# Banking Systems: Learn Java by Building What Banks Run

A collection of **standalone** tutorials. Each one builds a small but realistic version of a system you'd find inside a **retail or commercial bank**: a ledger, a payments engine, card authorisation, and more.

The goal is twofold:

1. **Learn the domain.** By the end of each tutorial you should be able to explain how that system works in a real bank, using the vocabulary a banking team would use.
2. **Learn the Java that goes with it.** Every system is chosen because it forces a different, interview-worthy technique: exact money arithmetic, transactions and locking, idempotency, state machines, messaging, batch jobs.

You write all the code yourself. The guides explain the concepts, show focused snippets for anything new, and hand the repetitive parts back to you as **Your turn** exercises.

---

## The systems

Each system lives in its own folder. Each folder is a short series of **3–5 guides**, and each guide ends at a working checkpoint, so you can stop between any two of them.

The systems don't depend on each other. Do them in any order, although the order below is the suggested one.

| # | System | Banking concept | Main Java/Spring skills | Status |
|---|---|---|---|---|
| 1 | [Core ledger](core-ledger/README.md) | Accounts, double-entry bookkeeping, balances, transfers | Exact money with `BigDecimal`, `@Transactional`, optimistic and pessimistic locking, deadlocks, idempotency, immutable history | **Available** |
| 2 | [Payments](payments/README.md) | Payment lifecycle (initiated → sent → settled/rejected), Faster Payments style | State machines, idempotency in depth, the transactional outbox, async processing | **Available** |
| 3 | [Card authorisation](card-authorisation/README.md) | Real-time authorisation, holds, available vs ledger balance, spending limits | Low-latency request paths, concurrency, expiring holds with scheduled jobs | **Available** |
| 4 | [Loans](loans/README.md) | Amortisation schedules, interest accrual, arrears | `java.time` date maths, rounding rules, Spring Batch | **Available** |
| 5 | [Open Banking API](open-banking-api/README.md) | Third-party access and consents (PSD2 / UK Open Banking) | OAuth2 resource server, scopes, OpenAPI, API versioning | **Available** |
| 6 | [Fraud monitoring](fraud-monitoring/README.md) | Transaction monitoring rules, alerts, case handling | Event-driven design with Kafka, rule engines, Testcontainers | **Available** |
| 7 | [Customer onboarding](customer-onboarding/README.md) | KYC/AML checks, onboarding workflow | Workflow state, PII encryption, GDPR-aware design | **Available** |
| 8 | [Reconciliation](reconciliation/README.md) | Matching the bank's records against a scheme or statement file | File parsing (CSV, ISO 20022 `camt.053`), batch matching, exception reports | **Available** |

Two series-wide references sit alongside the systems:

- **[Glossary](glossary.md):** every banking term used in the series, in plain English.
- **[Legacy cheat sheet](legacy-cheatsheet.md):** how the modern code here looks in the older Java and Spring codebases that banks often run (including Lombok).

---

## Versions

| Thing | Version | Notes |
|---|---|---|
| Spring Boot | **4.1.2** | Latest GA when these guides were written (6 November 2026) |
| Java | **25** (LTS) | Some features used need at least Java 21; they're marked where they appear |
| Build tool | Maven, via the bundled `mvnw` wrapper | Nothing to install globally |
| Database | H2 (file-based) | Stored in `./data/` inside each project |
| Schema | Flyway migrations | Hand-written, **portable** SQL with Hibernate set to `validate` |
| API | JSON REST, `ProblemDetail` errors, Swagger UI | No HTML views. Banking back ends are APIs |

If [start.spring.io](https://start.spring.io) offers a newer **4.1.x** patch when you begin, pick it. If it offers **4.2** or later, most things will still work, but check the release notes when something doesn't match.

> **Why H2 and not PostgreSQL?** H2 needs no installation, so all your attention goes on the Java and the banking. The SQL in every migration is written to also run on PostgreSQL, so switching later only takes a configuration change. Where H2 behaves differently from a production database, a sidebar says so.
>
> **Docker** is only needed for one system, **Fraud monitoring**, which runs Kafka in a container (and uses Testcontainers in its tests). Its first guide includes a quick "is Docker working?" check.

---

## Prerequisites

These tutorials **don't teach Spring basics**. You should already be comfortable with:

- **Core Java:** classes, interfaces, generics, lambdas, streams, exceptions, records
- **Spring Boot fundamentals:** beans and dependency injection, `application.properties`, starters, running with `mvnw`
- **Spring MVC:** controllers, request mapping, `@RequestBody`, `@PathVariable`, validation with `@Valid`
- **Spring Data JPA:** entities, repositories, relationships (`@OneToMany` / `@ManyToOne`), lazy loading
- **Flyway:** versioned migrations, `ddl-auto=validate`
- **Testing:** JUnit (Jupiter, versions 5 and 6 are the same API), AssertJ, `@SpringBootTest`, MockMvc

Each system's README lists anything *extra* it needs (for example, Spring Security for the Open Banking API).

You'll also need **IntelliJ IDEA**. Every guide shows the IntelliJ way first and a terminal alternative second. The free edition is enough. Ultimate-only features are marked **(Ultimate)**.

---

## Where things live

```
tutorials/
├── java/spring-boot/banking-systems/     ← these guides (committed)
│   ├── README.md
│   ├── glossary.md
│   ├── legacy-cheatsheet.md
│   └── core-ledger/
│       ├── README.md
│       └── 01-…md, 02-…md, …
└── workspace/                             ← your code (git-ignored)
    └── banking-systems/
        └── core-ledger/                   ← you generate this in guide 01
```

Your projects go in `workspace/banking-systems/<system>/`. The `workspace/` folder is git-ignored, so you can experiment freely without committing anything. No finished solution projects are provided. The solutions to each exercise are in collapsible blocks in the guides themselves.

---

## How each guide works

Every guide has the same shape:

1. **What you'll learn:** the goal.
2. **Concepts:** the *why*, before any code. This includes a **How it works in a real bank** section describing the real-world version of what you're building.
3. **Steps:** numbered, with focused snippets. Each step that adds behaviour also adds the **tests** that prove it.
4. **Your turn:** exercises to build yourself, with solutions hidden in collapsible blocks:

   <details>
   <summary>Solution</summary>

   Like this. Try first, then peek.

   </details>

5. **Checkpoint:** "these tests pass", plus the expected file tree.
6. **Common errors:** the messages you're likely to hit, and what they really mean.
7. **Recap:** what you now know, an **Interview angle** (questions a bank interviewer might ask, with model answers), and further reading.

Boxes like this are **sidebars**. They're useful background, but you can skip them on a first read:

> **Sidebar:** …

Boxes marked **In older codebases** show how the same thing tends to look in the Java 8–17 / Spring Boot 2–3 code you may meet at a bank:

> **In older codebases:** …

---

## Conventions

- **Tests are part of every step.** A checkpoint means "the tests pass", not just "the app starts". Banking code is judged on proof that it's correct.
- **Every system has a concurrency test**: many threads hitting the same account at once. You'll watch it fail, then make it pass.
- **Money is never a `double`.** Every system defines a small `Money` record (`BigDecimal` + `Currency`). It's copied into each system on purpose, so each one stands alone.
- **GBP only.** Amounts carry a currency anyway, so the multi-currency pattern is in place.
- **IntelliJ first, terminal second.** Terminal commands are written as `./mvnw …`. In PowerShell type `.\mvnw …`.
- **Plain Java:** no Lombok, constructor injection, modern Java features (records, text blocks, switch expressions, pattern matching) where they genuinely help.
- **Simplifications are labelled.** These are teaching systems. Where a real bank would do more, the guide says what and why.

## Deliberately out of scope (for now)

Multi-currency and FX, PostgreSQL, front ends, and deployment. (Docker appears only in Fraud monitoring.) Each guide's recap points to where to go next if you want to add one.
