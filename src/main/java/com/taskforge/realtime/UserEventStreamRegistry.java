package com.taskforge.realtime;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.SmartLifecycle;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import com.taskforge.common.exception.TooManyRequestsException;

// The streams open on *this* instance only - which instance a user happens to
// be connected to is exactly what Redis pub/sub exists to not need to know.
// A set per user, not one emitter: the same user can have several tabs or
// devices open at once.
//
// A SmartLifecycle at the default phase so it stops before Boot's
// WebServerGracefulShutdownLifecycle (a lower phase, stopped later). Open
// streams are in-flight async requests, so without this, graceful shutdown
// would sit out its whole timeout waiting on connections that never finish
// on their own. Completing them first lets clients reconnect - to another
// instance - immediately instead.
@Component
public class UserEventStreamRegistry implements SmartLifecycle {

	private final Map<UUID, Set<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();
	private final RealtimeProperties properties;
	// Starts out accepting, not only once start() runs: this bean starts
	// after the web server, and a stream request arriving in between is
	// perfectly valid.
	private volatile boolean accepting = true;

	public UserEventStreamRegistry(RealtimeProperties properties) {
		this.properties = properties;
	}

	// Checked and added in one compute() call so two concurrent connects from
	// the same user can't both pass the cap check and both get in.
	void register(UUID userId, SseEmitter emitter) {
		if (!accepting) {
			emitter.complete();
			return;
		}

		emittersByUser.compute(userId, (id, emitters) -> {
			Set<SseEmitter> current = emitters != null ? emitters : ConcurrentHashMap.newKeySet();
			if (current.size() >= properties.maxConnectionsPerUser()) {
				// A dropped client's slot is only reclaimed once a send to it
				// fails, and the heartbeat is what guarantees a send happens -
				// so one heartbeat interval is the honest upper bound on when a
				// stale slot frees up.
				throw new TooManyRequestsException(RealtimeErrorCode.TOO_MANY_STREAMS,
						RealtimeErrorCode.TOO_MANY_STREAMS.defaultMessage(),
						properties.heartbeatInterval().toSeconds());
			}
			current.add(emitter);
			return current;
		});

		// Without completing on timeout, the servlet container's async timeout
		// surfaces as an AsyncRequestTimeoutException that the error handling
		// would then try to write as a ProblemDetail onto an already-committed
		// event stream.
		emitter.onTimeout(emitter::complete);
		// Fires on every outcome - normal completion, timeout, and network error.
		emitter.onCompletion(() -> unregister(userId, emitter));
	}

	void deliver(UUID userId, UserEvent event) {
		Set<SseEmitter> emitters = emittersByUser.get(userId);
		if (emitters == null) {
			return;
		}
		for (SseEmitter emitter : emitters) {
			send(userId, emitter, toSse(event));
		}
	}

	void send(UUID userId, SseEmitter emitter, SseEventBuilder event) {
		try {
			emitter.send(event);
		}
		catch (IOException | IllegalStateException e) {
			// The client is gone (IOException) or the emitter already completed
			// (IllegalStateException) - either way it can't receive anything
			// more, so stop holding it. Deliberately no completeWithError here:
			// a failed write already makes Spring end the async request itself,
			// and a second completion from this thread is exactly what Tomcat
			// rejects ("AsyncContext after an error had occurred").
			unregister(userId, emitter);
		}
	}

	static SseEventBuilder toSse(UserEvent event) {
		SseEventBuilder sse = SseEmitter.event().name(event.type().wireName());
		// No id line at all for signals - see UserEvent for why an absent id
		// (rather than an empty one) matters to the client's Last-Event-ID.
		if (event.id() != null) {
			sse.id(event.id());
		}
		return sse.data(event.data(), MediaType.APPLICATION_JSON);
	}

	// Keeps idle connections from being cut by proxies/load balancers that
	// close anything quiet for ~60s, and is how a client that vanished
	// without a clean disconnect gets noticed at all: the write fails.
	@Scheduled(fixedRateString = "${app.realtime.heartbeat-interval}")
	void heartbeat() {
		emittersByUser.forEach((userId, emitters) -> {
			for (SseEmitter emitter : emitters) {
				send(userId, emitter, SseEmitter.event().comment("heartbeat"));
			}
		});
	}

	private void unregister(UUID userId, SseEmitter emitter) {
		emittersByUser.computeIfPresent(userId, (id, emitters) -> {
			emitters.remove(emitter);
			return emitters.isEmpty() ? null : emitters;
		});
	}

	@Override
	public void start() {
		accepting = true;
	}

	@Override
	public void stop() {
		accepting = false;
		emittersByUser.values().forEach(emitters -> emitters.forEach(SseEmitter::complete));
		emittersByUser.clear();
	}

	@Override
	public boolean isRunning() {
		return accepting;
	}

}
