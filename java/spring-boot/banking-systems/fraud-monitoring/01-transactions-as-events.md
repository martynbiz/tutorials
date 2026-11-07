# 01: Transactions as Events

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- What **transaction monitoring** is for, and the difference between monitoring for **fraud** and monitoring for **money laundering**
- Why a bank's monitoring system listens to **events** instead of being called directly
- The handful of **Kafka** ideas you need: topics, partitions, keys, offsets and consumer groups
- How to run Kafka locally with **Docker Compose**, and in tests with **Testcontainers**
- How to consume JSON events with **`@KafkaListener`**, and publish them with **`KafkaTemplate`**
- How to test asynchronous code without `Thread.sleep`, using **Awaitility**

By the end you'll have a service that receives every payment a customer makes as an event on a Kafka topic, keeps its own history of each account's payments, and a test suite that runs against a real Kafka broker in Docker.

This is the first system in the series that **needs Docker**. Step 1 checks yours works before you write any code.

---

## Concepts

### How it works in a real bank

Every payment that leaves a customer's account (a card payment in a shop, a Faster Payment to a friend, a CHAPS payment for a house deposit) is also watched by another system whose only job is to ask: **does this look wrong?** That's **transaction monitoring**.

It does two different jobs, often run by two different teams, with different rules, deadlines and consequences:

| | Fraud monitoring | AML transaction monitoring |
|---|---|---|
| **Protects** | The customer and the bank from losing money | The financial system from being used to launder criminal money |
| **Typical question** | "Is someone using Alice's card who isn't Alice?" "Is Alice being tricked into sending money to a scammer?" | "Is money moving through this account in a way that doesn't fit what we know about the customer?" |
| **When** | As fast as possible, ideally before the money goes. Seconds matter. | Often after the event, looking at patterns over days or weeks |
| **What happens next** | Block the card, hold the payment, phone or text the customer | An analyst investigates. If there's suspicion, it goes to the bank's **nominated officer**, who decides whether to make a **Suspicious Activity Report (SAR)** to the National Crime Agency |
| **Why the bank must do it** | Losses, customer harm, and reimbursement rules | The law requires it |

[AML](../glossary.md#aml) monitoring isn't optional. The [Money Laundering Regulations 2017](https://www.legislation.gov.uk/uksi/2017/692/contents) (regulation 28(11)) require a bank to conduct ongoing monitoring of each business relationship, including scrutiny of transactions to make sure they're consistent with what the bank knows about the customer. That's why monitoring is closely tied to [KYC](../glossary.md#kyc): you can only spot "unusual for this customer" if you know what usual looks like.

A growing share of fraud in the UK doesn't involve stolen cards at all. In **authorised push payment (APP) fraud**, a criminal persuades the customer to send the money themselves: a fake "bank security team" asks them to move their savings to a "safe account", or a fake builder sends an invoice with the criminal's account details. The customer authorises the payment, so passwords and card checks don't help. Monitoring is one of the few defences: a large [Faster Payment](../glossary.md#faster-payments) to someone the customer has never paid before is exactly the kind of pattern it looks for. Since 7 October 2024, the [Payment Systems Regulator's rules](https://www.psr.org.uk/our-work/app-scams/) require most victims of APP scams over Faster Payments and [CHAPS](../glossary.md#chaps) to be reimbursed, with the cost shared between the sending and receiving banks, so banks now carry much of the loss directly.

> **Simplification:** this tutorial monitors payments *after* they've been made: it watches, scores and raises alerts. Real-time blocking (holding a payment while it's checked) needs the payment system to *ask* the fraud system and wait for an answer, in milliseconds. Many banks do both: a fast synchronous check on the payment path, and richer event-driven monitoring behind it. You're building the second kind.

### Rules or machine learning?

Banks detect suspicious transactions in two broad ways:

- **Rules:** "flag any payment over five times this customer's average". They're easy to explain to a regulator, an auditor or a customer, and easy to change. They're also blunt, and they produce many **false positives**: alerts for perfectly innocent payments.
- **Machine-learning models:** a score from 0 to 1 learned from past fraud. Better at subtle patterns, harder to explain, and they need data science teams and careful governance.

Most banks run both, side by side. This tutorial builds the rules, because they're where most of the engineering lives (state, configuration, testing, explanation) and because every alert they raise can say in plain English *why*.

### Why events?

The card system, the payments engine and the core ledger don't call the fraud system and wait. They each **publish an event** ("a payment of £42.50 left account `acc-1001`") to a message broker, and carry on. The fraud system **subscribes** and processes events at its own pace.

That decoupling matters in a bank:

- **A slow or broken fraud system doesn't stop payments.** Events queue up in the broker until it catches up.
- **New consumers can be added without touching the producers.** The AML team, a data warehouse and a customer-notification service can all read the same events.
- **Events are a record.** The broker keeps them for a configured period, so a consumer can re-read history after a bug fix.

### Kafka in five ideas

[Apache Kafka](https://kafka.apache.org/documentation/) is the broker most banks use for this. You need five ideas:

1. **Topic:** a named stream of events, like a table that only ever grows. You'll use one called `transactions`.
2. **Partition:** each topic is split into partitions so it can be processed in parallel. Your topic will have 3.
3. **Offset:** each event's position in its partition: 0, 1, 2, … Consumers remember the offset they've reached.
4. **Key:** every event can carry a key. Kafka sends all events with the same key **to the same partition**, by hashing the key. Within one partition, events stay **in order**. So if you key every event by account id, all of one account's events arrive in the order they were published. Across different partitions there's no ordering at all.
5. **Consumer group:** consumers that share a group id split the partitions between them. Each partition is read by exactly one consumer in the group at a time. Run three copies of the fraud service in group `fraud-monitoring`, and each one gets one partition.

```
topic: transactions
  partition 0:  [acc-2001 £5] [acc-1003 £12] [acc-2001 £60] ...   → consumer A
  partition 1:  [acc-1001 £42.50] [acc-1001 £2,500] ...           → consumer B
  partition 2:  [acc-1002 £9.99] ...                              → consumer C
                  offset 0          offset 1
```

Keying by account is the most important design decision in this whole tutorial. Guide 02's rules depend on an account's history being processed in order, and guide 04 shows what happens when that assumption breaks.

> **Sidebar: Kafka delivers "at least once".** A consumer processes an event and then tells Kafka "I've reached offset 42". If it crashes in between, it will be given the same event again after restarting. So every event can arrive **more than once**. Guide 03 deals with that. For now, just know it's coming.

### Docker joins the series

Kafka is a server. Unlike H2, it can't run inside your application. Rather than installing it, you'll run it in a **Docker container**, two ways:

- **When you run the app:** Spring Boot's **Docker Compose support** starts the containers in a `compose.yaml` file when the app starts, and stops them when it stops.
- **When you run the tests:** **Testcontainers** starts a fresh Kafka container for the test run and throws it away afterwards. Tests never depend on whatever is running on your machine.

The database stays H2. Only Kafka needs Docker.

### Money, briefly

Money in this tutorial is a small `Money` record: a `BigDecimal` plus a `java.util.Currency`, always at the currency's own number of decimal places, refusing to round silently, and refusing to mix currencies. It's sent in JSON as a string (`"42.50"`) so no client can lose pennies to floating point. The Core ledger tutorial covers this properly. Here, the short version is enough.

---

## Steps

### 1. Check Docker works

Install Docker Desktop (Windows, macOS) or Docker Engine (Linux) from the [Docker documentation](https://docs.docker.com/get-started/get-docker/) if you haven't already. Then, in a terminal:

```bash
docker version
docker compose version
docker run --rm hello-world
```

You want a version for both **Client** and **Server** from the first command, a version from the second, and this from the third:

```
Hello from Docker!
This message shows that your installation appears to be working correctly.
```

If `docker version` shows a Client but then an error such as `Cannot connect to the Docker daemon`, Docker is installed but not running. Start Docker Desktop (or `sudo systemctl start docker` on Linux) and try again. Don't go on until `hello-world` works: nothing in this tutorial will start without it.

### 2. Generate the project

Go to [start.spring.io](https://start.spring.io) and choose:

| Setting | Value |
|---|---|
| Project | Maven |
| Language | Java |
| Spring Boot | 4.1.2 (or the latest 4.1.x) |
| Group | `com.example` |
| Artifact | `fraud-monitoring` |
| Package name | `com.example.fraud` |
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
| **SpringDoc OpenAPI** | Swagger UI |
| **Spring for Apache Kafka** | `@KafkaListener`, `KafkaTemplate` (pulls in `spring-boot-starter-kafka`, and `spring-boot-starter-kafka-test` for tests) |
| **Testcontainers** | Real Kafka in Docker for tests |
| **Docker Compose Support** | Starts `compose.yaml` when the app starts |

Click **Generate** and unzip the download so the project folder ends up at:

```
tutorials/workspace/banking-systems/fraud-monitoring/
```

Open it in IntelliJ with **File → Open…**, choose the `fraud-monitoring` folder, and trust the project. Check **File → Project Structure → Project → SDK** is set to a Java 25 JDK.

Initializr generated more than usual. Have a look before deleting anything:

- **`compose.yaml`** contains `services: {}`. You'll fill it in next.
- **`src/test/java/com/example/fraud/TestcontainersConfiguration.java`** declares a Kafka container for tests. Keep it. You'll adjust it in step 13.
- **`TestFraudMonitoringApplication.java`** (in `src/test`) can start the app with Testcontainers instead of Compose. Delete it. This tutorial uses one way of running locally, Docker Compose.
- **`FraudMonitoringApplicationTests.java`**: delete it. You'll write better tests.
- **`HELP.md`**: delete it (it warns that the app won't start with an empty `compose.yaml`, which is true until step 3).

### 3. Kafka in `compose.yaml`, and the configuration

Replace `compose.yaml` with:

```yaml
services:
  kafka:
    image: apache/kafka:4.2.2
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
```

That's a **single-node Kafka in KRaft mode**: one process acting as both the *broker* (stores and serves events) and the *controller* (keeps track of the cluster). Older Kafka needed a separate ZooKeeper cluster for the controller job. Kafka 4 removed ZooKeeper entirely.

- **`apache/kafka:4.2.2`** is the official Apache Kafka image, pinned to an exact version. Never use `latest` for infrastructure: it changes under you.
- **`KAFKA_ADVERTISED_LISTENERS`** is the address the broker tells clients to use. A Kafka client connects to the address you give it, asks "where are the partitions?", and then connects to whatever the broker *advertises*. If this didn't say `localhost:9092`, your app would bootstrap fine and then fail to connect to the container's internal hostname.
- **The replication factors are 1** because there's only one broker. Real clusters use 3, so losing a broker loses no data.

Replace `src/main/resources/application.properties` with:

```properties
spring.application.name=fraud-monitoring

# H2 database stored in ./data/fraud.mv.db
spring.datasource.url=jdbc:h2:file:./data/fraud
spring.datasource.username=sa
spring.h2.console.enabled=true

# Flyway owns the schema; Hibernate only checks it matches the entities
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false

# Kafka: the broker from compose.yaml
spring.kafka.bootstrap-servers=localhost:9092
spring.kafka.consumer.group-id=fraud-monitoring
spring.kafka.consumer.auto-offset-reset=earliest
```

- `username=sa`: Spring Boot only defaults the username to `sa` for *in-memory* H2. Without this line the file database would get a blank username and the H2 console couldn't log in.
- `ddl-auto=validate` and `open-in-view=false`: as in every system in this series. Flyway owns the schema, and lazy-loading mistakes fail loudly.
- **`bootstrap-servers`**: where to find Kafka. Spring Boot's Docker Compose support can fill in connection details automatically for some services, such as PostgreSQL, but **not for Kafka**, so you say it yourself. (`localhost:9092` happens to be Boot's default, but writing it down makes it obvious where it comes from.)
- **`group-id`**: the consumer group. Every copy of this service shares it, so they split the partitions.
- **`auto-offset-reset=earliest`**: what a consumer group does the very first time it reads a topic, when it has no remembered offset. Kafka's default is `latest`, meaning "only events published from now on". For a monitoring system that would silently skip every payment made before its first start. `earliest` means "from the beginning".

Add `data/` to `.gitignore`:

```
### Local database ###
data/
```

### 4. The `Money` record

Create `money/Money.java` (that's `src/main/java/com/example/fraud/money/Money.java`; paths in this tutorial are relative to the base package):

```java
package com.example.fraud.money;

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
- **`@JsonFormat(shape = STRING)`** writes the amount as `"42.50"`, not `42.50`. **`@JsonAutoDetect(isGetterVisibility = NONE)`** stops Jackson from treating `isZero()`, `isPositive()` and `isNegative()` as JSON properties.
- **`isLessThan` uses `compareTo`, never `equals`**, because `BigDecimal.equals` also compares scale.
- **`toString()`** gives `GBP 42.50`. Guide 02's alert reasons use it, so they read naturally.

Add `src/test/java/com/example/fraud/money/MoneyTest.java`:

```java
package com.example.fraud.money;

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

Run it from the gutter arrow next to `class MoneyTest`, or:

```bash
./mvnw test -Dtest=MoneyTest
```

### 5. Inject the clock

Fraud rules are all about time: "six payments in an hour", "the last 90 days". So from the first line of code, nothing in this application asks the operating system what time it is. It asks a `Clock` bean. Create `config/ClockConfig.java`:

```java
package com.example.fraud.config;

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

`Instant.now(clock)` asks the clock instead of the system. In production they're the same. In guide 02 you'll meet an important subtlety: a monitoring system has **two** different "nows", and only one of them comes from this clock.

> **In older codebases:** `new Date()`, `System.currentTimeMillis()` and `LocalDateTime.now()` scattered through services, and tests that can't check time-based rules without `Thread.sleep`. `Clock` (Java 8+) is the standard fix.

### 6. The event

The fraud system doesn't own payments. It's told about them. The shape of what it's told is a contract with every system that publishes, so make it a record. Create `transaction/Channel.java`:

```java
package com.example.fraud.transaction;

/** How the money left the account. */
public enum Channel {
    CARD,
    FASTER_PAYMENT,
    CHAPS;

    /** A push payment: the customer told their bank to send money to someone. */
    public boolean isPushPayment() {
        return this != CARD;
    }
}
```

A card payment is *pulled* by the merchant through the card scheme. Faster Payments and CHAPS are *push* payments: the customer instructs their bank to send money. The difference matters for APP fraud, which (as its name says) involves push payments.

Then `transaction/TransactionEvent.java`:

```java
package com.example.fraud.transaction;

import com.example.fraud.money.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * "Money has left a customer's account." Published by the bank's payment and
 * card systems. The fraud system only ever reads these; it never changes them.
 */
public record TransactionEvent(
        UUID eventId,
        String accountId,
        Money amount,
        String payee,
        Channel channel,
        Instant occurredAt) {

    public TransactionEvent {
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(payee, "payee is required");
        Objects.requireNonNull(channel, "channel is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
    }
}
```

- **`eventId`** uniquely identifies *this event*. The producer generates it. You'll lean on it heavily in guide 03.
- **`payee`** is who the money went to: a merchant name for a card payment, or a payee name for a transfer. Real events carry much more (sort code and account number, merchant category code, device, location). One string is enough for the rules you'll build.
- **`occurredAt`** is when the payment happened, according to the system that made it.
- **The compact constructor** makes an incomplete event impossible to create. When Jackson builds a `TransactionEvent` from JSON with a field missing, the constructor throws and the event is refused. You'll see what Kafka does with refused events in guide 03.

### 7. A topic, and JSON on the wire

Create `config/Topics.java` so topic names live in one place:

```java
package com.example.fraud.config;

/** Kafka topic names, in one place. */
public final class Topics {

    /** Every card payment and outgoing transfer, published by the bank's other systems. */
    public static final String TRANSACTIONS = "transactions";

    private Topics() {
    }
}
```

They're `static final String` constants, not an enum, because annotation attributes such as `@KafkaListener(topics = …)` must be compile-time constants.

Then `config/KafkaConfig.java`:

```java
package com.example.fraud.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJacksonJsonMessageConverter;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class KafkaConfig {

    /** Created on start-up if it doesn't exist yet. */
    @Bean
    NewTopic transactionsTopic() {
        return TopicBuilder.name(Topics.TRANSACTIONS).partitions(3).replicas(1).build();
    }

    /** Messages travel as JSON strings, converted with the same JsonMapper as the REST API. */
    @Bean
    RecordMessageConverter messageConverter(JsonMapper jsonMapper) {
        return new StringJacksonJsonMessageConverter(jsonMapper);
    }
}
```

- **A `NewTopic` bean** is picked up by Spring's `KafkaAdmin`, which creates the topic on start-up if it doesn't exist. In a bank, topics are usually created by a platform team or through infrastructure-as-code, not by the application. Declaring them in code is fine for development.
- **How the JSON gets in and out.** Kafka itself only moves bytes. By default Spring Boot configures Kafka to send and receive those bytes as **plain `String`s**. The `RecordMessageConverter` bean sits between that string and your code: on the way in it turns the JSON string into whatever type your `@KafkaListener` method asks for; on the way out it turns your object into a JSON string. Spring Boot notices the bean and wires it into both the listener containers and the `KafkaTemplate`.
- **`StringJacksonJsonMessageConverter`** is Spring for Apache Kafka 4's Jackson 3 converter. Passing in Spring Boot's own `JsonMapper` means events are read and written exactly like your REST API's JSON: same `Instant` format, same `Money` format.

> **Sidebar: JSON serializers.** Another common setup is to configure Kafka's own *serializer* and *deserializer* classes for JSON (`JacksonJsonSerializer` and `JacksonJsonDeserializer` in Spring for Apache Kafka 4), so the conversion happens inside the Kafka client. It works, but a message that isn't valid JSON then fails *before* Spring hands it to your code, which needs extra machinery (`ErrorHandlingDeserializer`) to deal with. Strings plus a message converter keep every failure inside the listener, where guide 03's error handling can see it.

> **In older codebases:** you'll see `JsonSerializer`, `JsonDeserializer` and `StringJsonMessageConverter`, the Jackson 2 versions. They still exist in Spring for Apache Kafka 4 but are deprecated in favour of the `Jackson…` classes.

### 8. The account's history

The rules in guide 02 need to know what an account normally does, so the fraud system keeps its **own copy** of every transaction it sees. It doesn't ask the core ledger: that would couple the two systems together and put load on the ledger for every event.

Create `src/main/resources/db/migration/V1__create_account_transaction.sql`:

```sql
CREATE TABLE account_transaction (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    event_id    UUID                     NOT NULL,
    account_id  VARCHAR(40)              NOT NULL,
    amount      NUMERIC(19, 4)           NOT NULL,
    currency    VARCHAR(3)               NOT NULL,
    payee       VARCHAR(100)             NOT NULL,
    channel     VARCHAR(20)              NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX ix_account_transaction_account ON account_transaction (account_id, occurred_at);
```

The SQL runs unchanged on H2 and PostgreSQL. `UUID` is a native type in both.

- **`occurred_at` and `received_at` are both kept.** One is when the payment happened, the other is when *you* heard about it. They differ when events are delayed, and guide 02 explains why that matters.
- **The index on `(account_id, occurred_at)`** matches the query every rule will run: "this account's transactions in a time window".
- **There's no unique constraint on `event_id`.** That's deliberate, for now. Guide 03 starts by showing the bug it allows.

Create `transaction/AccountTransaction.java`:

```java
package com.example.fraud.transaction;

import com.example.fraud.money.Money;
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
import java.util.UUID;

/** The fraud system's own copy of one transaction: the account's history. */
@Entity
@Table(name = "account_transaction")
public class AccountTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "account_id", nullable = false, length = 40)
    private String accountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currencyCode;

    @Column(nullable = false, length = 100)
    private String payee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Channel channel;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected AccountTransaction() {
        // for JPA
    }

    public static AccountTransaction record(TransactionEvent event, Instant receivedAt) {
        AccountTransaction txn = new AccountTransaction();
        txn.eventId = event.eventId();
        txn.accountId = event.accountId();
        txn.amount = event.amount().amount();
        txn.currencyCode = event.amount().currency().getCurrencyCode();
        txn.payee = event.payee();
        txn.channel = event.channel();
        txn.occurredAt = event.occurredAt();
        txn.receivedAt = receivedAt;
        return txn;
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getAccountId() {
        return accountId;
    }

    public Money getAmount() {
        return new Money(amount, Currency.getInstance(currencyCode));
    }

    public String getPayee() {
        return payee;
    }

    public Channel getChannel() {
        return channel;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
```

No setters, a named factory, plain column types inside and `Money` outside: the same entity style as every system in this series.

`transaction/AccountTransactionRepository.java`:

```java
package com.example.fraud.transaction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, Long> {

    List<AccountTransaction> findByAccountIdOrderByOccurredAtDesc(String accountId);
}
```

And `transaction/TransactionMonitoringService.java`. Today it only records. In guide 02 it becomes the heart of the system:

```java
package com.example.fraud.transaction;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class TransactionMonitoringService {

    private final AccountTransactionRepository transactions;
    private final Clock clock;

    public TransactionMonitoringService(AccountTransactionRepository transactions, Clock clock) {
        this.transactions = transactions;
        this.clock = clock;
    }

    @Transactional
    public void process(TransactionEvent event) {
        transactions.save(AccountTransaction.record(event, Instant.now(clock)));
    }

    @Transactional(readOnly = true)
    public List<AccountTransaction> history(String accountId) {
        return transactions.findByAccountIdOrderByOccurredAtDesc(accountId);
    }
}
```

### 9. The listener

Create `transaction/TransactionListener.java`:

```java
package com.example.fraud.transaction;

import com.example.fraud.config.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/** The front door: every transaction event comes in here. */
@Component
public class TransactionListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionListener.class);

    private final TransactionMonitoringService monitoring;

    public TransactionListener(TransactionMonitoringService monitoring) {
        this.monitoring = monitoring;
    }

    @KafkaListener(topics = Topics.TRANSACTIONS)
    public void on(TransactionEvent event,
                   @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                   @Header(KafkaHeaders.OFFSET) long offset) {
        log.info("Received {} for account {} (partition {}, offset {})",
                event.eventId(), event.accountId(), partition, offset);
        monitoring.process(event);
    }
}
```

That's a complete Kafka consumer:

- **`@KafkaListener(topics = …)`** makes Spring create a *listener container*: a background thread that polls Kafka, hands each record to this method, and commits the offset once the method returns.
- **The first parameter's type**, `TransactionEvent`, tells the message converter what to build from the JSON.
- **`@Header`** parameters pull Kafka metadata out of the record. Logging the partition and offset makes it easy to see keys and partitions at work.
- **The listener is thin.** It logs and delegates. All the logic lives in the service, which you can test without Kafka. That separation pays off in guide 04.
- The consumer group comes from `spring.kafka.consumer.group-id`. You could also write `@KafkaListener(topics = …, groupId = "…")`.

### 10. Read the history back

`transaction/TransactionController.java`:

```java
package com.example.fraud.transaction;

import com.example.fraud.money.Money;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class TransactionController {

    private final TransactionMonitoringService monitoring;

    public TransactionController(TransactionMonitoringService monitoring) {
        this.monitoring = monitoring;
    }

    @GetMapping("/accounts/{accountId}/transactions")
    public List<TransactionResponse> history(@PathVariable String accountId) {
        return monitoring.history(accountId).stream().map(TransactionResponse::from).toList();
    }

    public record TransactionResponse(
            UUID eventId, Money amount, String payee, Channel channel, Instant occurredAt, Instant receivedAt) {

        static TransactionResponse from(AccountTransaction txn) {
            return new TransactionResponse(txn.getEventId(), txn.getAmount(), txn.getPayee(),
                    txn.getChannel(), txn.getOccurredAt(), txn.getReceivedAt());
        }
    }
}
```

### 11. A simulator to publish events

In a bank, the card and payment systems publish the events. You don't have those, so add an endpoint that stands in for them. Create `simulator/SimulatorController.java`:

```java
package com.example.fraud.simulator;

import com.example.fraud.config.Topics;
import com.example.fraud.money.Money;
import com.example.fraud.transaction.Channel;
import com.example.fraud.transaction.TransactionEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Stands in for the bank's payment and card systems: publishes a transaction
 * event to Kafka, exactly as they would. Not part of a real fraud system.
 */
@RestController
public class SimulatorController {

    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;

    public SimulatorController(KafkaTemplate<String, String> kafka, Clock clock) {
        this.kafka = kafka;
        this.clock = clock;
    }

    @PostMapping("/simulator/transactions")
    public ResponseEntity<PublishedResponse> publish(@Valid @RequestBody SimulatedTransaction request) {
        TransactionEvent event = new TransactionEvent(
                request.eventId() != null ? request.eventId() : UUID.randomUUID(),
                request.accountId(),
                Money.gbp(request.amount().toPlainString()),
                request.payee(),
                request.channel(),
                request.occurredAt() != null ? request.occurredAt() : Instant.now(clock));

        var message = MessageBuilder.withPayload(event)
                .setHeader(KafkaHeaders.TOPIC, Topics.TRANSACTIONS)
                .setHeader(KafkaHeaders.KEY, event.accountId())
                .build();
        var result = kafka.send(message).join(); // wait for the broker to acknowledge

        return ResponseEntity.accepted().body(new PublishedResponse(
                event.eventId(), result.getRecordMetadata().partition(), result.getRecordMetadata().offset()));
    }

    public record SimulatedTransaction(
            UUID eventId,
            @NotBlank @Size(max = 40) String accountId,
            @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 100) String payee,
            @NotNull Channel channel,
            Instant occurredAt) {
    }

    public record PublishedResponse(UUID eventId, int partition, long offset) {
    }
}
```

- **`KafkaTemplate<String, String>`** is Spring's producer. Both key and value are strings on the wire.
- **`MessageBuilder.withPayload(event)`** sends a Spring `Message` instead of a raw value. That's what makes the template run your `RecordMessageConverter`, turning the record into a JSON string. The `TOPIC` and `KEY` headers say where it goes.
- **The key is the account id.** That's the "all of one account's events go to the same partition" rule from the Concepts section, in one line.
- **`send()` is asynchronous.** It returns a `CompletableFuture<SendResult>`. `join()` waits for the broker to confirm it has stored the event, which is right for a simulator. A busy real producer would usually not block like this.
- **`202 Accepted`**, not `201 Created`: the event has been accepted for processing, but nothing has been processed yet.
- **`eventId` and `occurredAt` are optional.** Supplying an old `eventId` lets you resend the same event on purpose, which you'll do in guide 03.

### 12. Errors as `ProblemDetail`

Create `web/ApiExceptionHandler.java`, the same base handler as every system in the series:

```java
package com.example.fraud.web;

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

Extending `ResponseEntityExceptionHandler` switches every Spring MVC error to [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) `ProblemDetail` JSON. The override adds an `errors` map of field-by-field validation messages.

### 13. Run it

Run the app: open `FraudMonitoringApplication.java` and click the green arrow next to `main`.

**Terminal alternative:**

```bash
./mvnw spring-boot:run
```

Watch the start of the log. Before Spring even creates the database, Docker Compose support starts Kafka:

```
Container fraud-monitoring-kafka-1 Created
Container fraud-monitoring-kafka-1 Starting
Container fraud-monitoring-kafka-1 Started
```

(The container name comes from the project folder and the service name, so yours may differ.) Then Flyway runs `V1`, the app starts, and the listener joins its consumer group:

```
fraud-monitoring: partitions assigned: [transactions-0, transactions-1, transactions-2]
```

One consumer, so it gets all three partitions. When you stop the app, Boot stops the container too. The container isn't deleted, so topics and events survive a restart.

Create `requests.http` in the project root. You'll add to it in every guide:

```http
### Alice buys something in a shop
POST http://localhost:8080/simulator/transactions
Content-Type: application/json

{"accountId": "acc-1001", "amount": "42.50", "payee": "Corner Shop", "channel": "CARD"}

### Alice's history, as the fraud system sees it
GET http://localhost:8080/accounts/acc-1001/transactions

### A bad request (should be 400)
POST http://localhost:8080/simulator/transactions
Content-Type: application/json

{"accountId": "", "amount": "-1"}
```

**(Ultimate)** Click the green ▶ beside each `###` request.

**Free edition / terminal alternatives:** open `http://localhost:8080/swagger-ui.html`, or use curl:

```bash
curl -i -X POST localhost:8080/simulator/transactions -H 'Content-Type: application/json' \
  -d '{"accountId":"acc-1001","amount":"42.50","payee":"Corner Shop","channel":"CARD"}'
```

You get `202` and something like:

```json
{"eventId":"0c8cfb4e-34a8-4ea2-99e9-756cac642fbb","partition":1,"offset":0}
```

In the app's log, the listener picks it up a moment later:

```
Received 0c8cfb4e-34a8-4ea2-99e9-756cac642fbb for account acc-1001 (partition 1, offset 0)
```

And the history endpoint returns it:

```json
[{"eventId":"0c8cfb4e-34a8-4ea2-99e9-756cac642fbb","amount":{"amount":"42.50","currency":"GBP"},"payee":"Corner Shop","channel":"CARD","occurredAt":"2026-11-07T09:30:24.077857Z","receivedAt":"2026-11-07T09:30:24.291268Z"}]
```

Send a few more for `acc-1001`. They all land on the same partition, with increasing offsets. Try other account ids, and they spread across partitions.

The bad request gets a field-by-field `ProblemDetail`:

```json
{"detail":"Invalid request content.","instance":"/simulator/transactions","status":400,"title":"Bad Request","errors":{"accountId":"must not be blank","amount":"must be greater than 0","channel":"must not be null","payee":"must not be blank"}}
```

**Look inside Kafka.** The `apache/kafka` image includes Kafka's command-line tools. With the app running, from the project folder:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic transactions --from-beginning \
  --formatter-property print.key=true --formatter-property print.partition=true
```

```
Partition:1	acc-1001	{"eventId":"0c8cfb4e-34a8-4ea2-99e9-756cac642fbb","accountId":"acc-1001","amount":{"amount":"42.50","currency":"GBP"},"payee":"Corner Shop","channel":"CARD","occurredAt":"2026-11-07T09:30:24.077857191Z"}
```

That's exactly what's on the wire: a key and a JSON string. Press <kbd>Ctrl</kbd>+<kbd>C</kbd> to stop. Two more commands worth knowing:

```bash
# The topic's partitions
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --describe --topic transactions

# How far your consumer group has read each partition, and its lag
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group fraud-monitoring
```

The second shows a `CURRENT-OFFSET`, `LOG-END-OFFSET` and `LAG` per partition. **Lag** (how many events are waiting) is the number a monitoring team watches most closely. If it grows, the fraud system is falling behind the bank's payments.

### 14. Tests against a real Kafka

Testing a Kafka consumer with mocks proves very little. The interesting failures (JSON that doesn't convert, keys and partitions, offsets, redelivery) all happen inside Kafka and Spring's listener container. So these tests use a **real broker** in Docker.

Open the generated `src/test/java/com/example/fraud/TestcontainersConfiguration.java` and pin the image to the same version as `compose.yaml`:

```java
package com.example.fraud;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** A real Kafka broker in Docker, started once for the whole test run. */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.2"));
    }
}
```

(Initializr generated it with `apache/kafka-native:latest`, a lighter, natively compiled image. It works too, but pin a version, and keep tests and local runs on the same image.)

- **`org.testcontainers.kafka.KafkaContainer`** is the Testcontainers 2 Kafka module (artifact `testcontainers-kafka`) for the Apache images. The container is a Spring bean, so it starts before the application context needs it and stops when the context closes.
- **`@ServiceConnection`** is the clever part. Spring Boot reads the container's random host port and sets `spring.kafka.bootstrap-servers` to point at it, overriding `localhost:9092` from `application.properties`. No properties to copy, and no clashes with a Kafka you might have running.

Now the meta-annotation every integration test will use. `src/test/java/com/example/fraud/FraudIntegrationTest.java`:

```java
package com.example.fraud;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Full application, real Flyway migrations, in-memory H2, a real Kafka broker
 * in a Testcontainer, and a MockMvcTester for calling the API.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:fraud-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public @interface FraudIntegrationTest {
}
```

Every class using `@FraudIntegrationTest` has identical configuration, so Spring builds **one** application context, and therefore starts **one** Kafka container, for the whole test run. That matters more than ever here: a context that differed (say, one test class adding a `@MockitoBean`) would start a second container. It also means **all integration tests share one database and one broker**, so every test creates its own uniquely named accounts and only asserts about those.

Docker Compose support is switched off in tests by default, so `compose.yaml` isn't started by `./mvnw test`.

First, a plain unit test for the event's guard. `src/test/java/com/example/fraud/transaction/TransactionEventTest.java`:

```java
package com.example.fraud.transaction;

import com.example.fraud.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionEventTest {

    @Test
    void refusesAnEventWithoutAnId() {
        assertThatThrownBy(() -> new TransactionEvent(null, "acc-1", Money.gbp("10.00"),
                "Coffee Shop", Channel.CARD, Instant.now()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("eventId is required");
    }
}
```

Then the real thing: publish raw JSON to Kafka and check it ends up in the database. `src/test/java/com/example/fraud/transaction/TransactionListenerTest.java`:

```java
package com.example.fraud.transaction;

import com.example.fraud.FraudIntegrationTest;
import com.example.fraud.config.Topics;
import com.example.fraud.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@FraudIntegrationTest
class TransactionListenerTest {

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired TransactionMonitoringService monitoring;

    @Test
    void recordsATransactionPublishedToKafka() {
        String account = "acc-" + UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        kafka.send(Topics.TRANSACTIONS, account, """
                {
                  "eventId": "%s",
                  "accountId": "%s",
                  "amount": {"amount": "42.50", "currency": "GBP"},
                  "payee": "Corner Shop",
                  "channel": "CARD",
                  "occurredAt": "2026-11-07T09:15:00Z"
                }
                """.formatted(eventId, account));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(monitoring.history(account)).hasSize(1));

        AccountTransaction recorded = monitoring.history(account).getFirst();
        assertThat(recorded.getEventId()).isEqualTo(eventId);
        assertThat(recorded.getAmount()).isEqualTo(Money.gbp("42.50"));
        assertThat(recorded.getOccurredAt()).isEqualTo(Instant.parse("2026-11-07T09:15:00Z"));
    }
}
```

- **The test sends a hand-written JSON string**, not a `TransactionEvent`. That pins down the wire format: if someone renames a field in the record, this test fails, just as a real producer's events would start failing.
- **`await().atMost(…).untilAsserted(…)`** is [Awaitility](http://www.awaitility.org/) (included with `spring-boot-starter-kafka-test`). The listener runs on another thread, some milliseconds later. Awaitility re-runs the assertion every 100 ms until it passes or the time runs out. It's faster and far more reliable than `Thread.sleep(2000)`, which is either too long (slow tests) or too short (flaky tests).
- **The account id is random**, so this test can't see data from any other test.

Finally the simulator's API. `src/test/java/com/example/fraud/simulator/SimulatorApiTest.java`:

```java
package com.example.fraud.simulator;

import com.example.fraud.FraudIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@FraudIntegrationTest
class SimulatorApiTest {

    @Autowired MockMvcTester mvc;

    @Test
    void publishedTransactionsShowUpInTheAccountHistory() {
        String account = "acc-" + UUID.randomUUID();

        MvcTestResult published = publish(account, "25.00");
        assertThat(published).hasStatus(HttpStatus.ACCEPTED);
        assertThat(published).bodyJson().hasPath("$.eventId");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(mvc.get().uri("/accounts/{id}/transactions", account))
                        .bodyJson().extractingPath("$[0].amount.amount").isEqualTo("25.00"));
    }

    @Test
    void rejectsAnAmountWithTooManyDecimalPlaces() {
        MvcTestResult response = publish("acc-" + UUID.randomUUID(), "1.005");

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors.amount")
                .isEqualTo("numeric value out of bounds (<15 digits>.<2 digits> expected)");
    }

    private MvcTestResult publish(String account, String amount) {
        return mvc.post().uri("/simulator/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accountId": "%s", "amount": "%s", "payee": "Corner Shop", "channel": "CARD"}
                        """.formatted(account, amount))
                .exchange();
    }
}
```

The first test goes all the way round: HTTP in, Kafka out, Kafka in, database, HTTP out.

Run everything: right-click `src/test/java` → **Run 'All Tests'**, or:

```bash
./mvnw test
```

The first run is slow, because Docker downloads the Kafka image and Testcontainers' own helper image. In the log you'll see:

```
Pulling docker image: apache/kafka:4.2.2. Please be patient; this may take some time but only needs to be done once.
...
Container apache/kafka:4.2.2 started in PT3.488001844S
...
Ryuk started - will monitor and terminate Testcontainers containers on JVM exit
```

**Ryuk** is a small Testcontainers container that removes your test containers when the test JVM exits, even if it crashes. You never have to clean up after a test run. Later runs skip the download and the container starts in a few seconds.

---

## Your turn

### Exercise 1: prove the key decides the partition

The `PublishedResponse` includes the partition each event went to. Add a test to `SimulatorApiTest` that publishes six events for the same account and checks they all went to the **same** partition.

<details>
<summary>Hint</summary>

You need the number out of the JSON, not just an assertion on it. `com.jayway.jsonpath.JsonPath.read(json, "$.partition")` (JsonPath is on the test classpath) reads it from `result.getResponse().getContentAsString()`, which throws a checked exception, so declare `throws Exception`.

</details>

<details>
<summary>Solution</summary>

In `SimulatorApiTest` (import `com.jayway.jsonpath.JsonPath`):

```java
    @Test
    void sendsEveryEventForOneAccountToTheSamePartition() throws Exception {
        String account = "acc-" + UUID.randomUUID();

        int first = partitionOf(publish(account, "1.00"));
        for (int i = 0; i < 5; i++) {
            assertThat(partitionOf(publish(account, "1.00"))).isEqualTo(first);
        }
    }

    private int partitionOf(MvcTestResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.partition");
    }
```

This is a test of Kafka's behaviour rather than your code, like `MoneyTest`'s notes about `BigDecimal` in the Core ledger tutorial. It documents an assumption the rest of the system relies on: same key, same partition, same order.

</details>

### Exercise 2: refuse a zero payment

A payment of £0.00 (or less) isn't a payment. Make `TransactionEvent` refuse one with an `IllegalArgumentException` whose message contains `must be positive`, and add a test to `TransactionEventTest`.

<details>
<summary>Solution</summary>

At the end of the compact constructor in `TransactionEvent`:

```java
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive, but was " + amount);
        }
```

In `TransactionEventTest` (import `java.util.UUID`):

```java
    @Test
    void refusesAZeroAmount() {
        assertThatThrownBy(() -> new TransactionEvent(UUID.randomUUID(), "acc-1", Money.gbp("0.00"),
                "Coffee Shop", Channel.CARD, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }
```

The `requireNonNull` checks come first, so `amount.isPositive()` can never hit a `null`.

</details>

---

## Checkpoint

Docker must be running. Then:

```bash
./mvnw test
```

```
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
```

(4 in `MoneyTest`, 2 in `TransactionEventTest`, 1 in `TransactionListenerTest` and 3 in `SimulatorApiTest`, if you did both exercises.)

Your project should look like this:

```
fraud-monitoring/
├── compose.yaml
├── pom.xml
├── requests.http
└── src/
    ├── main/
    │   ├── java/com/example/fraud/
    │   │   ├── FraudMonitoringApplication.java
    │   │   ├── config/
    │   │   │   ├── ClockConfig.java
    │   │   │   ├── KafkaConfig.java
    │   │   │   └── Topics.java
    │   │   ├── money/
    │   │   │   └── Money.java
    │   │   ├── simulator/
    │   │   │   └── SimulatorController.java
    │   │   ├── transaction/
    │   │   │   ├── AccountTransaction.java
    │   │   │   ├── AccountTransactionRepository.java
    │   │   │   ├── Channel.java
    │   │   │   ├── TransactionController.java
    │   │   │   ├── TransactionEvent.java
    │   │   │   ├── TransactionListener.java
    │   │   │   └── TransactionMonitoringService.java
    │   │   └── web/
    │   │       └── ApiExceptionHandler.java
    │   └── resources/
    │       ├── application.properties
    │       └── db/migration/
    │           └── V1__create_account_transaction.sql
    └── test/java/com/example/fraud/
        ├── FraudIntegrationTest.java
        ├── TestcontainersConfiguration.java
        ├── money/
        │   └── MoneyTest.java
        ├── simulator/
        │   └── SimulatorApiTest.java
        └── transaction/
            ├── TransactionEventTest.java
            └── TransactionListenerTest.java
```

---

## Common errors

**`DockerNotRunningException: Docker is not running` when starting the app**
Boot's Docker Compose support runs `docker version` before anything else, and it failed. The cause underneath says it plainly: `Cannot connect to the Docker daemon … Is the docker daemon running?` Start Docker and try again.

**The tests fail before any test runs, with Testcontainers saying it can't find a Docker environment**
Same problem, from the test side. Testcontainers needs a running Docker daemon it can reach. Check `docker run --rm hello-world` works from the same terminal (or restart IntelliJ after installing Docker, so it picks up the new environment).

**`No services defined in Docker Compose file …`, then a long pause and `KafkaAdmin : Could not configure topics` with `TimeoutException: Timed out waiting for a node assignment. Call: fetchMetadata`**
Kafka isn't running. Here `compose.yaml` still has Initializr's `services: {}` (you ran the app before step 3), so Boot started nothing, then `KafkaAdmin` spent about 30 seconds failing to reach a broker to create the topic. The app *does* finish starting after that, but the listener has nothing to connect to. You'd see the same `TimeoutException` if `bootstrap-servers` pointed at the wrong port.

**Kafka starts, but port 9092 is already in use**
Something else, often another Kafka, is already on 9092. Stop it, or change both the left-hand side of `ports:` and `KAFKA_ADVERTISED_LISTENERS` and `spring.kafka.bootstrap-servers` to another port. They must all agree, because the broker tells clients which address to use.

**A burst of `Group coordinator localhost:9092 … is unavailable or invalid due to cause: coordinator unavailable … Rediscovery will be attempted.` right after start-up**
Harmless. Docker reports the container as started a moment before Kafka is ready to manage consumer groups. The consumer retries, and a second or two later you see `partitions assigned`.

**`Using H2 2.4.240 which is newer than the version Flyway has been verified with`**
A warning, not an error. The migrations in this tutorial work fine on this H2 version.

**The listener never sees events published before the app first started**
`spring.kafka.consumer.auto-offset-reset` is missing, so the consumer group used Kafka's default, `latest`. It only applies the first time a group reads a topic. If you've already started with the wrong setting, the group has remembered offsets; delete the container (`docker compose down`) to start from scratch.

**`Cannot resolve symbol 'AutoConfigureMockMvc'`**
In Spring Boot 4 it moved to `org.springframework.boot.webmvc.test.autoconfigure`.

**A test passes on its own but fails when the whole suite runs**
It's asserting about data another test created: a fixed account id like `"acc-1"` in an integration test, for example. Every test shares one database and one broker. Use random account ids.

---

## Recap

- **Transaction monitoring** watches every payment. **Fraud** monitoring protects the customer and the bank and wants to act fast; **AML** monitoring is a legal duty under the Money Laundering Regulations 2017 and leads, when there's suspicion, to a SAR.
- **APP fraud** tricks customers into sending money themselves, so monitoring the pattern of payments is one of the few defences.
- **Event-driven design** decouples the fraud system from the systems that make payments. A slow consumer doesn't slow down payments.
- **Kafka:** topics are split into partitions; the **key** decides the partition; order is guaranteed **within a partition only**; a consumer group shares partitions out; offsets record progress.
- **Key events by account** so each account's events arrive in order.
- **Strings on the wire plus a `RecordMessageConverter`** give you typed `@KafkaListener` methods and JSON identical to your REST API.
- **Testcontainers with `@ServiceConnection`** gives every test run a real, throwaway Kafka with no configuration. **Awaitility** tests asynchronous behaviour without `sleep`.

### Interview angle

<details>
<summary>"What's the difference between fraud monitoring and AML transaction monitoring?"</summary>

Fraud monitoring protects the customer and the bank from losses (stolen cards, account takeover, scams) and tries to act before or immediately after the money moves, by blocking, holding or contacting the customer. AML monitoring is a regulatory duty (in the UK, the Money Laundering Regulations 2017 require ongoing monitoring, including scrutiny of transactions) to detect money laundering. It often looks at patterns over longer periods, alerts are investigated by analysts, and if there's suspicion the case goes to the nominated officer, who decides whether to submit a Suspicious Activity Report to the National Crime Agency. Same data, different goals, teams and deadlines.

</details>

<details>
<summary>"How does Kafka guarantee ordering?"</summary>

Only within a partition. Events with the same key always go to the same partition (the default partitioner hashes the key), and a partition is read by one consumer in a group at a time, so events for one key are processed in the order they were written. There's no ordering across partitions. So you choose the key to be the thing whose events must stay in order, here the account id. Changing the number of partitions changes which partition a key maps to, so it's a decision to make early.

</details>

<details>
<summary>"Why would a bank use events instead of calling the fraud service directly?"</summary>

Decoupling and resilience. Producers (card, payments, ledger) don't depend on the fraud system being up or fast; events queue in the broker and the consumer catches up. New consumers (AML, analytics, notifications) can subscribe without changing producers, and events can be replayed. The trade-off is that processing is asynchronous: if you need to block a payment before it goes, you also need a synchronous check on the payment path.

</details>

<details>
<summary>"How do you test a Kafka consumer?"</summary>

Keep the listener thin and unit-test the logic it delegates to. Then test the integration against a real broker, not mocks: Testcontainers starts Kafka in Docker, Spring Boot's `@ServiceConnection` points the app at it, and the test publishes a message and waits for the effect with something like Awaitility rather than `Thread.sleep`. Use unique keys or ids per test so tests sharing the broker can't interfere.

</details>

### Further reading

- [Money Laundering Regulations 2017](https://www.legislation.gov.uk/uksi/2017/692/contents), especially regulation 28 (customer due diligence and ongoing monitoring)
- [Payment Systems Regulator: APP scams](https://www.psr.org.uk/our-work/app-scams/)
- [Apache Kafka documentation](https://kafka.apache.org/documentation/): the "Introduction" and "Design" sections
- [Spring for Apache Kafka reference: `@KafkaListener`](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/listener-annotation.html)
- [Spring Boot reference: Testcontainers](https://docs.spring.io/spring-boot/4.1.2/reference/testing/testcontainers.html) and [Docker Compose support](https://docs.spring.io/spring-boot/4.1.2/reference/features/dev-services.html)
- [Testcontainers: Kafka module](https://java.testcontainers.org/modules/kafka/)

**Next:** [02: Rules and alerts](02-rules-and-alerts.md). The history starts earning its keep: a small rule engine that spots unusual payments and raises alerts.
