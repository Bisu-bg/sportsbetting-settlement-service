# Java and backend development practices

## Build and validation
- Use Java 21+ and the committed Gradle wrapper. Run `./gradlew clean check bootJar` before delivery.
- Preserve 100% JaCoCo line and branch coverage of handwritten production code. Do not suppress failures, exclude business code, or add fake tests to satisfy metrics.
- Write focused unit tests for contracts and failure paths; verify persistence transactions and concurrent processing with integration tests.
- Put tests in feature-oriented subpackages below `com.sportsbetting.settlement` (for example, `api`, `config`, `domain`, and `messaging`). Name test classes `Test*`; name integration test classes `Test*Integration` so Gradle's separate test tasks discover them. Keep shared fixtures in a `support` subpackage, not in another test class.
- Inspect generated reports at `build/reports/tests/test/index.html` and `build/reports/jacoco/test/html/index.html`.

## Design
- Follow SOLID, DRY, and clean code. Prefer small cohesive classes, explicit dependencies, constructor injection, meaningful names, and composition.
- Use `com.sportsbetting.settlement` as the base package. Organize application code by domain under `domain/bet`, `domain/outcome`, and `domain/settlement`, with each domain's `api`, `model`, `repository`, `service`, and `messaging` packages as applicable. Keep only cross-domain configuration and utilities at the root.
- Name service-typed fields, parameters, and local variables with a `Service` suffix (for example, `betService`); name repositories with a `Repository` suffix. Apply the same conventions in tests.
- Keep HTTP, messaging, application coordination, domain rules, and persistence concerns separate.
- Keep entities encapsulated. Avoid Lombok `@Data` on JPA entities; do not generate relationship-based equality or toString methods.
- Use records for immutable request/message DTOs, Lombok for repetitive constructors/logging, and MapStruct for entity-to-response mappings.
- Use BigDecimal for money with explicit scale and a documented payout policy. Never use floating point for financial amounts.

## Reliability
- Assume messages can be redelivered, duplicated, and delayed. Business idempotency must live in the database, not an in-memory Java set.
- Keep each matching batch's bet transitions and outbox rows atomic; include the first outcome record in its first batch transaction. Resume later batches from recorded outcomes with open bets. Keep bet/payout updates atomic. Do not acknowledge consumption before the first transaction commits.
- Validate broker messages as well as HTTP requests. Reject conflicting outcomes and settlement payloads that differ from the recorded instruction.
- Keep retries bounded in consumers and send poison messages to a dead-letter destination. Retain producer outbox work with capped backoff.
- Never swallow a failure and report success; preserve thread interruption. Test failure after send and before commit.
- Use per-event database coordination and uniqueness constraints for concurrency. Match at most 50 bets per transaction and resume from open bets. Document throughput limits and lock ordering; avoid network calls while holding locks except the explicitly bounded outbox send.
- In-memory H2 is a requirement here: do not claim crash-durable exactly-once rewards, cross-instance shared state, or production financial suitability.

## API, operations, and security
- Validate lengths, required fields, and amounts at boundaries and on persisted entities; use suitable HTTP status codes and safe problem details.
- Let springdoc infer public API routes and schemas from Spring MVC signatures and validation constraints. Avoid repetitive OpenAPI operation, response, and example annotations. Verify the generated routes and Swagger UI through an integration test, and document actual error behavior in the README.
- Log event/bet identifiers and failures without credentials or full user/message payloads. Keep local broker ports bound to loopback.
- Inspect Kafka and RocketMQ dead-letter queues and document manual investigation; do not silently discard failed settlements.
- Keep credentials out of source control. Pin GitHub Actions to full commit SHAs and review upgrades. Use explicit dependency versions; local Docker images use version tags, while reproducible production builds should pin image digests.
- Keep README commands, assumptions, retry policies, and execution limitations synchronized with code.
- Preserve user changes. Do not publish or deploy beyond the requested scope.
- Keep this `AGENT.MD` as the sole repository agent guide; update it when project conventions change.
