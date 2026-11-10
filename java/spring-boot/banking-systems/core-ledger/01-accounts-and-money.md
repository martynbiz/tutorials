# 01: Accounts and Money

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- How a bank thinks about *your* account, and why your balance is the bank's **liability**
- Why money must never be a `double`, and the traps hidden in `BigDecimal`
- How to model money as a small, strict **value object**: a Java `record` with a compact constructor
- How to send money in JSON without losing pennies
- How to return errors as **`ProblemDetail`** (RFC 9457), including field-by-field validation errors and `404`s from your own exceptions
- How to test an API with **`MockMvcTester`**, and how to bundle test setup into your own annotation

By the end you'll have an API that opens customer accounts and returns them with a balance of exactly `"0.00"` GBP, plus a test suite that proves it.

---

## Concepts

### How it works in a real bank

Every bank runs a **core banking system**. Its centre is a **ledger**: the authoritative record of every account and every movement of money. Mobile apps, card systems and payment gateways all *ask* the ledger for balances and *tell* it about movements. None of them owns the money.

The ledger has two kinds of [account](../glossary.md#account):

- **Customer accounts:** your current account, your savings account.
- **Internal accounts:** the bank's own bookkeeping, such as its cash, the fees it has earned and the interest it has paid. Customers never see these. (You'll add the first one in guide 02.)

Here's the idea that surprises most developers. **Your current account balance is a debt the bank owes you.** When you deposit £100, the bank now has £100 more cash, which it *owns* (an [asset](../glossary.md#asset)), and it owes you £100, which you can ask for at any time (a [liability](../glossary.md#liability)). So from the bank's point of view, every customer account is a **liability** account.

That's why the `Account` you build today has a `type`, and every customer account gets `LIABILITY`. It looks odd now. In guide 02 it's the key to getting debits and credits right.

### Why money is never a `double`

`double` stores numbers in binary floating point. Most decimal fractions, including 0.1, can't be represented exactly in binary, just as 1/3 can't be written exactly in decimal:

```java
System.out.println(0.1 + 0.2);        // 0.30000000000000004
System.out.println(0.1 + 0.2 == 0.3); // false
```

On one calculation the error is tiny. Across millions of postings, interest calculations and currency conversions, it becomes real money that's impossible to account for. Regulators and auditors expect every penny to be explained.

There are two correct choices in Java:

| Option | Example | Pros | Cons |
|---|---|---|---|
| **`long` of minor units** | `1050` means £10.50 | Fast, exact, simple to store | The unit is invisible. Currencies with 0 or 3 decimal places (JPY, KWD) break naive code. |
| **`BigDecimal`** | `new BigDecimal("10.50")` | Exact decimals, the unit is visible, standard in Java banking code | Slower, verbose, and it has traps (below) |

This series uses **`BigDecimal`, wrapped in a `Money` type** that removes the traps.

### The `BigDecimal` traps

A `BigDecimal` is an unscaled integer plus a **scale** (the number of digits after the point). `10.5` is `105` with scale 1. `10.50` is `1050` with scale 2. That leads to three traps:

1. **`equals` compares scale too.** `new BigDecimal("2.0").equals(new BigDecimal("2.00"))` is **false**. `compareTo` ignores scale and returns 0. Code that puts amounts in a `HashSet`, or compares them with `equals`, silently misbehaves.
2. **`new BigDecimal(0.1)` is wrong.** It copies the `double`'s inexact value: `0.1000000000000000055511151231257827…`. Always construct from a `String`.
3. **Division can fail.** `BigDecimal.ONE.divide(new BigDecimal(3))` throws `ArithmeticException`, because the result never ends. You must say how to round. (You'll need this for interest in the Loans tutorial.)

### A `Money` value object

Rather than passing bare `BigDecimal`s around and hoping everyone remembers the rules, you'll create a `Money` type that **can't be built in an invalid state**:

- It always has a currency.
- Its scale is **always** the currency's own number of decimal places, 2 for GBP. `Money.gbp("10.5")` becomes `10.50`, so `equals` just works.
- It **refuses** fractions of a penny instead of rounding them away. Silent rounding is how money goes missing.
- It refuses to add pounds to euros.

A Java **record** is a perfect fit: it's immutable, and its `equals`/`hashCode` are generated from its components. A record's **compact constructor** (a constructor with no parameter list) runs before the fields are assigned, so it can validate or normalise the values:

```java
public record Money(BigDecimal amount, Currency currency) {
    public Money {                           // compact constructor
        amount = amount.setScale(2, ...);    // reassigns the parameter, not the field
    }
}
```

### Money in JSON

JSON numbers have no fixed precision, and JavaScript reads every number as a `double`. If you send `{"amount": 10.10}`, a browser or Node.js client may hold it as `10.0999999999999996…`. Large amounts can lose whole pennies.

The common practice in banking APIs (and in the [UK Open Banking standard](https://standards.openbanking.org.uk/)) is to **send amounts as strings** together with their currency:

```json
{ "amount": "10.10", "currency": "GBP" }
```

### Errors as `ProblemDetail`

When a request fails, the client needs a machine-readable reason. [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) ("Problem Details for HTTP APIs") standardises the shape, with content type `application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Account not found",
  "status": 404,
  "detail": "No account with id 42",
  "instance": "/accounts/42"
}
```

Spring has this built in as the `ProblemDetail` class. You'll use it for every error in this tutorial.

> **In older codebases:** before Spring 6 / Boot 3, every team invented its own error JSON (`{"errorCode": ..., "message": ...}`), usually built in a `@ControllerAdvice` returning a custom `ErrorResponse` class. The idea is the same. `ProblemDetail` just makes it standard.

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
| Artifact | `core-ledger` |
| Package name | `com.example.ledger` |
| Packaging | Jar |
| Java | 25 |

Add these dependencies:

| Dependency | Why |
|---|---|
| **Spring Web** | REST controllers (it pulls in `spring-boot-starter-webmvc`) |
| **Spring Data JPA** | Entities and repositories |
| **Flyway Migration** | Versioned SQL migrations |
| **H2 Database** | The embedded database |
| **Validation** | `@Valid`, `@NotBlank`, `@Positive` |
| **SpringDoc OpenAPI** | Swagger UI, for trying the API in a browser |

Click **Generate** and unzip the download so the project folder ends up at:

```
tutorials/workspace/banking-systems/core-ledger/
```

Open it in IntelliJ with **File → Open…**, choose the `core-ledger` folder (the one containing `pom.xml`), and trust the project. Check **File → Project Structure → Project → SDK** is set to a Java 25 JDK.

Delete the generated test `src/test/java/com/example/ledger/CoreLedgerApplicationTests.java`. You'll replace it with better tests in step 11.

### 2. Configure the application

Replace `src/main/resources/application.properties` with:

```properties
spring.application.name=core-ledger

# H2 database stored in ./data/ledger.mv.db
spring.datasource.url=jdbc:h2:file:./data/ledger
spring.datasource.username=sa
spring.h2.console.enabled=true

# Flyway owns the schema; Hibernate only checks it matches the entities
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
```

- `username=sa`: Spring Boot only defaults the username to `sa` for *in-memory* H2. For a file database it would otherwise create the database with a blank username, and the H2 console (which suggests `sa`) would refuse to log in.
- `ddl-auto=validate`: Hibernate never changes the schema. It only checks at start-up that your entities match the tables your Flyway migrations created. A mismatch stops the app immediately, instead of failing later on a real request.
- `open-in-view=false`: the database session closes when the service method's transaction ends. Any lazy loading you forgot to do inside a transaction fails loudly in development, instead of quietly running extra queries from the controller.

Add `data/` to the project's `.gitignore` so the database file is never committed:

```
### Local database ###
data/
```

### 3. The `Money` record

Create the package `com.example.ledger.money` (right-click `com.example.ledger` → **New → Package**) and add `money/Money.java`:

```java
package com.example.ledger.money;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money in one currency. Immutable, and always held at the
 * currency's own number of decimal places (2 for GBP).
 */
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

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
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

Things to notice:

- **`java.util.Currency`** knows every ISO 4217 currency and how many decimal places it uses: 2 for GBP, 0 for JPY, 3 for KWD. `getDefaultFractionDigits()` means `Money` is correct for all of them without hard-coding `2`.
- **`RoundingMode.UNNECESSARY`** is the strictest rounding mode. Scaling `10.5` up to `10.50` needs no rounding, so it succeeds. Scaling `0.001` down to 2 places *would* need rounding, so it throws. Money never gets rounded by accident.
- **Static factories** (`gbp("10.50")`) take a `String`, so nobody can sneak a `double` in.
- **`@JsonFormat(shape = STRING)`** on the `amount` component tells Jackson to write `"10.50"` rather than `10.50`. The annotation lives in `com.fasterxml.jackson.annotation`, even in Spring Boot 4 (Jackson 3 kept the annotations package unchanged so existing annotated code keeps working).
- **Every operation returns a new `Money`.** Nothing ever modifies an existing one, so a `Money` can be shared freely between threads.

You'll add `minus`, `negate` and `isLessThan` yourself in the exercises.

### 4. Prove it with tests

Create `src/test/java/com/example/ledger/money/MoneyTest.java`. IntelliJ can do this for you: put the cursor on the class name `Money`, press <kbd>Alt</kbd>+<kbd>Enter</kbd> → **Create Test**, and choose JUnit (Boot 4 uses JUnit 6, which IntelliJ may list as JUnit 5 or 6; both use the same `org.junit.jupiter` API).

```java
package com.example.ledger.money;

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
    void bigDecimalEqualsComparesScaleButMoneyDoesNot() {
        assertThat(new BigDecimal("2.0")).isNotEqualTo(new BigDecimal("2.00"));
        assertThat(new BigDecimal("2.0").compareTo(new BigDecimal("2.00"))).isZero();
        assertThat(Money.gbp("2.0")).isEqualTo(Money.gbp("2.00"));
    }

    @Test
    void rejectsFractionsOfAPenny() {
        assertThatThrownBy(() -> Money.gbp("0.001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many decimal places");
    }

    @Test
    void doubleCannotEvenAddTenPenceAndTwentyPence() {
        assertThat(0.1 + 0.2).isNotEqualTo(0.3);
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

Two of these tests (`bigDecimalEquals…` and `doubleCannot…`) don't test *your* code. They pin down how Java behaves, as runnable notes to your future self. That's a perfectly good use of a test.

**Run them in IntelliJ:** click the green arrow in the gutter next to `class MoneyTest` → **Run 'MoneyTest'**. The Run tool window shows five green ticks.

**Terminal alternative:**

```bash
./mvnw test -Dtest=MoneyTest
```

### 5. The `account` table and entity

Create `src/main/resources/db/migration/V1__create_account.sql`:

```sql
CREATE TABLE account (
    id        BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name      VARCHAR(100)             NOT NULL,
    type      VARCHAR(20)              NOT NULL,
    currency  VARCHAR(3)               NOT NULL,
    balance   NUMERIC(19, 4)           NOT NULL,
    opened_at TIMESTAMP WITH TIME ZONE NOT NULL
);
```

This SQL is **portable**: it runs unchanged on H2 and PostgreSQL. Two choices are worth explaining:

- **`NUMERIC(19, 4)`** is an exact decimal type: up to 19 digits, 4 of them after the point. GBP only needs 2, but ledgers usually store a little extra precision for things like interest accrual (calculated daily, fractions of a penny that add up). The `Money` type is what enforces 2 places for actual movements.
- **`TIMESTAMP WITH TIME ZONE`** stores an absolute instant. Banks operate across time zones and clock changes, so record times in UTC. Never store "local time on the server".

Now the enum `account/AccountType.java`:

```java
package com.example.ledger.account;

/**
 * Which side of the bank's balance sheet an account sits on.
 * A customer's current account is money the bank OWES the customer, so to
 * the bank it is a LIABILITY. The bank's own cash is an ASSET.
 */
public enum AccountType {
    ASSET,
    LIABILITY
}
```

And the entity `account/Account.java`:

```java
package com.example.ledger.account;

import com.example.ledger.money.Money;
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
@Table(name = "account")
public class Account {

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

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    protected Account() {
        // for JPA
    }

    private Account(String name, AccountType type, Currency currency) {
        this.name = name;
        this.type = type;
        this.currencyCode = currency.getCurrencyCode();
        this.balance = BigDecimal.ZERO;
        this.openedAt = Instant.now();
    }

    public static Account openCustomerAccount(String name) {
        return new Account(name, AccountType.LIABILITY, Money.GBP);
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

    public Instant getOpenedAt() {
        return openedAt;
    }
}
```

The design choices here run through the whole tutorial:

- **There are no setters.** Nothing outside this class can just set `balance = whatever`. In guide 02 you'll add exactly one method that changes the balance, and only the ledger calls it.
- **A named factory, `openCustomerAccount`,** instead of a public constructor. It says what's happening in banking terms, and it guarantees every customer account is a `LIABILITY` in GBP.
- **The database columns are plain types** (`BigDecimal`, `String`), but **the getters return `Money`.** That keeps the JPA mapping simple, while code outside the entity only ever sees the safe type. Notice that the column is `NUMERIC(19, 4)`, so the database hands back `0.0000`. The `Money` constructor normalises that to `0.00` for you.
- **`@Enumerated(EnumType.STRING)`** stores `"LIABILITY"`, not `1`. With the default `ORDINAL`, inserting a new enum constant in the middle would silently change the meaning of every existing row.

> **In older codebases:** you'll often see `@Data` or `@Getter @Setter` from Lombok on entities, with a public setter for every field. It's convenient, but it means any code anywhere can change a balance without going through the rules. See the [legacy cheat sheet](../legacy-cheatsheet.md#lombok).

### 6. Repository and service

`account/AccountRepository.java`:

```java
package com.example.ledger.account;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {
}
```

`account/AccountService.java`:

```java
package com.example.ledger.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public Account open(String name) {
        return accounts.save(Account.openCustomerAccount(name));
    }

    @Transactional(readOnly = true)
    public Account get(Long id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }
}
```

`AccountNotFoundException` doesn't exist yet. Create `account/AccountNotFoundException.java`:

```java
package com.example.ledger.account;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(Long id) {
        super("No account with id " + id);
    }
}
```

`@Transactional(readOnly = true)` is a hint to Hibernate and the database: no changes are expected, so Hibernate skips checking entities for changes (dirty checking) at the end. It's a small optimisation, and it documents your intent.

### 7. The controller

`account/AccountController.java`:

```java
package com.example.ledger.account;

import com.example.ledger.money.Money;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request) {
        Account account = accountService.open(request.name());
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

    public record OpenAccountRequest(@NotBlank @Size(max = 100) String name) {
    }

    public record AccountResponse(Long id, String name, AccountType type, Money balance, Instant openedAt) {

        static AccountResponse from(Account account) {
            return new AccountResponse(
                    account.getId(), account.getName(), account.getType(),
                    account.getBalance(), account.getOpenedAt());
        }
    }
}
```

- **Request and response records** (often called DTOs, data transfer objects) keep the API's shape separate from the entity. You can rename a column without breaking clients, and clients can never set fields they shouldn't, like `balance`.
- **`201 Created` with a `Location` header** is the REST convention for "I made a new thing, and here's where it lives". `ServletUriComponentsBuilder.fromCurrentRequest()` builds `http://localhost:8080/accounts/1` from the incoming request.
- Nesting the records inside the controller keeps small APIs tidy. Move them to their own files when they grow.

### 8. Run it, and spot the bug

Run the app: open `CoreLedgerApplication.java` and click the green arrow next to `main`, or press <kbd>Shift</kbd>+<kbd>F10</kbd> once the run configuration exists.

**Terminal alternative:**

```bash
./mvnw spring-boot:run
```

Watch the log for Flyway applying the migration:

```
Migrating schema "PUBLIC" to version "1 - create account"
Successfully applied 1 migration to schema "PUBLIC", now at version v1
```

Now create a file called `requests.http` in the project root. You'll add to it in every guide:

```http
### Open an account
POST http://localhost:8080/accounts
Content-Type: application/json

{"name": "Alice"}

### Get an account
GET http://localhost:8080/accounts/1

### Get an account that doesn't exist (should be 404)
GET http://localhost:8080/accounts/999

### Open an account with a blank name (should be 400)
POST http://localhost:8080/accounts
Content-Type: application/json

{"name": ""}
```

**(Ultimate)** IntelliJ's HTTP Client shows a green ▶ beside each `###` request. Click it to send the request and see the response.

**Free edition / terminal alternatives:** open `http://localhost:8080/swagger-ui.html` in your browser, expand **POST /accounts**, click **Try it out**, and send `{"name": "Alice"}`. Or use curl:

```bash
curl -i -X POST localhost:8080/accounts -H 'Content-Type: application/json' -d '{"name":"Alice"}'
```

You get `201 Created` and something like:

```json
{
  "id": 1,
  "name": "Alice",
  "type": "LIABILITY",
  "balance": {
    "amount": "0.00",
    "currency": "GBP",
    "negative": false,
    "positive": false,
    "zero": true
  },
  "openedAt": "2026-11-06T22:06:55.516411052Z"
}
```

The amount is a string and the currency is there, but where did `negative`, `positive` and `zero` come from?

Jackson follows the JavaBeans naming convention: any public method called `isSomething()` that returns a `boolean` is treated as a **getter** for a property called `something`. Your helper methods `isZero()`, `isPositive()` and `isNegative()` have leaked into the API. If a client starts relying on them, you're stuck with them forever.

Fix it by telling Jackson to ignore `is…` methods on `Money`. Records still serialise through their components (`amount`, `currency`). Add the annotation and its import:

```java
import com.fasterxml.jackson.annotation.JsonAutoDetect;
...

@JsonAutoDetect(isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public record Money(
```

Restart and try again. The balance is now just `{"amount": "0.00", "currency": "GBP"}`.

> **Sidebar: why not a `long` and a currency code in the JSON?** Some APIs (Stripe, for example) send `{"amount": 1050, "currency": "gbp"}` in minor units. It's equally valid. What matters is that it's **exact** and **documented**. A decimal string is easier for people to read in logs and statements, and it's what UK Open Banking uses, so it's what this series uses.

### 9. Validation errors as `ProblemDetail`

Send the blank-name request. You get a `400`, but in Spring Boot's default error format, which says nothing about *which* field is wrong:

```json
{"timestamp":"2026-11-06T22:25:05.865Z","status":400,"error":"Bad Request","path":"/accounts"}
```

Spring MVC raises `MethodArgumentNotValidException` when `@Valid` fails. Extend `ResponseEntityExceptionHandler`, Spring's base class that turns all of Spring MVC's own exceptions into `ProblemDetail` responses, and override the one method you want to enrich with field errors.

Create `web/ApiExceptionHandler.java`:

```java
package com.example.ledger.web;

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

- `ex.getBody()` is the `ProblemDetail` Spring already built for this exception. You add to it rather than starting again.
- `setProperty("errors", …)` adds an **extension member**. RFC 9457 explicitly allows extra fields alongside the standard ones.
- A `TreeMap` keeps the fields in alphabetical order, so the output is stable. `putIfAbsent` keeps the first message when one field breaks two rules.

Just by existing, this class switches every Spring MVC error (bad JSON, a missing header, an unsupported method) to `ProblemDetail`. Restart and resend the blank-name request:

```json
{
  "detail": "Invalid request content.",
  "instance": "/accounts",
  "status": 400,
  "title": "Bad Request",
  "errors": { "name": "must not be blank" }
}
```

### 10. Your own exceptions as `ProblemDetail`

Send the "doesn't exist" request from `requests.http` (`GET /accounts/999`). Even with `ApiExceptionHandler` in place, you get the old format, and the wrong status:

```json
{"timestamp":"2026-11-06T22:31:12.401Z","status":500,"error":"Internal Server Error","path":"/accounts/999"}
```

`ResponseEntityExceptionHandler` only knows about **Spring MVC's own** exceptions, such as `MethodArgumentNotValidException`. `AccountNotFoundException` is yours, so nothing handles it. Spring treats any unhandled exception as a server fault (`500`) and hands it to Spring Boot's fallback `/error` page, which uses the old format. A missing account isn't a server fault, though: the client asked for something that doesn't exist, which is a `404`.

To handle an exception of your own, add a method to `ApiExceptionHandler` and annotate it with **`@ExceptionHandler`**, naming the exception class:

```java
import com.example.ledger.account.AccountNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
...

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail handle(AccountNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Account not found");
        return problem;
    }
```

- **`@ExceptionHandler(AccountNotFoundException.class)`** tells Spring: "when any controller throws this exception, call this method instead of failing". Because the class is a `@RestControllerAdvice`, it applies to every controller, not just `AccountController`.
- In step 9, Spring had already built the `ProblemDetail` and you added to it. Here nothing exists yet, so you build one. **`ProblemDetail.forStatusAndDetail(status, detail)`** creates it with the status and a human-readable `detail`. The exception message, `"No account with id 999"`, is a good detail.
- **`setTitle`** replaces the default title for the status (`"Not Found"`) with something more specific. The title should describe the *kind* of problem, the same for every missing account. The `detail` describes *this* occurrence.
- **Returning a `ProblemDetail`** is enough. Spring uses its `status` as the response status and sends it with the `application/problem+json` content type, the media type RFC 9457 defines.
- The method is package-private and its name doesn't matter. Spring finds it by the annotation. Later guides add one of these for each new exception, so they're all called `handle` and told apart by their parameter type.

Restart and send the request again:

```json
{
  "detail": "No account with id 999",
  "instance": "/accounts/999",
  "status": 404,
  "title": "Account not found"
}
```

> **Sidebar: why not `@ResponseStatus`?** You may see `@ResponseStatus(HttpStatus.NOT_FOUND)` put on an exception class. It fixes the status code, but the response still goes through Spring Boot's `/error` page, so you get a `404` in the old format, not a `ProblemDetail`. An `@ExceptionHandler` keeps every error's shape in one place: `ApiExceptionHandler`.

### 11. API tests with `MockMvcTester`

Every API test in this tutorial needs the same setup: the full application, the real Flyway migrations, and an **in-memory** database, so tests never touch your `./data` file and always start clean. Rather than repeating that on every class, make your own annotation.

Create `src/test/java/com/example/ledger/LedgerIntegrationTest.java`:

```java
package com.example.ledger;

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
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ledger-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
public @interface LedgerIntegrationTest {
}
```

This is a **meta-annotation**: Spring looks *through* `@LedgerIntegrationTest` and finds `@SpringBootTest` and `@AutoConfigureMockMvc` on it. It has a second benefit. Every test class uses identical configuration, so Spring builds the application context **once** and caches it for all of them, which keeps the suite fast.

`DB_CLOSE_DELAY=-1` keeps the in-memory database alive for as long as the JVM runs, not just while a connection is open.

Now `src/test/java/com/example/ledger/account/AccountApiTest.java`:

```java
package com.example.ledger.account;

import com.example.ledger.LedgerIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

@LedgerIntegrationTest
class AccountApiTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void opensAnAccountWithAZeroGbpBalance() {
        var response = mvc.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Alice"}
                        """);

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).headers().containsHeader("Location");
        assertThat(response).bodyJson().extractingPath("$.type").isEqualTo("LIABILITY");
        assertThat(response).bodyJson().extractingPath("$.balance.amount").isEqualTo("0.00");
        assertThat(response).bodyJson().extractingPath("$.balance.currency").isEqualTo("GBP");
        assertThat(response).bodyJson().doesNotHavePath("$.balance.zero");
    }

    @Test
    void rejectsABlankName() {
        var response = mvc.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "  "}
                        """);

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors.name").isEqualTo("must not be blank");
    }

    @Test
    void returnsProblemDetailForAnUnknownAccount() {
        var response = mvc.get().uri("/accounts/999999");

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Account not found");
    }
}
```

**`MockMvcTester`** (Spring Framework 6.2+) is the AssertJ-flavoured version of MockMvc. Requests go through the whole Spring MVC stack (validation, Jackson, exception handlers) without opening a real network port. `@AutoConfigureMockMvc` makes one available to inject.

- `bodyJson().extractingPath("$.balance.amount")` uses a JSONPath expression to pick out a value.
- `doesNotHavePath("$.balance.zero")` is a **regression test** for the Jackson bug in step 8. If someone removes `@JsonAutoDetect`, this test catches it.
- Notice that the test asserts `"0.00"` as a **string**. If someone removes `@JsonFormat`, this fails too.
- `hasContentType(MediaType.APPLICATION_PROBLEM_JSON)` checks the response really is a `ProblemDetail` (`application/problem+json`), not Spring Boot's old error format, which is plain `application/json`. Checking the status alone wouldn't tell them apart: an `@ResponseStatus` 404 would pass.
- The unknown-account test uses `999999`, not `999`, so it can't accidentally hit an account another test opened.

> **In older codebases:** the same test with classic MockMvc looks like `mockMvc.perform(post("/accounts").contentType(...).content(...)).andExpect(status().isCreated()).andExpect(jsonPath("$.balance.amount").value("0.00"))`. Both styles work. `MockMvcTester` gives better failure messages.

**Run every test in IntelliJ:** right-click `src/test/java` → **Run 'All Tests'**.

**Terminal alternative:**

```bash
./mvnw test
```

---

## Your turn

### Exercise 1: a name that's too long

`OpenAccountRequest` has two rules on `name`: `@NotBlank` and `@Size(max = 100)`. Step 11 only tests the first.

1. Add a request to `requests.http` that opens an account with a 101-character name, and send it. Note the `errors` message.
2. Add a test to `AccountApiTest` that does the same, and checks for a `400`, the `application/problem+json` content type, and the message under `$.errors.name`.

<details>
<summary>Hint</summary>

`"x".repeat(101)` builds the name, and `.formatted(...)` puts it into the JSON, just as `Money` builds its error messages. Copy the exact message from the response you got in part 1.

</details>

<details>
<summary>Solution</summary>

In `requests.http` (the name is 101 `x`s):

```http
### Open an account with a 101-character name (should be 400)
POST http://localhost:8080/accounts
Content-Type: application/json

{"name": "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"}
```

In `AccountApiTest`:

```java
@Test
void rejectsANameLongerThan100Characters() {
    var response = mvc.post().uri("/accounts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                    {"name": "%s"}
                    """.formatted("x".repeat(101)));

    assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(response).bodyJson().extractingPath("$.errors.name").isEqualTo("size must be between 0 and 100");
}
```

`@Size(max = 100)` has a default `min` of `0`, which is why the message mentions both. A name of exactly 100 characters is allowed. That matches the `VARCHAR(100)` column, so the database never has to reject it.

</details>

### Exercise 2: finish `Money`

Guides 02 and 03 need three more operations. Add them, each with a test in `MoneyTest`:

- `Money minus(Money other)`: subtract. It must refuse a different currency.
- `Money negate()`: flip the sign.
- `boolean isLessThan(Money other)`: compare. It must refuse a different currency. **Don't use `equals`.**

Then add a test showing that `Money.gbp("5.00").minus(Money.gbp("7.50"))` is `Money.gbp("-2.50")`, and that `Money.gbp("0.10").plus(Money.gbp("0.20"))` is exactly `Money.gbp("0.30")`, the sum `double` couldn't manage.

<details>
<summary>Solution</summary>

In `Money`:

```java
public Money minus(Money other) {
    requireSameCurrency(other);
    return new Money(amount.subtract(other.amount), currency);
}

public Money negate() {
    return new Money(amount.negate(), currency);
}

public boolean isLessThan(Money other) {
    requireSameCurrency(other);
    return amount.compareTo(other.amount) < 0;
}
```

In `MoneyTest`:

```java
@Test
void addsAndSubtracts() {
    assertThat(Money.gbp("0.10").plus(Money.gbp("0.20"))).isEqualTo(Money.gbp("0.30"));
    assertThat(Money.gbp("5.00").minus(Money.gbp("7.50"))).isEqualTo(Money.gbp("-2.50"));
}
```

`compareTo` is right for ordering because it ignores scale. Inside `Money` the scales always match anyway, but using `compareTo` means the method stays correct even if that ever changes.

</details>

---

## Checkpoint

All tests pass:

```bash
./mvnw test
```

```
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
```

(6 in `MoneyTest` and 4 in `AccountApiTest`, if you did both exercises.)

You can open an account, fetch it, and get a `400` with field errors for a blank name and a `404` `ProblemDetail` for an unknown id.

Your project should look like this:

```
core-ledger/
├── pom.xml
├── requests.http
└── src/
    ├── main/
    │   ├── java/com/example/ledger/
    │   │   ├── CoreLedgerApplication.java
    │   │   ├── account/
    │   │   │   ├── Account.java
    │   │   │   ├── AccountController.java
    │   │   │   ├── AccountNotFoundException.java
    │   │   │   ├── AccountRepository.java
    │   │   │   ├── AccountService.java
    │   │   │   └── AccountType.java
    │   │   ├── money/
    │   │   │   └── Money.java
    │   │   └── web/
    │   │       └── ApiExceptionHandler.java
    │   └── resources/
    │       ├── application.properties
    │       └── db/migration/
    │           └── V1__create_account.sql
    └── test/java/com/example/ledger/
        ├── LedgerIntegrationTest.java
        ├── account/
        │   └── AccountApiTest.java
        └── money/
            └── MoneyTest.java
```

---

## Common errors

**`Schema validation: wrong column type encountered in column [currency] in table [account]; found [character (Types#CHAR)], but expecting [varchar(3) (Types#VARCHAR)]`**
You used `CHAR(3)` in the migration. Hibernate maps a `String` to `VARCHAR`, and `validate` is strict about the difference. Use `VARCHAR(3)`. Because Flyway has already recorded `V1` as applied, either delete the `data/` folder (fine in development) or add a `V2` migration that alters the column. **Never edit a migration that has already run.**

**`Schema validation: missing table [account]`**
Flyway didn't find your migration. Check the path is exactly `src/main/resources/db/migration/` and the file name has **two** underscores: `V1__create_account.sql`.

**`IllegalArgumentException: 0.001 has too many decimal places for GBP`**
That's `Money` doing its job. If you hit it from the API, validate the request before converting it (guide 02 shows how, with `@Digits`).

**`ArithmeticException: Rounding necessary`**
You called `setScale(2)` without a rounding mode, or with `RoundingMode.UNNECESSARY`, outside `Money`'s `try` block. Decide deliberately: either reject the value (what `Money` does) or round with an explicit `RoundingMode` and a comment explaining why.

**`GET /accounts/999` returns `500` with `timestamp`, `status`, `error` and `path`**
That's Spring Boot's old error format, so nothing handled `AccountNotFoundException`. Extending `ResponseEntityExceptionHandler` isn't enough on its own: check `ApiExceptionHandler` has the `@ExceptionHandler(AccountNotFoundException.class)` method from step 10, and that you restarted the app.

**`"zero": true` appears in your JSON**
You skipped the `@JsonAutoDetect(isGetterVisibility = NONE)` fix in step 8.

**`Web server failed to start. Port 8080 was already in use.`**
Something else is using port 8080: another app, a Docker container, or this app running twice (once from IntelliJ and once from the terminal). Stop the other one, or add `server.port=8081` to `application.properties` and use that port in `requests.http`.

**`Database may be already in use: ".../data/ledger.mv.db"`**
The app is already running somewhere and has the H2 file open. Only run it one way at a time.

**`Cannot resolve symbol 'AutoConfigureMockMvc'`**
In Spring Boot 4 it moved to `org.springframework.boot.webmvc.test.autoconfigure`. Older answers online use `org.springframework.boot.test.autoconfigure.web.servlet`.

---

## Recap

- To the bank, a **customer account is a liability**: money the bank owes. The bank's own cash is an **asset**.
- **Never use `double` for money.** Use `BigDecimal` built from strings, inside a `Money` type that fixes the scale, refuses silent rounding and refuses to mix currencies.
- **`BigDecimal.equals` compares scale.** Normalise the scale, or use `compareTo`.
- **Send money in JSON as a string, together with the currency.**
- **Entities guard their own state**: no setters, named factories, and safe types out of the getters.
- **`ProblemDetail`** gives every error a standard shape. Extend `ResponseEntityExceptionHandler` to add detail such as field errors.
- **`ResponseEntityExceptionHandler` only covers Spring MVC's own exceptions.** Map your own with an `@ExceptionHandler` method that returns a `ProblemDetail`, or they become `500`s in Spring Boot's old error format.
- **A meta-annotation** (`@LedgerIntegrationTest`) gives every integration test identical configuration and one cached application context.

### Interview angle

<details>
<summary>"How would you represent money in Java?"</summary>

Never `double` or `float`, because binary floating point can't represent most decimal fractions exactly, so errors accumulate. Use `BigDecimal` constructed from strings, or a `long` of minor units. Either way, wrap it in a value type that carries the currency, fixes the scale to the currency's decimal places, refuses to mix currencies, and makes rounding explicit with a `RoundingMode`. Mention the `BigDecimal.equals` scale trap, and that JSON should carry amounts as strings.

</details>

<details>
<summary>"Is a customer's deposit an asset or a liability for the bank?"</summary>

A liability. The bank owes that money back to the customer on demand. The cash the bank received is the matching asset. This is why, in the bank's books, a deposit *credits* the customer's account.

</details>

<details>
<summary>"Why have separate request/response DTOs instead of returning the entity?"</summary>

They decouple the API contract from the database schema. You can't accidentally expose internal fields, clients can't set fields they shouldn't (mass assignment), there are no lazy-loading surprises during serialisation, and the API can evolve independently of the tables.

</details>

### Further reading

- [`java.math.BigDecimal` Javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/math/BigDecimal.html): read the class description on scale and `equals`
- [RFC 9457: Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457)
- [Spring Framework reference: Error Responses](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)
- [Spring Framework reference: MockMvcTester (AssertJ integration)](https://docs.spring.io/spring-framework/reference/testing/mockmvc/assertj.html)

**Next:** [02: Double-entry postings](02-double-entry-postings.md). Money starts moving, and you'll find out why every movement is written down twice.
