# 02: Double-Entry Postings

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- **Double-entry bookkeeping**: debits, credits, and why every movement of money is written down at least twice
- How a ledger stores movements as **journal entries** made of **postings**
- The difference between a **cached balance** and a **derived balance**, and why a real ledger keeps both
- How to make invalid states impossible: a **builder** that won't produce an unbalanced entry
- Seeding reference data (the bank's own cash account) with Flyway, guarded by database `CHECK` constraints
- `@EntityGraph` for loading what you need inside a transaction, and aggregate `@Query` methods
- A **randomised invariant test**: hundreds of random operations, then a check that the rules still hold

By the end, customers can pay cash in and take it out, and every movement is a balanced journal entry that you can fetch, inspect and verify.

---

## Concepts

### How it works in a real bank

[Double-entry bookkeeping](../glossary.md#double-entry) was written down by the Italian mathematician Luca Pacioli in 1494, and banks still run on it. The rule is simple:

> **Every movement of money is recorded as debits and credits that add up to the same amount.**

Money is never created or destroyed. It only moves between accounts. If £100 of cash comes in over the counter, two things change in the bank's books: the bank has £100 more cash, and it owes the customer £100 more. Write down only one of them and the books no longer balance. That is exactly how auditors, and your tests, can tell something is wrong.

Each business event is a [journal entry](../glossary.md#journal-entry), and each line of it is a [posting](../glossary.md#posting):

```
Journal entry #1: "Cash deposit"
   DEBIT   Bank cash (asset)          £100.00
   CREDIT  Alice's account (liability) £100.00
                                      ───────
             total debits  = £100.00
             total credits = £100.00   ✔ balanced
```

All the postings together form the [general ledger](../glossary.md#general-ledger), or, more precisely in our case, a [sub-ledger](../glossary.md#sub-ledger) of customer accounts that a real bank would roll up into its general ledger.

### Debits and credits are not "minus" and "plus"

This is the part almost everyone gets wrong. In everyday speech "debit" means money out of *your* account. In bookkeeping, debit and credit are just the **left** and **right** sides of an entry. Whether they make a balance go up or down depends on the type of account:

| Account type | A **debit**… | A **credit**… | Normal balance |
|---|---|---|---|
| [Asset](../glossary.md#asset) (the bank's cash) | increases it | decreases it | Debit |
| [Liability](../glossary.md#liability) (a customer's account) | decreases it | increases it | Credit |

The [normal balance](../glossary.md#normal-balance) is the side that makes the account grow. When your bank says it has "credited your account", it means its *liability to you* went up. That's the bank's point of view, which is the one the ledger takes.

The three movements in this tutorial:

| Event | Debit | Credit |
|---|---|---|
| Customer pays £100 cash in | Bank cash +£100 | Customer +£100 |
| Customer takes £30 cash out | Customer −£30 | Bank cash −£30 |
| Alice transfers £20 to Bob (guide 03) | Alice −£20 | Bob +£20 |

Check each row: the debit and the credit are always the same amount. That's the invariant.

> **Sidebar: why not just store signed amounts?** Many real systems do: `+100` on one account and `-100` on another, with the rule "every entry sums to zero". It's equivalent and simpler to query. This tutorial uses explicit `DEBIT`/`CREDIT` because that's the vocabulary you'll hear in a bank, and because seeing *why* a credit increases a liability is the point of the lesson. In an interview, knowing both representations is a plus.

### Cached balance vs derived balance

Where does an account's balance come from? There are two options:

- **Derived:** add up all the account's postings. It's always correct, because the postings *are* the truth, but it gets slower as history grows. A ten-year-old current account might have tens of thousands of postings.
- **Cached:** keep a `balance` column on the account and update it every time a posting is written. Reading it is instant, but it's only correct if every code path keeps it in step.

Real ledgers do **both**. Postings are the source of truth, and the cached [balance](../glossary.md#cached-balance) is updated **in the same database transaction** as the postings, so they can never be committed separately. Then you prove they agree: in tests now, and in production with a nightly reconciliation job that recomputes the [derived balance](../glossary.md#derived-balance) of every account and raises an alarm on any difference.

That cached `balance` column is also what makes concurrency dangerous, as you'll see in guide 03.

### Make invalid entries impossible

Where should the "debits must equal credits" rule live? If it's in a service, someone will eventually write another service that forgets it. Put it in the **domain model**: a `JournalEntry` can only be created through a builder whose `build()` method checks the rules and throws if they're broken. There is simply no way to get an unbalanced `JournalEntry` object.

The database adds a second line of defence with `CHECK` constraints: amounts must be positive, and the direction must be `DEBIT` or `CREDIT`. Even a bad SQL script can't break those rules.

---

## Steps

### 1. The journal tables, and the bank's cash account

Create `src/main/resources/db/migration/V2__create_journal.sql`:

```sql
-- Internal (bank-owned) accounts are identified by a code, e.g. CASH
ALTER TABLE account ADD COLUMN system_code VARCHAR(30);
ALTER TABLE account ADD CONSTRAINT uk_account_system_code UNIQUE (system_code);

INSERT INTO account (name, type, currency, balance, opened_at, system_code)
VALUES ('Bank cash', 'ASSET', 'GBP', 0, CURRENT_TIMESTAMP, 'CASH');

CREATE TABLE journal_entry (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    description VARCHAR(200)             NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE posting (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    journal_entry_id BIGINT         NOT NULL REFERENCES journal_entry (id),
    account_id       BIGINT         NOT NULL REFERENCES account (id),
    direction        VARCHAR(6)     NOT NULL,
    amount           NUMERIC(19, 4) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    CONSTRAINT ck_posting_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_posting_amount_positive CHECK (amount > 0)
);

CREATE INDEX ix_posting_account ON posting (account_id);
```

- **`system_code`** marks [internal accounts](../glossary.md#internal-account). Customer accounts leave it `NULL`. The unique constraint means there can only ever be one `CASH` account. (In most databases, including H2 and PostgreSQL, a unique constraint allows any number of `NULL`s.)
- **The `INSERT` is reference data**: the bank's own cash account has to exist before anyone can deposit. Seeding it in a migration means every environment, including your tests, gets it automatically. On a fresh database it gets `id` 1, so your first customer account will be `id` 2.
- **Amounts are always positive.** The *direction* says which way they go. A negative amount in a ledger is almost always a bug.
- **`ix_posting_account`** speeds up "all postings for this account", which statements and derived balances need constantly.

> **Remember:** never edit `V1`. Changes to an existing table go in a new migration, which is why `system_code` is added with `ALTER TABLE` here.

### 2. Directions and normal balances

Create `journal/Direction.java`:

```java
package com.example.ledger.journal;

public enum Direction {
    DEBIT,
    CREDIT
}
```

Teach `AccountType` its normal balance. Replace `account/AccountType.java`:

```java
package com.example.ledger.account;

import com.example.ledger.journal.Direction;

/**
 * Which side of the bank's balance sheet an account sits on.
 * A customer's current account is money the bank OWES the customer, so to
 * the bank it is a LIABILITY. The bank's own cash is an ASSET.
 * <p>
 * The "normal balance" is the direction that makes the balance go up.
 */
public enum AccountType {
    ASSET(Direction.DEBIT),
    LIABILITY(Direction.CREDIT);

    private final Direction normalBalance;

    AccountType(Direction normalBalance) {
        this.normalBalance = normalBalance;
    }

    public Direction normalBalance() {
        return normalBalance;
    }
}
```

Enums with fields and constructors are an underused Java feature. All the debit/credit rules from the Concepts table are now in one place, with no `if (type == ASSET && …)` scattered around the code.

### 3. Teach `Account` to apply a posting

In `account/Account.java`, map the new column, then add the **one** method that's allowed to change a balance. Add the field after `openedAt`:

```java
    @Column(name = "system_code", length = 30, unique = true)
    private String systemCode;
```

Then add this method above the getters:

```java
    /**
     * Updates the cached balance for one posting. Only the ledger calls this,
     * always in the same transaction that saves the posting.
     */
    public void apply(Direction direction, Money amount) {
        if (!amount.currency().equals(getCurrency())) {
            throw new IllegalArgumentException(
                    "Account %d is in %s, not %s".formatted(id, getCurrency(), amount.currency()));
        }
        Money current = getBalance();
        Money updated = direction == type.normalBalance() ? current.plus(amount) : current.minus(amount);
        this.balance = updated.amount();
    }
```

Add a getter too:

```java
    public String getSystemCode() {
        return systemCode;
    }
```

(Import `com.example.ledger.journal.Direction`.)

The arithmetic goes through `Money`, so the currency check and the fixed scale come for free.

Add a finder to `account/AccountRepository.java`:

```java
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findBySystemCode(String systemCode);
}
```

And a method to `account/AccountService.java`:

```java
    @Transactional(readOnly = true)
    public Account getSystemAccount(String systemCode) {
        return accounts.findBySystemCode(systemCode)
                .orElseThrow(() -> new IllegalStateException("Missing system account " + systemCode));
    }
```

This throws `IllegalStateException` rather than a "not found" exception on purpose. A missing `CASH` account isn't a bad client request. It means the deployment is broken, and a `500` is the honest answer.

### 4. The `Posting` entity

Create `journal/Posting.java`:

```java
package com.example.ledger.journal;

import com.example.ledger.account.Account;
import com.example.ledger.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Currency;

/** One line of a journal entry: a debit or credit of one account. */
@Entity
@Table(name = "posting")
public class Posting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id")
    private JournalEntry entry;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id")
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private Direction direction;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currencyCode;

    protected Posting() {
        // for JPA
    }

    Posting(JournalEntry entry, Account account, Direction direction, Money amount) {
        this.entry = entry;
        this.account = account;
        this.direction = direction;
        this.amount = amount.amount();
        this.currencyCode = amount.currency().getCurrencyCode();
    }

    public Long getId() {
        return id;
    }

    public Account getAccount() {
        return account;
    }

    public Direction getDirection() {
        return direction;
    }

    public Money getAmount() {
        return new Money(amount, Currency.getInstance(currencyCode));
    }
}
```

The constructor is **package-private** (no `public`). Only classes in `journal`, meaning the `JournalEntry` builder, can create postings. Nobody can make a stray posting that isn't part of a balanced entry.

`FetchType.LAZY` on both `@ManyToOne`s matters. The JPA default for `@ManyToOne` is `EAGER`, which would load the whole account and entry every time you touch a posting.

### 5. The `JournalEntry` and its builder

Create `journal/LedgerInvariantException.java`:

```java
package com.example.ledger.journal;

/** Thrown when code tries to build a journal entry that breaks the rules of double-entry. */
public class LedgerInvariantException extends RuntimeException {

    public LedgerInvariantException(String message) {
        super(message);
    }
}
```

Then `journal/JournalEntry.java`:

```java
package com.example.ledger.journal;

import com.example.ledger.account.Account;
import com.example.ledger.money.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;

/**
 * A single business event recorded in the ledger, made of two or more
 * postings whose debits and credits add up to the same amount.
 */
@Entity
@Table(name = "journal_entry")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.PERSIST)
    private List<Posting> postings = new ArrayList<>();

    protected JournalEntry() {
        // for JPA
    }

    private JournalEntry(String description) {
        this.description = description;
        this.createdAt = Instant.now();
    }

    public static Builder builder(String description) {
        return new Builder(description);
    }

    public Long getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<Posting> getPostings() {
        return Collections.unmodifiableList(postings);
    }

    public static final class Builder {

        private final JournalEntry entry;

        private Builder(String description) {
            this.entry = new JournalEntry(description);
        }

        public Builder debit(Account account, Money amount) {
            return add(account, Direction.DEBIT, amount);
        }

        public Builder credit(Account account, Money amount) {
            return add(account, Direction.CREDIT, amount);
        }

        private Builder add(Account account, Direction direction, Money amount) {
            if (!amount.isPositive()) {
                throw new LedgerInvariantException("Posting amounts must be positive, got " + amount);
            }
            if (!account.getCurrency().equals(amount.currency())) {
                throw new LedgerInvariantException("Cannot post %s to a %s account"
                        .formatted(amount, account.getCurrency()));
            }
            entry.postings.add(new Posting(entry, account, direction, amount));
            return this;
        }

        /** Checks the double-entry rules and returns the entry. */
        public JournalEntry build() {
            List<Posting> postings = entry.postings;
            if (postings.size() < 2) {
                throw new LedgerInvariantException("A journal entry needs at least two postings");
            }
            Currency currency = postings.getFirst().getAmount().currency();
            Money debits = Money.zero(currency);
            Money credits = Money.zero(currency);
            for (Posting posting : postings) {
                if (!posting.getAmount().currency().equals(currency)) {
                    throw new LedgerInvariantException("All postings in an entry must use one currency");
                }
                if (posting.getDirection() == Direction.DEBIT) {
                    debits = debits.plus(posting.getAmount());
                } else {
                    credits = credits.plus(posting.getAmount());
                }
            }
            if (!debits.equals(credits)) {
                throw new LedgerInvariantException(
                        "Unbalanced entry: debits %s, credits %s".formatted(debits, credits));
            }
            return entry;
        }
    }
}
```

How it fits together:

- **The only way in is `JournalEntry.builder(...)`.** The constructors are `private` (for us) and `protected` (for JPA only).
- **`build()` is the gatekeeper.** At least two postings, one currency, and debits equal to credits. Notice the check compares `Money` with `equals`. That's safe only because `Money` normalises the scale, which is exactly the trap you avoided in guide 01.
- **`cascade = CascadeType.PERSIST`** means saving the entry also saves its postings. Without it, Hibernate complains that the entry refers to unsaved postings.
- **`getPostings()` returns an unmodifiable view**, so nobody can add an unbalanced posting after `build()` has checked the list.
- `postings.getFirst()` is a **Java 21** sequenced-collection method. On older Java, write `postings.get(0)`.

Usage reads almost like the bookkeeping table in the Concepts section:

```java
JournalEntry entry = JournalEntry.builder("Cash deposit")
        .debit(cash, amount)
        .credit(customer, amount)
        .build();
```

> **Sidebar: why a hand-written builder and not Lombok's `@Builder`?** Lombok's builder sets fields. It can't run validation across all of them before handing the object over. A hand-written builder with a checking `build()` is how you put invariants in the domain. You'll see the same pattern for payments and loans.

### 6. Unit-test the rules

These tests need no Spring and no database, so they run in milliseconds. Create `src/test/java/com/example/ledger/journal/JournalEntryTest.java`:

```java
package com.example.ledger.journal;

import com.example.ledger.account.Account;
import com.example.ledger.money.Money;
import org.junit.jupiter.api.Test;

import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalEntryTest {

    private final Account alice = Account.openCustomerAccount("Alice");
    private final Account bob = Account.openCustomerAccount("Bob");

    @Test
    void buildsABalancedEntry() {
        JournalEntry entry = JournalEntry.builder("Test")
                .debit(alice, Money.gbp("25.00"))
                .credit(bob, Money.gbp("25.00"))
                .build();

        assertThat(entry.getPostings()).hasSize(2);
    }

    @Test
    void rejectsAnUnbalancedEntry() {
        var builder = JournalEntry.builder("Broken")
                .debit(alice, Money.gbp("25.00"))
                .credit(bob, Money.gbp("24.99"));

        assertThatThrownBy(builder::build)
                .isInstanceOf(LedgerInvariantException.class)
                .hasMessageContaining("Unbalanced");
    }

    @Test
    void rejectsASinglePosting() {
        var builder = JournalEntry.builder("Lonely").debit(alice, Money.gbp("1.00"));

        assertThatThrownBy(builder::build).isInstanceOf(LedgerInvariantException.class);
    }

    @Test
    void rejectsZeroAndNegativeAmounts() {
        assertThatThrownBy(() -> JournalEntry.builder("Zero").debit(alice, Money.gbp("0")))
                .isInstanceOf(LedgerInvariantException.class);
        assertThatThrownBy(() -> JournalEntry.builder("Negative").credit(alice, Money.gbp("-5")))
                .isInstanceOf(LedgerInvariantException.class);
    }

    @Test
    void rejectsAmountsInTheWrongCurrency() {
        Money euros = Money.of("5.00", Currency.getInstance("EUR"));

        assertThatThrownBy(() -> JournalEntry.builder("FX").debit(alice, euros))
                .isInstanceOf(LedgerInvariantException.class);
    }
}
```

`Account.openCustomerAccount(...)` works fine outside Spring. The accounts just don't have ids yet, which these tests don't need. Run `JournalEntryTest` from the gutter arrow and watch it finish in well under a second.

### 7. The ledger service: posting and deriving

Create `journal/JournalEntryRepository.java`:

```java
package com.example.ledger.journal;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    @EntityGraph(attributePaths = "postings")
    Optional<JournalEntry> findWithPostingsById(Long id);
}
```

`@EntityGraph(attributePaths = "postings")` tells Spring Data to load the entry **and** its postings in one query (a `JOIN`), instead of loading the postings lazily later. The method name `findWithPostingsById` is just a readable name. Spring Data ignores the words between `find` and `By`.

Create `journal/PostingRepository.java`:

```java
package com.example.ledger.journal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;

public interface PostingRepository extends JpaRepository<Posting, Long> {

    @Query("""
            select coalesce(sum(p.amount), 0)
            from Posting p
            where p.account.id = :accountId and p.direction = :direction
            """)
    BigDecimal sumByAccountAndDirection(Long accountId, Direction direction);

    @Query("select coalesce(sum(p.amount), 0) from Posting p where p.direction = :direction")
    BigDecimal sumByDirection(Direction direction);
}
```

`coalesce(sum(...), 0)` returns `0` instead of `null` when there are no rows. A fresh account has no postings, and `null` would blow up the arithmetic.

Now `journal/LedgerService.java`:

```java
package com.example.ledger.journal;

import com.example.ledger.account.Account;
import com.example.ledger.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class LedgerService {

    private final JournalEntryRepository entries;
    private final PostingRepository postings;

    public LedgerService(JournalEntryRepository entries, PostingRepository postings) {
        this.entries = entries;
        this.postings = postings;
    }

    /**
     * Saves the entry and updates every affected account's cached balance,
     * all in one transaction: either everything is written, or nothing is.
     */
    @Transactional
    public JournalEntry post(JournalEntry entry) {
        for (Posting posting : entry.getPostings()) {
            posting.getAccount().apply(posting.getDirection(), posting.getAmount());
        }
        return entries.save(entry);
    }

    /** The balance worked out from scratch from the postings: the source of truth. */
    @Transactional(readOnly = true)
    public Money derivedBalance(Account account) {
        BigDecimal debits = postings.sumByAccountAndDirection(account.getId(), Direction.DEBIT);
        BigDecimal credits = postings.sumByAccountAndDirection(account.getId(), Direction.CREDIT);
        BigDecimal balance = account.getType().normalBalance() == Direction.DEBIT
                ? debits.subtract(credits)
                : credits.subtract(debits);
        return new Money(balance, account.getCurrency());
    }
}
```

`post` is the **only** place in the application that calls `Account.apply`. It updates each account's cached balance, then saves the entry (which cascades to the postings). There's no `accounts.save(...)`: the accounts were loaded in this transaction, so they're **managed** entities, and Hibernate writes their changes automatically at commit (dirty checking).

Everything happens in one transaction. If saving the postings fails, the balance changes roll back too. Cached and derived balances can't drift apart because of a half-finished write.

### 8. Deposits and withdrawals

Create `cash/InsufficientFundsException.java`:

```java
package com.example.ledger.cash;

import com.example.ledger.money.Money;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(Long accountId, Money balance, Money requested) {
        super("Account %d has %s available; %s was requested".formatted(accountId, balance, requested));
    }
}
```

`cash/CashService.java`:

```java
package com.example.ledger.cash;

import com.example.ledger.account.Account;
import com.example.ledger.account.AccountService;
import com.example.ledger.journal.JournalEntry;
import com.example.ledger.journal.LedgerService;
import com.example.ledger.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cash paid in and out over the counter: the simplest two-posting entries. */
@Service
public class CashService {

    private final AccountService accountService;
    private final LedgerService ledger;

    public CashService(AccountService accountService, LedgerService ledger) {
        this.accountService = accountService;
        this.ledger = ledger;
    }

    @Transactional
    public JournalEntry deposit(Long accountId, Money amount) {
        Account customer = accountService.get(accountId);
        Account cash = accountService.getSystemAccount("CASH");
        JournalEntry entry = JournalEntry.builder("Cash deposit")
                .debit(cash, amount)       // the bank's cash goes up (asset)
                .credit(customer, amount)  // what the bank owes the customer goes up (liability)
                .build();
        return ledger.post(entry);
    }

    @Transactional
    public JournalEntry withdraw(Long accountId, Money amount) {
        Account customer = accountService.get(accountId);
        if (customer.getBalance().isLessThan(amount)) {
            throw new InsufficientFundsException(accountId, customer.getBalance(), amount);
        }
        Account cash = accountService.getSystemAccount("CASH");
        JournalEntry entry = JournalEntry.builder("Cash withdrawal")
                .debit(customer, amount)
                .credit(cash, amount)
                .build();
        return ledger.post(entry);
    }
}
```

`CashService.deposit` is `@Transactional`, and so are the `AccountService` and `LedgerService` methods it calls. With the default propagation (`REQUIRED`), the inner methods **join** the outer transaction rather than starting their own. The whole deposit is one unit of work.

> **Look closely at `withdraw`.** It reads the balance, checks it, then writes. What happens if two withdrawals for the same account run at exactly the same moment? Hold that thought. It's the whole of guide 03.

### 9. The API: deposits, withdrawals and journal entries

The response for anything that creates an entry is the entry itself. Create `journal/JournalEntryResponse.java`:

```java
package com.example.ledger.journal;

import com.example.ledger.money.Money;

import java.time.Instant;
import java.util.List;

public record JournalEntryResponse(Long id, String description, Instant createdAt, List<PostingResponse> postings) {

    public record PostingResponse(Long accountId, Direction direction, Money amount) {
    }

    public static JournalEntryResponse from(JournalEntry entry) {
        List<PostingResponse> postings = entry.getPostings().stream()
                .map(p -> new PostingResponse(p.getAccount().getId(), p.getDirection(), p.getAmount()))
                .toList();
        return new JournalEntryResponse(entry.getId(), entry.getDescription(), entry.getCreatedAt(), postings);
    }
}
```

`p.getAccount().getId()` doesn't trigger a database query, even though `account` is lazy. Hibernate's lazy proxy already knows its own id (it's the foreign key value it was created from), so asking for it doesn't load the account.

`cash/CashController.java`:

```java
package com.example.ledger.cash;

import com.example.ledger.journal.JournalEntry;
import com.example.ledger.journal.JournalEntryResponse;
import com.example.ledger.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;

@RestController
@RequestMapping("/accounts/{accountId}")
public class CashController {

    private final CashService cashService;

    public CashController(CashService cashService) {
        this.cashService = cashService;
    }

    @PostMapping("/deposits")
    public ResponseEntity<JournalEntryResponse> deposit(@PathVariable Long accountId,
                                                        @Valid @RequestBody CashRequest request) {
        return created(cashService.deposit(accountId, Money.gbp(request.amount().toPlainString())));
    }

    @PostMapping("/withdrawals")
    public ResponseEntity<JournalEntryResponse> withdraw(@PathVariable Long accountId,
                                                         @Valid @RequestBody CashRequest request) {
        return created(cashService.withdraw(accountId, Money.gbp(request.amount().toPlainString())));
    }

    private ResponseEntity<JournalEntryResponse> created(JournalEntry entry) {
        return ResponseEntity.created(URI.create("/journal-entries/" + entry.getId()))
                .body(JournalEntryResponse.from(entry));
    }

    public record CashRequest(@NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount) {
    }
}
```

**Validation happens at the edge.** `@Digits(integer = 15, fraction = 2)` turns `"10.001"` into a clean `400` with a field error *before* it ever reaches `Money`, whose exception would otherwise become a `500`. The request accepts the amount as a JSON string (`"100.00"`). Jackson converts it to `BigDecimal` exactly, without going through `double`.

The deposit returns `201 Created` with `Location: /journal-entries/{id}`, so clients need a way to fetch an entry. Create `journal/JournalEntryNotFoundException.java`:

```java
package com.example.ledger.journal;

public class JournalEntryNotFoundException extends RuntimeException {

    public JournalEntryNotFoundException(Long id) {
        super("No journal entry with id " + id);
    }
}
```

And `journal/JournalEntryController.java`:

```java
package com.example.ledger.journal;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/journal-entries")
public class JournalEntryController {

    private final JournalEntryRepository entries;

    public JournalEntryController(JournalEntryRepository entries) {
        this.entries = entries;
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public JournalEntryResponse get(@PathVariable Long id) {
        return entries.findWithPostingsById(id)
                .map(JournalEntryResponse::from)
                .orElseThrow(() -> new JournalEntryNotFoundException(id));
    }
}
```

The `@EntityGraph` loads the postings, and the mapping to a response record happens **inside** the read-only transaction. With `open-in-view=false`, that's what stops a `LazyInitializationException`.

### 10. Try it

Restart the app. Flyway applies `V2` to your existing database:

```
Migrating schema "PUBLIC" to version "2 - create journal"
```

Add to `requests.http` (if you deleted `data/` since guide 01, Alice will be id 2, because the cash account takes id 1):

```http
### Deposit £100 into account 2
POST http://localhost:8080/accounts/2/deposits
Content-Type: application/json

{"amount": "100.00"}

### Withdraw £30
POST http://localhost:8080/accounts/2/withdrawals
Content-Type: application/json

{"amount": "30.00"}

### Fetch journal entry 1
GET http://localhost:8080/journal-entries/1

### Look at the bank's cash account
GET http://localhost:8080/accounts/1
```

The deposit returns the balanced entry:

```json
{
  "id": 1,
  "description": "Cash deposit",
  "createdAt": "2026-11-06T22:19:39.127629350Z",
  "postings": [
    { "accountId": 1, "direction": "DEBIT",  "amount": { "amount": "100.00", "currency": "GBP" } },
    { "accountId": 2, "direction": "CREDIT", "amount": { "amount": "100.00", "currency": "GBP" } }
  ]
}
```

Now **look at the books directly**. Open `http://localhost:8080/h2-console`, set the JDBC URL to `jdbc:h2:file:./data/ledger` and connect (user `sa`, blank password). Then run:

```sql
SELECT direction, SUM(amount) FROM posting GROUP BY direction;
```

There are two rows with identical totals: the ledger balances. That one query is a **trial balance**, the oldest check in accounting.

### 11. API tests for cash

Create `src/test/java/com/example/ledger/cash/CashApiTest.java`:

```java
package com.example.ledger.cash;

import com.example.ledger.LedgerIntegrationTest;
import com.example.ledger.account.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

@LedgerIntegrationTest
class CashApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    AccountService accountService;

    Long accountId;

    @BeforeEach
    void openAccount() {
        accountId = accountService.open("Alice").getId();
    }

    @Test
    void depositCreditsTheCustomerAndDebitsCash() {
        MvcTestResult response = post("/deposits", "100.00");

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).bodyJson().extractingPath("$.postings.length()").isEqualTo(2);
        assertThat(response).bodyJson().extractingPath("$.postings[0].direction").isEqualTo("DEBIT");
        assertThat(response).bodyJson().extractingPath("$.postings[1].accountId").isEqualTo(accountId.intValue());
        assertThat(response).bodyJson().extractingPath("$.postings[1].direction").isEqualTo("CREDIT");
        assertThat(balance()).isEqualTo("100.00");
    }

    @Test
    void withdrawalReducesTheBalance() {
        post("/deposits", "100.00");

        assertThat(post("/withdrawals", "30.00")).hasStatus(HttpStatus.CREATED);
        assertThat(balance()).isEqualTo("70.00");
    }

    @Test
    void rejectsNegativeAndFractionalPennyAmounts() {
        assertThat(post("/deposits", "-5.00")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(post("/deposits", "0.001")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(balance()).isEqualTo("0.00");
    }

    private MvcTestResult post(String path, String amount) {
        return mvc.post().uri("/accounts/{id}" + path, accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount": "%s"}
                        """.formatted(amount))
                .exchange();
    }

    private String balance() {
        return accountService.get(accountId).getBalance().amount().toPlainString();
    }
}
```

- Each test opens a **fresh account** in `@BeforeEach`. All the integration tests share one in-memory database, so tests must never assume it's empty, and must only make assertions about data they created.
- `.exchange()` runs the request immediately and returns an `MvcTestResult`, which is handy when a helper method sends the request and the test asserts on it later.
- The test reads the balance through `AccountService` rather than the API. Asserting on the database state, not just the HTTP response, is what proves the posting really happened.

### 12. The invariant test

Example-based tests check the cases you thought of. An **invariant test** checks rules that must hold *whatever* happens, by making lots of random things happen. (This is the idea behind property-based testing libraries such as jqwik. Here you'll write a simple version by hand.)

Create `src/test/java/com/example/ledger/journal/LedgerInvariantsTest.java`:

```java
package com.example.ledger.journal;

import com.example.ledger.LedgerIntegrationTest;
import com.example.ledger.account.Account;
import com.example.ledger.account.AccountRepository;
import com.example.ledger.account.AccountService;
import com.example.ledger.cash.CashService;
import com.example.ledger.cash.InsufficientFundsException;
import com.example.ledger.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs hundreds of random deposits and withdrawals, then checks the rules
 * that must hold no matter what happened.
 */
@LedgerIntegrationTest
class LedgerInvariantsTest {

    @Autowired AccountService accountService;
    @Autowired AccountRepository accounts;
    @Autowired CashService cashService;
    @Autowired LedgerService ledger;
    @Autowired PostingRepository postings;

    @Test
    void randomActivityNeverBreaksTheLedger() {
        Random random = new Random(42); // fixed seed: the same "random" run every time
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(accountService.open("Customer " + i).getId());
        }

        for (int i = 0; i < 300; i++) {
            Long id = ids.get(random.nextInt(ids.size()));
            Money amount = Money.gbp("%d.%02d".formatted(random.nextInt(200), random.nextInt(100)));
            if (amount.isZero()) {
                continue;
            }
            try {
                if (random.nextBoolean()) {
                    cashService.deposit(id, amount);
                } else {
                    cashService.withdraw(id, amount);
                }
            } catch (InsufficientFundsException expected) {
                // fine: the ledger refused, and nothing was written
            }
        }

        // 1. Across the whole ledger, debits equal credits
        BigDecimal debits = postings.sumByDirection(Direction.DEBIT);
        BigDecimal credits = postings.sumByDirection(Direction.CREDIT);
        assertThat(debits).isEqualByComparingTo(credits);

        for (Account account : accounts.findAll()) {
            // 2. Every cached balance matches the balance rebuilt from postings
            assertThat(account.getBalance())
                    .as("cached vs derived balance of account %d", account.getId())
                    .isEqualTo(ledger.derivedBalance(account));
            // 3. No customer account is overdrawn
            if (account.getSystemCode() == null) {
                assertThat(account.getBalance().isNegative()).isFalse();
            }
        }
    }
}
```

The three assertions are the three promises a ledger makes:

1. **The books balance**: total debits equal total credits. `isEqualByComparingTo` uses `compareTo`, because the sums come back from the database as `NUMERIC(19,4)` with scale 4.
2. **The cached balance tells the truth**: it matches the postings for every account in the database, not just this test's.
3. **The business rule holds**: no customer is overdrawn.

The **fixed seed** (`new Random(42)`) makes the "random" sequence identical on every run. If the test ever fails, it fails the same way every time, so you can debug it. Try changing the seed to explore different sequences.

---

## Your turn

### Exercise 1: insufficient funds is a `422`

`POST /accounts/2/withdrawals` for more than the balance currently produces a `500`, because nothing handles `InsufficientFundsException`. It isn't a server error, though. The request is well-formed and understood, but it breaks a business rule. That's what **`422 Unprocessable Content`** is for.

1. Map `InsufficientFundsException` to a `422` `ProblemDetail` with the title `"Insufficient funds"`. (The constant is `HttpStatus.UNPROCESSABLE_CONTENT`.)
2. Map `JournalEntryNotFoundException` to a `404` with the title `"Journal entry not found"`.
3. Add a test to `CashApiTest`: deposit £10, try to withdraw £10.01, and check you get a `422` with the right title **and that the balance is still £10.00**.

<details>
<summary>Solution</summary>

In `ApiExceptionHandler`:

```java
@ExceptionHandler(JournalEntryNotFoundException.class)
ProblemDetail handle(JournalEntryNotFoundException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    problem.setTitle("Journal entry not found");
    return problem;
}

@ExceptionHandler(InsufficientFundsException.class)
ProblemDetail handle(InsufficientFundsException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
    problem.setTitle("Insufficient funds");
    return problem;
}
```

In `CashApiTest`:

```java
@Test
void cannotWithdrawMoreThanTheBalance() {
    post("/deposits", "10.00");

    MvcTestResult response = post("/withdrawals", "10.01");

    assertThat(response).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Insufficient funds");
    assertThat(balance()).isEqualTo("10.00");
}
```

</details>

> **In older codebases:** you'll see `HttpStatus.UNPROCESSABLE_ENTITY`. It's the same 422, under the name it had before RFC 9110 renamed it.

### Exercise 2: split entries

A journal entry isn't limited to two postings. A card payment with a fee might debit the customer £30 and credit the merchant £29.50 and the bank's fee income £0.50. Write a unit test in `JournalEntryTest` that builds an entry with **one debit of £30 and two credits of £20 and £10**, and checks it has three postings.

<details>
<summary>Solution</summary>

```java
@Test
void allowsOneDebitSplitAcrossSeveralCredits() {
    JournalEntry entry = JournalEntry.builder("Split")
            .debit(alice, Money.gbp("30.00"))
            .credit(bob, Money.gbp("20.00"))
            .credit(bob, Money.gbp("10.00"))
            .build();

    assertThat(entry.getPostings()).hasSize(3);
}
```

The rule is "total debits = total credits", not "one debit and one credit". Your builder already supports this without any changes.

</details>

### Exercise 3: break it on purpose

Prove the invariant test actually catches bugs. In `LedgerService.post`, temporarily comment out the `apply(...)` line, so postings are saved but cached balances never change. Run `LedgerInvariantsTest`. Which assertion fails, and what does the message tell you? Put the line back afterwards.

<details>
<summary>What you should see</summary>

Assertion 2 fails, with a message like:

```
[cached vs derived balance of account 1]
expected: GBP 1234.56
 but was: GBP 0.00
```

Debits still equal credits (the postings are fine), and no customer looks overdrawn (every balance is zero!). Only the cached-versus-derived check catches it. That's exactly the bug a nightly reconciliation job exists to find in production.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
```

New files since guide 01:

```
src/main/java/com/example/ledger/
├── cash/
│   ├── CashController.java
│   ├── CashService.java
│   └── InsufficientFundsException.java
└── journal/
    ├── Direction.java
    ├── JournalEntry.java
    ├── JournalEntryController.java
    ├── JournalEntryNotFoundException.java
    ├── JournalEntryRepository.java
    ├── JournalEntryResponse.java
    ├── LedgerInvariantException.java
    ├── LedgerService.java
    ├── Posting.java
    └── PostingRepository.java
src/main/resources/db/migration/
└── V2__create_journal.sql
src/test/java/com/example/ledger/
├── cash/CashApiTest.java
└── journal/
    ├── JournalEntryTest.java
    └── LedgerInvariantsTest.java
```

Modified: `Account.java` (`systemCode`, `apply`), `AccountType.java` (normal balance), `AccountRepository.java`, `AccountService.java`, `ApiExceptionHandler.java`, `requests.http`.

---

## Common errors

**`FlywayValidateException: Validate failed: Migrations have failed validation. Migration checksum mismatch for migration version 1`**
You edited `V1__create_account.sql` after it had run. Flyway stores a checksum of every applied migration and refuses to continue if one changes. Undo the edit and put the change in a new migration. In development only, deleting `data/` also works.

**`LazyInitializationException: Cannot lazily initialize collection of role 'com.example.ledger.journal.JournalEntry.postings' … no session`**
Something touched `entry.getPostings()` after the transaction had ended, usually while building a response in the controller. Either load the postings up front (`findWithPostingsById` with `@EntityGraph`), or do the mapping inside a `@Transactional` method.

**No error at all, but `LedgerInvariantsTest` says `[cached vs derived balance of account 1] expected: GBP 0.00 but was: GBP 3203.20`**
`cascade = CascadeType.PERSIST` is missing from `JournalEntry.postings`. Because `postings` is the *inverse* side of the relationship (`mappedBy`), Hibernate saves the entry and **silently ignores the postings**: no exception, and `CashApiTest` still passes, because the response is built from the objects in memory. The cached balances moved, but no postings exist to derive them from. This is precisely the kind of bug the invariant test is there to catch.

**`IllegalStateException: Missing system account CASH`**
The `INSERT` in `V2` didn't run, or the `system_code` was spelled differently. Check `SELECT * FROM account WHERE system_code = 'CASH'` in the H2 console.

**`LedgerInvariantException: Unbalanced entry: debits GBP 25.00, credits GBP 24.99`**
That's the builder doing its job. Look at the code that built the entry.

**`Check constraint violation: "CK_POSTING_AMOUNT_POSITIVE"`**
Something bypassed the builder and inserted a zero or negative amount. The database's `CHECK` constraint caught it. The builder should have stopped it first, so find out how it was bypassed.

**A deposit for `"10.001"` returns `500` instead of `400`**
The `@Digits` annotation is missing from `CashRequest`, so the bad value reached `Money`, which threw `IllegalArgumentException`.

---

## Recap

- **Double-entry**: every movement is recorded as postings whose debits equal their credits. Money only moves. It's never created or destroyed.
- **Debit and credit are sides, not signs.** Whether a side increases a balance depends on the account's **normal balance**: debit for assets, credit for liabilities.
- A ledger keeps a **cached balance** for speed and treats the **postings as the truth**. Update both in one transaction, and prove they match.
- **Put invariants in the domain.** A builder whose `build()` validates makes unbalanced entries impossible to create. Database `CHECK` constraints are the second line of defence.
- **`@EntityGraph`** loads associations in the same query, and mapping inside a transaction avoids `LazyInitializationException`.
- **Invariant tests** run lots of random operations and then check the rules that must always hold. A fixed seed keeps them reproducible.

### Interview angle

<details>
<summary>"Explain double-entry bookkeeping to me."</summary>

Every financial event is recorded as at least two postings, debits and credits of equal total, across different accounts. So money is always accounted for: it moves from somewhere to somewhere. For a bank, a customer deposit debits the bank's cash (an asset going up) and credits the customer's account (a liability going up, because the bank owes the customer more). The key property is that total debits always equal total credits, which gives you a built-in integrity check.

</details>

<details>
<summary>"Would you store the balance or calculate it?"</summary>

Both. The postings are the source of truth and the balance can always be derived from them, but deriving it for every read gets slow as history grows. So keep a cached balance column, update it in the same database transaction as the postings so they can't diverge, and run a reconciliation (in tests and as a scheduled job) that compares cached and derived balances and alerts on any difference. Some systems also store periodic balance snapshots, so a derivation only needs to add up the postings since the last snapshot.

</details>

<details>
<summary>"Where would you enforce a rule like 'debits must equal credits'?"</summary>

In the domain model, so it's impossible to construct an invalid object, for example a builder whose `build()` validates. Then add defence in depth: database constraints for what the database can check (positive amounts, valid directions, foreign keys), and tests that check the invariant across many random operations. Don't rely on every service remembering to check.

</details>

### Further reading

- [Martin Fowler: Accounting Patterns](https://martinfowler.com/eaaDev/AccountingNarrative.html): accounts, entries and postings as software patterns
- [Modern Treasury: Accounting for developers](https://www.moderntreasury.com/journal/accounting-for-developers-part-i): a clear developer-oriented series on ledgers
- [Spring Data JPA reference: entity graphs](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.entity-graph)

**Simplification to notice:** a real bank has many internal accounts (fee income, interest expense, suspense, payment scheme settlement), and its customer sub-ledger rolls up into a general ledger with a chart of accounts. The Reconciliation tutorial picks some of this up.

**Next:** [03: Transfers and concurrency](03-transfers-and-concurrency.md). Customers start paying each other, you'll find the race condition hiding in `withdraw`, and fix it three different ways.
