# 02: Drawdown, the Ledger and Value Dates

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- What happens in the bank's books when a loan is **drawn down**, and why a loan creates a deposit
- Why a loan has **two** ledger accounts of its own (principal and interest), plus the bank's **interest income** account
- **Booking date vs value date**: when a posting was recorded, and when it counts for interest
- The bank's **business date**, and why code should never call `LocalDate.now()` directly
- Injecting a `java.time.Clock`, and replacing it in tests with a clock you can move
- Persisting a schedule with JPA: a `Loan` aggregate that owns its `Instalment`s
- A balance **as at a value date**, computed with a JPQL aggregate over a join

By the end, a customer can open a current account and draw down a loan into it. The money moves through a small double-entry ledger, every posting carries two dates, and the loan's contractual schedule is stored.

---

## Concepts

### How it works in a real bank

When the bank pays out a £10,000 loan, it doesn't take £10,000 from a vault. It writes two lines in its books:

```
Journal entry: "Loan 1 drawdown"            booking 31 Jan 2026, value 31 Jan 2026
   DEBIT   Loan principal: Alice (asset)        £10,000.00   ← Alice owes the bank more
   CREDIT  Alice's current account (liability)  £10,000.00   ← the bank owes Alice more
```

Alice now owes the bank £10,000 (the loan, an [asset](../glossary.md#asset) to the bank), and the bank owes Alice £10,000 (her current account balance, a [liability](../glossary.md#liability)). Both sides of the bank's balance sheet grew by the same amount. In other words, **making a loan created a deposit**. The Bank of England explains this in [Money creation in the modern economy](https://www.bankofengland.co.uk/quarterly-bulletin/2014/q1/money-creation-in-the-modern-economy) (Quarterly Bulletin 2014 Q1), which is well worth reading before an interview at a bank.

This is [double-entry bookkeeping](../glossary.md#double-entry): every [journal entry](../glossary.md#journal-entry) is made of [postings](../glossary.md#posting) whose [debits](../glossary.md#debit) and [credits](../glossary.md#credit) add up to the same amount. This tutorial builds a deliberately **small** ledger: enough to record a loan's life correctly. The Core ledger tutorial covers ledgers properly, including concurrency, reversals and statements.

### A loan's accounts

Every loan gets two [accounts](../glossary.md#account) of its own, both assets:

| Account | Type | What its balance means |
|---|---|---|
| **Loan principal** | Asset | How much of the amount borrowed the customer still owes |
| **Loan interest** | Asset | Interest the bank has charged but the customer hasn't paid yet |

Keeping principal and interest apart is standard practice. They're treated differently almost everywhere: interest is income for the bank, principal is just the return of its own money; repayments are usually applied to interest first; regulators and accountants report them separately. Mixing them into one balance and trying to separate them later is a classic source of bugs.

The bank also has one [internal account](../glossary.md#internal-account), seeded by a migration:

| Account | Type | Normal balance |
|---|---|---|
| **Interest income: personal loans** | Income | Credit |

An **income** account is a third kind of account, alongside assets and liabilities. Income increases the bank's equity, and like liabilities its [normal balance](../glossary.md#normal-balance) is a **credit**: crediting it makes it grow. When interest is charged (guide 03), the entry will be "debit loan interest (the customer owes more), credit interest income (the bank has earned it)".

In a real bank, each of these is part of a [sub-ledger](../glossary.md#sub-ledger) (the loans sub-ledger) that rolls up into the [general ledger](../glossary.md#general-ledger), where there would be one "personal loans" asset line for all customers rather than one account per loan.

### Booking date vs value date

Every posting in a bank carries (at least) two dates:

- **Booking date:** the business day on which the bank **recorded** the posting. It's what the books looked like on that day. It never changes after the fact.
- **Value date:** the day from which the posting **counts for interest**. It's when the money is treated as having moved.

Most of the time they're the same day. They differ when the bank learns about money later or earlier than it moved:

| Situation | Booking date | Value date |
|---|---|---|
| A card payment made on Saturday, processed on Monday | Monday | Saturday |
| A payment that arrives after the day's cut-off time | The next business day | Usually the next business day |
| A correction to a payment that should have been credited last week | Today | Last week ("back-valued") |
| Interest charged by the nightly batch for an instalment due tomorrow (guide 03) | Today | Tomorrow ("forward-valued") |

Interest calculations follow the **value date**. If a £1,000 repayment is booked on Monday but value-dated Saturday, the customer should be charged interest as if their debt fell on Saturday. Statements and audit trails follow the **booking date**: "what did the books show on Friday evening?" must always have the same answer, no matter what was booked afterwards. A ledger that stores only one date can't answer both questions. (The glossary entry is [booking date / value date](../glossary.md#booking-date).)

> **Sidebar: back-valuation.** When a posting is value-dated into a period whose interest has already been calculated, the interest has to be recalculated and the difference posted as an **interest adjustment**. Core banking systems do this routinely, and it's one of the fiddliest parts of their interest engines. This tutorial avoids it: guide 04 makes sure a repayment can never be value-dated into a day that interest has already been calculated for.

### The business date and the `Clock`

A bank's day isn't the calendar day. Many core banking platforms keep a **business date** that only moves forward when the nightly [end-of-day](../glossary.md#end-of-day) processing finishes. A transaction at 00:30 on Tuesday morning, while Monday's end of day is still running, may well be booked on Monday's business date or held until Tuesday's opens.

This tutorial keeps it simpler: the business date is today's date **in London**, read from a `java.time.Clock`. Two rules make that testable:

1. **Nothing calls `LocalDate.now()` without a clock.** Every date decision goes through one small `BusinessDate` component.
2. **The `Clock` is a Spring bean,** so tests can replace it with one they control, and "today" can be 31 January 2026 in one test and 28 February in the next.

Without this, you can't write a test for "what happens on the due date?" except by waiting for the due date.

> **In older codebases:** you'll see `new Date()`, `Calendar.getInstance()` or `System.currentTimeMillis()` scattered through business logic, with tests that only pass on certain days of the month or need a mocking library to fake static methods. If you're working on one, introducing a `Clock` bean is a small, safe refactor that pays off immediately.

---

## Steps

### 1. The ledger tables

Create `src/main/resources/db/migration/V1__create_ledger.sql`:

```sql
CREATE TABLE ledger_account (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100)             NOT NULL,
    type        VARCHAR(20)              NOT NULL,
    currency    VARCHAR(3)               NOT NULL,
    balance     NUMERIC(19, 4)           NOT NULL,
    system_code VARCHAR(30),
    opened_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_ledger_account_system_code UNIQUE (system_code),
    CONSTRAINT ck_ledger_account_type CHECK (type IN ('ASSET', 'LIABILITY', 'INCOME'))
);

-- The bank's own income account: every penny of interest it earns ends up here
INSERT INTO ledger_account (name, type, currency, balance, system_code, opened_at)
VALUES ('Interest income: personal loans', 'INCOME', 'GBP', 0, 'INTEREST_INCOME', CURRENT_TIMESTAMP);

CREATE TABLE journal_entry (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    description  VARCHAR(200)             NOT NULL,
    booking_date DATE                     NOT NULL,
    value_date   DATE                     NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE posting (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    journal_entry_id BIGINT         NOT NULL REFERENCES journal_entry (id),
    account_id       BIGINT         NOT NULL REFERENCES ledger_account (id),
    direction        VARCHAR(6)     NOT NULL,
    amount           NUMERIC(19, 4) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    CONSTRAINT ck_posting_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_posting_amount_positive CHECK (amount > 0)
);

CREATE INDEX ix_posting_account ON posting (account_id);
```

- **`booking_date` and `value_date` are `DATE`, not timestamps.** They're business days, not instants. `created_at` is the technical "when did this row get written" instant, which is a different thing again.
- **Both dates live on the journal entry,** so all the postings in one entry share them. That's a simplification: some ledgers allow different value dates on different legs of one entry (for example in foreign exchange, where the two currencies can settle on different days).
- **The `INSERT` seeds the income account.** On a fresh database it gets `id` 1, so the first customer account will be `id` 2.
- All the SQL is portable: it runs unchanged on H2 and PostgreSQL.

### 2. Directions and account types

Create `ledger/Direction.java`:

```java
package com.example.loans.ledger;

public enum Direction {
    DEBIT,
    CREDIT
}
```

And `ledger/AccountType.java`:

```java
package com.example.loans.ledger;

/**
 * Where an account sits in the bank's books, and which direction makes it grow.
 * A loan is money owed TO the bank: an ASSET. A current account is money the bank
 * owes the customer: a LIABILITY. Interest the bank earns is INCOME.
 */
public enum AccountType {
    ASSET(Direction.DEBIT),
    LIABILITY(Direction.CREDIT),
    INCOME(Direction.CREDIT);

    private final Direction normalBalance;

    AccountType(Direction normalBalance) {
        this.normalBalance = normalBalance;
    }

    public Direction normalBalance() {
        return normalBalance;
    }
}
```

The whole debit/credit rulebook for this tutorial is those three lines.

### 3. The ledger account

Create `ledger/LedgerAccount.java`:

```java
package com.example.loans.ledger;

import com.example.loans.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;

@Entity
@Table(name = "ledger_account")
public class LedgerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType type;

    @Column(name = "currency", nullable = false, length = 3)
    private String currencyCode;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Column(name = "system_code", length = 30, unique = true)
    private String systemCode;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    protected LedgerAccount() {
        // for JPA
    }

    private LedgerAccount(String name, AccountType type) {
        this.name = name;
        this.type = type;
        this.currencyCode = Money.GBP.getCurrencyCode();
        this.balance = BigDecimal.ZERO;
        this.openedAt = Instant.now();
    }

    /** A customer's current account: the bank owes the customer this money. */
    public static LedgerAccount currentAccount(String customerName) {
        return new LedgerAccount(customerName, AccountType.LIABILITY);
    }

    /** One of a loan's own accounts: the customer owes the bank this money. */
    public static LedgerAccount loanAccount(String name) {
        return new LedgerAccount(name, AccountType.ASSET);
    }

    /** Updates the cached balance for one posting. Only LedgerService calls this. */
    void apply(Direction direction, Money amount) {
        Money current = getBalance();
        Money updated = direction == type.normalBalance() ? current.plus(amount) : current.minus(amount);
        this.balance = updated.amount();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public AccountType getType() {
        return type;
    }

    public Currency getCurrency() {
        return Currency.getInstance(currencyCode);
    }

    public Money getBalance() {
        return new Money(balance, getCurrency());
    }

    public String getSystemCode() {
        return systemCode;
    }

    @Override
    public String toString() {
        return "LedgerAccount[id=%d, name=%s, balance=%s]".formatted(id, name, getBalance());
    }
}
```

The patterns are the same as in any well-behaved entity:

- **No setters.** The only way to change a balance is `apply`, and it's **package-private** (no `public`), so only classes in the `ledger` package (in practice, `LedgerService`) can call it.
- **Named factories** (`currentAccount`, `loanAccount`) say what's being opened in banking terms, and guarantee the type is right.
- **`balance` is a [cached balance](../glossary.md#cached-balance).** It's updated in the same transaction as every posting, and it must always equal the [derived balance](../glossary.md#derived-balance) worked out from the postings. You'll prove that in Exercise 2.
- **`toString`** is there so test failures print something readable. It deliberately avoids lazy relationships, which could throw outside a transaction.

### 4. Postings and journal entries

Create `ledger/Posting.java`:

```java
package com.example.loans.ledger;

import com.example.loans.money.Money;
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
    private LedgerAccount account;

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

    Posting(JournalEntry entry, LedgerAccount account, Direction direction, Money amount) {
        this.entry = entry;
        this.account = account;
        this.direction = direction;
        this.amount = amount.amount();
        this.currencyCode = amount.currency().getCurrencyCode();
    }

    public Long getId() {
        return id;
    }

    public JournalEntry getEntry() {
        return entry;
    }

    public LedgerAccount getAccount() {
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

The constructor is package-private: only the journal entry's builder can create postings.

Create `ledger/JournalEntry.java`, with a builder that takes **both dates** and refuses to build an unbalanced entry:

```java
package com.example.loans.ledger;

import com.example.loans.money.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One business event in the ledger: postings whose debits equal their credits.
 * Booking date = the business day it was recorded. Value date = the day it takes
 * effect for interest.
 */
@Entity
@Table(name = "journal_entry")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(name = "booking_date", nullable = false)
    private LocalDate bookingDate;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.PERSIST)
    private List<Posting> postings = new ArrayList<>();

    protected JournalEntry() {
        // for JPA
    }

    private JournalEntry(String description, LocalDate bookingDate, LocalDate valueDate) {
        this.description = description;
        this.bookingDate = Objects.requireNonNull(bookingDate, "bookingDate must not be null");
        this.valueDate = Objects.requireNonNull(valueDate, "valueDate must not be null");
        this.createdAt = Instant.now();
    }

    public static Builder builder(String description, LocalDate bookingDate, LocalDate valueDate) {
        return new Builder(description, bookingDate, valueDate);
    }

    public Long getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public LocalDate getBookingDate() {
        return bookingDate;
    }

    public LocalDate getValueDate() {
        return valueDate;
    }

    public List<Posting> getPostings() {
        return Collections.unmodifiableList(postings);
    }

    public static final class Builder {

        private final JournalEntry entry;

        private Builder(String description, LocalDate bookingDate, LocalDate valueDate) {
            this.entry = new JournalEntry(description, bookingDate, valueDate);
        }

        public Builder debit(LedgerAccount account, Money amount) {
            return add(account, Direction.DEBIT, amount);
        }

        public Builder credit(LedgerAccount account, Money amount) {
            return add(account, Direction.CREDIT, amount);
        }

        private Builder add(LedgerAccount account, Direction direction, Money amount) {
            if (!amount.isPositive()) {
                throw new IllegalArgumentException("Posting amounts must be positive, got " + amount);
            }
            entry.postings.add(new Posting(entry, account, direction, amount));
            return this;
        }

        /** Checks the double-entry rule and returns the entry. */
        public JournalEntry build() {
            if (entry.postings.size() < 2) {
                throw new IllegalStateException("A journal entry needs at least two postings");
            }
            Money debits = Money.zero(Money.GBP);
            Money credits = Money.zero(Money.GBP);
            for (Posting posting : entry.postings) {
                if (posting.getDirection() == Direction.DEBIT) {
                    debits = debits.plus(posting.getAmount());
                } else {
                    credits = credits.plus(posting.getAmount());
                }
            }
            if (!debits.equals(credits)) {
                throw new IllegalStateException(
                        "Unbalanced entry: debits %s, credits %s".formatted(debits, credits));
            }
            return entry;
        }
    }
}
```

**The builder makes both dates mandatory.** There is no "default to today" overload. Every caller has to decide, which is exactly the point: a developer writing a new kind of posting is forced to think about its value date.

Unit-test the builder in `src/test/java/com/example/loans/ledger/JournalEntryTest.java`:

```java
package com.example.loans.ledger;

import com.example.loans.money.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalEntryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    @Test
    void balancedEntryBuilds() {
        JournalEntry entry = JournalEntry.builder("Drawdown", TODAY, TODAY)
                .debit(LedgerAccount.loanAccount("Loan principal"), Money.gbp("1000.00"))
                .credit(LedgerAccount.currentAccount("Alice"), Money.gbp("1000.00"))
                .build();

        assertThat(entry.getPostings()).hasSize(2);
        assertThat(entry.getValueDate()).isEqualTo(TODAY);
    }

    @Test
    void unbalancedEntryIsRefused() {
        assertThatThrownBy(() -> JournalEntry.builder("Oops", TODAY, TODAY)
                .debit(LedgerAccount.loanAccount("Loan principal"), Money.gbp("1000.00"))
                .credit(LedgerAccount.currentAccount("Alice"), Money.gbp("999.99"))
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unbalanced entry: debits GBP 1000.00, credits GBP 999.99");
    }
}
```

These are plain unit tests: no Spring, no database. Entities are just Java objects until they're saved.

### 5. The ledger service and value-dated balances

Create `ledger/LedgerAccountRepository.java`:

```java
package com.example.loans.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, Long> {

    Optional<LedgerAccount> findBySystemCode(String systemCode);
}
```

`ledger/JournalEntryRepository.java`:

```java
package com.example.loans.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {
}
```

And `ledger/PostingRepository.java`, with the two aggregate queries:

```java
package com.example.loans.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface PostingRepository extends JpaRepository<Posting, Long> {

    @Query("""
            select coalesce(sum(p.amount), 0)
            from Posting p
            where p.account.id = :accountId and p.direction = :direction
            """)
    BigDecimal sumByAccountAndDirection(Long accountId, Direction direction);

    /** Only postings whose value date is on or before {@code valueDate}. */
    @Query("""
            select coalesce(sum(p.amount), 0)
            from Posting p
            where p.account.id = :accountId and p.direction = :direction
              and p.entry.valueDate <= :valueDate
            """)
    BigDecimal sumByAccountAndDirectionValuedBy(Long accountId, Direction direction, LocalDate valueDate);
}
```

`p.entry.valueDate` navigates from the posting to its journal entry. Hibernate turns that into a SQL `JOIN` for you. `coalesce(…, 0)` returns zero instead of `null` when an account has no postings yet.

Now `ledger/LedgerService.java`:

```java
package com.example.loans.ledger;

import com.example.loans.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

@Service
public class LedgerService {

    private final JournalEntryRepository entries;
    private final PostingRepository postings;
    private final LedgerAccountRepository accounts;

    public LedgerService(JournalEntryRepository entries, PostingRepository postings,
                         LedgerAccountRepository accounts) {
        this.entries = entries;
        this.postings = postings;
        this.accounts = accounts;
    }

    /** Saves the entry and updates each account's cached balance, in one transaction. */
    @Transactional
    public JournalEntry post(JournalEntry entry) {
        for (Posting posting : entry.getPostings()) {
            posting.getAccount().apply(posting.getDirection(), posting.getAmount());
        }
        return entries.save(entry);
    }

    @Transactional(readOnly = true)
    public LedgerAccount getSystemAccount(String systemCode) {
        return accounts.findBySystemCode(systemCode)
                .orElseThrow(() -> new IllegalStateException("Missing system account " + systemCode));
    }

    /** The balance from every posting, whatever its dates: the source of truth for "now". */
    @Transactional(readOnly = true)
    public Money derivedBalance(LedgerAccount account) {
        return signed(account,
                postings.sumByAccountAndDirection(account.getId(), Direction.DEBIT),
                postings.sumByAccountAndDirection(account.getId(), Direction.CREDIT));
    }

    /** The balance for interest purposes at the end of {@code valueDate}. */
    @Transactional(readOnly = true)
    public Money balanceAsAt(LedgerAccount account, LocalDate valueDate) {
        return signed(account,
                postings.sumByAccountAndDirectionValuedBy(account.getId(), Direction.DEBIT, valueDate),
                postings.sumByAccountAndDirectionValuedBy(account.getId(), Direction.CREDIT, valueDate));
    }

    private Money signed(LedgerAccount account, BigDecimal debits, BigDecimal credits) {
        BigDecimal balance = account.getType().normalBalance() == Direction.DEBIT
                ? debits.subtract(credits)
                : credits.subtract(debits);
        return new Money(balance, account.getCurrency());
    }
}
```

- **`post` is the only caller of `apply`.** It updates every cached balance and saves the entry in one transaction. There's no `accounts.save(...)`: the accounts are **managed** entities in the caller's transaction, so Hibernate writes their changes at commit (dirty checking). That relies on the caller having loaded the accounts inside the same transaction. Keep that in mind: it's the first common error below.
- **`balanceAsAt` is the method that makes value dates useful.** In guide 03, the nightly batch will ask "what was this loan's principal at the end of 3 February, for interest purposes?" and get an answer that includes back-valued postings and excludes forward-valued ones.
- **`signed` applies the normal-balance rule:** for an asset, debits minus credits; for a liability or income account, credits minus debits.

> **Sidebar: as-at queries at scale.** Summing every posting up to a date is fine for a tutorial. A loan book with millions of accounts and years of history needs more: real systems keep **daily balance snapshots** (one row per account per value date) or end-of-day balance tables, and query those instead. Guide 03's accrual table is a small example of the same idea.

### 6. The business date

Create `time/ClockConfig.java`:

```java
package com.example.loans.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ClockConfig {

    /** The bank's clock, in the bank's time zone. Tests replace this bean. */
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Europe/London"));
    }
}
```

And `time/BusinessDate.java`:

```java
package com.example.loans.time;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/** "What day is it, for the bank?" Every date-sensitive decision asks this, never LocalDate.now(). */
@Component
public class BusinessDate {

    private final Clock clock;

    public BusinessDate(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
```

**The zone matters.** `Clock.systemUTC()` would give the wrong business date for an hour every night during British Summer Time: at 00:30 BST it's still 23:30 the previous day in UTC. A UK bank's business day is a London day.

Now the test side. Create `src/test/java/com/example/loans/MutableClock.java`:

```java
package com.example.loans;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** A clock that tests can move. Starts at midday on 6 November 2026, London time. */
public class MutableClock extends Clock {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private volatile Instant instant = LocalDate.of(2026, 11, 6).atTime(12, 0).atZone(LONDON).toInstant();

    public void setDate(LocalDate date) {
        instant = date.atTime(12, 0).atZone(LONDON).toInstant();
    }

    @Override
    public ZoneId getZone() {
        return LONDON;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
```

- **`java.time.Clock` is an abstract class** with three methods to implement. `LocalDate.now(clock)` only calls `instant()` and `getZone()`.
- **Midday**, so a date never slips to the day before or after because of a time zone or daylight saving shift.
- **`volatile`**, because in guide 04 a test moves the clock on one thread while others read it.

> **Sidebar: why not `Clock.fixed(...)`?** A fixed clock is perfect when a test needs one date. These tests need to *move* through time: draw a loan down on 31 January, run end of day every night until 27 February, then repay on the 28th. A mutable clock shared by the whole Spring context makes that a one-line `clock.setDate(...)`. The cost is shared state: every test that cares about the date must set it in `@BeforeEach`, and never rely on what an earlier test left behind.

Register it in `src/test/java/com/example/loans/TestClockConfig.java`:

```java
package com.example.loans;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestClockConfig {

    /** Wins over the real Clock bean, so every test controls "today". */
    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock();
    }
}
```

Both `Clock` beans exist in the test context. `@Primary` tells Spring which one to inject when something asks for "a `Clock`", so `BusinessDate` gets the mutable one. Tests can inject `MutableClock` directly to move it.

Import it from the meta-annotation, so every integration test gets it. Update `LoansIntegrationTest`:

```java
import org.springframework.context.annotation.Import;
...

/**
 * Full application, real Flyway migrations, in-memory H2 (so tests never
 * touch ./data), a MockMvcTester for calling the API, and a clock tests can move.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:loans-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public @interface LoansIntegrationTest {
}
```

### 7. Prove the value-date query

Create `src/test/java/com/example/loans/ledger/ValueDateTest.java`:

```java
package com.example.loans.ledger;

import com.example.loans.LoansIntegrationTest;
import com.example.loans.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@LoansIntegrationTest
@Transactional // the accounts must be managed, so post() can update their cached balances
class ValueDateTest {

    @Autowired LedgerService ledger;
    @Autowired LedgerAccountRepository accounts;

    @Test
    void balanceAsAtFollowsTheValueDateNotTheBookingDate() {
        LedgerAccount loan = accounts.save(LedgerAccount.loanAccount("Loan principal: test"));
        LedgerAccount customer = accounts.save(LedgerAccount.currentAccount("Test customer"));
        LocalDate friday = LocalDate.of(2026, 11, 6);
        LocalDate monday = LocalDate.of(2026, 11, 9);

        // Booked on Monday, but it counts from Friday
        ledger.post(JournalEntry.builder("Back-valued drawdown", monday, friday)
                .debit(loan, Money.gbp("1000.00"))
                .credit(customer, Money.gbp("1000.00"))
                .build());

        assertThat(ledger.balanceAsAt(loan, friday.minusDays(1))).isEqualTo(Money.gbp("0.00"));
        assertThat(ledger.balanceAsAt(loan, friday)).isEqualTo(Money.gbp("1000.00"));
        assertThat(ledger.derivedBalance(loan)).isEqualTo(Money.gbp("1000.00"));
    }
}
```

**Why `@Transactional` on the test class?** The test saves two accounts, then posts an entry against them. Without a surrounding transaction, `accounts.save(...)` commits and returns **detached** entities. `post` then calls `apply` on those detached objects, which changes them in memory, but nothing ever writes them to the database. The postings are saved (they're new), but the cached balances stay at zero. You'd only find out when another test compared cached and derived balances. With the class-level `@Transactional`, everything runs in one transaction that Spring rolls back at the end of the test, so it also leaves nothing behind.

(The real services don't have this problem, because they load accounts and post entries inside the same `@Transactional` method.)

### 8. Customer current accounts

The loan needs somewhere to pay the money. Create `account/AccountNotFoundException.java`:

```java
package com.example.loans.account;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(Long id) {
        super("No account with id " + id);
    }
}
```

`account/AccountService.java`:

```java
package com.example.loans.account;

import com.example.loans.ledger.AccountType;
import com.example.loans.ledger.LedgerAccount;
import com.example.loans.ledger.LedgerAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customer current accounts: where loans are paid out to and repaid from. */
@Service
public class AccountService {

    private final LedgerAccountRepository accounts;

    public AccountService(LedgerAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public LedgerAccount open(String customerName) {
        return accounts.save(LedgerAccount.currentAccount(customerName));
    }

    @Transactional(readOnly = true)
    public LedgerAccount get(Long id) {
        return accounts.findById(id)
                .filter(account -> account.getType() == AccountType.LIABILITY)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }
}
```

The `filter` means `GET /accounts/1` (the bank's income account) is a `404`. Customers have no business looking at the bank's own books, and the API shouldn't even confirm they exist.

`account/AccountController.java`:

```java
package com.example.loans.account;

import com.example.loans.ledger.LedgerAccount;
import com.example.loans.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request) {
        LedgerAccount account = accountService.open(request.name());
        return ResponseEntity.created(URI.create("/accounts/" + account.getId()))
                .body(AccountResponse.from(account));
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable Long id) {
        return AccountResponse.from(accountService.get(id));
    }

    public record OpenAccountRequest(@NotBlank @Size(max = 100) String name) {
    }

    public record AccountResponse(Long id, String name, Money balance) {

        static AccountResponse from(LedgerAccount account) {
            return new AccountResponse(account.getId(), account.getName(), account.getBalance());
        }
    }
}
```

### 9. The loan and its schedule

Create `src/main/resources/db/migration/V2__create_loans.sql`:

```sql
CREATE TABLE loan (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    customer_account_id   BIGINT         NOT NULL REFERENCES ledger_account (id),
    principal_account_id  BIGINT         NOT NULL REFERENCES ledger_account (id),
    interest_account_id   BIGINT         NOT NULL REFERENCES ledger_account (id),
    principal             NUMERIC(19, 4) NOT NULL,
    annual_rate           NUMERIC(5, 2)  NOT NULL,
    term_months           INTEGER        NOT NULL,
    drawdown_date         DATE           NOT NULL,
    monthly_payment       NUMERIC(19, 4) NOT NULL,
    status                VARCHAR(20)    NOT NULL,
    CONSTRAINT ck_loan_status CHECK (status IN ('ACTIVE', 'SETTLED'))
);

-- The contractual schedule, fixed when the loan is drawn down
CREATE TABLE loan_instalment (
    id                  BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    loan_id             BIGINT         NOT NULL REFERENCES loan (id),
    number              INTEGER        NOT NULL,
    due_date            DATE           NOT NULL,
    scheduled_payment   NUMERIC(19, 4) NOT NULL,
    scheduled_interest  NUMERIC(19, 4) NOT NULL,
    scheduled_principal NUMERIC(19, 4) NOT NULL,
    CONSTRAINT uk_loan_instalment_number UNIQUE (loan_id, number)
);

CREATE INDEX ix_loan_instalment_due_date ON loan_instalment (due_date);
```

- **The loan doesn't store a balance.** What the customer owes lives in the ledger accounts it points to. Duplicating it on the loan would create a second source of truth to keep in step.
- **The schedule is stored as *scheduled* amounts.** In guide 03 you'll add columns for what was actually **billed**, because a customer who pays late is charged more interest than the schedule projected. The original schedule stays as the contractual record.
- **`ix_loan_instalment_due_date`** is for the nightly batch, which will ask "which instalments fall due tomorrow?" across every loan.

`loan/LoanStatus.java`:

```java
package com.example.loans.loan;

public enum LoanStatus {
    ACTIVE,
    SETTLED
}
```

`loan/Instalment.java`:

```java
package com.example.loans.loan;

import com.example.loans.money.Money;
import com.example.loans.schedule.ScheduledInstalment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One row of a loan's contractual schedule. */
@Entity
@Table(name = "loan_instalment")
public class Instalment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_id")
    private Loan loan;

    @Column(nullable = false)
    private int number;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "scheduled_payment", nullable = false, precision = 19, scale = 4)
    private BigDecimal scheduledPayment;

    @Column(name = "scheduled_interest", nullable = false, precision = 19, scale = 4)
    private BigDecimal scheduledInterest;

    @Column(name = "scheduled_principal", nullable = false, precision = 19, scale = 4)
    private BigDecimal scheduledPrincipal;

    protected Instalment() {
        // for JPA
    }

    Instalment(Loan loan, ScheduledInstalment line) {
        this.loan = loan;
        this.number = line.number();
        this.dueDate = line.dueDate();
        this.scheduledPayment = line.payment().amount();
        this.scheduledInterest = line.interest().amount();
        this.scheduledPrincipal = line.principal().amount();
    }

    public Long getId() {
        return id;
    }

    public Loan getLoan() {
        return loan;
    }

    public int getNumber() {
        return number;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public Money getScheduledPayment() {
        return new Money(scheduledPayment, Money.GBP);
    }

    public Money getScheduledInterest() {
        return new Money(scheduledInterest, Money.GBP);
    }

    public Money getScheduledPrincipal() {
        return new Money(scheduledPrincipal, Money.GBP);
    }
}
```

`loan/Loan.java`:

```java
package com.example.loans.loan;

import com.example.loans.ledger.LedgerAccount;
import com.example.loans.money.Money;
import com.example.loans.schedule.Schedule;
import com.example.loans.schedule.ScheduledInstalment;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A personal loan: the contract (amount, rate, term, schedule) plus pointers to the
 * ledger accounts that hold what the customer owes.
 */
@Entity
@Table(name = "loan")
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_account_id")
    private LedgerAccount customerAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "principal_account_id")
    private LedgerAccount principalAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "interest_account_id")
    private LedgerAccount interestAccount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal principal;

    @Column(name = "annual_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal annualRate;

    @Column(name = "term_months", nullable = false)
    private int termMonths;

    @Column(name = "drawdown_date", nullable = false)
    private LocalDate drawdownDate;

    @Column(name = "monthly_payment", nullable = false, precision = 19, scale = 4)
    private BigDecimal monthlyPayment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LoanStatus status;

    @OneToMany(mappedBy = "loan", cascade = CascadeType.PERSIST)
    @OrderBy("number")
    private List<Instalment> instalments = new ArrayList<>();

    protected Loan() {
        // for JPA
    }

    private Loan(LedgerAccount customerAccount, LedgerAccount principalAccount,
                 LedgerAccount interestAccount, Schedule schedule) {
        this.customerAccount = customerAccount;
        this.principalAccount = principalAccount;
        this.interestAccount = interestAccount;
        this.principal = schedule.terms().principal().amount();
        this.annualRate = schedule.terms().annualRatePercent();
        this.termMonths = schedule.terms().termMonths();
        this.drawdownDate = schedule.terms().drawdownDate();
        this.monthlyPayment = schedule.regularPayment().amount();
        this.status = LoanStatus.ACTIVE;
        for (ScheduledInstalment line : schedule.instalments()) {
            instalments.add(new Instalment(this, line));
        }
    }

    public static Loan drawDown(LedgerAccount customerAccount, LedgerAccount principalAccount,
                                LedgerAccount interestAccount, Schedule schedule) {
        return new Loan(customerAccount, principalAccount, interestAccount, schedule);
    }

    public Long getId() {
        return id;
    }

    public LedgerAccount getCustomerAccount() {
        return customerAccount;
    }

    public LedgerAccount getPrincipalAccount() {
        return principalAccount;
    }

    public LedgerAccount getInterestAccount() {
        return interestAccount;
    }

    public Money getPrincipal() {
        return new Money(principal, Money.GBP);
    }

    public BigDecimal getAnnualRate() {
        return annualRate;
    }

    public int getTermMonths() {
        return termMonths;
    }

    public LocalDate getDrawdownDate() {
        return drawdownDate;
    }

    public Money getMonthlyPayment() {
        return new Money(monthlyPayment, Money.GBP);
    }

    public LoanStatus getStatus() {
        return status;
    }

    public List<Instalment> getInstalments() {
        return Collections.unmodifiableList(instalments);
    }
}
```

`Loan` is an **aggregate root**: it owns its instalments. They're created only by the `Loan` constructor (the `Instalment` constructor is package-private), saved by cascade, and handed out read-only. `@OrderBy("number")` means `getInstalments()` always comes back in schedule order, which guide 04's repayment logic depends on.

`loan/LoanRepository.java` and `loan/LoanNotFoundException.java`:

```java
package com.example.loans.loan;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanRepository extends JpaRepository<Loan, Long> {
}
```

```java
package com.example.loans.loan;

public class LoanNotFoundException extends RuntimeException {

    public LoanNotFoundException(Long id) {
        super("No loan with id " + id);
    }
}
```

### 10. Drawing a loan down

The service returns read-only view records, built **inside** the transaction. With `open-in-view=false`, touching a lazy relationship (like `loan.getPrincipalAccount().getBalance()`) after the transaction has ended throws. Building the view inside the service avoids that completely.

`loan/LoanSummary.java`:

```java
package com.example.loans.loan;

import com.example.loans.money.Money;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A read-only view of a loan, built inside a transaction so nothing lazy escapes. */
public record LoanSummary(
        Long id,
        Long customerAccountId,
        LoanStatus status,
        Money principal,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal annualRate,
        int termMonths,
        LocalDate drawdownDate,
        Money monthlyPayment,
        Money principalOutstanding,
        Money interestOutstanding) {

    static LoanSummary from(Loan loan) {
        return new LoanSummary(
                loan.getId(), loan.getCustomerAccount().getId(), loan.getStatus(),
                loan.getPrincipal(), loan.getAnnualRate(), loan.getTermMonths(),
                loan.getDrawdownDate(), loan.getMonthlyPayment(),
                loan.getPrincipalAccount().getBalance(), loan.getInterestAccount().getBalance());
    }
}
```

`loan/InstalmentView.java`:

```java
package com.example.loans.loan;

import com.example.loans.money.Money;

import java.time.LocalDate;

public record InstalmentView(
        int number,
        LocalDate dueDate,
        Money scheduledPayment,
        Money scheduledInterest,
        Money scheduledPrincipal) {

    static InstalmentView from(Instalment instalment) {
        return new InstalmentView(
                instalment.getNumber(), instalment.getDueDate(), instalment.getScheduledPayment(),
                instalment.getScheduledInterest(), instalment.getScheduledPrincipal());
    }
}
```

Now `loan/LoanService.java`:

```java
package com.example.loans.loan;

import com.example.loans.account.AccountService;
import com.example.loans.ledger.JournalEntry;
import com.example.loans.ledger.LedgerAccount;
import com.example.loans.ledger.LedgerAccountRepository;
import com.example.loans.ledger.LedgerService;
import com.example.loans.money.Money;
import com.example.loans.schedule.LoanTerms;
import com.example.loans.schedule.Schedule;
import com.example.loans.schedule.ScheduleCalculator;
import com.example.loans.time.BusinessDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
public class LoanService {

    private final LoanRepository loans;
    private final AccountService accountService;
    private final LedgerAccountRepository ledgerAccounts;
    private final LedgerService ledger;
    private final BusinessDate businessDate;

    public LoanService(LoanRepository loans, AccountService accountService,
                       LedgerAccountRepository ledgerAccounts, LedgerService ledger,
                       BusinessDate businessDate) {
        this.loans = loans;
        this.accountService = accountService;
        this.ledgerAccounts = ledgerAccounts;
        this.ledger = ledger;
        this.businessDate = businessDate;
    }

    /** Agrees the loan and pays the money into the customer's current account, today. */
    @Transactional
    public LoanSummary drawDown(Long customerAccountId, Money amount, BigDecimal annualRate, int termMonths) {
        LedgerAccount customer = accountService.get(customerAccountId);
        LocalDate today = businessDate.today();
        Schedule schedule = ScheduleCalculator.schedule(new LoanTerms(amount, annualRate, termMonths, today));

        LedgerAccount principal = ledgerAccounts.save(
                LedgerAccount.loanAccount("Loan principal: " + customer.getName()));
        LedgerAccount interest = ledgerAccounts.save(
                LedgerAccount.loanAccount("Loan interest: " + customer.getName()));
        Loan loan = loans.save(Loan.drawDown(customer, principal, interest, schedule));

        ledger.post(JournalEntry.builder("Loan %d drawdown".formatted(loan.getId()), today, today)
                .debit(principal, amount)   // the customer owes the bank more: asset up
                .credit(customer, amount)   // the bank owes the customer more: liability up
                .build());
        return LoanSummary.from(loan);
    }

    @Transactional(readOnly = true)
    public LoanSummary summary(Long loanId) {
        return LoanSummary.from(get(loanId));
    }

    @Transactional(readOnly = true)
    public List<InstalmentView> instalments(Long loanId) {
        return get(loanId).getInstalments().stream().map(InstalmentView::from).toList();
    }

    private Loan get(Long loanId) {
        return loans.findById(loanId).orElseThrow(() -> new LoanNotFoundException(loanId));
    }
}
```

Read `drawDown` top to bottom and it's the whole banking story:

1. Find the customer's current account (a `404` if it doesn't exist).
2. Ask the **business date** what today is, and build the schedule from it. The same `ScheduleCalculator` that produced the quote produces the contract.
3. Open the loan's two ledger accounts, and save the loan with its schedule.
4. Post the drawdown, **booked and value-dated today**.

All in one transaction. If the posting fails, there's no loan, no accounts and no schedule.

`loan/LoanController.java`:

```java
package com.example.loans.loan;

import com.example.loans.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/loans")
public class LoanController {

    private final LoanService loanService;

    public LoanController(LoanService loanService) {
        this.loanService = loanService;
    }

    @PostMapping
    public ResponseEntity<LoanSummary> drawDown(@Valid @RequestBody LoanRequest request) {
        LoanSummary loan = loanService.drawDown(request.customerAccountId(),
                Money.gbp(request.amount().toPlainString()), request.annualRate(), request.termMonths());
        return ResponseEntity.created(URI.create("/loans/" + loan.id())).body(loan);
    }

    @GetMapping("/{id}")
    public LoanSummary get(@PathVariable Long id) {
        return loanService.summary(id);
    }

    @GetMapping("/{id}/instalments")
    public List<InstalmentView> instalments(@PathVariable Long id) {
        return loanService.instalments(id);
    }

    public record LoanRequest(
            @NotNull Long customerAccountId,
            @NotNull @DecimalMin("500.00") @DecimalMax("50000.00") @Digits(integer = 5, fraction = 2) BigDecimal amount,
            @NotNull @DecimalMin("0.00") @DecimalMax("99.99") @Digits(integer = 2, fraction = 2) BigDecimal annualRate,
            @NotNull @Min(6) @Max(84) Integer termMonths) {
    }
}
```

There's no `drawdownDate` in the request. A real loan is drawn down **today**, by the bank's clock, not on a date the client chooses.

Finally, map the two "not found" exceptions in `ApiExceptionHandler` (add imports for both exceptions, `HttpStatus` and `ExceptionHandler`):

```java
    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail handle(AccountNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Account not found");
        return problem;
    }

    @ExceptionHandler(LoanNotFoundException.class)
    ProblemDetail handle(LoanNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Loan not found");
        return problem;
    }
```

### 11. Try it

Run the app. Flyway applies both migrations:

```
Migrating schema "PUBLIC" to version "1 - create ledger"
Migrating schema "PUBLIC" to version "2 - create loans"
Successfully applied 2 migrations to schema "PUBLIC", now at version v2
```

Add to `requests.http`:

```http
### Open Alice's current account (id 2: id 1 is the bank's interest income account)
POST http://localhost:8080/accounts
Content-Type: application/json

{"name": "Alice"}

### Draw down a loan into Alice's account
POST http://localhost:8080/loans
Content-Type: application/json

{"customerAccountId": 2, "amount": "5000.00", "annualRate": "7.9", "termMonths": 12}

### The loan
GET http://localhost:8080/loans/1

### Its schedule
GET http://localhost:8080/loans/1/instalments

### Alice's current account
GET http://localhost:8080/accounts/2
```

Or with curl:

```bash
curl -s -X POST localhost:8080/accounts -H 'Content-Type: application/json' -d '{"name":"Alice"}'
curl -si -X POST localhost:8080/loans -H 'Content-Type: application/json' \
  -d '{"customerAccountId":2,"amount":"5000.00","annualRate":"7.9","termMonths":12}'
```

The drawdown returns `201 Created` with `Location: /loans/1` and (run on 6 November 2026):

```json
{
  "id": 1,
  "customerAccountId": 2,
  "status": "ACTIVE",
  "principal": { "amount": "5000.00", "currency": "GBP" },
  "annualRate": "7.9",
  "termMonths": 12,
  "drawdownDate": "2026-11-06",
  "monthlyPayment": { "amount": "434.71", "currency": "GBP" },
  "principalOutstanding": { "amount": "5000.00", "currency": "GBP" },
  "interestOutstanding": { "amount": "0.00", "currency": "GBP" }
}
```

`GET /accounts/2` now shows Alice's £5,000:

```json
{"id":2,"name":"Alice","balance":{"amount":"5000.00","currency":"GBP"}}
```

Now look carefully at `GET /loans/1`. Everything matches, except one thing:

```json
"annualRate": "7.90",
```

The `POST` response said `"7.9"`: it was built from the `BigDecimal` that came in the request. The `GET` response says `"7.90"`: it was read back from a `NUMERIC(5, 2)` column, which always has scale 2. They're equal numbers with different scales, the same `BigDecimal` trap that `Money` exists to avoid. Here it's only cosmetic, but it's a reminder that **any `BigDecimal` crossing a boundary should have its scale normalised** if anyone will compare it as text.

### 12. API tests

`src/test/java/com/example/loans/account/AccountApiTest.java`:

```java
package com.example.loans.account;

import com.example.loans.LoansIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

@LoansIntegrationTest
class AccountApiTest {

    @Autowired MockMvcTester mvc;

    @Test
    void opensACurrentAccountWithAZeroBalance() {
        var response = mvc.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Alice"}
                        """);

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).bodyJson().extractingPath("$.balance.amount").isEqualTo("0.00");
    }

    @Test
    void returns404ForAnUnknownAccount() {
        var response = mvc.get().uri("/accounts/999999");

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }
}
```

`src/test/java/com/example/loans/loan/LoanApiTest.java`:

```java
package com.example.loans.loan;

import com.example.loans.LoansIntegrationTest;
import com.example.loans.MutableClock;
import com.example.loans.account.AccountService;
import com.example.loans.ledger.LedgerAccount;
import com.example.loans.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@LoansIntegrationTest
class LoanApiTest {

    @Autowired MockMvcTester mvc;
    @Autowired AccountService accountService;
    @Autowired LoanService loanService;
    @Autowired MutableClock clock;

    Long alice;

    @BeforeEach
    void setUp() {
        clock.setDate(LocalDate.of(2026, 1, 31));
        alice = accountService.open("Alice").getId();
    }

    @Test
    void drawdownPaysTheMoneyIntoTheCurrentAccount() {
        MvcTestResult response = drawDown(alice, "10000.00");

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).bodyJson().extractingPath("$.drawdownDate").isEqualTo("2026-01-31");
        assertThat(response).bodyJson().extractingPath("$.monthlyPayment.amount").isEqualTo("308.31");
        assertThat(response).bodyJson().extractingPath("$.principalOutstanding.amount").isEqualTo("10000.00");
        assertThat(response).bodyJson().extractingPath("$.interestOutstanding.amount").isEqualTo("0.00");

        LedgerAccount current = accountService.get(alice);
        assertThat(current.getBalance()).isEqualTo(Money.gbp("10000.00"));
    }

    @Test
    void storesTheScheduleFromTheBusinessDate() {
        Long loanId = loanService.drawDown(alice, Money.gbp("10000.00"), new BigDecimal("6.9"), 36).id();

        MvcTestResult response = mvc.get().uri("/loans/{id}/instalments", loanId).exchange();

        assertThat(response).hasStatus(HttpStatus.OK);
        assertThat(response).bodyJson().extractingPath("$.length()").isEqualTo(36);
        assertThat(response).bodyJson().extractingPath("$[0].dueDate").isEqualTo("2026-02-28");
        assertThat(response).bodyJson().extractingPath("$[0].scheduledInterest.amount").isEqualTo("52.93");
        assertThat(response).bodyJson().extractingPath("$[35].scheduledPayment.amount").isEqualTo("305.99");
    }

    @Test
    void returns404ForAnUnknownCustomerAccount() {
        assertThat(drawDown(999_999L, "10000.00")).hasStatus(HttpStatus.NOT_FOUND);
    }

    private MvcTestResult drawDown(Long customerAccountId, String amount) {
        return mvc.post().uri("/loans")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerAccountId": %d, "amount": "%s", "annualRate": "6.9", "termMonths": 36}
                        """.formatted(customerAccountId, amount))
                .exchange();
    }
}
```

`clock.setDate(LocalDate.of(2026, 1, 31))` makes "today" the same end-of-month date as guide 01's worked example, so the stored schedule can be checked against the numbers you already know: first due 28 February, £52.93 interest, last payment £305.99. Same calculator, same answers.

The tests create their own accounts and loans, and never assume the database is empty, so they can run in any order alongside other test classes.

---

## Your turn

### Exercise 1: what did the books say on Friday?

`balanceAsAt` answers the **value-date** question. Add `balanceBookedBy(LedgerAccount account, LocalDate bookingDate)` to `LedgerService`, which only counts entries *booked* on or before that date. Then add a test to `ValueDateTest` showing that, for an entry booked on Monday and value-dated Friday:

- the books on Friday showed **£0.00** (it hadn't been booked yet),
- the balance for interest on Friday is **£1,000.00**,
- the books on Monday showed **£1,000.00**.

<details>
<summary>Solution</summary>

`PostingRepository`:

```java
    /** Only postings whose booking date is on or before {@code bookingDate}. */
    @Query("""
            select coalesce(sum(p.amount), 0)
            from Posting p
            where p.account.id = :accountId and p.direction = :direction
              and p.entry.bookingDate <= :bookingDate
            """)
    BigDecimal sumByAccountAndDirectionBookedBy(Long accountId, Direction direction, LocalDate bookingDate);
```

`LedgerService`:

```java
    /** What the books showed at the end of {@code bookingDate}: only entries recorded by then. */
    @Transactional(readOnly = true)
    public Money balanceBookedBy(LedgerAccount account, LocalDate bookingDate) {
        return signed(account,
                postings.sumByAccountAndDirectionBookedBy(account.getId(), Direction.DEBIT, bookingDate),
                postings.sumByAccountAndDirectionBookedBy(account.getId(), Direction.CREDIT, bookingDate));
    }
```

`ValueDateTest`:

```java
    @Test
    void theBooksOnFridayDidNotShowAnEntryBookedOnMonday() {
        LedgerAccount loan = accounts.save(LedgerAccount.loanAccount("Loan principal: test"));
        LedgerAccount customer = accounts.save(LedgerAccount.currentAccount("Test customer"));
        LocalDate friday = LocalDate.of(2026, 11, 6);
        LocalDate monday = LocalDate.of(2026, 11, 9);

        ledger.post(JournalEntry.builder("Back-valued drawdown", monday, friday)
                .debit(loan, Money.gbp("1000.00"))
                .credit(customer, Money.gbp("1000.00"))
                .build());

        assertThat(ledger.balanceBookedBy(loan, friday)).isEqualTo(Money.gbp("0.00"));
        assertThat(ledger.balanceAsAt(loan, friday)).isEqualTo(Money.gbp("1000.00"));
        assertThat(ledger.balanceBookedBy(loan, monday)).isEqualTo(Money.gbp("1000.00"));
    }
```

Two questions, two different correct answers, from the same postings. That's why both dates are stored.

</details>

### Exercise 2: the ledger invariants

Write `src/test/java/com/example/loans/ledger/LedgerInvariantsTest.java`. Draw down five loans of different sizes and terms for five new customers, then assert:

1. Across the whole ledger, total debits equal total credits.
2. For **every** account, the cached balance equals the derived balance.

<details>
<summary>Hint</summary>

Add `@Query("select coalesce(sum(p.amount), 0) from Posting p where p.direction = :direction") BigDecimal sumByDirection(Direction direction);` to `PostingRepository`. For the second check, `assertThat(accounts.findAll()).allSatisfy(...)` runs an assertion on every element and reports every one that fails.

</details>

<details>
<summary>Solution</summary>

```java
package com.example.loans.ledger;

import com.example.loans.LoansIntegrationTest;
import com.example.loans.account.AccountService;
import com.example.loans.loan.LoanService;
import com.example.loans.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@LoansIntegrationTest
class LedgerInvariantsTest {

    @Autowired AccountService accountService;
    @Autowired LoanService loanService;
    @Autowired LedgerService ledger;
    @Autowired LedgerAccountRepository accounts;
    @Autowired PostingRepository postings;

    @Test
    void debitsEqualCreditsAndCachedEqualsDerived() {
        for (int i = 0; i < 5; i++) {
            Long customer = accountService.open("Customer " + i).getId();
            loanService.drawDown(customer, Money.gbp("1000.00").plus(Money.gbp(i + "00.00")),
                    new BigDecimal("9.9"), 12 + i);
        }

        assertThat(postings.sumByDirection(Direction.DEBIT))
                .isEqualByComparingTo(postings.sumByDirection(Direction.CREDIT));
        assertThat(accounts.findAll()).allSatisfy(account ->
                assertThat(account.getBalance()).isEqualTo(ledger.derivedBalance(account)));
    }
}
```

This test checks *every* account in the shared test database, including those other test classes created. That's deliberate: if any code path anywhere forgets to keep a cached balance in step, this test notices. (It's how the `@Transactional` problem in `ValueDateTest` was found when this guide was written. See Common errors.)

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 34, Failures: 0, Errors: 0, Skipped: 0
```

New files since guide 01:

```
src/main/java/com/example/loans/
├── account/
│   ├── AccountController.java
│   ├── AccountNotFoundException.java
│   └── AccountService.java
├── ledger/
│   ├── AccountType.java
│   ├── Direction.java
│   ├── JournalEntry.java
│   ├── JournalEntryRepository.java
│   ├── LedgerAccount.java
│   ├── LedgerAccountRepository.java
│   ├── LedgerService.java
│   ├── Posting.java
│   └── PostingRepository.java
├── loan/
│   ├── Instalment.java
│   ├── InstalmentView.java
│   ├── Loan.java
│   ├── LoanController.java
│   ├── LoanNotFoundException.java
│   ├── LoanRepository.java
│   ├── LoanService.java
│   ├── LoanStatus.java
│   └── LoanSummary.java
└── time/
    ├── BusinessDate.java
    └── ClockConfig.java
src/main/resources/db/migration/
├── V1__create_ledger.sql
└── V2__create_loans.sql
src/test/java/com/example/loans/
├── MutableClock.java
├── TestClockConfig.java
├── account/AccountApiTest.java
├── ledger/
│   ├── JournalEntryTest.java
│   ├── LedgerInvariantsTest.java
│   └── ValueDateTest.java
└── loan/LoanApiTest.java
```

Modified: `ApiExceptionHandler.java`, `LoansIntegrationTest.java`, `requests.http`.

---

## Common errors

**`LedgerInvariantsTest` fails with `LedgerAccount[id=9, name=Loan principal: test, balance=GBP 0.00]` … `expected: GBP 1000.00 but was: GBP 0.00`**
Some code posted an entry against **detached** accounts: entities saved or loaded in an earlier, already-committed transaction. `apply` changed them in memory, but nothing wrote the change back. The postings exist, so the derived balance is right, and the cached balance is stale. When this guide was written, the culprit was `ValueDateTest` without its class-level `@Transactional`. In service code, the fix is always the same: load the accounts and post the entry **inside one `@Transactional` method**.

**`LazyInitializationException: Could not initialize proxy [com.example.loans.loan.Loan#1] - no session`**
Something touched a lazy relationship after the transaction ended, typically a test calling `instalment.getLoan().getPrincipalAccount()` on an entity it got from a repository. Build what you need inside a transactional service method (as `LoanSummary.from` does), or wrap the test code in a `TransactionTemplate`.

**`IllegalStateException: Unbalanced entry: debits GBP 1000.00, credits GBP 999.99`**
The builder doing its job. Check the amounts you passed to `debit` and `credit`.

**`IllegalStateException: Missing system account INTEREST_INCOME`**
The seed row from `V1__create_ledger.sql` isn't there. If you deleted rows by hand in the H2 console, delete the `data/` folder and let Flyway rebuild the database.

**The wrong date in a test**
Something is reading the real clock. Search for `LocalDate.now()` without an argument and route it through `BusinessDate`. Also check the test sets the date in `@BeforeEach`, rather than relying on whatever an earlier test left in the shared `MutableClock`.

**`404` with `"title": "Account not found"` for account 1**
Working as intended: id 1 is the bank's income account, and `AccountService.get` only returns customer (liability) accounts.

---

## Recap

- **A loan creates a deposit:** drawdown debits the loan (an asset) and credits the customer's current account (a liability).
- A loan has **separate principal and interest accounts**. Interest the bank earns is credited to an **income** account, whose normal balance is a credit.
- Every posting has a **booking date** (when it was recorded, for the books and the audit trail) and a **value date** (when it counts, for interest). Store both, and make every caller choose.
- **`balanceAsAt(valueDate)`** answers "what was owed for interest purposes at the end of that day?", including back-valued postings.
- The **business date** comes from an injected `Clock` in the bank's time zone. Tests replace it with a clock they can move.
- **Aggregates own their children:** `Loan` creates and exposes its `Instalment`s, and services return **view records** built inside the transaction.

### Interview angle

<details>
<summary>"What's the difference between a booking date and a value date?"</summary>

The booking date is when the bank recorded the transaction in its books. The value date is when the money is deemed to have moved, which determines interest. They differ for weekend or after-cut-off transactions, corrections (back-valued) and some scheme or batch postings (forward-valued). Interest engines and balance-for-interest calculations use the value date. Statements, audit and "what did the ledger show on day X" use the booking date. A posting that's value-dated into an already-processed interest period needs an interest adjustment.

</details>

<details>
<summary>"How do you test code that depends on the current date?"</summary>

Never call `LocalDate.now()` or `Instant.now()` without a `Clock`. Inject a `java.time.Clock` bean, use `LocalDate.now(clock)`, and in tests provide a fixed or controllable clock (here a `@Primary` test bean). That makes date logic deterministic and lets a test walk through time: drawdown, due dates, missed payments. Also be explicit about the time zone, because the business date for a UK bank is a London date, not a UTC one.

</details>

<details>
<summary>"Why does a loan have separate principal and interest balances?"</summary>

They mean different things to the bank. Interest is income, principal is the bank's own money coming back. Repayments are normally allocated to interest before principal, interest is only charged on principal (in a simple loan), and they're reported and taxed differently. Keeping them in separate ledger accounts means each balance is always available exactly, rather than being reconstructed from a mixed balance later.

</details>

### Further reading

- [Bank of England: Money creation in the modern economy](https://www.bankofengland.co.uk/quarterly-bulletin/2014/q1/money-creation-in-the-modern-economy): why lending creates deposits
- [`java.time.Clock` Javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/Clock.html): including the advice to inject a clock rather than use static methods
- [Spring Framework reference: Test-managed transactions](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/tx.html): how `@Transactional` tests roll back
- [Spring Data JPA reference: Using `@Query`](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.query-methods.at-query)

**Simplification to notice:** customers here have exactly one current account, and the loan always pays into it. Real lending systems hold a **settlement instruction** per loan (where to pay the money out, and which account or mandate to collect repayments from), which can be a different bank altogether.

**Next:** [03: End of day with Spring Batch](03-end-of-day-batch.md). Interest starts to accrue, every night, for every loan, exactly once.
