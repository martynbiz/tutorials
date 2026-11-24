# 05: Imports in the background

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](README.md) for version notes.

## What you'll learn

- Why an import that can take **minutes** mustn't keep an HTTP request waiting
- How **`202 Accepted`** and a **`Location`** header say "I've started it; ask there how it's going", and how a **status endpoint** answers
- Why an import that fails now **ends `FAILED`** instead of answering the `POST` with a `404` or `502`, and why that's right
- How to record **each import as a row**, with its progress written **as it happens**
- How **`@Async`** runs a method on another thread, which thread, and the **proxy** rule it shares with `@Transactional`
- How to refuse a second import of the same team with **`409 Conflict`**
- What happens to an import that was **running when the importer stopped**, and why it must be marked `FAILED` at start-up
- How to put **every endpoint** behind **HTTP Basic** with **one admin user**, and what's development-only about it
- How to test background work **without waiting**: a **`SyncTaskExecutor`** in the tests

By the end, `POST /imports/teams/900001` answers at once with `202 Accepted` and `Location: /imports/1`, the import runs on a background thread, and `GET /imports/1` shows it going from `RUNNING` to `COMPLETE`, counting players as they arrive. Nothing answers without the admin's username and password.

---

## Prerequisites for this guide

You need `importer` and `fake-provider` as they were at the end of [04: Squads and players](04-squads-and-players.md), with all **39** tests passing. In particular:

- `TeamImporter.importTeam(apiId)` runs guide 04's four steps (the team, the squad, each member, `lastImportedAt`), with **no transaction** around it, and returns an `ImportResult`.
- `POST /imports/teams/{apiId}`, in `TeamController`, runs that import **while you wait** and answers `200` with the `ImportResult`.
- `GET /teams` and `GET /teams/{id}/players` read our database; `web/NotFoundException` (`"Team 999999 was not found"`) becomes a `404`.
- The fake provider has Riverside Rovers (900001), Harbour Athletic (900002) and Scotland (900100), with squads and profiles.

From the [todo app](../todo-app/README.md) series, you should know:

- **A `SecurityFilterChain`** bean, and **`@WithMockUser`** in tests ([todo app 04](../todo-app/04-registration-and-login.md) and [07](../todo-app/07-testing-unit-and-web.md)). The todo app signed people in with a form and a session cookie; this guide uses HTTP Basic instead, and explains it from scratch.
- **Proxies and self-invocation** for `@Transactional`. Guide 03 reminded you of it; `@Async` follows the same rule.

---

## Concepts

### An import is too long for one request

Until now, `POST /imports/teams/{apiId}` has done the whole import while the caller waits, and answered when it's finished. Against the fake provider that's under a second. Against the real one, it isn't:

- A first import of a 25-player club is **27 requests** (guide 04's numbers: the team, the squad, and 25 profiles).
- API-Football's free plan allows **10 requests a minute**. From guide 06, the importer pauses for a minute whenever the provider says the minute's allowance is used up. That's **two pauses** for one club: more than two minutes for one `POST`.
- From guide 06 again, an import can also stop because the **day's** 100 requests are gone, and carry on tomorrow. No HTTP request waits until tomorrow.

Keeping a request open that long causes trouble on both ends:

- **The caller gives up first.** curl waits for ever by default, but browsers, HTTP client libraries, proxies and load balancers all have timeouts, and many of them won't wait minutes. The caller then gets an error, and has no idea whether the import happened, is still happening, or never started.
- **"Try again" makes it worse.** The natural reaction to a timeout is to send the `POST` again. Now two imports of the same team are running, spending the same daily quota twice.
- **Nobody can see how it's going.** For two minutes there's nothing to look at but a spinning cursor.
- **It ties up a server thread.** Each request holds one of Tomcat's request threads for its whole length. A handful of long imports is fine; it's still a thread doing nothing but waiting.

The fix is to split "please do this" from "how's it going?".

### `202 Accepted`, and where to look

HTTP has a status for exactly this: **`202 Accepted`**, "the request has been accepted for processing, but the processing has not been completed" ([RFC 9110, section 15.3.3](https://www.rfc-editor.org/rfc/rfc9110#section-15.3.3)). The conversation becomes:

```
POST /imports/teams/900001                 ──▶  202 Accepted
                                                Location: /imports/1
                                                {"id":1,"status":"RUNNING",…}
                                                (the import carries on, on another thread)

GET /imports/1                             ──▶  200 {"status":"RUNNING","squadSize":6,"profilesFetched":2,…}
GET /imports/1      (a little later)       ──▶  200 {"status":"COMPLETE","squadSize":6,"profilesFetched":6,…}
```

- The **`POST`** does as little as possible: it records that an import has started, starts it in the background, and answers straight away.
- The **`Location`** header says where the import's **status** lives. The caller doesn't need to know how our URLs are built; it follows the header. (Here it's a relative URL, `/imports/1`, which HTTP allows: it's relative to the URL the request was sent to.)
- **`GET /imports/{id}`** is a plain read of our database: what the import's status is, how far it has got, what it skipped and why, and, if it failed, what went wrong. Ask as often as you like.

The URL of the `POST` hasn't changed since guide 03, as promised. Only the conversation has.

> **Done the REST API series?** Its guide 02 answered `POST /events` with **`201 Created`** and a `Location` pointing at the **new event**. `202` is its sibling for work that isn't finished: the `Location` points at something you can ask about the **work**, not at the finished result. Its `Location` was a full URL built with `ServletUriComponentsBuilder`; this guide's is relative. Both are valid HTTP.

### "Accepted" isn't "it worked"

There's a consequence, and it's easy to miss. In guides 03 and 04, importing a team the provider doesn't have answered the `POST` with a `404`; a broken provider gave a `502`. Now the `POST` answers before the provider has even been asked. So:

| What happens | Guide 04's `POST` | This guide's `POST` | Where you find out |
| --- | --- | --- | --- |
| The import works | `200` + the result | `202` + `Location` | `GET /imports/{id}`: `COMPLETE` |
| The provider has no such team | `404` | **`202`** + `Location` | `GET /imports/{id}`: `FAILED`, `"error":"API-Football has no team with id …"` |
| The provider is broken | `502` | **`202`** + `Location` | `GET /imports/{id}`: `FAILED`, `"error":"API-Football answered HTTP 500"` |
| That team is already being imported | (two imports run) | **`409`** | The `POST`'s own answer |

`202` means **accepted**, not **succeeded**. That's right, not a loss:

- **The `POST` can only report what it knows.** At the moment it answers, the import hasn't asked the provider anything. Saying `404` would mean asking first, which costs a request and still couldn't promise the rest of the import will work: the squad or a profile can fail two minutes later.
- **There's one place to look for the outcome.** Whether the import fails on the first request or the twenty-seventh, it ends `FAILED` with the reason in `error`, and the counts show how far it got. A caller that checks the status handles every failure the same way.
- **The `POST` still refuses what it *can* judge at once.** A team that's already being imported is known straight away, from our own database, so that's a `409` on the `POST` itself (below). So is a request without the admin's password (`401`).

### Each import is a row

"Where you find out" needs somewhere to keep the answer. Each import becomes a row in a new table, `team_import`:

| Column | What it holds |
| --- | --- |
| `team_api_id` | Which team (the provider's id) |
| `status` | `RUNNING`, `COMPLETE`, `FAILED`, or `INCOMPLETE_DAILY_QUOTA` (from guide 06) |
| `squad_size`, `profiles_fetched`, `skipped` | How far it got. `squad_size` stays `null` until the squad arrives |
| `team_import_skipped` (a second table) | The reasons players were skipped, one row each |
| `quota_remaining` | The provider's daily requests left. Created now, filled in from guide 06 |
| `error` | Why it failed, if it did |
| `created_at`, `finished_at` | When it started and ended |

Why a table, rather than a map in memory? Because it **survives a restart**: you can look up last night's import this morning. Guide 06 adds an import that stops for the day and carries on tomorrow, and guide 09 a queue of them, both of which only work if imports are rows.

The counts are written **as they happen**, each in its own short transaction, just like guide 04's players. While an import runs, `GET /imports/{id}` shows `profilesFetched` climbing; if it fails half-way, it still shows how far it got. The progress comes from `TeamImporter` through a small interface, `ImportProgress`, so the `team` package doesn't need to know `Import` rows exist.

### `@Async`: the work on another thread

How does the `POST` return while the import carries on? Spring's **`@Async`**: put it on a method, and calling that method **returns at once**, while the method body runs on a thread from a **task executor** (a pool of threads kept for background work).

- **`@EnableAsync`** on a configuration class switches it on. Without it, `@Async` is silently ignored and the method runs on the caller's thread as usual.
- **The threads come from Spring Boot.** Boot auto-configures a task executor, a thread pool whose threads are named `task-1`, `task-2` and so on, and `@Async` uses it. You'll see those names in the log, instead of the `nio-8080-exec-…` names of Tomcat's request threads. No configuration needed.
- **It works through the proxy, like `@Transactional`.** The proxy around the bean is what hands the call to the executor. A call from inside the same object (`this.run(...)`) skips the proxy, and runs **on the caller's thread**, with no error and no warning. So the method that runs imports lives in its **own bean**, `ImportRunner`, called from the controller: the same lesson as guide 03's `TeamImporter` and `TeamService`.
- **Nobody is waiting for the result.** An `@Async` method that returns `void` has no caller to throw an exception at; if it throws, Spring just logs it. So `ImportRunner.run` catches **every** exception itself and records it on the row as `FAILED`. An exception that escaped would leave the row `RUNNING` for ever.

There's one ordering detail. The `POST` saves the `RUNNING` row in a transaction that **commits before** the background work starts. If the background thread started first, it could look for a row that wasn't there yet.

### One import of a team at a time: `409 Conflict`

Two imports of the same team at once would do the same work twice, spending requests twice, and race each other linking players. So starting an import first checks for a `RUNNING` import of that team, and if there is one, refuses with **`409 Conflict`**: "the request conflicts with the current state of the resource" ([RFC 9110, section 15.5.10](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.10)). The `ProblemDetail` says which import is in the way:

```json
{"detail":"Team 900002 is already being imported (import 2)","instance":"/imports/teams/900002","status":409,"title":"Conflict"}
```

Imports of **different** teams can run side by side, each on its own `task-` thread. Finished imports, `COMPLETE` or `FAILED`, don't block anything: run the team again whenever you like.

**A known gap.** The check is "look for a `RUNNING` row, then insert one". Two `POST`s for the same team arriving in the same instant could both look, both find nothing, and both insert. For an importer with one admin typing curl commands, that's not going to happen. Guide 09, which adds a scheduled worker, discusses the real fix: making sure only one thing ever starts imports.

### When the importer stops mid-import

An import's row says `RUNNING` until the background thread writes `COMPLETE` or `FAILED`. If the importer **stops** in between (it crashes, the machine restarts, you press the red square), that write never happens. The row stays `RUNNING`, and nothing will ever finish it: the thread that would have done so is gone.

That's worse than a wrong status. **The `409` check would refuse that team for ever**, because it looks exactly like an import that's still going.

So at start-up, once the application is ready, the importer marks any import still `RUNNING` as `FAILED`, with the error `"Interrupted: the importer stopped while it was running"`, and logs a warning. That's safe because **at start-up nothing can be running yet**: every `RUNNING` row is left over from before. And because guide 04's import keeps what it did and fetches only what's missing, running the team again simply carries on.

### HTTP Basic, and one admin

Until now, anyone who could reach port 8080 could start imports, and so spend your API-Football quota. This guide closes that: **every** endpoint needs the admin's username and password, including the read-only `GET /teams` and guide 01's `GET /provider/teams/{id}`.

The importer has **one user**, the admin, and its callers are **programs and curl**, not a browser. The todo app's form login, with a session cookie, is the wrong tool. **HTTP Basic** fits:

- The client sends the username and password **with every request**, in a header: `Authorization: Basic YWRtaW46ZGV2LW9ubHktcGFzc3dvcmQ=`. That's `admin:dev-only-password` in **Base64**, an encoding, not encryption: anyone who sees the header can decode it. Over the internet, Basic is only acceptable over **HTTPS**.
- A request **without** credentials gets **`401 Unauthorized`** and a **`WWW-Authenticate: Basic …`** header, which tells the client what kind of credentials to bring. A browser responds with its own sign-in pop-up; curl sends them when you give it **`-u admin:dev-only-password`**.
- **No session, no cookie.** Each request proves who it is by itself (`STATELESS`). That also means **no CSRF protection is needed**: a CSRF attack relies on the browser attaching a cookie automatically to a forged request, and there's no cookie here. The `Authorization` header is only there when the client adds it on purpose.

**The one user comes from configuration.** Spring Boot creates a user from three properties, `spring.security.user.name`, `.password` and `.roles`, with no code. The password in this series is `dev-only-password`, and it's **development only**, as the [README](README.md#conventions) said: it's in a committed file, it's in plain text, and every reader of this guide knows it. The property can be overridden with an environment variable, `IMPORTER_ADMIN_PASSWORD`, and a real deployment would always do that, with a long random password from a secret store. A system with more than one admin, or with real people signing in, would go further: users in a database with hashed passwords, or sign-in through the organisation's identity provider.

> **Done the REST API series?** Its guide 05 secured the API with **OAuth 2.0** and **JWT** access tokens from an authorisation server, because it had several services with different permissions. That's the right tool there. One admin running an importer doesn't need an authorisation server, so this guide uses HTTP Basic. The parts you already know, `csrf.disable()` and `STATELESS` for an API without cookies, work the same way. Skim step 8.

---

## Steps

### 1. The tables: `V3__create_team_import.sql`

Create `src/main/resources/db/migration/V3__create_team_import.sql`:

```sql
create table team_import (
    id               bigint generated by default as identity primary key,
    team_api_id      bigint                   not null,
    status           varchar(30)              not null,
    squad_size       integer,
    profiles_fetched integer                  not null,
    skipped          integer                  not null,
    -- the daily requests left, as the provider last told us: guide 06
    quota_remaining  integer,
    error            varchar(500),
    created_at       timestamp with time zone not null,
    finished_at      timestamp with time zone
);

create table team_import_skipped (
    import_id bigint       not null references team_import (id),
    reason    varchar(300) not null
);
```

- The table is **`team_import`**, not `import`. An import of *what* is clearer, and a table named after a word that's so close to SQL's own vocabulary is asking for trouble.
- **`team_api_id` is not unique**: a team is imported many times, and each time is a new row. That's the history.
- **`squad_size` can be `null`**: "we haven't received the squad yet" is different from "the squad is empty".
- **`team_import_skipped`** holds the skipped reasons, one row per reason. A list of strings doesn't fit in one column, so it gets a small table of its own, pointing back at its import.

It's `V3`, after guide 03's `V1` and guide 04's `V2`. Never edit those.

### 2. The `Import` entity

Create a new package, `imports` (plural, because `import` is a Java keyword and can't be a package name), and in it `imports/ImportStatus.java`:

```java
package com.example.importer.imports;

public enum ImportStatus {
    RUNNING, COMPLETE, INCOMPLETE_DAILY_QUOTA, FAILED
}
```

`INCOMPLETE_DAILY_QUOTA` isn't used until guide 06, when an import stops because the day's requests are gone. It's here now so the column's values are settled from the start.

Then `imports/Import.java`:

```java
package com.example.importer.imports;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

/** One run of "import this team": what happened, so far or in the end. */
@Entity
@Table(name = "team_import")
public class Import {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long teamApiId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ImportStatus status;

    private Integer squadSize;

    private int profilesFetched;

    private int skipped;

    @ElementCollection
    @CollectionTable(name = "team_import_skipped", joinColumns = @JoinColumn(name = "import_id"))
    @Column(name = "reason")
    private List<String> skippedReasons = new ArrayList<>();

    private Integer quotaRemaining;

    private String error;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant finishedAt;

    protected Import() {
        // for JPA
    }

    public Import(long teamApiId, Instant createdAt) {
        this.teamApiId = teamApiId;
        this.status = ImportStatus.RUNNING;
        this.createdAt = createdAt;
    }

    void squadReceived(int size) {
        this.squadSize = size;
    }

    void profileFetched() {
        this.profilesFetched++;
    }

    void playerSkipped(String reason) {
        this.skipped++;
        this.skippedReasons.add(reason);
    }

    void finish(ImportStatus status, String error, Instant when) {
        this.status = status;
        this.error = error;
        this.finishedAt = when;
    }

    public Long getId() {
        return id;
    }

    public long getTeamApiId() {
        return teamApiId;
    }

    public ImportStatus getStatus() {
        return status;
    }

    public Integer getSquadSize() {
        return squadSize;
    }

    public int getProfilesFetched() {
        return profilesFetched;
    }

    public int getSkipped() {
        return skipped;
    }

    public List<String> getSkippedReasons() {
        return Collections.unmodifiableList(skippedReasons);
    }

    public Integer getQuotaRemaining() {
        return quotaRemaining;
    }

    public String getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
```

- **`@Table(name = "team_import")`**: the class is `Import`, the table isn't, so say which table.
- **A new import is always `RUNNING`.** The only public constructor sets it; there's no way to create an import in any other state.
- **`@ElementCollection`** maps a collection of plain values (here strings, not entities) to a table of its own. **`@CollectionTable`** names that table and the column pointing back at the import; **`@Column(name = "reason")`** names the column the strings go in. Like guide 04's `players`, it's loaded lazily.
- **The changing methods are package-private** (no `public`). Only code in the `imports` package, in practice the service in step 4, can move an import along. Everything else can only read it.
- **`Integer squadSize` and `Integer quotaRemaining`** are wrapper types because they can be `null`; `int profilesFetched` and `int skipped` start at 0 and can't.

### 3. The repository and the view

Create `imports/ImportRepository.java`:

```java
package com.example.importer.imports;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportRepository extends JpaRepository<Import, Long> {

    Optional<Import> findFirstByTeamApiIdAndStatus(long teamApiId, ImportStatus status);

    List<Import> findByStatus(ImportStatus status);
}
```

Two derived queries: "the first import of this team with this status" (for the `409` check) and "every import with this status" (for the start-up clean-up).

And `imports/ImportView.java`, the JSON shape of an import:

```java
package com.example.importer.imports;

import java.time.Instant;
import java.util.List;

public record ImportView(Long id, long teamApiId, ImportStatus status, Integer squadSize, int profilesFetched,
        int skipped, List<String> skippedReasons, Integer quotaRemaining, String error, Instant createdAt,
        Instant finishedAt) {

    static ImportView from(Import i) {
        return new ImportView(i.getId(), i.getTeamApiId(), i.getStatus(), i.getSquadSize(), i.getProfilesFetched(),
                i.getSkipped(), List.copyOf(i.getSkippedReasons()), i.getQuotaRemaining(), i.getError(),
                i.getCreatedAt(), i.getFinishedAt());
    }
}
```

Same pattern as guide 04's `TeamView`: the controller returns a record, never the entity. **`List.copyOf`** matters here: it copies the reasons out of the lazily loaded collection **while the transaction is still open**. Without it, the view would hold Hibernate's lazy list, and turning it into JSON after the transaction had closed would fail with guide 04's `LazyInitializationException`.

### 4. `ImportService`: start, progress, finish

Create `imports/ImportAlreadyRunningException.java`:

```java
package com.example.importer.imports;

public class ImportAlreadyRunningException extends RuntimeException {

    private final long importId;

    public ImportAlreadyRunningException(long teamApiId, long importId) {
        super("Team " + teamApiId + " is already being imported (import " + importId + ")");
        this.importId = importId;
    }

    public long importId() {
        return importId;
    }
}
```

Then `imports/ImportService.java`, all the database work for imports:

```java
package com.example.importer.imports;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.importer.web.NotFoundException;

/** Import rows. Every method is one short transaction. */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final ImportRepository imports;

    public ImportService(ImportRepository imports) {
        this.imports = imports;
    }

    /** A new RUNNING import, unless this team is already being imported. */
    @Transactional
    public ImportView start(long teamApiId) {
        imports.findFirstByTeamApiIdAndStatus(teamApiId, ImportStatus.RUNNING).ifPresent(running -> {
            throw new ImportAlreadyRunningException(teamApiId, running.getId());
        });
        return ImportView.from(imports.save(new Import(teamApiId, Instant.now())));
    }

    @Transactional
    public void squadReceived(long importId, int size) {
        get(importId).squadReceived(size);
    }

    @Transactional
    public void profileFetched(long importId) {
        get(importId).profileFetched();
    }

    @Transactional
    public void playerSkipped(long importId, String reason) {
        get(importId).playerSkipped(reason);
    }

    @Transactional
    public void finish(long importId, ImportStatus status, String error) {
        get(importId).finish(status, error, Instant.now());
    }

    @Transactional(readOnly = true)
    public ImportView view(long importId) {
        return ImportView.from(get(importId));
    }

    /**
     * An import still RUNNING when the app starts was cut off when the app last stopped. Without this, its team
     * could never be imported again (every attempt would get a 409).
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void failInterruptedImports() {
        for (Import running : imports.findByStatus(ImportStatus.RUNNING)) {
            log.warn("Import {} (team {}) was interrupted by a restart", running.getId(), running.getTeamApiId());
            running.finish(ImportStatus.FAILED, "Interrupted: the importer stopped while it was running",
                    Instant.now());
        }
    }

    private Import get(long importId) {
        return imports.findById(importId).orElseThrow(() -> new NotFoundException("Import", importId));
    }
}
```

- **`start`** is the `409` check from the Concepts: a `RUNNING` import of this team already? Throw. Otherwise save a new `RUNNING` row and return its view, with the id the database just gave it. When `start` returns, its transaction has **committed**: the row is there for any other thread to see.
- **`squadReceived`, `profileFetched`, `playerSkipped`, `finish`** each load the import, change it, and return. There's no `save` call: inside a transaction, JPA notices that a loaded entity has changed and writes it at commit ("dirty checking", as in guide 04's `TeamService`). Each is **its own short transaction**, so a count is in the database the moment the method returns.
- **`view`** is read-only and builds the `ImportView` inside the transaction (step 3's `List.copyOf`). An unknown id throws guide 04's **`NotFoundException`**, `"Import 999999 was not found"`, which the existing handler already turns into a `404`.
- **`failInterruptedImports`** is the start-up clean-up. **`@EventListener(ApplicationReadyEvent.class)`** asks Spring to call it once the application has fully started; Spring calls it through the bean's proxy, so its `@Transactional` applies and every change is written at commit.

Now the `409`. In `web/ApiExceptionHandler.java`, add a handler (and the import):

```java
import com.example.importer.imports.ImportAlreadyRunningException;
```

```java
    @ExceptionHandler
    ProblemDetail alreadyRunning(ImportAlreadyRunningException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
```

The same shape as the other handlers since guide 02: one exception, one status, our message as the `detail`.

### 5. Progress from the importer: `ImportProgress`

`TeamImporter` knows when each step happens; `ImportService` knows how to record it. They shouldn't know about each other: `TeamImporter` is in `team`, and `team` shouldn't depend on `imports`. A small interface in `team` sits between them.

Create `team/ImportProgress.java`:

```java
package com.example.importer.team;

/** Told about each step of an import as it happens. Every method does nothing unless overridden. */
public interface ImportProgress {

    ImportProgress NONE = new ImportProgress() {
    };

    default void squadReceived(int size) {
    }

    default void profileFetched() {
    }

    default void playerSkipped(String reason) {
    }
}
```

- **`default` methods** have a body in the interface itself, here an empty one. An implementation overrides only what it cares about.
- **`NONE`** is an implementation that overrides nothing: progress that goes nowhere. `new ImportProgress() { }` is an **anonymous class**, a class with no name, written and instantiated in one go. Step 6 writes one that does override the methods.

In `team/TeamImporter.java`, the old `importTeam(apiId)` becomes a one-liner, and the import itself moves into a second method that takes an `ImportProgress` and reports three things. The changes, in the middle of guide 04's method:

```java
    public ImportResult importTeam(long apiId) {
        return importTeam(apiId, ImportProgress.NONE);
    }

    /** As above, telling {@code progress} about each step as it happens. */
    public ImportResult importTeam(long apiId, ImportProgress progress) {
        // 1. The team itself: create or update
        Team team = teamService.saveFromProvider(provider.fetchTeam(apiId).team());

        // 2. The squad: only the members' ids. Anyone no longer in it is unlinked straight away.
        List<Long> squad = provider.fetchSquadIds(apiId);
        teamService.unlinkPlayersNotIn(team.getId(), squad);
        progress.squadReceived(squad.size());

        // 3. Each member: link if we have them, otherwise fetch their profile, create them and link them
        int profilesFetched = 0;
        List<String> skipped = new ArrayList<>();
        for (long playerApiId : squad) {
            if (teamService.playerExists(playerApiId)) {
                teamService.linkExistingPlayer(team.getId(), playerApiId);
                continue;
            }
            Optional<ApiPlayer> profile = provider.fetchProfile(playerApiId);
            profilesFetched++;
            progress.profileFetched();
            String problem = problemWith(profile);
            if (problem != null) {
                String reason = "Player " + playerApiId + ": " + problem;
                skipped.add(reason);
                progress.playerSkipped(reason);
                continue;
            }
            teamService.createAndLinkPlayer(team.getId(), profile.get());
        }

        // 4. Every member is done (linked or skipped): the import is complete
        team = teamService.markImported(team.getId(), Instant.now());
        return new ImportResult(TeamView.from(team), squad.size(), profilesFetched, skipped.size(), skipped);
    }
```

Three new lines, `progress.squadReceived(…)`, `progress.profileFetched()` and `progress.playerSkipped(reason)`, and the reason now goes in a variable so it can be used twice. Nothing else about the import changes.

Keeping `importTeam(apiId)` means guide 03 and 04's tests, which call it, don't change at all.

### 6. `@Async`: `ImportRunner`

Switch `@Async` on. Create `imports/AsyncConfig.java`:

```java
package com.example.importer.imports;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Switches @Async on. The threads come from Spring Boot's auto-configured task executor. */
@Configuration
@EnableAsync
public class AsyncConfig {
}
```

Nothing inside it: the annotation is the point. Spring Boot's task executor is already there, waiting for something to use it.

Now the bean that runs imports in the background. Create `imports/ImportRunner.java`:

```java
package com.example.importer.imports;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.example.importer.team.ImportProgress;
import com.example.importer.team.TeamImporter;

/** Runs imports in the background, recording progress and the outcome on the Import row. */
@Service
public class ImportRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportRunner.class);

    private final TeamImporter teamImporter;
    private final ImportService importService;

    public ImportRunner(TeamImporter teamImporter, ImportService importService) {
        this.teamImporter = teamImporter;
        this.importService = importService;
    }

    /** Returns at once; the work happens on another thread. Nobody is waiting, so every outcome is recorded. */
    @Async
    public void run(long importId, long teamApiId) {
        log.info("Import {} of team {} started", importId, teamApiId);
        try {
            teamImporter.importTeam(teamApiId, progressOf(importId));
            importService.finish(importId, ImportStatus.COMPLETE, null);
            log.info("Import {} of team {} complete", importId, teamApiId);
        } catch (RuntimeException e) {
            log.warn("Import {} of team {} failed: {}", importId, teamApiId, e.toString());
            importService.finish(importId, ImportStatus.FAILED, e.getMessage());
        }
    }

    private ImportProgress progressOf(long importId) {
        return new ImportProgress() {
            @Override
            public void squadReceived(int size) {
                importService.squadReceived(importId, size);
            }

            @Override
            public void profileFetched() {
                importService.profileFetched(importId);
            }

            @Override
            public void playerSkipped(String reason) {
                importService.playerSkipped(importId, reason);
            }
        };
    }
}
```

- **`@Async` on `run`**: a caller going through the proxy gets control back at once, and the body runs on one of Boot's `task-` threads.
- **The `try`/`catch`** is the "nobody is waiting" rule. Whatever happens, the row ends `COMPLETE` or `FAILED`. Guide 02's exceptions all extend `RuntimeException`, and their messages (`API-Football has no team with id 900055`, `API-Football answered HTTP 500`) become the import's `error`. It's the same text the `ProblemDetail` used to carry.
- **`progressOf(importId)`** is an anonymous `ImportProgress` that passes each step on to `ImportService`, with the import's id. Every call is a transaction of its own, so `GET /imports/{id}` sees each count as soon as it changes.
- **`ImportRunner` is its own bean**, and that's not a style choice: `@Async` only works when the call comes through the proxy, from another bean. The controller in the next step is that other bean.

> **Sidebar: Boot's task executor.** The executor `@Async` uses here is a thread pool that Spring Boot auto-configures. Its threads are the `task-1`, `task-2`… you'll see in the log, and its settings live under `spring.task.execution.*` (pool size, queue capacity, thread name prefix): see the Spring Boot reference under Further reading. This guide leaves the defaults alone. A handful of imports, mostly waiting on the network, is no load at all, and guide 09 replaces "start a thread per `POST`" with a queue that runs one import at a time.

### 7. The endpoints: `ImportController`

Create `imports/ImportController.java`:

```java
package com.example.importer.imports;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ImportController {

    private final ImportService importService;
    private final ImportRunner runner;

    public ImportController(ImportService importService, ImportRunner runner) {
        this.importService = importService;
        this.runner = runner;
    }

    /** 202 Accepted: "we've got it, it's not done yet". The Location header says where to check. */
    @PostMapping("/imports/teams/{apiId}")
    public ResponseEntity<ImportView> importTeam(@PathVariable long apiId) {
        ImportView started = importService.start(apiId); // committed before the background work begins
        runner.run(started.id(), apiId);
        return ResponseEntity.accepted().location(URI.create("/imports/" + started.id())).body(started);
    }

    @GetMapping("/imports/{id}")
    public ImportView status(@PathVariable long id) {
        return importService.view(id);
    }
}
```

The `POST`, line by line:

1. **`importService.start(apiId)`** saves the `RUNNING` row, or throws `ImportAlreadyRunningException` (a `409`, and nothing is started). Its transaction has committed by the time it returns.
2. **`runner.run(...)`** goes through `ImportRunner`'s proxy, which hands the work to a `task-` thread and returns **immediately**.
3. **`ResponseEntity.accepted()`** is `202`. **`.location(...)`** sets the `Location` header, and **`.body(started)`** sends the import as it was when it started: `RUNNING`, with the id the caller needs.

`ResponseEntity` lets a controller set the status and headers, not only the body, which a plain return value can't. The `GET` doesn't need it: a plain `ImportView` is a `200`.

**The same URL can't be in two controllers.** Remove guide 04's `POST` from `team/TeamController.java`: delete the `importTeam` method, the `TeamImporter` field and constructor parameter, and the `PostMapping` import. What's left:

```java
@RestController
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @GetMapping("/teams")
    public List<TeamView> teams() {
        return teamService.findAll().stream().map(TeamView::from).toList();
    }
```

(followed by guide 04's `GET /teams/{id}/players`, unchanged). Forget this, and the importer won't start: Spring refuses two handlers for `POST /imports/teams/{apiId}`.

`TeamImporter.importTeam` still returns an `ImportResult`. Nothing uses it over HTTP any more, but guide 04's tests check it, and it costs nothing to keep.

### 8. HTTP Basic for every endpoint

**The dependencies.** In `pom.xml`, add Spring Security, and its test support, next to the other starters:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
```

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security-test</artifactId>
            <scope>test</scope>
        </dependency>
```

(These are exactly what start.spring.io adds for **Spring Security** with Boot 4.) Reload Maven in IntelliJ: the **M** icon that appears, or **Maven tool window → Reload All Maven Projects**. The terminal picks them up on the next `./mvnw` command.

**The admin user.** At the end of `src/main/resources/application.properties`:

```properties
# The one admin user (HTTP Basic).
# DEVELOPMENT ONLY: a real password comes from the environment, never from a committed file.
spring.security.user.name=admin
spring.security.user.password=${IMPORTER_ADMIN_PASSWORD:dev-only-password}
spring.security.user.roles=ADMIN
```

- These three properties are Spring Boot's own. From them it builds an **in-memory user** (an `InMemoryUserDetailsManager`), with no code from you. You'll see it in the start-up log: `Global AuthenticationManager configured with UserDetailsService bean with name inMemoryUserDetailsManager`.
- **`${IMPORTER_ADMIN_PASSWORD:dev-only-password}`** is the same `${NAME:default}` form as guide 01's `api-football.key`: the environment variable if it's set, otherwise `dev-only-password`. The default is there so the series works out of the box. On any machine that isn't yours alone, set the variable.
- **The password is stored as it is**, in plain text in memory, because there's no password encoder in this project. That's acceptable for a development default everyone already knows; a real user store keeps only **hashes** (as the todo app's did, with BCrypt).

**The rules.** Create `security/SecurityConfig.java`, in a new `security` package:

```java
package com.example.importer.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    /**
     * Every endpoint is for the admin, who sends a username and password with each request (HTTP Basic).
     * No sessions and no login page, so no session cookie for CSRF to abuse: CSRF protection is off.
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(requests -> requests.anyRequest().hasRole("ADMIN"))
                .httpBasic(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
```

- **`anyRequest().hasRole("ADMIN")`**: one rule for everything. Every URL, including ones that don't exist, needs a signed-in user with the `ADMIN` role, which the property `spring.security.user.roles=ADMIN` gave our admin. Unlike the todo app, there's no public page at all: no `permitAll()` anywhere.
- **`httpBasic(Customizer.withDefaults())`** switches HTTP Basic on, with its default settings: read the `Authorization: Basic …` header, and answer a request without one with `401` and `WWW-Authenticate: Basic …`. There's no `formLogin()`, so there's no login page.
- **`STATELESS`** means Spring Security never creates an HTTP session, so there's never a session cookie. Every request brings its own credentials.
- **`csrf.disable()`**: with no cookie, there's nothing for a cross-site request to borrow (see the Concepts). Leave CSRF on and every `POST` would get a `403`, because curl has no CSRF token to send.

> **Done the REST API series?** Its `SecurityConfig` had the same `csrf.disable()` and `STATELESS` lines for the same reason, with `oauth2ResourceServer(...)` where this one has `httpBasic(...)`. Its `/error` was public; here nothing is, which is fine for one admin who always sends credentials.

### 9. Try it

Start the fake provider, if it isn't running, and then the importer. Start from an empty database, so that your ids match the ones below: stop the importer, delete `importer/data/`, then start it again. Watch the log for Flyway:

```
Migrating schema "PUBLIC" to version "3 - create team import"
```

(On a fresh database you'll see versions 1 and 2 first.) Then, with the fake provider on 8089 and the importer on 8080 (in PowerShell, type `curl.exe` instead of `curl` throughout):

**Without credentials:**

```bash
curl -i -X POST http://localhost:8080/imports/teams/900001
```

```
HTTP/1.1 401
WWW-Authenticate: Basic realm="Realm", charset="UTF-8"
```

No body: the `WWW-Authenticate` header is the answer, "bring a username and password". `realm` is a label for the protected area; `charset="UTF-8"` tells the client how to encode a password that isn't plain ASCII. (Spring Security also adds a few headers of its own to every response, such as `Cache-Control` and `X-Content-Type-Options`. They're left out of the outputs in this guide.)

**With them**, using curl's `-u`, which builds the `Authorization: Basic …` header for you:

```bash
curl -i -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900001
```

```
HTTP/1.1 202
Location: /imports/1
Content-Type: application/json

{"id":1,"teamApiId":900001,"status":"RUNNING","squadSize":null,"profilesFetched":0,"skipped":0,"skippedReasons":[],"quotaRemaining":null,"error":null,"createdAt":"2026-11-16T11:11:08.335760500Z","finishedAt":null}
```

`202 Accepted`, a `Location`, and the import as it was the moment it started: `RUNNING`, with no squad yet. Now follow the `Location`. Straight away, it's probably still running (the first import after start-up is the slowest, about two and a half seconds, while the HTTP client and Hibernate warm up):

```bash
curl -u admin:dev-only-password http://localhost:8080/imports/1
```

```
{"id":1,"teamApiId":900001,"status":"RUNNING","squadSize":null,"profilesFetched":0,…}
```

A couple of seconds later:

```
{"id":1,"teamApiId":900001,"status":"COMPLETE","squadSize":6,"profilesFetched":6,"skipped":0,"skippedReasons":[],"quotaRemaining":null,"error":null,"createdAt":"2026-11-16T11:11:08.335761Z","finishedAt":"2026-11-16T11:11:10.906391Z"}
```

`COMPLETE`, with guide 04's numbers for Riverside Rovers: six in the squad, six profiles fetched. Your times will differ, of course. (`createdAt` has seven decimal places in the `POST`'s answer and six when read back from H2, as guide 04's `lastImportedAt` did: H2 stores microseconds.) `quotaRemaining` stays `null` until guide 06.

**Look at the importer's log.** The import didn't run on a request thread:

```
INFO … [         task-1] c.example.importer.imports.ImportRunner  : Import 1 of team 900001 started
INFO … [         task-1] c.example.importer.imports.ImportRunner  : Import 1 of team 900001 complete
```

`task-1` is a thread from Boot's task executor, which `@Async` handed the work to. Request threads in this log are named `nio-8080-exec-…`.

**A conflict.** Against the fake provider an import is over before you can type the next command, so slow the provider down. WireMock can add a delay to every response, through its admin API:

```bash
curl -X POST http://localhost:8089/__admin/settings -d '{"fixedDelay": 1500}'
```

In PowerShell, the double quotes inside the JSON need a backslash each, or `curl.exe` receives them stripped out and WireMock answers `Error parsing JSON`:

```powershell
curl.exe -X POST http://localhost:8089/__admin/settings -d '{\"fixedDelay\": 1500}'
```

Every reply from the fake provider now takes a second and a half, so importing Harbour Athletic (seven requests) takes about ten seconds. Start it, and then, **quickly**, start it again:

```bash
curl -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900002
curl -i -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900002
```

The first answers `202`, with `Location: /imports/2`. The second:

```
HTTP/1.1 409
Content-Type: application/problem+json

{"detail":"Team 900002 is already being imported (import 2)","instance":"/imports/teams/900002","status":409,"title":"Conflict"}
```

Import 2 is `RUNNING`, so a second import of the same team is refused, and the `detail` says which import to look at. Nothing new was started: there's no import 3.

Take the delay off again (in PowerShell, `-d '{\"fixedDelay\": 0}'`):

```bash
curl -X POST http://localhost:8089/__admin/settings -d '{"fixedDelay": 0}'
```

When the import has finished:

```bash
curl -u admin:dev-only-password http://localhost:8080/imports/2
```

```
{"id":2,"teamApiId":900002,"status":"COMPLETE","squadSize":5,"profilesFetched":5,"skipped":1,"skippedReasons":["Player 9000014: the provider has no profile"],"quotaRemaining":null,"error":null,"createdAt":"…","finishedAt":"…"}
```

Guide 04's empty profile, M. Duffy, is in `skippedReasons`, and it's still `COMPLETE`: a skipped player counts as done. Once it's finished, `POST /imports/teams/900002` is accepted again.

**Everything is protected now.** `curl http://localhost:8080/teams` without `-u` is a `401` too, and so is guide 01's `GET /provider/teams/900001`. From now on, every curl command to the importer in this series has `-u admin:dev-only-password`.

### 10. Stop the importer in the middle of an import

Now the case from "When the importer stops mid-import". You need an import that's running when the importer dies, so slow the provider down again, as in step 9 (`{"fixedDelay": 1500}`), and start importing Scotland:

```bash
curl -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900100
```

It answers `202` with `Location: /imports/3`. Now, while it's running, **kill** the importer. Not a polite stop: a crash or a power cut doesn't give an application the chance to tidy up, so imitate one. Every log line shows the importer's process id after the level, `INFO 1716 --- [importer] …`; use your own number:

- **Windows (PowerShell):** `Stop-Process -Id 1716 -Force`
- **macOS or Linux:** `kill -9 1716`

The log just stops. The last line is `Import 3 of team 900100 started`, and nothing ever wrote `COMPLETE` or `FAILED` on its row: in the database, import 3 is still `RUNNING`.

Take the delay off (`{"fixedDelay": 0}`) and start the importer again. Just after `Started ImporterApplication`, the log says:

```
WARN … [           main] c.e.importer.imports.ImportService       : Import 3 (team 900100) was interrupted by a restart
```

```bash
curl -u admin:dev-only-password http://localhost:8080/imports/3
```

```
{"id":3,"teamApiId":900100,"status":"FAILED","squadSize":null,"profilesFetched":0,"skipped":0,"skippedReasons":[],"quotaRemaining":null,"error":"Interrupted: the importer stopped while it was running",…}
```

(How far your import got before you killed it depends on your timing, so your counts may not be zeros.) And Scotland can be imported again:

```bash
curl -i -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900100
```

`202`, not `409`. Without step 4's `failInterruptedImports`, it would have been `409` for ever.

**Look at the `Location`, though.** It's `/imports/33`, not `/imports/4`. H2 hands out identity values in **blocks of 32** and keeps the unused rest of the block in memory; when the process is killed, that rest is lost, and the next id comes from a new block. Every hard kill can skip ids like this. It's harmless: ids are unique, not consecutive, and nothing should ever rely on there being no gaps. (Your number will be different if you've killed the importer before.)

> **Sidebar: a normal stop.** This guide kills the importer on purpose, because a crash is the case that matters: nothing can tidy up. What happens to a running import when you stop the importer **normally** (Ctrl+C in the terminal) wasn't tried for this series. Spring Boot shuts its task executor down as part of stopping, which interrupts a running import; whether the import then manages to write `FAILED` before the database closes depends on timing. Either way, `failInterruptedImports` catches whatever is left `RUNNING` at the next start-up, which is why it's there.

### 11. Tests: background work, without waiting

Run the tests now and the import tests are in trouble: `POST /imports/teams/…` returns before the import has run, so anything that checks the result straight afterwards sees `RUNNING`, or a team that isn't stored yet. The usual "fix" is to sleep, or to poll until the status changes, which makes the tests slow, and flaky on a slow machine.

There's a better way. `@Async` doesn't care **which** executor it hands the work to. In the tests, give it one that runs the work **on the calling thread**, at once: Spring's **`SyncTaskExecutor`**. Then the import has finished by the time the `POST` returns.

Create `src/test/java/com/example/importer/SynchronousAsync.java`:

```java
package com.example.importer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * In tests, @Async methods run on the calling thread, so a test can check an import's outcome straight after
 * starting it: no waiting, no sleeping. (Spring Boot's own executor backs off when this one exists.)
 */
@TestConfiguration
public class SynchronousAsync {

    @Bean
    SyncTaskExecutor taskExecutor() {
        return new SyncTaskExecutor();
    }
}
```

- **`@TestConfiguration`** is a configuration class for tests only. It isn't picked up by itself; something has to import it (next).
- **The bean replaces Boot's executor.** Boot only auto-configures its task executor when there's no `Executor` bean already; this is one, so Boot's backs off, and `@Async` uses the only executor there is. (Calling it `taskExecutor`, the name Boot's own has, keeps things unambiguous if another executor ever appears: that's the name `@Async` falls back to when there's more than one.)

Add it to every Spring test at once, in the meta-annotation from guide 01, `ImporterIntegrationTest.java`:

```java
import org.springframework.context.annotation.Import;
```

```java
/** The whole app, with an in-memory database instead of ./data/importer, and @Async methods run at once. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:importer-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(SynchronousAsync.class)
public @interface ImporterIntegrationTest {
}
```

**`@Import`** adds the configuration class to the test's application context. (It's Spring's `org.springframework.context.annotation.Import`, nothing to do with our `Import` entity, which lives in another package.)

**What this proves, and what it doesn't.** With a `SyncTaskExecutor`, the tests prove what an import **does**: the statuses, the counts, the errors, the `409`. They can't prove that it runs on **another thread**: in the tests, everything runs on `main`. That part you checked in step 9, with `task-1` in the log. Exercise 4 shows why that matters.

### 12. Tests for imports

Create `src/test/java/com/example/importer/imports/ImportApiTest.java`:

```java
package com.example.importer.imports;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.example.importer.ImporterIntegrationTest;
import com.example.importer.provider.ProviderJson;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

@ImporterIntegrationTest
@WithMockUser(roles = "ADMIN")
class ImportApiTest {

    @RegisterExtension
    static WireMockExtension provider = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        registry.add("api-football.base-url", provider::baseUrl);
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ImportRepository imports;

    @Autowired
    ImportService importService;

    @Test
    void postAcceptsTheImportAndSaysWhereToCheckOnIt() {
        stubTeam(900051);
        stubSquad(900051, 9000511);
        stubProfile(9000511, ProviderJson.profile(9000511, "A. Player"));

        MvcTestResult result = mvc.post().uri("/imports/teams/900051").exchange();

        assertThat(result).hasStatus(HttpStatus.ACCEPTED)
                .bodyJson().isLenientlyEqualTo("""
                        {"teamApiId":900051,"status":"RUNNING"}
                        """);
        String location = result.getResponse().getHeader("Location");
        assertThat(location).matches("/imports/\\d+");

        assertThat(mvc.get().uri(location))
                .hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        {"teamApiId":900051,"status":"COMPLETE","squadSize":1,"profilesFetched":1,"skipped":0,
                         "skippedReasons":[],"error":null}
                        """);
    }

    @Test
    void theStatusShowsSkippedPlayersAndWhy() {
        stubTeam(900052);
        stubSquad(900052, 9000521, 9000522);
        stubProfile(9000521, ProviderJson.profile(9000521, "A. Player"));
        stubProfile(9000522, ProviderJson.noProfile(9000522));

        String location = mvc.post().uri("/imports/teams/900052").exchange().getResponse().getHeader("Location");

        assertThat(mvc.get().uri(location))
                .bodyJson().isLenientlyEqualTo("""
                        {"status":"COMPLETE","squadSize":2,"profilesFetched":2,"skipped":1,
                         "skippedReasons":["Player 9000522: the provider has no profile"]}
                        """);
    }

    @Test
    void aTeamAlreadyBeingImportedIsAConflict() {
        Import running = imports.save(new Import(900053, Instant.now()));

        assertThat(mvc.post().uri("/imports/teams/900053"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().isLenientlyEqualTo("""
                        {"status":409,"title":"Conflict",
                         "detail":"Team 900053 is already being imported (import %d)"}
                        """.formatted(running.getId()));
    }

    @Test
    void whenTheProviderFailsTheImportIsMarkedFailed() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(serverError()));

        String location = mvc.post().uri("/imports/teams/900054").exchange().getResponse().getHeader("Location");

        assertThat(mvc.get().uri(location))
                .bodyJson().isLenientlyEqualTo("""
                        {"status":"FAILED","error":"API-Football answered HTTP 500"}
                        """);
    }

    @Test
    void aTeamTheProviderDoesNotHaveFailsTheImport() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(okJson(ProviderJson.noTeam(900055))));

        String location = mvc.post().uri("/imports/teams/900055").exchange().getResponse().getHeader("Location");

        assertThat(mvc.get().uri(location))
                .bodyJson().isLenientlyEqualTo("""
                        {"status":"FAILED","error":"API-Football has no team with id 900055"}
                        """);
    }

    @Test
    void anUnknownImportIs404() {
        assertThat(mvc.get().uri("/imports/999999"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().isLenientlyEqualTo("""
                        {"status":404,"detail":"Import 999999 was not found"}
                        """);
    }

    @Test
    void importsLeftRunningByARestartAreMarkedFailed() {
        Import cutOff = imports.save(new Import(900056, Instant.now()));

        importService.failInterruptedImports();

        assertThat(importService.view(cutOff.getId()).status()).isEqualTo(ImportStatus.FAILED);
        assertThat(importService.view(cutOff.getId()).error())
                .isEqualTo("Interrupted: the importer stopped while it was running");
    }

    private void stubTeam(long id) {
        provider.stubFor(get(urlPathEqualTo("/teams")).withQueryParam("id", equalTo(String.valueOf(id)))
                .willReturn(okJson(ProviderJson.team(id, "Team " + id, null, "England", false))));
    }

    private void stubSquad(long teamId, long... playerIds) {
        provider.stubFor(get(urlPathEqualTo("/players/squads")).withQueryParam("team", equalTo(String.valueOf(teamId)))
                .willReturn(okJson(ProviderJson.squad(teamId, playerIds))));
    }

    private void stubProfile(long id, String json) {
        provider.stubFor(get(urlPathEqualTo("/players/profiles")).withQueryParam("player", equalTo(String.valueOf(id)))
                .willReturn(okJson(json)));
    }
}
```

- **`@WithMockUser(roles = "ADMIN")`** (the todo app's guide 07) runs every test in the class as a signed-in user with the `ADMIN` role, so the security rules let the requests through. This class is about imports; step 13 tests the security itself.
- **`postAcceptsTheImportAndSaysWhereToCheckOnIt`** is the whole conversation in one test: `202`, a body that says `RUNNING` (it was captured when the import started, before the run), a `Location` that **`matches`** the regular expression `/imports/\d+` (our id depends on what other classes stored first), and then a `GET` of that `Location` that says `COMPLETE`. It's `COMPLETE` without any waiting because of step 11.
- **`.exchange()`** (guide 03) performs the request and gives you the result, so you can read the `Location` header from it with **`getResponse().getHeader("Location")`**, and follow it like a real client would.
- **`aTeamAlreadyBeingImportedIsAConflict`** saves a `RUNNING` import **directly**, with the repository, rather than trying to start two imports at the same moment. With a `SyncTaskExecutor`, an import started through the `POST` has always finished before the next line, so this is the only way to have one "running". **`.formatted(...)`** fills the `%d` in with that import's id.
- **The two `FAILED` tests** are this guide's other side: the cases that used to be a `502` and a `404` on the `POST` now end the import `FAILED`, with the same message in `error`.
- **`importsLeftRunningByARestartAreMarkedFailed`** calls `failInterruptedImports()` directly, as Spring does at start-up.
- **Ids 900051 to 900056**: this class's own range, because all the test classes share one in-memory database (guide 03, step 10).

### 13. The other tests, behind security

The rest of the suite needs three changes.

**`TeamApiTest`.** Two of its tests checked guide 04's synchronous `POST`: `postImportsATeamAndReturnsIt` (the `ImportResult`) and `importingATeamTheProviderDoesNotHaveIs404`. Neither is true any more, and `ImportApiTest` now covers both cases, so **delete them**. The two tests that use the `POST` to set things up now expect `202`. In `getTeamsListsWhatWeStored`:

```java
        assertThat(mvc.post().uri("/imports/teams/900032")).hasStatus(HttpStatus.ACCEPTED);
```

and in `listsATeamsPlayers`:

```java
        assertThat(mvc.post().uri("/imports/teams/900033")).hasStatus(HttpStatus.ACCEPTED);
```

They go straight on to check `GET /teams` and `GET /teams/{id}/players`, which only works because the import has already finished (step 11).

**Run the tests now:**

```bash
./mvnw test
```

Tests in `TeamApiTest` and `ProviderLookupControllerTest` fail like this:

```
[HTTP status code] 
expected: 202
 but was: 401
```

(and `expected: 404`, `but was: 401`, and so on). That's security working: those tests send no credentials, and every endpoint now needs them. Add `@WithMockUser(roles = "ADMIN")` to **both classes**, above the class, as in `ImportApiTest`:

```java
import org.springframework.security.test.context.support.WithMockUser;
```

```java
@ImporterIntegrationTest
@WithMockUser(roles = "ADMIN")
class TeamApiTest {
```

**`SecurityTest`.** `@WithMockUser` skips the password check entirely, so nothing so far proves that the real admin gets in and everyone else doesn't. Create `src/test/java/com/example/importer/security/SecurityTest.java`, **without** `@WithMockUser`:

```java
package com.example.importer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.example.importer.ImporterIntegrationTest;

@ImporterIntegrationTest
class SecurityTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void withoutCredentialsItAsksForThem() {
        assertThat(mvc.get().uri("/teams"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .headers().hasValue("WWW-Authenticate", "Basic realm=\"Realm\", charset=\"UTF-8\"");
    }

    @Test
    void aWrongPasswordIsRefused() {
        assertThat(mvc.get().uri("/teams").with(httpBasic("admin", "guess")))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theAdminGetsIn() {
        assertThat(mvc.get().uri("/teams").with(httpBasic("admin", "dev-only-password")))
                .hasStatusOk();
    }

    @Test
    void startingAnImportNeedsCredentialsToo() {
        assertThat(mvc.post().uri("/imports/teams/900001")).hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
```

- **`httpBasic("admin", "dev-only-password")`**, from spring-security-test, adds a real `Authorization: Basic …` header to the request, like `curl -u`. Unlike `@WithMockUser`, the username and password really are checked, against the user from `application.properties`.
- **`headers().hasValue(...)`** checks the whole `WWW-Authenticate` value, including Spring Security 7's `charset="UTF-8"`.
- **`startingAnImportNeedsCredentialsToo`**: the one endpoint that spends quota, checked on its own. No provider is stubbed: the request never gets as far as the controller.

Run everything again: right-click `src/test/java` → **Run 'All Tests'**, or:

```bash
./mvnw test
```

(`.\mvnw test` in PowerShell.)

```
[INFO] Tests run: 48, Failures: 0, Errors: 0, Skipped: 0
```

---

## Your turn

### Exercise 1: predict the answers

With the importer and the fake provider running (and no `fixedDelay`), predict what each of these answers, the status and the body, before you run it:

1. `curl -i -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/99999999`, and then a `GET` of the `Location` it gives you.
2. `curl -i -u admin:dev-only-password http://localhost:8080/imports/999`
3. `curl -i -u admin:guess http://localhost:8080/teams`

<details>
<summary>Solution</summary>

1. **`202 Accepted`**, with a `Location`, and a body that says `RUNNING`, even though the provider has never heard of team 99999999. The `POST` doesn't ask the provider anything; it accepts the work. The `GET` a moment later tells the truth:

   ```
   {"id":…,"teamApiId":99999999,"status":"FAILED","squadSize":null,"profilesFetched":0,"skipped":0,"skippedReasons":[],"quotaRemaining":null,"error":"API-Football has no team with id 99999999",…}
   ```

   In guide 04 this was a `404` on the `POST`. The same exception, with the same message, now lands in `error` instead (the Concepts' "Accepted isn't it worked").

2. **`404`**, as a `ProblemDetail`:

   ```
   {"detail":"Import 999 was not found","instance":"/imports/999","status":404,"title":"Not Found"}
   ```

   The handler is guide 04's `NotFoundException` one; `ImportService.get` throws it.

3. **`401`**, with `WWW-Authenticate: Basic realm="Realm", charset="UTF-8"` and no body: exactly the same as no credentials at all. The importer doesn't tell a caller whether it was the username or the password that was wrong, or whether there were any credentials, which gives someone guessing nothing to go on.

</details>

### Exercise 2: a rejected request fails the import

`ImportApiTest` covers a provider that answers `500` and a team the provider doesn't have. Guide 02's third kind of trouble is a **rejected** request: a `200 OK` with the reasons in `errors`. Add a test, `aRejectedRequestFailsTheImport`, that stubs a rejected reply for every `/teams` request, starts an import, and checks that it ends `FAILED` with the provider's reason in `error`.

Which team id should it use? What exactly will `error` say?

<details>
<summary>Solution</summary>

```java
@Test
void aRejectedRequestFailsTheImport() {
    provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(okJson(ProviderJson.rejected("""
            {"token":"Error/Missing application key."}
            """))));

    String location = mvc.post().uri("/imports/teams/900057").exchange().getResponse().getHeader("Location");

    assertThat(mvc.get().uri(location))
            .bodyJson().isLenientlyEqualTo("""
                    {"status":"FAILED",
                     "error":"API-Football rejected the request: {token=Error/Missing application key.}"}
                    """);
}
```

- **The id:** 900057, the next one in this class's range (900051 to 900056). Any id no other class uses would do.
- **The message** is guide 02's `ProviderRejectedException`'s: `API-Football rejected the request: ` followed by the `errors` map as Java prints it, `{token=…}`. It's the same text guide 02's `502` had in its `detail`; only where it ends up has changed.

No change to the main code: `ImportRunner` catches every `RuntimeException`, and `ProviderRejectedException` is one.

</details>

### Exercise 3: only a running import blocks

The `409` is for an import that's **running**. Prove the other half of the rule with two tests in `ImportApiTest`:

1. `aFinishedImportDoesNotBlockTheNextOne`: a team whose last import **failed** can be imported again.
2. `anotherTeamCanBeImportedWhileOneIsRunning`: a `RUNNING` import of one team doesn't stop an import of another.

Hint: `aTeamAlreadyBeingImportedIsAConflict` shows how to get an import into the database without starting it, and `ImportService` has a public method that finishes one. Do you need to stub the provider?

<details>
<summary>Solution</summary>

```java
@Test
void aFinishedImportDoesNotBlockTheNextOne() {
    Import earlier = imports.save(new Import(900058, Instant.now()));
    importService.finish(earlier.getId(), ImportStatus.FAILED, "Stopped by the test");

    assertThat(mvc.post().uri("/imports/teams/900058")).hasStatus(HttpStatus.ACCEPTED);
}

@Test
void anotherTeamCanBeImportedWhileOneIsRunning() {
    imports.save(new Import(900059, Instant.now()));

    assertThat(mvc.post().uri("/imports/teams/900060")).hasStatus(HttpStatus.ACCEPTED);
}
```

- **`new Import(...)` is always `RUNNING`**, so the first test finishes it with **`importService.finish`**, the same method `ImportRunner` uses.
- **No stubs needed.** Both tests are about whether the `POST` is **accepted**, and that's decided before the provider is asked anything. The imports themselves fail (WireMock has no stub, so it answers `404`, and the log says `Import … failed: …ProviderHttpException: API-Football answered HTTP 404`), but they fail **after** the `202`, on their own row. That's this guide's whole point, seen from a test.
- Ids 900058 to 900060, still this class's own.

With exercises 2 and 3 kept, `./mvnw test` runs 51.

</details>

### Exercise 4: a colleague tidies up `AsyncConfig`

A colleague sees an empty class, `AsyncConfig`, and deletes the `@EnableAsync` line ("it doesn't do anything"). Answer without running anything:

1. Do the tests still pass?
2. What changes when you `POST` an import against the running importer? What does the `202`'s body say? Where would you look to prove what happened?

<details>
<summary>Solution</summary>

1. **Yes, all of them.** Without `@EnableAsync`, `@Async` is ignored and `run` executes on the caller's thread, which is exactly what the tests' `SyncTaskExecutor` was doing anyway. The tests were never able to tell "on another thread" from "on this thread" (step 11, "What this proves, and what it doesn't"). For this series, removing the line and running `ImportApiTest` and `TeamApiTest` confirmed it: everything passed.
2. **The `POST` waits for the whole import again**, as in guide 04, because the import now runs on the Tomcat request thread. It still answers `202` and a `Location`, and the body still says `RUNNING`, because `start` captured it before the import ran: the response is now **wrong**, claiming work is in progress that has already finished. Against the real provider, the request would take minutes again. The proof is in the **log**: `Import … started` would be on an `nio-8080-exec-…` thread instead of `task-…`.

The same thing happens if someone "simplifies" by calling `run` from inside `ImportService` (`this.run(...)`): self-invocation skips the proxy, so `@Async` is skipped too. Whenever behaviour depends on a proxy, a test with everything on one thread can't see it break; a look at the thread names can.

</details>

### Exercise 5: a password that isn't the default

The Concepts said a real deployment never uses `dev-only-password`. Run the importer with the admin password set through `IMPORTER_ADMIN_PASSWORD` instead, and show with curl that the default no longer works and the new one does. Which of the tests would still pass if you ran them in a terminal where `IMPORTER_ADMIN_PASSWORD` is set?

<details>
<summary>Solution</summary>

Set the variable the way guide 01 set `APISPORTS_KEY`:

- **In IntelliJ:** **Run → Edit Configurations…**, `ImporterApplication`, **Environment variables**: `IMPORTER_ADMIN_PASSWORD=exercise-password`. Restart the importer.
- **Terminal alternative:** in the terminal that starts the importer, `export IMPORTER_ADMIN_PASSWORD=exercise-password` in bash, or `$env:IMPORTER_ADMIN_PASSWORD="exercise-password"` in PowerShell, then `./mvnw spring-boot:run`. (Typing it is fine for a throwaway exercise password. For a real one, use guide 01's `read -rsp` / `Read-Host`, so it doesn't land in your shell's history.)

Then:

```bash
curl -i -u admin:dev-only-password http://localhost:8080/teams
curl -i -u admin:exercise-password http://localhost:8080/teams
```

The first is `401` with `WWW-Authenticate: Basic realm="Realm", charset="UTF-8"`; the second is `200`. The `${IMPORTER_ADMIN_PASSWORD:dev-only-password}` in `application.properties` took the variable, and the default was never used.

**The tests:** `SecurityTest.theAdminGetsIn` sends `dev-only-password`, so it **fails** in that terminal:

```
[HTTP status code] 
expected: 200
 but was: 401
```

The tests read the same `application.properties`, and so the same environment variable. Everything else would pass (`@WithMockUser` doesn't check passwords, and the other `SecurityTest` tests expect a `401` anyway). If you set the variable for the whole machine, rather than one terminal or one run configuration, remember that the tests see it too.

Take the variable out of the run configuration when you're done, so the rest of the series' commands work as written.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 48, Failures: 0, Errors: 0, Skipped: 0
```

(39 from guide 04, minus the 2 removed from `TeamApiTest`, plus 7 in `ImportApiTest` and 4 in `SecurityTest`. With the tests from exercises 2 and 3, 51.)

Against a fresh database, `POST /imports/teams/900001` answers `202` with `Location: /imports/1`, and a moment later:

```
{"id":1,"teamApiId":900001,"status":"COMPLETE","squadSize":6,"profilesFetched":6,"skipped":0,"skippedReasons":[],"quotaRemaining":null,"error":null,"createdAt":"…","finishedAt":"…"}
```

and the log shows the import on a `task-` thread. Without `-u admin:dev-only-password`, every endpoint answers `401`.

New files in the importer:

```
src/main/java/com/example/importer/
├── imports/
│   ├── AsyncConfig.java
│   ├── Import.java
│   ├── ImportAlreadyRunningException.java
│   ├── ImportController.java
│   ├── ImportRepository.java
│   ├── ImportRunner.java
│   ├── ImportService.java
│   ├── ImportStatus.java
│   └── ImportView.java
├── security/
│   └── SecurityConfig.java
└── team/
    └── ImportProgress.java
src/main/resources/
└── db/migration/
    └── V3__create_team_import.sql
src/test/java/com/example/importer/
├── SynchronousAsync.java
├── imports/
│   └── ImportApiTest.java
└── security/
    └── SecurityTest.java
```

Modified:

```
pom.xml                                  (spring-boot-starter-security, spring-boot-starter-security-test)
src/main/resources/application.properties (the admin user)
src/main/java/com/example/importer/
├── team/TeamController.java             (the POST moved to ImportController)
├── team/TeamImporter.java               (ImportProgress)
└── web/ApiExceptionHandler.java         (409)
src/test/java/com/example/importer/
├── ImporterIntegrationTest.java         (@Import(SynchronousAsync.class))
├── provider/ProviderLookupControllerTest.java (@WithMockUser)
└── team/TeamApiTest.java                (@WithMockUser, 202, two tests removed)
```

The fake provider doesn't change: no new files in `mappings/`, `__files/`, `misbehaving/` or `changes/`.

---

## Common errors

**`HTTP/1.1 401` with `WWW-Authenticate: Basic realm="Realm", charset="UTF-8"` and an empty body**
You left out the credentials. Every endpoint needs them now, including `GET /teams` and `GET /provider/teams/{id}`: add `-u admin:dev-only-password` to the curl command. If you did send them, check the password, and whether `IMPORTER_ADMIN_PASSWORD` is set where the importer runs (exercise 5).

**`[HTTP status code] expected: 202 but was: 401` (or `404`, or `200`) in tests that passed before**
A test class that calls the endpoints is missing `@WithMockUser(roles = "ADMIN")`, so its requests arrive with no user and security turns them away. Add it to the class, as in step 13. Only `SecurityTest` should be without it.

**`expected: "Basic realm="Realm""  but was: "Basic realm="Realm", charset="UTF-8""`**
A test, perhaps copied from an older example, expects the challenge header without the charset. Spring Security 7 adds `charset="UTF-8"`; expect the full value, as `withoutCredentialsItAsksForThem` does.

**`Ambiguous mapping. Cannot map 'teamController' method`**
The importer won't start (and every Spring test fails to load its context). In full:

```
Caused by: java.lang.IllegalStateException: Ambiguous mapping. Cannot map 'teamController' method 
com.example.importer.team.TeamController#importTeam(long)
to {POST [/imports/teams/{apiId}]}: There is already 'importController' bean method
com.example.importer.imports.ImportController#importTeam(long) mapped.
```

Guide 04's `POST` is still in `TeamController`. Delete it, as step 7 says: one URL, one handler.

**PowerShell: WireMock answers `Error parsing JSON` to the `fixedDelay` command**
Windows PowerShell strips the double quotes out of `'{"fixedDelay": 1500}'` before `curl.exe` sees them, so WireMock receives something that isn't JSON. Escape each one with a backslash: `curl.exe -X POST http://localhost:8089/__admin/settings -d '{\"fixedDelay\": 1500}'`.

**A team answers `409` and nothing seems to be importing it**
Look up the import the `detail` names (`GET /imports/{id}`). If it really is still running, wait for it: with a `fixedDelay` left on, the fake provider makes every import slow. Switch the delay off (`{"fixedDelay": 0}`). If the importer stopped while that import was running, restarting it marks the import `FAILED` (step 10) and the team can be imported again.

---

## Recap

- An import can take **minutes** (and, from guide 06, **days**), far too long for an HTTP request to wait: callers time out, retry, and start it twice.
- **`POST /imports/teams/{apiId}`** now answers **`202 Accepted`** at once, with **`Location: /imports/{id}`**. **`GET /imports/{id}`** is the status: `RUNNING`, `COMPLETE` or `FAILED` (and, from guide 06, `INCOMPLETE_DAILY_QUOTA`), the counts, the skipped reasons and the error.
- **`202` means accepted, not succeeded.** A team the provider doesn't have, or a broken provider, is no longer a `404` or `502` on the `POST`: the import ends **`FAILED`** with the reason in `error`, the one place to look whichever request failed. The `POST` still refuses what it can judge at once: **`409`** and **`401`**.
- Each import is a **row** in `team_import`, with progress written **as it happens** through **`ImportProgress`**, each count in its own short transaction.
- **`@Async`** (switched on by **`@EnableAsync`**) runs `ImportRunner.run` on a thread from **Boot's task executor** (`task-1`, `task-2`…). It works only **through the proxy**, so `ImportRunner` is its own bean. It catches **every** exception, because nobody is waiting for it.
- The `RUNNING` row is **committed before** the background work starts.
- **`409 Conflict`** for a team that's already `RUNNING`. The check-then-insert has a tiny race, accepted for now; guide 09 explains how to close it properly.
- At start-up, **`failInterruptedImports`** marks leftover `RUNNING` imports **`FAILED`**. Without it, a crash would block that team with `409` for ever. A hard kill can also make H2's ids **jump by 32**: harmless.
- **HTTP Basic** on **every** endpoint, **one admin** from `spring.security.user.*`, `STATELESS`, no CSRF (no cookies). **`dev-only-password` is development only**; a real deployment sets **`IMPORTER_ADMIN_PASSWORD`** from a secret store, over **HTTPS**.
- Tests use a **`SyncTaskExecutor`**, so imports finish before the `POST` returns: no sleeps. They prove what an import does, not that it runs on another thread; the log's thread names prove that.

### Interview angle

<details>
<summary>"An operation takes several minutes. How do you expose it over HTTP?"</summary>

Don't hold the request open. Accept the request, record the job (a database row, so it survives restarts), start the work in the background, and answer `202 Accepted` with a `Location` header pointing at a status resource. The client polls that resource, which reports the job's state, progress and, at the end, the outcome or the error. Errors that can only be discovered during the work belong in the job's status, not in the response to the `POST`; the `POST` only rejects what it can judge immediately, such as bad input, a missing permission or a conflicting job. Alternatives to polling include a callback (webhook) or server-sent events, but polling a status URL is the simplest and works for any client.

</details>

<details>
<summary>"Your `@Async` method doesn't seem to run asynchronously. Why might that be?"</summary>

The usual causes: `@EnableAsync` is missing, so the annotation is ignored; the method is called from inside the same class, so the call never goes through the Spring proxy that hands it to an executor (the same self-invocation trap as `@Transactional`); or the object wasn't created by Spring. It's easy to miss because nothing fails: the method just runs on the caller's thread. Checking the thread name in a log line is the quickest proof. In tests, it's common to swap in a synchronous executor on purpose, which also means tests can't detect any of these.

</details>

<details>
<summary>"What happens to an exception thrown from an `@Async` method?"</summary>

If the method returns a `Future` or `CompletableFuture`, the exception is stored in it, for the caller to find when they ask for the result. If it returns `void`, there's no caller to give it to: Spring passes it to an `AsyncUncaughtExceptionHandler`, which by default just logs it. So a `void` background job should catch its own exceptions and record the outcome somewhere someone will look, such as the job's status row, or the failure is effectively lost.

</details>

<details>
<summary>"How do you stop the same job running twice at once? What are the weaknesses of a simple check?"</summary>

Before starting, look for a job of the same kind that's still running, and refuse (`409 Conflict`) if there is one. The weakness is the gap between the check and the insert: two requests at the same moment can both pass the check. Closing it needs the database to enforce it, for example a unique constraint that only applies to running jobs, a row lock, or a single worker that is the only thing allowed to start jobs. The other weakness is crashes: a job marked "running" when the process died will block for ever unless something marks it failed, for example at start-up, or by expiring jobs that haven't reported progress for a while.

</details>

<details>
<summary>"Is HTTP Basic secure?"</summary>

It's as secure as the channel and the password. The credentials are only Base64-encoded, so anyone who can read the traffic can read the password: it must only be used over HTTPS. It sends the password on every request, so there's nothing to expire or revoke short of changing the password, and it has no built-in protection against guessing, so rate limiting or lockouts matter. For one administrator calling an internal tool over HTTPS, with a strong password kept in a secret store, it's a reasonable choice. For many users, or for services calling each other with different permissions, token-based schemes such as OAuth 2.0 are a better fit. Because it uses a header rather than a cookie, a Basic-only API with no sessions doesn't need CSRF protection.

</details>

### Further reading

- [RFC 9110: 202 Accepted](https://www.rfc-editor.org/rfc/rfc9110#section-15.3.3) and [409 Conflict](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.10)
- [RFC 7617: the 'Basic' HTTP authentication scheme](https://www.rfc-editor.org/rfc/rfc7617), including the `charset` parameter
- [Spring Framework reference: task execution and scheduling](https://docs.spring.io/spring-framework/reference/integration/scheduling.html), for `@Async`, `@EnableAsync` and executors
- [Spring Boot reference: task execution and scheduling](https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html), for the auto-configured executor and its `spring.task.execution.*` properties
- [Spring Security reference: Basic authentication](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/basic.html)
- [Spring Boot reference: Spring Security](https://docs.spring.io/spring-boot/reference/web/spring-security.html), for the default user and `spring.security.user.*`
- [Todo app 07: Testing, unit and web](../todo-app/07-testing-unit-and-web.md), for `@WithMockUser`

**Simplification to notice:** every `POST` starts its import at once, on its own thread, and as many teams as you like can import side by side. Against the real API, they'd all be spending the same 10 requests a minute and 100 a day, and none of them is yet paying attention to what's left.

**Next:** [06: Rate limits and quotas](06-rate-limits-and-quotas.md). Reading the provider's rate-limit headers after every response, pausing when the minute's allowance is used up, stopping cleanly when the day's is, and a `429` safety net.
