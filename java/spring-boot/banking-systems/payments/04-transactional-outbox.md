# 04: The Transactional Outbox

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- Why "save the payment, then call the scheme" can **lose** a payment or send one the bank has no record of
- The **dual-write problem**: you can't update a database and call another system atomically
- The **transactional outbox**: write the payment and the message in one transaction, and let a background **relay** send it
- **At-least-once delivery**, and why it makes the receiver's job to be an **idempotent consumer**
- A `@Scheduled` relay, with one short transaction per message
- What happens when **two copies of your application** run the relay at the same time, seen in a failing concurrency test
- **`SELECT … FOR UPDATE SKIP LOCKED`**: letting several workers share a queue table without blocking or duplicating

By the end, a validated payment is sent to a simulated Faster Payments scheme exactly when, and only when, the bank has committed it, even if the scheme is down, a reply is lost, or two relays run at once.

---

## Concepts

### How it works in a real bank

Once a payment is validated and paid for, the bank's payments platform has to hand it to the scheme. In a real bank that's a message across a dedicated, secure network link to the scheme's central infrastructure, usually through a "scheme gateway" component. It's another system, on another machine, with its own failures.

Here's the obvious code:

```java
@Transactional
public Payment create(...) {
    // ... save the payment, debit the customer ...
    scheme.submit(message);     // call the other system
    return payment;
}                                // commit
```

Look at everything that can go wrong:

| What fails | Result |
|---|---|
| The scheme call succeeds, then **our commit fails** (a lost database connection, a constraint violation, a crash) | The scheme pays Bob. The bank has **no record** of the payment and **never debited** Alice. Real money has left the bank with nothing in the books. |
| The scheme call **times out** | We can't know if the scheme got it. If we roll back and it did, that's the row above. If we keep the payment and it didn't, it's never sent. |
| Move the call **after** the commit: the commit succeeds, then the process **crashes** before the call | Alice has been debited, the payment says `VALIDATED`, and nothing will ever send it. It's stuck. |

This is the **dual-write problem**. A database transaction can make many changes to *one* database atomic. It can't include a call to another system. Whichever order you choose, there's a moment where one write has happened and the other hasn't.

(Distributed transactions, "two-phase commit" or XA, do exist. They need every participant to support them, they're slow and operationally fragile, and a payment scheme isn't going to join your database transaction. Modern systems avoid them.)

### The transactional outbox

The fix is to stop doing two writes. Do **one**, to your own database:

1. In the **same transaction** that validates the payment and debits the customer, insert a row into an `outbox_event` table: "send this message to the scheme".
2. A separate background process, the **relay**, reads unsent rows, sends them, and marks them sent.

Now the payment and the intention to send it commit together or not at all. There's no phantom payment: if the transaction rolls back, so does the outbox row. And there's no lost payment: if the process crashes after the commit, the row is still there, and the relay sends it when it starts again.

### At-least-once, so the receiver must be idempotent

The relay itself still does two things: send the message, then mark the row sent. If it crashes between the two, or the scheme's reply is lost, the row is still unsent, and it's sent **again**.

So the outbox guarantees **at-least-once** delivery, not exactly-once. That's fine **as long as the receiver is an idempotent consumer**: every message carries a unique id, and the receiver remembers which ids it has acted on and ignores repeats. That's [idempotency](../glossary.md#idempotency) again, this time between systems. In this tutorial each message gets a UUID `messageId` when the outbox row is written, so every resend of the same payment carries the **same** id.

> **Simplification:** the fake scheme you'll build deduplicates on `messageId`. Real schemes and banks have their own duplicate detection on identifiers in the message, defined in their rulebooks. The general rule holds everywhere: **give every message a stable unique id, and make every receiver check it.** You'll build the bank's own idempotent consumer in guide 05.

### Several relays

In production you run more than one copy of the application, for resilience. Each copy has a relay. If two relays read the same unsent row at the same moment, both send it. The receiver deduplicates, so nobody is paid twice, but you've doubled the traffic and made the duplicate-handling path the normal path.

The classic fix is to let each relay **lock** the rows it takes, and have other relays **skip** locked rows instead of waiting for them:

```sql
SELECT … FROM outbox_event WHERE published_at IS NULL ORDER BY id
FETCH FIRST 1 ROWS ONLY
FOR UPDATE SKIP LOCKED
```

Two relays running that at the same moment get **different** rows. PostgreSQL, Oracle and MySQL 8 support `SKIP LOCKED`, and so does H2 (you'll see the SQL Hibernate generates for it).

> **Sidebar: change data capture.** Another way to relay an outbox is not to poll at all: a tool such as Debezium reads the database's transaction log and publishes each new outbox row to a message broker like Kafka. It removes the polling delay and load, at the cost of running more infrastructure. The outbox table and the at-least-once guarantee are the same either way.

---

## Steps

### 1. A simulated scheme

First, something to send payments to. Create the package `scheme`.

The message, loosely modelled on an ISO 20022 `pacs.008`. `scheme/CreditTransferMessage.java`:

```java
package com.example.payments.scheme;

import com.example.payments.money.Money;
import com.example.payments.payment.Creditor;

import java.time.Instant;

/**
 * What we send to the scheme: "please pay this creditor". Loosely modelled on an
 * ISO 20022 pacs.008 (FI to FI customer credit transfer), cut down to the essentials.
 *
 * @param messageId unique per message, so a receiver can spot a duplicate
 * @param paymentId our own id for the payment, echoed back in status reports
 */
public record CreditTransferMessage(
        String messageId,
        Long paymentId,
        Long debtorAccountId,
        Creditor creditor,
        Money amount,
        String reference,
        Instant createdAt) {
}
```

The boundary, `scheme/SchemeGateway.java`:

```java
package com.example.payments.scheme;

/** Our connection to the payment scheme. In a real bank this is a network link to the scheme. */
public interface SchemeGateway {

    /**
     * Hands a payment to the scheme.
     *
     * @throws SchemeUnavailableException if the scheme couldn't be reached, or didn't answer in time.
     *         After a timeout we CAN'T KNOW whether the scheme received the message.
     */
    void submit(CreditTransferMessage message);
}
```

`scheme/SchemeUnavailableException.java`:

```java
package com.example.payments.scheme;

public class SchemeUnavailableException extends RuntimeException {

    public SchemeUnavailableException(String message) {
        super(message);
    }
}
```

And the fake, `scheme/FakeFasterPaymentsScheme.java`:

```java
package com.example.payments.scheme;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-process stand-in for the Faster Payments central infrastructure.
 * It is an idempotent consumer: a message id it has already seen is ignored.
 */
@Component
public class FakeFasterPaymentsScheme implements SchemeGateway {

    public enum Mode {
        /** Receives messages normally. */
        NORMAL,
        /** Refuses connections: nothing is received. */
        UNAVAILABLE,
        /** Receives the message, then the reply is lost: the sender sees a timeout. */
        TIMEOUT_AFTER_RECEIVING
    }

    private static final Logger log = LoggerFactory.getLogger(FakeFasterPaymentsScheme.class);

    /** Every call to submit(), duplicates included. */
    private final List<CreditTransferMessage> submissions = new CopyOnWriteArrayList<>();
    /** What the scheme actually acted on: one entry per message id. */
    private final Map<String, CreditTransferMessage> received = new ConcurrentHashMap<>();
    private volatile Mode mode = Mode.NORMAL;

    @Override
    public void submit(CreditTransferMessage message) {
        if (mode == Mode.UNAVAILABLE) {
            throw new SchemeUnavailableException("Connection refused");
        }
        submissions.add(message);
        if (received.putIfAbsent(message.messageId(), message) == null) {
            log.info("Scheme received payment {} for {} (message {})",
                    message.paymentId(), message.amount(), message.messageId());
        } else {
            log.warn("Scheme ignored duplicate message {} for payment {}", message.messageId(), message.paymentId());
        }
        if (mode == Mode.TIMEOUT_AFTER_RECEIVING) {
            throw new SchemeUnavailableException("Timed out waiting for the scheme to reply");
        }
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /** How many times this payment was sent to us, duplicates included. */
    public long timesSubmitted(Long paymentId) {
        return submissions.stream().filter(m -> m.paymentId().equals(paymentId)).count();
    }

    /** Whether the scheme acted on this payment (at most once, whatever the duplicates). */
    public boolean hasReceived(Long paymentId) {
        return received.values().stream().anyMatch(m -> m.paymentId().equals(paymentId));
    }
}
```

- **Two collections.** `submissions` records every call, so tests can count duplicates. `received` is what the scheme acted on, keyed by message id. `putIfAbsent` returns `null` only for the first message with that id: that single line is the **idempotent consumer**.
- **Three modes** simulate the failures from the Concepts table. `TIMEOUT_AFTER_RECEIVING` is the nasty one: the scheme has the payment, but the caller gets an exception.
- **Thread-safe collections** (`CopyOnWriteArrayList`, `ConcurrentHashMap`) and a `volatile` mode, because relays on several threads call it at once.

> **Simplification:** the real Faster Payments infrastructure does far more: it routes the payment to the receiving bank, applies scheme limits and checks, and tracks each bank's position against its settlement cap. This fake just receives. In guide 05 it will answer too.

### 2. The naive version, and a test that catches it

Inject the gateway into `PaymentService` (add a `SchemeGateway scheme` field and constructor parameter, and import `CreditTransferMessage`, `SchemeGateway` and `java.util.UUID`), and call it straight after taking the money:

```java
            ledger.post(debtor, settlement, amount, "Faster Payment to " + creditor.name(), payment.getId());

            // NAIVE: call the scheme from inside our database transaction
            scheme.submit(new CreditTransferMessage(UUID.randomUUID().toString(), payment.getId(),
                    debtorAccountId, creditor, amount, reference, now));
        }
```

Now a test for the first row of the Concepts table: our transaction fails **after** the call. Create `src/test/java/com/example/payments/outbox/DualWriteTest.java`:

```java
package com.example.payments.outbox;

import com.example.payments.PaymentsIntegrationTest;
import com.example.payments.ledger.LedgerService;
import com.example.payments.money.Money;
import com.example.payments.payment.Creditor;
import com.example.payments.payment.Payment;
import com.example.payments.payment.PaymentRepository;
import com.example.payments.payment.PaymentService;
import com.example.payments.payment.SortCode;
import com.example.payments.scheme.FakeFasterPaymentsScheme;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Our database and the scheme are two separate systems. What if only one of the writes happens? */
@PaymentsIntegrationTest
class DualWriteTest {

    private static final Creditor BOB = new Creditor("Bob Smith", SortCode.of("20-00-00"), "55779911");

    @Autowired PaymentService paymentService;
    @Autowired PaymentRepository payments;
    @Autowired LedgerService ledger;
    @Autowired FakeFasterPaymentsScheme scheme;
    @Autowired TransactionTemplate transaction;

    @Test
    void nothingReachesTheSchemeIfOurTransactionRollsBack() {
        Long alice = ledger.openCustomerAccount("Alice").getId();
        ledger.depositCash(alice, Money.gbp("100.00"));
        AtomicLong paymentId = new AtomicLong();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            Payment payment = paymentService.create(alice, BOB, Money.gbp("25.00"), "Rent");
            paymentId.set(payment.getId());
            // Anything that fails before the commit: a lost database connection, a crash, a bug...
            throw new IllegalStateException("Simulated failure before commit");
        })).hasMessage("Simulated failure before commit");

        System.out.printf("payment saved=%s, alice=%s, scheme received=%s%n",
                payments.existsById(paymentId.get()), ledger.get(alice).getBalance(),
                scheme.hasReceived(paymentId.get()));

        assertThat(payments.existsById(paymentId.get())).isFalse();
        assertThat(ledger.get(alice).getBalance()).isEqualTo(Money.gbp("100.00"));
        assertThat(scheme.hasReceived(paymentId.get())).isFalse();
    }
}
```

The test wraps `create` in an outer transaction (`create` joins it) and then throws before the commit. That's the simplest reliable way to make "the commit fails" happen on demand. `AtomicLong` is just a mutable holder the lambda can write to.

Run it:

```
payment saved=false, alice=GBP 100.00, scheme received=true
```

```
Expecting value to be false but was true
```

The payment doesn't exist and Alice still has her £100, but the scheme received the payment. In real life Bob has just been paid £25 by a bank that has no record of it. Everything else in the suite still passes, which is the frightening part: nothing about this bug shows up until something fails at the wrong moment.

### 3. The outbox table

Remove the naive call (and the `SchemeGateway` field) from `PaymentService` again. Create `src/main/resources/db/migration/V5__create_outbox_event.sql`:

```sql
CREATE TABLE outbox_event (
    id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    aggregate_type VARCHAR(50)              NOT NULL,
    aggregate_id   BIGINT                   NOT NULL,
    event_type     VARCHAR(50)              NOT NULL,
    payload        VARCHAR(4000)            NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at   TIMESTAMP WITH TIME ZONE
);

CREATE INDEX ix_outbox_event_unpublished ON outbox_event (published_at, id);
```

- **`aggregate_type` / `aggregate_id`** say which business object the event is about (`Payment`, 7). "Aggregate" is the Domain-Driven Design word for an entity and everything that belongs to it.
- **`payload`** is the message, as JSON, exactly as it will be sent. It's built at the moment of the business change, so the relay never has to reconstruct it from data that might have changed since.
- **`published_at` is `NULL` until sent.** The relay's question is always "unpublished rows, oldest first", which is what the index serves.

Add the relay's polling interval to `application.properties`:

```properties

# How often the outbox relay looks for messages to send
payments.outbox-poll-interval=PT1S
```

### 4. The outbox entity and repository

Create the package `outbox`, and `outbox/OutboxEvent.java`:

```java
package com.example.payments.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A message waiting to be sent, written in the same transaction as the change that caused it. */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(nullable = false, length = 4000)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
        // for JPA
    }

    public OutboxEvent(String aggregateType, Long aggregateId, String eventType, String payload, Instant createdAt) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public void markPublished(Instant now) {
        this.publishedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
```

`outbox/OutboxEventRepository.java`. This first version has no locking, **deliberately**:

```java
package com.example.payments.outbox;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query("select e from OutboxEvent e where e.publishedAt is null order by e.id")
    List<OutboxEvent> findUnpublished(Limit limit);

    List<OutboxEvent> findByAggregateId(Long aggregateId);
}
```

`Limit` (from `org.springframework.data.domain`) caps the number of results, which Hibernate turns into `FETCH FIRST n ROWS ONLY`.

### 5. Write the event with the payment

In `PaymentService`, inject the repository and Jackson's `JsonMapper`:

```java
    private final OutboxEventRepository outbox;
    private final JsonMapper json;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, LedgerService ledger, PaymentProperties properties,
                          OutboxEventRepository outbox, JsonMapper json, Clock clock) {
        this.payments = payments;
        this.ledger = ledger;
        this.properties = properties;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }
```

(Imports: `OutboxEvent`, `OutboxEventRepository`, `CreditTransferMessage`, `tools.jackson.databind.json.JsonMapper`, `java.util.UUID`.) Where the naive call was, write the event instead:

```java
            ledger.post(debtor, settlement, amount, "Faster Payment to " + creditor.name(), payment.getId());

            // Don't call the scheme. Write down that we need to, in this same transaction.
            CreditTransferMessage message = new CreditTransferMessage(UUID.randomUUID().toString(),
                    payment.getId(), debtorAccountId, creditor, amount, reference, now);
            outbox.save(new OutboxEvent("Payment", payment.getId(), "PaymentValidated",
                    json.writeValueAsString(message), now));
        }
```

The `messageId` is generated **here**, once, and stored in the payload. However many times the relay sends this event, it's the same message with the same id. If the relay generated a fresh UUID on each attempt, the scheme couldn't recognise a resend.

The relay will also need to move the payment to `SUBMITTED`. Add to `PaymentService`:

```java
    /** Called by the outbox relay once the scheme has the payment. */
    @Transactional
    public void markSubmitted(Long paymentId) {
        get(paymentId).markSubmitted(clock.instant());
    }
```

### 6. The relay

Create `outbox/OutboxRelay.java`:

```java
package com.example.payments.outbox;

import com.example.payments.payment.PaymentService;
import com.example.payments.scheme.CreditTransferMessage;
import com.example.payments.scheme.SchemeGateway;
import com.example.payments.scheme.SchemeUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;

/** Sends outbox events to the scheme, one per transaction, and marks them published. */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_PER_RUN = 100;

    private final OutboxEventRepository events;
    private final SchemeGateway scheme;
    private final PaymentService paymentService;
    private final TransactionTemplate transaction;
    private final JsonMapper json;
    private final Clock clock;

    public OutboxRelay(OutboxEventRepository events, SchemeGateway scheme, PaymentService paymentService,
                       TransactionTemplate transaction, JsonMapper json, Clock clock) {
        this.events = events;
        this.scheme = scheme;
        this.paymentService = paymentService;
        this.transaction = transaction;
        this.json = json;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${payments.outbox-poll-interval}")
    public void poll() {
        try {
            publishPending();
        } catch (SchemeUnavailableException e) {
            log.warn("Scheme unavailable, will retry: {}", e.getMessage());
        }
    }

    /** Publishes waiting events until there are none left. Returns how many it sent. */
    public int publishPending() {
        int sent = 0;
        while (sent < MAX_PER_RUN && Boolean.TRUE.equals(transaction.execute(status -> publishNext()))) {
            sent++;
        }
        return sent;
    }

    private boolean publishNext() {
        List<OutboxEvent> next = events.findUnpublished(Limit.of(1));
        if (next.isEmpty()) {
            return false;
        }
        OutboxEvent event = next.getFirst();
        CreditTransferMessage message = json.readValue(event.getPayload(), CreditTransferMessage.class);

        scheme.submit(message); // if this throws, the transaction rolls back and the event stays unpublished

        paymentService.markSubmitted(message.paymentId());
        event.markPublished(clock.instant());
        return true;
    }
}
```

The design decisions:

- **One event per transaction.** Each `publishNext` runs in its own `TransactionTemplate` transaction: find one event, send it, mark the payment `SUBMITTED` and the event published, commit. A failure affects only that event.
- **If the send throws, nothing is marked.** The exception rolls the transaction back, the event stays unpublished, and the next run tries again. That's the retry mechanism, and it's why the relay never loses a message.
- **`publishPending` lets exceptions out; `poll` catches them.** Tests call `publishPending` and can see exactly what went wrong. The scheduled `poll` just logs a warning and tries again on its next tick.
- **`fixedDelayString = "${payments.outbox-poll-interval}"`**: a placeholder, so the interval lives in `application.properties`. Spring parses `PT1S` as one second.
- **`MAX_PER_RUN`** stops one run hogging the scheduler thread forever if events arrive faster than they're sent.
- **`List.getFirst()`** is Java 21+. Older code uses `get(0)`.

> **Sidebar: a transaction open during a network call.** The relay holds a database transaction (and soon a row lock) while it calls the scheme. If the scheme is slow, that transaction is long. Here it's acceptable: the transaction touches one outbox row and one payment, and gateways have short timeouts. A higher-volume design would "claim" a batch of rows in one quick transaction (marking them in progress with a lease time), send them outside any transaction, then mark them published in another. That's more code, and the at-least-once reasoning is the same.

Scheduling is already switched on by `SchedulingConfig` from guide 03, and off in tests, so in tests nothing is sent until a test calls `publishPending()` itself.

### 7. Tests

Run `DualWriteTest` again. It passes: the payment, the ledger entries **and** the outbox row all rolled back together, so there was nothing to send.

Now `src/test/java/com/example/payments/outbox/OutboxRelayTest.java`:

```java
package com.example.payments.outbox;

import com.example.payments.PaymentsIntegrationTest;
import com.example.payments.ledger.LedgerService;
import com.example.payments.money.Money;
import com.example.payments.payment.Creditor;
import com.example.payments.payment.PaymentService;
import com.example.payments.payment.PaymentStatus;
import com.example.payments.payment.SortCode;
import com.example.payments.scheme.FakeFasterPaymentsScheme;
import com.example.payments.scheme.FakeFasterPaymentsScheme.Mode;
import com.example.payments.scheme.SchemeUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@PaymentsIntegrationTest
class OutboxRelayTest {

    private static final Creditor BOB = new Creditor("Bob Smith", SortCode.of("20-00-00"), "55779911");

    @Autowired PaymentService paymentService;
    @Autowired LedgerService ledger;
    @Autowired OutboxRelay relay;
    @Autowired OutboxEventRepository outbox;
    @Autowired FakeFasterPaymentsScheme scheme;

    Long alice;

    @BeforeEach
    void openAccount() {
        alice = ledger.openCustomerAccount("Alice").getId();
        ledger.depositCash(alice, Money.gbp("100.00"));
    }

    @AfterEach
    void schemeBackToNormal() {
        scheme.setMode(Mode.NORMAL);
    }

    @Test
    void aValidatedPaymentWaitsInTheOutboxUntilTheRelaySendsIt() {
        Long id = paymentService.create(alice, BOB, Money.gbp("25.00"), "Rent").getId();
        assertThat(scheme.hasReceived(id)).isFalse();
        assertThat(outbox.findByAggregateId(id)).singleElement()
                .satisfies(event -> assertThat(event.getPublishedAt()).isNull());

        relay.publishPending();

        assertThat(scheme.timesSubmitted(id)).isEqualTo(1);
        assertThat(paymentService.get(id).getStatus()).isEqualTo(PaymentStatus.SUBMITTED);
        assertThat(outbox.findByAggregateId(id)).singleElement()
                .satisfies(event -> assertThat(event.getPublishedAt()).isNotNull());
    }

    @Test
    void aRejectedPaymentIsNeverSent() {
        Long id = paymentService.create(alice, BOB, Money.gbp("500.00"), "Car").getId();

        assertThat(outbox.findByAggregateId(id)).isEmpty();
    }

    @Test
    void whenTheSchemeIsDownTheEventWaitsForTheNextRun() {
        Long id = paymentService.create(alice, BOB, Money.gbp("25.00"), "Rent").getId();
        scheme.setMode(Mode.UNAVAILABLE);

        assertThatThrownBy(() -> relay.publishPending()).isInstanceOf(SchemeUnavailableException.class);
        assertThat(paymentService.get(id).getStatus()).isEqualTo(PaymentStatus.VALIDATED);

        scheme.setMode(Mode.NORMAL);
        relay.publishPending();

        assertThat(paymentService.get(id).getStatus()).isEqualTo(PaymentStatus.SUBMITTED);
    }

    @Test
    void aLostReplyMeansTheMessageIsSentTwiceButActedOnOnce() {
        Long id = paymentService.create(alice, BOB, Money.gbp("25.00"), "Rent").getId();
        scheme.setMode(Mode.TIMEOUT_AFTER_RECEIVING);

        assertThatThrownBy(() -> relay.publishPending()).isInstanceOf(SchemeUnavailableException.class);

        scheme.setMode(Mode.NORMAL);
        relay.publishPending();

        assertThat(scheme.timesSubmitted(id)).isEqualTo(2);  // at-least-once delivery...
        assertThat(scheme.hasReceived(id)).isTrue();          // ...made safe by an idempotent receiver
        assertThat(paymentService.get(id).getStatus()).isEqualTo(PaymentStatus.SUBMITTED);
    }
}
```

The last test is the whole outbox story in a dozen lines. The first attempt reached the scheme, but the relay saw a timeout, so it rolled back and left the event unpublished. The second attempt sent the **same** message again. The scheme logged:

```
WARN … c.e.p.scheme.FakeFasterPaymentsScheme    : Scheme ignored duplicate message 9ec08136-92d8-4d51-a531-3ba2b39ef134 for payment 30
```

and acted on it once.

The relay publishes **every** unpublished event in the shared test database, including ones left by other test classes. That's why the assertions are about the payment each test created, never about totals.

`@AfterEach` puts the fake scheme back to `NORMAL`: like the clock in guide 03, it's one shared bean, and a test that leaves it `UNAVAILABLE` would break every test after it.

### 8. Two relays at once

Run a second copy of the app and you have two relays. Simulate it: create `src/test/java/com/example/payments/outbox/ConcurrentRelayTest.java`:

```java
package com.example.payments.outbox;

import com.example.payments.PaymentsIntegrationTest;
import com.example.payments.ledger.LedgerService;
import com.example.payments.money.Money;
import com.example.payments.payment.Creditor;
import com.example.payments.payment.PaymentService;
import com.example.payments.payment.PaymentStatus;
import com.example.payments.payment.SortCode;
import com.example.payments.scheme.FakeFasterPaymentsScheme;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Two copies of the application are running, so two relays poll the same outbox at the same time. */
@PaymentsIntegrationTest
class ConcurrentRelayTest {

    private static final Creditor BOB = new Creditor("Bob Smith", SortCode.of("20-00-00"), "55779911");

    @Autowired PaymentService paymentService;
    @Autowired LedgerService ledger;
    @Autowired OutboxRelay relay;
    @Autowired FakeFasterPaymentsScheme scheme;

    @Test
    void twoRelaysNeverSendTheSamePaymentTwice() throws Exception {
        Long alice = ledger.openCustomerAccount("Alice").getId();
        ledger.depositCash(alice, Money.gbp("1000.00"));
        List<Long> paymentIds = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            paymentIds.add(paymentService.create(alice, BOB, Money.gbp("10.00"), "Payment " + i).getId());
        }

        CountDownLatch startingGun = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int relays = 0; relays < 2; relays++) {
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    try {
                        relay.publishPending();
                    } catch (RuntimeException e) {
                        synchronized (failures) {
                            failures.add(e);
                        }
                    }
                    return null;
                }));
            }
            startingGun.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        long totalSubmissions = paymentIds.stream().mapToLong(scheme::timesSubmitted).sum();
        System.out.printf("submissions=%d for %d payments, failures=%s%n", totalSubmissions, paymentIds.size(),
                failures.stream().map(t -> t.getClass().getSimpleName()).toList());

        assertThat(failures).isEmpty();
        assertThat(totalSubmissions).isEqualTo(20);
        assertThat(paymentIds).allSatisfy(id ->
                assertThat(paymentService.get(id).getStatus()).isEqualTo(PaymentStatus.SUBMITTED));
    }
}
```

Two threads calling the same `OutboxRelay` bean behave exactly like two application instances sharing a database: each call runs its own transactions on its own connection. The test counts **submissions** (every call to the scheme, duplicates included), not what the scheme acted on, because the point is that the relays shouldn't be sending duplicates in the first place.

Run it:

```
submissions=21 for 20 payments, failures=[ObjectOptimisticLockingFailureException]
```

Run it again and you'll very likely see the same. Here's the sequence:

1. Both relays run `findUnpublished` at the same instant and get the **same** first event.
2. Both send it to the scheme. That's the duplicate.
3. Both mark payment `SUBMITTED`. The first commits. The second's `UPDATE payment … where id=? and version=?` matches zero rows, because `@Version` (from guide 01) sees the payment changed under it:

   ```
   ObjectOptimisticLockingFailureException: Unexpected row count (expected row count 1 but was 0)
   [update payment set amount=?, … ,status=?,updated_at=?,version=? where id=? and version=?]
   ```

4. The losing relay's transaction rolls back and the exception stops its run. The winning relay sends the other 19.

`@Version` kept the **database** consistent, but too late to matter: the message had already gone. A lock taken when the row is **read** is the only thing that can stop the second relay from sending.

### 9. Fix it: `FOR UPDATE SKIP LOCKED`

Replace the query in `OutboxEventRepository`:

```java
package com.example.payments.outbox;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * SELECT ... FOR UPDATE SKIP LOCKED: lock the unpublished events we return, and skip over
     * any that another relay has already locked, instead of waiting for them.
     * A lock timeout of -2 is Hibernate's way of asking for SKIP LOCKED.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select e from OutboxEvent e where e.publishedAt is null order by e.id")
    List<OutboxEvent> findUnpublishedForUpdate(Limit limit);

    List<OutboxEvent> findByAggregateId(Long aggregateId);
}
```

and use it in `OutboxRelay.publishNext`:

```java
        List<OutboxEvent> next = events.findUnpublishedForUpdate(Limit.of(1));
```

- **`@Lock(PESSIMISTIC_WRITE)`** adds `FOR UPDATE`, as in guide 02.
- **The lock timeout hint** is the standard JPA hint `jakarta.persistence.lock.timeout`. The value `-2` is a Hibernate convention meaning "skip locked rows" (`0` means "don't wait, fail", and a positive number is a timeout in milliseconds). It's not obvious, hence the comment.

To see the SQL, run the test with `-Dspring.jpa.show-sql=true`:

```bash
./mvnw test -Dtest=ConcurrentRelayTest -Dspring.jpa.show-sql=true
```

```
Hibernate: select oe1_0.id,oe1_0.aggregate_id,oe1_0.aggregate_type,oe1_0.created_at,oe1_0.event_type,oe1_0.payload,oe1_0.published_at from outbox_event oe1_0 where oe1_0.published_at is null order by oe1_0.id fetch first ? rows only for update skip locked
```

And the test:

```
submissions=20 for 20 payments, failures=[]
```

Green. Each relay locks the oldest event nobody else holds, sends it, and commits. The other relay never sees a row that's being worked on, so it takes the next one instead. The two share the queue without duplicates **and** without waiting for each other. Run the whole suite two or three times to make sure it's stable.

> **Sidebar: why not plain `FOR UPDATE`?** It would also stop the duplicates. The second relay would wait for the first one's lock, and when it got the row, both H2 and PostgreSQL re-check the `WHERE` clause against the now-committed row, see `published_at` is set, and move on. But every relay would queue behind every other on the same oldest row, so two relays would be no faster than one. `SKIP LOCKED` is what lets them work in parallel.

> **Sidebar: ordering.** Relays working in parallel can send event 8 before event 7 finishes. Faster Payments are independent of each other, so that's fine here. When order *does* matter (all the events for one account, say), process each group in a single worker, or include a sequence number and let the receiver reorder.

### 10. Try it

Restart the app with a fresh `data/` folder (the relay now runs on its schedule), open and fund Alice as in guide 02, and send the £25 payment from `requests.http`. The response still says:

```json
"status": "VALIDATED"
```

because the response is returned when the transaction commits, before the relay has run. Within about a second the log shows:

```
INFO … [   scheduling-1] c.e.p.scheme.FakeFasterPaymentsScheme    : Scheme received payment 1 for GBP 25.00 (message f7516a16-5bc2-4f3a-958a-e253b5dfd042)
```

on the `scheduling-1` thread, and fetching the payment again shows:

```json
"status": "SUBMITTED"
```

You can look at the outbox in the H2 console (`http://localhost:8080/h2-console`, JDBC URL `jdbc:h2:file:./data/payments`, user `sa`, no password) with `SELECT * FROM outbox_event`.

---

## Your turn

### Exercise 1: housekeeping

The outbox grows forever. Add an `OutboxCleaner` that runs at 02:30 every day and deletes events **published** more than seven days ago. Events that were never published must **never** be deleted: they're payments that haven't been sent yet. Test it with the `MutableClock` from guide 03.

<details>
<summary>Hint</summary>

`@Scheduled(cron = "0 30 2 * * *")`: Spring's cron format has six fields, starting with seconds. A JPQL `delete … where e.publishedAt < :cutoff` doesn't match `NULL` values, because any comparison with `NULL` is unknown in SQL, so unpublished rows are safe without an extra condition.

</details>

<details>
<summary>Solution</summary>

`OutboxEventRepository` (import `org.springframework.data.jpa.repository.Modifying` and `java.time.Instant`):

```java
    @Modifying
    @Query("delete from OutboxEvent e where e.publishedAt < :cutoff")
    int deletePublishedBefore(Instant cutoff);
```

`outbox/OutboxCleaner.java`:

```java
package com.example.payments.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/** Published events are only kept for a week, for troubleshooting. Unpublished ones are never deleted. */
@Component
public class OutboxCleaner {

    static final Duration KEEP_FOR = Duration.ofDays(7);

    private final OutboxEventRepository events;
    private final Clock clock;

    public OutboxCleaner(OutboxEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 2 * * *") // 02:30 every day
    @Transactional
    public int deleteOldPublishedEvents() {
        return events.deletePublishedBefore(clock.instant().minus(KEEP_FOR));
    }
}
```

`src/test/java/com/example/payments/outbox/OutboxCleanerTest.java`:

```java
package com.example.payments.outbox;

import com.example.payments.MutableClock;
import com.example.payments.PaymentsIntegrationTest;
import com.example.payments.ledger.LedgerService;
import com.example.payments.money.Money;
import com.example.payments.payment.Creditor;
import com.example.payments.payment.PaymentService;
import com.example.payments.payment.SortCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@PaymentsIntegrationTest
class OutboxCleanerTest {

    private static final Creditor BOB = new Creditor("Bob Smith", SortCode.of("20-00-00"), "55779911");

    @Autowired PaymentService paymentService;
    @Autowired LedgerService ledger;
    @Autowired OutboxRelay relay;
    @Autowired OutboxCleaner cleaner;
    @Autowired OutboxEventRepository outbox;
    @Autowired MutableClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void deletesPublishedEventsAfterAWeekButNeverUnpublishedOnes() {
        Long alice = ledger.openCustomerAccount("Alice").getId();
        ledger.depositCash(alice, Money.gbp("100.00"));
        Long sent = paymentService.create(alice, BOB, Money.gbp("10.00"), "Sent").getId();
        relay.publishPending();
        Long waiting = paymentService.create(alice, BOB, Money.gbp("10.00"), "Waiting").getId();

        clock.advance(Duration.ofDays(8));
        cleaner.deleteOldPublishedEvents();

        assertThat(outbox.findByAggregateId(sent)).isEmpty();
        assertThat(outbox.findByAggregateId(waiting)).hasSize(1);
    }
}
```

Keeping published events for a while is useful: when someone asks "did we send payment 7, and when?", the outbox row answers. In a real bank the definitive record of what was sent and received is kept elsewhere, for much longer, to meet record-keeping rules.

</details>

### Exercise 2: what about "after commit"?

Spring can run code after a transaction commits, for example with `@TransactionalEventListener(phase = AFTER_COMMIT)`. A colleague suggests: "Publish a Spring event from `create`, and send to the scheme in an after-commit listener. Then a rollback can't send anything, and we don't need an outbox table." What's wrong with that? Which row of the Concepts table does it fix, and which does it leave?

<details>
<summary>Answer</summary>

It fixes the first row: if the transaction rolls back, the listener never runs, so there's no phantom payment. But it reintroduces the third row. Between the commit and the listener's call there's a window, and if the process crashes, is redeployed or is killed in it, or if the scheme is down when the listener runs, the payment is committed as `VALIDATED`, Alice has been debited, and nothing anywhere remembers that it still has to be sent. The event lived only in memory.

The outbox works because the "still has to be sent" fact is **stored durably in the same transaction** as the payment, and something keeps retrying until it's done. After-commit listeners are fine for things you can afford to lose, like a cache refresh or a notification, but not for moving money.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 50, Failures: 0, Errors: 0, Skipped: 0
```

Run it a few times. `ConcurrentRelayTest` must pass every time.

New files since guide 03:

```
src/main/java/com/example/payments/
├── outbox/
│   ├── OutboxCleaner.java
│   ├── OutboxEvent.java
│   ├── OutboxEventRepository.java
│   └── OutboxRelay.java
└── scheme/
    ├── CreditTransferMessage.java
    ├── FakeFasterPaymentsScheme.java
    ├── SchemeGateway.java
    └── SchemeUnavailableException.java
src/main/resources/db/migration/
└── V5__create_outbox_event.sql
src/test/java/com/example/payments/outbox/
├── ConcurrentRelayTest.java
├── DualWriteTest.java
├── OutboxCleanerTest.java
└── OutboxRelayTest.java
```

Modified: `PaymentService.java` (outbox event, `markSubmitted`), `application.properties`.

---

## Common errors

**`submissions=21 for 20 payments, failures=[ObjectOptimisticLockingFailureException]`**
Two relays took the same event. The relay is still calling `findUnpublished` (no lock) instead of `findUnpublishedForUpdate`.

**`ObjectOptimisticLockingFailureException: … [update payment set … where id=? and version=?]` in the app log**
Something else changed the payment between the relay reading it and writing it, usually a second relay. Check the locking query, as above.

**`Expecting value to be false but was true` in `DualWriteTest`**
Something still calls the scheme directly from inside `PaymentService.create`. All sending must go through the outbox.

**`WARN … Scheme ignored duplicate message …`**
Not an error: the fake scheme saw a resend and ignored it, as an idempotent consumer should. It's expected after a timeout (see `aLostReplyMeansTheMessageIsSentTwiceButActedOnOnce`). If you see it constantly in normal running, two relays are sending the same events.

**Payments stay `VALIDATED` when you run the app**
The relay isn't running. Check `SchedulingConfig` exists with `@EnableScheduling`, and that you haven't set `payments.scheduling.enabled=false` outside the tests.

---

## Recap

- You can't atomically update your database **and** call another system. Every ordering of the two has a window where one has happened and the other hasn't: a phantom payment or a lost one. That's the **dual-write problem**.
- The **transactional outbox** turns it into one write: the business change and an `outbox_event` row commit in the same transaction. A **relay** sends the events later and marks them published.
- The relay delivers **at least once**. Give each message a **stable unique id** when the event is written, and make every receiver an **idempotent consumer**.
- One short transaction per event: send, mark, commit. If sending fails, the rollback leaves the event for the next run.
- Several relays must not take the same row. **`FOR UPDATE SKIP LOCKED`** lets them share the table without duplicates or waiting. In Hibernate, ask for it with `@Lock(PESSIMISTIC_WRITE)` and a lock timeout hint of `-2`.
- `@Version` keeps rows consistent, but it acts at write time. To stop a **side effect** (a message sent), lock at **read** time.

### Interview angle

<details>
<summary>"How do you reliably send a message to another system when you save something to your database?"</summary>

The transactional outbox. In the same database transaction as the business change, insert the message into an outbox table. A separate relay (a poller, or change data capture with something like Debezium) reads unsent rows, publishes them, and marks them sent. That avoids the dual-write problem: the change and the intent to send commit atomically, so you can't send something that was rolled back or lose something that was committed. Delivery is at-least-once, so messages carry a unique id and consumers deduplicate. Mention running several relays safely with `SELECT … FOR UPDATE SKIP LOCKED`, cleaning up old rows, and that ordering needs thought if it matters.

</details>

<details>
<summary>"Why not just use exactly-once delivery?"</summary>

Across independent systems over a network, it isn't achievable in general. A sender can't distinguish "the receiver never got it" from "it got it and the acknowledgement was lost", so it must either risk losing the message (at-most-once) or risk sending it twice (at-least-once). Payments choose at-least-once and get the *effect* of exactly-once by making the receiver idempotent: process each message id once, ignore repeats. Systems that advertise exactly-once (for example Kafka's transactions) achieve it within their own boundary, and still need idempotency at the edges.

</details>

<details>
<summary>"What does `SKIP LOCKED` do, and when would you use it?"</summary>

With `SELECT … FOR UPDATE SKIP LOCKED`, the query locks the rows it returns and silently skips rows that other transactions have locked, rather than waiting for them. It turns a table into a work queue that several workers can consume concurrently without blocking each other or taking the same item twice. Typical uses: outbox relays, job queues, batch workers claiming records. It's supported by PostgreSQL, Oracle, MySQL 8 and H2. The trade-off is that strict ordering is lost between workers.

</details>

<details>
<summary>"The scheme call times out. Did the payment go? What do you do?"</summary>

You don't know, and you must not guess. Don't mark it failed and refund the customer, because the scheme may have accepted it. Don't create a new payment, because that could pay twice. Keep it in its current state and resend the **same** message with the **same** id: the scheme deduplicates, so a resend is safe. Then rely on the scheme's status report (or an enquiry, or end-of-day reconciliation) to learn the true outcome. Timeouts are "unknown", never "failed".

</details>

### Further reading

- [microservices.io: Transactional outbox pattern](https://microservices.io/patterns/data/transactional-outbox.html) and [Idempotent consumer pattern](https://microservices.io/patterns/communication-style/idempotent-consumer.html) (Chris Richardson)
- [PostgreSQL docs: SELECT, the locking clause](https://www.postgresql.org/docs/current/sql-select.html): `FOR UPDATE`, `NOWAIT` and `SKIP LOCKED`
- [Spring Framework reference: task scheduling](https://docs.spring.io/spring-framework/reference/integration/scheduling.html): `@Scheduled`, fixed delay vs fixed rate, cron
- [Spring Framework reference: programmatic transaction management](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html): `TransactionTemplate`
- [Debezium: outbox event router](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html): the change-data-capture approach

**Next:** [05: Scheme responses and settlement](05-scheme-responses-and-settlement.md). The scheme answers, sometimes twice: accept or reject, give the money back, and settle.
