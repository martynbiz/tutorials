# 04: Clearing and the Ledger

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- What **clearing** (presentment) is, and why it's the moment money really leaves the customer's account
- Why the **cleared amount can differ** from the authorised amount: tips, fuel, hotels
- Why clearing **never declines**, even when it takes an account past its overdraft limit
- Enough **double-entry bookkeeping** to post a card payment properly: debit the customer, credit a **scheme settlement** account
- What **settlement** with the card scheme looks like in the bank's books
- How to make clearing safe against **duplicate presentments**, with a status check under a lock and a unique constraint as a backstop
- How to keep a lock order across several accounts so that it can never deadlock

By the end, holds turn into real postings, the books balance, and a presentment delivered five times at once posts exactly once.

---

## Concepts

### How it works in a real bank

An authorisation is a promise. **Clearing** is when the promise is kept. Some time after the sale (often the same day, sometimes days later), the merchant's acquirer sends the transaction's final details through the card scheme to the issuer. This record is called the **presentment** (or *first presentment*): "the £45 authorisation for card … at Shell, on this date, is final, for £38.20".

The issuer then:

1. **matches** the presentment to the original authorisation,
2. **releases the hold**, and
3. **posts** the final amount to the customer's account. Now the [ledger balance](../glossary.md#ledger-balance) changes, and the pending transaction becomes a completed one in the customer's app.

Presentments don't arrive one by one in real life. The schemes deliver **clearing files** in batches several times a day (Visa's clearing system is called BASE II, and Mastercard sends IPM-format files from its Global Clearing Management System). This tutorial receives one presentment per API call, matched by authorisation id.

> **Simplification:** matching a presentment to its authorisation is genuinely hard. Real presentments don't carry the issuer's database id. They're matched on data from the original exchange (an authorisation code, a scheme transaction identifier, the card, the amount and the date), and some arrive with no matching authorisation at all (offline transactions, or authorisations the issuer never saw because the scheme stood in). We use the authorisation id so the matching problem doesn't hide the rest.

### Why the cleared amount can differ

The authorised amount is often an **estimate**:

| Situation | Authorised | Cleared | Why |
|---|---|---|---|
| Pay at the pump | a pre-authorisation amount set by the fuel station | what you actually pumped | The pump doesn't know how much fuel you'll take |
| Restaurant with a tip | the bill | the bill plus the tip | The tip is added after the card was authorised (common in the US, less so in the UK) |
| Hotel | an estimate at check-in | the final bill | Extra nights, minibar, or a shorter stay |

So the issuer **posts the cleared amount**, not the authorised one, and releases the **whole** hold. In the fuel example, Alice's available balance goes *up* at clearing, because £45 was held and only £38.20 was posted.

> **Simplification:** the schemes have detailed rules about how far a cleared amount may exceed the authorised one for each kind of merchant, and what the issuer can do (raise a *chargeback*, a formal dispute through the scheme) when a merchant goes beyond them. This tutorial posts whatever is presented.

### Clearing never declines

By the time a presentment arrives, the issuer has already said yes. Under the scheme rules, an approved authorisation is a guarantee of payment to the merchant, so the issuer can't refuse to pay just because the customer has spent the money elsewhere in the meantime. If a tip takes Alice past her overdraft limit, the posting goes through anyway and her account goes into an unarranged [overdraft](../glossary.md#overdraft). That's the bank's credit risk, and it's exactly why authorisation decisions (and holds) matter.

### Double-entry, briefly

Until now, `deposit` changed `ledger_balance` directly. That's fine for a demo and unacceptable in a bank: there's no record of *where* the money came from or went to. Banks use **[double-entry bookkeeping](../glossary.md#double-entry)**: every movement is a [journal entry](../glossary.md#journal-entry) of two or more [postings](../glossary.md#posting) whose [debits](../glossary.md#debit) and [credits](../glossary.md#credit) are equal.

You'll build a **small, cut-down ledger**: just enough for this system. The Core ledger tutorial covers ledgers properly: builders that enforce the rules, statements, reversals and the full reasoning behind every choice.

The two things you need here:

1. **A customer's account is a [liability](../glossary.md#liability) of the bank**: it's money the bank owes the customer. A **credit** increases it and a **debit** decreases it.
2. **The bank has its own [internal accounts](../glossary.md#internal-account)** for the other side of each movement.

You'll add two internal accounts:

| Account | Type | What it means |
|---|---|---|
| `CASH` | [Asset](../glossary.md#asset) | The bank's own money. (Simplified: in reality, a bank's money sits mostly in its reserve account at the Bank of England, and payments in arrive through scheme settlement accounts for Faster Payments, Bacs and so on.) |
| `SCHEME_SETTLEMENT` | Liability | What the bank owes the card scheme for cleared transactions that haven't been settled yet |

And three kinds of movement:

| Event | Debit | Credit |
|---|---|---|
| Money arrives for Alice (a deposit) | `CASH` +£250 | Alice +£250 |
| A card payment clears for £38.20 | Alice −£38.20 | `SCHEME_SETTLEMENT` +£38.20 |
| The bank settles with the scheme (Exercise 1) | `SCHEME_SETTLEMENT` −£38.20 | `CASH` −£38.20 |

Read the middle row carefully. When Alice's payment clears, nothing has gone to the merchant yet. The bank now owes the money to the **scheme** instead of to Alice. Later, at **[settlement](../glossary.md#settlement)**, the bank pays the scheme (in bulk, for all of its customers' payments) and that liability goes back down.

### Settlement

[Clearing](../glossary.md#clearing) works out who owes whom. Settlement moves the money. For card payments, the scheme nets everything up (all the issuers' totals and all the acquirers' totals, after fees) and each bank makes or receives one payment per settlement cycle, rather than one per transaction. In the UK, the money moves between the banks' accounts at the Bank of England or through a settlement bank, depending on the scheme's arrangements.

You'll model the issuer's side of that as one journal entry: debit `SCHEME_SETTLEMENT`, credit `CASH`.

> **Simplification:** interchange fees (paid by the acquirer to the issuer on each transaction) and scheme fees are left out entirely. With them, the amount the issuer pays the scheme is less than the total of its customers' cleared payments.

### Duplicate presentments

A presentment can arrive twice: a file resent after an error, a message retried after a timeout. Posting it twice charges the customer twice. You'll defend against that the same way twice over:

- **Under the account lock, check the status.** A second presentment for the same authorisation waits on the lock, then finds it already `CLEARED` and is refused.
- **A unique constraint** on the journal entry's `authorisation_id`. If anything ever slips past the first defence, the database refuses the second posting.

---

## Steps

> **Start with a fresh database.** The deposits you made in guides 01 to 03 changed `ledger_balance` directly, without any postings. The new ledger can't explain them, so the bank's `CASH` account will look wrong later on. That's actually a nice demonstration of why every movement must go through the ledger, but for clean numbers, stop the app and delete the `data/` folder now. (The examples in step 9 were captured *without* deleting it, and show what happens.)

### 1. The migration

Create `src/main/resources/db/migration/V3__create_ledger.sql`:

```sql
-- Customer accounts are liabilities of the bank. Its own accounts can be either.
ALTER TABLE account ADD COLUMN type VARCHAR(10) DEFAULT 'LIABILITY' NOT NULL;
ALTER TABLE account ADD CONSTRAINT ck_account_type CHECK (type IN ('ASSET', 'LIABILITY'));

-- Internal (bank-owned) accounts are identified by a code
ALTER TABLE account ADD COLUMN system_code VARCHAR(30);
ALTER TABLE account ADD CONSTRAINT uk_account_system_code UNIQUE (system_code);

INSERT INTO account (name, currency, ledger_balance, overdraft_limit, opened_at, type, system_code)
VALUES ('Bank cash', 'GBP', 0, 0, CURRENT_TIMESTAMP, 'ASSET', 'CASH');

INSERT INTO account (name, currency, ledger_balance, overdraft_limit, opened_at, type, system_code)
VALUES ('Card scheme settlement', 'GBP', 0, 0, CURRENT_TIMESTAMP, 'LIABILITY', 'SCHEME_SETTLEMENT');

CREATE TABLE journal_entry (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    description      VARCHAR(200)             NOT NULL,
    authorisation_id BIGINT REFERENCES authorisation (id),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    -- One presentment, one posting: an authorisation can be cleared at most once
    CONSTRAINT uk_journal_entry_authorisation UNIQUE (authorisation_id)
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

-- What the merchant finally presented, which can differ from what was authorised
ALTER TABLE authorisation ADD COLUMN cleared_amount NUMERIC(19, 4);
ALTER TABLE authorisation ADD COLUMN cleared_at TIMESTAMP WITH TIME ZONE;
```

- **The internal accounts are seeded by the migration**, so every environment, including the in-memory test database, has them. On a fresh database they get ids 1 and 2, so your first customer will be id 3.
- **`uk_journal_entry_authorisation`** is the duplicate-presentment backstop. Deposits have no authorisation and leave it `NULL`, and a unique constraint allows any number of `NULL`s (in H2 and PostgreSQL alike).
- **Posting amounts are always positive.** The direction says which way the money goes.

### 2. Teach the account about postings

Create `ledger/Direction.java`:

```java
package com.example.cards.ledger;

public enum Direction {
    DEBIT,
    CREDIT
}
```

and `account/AccountType.java`:

```java
package com.example.cards.account;

import com.example.cards.ledger.Direction;

/**
 * Which side of the bank's balance sheet an account sits on, and so which
 * direction makes its balance go up (its "normal balance").
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

The [normal balance](../glossary.md#normal-balance) is the direction that makes an account grow: debit for assets, credit for liabilities. Keeping it on the enum means no `if (type == ASSET …)` anywhere else.

In `account/Account.java`, map the two new columns after `openedAt` (import `EnumType`, `Enumerated` and `com.example.cards.ledger.Direction`):

```java
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AccountType type;

    @Column(name = "system_code", length = 30, unique = true)
    private String systemCode;
```

At the end of the private constructor, every account opened through the API is a customer account:

```java
        this.type = AccountType.LIABILITY;
```

Then **replace** `deposit` with the one method that's allowed to change the ledger balance:

```java
    /**
     * Updates the cached ledger balance for one posting. Only the ledger calls
     * this, in the same transaction that saves the posting.
     */
    public void apply(Direction direction, Money amount) {
        Money current = getLedgerBalance();
        Money updated = direction == type.normalBalance() ? current.plus(amount) : current.minus(amount);
        this.ledgerBalance = updated.amount();
    }
```

and add getters for `type` and `systemCode`.

`ledger_balance` is now a [cached balance](../glossary.md#cached-balance): the postings are the truth, and the column is kept in step with them in the same transaction.

`AccountTest` used `account.deposit(...)`. Change those two lines to `account.apply(Direction.CREDIT, Money.gbp("250.00"))` and `account.apply(Direction.CREDIT, Money.gbp("50.00"))`: crediting a liability is exactly what a deposit does.

### 3. Postings and journal entries

Create `ledger/Posting.java`:

```java
package com.example.cards.ledger;

import com.example.cards.account.Account;
import com.example.cards.money.Money;
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

The constructor is package-private, so only `JournalEntry` can create postings. Then `ledger/JournalEntry.java`:

```java
package com.example.cards.ledger;

import com.example.cards.account.Account;
import com.example.cards.money.Money;
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
import java.util.List;

/**
 * A cut-down journal entry: exactly one debit and one credit of the same amount,
 * so it balances by construction. (The Core ledger tutorial builds the full version.)
 */
@Entity
@Table(name = "journal_entry")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String description;

    /** The card authorisation this entry clears, if any. Just an id: the ledger knows nothing about cards. */
    @Column(name = "authorisation_id")
    private Long authorisationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.PERSIST)
    private List<Posting> postings = new ArrayList<>();

    protected JournalEntry() {
        // for JPA
    }

    private JournalEntry(String description, Long authorisationId, Instant createdAt) {
        this.description = description;
        this.authorisationId = authorisationId;
        this.createdAt = createdAt;
    }

    /** Debit one account and credit another by the same amount. */
    public static JournalEntry of(String description, Account debit, Account credit, Money amount,
                                  Long authorisationId, Instant createdAt) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Posting amounts must be positive, got " + amount);
        }
        if (debit == credit) {
            throw new IllegalArgumentException("Cannot debit and credit the same account");
        }
        JournalEntry entry = new JournalEntry(description, authorisationId, createdAt);
        entry.postings.add(new Posting(entry, debit, Direction.DEBIT, amount));
        entry.postings.add(new Posting(entry, credit, Direction.CREDIT, amount));
        return entry;
    }

    public Long getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public Long getAuthorisationId() {
        return authorisationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<Posting> getPostings() {
        return Collections.unmodifiableList(postings);
    }
}
```

- **It balances by construction.** The only factory creates one debit and one credit of the same amount, so an unbalanced entry can't exist. Every movement in this system has exactly two sides, so that's all you need. A real ledger allows more (a payment with a fee might be one debit and two credits), and then a builder has to check the totals.
- **`authorisationId` is a plain `Long`**, not a `@ManyToOne`. The ledger is a general-purpose component, and it shouldn't depend on the card domain. It just records which authorisation an entry belongs to, and the database's foreign key and unique constraint do the rest.
- **`cascade = CascadeType.PERSIST`** saves the postings along with the entry.

### 4. The ledger service

`ledger/JournalEntryRepository.java`:

```java
package com.example.cards.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {
}
```

`ledger/PostingRepository.java`:

```java
package com.example.cards.ledger;

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
}
```

`ledger/LedgerService.java`:

```java
package com.example.cards.ledger;

import com.example.cards.account.Account;
import com.example.cards.money.Money;
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
     * Saves the entry and updates both accounts' cached ledger balances in one
     * transaction. The caller must already hold the locks on those accounts.
     */
    @Transactional
    public JournalEntry post(JournalEntry entry) {
        for (Posting posting : entry.getPostings()) {
            posting.getAccount().apply(posting.getDirection(), posting.getAmount());
        }
        return entries.save(entry);
    }

    /** The ledger balance worked out from scratch from the postings: the source of truth. */
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

`post` is the **only** caller of `Account.apply`. The postings and the cached balances commit together or not at all. `derivedBalance` rebuilds a balance from the postings: the [derived balance](../glossary.md#derived-balance), which tests use to prove the cached one is telling the truth.

**"The caller must already hold the locks"** is the important comment. `post` changes two balances. Guide 03's rule says anything that changes an account's money must hold that account's lock.

### 5. Deposits through the ledger

Add a locking lookup for internal accounts to `AccountRepository`:

```java
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.systemCode = :systemCode")
    Optional<Account> findBySystemCodeForUpdate(String systemCode);
```

In `AccountService`, inject `LedgerService` (constructor parameter and field), replace `deposit`, and add a helper:

```java
    /** Money arriving in the account, recorded as a proper double-entry posting. */
    @Transactional
    public AccountBalances deposit(Long id, Money amount) {
        // Lock order: the customer's account first, then internal accounts
        Account customer = accounts.findByIdForUpdate(id).orElseThrow(() -> new AccountNotFoundException(id));
        Account cash = getSystemAccountForUpdate("CASH");
        ledger.post(JournalEntry.of("Deposit", cash, customer, amount, null, Instant.now(clock)));
        return balances(id);
    }

    @Transactional
    public Account getSystemAccountForUpdate(String systemCode) {
        return accounts.findBySystemCodeForUpdate(systemCode)
                .orElseThrow(() -> new IllegalStateException("Missing system account " + systemCode));
    }
```

(Import `com.example.cards.ledger.JournalEntry` and `com.example.cards.ledger.LedgerService`.)

**Lock ordering.** A deposit now locks two rows. Any code that locks more than one row must take the locks in a consistent order, or two transactions can each hold one lock and wait forever for the other: a **deadlock**. The rule for this system:

> **The customer's account first, then internal accounts, always in the order `SCHEME_SETTLEMENT`, then `CASH`.**

Deposits follow it (customer, `CASH`), clearing will follow it (customer, `SCHEME_SETTLEMENT`), and so will settlement in Exercise 1 (`SCHEME_SETTLEMENT`, `CASH`). No path ever holds an internal account while waiting for a customer's, so no cycle can form.

A missing internal account is an `IllegalStateException`, not a "not found": it means the deployment is broken, not that the client asked for something odd, and a `500` is the honest answer.

> **Sidebar: the hot account.** Every deposit in the bank now queues on the one `CASH` row, and every clearing queues on the one `SCHEME_SETTLEMENT` row. These are textbook [hot accounts](../glossary.md#hot-account). That's fine for a tutorial and would be the first bottleneck at scale. Real ledgers split such accounts into several sub-accounts, post to them in batches, or don't keep a cached balance for them at all. The Core ledger tutorial discusses the options.

Run the tests now. `AccountApiTest.depositRaisesLedgerAndAvailableBalance` still passes unchanged: the API behaves the same, but every deposit now leaves a balanced journal entry behind.

### 6. Clearing an authorisation

Add to `Authorisation`, two columns after `expiresAt`:

```java
    @Column(name = "cleared_amount", precision = 19, scale = 4)
    private BigDecimal clearedAmount;

    @Column(name = "cleared_at")
    private Instant clearedAt;
```

the rule:

```java
    /**
     * The merchant's presentment has arrived: the hold is replaced by a real posting.
     * The cleared amount may differ from the authorised one (a tip, a fuel pre-authorisation).
     * An EXPIRED hold can still be cleared: that's a late presentment.
     */
    public void clear(Money amount, Instant now) {
        if (status != AuthorisationStatus.PENDING && status != AuthorisationStatus.EXPIRED) {
            throw new AuthorisationStateException(id, status, "cleared");
        }
        this.status = AuthorisationStatus.CLEARED;
        this.heldAmount = BigDecimal.ZERO;
        this.clearedAmount = amount.amount();
        this.clearedAt = now;
    }
```

and getters:

```java
    /** Null until the authorisation is cleared. */
    public Money getClearedAmount() {
        return clearedAmount == null ? null : new Money(clearedAmount, Currency.getInstance(currencyCode));
    }

    public Instant getClearedAt() {
        return clearedAt;
    }
```

Which statuses can be cleared?

- **`PENDING`**: the normal case. A *partially* reversed authorisation is still `PENDING`, so it can clear too.
- **`EXPIRED`**: a **late presentment**. The hold ran out (guide 05), but the merchant was entitled to be paid, and the presentment still has to be posted. The customer sees the pending amount disappear, then reappear as a completed payment.
- **`CLEARED`, `REVERSED`, `DECLINED`**: refused. Clearing twice would charge the customer twice.

> **Simplification:** a real issuer can't simply refuse a presentment for a reversed or declined authorisation. The scheme has already moved it through clearing. The issuer posts it or handles it as an exception, and may raise a chargeback. Refusing it keeps this tutorial's rules simple.

Unit-test the rules. `src/test/java/com/example/cards/authorisation/AuthorisationTest.java`:

```java
package com.example.cards.authorisation;

import com.example.cards.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorisationTest {

    private final Instant now = Instant.parse("2026-11-06T12:00:00Z");

    private Authorisation hold(String amount) {
        // Card and account aren't needed for these rules, so null is fine in a unit test
        return Authorisation.approved(null, null, Money.gbp(amount), "Shell", "5542",
                now, now.plusSeconds(7 * 24 * 3600));
    }

    @Test
    void clearingReleasesTheHoldAndRecordsTheFinalAmount() {
        Authorisation fuel = hold("100.00");

        fuel.clear(Money.gbp("43.21"), now);

        assertThat(fuel.getStatus()).isEqualTo(AuthorisationStatus.CLEARED);
        assertThat(fuel.getHeldAmount()).isEqualTo(Money.gbp("0.00"));
        assertThat(fuel.getAmount()).isEqualTo(Money.gbp("100.00"));
        assertThat(fuel.getClearedAmount()).isEqualTo(Money.gbp("43.21"));
    }

    @Test
    void cannotClearTwice() {
        Authorisation authorisation = hold("10.00");
        authorisation.clear(Money.gbp("10.00"), now);

        assertThatThrownBy(() -> authorisation.clear(Money.gbp("10.00"), now))
                .isInstanceOf(AuthorisationStateException.class)
                .hasMessageContaining("is CLEARED");
    }

    @Test
    void cannotClearAFullyReversedAuthorisation() {
        Authorisation authorisation = hold("10.00");
        authorisation.reverse(Money.gbp("10.00"));

        assertThatThrownBy(() -> authorisation.clear(Money.gbp("10.00"), now))
                .isInstanceOf(AuthorisationStateException.class);
    }

    @Test
    void canClearAPartiallyReversedAuthorisation() {
        Authorisation hotel = hold("200.00");
        hotel.reverse(Money.gbp("50.00"));

        hotel.clear(Money.gbp("150.00"), now);

        assertThat(hotel.getStatus()).isEqualTo(AuthorisationStatus.CLEARED);
    }
}
```

### 7. The clearing service

Create `clearing/ClearingResult.java`:

```java
package com.example.cards.clearing;

import com.example.cards.authorisation.Authorisation;
import com.example.cards.ledger.JournalEntry;

public record ClearingResult(Authorisation authorisation, JournalEntry entry) {
}
```

and `clearing/ClearingService.java`:

```java
package com.example.cards.clearing;

import com.example.cards.account.Account;
import com.example.cards.account.AccountRepository;
import com.example.cards.account.AccountService;
import com.example.cards.authorisation.Authorisation;
import com.example.cards.authorisation.AuthorisationNotFoundException;
import com.example.cards.authorisation.AuthorisationRepository;
import com.example.cards.ledger.JournalEntry;
import com.example.cards.ledger.LedgerService;
import com.example.cards.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** Turns a hold into money actually leaving the customer's account. */
@Service
public class ClearingService {

    private final AuthorisationRepository authorisations;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final LedgerService ledger;
    private final Clock clock;

    public ClearingService(AuthorisationRepository authorisations, AccountRepository accounts,
                           AccountService accountService, LedgerService ledger, Clock clock) {
        this.authorisations = authorisations;
        this.accounts = accounts;
        this.accountService = accountService;
        this.ledger = ledger;
        this.clock = clock;
    }

    @Transactional
    public ClearingResult clear(Long authorisationId, Money amount) {
        // 1. Lock the customer's account, then the scheme settlement account (always this order)
        Long accountId = authorisations.findAccountIdById(authorisationId)
                .orElseThrow(() -> new AuthorisationNotFoundException(authorisationId));
        Account customer = accounts.findByIdForUpdate(accountId).orElseThrow();
        Account schemeSettlement = accountService.getSystemAccountForUpdate("SCHEME_SETTLEMENT");

        // 2. Load the authorisation after the locks, and release its hold
        Authorisation authorisation = authorisations.findById(authorisationId).orElseThrow();
        Instant now = Instant.now(clock);
        authorisation.clear(amount, now);

        // 3. Post what the merchant presented, which may not be what was authorised
        JournalEntry entry = JournalEntry.of("Card payment: " + authorisation.getMerchantName(),
                customer, schemeSettlement, amount, authorisationId, now);
        return new ClearingResult(authorisation, ledger.post(entry));
    }
}
```

It's the guide 03 pattern again, now with two locks:

1. **Read the id, lock, then load.** `findAccountIdById` (from guide 03) gets the account id without loading the authorisation. Both locks are taken in the agreed order. Only then is the authorisation loaded, so its status is the latest committed one. A duplicate presentment waits at the first lock, then finds `CLEARED` and throws.
2. **Release the hold and post in one transaction.** The status change and the journal entry commit together. There's never a moment when the hold is gone but the posting isn't there (the customer would briefly see too much available), or the other way round (they'd briefly see it taken twice).
3. **No balance check.** Clearing never declines.

### 8. The presentment endpoint

Create `clearing/PresentmentController.java`:

```java
package com.example.cards.clearing;

import com.example.cards.authorisation.Authorisation;
import com.example.cards.ledger.Direction;
import com.example.cards.ledger.JournalEntry;
import com.example.cards.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/** Where the card scheme's clearing records arrive, one presentment at a time. */
@RestController
public class PresentmentController {

    private final ClearingService clearingService;

    public PresentmentController(ClearingService clearingService) {
        this.clearingService = clearingService;
    }

    @PostMapping("/presentments")
    public PresentmentResponse present(@Valid @RequestBody PresentmentRequest request) {
        ClearingResult result = clearingService.clear(request.authorisationId(),
                Money.gbp(request.amount().toPlainString()));
        return PresentmentResponse.from(result);
    }

    public record PresentmentRequest(
            @NotNull Long authorisationId,
            @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount) {
    }

    public record PresentmentResponse(Long authorisationId, Money authorisedAmount, Money clearedAmount,
                                      Long journalEntryId, List<PostingResponse> postings) {

        public record PostingResponse(Long accountId, Direction direction, Money amount) {
        }

        static PresentmentResponse from(ClearingResult result) {
            Authorisation authorisation = result.authorisation();
            JournalEntry entry = result.entry();
            List<PostingResponse> postings = entry.getPostings().stream()
                    .map(p -> new PostingResponse(p.getAccount().getId(), p.getDirection(), p.getAmount()))
                    .toList();
            return new PresentmentResponse(authorisation.getId(), authorisation.getAmount(),
                    authorisation.getClearedAmount(), entry.getId(), postings);
        }
    }
}
```

Mapping happens in the controller, after the transaction, and that's safe here: the `postings` list was built in memory by `JournalEntry.of`, and `p.getAccount()` is an account that was loaded inside the transaction, not a lazy proxy.

Finally, add `clearedAmount` to `AuthorisationController.AuthorisationResponse` (a `Money clearedAmount` component at the end, filled from `a.getClearedAmount()`) so `GET /authorisations/{id}` shows it.

A duplicate presentment throws `AuthorisationStateException`, which guide 03 already maps to `409 Conflict`.

### 9. Presentment tests

Create `src/test/java/com/example/cards/clearing/PresentmentApiTest.java`:

```java
package com.example.cards.clearing;

import com.example.cards.CardsIntegrationTest;
import com.example.cards.account.Account;
import com.example.cards.account.AccountBalances;
import com.example.cards.account.AccountService;
import com.example.cards.authorisation.AuthorisationService;
import com.example.cards.card.CardService;
import com.example.cards.ledger.LedgerService;
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
class PresentmentApiTest {

    @Autowired MockMvcTester mvc;
    @Autowired AccountService accountService;
    @Autowired CardService cardService;
    @Autowired AuthorisationService authorisationService;
    @Autowired LedgerService ledger;

    Long accountId;
    String token;

    @BeforeEach
    void openAccountWithCard() {
        accountId = accountService.open("Alice", Money.gbp("0.00")).getId();
        accountService.deposit(accountId, Money.gbp("100.00"));
        token = cardService.issue(accountId).getToken();
    }

    @Test
    void fuelPreAuthorisationClearsForWhatWasActuallyPumped() {
        Long authorisationId = authorise("90.00", "Shell Mile End", "5542");
        assertThat(accountService.balances(accountId).available()).isEqualTo(Money.gbp("10.00"));

        MvcTestResult response = present(authorisationId, "43.21");

        assertThat(response).hasStatus(HttpStatus.OK);
        assertThat(response).bodyJson().extractingPath("$.authorisedAmount.amount").isEqualTo("90.00");
        assertThat(response).bodyJson().extractingPath("$.clearedAmount.amount").isEqualTo("43.21");
        assertThat(response).bodyJson().extractingPath("$.postings[0].accountId").isEqualTo(accountId.intValue());
        assertThat(response).bodyJson().extractingPath("$.postings[0].direction").isEqualTo("DEBIT");
        assertThat(response).bodyJson().extractingPath("$.postings[1].direction").isEqualTo("CREDIT");

        AccountBalances balances = accountService.balances(accountId);
        assertThat(balances.account().getLedgerBalance()).isEqualTo(Money.gbp("56.79"));
        assertThat(balances.held()).isEqualTo(Money.gbp("0.00"));
        assertThat(balances.available()).isEqualTo(Money.gbp("56.79"));
    }

    @Test
    void aTipCanTakeTheClearedAmountAboveTheAuthorisedAmount() {
        Long authorisationId = authorise("95.00", "Bella Italia", "5812");

        present(authorisationId, "105.00");

        // Clearing never declines: the bank already promised to pay when it approved
        Account account = accountService.get(accountId);
        assertThat(account.getLedgerBalance()).isEqualTo(Money.gbp("-5.00"));
        assertThat(ledger.derivedBalance(account)).isEqualTo(Money.gbp("-5.00"));
    }

    @Test
    void theSameAuthorisationCannotBeClearedTwice() {
        Long authorisationId = authorise("20.00", "Corner Cafe", "5814");
        present(authorisationId, "20.00");

        MvcTestResult response = present(authorisationId, "20.00");

        assertThat(response).hasStatus(HttpStatus.CONFLICT);
        assertThat(accountService.get(accountId).getLedgerBalance()).isEqualTo(Money.gbp("80.00"));
    }

    @Test
    void returns404ForAnUnknownAuthorisation() {
        assertThat(present(999_999L, "1.00")).hasStatus(HttpStatus.NOT_FOUND);
    }

    private Long authorise(String amount, String merchant, String mcc) {
        return authorisationService.authorise(token, Money.gbp(amount), merchant, mcc).authorisationId();
    }

    private MvcTestResult present(Long authorisationId, String amount) {
        return mvc.post().uri("/presentments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"authorisationId": %d, "amount": "%s"}
                        """.formatted(authorisationId, amount))
                .exchange();
    }
}
```

The fuel test tells the whole story in numbers. £100 in the account, £90 held at the pump (£10 available), then £43.21 cleared: the ledger balance is now £56.79, nothing is held, and the available balance went **up** from £10 to £56.79. The tip test shows an account taken to −£5.00 with no overdraft, and the derived balance agrees with the cached one.

### 10. Try it

Restart the app. Flyway applies `V3`:

```
Migrating schema "PUBLIC" to version "3 - create ledger"
```

Add to `requests.http`:

```http
### The fuel station presents £38.20 for the £45 authorisation
POST http://localhost:8080/presentments
Content-Type: application/json

{"authorisationId": 1, "amount": "38.20"}

### The same presentment again (409)
POST http://localhost:8080/presentments
Content-Type: application/json

{"authorisationId": 1, "amount": "38.20"}

### The cleared authorisation
GET http://localhost:8080/authorisations/1
```

Here's what that looked like on a database carried over from the earlier guides, where Alice is account 1 and the new internal accounts became 2 (`CASH`) and 3 (`SCHEME_SETTLEMENT`). On a fresh database the ids differ.

```json
{
  "authorisationId": 1,
  "authorisedAmount": { "amount": "45.00", "currency": "GBP" },
  "clearedAmount": { "amount": "38.20", "currency": "GBP" },
  "journalEntryId": 1,
  "postings": [
    { "accountId": 1, "direction": "DEBIT",  "amount": { "amount": "38.20", "currency": "GBP" } },
    { "accountId": 3, "direction": "CREDIT", "amount": { "amount": "38.20", "currency": "GBP" } }
  ]
}
```

Alice's account afterwards: the ledger balance is down by £38.20, and the £45 hold has gone.

```json
{
  "id": 1,
  "name": "Alice",
  "ledgerBalance": { "amount": "211.80", "currency": "GBP" },
  "held": { "amount": "0.00", "currency": "GBP" },
  "overdraftLimit": { "amount": "100.00", "currency": "GBP" },
  "availableBalance": { "amount": "311.80", "currency": "GBP" }
}
```

Sending it again:

```json
{"detail":"Authorisation 1 is CLEARED, so it cannot be cleared","instance":"/presentments","status":409,"title":"Authorisation not pending"}
```

And `GET /authorisations/1` now ends with `"status": "CLEARED"` and `"clearedAmount": {"amount": "38.20", "currency": "GBP"}`, alongside the original `"amount"` of £45.00.

---

## Your turn

### Exercise 1: settle with the scheme

The `SCHEME_SETTLEMENT` account now shows how much the bank owes the card scheme. Add `POST /settlements` with a body `{"amount": "38.20"}` that records the bank paying the scheme: **debit `SCHEME_SETTLEMENT`, credit `CASH`**. Respond with the journal entry's id and description.

Write a test that checks both accounts went down by the settled amount. Internal accounts are shared by every test in the suite, so compare **before and after**, not absolute values. You'll want a read-only `getSystemAccount(String)` on `AccountService` for the test.

<details>
<summary>Solution</summary>

`AccountRepository`:

```java
    Optional<Account> findBySystemCode(String systemCode);
```

`AccountService`:

```java
    @Transactional(readOnly = true)
    public Account getSystemAccount(String systemCode) {
        return accounts.findBySystemCode(systemCode)
                .orElseThrow(() -> new IllegalStateException("Missing system account " + systemCode));
    }
```

`clearing/SettlementService.java`:

```java
package com.example.cards.clearing;

import com.example.cards.account.Account;
import com.example.cards.account.AccountService;
import com.example.cards.ledger.JournalEntry;
import com.example.cards.ledger.LedgerService;
import com.example.cards.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** The bank pays the card scheme what it owes for cleared transactions. */
@Service
public class SettlementService {

    private final AccountService accountService;
    private final LedgerService ledger;
    private final Clock clock;

    public SettlementService(AccountService accountService, LedgerService ledger, Clock clock) {
        this.accountService = accountService;
        this.ledger = ledger;
        this.clock = clock;
    }

    @Transactional
    public JournalEntry settle(Money amount) {
        // Internal accounts are always locked in this order: SCHEME_SETTLEMENT, then CASH
        Account schemeSettlement = accountService.getSystemAccountForUpdate("SCHEME_SETTLEMENT");
        Account cash = accountService.getSystemAccountForUpdate("CASH");
        return ledger.post(JournalEntry.of("Settlement with card scheme",
                schemeSettlement, cash, amount, null, Instant.now(clock)));
    }
}
```

`clearing/SettlementController.java`:

```java
package com.example.cards.clearing;

import com.example.cards.ledger.JournalEntry;
import com.example.cards.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @PostMapping("/settlements")
    public SettlementResponse settle(@Valid @RequestBody SettlementRequest request) {
        JournalEntry entry = settlementService.settle(Money.gbp(request.amount().toPlainString()));
        return new SettlementResponse(entry.getId(), entry.getDescription());
    }

    public record SettlementRequest(@NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount) {
    }

    public record SettlementResponse(Long journalEntryId, String description) {
    }
}
```

`src/test/java/com/example/cards/clearing/SettlementApiTest.java`:

```java
package com.example.cards.clearing;

import com.example.cards.CardsIntegrationTest;
import com.example.cards.account.AccountService;
import com.example.cards.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

@CardsIntegrationTest
class SettlementApiTest {

    @Autowired MockMvcTester mvc;
    @Autowired AccountService accountService;

    @Test
    void settlementReducesWhatTheBankOwesTheSchemeAndItsCash() {
        Money owedBefore = schemeSettlement();
        Money cashBefore = cash();

        MvcTestResult response = mvc.post().uri("/settlements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount": "25.00"}
                        """)
                .exchange();

        assertThat(response).hasStatus(HttpStatus.OK);
        assertThat(schemeSettlement())
                .isEqualTo(owedBefore.minus(Money.gbp("25.00")));
        assertThat(cash())
                .isEqualTo(cashBefore.minus(Money.gbp("25.00")));
    }

    private Money schemeSettlement() {
        return accountService.getSystemAccount("SCHEME_SETTLEMENT").getLedgerBalance();
    }

    private Money cash() {
        return accountService.getSystemAccount("CASH").getLedgerBalance();
    }
}
```

Both balances go **down**: `SCHEME_SETTLEMENT` is a liability, so a debit reduces what the bank owes, and `CASH` is an asset, so a credit reduces what it has.

Try it on the database carried over from earlier guides. After settling £38.20, `SCHEME_SETTLEMENT` is at £0.00, but `CASH` is at **−£38.20**:

```json
{"id":2,"name":"Bank cash","ledgerBalance":{"amount":"-38.20","currency":"GBP"}, …}
```

The bank appears to have negative cash, because Alice's £250 from guide 01 never went through the ledger: `CASH` never received it. Every movement has to be recorded as postings, or the books stop making sense. With a fresh `data/` folder this doesn't happen.

> **Simplification:** a real settlement amount comes from the scheme's settlement report, net of interchange and fees, and the bank reconciles it against its own records (the Reconciliation tutorial covers that). Here you choose the amount.

</details>

### Exercise 2: prove the books balance

Write `LedgerInvariantsTest`: whatever the other tests have done to the shared test database, **total debits must equal total credits**, and **every account's cached ledger balance must equal its derived balance**. You'll need a `sumByDirection(Direction)` query on `PostingRepository`.

<details>
<summary>Solution</summary>

`PostingRepository`:

```java
    @Query("select coalesce(sum(p.amount), 0) from Posting p where p.direction = :direction")
    BigDecimal sumByDirection(Direction direction);
```

`src/test/java/com/example/cards/ledger/LedgerInvariantsTest.java`:

```java
package com.example.cards.ledger;

import com.example.cards.CardsIntegrationTest;
import com.example.cards.account.Account;
import com.example.cards.account.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Whatever the other tests did to the shared database, the books must still balance. */
@CardsIntegrationTest
class LedgerInvariantsTest {

    @Autowired AccountRepository accounts;
    @Autowired PostingRepository postings;
    @Autowired LedgerService ledger;

    @Test
    void debitsEqualCreditsAndEveryCachedBalanceMatchesItsPostings() {
        BigDecimal debits = postings.sumByDirection(Direction.DEBIT);
        BigDecimal credits = postings.sumByDirection(Direction.CREDIT);
        assertThat(debits).isEqualByComparingTo(credits);

        for (Account account : accounts.findAll()) {
            assertThat(account.getLedgerBalance())
                    .as("cached vs derived balance of account %d", account.getId())
                    .isEqualTo(ledger.derivedBalance(account));
        }
    }
}
```

`isEqualByComparingTo` uses `compareTo`, because sums come back from the database at scale 4. This test checks *every* account in the database, including the ones created by every other test that has run before it, so it gets stronger as the suite grows. In production, the same check runs as a nightly reconciliation job.

</details>

### Exercise 3: the presentment that arrives five times

Write `ConcurrentPresentmentTest`: authorise £30, then five threads present it at the same instant. Exactly one should succeed, four should get `AuthorisationStateException`, and the ledger balance should have gone down by £30 exactly once.

<details>
<summary>Solution</summary>

`src/test/java/com/example/cards/clearing/ConcurrentPresentmentTest.java`:

```java
package com.example.cards.clearing;

import com.example.cards.CardsIntegrationTest;
import com.example.cards.account.AccountService;
import com.example.cards.authorisation.AuthorisationService;
import com.example.cards.authorisation.AuthorisationStateException;
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
class ConcurrentPresentmentTest {

    @Autowired AccountService accountService;
    @Autowired CardService cardService;
    @Autowired AuthorisationService authorisationService;
    @Autowired ClearingService clearingService;

    @Test
    void aPresentmentDeliveredFiveTimesAtOncePostsOnce() throws Exception {
        Long accountId = accountService.open("Alice", Money.gbp("0.00")).getId();
        accountService.deposit(accountId, Money.gbp("100.00"));
        String token = cardService.issue(accountId).getToken();
        Long authorisationId = authorisationService
                .authorise(token, Money.gbp("30.00"), "Corner Cafe", "5814").authorisationId();

        CountDownLatch startingGun = new CountDownLatch(1);
        AtomicInteger cleared = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    try {
                        clearingService.clear(authorisationId, Money.gbp("30.00"));
                        cleared.incrementAndGet();
                    } catch (AuthorisationStateException e) {
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

        assertThat(cleared.get()).isEqualTo(1);
        assertThat(refused.get()).isEqualTo(4);
        assertThat(accountService.get(accountId).getLedgerBalance()).isEqualTo(Money.gbp("70.00"));
    }
}
```

It passes first time, because `ClearingService` already locks before it loads. The four duplicates queue on Alice's account lock, then each finds the authorisation `CLEARED`. The unique constraint on `journal_entry.authorisation_id` never gets a chance to fire, and that's how it should be: it's there for the bug nobody has written yet.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 50, Failures: 0, Errors: 0, Skipped: 0
```

New files since guide 03:

```
src/main/java/com/example/cards/
├── account/AccountType.java
├── clearing/
│   ├── ClearingResult.java
│   ├── ClearingService.java
│   ├── PresentmentController.java
│   ├── SettlementController.java
│   └── SettlementService.java
└── ledger/
    ├── Direction.java
    ├── JournalEntry.java
    ├── JournalEntryRepository.java
    ├── LedgerService.java
    ├── Posting.java
    └── PostingRepository.java
src/main/resources/db/migration/
└── V3__create_ledger.sql
src/test/java/com/example/cards/
├── authorisation/AuthorisationTest.java
├── clearing/
│   ├── ConcurrentPresentmentTest.java
│   ├── PresentmentApiTest.java
│   └── SettlementApiTest.java
└── ledger/LedgerInvariantsTest.java
```

Modified: `Account.java` (`type`, `systemCode`, `apply` replaces `deposit`), `AccountRepository.java`, `AccountService.java`, `Authorisation.java` (`clear`), `AuthorisationController.java`, `AccountTest.java`, `requests.http`.

---

## Common errors

**`FlywayException: Found more than one migration with version 3`** followed by `Offenders:` and two paths under `target/classes/db/migration/`
You renamed or deleted a migration file, but the old copy is still in `target/`. Flyway reads migrations from the compiled output, not from `src/`. Run `./mvnw clean test` (or **Build → Rebuild Project** in IntelliJ).

**`IllegalStateException: Missing system account SCHEME_SETTLEMENT`**
The seed `INSERT`s in `V3` didn't run, or the code is spelled differently. Check `SELECT * FROM account WHERE system_code IS NOT NULL` in the H2 console.

**`409` with `"Authorisation 1 is CLEARED, so it cannot be cleared"`**
Working as intended: one presentment, one posting.

**`LedgerInvariantsTest` fails with `[cached vs derived balance of account …]`**
Some code path changed `ledger_balance` without writing postings, or wrote postings without calling `apply`. Every movement must go through `LedgerService.post`. On your development database (not the tests), balances left over from the guide 01 to 03 deposits will also disagree. Delete `data/`.

**A deadlock error (Spring reports it as a `CannotAcquireLockException`)**
None of this guide's tests produce one, but if you add a code path that locks accounts in a different order, they can. Two transactions each hold one lock and wait for the other's, and the database rolls one back. Check every path against the rule: customer first, then `SCHEME_SETTLEMENT`, then `CASH`.

**Postings silently missing, while balances still change**
`cascade = CascadeType.PERSIST` is missing from `JournalEntry.postings`. Because `postings` is the inverse (`mappedBy`) side, Hibernate saves the entry and quietly ignores its postings, with no exception. `LedgerInvariantsTest` exists to catch exactly this kind of quiet failure.

---

## Recap

- **Clearing** (presentment) is when an authorisation becomes a real payment: the issuer matches it, releases the hold, and **posts** the final amount.
- **The cleared amount can differ from the authorised amount** (fuel, tips, hotels), so post what's presented and release the whole hold. **Clearing never declines**: the issuer already promised to pay.
- A card payment in the books: **debit the customer** (a liability goes down), **credit `SCHEME_SETTLEMENT`** (the bank now owes the scheme). **Settlement** later debits `SCHEME_SETTLEMENT` and credits the bank's cash.
- Every movement goes through the **ledger**, as balanced postings in the same transaction as the cached balance, or the books stop making sense.
- **Duplicates**: lock, then check the status, with a **unique constraint** as the backstop.
- **Lock in a consistent order** across all code paths, and you can't deadlock.

### Interview angle

<details>
<summary>"What's the difference between authorisation, clearing and settlement?"</summary>

Authorisation is the real-time question and answer at the point of sale: the issuer approves or declines and places a hold, and no money moves. Clearing is when the acquirer sends the final transaction details through the scheme; the issuer matches them to the authorisation, posts the final amount to the customer's account and releases the hold. Settlement is the movement of money between the banks, usually netted and in bulk per settlement cycle, through the scheme's settlement arrangements.

</details>

<details>
<summary>"Why might a cleared amount differ from the authorised amount, and what do you do about it?"</summary>

Because the authorisation was an estimate: fuel pre-authorisations, restaurant tips, hotel and car hire bills. The issuer posts the cleared amount and releases the whole hold, so if less was cleared the customer's available balance goes up. Scheme rules limit how far over the authorised amount certain merchant types can clear, and the issuer can dispute amounts beyond that through a chargeback.

</details>

<details>
<summary>"How do you record a card payment in a double-entry ledger?"</summary>

At clearing: debit the customer's account (a liability to the bank, so it decreases) and credit an internal scheme settlement account (a liability that represents what the bank now owes the card scheme). At settlement: debit the scheme settlement account and credit the account the money actually leaves from, such as the bank's reserve or settlement bank account. Interchange and scheme fees add further postings in a real bank.

</details>

<details>
<summary>"The same clearing record arrives twice. How do you make sure the customer isn't charged twice?"</summary>

Make processing idempotent: under a lock on the account, check the authorisation's status and refuse anything that isn't still clearable, and record the posting and the status change in one transaction. Back it up with a unique constraint linking the posting to the authorisation (or to the presentment's own identifier), so the database rejects a second posting even if the logic has a bug. Test it with concurrent duplicate deliveries.

</details>

### Further reading

- [Visa: Authorization and reversal processing best practices for merchants](https://usa.visa.com/content/dam/VCOM/regional/na/us/support-legal/documents/authorization-and-reversal-processing-best-practices-for-merchants.pdf): how authorisations, reversals and clearing fit together, from the merchant's side
- [Bank of England: How do card payments work?](https://www.bankofengland.co.uk/explainers/how-do-card-payments-work): how the scheme nets up what banks owe each other, and how the Bank of England settles it
- [Martin Fowler: Accounting Patterns](https://martinfowler.com/eaaDev/AccountingNarrative.html): accounts, entries and postings as software patterns
- [Modern Treasury: Accounting for developers](https://www.moderntreasury.com/journal/accounting-for-developers-part-i)

**Next:** [05: Expiring holds](05-expiring-holds.md). Some presentments never arrive. A scheduled job releases the money, without releasing it twice.
