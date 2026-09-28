package com.taskforge.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.taskforge.notification.dto.NotificationResponse;
import com.taskforge.realtime.UserEventPublisher;
import com.taskforge.task.Task;
import com.taskforge.task.TaskRepository;
import com.taskforge.task.TaskStatus;

import tools.jackson.databind.ObjectMapper;

@Component
public class OverdueTaskDigestJob {

	private static final Logger log = LoggerFactory.getLogger(OverdueTaskDigestJob.class);

	// ON CONFLICT DO NOTHING, not a check-then-insert in Java: a check-then-act
	// in application code can't survive two overlapping runs of the same job
	// (e.g. a retry after a crash) racing each other between the check and the
	// insert. The database constraint (see the migration) is what actually
	// makes this safe - this just tells Postgres to treat hitting it as
	// "already sent today" rather than an error.
	private static final String INSERT_DIGEST_SQL = "insert into notifications "
			+ "(id, user_id, organization_id, type, payload, created_at, digest_date) "
			+ "values (:id, :userId, :organizationId, 'OVERDUE_TASK_DIGEST', CAST(:payload AS jsonb), :createdAt, :digestDate) "
			+ "on conflict (user_id, organization_id, digest_date) do nothing";

	private final TaskRepository taskRepository;
	private final NamedParameterJdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;
	private final UserEventPublisher userEventPublisher;
	// Bounds how many overdue tasks are pulled into the persistence context at
	// once. This runs globally across every organization, so without a
	// ceiling a single day's worth of overdue tasks across every tenant would
	// load as one unbounded result set. Digest payloads are built from the
	// lightweight (assignee, task id) pairs extracted per page, not from
	// retained Task entities, so memory stays bounded by this page size
	// regardless of the total overdue count. Configurable (not a constant) so
	// a test can force multiple pages with a handful of rows instead of
	// needing hundreds of tasks to prove the paging loop actually pages.
	private final int pageSize;

	public OverdueTaskDigestJob(TaskRepository taskRepository, NamedParameterJdbcTemplate jdbcTemplate,
			ObjectMapper objectMapper, UserEventPublisher userEventPublisher,
			@Value("${app.notification.overdue-digest.page-size:500}") int pageSize) {
		this.taskRepository = taskRepository;
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
		this.userEventPublisher = userEventPublisher;
		this.pageSize = pageSize;
	}

	@Scheduled(cron = "0 0 6 * * *")
	public void run() {
		runForDate(LocalDate.now());
	}

	// A separate, public, directly-callable method - not folded into run() -
	// so a test can trigger a real run for a specific date without waiting on
	// the cron schedule, per this PR's own definition of done.
	@Transactional
	public void runForDate(LocalDate today) {
		Map<AssigneeInOrganization, List<UUID>> taskIdsByAssignee = collectOverdueTaskIdsByAssignee(today);

		if (taskIdsByAssignee.isEmpty()) {
			log.info("Overdue task digest for {}: nothing overdue", today);
			return;
		}

		// createdAt is set here rather than by now() in the SQL so the exact
		// value is known up front for the real-time push below, without
		// reading the rows back.
		Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
		List<DigestRow> digests = taskIdsByAssignee.entrySet().stream()
				.map(entry -> new DigestRow(UUID.randomUUID(), entry.getKey(),
						Map.of("taskCount", entry.getValue().size(), "taskIds", entry.getValue())))
				.toList();

		// One batched round trip instead of one insert per assignee - the
		// driver sends the whole set of digest rows together, with
		// ON CONFLICT DO NOTHING still deduplicating a rerun for the same day
		// exactly as a single-row insert would.
		SqlParameterSource[] batchArgs = digests.stream()
				.map(digest -> digestInsertArgs(digest, createdAt, today))
				.toArray(SqlParameterSource[]::new);
		int[] rowsInserted = jdbcTemplate.batchUpdate(INSERT_DIGEST_SQL, batchArgs);

		// Only rows this run actually inserted: one that ON CONFLICT skipped
		// was already sent (and pushed) by an earlier run for the same day.
		for (int i = 0; i < digests.size(); i++) {
			if (rowsInserted[i] > 0) {
				DigestRow digest = digests.get(i);
				userEventPublisher.publishAfterCommit(digest.assignee().userId(),
						NotificationReplaySource.toEvent(new NotificationResponse(digest.id(),
								NotificationType.OVERDUE_TASK_DIGEST, digest.payload(), null, createdAt)));
			}
		}

		long sent = IntStream.of(rowsInserted).filter(rows -> rows > 0).count();
		log.info("Overdue task digest for {}: {} sent, {} already sent today", today, sent,
				rowsInserted.length - sent);
	}

	private Map<AssigneeInOrganization, List<UUID>> collectOverdueTaskIdsByAssignee(LocalDate today) {
		Map<AssigneeInOrganization, List<UUID>> taskIdsByAssignee = new LinkedHashMap<>();

		Pageable pageable = PageRequest.of(0, pageSize, Sort.by("id"));
		Slice<Task> page;
		do {
			page = taskRepository.findByDueDateBeforeAndStatusNotAndAssigneeIsNotNull(today, TaskStatus.DONE,
					pageable);
			for (Task task : page.getContent()) {
				AssigneeInOrganization key = new AssigneeInOrganization(task.getAssignee().getId(),
						task.getProject().getOrganization().getId());
				taskIdsByAssignee.computeIfAbsent(key, k -> new ArrayList<>()).add(task.getId());
			}
			pageable = page.nextPageable();
		}
		while (page.hasNext());

		return taskIdsByAssignee;
	}

	private SqlParameterSource digestInsertArgs(DigestRow digest, Instant createdAt, LocalDate today) {
		return new MapSqlParameterSource().addValue("id", digest.id())
				.addValue("userId", digest.assignee().userId())
				.addValue("organizationId", digest.assignee().organizationId())
				.addValue("payload", objectMapper.writeValueAsString(digest.payload()))
				// pgjdbc has no setObject mapping for Instant; OffsetDateTime is
				// its supported equivalent for a timestamptz column.
				.addValue("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
				.addValue("digestDate", today);
	}

	private record AssigneeInOrganization(UUID userId, UUID organizationId) {
	}

	private record DigestRow(UUID id, AssigneeInOrganization assignee, Map<String, ?> payload) {
	}

}
