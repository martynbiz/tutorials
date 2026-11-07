# Customer Onboarding

Build the front door of a bank: the system that takes an application for a current account, checks who the applicant really is, screens them against sanctions and PEP lists, decides whether to open the account, and looks after their personal data the way the law requires.

Every customer relationship starts here, and so do a bank's obligations under anti-money laundering law and data protection law. Onboarding teams spend their days balancing the two: collect and keep enough to prove the bank knew its customer, and not a byte more than that.

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for versions, conventions and where your code goes.

---

## What you'll build

A JSON API for opening a retail current account:

- An **onboarding workflow** stored as state: `RECEIVED → CHECKS_IN_PROGRESS → APPROVED / REFERRED / DECLINED`, with every illegal move rejected
- **Customer due diligence** checks behind a provider interface: identity, address, **sanctions** (with fuzzy name matching) and **PEP** screening, run in order and **resumable** if a provider goes down
- A **risk rating** that chooses simplified, standard or enhanced due diligence
- A **manual review queue** with reasons, the **four-eyes** rule for high-risk approvals, and an **audit trail** of every decision
- **Personal data encrypted at rest** with AES-GCM, and a **blind index** to spot the same person applying twice
- **GDPR-aware design:** masked responses, no personal data in logs, and an **erasure request** that erases what it may and keeps what the Money Laundering Regulations require, until a computed date

```
  POST /applications ─► RECEIVED
                           │ POST /applications/{id}/checks
                           ▼
                  CHECKS_IN_PROGRESS ── identity ─ address ─ sanctions ─ PEP
                           │                (each result committed on its own;
                           │                 a provider outage resumes later)
           ┌───────────────┼────────────────┐
           ▼               ▼                ▼
       APPROVED        REFERRED         DECLINED
                           │ POST /reviews/{id}/decision  (a reason is required;
                           ▼                               high risk needs two people)
                 APPROVED or DECLINED
```

### The API you'll end up with

| Method | Path | Does |
|---|---|---|
| `POST` | `/applications` | Apply for an account |
| `GET` | `/applications/{id}` | The application's status, with a masked email |
| `POST` | `/applications/{id}/withdrawal` | The applicant withdraws |
| `POST` | `/applications/{id}/checks` | Run (or resume) the due diligence checks and decide |
| `GET` | `/applications/{id}/checks` | Check results (staff only in a real bank) |
| `GET` | `/applications/{id}/audit` | The audit trail |
| `GET` | `/reviews` | The manual review queue |
| `GET` | `/reviews/{id}` | A reviewer's full view of an application (audited) |
| `POST` | `/reviews/{id}/decision` | Approve or decline a referral, with a reason (`X-Reviewer` header) |
| `POST` | `/applications/{id}/erasure-request` | The applicant's right to erasure, as far as the law allows |
| `POST` | `/applications/{id}/account-closure` | The account has closed: start the retention period |

---

## Prerequisites

As well as the [series prerequisites](../README.md#prerequisites), this tutorial leans on:

- **Spring Data JPA:** entities, repositories, derived queries, `@Query`
- **Transactions:** what `@Transactional` does on a service method. Guides 02 and 03 show where it isn't enough, and what propagation means.
- **Java concurrency basics:** threads and `ExecutorService`. The concurrency tests use `CountDownLatch`, explained when it first appears.
- **Records and enums:** including compact constructors, enums with fields, and switch expressions

No cryptography background is needed: guide 04 explains AES-GCM and HMAC from scratch, using only the JDK's built-in `javax.crypto`. No Spring Security either; staff and applicant identity are deliberately left out (see Simplifications).

---

## The guides

Do them in order. Each one ends with every test passing, so you can stop between any two.

| # | Guide | Banking | Java / Spring |
|---|---|---|---|
| 01 | [Applications and workflow state](01-applications-and-workflow-state.md) | KYC and customer due diligence (MLR 2017), the account-opening journey | A workflow persisted as state, transition rules in an enum, `409` for illegal moves, UUID ids, a `Clock` bean, parameterised tests |
| 02 | [Due diligence checks](02-due-diligence-checks.md) | Electronic ID&V, sanctions and PEP screening, false positives, risk-based approach (SDD / CDD / EDD), approve / refer / decline | A `Check` interface with fake providers, `List<Check>` injection, fuzzy name matching, a resumable workflow with one transaction per step, `503` + `Retry-After` |
| 03 | [Manual review and the audit trail](03-manual-review-and-audit-trail.md) | Referrals, four-eyes, PEP approval, tipping off, audit trails | Insert-only audit events with `@Immutable` and `Propagation.MANDATORY`, method validation and `HandlerMethodValidationException`, a reviewer race fixed with `SELECT … FOR UPDATE` |
| 04 | [Encrypting personal data](04-encrypting-personal-data.md) | Encryption at rest, keys in HSM/KMS, key rotation | AES-GCM with `javax.crypto`, a JPA `AttributeConverter`, proving ciphertext with `JdbcTemplate`, an HMAC blind index, a duplicate-application race fixed with a unique constraint |
| 05 | [GDPR, retention and erasure](05-gdpr-retention-and-erasure.md) | Data minimisation, storage limitation, MLR record keeping vs the right to erasure, ongoing monitoring | Masking, keeping personal data out of logs (`OutputCaptureExtension`, records' `toString`), a mutable test `Clock`, an erasure service, a scheduled purge |

Allow roughly an evening per guide.

---

## Project at a glance

| | |
|---|---|
| Your project folder | `workspace/banking-systems/customer-onboarding/` |
| Base package | `com.example.onboarding` (paths like `application/Application.java` mean `src/main/java/com/example/onboarding/application/Application.java`) |
| Runs on | `http://localhost:8080` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| H2 console | `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:file:./data/onboarding`, user `sa`, no password) |
| Tests | `./mvnw test` (99 by the end, all against an in-memory database) |

The finished package layout:

```
com.example.onboarding
├── config/       ClockConfig, SchedulingConfig
├── application/  Application, Applicant, ApplicationStatus, ApplicationService, ApplicationController, …
├── check/        Check, CheckType, CheckResult, CheckService, FakeIdentityCheck, FakeAddressCheck, …
├── screening/    NameMatcher, SanctionsCheck, PepCheck, WatchlistEntry
├── risk/         RiskRating, DueDiligence, RiskAssessor
├── workflow/     OnboardingWorkflow, Decision, CheckController
├── review/       ReviewService, ReviewController
├── audit/        AuditEvent, AuditTrail, AuditController, …
├── crypto/       PiiCipher, EncryptedStringConverter, BlindIndex, CryptoProperties, …
├── privacy/      Masking, RetentionPolicy, ErasureService, RetentionJob, …
└── web/          ApiExceptionHandler
```

---

## Simplifications

Real onboarding platforms, and the regulations behind them, involve far more. The biggest things this tutorial leaves out, each labelled where it matters:

- **Fake providers.** Identity, address and screening checks are simulated with magic test values, like a provider's sandbox. Sanctions and PEP entries are **invented**: a real bank screens against the UK Sanctions List and commercial PEP data, through a screening engine.
- **A toy risk model.** Three rules. Real customer risk assessments weigh occupation, geography, product, channel and expected activity, under a documented business-wide risk assessment.
- **No authentication.** Applicants can see any application whose id they know, and reviewers identify themselves with a header. A real system takes identity and roles from Spring Security, and checks that a PEP's second approver has senior authority.
- **Synchronous checks.** Checks run inside an HTTP request. Production systems usually run them from a queue, with automatic retries and back-off.
- **One application per email address,** until the email is erased. A real bank lets declined applicants apply again later.
- **Retention for declined applications** is this tutorial's own policy (five years from the decision), not a rule quoted from the regulations. Each bank sets its own, as part of a full retention schedule.
- **Keys in configuration.** Development keys are committed on purpose and log a warning. Production keys belong in a KMS or HSM.
- **Onboarding only.** Ongoing monitoring, periodic KYC reviews and transaction monitoring happen after the account opens. The Fraud monitoring tutorial in this series covers transaction monitoring.

This tutorial explains the law in plain English so you can build sensible software and talk to compliance colleagues. It isn't legal advice: real banks' rules come from their compliance and legal teams.

Each guide's recap points to where to go next.
