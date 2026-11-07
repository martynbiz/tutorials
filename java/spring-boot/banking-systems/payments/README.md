# Payments

Build the system that sends a customer's money to **another bank**: a Faster Payments-style outbound payments engine that takes the money, sends the payment, copes with every way the network and the other side can fail, and settles.

Moving money *inside* a bank is a ledger problem. Moving it *between* banks is a distributed systems problem: two organisations, a payment scheme in the middle, messages that are lost, delayed or delivered twice, and a central bank where the money finally moves. Most of the hard engineering in a bank's payments team is about making that reliable.

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for versions, conventions and where your code goes.

---

## What you'll build

A JSON API, plus a simulated payment scheme running inside the same application:

- Payments to a UK **sort code and account number**, checked against the bank's limit and the customer's funds, with a lifecycle that **can't** go wrong: `RECEIVED → VALIDATED → SUBMITTED → ACCEPTED → SETTLED`, or `REJECTED`
- A small **double-entry ledger**, so the customer's money really leaves their account and sits in a settlement account until the banks settle
- **Idempotent** payment requests that are safe to retry, even ten at a time
- A **transactional outbox** that sends each payment to the scheme exactly when it's committed, and never loses one
- **Asynchronous** accept and reject answers from the scheme, handled by an **idempotent consumer**, with rejected payments' money returned, and **settlement cycles** against the bank's reserves at the Bank of England

```
  POST /payments ──► Payment VALIDATED ──┐  one transaction:
                     Alice −£25          │  payment, ledger entry,
                     FPS settlement +£25 │  outbox event, idempotency record
                     outbox_event        ┘
                           │
                  relay (FOR UPDATE SKIP LOCKED)
                           ▼
                   simulated scheme ──── later ───► status report: ACSP / RJCT
                                                     settlement notice
                                                           │
                                     inbox (processed ids) ▼
                                     ACCEPTED → SETTLED, or REJECTED + funds returned
```

### The API you'll end up with

| Method | Path | Does |
|---|---|---|
| `POST` | `/payments` | Make a payment (needs an `Idempotency-Key` header) |
| `GET` | `/payments/{id}` | A payment and its current status |
| `POST` | `/accounts` | Open a customer account |
| `GET` | `/accounts/{id}` | An account and its balance, including the bank's internal accounts |
| `POST` | `/accounts/{id}/deposits` | Pay cash in, so there's money to send (optional `Idempotency-Key`) |
| `POST` | `/scheme/status-reports` | The scheme's accept/reject answer (the simulator calls this logic; you can too) |
| `POST` | `/scheme/settlements` | The scheme's settlement notice |

---

## Prerequisites

As well as the [series prerequisites](../README.md#prerequisites), this tutorial leans on:

- **Spring Data JPA:** entities, repositories, `@Query`, `@ManyToOne`
- **Transactions:** what `@Transactional` does on a service method. Guide 02 shows where it isn't enough.
- **Java concurrency basics:** threads and `ExecutorService`. The concurrency tests use `CountDownLatch`, explained when it first appears.
- **JSON with Jackson:** how records become JSON

Nothing else is assumed. Everything the tutorial needs from a ledger is built in guide 02; the Core ledger tutorial goes much deeper if you want it, but you don't need it first.

---

## The guides

Do them in order. Each one ends with every test passing, so you can stop between any two.

| # | Guide | Banking | Java / Spring |
|---|---|---|---|
| 01 | [The payment lifecycle](01-payment-lifecycle.md) | Clearing vs settlement, sort codes, Faster Payments vs Bacs vs CHAPS, ISO 20022 | An explicit state machine (enum + exhaustive switch), value objects, `@ConfigurationProperties`, a `Clock` bean, parameterised tests |
| 02 | [Taking the money](02-taking-the-money.md) | Debiting the customer into a scheme settlement account, double spending, hot accounts | A small double-entry ledger, `@Version`, `SELECT … FOR UPDATE`, lock ordering, a concurrency test that fails then passes |
| 03 | [Idempotency in depth](03-idempotency-in-depth.md) | Safe retries, UK Open Banking's idempotency rules | A generic `idempotency_record` table, request fingerprints, key expiry, `Persistable`, method validation, a controllable test clock |
| 04 | [The transactional outbox](04-transactional-outbox.md) | Never lose a payment, never send one the bank has no record of | The dual-write problem, the outbox, at-least-once delivery, `@Scheduled`, `FOR UPDATE SKIP LOCKED` |
| 05 | [Scheme responses and settlement](05-scheme-responses-and-settlement.md) | Accepts, rejects, returns, settlement cycles | Asynchronous callbacks, the idempotent consumer (inbox), lock-then-check, the state machine as a guard |

Allow roughly an evening per guide.

---

## Project at a glance

| | |
|---|---|
| Your project folder | `workspace/banking-systems/payments/` |
| Base package | `com.example.payments` (paths like `payment/Payment.java` mean `src/main/java/com/example/payments/payment/Payment.java`) |
| Runs on | `http://localhost:8080` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| H2 console | `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:file:./data/payments`, user `sa`, no password) |
| Tests | `./mvnw test` (58 by the end, all against an in-memory database) |

The finished package layout:

```
com.example.payments
├── config/       ClockConfig, SchedulingConfig
├── money/        Money
├── payment/      Payment, PaymentStatus, SortCode, PaymentService, PaymentController, …
├── ledger/       Account, LedgerEntry, LedgerService, AccountController, …
├── idempotency/  IdempotencyRecord, IdempotencyService, IdempotencyRecordCleaner, …
├── outbox/       OutboxEvent, OutboxRelay, OutboxCleaner, …
├── scheme/       SchemeGateway, FakeFasterPaymentsScheme, the message records
├── callback/     SchemeResponseHandler, SchemeCallbackController, ProcessedMessage, …
└── web/          ApiExceptionHandler
```

---

## Simplifications

Real payment platforms (and the scheme behind them) do far more. The biggest things this tutorial leaves out, each labelled where it matters:

- **Outbound only.** No incoming payments, and returns are left as a stretch exercise.
- **A simulated scheme.** It runs in-process and speaks JSON loosely modelled on ISO 20022 `pacs.008` and `pacs.002`. Real Faster Payments messages are based on ISO 8583 and travel over a dedicated network link.
- **Settlement by payment list.** The simulator names the payments it settled. Real Faster Payments settlement is net, per cycle, three times each working day, and banks reconcile against it.
- **No fraud checks, Confirmation of Payee or modulus checking.** Real banks run all of these before a payment is accepted.
- **A cut-down ledger.** Two-leg entries only, and the bank's reserves start at zero. The Core ledger tutorial covers ledgers properly.
- **No authentication.** Not on the customer API, and not on the scheme callback endpoints, which in real life are an authenticated, private channel.
- **Idempotency keys are global** rather than scoped to each client, and the inbox of processed message ids never expires.

Each guide's recap points to where to go next.
