# REST API: Event Tickets

A series that teaches you to build a **REST API**: a service with no pages of its own, which other applications call to read and change data. The guides cover how to design it, document it and secure it.

The [todo app](../todo-app/README.md) series builds a website: Spring renders HTML pages, and people sign in with a login form. This series builds the other kind of back end. The API's callers are **applications**: a website's server, a mobile app, a partner's system, a background job. They send JSON over HTTP and prove who they are with **access tokens**. Most of the [banking systems](../banking-systems/README.md) tutorials assume you can already build an API like this. This series is where you learn how.

---

## What you'll build

An API for booking tickets to live events: gigs, comedy, theatre and sport, at venues around the UK. Everything is fictional.

Three kinds of caller use it, and each is allowed to do different things:

| Caller | What it does | How it proves who it is |
| --- | --- | --- |
| **Anyone** | Browses venues, events and ticket prices | Nothing needed: these are public |
| **An organiser service** | Creates, changes and cancels events and their ticket types | Its own access token (*client credentials*) |
| **Customers**, through an app | Book tickets, list and cancel **their own** bookings | An access token issued **for that customer** (*authorisation code with PKCE*) |
| **A scanner service** at the venue door | Checks bookings in | Its own access token, with a different permission from the organiser |

```
                    ┌─────────────────────┐
                    │ Authorisation server │  issues access tokens
                    │ localhost:9000       │
                    └──────────┬──────────┘
                tokens         │         tokens
        ┌──────────────────────┼──────────────────────┐
        ▼                      ▼                      ▼
  Organiser service      Customer (via an app,   Scanner service
  (curl in this series)  Swagger UI here)        (command-line app)
        │                      │                      │
        │  POST /events        │  POST /bookings      │  POST /bookings/{id}/check-in
        └──────────────────────┼──────────────────────┘
                               ▼
                    ┌─────────────────────┐
                    │ Tickets API          │  checks every token,
                    │ localhost:8080       │  then does the work
                    └─────────────────────┘
```

Along the way, each part of the domain gives a reason to learn something about APIs:

| Part of the API | What it teaches |
| --- | --- |
| Browsing events | URLs and HTTP methods, status codes, JSON, paging, filtering, sorting, caching |
| Creating and changing events | Request and response objects, validation, consistent error responses, `PUT` vs `PATCH` |
| Two organisers editing the same event | Concurrent updates, with `ETag` and `If-Match` |
| Booking tickets | Who owns which data, and the API vulnerability that tops the OWASP list |
| The last ticket, wanted by two people at once | Race conditions, and how to make sure only one wins |
| A booking request sent twice after a network error | Idempotency keys, so a retry never books twice |
| Cancelling and checking in | Actions that aren't simple create/read/update/delete, and the rules about when they're allowed |
| The scanner | Calling a secured API from another Spring application |

### The API you'll end up with

| Method | Path | Does | Who can call it |
| --- | --- | --- | --- |
| `GET` | `/venues`, `/venues/{id}` | Venues | Anyone |
| `GET` | `/events` | Events, a page at a time, filtered by city, category and date | Anyone |
| `GET` | `/events/{id}` | One event | Anyone |
| `GET` | `/events/{id}/ticket-types`, `/events/{id}/ticket-types/{typeId}` | The ticket types for an event, with prices and availability | Anyone |
| `POST` | `/events` | Create an event | Organiser |
| `PUT` / `PATCH` | `/events/{id}` | Replace or update an event | Organiser |
| `DELETE` | `/events/{id}` | Delete an event with no bookings | Organiser |
| `POST` | `/events/{id}/ticket-types` | Add a ticket type | Organiser |
| `POST` | `/bookings` | Book tickets | Customer |
| `GET` | `/bookings`, `/bookings/{id}` | The customer's **own** bookings | Customer |
| `POST` | `/bookings/{id}/cancel` | Cancel a booking | Customer (their own) |
| `POST` | `/bookings/{id}/check-in` | Check a booking in at the door | Scanner |

By the end, every error, from a typo in a date to a missing token, comes back in the same format: a `ProblemDetail` ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)). The guides build that up one kind of error at a time, and guide 08 catches unexpected failures and explains the two cases left over.

---

## Versions

| Thing | Version | Notes |
| --- | --- | --- |
| Spring Boot | **4.1.2** | Latest GA when these guides were written (November 2026) |
| Spring Security | **7.1.2** | Comes with Boot. Includes the authorisation server and resource server support |
| Java | **25** (LTS) | |
| Build tool | Maven, via each project's `mvnw` wrapper | Nothing to install globally |
| Database | H2 (file-based) | Stored in `./data/` inside the API project |
| Schema | Flyway migrations | Hibernate set to `validate` |
| API documentation | SpringDoc OpenAPI, with Swagger UI | |

If [start.spring.io](https://start.spring.io) offers a newer **4.1.x** patch, pick it. If it offers **4.2** or later, most things will still work, but check the release notes when something doesn't match.

> **Spring Boot 4 vs what you'll find online.** Most blog posts and Stack Overflow answers are for Spring Boot 2 or 3. The [todo app README](../todo-app/README.md#versions) lists the differences that matter. The ones you'll meet most here:
> - The web starter is `spring-boot-starter-webmvc`, and the security starters have `security` in their names (`spring-boot-starter-security-oauth2-resource-server`).
> - The authorisation server is now part of Spring Security (`http.oauth2AuthorizationServer(...)`), and Spring Boot can set it up from properties alone, which is what guide 05 does. Older answers show a separate project with a different setup.
> - Imports use `jakarta.*`, never `javax.*`.
>
> When you're unsure of a dependency, pick the same ones on start.spring.io and click **Explore** to see the generated `pom.xml`.

---

## What you need to know already

This series assumes you've done the [todo app](../todo-app/README.md) series, or know the same things:

- **Spring Boot basics:** starters, `application.properties`, beans and constructor injection, running with `mvnw`.
- **Spring MVC:** controllers, `@GetMapping` and friends, `@PathVariable`, `@RequestParam`.
- **Spring Data JPA:** entities, repositories, `@ManyToOne` / `@OneToMany`, lazy loading.
- **Flyway:** versioned migrations, with Hibernate set to `validate`.
- **Spring Security basics:** that requests pass through a **filter chain**, and how a `SecurityFilterChain` bean is configured with `HttpSecurity` and lambdas. You don't need to know OAuth 2.0 or JWTs: guide 05 teaches them.
- **Testing:** JUnit and AssertJ. This series uses `MockMvcTester`, explained in guide 01.
- **Core Java:** records, lambdas, generics, exceptions.

You don't need the [identity provider](../identity-provider/README.md) series. It builds a full authorisation server. This series only needs a minimal one, built in a few steps in guide 05. If you've done that series, you'll recognise the ideas. A sidebar in guide 05 explains what would change to trust a different authorisation server, such as that series' Passport, Keycloak or Microsoft Entra ID.

You'll need **IntelliJ IDEA**. Every guide shows the IntelliJ way first and a terminal alternative second. The free edition is enough for everything except IntelliJ's HTTP client (the `requests.http` files), which needs the paid edition. Curl and Swagger UI do the same job. **curl** comes with Windows 10 and later, macOS and Linux.

---

## Where things live

```
tutorials/
├── java/spring-boot/rest-api/      ← these guides
└── workspace/rest-api/             ← your code (git-ignored)
    ├── api/                        ← the Tickets API: generated in guide 01
    ├── auth-server/                ← the authorisation server: generated in guide 05
    └── scanner/                    ← the scanner service: generated in guide 09
```

Three separate Maven projects, as they would be in real life: an API, the service that issues tokens, and a client are built, deployed and run independently, often by different teams.

| Project | URL |
| --- | --- |
| Tickets API | `http://localhost:8080` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| Authorisation server | `http://localhost:9000` |
| Scanner | No URL: a command-line app you run with booking ids, such as `java -jar target/scanner-0.0.1-SNAPSHOT.jar 2` |

**Running more than one.** From guide 05 on, start the **authorisation server first**. The API starts without it, but it fetches the authorisation server's public keys when the first request carrying a token arrives. If the authorisation server isn't running by then, that request fails with a `500` until you start it. From guide 09, run the scanner last, once both are up. In IntelliJ, open each project in its own window (**File → Open**, then choose **New Window**) and run each from the green arrow next to its `main` method. In a terminal, use one window each for the authorisation server and the API, each running `./mvnw spring-boot:run`. The scanner isn't a server: it runs, checks in the bookings you give it, and exits (guide 09 shows how). In **PowerShell**, type `.\mvnw` instead of `./mvnw`.

> **Plain HTTP, on purpose.** Everything runs over `http://` so you can read every request and response. **A real API must use HTTPS.** Access tokens work like cash: anyone who sees one can use it until it expires. Over plain HTTP, anyone on the network can see them.

---

## The guides

Follow them **in order**. Each builds on the code from the one before, and ends with every test passing, so you can stop between any two.

| # | Guide | You'll learn |
| --- | --- | --- |
| 01 | [REST and a first endpoint](01-rest-and-a-first-endpoint.md) | What makes an API "REST". Resources and URLs, HTTP methods, status codes, JSON. Response objects (DTOs) from day one. `404` as a `ProblemDetail`. `MockMvcTester`. Swagger UI as a tool |
| 02 | [Creating and changing data](02-creating-and-changing-data.md) | `POST`, `PUT`, `PATCH` and `DELETE`. Request objects vs entities. Validation. One error format for everything. `201 Created` and `Location` |
| 03 | [Collections and caching](03-collections-and-caching.md) | Paging, filtering and sorting, safely. `Cache-Control`, `ETag` and `304 Not Modified` |
| 04 | [Documenting the API](04-documenting-the-api.md) | OpenAPI properly: descriptions, examples, error responses, hiding internals, exporting the document. Code-first vs contract-first |
| 05 | [Securing the API: services](05-securing-the-api-services.md) | OAuth 2.0 for APIs. A minimal authorisation server. The API as a **resource server**: checking a JWT's signature, issuer, audience and expiry. Scopes. `401` vs `403`. The client credentials flow. Testing with `jwt()` |
| 06 | [Users and their bookings](06-users-and-their-bookings.md) | Tokens issued for a user, through Swagger UI's Authorize button. Ownership checks: the **#1 API vulnerability**. Mass assignment. Actions with state rules |
| 07 | [Concurrency and retries](07-concurrency-and-retries.md) | The last-ticket race, proved with a test and fixed. Optimistic locking with `ETag` / `If-Match`. Idempotency keys |
| 08 | [Hardening](08-hardening.md) | CORS, rate limiting with `429`, security headers, error messages that don't leak. The OWASP API Security Top 10 as a checklist |
| 09 | [A client service](09-a-client-service.md) | The scanner as its own Spring application: getting tokens with client credentials, calling the API with `RestClient`, handling `401`, `409` and `429` |

Allow roughly an evening per guide.

---

## How each guide works

1. **What you'll learn**
2. **Concepts**: the *why*, before any code.
3. **Steps**: numbered, with focused snippets.
4. **Your turn**: exercises that practise what the steps taught. Solutions are hidden in collapsible blocks.
5. **Checkpoint**: what you should see, and the files you should have.
6. **Common errors**: messages you're likely to hit, and what they really mean.
7. **Recap**, with interview questions and further reading.

Boxes like this are **sidebars**. They're useful background, but you can skip them on a first read:

> **Sidebar:** …

---

## Conventions

- **Base packages** are `com.example.tickets` (the API), `com.example.authserver` and `com.example.scanner`. File paths are shortened, so `event/EventController.java` means `src/main/java/com/example/tickets/event/EventController.java` in whichever project the step is about.
- **Plain Java:** records for request and response objects, constructor injection, no Lombok.
- **Money** is stored as a whole number of pence in a `long`, so there are no rounding problems. The [banking systems](../banking-systems/README.md) series covers money properly.
- **Development secrets are in the configuration** (client secrets, users' passwords). That keeps the guides readable. Each one is marked, and the guide says what a real system would do instead.
- **Try requests** in Swagger UI or with `curl`. Every curl command is shown on one line, so it pastes into any terminal. In PowerShell, type `curl.exe` instead of `curl`. Windows PowerShell 5.1 strips the double quotes from JSON bodies, though, so for commands that send JSON use Git Bash, IntelliJ's HTTP client or Swagger UI instead.

---

## Deliberately out of scope

| Topic | Instead |
| --- | --- |
| **API versioning** | The [Open Banking API](../banking-systems/open-banking-api/README.md) tutorial covers it, in guide 05 |
| **Payments** | A booking is confirmed straight away. A sidebar in guide 06 explains how a real system would take payment |
| **Allocated seating** | Tickets are general admission (a capacity per ticket type). A sidebar in guide 07 explains what seat maps would change |
| **HATEOAS** (links in responses) | Mentioned in guide 01 |
| **Contract-first design** | A sidebar in guide 04 |
| **Webhooks and asynchronous processing** | Not covered |
| **Single-page and mobile apps** | Not built. Guide 08 covers CORS, which is what a browser app would need from the API |
| **HTTPS, gateways, deployment, Docker, monitoring** | Not covered |
| **A full identity provider** | The [identity provider](../identity-provider/README.md) series builds one |
