# TaskForge API

Multi-tenant SaaS task management REST API — a production-grade Spring Boot backend, built
incrementally as a series of independent, reviewable PRs.

## Tech stack

- Java 25
- Spring Boot 4.1.1 (Spring Framework 7)
- Maven
- PostgreSQL + Flyway (introduced in a later PR)
- Redis (introduced in a later PR)
- Spring Security / JWT (introduced in a later PR)
- Testcontainers for integration tests (introduced in a later PR)

## Current status

Early scaffold. At this stage the app boots, exposes Actuator health, and has no domain logic,
database, or security yet — those land in subsequent PRs.

## Running locally

Requires Java 25.

```
./mvnw spring-boot:run
```

By default this runs without an active profile. To run against a specific profile:

```
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
