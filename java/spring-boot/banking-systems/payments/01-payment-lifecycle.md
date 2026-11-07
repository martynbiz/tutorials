# 01: The Payment Lifecycle

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- What really happens when you send money from your bank to someone at another bank in the UK
- The difference between **clearing** and **settlement**, and why a payment can be "done" for the customer hours before the banks have settled
- How **Faster Payments**, **Bacs** and **CHAPS** differ, and where **ISO 20022** messages fit in
- How to model a payment's life as an explicit **state machine** in plain Java: an `enum` that knows its own allowed transitions, checked by an exhaustive `switch`
- How to make an entity refuse illegal transitions, and prove it with a **parameterised test** that checks every possible pair
- Value objects for UK bank details (a `SortCode` record), and binding settings with `@ConfigurationProperties`
- A `Clock` bean, so that every timestamp in the system can be controlled in tests

By the end you'll have an API that accepts a payment instruction, checks it against the bank's limit, and records it as `VALIDATED` or `REJECTED`, plus a test suite that proves no code can move a payment through its lifecycle in the wrong order.

---

## Concepts

### How it works in a real bank

You open your banking app and pay Bob £25. Bob banks somewhere else. Your bank can't simply change a number in Bob's account, because it doesn't keep Bob's account. Bob's bank does. So two banks have to agree that the payment is happening, and eventually your bank has to actually hand over £25 of real money to Bob's bank.

That splits every interbank payment into two separate jobs:

- **[Clearing](../glossary.md#clearing):** exchanging the payment instruction and agreeing it. Your bank says "please credit Bob, sort code 20-00-00, account 55779911, £25". Bob's bank checks the account and says "accepted". Now both banks know who owes whom.
- **[Settlement](../glossary.md#settlement):** actually moving the money between the banks. In the UK that happens across the banks' accounts at the **Bank of England**, in "central bank money", the safest form of money there is.

The two don't have to happen at the same time. For most everyday UK payments, clearing happens in seconds and Bob can spend the money straight away, but the banks settle with each other later, in batches.

UK bank accounts are identified by a **[sort code and account number](../glossary.md#sort-code)**: a 6-digit sort code (which bank, and historically which branch) plus an 8-digit account number. Before sending, banks check the details look valid: a **modulus check** (an arithmetic check, from a specification published by Vocalink) catches most typing mistakes, although passing it only means the account number is *possible* for that sort code, not that the account exists. The **Extended Industry Sort Code Directory (EISCD)**, published by Pay.UK, says which payment schemes each sort code can receive. And since 2020, **Confirmation of Payee** checks the name you typed against the name on the account before you pay.

### Three UK payment schemes

A **payment scheme** is the set of rules, and the shared infrastructure, that banks use to clear payments with each other. Three matter most for sterling payments between accounts:

| | [Faster Payments (FPS)](../glossary.md#faster-payments) | [Bacs](../glossary.md#bacs) | [CHAPS](../glossary.md#chaps) |
|---|---|---|---|
| Typical use | Everyday transfers, bill payments, standing orders | Salaries (Direct Credit), Direct Debits | House purchases, high-value and wholesale payments |
| Speed for the customer | Usually within seconds, 24 hours a day, every day | A three-day cycle: input day, processing day, entry day | Same day, during the operating day |
| How the banks settle | **Deferred net**: positions are added up and settled in cycles, three times each working day | **Deferred net**: once a day | **Real-time gross (RTGS)**: each payment settles individually, as it happens |
| Operator | Pay.UK (central infrastructure provided by Vocalink) | Pay.UK | Bank of England (directly, since November 2017) |
| Limit | £1 million scheme limit; each bank sets its own lower limits | | No minimum or maximum |

Two terms in that table need unpacking:

- **Net settlement** means the banks don't move money for every payment. If, during a cycle, Bank A sends Bank B £10 million of payments and Bank B sends Bank A £9 million, only the **net** £1 million moves. It's efficient, but between cycles banks are owed money by each other. To remove that risk, Faster Payments and Bacs settlement participants must **prefund**: since 2015 they've held cash at the Bank of England to cover the most they could owe at settlement.
- **Gross settlement** (CHAPS) means each payment settles on its own, in full, before it's final. No exposure builds up, which is why it's used for large amounts.

This tutorial builds the sending side of a **Faster Payments-style** payment.

> **Sidebar: "instant" isn't quite a guarantee.** Pay.UK's own description of Faster Payments says funds are usually available within seconds but can sometimes take up to two hours, for example while a bank runs extra fraud checks. The legal floor is far slower: under [regulation 86 of the Payment Services Regulations 2017](https://www.legislation.gov.uk/uksi/2017/752/regulation/86), for a sterling payment within the UK the payer's bank must get the money to the payee's bank by the end of the next business day.

### ISO 20022: the language of payments

Banks talk to schemes in standardised messages. **[ISO 20022](../glossary.md#iso-20022)** is the international standard most payment systems are moving to. Its messages have names like:

| Message | Name | What it says |
|---|---|---|
| `pain.001` | Customer credit transfer initiation | A customer (often a business) tells its own bank "make these payments" |
| `pacs.008` | FI to FI customer credit transfer | One bank tells another, via the scheme, "credit this customer" |
| `pacs.002` | FI to FI payment status report | "Your payment was accepted" or "rejected, and here's why" |
| `pacs.004` | Payment return | "We're sending this money back" |
| `camt.053` | Bank to customer statement | An account statement |

Status reports use short codes. A `pacs.002` might carry the transaction status `ACSP` ("accepted, settlement in process") or `RJCT` ("rejected"), and a rejection carries a **reason code** such as `AC01` (incorrect account number), `AC04` (closed account number) or `AM04` (insufficient funds). You'll use these codes in this tutorial, because they're what a real scheme sends back.

Be precise about who uses what, because interviewers notice:

- **CHAPS** moved to ISO 20022 in June 2023.
- **Faster Payments** still uses messages based on an older card-industry standard, **ISO 8583**. Pay.UK has plans to move it to ISO 20022 as part of its future payments strategy (the "New Payments Vision", previously called the New Payments Architecture).
- **Bacs** uses its own long-established file format, "Standard 18".

This tutorial's messages are **loosely modelled** on `pacs.008` and `pacs.002` as JSON, because that's the direction of travel and the concepts transfer. They are not the real formats.

### A payment's life

From the moment your bank receives your instruction, a payment goes through a series of states. This tutorial uses:

```
RECEIVED ──► VALIDATED ──► SUBMITTED ──► ACCEPTED ──► SETTLED
    │                          │
    └──────────► REJECTED ◄────┘
```

| Status | Meaning | Built in |
|---|---|---|
| `RECEIVED` | We have the customer's instruction. Nothing checked yet. | This guide |
| `VALIDATED` | Our own checks passed (limits, and from guide 02, funds). | This guide |
| `SUBMITTED` | Sent to the scheme. | Guide 04 |
| `ACCEPTED` | The scheme and the receiving bank accepted it. Bob has been paid. | Guide 05 |
| `SETTLED` | The banks have settled with each other. Nothing more can happen. | Guide 05 |
| `REJECTED` | Refused, by us or by the receiving bank. Nothing more can happen. | This guide and guide 05 |

Notice what's **not** allowed. An `ACCEPTED` payment can't become `REJECTED`: once Bob's bank has accepted the money, it's his. If something goes wrong later (say Bob's account is closed the next day), Faster Payments uses a **return**, which is a *new* payment in the opposite direction that references the original. Pay.UK's rules are explicit that a payment can't be revoked or recalled once it has been sent.

> **Simplification:** real payment platforms have more states: fraud screening, held for review, scheduled for a future date, partially processed, returned, and more. Six is enough to learn the technique, and adding a state later is easy once the machine is explicit.

### Why an explicit state machine?

The alternative, which you'll see in plenty of real code, is a `status` column that any method can set:

```java
payment.setStatus("SETTLED");   // from anywhere, at any time
```

Nothing stops a bug, or a delayed message from the scheme, moving a rejected payment to settled, or settling a payment that was never accepted. In payments, an illegal transition usually means money in the wrong place.

An **explicit state machine** writes the allowed transitions down in one place, and makes it impossible to change state any other way:

1. The `PaymentStatus` enum knows which statuses each one may move to.
2. The `Payment` entity has no `setStatus`. Its methods (`markValidated`, `reject`, …) all go through one private `moveTo` that checks the transition and throws if it's illegal.
3. A test checks **every** pair of statuses against the diagram, so the rules can't drift silently.

> **Sidebar: other ways to build one.** For a handful of states, an `enum` with a `switch` is the most common choice in Java code you'll meet. A **sealed interface** (Java 17+) with one record per state is a good fit when each state carries different data (a `Rejected` record that *must* have a reason, a `Settled` record that *must* have a settlement date). Libraries such as Spring Statemachine add events, guards and persistence, at the cost of a lot more machinery. Whichever you choose, the principles are the same: transitions are declared in one place, and illegal ones are refused.

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
| Artifact | `payments` |
| Package name | `com.example.payments` |
| Packaging | Jar |
| Java | 25 |

Add these dependencies:

| Dependency | Why |
|---|---|
| **Spring Web** | REST controllers (it pulls in `spring-boot-starter-webmvc`) |
| **Spring Data JPA** | Entities and repositories |
| **Flyway Migration** | Versioned SQL migrations |
| **H2 Database** | The embedded database |
| **Validation** | `@Valid`, `@NotBlank`, `@Pattern` |
| **SpringDoc OpenAPI** | Swagger UI, for trying the API in a browser |

Click **Generate** and unzip the download so the project folder ends up at:

```
tutorials/workspace/banking-systems/payments/
```

Open it in IntelliJ with **File → Open…**, choose the `payments` folder (the one containing `pom.xml`), and trust the project. Check **File → Project Structure → Project → SDK** is set to a Java 25 JDK.

Delete the generated test `src/test/java/com/example/payments/PaymentsApplicationTests.java`. You'll write better ones in step 9.

### 2. Configure the application

Replace `src/main/resources/application.properties` with:

```properties
spring.application.name=payments

# H2 database stored in ./data/payments.mv.db
spring.datasource.url=jdbc:h2:file:./data/payments
spring.datasource.username=sa
spring.h2.console.enabled=true

# Flyway owns the schema; Hibernate only checks it matches the entities
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false

# Our bank's own limit for a single outbound payment
payments.single-payment-limit=25000.00
```

- `username=sa`: Spring Boot only defaults the username to `sa` for *in-memory* H2. Set it explicitly for a file database, or the H2 console won't let you log in.
- `ddl-auto=validate`: Hibernate never changes the schema. It only checks at start-up that your entities match the tables Flyway created.
- `open-in-view=false`: the database session closes when the service's transaction ends, so a forgotten lazy load fails loudly instead of quietly running queries from the controller.
- `payments.single-payment-limit` is **our bank's** limit, not the scheme's. The Faster Payments scheme allows up to £1 million, but every bank sets its own, usually much lower, limits for customers. £25,000 is just a number for this tutorial.

Add `data/` to the project's `.gitignore` so the database file is never committed:

```
### Local database ###
data/
```

### 3. Money

Every system in this series has its own copy of a small `Money` type, so it stands alone. The Core ledger tutorial covers it properly: why money is never a `double`, the `BigDecimal` traps, and why amounts travel through JSON as strings. Here's the short version:

- An amount is a `BigDecimal` plus a `java.util.Currency`.
- The scale is **always** the currency's own number of decimal places (2 for GBP), so `Money.gbp("10.5")` equals `Money.gbp("10.50")`.
- `RoundingMode.UNNECESSARY` makes it **refuse** a fraction of a penny rather than rounding it away.
- It refuses to add pounds to euros.
- In JSON it's `{"amount": "10.50", "currency": "GBP"}`, with the amount as a string so no client can turn it into a floating-point number.

Create `money/Money.java` (paths like this mean `src/main/java/com/example/payments/money/Money.java`):

```java
package com.example.payments.money;

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

The two Jackson annotations deserve a word. `@JsonFormat(shape = STRING)` writes the amount as `"25.00"` rather than `25.00`. `@JsonAutoDetect(isGetterVisibility = NONE)` stops Jackson treating `isZero()`, `isPositive()` and `isNegative()` as properties, which would otherwise leak into every response as `"zero": false` and so on. (The annotations live in `com.fasterxml.jackson.annotation` even in Spring Boot 4, which otherwise uses Jackson 3's `tools.jackson` packages.)

And a few tests in `src/test/java/com/example/payments/money/MoneyTest.java`:

```java
package com.example.payments.money;

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
        assertThat(Money.gbp("1.00").isLessThan(Money.gbp("1.01"))).isTrue();
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

**Run them in IntelliJ:** click the green arrow beside `class MoneyTest` → **Run 'MoneyTest'**. **Terminal alternative:** `./mvnw test -Dtest=MoneyTest`.

### 4. A sort code value object

A sort code is written `20-00-00` on a bank card and `200000` in many files and APIs. If you store it as a bare `String`, some rows end up with hyphens and some without, and `"20-00-00".equals("200000")` is `false`. A tiny **value object** fixes that once.

Create `payment/SortCode.java`:

```java
package com.example.payments.payment;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;

/**
 * A UK sort code: six digits identifying a bank and branch.
 * Accepts "20-00-00" or "200000", always stores "200000", always prints "20-00-00".
 */
public record SortCode(String digits) {

    public SortCode {
        Objects.requireNonNull(digits, "sort code must not be null");
        digits = digits.replace("-", "");
        if (!digits.matches("\\d{6}")) {
            throw new IllegalArgumentException("A sort code must be six digits: " + digits);
        }
    }

    public static SortCode of(String value) {
        return new SortCode(value);
    }

    @JsonValue
    @Override
    public String toString() {
        return digits.substring(0, 2) + "-" + digits.substring(2, 4) + "-" + digits.substring(4, 6);
    }
}
```

- The **compact constructor** normalises and validates before the field is assigned, exactly as `Money` does with its scale. There's no way to hold an invalid `SortCode`.
- **`@JsonValue`** tells Jackson to write the whole object as the single value `toString()` returns, so a sort code appears in JSON as `"20-00-00"`, not `{"digits": "200000"}`.

> **Simplification:** a real system would also run the modulus check against the account number, and look the sort code up in the EISCD to confirm it can receive Faster Payments. Both need reference data published to banks.

Who's being paid is a payee name plus their bank details. Create `payment/Creditor.java`:

```java
package com.example.payments.payment;

/** Who is being paid: the payee's name and their account at another bank. */
public record Creditor(String name, SortCode sortCode, String accountNumber) {
}
```

"Creditor" is the ISO 20022 word for the payee, and "debtor" for the payer. You'll see both in the code from now on.

Test the sort code in `src/test/java/com/example/payments/payment/SortCodeTest.java`:

```java
package com.example.payments.payment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SortCodeTest {

    @Test
    void acceptsBothFormatsAndPrintsWithHyphens() {
        assertThat(SortCode.of("20-00-00")).isEqualTo(SortCode.of("200000"));
        assertThat(SortCode.of("200000").digits()).isEqualTo("200000");
        assertThat(SortCode.of("200000")).hasToString("20-00-00");
    }

    @Test
    void rejectsAnythingThatIsNotSixDigits() {
        assertThatThrownBy(() -> SortCode.of("20-00-0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("six digits");
    }
}
```

### 5. The state machine

Create `payment/PaymentStatus.java`:

```java
package com.example.payments.payment;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a payment is in its lifecycle, and where it is allowed to go next.
 *
 * <pre>
 * RECEIVED ──► VALIDATED ──► SUBMITTED ──► ACCEPTED ──► SETTLED
 *     │                          │
 *     └──────────► REJECTED ◄────┘
 * </pre>
 */
public enum PaymentStatus {

    /** We have the customer's instruction, nothing checked yet. */
    RECEIVED,
    /** Our own checks passed. */
    VALIDATED,
    /** Sent to the payment scheme. */
    SUBMITTED,
    /** The scheme and the receiving bank accepted it. The payee has been paid. */
    ACCEPTED,
    /** The banks have settled with each other. Nothing more can happen. */
    SETTLED,
    /** Refused, by us or by the scheme. Nothing more can happen. */
    REJECTED;

    /** The statuses this one may move to. */
    public Set<PaymentStatus> nextStatuses() {
        return switch (this) {
            case RECEIVED -> EnumSet.of(VALIDATED, REJECTED);
            case VALIDATED -> EnumSet.of(SUBMITTED);
            case SUBMITTED -> EnumSet.of(ACCEPTED, REJECTED);
            case ACCEPTED -> EnumSet.of(SETTLED);
            case SETTLED, REJECTED -> EnumSet.noneOf(PaymentStatus.class);
        };
    }

    public boolean canMoveTo(PaymentStatus next) {
        return nextStatuses().contains(next);
    }

    public boolean isTerminal() {
        return nextStatuses().isEmpty();
    }
}
```

The whole diagram is in `nextStatuses()`. Three Java details make it robust:

- **A switch expression (Java 14+) with no `default`.** The compiler checks that every constant is handled. Add a seventh status and forget to give it transitions, and the project doesn't compile:

  ```
  [ERROR] .../PaymentStatus.java:[32,16] the switch expression does not cover all possible input values
  ```

  A `default ->` branch would silently swallow the new status. Leaving it out turns a forgotten rule into a compile error.
- **Why a method and not a field?** It's tempting to pass the allowed set to each constant's constructor, `VALIDATED(EnumSet.of(SUBMITTED))`, but Java won't let a constant refer to one declared *after* it (an "illegal forward reference"). A method runs after every constant exists.
- **`EnumSet`** is the standard, compact `Set` for enums: internally it's a bit field.

> **In older codebases:** before switch expressions you'll see a `static Map<Status, Set<Status>>` filled in a `static {}` block, or a `canTransitionTo` method full of `if` statements. They work, but nothing tells you when a new status was forgotten.

Why not allow `VALIDATED → REJECTED`? In this design, our own checks all happen while the payment is `RECEIVED`. Once it's `VALIDATED`, only the scheme can reject it, and only after it's `SUBMITTED`. Every arrow in the diagram should have a reason you can say out loud.

Create `payment/IllegalPaymentTransitionException.java`:

```java
package com.example.payments.payment;

public class IllegalPaymentTransitionException extends RuntimeException {

    public IllegalPaymentTransitionException(Long paymentId, PaymentStatus from, PaymentStatus to) {
        super("Payment %s cannot move from %s to %s".formatted(paymentId, from, to));
    }
}
```

And `payment/RejectionReason.java`, using the real ISO 20022 reason codes:

```java
package com.example.payments.payment;

/**
 * Why a payment was rejected. The names are ISO 20022 external status reason
 * codes, the same codes a real scheme would send back.
 */
public enum RejectionReason {
    AC01("Incorrect account number"),
    AC04("Closed account number"),
    AM02("Amount exceeds the allowed maximum"),
    AM04("Insufficient funds");

    private final String description;

    RejectionReason(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
```

Using the scheme's own codes for your own rejections means a client handles one vocabulary, whoever said no.

### 6. Prove the state machine

Before any database, prove the rules. Create `src/test/java/com/example/payments/payment/PaymentStatusTest.java`:

```java
package com.example.payments.payment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.example.payments.payment.PaymentStatus.ACCEPTED;
import static com.example.payments.payment.PaymentStatus.RECEIVED;
import static com.example.payments.payment.PaymentStatus.REJECTED;
import static com.example.payments.payment.PaymentStatus.SETTLED;
import static com.example.payments.payment.PaymentStatus.SUBMITTED;
import static com.example.payments.payment.PaymentStatus.VALIDATED;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentStatusTest {

    /** The lifecycle diagram, written out as data. If this changes, it should be a deliberate decision. */
    private static final Map<PaymentStatus, Set<PaymentStatus>> EXPECTED = Map.of(
            RECEIVED, EnumSet.of(VALIDATED, REJECTED),
            VALIDATED, EnumSet.of(SUBMITTED),
            SUBMITTED, EnumSet.of(ACCEPTED, REJECTED),
            ACCEPTED, EnumSet.of(SETTLED),
            SETTLED, EnumSet.noneOf(PaymentStatus.class),
            REJECTED, EnumSet.noneOf(PaymentStatus.class));

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void allowsExactlyTheTransitionsInTheDiagram(PaymentStatus from) {
        for (PaymentStatus to : PaymentStatus.values()) {
            assertThat(from.canMoveTo(to))
                    .as("%s -> %s", from, to)
                    .isEqualTo(EXPECTED.get(from).contains(to));
        }
    }

    @Test
    void onlySettledAndRejectedAreTerminal() {
        assertThat(EnumSet.allOf(PaymentStatus.class).stream().filter(PaymentStatus::isTerminal))
                .containsExactlyInAnyOrder(SETTLED, REJECTED);
    }
}
```

- **`@ParameterizedTest` with `@EnumSource`** runs the test once per status: six runs, each checking all six possible targets. That's all 36 pairs, including the ones that must be **refused**, which are the ones that matter.
- **`.as("%s -> %s", from, to)`** labels the assertion, so a failure says `[SUBMITTED -> SETTLED]` instead of just "expected false".
- The expected table is written out separately from the enum, on purpose. A test that just called `nextStatuses()` to compute its expectations would agree with any bug.

Run it. IntelliJ shows the six parameterised runs nested under the test, one per status. JUnit counts each one as a test, so this class contributes **7** tests.

### 7. The `payment` table and entity

Create `src/main/resources/db/migration/V1__create_payment.sql`:

```sql
CREATE TABLE payment (
    id                      BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    debtor_account_id       BIGINT                   NOT NULL,
    creditor_name           VARCHAR(70)              NOT NULL,
    creditor_sort_code      VARCHAR(6)               NOT NULL,
    creditor_account_number VARCHAR(8)               NOT NULL,
    amount                  NUMERIC(19, 2)           NOT NULL,
    currency                VARCHAR(3)               NOT NULL,
    reference               VARCHAR(18)              NOT NULL,
    status                  VARCHAR(20)              NOT NULL,
    rejection_reason        VARCHAR(4),
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    version                 BIGINT                   NOT NULL,
    CONSTRAINT ck_payment_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_payment_status CHECK (status IN
        ('RECEIVED', 'VALIDATED', 'SUBMITTED', 'ACCEPTED', 'SETTLED', 'REJECTED'))
);

CREATE INDEX ix_payment_status ON payment (status);
```

This SQL is portable: it runs unchanged on H2 and PostgreSQL. Some choices:

- **`debtor_account_id`** is the payer's account. For now it's just a number. In guide 02 you'll give the bank a small ledger and turn this into a foreign key.
- **`reference VARCHAR(18)`**: the reference the payee sees on their statement. Faster Payments carries up to 18 characters of reference information in its standard reference field, the same length as a Bacs reference, so 18 is the limit UK customers are used to.
- **`ck_payment_status`**: the database refuses any status that isn't one of the six, even from a hand-written SQL script. It can't check *transitions*, which need the old value, but it stops nonsense values.
- **`version`** is for optimistic locking with `@Version`. Payments are updated by several actors (the API, a background relay, scheme callbacks), so a lost update must be impossible. Guide 02 explains locking.
- **`ix_payment_status`**: background jobs constantly ask "which payments are in status X?".

Now the entity, `payment/Payment.java`:

```java
package com.example.payments.payment;

import com.example.payments.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;

@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "debtor_account_id", nullable = false)
    private Long debtorAccountId;

    @Column(name = "creditor_name", nullable = false, length = 70)
    private String creditorName;

    @Column(name = "creditor_sort_code", nullable = false, length = 6)
    private String creditorSortCode;

    @Column(name = "creditor_account_number", nullable = false, length = 8)
    private String creditorAccountNumber;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currencyCode;

    @Column(nullable = false, length = 18)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason", length = 4)
    private RejectionReason rejectionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Payment() {
        // for JPA
    }

    private Payment(Long debtorAccountId, Creditor creditor, Money amount, String reference, Instant now) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("A payment amount must be positive, not " + amount);
        }
        this.debtorAccountId = debtorAccountId;
        this.creditorName = creditor.name();
        this.creditorSortCode = creditor.sortCode().digits();
        this.creditorAccountNumber = creditor.accountNumber();
        this.amount = amount.amount();
        this.currencyCode = amount.currency().getCurrencyCode();
        this.reference = reference;
        this.status = PaymentStatus.RECEIVED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Every payment starts life as RECEIVED. */
    public static Payment receive(Long debtorAccountId, Creditor creditor, Money amount, String reference,
                                  Instant now) {
        return new Payment(debtorAccountId, creditor, amount, reference, now);
    }

    public void markValidated(Instant now) {
        moveTo(PaymentStatus.VALIDATED, now);
    }

    public void markSubmitted(Instant now) {
        moveTo(PaymentStatus.SUBMITTED, now);
    }

    public void markAccepted(Instant now) {
        moveTo(PaymentStatus.ACCEPTED, now);
    }

    public void markSettled(Instant now) {
        moveTo(PaymentStatus.SETTLED, now);
    }

    public void reject(RejectionReason reason, Instant now) {
        moveTo(PaymentStatus.REJECTED, now);
        this.rejectionReason = reason;
    }

    /** The only place the status changes. Every transition is checked against the state machine. */
    private void moveTo(PaymentStatus next, Instant now) {
        if (!status.canMoveTo(next)) {
            throw new IllegalPaymentTransitionException(id, status, next);
        }
        this.status = next;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getDebtorAccountId() {
        return debtorAccountId;
    }

    public Creditor getCreditor() {
        return new Creditor(creditorName, new SortCode(creditorSortCode), creditorAccountNumber);
    }

    public Money getAmount() {
        return new Money(amount, Currency.getInstance(currencyCode));
    }

    public String getReference() {
        return reference;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public RejectionReason getRejectionReason() {
        return rejectionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
```

The important parts:

- **There is no `setStatus`.** The five public transition methods are named after business events, and they all funnel into `moveTo`, which asks the enum. The state machine can't be bypassed from outside the class.
- **`reject` moves first, then records the reason.** If the transition is illegal, `moveTo` throws before anything changes, so a refused rejection leaves no stray reason behind.
- **Every method takes `Instant now`.** The entity never calls `Instant.now()` itself. The caller passes the time in, which makes the entity trivial to test with fixed times, and in step 8 you'll give the service a `Clock` for the same reason.
- **The getters return value objects** (`Money`, `Creditor`, `SortCode`) while the columns stay plain types. Code outside the entity only ever sees the safe types.

> **In older codebases:** expect `@Data` (Lombok) on the entity and `payment.setStatus(PaymentStatus.SETTLED)` called from services, schedulers and message listeners alike. When you find a payment in an impossible state in production, that's usually why. See the [legacy cheat sheet](../legacy-cheatsheet.md#lombok).

Unit-test the entity, no Spring needed. `src/test/java/com/example/payments/payment/PaymentTest.java`:

```java
package com.example.payments.payment;

import com.example.payments.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    private static final Creditor BOB = new Creditor("Bob Smith", SortCode.of("20-00-00"), "55779911");

    private Payment newPayment() {
        return Payment.receive(1L, BOB, Money.gbp("25.00"), "Rent", NOW);
    }

    @Test
    void walksTheHappyPath() {
        Payment payment = newPayment();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.RECEIVED);

        payment.markValidated(NOW);
        payment.markSubmitted(NOW);
        payment.markAccepted(NOW);
        payment.markSettled(NOW.plusSeconds(60));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(payment.getUpdatedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void refusesToSkipAStep() {
        Payment payment = newPayment();

        assertThatThrownBy(() -> payment.markSubmitted(NOW))
                .isInstanceOf(IllegalPaymentTransitionException.class)
                .hasMessageContaining("cannot move from RECEIVED to SUBMITTED");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.RECEIVED);
    }

    @Test
    void anAcceptedPaymentCanNoLongerBeRejected() {
        Payment payment = newPayment();
        payment.markValidated(NOW);
        payment.markSubmitted(NOW);
        payment.markAccepted(NOW);

        assertThatThrownBy(() -> payment.reject(RejectionReason.AC04, NOW))
                .isInstanceOf(IllegalPaymentTransitionException.class);
        assertThat(payment.getRejectionReason()).isNull();
    }

    @Test
    void recordsWhyAPaymentWasRejected() {
        Payment payment = newPayment();

        payment.reject(RejectionReason.AM02, NOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(payment.getRejectionReason()).isEqualTo(RejectionReason.AM02);
    }

    @Test
    void refusesAZeroAmount() {
        assertThatThrownBy(() -> Payment.receive(1L, BOB, Money.gbp("0.00"), "Rent", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

`anAcceptedPaymentCanNoLongerBeRejected` is the banking rule from the Concepts section, written as a test: once accepted, the only way back is a return.

### 8. Clock, settings, repository and service

**A `Clock` bean.** Payments are full of time: when it was created, when it changed, and later, when an idempotency key expires and which outbox messages are due. If every class calls `Instant.now()`, tests can't control time. Instead, inject a `java.time.Clock` and ask it. Create `config/ClockConfig.java`:

```java
package com.example.payments.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** Inject this instead of calling Instant.now(), so tests can control time. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

In guide 03 a test will swap it for a clock it can wind forward 24 hours in a millisecond.

**Typed settings.** Create `payment/PaymentProperties.java`:

```java
package com.example.payments.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/** Settings under "payments." in application.properties. */
@ConfigurationProperties("payments")
public record PaymentProperties(BigDecimal singlePaymentLimit) {
}
```

`@ConfigurationProperties` binds `payments.single-payment-limit` to the `singlePaymentLimit` component (Spring's "relaxed binding" maps kebab-case to camelCase). A record works because Spring binds through its constructor. Spring has to be told to look for these classes, so add `@ConfigurationPropertiesScan` to the application class, `PaymentsApplication.java`:

```java
package com.example.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentsApplication.class, args);
    }
}
```

**The repository**, `payment/PaymentRepository.java`:

```java
package com.example.payments.payment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
}
```

**An exception** for unknown ids, `payment/PaymentNotFoundException.java`:

```java
package com.example.payments.payment;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(Long id) {
        super("No payment with id " + id);
    }
}
```

**The service**, `payment/PaymentService.java`:

```java
package com.example.payments.payment;

import com.example.payments.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final PaymentProperties properties;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, PaymentProperties properties, Clock clock) {
        this.payments = payments;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Payment create(Long debtorAccountId, Creditor creditor, Money amount, String reference) {
        Instant now = clock.instant();
        Payment payment = Payment.receive(debtorAccountId, creditor, amount, reference, now);

        Money limit = new Money(properties.singlePaymentLimit(), amount.currency());
        if (limit.isLessThan(amount)) {
            payment.reject(RejectionReason.AM02, now);
        } else {
            payment.markValidated(now);
        }
        return payments.save(payment);
    }

    @Transactional(readOnly = true)
    public Payment get(Long id) {
        return payments.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }
}
```

Notice that a payment over the limit is **not** an error. It's recorded, as `REJECTED` with reason `AM02`. That's a deliberate design choice, and it's how payment APIs usually behave (the UK Open Banking payment APIs, for example, return the created payment resource with a status that can say it was rejected):

- **A malformed request** (no sort code, a negative amount) never becomes a payment. It's a `400 Bad Request`, and nothing is stored.
- **A well-formed instruction that the bank declines** *is* a payment, with a status that says what happened and why. Banks keep a record of declined payments for the customer, for complaints, and for fraud and audit teams. The [audit trail](../glossary.md#audit-trail) matters as much for "no" as for "yes".

### 9. The API

Create `payment/PaymentController.java`:

```java
package com.example.payments.payment;

import com.example.payments.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(@Valid @RequestBody CreatePaymentRequest request) {
        Payment payment = paymentService.create(
                request.debtorAccountId(),
                request.creditor().toCreditor(),
                Money.gbp(request.amount().toPlainString()),
                request.reference());
        return ResponseEntity.created(URI.create("/payments/" + payment.getId()))
                .body(PaymentResponse.from(payment));
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable Long id) {
        return PaymentResponse.from(paymentService.get(id));
    }

    public record CreatePaymentRequest(
            @NotNull Long debtorAccountId,
            @NotNull @Valid CreditorRequest creditor,
            @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 18) String reference) {
    }

    public record CreditorRequest(
            @NotBlank @Size(max = 70) String name,
            @NotNull @Pattern(regexp = "\\d{2}-?\\d{2}-?\\d{2}", message = "must be six digits, like 20-00-00")
            String sortCode,
            @NotNull @Pattern(regexp = "\\d{8}", message = "must be eight digits")
            String accountNumber) {

        Creditor toCreditor() {
            return new Creditor(name, SortCode.of(sortCode), accountNumber);
        }
    }

    public record PaymentResponse(
            Long id,
            Long debtorAccountId,
            Creditor creditor,
            Money amount,
            String reference,
            PaymentStatus status,
            RejectionReason rejectionReason,
            Instant createdAt,
            Instant updatedAt) {

        static PaymentResponse from(Payment payment) {
            return new PaymentResponse(
                    payment.getId(), payment.getDebtorAccountId(), payment.getCreditor(),
                    payment.getAmount(), payment.getReference(), payment.getStatus(),
                    payment.getRejectionReason(), payment.getCreatedAt(), payment.getUpdatedAt());
        }
    }
}
```

- **`@Valid` on the nested `creditor` field** is what makes Bean Validation look *inside* `CreditorRequest`. Without it, the `@Pattern`s on the sort code and account number are never checked (see Common errors).
- **`@Digits(integer = 15, fraction = 2)`** rejects `25.001` with a `400`, before `Money` would throw on it.
- **The request takes strings for bank details, the domain takes value objects.** Validation gives the client a friendly field error; the `SortCode` constructor is the last line of defence.

Turn validation failures into `ProblemDetail` responses with field errors. Create `web/ApiExceptionHandler.java`:

```java
package com.example.payments.web;

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

Extending `ResponseEntityExceptionHandler` switches all of Spring MVC's own errors (bad JSON, a missing header, an unsupported method) to RFC 9457 `ProblemDetail` responses. The override adds an `errors` map so the client knows *which* field is wrong. The Core ledger tutorial walks through this class line by line.

### 10. Run it

Run `PaymentsApplication` from IntelliJ (the green arrow beside `main`), or:

```bash
./mvnw spring-boot:run
```

Flyway applies the migration:

```
Migrating schema "PUBLIC" to version "1 - create payment"
```

Create `requests.http` in the project root. You'll add to it in every guide:

```http
### Pay Bob £25
POST http://localhost:8080/payments
Content-Type: application/json

{
  "debtorAccountId": 1,
  "creditor": {"name": "Bob Smith", "sortCode": "20-00-00", "accountNumber": "55779911"},
  "amount": "25.00",
  "reference": "Rent"
}

### A payment over our limit (recorded, but REJECTED)
POST http://localhost:8080/payments
Content-Type: application/json

{
  "debtorAccountId": 1,
  "creditor": {"name": "Bob Smith", "sortCode": "20-00-00", "accountNumber": "55779911"},
  "amount": "30000.00",
  "reference": "Car"
}

### Get payment 1
GET http://localhost:8080/payments/1
```

**(Ultimate)** IntelliJ's HTTP Client shows a green ▶ beside each `###` request. **Free edition / terminal alternatives:** open `http://localhost:8080/swagger-ui.html`, expand **POST /payments**, click **Try it out** and paste the JSON. Or use curl:

```bash
curl -i -X POST localhost:8080/payments -H 'Content-Type: application/json' \
  -d '{"debtorAccountId":1,"creditor":{"name":"Bob Smith","sortCode":"20-00-00","accountNumber":"55779911"},"amount":"25.00","reference":"Rent"}'
```

The first request returns `201 Created`, a `Location: /payments/1` header, and:

```json
{
  "id": 1,
  "debtorAccountId": 1,
  "creditor": { "name": "Bob Smith", "sortCode": "20-00-00", "accountNumber": "55779911" },
  "amount": { "amount": "25.00", "currency": "GBP" },
  "reference": "Rent",
  "status": "VALIDATED",
  "rejectionReason": null,
  "createdAt": "2026-11-06T22:52:50.356900695Z",
  "updatedAt": "2026-11-06T22:52:50.356900695Z"
}
```

The second is also `201`, with `"status": "REJECTED"` and `"rejectionReason": "AM02"`. And a request with a blank name, a short sort code, a short account number, a fraction of a penny and an overlong reference gets every problem back at once:

```json
{
  "detail": "Invalid request content.",
  "instance": "/payments",
  "status": 400,
  "title": "Bad Request",
  "errors": {
    "amount": "numeric value out of bounds (<15 digits>.<2 digits> expected)",
    "creditor.accountNumber": "must be eight digits",
    "creditor.name": "must not be blank",
    "creditor.sortCode": "must be six digits, like 20-00-00",
    "reference": "size must be between 0 and 18"
  }
}
```

### 11. API tests

Every integration test needs the same setup: the whole application, the real Flyway migrations, and an **in-memory** database so tests never touch `./data`. Bundle it in a meta-annotation, `src/test/java/com/example/payments/PaymentsIntegrationTest.java`:

```java
package com.example.payments;

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
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:payments-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
public @interface PaymentsIntegrationTest {
}
```

Because every test class uses identical configuration, Spring builds the application context once and reuses it, which keeps the suite fast. `DB_CLOSE_DELAY=-1` keeps the in-memory database alive for the whole test run. You'll extend this annotation in guide 03.

Now `src/test/java/com/example/payments/payment/PaymentApiTest.java`:

```java
package com.example.payments.payment;

import com.example.payments.PaymentsIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

@PaymentsIntegrationTest
class PaymentApiTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void createsAValidatedPayment() {
        MvcTestResult response = pay("25.00", "20-00-00");

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).headers().containsHeader("Location");
        assertThat(response).bodyJson().extractingPath("$.status").isEqualTo("VALIDATED");
        assertThat(response).bodyJson().extractingPath("$.amount.amount").isEqualTo("25.00");
        assertThat(response).bodyJson().extractingPath("$.creditor.sortCode").isEqualTo("20-00-00");
    }

    @Test
    void rejectsAPaymentOverTheLimitButStillRecordsIt() {
        MvcTestResult response = pay("25000.01", "200000");

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(response).bodyJson().extractingPath("$.rejectionReason").isEqualTo("AM02");
    }

    @Test
    void returnsFieldErrorsForABadSortCode() {
        MvcTestResult response = pay("25.00", "20-00");

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors['creditor.sortCode']")
                .isEqualTo("must be six digits, like 20-00-00");
    }

    @Test
    void fetchesAPaymentById() {
        String location = pay("10.00", "20-00-00").getResponse().getHeader("Location");

        assertThat(mvc.get().uri(location)).hasStatus(HttpStatus.OK)
                .bodyJson().extractingPath("$.reference").isEqualTo("Rent");
    }

    private MvcTestResult pay(String amount, String sortCode) {
        return mvc.post().uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "debtorAccountId": 1,
                          "creditor": {"name": "Bob Smith", "sortCode": "%s", "accountNumber": "55779911"},
                          "amount": "%s",
                          "reference": "Rent"
                        }
                        """.formatted(sortCode, amount))
                .exchange();
    }
}
```

- `pay("25000.01", "200000")` checks two things at once: one penny over the limit is rejected, and a sort code without hyphens is accepted.
- `$.errors['creditor.sortCode']` uses JSONPath's bracket notation, because the key itself contains a dot.
- `.exchange()` sends the request and returns an `MvcTestResult` you can keep and inspect, which the helper method needs.

Run all the tests: right-click `src/test/java` → **Run 'All Tests'**, or `./mvnw test`.

---

## Your turn

### Exercise 1: a proper 404

`GET /payments/999` throws `PaymentNotFoundException`, which Spring reports as a `500 Internal Server Error`. Add an `@ExceptionHandler` to `ApiExceptionHandler` that returns a `404` `ProblemDetail` titled `"Payment not found"`, and a test in `PaymentApiTest` that checks the status, the `application/problem+json` content type and the title.

<details>
<summary>Solution</summary>

In `ApiExceptionHandler` (import `PaymentNotFoundException`, `HttpStatus` and `ExceptionHandler`):

```java
    @ExceptionHandler(PaymentNotFoundException.class)
    ProblemDetail handle(PaymentNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Payment not found");
        return problem;
    }
```

In `PaymentApiTest`:

```java
    @Test
    void returnsProblemDetailForAnUnknownPayment() {
        MvcTestResult response = mvc.get().uri("/payments/999999").exchange();

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Payment not found");
    }
```

The response is:

```json
{"detail":"No payment with id 99","instance":"/payments/99","status":404,"title":"Payment not found"}
```

</details>

### Exercise 2: make the database agree

The entity guarantees that a `REJECTED` payment has a reason and no other payment does. But a support engineer with a SQL console could still break that rule. Add a `V2` migration with a `CHECK` constraint enforcing it, and a test that goes **around** the entity, straight to SQL with Spring's `JdbcClient`, to prove the database refuses both a rejected payment without a reason and an accepted payment with one.

<details>
<summary>Hint</summary>

A rule of the form "A if and only if B" can be written portably as `(A AND B) OR (NOT A AND NOT B)`. `JdbcClient` is auto-configured; inject it with `@Autowired JdbcClient jdbc` and use `jdbc.sql("…").param("name", value).update()`. Spring translates a constraint violation into `DataIntegrityViolationException`.

</details>

<details>
<summary>Solution</summary>

`src/main/resources/db/migration/V2__payment_rejection_reason.sql`:

```sql
-- A rejected payment must say why. Any other payment must not have a reason.
ALTER TABLE payment ADD CONSTRAINT ck_payment_rejection_reason CHECK (
    (status = 'REJECTED' AND rejection_reason IS NOT NULL)
    OR (status <> 'REJECTED' AND rejection_reason IS NULL));
```

`src/test/java/com/example/payments/payment/PaymentConstraintsTest.java`:

```java
package com.example.payments.payment;

import com.example.payments.PaymentsIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Goes around the entity, straight to SQL, to prove the database enforces the rule too. */
@PaymentsIntegrationTest
class PaymentConstraintsTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void aRejectedPaymentMustHaveAReason() {
        assertThatThrownBy(() -> insertPayment("REJECTED", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("CK_PAYMENT_REJECTION_REASON");
    }

    @Test
    void anAcceptedPaymentCannotHaveAReason() {
        assertThatThrownBy(() -> insertPayment("ACCEPTED", "AC04"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertPayment(String status, String reason) {
        jdbc.sql("""
                        INSERT INTO payment (debtor_account_id, creditor_name, creditor_sort_code,
                            creditor_account_number, amount, currency, reference, status,
                            rejection_reason, created_at, updated_at, version)
                        VALUES (1, 'Bob', '200000', '55779911', 1.00, 'GBP', 'Test', :status,
                            :reason, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                        """)
                .param("status", status)
                .param("reason", reason)
                .update();
    }
}
```

H2 reports constraint names in upper case, hence `CK_PAYMENT_REJECTION_REASON` in the assertion. On PostgreSQL the message would contain the lower-case name, so if you switch databases, assert on the exception type only.

This is **defence in depth**: the entity gives the right behaviour, and the database guarantees the rule holds whoever writes the data.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
```

(`MoneyTest` 4, `SortCodeTest` 2, `PaymentStatusTest` 7, `PaymentTest` 5, `PaymentApiTest` 5 and `PaymentConstraintsTest` 2, if you did both exercises.)

Your project should look like this:

```
payments/
├── pom.xml
├── requests.http
└── src/
    ├── main/
    │   ├── java/com/example/payments/
    │   │   ├── PaymentsApplication.java
    │   │   ├── config/
    │   │   │   └── ClockConfig.java
    │   │   ├── money/
    │   │   │   └── Money.java
    │   │   ├── payment/
    │   │   │   ├── Creditor.java
    │   │   │   ├── IllegalPaymentTransitionException.java
    │   │   │   ├── Payment.java
    │   │   │   ├── PaymentController.java
    │   │   │   ├── PaymentNotFoundException.java
    │   │   │   ├── PaymentProperties.java
    │   │   │   ├── PaymentRepository.java
    │   │   │   ├── PaymentService.java
    │   │   │   ├── PaymentStatus.java
    │   │   │   ├── RejectionReason.java
    │   │   │   └── SortCode.java
    │   │   └── web/
    │   │       └── ApiExceptionHandler.java
    │   └── resources/
    │       ├── application.properties
    │       └── db/migration/
    │           ├── V1__create_payment.sql
    │           └── V2__payment_rejection_reason.sql
    └── test/java/com/example/payments/
        ├── PaymentsIntegrationTest.java
        ├── money/
        │   └── MoneyTest.java
        └── payment/
            ├── PaymentApiTest.java
            ├── PaymentConstraintsTest.java
            ├── PaymentStatusTest.java
            ├── PaymentTest.java
            └── SortCodeTest.java
```

---

## Common errors

**`the switch expression does not cover all possible input values`**
You added a status to `PaymentStatus` (or deleted a `case`) without saying where it can go. That's the compiler doing exactly what you want. Add the `case`, and update the `EXPECTED` table in `PaymentStatusTest`.

**`Parameter 1 of constructor in com.example.payments.payment.PaymentService required a bean of type 'com.example.payments.payment.PaymentProperties' that could not be found.`**
`@ConfigurationPropertiesScan` is missing from `PaymentsApplication`, so Spring never created the properties bean. (Alternatively, put `@EnableConfigurationProperties(PaymentProperties.class)` on a configuration class.)

**`500` with `IllegalArgumentException: A sort code must be six digits: 2000` in the log**
The `@Valid` on the nested `creditor` field of `CreatePaymentRequest` is missing, so the `@Pattern` inside `CreditorRequest` was never checked and the bad value reached the `SortCode` constructor. Notice the message says `2000`: the constructor had already stripped the hyphens from `20-00`. Bean Validation only descends into nested objects that are marked `@Valid`.

**`Payment null cannot move from RECEIVED to SUBMITTED`**
An illegal transition on a payment that hasn't been saved yet, so it has no id. Check the order of calls in your service: you can't submit a payment that was never validated.

**`WARN … Using H2 2.4.240 which is newer than the version Flyway has been verified with.`**
Harmless. Spring Boot manages both versions and they work together. It's Flyway being cautious.

**`Web server failed to start. Port 8080 was already in use.`**
Another app (or this one, started twice) is using the port. Stop it, or set `server.port=8081` and use that in `requests.http`.

---

## Recap

- **Clearing** agrees a payment between banks. **Settlement** moves the money between them, at the Bank of England. For Faster Payments, clearing takes seconds and settlement happens later, net, in cycles, with prefunding removing the risk in between.
- **Faster Payments** (near real-time, net settlement), **Bacs** (three-day cycle, net), **CHAPS** (high value, real-time gross settlement).
- **ISO 20022** names the messages: `pacs.008` asks for a credit transfer, `pacs.002` reports its status with codes like `ACSP`, `RJCT` and `AC04`. CHAPS uses it today; Faster Payments still uses ISO 8583-based messages.
- An **explicit state machine** puts the allowed transitions in one place. An `enum` with an exhaustive switch expression makes forgotten states a compile error.
- **Entities guard their own state**: no `setStatus`, named transition methods, one checked `moveTo`.
- **Test every pair** of states, especially the ones that must be refused, against a table written independently of the code.
- **A declined payment is still a payment.** Record it with a reason. Only malformed requests are `400`s.
- **Inject a `Clock`.** Never call `Instant.now()` from code you want to test.

### Interview angle

<details>
<summary>"What's the difference between clearing and settlement?"</summary>

Clearing is the exchange and agreement of payment instructions between banks: who owes whom, and for which customer payments. Settlement is the actual transfer of funds between the banks to discharge those obligations, in the UK across accounts at the Bank of England. They can be separated in time. Faster Payments clears in near real time, so the payee can use the money immediately, but settles on a deferred net basis in cycles during the working day, with participants prefunding at the Bank of England to cover their positions. CHAPS settles each payment gross and in real time.

</details>

<details>
<summary>"How would you model a payment's status so it can't get into an invalid state?"</summary>

As an explicit state machine. An enum of statuses where each knows its allowed next statuses (a switch expression with no default, so the compiler catches a new status without rules). The entity has no status setter: business-named methods like `markSubmitted` or `reject(reason)` all go through one private method that checks the transition and throws if it's illegal. Back it up with a database `CHECK` for valid values, optimistic locking so concurrent updates can't overwrite each other, and a test that checks every pair of states, including the forbidden ones. Mention that terminal states (settled, rejected) accept nothing, and that a payment the receiving bank has accepted is undone with a return, not a status change.

</details>

<details>
<summary>"Faster Payments, Bacs or CHAPS: which would you use for a salary run, a house deposit and paying a friend?"</summary>

Salaries: Bacs Direct Credit, a bulk scheme on a three-day cycle that's cheap and predictable for large files submitted in advance. A house completion: CHAPS, because it's high value, needs to be same-day and final, and CHAPS settles each payment individually in central bank money with no upper limit. Paying a friend: Faster Payments, near real time and available 24/7, within the scheme's £1 million limit and the sending bank's own lower limits.

</details>

### Further reading

- [Pay.UK: Faster Payment System](https://www.wearepay.uk/what-we-do/payment-systems/faster-payment-system/) and [how Faster Payments work](https://www.wearepay.uk/what-we-do/payment-systems/faster-payment-system/how-faster-payments-work/)
- [Bank of England: a brief introduction to RTGS and CHAPS](https://www.bankofengland.co.uk/payments/a-brief-introduction-to-the-real-time-gross-settlement-system-and-chaps)
- [Bank of England: CHAPS transitions to ISO 20022 (June 2023)](https://www.bankofengland.co.uk/news/2023/june/chaps-transitions-to-latest-global-financial-messaging-standard)
- [Pay.UK: Faster Payments System Principles](https://www.wearepay.uk/wp-content/uploads/2026/02/Pay.UK-Faster-Payments-System-Principles-v-11-Jan-2026.pdf) (PDF): the scheme's own description of payments, references, returns and settlement
- [The Payment Services Regulations 2017, regulation 86](https://www.legislation.gov.uk/uksi/2017/752/regulation/86): execution times
- [ISO 20022](https://www.iso20022.org/): the message standard's home

**Next:** [02: Taking the money](02-taking-the-money.md). Give the bank a small ledger, take the customer's money when a payment is validated, and make sure two payments can't spend the same pound.
