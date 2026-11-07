# 01: Open Banking and Account Data

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- Why UK banks have to let other companies read their customers' accounts, and where that obligation came from: [PSD2](../glossary.md#psd2) and the CMA's Open Banking remedy
- The cast of Open Banking: **ASPSP**, **AISP**, **PISP**, **TPP** and **PSU**, and which one you're building
- How a UK Open Banking response is shaped: a `data` payload wrapped with `links` and `meta`
- The difference between a **booked** and an **available** balance, worked out from real transactions
- How to seed a database with demo customers **without** shipping them to production (a second Flyway location)
- How to give every response a **correlation id** (`x-fapi-interaction-id`) with a servlet filter, and put it on every log line

By the end you'll have a read-only JSON API that returns accounts, balances and transactions in an Open Banking style. It will also be wide open: anyone can read anyone's account. That's deliberate. Guides 02 to 04 close it, one layer at a time.

---

## Concepts

### How it works in a real bank

Until the late 2010s, if you wanted a budgeting app to see your current account, you usually gave the app your **online banking password**. The app then logged in as you and copied the numbers off the web pages. This was called **screen scraping**. It worked, but it was terrible for everyone: the app could see (and do) everything you could, the bank couldn't tell the app from you, and you'd broken your bank's terms by sharing your password.

Two changes replaced it with something designed for the job:

1. **PSD2.** The EU's second Payment Services Directive ([Directive (EU) 2015/2366](https://eur-lex.europa.eu/eli/dir/2015/2366/oj)) required banks to give *authorised* third parties access to customers' payment accounts, with the customer's consent, through a proper interface. The UK put it into law as the [Payment Services Regulations 2017](https://www.legislation.gov.uk/uksi/2017/752/contents), which still apply after Brexit.
2. **The CMA Order.** The Competition and Markets Authority's investigation into retail banking found that customers rarely switched banks and that big banks faced too little competition. Its [Retail Banking Market Investigation Order 2017](https://www.gov.uk/government/publications/retail-banking-market-investigation-order-2017) required the nine largest current account providers (known as the **CMA9**) to build open, *standardised* APIs. It also set up the Open Banking Implementation Entity (OBIE) to write the standards. That body now operates as Open Banking Limited.

PSD2 says *that* banks must open up. The UK Open Banking standards say *how*: the same URLs, the same JSON, the same security profile, at every bank that adopts them. That's what lets one budgeting app talk to dozens of banks with one integration. You can browse them at [standards.openbanking.org.uk](https://standards.openbanking.org.uk/).

### Who's who

Open Banking has its own vocabulary, and job adverts and interviews use it freely:

| Term | Stands for | Plain English | In this tutorial |
|---|---|---|---|
| **ASPSP** | Account Servicing Payment Service Provider | The bank that holds the account | **You.** This API is the bank's side. |
| **TPP** | Third Party Provider | Any company accessing the account through Open Banking | A budgeting app called *moneywise* |
| **AISP** | Account Information Service Provider | A TPP that *reads* account data: budgeting apps, lenders checking affordability, accounting software | moneywise is an AISP |
| **PISP** | Payment Initiation Service Provider | A TPP that *starts payments* from the account, such as "pay by bank" at a checkout | Not covered (the Payments tutorial deals with payments) |
| **PSU** | Payment Service User | The customer | Alice and Bob |

A TPP has to be authorised or registered by the **FCA** (or a European regulator) before a bank will let it in. A company can't just turn up and ask for data.

This tutorial builds the **account information** side: the *Accounts and Transactions* API that AISPs call. The UK Read/Write API specification groups its endpoints by role, which is why paths look like `/open-banking/v3.1/aisp/accounts`.

### The flow you're working towards

Here's the whole journey. You'll build a piece of it in each guide:

```
  moneywise (AISP)            Your bank (ASPSP)                    Alice (PSU)
        │                            │                                  │
        │ 1. "I'd like to read Alice's balances and transactions"       │
        │──── POST /account-access-consents ──▶  status: AwaitingAuthorisation
        │                            │                                  │
        │ 2. sends Alice to the bank │◀─── logs in, strong customer ────│
        │                            │     authentication, picks        │
        │                            │     accounts, says yes           │
        │                            │  status: Authorised              │
        │ 3. gets an access token linked to that consent                │
        │──── GET /accounts/{id}/balances ──▶ checks token AND consent  │
        │◀─── balances ──────────────│                                  │
```

- **Guide 01 (this one):** the data endpoints themselves, unprotected.
- **Guide 02:** OAuth 2.0 access tokens, so only callers with a valid token get in.
- **Guide 03:** the consent resource and its lifecycle (step 1 and step 2).
- **Guide 04:** every data request checks the consent (step 3), and revocation works instantly, even under concurrency.
- **Guide 05:** releasing a version 2 of the API without breaking version 1 clients.

### The shape of an Open Banking response

Every response in the UK Read/Write API is wrapped the same way: the payload in `Data`, a mandatory `Links` object with at least a `Self` link, and a mandatory (but possibly empty) `Meta` object that can carry `TotalPages` for paged results. Lists live inside `Data` under a singular name, so a list of accounts is `Data.Account`.

This tutorial uses the same shape with one simplification: **property names are camelCase** (`data`, `links`, `self`, `accountId`) rather than the standard's PascalCase (`Data`, `Links`, `Self`, `AccountId`). That keeps the Java records plain. It also leaves out many optional fields. Treat the JSON here as *modelled on* the standard, not conforming to it.

```json
{
  "data": { "account": [ { "accountId": "acc-1001", "currency": "GBP", "...": "..." } ] },
  "links": { "self": "http://localhost:8080/open-banking/v1/aisp/accounts" },
  "meta": { "totalPages": 1 }
}
```

### Booked and available balances

The balances endpoint returns more than one balance, because "how much is in my account?" has more than one answer:

- **Booked balance** (the standard calls it `InterimBooked` during the day): everything actually posted to the account. This is the [ledger balance](../glossary.md#ledger-balance).
- **Available balance** (`InterimAvailable`): what the customer can spend right now. This is the [available balance](../glossary.md#available-balance). In this tutorial it's the booked balance minus **pending** payments out, such as a card payment that has been approved but not yet settled.

Pending payments *in* are typically not counted until they're booked. Banks are cautious about letting you spend money that hasn't actually arrived.

The UK standard defines several more balance types (`ClosingBooked`, `Expected`, `OpeningAvailable` and others) and lets a bank include credit lines such as an arranged overdraft. Two are enough to show the idea.

> **Sidebar: where does an ASPSP's data really come from?** The Open Banking API is a *façade*. It doesn't own any money. In a real bank it reads from the core banking system (the ledger), the card authorisation system (for pending transactions) and the customer database. The [Core ledger](../core-ledger/README.md) tutorial covers how balances are really kept. Here, a small table of seeded transactions stands in for all of that.

### `x-fapi-interaction-id`: one id per conversation

When a TPP calls a bank and something goes wrong, the two companies need to find the *same* request in their separate logs. The UK standard handles this with the `x-fapi-interaction-id` header (the name comes from FAPI, the security profile you'll meet in guide 02):

- The TPP *may* send one: a UUID.
- If it does, the bank **must play it back** in the response.
- If it doesn't, the bank **must generate one** and return it.
- It's returned on **every** response, successes and errors alike.

You'll implement that with a servlet filter, and put the id on every log line too.

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
| Artifact | `open-banking-api` |
| Package name | `com.example.openbanking` |
| Packaging | Jar |
| Java | 25 |

Add these dependencies:

| Dependency | Why |
|---|---|
| **Spring Web** | REST controllers (it pulls in `spring-boot-starter-webmvc`) |
| **Spring Data JPA** | Entities and repositories |
| **Flyway Migration** | Versioned SQL migrations |
| **H2 Database** | The embedded database |
| **Validation** | `@Valid`, `@NotBlank` (used from guide 02) |
| **SpringDoc OpenAPI** | Swagger UI, for trying the API in a browser |

You'll add the OAuth2 resource server dependency yourself in guide 02, so you can see exactly what it changes.

Click **Generate** and unzip the download so the project folder ends up at:

```
tutorials/workspace/banking-systems/open-banking-api/
```

Open it in IntelliJ with **File → Open…**, choose the `open-banking-api` folder (the one containing `pom.xml`), and trust the project. Check **File → Project Structure → Project → SDK** is set to a Java 25 JDK.

Delete the generated test `src/test/java/com/example/openbanking/OpenBankingApiApplicationTests.java`. You'll replace it with better tests in step 9.

> **Note:** in Spring Boot 4, choosing H2 also adds `spring-boot-h2console` to the `pom.xml`. This tutorial doesn't switch the console on, because from guide 02 every URL sits behind token security, and the console would need its own exceptions. Leave the dependency or delete it; either is fine.

### 2. Configure the application

Replace `src/main/resources/application.properties` with:

```properties
spring.application.name=open-banking-api

# H2 database stored in ./data/openbanking.mv.db
spring.datasource.url=jdbc:h2:file:./data/openbanking
spring.datasource.username=sa

# Flyway owns the schema; Hibernate only checks it matches the entities
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false

# Schema migrations, plus made-up demo customers (a real deployment would leave db/demo out)
spring.flyway.locations=classpath:db/migration,classpath:db/demo
```

The first block is the series standard: `username=sa` so the file database gets the usual H2 user, `ddl-auto=validate` so Hibernate checks your entities against the tables Flyway created but never changes them, and `open-in-view=false` so lazy loading outside a transaction fails loudly. The [Core ledger guide 01](../core-ledger/01-accounts-and-money.md#2-configure-the-application) explains each one in more depth.

The last line is new. Flyway normally reads migrations from `db/migration` only. Here you give it **two** locations:

- `db/migration`: the schema. Every environment gets it.
- `db/demo`: made-up customers for local development and tests.

A production deployment would set `spring.flyway.locations=classpath:db/migration` and never see the demo data. Real customer data never comes from a migration script.

Add `data/` to the project's `.gitignore`:

```
### Local database ###
data/
```

### 3. The `Money` record

Every system in this series has its own copy of a small `Money` type, so each one stands alone. Create `money/Money.java` (paths like this mean `src/main/java/com/example/openbanking/money/Money.java`):

```java
package com.example.openbanking.money;

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

The short version of why it looks like this:

- **`BigDecimal`, never `double`,** because binary floating point can't hold most decimal fractions exactly.
- **The compact constructor fixes the scale** to the currency's decimal places (2 for GBP) with `RoundingMode.UNNECESSARY`, so `10.5` becomes `10.50`, `equals` works, and a fraction of a penny is *refused* rather than silently rounded away.
- **`@JsonFormat(shape = STRING)`** sends amounts as `"10.50"`, a string, which is exactly what the UK Open Banking standard does. JSON numbers are read as `double` by many clients.
- **`@JsonAutoDetect(isGetterVisibility = NONE)`** stops Jackson treating `isZero()`, `isPositive()` and `isNegative()` as properties and leaking `"zero": true` into the API.

The Core ledger tutorial covers all of this properly, with the traps that motivate each line, in its [guide 01](../core-ledger/01-accounts-and-money.md#why-money-is-never-a-double). Here you only need to trust it.

Pin its behaviour with a test, `src/test/java/com/example/openbanking/money/MoneyTest.java`:

```java
package com.example.openbanking.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void normalisesToTwoDecimalPlaces() {
        assertThat(Money.gbp("10").amount()).isEqualTo(new BigDecimal("10.00"));
        assertThat(Money.gbp("10.5")).isEqualTo(Money.gbp("10.50"));
    }

    @Test
    void rejectsFractionsOfAPenny() {
        assertThatThrownBy(() -> Money.gbp("0.001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many decimal places");
    }

    @Test
    void refusesToMixCurrencies() {
        Money euros = Money.of("1.00", Currency.getInstance("EUR"));
        assertThatThrownBy(() -> Money.gbp("1.00").plus(euros))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    void addsAndSubtracts() {
        assertThat(Money.gbp("0.10").plus(Money.gbp("0.20"))).isEqualTo(Money.gbp("0.30"));
        assertThat(Money.gbp("5.00").minus(Money.gbp("7.50"))).isEqualTo(Money.gbp("-2.50"));
        assertThat(Money.gbp("2.50").negate()).isEqualTo(Money.gbp("-2.50"));
        assertThat(Money.gbp("1.00").isLessThan(Money.gbp("1.01"))).isTrue();
    }
}
```

**Run it in IntelliJ:** click the green arrow in the gutter next to `class MoneyTest` → **Run 'MoneyTest'**. **Terminal alternative:** `./mvnw test -Dtest=MoneyTest`. Four green ticks.

### 4. The schema and the demo customers

Create `src/main/resources/db/migration/V1__create_accounts.sql`:

```sql
CREATE TABLE account (
    id               VARCHAR(40)  PRIMARY KEY,
    customer_id      VARCHAR(40)  NOT NULL,
    account_type     VARCHAR(20)  NOT NULL,
    account_sub_type VARCHAR(20)  NOT NULL,
    currency         VARCHAR(3)   NOT NULL,
    nickname         VARCHAR(70),
    holder_name      VARCHAR(140) NOT NULL,
    sort_code        VARCHAR(6)   NOT NULL,
    account_number   VARCHAR(8)   NOT NULL
);

CREATE INDEX ix_account_customer ON account (customer_id);

CREATE TABLE account_transaction (
    id              VARCHAR(40)              PRIMARY KEY,
    account_id      VARCHAR(40)              NOT NULL REFERENCES account (id),
    credit_debit    VARCHAR(6)               NOT NULL,
    status          VARCHAR(10)              NOT NULL,
    amount          NUMERIC(19, 4)           NOT NULL,
    booked_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    information     VARCHAR(140)             NOT NULL
);

CREATE INDEX ix_transaction_account ON account_transaction (account_id, booked_at);
```

A few deliberate choices:

- **The account id is a string, not the sort code and account number.** The UK standard has the bank issue an opaque `AccountId` for each account. The sort code and account number are *data* about the account (you'll return them, but only to TPPs with the right permission, in guide 04), not its key. Exposing them as ids would leak them in every URL and log line.
- **`amount` is always positive**, with a separate `credit_debit` column saying which way the money moved. That's how Open Banking represents transactions: `"Amount": "42.50"` plus `"CreditDebitIndicator": "Debit"`, never `-42.50`. (The Core ledger tutorial explains why banks think in debits and credits.)
- **The table is `account_transaction`, not `transaction`.** `TRANSACTION` is an SQL keyword, and naming a table after one invites quoting problems on some databases.
- **`TIMESTAMP WITH TIME ZONE`, `NUMERIC(19, 4)` and `VARCHAR`** are portable: this file runs unchanged on H2 and PostgreSQL.

Now the demo data, in a **different folder**: `src/main/resources/db/demo/V2__demo_customers.sql`:

```sql
-- Made-up customers for local development and tests. Never real data.
INSERT INTO account (id, customer_id, account_type, account_sub_type, currency, nickname, holder_name, sort_code, account_number)
VALUES ('acc-1001', 'alice', 'PERSONAL', 'CURRENT_ACCOUNT', 'GBP', 'Bills',     'Alice Smith', '112233', '12345678'),
       ('acc-1002', 'alice', 'PERSONAL', 'SAVINGS',         'GBP', 'Rainy day', 'Alice Smith', '112233', '87654321'),
       ('acc-2001', 'bob',   'PERSONAL', 'CURRENT_ACCOUNT', 'GBP', NULL,        'Bob Jones',   '112233', '11112222');

INSERT INTO account_transaction (id, account_id, credit_debit, status, amount, booked_at, information)
VALUES ('txn-1', 'acc-1001', 'CREDIT', 'BOOKED',  2500.00, TIMESTAMP WITH TIME ZONE '2026-10-01 09:00:00+00:00', 'Salary ACME LTD'),
       ('txn-2', 'acc-1001', 'DEBIT',  'BOOKED',   950.00, TIMESTAMP WITH TIME ZONE '2026-10-02 08:30:00+00:00', 'Rent'),
       ('txn-3', 'acc-1001', 'DEBIT',  'BOOKED',    42.50, TIMESTAMP WITH TIME ZONE '2026-10-03 18:12:00+00:00', 'Tesco Stores'),
       ('txn-4', 'acc-1001', 'DEBIT',  'PENDING',   12.30, TIMESTAMP WITH TIME ZONE '2026-11-05 08:05:00+00:00', 'Corner Coffee'),
       ('txn-5', 'acc-1002', 'CREDIT', 'BOOKED',  5000.00, TIMESTAMP WITH TIME ZONE '2026-09-15 12:00:00+00:00', 'Opening deposit'),
       ('txn-6', 'acc-2001', 'CREDIT', 'BOOKED',   100.00, TIMESTAMP WITH TIME ZONE '2026-10-04 10:00:00+00:00', 'Refund'),
       ('txn-7', 'acc-2001', 'DEBIT',  'BOOKED',   180.00, TIMESTAMP WITH TIME ZONE '2026-10-06 16:45:00+00:00', 'Garage repairs'),
       ('txn-8', 'acc-2001', 'DEBIT',  'PENDING',    5.00, TIMESTAMP WITH TIME ZONE '2026-11-05 12:20:00+00:00', 'Parking');
```

Flyway numbers versions across **all** its locations, so the demo file is `V2`, not `V1`. The next schema migration (guide 03) will be `V3`, back in `db/migration`.

`TIMESTAMP WITH TIME ZONE '…+00:00'` is the SQL standard way to write a timestamp literal, and it means the same instant on every database regardless of the server's time zone.

Work out the numbers now, because the tests will check them:

| Account | Booked | Pending out | Available |
|---|---|---|---|
| `acc-1001` (Alice, current) | 2500.00 − 950.00 − 42.50 = **1507.50** | 12.30 | **1495.20** |
| `acc-1002` (Alice, savings) | **5000.00** | 0.00 | **5000.00** |
| `acc-2001` (Bob, current) | 100.00 − 180.00 = **−80.00** | 5.00 | **−85.00** |

Bob is in [overdraft](../glossary.md#overdraft). You'll need that in guide 05.

> **Sidebar: why are the sort codes made up?** Every real sort code belongs to a real bank branch, and test data has a way of escaping into screenshots and bug reports. `112233` is just six digits that look the part. Never put real account details, even your own, into a seed file.

### 5. The enums

The UK standard writes codes in PascalCase (`CurrentAccount`, `InterimBooked`, `Debit`). Java constants are UPPER_SNAKE_CASE. Bridge the two with `@JsonValue`: Jackson uses whatever that method returns as the JSON value (and also accepts it when reading JSON back in).

`account/AccountType.java`:

```java
package com.example.openbanking.account;

import com.fasterxml.jackson.annotation.JsonValue;

public enum AccountType {
    PERSONAL("Personal"),
    BUSINESS("Business");

    private final String code;

    AccountType(String code) {
        this.code = code;
    }

    /** The value used in the JSON, in the style of the UK standard. */
    @JsonValue
    public String code() {
        return code;
    }
}
```

The other four follow exactly the same pattern. Create them yourself, using these constants and codes:

| File | Constants → JSON codes |
|---|---|
| `account/AccountSubType.java` | `CURRENT_ACCOUNT` → `CurrentAccount`, `SAVINGS` → `Savings` |
| `account/CreditDebit.java` | `CREDIT` → `Credit`, `DEBIT` → `Debit` |
| `account/TransactionStatus.java` | `BOOKED` → `Booked`, `PENDING` → `Pending` |
| `account/BalanceType.java` | `INTERIM_BOOKED` → `InterimBooked`, `INTERIM_AVAILABLE` → `InterimAvailable` |

<details>
<summary>Check your <code>TransactionStatus</code></summary>

```java
package com.example.openbanking.account;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TransactionStatus {
    /** Posted to the ledger. Counts towards the booked balance. */
    BOOKED("Booked"),
    /** Authorised but not yet posted, such as a card payment waiting to settle. */
    PENDING("Pending");

    private final String code;

    TransactionStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
```

</details>

`@JsonValue` changes the JSON only. The **database** still stores the constant's name (`CURRENT_ACCOUNT`), because the entities use `@Enumerated(EnumType.STRING)`, which always uses `name()`. That's why the seed file says `'CURRENT_ACCOUNT'`, not `'CurrentAccount'`.

### 6. Read-only entities

The Open Banking API never changes an account or a transaction. Say so in the code. `account/Account.java`:

```java
package com.example.openbanking.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.util.Currency;

/** A customer's account, as the Open Banking API sees it: read-only. */
@Entity
@Table(name = "account")
@Immutable
public class Account {

    @Id
    private String id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_sub_type", nullable = false)
    private AccountSubType accountSubType;

    @Column(name = "currency", nullable = false)
    private String currencyCode;

    private String nickname;

    @Column(name = "holder_name", nullable = false)
    private String holderName;

    @Column(name = "sort_code", nullable = false)
    private String sortCode;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    protected Account() {
        // for JPA
    }

    public String getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public AccountSubType getAccountSubType() {
        return accountSubType;
    }

    public Currency getCurrency() {
        return Currency.getInstance(currencyCode);
    }

    public String getNickname() {
        return nickname;
    }

    public String getHolderName() {
        return holderName;
    }

    public String getSortCode() {
        return sortCode;
    }

    public String getAccountNumber() {
        return accountNumber;
    }
}
```

- **`@Immutable`** (Hibernate's own annotation) tells Hibernate never to write changes to this entity back to the database. There are no setters, and no public constructor, so nothing in this application can create or modify an account.
- **The id is assigned by the bank, not generated.** No `@GeneratedValue`.

`account/AccountTransaction.java`:

```java
package com.example.openbanking.account;

import com.example.openbanking.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "account_transaction")
@Immutable
public class AccountTransaction {

    @Id
    private String id;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "credit_debit", nullable = false)
    private CreditDebit creditDebit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    /** Always positive. The direction is in {@link #creditDebit}. */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "booked_at", nullable = false)
    private Instant bookedAt;

    @Column(nullable = false)
    private String information;

    protected AccountTransaction() {
        // for JPA
    }

    public String getId() {
        return id;
    }

    public String getAccountId() {
        return accountId;
    }

    public CreditDebit getCreditDebit() {
        return creditDebit;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public Money getAmount() {
        return Money.gbp(amount.toPlainString());
    }

    /** Positive for money in, negative for money out. */
    public Money getSignedAmount() {
        return creditDebit == CreditDebit.CREDIT ? getAmount() : getAmount().negate();
    }

    public Instant getBookedAt() {
        return bookedAt;
    }

    public String getInformation() {
        return information;
    }
}
```

`getAmount()` builds GBP directly because everything in this series is GBP. The column is `NUMERIC(19, 4)`, so the database hands back `42.5000`, and `Money` normalises it to `42.50`.

`account_id` is a plain `String` rather than a `@ManyToOne Account`. This API never needs to walk from a transaction to its account, and a plain column keeps the queries obvious.

### 7. Balances, repositories and the service

`account/Balance.java` works out both balances from a list of transactions:

```java
package com.example.openbanking.account;

import com.example.openbanking.money.Money;

import java.util.List;

public record Balance(BalanceType type, Money amount) {

    /**
     * Works out both balances from an account's transactions. A real ASPSP reads
     * these from its core banking system instead.
     */
    public static List<Balance> from(List<AccountTransaction> transactions) {
        Money booked = Money.zero(Money.GBP);
        Money pendingOut = Money.zero(Money.GBP);
        for (AccountTransaction t : transactions) {
            if (t.getStatus() == TransactionStatus.BOOKED) {
                booked = booked.plus(t.getSignedAmount());
            } else if (t.getCreditDebit() == CreditDebit.DEBIT) {
                pendingOut = pendingOut.plus(t.getAmount());
            }
            // Pending credits are ignored until they're booked.
        }
        return List.of(
                new Balance(BalanceType.INTERIM_BOOKED, booked),
                new Balance(BalanceType.INTERIM_AVAILABLE, booked.minus(pendingOut)));
    }
}
```

`account/AccountRepository.java`:

```java
package com.example.openbanking.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountRepository extends JpaRepository<Account, String> {

    List<Account> findAllByOrderById();
}
```

`account/AccountTransactionRepository.java`:

```java
package com.example.openbanking.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, String> {

    List<AccountTransaction> findByAccountIdOrderByBookedAtDesc(String accountId);
}
```

`account/AccountNotFoundException.java`:

```java
package com.example.openbanking.account;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String accountId) {
        super("No account with id " + accountId);
    }
}
```

`account/AccountService.java`:

```java
package com.example.openbanking.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository accounts;
    private final AccountTransactionRepository transactions;

    public AccountService(AccountRepository accounts, AccountTransactionRepository transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    public List<Account> allAccounts() {
        return accounts.findAllByOrderById();
    }

    public Account get(String accountId) {
        return accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    public List<AccountTransaction> transactions(String accountId) {
        get(accountId); // 404 for an unknown account, rather than an empty list
        return transactions.findByAccountIdOrderByBookedAtDesc(accountId);
    }

    public List<Balance> balances(String accountId) {
        return Balance.from(transactions(accountId));
    }
}
```

`@Transactional(readOnly = true)` on the **class** applies to every public method. It documents that this service never writes, and lets Hibernate skip its end-of-transaction dirty checking.

`allAccounts()` returns **every customer's** accounts. That is exactly the bug the rest of this tutorial exists to fix. For now it lets you see the data.

### 8. The response envelope and the controller

Create `api/ObResponse.java`, the envelope every response comes in:

```java
package com.example.openbanking.api;

import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The envelope every response comes in, loosely modelled on the UK Open Banking
 * Read/Write API: the payload in "data", plus "links" and "meta".
 */
public record ObResponse<T>(T data, Links links, Meta meta) {

    public record Links(String self) {
    }

    public record Meta(int totalPages) {
    }

    /** Wraps a single-page payload, with a self link to the current request. */
    public static <T> ObResponse<T> of(T data) {
        String self = ServletUriComponentsBuilder.fromCurrentRequest().toUriString();
        return new ObResponse<>(data, new Links(self), new Meta(1));
    }
}
```

A **generic record**: `ObResponse<AccountsData>`, `ObResponse<BalancesData>` and so on all share one definition. `ServletUriComponentsBuilder.fromCurrentRequest()` rebuilds the full URL of the request being handled, which is what `self` should be.

Now `account/AccountController.java`:

```java
package com.example.openbanking.account;

import com.example.openbanking.api.ObResponse;
import com.example.openbanking.money.Money;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/open-banking/v1/aisp")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/accounts")
    public ObResponse<AccountsData> accounts() {
        return ObResponse.of(new AccountsData(
                accountService.allAccounts().stream().map(AccountResponse::from).toList()));
    }

    @GetMapping("/accounts/{accountId}")
    public ObResponse<AccountsData> account(@PathVariable String accountId) {
        return ObResponse.of(new AccountsData(List.of(AccountResponse.from(accountService.get(accountId)))));
    }

    @GetMapping("/accounts/{accountId}/balances")
    public ObResponse<BalancesData> balances(@PathVariable String accountId) {
        Instant now = Instant.now();
        return ObResponse.of(new BalancesData(accountService.balances(accountId).stream()
                .map(b -> new BalanceResponse(accountId, b.type(), b.amount(), now))
                .toList()));
    }

    @GetMapping("/accounts/{accountId}/transactions")
    public ObResponse<TransactionsData> transactions(@PathVariable String accountId) {
        return ObResponse.of(new TransactionsData(accountService.transactions(accountId).stream()
                .map(TransactionResponse::from)
                .toList()));
    }

    public record AccountsData(List<AccountResponse> account) {
    }

    public record AccountResponse(String accountId, String currency, AccountType accountType,
                                  AccountSubType accountSubType, String nickname,
                                  AccountIdentification account) {

        static AccountResponse from(Account a) {
            return new AccountResponse(a.getId(), a.getCurrency().getCurrencyCode(),
                    a.getAccountType(), a.getAccountSubType(), a.getNickname(),
                    new AccountIdentification("UK.OBIE.SortCodeAccountNumber",
                            a.getSortCode() + a.getAccountNumber(), a.getHolderName()));
        }
    }

    /** How the account is identified for payments: sort code and account number. */
    public record AccountIdentification(String schemeName, String identification, String name) {
    }

    public record BalancesData(List<BalanceResponse> balance) {
    }

    public record BalanceResponse(String accountId, BalanceType type, Money amount, Instant dateTime) {
    }

    public record TransactionsData(List<TransactionResponse> transaction) {
    }

    public record TransactionResponse(String transactionId, String accountId,
                                      CreditDebit creditDebitIndicator, TransactionStatus status,
                                      Instant bookingDateTime, Money amount,
                                      String transactionInformation) {

        static TransactionResponse from(AccountTransaction t) {
            return new TransactionResponse(t.getId(), t.getAccountId(), t.getCreditDebit(),
                    t.getStatus(), t.getBookedAt(), t.getAmount(), t.getInformation());
        }
    }
}
```

Points worth noticing:

- **`/open-banking/v1/aisp`** follows the standard's path structure: `/open-banking/[version]/[resource-group]/[resource]`, where the resource group is named after the TPP role (`aisp`). Guide 05 turns the hard-coded `v1` into real API versioning.
- **A single account still comes back as a list** (`data.account[0]`). The standard does this everywhere, so clients parse one shape.
- **The account's `account` field** is the standard's (admittedly confusing) name for *how the account is identified for payments*. The scheme `UK.OBIE.SortCodeAccountNumber` means "a 14-digit sort code followed by account number". It's sensitive data, which matters in guide 04.
- **Response records are nested in the controller**, like the core ledger's. Move them out when they grow.

Add a 404 for unknown accounts. `web/ApiExceptionHandler.java`:

```java
package com.example.openbanking.web;

import com.example.openbanking.account.AccountNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail handle(AccountNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Account not found");
        return problem;
    }
}
```

Extending `ResponseEntityExceptionHandler` switches all of Spring MVC's own errors (bad JSON, a missing header) to [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) `ProblemDetail` responses too. Core ledger guide 01 explains the class in detail.

> **Sidebar: the UK standard's own error format.** The Read/Write API defines its own error body: a `Code`, an optional `Id`, a `Message` and an `Errors` array whose items carry an `ErrorCode` (such as `UK.OBIE.Field.Invalid`), a `Message` and a `Path`. This series uses `ProblemDetail` everywhere for consistency. If you join a bank's Open Banking team, you'll implement their error format instead. The idea (a machine-readable code plus a human-readable message) is identical.

### 9. Run it

Run the app: open `OpenBankingApiApplication.java` and click the green arrow next to `main`. **Terminal alternative:** `./mvnw spring-boot:run`.

Watch Flyway apply **both** locations:

```
Migrating schema "PUBLIC" to version "1 - create accounts"
Migrating schema "PUBLIC" to version "2 - demo customers"
Successfully applied 2 migrations to schema "PUBLIC", now at version v2
```

Create `requests.http` in the project root. You'll add to it in every guide:

```http
### List every account (wide open, for now)
GET http://localhost:8080/open-banking/v1/aisp/accounts

### One account
GET http://localhost:8080/open-banking/v1/aisp/accounts/acc-1001
x-fapi-interaction-id: 93bac548-d2de-4546-b106-880a5018460d

### Balances
GET http://localhost:8080/open-banking/v1/aisp/accounts/acc-1001/balances

### Transactions, newest first
GET http://localhost:8080/open-banking/v1/aisp/accounts/acc-1001/transactions

### An account that doesn't exist (404)
GET http://localhost:8080/open-banking/v1/aisp/accounts/acc-9999
```

**(Ultimate)** Click the green ▶ beside a `###` request. **Free edition / terminal alternatives:** open `http://localhost:8080/swagger-ui.html` and use **Try it out**, or curl:

```bash
curl -s localhost:8080/open-banking/v1/aisp/accounts/acc-1001/balances
```

You get:

```json
{
  "data": {
    "balance": [
      {
        "accountId": "acc-1001",
        "type": "InterimBooked",
        "amount": { "amount": "1507.50", "currency": "GBP" },
        "dateTime": "2026-11-06T22:54:11.763826153Z"
      },
      {
        "accountId": "acc-1001",
        "type": "InterimAvailable",
        "amount": { "amount": "1495.20", "currency": "GBP" },
        "dateTime": "2026-11-06T22:54:11.763826153Z"
      }
    ]
  },
  "links": { "self": "http://localhost:8080/open-banking/v1/aisp/accounts/acc-1001/balances" },
  "meta": { "totalPages": 1 }
}
```

The £12.30 coffee is pending, so it's already out of the *available* balance but not yet out of the *booked* one. The transactions endpoint shows it at the top, with `"status": "Pending"`.

Now try `GET /open-banking/v1/aisp/accounts` and look at what you get back: **Alice's and Bob's accounts, sort codes and all, to anyone who asks.** Hold that thought.

### 10. The interaction id filter

Create `api/FapiInteractionIdFilter.java`:

```java
package com.example.openbanking.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Every response carries an x-fapi-interaction-id: the caller's own value played
 * back if it sent one, otherwise a fresh UUID. It also goes into the logging
 * context, so one id ties together the TPP's logs and ours.
 */
@Component
public class FapiInteractionIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "x-fapi-interaction-id";
    private static final String MDC_KEY = "interactionId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String interactionId = request.getHeader(HEADER);
        if (interactionId == null || interactionId.isBlank()) {
            interactionId = UUID.randomUUID().toString();
        }
        response.setHeader(HEADER, interactionId);
        MDC.put(MDC_KEY, interactionId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
```

- **A `@Component` that extends `OncePerRequestFilter`** is registered automatically by Spring Boot as a servlet filter. It runs *before* any controller, so it sees every request, including ones that will end in an error.
- **The header is set before `chain.doFilter`.** Once a controller starts writing the body, the headers are committed and can't be added any more.
- **`MDC`** (Mapped Diagnostic Context) is SLF4J's per-thread map of values that log lines can include. The `finally` block matters: servlet containers reuse threads, so a value left behind would be stamped on some *other* request's logs.

Tell Logback to print it. Add to `application.properties` (note the **space** at the end of the value, which separates the id from the logger name):

```properties
# Put the x-fapi-interaction-id on every log line written while handling a request
logging.pattern.correlation=[%X{interactionId:-}] 
```

`logging.pattern.correlation` is Spring Boot's slot in its default log format for a correlation id. `%X{interactionId:-}` prints the MDC value, or nothing if there isn't one. Restart, and every line now has a `[…]` section: empty for start-up lines, and filled with the interaction id for anything logged while handling a request:

```
2026-11-06T23:01:36.072Z DEBUG 122577 --- [open-banking-api] [nio-8085-exec-7] [8a0e7b5b-d37b-4d2a-8eb6-6fba28a282f6] o.s.web.servlet.DispatcherServlet        : POST "/dev/tokens", parameters={}
```

(That line is from guide 02, with `DispatcherServlet` debug logging switched on. With the default log levels, a successful request logs nothing at all.)

Send the "One account" request, which includes a fixed id, and look at the response headers:

```
HTTP/1.1 200
x-fapi-interaction-id: 93bac548-d2de-4546-b106-880a5018460d
Content-Type: application/json
```

> **Sidebar: should the bank trust the caller's id?** The id is for correlation only, never for security decisions, so playing it back is harmless. A stricter implementation would check it really is a UUID (the standard says it's an RFC 4122 UUID) and reject or replace anything else, which also stops a caller filling your logs with junk.

### 11. API tests

As in every system in this series, bundle the test setup into one meta-annotation. Create `src/test/java/com/example/openbanking/OpenBankingIntegrationTest.java`:

```java
package com.example.openbanking;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Full application, real Flyway migrations (including the demo customers),
 * in-memory H2 so tests never touch ./data, and a MockMvcTester.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:openbanking-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
public @interface OpenBankingIntegrationTest {
}
```

Because `spring.flyway.locations` includes `db/demo`, the in-memory test database gets Alice and Bob too, and the tests can rely on their numbers.

`src/test/java/com/example/openbanking/account/AccountApiTest.java`:

```java
package com.example.openbanking.account;

import com.example.openbanking.OpenBankingIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

@OpenBankingIntegrationTest
class AccountApiTest {

    private static final String BASE = "/open-banking/v1/aisp";

    @Autowired
    MockMvcTester mvc;

    @Test
    void listsAccountsInsideTheDataEnvelope() {
        var response = mvc.get().uri(BASE + "/accounts");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.data.account[*].accountId")
                .asArray().containsExactly("acc-1001", "acc-1002", "acc-2001");
        assertThat(response).bodyJson().extractingPath("$.links.self")
                .isEqualTo("http://localhost" + BASE + "/accounts");
        assertThat(response).bodyJson().extractingPath("$.meta.totalPages").isEqualTo(1);
    }

    @Test
    void showsHowAnAccountIsIdentified() {
        var response = mvc.get().uri(BASE + "/accounts/acc-1001");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.data.account[0].accountSubType")
                .isEqualTo("CurrentAccount");
        assertThat(response).bodyJson().extractingPath("$.data.account[0].account.schemeName")
                .isEqualTo("UK.OBIE.SortCodeAccountNumber");
        assertThat(response).bodyJson().extractingPath("$.data.account[0].account.identification")
                .isEqualTo("11223312345678");
    }

    @Test
    void returnsProblemDetailForAnUnknownAccount() {
        var response = mvc.get().uri(BASE + "/accounts/acc-9999");

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Account not found");
    }

    @Test
    void availableBalanceTakesOffPendingPayments() {
        var response = mvc.get().uri(BASE + "/accounts/acc-1001/balances");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.data.balance[0].type").isEqualTo("InterimBooked");
        assertThat(response).bodyJson().extractingPath("$.data.balance[0].amount.amount").isEqualTo("1507.50");
        assertThat(response).bodyJson().extractingPath("$.data.balance[1].type").isEqualTo("InterimAvailable");
        assertThat(response).bodyJson().extractingPath("$.data.balance[1].amount.amount").isEqualTo("1495.20");
    }

    @Test
    void listsTransactionsNewestFirst() {
        var response = mvc.get().uri(BASE + "/accounts/acc-1001/transactions");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.data.transaction[*].transactionId")
                .asArray().containsExactly("txn-4", "txn-3", "txn-2", "txn-1");
        assertThat(response).bodyJson().extractingPath("$.data.transaction[0].status").isEqualTo("Pending");
        assertThat(response).bodyJson().extractingPath("$.data.transaction[0].creditDebitIndicator")
                .isEqualTo("Debit");
        assertThat(response).bodyJson().extractingPath("$.data.transaction[0].amount.amount").isEqualTo("12.30");
    }
}
```

- **`extractingPath("$.data.account[*].accountId").asArray()`**: `[*]` picks a field from every element, and `asArray()` switches to AssertJ's list assertions, so `containsExactly` checks both the contents and the order.
- **MockMvc requests have the host `localhost` with no port**, which is why the expected self link is `http://localhost/open-banking/…`.

And `src/test/java/com/example/openbanking/api/FapiInteractionIdTest.java`:

```java
package com.example.openbanking.api;

import com.example.openbanking.OpenBankingIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@OpenBankingIntegrationTest
class FapiInteractionIdTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void playsBackTheCallersInteractionId() {
        String id = UUID.randomUUID().toString();

        var response = mvc.get().uri("/open-banking/v1/aisp/accounts")
                .header("x-fapi-interaction-id", id);

        assertThat(response).headers().hasValue("x-fapi-interaction-id", id);
    }

    @Test
    void generatesOneWhenTheCallerDidNotSendIt() {
        var response = mvc.get().uri("/open-banking/v1/aisp/accounts/acc-9999").exchange();

        assertThat(response).hasStatus(404);
        assertThat(response).headers().containsHeader("x-fapi-interaction-id");
        String generated = response.getResponse().getHeader("x-fapi-interaction-id");
        assertThat(UUID.fromString(generated)).isNotNull();
    }
}
```

The second test deliberately uses an **error** response: the standard requires the header on errors too. `.exchange()` turns the request builder into an `MvcTestResult`, so you can get at the raw response with `getResponse()`. `UUID.fromString` throws if the value isn't a UUID, so that line is the real assertion.

**Run everything:** right-click `src/test/java` → **Run 'All Tests'**, or `./mvnw test`.

---

## Your turn

### Exercise 1: Bob's overdraft

Add a test to `AccountApiTest` that fetches Bob's balances (`acc-2001`) and checks both numbers. Work them out from the seed data first, without looking at the table in step 4.

<details>
<summary>Solution</summary>

```java
@Test
void anOverdrawnAccountHasNegativeBalances() {
    var response = mvc.get().uri(BASE + "/accounts/acc-2001/balances");

    assertThat(response).hasStatusOk();
    assertThat(response).bodyJson().extractingPath("$.data.balance[0].amount.amount").isEqualTo("-80.00");
    assertThat(response).bodyJson().extractingPath("$.data.balance[1].amount.amount").isEqualTo("-85.00");
}
```

£100 in, £180 out, so booked is −£80. The £5 pending parking charge takes available to −£85. A negative available balance means Bob has nothing to spend. Keep this account in mind: guide 05 changes how a negative balance is shown.

</details>

### Exercise 2: transactions for an unknown account

What should `GET /open-banking/v1/aisp/accounts/acc-9999/transactions` return? Decide, then write the test.

<details>
<summary>Solution</summary>

A `404`, not `200` with an empty list. An empty list says "this account exists and has no transactions", which is false. `AccountService.transactions` already calls `get(accountId)` first for exactly this reason.

```java
@Test
void transactionsForAnUnknownAccountAre404() {
    assertThat(mvc.get().uri(BASE + "/accounts/acc-9999/transactions")).hasStatus(HttpStatus.NOT_FOUND);
}
```

(In guide 04 this changes again, for a security reason. See if you can guess why before you get there.)

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
```

(4 in `MoneyTest`, 7 in `AccountApiTest` and 2 in `FapiInteractionIdTest`, if you did both exercises.)

Your project should look like this:

```
open-banking-api/
├── pom.xml
├── requests.http
└── src/
    ├── main/
    │   ├── java/com/example/openbanking/
    │   │   ├── OpenBankingApiApplication.java
    │   │   ├── account/
    │   │   │   ├── Account.java
    │   │   │   ├── AccountController.java
    │   │   │   ├── AccountNotFoundException.java
    │   │   │   ├── AccountRepository.java
    │   │   │   ├── AccountService.java
    │   │   │   ├── AccountSubType.java
    │   │   │   ├── AccountTransaction.java
    │   │   │   ├── AccountTransactionRepository.java
    │   │   │   ├── AccountType.java
    │   │   │   ├── Balance.java
    │   │   │   ├── BalanceType.java
    │   │   │   ├── CreditDebit.java
    │   │   │   └── TransactionStatus.java
    │   │   ├── api/
    │   │   │   ├── FapiInteractionIdFilter.java
    │   │   │   └── ObResponse.java
    │   │   ├── money/
    │   │   │   └── Money.java
    │   │   └── web/
    │   │       └── ApiExceptionHandler.java
    │   └── resources/
    │       ├── application.properties
    │       └── db/
    │           ├── demo/
    │           │   └── V2__demo_customers.sql
    │           └── migration/
    │               └── V1__create_accounts.sql
    └── test/java/com/example/openbanking/
        ├── OpenBankingIntegrationTest.java
        ├── account/
        │   └── AccountApiTest.java
        ├── api/
        │   └── FapiInteractionIdTest.java
        └── money/
            └── MoneyTest.java
```

---

## Common errors

**`IllegalArgumentException: No enum constant com.example.openbanking.account.AccountType.Personal`**
The seed data used the JSON code (`'Personal'`) instead of the enum constant's name (`'PERSONAL'`). `@Enumerated(EnumType.STRING)` stores and reads `name()`, while `@JsonValue` only affects JSON. Fix the seed file and delete `data/` (the bad `V2` has already been recorded as applied in your local database).

**`listsAccountsInsideTheDataEnvelope` finds no accounts, or `availableBalance…` gets `0.00`**
Flyway didn't run the demo data. Check that `spring.flyway.locations` lists **both** `classpath:db/migration` and `classpath:db/demo`, and that the seed file is `src/main/resources/db/demo/V2__demo_customers.sql` (two underscores).

**`Using H2 2.4.240 which is newer than the version Flyway has been verified with.`**
A `WARN` at start-up, not an error. Spring Boot 4.1 ships a newer H2 than Flyway has tested against. Everything in this tutorial works. Ignore it.

**Log lines look like `[]c.e.o.OpenBankingApiApplication` with no space**
The trailing space at the end of `logging.pattern.correlation` went missing (some editors strip trailing whitespace). Put it back, or write the value as `[%X{interactionId:-}] `.

**`x-fapi-interaction-id` is missing on some responses**
The header was set *after* `chain.doFilter(...)`. By then the response may already be committed. Set it first, as in step 10.

**`Database may be already in use: ".../data/openbanking.mv.db"`**
The app is already running (perhaps once from IntelliJ and once from the terminal). Only run it one way at a time.

**`Web server failed to start. Port 8080 was already in use.`**
Something else is on port 8080, possibly another tutorial's app. Stop it, or add `server.port=8081` to `application.properties` and use that port in `requests.http`.

---

## Recap

- **PSD2** requires banks to give authorised third parties access to customers' payment accounts, with consent. In the UK, the **CMA Order** made the largest banks implement *standard* APIs, which became UK **Open Banking**.
- The bank is the **ASPSP**. The third party is a **TPP**: an **AISP** reads data, a **PISP** starts payments. The customer is the **PSU**.
- Open Banking responses wrap the payload in **`Data`**, with **`Links.Self`** and **`Meta`**. Lists sit under a singular name, even for one item.
- The **booked** balance is what's posted. The **available** balance takes off pending payments out.
- Keep demo data in **its own Flyway location**, so production never runs it.
- **`x-fapi-interaction-id`** is played back or generated on every response, and put in the **MDC** so it's on every log line.

### Interview angle

<details>
<summary>"What is Open Banking, and what did PSD2 have to do with it?"</summary>

PSD2 is the EU directive that required banks to let authorised third parties access customers' payment accounts, with the customer's explicit consent, through a dedicated interface rather than screen scraping. The UK implemented it in the Payment Services Regulations 2017. Separately, the CMA's retail banking investigation required the nine largest UK current account providers to build common, standardised APIs, and set up the body that wrote the UK Open Banking standards. So PSD2 created the legal right of access, and the CMA Order created the shared standard that makes it practical at scale.

</details>

<details>
<summary>"What's the difference between an AISP and a PISP? Which side is the ASPSP?"</summary>

Both are third-party providers (TPPs) authorised or registered with a regulator such as the FCA. An AISP reads account information (balances, transactions) with the customer's consent: budgeting apps, affordability checks, accounting software. A PISP initiates payments from the customer's account, such as pay-by-bank at a checkout. The ASPSP is the bank that services the account and exposes the APIs both of them call.

</details>

<details>
<summary>"What's the difference between a booked and an available balance?"</summary>

The booked (ledger) balance is the sum of everything that has actually been posted to the account. The available balance is what the customer can spend now: typically the booked balance minus pending debits such as card authorisations not yet settled, and plus any arranged overdraft. They differ whenever there's money in flight, which is most of the time on a busy current account.

</details>

<details>
<summary>"How would you trace one request across two companies' systems?"</summary>

A correlation id. The caller generates a UUID and sends it in a header (in Open Banking, `x-fapi-interaction-id`); the server plays it back, generates one if it's missing, and puts it in its logging context (MDC) so every log line for that request carries it. Both sides can then search their logs for the same value. Clear the MDC after each request, because servlet threads are reused.

</details>

### Further reading

- [UK Open Banking standards](https://standards.openbanking.org.uk/): the home of the Read/Write API, security profile and customer experience guidelines
- [Read/Write Data API profile (v3.1.10)](https://openbankinguk.github.io/read-write-api-site3/v3.1.10/profiles/read-write-data-api-profile.html): URL structure, headers including `x-fapi-interaction-id`, and the `Data`/`Links`/`Meta` envelope
- [The Payment Services Regulations 2017](https://www.legislation.gov.uk/uksi/2017/752/contents) on legislation.gov.uk
- [CMA: Retail Banking Market Investigation Order 2017](https://www.gov.uk/government/publications/retail-banking-market-investigation-order-2017)
- [Spring Boot reference: logging](https://docs.spring.io/spring-boot/reference/features/logging.html)

**Simplification to notice:** the standard pages large transaction lists with `Links.Next` and `Links.Prev`, and lets the TPP filter by date. This API returns everything on one page. The Core ledger tutorial's statements show how to page with correct running balances.

**Next:** [02: Securing the API with OAuth 2.0](02-securing-the-api-with-oauth2.md). Only callers with a valid access token get in.
