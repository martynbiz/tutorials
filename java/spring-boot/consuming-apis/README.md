# Consuming APIs: Football Data Importer

A series that teaches you to **consume** an API: to call a service someone else runs, make sense of what it sends back, live within its limits, and keep a copy of its data in your own database.

The [REST API](../rest-api/README.md) series is about *building* an API, where you control both ends. This series is about the other side, and the harder one in practice: a **provider you don't control**. Its data has gaps, its errors don't always look like errors, it limits how often you may call it, and it can be slow or down when you need it. Almost every real system pulls data from somewhere like this: prices, addresses, weather, exchange rates, sports data.

---

## What you'll build

An **importer**: a Spring Boot application that copies football **teams and players** from [API-Football](https://www.api-football.com) into its own database, ready for something else to use (a game, a website, a stats tool). The importer never serves its users from API-Football directly. It **syncs from their copy, and serves from ours**.

```
  ┌────────────────────────────┐        ┌───────────────────────────┐
  │ API-Football (real)        │   or   │ Fake provider (WireMock)  │
  │ v3.football.api-sports.io  │        │ localhost:8089            │
  └─────────────┬──────────────┘        └─────────────┬─────────────┘
                │  GET /teams, /players/squads, /players/profiles
                └───────────────┬─────────────────────┘
                                ▼
                  ┌───────────────────────────┐
  you (admin) ──▶ │ Importer                  │  POST /imports/teams/{id}
   HTTP Basic     │ localhost:8080            │  GET  /imports/{id}
                  └─────────────┬─────────────┘
                                ▼
                  ┌───────────────────────────┐
                  │ Our database (H2)         │  teams, players, imports
                  └───────────────────────────┘
```

Importing one team takes three kinds of request:

| Step | Request | What we take from it |
| --- | --- | --- |
| 1 | `GET /teams?id={id}` | The team: name, country, short code, and whether it's a national team |
| 2 | `GET /players/squads?team={id}` | **Who** is in the squad today: player ids only |
| 3 | `GET /players/profiles?player={id}`, once per player we don't have yet | The player: name, nationality, position, birth year |

That's around 30 requests for one team. API-Football's free plan allows **10 a minute and 100 a day**, so a single import can run into both limits. Dealing with that properly, without wasting requests, losing work or importing anything twice, is what most of the series is about.

### What each part teaches

| Part of the importer | What it teaches |
| --- | --- |
| Fetching one team | HTTP from the client's side: requests, headers, status codes, JSON |
| Reading API-Football's replies | Errors that arrive with `200 OK`, a field that's sometimes a list and sometimes an object, "not found" that isn't a `404` |
| Storing teams and players | Your model vs theirs, create-or-update by the provider's id, many-to-many, and keeping HTTP calls out of database transactions |
| Re-importing a team | Making an import safe to repeat, and spending requests only on what you don't already have |
| Imports that take minutes | Running work in the background, `202 Accepted`, and a status you can check |
| 10 a minute, 100 a day | Reading the provider's rate-limit headers, pausing, and stopping cleanly to carry on tomorrow |
| Odd data | Treating a provider's data as untrusted input |
| A slow or broken provider | Timeouts, which failures to retry (and which never to), backoff |
| A league's worth of teams | A queue that a scheduled worker resumes each day by itself |

---

## Versions

| Thing | Version | Notes |
| --- | --- | --- |
| Spring Boot | **4.1.2** | Latest GA when these guides were written (November 2026) |
| Java | **25** (LTS) | |
| Build tool | Maven, via the project's `mvnw` wrapper | Nothing to install globally |
| Database | H2 (file-based) | Stored in `./data/` inside the importer project |
| Schema | Flyway migrations | Hibernate set to `validate` |
| Fake provider | WireMock **3.13.2**, standalone | One jar, run with `java -jar`. No Docker. The same version is used inside the tests |

If [start.spring.io](https://start.spring.io) offers a newer **4.1.x** patch, pick it. If it offers **4.2** or later, most things will still work, but check the release notes when something doesn't match.

> **Spring Boot 4 vs what you'll find online.** Most examples online are for Spring Boot 2 or 3. The [todo app README](../todo-app/README.md#versions) lists the differences that matter. For this series: `RestClient` has its own starter (`spring-boot-starter-restclient`), Jackson is version 3 (`tools.jackson.*`), and `RestTemplate` is the older client you'll still see everywhere.

---

## What you need to know already

This series assumes you've done the [todo app](../todo-app/README.md) series, or know the same things:

- **Spring Boot basics:** starters, `application.properties`, beans and constructor injection, running with `mvnw`.
- **Spring MVC:** controllers and `@GetMapping` / `@PostMapping`.
- **Spring Data JPA:** entities, repositories, relationships.
- **Flyway:** versioned migrations.
- **Spring Security basics:** a `SecurityFilterChain`, and signing in.
- **Core Java:** records, lambdas, exceptions.

You **don't** need the [REST API](../rest-api/README.md) series. This series teaches HTTP and `RestClient` from the caller's side, from scratch. If you have done it, notes marked **"Done the REST API series?"** say what you can skim.

You'll need **IntelliJ IDEA** and **curl** (which comes with Windows 10 and later, macOS and Linux). Every guide shows the IntelliJ way first and a terminal alternative second.

### An API-Football account (optional, free)

You can do the whole series without one: the importer talks to a **fake provider** on your own machine by default. At a few chosen points, a guide shows you how to point it at the real API instead, to see real teams and players arrive. For that, register for the free plan at [api-football.com](https://www.api-football.com) and keep your key somewhere safe. You'll put it in an **environment variable**, never in a file. Guide 01 explains why.

---

## Where things live

```
tutorials/
├── java/spring-boot/consuming-apis/     ← these guides
└── workspace/consuming-apis/            ← your code (git-ignored)
    ├── importer/                        ← the Spring Boot importer: generated in guide 01
    └── fake-provider/                   ← WireMock and its fictional API-Football responses: guide 01
        ├── mappings/  __files/          ← loaded at start-up: the provider behaving normally
        ├── misbehaving/                 ← switched on when a guide asks: errors, limits, slowness (guide 02 onwards)
        └── changes/                     ← switched on when a guide asks: a squad that has changed (guide 04)
```

| What | URL |
| --- | --- |
| Importer | `http://localhost:8080` |
| Fake provider | `http://localhost:8089` |
| Real provider | `https://v3.football.api-sports.io` |

**Running both.** Start the **fake provider first**, from `fake-provider/`: `java -jar wiremock-standalone-3.13.2.jar --port 8089` (guide 01 shows where the jar comes from). Restart it after adding or changing a file in `mappings/` or `__files/`. Then start the importer: in IntelliJ from the green arrow next to `main`, or `./mvnw spring-boot:run` in `importer/`. In **PowerShell**, type `.\mvnw` instead of `./mvnw`.

---

## Quick reference

Come back to this once you're a few guides in. You don't need any of it to start.

**Signing in.** From guide 05, every importer endpoint needs HTTP Basic: `admin` / `dev-only-password` (a development password, marked as such in the configuration). To use another, set the `IMPORTER_ADMIN_PASSWORD` environment variable.

**Environment variables.** `APISPORTS_KEY` holds your API-Football key, and is only needed when you point the importer at the real API. `IMPORTER_ADMIN_PASSWORD` is optional.

**Endpoints by the end of the series.**

| Request | What it does | From guide |
| --- | --- | --- |
| `GET /provider/teams/{id}` | Shows what the provider returns for a team, storing nothing | 01 |
| `POST /imports/teams/{apiId}` | Imports a team and its squad (queued for the worker, from guide 09) | 03 |
| `GET /teams` | Teams in our database | 03 |
| `GET /teams/{id}/players` | A team's players | 04 |
| `GET /imports/{id}` | One import's status | 05 |
| `GET /imports` | The 50 most recent imports | 09 |

**The import request changes as the series goes on.** Each guide explains why. If you're looking at a later guide's output, this is why yours may differ:

| Guide | `POST /imports/teams/{apiId}` answers | An import cut off by a restart |
| --- | --- | --- |
| 03 | `200` with the team, once it's stored | (no import records yet) |
| 04 | `200` with a summary of the import | (no import records yet) |
| 05–08 | `202 Accepted`, the import `RUNNING` in the background | Marked `FAILED` at start-up |
| 09 | `202 Accepted`, the import `QUEUED` for the worker | Put back in the queue |

**Making the fake provider misbehave.** Files in `misbehaving/` and `changes/` aren't loaded when WireMock starts. A guide switches one on while WireMock is running, and switches it off again afterwards:

| File | What the provider does | Guide |
| --- | --- | --- |
| `misbehaving/server-error.json` | Answers `500` | 02 |
| `misbehaving/bad-key.json` | Rejects the key (a `200` with `errors.token`) | 02 |
| `changes/squad-900001-player-left.json` | Riverside Rovers' squad without one player | 04 |
| `misbehaving/minute-limit.json` | Has no requests left this minute | 06 |
| `misbehaving/too-many-requests.json` | Answers one `429` | 06 |
| `misbehaving/daily-limit-reached.json` | Has no requests left today | 06 |
| `misbehaving/daily-exhausted-200.json`, `daily-exhausted-429.json` | Reports that the day's quota is used up, in two different shapes | 06 |
| `misbehaving/slow.json` | Answers too slowly | 08 |
| `misbehaving/service-unavailable.json` | Answers `503` once | 08 |
| `misbehaving/connection-reset.json` | Drops the connection | 08 |

The guide that introduces each file gives the exact command. In outline, from `fake-provider/`:
- Switch on one stub: `curl -X POST http://localhost:8089/__admin/mappings -d @misbehaving/server-error.json`
- Switch on a file holding several stubs, such as `minute-limit`, `daily-limit-reached` or `slow`: post it to `/__admin/mappings/import` instead.
- Switch everything back to normal: `curl -X POST http://localhost:8089/__admin/mappings/reset`, and also `curl -X POST http://localhost:8089/__admin/scenarios/reset` after a one-shot stub such as the `429`.

---

## The guides

Follow them **in order**. Each builds on the code from the one before, and ends with every test passing: 8 tests after guide 01, growing to 81 by guide 09.

| # | Guide | You'll learn |
| --- | --- | --- |
| 01 | [Calling an API](01-calling-an-api.md) | HTTP from the client's side. A fake provider with WireMock. Fetching a team with `RestClient`. JSON into records. Tests against WireMock. One call to the real API, with the key kept safe |
| 02 | [Reading the provider's replies](02-reading-the-providers-replies.md) | API-Football's response wrapper. Errors with `200 OK`. A field that changes type. "Not found" without a `404`. Our own exceptions. HTTP interface clients. Making the fake provider misbehave |
| 03 | [Storing teams](03-storing-teams.md) | Our model vs theirs. Create-or-update by the provider's id. Why HTTP calls stay outside transactions |
| 04 | [Squads and players](04-squads-and-players.md) | Many-to-many. Players who leave. Fetching only what's missing. Imports that finish another day |
| 05 | [Imports in the background](05-imports-in-the-background.md) | An admin endpoint with HTTP Basic. `202 Accepted` and a status endpoint. `@Async`. Refusing a duplicate import |
| 06 | [Rate limits and quotas](06-rate-limits-and-quotas.md) | Reading the provider's limit headers. Pausing for the minute. Stopping for the day. A `429` safety net |
| 07 | [Validating what you receive](07-validating-what-you-receive.md) | The provider's data as untrusted input: missing fields, unexpected values, oversized text |
| 08 | [When the provider fails](08-when-the-provider-fails.md) | Timeouts. Which failures to retry and which never to. Backoff. Logging every request, with the key masked |
| 09 | [The import queue](09-the-import-queue.md) | A queue of imports, run one at a time by a scheduled worker that carries on after midnight UTC by itself. A security checklist |

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

- **Base package** is `com.example.importer`. File paths are shortened, so `team/TeamImporter.java` means `src/main/java/com/example/importer/team/TeamImporter.java`.
- **Plain Java:** records for the provider's JSON and for our responses, constructor injection, no Lombok.
- **Fictional data.** The fake provider's teams and players are made up, in exactly the shape API-Football sends. When you point the importer at the real API, you'll see real data, which changes from day to day, so your output won't match the guides there.
- **Their model vs ours.** Classes that mirror API-Football's JSON live apart from our own entities, and only one place maps between them.
- **Development secrets are in the configuration** (the admin password). Each one is marked, and the guide says what a real system would do. The API-Football key is the exception: it's **never** in a file.
- **Try requests** with `curl`. Every curl command is shown on one line. In PowerShell, type `curl.exe` instead of `curl`, and `.\mvnw` instead of `./mvnw`. Windows PowerShell also strips the double quotes from JSON typed on the command line, so escape each one there: `-d '{\"fixedDelay\": 1500}'`.

---

## Deliberately out of scope

| Topic | Instead |
| --- | --- |
| **Using the data** (a game, a website) | The series stops once the data is in our database |
| **Fixtures, results and live scores** | Teams and players only. The same techniques apply |
| **Squad history by season** | A team's squad is whoever is in it today |
| **Refreshing players over time** | A player's profile is fetched once. Guide 04 explains the trade-off |
| **Building an API** | The [REST API](../rest-api/README.md) series |
| **Webhooks** (the provider calling you) | Not covered |
| **PostgreSQL or MySQL** | A sidebar in guide 03 |
| **Running more than one importer** | A sidebar in guide 09 |
| **Docker, deployment, monitoring** | Not covered |
