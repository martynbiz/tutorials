# Core Ledger

Build the heart of a bank: the **ledger** that records every penny that moves, and can prove that none was created or lost.

Every other banking system (payments, cards, loans) ends up writing to a ledger like this one. If you understand how it works, and why it's built the way it is, the rest of banking software makes much more sense.

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for versions, conventions and where your code goes.

---

## What you'll build

A JSON API for a small bank's ledger:

- Customer **accounts** holding **GBP**, plus the bank's own **cash** account
- **Deposits** and **withdrawals** recorded as proper **double-entry** journal entries
- **Transfers** between customers that stay correct when hundreds arrive at once
- **Statements** with running balances, **reversals** instead of deletes, and **idempotent** requests that are safe to retry

```
            ┌─────────────────────────────────────────────┐
            │ JournalEntry  "Transfer from 2 to 3"        │
            │   Posting: DEBIT  account 2   £25.00        │
            │   Posting: CREDIT account 3   £25.00        │
            └─────────────────────────────────────────────┘
                         debits = credits, always
```

### The API you'll end up with

| Method | Path | Does |
|---|---|---|
| `POST` | `/accounts` | Open a customer account |
| `GET` | `/accounts/{id}` | Account details and balance |
| `POST` | `/accounts/{id}/deposits` | Pay cash in |
| `POST` | `/accounts/{id}/withdrawals` | Take cash out |
| `POST` | `/transfers` | Move money between customers (needs an `Idempotency-Key` header) |
| `GET` | `/journal-entries/{id}` | One journal entry and its postings |
| `POST` | `/journal-entries/{id}/reversal` | Cancel an entry by posting its mirror image |
| `GET` | `/accounts/{id}/statement` | Paged history, newest first, with running balances |

---

## Prerequisites

As well as the [series prerequisites](../README.md#prerequisites), this tutorial leans on:

- **Spring Data JPA:** entities, repositories, `@ManyToOne` / `@OneToMany`, lazy loading, `@Query`
- **Transactions:** what `@Transactional` does on a service method (guide 03 goes deeper)
- **Java concurrency basics:** what a thread is, and `ExecutorService`. The concurrency tests use `CountDownLatch`, which is explained when it appears.

No Spring Security is needed. The ledger has no login, because in a real bank it sits behind other services and is never exposed to customers directly.

---

## The guides

Do them in order. Each one ends with every test passing, so you can stop between any two.

| # | Guide | Banking | Java / Spring |
|---|---|---|---|
| 01 | [Accounts and money](01-accounts-and-money.md) | Why your balance is the bank's *liability*. Why money is never a `double`. | A `Money` record, `BigDecimal` traps, money in JSON, `ProblemDetail` errors, `MockMvcTester` |
| 02 | [Double-entry postings](02-double-entry-postings.md) | Debits, credits, journal entries, internal accounts, and cached vs derived balances | A domain builder that enforces invariants, `@EntityGraph`, aggregate queries, a randomised invariant test |
| 03 | [Transfers and concurrency](03-transfers-and-concurrency.md) | Overdraft rules, double-spending, hot accounts | Lost updates, `@Version` optimistic locking, `SELECT … FOR UPDATE`, deadlocks and lock ordering, multi-threaded tests |
| 04 | [Statements, reversals and idempotency](04-statements-reversals-and-idempotency.md) | Statements, "never edit, always reverse", audit trails, safe retries | Pagination, running balances, Hibernate `@Immutable`, `Idempotency-Key`, unique constraints as a backstop |

Allow roughly an evening per guide.

---

## Project at a glance

| | |
|---|---|
| Your project folder | `workspace/banking-systems/core-ledger/` |
| Base package | `com.example.ledger` (paths like `money/Money.java` mean `src/main/java/com/example/ledger/money/Money.java`) |
| Runs on | `http://localhost:8080` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| H2 console | `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:file:./data/ledger`, user `sa`, no password) |
| Tests | `./mvnw test` (42 by the end, all against an in-memory database) |

The finished package layout:

```
com.example.ledger
├── money/        Money
├── account/      Account, AccountType, AccountService, AccountController, …
├── journal/      JournalEntry, Posting, Direction, LedgerService, …
├── cash/         CashService, CashController (deposits, withdrawals)
├── transfer/     TransferService, TransferController, idempotency
├── statement/    StatementService, StatementController
└── web/          ApiExceptionHandler
```

---

## Simplifications

Real core banking platforms (for example Thought Machine Vault, 10x SuperCore, Temenos, Mambu) do far more. The biggest things this tutorial leaves out:

- **One currency.** Amounts carry a currency, but everything is GBP.
- **No interest, fees or overdraft limits.** Customer balances simply can't go below zero through normal operations.
- **No value dates.** Every posting takes effect immediately.
- **One internal account (cash).** A real general ledger has hundreds: fees, interest, suspense, payment scheme settlement accounts.
- **No authentication or authorisation.** These sit in front of a real ledger, not inside it.

Each guide's recap mentions the most relevant omission and where you'd go to learn it.
