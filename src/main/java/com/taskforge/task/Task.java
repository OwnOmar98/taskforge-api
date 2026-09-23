package com.taskforge.task;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.taskforge.common.Auditable;
import com.taskforge.media.Media;
import com.taskforge.project.Project;
import com.taskforge.user.User;

@Entity
@Table(name = "tasks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Task extends Auditable {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	private Project project;

	@Column(nullable = false)
	private String title;

	@Column
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 50)
	private TaskStatus status;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 50)
	private TaskPriority priority;

	@Column(name = "due_date")
	private LocalDate dueDate;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "assignee_id")
	private User assignee;

	@Version
	@Column(nullable = false)
	private Long version;

	// Owned lifecycle: a comment has no existence apart from its task, so
	// deleting the task deletes its comments, and removing one from this list
	// deletes it outright (orphanRemoval) rather than leaving a dangling row.
	@OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
	private List<TaskComment> comments = new ArrayList<>();

	// Independent lifecycle: labels are shared org-level reference data, so
	// no cascade - deleting a task must only remove the join-table row, never
	// the label itself, which other tasks may still reference.
	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(name = "task_labels", joinColumns = @JoinColumn(name = "task_id"),
			inverseJoinColumns = @JoinColumn(name = "label_id"))
	private Set<Label> labels = new HashSet<>();

	// Owned lifecycle, same reasoning as comments: an attachment is only ever
	// linked to the one task that uploaded it today, so deleting the task
	// deletes the Media row too. This does NOT delete the underlying object in
	// storage - that cleanup isn't built yet, a known gap.
	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
	@JoinTable(name = "task_attachments", joinColumns = @JoinColumn(name = "task_id"),
			inverseJoinColumns = @JoinColumn(name = "media_id"))
	private List<Media> attachments = new ArrayList<>();

	public Task(Project project, String title, String description, TaskPriority priority, LocalDate dueDate) {
		this.project = project;
		this.title = title;
		this.description = description;
		this.status = TaskStatus.TODO;
		this.priority = priority;
		this.dueDate = dueDate;
	}

	public void rename(String newTitle) {
		this.title = newTitle;
	}

	public void updateDescription(String newDescription) {
		this.description = newDescription;
	}

	public void changeStatus(TaskStatus newStatus) {
		this.status = newStatus;
	}

	public void changePriority(TaskPriority newPriority) {
		this.priority = newPriority;
	}

	public void changeDueDate(LocalDate newDueDate) {
		this.dueDate = newDueDate;
	}

	public void assignTo(User newAssignee) {
		this.assignee = newAssignee;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Task other)) {
			return false;
		}
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
