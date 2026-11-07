# 04: Statements, Reversals and Idempotency

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- Why a ledger **never edits or deletes** history, and how a **reversal** corrects a mistake instead
- What makes a ledger an **audit trail**, and what Hibernate's `@Immutable` does (and doesn't) protect
- How to build a paged **statement** with a correct **running balance** on every page
- **Idempotency**: why "just retry it" can pay someone twice, and how an `Idempotency-Key` header prevents it
- Why the idempotency check must come **after** taking the locks, and how a **unique constraint** backs it up
- Choosing between `409 Conflict` and `422 Unprocessable Content`

By the end, your ledger can correct mistakes without losing history, show customers their statements, and safely accept a transfer request that a nervous client sends five times.

---

## Concepts

### How it works in a real bank: never edit, always reverse

Suppose a cashier keys a £500 deposit into the wrong account. In most software you'd fix that with an `UPDATE` or a `DELETE`. In a ledger, **you never do**. Instead you post a new journal entry that is the exact mirror image of the mistake, a [reversal](../glossary.md#reversal), and then post the correct entry:

```
#41  Cash deposit              DEBIT cash £500     CREDIT Alice £500   ← the mistake
#42  Reversal of entry 41      CREDIT cash £500    DEBIT Alice £500    ← cancels it
#43  Cash deposit              DEBIT cash £500     CREDIT Bob £500     ← the correction
```

Why go to that trouble?

- **The [audit trail](../glossary.md#audit-trail) stays complete.** Auditors, regulators, fraud investigators and the customer can all see what happened, when, and how it was fixed. A deleted row tells no story at all, and a ledger where history can change can't be trusted.
- **Every report stays reproducible.** Last month's statement, last quarter's regulatory return and yesterday's reconciliation were all calculated from postings that still exist, unchanged.
- **It's how accounting has always worked.** Accountants have corrected mistakes with contra entries for centuries, long before databases existed.

The rule for your code: **journal entries and postings are insert-only.**

One consequence surprises people. If Alice was wrongly credited £100 and has already spent £25 of it, reversing the £100 takes her to **−£25**. That's correct: the money was never hers. Banks handle this operationally (contacting the customer, an unarranged overdraft), but the ledger records the truth rather than refusing the correction.

### Statements and running balances

A [statement](../glossary.md#statement) lists an account's postings, usually newest first, with the [running balance](../glossary.md#running-balance) after each line:

| Date | Description | Amount | Balance |
|---|---|---|---|
| 22:19:39 | Transfer to 3 | −25.00 | 75.00 |
| 22:19:39 | Cash deposit | 100.00 | 100.00 |

With a thousand postings and 20 per page, how do you know the balance at the top of page 7 without adding up pages 1 to 6? The approach you'll use:

1. Fetch the page of postings (newest first).
2. Work out the balance **after the newest posting on the page** with one aggregate query: the sum of all of this account's postings with an id up to and including that one.
3. Walk down the page, subtracting each line's effect to get the balance after the line below it.

That's two queries per page, regardless of how far back the customer scrolls.

### Idempotency: safe retries

A mobile app sends `POST /transfers`. The network drops **after** the server committed the transfer but **before** the response arrived. From the app's point of view the request failed. Did the money move? It has no way of knowing, so it retries, and Bob gets paid twice.

The fix is [idempotency](../glossary.md#idempotency): sending the same request twice has the same effect as sending it once. The client generates a unique [idempotency key](../glossary.md#idempotency-key) (usually a UUID) for each *intended* operation, and sends it with every attempt:

```http
POST /transfers
Idempotency-Key: 6b0f4b02-2f5c-4c1e-9a49-8f0f7c6f9a11
```

The server's rules:

1. **New key:** do the work, and store the key **in the same transaction** as the result.
2. **Known key, same request:** do **nothing**. Return the original result. (We add an `Idempotent-Replayed: true` header so the client can tell.)
3. **Known key, different request:** that's a client bug. Reject it, so the second request isn't silently ignored.

The `Idempotency-Key` header is being standardised by the IETF ([draft-ietf-httpapi-idempotency-key-header](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/)) and is used by Stripe, among many others. UK Open Banking APIs use the same idea with an `x-idempotency-key` header. The Payments tutorial goes further, with the same problem across *several* systems.

### The hard part: duplicates that arrive together

Rule 1 says "check the key, then do the work". That's a read-check-write sequence, which you know from guide 03 means a race. Two copies of the same request arriving at the same moment could both see "new key" and both pay.

There are two defences, and you'll use both:

- **Check the key *after* taking the account locks.** Duplicate requests have the same accounts, so the second one waits on the row lock until the first commits. By the time it checks, the first one's key is in the database.
- **A `UNIQUE` constraint on the key column.** If anything ever slips past the first defence, the database refuses the second insert. The ledger stays correct, and the client gets a `409 Conflict` it can retry.

### 409 or 422?

Both mean "I understood you, but no":

- **`422 Unprocessable Content`:** the request itself breaks a rule. Insufficient funds, a transfer to yourself, an idempotency key reused for a different request. Retrying the same request won't help.
- **`409 Conflict`:** the request conflicts with the **current state** of the resource. The entry has already been reversed, or a concurrent duplicate was inserted. The state could change, and re-reading it may explain what happened.

---

## Steps

### 1. The migration

Create `src/main/resources/db/migration/V4__reversals_and_idempotency.sql`:

```sql
-- A reversal points at the entry it cancels. UNIQUE: an entry can be reversed at most once.
ALTER TABLE journal_entry ADD COLUMN reverses_entry_id BIGINT REFERENCES journal_entry (id);
ALTER TABLE journal_entry ADD CONSTRAINT uk_journal_entry_reverses UNIQUE (reverses_entry_id);

-- The client's Idempotency-Key, and what they asked for the first time they used it
ALTER TABLE journal_entry ADD COLUMN idempotency_key VARCHAR(100);
ALTER TABLE journal_entry ADD COLUMN request_fingerprint VARCHAR(200);
ALTER TABLE journal_entry ADD CONSTRAINT uk_journal_entry_idempotency_key UNIQUE (idempotency_key);
```

Both rules ("reverse at most once" and "one entry per idempotency key") are enforced by the **database**, not just by Java code. Most entries have neither a reversal link nor a key, and that's fine: unique constraints ignore `NULL`s.

### 2. Make entries immutable, and teach them to reverse

In `journal/JournalEntry.java`, add `@Immutable` (from `org.hibernate.annotations`) to the class:

```java
@Entity
@Immutable
@Table(name = "journal_entry")
public class JournalEntry {
```

Add the new fields after `postings`:

```java
    @Column(name = "reverses_entry_id")
    private Long reversesEntryId;

    @Column(name = "idempotency_key", length = 100, unique = true)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", length = 200)
    private String requestFingerprint;
```

Add a factory that builds the mirror image of an entry, below `builder(...)`:

```java
    /** A new entry with every posting flipped: the only way to "undo" an entry. */
    public static JournalEntry reversalOf(JournalEntry original) {
        Builder builder = builder("Reversal of entry " + original.getId());
        for (Posting posting : original.getPostings()) {
            if (posting.getDirection() == Direction.DEBIT) {
                builder.credit(posting.getAccount(), posting.getAmount());
            } else {
                builder.debit(posting.getAccount(), posting.getAmount());
            }
        }
        builder.entry.reversesEntryId = original.getId();
        return builder.build();
    }
```

A reversal goes through the same `Builder`, so it's checked by exactly the same rules as any other entry. If the original balanced, the mirror image balances too.

Add getters:

```java
    public Long getReversesEntryId() {
        return reversesEntryId;
    }

    public boolean isReversal() {
        return reversesEntryId != null;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }
```

And a builder method, in `Builder`:

```java
        public Builder idempotencyKey(String key, String requestFingerprint) {
            entry.idempotencyKey = key;
            entry.requestFingerprint = requestFingerprint;
            return this;
        }
```

In `journal/Posting.java`, add `@Immutable` to the class too, plus a getter the statement will need:

```java
    public JournalEntry getEntry() {
        return entry;
    }
```

**What does `@Immutable` actually do?** It tells Hibernate that rows of this entity never change after insert:

- Changes you make to a loaded entity are **ignored**. Hibernate never issues an `UPDATE` for it (and skips the dirty-checking work).
- HQL/JPQL bulk `update` statements against it **throw an exception**.
- It does **not** stop deletes. `repository.deleteById(...)` or a JPQL `delete` would still run.

So `@Immutable` stops accidental edits from Java, but it isn't a security boundary. Real protection comes in layers: no delete or update code paths in the application (you're about to test that the API has none), foreign keys (`posting` rows already make a `journal_entry` impossible to delete on its own), and, most importantly in production, a **database user for the application that has no `UPDATE` or `DELETE` permission** on the journal tables. Some banks also add database triggers that reject updates, or write entries to append-only storage as well.

> **Sidebar: hash chaining.** Some ledgers go one step further and store, with each entry, a cryptographic hash of the entry *plus the previous entry's hash*. Changing any historical row would break every hash after it, so tampering becomes detectable. It's the same idea behind blockchains, minus the distributed part.

### 3. Repository queries

Replace `journal/JournalEntryRepository.java`:

```java
package com.example.ledger.journal;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    @EntityGraph(attributePaths = "postings")
    Optional<JournalEntry> findWithPostingsById(Long id);

    @EntityGraph(attributePaths = "postings")
    Optional<JournalEntry> findByIdempotencyKey(String idempotencyKey);

    boolean existsByReversesEntryId(Long reversesEntryId);
}
```

Add two methods to `journal/PostingRepository.java` (with imports for `Page`, `Pageable` and `EntityGraph`):

```java
    /** Like sumByAccountAndDirection, but only postings up to and including maxPostingId. */
    @Query("""
            select coalesce(sum(p.amount), 0)
            from Posting p
            where p.account.id = :accountId and p.direction = :direction and p.id <= :maxPostingId
            """)
    BigDecimal sumByAccountAndDirectionUpTo(Long accountId, Direction direction, Long maxPostingId);

    @EntityGraph(attributePaths = "entry")
    Page<Posting> findByAccountIdOrderByIdDesc(Long accountId, Pageable pageable);
```

`findByAccountIdOrderByIdDesc` is a derived query: Spring Data reads the method name (`account.id = ?`, newest id first) and adds paging from the `Pageable`. The `@EntityGraph` fetches each posting's journal entry in the same query, because every statement line needs the entry's date and description. Without it you'd get one extra query per line, the classic **N+1** problem.

> **Sidebar: why order by id and not by date?** Ids from an identity column increase in insert order, they're unique, and they're indexed. Two postings can share a timestamp, but never an id. A real ledger usually orders by **booking date** and then a sequence number, and distinguishes the booking date from the [value date](../glossary.md#booking-date). The Loans tutorial deals with that.

### 4. Reversing an entry

Create `journal/ReversalNotAllowedException.java`:

```java
package com.example.ledger.journal;

public class ReversalNotAllowedException extends RuntimeException {

    public ReversalNotAllowedException(String message) {
        super(message);
    }
}
```

`LedgerService` now needs to lock accounts, so give it the `AccountService`:

```java
    private final JournalEntryRepository entries;
    private final PostingRepository postings;
    private final AccountService accountService;

    public LedgerService(JournalEntryRepository entries, PostingRepository postings,
                         AccountService accountService) {
        this.entries = entries;
        this.postings = postings;
        this.accountService = accountService;
    }
```

Add the `reverse` method:

```java
    /** Cancels an entry by posting its mirror image. Nothing is ever deleted. */
    @Transactional
    public JournalEntry reverse(Long entryId) {
        JournalEntry original = entries.findWithPostingsById(entryId)
                .orElseThrow(() -> new JournalEntryNotFoundException(entryId));
        if (entries.existsByReversesEntryId(entryId)) {
            throw new ReversalNotAllowedException("Entry %d has already been reversed".formatted(entryId));
        }
        // Lock every account involved, lowest id first (same rule as transfers)
        List<Long> accountIds = original.getPostings().stream()
                .map(p -> p.getAccount().getId())
                .distinct()
                .sorted()
                .toList();
        accountIds.forEach(accountService::getForUpdate);
        return post(JournalEntry.reversalOf(original));
    }
```

(Import `com.example.ledger.account.AccountService` and `java.util.List`.)

The guide 03 lessons apply directly:

- **A reversal changes balances**, so it must lock the accounts it touches.
- **It locks them in ascending id order**, the same global rule as transfers, so a reversal can never deadlock with a transfer or with another reversal.
- **It reads each account for the first time *with* the lock.** The entity graph loaded only the postings. The accounts are still unloaded lazy proxies (asking for `getId()` doesn't load them), so `getForUpdate` is the first real read of each row. That's the "lock on first read" rule from guide 03.

Expose it in `journal/JournalEntryController.java`. Add `LedgerService` to the constructor and add this method:

```java
    @PostMapping("/{id}/reversal")
    public ResponseEntity<JournalEntryResponse> reverse(@PathVariable Long id) {
        JournalEntry reversal = ledger.reverse(id);
        return ResponseEntity.created(URI.create("/journal-entries/" + reversal.getId()))
                .body(JournalEntryResponse.from(reversal));
    }
```

`POST /journal-entries/{id}/reversal` creates a *new* resource (the reversal), so it's a `POST` returning `201 Created`, not a `DELETE`.

Add `reversesEntryId` to `JournalEntryResponse` so clients can see the link:

```java
public record JournalEntryResponse(Long id, String description, Instant createdAt,
                                   Long reversesEntryId, List<PostingResponse> postings) {

    public record PostingResponse(Long accountId, Direction direction, Money amount) {
    }

    public static JournalEntryResponse from(JournalEntry entry) {
        List<PostingResponse> postings = entry.getPostings().stream()
                .map(p -> new PostingResponse(p.getAccount().getId(), p.getDirection(), p.getAmount()))
                .toList();
        return new JournalEntryResponse(entry.getId(), entry.getDescription(), entry.getCreatedAt(),
                entry.getReversesEntryId(), postings);
    }
}
```

And map the exception in `ApiExceptionHandler`. "Already reversed" depends on the entry's current state, so it's a `409`:

```java
@ExceptionHandler(ReversalNotAllowedException.class)
ProblemDetail handle(ReversalNotAllowedException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    problem.setTitle("Reversal not allowed");
    return problem;
}
```

### 5. Reversal and immutability tests

`src/test/java/com/example/ledger/journal/ReversalApiTest.java`:

```java
package com.example.ledger.journal;

import com.example.ledger.LedgerIntegrationTest;
import com.example.ledger.account.AccountService;
import com.example.ledger.cash.CashService;
import com.example.ledger.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

@LedgerIntegrationTest
class ReversalApiTest {

    @Autowired MockMvcTester mvc;
    @Autowired AccountService accountService;
    @Autowired CashService cashService;
    @Autowired JournalEntryRepository entries;
    @Autowired LedgerService ledger;

    Long alice;
    Long depositId;

    @BeforeEach
    void depositByMistake() {
        alice = accountService.open("Alice").getId();
        depositId = cashService.deposit(alice, Money.gbp("500.00")).getId();
    }

    @Test
    void reversalFlipsEveryPostingAndRestoresTheBalance() {
        MvcTestResult response = mvc.post().uri("/journal-entries/{id}/reversal", depositId).exchange();

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).bodyJson().extractingPath("$.reversesEntryId").isEqualTo(depositId.intValue());
        assertThat(response).bodyJson().extractingPath("$.postings[*].direction")
                .asArray().containsExactly("CREDIT", "DEBIT");
        assertThat(accountService.get(alice).getBalance()).isEqualTo(Money.gbp("0.00"));
        // The original is still there, untouched
        assertThat(entries.findById(depositId)).isPresent();
    }

    @Test
    void anEntryCanOnlyBeReversedOnce() {
        mvc.post().uri("/journal-entries/{id}/reversal", depositId).exchange();

        MvcTestResult again = mvc.post().uri("/journal-entries/{id}/reversal", depositId).exchange();

        assertThat(again).hasStatus(HttpStatus.CONFLICT);
        assertThat(accountService.get(alice).getBalance()).isEqualTo(Money.gbp("0.00"));
    }
}
```

`$.postings[*].direction` is a JSONPath wildcard that collects the field from every posting into an array. `asArray().containsExactly(...)` then checks both the values and their order.

`src/test/java/com/example/ledger/journal/ImmutabilityTest.java` pins down exactly what `@Immutable` and the API guarantee:

```java
package com.example.ledger.journal;

import com.example.ledger.LedgerIntegrationTest;
import com.example.ledger.account.AccountService;
import com.example.ledger.cash.CashService;
import com.example.ledger.money.Money;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@LedgerIntegrationTest
class ImmutabilityTest {

    @Autowired MockMvcTester mvc;
    @Autowired AccountService accountService;
    @Autowired CashService cashService;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    Long entryId;

    @BeforeEach
    void anEntry() {
        Long alice = accountService.open("Alice").getId();
        entryId = cashService.deposit(alice, Money.gbp("10.00")).getId();
    }

    @Test
    void hibernateRefusesToUpdateAJournalEntry() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                em.createQuery("update JournalEntry e set e.description = 'edited' where e.id = :id")
                        .setParameter("id", entryId)
                        .executeUpdate()))
                .hasMessageContaining("immutable entity");
    }

    @Test
    void theApiHasNoWayToDeleteAnEntry() {
        assertThat(mvc.delete().uri("/journal-entries/{id}", entryId))
                .hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
    }
}
```

`TransactionTemplate` is the **programmatic** version of `@Transactional`. It runs the lambda inside a transaction. A JPQL `update` needs one, and the test class itself mustn't be transactional.

The second test looks trivial, but it **documents a requirement**. If someone adds a `@DeleteMapping` to `JournalEntryController` one day, this test fails and starts a conversation.

### 6. Statements

`LedgerService` needs a "balance after posting N" method. Refactor `derivedBalance` so both methods share the arithmetic:

```java
    /** The balance worked out from scratch from the postings: the source of truth. */
    @Transactional(readOnly = true)
    public Money derivedBalance(Account account) {
        BigDecimal debits = postings.sumByAccountAndDirection(account.getId(), Direction.DEBIT);
        BigDecimal credits = postings.sumByAccountAndDirection(account.getId(), Direction.CREDIT);
        return toBalance(account, debits, credits);
    }

    /** The balance immediately after the given posting. */
    @Transactional(readOnly = true)
    public Money balanceAfter(Account account, Long postingId) {
        BigDecimal debits = postings.sumByAccountAndDirectionUpTo(account.getId(), Direction.DEBIT, postingId);
        BigDecimal credits = postings.sumByAccountAndDirectionUpTo(account.getId(), Direction.CREDIT, postingId);
        return toBalance(account, debits, credits);
    }

    private static Money toBalance(Account account, BigDecimal debits, BigDecimal credits) {
        BigDecimal balance = account.getType().normalBalance() == Direction.DEBIT
                ? debits.subtract(credits)
                : credits.subtract(debits);
        return new Money(balance, account.getCurrency());
    }
```

Create the `statement` package with two response records. `statement/StatementLine.java`:

```java
package com.example.ledger.statement;

import com.example.ledger.money.Money;

import java.time.Instant;

/** amount is signed from the account holder's point of view: money in is positive. */
public record StatementLine(Long journalEntryId, Instant date, String description,
                            Money amount, Money balanceAfter) {
}
```

`statement/Statement.java`:

```java
package com.example.ledger.statement;

import com.example.ledger.money.Money;

import java.util.List;

public record Statement(Long accountId, Money currentBalance, int page, int size,
                        long totalLines, List<StatementLine> lines) {
}
```

A statement translates the ledger's debits and credits into what a customer understands: **money in is positive, money out is negative**. That's just "does this posting go in the account's normal-balance direction?"

`statement/StatementService.java`:

```java
package com.example.ledger.statement;

import com.example.ledger.account.Account;
import com.example.ledger.account.AccountService;
import com.example.ledger.journal.LedgerService;
import com.example.ledger.journal.Posting;
import com.example.ledger.journal.PostingRepository;
import com.example.ledger.money.Money;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class StatementService {

    private final AccountService accountService;
    private final PostingRepository postings;
    private final LedgerService ledger;

    public StatementService(AccountService accountService, PostingRepository postings, LedgerService ledger) {
        this.accountService = accountService;
        this.postings = postings;
        this.ledger = ledger;
    }

    /** One page of an account's history, newest first, each line with the balance after it. */
    @Transactional(readOnly = true)
    public Statement statement(Long accountId, int page, int size) {
        Account account = accountService.get(accountId);
        Page<Posting> result = postings.findByAccountIdOrderByIdDesc(accountId, PageRequest.of(page, size));

        List<StatementLine> lines = new ArrayList<>();
        if (result.hasContent()) {
            // Balance after the newest posting on this page, worked out from the postings...
            Money balance = ledger.balanceAfter(account, result.getContent().getFirst().getId());
            // ...then walk backwards in time, undoing one posting per line
            for (Posting posting : result.getContent()) {
                Money effect = posting.getDirection() == account.getType().normalBalance()
                        ? posting.getAmount()
                        : posting.getAmount().negate();
                lines.add(new StatementLine(
                        posting.getEntry().getId(),
                        posting.getEntry().getCreatedAt(),
                        posting.getEntry().getDescription(),
                        effect,
                        balance));
                balance = balance.minus(effect);
            }
        }
        return new Statement(accountId, account.getBalance(), page, size, result.getTotalElements(), lines);
    }
}
```

Read the loop with an example. The page holds postings for −£20, −£30 and +£100 (newest first), and `balanceAfter` returns £50.00 for the newest one:

| Line | Effect | Balance after this line | Next `balance` |
|---|---|---|---|
| Transfer | −20.00 | **50.00** | 50 − (−20) = 70.00 |
| Withdrawal | −30.00 | **70.00** | 70 − (−30) = 100.00 |
| Deposit | +100.00 | **100.00** | 0.00 |

Notice that the running balance comes from the **postings** (`balanceAfter`), not from the cached balance. A statement is a historical document, so it's built from the source of truth.

`statement/StatementController.java`:

```java
package com.example.ledger.statement;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatementController {

    private final StatementService statementService;

    public StatementController(StatementService statementService) {
        this.statementService = statementService;
    }

    @GetMapping("/accounts/{accountId}/statement")
    public Statement statement(@PathVariable Long accountId,
                               @RequestParam(defaultValue = "0") @Min(0) int page,
                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return statementService.statement(accountId, page, size);
    }
}
```

Constraint annotations directly on `@RequestParam` parameters are checked by Spring MVC's **built-in method validation** (Spring Framework 6.1+). A violation produces a `400` `ProblemDetail` through your `ResponseEntityExceptionHandler`. Capping `size` stops a client from asking for a million rows in one request.

> **Why return `Statement` and not Spring Data's `Page`?** Serialising `PageImpl` directly exposes Spring internals in your API (and Spring Data logs a warning if you do). Your own record is a deliberate, stable contract.

### 7. Statement tests

`src/test/java/com/example/ledger/statement/StatementApiTest.java`:

```java
package com.example.ledger.statement;

import com.example.ledger.LedgerIntegrationTest;
import com.example.ledger.account.AccountService;
import com.example.ledger.cash.CashService;
import com.example.ledger.money.Money;
import com.example.ledger.transfer.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@LedgerIntegrationTest
class StatementApiTest {

    @Autowired MockMvcTester mvc;
    @Autowired AccountService accountService;
    @Autowired CashService cashService;
    @Autowired TransferService transferService;

    Long alice;

    @BeforeEach
    void someHistory() {
        alice = accountService.open("Alice").getId();
        Long bob = accountService.open("Bob").getId();
        cashService.deposit(alice, Money.gbp("100.00"));   // balance 100.00
        cashService.withdraw(alice, Money.gbp("30.00"));   // balance  70.00
        transferService.transfer(UUID.randomUUID().toString(), alice, bob, Money.gbp("20.00")); // 50.00
    }

    @Test
    void listsNewestFirstWithARunningBalance() {
        MvcTestResult response = mvc.get().uri("/accounts/{id}/statement", alice).exchange();

        assertThat(response).hasStatus(HttpStatus.OK);
        assertThat(response).bodyJson().extractingPath("$.totalLines").isEqualTo(3);
        assertThat(response).bodyJson().extractingPath("$.lines[*].amount.amount")
                .asArray().containsExactly("-20.00", "-30.00", "100.00");
        assertThat(response).bodyJson().extractingPath("$.lines[*].balanceAfter.amount")
                .asArray().containsExactly("50.00", "70.00", "100.00");
    }

    @Test
    void laterPagesStillHaveTheRightRunningBalance() {
        MvcTestResult response = mvc.get().uri("/accounts/{id}/statement?page=1&size=2", alice).exchange();

        assertThat(response).bodyJson().extractingPath("$.lines.length()").isEqualTo(1);
        assertThat(response).bodyJson().extractingPath("$.lines[0].description").isEqualTo("Cash deposit");
        assertThat(response).bodyJson().extractingPath("$.lines[0].balanceAfter.amount").isEqualTo("100.00");
    }

    @Test
    void rejectsAPageSizeOver100() {
        assertThat(mvc.get().uri("/accounts/{id}/statement?size=1000", alice))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }
}
```

`transferService.transfer(UUID.randomUUID().toString(), …)`: the transfer method gains an idempotency-key parameter in the next step. This test won't compile until you've done step 8, which is fine. Write it now and run it after.

`laterPagesStillHaveTheRightRunningBalance` is the important one. Page 1 (the second page) contains only the oldest posting, and its running balance must still be correct without the newer postings being on the page.

### 8. Idempotent transfers

Create `transfer/IdempotencyKeyReusedException.java`:

```java
package com.example.ledger.transfer;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key '%s' was already used for a different request".formatted(key));
    }
}
```

`transfer/TransferResult.java`, so the controller can tell a fresh result from a replay:

```java
package com.example.ledger.transfer;

import com.example.ledger.journal.JournalEntry;

/** replayed = true when this key was seen before and we're returning the original result. */
public record TransferResult(JournalEntry entry, boolean replayed) {
}
```

Now the full `transfer/TransferService.java`:

```java
package com.example.ledger.transfer;

import com.example.ledger.account.Account;
import com.example.ledger.account.AccountService;
import com.example.ledger.cash.InsufficientFundsException;
import com.example.ledger.journal.JournalEntry;
import com.example.ledger.journal.JournalEntryRepository;
import com.example.ledger.journal.LedgerService;
import com.example.ledger.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class TransferService {

    private final AccountService accountService;
    private final LedgerService ledger;
    private final JournalEntryRepository entries;

    public TransferService(AccountService accountService, LedgerService ledger, JournalEntryRepository entries) {
        this.accountService = accountService;
        this.ledger = ledger;
        this.entries = entries;
    }

    @Transactional
    public TransferResult transfer(String idempotencyKey, Long fromId, Long toId, Money amount) {
        if (fromId.equals(toId)) {
            throw new InvalidTransferException("Cannot transfer from account %d to itself".formatted(fromId));
        }
        // Always lock the lower id first, so two opposite transfers can't deadlock
        Account first = accountService.getForUpdate(Math.min(fromId, toId));
        Account second = accountService.getForUpdate(Math.max(fromId, toId));
        Account from = first.getId().equals(fromId) ? first : second;
        Account to = first.getId().equals(fromId) ? second : first;
        if (from.isInternal() || to.isInternal()) {
            throw new InvalidTransferException("Transfers are only allowed between customer accounts");
        }

        // Checked AFTER taking the locks: a duplicate request for the same accounts
        // waits above until the first one commits, then finds its entry here.
        String fingerprint = "%d|%d|%s".formatted(fromId, toId, amount);
        Optional<JournalEntry> previous = entries.findByIdempotencyKey(idempotencyKey);
        if (previous.isPresent()) {
            if (!previous.get().getRequestFingerprint().equals(fingerprint)) {
                throw new IdempotencyKeyReusedException(idempotencyKey);
            }
            return new TransferResult(previous.get(), true);
        }

        if (from.getBalance().isLessThan(amount)) {
            throw new InsufficientFundsException(fromId, from.getBalance(), amount);
        }
        JournalEntry entry = JournalEntry.builder("Transfer from %d to %d".formatted(fromId, toId))
                .idempotencyKey(idempotencyKey, fingerprint)
                .debit(from, amount)
                .credit(to, amount)
                .build();
        return new TransferResult(ledger.post(entry), false);
    }
}
```

The order of operations is the whole design:

1. **Lock** both accounts in id order (guide 03).
2. **Check the key.** Under READ COMMITTED, a duplicate that was waiting for the lock now sees the first request's committed entry.
3. **Compare fingerprints.** The fingerprint is a compact record of what was asked (`"2|3|GBP 25.00"`). Same key and same fingerprint means a retry, so return the original result. Same key with a different fingerprint means the client has a bug, so reject it.
4. **Only then** check the funds and post. The key is saved **on the journal entry itself**, so it commits atomically with the money movement. There's no window where the money has moved but the key isn't recorded, or the other way round.

Notice that a replay returns the original result **even if the balance has changed since**. If Alice has spent everything in the meantime, a retry of her earlier transfer still says "done" instead of "insufficient funds", because it *was* done.

> **Sidebar: separate idempotency table vs a column on the result.** Storing the key on the journal entry works neatly here because one transfer is exactly one entry. Many systems use a dedicated `idempotency_record` table instead (key, fingerprint, response status, response body, expiry), so the same mechanism works for any endpoint and keys can be expired after, say, 24 hours. The Payments tutorial builds that version.

Update `transfer/TransferController.java` to require the header:

```java
    @PostMapping
    public ResponseEntity<JournalEntryResponse> transfer(
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 100) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        TransferResult result = transferService.transfer(idempotencyKey,
                request.fromAccountId(), request.toAccountId(),
                Money.gbp(request.amount().toPlainString()));
        return ResponseEntity.created(URI.create("/journal-entries/" + result.entry().getId()))
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(JournalEntryResponse.from(result.entry()));
    }
```

(Import `RequestHeader` and `jakarta.validation.constraints.Size`. The `JournalEntry` import is no longer needed.)

`@RequestHeader("Idempotency-Key")` is required by default. A request without it gets a `400` automatically, as a `ProblemDetail`, thanks to your `ResponseEntityExceptionHandler`.

**There's a catch, though.** Send a bad amount (`"10.001"`) now, and the field errors from guide 01 have gone:

```json
{"detail":"Validation failure","instance":"/transfers","status":400,"title":"Bad Request"}
```

Putting a constraint (`@Size`) directly on a parameter switches Spring MVC to **method validation** for the whole method. Constraints on plain parameters and the `@Valid` body are then all checked together, and failures arrive as a `HandlerMethodValidationException` instead of the `MethodArgumentNotValidException` that your `handleMethodArgumentNotValid` override handles. (The same exception is behind the statement endpoint's `@Min`/`@Max` errors from step 6.) Override its handler in `ApiExceptionHandler` too:

```java
    /**
     * Used instead of handleMethodArgumentNotValid when a controller method has
     * constraints on plain parameters too (e.g. @Size on a @RequestHeader).
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new TreeMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors bodyErrors) {
                // a @Valid @RequestBody: report each field, as before
                for (FieldError error : bodyErrors.getFieldErrors()) {
                    errors.putIfAbsent(error.getField(), error.getDefaultMessage());
                }
            } else {
                // a plain parameter such as a header or request param
                String name = result.getMethodParameter().getParameterName();
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    errors.putIfAbsent(name, error.getDefaultMessage());
                }
            }
        }
        ProblemDetail problem = ex.getBody();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }
```

(Imports: `org.springframework.context.MessageSourceResolvable`, `org.springframework.validation.method.ParameterErrors`, `org.springframework.validation.method.ParameterValidationResult` and `org.springframework.web.method.annotation.HandlerMethodValidationException`.)

Each `ParameterValidationResult` is one failing method parameter. The body's result is a `ParameterErrors`, which still has the familiar `FieldError`s. A header's result just has messages, reported under the Java parameter name (`idempotencyKey`). The `instanceof` pattern (Java 16+) both tests the type and gives you a typed variable. You'll add tests for both cases in step 9.

Finally, two handlers in `ApiExceptionHandler`. A reused key is a client error (`422`). A unique-constraint violation is the database backstop catching a concurrent duplicate (`409`):

```java
@ExceptionHandler(IdempotencyKeyReusedException.class)
ProblemDetail handle(IdempotencyKeyReusedException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
    problem.setTitle("Idempotency key reused");
    return problem;
}

/** The database's unique constraints are the last line of defence against duplicates. */
@ExceptionHandler(DataIntegrityViolationException.class)
ProblemDetail handle(DataIntegrityViolationException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
            "The request conflicts with data that already exists. Retry it to see the current state.");
    problem.setTitle("Conflict");
    return problem;
}
```

The `409` message deliberately doesn't include `ex.getMessage()`, which contains SQL and constraint names. Never leak database internals to API clients.

**Fix the existing tests.** `TransferService.transfer` has a new signature, and the API now requires the header:

- In `TransferApiTest`'s `transfer(...)` helper, add `.header("Idempotency-Key", UUID.randomUUID().toString())` before `.contentType(...)`.
- In `ConcurrentTransferTest` and `OppositeTransfersTest`, pass `UUID.randomUUID().toString()` as the first argument of every `transferService.transfer(...)` call. Each simulated transfer is a *different* intended operation, so each gets its own key.

Add to `requests.http`, and send the transfer twice:

```http
### Alice pays Bob £25, safely retryable
POST http://localhost:8080/transfers
Content-Type: application/json
Idempotency-Key: abc-123

{"fromAccountId": 2, "toAccountId": 3, "amount": "25.00"}

### Reverse journal entry 1
POST http://localhost:8080/journal-entries/1/reversal

### Alice's statement
GET http://localhost:8080/accounts/2/statement?page=0&size=20
```

The first response has `Idempotent-Replayed: false`, the second `Idempotent-Replayed: true` with the same entry id, and Alice's balance only moves once.

### 9. Idempotency tests

`src/test/java/com/example/ledger/transfer/IdempotencyTest.java`:

```java
package com.example.ledger.transfer;

import com.example.ledger.LedgerIntegrationTest;
import com.example.ledger.account.AccountService;
import com.example.ledger.cash.CashService;
import com.example.ledger.journal.JournalEntryRepository;
import com.example.ledger.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@LedgerIntegrationTest
class IdempotencyTest {

    @Autowired MockMvcTester mvc;
    @Autowired AccountService accountService;
    @Autowired CashService cashService;
    @Autowired TransferService transferService;
    @Autowired JournalEntryRepository entries;

    Long alice;
    Long bob;

    @BeforeEach
    void openAccounts() {
        alice = accountService.open("Alice").getId();
        bob = accountService.open("Bob").getId();
        cashService.deposit(alice, Money.gbp("100.00"));
    }

    @Test
    void retryingWithTheSameKeyDoesNotPayTwice() {
        String key = UUID.randomUUID().toString();

        MvcTestResult first = transfer(key, "25.00");
        MvcTestResult retry = transfer(key, "25.00");

        assertThat(first).hasStatus(HttpStatus.CREATED).hasHeader("Idempotent-Replayed", "false");
        assertThat(retry).hasStatus(HttpStatus.CREATED).hasHeader("Idempotent-Replayed", "true");
        int entryId = entries.findByIdempotencyKey(key).orElseThrow().getId().intValue();
        assertThat(first).bodyJson().extractingPath("$.id").isEqualTo(entryId);
        assertThat(retry).bodyJson().extractingPath("$.id").isEqualTo(entryId);
        assertThat(accountService.get(alice).getBalance()).isEqualTo(Money.gbp("75.00"));
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        String key = UUID.randomUUID().toString();
        transfer(key, "25.00");

        MvcTestResult different = transfer(key, "99.00");

        assertThat(different).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(different).bodyJson().extractingPath("$.title").isEqualTo("Idempotency key reused");
    }

    @Test
    void theHeaderIsRequired() {
        MvcTestResult response = mvc.post().uri("/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("25.00"))
                .exchange();

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void bodyFieldErrorsAreStillReportedAlongsideTheHeader() {
        MvcTestResult response = transfer(UUID.randomUUID().toString(), "10.001");

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors.amount")
                .isEqualTo("numeric value out of bounds (<15 digits>.<2 digits> expected)");
    }

    @Test
    void anEmptyKeyIsRejected() {
        MvcTestResult response = transfer("", "10.00");

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors.idempotencyKey")
                .isEqualTo("size must be between 1 and 100");
    }

    @Test
    void simultaneousRetriesStillPayOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        CountDownLatch startingGun = new CountDownLatch(1);
        Set<Long> entryIds = new HashSet<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            List<Future<TransferResult>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    return transferService.transfer(key, alice, bob, Money.gbp("25.00"));
                }));
            }
            startingGun.countDown();
            for (Future<TransferResult> future : futures) {
                entryIds.add(future.get().entry().getId());
            }
        }

        assertThat(entryIds).hasSize(1);
        assertThat(accountService.get(alice).getBalance()).isEqualTo(Money.gbp("75.00"));
    }

    private MvcTestResult transfer(String key, String amount) {
        return mvc.post().uri("/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(amount))
                .exchange();
    }

    private String body(String amount) {
        return """
                {"fromAccountId": %d, "toAccountId": %d, "amount": "%s"}
                """.formatted(alice, bob, amount);
    }
}
```

`simultaneousRetriesStillPayOnce` is the guide 03 starting-gun pattern pointed at idempotency: ten copies of one request, released together. All ten must report **the same** journal entry, and Alice must be charged **once**. It passes because of the lock-then-check order. Exercise 2 asks you to prove that.

---

## Your turn

### Exercise 1: no reversing a reversal

What should happen if someone reverses entry #42, which is itself the reversal of #41? It would re-apply the original mistake, and the history would read "mistake, cancelled, un-cancelled", which is confusing to every auditor who reads it. If the original *was* right, post it again as a fresh entry.

1. In `LedgerService.reverse`, throw `ReversalNotAllowedException("Entry %d is itself a reversal")` when `original.isReversal()`.
2. Add a test to `ReversalApiTest` that reverses the deposit (using `ledger.reverse(depositId)` to get the reversal's id), then tries to reverse the reversal through the API, and expects a `409`.

<details>
<summary>Solution</summary>

In `LedgerService.reverse`, straight after loading `original`:

```java
        if (original.isReversal()) {
            throw new ReversalNotAllowedException("Entry %d is itself a reversal".formatted(entryId));
        }
```

In `ReversalApiTest`:

```java
    @Test
    void aReversalCannotItselfBeReversed() {
        Long reversalId = ledger.reverse(depositId).getId();

        assertThat(mvc.post().uri("/journal-entries/{id}/reversal", reversalId))
                .hasStatus(HttpStatus.CONFLICT);
    }
```

Setting up through the service (`ledger.reverse`) and asserting through the API is a common, readable test pattern: arrange quickly, then act on the thing you're actually testing.

</details>

### Exercise 2: why "lock, then check"?

Move the idempotency check in `TransferService` to the **top** of the method, before the locks, and run `IdempotencyTest`. What happens to `simultaneousRetriesStillPayOnce`, and why? What stops the ledger from paying twice anyway? Put the check back afterwards.

<details>
<summary>Answer</summary>

Several of the ten threads check the key at the same moment, before any of them has committed, so they all see "new key". They queue on Alice's lock and run one after another, and each tries to insert an entry with the same `idempotency_key`. The first commits. Every later one hits the **unique constraint** and fails with `DataIntegrityViolationException`, which `future.get()` re-throws, so the test fails with errors.

The **money is still correct**: only one transfer is ever committed, thanks to the constraint. But the client experience is worse. Retries that should have quietly returned the original result get `409 Conflict` errors instead. With the check after the locks, duplicates wait their turn and then cleanly find the committed entry.

This is defence in depth working as designed. The application logic gives the right *behaviour*, and the database constraint guarantees *correctness* even when the logic is wrong.

</details>

### Exercise 3 (stretch, no solution given): idempotent cash

A cash machine (ATM) that loses its connection mid-withdrawal will retry too. Add an optional `Idempotency-Key` header to `POST /accounts/{id}/deposits` and `/withdrawals`, reusing the `idempotency_key` column and the lock-then-check pattern. Use `IdempotencyTest` as a model for the tests. Think about what the fingerprint should contain: should a deposit and a withdrawal be allowed to share a key?

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
```

New files since guide 03:

```
src/main/java/com/example/ledger/
├── journal/ReversalNotAllowedException.java
├── statement/
│   ├── Statement.java
│   ├── StatementController.java
│   ├── StatementLine.java
│   └── StatementService.java
└── transfer/
    ├── IdempotencyKeyReusedException.java
    └── TransferResult.java
src/main/resources/db/migration/
└── V4__reversals_and_idempotency.sql
src/test/java/com/example/ledger/
├── journal/
│   ├── ImmutabilityTest.java
│   └── ReversalApiTest.java
├── statement/StatementApiTest.java
└── transfer/IdempotencyTest.java
```

Modified: `JournalEntry.java`, `Posting.java`, `JournalEntryRepository.java`, `PostingRepository.java`, `LedgerService.java`, `JournalEntryController.java`, `JournalEntryResponse.java`, `TransferService.java`, `TransferController.java`, `ApiExceptionHandler.java`, the three existing transfer tests, `requests.http`.

**Try the whole story by hand.** With a fresh database (delete `data/`), run this sequence from `requests.http`: open Alice (id 2) and Bob (id 3), deposit £100 to Alice, transfer £25 to Bob with `Idempotency-Key: abc-123` (twice), reverse entry 1 (the deposit), then fetch Alice's statement. You should see:

```json
{
  "accountId": 2,
  "currentBalance": { "amount": "-25.00", "currency": "GBP" },
  "totalLines": 3,
  "lines": [
    { "journalEntryId": 3, "description": "Reversal of entry 1",  "amount": { "amount": "-100.00" }, "balanceAfter": { "amount": "-25.00" } },
    { "journalEntryId": 2, "description": "Transfer from 2 to 3", "amount": { "amount": "-25.00" },  "balanceAfter": { "amount": "75.00" } },
    { "journalEntryId": 1, "description": "Cash deposit",         "amount": { "amount": "100.00" },  "balanceAfter": { "amount": "100.00" } }
  ]
}
```

(Trimmed for readability.) Only one transfer appears, despite the retry. The full history survives the reversal. And Alice is at −£25, because the deposit she'd spent from turned out never to have been hers.

---

## Common errors

**`400 Bad Request` with `"detail": "Required header 'Idempotency-Key' is not present."`**
Working as intended. Every `POST /transfers` now needs the header. If it's one of your tests, add `.header("Idempotency-Key", UUID.randomUUID().toString())`.

**`400` with `"detail": "Validation failure"` and no `errors` map**
A controller method has a constraint on a plain parameter (like `@Size` on the header), so Spring used method validation and raised `HandlerMethodValidationException`. Override `handleHandlerMethodValidationException` as shown in step 8.

**`InterpretationException: … The query attempts to update an immutable entity`**
Some code tried a JPQL `update` on `JournalEntry` or `Posting`. Ask why it needed to. The answer is almost always "post a reversal instead".

**`409 Conflict` on a reversal: `"Entry 7 has already been reversed"`**
Working as intended: each entry can be reversed once. If you see a `409` with the generic "conflicts with data that already exists" message instead, two reversals of the same entry raced, and the `uk_journal_entry_reverses` constraint caught the second.

**`LazyInitializationException` while building a statement**
`findByAccountIdOrderByIdDesc` is missing `@EntityGraph(attributePaths = "entry")`, or `StatementService.statement` isn't `@Transactional`. `posting.getEntry().getDescription()` needs the entry loaded.

**A statement's running balances are wrong on page 2 and later**
Check that `balanceAfter` uses `p.id <= :maxPostingId` (less than *or equal*), and that the page is ordered newest first, to match the walk-backwards loop.

**`simultaneousRetriesStillPayOnce` fails with `DataIntegrityViolationException`**
The idempotency check runs before the locks. See Exercise 2.

---

## Recap

- **Ledgers are insert-only.** Mistakes are corrected with **reversals**, mirror-image entries linked to the original, never with `UPDATE` or `DELETE`. History is the audit trail.
- **Hibernate's `@Immutable`** ignores entity changes and rejects JPQL updates, but **does not stop deletes**. Real immutability needs layers: no code paths that modify, foreign keys, and database permissions.
- **Statements** are built from the postings. A running balance on any page takes one aggregate query ("balance after the newest line") and a walk backwards.
- **Idempotency keys** make retries safe. Store the key **atomically with the result**, replay the original result for the same request, and reject the same key used for a different request.
- **Check idempotency after taking the locks**, and back it up with a **unique constraint**: the logic gives the right behaviour, and the database guarantees correctness.
- **`422`** means the request breaks a rule. **`409`** means it conflicts with the current state.

### Interview angle

<details>
<summary>"A customer was credited £500 by mistake. How do you fix it in the ledger?"</summary>

Never edit or delete the original entry. Post a reversal (a new entry with every debit and credit flipped, linked to the original), then post the correct entry if one is needed. The history stays complete for audit, every past report stays reproducible, and the correction is itself recorded. If the customer has already spent the money, the reversal can take them overdrawn, which is the truth, and gets handled operationally.

</details>

<details>
<summary>"How would you make a payment API safe to retry?"</summary>

Require an idempotency key (a client-generated UUID per intended operation) in a header. Store it in the same transaction as the result, with a fingerprint of the request. On a repeat: same key and same request returns the stored result without redoing the work, and same key with a different request returns an error. Handle concurrent duplicates by serialising on the affected resource (lock first, then check), plus a unique constraint on the key as a backstop. Mention key expiry, and that the key must be stored atomically with the side effect.

</details>

<details>
<summary>"What's the difference between 409 and 422?"</summary>

`422 Unprocessable Content`: the request is well-formed but semantically invalid against the business rules, so the same request will keep failing (insufficient funds, invalid transfer). `409 Conflict`: the request conflicts with the current state of the resource (already reversed, a concurrent modification), so understanding or refreshing that state may resolve it.

</details>

<details>
<summary>"How do you paginate a statement with running balances efficiently?"</summary>

Order by a stable, unique, indexed key (posting id or sequence number). For each page, compute the balance after the newest posting on the page with one aggregate query (the sum of postings up to that id), then derive each line's balance by walking backwards through the page. That's constant work per page regardless of depth. At very large scale, add periodic balance snapshots so the aggregate only covers postings since the last snapshot, and use keyset (seek) pagination instead of offsets.

</details>

### Further reading

- [IETF draft: The Idempotency-Key HTTP Header Field](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/)
- [Stripe: Designing robust and predictable APIs with idempotency](https://stripe.com/blog/idempotency)
- [Hibernate ORM user guide: `@Immutable`](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#entity-immutability)
- [Use The Index, Luke: paging through results (keyset pagination)](https://use-the-index-luke.com/no-offset)

---

## You've built a core ledger

Across four guides you've built the foundation that every other banking system writes to:

| Guide | Banking idea | Java/Spring skill |
|---|---|---|
| 01 | Your balance is the bank's liability | `Money` value object, `BigDecimal`, `ProblemDetail`, `MockMvcTester` |
| 02 | Double-entry: debits always equal credits | Domain invariants, builders, `@EntityGraph`, invariant testing |
| 03 | No double spending | Optimistic and pessimistic locking, deadlocks and lock ordering, concurrency tests |
| 04 | Never edit, always reverse. Safe retries | Insert-only design, `@Immutable`, pagination, idempotency keys |

**Where to go next:**

- **The [Payments tutorial](../payments/README.md)** moves money *between banks*: a payment's lifecycle as a state machine, idempotency across systems, and the transactional outbox for reliably telling other systems what happened.
- **Extend this ledger yourself:** an arranged overdraft limit per account; a nightly job (`@Scheduled`) that compares every cached balance with its derived balance and logs any break; switching to PostgreSQL with Docker Compose and checking that every test still passes.
