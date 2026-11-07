# 03: Transfers and Concurrency

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- How a transfer between two customers is recorded
- Why `@Transactional` alone does **not** stop two requests from spending the same money
- The **lost update** problem, seen for real in a failing test
- **Optimistic locking** with `@Version`: what it protects, and what it costs
- **Pessimistic locking** with `SELECT … FOR UPDATE` through Spring Data's `@Lock`
- **Deadlocks**: how two perfectly reasonable transfers can freeze each other, and the lock-ordering rule that prevents it
- **Hot accounts**, and why the bank's own cash account is a bottleneck
- How to write a **multi-threaded test** that reliably reproduces a race condition

This is the guide where a ledger stops being a CRUD app. By the end, ten simultaneous transfers can't spend the same £100 twice, and you'll be able to explain why in an interview.

---

## Concepts

### How it works in a real bank

A current account is hit from many directions at once: card payments, Faster Payments, Direct Debits, standing orders, the customer's own app. Many of these arrive within milliseconds of each other. If two of them read the balance at the same moment, both see enough money, and both go through, the customer has spent money they didn't have, and the bank has an unarranged [overdraft](../glossary.md#overdraft) it never agreed to. This is **double spending**, and preventing it is one of a core banking system's most important jobs.

Banks prevent it by making sure that **updates to one account happen one at a time**. Different systems do this differently: database row locks, a single-threaded "actor" per account, or routing all of an account's traffic through one partition of a queue. The idea is the same in every case: **serialise per account**, and keep everything else parallel.

### `@Transactional` is not enough

`@Transactional` gives you **atomicity**: all of the transaction's writes commit, or none do. It doesn't stop two transactions from **interleaving**. Under the default **READ COMMITTED** isolation level (the default on H2, PostgreSQL and Oracle), each transaction sees other transactions' data as soon as they commit, but nothing stops two transactions from reading the same row *before* either has written it.

Here's what happens when two £60 transfers leave an account holding £100 at the same moment:

| Time | Transaction A | Transaction B | Balance in the database |
|---|---|---|---|
| 1 | Read balance → £100 | | £100 |
| 2 | | Read balance → £100 | £100 |
| 3 | £100 ≥ £60 ✔. Set balance = £40 | | £100 (A hasn't committed) |
| 4 | Commit | | **£40** |
| 5 | | £100 ≥ £60 ✔. Set balance = £40 | £40 |
| 6 | | Commit | **£40** |

Two lots of £60 have left the account. Both journal entries exist, so the derived balance is **−£20**. The cached balance says **£40**, because B overwrote A's update with a value calculated from a stale read. That's a **lost update**, and the overdraft check didn't help at all, because each transaction checked a balance that was already out of date.

### Fix 1: optimistic locking

Add a `version` column to the row. Every update says "set the new values **and** increment the version, but only if the version is still the one I read":

```sql
UPDATE account SET balance = 40.00, version = 8 WHERE id = 2 AND version = 7
```

If another transaction got there first, the version is now 8, so the `WHERE` matches **zero rows**. Hibernate notices and throws `ObjectOptimisticLockingFailureException`, and the losing transaction rolls back. Nothing is overwritten.

It's called *optimistic* because it doesn't block anyone. It assumes conflicts are rare and detects them at the end. That's cheap when conflicts *are* rare. When they're common, most transactions fail and have to be retried.

### Fix 2: pessimistic locking

Lock the row **when you read it**:

```sql
SELECT * FROM account WHERE id = 2 FOR UPDATE
```

Any other transaction that tries to `SELECT … FOR UPDATE` the same row **waits** until the first one commits or rolls back, and then reads the *new* balance. The check-then-write sequence is now truly one at a time for that account.

It's called *pessimistic* because it assumes conflict and prevents it up front. The cost is waiting, plus a new risk…

### Deadlock, and lock ordering

Alice sends Bob £1 at the same moment Bob sends Alice £1:

| Transaction A (Alice → Bob) | Transaction B (Bob → Alice) |
|---|---|
| Lock Alice ✔ | Lock Bob ✔ |
| Lock Bob… *waits for B* | Lock Alice… *waits for A* |

Each waits for the other forever. The database spots this cycle (**deadlock detection**), picks a victim, and rolls it back with an error.

The standard fix is simple: **always take locks in the same global order**, for example by account id, lowest first. Then both transactions try to lock the lower id first. One gets it, and the other waits *before holding anything*, so no cycle can form.

### Hot accounts

Every deposit and withdrawal touches the bank's single `CASH` account. Once you lock it, all cash operations across all customers queue behind each other on that one row. A row like that is called a [hot account](../glossary.md#hot-account). Real ledgers handle hot accounts by splitting them into several sub-accounts (and adding them up when needed), by batching their postings, or by not caching their balance at all and deriving it on demand. For a teaching ledger, locking it is correct and simple. Just know that it's the first thing that would break at scale.

---

## Steps

### 1. A naive transfer

Create `transfer/InvalidTransferException.java`:

```java
package com.example.ledger.transfer;

public class InvalidTransferException extends RuntimeException {

    public InvalidTransferException(String message) {
        super(message);
    }
}
```

Create `transfer/TransferService.java`. This first version has the same read-check-write shape as `withdraw`, deliberately:

```java
package com.example.ledger.transfer;

import com.example.ledger.account.Account;
import com.example.ledger.account.AccountService;
import com.example.ledger.cash.InsufficientFundsException;
import com.example.ledger.journal.JournalEntry;
import com.example.ledger.journal.LedgerService;
import com.example.ledger.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final AccountService accountService;
    private final LedgerService ledger;

    public TransferService(AccountService accountService, LedgerService ledger) {
        this.accountService = accountService;
        this.ledger = ledger;
    }

    @Transactional
    public JournalEntry transfer(Long fromId, Long toId, Money amount) {
        if (fromId.equals(toId)) {
            throw new InvalidTransferException("Cannot transfer from account %d to itself".formatted(fromId));
        }
        Account from = accountService.get(fromId);
        Account to = accountService.get(toId);
        if (from.getBalance().isLessThan(amount)) {
            throw new InsufficientFundsException(fromId, from.getBalance(), amount);
        }
        JournalEntry entry = JournalEntry.builder("Transfer from %d to %d".formatted(fromId, toId))
                .debit(from, amount)
                .credit(to, amount)
                .build();
        return ledger.post(entry);
    }
}
```

Both sides are customer accounts, which are liabilities. So the payer is **debited** (the bank owes them less) and the payee is **credited** (the bank owes them more), exactly as in the guide 02 table.

Create `transfer/TransferController.java`:

```java
package com.example.ledger.transfer;

import com.example.ledger.journal.JournalEntry;
import com.example.ledger.journal.JournalEntryResponse;
import com.example.ledger.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<JournalEntryResponse> transfer(@Valid @RequestBody TransferRequest request) {
        JournalEntry entry = transferService.transfer(
                request.fromAccountId(), request.toAccountId(),
                Money.gbp(request.amount().toPlainString()));
        return ResponseEntity.created(URI.create("/journal-entries/" + entry.getId()))
                .body(JournalEntryResponse.from(entry));
    }

    public record TransferRequest(
            @NotNull Long fromAccountId,
            @NotNull Long toAccountId,
            @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount) {
    }
}
```

Map the new exception in `ApiExceptionHandler`. A transfer to yourself is understood but not allowed, so it's a `422`:

```java
@ExceptionHandler(InvalidTransferException.class)
ProblemDetail handle(InvalidTransferException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
    problem.setTitle("Invalid transfer");
    return problem;
}
```

Add to `requests.http`:

```http
### Open Bob (account 3)
POST http://localhost:8080/accounts
Content-Type: application/json

{"name": "Bob"}

### Alice pays Bob £25
POST http://localhost:8080/transfers
Content-Type: application/json

{"fromAccountId": 2, "toAccountId": 3, "amount": "25.00"}
```

### 2. Ordinary transfer tests

Create `src/test/java/com/example/ledger/transfer/TransferApiTest.java`:

```java
package com.example.ledger.transfer;

import com.example.ledger.LedgerIntegrationTest;
import com.example.ledger.account.AccountService;
import com.example.ledger.cash.CashService;
import com.example.ledger.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

@LedgerIntegrationTest
class TransferApiTest {

    @Autowired MockMvcTester mvc;
    @Autowired AccountService accountService;
    @Autowired CashService cashService;

    Long alice;
    Long bob;

    @BeforeEach
    void openAccounts() {
        alice = accountService.open("Alice").getId();
        bob = accountService.open("Bob").getId();
        cashService.deposit(alice, Money.gbp("50.00"));
    }

    @Test
    void movesMoneyBetweenAccounts() {
        MvcTestResult response = transfer(alice, bob, "20.00");

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(balanceOf(alice)).isEqualTo(Money.gbp("30.00"));
        assertThat(balanceOf(bob)).isEqualTo(Money.gbp("20.00"));
    }

    @Test
    void refusesToOverdraw() {
        assertThat(transfer(alice, bob, "50.01")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(balanceOf(alice)).isEqualTo(Money.gbp("50.00"));
        assertThat(balanceOf(bob)).isEqualTo(Money.gbp("0.00"));
    }

    @Test
    void refusesATransferToTheSameAccount() {
        MvcTestResult response = transfer(alice, alice, "1.00");

        assertThat(response).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Invalid transfer");
    }

    @Test
    void returns404ForAnUnknownAccount() {
        assertThat(transfer(alice, 999_999L, "1.00")).hasStatus(HttpStatus.NOT_FOUND);
    }

    private MvcTestResult transfer(Long from, Long to, String amount) {
        return mvc.post().uri("/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fromAccountId": %d, "toAccountId": %d, "amount": "%s"}
                        """.formatted(from, to, amount))
                .exchange();
    }

    private Money balanceOf(Long id) {
        return accountService.get(id).getBalance();
    }
}
```

Run it. All four pass. Each request runs on its own, one after another, so the naive service looks perfectly correct. That's the trap: **sequential tests can't find concurrency bugs.**

### 3. A test that fires ten transfers at once

Alice has £100. Ten threads each try to send Bob £20 **at the same instant**. The correct outcome is exactly five successes, five "insufficient funds" refusals, Alice at £0.00, and cached balances that match the postings.

Create `src/test/java/com/example/ledger/transfer/ConcurrentTransferTest.java`:

```java
package com.example.ledger.transfer;

import com.example.ledger.LedgerIntegrationTest;
import com.example.ledger.account.Account;
import com.example.ledger.account.AccountService;
import com.example.ledger.cash.CashService;
import com.example.ledger.cash.InsufficientFundsException;
import com.example.ledger.journal.LedgerService;
import com.example.ledger.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@LedgerIntegrationTest
class ConcurrentTransferTest {

    @Autowired AccountService accountService;
    @Autowired CashService cashService;
    @Autowired TransferService transferService;
    @Autowired LedgerService ledger;

    @Test
    void tenSimultaneousTransfersCannotSpendTheSameMoneyTwice() throws Exception {
        Long alice = accountService.open("Alice").getId();
        Long bob = accountService.open("Bob").getId();
        cashService.deposit(alice, Money.gbp("100.00"));

        int threads = 10;
        CountDownLatch startingGun = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startingGun.await(); // every thread waits here...
                    try {
                        transferService.transfer(alice, bob, Money.gbp("20.00"));
                        succeeded.incrementAndGet();
                    } catch (InsufficientFundsException e) {
                        refused.incrementAndGet();
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
                future.get();
            }
        }

        Account aliceNow = accountService.get(alice);
        Account bobNow = accountService.get(bob);
        System.out.printf("succeeded=%d refused=%d unexpected=%s%n", succeeded.get(), refused.get(),
                unexpected.stream().map(t -> t.getClass().getSimpleName()).toList());
        System.out.printf("alice cached=%s derived=%s | bob cached=%s derived=%s%n",
                aliceNow.getBalance(), ledger.derivedBalance(aliceNow),
                bobNow.getBalance(), ledger.derivedBalance(bobNow));

        assertThat(unexpected).isEmpty();
        assertThat(succeeded.get()).isEqualTo(5);
        assertThat(refused.get()).isEqualTo(5);
        assertThat(aliceNow.getBalance()).isEqualTo(Money.gbp("0.00"));
        assertThat(ledger.derivedBalance(aliceNow)).isEqualTo(Money.gbp("0.00"));
        assertThat(bobNow.getBalance()).isEqualTo(ledger.derivedBalance(bobNow));
    }
}
```

The moving parts:

- **`CountDownLatch startingGun = new CountDownLatch(1)`** is the trick that makes the race reliable. Each thread is created and then blocks on `await()`. Only when all ten are lined up does the main thread call `countDown()`, releasing them together. Without it, the first thread would often finish before the tenth had even started, and the test would pass by luck.
- **`ExecutorService` in try-with-resources** (Java 19+): closing the pool waits for every task to finish. `future.get()` re-throws anything unexpected that escaped a task.
- **`AtomicInteger`** counters, and `synchronized` around the shared list, because ten threads update them at once. (A plain `int++` would itself be a lost-update bug, the same one you're testing for.)
- **The test class is *not* `@Transactional`.** That's deliberate. Each `transferService.transfer(...)` call runs in its own real transaction on its own thread, exactly as concurrent HTTP requests would. A test-level transaction would only cover the main thread, and the worker threads couldn't see its uncommitted data.
- **The `printf`s** are there so you can *see* the damage in the test output. Keep them while learning.

### 4. Watch it fail

Run `ConcurrentTransferTest`. It fails. In the Run window's output you'll see something like:

```
succeeded=10 refused=0 unexpected=[]
alice cached=GBP 60.00 derived=GBP -100.00 | bob cached=GBP 40.00 derived=GBP 200.00
```

```
expected: 5
 but was: 10
```

Read those numbers slowly:

- **All ten transfers succeeded.** £200 left an account that held £100.
- **The postings say Alice is at −£100**, an overdraft the bank never agreed to. Bob received £200.
- **The cached balances say £60 and £40.** Most updates were **lost**, each overwritten by a transaction that had read a stale balance.
- The ledger no longer even agrees with itself. The guide 02 invariant, cached = derived, is broken.

Run it a few more times. The cached numbers change from run to run (£60, £80, …), because they depend on exactly how the threads interleave. That's what makes race conditions so dangerous: in production they show up rarely and look different every time.

Your `withdraw` method has exactly the same bug.

### 5. Fix 1: optimistic locking with `@Version`

Create `src/main/resources/db/migration/V3__add_account_version.sql`:

```sql
ALTER TABLE account ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
```

`DEFAULT 0` gives existing rows (including `CASH` and any accounts already in your `data/` database) a starting version.

In `Account.java`, add the field (import `jakarta.persistence.Version`):

```java
    @Version
    private long version;
```

That's all. Hibernate now adds `AND version = ?` to every `UPDATE` of an account and increments the version. Run `ConcurrentTransferTest` again:

```
succeeded=2 refused=0 unexpected=[ObjectOptimisticLockingFailureException, ObjectOptimisticLockingFailureException, … (8 in total)]
alice cached=GBP 60.00 derived=GBP 60.00 | bob cached=GBP 40.00 derived=GBP 40.00
```

It still fails, but look at what changed:

- ✅ **The data is correct.** Cached matches derived for both accounts. No update was lost and there was no overdraft.
- ❌ **The outcome is wrong.** Only two transfers went through. Eight failed with `ObjectOptimisticLockingFailureException`, although Alice had enough money for three more. Their only crime was colliding with a transaction that committed first.

The exception message shows the guard in action:

```
Unexpected row count (expected row count 1 but was 0)
[update account set balance=?, … version=? where id=? and version=?]
```

Optimistic locking turned "silently corrupt the ledger" into "fail safely". That's a huge improvement, but under heavy contention on one account it rejects good transactions. To use it on its own, you'd catch the exception and **retry** the whole transaction (Spring Framework 7 has `@Retryable` built in for this). That works when collisions are occasional. For one account hammered by many requests at once, it's the wrong tool.

**Keep `@Version` anyway.** It costs almost nothing, and it's a safety net for any code path that forgets to lock properly.

### 6. Fix 2: pessimistic locking

Add a locking query to `AccountRepository`:

```java
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
...

    /** SELECT ... FOR UPDATE: other transactions wanting this row wait until we commit. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(Long id);
```

`@Lock(PESSIMISTIC_WRITE)` makes Hibernate add `FOR UPDATE` to the SQL. It goes on a query method, which is why a `@Query` is needed here: `findById` itself can't be annotated.

Expose it from `AccountService`:

```java
    /** Loads and locks the account until the surrounding transaction ends. */
    @Transactional
    public Account getForUpdate(Long id) {
        return accounts.findByIdForUpdate(id).orElseThrow(() -> new AccountNotFoundException(id));
    }
```

A row lock lasts until the **transaction** ends. Because `getForUpdate` joins the caller's transaction, the lock is held until `TransferService.transfer` commits, which is what you want: through the check, the postings and the balance updates.

In `TransferService.transfer`, use it:

```java
        Account from = accountService.getForUpdate(fromId);
        Account to = accountService.getForUpdate(toId);
```

Run `ConcurrentTransferTest`:

```
succeeded=5 refused=5 unexpected=[]
alice cached=GBP 0.00 derived=GBP 0.00 | bob cached=GBP 100.00 derived=GBP 100.00
```

✅ **Green.** Exactly five transfers, Alice at zero, and every balance consistent. The transfers queued up on Alice's row lock and ran one after another. Transfer six saw £0.00 and was refused properly with `InsufficientFundsException`.

### 7. The deadlock

Now try transfers in **both** directions at once. Create `src/test/java/com/example/ledger/transfer/OppositeTransfersTest.java`:

```java
package com.example.ledger.transfer;

import com.example.ledger.LedgerIntegrationTest;
import com.example.ledger.account.AccountService;
import com.example.ledger.cash.CashService;
import com.example.ledger.journal.LedgerService;
import com.example.ledger.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Alice pays Bob while Bob pays Alice, many times at once. */
@LedgerIntegrationTest
class OppositeTransfersTest {

    @Autowired AccountService accountService;
    @Autowired CashService cashService;
    @Autowired TransferService transferService;
    @Autowired LedgerService ledger;

    @Test
    void transfersInBothDirectionsDoNotDeadlock() throws Exception {
        Long alice = accountService.open("Alice").getId();
        Long bob = accountService.open("Bob").getId();
        cashService.deposit(alice, Money.gbp("100.00"));
        cashService.deposit(bob, Money.gbp("100.00"));

        CountDownLatch startingGun = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                boolean aliceToBob = i % 2 == 0;
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    try {
                        if (aliceToBob) {
                            transferService.transfer(alice, bob, Money.gbp("1.00"));
                        } else {
                            transferService.transfer(bob, alice, Money.gbp("1.00"));
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
                future.get();
            }
        }

        failures.forEach(f -> System.out.println("FAILURE: " + f));
        assertThat(failures).isEmpty();
        assertThat(accountService.get(alice).getBalance()).isEqualTo(Money.gbp("100.00"));
        assertThat(accountService.get(bob).getBalance()).isEqualTo(Money.gbp("100.00"));
    }
}
```

Ten £1 transfers each way should leave both accounts exactly where they started. Run it. It takes noticeably longer than the other tests, then fails with about half the transfers rejected:

```
FAILURE: org.springframework.dao.CannotAcquireLockException: JDBC exception executing SQL
  [Deadlock detected. The current transaction was rolled back. Details: "ACCOUNT"; …]
```

That's the cycle from the Concepts section. Alice-to-Bob transfers lock Alice and then want Bob. Bob-to-Alice transfers lock Bob and then want Alice. H2 detected the cycle and rolled back a victim each time. The data stays correct (the victims rolled back cleanly), but real customer payments failed.

### 8. Fix 3: lock in a consistent order

Replace the two `getForUpdate` lines in `TransferService.transfer`:

```java
        // Always lock the lower id first, so two opposite transfers can't deadlock
        Account first = accountService.getForUpdate(Math.min(fromId, toId));
        Account second = accountService.getForUpdate(Math.max(fromId, toId));
        Account from = first.getId().equals(fromId) ? first : second;
        Account to = first.getId().equals(fromId) ? second : first;
```

Now both directions try to lock the lower id first. One transfer gets it, and the other waits **before holding any lock**, so there's no cycle. Run all the transfer tests: green, and `OppositeTransfersTest` is fast again.

The **"lock in a global order"** rule applies everywhere, not just to transfers. Any code that locks more than one account must follow it. In guide 04 you'll use it for reversals.

> **Sidebar: H2 vs PostgreSQL.** Both support `SELECT … FOR UPDATE` and both detect deadlocks, so everything in this guide behaves the same way on PostgreSQL. The error text differs: PostgreSQL reports `ERROR: deadlock detected` with SQLSTATE `40P01`. Spring translates both into its `PessimisticLockingFailureException` family (`CannotAcquireLockException` is one member), so catch the parent type if you write retry logic. What *does* differ between databases is the finer detail of isolation levels (`REPEATABLE READ` and `SERIALIZABLE` behave quite differently on H2, PostgreSQL and Oracle). That's why this guide relies on explicit locks rather than isolation levels for correctness.

---

## Your turn

### Exercise 1: make cash safe too

`CashService.withdraw` has the same read-check-write race as the naive transfer, and deposits update the `CASH` account's cached balance without a lock. That now fails safely thanks to `@Version`, but it fails.

Add this test and watch it fail. Then fix `CashService` so it passes.

`src/test/java/com/example/ledger/cash/ConcurrentCashTest.java`:

```java
package com.example.ledger.cash;

import com.example.ledger.LedgerIntegrationTest;
import com.example.ledger.account.Account;
import com.example.ledger.account.AccountService;
import com.example.ledger.journal.LedgerService;
import com.example.ledger.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Different customers depositing at once all touch the bank's single CASH account. */
@LedgerIntegrationTest
class ConcurrentCashTest {

    @Autowired AccountService accountService;
    @Autowired CashService cashService;
    @Autowired LedgerService ledger;

    @Test
    void simultaneousDepositsAllSucceed() throws Exception {
        List<Long> customers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            customers.add(accountService.open("Customer " + i).getId());
        }
        Account cashBefore = accountService.getSystemAccount("CASH");

        CountDownLatch startingGun = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            List<Future<?>> futures = new ArrayList<>();
            for (Long id : customers) {
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    return cashService.deposit(id, Money.gbp("10.00"));
                }));
            }
            startingGun.countDown();
            for (Future<?> future : futures) {
                future.get(); // rethrows if any deposit failed
            }
        }

        Account cashAfter = accountService.getSystemAccount("CASH");
        assertThat(cashAfter.getBalance()).isEqualTo(cashBefore.getBalance().plus(Money.gbp("100.00")));
        assertThat(cashAfter.getBalance()).isEqualTo(ledger.derivedBalance(cashAfter));
    }
}
```

<details>
<summary>Hint 1</summary>

Lock both accounts with `getForUpdate`. Always lock the customer first and `CASH` second, in both `deposit` and `withdraw`. That's a consistent order for cash operations. (Transfers never touch `CASH`, so they can't form a cycle with them. Exercise 2 makes sure of that.)

</details>

<details>
<summary>Hint 2: if you get <code>StaleObjectStateException</code></summary>

If you wrote `accountService.getForUpdate(accountService.getSystemAccount("CASH").getId())`, you'll see:

```
ObjectOptimisticLockingFailureException: Query result contains conflicting version of
entity already held in persistence context for entity [Account with id '1']
```

`getSystemAccount` loads the `CASH` row **without** a lock, which puts a copy in this transaction's persistence context. By the time `getForUpdate` runs and gets the lock, another transaction has committed a newer version. Hibernate finds that the row it just locked doesn't match the stale copy it's already holding, and refuses to continue. **Never read a row without the lock and then lock it in the same transaction.** Lock it on the first read: add a locking query that finds the account by `systemCode`.

</details>

<details>
<summary>Solution</summary>

`AccountRepository`:

```java
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.systemCode = :systemCode")
    Optional<Account> findBySystemCodeForUpdate(String systemCode);
```

`AccountService`:

```java
    @Transactional
    public Account getSystemAccountForUpdate(String systemCode) {
        return accounts.findBySystemCodeForUpdate(systemCode)
                .orElseThrow(() -> new IllegalStateException("Missing system account " + systemCode));
    }
```

`CashService`:

```java
    @Transactional
    public JournalEntry deposit(Long accountId, Money amount) {
        Account customer = accountService.getForUpdate(accountId);
        Account cash = accountService.getSystemAccountForUpdate("CASH");
        JournalEntry entry = JournalEntry.builder("Cash deposit")
                .debit(cash, amount)       // the bank's cash goes up (asset)
                .credit(customer, amount)  // what the bank owes the customer goes up (liability)
                .build();
        return ledger.post(entry);
    }

    @Transactional
    public JournalEntry withdraw(Long accountId, Money amount) {
        Account customer = accountService.getForUpdate(accountId);
        if (customer.getBalance().isLessThan(amount)) {
            throw new InsufficientFundsException(accountId, customer.getBalance(), amount);
        }
        Account cash = accountService.getSystemAccountForUpdate("CASH");
        JournalEntry entry = JournalEntry.builder("Cash withdrawal")
                .debit(customer, amount)
                .credit(cash, amount)
                .build();
        return ledger.post(entry);
    }
```

`withdraw` checks the balance *after* locking the customer, and before bothering to lock `CASH`. An insufficient-funds refusal never queues on the hot account.

</details>

### Exercise 2: customers only

`POST /transfers` accepts **any** account id, including the bank's `CASH` account (id 1). A client could "transfer" money into or out of the bank's own cash. That's a business bug, and it would also break the lock-ordering argument in Exercise 1, because a transfer could then lock `CASH` before a customer.

1. Add `public boolean isInternal()` to `Account` (`true` when `systemCode` is not `null`).
2. In `TransferService`, after loading both accounts, throw `InvalidTransferException("Transfers are only allowed between customer accounts")` if either one is internal.
3. Add a test to `TransferApiTest` that transfers from Alice to the `CASH` account and expects a `422`.
4. Update `LedgerInvariantsTest` to use `isInternal()` instead of `getSystemCode() == null`.

<details>
<summary>Solution</summary>

`Account`:

```java
    /** True for the bank's own accounts, such as CASH. */
    public boolean isInternal() {
        return systemCode != null;
    }
```

`TransferService.transfer`, straight after working out `from` and `to`:

```java
        if (from.isInternal() || to.isInternal()) {
            throw new InvalidTransferException("Transfers are only allowed between customer accounts");
        }
```

`TransferApiTest`:

```java
    @Test
    void refusesATransferToTheBanksOwnCashAccount() {
        Long cash = accountService.getSystemAccount("CASH").getId();

        assertThat(transfer(alice, cash, "1.00")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }
```

The check comes *after* the locks, because it needs the loaded accounts. That's fine: locking `CASH` briefly in the rejected case is harmless, because the transaction rolls back straight away.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
```

Run it two or three times. Concurrency tests must pass **every** time, not just usually.

New files since guide 02:

```
src/main/java/com/example/ledger/transfer/
├── InvalidTransferException.java
├── TransferController.java
└── TransferService.java
src/main/resources/db/migration/
└── V3__add_account_version.sql
src/test/java/com/example/ledger/
├── cash/ConcurrentCashTest.java
└── transfer/
    ├── ConcurrentTransferTest.java
    ├── OppositeTransfersTest.java
    └── TransferApiTest.java
```

Modified: `Account.java` (`@Version`, `isInternal`), `AccountRepository.java` (two locking queries), `AccountService.java` (`getForUpdate`, `getSystemAccountForUpdate`), `CashService.java` (locking), `ApiExceptionHandler.java`, `LedgerInvariantsTest.java`, `requests.http`.

---

## Common errors

**`ObjectOptimisticLockingFailureException: Unexpected row count (expected row count 1 but was 0)`**
Two transactions updated the same row, and `@Version` stopped the second one from overwriting the first. If you see it in a test, some code path is changing an account without `getForUpdate`. Find it and lock the account.

**`ObjectOptimisticLockingFailureException: Query result contains conflicting version of entity already held in persistence context`**
You read an account without a lock, then locked it in the same transaction. See Exercise 1, Hint 2. Lock it on the **first** read.

**`CannotAcquireLockException: … Deadlock detected. The current transaction was rolled back.`**
Two transactions locked the same rows in different orders. Make every code path lock accounts in ascending id order.

**`CannotAcquireLockException: … Timeout trying to lock table "ACCOUNT"`**
A transaction waited longer than H2's lock timeout. Usually something held a lock for a long time, such as a breakpoint you paused on inside a transaction while another thread waited. Resume the debugger, or raise the timeout for experiments with `;LOCK_TIMEOUT=30000` on the JDBC URL.

**The concurrency test passes even without locking**
The threads aren't really overlapping. Check that every task calls `startingGun.await()` *before* doing any work, and that `countDown()` is only called after all tasks have been submitted.

**The concurrency test hangs**
A task is stuck in `await()` because `countDown()` was never reached, often because an exception was thrown while submitting the tasks. As a safety net, use `future.get(30, TimeUnit.SECONDS)` so a hang becomes a failure.

**Worker threads can't see the accounts the test created**
The test class (or test method) is annotated `@Transactional`. The data was created inside the test's transaction, which hasn't committed, so other threads can't see it. Concurrency tests must not be `@Transactional`.

**Locking seems to do nothing**
`getForUpdate` was called from a method that isn't running in a transaction, so the lock was released the moment the query finished. The outer service method (`transfer`, `deposit`, …) must be `@Transactional`, and it must be called **through the Spring bean** (from another class), not as `this.transfer(...)` inside the same class, which bypasses the transactional proxy.

---

## Recap

- **Double spending** happens when two transactions check the same balance before either updates it. `@Transactional` gives you atomicity, not mutual exclusion.
- A **lost update** is when one transaction overwrites another's change with a value calculated from a stale read.
- **Optimistic locking** (`@Version`) detects conflicts at write time and fails the loser. It's cheap, it's a great safety net, and it's best when conflicts are rare. Combine it with retries.
- **Pessimistic locking** (`@Lock(PESSIMISTIC_WRITE)` → `SELECT … FOR UPDATE`) makes other transactions wait. It's the right tool for a heavily contended row like a busy account.
- **Deadlocks** come from acquiring locks in different orders. **Always lock in a consistent global order**, such as ascending account id.
- **Never read a row without the lock and then lock it** in the same transaction.
- **Hot accounts** serialise everything that touches them. Real ledgers split, batch or derive them.
- **Concurrency bugs need concurrency tests.** A `CountDownLatch` starting gun makes the race reliable. Assert on the invariants, not just on success counts.

### Interview angle

<details>
<summary>"Two requests withdraw from the same account at the same time. How do you stop it going overdrawn?"</summary>

Serialise updates to that account. With a relational database, lock the account row with `SELECT … FOR UPDATE` (in JPA, `@Lock(PESSIMISTIC_WRITE)`) at the start of the transaction, then check the balance and write the postings while holding the lock. Explain why `@Transactional` alone isn't enough (READ COMMITTED allows both to read the same balance). Mention optimistic locking with a version column as an alternative, or a safety net, with retries, and say when each fits: pessimistic for hot rows, optimistic when conflicts are rare.

</details>

<details>
<summary>"What's a deadlock and how do you avoid one?"</summary>

Two transactions each hold a lock the other needs, so neither can proceed. The database detects the cycle and aborts one of them. Avoid it by acquiring locks in a consistent global order (for example by primary key) and keeping transactions short. Handle the rare remaining cases by retrying the aborted transaction.

</details>

<details>
<summary>"How would you test for a race condition?"</summary>

Run many threads against the same resource, released simultaneously with a `CountDownLatch`, with no test-level transaction, so each call commits for real. Then assert the invariants (the balance never goes negative, the number of successes is exactly right, cached equals derived) rather than just "no exceptions". Run it repeatedly. Show that it fails *before* the fix. A concurrency test that has never failed proves nothing.

</details>

<details>
<summary>"What's a hot account and how would you deal with one?"</summary>

An account that a large share of transactions touch, like the bank's own cash or settlement account, or a big merchant's. Locking it serialises all those transactions. Options: split it into N sub-accounts and pick one per transaction (the sum is the real balance), batch its postings periodically, or don't maintain a cached balance for it and derive it when needed.

</details>

### Further reading

- [PostgreSQL docs: Explicit Locking](https://www.postgresql.org/docs/current/explicit-locking.html): row-level locks and deadlocks explained clearly
- [Vlad Mihalcea: optimistic vs pessimistic locking](https://vladmihalcea.com/optimistic-vs-pessimistic-locking/)
- [Spring Data JPA reference: Locking](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)
- [Java Concurrency in Practice](https://jcip.net/) (Goetz et al.): the classic book. Chapters 1–5 are the essentials.

**Simplification to notice:** real banks often allow an **arranged overdraft** with a limit. The check becomes `balance + overdraftLimit >= amount`, and the locking story stays exactly the same.

**Next:** [04: Statements, reversals and idempotency](04-statements-reversals-and-idempotency.md). How a ledger corrects mistakes without ever deleting anything, and how to make "retry the request" safe.
