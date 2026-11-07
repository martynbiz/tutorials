# Open Banking API

Build the **bank's side** of UK Open Banking: the API that budgeting apps, lenders and accounting tools call to read a customer's accounts, **with that customer's consent**.

Every UK current account provider in Open Banking runs an API like this one. Behind its simple-looking `GET /accounts` sit some of the most interview-worthy topics in banking software: OAuth 2.0, fine-grained consent, regulation, and an API contract that hundreds of other companies depend on.

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for versions, conventions and where your code goes.

---

## What you'll build

A JSON API in the style of the UK Open Banking Read/Write API, as the **ASPSP** (the bank):

- **Account data:** accounts, booked and available **balances**, and **transactions**, for a small set of seeded demo customers
- **OAuth 2.0 resource server:** every request needs a signed JWT access token with the right **scope**
- **Account-access consents:** a TPP asks for specific **permissions**, the customer approves and picks accounts, and either side can withdraw
- **Consent enforcement:** every data request is checked against its consent, revocation takes effect immediately, and a race between approval and deletion can't resurrect a deleted consent
- **API versioning:** v1 and v2 side by side, with `Deprecation` and `Sunset` headers on v1

```
  moneywise (TPP)                         Your bank (ASPSP)
       │  POST /account-access-consents   ┌──────────────────────────────┐
       │─────────────────────────────────▶│ Consent: AwaitingAuthorisation│
       │                                  └──────────────┬───────────────┘
       │        Alice logs in at the bank, approves,     │
       │        picks her current account  ─────────────▶│ Authorised
       │                                                 │
       │  GET /accounts/acc-1001/balances                │
       │  Authorization: Bearer <token → consent>        │
       │─────────────────────────────────▶ token valid? consent Authorised?
       │◀──────────── balances ─────────── unexpired? permission? account?
```

### The API you'll end up with

| Method | Path | Does | Token |
|---|---|---|---|
| `POST` | `/open-banking/{v}/aisp/account-access-consents` | A TPP asks for access (needs an `x-idempotency-key` header) | TPP client credentials |
| `GET` | `/open-banking/{v}/aisp/account-access-consents/{consentId}` | The TPP checks a consent's status | TPP client credentials |
| `DELETE` | `/open-banking/{v}/aisp/account-access-consents/{consentId}` | The TPP withdraws a consent | TPP client credentials |
| `GET` | `/open-banking/{v}/aisp/accounts` | The accounts the customer shared | Consent-bound |
| `GET` | `/open-banking/{v}/aisp/accounts/{accountId}` | One shared account | Consent-bound |
| `GET` | `/open-banking/{v}/aisp/accounts/{accountId}/balances` | Booked and available balances (v1 signed, v2 with a credit/debit indicator) | Consent-bound |
| `GET` | `/open-banking/{v}/aisp/accounts/{accountId}/transactions` | Transactions, shaped by the consent's permissions | Consent-bound |
| `POST` | `/internal/account-access-consents/{consentId}/authorise` | Stand-in for the bank's consent screen: the customer approves | Customer (`psu`) |
| `POST` | `/internal/account-access-consents/{consentId}/reject` | The customer declines | Customer (`psu`) |
| `POST` | `/internal/account-access-consents/{consentId}/revoke` | Stand-in for the bank's access dashboard | Customer (`psu`) |
| `POST` | `/dev/tokens` | **Development only:** mints test tokens | None |

`{v}` is `v1` or `v2`. Every response carries an `x-fapi-interaction-id` header.

---

## Prerequisites

As well as the [series prerequisites](../README.md#prerequisites), this tutorial needs:

- **Spring Security basics:** the security **filter chain**, configuring a **`SecurityFilterChain` bean** with `HttpSecurity` and lambdas, and the difference between authentication and authorisation. You don't need to know OAuth 2.0 or JWTs: guide 02 teaches them.
- **Spring Data JPA:** entities, repositories, derived queries, and what `open-in-view=false` means for lazy loading.
- **Transactions:** what `@Transactional` does on a service method.
- **Java concurrency basics:** threads and `ExecutorService`. The concurrency test in guide 04 uses `CountDownLatch`, explained when it appears.

No real authorisation server is needed. Tests use spring-security-test's `jwt()`, and when running locally a dev-only profile signs tokens with a shared secret.

---

## The guides

Do them in order. Each one ends with every test passing, so you can stop between any two.

| # | Guide | Banking | Java / Spring |
|---|---|---|---|
| 01 | [Open Banking and account data](01-open-banking-and-account-data.md) | PSD2 and the CMA Order. ASPSP, AISP, PISP, TPP, PSU. Booked vs available balances. | Seed data in its own Flyway location, the `Data`/`Links`/`Meta` envelope, a correlation-id filter with MDC, `ProblemDetail`, `MockMvcTester` |
| 02 | [Securing the API with OAuth 2.0](02-securing-the-api-with-oauth2.md) | OAuth roles in Open Banking, strong customer authentication, FAPI | OAuth2 resource server, JWT validation (RFC 9068), scopes to authorities, 401/403 as `ProblemDetail`, filter order, `jwt()` tests, Swagger bearer auth |
| 03 | [Account-access consents](03-account-access-consents.md) | Consents, permissions and data minimisation, the consent lifecycle, the customer's authorisation journey | A state machine inside the entity, `@ElementCollection`, idempotent creation with `x-idempotency-key`, header validation, injecting a `Clock` |
| 04 | [Enforcing consents](04-enforcing-consents.md) | Consent checks on every request, immediate revocation, not leaking what exists | A consent guard, permission-shaped JSON, controllable time in tests, a race-condition test, optimistic locking with `@Version` |
| 05 | [API versioning](05-api-versioning.md) | Breaking changes in an API others depend on, deprecation and sunset | Spring Framework 7 API versioning, `Deprecation`/`Sunset` headers, OpenAPI customisation |

Allow roughly an evening per guide.

---

## Project at a glance

| | |
|---|---|
| Your project folder | `workspace/banking-systems/open-banking-api/` |
| Base package | `com.example.openbanking` (paths like `consent/Consent.java` mean `src/main/java/com/example/openbanking/consent/Consent.java`) |
| Runs on | `http://localhost:8080` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` (get a token from `POST /dev/tokens`, then click **Authorize**) |
| Database | H2 file at `./data/openbanking`, with made-up customers Alice (`acc-1001`, `acc-1002`) and Bob (`acc-2001`) |
| Tests | `./mvnw test` (63 by the end, all against an in-memory database) |

The finished package layout:

```
com.example.openbanking
├── money/       Money
├── account/     Account, AccountTransaction, Balance, AccountService, AccountController, …
├── consent/     Consent, Permission, ConsentStatus, ConsentService, ConsentGuard, controllers, …
├── security/    SecurityConfig, SecurityProblemHandler, dev-only token signing and issuing
├── api/         ObResponse, FapiInteractionIdFilter, ApiVersioningConfig, OpenApiConfig
├── config/      ClockConfig
└── web/         ApiExceptionHandler
```

---

## Simplifications

A real ASPSP's Open Banking platform does far more. The biggest things this tutorial leaves out:

- **No authorisation server.** The customer's login, SCA, consent screen and token issuing happen in a real authorisation server. Here, `/internal/...` endpoints stand in for the consent screen and a dev-only endpoint mints tokens.
- **No FAPI transport security.** Real Open Banking requires mutual TLS with certificates issued to regulated TPPs, certificate-bound tokens and signed requests. These are configured at the TLS and authorisation-server layers.
- **No TPP registration.** Real TPPs are authorised by the FCA (or another regulator) and register their software with each bank (dynamic client registration). Here, any `client_id` is accepted.
- **Simplified JSON.** Shapes are modelled on the UK Read/Write API (`Data`, `Links`, `Meta`, permission codes, statuses), but with camelCase names, far fewer fields, `ProblemDetail` errors instead of the standard's error format, and no pagination.
- **A small subset of permissions and endpoints.** No beneficiaries, standing orders, direct debits, statements, party data or products. No payment initiation (the Payments tutorial covers payments).
- **Account data is seeded, not real.** A real ASPSP reads from its core banking system. The [Core ledger](../core-ledger/README.md) tutorial covers how balances are really kept.
- **One consent model for revocation.** Both a TPP's `DELETE` and the customer's dashboard set the status to `Revoked`, and the row is kept. The standard's details differ between versions.
- **Light idempotency.** Consent creation replays on a repeated `x-idempotency-key`, but doesn't fingerprint the request body or expire keys after 24 hours.

Each guide's recap mentions the most relevant omission and where you'd go to learn it.
