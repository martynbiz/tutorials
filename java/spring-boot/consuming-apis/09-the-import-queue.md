# 09: The import queue

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](README.md) for version notes.

## What you'll learn

- Why guide 05's **`@Async`**, one background thread per `POST`, is the wrong shape for an importer that shares one quota, and what replaces it: a **queue of rows** and **one worker**
- How **`POST /imports/teams/{apiId}`** now only **queues** the import (`QUEUED`, still `202 Accepted` and a `Location`), and sends nothing to the provider
- How **`@Scheduled`** and **`@EnableScheduling`** run a method every few seconds on Spring's scheduler thread, and why **`fixedDelay`** means the worker never overlaps itself
- How the worker **claims** the next import in one short transaction: the oldest `QUEUED` one, otherwise the oldest one stopped by the daily quota **before 00:00 UTC today**
- Why that midnight check uses the import's **`finishedAt`** and the **injected `Clock`**, so it still holds after a restart, when guide 06's in-memory tracker has forgotten everything
- How an import interrupted by a restart goes **back in the queue** instead of ending `FAILED`, and how a **`runs`** counter (migration `V4`) records how many times the worker has started it
- Why **`409 Conflict`** now covers an import that's queued, running **or waiting for tomorrow**, and a new **`GET /imports`** to see the queue
- How to test a scheduled worker **without a background thread**: scheduling switched off in tests, tests calling **`runOnce()`**, and **a database of its own for every test context**
- What breaks with **more than one importer**, and the usual fixes (a sidebar, untested)
- A **security checklist** for the whole series: the key, the admin password, untrusted data, timeouts and quota, and **UK GDPR** for the players you store

By the end, you can queue as many teams as you like in one go. The importer works through them one at a time, never sends a request while the day's quota is used up, carries on with any import the quota stopped **by itself after 00:00 UTC**, and picks up an import a restart cut off.

---

## Prerequisites for this guide

You need `importer` and `fake-provider` as they were at the end of [08: When the provider fails](08-when-the-provider-fails.md), with all **76** tests passing. In particular:

- `POST /imports/teams/{apiId}` answers **`202 Accepted`** with `Location: /imports/{id}`, and **`ImportRunner.run`** does the work on a `task-` thread because it's **`@Async`** (`imports/AsyncConfig` has `@EnableAsync`). In the tests, guide 05's **`SynchronousAsync`** makes it run at once, on the test's own thread ([guide 05](05-imports-in-the-background.md)).
- `ImportService.start` refuses a second import of a team that's **`RUNNING`** with a `409`, and `failInterruptedImports` marks imports left `RUNNING` by a restart as **`FAILED`** at start-up.
- An import that runs out of the day's quota ends **`INCOMPLETE_DAILY_QUOTA`**, and the **`QuotaTracker`** sends nothing more until 00:00 UTC. Importing the team again carries on, fetching only what's missing; for now, **you** have to do that, after midnight ([guide 06](06-rate-limits-and-quotas.md)).
- **Injected time:** a `Clock` bean and a `Sleeper` bean, replaced in every Spring test by `TestTime`'s `TestClock` and `RecordingSleeper` (guide 06).
- Guide 08's **`ProviderRetry`** retries what might work next time, **`ProviderUnavailableException`** reports what didn't, and **`ProviderLoggingInterceptor`** logs every request with the key as `****`. Guide 07's **`Validation`** skips players that break the rules, and fails an import whose team does.
- All the Spring tests share **one** in-memory database, `importer-test`, and each test class uses its own team ids so they don't trip over each other ([guide 03](03-storing-teams.md), step 10). This guide changes that.
- Every endpoint needs `-u admin:dev-only-password`.

No new dependencies, and no API-Football account is needed: everything happens against the fake provider.

---

## Concepts

### What's wrong with a thread per `POST`

Guide 05 made the `POST` answer at once by handing each import to its own background thread. It works, and the `202`, the `Location` and the status endpoint all stay. But look at what the importer has become since:

- **One quota, many imports.** Ten `POST`s start ten imports at once, all spending the same 10 requests a minute and 100 a day. Guide 06's `QuotaTracker` keeps them inside the limits (its methods are `synchronized`, so while one import waits for the minute, they all do), but they're fighting over the same requests: ten imports, all half-finished, instead of five finished and five not started. When the day runs out, it runs out for all ten at once.
- **Somebody has to come back tomorrow.** An import stopped by the daily quota ends `INCOMPLETE_DAILY_QUOTA`, and stays that way until you `POST` the team again after 00:00 UTC. For a league of 20 clubs, at around 27 requests each, that's a week of remembering to type curl commands just after midnight.
- **The work lives in a thread.** A thread doesn't survive a restart. Guide 05's answer was to mark whatever was left `RUNNING` as `FAILED` at start-up, and let you start it again by hand.

All three have the same fix. The import rows from guide 05 already **are** a list of work, stored in the database. Make them a **queue**: the `POST` adds a row and does nothing else, and **one worker** takes rows off the queue, one at a time, and runs them.

```
POST /imports/teams/900001 ──▶ 202, import 1 QUEUED ─┐
POST /imports/teams/900002 ──▶ 202, import 2 QUEUED ─┤      team_import
POST /imports/teams/900100 ──▶ 202, import 3 QUEUED ─┘   (the queue: rows)
                                                              │
                     every few seconds, on its own thread:    ▼
                     ImportWorker: the oldest import that's ready ──▶ RUNNING ──▶ COMPLETE
                                                                                 FAILED
                                                                                 INCOMPLETE_DAILY_QUOTA
                                                                                  (ready again after 00:00 UTC)
```

The `POST` still answers `202 Accepted` and a `Location`, and `202` still means "accepted, not done" (guide 05's Concepts). Only two things a caller can see change: the body says **`QUEUED`** instead of `RUNNING`, and it has a new field, **`runs`**.

### One worker, on a schedule

Spring can call a method of a bean **on a schedule**, with no thread of your own: put **`@Scheduled`** on it, and **`@EnableScheduling`** on a configuration class (without it, `@Scheduled` is silently ignored, like `@Async` without `@EnableAsync`). The method runs on a thread of **Spring's task scheduler**, which Spring Boot sets up for you, with threads named `scheduling-1` and so on. Out of the box it has **one** thread.

`@Scheduled` has several ways of saying when:

| Attribute | Means | Can two runs overlap? |
| --- | --- | --- |
| `fixedRate = …` | Start every N, measured from the **start** of the last run | Not on Boot's one-thread scheduler, but a slow run makes the next one start late and the ones after it bunch up |
| **`fixedDelay = …`** | Start N after the last run **finished** | **No**: the next run isn't even planned until this one ends |
| `cron = "…"` | At set times of day, like Unix `cron` | Like `fixedRate` |

The worker uses **`fixedDelay`**. An import can take minutes (guide 06's 60-second pauses), and with `fixedDelay` a check that runs for five minutes is simply followed, ten seconds after it ends, by the next check. There's never a second worker run alongside the first, so **imports never overlap**. That's the point: the quota belongs to the key, so imports should take turns, not share.

Each check does everything that's ready, one import after another, and then stops until the next check. With nothing in the queue, a check costs one small database query every ten seconds, and **no requests to the provider at all**.

> **Sidebar: why not keep `@Async` and add a queue?** You could keep a thread per `POST` and make each one wait for its turn. But then the waiting is in memory, and a restart loses it again; and something still has to start the imports stopped by the quota after midnight. A scheduled worker that reads its work from the database does both jobs with one mechanism. Spring Boot's task executor, the one `@Async` used, goes back to doing nothing: nothing in the importer uses it any more.

### Claiming the next import

When the worker checks the queue, it asks the database, in **one short transaction**, for the next import that's ready, and marks it `RUNNING` before the transaction ends. "Ready" means one of two things, in this order:

1. **The oldest `QUEUED` import.** First come, first served: a lower id was queued earlier.
2. Otherwise, **the oldest `INCOMPLETE_DAILY_QUOTA` import that finished before 00:00 UTC today.** The quota stopped it on an earlier day, and API-Football's documentation says the quota resets at 00:00 UTC (guide 06), so it can carry on now.

Then the transaction commits and, outside it, the worker runs the import exactly as `ImportRunner` always has: guide 03's rule, **no transaction around HTTP calls**, still holds.

Re-running an import isn't a special case. Guide 04 made a second import of a team cheap: it saves the team again, reads the squad again, and fetches a profile **only for players we don't have yet**. So "carry on" just means "run the same import again", and the players fetched yesterday cost nothing today.

### Nothing is sent while the day is used up

Before it claims anything, the worker asks guide 06's `QuotaTracker` whether the day's quota is used up. If it is, it does **nothing**, not even "try one and see". The tracker would refuse to send the request anyway, so all a run could do is end another import `INCOMPLETE_DAILY_QUOTA` straight away, and the queue would be emptied of everything waiting in it. So queued imports **stay queued** until the tracker's day has moved on, and then the worker starts them, in order.

### Midnight, from the row, not from memory

The tracker lives in memory. Restart the importer and it forgets that the day was used up: guide 06 relied on exactly that to stand in for "the next day". A worker that went by the tracker alone would, after any restart, pick up every import the quota had stopped, send a request for each, and get "no more today" back for each one: requests thrown away, and on the real API, perhaps more harm than that.

So the second rule above doesn't ask the tracker. It asks **the row**: when did this import finish? An import that stopped at 15:01 today isn't ready until 00:00 UTC tomorrow, **whatever the importer has forgotten since**. Like guide 06's tracker, "today" is the UTC date of **`clock.instant()`**, the injected `Clock`, never `Instant.now()`. That's what lets a test set the time to 23:59, check nothing happens, move it to 00:00:30, and check the import carries on, in milliseconds ([guide 06](06-rate-limits-and-quotas.md#time-is-a-dependency) explained why time is a dependency). For the same reason, `ImportService` now takes the `createdAt` and `finishedAt` it writes from the `Clock` too.

### `runs`: one row, several passes

An import that stops for the quota and carries on tomorrow is still **one** import: one row, one id, one `Location` to follow. Each time the worker starts it, the row's new **`runs`** count goes up by one, and the fields that describe **this pass** over the squad start again: `squadSize`, `skipped`, `skippedReasons`, `error` and `finishedAt`. **`profilesFetched` keeps counting**, because every profile fetched cost a request, whichever day it was on. So an import that fetched one profile yesterday and the one still missing today says `"runs":2,"profilesFetched":2`.

### A restart puts the import back in the queue

Guide 05 marked an import left `RUNNING` by a restart as `FAILED`, because nothing would ever finish it. Now something will: put it back to **`QUEUED`**, and the worker picks it up again within seconds of starting, as its next `run`. Nothing is lost that was stored (guide 04 links players as it goes), and what's missing is fetched. At start-up nothing can be running yet, so every `RUNNING` row really is left over from before: the same reasoning as guide 05.

### `409`: one unfinished import per team

Guide 05 refused a second import of a team that was `RUNNING`. Now an import can also be **waiting**, `QUEUED` or `INCOMPLETE_DAILY_QUOTA`, and it will run by itself. A second one would do the same work again, so the `409` covers all three. The message now says which state the import is in:

```json
{"detail":"Team 900003 already has an unfinished import (import 1, INCOMPLETE_DAILY_QUOTA)","instance":"/imports/teams/900003","status":409,"title":"Conflict"}
```

`COMPLETE` and `FAILED` imports are finished, and block nothing: queue the team again whenever you like. A `FAILED` import is never retried by the worker. Guide 08 already tried what was worth trying, and a failure that's left needs a person to look at it.

**What happened to guide 05's "known gap"?** Guide 05 pointed out that its `409` check is "look, then insert", so two `POST`s in the same instant could both pass it. That's still true of the check, but what it costs has changed. Only the worker starts imports, one at a time, so two rows for one team can no longer **run at the same time**: they'd run one after the other, and the second would find the profiles already stored (2 requests, plus any player still missing). For one admin with curl, that's the whole fix. With **several** importers sharing a database, the worker itself is the problem, and the `409` check needs the database's help: that's the sidebar at the end of the steps.

### Tests without a background thread

A worker that wakes up every ten seconds on its own thread is the last thing a test wants: it could pick up an import halfway through a test's setup, or run one the test meant to check was still queued. So in the tests:

- **Scheduling is switched off.** `@EnableScheduling` goes on a configuration class that only exists when the property `importer.queue.enabled` is `true` (it is, unless something says otherwise). The tests set it to `false`, and nothing runs by itself.
- **Tests run the worker themselves**, by calling its **`runOnce()`**: "run the next ready import, if there is one, and say whether you did". A test queues an import, checks it's `QUEUED`, calls `runOnce()`, and checks it's `COMPLETE`, all on the test's own thread. That replaces guide 05's `SynchronousAsync`, and like it, it means no sleeping and no polling.
- **Every test context gets a database of its own.** Since guide 03, all the test classes have shared one in-memory database, each with its own team ids (guide 03, step 10). That was fine while nothing read **other** rows. The worker does: it takes "the oldest queued import", and in a shared database, that could be one another class left behind, with no WireMock stub to answer it. So the test database's name now ends in a random UUID, and each Spring context gets a new, empty one. The team ids stay as they are: still distinct, now just a habit.

Even within one class, tests share a context and so a database. A test that leaves an import queued would have it picked up by the next test's `runOnce()`. So the helpers in each class **drain the queue** (`while (worker.runOnce())`). `ImportQueueTest`, whose tests move the clock about, goes further: it **deletes every import** before each test, so each one starts with an empty table, whatever ran before it.

> **Done the REST API series?** Nothing there was scheduled, but its [guide 07](../rest-api/07-concurrency-and-retries.md) is the background for this guide's sidebar on running more than one importer: what "two requests at the same moment" does to a check-then-write, and the pessimistic and optimistic locks that stop it.

---

## Steps

Everything new lives in the `imports` package, next to guide 05's classes.

### 1. The `runs` column: `V4__add_import_runs.sql`

Create `src/main/resources/db/migration/V4__add_import_runs.sql`:

```sql
-- guide 09: how many times the worker has started this import (it resumes after the daily quota)
alter table team_import add column runs integer not null default 0;
```

`V4`, after guide 05's `V3`. Never edit `V3` instead: on a database where `V3` has already run (yours, since guide 05), Flyway would refuse to start, because the file no longer matches what it ran. **`default 0`** gives the rows already in your database a value, so `not null` holds for them too.

### 2. A status for "waiting its turn"

`imports/ImportStatus.java` gets **`QUEUED`**, and a set of the statuses that count as **unfinished**:

```java
package com.example.importer.imports;

import java.util.EnumSet;
import java.util.Set;

public enum ImportStatus {
    QUEUED, RUNNING, COMPLETE, INCOMPLETE_DAILY_QUOTA, FAILED;

    /** Not finished: waiting, running, or stopped by the daily quota (it carries on after midnight UTC). */
    static final Set<ImportStatus> UNFINISHED = EnumSet.of(QUEUED, RUNNING, INCOMPLETE_DAILY_QUOTA);
}
```

- **`EnumSet.of(...)`** is a `Set` made for enum values: compact and fast, and it keeps them in the enum's order.
- **`UNFINISHED`** is package-private (no `public`): only the `imports` package needs it, for step 5's `409` check.
- The column is `varchar(30)` with the name stored as text (`@Enumerated(EnumType.STRING)`, guide 05), so a new value needs no migration.

### 3. The `Import` entity: queued, started, queued again

In `imports/Import.java`, add the field, after `finishedAt`:

```java
    private int runs;
```

A new import now starts **`QUEUED`**:

```java
    public Import(long teamApiId, Instant createdAt) {
        this.teamApiId = teamApiId;
        this.status = ImportStatus.QUEUED;
        this.createdAt = createdAt;
    }
```

Add two methods, after the constructor:

```java
    /**
     * The worker picked it up (again). The squad is gone through from the start each time, so the squad size and
     * skipped players describe this run; profilesFetched keeps counting, because every fetch cost a request.
     */
    void start() {
        this.status = ImportStatus.RUNNING;
        this.runs++;
        this.squadSize = null;
        this.skipped = 0;
        this.skippedReasons.clear();
        this.error = null;
        this.finishedAt = null;
    }

    /** Cut off by a restart: put it back in the queue. */
    void requeue() {
        this.status = ImportStatus.QUEUED;
    }
```

and a getter, at the end:

```java
    public int getRuns() {
        return runs;
    }
```

- **`start()`** is "the worker picked it up", the Concepts' `runs` section in code. The fields it clears are filled in again as this run goes; `profilesFetched` isn't touched.
- **`skippedReasons.clear()`** empties the `@ElementCollection`, and Hibernate deletes the rows in `team_import_skipped` when the transaction commits. A player skipped yesterday is tried again today (guide 07: a skipped player costs a request every time), and if they're still skipped, the reason comes back.
- Both are **package-private**, like the other changing methods since guide 05: only the `imports` package moves an import along.

In `imports/ImportView.java`, the JSON gains `runs`, at the end:

```java
public record ImportView(Long id, long teamApiId, ImportStatus status, Integer squadSize, int profilesFetched,
        int skipped, List<String> skippedReasons, Integer quotaRemaining, String error, Instant createdAt,
        Instant finishedAt, int runs) {

    static ImportView from(Import i) {
        return new ImportView(i.getId(), i.getTeamApiId(), i.getStatus(), i.getSquadSize(), i.getProfilesFetched(),
                i.getSkipped(), List.copyOf(i.getSkippedReasons()), i.getQuotaRemaining(), i.getError(),
                i.getCreatedAt(), i.getFinishedAt(), i.getRuns());
    }
}
```

### 4. The queries: `ImportRepository`

```java
package com.example.importer.imports;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportRepository extends JpaRepository<Import, Long> {

    Optional<Import> findFirstByTeamApiIdAndStatusIn(long teamApiId, Collection<ImportStatus> statuses);

    List<Import> findByStatus(ImportStatus status);

    /** The oldest import with this status (ids go up as imports are created). */
    Optional<Import> findFirstByStatusOrderByIdAsc(ImportStatus status);

    /** The oldest import with this status that finished before the given moment. */
    Optional<Import> findFirstByStatusAndFinishedAtBeforeOrderByIdAsc(ImportStatus status, Instant before);

    List<Import> findTop50ByOrderByIdDesc();
}
```

All derived queries, as in guides 03 to 05: Spring Data reads the method name and writes the query. The new words:

- **`…StatusIn(Collection<ImportStatus> statuses)`**: `status in (…)`. It replaces guide 05's `findFirstByTeamApiIdAndStatus`, for the `409` check over all three unfinished statuses.
- **`OrderByIdAsc`** with **`findFirst`**: sort by id, smallest first, and take one. "The oldest", because ids go up as rows are created.
- **`FinishedAtBefore(Instant before)`**: `finished_at < ?`, for "stopped before 00:00 UTC today".
- **`findTop50ByOrderByIdDesc()`**: the 50 newest, for `GET /imports`. `Top50` and `First50` mean the same.

`findByStatus` stays: start-up still looks for `RUNNING` rows.

### 5. `ImportService`: queue, claim, requeue

Make the `409`'s message say which state the unfinished import is in. `imports/ImportAlreadyRunningException.java`:

```java
package com.example.importer.imports;

/** The team already has an import that isn't finished: waiting, running, or waiting for tomorrow's quota. */
public class ImportAlreadyRunningException extends RuntimeException {

    private final long importId;

    public ImportAlreadyRunningException(long teamApiId, long importId, ImportStatus status) {
        super("Team " + teamApiId + " already has an unfinished import (import " + importId + ", " + status + ")");
        this.importId = importId;
    }

    public long importId() {
        return importId;
    }
}
```

(The class keeps its name, so guide 05's handler in `ApiExceptionHandler` keeps working unchanged. "Unfinished" would be a better name now; renaming it is a refactoring you can do with IntelliJ's **Refactor → Rename** whenever you like.)

Then `imports/ImportService.java`. Here it is in full, because most of it changes:

```java
package com.example.importer.imports;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

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
    private final Clock clock;

    public ImportService(ImportRepository imports, Clock clock) {
        this.imports = imports;
        this.clock = clock;
    }

    /** A new QUEUED import, unless this team already has one that isn't finished. */
    @Transactional
    public ImportView queue(long teamApiId) {
        imports.findFirstByTeamApiIdAndStatusIn(teamApiId, ImportStatus.UNFINISHED).ifPresent(unfinished -> {
            throw new ImportAlreadyRunningException(teamApiId, unfinished.getId(), unfinished.getStatus());
        });
        return ImportView.from(imports.save(new Import(teamApiId, clock.instant())));
    }

    /**
     * The next import to run, now marked RUNNING: the oldest QUEUED one, otherwise the oldest one stopped by the
     * daily quota on an earlier (UTC) day — the quota has been reset since.
     */
    @Transactional
    public Optional<ImportView> claimNext() {
        Instant startOfTodayUtc = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        Optional<Import> next = imports.findFirstByStatusOrderByIdAsc(ImportStatus.QUEUED)
                .or(() -> imports.findFirstByStatusAndFinishedAtBeforeOrderByIdAsc(
                        ImportStatus.INCOMPLETE_DAILY_QUOTA, startOfTodayUtc));
        next.ifPresent(Import::start);
        return next.map(ImportView::from);
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
    public void finish(long importId, ImportStatus status, String error, Integer quotaRemaining) {
        get(importId).finish(status, error, quotaRemaining, clock.instant());
    }

    @Transactional(readOnly = true)
    public ImportView view(long importId) {
        return ImportView.from(get(importId));
    }

    @Transactional(readOnly = true)
    public List<ImportView> latest() {
        return imports.findTop50ByOrderByIdDesc().stream().map(ImportView::from).toList();
    }

    /**
     * An import still RUNNING when the app starts was cut off when the app last stopped. It goes back in the
     * queue: running it again fetches only what's still missing.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void requeueInterruptedImports() {
        for (Import running : imports.findByStatus(ImportStatus.RUNNING)) {
            log.warn("Import {} (team {}) was interrupted by a restart: queued again", running.getId(),
                    running.getTeamApiId());
            running.requeue();
        }
    }

    private Import get(long importId) {
        return imports.findById(importId).orElseThrow(() -> new NotFoundException("Import", importId));
    }
}
```

What changed, from the top:

- **The `Clock`** comes in through the constructor: guide 06's bean (`Clock.systemUTC()`), or `TestClock` in the tests. Every `Instant.now()` in this class is now **`clock.instant()`**: in `queue`, in `finish`, and in the midnight check.
- **`queue`** is guide 05's `start` with a new name, because that's what it does now: the `409` check over **`UNFINISHED`**, then a new row, which the constructor makes `QUEUED`.
- **`claimNext`** is the Concepts' "claiming the next import":
  - **`startOfTodayUtc`**: today's UTC date (`LocalDate.ofInstant(…, ZoneOffset.UTC)`), turned back into the instant it starts, 00:00 UTC.
  - **`.or(() -> …)`** on an `Optional`: if the first query found nothing, ask the second. The second query only runs when it's needed. So `QUEUED` comes first, then an import the quota stopped before today.
  - **`next.ifPresent(Import::start)`** marks it `RUNNING`, adds one to `runs`, and clears the per-run fields (step 3). There's no `save`: the import was loaded in this transaction, so the change is written when it commits (dirty checking, guide 04).
  - It returns a **view**, not the entity, built inside the transaction, as `view()` does.
- **`latest`** is for `GET /imports`: the newest 50, as views.
- **`requeueInterruptedImports`** replaces guide 05's `failInterruptedImports`: same start-up event, same search for `RUNNING` rows, but `requeue()` instead of `finish(FAILED, …)`, and a log line that says so.

### 6. `ImportRunner` is an ordinary method again

The runner stays exactly as guide 06 left it, apart from two things: **remove `@Async`** from `run` (and its import, `org.springframework.scheduling.annotation.Async`), and update the comments:

```java
/** Runs one import (for the worker), recording progress and the outcome on the Import row. */
@Service
public class ImportRunner {
```

```java
    /** Nobody is waiting for the answer, so every outcome is recorded on the row. */
    public void run(long importId, long teamApiId) {
```

The `try`/`catch` stays: the worker isn't waiting for an answer any more than guide 05's caller was, and an exception that escaped `run` would leave the row `RUNNING` and stop the worker's check. `ImportRunner` also stays a bean of its own, though it no longer needs a proxy to work: the worker calls it, and the tests call the worker.

**Delete `imports/AsyncConfig.java`.** Without `@Async` anywhere, `@EnableAsync` has nothing to switch on.

### 7. The worker: `ImportWorker`

Create `imports/ImportWorker.java`:

```java
package com.example.importer.imports;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.importer.provider.QuotaTracker;

/**
 * Works through the import queue, one import at a time, on its own thread.
 * Imports stopped by the daily quota are picked up again by themselves after 00:00 UTC.
 */
@Component
public class ImportWorker {

    private static final Logger log = LoggerFactory.getLogger(ImportWorker.class);

    private final ImportService importService;
    private final ImportRunner runner;
    private final QuotaTracker quotaTracker;

    public ImportWorker(ImportService importService, ImportRunner runner, QuotaTracker quotaTracker) {
        this.importService = importService;
        this.runner = runner;
        this.quotaTracker = quotaTracker;
    }

    /** Every so often (after the previous check has finished): run everything that's ready. */
    @Scheduled(fixedDelayString = "${importer.queue.poll-interval}")
    public void checkTheQueue() {
        while (runOnce()) {
            // keep going until there's nothing left that can run now
        }
    }

    /** Runs the next import, if one is ready and the provider will take requests. Returns whether it ran one. */
    public boolean runOnce() {
        if (quotaTracker.dailyQuotaExhausted()) {
            return false; // nothing can run until 00:00 UTC: don't spend a request finding that out again
        }
        Optional<ImportView> next = importService.claimNext();
        if (next.isEmpty()) {
            return false;
        }
        log.info("Taking import {} (team {}) from the queue, run {}", next.get().id(), next.get().teamApiId(),
                next.get().runs());
        runner.run(next.get().id(), next.get().teamApiId());
        return true;
    }
}
```

- **`@Scheduled(fixedDelayString = "${importer.queue.poll-interval}")`**: call `checkTheQueue` again a poll interval after the last call **finished**. `fixedDelay` takes a number of milliseconds; **`fixedDelayString`** takes text, which may be a `${…}` placeholder for a property, and Spring reads `10s` in it the same way Spring Boot reads guide 06's `Duration` properties (`10s`, `2s`, `1m`, or a bare number of milliseconds). Step 8 sets the property.
- **`checkTheQueue`** runs imports until **`runOnce`** says there's nothing more it can do, and returns. The next check is a poll interval later.
- **`runOnce`** is the worker's one decision:
  1. **The day is used up?** Do nothing. `dailyQuotaExhausted()` is guide 06's: true until the tracker's UTC date moves on.
  2. **Claim** the next ready import (step 5). Nothing ready? Do nothing.
  3. **Run it**, on this thread, to the end: `COMPLETE`, `FAILED` or `INCOMPLETE_DAILY_QUOTA`. When an import stops for the quota, the next `runOnce` stops at rule 1, so a queue of ten waits quietly behind the first one.
- **`runOnce` is `public`** because the tests call it (step 12). It's also the whole worker: `checkTheQueue` only adds the schedule and the loop.
- The **log line** says which run this is: `run 1`, or `run 2` for an import carrying on.

### 8. Switch scheduling on (except in tests)

Create `imports/SchedulingConfig.java`:

```java
package com.example.importer.imports;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Switches @Scheduled on, unless importer.queue.enabled=false (the tests run the worker themselves). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "importer.queue.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
```

- **`@EnableScheduling`** makes Spring look for `@Scheduled` methods on every bean and run them. Like guide 05's `AsyncConfig`, the class is empty: the annotations are the point.
- **`@ConditionalOnProperty(...)`**: this configuration class only exists when **`importer.queue.enabled`** is `true`. **`matchIfMissing = true`**: and when the property isn't set at all, so deleting the line from `application.properties` doesn't quietly switch the queue off. With the property `false`, there's no `@EnableScheduling`, so `@Scheduled` is ignored: `ImportWorker` is still a bean, and `runOnce()` still works when something calls it. It just never runs by itself.

At the end of `src/main/resources/application.properties`:

```properties
# The import queue: the worker checks for work this often (after finishing the previous check)
importer.queue.enabled=true
importer.queue.poll-interval=10s
```

Ten seconds is often enough that a queued import starts promptly, and costs nothing: an empty check is one quick query, and no request to the provider. The real API's limits are per minute and per day; nothing gains from checking more often.

### 9. The endpoints: queue, and see the queue

`imports/ImportController.java` no longer needs the runner. In full:

```java
package com.example.importer.imports;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    /** 202 Accepted: it's in the queue. The Location header says where to check on it. */
    @PostMapping("/imports/teams/{apiId}")
    public ResponseEntity<ImportView> importTeam(@PathVariable long apiId) {
        ImportView queued = importService.queue(apiId);
        return ResponseEntity.accepted().location(URI.create("/imports/" + queued.id())).body(queued);
    }

    @GetMapping("/imports/{id}")
    public ImportView status(@PathVariable long id) {
        return importService.view(id);
    }

    /** The latest 50 imports, newest first: what's queued, running, waiting for tomorrow, done. */
    @GetMapping("/imports")
    public List<ImportView> latest() {
        return importService.latest();
    }
}
```

- **The `POST`** is now one line of work: queue it (or throw for the `409`), and answer `202` with the `Location`, as before. Guide 05's ordering rule ("commit the row before the background work starts") looks after itself: the worker only ever finds rows that are committed.
- **`GET /imports`** lists the newest 50, newest first. Fifty is enough to see a queue and what happened to it lately; a real admin tool would page it (`Pageable`, as the [REST API series](../rest-api/03-collections-and-caching.md) did), but a fixed limit keeps the response small without any new parameters. Like every other endpoint, it needs the admin's password (guide 05's `anyRequest().hasRole("ADMIN")` covers it with no change).

The importer compiles and runs. Before the tests, watch it.

### 10. Watch the queue

Ten seconds between checks is fine for real use, and slow to watch. Start the importer with a **2-second** poll interval:

- **In IntelliJ:** **Run → Edit Configurations…**, select `ImporterApplication`, and put this in **Program arguments** (as in guide 06, step 12):

  ```
  --importer.queue.poll-interval=2s
  ```

  Remove it again when you've finished this guide.
- **Terminal alternative:** build the jar and run it, from `importer/`:

  ```bash
  ./mvnw package
  java -jar target/importer-0.0.1-SNAPSHOT.jar --importer.queue.poll-interval=2s
  ```

  (`.\mvnw package` in PowerShell.) The outputs below came from this `java -jar` form.

Have the fake provider running, and a second terminal open in `fake-provider/` for its switches. Each part below started from a **fresh database** (stop the importer, delete `importer/data/`, start it again), so the import ids start at 1; on yours they may not, and the `Location` header always has the right one. On a fresh database, the log shows the new migration:

```
Migrating schema "PUBLIC" to version "4 - add import runs"
```

(On the database you've been using since guide 05, you'll see only that one migration, and every existing import gets `"runs":0`.)

**Queue three teams, and one twice.** Type these one after the other, quickly:

```bash
curl -i -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900001
```

```
HTTP/1.1 202
Location: /imports/1

{"id":1,"teamApiId":900001,"status":"QUEUED","squadSize":null,"profilesFetched":0,"skipped":0,"skippedReasons":[],"quotaRemaining":null,"error":null,"createdAt":"2026-11-17T13:46:42.917955200Z","finishedAt":null,"runs":0}
```

`QUEUED`, with `"runs":0`: the worker hasn't touched it, and nothing has been sent to the provider.

```bash
curl -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900002
curl -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900100
curl -i -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900001
```

The first two are `202`, with `Location: /imports/2` and `/imports/3`, both `QUEUED`. The third is Riverside Rovers again, while import 1 is still waiting:

```
HTTP/1.1 409

{"detail":"Team 900001 already has an unfinished import (import 1, QUEUED)","instance":"/imports/teams/900001","status":409,"title":"Conflict"}
```

**See the queue.** Straight away:

```bash
curl -u admin:dev-only-password http://localhost:8080/imports
```

```
[{"id":3,…,"status":"QUEUED",…,"runs":0},{"id":2,…,"status":"QUEUED",…,"runs":0},{"id":1,"teamApiId":900001,"status":"RUNNING",…,"runs":1}]
```

(Shortened.) Newest first: Scotland and Harbour Athletic waiting their turn, Riverside Rovers being imported, on its first run. A few seconds later, the same request:

```
[{"id":3,"teamApiId":900100,"status":"COMPLETE","squadSize":5,"profilesFetched":2,…,"quotaRemaining":89,…,"finishedAt":"2026-11-17T13:46:46.303695Z","runs":1},{"id":2,"teamApiId":900002,"status":"COMPLETE","squadSize":5,"profilesFetched":5,"skipped":1,"skippedReasons":["Player 9000014: the provider has no profile"],…,"runs":1},{"id":1,"teamApiId":900001,"status":"COMPLETE","squadSize":6,"profilesFetched":6,…,"finishedAt":"2026-11-17T13:46:45.684056Z","runs":1}]
```

Scotland needed only 2 profiles: the other three players had already arrived with the two clubs, which ran first. In the importer's log (trimmed), one thread did all three, one after the other:

```
13:46:44.412 INFO … [   scheduling-1] c.example.importer.imports.ImportWorker  : Taking import 1 (team 900001) from the queue, run 1
13:46:45.704 INFO … [   scheduling-1] c.example.importer.imports.ImportWorker  : Taking import 2 (team 900002) from the queue, run 1
13:46:46.072 INFO … [   scheduling-1] c.example.importer.imports.ImportWorker  : Taking import 3 (team 900100) from the queue, run 1
13:46:46.307 INFO … ImportRunner : Import 3 of team 900100 complete
```

`scheduling-1` is Spring's scheduler thread; guide 05's `task-1`, `task-2` are gone. Each import starts only after the one before it has finished (import 1's last line, `complete`, is at 13:46:45.688, just before import 2 is taken). All three ran in **one** check: `checkTheQueue` kept calling `runOnce()` until the queue was empty.

**Stop the importer in the middle of an import.** Fresh database again. Make the fake provider slow with guide 08's `slow.json`, and queue Riverside Rovers:

```bash
curl -X POST http://localhost:8089/__admin/mappings/import -d @misbehaving/slow.json
curl -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900001
```

Within 2 seconds, the log says `Taking import 1 (team 900001) from the queue, run 1`, and then nothing for a while: the first request is waiting for its reply. **Kill** the importer now, as in guide 05, step 10 (the process id is the number after the level on every log line):

- **Windows (PowerShell):** `Stop-Process -Id 1716 -Force` (with your own number)
- **macOS or Linux:** `kill -9 1716`

Import 1 is left `RUNNING` in the database. Switch `slow.json` off (`curl -X POST http://localhost:8089/__admin/mappings/reset`), and start the importer again. Just after it has started:

```
WARN … [           main] c.e.importer.imports.ImportService       : Import 1 (team 900001) was interrupted by a restart: queued again
INFO … [   scheduling-1] c.example.importer.imports.ImportWorker  : Taking import 1 (team 900001) from the queue, run 2
```

```bash
curl -u admin:dev-only-password http://localhost:8080/imports/1
```

```
{"id":1,"teamApiId":900001,"status":"COMPLETE","squadSize":6,"profilesFetched":6,"skipped":0,"skippedReasons":[],"quotaRemaining":89,"error":null,"createdAt":"2026-11-17T13:54:55.187788Z","finishedAt":"2026-11-17T13:55:27.544903Z","runs":2}
```

No `FAILED`, and nothing for you to do: the same import, finished on its second run.

**The day runs out.** Fresh database again. Switch on guide 06's `daily-limit-reached.json` (the first reply says nothing is left today), and queue Northgate United, guide 07's team:

```bash
curl -X POST http://localhost:8089/__admin/mappings/import -d @misbehaving/daily-limit-reached.json
curl -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900003
```

A couple of seconds later:

```bash
curl -u admin:dev-only-password http://localhost:8080/imports/1
```

```
{"id":1,"teamApiId":900003,"status":"INCOMPLETE_DAILY_QUOTA","squadSize":null,"profilesFetched":0,"skipped":0,"skippedReasons":[],"quotaRemaining":0,"error":"API-Football's daily request quota is used up (it resets at 00:00 UTC)","createdAt":"2026-11-17T15:01:53.206630Z","finishedAt":"2026-11-17T15:01:55.166462Z","runs":1}
```

Guide 06's clean stop, as before. Now put the provider back to normal, so it **would** answer, and give the worker a few checks:

```bash
curl -X POST http://localhost:8089/__admin/mappings/reset
```

Wait about 7 seconds (three or four checks at 2 seconds), then ask WireMock how many team requests it has had since that reset:

```bash
curl -X POST http://localhost:8089/__admin/requests/count -d '{"method":"GET","urlPath":"/teams"}'
```

```
{ "count" : 0, "requestJournalDisabled" : false }
```

**None.** The worker checked the queue every 2 seconds, found the tracker's day used up, and did nothing. In PowerShell, escape the quotes in that JSON, as guide 06 did:

```powershell
curl.exe -X POST http://localhost:8089/__admin/requests/count -d '{\"method\":\"GET\",\"urlPath\":\"/teams\"}'
```

And Northgate United can't be queued again:

```bash
curl -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900003
```

```
{"detail":"Team 900003 already has an unfinished import (import 1, INCOMPLETE_DAILY_QUOTA)","instance":"/imports/teams/900003","status":409,"title":"Conflict"}
```

It doesn't need to be: import 1 will carry on by itself after 00:00 UTC.

In PowerShell, every command above works with `curl.exe` instead of `curl`; the only JSON typed on the command line is the `requests/count` one.

### 11. After midnight, without waiting for it

The last part of step 10 ends with an import that will carry on "after 00:00 UTC". **That wasn't watched happening while this guide was written:** it would have meant waiting until midnight UTC. It's proved by a test, `anImportStoppedByTheDailyQuotaCarriesOnByItselfAfterMidnightUtc` (step 13), which sets the `TestClock` to 23:59, checks nothing is sent, moves it to 00:00:30 the next day, and checks the import ends `COMPLETE` on its second run. That's what injecting the clock is for.

If you'd like to see it for real, there are two ways.

**Leave it running.** Keep the importer and the fake provider (back to normal) running past 00:00 UTC: midnight in the UK in winter, 1 am in summer. Within one poll interval after midnight, the log should show `Taking import 1 (team 900003) from the queue, run 2`.

**Or move the import's finish time back a day.** A restart alone won't do it any more. Guide 06's trick, restarting the importer so the tracker forgets, no longer starts anything: the worker goes by the row's `finishedAt`, which says "today" whatever the tracker remembers. But you can change the row. H2 comes with a small command-line tool, `org.h2.tools.Shell`, that runs SQL against a database file, and its jar is already in your local Maven repository, because the importer depends on it.

1. **Stop the importer.** H2's file database can only be open in one process at a time (Common errors shows what happens otherwise).
2. From `importer/`, move the stopped import's `finished_at` back one day:

   ```bash
   java -cp ~/.m2/repository/com/h2database/h2/2.4.240/h2-2.4.240.jar org.h2.tools.Shell -url jdbc:h2:file:./data/importer -user sa -sql "update team_import set finished_at = dateadd(day, -1, finished_at) where status = 'INCOMPLETE_DAILY_QUOTA'"
   ```

   In PowerShell:

   ```powershell
   java -cp $env:USERPROFILE\.m2\repository\com\h2database\h2\2.4.240\h2-2.4.240.jar org.h2.tools.Shell -url jdbc:h2:file:./data/importer -user sa -sql "update team_import set finished_at = dateadd(day, -1, finished_at) where status = 'INCOMPLETE_DAILY_QUOTA'"
   ```

   It answers `(Update count: 1, 15 ms)`. The URL and the user `sa` are the ones in `application.properties`; there's no password. (If your H2 version is different, look in that `h2` folder for the right one.)
3. Make sure the fake provider is back to normal (`curl -X POST http://localhost:8089/__admin/mappings/reset`), and start the importer again.

As far as the importer can tell, import 1 stopped **yesterday**, and the quota has been reset since. This was tried while writing the guide (on a later day than the outputs in step 10). A couple of seconds after start-up:

```
INFO … [   scheduling-1] c.example.importer.imports.ImportWorker  : Taking import 1 (team 900003) from the queue, run 2
INFO … [   scheduling-1] c.example.importer.imports.ImportRunner  : Import 1 of team 900003 started
INFO … [   scheduling-1] c.example.importer.imports.ImportRunner  : Import 1 of team 900003 complete
```

```
{"id":1,"teamApiId":900003,"status":"COMPLETE","squadSize":5,"profilesFetched":5,"skipped":2,"skippedReasons":["Player 9000022: name is 121 characters long (at most 100 fit)","Player 9000024: profile has no name"],"quotaRemaining":89,"error":null,"createdAt":"2026-11-24T09:47:59.594895Z","finishedAt":"2026-11-24T09:49:01.463850Z","runs":2}
```

Guide 07's two skipped players, on run 2, with nothing typed but a restart. Editing rows by hand is fine for a demonstration on your own machine's copy of the data; it's not how you'd treat a real database.

### 12. The tests: a database each, and no scheduler

Run the tests now, and a lot of them fail to compile: `ImportService.start` and `failInterruptedImports` are gone, and `SynchronousAsync` makes a `SyncTaskExecutor` for an `@Async` that no longer exists. Three changes to the set-up, then each test class in turn.

**Delete `src/test/java/com/example/importer/SynchronousAsync.java`.**

**`ImporterIntegrationTest.java`**: a database of its own for each context, the worker switched off, and only `TestTime` imported:

```java
package com.example.importer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * The whole app, with:
 * <ul>
 * <li>its own in-memory database (a new, empty one for each test context) instead of ./data/importer;</li>
 * <li>the scheduled queue worker switched off: tests call {@code ImportWorker.runOnce()} when they want it to run;</li>
 * <li>a clock that only moves when a test moves it (nothing really sleeps).</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:importer-test-${random.uuid};DB_CLOSE_DELAY=-1",
        "importer.queue.enabled=false" })
@AutoConfigureMockMvc
@Import(TestTime.class)
public @interface ImporterIntegrationTest {
}
```

- **`properties = { …, … }`**: an array, now there are two.
- **`${random.uuid}`** is a value Spring Boot makes up for you: a new random UUID each time it's resolved. Each Spring context resolves the URL once, when it starts, so each gets a database with a name of its own, such as `importer-test-3f1c…`, empty, with Flyway's migrations run fresh. A context that's **reused** (two test classes with exactly the same configuration share one) shares its database too, which is why each class still drains the queue.
- **`importer.queue.enabled=false`**: step 8's `SchedulingConfig` doesn't exist in the tests, so no `scheduling-1` thread ever runs the worker behind a test's back.

**`imports/ImportApiTest.java`**. The tests that `POST` and then check the outcome now have to run the worker in between. Inject it, with the other fields (and move `TeamRepository`'s field up with them, if it's still below the tests where guide 07 added it):

```java
    @Autowired
    ImportWorker worker;

    @Autowired
    TeamRepository teams;
```

Add two helpers at the end of the class, before the stubs:

```java
    /** POST, then let the worker run it (in the app, the scheduled worker does this within seconds). */
    private String importAndRun(long teamApiId) {
        String location = mvc.post().uri("/imports/teams/" + teamApiId).exchange().getResponse().getHeader("Location");
        runQueue();
        return location;
    }

    /** Everything that's ready, oldest first (other tests in this class may have left imports queued). */
    private void runQueue() {
        while (worker.runOnce()) {
            // next
        }
    }
```

The first test shows the new conversation, with a check that **nothing** reached the provider before the worker ran. Rename it and change it like this:

```java
    @Test
    void postQueuesTheImportAndSaysWhereToCheckOnIt() {
        stubTeam(900051);
        stubSquad(900051, 9000511);
        stubProfile(9000511, ProviderJson.profile(9000511, "A. Player"));

        MvcTestResult result = mvc.post().uri("/imports/teams/900051").exchange();

        assertThat(result).hasStatus(HttpStatus.ACCEPTED)
                .bodyJson().isLenientlyEqualTo("""
                        {"teamApiId":900051,"status":"QUEUED","runs":0}
                        """);
        String location = result.getResponse().getHeader("Location");
        assertThat(location).matches("/imports/\\d+");
        provider.verify(exactly(0), getRequestedFor(urlPathEqualTo("/teams"))); // nothing until the worker runs

        runQueue();

        assertThat(mvc.get().uri(location))
                .hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        {"teamApiId":900051,"status":"COMPLETE","squadSize":1,"profilesFetched":1,"skipped":0,
                         "skippedReasons":[],"error":null,"runs":1}
                        """);
    }
```

with two more WireMock imports, which guide 06 used in `RateLimitTest`:

```java
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
```

In **`theStatusShowsSkippedPlayersAndWhy`**, **`whenTheProviderFailsTheImportIsMarkedFailed`**, **`aTeamTheProviderDoesNotHaveFailsTheImport`** and guide 07's **`aTeamWeCannotStoreFailsTheImportAndIsNotSaved`**, replace the line that posts and reads the `Location` with the helper. For example:

```java
        String location = importAndRun(900052);
```

The `409` test now saves a row that the constructor makes `QUEUED`, and expects the new message:

```java
    @Test
    void aTeamWithAnUnfinishedImportIsAConflict() {
        Import queued = imports.save(new Import(900053, Instant.now()));

        assertThat(mvc.post().uri("/imports/teams/900053"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().isLenientlyEqualTo("""
                        {"status":409,"title":"Conflict",
                         "detail":"Team 900053 already has an unfinished import (import %d, QUEUED)"}
                        """.formatted(queued.getId()));
    }
```

And the restart test checks the import goes back in the queue:

```java
    @Test
    void importsLeftRunningByARestartAreQueuedAgain() {
        Import cutOff = new Import(900056, Instant.now());
        cutOff.start();
        imports.save(cutOff);

        importService.requeueInterruptedImports();

        assertThat(importService.view(cutOff.getId()).status()).isEqualTo(ImportStatus.QUEUED);
    }
```

**`cutOff.start()`** makes it `RUNNING`, as the worker would have. The test can call it because it's in the same package, `imports`, as `Import`: package-private methods are visible to tests in the same package.

**`team/TeamApiTest.java`**. Its two tests that set things up with a `POST` need the worker to run before they look at `GET /teams`. Inject it:

```java
import com.example.importer.imports.ImportWorker;
```

```java
    @Autowired
    ImportWorker worker;
```

and add one line after each `POST`, in `getTeamsListsWhatWeStored`:

```java
        assertThat(mvc.post().uri("/imports/teams/900032")).hasStatus(HttpStatus.ACCEPTED);
        worker.runOnce();
```

and the same after `/imports/teams/900033` in `listsATeamsPlayers`. One `runOnce()` is enough: each test queues one import, and every import in this class finishes in one run.

**`provider/RateLimitTest.java`**. Its `runImport` helper started an import and called the runner directly. Swap the `ImportRunner` field for the worker:

```java
import com.example.importer.imports.ImportWorker;
```

```java
    @Autowired
    ImportWorker worker;
```

(remove the `ImportRunner` field and its import), and change the helper, adding a `runQueue` like `ImportApiTest`'s:

```java
    private ImportView runImport(long teamApiId) {
        ImportView queued = importService.queue(teamApiId);
        runQueue();
        return importService.view(queued.id());
    }

    /** Everything that's ready, oldest first (earlier tests in this class may have left imports waiting). */
    private void runQueue() {
        while (worker.runOnce()) {
            // next
        }
    }
```

One test changes its meaning, for the better. Guide 06's `importingAgainTheNextDayCarriesOnWhereItStopped` moved the clock on a day and imported the team **again**, as you had to then. Now the worker carries on **the same import** by itself. Its second half becomes:

```java
        clock.advance(Duration.ofDays(1));
        runQueue(); // guide 09: the worker picks it up again by itself after midnight UTC
        ImportView second = importService.view(first.id());

        assertThat(second.status()).isEqualTo(ImportStatus.COMPLETE);
        assertThat(second.runs()).isEqualTo(2);
        assertThat(second.profilesFetched()).isEqualTo(2); // 1 yesterday + only the one still missing today
        assertThat(second.quotaRemaining()).isEqualTo(99);
        provider.verify(exactly(1), getRequestedFor(urlPathEqualTo("/players/profiles"))
                .withQueryParam("player", equalTo("9000641")));
```

`second` is now the **same row** as `first`, on its second run, so `profilesFetched` is the total over both days, 2; the last line still proves player 9000641's profile was asked for only once.

**`provider/ProviderFailureTest.java`**. Guide 08's `anImportThatCannotReachTheProviderFailsWithTheReason` started an import and called the runner. The same swap: an `ImportWorker worker` field (and import) instead of `ImportRunner runner`, and:

```java
        ImportView queued = importService.queue(900071);
        worker.runOnce();

        assertThat(importService.view(queued.id()).error()).isEqualTo("API-Football did not answer within 300 ms");
```

Every other test class is unchanged.

### 13. Tests for the queue: `ImportQueueTest`

Now the worker's own rules. Create `src/test/java/com/example/importer/imports/ImportQueueTest.java`:

```java
package com.example.importer.imports;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.example.importer.ImporterIntegrationTest;
import com.example.importer.TestTime.TestClock;
import com.example.importer.provider.ProviderJson;
import com.example.importer.provider.QuotaTracker;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

@ImporterIntegrationTest
@WithMockUser(roles = "ADMIN")
class ImportQueueTest {

    @RegisterExtension
    static WireMockExtension provider = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        registry.add("api-football.base-url", provider::baseUrl);
    }

    @Autowired
    ImportService importService;

    @Autowired
    ImportWorker worker;

    @Autowired
    QuotaTracker tracker;

    @Autowired
    TestClock clock;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ImportRepository imports;

    @BeforeEach
    void emptyQueueAndAFreshDay() {
        imports.deleteAll(); // no imports left by other tests, whichever order they ran in
        tracker.reset();
        clock.set(Instant.parse("2030-01-01T12:00:00Z"));
        provider.resetRequests(); // so request counts only see this test's requests
    }

    @Test
    void theWorkerRunsOneImportAtATimeOldestFirst() {
        stubTeam(900091, 9, 80);
        stubTeam(900092, 9, 80);
        ImportView first = importService.queue(900091);
        ImportView second = importService.queue(900092);

        assertThat(worker.runOnce()).isTrue();

        assertThat(importService.view(first.id()).status()).isEqualTo(ImportStatus.COMPLETE);
        assertThat(importService.view(second.id()).status()).isEqualTo(ImportStatus.QUEUED);

        assertThat(worker.runOnce()).isTrue();
        assertThat(importService.view(second.id()).status()).isEqualTo(ImportStatus.COMPLETE);
        assertThat(worker.runOnce()).isFalse(); // nothing left
    }

    @Test
    void whileTheDailyQuotaIsUsedUpTheWorkerWaitsWithoutSendingAnything() {
        stubTeam(900093, 9, 80);
        tracker.dayExhausted();
        ImportView queued = importService.queue(900093);

        assertThat(worker.runOnce()).isFalse();

        assertThat(importService.view(queued.id()).status()).isEqualTo(ImportStatus.QUEUED);
        provider.verify(exactly(0), getRequestedFor(urlPathEqualTo("/teams")));

        clock.set(Instant.parse("2030-01-02T00:00:30Z")); // just after midnight UTC
        assertThat(worker.runOnce()).isTrue();
        assertThat(importService.view(queued.id()).status()).isEqualTo(ImportStatus.COMPLETE);
    }

    @Test
    void anImportStoppedByTheDailyQuotaCarriesOnByItselfAfterMidnightUtc() {
        stubTeam(900094, 9, 0); // the day's last request
        ImportView queued = importService.queue(900094);
        worker.runOnce();
        assertThat(importService.view(queued.id()).status()).isEqualTo(ImportStatus.INCOMPLETE_DAILY_QUOTA);

        tracker.reset(); // even after a restart (the tracker forgets), it must wait for the new day
        clock.set(Instant.parse("2030-01-01T23:59:00Z"));
        assertThat(worker.runOnce()).isFalse();
        provider.verify(exactly(1), getRequestedFor(urlPathEqualTo("/teams")));

        stubTeam(900094, 9, 100); // a new day, a new quota
        clock.set(Instant.parse("2030-01-02T00:00:30Z"));
        assertThat(worker.runOnce()).isTrue();

        ImportView resumed = importService.view(queued.id());
        assertThat(resumed.status()).isEqualTo(ImportStatus.COMPLETE);
        assertThat(resumed.runs()).isEqualTo(2);
        assertThat(resumed.quotaRemaining()).isEqualTo(100);
    }

    @Test
    void aSecondRequestWhileTheFirstIsWaitingForTomorrowIsAConflict() {
        stubTeam(900095, 9, 0);
        ImportView queued = importService.queue(900095);
        worker.runOnce();

        assertThat(mvc.post().uri("/imports/teams/900095"))
                .hasStatus(409)
                .bodyJson().extractingPath("$.detail").asString()
                .isEqualTo("Team 900095 already has an unfinished import (import %d, INCOMPLETE_DAILY_QUOTA)"
                        .formatted(queued.id()));
    }

    @Test
    void getImportsListsTheLatestFirst() {
        ImportView older = importService.queue(900096);
        ImportView newer = importService.queue(900097);

        assertThat(mvc.get().uri("/imports"))
                .hasStatusOk()
                .bodyJson().extractingPath("$[0:2].id").asArray().containsExactly(
                        newer.id().intValue(), older.id().intValue());
    }

    private void stubTeam(long id, int minuteLeft, int dayLeft) {
        provider.stubFor(get(urlPathEqualTo("/teams")).withQueryParam("id", equalTo(String.valueOf(id)))
                .willReturn(withLimits(okJson(ProviderJson.team(id, "Team " + id, null, "England", false)),
                        minuteLeft, dayLeft)));
        provider.stubFor(get(urlPathEqualTo("/players/squads")).withQueryParam("team", equalTo(String.valueOf(id)))
                .willReturn(withLimits(okJson(ProviderJson.squad(id)), minuteLeft, dayLeft)));
    }

    private static ResponseDefinitionBuilder withLimits(ResponseDefinitionBuilder response, int minute, int day) {
        return response.withHeader("x-ratelimit-remaining", String.valueOf(minute))
                .withHeader("x-ratelimit-requests-remaining", String.valueOf(day));
    }
}
```

The new things in it:

- **`@BeforeEach emptyQueueAndAFreshDay()`**: an **empty `team_import` table**, a clean tracker, and noon on 1 January 2030. Then **`provider.resetRequests()`** empties WireMock's request journal, so the `verify` counts in the test only see the test's own requests.
- **`imports.deleteAll()`** is one of the methods `ImportRepository` has had since guide 05, from `JpaRepository`, like `save` and `findById`: it deletes every row. It loads each `Import` and deletes it, so the rows in `team_import_skipped`, the skipped reasons, go first, and their foreign key to `team_import` is never broken. Nothing else points at `team_import`, so that's all there is to delete. Why delete rather than drain the queue with `while (worker.runOnce())`, as the other classes do? Because draining at noon on 1 January only empties what's ready **at noon on 1 January**. An import a test left `INCOMPLETE_DAILY_QUOTA` isn't ready until 2 January, so it would stay, and the next test that moves the clock to 2 January would find it and run it before its own. JUnit runs a class's tests in an order that's fixed but deliberately not obvious, so whether that happens depends on which test comes first. After `deleteAll()`, every test starts with nothing in the table, whatever ran before it.
- **`stubTeam(id, minuteLeft, dayLeft)`**: a team, with guide 06's limit headers on the reply, and an **empty squad** (`ProviderJson.squad(id)` with no players). An import of it is two requests, which is all these tests need: they're about **which** import runs and **when**, not what it stores.
- **`stubTeam(900094, 9, 0)`** makes the team reply "the day's last request", so the import stops `INCOMPLETE_DAILY_QUOTA` before asking for the squad, as in guide 06.
- **`.bodyJson().extractingPath("$.detail").asString()`** picks one field out of the JSON with a **JSONPath** expression and checks it exactly. **`"$[0:2].id"`** takes the `id` of the first two entries of the array (from index 0 up to, not including, 2); `.asArray().containsExactly(…)` checks them, in order. The ids come back from JSON as `Integer`, hence `.intValue()`.

What each test proves:

| Test | Proves |
| --- | --- |
| `theWorkerRunsOneImportAtATimeOldestFirst` | One `runOnce()` runs **one** import, the oldest, to the end; the next waits its turn; an empty queue returns `false` |
| `whileTheDailyQuotaIsUsedUpTheWorkerWaitsWithoutSendingAnything` | With the day used up, the import stays `QUEUED` and **0** requests are sent. Just after midnight UTC, it runs |
| `anImportStoppedByTheDailyQuotaCarriesOnByItselfAfterMidnightUtc` | Even with the tracker reset (a restart), the stopped import isn't touched at 23:59: still **1** request in all. At 00:00:30 it carries on, `COMPLETE` on **run 2** |
| `aSecondRequestWhileTheFirstIsWaitingForTomorrowIsAConflict` | `409` for an `INCOMPLETE_DAILY_QUOTA` import, with the status in the message |
| `getImportsListsTheLatestFirst` | `GET /imports`, newest first |

The third test is the one step 11 promised: midnight, in milliseconds. **`tracker.reset()`** is the restart. Without the `finishedAt` rule, the worker would pick the import up at 23:59, the moment the tracker forgot.

Run everything. In IntelliJ, right-click `src/test/java` → **Run 'All Tests'**, or in a terminal, in `importer/`:

```bash
./mvnw test
```

(`.\mvnw test` in PowerShell.)

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.240 s -- in com.example.importer.imports.ImportQueueTest
...
[INFO] Tests run: 81, Failures: 0, Errors: 0, Skipped: 0
```

> **Sidebar: more than one importer.** Everything in this guide assumes **one** importer process. Run two copies against the same database (two servers, for resilience, or a second one started by mistake) and three things go wrong. None of the fixes below were tried for this series: it runs on H2, in one process, and nothing here was tested with two instances or with ShedLock. Check each against its documentation.
>
> **What breaks.** **Both workers can claim the same import.** `claimNext` reads "the oldest `QUEUED` import" and then marks it `RUNNING`; two workers reading at the same moment both see it `QUEUED`, both mark it, and the team is imported twice, spending the quota twice. **The `409` check has the same race**, between two `POST`s on two instances. And **each instance has its own `QuotaTracker`** in memory, so each learns about the shared quota only from its own responses: still safe, because the provider's headers are the truth (guide 06), just one request late.
>
> **The fixes, simplest first:**
>
> 1. **Run one worker.** Leave `importer.queue.enabled=true` on one instance and set it to `false` on the others. They still accept `POST`s and answer `GET`s; only one of them ever starts imports. The property already exists, and for an importer this is often enough.
> 2. **ShedLock** (`net.javacrumbs.shedlock:shedlock-spring`, with `shedlock-provider-jdbc-template` and a small `shedlock` table): put `@SchedulerLock(name = "import-queue", lockAtMostFor = "…")` on `checkTheQueue`, and only one instance at a time runs it, whichever gets the lock. Check which version supports Spring Boot 4 first. `lockAtMostFor` must be longer than your longest import, or a second instance could start while the first is still busy.
> 3. **Let the database hand out the rows.** Claim with a row lock that skips rows another transaction holds: `select … for update skip locked`, supported by PostgreSQL and MySQL 8. In Spring Data JPA, that's `@Lock(LockModeType.PESSIMISTIC_WRITE)` on the claiming query plus a lock timeout hint of `-2` (`@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))`), which Hibernate turns into `SKIP LOCKED`. Then several workers can run at once, each claiming a different import; you'd still want them to share one quota. Or **optimistic locking**: a `@Version` column on `Import`, so the second worker's update fails with an `ObjectOptimisticLockingFailureException` and it moves on to the next row.
> 4. **Share the quota state**, if instances mustn't overshoot the per-minute limit between them: a `provider_quota` row in the database, or Redis, instead of the in-memory tracker.
>
> And for the `409` race, let the database refuse the second row. In PostgreSQL, a **partial unique index**, one that only covers some rows, does it: `create unique index one_unfinished_import_per_team on team_import (team_api_id) where status in ('QUEUED', 'RUNNING', 'INCOMPLETE_DAILY_QUOTA');`. H2 has no partial indexes, which is one more reason to move to a database server ([guide 03](03-storing-teams.md)'s sidebar) before running more than one importer.

---

## Your turn

### Exercise 1: predict what the worker does

Without running anything, say what happens in each case: which imports run, in what order, how many requests reach the provider, and how each import ends. The importer uses the default 10-second poll interval, and the times are UTC.

1. At 15:01, import 1 (team 900003) stopped `INCOMPLETE_DAILY_QUOTA`. At 15:10 you queue 900001 and then 900002. The provider is still out of requests today.
2. The same as case 1, but at 15:05 you restarted the importer, before queuing the two teams.
3. Team 900002's last import is `FAILED`. You `POST /imports/teams/900002`.
4. An import is on `"runs":1,"profilesFetched":3` when the importer is killed. It's restarted with the provider working normally, and the team's squad has 6 players, none of whom were stored before this import.
5. Someone starts the importer with `--importer.queue.enabled=false`, and `POST`s a team.

<details>
<summary>Solution</summary>

1. **Nothing runs until midnight.** The two new imports are `QUEUED`, but every check finds the tracker's day used up (`dailyQuotaExhausted()`), and does nothing: **0** requests. Just after 00:00 UTC, one check runs all three, in this order: 900001, 900002 (`QUEUED` first, oldest first), then import 1 (an `INCOMPLETE_DAILY_QUOTA` import from an earlier day). Import 1 is on **run 2**.
2. **One request goes out.** The restart made the tracker forget, so the worker no longer knows the day is used up. Import 1 still isn't touched (it finished today, and that's in its row). But 900001 is `QUEUED`, so it's claimed and run: its first request gets "no more today" back, the tracker believes it, and 900001 ends **`INCOMPLETE_DAILY_QUOTA`**. 900002 stays `QUEUED`: the next `runOnce()` stops at the tracker again. After midnight, the queue goes 900002 (still `QUEUED`), then import 1 and 900001 (oldest first). That one request is the price of the tracker living in memory, as in guide 06's exercise 1, case 7; this guide made sure it's one, not one per waiting import.
3. **`202`, and it runs**, as a new import with its own id and `"runs":0`. A `FAILED` import isn't unfinished, so it doesn't block the `POST`, and the worker never retries it by itself: the new import is the retry, because you asked for it.
4. At start-up the import goes back to **`QUEUED`** (the `WARN … queued again` line), and the worker takes it within one poll interval as **run 2**. Its 3 profiles are already stored, so run 2 fetches the other 3: it ends **`COMPLETE`** with `"runs":2,"profilesFetched":6` (3 + 3; the count is never reset), and the provider has had 2 + 3 requests on run 2.
5. **`202`, `QUEUED`, and it stays `QUEUED` for ever.** With the property `false`, `SchedulingConfig` doesn't exist, so there's no `@EnableScheduling` and nothing calls the worker. Nothing fails, and nothing in the log mentions `ImportWorker` at all (Common errors). A second `POST` of the same team is a `409`, because the first is still unfinished.

</details>

### Exercise 2: a failed import stays failed

The Concepts say the worker **never** retries a `FAILED` import: it only claims `QUEUED` ones and quota-stopped ones. Prove it with a test in `ImportQueueTest`, `aFailedImportIsNotTriedAgain`:

- Every `/teams` request gets a `500`. Queue team `900098`, and run the worker: the import is `FAILED`.
- Running the worker again does nothing, and the provider has had exactly **one** `/teams` request.
- And yet the team can be queued again: a `POST` is accepted.

How many requests would you expect if the provider answered `503` instead? (No need to write that test.)

<details>
<summary>Solution</summary>

```java
    @Test
    void aFailedImportIsNotTriedAgain() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(serverError()));
        ImportView queued = importService.queue(900098);

        assertThat(worker.runOnce()).isTrue();
        assertThat(importService.view(queued.id()).status()).isEqualTo(ImportStatus.FAILED);

        assertThat(worker.runOnce()).isFalse();
        provider.verify(exactly(1), getRequestedFor(urlPathEqualTo("/teams")));

        assertThat(mvc.post().uri("/imports/teams/900098")).hasStatus(202); // but it may be queued again
    }
```

with one more import, `serverError()`, which guide 05's `ImportApiTest` used:

```java
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
```

- **One request in all.** Guide 08's `ProviderRetry` never retries a `500`, and the worker never picks a `FAILED` import up again. The second `runOnce()` finds nothing ready and returns `false`.
- **The `POST` at the end** queues a new import, which nothing runs: the next test's `@BeforeEach` deletes it with the rest.
- **With a `503`**, guide 08 retries: **3** requests (with waits of 1 and 2 seconds, recorded by the `RecordingSleeper`, not slept), and then the import is `FAILED`, and the worker leaves it alone.
- **Want to show a `FAILED` import isn't picked up after midnight either?** Add `clock.set(Instant.parse("2030-01-02T00:00:30Z"));` before the second `runOnce()`, and it still returns `false`. That's only a fair test because `@BeforeEach` emptied the table: a later day wakes **every** import stopped by the quota, and if an earlier test had left one behind, `runOnce()` would run that one and return `true`. A test that moves time forward has to think about everything that time wakes up.

This was run while writing the guide, with and without the clock moved: it passes with no change to the main code.

</details>

### Exercise 3: yesterday's imports first

`claimNext` takes `QUEUED` imports first, and quota-stopped imports only when nothing is queued. Make it the other way round: after midnight, an import that stopped **yesterday** has waited longest, and should carry on before anything queued today.

1. Write the test first, in `ImportQueueTest`: `yesterdaysUnfinishedImportGoesFirst`. Team `900099` stops for the quota on 1 January. Just after midnight, team `900090` is queued. One `runOnce()` should finish 900099 and leave 900090 `QUEUED`. Run it, and watch it fail.
2. Change `claimNext`, and run the whole class.

<details>
<summary>Solution</summary>

**The test:**

```java
    @Test
    void yesterdaysUnfinishedImportGoesFirst() {
        stubTeam(900099, 9, 0); // the day's last request
        ImportView stopped = importService.queue(900099);
        worker.runOnce();

        clock.set(Instant.parse("2030-01-02T00:00:30Z"));
        stubTeam(900099, 9, 100);
        stubTeam(900090, 9, 99);
        ImportView newer = importService.queue(900090);

        assertThat(worker.runOnce()).isTrue();
        assertThat(importService.view(stopped.id()).status()).isEqualTo(ImportStatus.COMPLETE);
        assertThat(importService.view(newer.id()).status()).isEqualTo(ImportStatus.QUEUED);
    }
```

The second `stubTeam(900099, …)` gives the same request a new answer for the new day: of two stubs that match, WireMock uses the one added last, as in `anImportStoppedByTheDailyQuotaCarriesOnByItselfAfterMidnightUtc`.

Before the change, running `ImportQueueTest` gives **one** failure, the new test:

```
expected: COMPLETE
 but was: INCOMPLETE_DAILY_QUOTA
```

900090, queued today, ran first, and 900099 is still waiting. The failing test leaves 900099 `INCOMPLETE_DAILY_QUOTA` behind, but that doesn't upset `anImportStoppedByTheDailyQuotaCarriesOnByItselfAfterMidnightUtc`, which also moves the clock to 2 January: its `@BeforeEach` deletes the leftover first, so the worker can only find the test's own import.

**The change**, in `ImportService.claimNext`: swap the two queries.

```java
        Optional<Import> next = imports.findFirstByStatusAndFinishedAtBeforeOrderByIdAsc(
                        ImportStatus.INCOMPLETE_DAILY_QUOTA, startOfTodayUtc)
                .or(() -> imports.findFirstByStatusOrderByIdAsc(ImportStatus.QUEUED));
```

Update its Javadoc to match ("the oldest one stopped by the daily quota on an earlier (UTC) day, otherwise the oldest QUEUED one"). Now the class passes: yesterday's import goes first. With exercises 2 and 3, the whole suite runs **83** tests, all passing (run while writing this guide).

Which order is right? Both are reasonable. Yesterday-first is fairer to the import that has waited longest. Queued-first lets a team you've just asked for jump ahead of a long import that might use the whole day's quota again. What matters is that the order is a decision, written in one place, and a test says which one you chose.

</details>

---

## Checkpoint

In `importer/`:

```bash
./mvnw test
```

```
[INFO] Tests run: 81, Failures: 0, Errors: 0, Skipped: 0
```

That's guide 08's 76, with `ImportApiTest`, `RateLimitTest`, `ProviderFailureTest` and `TeamApiTest` changed but no longer or shorter, plus `ImportQueueTest`'s 5. With the tests from exercises 2 and 3, 83.

**Tests you kept from earlier exercises** may need the same treatment as step 12. Anything that calls `importService.start(…)` and `runner.run(…)` (guide 06's `ReserveTest`, guide 08's exercise 2 helper) becomes `importService.queue(…)` followed by `worker.runOnce()`, with an `ImportWorker` field instead of the `ImportRunner` one. Anything that `POST`s and then checks the import (guide 05's exercises 2 and 3) needs the worker run in between. Guide 05's exercise 3 also relied on a new `Import` being `RUNNING`: it's `QUEUED` now, which still blocks a second import of the team, but the test names no longer quite fit.

With the fake provider and the importer running: `POST /imports/teams/{id}` answers `202` with `"status":"QUEUED","runs":0`, the log shows `Taking import … from the queue, run 1` on the `scheduling-1` thread within a poll interval, and `GET /imports` lists the latest imports, newest first. While the day's quota is used up, queued imports stay queued and the provider receives nothing; an import left `RUNNING` by a restart is queued again and finished as run 2.

Your files (new ones marked **new**, changed ones **changed**, deleted ones **deleted**; unchanged folders left out):

```
importer/src/
├── main/
│   ├── java/com/example/importer/
│   │   └── imports/
│   │       ├── AsyncConfig.java                      deleted
│   │       ├── Import.java                           changed
│   │       ├── ImportAlreadyRunningException.java    changed
│   │       ├── ImportController.java                 changed
│   │       ├── ImportRepository.java                 changed
│   │       ├── ImportRunner.java                     changed
│   │       ├── ImportService.java                    changed
│   │       ├── ImportStatus.java                     changed
│   │       ├── ImportView.java                       changed
│   │       ├── ImportWorker.java                     new
│   │       └── SchedulingConfig.java                 new
│   └── resources/
│       ├── application.properties                    changed
│       └── db/migration/
│           └── V4__add_import_runs.sql               new
└── test/java/com/example/importer/
    ├── ImporterIntegrationTest.java                  changed
    ├── SynchronousAsync.java                         deleted
    ├── imports/
    │   ├── ImportApiTest.java                        changed
    │   └── ImportQueueTest.java                      new
    ├── provider/
    │   ├── ProviderFailureTest.java                  changed
    │   └── RateLimitTest.java                        changed
    └── team/
        └── TeamApiTest.java                          changed
```

No new dependencies (`@Scheduled` and `@EnableScheduling` are part of Spring Framework, already on the classpath), and nothing changes in the fake provider.

---

## Common errors

**Imports stay `QUEUED` for ever, and nothing fails**
`POST` answers `202 … "status":"QUEUED"`, and fifteen seconds later `GET /imports/{id}` still says `QUEUED`. The log has **no** `ImportWorker` lines at all. Nothing runs the worker: either `importer.queue.enabled` is `false` (in `application.properties`, or a `--importer.queue.enabled=false` program argument left over from something), or `@EnableScheduling` is missing from `SchedulingConfig`. Both look exactly the same, which is what makes this one confusing: `@Scheduled` without `@EnableScheduling` is silently ignored. (Captured while building the guide, by starting the importer with `--importer.queue.enabled=false`.) If the log has `Taking import …` lines but not for **your** import, see the next two entries instead.

**The importer won't start: `Invalid fixedDelayString value "10 seconds"`**
In full:

```
org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'importWorker' …
Caused by: java.lang.IllegalStateException: Encountered invalid @Scheduled method 'checkTheQueue': Invalid fixedDelayString value "10 seconds"; java.lang.IllegalArgumentException: '10 seconds' is not a valid duration, cannot detect any known style
```

The poll interval was written in words (`--importer.queue.poll-interval="10 seconds"`, captured while building the guide). Write `10s`, as `application.properties` does, or the ISO form `PT10S`. And remember guide 06's trap: a bare number, such as `10`, is **milliseconds**, which would make the worker check the queue a hundred times a second.

**A team answers `409` with `INCOMPLETE_DAILY_QUOTA`, and you want to import it now**
Working as intended: the import is waiting for 00:00 UTC, and will carry on by itself. Guide 06's way round it, restarting the importer, doesn't work any more (step 11 explains why, and shows how to move the import's finish time back on your own machine). If the quota really has reset early, for example because you upgraded your API-Football plan, that's the case for editing the row, or for waiting.

**Queued imports don't run, and the log has no `Taking import` lines, but the queue is switched on**
Look further up the log for `Import … stopped: API-Football's daily request quota is used up`. The tracker knows today's quota is gone, so the worker sends nothing until 00:00 UTC, however many imports are queued (step 10's last part). Check `GET /imports`: the stopped import says `INCOMPLETE_DAILY_QUOTA` with `"quotaRemaining":0`.

**`Database may be already in use` from H2's Shell**
```
Exception in thread "main" org.h2.jdbc.JdbcSQLNonTransientConnectionException: Database may be already in use: "…/importer/data/importer.mv.db". Possible solutions: close all other connection(s); use the server mode [90020-240]
```

The importer is still running and has the database file open (captured while writing step 11). Stop it first, then run the `Shell` command, then start it again.

**Tests that passed in guide 08 now fail, because an import is still `QUEUED`**
A test `POST`s an import (or calls `importService.queue`) and checks the outcome without running the worker. Scheduling is off in the tests, so nothing runs it unless the test does: add `worker.runOnce()`, or a `runQueue()` helper, as in step 12. And if a test fails only when it runs **with** other tests in its class, look for an import an earlier test left waiting, and empty the table before each test, as `ImportQueueTest`'s `@BeforeEach` does with `imports.deleteAll()`.

---

## Recap

- A thread per `POST` (guide 05's `@Async`) let imports **compete** for one quota, needed **you** to restart quota-stopped imports after midnight, and lost its work on a **restart**. The import rows were already a list of work, so they became a **queue**.
- **`POST /imports/teams/{apiId}`** only **queues**: `202 Accepted`, a `Location`, `"status":"QUEUED","runs":0`, and nothing sent to the provider. `AsyncConfig` and `SynchronousAsync` are gone, and `ImportRunner.run` is an ordinary method.
- **One worker**: `@Scheduled(fixedDelayString = "${importer.queue.poll-interval}")`, switched on by **`@EnableScheduling`** on a class that exists only when **`importer.queue.enabled`** is `true`. **`fixedDelay`** waits for the last run to **finish**, so the worker never overlaps itself, and imports run **one at a time**, on `scheduling-1`.
- **`runOnce()`**: if the tracker says the day is used up, **do nothing**; otherwise **claim** the next import in one short transaction (the oldest **`QUEUED`**, otherwise the oldest **`INCOMPLETE_DAILY_QUOTA` that finished before 00:00 UTC today**), and run it outside any transaction.
- The midnight rule reads the row's **`finishedAt`** and the injected **`Clock`**, so it holds **across restarts**, when the in-memory tracker has forgotten. `ImportService` takes every timestamp from the `Clock` now.
- Every run is **one row**: **`runs`** goes up (migration **`V4`**), the per-run fields start again, and **`profilesFetched`** keeps counting. An import cut off by a restart goes **back to `QUEUED`**, and finishes as the next run.
- **`409`** covers **`QUEUED`, `RUNNING` and `INCOMPLETE_DAILY_QUOTA`**, with the status in the message. `FAILED` imports are never retried by the worker; queue the team again. **`GET /imports`** shows the latest 50, newest first.
- **Tests**: scheduling **off**, tests call **`runOnce()`**, and every test context gets **its own database** (`${random.uuid}` in the URL), because the worker reads rows other classes would otherwise have left behind. Moving the clock forward wakes up everything waiting for tomorrow, in your test **and** before it.
- The midnight resume was **proved by a test**, not watched live; moving a row's `finished_at` back a day with H2's `Shell` shows it on your own machine.
- **More than one importer** needs one worker, a lock (ShedLock, `SKIP LOCKED`, `@Version`) or a database constraint: a sidebar, untested.

### Interview angle

<details>
<summary>"How would you run background jobs that must survive a restart?"</summary>

Store each job as a row in the database, with a status, rather than as a task in memory. The request that creates the job only inserts the row and answers `202 Accepted` with a link to its status. A worker, on a schedule or a loop, claims the next ready row in a short transaction, marks it running, does the work outside the transaction, and records the outcome on the row. At start-up, rows left "running" by a crash are put back as ready, which only works if the job is safe to run again: here, because the import keeps what it did and only fetches what's missing. For more than one worker process, claim rows with a lock (`select … for update skip locked`) or use a job framework or a message queue.

</details>

<details>
<summary>"What's the difference between `fixedRate`, `fixedDelay` and `cron` in Spring's `@Scheduled`?"</summary>

`fixedRate` starts the method every N, measured from the start of the previous run; if a run takes longer than N, the next one is late, and runs can bunch up. `fixedDelay` starts it N after the previous run finished, so a run never overlaps the next and a slow run simply pushes the schedule back. `cron` runs at set times of the day or week. For a worker that does a variable amount of work, `fixedDelay` is usually what you want. All of them need `@EnableScheduling`, and run on Spring's task scheduler, which Spring Boot gives one thread by default, so a slow scheduled method also delays every other scheduled method in the application.

</details>

<details>
<summary>"How do you test code that runs on a schedule?"</summary>

Separate the schedule from the work. Put the work in a public method that does one unit and returns, and let the scheduled method just call it in a loop. In tests, switch scheduling off (for example with a property that a `@ConditionalOnProperty` configuration class depends on), and call the work method directly, so nothing runs on a background thread behind the test's back and there's no need to sleep or poll. Inject the clock, so "after midnight" is a line in the test. And keep test data isolated: a worker that picks "the oldest ready row" will happily pick another test's leftovers.

</details>

<details>
<summary>"Two instances of your service run the same scheduled job. What can go wrong, and how do you stop it?"</summary>

Both run it, at about the same time: a job that claims work by reading "the next ready row" and then updating it can give the same row to both, so the work is done twice. Options, simplest first: run the scheduler on one instance only (a setting); use a distributed lock such as ShedLock, backed by a database table, so only one instance runs the job at a time; claim rows with `select … for update skip locked`, so several workers each get a different row; or use optimistic locking (a version column), so the second update fails and that worker moves on. Anything checked before an insert, such as "no unfinished job for this team", also needs the database to enforce it, for example with a partial unique index.

</details>

<details>
<summary>"When is personal data from a third-party API your problem?"</summary>

As soon as you store it. Under UK GDPR, information about an identifiable person is personal data even if it's publicly available, and copying it into your database makes you responsible for it: you need a lawful basis (often legitimate interests, with a short written assessment), you should store only what you need (data minimisation), keep it accurate, keep it only as long as you need it, protect it, and be able to correct or delete it on request. Tell people in your privacy notice where the data came from. Separately, check the provider's terms for what you're allowed to store and show.

</details>

### Further reading

- [Spring Framework reference: task execution and scheduling](https://docs.spring.io/spring-framework/reference/integration/scheduling.html), for `@Scheduled`, `@EnableScheduling`, `fixedDelay` and the duration formats `fixedDelayString` accepts
- [Spring Boot reference: task execution and scheduling](https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html), for the auto-configured scheduler and its `spring.task.scheduling.*` properties
- [Spring Boot reference: externalized configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html), including `${random.uuid}` and the other `random.*` values
- [Spring Data JPA reference: query methods](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html), for `In`, `Before`, `OrderBy…Asc` and `Top50`, and [locking](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html) for `@Lock`
- [ShedLock](https://github.com/lukas-krecan/ShedLock), for running a scheduled job on one instance at a time
- [PostgreSQL: `SELECT … FOR UPDATE SKIP LOCKED`](https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE), and [partial indexes](https://www.postgresql.org/docs/current/indexes-partial.html)
- [H2 tutorial](https://h2database.com/html/tutorial.html), including the command-line tools such as `Shell`
- [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x11-t10/), especially [API10: Unsafe Consumption of APIs](https://owasp.org/API-Security/editions/2023/en/0xaa-unsafe-consumption-of-apis/) and [API4: Unrestricted Resource Consumption](https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/)
- [ICO: guide to the UK GDPR](https://ico.org.uk/for-organisations/uk-gdpr-guidance-and-resources/), especially lawful basis, data minimisation and storage limitation

**Simplification to notice:** the queue is first come, first served, one import at a time, with one quota for everything. A real importer of a whole league might want priorities (this week's opponents first), a daily budget per kind of work, or a way to pause the queue without stopping the importer. Each is a small change to `claimNext` or `runOnce`, and each is a decision someone has to make on purpose.

---

## What you've built

Nine guides ago, there was a `GET /provider/teams/900001` that showed one fictional team. Now there's an importer that copies teams and players from a provider you don't control, into a database you do, at the provider's pace:

```
  ┌────────────────────────────┐        ┌───────────────────────────┐
  │ API-Football (real)        │   or   │ Fake provider (WireMock)  │
  │ v3.football.api-sports.io  │        │ localhost:8089            │
  └─────────────┬──────────────┘        └─────────────┬─────────────┘
                └───────────────┬─────────────────────┘
                                │  every request: limits checked, timed out, retried or not, logged with ****
                                ▼
                  ┌───────────────────────────┐
  you (admin) ──▶ │ Importer         :8080    │  POST /imports/teams/{id} → QUEUED
   HTTP Basic     │ one worker, one at a time │  GET  /imports, /imports/{id}, /teams …
                  └─────────────┬─────────────┘
                                ▼
                  ┌───────────────────────────┐
                  │ Our database (H2)         │  teams, players, imports: 81 tests
                  └───────────────────────────┘
```

| Guide | What the importer gained | What it protects against |
| --- | --- | --- |
| 01 | A `RestClient`, records for the provider's JSON, the key from an environment variable | Keys in files; a misconfigured start |
| 02 | The envelope, errors inside a `200`, our own exceptions | Treating a failure as data |
| 03 | Our own model, create-or-update by the provider's id, no HTTP inside transactions | Duplicates; long-held database locks |
| 04 | Squads, players who leave, fetching only what's missing | Wasted requests; stale squads |
| 05 | `202 Accepted`, import rows, `409`, HTTP Basic | Callers timing out; strangers spending the quota |
| 06 | Reading the limit headers, pausing, stopping for the day | `429`s; burning tomorrow's quota today |
| 07 | Three rules for the provider's data | Bad data in our database |
| 08 | Timeouts, retries that are worth it, masked logging | Hanging for ever; retrying what won't work; leaking the key |
| 09 | A queue, one worker, resuming after midnight | Imports fighting over the quota; work lost to restarts |

Three ideas ran through all of it:

- **The provider is someone else's system.** Its errors don't always look like errors, its data has gaps, it's sometimes slow or down, and its limits are its rules. Check everything, and believe what it tells you about itself (the headers) over your own guesses.
- **Every request costs something.** So fetch only what's missing, retry only what might work, stop cleanly when the day is used up, and carry on where you stopped.
- **Time and failure are inputs.** The clock, the sleeper and the fake provider's misbehaving files turned minutes, midnights and outages into tests that run in seconds.

---

## Security checklist

Before the importer runs anywhere but your own machine, or against the real API, go through this list. Each item points back to the guide that built it.

**The API key**

- [ ] It comes **only** from the **`APISPORTS_KEY`** environment variable (`api-football.key=${APISPORTS_KEY:}`), never from a committed file ([guide 01](01-calling-an-api.md)). On a server, the environment is filled from a secrets manager, not typed by hand.
- [ ] It travels in a **header**, `x-apisports-key`, never in a URL, where proxies and access logs would keep it (guide 08's exercise 4).
- [ ] The importer **refuses to start** with the real base URL and no key (guide 01).
- [ ] It's **never logged**: every request line shows `x-apisports-key=****`, `ApiFootballProperties.toString()` hides it, and a test proves the key is sent but never printed ([guide 08](08-when-the-provider-fails.md)). Don't log `HttpHeaders`, requests or settings objects anywhere else.
- [ ] Calls to the real API use **HTTPS** (`https://v3.football.api-sports.io`), and certificate checks are **never** switched off, not even "just for a corporate proxy": the key is in every request.
- [ ] **If it leaks**: regenerate it in the API-Football dashboard (the old one stops working), update the environment variable, restart the importer, and check whether anyone spent your quota.

**The admin password**

- [ ] **`dev-only-password` is for development only.** Anywhere shared or deployed, set **`IMPORTER_ADMIN_PASSWORD`** to a long random value from a secret store ([guide 05](05-imports-in-the-background.md)). A stricter version would have no default at all, so the importer wouldn't start without one; the series keeps the default so it runs with no set-up.
- [ ] HTTP Basic sends the password, base64-encoded but **not encrypted**, with every request: **only over HTTPS** anywhere but `localhost`.
- [ ] **Every** endpoint needs the admin role, including the read-only ones (`/teams`, `/teams/{id}/players`, `/imports`, `/provider/teams/{id}`): they show personal data, and the last one spends quota.

**The provider's data is untrusted input** ([OWASP API10:2023, Unsafe Consumption of APIs](https://owasp.org/API-Security/editions/2023/en/0xaa-unsafe-consumption-of-apis/))

- [ ] It's **validated before it's saved**: required fields, no invented values, unknown positions mapped to `null`, lengths checked and never cut short ([guide 07](07-validating-what-you-receive.md)); and replies of unexpected shapes, like `errors` as a list or an object, are handled ([guide 02](02-reading-the-providers-replies.md)).
- [ ] What we accept is **limited**: time limits on every call, a bounded number of retries with backoff, no retries of errors that would only repeat (guide 08), and the daily quota respected (guide 06).
- [ ] We only follow what we asked for: **no URL from the data is ever fetched** (logos and photos are stored nowhere). If a later feature downloads `photo` URLs, check the host is the provider's media host first, or you've built a way for the data to make your server fetch anything (SSRF).
- [ ] Names reach the database as **bound parameters** (JPA), so they can't change a query; anything that shows them in HTML must **escape** them.
- [ ] Two more from the same list: **API4, unrestricted resource consumption**, is why there's a queue, one worker and a quota check before every request; **API8, security misconfiguration**, is why the default password is marked and why Hibernate only **validates** the schema (`ddl-auto=validate`, never `update`).

**Timeouts, retries and the quota**

- [ ] Connect and read **timeouts** are set (5 s and 10 s), so no call can hang for ever (guide 08).
- [ ] Only failures that might succeed are **retried**, at most 3 attempts, with backoff; a `500`, a bad request or the daily quota never are (guide 08).
- [ ] The **daily quota** stops imports cleanly, nothing is sent until 00:00 UTC, and the worker carries on afterwards by itself ([guide 06](06-rate-limits-and-quotas.md) and this guide). With more than one instance, see this guide's sidebar.

**Personal data: UK GDPR**

The fake provider's players are fictional. **Real players are real people.** Once you point the importer at the real API, their **names, nationalities and birth years are personal data** under UK GDPR, even though they're publicly available, and storing them makes you responsible for them. This isn't legal advice; for a real product, check with someone who knows the law. But these are the questions to answer:

- [ ] **A lawful basis.** For a game using publicly available football data, that's likely **legitimate interests**, with a short written assessment that weighs your interest against the players'. Say in the game's **privacy notice** what you hold and where it came from (API-Football).
- [ ] **Data minimisation: store only what the game needs.** The importer already does: it keeps the birth **year**, not the full date of birth, and ignores the birthplace, height, weight and photo the profile also contains ([guide 04](04-squads-and-players.md)). Keep it that way when you add features.
- [ ] **Accuracy.** Guide 04's trade-off, profiles fetched once and never refreshed, means a player's details can go out of date. A re-import fixes squads, not profiles. Decide how stale is acceptable.
- [ ] **Retention.** Decide how long you keep a player who's no longer in any squad you import, and delete them after that, rather than keeping everyone for ever.
- [ ] **Requests from the people themselves.** Be able to correct or delete one player's data. Today that means deleting their `player` row and their `team_player` links, and the next import of their team would bring them back: a real app needs a "do not import" list.
- [ ] **Who can see it.** Only the admin, today. **Backups and logs count too**: the importer's logs contain team and player **ids**, not names, so keep it that way.
- [ ] **The provider's terms.** Not a GDPR matter, but the same checklist: read API-Football's terms of use for what you may store, for how long, and whether you may show it to others in a game.

### One last exercise

A colleague sends you a pull request for the importer with these changes. For each one, say which item on the checklist it breaks, and what you'd suggest instead.

1. Logging every request's headers at `DEBUG` level, "only switched on when we're debugging".
2. An `application-prod.properties`, committed, with `api-football.key=` the production key, "because environment variables are a pain on the server".
3. Storing each player's full date of birth and photo URL, "in case the game wants them later".
4. `GET /teams` and `GET /teams/{id}/players` made public (`permitAll()`), "so the game can read them without a password".
5. A trust-all `SSLContext` on the `RestClient`, "because the office proxy breaks HTTPS".
6. `importer.queue.enabled=true` on both of two new servers, "for resilience".

<details>
<summary>Solution</summary>

1. **The key, never logged.** The `x-apisports-key` header would be printed in full the first time someone switches `DEBUG` on, and logs are copied, shipped and kept. Log through guide 08's `masked(...)`, or not at all. Don't count on guide 08's `ProviderLoggingTest` to catch it: the test can only see what's logged while it runs, and a `DEBUG` line is switched off by default. Keep one place that logs requests, and make it mask.
2. **The key, only from the environment.** A committed file is in every clone and every copy of the history, for ever. Set `APISPORTS_KEY` on the server from a secrets manager. If the key has already been committed, regenerate it: removing the file doesn't remove it from the history.
3. **Data minimisation (UK GDPR).** "In case" isn't a need. Store the date of birth and the photo when, and if, the game really needs them, and record why. The photo URL also opens the SSRF question if anything ever fetches it.
4. **The admin role on every endpoint.** These show personal data to anyone who can reach the importer. The game should read from **its own copy** or from an API built for it, with its own access rules (guide 01's sidebar: the importer copies, everything else reads from the copy), not from the admin tool.
5. **HTTPS, never certificate checks off.** A trust-all `SSLContext` accepts anyone in the middle, who would then see the key in every request. Fix the trust instead: add the proxy's certificate authority to the JVM's trust store.
6. **More than one importer.** Two workers on one database can claim the same import and spend the quota twice. Switch the queue off on one of them, or add a lock (this guide's sidebar).

</details>

---

## Where next

The series stops where it promised to: the data is in our database. Some directions from here, in roughly the order you'd meet them:

- **Use the data.** Build what the importer was for: a game, a website, a stats tool, reading teams and players from **our** database. Keep the importer and the thing that uses its data apart, so the provider's key and quota stay in one place, and the game keeps working when the provider doesn't.
- **A real database.** Move from H2 to PostgreSQL (guide 03's sidebar has the configuration). It's also what makes the multi-instance fixes in this guide's sidebar possible: `SKIP LOCKED`, partial unique indexes, a database several processes can share.
- **More of the provider.** Fixtures, results and league tables use the same envelope, the same limits and the same techniques: fetch only what's missing, validate, queue, carry on tomorrow.
- **Deploy it.** Secrets from a secrets manager, HTTPS in front of the importer, logs and alerts you'd notice ("an import has been `FAILED` since Tuesday"), and backups, which hold personal data too.
- **Build the other side.** If you haven't, the [REST API](../rest-api/README.md) series builds an API like the one you've been consuming: from rate limits handed out to retries welcomed, the same ideas from the server's end.

---

**That's the end of the series.** Look back at the [series README](README.md) for the whole importer: the endpoints, the fake provider's switches, and how to run both together.
