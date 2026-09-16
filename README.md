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
