package com.taskforge.project;

public enum ProjectMemberRole {

	LEAD(2),
	CONTRIBUTOR(1),
	VIEWER(0);

	private final int rank;

	ProjectMemberRole(int rank) {
		this.rank = rank;
	}

	public boolean isAtLeast(ProjectMemberRole other) {
		return this.rank >= other.rank;
	}

}
