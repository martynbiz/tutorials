# 05: Scheme Responses and Settlement

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- What the scheme and the receiving bank send back after a payment, and what each answer means for the customer's money
- Why a **rejection** gives the money back with a new ledger entry, and why an accepted payment can only come back as a **return**
- How a **settlement cycle** clears the bank's settlement account against its reserves at the Bank of England
- **Asynchronous** processing: the answer arrives later, on another thread, from another system
- The **idempotent consumer** (an "inbox" table of processed message ids), and the **lock, then check** order that makes it safe under concurrent duplicates
- How the **state machine** from guide 01 protects you from late, out-of-order and contradictory messages
- A callback endpoint, and an in-process simulator that delivers with retries

By the end, a payment travels the whole lifecycle on its own: `VALIDATED` → `SUBMITTED` → `ACCEPTED` → `SETTLED`, or `REJECTED` with the customer's money back, however many times the scheme repeats itself.

---

## Concepts

### How it works in a real bank

Once your bank has submitted the payment, it's out of its hands for a moment. The scheme routes it to Bob's bank, which checks the account: does it exist, is it open, can it receive credits? Bob's bank then accepts or rejects it, and the answer comes back through the scheme to your bank. For Faster Payments this usually takes seconds.

In ISO 20022 terms the answer is a **status report** (`pacs.002`), with a transaction status and, for a rejection, a reason code:

| Status | Meaning | What your bank does |
|---|---|---|
| `ACSP` | Accepted, settlement in process | Mark the payment accepted. Bob has been paid. Nothing to do with the money yet: it's still owed via the scheme. |
| `RJCT` + reason (`AC01` incorrect account number, `AC04` closed account…) | Rejected | Mark the payment rejected, and **give Alice her money back** with a new ledger entry: debit the FPS settlement account, credit Alice. |

(As guide 01 said, Faster Payments' own messages are based on ISO 8583, not ISO 20022, but they carry the same accept-or-reject information. The JSON messages in this tutorial borrow the ISO 20022 names because that's where the industry is heading.)

**A rejection is not a return.** A rejection happens *before* the receiving bank accepts the payment, so it never reached Bob. If something goes wrong *after* acceptance (Bob's account is closed the next morning, say), Bob's bank sends a **return**: a brand new payment in the opposite direction that references the original. Under Pay.UK's rules a payment can't be revoked once sent; the original stays accepted, and the return is a separate inbound payment. That's why the state machine in guide 01 refuses `ACCEPTED → REJECTED`.

### Settlement

Throughout the day, your bank's FPS settlement account fills up with money owed to other banks. Three times each working day, Faster Payments settles: each bank's net position is calculated and settled across the banks' accounts at the Bank of England. Your bank's reserves there go down by what it owes (or up by what it's owed), and the settlement account is cleared:

```
Settlement of £25 outbound:
   DEBIT   FPS settlement (liability)           £25   ← we no longer owe it
   CREDIT  Reserves at the Bank of England (asset) £25   ← the money has left
```

Only then is the payment **settled** between the banks. Bob was paid hours earlier; settlement is the banks catching up with each other.

> **Simplification:** in real Faster Payments, settlement is **net** and per **cycle**: a bank learns its net position for the cycle, not a list of individual payments, and reconciles the two (the Reconciliation tutorial is about exactly that). Here the simulated scheme sends a "settlement notice" listing the payments it settled, and since this toy bank only sends payments, net equals gross. The ledger entries have the right shape either way.

> **Simplification:** the toy bank's reserves account starts at zero, so it goes **negative** after the first settlement. A real bank holds a large balance at the Bank of England, and for Faster Payments must prefund enough to cover its maximum net position.

### The answer arrives later

Until now, everything happened inside one HTTP request. Scheme responses don't work like that. The answer arrives **later**, on a **different** thread, from **another system**:

- Your API has long since returned `VALIDATED` to Alice's app. The app finds out the outcome by asking again (or by a push notification).
- The bank must be ready for an answer at any moment, including while it's still processing something else for the same payment.

In a real bank, the scheme's messages arrive over the bank's link to the scheme and are picked up by a listener. Here, the simulated scheme calls a handler bean directly from a scheduled thread, and the same handler is also exposed as a **callback endpoint** so you can play the scheme yourself with curl.

### Messages arrive twice, late, and out of order

The scheme delivers at least once, for the same reason your relay does: it can't tell a lost message from a lost acknowledgement. So your bank must assume:

- **Duplicates.** The same status report may arrive twice, even at the same moment. Process each message id **once**: that's the **idempotent consumer**. Store the ids you've processed in an **inbox** table, in the same transaction as the work.
- **Late or contradictory messages.** A rejection arriving after the payment was accepted. A settlement notice for a payment that hasn't been accepted yet. These are different messages, so the inbox won't catch them, but the **state machine** will: the transition is illegal, the message is refused, and nothing changes. The sender can retry later, or the case goes to an operations team.

Concurrent duplicates need the same care as concurrent payments. "Check the inbox, then do the work" is a read-then-write race. The fix is the order from guide 02: **lock the payment first, then check the inbox**. A duplicate waits on the payment's row lock until the first delivery commits, and then finds its id in the inbox.

---

## Steps

### 1. The inbox table

Create `src/main/resources/db/migration/V6__create_processed_message.sql`:

```sql
-- The inbox: ids of messages from the scheme that we have already acted on
CREATE TABLE processed_message (
    message_id   VARCHAR(100)             NOT NULL PRIMARY KEY,
    message_type VARCHAR(50)              NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
```

The primary key is the backstop again: even if two deliveries of one message both got past the check, only one could insert its row, and the other's transaction would roll back.

### 2. The messages

Add to the `scheme` package. `scheme/TransactionStatus.java`:

```java
package com.example.payments.scheme;

/** ISO 20022 transaction status codes, as used in a pacs.002 status report. */
public enum TransactionStatus {
    /** AcceptedSettlementInProcess: accepted, and settlement will follow. */
    ACSP,
    /** Rejected. A reason code says why. */
    RJCT
}
```

`scheme/PaymentStatusReport.java`:

```java
package com.example.payments.scheme;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The scheme's answer to one of our payments. Loosely modelled on an ISO 20022 pacs.002
 * (FI to FI payment status report).
 *
 * @param messageId         unique id of THIS report, used to spot a duplicate delivery
 * @param originalMessageId the id of the credit transfer message it answers
 * @param reasonCode        ISO 20022 status reason code, required when status is RJCT
 */
public record PaymentStatusReport(
        @NotBlank @Size(max = 100) String messageId,
        @NotBlank @Size(max = 100) String originalMessageId,
        @NotNull Long paymentId,
        @NotNull TransactionStatus status,
        @Size(max = 4) String reasonCode) {
}
```

`scheme/SettlementNotice.java`:

```java
package com.example.payments.scheme;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** "These payments were included in a settlement cycle that has now completed at the central bank." */
public record SettlementNotice(
        @NotBlank @Size(max = 100) String messageId,
        @NotEmpty List<Long> paymentIds) {
}
```

Every message has its **own** `messageId`, distinct from the payment's id and from the id of the message it answers. Deduplication is per message, not per payment: an acceptance and a later settlement notice are two different messages about the same payment, and both must be processed.

### 3. The inbox entity

Create the package `callback`. `callback/ProcessedMessage.java`:

```java
package com.example.payments.callback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/** One row per message from the scheme that we have acted on. */
@Entity
@Table(name = "processed_message")
public class ProcessedMessage implements Persistable<String> {

    @Id
    @Column(name = "message_id", length = 100)
    private String messageId;

    @Column(name = "message_type", nullable = false, length = 50)
    private String messageType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Transient
    private boolean isNew;

    protected ProcessedMessage() {
        // for JPA
    }

    ProcessedMessage(String messageId, String messageType, Instant processedAt) {
        this.messageId = messageId;
        this.messageType = messageType;
        this.processedAt = processedAt;
        this.isNew = true;
    }

    @Override
    public String getId() {
        return messageId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
```

`Persistable` again, for the reason in guide 03: the id is assigned, so without it Spring Data would `merge`, and a duplicate would quietly update the row instead of failing on the primary key.

`callback/ProcessedMessageRepository.java`:

```java
package com.example.payments.callback;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, String> {
}
```

`callback/CallbackOutcome.java`:

```java
package com.example.payments.callback;

/** What happened to a message from the scheme. Either way, the scheme can stop resending it. */
public enum CallbackOutcome {
    PROCESSED,
    DUPLICATE
}
```

A duplicate is **not an error**. The sender needs a success response so it stops retrying; `DUPLICATE` just tells it (and your logs) that nothing new happened.

### 4. Locking a payment

The handler must lock the payment it's working on. Add to `PaymentRepository`:

```java
package com.example.payments.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(Long id);
}
```

and to `PaymentService`:

```java
    /** Loads and locks the payment until the surrounding transaction ends. */
    @Transactional
    public Payment getForUpdate(Long id) {
        return payments.findByIdForUpdate(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }
```

### 5. The handler

Create `callback/SchemeResponseHandler.java`:

```java
package com.example.payments.callback;

import com.example.payments.ledger.Account;
import com.example.payments.ledger.LedgerService;
import com.example.payments.money.Money;
import com.example.payments.payment.InvalidPaymentException;
import com.example.payments.payment.Payment;
import com.example.payments.payment.PaymentService;
import com.example.payments.payment.RejectionReason;
import com.example.payments.scheme.PaymentStatusReport;
import com.example.payments.scheme.SettlementNotice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Acts on messages from the scheme. Each message is processed at most once (an idempotent
 * consumer), however many times it is delivered.
 */
@Service
public class SchemeResponseHandler {

    private static final Logger log = LoggerFactory.getLogger(SchemeResponseHandler.class);

    private final PaymentService paymentService;
    private final LedgerService ledger;
    private final ProcessedMessageRepository inbox;
    private final Clock clock;

    public SchemeResponseHandler(PaymentService paymentService, LedgerService ledger,
                                 ProcessedMessageRepository inbox, Clock clock) {
        this.paymentService = paymentService;
        this.ledger = ledger;
        this.inbox = inbox;
        this.clock = clock;
    }

    @Transactional
    public CallbackOutcome handle(PaymentStatusReport report) {
        // 1. Lock the payment. A duplicate of this report waits here until we commit...
        Payment payment = paymentService.getForUpdate(report.paymentId());
        // 2. ...and then finds our inbox row here.
        if (alreadyProcessed(report.messageId())) {
            return CallbackOutcome.DUPLICATE;
        }
        Instant now = clock.instant();
        switch (report.status()) {
            case ACSP -> payment.markAccepted(now);
            case RJCT -> {
                payment.reject(reasonFrom(report), now);
                returnFunds(payment);
            }
        }
        recordProcessed(report.messageId(), "PaymentStatusReport", now);
        log.info("Payment {} is now {}", payment.getId(), payment.getStatus());
        return CallbackOutcome.PROCESSED;
    }

    @Transactional
    public CallbackOutcome handle(SettlementNotice notice) {
        // Lock every payment first, in id order, then the internal accounts
        List<Payment> settled = notice.paymentIds().stream().sorted().distinct()
                .map(paymentService::getForUpdate)
                .toList();
        if (alreadyProcessed(notice.messageId())) {
            return CallbackOutcome.DUPLICATE;
        }
        Instant now = clock.instant();
        Money total = Money.zero(Money.GBP);
        for (Payment payment : settled) {
            payment.markSettled(now);
            total = total.plus(payment.getAmount());
        }
        // The money we owed via the scheme leaves our reserves at the Bank of England
        Account fpsSettlement = ledger.getSystemAccountForUpdate(LedgerService.FPS_SETTLEMENT);
        Account reserves = ledger.getSystemAccountForUpdate(LedgerService.BOE_RESERVES);
        ledger.post(fpsSettlement, reserves, total,
                "FPS settlement %s (%d payments)".formatted(notice.messageId(), settled.size()), null);
        recordProcessed(notice.messageId(), "SettlementNotice", now);
        log.info("Settled {} payments, total {}", settled.size(), total);
        return CallbackOutcome.PROCESSED;
    }

    /** The scheme said no, so the money we took goes back to the customer. */
    private void returnFunds(Payment payment) {
        // Same lock order as PaymentService.create: the customer, then the internal account
        Account debtor = ledger.getForUpdate(payment.getDebtorAccountId());
        Account fpsSettlement = ledger.getSystemAccountForUpdate(LedgerService.FPS_SETTLEMENT);
        ledger.post(fpsSettlement, debtor, payment.getAmount(),
                "Return of rejected payment " + payment.getId(), payment.getId());
    }

    private static RejectionReason reasonFrom(PaymentStatusReport report) {
        if (report.reasonCode() == null) {
            throw new InvalidPaymentException("A rejection must include a reason code");
        }
        try {
            return RejectionReason.valueOf(report.reasonCode());
        } catch (IllegalArgumentException e) {
            throw new InvalidPaymentException("Unknown reason code " + report.reasonCode());
        }
    }

    private boolean alreadyProcessed(String messageId) {
        return inbox.existsById(messageId);
    }

    private void recordProcessed(String messageId, String type, Instant now) {
        inbox.saveAndFlush(new ProcessedMessage(messageId, type, now));
    }
}
```

The rules in this class are the point of the guide:

- **Lock, check, act, record, all in one transaction.** The payment is locked first, so concurrent deliveries of the same report queue up. The inbox check comes second, so a queued duplicate sees the first delivery's committed row. The inbox row is written in the same transaction as the status change and the ledger entry, so they can't get out of step.
- **The state machine does the validating.** The handler doesn't ask "is this payment in the right state for an acceptance?". It calls `markAccepted`, and `Payment` throws `IllegalPaymentTransitionException` if not. The whole transaction rolls back, **including the inbox row**, so the message isn't marked processed and can be retried once it makes sense (or investigated if it never will).
- **A rejection reverses the money in the same transaction.** `reject` succeeds only for a `SUBMITTED` payment, so the funds can only ever be returned once, even if the inbox somehow failed.
- **Lock order.** `returnFunds` locks the customer, then the settlement account: the same order as `PaymentService.create` in guide 02, so they can't deadlock. The settlement handler locks payments **in ascending id order** (`sorted()`), then the internal accounts, so two settlement notices can't deadlock on each other either.
- **The `switch` on `report.status()`** is a switch *statement* with arrow labels. Because `TransactionStatus` is an enum, adding a third status would let this compile silently. A switch *expression* (one that returns a value) would force you to handle it. Either is fine here: the record's `@NotNull` and the enum type already restrict what can arrive.

> **Simplification:** the inbox table grows forever. A real system expires processed ids after a period comfortably longer than the sender's retry window, just like the idempotency records in guide 03.

### 6. The callback endpoint

Create `callback/SchemeCallbackController.java`:

```java
package com.example.payments.callback;

import com.example.payments.scheme.PaymentStatusReport;
import com.example.payments.scheme.SettlementNotice;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the scheme's messages arrive. A real bank receives these over its link to the scheme,
 * not over a public HTTP API, and only after authenticating the sender.
 */
@RestController
@RequestMapping("/scheme")
public class SchemeCallbackController {

    private final SchemeResponseHandler handler;

    public SchemeCallbackController(SchemeResponseHandler handler) {
        this.handler = handler;
    }

    @PostMapping("/status-reports")
    public CallbackResponse statusReport(@Valid @RequestBody PaymentStatusReport report) {
        return new CallbackResponse(report.messageId(), handler.handle(report));
    }

    @PostMapping("/settlements")
    public CallbackResponse settlement(@Valid @RequestBody SettlementNotice notice) {
        return new CallbackResponse(notice.messageId(), handler.handle(notice));
    }

    public record CallbackResponse(String messageId, CallbackOutcome outcome) {
    }
}
```

This endpoint has no authentication, so anyone who can reach it can mark payments accepted. That's acceptable for a tutorial running on your laptop and **nowhere else**. A real inbound channel authenticates the sender (mutual TLS, signed messages) before anything is processed.

A message that doesn't fit the lifecycle should be a `409 Conflict`: the request is valid, but it conflicts with the payment's current state, and the state may change. Add to `ApiExceptionHandler` (import `IllegalPaymentTransitionException`):

```java
    /** The payment isn't in a state where this can happen (yet). The sender can retry later. */
    @ExceptionHandler(IllegalPaymentTransitionException.class)
    ProblemDetail handle(IllegalPaymentTransitionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Illegal payment transition");
        return problem;
    }
```

An unknown payment is already a `404` (`PaymentNotFoundException`), and a rejection without a valid reason code is a `422` (`InvalidPaymentException`).

### 7. Make the scheme answer

Now give the fake scheme a voice. It should decide on each payment it receives, deliver its answers **later** on a scheduled thread, retry deliveries that fail, and run settlement cycles. Add to `application.properties`:

```properties

# The simulated scheme: how quickly it answers, and how often it settles
payments.scheme.response-interval=PT2S
payments.scheme.settlement-interval=PT30S
```

(Real Faster Payments settles three times each working day. Thirty seconds lets you watch it happen.)

Replace `scheme/FakeFasterPaymentsScheme.java`:

```java
package com.example.payments.scheme;

import com.example.payments.callback.SchemeResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-process stand-in for the Faster Payments central infrastructure and the receiving banks.
 * It is an idempotent consumer: a message id it has already seen is ignored.
 * It answers later, not during submit(), and settles accepted payments in cycles.
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

    /** The receiving bank says this account has been closed. */
    public static final String CLOSED_ACCOUNT = "99999999";

    private static final Logger log = LoggerFactory.getLogger(FakeFasterPaymentsScheme.class);
    private static final int MAX_DELIVERY_ATTEMPTS = 5;

    private final SchemeResponseHandler bank;

    /** Every call to submit(), duplicates included. */
    private final List<CreditTransferMessage> submissions = new CopyOnWriteArrayList<>();
    /** What the scheme actually acted on: one entry per message id. */
    private final Map<String, CreditTransferMessage> received = new ConcurrentHashMap<>();
    /** Status reports waiting to be delivered to the bank. */
    private final Queue<PendingReport> outbox = new ConcurrentLinkedQueue<>();
    /** Accepted payments waiting for the next settlement cycle. */
    private final Set<Long> awaitingSettlement = ConcurrentHashMap.newKeySet();
    private volatile Mode mode = Mode.NORMAL;

    private record PendingReport(PaymentStatusReport report, int attempts) {
    }

    public FakeFasterPaymentsScheme(SchemeResponseHandler bank) {
        this.bank = bank;
    }

    @Override
    public void submit(CreditTransferMessage message) {
        if (mode == Mode.UNAVAILABLE) {
            throw new SchemeUnavailableException("Connection refused");
        }
        submissions.add(message);
        if (received.putIfAbsent(message.messageId(), message) == null) {
            log.info("Scheme received payment {} for {} (message {})",
                    message.paymentId(), message.amount(), message.messageId());
            outbox.add(new PendingReport(decide(message), 0));
        } else {
            log.warn("Scheme ignored duplicate message {} for payment {}", message.messageId(), message.paymentId());
        }
        if (mode == Mode.TIMEOUT_AFTER_RECEIVING) {
            throw new SchemeUnavailableException("Timed out waiting for the scheme to reply");
        }
    }

    /** The receiving bank's decision. Real banks check the account exists, is open, can take credits... */
    private PaymentStatusReport decide(CreditTransferMessage message) {
        boolean closed = CLOSED_ACCOUNT.equals(message.creditor().accountNumber());
        return new PaymentStatusReport(UUID.randomUUID().toString(), message.messageId(), message.paymentId(),
                closed ? TransactionStatus.RJCT : TransactionStatus.ACSP, closed ? "AC04" : null);
    }

    /** Sends every waiting status report to the bank. A failed delivery is retried next time. */
    @Scheduled(fixedDelayString = "${payments.scheme.response-interval}")
    public int deliverResponses() {
        int delivered = 0;
        List<PendingReport> retry = new ArrayList<>();
        PendingReport pending;
        while ((pending = outbox.poll()) != null) {
            PaymentStatusReport report = pending.report();
            try {
                bank.handle(report);
                delivered++;
                if (report.status() == TransactionStatus.ACSP) {
                    awaitingSettlement.add(report.paymentId());
                }
            } catch (RuntimeException e) {
                if (pending.attempts() + 1 < MAX_DELIVERY_ATTEMPTS) {
                    log.warn("Delivery of report {} failed, will retry: {}", report.messageId(), e.getMessage());
                    retry.add(new PendingReport(report, pending.attempts() + 1));
                } else {
                    log.error("Giving up on report {} after {} attempts", report.messageId(), MAX_DELIVERY_ATTEMPTS);
                }
            }
        }
        outbox.addAll(retry);
        return delivered;
    }

    /** A settlement cycle: everything accepted since the last cycle is settled together. */
    @Scheduled(fixedDelayString = "${payments.scheme.settlement-interval}")
    public void runSettlementCycle() {
        if (awaitingSettlement.isEmpty()) {
            return;
        }
        List<Long> paymentIds = List.copyOf(awaitingSettlement);
        bank.handle(new SettlementNotice(UUID.randomUUID().toString(), paymentIds));
        paymentIds.forEach(awaitingSettlement::remove);
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

What's new:

- **`submit` no longer answers.** It decides, queues the answer in its own little outbox, and returns. `deliverResponses`, on a scheduler thread, delivers queued answers to the bank's handler a couple of seconds later. That's the asynchrony.
- **The decision rule** is deliberately simple: account number `99999999` is "closed" (`AC04`); anything else is accepted.
- **Failed deliveries are retried**, up to five attempts in all, then dropped with an error log. A real scheme has its own rules for unanswered messages, but every sender has some retry limit, after which a person takes over.
- **`runSettlementCycle`** settles every payment it has had accepted since the last cycle, in one notice.
- **It calls the handler directly.** In-process, the "network" is a method call, which keeps the tutorial self-contained. The `/scheme` endpoints run exactly the same handler code.

> **In older codebases:** this kind of scheme connectivity is often built on message queues (IBM MQ is common in banks) with JMS listeners, or on an integration framework such as Apache Camel or Spring Integration. The patterns in this guide, idempotent consumers and lock-then-check, apply unchanged.

Run the whole test suite now. All 50 tests from guide 04 still pass: in tests, scheduling is off, so the scheme queues answers that nobody delivers.

### 8. Tests for the whole journey

Create `src/test/java/com/example/payments/callback/SchemeResponseTest.java`:

```java
package com.example.payments.callback;

import com.example.payments.PaymentsIntegrationTest;
import com.example.payments.ledger.LedgerEntryRepository;
import com.example.payments.ledger.LedgerService;
import com.example.payments.money.Money;
import com.example.payments.outbox.OutboxRelay;
import com.example.payments.payment.Creditor;
import com.example.payments.payment.PaymentService;
import com.example.payments.payment.PaymentStatus;
import com.example.payments.payment.RejectionReason;
import com.example.payments.payment.SortCode;
import com.example.payments.scheme.FakeFasterPaymentsScheme;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** The whole journey, with the simulated scheme answering asynchronously. */
@PaymentsIntegrationTest
class SchemeResponseTest {

    @Autowired PaymentService paymentService;
    @Autowired LedgerService ledger;
    @Autowired LedgerEntryRepository entries;
    @Autowired OutboxRelay relay;
    @Autowired FakeFasterPaymentsScheme scheme;

    Long alice;

    @BeforeEach
    void setUp() {
        // Clear out anything earlier tests left in flight, so the numbers below are ours alone
        relay.publishPending();
        scheme.deliverResponses();
        scheme.runSettlementCycle();

        alice = ledger.openCustomerAccount("Alice").getId();
        ledger.depositCash(alice, Money.gbp("100.00"));
    }

    @Test
    void anAcceptedPaymentIsSettledInTheNextCycle() {
        Money fpsBefore = ledger.getSystemAccount(LedgerService.FPS_SETTLEMENT).getBalance();
        Money reservesBefore = ledger.getSystemAccount(LedgerService.BOE_RESERVES).getBalance();
        Long id = pay("20-00-00", "55779911");

        relay.publishPending();
        assertThat(paymentService.get(id).getStatus()).isEqualTo(PaymentStatus.SUBMITTED);

        scheme.deliverResponses();
        assertThat(paymentService.get(id).getStatus()).isEqualTo(PaymentStatus.ACCEPTED);

        scheme.runSettlementCycle();
        assertThat(paymentService.get(id).getStatus()).isEqualTo(PaymentStatus.SETTLED);
        // We no longer owe the scheme: the money has left our reserves at the Bank of England
        assertThat(ledger.getSystemAccount(LedgerService.FPS_SETTLEMENT).getBalance()).isEqualTo(fpsBefore);
        assertThat(ledger.getSystemAccount(LedgerService.BOE_RESERVES).getBalance())
                .isEqualTo(reservesBefore.minus(Money.gbp("25.00")));
        assertThat(ledger.get(alice).getBalance()).isEqualTo(Money.gbp("75.00"));
        assertThat(ledger.booksBalance()).isTrue();
    }

    @Test
    void aRejectedPaymentGivesTheCustomerTheirMoneyBack() {
        Long id = pay("20-00-00", FakeFasterPaymentsScheme.CLOSED_ACCOUNT);
        assertThat(ledger.get(alice).getBalance()).isEqualTo(Money.gbp("75.00"));

        relay.publishPending();
        scheme.deliverResponses();

        assertThat(paymentService.get(id).getStatus()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(paymentService.get(id).getRejectionReason()).isEqualTo(RejectionReason.AC04);
        assertThat(ledger.get(alice).getBalance()).isEqualTo(Money.gbp("100.00"));
        assertThat(entries.findByPaymentIdOrderById(id)).extracting(e -> e.getDescription())
                .containsExactly("Faster Payment to Bob Smith", "Return of rejected payment " + id);
        assertThat(ledger.booksBalance()).isTrue();
    }

    private Long pay(String sortCode, String accountNumber) {
        Creditor bob = new Creditor("Bob Smith", SortCode.of(sortCode), accountNumber);
        return paymentService.create(alice, bob, Money.gbp("25.00"), "Rent").getId();
    }
}
```

The tests drive the asynchronous world **by hand**: relay, then deliver, then settle, asserting the state after each step. That's why scheduling is off in tests. Background threads racing the assertions would make these tests flaky.

The `@BeforeEach` flush matters. Earlier test classes left payments submitted and answers queued in the shared fake scheme. Delivering and settling all of that first means the settlement in `anAcceptedPaymentIsSettledInTheNextCycle` is the only one that moves the balances being measured.

`aRejectedPaymentGivesTheCustomerTheirMoneyBack` checks the ledger tells the story: two entries for the payment, the original and its reversal. Nothing was edited or deleted. That's the [audit trail](../glossary.md#audit-trail).

Now the callback endpoint, `src/test/java/com/example/payments/callback/SchemeCallbackApiTest.java`:

```java
package com.example.payments.callback;

import com.example.payments.PaymentsIntegrationTest;
import com.example.payments.ledger.LedgerService;
import com.example.payments.money.Money;
import com.example.payments.outbox.OutboxRelay;
import com.example.payments.payment.Creditor;
import com.example.payments.payment.PaymentService;
import com.example.payments.payment.PaymentStatus;
import com.example.payments.payment.SortCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@PaymentsIntegrationTest
class SchemeCallbackApiTest {

    private static final Creditor BOB = new Creditor("Bob Smith", SortCode.of("20-00-00"), "55779911");

    @Autowired MockMvcTester mvc;
    @Autowired PaymentService paymentService;
    @Autowired LedgerService ledger;
    @Autowired OutboxRelay relay;

    Long alice;
    Long paymentId;

    @BeforeEach
    void submitAPayment() {
        alice = ledger.openCustomerAccount("Alice").getId();
        ledger.depositCash(alice, Money.gbp("100.00"));
        paymentId = paymentService.create(alice, BOB, Money.gbp("25.00"), "Rent").getId();
        relay.publishPending();
    }

    @Test
    void aRejectionDeliveredTwiceIsActedOnOnce() {
        String messageId = UUID.randomUUID().toString();

        MvcTestResult first = report(messageId, paymentId, "RJCT", "AC01");
        MvcTestResult again = report(messageId, paymentId, "RJCT", "AC01");

        assertThat(first).hasStatus(HttpStatus.OK).bodyJson().extractingPath("$.outcome").isEqualTo("PROCESSED");
        assertThat(again).hasStatus(HttpStatus.OK).bodyJson().extractingPath("$.outcome").isEqualTo("DUPLICATE");
        assertThat(paymentService.get(paymentId).getStatus()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(ledger.get(alice).getBalance()).isEqualTo(Money.gbp("100.00"));
    }

    @Test
    void acceptedThenSettled() {
        report(UUID.randomUUID().toString(), paymentId, "ACSP", null);

        MvcTestResult settled = mvc.post().uri("/scheme/settlements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"messageId": "%s", "paymentIds": [%d]}
                        """.formatted(UUID.randomUUID(), paymentId))
                .exchange();

        assertThat(settled).hasStatus(HttpStatus.OK);
        assertThat(paymentService.get(paymentId).getStatus()).isEqualTo(PaymentStatus.SETTLED);
    }

    @Test
    void aReportThatDoesNotFitTheLifecycleIsAConflict() {
        report(UUID.randomUUID().toString(), paymentId, "ACSP", null);

        MvcTestResult lateRejection = report(UUID.randomUUID().toString(), paymentId, "RJCT", "AC04");

        assertThat(lateRejection).hasStatus(HttpStatus.CONFLICT);
        assertThat(lateRejection).bodyJson().extractingPath("$.detail")
                .isEqualTo("Payment %d cannot move from ACCEPTED to REJECTED".formatted(paymentId));
        assertThat(ledger.get(alice).getBalance()).isEqualTo(Money.gbp("75.00"));
    }

    @Test
    void aRejectionMustSayWhy() {
        assertThat(report(UUID.randomUUID().toString(), paymentId, "RJCT", null))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(paymentService.get(paymentId).getStatus()).isEqualTo(PaymentStatus.SUBMITTED);
    }

    @Test
    void aReportForAnUnknownPaymentIs404() {
        assertThat(report(UUID.randomUUID().toString(), 999_999L, "ACSP", null)).hasStatus(HttpStatus.NOT_FOUND);
    }

    private MvcTestResult report(String messageId, Long paymentId, String status, String reasonCode) {
        String reason = reasonCode == null ? "null" : "\"" + reasonCode + "\"";
        return mvc.post().uri("/scheme/status-reports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"messageId": "%s", "originalMessageId": "m-%d", "paymentId": %d,
                         "status": "%s", "reasonCode": %s}
                        """.formatted(messageId, paymentId, paymentId, status, reason))
                .exchange();
    }
}
```

`aReportThatDoesNotFitTheLifecycleIsAConflict` is the "contradictory message" case: a rejection for a payment that was already accepted. The state machine refuses it, the transaction rolls back, and Alice's money stays where it should: with Bob.

These tests act as the scheme themselves, so the fake scheme's own queued answer for the same payment will fail if it's ever delivered later (the payment has already moved on). The fake logs that, retries, and eventually gives up. That's exactly what a contradictory duplicate *should* produce: noise in a log, and no change to the money.

### 9. Duplicates at the same moment

The scheme's retry can fire while its first delivery is still being processed. Create `src/test/java/com/example/payments/callback/ConcurrentCallbackTest.java`:

```java
package com.example.payments.callback;

import com.example.payments.PaymentsIntegrationTest;
import com.example.payments.ledger.LedgerEntryRepository;
import com.example.payments.ledger.LedgerService;
import com.example.payments.money.Money;
import com.example.payments.outbox.OutboxRelay;
import com.example.payments.payment.Creditor;
import com.example.payments.payment.PaymentService;
import com.example.payments.payment.SortCode;
import com.example.payments.scheme.PaymentStatusReport;
import com.example.payments.scheme.TransactionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** The scheme's retry fires while the first delivery is still being processed. */
@PaymentsIntegrationTest
class ConcurrentCallbackTest {

    private static final Creditor BOB = new Creditor("Bob Smith", SortCode.of("20-00-00"), "55779911");

    @Autowired SchemeResponseHandler handler;
    @Autowired PaymentService paymentService;
    @Autowired LedgerService ledger;
    @Autowired LedgerEntryRepository entries;
    @Autowired OutboxRelay relay;

    @Test
    void theSameRejectionDeliveredFiveTimesAtOnceReturnsTheMoneyOnce() throws Exception {
        Long alice = ledger.openCustomerAccount("Alice").getId();
        ledger.depositCash(alice, Money.gbp("100.00"));
        Long paymentId = paymentService.create(alice, BOB, Money.gbp("25.00"), "Rent").getId();
        relay.publishPending();
        PaymentStatusReport report = new PaymentStatusReport(UUID.randomUUID().toString(), "m-" + paymentId,
                paymentId, TransactionStatus.RJCT, "AC01");

        CountDownLatch startingGun = new CountDownLatch(1);
        List<CallbackOutcome> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            List<Future<CallbackOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    return handler.handle(report);
                }));
            }
            startingGun.countDown();
            for (Future<CallbackOutcome> future : futures) {
                outcomes.add(future.get());
            }
        }

        System.out.println("outcomes=" + outcomes);
        assertThat(outcomes).containsOnlyOnce(CallbackOutcome.PROCESSED);
        assertThat(ledger.get(alice).getBalance()).isEqualTo(Money.gbp("100.00"));
        assertThat(entries.findByPaymentIdOrderById(paymentId)).hasSize(2);
    }
}
```

It passes:

```
outcomes=[DUPLICATE, PROCESSED, DUPLICATE, DUPLICATE, DUPLICATE]
```

Exactly one delivery processed the rejection, the other four were recognised as duplicates **and answered with success**, and the payment has exactly two ledger entries: the original debit and one reversal. Exercise 1 shows why the order inside `handle` is what makes this work.

### 10. Try it

Restart the app with a fresh `data/` folder. Open and fund Alice (account 4) as before, then add to `requests.http` and send both payments:

```http
### Alice pays her old gym, whose account is closed
POST http://localhost:8080/payments
Content-Type: application/json
Idempotency-Key: gym-2026-11

{
  "debtorAccountId": 4,
  "creditor": {"name": "Old Gym Ltd", "sortCode": "20-00-00", "accountNumber": "99999999"},
  "amount": "30.00",
  "reference": "Membership"
}

### Play the scheme: a status report
POST http://localhost:8080/scheme/status-reports
Content-Type: application/json

{"messageId": "report-1", "originalMessageId": "x", "paymentId": 1, "status": "ACSP", "reasonCode": null}

### Play the scheme: a settlement notice
POST http://localhost:8080/scheme/settlements
Content-Type: application/json

{"messageId": "settlement-1", "paymentIds": [1]}
```

The last two requests let you play the scheme yourself; you'll use them at the end of this step. Send the two payments first.

Right after the two `POST /payments`, both are `VALIDATED` and Alice is at £45.00 (£100 − £25 − £30). Watch the log:

```
… FakeFasterPaymentsScheme : Scheme received payment 1 for GBP 25.00 (message d6e26131-7e32-4bf0-ad39-34e1862ecf94)
… FakeFasterPaymentsScheme : Scheme received payment 2 for GBP 30.00 (message 242dcef6-a77a-4aed-94f9-5d5ac7d8dada)
… SchemeResponseHandler    : Payment 1 is now ACCEPTED
… SchemeResponseHandler    : Payment 2 is now REJECTED
```

A few seconds later, payment 2 shows:

```json
{
  "id": 2,
  "creditor": { "name": "Old Gym Ltd", "sortCode": "20-00-00", "accountNumber": "99999999" },
  "amount": { "amount": "30.00", "currency": "GBP" },
  "status": "REJECTED",
  "rejectionReason": "AC04",
  …
}
```

and Alice is back to £75.00. Within thirty seconds the settlement cycle runs:

```
… SchemeResponseHandler    : Settled 1 payments, total GBP 25.00
```

Payment 1 is `SETTLED`, and the internal accounts show where the money ended up:

```json
{"id":2,"name":"Faster Payments settlement","type":"LIABILITY","balance":{"amount":"0.00","currency":"GBP"}}
{"id":3,"name":"Reserves at the Bank of England","type":"ASSET","balance":{"amount":"-25.00","currency":"GBP"}}
```

The settlement account is back to zero: the bank owes nothing more through the scheme. The reserves have gone down by Alice's £25 (negative only because this toy bank started with none).

Now play the scheme. Sending a rejection for the settled payment 1 gets:

```json
{"detail":"Payment 1 cannot move from SETTLED to REJECTED","instance":"/scheme/status-reports","status":409,"title":"Illegal payment transition"}
```

To try the duplicate handling, make the fake scheme slow so you can answer first: start the app with `--payments.scheme.response-interval=PT15S` (in IntelliJ, add it to the run configuration's **Program arguments**; in the terminal, `./mvnw spring-boot:run -Dspring-boot.run.arguments=--payments.scheme.response-interval=PT15S`). Make a payment, and once the log says the scheme received it, send the `report-1` request twice:

```json
{"messageId":"report-1","outcome":"PROCESSED"}
{"messageId":"report-1","outcome":"DUPLICATE"}
```

When the fake scheme's own acceptance arrives a few seconds later, it's a *different* message for a payment that has already moved on:

```
WARN … FakeFasterPaymentsScheme : Delivery of report 3869c4ff-a098-48aa-bf8b-d014722f6188 failed, will retry: Payment 1 cannot move from ACCEPTED to ACCEPTED
```

The state machine refuses it, nothing changes, and the fake keeps retrying until it gives up. (Because the fake only settles payments whose acceptance *it* delivered, a payment you accepted by hand won't be settled automatically. Send a settlement notice for it yourself.)

---

## Your turn

### Exercise 1: why lock first?

In `SchemeResponseHandler.handle(PaymentStatusReport)`, move the `alreadyProcessed` check **above** `getForUpdate`, so the handler checks the inbox before locking. Run `ConcurrentCallbackTest` a few times. What happens, and why? Does Alice get her money back twice? Put the order back afterwards.

<details>
<summary>Answer</summary>

The test fails with an error:

```
Caused by: com.example.payments.payment.IllegalPaymentTransitionException: Payment 1 cannot move from REJECTED to REJECTED
```

All five threads check the inbox at the same instant, before any of them has committed, so all five see "not processed". Then they queue on the payment lock. The first rejects the payment, returns the funds, records the message and commits. The second gets the lock, finds the payment already `REJECTED`, and `reject` throws, rolling its transaction back. So do the rest.

**Alice's money is still right**: she's refunded once, because the state machine refuses a second rejection before `returnFunds` runs. But the duplicates got **errors** instead of `DUPLICATE`. Over HTTP those would be `409`s, and a scheme that sees errors keeps retrying, raising alerts, and eventually escalating to people. With the check after the lock, a duplicate waits its turn and then finds the committed inbox row: a clean, successful `DUPLICATE`.

This is the same lesson as the Core ledger's idempotent transfers: **lock, then check**. The state machine (and the inbox's primary key) guarantee correctness; the order gives the right behaviour.

</details>

### Exercise 2 (stretch, no solution given): returns

Model a **return**: Bob's bank accepted payment 7, but later sends it back because his account has since been closed. Add a `RETURNED` status reachable from `ACCEPTED` and `SETTLED`, a `PaymentReturnNotice` message (loosely a `pacs.004`) with its own `messageId`, a handler method that credits Alice and records the return in the ledger, and a callback endpoint. Think about:

- Which internal account is debited? The returned money arrives via the scheme, so it affects the FPS settlement position.
- What should happen to a return for a payment that was never accepted?
- Update `PaymentStatusTest`'s `EXPECTED` table first, and watch it fail until `nextStatuses()` agrees.

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 58, Failures: 0, Errors: 0, Skipped: 0
```

Run it at least twice. Every concurrency test (`ConcurrentPaymentTest`, `ManyPayersTest`, `ConcurrentRetryTest`, `ConcurrentRelayTest`, `ConcurrentCallbackTest`) must pass every time.

New files since guide 04:

```
src/main/java/com/example/payments/
├── callback/
│   ├── CallbackOutcome.java
│   ├── ProcessedMessage.java
│   ├── ProcessedMessageRepository.java
│   ├── SchemeCallbackController.java
│   └── SchemeResponseHandler.java
└── scheme/
    ├── PaymentStatusReport.java
    ├── SettlementNotice.java
    └── TransactionStatus.java
src/main/resources/db/migration/
└── V6__create_processed_message.sql
src/test/java/com/example/payments/callback/
├── ConcurrentCallbackTest.java
├── SchemeCallbackApiTest.java
└── SchemeResponseTest.java
```

Modified: `FakeFasterPaymentsScheme.java`, `PaymentRepository.java`, `PaymentService.java` (`getForUpdate`), `ApiExceptionHandler.java`, `application.properties`, `requests.http`.

The finished package layout:

```
com.example.payments
├── config/       ClockConfig, SchedulingConfig
├── money/        Money
├── payment/      Payment, PaymentStatus, SortCode, PaymentService, PaymentController, …
├── ledger/       Account, LedgerEntry, LedgerService, AccountController, …
├── idempotency/  IdempotencyRecord, IdempotencyService, IdempotencyRecordCleaner, …
├── outbox/       OutboxEvent, OutboxRelay, OutboxCleaner, …
├── scheme/       SchemeGateway, FakeFasterPaymentsScheme, the message records
├── callback/     SchemeResponseHandler, SchemeCallbackController, ProcessedMessage, …
└── web/          ApiExceptionHandler
```

---

## Common errors

**`409` with `"Payment 1 cannot move from SETTLED to REJECTED"`**
Working as intended: a status report that contradicts the payment's lifecycle is refused, and nothing changes. If you see this from the real scheme in production, it goes to an operations team.

**`IllegalPaymentTransitionException: Payment 1 cannot move from REJECTED to REJECTED` in `ConcurrentCallbackTest`**
The inbox check runs before the payment lock. See Exercise 1.

**`422` with `"A rejection must include a reason code"`**
An `RJCT` report without `reasonCode`. Every rejection must say why (`AC01`, `AC04`, …).

**`WARN … Delivery of report … failed, will retry: Payment 1 cannot move from ACCEPTED to ACCEPTED`**
The fake scheme is delivering an acceptance for a payment that's already accepted, usually because you answered for it by hand. It's harmless: nothing changes, and the fake gives up after five attempts.

**Payments stay `SUBMITTED` in the running app**
The fake scheme's scheduled `deliverResponses` isn't running, or every delivery is failing. Look for `Delivery of report … failed` warnings in the log: the message after the colon says why.

---

## Recap

- After submission, the receiving bank **accepts** (`ACSP`) or **rejects** (`RJCT` with a reason code). A rejection returns the customer's money with a **new, opposite ledger entry**. After acceptance, money can only come back as a **return**, a separate payment.
- **Settlement** happens later, in cycles: the FPS settlement account is cleared against the bank's **reserves at the Bank of England**.
- Scheme answers arrive **asynchronously**, from another system and thread. The API reports the state *now*; clients ask again later.
- Messages arrive **twice, late or contradictory**. An **inbox** of processed message ids, written in the same transaction as the work, makes the consumer idempotent. The **state machine** refuses messages that don't fit, and rolls back so they can be retried.
- **Lock the payment, then check the inbox.** Concurrent duplicates then queue, and get a clean `DUPLICATE` instead of an error.
- A **duplicate is a success** for the sender: acknowledge it so it stops retrying.

### Interview angle

<details>
<summary>"What's an idempotent consumer, and how do you build one?"</summary>

A message receiver that produces the same effect whether a message arrives once or many times, which you need because delivery is at-least-once. Each message carries a unique id. In the same transaction as the processing, insert the id into a processed-messages (inbox) table with a unique constraint. Before processing, check whether the id is there; if so, acknowledge it as a duplicate without doing anything. To handle concurrent duplicates, serialise on the business entity first (lock the row), then check the inbox, and keep the unique constraint as a backstop. Also expire old ids after the sender's retry window, and remember that semantically duplicate messages with different ids need business-level protection, such as a state machine.

</details>

<details>
<summary>"A payment's status report says rejected, but your system already has it as accepted. What do you do?"</summary>

Refuse to apply it and change nothing: once accepted, a payment can't be rejected, and money would end up in the wrong place. The state machine throws, the transaction rolls back (including the inbox entry, so the message isn't marked as handled), and the receiver returns an error such as `409 Conflict`. Then raise it for investigation, because a contradictory message means either an out-of-order delivery or a real discrepancy with the scheme, which reconciliation would also catch. If the money genuinely needs to come back, that's a return: a new payment.

</details>

<details>
<summary>"Walk me through the full life of an outbound Faster Payment in your system."</summary>

The API receives the instruction with an idempotency key. In one transaction: lock the payer's account, check our limit and the funds, create the payment as validated (or rejected with a reason code), debit the customer and credit the FPS settlement account, write an outbox event with a unique message id, and store the idempotency record. The relay picks up the event with `FOR UPDATE SKIP LOCKED`, sends it to the scheme, and marks the payment submitted. The scheme's status report arrives asynchronously: the handler locks the payment, checks the inbox, and marks it accepted, or rejected with a reversing ledger entry. At the settlement cycle, the settlement account is debited and the central bank reserves credited, and the payments are marked settled. Every step is retry-safe: idempotency keys at the API, at-least-once delivery with message ids on the way out, and an idempotent consumer on the way in.

</details>

### Further reading

- [Pay.UK: Faster Payments System Principles](https://www.wearepay.uk/wp-content/uploads/2026/02/Pay.UK-Faster-Payments-System-Principles-v-11-Jan-2026.pdf) (PDF): section 5 covers returns, section 6 settlement
- [Pay.UK: 2025 PFMI self-assessment](https://www.wearepay.uk/wp-content/uploads/2025/11/2025-PFMI-self-assessment-of-Pay.UK_.pdf) (PDF): how Faster Payments and Bacs work and settle, in Pay.UK's own words
- [Bank of England: CHAPS](https://www.bankofengland.co.uk/payments/chaps)
- [microservices.io: Idempotent consumer pattern](https://microservices.io/patterns/communication-style/idempotent-consumer.html)
- [Pay.UK: Confirmation of Payee](https://www.wearepay.uk/what-we-do/overlay-services/confirmation-of-payee/): the name check that runs before a payment like this is even created

---

## You've built a payments engine

Across five guides you've built the sending side of a Faster Payments-style payment:

| Guide | Banking idea | Java/Spring skill |
|---|---|---|
| 01 | Clearing vs settlement; FPS, Bacs and CHAPS; ISO 20022 | An explicit state machine, value objects, `@ConfigurationProperties`, a `Clock` bean |
| 02 | Where the money goes while a payment is in flight | A small double-entry ledger, `@Version`, `SELECT … FOR UPDATE`, lock ordering, concurrency tests |
| 03 | Safe retries for customers' apps | A generic idempotency layer, fingerprints, `Persistable`, method validation, a mutable test clock |
| 04 | Never lose a payment, never send a phantom one | The transactional outbox, at-least-once delivery, `@Scheduled`, `FOR UPDATE SKIP LOCKED` |
| 05 | Accept, reject, return funds, settle | Asynchronous callbacks, the idempotent consumer, lock-then-check |

**Where to go next:**

- **The Core ledger tutorial** builds the ledger this system cut down: journal entries with many postings, derived balances, statements and reversals, in depth.
- **The [Reconciliation tutorial](../reconciliation/README.md)** checks the bank's records against the scheme's settlement reports, and investigates the breaks.
- **Extend this system yourself:** returns (Exercise 2); expiring the inbox; incoming payments (credit a customer from the scheme, idempotently); switching to PostgreSQL with Docker Compose and checking every test still passes, `SKIP LOCKED` included.
