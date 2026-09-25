package com.taskforge.notification;

import java.time.Instant;
import java.time.LocalDate;
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

	// ON CONFLICT DO NOTHING, not a check-then-insert in Java: a check-then-act
	// in application code can't survive two overlapping runs of the same job
	// (e.g. a retry after a crash) racing each other between the check and the
	// insert. The database constraint (see the migration) is what actually
	// makes this safe - this just tells Postgres to treat hitting it as
	// "already sent today" rather than an error. Returns the row count (0 or
	// 1) so the caller can tell inserted from skipped, e.g. for logging.
	@Modifying
	@Query(value = "insert into notifications (id, user_id, organization_id, type, payload, created_at, digest_date) "
			+ "values (:id, :userId, :organizationId, 'OVERDUE_TASK_DIGEST', CAST(:payload AS jsonb), now(), :digestDate) "
			+ "on conflict (user_id, organization_id, digest_date) do nothing", nativeQuery = true)
	int insertOverdueDigestIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId,
			@Param("organizationId") UUID organizationId, @Param("payload") String payload,
			@Param("digestDate") LocalDate digestDate);

}
