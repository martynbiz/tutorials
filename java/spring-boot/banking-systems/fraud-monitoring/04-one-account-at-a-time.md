# 04: One Account at a Time

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- How to run several Kafka consumer threads with **listener concurrency**, and what a **rebalance** looks like
- Why partition ordering protects an account's state only **if every producer and every code path plays along**
- **Write skew**: how concurrent processing makes the velocity rule silently miss, seen in a failing test
- How to serialise work per account with a **lock row** and `SELECT … FOR UPDATE`
- The **"you can't lock a row that doesn't exist"** problem, and the create-if-absent-then-lock pattern
- Why the duplicate check has to come **after** the lock, and how a primary key backs it up
- Programmatic transactions with **`TransactionTemplate`**

This guide's concurrency test fails twice, in two different ways, before it passes. By the end, ten payments for the same account processed at the same instant still trip the velocity rule exactly as they would one at a time.

---

## Concepts

### How it works in a real bank

Fraud rules are **stateful**. The velocity rule's answer for payment number six depends on payments one to five being in the history. So the monitoring system must process each account's payments **one at a time**, in order, with each one seeing the results of the last.

Guide 01 got that for free: events are keyed by account, an account's events all go to one partition, and a partition is read by one consumer thread at a time. That's how most event-driven banking systems serialise work per account, and it's the right first line of defence.

But it rests on assumptions that are easy to break:

- **Every producer keys by account.** Suppose the card system keys its events by card token and the payments engine keys by account id. Alice's card payments and her Faster Payments now land on **different partitions**, read by **different threads**, possibly in different instances of your service. Nothing in Kafka will tell you.
- **Only the listener processes events.** A "reprocess this account" admin endpoint, a nightly backfill job or a replay of the dead-letter topic all call the same service, on other threads, while the listener keeps going.
- **A partition really has one consumer at a time.** During a **rebalance** (an instance starts, stops or is too slow to poll), partitions are taken from one consumer and given to another. A consumer that has just lost a partition may still be in the middle of a record from it when the new owner starts. That's also how a duplicate can be processed **at the same moment** as the original.

When any of those happens, two transactions for the same account run at once. The relational-database answer is the same one the Core ledger tutorial uses for balances: **serialise per account with a row lock**. Keep the partitioning (it keeps locks uncontended almost all the time), and add the lock so correctness doesn't depend on it.

### Write skew: how the velocity rule goes blind

The velocity limit is "more than 5 payments in an hour". Alice has already made 4 payments this hour. Two more arrive at the same moment, each processed in its own transaction. Whichever is processed second is her sixth payment and should raise an alert.

| Time | Transaction A (payment 5) | Transaction B (payment 6) |
|---|---|---|
| 1 | Read history → 4 payments | |
| 2 | | Read history → 4 payments (A hasn't committed) |
| 3 | 4 + 1 = 5, not more than 5 → no alert | 4 + 1 = 5, not more than 5 → no alert |
| 4 | Insert payment, commit | |
| 5 | | Insert payment, commit |

Afterwards the history holds six payments in the hour, and there's no alert. Scale it up to **ten** payments arriving at once for an account with no history: every transaction reads the history before any of the others has committed, each sees nothing, and not one alert is raised.

Nothing was overwritten here, so it isn't the Core ledger's lost update. Each transaction made a decision based on data that another transaction was changing, and both decisions were individually reasonable. That's called **write skew**. `@Transactional` doesn't prevent it: under **READ COMMITTED** (the default isolation level on H2, PostgreSQL and Oracle) a transaction only ever sees data that others have committed.

### Locking something that might not exist

The fix is to lock something per account before reading its history. There's no natural "account" row here (the fraud system doesn't own accounts), so you'll add one: a `monitored_account` table with one row per account, whose main job is to be locked.

That creates a new problem. The first time an account appears, there's no row to lock. `SELECT … FOR UPDATE` on a row that doesn't exist locks nothing, so two threads both find nothing and both try to insert it. One wins; the other hits the primary key. The pattern that handles this is:

1. **Create the row if it's missing, in its own short transaction**, and treat "someone else just created it" as success.
2. **Then**, in the main transaction, **lock** it.

---

## Steps

### 1. More than one consumer thread

Add this to `application.properties`:

```properties
# One consumer thread per partition
spring.kafka.listener.concurrency=3
```

Spring now runs three consumers for `@KafkaListener` in the same group, one per partition, each on its own thread. It's the in-process equivalent of running three instances of the service. Restart the app (or run the tests) and watch the log as they join:

```
[ntainer#0-0-C-1] fraud-monitoring: partitions assigned: [transactions-0, transactions-1]
[ntainer#0-1-C-1] fraud-monitoring: partitions assigned: [transactions-2]
[ntainer#0-0-C-1] fraud-monitoring: partitions assigned: [transactions-0]
[ntainer#0-2-C-1] fraud-monitoring: partitions assigned: [transactions-2]
```

That's a **rebalance** happening in front of you: the first consumer is given two partitions, then as the others join, the group shares them out again. The thread names (`#0-0`, `#0-1`, `#0-2`) are the three consumers. With keyed events this is still safe, because an account's events stay on one partition and therefore one thread. More consumers than partitions would leave the extras idle, so partition count sets your maximum parallelism.

Run the suite. Everything still passes. Partitioning is doing its job.

### 2. A test that breaks the assumption

Now simulate what happens when two consumers *do* process the same account at once. Like the Core ledger's concurrency tests, this calls the service directly from many threads, released together by a `CountDownLatch`.

`src/test/java/com/example/fraud/transaction/ConcurrentProcessingTest.java`:

```java
package com.example.fraud.transaction;

import com.example.fraud.FraudIntegrationTest;
import com.example.fraud.alert.Alert;
import com.example.fraud.alert.AlertRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.example.fraud.TestTransactions.payment;
import static org.assertj.core.api.Assertions.assertThat;

/** Two consumers working on the same account at the same moment. */
@FraudIntegrationTest
class ConcurrentProcessingTest {

    @Autowired TransactionMonitoringService monitoring;
    @Autowired AlertRepository alerts;

    @Test
    void tenSimultaneousPaymentsStillTripTheVelocityRule() throws Exception {
        String account = "acc-" + UUID.randomUUID();
        List<TransactionEvent> burst = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            burst.add(payment("20.00").account(account).build()); // all at the same instant
        }

        List<Throwable> unexpected = runAllAtOnce(burst);

        List<String> ruleCodes = alerts.findByAccountIdOrderByIdAsc(account).stream()
                .map(Alert::getRuleCode).toList();
        System.out.printf("history=%d alerts=%s unexpected=%s%n", monitoring.history(account).size(),
                ruleCodes, unexpected.stream().map(t -> t.getClass().getSimpleName()).toList());

        assertThat(unexpected).isEmpty();
        assertThat(monitoring.history(account)).hasSize(10);
        assertThat(ruleCodes).containsOnly("VELOCITY").hasSize(5); // payments 6 to 10
    }

    @Test
    void theSameEventDeliveredToTwoConsumersAtOnceIsProcessedOnce() throws Exception {
        String account = "acc-" + UUID.randomUUID();
        TransactionEvent event = payment("750.00").account(account).build();

        List<Throwable> unexpected = runAllAtOnce(List.of(event, event, event, event, event));

        System.out.printf("history=%d alerts=%d unexpected=%s%n", monitoring.history(account).size(),
                alerts.findByAccountIdOrderByIdAsc(account).size(),
                unexpected.stream().map(t -> t.getClass().getSimpleName()).toList());

        assertThat(unexpected).isEmpty();
        assertThat(monitoring.history(account)).hasSize(1);
        assertThat(alerts.findByAccountIdOrderByIdAsc(account)).hasSize(1);
    }

    private List<Throwable> runAllAtOnce(List<TransactionEvent> events) throws Exception {
        CountDownLatch startingGun = new CountDownLatch(1);
        List<Throwable> unexpected = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(events.size())) {
            List<Future<?>> futures = new ArrayList<>();
            for (TransactionEvent event : events) {
                futures.add(pool.submit(() -> {
                    startingGun.await(); // every thread waits here...
                    try {
                        monitoring.process(event);
                    } catch (RuntimeException e) {
                        synchronized (unexpected) {
                            unexpected.add(e);
                        }
                    }
                    return null;
                }));
            }
            startingGun.countDown(); // ...then they all go at once
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
        return unexpected;
    }
}
```

What the two tests claim:

- **Velocity under concurrency.** Ten £20 card payments, all with the same `occurredAt` (`TestTransactions.NOON`). Processed one at a time, payment *k* sees *k − 1* earlier payments in the window, so payments 6 to 10 each break the "more than 5 in an hour" limit: **exactly five `VELOCITY` alerts**, whatever order they're processed in. Giving them all the same timestamp is what makes the right answer independent of order. (A burst like this, many small payments within seconds, is what card testing looks like.)
- **Duplicates under concurrency.** The same event five times at once, as after a rebalance. Exactly one history row, one alert, and **no exceptions**: a duplicate is a normal event in a Kafka system, not an error.

The mechanics are the same as any concurrency test in this series:

- **`CountDownLatch startingGun`** lines every thread up on `await()` and releases them together, so they genuinely overlap.
- **The test class is not `@Transactional`.** Each `process` call commits for real on its own thread.
- **`future.get(30, SECONDS)`** turns a hang into a failure.
- **The `printf`s** let you see what happened, not just that an assertion failed.

### 3. Watch it fail

Run `ConcurrentProcessingTest`. Both tests fail. The printed lines (yours will vary from run to run):

```
history=1 alerts=1 unexpected=[DataIntegrityViolationException, DataIntegrityViolationException, DataIntegrityViolationException, DataIntegrityViolationException]
history=10 alerts=[VELOCITY] unexpected=[]
```

And on another run:

```
history=10 alerts=[] unexpected=[]
```

**The velocity test.** Ten payments in the history, all at the same instant, and one alert or none, where there should be five:

```
Expected size: 5 but was: 1 in:
["VELOCITY"]
```

That's write skew. Every thread read the history before the others committed. How many alerts you get depends on exactly how the threads interleave: one run gave one, the next gave none. That's what makes this kind of bug so dangerous in production. Nothing errors, nothing is logged, and the control quietly doesn't work.

**The duplicate test.** Here the data is actually right (one history row, one alert), thanks to guide 03's primary key, but four of the five deliveries blew up:

```
Expecting empty but was: [org.springframework.dao.DataIntegrityViolationException: could not execute statement [Unique index or primary key violation: "PUBLIC.CONSTRAINT_26 INDEX PUBLIC.PRIMARY_KEY_26 ON PUBLIC.PROCESSED_EVENT(EVENT_ID) VALUES ( /* 1 */ UUID '5d1b850a-ba3c-448f-99f1-55617d1412cf' )"; SQL statement:
insert into processed_event (processed_at,event_id) values (?,?) [23505-240]] ...
```

All five threads ran `existsById`, all saw "not processed", all evaluated the rules and inserted history and alerts, and then four of them failed on the `processed_event` insert and rolled the lot back. (This is the backstop working, and it only works because `ProcessedEvent` implements `Persistable`, so `save()` really does an `INSERT`.) In the Kafka listener, `DefaultErrorHandler` would retry those four, and the retries would skip as duplicates. So nothing is wrong, but the logs are full of errors for something perfectly normal, and the alerts from four rolled-back transactions came close to being published. Better not to get there at all.

### 4. A row to lock

`src/main/resources/db/migration/V4__create_monitored_account.sql`:

```sql
CREATE TABLE monitored_account (
    account_id    VARCHAR(40)              PRIMARY KEY,
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL
);
```

`transaction/MonitoredAccount.java`:

```java
package com.example.fraud.transaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * One row per account the fraud system has seen. Its main job is to be
 * locked: while one transaction holds it, nobody else can evaluate rules
 * for that account.
 */
@Entity
@Table(name = "monitored_account")
public class MonitoredAccount implements Persistable<String> {

    @Id
    @Column(name = "account_id", length = 40)
    private String accountId;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    protected MonitoredAccount() {
        // for JPA
    }

    public MonitoredAccount(String accountId, Instant firstSeenAt) {
        this.accountId = accountId;
        this.firstSeenAt = firstSeenAt;
    }

    @Override
    public String getId() {
        return accountId;
    }

    /** We only ever save() brand-new rows, so save() must always INSERT. */
    @Override
    public boolean isNew() {
        return true;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }
}
```

`Persistable` again, for the same reason as `ProcessedEvent`: the id is assigned, and a concurrent second insert must fail on the primary key rather than turn into a silent `merge`.

It's a natural place for per-account data later (a risk rating from onboarding, a "known fraud victim" flag), but today it only needs to exist.

`transaction/MonitoredAccountRepository.java`:

```java
package com.example.fraud.transaction;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface MonitoredAccountRepository extends JpaRepository<MonitoredAccount, String> {

    /** SELECT ... FOR UPDATE: anyone else wanting this row waits until we commit. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from MonitoredAccount a where a.accountId = :accountId")
    Optional<MonitoredAccount> findByIdForUpdate(String accountId);
}
```

`@Lock(PESSIMISTIC_WRITE)` makes Hibernate add `FOR UPDATE` to the query. Any other transaction that asks for the same row with `FOR UPDATE` **waits** until the first one commits or rolls back.

### 5. First attempt: lock it, or create it

The obvious version, at the top of `process` in `TransactionMonitoringService` (inject `MonitoredAccountRepository accounts` through the constructor first):

```java
    @Transactional
    public List<Alert> process(TransactionEvent event) {
        // One at a time per account: lock first, then check and evaluate
        accounts.findByIdForUpdate(event.accountId())
                .orElseGet(() -> accounts.saveAndFlush(new MonitoredAccount(event.accountId(), Instant.now(clock))));

        if (processedEvents.existsById(event.eventId())) {
            ...
```

"Lock the account row; if there isn't one, create it." Run the test:

```
history=1 alerts=1 unexpected=[DataIntegrityViolationException, DataIntegrityViolationException, DataIntegrityViolationException, DataIntegrityViolationException]
history=1 alerts=[] unexpected=[DataIntegrityViolationException, DataIntegrityViolationException, DataIntegrityViolationException, DataIntegrityViolationException, DataIntegrityViolationException, DataIntegrityViolationException, DataIntegrityViolationException, DataIntegrityViolationException, DataIntegrityViolationException]
```

Worse: **nine** of ten payments failed, and the history has one. The error explains it:

```
Unique index or primary key violation: "PUBLIC.CONSTRAINT_A INDEX PUBLIC.PRIMARY_KEY_A ON PUBLIC.MONITORED_ACCOUNT(ACCOUNT_ID) VALUES ( /* 1 */ 'acc-6cf44f6b-ba57-4b3f-a4ed-354c06e0006e' )
insert into monitored_account (first_seen_at,account_id) values (?,?)
```

Each test uses a brand-new account, so there's no row yet. All ten threads ran `SELECT … FOR UPDATE`, found nothing (and locked nothing), and all tried to insert. One insert won; the other nine waited on the primary key index until the winner committed, then failed. **You can't lock a row that doesn't exist yet.**

In the real listener the error handler would retry those nine, and the retries would find the row. So it wouldn't lose data. But a brand-new account's first burst of payments is exactly when you most want monitoring to work smoothly.

### 6. Create if absent, then lock

Make the two jobs separate, with separate transactions. Replace `TransactionMonitoringService`:

```java
package com.example.fraud.transaction;

import com.example.fraud.alert.Alert;
import com.example.fraud.alert.AlertRepository;
import com.example.fraud.money.Money;
import com.example.fraud.rules.AccountHistory;
import com.example.fraud.rules.PastTransaction;
import com.example.fraud.rules.RuleEngine;
import com.example.fraud.rules.RuleHit;
import com.example.fraud.rules.RulesProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class TransactionMonitoringService {

    private static final Logger log = LoggerFactory.getLogger(TransactionMonitoringService.class);

    private final AccountTransactionRepository transactions;
    private final AlertRepository alerts;
    private final ProcessedEventRepository processedEvents;
    private final MonitoredAccountRepository accounts;
    private final RuleEngine ruleEngine;
    private final RulesProperties rules;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public TransactionMonitoringService(AccountTransactionRepository transactions, AlertRepository alerts,
                                        ProcessedEventRepository processedEvents,
                                        MonitoredAccountRepository accounts, RuleEngine ruleEngine,
                                        RulesProperties rules, TransactionTemplate transactionTemplate,
                                        Clock clock) {
        this.transactions = transactions;
        this.alerts = alerts;
        this.processedEvents = processedEvents;
        this.accounts = accounts;
        this.ruleEngine = ruleEngine;
        this.rules = rules;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** Checks one transaction against every rule, records it, and returns any new alerts. */
    public List<Alert> process(TransactionEvent event) {
        ensureMonitored(event.accountId());                           // its own short transaction
        return transactionTemplate.execute(status -> evaluate(event)); // the main transaction
    }

    @Transactional(readOnly = true)
    public List<AccountTransaction> history(String accountId) {
        return transactions.findByAccountIdOrderByOccurredAtDesc(accountId);
    }

    /** Makes sure there's a row to lock. Two consumers may race to create it; one wins, and that's fine. */
    private void ensureMonitored(String accountId) {
        if (accounts.existsById(accountId)) {
            return;
        }
        try {
            accounts.saveAndFlush(new MonitoredAccount(accountId, Instant.now(clock)));
        } catch (DataIntegrityViolationException e) {
            log.debug("Account {} was created by another consumer first", accountId);
        }
    }

    private List<Alert> evaluate(TransactionEvent event) {
        // Lock first, then check: one transaction at a time per account from here to commit
        accounts.findByIdForUpdate(event.accountId()).orElseThrow();

        if (processedEvents.existsById(event.eventId())) {
            log.info("Skipping {}: already processed", event.eventId());
            return List.of();
        }
        if (!event.amount().currency().equals(Money.GBP)) {
            throw new UnsupportedTransactionException("Only GBP is monitored, but %s was %s"
                    .formatted(event.eventId(), event.amount().currency()));
        }
        AccountHistory history = historyBefore(event);
        List<RuleHit> hits = ruleEngine.evaluate(event, history);

        Instant now = Instant.now(clock);
        transactions.save(AccountTransaction.record(event, now));
        processedEvents.save(new ProcessedEvent(event.eventId(), now));
        return alerts.saveAll(hits.stream().map(hit -> Alert.raise(event, hit, now)).toList());
    }

    private AccountHistory historyBefore(TransactionEvent event) {
        Instant to = event.occurredAt();
        Instant from = to.minus(rules.history());
        List<PastTransaction> past = transactions.findHistory(event.accountId(), from, to).stream()
                .map(t -> new PastTransaction(t.getAmount(), t.getPayee(), t.getOccurredAt()))
                .toList();
        return new AccountHistory(past);
    }
}
```

Read `process` as two steps:

1. **`ensureMonitored`** runs with **no surrounding transaction**. `existsById` and `saveAndFlush` are Spring Data repository methods, which are transactional on their own, so each commits immediately. If two threads race to create the row, one insert succeeds and the other throws `DataIntegrityViolationException`, which is caught and ignored: either way, **the row now exists**. Catching it is safe precisely because it happened in its own tiny transaction. Caught *inside* a larger transaction, a failed insert would leave that transaction marked rollback-only.
2. **`transactionTemplate.execute(...)`** starts the main transaction and runs `evaluate` inside it. `evaluate` begins by **locking** the account row, which now definitely exists. From that line until commit, every other transaction for this account waits at the same line.

Inside the lock the order matters too: **lock, then check for duplicates, then decide, then record**. It's the "lock, then check" rule from the Core ledger tutorial's idempotency guide.

**Why `TransactionTemplate` instead of `@Transactional`?** `process` must *not* be one big transaction, because `ensureMonitored` has to commit on its own first. You could move `evaluate` to another bean and annotate it, but `@Transactional` only works when called **through the Spring proxy**, so it can't go on a private method called from the same class. `TransactionTemplate` (Spring Boot auto-configures one) is **programmatic** transaction management: the transaction is exactly the lambda you pass in. It's explicit, and it makes the two-step structure visible in one line.

> **Sidebar: why not `REQUIRES_NEW`?** Another way to get a separate transaction for the insert is a method annotated `@Transactional(propagation = REQUIRES_NEW)`, called from inside the main transaction. It works, but the outer transaction is already holding a database connection while it waits for the inner one to get a second connection. With ten threads doing that at once and a connection pool of ten (HikariCP's default maximum), threads can end up waiting for connections that the other threads are holding. Doing the insert *before* the main transaction starts uses one connection at a time.

> **Sidebar: PostgreSQL has a shortcut.** `INSERT … ON CONFLICT DO NOTHING` creates a row only if it's missing, in one statement, without an error. H2 doesn't support that syntax in its default mode, and the migrations in this series stay portable, so the Java version above is the portable equivalent.

### 7. Watch it pass

Run `ConcurrentProcessingTest` two or three times:

```
history=1 alerts=1 unexpected=[]
history=10 alerts=[VELOCITY, VELOCITY, VELOCITY, VELOCITY, VELOCITY] unexpected=[]
```

✅ **Every time.** Ten payments, five velocity alerts, no errors. The ten transactions queued on the account row and ran one after another; each one's history included every payment committed before it.

✅ **The duplicates are quiet.** The second to fifth deliveries waited for the lock, then found the event in `processed_event` and skipped it. The primary key is still there as a backstop, but nothing hits it any more.

Run the whole suite. Everything that passed before still passes, because nothing about the single-threaded behaviour changed.

> **Sidebar: the cost.** Every event now takes a row lock. For different accounts that costs almost nothing: different rows, no waiting. For the same account, transactions queue, but Kafka partitioning already sends an account's events to one thread, so in normal running there's nobody to queue behind. The lock only bites in exactly the unusual cases it's there for. That's the design: **partition by key for throughput and ordering, lock for correctness.**

---

## Your turn

### Exercise 1: break it on purpose

Move the duplicate check in `evaluate` **above** the lock, so the order is "check, then lock". Before you run `ConcurrentProcessingTest`, predict which test fails and how. Then run it, and put the check back.

<details>
<summary>Solution</summary>

The velocity test still passes: the lock still serialises the rule evaluation. The duplicate test fails, with the same four `DataIntegrityViolationException`s on `PROCESSED_EVENT` as in step 3:

```
history=1 alerts=1 unexpected=[DataIntegrityViolationException, DataIntegrityViolationException, DataIntegrityViolationException, DataIntegrityViolationException]
history=10 alerts=[VELOCITY, VELOCITY, VELOCITY, VELOCITY, VELOCITY] unexpected=[]
```

All five threads checked `processed_event` *before* any of them held the lock, so all five saw "not processed". They then queued on the lock and went through one by one, each one sure it was the first. The primary key stopped four of them at the end. A check only means something if nothing can change the answer between the check and the action, and here that's only true **inside** the lock.

</details>

### Exercise 2: why not just…?

Answer these in a sentence or two each, then compare with the solution.

1. Why not put `synchronized` on `process`?
2. Why not just make sure every producer keys by account, and skip the lock?
3. Why not use `@Version` (optimistic locking) on `MonitoredAccount`?

<details>
<summary>Solution</summary>

1. `synchronized` only works within one JVM. Banks run several instances of a service for resilience, and two instances can process the same account at once (after a rebalance, or with inconsistent keys). It would also serialise *every* account through one lock, not one lock per account. The database row lock works across instances and is per account.
2. Partitioning by key is the right design and you should do it, but it's a convention spread across other teams' producers, rebalances and any other code path that calls the service. A control the bank depends on shouldn't rely on everyone else getting it right. The lock makes correctness local, and it costs almost nothing when partitioning is working.
3. `@Version` detects conflicting **updates to the same row**. Here nothing updates the account row: the conflicting transactions insert *different* history rows, so there's no version conflict to detect. Write skew needs the transactions to be made to conflict deliberately, which is what taking the same lock does. (The Core ledger tutorial uses `@Version` where two transactions really do update the same balance.)

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
```

Run it two or three times. The concurrency tests must pass **every** time.

New files since guide 03:

```
src/main/java/com/example/fraud/transaction/
├── MonitoredAccount.java
└── MonitoredAccountRepository.java
src/main/resources/db/migration/
└── V4__create_monitored_account.sql
src/test/java/com/example/fraud/transaction/
└── ConcurrentProcessingTest.java
```

Modified: `TransactionMonitoringService.java` (create-if-absent, lock, `TransactionTemplate`), `application.properties` (listener concurrency).

---

## Common errors

**`Expected size: 5 but was: 1` (or `Expecting ArrayList: [] to contain only: ["VELOCITY"]`)**
The velocity test is seeing write skew. The account row isn't being locked before the history is read: check `evaluate` starts with `findByIdForUpdate`, and that `evaluate` runs inside `transactionTemplate.execute`, so the lock is held until commit.

**`Unique index or primary key violation: … MONITORED_ACCOUNT(ACCOUNT_ID)`** from the concurrency test
The row is being created inside the main transaction (step 5's first attempt). Create it in `ensureMonitored`, before the main transaction starts.

**`Unique index or primary key violation: … PROCESSED_EVENT(EVENT_ID)`** from the duplicate test
The duplicate check runs before the lock. See Exercise 1.

**`InvalidDataAccessApiUsageException: No active transaction`**
`findByIdForUpdate` was called outside a transaction. A row lock only lasts until the transaction ends, so a lock with no transaction is meaningless and Spring refuses. It must be called inside `transactionTemplate.execute(...)`.

**`UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only`**
You caught the `DataIntegrityViolationException` from the account insert *inside* a transaction (for example, by moving `ensureMonitored` inside `evaluate`). The failed insert marked the whole transaction for rollback, so catching it didn't help. Keep the insert in its own transaction.

**The concurrency test hangs, then fails with `TimeoutException`**
A thread is waiting for a lock that's never released: usually a breakpoint paused inside a transaction while other threads wait. Resume the debugger. `future.get(30, SECONDS)` is what turns a hang into a failure.

**Worker threads can't see data the test created**
The test class or method is `@Transactional`. Concurrency tests must not be.

---

## Recap

- Stateful rules need each account's events processed **one at a time**. **Partitioning by key** gives you that in normal running, but it depends on every producer, every rebalance and every code path behaving.
- Concurrent processing of one account causes **write skew**: each transaction decides on a history the others are changing, and the velocity rule silently misses. No error is raised.
- Serialise per account with a **lock row** and `SELECT … FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)`).
- **You can't lock a row that doesn't exist.** Create it if absent in its own short transaction, tolerating a lost race, then lock it in the main transaction.
- **Lock, then check, then act.** Duplicate checks inside the lock are reliable; a primary key backs them up.
- **`TransactionTemplate`** gives you explicit transaction boundaries when `@Transactional`'s proxy-based model doesn't fit.
- **Partition for throughput and ordering, lock for correctness.**

### Interview angle

<details>
<summary>"Kafka keeps events for one key in order. Why would you still need locking?"</summary>

Because the ordering guarantee only holds if every producer uses the same key, if nothing else (admin endpoints, backfills, replays) processes the same entity, and if a partition really has one consumer at a time, which isn't true for a moment during rebalances. If any of those fail, two transactions for the same entity run concurrently and stateful logic suffers from write skew. A database lock per entity makes correctness independent of those assumptions, and costs almost nothing when partitioning is working, because there's no contention.

</details>

<details>
<summary>"What's write skew, and how is it different from a lost update?"</summary>

A lost update is when two transactions update the same row and one overwrites the other's change. Write skew is when two transactions each read some data, make a decision based on it, and write *different* rows, so no single row conflicts, but the combined result breaks a rule each would have enforced on its own, like ten payments that should have triggered a velocity alert but each saw an empty history. Optimistic locking doesn't catch it because nothing updates the same row. Fix it by making the transactions conflict on purpose, by locking a common row, or by using SERIALIZABLE isolation where the database supports it properly.

</details>

<details>
<summary>"How do you lock an entity that might not exist yet?"</summary>

You can't lock a missing row, so concurrent first-time requests both see nothing and both try to insert. Insert it if absent in a separate short transaction, and treat a unique-constraint violation as "someone else created it", which is fine. Then lock it in the main transaction. In PostgreSQL, `INSERT … ON CONFLICT DO NOTHING` does the first step in one statement. Don't catch the constraint violation inside the main transaction, because the failure marks it rollback-only.

</details>

<details>
<summary>"How would you test a race condition in a message consumer?"</summary>

Call the processing service directly from many threads, released at the same instant by a `CountDownLatch`, without a test-level transaction, so each call commits for real. Choose inputs where the correct result doesn't depend on processing order (here, payments with the same timestamp). Assert the invariants (exact alert count, no unexpected exceptions, no duplicates), print what happened, show the test failing before the fix, and run it repeatedly.

</details>

### Further reading

- [Spring for Apache Kafka reference: `@KafkaListener` and container concurrency](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/listener-annotation.html)
- [Spring Data JPA reference: locking](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)
- [Spring Framework reference: programmatic transaction management](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)
- [PostgreSQL docs: transaction isolation](https://www.postgresql.org/docs/current/transaction-iso.html), including serialization anomalies such as write skew
- [Martin Kleppmann, *Designing Data-Intensive Applications*](https://dataintensive.net/), chapter 7 (transactions, write skew and phantoms)

**Next:** [05: Alerts and cases](05-alerts-and-cases.md). The humans arrive: analysts turn alerts into cases, investigate them and close them with an outcome, and the API makes sure nobody skips a step.
