# Project instructions — Fleet Telemetry

## Objective

Build a local-first fleet telemetry MVP for a senior full-stack assessment.

## Technology

* Backend: Java 21, Spring Boot, Maven.
* Messaging: Apache Kafka.
* Database: PostgreSQL with TimescaleDB.
* Frontend: React, TypeScript, Vite.
* Realtime: Server-Sent Events (SSE).
* Resilience: Resilience4j.
* Load testing: k6.
* Local infrastructure: Docker Compose.
* IaC: Terraform.
* CI: GitHub Actions.

## Architecture rules

* Separate domain, application/use cases, and infrastructure concerns.
* Keep controllers thin; business rules belong in application/domain layers.
* Use DTOs at API boundaries and validate incoming requests.
* Do not expose database entities directly from the API.
* Keep Kafka publishing and consuming separate from HTTP request handling.
* Use stable event IDs and idempotent persistence.
* Use UTC timestamps and explicit units for coordinates and durations.
* Configure timeouts and bounded retries for remote calls.
* Use a circuit breaker only around an appropriate fallible dependency.
* Keep AI tools narrowly scoped, parameterized, and read-only unless explicitly required otherwise.

## Quality rules

* Write tests for business rules and duplicate event handling.
* Never claim a test passed unless it was executed successfully.
* Never invent configuration values or silently ignore build errors.
* Pin dependency and container versions.
* Do not commit secrets, API keys, passwords, or local .env files.
* Do not add dependencies without explaining their purpose.
* Prefer small, reviewable changes over generating the entire application at once.

## Delivery

* Keep README.md synchronized with the actual implementation.
* Document architecture decisions and known limitations.
* Record real examples of AI-generated code that required correction.
* Ensure all required services can be started using documented commands.
