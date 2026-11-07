# 01: Amortisation Schedules

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- What a personal loan actually is: **principal**, **rate**, **term**, and why the rate on the advert (the **APR**) isn't the rate in the formula
- How a **repayment (annuity) loan** works, and the formula that turns "£10,000 over 3 years at 6.9%" into a monthly payment
- **Day count conventions**, and why February's interest is smaller than March's
- `BigDecimal` division done properly: `MathContext`, `RoundingMode`, and where to round
- The banker's rule for schedules: **round each line to the penny, and let the last instalment absorb the difference**, so the totals reconcile exactly
- Monthly date arithmetic with `java.time`: `plusMonths`, end-of-month clamping, leap years, and the drift bug that catches everyone
- **Parameterised tests** that prove a property ("principal repaid = principal borrowed") across many loans at once

By the end you'll have an API that quotes a loan and returns its full repayment schedule, to the penny, with tests that prove every schedule adds up.

---

## Concepts

### How it works in a real bank

A **personal loan** is the simplest lending product a retail bank sells. A customer borrows a fixed amount, for example £10,000, and pays it back in equal monthly instalments over a fixed term, for example 36 months. The ones in this tutorial are:

- **Unsecured:** nothing (like a house or a car) can be repossessed if the customer stops paying. The bank relies on the customer's creditworthiness alone, which is why unsecured rates are higher than mortgage rates.
- **Fixed-rate:** the interest rate is agreed at the start and never changes.
- **Amortising:** every payment covers that month's interest *and* repays some of the amount borrowed, so the debt reaches exactly zero with the last payment.

From the bank's point of view, a loan is an [asset](../glossary.md#asset): money the customer owes the bank. (A current account is the opposite, a [liability](../glossary.md#liability). If that idea is new to you, the Core ledger tutorial explains it properly.)

Behind the counter, a loan's life looks like this:

```
 Application ──► Credit decision ──► Agreement ──► Drawdown ──► Monthly repayments ──► Paid off
 (out of scope)  (out of scope)        signed       money paid     interest + principal      (or settled
                                                    out            every month               early)
```

This tutorial builds the right-hand side: the **loan servicing** system that turns a signed agreement into a schedule, pays the money out, charges interest every day, collects repayments, and spots customers who fall behind. In a real bank, deciding *whether* to lend (underwriting, affordability and credit checks) is a separate system with its own rules.

### Principal, rate and term

Three numbers define a simple loan:

| Term | Meaning | Example |
|---|---|---|
| **Principal** | The amount borrowed, and later, the amount still owed (excluding interest) | £10,000 |
| **Nominal annual rate** | The yearly interest rate written into the agreement, before any compounding | 6.9% |
| **Term** | How long the customer has to repay | 36 months |

From those, the bank works out the **monthly payment**, here £308.31, and the full schedule.

### Nominal rate vs APR

The rate in your formula is not the rate on the advert. UK lenders must advertise the **APR** (annual percentage rate of charge), and it differs from the nominal rate in two ways:

1. **It includes most compulsory fees,** not just interest. An arrangement fee pushes the APR up even when the interest rate stays the same.
2. **It's an annualised, compounded rate.** Interest charged monthly costs a little more over a year than the nominal annual figure suggests. Even with no fees at all, a 6.9% nominal rate charged monthly is equivalent to about 7.12% a year. (You'll calculate that yourself in Exercise 2.)

For consumer credit not secured on land, the APR must be calculated using the formula in the FCA's Consumer Credit sourcebook, [CONC App 1.2](https://handbook.fca.org.uk/handbook/CONC/App/1/2.html), which discounts every payment and fee the customer makes back to the start date. Lenders that advertise an APR must show a **representative APR**: one at or below which they reasonably expect at least 51% of the resulting agreements to be made (see the FCA's [glossary definition](https://handbook.fca.org.uk/handbook/glossary/G3354.html)). That's why the rate you're *offered* can be higher than the one on the advert.

**This tutorial uses the nominal rate everywhere,** because that's what drives the arithmetic. It doesn't calculate the regulated APR. In a real bank, a separate, carefully tested component does that, because the APR in a credit agreement is a regulatory disclosure.

### Repayment loans and the annuity formula

A **repayment** loan (also called an **annuity** or **amortising** loan) has a level payment every month. Early on, most of each payment is interest, because the debt is large. Later, most of it is principal. The table that shows this split for every month is the **amortisation schedule**:

```
 #   Due date    Payment   Interest  Principal   Balance after
 1   2026-02-28   308.31     52.93     255.38      9,744.62
 2   2026-03-31   308.31     57.11     251.20      9,493.42
 …
36   2029-01-31   305.99      1.78     304.21          0.00
```

The level payment comes from the **annuity formula**:

```
            P × r
payment = ─────────────
          1 − (1 + r)⁻ⁿ
```

- `P` is the principal (£10,000)
- `r` is the **monthly** rate: the nominal annual rate ÷ 12 (6.9% ÷ 12 = 0.575% = 0.00575)
- `n` is the number of monthly payments (36)

It's the payment for which the present value of all `n` payments, discounted at `r` per month, equals exactly `P`. You don't need to derive it. You do need to implement it without losing a penny.

### Day count conventions

The annuity formula treats every month as identical. Real months aren't. Most lenders that charge interest daily work out each day's interest on the outstanding balance, so a 31-day month costs more than a 28-day one. How a rate is turned into a daily amount is the **day count convention**. The common ones are:

| Convention | A day's interest is… | Where you'll meet it |
|---|---|---|
| **Actual/365 Fixed** | annual rate ÷ 365, every day, even in leap years | Sterling money markets (SONIA uses it), many UK retail products |
| **Actual/360** | annual rate ÷ 360 | US dollar and euro money markets |
| **30/360** | every month counts as 30 days, every year as 360 | Many bonds, some older loan systems |
| **Actual/Actual** | annual rate ÷ 365 or 366, depending on the year | Government bonds |

**This tutorial uses Actual/365 Fixed.** Each period's interest is *balance × rate ÷ 365 × the actual number of days in that period*. That's why the first instalment of a loan drawn on 31 January (28 days to 28 February) has less interest than the second (31 days to 31 March), even though the balance is lower in March.

> **Sidebar: which convention does a real loan use?** Whatever its credit agreement says. The convention is part of the contract, and lenders differ. Some calculate interest monthly at exactly rate ÷ 12 (effectively 30/360). Some calculate it daily. If you work on a lending system, the very first question to ask is "which day count, and where do we round?"

Using actual days has a consequence: the level payment from the formula (which assumes equal months) won't take the balance to exactly zero. That's the next problem.

### Rounding, and the final instalment

Money can only move in whole pennies. So every line of the schedule has to be rounded, and those roundings add up. There are several defensible ways to handle that. This tutorial uses one that's common and easy to audit:

1. Round the **level payment** to the penny (half up).
2. Work out each month's **interest** at high precision, then round it to the penny (half up), **once**, at the end of the period.
3. **Principal part = payment − interest.** No rounding needed, both are already pennies.
4. The **last instalment** isn't the level payment. It's *whatever principal is left, plus that month's interest*.

Rule 4 is what makes the schedule reconcile: the principal parts always add up to **exactly** the amount borrowed, so the balance after the last instalment is exactly £0.00. In our example, the last payment is £305.99, not £308.31, because the actual-day interest and the penny rounding together came out £2.32 in the customer's favour over three years.

> **Sidebar: half up or half even?** `HALF_UP` rounds 0.005 to 0.01. `HALF_EVEN` ("banker's rounding") rounds to the nearest even digit, so 0.005 → 0.00 and 0.015 → 0.02, which avoids a systematic upward bias across millions of calculations. Both are used in banking. The real answer is "whatever the product terms and the finance team say". The important thing is to choose **deliberately**, write it down, and use the same rule everywhere.

### `BigDecimal` division needs instructions

The formula divides, and `BigDecimal` refuses to divide when the answer has infinitely many digits:

```java
BigDecimal.ONE.divide(BigDecimal.valueOf(365));
// ArithmeticException: Non-terminating decimal expansion; no exact representable decimal result.
```

You have to say how precise the answer should be. There are two ways:

- **A `MathContext`**: a number of *significant* digits plus a rounding mode. `MathContext.DECIMAL128` (34 significant digits, `HALF_EVEN`) is a good default for intermediate results in a formula.
- **A scale plus a `RoundingMode`**: `divide(divisor, 6, RoundingMode.HALF_UP)` means "6 digits after the decimal point, rounding half up". Use this when you need a fixed number of decimal places.

The rule of thumb: **carry lots of precision through the calculation, and round to pennies once, at a point you chose on purpose.**

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
| Artifact | `loans` |
| Package name | `com.example.loans` |
| Packaging | Jar |
| Java | 25 |

Add these dependencies:

| Dependency | Why |
|---|---|
| **Spring Web** | REST controllers |
| **Spring Data JPA** | Entities and repositories (from guide 02) |
| **Flyway Migration** | Versioned SQL migrations (from guide 02) |
| **H2 Database** | The embedded database |
| **Validation** | `@Valid`, `@DecimalMin`, `@Digits` |
| **SpringDoc OpenAPI** | Swagger UI, for trying the API in a browser |

You'll add **Spring Batch JDBC** in guide 03, when you need it.

Click **Generate** and unzip the download so the project ends up at:

```
tutorials/workspace/banking-systems/loans/
```

Open it in IntelliJ with **File → Open…**, choose the `loans` folder (the one containing `pom.xml`), and trust the project. Check **File → Project Structure → Project → SDK** is set to a Java 25 JDK.

Delete the generated test `src/test/java/com/example/loans/LoansApplicationTests.java`. You'll write better ones.

### 2. Configure the application

Replace `src/main/resources/application.properties` with:

```properties
spring.application.name=loans

# H2 database stored in ./data/loans.mv.db
spring.datasource.url=jdbc:h2:file:./data/loans
spring.datasource.username=sa
spring.h2.console.enabled=true

# Flyway owns the schema; Hibernate only checks it matches the entities
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
```

- `username=sa`: Spring Boot only defaults the username to `sa` for *in-memory* H2. Set it explicitly for a file database, or the H2 console won't let you in.
- `ddl-auto=validate`: Hibernate never changes the schema. It only checks at start-up that your entities match the tables Flyway created.
- `open-in-view=false`: the database session closes when the service method's transaction ends, so a forgotten lazy load fails loudly instead of quietly running extra queries.

Add `data/` to the project's `.gitignore`:

```
### Local database ###
data/
```

### 3. The `Money` record

Every system in this series has its own copy of a small `Money` type: a `BigDecimal` amount plus a `java.util.Currency`, held at exactly the currency's number of decimal places, refusing to round silently and refusing to mix currencies. The Core ledger tutorial covers why, and each trap it avoids, properly. Here's the finished version.

Create `money/Money.java` (paths like this mean `src/main/java/com/example/loans/money/Money.java`):

```java
package com.example.loans.money;

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

The short version of what matters:

- **`RoundingMode.UNNECESSARY`** in the compact constructor means `Money` *never* rounds. `Money.gbp("10.5")` becomes `10.50`, but `Money.gbp("0.001")` throws. That's important in this tutorial: every rounding in a schedule must be one *you* wrote, with a mode you chose, never an accident.
- **`@JsonFormat(shape = STRING)`** sends amounts as `"10.50"`, not `10.50`, so JavaScript clients can't turn them into floating-point numbers.
- **`@JsonAutoDetect(isGetterVisibility = NONE)`** stops Jackson from treating `isZero()` and friends as JSON properties.
- **`plus` and `minus` refuse mixed currencies.** GBP is the only currency here, but the check costs nothing.

Add a quick test, `src/test/java/com/example/loans/money/MoneyTest.java`:

```java
package com.example.loans.money;

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
    void addsAndSubtractsExactly() {
        assertThat(Money.gbp("0.10").plus(Money.gbp("0.20"))).isEqualTo(Money.gbp("0.30"));
        assertThat(Money.gbp("5.00").minus(Money.gbp("7.50"))).isEqualTo(Money.gbp("-2.50"));
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

### 4. Interest arithmetic in one place

Interest is calculated in two places in this tutorial: here, to build the schedule, and in guide 03, when the end-of-day batch accrues interest every night. If those two calculations ever disagreed, even by a fraction of a penny, the bank would charge a customer something different from what their schedule promised. So both use the same class.

Create `schedule/InterestMath.java`:

```java
package com.example.loans.schedule;

import com.example.loans.money.Money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Interest arithmetic shared by the schedule and (later) the end-of-day accrual,
 * so the two can never disagree.
 *
 * Day count convention: Actual/365 Fixed. Every day earns 1/365 of the annual
 * rate, including 29 February.
 */
public final class InterestMath {

    /** 34 significant digits, rounding half-even: plenty for intermediate results. */
    public static final MathContext MC = MathContext.DECIMAL128;

    /** Daily accruals are kept to 6 decimal places (a millionth of a pound). */
    public static final int ACCRUAL_SCALE = 6;

    private static final BigDecimal DAYS_IN_YEAR = BigDecimal.valueOf(365);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private InterestMath() {
    }

    /** One day's interest on {@code balance}, at {@code annualRatePercent} (6.9 means 6.9%). */
    public static BigDecimal dailyInterest(Money balance, BigDecimal annualRatePercent) {
        return balance.amount()
                .multiply(annualRatePercent)
                .divide(HUNDRED.multiply(DAYS_IN_YEAR), ACCRUAL_SCALE, RoundingMode.HALF_UP);
    }

    /** Interest for {@code days} days at a constant balance, rounded to the penny. */
    public static Money interestForDays(Money balance, BigDecimal annualRatePercent, long days) {
        BigDecimal accrued = dailyInterest(balance, annualRatePercent).multiply(BigDecimal.valueOf(days));
        return toPence(accrued, balance);
    }

    /** Rounds an accrued amount to the currency's minor unit: half up, so £0.005 becomes £0.01. */
    public static Money toPence(BigDecimal accrued, Money like) {
        int places = like.currency().getDefaultFractionDigits();
        return new Money(accrued.setScale(places, RoundingMode.HALF_UP), like.currency());
    }

    /** The nominal rate per month as a fraction: 6.9% a year is 0.00575 a month. */
    public static BigDecimal monthlyRate(BigDecimal annualRatePercent) {
        return annualRatePercent.divide(HUNDRED.multiply(BigDecimal.valueOf(12)), MC);
    }
}
```

Decisions worth noticing:

- **The rate is a percentage `BigDecimal`** (`6.9`), not a fraction (`0.069`) and certainly not a `double`. Percentages are what appear in agreements, APIs and databases, so that's what the code takes. The `÷ 100` happens in exactly one place.
- **A day's interest is kept to 6 decimal places.** £10,000 at 6.9% earns £1.8904109589… a day. Rounding that to £1.89 every day would undercharge by a fraction of a penny every day, on every loan. Banks hold accruals at higher precision than pennies for exactly this reason. Six places is this tutorial's choice. Real systems vary.
- **`interestForDays` multiplies the *daily* amount by the number of days, then rounds once.** It deliberately does *not* compute `balance × rate × days ÷ 365` in one go. In guide 03, the nightly batch will add up the same 6-decimal daily amount once per day. Building the schedule from that same daily figure means the schedule and the batch agree to the penny. (You'll prove it with a test in guide 03.)
- **`monthlyRate` uses `MC`** (34 significant digits) because it feeds the annuity formula, where the result is rounded later.
- **The class is `final` with a private constructor**, the standard Java shape for a bag of pure functions.

Test it in `src/test/java/com/example/loans/schedule/InterestMathTest.java`:

```java
package com.example.loans.schedule;

import com.example.loans.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterestMathTest {

    @Test
    void dailyInterestIsAnnualRateOver365() {
        // £10,000 × 6.9% ÷ 365 = 1.8904109589… → 1.890411 at 6 decimal places
        assertThat(InterestMath.dailyInterest(Money.gbp("10000.00"), new BigDecimal("6.9")))
                .isEqualByComparingTo("1.890411");
    }

    @Test
    void interestForDaysRoundsOnceAtTheEnd() {
        // 28 days × 1.890411 = 52.931508 → £52.93
        assertThat(InterestMath.interestForDays(Money.gbp("10000.00"), new BigDecimal("6.9"), 28))
                .isEqualTo(Money.gbp("52.93"));
    }

    @Test
    void divideWithoutARoundingRuleFailsForRecurringDecimals() {
        assertThatThrownBy(() -> BigDecimal.ONE.divide(BigDecimal.valueOf(365)))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("Non-terminating decimal expansion");
    }
}
```

`isEqualByComparingTo("1.890411")` compares with `compareTo`, so it ignores scale. That's the right assertion for a raw `BigDecimal`. (`isEqualTo` would also check the scale is exactly 6.)

### 5. The annuity formula

Create `schedule/Annuity.java`:

```java
package com.example.loans.schedule;

import com.example.loans.money.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static com.example.loans.schedule.InterestMath.MC;

/** The level monthly payment that repays a loan over a fixed term. */
public final class Annuity {

    private Annuity() {
    }

    /**
     * payment = P × r / (1 − (1 + r)^−n), with r the monthly rate and n the number of months.
     * Rounded half up to the penny; the final instalment absorbs the difference.
     */
    public static Money monthlyPayment(Money principal, BigDecimal annualRatePercent, int months) {
        int places = principal.currency().getDefaultFractionDigits();
        if (annualRatePercent.signum() == 0) {
            // r = 0 would divide by zero: an interest-free loan is just principal / n
            BigDecimal payment = principal.amount().divide(BigDecimal.valueOf(months), places, RoundingMode.HALF_UP);
            return new Money(payment, principal.currency());
        }
        BigDecimal r = InterestMath.monthlyRate(annualRatePercent);
        BigDecimal growth = BigDecimal.ONE.add(r).pow(months, MC);            // (1 + r)^n
        BigDecimal discount = BigDecimal.ONE.subtract(BigDecimal.ONE.divide(growth, MC)); // 1 − (1 + r)^−n
        BigDecimal payment = principal.amount().multiply(r, MC).divide(discount, MC);
        return new Money(payment.setScale(places, RoundingMode.HALF_UP), principal.currency());
    }
}
```

- **`(1 + r)⁻ⁿ` is written as `1 ÷ (1 + r)ⁿ`.** `BigDecimal.pow(int, MathContext)` raises to a whole-number power. Dividing one by the result gives the negative power, without any `double`s.
- **Every intermediate operation passes `MC`.** Leave it off `multiply` and nothing breaks (multiplication is always exact), but the numbers grow digits on every step. Leave it off `divide` and you get the "non-terminating" exception from the test above.
- **The 0% case is special.** With `r = 0` the formula divides zero by zero. Interest-free loans are real (retailers offer them), so the code handles them: the payment is simply principal ÷ months.
- **The result is rounded exactly once,** with `setScale(2, HALF_UP)`, and only then wrapped in `Money`.

> **In older codebases:** you'll often see `Math.pow(1 + r, -n)` with `double`s, then `new BigDecimal(result).setScale(2, BigDecimal.ROUND_HALF_UP)`. For one schedule the answer is usually right. The trouble is "usually": `double` arithmetic can land a hair either side of a half-penny, and the same loan can then round differently on two systems. See the [legacy cheat sheet](../legacy-cheatsheet.md#money-in-older-code).

### 6. Due dates, and the end-of-month trap

The first instalment falls due one month after drawdown, and every month after that on the same day of the month. Java's `LocalDate.plusMonths` handles short months by **clamping** to the last valid day: 31 January plus one month is 28 February (or 29 in a leap year).

That clamping hides a trap. If you add one month to *the previous due date*, the clamped day sticks:

```
31 Jan → +1 month → 28 Feb → +1 month → 28 Mar   ✗ (should be 31 Mar)
```

The fix is to always count from the **anchor date**: drawdown + 1 month, drawdown + 2 months, and so on.

Create `schedule/DueDates.java`:

```java
package com.example.loans.schedule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Monthly due dates on the drawdown day-of-month, clamped to shorter months. */
public final class DueDates {

    private DueDates() {
    }

    public static List<LocalDate> monthly(LocalDate drawdownDate, int count) {
        List<LocalDate> dates = new ArrayList<>(count);
        for (int k = 1; k <= count; k++) {
            // Always count from the anchor date. plusMonths clamps 31 Jan + 1 month to 28/29 Feb,
            // but 31 Jan + 2 months is still 31 Mar.
            dates.add(drawdownDate.plusMonths(k));
        }
        return dates;
    }
}
```

And `src/test/java/com/example/loans/schedule/DueDatesTest.java`:

```java
package com.example.loans.schedule;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DueDatesTest {

    @Test
    void clampsToTheEndOfShorterMonthsWithoutDrifting() {
        assertThat(DueDates.monthly(LocalDate.of(2026, 1, 31), 4)).containsExactly(
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 4, 30),
                LocalDate.of(2026, 5, 31));
    }

    @Test
    void knowsAboutLeapYears() {
        assertThat(DueDates.monthly(LocalDate.of(2028, 1, 31), 1))
                .containsExactly(LocalDate.of(2028, 2, 29));
    }

    @Test
    void chainingPlusMonthsDriftsToThe28th() {
        // The bug DueDates avoids: each step starts from an already-clamped date
        LocalDate date = LocalDate.of(2026, 1, 31);
        date = date.plusMonths(1); // 2026-02-28
        date = date.plusMonths(1); // 2026-03-28, not 31!
        assertThat(date).isEqualTo(LocalDate.of(2026, 3, 28));
    }
}
```

The third test is another "pin down how Java behaves" test. It documents the bug, so nobody "simplifies" `DueDates` into a loop that adds a month to the previous date.

> **Sidebar: other `java.time` tools for monthly dates.** `YearMonth` represents a month without a day. `YearMonth.from(date).plusMonths(k).atEndOfMonth()` gives "the last day of the month, k months later", which is how you'd write a product that is always due on the last day of the month. `date.with(TemporalAdjusters.lastDayOfMonth())` does the same for a single date. `ChronoUnit.DAYS.between(a, b)` counts the days from `a` (inclusive) to `b` (exclusive), which is exactly the "days in this interest period" number you need next.

> **Sidebar: weekends and bank holidays.** Real loans usually move a due date that lands on a non-working day, often to the next working day, which needs a holiday calendar (in the UK, published each year for England and Wales, Scotland and Northern Ireland separately). This tutorial keeps the unadjusted dates. If you add a business-day rule, remember that moving the due date changes the number of days in two interest periods.

### 7. The schedule

Three small types describe the input and the output. Create `schedule/LoanTerms.java`:

```java
package com.example.loans.schedule;

import com.example.loans.money.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** What the customer asked for: how much, at what rate, for how long, from when. */
public record LoanTerms(Money principal, BigDecimal annualRatePercent, int termMonths, LocalDate drawdownDate) {

    public LoanTerms {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(annualRatePercent, "annualRatePercent must not be null");
        Objects.requireNonNull(drawdownDate, "drawdownDate must not be null");
        if (!principal.isPositive()) {
            throw new IllegalArgumentException("principal must be positive");
        }
        if (annualRatePercent.signum() < 0) {
            throw new IllegalArgumentException("annualRatePercent must not be negative");
        }
        if (termMonths < 1) {
            throw new IllegalArgumentException("termMonths must be at least 1");
        }
    }
}
```

`schedule/ScheduledInstalment.java`:

```java
package com.example.loans.schedule;

import com.example.loans.money.Money;

import java.time.LocalDate;

/** One line of an amortisation schedule. payment = interest + principal, always. */
public record ScheduledInstalment(
        int number,
        LocalDate dueDate,
        long days,
        Money payment,
        Money interest,
        Money principal,
        Money balanceAfter) {
}
```

`schedule/Schedule.java`:

```java
package com.example.loans.schedule;

import com.example.loans.money.Money;

import java.util.List;

public record Schedule(LoanTerms terms, Money regularPayment, List<ScheduledInstalment> instalments) {

    public Schedule {
        instalments = List.copyOf(instalments);
    }

    public Money totalInterest() {
        return instalments.stream().map(ScheduledInstalment::interest)
                .reduce(Money.zero(terms.principal().currency()), Money::plus);
    }

    public Money totalRepayable() {
        return instalments.stream().map(ScheduledInstalment::payment)
                .reduce(Money.zero(terms.principal().currency()), Money::plus);
    }
}
```

`List.copyOf` in the compact constructor makes the record genuinely immutable: nobody can add a line to a schedule after it's built, even through the list they passed in.

Now the calculator itself, `schedule/ScheduleCalculator.java`:

```java
package com.example.loans.schedule;

import com.example.loans.money.Money;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a repayment (annuity) schedule: a level payment every month, with each
 * month's interest worked out on the actual number of days in the period.
 */
public final class ScheduleCalculator {

    private ScheduleCalculator() {
    }

    public static Schedule schedule(LoanTerms terms) {
        Money payment = Annuity.monthlyPayment(terms.principal(), terms.annualRatePercent(), terms.termMonths());
        List<LocalDate> dueDates = DueDates.monthly(terms.drawdownDate(), terms.termMonths());

        List<ScheduledInstalment> lines = new ArrayList<>();
        Money balance = terms.principal();
        LocalDate periodStart = terms.drawdownDate();
        for (int k = 1; k <= terms.termMonths(); k++) {
            LocalDate dueDate = dueDates.get(k - 1);
            long days = ChronoUnit.DAYS.between(periodStart, dueDate);
            Money interest = InterestMath.interestForDays(balance, terms.annualRatePercent(), days);

            boolean last = k == terms.termMonths();
            // The final instalment clears whatever is left, so the principal parts always
            // add up to exactly the amount borrowed.
            Money principalPart = last ? balance : payment.minus(interest);
            Money thisPayment = last ? principalPart.plus(interest) : payment;
            if (principalPart.isNegative()) {
                throw new IllegalArgumentException(
                        "Payment %s does not cover the interest %s in month %d".formatted(payment, interest, k));
            }
            balance = balance.minus(principalPart);

            lines.add(new ScheduledInstalment(k, dueDate, days, thisPayment, interest, principalPart, balance));
            periodStart = dueDate;
        }
        return new Schedule(terms, payment, lines);
    }
}
```

Walk through one line. For the £10,000 loan drawn on 31 January 2026:

| Step | Value |
|---|---|
| `days` from 31 Jan to 28 Feb | 28 |
| `interest` = 1.890411 × 28 = 52.931508, rounded | £52.93 |
| `principalPart` = 308.31 − 52.93 | £255.38 |
| `balance` = 10,000.00 − 255.38 | £9,744.62 |

All the arithmetic after the interest calculation is `Money` arithmetic on whole pennies, so it's exact. The only roundings are the two you chose: the payment, and each period's interest.

The `principalPart.isNegative()` guard catches a nonsense product: a payment that doesn't even cover the interest would make the debt *grow* (**negative amortisation**). The API validation in step 9 keeps rates and terms in a range where that can't happen, but the calculator shouldn't trust its callers.

### 8. Prove that every schedule reconciles

Create `src/test/java/com/example/loans/schedule/ScheduleCalculatorTest.java`:

```java
package com.example.loans.schedule;

import com.example.loans.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleCalculatorTest {

    private static final LoanTerms TEN_THOUSAND = new LoanTerms(
            Money.gbp("10000.00"), new BigDecimal("6.9"), 36, LocalDate.of(2026, 1, 31));

    @Test
    void levelMonthlyPaymentFromTheAnnuityFormula() {
        Schedule schedule = ScheduleCalculator.schedule(TEN_THOUSAND);

        assertThat(schedule.regularPayment()).isEqualTo(Money.gbp("308.31"));
        assertThat(schedule.instalments()).hasSize(36);
    }

    @Test
    void interestUsesTheActualDaysInEachPeriod() {
        Schedule schedule = ScheduleCalculator.schedule(TEN_THOUSAND);

        ScheduledInstalment first = schedule.instalments().getFirst();
        assertThat(first.dueDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(first.days()).isEqualTo(28);
        assertThat(first.interest()).isEqualTo(Money.gbp("52.93"));
        assertThat(first.principal()).isEqualTo(Money.gbp("255.38"));

        ScheduledInstalment second = schedule.instalments().get(1);
        assertThat(second.days()).isEqualTo(31);
        assertThat(second.interest()).isEqualTo(Money.gbp("57.11"));
    }

    @Test
    void finalInstalmentAbsorbsTheRoundingDifference() {
        Schedule schedule = ScheduleCalculator.schedule(TEN_THOUSAND);

        ScheduledInstalment last = schedule.instalments().getLast();
        assertThat(last.payment()).isEqualTo(Money.gbp("305.99"));
        assertThat(last.balanceAfter()).isEqualTo(Money.gbp("0.00"));
        assertThat(schedule.totalInterest()).isEqualTo(Money.gbp("1096.84"));
        assertThat(schedule.totalRepayable()).isEqualTo(Money.gbp("11096.84"));
    }

    @Test
    void zeroPercentLoanSplitsThePrincipalEvenly() {
        Schedule schedule = ScheduleCalculator.schedule(new LoanTerms(
                Money.gbp("1200.00"), BigDecimal.ZERO, 12, LocalDate.of(2026, 11, 6)));

        assertThat(schedule.regularPayment()).isEqualTo(Money.gbp("100.00"));
        assertThat(schedule.totalInterest()).isEqualTo(Money.gbp("0.00"));
    }

    @ParameterizedTest
    @CsvSource({
            "10000.00, 6.9,  36, 2026-01-31",
            "5000.00,  7.9,  12, 2026-11-06",
            "500.00,   29.9, 6,  2026-02-28",
            "50000.00, 3.5,  84, 2027-12-31",
            "1234.56,  12.34, 25, 2028-02-29",
            "999.99,   0,    7,  2026-05-15"
    })
    void everyScheduleReconcilesToThePenny(String amount, String rate, int months, LocalDate drawdown) {
        LoanTerms terms = new LoanTerms(Money.gbp(amount), new BigDecimal(rate), months, drawdown);
        Schedule schedule = ScheduleCalculator.schedule(terms);

        Money principalRepaid = schedule.instalments().stream()
                .map(ScheduledInstalment::principal)
                .reduce(Money.gbp("0"), Money::plus);
        assertThat(principalRepaid).isEqualTo(terms.principal());
        assertThat(schedule.totalRepayable()).isEqualTo(terms.principal().plus(schedule.totalInterest()));
        assertThat(schedule.instalments()).allSatisfy(line -> {
            assertThat(line.payment()).isEqualTo(line.interest().plus(line.principal()));
            assertThat(line.principal().isNegative()).isFalse();
        });
        assertThat(schedule.instalments().getLast().balanceAfter().isZero()).isTrue();
    }
}
```

The first four tests pin down one well-understood loan. The numbers in them (308.31, 52.93, 305.99, 1,096.84) were produced by the code, then checked for plausibility: 1.890411 × 28 by hand, and the level payment against the formula on a calculator. In a real team, you'd also check a few schedules against an independent source, such as the finance team's spreadsheet, and keep them as **golden** test cases.

The parameterised test is the important one. It doesn't check *specific* numbers. It checks **properties that must hold for every loan**:

- The principal parts add up to **exactly** the principal.
- Total repayable = principal + total interest.
- Every line's payment = its interest + its principal.
- No line has negative principal, and the balance ends at exactly zero.

The six rows deliberately include awkward cases: an end-of-month drawdown, a drawdown on 28 February, one on 29 February 2028 (a leap day), an odd principal (£1,234.56), an odd term (25 months), a high rate, the longest term, and 0%. `@CsvSource` converts `2026-01-31` to a `LocalDate` for you.

Run the tests: click the green arrow next to `class ScheduleCalculatorTest`, or:

```bash
./mvnw test -Dtest=ScheduleCalculatorTest
```

Ten green ticks: four tests, plus six runs of the parameterised one.

> **Sidebar: property-based testing.** The parameterised test is a hand-made version of **property-based testing**: state a rule, then try it on many inputs. Libraries such as [jqwik](https://jqwik.net/) generate hundreds of random loans for you and, when one fails, shrink it to the simplest failing case. Financial calculations are an excellent fit for that style.

### 9. The quote API

Create `quote/QuoteController.java`:

```java
package com.example.loans.quote;

import com.example.loans.money.Money;
import com.example.loans.schedule.LoanTerms;
import com.example.loans.schedule.Schedule;
import com.example.loans.schedule.ScheduleCalculator;
import com.example.loans.schedule.ScheduledInstalment;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/loan-quotes")
public class QuoteController {

    @PostMapping
    public QuoteResponse quote(@Valid @RequestBody QuoteRequest request) {
        LoanTerms terms = new LoanTerms(
                Money.gbp(request.amount().toPlainString()),
                request.annualRate(), request.termMonths(), request.drawdownDate());
        return QuoteResponse.from(ScheduleCalculator.schedule(terms));
    }

    public record QuoteRequest(
            @NotNull @DecimalMin("500.00") @DecimalMax("50000.00") @Digits(integer = 5, fraction = 2) BigDecimal amount,
            @NotNull @DecimalMin("0.00") @DecimalMax("99.99") @Digits(integer = 2, fraction = 2) BigDecimal annualRate,
            @NotNull @Min(6) @Max(84) Integer termMonths,
            @NotNull LocalDate drawdownDate) {
    }

    public record QuoteResponse(
            Money amount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal annualRate,
            int termMonths,
            LocalDate drawdownDate,
            Money monthlyPayment,
            Money totalInterest,
            Money totalRepayable,
            List<ScheduledInstalment> instalments) {

        static QuoteResponse from(Schedule schedule) {
            LoanTerms terms = schedule.terms();
            return new QuoteResponse(terms.principal(), terms.annualRatePercent(), terms.termMonths(),
                    terms.drawdownDate(), schedule.regularPayment(), schedule.totalInterest(),
                    schedule.totalRepayable(), schedule.instalments());
        }
    }
}
```

- **A quote is a calculation, not a resource**, so it's a `POST` that returns `200 OK` with no `Location` header. Nothing is stored.
- **The validation is the product definition.** £500 to £50,000, 6 to 84 months, a rate with at most two decimal places. Those limits are made up for this tutorial, but every real loan product has limits like them, and the API is where they're enforced first.
- **`@Digits(integer = 5, fraction = 2)`** on the amount guarantees `Money.gbp(...)` will never be handed fractions of a penny.
- **`termMonths` is an `Integer`, not an `int`,** so a missing value is `null` and fails `@NotNull` with a clear message instead of silently becoming 0.
- **The `annualRate` is a string in the JSON**, just like money, for the same reason.

Now the error handler, `web/ApiExceptionHandler.java`, which turns validation failures into a `ProblemDetail` with a field-by-field `errors` map:

```java
package com.example.loans.web;

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

Extending `ResponseEntityExceptionHandler` switches all of Spring MVC's own errors to `ProblemDetail` ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)) responses. The override adds an `errors` extension member. The Core ledger tutorial walks through this class in detail.

Run the app: open `LoansApplication.java` and click the green arrow next to `main`, or:

```bash
./mvnw spring-boot:run
```

There are no migrations yet, so Flyway logs a warning you can ignore for now:

```
No migrations found. Are your locations set up correctly?
```

Create `requests.http` in the project root. You'll add to it in every guide:

```http
### Quote a loan: £5,000 at 7.9% over 12 months
POST http://localhost:8080/loan-quotes
Content-Type: application/json

{"amount": "5000.00", "annualRate": "7.9", "termMonths": 12, "drawdownDate": "2026-11-06"}

### A term outside the product range (should be 400)
POST http://localhost:8080/loan-quotes
Content-Type: application/json

{"amount": "5000.00", "annualRate": "7.9", "termMonths": 120, "drawdownDate": "2026-11-06"}
```

**(Ultimate)** IntelliJ's HTTP Client shows a green ▶ beside each `###` request.

**Free edition / terminal alternatives:** open `http://localhost:8080/swagger-ui.html`, expand **POST /loan-quotes**, click **Try it out** and paste the body. Or use curl:

```bash
curl -s -X POST localhost:8080/loan-quotes -H 'Content-Type: application/json' \
  -d '{"amount":"5000.00","annualRate":"7.9","termMonths":12,"drawdownDate":"2026-11-06"}'
```

The response (trimmed to the first instalment):

```json
{
  "amount": { "amount": "5000.00", "currency": "GBP" },
  "annualRate": "7.9",
  "termMonths": 12,
  "drawdownDate": "2026-11-06",
  "monthlyPayment": { "amount": "434.71", "currency": "GBP" },
  "totalInterest": { "amount": "215.81", "currency": "GBP" },
  "totalRepayable": { "amount": "5215.81", "currency": "GBP" },
  "instalments": [
    {
      "number": 1,
      "dueDate": "2026-12-06",
      "days": 30,
      "payment": { "amount": "434.71", "currency": "GBP" },
      "interest": { "amount": "32.47", "currency": "GBP" },
      "principal": { "amount": "402.24", "currency": "GBP" },
      "balanceAfter": { "amount": "4597.76", "currency": "GBP" }
    }
  ]
}
```

Scroll to the end of the real response and you'll see instalment 12 is £434.00, not £434.71: the final-instalment adjustment at work.

The out-of-range request returns:

```json
{
  "detail": "Invalid request content.",
  "instance": "/loan-quotes",
  "status": 400,
  "title": "Bad Request",
  "errors": { "termMonths": "must be less than or equal to 84" }
}
```

### 10. API tests with `MockMvcTester`

Every integration test in this tutorial needs the same setup: the whole application, the real Flyway migrations, and an **in-memory** database, so tests never touch `./data`. Bundle that into a meta-annotation.

Create `src/test/java/com/example/loans/LoansIntegrationTest.java`:

```java
package com.example.loans;

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
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:loans-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
public @interface LoansIntegrationTest {
}
```

Every test class that uses it shares one cached Spring context, so the suite stays fast. `DB_CLOSE_DELAY=-1` keeps the in-memory database alive for the whole test run.

Now `src/test/java/com/example/loans/quote/QuoteApiTest.java`:

```java
package com.example.loans.quote;

import com.example.loans.LoansIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

@LoansIntegrationTest
class QuoteApiTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void returnsAFullScheduleWithMoneyAsStrings() {
        MvcTestResult response = quote("""
                {"amount": "10000.00", "annualRate": "6.9", "termMonths": 36, "drawdownDate": "2026-01-31"}
                """);

        assertThat(response).hasStatus(HttpStatus.OK);
        assertThat(response).bodyJson().extractingPath("$.monthlyPayment.amount").isEqualTo("308.31");
        assertThat(response).bodyJson().extractingPath("$.totalInterest.amount").isEqualTo("1096.84");
        assertThat(response).bodyJson().extractingPath("$.instalments.length()").isEqualTo(36);
        assertThat(response).bodyJson().extractingPath("$.instalments[0].dueDate").isEqualTo("2026-02-28");
        assertThat(response).bodyJson().extractingPath("$.instalments[35].payment.amount").isEqualTo("305.99");
    }

    @Test
    void rejectsATermOutsideTheProductRange() {
        MvcTestResult response = quote("""
                {"amount": "10000.00", "annualRate": "6.9", "termMonths": 120, "drawdownDate": "2026-01-31"}
                """);

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors.termMonths")
                .isEqualTo("must be less than or equal to 84");
    }

    private MvcTestResult quote(String json) {
        return mvc.post().uri("/loan-quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }
}
```

The API test overlaps with the unit tests on purpose, but checks different things: that the JSON has the right **shape** (strings for money, ISO dates, 36 lines) and that validation is wired up. The arithmetic is proved by the unit tests, which run in milliseconds.

Run everything: right-click `src/test/java` → **Run 'All Tests'**, or `./mvnw test`.

---

## Your turn

### Exercise 1: no fractions of a percent

A rate of `6.999` is not a valid product rate. Add a test to `QuoteApiTest` that sends `"annualRate": "6.999"` and expects a `400` with an error on `annualRate`. Run it before writing anything else. Does it already pass? Why?

<details>
<summary>Solution</summary>

```java
@Test
void rejectsARateWithMoreThanTwoDecimalPlaces() {
    MvcTestResult response = quote("""
            {"amount": "10000.00", "annualRate": "6.999", "termMonths": 36, "drawdownDate": "2026-01-31"}
            """);

    assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(response).bodyJson().extractingPath("$.errors.annualRate")
            .isEqualTo("numeric value out of bounds (<2 digits>.<2 digits> expected)");
}
```

It passes straight away, because `@Digits(integer = 2, fraction = 2)` was already on `annualRate`. That's still worth a test: it pins the rule down, so nobody can loosen the annotation without a test failing.

</details>

### Exercise 2: what monthly compounding really costs

Add `public static BigDecimal annualEquivalentRate(BigDecimal annualRatePercent)` to `InterestMath`. It should return **(1 + r)¹² − 1** as a percentage, rounded half up to 2 decimal places, where `r` is the monthly rate. Test that 6.9% gives 7.12% and 0% gives 0.00%.

This is not the regulated APR (which follows the CONC formula, includes fees and uses the actual payment dates), but it shows why the APR of a loan with no fees is still higher than its nominal rate.

<details>
<summary>Hint</summary>

`monthlyRate` already gives you `r`. `BigDecimal.pow(12, MC)` raises to the 12th power. Multiply by 100 before you `setScale`.

</details>

<details>
<summary>Solution</summary>

In `InterestMath`:

```java
/** (1 + r/12)^12 − 1, as a percentage to 2 decimal places: what monthly compounding really costs. */
public static BigDecimal annualEquivalentRate(BigDecimal annualRatePercent) {
    BigDecimal compounded = BigDecimal.ONE.add(monthlyRate(annualRatePercent)).pow(12, MC);
    return compounded.subtract(BigDecimal.ONE).multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
}
```

In `InterestMathTest`:

```java
@Test
void monthlyCompoundingMakesTheEffectiveRateHigherThanTheNominalRate() {
    assertThat(InterestMath.annualEquivalentRate(new BigDecimal("6.9"))).isEqualByComparingTo("7.12");
    assertThat(InterestMath.annualEquivalentRate(BigDecimal.ZERO)).isEqualByComparingTo("0.00");
}
```

The unrounded result for 6.9% is 7.1224495…%. If 0.575% were added to a debt every month and left unpaid, after twelve months each pound owed would have grown by about 7.12p, not 6.9p, because from the second month onwards you pay interest on interest. Annualised rates such as the APR measure cost on that compounded basis.

</details>

---

## Checkpoint

All tests pass:

```bash
./mvnw test
```

```
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
```

(4 in `MoneyTest`, 4 in `InterestMathTest`, 3 in `DueDatesTest`, 10 in `ScheduleCalculatorTest` and 3 in `QuoteApiTest`, if you did both exercises.)

Your project should look like this:

```
loans/
├── pom.xml
├── requests.http
└── src/
    ├── main/
    │   ├── java/com/example/loans/
    │   │   ├── LoansApplication.java
    │   │   ├── money/
    │   │   │   └── Money.java
    │   │   ├── quote/
    │   │   │   └── QuoteController.java
    │   │   ├── schedule/
    │   │   │   ├── Annuity.java
    │   │   │   ├── DueDates.java
    │   │   │   ├── InterestMath.java
    │   │   │   ├── LoanTerms.java
    │   │   │   ├── Schedule.java
    │   │   │   ├── ScheduleCalculator.java
    │   │   │   └── ScheduledInstalment.java
    │   │   └── web/
    │   │       └── ApiExceptionHandler.java
    │   └── resources/
    │       └── application.properties
    └── test/java/com/example/loans/
        ├── LoansIntegrationTest.java
        ├── money/
        │   └── MoneyTest.java
        ├── quote/
        │   └── QuoteApiTest.java
        └── schedule/
            ├── DueDatesTest.java
            ├── InterestMathTest.java
            └── ScheduleCalculatorTest.java
```

---

## Common errors

**`ArithmeticException: Non-terminating decimal expansion; no exact representable decimal result.`**
A `divide` without a `MathContext` or a scale and `RoundingMode`. The answer has infinitely many digits (dividing by 3, 12 or 365 usually does). Pass `MC` for intermediate results, or a scale and rounding mode where you want a fixed number of decimal places.

**`IllegalArgumentException: 0.001 has too many decimal places for GBP`**
Something tried to put an unrounded result into `Money`. That's `Money` protecting you. Round first, deliberately (`setScale(2, RoundingMode.HALF_UP)` or `InterestMath.toPence`), and only then build the `Money`.

**The final instalment is way off, or the balance doesn't end at zero**
You probably rounded `principalPart` separately from the payment and interest, or updated `balance` with an unrounded value. Every value in the loop after `interest` should be `Money`, so the arithmetic stays in whole pennies.

**Due dates drift to the 28th after February**
You're adding one month to the previous due date. Always add `k` months to the drawdown date. `DueDatesTest.chainingPlusMonthsDriftsToThe28th` shows the bug.

**`400` with `"detail": "Failed to read request"`**
Jackson couldn't parse the body, often a date in the wrong format. `drawdownDate` must be ISO-8601 (`"2026-11-06"`), not `"06/11/2026"`. The response doesn't say which field, because the JSON couldn't be read at all.

**`No migrations found. Are your locations set up correctly?`**
A warning, not an error. There's nothing in `db/migration` yet. It goes away in guide 02.

**`Cannot resolve symbol 'AutoConfigureMockMvc'`**
In Spring Boot 4 it lives in `org.springframework.boot.webmvc.test.autoconfigure`. Older answers online use `org.springframework.boot.test.autoconfigure.web.servlet`.

---

## Recap

- A personal loan has a **principal**, a **nominal rate** and a **term**. The advertised **APR** is a different, regulated figure that includes fees and compounding.
- A **repayment (annuity) loan** has a level payment from the formula *P·r / (1 − (1 + r)⁻ⁿ)*. Each payment is part interest, part principal, and the schedule shows the split.
- A **day count convention** turns an annual rate into daily interest. This tutorial uses **Actual/365 Fixed**, so longer months cost more interest.
- **`BigDecimal` division needs a `MathContext` or a scale and `RoundingMode`.** Keep high precision during calculations, and round to pennies once, deliberately.
- **Round each line, and let the final instalment absorb the difference**, so the principal parts always sum to exactly the amount borrowed.
- **Always compute monthly dates from the anchor date.** `plusMonths` clamps to the end of short months, and chaining it drifts.
- **Test properties, not just examples.** "Principal repaid = principal borrowed" must hold for every loan.

### Interview angle

<details>
<summary>"How would you calculate a loan's monthly repayment in Java?"</summary>

Use the annuity formula with `BigDecimal`: monthly rate r = annual rate ÷ 12, payment = P·r / (1 − (1 + r)⁻ⁿ). Do every intermediate step with a `MathContext` such as `DECIMAL128`, and round the payment once to the penny with an explicit `RoundingMode`. Handle the zero-rate case separately (the formula divides by zero). Then build the schedule line by line, rounding each period's interest, and make the final instalment "remaining principal + final interest" so the totals reconcile exactly. Mention that the day count convention and rounding rules come from the product terms, not from the developer.

</details>

<details>
<summary>"What's the difference between the interest rate and the APR?"</summary>

The nominal interest rate is the contractual rate used to calculate interest. The APR is a standardised, annualised measure of the total cost of credit: it includes most compulsory fees and reflects the timing of payments, so customers can compare products. In the UK its calculation is prescribed by the FCA's CONC rules, and adverts must show a representative APR that at least 51% of resulting agreements are expected to get. A loan with no fees still has an APR above its nominal rate if interest is charged more often than annually.

</details>

<details>
<summary>"Where would you round in a financial calculation, and how?"</summary>

As late as possible and as few times as possible, at points defined by the business rules: typically once per posted amount (a payment, a period's interest). Keep intermediate values at high precision. Use an explicit `RoundingMode` (`HALF_UP` or `HALF_EVEN`, whichever the product specifies), never the default, and never through `double`. Then make the totals reconcile by construction, for example by putting the remainder into the last instalment, and prove it with tests that check sums, not just individual lines.

</details>

<details>
<summary>"What can go wrong with month arithmetic on dates?"</summary>

Months have different lengths, so adding a month to 31 January has to clamp to 28 or 29 February. Java's `plusMonths` does that, but if you add a month to the *previous* result, the clamped day sticks and every later date drifts to the 28th. Always compute from the anchor date. Also watch leap years, time zones (use `LocalDate` for business dates, not `Instant`), and weekends and bank holidays if the product moves due dates to working days.

</details>

### Further reading

- [FCA Handbook, CONC App 1.2: total charge for credit rules](https://handbook.fca.org.uk/handbook/CONC/App/1/2.html): where the APR formula lives
- [FCA Handbook glossary: representative APR](https://handbook.fca.org.uk/handbook/glossary/G3354.html)
- [`java.math.BigDecimal` Javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/math/BigDecimal.html): the class description explains scale, precision and rounding
- [`java.time.LocalDate.plusMonths` Javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/LocalDate.html#plusMonths(long)): the clamping rule, in the JDK's own words
- [JUnit user guide: parameterised tests](https://docs.junit.org/current/user-guide/#writing-tests-parameterized-tests)

**Next:** [02: Drawdown, the ledger and value dates](02-drawdown-ledger-and-value-dates.md). The quote becomes a real loan: money is paid out, recorded in a small double-entry ledger, and dated twice.
