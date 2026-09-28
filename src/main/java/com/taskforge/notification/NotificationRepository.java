package com.taskforge.notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

	Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

	long countByUserId(UUID userId);

	// A bulk update, not load-each-then-save: marking N rows read shouldn't
	// cost N SELECTs plus N UPDATEs. clearAutomatically is needed because a
	// bulk JPQL update bypasses the persistence context entirely - it goes
	// straight to the database, so any Notification entities already loaded
	// into this session would otherwise keep their stale in-memory readAt
	// after this runs, with nothing forcing them to reload.
	@Modifying(clearAutomatically = true)
	@Query("update Notification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
	void markAllAsRead(@Param("userId") UUID userId, @Param("now") Instant now);

	// limit is passed as (requested size + 1): fetching one extra row is how
	// the caller knows whether there's a next page, without a separate
	// COUNT(*) query.
	@Query(value = "select * from notifications where user_id = :userId "
			+ "order by created_at desc, id desc limit :limit", nativeQuery = true)
	List<Notification> findFirstPageByUserId(@Param("userId") UUID userId, @Param("limit") int limit);

	// created_at alone can't break a tie between two rows created in the same
	// instant, so id is a second, arbitrary-but-stable tiebreaker - not a
	// substitute ordering, since ids are random UUIDs with no insertion order
	// of their own.
	@Query(value = "select * from notifications where user_id = :userId "
			+ "and (created_at < :cursorCreatedAt or (created_at = :cursorCreatedAt and id < :cursorId)) "
			+ "order by created_at desc, id desc limit :limit", nativeQuery = true)
	List<Notification> findNextPageByUserId(@Param("userId") UUID userId,
			@Param("cursorCreatedAt") Instant cursorCreatedAt, @Param("cursorId") UUID cursorId,
			@Param("limit") int limit);

	// The reverse of findNextPageByUserId: rows strictly newer than a given
	// (createdAt, id), oldest first - for replaying what a reconnecting
	// real-time stream missed, in the order it would have seen them live.
	@Query(value = "select * from notifications where user_id = :userId "
			+ "and (created_at > :afterCreatedAt or (created_at = :afterCreatedAt and id > :afterId)) "
			+ "order by created_at asc, id asc limit :limit", nativeQuery = true)
	List<Notification> findNewerThanByUserId(@Param("userId") UUID userId,
			@Param("afterCreatedAt") Instant afterCreatedAt, @Param("afterId") UUID afterId,
			@Param("limit") int limit);

}
