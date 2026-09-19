package com.taskforge.task;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskforge.common.exception.ConflictException;
import com.taskforge.common.exception.GeneralErrorCode;
import com.taskforge.common.exception.ResourceNotFoundException;
import com.taskforge.project.Project;
import com.taskforge.project.ProjectMemberRepository;
import com.taskforge.project.ProjectRepository;
import com.taskforge.task.dto.TaskResponse;
import com.taskforge.task.dto.UpdateTaskRequest;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

@Service
public class TaskService {

	private final TaskRepository taskRepository;
	private final ProjectRepository projectRepository;
	private final ProjectMemberRepository projectMemberRepository;
	private final UserRepository userRepository;

	public TaskService(TaskRepository taskRepository, ProjectRepository projectRepository,
			ProjectMemberRepository projectMemberRepository, UserRepository userRepository) {
		this.taskRepository = taskRepository;
		this.projectRepository = projectRepository;
		this.projectMemberRepository = projectMemberRepository;
		this.userRepository = userRepository;
	}

	@PreAuthorize("hasPermission(#projectId, 'Project', 'MEMBER')")
	@Transactional
	public TaskResponse createTask(UUID projectId, String title, String description, TaskPriority priority,
			LocalDate dueDate, UUID assigneeId) {
		Project project = projectRepository.findById(projectId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Project not found"));

		Task task = new Task(project, title, description, priority, dueDate);

		if (assigneeId != null) {
			task.assignTo(validateAssigneeOrThrow(projectId, assigneeId));
		}

		return toResponse(taskRepository.save(task), projectId);
	}

	@PreAuthorize("hasPermission(#projectId, 'Project', 'MEMBER')")
	@Transactional(readOnly = true)
	public List<TaskResponse> listTasks(UUID projectId) {
		return taskRepository.findByProjectIdWithAssignee(projectId).stream()
				.map(task -> toResponse(task, projectId))
				.toList();
	}

	@PreAuthorize("hasPermission(#projectId, 'Project', 'MEMBER')")
	@Transactional(readOnly = true)
	public TaskResponse getTask(UUID projectId, UUID taskId) {
		return toResponse(findTaskInProjectOrThrow(projectId, taskId), projectId);
	}

	@PreAuthorize("hasPermission(#projectId, 'Project', 'MEMBER')")
	@Transactional
	public TaskResponse updateTask(UUID projectId, UUID taskId, UpdateTaskRequest request) {
		Task task = findTaskInProjectOrThrow(projectId, taskId);

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
		if (request.status() != null) {
			task.changeStatus(request.status());
		}
		if (request.priority() != null) {
			task.changePriority(request.priority());
		}
		if (request.dueDate() != null) {
			task.changeDueDate(request.dueDate());
		}
		if (request.assigneeId() != null) {
			task.assignTo(validateAssigneeOrThrow(projectId, request.assigneeId()));
		}

		// Same reasoning as ProjectService.updateProject: force the version bump
		// to happen now so the response reflects it, not the stale pre-flush value.
		taskRepository.flush();
		return toResponse(task, projectId);
	}

	@PreAuthorize("hasPermission(#projectId, 'Project', 'MEMBER')")
	@Transactional
	public void deleteTask(UUID projectId, UUID taskId) {
		taskRepository.delete(findTaskInProjectOrThrow(projectId, taskId));
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

	private TaskResponse toResponse(Task task, UUID projectId) {
		User assignee = task.getAssignee();
		return new TaskResponse(task.getId(), projectId, task.getTitle(), task.getDescription(), task.getStatus(),
				task.getPriority(), task.getDueDate(), assignee == null ? null : assignee.getId(),
				assignee == null ? null : assignee.getEmail(), task.getVersion(), task.getCreatedAt());
	}

}
