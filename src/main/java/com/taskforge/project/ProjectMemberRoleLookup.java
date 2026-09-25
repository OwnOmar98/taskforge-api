package com.taskforge.project;

// See MembershipRoleLookup for why this wrapper exists: a bare enum cached
// directly as the top-level value round-trips through Redis as a plain
// String instead of the enum, since Jackson's default typing treats enums
// as "final" and skips embedding type info.
public record ProjectMemberRoleLookup(ProjectMemberRole role) {
}
