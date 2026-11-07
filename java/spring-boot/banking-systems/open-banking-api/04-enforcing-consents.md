# 04: Enforcing Consents

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- How a token gets linked to **one consent**, and the five checks every data request must pass
- Why the consent is read from the database on **every** request, and how that makes **revocation take effect immediately**, even for unexpired tokens
- How permissions shape the **response** itself: Basic vs Detail fields, Credits vs Debits transactions
- Why an account that doesn't exist must look **exactly like** one that isn't shared
- Controlling **time in tests** with an injected `Clock`
- A real race: the customer approves a consent at the instant the TPP deletes it, and the deleted consent **comes back to life**. You'll watch it happen in a test, then fix it with **optimistic locking**
- Why optimistic locking is the right tool here when it was the wrong one for a busy bank account

By the end, the API finally keeps its promise: a TPP sees exactly what the customer agreed to, for as long as they agreed, and not a moment longer.

---

## Concepts

### How it works in a real bank

Once Alice approves moneywise's consent, the bank's authorisation server issues moneywise an access token for it. In the UK standard the `ConsentId` is the *intent id* the OAuth authorisation code flow is started with, so the token the bank issues at the end is tied to exactly that consent. How a bank records that link inside the token is up to the bank: many use opaque tokens and look it up.

This tutorial puts it in the JWT as a **`consent_id` claim**. A consent-bound token looks like this:

```json
{
  "sub": "alice",
  "client_id": "moneywise",
  "scope": "accounts",
  "consent_id": "aac-302814c9-bf97-4005-9ec1-cd8601601a4f",
  "...": "iss, aud, exp, iat, jti as in guide 02"
}
```

From now on there are two kinds of TPP token, and they do different jobs:

| Token | `sub` | `consent_id` | Used for |
|---|---|---|---|
| **Client credentials** | the TPP itself | none | Creating, reading and deleting consents |
| **Consent-bound** (authorisation code) | the customer | yes | Reading account data |

### The five checks

On every data request, before reading anything, the API asks:

1. **Is the token linked to a consent at all?** A client-credentials token isn't, so it can't read data.
2. **Does the consent exist, and belong to this TPP?** The consent's `client_id` must match the token's. A token can't be used with another TPP's consent.
3. **Is it `Authorised`?** Not awaiting authorisation, not rejected, not revoked.
4. **Has it expired?** Compare `ExpirationDateTime` with the current time.
5. **Does it cover this request?** The right **permission** for the endpoint, and, for per-account endpoints, the **account** is one the customer chose.

Any "no" is a `403 Forbidden`, as a `ProblemDetail` saying which check failed.

> **Sidebar: 403, or 401 `invalid_token`?** The token itself is fine here (signed, unexpired, right audience). What's gone is the permission it stood for, so this tutorial uses `403`. Some implementations treat a revoked consent as revoking its tokens, so the next call gets `401` with `error="invalid_token"` and the TPP knows to stop using that token. Both are seen in practice. What matters is that the TPP can tell "fix your request" from "go back to the customer", and that the reason is clear.

### Why read the consent on every request?

A JWT is self-contained. Once issued, it's valid until its `exp`, and nothing the bank does can recall it. If the API trusted claims inside the token about *what* it allows, a customer who revoked access at 10:00 would still have their data read until the token expired at 10:59.

So the token only carries a **pointer** (`consent_id`). The *rules* stay in the bank's database, and the API reads them fresh each time. Revocation takes effect on the very next request. The cost is one indexed primary-key lookup per request, which is cheap.

This is the general pattern whenever access must be **revocable faster than tokens expire**: keep tokens short-lived, and check the thing that can change (a consent, a session, an account status) at request time.

### Permissions shape the response

Permissions don't just decide yes or no. They decide **what's in the answer**:

| Endpoint | Needs one of | Then the response… |
|---|---|---|
| `GET /accounts` | `ReadAccountsBasic`, `ReadAccountsDetail` | lists **only the accounts in the consent**. The `account` block (sort code, account number, name) appears only with **Detail**. |
| `GET /accounts/{id}` | same, and `{id}` in the consent | as above, for one account |
| `GET /accounts/{id}/balances` | `ReadBalances`, and `{id}` in the consent | unchanged |
| `GET /accounts/{id}/transactions` | `ReadTransactionsBasic`, `ReadTransactionsDetail`, and `{id}` in the consent | includes credits only with `ReadTransactionsCredits`, debits only with `ReadTransactionsDebits`. `transactionInformation` appears only with **Detail**. |

Fields the TPP may not see are **left out of the JSON entirely** (not sent as `null`), with Jackson's `@JsonInclude(NON_NULL)`.

### Don't leak what exists

In guide 01, an unknown account id returned `404 Account not found`. Now think about a TPP whose consent covers `acc-1001` asking for `acc-2001` and `acc-9999`:

- If `acc-2001` (exists, not shared) gave `403` and `acc-9999` (doesn't exist) gave `404`, the TPP could probe ids and learn which accounts exist at the bank.
- So the consent check runs **first**, before looking the account up. Both get the same `403`: "your consent does not cover this account".

### The race: a consent that comes back to life

Alice is on her bank's consent screen and taps **Approve**. At the same instant, moneywise's back end gives up waiting and calls `DELETE` on the consent. Both requests start before either finishes:

| Time | Approve (customer) | Delete (TPP) | Database |
|---|---|---|---|
| 1 | Read consent → `AwaitingAuthorisation` | | `AwaitingAuthorisation` |
| 2 | | Read consent → `AwaitingAuthorisation` | `AwaitingAuthorisation` |
| 3 | Check Alice owns the accounts… | Status allows revoke ✔. Set `Revoked` | |
| 4 | | Commit. **TPP gets `204 Deleted`** | `Revoked` |
| 5 | Status allows authorise ✔ (it read `AwaitingAuthorisation` at time 1). Set `Authorised` | | |
| 6 | Commit | | **`Authorised`** |

The TPP was told the consent is deleted. The bank's database says it's authorised, with accounts attached. moneywise's tokens linked to it **work again**. A customer who later checks the bank's access dashboard sees a TPP with access that both sides believe was withdrawn.

It's the same **lost update** as the core ledger's double-spend, in a different costume: two transactions read the same row, both make a decision based on what they read, and the last write wins. The state machine in `Consent` checked the status perfectly, but against a status that was already **stale**.

### Optimistic locking fits here

The core ledger fixed its race with **pessimistic** locking (`SELECT … FOR UPDATE`), because a busy current account is hit by many writes at once and optimistic locking made most of them fail. A consent is different:

- **Conflicts are rare.** A consent changes status a handful of times in its whole life, and two changes landing within milliseconds is unusual.
- **A failure is cheap to handle.** The loser gets `409 Conflict` and, if it still wants to, fetches the consent and tries again.

That's the textbook case for **optimistic locking**: add a `version` column, and every `UPDATE` includes `WHERE version = <what I read>`. If another transaction changed the row first, the update matches zero rows, Hibernate throws `ObjectOptimisticLockingFailureException`, and the whole transaction rolls back. Nothing waits and nothing deadlocks.

---

## Steps

### 1. The consent guard

`consent/ConsentAccessDeniedException.java`:

```java
package com.example.openbanking.consent;

/** The token is valid, but the consent behind it doesn't allow this request. */
public class ConsentAccessDeniedException extends RuntimeException {

    public ConsentAccessDeniedException(String message) {
        super(message);
    }
}
```

`consent/ConsentGuard.java`:

```java
package com.example.openbanking.consent;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Checks, on every data request, that the consent behind the access token still
 * allows it. The consent is read from the database each time, so a revocation
 * takes effect on the very next request, whatever the token's own expiry says.
 */
@Component
public class ConsentGuard {

    public static final String CONSENT_CLAIM = "consent_id";

    private final ConsentRepository consents;
    private final Clock clock;

    public ConsentGuard(ConsentRepository consents, Clock clock) {
        this.consents = consents;
        this.clock = clock;
    }

    /** The consent must be usable, and must include at least one of the given permissions. */
    @Transactional(readOnly = true)
    public Consent require(Jwt token, Permission... anyOf) {
        String consentId = token.getClaimAsString(CONSENT_CLAIM);
        if (consentId == null) {
            throw new ConsentAccessDeniedException("The access token is not linked to a consent");
        }
        Consent consent = consents.findById(consentId)
                .filter(c -> c.getClientId().equals(token.getClaimAsString("client_id")))
                .orElseThrow(() -> new ConsentAccessDeniedException("Consent %s not found".formatted(consentId)));
        if (consent.getStatus() != ConsentStatus.AUTHORISED) {
            throw new ConsentAccessDeniedException(
                    "Consent %s is %s".formatted(consentId, consent.getStatus().code()));
        }
        if (consent.isExpiredAt(clock.instant())) {
            throw new ConsentAccessDeniedException(
                    "Consent %s expired at %s".formatted(consentId, consent.getExpiresAt()));
        }
        if (Arrays.stream(anyOf).noneMatch(consent.getPermissions()::contains)) {
            throw new ConsentAccessDeniedException("Consent %s does not include %s".formatted(consentId,
                    Arrays.stream(anyOf).map(Permission::code).collect(Collectors.joining(" or "))));
        }
        return consent;
    }

    /** As above, and the customer must have chosen to share this account. */
    @Transactional(readOnly = true)
    public Consent require(Jwt token, String accountId, Permission... anyOf) {
        Consent consent = require(token, anyOf);
        if (!consent.getAccountIds().contains(accountId)) {
            throw new ConsentAccessDeniedException(
                    "Consent %s does not cover account %s".formatted(consent.getId(), accountId));
        }
        return consent;
    }
}
```

- **The checks run in the order from the Concepts section**, and each failure says which one failed. That's what a TPP's support team needs at 2am.
- **`Permission... anyOf`** is a *varargs* parameter: callers pass one or more permissions, and Java hands them over as an array. `ReadAccountsBasic` *or* `ReadAccountsDetail` both allow listing accounts.
- **`consent.getPermissions()::contains`** is a method reference to `contains` on that particular set, used as a predicate.
- **It returns the `Consent`**, because the controller needs its accounts and permissions to shape the response.
- **The second method calls the first through `this`**, which bypasses the Spring proxy. That's fine: the outer call already opened the read-only transaction.

Map it to a `403` in `ApiExceptionHandler` (import `ConsentAccessDeniedException`):

```java
    @ExceptionHandler(ConsentAccessDeniedException.class)
    ProblemDetail handle(ConsentAccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Consent does not allow this request");
        return problem;
    }
```

### 2. Only the consented accounts

`allAccounts()` has to go. Replace the repository method in `account/AccountRepository.java`:

```java
package com.example.openbanking.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AccountRepository extends JpaRepository<Account, String> {

    List<Account> findByIdInOrderById(Collection<String> ids);
}
```

`findByIdIn…` is a derived query: Spring Data generates `where a.id in (:ids)`.

In `AccountService`, replace `allAccounts()` with (and `import java.util.Collection;`):

```java
    public List<Account> accounts(Collection<String> accountIds) {
        return accounts.findByIdInOrderById(accountIds);
    }
```

The service no longer offers *any* way to list all accounts. Making the unsafe operation impossible beats remembering not to call it.

### 3. The controller asks the guard first

Replace `account/AccountController.java`:

```java
package com.example.openbanking.account;

import com.example.openbanking.api.ObResponse;
import com.example.openbanking.consent.Consent;
import com.example.openbanking.consent.ConsentGuard;
import com.example.openbanking.consent.Permission;
import com.example.openbanking.money.Money;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.example.openbanking.consent.Permission.READ_ACCOUNTS_BASIC;
import static com.example.openbanking.consent.Permission.READ_ACCOUNTS_DETAIL;
import static com.example.openbanking.consent.Permission.READ_BALANCES;
import static com.example.openbanking.consent.Permission.READ_TRANSACTIONS_BASIC;
import static com.example.openbanking.consent.Permission.READ_TRANSACTIONS_CREDITS;
import static com.example.openbanking.consent.Permission.READ_TRANSACTIONS_DEBITS;
import static com.example.openbanking.consent.Permission.READ_TRANSACTIONS_DETAIL;

@RestController
@RequestMapping("/open-banking/v1/aisp")
public class AccountController {

    private final AccountService accountService;
    private final ConsentGuard consentGuard;

    public AccountController(AccountService accountService, ConsentGuard consentGuard) {
        this.accountService = accountService;
        this.consentGuard = consentGuard;
    }

    @GetMapping("/accounts")
    public ObResponse<AccountsData> accounts(@AuthenticationPrincipal Jwt token) {
        Consent consent = consentGuard.require(token, READ_ACCOUNTS_BASIC, READ_ACCOUNTS_DETAIL);
        boolean detail = consent.getPermissions().contains(READ_ACCOUNTS_DETAIL);
        return ObResponse.of(new AccountsData(accountService.accounts(consent.getAccountIds()).stream()
                .map(a -> AccountResponse.from(a, detail))
                .toList()));
    }

    @GetMapping("/accounts/{accountId}")
    public ObResponse<AccountsData> account(@AuthenticationPrincipal Jwt token, @PathVariable String accountId) {
        Consent consent = consentGuard.require(token, accountId, READ_ACCOUNTS_BASIC, READ_ACCOUNTS_DETAIL);
        boolean detail = consent.getPermissions().contains(READ_ACCOUNTS_DETAIL);
        return ObResponse.of(new AccountsData(List.of(AccountResponse.from(accountService.get(accountId), detail))));
    }

    @GetMapping("/accounts/{accountId}/balances")
    public ObResponse<BalancesData> balances(@AuthenticationPrincipal Jwt token, @PathVariable String accountId) {
        consentGuard.require(token, accountId, READ_BALANCES);
        Instant now = Instant.now();
        return ObResponse.of(new BalancesData(accountService.balances(accountId).stream()
                .map(b -> new BalanceResponse(accountId, b.type(), b.amount(), now))
                .toList()));
    }

    @GetMapping("/accounts/{accountId}/transactions")
    public ObResponse<TransactionsData> transactions(@AuthenticationPrincipal Jwt token,
                                                     @PathVariable String accountId) {
        Consent consent = consentGuard.require(token, accountId, READ_TRANSACTIONS_BASIC, READ_TRANSACTIONS_DETAIL);
        Set<Permission> granted = consent.getPermissions();
        boolean detail = granted.contains(READ_TRANSACTIONS_DETAIL);
        boolean credits = granted.contains(READ_TRANSACTIONS_CREDITS);
        boolean debits = granted.contains(READ_TRANSACTIONS_DEBITS);
        return ObResponse.of(new TransactionsData(accountService.transactions(accountId).stream()
                .filter(t -> t.getCreditDebit() == CreditDebit.CREDIT ? credits : debits)
                .map(t -> TransactionResponse.from(t, detail))
                .toList()));
    }

    public record AccountsData(List<AccountResponse> account) {
    }

    /** "account" (how to pay into it) is only included with ReadAccountsDetail. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AccountResponse(String accountId, String currency, AccountType accountType,
                                  AccountSubType accountSubType, String nickname,
                                  AccountIdentification account) {

        static AccountResponse from(Account a, boolean detail) {
            AccountIdentification identification = detail
                    ? new AccountIdentification("UK.OBIE.SortCodeAccountNumber",
                            a.getSortCode() + a.getAccountNumber(), a.getHolderName())
                    : null;
            return new AccountResponse(a.getId(), a.getCurrency().getCurrencyCode(),
                    a.getAccountType(), a.getAccountSubType(), a.getNickname(), identification);
        }
    }

    /** How the account is identified for payments: sort code and account number. */
    public record AccountIdentification(String schemeName, String identification, String name) {
    }

    public record BalancesData(List<BalanceResponse> balance) {
    }

    public record BalanceResponse(String accountId, BalanceType type, Money amount, Instant dateTime) {
    }

    public record TransactionsData(List<TransactionResponse> transaction) {
    }

    /** "transactionInformation" (the description) is only included with ReadTransactionsDetail. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TransactionResponse(String transactionId, String accountId,
                                      CreditDebit creditDebitIndicator, TransactionStatus status,
                                      Instant bookingDateTime, Money amount,
                                      String transactionInformation) {

        static TransactionResponse from(AccountTransaction t, boolean detail) {
            return new TransactionResponse(t.getId(), t.getAccountId(), t.getCreditDebit(),
                    t.getStatus(), t.getBookedAt(), t.getAmount(), detail ? t.getInformation() : null);
        }
    }
}
```

- **Every method calls the guard on its first line**, before touching any account data. That ordering is what makes an unknown account indistinguishable from an unshared one.
- **The filter `t.getCreditDebit() == CREDIT ? credits : debits`** keeps a transaction only if the consent covers its direction.
- **`@JsonInclude(NON_NULL)` on the two records** leaves out `account` and `transactionInformation` when they're `null`. (`nickname` is also left out when an account has none, which is fine: it's optional in the standard too.)

> **Sidebar: why not `@PreAuthorize`?** Spring Security's method security could express "has scope accounts" on each method. The consent checks need the database, the account id from the path and the permission list, and the result (the `Consent`) is needed afterwards to shape the response. An explicit call to a guard is clearer than a SpEL expression, and easy to unit-test. Use annotations for simple role and scope rules, and code for business rules.

Run the tests. Nearly every `AccountApiTest` now fails with `403`: their `accounts()` token has no `consent_id`, so the guard refuses it. That's correct. The tests need consents.

### 4. Test support: consents, consent-bound tokens and a clock you control

Add a third token to `TestTokens`:

```java
    /**
     * What the TPP gets after the customer authorises a consent: a token acting
     * for the customer, linked to that consent.
     */
    public static JwtRequestPostProcessor consentBound(String consentId) {
        return jwt().jwt(token -> token
                .subject("alice")
                .claim("client_id", TPP)
                .claim("scope", "accounts")
                .claim("consent_id", consentId));
    }
```

(The guard doesn't look at `sub`, so `"alice"` is fine even for a consent Bob authorised.)

Most tests need "a consent with these permissions, authorised for these accounts". Create a fixture, `src/test/java/com/example/openbanking/TestConsents.java`:

```java
package com.example.openbanking;

import com.example.openbanking.account.AccountService;
import com.example.openbanking.consent.ConsentService;
import com.example.openbanking.consent.Permission;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Creates consents for tests, the way a TPP and then a customer would. */
@Component
public class TestConsents {

    private final ConsentService consentService;
    private final AccountService accountService;

    public TestConsents(ConsentService consentService, AccountService accountService) {
        this.consentService = consentService;
        this.accountService = accountService;
    }

    /** Requested by moneywise, not yet authorised. Returns the consent id. */
    public String awaitingAuthorisation(Permission... permissions) {
        return consentService.create(TestTokens.TPP, UUID.randomUUID().toString(), Set.of(permissions), null)
                .getId();
    }

    /** Requested by moneywise and authorised by the accounts' owner. Returns the consent id. */
    public String authorised(List<String> accountIds, Permission... permissions) {
        String consentId = awaitingAuthorisation(permissions);
        String customerId = accountService.get(accountIds.getFirst()).getCustomerId();
        consentService.authorise(consentId, customerId, accountIds);
        return consentId;
    }
}
```

It's a `@Component` in the **test** source tree, under the application's base package. `@SpringBootTest` component-scans from `OpenBankingApiApplication`'s package, and the test classes are on the classpath during tests, so it becomes a bean you can `@Autowired` into any test. (`List.getFirst()` needs Java 21+. Older code uses `get(0)`.)

To test **expiry** without waiting 90 days, tests need to move time forwards. Create `src/test/java/com/example/openbanking/MutableClock.java`:

```java
package com.example.openbanking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Real time, plus an offset a test can move forwards (and must put back). */
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
        throw new UnsupportedOperationException();
    }
}
```

It follows real time by default, so every other test behaves normally. `volatile` makes an `advance` on the test thread visible to the request-handling code immediately.

Register it, `src/test/java/com/example/openbanking/TestClockConfig.java`:

```java
package com.example.openbanking;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestClockConfig {

    /** Replaces the real Clock everywhere it's injected, so tests can move time forwards. */
    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }
}
```

There are now **two** `Clock` beans: the real one from `ClockConfig` and this one. `@Primary` makes this one win wherever a `Clock` is injected. `@TestConfiguration` classes are deliberately **not** picked up by component scanning, so import it in the meta-annotation (with `import org.springframework.context.annotation.Import;`):

```java
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:openbanking-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public @interface OpenBankingIntegrationTest {
}
```

Every test class still shares one cached application context, because they all use the same meta-annotation.

### 5. Rewrite the account tests

Replace `src/test/java/com/example/openbanking/account/AccountApiTest.java`:

```java
package com.example.openbanking.account;

import com.example.openbanking.OpenBankingIntegrationTest;
import com.example.openbanking.TestConsents;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.util.List;

import static com.example.openbanking.TestTokens.consentBound;
import static com.example.openbanking.consent.Permission.READ_ACCOUNTS_BASIC;
import static com.example.openbanking.consent.Permission.READ_ACCOUNTS_DETAIL;
import static com.example.openbanking.consent.Permission.READ_BALANCES;
import static com.example.openbanking.consent.Permission.READ_TRANSACTIONS_BASIC;
import static com.example.openbanking.consent.Permission.READ_TRANSACTIONS_CREDITS;
import static com.example.openbanking.consent.Permission.READ_TRANSACTIONS_DEBITS;
import static com.example.openbanking.consent.Permission.READ_TRANSACTIONS_DETAIL;
import static org.assertj.core.api.Assertions.assertThat;

@OpenBankingIntegrationTest
class AccountApiTest {

    private static final String BASE = "/open-banking/v1/aisp";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestConsents consents;

    @Test
    void listsOnlyTheAccountsTheCustomerChoseToShare() {
        String consentId = consents.authorised(List.of("acc-1001"), READ_ACCOUNTS_DETAIL);

        var response = mvc.get().uri(BASE + "/accounts").with(consentBound(consentId));

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.data.account[*].accountId")
                .asArray().containsExactly("acc-1001");
        assertThat(response).bodyJson().extractingPath("$.links.self")
                .isEqualTo("http://localhost" + BASE + "/accounts");
        assertThat(response).bodyJson().extractingPath("$.meta.totalPages").isEqualTo(1);
    }

    @Test
    void detailPermissionShowsHowTheAccountIsIdentified() {
        String consentId = consents.authorised(List.of("acc-1001"), READ_ACCOUNTS_DETAIL);

        var response = mvc.get().uri(BASE + "/accounts/acc-1001").with(consentBound(consentId));

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.data.account[0].accountSubType")
                .isEqualTo("CurrentAccount");
        assertThat(response).bodyJson().extractingPath("$.data.account[0].account.schemeName")
                .isEqualTo("UK.OBIE.SortCodeAccountNumber");
        assertThat(response).bodyJson().extractingPath("$.data.account[0].account.identification")
                .isEqualTo("11223312345678");
    }

    @Test
    void basicPermissionLeavesTheIdentificationOut() {
        String consentId = consents.authorised(List.of("acc-1001"), READ_ACCOUNTS_BASIC);

        var response = mvc.get().uri(BASE + "/accounts/acc-1001").with(consentBound(consentId));

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().doesNotHavePath("$.data.account[0].account");
    }

    @Test
    void anAccountOutsideTheConsentIs403() {
        String consentId = consents.authorised(List.of("acc-1001"), READ_ACCOUNTS_BASIC);

        var response = mvc.get().uri(BASE + "/accounts/acc-1002").with(consentBound(consentId));

        assertThat(response).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(response).bodyJson().extractingPath("$.detail").asString()
                .endsWith("does not cover account acc-1002");
    }

    @Test
    void availableBalanceTakesOffPendingPayments() {
        String consentId = consents.authorised(List.of("acc-1001"), READ_ACCOUNTS_BASIC, READ_BALANCES);

        var response = mvc.get().uri(BASE + "/accounts/acc-1001/balances").with(consentBound(consentId));

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.data.balance[0].type").isEqualTo("InterimBooked");
        assertThat(response).bodyJson().extractingPath("$.data.balance[0].amount.amount").isEqualTo("1507.50");
        assertThat(response).bodyJson().extractingPath("$.data.balance[1].type").isEqualTo("InterimAvailable");
        assertThat(response).bodyJson().extractingPath("$.data.balance[1].amount.amount").isEqualTo("1495.20");
    }

    @Test
    void anOverdrawnAccountHasNegativeBalances() {
        String consentId = consents.authorised(List.of("acc-2001"), READ_ACCOUNTS_BASIC, READ_BALANCES);

        var response = mvc.get().uri(BASE + "/accounts/acc-2001/balances").with(consentBound(consentId));

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.data.balance[0].amount.amount").isEqualTo("-80.00");
        assertThat(response).bodyJson().extractingPath("$.data.balance[1].amount.amount").isEqualTo("-85.00");
    }

    @Test
    void listsTransactionsNewestFirst() {
        String consentId = consents.authorised(List.of("acc-1001"), READ_ACCOUNTS_BASIC,
                READ_TRANSACTIONS_DETAIL, READ_TRANSACTIONS_CREDITS, READ_TRANSACTIONS_DEBITS);

        var response = mvc.get().uri(BASE + "/accounts/acc-1001/transactions").with(consentBound(consentId));

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.data.transaction[*].transactionId")
                .asArray().containsExactly("txn-4", "txn-3", "txn-2", "txn-1");
        assertThat(response).bodyJson().extractingPath("$.data.transaction[0].status").isEqualTo("Pending");
        assertThat(response).bodyJson().extractingPath("$.data.transaction[0].creditDebitIndicator")
                .isEqualTo("Debit");
        assertThat(response).bodyJson().extractingPath("$.data.transaction[0].amount.amount").isEqualTo("12.30");
        assertThat(response).bodyJson().extractingPath("$.data.transaction[0].transactionInformation")
                .isEqualTo("Corner Coffee");
    }

    @Test
    void aCreditsOnlyBasicConsentSeesMoneyInWithoutDescriptions() {
        String consentId = consents.authorised(List.of("acc-1001"), READ_ACCOUNTS_BASIC,
                READ_TRANSACTIONS_BASIC, READ_TRANSACTIONS_CREDITS);

        var response = mvc.get().uri(BASE + "/accounts/acc-1001/transactions").with(consentBound(consentId));

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.data.transaction[*].transactionId")
                .asArray().containsExactly("txn-1");
        assertThat(response).bodyJson().doesNotHavePath("$.data.transaction[0].transactionInformation");
    }
}
```

Each test creates **its own** consent with exactly the permissions it's about. Tests don't share consents, so they can run in any order. The guide 01 test for an unknown account's `404` is gone: as the Concepts section explained, that's now a `403`.

Two older test classes also used the `accounts()` token against `/accounts`, which now needs a consent. In `FapiInteractionIdTest`, point the two token-carrying tests at an endpoint that only needs a client token, and keep the `401` test as it is:

```java
    private static final String UNKNOWN_CONSENT = "/open-banking/v1/aisp/account-access-consents/aac-unknown";

    @Test
    void playsBackTheCallersInteractionId() {
        String id = UUID.randomUUID().toString();

        var response = mvc.get().uri(UNKNOWN_CONSENT)
                .with(client("moneywise"))
                .header("x-fapi-interaction-id", id);

        assertThat(response).headers().hasValue("x-fapi-interaction-id", id);
    }

    @Test
    void generatesOneWhenTheCallerDidNotSendIt() {
        var response = mvc.get().uri(UNKNOWN_CONSENT)
                .with(client("moneywise"))
                .exchange();

        assertThat(response).hasStatus(404);
        String generated = response.getResponse().getHeader("x-fapi-interaction-id");
        assertThat(generated).isNotNull();
        assertThat(UUID.fromString(generated)).isNotNull();
    }
```

(Replace the `accounts` static import with `import static com.example.openbanking.TestTokens.client;`.) In `RealTokenTest`, the real tokens have no consent either, so make them read a consent instead of accounts. Add:

```java
    @Autowired
    TestConsents consents;

    String consentPath;

    @BeforeEach
    void aConsentToRead() {
        consentPath = "/open-banking/v1/aisp/account-access-consents/" + consents.awaitingAuthorisation(READ_ACCOUNTS_BASIC);
    }
```

and change every `mvc.get().uri("/open-banking/v1/aisp/accounts")` in that class to `mvc.get().uri(consentPath)`. (Imports: `com.example.openbanking.TestConsents`, `org.junit.jupiter.api.BeforeEach` and a static import of `READ_ACCOUNTS_BASIC`.) The tests still prove the same thing: what the decoder accepts and refuses.

`accounts()` in `TestTokens` is now unused. Delete it, or keep it for experiments.

### 6. Enforcement tests

`src/test/java/com/example/openbanking/consent/ConsentEnforcementApiTest.java`:

```java
package com.example.openbanking.consent;

import com.example.openbanking.MutableClock;
import com.example.openbanking.OpenBankingIntegrationTest;
import com.example.openbanking.TestConsents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.example.openbanking.TestTokens.client;
import static com.example.openbanking.TestTokens.consentBound;
import static com.example.openbanking.consent.Permission.READ_ACCOUNTS_BASIC;
import static com.example.openbanking.consent.Permission.READ_BALANCES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@OpenBankingIntegrationTest
class ConsentEnforcementApiTest {

    static final String BALANCES = "/open-banking/v1/aisp/accounts/acc-1001/balances";

    @Autowired MockMvcTester mvc;
    @Autowired TestConsents consents;
    @Autowired ConsentService consentService;
    @Autowired MutableClock clock;

    @AfterEach
    void putTheClockBack() {
        clock.reset();
    }

    @Test
    void aTokenWithoutAConsentIs403() {
        MvcTestResult response = mvc.get().uri(BALANCES).with(client("moneywise")).exchange();

        assertThat(response).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Consent does not allow this request");
        assertThat(response).bodyJson().extractingPath("$.detail")
                .isEqualTo("The access token is not linked to a consent");
    }

    @Test
    void aConsentAwaitingAuthorisationGrantsNothing() {
        String consentId = consents.awaitingAuthorisation(READ_ACCOUNTS_BASIC, READ_BALANCES);

        MvcTestResult response = mvc.get().uri(BALANCES).with(consentBound(consentId)).exchange();

        assertThat(response).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(response).bodyJson().extractingPath("$.detail").asString().endsWith("is AwaitingAuthorisation");
    }

    @Test
    void theConsentMustIncludeThePermission() {
        String consentId = consents.authorised(List.of("acc-1001"), READ_ACCOUNTS_BASIC);

        MvcTestResult response = mvc.get().uri(BALANCES).with(consentBound(consentId)).exchange();

        assertThat(response).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(response).bodyJson().extractingPath("$.detail").asString().endsWith("does not include ReadBalances");
    }

    @Test
    void anotherTppCannotUseTheConsent() {
        String consentId = consents.authorised(List.of("acc-1001"), READ_ACCOUNTS_BASIC, READ_BALANCES);

        MvcTestResult response = mvc.get().uri(BALANCES)
                .with(jwt().jwt(token -> token.subject("alice").claim("client_id", "snoopco")
                        .claim("scope", "accounts").claim("consent_id", consentId)))
                .exchange();

        assertThat(response).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void revocationTakesEffectOnTheVeryNextRequest() {
        String consentId = consents.authorised(List.of("acc-1001"), READ_ACCOUNTS_BASIC, READ_BALANCES);
        assertThat(mvc.get().uri(BALANCES).with(consentBound(consentId))).hasStatusOk();

        assertThat(mvc.delete().uri("/open-banking/v1/aisp/account-access-consents/" + consentId)
                .with(client("moneywise"))).hasStatus(HttpStatus.NO_CONTENT);

        // Same token, still unexpired. The consent behind it is gone.
        MvcTestResult response = mvc.get().uri(BALANCES).with(consentBound(consentId)).exchange();
        assertThat(response).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(response).bodyJson().extractingPath("$.detail").asString().endsWith("is Revoked");
    }

    @Test
    void anExpiredConsentGrantsNothing() {
        String consentId = consentService.create("moneywise", UUID.randomUUID().toString(),
                Set.of(READ_ACCOUNTS_BASIC, READ_BALANCES), Instant.now().plus(Duration.ofDays(90))).getId();
        consentService.authorise(consentId, "alice", List.of("acc-1001"));
        assertThat(mvc.get().uri(BALANCES).with(consentBound(consentId))).hasStatusOk();

        clock.advance(Duration.ofDays(91));

        MvcTestResult response = mvc.get().uri(BALANCES).with(consentBound(consentId)).exchange();
        assertThat(response).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(response).bodyJson().extractingPath("$.detail").asString().contains("expired at");
    }
}
```

- **`revocationTakesEffectOnTheVeryNextRequest`** is the test that justifies the whole design. The *same token* works, then, one `DELETE` later, doesn't.
- **`anExpiredConsentGrantsNothing`** moves the clock 91 days forward. The `@AfterEach` puts it back **even if the test fails**. Without that, every test that ran afterwards would think it was February.
- **The clock only affects code that asks for the `Clock` bean.** Code that calls `Instant.now()` directly ignores it, which is exactly the trap `Clock` injection avoids. (The `jwt()` tokens are unaffected anyway, because they skip the JWT decoder and its expiry check.)

### 7. Try it

With the app running, walk the whole journey: moneywise's token, create a consent (`ReadAccountsDetail`, `ReadBalances`, `ReadTransactionsBasic`, `ReadTransactionsDebits`), Alice authorises it for `acc-1001`, then mint moneywise a consent-bound token. In `requests.http`:

```http
### moneywise's token after Alice approved (consent-bound)
POST http://localhost:8080/dev/tokens
Content-Type: application/json

{"clientId": "moneywise", "subject": "alice", "scope": "accounts", "consentId": "{{consentId}}"}

> {% client.global.set("dataToken", response.body.access_token); %}

### Accounts the consent covers
GET http://localhost:8080/open-banking/v1/aisp/accounts
Authorization: Bearer {{dataToken}}

### Transactions (debits only, no descriptions, with that consent)
GET http://localhost:8080/open-banking/v1/aisp/accounts/acc-1001/transactions
Authorization: Bearer {{dataToken}}

### An account the consent doesn't cover (403)
GET http://localhost:8080/open-banking/v1/aisp/accounts/acc-1002/balances
Authorization: Bearer {{dataToken}}
```

Update the earlier data requests in `requests.http` to use `{{dataToken}}` too. With curl, mint the token with the same JSON body and keep it in a variable.

The transactions come back debits only, without descriptions:

```json
{
  "data": {
    "transaction": [
      {
        "transactionId": "txn-4",
        "accountId": "acc-1001",
        "creditDebitIndicator": "Debit",
        "status": "Pending",
        "bookingDateTime": "2026-11-05T08:05:00Z",
        "amount": { "amount": "12.30", "currency": "GBP" }
      },
      { "transactionId": "txn-3", "...": "..." },
      { "transactionId": "txn-2", "...": "..." }
    ]
  },
  "links": { "self": "http://localhost:8080/open-banking/v1/aisp/accounts/acc-1001/transactions" },
  "meta": { "totalPages": 1 }
}
```

The salary credit (`txn-1`) isn't there, and no `transactionInformation`. The uncovered account:

```json
{"detail":"Consent aac-302814c9-bf97-4005-9ec1-cd8601601a4f does not cover account acc-1002","instance":"/open-banking/v1/aisp/accounts/acc-1002/balances","status":403,"title":"Consent does not allow this request"}
```

Now `DELETE` the consent with moneywise's client token, and repeat a data request with the **same** `dataToken`:

```
HTTP/1.1 403
x-fapi-interaction-id: 08b1c262-e766-4305-b7e7-7d3896a81cdf
Content-Type: application/problem+json

{"detail":"Consent aac-302814c9-bf97-4005-9ec1-cd8601601a4f is Revoked","instance":"/open-banking/v1/aisp/accounts/acc-1001/balances","status":403,"title":"Consent does not allow this request"}
```

### 8. The race, in a test

Everything passes. Sequentially. Now fire approvals and deletions at the same consents at the same instant. Create `src/test/java/com/example/openbanking/consent/ConcurrentConsentTest.java`:

```java
package com.example.openbanking.consent;

import com.example.openbanking.OpenBankingIntegrationTest;
import com.example.openbanking.TestConsents;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.example.openbanking.consent.Permission.READ_ACCOUNTS_BASIC;
import static com.example.openbanking.consent.Permission.READ_BALANCES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The customer approves a consent at the very moment the TPP deletes it.
 * Whichever order they land in, a TPP that was told "deleted" must never end
 * up holding an authorised consent.
 */
@OpenBankingIntegrationTest
class ConcurrentConsentTest {

    @Autowired TestConsents consents;
    @Autowired ConsentService consentService;

    @Test
    void aDeletedConsentNeverComesBackToLife() throws Exception {
        int pairs = 20;
        List<String> consentIds = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            consentIds.add(consents.awaitingAuthorisation(READ_ACCOUNTS_BASIC, READ_BALANCES));
        }

        CountDownLatch startingGun = new CountDownLatch(1);
        Map<String, Boolean> deleted = new ConcurrentHashMap<>();
        Map<String, Boolean> authorised = new ConcurrentHashMap<>();
        List<Throwable> unexpected = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(pairs * 2)) {
            List<Future<?>> futures = new ArrayList<>();
            for (String consentId : consentIds) {
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    attempt(() -> consentService.authorise(consentId, "alice", List.of("acc-1001", "acc-1002")),
                            consentId, authorised, unexpected);
                    return null;
                }));
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    attempt(() -> consentService.revokeByClient(consentId, "moneywise"),
                            consentId, deleted, unexpected);
                    return null;
                }));
            }
            startingGun.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        List<String> zombies = consentIds.stream()
                .filter(id -> deleted.getOrDefault(id, false))
                .filter(id -> consentService.getForClient(id, "moneywise").getStatus() != ConsentStatus.REVOKED)
                .toList();
        System.out.printf("deleted=%d authorised=%d zombies=%d unexpected=%s%n",
                deleted.values().stream().filter(b -> b).count(),
                authorised.values().stream().filter(b -> b).count(),
                zombies.size(),
                unexpected.stream().map(t -> t.getClass().getSimpleName()).distinct().toList());

        assertThat(unexpected).isEmpty();
        assertThat(zombies).isEmpty();
    }

    /** Records whether the call succeeded. A refusal is a fine outcome; anything else is a bug. */
    private static void attempt(Runnable call, String consentId, Map<String, Boolean> outcome,
                                List<Throwable> unexpected) {
        try {
            call.run();
            outcome.put(consentId, true);
        } catch (ConsentStateException e) {
            outcome.put(consentId, false);
        } catch (RuntimeException e) {
            synchronized (unexpected) {
                unexpected.add(e);
            }
        }
    }
}
```

How it works:

- **Twenty consents, two threads each**: one approves as Alice, one deletes as moneywise. Forty threads in all.
- **The `CountDownLatch` starting gun** holds every thread at `await()` until all forty are queued, then `countDown()` releases them together. Without it, the first pairs would finish before the last ones started, and the race would rarely happen. (Core ledger guide 03 explains the pattern step by step.)
- **The test isn't `@Transactional`**, deliberately. Each service call must run in its own real transaction on its own thread, exactly as two HTTP requests would.
- **The invariant is precise.** It doesn't say "only one call may succeed": approve-then-delete, both succeeding, is perfectly legitimate. It says: **if the delete succeeded, the consent must end up `Revoked`.** A consent that broke that rule is a *zombie*.
- **A `ConsentStateException` is a fine outcome** (for example, approving a consent that's already revoked). Anything else is recorded as unexpected.
- **`ConcurrentHashMap`** because forty threads write to the maps at once.

Run it. It fails:

```
deleted=20 authorised=18 zombies=15 unexpected=[]
```

```
Expecting empty but was: ["aac-691d28a5-cf82-4a01-9382-26fefa7334f6",
```

Fifteen of the twenty deleted consents are back to `Authorised`. Run it a few more times: the number of zombies changes (13, 10, 10 in three more runs while writing this guide), but it's never zero. Every one is the timeline from the Concepts section: the approval read `AwaitingAuthorisation`, the deletion committed `Revoked`, and then the approval wrote `Authorised` over it.

Notice what *didn't* help. `Consent.authorise` checks the status perfectly. `ConsentService` is `@Transactional`. Neither stops two transactions from reading the same row and acting on what they read.

### 9. The fix: optimistic locking

Create `src/main/resources/db/migration/V4__add_consent_version.sql`:

```sql
ALTER TABLE account_access_consent ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
```

`DEFAULT 0` gives consents already in your `data/` database a starting version. **Don't edit `V3`**: it has already run.

In `Consent.java`, add the field (and `import jakarta.persistence.Version;`):

```java
    /** Optimistic lock: two requests can't both change the same version of a consent. */
    @Version
    private long version;
```

Run the concurrency test again:

```
deleted=15 authorised=6 zombies=0 unexpected=[ObjectOptimisticLockingFailureException]
```

```
org.springframework.orm.ObjectOptimisticLockingFailureException: Unexpected row count (expected row count 1 but was 0) [update account_access_consent set client_id=?,created_at=?,customer_id=?,expires_at=?,idempotency_key=?,status=?,status_updated_at=?,version=? where id=? and version=?] for entity [com.example.openbanking.consent.Consent with id 'aac-df321755-2b90-43cc-817a-ff97ab4798d5']
```

**Zero zombies.** Read the SQL in the exception: `… where id=? and version=?`. When the deletion committed first, it moved the version on, so the approval's update matched no rows, Hibernate threw, and the approval rolled back (consent account rows and all). The same happens the other way round when the approval commits first.

The test still fails, but only because it doesn't know that `ObjectOptimisticLockingFailureException` is a *refusal*, not a bug. In `attempt`, treat it like a `ConsentStateException` (import `org.springframework.dao.OptimisticLockingFailureException`, its parent):

```java
        } catch (ConsentStateException | OptimisticLockingFailureException e) {
            outcome.put(consentId, false);
```

Run it three times:

```
deleted=16 authorised=6 zombies=0 unexpected=[]
deleted=19 authorised=4 zombies=0 unexpected=[]
deleted=15 authorised=7 zombies=0 unexpected=[]
```

✅ Green, every time. The exact split between deletions and approvals that won varies with the timing, and that's fine: the invariant holds in every run.

Finally, an API client that loses this race should get a proper answer, not a `500`. Add to `ApiExceptionHandler` (import `org.springframework.dao.OptimisticLockingFailureException`):

```java
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handle(OptimisticLockingFailureException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The resource was changed by another request at the same time. Fetch it and try again.");
        problem.setTitle("Concurrent update");
        return problem;
    }
```

`409 Conflict` tells the caller exactly what happened. A TPP whose `DELETE` loses can simply send it again: the second attempt reads the new version (now `Authorised`) and revokes it.

> **Sidebar: two other ways to fix it.** **Pessimistic locking** (`@Lock(PESSIMISTIC_WRITE)` on a `findByIdForUpdate` query, as in [Core ledger guide 03](../core-ledger/03-transfers-and-concurrency.md#6-fix-2-pessimistic-locking)) makes the second transaction *wait* and then see the new status: the deletion would always succeed, just slightly later. A **conditional update** (`UPDATE account_access_consent SET status = 'REVOKED' WHERE id = ? AND status IN ('AWAITING_AUTHORISATION', 'AUTHORISED')`, then check the row count) does the compare-and-set in one statement without loading the entity. All three are correct. Optimistic locking is the least code and costs nothing when there's no conflict, which for consents is nearly always.

---

## Your turn

### Exercise 1: revoked from the bank's app

Add a test to `ConsentEnforcementApiTest`: Alice revokes an authorised consent through `/internal/account-access-consents/{id}/revoke`, and moneywise's next data request is refused.

<details>
<summary>Solution</summary>

```java
@Test
void revokingFromTheBanksAppEndsAccessToo() {
    String consentId = consents.authorised(List.of("acc-1001"), READ_ACCOUNTS_BASIC, READ_BALANCES);

    assertThat(mvc.post().uri("/internal/account-access-consents/{id}/revoke", consentId).with(psu("alice")))
            .hasStatusOk();

    assertThat(mvc.get().uri(BALANCES).with(consentBound(consentId))).hasStatus(HttpStatus.FORBIDDEN);
}
```

(Add `import static com.example.openbanking.TestTokens.psu;`.) Both revocation routes end in the same status, and the guard only cares about the status.

</details>

### Exercise 2: prove nothing leaks

Write a test showing that, with a consent covering only `acc-1001`, asking for the balances of `acc-2001` (exists, Bob's) and `acc-9999` (doesn't exist) gives the **same** status. This is the guide 01 exercise's answer changing, as promised.

<details>
<summary>Solution</summary>

```java
@Test
void anAccountThatDoesNotExistLooksTheSameAsOneThatIsNotShared() {
    String consentId = consents.authorised(List.of("acc-1001"), READ_ACCOUNTS_BASIC, READ_BALANCES);

    MvcTestResult notShared = mvc.get().uri("/open-banking/v1/aisp/accounts/acc-2001/balances")
            .with(consentBound(consentId)).exchange();
    MvcTestResult missing = mvc.get().uri("/open-banking/v1/aisp/accounts/acc-9999/balances")
            .with(consentBound(consentId)).exchange();

    assertThat(notShared).hasStatus(HttpStatus.FORBIDDEN);
    assertThat(missing).hasStatus(HttpStatus.FORBIDDEN);
}
```

The guard checks the account id against the consent *before* anything looks the account up, so the database never gets asked whether `acc-9999` exists. Swap the order in the controller and this test catches it.

</details>

### Exercise 3 (stretch, no solution given): retry the deletion automatically

A TPP's `DELETE` that loses the race gets a `409`, and has to retry. Could the bank retry for it? Spring Framework 7 has `@Retryable` built in (`org.springframework.resilience.annotation`, switched on with `@EnableResilientMethods`). Try making `revokeByClient` retry on `OptimisticLockingFailureException`. The catch: each retry must run in a **new** transaction, so think carefully about where the retry proxy sits relative to the `@Transactional` one. Then extend the concurrency test to assert that **every** deletion succeeds.

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 54, Failures: 0, Errors: 0, Skipped: 0
```

(44 from guide 03; `AccountApiTest` goes from 7 to 8; plus 8 in `ConsentEnforcementApiTest` and 1 in `ConcurrentConsentTest`, if you did the first two exercises.) Run it two or three times. The concurrency test must pass **every** time.

New files since guide 03:

```
src/main/java/com/example/openbanking/consent/
├── ConsentAccessDeniedException.java
└── ConsentGuard.java
src/main/resources/db/migration/
└── V4__add_consent_version.sql
src/test/java/com/example/openbanking/
├── MutableClock.java
├── TestClockConfig.java
├── TestConsents.java
└── consent/
    ├── ConcurrentConsentTest.java
    └── ConsentEnforcementApiTest.java
```

Modified: `AccountController.java`, `AccountRepository.java`, `AccountService.java`, `Consent.java` (`@Version`), `ApiExceptionHandler.java`, `OpenBankingIntegrationTest.java`, `TestTokens.java`, `AccountApiTest.java`, `FapiInteractionIdTest.java`, `RealTokenTest.java`, `requests.http`.

---

## Common errors

**Every data request is `403` with `"The access token is not linked to a consent"`**
The token has no `consent_id` claim. In tests use `consentBound(consentId)`. With curl, mint a token with `"consentId"` in the `/dev/tokens` body. Client-credentials tokens are for the consent endpoints only.

**`403` with `Consent aac-… is AwaitingAuthorisation`**
The customer hasn't approved it yet. Call the `/internal/…/authorise` endpoint with a `psu` token first.

**`ConcurrentConsentTest` passes even before adding `@Version`**
The threads aren't overlapping. Check every task calls `startingGun.await()` before doing anything, and that `countDown()` comes after all forty have been submitted. Also check the test class has no `@Transactional`.

**`ConcurrentConsentTest` fails with `unexpected=[ObjectOptimisticLockingFailureException]`**
You've added `@Version` (good) but `attempt` doesn't yet count an optimistic-locking failure as a refusal. See step 9.

**`ObjectOptimisticLockingFailureException: Unexpected row count (expected row count 1 but was 0)` in the logs of a running app**
Two requests changed the same consent at the same moment, and the loser was rolled back. It's reported to the client as `409 Concurrent update`. If you see it constantly, something is updating consents far more often than it should.

**`Schema validation: missing column [version] in table [account_access_consent]`**
You added the `@Version` field but not the `V4` migration (or put it under `db/demo`). Hibernate's `validate` mode catches the mismatch at start-up.

**Other tests start failing with `403`s mentioning `expired at`, depending on the order tests run in**
The clock wasn't put back after a test moved it. Keep the `@AfterEach` that calls `clock.reset()`.

**`anExpiredConsentGrantsNothing` fails with `expected: 403 but was: 200`**
`TestClockConfig`'s bean is missing `@Primary`. Surprisingly, there's no "two beans of type `Clock`" error: when Spring finds several candidates, it falls back to matching the **parameter name**, and `ConsentGuard`'s constructor parameter is called `clock`, which is the name of the *real* clock's bean. So the guard silently keeps using real time and never sees the 91 days pass. `@Primary` makes the choice explicit.

---

## Recap

- A data token carries a **pointer to a consent**. The rules stay in the bank's database and are **checked on every request**: linked, owned by this TPP, `Authorised`, unexpired, right permission, right account.
- Reading the consent each time is what makes **revocation immediate**, even though JWTs can't be recalled.
- **Permissions shape responses**: leave out fields (`@JsonInclude(NON_NULL)`) and filter transactions according to what was granted.
- Check the consent **before** looking anything up, so "doesn't exist" and "not yours" look identical.
- **Inject a `Clock`** so tests can move time. Reset it after each test.
- A state machine checked inside a transaction can still act on a **stale read**. Two requests racing on one consent produced **zombie consents**: deleted for the TPP, authorised in the database.
- **Optimistic locking** (`@Version`) detects the conflict and rolls the loser back. It's the right tool when conflicts are **rare** and a `409`-and-retry is acceptable. Pessimistic locking suits hot rows like busy accounts.
- Concurrency tests assert **invariants** ("deleted ⇒ revoked"), not a fixed number of winners.

### Interview angle

<details>
<summary>"A customer revokes a TPP's access. How do you make sure it stops immediately, if the TPP holds a JWT that's valid for another hour?"</summary>

Don't put the authorisation decision only in the token. The token carries a reference to the consent (or the bank looks the consent up from an opaque token), and the API checks the consent's current status in the database on every request, so revocation takes effect on the next call. Keep access tokens short-lived anyway, and optionally revoke refresh tokens at the authorisation server so no new access tokens are issued. Mention the trade-off: one cheap indexed lookup per request in exchange for instant revocation.

</details>

<details>
<summary>"When would you choose optimistic over pessimistic locking?"</summary>

Optimistic locking (a version column checked in the `UPDATE`) suits data where concurrent writes to the same row are rare and a failed write can be retried or reported as a conflict: it costs nothing when there's no conflict and never blocks or deadlocks. Pessimistic locking (`SELECT … FOR UPDATE`) suits hot rows written by many transactions at once, like a busy account balance, where optimistic locking would make most writes fail. Consents change status a few times in their life, so optimistic. Account balances are hammered, so pessimistic.

</details>

<details>
<summary>"Your status checks are inside @Transactional methods. How can a 'revoked' consent still end up authorised?"</summary>

`@Transactional` gives atomicity, not mutual exclusion. Under READ COMMITTED, both transactions can read the same row before either writes. The authorisation read "awaiting", the revocation read "awaiting" and committed "revoked", then the authorisation, still acting on its stale read, committed "authorised" over it: a lost update. Fix it by making the write conditional on what was read (a version column, or `UPDATE … WHERE status = …`) or by locking the row when reading it.

</details>

<details>
<summary>"How do you test a race condition like that?"</summary>

Create many independent instances of the race (here, twenty consents), start a thread for each side of each race, and hold them all at a `CountDownLatch` so they're released together. Don't make the test transactional, so each call commits for real. Assert an invariant that must hold whatever the interleaving ("if the deletion succeeded, the consent is revoked"), and treat expected refusals separately from unexpected exceptions. Show it failing before the fix, and run it repeatedly after.

</details>

### Further reading

- [UK Open Banking: Account and Transaction API profile (v3.1.10)](https://openbankinguk.github.io/read-write-api-site3/v3.1.10/profiles/account-and-transaction-api-profile.html): which fields each Basic and Detail permission includes
- [Spring Data JPA reference: Locking](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)
- [Vlad Mihalcea: how optimistic locking works with the version property](https://vladmihalcea.com/optimistic-locking-version-property-jpa-hibernate/)
- [RFC 6750 section 3.1: bearer token error codes](https://www.rfc-editor.org/rfc/rfc6750#section-3.1) (`invalid_token` vs `insufficient_scope`)

**Simplification to notice:** under the FCA's 2022 rules, an AISP must reconfirm the customer's consent at least every 90 days. That obligation sits with the TPP, but a bank could also enforce a maximum consent lifetime by rejecting an `expirationDateTime` too far in the future. It would be one more rule in `Consent.request`.

**Next:** [05: API versioning](05-api-versioning.md). How to change a response shape without breaking every TPP that depends on the old one.
