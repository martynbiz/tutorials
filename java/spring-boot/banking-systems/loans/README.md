# Loans

Build the system that services a bank's **personal loans**: it turns a signed agreement into a repayment schedule, pays the money out, charges interest every day, collects repayments, and spots customers who fall behind.

Lending is where banking code meets dates and arithmetic head on. A loan system has to get every penny of interest right, on every day, for every customer, and then prove it. It's also where you meet the **overnight batch**, the part of a bank most developers never see but every bank depends on.

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for versions, conventions and where your code goes.

---

## What you'll build

A JSON API and a nightly batch job for a small book of unsecured, fixed-rate personal loans:

- **Quotes** with a full amortisation schedule, reconciled to the penny
- **Drawdown** into the customer's current account, recorded in a small double-entry ledger where every posting has a **booking date** and a **value date**
- An **end-of-day** job (Spring Batch) that accrues interest on every loan, bills instalments as they fall due, and works out who's in arrears, safely re-runnable and restartable
- **Repayments** allocated interest-first, **arrears buckets** by days past due, and a **settlement quote**
- Correct results when repayments and the nightly batch hit the same loan at the same moment

```
 Quote ──► Drawdown ──► every night: accrue ─► bill ─► arrears ──► Repayments ──► Paid off
           DEBIT  loan principal   £10,000.00       (Spring Batch)       interest first,
           CREDIT current account  £10,000.00                            then principal
```

### The API you'll end up with

| Method | Path | Does |
|---|---|---|
| `POST` | `/loan-quotes` | Quote a loan and return its full schedule (nothing is stored) |
| `POST` | `/accounts` | Open a customer current account |
| `GET` | `/accounts/{id}` | Current account and balance |
| `POST` | `/loans` | Draw down a loan into a current account, today |
| `GET` | `/loans/{id}` | Loan summary: balances, amount due, days past due |
| `GET` | `/loans/{id}/instalments` | The schedule: scheduled, billed and still due, per instalment |
| `POST` | `/eod?businessDate=…` | Run end of day for a business date (default: today) |
| `POST` | `/loans/{id}/repayments` | Repay from the current account |
| `GET` | `/loans/{id}/settlement-quote` | What it would cost to clear the loan today |

---

## Prerequisites

As well as the [series prerequisites](../README.md#prerequisites), this tutorial leans on:

- **`BigDecimal` basics:** creating one from a `String`, and why `double` is wrong for money. Guide 01 recaps the essentials.
- **`java.time`:** `LocalDate` and the idea of an immutable date type. Guide 01 covers the month arithmetic.
- **Spring Data JPA:** entities, repositories, `@ManyToOne` / `@OneToMany`, lazy loading, `@Query`
- **Transactions:** what `@Transactional` does on a service method
- **Java concurrency basics:** what a thread is, and `ExecutorService`. Guide 04's concurrency test uses `CountDownLatch`, which is explained when it appears.

No Spring Batch experience is needed: guide 03 starts from the beginning. No Spring Security is needed either: like a ledger, a loan servicing system sits behind other services.

---

## The guides

Do them in order. Each one ends with every test passing, so you can stop between any two.

| # | Guide | Banking | Java / Spring |
|---|---|---|---|
| 01 | [Amortisation schedules](01-amortisation-schedules.md) | Principal, nominal rate vs APR, repayment loans, day count conventions, rounding rules | `BigDecimal` with `MathContext` and `RoundingMode`, the annuity formula, `java.time` month arithmetic, parameterised property tests |
| 02 | [Drawdown, the ledger and value dates](02-drawdown-ledger-and-value-dates.md) | How a loan creates a deposit, principal and interest accounts, booking date vs value date, the business date | A small double-entry ledger, value-dated balance queries, an injected `Clock` with a movable test clock, JPA aggregates |
| 03 | [End of day with Spring Batch](03-end-of-day-batch.md) | The overnight batch, daily accrual vs monthly charging, billing | Spring Batch 6 on Boot 4.1: jobs, chunk steps, `@StepScope`, job parameters as identity, idempotent re-runs, restarts, keyset paging |
| 04 | [Repayments, arrears and concurrency](04-repayments-arrears-and-concurrency.md) | Allocation waterfalls, days past due and arrears buckets, notices of arrears, early settlement | Pessimistic locking of an aggregate root, lock ordering, a concurrency test across online and batch paths |

Allow roughly an evening per guide.

---

## Project at a glance

| | |
|---|---|
| Your project folder | `workspace/banking-systems/loans/` |
| Base package | `com.example.loans` (paths like `money/Money.java` mean `src/main/java/com/example/loans/money/Money.java`) |
| Runs on | `http://localhost:8080` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| H2 console | `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:file:./data/loans`, user `sa`, no password) |
| Tests | `./mvnw test` (60 by the end, all against an in-memory database) |

The finished package layout:

```
com.example.loans
├── money/       Money
├── schedule/    InterestMath, Annuity, DueDates, ScheduleCalculator, …
├── quote/       QuoteController
├── ledger/      LedgerAccount, JournalEntry, Posting, LedgerService, …
├── account/     AccountService, AccountController (customer current accounts)
├── time/        ClockConfig, BusinessDate
├── loan/        Loan, Instalment, LoanService, LoanController, ArrearsBucket, …
├── interest/    InterestAccrual, AccrualService, BillingService
├── eod/         EndOfDayJobConfig, EndOfDayService, EndOfDayController
├── repayment/   RepaymentService, RepaymentController
├── arrears/     ArrearsService
└── web/         ApiExceptionHandler
```

---

## Simplifications

Real lending platforms do far more. The biggest things this tutorial leaves out, each explained where it matters:

- **No origination.** Applications, credit scoring, affordability checks and the credit agreement itself are separate systems. Loans here are drawn down on request.
- **No regulated APR calculation.** The nominal rate drives everything. The APR, which UK lenders must disclose, has its own prescribed formula (guide 01 explains what it is).
- **One product shape:** unsecured, fixed-rate, monthly, level repayments, Actual/365 Fixed interest on the outstanding principal, no fees, no payment holidays, no interest on arrears.
- **Calendar-day due dates.** No moving due dates off weekends or bank holidays, and end of day runs every calendar day.
- **A cut-down ledger.** One loans sub-ledger with one income account, accruals kept in the sub-ledger and posted when billed, both dates per journal entry rather than per posting. The Core ledger tutorial covers ledgers properly.
- **No back-valuation.** A repayment can't be value-dated into a day whose interest is already accrued: it's value-dated the next day instead.
- **Simplified arrears and settlement.** Arrears status and a statutory-notice trigger, but no collections workflow, forbearance arrangements or credit reporting. A settlement quote, but no statutory rebate calculation or compensation.
- **No idempotency keys on repayments, and no authentication.** Both would sit in front of, or around, a real servicing system.

Each guide's recap mentions the most relevant omission and where you'd go to learn it.
