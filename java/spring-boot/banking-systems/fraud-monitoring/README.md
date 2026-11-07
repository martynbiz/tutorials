# Fraud Monitoring

Build the system that watches every payment a bank's customers make and asks **"does this look wrong?"**: transaction monitoring for **fraud** and **money laundering**, from the event arriving on Kafka to an analyst closing the case.

Every UK bank must monitor its customers' transactions, and fraud and financial crime teams are some of the biggest employers of developers in retail and commercial banking. It's also a great place to learn event-driven Java, because the hard problems (duplicates, poison messages, ordering, concurrent state) all show up for real.

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for versions, conventions and where your code goes.

---

## What you'll build

An event-driven monitoring service with a JSON API for analysts:

- **Transactions arrive as events** on a Kafka topic, keyed by account, and the service keeps each account's own history
- A small **rule engine** (large amounts, velocity, new payees, structuring) raises **alerts** with a plain-English reason, and publishes them to an alerts topic
- An **idempotent consumer** that processes a redelivered event once, and a **dead-letter topic** where broken events go instead of vanishing
- **Per-account serialisation**, so stateful rules stay correct when events for one account are processed concurrently
- **Case management** for analysts: a queue of alerts, cases with a strict lifecycle, outcomes, and a four-eyes rule

```
 card / payments systems                fraud-monitoring                         analysts
 ─────────────────────                  ────────────────                         ────────
 TransactionEvent ──▶ [transactions] ──▶ @KafkaListener ──▶ rules ──▶ alerts ──▶ /alerts/queue
   key = account id      3 partitions     │   idempotent,    pure      │         /cases
                                          │   locked per     functions  ▼          OPEN → INVESTIGATING
                         [transactions-dlt] ◀─ poison         [fraud-alerts]       → CLOSED + outcome
                                               messages        topic
```

### The API you'll end up with

| Method | Path | Does |
|---|---|---|
| `POST` | `/simulator/transactions` | Publish a transaction event to Kafka (stands in for the bank's payment systems) |
| `GET` | `/accounts/{accountId}/transactions` | The account's history, as the fraud system sees it |
| `GET` | `/alerts?accountId=…` | An account's alerts |
| `GET` | `/alerts/queue` | Alerts nobody has opened a case for yet |
| `POST` | `/cases` | Open a case for one or more alerts |
| `GET` | `/cases/{id}` | A case and its alerts |
| `GET` | `/cases?status=…` | Cases in one state (optionally `&assignee=…`) |
| `POST` | `/cases/{id}/assignment` | An analyst takes the case |
| `POST` | `/cases/{id}/closure` | Close the case with an outcome and a note |

And on Kafka:

| Topic | Who writes | Contains |
|---|---|---|
| `transactions` | The bank's card and payment systems (your simulator) | One event per payment, keyed by account |
| `fraud-alerts` | This service | One event per alert, keyed by account |
| `transactions-dlt` | Spring for Apache Kafka's error handler | Transaction events that couldn't be processed, with headers saying why |

---

## Prerequisites

As well as the [series prerequisites](../README.md#prerequisites), this tutorial leans on:

- **Docker:** this is the first system in the series that needs it. Kafka runs in a container, both when you run the app (Docker Compose) and in the tests (Testcontainers). You don't need to know Docker well: guide 01 starts with a check that yours works, and the rest is configuration. Docker Desktop on Windows or macOS, or Docker Engine on Linux, is enough.
- **Spring Data JPA:** entities, repositories, `@Query`, `@Lock`
- **Transactions:** what `@Transactional` does on a service method, and that it works through a proxy
- **Java concurrency basics:** threads and `ExecutorService`. The concurrency tests use `CountDownLatch`, which is explained when it appears.
- **`java.time`:** `Instant`, `Duration`, `ZoneId`

No prior Kafka knowledge is assumed: guide 01 introduces topics, partitions, keys, offsets and consumer groups. No Spring Security is needed. In a real bank the analyst API sits behind the bank's own authentication, and guide 05 points out where that matters.

You don't need to have done any other tutorial in the series. This one builds its own small `Money` type, and says where the Core ledger tutorial goes further.

---

## The guides

Do them in order. Each one ends with every test passing, so you can stop between any two.

| # | Guide | Banking | Java / Spring |
|---|---|---|---|
| 01 | [Transactions as events](01-transactions-as-events.md) | Fraud vs AML monitoring, APP fraud, why monitoring is event-driven | Kafka topics, partitions and keys, `@KafkaListener`, `KafkaTemplate`, Docker Compose, Testcontainers with `@ServiceConnection`, Awaitility |
| 02 | [Rules and alerts](02-rules-and-alerts.md) | Monitoring scenarios, alerts, false positives, explainability | A sealed `Rule` interface, rules as pure functions, validated `@ConfigurationProperties` records, event time vs processing time, publishing events |
| 03 | [Reliable consumers](03-reliable-consumers.md) | Never miss a payment, never count one twice | At-least-once delivery, the idempotent consumer, `Persistable`, `DefaultErrorHandler`, retries and dead-letter topics |
| 04 | [One account at a time](04-one-account-at-a-time.md) | Stateful rules, card testing, why ordering matters | Listener concurrency and rebalances, write skew, `SELECT … FOR UPDATE`, create-if-absent-then-lock, `TransactionTemplate` |
| 05 | [Alerts and cases](05-alerts-and-cases.md) | Case management, outcomes, the nominated officer and SARs, tipping off, four eyes | State machines with enums, `409` vs `422` `ProblemDetail`s, `@Version` for concurrent edits |

Allow roughly an evening per guide.

---

## Project at a glance

| | |
|---|---|
| Your project folder | `workspace/banking-systems/fraud-monitoring/` |
| Base package | `com.example.fraud` (paths like `rules/Rule.java` mean `src/main/java/com/example/fraud/rules/Rule.java`) |
| Runs on | `http://localhost:8080` (Kafka on `localhost:9092`, started from `compose.yaml`) |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| H2 console | `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:file:./data/fraud`, user `sa`, no password) |
| Tests | `./mvnw test` (59 by the end, against an in-memory database and a real Kafka broker in Docker) |

The finished package layout:

```
com.example.fraud
├── money/        Money
├── config/       ClockConfig, KafkaConfig, Topics
├── transaction/  TransactionEvent, TransactionListener, TransactionMonitoringService, AccountTransaction,
│                 ProcessedEvent, MonitoredAccount, …
├── rules/        Rule (sealed), LargeAmountRule, VelocityRule, NewPayeeRule, StructuringRule,
│                 RuleEngine, RulesProperties, …
├── alert/        Alert, AlertPublisher, AlertRaised, AlertController, …
├── casework/     FraudCase, CaseStatus, CaseOutcome, CaseService, CaseController, …
├── simulator/    SimulatorController
└── web/          ApiExceptionHandler
```

---

## Simplifications

Real transaction monitoring platforms (commercial ones from specialist vendors, or banks' own) do far more. The biggest things this tutorial leaves out:

- **After the event only.** The service monitors payments once they've been made. Real-time blocking needs a synchronous check on the payment path as well.
- **Four simple rules with made-up thresholds.** Real platforms run many more scenarios, tuned per customer segment, alongside machine-learning scores, and every rule change goes through governance and testing.
- **Very little data per transaction.** No sort codes and account numbers, merchant categories, devices, locations or Confirmation of Payee results, and no customer risk rating from KYC.
- **No authentication.** Analysts' names come from request bodies, which makes the four-eyes rule easy to get round. A real system takes identity from the bank's single sign-on and restricts AML cases to the people who need them.
- **Cases stop at the outcome.** No notes history, evidence, deadlines, customer contact, fund recovery, or the nominated officer's SAR process.
- **A small gap when publishing alerts.** Alerts are published after the database commit; a crash in between loses the event. Guide 02 explains the transactional outbox that closes the gap.
- **One currency.** Amounts carry a currency, but everything is GBP, and other currencies go to the dead-letter topic.

Each guide labels its own simplifications where they come up.
