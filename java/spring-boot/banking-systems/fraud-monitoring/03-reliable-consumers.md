# 03: Reliable Consumers

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- Why Kafka delivers every event **at least once**, and what a duplicate does to a fraud system
- The **idempotent consumer** pattern: remember which events you've processed, in the same database transaction as the work
- A Spring Data trap: why `save()` on an entity with an id you assigned yourself does a `SELECT` first, and how **`Persistable`** fixes it
- **Poison messages**: events that can never be processed, and what Spring for Apache Kafka does with them by default (you'll lose them)
- **`DefaultErrorHandler`**: retries with back-off, and which failures are worth retrying
- **Dead-letter topics**: parking failed events, with headers that say why, so nothing is silently lost

By the end, sending the same payment twice raises its alerts once, and a broken event ends up on a dead-letter topic with its error attached instead of vanishing into a log file.

---

## Concepts

### How it works in a real bank

A monitoring system that occasionally loses a payment, or counts one twice, is worse than useless: it gives the bank false confidence. Banks run these systems for years, through deployments, crashes, network blips and broker failovers. Every one of those can cause an event to be delivered again or to fail. So the reliability work isn't polish. It's most of the engineering.

Duplicates in particular are expensive here:

- **The history is wrong.** A payment counted twice doubles towards the velocity rule and drags the average around. Customers get **false alerts** (and possibly a blocked card) for spending they only did once.
- **Alerts are duplicated.** Analysts investigate the same thing twice, and the customer may get two "did you make this payment?" texts.
- **The audit trail is wrong.** Regulators and auditors expect the bank to show exactly which transactions were monitored and what was raised.

And losing an event is worse: a payment that was never monitored is a gap in a control the bank is legally required to operate.

### Why "at least once"?

Recall how a consumer works: it **polls** a batch of records, processes them, then **commits its offset** ("I've done everything up to offset 42"). Between "processed" and "committed" there's always a gap. If the app crashes in that gap, or the consumer group **rebalances** (a consumer joins or leaves and partitions move between them) before the commit, the next consumer to own that partition starts from the last committed offset and processes the same records again.

```
poll [40, 41, 42] → process 40 ✔ → process 41 ✔ → process 42 ✔ → 💥 crash before commit
restart → last committed offset was 40 → poll [40, 41, 42] again
```

You could commit *before* processing instead. Then a crash loses events: **at most once**. For monitoring, losing is unacceptable, so you choose at-least-once and make processing safe to repeat. Duplicates can also come from the other end: a producer that times out waiting for the broker's acknowledgement and retries may publish the same event twice.

> **Sidebar: what about Kafka's "exactly once"?** Kafka has transactions, and Spring for Apache Kafka supports them. They give exactly-once processing for **read from Kafka → write to Kafka** pipelines. Your consumer writes to a **database**, which isn't part of the Kafka transaction. You still need an idempotent consumer. That's true of almost every real banking consumer.

### The idempotent consumer

[Idempotency](../glossary.md#idempotency) means doing something twice has the same effect as doing it once. The pattern for a consumer ([microservices.io: idempotent consumer](https://microservices.io/patterns/communication-style/idempotent-consumer.html)) is:

1. Every event carries a unique id (your `eventId`).
2. Keep a `processed_event` table with that id as its **primary key**.
3. In the **same database transaction** as the real work: if the id is already there, skip; otherwise do the work and insert the id.

Because the marker and the work commit together, there's no moment where one exists without the other. If the transaction rolls back, neither exists, and the redelivered event is processed properly. The primary key is the backstop: two inserts of the same id can never both succeed.

### Poison messages, retries and dead letters

Some events fail. The question is *why*:

| Kind of failure | Example | Will retrying help? |
|---|---|---|
| **Transient** | The database was briefly unreachable; a lock timed out | Yes, after a short wait |
| **Permanent** | The message isn't valid JSON; it's in a currency you don't monitor; a bug in your code | No. It will fail the same way forever |

A message that fails permanently is a **poison message**. Retrying it forever blocks everything behind it on the same partition (remember, a partition is processed in order). Skipping it silently loses a payment. The answer is to **retry a few times, then move it aside**: publish it to a **dead-letter topic (DLT)** with headers recording what went wrong, and carry on with the next record. Someone (in a bank, an operations or production-support team) investigates the DLT, fixes the cause, and replays the events if needed.

---

## Steps

### 1. Watch a duplicate do damage

Write the test first. Publish the same event twice, then a small marker payment, and wait for the marker: by the time it's processed, both copies of the first event have been too, because they're all on the same partition, in order.

`src/test/java/com/example/fraud/transaction/DuplicateDeliveryTest.java`:

```java
package com.example.fraud.transaction;

import com.example.fraud.FraudIntegrationTest;
import com.example.fraud.alert.Alert;
import com.example.fraud.alert.AlertRepository;
import com.example.fraud.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.UUID;

import static com.example.fraud.TestTransactions.payment;
import static com.example.fraud.TestTransactions.publish;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@FraudIntegrationTest
class DuplicateDeliveryTest {

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired TransactionMonitoringService monitoring;
    @Autowired AlertRepository alerts;

    @Test
    void theSameEventDeliveredTwiceIsOnlyProcessedOnce() {
        String account = "acc-" + UUID.randomUUID();
        TransactionEvent event = payment("750.00").account(account).build();

        publish(kafka, event);
        publish(kafka, event); // a redelivery: same event, same id
        publish(kafka, payment("5.00").account(account).build()); // a marker we can wait for

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(monitoring.history(account)).extracting(AccountTransaction::getAmount)
                        .contains(Money.gbp("5.00")));

        assertThat(monitoring.history(account)).hasSize(2);
        assertThat(alerts.findByAccountIdOrderByIdAsc(account))
                .extracting(Alert::getRuleCode)
                .containsExactly("LARGE_AMOUNT");
    }
}
```

Publishing the same event twice is exactly what a redelivery looks like to your listener: same bytes, same `eventId`.

Run it:

```
Expected size: 2 but was: 3 in:
[com.example.fraud.transaction.AccountTransaction@43fbd29,
    com.example.fraud.transaction.AccountTransaction@6884cfca,
    com.example.fraud.transaction.AccountTransaction@1d6ddb48]
```

The £750 payment is in the history twice. Interestingly, the alert assertion would have passed: the duplicate didn't raise a second `LARGE_AMOUNT` alert, because by then the account's history contained the first copy, and £750 isn't more than five times £750. That's luck, not correctness. The polluted history is now in every future rule decision for this account: two payments towards the velocity count, and an average dragged upwards that hides the next genuinely unusual payment.

### 2. Remember processed events

`src/main/resources/db/migration/V3__create_processed_event.sql`:

```sql
CREATE TABLE processed_event (
    event_id     UUID                     PRIMARY KEY,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
```

The `PRIMARY KEY` is the guarantee: the database will never hold two rows for one event.

`transaction/ProcessedEvent.java`, first version:

```java
package com.example.fraud.transaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** "We have already handled this event." One row per event id, ever. */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
        // for JPA
    }

    public ProcessedEvent(UUID eventId, Instant processedAt) {
        this.eventId = eventId;
        this.processedAt = processedAt;
    }

    public UUID getEventId() {
        return eventId;
    }
}
```

There's no `@GeneratedValue`: the id is the event's own id, assigned by the producer.

`transaction/ProcessedEventRepository.java`:

```java
package com.example.fraud.transaction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
}
```

Now `TransactionMonitoringService`. Add the repository and a logger:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
...

@Service
public class TransactionMonitoringService {

    private static final Logger log = LoggerFactory.getLogger(TransactionMonitoringService.class);

    private final AccountTransactionRepository transactions;
    private final AlertRepository alerts;
    private final ProcessedEventRepository processedEvents;
    private final RuleEngine ruleEngine;
    private final RulesProperties rules;
    private final Clock clock;

    public TransactionMonitoringService(AccountTransactionRepository transactions, AlertRepository alerts,
                                        ProcessedEventRepository processedEvents, RuleEngine ruleEngine,
                                        RulesProperties rules, Clock clock) {
        this.transactions = transactions;
        this.alerts = alerts;
        this.processedEvents = processedEvents;
        this.ruleEngine = ruleEngine;
        this.rules = rules;
        this.clock = clock;
    }
```

and change `process`:

```java
    /** Checks one transaction against every rule, records it, and returns any new alerts. */
    @Transactional
    public List<Alert> process(TransactionEvent event) {
        if (processedEvents.existsById(event.eventId())) {
            log.info("Skipping {}: already processed", event.eventId());
            return List.of();
        }
        AccountHistory history = historyBefore(event);
        List<RuleHit> hits = ruleEngine.evaluate(event, history);

        Instant now = Instant.now(clock);
        transactions.save(AccountTransaction.record(event, now));
        processedEvents.save(new ProcessedEvent(event.eventId(), now));
        return alerts.saveAll(hits.stream().map(hit -> Alert.raise(event, hit, now)).toList());
    }
```

The check, the work and the marker all happen inside one `@Transactional` method. Returning an empty list for a duplicate means the listener publishes nothing for it, either.

Run `DuplicateDeliveryTest`: green. In the log:

```
Skipping 5b41af65-ee7c-4784-8597-db07fcb9cd49: already processed
```

### 3. The `save()` trap

Look at the SQL. Add `logging.level.org.hibernate.SQL=debug` to `application.properties` temporarily, and run the test again. For the first delivery:

```
select count(*) from processed_event pe1_0 where pe1_0.event_id=?
select pe1_0.event_id,pe1_0.processed_at from processed_event pe1_0 where pe1_0.event_id=?
insert into processed_event (processed_at,event_id) values (?,?)
```

The first `select count(*)` is your `existsById`. But where did the second `select` come from?

From `save()`. Spring Data's `save()` has to decide between `persist` (insert a new row) and `merge` (update an existing one). By default it decides by looking at the id: **null means new**. Your id is never null, because you assigned it, so Spring Data assumes the entity *might* already exist and calls `merge`, which loads the row first to see. It's a wasted query on every event, and worse, it's the wrong semantics: if the row *did* exist (another consumer committed it a moment ago), `merge` would quietly **update** it instead of failing. Your primary key backstop would never fire.

Fix it by telling Spring Data the truth. Implement `Persistable` in `ProcessedEvent`:

```java
import org.springframework.data.domain.Persistable;
...

public class ProcessedEvent implements Persistable<UUID> {

    ...

    @Override
    public UUID getId() {
        return eventId;
    }

    /** Always new: save() must INSERT, never look for an existing row to update. */
    @Override
    public boolean isNew() {
        return true;
    }
}
```

(Replace `getEventId()` with `getId()`; nothing else uses it.) Run again:

```
select count(*) from processed_event pe1_0 where pe1_0.event_id=?
insert into processed_event (processed_at,event_id) values (?,?)
```

One query fewer, and now a second insert of the same id will hit the primary key and fail loudly, which is exactly what you want from a backstop. Guide 04 relies on it. Remove the SQL logging line.

> **Sidebar: a separate table, or a unique column?** You could put a unique constraint on `account_transaction.event_id` instead, and let the history row double as the marker. That works for this one listener. A separate `processed_event` table is the general pattern: it works for any consumer, including ones that don't store a row per event, and it's easy to add an expiry (delete markers older than the topic's retention period, since no event that old can be redelivered).

### 4. What happens to a broken event today?

Now the other kind of reliability problem. Write a test that publishes something that isn't JSON at all, followed by a good payment for the same account. `src/test/java/com/example/fraud/transaction/PoisonMessageTest.java`, first version:

```java
package com.example.fraud.transaction;

import com.example.fraud.FraudIntegrationTest;
import com.example.fraud.config.Topics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.UUID;

import static com.example.fraud.TestTransactions.payment;
import static com.example.fraud.TestTransactions.publish;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@FraudIntegrationTest
class PoisonMessageTest {

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired TransactionMonitoringService monitoring;

    @Test
    void aBadMessageDoesNotBlockTheOnesBehindIt() {
        String account = "acc-" + UUID.randomUUID();

        kafka.send(Topics.TRANSACTIONS, account, "this is not JSON").join();
        publish(kafka, payment("10.00").account(account).build());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(monitoring.history(account)).hasSize(1));
    }
}
```

It passes. The good payment behind the bad one was processed. But read the log:

```
ERROR o.s.kafka.listener.DefaultErrorHandler : Backoff FixedBackOffExecution[interval=0, currentAttempts=1, maxAttempts=0] exhausted for transactions-1@0

org.springframework.kafka.listener.ListenerExecutionFailedException: Listener failed
...
Caused by: org.springframework.kafka.support.converter.ConversionException: Failed to convert from JSON
Caused by: tools.jackson.core.exc.StreamReadException: Unrecognized token 'this': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')
```

With no error handling configured, Spring for Apache Kafka uses a `DefaultErrorHandler` with default settings. It knows a `ConversionException` can never succeed on retry, so it gave up after one attempt, **logged it, and skipped it**. The message is gone. If that had been a real payment with a malformed field, it would never have been monitored, and the only trace would be a log line.

Now try a failure the defaults *don't* recognise as permanent. A payment in euros is valid JSON and converts fine, but the rules compare it with GBP thresholds and `Money` throws `Currency mismatch: EUR and GBP`. Publish one (with the same `kafka.send(…)` style, changing `"currency": "GBP"` to `"EUR"` in the JSON from guide 01's listener test) and the log shows the listener being called again and again:

```
Seeking to offset 0 for partition transactions-0
Seeking to offset 0 for partition transactions-0
...
ERROR o.s.kafka.listener.DefaultErrorHandler : Backoff FixedBackOffExecution[interval=0, currentAttempts=10, maxAttempts=9] exhausted for transactions-0@0
```

Ten attempts with no pause between them (the default `FixedBackOff(0, 9)`: no interval, nine retries), each one **seeking back** to the failed record so the partition can't move on. Then it's logged and skipped, and lost, just like the first one. Retrying a bug ten times in a row didn't help anyone, and while it was happening, every other account on that partition was waiting.

You want three things the defaults don't give you:

1. **Don't retry what can never succeed.**
2. **Wait a little between retries** of what might succeed.
3. **Never lose anything.** Park it somewhere visible.

### 5. Say what can't be retried

A non-GBP payment isn't a bug in the event or in your code: it's an event this system was never designed to handle. (Remember, the series is GBP only.) Make that explicit, as an exception the error handler can recognise. `transaction/UnsupportedTransactionException.java`:

```java
package com.example.fraud.transaction;

/** A well-formed event this system can never process, however many times it tries. */
public class UnsupportedTransactionException extends RuntimeException {

    public UnsupportedTransactionException(String message) {
        super(message);
    }
}
```

In `TransactionMonitoringService.process`, straight after the duplicate check (import `com.example.fraud.money.Money`):

```java
        if (!event.amount().currency().equals(Money.GBP)) {
            throw new UnsupportedTransactionException("Only GBP is monitored, but %s was %s"
                    .formatted(event.eventId(), event.amount().currency()));
        }
```

Checking *after* the duplicate check means a redelivered non-GBP event is still skipped quietly if it was somehow processed. Checking *before* the rules means `Money` never sees two currencies.

### 6. Retries, back-off and a dead-letter topic

Add the topic name to `config/Topics.java`:

```java
    /** Where Spring Kafka puts transaction events that could not be processed. */
    public static final String TRANSACTIONS_DLT = "transactions-dlt";
```

Then replace `config/KafkaConfig.java`:

```java
package com.example.fraud.config;

import com.example.fraud.transaction.UnsupportedTransactionException;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJacksonJsonMessageConverter;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class KafkaConfig {

    /** Created on start-up if it doesn't exist yet. */
    @Bean
    NewTopic transactionsTopic() {
        return TopicBuilder.name(Topics.TRANSACTIONS).partitions(3).replicas(1).build();
    }

    /** Same partition count as the main topic: a failed record goes to the same partition number. */
    @Bean
    NewTopic transactionsDeadLetterTopic() {
        return TopicBuilder.name(Topics.TRANSACTIONS_DLT).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic alertsTopic() {
        return TopicBuilder.name(Topics.ALERTS).partitions(3).replicas(1).build();
    }

    /** Messages travel as JSON strings, converted with the same JsonMapper as the REST API. */
    @Bean
    RecordMessageConverter messageConverter(JsonMapper jsonMapper) {
        return new StringJacksonJsonMessageConverter(jsonMapper);
    }

    /**
     * Retry a failed record twice, a second apart. If it still fails, or the failure
     * can never succeed, publish it to the "-dlt" topic and move on.
     */
    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafka) {
        var recoverer = new DeadLetterPublishingRecoverer(kafka);
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2));
        handler.addNotRetryableExceptions(UnsupportedTransactionException.class);
        return handler;
    }
}
```

Spring Boot sees a `CommonErrorHandler` bean (which `DefaultErrorHandler` is) and installs it in the listener container factory. Reading the handler from the inside out:

- **`DeadLetterPublishingRecoverer`** is the "recoverer": what to do once retries are over. It publishes the failed record, unchanged, to a topic named after the original plus **`-dlt`**, on the **same partition number**. That's why the DLT has three partitions too. It uses your existing `KafkaTemplate`. Because the failed record's value is the original `String` and the template sends `String`s, the dead letter holds exactly the bytes that failed.
- **`FixedBackOff(1000L, 2)`**: wait one second, retry, up to two retries, so three attempts in total. For a monitoring consumer, a short back-off rides out a blip without holding the partition for long. `ExponentialBackOff` is the other common choice.
- **`addNotRetryableExceptions(UnsupportedTransactionException.class)`** adds to the built-in list of exceptions that skip straight to the recoverer. The built-in list already includes conversion and deserialisation failures, which is why the bad JSON wasn't retried. The handler looks through the cause chain, so it finds your exception even though the listener container wraps it in a `ListenerExecutionFailedException`.

> **In older codebases:** up to Spring for Apache Kafka 3.2 (Spring Boot 3.3 and earlier), the default dead-letter topic name was `<topic>.DLT`, with a dot. Version 3.3 changed the default suffix to `-dlt`. If you're working on an older service, look for `.DLT` topics, and watch out for the name changing under you during an upgrade. You may also see `SeekToCurrentErrorHandler`, which `DefaultErrorHandler` replaced in Spring for Apache Kafka 2.8.

> **Sidebar: non-blocking retries.** While `DefaultErrorHandler` waits between retries, the whole partition waits. For long back-offs (minutes) Spring for Apache Kafka offers `@RetryableTopic`, which moves the failed record to separate retry topics so the main partition keeps flowing. The cost is that the retried event is processed **out of order** relative to the account's later events. For a per-account history, blocking retries with a short back-off are the safer default.

### 7. Prove nothing is lost

Tests need to read the DLT, so add a small helper next to `TestTransactions`. `src/test/java/com/example/fraud/DeadLetters.java`:

```java
package com.example.fraud;

import com.example.fraud.config.Topics;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Reads the dead-letter topic in tests. */
public final class DeadLetters {

    private DeadLetters() {
    }

    /** Waits for the dead letter with this key and returns it. */
    public static ConsumerRecord<String, String> awaitDeadLetter(ConsumerFactory<String, String> factory, String key) {
        try (Consumer<String, String> consumer = factory.createConsumer("dlt-test-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(Topics.TRANSACTIONS_DLT));
            AtomicReference<ConsumerRecord<String, String>> found = new AtomicReference<>();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                KafkaTestUtils.getRecords(consumer, Duration.ofMillis(500)).forEach(r -> {
                    if (key.equals(r.key())) {
                        found.set(r);
                    }
                });
                assertThat(found.get()).isNotNull();
            });
            return found.get();
        }
    }

    public static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value());
    }
}
```

It's the same "fresh consumer group, poll until our key turns up" technique as `AlertPublishingTest`. The `AtomicReference` is there because a lambda can only assign to effectively-final variables.

Now replace `PoisonMessageTest` with a version that checks the dead letters:

```java
package com.example.fraud.transaction;

import com.example.fraud.FraudIntegrationTest;
import com.example.fraud.config.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;

import java.time.Duration;
import java.util.UUID;

import static com.example.fraud.DeadLetters.awaitDeadLetter;
import static com.example.fraud.DeadLetters.header;
import static com.example.fraud.TestTransactions.payment;
import static com.example.fraud.TestTransactions.publish;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@FraudIntegrationTest
class PoisonMessageTest {

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ConsumerFactory<String, String> consumerFactory;
    @Autowired TransactionMonitoringService monitoring;

    @Test
    void aMessageThatIsNotJsonGoesToTheDeadLetterTopic() {
        String account = "acc-" + UUID.randomUUID();

        kafka.send(Topics.TRANSACTIONS, account, "this is not JSON").join();
        publish(kafka, payment("10.00").account(account).build());

        ConsumerRecord<String, String> dead = awaitDeadLetter(consumerFactory, account);
        assertThat(dead.value()).isEqualTo("this is not JSON");
        assertThat(header(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(Topics.TRANSACTIONS);
        assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .isEqualTo("org.springframework.kafka.support.converter.ConversionException");

        // ...and the good payment behind it on the same partition was still processed
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(monitoring.history(account)).hasSize(1));
    }

    @Test
    void aNonGbpPaymentGoesStraightToTheDeadLetterTopic() {
        String account = "acc-" + UUID.randomUUID();
        kafka.send(Topics.TRANSACTIONS, account, """
                {"eventId": "%s", "accountId": "%s", "amount": {"amount": "10.00", "currency": "EUR"},
                 "payee": "Cafe de Paris", "channel": "CARD", "occurredAt": "2026-11-02T12:00:00Z"}
                """.formatted(UUID.randomUUID(), account)).join();

        ConsumerRecord<String, String> dead = awaitDeadLetter(consumerFactory, account);
        assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .isEqualTo(UnsupportedTransactionException.class.getName());
        assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).contains("Only GBP is monitored");
        assertThat(monitoring.history(account)).isEmpty();
    }
}
```

- **The dead letter's value is the original, untouched**: `"this is not JSON"`. Whoever investigates sees exactly what arrived.
- **Headers record the why and where.** `KafkaHeaders` has constants for each one Spring adds: `DLT_ORIGINAL_TOPIC`, `DLT_ORIGINAL_PARTITION`, `DLT_ORIGINAL_OFFSET`, `DLT_EXCEPTION_FQCN`, `DLT_EXCEPTION_CAUSE_FQCN`, `DLT_EXCEPTION_MESSAGE`, `DLT_EXCEPTION_STACKTRACE` and more.
- **The second test proves the classification.** Count the `Received … for account` log lines for the EUR event: there's exactly one. No retries, no waiting, straight to the DLT.

Run the whole suite. It takes a little longer than before only if something is retried, and nothing in the happy path is.

### 8. Look at a dead letter

Restart the app. Spring creates `transactions-dlt` on start-up. Then publish a broken message straight onto the topic with Kafka's console producer, keyed by `acc-1001` (the `|` separates key from value):

```bash
echo 'acc-1001|this is not JSON' | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic transactions \
  --reader-property parse.key=true --reader-property 'key.separator=|'
```

And read the DLT with headers:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic transactions-dlt --from-beginning \
  --formatter-property print.key=true --formatter-property print.headers=true
```

The output is one (very long) line per record. Trimmed:

```
kafka_dlt-exception-fqcn:org.springframework.kafka.listener.ListenerExecutionFailedException,
kafka_dlt-exception-cause-fqcn:org.springframework.kafka.support.converter.ConversionException,
kafka_dlt-exception-message:Listener failed; Failed to convert from JSON,
kafka_dlt-exception-stacktrace:org.springframework.kafka.listener.ListenerExecutionFailedException: Listener failed
	at org.springframework.kafka.listener.KafkaMessageListenerContainer$ListenerConsumer.decorateException(...)
	...
,kafka_dlt-original-topic:transactions,kafka_dlt-original-partition:…,kafka_dlt-original-offset:…,
kafka_dlt-original-timestamp:…,kafka_dlt-original-timestamp-type:CreateTime,
kafka_dlt-original-consumer-group:fraud-monitoring	acc-1001	this is not JSON
```

The original partition, offset and timestamp headers are binary numbers, so the console prints them as unreadable characters. Spring's `DeadLetterPublishingRecoverer` and your own tooling read them as integers.

> **Sidebar: running a DLT.** A dead-letter topic only works if somebody owns it. Banks alert on DLT growth (any message there means a payment wasn't monitored), triage each one, fix the producer or the consumer, and **replay** the original events back onto the main topic. Because your consumer is idempotent, replaying an event that did get processed after all is harmless. That's the two halves of this guide working together.

---

## Your turn

### Exercise 1: an idempotency test without Kafka

`DuplicateDeliveryTest` goes through Kafka, which is the realistic path but slow and indirect. Write `IdempotentProcessingTest` that calls `TransactionMonitoringService.process` directly, twice, with the same £750 event for a fresh account, and checks the first call returns one alert, the second returns none, and the history holds one transaction.

<details>
<summary>Solution</summary>

`src/test/java/com/example/fraud/transaction/IdempotentProcessingTest.java`:

```java
package com.example.fraud.transaction;

import com.example.fraud.FraudIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static com.example.fraud.TestTransactions.payment;
import static org.assertj.core.api.Assertions.assertThat;

/** Calls the service directly: no Kafka, no waiting. */
@FraudIntegrationTest
class IdempotentProcessingTest {

    @Autowired TransactionMonitoringService monitoring;

    @Test
    void processingTheSameEventTwiceRaisesNoNewAlerts() {
        String account = "acc-" + UUID.randomUUID();
        TransactionEvent event = payment("750.00").account(account).build();

        assertThat(monitoring.process(event)).hasSize(1);
        assertThat(monitoring.process(event)).isEmpty();
        assertThat(monitoring.history(account)).hasSize(1);
    }
}
```

It still uses `@FraudIntegrationTest` (it needs the database and, so it shares the cached context, the same configuration as everything else), but it doesn't wait on Kafka at all. Keep both tests: this one pins down the service's behaviour precisely; the Kafka one proves the wiring.

</details>

### Exercise 2: an event with a missing field

Guide 01 made `TransactionEvent`'s compact constructor refuse a missing `eventId`. Add a test to `PoisonMessageTest` that publishes valid JSON with no `eventId` and checks it reaches the DLT. Before you write the assertion, predict what `DLT_EXCEPTION_MESSAGE` will contain. Where will you find `eventId is required`?

<details>
<summary>Solution</summary>

```java
    @Test
    void anEventWithoutAnIdGoesToTheDeadLetterTopic() {
        String account = "acc-" + UUID.randomUUID();
        kafka.send(Topics.TRANSACTIONS, account, """
                {"accountId": "%s", "amount": {"amount": "10.00", "currency": "GBP"},
                 "payee": "Corner Shop", "channel": "CARD", "occurredAt": "2026-11-02T12:00:00Z"}
                """.formatted(account)).join();

        ConsumerRecord<String, String> dead = awaitDeadLetter(consumerFactory, account);
        assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).isEqualTo("Listener failed; Failed to convert from JSON");
        assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_STACKTRACE)).contains("eventId is required");
    }
```

If you guessed the message header would say `eventId is required`, the test told you otherwise:

```
Expecting actual:
  "Listener failed; Failed to convert from JSON"
to contain:
  "eventId is required"
```

The message header holds the **top-level** exception's message. Jackson called the record's constructor, the constructor threw, and that became a conversion failure wrapped in a listener failure. The real reason is several causes down, so it's only in the **stack trace** header. Remember this when you're the one triaging a DLT: read the stack trace header, not just the message.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 39, Failures: 0, Errors: 0, Skipped: 0
```

That's 3 new tests from the steps (`DuplicateDeliveryTest` and two in `PoisonMessageTest`) and 2 from the exercises, on top of guide 02's 34.

New files since guide 02:

```
src/main/java/com/example/fraud/transaction/
├── ProcessedEvent.java
├── ProcessedEventRepository.java
└── UnsupportedTransactionException.java
src/main/resources/db/migration/
└── V3__create_processed_event.sql
src/test/java/com/example/fraud/
├── DeadLetters.java
└── transaction/
    ├── DuplicateDeliveryTest.java
    ├── IdempotentProcessingTest.java
    └── PoisonMessageTest.java
```

Modified: `Topics.java`, `KafkaConfig.java` (DLT topic, error handler), `TransactionMonitoringService.java` (duplicate check, currency check), `requests.http`.

---

## Common errors

**`Backoff FixedBackOffExecution[interval=0, currentAttempts=10, maxAttempts=9] exhausted for transactions-0@0`**
You're running with Spring's default error handler: ten immediate attempts, then the record is logged and **skipped**. Your `DefaultErrorHandler` bean isn't being picked up. Check it's a `@Bean` in a `@Configuration` class Spring scans.

**The non-GBP test is slow and the log shows three attempts for the EUR event**
`UnsupportedTransactionException` isn't registered with `addNotRetryableExceptions`, so it's retried with the back-off before going to the DLT. The test still passes, just a couple of seconds later.

**`DuplicateDeliveryTest` still fails with `Expected size: 2 but was: 3`**
The duplicate check isn't inside the same transaction as the save, or `process` isn't `@Transactional`. Also check that the listener calls `monitoring.process(...)` on the injected bean.

**An extra `select … from processed_event` before every insert**
`ProcessedEvent` doesn't implement `Persistable`, so `save()` is doing a `merge`. See step 3.

**`awaitDeadLetter` times out**
The dead letter went to a topic you're not reading. Current versions use the suffix `-dlt`, not the older `.DLT`. Check `Topics.TRANSACTIONS_DLT` matches.

---

## Recap

- Kafka gives you **at-least-once** delivery: a crash or rebalance between processing and committing the offset means redelivery. Producers can duplicate too.
- An **idempotent consumer** records each event id in a `processed_event` table, **in the same database transaction** as the work, with a **primary key** as the backstop.
- Spring Data's `save()` **merges** entities with assigned ids. Implement **`Persistable`** with `isNew() == true` for insert-only entities.
- Without configuration, Spring's default error handler retries ten times without pausing and then **logs and skips** the record: silently losing it.
- Classify failures: **transient** (retry with back-off) or **permanent** (don't retry). `addNotRetryableExceptions` adds your own permanent failures.
- A **`DeadLetterPublishingRecoverer`** parks failed records on a **`-dlt`** topic with headers saying where they came from and why they failed. Somebody must own and replay the DLT, and idempotency makes replay safe.

### Interview angle

<details>
<summary>"How do you handle duplicate messages from Kafka?"</summary>

Accept that delivery is at-least-once and make the consumer idempotent. Each event carries a unique id. In the same database transaction as the processing, check a processed-events table and insert the id, with a primary key or unique constraint so two inserts can't both succeed. A duplicate is then skipped, and a crash before commit rolls back both the work and the marker, so the redelivery is processed normally. Mention that Kafka's exactly-once transactions don't cover external databases, and that markers can be expired after the topic's retention period.

</details>

<details>
<summary>"What's a poison message, and what do you do about it?"</summary>

A message that will fail every time it's processed, such as invalid JSON, a missing field, unsupported data, or a bug triggered by its contents. Because a partition is processed in order, retrying it forever blocks everything behind it, and skipping it silently loses data. So classify exceptions: retry transient ones a few times with back-off, send permanent ones straight to a dead-letter topic with headers describing the failure, and carry on. Then monitor, triage and replay the DLT.

</details>

<details>
<summary>"Why might you not want to retry an exception?"</summary>

Because it can't succeed. Validation errors, conversion errors and unsupported data will fail identically on every attempt, and each retry holds up the partition. Retrying is for failures caused by the environment, like a database failover or a lock timeout, that may clear up on their own. Making the distinction explicit, with exception types, is better than one retry policy for everything.

</details>

<details>
<summary>"How would you safely reprocess events after fixing a bug?"</summary>

Replay them, either from the dead-letter topic or by resetting the consumer group's offsets to an earlier point. That's only safe if the consumer is idempotent, so events that were processed successfully the first time are skipped rather than counted twice. Also check that the processing is deterministic and uses event time, not processing time, so a replay gives the same answers it would have given originally.

</details>

### Further reading

- [Spring for Apache Kafka reference: handling exceptions](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html): `DefaultErrorHandler`, back-off, and publishing dead-letter records
- [microservices.io: idempotent consumer](https://microservices.io/patterns/communication-style/idempotent-consumer.html)
- [Apache Kafka documentation: message delivery semantics](https://kafka.apache.org/documentation/#semantics)
- [Spring Data JPA reference: entity state detection](https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html), which explains `save()`, `merge` and `Persistable`

**Next:** [04: One account at a time](04-one-account-at-a-time.md). What happens when two consumers process the same account at the same moment, and why the velocity rule quietly stops working.
