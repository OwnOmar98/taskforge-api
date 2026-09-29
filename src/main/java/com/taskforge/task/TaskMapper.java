package com.taskforge.task;

import java.util.List;
import java.util.UUID;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.taskforge.common.mapper.MapStructConfig;
import com.taskforge.media.Media;
import com.taskforge.task.dto.LabelResponse;
import com.taskforge.task.dto.TaskAttachmentResponse;
import com.taskforge.task.dto.TaskCommentResponse;
import com.taskforge.task.dto.TaskResponse;
import com.taskforge.task.dto.TaskSummaryProjection;

@Mapper(config = MapStructConfig.class)
public interface TaskMapper {

	// projectId is passed in rather than read off task.getProject(): every
	// caller already has it from the route, so there's no reason to touch the
	// project association just to echo an id back.
	@Mapping(target = "projectId", source = "projectId")
	@Mapping(target = "assigneeId", source = "task.assignee.id")
	@Mapping(target = "assigneeEmail", source = "task.assignee.email")
	TaskResponse toResponse(Task task, UUID projectId);

	@Mapping(target = "labelNames", source = "labelNames")
	@Mapping(target = "assigneeId", source = "task.assignee.id")
	@Mapping(target = "assigneeEmail", source = "task.assignee.email")
	TaskSummaryProjection toSummary(Task task, List<String> labelNames);

	@Mapping(target = "authorId", source = "author.id")
	@Mapping(target = "authorEmail", source = "author.email")
	TaskCommentResponse toResponse(TaskComment comment);

	@Mapping(target = "organizationId", source = "organization.id")
	LabelResponse toResponse(Label label);

	TaskAttachmentResponse toAttachmentResponse(Media media);

}
