package com.taskforge.organization;

// A thin wrapper, not a bare MembershipRole, because the Redis cache
// serializer needs a real declared field type to deserialize back into.
// An enum cached directly as the top-level value round-trips as a plain
// String instead - Jackson's default typing treats enums as "final" and
// skips embedding type info, but generic Object-typed deserialization then
// has nothing telling it to reconstruct the enum rather than leave it as a
// string.
public record MembershipRoleLookup(MembershipRole role) {
}
