# TaskForge API

Multi-tenant SaaS task management REST API — a production-grade Spring Boot backend, built
incrementally as a series of independent, reviewable PRs.

## Tech stack

- Java 25, Spring Boot 4.1.1 (Spring Framework 7), Maven
- PostgreSQL + Flyway (versioned migrations, no auto-DDL)
- Redis (permission-check caching, rate limiting, pub/sub fan-out for real-time events)
- Spring Security / JWT (access + rotating refresh tokens)
- Resilience4j (retry/circuit breaker/timeout on outbound webhook delivery)
- S3-compatible object storage (DigitalOcean Spaces in prod; LocalStack in tests/local dev)
- springdoc-openapi (Swagger UI, `/v3/api-docs`)
- Testcontainers for integration tests; GitHub Actions CI (migration verification, full
  Testcontainers suite, Docker image build)

## Current status

Feature-complete: multi-tenant orgs/projects/tasks, JWT auth, role-based permissions, comments,
labels, attachments, notifications, a real-time SSE event stream, outbound webhooks, audit logging,
observability (actuator/metrics/health), a CI pipeline, and full OpenAPI documentation.

## Running locally

Requires Java 25.

```
./mvnw spring-boot:run
```

By default this runs without an active profile. The `dev` profile needs Postgres and Redis
(`docker compose up -d` starts both, plus a local LocalStack S3 on port 4566 for manually poking at
the storage feature) and the DO Spaces variables in `.env.example` sourced into your shell:

```
docker compose up -d
set -a && source .env && set +a
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

## Health check

```
curl http://localhost:8080/actuator/health
```

Should return `{"status":"UP"}`.

## API documentation

Interactive Swagger UI, with a working "Authorize" flow for the JWT bearer token returned by
`/api/v1/auth/login`:

```
http://localhost:8080/swagger-ui.html
```

Raw OpenAPI document: `http://localhost:8080/v3/api-docs`.

## API versioning

The API is versioned in the URI (`/api/v1/...`), not via a request header. A URI prefix is visible
in every request without needing to inspect headers, works with tools that don't easily let you set
custom headers (a browser address bar, `curl` without extra flags), and caches cleanly - a header-based
scheme (`Accept: application/vnd.taskforge.v1+json` or a custom `X-API-Version`) would avoid the URL
change a breaking v2 requires, but that tradeoff isn't worth it for an API with no existing v1 clients
to protect yet. A breaking change would ship as `/api/v2/...`, decided if and when one is actually
needed.

## Real-time events

`GET /api/v1/events/stream` is one Server-Sent Events stream per user, carrying every kind of
real-time event, delivered the moment it happens on any app instance (fanned out between instances
over Redis pub/sub):

```
curl -N -H "Authorization: Bearer $ACCESS_TOKEN" http://localhost:8080/api/v1/events/stream
```

The SSE `event:` field is the event type; clients dispatch on it. `data:` is that type's JSON payload.

| `event:`       | `id:`           | `data:`                                               | Sent to                                                                             |
|----------------|-----------------|-------------------------------------------------------|-------------------------------------------------------------------------------------|
| `notification` | notification id | same shape as one item of `GET /api/v1/notifications` | the notification's recipient                                                        |
| `task.updated` | none            | `{taskId, projectId, version}`                        | the task's assignee, plus the previous assignee on a reassignment, never the editor |
| `task.deleted` | none            | `{taskId, projectId}`                                 | the task's assignee, never the deleter                                              |
| `resync`       | none            | empty                                                 | a reconnecting client whose missed backlog exceeds `app.realtime.replay-limit`      |

- **Two kinds of event.** *Replayable* events (`notification`) are persisted and carry an `id`.
  *Signals* (`task.updated`, `task.deleted`) mean "refetch this", aren't stored and carry no `id`, so they never move
  the client's last-event-id.
- **Reconnecting:** send the last received id back as `Last-Event-ID` and missed notifications are
  replayed, oldest first. Events can occasionally arrive twice around a reconnect, so de-duplicate by
  id. A missed signal isn't replayed, so refetch whatever view is on screen after reconnecting. On
  `resync`, reload from the REST endpoints.
- **Lifetime:** a stream closes when the access token that opened it expires, so reconnect with a
  refreshed token (and `Last-Event-ID`). Streams also close when an instance shuts down; reconnecting
  lands on another instance.
- **Auth** is the usual bearer header. A browser's native `EventSource` can't set headers, so browser
  clients need a fetch-based SSE client; tokens in the query string are deliberately not accepted.
- Heartbeat comments are sent every `app.realtime.heartbeat-interval` to keep proxies from closing
  idle streams. Proxies in front of the app must not buffer `text/event-stream` responses.
- At most `app.realtime.max-connections-per-user` concurrent streams per user (429 beyond that).
- Best-effort: the app still boots and serves REST with Redis unreachable, and subscribes in the
  background once Redis is back.
- **Adding an event type:** add a constant to `UserEventType` and call
  `UserEventPublisher.publishAfterCommit(userId, UserEvent.signal(type, data))` where the change
  happens. Nothing else (Redis subscription, endpoint, registry) needs to change. A second
  *replayable* type would also need its own `EventReplaySource` and namespaced event ids.

## Deleting and restoring

Tasks and projects are soft-deleted: `DELETE` hides them everywhere, but the rows (and everything under
them: a task's comments and attachments, a project's tasks) stay in the database and can be restored.

| Action            | Endpoint                                                          | Who                        |
|-------------------|-------------------------------------------------------------------|----------------------------|
| Delete a task     | `DELETE /api/v1/projects/{projectId}/tasks/{taskId}`              | project CONTRIBUTOR+       |
| Restore a task    | `POST /api/v1/projects/{projectId}/tasks/{taskId}/restore`        | project CONTRIBUTOR+       |
| Delete a project  | `DELETE /api/v1/organizations/{orgId}/projects/{projectId}`       | org ADMIN+ or project LEAD |
| Restore a project | `POST /api/v1/organizations/{orgId}/projects/{projectId}/restore` | org ADMIN+                 |

- A deleted task or project answers exactly like one that doesn't exist. A deleted project also hides
  all of its tasks, and a task inside a deleted project can only come back by restoring the project.
- A deleted project's key is free for a new project to reuse. Restoring the old one while its key is
  taken returns 409 `PROJECT_KEY_IN_USE`.
- Every delete and restore is recorded in the audit log (`TASK_DELETED`, `TASK_RESTORED`,
  `PROJECT_DELETED`, `PROJECT_RESTORED`) with the actor and the task's title or the project's key and
  name. Admins can find deleted ids through `GET /api/v1/organizations/{orgId}/audit-logs`.
- Deleting a task sends its assignee a `task.deleted` real-time event, and restoring it sends
  `task.updated`.
- Nothing is purged automatically yet.

## Environment variables

None of these have a real value in git anywhere - `dev`/`test` use clearly-labeled dummy secrets in
their own `application-*.yml`, and `prod` requires every secret below to be set with no fallback
(the app fails to start rather than silently running with a blank one).

| Variable                                                     | Required in prod      | Notes                                                                       |
|--------------------------------------------------------------|-----------------------|-----------------------------------------------------------------------------|
| `DB_HOST`, `DB_PORT`, `DB_NAME`                              | No (defaults)         | `localhost`, `5432`, `taskforge`                                            |
| `DB_USERNAME`, `DB_PASSWORD`                                 | Yes                   | No default - the app won't start without them                               |
| `DB_POOL_MAX_SIZE`, `DB_POOL_MIN_IDLE`                       | No (defaults)         | HikariCP pool sizing, `20`/`5`                                              |
| `REDIS_HOST`, `REDIS_PORT`                                   | No (defaults)         | `localhost`, `6379`                                                         |
| `REDIS_PASSWORD`                                             | No (defaults to none) | Set if the Redis instance requires auth                                     |
| `APP_ENCRYPTION_KEY`                                         | Yes                   | Base64, 32 raw bytes (AES-256) - see `EncryptedStringConverter`             |
| `APP_JWT_SECRET`                                             | Yes                   | JWT signing secret - rotating it invalidates every outstanding access token |
| `CORS_ALLOWED_ORIGINS`                                       | No (defaults to none) | Comma-separated; empty means every cross-origin browser request is rejected |
| `DO_SPACES_ENDPOINT`, `DO_SPACES_REGION`, `DO_SPACES_BUCKET` | Yes                   | DigitalOcean Spaces (S3-compatible) connection details                      |
| `DO_SPACES_KEY`, `DO_SPACES_SECRET`                          | Yes                   | Scoped to the Spaces bucket above - never the account-wide credential       |
| `MANAGEMENT_PORT`                                            | No (defaults)         | `8081` - actuator's own port, kept off the public-facing one                |

## Runbook

**Deploying:** `docker build -t taskforge-api .` (multi-stage, non-root runtime user) or
`docker compose -f docker-compose.prod.yml up -d` for a full prod-shaped stack including Postgres
and Redis. The image runs Flyway migrations against the target database automatically on startup -
there's no separate migration step to remember, but see the CI note below on how a broken migration
is still caught before merge, not after deploy. Shutdown is graceful
(`server.shutdown: graceful`, 20s per phase): a rolling deploy stops routing new requests to the old
instance but lets in-flight ones finish first, rather than cutting them off.

**Rolling back:** migrations are forward-only by convention - there is no `flyway:undo` step in this
pipeline, and reversing a schema change safely (especially one another migration or running code may
already depend on) is rarely as simple as running the down version anyway. A bad migration in prod
gets fixed by shipping a new migration that corrects it, not by rolling the schema back; a bad
non-migration deploy can just redeploy the previous image, since the app itself is stateless.

**CI pipeline** (`.github/workflows/ci.yml`), on every PR against `main`: verifies pending
migrations apply cleanly to a fresh database (`mvn flyway:migrate`, independent of the app's own
Flyway autoconfiguration - this is what catches an edited already-shipped migration's checksum
mismatch), then runs the full test suite against real Postgres/Redis via Testcontainers, then builds
the Docker image. Each step is gated on the previous one passing.

**First response, if the app looks down:** `/actuator/health` first - `db`/`redis` show up as separate
components under `readiness`, so the body itself usually says which dependency is the problem before
you go looking anywhere else. If health is `UP` but requests are still failing, check the application
logs next (structured JSON in prod, via `logging.structured.format.console: ecs`) - every request
carries a correlation ID (`CorrelationIdFilter`), so one failing request's log lines can be found
without wading through unrelated traffic.

**Monitoring:** `/actuator/health` (liveness + readiness, including live `db`/`redis`
connectivity), `/actuator/metrics`, and `/actuator/prometheus` for scraping - all on
`MANAGEMENT_PORT`, not the public API port. `/actuator/health/readiness` is what a load balancer or
orchestrator should probe; an open circuit breaker on webhook delivery deliberately does not fail it
(one org's degraded webhook delivery isn't a reason to pull a healthy instance out of rotation).

**Secrets:** every prod secret is env-var-only with no default (see the table above) - there is
nothing to rotate in application code, only in whatever secret store injects these variables at
deploy time. `.env` (real, local-only values) is gitignored; `.env.example` documents the shape
without any real value. Application logs never include secret values - `logging.level` is left at
its default outside `dev`'s `org.hibernate.SQL: debug`, which itself only logs SQL statements
(bind parameters, not the query text, would need `TRACE`, never enabled).

**SQL injection:** every database query in this codebase is either a Spring Data derived query
method, a `@Query` with named parameters, or built through the JPA Criteria API - none of it
concatenates a request value into a query string, including the three native `@Query`s in
`NotificationRepository` (line-wrapped for readability, not string-built from input). This is a
structural property of how the codebase is written, not a claim that needs re-verifying per PR.

**Dependency scanning:** GitHub's dependency graph and Dependabot alerts are enabled on this repo
and run continuously - check the repo's Security tab for anything new. Secret scanning isn't
available (it requires GitHub Advanced Security, not offered on a private repo on the free plan).
