# 05: Re-runnable and Concurrent Runs

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- Why a reconciliation run must be **re-runnable**: late files, corrected files, and runs triggered twice
- What **idempotent** means for a batch job: one run per account and business date, however many times it's started
- What goes wrong when two runs for the same account start at the same moment: every item **matched twice**, seen in a failing test
- Two complementary fixes: a **conditional update** that refuses to match an item that's no longer open, and a **pessimistic lock** that makes runs for one account take turns
- Why the order is **lock first, then read**
- How to add a unique constraint to a table that **already holds duplicates**, with a data-tidying Flyway migration

By the end, a run for any account and date can be started as often as anyone likes, by a scheduler and a person at the same time, and every item still ends up in exactly one match.

---

## Concepts

### How it works in a real bank

A reconciliation run for "the nostro, 6 November" is rarely run only once:

- **A file arrives late.** The scheme's report for the 6th lands at 11am instead of 6am, after the morning run. Operations re-run the 6th so the new lines are matched.
- **A file is corrected.** The correspondent sends a corrected statement. The original is left alone, and the correction's new items are imported and matched by running again.
- **A run fails half-way** (a database timeout, a deployment) and is started again.
- **Two triggers fire.** The end-of-day scheduler starts the run, and an operator, impatient for the results, clicks "Run now" at the same moment.

So a run must be **[idempotent](../glossary.md#idempotency)**: running it again for the same account and date must never create duplicate matches, and should report the same picture as one run would have. There's one *logical* run per account and business date; re-running it records another **attempt**, not another run.

You've already got part of this for free: a second run only loads items that are still `OPEN`, so sequentially it can't match anything twice. But in guide 03 you saw it create a second run record for the same date. And "sequentially" is doing a lot of work in that sentence.

### Two runs at once

Two transactions start the same run together. Under READ COMMITTED, each reads the open items **before** the other has committed anything, so both see all of them as `OPEN`. Each runs the matching engine, gets the same answer, saves its own matches and marks the items matched. Both commit. Every pair of items now has two match records, and each item points at whichever match was written last. The break report still looks fine, because every item is `MATCHED`. The match history, which is the audit trail, is wrong.

It's the same shape of bug as guide 04's double approval, and it has the same kind of fix, applied at two levels:

1. **A guard on every item.** Mark items matched with a conditional `UPDATE … WHERE status = 'OPEN'`, and check the row count. A second run's update finds the items already matched, changes nothing, and the run rolls back. The data is protected, but the second run *fails*.
2. **Take turns per account.** Lock the account's row (`SELECT … FOR UPDATE`) at the start of every run. A second run for the same account **waits** until the first commits, then reads the open items, finds the first run's matches already done, and has nothing left to do. Both runs succeed; neither does anything twice. Runs for *different* accounts still go in parallel.

The lock is what gives the right *behaviour*; the conditional update is the backstop that guarantees *correctness* if any code path ever forgets the lock. It's the same "logic for behaviour, database for guarantees" pairing as guide 02's checksum.

### Lock first, then read

The lock only helps if the run reads the open items **after** it has the lock. Read them before, and the second run is still working from a list made before the first run committed, however long it then waits for the lock. Lock, then read, then decide. You'll prove that in Exercise 1.

---

## Steps

### 1. A test that runs four reconciliations at once

`src/test/java/com/example/recon/run/ConcurrentRunTest.java`:

```java
package com.example.recon.run;

import com.example.recon.ReconIntegrationTest;
import com.example.recon.TestAccounts;
import com.example.recon.account.AccountRepository;
import com.example.recon.item.ItemStatus;
import com.example.recon.item.ReconItem;
import com.example.recon.item.ReconItemRepository;
import com.example.recon.ledger.LedgerService;
import com.example.recon.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** The scheduler and an operator both start the same reconciliation at the same moment. */
@ReconIntegrationTest
class ConcurrentRunTest {

    static final LocalDate NOV_6 = LocalDate.of(2026, 11, 6);
    static final int PAIRS = 20;

    @Autowired AccountRepository accounts;
    @Autowired ReconItemRepository items;
    @Autowired ReconMatchRepository matches;
    @Autowired ReconRunRepository runs;
    @Autowired LedgerService ledger;
    @Autowired ReconciliationService reconciliation;

    @Test
    void simultaneousRunsMatchEachItemExactlyOnce() throws Exception {
        String nostro = TestAccounts.newNostro(accounts).getCode();
        for (int i = 1; i <= PAIRS; i++) {
            ledger.post(nostro, "CUSTOMER_FUNDS", Money.gbp("10.00"), "INV-" + i, NOV_6, "Invoice " + i);
            items.save(ReconItem.external(nostro, null, "INV-" + i, Money.gbp("10.00"), NOV_6, NOV_6, "Statement"));
        }

        int threads = 4;
        CountDownLatch startingGun = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    try {
                        reconciliation.run(nostro, NOV_6);
                    } catch (RuntimeException e) {
                        synchronized (failures) {
                            failures.add(e);
                        }
                    }
                    return null;
                }));
            }
            startingGun.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        List<ReconMatch> made = matches.findAll().stream()
                .filter(m -> m.getAccountCode().equals(nostro)).toList();
        long runRows = runs.findAll().stream().filter(r -> r.getAccountCode().equals(nostro)).count();
        long matchedItems = items.findAll().stream()
                .filter(i -> i.getAccountCode().equals(nostro) && i.getStatus() == ItemStatus.MATCHED).count();
        System.out.printf("failures=%s matches=%d runRows=%d matchedItems=%d%n",
                failures.stream().map(f -> f.getClass().getSimpleName()).toList(), made.size(), runRows, matchedItems);

        assertThat(failures).isEmpty();
        assertThat(made).hasSize(PAIRS);           // one match per pair, not one per pair per run
        assertThat(matchedItems).isEqualTo(2L * PAIRS);
        assertThat(runRows).isEqualTo(1);         // one run per account and business date
    }
}
```

Twenty exact pairs, four simultaneous runs. The correct outcome: no failures, exactly twenty matches, forty matched items, and one run record. Run it:

```
failures=[] matches=80 runRows=4 matchedItems=40
```

```
Expected size: 20 but was: 80 in:
```

Every run succeeded, and every run matched every pair. Notice `matchedItems=40`: by item status alone, everything looks perfect. Only the match records give it away. Four "system" matches claim each pair, with four different run ids, and nobody can say which is real. A break report would never have shown you this.

### 2. Fix 1: never match an item that isn't open

Matching currently goes through `ReconItem.matchTo`, which checks `status` on the object in memory: the stale copy each run loaded before the others committed. Move the check into the database. In `ReconItemRepository` (importing `Modifying` and `java.util.Collection`):

```java
    /**
     * Compare-and-set: matches only items that are still OPEN, and says how many it changed.
     * If that's fewer than asked for, someone else got there first.
     */
    @Modifying
    @Query("""
            update ReconItem i
            set i.status = com.example.recon.item.ItemStatus.MATCHED, i.matchId = :matchId
            where i.id in :ids and i.status = com.example.recon.item.ItemStatus.OPEN
            """)
    int markMatched(Collection<Long> ids, Long matchId);

    /** Matches the items, or throws (rolling the whole transaction back) if any were already matched. */
    default void matchAll(Collection<Long> ids, Long matchId) {
        int changed = markMatched(ids, matchId);
        if (changed != ids.size()) {
            throw new ItemsAlreadyMatchedException(
                    "Only %d of items %s were still open; someone else matched the rest".formatted(changed, ids));
        }
    }
```

A **`default` method** on a Spring Data repository interface is ordinary Java: it can call the generated query methods, so the "update and check the count" rule lives next to the query it depends on, and no caller can forget the check.

This `@Modifying` has no `clearAutomatically`. Nothing in these transactions reads an item's status again after marking it, and leaving the persistence context alone means the loaded `ReconItem` objects stay attached. They're not dirty (nothing calls a setter on them), so Hibernate won't write their stale state back.

Create `item/ItemsAlreadyMatchedException.java` (a `RuntimeException` with a `String message` constructor), map it in `ApiExceptionHandler` to a `409` titled `"Items already matched"`, and **delete `matchTo` from `ReconItem`**, so nothing can match an item the unsafe way. Then fix the three places that called it.

In `ReconciliationService.run`, the loop becomes (you no longer need the `byId` map or its imports):

```java
        for (ProposedMatch proposed : result.matches()) {
            ReconMatch match = matches.save(new ReconMatch(account.getCode(), proposed.rule(),
                    proposed.difference(), run.getId(), SYSTEM_USER));
            items.matchAll(Stream.concat(proposed.internalIds().stream(), proposed.externalIds().stream()).toList(),
                    match.getId());
        }
```

In `ResolutionService.approve`:

```java
        items.matchAll(resolution.getItemIds(), match.getId());
```

and at the end of `writeOffToSuspense`:

```java
        items.matchAll(List.of(items.findByPostingId(onAccount.getId()).orElseThrow().getId()), match.getId());
```

Run `ConcurrentRunTest`:

```
failures=[ItemsAlreadyMatchedException, ItemsAlreadyMatchedException, ItemsAlreadyMatchedException] matches=20 runRows=1 matchedItems=40
```

```
Expecting empty but was: [com.example.recon.item.ItemsAlreadyMatchedException: Only 0 of items [1, 2] were still open; someone else matched the rest
```

(Your item ids may differ.) Look at what changed:

- ✅ **The data is right.** Twenty matches, one run record. The three losing runs tried to match items another run had already matched, got a row count of 0, threw, and rolled back **everything** they'd done, run record included.
- ❌ **Three runs failed.** Nobody did anything wrong: an operator clicked "Run now" while the scheduler was running. They shouldn't see an error for that.

### 3. Fix 2: runs take turns, one per date

Now make runs for the same account wait for each other, and make a re-run update the existing run rather than create another.

First the schema. You want a unique constraint on `(account_code, business_date)`. But if you've been following along, your `data/` database probably already has two runs for 6 November from guide 03, and a constraint can't be added to a table that already breaks it. If V5 simply added the constraint, starting the application would fail with:

```
Migration of schema "PUBLIC" to version "5 - one run per account and date" failed! Please restore backups and roll back database and code!
Message    : Unique index or primary key violation: "PUBLIC.UK_RECON_RUN_ACCOUNT_DATE_INDEX_E ON PUBLIC.RECON_RUN(ACCOUNT_CODE NULLS FIRST, BUSINESS_DATE NULLS FIRST) VALUES ( /* key:1 */ 'NOSTRO_GBP', DATE '2026-11-06')"; SQL statement:
```

In development you could delete `data/`. A production database can't be deleted, so the migration has to **tidy the existing data first**. Create `src/main/resources/db/migration/V5__one_run_per_account_and_date.sql`:

```sql
-- A business date is reconciled once per account; re-running it updates the same row.
ALTER TABLE recon_run ADD COLUMN attempts INT DEFAULT 1 NOT NULL;
ALTER TABLE recon_run ADD COLUMN last_run_at TIMESTAMP WITH TIME ZONE;

-- Tidy up duplicate runs made before this rule existed: keep the first run for each
-- account and date, count the others as attempts, and move their matches onto it.
UPDATE recon_run SET attempts = (
    SELECT COUNT(*) FROM recon_run r2
    WHERE r2.account_code = recon_run.account_code AND r2.business_date = recon_run.business_date);

UPDATE recon_match SET run_id = (
    SELECT MIN(r2.id) FROM recon_run r1
    JOIN recon_run r2 ON r2.account_code = r1.account_code AND r2.business_date = r1.business_date
    WHERE r1.id = recon_match.run_id)
WHERE run_id IS NOT NULL;

DELETE FROM recon_run
WHERE id NOT IN (SELECT MIN(id) FROM recon_run GROUP BY account_code, business_date);

ALTER TABLE recon_run ADD CONSTRAINT uk_recon_run_account_date UNIQUE (account_code, business_date);
```

Reading it in order:

1. Add the new columns, with a default so existing rows are valid.
2. Set each run's `attempts` to the number of runs for its account and date.
3. Point every match at the **first** run (lowest id) for its account and date, so no match loses its run.
4. Delete the other runs, now that nothing refers to them.
5. Only then add the constraint.

Every statement is plain, portable SQL (correlated subqueries, `GROUP BY`, `NOT IN`), so the same migration runs on PostgreSQL. **Data migrations like this are where Flyway earns its keep**: the fix is versioned, reviewed and applied identically in every environment.

Now the entity. In `ReconRun`, add the two fields (with getters `getAttempts()` and `getLastRunAt()`), set them in the constructor, and add `rerun()`:

```java
    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    ...

    public ReconRun(String accountCode, LocalDate businessDate) {
        this.accountCode = accountCode;
        this.businessDate = businessDate;
        this.startedAt = Instant.now();
        this.lastRunAt = startedAt;
        this.attempts = 1;
    }

    /** Running a date again is normal: a late file arrived, or a break was fixed. */
    public void rerun() {
        this.attempts++;
        this.lastRunAt = Instant.now();
    }
```

Add a finder to `ReconRunRepository` and a count to `ReconMatchRepository`:

```java
    Optional<ReconRun> findByAccountCodeAndBusinessDate(String accountCode, LocalDate businessDate);
```

```java
    int countByRunId(Long runId);
```

And the lock, in `AccountRepository` (importing `jakarta.persistence.LockModeType`, `Lock` and `Query`):

```java
    /** SELECT ... FOR UPDATE: anyone else reconciling this account waits until we commit. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.code = :code")
    Optional<Account> findByCodeForUpdate(String code);
```

Now the top and bottom of `ReconciliationService.run`:

```java
    /** Matches everything still open on or before the business date. */
    @Transactional
    public ReconRun run(String accountCode, LocalDate businessDate) {
        // Lock first, then read: a second run for this account waits here, then sees our matches
        Account account = accounts.findByCodeForUpdate(accountCode)
                .filter(Account::isReconciled)
                .orElseThrow(() -> new UnknownAccountException("No reconciled account " + accountCode));
        ReconRun run = runs.findByAccountCodeAndBusinessDate(account.getCode(), businessDate)
                .map(existing -> {
                    existing.rerun();
                    return existing;
                })
                .orElseGet(() -> runs.save(new ReconRun(account.getCode(), businessDate)));

        List<ReconItem> internal = items.findOpen(account.getCode(), Side.INTERNAL, businessDate);
        List<ReconItem> external = items.findOpen(account.getCode(), Side.EXTERNAL, businessDate);
        MatchResult result = engine.match(candidates(internal), candidates(external));

        for (ProposedMatch proposed : result.matches()) {
            ReconMatch match = matches.save(new ReconMatch(account.getCode(), proposed.rule(),
                    proposed.difference(), run.getId(), SYSTEM_USER));
            items.matchAll(Stream.concat(proposed.internalIds().stream(), proposed.externalIds().stream()).toList(),
                    match.getId());
        }
        // Totals for the date across every attempt, so a re-run reports the same picture
        run.finished(matches.countByRunId(run.getId()),
                result.unmatchedInternal().size(), result.unmatchedExternal().size());
        return run;
    }
```

- **`findByCodeForUpdate` comes first.** Everything after it, including finding the run and reading the open items, happens while this transaction holds the account's row lock. A second run for the same account blocks on that line until the first commits.
- **Find or create the run.** Because creation also happens under the lock, two runs can't both decide the run doesn't exist yet. The unique constraint is the backstop if anything ever tries.
- **`matched` is now the total for the date**, across every attempt (`countByRunId`), so a re-run that finds nothing new still reports what the date's reconciliation achieved. The open counts are always current.

In `ReconciliationController`, add `attempts` to `RunResponse` (after `businessDate`, passing `run.getAttempts()`), and return `200 OK` for a re-run, since nothing new was created:

```java
    @PostMapping
    public ResponseEntity<RunResponse> run(@Valid @RequestBody RunRequest request) {
        ReconRun run = reconciliation.run(request.accountCode(), request.businessDate());
        if (run.getAttempts() > 1) {
            return ResponseEntity.ok(response(run)); // a re-run updates the run that already exists
        }
        return ResponseEntity.created(URI.create("/reconciliations/" + run.getId())).body(response(run));
    }
```

Run `ConcurrentRunTest`:

```
failures=[] matches=20 runRows=1 matchedItems=40
```

Green. Run it several times. The four runs now queue on the account lock: the first matches everything, and the other three wait, then find no open items, add an attempt each, and commit.

> **Sidebar: H2 vs PostgreSQL.** Both support `SELECT … FOR UPDATE`, and both make a waiting `UPDATE` re-check its `WHERE` clause after the other transaction commits, so everything in this guide behaves the same way. One PostgreSQL detail is worth knowing: inserting a row with a foreign key to a locked row takes a lighter `FOR KEY SHARE` lock on it, which conflicts with `FOR UPDATE`. So while a run holds the nostro's lock, a ledger posting to the nostro **waits** too. That's usually what you want during a run (the books don't change underneath it), but it's why runs should be short.

### 4. Approvals take the same lock

Runs and manual approvals both match items on the same account. With Fix 1 they can't both match the same item, but one of them would fail with a `409`. Make them take turns too. In `ResolutionService`, inject `AccountRepository` (add it to the constructor), and lock the account in `approve` after the friendly checks, before claiming the resolution:

```java
    @Transactional
    public Resolution approve(Long id, String checker) {
        Resolution pending = get(id);
        pending.checkCanBeDecidedBy(checker); // friendly errors: wrong person, already decided
        lockAccount(pending.getAccountCode()); // same lock as a reconciliation run, taken first
        if (resolutions.markApproved(id, checker, Instant.now()) == 0) {
            throw new ResolutionConflictException("Resolution %d has already been decided".formatted(id));
        }
        Resolution resolution = get(id); // reload: markApproved cleared the persistence context
        ...
```

```java
    private void lockAccount(String code) {
        accounts.findByCodeForUpdate(code).orElseThrow();
    }
```

Every code path that changes items on an account now takes **the account lock first**, and then other rows. One consistent lock order means runs and approvals can never deadlock each other.

### 5. Re-run tests

In `ReconciliationApiTest`, replace `runningAgainFindsNothingNewToMatch` with two tests that describe the new behaviour:

```java
    @Test
    void runningTheSameDateAgainUpdatesTheSameRun() {
        MvcTestResult first = run(nostro, "2026-11-06");

        MvcTestResult again = run(nostro, "2026-11-06");

        assertThat(again).hasStatus(HttpStatus.OK);
        assertThat(again).bodyJson().extractingPath("$.id").isEqualTo(idOf(first));
        assertThat(again).bodyJson().extractingPath("$.attempts").isEqualTo(2);
        assertThat(again).bodyJson().extractingPath("$.matched").isEqualTo(3);
        assertThat(again).bodyJson().extractingPath("$.openInternal").isEqualTo(1);
        assertThat(items.findAll().stream()
                .filter(i -> i.getAccountCode().equals(nostro) && i.getStatus() == ItemStatus.MATCHED))
                .hasSize(6);
    }

    @Test
    void aReRunPicksUpAnItemThatArrivedLate() {
        run(nostro, "2026-11-06");
        external("PAY-7740", "-300.00", NOV_6); // a corrected statement line, imported late

        MvcTestResult again = run(nostro, "2026-11-06");

        assertThat(again).bodyJson().extractingPath("$.matched").isEqualTo(4);
        assertThat(again).bodyJson().extractingPath("$.openInternal").isEqualTo(0);
        assertThat(again).bodyJson().extractingPath("$.matches[3].rule").isEqualTo("EXACT");
    }
```

with a helper:

```java
    private static Integer idOf(MvcTestResult created) {
        return Integer.valueOf(created.getResponse().getHeader("Location").replace("/reconciliations/", ""));
    }
```

`idOf` returns an `Integer`, the type JSONPath gives small JSON numbers, so `isEqualTo` compares like with like.

`aReRunPicksUpAnItemThatArrivedLate` is the "late file" story from Concepts: the same run gains a fourth match, and the date's numbers update. Run the whole suite: green.

### 6. Try it: a timing difference clears itself

Add to `requests.http`:

```http
### Re-run 6 November: same run, attempts goes up, nothing is matched twice
POST http://localhost:8080/reconciliations
Content-Type: application/json

{"accountCode": "NOSTRO_GBP", "businessDate": "2026-11-06"}
```

Start from an empty `data/`, import the 6 November statement, post the four ledger entries from guide 03, and run the 6th. The first run returns `201 Created`. The re-run returns `200 OK`:

```json
{"id":1,"accountCode":"NOSTRO_GBP","businessDate":"2026-11-06","attempts":2,"matched":3,"openInternal":1,"openExternal":2, "matches": [ … ]}
```

Now the next morning's statement arrives. Create `samples/camt053-nostro-2026-11-07.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08">
  <BkToCstmrStmt>
    <GrpHdr><MsgId>MSG-20261108-0001</MsgId></GrpHdr>
    <Stmt>
      <Id>STMT-NOSTRO-20261107</Id>
      <Acct><Id><IBAN>GB33BUKB20201555555555</IBAN></Id><Ccy>GBP</Ccy></Acct>
      <Bal><Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy="GBP">11899.50</Amt><CdtDbtInd>CRDT</CdtDbtInd></Bal>
      <Bal><Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy="GBP">11599.50</Amt><CdtDbtInd>CRDT</CdtDbtInd></Bal>
      <Ntry>
        <NtryRef>1</NtryRef><Amt Ccy="GBP">300.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts><Cd>BOOK</Cd></Sts>
        <BookgDt><Dt>2026-11-07</Dt></BookgDt><ValDt><Dt>2026-11-07</Dt></ValDt>
        <AcctSvcrRef>CB-20261107-0001</AcctSvcrRef>
        <NtryDtls><TxDtls><Refs><EndToEndId>PAY-7740</EndToEndId></Refs><RmtInf><Ustrd>Supplier payment 7740</Ustrd></RmtInf></TxDtls></NtryDtls>
      </Ntry>
    </Stmt>
  </BkToCstmrStmt>
</Document>
```

Its opening balance is yesterday's closing balance, as it must be. Import it, then reconcile the 7th (`{"accountCode": "NOSTRO_GBP", "businessDate": "2026-11-07"}`). It's a new run (`201`, `"id": 2`, `"attempts": 1`) with one match:

```
"matched": 1, "openInternal": 0, "openExternal": 2
DATE_WINDOW: PAY-7740 booked 2026-11-06 (ours) with PAY-7740 booked 2026-11-07 (theirs)
```

The £300 the bank sent late on the 6th was yesterday's break; the correspondent booked it on the 7th, and the run for the 7th matched it to the still-open ledger item. That's a timing difference clearing itself, exactly as guide 01 described. `GET /breaks?account=NOSTRO_GBP&asOf=2026-11-07` now shows two breaks, the fee and the J SMITH credit (unless you parked it in suspense in guide 04), each **1 day** old.

---

## Your turn

### Exercise 1: why lock first?

In `ReconciliationService.run`, move the two `items.findOpen(...)` calls **above** `findByCodeForUpdate` (keep everything else the same), and run `ConcurrentRunTest`. What happens, and why? Put them back afterwards.

<details>
<summary>Answer</summary>

```
failures=[ItemsAlreadyMatchedException, ItemsAlreadyMatchedException, ItemsAlreadyMatchedException] matches=20 runRows=1 matchedItems=40
```

The runs still take turns on the lock, but each one read its list of open items **before** queuing for it, while all forty items were still open. When the second run finally gets the lock, it's working from that stale list: it tries to match items the first run has already matched, `markMatched` changes 0 rows, and the run fails. The data is still correct, thanks to the conditional update, but you're back to failed runs.

A lock protects only what you read **after** taking it. The rule is the same as the Core ledger tutorial's "lock, then check": take the lock, then read the state, then decide.

</details>

### Exercise 2: a run and an approval at once

A checker approves a manual match while the nightly run is matching the same two items. Write a test that starts both at the same moment and proves the items end up in **exactly one** match, whichever wins. The loser may fail with `ResolutionConflictException` or `ItemsAlreadyMatchedException`; any other exception is a bug. Repeat it a few times with JUnit's `@RepeatedTest(5)`, because the winner varies.

<details>
<summary>Solution</summary>

`src/test/java/com/example/recon/run/RunVersusApprovalTest.java`:

```java
package com.example.recon.run;

import com.example.recon.ReconIntegrationTest;
import com.example.recon.TestAccounts;
import com.example.recon.account.AccountRepository;
import com.example.recon.item.ItemsAlreadyMatchedException;
import com.example.recon.item.ReconItem;
import com.example.recon.item.ReconItemRepository;
import com.example.recon.money.Money;
import com.example.recon.resolution.ResolutionConflictException;
import com.example.recon.resolution.ResolutionService;
import com.example.recon.resolution.ResolutionType;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** A checker approves a manual match while the nightly run is matching the same items. */
@ReconIntegrationTest
class RunVersusApprovalTest {

    @Autowired AccountRepository accounts;
    @Autowired ReconItemRepository items;
    @Autowired ResolutionService resolutions;
    @Autowired ReconciliationService reconciliation;

    @RepeatedTest(5)
    void anItemEndsUpInExactlyOneMatch() throws Exception {
        String nostro = TestAccounts.newNostro(accounts).getCode();
        LocalDate nov6 = LocalDate.of(2026, 11, 6);
        Long a = items.save(ReconItem.internal(nostro, null, "INV-1", Money.gbp("10.00"), nov6, "Ours")).getId();
        Long b = items.save(ReconItem.external(nostro, null, "INV-1", Money.gbp("10.00"), nov6, nov6, "Theirs")).getId();
        Long resolution = resolutions.request("alice", ResolutionType.MANUAL_MATCH, Set.of(a, b), "Same payment")
                .getId();

        CountDownLatch startingGun = new CountDownLatch(1);
        List<Callable<Object>> tasks = List.of(
                () -> reconciliation.run(nostro, nov6),
                () -> resolutions.approve(resolution, "bob"));
        List<Throwable> unexpected = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    try {
                        task.call();
                    } catch (ResolutionConflictException | ItemsAlreadyMatchedException e) {
                        // the loser is told; that's the correct outcome
                    } catch (Exception e) {
                        synchronized (unexpected) {
                            unexpected.add(e);
                        }
                    }
                    return null;
                }));
            }
            startingGun.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        ReconItem ours = items.findById(a).orElseThrow();
        ReconItem theirs = items.findById(b).orElseThrow();
        assertThat(unexpected).isEmpty();
        assertThat(ours.getMatchId()).isNotNull().isEqualTo(theirs.getMatchId());
    }
}
```

- **`List<Callable<Object>>`** holds two different jobs; each thread calls one. A multi-catch (`catch (A | B e)`) accepts either "you lost" exception.
- If the run wins, the approval waits for the account lock, claims the resolution, then `openItems` finds the items already matched and throws `ResolutionConflictException`. The whole approval transaction rolls back, claim included, so the resolution is left `PENDING`, and a person can reject it as no longer needed.
- If the approval wins, the run waits, then finds no open items for this account.
- Either way, the assertion that matters holds: both items point at the **same** single match.
- `@RepeatedTest(5)` counts as five tests in the totals.

</details>

---

## Checkpoint

```bash
./mvnw clean test
```

```
[INFO] Tests run: 60, Failures: 0, Errors: 0, Skipped: 0
```

(53 from guide 04, plus 1 more in `ReconciliationApiTest`, 1 in `ConcurrentRunTest` and 5 repetitions in `RunVersusApprovalTest`.) Run it two or three times: every concurrency test must pass every time.

New files since guide 04:

```
samples/camt053-nostro-2026-11-07.xml
src/main/java/com/example/recon/item/ItemsAlreadyMatchedException.java
src/main/resources/db/migration/V5__one_run_per_account_and_date.sql
src/test/java/com/example/recon/run/
├── ConcurrentRunTest.java
└── RunVersusApprovalTest.java
```

Modified: `AccountRepository`, `ReconItem` (`matchTo` removed), `ReconItemRepository`, `ReconRun`, `ReconRunRepository`, `ReconMatchRepository`, `ReconciliationService`, `ReconciliationController`, `ResolutionService`, `ApiExceptionHandler`, `ReconciliationApiTest`, `requests.http`.

---

## Common errors

**`failures=[] matches=80 runRows=4`**
Nothing stops two runs working on the same items. Do both fixes: the conditional `matchAll`, and the account lock at the very start of `run`.

**`failures=[ItemsAlreadyMatchedException, …] matches=20 runRows=1`**
The data is safe, but runs aren't taking turns. Either the lock is missing, or the open items are read **before** it's taken (Exercise 1).

**`Migration of schema "PUBLIC" to version "5 - one run per account and date" failed! … Unique index or primary key violation: "PUBLIC.UK_RECON_RUN_ACCOUNT_DATE_INDEX_E …"`**
Your database already had duplicate runs, and V5 added the constraint without tidying them first. Use the full V5 from step 3. If a failed V5 has already been recorded by Flyway (and on H2, where each `ALTER TABLE` commits as it runs, its new columns may already exist), the simplest fix in development is to delete `data/`.

**A new run returns `409` with `"title": "Items already matched"`**
Some code path still matches items without taking the account lock first. Every path that changes items on an account must start with `findByCodeForUpdate`.

**`ConcurrentRunTest` hangs, or fails with `Timeout trying to lock table`**
A transaction is holding the account lock for a long time: often a breakpoint you've paused at inside `run` while other threads wait. Resume the debugger. Locks are held until the transaction ends, so keep work inside a locked transaction short.

**`RunVersusApprovalTest` passes sometimes and fails sometimes**
That's what a race looks like. Check that `approve` takes the account lock **before** `openItems`, and that both services use `matchAll`, never an entity setter.

---

## Recap

- A reconciliation run must be **re-runnable**: late and corrected files, failed runs and double triggers are normal. Make it **idempotent**: one logical run per account and business date, with **attempts** counted, and matches never duplicated.
- **Concurrent runs double-match** because each reads the open items before the other commits. Item statuses can look perfect while the match history is wrong: test the audit trail, not just the totals.
- **Conditional update** (`UPDATE … WHERE status = 'OPEN'`, check the row count) guarantees no item is matched twice. Losers fail safely and roll back.
- **A pessimistic lock on the account** makes runs and approvals for that account take turns, so nobody needs to fail. Different accounts still run in parallel.
- **Lock first, then read.** A lock only protects what you read after taking it.
- **One lock order everywhere**: the account first, then everything else.
- Adding a constraint to existing data needs a **data-tidying migration**: fix the rows, then add the rule, all in versioned, portable SQL.

### Interview angle

<details>
<summary>"How do you make a batch job safe to re-run?"</summary>

Give each logical run a natural key (here, account plus business date) with a unique constraint, and find-or-create it rather than always inserting, recording attempts and timestamps. Make each unit of work conditional on state, so redoing it is a no-op: only process items still in the "to do" state, and change their state with a conditional update that reports how many rows it changed. Make each run transactional (or checkpointed per chunk) so a failure part-way leaves nothing half-done. Then a re-run picks up exactly what's left, whether it's caused by a late file, a failure or a duplicate trigger. Mention that Spring Batch provides restartability and job instance identity out of the box for larger jobs.

</details>

<details>
<summary>"Two copies of the same job start at once. How do you stop them doing the work twice?"</summary>

Serialise them on a shared resource, for example a `SELECT … FOR UPDATE` on the row representing the account or the job, taken at the start of the transaction before anything is read; the second waits, then sees the first's results. Back it up with a conditional update on each item (`WHERE status = 'OPEN'`) and a unique constraint on the run's key, so even a code path that forgets the lock can't corrupt data. In a multi-node deployment, the database lock works across instances; alternatives are a distributed lock or a scheduler that guarantees a single runner (ShedLock is a common library for this).

</details>

<details>
<summary>"You need to add a unique constraint to a production table that already has duplicates. What do you do?"</summary>

Write a migration that first resolves the duplicates according to an agreed business rule (here, keep the earliest run, count the others as attempts, and re-point their matches to it), then deletes the extras, and only then adds the constraint, all in one versioned migration that's tested against a copy of production data. Check foreign keys before deleting. On large tables, think about locking and duration: some teams add the constraint in a separate step, or use database-specific online index builds.

</details>

### Further reading

- [Spring Data JPA reference: Locking](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)
- [PostgreSQL docs: Explicit Locking](https://www.postgresql.org/docs/current/explicit-locking.html), including how row-level lock modes conflict
- [Flyway documentation](https://documentation.red-gate.com/flyway): migrations, including data migrations
- [Spring Batch reference](https://docs.spring.io/spring-batch/reference/): job instances, restartability and the job repository (the Loans tutorial uses it)

---

## You've built a reconciliation system

Across five guides you've built the control that tells a bank whether its books agree with the outside world:

| Guide | Banking idea | Java/Spring skill |
|---|---|---|
| 01 | Two records of the same money; nostro and vostro; camt.053 statements | StAX streaming, XXE prevention, multipart uploads, all-or-nothing imports |
| 02 | Scheme settlement reports; partial files and error reports | Commons CSV, per-line validation, SHA-256 duplicate detection, unique constraints under concurrency |
| 03 | Matching rules, tolerances, timing differences | A pure matching engine, `@ConfigurationProperties` records, load → match → persist |
| 04 | Breaks, ageing, suspense, maker-checker | Pure report builders, a `Clock` bean, compare-and-set with `@Modifying` queries |
| 05 | Re-runnable end-of-day controls | Idempotent runs, pessimistic locks, lock ordering, data-tidying migrations |

**Where to go next:**

- **The Payments tutorial** builds the system whose settlement this one checks: payments sent to a scheme, the transactional outbox, and settlement cycles.
- **The Core ledger tutorial** builds a full double-entry ledger, where this tutorial's tiny one leaves off.
- **Extend this system yourself:** clear suspense to a customer account with maker-checker; reconcile **balances** as well as transactions (statement closing balance against ledger balance plus open items); add per-account tolerances stored in the database, with their own approval flow; schedule the daily run with `@Scheduled` for each reconciled account; or switch to PostgreSQL with Docker Compose and check every test still passes.
