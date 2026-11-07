# Reconciliation

Build the control that tells a bank whether its books agree with the outside world: **reconciliation**, the daily job of matching the bank's own records against someone else's record of the same money, and chasing every difference until it's explained.

Every bank has one: for each account it holds at another bank, for each payment scheme it belongs to, for its account at the central bank. It's unglamorous, it runs every morning, and when it's done badly, losses and errors hide in the gaps. It's also full of the problems that make banking software interesting: parsing files from outside, matching rules that must be explainable, controls that need two people, and batch jobs that must be safe to run again.

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for versions, conventions and where your code goes.

---

## What you'll build

A JSON API for reconciling a small bank's **nostro** account (its GBP account at a correspondent bank) and its **payment scheme settlement** account:

- **Imports** of the correspondent's ISO 20022 **camt.053** statement (streamed XML, protected against XXE) and the scheme's **CSV** settlement report (with a line-by-line error report), each refused if it's already been imported
- A tiny **double-entry ledger** whose postings feed reconciliation automatically
- A **matching engine** that pairs items exactly, then by rules for timing differences, small amount differences and many-to-one totals, recording the rule behind every match
- An **exception report** of breaks by age, and **manual resolution** (match by hand, or write off to **suspense**) under **maker-checker**
- Reconciliation **runs** that are idempotent, re-runnable for any date, and correct when started twice at once

```
  camt.053 statement ──┐                         ┌── matches (EXACT, DATE_WINDOW,
  scheme CSV report ───┤──► recon items ──► run ─┤   AMOUNT_TOLERANCE, MANY_TO_ONE)
  ledger postings ─────┘    (one shape,          └── breaks ──► exception report by age
                             signed amounts)                     └─► maker proposes, checker approves:
                                                                     MANUAL match or SUSPENSE write-off
```

### The API you'll end up with

| Method | Path | Does |
|---|---|---|
| `POST` | `/imports/camt053` | Upload a camt.053 statement (multipart) |
| `POST` | `/imports/scheme-report` | Upload a scheme settlement report (multipart CSV) |
| `GET` | `/imports/{id}` | An import, with its line errors |
| `POST` | `/ledger/entries` | Post a two-leg journal entry |
| `GET` | `/accounts/{code}` | An internal account's balance |
| `POST` | `/reconciliations` | Run (or re-run) the reconciliation for an account and business date |
| `GET` | `/reconciliations/{id}` | A run, its matches and the rules that made them |
| `GET` | `/breaks?account=…&asOf=…` | The exception report: open items by age bucket |
| `POST` | `/resolutions` | Propose a manual match or a write-off (needs an `X-User` header) |
| `POST` | `/resolutions/{id}/approval` | Approve it, as a different user |
| `POST` | `/resolutions/{id}/rejection` | Reject it |

---

## Prerequisites

As well as the [series prerequisites](../README.md#prerequisites), this tutorial leans on:

- **Spring Data JPA:** entities, repositories, `@Query`, `@ManyToOne`
- **Transactions:** what `@Transactional` does on a service method. Guides 02, 04 and 05 show where it isn't enough.
- **Java concurrency basics:** threads and `ExecutorService`. The concurrency tests use `CountDownLatch`, explained when it first appears.
- **Some XML:** elements, attributes and namespaces. No XML APIs are assumed.

Nothing else is assumed. The tutorial builds its own small `Money` type and a cut-down ledger, and says where the Core ledger tutorial goes further. No Spring Security is needed: like a ledger, a reconciliation system sits behind other services, and the guides say clearly where real authentication would go.

---

## The guides

Do them in order. Each one ends with every test passing, so you can stop between any two.

| # | Guide | Banking | Java / Spring |
|---|---|---|---|
| 01 | [Bank statements and camt.053](01-statements-and-camt053.md) | Why banks reconcile, nostro and vostro, the mirror image, camt.053 statements | StAX streaming, XXE prevention (failing test first), multipart uploads, all-or-nothing imports |
| 02 | [Scheme reports, CSV and duplicate files](02-scheme-reports-and-csv.md) | Scheme settlement reports, partial files and error reports | Apache Commons CSV, per-line validation, SHA-256 checksums, a unique constraint caught racing |
| 03 | [The matching engine](03-the-matching-engine.md) | Matching passes, tolerances, timing differences, auto-match rate | A pure matching engine, `@ConfigurationProperties` records, a ledger feed in the same transaction |
| 04 | [Breaks, suspense and maker-checker](04-breaks-suspense-and-maker-checker.md) | Breaks and ageing, exception reports, suspense accounts, four eyes | A pure report builder, a `Clock` bean, compare-and-set with `@Modifying` queries, a double-approval race |
| 05 | [Re-runnable and concurrent runs](05-re-runs-and-concurrent-runs.md) | Late and corrected files, re-running a business date | Idempotent runs, a double-matching race, `SELECT … FOR UPDATE`, lock ordering, data-tidying migrations |

Allow roughly an evening per guide.

---

## Project at a glance

| | |
|---|---|
| Your project folder | `workspace/banking-systems/reconciliation/` |
| Base package | `com.example.recon` (paths like `statement/Camt053Parser.java` mean `src/main/java/com/example/recon/statement/Camt053Parser.java`) |
| Runs on | `http://localhost:8080` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| H2 console | `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:file:./data/recon`, user `sa`, no password) |
| Tests | `./mvnw clean test` (60 by the end, all against an in-memory database) |

The finished package layout:

```
com.example.recon
├── account/     Account, AccountRepository, AccountType, UnknownAccountException
├── breaks/      AgeBucket, BreakReport, BreakService, BreakController
├── config/      ClockConfig
├── imports/     ImportService, ImportController, ImportedFile, ImportError, Checksums, …
├── item/        ReconItem, Side, ItemStatus, ReconItemRepository, ItemsAlreadyMatchedException
├── ledger/      JournalEntry, Posting, Direction, LedgerService, LedgerController, …
├── matching/    MatchingEngine, MatchingRules, MatchRule, Candidate, ProposedMatch, MatchResult, …
├── money/       Money
├── resolution/  Resolution, ResolutionService, ResolutionController, the maker-checker exceptions, …
├── run/         ReconciliationService, ReconciliationController, ReconRun, ReconMatch, …
├── scheme/      SchemeReportParser, SchemeLine, LineError, ParsedReport, …
├── statement/   Camt053Parser, StatementEntry, StatementListener, StatementSummary, …
└── web/         ApiExceptionHandler
```

---

## Simplifications

Real reconciliation platforms do far more. The biggest things this tutorial leaves out, each labelled where it matters:

- **Simplified file formats.** The camt.053 is structurally faithful but carries only the elements the tutorial reads (one `Stmt` per file, one `TxDtls` per entry). The CSV report layout is invented.
- **GBP only, and one set of tolerances.** Real systems configure tolerances per account, store them, and put changes to them through approval.
- **Calendar-day ageing** with illustrative buckets. Banks usually count business days and set their own thresholds.
- **A cut-down ledger.** Two-leg entries, customer accounts rolled into one, no opening balances. The Core ledger tutorial covers ledgers properly.
- **Transaction matching only.** Balance reconciliation (statement balance against ledger balance plus open items) is described, not built.
- **Suspense is a one-way street.** Items are written off to suspense, but clearing them to the right customer is left as an extension.
- **No authentication.** The user comes from an `X-User` header so maker-checker can be demonstrated; a real system takes it from the authenticated principal.

Each guide's recap points to where to go next.
