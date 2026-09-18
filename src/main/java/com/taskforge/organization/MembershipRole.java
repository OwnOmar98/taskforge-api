package com.taskforge.organization;

public enum MembershipRole {

	OWNER(3),
	ADMIN(2),
	MEMBER(1),
	GUEST(0);

	private final int rank;

	MembershipRole(int rank) {
		this.rank = rank;
	}

	// Explicit ranks rather than ordinal(): reordering these constants later
	// must never silently change who outranks whom.
	public boolean isAtLeast(MembershipRole other) {
		return this.rank >= other.rank;
	}

}
