# 03: The Matching Engine

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- The bank's side of the story: a tiny **double-entry ledger** whose postings feed reconciliation automatically
- How reconciliation **matching rules** work: exact first, then rule-based "fuzzy" passes for timing differences and small amount differences
- Why the **order** of the passes matters, and why every match must record **the rule that made it**
- How to design a matching engine as **pure functions** over in-memory lists, and unit-test every rule without a database
- Binding tolerances from configuration into a validated **`@ConfigurationProperties` record**
- Persisting a **reconciliation run**: its matches, and the items left over

By the end you can post entries to the ledger, import the correspondent's statement, run a reconciliation for a business date, and see which items matched, by which rule, and what's left.

---

## Concepts

### How it works in a real bank

In guide 01 you imported the correspondent's record of your nostro. The other half is your own: every [posting](../glossary.md#posting) the bank's ledger has made to the nostro account. In a real bank those postings come from many systems (payments, treasury, card settlement, fees), and the reconciliation system receives them as a **feed** from the general ledger or the sub-ledgers.

Matching then happens in **passes**, from most certain to least:

1. **Exact.** Same reference, same amount, same date. In a well-run operation the large majority of items match here, automatically.
2. **Rule-based ("fuzzy").** Rules that accept known, harmless differences: the dates are a day or two apart (a payment sent late in the evening, or over a weekend), or the amounts differ by a few pence (charges deducted by a bank along the way).
3. **Manual.** What's left goes to people, who match items the rules couldn't (a customer quoted their name instead of the invoice number) or decide something is genuinely wrong. That's guide 04.

The share of items matched automatically (the **auto-match rate**) is one of the numbers reconciliation teams watch most closely. A rule that's too strict leaves people matching by hand all day. A rule that's too loose is worse: it **hides real errors** by pairing items that aren't actually the same money. That's why tolerances are agreed with operations and risk, set per account, and treated as controlled configuration rather than something a developer tweaks.

### Order matters

Suppose your ledger has two £50 items with reference `INV-9`, booked on 5 and 6 November, and the statement has one, on 6 November. A "date within two days" rule would happily pair the statement line with the 5 November item, because it comes first. But the 6 November item is an *exact* match, and leaving it unmatched creates a break that isn't real.

Running the **exact pass over everything first**, and only then the looser passes over what's left, makes the engine pick the best explanation available. Every pass only ever sees what earlier passes left behind.

### Every match explains itself

Months later, an auditor (or you) will ask: "why did the system decide these two items were the same money?" So every match records **which rule** made it, **who** made it (the system, or a named person), and any **difference** it accepted. A tolerance match of £999.50 against £1,000.00 records a difference of −£0.50, which someone may still need to post as a charge. Reconciliation is part of the bank's [audit trail](../glossary.md#audit-trail).

### Keep the engine pure

The matching logic is the most rule-heavy, most-tested part of the system, so it gets the simplest possible shape: **lists in, result out**. No database, no Spring, no clock. The service loads the open items, converts them to small `Candidate` records, calls the engine, and persists what comes back. Every rule can then be unit-tested with three lines of setup, with no database or Spring context to start.

### The ledger, cut down

This guide adds a deliberately tiny [double-entry](../glossary.md#double-entry) ledger: each [journal entry](../glossary.md#journal-entry) has exactly one debit and one credit posting of the same amount. Customer accounts are rolled up into a single `CUSTOMER_FUNDS` liability account, because reconciliation only cares about the nostro side. The Core ledger tutorial covers ledgers properly.

Whenever the ledger posts to an account marked **reconciled**, it also writes an `INTERNAL` recon item, **in the same transaction**. The feed can never miss a posting or include one that was rolled back. (In a bank, where the ledger and the reconciliation system are separate systems, the same guarantee needs something like the transactional outbox, covered in the Payments tutorial.)

---

## Steps

### 1. The migration

Create `src/main/resources/db/migration/V3__ledger_matches_and_runs.sql`:

```sql
-- Which accounts the reconciliation system watches
ALTER TABLE account ADD COLUMN reconciled BOOLEAN DEFAULT FALSE NOT NULL;
UPDATE account SET reconciled = TRUE WHERE code IN ('NOSTRO_GBP', 'SCHEME_SETTLEMENT');

INSERT INTO account (code, name, type, currency, external_id, reconciled)
VALUES ('CUSTOMER_FUNDS', 'Customer accounts (all of them, rolled up)', 'LIABILITY', 'GBP', NULL, FALSE);
INSERT INTO account (code, name, type, currency, external_id, reconciled)
VALUES ('SUSPENSE', 'Reconciliation suspense', 'LIABILITY', 'GBP', NULL, FALSE);

-- A tiny double-entry ledger: every journal entry has one debit and one credit posting
CREATE TABLE journal_entry (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    description  VARCHAR(140)             NOT NULL,
    booking_date DATE                     NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE posting (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    journal_entry_id BIGINT         NOT NULL REFERENCES journal_entry (id),
    account_id       BIGINT         NOT NULL REFERENCES account (id),
    direction        VARCHAR(6)     NOT NULL,
    amount           NUMERIC(19, 2) NOT NULL,
    reference        VARCHAR(35)    NOT NULL
);

-- One row per reconciliation run
CREATE TABLE recon_run (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    account_code    VARCHAR(30)              NOT NULL,
    business_date   DATE                     NOT NULL,
    started_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    matched_count   INT                      NOT NULL,
    open_internal   INT                      NOT NULL,
    open_external   INT                      NOT NULL
);

-- A group of items that together explain the same money, and the rule that decided so
CREATE TABLE recon_match (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    account_code VARCHAR(30)              NOT NULL,
    match_rule   VARCHAR(30)              NOT NULL,
    difference   NUMERIC(19, 2)           NOT NULL,
    run_id       BIGINT                   REFERENCES recon_run (id),
    matched_by   VARCHAR(50)              NOT NULL,
    matched_at   TIMESTAMP WITH TIME ZONE NOT NULL
);

ALTER TABLE recon_item ADD COLUMN posting_id BIGINT REFERENCES posting (id);
ALTER TABLE recon_item ADD COLUMN match_id BIGINT REFERENCES recon_match (id);
```

- **`SUSPENSE`** is seeded now and used in guide 04.
- **`BOOLEAN`** is standard SQL and works on both H2 and PostgreSQL.
- **`recon_match.run_id` is nullable**, because in guide 04 people will make matches outside any run.
- **The column is `match_rule`, not `rule`.** `RULE` is an SQL keyword in several databases (PostgreSQL has a `CREATE RULE` statement, for example). Whether a keyword is *reserved* varies by database and version, so the simplest portable habit is not to name columns after keywords at all.

### 2. Accounts learn about debits and credits

Create `ledger/Direction.java`:

```java
package com.example.recon.ledger;

public enum Direction {
    DEBIT,
    CREDIT
}
```

In `Account`, add the `reconciled` field, a factory (the real accounts are seeded, but tests will want accounts of their own), and the one piece of bookkeeping logic the system needs:

```java
    /** True if the reconciliation system should watch this account's postings. */
    @Column(nullable = false)
    private boolean reconciled;

    protected Account() {
        // for JPA
    }

    private Account(String code, String name, AccountType type, String externalId, boolean reconciled) {
        this.code = code;
        this.name = name;
        this.type = type;
        this.currencyCode = Money.GBP.getCurrencyCode();
        this.externalId = externalId;
        this.reconciled = reconciled;
    }

    /** Opens a GBP internal account. The real ones are seeded by Flyway; tests make their own. */
    public static Account open(String code, String name, AccountType type, String externalId, boolean reconciled) {
        return new Account(code, name, type, externalId, reconciled);
    }

    /**
     * How a posting changes this account's balance. Debits increase assets;
     * credits increase liabilities. (The Core ledger tutorial explains why.)
     */
    public Money effectOf(Direction direction, Money amount) {
        boolean increases = (type == AccountType.ASSET) == (direction == Direction.DEBIT);
        return increases ? amount : amount.negate();
    }
```

Add a getter `isReconciled()`, and import `Direction` and `Money`.

`effectOf` is the **sign convention** from guide 01, in code. A debit to the nostro (an asset) is `+`, exactly like a CRDT on the correspondent's statement. A credit to `SUSPENSE` (a liability) is also `+`, because it increases what that account holds.

### 3. Postings and journal entries

`ledger/Posting.java`:

```java
package com.example.recon.ledger;

import com.example.recon.account.Account;
import com.example.recon.money.Money;
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

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 35)
    private String reference;

    protected Posting() {
        // for JPA
    }

    Posting(JournalEntry entry, Account account, Direction direction, Money amount, String reference) {
        this.entry = entry;
        this.account = account;
        this.direction = direction;
        this.amount = amount.amount();
        this.reference = reference;
    }

    public Money getAmount() {
        return new Money(amount, Money.GBP);
    }

    // + getters for id, entry, account, direction and reference
}
```

`ledger/JournalEntry.java`. The only way to build one guarantees it balances:

```java
package com.example.recon.ledger;

import com.example.recon.account.Account;
import com.example.recon.money.Money;
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
import java.util.List;

/**
 * One business event in the ledger. Always exactly one debit and one credit
 * of the same amount, so it can never be out of balance.
 */
@Entity
@Table(name = "journal_entry")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 140)
    private String description;

    @Column(name = "booking_date", nullable = false)
    private LocalDate bookingDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.PERSIST)
    private List<Posting> postings = new ArrayList<>();

    protected JournalEntry() {
        // for JPA
    }

    public static JournalEntry of(String description, LocalDate bookingDate, String reference,
                                  Account debit, Account credit, Money amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("A journal entry needs a positive amount, not " + amount);
        }
        if (debit.getId().equals(credit.getId())) {
            throw new IllegalArgumentException("Debit and credit must be different accounts");
        }
        JournalEntry entry = new JournalEntry();
        entry.description = description;
        entry.bookingDate = bookingDate;
        entry.createdAt = Instant.now();
        entry.postings.add(new Posting(entry, debit, Direction.DEBIT, amount, reference));
        entry.postings.add(new Posting(entry, credit, Direction.CREDIT, amount, reference));
        return entry;
    }

    public List<Posting> getPostings() {
        return List.copyOf(postings);
    }

    // + getters for id, description and bookingDate
}
```

`cascade = PERSIST` saves both postings when the entry is saved. `getPostings()` hands out a copy, so nobody can add a third, unbalanced leg.

`ledger/JournalEntryRepository.java` is an empty `JpaRepository<JournalEntry, Long>`. `ledger/PostingRepository.java` totals one side of an account:

```java
package com.example.recon.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;

public interface PostingRepository extends JpaRepository<Posting, Long> {

    @Query("select coalesce(sum(p.amount), 0) from Posting p where p.account.id = :accountId and p.direction = :direction")
    BigDecimal total(Long accountId, Direction direction);
}
```

### 4. The ledger feeds reconciliation

`ReconItem` needs to know which posting an internal item came from, and which match it ends up in. Add the fields (with getters), a factory, and a method to mark an item matched:

```java
    @Column(name = "posting_id")
    private Long postingId;

    @Column(name = "match_id")
    private Long matchId;

    ...

    /** An item from our own ledger. */
    public static ReconItem internal(String accountCode, Long postingId, String reference, Money amount,
                                     LocalDate bookingDate, String description) {
        ReconItem item = new ReconItem(accountCode, Side.INTERNAL, null, reference, amount,
                bookingDate, null, description);
        item.postingId = postingId;
        return item;
    }

    public void matchTo(Long matchId) {
        if (status != ItemStatus.OPEN) {
            throw new IllegalStateException("Item " + id + " is already matched");
        }
        this.status = ItemStatus.MATCHED;
        this.matchId = matchId;
    }
```

Now `ledger/LedgerService.java`:

```java
package com.example.recon.ledger;

import com.example.recon.account.Account;
import com.example.recon.account.AccountRepository;
import com.example.recon.account.UnknownAccountException;
import com.example.recon.item.ReconItem;
import com.example.recon.item.ReconItemRepository;
import com.example.recon.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final JournalEntryRepository entries;
    private final PostingRepository postings;
    private final ReconItemRepository items;

    public LedgerService(AccountRepository accounts, JournalEntryRepository entries,
                         PostingRepository postings, ReconItemRepository items) {
        this.accounts = accounts;
        this.entries = entries;
        this.postings = postings;
        this.items = items;
    }

    /**
     * Posts a two-leg entry. Every posting on a reconciled account is also handed to
     * reconciliation, in the same transaction, so the feed can never miss one.
     */
    @Transactional
    public JournalEntry post(String debitCode, String creditCode, Money amount, String reference,
                             LocalDate bookingDate, String description) {
        Account debit = account(debitCode);
        Account credit = account(creditCode);
        JournalEntry entry = entries.save(
                JournalEntry.of(description, bookingDate, reference, debit, credit, amount));
        for (Posting posting : entry.getPostings()) {
            Account account = posting.getAccount();
            if (account.isReconciled()) {
                items.save(ReconItem.internal(account.getCode(), posting.getId(), reference,
                        account.effectOf(posting.getDirection(), amount), bookingDate, description));
            }
        }
        return entry;
    }

    @Transactional(readOnly = true)
    public Money balance(String accountCode) {
        Account account = account(accountCode);
        Money debits = new Money(postings.total(account.getId(), Direction.DEBIT), Money.GBP);
        Money credits = new Money(postings.total(account.getId(), Direction.CREDIT), Money.GBP);
        return account.effectOf(Direction.DEBIT, debits).plus(account.effectOf(Direction.CREDIT, credits));
    }

    private Account account(String code) {
        return accounts.findByCode(code).orElseThrow(() -> new UnknownAccountException("No account " + code));
    }
}
```

- `entries.save(...)` inserts the entry and, through the cascade, both postings. With `IDENTITY` ids the inserts happen immediately, so `posting.getId()` is already set when the recon item is created.
- `balance` is a **derived** balance: the sum of every posting, signed by `effectOf`. No cached balance column, so nothing can drift.

And a small API, `ledger/LedgerController.java`, just enough to put some postings on the nostro by hand:

```java
package com.example.recon.ledger;

import com.example.recon.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;

/** Just enough ledger to give reconciliation something to reconcile. */
@RestController
public class LedgerController {

    private final LedgerService ledger;

    public LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping("/ledger/entries")
    public ResponseEntity<EntryResponse> post(@Valid @RequestBody EntryRequest request) {
        JournalEntry entry = ledger.post(request.debitAccount(), request.creditAccount(),
                new Money(request.amount(), Money.GBP), request.reference(),
                request.bookingDate(), request.description());
        return ResponseEntity.created(URI.create("/ledger/entries/" + entry.getId()))
                .body(new EntryResponse(entry.getId(), request.reference(), entry.getBookingDate()));
    }

    @GetMapping("/accounts/{code}")
    public BalanceResponse balance(@PathVariable String code) {
        return new BalanceResponse(code, ledger.balance(code));
    }

    public record EntryRequest(
            @NotBlank String debitAccount,
            @NotBlank String creditAccount,
            @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 35) String reference,
            @NotNull LocalDate bookingDate,
            @NotBlank @Size(max = 140) String description) {
    }

    public record EntryResponse(Long id, String reference, LocalDate bookingDate) {
    }

    public record BalanceResponse(String accountCode, Money balance) {
    }
}
```

`@Digits(integer = 15, fraction = 2)` rejects `1.001` with a `400` before it ever reaches `Money`. `JournalEntry.of` throws `IllegalArgumentException` for its own rules (same account on both sides); map that in `ApiExceptionHandler` as a `422`:

```java
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handle(IllegalArgumentException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request", ex.getMessage());
    }
```

### 5. Ledger tests

Tests from here on post to the nostro and run reconciliations. Every test class shares one in-memory database, so a test that uses the real `NOSTRO_GBP` account sees every other test's items, and its counts become unpredictable. The fix is for **each test to open an account of its own**. Create `src/test/java/com/example/recon/TestAccounts.java`:

```java
package com.example.recon;

import com.example.recon.account.Account;
import com.example.recon.account.AccountRepository;
import com.example.recon.account.AccountType;

import java.util.UUID;

/** Each test gets accounts of its own, so tests sharing one database never see each other's items. */
public final class TestAccounts {

    private TestAccounts() {
    }

    public static Account newNostro(AccountRepository accounts) {
        String code = "T-" + UUID.randomUUID().toString().substring(0, 13);
        return accounts.save(Account.open(code, "Test nostro", AccountType.ASSET, code, true));
    }
}
```

`src/test/java/com/example/recon/ledger/LedgerApiTest.java`:

```java
package com.example.recon.ledger;

import com.example.recon.ReconIntegrationTest;
import com.example.recon.TestAccounts;
import com.example.recon.account.Account;
import com.example.recon.account.AccountRepository;
import com.example.recon.item.ReconItem;
import com.example.recon.item.ReconItemRepository;
import com.example.recon.item.Side;
import com.example.recon.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ReconIntegrationTest
class LedgerApiTest {

    @Autowired MockMvcTester mvc;
    @Autowired AccountRepository accounts;
    @Autowired ReconItemRepository items;

    @Test
    void postingToAReconciledAccountFeedsReconciliation() {
        Account nostro = TestAccounts.newNostro(accounts);

        MvcTestResult in = post(nostro.getCode(), "CUSTOMER_FUNDS", "1250.00", "INV-1001");
        MvcTestResult out = post("CUSTOMER_FUNDS", nostro.getCode(), "400.00", "PAY-7731");

        assertThat(in).hasStatus(HttpStatus.CREATED);
        assertThat(out).hasStatus(HttpStatus.CREATED);
        assertThat(mvc.get().uri("/accounts/{code}", nostro.getCode()))
                .bodyJson().extractingPath("$.balance.amount").isEqualTo("850.00");

        List<ReconItem> fed = items.findAll().stream()
                .filter(i -> i.getAccountCode().equals(nostro.getCode())).toList();
        assertThat(fed).allMatch(i -> i.getSide() == Side.INTERNAL && i.getPostingId() != null);
        assertThat(fed).extracting(ReconItem::getAmount)
                .containsExactlyInAnyOrder(Money.gbp("1250.00"), Money.gbp("-400.00"));
    }

    @Test
    void rejectsAnEntryWithTheSameAccountOnBothSides() {
        MvcTestResult response = post("CUSTOMER_FUNDS", "CUSTOMER_FUNDS", "1.00", "X-1");

        assertThat(response).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response).bodyJson().extractingPath("$.detail")
                .isEqualTo("Debit and credit must be different accounts");
    }

    @Test
    void rejectsFractionsOfAPenny() {
        MvcTestResult response = post("NOSTRO_GBP", "CUSTOMER_FUNDS", "1.001", "X-2");

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors.amount").asString().startsWith("numeric value out of bounds");
    }

    private MvcTestResult post(String debit, String credit, String amount, String reference) {
        return mvc.post().uri("/ledger/entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"debitAccount": "%s", "creditAccount": "%s", "amount": "%s",
                         "reference": "%s", "bookingDate": "2026-11-06", "description": "Test entry"}
                        """.formatted(debit, credit, amount, reference))
                .exchange();
    }
}
```

The first test shows the feed: crediting `CUSTOMER_FUNDS` (not reconciled) creates no item, while both nostro postings do, with the right signs. Run it: green.

### 6. The matching engine's vocabulary

Create the package `com.example.recon.matching`. These types know nothing about JPA.

`matching/Candidate.java`, the only facts matching needs:

```java
package com.example.recon.matching;

import com.example.recon.money.Money;

import java.time.LocalDate;
import java.util.Locale;

/** The only facts the matching engine needs about an item. */
public record Candidate(long id, String reference, Money amount, LocalDate date) {

    /** References arrive with stray spaces and mixed case. Compare them normalised. */
    public String normalisedReference() {
        return reference.strip().toUpperCase(Locale.ROOT);
    }
}
```

`Locale.ROOT` makes `toUpperCase` behave the same on every machine. (Under a Turkish locale, `"i".toUpperCase()` is `"İ"`, a classic source of "works on my machine" bugs.)

`matching/MatchRule.java`:

```java
package com.example.recon.matching;

/** Why items were matched. Stored with every match, so anyone can see how it was decided. */
public enum MatchRule {
    /** Same reference, same amount, same date. */
    EXACT,
    /** Same reference and amount, dates a few days apart: a timing difference. */
    DATE_WINDOW,
    /** Same reference, dates close, amounts differ by no more than the tolerance. */
    AMOUNT_TOLERANCE
}
```

`matching/ProposedMatch.java` and `matching/MatchResult.java`:

```java
package com.example.recon.matching;

import com.example.recon.money.Money;

import java.util.List;

/** Items the engine believes explain each other. difference = external total - internal total. */
public record ProposedMatch(MatchRule rule, List<Long> internalIds, List<Long> externalIds, Money difference) {

    public ProposedMatch {
        internalIds = List.copyOf(internalIds);
        externalIds = List.copyOf(externalIds);
    }
}
```

```java
package com.example.recon.matching;

import java.util.List;

/** Every candidate ends up in exactly one place: a match, or one of the unmatched lists. */
public record MatchResult(List<ProposedMatch> matches,
                          List<Candidate> unmatchedInternal,
                          List<Candidate> unmatchedExternal) {
}
```

A match holds **lists** of ids on each side, even though the passes in this guide are all one-to-one. Exercise 1 adds a many-to-one rule, and guide 04 lets people match several items at once.

### 7. Tolerances from configuration

`matching/MatchingRules.java`:

```java
package com.example.recon.matching;

import com.example.recon.money.Money;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * How forgiving the fuzzy passes are. In a bank these are agreed with operations
 * and risk, per account, and changing them is itself a controlled change.
 */
@ConfigurationProperties(prefix = "recon.matching")
public record MatchingRules(int dateWindowDays, BigDecimal amountTolerance) {

    public MatchingRules {
        if (dateWindowDays < 0) {
            throw new IllegalArgumentException("dateWindowDays must not be negative");
        }
        if (amountTolerance == null || amountTolerance.signum() < 0) {
            throw new IllegalArgumentException("amountTolerance must be zero or more");
        }
    }

    public Money tolerance() {
        return new Money(amountTolerance, Money.GBP);
    }
}
```

Add to `application.properties`:

```properties
# Matching tolerances (see MatchingRules)
recon.matching.date-window-days=2
recon.matching.amount-tolerance=1.00
```

A **record** works as `@ConfigurationProperties`: Spring binds `date-window-days` to `dateWindowDays` through the constructor, and the compact constructor validates the values **at start-up**. A bad tolerance stops the application from starting at all, which is much better than it quietly matching the wrong things in production.

> **Simplification:** one set of tolerances for every account. Real systems set them per account (and sometimes per counterparty or product), store them in the database, and put changes to them through approval, because loosening a tolerance can hide errors.

### 8. The engine

`matching/MatchingEngine.java`:

```java
package com.example.recon.matching;

import com.example.recon.money.Money;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * Pure matching logic: lists in, result out. No database, no Spring, no clock,
 * so every rule can be unit-tested in milliseconds.
 */
public class MatchingEngine {

    private static final Comparator<Candidate> OLDEST_FIRST =
            Comparator.comparing(Candidate::date).thenComparing(Candidate::id);

    private final MatchingRules rules;

    public MatchingEngine(MatchingRules rules) {
        this.rules = rules;
    }

    public MatchResult match(List<Candidate> internal, List<Candidate> external) {
        List<Candidate> openInternal = sorted(internal);
        List<Candidate> openExternal = sorted(external);
        List<ProposedMatch> matches = new ArrayList<>();

        // Most certain first. Each pass only sees what earlier passes left behind.
        pass(MatchRule.EXACT, openInternal, openExternal, matches,
                (i, e) -> sameReference(i, e) && i.amount().equals(e.amount()) && i.date().equals(e.date()));
        pass(MatchRule.DATE_WINDOW, openInternal, openExternal, matches,
                (i, e) -> sameReference(i, e) && i.amount().equals(e.amount()) && withinDateWindow(i, e));
        pass(MatchRule.AMOUNT_TOLERANCE, openInternal, openExternal, matches,
                (i, e) -> sameReference(i, e) && withinDateWindow(i, e) && withinTolerance(i, e));

        return new MatchResult(List.copyOf(matches), List.copyOf(openInternal), List.copyOf(openExternal));
    }

    /** One-to-one pass: each external item takes the oldest open internal item the rule accepts. */
    private static void pass(MatchRule rule, List<Candidate> openInternal, List<Candidate> openExternal,
                             List<ProposedMatch> matches, BiPredicate<Candidate, Candidate> accepts) {
        Iterator<Candidate> externals = openExternal.iterator();
        while (externals.hasNext()) {
            Candidate e = externals.next();
            for (Iterator<Candidate> internals = openInternal.iterator(); internals.hasNext(); ) {
                Candidate i = internals.next();
                if (accepts.test(i, e)) {
                    matches.add(new ProposedMatch(rule, List.of(i.id()), List.of(e.id()), e.amount().minus(i.amount())));
                    internals.remove();
                    externals.remove();
                    break;
                }
            }
        }
    }

    private static boolean sameReference(Candidate i, Candidate e) {
        return i.normalisedReference().equals(e.normalisedReference());
    }

    private boolean withinDateWindow(Candidate i, Candidate e) {
        return Math.abs(ChronoUnit.DAYS.between(i.date(), e.date())) <= rules.dateWindowDays();
    }

    private boolean withinTolerance(Candidate i, Candidate e) {
        Money difference = e.amount().minus(i.amount()).abs();
        boolean sameSign = i.amount().amount().signum() == e.amount().amount().signum();
        return sameSign && !rules.tolerance().isLessThan(difference);
    }

    private static List<Candidate> sorted(List<Candidate> candidates) {
        List<Candidate> copy = new ArrayList<>(candidates);
        copy.sort(OLDEST_FIRST);
        return copy;
    }
}
```

How it fits together:

- **A pass is a rule plus a predicate.** `BiPredicate<Candidate, Candidate>` is a function that takes an internal and an external candidate and says yes or no. The three passes are the same loop with different predicates, so adding a rule is one line.
- **Removing as it goes.** When a pair matches, both are removed from the open lists through their iterators (`Iterator.remove()` is the safe way to remove while looping; `list.remove(x)` inside a for-each would throw `ConcurrentModificationException`). Nothing can be matched twice, and the next pass only sees leftovers.
- **Deterministic.** Both lists are sorted oldest first, then by id. The same inputs always give the same matches, which matters when someone asks you to explain yesterday's run.
- **`sameSign`** stops the tolerance rule pairing a 40p credit with a 40p debit, which differ by 80p but are clearly not the same money.
- **The difference is `external − internal`.** For `INV-1002` that's £999.50 − £1,000.00 = −£0.50: the bank received 50p less than it booked.

The cost is `O(internal × external)` per pass, which is fine for hundreds or a few thousand open items per account. At larger volumes you'd first group candidates by normalised reference in a `HashMap`, so each external item only looks at internal items with the same reference.

Make it a Spring bean, without making the engine itself depend on Spring. `matching/MatchingConfig.java`:

```java
package com.example.recon.matching;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MatchingRules.class)
public class MatchingConfig {

    @Bean
    MatchingEngine matchingEngine(MatchingRules rules) {
        return new MatchingEngine(rules);
    }
}
```

### 9. Unit-test the rules

`src/test/java/com/example/recon/matching/MatchingEngineTest.java`. No Spring, no database: each test builds a few candidates and checks the result:

```java
package com.example.recon.matching;

import com.example.recon.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MatchingEngineTest {

    private static final LocalDate NOV_6 = LocalDate.of(2026, 11, 6);

    private final MatchingEngine engine = new MatchingEngine(new MatchingRules(2, new BigDecimal("1.00")));

    @Test
    void matchesSameReferenceAmountAndDateExactly() {
        MatchResult result = engine.match(
                List.of(item(1, "INV-1001", "1250.00", NOV_6)),
                List.of(item(101, "INV-1001", "1250.00", NOV_6)));

        assertThat(result.matches()).containsExactly(
                new ProposedMatch(MatchRule.EXACT, List.of(1L), List.of(101L), Money.gbp("0.00")));
        assertThat(result.unmatchedInternal()).isEmpty();
        assertThat(result.unmatchedExternal()).isEmpty();
    }

    @Test
    void aTimingDifferenceInsideTheWindowIsStillAMatch() {
        MatchResult result = engine.match(
                List.of(item(1, "PAY-7731", "-400.00", NOV_6.minusDays(1))),
                List.of(item(101, "PAY-7731", "-400.00", NOV_6)));

        assertThat(result.matches()).singleElement()
                .extracting(ProposedMatch::rule).isEqualTo(MatchRule.DATE_WINDOW);
    }

    @Test
    void outsideTheWindowItIsABreakOnBothSides() {
        MatchResult result = engine.match(
                List.of(item(1, "PAY-7731", "-400.00", NOV_6.minusDays(3))),
                List.of(item(101, "PAY-7731", "-400.00", NOV_6)));

        assertThat(result.matches()).isEmpty();
        assertThat(result.unmatchedInternal()).extracting(Candidate::id).containsExactly(1L);
        assertThat(result.unmatchedExternal()).extracting(Candidate::id).containsExactly(101L);
    }

    @Test
    void aSmallDifferenceIsMatchedAndTheDifferenceIsRecorded() {
        MatchResult result = engine.match(
                List.of(item(1, "INV-1002", "1000.00", NOV_6)),
                List.of(item(101, "INV-1002", "999.50", NOV_6)));

        assertThat(result.matches()).containsExactly(
                new ProposedMatch(MatchRule.AMOUNT_TOLERANCE, List.of(1L), List.of(101L), Money.gbp("-0.50")));
    }

    @Test
    void aDifferenceOverTheToleranceIsNotMatched() {
        MatchResult result = engine.match(
                List.of(item(1, "INV-1002", "1000.00", NOV_6)),
                List.of(item(101, "INV-1002", "998.99", NOV_6)));

        assertThat(result.matches()).isEmpty();
    }

    @Test
    void theExactPassRunsFirstSoTheBestCandidateWins() {
        // Internal 1 is a day earlier, so it is "first" in date order, but internal 2 is exact
        MatchResult result = engine.match(
                List.of(item(1, "INV-9", "50.00", NOV_6.minusDays(1)), item(2, "INV-9", "50.00", NOV_6)),
                List.of(item(101, "INV-9", "50.00", NOV_6)));

        assertThat(result.matches()).singleElement().satisfies(m -> {
            assertThat(m.rule()).isEqualTo(MatchRule.EXACT);
            assertThat(m.internalIds()).containsExactly(2L);
        });
        assertThat(result.unmatchedInternal()).extracting(Candidate::id).containsExactly(1L);
    }

    @Test
    void anItemIsNeverUsedTwiceAndReferencesAreNormalised() {
        MatchResult result = engine.match(
                List.of(item(1, " inv-7 ", "10.00", NOV_6)),
                List.of(item(101, "INV-7", "10.00", NOV_6), item(102, "INV-7", "10.00", NOV_6)));

        assertThat(result.matches()).singleElement()
                .extracting(ProposedMatch::externalIds).isEqualTo(List.of(101L));
        assertThat(result.unmatchedExternal()).extracting(Candidate::id).containsExactly(102L);
    }

    private static Candidate item(long id, String reference, String amount, LocalDate date) {
        return new Candidate(id, reference, Money.gbp(amount), date);
    }
}
```

Each test is one rule, named in plain English. `theExactPassRunsFirstSoTheBestCandidateWins` is the "order matters" example from Concepts: try reordering the passes in the engine and watch it fail. Run the class: seven green ticks.

### 10. Runs and matches

Create the package `com.example.recon.run`. `run/ReconMatch.java`:

```java
package com.example.recon.run;

import com.example.recon.matching.MatchRule;
import com.example.recon.money.Money;
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

/** A persisted match: which rule decided it, who (or what) made it, and any difference left over. */
@Entity
@Table(name = "recon_match")
public class ReconMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_code", nullable = false, length = 30)
    private String accountCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_rule", nullable = false, length = 30)
    private MatchRule rule;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal difference;

    @Column(name = "run_id")
    private Long runId;

    @Column(name = "matched_by", nullable = false, length = 50)
    private String matchedBy;

    @Column(name = "matched_at", nullable = false)
    private Instant matchedAt;

    protected ReconMatch() {
        // for JPA
    }

    public ReconMatch(String accountCode, MatchRule rule, Money difference, Long runId, String matchedBy) {
        this.accountCode = accountCode;
        this.rule = rule;
        this.difference = difference.amount();
        this.runId = runId;
        this.matchedBy = matchedBy;
        this.matchedAt = Instant.now();
    }

    public Money getDifference() {
        return new Money(difference, Money.GBP);
    }

    // + getters for id, accountCode, rule, runId, matchedBy and matchedAt
}
```

`run/ReconRun.java`:

```java
package com.example.recon.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "recon_run")
public class ReconRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_code", nullable = false, length = 30)
    private String accountCode;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "matched_count", nullable = false)
    private int matchedCount;

    @Column(name = "open_internal", nullable = false)
    private int openInternal;

    @Column(name = "open_external", nullable = false)
    private int openExternal;

    protected ReconRun() {
        // for JPA
    }

    public ReconRun(String accountCode, LocalDate businessDate) {
        this.accountCode = accountCode;
        this.businessDate = businessDate;
        this.startedAt = Instant.now();
    }

    public void finished(int matchedCount, int openInternal, int openExternal) {
        this.matchedCount = matchedCount;
        this.openInternal = openInternal;
        this.openExternal = openExternal;
    }

    // + getters for every field
}
```

The repositories: `run/ReconMatchRepository.java` with one query, and an empty `run/ReconRunRepository.java` (`JpaRepository<ReconRun, Long>`):

```java
package com.example.recon.run;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconMatchRepository extends JpaRepository<ReconMatch, Long> {

    List<ReconMatch> findByRunIdOrderById(Long runId);
}
```

Add two queries to `ReconItemRepository` (import `Query` and `LocalDate`):

```java
    List<ReconItem> findByMatchIdOrderById(Long matchId);

    /** Everything still unexplained on or before the business date, oldest first. */
    @Query("""
            select i from ReconItem i
            where i.accountCode = :accountCode and i.side = :side
              and i.status = com.example.recon.item.ItemStatus.OPEN and i.bookingDate <= :businessDate
            order by i.bookingDate, i.id
            """)
    List<ReconItem> findOpen(String accountCode, Side side, LocalDate businessDate);
```

`bookingDate <= :businessDate`, not `=`. A run for 6 November also looks at anything **still open from earlier days**. That's how timing differences resolve themselves: Monday's late payment is still open on Tuesday, and Tuesday's statement line matches it.

### 11. The reconciliation service

`run/ReconciliationService.java`:

```java
package com.example.recon.run;

import com.example.recon.account.Account;
import com.example.recon.account.AccountRepository;
import com.example.recon.account.UnknownAccountException;
import com.example.recon.item.ReconItem;
import com.example.recon.item.ReconItemRepository;
import com.example.recon.item.Side;
import com.example.recon.matching.Candidate;
import com.example.recon.matching.MatchResult;
import com.example.recon.matching.MatchingEngine;
import com.example.recon.matching.ProposedMatch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class ReconciliationService {

    static final String SYSTEM_USER = "system";

    private final AccountRepository accounts;
    private final ReconItemRepository items;
    private final ReconMatchRepository matches;
    private final ReconRunRepository runs;
    private final MatchingEngine engine;

    public ReconciliationService(AccountRepository accounts, ReconItemRepository items,
                                 ReconMatchRepository matches, ReconRunRepository runs, MatchingEngine engine) {
        this.accounts = accounts;
        this.items = items;
        this.matches = matches;
        this.runs = runs;
        this.engine = engine;
    }

    /** Matches everything still open on or before the business date. */
    @Transactional
    public ReconRun run(String accountCode, LocalDate businessDate) {
        Account account = accounts.findByCode(accountCode)
                .filter(Account::isReconciled)
                .orElseThrow(() -> new UnknownAccountException("No reconciled account " + accountCode));

        List<ReconItem> internal = items.findOpen(account.getCode(), Side.INTERNAL, businessDate);
        List<ReconItem> external = items.findOpen(account.getCode(), Side.EXTERNAL, businessDate);
        MatchResult result = engine.match(candidates(internal), candidates(external));

        ReconRun run = runs.save(new ReconRun(account.getCode(), businessDate));
        Map<Long, ReconItem> byId = Stream.concat(internal.stream(), external.stream())
                .collect(Collectors.toMap(ReconItem::getId, Function.identity()));
        for (ProposedMatch proposed : result.matches()) {
            ReconMatch match = matches.save(new ReconMatch(account.getCode(), proposed.rule(),
                    proposed.difference(), run.getId(), SYSTEM_USER));
            Stream.concat(proposed.internalIds().stream(), proposed.externalIds().stream())
                    .forEach(id -> byId.get(id).matchTo(match.getId()));
        }
        run.finished(result.matches().size(), result.unmatchedInternal().size(), result.unmatchedExternal().size());
        return run;
    }

    @Transactional(readOnly = true)
    public ReconRun get(Long id) {
        return runs.findById(id).orElseThrow(() -> new RunNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<ReconMatch> matchesFor(Long runId) {
        return matches.findByRunIdOrderById(runId);
    }

    @Transactional(readOnly = true)
    public List<ReconItem> itemsIn(Long matchId) {
        return items.findByMatchIdOrderById(matchId);
    }

    private static List<Candidate> candidates(List<ReconItem> items) {
        return items.stream()
                .map(i -> new Candidate(i.getId(), i.getReference(), i.getAmount(), i.getBookingDate()))
                .toList();
    }
}
```

This is the **load → match → persist** shape: everything with side effects is here, and everything with rules is in the engine. The engine returns ids; the service maps them back to the loaded entities with `byId`, and `matchTo` updates each one through dirty checking.

Create `run/RunNotFoundException.java` with the message `"No reconciliation run with id " + id`, and map it to a `404` titled `"Reconciliation run not found"` in `ApiExceptionHandler`, just like `ImportNotFoundException`.

### 12. The API

`run/ReconciliationController.java`:

```java
package com.example.recon.run;

import com.example.recon.item.ReconItem;
import com.example.recon.item.Side;
import com.example.recon.matching.MatchRule;
import com.example.recon.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/reconciliations")
public class ReconciliationController {

    private final ReconciliationService reconciliation;

    public ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @PostMapping
    public ResponseEntity<RunResponse> run(@Valid @RequestBody RunRequest request) {
        ReconRun run = reconciliation.run(request.accountCode(), request.businessDate());
        return ResponseEntity.created(URI.create("/reconciliations/" + run.getId())).body(response(run));
    }

    @GetMapping("/{id}")
    public RunResponse get(@PathVariable Long id) {
        return response(reconciliation.get(id));
    }

    private RunResponse response(ReconRun run) {
        List<MatchResponse> matches = reconciliation.matchesFor(run.getId()).stream()
                .map(m -> new MatchResponse(m.getId(), m.getRule(), m.getDifference(),
                        reconciliation.itemsIn(m.getId()).stream().map(ItemResponse::from).toList()))
                .toList();
        return new RunResponse(run.getId(), run.getAccountCode(), run.getBusinessDate(),
                run.getMatchedCount(), run.getOpenInternal(), run.getOpenExternal(), matches);
    }

    public record RunRequest(@NotBlank String accountCode, @NotNull LocalDate businessDate) {
    }

    public record RunResponse(Long id, String accountCode, LocalDate businessDate, int matched,
                              int openInternal, int openExternal, List<MatchResponse> matches) {
    }

    public record MatchResponse(Long id, MatchRule rule, Money difference, List<ItemResponse> items) {
    }

    public record ItemResponse(Long id, Side side, String reference, Money amount, LocalDate bookingDate) {

        public static ItemResponse from(ReconItem item) {
            return new ItemResponse(item.getId(), item.getSide(), item.getReference(),
                    item.getAmount(), item.getBookingDate());
        }
    }
}
```

`response` runs one query per match to fetch its items. That's fine for a day's run on one account; for big runs you'd fetch all the run's items in one query and group them in memory.

### 13. Try it

Delete `data/`, restart, and import the statement from guide 01. Then add the bank's own postings to `requests.http`:

```http
### Our books: £1,250 received for invoice 1001
POST http://localhost:8080/ledger/entries
Content-Type: application/json

{"debitAccount": "NOSTRO_GBP", "creditAccount": "CUSTOMER_FUNDS", "amount": "1250.00",
 "reference": "INV-1001", "bookingDate": "2026-11-06", "description": "Invoice 1001 Acme Ltd"}

### Our books: £400 supplier payment, booked the day before the other bank booked it
POST http://localhost:8080/ledger/entries
Content-Type: application/json

{"debitAccount": "CUSTOMER_FUNDS", "creditAccount": "NOSTRO_GBP", "amount": "400.00",
 "reference": "PAY-7731", "bookingDate": "2026-11-05", "description": "Supplier payment 7731"}

### Our books: £1,000 expected for invoice 1002
POST http://localhost:8080/ledger/entries
Content-Type: application/json

{"debitAccount": "NOSTRO_GBP", "creditAccount": "CUSTOMER_FUNDS", "amount": "1000.00",
 "reference": "INV-1002", "bookingDate": "2026-11-06", "description": "Invoice 1002"}

### Our books: £300 sent late in the day
POST http://localhost:8080/ledger/entries
Content-Type: application/json

{"debitAccount": "CUSTOMER_FUNDS", "creditAccount": "NOSTRO_GBP", "amount": "300.00",
 "reference": "PAY-7740", "bookingDate": "2026-11-06", "description": "Supplier payment 7740"}

### Our view of the nostro balance
GET http://localhost:8080/accounts/NOSTRO_GBP

### Reconcile the nostro for 6 November
POST http://localhost:8080/reconciliations
Content-Type: application/json

{"accountCode": "NOSTRO_GBP", "businessDate": "2026-11-06"}
```

Free-edition users can send each one from Swagger UI, or with `curl localhost:8080/ledger/entries -H 'Content-Type: application/json' -d '{…}'`. Note the debit and credit sides: money **into** the nostro is a debit to `NOSTRO_GBP`; money **out** is a credit.

The balance comes back as `{"accountCode":"NOSTRO_GBP","balance":{"amount":"1550.00","currency":"GBP"}}`. (It isn't the statement's £11,899.50, because this toy ledger has no opening balance. Comparing balances is the subject of a sidebar below.) The run returns (trimmed):

```json
{
  "id": 1,
  "accountCode": "NOSTRO_GBP",
  "businessDate": "2026-11-06",
  "matched": 3,
  "openInternal": 1,
  "openExternal": 2,
  "matches": [
    { "id": 1, "rule": "EXACT", "difference": { "amount": "0.00", "currency": "GBP" },
      "items": [
        { "id": 1, "side": "EXTERNAL", "reference": "INV-1001", "amount": { "amount": "1250.00" }, "bookingDate": "2026-11-06" },
        { "id": 6, "side": "INTERNAL", "reference": "INV-1001", "amount": { "amount": "1250.00" }, "bookingDate": "2026-11-06" } ] },
    { "id": 2, "rule": "DATE_WINDOW", "difference": { "amount": "0.00", "currency": "GBP" },
      "items": [
        { "id": 2, "side": "EXTERNAL", "reference": "PAY-7731", "amount": { "amount": "-400.00" }, "bookingDate": "2026-11-06" },
        { "id": 7, "side": "INTERNAL", "reference": "PAY-7731", "amount": { "amount": "-400.00" }, "bookingDate": "2026-11-05" } ] },
    { "id": 3, "rule": "AMOUNT_TOLERANCE", "difference": { "amount": "-0.50", "currency": "GBP" },
      "items": [
        { "id": 3, "side": "EXTERNAL", "reference": "INV-1002", "amount": { "amount": "999.50" }, "bookingDate": "2026-11-06" },
        { "id": 8, "side": "INTERNAL", "reference": "INV-1002", "amount": { "amount": "1000.00" }, "bookingDate": "2026-11-06" } ] }
  ]
}
```

Three of the five statement lines matched, each by a different rule. What's left is the story of guide 04: on the statement, the £25 fee and the £75 from J SMITH; in the ledger, the £300 payment the correspondent hasn't booked yet.

> **Sidebar: balance reconciliation.** This guide reconciles **transactions**. Banks also reconcile **balances**: does the statement's closing balance equal the ledger balance, once the open items are taken into account? Statement balance − ledger balance should equal the sum of the open breaks. If it doesn't, something is missing from *both* sides' item lists, which transaction matching alone can't see.

### 14. Run tests

`src/test/java/com/example/recon/run/ReconciliationApiTest.java` sets up the same day as `requests.http`, on a test account of its own:

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@ReconIntegrationTest
class ReconciliationApiTest {

    static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);
    static final LocalDate NOV_6 = LocalDate.of(2026, 11, 6);

    @Autowired MockMvcTester mvc;
    @Autowired AccountRepository accounts;
    @Autowired ReconItemRepository items;
    @Autowired LedgerService ledger;

    String nostro;

    @BeforeEach
    void bookTheDay() {
        nostro = TestAccounts.newNostro(accounts).getCode();
        // Our books
        ledger.post(nostro, "CUSTOMER_FUNDS", Money.gbp("1250.00"), "INV-1001", NOV_6, "Invoice 1001");
        ledger.post("CUSTOMER_FUNDS", nostro, Money.gbp("400.00"), "PAY-7731", NOV_5, "Supplier payment");
        ledger.post(nostro, "CUSTOMER_FUNDS", Money.gbp("1000.00"), "INV-1002", NOV_6, "Invoice 1002");
        ledger.post("CUSTOMER_FUNDS", nostro, Money.gbp("300.00"), "PAY-7740", NOV_6, "Sent late in the day");
        // The other bank's statement
        external("INV-1001", "1250.00", NOV_6);
        external("PAY-7731", "-400.00", NOV_6);
        external("INV-1002", "999.50", NOV_6);
        external("CB-FEE-20261106", "-25.00", NOV_6);
    }

    @Test
    void matchesWhatItCanAndLeavesTheRestOpen() {
        MvcTestResult response = run(nostro, "2026-11-06");

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).bodyJson().extractingPath("$.matched").isEqualTo(3);
        assertThat(response).bodyJson().extractingPath("$.openInternal").isEqualTo(1);
        assertThat(response).bodyJson().extractingPath("$.openExternal").isEqualTo(1);
        assertThat(response).bodyJson().extractingPath("$.matches[*].rule")
                .asArray().containsExactly("EXACT", "DATE_WINDOW", "AMOUNT_TOLERANCE");
        assertThat(response).bodyJson().extractingPath("$.matches[2].difference.amount").isEqualTo("-0.50");
    }

    @Test
    void runningAgainFindsNothingNewToMatch() {
        run(nostro, "2026-11-06");

        MvcTestResult again = run(nostro, "2026-11-06");

        assertThat(again).bodyJson().extractingPath("$.matched").isEqualTo(0);
        assertThat(again).bodyJson().extractingPath("$.openInternal").isEqualTo(1);
        assertThat(items.findAll().stream()
                .filter(i -> i.getAccountCode().equals(nostro) && i.getStatus() == ItemStatus.MATCHED))
                .hasSize(6);
    }

    @Test
    void onlyLooksAtItemsUpToTheBusinessDate() {
        MvcTestResult response = run(nostro, "2026-11-05");

        assertThat(response).bodyJson().extractingPath("$.matched").isEqualTo(0);
        assertThat(response).bodyJson().extractingPath("$.openInternal").isEqualTo(1); // just PAY-7731
        assertThat(response).bodyJson().extractingPath("$.openExternal").isEqualTo(0);
    }

    @Test
    void refusesAnAccountThatIsNotReconciled() {
        MvcTestResult response = run("CUSTOMER_FUNDS", "2026-11-06");

        assertThat(response).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Unknown account");
    }

    private void external(String reference, String amount, LocalDate date) {
        items.save(ReconItem.external(nostro, null, reference, Money.gbp(amount), date, date, "From statement"));
    }

    private MvcTestResult run(String account, String date) {
        return mvc.post().uri("/reconciliations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accountCode": "%s", "businessDate": "%s"}
                        """.formatted(account, date))
                .exchange();
    }
}
```

The tests save external items directly instead of uploading XML. The import path is already tested; this class is about matching, so it arranges its data the quickest way. `onlyLooksAtItemsUpToTheBusinessDate` proves a run for 5 November can't see items from the 6th.

`runningAgainFindsNothingNewToMatch` passes, but look at what it really does: the second run creates a **second run record** for the same account and date. Keep that in mind for guide 05.

---

## Your turn

### Exercise 1: many-to-one

A card scheme pays the bank one lump sum for a day's settlement (`SETTLE-1106`, £400.00), while the bank's books recorded it as three postings with the same reference (£100.00, £250.25, £49.75). Add a `MANY_TO_ONE` rule, run after the three one-to-one passes, that matches **two or more** open internal items with an external item's reference, inside the date window, when they **add up exactly** to its amount. Test it in `MatchingEngineTest`.

<details>
<summary>Hint</summary>

For each open external item, filter the open internal items with `sameReference` and `withinDateWindow`. If there are at least two, add them up with `reduce(Money.zero(Money.GBP), Money::plus)`. On an exact total, record the match with all their ids, then `openInternal.removeAll(group)` and `externals.remove()`.

</details>

<details>
<summary>Solution</summary>

Add `MANY_TO_ONE` to `MatchRule`:

```java
    /** Several internal items with one reference add up to one external item. */
    MANY_TO_ONE
```

In `MatchingEngine.match`, after the tolerance pass:

```java
        manyToOne(openInternal, openExternal, matches);
```

and the method:

```java
    /** Several internal items with the external item's reference, inside the window, adding up exactly. */
    private void manyToOne(List<Candidate> openInternal, List<Candidate> openExternal, List<ProposedMatch> matches) {
        Iterator<Candidate> externals = openExternal.iterator();
        while (externals.hasNext()) {
            Candidate e = externals.next();
            List<Candidate> group = openInternal.stream()
                    .filter(i -> sameReference(i, e) && withinDateWindow(i, e))
                    .toList();
            if (group.size() < 2) {
                continue;
            }
            Money total = group.stream().map(Candidate::amount).reduce(Money.zero(Money.GBP), Money::plus);
            if (total.equals(e.amount())) {
                matches.add(new ProposedMatch(MatchRule.MANY_TO_ONE,
                        group.stream().map(Candidate::id).toList(), List.of(e.id()), Money.zero(Money.GBP)));
                openInternal.removeAll(group);
                externals.remove();
            }
        }
    }
```

The test:

```java
    @Test
    void severalPostingsCanAddUpToOneStatementLine() {
        MatchResult result = engine.match(
                List.of(item(1, "SETTLE-1106", "100.00", NOV_6),
                        item(2, "SETTLE-1106", "250.25", NOV_6),
                        item(3, "SETTLE-1106", "49.75", NOV_6)),
                List.of(item(101, "SETTLE-1106", "400.00", NOV_6)));

        assertThat(result.matches()).containsExactly(new ProposedMatch(
                MatchRule.MANY_TO_ONE, List.of(1L, 2L, 3L), List.of(101L), Money.gbp("0.00")));
    }
```

It's deliberately "all of them or nothing": it doesn't search for a *subset* that adds up. Subset-sum matching is computationally expensive and, worse, can find coincidental combinations that aren't really the same money. Real engines that do it limit the group size and the search, and treat the result as a suggestion for a person to confirm.

</details>

### Exercise 2: a 40p credit isn't a 40p debit

`withinTolerance` checks `sameSign`. Write a test that proves it: an internal `+0.40` and an external `-0.40` with the same reference and date must **not** match, even though they differ by only 80p, inside the £1.00 tolerance. Then delete the `sameSign &&` and watch the test fail.

<details>
<summary>Solution</summary>

```java
    @Test
    void toleranceNeverMatchesMoneyGoingTheOtherWay() {
        MatchResult result = engine.match(
                List.of(item(1, "INV-5", "0.40", NOV_6)),
                List.of(item(101, "INV-5", "-0.40", NOV_6)));

        assertThat(result.matches()).isEmpty();
    }
```

Without the sign check, the engine pairs money arriving with money leaving, and reports the result as "matched within tolerance". Both breaks would silently disappear. Tests like this one, which pin down what a rule must **not** match, matter as much as the happy paths.

</details>

---

## Checkpoint

```bash
./mvnw clean test
```

```
[INFO] Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
```

(25 from guide 02, plus 9 in `MatchingEngineTest`, 3 in `LedgerApiTest` and 4 in `ReconciliationApiTest`.)

New files since guide 02:

```
src/main/java/com/example/recon/
├── ledger/    Direction, JournalEntry, JournalEntryRepository, LedgerController, LedgerService,
│              Posting, PostingRepository
├── matching/  Candidate, MatchResult, MatchRule, MatchingConfig, MatchingEngine, MatchingRules,
│              ProposedMatch
└── run/       ReconMatch, ReconMatchRepository, ReconRun, ReconRunRepository, ReconciliationController,
               ReconciliationService, RunNotFoundException
src/main/resources/db/migration/V3__ledger_matches_and_runs.sql
src/test/java/com/example/recon/
├── TestAccounts.java
├── ledger/LedgerApiTest.java
├── matching/MatchingEngineTest.java
└── run/ReconciliationApiTest.java
```

Modified: `Account`, `ReconItem`, `ReconItemRepository`, `ApiExceptionHandler`, `application.properties`, `requests.http`.

---

## Common errors

**`Failed to bind properties under 'recon.matching' to com.example.recon.matching.MatchingRules: Reason: java.lang.IllegalArgumentException: amountTolerance must be zero or more`**
The application refused to start because a tolerance is invalid, which is exactly what the compact constructor is for. Check `application.properties` (and any `--recon.matching…` command-line arguments or environment variables).

**`Parameter 0 of method matchingEngine in com.example.recon.matching.MatchingConfig required a bean of type 'com.example.recon.matching.MatchingRules' that could not be found.`**
`@EnableConfigurationProperties(MatchingRules.class)` is missing from `MatchingConfig`. A `@ConfigurationProperties` record isn't a bean until something enables it (or `@ConfigurationPropertiesScan` is on the application class).

**`400` with `"errors": {"amount": "numeric value out of bounds (<15 digits>.<2 digits> expected)"}`**
The amount has more than two decimal places. That's `@Digits` protecting `Money` from ever seeing `1.001`.

**`ConcurrentModificationException` in the engine**
You removed from a list with `list.remove(…)` while looping over it with a for-each. Use the iterator's own `remove()`, as `pass` does.

**A test's counts are off by a few, and only when the whole suite runs**
It's using a shared account (`NOSTRO_GBP`), and other test classes have left items on it. Use `TestAccounts.newNostro(accounts)`.

---

## Recap

- Reconciliation matches the bank's **internal** items (ledger postings) against **external** items (statement and report lines). Feeding postings to reconciliation **in the same transaction** that posts them means none are ever missed.
- Match in **passes, most certain first**: exact, then rule-based passes for **timing differences** (date window) and **small amount differences** (tolerance), each pass seeing only what's left. Order changes the answer.
- Tolerances are **controlled configuration**: too strict means manual work, too loose hides errors. Validate them at start-up.
- Every match records **the rule, who made it, and any difference**, so it can be explained later.
- Keep the engine **pure**: lists in, result out, no database or framework. The service does **load → match → persist**.
- A run looks at everything **still open up to** the business date, so yesterday's timing differences can match today.

### Interview angle

<details>
<summary>"How would you design a reconciliation matching engine?"</summary>

Normalise both sides into a common item shape (reference, signed amount, date, side). Match in ordered passes from strictest to loosest (exact on reference, amount and date; then reference and amount within a date window; then reference with an amount tolerance; then one-to-many or many-to-one aggregation), each pass working only on what's left, so the best explanation wins. Record the rule, the actor and any difference on every match. Keep the engine pure and deterministic so it's easy to unit-test and to explain. Configure tolerances per account under change control, and send everything unmatched to an exception queue for people. Mention performance (group by reference before comparing) and that a too-loose rule is a risk, because it hides genuine breaks.

</details>

<details>
<summary>"What's the difference between a timing difference and a break that needs investigating?"</summary>

A timing difference is an item that both sides have, or will have, but on different dates: a payment booked late on Monday that the other bank books on Tuesday. It clears itself in a later run, and a date-window rule may match it straight away. A break that needs investigating is one that won't clear on its own: a missing or duplicated posting, a wrong amount, an unexpected fee or an unidentified receipt. In practice you tell them apart with matching rules and **ageing**: a one-day-old break is probably timing; a ten-day-old one almost certainly isn't.

</details>

<details>
<summary>"Why keep business rules out of the persistence layer?"</summary>

Because pure functions over plain data are easy to test exhaustively, fast, and deterministic. You can test every rule and edge case without a database or Spring context, replay a production scenario from a list of items, and change persistence without touching the rules. The service layer handles transactions and loading/saving; the domain logic says what should happen.

</details>

### Further reading

- [Spring Boot reference: Type-safe Configuration Properties](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties), including constructor binding with records
- [Spring Data JPA reference: Using `@Query`](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.query-methods.at-query)
- [PostgreSQL: SQL key words](https://www.postgresql.org/docs/current/sql-keywords-appendix.html): which words are reserved where, worth a look before naming columns

**Next:** [04: Breaks, suspense and maker-checker](04-breaks-suspense-and-maker-checker.md). What the bank does with everything the engine couldn't match, and why no one person is allowed to do it alone.
