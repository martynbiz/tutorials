# 02: Authorising a Card Payment

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- What's in an **authorisation request**, and what the issuer sends back: approve or decline, with a **response code**
- What a **hold** really is in the database, and how it changes the available balance without touching the ledger balance
- **Spending limits**: a per-transaction limit and a daily limit, checked on every authorisation
- Why authorisation has to be **fast**, what happens when the issuer is too slow (**timeouts** and **stand-in processing**), and how that shapes the code
- How to write the decision as a **pure function**, then test every rule in milliseconds with a parameterised test and a **test data builder**
- Why a **decline is not an HTTP error**
- How to prove a code path stays short, with **Hibernate statistics** in a test

By the end, `POST /authorisations` answers like an issuer: approve with `00` and place a hold, or decline with `51`, `54`, `61` and friends.

---

## Concepts

### How it works in a real bank

When you tap your card, the scheme delivers an **authorisation request** to your bank. Real messages follow ISO 8583 (an older binary message standard used across the card industry) or a scheme's own variant of it, and carry dozens of fields. The ones that matter most for a decision are:

| Field | Example | This tutorial |
|---|---|---|
| The card | PAN, or a token standing in for it | `cardToken` |
| Amount and currency | £4.50, GBP | `amount` (GBP only) |
| Merchant name and location | "Corner Cafe, London" | `merchantName` |
| **Merchant category code (MCC)** | `5814` (fast food restaurants) | `merchantCategoryCode` |
| How the card was used | chip and PIN, contactless, online | not modelled |
| Security results | PIN checked, CVV2 matched, 3-D Secure | not modelled |

The **MCC** is a four-digit code (standardised in ISO 18245) describing what kind of business the merchant is: `5542` is automated fuel dispensers, `5812` restaurants, `7011` hotels. Issuers use it for rules ("no gambling transactions" blocks in UK banking apps work on MCCs), for fraud scoring, and, in guide 05, for how long a hold should last.

The issuer runs its checks and answers with a two-digit **response code**. There are many. This tutorial uses six well-known ones, **simplified**:

| Code | Meaning | When we send it |
|---|---|---|
| `00` | Approved | Everything checks out |
| `05` | Do not honour | The card is blocked (a generic "no") |
| `14` | Invalid card number | No card with that token |
| `51` | Insufficient funds | Amount is more than the available balance |
| `54` | Expired card | Past the end of the card's expiry month |
| `61` | Exceeds amount limit | Over the per-transaction or daily limit |

> **Simplification:** real issuers distinguish many more cases (lost card, stolen card, suspected fraud, incorrect PIN, and so on), and each scheme documents its own codes and how they must be used. The six above are enough to learn the pattern.

If the answer is **approve**, the issuer places a **[hold](../glossary.md#hold)**: it reserves the amount so the customer can't spend it again, without posting anything. In this tutorial a hold isn't a separate table. Every authorisation request is recorded in an `authorisation` table, approved or declined, and **an approved authorisation with status `PENDING` is the hold**. The [available balance](../glossary.md#available-balance) subtracts the total of those rows.

### Spending limits

As well as having enough money, a payment must fit within the card's **limits**. Banks set them to cap the damage from fraud, and many banking apps now let customers lower their own. You'll implement two:

- **Per-transaction limit**: no single authorisation above, say, £500.
- **Daily limit**: no more than, say, £1,000 approved on this card per day. "Today" means the UK calendar day, because that's what a UK customer understands by "today".

> **Simplification:** the £500 and £1,000 defaults are made up for this tutorial. Real issuers set limits per product and per channel (contactless, online, cash machine), and a contactless payment above the contactless limit needs the PIN. Those rules belong to the issuer and the schemes, not to this code.

### The clock is ticking: timeouts and stand-in

A customer at a till won't wait. The scheme gives the issuer a short window to answer, and if no answer arrives in time, the payment doesn't simply fail. Depending on the scheme and the issuer's set-up, the scheme can make the decision itself, using limits the issuer gave it in advance. This is **stand-in processing** (Visa calls its service *STIP*). The issuer is told about the stand-in decision afterwards and has to record it, holds included.

That has consequences for the code:

1. **The authorisation path must be fast and predictable.** One short database transaction, a handful of queries, no calls to slow external systems in the middle of it.
2. **A slow answer can be worse than no answer.** If the scheme has already stood in and approved, and your late decision was a decline, the two systems now disagree. Real issuers set their own internal deadlines below the scheme's and log anything that runs late.

> **Simplification:** this tutorial doesn't implement stand-in, advices or timeouts. Its job is to keep the path short enough that they'd rarely be needed, and to prove it with a test.

### Shape of the code: gather, decide, record

A useful way to structure any fast decision path:

1. **Gather** the facts: the card, the account, the total of the holds, today's spending. A fixed, small number of queries.
2. **Decide**, using a **pure function**: facts in, decision out. No database, no clock, no Spring. It's easy to read, and every rule can be unit-tested in milliseconds.
3. **Record** the answer: one insert.

```
 gather (4 reads) ──▶ AuthorisationRules.decide(facts) ──▶ record (1 insert)
                        pure: no I/O, no time, no Spring
```

### A decline is not an error

When the issuer declines, the system worked perfectly. It received a valid request and gave a valid answer. So `POST /authorisations` returns **`200 OK` for approvals and declines alike**, with the decision in the body. HTTP error codes are kept for requests the issuer couldn't process at all, such as malformed JSON (`400`).

Real authorisation messages aren't HTTP, but the same principle holds: the response code is the business answer, and transport errors are something else.

---

## Steps

### 1. The migration

Create `src/main/resources/db/migration/V2__create_authorisation.sql`:

```sql
-- Spending limits, set per card
ALTER TABLE card ADD COLUMN per_transaction_limit NUMERIC(19, 4) DEFAULT 500 NOT NULL;
ALTER TABLE card ADD COLUMN daily_limit NUMERIC(19, 4) DEFAULT 1000 NOT NULL;

-- Every authorisation request we answer, approved or declined.
-- An approved one with status PENDING is a hold.
CREATE TABLE authorisation (
    id                     BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    card_id                BIGINT                   NOT NULL REFERENCES card (id),
    account_id             BIGINT                   NOT NULL REFERENCES account (id),
    amount                 NUMERIC(19, 4)           NOT NULL,
    held_amount            NUMERIC(19, 4)           NOT NULL,
    currency               VARCHAR(3)               NOT NULL,
    merchant_name          VARCHAR(100)             NOT NULL,
    merchant_category_code VARCHAR(4)               NOT NULL,
    response_code          VARCHAR(2)               NOT NULL,
    status                 VARCHAR(10)              NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at             TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_authorisation_amount CHECK (amount > 0),
    CONSTRAINT ck_authorisation_held_amount CHECK (held_amount >= 0),
    CONSTRAINT ck_authorisation_status
        CHECK (status IN ('DECLINED', 'PENDING', 'CLEARED', 'REVERSED', 'EXPIRED'))
);

CREATE INDEX ix_authorisation_account_status ON authorisation (account_id, status);
CREATE INDEX ix_authorisation_card_created ON authorisation (card_id, created_at);
```

The design decisions:

- **`DEFAULT … NOT NULL`** on the new card columns gives existing cards (including the one in your `data/` database) a value, so the `NOT NULL` constraint can be added in one step.
- **`account_id` is stored on the authorisation**, even though the card already knows its account. Copying it here (denormalising) means "total of this account's holds" is a query on one table, using the `(account_id, status)` index, with no join. Authorisation is the hot path, so that's worth it.
- **`amount` and `held_amount` are separate.** `amount` is what was authorised, and never changes. `held_amount` is what's reserved *now*: the same as `amount` for a fresh approval, zero for a decline, and lower after a partial reversal (guide 03).
- **`response_code` stores the two-digit code** (`"51"`), exactly as it would appear in a scheme message or a log.
- **All five statuses are in the `CHECK` constraint now**, although `CLEARED`, `REVERSED` and `EXPIRED` arrive in later guides. Changing a `CHECK` constraint later means dropping and re-adding it, and the syntax for that differs between databases.
- **`ix_authorisation_card_created`** serves the daily-limit query: this card's authorisations since midnight.

### 2. Limits on the card

In `card/Card.java`, map the two new columns after `issuedAt`:

```java
    @Column(name = "per_transaction_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal perTransactionLimit;

    @Column(name = "daily_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal dailyLimit;
```

Set the defaults at the end of the private constructor:

```java
        this.perTransactionLimit = new BigDecimal("500.00");
        this.dailyLimit = new BigDecimal("1000.00");
```

And add two getters (import `com.example.cards.money.Money` and `java.math.BigDecimal`):

```java
    // Cards are GBP-only in this tutorial, like their accounts
    public Money getPerTransactionLimit() {
        return new Money(perTransactionLimit, Money.GBP);
    }

    public Money getDailyLimit() {
        return new Money(dailyLimit, Money.GBP);
    }
```

Why `Money.GBP` and not the account's currency? Because reading `card.getAccount().getCurrency()` would make Hibernate **load the account** through the lazy proxy, and in guide 03 you'll need to be in control of exactly when and how the account is loaded. A card's limits are in the currency of its account, which in this tutorial is always GBP.

### 3. Response codes

Create `authorisation/ResponseCode.java`:

```java
package com.example.cards.authorisation;

/**
 * A simplified subset of the two-digit response codes used in card
 * authorisation messages (ISO 8583 style). Real schemes define many more.
 */
public enum ResponseCode {
    APPROVED("00", "Approved"),
    DO_NOT_HONOUR("05", "Do not honour"),
    INVALID_CARD("14", "Invalid card number"),
    INSUFFICIENT_FUNDS("51", "Insufficient funds"),
    EXPIRED_CARD("54", "Expired card"),
    EXCEEDS_AMOUNT_LIMIT("61", "Exceeds amount limit");

    private final String code;
    private final String meaning;

    ResponseCode(String code, String meaning) {
        this.code = code;
        this.meaning = meaning;
    }

    public String code() {
        return code;
    }

    public String meaning() {
        return meaning;
    }

    public boolean isApproved() {
        return this == APPROVED;
    }

    public static ResponseCode fromCode(String code) {
        for (ResponseCode value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown response code " + code);
    }
}
```

The database column holds `"51"`, but `@Enumerated(EnumType.STRING)` would store `"INSUFFICIENT_FUNDS"`. A JPA **`AttributeConverter`** translates between the two. Create `authorisation/ResponseCodeConverter.java`:

```java
package com.example.cards.authorisation;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores a ResponseCode as its two-digit code ("51"), not its Java name. */
@Converter(autoApply = true)
public class ResponseCodeConverter implements AttributeConverter<ResponseCode, String> {

    @Override
    public String convertToDatabaseColumn(ResponseCode responseCode) {
        return responseCode == null ? null : responseCode.code();
    }

    @Override
    public ResponseCode convertToEntityAttribute(String code) {
        return code == null ? null : ResponseCode.fromCode(code);
    }
}
```

`autoApply = true` means every entity field of type `ResponseCode` uses this converter automatically, without an annotation on the field.

### 4. The lifecycle of an authorisation

Create `authorisation/AuthorisationStatus.java`:

```java
package com.example.cards.authorisation;

/**
 * DECLINED: never held anything.
 * PENDING:  approved, and the money is held. This is the "hold".
 * CLEARED:  the merchant's presentment arrived and was posted (guide 04).
 * REVERSED: the merchant cancelled the authorisation (guide 03).
 * EXPIRED:  no presentment arrived in time, so the hold was released (guide 05).
 */
public enum AuthorisationStatus {
    DECLINED,
    PENDING,
    CLEARED,
    REVERSED,
    EXPIRED
}
```

The whole tutorial in one picture:

```
                ┌──────────▶ CLEARED   (guide 04: presentment arrives, money is posted)
   request ─▶ PENDING ─────▶ REVERSED  (guide 03: merchant cancels)
      │         └──────────▶ EXPIRED   (guide 05: nothing arrives in time)
      └─────▶ DECLINED                  (never held anything)
```

### 5. The decision, as a pure function

The decision needs eight facts. Put them in a record, `authorisation/AuthorisationFacts.java`:

```java
package com.example.cards.authorisation;

import com.example.cards.card.CardStatus;
import com.example.cards.money.Money;

import java.time.LocalDate;
import java.time.YearMonth;

/** Everything the decision needs, gathered up front. No entities, no database. */
public record AuthorisationFacts(
        CardStatus cardStatus,
        YearMonth cardExpiry,
        LocalDate today,
        Money amount,
        Money perTransactionLimit,
        Money dailyLimit,
        Money spentToday,
        Money availableBalance) {
}
```

Notice `today` is a **parameter**. The rules never ask what day it is. Whoever calls them decides, so a test can say "pretend it's 1 December 2026".

The outcome, `authorisation/Decision.java`:

```java
package com.example.cards.authorisation;

public record Decision(ResponseCode responseCode, String reason) {

    public static Decision approve() {
        return new Decision(ResponseCode.APPROVED, "Approved");
    }

    public static Decision decline(ResponseCode responseCode, String reason) {
        return new Decision(responseCode, reason);
    }

    public boolean approved() {
        return responseCode.isApproved();
    }
}
```

The `reason` is human-readable text for logs and for you while learning. Real response messages carry the code, not an explanation, and an issuer is careful about what it reveals to the merchant.

And the rules, `authorisation/AuthorisationRules.java`:

```java
package com.example.cards.authorisation;

import com.example.cards.card.CardStatus;
import com.example.cards.money.Money;

import java.time.YearMonth;

/**
 * The authorisation decision as a pure function: facts in, decision out.
 * No database, no clock, no Spring, so every rule can be unit-tested in milliseconds.
 */
public final class AuthorisationRules {

    private AuthorisationRules() {
    }

    public static Decision decide(AuthorisationFacts facts) {
        if (facts.cardStatus() != CardStatus.ACTIVE) {
            return Decision.decline(ResponseCode.DO_NOT_HONOUR, "Card is " + facts.cardStatus());
        }
        // A card is valid up to and including the last day of its expiry month
        if (YearMonth.from(facts.today()).isAfter(facts.cardExpiry())) {
            return Decision.decline(ResponseCode.EXPIRED_CARD, "Card expired " + facts.cardExpiry());
        }
        if (facts.perTransactionLimit().isLessThan(facts.amount())) {
            return Decision.decline(ResponseCode.EXCEEDS_AMOUNT_LIMIT,
                    "Over the per-transaction limit of " + facts.perTransactionLimit());
        }
        Money spentAfter = facts.spentToday().plus(facts.amount());
        if (facts.dailyLimit().isLessThan(spentAfter)) {
            return Decision.decline(ResponseCode.EXCEEDS_AMOUNT_LIMIT,
                    "Would take today's spending to %s, over the daily limit of %s"
                            .formatted(spentAfter, facts.dailyLimit()));
        }
        if (facts.availableBalance().isLessThan(facts.amount())) {
            return Decision.decline(ResponseCode.INSUFFICIENT_FUNDS,
                    "Available balance is " + facts.availableBalance());
        }
        return Decision.approve();
    }
}
```

- **The order of the checks matters.** The card is checked first: if it's blocked or expired, the customer's balance is irrelevant, and you shouldn't reveal it (a `51` tells whoever is holding a stolen card that it's otherwise valid). Then the limits, then the money.
- **`isLessThan`, never `>`/`<` on `BigDecimal` or `equals`.** "Exactly the available balance" must be approved.
- **`final` class, private constructor, one `static` method.** There's nothing to inject and nothing to mock. That's the point.

### 6. Test every rule, fast

Create `src/test/java/com/example/cards/authorisation/AuthorisationRulesTest.java`:

```java
package com.example.cards.authorisation;

import com.example.cards.card.CardStatus;
import com.example.cards.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorisationRulesTest {

    @Test
    void approvesWhenEverythingIsFine() {
        Decision decision = AuthorisationRules.decide(new Facts().build());

        assertThat(decision.approved()).isTrue();
        assertThat(decision.responseCode().code()).isEqualTo("00");
    }

    @ParameterizedTest(name = "available {0}, amount {1} -> {2}")
    @CsvSource({
            "300.00, 300.00, 00",   // exactly the available balance is fine
            "300.00, 300.01, 51",   // one penny more is not
            "0.00,   0.01,   51",
            "-5.00,  1.00,   51"    // already past the overdraft limit
    })
    void checksTheAvailableBalance(String available, String amount, String expectedCode) {
        AuthorisationFacts facts = new Facts().available(available).amount(amount).build();

        assertThat(AuthorisationRules.decide(facts).responseCode().code()).isEqualTo(expectedCode);
    }

    @Test
    void declinesABlockedCardWithDoNotHonour() {
        AuthorisationFacts facts = new Facts().cardStatus(CardStatus.BLOCKED).build();

        assertThat(AuthorisationRules.decide(facts).responseCode()).isEqualTo(ResponseCode.DO_NOT_HONOUR);
    }

    @Test
    void aCardIsValidUntilTheEndOfItsExpiryMonth() {
        Facts expiresNovember = new Facts().expiry(YearMonth.of(2026, 11));

        Decision lastDay = AuthorisationRules.decide(expiresNovember.today(LocalDate.of(2026, 11, 30)).build());
        Decision dayAfter = AuthorisationRules.decide(expiresNovember.today(LocalDate.of(2026, 12, 1)).build());

        assertThat(lastDay.approved()).isTrue();
        assertThat(dayAfter.responseCode()).isEqualTo(ResponseCode.EXPIRED_CARD);
    }

    @Test
    void declinesOverThePerTransactionLimit() {
        AuthorisationFacts facts = new Facts().available("5000.00").amount("500.01").build();

        assertThat(AuthorisationRules.decide(facts).responseCode()).isEqualTo(ResponseCode.EXCEEDS_AMOUNT_LIMIT);
    }

    @Test
    void declinesWhenTodaysSpendingWouldPassTheDailyLimit() {
        AuthorisationFacts facts = new Facts().available("5000.00").spentToday("900.01").amount("100.00").build();

        Decision decision = AuthorisationRules.decide(facts);

        assertThat(decision.responseCode()).isEqualTo(ResponseCode.EXCEEDS_AMOUNT_LIMIT);
        assertThat(decision.reason()).contains("daily limit");
    }

    @Test
    void checksTheCardBeforeTheMoney() {
        AuthorisationFacts facts = new Facts().cardStatus(CardStatus.BLOCKED).available("0.00").build();

        assertThat(AuthorisationRules.decide(facts).responseCode()).isEqualTo(ResponseCode.DO_NOT_HONOUR);
    }

    /** Test data builder: sensible defaults where everything is fine, so each test changes one thing. */
    private static final class Facts {
        private CardStatus cardStatus = CardStatus.ACTIVE;
        private YearMonth expiry = YearMonth.of(2029, 11);
        private LocalDate today = LocalDate.of(2026, 11, 6);
        private String amount = "25.00";
        private String spentToday = "0.00";
        private String available = "300.00";

        Facts cardStatus(CardStatus value) { cardStatus = value; return this; }
        Facts expiry(YearMonth value) { expiry = value; return this; }
        Facts today(LocalDate value) { today = value; return this; }
        Facts amount(String value) { amount = value; return this; }
        Facts spentToday(String value) { spentToday = value; return this; }
        Facts available(String value) { available = value; return this; }

        AuthorisationFacts build() {
            return new AuthorisationFacts(cardStatus, expiry, today, Money.gbp(amount),
                    Money.gbp("500.00"), Money.gbp("1000.00"), Money.gbp(spentToday), Money.gbp(available));
        }
    }
}
```

Two techniques worth stealing:

- **A test data builder** (the private `Facts` class). `AuthorisationFacts` has eight components. Writing all eight in every test would bury what each test is about. The builder starts from "everything is fine", and each test changes only the fact it cares about: `new Facts().cardStatus(BLOCKED).build()` reads like the test's name.
- **`@ParameterizedTest` with `@CsvSource`** runs one test method once per row. The boundary cases (exactly equal, one penny over, zero, already negative) are listed side by side, so a gap is easy to spot. Each row shows up as its own test in the results, named by the `name` pattern.

Run it. Ten tests (six methods, one of them with four rows) finish in a fraction of a second, with no Spring context at all.

### 7. The `Authorisation` entity

Create `authorisation/Authorisation.java`:

```java
package com.example.cards.authorisation;

import com.example.cards.account.Account;
import com.example.cards.card.Card;
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
import java.time.Instant;
import java.util.Currency;

/** One authorisation request and our answer to it. An approved, PENDING one is a hold. */
@Entity
@Table(name = "authorisation")
public class Authorisation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id")
    private Card card;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "held_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal heldAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "merchant_name", nullable = false, length = 100)
    private String merchantName;

    @Column(name = "merchant_category_code", nullable = false, length = 4)
    private String merchantCategoryCode;

    @Column(name = "response_code", nullable = false, length = 2)
    private ResponseCode responseCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AuthorisationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected Authorisation() {
        // for JPA
    }

    private Authorisation(Card card, Account account, Money amount, String merchantName,
                          String merchantCategoryCode, ResponseCode responseCode, Instant createdAt) {
        this.card = card;
        this.account = account;
        this.amount = amount.amount();
        this.currencyCode = amount.currency().getCurrencyCode();
        this.merchantName = merchantName;
        this.merchantCategoryCode = merchantCategoryCode;
        this.responseCode = responseCode;
        this.createdAt = createdAt;
    }

    /** Approved: the full amount is held until it clears, is reversed or expires. */
    public static Authorisation approved(Card card, Account account, Money amount, String merchantName,
                                         String merchantCategoryCode, Instant now, Instant expiresAt) {
        Authorisation authorisation = new Authorisation(card, account, amount, merchantName,
                merchantCategoryCode, ResponseCode.APPROVED, now);
        authorisation.status = AuthorisationStatus.PENDING;
        authorisation.heldAmount = amount.amount();
        authorisation.expiresAt = expiresAt;
        return authorisation;
    }

    /** Declined: recorded for the audit trail, but nothing is held. */
    public static Authorisation declined(Card card, Account account, Money amount, String merchantName,
                                         String merchantCategoryCode, ResponseCode responseCode, Instant now) {
        Authorisation authorisation = new Authorisation(card, account, amount, merchantName,
                merchantCategoryCode, responseCode, now);
        authorisation.status = AuthorisationStatus.DECLINED;
        authorisation.heldAmount = BigDecimal.ZERO;
        return authorisation;
    }

    public Long getId() {
        return id;
    }

    public Card getCard() {
        return card;
    }

    public Account getAccount() {
        return account;
    }

    public Money getAmount() {
        return new Money(amount, Currency.getInstance(currencyCode));
    }

    public Money getHeldAmount() {
        return new Money(heldAmount, Currency.getInstance(currencyCode));
    }

    public String getMerchantName() {
        return merchantName;
    }

    public String getMerchantCategoryCode() {
        return merchantCategoryCode;
    }

    public ResponseCode getResponseCode() {
        return responseCode;
    }

    public AuthorisationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
```

**Two named factories, `approved` and `declined`,** instead of a public constructor. Each one sets up a consistent state: an approved authorisation is `PENDING`, holds the full amount and has an expiry. A declined one holds nothing and never expires. Neither can be built half-right.

**Why keep declines at all?** Because the bank must be able to answer "why was my card declined at 14:03?" from the customer, the fraud team and the regulator. Every decision is part of the [audit trail](../glossary.md#audit-trail).

### 8. The repository

Create `authorisation/AuthorisationRepository.java`:

```java
package com.example.cards.authorisation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;

public interface AuthorisationRepository extends JpaRepository<Authorisation, Long> {

    /** The total of an account's active holds. */
    @Query("""
            select coalesce(sum(a.heldAmount), 0)
            from Authorisation a
            where a.account.id = :accountId and a.status = :status
            """)
    BigDecimal sumHeldAmount(Long accountId, AuthorisationStatus status);

    /** How much a card has had approved since a given instant, for the daily limit. */
    @Query("""
            select coalesce(sum(a.amount), 0)
            from Authorisation a
            where a.card.id = :cardId and a.status in :statuses and a.createdAt >= :since
            """)
    BigDecimal sumAmountSince(Long cardId, Collection<AuthorisationStatus> statuses, Instant since);
}
```

- **Aggregates in the database.** `sum(...)` returns one number instead of loading every hold as an entity and adding them up in Java.
- **`coalesce(..., 0)`** turns "no rows" into `0` instead of `null`.
- **The status is a parameter**, rather than a fully qualified enum literal inside the JPQL string, so the query stays readable.

> **Simplification:** the daily spend counts `PENDING` and `CLEARED` authorisations by their original `amount`. A real issuer would decide carefully whether reversed, partially reversed or expired amounts count towards today's limit, and would often keep a running total per card per day rather than summing on every request.

### 9. How long a hold lasts

Holds don't last forever (guide 05). For now, each approval records an `expiresAt`. Make the lifetime configurable. Create `authorisation/HoldProperties.java`:

```java
package com.example.cards.authorisation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Settings under cards.holds.* in application.properties. */
@ConfigurationProperties("cards.holds")
public record HoldProperties(Duration lifetime) {
}
```

A **record** works as `@ConfigurationProperties`: Spring Boot binds the properties through its constructor, and the result is immutable. Tell Spring Boot to look for such classes by adding `@ConfigurationPropertiesScan` to `CardAuthorisationApplication`:

```java
@SpringBootApplication
@ConfigurationPropertiesScan
public class CardAuthorisationApplication {
```

And add the setting to `application.properties`:

```properties
# How long an approved authorisation holds money if no presentment arrives
cards.holds.lifetime=7d
```

Spring Boot converts `7d` into a `Duration` of seven days. (`PT168H`, the ISO-8601 form, also works.)

> **Simplification:** seven days is this tutorial's choice. The real lifetime is set by scheme rules and depends on the type of merchant and authorisation. Hotels and car hire, for example, typically get much longer, around a month. Guide 05's exercise makes the lifetime depend on the MCC.

### 10. The authorisation service

Create `authorisation/AuthorisationResult.java`:

```java
package com.example.cards.authorisation;

/** What we answer: the decision, and the id of the record we kept (null if we kept none). */
public record AuthorisationResult(Long authorisationId, Decision decision) {
}
```

And `authorisation/AuthorisationService.java`:

```java
package com.example.cards.authorisation;

import com.example.cards.account.Account;
import com.example.cards.account.AccountRepository;
import com.example.cards.card.Card;
import com.example.cards.card.CardRepository;
import com.example.cards.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Service
public class AuthorisationService {

    /** The bank's "today" for daily limits is the UK calendar day. */
    static final ZoneId BANK_ZONE = ZoneId.of("Europe/London");

    private final CardRepository cards;
    private final AccountRepository accounts;
    private final AuthorisationRepository authorisations;
    private final HoldProperties holds;
    private final Clock clock;

    public AuthorisationService(CardRepository cards, AccountRepository accounts,
                                AuthorisationRepository authorisations, HoldProperties holds, Clock clock) {
        this.cards = cards;
        this.accounts = accounts;
        this.authorisations = authorisations;
        this.holds = holds;
        this.clock = clock;
    }

    @Transactional
    public AuthorisationResult authorise(String cardToken, Money amount, String merchantName,
                                         String merchantCategoryCode) {
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.ofInstant(now, BANK_ZONE);

        // 1. Find the card. An unknown token is a decline, not an error.
        Optional<Card> found = cards.findByToken(cardToken);
        if (found.isEmpty()) {
            return new AuthorisationResult(null,
                    Decision.decline(ResponseCode.INVALID_CARD, "No card with that token"));
        }
        Card card = found.get();

        // 2. Gather the facts: the account, its holds, and today's spending on this card
        Account account = accounts.findById(card.getAccount().getId()).orElseThrow();
        Money held = new Money(
                authorisations.sumHeldAmount(account.getId(), AuthorisationStatus.PENDING),
                account.getCurrency());
        Money spentToday = new Money(
                authorisations.sumAmountSince(card.getId(),
                        List.of(AuthorisationStatus.PENDING, AuthorisationStatus.CLEARED),
                        today.atStartOfDay(BANK_ZONE).toInstant()),
                account.getCurrency());

        // 3. Decide: a pure function, no I/O
        Decision decision = AuthorisationRules.decide(new AuthorisationFacts(
                card.getStatus(), card.getExpiry(), today, amount,
                card.getPerTransactionLimit(), card.getDailyLimit(),
                spentToday, account.availableBalance(held)));

        // 4. Record the answer. If approved, this row IS the hold.
        Authorisation authorisation = decision.approved()
                ? Authorisation.approved(card, account, amount, merchantName, merchantCategoryCode,
                        now, now.plus(holds.lifetime()))
                : Authorisation.declined(card, account, amount, merchantName, merchantCategoryCode,
                        decision.responseCode(), now);
        authorisations.save(authorisation);
        return new AuthorisationResult(authorisation.getId(), decision);
    }

    @Transactional(readOnly = true)
    public Authorisation get(Long id) {
        return authorisations.findById(id).orElseThrow(() -> new AuthorisationNotFoundException(id));
    }
}
```

Read it as the gather-decide-record shape from the Concepts section:

- **`now` is read once**, at the top, and used everywhere below. If the method asked the clock three times, a request arriving at 23:59:59.999 could use one day for the daily limit and the next day for the expiry check.
- **"Today" is the UK day.** `Instant` is a point on the global timeline. `LocalDate.ofInstant(now, BANK_ZONE)` turns it into a calendar date in London, including British Summer Time. `today.atStartOfDay(BANK_ZONE)` is midnight in London, as an `Instant`, for the daily-limit query.
- **The account is loaded with `findById(card.getAccount().getId())`.** `card.getAccount()` is a lazy proxy, and `getId()` doesn't initialise it. Loading the account explicitly makes the moment it's read obvious. In guide 03 this exact line changes, and it's the most important change in the tutorial.
- **An unknown token returns a decline, `14`**, and records nothing, because there's no card or account to attach a record to. (A real issuer would still log it: a burst of unknown card numbers can be a sign of an attack that guesses card numbers.)

Create `authorisation/AuthorisationNotFoundException.java`:

```java
package com.example.cards.authorisation;

public class AuthorisationNotFoundException extends RuntimeException {

    public AuthorisationNotFoundException(Long id) {
        super("No authorisation with id " + id);
    }
}
```

and map it to a `404` in `ApiExceptionHandler`, exactly like the others:

```java
    @ExceptionHandler(AuthorisationNotFoundException.class)
    ProblemDetail handle(AuthorisationNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Authorisation not found");
        return problem;
    }
```

### 11. The controller

Create `authorisation/AuthorisationController.java`:

```java
package com.example.cards.authorisation;

import com.example.cards.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

@RestController
public class AuthorisationController {

    private final AuthorisationService authorisationService;

    public AuthorisationController(AuthorisationService authorisationService) {
        this.authorisationService = authorisationService;
    }

    /** Always 200: a decline is a perfectly good answer, not an HTTP error. */
    @PostMapping("/authorisations")
    public DecisionResponse authorise(@Valid @RequestBody AuthorisationRequest request) {
        AuthorisationResult result = authorisationService.authorise(
                request.cardToken(), Money.gbp(request.amount().toPlainString()),
                request.merchantName(), request.merchantCategoryCode());
        Decision decision = result.decision();
        return new DecisionResponse(result.authorisationId(), decision.approved(),
                decision.responseCode().code(), decision.reason());
    }

    @GetMapping("/authorisations/{id}")
    public AuthorisationResponse get(@PathVariable Long id) {
        return AuthorisationResponse.from(authorisationService.get(id));
    }

    public record AuthorisationRequest(
            @NotBlank String cardToken,
            @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 100) String merchantName,
            @NotNull @Pattern(regexp = "\\d{4}") String merchantCategoryCode) {
    }

    public record DecisionResponse(Long authorisationId, boolean approved, String responseCode, String reason) {
    }

    public record AuthorisationResponse(Long id, Long cardId, Long accountId, Money amount, Money heldAmount,
                                        String merchantName, String merchantCategoryCode, String responseCode,
                                        AuthorisationStatus status, Instant createdAt, Instant expiresAt) {

        static AuthorisationResponse from(Authorisation a) {
            return new AuthorisationResponse(a.getId(), a.getCard().getId(), a.getAccount().getId(),
                    a.getAmount(), a.getHeldAmount(), a.getMerchantName(), a.getMerchantCategoryCode(),
                    a.getResponseCode().code(), a.getStatus(), a.getCreatedAt(), a.getExpiresAt());
        }
    }
}
```

There's no class-level `@RequestMapping`, because Exercise 2 adds an endpoint under `/accounts` to this controller.

`@Pattern(regexp = "\\d{4}")` checks the MCC is exactly four digits. The amount arrives as a JSON string (`"45.00"`), and Jackson converts it straight to `BigDecimal` without passing through `double`.

### 12. Show the holds on the account

The account response should now show the holds and a real available balance. Create `account/AccountBalances.java`:

```java
package com.example.cards.account;

import com.example.cards.money.Money;

/** An account together with the total of its active holds. */
public record AccountBalances(Account account, Money held) {

    public Money available() {
        return account.availableBalance(held);
    }
}
```

In `AccountService`, inject the `AuthorisationRepository` (add it as a constructor parameter and field), add a `balances` method, and make `deposit` return balances too:

```java
    @Transactional(readOnly = true)
    public AccountBalances balances(Long id) {
        Account account = get(id);
        Money held = new Money(
                authorisations.sumHeldAmount(id, AuthorisationStatus.PENDING), account.getCurrency());
        return new AccountBalances(account, held);
    }

    @Transactional
    public AccountBalances deposit(Long id, Money amount) {
        get(id).deposit(amount);
        return balances(id);
    }
```

In `AccountController`, change `AccountResponse` to carry the holds:

```java
    public record AccountResponse(Long id, String name, Money ledgerBalance, Money held,
                                  Money overdraftLimit, Money availableBalance) {

        static AccountResponse from(AccountBalances balances) {
            Account account = balances.account();
            return new AccountResponse(account.getId(), account.getName(), account.getLedgerBalance(),
                    balances.held(), account.getOverdraftLimit(), balances.available());
        }
    }
```

`get` becomes `return AccountResponse.from(accountService.balances(id));` and, in `open`, a new account obviously has no holds:

```java
        // A brand-new account has no holds
        AccountBalances balances = new AccountBalances(account, Money.zero(account.getCurrency()));
        return ResponseEntity.created(location).body(AccountResponse.from(balances));
```

The `deposit` endpoint needs no change: `AccountResponse.from(accountService.deposit(...))` now receives `AccountBalances`.

### 13. API tests

Create `src/test/java/com/example/cards/authorisation/AuthorisationApiTest.java`:

```java
package com.example.cards.authorisation;

import com.example.cards.CardsIntegrationTest;
import com.example.cards.account.AccountService;
import com.example.cards.card.CardService;
import com.example.cards.money.Money;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

@CardsIntegrationTest
class AuthorisationApiTest {

    @Autowired MockMvcTester mvc;
    @Autowired AccountService accountService;
    @Autowired CardService cardService;

    Long accountId;
    String cardToken;

    @BeforeEach
    void openAccountWithCard() {
        accountId = accountService.open("Alice", Money.gbp("0.00")).getId();
        accountService.deposit(accountId, Money.gbp("100.00"));
        cardToken = cardService.issue(accountId).getToken();
    }

    @Test
    void approvalPlacesAHoldThatReducesTheAvailableBalanceOnly() {
        MvcTestResult response = authorise(cardToken, "30.00");

        assertThat(response).hasStatus(HttpStatus.OK);
        assertThat(response).bodyJson().extractingPath("$.approved").isEqualTo(true);
        assertThat(response).bodyJson().extractingPath("$.responseCode").isEqualTo("00");

        var balances = accountService.balances(accountId);
        assertThat(balances.account().getLedgerBalance()).isEqualTo(Money.gbp("100.00"));
        assertThat(balances.held()).isEqualTo(Money.gbp("30.00"));
        assertThat(balances.available()).isEqualTo(Money.gbp("70.00"));
    }

    @Test
    void declineIsA200WithResponseCode51() {
        authorise(cardToken, "80.00");

        MvcTestResult response = authorise(cardToken, "20.01");

        assertThat(response).hasStatus(HttpStatus.OK);
        assertThat(response).bodyJson().extractingPath("$.approved").isEqualTo(false);
        assertThat(response).bodyJson().extractingPath("$.responseCode").isEqualTo("51");
        assertThat(accountService.balances(accountId).available()).isEqualTo(Money.gbp("20.00"));
    }

    @Test
    void unknownCardTokenIsDeclinedWithResponseCode14() {
        MvcTestResult response = authorise("no-such-token", "1.00");

        assertThat(response).hasStatus(HttpStatus.OK);
        assertThat(response).bodyJson().extractingPath("$.responseCode").isEqualTo("14");
    }

    @Test
    void declinesAreRecordedButHoldNothing() throws Exception {
        MvcTestResult response = authorise(cardToken, "500.01"); // over the £500 per-transaction limit
        assertThat(response).bodyJson().extractingPath("$.responseCode").isEqualTo("61");
        Integer id = JsonPath.read(response.getResponse().getContentAsString(), "$.authorisationId");

        MvcTestResult stored = mvc.get().uri("/authorisations/{id}", id).exchange();

        assertThat(stored).bodyJson().extractingPath("$.status").isEqualTo("DECLINED");
        assertThat(stored).bodyJson().extractingPath("$.heldAmount.amount").isEqualTo("0.00");
    }

    @Test
    void rejectsAMalformedRequest() {
        MvcTestResult response = mvc.post().uri("/authorisations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"cardToken": "%s", "amount": "1.001", "merchantName": "Cafe", "merchantCategoryCode": "58"}
                        """.formatted(cardToken))
                .exchange();

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors.amount").isNotNull();
        assertThat(response).bodyJson().extractingPath("$.errors.merchantCategoryCode").isNotNull();
    }

    private MvcTestResult authorise(String token, String amount) {
        return mvc.post().uri("/authorisations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"cardToken": "%s", "amount": "%s", "merchantName": "Corner Cafe", "merchantCategoryCode": "5814"}
                        """.formatted(token, amount))
                .exchange();
    }
}
```

- **`approvalPlacesAHold…` checks all three numbers.** The ledger balance didn't move, the hold is £30, and the available balance dropped by £30. That's the whole idea of a hold in three assertions.
- **`declineIsA200…`** pins down the design decision from the Concepts section, so nobody "fixes" it into a `422` later.
- **`declinesAreRecorded…`** reads the new id out of the response with `JsonPath.read` (the library behind `extractingPath`, already on the test classpath) and fetches the stored record through the API.
- **`rejectsAMalformedRequest`** is the other side of the line: this is a request the issuer can't process at all, so it *is* an HTTP error.

### 14. Prove the path is short

"Keep the authorisation path to a handful of queries" is the kind of rule that quietly erodes. Someone adds a lazy-loaded field to a log line, or an innocent-looking repository call, and the hot path does twice the work. Nobody notices until it's slow in production.

So write it down as a test. Hibernate can count the statements it sends. Turn statistics on for tests by changing the `@SpringBootTest` line in `CardsIntegrationTest`:

```java
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cards-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
```

Then create `src/test/java/com/example/cards/authorisation/AuthorisationQueryCountTest.java`:

```java
package com.example.cards.authorisation;

import com.example.cards.CardsIntegrationTest;
import com.example.cards.account.AccountService;
import com.example.cards.card.CardService;
import com.example.cards.money.Money;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the hot path: if someone adds a query to it, this test says so. */
@CardsIntegrationTest
class AuthorisationQueryCountTest {

    @Autowired AccountService accountService;
    @Autowired CardService cardService;
    @Autowired AuthorisationService authorisationService;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void anAuthorisationIsAHandfulOfStatements() {
        Long accountId = accountService.open("Alice", Money.gbp("0.00")).getId();
        accountService.deposit(accountId, Money.gbp("100.00"));
        String token = cardService.issue(accountId).getToken();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        authorisationService.authorise(token, Money.gbp("10.00"), "Corner Cafe", "5814");

        System.out.println("statements=" + statistics.getPrepareStatementCount()
                + " entities loaded=" + statistics.getEntityLoadCount());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(5);
    }
}
```

Run it:

```
statements=5 entities loaded=2
```

If you temporarily add `spring.jpa.show-sql=true` to the test properties, you can see all five (column lists trimmed):

```
select c1_0.id, … from card c1_0 where c1_0.token=?
select a1_0.id, … from account a1_0 where a1_0.id=?
select coalesce(sum(a1_0.held_amount),0) from authorisation a1_0 where a1_0.account_id=? and a1_0.status=?
select coalesce(sum(a1_0.amount),0) from authorisation a1_0 where a1_0.card_id=? and a1_0.status in (?,?) and a1_0.created_at>=?
insert into authorisation (account_id, amount, card_id, …) values (?, ?, ?, …)
```

Four indexed reads and one insert, in one transaction. The test asserts **exactly** 5 rather than "at most 5", so that it also tells you when someone makes the path *shorter*, and you can update the number deliberately.

> **Sidebar: counting isn't timing.** Five statements against an in-memory database tells you nothing about milliseconds in production, where each statement is a network round trip. But the statement count is the thing your code controls, it's deterministic, and it's the first thing to grow when a hot path decays. Real latency is measured in production with metrics (for example Micrometer timers, which Spring Boot Actuator exposes) and percentiles, because the slowest 1% of authorisations matter more than the average.

### 15. Try it

Restart the app. Flyway applies `V2` to your existing database:

```
Migrating schema "PUBLIC" to version "2 - create authorisation"
```

Add to `requests.http`:

```http
### Alice buys fuel: £45 at the pump
POST http://localhost:8080/authorisations
Content-Type: application/json

{"cardToken": "{{cardToken}}", "amount": "45.00", "merchantName": "Shell Mile End", "merchantCategoryCode": "5542"}

### Her balances now
GET http://localhost:8080/accounts/1

### The hold itself
GET http://localhost:8080/authorisations/1

### Too much: declined
POST http://localhost:8080/authorisations
Content-Type: application/json

{"cardToken": "{{cardToken}}", "amount": "400.00", "merchantName": "Big TV Shop", "merchantCategoryCode": "5732"}

### An unknown card
POST http://localhost:8080/authorisations
Content-Type: application/json

{"cardToken": "no-such-card", "amount": "1.00", "merchantName": "Corner Cafe", "merchantCategoryCode": "5814"}
```

If you're using curl or Swagger UI, paste the token you copied in guide 01 in place of `{{cardToken}}`. If you've restarted IntelliJ since, run the "Issue her a card" request again (or `GET /cards/1` and copy the token).

With Alice's account from guide 01 (£250.00 ledger balance, £100 overdraft), the fuel authorisation is approved:

```json
{"authorisationId": 1, "approved": true, "responseCode": "00", "reason": "Approved"}
```

and her account shows the hold:

```json
{
  "id": 1,
  "name": "Alice",
  "ledgerBalance": { "amount": "250.00", "currency": "GBP" },
  "held": { "amount": "45.00", "currency": "GBP" },
  "overdraftLimit": { "amount": "100.00", "currency": "GBP" },
  "availableBalance": { "amount": "305.00", "currency": "GBP" }
}
```

Those are exactly the numbers from guide 01's table. The £400 TV is declined, and the reason shows why:

```json
{"authorisationId": 2, "approved": false, "responseCode": "51", "reason": "Available balance is GBP 305.00"}
```

The unknown card is declined with `14` and no record:

```json
{"authorisationId": null, "approved": false, "responseCode": "14", "reason": "No card with that token"}
```

And the hold itself (`GET /authorisations/1`):

```json
{
  "id": 1,
  "cardId": 1,
  "accountId": 1,
  "amount": { "amount": "45.00", "currency": "GBP" },
  "heldAmount": { "amount": "45.00", "currency": "GBP" },
  "merchantName": "Shell Mile End",
  "merchantCategoryCode": "5542",
  "responseCode": "00",
  "status": "PENDING",
  "createdAt": "2026-11-06T23:00:05.706847Z",
  "expiresAt": "2026-11-13T23:00:05.706847Z"
}
```

Try £600 too: that's declined with `61`, because the per-transaction limit is checked before the balance.

---

## Your turn

### Exercise 1: blocked cards are declined

Guide 01's Exercise 2 added `POST /cards/{id}/block`. The rules already decline a blocked card, but nothing proves the whole path works. Add a test to `AuthorisationApiTest` that issues a **second** card on the account, blocks it, authorises £1 on it, and expects response code `05`.

<details>
<summary>Solution</summary>

```java
    @Test
    void blockedCardIsDeclinedWithDoNotHonour() {
        Long cardId = cardService.issue(accountId).getId();
        String blockedToken = cardService.block(cardId).getToken();

        MvcTestResult response = authorise(blockedToken, "1.00");

        assertThat(response).bodyJson().extractingPath("$.responseCode").isEqualTo("05");
    }
```

If you skipped guide 01's exercise, add `block` to `Card` and `CardService` first (see the solution there).

</details>

### Exercise 2: what's pending?

Banking apps show a list of **pending** transactions: the holds. Add:

```
GET /accounts/{accountId}/authorisations?status=PENDING
```

returning that account's authorisations with the given status, **newest first**, as a list of `AuthorisationResponse`. Then add a test that makes two approved authorisations and one declined one, and checks that exactly two come back, the newer first.

<details>
<summary>Hint</summary>

A derived query method can do all of it: `findByAccountIdAndStatusOrderByCreatedAtDesc…`. Think about what happens if two authorisations have exactly the same `createdAt`, and add a tie-breaker.

</details>

<details>
<summary>Solution</summary>

`AuthorisationRepository` (import `java.util.List`):

```java
    List<Authorisation> findByAccountIdAndStatusOrderByCreatedAtDescIdDesc(Long accountId, AuthorisationStatus status);
```

`AuthorisationService`:

```java
    @Transactional(readOnly = true)
    public List<Authorisation> findByStatus(Long accountId, AuthorisationStatus status) {
        return authorisations.findByAccountIdAndStatusOrderByCreatedAtDescIdDesc(accountId, status);
    }
```

`AuthorisationController` (import `RequestParam` and `java.util.List`):

```java
    /** What a banking app shows as "pending": GET /accounts/2/authorisations?status=PENDING */
    @GetMapping("/accounts/{accountId}/authorisations")
    public List<AuthorisationResponse> forAccount(@PathVariable Long accountId,
                                                  @RequestParam AuthorisationStatus status) {
        return authorisationService.findByStatus(accountId, status).stream()
                .map(AuthorisationResponse::from)
                .toList();
    }
```

`AuthorisationApiTest`:

```java
    @Test
    void listsPendingAuthorisationsNewestFirst() {
        authorise(cardToken, "10.00");
        authorise(cardToken, "20.00");
        authorise(cardToken, "999.00"); // declined, so not pending

        MvcTestResult response = mvc.get().uri("/accounts/{id}/authorisations?status=PENDING", accountId)
                .exchange();

        assertThat(response).hasStatus(HttpStatus.OK);
        assertThat(response).bodyJson().extractingPath("$.length()").isEqualTo(2);
        assertThat(response).bodyJson().extractingPath("$[0].amount.amount").isEqualTo("20.00");
    }
```

- **`IdDesc` is the tie-breaker.** Two authorisations in the same microsecond (easy in a test, and possible in production) would otherwise come back in an undefined order. Identity ids always increase, so they settle it.
- Spring converts `?status=PENDING` to the enum for you. `?status=WAITING` gets a `400` `ProblemDetail`: `"Failed to convert 'status' with value: 'WAITING'"`.
- `AuthorisationResponse.from` runs **outside** the transaction, but only calls `getId()` on the lazy card and account proxies, which is safe.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
```

New files since guide 01:

```
src/main/java/com/example/cards/
├── account/AccountBalances.java
└── authorisation/
    ├── Authorisation.java
    ├── AuthorisationController.java
    ├── AuthorisationFacts.java
    ├── AuthorisationNotFoundException.java
    ├── AuthorisationRepository.java
    ├── AuthorisationResult.java
    ├── AuthorisationRules.java
    ├── AuthorisationService.java
    ├── AuthorisationStatus.java
    ├── Decision.java
    ├── HoldProperties.java
    ├── ResponseCode.java
    └── ResponseCodeConverter.java
src/main/resources/db/migration/
└── V2__create_authorisation.sql
src/test/java/com/example/cards/authorisation/
├── AuthorisationApiTest.java
├── AuthorisationQueryCountTest.java
└── AuthorisationRulesTest.java
```

Modified: `CardAuthorisationApplication.java`, `Card.java`, `AccountService.java`, `AccountController.java`, `ApiExceptionHandler.java`, `application.properties`, `CardsIntegrationTest.java`, `requests.http`.

---

## Common errors

**`Failed to bind properties under 'cards.holds.lifetime' to java.time.Duration`** … `'seven days' is not a valid duration`
The value isn't a duration Spring Boot understands. Use `7d`, `168h` or `PT168H`. Watch out for a bare number such as `7`: Spring Boot reads it as **milliseconds**, which binds without any error but makes every hold expire almost immediately.

**`Parameter 3 of constructor in com.example.cards.authorisation.AuthorisationService required a bean of type 'com.example.cards.authorisation.HoldProperties' that could not be found.`**
`@ConfigurationPropertiesScan` is missing from `CardAuthorisationApplication`, so Spring Boot never created the `HoldProperties` bean.

**`AuthorisationQueryCountTest` says `expected: 5 but was: 6`**
Something added a statement to the hot path. Turn on `spring.jpa.show-sql=true` in the test properties, compare the SQL with the five statements in step 14, and decide whether the new one earns its place.

**`AuthorisationQueryCountTest` says `expected: 5 but was: 0L`**
Statistics aren't switched on, so Hibernate counts nothing. Check the `hibernate.generate_statistics=true` property in `CardsIntegrationTest`.

**A decline comes back as a `500`**
Something in the rules threw an exception instead of returning a `Decision`. (In this design that should be impossible, because every rule returns a value, but it's easy to break by adding a rule that throws.) Declines must be values, not exceptions.

**Daily limits reset at the wrong time**
Somewhere, "today" was computed with `LocalDate.now()` (the server's time zone) or in UTC. During British Summer Time, UTC midnight is 01:00 in London. Always compute it from the injected clock with the bank's zone.

---

## Recap

- An **authorisation request** carries the card (here a token), the amount, the merchant and its **MCC**. The issuer answers with a **response code**: `00` approves, anything else declines.
- An approved authorisation places a **hold**. Here, a hold is simply an authorisation row with status `PENDING`. It lowers the **available balance** and leaves the **ledger balance** alone.
- **Spending limits** (per transaction, per day) are checked alongside the balance, and the order of checks matters: card first, then limits, then money.
- Authorisation must be **fast**. If the issuer is too slow, the scheme may **stand in** and decide for it. So keep the path to one short transaction and a few indexed queries, and guard it with a statement-count test.
- **Gather, decide, record.** The decision is a **pure function** of the facts, unit-tested with a **test data builder** and **parameterised tests**.
- **A decline is a successful response.** HTTP errors are for requests that couldn't be processed at all.

### Interview angle

<details>
<summary>"How would you design an authorisation endpoint to be fast?"</summary>

Keep the work inside the request to the minimum: one short database transaction, a small fixed number of indexed queries (find the card, load the account, sum the holds, sum today's spend), a pure in-memory decision, and one insert. Use aggregates in the database rather than loading collections. No synchronous calls to slow systems in the middle. Anything that can happen afterwards (notifications, analytics) happens asynchronously. Guard it with a test that counts SQL statements, and measure latency percentiles in production. Mention that the scheme has a deadline and may use stand-in processing if the issuer misses it.

</details>

<details>
<summary>"Why make the decision logic a pure function?"</summary>

It separates *what* the rules are from *how* the facts are fetched. Pure functions are trivially testable (no database, no mocks, no clock), so every rule and boundary can be covered with fast unit tests, including awkward ones such as the last day of the expiry month. It also makes the rules easy to read and review, easy to reuse (for example, in a stand-in or simulation mode) and deterministic: the same facts always give the same answer, which helps when investigating a disputed decline.

</details>

<details>
<summary>"A payment was declined. Should the API return a 4xx?"</summary>

No. The request was valid and processed successfully, and the decision happens to be "no". It belongs in the response body as a business outcome (here a response code). HTTP error codes are for requests that couldn't be processed: malformed input, authentication failures, server errors. Mixing the two makes clients treat business declines as technical failures and, for example, retry them.

</details>

<details>
<summary>"Why store declined authorisations?"</summary>

Audit and investigation. Customers ask why a payment failed, fraud teams look for patterns (many declines in a row can mean a stolen card being tested), and operations need to see decline rates. Each record shows the response code, the time and the merchant. Declines simply hold no money.

</details>

### Further reading

- [Visa: Authorization and reversal processing best practices for merchants](https://usa.visa.com/content/dam/VCOM/regional/na/us/support-legal/documents/authorization-and-reversal-processing-best-practices-for-merchants.pdf): the merchant's side of authorisations, holds and reversals
- [JUnit user guide: parameterized tests](https://docs.junit.org/current/user-guide/#writing-tests-parameterized-tests)
- [Spring Boot reference: type-safe configuration properties](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties)
- [Hibernate ORM user guide: statistics](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#statistics)

**Next:** [03: Concurrent authorisations](03-concurrent-authorisations.md). Ten payments arrive at the same instant, and every one of them is approved.
