# 03: Account-Access Consents

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- What a **consent** is in Open Banking, and why it's a resource in the bank's database rather than something inside a token
- The **permission** codes (`ReadAccountsBasic`, `ReadBalances`, `ReadTransactionsDetail`, …), the rules on combining them, and the idea of **data minimisation**
- The consent **lifecycle**: `AwaitingAuthorisation` → `Authorised`, `Rejected` or `Revoked`, and how to enforce it inside the entity
- What happens during **authorisation**: the customer logs in at the bank, passes SCA, and chooses which accounts to share
- A light version of **idempotent creation** with an `x-idempotency-key` header
- `@ElementCollection` for small sets of values, and why it pairs badly with `open-in-view=false` unless you choose the fetch type deliberately
- Validating **request headers** as well as bodies, and reporting both as field errors in a `ProblemDetail`
- Injecting a `Clock` instead of calling `Instant.now()`

By the end, a TPP can ask for access, the customer can approve or reject it, and either side can withdraw it. The data endpoints don't check consents yet. That's guide 04.

---

## Concepts

### How it works in a real bank

In guide 02 a token with the `accounts` scope could read every customer's data. A scope says "this TPP may use the accounts API". It says nothing about **which customer** agreed to **what**.

That's the job of the **account-access consent**. It's a record, held by the bank, of a specific agreement:

> *Alice* allows *moneywise* to read *the balances and transactions* of *her current account*, until *1 February 2027*.

The journey from guide 01's diagram, in more detail:

1. **The TPP asks.** moneywise calls `POST /account-access-consents` with a **client-credentials** token (one that represents moneywise itself, with no customer involved) and a list of permissions. The bank stores the consent with status `AwaitingAuthorisation` and returns its `ConsentId`.
2. **The customer decides, at the bank.** moneywise sends Alice to her bank's authorisation server (a redirect in the browser, or a jump to the banking app on a phone), passing the `ConsentId`. Alice logs in with **strong customer authentication**, sees what moneywise is asking for, **chooses which of her accounts** to share, and approves or declines. The standard is explicit that the AISP can't pre-select accounts for her: the choice is the customer's, made at the bank.
3. **The bank issues a token linked to the consent.** If Alice approved, the consent becomes `Authorised`, and moneywise gets an access token through the OAuth authorisation code flow. The `ConsentId` is the "intent id" that flow is started with, so the token is tied to that one consent.
4. **Every data request is checked against the consent** (guide 04).

The consent outlives any single token. Tokens expire within minutes or hours and are refreshed. The consent may last months, and it's what the customer sees and manages in their bank's app.

### Permissions, and data minimisation

The TPP asks for **permissions**, not raw database access. The UK standard defines a long list of permission codes. This tutorial implements seven:

| Permission | Lets the TPP read |
|---|---|
| `ReadAccountsBasic` | The list of accounts: type, currency, nickname |
| `ReadAccountsDetail` | The above, **plus** how each account is identified: sort code, account number and holder name |
| `ReadBalances` | Balances |
| `ReadTransactionsBasic` | Transactions, without the descriptive fields |
| `ReadTransactionsDetail` | Transactions, **plus** details such as `TransactionInformation` (the description) |
| `ReadTransactionsCredits` | **Which** transactions: money in |
| `ReadTransactionsDebits` | **Which** transactions: money out |

Two ideas sit behind this design:

- **Basic vs Detail** controls *which fields* the TPP sees. A Detail permission includes everything Basic does.
- **Credits vs Debits** controls *which transactions* the TPP sees. A lender checking income might only need credits.

This is **data minimisation**, a core principle of UK GDPR: a TPP should only get the data it needs for the service the customer signed up to. Sort codes and account numbers are personal data ([PII](../glossary.md#pii)), and a transaction description can reveal a great deal about someone's life.

The standard also has **rules** about which permissions go together. The ones this tutorial enforces, each answered with `400 Bad Request`:

- At least one of `ReadAccountsBasic` and `ReadAccountsDetail` must be requested.
- `ReadTransactionsBasic` or `ReadTransactionsDetail` must come with `ReadTransactionsCredits` and/or `ReadTransactionsDebits`, and vice versa. Asking for "transaction fields" without saying "which transactions", or the other way round, is meaningless.
- An empty list, or an unknown code, is rejected.

### The lifecycle

```
                       customer approves            TPP deletes, or
   POST ──▶ AwaitingAuthorisation ─────────▶ Authorised ─────────────▶ Revoked
                    │                                customer revokes
                    │ customer declines                in the bank's app
                    ▼
                 Rejected
```

| Status | Means | Data access? |
|---|---|---|
| `AwaitingAuthorisation` | The TPP has asked. The customer hasn't decided. | No |
| `Authorised` | The customer approved, and chose accounts | Yes, within the permissions, accounts and expiry |
| `Rejected` | The customer declined | No. Final. |
| `Revoked` | Access was withdrawn | No. Final. |

The rules worth noticing:

- **Only an `AwaitingAuthorisation` consent can be authorised or rejected.** You can't approve a consent twice, or resurrect a rejected one.
- **Revocation is final.** To get access again the TPP creates a *new* consent and the customer approves *that*.
- **An `Authorised` consent can still be unusable**: when its `ExpirationDateTime` has passed. The status doesn't change on expiry. The check happens at request time (guide 04).

There are **two ways to withdraw** access:

- **The TPP deletes it** with `DELETE /account-access-consents/{ConsentId}`. A customer who tells moneywise "stop" expects moneywise to tell the bank, and the standard requires the AISP to delete the consent when that happens.
- **The customer revokes it at the bank.** Banks provide an **access dashboard** in their own app, listing every TPP with access and a button to withdraw each one.

> **Simplification:** the UK standard has changed how it reports revocation across versions. In the v3.1 specification, `DELETE` *removes* the consent resource, `Revoked` described revocation through the bank's own interface, and later v3.1.x versions say `Revoked` no longer applies to newly created consents. This tutorial uses one simple model instead: **both** routes set the status to `Revoked`, and the row is **kept**. Keeping it is a deliberate choice, because "who had access to what, and when did that end" is exactly the kind of [audit trail](../glossary.md#audit-trail) a bank needs.

### Idempotent creation, lightly

What if moneywise's `POST` times out? It can't tell whether the consent was created, so it retries, and now there are two consents awaiting authorisation for one customer journey.

The UK standard's answer for this kind of `POST` is the `x-idempotency-key` header: a unique value the TPP sends, at most 40 characters long. If the bank receives the same key from the same TPP within 24 hours, it must not create another resource, and returns the existing one with `201 Created`. The TPP must not change the request body while reusing a key.

The standard requires `x-idempotency-key` on payment requests, where a duplicate would move money twice. **It doesn't require it on account-access consents.** This tutorial adds it anyway, because a duplicate consent is still a nuisance and it's good practice for the Payments tutorial, which covers [idempotency](../glossary.md#idempotency) in depth. The version here is deliberately light:

- The key is stored on the consent, with a database `UNIQUE (client_id, idempotency_key)` constraint.
- The same key from the same TPP returns the original consent, **even if the body is different**. (A full implementation fingerprints the body and rejects a mismatch. Core ledger guide 04 does that.)
- Keys never expire, and two *simultaneous* duplicates would collide on the unique constraint and fail with an error instead of a clean replay.

### Who calls the authorisation step?

Step 2 of the journey, the customer approving, doesn't happen through the Open Banking API. It happens inside the bank's own authorisation server and login pages, after SCA. Building those is out of scope, so this tutorial stands them in with a small **internal** API:

```
POST /internal/account-access-consents/{consentId}/authorise   {"accountIds": ["acc-1001"]}
POST /internal/account-access-consents/{consentId}/reject
POST /internal/account-access-consents/{consentId}/revoke      (the access dashboard)
```

It's called with a token representing the logged-in **customer**: `sub` is their customer id, and the scope is `psu`. `psu` is this tutorial's own scope, not one from the standard. In a real bank these endpoints wouldn't be exposed to TPPs at all.

---

## Steps

### 1. The migration

Create `src/main/resources/db/migration/V3__create_consents.sql` (back in `db/migration`, because this is schema, not demo data):

```sql
CREATE TABLE account_access_consent (
    id                VARCHAR(40)              PRIMARY KEY,
    client_id         VARCHAR(100)             NOT NULL,
    idempotency_key   VARCHAR(40)              NOT NULL,
    status            VARCHAR(30)              NOT NULL,
    customer_id       VARCHAR(40),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    status_updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_consent_idempotency UNIQUE (client_id, idempotency_key)
);

CREATE TABLE consent_permission (
    consent_id VARCHAR(40) NOT NULL REFERENCES account_access_consent (id),
    permission VARCHAR(40) NOT NULL,
    PRIMARY KEY (consent_id, permission)
);

CREATE TABLE consent_account (
    consent_id VARCHAR(40) NOT NULL REFERENCES account_access_consent (id),
    account_id VARCHAR(40) NOT NULL REFERENCES account (id),
    PRIMARY KEY (consent_id, account_id)
);
```

- **`customer_id` is nullable**: nobody has authorised the consent when it's created.
- **`expires_at` is nullable**: the standard lets a consent be open-ended.
- **The unique constraint is per TPP.** Two different TPPs may happen to pick the same key.
- **Permissions and accounts are child tables**, one row per value. The composite primary keys stop a duplicate.

### 2. Permissions and statuses

`consent/Permission.java`, using the same `@JsonValue` pattern as guide 01's enums:

```java
package com.example.openbanking.consent;

import com.fasterxml.jackson.annotation.JsonValue;

/** The data a customer can let a TPP read. A subset of the UK standard's permission codes. */
public enum Permission {
    READ_ACCOUNTS_BASIC("ReadAccountsBasic"),
    READ_ACCOUNTS_DETAIL("ReadAccountsDetail"),
    READ_BALANCES("ReadBalances"),
    READ_TRANSACTIONS_BASIC("ReadTransactionsBasic"),
    READ_TRANSACTIONS_DETAIL("ReadTransactionsDetail"),
    READ_TRANSACTIONS_CREDITS("ReadTransactionsCredits"),
    READ_TRANSACTIONS_DEBITS("ReadTransactionsDebits");

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
```

Jackson uses the `@JsonValue` method in **both** directions: it writes `"ReadBalances"`, and reading `"ReadBalances"` from a request body gives you `READ_BALANCES`. An unknown code such as `"ReadEverything"` fails to deserialise, which Spring turns into a `400`. That's exactly what the standard asks for.

`consent/ConsentStatus.java`:

```java
package com.example.openbanking.consent;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ConsentStatus {
    /** Created by the TPP. The customer hasn't said yes or no yet. */
    AWAITING_AUTHORISATION("AwaitingAuthorisation"),
    /** The customer said yes. Data can be read. */
    AUTHORISED("Authorised"),
    /** The customer said no. Final. */
    REJECTED("Rejected"),
    /** Access was withdrawn, by the TPP or by the customer. Final. */
    REVOKED("Revoked");

    private final String code;

    ConsentStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
```

### 3. Three exceptions

Each one maps to a different HTTP status, so each is its own type.

`consent/InvalidConsentException.java` (will be a `400`):

```java
package com.example.openbanking.consent;

/** The consent request breaks the rules, so it can never be accepted as it stands. */
public class InvalidConsentException extends RuntimeException {

    public InvalidConsentException(String message) {
        super(message);
    }
}
```

`consent/ConsentStateException.java` (will be a `409`):

```java
package com.example.openbanking.consent;

/** The consent isn't in a status that allows this action. */
public class ConsentStateException extends RuntimeException {

    public ConsentStateException(String consentId, ConsentStatus status, String action) {
        super("Cannot %s consent %s: it is %s".formatted(action, consentId, status.code()));
    }
}
```

`consent/ConsentNotFoundException.java` (will be a `404`):

```java
package com.example.openbanking.consent;

public class ConsentNotFoundException extends RuntimeException {

    public ConsentNotFoundException(String consentId) {
        super("No consent with id " + consentId);
    }
}
```

Why `409` for a wrong status, rather than `422`? Authorising an already-authorised consent isn't a malformed request: it **conflicts with the resource's current state**. Fetch the consent, see its status, and the conflict makes sense. Core ledger guide 04 discusses the `409`/`422` distinction.

### 4. The `Consent` entity, and its state machine

`consent/Consent.java`:

```java
package com.example.openbanking.consent;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.example.openbanking.consent.Permission.READ_ACCOUNTS_BASIC;
import static com.example.openbanking.consent.Permission.READ_ACCOUNTS_DETAIL;
import static com.example.openbanking.consent.Permission.READ_TRANSACTIONS_BASIC;
import static com.example.openbanking.consent.Permission.READ_TRANSACTIONS_CREDITS;
import static com.example.openbanking.consent.Permission.READ_TRANSACTIONS_DEBITS;
import static com.example.openbanking.consent.Permission.READ_TRANSACTIONS_DETAIL;

/**
 * An account-access consent: a TPP's request to read some of a customer's data,
 * and (once the customer agrees) the record of exactly what they agreed to.
 */
@Entity
@Table(name = "account_access_consent")
public class Consent {

    @Id
    private String id;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConsentStatus status;

    /** Who authorised it. Null until the customer does. */
    @Column(name = "customer_id")
    private String customerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "status_updated_at", nullable = false)
    private Instant statusUpdatedAt;

    /** Null means the consent doesn't expire. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "consent_permission", joinColumns = @JoinColumn(name = "consent_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "permission")
    private Set<Permission> permissions = new HashSet<>();

    /** The accounts the customer chose to share. Empty until authorised. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "consent_account", joinColumns = @JoinColumn(name = "consent_id"))
    @Column(name = "account_id")
    private Set<String> accountIds = new HashSet<>();

    protected Consent() {
        // for JPA
    }

    /** A TPP asks for access. The rules on which permissions go together are checked here. */
    public static Consent request(String clientId, String idempotencyKey, Set<Permission> permissions,
                                  Instant expiresAt, Instant now) {
        checkPermissions(permissions);
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new InvalidConsentException("expirationDateTime must be in the future");
        }
        Consent consent = new Consent();
        consent.id = "aac-" + UUID.randomUUID();
        consent.clientId = clientId;
        consent.idempotencyKey = idempotencyKey;
        consent.status = ConsentStatus.AWAITING_AUTHORISATION;
        consent.createdAt = now;
        consent.statusUpdatedAt = now;
        consent.expiresAt = expiresAt;
        consent.permissions = EnumSet.copyOf(permissions);
        return consent;
    }

    private static void checkPermissions(Set<Permission> p) {
        if (p == null || p.isEmpty()) {
            throw new InvalidConsentException("permissions must not be empty");
        }
        if (!p.contains(READ_ACCOUNTS_BASIC) && !p.contains(READ_ACCOUNTS_DETAIL)) {
            throw new InvalidConsentException("permissions must include ReadAccountsBasic or ReadAccountsDetail");
        }
        boolean whichFields = p.contains(READ_TRANSACTIONS_BASIC) || p.contains(READ_TRANSACTIONS_DETAIL);
        boolean whichTransactions = p.contains(READ_TRANSACTIONS_CREDITS) || p.contains(READ_TRANSACTIONS_DEBITS);
        if (whichFields && !whichTransactions) {
            throw new InvalidConsentException(
                    "ReadTransactionsBasic or ReadTransactionsDetail needs ReadTransactionsCredits and/or ReadTransactionsDebits");
        }
        if (whichTransactions && !whichFields) {
            throw new InvalidConsentException(
                    "ReadTransactionsCredits or ReadTransactionsDebits needs ReadTransactionsBasic or ReadTransactionsDetail");
        }
    }

    /** The customer says yes, and chooses which of their accounts to share. */
    public void authorise(String customerId, List<String> accountIds, Instant now) {
        if (status != ConsentStatus.AWAITING_AUTHORISATION) {
            throw new ConsentStateException(id, status, "authorise");
        }
        if (accountIds.isEmpty()) {
            throw new InvalidConsentException("Choose at least one account to share");
        }
        this.customerId = customerId;
        this.accountIds = new HashSet<>(accountIds);
        moveTo(ConsentStatus.AUTHORISED, now);
    }

    /** The customer says no. */
    public void reject(Instant now) {
        if (status != ConsentStatus.AWAITING_AUTHORISATION) {
            throw new ConsentStateException(id, status, "reject");
        }
        moveTo(ConsentStatus.REJECTED, now);
    }

    /**
     * Access is withdrawn. Safe to repeat: revoking a consent that already grants
     * nothing (rejected or revoked) changes nothing.
     */
    public void revoke(Instant now) {
        switch (status) {
            case AWAITING_AUTHORISATION, AUTHORISED -> moveTo(ConsentStatus.REVOKED, now);
            case REJECTED, REVOKED -> { /* nothing to withdraw */ }
        }
    }

    private void moveTo(ConsentStatus next, Instant now) {
        this.status = next;
        this.statusUpdatedAt = now;
    }

    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public String getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public ConsentStatus getStatus() {
        return status;
    }

    public String getCustomerId() {
        return customerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStatusUpdatedAt() {
        return statusUpdatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Set<Permission> getPermissions() {
        return permissions.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions);
    }

    public Set<String> getAccountIds() {
        return Set.copyOf(accountIds);
    }
}
```

This is the heart of the guide. The design points:

- **No setters for the status.** The only ways to change it are `authorise`, `reject` and `revoke`, and each checks the current status first. An illegal transition is impossible to write by accident, anywhere in the codebase. This is a small **state machine** living inside the entity. (The Payments tutorial builds a bigger one.)
- **The time is passed in** (`Instant now`) rather than read inside the entity. That keeps `Consent` a plain object you can unit-test with any time you like, no mocking needed.
- **`revoke` is idempotent.** `DELETE` should be safe to repeat in HTTP, so revoking twice leaves the status and timestamp from the first time. The **switch statement over an enum** with `->` arrows (Java 14+) must list every constant here, which makes it obvious that all four statuses were considered.
- **`isExpiredAt` treats the expiry instant itself as expired** (`!now.isBefore(expiresAt)`). Be precise about boundaries in banking code.
- **The getters return copies.** `EnumSet.copyOf` and `Set.copyOf` stop callers modifying the entity's collections behind its back. (`EnumSet.copyOf` needs a non-empty collection, or an `EnumSet`, which is why the empty case is handled separately.)
- **`"aac-" + UUID`** gives an id of exactly 40 characters, fitting the column. The prefix makes consent ids recognisable in logs.

**`@ElementCollection`** maps a collection of simple values (enums, strings) to a child table, without a separate entity class. `@CollectionTable` names the table and its foreign-key column, and `@Column` names the value column.

**Why `fetch = FetchType.EAGER`?** Element collections are **lazy** by default: Hibernate only loads them when first touched. With `spring.jpa.open-in-view=false`, the database session closes when the service method's transaction ends, so a controller that touches `getPermissions()` afterwards would fail. These sets are tiny and needed on every single use of a consent, so loading them straight away is the right call. For a large or rarely used collection you'd keep it lazy and load it inside the transaction (with `@EntityGraph`, as the core ledger does).

### 5. Unit-test the rules

Plain unit tests, no Spring, no database. `src/test/java/com/example/openbanking/consent/ConsentTest.java`:

```java
package com.example.openbanking.consent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.example.openbanking.consent.Permission.READ_ACCOUNTS_BASIC;
import static com.example.openbanking.consent.Permission.READ_BALANCES;
import static com.example.openbanking.consent.Permission.READ_TRANSACTIONS_CREDITS;
import static com.example.openbanking.consent.Permission.READ_TRANSACTIONS_DETAIL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plain unit tests: no Spring, no database. */
class ConsentTest {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

    @Test
    void aNewConsentAwaitsAuthorisation() {
        Consent consent = request(Set.of(READ_ACCOUNTS_BASIC, READ_BALANCES));

        assertThat(consent.getStatus()).isEqualTo(ConsentStatus.AWAITING_AUTHORISATION);
        assertThat(consent.getId()).startsWith("aac-");
        assertThat(consent.getAccountIds()).isEmpty();
    }

    @Test
    void mustAskForAccountsBasicOrDetail() {
        assertThatThrownBy(() -> request(Set.of(READ_BALANCES)))
                .isInstanceOf(InvalidConsentException.class)
                .hasMessageContaining("ReadAccountsBasic or ReadAccountsDetail");
    }

    @Test
    void transactionDetailNeedsCreditsOrDebits() {
        assertThatThrownBy(() -> request(Set.of(READ_ACCOUNTS_BASIC, READ_TRANSACTIONS_DETAIL)))
                .isInstanceOf(InvalidConsentException.class)
                .hasMessageContaining("needs ReadTransactionsCredits");
    }

    @Test
    void expiryMustBeInTheFuture() {
        assertThatThrownBy(() -> Consent.request("moneywise", "key-1", Set.of(READ_ACCOUNTS_BASIC),
                NOW.minusSeconds(1), NOW))
                .isInstanceOf(InvalidConsentException.class);
    }

    @Test
    void authorisingRecordsTheCustomerAndTheirAccounts() {
        Consent consent = request(Set.of(READ_ACCOUNTS_BASIC, READ_TRANSACTIONS_DETAIL, READ_TRANSACTIONS_CREDITS));

        consent.authorise("alice", List.of("acc-1001"), NOW.plusSeconds(30));

        assertThat(consent.getStatus()).isEqualTo(ConsentStatus.AUTHORISED);
        assertThat(consent.getCustomerId()).isEqualTo("alice");
        assertThat(consent.getAccountIds()).containsExactly("acc-1001");
        assertThat(consent.getStatusUpdatedAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void cannotAuthoriseARejectedConsent() {
        Consent consent = request(Set.of(READ_ACCOUNTS_BASIC));
        consent.reject(NOW);

        assertThatThrownBy(() -> consent.authorise("alice", List.of("acc-1001"), NOW))
                .isInstanceOf(ConsentStateException.class)
                .hasMessageContaining("it is Rejected");
    }

    @Test
    void revokingTwiceIsHarmless() {
        Consent consent = request(Set.of(READ_ACCOUNTS_BASIC));
        consent.authorise("alice", List.of("acc-1001"), NOW);

        consent.revoke(NOW.plusSeconds(1));
        consent.revoke(NOW.plusSeconds(2));

        assertThat(consent.getStatus()).isEqualTo(ConsentStatus.REVOKED);
        assertThat(consent.getStatusUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void expiresAtExactlyItsExpirationTime() {
        Consent consent = Consent.request("moneywise", "key-1", Set.of(READ_ACCOUNTS_BASIC),
                NOW.plus(Duration.ofDays(90)), NOW);

        assertThat(consent.isExpiredAt(NOW.plus(Duration.ofDays(90)).minusMillis(1))).isFalse();
        assertThat(consent.isExpiredAt(NOW.plus(Duration.ofDays(90)))).isTrue();
    }

    private static Consent request(Set<Permission> permissions) {
        return Consent.request("moneywise", "key-1", permissions, null, NOW);
    }
}
```

These run in milliseconds. A fixed `NOW` makes every timestamp assertion exact. Run them now: eight green ticks before any Spring code exists.

### 6. A `Clock`, the repository and the service

Create `config/ClockConfig.java`:

```java
package com.example.openbanking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** Inject this instead of calling Instant.now(), so time can be controlled in tests. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

`Instant.now()` is a hidden dependency on the system clock. Code that asks a `Clock` for the time can be given a different clock in tests. You'll cash that in in guide 04, to test expiry without waiting 90 days.

`consent/ConsentRepository.java`:

```java
package com.example.openbanking.consent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConsentRepository extends JpaRepository<Consent, String> {

    Optional<Consent> findByClientIdAndIdempotencyKey(String clientId, String idempotencyKey);
}
```

`consent/ConsentService.java`:

```java
package com.example.openbanking.consent;

import com.example.openbanking.account.Account;
import com.example.openbanking.account.AccountService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

@Service
public class ConsentService {

    private final ConsentRepository consents;
    private final AccountService accountService;
    private final Clock clock;

    public ConsentService(ConsentRepository consents, AccountService accountService, Clock clock) {
        this.consents = consents;
        this.accountService = accountService;
        this.clock = clock;
    }

    /** A TPP asks for access. Repeating a request with the same idempotency key returns the original. */
    @Transactional
    public Consent create(String clientId, String idempotencyKey, Set<Permission> permissions, Instant expiresAt) {
        return consents.findByClientIdAndIdempotencyKey(clientId, idempotencyKey)
                .orElseGet(() -> consents.save(
                        Consent.request(clientId, idempotencyKey, permissions, expiresAt, now())));
    }

    /** A TPP may only see its own consents. Anyone else's look exactly like missing ones. */
    @Transactional(readOnly = true)
    public Consent getForClient(String consentId, String clientId) {
        return consents.findById(consentId)
                .filter(c -> c.getClientId().equals(clientId))
                .orElseThrow(() -> new ConsentNotFoundException(consentId));
    }

    /** The TPP withdraws the consent (DELETE). */
    @Transactional
    public void revokeByClient(String consentId, String clientId) {
        getForClient(consentId, clientId).revoke(now());
    }

    /** The customer, logged in at the bank, says yes and picks the accounts to share. */
    @Transactional
    public Consent authorise(String consentId, String customerId, List<String> accountIds) {
        Consent consent = consents.findById(consentId).orElseThrow(() -> new ConsentNotFoundException(consentId));
        for (String accountId : accountIds) {
            Account account = accountService.get(accountId);
            if (!account.getCustomerId().equals(customerId)) {
                throw new InvalidConsentException("Account %s does not belong to the customer".formatted(accountId));
            }
        }
        consent.authorise(customerId, accountIds, now());
        return consent;
    }

    /** The customer says no. */
    @Transactional
    public Consent reject(String consentId) {
        Consent consent = consents.findById(consentId).orElseThrow(() -> new ConsentNotFoundException(consentId));
        consent.reject(now());
        return consent;
    }

    /** The customer withdraws access from the bank's own app (the "access dashboard"). */
    @Transactional
    public Consent revokeByCustomer(String consentId, String customerId) {
        Consent consent = consents.findById(consentId)
                .filter(c -> customerId.equals(c.getCustomerId()))
                .orElseThrow(() -> new ConsentNotFoundException(consentId));
        consent.revoke(now());
        return consent;
    }

    /** The database keeps microseconds, so drop the nanoseconds now: a replay must match the original exactly. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
```

What to notice:

- **The service never sets a status.** It loads the consent, calls a domain method, and lets Hibernate's dirty checking write the change when the transaction commits. No `save` is needed for an entity that's already managed.
- **Another TPP's consent is reported as "not found"**, never "forbidden". A `403` would confirm that the id exists, which is information snoopco has no right to.
- **Account ownership is checked by the bank, not trusted from the request.** The customer id comes from the token (step 8), and each chosen account must belong to that customer.
- **`now()` truncates to microseconds**, the precision of `TIMESTAMP WITH TIME ZONE`. The test in step 9 explains why.

> **Sidebar: "find or create" and races.** `create` checks for an existing key and then inserts. Two identical requests arriving at the same instant could both find nothing and both insert. The `uk_consent_idempotency` constraint makes sure only one row survives; the loser's transaction fails instead of creating a duplicate. A full implementation would turn that failure into a clean replay. Core ledger guide 04 shows the "lock, then check, with a unique constraint as backstop" pattern.

### 7. The TPP's endpoints

The create response's `self` link should point at the **new** consent, not at the collection the `POST` went to. Add an overload to `api/ObResponse.java` (and `import java.net.URI;`):

```java
    /** Wraps a payload whose self link isn't the current request, such as a newly created resource. */
    public static <T> ObResponse<T> of(T data, URI self) {
        return new ObResponse<>(data, new Links(self.toString()), new Meta(1));
    }
```

`consent/ConsentResponse.java`:

```java
package com.example.openbanking.consent;

import java.time.Instant;
import java.util.Set;

public record ConsentResponse(String consentId, ConsentStatus status, Instant creationDateTime,
                              Instant statusUpdateDateTime, Set<Permission> permissions,
                              Instant expirationDateTime) {

    static ConsentResponse from(Consent consent) {
        return new ConsentResponse(consent.getId(), consent.getStatus(), consent.getCreatedAt(),
                consent.getStatusUpdatedAt(), consent.getPermissions(), consent.getExpiresAt());
    }
}
```

The response deliberately leaves out which accounts were chosen and who the customer is. The TPP finds out which accounts it can see by calling `GET /accounts` with its consent-linked token.

`consent/ConsentController.java`:

```java
package com.example.openbanking.consent;

import com.example.openbanking.api.ObResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.Set;

/** The TPP's side of consents. Called with a client-credentials token. */
@RestController
@RequestMapping("/open-banking/v1/aisp/account-access-consents")
public class ConsentController {

    private final ConsentService consentService;

    public ConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    @PostMapping
    public ResponseEntity<ObResponse<ConsentResponse>> create(
            @AuthenticationPrincipal Jwt token,
            @RequestHeader("x-idempotency-key") @NotBlank @Size(max = 40) String idempotencyKey,
            @Valid @RequestBody CreateConsentRequest request) {
        Consent consent = consentService.create(clientId(token), idempotencyKey,
                request.data().permissions(), request.data().expirationDateTime());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{consentId}")
                .buildAndExpand(consent.getId())
                .toUri();
        return ResponseEntity.created(location).body(ObResponse.of(ConsentResponse.from(consent), location));
    }

    @GetMapping("/{consentId}")
    public ObResponse<ConsentResponse> get(@AuthenticationPrincipal Jwt token, @PathVariable String consentId) {
        return ObResponse.of(ConsentResponse.from(consentService.getForClient(consentId, clientId(token))));
    }

    @DeleteMapping("/{consentId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt token, @PathVariable String consentId) {
        consentService.revokeByClient(consentId, clientId(token));
        return ResponseEntity.noContent().build();
    }

    private static String clientId(Jwt token) {
        return token.getClaimAsString("client_id");
    }

    public record CreateConsentRequest(@NotNull @Valid Data data) {

        public record Data(@NotEmpty Set<Permission> permissions, Instant expirationDateTime) {
        }
    }
}
```

- **`@AuthenticationPrincipal Jwt token`** injects the decoded access token. The TPP's identity comes from its `client_id` claim: never from the request body, where a TPP could claim to be someone else.
- **The request body is wrapped in `data`**, like the standard's `Data`. (The standard's request also has a mandatory `Risk` object, which is empty for account access. It's left out here.) `@Valid` on the `data` component makes validation cascade into the nested record.
- **`@RequestHeader("x-idempotency-key") @NotBlank @Size(max = 40)`**: constraints directly on a controller method parameter. Spring Framework 6.1 and later validate these automatically, without `@Validated` on the class.
- **`DELETE` returns `204 No Content`**, as the standard specifies.

### 8. The customer's endpoints

`consent/PsuConsentController.java`:

```java
package com.example.openbanking.consent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Stands in for the bank's own consent screens. In a real bank this happens inside
 * the authorisation server, after the customer has logged in with strong customer
 * authentication. It is not part of the Open Banking API. The token's subject is
 * the logged-in customer.
 */
@RestController
@RequestMapping("/internal/account-access-consents/{consentId}")
public class PsuConsentController {

    private final ConsentService consentService;

    public PsuConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    @PostMapping("/authorise")
    public ConsentResponse authorise(@AuthenticationPrincipal Jwt customer, @PathVariable String consentId,
                                     @Valid @RequestBody AuthoriseRequest request) {
        return ConsentResponse.from(consentService.authorise(consentId, customer.getSubject(), request.accountIds()));
    }

    @PostMapping("/reject")
    public ConsentResponse reject(@PathVariable String consentId) {
        return ConsentResponse.from(consentService.reject(consentId));
    }

    @PostMapping("/revoke")
    public ConsentResponse revoke(@AuthenticationPrincipal Jwt customer, @PathVariable String consentId) {
        return ConsentResponse.from(consentService.revokeByCustomer(consentId, customer.getSubject()));
    }

    public record AuthoriseRequest(@NotEmpty List<String> accountIds) {
    }
}
```

The customer's identity is the token's `sub`. These responses aren't wrapped in the Open Banking envelope, because this isn't the Open Banking API.

Protect it in `SecurityConfig`, with a new rule after the `/open-banking/**` one:

```java
                        .requestMatchers("/open-banking/**").hasAuthority("SCOPE_accounts")
                        .requestMatchers("/internal/**").hasAuthority("SCOPE_psu")
```

A TPP's `accounts` token can now **create** a consent but never **approve** one. Only a customer's token can.

### 9. Errors, including header validation

Map the three new exceptions in `web/ApiExceptionHandler.java` (import them from `com.example.openbanking.consent`):

```java
    @ExceptionHandler(ConsentNotFoundException.class)
    ProblemDetail handle(ConsentNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Consent not found");
        return problem;
    }

    @ExceptionHandler(InvalidConsentException.class)
    ProblemDetail handle(InvalidConsentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid consent request");
        return problem;
    }

    @ExceptionHandler(ConsentStateException.class)
    ProblemDetail handle(ConsentStateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Consent status conflict");
        return problem;
    }
```

Now try a bad request: an empty permission list *and* a 41-character idempotency key. Without any more code, you get:

```json
{"detail":"Validation failure","instance":"/open-banking/v1/aisp/account-access-consents","status":400,"title":"Bad Request"}
```

"Validation failure" of *what*? Spring has two validation paths, and this request took the less obvious one:

- When only a `@Valid @RequestBody` fails, Spring raises **`MethodArgumentNotValidException`** (detail: `Invalid request content.`).
- When a controller method has constraints on **other** parameters too (here, the header), Spring validates the **whole method** at once and raises **`HandlerMethodValidationException`** instead, with results for every parameter, body included.

Handle both, and list every problem by name. Add these overrides at the top of `ApiExceptionHandler`:

```java
    /** @Valid on a request body failed. */
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

    /**
     * Constraints on other parameters (such as a header) failed too. Spring then
     * validates the whole method at once and reports everything here instead.
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
                RequestHeader header = result.getMethodParameter().getParameterAnnotation(RequestHeader.class);
                String name = header != null ? header.value() : result.getMethodParameter().getParameterName();
                result.getResolvableErrors().forEach(error -> errors.putIfAbsent(name, error.getDefaultMessage()));
            }
        }
        ProblemDetail problem = ex.getBody();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }
```

The imports this needs:

```java
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.Map;
import java.util.TreeMap;
```

- **`result instanceof ParameterErrors bodyErrors`** is pattern matching for `instanceof` (Java 16+): test and cast in one step. A `ParameterErrors` is the result for an object validated with `@Valid`, carrying ordinary field errors.
- **For a header, the name comes from its `@RequestHeader`**, so the client sees `x-idempotency-key`, the name it actually sent, rather than the Java parameter name.

The same request now says exactly what's wrong:

```json
{"detail":"Validation failure","instance":"/open-banking/v1/aisp/account-access-consents","status":400,"title":"Bad Request","errors":{"data.permissions":"must not be empty","x-idempotency-key":"size must be between 0 and 40"}}
```

### 10. API tests

Add two token helpers to `TestTokens`:

```java
    /** A TPP's client-credentials token: the TPP acting for itself, with no customer involved. */
    public static JwtRequestPostProcessor client(String clientId) {
        return jwt().jwt(token -> token
                .subject(clientId)
                .claim("client_id", clientId)
                .claim("scope", "accounts"));
    }

    /** A customer logged in to the bank's own consent screens. */
    public static JwtRequestPostProcessor psu(String customerId) {
        return jwt().jwt(token -> token
                .subject(customerId)
                .claim("client_id", "bank-consent-app")
                .claim("scope", "psu"));
    }
```

`src/test/java/com/example/openbanking/consent/ConsentApiTest.java`:

```java
package com.example.openbanking.consent;

import com.example.openbanking.OpenBankingIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.example.openbanking.TestTokens.client;
import static org.assertj.core.api.Assertions.assertThat;

@OpenBankingIntegrationTest
class ConsentApiTest {

    static final String CONSENTS = "/open-banking/v1/aisp/account-access-consents";
    static final String BALANCES_ONLY = """
            {"data": {"permissions": ["ReadAccountsBasic", "ReadBalances"]}}
            """;

    @Autowired
    MockMvcTester mvc;

    @Test
    void createsAConsentAwaitingAuthorisation() {
        MvcTestResult response = create("moneywise", UUID.randomUUID().toString(), BALANCES_ONLY);

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).headers().containsHeader("Location");
        assertThat(response).bodyJson().extractingPath("$.data.status").isEqualTo("AwaitingAuthorisation");
        assertThat(response).bodyJson().extractingPath("$.data.permissions")
                .asArray().containsExactly("ReadAccountsBasic", "ReadBalances");
    }

    @Test
    void repeatingARequestWithTheSameIdempotencyKeyReturnsTheSameConsent() throws Exception {
        String key = UUID.randomUUID().toString();

        MvcTestResult first = create("moneywise", key, BALANCES_ONLY);
        MvcTestResult retry = create("moneywise", key, BALANCES_ONLY);

        assertThat(retry).hasStatus(HttpStatus.CREATED);
        assertThat(retry).bodyJson().extractingPath("$.data.consentId").isEqualTo(consentIdOf(first));
        assertThat(retry.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void theIdempotencyKeyIsRequired() {
        MvcTestResult response = mvc.post().uri(CONSENTS)
                .with(client("moneywise"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BALANCES_ONLY)
                .exchange();

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.detail")
                .isEqualTo("Required header 'x-idempotency-key' is not present.");
    }

    @Test
    void anUnknownPermissionIs400() {
        MvcTestResult response = create("moneywise", UUID.randomUUID().toString(), """
                {"data": {"permissions": ["ReadAccountsBasic", "ReadEverything"]}}
                """);

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void permissionsThatDoNotGoTogetherAre400() {
        MvcTestResult response = create("moneywise", UUID.randomUUID().toString(), """
                {"data": {"permissions": ["ReadAccountsBasic", "ReadTransactionsCredits"]}}
                """);

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Invalid consent request");
    }

    @Test
    void aTppCannotSeeAnotherTppsConsent() throws Exception {
        String consentId = consentIdOf(create("moneywise", UUID.randomUUID().toString(), BALANCES_ONLY));

        MvcTestResult response = mvc.get().uri(CONSENTS + "/" + consentId).with(client("snoopco")).exchange();

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void deletingAConsentRevokesIt() throws Exception {
        String consentId = consentIdOf(create("moneywise", UUID.randomUUID().toString(), BALANCES_ONLY));

        assertThat(mvc.delete().uri(CONSENTS + "/" + consentId).with(client("moneywise")))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(mvc.get().uri(CONSENTS + "/" + consentId).with(client("moneywise")))
                .bodyJson().extractingPath("$.data.status").isEqualTo("Revoked");
    }

    @Test
    void anIdempotencyKeyOver40CharactersIs400() {
        MvcTestResult response = create("moneywise", "x".repeat(41), BALANCES_ONLY);

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors.x-idempotency-key")
                .isEqualTo("size must be between 0 and 40");
    }

    MvcTestResult create(String clientId, String idempotencyKey, String body) {
        return mvc.post().uri(CONSENTS)
                .with(client(clientId))
                .header("x-idempotency-key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    static String consentIdOf(MvcTestResult response) throws Exception {
        return JsonPath.read(response.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.consentId");
    }
}
```

Every test uses a **fresh random key**, so tests never collide with each other's consents in the shared in-memory database.

**The replay test compares the two responses byte for byte**, and it's the reason for `truncatedTo(ChronoUnit.MICROS)` in the service. Remove the truncation and run it:

```
expected: "{"data":{"consentId":"aac-97617340-…","status":"AwaitingAuthorisation","creationDateTime":"2026-11-06T23:10:58.589034664Z",…
 but was: "{"data":{"consentId":"aac-97617340-…","status":"AwaitingAuthorisation","creationDateTime":"2026-11-06T23:10:58.589035Z",…
```

The first response is built from the entity in memory, with Java's nanosecond timestamp. The replay is loaded from the database, which stores **microseconds** (and H2 *rounded* `…034664` up to `…035`). Same consent, two different `creationDateTime`s: a client comparing them would think they differ. Truncating before saving means the value in memory is exactly what the database will hand back. Put the truncation back.

`src/test/java/com/example/openbanking/consent/PsuConsentApiTest.java`:

```java
package com.example.openbanking.consent;

import com.example.openbanking.OpenBankingIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.Set;
import java.util.UUID;

import static com.example.openbanking.TestTokens.client;
import static com.example.openbanking.TestTokens.psu;
import static org.assertj.core.api.Assertions.assertThat;

@OpenBankingIntegrationTest
class PsuConsentApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ConsentService consentService;

    String consentId;

    @BeforeEach
    void tppAsksForAccess() {
        consentId = consentService.create("moneywise", UUID.randomUUID().toString(),
                Set.of(Permission.READ_ACCOUNTS_BASIC, Permission.READ_BALANCES), null).getId();
    }

    @Test
    void theCustomerAuthorisesAndChoosesAccounts() {
        MvcTestResult response = authorise("alice", "acc-1001");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.status").isEqualTo("Authorised");
        assertThat(consentService.getForClient(consentId, "moneywise").getAccountIds()).containsExactly("acc-1001");
    }

    @Test
    void aCustomerCannotShareSomeoneElsesAccount() {
        MvcTestResult response = authorise("alice", "acc-2001");

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.detail")
                .isEqualTo("Account acc-2001 does not belong to the customer");
    }

    @Test
    void aConsentCanOnlyBeAuthorisedOnce() {
        authorise("alice", "acc-1001");

        MvcTestResult again = authorise("alice", "acc-1002");

        assertThat(again).hasStatus(HttpStatus.CONFLICT);
        assertThat(again).bodyJson().extractingPath("$.title").isEqualTo("Consent status conflict");
    }

    @Test
    void aTppTokenCannotAuthoriseOnTheCustomersBehalf() {
        MvcTestResult response = mvc.post().uri("/internal/account-access-consents/{id}/authorise", consentId)
                .with(client("moneywise"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accountIds": ["acc-1001"]}
                        """)
                .exchange();

        assertThat(response).hasStatus(HttpStatus.FORBIDDEN);
    }

    MvcTestResult authorise(String customerId, String accountId) {
        return mvc.post().uri("/internal/account-access-consents/{id}/authorise", consentId)
                .with(psu(customerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accountIds": ["%s"]}
                        """.formatted(accountId))
                .exchange();
    }
}
```

The `@BeforeEach` creates its consent through the **service**, not the API. The API for creating consents has its own tests. Here it's only setup, and calling the service is shorter and clearer.

### 11. Try the journey by hand

Add to `requests.http`:

```http
### moneywise asks for access (client-credentials token from the guide 02 request)
POST http://localhost:8080/open-banking/v1/aisp/account-access-consents
Authorization: Bearer {{tppToken}}
x-idempotency-key: 5f0c7e0a-1d1b-4a39-9a5e-1c2f8b6b9e01
Content-Type: application/json

{"data": {"permissions": ["ReadAccountsDetail", "ReadBalances", "ReadTransactionsDetail",
                          "ReadTransactionsCredits", "ReadTransactionsDebits"],
          "expirationDateTime": "2027-02-01T00:00:00Z"}}

> {% client.global.set("consentId", response.body.data.consentId); %}

### Alice logs in at the bank (dev token for the customer)
POST http://localhost:8080/dev/tokens
Content-Type: application/json

{"clientId": "bank-consent-app", "subject": "alice", "scope": "psu"}

> {% client.global.set("aliceToken", response.body.access_token); %}

### Alice approves, sharing only her current account
POST http://localhost:8080/internal/account-access-consents/{{consentId}}/authorise
Authorization: Bearer {{aliceToken}}
Content-Type: application/json

{"accountIds": ["acc-1001"]}

### moneywise checks the consent
GET http://localhost:8080/open-banking/v1/aisp/account-access-consents/{{consentId}}
Authorization: Bearer {{tppToken}}

### moneywise deletes it
DELETE http://localhost:8080/open-banking/v1/aisp/account-access-consents/{{consentId}}
Authorization: Bearer {{tppToken}}
```

The create response:

```
HTTP/1.1 201
Location: http://localhost:8080/open-banking/v1/aisp/account-access-consents/aac-506da8e2-23ba-4f8f-aca0-1b27b64f09ec
```

```json
{
  "data": {
    "consentId": "aac-506da8e2-23ba-4f8f-aca0-1b27b64f09ec",
    "status": "AwaitingAuthorisation",
    "creationDateTime": "2026-11-06T23:09:24.500519Z",
    "statusUpdateDateTime": "2026-11-06T23:09:24.500519Z",
    "permissions": ["ReadAccountsDetail", "ReadBalances", "ReadTransactionsDetail",
                    "ReadTransactionsCredits", "ReadTransactionsDebits"],
    "expirationDateTime": "2027-02-01T00:00:00Z"
  },
  "links": { "self": "http://localhost:8080/open-banking/v1/aisp/account-access-consents/aac-506da8e2-23ba-4f8f-aca0-1b27b64f09ec" },
  "meta": { "totalPages": 1 }
}
```

Send the same `POST` again, unchanged: same `consentId`, still `201`. Approve it, then approve it again:

```json
{"detail":"Cannot authorise consent aac-506da8e2-23ba-4f8f-aca0-1b27b64f09ec: it is Authorised","instance":"/internal/account-access-consents/aac-506da8e2-23ba-4f8f-aca0-1b27b64f09ec/authorise","status":409,"title":"Consent status conflict"}
```

**With curl**, follow the same order, keeping each value in a shell variable:

```bash
TPP=$(curl -s -X POST localhost:8080/dev/tokens -H 'Content-Type: application/json' \
      -d '{"clientId":"moneywise","scope":"accounts"}' | jq -r .access_token)
curl -s -X POST localhost:8080/open-banking/v1/aisp/account-access-consents \
     -H "Authorization: Bearer $TPP" -H 'x-idempotency-key: k-001' -H 'Content-Type: application/json' \
     -d '{"data":{"permissions":["ReadAccountsBasic","ReadBalances"]}}'
```

(`jq` is a small JSON command-line tool. Without it, copy the `access_token` value by hand.)

Now the uncomfortable part. With the consent `Authorised` for `acc-1001` only, call `GET /open-banking/v1/aisp/accounts/acc-2001/balances` with moneywise's token. **Bob's balance comes back.** The consent exists, but nothing checks it yet.

---

## Your turn

### Exercise 1: rejected means rejected

Add a test to `PsuConsentApiTest`: the customer rejects the consent (check the response says `Rejected`), and a later attempt to authorise it gets a `409`.

<details>
<summary>Solution</summary>

```java
@Test
void aRejectedConsentCannotBeAuthorisedAfterwards() {
    assertThat(mvc.post().uri("/internal/account-access-consents/{id}/reject", consentId).with(psu("alice")))
            .bodyJson().extractingPath("$.status").isEqualTo("Rejected");

    assertThat(authorise("alice", "acc-1001")).hasStatus(HttpStatus.CONFLICT);
}
```

</details>

### Exercise 2: the access dashboard

Alice authorises a consent. Prove that **Bob** can't revoke it from his banking app (he should get a `404`, as if it didn't exist), but Alice can.

<details>
<summary>Solution</summary>

```java
@Test
void onlyTheCustomerWhoAuthorisedItCanRevokeIt() {
    authorise("alice", "acc-1001");

    assertThat(mvc.post().uri("/internal/account-access-consents/{id}/revoke", consentId).with(psu("bob")))
            .hasStatus(HttpStatus.NOT_FOUND);
    assertThat(mvc.post().uri("/internal/account-access-consents/{id}/revoke", consentId).with(psu("alice")))
            .bodyJson().extractingPath("$.status").isEqualTo("Revoked");
}
```

`revokeByCustomer` filters on `customerId`, so Bob's attempt finds nothing. Notice that `reject` has no such check: before authorisation, a consent isn't bound to any customer yet. In a real bank the rejection happens inside the customer's own logged-in session, which ties it to them.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 44, Failures: 0, Errors: 0, Skipped: 0
```

(22 from guide 02, plus 8 in `ConsentTest`, 8 in `ConsentApiTest` and 6 in `PsuConsentApiTest`, if you did both exercises.)

New files since guide 02:

```
src/main/java/com/example/openbanking/
├── config/ClockConfig.java
└── consent/
    ├── Consent.java
    ├── ConsentController.java
    ├── ConsentNotFoundException.java
    ├── ConsentRepository.java
    ├── ConsentResponse.java
    ├── ConsentService.java
    ├── ConsentStateException.java
    ├── ConsentStatus.java
    ├── InvalidConsentException.java
    ├── Permission.java
    └── PsuConsentController.java
src/main/resources/db/migration/
└── V3__create_consents.sql
src/test/java/com/example/openbanking/consent/
├── ConsentApiTest.java
├── ConsentTest.java
└── PsuConsentApiTest.java
```

Modified: `ObResponse.java`, `SecurityConfig.java`, `ApiExceptionHandler.java`, `TestTokens.java`, `requests.http`.

---

## Common errors

**`LazyInitializationException: Cannot lazily initialize collection of role 'com.example.openbanking.consent.Consent.permissions' with key 'aac-…' (no session)`**
The `@ElementCollection` is lazy (the default) and the controller touched it after the service's transaction ended. Use `fetch = FetchType.EAGER` for small, always-needed sets, or load the collection inside the transaction.

**`400` with `"detail": "Validation failure"` and no `errors`**
You haven't overridden `handleHandlerMethodValidationException`. The header constraint makes Spring validate the whole method, which skips `handleMethodArgumentNotValid`. See step 9.

**`400` with `"detail": "Required header 'x-idempotency-key' is not present."`**
Working as intended. Every consent `POST` needs the header.

**`400` with `"detail": "Failed to read request"`**
The JSON couldn't be turned into the request record. Most often a permission code that doesn't exist (check the spelling and capitals: `ReadBalances`, not `READ_BALANCES` or `readBalances`).

**`403` calling `/internal/...` with a TPP token**
Working as intended. Only a `psu` token can approve consents.

**`409`: `Cannot authorise consent aac-…: it is Authorised`**
Working as intended: a consent can only be authorised once. Create a new one.

**The idempotent replay test fails with timestamps that differ only in their last digits**
`now()` isn't truncating to microseconds, so the first response has nanoseconds and the replay has the database's microseconds. See step 10.

**A missing-table error at start-up mentioning `account_access_consent`, `consent_permission` or `consent_account`**
Flyway didn't find `V3`. Check it's in `src/main/resources/db/migration/` (not `db/demo`) and that the name has a double underscore: `V3__create_consents.sql`.

---

## Recap

- A **consent** is the bank's record of what a customer allowed a TPP to read: **permissions**, **accounts**, **expiry**. It lives in the bank's database, not in a token.
- The **TPP** creates it (client-credentials token). The **customer** authorises it at the bank, after SCA, and **chooses the accounts**.
- **Permissions** control which data (Basic vs Detail fields, Credits vs Debits transactions). That's **data minimisation**. Invalid combinations are `400`.
- The **lifecycle** (`AwaitingAuthorisation` → `Authorised`/`Rejected` → `Revoked`) is enforced by **domain methods on the entity**, with no status setter. A wrong transition is a `409`.
- Another TPP's consent is a **`404`**, never a `403`: don't confirm what exists.
- **`x-idempotency-key`** makes creation safe to retry: store the key with the result, behind a unique constraint.
- With method-level constraints, Spring raises **`HandlerMethodValidationException`**: handle it to report header and body errors together.
- Inject a **`Clock`**, and **truncate timestamps** to the database's precision when exact round trips matter.

### Interview angle

<details>
<summary>"What's the difference between an OAuth scope and an Open Banking consent?"</summary>

A scope is coarse and part of the token: `accounts` means "this client may use the account information API". A consent is fine-grained and held by the bank: this customer allowed this TPP to read these permissions on these accounts until this date. The token is linked to the consent (the consent id is the intent id the authorisation flow starts with), and every request is checked against the consent's current state. That lets the customer or TPP revoke access instantly, which a self-contained token can't support.

</details>

<details>
<summary>"How would you model a status lifecycle so illegal transitions can't happen?"</summary>

Keep the status private with no setter, and expose intention-revealing methods (`authorise`, `reject`, `revoke`) on the entity that check the current state before changing it and throw a specific exception otherwise. Final states stay final. Map the exception to `409 Conflict`. Unit-test the transitions without Spring. In larger systems the same idea becomes an explicit state machine (sealed types, a transition table, or a library), but the principle is the same: the domain object guards its own invariants.

</details>

<details>
<summary>"Why might a bank return 404 rather than 403 for someone else's resource?"</summary>

A `403` confirms the resource exists, which leaks information: ids could be probed to learn how many consents or accounts there are, or whether a particular one is real. Returning `404` for anything the caller isn't entitled to see makes "not yours" indistinguishable from "doesn't exist". It's a standard technique for resources identified by guessable or leaked ids.

</details>

<details>
<summary>"What is data minimisation and how does Open Banking apply it?"</summary>

It's the UK GDPR principle that you should only process the personal data you need for a stated purpose. Open Banking applies it through granular permissions: a TPP asks only for what its service needs (balances but not transactions, credits but not debits, basic fields but not account numbers), the customer sees exactly that list, chooses the accounts, and the bank enforces it on every request.

</details>

### Further reading

- [UK Open Banking: Account Access Consents (v3.1.10)](https://openbankinguk.github.io/read-write-api-site3/v3.1.10/resources-and-data-models/aisp/account-access-consents.html): the endpoints, statuses and request fields
- [UK Open Banking: Account and Transaction API profile (v3.1.10)](https://openbankinguk.github.io/read-write-api-site3/v3.1.10/profiles/account-and-transaction-api-profile.html): the permission codes and the rules for combining them
- [ICO: the data minimisation principle](https://ico.org.uk/for-organisations/uk-gdpr-guidance-and-resources/data-protection-principles/a-guide-to-the-data-protection-principles/data-minimisation/)
- [Spring Framework reference: method validation](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html)

**Simplification to notice:** the standard also lets a TPP limit a consent to a window of transactions with `TransactionFromDateTime` and `TransactionToDateTime`. Adding them is two nullable columns and one more filter in guide 04.

**Next:** [04: Enforcing consents](04-enforcing-consents.md). Every data request is checked against its consent, and a revoked consent stays revoked, even when the customer's approval and the TPP's deletion arrive at the same instant.
