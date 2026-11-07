# 02: Rules and Alerts

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- What a monitoring **rule** looks for, and the classic patterns: unusually large payments, **velocity**, **new payees** and **structuring**
- What an **alert** is, why most alerts are **false positives**, and why every alert must explain itself
- How to build a small **rule engine** in plain Java: a **sealed interface**, rules as **pure functions**, and an engine that runs them all
- How to keep thresholds out of the code with **`@ConfigurationProperties`** bound to an immutable record, validated at start-up
- The difference between **event time** and **processing time**, and which one a rule should use
- How to publish your own events, an **alert** topic, with `KafkaTemplate`, and how to read a topic in a test

By the end, every payment is checked against four rules, suspicious ones raise alerts with a plain-English reason, and every alert is published to Kafka for the rest of the bank.

---

## Concepts

### How it works in a real bank

A bank's monitoring platform runs hundreds of rules (often called **scenarios** in AML systems) over every transaction. Each one encodes a pattern that experience, regulators or industry bodies say is worth a second look. When a rule matches, the system raises an **alert**: a record saying "this transaction, on this account, matched this rule, for this reason". A person, an **analyst**, then looks at it. (Guide 05 builds their tools.)

Some classic patterns, all of which you'll build today:

| Pattern | What it looks like | Why it's suspicious |
|---|---|---|
| **Unusually large** | A payment many times bigger than the customer's normal spending | Account takeover: a criminal with Alice's login empties her account in one go |
| **Velocity** | Many payments in a short time | Card testing (trying a stolen card with lots of small payments), or a criminal draining an account before it's blocked |
| **New payee, large amount** | A big transfer to someone the customer has never paid | The signature of many APP scams: "move your money to this safe account" |
| **Structuring** | Several round amounts, each just under a threshold, adding up to more than it | Someone splitting one large movement into pieces to avoid attention. Also called *smurfing* |

> **Simplification:** these rules are deliberately simple illustrations of real patterns, with made-up thresholds. Real rules are tuned per customer segment (a business account and a student account have very different "normal"), use far more data (device, location, merchant category, time of day, the payee's own history), and are reviewed and approved under the bank's governance before they go live.

### Alerts, and the false-positive problem

Most alerts turn out to be innocent: Alice really did buy a car, and that really is her new builder. These are **false positives**, and in transaction monitoring they're the majority. Each one costs an analyst's time, and for fraud alerts that hold a payment, it can cost the customer too.

That has two consequences for the software:

- **Thresholds must be easy to change and easy to see.** Tuning a rule (moving "5 times the average" to "8 times") to cut false positives without missing real cases is routine work. It shouldn't need a code change, and the current values should be in one obvious place. That's `@ConfigurationProperties`.
- **Every alert must say why.** "Rule 47 fired" helps nobody. "GBP 2500.00 is more than 5 times the average of GBP 42.50" lets an analyst decide in seconds, and lets the bank show an auditor or regulator exactly what its controls do. Rules are a good fit for regulated monitoring precisely because they explain themselves.

### Rules as pure functions

A rule's job is a judgement: *given this transaction, and this account's history, does it look wrong?* If you write it as a **pure function** (inputs in, answer out, no database, no clock, no Kafka), three good things happen:

1. **It's trivially testable.** A rule test is three lines: build a history, build a transaction, check the answer. Hundreds of them run in milliseconds.
2. **It's deterministic.** The same inputs always give the same answer, so you can replay history through a new version of a rule and compare.
3. **The hard parts live in one place.** Loading history, locking, saving alerts and publishing events all stay in the service. The rules don't know any of that exists.

The engine is equally simple: run every rule and collect the hits.

```
TransactionEvent + AccountHistory ──▶ [LargeAmount] ──▶ hit?
                                  ──▶ [Velocity]    ──▶ hit?     ──▶ List<RuleHit> ──▶ alerts
                                  ──▶ [NewPayee]    ──▶ hit?
                                  ──▶ [Structuring] ──▶ hit?
```

### Event time vs processing time

There are two "nows" in an event-driven system:

- **Event time:** when the payment actually happened (`occurredAt`, set by the system that made it).
- **Processing time:** when the fraud system got round to looking at it (the injected `Clock`).

Normally they're milliseconds apart. But if the fraud system was down for an hour and is now catching up on a backlog, they're an hour apart. A velocity rule asking "how many payments in the last hour?" must mean **the hour before the payment happened**, not the hour before right now, or a backlog would make every account look like it was on a spending spree (or none of them, depending on how you count).

So the rules work in **event time**, from `occurredAt`. The `Clock` is for **processing time**: when an alert was raised, when a case was opened. Keeping the two separate is one of the most useful habits in event-driven code.

---

## Steps

### 1. What the rules see

Create the `rules` package. The rules don't see entities. They see small, immutable records made for them.

`rules/RuleHit.java`:

```java
package com.example.fraud.rules;

/** One rule's verdict that a transaction looks suspicious, and why. */
public record RuleHit(String ruleCode, String reason) {
}
```

`rules/PastTransaction.java`:

```java
package com.example.fraud.rules;

import com.example.fraud.money.Money;

import java.time.Instant;

/** One earlier transaction on the account, as the rules see it. */
public record PastTransaction(Money amount, String payee, Instant occurredAt) {
}
```

`rules/AccountHistory.java`:

```java
package com.example.fraud.rules;

import com.example.fraud.money.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The account's earlier transactions, oldest first. Rules only ever read it. */
public record AccountHistory(List<PastTransaction> transactions) {

    public AccountHistory {
        transactions = List.copyOf(transactions);
    }

    public static AccountHistory empty() {
        return new AccountHistory(List.of());
    }

    /** Transactions that happened after {@code from} and no later than {@code to}. */
    public List<PastTransaction> between(Instant from, Instant to) {
        return transactions.stream()
                .filter(t -> t.occurredAt().isAfter(from) && !t.occurredAt().isAfter(to))
                .toList();
    }

    public boolean hasPaid(String payee) {
        return transactions.stream().anyMatch(t -> t.payee().equalsIgnoreCase(payee));
    }

    /** The mean amount, rounded to the penny, or empty if there's no history. */
    public Optional<Money> averageAmount() {
        if (transactions.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal total = transactions.stream()
                .map(t -> t.amount().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal mean = total.divide(BigDecimal.valueOf(transactions.size()), 2, RoundingMode.HALF_EVEN);
        return Optional.of(new Money(mean, transactions.getFirst().amount().currency()));
    }
}
```

- **`List.copyOf`** in the compact constructor makes the history genuinely immutable: nobody can add to the list after it's built, including the caller who passed it in.
- **`between(from, to)`** is *after `from`, up to and including `to`*. Getting window edges right is where time-based rules usually go wrong, so it's defined once, here, and tested through the rules.
- **Division needs a rounding mode.** An average of £10.00, £10.00 and £10.01 is £10.00333…, which no `Money` can hold. `divide(…, 2, HALF_EVEN)` rounds to the penny, using banker's rounding, deliberately and visibly. This is the one place in the tutorial where money is rounded, and it's only used for comparison, never stored or paid.
- **`getFirst()`** is Java 21's sequenced-collection method (`get(0)` in older code).

### 2. A sealed `Rule` interface

`rules/Rule.java`:

```java
package com.example.fraud.rules;

import com.example.fraud.transaction.TransactionEvent;

import java.util.Optional;

/**
 * A transaction monitoring rule. Rules are pure functions: given the new
 * transaction and the account's history, they return a hit or nothing.
 * They never read the database, the clock or anything else.
 */
public sealed interface Rule permits LargeAmountRule, VelocityRule, NewPayeeRule, StructuringRule {

    /** A short, stable code that appears on every alert this rule raises. */
    String code();

    Optional<RuleHit> evaluate(TransactionEvent transaction, AccountHistory history);
}
```

A **sealed** interface (Java 17+) lists every class allowed to implement it. Nobody can add a rule anywhere else in the codebase.

Is that a good idea for a rule engine? Usually you'd want rules to be open for extension. In a bank there's a strong argument the other way: **every rule that can raise an alert is a control the bank must be able to explain**, so having the complete list in one place, reviewed whenever it changes, is a feature. The compiler enforces it. You'll see the error you get for breaking it in Exercise 1.

> **Sidebar: when not to seal.** If rules came from configuration or a rules database (many commercial monitoring platforms let analysts write scenarios without a deployment), or from plugins, a sealed interface would get in the way. Use a plain interface and let Spring collect every `Rule` bean into a `List<Rule>`. The governance moves from the compiler to a review process.

`Optional<RuleHit>` is the result type: either a hit with a reason, or nothing.

### 3. Thresholds in configuration

`rules/RulesProperties.java`:

```java
package com.example.fraud.rules;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

/** Every rule's thresholds, bound from the fraud.rules.* properties. */
@ConfigurationProperties("fraud.rules")
@Validated
public record RulesProperties(
        @NotNull Duration history,
        @Valid @NotNull LargeAmount largeAmount,
        @Valid @NotNull Velocity velocity,
        @Valid @NotNull NewPayee newPayee,
        @Valid @NotNull Structuring structuring) {

    /** Flag a payment far above the account's usual amount. */
    public record LargeAmount(@Positive int multiplier, @NotNull @Positive BigDecimal minimum) {
    }

    /** Flag more than maxTransactions payments within the window. */
    public record Velocity(@Positive int maxTransactions, @NotNull Duration window) {
    }

    /** Flag a large push payment to someone the customer has never paid before. */
    public record NewPayee(@NotNull @Positive BigDecimal threshold) {
    }

    /** Flag several round-amount payments, each under the threshold, that add up to more than it. */
    public record Structuring(@NotNull @Positive BigDecimal threshold, @NotNull @Positive BigDecimal roundTo,
                              @Positive int minCount, @NotNull Duration window) {
    }
}
```

- **A record** means the configuration is immutable after start-up. Spring Boot binds records through their constructor (constructor binding), so there are no setters.
- **One nested record per rule.** Each rule gets only its own section, which keeps rule constructors (and tests) small.
- **`@Validated` plus Jakarta constraints** check the values **at start-up**. `@Valid` on each nested record makes validation cascade into it. A typo that sets the velocity limit to `0` stops the application from starting, instead of silently alerting on every payment. You'll see the real failure in step 9.
- **`history`** is how far back the service loads an account's transactions for the rules: everything they can look at.

Add the values to `application.properties`:

```properties
# Transaction monitoring rules
fraud.rules.history=90d
fraud.rules.large-amount.multiplier=5
fraud.rules.large-amount.minimum=500.00
fraud.rules.velocity.max-transactions=5
fraud.rules.velocity.window=1h
fraud.rules.new-payee.threshold=1000.00
fraud.rules.structuring.threshold=1000.00
fraud.rules.structuring.round-to=100.00
fraud.rules.structuring.min-count=3
fraud.rules.structuring.window=24h
```

Spring Boot converts `90d`, `1h` and `24h` into `java.time.Duration`, and kebab-case property names (`max-transactions`) into camel-case record components (`maxTransactions`). Because these are ordinary properties, each environment can override them, for example with an environment variable `FRAUD_RULES_VELOCITY_MAXTRANSACTIONS=8`.

### 4. The large-amount rule

`rules/LargeAmountRule.java`:

```java
package com.example.fraud.rules;

import com.example.fraud.money.Money;
import com.example.fraud.transaction.TransactionEvent;

import java.math.BigDecimal;
import java.util.Optional;

/** A payment many times bigger than this account's average. */
public final class LargeAmountRule implements Rule {

    private final int multiplier;
    private final Money minimum;

    public LargeAmountRule(RulesProperties.LargeAmount config) {
        this.multiplier = config.multiplier();
        this.minimum = Money.gbp(config.minimum().toPlainString());
    }

    @Override
    public String code() {
        return "LARGE_AMOUNT";
    }

    @Override
    public Optional<RuleHit> evaluate(TransactionEvent txn, AccountHistory history) {
        Money amount = txn.amount();
        if (amount.isLessThan(minimum)) {
            return Optional.empty(); // small payments are never "large", whatever the history
        }
        Optional<Money> average = history.averageAmount();
        if (average.isEmpty()) {
            return Optional.of(new RuleHit(code(), "%s with no payment history".formatted(amount)));
        }
        Money limit = new Money(average.get().amount().multiply(BigDecimal.valueOf(multiplier)), amount.currency());
        if (limit.isLessThan(amount)) {
            return Optional.of(new RuleHit(code(),
                    "%s is more than %d times the average of %s".formatted(amount, multiplier, average.get())));
        }
        return Optional.empty();
    }
}
```

- **The minimum stops silly alerts.** Someone who usually spends £3 on coffee buying a £16 lunch is "5 times the average", but nobody needs to look at it.
- **No history at all** (a brand-new account, or nothing in 90 days) plus a payment over the minimum is flagged. New accounts are higher risk: criminals open accounts to receive and move stolen money (**money mules**) and use them quickly.
- **`final class`.** A class that implements a sealed interface must say whether *it* can be extended: `final`, `sealed` or `non-sealed`.
- **The rule converts its `BigDecimal` configuration into `Money` once**, in the constructor, so every comparison is `Money` against `Money` and can't mix currencies.

### 5. Velocity, new payee and structuring

`rules/VelocityRule.java`:

```java
package com.example.fraud.rules;

import com.example.fraud.transaction.TransactionEvent;

import java.util.Optional;

/** Too many payments in too short a time. */
public final class VelocityRule implements Rule {

    private final RulesProperties.Velocity config;

    public VelocityRule(RulesProperties.Velocity config) {
        this.config = config;
    }

    @Override
    public String code() {
        return "VELOCITY";
    }

    @Override
    public Optional<RuleHit> evaluate(TransactionEvent txn, AccountHistory history) {
        int count = history.between(txn.occurredAt().minus(config.window()), txn.occurredAt()).size() + 1;
        if (count > config.maxTransactions()) {
            return Optional.of(new RuleHit(code(),
                    "%d payments within %s (limit %d)".formatted(count, config.window(), config.maxTransactions())));
        }
        return Optional.empty();
    }
}
```

The window is measured back from the payment's own `occurredAt` (event time), and `+ 1` counts the payment being checked. `Duration.toString()` prints ISO 8601, so an hour appears as `PT1H` in the reason.

`rules/NewPayeeRule.java`:

```java
package com.example.fraud.rules;

import com.example.fraud.money.Money;
import com.example.fraud.transaction.TransactionEvent;

import java.util.Optional;

/** A large transfer to someone this customer has never paid: a classic sign of a scam. */
public final class NewPayeeRule implements Rule {

    private final Money threshold;

    public NewPayeeRule(RulesProperties.NewPayee config) {
        this.threshold = Money.gbp(config.threshold().toPlainString());
    }

    @Override
    public String code() {
        return "NEW_PAYEE";
    }

    @Override
    public Optional<RuleHit> evaluate(TransactionEvent txn, AccountHistory history) {
        if (!txn.channel().isPushPayment()
                || txn.amount().isLessThan(threshold)
                || history.hasPaid(txn.payee())) {
            return Optional.empty();
        }
        return Optional.of(new RuleHit(code(),
                "%s to new payee '%s' by %s".formatted(txn.amount(), txn.payee(), txn.channel())));
    }
}
```

Only **push payments** count. A card payment at a new shop is everyday life. A £2,500 Faster Payment to a name the customer has never paid is what APP scams look like.

> **Simplification:** "new" here means "not paid in the last 90 days of history", and payees are matched by name. Real systems match on sort code and account number, and know about payees the customer set up in their banking app. UK banks also use **Confirmation of Payee** ([Pay.UK](https://www.wearepay.uk/what-we-do/overlay-services/confirmation-of-payee/)), which checks the name the customer typed against the name on the receiving account before the payment is sent. A mismatch is a strong extra signal.

`rules/StructuringRule.java`:

```java
package com.example.fraud.rules;

import com.example.fraud.money.Money;
import com.example.fraud.transaction.TransactionEvent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Several round-amount payments, each kept under a threshold, that together
 * go over it: someone may be splitting one large payment to avoid attention.
 */
public final class StructuringRule implements Rule {

    private final RulesProperties.Structuring config;
    private final Money threshold;

    public StructuringRule(RulesProperties.Structuring config) {
        this.config = config;
        this.threshold = Money.gbp(config.threshold().toPlainString());
    }

    @Override
    public String code() {
        return "STRUCTURING";
    }

    @Override
    public Optional<RuleHit> evaluate(TransactionEvent txn, AccountHistory history) {
        if (!isRoundAndUnderThreshold(txn.amount())) {
            return Optional.empty();
        }
        List<Money> suspicious = new ArrayList<>();
        for (PastTransaction past : history.between(txn.occurredAt().minus(config.window()), txn.occurredAt())) {
            if (isRoundAndUnderThreshold(past.amount())) {
                suspicious.add(past.amount());
            }
        }
        suspicious.add(txn.amount());
        Money total = suspicious.stream().reduce(Money.zero(Money.GBP), Money::plus);
        if (suspicious.size() >= config.minCount() && !total.isLessThan(threshold)) {
            return Optional.of(new RuleHit(code(),
                    "%d round-amount payments under %s totalling %s within %s"
                            .formatted(suspicious.size(), threshold, total, config.window())));
        }
        return Optional.empty();
    }

    private boolean isRoundAndUnderThreshold(Money amount) {
        boolean round = amount.amount().remainder(config.roundTo()).signum() == 0;
        return round && amount.isLessThan(threshold);
    }
}
```

- **"Round"** means a whole multiple of `roundTo` (£100): `remainder` is zero. `BigDecimal.remainder` compares numerically, so `400.00` and `400` both count.
- **`reduce(Money.zero(GBP), Money::plus)`** adds `Money` values without ever touching a `double`.
- **`!total.isLessThan(threshold)`** means "at least the threshold". `Money` only has `isLessThan`, and negating it reads well enough.
- Once the pattern is established, each further round payment that day also matches. That's realistic: monitoring systems raise repeated alerts, and grouping them is the job of case management in guide 05.

### 6. The engine

`rules/RuleEngine.java`:

```java
package com.example.fraud.rules;

import com.example.fraud.transaction.TransactionEvent;

import java.util.List;
import java.util.Optional;

/** Runs every rule against a transaction and collects the hits. */
public class RuleEngine {

    private final List<Rule> rules;

    public RuleEngine(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<RuleHit> evaluate(TransactionEvent transaction, AccountHistory history) {
        return rules.stream()
                .map(rule -> rule.evaluate(transaction, history))
                .flatMap(Optional::stream)
                .toList();
    }

    public List<String> ruleCodes() {
        return rules.stream().map(Rule::code).toList();
    }
}
```

`flatMap(Optional::stream)` (Java 9+) turns each `Optional` into a stream of zero or one elements, so empty results simply disappear.

Wire it up in `rules/RulesConfig.java`:

```java
package com.example.fraud.rules;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Builds each rule from its own section of the configuration. */
@Configuration
@EnableConfigurationProperties(RulesProperties.class)
public class RulesConfig {

    @Bean
    RuleEngine ruleEngine(RulesProperties properties) {
        return new RuleEngine(List.of(
                new LargeAmountRule(properties.largeAmount()),
                new VelocityRule(properties.velocity()),
                new NewPayeeRule(properties.newPayee()),
                new StructuringRule(properties.structuring())));
    }
}
```

The rules aren't Spring beans and have no Spring annotations. They're plain objects built here, each from its own section. `@EnableConfigurationProperties` registers `RulesProperties` as a bean, bound and validated.

The list order is the order hits are reported in, and the order alerts are saved.

### 7. Unit-test the rules

Rule tests need a lot of small transactions, so start with a **test data builder**. `src/test/java/com/example/fraud/TestTransactions.java`:

```java
package com.example.fraud;

import com.example.fraud.config.Topics;
import com.example.fraud.money.Money;
import com.example.fraud.rules.PastTransaction;
import com.example.fraud.transaction.Channel;
import com.example.fraud.transaction.TransactionEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;

import java.time.Instant;
import java.util.UUID;

/** Readable test data: payment("50.00").to("Bob").at(NOON).build() */
public final class TestTransactions {

    public static final Instant NOON = Instant.parse("2026-11-02T12:00:00Z");

    private TestTransactions() {
    }

    public static Builder payment(String amount) {
        return new Builder(amount);
    }

    /** Publishes the event to the transactions topic, keyed by account, and waits for the broker. */
    public static void publish(KafkaTemplate<String, String> kafka, TransactionEvent event) {
        kafka.send(MessageBuilder.withPayload(event)
                .setHeader(KafkaHeaders.TOPIC, Topics.TRANSACTIONS)
                .setHeader(KafkaHeaders.KEY, event.accountId())
                .build()).join();
    }

    public static final class Builder {

        private UUID eventId = UUID.randomUUID();
        private String accountId = "acc-1";
        private final String amount;
        private String payee = "Corner Shop";
        private Channel channel = Channel.CARD;
        private Instant occurredAt = NOON;

        private Builder(String amount) {
            this.amount = amount;
        }

        public Builder eventId(UUID eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder account(String accountId) {
            this.accountId = accountId;
            return this;
        }

        public Builder to(String payee) {
            this.payee = payee;
            return this;
        }

        public Builder by(Channel channel) {
            this.channel = channel;
            return this;
        }

        public Builder at(Instant occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        public TransactionEvent build() {
            return new TransactionEvent(eventId, accountId, Money.gbp(amount), payee, channel, occurredAt);
        }

        /** The same payment, as it appears in an account's history. */
        public PastTransaction past() {
            return new PastTransaction(Money.gbp(amount), payee, occurredAt);
        }
    }
}
```

Every field has a sensible default, so a test only says what it cares about: `payment("2500.00").to("Safe Account Ltd").by(FASTER_PAYMENT).build()`. `NOON` is a fixed instant, so tests never depend on when they run. `publish(…)` is for the integration tests later.

`src/test/java/com/example/fraud/rules/LargeAmountRuleTest.java`:

```java
package com.example.fraud.rules;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.example.fraud.TestTransactions.NOON;
import static com.example.fraud.TestTransactions.payment;
import static org.assertj.core.api.Assertions.assertThat;

class LargeAmountRuleTest {

    // Flag payments of at least £500 that are more than 5 times the average
    LargeAmountRule rule = new LargeAmountRule(new RulesProperties.LargeAmount(5, new BigDecimal("500.00")));

    AccountHistory usualSpending = new AccountHistory(List.of(
            payment("40.00").at(NOON.minusSeconds(86_400 * 3)).past(),
            payment("60.00").at(NOON.minusSeconds(86_400 * 2)).past(),
            payment("50.00").at(NOON.minusSeconds(86_400)).past()));

    @Test
    void flagsAPaymentFarAboveTheAverage() {
        var hit = rule.evaluate(payment("900.00").build(), usualSpending);

        assertThat(hit).hasValueSatisfying(h -> {
            assertThat(h.ruleCode()).isEqualTo("LARGE_AMOUNT");
            assertThat(h.reason()).isEqualTo("GBP 900.00 is more than 5 times the average of GBP 50.00");
        });
    }

    @Test
    void allowsAPaymentWithinTheMultiple() {
        assertThat(rule.evaluate(payment("250.00").build(), usualSpending)).isEmpty();
    }

    @Test
    void neverFlagsAPaymentBelowTheMinimum() {
        assertThat(rule.evaluate(payment("499.99").build(), usualSpending)).isEmpty();
    }

    @Test
    void flagsALargeFirstPayment() {
        assertThat(rule.evaluate(payment("750.00").build(), AccountHistory.empty()))
                .map(RuleHit::reason)
                .hasValue("GBP 750.00 with no payment history");
    }
}
```

No Spring, no database, no Kafka. The test builds the rule with exactly the configuration it wants, which also documents what the configuration means. And it asserts the **exact reason text**: the reason is part of the rule's contract with the analysts who read it.

`src/test/java/com/example/fraud/rules/VelocityRuleTest.java`:

```java
package com.example.fraud.rules;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.example.fraud.TestTransactions.NOON;
import static com.example.fraud.TestTransactions.payment;
import static org.assertj.core.api.Assertions.assertThat;

class VelocityRuleTest {

    // More than 5 payments in an hour
    VelocityRule rule = new VelocityRule(new RulesProperties.Velocity(5, Duration.ofHours(1)));

    @Test
    void allowsFivePaymentsInAnHour() {
        assertThat(rule.evaluate(payment("10.00").build(), paymentsInTheLastHour(4))).isEmpty();
    }

    @Test
    void flagsTheSixthPaymentInAnHour() {
        assertThat(rule.evaluate(payment("10.00").build(), paymentsInTheLastHour(5)))
                .map(RuleHit::reason)
                .hasValue("6 payments within PT1H (limit 5)");
    }

    @Test
    void ignoresPaymentsOutsideTheWindow() {
        List<PastTransaction> past = new ArrayList<>(paymentsInTheLastHour(4).transactions());
        past.addFirst(payment("10.00").at(NOON.minus(Duration.ofMinutes(61))).past());

        assertThat(rule.evaluate(payment("10.00").build(), new AccountHistory(past))).isEmpty();
    }

    private AccountHistory paymentsInTheLastHour(int count) {
        List<PastTransaction> past = new ArrayList<>();
        for (int i = count; i > 0; i--) {
            past.add(payment("10.00").at(NOON.minus(Duration.ofMinutes(i * 10L))).past());
        }
        return new AccountHistory(past);
    }
}
```

The three tests pin down the boundary: 5 is fine, 6 isn't, and the 61-minute-old payment doesn't count.

`src/test/java/com/example/fraud/rules/NewPayeeRuleTest.java`:

```java
package com.example.fraud.rules;

import com.example.fraud.transaction.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.List;

import static com.example.fraud.TestTransactions.NOON;
import static com.example.fraud.TestTransactions.payment;
import static org.assertj.core.api.Assertions.assertThat;

class NewPayeeRuleTest {

    NewPayeeRule rule = new NewPayeeRule(new RulesProperties.NewPayee(new BigDecimal("1000.00")));

    AccountHistory paidMumBefore = new AccountHistory(List.of(
            payment("50.00").to("Mum").by(Channel.FASTER_PAYMENT).at(NOON.minusSeconds(86_400 * 30)).past()));

    @ParameterizedTest
    @EnumSource(value = Channel.class, names = {"FASTER_PAYMENT", "CHAPS"})
    void flagsALargePushPaymentToSomeoneNew(Channel channel) {
        var txn = payment("2500.00").to("Safe Account Ltd").by(channel).build();

        assertThat(rule.evaluate(txn, paidMumBefore))
                .map(RuleHit::reason)
                .hasValue("GBP 2500.00 to new payee 'Safe Account Ltd' by " + channel);
    }

    @Test
    void ignoresAPayeeTheCustomerHasPaidBefore() {
        var txn = payment("2500.00").to("Mum").by(Channel.FASTER_PAYMENT).build();

        assertThat(rule.evaluate(txn, paidMumBefore)).isEmpty();
    }

    @Test
    void ignoresCardPayments() {
        var txn = payment("2500.00").to("Electronics Store").by(Channel.CARD).build();

        assertThat(rule.evaluate(txn, paidMumBefore)).isEmpty();
    }
}
```

`@EnumSource(names = …)` runs the first test once for each push channel, so it counts as two tests.

`src/test/java/com/example/fraud/rules/StructuringRuleTest.java`:

```java
package com.example.fraud.rules;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static com.example.fraud.TestTransactions.NOON;
import static com.example.fraud.TestTransactions.payment;
import static org.assertj.core.api.Assertions.assertThat;

class StructuringRuleTest {

    // 3+ payments, each a multiple of £100 under £1,000, adding up to £1,000 or more in 24 hours
    StructuringRule rule = new StructuringRule(new RulesProperties.Structuring(
            new BigDecimal("1000.00"), new BigDecimal("100.00"), 3, Duration.ofHours(24)));

    @Test
    void flagsRoundPaymentsThatAddUpToTheThreshold() {
        var history = new AccountHistory(List.of(
                payment("400.00").at(NOON.minus(Duration.ofHours(5))).past(),
                payment("300.00").at(NOON.minus(Duration.ofHours(2))).past()));

        assertThat(rule.evaluate(payment("300.00").build(), history))
                .map(RuleHit::reason)
                .hasValue("3 round-amount payments under GBP 1000.00 totalling GBP 1000.00 within PT24H");
    }

    @Test
    void ignoresAmountsThatAreNotRound() {
        var history = new AccountHistory(List.of(
                payment("412.50").at(NOON.minus(Duration.ofHours(5))).past(),
                payment("300.00").at(NOON.minus(Duration.ofHours(2))).past()));

        assertThat(rule.evaluate(payment("300.00").build(), history)).isEmpty();
    }

    @Test
    void ignoresRoundPaymentsThatStayUnderTheThreshold() {
        var history = new AccountHistory(List.of(
                payment("100.00").at(NOON.minus(Duration.ofHours(5))).past(),
                payment("200.00").at(NOON.minus(Duration.ofHours(2))).past()));

        assertThat(rule.evaluate(payment("300.00").build(), history)).isEmpty();
    }
}
```

And the engine. `src/test/java/com/example/fraud/rules/RuleEngineTest.java`:

```java
package com.example.fraud.rules;

import com.example.fraud.transaction.Channel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static com.example.fraud.TestTransactions.payment;
import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    RuleEngine engine = new RuleEngine(List.of(
            new LargeAmountRule(new RulesProperties.LargeAmount(5, new BigDecimal("500.00"))),
            new VelocityRule(new RulesProperties.Velocity(5, Duration.ofHours(1))),
            new NewPayeeRule(new RulesProperties.NewPayee(new BigDecimal("1000.00")))));

    @Test
    void collectsTheHitsFromEveryRule() {
        var txn = payment("3000.00").to("Stranger").by(Channel.FASTER_PAYMENT).build();

        assertThat(engine.evaluate(txn, AccountHistory.empty()))
                .extracting(RuleHit::ruleCode)
                .containsExactly("LARGE_AMOUNT", "NEW_PAYEE");
    }

    @Test
    void returnsNothingForAnOrdinaryPayment() {
        assertThat(engine.evaluate(payment("12.99").build(), AccountHistory.empty())).isEmpty();
    }
}
```

Run the rules package: right-click `src/test/java/com/example/fraud/rules` → **Run 'Tests in rules'**, or `./mvnw test -Dtest='*RuleTest,RuleEngineTest'`. Sixteen tests, and none of them needs Docker.

### 8. Alerts: table, entity, event

`src/main/resources/db/migration/V2__create_alert.sql`:

```sql
CREATE TABLE alert (
    id                   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    account_id           VARCHAR(40)              NOT NULL,
    transaction_event_id UUID                     NOT NULL,
    rule_code            VARCHAR(30)              NOT NULL,
    reason               VARCHAR(500)             NOT NULL,
    amount               NUMERIC(19, 4)           NOT NULL,
    currency             VARCHAR(3)               NOT NULL,
    raised_at            TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX ix_alert_account ON alert (account_id);
```

One row per rule hit. A transaction that breaks two rules gets two alerts, each with its own reason. The alert copies the amount and account from the transaction, so an analyst's screen needs no joins.

`alert/Alert.java`:

```java
package com.example.fraud.alert;

import com.example.fraud.money.Money;
import com.example.fraud.rules.RuleHit;
import com.example.fraud.transaction.TransactionEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/** A rule thought a transaction looked suspicious. A person needs to look at it. */
@Entity
@Table(name = "alert")
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, length = 40)
    private String accountId;

    @Column(name = "transaction_event_id", nullable = false)
    private UUID transactionEventId;

    @Column(name = "rule_code", nullable = false, length = 30)
    private String ruleCode;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "raised_at", nullable = false)
    private Instant raisedAt;

    protected Alert() {
        // for JPA
    }

    public static Alert raise(TransactionEvent txn, RuleHit hit, Instant raisedAt) {
        Alert alert = new Alert();
        alert.accountId = txn.accountId();
        alert.transactionEventId = txn.eventId();
        alert.ruleCode = hit.ruleCode();
        alert.reason = hit.reason();
        alert.amount = txn.amount().amount();
        alert.currencyCode = txn.amount().currency().getCurrencyCode();
        alert.raisedAt = raisedAt;
        return alert;
    }

    public Long getId() {
        return id;
    }

    public String getAccountId() {
        return accountId;
    }

    public UUID getTransactionEventId() {
        return transactionEventId;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public String getReason() {
        return reason;
    }

    public Money getAmount() {
        return new Money(amount, Currency.getInstance(currencyCode));
    }

    public Instant getRaisedAt() {
        return raisedAt;
    }
}
```

`alert/AlertRepository.java`:

```java
package com.example.fraud.alert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    List<Alert> findByAccountIdOrderByIdAsc(String accountId);
}
```

Other systems want to know about alerts: a customer-contact service might text Alice "Did you just pay £2,500 to Safe Account Ltd?", and a case-management tool might pick them up. So publish each one as an event. `alert/AlertRaised.java`:

```java
package com.example.fraud.alert;

import com.example.fraud.money.Money;

import java.time.Instant;
import java.util.UUID;

/** Published to the fraud-alerts topic for other systems to react to. */
public record AlertRaised(
        Long alertId,
        String accountId,
        UUID transactionEventId,
        String ruleCode,
        String reason,
        Money amount,
        Instant raisedAt) {

    public static AlertRaised from(Alert alert) {
        return new AlertRaised(alert.getId(), alert.getAccountId(), alert.getTransactionEventId(),
                alert.getRuleCode(), alert.getReason(), alert.getAmount(), alert.getRaisedAt());
    }
}
```

It's a separate record from the entity on purpose: the event is a public contract with other teams, and the table is your private business.

Add the topic name to `config/Topics.java`:

```java
    /** Alerts raised by the rules, for case management and customer contact systems. */
    public static final String ALERTS = "fraud-alerts";
```

and the topic to `config/KafkaConfig.java`:

```java
    @Bean
    NewTopic alertsTopic() {
        return TopicBuilder.name(Topics.ALERTS).partitions(3).replicas(1).build();
    }
```

`alert/AlertPublisher.java`:

```java
package com.example.fraud.alert;

import com.example.fraud.config.Topics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.List;

/** Tells the rest of the bank about new alerts. */
@Component
public class AlertPublisher {

    private final KafkaTemplate<String, String> kafka;

    public AlertPublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    public void publish(List<Alert> alerts) {
        for (Alert alert : alerts) {
            var message = MessageBuilder.withPayload(AlertRaised.from(alert))
                    .setHeader(KafkaHeaders.TOPIC, Topics.ALERTS)
                    .setHeader(KafkaHeaders.KEY, alert.getAccountId())
                    .build();
            kafka.send(message).join();
        }
    }
}
```

Keyed by account again, so a consumer of alerts sees each account's alerts in order.

### 9. Put the rules to work

The service needs each account's recent history. Add a query to `AccountTransactionRepository`:

```java
package com.example.fraud.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, Long> {

    List<AccountTransaction> findByAccountIdOrderByOccurredAtDesc(String accountId);

    /** The account's transactions after {@code from}, up to and including {@code to}, oldest first. */
    @Query("""
            select t from AccountTransaction t
            where t.accountId = :accountId and t.occurredAt > :from and t.occurredAt <= :to
            order by t.occurredAt, t.id""")
    List<AccountTransaction> findHistory(String accountId, Instant from, Instant to);
}
```

A derived query name for this would be `findByAccountIdAndOccurredAtGreaterThanAndOccurredAtLessThanEqualOrderByOccurredAtAsc`. A short `@Query` is kinder to the reader. `:accountId` binds to the parameter of the same name, which works because Spring Boot's Maven setup compiles with `-parameters`. `order by …, t.id` breaks ties between payments at the same instant.

Now replace `TransactionMonitoringService`:

```java
package com.example.fraud.transaction;

import com.example.fraud.alert.Alert;
import com.example.fraud.alert.AlertRepository;
import com.example.fraud.rules.AccountHistory;
import com.example.fraud.rules.PastTransaction;
import com.example.fraud.rules.RuleEngine;
import com.example.fraud.rules.RuleHit;
import com.example.fraud.rules.RulesProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class TransactionMonitoringService {

    private final AccountTransactionRepository transactions;
    private final AlertRepository alerts;
    private final RuleEngine ruleEngine;
    private final RulesProperties rules;
    private final Clock clock;

    public TransactionMonitoringService(AccountTransactionRepository transactions, AlertRepository alerts,
                                        RuleEngine ruleEngine, RulesProperties rules, Clock clock) {
        this.transactions = transactions;
        this.alerts = alerts;
        this.ruleEngine = ruleEngine;
        this.rules = rules;
        this.clock = clock;
    }

    /** Checks one transaction against every rule, records it, and returns any new alerts. */
    @Transactional
    public List<Alert> process(TransactionEvent event) {
        AccountHistory history = historyBefore(event);
        List<RuleHit> hits = ruleEngine.evaluate(event, history);

        Instant now = Instant.now(clock);
        transactions.save(AccountTransaction.record(event, now));
        return alerts.saveAll(hits.stream().map(hit -> Alert.raise(event, hit, now)).toList());
    }

    @Transactional(readOnly = true)
    public List<AccountTransaction> history(String accountId) {
        return transactions.findByAccountIdOrderByOccurredAtDesc(accountId);
    }

    private AccountHistory historyBefore(TransactionEvent event) {
        Instant to = event.occurredAt();
        Instant from = to.minus(rules.history());
        List<PastTransaction> past = transactions.findHistory(event.accountId(), from, to).stream()
                .map(t -> new PastTransaction(t.getAmount(), t.getPayee(), t.getOccurredAt()))
                .toList();
        return new AccountHistory(past);
    }
}
```

The order inside `process` is the whole design:

1. **Gather:** load the history *before* saving this transaction, so the rules see only what came earlier.
2. **Decide:** the pure rule engine.
3. **Record:** save the transaction (so it's history for the next one) and the alerts, in **one database transaction**. Either both are saved or neither is.

The window ends at the event's `occurredAt` (event time). The clock only stamps `receivedAt` and `raisedAt` (processing time).

Update `TransactionListener` to publish the alerts:

```java
package com.example.fraud.transaction;

import com.example.fraud.alert.Alert;
import com.example.fraud.alert.AlertPublisher;
import com.example.fraud.config.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.List;

/** The front door: every transaction event comes in here. */
@Component
public class TransactionListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionListener.class);

    private final TransactionMonitoringService monitoring;
    private final AlertPublisher alertPublisher;

    public TransactionListener(TransactionMonitoringService monitoring, AlertPublisher alertPublisher) {
        this.monitoring = monitoring;
        this.alertPublisher = alertPublisher;
    }

    @KafkaListener(topics = Topics.TRANSACTIONS)
    public void on(TransactionEvent event,
                   @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                   @Header(KafkaHeaders.OFFSET) long offset) {
        log.info("Received {} for account {} (partition {}, offset {})",
                event.eventId(), event.accountId(), partition, offset);
        List<Alert> alerts = monitoring.process(event); // commits to the database...
        alertPublisher.publish(alerts);                  // ...and only then tells everyone else
        alerts.forEach(a -> log.info("Alert {} raised: {} {}", a.getId(), a.getRuleCode(), a.getReason()));
    }
}
```

Publishing happens **after** `process` returns, which means after its transaction has committed. If you published inside the transaction and the commit then failed, other systems would hear about an alert that doesn't exist.

> **Sidebar: the dual-write problem.** Publishing after the commit isn't perfect either. If the app crashes between the commit and the `send`, the alert is saved but never published. Writing to a database and to Kafka can't be made atomic directly; this is the **dual-write problem**. The standard fix is the [transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html): save an "outbox" row in the same database transaction as the alert, and have a separate process publish outbox rows to Kafka and mark them sent. This tutorial accepts the small gap and says so. A bank wouldn't.

Finally an endpoint to see alerts. `alert/AlertController.java`:

```java
package com.example.fraud.alert;

import com.example.fraud.money.Money;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/alerts")
public class AlertController {

    private final AlertRepository alerts;

    public AlertController(AlertRepository alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    public List<AlertResponse> forAccount(@RequestParam String accountId) {
        return alerts.findByAccountIdOrderByIdAsc(accountId).stream().map(AlertResponse::from).toList();
    }

    public record AlertResponse(Long id, String accountId, UUID transactionEventId, String ruleCode,
                                String reason, Money amount, Instant raisedAt) {

        static AlertResponse from(Alert alert) {
            return new AlertResponse(alert.getId(), alert.getAccountId(), alert.getTransactionEventId(),
                    alert.getRuleCode(), alert.getReason(), alert.getAmount(), alert.getRaisedAt());
        }
    }
}
```

### 10. Try it

Restart the app. Flyway applies `V2`. Add to `requests.http`:

```http
### Alice's usual coffee
POST http://localhost:8080/simulator/transactions
Content-Type: application/json

{"accountId": "acc-1001", "amount": "42.50", "payee": "Corner Shop", "channel": "CARD"}

### A "bank security team" persuades Alice to move £2,500 to a "safe account"
POST http://localhost:8080/simulator/transactions
Content-Type: application/json

{"accountId": "acc-1001", "amount": "2500.00", "payee": "Safe Account Ltd", "channel": "FASTER_PAYMENT"}

### Alice's alerts
GET http://localhost:8080/alerts?accountId=acc-1001
```

The log shows two alerts for the second payment:

```
Alert 1 raised: LARGE_AMOUNT GBP 2500.00 is more than 5 times the average of GBP 42.50
Alert 2 raised: NEW_PAYEE GBP 2500.00 to new payee 'Safe Account Ltd' by FASTER_PAYMENT
```

(If you sent more payments for `acc-1001` in guide 01, the average will differ, and those payments may also trip the velocity rule. Use a fresh account id to see exactly this output.)

And they're on the `fraud-alerts` topic for anyone to read:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic fraud-alerts --from-beginning \
  --formatter-property print.key=true
```

```
acc-1001	{"alertId":1,"accountId":"acc-1001","transactionEventId":"6f0e1b6f-6b5b-41fa-9f42-50dacdee618c","ruleCode":"LARGE_AMOUNT","reason":"GBP 2500.00 is more than 5 times the average of GBP 42.50","amount":{"amount":"2500.00","currency":"GBP"},"raisedAt":"2026-11-07T14:17:08.810354112Z"}
```

Now see the configuration validation for yourself. Stop the app and start it with a nonsense velocity limit:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--fraud.rules.velocity.max-transactions=0
```

(In IntelliJ: **Run → Edit Configurations…**, add `--fraud.rules.velocity.max-transactions=0` to **Program arguments**.) It refuses to start:

```
***************************
APPLICATION FAILED TO START
***************************

Description:

Binding to target com.example.fraud.rules.RulesProperties failed:

    Property: fraud.rules.velocity.maxTransactions
    Value: "0"
    Reason: must be greater than 0
```

That's exactly what you want from a control: a misconfiguration fails loudly at deployment, not quietly in production.

### 11. Integration tests

`src/test/java/com/example/fraud/alert/MonitoringFlowTest.java`:

```java
package com.example.fraud.alert;

import com.example.fraud.FraudIntegrationTest;
import com.example.fraud.transaction.Channel;
import com.example.fraud.transaction.TransactionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.time.Duration;
import java.util.UUID;

import static com.example.fraud.TestTransactions.NOON;
import static com.example.fraud.TestTransactions.payment;
import static com.example.fraud.TestTransactions.publish;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@FraudIntegrationTest
class MonitoringFlowTest {

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired AlertRepository alerts;
    @Autowired MockMvcTester mvc;

    @Test
    void theSixthPaymentInAnHourRaisesAVelocityAlert() {
        String account = "acc-" + UUID.randomUUID();
        for (int i = 0; i < 6; i++) {
            publish(kafka, payment("20.00").account(account).at(NOON.plus(Duration.ofMinutes(i))).build());
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(alerts.findByAccountIdOrderByIdAsc(account))
                        .extracting(Alert::getRuleCode)
                        .containsExactly("VELOCITY"));
    }

    @Test
    void alertsAreAvailableFromTheApi() {
        String account = "acc-" + UUID.randomUUID();
        TransactionEvent scam = payment("2500.00").account(account)
                .to("Safe Account Ltd").by(Channel.FASTER_PAYMENT).build();
        publish(kafka, scam);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(mvc.get().uri("/alerts?accountId={id}", account))
                        .bodyJson()
                        .extractingPath("$[*].ruleCode").asArray()
                        .containsExactly("LARGE_AMOUNT", "NEW_PAYEE"));
    }
}
```

The velocity test relies on **ordering**: six events with the same key, processed in the order they were published. That's partition ordering doing its job.

Now prove the alerts reach Kafka. The test needs its own consumer of `fraud-alerts`. `src/test/java/com/example/fraud/alert/AlertPublishingTest.java`:

```java
package com.example.fraud.alert;

import com.example.fraud.FraudIntegrationTest;
import com.example.fraud.config.Topics;
import com.example.fraud.transaction.Channel;
import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.example.fraud.TestTransactions.payment;
import static com.example.fraud.TestTransactions.publish;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@FraudIntegrationTest
class AlertPublishingTest {

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ConsumerFactory<String, String> consumerFactory;

    @Test
    void publishesEachAlertToTheAlertsTopic() {
        String account = "acc-" + UUID.randomUUID();

        try (Consumer<String, String> consumer = consumerFactory.createConsumer("alert-test-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(Topics.ALERTS));

            publish(kafka, payment("1500.00").account(account).to("Stranger").by(Channel.FASTER_PAYMENT).build());

            List<ConsumerRecord<String, String>> ours = new ArrayList<>();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                KafkaTestUtils.getRecords(consumer, Duration.ofMillis(500)).forEach(r -> {
                    if (account.equals(r.key())) {
                        ours.add(r);
                    }
                });
                assertThat(ours).hasSize(2);
            });

            List<String> ruleCodes = ours.stream().map(r -> JsonPath.<String>read(r.value(), "$.ruleCode")).toList();
            assertThat(ruleCodes).containsExactlyInAnyOrder("LARGE_AMOUNT", "NEW_PAYEE");
            assertThat(JsonPath.<String>read(ours.getFirst().value(), "$.amount.amount")).isEqualTo("1500.00");
        }
    }
}
```

- **`consumerFactory.createConsumer(groupId, clientIdSuffix)`** builds a plain Kafka `Consumer` from the application's own consumer configuration (same broker, same `auto-offset-reset=earliest`), but in a **new, random consumer group**. A new group starts from the beginning of the topic and doesn't steal partitions from the application's listener.
- **`KafkaTestUtils.getRecords`** (from `spring-kafka-test`) polls for up to the given time. Inside `await()` it's called repeatedly, collecting records until both alerts for this account have arrived. Other tests' alerts are on the same topic, which is why it filters by key.
- **`JsonPath.read`** checks the JSON other teams will receive: the amount is a string, `"1500.00"`.
- **try-with-resources** closes the consumer, so it leaves the group cleanly.

Run everything: `./mvnw test`.

---

## Your turn

### Exercise 1: an unusual-hour rule

Add a fifth rule, `UNUSUAL_HOUR`: flag any payment of at least £250 made between midnight and 6am **UK time**.

1. Add a nested `UnusualHour(@NotNull @Positive BigDecimal minimum)` record to `RulesProperties`, a `fraud.rules.unusual-hour.minimum=250.00` property, and the rule to `RulesConfig`.
2. Write `UnusualHourRule` and `UnusualHourRuleTest`, including a test that proves you used UK time, not UTC.
3. Compile before adding it to `Rule`'s `permits` clause, and read the error.

<details>
<summary>Hint</summary>

`occurredAt` is an `Instant`, which has no time zone. `txn.occurredAt().atZone(ZoneId.of("Europe/London")).toLocalTime()` gives the wall-clock time in the UK, including British Summer Time. In July, 05:30 UTC is 06:30 in London.

</details>

<details>
<summary>Solution</summary>

`rules/UnusualHourRule.java`:

```java
package com.example.fraud.rules;

import com.example.fraud.money.Money;
import com.example.fraud.transaction.TransactionEvent;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

/** A sizeable payment in the middle of the night, UK time. */
public final class UnusualHourRule implements Rule {

    private static final ZoneId UK = ZoneId.of("Europe/London");
    private static final LocalTime NIGHT_ENDS = LocalTime.of(6, 0);

    private final Money minimum;

    public UnusualHourRule(RulesProperties.UnusualHour config) {
        this.minimum = Money.gbp(config.minimum().toPlainString());
    }

    @Override
    public String code() {
        return "UNUSUAL_HOUR";
    }

    @Override
    public Optional<RuleHit> evaluate(TransactionEvent txn, AccountHistory history) {
        LocalTime ukTime = txn.occurredAt().atZone(UK).toLocalTime();
        if (ukTime.isBefore(NIGHT_ENDS) && !txn.amount().isLessThan(minimum)) {
            return Optional.of(new RuleHit(code(), "%s at %s UK time".formatted(txn.amount(), ukTime)));
        }
        return Optional.empty();
    }
}
```

Before you add it to `permits`, the build fails with:

```
class is not allowed to extend sealed class: com.example.fraud.rules.Rule (as it is not listed in its 'permits' clause)
```

Add it: `permits LargeAmountRule, VelocityRule, NewPayeeRule, StructuringRule, UnusualHourRule`. (If you also forget `final`, you get `sealed, non-sealed or final modifiers expected`.)

In `RulesProperties`, add `@Valid @NotNull UnusualHour unusualHour` as the last component, and:

```java
    /** Flag a payment of at least minimum between midnight and 6am UK time. */
    public record UnusualHour(@NotNull @Positive BigDecimal minimum) {
    }
```

In `RulesConfig`, add `new UnusualHourRule(properties.unusualHour())` to the end of the list, and in `application.properties`:

```properties
fraud.rules.unusual-hour.minimum=250.00
```

`src/test/java/com/example/fraud/rules/UnusualHourRuleTest.java`:

```java
package com.example.fraud.rules;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static com.example.fraud.TestTransactions.payment;
import static org.assertj.core.api.Assertions.assertThat;

class UnusualHourRuleTest {

    UnusualHourRule rule = new UnusualHourRule(new RulesProperties.UnusualHour(new BigDecimal("250.00")));

    @Test
    void flagsASizeablePaymentAt3amInWinter() {
        var txn = payment("300.00").at(Instant.parse("2026-01-15T03:00:00Z")).build();

        assertThat(rule.evaluate(txn, AccountHistory.empty()))
                .map(RuleHit::reason)
                .hasValue("GBP 300.00 at 03:00 UK time");
    }

    @Test
    void usesUkTimeNotUtcInSummer() {
        // 05:30 UTC in July is 06:30 in London (BST), so it's morning, not night
        var txn = payment("300.00").at(Instant.parse("2026-07-15T05:30:00Z")).build();

        assertThat(rule.evaluate(txn, AccountHistory.empty())).isEmpty();
    }

    @Test
    void ignoresSmallNightTimePayments() {
        var txn = payment("249.99").at(Instant.parse("2026-01-15T03:00:00Z")).build();

        assertThat(rule.evaluate(txn, AccountHistory.empty())).isEmpty();
    }
}
```

Notice the rule still uses **event time**, converted to a zone. A rule that asked the clock would flag a backlog of daytime payments processed at 3am.

</details>

### Exercise 2: test the configuration without starting everything

Starting the whole application (with Kafka) to check a property binds is slow. Spring Boot's `ApplicationContextRunner` starts just the configuration classes you name. Write `RulesConfigTest` with two tests: all the properties bind and the engine contains all five rules in order; and a velocity limit of `0` stops the context from starting, with a failure that mentions `fraud.rules.velocity.maxTransactions`.

<details>
<summary>Hint</summary>

`new ApplicationContextRunner().withUserConfiguration(RulesConfig.class).withPropertyValues("fraud.rules.history=90d", …)`, then `.run(context -> …)`. `assertThat(context).hasFailed()` and `context.getStartupFailure()` are AssertJ helpers for the context. A second `.withPropertyValues(...)` on the same runner overrides earlier values.

</details>

<details>
<summary>Solution</summary>

`src/test/java/com/example/fraud/rules/RulesConfigTest.java`:

```java
package com.example.fraud.rules;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Starts just the rules configuration: no web server, no database, no Kafka. */
class RulesConfigTest {

    ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RulesConfig.class)
            .withPropertyValues(
                    "fraud.rules.history=90d",
                    "fraud.rules.large-amount.multiplier=5",
                    "fraud.rules.large-amount.minimum=500.00",
                    "fraud.rules.velocity.max-transactions=5",
                    "fraud.rules.velocity.window=1h",
                    "fraud.rules.new-payee.threshold=1000.00",
                    "fraud.rules.structuring.threshold=1000.00",
                    "fraud.rules.structuring.round-to=100.00",
                    "fraud.rules.structuring.min-count=3",
                    "fraud.rules.structuring.window=24h",
                    "fraud.rules.unusual-hour.minimum=250.00");

    @Test
    void bindsEveryRuleFromProperties() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RulesProperties.class).velocity().window()).isEqualTo(Duration.ofHours(1));
            assertThat(context.getBean(RuleEngine.class).ruleCodes())
                    .containsExactly("LARGE_AMOUNT", "VELOCITY", "NEW_PAYEE", "STRUCTURING", "UNUSUAL_HOUR");
        });
    }

    @Test
    void refusesToStartWithANonsenseThreshold() {
        runner.withPropertyValues("fraud.rules.velocity.max-transactions=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("fraud.rules.velocity.maxTransactions");
        });
    }
}
```

Both tests run in well under a second. Validation still works without the rest of Spring Boot, because Boot's configuration-properties binder uses the Hibernate Validator on the classpath directly.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 34, Failures: 0, Errors: 0, Skipped: 0
```

That's 19 new tests from the steps (16 rule and engine tests, 2 in `MonitoringFlowTest`, 1 in `AlertPublishingTest`) plus 5 from the exercises, on top of guide 01's 10.

New files since guide 01:

```
src/main/java/com/example/fraud/
├── alert/
│   ├── Alert.java
│   ├── AlertController.java
│   ├── AlertPublisher.java
│   ├── AlertRaised.java
│   └── AlertRepository.java
└── rules/
    ├── AccountHistory.java
    ├── LargeAmountRule.java
    ├── NewPayeeRule.java
    ├── PastTransaction.java
    ├── Rule.java
    ├── RuleEngine.java
    ├── RuleHit.java
    ├── RulesConfig.java
    ├── RulesProperties.java
    ├── StructuringRule.java
    ├── UnusualHourRule.java
    └── VelocityRule.java
src/main/resources/db/migration/
└── V2__create_alert.sql
src/test/java/com/example/fraud/
├── TestTransactions.java
├── alert/
│   ├── AlertPublishingTest.java
│   └── MonitoringFlowTest.java
└── rules/
    ├── LargeAmountRuleTest.java
    ├── NewPayeeRuleTest.java
    ├── RuleEngineTest.java
    ├── RulesConfigTest.java
    ├── StructuringRuleTest.java
    ├── UnusualHourRuleTest.java
    └── VelocityRuleTest.java
```

Modified: `Topics.java`, `KafkaConfig.java`, `AccountTransactionRepository.java`, `TransactionMonitoringService.java`, `TransactionListener.java`, `application.properties`, `requests.http`.

---

## Common errors

**`Binding to target com.example.fraud.rules.RulesProperties failed: Property: fraud.rules.history Value: "null" Reason: must not be null`**
A property is missing or misspelled, so its record component is `null` and `@NotNull` caught it. Check the spelling in kebab case: `fraud.rules.history`, `fraud.rules.velocity.max-transactions`.

**`Reason: must be greater than 0`**
A threshold is zero or negative. That's the validation doing its job.

**`class is not allowed to extend sealed class: com.example.fraud.rules.Rule (as it is not listed in its 'permits' clause)`**
A new rule isn't in `Rule`'s `permits` list.

**`sealed, non-sealed or final modifiers expected`**
A class implementing a sealed interface must be `final`, `sealed` or `non-sealed`. Make the rule `final`.

**`ArithmeticException: Non-terminating decimal expansion; no exact representable decimal result.`**
You divided `BigDecimal`s without a scale and rounding mode. Use `divide(divisor, 2, RoundingMode.HALF_EVEN)` as `averageAmount` does.

**`IllegalArgumentException: Currency mismatch: EUR and GBP`** inside a rule
A non-GBP event reached a rule, which compares it with a GBP threshold. `Money` refuses to compare across currencies, which is right. Guide 03 deals with these events properly. (And if you compare a `BigDecimal` threshold directly with a `BigDecimal` amount instead of converting the threshold with `Money.gbp(...)`, you lose that protection.)

**The velocity test raises no alert, or one too many**
Check the window edges: `between` excludes `from` and includes `to`, and `+ 1` counts the transaction being evaluated. Also check the service loads history *before* saving the new transaction.

**`AlertPublishingTest` times out**
The test consumer joined an existing group, or started reading at the end of the topic. Make sure the group id is unique per test (`"alert-test-" + UUID.randomUUID()`) and that `spring.kafka.consumer.auto-offset-reset=earliest` is set, since the test's consumer inherits it.

---

## Recap

- A **rule** encodes a suspicious pattern: large amounts, velocity, new payees, structuring. An **alert** records one hit and must say **why**.
- **Most alerts are false positives**, so thresholds must be easy to tune and see, and reasons must be clear.
- **Rules as pure functions** (transaction + history in, `Optional<RuleHit>` out) are fast to test and deterministic. The service does the gathering and recording around them.
- A **sealed interface** makes the full set of rules explicit and compiler-checked, which suits a regulated control. Use a plain interface when rules must be pluggable.
- **`@ConfigurationProperties` on a validated record** keeps thresholds immutable, visible and checked at start-up.
- **Rules use event time** (`occurredAt`). The **clock** is for processing time (`receivedAt`, `raisedAt`).
- **Publish events after the database commit**, and know that the gap is the **dual-write problem**, fixed properly with a transactional outbox.

### Interview angle

<details>
<summary>"How would you design a rule engine for transaction monitoring?"</summary>

Separate the decision from the plumbing. Each rule is a pure function from (transaction, account history) to an optional hit with a reason code and human-readable explanation. An engine runs all rules and collects hits. A service around it loads the history, calls the engine, and records the transaction and alerts in one database transaction. Rule thresholds live in validated configuration so they can be tuned without code changes. That gives fast unit tests, deterministic replay, and explainable alerts. Mention event time versus processing time, and that real platforms add per-segment thresholds, more data, and governance over rule changes.

</details>

<details>
<summary>"Your fraud rules raise too many alerts. What do you do?"</summary>

Measure first: how many alerts per rule, and what proportion were confirmed as genuine after investigation. Then tune the rules with the worst ratio (thresholds, minimum amounts, exclusions for known-good payees), ideally by replaying historical transactions through the new settings to see what would have been caught and missed before changing production. Segment customers so "normal" means something. Consider combining weak signals (a score) rather than alerting on each one. Any change is a change to a control, so it's documented and approved.

</details>

<details>
<summary>"What's the difference between event time and processing time?"</summary>

Event time is when something happened in the real world, carried in the event. Processing time is when your system handles it. They diverge when there are delays, outages or backlogs. Time-window logic (velocity, daily limits) should use event time, otherwise replaying or catching up on a backlog gives wrong answers. Processing time is for operational timestamps like "alert raised at". Inject a `Clock` for processing time so it's testable.

</details>

<details>
<summary>"Why publish the alert event after the database transaction commits?"</summary>

So other systems never hear about an alert that was rolled back. The remaining risk is a crash between commit and publish, which loses the event: writing to a database and a broker can't be atomic on its own (the dual-write problem). The robust fix is the transactional outbox: write the event to an outbox table in the same transaction, and publish it from there with retries, accepting that consumers may see it more than once.

</details>

### Further reading

- [FCA Financial Crime Guide (FCG)](https://www.handbook.fca.org.uk/handbook/FCG/): the FCA's guidance on financial crime systems and controls, including transaction monitoring
- [JMLSG guidance](https://www.jmlsg.org.uk/guidance/current-guidance/): the UK financial sector's detailed AML guidance, including monitoring and red flags
- [Spring Boot reference: externalised configuration](https://docs.spring.io/spring-boot/4.1.2/reference/features/external-config.html), especially type-safe configuration properties and validation
- [Java language: sealed classes and interfaces](https://docs.oracle.com/en/java/javase/25/language/sealed-classes-and-interfaces.html)
- [microservices.io: transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html)
- [Spring for Apache Kafka reference: sending messages](https://docs.spring.io/spring-kafka/reference/kafka/sending-messages.html)

**Next:** [03: Reliable consumers](03-reliable-consumers.md). Kafka will deliver the same event twice, and some events can never be processed. You'll make the consumer survive both.
