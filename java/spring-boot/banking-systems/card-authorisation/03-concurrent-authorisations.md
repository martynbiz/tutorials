# 03: Concurrent Authorisations

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- Why simultaneous authorisations for one account are normal in card processing, not an edge case
- How ten payments at the same instant can **overspend** the available balance, seen for real in a failing test
- **Write skew**: the race where nothing is overwritten, and why `@Version` optimistic locking can't detect it
- How to fix it with a **pessimistic lock** on the account row, and how to decide **which** row to lock
- What lock waits do to latency, and the lock timeouts that keep them bounded
- **Reversals** and **partial reversals**: a merchant giving back some or all of a hold, safely under concurrency
- The **"read the id, lock, then load"** pattern, and the bug it prevents

This is the guide that makes authorisation correct. By the end, ten simultaneous £20 payments against £100 approve exactly five, every time.

---

## Concepts

### How it works in a real bank

Two authorisations for the same account arriving at the same moment isn't rare:

- **Joint accounts and multiple cards.** Two people with cards on the same account shop at the same time. A replacement card and the old card can both be live for a while.
- **Online merchants and subscriptions.** A batch of recurring payments from one merchant can arrive together, and so can several items in one online basket that are authorised separately.
- **Fraud.** Someone holding stolen card details may fire off many purchases in parallel, precisely to beat a system that checks balances one at a time.

The issuer must make sure that **the sum of everything it approves never exceeds what was available**. Approving a payment is a promise to pay: once the issuer says `00`, it owes the scheme that money even if the customer's account can't cover it. An issuer that overspends its customers' balances takes the loss itself.

### The race

The authorisation service from guide 02 reads the total of the account's holds, decides, and inserts a new hold. Two requests for £60 against an available balance of £100:

| Time | Request A | Request B | Holds in the database |
|---|---|---|---|
| 1 | Sum holds → £0. Available £100 | | £0 |
| 2 | | Sum holds → £0. Available £100 | £0 |
| 3 | £60 ≤ £100 ✔. Insert hold £60 | | £0 (A hasn't committed) |
| 4 | Commit | | **£60** |
| 5 | | £60 ≤ £100 ✔. Insert hold £60 | £60 |
| 6 | | Commit | **£120** |

Both decisions were correct **when they were made**, and together they're wrong. £120 is now held against £100.

`@Transactional` didn't help. Under the default **READ COMMITTED** isolation level, each transaction sees data other transactions have committed, but nothing stops two transactions reading the same data before either has written.

### Why optimistic locking can't see it

If you've met `@Version` before (the Core ledger tutorial uses it), you might reach for it here. It won't help, and the reason is worth understanding.

`@Version` protects a **row that two transactions both update**: the second `UPDATE … WHERE version = ?` matches zero rows and fails. In the table above, **no row is updated at all**. Each request reads a *sum* over many rows and then *inserts a new row*. There's no conflicting write for a version number to catch.

This pattern, where two transactions each read overlapping data, each make a decision, and each write *different* rows that together break a rule, is called **write skew**. It's the classic race that row versioning misses. (You'll see the proof in step 3: we tried it.)

### The fix: one lock per account

The rule you're protecting is about the **account**: "the holds on this account must not exceed its available balance". So every transaction that could change that must queue on something that belongs to the account. The account row is the natural choice:

```sql
SELECT * FROM account WHERE id = ? FOR UPDATE
```

`FOR UPDATE` locks the row. A second transaction asking for the same lock **waits** until the first commits, and then reads the new totals. The account row is being used as a **mutex**: you lock it not because you're going to change it, but because everyone who might break the account's rule agrees to take that lock first.

**Which row to lock matters.** Locking the **card** would feel natural, since the request arrives with a card token, but it would only serialise payments on *that card*. Two cards on the same account would still race each other. Lock the thing the invariant is about. (Exercise 1 tests this.)

> **Simplification:** this works because every hold for an account is created by this one service, in this one database. Real issuer platforms often keep available balances in a dedicated, very fast store (sometimes in memory, partitioned by account), but the principle is identical: **all changes to one account's available balance are serialised**, and different accounts proceed in parallel.

### Locks cost time

A lock turns parallel requests into a queue. For one person's current account that's fine: a few simultaneous payments wait a few milliseconds each. But remember guide 02: the scheme gives the issuer a deadline. So:

- **Hold the lock for as short a time as possible.** Lock as late as you can, do only the essential reads and the insert, and commit. Nothing slow (no network calls, no logging to a remote system) while the lock is held.
- **Bound the wait.** Databases have a **lock timeout**: H2 has a `LOCK_TIMEOUT` setting (which can be added to the JDBC URL), PostgreSQL has `lock_timeout`. A request that can't get the lock in time fails quickly instead of hanging, and the issuer can decline it or let stand-in processing take over.
- **Beware [hot accounts](../glossary.md#hot-account).** A large corporate card programme with thousands of cards on one account would queue every payment behind one lock. Real systems handle that with separate limits per card, or by splitting the balance across several sub-accounts.

### Reversals

Not every hold ends in clearing. A **reversal** is the merchant (through its acquirer) telling the issuer "release some or all of that authorisation":

- An online order is cancelled before it ships: **full reversal**.
- A hotel authorised £200 at check-in, but the final bill is £150: the hotel can send a **partial reversal** of £50, so the customer gets the money back in their available balance straight away, instead of waiting for clearing.

A reversal changes the held amount, so it changes the available balance, so **it must take the same account lock** as authorisation. That turns out to need some care (step 5).

> **Note on words:** an *authorisation* reversal releases a hold. It's a different thing from the *ledger* [reversal](../glossary.md#reversal) in the Core ledger tutorial, which cancels a posted journal entry with a mirror-image entry. No money has been posted yet, so there's nothing to mirror.

---

## Steps

### 1. A test that fires ten authorisations at once

Alice has £100 and no overdraft. Ten threads try to authorise £20 each **at the same instant**. The right answer is exactly five approvals, five declines, £100 held and £0 available.

Create `src/test/java/com/example/cards/authorisation/ConcurrentAuthorisationTest.java`:

```java
package com.example.cards.authorisation;

import com.example.cards.CardsIntegrationTest;
import com.example.cards.account.AccountBalances;
import com.example.cards.account.AccountService;
import com.example.cards.card.CardService;
import com.example.cards.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@CardsIntegrationTest
class ConcurrentAuthorisationTest {

    @Autowired AccountService accountService;
    @Autowired CardService cardService;
    @Autowired AuthorisationService authorisationService;

    @Test
    void tenSimultaneousAuthorisationsCannotOverspendTheAvailableBalance() throws Exception {
        Long accountId = accountService.open("Alice", Money.gbp("0.00")).getId();
        accountService.deposit(accountId, Money.gbp("100.00"));
        String token = cardService.issue(accountId).getToken();

        int threads = 10;
        CountDownLatch startingGun = new CountDownLatch(1);
        AtomicInteger approved = new AtomicInteger();
        AtomicInteger declined = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startingGun.await(); // every thread waits here...
                    try {
                        AuthorisationResult result = authorisationService.authorise(
                                token, Money.gbp("20.00"), "Corner Cafe", "5814");
                        if (result.decision().approved()) {
                            approved.incrementAndGet();
                        } else {
                            declined.incrementAndGet();
                        }
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

        AccountBalances balances = accountService.balances(accountId);
        System.out.printf("approved=%d declined=%d unexpected=%s held=%s available=%s%n",
                approved.get(), declined.get(),
                unexpected.stream().map(t -> t.getClass().getSimpleName()).toList(),
                balances.held(), balances.available());

        assertThat(unexpected).isEmpty();
        assertThat(approved.get()).isEqualTo(5);
        assertThat(declined.get()).isEqualTo(5);
        assertThat(balances.held()).isEqualTo(Money.gbp("100.00"));
        assertThat(balances.available()).isEqualTo(Money.gbp("0.00"));
    }
}
```

The moving parts:

- **`CountDownLatch startingGun = new CountDownLatch(1)`** makes the race reliable. Each thread is created and blocks on `await()`. Only when all ten are lined up does the main thread call `countDown()`, releasing them together. Without it, the first thread would often finish before the tenth had started, and the test would pass by luck.
- **`ExecutorService` in try-with-resources** (Java 19+): closing the pool waits for every task. `future.get(30, SECONDS)` re-throws anything that escaped a task, and turns a hang into a failure.
- **`AtomicInteger`** counters, and `synchronized` around the shared list, because ten threads update them at once.
- **The test class is not `@Transactional`.** Each `authorise(...)` call must run in its own real transaction on its own thread, exactly like concurrent requests from the scheme. A test-level transaction would only cover the main thread, and the worker threads couldn't even see the account it created.
- **The assertions check the invariant**, not just the counts: the held total and the available balance.

### 2. Watch it fail

Run `ConcurrentAuthorisationTest`. It fails. The output:

```
approved=10 declined=0 unexpected=[] held=GBP 200.00 available=GBP -100.00
```

```
expected: 5
 but was: 10
```

**All ten** were approved. £200 is held against an account that had £100, and the available balance is **−£100**: an overdraft nobody arranged, created by the bank's own authorisation system. Every one of those ten approvals is a promise to pay a merchant.

Run it again. On our runs it approved 10, then 10, then 9. The exact number changes with how the threads interleave, which is exactly what makes these bugs so hard to catch in production: they're rare, they look different every time, and sequential tests never find them.

### 3. Why `@Version` doesn't fix it

To prove the point from the Concepts section, we added a `version` column to `account` and `@Version private long version;` to `Account`, and ran the test again:

```
approved=10 declined=0 unexpected=[] held=GBP 200.00 available=GBP -100.00
```

No change at all. The account row is only ever *read* during authorisation, so its version never changes, and there's nothing for optimistic locking to detect. **You don't need to make this change.** If you tried it, remove it again (and delete `data/` or add a migration that drops the column, because Flyway will have recorded it).

### 4. Lock the account

Add a locking query to `account/AccountRepository.java`:

```java
package com.example.cards.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /** SELECT ... FOR UPDATE: other transactions wanting this row wait until we commit. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(Long id);
}
```

`@Lock(PESSIMISTIC_WRITE)` makes Hibernate add `FOR UPDATE` to the SQL. It goes on a query method, which is why there's a `@Query` here: the inherited `findById` can't be annotated.

Now change **one line** in `AuthorisationService.authorise`. Replace step 2:

```java
        // 2. Lock the account. Every authorisation for this account (from any of its
        //    cards) now queues here, so the facts below can't change until we commit.
        Account account = accounts.findByIdForUpdate(card.getAccount().getId()).orElseThrow();

        // 3. Gather the facts: its holds, and today's spending on this card
```

(and renumber the remaining comments to 4 and 5).

The lock is taken **before** the holds are summed, and held until `authorise` commits, after the new hold is inserted. Any other authorisation for the same account waits at that line, then sums the holds *including* the one just committed.

Run `ConcurrentAuthorisationTest`:

```
approved=5 declined=5 unexpected=[] held=GBP 100.00 available=GBP 0.00
```

✅ Green, every time. Run it several times to be sure. A concurrency test has to pass on **every** run, not just usually.

Run `AuthorisationQueryCountTest` too. It still says **5**: the locking read replaced the plain one. With `spring.jpa.show-sql=true` you can see the difference:

```
select a1_0.id, … from account a1_0 where a1_0.id=? for update
```

Why does the card lookup happen **without** a lock, before the account lock? Because the card row isn't what needs protecting: its status and limits change rarely, and the decision only needs a consistent view of the *account's* holds. Keeping the card read outside the account's queue means the lock is held for slightly less time.

> **Sidebar: H2 vs PostgreSQL.** Both support `SELECT … FOR UPDATE`, and this behaves the same way on both. PostgreSQL also offers `FOR UPDATE NOWAIT` (fail immediately if the row is locked) and `SKIP LOCKED` (skip locked rows, useful for job queues, not for this). In JPA you can ask for a lock timeout with the `jakarta.persistence.lock.timeout` query hint, but how (and whether) it's honoured depends on the database and Hibernate dialect, so test it on the database you actually run.

### 5. Reversals

A reversal releases some or all of a hold. Start with two exceptions. `authorisation/AuthorisationStateException.java`:

```java
package com.example.cards.authorisation;

/** The authorisation exists, but its status doesn't allow what was asked. */
public class AuthorisationStateException extends RuntimeException {

    public AuthorisationStateException(Long id, AuthorisationStatus status, String action) {
        super("Authorisation %d is %s, so it cannot be %s".formatted(id, status, action));
    }
}
```

`authorisation/InvalidReversalException.java`:

```java
package com.example.cards.authorisation;

public class InvalidReversalException extends RuntimeException {

    public InvalidReversalException(String message) {
        super(message);
    }
}
```

The rule belongs in the entity. Add to `Authorisation`:

```java
    /**
     * The merchant gives back some or all of the held amount: a cancelled order,
     * or a final bill lower than the pre-authorisation.
     */
    public void reverse(Money amount) {
        if (status != AuthorisationStatus.PENDING) {
            throw new AuthorisationStateException(id, status, "reversed");
        }
        Money held = getHeldAmount();
        if (held.isLessThan(amount)) {
            throw new InvalidReversalException(
                    "Cannot reverse %s: only %s is held".formatted(amount, held));
        }
        Money remaining = held.minus(amount);
        this.heldAmount = remaining.amount();
        if (remaining.isZero()) {
            this.status = AuthorisationStatus.REVERSED;
        }
    }
```

A partial reversal lowers `heldAmount` and leaves the authorisation `PENDING`, because the rest is still waiting to clear. Releasing the last penny makes it `REVERSED`.

Now the service. Here's the subtle part. The reversal arrives with an **authorisation id**, but the lock you need is on the **account**. The obvious code is:

```java
// DON'T: loads the authorisation BEFORE taking the lock
Authorisation authorisation = authorisations.findById(authorisationId).orElseThrow();
accounts.findByIdForUpdate(authorisation.getAccount().getId()).orElseThrow();
authorisation.reverse(amount);
```

It takes the lock, so it looks safe. But the authorisation was read **before** the lock. If another reversal of the same authorisation committed in between, this transaction is still holding the old `heldAmount` in memory. Hibernate doesn't refresh an entity just because you locked something else. The check runs against stale data, and the update overwrites the other reversal's change. (Exercise 2 shows exactly how badly this goes wrong.)

The fix is a pattern you'll use again: **read just the id, lock, then load.** Add to `AuthorisationRepository` (import `java.util.Optional`):

```java
    /** Just the account id, so callers can lock the account BEFORE loading the authorisation. */
    @Query("select a.account.id from Authorisation a where a.id = :id")
    Optional<Long> findAccountIdById(Long id);
```

And to `AuthorisationService`:

```java
    /** Releases some or all of a hold. Locks the account first, like everything that changes a hold. */
    @Transactional
    public Authorisation reverse(Long authorisationId, Money amount) {
        Long accountId = authorisations.findAccountIdById(authorisationId)
                .orElseThrow(() -> new AuthorisationNotFoundException(authorisationId));
        accounts.findByIdForUpdate(accountId).orElseThrow();
        // Loaded AFTER the lock, so we see the latest committed status and held amount
        Authorisation authorisation = authorisations.findById(authorisationId).orElseThrow();
        authorisation.reverse(amount);
        return authorisation;
    }
```

The first query returns a plain `Long`, not an entity, so nothing goes into the persistence context. The authorisation is loaded for the first time **after** the lock, so it's guaranteed to reflect everything committed before this transaction got the lock. (An authorisation never changes account, so reading the account id without a lock is safe.)

The rule for the rest of this tutorial: **anything that changes a hold takes the account lock first, then loads what it's going to change.**

Add the endpoint to `AuthorisationController`:

```java
    @PostMapping("/authorisations/{id}/reversals")
    public AuthorisationResponse reverse(@PathVariable Long id, @Valid @RequestBody ReversalRequest request) {
        return AuthorisationResponse.from(
                authorisationService.reverse(id, Money.gbp(request.amount().toPlainString())));
    }
```

with its request record:

```java
    public record ReversalRequest(@NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount) {
    }
```

And map the two exceptions in `ApiExceptionHandler`:

```java
    @ExceptionHandler(AuthorisationStateException.class)
    ProblemDetail handle(AuthorisationStateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Authorisation not pending");
        return problem;
    }

    @ExceptionHandler(InvalidReversalException.class)
    ProblemDetail handle(InvalidReversalException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setTitle("Invalid reversal");
        return problem;
    }
```

**`409` or `422`?** Reversing something that's already reversed or cleared conflicts with the authorisation's **current state** (`409 Conflict`): it might have been fine a minute ago. Reversing more than is held breaks a rule **in the request itself** (`422 Unprocessable Content`).

### 6. Reversal tests

Create `src/test/java/com/example/cards/authorisation/ReversalApiTest.java`:

```java
package com.example.cards.authorisation;

import com.example.cards.CardsIntegrationTest;
import com.example.cards.account.AccountService;
import com.example.cards.card.CardService;
import com.example.cards.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

@CardsIntegrationTest
class ReversalApiTest {

    @Autowired MockMvcTester mvc;
    @Autowired AccountService accountService;
    @Autowired CardService cardService;
    @Autowired AuthorisationService authorisationService;

    Long accountId;
    Long authorisationId;

    @BeforeEach
    void holdFiftyPounds() {
        accountId = accountService.open("Alice", Money.gbp("0.00")).getId();
        accountService.deposit(accountId, Money.gbp("100.00"));
        String token = cardService.issue(accountId).getToken();
        authorisationId = authorisationService
                .authorise(token, Money.gbp("50.00"), "Hotel Example", "7011")
                .authorisationId();
    }

    @Test
    void partialReversalReleasesPartOfTheHold() {
        MvcTestResult response = reverse(authorisationId, "20.00");

        assertThat(response).hasStatus(HttpStatus.OK);
        assertThat(response).bodyJson().extractingPath("$.heldAmount.amount").isEqualTo("30.00");
        assertThat(response).bodyJson().extractingPath("$.status").isEqualTo("PENDING");
        assertThat(accountService.balances(accountId).available()).isEqualTo(Money.gbp("70.00"));
    }

    @Test
    void fullReversalReleasesEverything() {
        MvcTestResult response = reverse(authorisationId, "50.00");

        assertThat(response).bodyJson().extractingPath("$.status").isEqualTo("REVERSED");
        assertThat(accountService.balances(accountId).available()).isEqualTo(Money.gbp("100.00"));
    }

    @Test
    void cannotReverseMoreThanIsHeld() {
        MvcTestResult response = reverse(authorisationId, "50.01");

        assertThat(response).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Invalid reversal");
    }

    @Test
    void cannotReverseTwice() {
        reverse(authorisationId, "50.00");

        MvcTestResult response = reverse(authorisationId, "1.00");

        assertThat(response).hasStatus(HttpStatus.CONFLICT);
        assertThat(response).bodyJson().extractingPath("$.detail")
                .isEqualTo("Authorisation %d is REVERSED, so it cannot be reversed".formatted(authorisationId));
    }

    private MvcTestResult reverse(Long id, String amount) {
        return mvc.post().uri("/authorisations/{id}/reversals", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount": "%s"}
                        """.formatted(amount))
                .exchange();
    }
}
```

Each test asserts on the **available balance**, not just the response. The whole point of a reversal is giving the customer their spending power back.

### 7. Try it

Add to `requests.http`:

```http
### Alice checks into a hotel: £120 pre-authorisation
POST http://localhost:8080/authorisations
Content-Type: application/json

{"cardToken": "{{cardToken}}", "amount": "120.00", "merchantName": "Premier Inn", "merchantCategoryCode": "7011"}

### The hotel releases £40 (use the authorisationId from the response above)
POST http://localhost:8080/authorisations/5/reversals
Content-Type: application/json

{"amount": "40.00"}

### Trying to release more than is held (422)
POST http://localhost:8080/authorisations/5/reversals
Content-Type: application/json

{"amount": "500.00"}
```

Your authorisation id may differ, depending on how many requests you sent in guide 02. After the £40 partial reversal:

```json
{
  "id": 5,
  "amount": { "amount": "120.00", "currency": "GBP" },
  "heldAmount": { "amount": "80.00", "currency": "GBP" },
  "merchantName": "Premier Inn",
  "merchantCategoryCode": "7011",
  "status": "PENDING",
  …
}
```

The original `amount` is kept, and only `heldAmount` has changed. Asking for £500 gives:

```json
{"detail":"Cannot reverse GBP 500.00: only GBP 80.00 is held","instance":"/authorisations/5/reversals","status":422,"title":"Invalid reversal"}
```

Reverse the remaining £80, then try £1 more:

```json
{"detail":"Authorisation 5 is REVERSED, so it cannot be reversed","instance":"/authorisations/5/reversals","status":409,"title":"Authorisation not pending"}
```

---

## Your turn

### Exercise 1: two cards, one balance

Alice and Bob have a joint account with £100 and a card each. Add a second test to `ConcurrentAuthorisationTest`: ten threads, alternating between the two cards, each authorising £20 at the same instant. Exactly five should be approved and £0 should remain available.

Then answer: **why would this test catch a version of the fix that locked the card row instead of the account row?**

<details>
<summary>Solution</summary>

```java
    @Test
    void twoCardsOnOneAccountShareTheSameAvailableBalance() throws Exception {
        Long accountId = accountService.open("Alice and Bob", Money.gbp("0.00")).getId();
        accountService.deposit(accountId, Money.gbp("100.00"));
        String aliceCard = cardService.issue(accountId).getToken();
        String bobCard = cardService.issue(accountId).getToken();

        CountDownLatch startingGun = new CountDownLatch(1);
        AtomicInteger approved = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                String token = i % 2 == 0 ? aliceCard : bobCard;
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    if (authorisationService.authorise(token, Money.gbp("20.00"), "Corner Cafe", "5814")
                            .decision().approved()) {
                        approved.incrementAndGet();
                    }
                    return null;
                }));
            }
            startingGun.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(approved.get()).isEqualTo(5);
        assertThat(accountService.balances(accountId).available()).isEqualTo(Money.gbp("0.00"));
    }
```

With your account lock, this passes every time.

A card lock only queues payments **on the same card**. Alice's payments would queue behind each other and Bob's behind each other, but an Alice payment and a Bob payment would run side by side, both read the same holds total, and both approve. We tried it: with the lock moved to the card, this test passed on one run and failed on the next (`expected: 5 but was: 6`). That inconsistency is typical of race conditions, and it's why you lock what the invariant is about: the account.

</details>

### Exercise 2: reversals race too

Write `ConcurrentReversalTest`: authorise £50, then have ten threads each try to reverse £10 at the same moment. Exactly five should succeed, five should be refused (`InvalidReversalException` or `AuthorisationStateException`), the authorisation should end up `REVERSED`, and the available balance should be back to £100.

Then temporarily swap `reverse` back to the "DON'T" version from step 5 (load, then lock) and run it again. What happens?

<details>
<summary>Solution</summary>

`src/test/java/com/example/cards/authorisation/ConcurrentReversalTest.java`:

```java
package com.example.cards.authorisation;

import com.example.cards.CardsIntegrationTest;
import com.example.cards.account.AccountService;
import com.example.cards.card.CardService;
import com.example.cards.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@CardsIntegrationTest
class ConcurrentReversalTest {

    @Autowired AccountService accountService;
    @Autowired CardService cardService;
    @Autowired AuthorisationService authorisationService;

    @Test
    void simultaneousPartialReversalsCannotReleaseMoreThanWasHeld() throws Exception {
        Long accountId = accountService.open("Alice", Money.gbp("0.00")).getId();
        accountService.deposit(accountId, Money.gbp("100.00"));
        String token = cardService.issue(accountId).getToken();
        Long authorisationId = authorisationService
                .authorise(token, Money.gbp("50.00"), "Hotel Example", "7011").authorisationId();

        CountDownLatch startingGun = new CountDownLatch(1);
        AtomicInteger reversed = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    try {
                        authorisationService.reverse(authorisationId, Money.gbp("10.00"));
                        reversed.incrementAndGet();
                    } catch (InvalidReversalException | AuthorisationStateException e) {
                        refused.incrementAndGet();
                    }
                    return null;
                }));
            }
            startingGun.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        System.out.printf("reversed=%d refused=%d available=%s%n", reversed.get(), refused.get(),
                accountService.balances(accountId).available());
        assertThat(reversed.get()).isEqualTo(5);
        assertThat(refused.get()).isEqualTo(5);
        assertThat(authorisationService.get(authorisationId).getStatus()).isEqualTo(AuthorisationStatus.REVERSED);
        assertThat(accountService.balances(accountId).available()).isEqualTo(Money.gbp("100.00"));
    }
}
```

With "read the id, lock, then load":

```
reversed=5 refused=5 available=GBP 100.00
```

With the load-then-lock version:

```
reversed=10 refused=0 available=GBP 60.00
```

**All ten "succeeded"**, yet only £10 was actually released. Every thread loaded the authorisation with £50 held, queued for the lock, and then (holding a stale copy) calculated £50 − £10 = £40 and wrote it. Nine of those writes overwrote each other: classic **lost updates**. The merchant was told ten times that its reversal worked, and the customer only got £10 back. Taking the lock didn't help, because the data was read before the lock was taken.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 39, Failures: 0, Errors: 0, Skipped: 0
```

Run it two or three times. The concurrency tests must pass **every** time.

New files since guide 02:

```
src/main/java/com/example/cards/authorisation/
├── AuthorisationStateException.java
└── InvalidReversalException.java
src/test/java/com/example/cards/authorisation/
├── ConcurrentAuthorisationTest.java
├── ConcurrentReversalTest.java
└── ReversalApiTest.java
```

Modified: `AccountRepository.java` (`findByIdForUpdate`), `Authorisation.java` (`reverse`), `AuthorisationRepository.java` (`findAccountIdById`), `AuthorisationService.java` (the lock, `reverse`), `AuthorisationController.java`, `ApiExceptionHandler.java`, `requests.http`.

---

## Common errors

**`ConcurrentAuthorisationTest` fails with `expected: 5 but was: 10` (or 9, or 8)**
The account isn't locked before the holds are summed. Check that `authorise` uses `findByIdForUpdate`, not `findById`, and that it happens **before** `sumHeldAmount`.

**`InvalidDataAccessApiUsageException: No active transaction`**
A `@Lock` query was called with no transaction running, for example straight from a test or from a method that isn't `@Transactional`. A row lock only means something inside a transaction (it's released at commit), so Spring refuses. Call it from a `@Transactional` service method, and call that method **through the Spring bean**, not as `this.method(...)` from inside the same class, which bypasses the transactional proxy.

**`ConcurrentReversalTest` says `reversed=10` and `expected: 5 but was: 10`**
The authorisation is loaded before the account lock is taken. See Exercise 2: read the account id, lock, then load.

**The concurrency test passes even without the lock**
The threads aren't really overlapping. Check every task calls `startingGun.await()` **before** doing any work, and `countDown()` is only called after all tasks are submitted.

**Worker threads can't see the account the test created**
The test class or method is `@Transactional`. Its data hasn't been committed, so other threads can't see it. Concurrency tests must not be `@Transactional`.

**A test hangs, then fails with `TimeoutException`**
A thread is waiting for something that never happens: a lock held by a paused debugger, or a latch that was never counted down. `future.get(30, SECONDS)` is what turns the hang into a failure instead of a frozen build.

---

## Recap

- **Simultaneous authorisations on one account are normal**: joint accounts, multiple cards, batches, fraud. An issuer that approves more than is available pays for it.
- **`@Transactional` gives atomicity, not mutual exclusion.** Under READ COMMITTED, two transactions can read the same holds total and both approve.
- **Write skew** (read overlapping data, write different rows) can't be detected by `@Version`, because no row is updated twice.
- **Lock the row the invariant is about.** `SELECT … FOR UPDATE` on the **account** serialises every change to its available balance, whichever card it came from.
- **Locks cost latency.** Hold them briefly, bound the wait with a lock timeout, and watch for hot accounts.
- **Reversals** release some or all of a hold, and must take the same lock.
- **Read the id, lock, then load.** Never read the data you're about to change before taking the lock that protects it.

### Interview angle

<details>
<summary>"Two card payments for the same account arrive at the same moment. How do you stop them overspending?"</summary>

Serialise authorisations per account. In a relational database, lock the account row (`SELECT … FOR UPDATE`, or `@Lock(PESSIMISTIC_WRITE)` in JPA) at the start of the authorisation transaction, then compute the available balance (ledger balance minus holds, plus overdraft), decide, and insert the hold before committing. Lock the account, not the card, because several cards can share one account. Mention that `@Transactional` alone isn't enough under READ COMMITTED, and keep the locked section short because authorisation has a latency budget.

</details>

<details>
<summary>"Why wouldn't optimistic locking with a version column work here?"</summary>

Because nothing updates the account row. Each authorisation reads a sum of holds and inserts a new hold row. Optimistic locking only detects two transactions updating the same row, and here they write different rows. That's write skew. You could make it work by deliberately updating the account row on every authorisation (for example, keeping a running held total on it, with a version), which effectively turns it back into a single contended row, or by using SERIALIZABLE isolation and retrying. An explicit pessimistic lock is simpler and predictable.

</details>

<details>
<summary>"What's the bug in 'load the entity, then lock'?"</summary>

The entity was read before the lock, so it can be stale by the time the lock is granted. The ORM won't refresh it, so the code checks and updates old values and overwrites changes committed in between (lost updates). Lock first, then load whatever you're going to change. If you only have a child id, fetch the parent's id with a cheap projection query, lock the parent, and then load the child.

</details>

<details>
<summary>"What's the difference between an authorisation reversal and a refund?"</summary>

An authorisation reversal happens before clearing: it releases some or all of a hold, and no money has moved. A refund happens after the payment has cleared: the merchant sends money back, which arrives as a new credit to the customer's account through clearing. Customers see the difference too: a reversal makes a pending transaction shrink or disappear, and a refund appears as a separate credit, often days later.

</details>

### Further reading

- [PostgreSQL docs: Explicit Locking](https://www.postgresql.org/docs/current/explicit-locking.html): row-level locks, `NOWAIT` and `SKIP LOCKED`
- [PostgreSQL docs: Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html): includes a worked write-skew example under Serializable isolation
- [Spring Data JPA reference: Locking](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)
- [Martin Kleppmann, *Designing Data-Intensive Applications*](https://dataintensive.net/), chapter 7: the clearest explanation of write skew and phantoms you'll find

**Next:** [04: Clearing and the ledger](04-clearing-and-the-ledger.md). The merchant's presentment arrives, and money finally leaves the account.
