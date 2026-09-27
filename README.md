# TaskForge API

Multi-tenant SaaS task management REST API — a production-grade Spring Boot backend, built
incrementally as a series of independent, reviewable PRs.

## Tech stack

- Java 25, Spring Boot 4.1.1 (Spring Framework 7), Maven
- PostgreSQL + Flyway (versioned migrations, no auto-DDL)
- Redis (permission-check caching, rate limiting)
- Spring Security / JWT (access + rotating refresh tokens)
- Resilience4j (retry/circuit breaker/timeout on outbound webhook delivery)
- S3-compatible object storage (DigitalOcean Spaces in prod; LocalStack/MinIO in tests/local dev)
- springdoc-openapi (Swagger UI, `/v3/api-docs`)
- Testcontainers for integration tests; GitHub Actions CI (migration verification, full
  Testcontainers suite, Docker image build)

## Current status

Feature-complete: multi-tenant orgs/projects/tasks, JWT auth, role-based permissions, comments,
labels, attachments, notifications, outbound webhooks, audit logging, observability
(actuator/metrics/health), a CI pipeline, and full OpenAPI documentation.

## Running locally

Requires Java 25.

```
./mvnw spring-boot:run
```

By default this runs without an active profile. The `dev` profile needs Postgres and Redis
(`docker compose up -d` starts both, plus a local MinIO for manually poking at the storage
feature) and the DO Spaces variables in `.env.example` sourced into your shell:

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
