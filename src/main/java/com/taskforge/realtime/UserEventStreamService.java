package com.taskforge.realtime;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.taskforge.common.exception.BadRequestException;
import com.taskforge.common.exception.GeneralErrorCode;

@Service
public class UserEventStreamService {

	private final UserEventStreamRegistry registry;
	private final Optional<EventReplaySource> replaySource;
	private final RealtimeProperties properties;

	public UserEventStreamService(UserEventStreamRegistry registry, Optional<EventReplaySource> replaySource,
			RealtimeProperties properties) {
		this.registry = registry;
		this.replaySource = replaySource;
		this.properties = properties;
	}

	// The stream times out exactly when the access token that opened it
	// expires. The JWT filter only authenticates a request once, when it
	// arrives - without this, one valid token would keep a stream alive
	// indefinitely after the token itself stopped being accepted anywhere
	// else. Same effective semantics as every REST endpoint: access tokens
	// are stateless and honored until exp, no longer. The client reconnects
	// with a refreshed token (and Last-Event-ID, so nothing is missed).
	public SseEmitter open(UUID userId, Instant tokenExpiresAt, String lastEventId) {
		// Validated before registering: failing after registration would leave
		// an emitter in the registry that no request ever completes.
		Optional<EventReplaySource> replay = lastEventId == null ? Optional.empty() : replaySource;
		if (replay.isPresent() && !replay.get().isValidEventId(lastEventId)) {
			throw new BadRequestException(GeneralErrorCode.VALIDATION_FAILED, "Invalid Last-Event-ID");
		}

		long timeoutMillis = Math.max(Duration.between(Instant.now(), tokenExpiresAt).toMillis(), 1);
		SseEmitter emitter = new SseEmitter(timeoutMillis);
		registry.register(userId, emitter);

		// Sent immediately so the response (headers included) is committed
		// now, not whenever the first real event happens to arrive.
		registry.send(userId, emitter, SseEmitter.event().comment("connected"));

		replay.ifPresent(source -> replay(source, userId, emitter, lastEventId));
		return emitter;
	}

	// Registered before this query runs, not after: an event published in
	// between then arrives both live and in the replay (the client
	// de-duplicates by event id), instead of in neither - a duplicate is
	// harmless, a gap isn't.
	private void replay(EventReplaySource source, UUID userId, SseEmitter emitter, String lastEventId) {
		// One extra row tells us whether the backlog exceeds what a replay
		// should push down a single stream.
		List<UserEvent> missed = source.eventsAfter(userId, lastEventId, properties.replayLimit() + 1);

		if (missed.size() > properties.replayLimit()) {
			// Too far behind to catch up event-by-event: tell the client to
			// reload from the REST endpoints instead of replaying a partial
			// backlog it can't tell is partial.
			registry.send(userId, emitter, SseEmitter.event().name("resync").data(""));
			return;
		}
		for (UserEvent event : missed) {
			registry.send(userId, emitter, UserEventStreamRegistry.toSse(event));
		}
	}

}
