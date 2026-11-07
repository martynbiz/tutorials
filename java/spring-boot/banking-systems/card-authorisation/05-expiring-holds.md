# 05: Expiring Holds

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- Why some holds are never cleared, and why the issuer has to **release** them
- What a **late presentment** is, and why releasing a hold doesn't cancel the payment
- How to run a background job with **`@Scheduled`**, and how to switch it off in tests
- How to test "a week later" in milliseconds, with a **mutable test `Clock`**
- Why **every instance** of a service runs its scheduled jobs, and the double-processing bug that follows, seen in a failing test
- How to make a job **idempotent and concurrency-safe** with a **conditional update** and a per-account lock, using `TransactionTemplate` for one short transaction per account
- Where **ShedLock**-style "only one instance runs this" fits, and why it isn't enough on its own

By the end, uncleared holds are released automatically, and four servers running the job at the same moment release each hold exactly once.

---

## Concepts

### How it works in a real bank

Most authorisations are followed by a presentment. Some never are:

- A **pay-at-the-pump** pre-authorisation where the customer drove off without filling up, and the terminal never sent a reversal.
- A **hotel** or **car hire** pre-authorisation that was replaced by a different one at check-out.
- A merchant whose system simply failed to submit its clearing record, or a reversal that got lost on the way.

If the issuer held that money forever, the customer would permanently lose part of their available balance for a payment that never happened. So holds **expire**. After a certain time with no presentment, the issuer releases the hold and the customer's available balance goes back up. The pending transaction disappears from their app.

How long a hold should last is governed by **scheme rules**, which depend on the type of merchant and authorisation. Hotels and car hire generally get much longer than an ordinary shop purchase, typically around a month, because the final bill comes at the end of the stay or rental. This tutorial uses a configurable default (seven days) and, in Exercise 2, a longer lifetime for hotels.

### Releasing a hold doesn't cancel the payment

Expiry is the issuer's housekeeping, not a refund. If the merchant's presentment turns up **after** the hold has expired (a **late presentment**), the issuer still has to post it. You already allowed for that in guide 04: an `EXPIRED` authorisation can still be cleared. For the customer, the money reappears in their available balance when the hold expires, then leaves again when the late presentment posts. That's one of the more confusing things customers see in their banking apps, and now you know why it happens.

### Scheduled jobs in Spring

Spring can run a method on a timer:

```java
@Scheduled(fixedDelayString = "${cards.holds.expiry-check-interval}")
public void run() { … }
```

`fixedDelay` means "wait this long after one run **finishes** before starting the next", so runs on one server never overlap. (`fixedRate` measures from the *start* of each run, and `cron` runs at set times of day.) Scheduling is switched on with `@EnableScheduling`.

That's simple, and it hides a trap.

### Every instance runs the job

Banks run services on several servers (instances) at once, for resilience and capacity. **Each instance runs its own scheduler.** With four instances, the expiry job runs four times per interval, often at nearly the same moment.

A job written as "load the due holds, mark each one expired" is fine on one server. On four, every instance loads the **same** due holds and processes each of them. The final status is the same either way (`EXPIRED`), so it's easy to miss. But anything else the job does per hold (sending the customer a "your pending payment has dropped off" notification, publishing an event, counting releases for a dashboard) happens **four times**. Worse, an expiry that overwrites an authorisation's row can wipe out a reversal or a clearing that committed a moment earlier.

There are two complementary answers:

1. **Make the job safe to run concurrently.** Each hold is released by exactly one instance, however many run at once. This is the one you must have.
2. **Try to run it on only one instance**, using a distributed lock such as the open-source [ShedLock](https://github.com/lukas-krecan/ShedLock) library (a lock row in a shared database table) or a leader-election mechanism. This saves wasted work, but on its own it isn't enough: locks can expire while a slow run is still going, and a deployment can briefly have two "leaders". Treat it as an optimisation, not as your correctness guarantee.

### Making the job safe

The fix has two parts:

- **A conditional update.** Instead of "load, then set the status", write one statement that changes a row **only if it's still in the state you expect**:

  ```sql
  UPDATE authorisation SET status = 'EXPIRED', held_amount = 0
  WHERE account_id = ? AND status = 'PENDING' AND expires_at <= ?
  ```

  The database re-checks `status = 'PENDING'` as it updates each row. If another instance has already expired it, or a presentment has cleared it, the row simply doesn't match. The statement returns the number of rows it changed, so every instance knows exactly how many holds **it** released. Run it twice and the second run changes nothing: it's **idempotent**.

- **The account lock, again.** Guide 03's rule says anything that changes a hold takes the account lock first. Reversals and clearing load an authorisation under that lock and then write the whole row back. If the expiry job changed the row without taking the lock, those writers could overwrite its change, or it could overwrite theirs. Taking the same lock keeps every writer in one queue per account.

---

## Steps

### 1. A clock tests can move

The application already gets the time from a `Clock` bean (guide 01). For tests, you need one you can wind forward. Create `src/test/java/com/example/cards/MutableClock.java`:

```java
package com.example.cards;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A clock that tests can move forward, so "a week later" takes no time at all. */
public class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant start, ZoneId zone) {
        this.instant = start;
        this.zone = zone;
    }

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }
}
```

`java.time.Clock` is abstract, and only three methods need implementing. The field is `volatile` because concurrency tests read the clock from many threads.

> **Sidebar: `Clock.fixed` and `Clock.offset`.** The JDK has `Clock.fixed(instant, zone)` for a clock that never moves, and `Clock.offset(clock, duration)` for one shifted by a fixed amount. They're perfect for unit tests that construct objects by hand. In a Spring context, where one clock bean is shared by every service, you need to move the *same* clock during a test, so a mutable one is simpler.

Now replace the application's clock in every integration test. `src/test/java/com/example/cards/TestClockConfig.java`:

```java
package com.example.cards;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.time.ZoneOffset;

/** Replaces the system clock in every integration test. */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    MutableClock testClock() { // not "clock": that name is taken by ClockConfig
        // A fixed, whole-second start: repeatable, and no sub-microsecond digits for the database to round
        return new MutableClock(Instant.parse("2026-11-06T09:00:00Z"), ZoneOffset.UTC);
    }
}
```

- **`@Primary`** means that wherever a `Clock` is injected, Spring picks this one over `ClockConfig`'s. Both beans exist, and the primary one wins.
- **The method is called `testClock`**, not `clock`. Bean names come from method names, and Spring Boot refuses to let a second bean silently replace one with the same name (see **Common errors**).
- **The start time is fixed**, which makes tests repeatable. Why a *whole second* matters is explained in step 4.

Update `CardsIntegrationTest` to import it, and to switch scheduled jobs off (step 2 explains the property):

```java
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cards-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "cards.scheduling.enabled=false"
})
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public @interface CardsIntegrationTest {
}
```

(Import `org.springframework.context.annotation.Import`.) Every integration test now runs at 09:00 UTC on 6 November 2026 until a test moves the clock. Run the whole suite: everything from guides 01 to 04 still passes, because nothing depended on the real time.

### 2. Switch scheduling on (except in tests)

Create `config/SchedulingConfig.java`:

```java
package com.example.cards.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduled jobs run unless cards.scheduling.enabled=false (as it is in tests). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "cards.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
```

`@ConditionalOnProperty` only creates this configuration, and so only enables scheduling, when the property is `true` or missing. Tests set it to `false`.

**Why turn it off in tests?** A job firing on its own in the background, at a time you don't control, would change data in the middle of other tests: holds expiring during a concurrency test, counts that are off by one now and then. Tests should call the job's logic directly, exactly when they mean to.

Add the interval to `application.properties`:

```properties
# How often to look for holds that have expired
cards.holds.expiry-check-interval=1m
```

### 3. A first version of the job

Add to `Authorisation`:

```java
    /** No presentment arrived in time: release the hold. */
    public void expire() {
        if (status != AuthorisationStatus.PENDING) {
            throw new AuthorisationStateException(id, status, "expired");
        }
        this.status = AuthorisationStatus.EXPIRED;
        this.heldAmount = BigDecimal.ZERO;
    }
```

and a finder to `AuthorisationRepository`:

```java
    List<Authorisation> findByStatusAndExpiresAtLessThanEqual(AuthorisationStatus status, Instant now);
```

Create `expiry/HoldExpiryService.java`. This first version is deliberately the obvious one:

```java
package com.example.cards.expiry;

import com.example.cards.authorisation.Authorisation;
import com.example.cards.authorisation.AuthorisationRepository;
import com.example.cards.authorisation.AuthorisationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Releases holds that were never cleared. FIRST VERSION: correct on one server only. */
@Service
public class HoldExpiryService {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryService.class);

    private final AuthorisationRepository authorisations;
    private final Clock clock;

    public HoldExpiryService(AuthorisationRepository authorisations, Clock clock) {
        this.authorisations = authorisations;
        this.clock = clock;
    }

    /** Returns how many holds this call released. */
    @Transactional
    public int expireDueHolds() {
        Instant now = Instant.now(clock);
        List<Authorisation> due = authorisations.findByStatusAndExpiresAtLessThanEqual(
                AuthorisationStatus.PENDING, now);
        for (Authorisation authorisation : due) {
            authorisation.expire();
        }
        log.info("Released {} expired hold(s)", due.size());
        return due.size();
    }
}
```

And the trigger, `expiry/HoldExpiryJob.java`:

```java
package com.example.cards.expiry;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The trigger. All the logic lives in HoldExpiryService, where tests can call it directly. */
@Component
public class HoldExpiryJob {

    private final HoldExpiryService expiryService;

    public HoldExpiryJob(HoldExpiryService expiryService) {
        this.expiryService = expiryService;
    }

    @Scheduled(fixedDelayString = "${cards.holds.expiry-check-interval}")
    public void run() {
        expiryService.expireDueHolds();
    }
}
```

**Keep the scheduled method thin.** `HoldExpiryJob` only decides *when*. `HoldExpiryService` decides *what*, returns a result a test can check, and can be called from a test, an admin endpoint or an operations script. The `${…}` placeholder reads the interval from configuration, and `fixedDelayString` accepts the same duration formats as the rest of Spring Boot (`1m`, `20s`, `PT1M`).

### 4. Test it, a week from now

Create `src/test/java/com/example/cards/expiry/HoldExpiryTest.java`:

```java
package com.example.cards.expiry;

import com.example.cards.CardsIntegrationTest;
import com.example.cards.MutableClock;
import com.example.cards.account.AccountService;
import com.example.cards.authorisation.AuthorisationService;
import com.example.cards.authorisation.AuthorisationStatus;
import com.example.cards.card.CardService;
import com.example.cards.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@CardsIntegrationTest
class HoldExpiryTest {

    @Autowired MutableClock clock;
    @Autowired AccountService accountService;
    @Autowired CardService cardService;
    @Autowired AuthorisationService authorisationService;
    @Autowired HoldExpiryService expiryService;

    Long accountId;
    Long authorisationId;

    @BeforeEach
    void holdThirtyPounds() {
        accountId = accountService.open("Alice", Money.gbp("0.00")).getId();
        accountService.deposit(accountId, Money.gbp("100.00"));
        String token = cardService.issue(accountId).getToken();
        authorisationId = authorisationService
                .authorise(token, Money.gbp("30.00"), "Corner Cafe", "5814").authorisationId();
    }

    @Test
    void aHoldIsStillPendingBeforeItExpires() {
        clock.advance(Duration.ofDays(6));

        expiryService.expireDueHolds();

        assertThat(authorisationService.get(authorisationId).getStatus()).isEqualTo(AuthorisationStatus.PENDING);
        assertThat(accountService.balances(accountId).available()).isEqualTo(Money.gbp("70.00"));
    }

    @Test
    void anUnclearedHoldIsReleasedWhenItExpires() {
        clock.advance(Duration.ofDays(7));

        expiryService.expireDueHolds();

        assertThat(authorisationService.get(authorisationId).getStatus()).isEqualTo(AuthorisationStatus.EXPIRED);
        assertThat(accountService.balances(accountId).available()).isEqualTo(Money.gbp("100.00"));
    }
}
```

`clock.advance(Duration.ofDays(7))` and a week has passed. No waiting, no `Thread.sleep`. And `anUnclearedHold…` tests the exact boundary: a hold created at 09:00 expires at 09:00 seven days later (`expiresAt <= now`), not a moment after.

Both pass. They pass because the clock starts on a whole second. **Our first version of `TestClockConfig` started from the real time, `Clock.systemUTC().instant()`, and `anUnclearedHold…` failed:**

```
expected: EXPIRED
 but was: PENDING
```

The job logged `Released 0 expired hold(s)`. The system clock has **nanosecond** precision, but the `TIMESTAMP` column stores **microseconds**. So `expires_at` came back from the database slightly different from the `now + 7 days` the test calculated, and on the exact boundary, "due" became "not due yet". In production that's harmless (the next run, a minute later, releases the hold). In a test that checks the exact boundary, it's a flaky failure. A whole-second start removes the extra digits.

> **The lesson generalises:** when you compare instants that have been through a database, think about precision. Many teams truncate `Instant.now(clock)` to microseconds (`.truncatedTo(ChronoUnit.MICROS)`) before storing it, so the value in memory and the value in the database are the same.

### 5. Four servers at once

Now simulate four instances running the job at the same moment. Create `src/test/java/com/example/cards/expiry/ConcurrentExpiryTest.java`:

```java
package com.example.cards.expiry;

import com.example.cards.CardsIntegrationTest;
import com.example.cards.MutableClock;
import com.example.cards.account.AccountService;
import com.example.cards.authorisation.AuthorisationService;
import com.example.cards.card.CardService;
import com.example.cards.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Four servers each run the expiry job at the same moment. */
@CardsIntegrationTest
class ConcurrentExpiryTest {

    @Autowired MutableClock clock;
    @Autowired AccountService accountService;
    @Autowired CardService cardService;
    @Autowired AuthorisationService authorisationService;
    @Autowired HoldExpiryService expiryService;

    @Test
    void eachHoldIsReleasedExactlyOnceWhenFourInstancesRunTogether() throws Exception {
        // Clear out any due holds left by other test classes, so we only count our own
        clock.advance(Duration.ofDays(30));
        expiryService.expireDueHolds();

        for (int i = 0; i < 2; i++) {
            Long accountId = accountService.open("Customer " + i, Money.gbp("0.00")).getId();
            accountService.deposit(accountId, Money.gbp("100.00"));
            String token = cardService.issue(accountId).getToken();
            for (int j = 0; j < 5; j++) {
                authorisationService.authorise(token, Money.gbp("1.00"), "Corner Cafe", "5814");
            }
        }
        clock.advance(Duration.ofDays(8));

        CountDownLatch startingGun = new CountDownLatch(1);
        List<Integer> releasedPerInstance = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    try {
                        int released = expiryService.expireDueHolds();
                        synchronized (releasedPerInstance) {
                            releasedPerInstance.add(released);
                        }
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
                future.get(30, TimeUnit.SECONDS);
            }
        }

        System.out.println("released per instance = " + releasedPerInstance + ", failures = "
                + failures.stream().map(t -> t.getClass().getSimpleName()).toList());
        assertThat(failures).isEmpty();
        assertThat(releasedPerInstance.stream().mapToInt(Integer::intValue).sum()).isEqualTo(10);
    }
}
```

- **Ten holds across two accounts**, then eight days later four threads (four "instances") run the job together, released by the same `CountDownLatch` starting gun as in guide 03.
- **Each instance reports how many holds it released.** Across all four, that must add up to exactly 10.
- **The warm-up at the top.** All integration tests share one database and one clock. Holds created by other test classes might be due too, and they'd inflate the count. Moving the clock 30 days and running the job once first clears them out, so the concurrent runs only see this test's holds. (Moving the shared clock forward is harmless for the other tests: they all work relative to "now".)

Run it:

```
released per instance = [10, 10, 10, 10], failures = []
```

```
expected: 10
 but was: 40
```

Every instance released all ten holds. Each one loaded the same ten `PENDING` rows before any of the others had committed, and each one "expired" them. In the database, the end state happens to look right (ten `EXPIRED` rows), and that's exactly what makes this bug dangerous. A notification per release would have reached each customer four times, and a release counter on a dashboard would show 40.

It failed the same way on every run.

### 6. Make the job safe

Replace the finder in `AuthorisationRepository` with two queries (import `org.springframework.data.jpa.repository.Modifying`):

```java
    /** Accounts with at least one hold due to expire: the candidates for this run. */
    @Query("""
            select distinct a.account.id
            from Authorisation a
            where a.status = :pending and a.expiresAt <= :now
            """)
    List<Long> findAccountIdsWithHoldsDueBy(Instant now, AuthorisationStatus pending);

    /**
     * Expires an account's due holds in one statement. The WHERE clause re-checks
     * status = PENDING, so running it twice changes nothing the second time.
     */
    @Modifying
    @Query("""
            update Authorisation a
            set a.status = :expired, a.heldAmount = 0
            where a.account.id = :accountId and a.status = :pending and a.expiresAt <= :now
            """)
    int expireDueHolds(Long accountId, Instant now, AuthorisationStatus pending, AuthorisationStatus expired);
```

- **`@Modifying`** tells Spring Data this `@Query` is an `update` (or `delete`), not a select, and the `int` return value is the number of rows changed.
- **A bulk update bypasses the persistence context.** Hibernate doesn't update any `Authorisation` objects already loaded in the same transaction. That's fine here because this transaction never loads any. If yours does, add `@Modifying(clearAutomatically = true)`.

Now rewrite `expiry/HoldExpiryService.java`:

```java
package com.example.cards.expiry;

import com.example.cards.account.AccountRepository;
import com.example.cards.authorisation.AuthorisationRepository;
import com.example.cards.authorisation.AuthorisationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Releases holds that were never cleared. Safe to run on any number of
 * servers at once: each hold is released exactly once.
 */
@Service
public class HoldExpiryService {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryService.class);

    private final AuthorisationRepository authorisations;
    private final AccountRepository accounts;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public HoldExpiryService(AuthorisationRepository authorisations, AccountRepository accounts,
                             TransactionTemplate transaction, Clock clock) {
        this.authorisations = authorisations;
        this.accounts = accounts;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** Returns how many holds THIS call released. */
    public int expireDueHolds() {
        Instant now = Instant.now(clock);
        int released = 0;
        for (Long accountId : authorisations.findAccountIdsWithHoldsDueBy(now, AuthorisationStatus.PENDING)) {
            // One short transaction per account: locks are held briefly, and one
            // bad account can't roll back the work done for all the others
            Integer count = transaction.execute(status -> {
                accounts.findByIdForUpdate(accountId).orElseThrow();
                return authorisations.expireDueHolds(accountId, now,
                        AuthorisationStatus.PENDING, AuthorisationStatus.EXPIRED);
            });
            released += count;
        }
        log.info("Released {} expired hold(s)", released);
        return released;
    }
}
```

`Authorisation.expire()` and `findByStatusAndExpiresAtLessThanEqual` are no longer used. Delete them.

What changed, and why:

1. **Find candidates without locking.** The first query just lists accounts that *might* have due holds. It's a cheap read, and it can be out of date by the time each account is processed. That's fine, because the next step re-checks everything.
2. **One transaction per account.** `TransactionTemplate.execute(...)` runs the lambda in its own transaction and commits when it returns. Each account's lock is held only for its own update, so the job never blocks a customer's card for long, and a failure on one account doesn't undo the others.
3. **Lock the account, then run the conditional update.** The lock puts the job in the same queue as authorisations, reversals and clearing for that account. The `WHERE status = 'PENDING'` makes the update count only holds that are *still* pending at that moment. An instance that arrives second, after waiting for the lock, finds nothing left to do.

> **Why `TransactionTemplate` and not `@Transactional`?** You want a transaction **per account**, inside a loop. Putting `@Transactional` on a private helper method in the same class wouldn't work: Spring's transactions are applied by a proxy around the bean, and a call from `this` goes straight to the method without passing through the proxy. Your options are a separate bean with a `@Transactional` method, or `TransactionTemplate`, which manages the transaction explicitly and makes the boundary visible right there in the loop. Spring Boot auto-configures a `TransactionTemplate` bean, so you can inject it.

Run `ConcurrentExpiryTest`:

```
released per instance = [10, 0, 0, 0], failures = []
```

✅ Green, every time. On some runs the split was `[5, 5, 0, 0]`: two instances got one account each. Either way, ten holds were released exactly once in total.

> **Which part fixed the test?** We checked: with the account lock commented out, the conditional update alone still gave exactly 10 on H2. That's the part that makes the job idempotent. The lock is there for the *other* writers. Without it, a partial reversal that had loaded a hold just before the job expired it would write the whole row back, `PENDING` and all, bringing an expired hold back to life. That race is very hard to trigger in a test (it needs exact timing), which is why the rule "every change to a hold takes the account lock" matters more than any one test.

### 7. Try it

Run the app with a one-minute hold lifetime and a 20-second check interval, so you can watch it happen. Command-line arguments override `application.properties`:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--cards.holds.lifetime=1m --cards.holds.expiry-check-interval=20s"
```

In IntelliJ: **Run → Edit Configurations…**, select `CardAuthorisationApplication`, and put `--cards.holds.lifetime=1m --cards.holds.expiry-check-interval=20s` in **Program arguments**.

Authorise £12 at the Corner Cafe (the first request in guide 02's section of `requests.http`, with the amount changed), then watch the log. The job runs on Spring's `scheduling-1` thread every 20 seconds:

```
2026-11-07T09:08:11.607Z  INFO … [scheduling-1] c.e.cards.expiry.HoldExpiryService : Released 0 expired hold(s)
2026-11-07T09:08:31.610Z  INFO … [scheduling-1] c.e.cards.expiry.HoldExpiryService : Released 0 expired hold(s)
2026-11-07T09:08:51.613Z  INFO … [scheduling-1] c.e.cards.expiry.HoldExpiryService : Released 0 expired hold(s)
2026-11-07T09:09:11.615Z  INFO … [scheduling-1] c.e.cards.expiry.HoldExpiryService : Released 0 expired hold(s)
2026-11-07T09:09:31.633Z  INFO … [scheduling-1] c.e.cards.expiry.HoldExpiryService : Released 1 expired hold(s)
```

The hold was created at 09:08:12 and expired at 09:09:12. The run at 09:09:11.615 was about a second too early, so the next run, at 09:09:31, released it. `GET /authorisations/{id}` now shows `"status": "EXPIRED"` and a `heldAmount` of £0.00, and the account's `held` is back to £0.00.

Now play the late presentment. Add to `requests.http` (using your authorisation's id):

```http
### A late presentment for the expired hold
POST http://localhost:8080/presentments
Content-Type: application/json

{"authorisationId": 6, "amount": "12.00"}
```

It's accepted and posted, and the ledger balance drops by £12.00, exactly as guide 04 intended:

```json
{
  "authorisationId": 6,
  "authorisedAmount": { "amount": "12.00", "currency": "GBP" },
  "clearedAmount": { "amount": "12.00", "currency": "GBP" },
  "journalEntryId": 3,
  "postings": [
    { "accountId": 1, "direction": "DEBIT",  "amount": { "amount": "12.00", "currency": "GBP" } },
    { "accountId": 3, "direction": "CREDIT", "amount": { "amount": "12.00", "currency": "GBP" } }
  ]
}
```

Stop the app and restart it normally afterwards, or every new hold will vanish after a minute.

---

## Your turn

### Exercise 1: prove late presentments work

Guide 04 made `EXPIRED` authorisations clearable, but no test proves the whole path. Add a test to `HoldExpiryTest`: expire the £30 hold, clear it for £30 through `ClearingService`, then check the status is `CLEARED`, the ledger balance is £70.00, and so is the available balance.

<details>
<summary>Solution</summary>

Inject `@Autowired ClearingService clearingService;` (import `com.example.cards.clearing.ClearingService`), then:

```java
    @Test
    void aLatePresentmentStillClearsAnExpiredHold() {
        clock.advance(Duration.ofDays(7));
        expiryService.expireDueHolds();

        clearingService.clear(authorisationId, Money.gbp("30.00"));

        assertThat(authorisationService.get(authorisationId).getStatus()).isEqualTo(AuthorisationStatus.CLEARED);
        assertThat(accountService.get(accountId).getLedgerBalance()).isEqualTo(Money.gbp("70.00"));
        assertThat(accountService.balances(accountId).available()).isEqualTo(Money.gbp("70.00"));
    }
```

Between the expiry and the clearing, the customer's available balance went back up to £100. That's the "the money came back, then went again" effect customers notice.

</details>

### Exercise 2: hotels hold for longer

Hotels (MCC `7011`) usually present at check-out, which can be well over a week after check-in. Make the hold lifetime depend on the merchant category code:

```properties
# Hotels (MCC 7011) settle at check-out, so their holds last longer
cards.holds.lifetime-by-merchant-category.7011=30d
```

1. Add a `Map<String, Duration> lifetimeByMerchantCategory` component to `HoldProperties`, defaulting to an empty map when the property is missing, plus a `lifetimeFor(String merchantCategoryCode)` method that falls back to `lifetime`.
2. Use it in `AuthorisationService`.
3. Add a test to `HoldExpiryTest`: a hotel hold is still `PENDING` after 8 days and `EXPIRED` after 30.

<details>
<summary>Hint</summary>

When a property is missing, Spring Boot binds `null` to a record component. A compact constructor can replace that `null` with `Map.of()`. Spring Boot binds `cards.holds.lifetime-by-merchant-category.7011=30d` to a map entry with the key `"7011"`.

</details>

<details>
<summary>Solution</summary>

`HoldProperties`:

```java
package com.example.cards.authorisation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/** Settings under cards.holds.* in application.properties. */
@ConfigurationProperties("cards.holds")
public record HoldProperties(Duration lifetime, Map<String, Duration> lifetimeByMerchantCategory) {

    public HoldProperties {
        lifetimeByMerchantCategory = lifetimeByMerchantCategory == null
                ? Map.of()
                : Map.copyOf(lifetimeByMerchantCategory);
    }

    /** The hold lifetime for a merchant category code, or the default if it has none of its own. */
    public Duration lifetimeFor(String merchantCategoryCode) {
        return lifetimeByMerchantCategory.getOrDefault(merchantCategoryCode, lifetime);
    }
}
```

In `AuthorisationService.authorise`, the approval becomes:

```java
                ? Authorisation.approved(card, account, amount, merchantName, merchantCategoryCode,
                        now, now.plus(holds.lifetimeFor(merchantCategoryCode)))
```

`HoldExpiryTest`:

```java
    @Test
    void hotelHoldsLastLonger() {
        String token = cardService.issue(accountId).getToken();
        Long hotel = authorisationService
                .authorise(token, Money.gbp("50.00"), "Hotel Example", "7011").authorisationId();

        clock.advance(Duration.ofDays(8));
        expiryService.expireDueHolds();
        assertThat(authorisationService.get(hotel).getStatus()).isEqualTo(AuthorisationStatus.PENDING);

        clock.advance(Duration.ofDays(22));
        expiryService.expireDueHolds();
        assertThat(authorisationService.get(hotel).getStatus()).isEqualTo(AuthorisationStatus.EXPIRED);
    }
```

`Map.copyOf` makes the record properly immutable. The expiry job needed no changes at all: it only looks at `expires_at`, which is decided once, when the hold is created.

> **Simplification:** real lifetimes depend on more than the MCC (the scheme, whether the authorisation was flagged as an estimate, and so on), and they're set by scheme rules, not chosen by the issuer.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 55, Failures: 0, Errors: 0, Skipped: 0
```

Run it two or three times: every concurrency test must pass every time.

New files since guide 04:

```
src/main/java/com/example/cards/
├── config/SchedulingConfig.java
└── expiry/
    ├── HoldExpiryJob.java
    └── HoldExpiryService.java
src/test/java/com/example/cards/
├── MutableClock.java
├── TestClockConfig.java
└── expiry/
    ├── ConcurrentExpiryTest.java
    └── HoldExpiryTest.java
```

Modified: `AuthorisationRepository.java` (the two expiry queries), `HoldProperties.java`, `AuthorisationService.java`, `application.properties`, `CardsIntegrationTest.java`, `requests.http`.

The finished package layout:

```
com.example.cards
├── account/        Account, AccountType, AccountBalances, AccountService, AccountController, …
├── authorisation/  Authorisation, AuthorisationRules, AuthorisationService, ResponseCode, …
├── card/           Card, CardStatus, CardService, CardController, …
├── clearing/       ClearingService, PresentmentController, SettlementService, …
├── config/         ClockConfig, SchedulingConfig
├── expiry/         HoldExpiryJob, HoldExpiryService
├── ledger/         JournalEntry, Posting, LedgerService, …
├── money/          Money
└── web/            ApiExceptionHandler
```

---

## Common errors

**`The bean 'clock', defined in class path resource [com/example/cards/config/ClockConfig.class], could not be registered. A bean with that name has already been defined in class path resource [com/example/cards/TestClockConfig.class] and overriding is disabled.`**
The test configuration's `@Bean` method is called `clock`, the same name as the application's. Rename it (`testClock`) and keep `@Primary`. Spring Boot's suggestion of setting `spring.main.allow-bean-definition-overriding=true` works, but it hides real mistakes elsewhere, so don't.

**`HoldExpiryTest` fails with `expected: EXPIRED but was: PENDING`, and the log says `Released 0 expired hold(s)`**
The test clock starts at a time with sub-microsecond digits, so the stored `expires_at` and the test's `now` differ slightly on the exact boundary. Start `MutableClock` at a fixed, whole-second instant (step 4).

**`ConcurrentExpiryTest` says `expected: 10 but was: 40`**
The job loads due holds and expires them one by one, so every instance processes every hold. Use the conditional `update … where status = PENDING` under the account lock (step 6).

**`ConcurrentExpiryTest` counts holds it didn't create**
Holds left over from other test classes are due too. Keep the warm-up run at the top of the test, which clears them before the concurrent runs.

**Tests fail at random, with holds `EXPIRED` that shouldn't be**
Scheduling is running during tests. Check `cards.scheduling.enabled=false` is in `CardsIntegrationTest`, and that `@EnableScheduling` is only on `SchedulingConfig` (not also on the application class, where the property can't switch it off).

**The job runs but never releases anything in the real app**
Check the hold's `expiresAt` against the log times, and remember a run only catches holds that are already due. With the default settings (7-day holds, a 1-minute interval) nothing will expire for a week. Use the command-line overrides from step 7 to see it happen.

---

## Recap

- **Holds expire** when no presentment arrives in time, giving the customer back their available balance. The lifetime is set by **scheme rules** and depends on the merchant type: hotels and car hire get longer.
- **A late presentment is still posted.** Expiry releases the hold, it doesn't cancel the payment.
- **`@Scheduled`** runs jobs on a timer. Keep the scheduled method thin, put the logic in a service tests can call, and **switch scheduling off in tests**.
- **Inject a `Clock`** and use a **mutable test clock** to test time-based behaviour in milliseconds. Mind the precision of instants that go through a database.
- **Every instance runs every scheduled job.** Make jobs **idempotent and concurrency-safe**: a **conditional update** that re-checks the state, under the **same lock** as every other writer, in **short per-item transactions**. "Run on one instance only" (ShedLock, leader election) is an optimisation on top, not a substitute.

### Interview angle

<details>
<summary>"Your service runs on four instances and has a scheduled job. What can go wrong, and what do you do?"</summary>

Each instance runs the job, so the work happens up to four times, concurrently. Anything that isn't idempotent (notifications, events, counters, writes that overwrite rows) gets duplicated or raced. First make the job itself safe: process each item with a conditional update or a compare-and-set on its state, so only one instance can transition it, and take the same locks other writers use. Keep transactions short and per item or per account. Then, if wasted work matters, add a distributed lock (ShedLock) or leader election so normally only one instance runs it, but never rely on that alone for correctness: locks can expire during long runs, and failovers can produce two leaders.

</details>

<details>
<summary>"How do you test code that depends on the current time?"</summary>

Never call `Instant.now()` or `LocalDate.now()` directly. Inject a `java.time.Clock` and use `Instant.now(clock)`. In unit tests, pass `Clock.fixed(...)`. In integration tests, register a mutable clock as the primary `Clock` bean and advance it, so "seven days later" takes no real time. Disable background schedulers in tests and call the job's logic directly. Watch out for precision: databases often store microseconds while Java clocks can have nanoseconds.

</details>

<details>
<summary>"Why does a pending card payment sometimes disappear and then reappear as a completed payment?"</summary>

The authorisation placed a hold, which showed as pending. The merchant didn't present the transaction before the hold's lifetime ran out, so the issuer released the hold and the pending item disappeared. The merchant then sent a late presentment, which the issuer has to post, so it reappears as a completed transaction, and the money leaves the account. Releasing a hold is housekeeping. It doesn't cancel the payment.

</details>

<details>
<summary>"What's the difference between fixedRate, fixedDelay and cron in Spring's @Scheduled?"</summary>

`fixedDelay` waits the given time after the previous run *finishes*, so runs never overlap on one instance. `fixedRate` aims to start runs at a regular interval measured from each *start* (on Spring's default single-threaded scheduler, a slow run delays the next one rather than overlapping it). `cron` runs at calendar times, such as 02:00 every day, and can take a time zone. For a housekeeping job like hold expiry, `fixedDelay` is the natural fit.

</details>

### Further reading

- [Spring Framework reference: Task Execution and Scheduling](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)
- [Spring Framework reference: Programmatic transaction management (`TransactionTemplate`)](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)
- [ShedLock on GitHub](https://github.com/lukas-krecan/ShedLock): a small library that stops a scheduled task running on more than one instance at the same time
- [Visa: Authorization and reversal processing best practices for merchants](https://usa.visa.com/content/dam/VCOM/regional/na/us/support-legal/documents/authorization-and-reversal-processing-best-practices-for-merchants.pdf): authorisation validity and why merchants should reverse unused authorisations

---

## You've built a card authorisation system

Across five guides you've built the issuer's side of a card payment:

| Guide | Banking idea | Java/Spring skill |
|---|---|---|
| 01 | The four-party model. Ledger vs available balance. Never store the PAN | `Money`, an injected `Clock`, `ProblemDetail`, a test meta-annotation |
| 02 | Authorisation, response codes, holds and spending limits | A pure decision function, test data builders, parameterised tests, counting SQL statements |
| 03 | No overspending, even at the same instant. Reversals | Write skew, pessimistic locking on the right row, "read the id, lock, then load" |
| 04 | Clearing, posting and settlement | A cut-down double-entry ledger, lock ordering, duplicate protection |
| 05 | Expiring holds and late presentments | `@Scheduled`, a mutable test clock, idempotent and concurrency-safe jobs |

**Where to go next:**

- **The Core ledger tutorial** builds the ledger properly: journal entries with any number of postings, statements, ledger reversals and idempotency keys.
- **The [Fraud monitoring tutorial](../fraud-monitoring/README.md)** consumes events like "authorisation approved" to look for suspicious patterns.
- **Extend this system yourself:** customer-set card limits (`PUT /cards/{id}/limits`); an *incremental authorisation* that raises an existing hotel hold instead of creating a new one; an idempotency key on `POST /authorisations`, because the scheme can retry a request it didn't get an answer to; or switching to PostgreSQL and checking every concurrency test still passes.
