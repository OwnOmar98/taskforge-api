package com.taskforge.task;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskforge.audit.events.EntityDeletedEvent;
import com.taskforge.audit.events.EntityRestoredEvent;
import com.taskforge.audit.events.TaskStatusChangedEvent;
import com.taskforge.common.PageResponse;
import com.taskforge.common.exception.ConflictException;
import com.taskforge.common.exception.GeneralErrorCode;
import com.taskforge.common.exception.ResourceNotFoundException;
import com.taskforge.project.Project;
import com.taskforge.project.ProjectMemberRepository;
import com.taskforge.project.ProjectRepository;
import com.taskforge.realtime.UserEvent;
import com.taskforge.realtime.UserEventPublisher;
import com.taskforge.realtime.UserEventType;
import com.taskforge.task.dto.TaskResponse;
import com.taskforge.task.dto.TaskSummaryProjection;
import com.taskforge.task.dto.UpdateTaskRequest;
import com.taskforge.task.events.TaskAssignedEvent;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class TaskService {

	private final TaskRepository taskRepository;
	private final ProjectRepository projectRepository;
	private final ProjectMemberRepository projectMemberRepository;
	private final UserRepository userRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final MeterRegistry meterRegistry;
	private final UserEventPublisher userEventPublisher;
	private final TaskMapper taskMapper;

	public TaskService(TaskRepository taskRepository, ProjectRepository projectRepository,
			ProjectMemberRepository projectMemberRepository, UserRepository userRepository,
			ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry,
			UserEventPublisher userEventPublisher, TaskMapper taskMapper) {
		this.taskRepository = taskRepository;
		this.projectRepository = projectRepository;
		this.projectMemberRepository = projectMemberRepository;
		this.userRepository = userRepository;
		this.eventPublisher = eventPublisher;
		this.meterRegistry = meterRegistry;
		this.userEventPublisher = userEventPublisher;
		this.taskMapper = taskMapper;
	}

	@PreAuthorize("hasPermission(#projectId, 'Project', 'CONTRIBUTE')")
	@Transactional
	public TaskResponse createTask(UUID projectId, String title, String description, TaskPriority priority,
			LocalDate dueDate, UUID assigneeId, UUID actorId) {
		Timer.Sample sample = Timer.start(meterRegistry);
		try {
			Project project = projectRepository.findById(projectId)
					.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
							"Project not found"));

			Task task = new Task(project, title, description, priority, dueDate);

			if (assigneeId != null) {
				task.assignTo(validateAssigneeOrThrow(projectId, assigneeId));
			}

			Task saved = taskRepository.save(task);
			if (assigneeId != null) {
				eventPublisher.publishEvent(new TaskAssignedEvent(saved.getId(), assigneeId, actorId));
			}

			return taskMapper.toResponse(saved, projectId);
		}
		finally {
			// Covers the whole method, not just the insert - a slow permission
			// check or project lookup is just as real a contributor to task
			// creation latency as the write itself.
			sample.stop(meterRegistry.timer("task.creation.duration"));
		}
	}

	@PreAuthorize("hasPermission(#projectId, 'Project', 'MEMBER')")
	@Transactional(readOnly = true)
	public PageResponse<TaskSummaryProjection> listTasks(UUID projectId, TaskStatus status, TaskPriority priority,
			UUID assigneeId, UUID labelId, Pageable pageable) {
		List<Specification<Task>> specs = new ArrayList<>();
		specs.add(TaskSpecifications.belongsToProject(projectId));
		if (status != null) {
			specs.add(TaskSpecifications.hasStatus(status));
		}
		if (priority != null) {
			specs.add(TaskSpecifications.hasPriority(priority));
		}
		if (assigneeId != null) {
			specs.add(TaskSpecifications.hasAssignee(assigneeId));
		}
		if (labelId != null) {
			specs.add(TaskSpecifications.hasLabel(labelId));
		}

		Page<Task> page = taskRepository.findAll(Specification.allOf(specs), pageable);

		List<UUID> taskIds = page.getContent().stream().map(Task::getId).toList();
		// An empty IN clause is invalid JPQL, and an empty page is common (e.g. no
		// results for the given filters), so skip the query entirely in that case.
		Map<UUID, List<String>> labelNamesByTaskId = taskIds.isEmpty() ? Map.of()
				: taskRepository.findLabelNamesForTasks(taskIds).stream()
						.collect(Collectors.groupingBy(TaskRepository.TaskLabelRow::getTaskId,
								Collectors.mapping(TaskRepository.TaskLabelRow::getLabelName, Collectors.toList())));

		return PageResponse.from(page.map(task -> taskMapper.toSummary(task,
				labelNamesByTaskId.getOrDefault(task.getId(), List.of()).stream().sorted().toList())));
	}

	@PreAuthorize("hasPermission(#projectId, 'Project', 'MEMBER')")
	@Transactional(readOnly = true)
	public TaskResponse getTask(UUID projectId, UUID taskId) {
		return taskMapper.toResponse(findTaskInProjectOrThrow(projectId, taskId), projectId);
	}

	@PreAuthorize("hasPermission(#projectId, 'Project', 'CONTRIBUTE')")
	@Transactional
	public TaskResponse updateTask(UUID projectId, UUID taskId, UpdateTaskRequest request, UUID actorId) {
		Task task = findTaskInProjectOrThrow(projectId, taskId);
		User assigneeBefore = task.getAssignee();

		if (!task.getVersion().equals(request.version())) {
			throw new ConflictException(TaskErrorCode.STALE_TASK_VERSION,
					TaskErrorCode.STALE_TASK_VERSION.defaultMessage());
		}

		if (request.title() != null) {
			task.rename(request.title());
		}
		if (request.description() != null) {
			task.updateDescription(request.description());
		}
		if (request.status() != null && request.status() != task.getStatus()) {
			TaskStatus oldStatus = task.getStatus();
			task.changeStatus(request.status());
			eventPublisher.publishEvent(new TaskStatusChangedEvent(task.getProject().getOrganization().getId(),
					actorId, task.getId(), oldStatus, request.status()));
		}
		if (request.priority() != null) {
			task.changePriority(request.priority());
		}
		if (request.dueDate() != null) {
			task.changeDueDate(request.dueDate());
		}
		if (request.assigneeId() != null) {
			User previousAssignee = task.getAssignee();
			if (previousAssignee == null || !previousAssignee.getId().equals(request.assigneeId())) {
				task.assignTo(validateAssigneeOrThrow(projectId, request.assigneeId()));
				eventPublisher.publishEvent(new TaskAssignedEvent(task.getId(), request.assigneeId(), actorId));
			}
		}

		// Same reasoning as ProjectService.updateProject: force the version bump
		// to happen now so the response reflects it, not the stale pre-flush value.
		taskRepository.flush();
		// An unchanged version after the flush means nothing was actually
		// written (every provided field matched its current value), so there's
		// nothing for anyone to refetch.
		if (!task.getVersion().equals(request.version())) {
			publishTaskUpdated(task, projectId, assigneeBefore, actorId);
		}
		return taskMapper.toResponse(task, projectId);
	}

	// A signal, not a notification: it tells the people whose task lists this
	// change affects to refetch, and isn't recorded anywhere. The previous
	// assignee is included on a reassignment - the task just left their list.
	// The actor is skipped for the same reason notifications skip them: they
	// already have the result in their own response.
	private void publishTaskUpdated(Task task, UUID projectId, User assigneeBefore, UUID actorId) {
		Set<UUID> recipients = new HashSet<>();
		if (assigneeBefore != null) {
			recipients.add(assigneeBefore.getId());
		}
		if (task.getAssignee() != null) {
			recipients.add(task.getAssignee().getId());
		}
		recipients.remove(actorId);

		Map<String, Object> data = Map.of("taskId", task.getId(), "projectId", projectId, "version",
				task.getVersion());
		for (UUID recipient : recipients) {
			userEventPublisher.publishAfterCommit(recipient, UserEvent.signal(UserEventType.TASK_UPDATED, data));
		}
	}

	@PreAuthorize("hasPermission(#projectId, 'Project', 'CONTRIBUTE')")
	@Transactional
	public void deleteTask(UUID projectId, UUID taskId, UUID actorId) {
		Task task = findTaskInProjectOrThrow(projectId, taskId);
		task.markDeleted(actorId);

		eventPublisher.publishEvent(new EntityDeletedEvent(task.getProject().getOrganization().getId(), actorId,
				"Task", task.getId(), Map.of("title", task.getTitle())));
		signalAssignee(task, actorId,
				UserEvent.signal(UserEventType.TASK_DELETED, Map.of("taskId", task.getId(), "projectId", projectId)));
	}

	// Same CONTRIBUTE rule as deleting. A task whose project is itself deleted
	// can't get this far: a deleted project has no members for authorization,
	// so the project has to be restored first.
	@PreAuthorize("hasPermission(#projectId, 'Project', 'CONTRIBUTE')")
	@Transactional
	public TaskResponse restoreTask(UUID projectId, UUID taskId, UUID actorId) {
		Task task = taskRepository.findDeletedByIdAndProjectId(taskId, projectId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Deleted task not found"));
		task.restore();
		taskRepository.flush();

		eventPublisher.publishEvent(new EntityRestoredEvent(task.getProject().getOrganization().getId(), actorId,
				"Task", task.getId(), Map.of("title", task.getTitle())));
		// A restored task reappears in its assignee's list - the same "refetch"
		// signal as any other change to it.
		signalAssignee(task, actorId, UserEvent.signal(UserEventType.TASK_UPDATED,
				Map.of("taskId", task.getId(), "projectId", projectId, "version", task.getVersion())));
		return taskMapper.toResponse(task, projectId);
	}

	private void signalAssignee(Task task, UUID actorId, UserEvent event) {
		User assignee = task.getAssignee();
		if (assignee != null && !assignee.getId().equals(actorId)) {
			userEventPublisher.publishAfterCommit(assignee.getId(), event);
		}
	}

	// Deliberately here, not a validation annotation: "is this user a member of
	// this project" needs a database lookup and belongs with the rest of the
	// domain logic, unlike the due-date check, which never needs to look
	// beyond the field it's validating.
	private User validateAssigneeOrThrow(UUID projectId, UUID assigneeId) {
		User assignee = userRepository.findById(assigneeId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"User not found"));

		boolean isProjectMember = projectMemberRepository.findByProject_IdAndUser_Id(projectId, assigneeId)
				.isPresent();
		if (!isProjectMember) {
			throw new ConflictException(TaskErrorCode.ASSIGNEE_NOT_A_PROJECT_MEMBER,
					TaskErrorCode.ASSIGNEE_NOT_A_PROJECT_MEMBER.defaultMessage());
		}

		return assignee;
	}

	private Task findTaskInProjectOrThrow(UUID projectId, UUID taskId) {
		return taskRepository.findByIdAndProject_Id(taskId, projectId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Task not found"));
	}

}
