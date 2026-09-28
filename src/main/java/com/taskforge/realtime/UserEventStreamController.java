package com.taskforge.realtime;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.taskforge.security.CurrentUserId;
import com.taskforge.security.JwtService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

// One stream per user for every kind of real-time event, not one endpoint per
// feature: clients hold a single connection and dispatch on the SSE event
// name (see UserEventType).
//
// Bearer header auth like every other endpoint, so a browser's native
// EventSource (which can't set headers) needs a fetch-based SSE client
// instead. A ?token= query param was deliberately not added as a workaround:
// URLs end up in proxy and access logs, headers don't.
@Tag(name = "Real-time events")
@RestController
public class UserEventStreamController {

	private final UserEventStreamService streamService;
	private final JwtService jwtService;

	public UserEventStreamController(UserEventStreamService streamService, JwtService jwtService) {
		this.streamService = streamService;
		this.jwtService = jwtService;
	}

	// JwtAuthenticationFilter has already validated the token by the time this
	// runs - it's only re-read here for its exp.
	@Operation(summary = "Stream the current user's real-time events as Server-Sent Events")
	@GetMapping(path = "/api/v1/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream(@CurrentUserId UUID currentUserId,
			@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
			@RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
		String token = authorization.substring("Bearer ".length());
		return streamService.open(currentUserId, jwtService.extractExpiration(token), lastEventId);
	}

}
