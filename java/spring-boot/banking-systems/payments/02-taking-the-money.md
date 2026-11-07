# 02: Taking the Money

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- **When** a bank takes the money for an outbound payment, and where it goes while the payment is in flight
- The bank's **internal accounts** for payments: a scheme settlement account and its reserves at the Bank of England
- How to build a deliberately **small double-entry ledger**: just enough for a payments system to take money and give it back
- Why checking the balance and then debiting it is a **race**, seen for real in a multi-threaded test that fails
- How `@Version` turns silent corruption into a safe failure, and why that still isn't good enough
- How **pessimistic locking** (`SELECT … FOR UPDATE`) with a consistent **lock order** fixes it
- Why every payment touching one settlement account makes it a **hot account**

By the end, a validated payment really debits the customer, a customer who can't afford a payment gets it rejected with reason `AM04`, and ten payments fired at the same instant can't spend the same £100 twice.

---

## Concepts

### How it works in a real bank

When you send a Faster Payment, your balance drops straight away, long before the banks settle with each other later in the day. So where has your money gone?

It hasn't left the bank yet. The bank moves it from **your** account into one of its own [internal accounts](../glossary.md#internal-account): a **scheme settlement account** that records "money we owe to other banks through Faster Payments". Later, when the scheme's settlement cycle completes at the Bank of England, the bank's **reserves** there go down by the net amount, and the settlement account is cleared.

In double-entry terms (the Core ledger tutorial covers [double-entry bookkeeping](../glossary.md#double-entry) properly), every movement is a [debit](../glossary.md#debit) on one account and a matching [credit](../glossary.md#credit) on another:

| Event | Debit | Credit | Built in |
|---|---|---|---|
| Customer pays cash in (demo only) | Cash (an [asset](../glossary.md#asset)): up | Customer (a [liability](../glossary.md#liability)): up | This guide |
| Payment validated: take the money | Customer: down | FPS settlement (a liability): up | This guide |
| Scheme rejects it: give the money back | FPS settlement: down | Customer: up | Guide 05 |
| Settlement cycle completes | FPS settlement: down | Reserves at the Bank of England (an asset): down | Guide 05 |

Two things to notice:

- A customer's balance is money the bank **owes** the customer, so to the bank it's a liability. A debit reduces it. The FPS settlement account is also a liability: money the bank owes, via the scheme, to other banks.
- Debiting the customer **at validation**, before the payment is even sent, is what makes the money unavailable for anything else. If the payment is later rejected, the bank posts the opposite entry. It never edits or deletes the first one.

> **Simplification:** some banks *reserve* the funds first (a hold that reduces the available balance) and only post the debit later. The Card authorisation tutorial covers holds. Posting the debit straight away is simpler and common for outbound payments.

### A ledger small enough for a payments system

A payments system in a real bank doesn't own the customer's account. It asks the core banking ledger to post the debit. Since each system in this series stands alone, you'll build a **cut-down ledger** inside this project, with just two tables:

- **`account`**: customer accounts plus three internal ones (cash, FPS settlement, reserves at the Bank of England), each with a cached balance.
- **`ledger_entry`**: one row per movement, holding **both** sides: a debit account, a credit account and one amount. Because each row debits and credits the same amount, every entry is balanced by construction.

The Core ledger tutorial builds the full version: journal entries with any number of postings, a builder that enforces the rules, derived balances, statements and reversals. Here, two legs are always enough.

### Double spending

Alice has £100. Her app (or a script, or a fraudster) sends ten £20 payments at the same instant. Each request reads her balance, sees £100, decides she can afford £20, and debits her. Without protection, all ten go through, and the bank has paid out £200 from an account holding £100.

`@Transactional` doesn't prevent this. It makes each request's writes all-or-nothing, but it doesn't stop two transactions reading the same balance before either has written. You need the requests for one account to happen **one at a time**: lock Alice's account row when you read it (`SELECT … FOR UPDATE`), so the next request waits until the first has committed and then reads the new balance.

That's the fix you'll apply, after watching the bug happen.

### A hot account

Every outbound payment credits the **same** FPS settlement account. If you lock it, every payment from every customer queues behind every other on that one row. That's a [hot account](../glossary.md#hot-account). It's correct and simple at tutorial scale, and the first thing that breaks at real scale. Real ledgers split such accounts into several sub-accounts, batch their postings, or don't keep a cached balance for them at all.

---

## Steps

### 1. Start with a fresh database

The `V3` migration in this guide makes `payment.debtor_account_id` a **foreign key** to a real account. The payments you created in guide 01 pointed at made-up account ids, so on your existing database the migration could fail. Stop the app and delete the `data/` folder in your project before going on. (On a real system you'd never do this. You'd write a migration that fixes or archives the bad rows first.)

### 2. The ledger tables

Create `src/main/resources/db/migration/V3__create_ledger.sql`:

```sql
CREATE TABLE account (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100)   NOT NULL,
    type        VARCHAR(20)    NOT NULL,
    system_code VARCHAR(30),
    currency    VARCHAR(3)     NOT NULL,
    balance     NUMERIC(19, 2) NOT NULL,
    version     BIGINT         NOT NULL,
    CONSTRAINT uk_account_system_code UNIQUE (system_code),
    CONSTRAINT ck_account_type CHECK (type IN ('ASSET', 'LIABILITY'))
);

-- The bank's own (internal) accounts
INSERT INTO account (name, type, system_code, currency, balance, version)
VALUES ('Cash', 'ASSET', 'CASH', 'GBP', 0, 0);
INSERT INTO account (name, type, system_code, currency, balance, version)
VALUES ('Faster Payments settlement', 'LIABILITY', 'FPS_SETTLEMENT', 'GBP', 0, 0);
INSERT INTO account (name, type, system_code, currency, balance, version)
VALUES ('Reserves at the Bank of England', 'ASSET', 'BOE_RESERVES', 'GBP', 0, 0);

-- One row = one balanced two-leg entry: debit one account, credit another, same amount
CREATE TABLE ledger_entry (
    id                BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    debit_account_id  BIGINT                   NOT NULL REFERENCES account (id),
    credit_account_id BIGINT                   NOT NULL REFERENCES account (id),
    amount            NUMERIC(19, 2)           NOT NULL,
    currency          VARCHAR(3)               NOT NULL,
    description       VARCHAR(200)             NOT NULL,
    payment_id        BIGINT REFERENCES payment (id),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_ledger_entry_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_ledger_entry_two_accounts CHECK (debit_account_id <> credit_account_id)
);

CREATE INDEX ix_ledger_entry_payment ON ledger_entry (payment_id);

ALTER TABLE payment ADD CONSTRAINT fk_payment_debtor_account
    FOREIGN KEY (debtor_account_id) REFERENCES account (id);
```

- **`system_code`** marks the bank's internal accounts. Customer accounts leave it `NULL`, and the unique constraint means there's only ever one `FPS_SETTLEMENT`. The three seeded accounts get ids 1, 2 and 3 on a fresh database, so your first customer is account 4.
- **`payment_id`** links each ledger entry to the payment that caused it. Later you'll ask "show me every entry for payment 7".
- **The two `CHECK`s** make a negative amount, or debiting and crediting the same account, impossible at the database level.
- **`version`** is for `@Version` optimistic locking. You'll see it earn its keep in step 8.

### 3. Accounts

Create `ledger/AccountType.java`:

```java
package com.example.payments.ledger;

/** A customer's account is money the bank owes (LIABILITY). The bank's cash and reserves are ASSETs. */
public enum AccountType {
    ASSET,
    LIABILITY
}
```

And `ledger/Account.java`:

```java
package com.example.payments.ledger;

import com.example.payments.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
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

    @Column(name = "system_code", length = 30)
    private String systemCode;

    @Column(name = "currency", nullable = false, length = 3)
    private String currencyCode;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Version
    private long version;

    protected Account() {
        // for JPA
    }

    public static Account openCustomerAccount(String name) {
        Account account = new Account();
        account.name = name;
        account.type = AccountType.LIABILITY;
        account.currencyCode = Money.GBP.getCurrencyCode();
        account.balance = BigDecimal.ZERO;
        return account;
    }

    /** Package-private: only the LedgerService moves money. */
    void debit(Money amount) {
        // A debit increases an asset and decreases a liability
        balance = type == AccountType.ASSET ? balance.add(amount.amount()) : balance.subtract(amount.amount());
    }

    void credit(Money amount) {
        // A credit decreases an asset and increases a liability
        balance = type == AccountType.ASSET ? balance.subtract(amount.amount()) : balance.add(amount.amount());
    }

    public boolean isInternal() {
        return systemCode != null;
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

    public String getSystemCode() {
        return systemCode;
    }

    public Money getBalance() {
        return new Money(balance, Currency.getInstance(currencyCode));
    }
}
```

- **`debit` and `credit` are package-private.** Only code in the `ledger` package can move a balance, and in practice only `LedgerService` does. Nobody in the `payment` package can write `account.setBalance(…)`, because there's no such method.
- **The direction rules live in one place.** "A debit increases an asset and decreases a liability" is the whole of double entry's sign convention, and it's here, next to the balance it changes.
- **`openCustomerAccount`** is the only way to create an account in code, and it always makes a GBP liability. Internal accounts come from the migration.

> **Simplification:** a real customer account also has its own sort code and account number, an owner, a product type and an arranged overdraft limit. Here, the payer is identified by our internal account id.

### 4. Ledger entries

Create `ledger/LedgerEntry.java`:

```java
package com.example.payments.ledger;

import com.example.payments.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;

/** One balanced movement: debit one account, credit another, by the same amount. Never changed once written. */
@Entity
@Immutable
@Table(name = "ledger_entry")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "debit_account_id")
    private Account debitAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credit_account_id")
    private Account creditAccount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currencyCode;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
        // for JPA
    }

    LedgerEntry(Account debitAccount, Account creditAccount, Money amount, String description, Long paymentId,
                Instant createdAt) {
        this.debitAccount = debitAccount;
        this.creditAccount = creditAccount;
        this.amount = amount.amount();
        this.currencyCode = amount.currency().getCurrencyCode();
        this.description = description;
        this.paymentId = paymentId;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Account getDebitAccount() {
        return debitAccount;
    }

    public Account getCreditAccount() {
        return creditAccount;
    }

    public Money getAmount() {
        return new Money(amount, Currency.getInstance(currencyCode));
    }

    public String getDescription() {
        return description;
    }

    public Long getPaymentId() {
        return paymentId;
    }
}
```

**`@Immutable`** (Hibernate's) tells Hibernate never to issue an `UPDATE` for this entity. Ledgers are insert-only: a mistake is corrected with a new, opposite entry, never by editing history. Note `paymentId` is a plain `Long`, not a `@ManyToOne Payment`: the ledger package doesn't need to know what a payment is.

### 5. Repositories and the ledger service

`ledger/AccountRepository.java`:

```java
package com.example.payments.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findBySystemCode(String systemCode);
}
```

`ledger/LedgerEntryRepository.java`:

```java
package com.example.payments.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByPaymentIdOrderById(Long paymentId);
}
```

`ledger/AccountNotFoundException.java`:

```java
package com.example.payments.ledger;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(Long id) {
        super("No account with id " + id);
    }
}
```

`ledger/LedgerService.java`:

```java
package com.example.payments.ledger;

import com.example.payments.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** A deliberately small ledger: just enough to take a payment's money and give it back. */
@Service
public class LedgerService {

    public static final String CASH = "CASH";
    public static final String FPS_SETTLEMENT = "FPS_SETTLEMENT";
    public static final String BOE_RESERVES = "BOE_RESERVES";

    private final AccountRepository accounts;
    private final LedgerEntryRepository entries;
    private final Clock clock;

    public LedgerService(AccountRepository accounts, LedgerEntryRepository entries, Clock clock) {
        this.accounts = accounts;
        this.entries = entries;
        this.clock = clock;
    }

    @Transactional
    public Account openCustomerAccount(String name) {
        return accounts.save(Account.openCustomerAccount(name));
    }

    @Transactional(readOnly = true)
    public Account get(Long id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Account getSystemAccount(String systemCode) {
        return accounts.findBySystemCode(systemCode)
                .orElseThrow(() -> new IllegalStateException("Missing system account " + systemCode));
    }

    /** Debit one account and credit another by the same amount, in the caller's transaction. */
    @Transactional
    public LedgerEntry post(Account debit, Account credit, Money amount, String description, Long paymentId) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Ledger amounts must be positive, not " + amount);
        }
        debit.debit(amount);
        credit.credit(amount);
        return entries.save(new LedgerEntry(debit, credit, amount, description, paymentId, clock.instant()));
    }

    /** Demo and test helper: a customer pays cash in over the counter. */
    @Transactional
    public LedgerEntry depositCash(Long accountId, Money amount) {
        Account customer = get(accountId);
        Account cash = getSystemAccount(CASH);
        return post(cash, customer, amount, "Cash deposit", null);
    }
}
```

`post` changes both cached balances and writes the entry **in the caller's transaction** (the default `REQUIRED` propagation joins it). The balance updates and the entry commit together or not at all. There's no `save(account)` call: the accounts are managed entities, so Hibernate writes their changed balances when the transaction commits.

`depositCash` exists so you have money to pay with. A payments system wouldn't normally take cash deposits.

### 6. An accounts API

So you can open accounts and fund them by hand, create `ledger/AccountController.java`:

```java
package com.example.payments.ledger;

import com.example.payments.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final LedgerService ledger;

    public AccountController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request) {
        Account account = ledger.openCustomerAccount(request.name());
        return ResponseEntity.created(URI.create("/accounts/" + account.getId()))
                .body(AccountResponse.from(account));
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable Long id) {
        return AccountResponse.from(ledger.get(id));
    }

    /** Demo helper so you have money to pay with. */
    @PostMapping("/{id}/deposits")
    public AccountResponse deposit(@PathVariable Long id, @Valid @RequestBody DepositRequest request) {
        ledger.depositCash(id, Money.gbp(request.amount().toPlainString()));
        return AccountResponse.from(ledger.get(id));
    }

    public record OpenAccountRequest(@NotBlank @Size(max = 100) String name) {
    }

    public record DepositRequest(@NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount) {
    }

    public record AccountResponse(Long id, String name, AccountType type, Money balance) {

        static AccountResponse from(Account account) {
            return new AccountResponse(account.getId(), account.getName(), account.getType(), account.getBalance());
        }
    }
}
```

### 7. Take the money

Create `payment/InvalidPaymentException.java`:

```java
package com.example.payments.payment;

public class InvalidPaymentException extends RuntimeException {

    public InvalidPaymentException(String message) {
        super(message);
    }
}
```

Now update `PaymentService`. This first version has the obvious read-check-write shape, **deliberately**:

```java
package com.example.payments.payment;

import com.example.payments.ledger.Account;
import com.example.payments.ledger.LedgerService;
import com.example.payments.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final LedgerService ledger;
    private final PaymentProperties properties;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, LedgerService ledger, PaymentProperties properties,
                          Clock clock) {
        this.payments = payments;
        this.ledger = ledger;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Payment create(Long debtorAccountId, Creditor creditor, Money amount, String reference) {
        Account debtor = ledger.get(debtorAccountId);
        if (debtor.isInternal()) {
            throw new InvalidPaymentException("Payments can only be made from customer accounts");
        }
        Instant now = clock.instant();
        Payment payment = payments.save(Payment.receive(debtorAccountId, creditor, amount, reference, now));

        Money limit = new Money(properties.singlePaymentLimit(), amount.currency());
        if (limit.isLessThan(amount)) {
            payment.reject(RejectionReason.AM02, now);
        } else if (debtor.getBalance().isLessThan(amount)) {
            payment.reject(RejectionReason.AM04, now);
        } else {
            payment.markValidated(now);
            Account settlement = ledger.getSystemAccount(LedgerService.FPS_SETTLEMENT);
            ledger.post(debtor, settlement, amount, "Faster Payment to " + creditor.name(), payment.getId());
        }
        return payment;
    }

    @Transactional(readOnly = true)
    public Payment get(Long id) {
        return payments.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }
}
```

What changed:

- **An unknown debtor is a `404`** (`ledger.get` throws `AccountNotFoundException`), and paying *from* one of the bank's own accounts is refused outright. Neither creates a payment: they're not instructions the bank could ever carry out.
- **The payment is saved first.** With `IDENTITY` ids, `save` inserts immediately and the payment gets its id, which the ledger entry needs for its `payment_id` foreign key.
- **Insufficient funds is a business rejection**, recorded with ISO 20022 reason `AM04`, just like the limit check.
- **Validated means paid for**: the customer is debited and the FPS settlement account credited, in the same transaction as the status change.

Map the two new exceptions in `ApiExceptionHandler` (import `AccountNotFoundException`, `InvalidPaymentException`, `HttpStatus` and `ExceptionHandler`):

```java
    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail handle(AccountNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Account not found");
        return problem;
    }

    @ExceptionHandler(InvalidPaymentException.class)
    ProblemDetail handle(InvalidPaymentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setTitle("Invalid payment");
        return problem;
    }
```

`422 Unprocessable Content` means "I understood the request, and it breaks a rule". (Older code calls the same status `UNPROCESSABLE_ENTITY`; it was renamed in RFC 9110.)

### 8. Tests for the happy and unhappy paths

The guide 01 API tests pay from account `1`, which is now the bank's `CASH` account, so they'd get a `422`. Give `PaymentApiTest` a real, funded customer. Replace it with:

```java
package com.example.payments.payment;

import com.example.payments.PaymentsIntegrationTest;
import com.example.payments.ledger.LedgerService;
import com.example.payments.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

@PaymentsIntegrationTest
class PaymentApiTest {

    @Autowired MockMvcTester mvc;
    @Autowired LedgerService ledger;

    Long alice;

    @BeforeEach
    void openAccount() {
        alice = ledger.openCustomerAccount("Alice").getId();
        ledger.depositCash(alice, Money.gbp("100.00"));
    }

    @Test
    void createsAValidatedPaymentAndTakesTheMoney() {
        Money settlementBefore = ledger.getSystemAccount(LedgerService.FPS_SETTLEMENT).getBalance();

        MvcTestResult response = pay(alice, "25.00", "20-00-00");

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).headers().containsHeader("Location");
        assertThat(response).bodyJson().extractingPath("$.status").isEqualTo("VALIDATED");
        assertThat(response).bodyJson().extractingPath("$.amount.amount").isEqualTo("25.00");
        assertThat(response).bodyJson().extractingPath("$.creditor.sortCode").isEqualTo("20-00-00");
        assertThat(ledger.get(alice).getBalance()).isEqualTo(Money.gbp("75.00"));
        assertThat(ledger.getSystemAccount(LedgerService.FPS_SETTLEMENT).getBalance())
                .isEqualTo(settlementBefore.plus(Money.gbp("25.00")));
    }

    @Test
    void rejectsAPaymentOverTheLimitButStillRecordsIt() {
        MvcTestResult response = pay(alice, "25000.01", "200000");

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(response).bodyJson().extractingPath("$.rejectionReason").isEqualTo("AM02");
    }

    @Test
    void rejectsAPaymentTheCustomerCannotAfford() {
        MvcTestResult response = pay(alice, "100.01", "200000");

        assertThat(response).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(response).bodyJson().extractingPath("$.rejectionReason").isEqualTo("AM04");
        assertThat(ledger.get(alice).getBalance()).isEqualTo(Money.gbp("100.00"));
    }

    @Test
    void returns404ForAnUnknownDebtorAccount() {
        assertThat(pay(999_999L, "1.00", "200000")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void refusesToPayFromTheBanksOwnAccounts() {
        Long cash = ledger.getSystemAccount(LedgerService.CASH).getId();

        MvcTestResult response = pay(cash, "1.00", "200000");

        assertThat(response).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Invalid payment");
    }

    @Test
    void returnsFieldErrorsForABadSortCode() {
        MvcTestResult response = pay(alice, "25.00", "20-00");

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors['creditor.sortCode']")
                .isEqualTo("must be six digits, like 20-00-00");
    }

    @Test
    void fetchesAPaymentById() {
        String location = pay(alice, "10.00", "20-00-00").getResponse().getHeader("Location");

        assertThat(mvc.get().uri(location)).hasStatus(HttpStatus.OK)
                .bodyJson().extractingPath("$.reference").isEqualTo("Rent");
    }

    @Test
    void returnsProblemDetailForAnUnknownPayment() {
        MvcTestResult response = mvc.get().uri("/payments/999999").exchange();

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Payment not found");
    }

    private MvcTestResult pay(Long debtor, String amount, String sortCode) {
        return mvc.post().uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "debtorAccountId": %d,
                          "creditor": {"name": "Bob Smith", "sortCode": "%s", "accountNumber": "55779911"},
                          "amount": "%s",
                          "reference": "Rent"
                        }
                        """.formatted(debtor, sortCode, amount))
                .exchange();
    }
}
```

`createsAValidatedPaymentAndTakesTheMoney` measures the settlement account **before and after** rather than expecting an exact balance. Every test in the suite shares one in-memory database, so other tests have already moved money through that account. Each test creates its own customer and only makes exact assertions about accounts it owns.

`PaymentConstraintsTest` from guide 01 still inserts `debtor_account_id = 1` directly. That's now the `CASH` account, so the new foreign key is satisfied and the test still checks what it was written to check.

And `src/test/java/com/example/payments/ledger/AccountApiTest.java`:

```java
package com.example.payments.ledger;

import com.example.payments.PaymentsIntegrationTest;
import com.example.payments.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

@PaymentsIntegrationTest
class AccountApiTest {

    @Autowired MockMvcTester mvc;
    @Autowired LedgerService ledger;

    @Test
    void opensAnAccountAndTakesADeposit() {
        MvcTestResult opened = mvc.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Alice"}
                        """)
                .exchange();
        assertThat(opened).hasStatus(HttpStatus.CREATED);
        assertThat(opened).bodyJson().extractingPath("$.type").isEqualTo("LIABILITY");
        String location = opened.getResponse().getHeader("Location");

        MvcTestResult deposited = mvc.post().uri(location + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount": "100.00"}
                        """)
                .exchange();

        assertThat(deposited).hasStatus(HttpStatus.OK);
        assertThat(deposited).bodyJson().extractingPath("$.balance.amount").isEqualTo("100.00");
    }

    @Test
    void aCashDepositIncreasesBothTheCustomerAndTheBanksCash() {
        Long alice = ledger.openCustomerAccount("Alice").getId();
        Money cashBefore = ledger.getSystemAccount(LedgerService.CASH).getBalance();

        ledger.depositCash(alice, Money.gbp("40.00"));

        // Debit CASH (an asset goes up), credit Alice (a liability goes up)
        assertThat(ledger.get(alice).getBalance()).isEqualTo(Money.gbp("40.00"));
        assertThat(ledger.getSystemAccount(LedgerService.CASH).getBalance())
                .isEqualTo(cashBefore.plus(Money.gbp("40.00")));
    }
}
```

The second test pins down the counter-intuitive part of double entry: a single debit-and-credit makes **both** balances go up, because one account is an asset and the other a liability.

Run everything. It all passes. Every request runs on its own, one after another, so the naive service looks correct. Sequential tests can't find concurrency bugs.

### 9. Ten payments at once

Alice has £100. Ten threads each try to pay Bob £20 **at the same instant**. The correct outcome: exactly five `VALIDATED`, five `REJECTED`, and Alice at £0.00.

Create `src/test/java/com/example/payments/payment/ConcurrentPaymentTest.java`:

```java
package com.example.payments.payment;

import com.example.payments.PaymentsIntegrationTest;
import com.example.payments.ledger.LedgerService;
import com.example.payments.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@PaymentsIntegrationTest
class ConcurrentPaymentTest {

    private static final Creditor BOB = new Creditor("Bob Smith", SortCode.of("20-00-00"), "55779911");

    @Autowired PaymentService paymentService;
    @Autowired LedgerService ledger;

    @Test
    void tenPaymentsAtOnceCannotSpendTheSameMoneyTwice() throws Exception {
        Long alice = ledger.openCustomerAccount("Alice").getId();
        ledger.depositCash(alice, Money.gbp("100.00"));

        CountDownLatch startingGun = new CountDownLatch(1);
        List<PaymentStatus> outcomes = new ArrayList<>();
        List<Throwable> unexpected = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    try {
                        Payment payment = paymentService.create(alice, BOB, Money.gbp("20.00"), "Rent");
                        synchronized (outcomes) {
                            outcomes.add(payment.getStatus());
                        }
                    } catch (RuntimeException e) {
                        synchronized (unexpected) {
                            unexpected.add(e);
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

        System.out.printf("outcomes=%s unexpected=%s balance=%s%n", outcomes,
                unexpected.stream().map(t -> t.getClass().getSimpleName()).toList(),
                ledger.get(alice).getBalance());

        assertThat(unexpected).isEmpty();
        assertThat(outcomes).filteredOn(s -> s == PaymentStatus.VALIDATED).hasSize(5);
        assertThat(outcomes).filteredOn(s -> s == PaymentStatus.REJECTED).hasSize(5);
        assertThat(ledger.get(alice).getBalance()).isEqualTo(Money.gbp("0.00"));
    }
}
```

How it works:

- **`CountDownLatch startingGun`** makes the race reliable. Every thread is created and then blocks on `await()`. Only when all ten are waiting does `countDown()` release them together. Without it, the first thread would often finish before the last had started, and the test would pass by luck.
- **`ExecutorService` in try-with-resources** (Java 19+): closing the pool waits for all the tasks.
- **`synchronized` around the shared lists**, because ten threads add to them at once.
- **The test is not `@Transactional`.** Each `create` call must run in its own real transaction on its own thread, exactly like ten HTTP requests. Data created inside a test-level transaction wouldn't even be visible to the worker threads.

### 10. Watch it fail

Run `ConcurrentPaymentTest`:

```
outcomes=[VALIDATED, VALIDATED] unexpected=[ObjectOptimisticLockingFailureException, ObjectOptimisticLockingFailureException, ObjectOptimisticLockingFailureException, ObjectOptimisticLockingFailureException, ObjectOptimisticLockingFailureException, ObjectOptimisticLockingFailureException, ObjectOptimisticLockingFailureException, ObjectOptimisticLockingFailureException] balance=GBP 60.00
```

Read it carefully:

- **The money is right.** Two payments of £20 went through, and Alice has £60. Nothing was double-spent.
- **The outcome is wrong.** Eight payments failed with an exception, though Alice could have afforded three more. In production those customers would see a `500`.

Each failure says why:

```
ObjectOptimisticLockingFailureException: Unexpected row count (expected row count 1 but was 0)
[update account set balance=?,currency=?,name=?,system_code=?,type=?,version=? where id=? and version=?]
```

That's `@Version` at work. All ten transactions read Alice's account at version *n*. The first to commit wrote `… where id=? and version=n` and bumped the version. Every later `UPDATE` with `version=n` matched **zero rows**, so Hibernate refused to overwrite a balance that had changed since it was read, and rolled the transaction back. Without `@Version`, those updates would have silently overwritten each other: a **lost update**, and real money gone missing. (The Core ledger tutorial shows that version of the bug, step by step.)

So optimistic locking makes the race **safe**, but for a busy account it's the wrong tool: it rejects good payments just for colliding. You could catch the exception and retry the whole transaction, but when many requests hit one account at once, most retries collide again.

### 11. Fix it: lock, then check

Make requests for the same account queue up instead. Add two locking queries to `AccountRepository` (import `jakarta.persistence.LockModeType`, `org.springframework.data.jpa.repository.Lock` and `org.springframework.data.jpa.repository.Query`):

```java
    /** SELECT ... FOR UPDATE: other transactions wanting this row wait until we commit. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.systemCode = :systemCode")
    Optional<Account> findBySystemCodeForUpdate(String systemCode);
```

`@Lock(PESSIMISTIC_WRITE)` makes Hibernate add `FOR UPDATE` to the SQL. Expose them from `LedgerService`:

```java
    /** Loads and locks the account until the surrounding transaction ends. */
    @Transactional
    public Account getForUpdate(Long id) {
        return accounts.findByIdForUpdate(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Transactional
    public Account getSystemAccountForUpdate(String systemCode) {
        return accounts.findBySystemCodeForUpdate(systemCode)
                .orElseThrow(() -> new IllegalStateException("Missing system account " + systemCode));
    }
```

A row lock lasts until the **transaction** ends. These methods join the caller's transaction, so the lock is held right through the balance check, the posting and the commit.

Use them in `PaymentService.create`. Replace the first line:

```java
        // Lock the payer's account first: concurrent payments from it now queue here
        Account debtor = ledger.getForUpdate(debtorAccountId);
```

and the settlement account lookup:

```java
            Account settlement = ledger.getSystemAccountForUpdate(LedgerService.FPS_SETTLEMENT);
```

And in `LedgerService.depositCash`:

```java
        // Lock order everywhere: the customer first, then the internal account
        Account customer = getForUpdate(accountId);
        Account cash = getSystemAccountForUpdate(CASH);
```

Two rules are at work:

1. **Lock on the first read.** Don't load the account normally and lock it later in the same transaction: Hibernate would already be holding a possibly stale copy. `getForUpdate` replaces `get` rather than following it.
2. **Always lock in the same order**: the customer's account, then the internal account. If one code path locked settlement-then-customer while another locked customer-then-settlement, two transactions could each hold the lock the other needs and wait forever, a **deadlock**. The database would detect it and kill one of them. A consistent order makes the cycle impossible.

Run `ConcurrentPaymentTest` again:

```
outcomes=[VALIDATED, VALIDATED, VALIDATED, VALIDATED, VALIDATED, REJECTED, REJECTED, REJECTED, REJECTED, REJECTED] unexpected=[] balance=GBP 0.00
```

Green. The ten transactions queued on Alice's row and ran one after another. The sixth saw £0.00 and was rejected properly, with reason `AM04`. Run it a few more times: a concurrency test must pass **every** time.

> **Sidebar: H2 vs PostgreSQL.** Both support `SELECT … FOR UPDATE` and both detect deadlocks, so this behaves the same way on PostgreSQL. Spring translates both databases' lock errors into the same exception hierarchy, so your code doesn't change. Keep `@Version` too: it costs almost nothing and catches any future code path that forgets to lock.

### 12. Try it

Restart the app (with `data/` deleted, see step 1) and add to `requests.http`:

```http
### Open Alice's account (account 4 on a fresh database)
POST http://localhost:8080/accounts
Content-Type: application/json

{"name": "Alice"}

### Pay £100 into it
POST http://localhost:8080/accounts/4/deposits
Content-Type: application/json

{"amount": "100.00"}

### Alice pays Bob £25
POST http://localhost:8080/payments
Content-Type: application/json

{
  "debtorAccountId": 4,
  "creditor": {"name": "Bob Smith", "sortCode": "20-00-00", "accountNumber": "55779911"},
  "amount": "25.00",
  "reference": "Rent"
}

### Alice tries to pay £80 (she only has £75 left)
POST http://localhost:8080/payments
Content-Type: application/json

{
  "debtorAccountId": 4,
  "creditor": {"name": "Bob Smith", "sortCode": "20-00-00", "accountNumber": "55779911"},
  "amount": "80.00",
  "reference": "Car"
}

### Alice's account
GET http://localhost:8080/accounts/4

### The bank's FPS settlement account
GET http://localhost:8080/accounts/2
```

Change the existing guide 01 requests' `debtorAccountId` from `1` to `4` as well. Run them in order. The £25 payment is `VALIDATED`, the £80 one is `REJECTED` with `"rejectionReason": "AM04"`, and the two accounts show where the money went:

```json
{"id":4,"name":"Alice","type":"LIABILITY","balance":{"amount":"75.00","currency":"GBP"}}
```

```json
{"id":2,"name":"Faster Payments settlement","type":"LIABILITY","balance":{"amount":"25.00","currency":"GBP"}}
```

Alice's £25 hasn't left the bank. It's sitting in the settlement account as money the bank owes, until the payment is sent and settled. Paying from account `1` (the bank's cash) gets:

```json
{"detail":"Payments can only be made from customer accounts","instance":"/payments","status":422,"title":"Invalid payment"}
```

---

## Your turn

### Exercise 1: the hot account

`ConcurrentPaymentTest` has one customer racing herself. Write `ManyPayersTest`: ten **different** customers, each with £10, all paying £10 at the same instant. Every payment must be `VALIDATED`, and the FPS settlement account must go up by exactly £100.

Then, to see why the settlement account needs its lock, temporarily change `PaymentService` back to `ledger.getSystemAccount(LedgerService.FPS_SETTLEMENT)` (no lock) and run it again. What happens, and why? Put the lock back afterwards.

<details>
<summary>Solution</summary>

`src/test/java/com/example/payments/payment/ManyPayersTest.java`:

```java
package com.example.payments.payment;

import com.example.payments.PaymentsIntegrationTest;
import com.example.payments.ledger.LedgerService;
import com.example.payments.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Ten different customers pay at once. They share only the FPS settlement account. */
@PaymentsIntegrationTest
class ManyPayersTest {

    private static final Creditor BOB = new Creditor("Bob Smith", SortCode.of("20-00-00"), "55779911");

    @Autowired PaymentService paymentService;
    @Autowired LedgerService ledger;

    @Test
    void everyCustomersPaymentGoesThrough() throws Exception {
        List<Long> customers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Long id = ledger.openCustomerAccount("Customer " + i).getId();
            ledger.depositCash(id, Money.gbp("10.00"));
            customers.add(id);
        }
        Money settlementBefore = ledger.getSystemAccount(LedgerService.FPS_SETTLEMENT).getBalance();

        CountDownLatch startingGun = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            List<Future<Payment>> futures = new ArrayList<>();
            for (Long id : customers) {
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    return paymentService.create(id, BOB, Money.gbp("10.00"), "Rent");
                }));
            }
            startingGun.countDown();
            for (Future<Payment> future : futures) {
                assertThat(future.get().getStatus()).isEqualTo(PaymentStatus.VALIDATED);
            }
        }

        assertThat(ledger.getSystemAccount(LedgerService.FPS_SETTLEMENT).getBalance())
                .isEqualTo(settlementBefore.plus(Money.gbp("100.00")));
    }
}
```

Without the lock on the settlement account, it fails:

```
java.util.concurrent.ExecutionException: org.springframework.orm.ObjectOptimisticLockingFailureException:
Unexpected row count (expected row count 1 but was 0) [update account set balance=?, … where id=? and version=?]
for entity [com.example.payments.ledger.Account with id '2']
```

Account `2` is `FPS_SETTLEMENT`. The ten customers' locks don't conflict, because they're different rows, but all ten transactions update the **same** settlement row. `@Version` catches the collision, and payments fail. Locking the settlement account makes them queue on it instead: correct, but every payment in the bank now passes through that one row lock. That's a hot account, and why real ledgers split, batch or stop caching such balances.

</details>

### Exercise 2: do the books balance?

In double entry, everything the bank owns equals everything it owes, at all times: the total of all asset balances equals the total of all liability balances. (A real balance sheet also has equity, which this toy bank doesn't have.) Add `boolean booksBalance()` to `LedgerService`, using one aggregate query, and assert it in `ManyPayersTest` and in a new `AccountApiTest` test.

<details>
<summary>Solution</summary>

`AccountRepository` (import `java.math.BigDecimal`):

```java
    @Query("select coalesce(sum(a.balance), 0) from Account a where a.type = :type")
    BigDecimal totalBalance(AccountType type);
```

`LedgerService`:

```java
    /** The bank's books balance when everything it owns equals everything it owes. */
    @Transactional(readOnly = true)
    public boolean booksBalance() {
        return accounts.totalBalance(AccountType.ASSET).compareTo(accounts.totalBalance(AccountType.LIABILITY)) == 0;
    }
```

At the end of `ManyPayersTest.everyCustomersPaymentGoesThrough`:

```java
        assertThat(ledger.booksBalance()).isTrue();
```

In `AccountApiTest`:

```java
    @Test
    void theBooksBalance() {
        Long alice = ledger.openCustomerAccount("Alice").getId();
        ledger.depositCash(alice, Money.gbp("12.34"));

        assertThat(ledger.booksBalance()).isTrue();
    }
```

`compareTo`, not `equals`: the two sums could come back with different scales. `coalesce(…, 0)` handles a type with no accounts.

This works across the whole shared test database, whatever other tests have done, because **every** entry is balanced. It's a cheap invariant worth asserting anywhere money moves.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
```

Run it two or three times. Concurrency tests must pass every time.

New files since guide 01:

```
src/main/java/com/example/payments/
├── ledger/
│   ├── Account.java
│   ├── AccountController.java
│   ├── AccountNotFoundException.java
│   ├── AccountRepository.java
│   ├── AccountType.java
│   ├── LedgerEntry.java
│   ├── LedgerEntryRepository.java
│   └── LedgerService.java
└── payment/InvalidPaymentException.java
src/main/resources/db/migration/
└── V3__create_ledger.sql
src/test/java/com/example/payments/
├── ledger/AccountApiTest.java
└── payment/
    ├── ConcurrentPaymentTest.java
    └── ManyPayersTest.java
```

Modified: `PaymentService.java`, `ApiExceptionHandler.java`, `PaymentApiTest.java`, `requests.http`.

---

## Common errors

**`Migration of schema "PUBLIC" to version "3 - create ledger" failed!` … `Referential integrity constraint violation: "FK_PAYMENT_DEBTOR_ACCOUNT: PUBLIC.PAYMENT FOREIGN KEY(DEBTOR_ACCOUNT_ID) REFERENCES PUBLIC.ACCOUNT(ID)"`**
Your database still has guide 01 payments whose `debtor_account_id` doesn't match any account. Stop the app, delete `data/`, and start again (step 1).

**`ObjectOptimisticLockingFailureException: Unexpected row count (expected row count 1 but was 0) [update account …]`**
Two transactions updated the same account row and `@Version` stopped the second. Some code path is changing an account it didn't lock. Find it, and load the account with `getForUpdate` (or `getSystemAccountForUpdate`) instead of `get`.

**`422` with `"Payments can only be made from customer accounts"`**
You're paying from account 1, 2 or 3, which are the bank's internal accounts on a fresh database. Open a customer account and use its id (4 on a fresh database).

**The concurrency test passes even before the fix**
The threads aren't really overlapping. Check that every task calls `startingGun.await()` **before** doing any work, and that `countDown()` happens only after all the tasks are submitted.

**Worker threads can't see the account the test created**
The test class or method is `@Transactional`, so the account hasn't been committed. Concurrency tests must not be `@Transactional`.

**Locking seems to do nothing**
`getForUpdate` was called outside a transaction, so the lock was released as soon as the query finished. `PaymentService.create` must be `@Transactional` and called **through the Spring bean**, not as `this.create(…)` from inside the same class (which bypasses the transactional proxy).

---

## Recap

- A Faster Payment debits the customer **straight away**, into the bank's **FPS settlement** account: money the bank owes other banks until the settlement cycle moves its **reserves at the Bank of England**.
- A payments system needs only a **small ledger**: accounts with cached balances, and balanced, insert-only entries. Debits and credits move assets and liabilities in opposite directions.
- **`@Transactional` is not mutual exclusion.** Two transactions can read the same balance before either writes it.
- **`@Version`** turns lost updates into safe failures, but under contention it rejects good payments.
- **`SELECT … FOR UPDATE`** makes payments from one account queue up. **Lock on the first read**, and **always in the same order** (customer, then internal account) to avoid deadlocks.
- A shared internal account is a **hot account**: every payment serialises on it.
- **Concurrency bugs need concurrency tests**: a `CountDownLatch` starting gun, no test-level transaction, and assertions on the invariants.

### Interview angle

<details>
<summary>"A customer sends a Faster Payment. Walk me through the accounting."</summary>

When the payment is validated, debit the customer's account (a liability to the bank, so it decreases) and credit an internal scheme settlement account (also a liability: money owed via the scheme). The customer's balance drops immediately. If the scheme rejects the payment, post the opposite entry to return the funds, never editing the first. When the scheme's settlement cycle completes, debit the settlement account and credit the bank's reserves account at the central bank (an asset, which decreases). Every entry is balanced, so total assets always equal total liabilities plus equity.

</details>

<details>
<summary>"Ten payments from the same account arrive at once. How do you stop an overdraft?"</summary>

Serialise work on that account. In a relational database, load the account with `SELECT … FOR UPDATE` (in JPA, `@Lock(PESSIMISTIC_WRITE)`) at the start of the transaction, then check the balance and post while holding the lock. Explain why `@Transactional` alone isn't enough under READ COMMITTED. Keep a `@Version` column as a safety net. Lock multiple accounts in a consistent order to avoid deadlocks. Prove it with a multi-threaded test using a latch, asserting the exact number of successes and the final balance.

</details>

<details>
<summary>"What's wrong with every payment updating one settlement account?"</summary>

It's a hot account. With pessimistic locking, every payment in the bank serialises on that row, which caps throughput. With optimistic locking, they collide and fail. Options: split it into several sub-accounts and spread postings across them (the real balance is the sum), batch postings to it periodically, or don't maintain a cached balance for it and derive it when needed.

</details>

### Further reading

- [PostgreSQL docs: Explicit Locking](https://www.postgresql.org/docs/current/explicit-locking.html): row locks and deadlocks, clearly explained
- [Spring Data JPA reference: Locking](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)
- [Pay.UK: Faster Payments System Principles](https://www.wearepay.uk/wp-content/uploads/2026/02/Pay.UK-Faster-Payments-System-Principles-v-11-Jan-2026.pdf) (PDF): section 6 describes settlement and prefunding
- [Bank of England: RTGS service description](https://www.bankofengland.co.uk/-/media/boe/files/payments/rtgspfmidesc): how the schemes settle across the Bank's accounts

**Simplification to notice:** no arranged overdrafts. With one, the check becomes `balance + overdraftLimit >= amount`, and the locking story is exactly the same.

**Next:** [03: Idempotency in depth](03-idempotency-in-depth.md). What happens when the customer's app times out and sends the same payment again.
