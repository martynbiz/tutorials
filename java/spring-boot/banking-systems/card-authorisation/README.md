# Card Authorisation

Build the system at a bank that answers the question **"can this card pay £4.50 here?"** in real time, then follows that payment through to the moment money actually leaves the account.

This is the **issuer** side of a card payment: the customer's own bank. It's where a retail bank's card developers work, and it's a great place to learn concurrency, because a wrong answer under load means the bank pays for something its customer couldn't afford.

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for versions, conventions and where your code goes.

---

## What you'll build

A JSON API for a small card issuer:

- Customer **accounts** with an arranged overdraft, and **cards** identified by a token (never a real card number)
- **Authorisation** requests answered with ISO 8583-style **response codes**, placing **holds** that reduce the **available balance** but not the **ledger balance**
- **Spending limits** per transaction and per day
- Authorisations that stay correct when **many arrive at once** for the same account, plus full and partial **reversals**
- **Clearing**: turning a hold into a real double-entry posting, for an amount that may differ from what was authorised, and **settlement** with the card scheme
- A **scheduled job** that releases holds nobody ever cleared, safely, even with several servers running it at once

```
   POST /authorisations ─▶ approve "00" ─▶ hold (PENDING)
                                            │
            ┌───────────────────────────────┼───────────────────────────┐
            ▼                               ▼                           ▼
   POST /presentments            POST /authorisations/{id}/reversals   expiry job
   CLEARED: debit customer,      REVERSED: hold released               EXPIRED: hold released
   credit scheme settlement      (or partly released)                  (a late presentment can still clear)
```

### The API you'll end up with

| Method | Path | Does |
|---|---|---|
| `POST` | `/accounts` | Open an account with an arranged overdraft limit |
| `GET` | `/accounts/{id}` | Ledger balance, holds, overdraft and available balance |
| `POST` | `/accounts/{id}/deposits` | Money arriving in the account |
| `POST` | `/accounts/{id}/cards` | Issue a card (returns its token) |
| `GET` | `/cards/{id}` | One card |
| `POST` | `/cards/{id}/block` | Stop a card working |
| `POST` | `/authorisations` | The scheme asks: can this card pay? Always `200`, with a response code |
| `GET` | `/authorisations/{id}` | One authorisation and its status |
| `GET` | `/accounts/{id}/authorisations?status=PENDING` | The account's pending transactions |
| `POST` | `/authorisations/{id}/reversals` | Release some or all of a hold |
| `POST` | `/presentments` | Clear an authorisation: post the final amount |
| `POST` | `/settlements` | Record paying the card scheme |

---

## Prerequisites

As well as the [series prerequisites](../README.md#prerequisites), this tutorial leans on:

- **Spring Data JPA:** entities, repositories, `@ManyToOne` and lazy loading, `@Query` with JPQL
- **Transactions:** what `@Transactional` does on a service method. Guide 03 goes much deeper.
- **Java concurrency basics:** threads and `ExecutorService`. The concurrency tests use `CountDownLatch`, which is explained when it appears.
- **`java.time`:** `Instant`, `LocalDate`, `Duration`, `YearMonth`

No Spring Security is needed. In a real bank this system sits behind the card scheme's network connection and internal services, and is never exposed to customers directly.

You don't need to have done the Core ledger tutorial. This tutorial builds its own small `Money` type and a cut-down ledger, and says where the Core ledger tutorial goes further.

---

## The guides

Do them in order. Each one ends with every test passing, so you can stop between any two.

| # | Guide | Banking | Java / Spring |
|---|---|---|---|
| 01 | [Cards and available balance](01-cards-and-available-balance.md) | The four-party model, authorisation vs clearing vs settlement, ledger vs available balance, PCI DSS and tokens | `Money`, an injected `Clock`, `ProblemDetail`, a test meta-annotation |
| 02 | [Authorising a card payment](02-authorising-a-payment.md) | Authorisation requests, response codes, holds, spending limits, timeouts and stand-in processing | Gather-decide-record, a pure decision function, test data builders, `@ParameterizedTest`, counting SQL statements |
| 03 | [Concurrent authorisations](03-concurrent-authorisations.md) | No overspending under load, joint accounts, reversals and partial reversals | Write skew, why `@Version` can't help, `SELECT … FOR UPDATE` on the right row, "read the id, lock, then load" |
| 04 | [Clearing and the ledger](04-clearing-and-the-ledger.md) | Presentment, cleared amount ≠ authorised amount, posting, scheme settlement | A cut-down double-entry ledger, lock ordering, duplicate protection with a unique constraint |
| 05 | [Expiring holds](05-expiring-holds.md) | Hold lifetimes, late presentments | `@Scheduled`, a mutable test `Clock`, idempotent and concurrency-safe jobs, `TransactionTemplate` |

Allow roughly an evening per guide.

---

## Project at a glance

| | |
|---|---|
| Your project folder | `workspace/banking-systems/card-authorisation/` |
| Base package | `com.example.cards` (paths like `money/Money.java` mean `src/main/java/com/example/cards/money/Money.java`) |
| Runs on | `http://localhost:8080` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| H2 console | `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:file:./data/cards`, user `sa`, no password) |
| Tests | `./mvnw test` (55 by the end, all against an in-memory database) |

The finished package layout:

```
com.example.cards
├── money/          Money
├── config/         ClockConfig, SchedulingConfig
├── account/        Account, AccountBalances, AccountService, AccountController, …
├── card/           Card, CardStatus, CardService, CardController, …
├── authorisation/  Authorisation, AuthorisationRules, ResponseCode, AuthorisationService, …
├── ledger/         JournalEntry, Posting, LedgerService, …
├── clearing/       ClearingService, PresentmentController, SettlementService, …
├── expiry/         HoldExpiryJob, HoldExpiryService
└── web/            ApiExceptionHandler
```

---

## Simplifications

Real issuer processing platforms do far more. The biggest things this tutorial leaves out:

- **No real card messages.** Authorisations arrive as simple JSON, not ISO 8583 messages over a scheme connection, and only six response codes are used.
- **No card numbers.** Cards are identified by a token from the start. A real issuer receives the PAN (or a scheme token) and translates it in a PCI DSS-controlled component.
- **No security checks.** PIN, CVV2, chip cryptograms, 3-D Secure and fraud scoring all happen before or alongside the decision in a real bank.
- **No stand-in processing, timeouts or advices.** The guides explain them, but don't implement them.
- **Matching by id.** Presentments name the authorisation they clear. Real clearing files have to be matched on scheme data, and some presentments match nothing.
- **No fees and no interchange.** Settlement is one simple journal entry.
- **The card system owns the ledger balance.** In most banks it comes from the core banking system. The Core ledger tutorial builds that properly.
- **One currency.** Amounts carry a currency, but everything is GBP.

Each guide labels its own simplifications where they come up.
