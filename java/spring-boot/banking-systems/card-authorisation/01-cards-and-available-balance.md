# 01: Cards and Available Balance

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- Who's involved in a card payment: the **cardholder**, the **merchant**, the **acquirer**, the **issuer** and the **card scheme**, and which of them you're building
- The three stages of a card payment: **authorisation**, **clearing** and **settlement**
- The difference between a **ledger balance** and an **available balance**, and why your banking app shows both
- Why a card system should never store the real card number, and what **PCI DSS** has to do with it
- How to set up a Spring Boot project for this tutorial, with a `Money` type, an injected `Clock`, `ProblemDetail` errors and a test meta-annotation

By the end you'll have an API that opens accounts with an arranged overdraft, issues cards against them, and reports both balances, plus a test suite that proves it.

---

## Concepts

### How it works in a real bank

You tap your card in a café. Before the terminal beeps, a message has travelled from the café's terminal to the café's bank, through a card network such as Visa or Mastercard, to **your** bank, and back again. Your bank looked at your account, decided whether you could pay, and answered. That's usually all over in a second or two.

There are five parties in that journey. The industry calls the first four the **four-party model**, with the card scheme connecting them:

```
 Cardholder ──card──▶ Merchant ──▶ Acquirer ──▶ Card scheme ──▶ Issuer
   (you)             (the café)   (café's bank)  (Visa, Mastercard)  (your bank)
                                       ◀──────── approve / decline ◀──
```

| Party | Who | What it does |
|---|---|---|
| **Cardholder** | You | Owns the card and the account behind it |
| **Merchant** | The café | Sells you something and accepts card payments |
| **Acquirer** | The café's bank (or a payment provider acting for it) | Connects the merchant to the card schemes, and pays the merchant |
| **Card scheme** | Visa, Mastercard | Runs the network, routes messages, sets the rules, and works out who owes whom |
| **Issuer** | Your bank | Issued your card. Decides whether to approve each payment, and pays for the ones it approves |

This tutorial builds the **issuer** side: the system at your bank that receives authorisation requests from the card scheme and answers them. It's the side a retail bank's developers work on.

> **Sidebar: three-party schemes.** American Express has traditionally been both issuer and acquirer for many of its cards, so the same company sits on both sides. That's called a *three-party* model. The issuer-side ideas in this tutorial are the same either way.

### Authorisation, clearing, settlement

A card payment isn't one event. It's three, often days apart:

1. **[Authorisation](../glossary.md#authorisation)**, at the till, in real time. The scheme asks the issuer "can this card spend £4.50 here?" The issuer approves or declines. If it approves, it puts a **[hold](../glossary.md#hold)** on £4.50 of the customer's money, but **no money moves yet**.
2. **Clearing** (also called **presentment**), usually later that day or within a few days. The merchant's acquirer sends the final transaction details through the scheme to the issuer: "that £4.50 authorisation really happened, and here is the final amount". The issuer now **posts** the payment to the customer's account and releases the hold.
3. **Settlement**. The scheme works out the totals and the issuers pay the acquirers, who pay the merchants. The money moves between banks in bulk, not payment by payment.

You'll build authorisation in guides 02 and 03, clearing and a simple settlement in guide 04, and what happens when clearing never arrives in guide 05.

### Ledger balance vs available balance

Your banking app probably shows two numbers. They're different on purpose:

- **[Ledger balance](../glossary.md#ledger-balance)** (also called the *book* or *current* balance): the result of everything that has actually been **posted** to the account. Cleared card payments, salary, Direct Debits.
- **[Available balance](../glossary.md#available-balance)**: what the customer can spend **right now**.

```
available balance = ledger balance − active holds + arranged overdraft limit
```

Suppose Alice has £250.00 in her account and a £100 [arranged overdraft](../glossary.md#overdraft). She fills up with fuel and the pump authorises £45. Until the fuel station's clearing record arrives, her account looks like this:

| | |
|---|---|
| Ledger balance | £250.00 |
| Holds (pending) | £45.00 |
| Arranged overdraft | £100.00 |
| **Available balance** | **£305.00** |

The £45 shows as **pending** in her app. It hasn't left her account (the ledger balance is unchanged), but she can't spend it twice either. Authorisation decisions are made against the **available** balance. Today you'll build accounts that report both numbers. Holds arrive in guide 02.

### Never store the card number

The long number on the front of a card is the **PAN** (primary account number). Anyone who holds PANs, together with the other details on the card, can attempt fraud with them. So the card industry's security standard, **PCI DSS** (the Payment Card Industry Data Security Standard, run by the PCI Security Standards Council), puts strict requirements on any system that stores, processes or transmits card numbers: encryption, network segregation, access logging, regular audits, and limits on how much of a number may be shown on screen.

The most effective way to meet those requirements is to keep the PAN out of as many systems as possible. Real issuers keep it in a tightly controlled card-management system (with the cryptographic keys in hardware security modules), and the rest of the bank works with a **token**: a meaningless stand-in value that maps to the card.

This tutorial goes one step further: **it never sees a PAN at all.** Each card gets a random token, and the only digits you keep are a made-up "last four" for display, which is what you see on receipts and in banking apps. The authorisation requests in guide 02 arrive with the token, not the number.

> **Simplification:** in reality the scheme sends the issuer the PAN (or a scheme token) in the authorisation message, and a secure component at the issuer translates it. We skip that translation step and pretend the token arrives directly.

### Money, briefly

Money in this tutorial is a small `Money` record: a `BigDecimal` plus a `java.util.Currency`, always at the currency's own number of decimal places, refusing to round silently, and refusing to mix currencies. It's sent in JSON as a string (`"45.00"`) so no client can lose pennies to floating-point.

You'll copy it in step 3. The Core ledger tutorial covers this properly: why `double` is wrong for money, the `BigDecimal` traps, and every design choice in the record. Here, the short version is enough.

---

## Steps

### 1. Generate the project

Go to [start.spring.io](https://start.spring.io) and choose:

| Setting | Value |
|---|---|
| Project | Maven |
| Language | Java |
| Spring Boot | 4.1.2 (or the latest 4.1.x) |
| Group | `com.example` |
| Artifact | `card-authorisation` |
| Package name | `com.example.cards` |
| Packaging | Jar |
| Java | 25 |

Add these dependencies:

| Dependency | Why |
|---|---|
| **Spring Web** | REST controllers |
| **Spring Data JPA** | Entities and repositories |
| **Flyway Migration** | Versioned SQL migrations |
| **H2 Database** | The embedded database |
| **Validation** | `@Valid`, `@NotBlank`, `@Positive` |
| **SpringDoc OpenAPI** | Swagger UI, for trying the API in a browser |

Click **Generate** and unzip the download so the project folder ends up at:

```
tutorials/workspace/banking-systems/card-authorisation/
```

Open it in IntelliJ with **File → Open…**, choose the `card-authorisation` folder (the one containing `pom.xml`), and trust the project. Check **File → Project Structure → Project → SDK** is set to a Java 25 JDK.

Delete the generated test `src/test/java/com/example/cards/CardAuthorisationApplicationTests.java`. You'll write better ones in step 9.

### 2. Configure the application

Replace `src/main/resources/application.properties` with:

```properties
spring.application.name=card-authorisation

# H2 database stored in ./data/cards.mv.db
spring.datasource.url=jdbc:h2:file:./data/cards
spring.datasource.username=sa
spring.h2.console.enabled=true

# Flyway owns the schema; Hibernate only checks it matches the entities
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
```

- `username=sa`: Spring Boot only defaults the username to `sa` for *in-memory* H2. Without this line the file database would be created with a blank username, and the H2 console (which suggests `sa`) couldn't log in.
- `ddl-auto=validate`: Hibernate never changes the schema. It only checks at start-up that your entities match the tables Flyway created.
- `open-in-view=false`: the database session closes when the service method's transaction ends, so forgotten lazy loading fails loudly in development instead of quietly running extra queries.

Add `data/` to the project's `.gitignore`:

```
### Local database ###
data/
```

### 3. The `Money` record

Create `money/Money.java` (that's `src/main/java/com/example/cards/money/Money.java`; paths in this tutorial are relative to the base package):

```java
package com.example.cards.money;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money in one currency. Immutable, and always held at the
 * currency's own number of decimal places (2 for GBP).
 */
@JsonAutoDetect(isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public record Money(
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        Currency currency) {

    public static final Currency GBP = Currency.getInstance("GBP");

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        try {
            // UNNECESSARY = "don't round; fail if rounding would be needed"
            amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "%s has too many decimal places for %s".formatted(amount.toPlainString(), currency));
        }
    }

    public static Money of(String amount, Currency currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money gbp(String amount) {
        return of(amount, GBP);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) < 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: %s and %s".formatted(currency, other.currency));
        }
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + amount.toPlainString();
    }
}
```

The essentials, in one breath:

- **The compact constructor** (`public Money { … }`) normalises every amount to the currency's decimal places, so `Money.gbp("10.5")` equals `Money.gbp("10.50")`, and `RoundingMode.UNNECESSARY` makes it **throw** rather than silently round away a fraction of a penny.
- **Static factories take a `String`**, so a `double` can never sneak in.
- **`@JsonFormat(shape = STRING)`** writes the amount as `"10.50"`, not `10.50`. **`@JsonAutoDetect(isGetterVisibility = NONE)`** stops Jackson from treating `isZero()`, `isPositive()` and `isNegative()` as JSON properties.
- **`isLessThan` uses `compareTo`, never `equals`**, because `BigDecimal.equals` also compares scale.

Add a few tests so you know your copy works. `src/test/java/com/example/cards/money/MoneyTest.java`:

```java
package com.example.cards.money;

import org.junit.jupiter.api.Test;

import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void normalisesToTwoDecimalPlaces() {
        assertThat(Money.gbp("10.5")).isEqualTo(Money.gbp("10.50"));
    }

    @Test
    void rejectsFractionsOfAPenny() {
        assertThatThrownBy(() -> Money.gbp("0.001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many decimal places");
    }

    @Test
    void addsSubtractsAndCompares() {
        assertThat(Money.gbp("0.10").plus(Money.gbp("0.20"))).isEqualTo(Money.gbp("0.30"));
        assertThat(Money.gbp("5.00").minus(Money.gbp("7.50"))).isEqualTo(Money.gbp("-2.50"));
        assertThat(Money.gbp("4.99").isLessThan(Money.gbp("5.00"))).isTrue();
    }

    @Test
    void refusesToMixCurrencies() {
        Money euros = Money.of("1.00", Currency.getInstance("EUR"));
        assertThatThrownBy(() -> Money.gbp("1.00").plus(euros))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
    }
}
```

Run them from the gutter arrow next to `class MoneyTest`, or:

```bash
./mvnw test -Dtest=MoneyTest
```

### 4. Inject the clock

Cards expire, holds expire, and daily spending limits reset at midnight. All of that depends on **what time it is**, and code that calls `Instant.now()` directly can't be tested without waiting for real time to pass.

So from the very first line of code, nothing in this application asks the system for the time. It asks a `Clock` bean. Create `config/ClockConfig.java`:

```java
package com.example.cards.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** One clock for the whole application, so tests can replace it. */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

`java.time.Clock` is the standard Java abstraction for "the current time". `Instant.now(clock)` asks the clock instead of the operating system. In production that's the same thing. In guide 05 a test will swap in a clock it can move forward by a week in a single line.

> **In older codebases:** you'll see `new Date()`, `System.currentTimeMillis()` and `LocalDateTime.now()` scattered through services, and tests that either can't check time-based rules at all or use `Thread.sleep`. Some teams wrap time in a home-grown `TimeProvider` interface. `Clock` (Java 8+) is the standard version of that idea.

### 5. The `account` and `card` tables

Create `src/main/resources/db/migration/V1__create_account_and_card.sql`:

```sql
CREATE TABLE account (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name            VARCHAR(100)             NOT NULL,
    currency        VARCHAR(3)               NOT NULL,
    ledger_balance  NUMERIC(19, 4)           NOT NULL,
    overdraft_limit NUMERIC(19, 4)           NOT NULL,
    opened_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_account_overdraft_limit CHECK (overdraft_limit >= 0)
);

CREATE TABLE card (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    token        VARCHAR(36)              NOT NULL,
    account_id   BIGINT                   NOT NULL REFERENCES account (id),
    last_four    VARCHAR(4)               NOT NULL,
    expiry_year  INTEGER                  NOT NULL,
    expiry_month INTEGER                  NOT NULL,
    status       VARCHAR(10)              NOT NULL,
    issued_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_card_token UNIQUE (token),
    CONSTRAINT ck_card_status CHECK (status IN ('ACTIVE', 'BLOCKED'))
);

CREATE INDEX ix_card_account ON card (account_id);
```

The SQL is **portable**: it runs unchanged on H2 and PostgreSQL. A few choices are worth a sentence each:

- **`NUMERIC(19, 4)`** is an exact decimal type. `Money` enforces 2 decimal places for GBP. The extra precision in the column is a common ledger habit.
- **`ledger_balance`** is stored on the account so reading it is instant. In this guide you'll update it directly. In guide 04 it becomes a proper cached balance maintained by double-entry postings.
- **The arranged overdraft limit is per account**, and a `CHECK` constraint makes a negative limit impossible even for a bad SQL script.
- **`token` is unique**, because it's how an authorisation request finds its card. There's no `pan` column, and there never will be.
- **The expiry is two integers**, because a card expires at the end of a *month*, not on a date. The entity turns them into a `java.time.YearMonth`.
- **`ix_card_account`** speeds up "this account's cards". Foreign key columns aren't indexed automatically in PostgreSQL, so it's a good habit to add the index yourself.

### 6. The `Account` entity

Create `account/Account.java`:

```java
package com.example.cards.account;

import com.example.cards.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;

/** A customer's current account, as the card system sees it. */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "currency", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "ledger_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal ledgerBalance;

    @Column(name = "overdraft_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal overdraftLimit;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    protected Account() {
        // for JPA
    }

    private Account(String name, Money overdraftLimit, Instant openedAt) {
        this.name = name;
        this.currencyCode = overdraftLimit.currency().getCurrencyCode();
        this.ledgerBalance = BigDecimal.ZERO;
        this.overdraftLimit = overdraftLimit.amount();
        this.openedAt = openedAt;
    }

    public static Account open(String name, Money overdraftLimit, Instant openedAt) {
        if (overdraftLimit.isNegative()) {
            throw new IllegalArgumentException("Overdraft limit cannot be negative");
        }
        return new Account(name, overdraftLimit, openedAt);
    }

    /** Money arriving in the account (salary, a transfer in). Guide 04 replaces this with a proper posting. */
    public void deposit(Money amount) {
        this.ledgerBalance = getLedgerBalance().plus(amount).amount();
    }

    /**
     * What the customer can spend right now.
     * Guide 02 passes in the total of the account's active holds.
     */
    public Money availableBalance(Money activeHolds) {
        return getLedgerBalance().minus(activeHolds).plus(getOverdraftLimit());
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Currency getCurrency() {
        return Currency.getInstance(currencyCode);
    }

    public Money getLedgerBalance() {
        return new Money(ledgerBalance, getCurrency());
    }

    public Money getOverdraftLimit() {
        return new Money(overdraftLimit, getCurrency());
    }

    public Instant getOpenedAt() {
        return openedAt;
    }
}
```

- **No setters.** The balance only changes through `deposit` (and, from guide 04, through the ledger).
- **The opening time is passed in**, not read from the system clock. The entity doesn't know about Spring, so the service hands it `Instant.now(clock)`.
- **`availableBalance` is the formula from the Concepts section**, in one place. It takes the holds as a parameter because the account doesn't know about holds: they live in their own table (guide 02), and adding them up is a database query, not something an entity should do.
- **Columns are plain types, getters return `Money`.** The database hands back `0.0000`, and the `Money` constructor normalises it to `0.00`.

> **Simplification:** a real issuer's card system rarely owns the ledger balance. It's normally fed from the core banking system, either by calling it or by keeping a synchronised copy. To keep this tutorial standalone, the card system keeps the balance itself.

Unit-test the formula. These tests need no Spring and no database. `src/test/java/com/example/cards/account/AccountTest.java`:

```java
package com.example.cards.account;

import com.example.cards.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    @Test
    void availableBalanceIsLedgerBalanceMinusHoldsPlusOverdraft() {
        Account account = Account.open("Alice", Money.gbp("100.00"), Instant.now());
        account.deposit(Money.gbp("250.00"));

        assertThat(account.availableBalance(Money.gbp("0.00"))).isEqualTo(Money.gbp("350.00"));
        assertThat(account.availableBalance(Money.gbp("80.00"))).isEqualTo(Money.gbp("270.00"));
    }

    @Test
    void availableBalanceCanBeLowerThanLedgerBalance() {
        Account account = Account.open("Bob", Money.gbp("0.00"), Instant.now());
        account.deposit(Money.gbp("50.00"));

        Money available = account.availableBalance(Money.gbp("30.00"));

        assertThat(account.getLedgerBalance()).isEqualTo(Money.gbp("50.00"));
        assertThat(available).isEqualTo(Money.gbp("20.00"));
    }

    @Test
    void refusesANegativeOverdraftLimit() {
        assertThatThrownBy(() -> Account.open("Carol", Money.gbp("-1.00"), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

(`Instant.now()` is fine *in a test* that doesn't care about the time.)

### 7. Account repository, service and controller

`account/AccountRepository.java`:

```java
package com.example.cards.account;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {
}
```

`account/AccountNotFoundException.java`:

```java
package com.example.cards.account;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(Long id) {
        super("No account with id " + id);
    }
}
```

`account/AccountService.java`:

```java
package com.example.cards.account;

import com.example.cards.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final Clock clock;

    public AccountService(AccountRepository accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional
    public Account open(String name, Money overdraftLimit) {
        return accounts.save(Account.open(name, overdraftLimit, Instant.now(clock)));
    }

    @Transactional(readOnly = true)
    public Account get(Long id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Transactional
    public Account deposit(Long id, Money amount) {
        Account account = get(id);
        account.deposit(amount);
        return account;
    }
}
```

`deposit` doesn't call `save`. The account was loaded inside this transaction, so it's a **managed** entity, and Hibernate writes its changes at commit (dirty checking).

> **Look closely at `deposit`.** It reads a row, changes it, and writes it back. Hold that thought until guide 03.

`account/AccountController.java`:

```java
package com.example.cards.account;

import com.example.cards.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.math.BigDecimal;
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
        Account account = accountService.open(request.name(), Money.gbp(request.overdraftLimit().toPlainString()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(account.getId())
                .toUri();
        return ResponseEntity.created(location).body(AccountResponse.from(account));
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable Long id) {
        return AccountResponse.from(accountService.get(id));
    }

    @PostMapping("/{id}/deposits")
    public AccountResponse deposit(@PathVariable Long id, @Valid @RequestBody DepositRequest request) {
        return AccountResponse.from(accountService.deposit(id, Money.gbp(request.amount().toPlainString())));
    }

    public record OpenAccountRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull @PositiveOrZero @Digits(integer = 15, fraction = 2) BigDecimal overdraftLimit) {
    }

    public record DepositRequest(@NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount) {
    }

    public record AccountResponse(Long id, String name, Money ledgerBalance, Money overdraftLimit,
                                  Money availableBalance) {

        static AccountResponse from(Account account) {
            Money noHolds = Money.zero(account.getCurrency());
            return new AccountResponse(account.getId(), account.getName(), account.getLedgerBalance(),
                    account.getOverdraftLimit(), account.availableBalance(noHolds));
        }
    }
}
```

- **Validation happens at the edge.** `@Digits(integer = 15, fraction = 2)` turns `"10.001"` into a `400` with a field error before it reaches `Money` (whose exception would otherwise become a `500`). `@PositiveOrZero` allows an account with no overdraft.
- **The response shows both balances.** With no holds yet, the available balance is just ledger balance plus overdraft. Guide 02 fills in the holds.
- **Deposits** stand in for money arriving from elsewhere (a salary, a transfer). Without them, nobody would have anything to spend.

### 8. Cards

`card/CardStatus.java`:

```java
package com.example.cards.card;

public enum CardStatus {
    ACTIVE,
    BLOCKED
}
```

`card/Card.java`:

```java
package com.example.cards.card;

import com.example.cards.account.Account;
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

import java.time.Instant;
import java.time.YearMonth;

/**
 * A payment card linked to an account. We never hold the real card number
 * (the PAN): only a token that stands in for it, plus the last four digits
 * for display.
 */
@Entity
@Table(name = "card")
public class Card {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36, unique = true)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(name = "last_four", nullable = false, length = 4)
    private String lastFour;

    @Column(name = "expiry_year", nullable = false)
    private int expiryYear;

    @Column(name = "expiry_month", nullable = false)
    private int expiryMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CardStatus status;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    protected Card() {
        // for JPA
    }

    private Card(String token, Account account, String lastFour, YearMonth expiry, Instant issuedAt) {
        this.token = token;
        this.account = account;
        this.lastFour = lastFour;
        this.expiryYear = expiry.getYear();
        this.expiryMonth = expiry.getMonthValue();
        this.status = CardStatus.ACTIVE;
        this.issuedAt = issuedAt;
    }

    public static Card issue(String token, Account account, String lastFour, YearMonth expiry, Instant issuedAt) {
        return new Card(token, account, lastFour, expiry, issuedAt);
    }

    public Long getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public Account getAccount() {
        return account;
    }

    public String getLastFour() {
        return lastFour;
    }

    public YearMonth getExpiry() {
        return YearMonth.of(expiryYear, expiryMonth);
    }

    public CardStatus getStatus() {
        return status;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }
}
```

- **Many cards, one account.** A joint account can have a card for each holder, and a replacement card can be issued while the old one is still live. Both draw on the same balance, which is going to matter a great deal in guide 03.
- **`FetchType.LAZY`** on the `@ManyToOne`, because the JPA default for `@ManyToOne` is `EAGER`, which would load the account every time you touch a card.
- **`YearMonth`** (Java 8+) is exactly the right type for an expiry date. The getter builds it from the two columns.

`card/CardRepository.java`:

```java
package com.example.cards.card;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CardRepository extends JpaRepository<Card, Long> {

    Optional<Card> findByToken(String token);
}
```

`card/CardNotFoundException.java`:

```java
package com.example.cards.card;

public class CardNotFoundException extends RuntimeException {

    public CardNotFoundException(Long id) {
        super("No card with id " + id);
    }
}
```

`card/CardService.java`:

```java
package com.example.cards.card;

import com.example.cards.account.Account;
import com.example.cards.account.AccountService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class CardService {

    private final CardRepository cards;
    private final AccountService accountService;
    private final Clock clock;

    public CardService(CardRepository cards, AccountService accountService, Clock clock) {
        this.cards = cards;
        this.accountService = accountService;
        this.clock = clock;
    }

    @Transactional
    public Card issue(Long accountId) {
        Account account = accountService.get(accountId);
        String token = UUID.randomUUID().toString();
        // Stand-in for the last four digits of a real card number, which we never see
        String lastFour = "%04d".formatted(ThreadLocalRandom.current().nextInt(10_000));
        YearMonth expiry = YearMonth.now(clock).plusYears(3);
        return cards.save(Card.issue(token, account, lastFour, expiry, Instant.now(clock)));
    }

    @Transactional(readOnly = true)
    public Card get(Long id) {
        return cards.findById(id).orElseThrow(() -> new CardNotFoundException(id));
    }
}
```

- **A random UUID as the token.** It reveals nothing about the card or the customer, and it's unguessable. That's the property a token needs.
- **The expiry comes from the injected clock**: three years from the month of issue. (Real issuers choose their own validity periods.)

`card/CardController.java`:

```java
package com.example.cards.card;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.YearMonth;

@RestController
public class CardController {

    private final CardService cardService;

    public CardController(CardService cardService) {
        this.cardService = cardService;
    }

    @PostMapping("/accounts/{accountId}/cards")
    public ResponseEntity<CardResponse> issue(@PathVariable Long accountId) {
        Card card = cardService.issue(accountId);
        return ResponseEntity.created(URI.create("/cards/" + card.getId())).body(CardResponse.from(card));
    }

    @GetMapping("/cards/{id}")
    public CardResponse get(@PathVariable Long id) {
        return CardResponse.from(cardService.get(id));
    }

    public record CardResponse(Long id, Long accountId, String token, String lastFour, YearMonth expiry,
                               CardStatus status) {

        static CardResponse from(Card card) {
            return new CardResponse(card.getId(), card.getAccount().getId(), card.getToken(),
                    card.getLastFour(), card.getExpiry(), card.getStatus());
        }
    }
}
```

`CardResponse.from` runs in the controller, **after** the service's transaction has closed. `card.getAccount()` is an uninitialised lazy proxy at that point, and touching it would normally throw `LazyInitializationException`. `getId()` is the exception to that rule: the proxy was created from the foreign key, so it already knows its own id and doesn't need the database. Ask it for anything else, such as the account name, and it fails (see **Common errors**).

### 9. Errors as `ProblemDetail`, and the first API tests

Create `web/ApiExceptionHandler.java`, which turns validation failures into `ProblemDetail` responses with a field-by-field `errors` map:

```java
package com.example.cards.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;
import java.util.TreeMap;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new TreeMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ProblemDetail problem = ex.getBody();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }
}
```

Extending `ResponseEntityExceptionHandler` switches all of Spring MVC's own errors (bad JSON, a missing parameter, a wrong method) to `ProblemDetail` ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)). The override adds an `errors` extension member so a client can see *which* field was wrong.

Now a meta-annotation that bundles the setup every integration test needs. `src/test/java/com/example/cards/CardsIntegrationTest.java`:

```java
package com.example.cards;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Full application, real Flyway migrations, in-memory H2 (so tests never
 * touch ./data), and a MockMvcTester for calling the API.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:cards-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
public @interface CardsIntegrationTest {
}
```

Every test class that uses `@CardsIntegrationTest` has identical configuration, so Spring builds the application context **once** and reuses it. That keeps the suite fast, and it also means **all integration tests share one database**. Each test must create its own data and only make assertions about that data.

`src/test/java/com/example/cards/account/AccountApiTest.java`:

```java
package com.example.cards.account;

import com.example.cards.CardsIntegrationTest;
import com.example.cards.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

@CardsIntegrationTest
class AccountApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    AccountService accountService;

    @Test
    void opensAnAccountWithAnArrangedOverdraft() {
        MvcTestResult response = open("Alice", "100.00");

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).headers().containsHeader("Location");
        assertThat(response).bodyJson().extractingPath("$.ledgerBalance.amount").isEqualTo("0.00");
        assertThat(response).bodyJson().extractingPath("$.availableBalance.amount").isEqualTo("100.00");
    }

    @Test
    void depositRaisesLedgerAndAvailableBalance() {
        Long id = accountService.open("Bob", Money.gbp("0.00")).getId();

        MvcTestResult response = mvc.post().uri("/accounts/{id}/deposits", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount": "250.00"}
                        """)
                .exchange();

        assertThat(response).hasStatus(HttpStatus.OK);
        assertThat(response).bodyJson().extractingPath("$.ledgerBalance.amount").isEqualTo("250.00");
        assertThat(response).bodyJson().extractingPath("$.availableBalance.amount").isEqualTo("250.00");
    }

    @Test
    void rejectsANegativeOverdraftLimit() {
        MvcTestResult response = open("Carol", "-50.00");

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors.overdraftLimit")
                .isEqualTo("must be greater than or equal to 0");
    }

    private MvcTestResult open(String name, String overdraftLimit) {
        return mvc.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "overdraftLimit": "%s"}
                        """.formatted(name, overdraftLimit))
                .exchange();
    }
}
```

Notice the two styles of setup. `opensAnAccount…` goes through the API, because opening an account is what it's testing. `depositRaises…` opens its account through `AccountService` directly, because the account is just a prerequisite. Using the service for setup keeps each test focused on one endpoint.

And `src/test/java/com/example/cards/card/CardApiTest.java`:

```java
package com.example.cards.card;

import com.example.cards.CardsIntegrationTest;
import com.example.cards.account.AccountService;
import com.example.cards.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

@CardsIntegrationTest
class CardApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    AccountService accountService;

    @Test
    void issuesAnActiveCardWithATokenAndNoCardNumber() {
        Long accountId = accountService.open("Alice", Money.gbp("0.00")).getId();

        MvcTestResult response = mvc.post().uri("/accounts/{id}/cards", accountId).exchange();

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).bodyJson().extractingPath("$.accountId").isEqualTo(accountId.intValue());
        assertThat(response).bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");
        assertThat(response).bodyJson().extractingPath("$.token").asString().hasSize(36);
        assertThat(response).bodyJson().extractingPath("$.lastFour").asString().hasSize(4);
        assertThat(response).bodyJson().doesNotHavePath("$.pan");
    }
}
```

`doesNotHavePath("$.pan")` looks pointless today, because there's no `pan` field anywhere. It's there as a guard. If anyone ever adds the card number to the response, this test fails, and the code review that follows will be an important one.

Run everything: right-click `src/test/java` → **Run 'All Tests'**, or:

```bash
./mvnw test
```

### 10. Try it

Run the app (the green arrow next to `main` in `CardAuthorisationApplication`, or `./mvnw spring-boot:run`). Flyway applies the migration:

```
Migrating schema "PUBLIC" to version "1 - create account and card"
Successfully applied 1 migration to schema "PUBLIC", now at version v1
```

Create `requests.http` in the project root. You'll add to it in every guide:

```http
### Open Alice's account with a £100 arranged overdraft
POST http://localhost:8080/accounts
Content-Type: application/json

{"name": "Alice", "overdraftLimit": "100.00"}

### Her salary arrives
POST http://localhost:8080/accounts/1/deposits
Content-Type: application/json

{"amount": "250.00"}

### Issue her a card
POST http://localhost:8080/accounts/1/cards

> {% client.global.set("cardToken", response.body.token); %}

### Look at the card
GET http://localhost:8080/cards/1

### Look at the account
GET http://localhost:8080/accounts/1
```

**(Ultimate)** IntelliJ's HTTP Client shows a green ▶ beside each `###` request. The `> {% … %}` block is a **response handler**: it saves the new card's token in a variable called `cardToken`, which guide 02's requests use as `{{cardToken}}`.

**Free edition / terminal alternatives:** open `http://localhost:8080/swagger-ui.html`, or use curl:

```bash
curl -i -X POST localhost:8080/accounts -H 'Content-Type: application/json' \
     -d '{"name":"Alice","overdraftLimit":"100.00"}'
curl -X POST localhost:8080/accounts/1/deposits -H 'Content-Type: application/json' -d '{"amount":"250.00"}'
curl -X POST localhost:8080/accounts/1/cards
```

Copy the `token` from the card response somewhere handy. You'll need it in guide 02.

The account after the deposit:

```json
{
  "id": 1,
  "name": "Alice",
  "ledgerBalance": { "amount": "250.00", "currency": "GBP" },
  "overdraftLimit": { "amount": "100.00", "currency": "GBP" },
  "availableBalance": { "amount": "350.00", "currency": "GBP" }
}
```

And the card (your token and last four will differ, and the expiry is three years from today):

```json
{
  "id": 1,
  "accountId": 1,
  "token": "f268105e-70ac-47d3-86ec-efebd7a638a3",
  "lastFour": "1500",
  "expiry": "2029-11",
  "status": "ACTIVE"
}
```

Jackson writes the `YearMonth` as `"2029-11"` with no extra configuration.

Now try `GET http://localhost:8080/cards/99`:

```json
{"timestamp":"2026-11-06T22:52:03.879Z","status":500,"error":"Internal Server Error","path":"/cards/99"}
```

A `500` for something that simply doesn't exist. Exercise 1 fixes that.

---

## Your turn

### Exercise 1: proper 404s

`GET /accounts/999` and `GET /cards/999` both produce a `500`, because nothing handles `AccountNotFoundException` or `CardNotFoundException`.

1. Add `@ExceptionHandler` methods to `ApiExceptionHandler` that turn each into a `404` `ProblemDetail`, with the titles `"Account not found"` and `"Card not found"` and the exception's message as the `detail`.
2. Add a test to each of `AccountApiTest` and `CardApiTest` that requests id `999999` and checks the status, the `application/problem+json` content type and the title.

<details>
<summary>Hint</summary>

`ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage())` builds one. A `@RestControllerAdvice` method that **returns** a `ProblemDetail` gets the right status and content type automatically. The content type constant is `MediaType.APPLICATION_PROBLEM_JSON`.

</details>

<details>
<summary>Solution</summary>

In `ApiExceptionHandler` (import `AccountNotFoundException`, `CardNotFoundException`, `HttpStatus` and `ExceptionHandler`):

```java
    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail handle(AccountNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Account not found");
        return problem;
    }

    @ExceptionHandler(CardNotFoundException.class)
    ProblemDetail handle(CardNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Card not found");
        return problem;
    }
```

In `AccountApiTest`:

```java
    @Test
    void returnsProblemDetailForAnUnknownAccount() {
        MvcTestResult response = mvc.get().uri("/accounts/999999").exchange();

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Account not found");
    }
```

In `CardApiTest` (import `MediaType`):

```java
    @Test
    void returnsProblemDetailForAnUnknownCard() {
        MvcTestResult response = mvc.get().uri("/cards/999999").exchange();

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Card not found");
    }
```

</details>

### Exercise 2: block a card

A customer reports their card lost. The bank must stop it working **immediately**, while leaving the account and any other cards untouched. (Guide 02's authorisation logic will decline any card that isn't `ACTIVE`.)

1. Add a `block()` method to `Card` that sets the status to `BLOCKED`.
2. Add `Card block(Long id)` to `CardService`.
3. Add `POST /cards/{id}/block`, returning the updated card.
4. Add a test to `CardApiTest` that issues a card, blocks it through the API, and checks both the response and the stored status.

<details>
<summary>Solution</summary>

`Card`:

```java
    /** Stops all future authorisations, for example when the customer reports the card lost. */
    public void block() {
        this.status = CardStatus.BLOCKED;
    }
```

`CardService`:

```java
    @Transactional
    public Card block(Long id) {
        Card card = get(id);
        card.block();
        return card;
    }
```

`CardController`:

```java
    @PostMapping("/cards/{id}/block")
    public CardResponse block(@PathVariable Long id) {
        return CardResponse.from(cardService.block(id));
    }
```

`CardApiTest` (add `@Autowired CardService cardService;`):

```java
    @Test
    void blocksACard() {
        Long accountId = accountService.open("Bob", Money.gbp("0.00")).getId();
        Long cardId = cardService.issue(accountId).getId();

        MvcTestResult response = mvc.post().uri("/cards/{id}/block", cardId).exchange();

        assertThat(response).hasStatus(HttpStatus.OK);
        assertThat(response).bodyJson().extractingPath("$.status").isEqualTo("BLOCKED");
        assertThat(cardService.get(cardId).getStatus()).isEqualTo(CardStatus.BLOCKED);
    }
```

`block` calls `get(id)`, which is also `@Transactional`. Both are in the same class, so the inner annotation is ignored (the call doesn't go through Spring's proxy), but that's fine here: `block`'s own transaction covers both.

> **Simplification:** a real issuer distinguishes *why* a card is stopped (lost, stolen, suspected fraud, temporarily frozen by the customer in their app) because each leads to different follow-up and, in the authorisation response, often a different response code. One `BLOCKED` status is enough for this tutorial.

</details>

---

## Checkpoint

All tests pass:

```bash
./mvnw test
```

```
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
```

(4 in `MoneyTest`, 3 in `AccountTest`, 4 in `AccountApiTest` and 3 in `CardApiTest`, if you did both exercises.)

Your project should look like this:

```
card-authorisation/
├── pom.xml
├── requests.http
└── src/
    ├── main/
    │   ├── java/com/example/cards/
    │   │   ├── CardAuthorisationApplication.java
    │   │   ├── account/
    │   │   │   ├── Account.java
    │   │   │   ├── AccountController.java
    │   │   │   ├── AccountNotFoundException.java
    │   │   │   ├── AccountRepository.java
    │   │   │   └── AccountService.java
    │   │   ├── card/
    │   │   │   ├── Card.java
    │   │   │   ├── CardController.java
    │   │   │   ├── CardNotFoundException.java
    │   │   │   ├── CardRepository.java
    │   │   │   ├── CardService.java
    │   │   │   └── CardStatus.java
    │   │   ├── config/
    │   │   │   └── ClockConfig.java
    │   │   ├── money/
    │   │   │   └── Money.java
    │   │   └── web/
    │   │       └── ApiExceptionHandler.java
    │   └── resources/
    │       ├── application.properties
    │       └── db/migration/
    │           └── V1__create_account_and_card.sql
    └── test/java/com/example/cards/
        ├── CardsIntegrationTest.java
        ├── account/
        │   ├── AccountApiTest.java
        │   └── AccountTest.java
        ├── card/
        │   └── CardApiTest.java
        └── money/
            └── MoneyTest.java
```

---

## Common errors

**`Schema validation: wrong column type encountered in column [expiry_year] in table [card]; found [smallint (Types#SMALLINT)], but expecting [integer (Types#INTEGER)]`**
You used `SMALLINT` for the expiry columns. Hibernate maps a Java `int` to `INTEGER`, and `ddl-auto=validate` is strict about the difference. Use `INTEGER`. Because Flyway has already recorded `V1` as applied, either delete the `data/` folder (fine in development) or fix it in a new `V2` migration. **Never edit a migration that has already run.**

**`LazyInitializationException: Could not initialize proxy [com.example.cards.account.Account#2] - no session`**
Something outside a transaction asked the card's lazy `account` for more than its id, for example `card.getAccount().getName()` in `CardResponse.from`. Either load what you need inside the service's transaction, or keep responses to fields the card itself holds.

**`Schema validation: missing table [account]`**
Flyway didn't find your migration. Check the path is exactly `src/main/resources/db/migration/` and that the file name has **two** underscores: `V1__create_account_and_card.sql`.

**`"zero": true` appears in your JSON**
`@JsonAutoDetect(isGetterVisibility = NONE)` is missing from `Money`, so Jackson treats `isZero()` and friends as properties.

**A deposit of `"10.001"` returns `500` instead of `400`**
`@Digits` is missing from `DepositRequest`, so the value reached `Money`, which threw `IllegalArgumentException`.

**`Web server failed to start. Port 8080 was already in use.`**
Something else is using port 8080, often this app running twice (once from IntelliJ, once from the terminal). Stop the other one, or set `server.port=8081` and use that port in `requests.http`.

**`Database may be already in use: ".../data/cards.mv.db"`**
The app is already running somewhere and has the H2 file open. Run it one way at a time.

**`Cannot resolve symbol 'AutoConfigureMockMvc'`**
In Spring Boot 4 it lives in `org.springframework.boot.webmvc.test.autoconfigure`. Older answers online use `org.springframework.boot.test.autoconfigure.web.servlet`.

---

## Recap

- A card payment involves the **cardholder, merchant, acquirer, card scheme and issuer**. You're building the **issuer**: the bank that decides and pays.
- A card payment happens in three stages: **authorisation** (real time, places a hold), **clearing** (the final amount is posted), and **settlement** (banks pay each other in bulk).
- **Available balance = ledger balance − holds + arranged overdraft.** Authorisation decisions use the available balance, and the ledger balance only changes when money is posted.
- **Never store the PAN** unless you must. PCI DSS applies to any system that does, so keep card numbers in as few places as possible and use **tokens** everywhere else.
- **Inject a `Clock`** from day one. Time-based rules are only testable if time can be controlled.
- **Entities guard their state** (no setters, named factories, `Money` out of the getters), **validation happens at the edge**, and **errors are `ProblemDetail`**.

### Interview angle

<details>
<summary>"Walk me through what happens when I tap my card in a shop."</summary>

The terminal sends an authorisation request to the merchant's acquirer, which routes it through the card scheme (Visa or Mastercard) to the issuer, the cardholder's bank. The issuer checks the card (status, expiry), its limits and the account's available balance, then approves or declines with a response code, all in real time. If approved, it places a hold for the amount, which reduces the available balance but doesn't post anything. Later, the merchant's acquirer sends a clearing record (the presentment) with the final amount, and the issuer posts the debit and releases the hold. Settlement between the banks happens in bulk through the scheme.

</details>

<details>
<summary>"What's the difference between ledger balance and available balance?"</summary>

The ledger balance is the total of everything actually posted to the account. The available balance is what the customer can spend now: the ledger balance minus active holds (approved card authorisations that haven't cleared yet), plus any arranged overdraft. Card authorisations are decided against the available balance, so a customer can't spend the same money twice while a payment is pending.

</details>

<details>
<summary>"How would you handle card numbers in a system like this?"</summary>

Keep them out of it. Only a tightly controlled, PCI DSS-scoped component should store or see full card numbers (with encryption and hardware security modules for the keys). Everything else works with a token that maps to the card, plus at most a masked number (typically the last four digits) for display. That shrinks the PCI DSS scope and the damage any breach could do. I'd also add a test that fails if a full card number ever appears in an API response or a log.

</details>

<details>
<summary>"Why inject a Clock instead of calling Instant.now()?"</summary>

So time becomes a dependency you can control. In production it's the system clock. In tests it's a fixed or adjustable clock, so you can test expiry, cut-offs and daily limits deterministically, without sleeping and without tests that fail at midnight. It also makes it explicit which code depends on the time.

</details>

### Further reading

- [Bank of England: How do card payments work?](https://www.bankofengland.co.uk/explainers/how-do-card-payments-work): a short, plain-English walk through a card payment, including how the banks settle with each other
- [PCI Security Standards Council: PCI DSS](https://www.pcisecuritystandards.org/standards/pci-dss/)
- [Visa: rules and fees](https://www.visa.com/en-us/support/business/regulation-fees): where Visa publishes its operating rules and interchange fees
- [`java.time.Clock` Javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/Clock.html)

**Next:** [02: Authorising a card payment](02-authorising-a-payment.md). The first authorisation request arrives, and you'll decide whether Alice can pay.
