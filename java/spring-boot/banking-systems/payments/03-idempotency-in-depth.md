# 03: Idempotency in Depth

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- Why a payment API **must** be safe to retry, and what goes wrong when it isn't
- How the `Idempotency-Key` header works, and how UK Open Banking specifies the same idea
- A **generic idempotency layer** with its own `idempotency_record` table: key, request fingerprint, stored response, and expiry
- Why the record must be written **in the same transaction** as the work it protects
- Fingerprinting a request with **SHA-256**, so a key reused for a different request is caught
- How simultaneous duplicates are resolved by a **primary key** and a replay, proven with a concurrency test
- A **merge trap** in Spring Data JPA that silently turns "insert" into "overwrite"
- Controlling time in tests with an injected `Clock`, and expiring old keys with a `@Scheduled` job

By the end, a client can send the same payment as many times as it likes, even ten times at the same instant, and the customer is charged exactly once.

---

## Concepts

### How it works in a real bank

Alice taps **Pay** in her banking app on a train. The request reaches the bank, the payment is created and her account is debited, and then the train enters a tunnel. The response never arrives. The app shows "Something went wrong. Try again?"

The app can't tell the difference between "the request never arrived" and "it arrived, worked, and the answer was lost". If it retries and the server treats the retry as a new payment, Bob is paid twice. If it doesn't retry, a payment that really failed is silently dropped. Neither is acceptable for money.

The fix is [idempotency](../glossary.md#idempotency): doing the same request twice has the same effect as doing it once. The client generates a unique [idempotency key](../glossary.md#idempotency-key) (usually a UUID) for each payment it *intends* to make, and sends it with every attempt:

```http
POST /payments
Idempotency-Key: 0b7f8d9e-5c2a-4f1e-9a3b-2d6c4e8f1a7b
```

The server's rules:

1. **A new key:** do the work, and remember the key and the response.
2. **A known key with the same request:** do **nothing**. Return the original response.
3. **A known key with a different request:** that's a client bug. Refuse it, so the second request isn't silently ignored.
4. **An expired key:** forget it, and treat the request as new.

UK Open Banking's Read/Write API specifies exactly this for third parties initiating payments. Its `x-idempotency-key` header is mandatory on the payment-creating `POST`s, may be at most 40 characters, and the bank must treat a request as a repeat if it received one with the same key from the same third party **in the preceding 24 hours**, returning the existing resource with `201 Created` and creating nothing new. A plain `Idempotency-Key` header is being standardised by the IETF.

### Idempotency across systems

That's idempotency between a client and your API. Payments add a second, harder kind: between **your systems and other banks' systems**. Your bank sends a payment to the scheme and the connection times out. Did the scheme get it? Your bank must be able to resend it, so the scheme must recognise a resend. And when the scheme sends *you* a status report, it may arrive twice, so you must recognise that too. Guides 04 and 05 build both directions. The principle is the same in all of them: **every message carries a unique id, and the receiver remembers which ids it has acted on.**

### A record per key

There are two common designs:

- **Store the key on the thing you created.** Add an `idempotency_key` column with a unique constraint to the `payment` table. Simple, and it works well when one request creates exactly one row. The Core ledger tutorial does this.
- **A separate `idempotency_record` table**, independent of any business table, storing the key, a fingerprint of the request, and the **response** that was sent. Any endpoint can use it, the exact original response can be replayed (status, `Location` header and body), and old keys can be expired and deleted without touching business data. That's what you'll build.

The record has to be written **in the same database transaction as the work it protects**. If the payment commits and the record doesn't, a retry pays again. If the record commits and the payment doesn't, a retry is told "done" about a payment that never happened. One transaction makes them commit or roll back together.

### Fingerprints

To spot a key reused for a different request, store a **fingerprint** of the original: a SHA-256 hash of the operation (`POST /payments`) plus the request body's JSON. Same request, same fingerprint. Change a single penny and the hash is completely different. A hash is fixed-length (64 hex characters) whatever the size of the request, and it doesn't keep a copy of the customer's payment details in a second table.

### Duplicates that arrive together

"Look up the key, and if it's new, do the work" is a read-then-write, so it's a race. Ten copies of the same request arriving at once can all look up the key, all find nothing, and all start work.

The defence is the table's **primary key**. All ten can do the work, but only one can insert its record. The other nine fail on the primary key, which rolls back their whole transaction, payment and ledger entries included. Each loser then reads the winner's committed record and replays its response. The customer is charged once, and all ten callers get the same answer.

> **Sidebar: reserve the key first?** Another common design inserts the key *before* doing the work, marked "in progress", in its own short transaction, then does the work, then fills in the response. Duplicates then hit the primary key immediately and do no work at all. The cost is a new problem: if the process dies mid-request, the key is stuck "in progress", and you need a recovery rule (expire it, or check whether the work actually happened). The single-transaction design you'll build wastes some work on duplicates, but it can never get stuck.

### Which responses are remembered?

This design stores a response only when the work **completes**, including a payment that was created but `REJECTED` for insufficient funds (that's a real outcome, recorded in the database). If the request fails with an error instead (a validation `400`, a `404` for an unknown account, a `422`), the transaction rolls back, nothing is stored, and the client may fix the problem and retry with the same key. Some APIs store error responses too. Either policy is defensible, as long as it's documented.

### Time

Expiry means the code depends on the current time, and a test can't wait 24 hours. Since guide 01, every class has asked an injected `Clock` for the time. In this guide a test replaces that bean with one it can move forward on demand.

---

## Steps

### 1. The table

Create `src/main/resources/db/migration/V4__create_idempotency_record.sql`:

```sql
CREATE TABLE idempotency_record (
    idempotency_key     VARCHAR(100)             NOT NULL PRIMARY KEY,
    request_fingerprint VARCHAR(64)              NOT NULL,
    response_status     INTEGER                  NOT NULL,
    response_body       VARCHAR(10000)           NOT NULL,
    response_location   VARCHAR(200),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at          TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX ix_idempotency_record_expires_at ON idempotency_record (expires_at);
```

- **The key is the primary key.** That's the duplicate backstop from the Concepts section.
- **`response_body` is `VARCHAR(10000)`** rather than a `TEXT` or `CLOB` type, because `VARCHAR` is portable between H2 and PostgreSQL and payment responses are small.
- **`ix_idempotency_record_expires_at`** lets the clean-up job find expired rows without scanning the table.

> **Simplification:** keys here are unique across **all** clients. A real API scopes them per client (Open Banking says "from the same third party"), usually with a composite primary key of client id and key, so two clients can't collide or, worse, read each other's responses.

### 2. A time-to-live setting

Add to `application.properties`:

```properties

# How long an Idempotency-Key is remembered (ISO 8601 duration)
payments.idempotency-ttl=PT24H
```

And to `PaymentProperties`:

```java
package com.example.payments.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/** Settings under "payments." in application.properties. */
@ConfigurationProperties("payments")
public record PaymentProperties(BigDecimal singlePaymentLimit, Duration idempotencyTtl) {
}
```

Spring Boot converts `PT24H` (ISO 8601: "a period of 24 hours") straight into a `java.time.Duration`. It also accepts shorthand such as `24h`.

### 3. The record

Create `idempotency/IdempotencyRecord.java`:

```java
package com.example.payments.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/** The stored outcome of one idempotent request, so a retry can be answered without redoing the work. */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord implements Persistable<String> {

    @Id
    @Column(name = "idempotency_key", length = 100)
    private String key;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", nullable = false, length = 10000)
    private String responseBody;

    @Column(name = "response_location", length = 200)
    private String responseLocation;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Transient
    private boolean isNew;

    protected IdempotencyRecord() {
        // for JPA
    }

    IdempotencyRecord(String key, String requestFingerprint, int responseStatus, String responseBody,
                      String responseLocation, Instant createdAt, Instant expiresAt) {
        this.key = key;
        this.requestFingerprint = requestFingerprint;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.responseLocation = responseLocation;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.isNew = true;
    }

    /** Tells Spring Data to INSERT (persist), never merge. See the guide for why that matters. */
    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public String getId() {
        return key;
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public String getResponseLocation() {
        return responseLocation;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
```

**Why `Persistable`?** Spring Data's `save()` has to decide between `persist` (an `INSERT`) and `merge` (load the row, then `INSERT` or `UPDATE` it). For an entity whose id you assign yourself, like this key, it can't tell a new object from an existing one by looking at the id, so it falls back to `merge`. A merge of a key that another transaction has just committed **updates** that row instead of failing. The primary key backstop silently disappears. Implementing `Persistable` and returning `true` from `isNew()` for objects built by the constructor forces an `INSERT`, so a duplicate key fails as it should. (JPA builds loaded records with the protected no-args constructor, so for those `isNew` stays `false`.) Exercise 2 shows the damage without it.

### 4. Repository and exception

`idempotency/IdempotencyRecordRepository.java`:

```java
package com.example.payments.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {

    @Modifying
    @Query("delete from IdempotencyRecord r where r.expiresAt <= :now")
    int deleteExpired(Instant now);
}
```

`@Modifying` marks a JPQL `delete` or `update`. It runs as one bulk statement and returns the number of rows affected.

`idempotency/IdempotencyKeyReusedException.java`:

```java
package com.example.payments.idempotency;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key '%s' was already used for a different request".formatted(key));
    }
}
```

### 5. The idempotency service

This is the generic part: any controller can wrap its work in it. Create `idempotency/IdempotencyService.java`:

```java
package com.example.payments.idempotency;

import com.example.payments.payment.PaymentProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Makes any POST endpoint safe to retry. The first request with a key does the work and stores
 * the response IN THE SAME TRANSACTION. Later requests with that key get the stored response back.
 */
@Service
public class IdempotencyService {

    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final IdempotencyRecordRepository records;
    private final TransactionTemplate transaction;
    private final JsonMapper json;
    private final PaymentProperties properties;
    private final Clock clock;

    public IdempotencyService(IdempotencyRecordRepository records, TransactionTemplate transaction,
                              JsonMapper json, PaymentProperties properties, Clock clock) {
        this.records = records;
        this.transaction = transaction;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param key       the client's Idempotency-Key
     * @param operation names the endpoint, e.g. "POST /payments", so a key can't be reused across endpoints
     * @param request   the request body; its JSON is fingerprinted
     * @param type      the response body type, needed to rebuild a stored response
     * @param action    does the real work; runs inside this method's transaction
     */
    public <T> ResponseEntity<T> execute(String key, String operation, Object request, Class<T> type,
                                         Supplier<ResponseEntity<T>> action) {
        String fingerprint = fingerprint(operation, request);
        try {
            return transaction.execute(status -> {
                Optional<ResponseEntity<T>> previous = findPrevious(key, fingerprint, type);
                if (previous.isPresent()) {
                    return previous.get();
                }
                ResponseEntity<T> response = action.get();
                save(key, fingerprint, response);
                return withReplayedHeader(response, false);
            });
        } catch (DataIntegrityViolationException e) {
            // Another request with the same key committed while we were working. Our work
            // has been rolled back, so answer with the winner's stored response instead.
            return transaction.execute(status -> findPrevious(key, fingerprint, type).orElseThrow(() -> e));
        }
    }

    private <T> Optional<ResponseEntity<T>> findPrevious(String key, String fingerprint, Class<T> type) {
        Optional<IdempotencyRecord> found = records.findById(key);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyRecord record = found.get();
        if (record.isExpiredAt(clock.instant())) {
            records.delete(record);
            records.flush(); // the DELETE must reach the database before our INSERT of the same key
            return Optional.empty();
        }
        if (!record.getRequestFingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyReusedException(key);
        }
        ResponseEntity.BodyBuilder replay = ResponseEntity.status(record.getResponseStatus())
                .header(REPLAYED_HEADER, "true");
        if (record.getResponseLocation() != null) {
            replay.location(URI.create(record.getResponseLocation()));
        }
        return Optional.of(replay.body(json.readValue(record.getResponseBody(), type)));
    }

    private void save(String key, String fingerprint, ResponseEntity<?> response) {
        Instant now = clock.instant();
        URI location = response.getHeaders().getLocation();
        records.saveAndFlush(new IdempotencyRecord(
                key, fingerprint, response.getStatusCode().value(),
                json.writeValueAsString(response.getBody()),
                location == null ? null : location.toString(),
                now, now.plus(properties.idempotencyTtl())));
    }

    private static <T> ResponseEntity<T> withReplayedHeader(ResponseEntity<T> response, boolean replayed) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(response.getHeaders());
        headers.set(REPLAYED_HEADER, String.valueOf(replayed));
        return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
    }

    /** SHA-256 of the operation plus the request's JSON: same request, same fingerprint. */
    private String fingerprint(String operation, Object request) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest((operation + "\n" + json.writeValueAsString(request))
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime must support SHA-256", e);
        }
    }
}
```

Read `execute` from the top:

1. **Fingerprint** the request.
2. **Start a transaction** with Spring's `TransactionTemplate`, the programmatic version of `@Transactional`. Everything in the lambda, including whatever `action` does, runs in it. `PaymentService.create` is itself `@Transactional`, and the default propagation (`REQUIRED`) means it **joins** this transaction rather than starting its own. So the payment, the ledger entries and the idempotency record all commit together.
3. **Look for a previous record.** Expired? Delete it and carry on as new. Different fingerprint? Refuse. Otherwise, rebuild the original response from the record and return it, with `Idempotent-Replayed: true`.
4. **No record: do the work**, then `saveAndFlush` the record. The flush sends the `INSERT` immediately, so a primary key clash surfaces here, inside the `try`, rather than later during the commit.
5. **If the insert loses a race**, Spring translates the primary key violation into `DataIntegrityViolationException`. By the time the `catch` runs, the losing transaction has been rolled back, so **none of its work survives**. A fresh transaction reads the winner's record and replays it.

A few more details:

- **`JsonMapper`** is Jackson 3's mapper (package `tools.jackson.databind.json`), which Spring Boot 4 configures as a bean. It serialises the response body for storage and the request for the fingerprint. Jackson 3's methods throw unchecked exceptions, so there's no `try`/`catch` around them.
- **`HexFormat`** (Java 17+) turns the 32-byte hash into 64 hex characters.
- **`withReplayedHeader`** copies the headers because a `ResponseEntity`'s headers are read-only.
- **The replay deserialises into `type`** (`PaymentResponse.class`), so a replayed response goes through Jackson exactly like a fresh one. The `SortCode` round-trips too: Jackson uses the record's single-argument constructor to read back the `"20-00-00"` that `@JsonValue` wrote.

### 6. Use it in the controller

Update `PaymentController`. The fields, constructor and `create` method become:

```java
    private final PaymentService paymentService;
    private final IdempotencyService idempotency;

    public PaymentController(PaymentService paymentService, IdempotencyService idempotency) {
        this.paymentService = paymentService;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 100) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        return idempotency.execute(idempotencyKey, "POST /payments", request, PaymentResponse.class, () -> {
            Payment payment = paymentService.create(
                    request.debtorAccountId(),
                    request.creditor().toCreditor(),
                    Money.gbp(request.amount().toPlainString()),
                    request.reference());
            return ResponseEntity.created(URI.create("/payments/" + payment.getId()))
                    .body(PaymentResponse.from(payment));
        });
    }
```

(Import `IdempotencyService` and `org.springframework.web.bind.annotation.RequestHeader`.) The body of the lambda is exactly what `create` did before. The controller decides **what** the work is; the idempotency service decides **whether** to do it.

`@RequestHeader("Idempotency-Key")` is required by default, so a request without it gets a `400` automatically, as a `ProblemDetail`.

Map the two new failures in `ApiExceptionHandler` (import `IdempotencyKeyReusedException` and `org.springframework.dao.DataIntegrityViolationException`):

```java
    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail handle(IdempotencyKeyReusedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setTitle("Idempotency key reused");
        return problem;
    }

    /** The database's unique constraints are the last line of defence against duplicates. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handle(DataIntegrityViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The request conflicts with data that already exists. Retry it to see the current state.");
        problem.setTitle("Conflict");
        return problem;
    }
```

- A reused key is a `422`: the request breaks a rule, and resending it won't help.
- A constraint violation that gets this far is a `409 Conflict`. The `detail` deliberately doesn't include `ex.getMessage()`, which contains SQL and constraint names. Never leak database internals to clients.

### 7. A surprise in the validation errors

Add the header to the `pay` helper in `PaymentApiTest`, since every payment request now needs one (import `java.util.UUID`):

```java
        return mvc.post().uri("/payments")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
```

Each call is a *different* intended payment, so each gets its own key. Run `PaymentApiTest`. One test fails:

```
[ERROR]   PaymentApiTest.returnsFieldErrorsForABadSortCode:86 [JSON content]
Expecting:
  "{"detail":"Validation failure","instance":"/payments","status":400,"title":"Bad Request"}"
To match JSON path:
  "$.errors['creditor.sortCode']"
```

Still a `400`, but the `errors` map has gone, and the detail now says `"Validation failure"` instead of `"Invalid request content."`.

The cause is the `@Size` on the **header** parameter. Spring MVC (since Spring Framework 6.1) has built-in **method validation**: when a controller method has constraint annotations directly on its parameters, like `@Size` on a `@RequestHeader`, Spring validates the whole method call in one go. Failures, including those from the `@Valid @RequestBody`, are then reported as a `HandlerMethodValidationException`, not the `MethodArgumentNotValidException` your handler customises. Spring's default handling gives a `ProblemDetail` without field details.

Handle that exception too. Add this override to `ApiExceptionHandler`:

```java
    /**
     * Once any controller parameter other than the body has a constraint (like @Size on a header),
     * Spring validates the whole method instead, and reports failures with this exception.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new TreeMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors bodyErrors) {
                for (FieldError error : bodyErrors.getFieldErrors()) {
                    errors.putIfAbsent(error.getField(), error.getDefaultMessage());
                }
            } else {
                String name = result.getMethodParameter().getParameterName();
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    errors.putIfAbsent(name, error.getDefaultMessage());
                }
            }
        }
        ProblemDetail problem = ex.getBody();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }
```

with these imports:

```java
import org.springframework.context.MessageSourceResolvable;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
```

- There's one `ParameterValidationResult` per invalid parameter. A `@Valid` object parameter's result is a **`ParameterErrors`**, which has field errors like `creditor.sortCode`. Anything else (the header) only has plain error messages, which are keyed here by the Java parameter name.
- **`result instanceof ParameterErrors bodyErrors`** is pattern matching for `instanceof` (Java 16+): test and cast in one step.
- `getParameterName()` works because Spring Boot's Maven parent compiles with the `-parameters` flag, which keeps parameter names in the class files.

`PaymentApiTest` passes again, and an overlong key now reports `"errors": {"idempotencyKey": "size must be between 1 and 100"}`.

### 8. Cleaning up expired keys

Records must not pile up forever. Expired ones are already ignored by `findPrevious`; a background job deletes them. Create `idempotency/IdempotencyRecordCleaner.java`:

```java
package com.example.payments.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** Deletes idempotency records once they have expired, so the table doesn't grow forever. */
@Component
public class IdempotencyRecordCleaner {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyRecordCleaner.class);

    private final IdempotencyRecordRepository records;
    private final Clock clock;

    public IdempotencyRecordCleaner(IdempotencyRecordRepository records, Clock clock) {
        this.records = records;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    @Transactional
    public int deleteExpired() {
        int deleted = records.deleteExpired(clock.instant());
        if (deleted > 0) {
            log.info("Deleted {} expired idempotency records", deleted);
        }
        return deleted;
    }
}
```

`@Scheduled(fixedDelayString = "PT1H")` runs the method an hour after the previous run **finished** (unlike `fixedRate`, runs never overlap), starting a minute after start-up. It returns the count so a test can check it.

`@Scheduled` does nothing until scheduling is switched on. Create `config/SchedulingConfig.java`:

```java
package com.example.payments.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs run unless payments.scheduling.enabled=false (the tests turn them off). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "payments.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
```

The condition matters from guide 04 onwards, when background jobs start sending payments. In tests you want **nothing** happening behind your back: each test runs the jobs itself, at exactly the moment it chooses.

### 9. A clock the tests control

Create `src/test/java/com/example/payments/MutableClock.java`:

```java
package com.example.payments;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock that tells the real time, until a test moves it forward. */
public class MutableClock extends Clock {

    private volatile Duration offset = Duration.ZERO;

    public void advance(Duration duration) {
        offset = offset.plus(duration);
    }

    public void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("Always UTC");
    }
}
```

It runs at real time plus an offset, so ordinary tests behave normally, and a test can jump a day ahead. `volatile` makes an `advance` on the test thread visible to other threads straight away.

Register it, `src/test/java/com/example/payments/TestClockConfig.java`:

```java
package com.example.payments;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestClockConfig {

    /** @Primary: wherever the app asks for a Clock, it gets this one. */
    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock();
    }
}
```

There are now two `Clock` beans (`clock` and `testClock`). `@Primary` says which one to inject when a constructor asks for a `Clock`.

Update the meta-annotation, `PaymentsIntegrationTest.java`:

```java
package com.example.payments;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Full application, real Flyway migrations, in-memory H2 (so tests never
 * touch ./data), a MockMvcTester for calling the API, a controllable clock,
 * and no background jobs: tests run them by hand when they want to.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payments-test;DB_CLOSE_DELAY=-1",
        "payments.scheduling.enabled=false"
})
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public @interface PaymentsIntegrationTest {
}
```

Every integration test now gets the same setup, so they still share one cached application context, and one `MutableClock`. That's why any test that moves the clock must put it back afterwards.

### 10. Idempotency tests

Create `src/test/java/com/example/payments/idempotency/IdempotencyTest.java`:

```java
package com.example.payments.idempotency;

import com.example.payments.MutableClock;
import com.example.payments.PaymentsIntegrationTest;
import com.example.payments.ledger.LedgerService;
import com.example.payments.money.Money;
import com.example.payments.payment.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@PaymentsIntegrationTest
class IdempotencyTest {

    @Autowired MockMvcTester mvc;
    @Autowired LedgerService ledger;
    @Autowired PaymentRepository payments;
    @Autowired IdempotencyRecordRepository records;
    @Autowired IdempotencyRecordCleaner cleaner;
    @Autowired MutableClock clock;

    Long alice;

    @BeforeEach
    void openAccount() {
        alice = ledger.openCustomerAccount("Alice").getId();
        ledger.depositCash(alice, Money.gbp("100.00"));
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void aRetryGetsTheOriginalResponseAndPaysOnce() throws Exception {
        String key = UUID.randomUUID().toString();

        MvcTestResult first = pay(key, "25.00");
        MvcTestResult retry = pay(key, "25.00");

        assertThat(first).hasStatus(HttpStatus.CREATED).hasHeader("Idempotent-Replayed", "false");
        assertThat(retry).hasStatus(HttpStatus.CREATED).hasHeader("Idempotent-Replayed", "true");
        assertThat(retry.getResponse().getHeader("Location"))
                .isEqualTo(first.getResponse().getHeader("Location"));
        assertThat(retry).bodyJson().isEqualTo(first.getResponse().getContentAsString());
        assertThat(ledger.get(alice).getBalance()).isEqualTo(Money.gbp("75.00"));
    }

    @Test
    void aRejectedPaymentIsReplayedAsRejected() {
        String key = UUID.randomUUID().toString();
        pay(key, "500.00");
        ledger.depositCash(alice, Money.gbp("1000.00")); // she can afford it now...

        MvcTestResult retry = pay(key, "500.00");

        // ...but a retry is the SAME request, so it gets the same answer
        assertThat(retry).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(ledger.get(alice).getBalance()).isEqualTo(Money.gbp("1100.00"));
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        String key = UUID.randomUUID().toString();
        pay(key, "25.00");

        MvcTestResult different = pay(key, "26.00");

        assertThat(different).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(different).bodyJson().extractingPath("$.title").isEqualTo("Idempotency key reused");
    }

    @Test
    void theHeaderIsRequired() {
        MvcTestResult response = mvc.post().uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("25.00"))
                .exchange();

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.detail")
                .isEqualTo("Required header 'Idempotency-Key' is not present.");
    }

    @Test
    void anOverlongKeyIsAValidationError() {
        MvcTestResult response = pay("k".repeat(101), "25.00");

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors.idempotencyKey")
                .isEqualTo("size must be between 1 and 100");
    }

    @Test
    void aFailedRequestIsNotRemembered() {
        String key = UUID.randomUUID().toString();
        Long cash = ledger.getSystemAccount(LedgerService.CASH).getId();
        MvcTestResult failed = mvc.post().uri("/payments")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(cash, "25.00"))
                .exchange();

        assertThat(failed).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(records.findById(key)).isEmpty();
    }

    @Test
    void anExpiredKeyCountsAsNew() {
        String key = UUID.randomUUID().toString();
        pay(key, "25.00");

        clock.advance(Duration.ofHours(24));
        MvcTestResult later = pay(key, "25.00");

        assertThat(later).hasStatus(HttpStatus.CREATED).hasHeader("Idempotent-Replayed", "false");
        assertThat(ledger.get(alice).getBalance()).isEqualTo(Money.gbp("50.00"));
    }

    @Test
    void theCleanerDeletesExpiredRecords() {
        String key = UUID.randomUUID().toString();
        pay(key, "25.00");

        clock.advance(Duration.ofHours(23));
        cleaner.deleteExpired();
        assertThat(records.findById(key)).isPresent();

        clock.advance(Duration.ofHours(1));
        cleaner.deleteExpired();
        assertThat(records.findById(key)).isEmpty();
    }

    private MvcTestResult pay(String key, String amount) {
        return mvc.post().uri("/payments")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(amount))
                .exchange();
    }

    private String body(String amount) {
        return body(alice, amount);
    }

    private String body(Long debtor, String amount) {
        return """
                {
                  "debtorAccountId": %d,
                  "creditor": {"name": "Bob Smith", "sortCode": "20-00-00", "accountNumber": "55779911"},
                  "amount": "%s",
                  "reference": "Rent"
                }
                """.formatted(debtor, amount);
    }
}
```

The two most important tests:

- **`aRetryGetsTheOriginalResponseAndPaysOnce`** compares the retry with the original byte for byte (`bodyJson().isEqualTo(...)` compares JSON), including the `Location`, and checks Alice was charged once.
- **`aRejectedPaymentIsReplayedAsRejected`** shows that a replay is a *record of what happened*, not a re-evaluation. Alice can afford the payment now, but the retry still says `REJECTED`, because it's the same request and it was rejected. If she wants to try again, that's a new payment with a new key.

`anExpiredKeyCountsAsNew` moves the clock exactly 24 hours. `isExpiredAt` uses `!now.isBefore(expiresAt)`, so a key expires **at** its expiry time, not a moment after. Boundaries like that are where bugs hide, so test them.

### 11. Ten retries at once

The race from the Concepts section. Create `src/test/java/com/example/payments/idempotency/ConcurrentRetryTest.java`:

```java
package com.example.payments.idempotency;

import com.example.payments.PaymentsIntegrationTest;
import com.example.payments.ledger.LedgerService;
import com.example.payments.money.Money;
import com.example.payments.payment.PaymentController;
import com.example.payments.payment.PaymentController.CreatePaymentRequest;
import com.example.payments.payment.PaymentController.CreditorRequest;
import com.example.payments.payment.PaymentController.PaymentResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** A client times out and fires the same request ten times, all at once. */
@PaymentsIntegrationTest
class ConcurrentRetryTest {

    @Autowired PaymentController controller;
    @Autowired LedgerService ledger;

    @Test
    void simultaneousRetriesPayOnce() throws Exception {
        Long alice = ledger.openCustomerAccount("Alice").getId();
        ledger.depositCash(alice, Money.gbp("100.00"));
        String key = UUID.randomUUID().toString();
        CreatePaymentRequest request = new CreatePaymentRequest(alice,
                new CreditorRequest("Bob Smith", "20-00-00", "55779911"), new BigDecimal("25.00"), "Rent");

        CountDownLatch startingGun = new CountDownLatch(1);
        Set<Long> paymentIds = new HashSet<>();
        List<String> replayed = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            List<Future<ResponseEntity<PaymentResponse>>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    return controller.create(key, request);
                }));
            }
            startingGun.countDown();
            for (Future<ResponseEntity<PaymentResponse>> future : futures) {
                ResponseEntity<PaymentResponse> response = future.get();
                paymentIds.add(response.getBody().id());
                replayed.add(response.getHeaders().getFirst(IdempotencyService.REPLAYED_HEADER));
            }
        }

        System.out.println("paymentIds=" + paymentIds + " replayed=" + replayed);
        assertThat(paymentIds).hasSize(1);
        assertThat(replayed).containsOnlyOnce("false");
        assertThat(ledger.get(alice).getBalance()).isEqualTo(Money.gbp("75.00"));
    }
}
```

It calls the controller **bean** directly from ten threads. That's the same code path an HTTP request takes, minus the HTTP plumbing, which isn't what's being tested here. It passes:

```
paymentIds=[1] replayed=[true, true, true, true, true, true, true, true, true, false]
```

One payment, one fresh response, nine replays, and Alice charged £25 once.

Here's what happened inside. All ten looked up the key and found nothing. All ten then called `PaymentService.create`, which queued them on Alice's account lock from guide 02. The first committed its payment **and** its record. Each of the others then got the lock, created a payment of its own, and failed when it tried to insert the same key. Each failure rolled back that duplicate's payment and ledger entries, and replayed the winner's response. The duplicates did wasted work, but none of it survived. (That's the trade-off from the "reserve the key first" sidebar.)

### 12. Try it

Add to `requests.http`, and add an `Idempotency-Key` header to each existing `POST /payments` request (any unique value):

```http
### Alice pays Bob £25, safely retryable. Send it twice.
POST http://localhost:8080/payments
Content-Type: application/json
Idempotency-Key: rent-2026-11

{
  "debtorAccountId": 4,
  "creditor": {"name": "Bob Smith", "sortCode": "20-00-00", "accountNumber": "55779911"},
  "amount": "25.00",
  "reference": "Rent"
}

### Same key, different amount (should be 422)
POST http://localhost:8080/payments
Content-Type: application/json
Idempotency-Key: rent-2026-11

{
  "debtorAccountId": 4,
  "creditor": {"name": "Bob Smith", "sortCode": "20-00-00", "accountNumber": "55779911"},
  "amount": "26.00",
  "reference": "Rent"
}
```

With curl, add `-H 'Idempotency-Key: rent-2026-11'`. On a fresh database, after opening and funding Alice as in guide 02, the first send returns:

```
HTTP/1.1 201
Location: /payments/1
Idempotent-Replayed: false
Content-Type: application/json

{"id":1,"debtorAccountId":4,…,"status":"VALIDATED",…}
```

and the second:

```
HTTP/1.1 201
Idempotent-Replayed: true
Location: /payments/1
Content-Type: application/json

{"id":1,"debtorAccountId":4,…,"status":"VALIDATED",…}
```

with an identical body. Alice's balance is £75.00, not £50.00. The different amount gets:

```json
{"detail":"Idempotency-Key 'rent-2026-11' was already used for a different request","instance":"/payments","status":422,"title":"Idempotency key reused"}
```

and a request with no header:

```json
{"detail":"Required header 'Idempotency-Key' is not present.","instance":"/payments","status":400,"title":"Bad Request"}
```

---

## Your turn

### Exercise 1: idempotent deposits

The service is generic, so prove it. Make `POST /accounts/{id}/deposits` idempotent too, but with an **optional** header: with a key, a retried deposit is credited once; without one, it behaves as before. Add a test to `AccountApiTest` that sends the same deposit twice with the same key and checks the balance went up once.

<details>
<summary>Hint</summary>

`@RequestHeader(name = "Idempotency-Key", required = false)` gives `null` when the header is absent. Put the work in a `Supplier<ResponseEntity<AccountResponse>>` so you can either call it directly or hand it to `execute`. Include the account id in the operation name: a key used to deposit into account 4 shouldn't count as a repeat for account 5.

</details>

<details>
<summary>Solution</summary>

`AccountController` (import `IdempotencyService`, `RequestHeader` and `java.util.function.Supplier`, and inject `IdempotencyService` through the constructor):

```java
    /** Demo helper so you have money to pay with. The Idempotency-Key is optional here. */
    @PostMapping("/{id}/deposits")
    public ResponseEntity<AccountResponse> deposit(
            @PathVariable Long id,
            @RequestHeader(name = "Idempotency-Key", required = false) @Size(min = 1, max = 100) String key,
            @Valid @RequestBody DepositRequest request) {
        Supplier<ResponseEntity<AccountResponse>> action = () -> {
            ledger.depositCash(id, Money.gbp(request.amount().toPlainString()));
            return ResponseEntity.ok(AccountResponse.from(ledger.get(id)));
        };
        if (key == null) {
            return action.get();
        }
        return idempotency.execute(key, "POST /accounts/" + id + "/deposits", request, AccountResponse.class, action);
    }
```

`AccountApiTest`:

```java
    @Test
    void aRetriedDepositIsOnlyCreditedOnce() {
        Long alice = ledger.openCustomerAccount("Alice").getId();

        for (int i = 0; i < 2; i++) {
            mvc.post().uri("/accounts/{id}/deposits", alice)
                    .header("Idempotency-Key", "deposit-" + alice)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"amount": "10.00"}
                            """)
                    .exchange();
        }

        assertThat(ledger.get(alice).getBalance()).isEqualTo(Money.gbp("10.00"));
    }
```

Notice that nothing in `IdempotencyService` changed. A replayed deposit returns the balance **as it was** straight after the original deposit, even if more money has arrived since: a replay is a record, not a fresh read.

</details>

### Exercise 2: the merge trap

In `IdempotencyRecord`, make `isNew()` return `false`, so Spring Data uses `merge` as it would for an ordinary entity with an assigned id. Run `ConcurrentRetryTest`. What happens, and why? Then put it back.

<details>
<summary>Answer</summary>

It fails badly:

```
paymentIds=[16, 7, 8, 9, 10, 11, 12, 13, 14, 15] replayed=[false, false, false, false, false, false, false, false, false, false]
```

Ten payments were created from one intended payment, and every caller was told it was the original.

With `merge`, the duplicate's `saveAndFlush` first `SELECT`s the key. The winner has already committed, so the row is there. `merge` copies the duplicate's values onto it and issues an **`UPDATE`**, which succeeds. No primary key violation, no rollback, no replay: each duplicate's payment commits, and its record overwrites the previous one. (Alice only had £100, so most of those ten payments were recorded as `REJECTED`, but in an API with no funds check they would all have paid.)

The primary key only protects you if you actually `INSERT`. `Persistable.isNew()` is how you tell Spring Data that an object with an id is still new.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 43, Failures: 0, Errors: 0, Skipped: 0
```

New files since guide 02:

```
src/main/java/com/example/payments/
├── config/SchedulingConfig.java
└── idempotency/
    ├── IdempotencyKeyReusedException.java
    ├── IdempotencyRecord.java
    ├── IdempotencyRecordCleaner.java
    ├── IdempotencyRecordRepository.java
    └── IdempotencyService.java
src/main/resources/db/migration/
└── V4__create_idempotency_record.sql
src/test/java/com/example/payments/
├── MutableClock.java
├── TestClockConfig.java
└── idempotency/
    ├── ConcurrentRetryTest.java
    └── IdempotencyTest.java
```

Modified: `PaymentProperties.java`, `PaymentController.java`, `AccountController.java`, `ApiExceptionHandler.java`, `PaymentsIntegrationTest.java`, `PaymentApiTest.java`, `AccountApiTest.java`, `application.properties`, `requests.http`.

---

## Common errors

**`400` with `"detail": "Required header 'Idempotency-Key' is not present."`**
Working as intended. Every `POST /payments` needs the header. In tests, add `.header("Idempotency-Key", UUID.randomUUID().toString())`.

**`400` with `"detail": "Validation failure"` and no `errors` map**
You added a constraint such as `@Size` to the header parameter, so Spring switched to method validation and raised `HandlerMethodValidationException`. Add the `handleHandlerMethodValidationException` override from step 7.

**`422` with `"Idempotency-Key '…' was already used for a different request"`**
The same key with a different body, or for a different endpoint. Generate a new key for each *intended* operation, and reuse it only to retry that exact request. If you edited a request in `requests.http`, change its key too.

**A retry creates a second payment**
Check that `PaymentService.create` is called **inside** the `action` lambda, not before `execute`. Then check `IdempotencyRecord.isNew()` (Exercise 2).

**`No qualifying bean of type 'com.example.payments.MutableClock' available`**
The test class is annotated with `@SpringBootTest` directly instead of `@PaymentsIntegrationTest`, so `TestClockConfig` was never imported.

---

## Recap

- Networks fail **after** the server has done the work, so clients must retry, so **every money-moving `POST` must be idempotent**.
- An **idempotency key** identifies one intended operation. Same key and same request: replay the stored response. Same key and a different request: `422`. Expired key: new request.
- A dedicated **`idempotency_record` table** stores the key, a **SHA-256 fingerprint** of the request and the full **response**, so any endpoint can use it and records can **expire**.
- Write the record **in the same transaction** as the work, with a `TransactionTemplate` that the service's `@Transactional` joins.
- Simultaneous duplicates all do the work, but the **primary key** lets only one commit. The losers roll back and **replay** the winner.
- For assigned ids, implement **`Persistable`** so `save` inserts. A `merge` quietly overwrites and defeats the primary key.
- A constraint on any controller parameter switches Spring to **method validation**: handle `HandlerMethodValidationException` too.
- **Inject a `Clock`** and give tests a mutable one. Turn **scheduling off** in tests and run jobs by hand.

### Interview angle

<details>
<summary>"Design idempotency for a payments API."</summary>

Require a client-generated idempotency key (a UUID) on every creating `POST`. Store a record keyed by (client, key) with a hash of the request and the response status, headers and body, in the **same transaction** as the business change, with an expiry (Open Banking uses 24 hours). On a repeat: same fingerprint, return the stored response and flag it as a replay; different fingerprint, reject with 422. Handle concurrent duplicates with the primary key on the key: losers roll back and replay the winner (or reserve the key first and handle stuck in-progress records). Purge expired records with a scheduled job. Then mention the harder part: idempotency across systems, with unique message ids and idempotent consumers.

</details>

<details>
<summary>"Why store the response rather than just the fact that the key was used?"</summary>

So a retry gets exactly what the original call would have returned, including the created resource's id and `Location`. The client may never have seen the original response, which is why it's retrying. Returning "already done" without the result leaves it unable to continue. Storing the response also means the replay doesn't depend on current state: if the payment has since moved on, or the customer's balance changed, the replay still reports what actually happened to that request.

</details>

<details>
<summary>"Two identical requests arrive at the same moment. Walk me through what your system does."</summary>

Both compute the same fingerprint and find no record. Both do the work in their own transaction (in this system they also queue on the payer's account lock). The first commits its payment and its idempotency record together. The second tries to insert a record with the same key, hits the primary key, and its whole transaction, payment included, rolls back. It catches the violation, reads the committed record in a new transaction, and returns the stored response marked as a replay. One payment, two identical responses.

</details>

### Further reading

- [UK Open Banking Read/Write Data API profile](https://openbankinguk.github.io/read-write-api-site3/v4.0.1/profiles/read-write-data-api-profile.html): the `x-idempotency-key` rules
- [IETF draft: The Idempotency-Key HTTP Header Field](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/)
- [Stripe: Designing robust and predictable APIs with idempotency](https://stripe.com/blog/idempotency)
- [Spring Framework reference: method validation in Spring MVC](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html)
- [Spring Data JPA reference: entity state detection](https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html): how `save` chooses between persist and merge

**Next:** [04: The transactional outbox](04-transactional-outbox.md). Send the payment to the scheme, without losing it or sending it twice.
