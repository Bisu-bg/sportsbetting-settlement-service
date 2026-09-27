# Sportsbetting settlement service

A Java 21 / Spring Boot application implementing:

```text
POST /api/event-outcomes
    -> Kafka: event-outcomes (key = event ID)
    -> Kafka consumer -> H2 transaction: outcome + first matching batch + outbox
    -> scheduled matcher -> remaining 50-bet batches, each with its own outbox transaction
    -> scheduled outbox publisher -> RocketMQ: bet-settlements
    -> RocketMQ consumer -> H2 transaction: terminal bet status + payout
```

Uses Gradle 9.2 with a committed wrapper, Spring Data JPA/Hibernate, in-memory H2, Lombok, MapStruct, Jakarta Validation, springdoc OpenAPI/Swagger UI, and JaCoCo. No paid services, private dependencies, credentials, or external accounts are needed to run it.

## Code layout

The Java code uses the base package `com.sportsbetting.settlement` and is organized by domain under `src/main/java/com/sportsbetting/settlement/domain`:

```text
domain/
  bet/          api/  model/  repository/  service/
  outcome/      api/  model/  repository/  service/  messaging/
  settlement/   model/  repository/  service/  messaging/
```

The bet domain owns bet admission and lookup. The outcome domain owns the HTTP outcome endpoint, Kafka publisher/consumer, and outcome matching. The settlement domain owns the outbox, RocketMQ publisher/consumer, and payout transition. The root `api`, `config`, and `messaging` packages contain shared HTTP error handling, broker configuration, and JSON/error utilities. Injected services use names such as `betService` and `outcomeService`; repositories use names such as `betRepository`.

Tests live in corresponding subpackages under `src/test/java/com/sportsbetting/settlement`: `api`, `config`, `domain` (including `domain/outcome/messaging`), and `messaging`. Reusable test data lives in `support`. Test classes start with `Test`; integration test classes end with `Integration`.

## Run the complete application

Prerequisites: Docker Engine / Docker Desktop **running**, Docker Compose v2, and approximately 4 GB of available memory. The Docker build runs the tests and the coverage gate before packaging.

```sh
docker compose up --build -d
docker compose logs -f app
```

Wait for `Started SettlementApplication`, then visit `http://localhost:8080/actuator/health`. The API listens on localhost port 8080. Kafka and RocketMQ are real brokers in separate containers. Topic creation and broker readiness precede application startup.

```sh
docker compose down
```

**Data lifetime:** the required H2 database lives only in the application JVM. Restarting the application loses bets, outcomes, and idempotency/outbox records. Broker storage is also ephemeral in this demo. Reset the full stack with `docker compose down` followed by `docker compose up --build -d` for a fresh run; restarting only the application can leave old broker messages referencing lost bets.

## Run with a local JDK / IntelliJ

Install JDK 21 or 25. In IntelliJ, open the repository directory as a project. IntelliJ should detect `build.gradle` and the committed Gradle wrapper. If a Gradle import option is absent from the `build.gradle` context menu, open the **Gradle** tool window and select **Sync All Gradle Projects**, or use **File → Open** on the repository directory. Wait for synchronization before building.

Under **Settings → Build, Execution, Deployment → Build Tools → Gradle**, select the **Gradle wrapper**, set **Gradle JVM** to an installed JDK 21 or 25 (not a removed JDK path), and select **Gradle** for both **Build and run using** and **Run tests using**. Gradle configures Lombok and MapStruct annotation processing.

If IntelliJ reports `package lombok does not exist`, `package jakarta.validation.constraints does not exist`, or missing Spring packages while the wrapper builds successfully, the Gradle model has not loaded. Synchronize the Gradle project and inspect **Build → Sync** if synchronization fails. Gradle dependencies use `~/.gradle`, not `.m2`; Maven's `.m2` directory is unnecessary for this project. See [JetBrains' Gradle import and synchronization instructions](https://www.jetbrains.com/help/idea/work-with-gradle-projects.html).

Start brokers with their host-accessible advertised address:

```sh
docker compose -f compose.yml -f compose.local.yml up -d kafka nameserver rocketmq rocketmq-init
./gradlew bootRun
```

On Windows use `.\gradlew.bat bootRun`. Stop any Compose `app` container first to free port 8080. Do not run the containerized app with the local override: that override advertises RocketMQ on `127.0.0.1` for a host JVM.

Environment overrides: `KAFKA_BOOTSTRAP_SERVERS` (default `localhost:9092`), `ROCKETMQ_NAMESERVER` (default `localhost:9876`), and standard Spring Boot variables such as `SERVER_PORT`.

## Interactive API documentation

Once the application is running, open [Swagger UI](http://localhost:8080/swagger-ui.html). The generated OpenAPI contract is available as [JSON](http://localhost:8080/v3/api-docs) or [YAML](http://localhost:8080/v3/api-docs.yaml). Springdoc infers the public `/api/**` routes, request and response types, and validation constraints from the Spring MVC code. There are no hand-written operation, response, or schema-example annotations; the API behavior and error statuses are described below.

Use **Try it out** to create bets before publishing their event outcome. `POST /api/event-outcomes` returns 202 when Kafka acknowledges the message; settlement continues asynchronously, so use `GET /api/bets/{id}` to observe the final status and payout. Invalid requests and other API failures use `application/problem+json`.

The demo API and Swagger UI have no authentication. Compose binds the application port to localhost. If you deploy it behind a public endpoint, protect the API and its documentation; set `SPRINGDOC_API_DOCS_ENABLED=false` and `SPRINGDOC_SWAGGER_UI_ENABLED=false` to turn off the documentation endpoints where appropriate.

## Use the API

IDs are nonblank strings of at most 100 characters; event names allow 200. Amounts must be positive, at least 0.01, with up to 12 integer digits and 2 decimal places. All amounts use `BigDecimal`.

Create two bets **before** publishing the outcome:

```sh
curl -i -X POST http://localhost:8080/api/bets \
  -H 'Content-Type: application/json' \
  -d '{"betId":"bet-1","userId":"user-1","eventId":"final-1","eventMarketId":"match-winner","eventWinnerId":"team-a","betAmount":10.00}'

curl -i -X POST http://localhost:8080/api/bets \
  -H 'Content-Type: application/json' \
  -d '{"betId":"bet-2","userId":"user-2","eventId":"final-1","eventMarketId":"match-winner","eventWinnerId":"team-b","betAmount":15.00}'

curl -i -X POST http://localhost:8080/api/event-outcomes \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"final-1","eventName":"Championship final","eventWinnerId":"team-a"}'

curl http://localhost:8080/api/bets/bet-1
curl http://localhost:8080/api/bets/bet-2
```

These examples use a POSIX shell. With the full Compose stack running, this Windows/PowerShell smoke test creates bets, publishes an outcome, waits for settlement, and checks duplicate publication. It reads the Compose `app` logs, so use the API examples above when running the application directly on the host JVM:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/smoke.ps1
# Or, on any machine with PowerShell 7:
pwsh -File scripts/smoke.ps1
```

The Windows execution-policy override applies only to that test process and does not change the machine's policy.

Expected final state: `bet-1` is `WON` with payout `20.00`; `bet-2` is `LOST` with payout `0.00`. Intermediate states are `OPEN` and `PENDING`.

| Endpoint | Success | Failure |
| --- | --- | --- |
| `POST /api/bets` | 201 + bet representation | 400 invalid body; 409 duplicate bet ID or already-recorded event |
| `GET /api/bets/{id}` | 200 + current state/payout | 404 unknown bet |
| `POST /api/event-outcomes` | 202 after Kafka acknowledgement | 400 invalid body; 503 acknowledgement failed/timed out |

202 means Kafka accepted the event; downstream settlement is asynchronous. A 503 can mean the send succeeded but its acknowledgement was lost: retry **the same outcome**. Conflicting winners are detected asynchronously by the Kafka consumer and sent to the DLT. Repeating the same event ID and winner safely resumes any unmatched bets; event name changes on such a replay do not change the first recorded metadata.

## Settlement assumptions

- The assignment supplies no odds, currency, wallet, or payment gateway. This simulation pays **2 × stake including the stake** for a winning selection and zero for a losing selection. `payout` is the persisted reward record; there is no real money transfer.
- `eventWinnerId` on a bet is its selected participant; on an outcome it is the actual winner. All bets for an event are settled, including losing bets.
- `eventMarketId` is stored and returned. The supplied outcome has no market ID; all markets are interpreted as winner-selection markets for that event. Market-specific outcomes, draws, voids, and outcome corrections require a richer contract.
- Once an outcome is recorded, new bets for that event are rejected, including events that had no matching bets. Before the asynchronous consumer records it, API bet admission is still possible; such bets are included in the same outcome processing.

## Transactions, concurrency, and retries

`OutcomeService` records the first outcome, transitions up to 50 matching open bets to pending, and creates one outbox row per bet in one database transaction. The Kafka listener returns only after that transaction commits. An outbox failure rolls back that batch and, for the first batch, the outcome record. A scheduled matcher repeatedly selects recorded events with open bets and commits the next 50 per event; duplicate Kafka outcomes also resume matching. Each batch is independently retryable. The outbox primary key is the bet ID.

Bet admission and outcome recording/matching acquire a database coordination row **for the event**. Independent events can proceed concurrently while create/close races for one event remain serialized. Bet IDs are unique across events, enforced by the database. Settlement uses a pessimistic row lock on the bet plus JPA versioning, so simultaneous deliveries cannot award it twice. The terminal status and payout commit together. Incoming settlement data must exactly match the stored instruction.

Matching acquires the event coordination row before changing bets and creating outbox rows. Delivery locks only its outbox row; settlement validates the instruction before locking the bet. The bounded broker send happens while its outbox row is locked, which trades throughput for a simple unambiguous acknowledgement state in this single-instance demo.

The outbox dispatcher locks each due row and waits for a bounded RocketMQ send acknowledgement. It marks the row sent only after `SEND_OK`. A crash between broker acknowledgement and database commit can resend the message; settlement remains idempotent. Sent rows remain as the instruction audit/validation record.

| Stage | Policy |
| --- | --- |
| Kafka producer | `acks=all`, idempotence enabled, 10-second delivery timeout, bounded metadata wait; API returns 503 on failures |
| Kafka consumer | Record acknowledgements, auto-commit off; transient failures get 3 retries at 1-second intervals, then `event-outcomes.DLT` |
| Invalid/conflicting Kafka message | Immediate DLT; failed DLT publication throws so the source record is retained |
| Outcome matching | 50 bets per transaction; one-second scheduled resumption of recorded events with open bets |
| RocketMQ producer | Synchronous send, 5-second timeout, 2 client retries; non-`SEND_OK` is a failure |
| Outbox | Batches of 50 due rows, 1-second scheduling delay; retries at 2, 4, 8, 16, 32, then 60 seconds until successful |
| RocketMQ consumer | Cluster consumption, batch size 1, 2–4 threads; returns `RECONSUME_LATER` on failure, maximum 5 broker-managed retries, then `%DLQ%bet-settlement-consumer` |

The retry behavior follows the [Spring Kafka error-handler documentation](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html) and [RocketMQ classic push-consumer documentation](https://rocketmq.apache.org/docs/4.x/consumer/02push/). The classic RocketMQ client uses the 10911 broker port; the RocketMQ 5 gRPC proxy is not required. The Compose broker is built from Apache's RocketMQ 5.5.1 binary archive with its published SHA-512 checksum verified during the image build.

Outbox retries are intentionally persistent for broker outages, with capped delay and WARN logs containing bet IDs/attempt counts. Consumer poison messages have bounded retries. Logs include outcomes recorded, matched counts, send failures, duplicate deliveries, and final settlements.

Inspect Kafka dead letters:

```sh
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:29092 --topic event-outcomes.DLT --from-beginning
```

Kafka DLT records are not retried automatically. If a valid outcome landed there because a transient failure exhausted the consumer retries, fix the cause and republish the **same event ID and winner** through the API. Inspect malformed or conflicting records instead of replaying them. An outcome that never reached the database leaves its bets `OPEN`; the scheduled matcher can resume only outcomes already recorded in H2.

Inspect RocketMQ dead letters using `mqadmin printMsg` (quote the percent-delimited topic):

```sh
docker compose exec rocketmq mqadmin printMsg \
  -n nameserver:9876 -t '%DLQ%bet-settlement-consumer'
```

Inspect application logs and the RocketMQ DLQ when settlement work fails or remains pending.

RocketMQ does not automatically consume messages from its DLQ. If a settlement reaches it, investigate and fix the cause before manually redelivering the original instruction. The consumer validates that instruction against the stored outbox row and ignores a duplicate payout. There is no replay API. An outbox row already marked sent will not be republished by the scheduler; a bet can remain `PENDING` until the message is redelivered. If H2 state was lost during an app restart, a settlement message alone cannot restore it.

## Tests and coverage

```sh
./gradlew clean check bootJar
./gradlew test                         # unit tests, no brokers required
./gradlew integrationTest              # real H2 concurrency + embedded Kafka
```

On Windows replace `./gradlew` with `.\gradlew.bat`. Dependencies download on the first build. The packaged executable is `build/libs/sportsbetting-settlement-service.jar` and can be started with `java -jar build/libs/sportsbetting-settlement-service.jar` while the brokers are running.

`check` enforces **100% line and branch coverage from the unit-test task alone**, and also runs integration tests separately. No production packages are excluded. JaCoCo automatically filters compiler/Lombok-generated boilerplate; the MapStruct implementation is exercised, including null mapping. Coverage is not a substitute for concurrency and transaction tests.

Gradle selects `Test*Integration` classes for `integrationTest` and the remaining `Test*` classes for `test`, so the same test is not run in both tasks.

- Unit test report: `build/reports/tests/test/index.html`
- Integration report: `build/reports/tests/integrationTest/index.html`
- JaCoCo HTML: `build/reports/jacoco/test/html/index.html`
- JaCoCo XML/CSV: `build/reports/jacoco/test/jacocoTestReport.xml` / `.csv`
- Included delivery snapshot: [JaCoCo HTML](docs/coverage/html/index.html), [XML](docs/coverage/jacocoTestReport.xml), and [CSV](docs/coverage/jacocoTestReport.csv). Rebuild to verify the current source.
- CI uploads the test and JaCoCo reports as artifacts and runs the real-broker smoke test.

Tests cover valid/invalid HTTP requests, both payout results, duplicate/conflicting outcomes, broker timeouts and interrupted threads, JSON validation, producer send statuses, outbox retries, and consumer acknowledgement decisions. Integration tests use real H2 transactions/locks, 125-bet paged matching, and real embedded Kafka consumption/DLT publication; they also request the generated OpenAPI contract and Swagger UI, and check the documented error format. RocketMQ adapter unit tests mock its client; `scripts/smoke.ps1 -TestOutage` exercises duplicate RocketMQ delivery and broker outage/recovery with Docker Compose.

## Limitations and delivery notes

- This is a local simulation, not a production financial system. Idempotency holds while the H2 database exists; there is no crash-durable guarantee across JVM restarts. Independent application instances have independent in-memory databases and must not be deployed as a shared consumer group.
- Broker replication is one for local execution. Production requires persistent shared storage, replicated brokers, authentication/TLS, authorization, monetary audit/ledger rules, configured alert delivery, and retention.
- The first Kafka transaction still processes up to 50 bets before acknowledgement; very large events continue in scheduled batches. This H2-backed, single-instance design is not a production throughput benchmark.
- H2 rows are retained for the process lifetime. There is no automatic cleanup of completed outbox/outcome records because they enforce idempotency.
- The Gradle build uses Spring Boot 4.1.1, springdoc 3.1.1, RocketMQ client/broker 5.5.1, gRPC 1.84.0, and Fastjson2 2.0.65. Review future releases before upgrading.
- Local Docker base and broker images use version tags, which may move to newer image contents; pin digests for a reproducible production build. GitHub Actions in this repository are pinned to full commit SHAs.
- Local Docker verification depends on a running Docker daemon. See `VERIFICATION.md` for actual checks performed and any remaining environment limitations.

Java/backend contributor instructions are in the single repository guide, [AGENT.MD](AGENT.MD).
