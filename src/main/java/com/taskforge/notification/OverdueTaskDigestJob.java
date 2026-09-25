package com.taskforge.notification;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.taskforge.task.Task;
import com.taskforge.task.TaskRepository;
import com.taskforge.task.TaskStatus;

import tools.jackson.databind.ObjectMapper;

@Component
public class OverdueTaskDigestJob {

	private static final Logger log = LoggerFactory.getLogger(OverdueTaskDigestJob.class);

	private final TaskRepository taskRepository;
	private final NotificationRepository notificationRepository;
	private final ObjectMapper objectMapper;

	public OverdueTaskDigestJob(TaskRepository taskRepository, NotificationRepository notificationRepository,
			ObjectMapper objectMapper) {
		this.taskRepository = taskRepository;
		this.notificationRepository = notificationRepository;
		this.objectMapper = objectMapper;
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
		List<Task> overdueTasks = taskRepository.findByDueDateBeforeAndStatusNotAndAssigneeIsNotNull(today,
				TaskStatus.DONE);

		Map<AssigneeInOrganization, List<Task>> byAssignee = overdueTasks.stream()
				.collect(Collectors.groupingBy(
						task -> new AssigneeInOrganization(task.getAssignee().getId(),
								task.getProject().getOrganization().getId())));

		int sent = 0;
		int skipped = 0;
		for (Map.Entry<AssigneeInOrganization, List<Task>> entry : byAssignee.entrySet()) {
			int rowsInserted = notificationRepository.insertOverdueDigestIfAbsent(UUID.randomUUID(),
					entry.getKey().userId(), entry.getKey().organizationId(), buildPayload(entry.getValue()), today);
			if (rowsInserted > 0) {
				sent++;
			}
			else {
				skipped++;
			}
		}

		log.info("Overdue task digest for {}: {} sent, {} already sent today", today, sent, skipped);
	}

	private String buildPayload(List<Task> overdueTasks) {
		List<UUID> taskIds = overdueTasks.stream().map(Task::getId).toList();
		return objectMapper.writeValueAsString(Map.of("taskCount", taskIds.size(), "taskIds", taskIds));
	}

	private record AssigneeInOrganization(UUID userId, UUID organizationId) {
	}

}
