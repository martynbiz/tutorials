# 04: Repayments, Arrears and Concurrency

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- How a loan repayment is **allocated**: oldest instalment first, interest before principal
- Why a repayment that arrives after end of day must be **value-dated tomorrow**
- **Arrears**: days past due (DPD), the 30-day **arrears buckets**, and what UK rules expect when a customer falls behind
- **Early settlement**: the customer's legal right to repay early, and how a settlement figure is built
- Adding a step to a Spring Batch job, and reusing a step-scoped reader
- A **concurrency test** where ten repayments race each other *and* the nightly batch on the same loan, failing first with lost updates and overpaid interest
- **Pessimistic locking** of an aggregate root (`SELECT … FOR UPDATE` on the loan), and a **lock-ordering rule** that every code path follows

By the end, customers can repay, EOD tracks who is behind and by how much, and every balance stays correct when payments and the batch collide.

---

## Concepts

### How it works in a real bank

Most UK personal loan repayments are collected by **Direct Debit** on the due date. Direct Debits run through [Bacs](../glossary.md#bacs), which takes three working days, so the lender submits the collection in advance to arrive on the due date. Customers can also pay by card or bank transfer, or overpay through their app. In this tutorial, every repayment is taken from the customer's current account at the same bank, which keeps the money movement inside one ledger.

When a repayment arrives, the bank has to decide which debt it pays off. That's **allocation** (sometimes called the payment **waterfall** or **hierarchy**):

1. **Oldest instalment first.** If two instalments are overdue, the older one is cleared first. That brings the arrears down fastest and stops the oldest debt ageing further.
2. **Within an instalment, interest before principal.** This is the conventional order for an amortising loan and matches how the schedule was built: each instalment's interest is covered before its principal.

The exact order is set by the credit agreement and the bank's policy (fees, default interest and insurance can all have their own places in it), and it matters: allocation decides how much interest the customer pays over the life of the loan. Getting it wrong is the kind of error that leads to customer remediation programmes.

### Arrears and days past due

A customer who hasn't paid an instalment by its due date is **in arrears**. Banks track arrears with one number above all others: **days past due (DPD)**, the number of days since the due date of the **oldest** instalment that hasn't been paid in full. An instalment isn't past due on its own due date. It becomes 1 DPD the day after.

Loans are then grouped into **arrears buckets**, usually in 30-day bands:

| Bucket | DPD | Typically means |
|---|---|---|
| Up to date | 0 | Nothing overdue |
| 1–29 | 1 to 29 | One missed payment, often a timing problem |
| 30–59 | 30 to 59 | One to two missed payments |
| 60–89 | 60 to 89 | Two to three missed payments |
| 90+ | 90 or more | Serious arrears |

The buckets drive almost everything downstream: collections activity, credit reference reporting, provisions in the accounts, and risk reporting. The **90-day** threshold is the one to remember: under the prudential rules UK banks apply, a borrower who is more than 90 days past due on a material credit obligation is treated as **in default** ([Article 178 of the Capital Requirements Regulation](https://www.legislation.gov.uk/eur/2013/575/article/178)), alongside a judgement that they're unlikely to pay.

UK consumer credit law and FCA rules also attach obligations to arrears. Two that a loans system has to support:

- **Notices of sums in arrears.** For regulated fixed-sum loans with monthly payments, once at least two payments have fallen due and the shortfall is at least the total of the last two payments, the lender must send a statutory **notice of sums in arrears**, within 14 days, and then at least every six months while the arrears continue ([Consumer Credit Act 1974, s.86B](https://www.legislation.gov.uk/ukpga/1974/39/section/86B)). You'll build the trigger in Exercise 2.
- **Treating customers in arrears fairly.** The FCA's rules in [CONC 7.3](https://handbook.fca.org.uk/handbook/CONC/7/3.html) require lenders to treat customers in arrears or default with forbearance and due consideration, for example by considering suspended or reduced payments for customers in financial difficulty. In software terms, that means arrangements and payment plans, which this tutorial leaves out.

### Early settlement

Under the [Consumer Credit Act 1974, s.94](https://www.legislation.gov.uk/ukpga/1974/39/section/94), a borrower under a regulated consumer credit agreement can repay early, in full or in part, by giving notice to the lender. The settlement figure is broadly what's still owed, less a **rebate** of interest for the period that's no longer needed. The rebate calculation is set out in regulations made under s.95 (the [Consumer Credit (Early Settlement) Regulations 2004](https://www.legislation.gov.uk/uksi/2004/1483/contents)). For fixed-rate loans, [s.95A](https://www.legislation.gov.uk/ukpga/1974/39/section/95A) lets the lender claim compensation for its costs in some cases, capped at 1% of the amount repaid early if more than a year of the fixed period remains, or 0.5% otherwise, and only where the early repayments exceed £8,000 in a 12-month period.

**This tutorial's simplification:** interest here is charged daily on the outstanding principal, so there's no unearned future interest to rebate. The settlement quote in Exercise 1 is simply principal outstanding + billed interest unpaid + interest accrued so far, with no compensation and no notice period. Real settlement quotes follow the agreement and the regulations, and are typically valid until a stated date.

### Value dates and closed days

A repayment usually counts from the day it's made. But what if it arrives on 28 February **after** that night's EOD has already accrued interest for the 28th? The interest for that day was calculated on the higher balance and is already recorded. Value-dating the repayment to the 28th would make the stored accrual wrong (a **back-valuation**, which needs an interest adjustment).

This tutorial uses the simple, common rule: **once EOD has accrued a day for a loan, that day is closed. A later repayment is booked today but value-dated tomorrow.** That mirrors how payments after a bank's daily cut-off time are treated as received the next business day.

### The race

Guide 03's EOD and this guide's repayments both change the same loan. On the due date, the customer might pay through their app while the nightly batch is accruing their interest, and a retrying app might even send the payment twice. Every one of those paths reads the loan, makes a decision, and writes:

- A repayment reads "£52.93 interest due", pays £30 towards it, and writes "£30 paid".
- Another repayment, at the same moment, reads the same "£52.93 due", pays £30, and writes "£30 paid".
- EOD reads "principal £10,000" for the day's accrual while a repayment is reducing it.

That's the **lost update** problem, and `@Transactional` alone doesn't prevent it (the Core ledger tutorial walks through exactly why). The standard fix is to **serialise everything that changes one loan**: lock the loan row at the start of every transaction that touches the loan, so the others wait their turn. The loan is the **aggregate root**. Locking it protects everything it owns (instalments, its ledger accounts) as long as every code path takes that lock **first**.

---

## Steps

### 1. The migration

Create `src/main/resources/db/migration/V4__repayments_and_arrears.sql`:

```sql
-- How much of each billed instalment has been paid, interest and principal separately
ALTER TABLE loan_instalment ADD COLUMN interest_paid NUMERIC(19, 4) DEFAULT 0 NOT NULL;
ALTER TABLE loan_instalment ADD COLUMN principal_paid NUMERIC(19, 4) DEFAULT 0 NOT NULL;

-- Arrears status, as worked out by the last end-of-day run
ALTER TABLE loan ADD COLUMN days_past_due INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE loan ADD COLUMN arrears_bucket VARCHAR(20) DEFAULT 'UP_TO_DATE' NOT NULL;
ALTER TABLE loan ADD COLUMN arrears_as_at DATE;
```

`DEFAULT … NOT NULL` gives existing rows (the loans already in your `data/` database) sensible starting values.

### 2. Allocating a payment to an instalment

Add the two paid fields to `loan/Instalment.java`, after `billedOn`:

```java
    @Column(name = "interest_paid", nullable = false, precision = 19, scale = 4)
    private BigDecimal interestPaid = BigDecimal.ZERO;

    @Column(name = "principal_paid", nullable = false, precision = 19, scale = 4)
    private BigDecimal principalPaid = BigDecimal.ZERO;
```

And these methods at the end of the class:

```java
    public Money getInterestPaid() {
        return new Money(interestPaid, Money.GBP);
    }

    public Money getPrincipalPaid() {
        return new Money(principalPaid, Money.GBP);
    }

    /** Billed interest not yet paid. Zero until the instalment is billed. */
    public Money interestDue() {
        return isBilled() ? getBilledInterest().minus(getInterestPaid()) : Money.zero(Money.GBP);
    }

    /** Billed principal not yet paid. Zero until the instalment is billed. */
    public Money principalDue() {
        return isBilled() ? getBilledPrincipal().minus(getPrincipalPaid()) : Money.zero(Money.GBP);
    }

    public Money amountDue() {
        return interestDue().plus(principalDue());
    }

    /**
     * Applies as much of {@code available} as this instalment needs: interest first, then principal.
     * Returns what was taken.
     */
    public Allocation applyPayment(Money available) {
        Money toInterest = min(available, interestDue());
        Money toPrincipal = min(available.minus(toInterest), principalDue());
        this.interestPaid = getInterestPaid().plus(toInterest).amount();
        this.principalPaid = getPrincipalPaid().plus(toPrincipal).amount();
        return new Allocation(toInterest, toPrincipal);
    }

    private static Money min(Money a, Money b) {
        return a.isLessThan(b) ? a : b;
    }

    public record Allocation(Money interest, Money principal) {

        public Money total() {
            return interest.plus(principal);
        }
    }
}
```

The waterfall's inner rule lives on the instalment: `applyPayment` takes interest first, then principal, never more than is due, and reports what it took. An unbilled instalment has nothing due, so it takes nothing. `Allocation` is a **nested record**, a neat way to return two values without inventing a separate top-level class.

### 3. What the loan owes, and its arrears status

Create `loan/ArrearsBucket.java`:

```java
package com.example.loans.loan;

/** The usual 30-day arrears bands, by days past due (DPD). */
public enum ArrearsBucket {
    UP_TO_DATE,
    DPD_1_29,
    DPD_30_59,
    DPD_60_89,
    DPD_90_PLUS;

    public static ArrearsBucket forDaysPastDue(long days) {
        if (days < 0) {
            throw new IllegalArgumentException("days past due cannot be negative: " + days);
        }
        if (days == 0) {
            return UP_TO_DATE;
        } else if (days < 30) {
            return DPD_1_29;
        } else if (days < 60) {
            return DPD_30_59;
        } else if (days < 90) {
            return DPD_60_89;
        }
        return DPD_90_PLUS;
    }
}
```

In `loan/Loan.java`, map the three new columns, before the `instalments` field:

```java
    @Column(name = "days_past_due", nullable = false)
    private int daysPastDue;

    @Enumerated(EnumType.STRING)
    @Column(name = "arrears_bucket", nullable = false, length = 20)
    private ArrearsBucket arrearsBucket = ArrearsBucket.UP_TO_DATE;

    @Column(name = "arrears_as_at")
    private LocalDate arrearsAsAt;
```

And add these methods:

```java
    /** Everything billed and not yet paid. */
    public Money amountDue() {
        return instalments.stream().map(Instalment::amountDue).reduce(Money.zero(Money.GBP), Money::plus);
    }

    public void recordArrears(int daysPastDue, ArrearsBucket bucket, LocalDate asAt) {
        this.daysPastDue = daysPastDue;
        this.arrearsBucket = bucket;
        this.arrearsAsAt = asAt;
    }

    public int getDaysPastDue() {
        return daysPastDue;
    }

    public ArrearsBucket getArrearsBucket() {
        return arrearsBucket;
    }

    public LocalDate getArrearsAsAt() {
        return arrearsAsAt;
    }
```

**`arrearsAsAt` matters.** Arrears status is calculated by EOD, so it's correct *as at* the date of the last run, not "now". A customer who paid off their arrears at 10 a.m. is still shown as 15 DPD until that night's run. Showing the date alongside the status makes that explicit, for colleagues in collections and for anyone debugging.

Extend `LoanSummary` with the new information. Its components end like this now:

```java
        Money interestOutstanding,
        Money interestAccrued,
        Money amountDue,
        int daysPastDue,
        ArrearsBucket arrearsBucket,
        LocalDate arrearsAsAt) {

    static LoanSummary from(Loan loan, Money interestAccrued) {
        return new LoanSummary(
                loan.getId(), loan.getCustomerAccount().getId(), loan.getStatus(),
                loan.getPrincipal(), loan.getAnnualRate(), loan.getTermMonths(),
                loan.getDrawdownDate(), loan.getMonthlyPayment(),
                loan.getPrincipalAccount().getBalance(), loan.getInterestAccount().getBalance(),
                interestAccrued, loan.amountDue(), loan.getDaysPastDue(), loan.getArrearsBucket(),
                loan.getArrearsAsAt());
    }
```

And let the schedule endpoint show what's been billed and what's still due. Replace `InstalmentView` with:

```java
package com.example.loans.loan;

import com.example.loans.money.Money;

import java.time.LocalDate;

public record InstalmentView(
        int number,
        LocalDate dueDate,
        Money scheduledPayment,
        Money scheduledInterest,
        Money scheduledPrincipal,
        LocalDate billedOn,
        Money billedInterest,
        Money billedPrincipal,
        Money amountDue) {

    static InstalmentView from(Instalment instalment) {
        return new InstalmentView(
                instalment.getNumber(), instalment.getDueDate(), instalment.getScheduledPayment(),
                instalment.getScheduledInterest(), instalment.getScheduledPrincipal(),
                instalment.getBilledOn(), instalment.getBilledInterest(), instalment.getBilledPrincipal(),
                instalment.amountDue());
    }
}
```

### 4. Repayments (a first, naive version)

The value-date rule needs the last date EOD accrued for a loan. Add to `InterestAccrualRepository`:

```java
    @Query("select max(a.accrualDate) from InterestAccrual a where a.loan.id = :loanId")
    LocalDate lastAccrualDate(Long loanId);
```

Create `repayment/RepaymentRejectedException.java`:

```java
package com.example.loans.repayment;

/** The repayment is understood but breaks a rule: too much, or not enough money in the account. */
public class RepaymentRejectedException extends RuntimeException {

    public RepaymentRejectedException(String message) {
        super(message);
    }
}
```

`repayment/RepaymentResult.java`:

```java
package com.example.loans.repayment;

import com.example.loans.money.Money;

import java.time.LocalDate;

public record RepaymentResult(
        Long journalEntryId,
        Money amount,
        LocalDate bookingDate,
        LocalDate valueDate,
        Money interestPaid,
        Money principalPaid) {
}
```

Now `repayment/RepaymentService.java`. This first version reads and writes the loan **without any locking**. It looks completely reasonable, and every sequential test you'll write passes against it.

```java
package com.example.loans.repayment;

import com.example.loans.interest.InterestAccrualRepository;
import com.example.loans.ledger.JournalEntry;
import com.example.loans.ledger.LedgerAccount;
import com.example.loans.ledger.LedgerService;
import com.example.loans.loan.Instalment;
import com.example.loans.loan.Loan;
import com.example.loans.loan.LoanNotFoundException;
import com.example.loans.loan.LoanRepository;
import com.example.loans.money.Money;
import com.example.loans.time.BusinessDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class RepaymentService {

    private final LoanRepository loans;
    private final InterestAccrualRepository accruals;
    private final LedgerService ledger;
    private final BusinessDate businessDate;

    public RepaymentService(LoanRepository loans, InterestAccrualRepository accruals,
                            LedgerService ledger, BusinessDate businessDate) {
        this.loans = loans;
        this.accruals = accruals;
        this.ledger = ledger;
        this.businessDate = businessDate;
    }

    /** Takes a repayment from the customer's current account and applies it to what's due. */
    @Transactional
    public RepaymentResult repay(Long loanId, Money amount) {
        Loan loan = loans.findById(loanId).orElseThrow(() -> new LoanNotFoundException(loanId));
        LedgerAccount customer = loan.getCustomerAccount();

        Money due = loan.amountDue();
        if (due.isLessThan(amount)) {
            throw new RepaymentRejectedException(
                    "Loan %d: payment %s is more than the %s due".formatted(loanId, amount, due));
        }
        if (customer.getBalance().isLessThan(amount)) {
            throw new RepaymentRejectedException(
                    "Account %d: balance %s is less than %s".formatted(customer.getId(), customer.getBalance(), amount));
        }

        // Oldest instalment first; within each, interest before principal
        Money remaining = amount;
        Money toInterest = Money.zero(Money.GBP);
        Money toPrincipal = Money.zero(Money.GBP);
        for (Instalment instalment : loan.getInstalments()) {
            if (remaining.isZero()) {
                break;
            }
            Instalment.Allocation allocation = instalment.applyPayment(remaining);
            toInterest = toInterest.plus(allocation.interest());
            toPrincipal = toPrincipal.plus(allocation.principal());
            remaining = remaining.minus(allocation.total());
        }

        LocalDate today = businessDate.today();
        LocalDate valueDate = valueDateFor(loanId, today);
        JournalEntry.Builder entry = JournalEntry.builder("Loan %d repayment".formatted(loanId), today, valueDate)
                .debit(customer, amount);               // the bank owes the customer less
        if (toInterest.isPositive()) {
            entry.credit(loan.getInterestAccount(), toInterest);   // the customer owes less interest
        }
        if (toPrincipal.isPositive()) {
            entry.credit(loan.getPrincipalAccount(), toPrincipal); // ...and less principal
        }
        JournalEntry posted = ledger.post(entry.build());
        return new RepaymentResult(posted.getId(), amount, today, valueDate, toInterest, toPrincipal);
    }

    /**
     * Normally today. But if end of day has already accrued interest for today, today is closed,
     * so the payment takes effect for interest from tomorrow.
     */
    private LocalDate valueDateFor(Long loanId, LocalDate today) {
        LocalDate lastAccrued = accruals.lastAccrualDate(loanId);
        return lastAccrued != null && !lastAccrued.isBefore(today) ? lastAccrued.plusDays(1) : today;
    }
}
```

The banking rules, in order:

- **No overpayments.** A payment larger than what's billed and unpaid is rejected. Paying ahead of schedule is a different product feature (a partial early repayment, which changes the schedule) and gets its own endpoint in a real system.
- **No overdrafts.** The current account must have the money. (This tutorial's current accounts have no overdraft facility.)
- **The waterfall:** `getInstalments()` comes back in schedule order (`@OrderBy("number")` from guide 02), so the loop visits the oldest instalment first, and each instalment takes interest before principal.
- **One journal entry, up to three postings:** debit the current account with the whole amount, credit the interest account with the interest part, and credit the principal account with the principal part. The builder checks it balances.
- **Booking date today, value date from `valueDateFor`.** Usually today. Tomorrow if EOD has already closed today.

The controller, `repayment/RepaymentController.java`:

```java
package com.example.loans.repayment;

import com.example.loans.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
public class RepaymentController {

    private final RepaymentService repaymentService;

    public RepaymentController(RepaymentService repaymentService) {
        this.repaymentService = repaymentService;
    }

    @PostMapping("/loans/{id}/repayments")
    @ResponseStatus(HttpStatus.CREATED)
    public RepaymentResult repay(@PathVariable Long id, @Valid @RequestBody RepaymentRequest request) {
        return repaymentService.repay(id, Money.gbp(request.amount().toPlainString()));
    }

    public record RepaymentRequest(@NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount) {
    }
}
```

And in `ApiExceptionHandler`, a rejected repayment is a `422`: well-formed, but against the rules.

```java
    @ExceptionHandler(RepaymentRejectedException.class)
    ProblemDetail handle(RepaymentRejectedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setTitle("Repayment rejected");
        return problem;
    }
```

> **Sidebar: idempotent repayments.** A mobile app that times out and retries `POST /loans/1/repayments` would take the money twice. A real repayment API requires an `Idempotency-Key` header, exactly as the Core ledger tutorial does for transfers. It's left out here to keep the focus on allocation and locking.

### 5. Test fixtures, and repayment tests

From here on, most tests need the same thing: a clean database, Alice's £10,000 loan from 31 January, and EOD run over a range of dates. Put that in one place. Create `src/test/java/com/example/loans/LoanFixtures.java`:

```java
package com.example.loans;

import com.example.loans.account.AccountService;
import com.example.loans.eod.EndOfDayService;
import com.example.loans.loan.LoanService;
import com.example.loans.loan.LoanSummary;
import com.example.loans.money.Money;
import org.springframework.batch.core.BatchStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Builds the standard test loan and runs end of day over a range of dates. */
@Component
public class LoanFixtures {

    public static final LocalDate DRAWDOWN = LocalDate.of(2026, 1, 31);
    public static final LocalDate FIRST_DUE = LocalDate.of(2026, 2, 28);

    private final CleanDatabase cleanDatabase;
    private final MutableClock clock;
    private final AccountService accountService;
    private final LoanService loanService;
    private final EndOfDayService endOfDay;

    public LoanFixtures(CleanDatabase cleanDatabase, MutableClock clock, AccountService accountService,
                        LoanService loanService, EndOfDayService endOfDay) {
        this.cleanDatabase = cleanDatabase;
        this.clock = clock;
        this.accountService = accountService;
        this.loanService = loanService;
        this.endOfDay = endOfDay;
    }

    /** A clean database and Alice's £10,000 loan at 6.9% over 36 months, drawn on 31 January 2026. */
    public LoanSummary freshLoan() {
        cleanDatabase.reset();
        clock.setDate(DRAWDOWN);
        Long alice = accountService.open("Alice").getId();
        return loanService.drawDown(alice, Money.gbp("10000.00"), new BigDecimal("6.9"), 36);
    }

    /** Runs end of day for every date from {@code from} up to and including {@code to}. */
    public void endOfDay(LocalDate from, LocalDate to) throws Exception {
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            clock.setDate(day);
            BatchStatus status = endOfDay.run(day).getStatus();
            if (status != BatchStatus.COMPLETED) {
                throw new IllegalStateException("End of day for " + day + " finished " + status);
            }
        }
    }
}
```

Register it alongside `CleanDatabase` in `TestClockConfig`:

```java
@Import({CleanDatabase.class, LoanFixtures.class})
```

`endOfDay` moves the clock as it goes, so the business date and the EOD date agree, and it fails fast if any night's run doesn't complete. A test helper that silently ignores a failed setup step can waste an afternoon.

Now `src/test/java/com/example/loans/repayment/RepaymentApiTest.java`:

```java
package com.example.loans.repayment;

import com.example.loans.LoanFixtures;
import com.example.loans.LoansIntegrationTest;
import com.example.loans.MutableClock;
import com.example.loans.eod.EndOfDayService;
import com.example.loans.loan.LoanService;
import com.example.loans.loan.LoanSummary;
import com.example.loans.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static com.example.loans.LoanFixtures.DRAWDOWN;
import static com.example.loans.LoanFixtures.FIRST_DUE;
import static org.assertj.core.api.Assertions.assertThat;

@LoansIntegrationTest
class RepaymentApiTest {

    @Autowired MockMvcTester mvc;
    @Autowired LoanFixtures fixtures;
    @Autowired MutableClock clock;
    @Autowired LoanService loanService;
    @Autowired EndOfDayService endOfDay;

    LoanSummary loan;

    @BeforeEach
    void firstInstalmentBilled() throws Exception {
        loan = fixtures.freshLoan();
        fixtures.endOfDay(DRAWDOWN, FIRST_DUE.minusDays(1)); // bills instalment 1 on the 27th
        clock.setDate(FIRST_DUE);
    }

    @Test
    void paysTheInstalmentInterestFirst() {
        MvcTestResult response = repay("308.31");

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).bodyJson().extractingPath("$.valueDate").isEqualTo("2026-02-28");
        assertThat(response).bodyJson().extractingPath("$.interestPaid.amount").isEqualTo("52.93");
        assertThat(response).bodyJson().extractingPath("$.principalPaid.amount").isEqualTo("255.38");

        LoanSummary after = loanService.summary(loan.id());
        assertThat(after.principalOutstanding()).isEqualTo(Money.gbp("9744.62"));
        assertThat(after.interestOutstanding()).isEqualTo(Money.gbp("0.00"));
        assertThat(after.amountDue()).isEqualTo(Money.gbp("0.00"));
    }

    @Test
    void aPartPaymentClearsInterestBeforePrincipal() {
        MvcTestResult response = repay("60.00");

        assertThat(response).bodyJson().extractingPath("$.interestPaid.amount").isEqualTo("52.93");
        assertThat(response).bodyJson().extractingPath("$.principalPaid.amount").isEqualTo("7.07");
        assertThat(loanService.summary(loan.id()).amountDue()).isEqualTo(Money.gbp("248.31"));
    }

    @Test
    void refusesToTakeMoreThanIsDue() {
        MvcTestResult response = repay("308.32");

        assertThat(response).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Repayment rejected");
    }

    @Test
    void aPaymentAfterEndOfDayIsValueDatedTomorrow() throws Exception {
        endOfDay.run(FIRST_DUE); // today is now closed for interest

        MvcTestResult response = repay("308.31");

        assertThat(response).bodyJson().extractingPath("$.bookingDate").isEqualTo("2026-02-28");
        assertThat(response).bodyJson().extractingPath("$.valueDate").isEqualTo("2026-03-01");
    }

    private MvcTestResult repay(String amount) {
        return mvc.post().uri("/loans/{id}/repayments", loan.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount": "%s"}
                        """.formatted(amount))
                .exchange();
    }
}
```

All four pass. Paying the exact instalment on the due date leaves the principal at £9,744.62, **exactly the "balance after" on line 1 of guide 01's schedule**. The schedule, the nightly batch and the repayment all agree to the penny.

The response to the full payment:

```json
{
  "journalEntryId": 3,
  "amount": { "amount": "308.31", "currency": "GBP" },
  "bookingDate": "2026-02-28",
  "valueDate": "2026-02-28",
  "interestPaid": { "amount": "52.93", "currency": "GBP" },
  "principalPaid": { "amount": "255.38", "currency": "GBP" }
}
```

Paying again once nothing is due:

```json
{"detail":"Loan 1: payment GBP 10.00 is more than the GBP 0.00 due","instance":"/loans/1/repayments","status":422,"title":"Repayment rejected"}
```

### 6. Arrears in end of day

Create `arrears/ArrearsService.java`:

```java
package com.example.loans.arrears;

import com.example.loans.loan.ArrearsBucket;
import com.example.loans.loan.Instalment;
import com.example.loans.loan.Loan;
import com.example.loans.loan.LoanNotFoundException;
import com.example.loans.loan.LoanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
public class ArrearsService {

    private final LoanRepository loans;

    public ArrearsService(LoanRepository loans) {
        this.loans = loans;
    }

    /**
     * Days past due = days since the due date of the OLDEST instalment that isn't fully paid.
     * An instalment is not past due on its own due date, only from the day after.
     */
    @Transactional
    public void update(Long loanId, LocalDate asAt) {
        Loan loan = loans.findById(loanId).orElseThrow(() -> new LoanNotFoundException(loanId));
        long daysPastDue = loan.getInstalments().stream()
                .filter(i -> !i.getDueDate().isAfter(asAt) && i.amountDue().isPositive())
                .map(Instalment::getDueDate)
                .min(LocalDate::compareTo)
                .map(oldest -> ChronoUnit.DAYS.between(oldest, asAt))
                .orElse(0L);
        loan.recordArrears(Math.toIntExact(daysPastDue), ArrearsBucket.forDaysPastDue(daysPastDue), asAt);
    }
}
```

A stream with an `Optional` at the end reads almost like the definition: of the instalments due by today with money still owing, take the earliest due date, and count the days to today. No such instalment means 0 DPD. `Math.toIntExact` converts the `long` and throws rather than silently overflowing (not that a loan will be 2 billion days late).

Add a third step to `EndOfDayJobConfig`. First, the job:

```java
    @Bean
    public Job endOfDayJob(JobRepository jobRepository, Step accrueInterestStep, Step billInstalmentsStep,
                           Step updateArrearsStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(new DefaultJobParametersValidator(new String[] {BUSINESS_DATE}, new String[0]))
                .start(accrueInterestStep)
                .next(billInstalmentsStep)
                .next(updateArrearsStep)
                .build();
    }
```

Then the step and its writer, at the end of the class (import `com.example.loans.arrears.ArrearsService`):

```java
    // ---- Step 3: work out every active loan's days past due ----

    @Bean
    public Step updateArrearsStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                  JdbcPagingItemReader<Long> activeLoanIds, ItemWriter<Long> arrearsWriter) {
        return new StepBuilder("updateArrears", jobRepository)
                .<Long, Long>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(activeLoanIds)
                .writer(arrearsWriter)
                .build();
    }

    @Bean
    @StepScope
    public ItemWriter<Long> arrearsWriter(
            ArrearsService arrearsService, @Value("#{jobParameters['businessDate']}") LocalDate businessDate) {
        return chunk -> chunk.forEach(loanId -> arrearsService.update(loanId, businessDate));
    }
```

The step **reuses** the `activeLoanIds` reader from step 1. That's safe because it's `@StepScope`: what gets injected is a proxy, and each step execution gets its own fresh reader instance with its own position.

Arrears runs **last**, after billing, so it sees today's state. Order matters in an EOD job, and real ones encode a lot of business knowledge in their step order.

Now test it. First, the bucket boundaries, `src/test/java/com/example/loans/arrears/ArrearsBucketTest.java`:

```java
package com.example.loans.arrears;

import com.example.loans.loan.ArrearsBucket;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ArrearsBucketTest {

    @ParameterizedTest
    @CsvSource({
            "0,  UP_TO_DATE",
            "1,  DPD_1_29",
            "29, DPD_1_29",
            "30, DPD_30_59",
            "59, DPD_30_59",
            "60, DPD_60_89",
            "89, DPD_60_89",
            "90, DPD_90_PLUS",
            "400, DPD_90_PLUS"
    })
    void bucketsByDaysPastDue(long days, ArrearsBucket expected) {
        assertThat(ArrearsBucket.forDaysPastDue(days)).isEqualTo(expected);
    }
}
```

Every boundary, on both sides. Off-by-one errors in arrears bucketing put customers in the wrong collections queue, so boundary tests are cheap insurance. JUnit converts `"DPD_1_29"` into the enum constant for you.

Then the whole story, `src/test/java/com/example/loans/arrears/ArrearsTest.java`:

```java
package com.example.loans.arrears;

import com.example.loans.LoanFixtures;
import com.example.loans.LoansIntegrationTest;
import com.example.loans.loan.ArrearsBucket;
import com.example.loans.loan.LoanService;
import com.example.loans.loan.LoanSummary;
import com.example.loans.money.Money;
import com.example.loans.repayment.RepaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static com.example.loans.LoanFixtures.DRAWDOWN;
import static com.example.loans.LoanFixtures.FIRST_DUE;
import static org.assertj.core.api.Assertions.assertThat;

@LoansIntegrationTest
class ArrearsTest {

    @Autowired LoanFixtures fixtures;
    @Autowired LoanService loanService;
    @Autowired RepaymentService repaymentService;

    @Test
    void aMissedPaymentAgesThroughTheBucketsUntilItIsPaid() throws Exception {
        LoanSummary loan = fixtures.freshLoan();

        fixtures.endOfDay(DRAWDOWN, FIRST_DUE);
        assertThat(loanService.summary(loan.id()).daysPastDue()).isZero(); // due today: not past due yet

        fixtures.endOfDay(FIRST_DUE.plusDays(1), FIRST_DUE.plusDays(1));
        LoanSummary dayOne = loanService.summary(loan.id());
        assertThat(dayOne.daysPastDue()).isEqualTo(1);
        assertThat(dayOne.arrearsBucket()).isEqualTo(ArrearsBucket.DPD_1_29);

        LocalDate day30 = FIRST_DUE.plusDays(30); // 30 March: the second instalment is also billed and due
        fixtures.endOfDay(FIRST_DUE.plusDays(2), day30);
        LoanSummary month = loanService.summary(loan.id());
        assertThat(month.daysPastDue()).isEqualTo(30);
        assertThat(month.arrearsBucket()).isEqualTo(ArrearsBucket.DPD_30_59);
        assertThat(month.amountDue()).isEqualTo(Money.gbp("616.62"));

        repaymentService.repay(loan.id(), month.amountDue());
        fixtures.endOfDay(day30.plusDays(1), day30.plusDays(1));
        LoanSummary cleared = loanService.summary(loan.id());
        assertThat(cleared.daysPastDue()).isZero();
        assertThat(cleared.arrearsBucket()).isEqualTo(ArrearsBucket.UP_TO_DATE);
    }
}
```

Alice misses her 28 February payment. On the 28th she's due but not past due. On 1 March she's 1 DPD. By 30 March she's 30 DPD and in the 30–59 bucket, and because the second instalment (due 31 March) was billed on the 30th, she owes two instalments: £616.62. She pays the lot, and the next night's run puts her back to up to date.

Notice the second instalment is still £308.31 in total, although Alice paid late. Her principal stayed higher for longer, so the interest billed for March was more than the schedule projected, and the principal part correspondingly less. The level payment absorbs the difference, and the final instalment will settle the rest. That's the guide 03 billing logic doing its job.

Add the new requests to `requests.http`:

```http
### Repay £50
POST http://localhost:8080/loans/1/repayments
Content-Type: application/json

{"amount": "50.00"}
```

Run all the tests. Everything passes. Every test so far runs one thing at a time.

### 7. The race: watch it fail

On the due date, Alice's £308.31 is due. Ten payments of £30.00 arrive at once (an app retrying, a standing order, Alice tapping "pay" repeatedly), and at the same moment the nightly batch runs for that date. The correct outcome is easy to state:

- All ten payments succeed: £300.00 is less than the £308.31 due, so there's room for all of them.
- Instalment 1 shows exactly **£300.00 paid**: £52.93 interest and £247.07 principal.
- Every ledger account's cached balance equals its derived balance.
- The accrual for the due date was calculated on the principal **as it stood at the end of that day, by value date**, however the payments and the batch interleaved.

Create `src/test/java/com/example/loans/repayment/ConcurrentRepaymentTest.java`:

```java
package com.example.loans.repayment;

import com.example.loans.LoanFixtures;
import com.example.loans.LoansIntegrationTest;
import com.example.loans.MutableClock;
import com.example.loans.eod.EndOfDayService;
import com.example.loans.interest.InterestAccrual;
import com.example.loans.interest.InterestAccrualRepository;
import com.example.loans.ledger.LedgerAccount;
import com.example.loans.ledger.LedgerAccountRepository;
import com.example.loans.ledger.LedgerService;
import com.example.loans.loan.Instalment;
import com.example.loans.loan.InstalmentRepository;
import com.example.loans.loan.LoanSummary;
import com.example.loans.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.example.loans.LoanFixtures.DRAWDOWN;
import static com.example.loans.LoanFixtures.FIRST_DUE;
import static org.assertj.core.api.Assertions.assertThat;

/** On the due date: ten £30 repayments on one loan, racing each other and that night's end of day. */
@LoansIntegrationTest
class ConcurrentRepaymentTest {

    @Autowired LoanFixtures fixtures;
    @Autowired MutableClock clock;
    @Autowired RepaymentService repaymentService;
    @Autowired EndOfDayService endOfDay;
    @Autowired InstalmentRepository instalments;
    @Autowired InterestAccrualRepository accruals;
    @Autowired LedgerAccountRepository accounts;
    @Autowired LedgerService ledger;

    @Test
    void repaymentsRacingEndOfDayKeepEveryBalanceConsistent() throws Exception {
        LoanSummary loan = fixtures.freshLoan();
        fixtures.endOfDay(DRAWDOWN, FIRST_DUE.minusDays(1)); // instalment 1 billed: £308.31 due
        clock.setDate(FIRST_DUE);

        CountDownLatch startingGun = new CountDownLatch(1);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> {
                startingGun.await();
                return repaymentService.repay(loan.id(), Money.gbp("30.00"));
            });
        }
        tasks.add(() -> {
            startingGun.await();
            return endOfDay.run(FIRST_DUE).getStatus();
        });

        List<Throwable> failures = new ArrayList<>();
        List<Object> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<Object>> futures = tasks.stream().map(pool::submit).toList();
            startingGun.countDown();
            for (Future<Object> future : futures) {
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    failures.add(e.getCause());
                }
            }
        }

        // The database was cleaned, so this loan is the only one
        Instalment first = instalments.findAll().stream()
                .filter(i -> i.getNumber() == 1).findFirst().orElseThrow();
        LedgerAccount principal = accounts.findAll().stream()
                .filter(a -> a.getName().equals("Loan principal: Alice")).findFirst().orElseThrow();
        InterestAccrual dueDateAccrual = accruals.findAll().stream()
                .filter(a -> a.getAccrualDate().equals(FIRST_DUE)).findFirst().orElseThrow();

        System.out.printf("failures=%s%n", failures.stream().map(f -> f.getClass().getSimpleName()).toList());
        System.out.printf("instalment paid: interest=%s principal=%s%n", first.getInterestPaid(), first.getPrincipalPaid());
        accounts.findAll().forEach(a ->
                System.out.printf("%-30s cached=%s derived=%s%n", a.getName(), a.getBalance(), ledger.derivedBalance(a)));
        System.out.printf("accrual on due date used %s; value-dated principal at end of day=%s%n",
                dueDateAccrual.getBalance(), ledger.balanceAsAt(principal, FIRST_DUE));

        assertThat(failures).isEmpty();
        assertThat(results).contains(BatchStatus.COMPLETED);
        assertThat(first.getInterestPaid().plus(first.getPrincipalPaid())).isEqualTo(Money.gbp("300.00"));
        assertThat(accounts.findAll()).allSatisfy(account ->
                assertThat(account.getBalance()).as(account.getName()).isEqualTo(ledger.derivedBalance(account)));
        assertThat(dueDateAccrual.getBalance()).isEqualTo(ledger.balanceAsAt(principal, FIRST_DUE));
    }
}
```

The structure is the classic concurrency test:

- **Eleven tasks**, ten repayments and one EOD run, all `Callable`s so each can return its result.
- **The `CountDownLatch` starting gun.** Every task blocks on `await()` until all are submitted, then `countDown()` releases them together. Without it, the first repayment would probably finish before the last thread started, and the test would pass by luck.
- **No `@Transactional` on the test.** Each repayment and each EOD chunk commits for real, on its own thread, as in production. The setup (`freshLoan`, the 28 nights of EOD) commits too, so the worker threads can see it.
- **`try` with resources on the `ExecutorService`** (Java 19+) waits for every task. `future.get()` rethrows anything a task threw, wrapped in an `ExecutionException`, so `e.getCause()` is the real exception.
- **The assertions check invariants**, not just "no exceptions": the instalment's paid total, cached against derived for every account, and the accrual against the value-dated principal.

Run it. It fails, every time:

```
failures=[]
instalment paid: interest=GBP 30.00 principal=GBP 0.00
Interest income: personal loans cached=GBP 52.93 derived=GBP 52.93
Alice                          cached=GBP 9940.00 derived=GBP 9700.00
Loan principal: Alice          cached=GBP 10000.00 derived=GBP 10000.00
Loan interest: Alice           cached=GBP -37.07 derived=GBP -247.07
accrual on due date used GBP 10000.00; value-dated principal at end of day=GBP 10000.00
```

```
expected: GBP 300.00
 but was: GBP 30.00
```

Read it slowly, because every line is a different kind of wrong:

- **All ten repayments "succeeded"**, and the postings say £300 left Alice's current account (derived £9,700.00). But the cached balance says £9,940.00: eight of the ten updates to her account were **lost**.
- **The instalment records £30.00 paid**, not £300.00. Nine of the ten updates to the instalment were lost.
- **Every repayment put its whole £30 towards interest.** Each one read "£52.93 interest due" before any other had committed, so each decided the full £30 was interest. Ten times £30 of interest was credited against £52.93 of interest owed: the loan's interest account is **−£247.07** by its postings. Alice has **overpaid interest by £247.07**, and not a penny of her principal came down. In a real bank, that's a customer complaint, a remediation exercise and possibly a report to the regulator.
- **The ledger no longer agrees with itself:** cached and derived balances differ on two accounts.

Nothing threw an exception. Every transaction committed. That's what makes lost updates so dangerous.

### 8. Fix it: lock the loan, always first

Add a locking finder to `loan/LoanRepository.java`:

```java
package com.example.loans.loan;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface LoanRepository extends JpaRepository<Loan, Long> {

    /** SELECT ... FOR UPDATE: anyone else wanting this loan waits until we commit. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Loan l where l.id = :id")
    Optional<Loan> findByIdForUpdate(Long id);
}
```

And one for ledger accounts in `ledger/LedgerAccountRepository.java` (with the same three imports):

```java
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LedgerAccount a where a.id = :id")
    Optional<LedgerAccount> findByIdForUpdate(Long id);
```

`@Lock(PESSIMISTIC_WRITE)` makes Hibernate add `FOR UPDATE` to the query. The row lock lasts until the **transaction** commits or rolls back.

Now the rule. **Every transaction that changes a loan locks the loan row first, then (if it needs to) the customer's current account.** Apply it everywhere:

**`RepaymentService.repay`** locks both. Add a `LedgerAccountRepository accounts` constructor parameter and field, then replace the first two lines of `repay`:

```java
        // Lock order, everywhere: the loan first, then the customer's current account
        Loan loan = loans.findByIdForUpdate(loanId).orElseThrow(() -> new LoanNotFoundException(loanId));
        LedgerAccount customer = accounts.findByIdForUpdate(loan.getCustomerAccount().getId()).orElseThrow();
```

`loan.getCustomerAccount().getId()` doesn't load the account: Hibernate knows a lazy proxy's id without a query. So the account's **first** load is the locking one. (Reading a row without a lock and *then* locking it in the same transaction leaves you holding a stale copy. The Core ledger tutorial shows the error that produces.)

**`AccrualService.calculate`** locks the loan, so a repayment can't change the principal between the accrual reading it and the chunk committing:

```java
    @Transactional
    public InterestAccrual calculate(Long loanId, LocalDate date) {
        // Locked until the chunk commits, so a repayment can't slip in between reading
        // the principal and saving the accrual
        Loan loan = loans.findByIdForUpdate(loanId).orElseThrow(() -> new LoanNotFoundException(loanId));
```

(It was `@Transactional(readOnly = true)`. It now takes a write lock, so drop the `readOnly`.)

**`BillingService.billDue`** and **`ArrearsService.update`** both change the loan or its instalments, so both use `findByIdForUpdate` instead of `findById`:

```java
        Loan loan = loans.findByIdForUpdate(loanId).orElseThrow(() -> new LoanNotFoundException(loanId));
```

**`LoanService.drawDown`** credits the customer's current account, which another loan's repayment might be debiting at the same moment. Add to `AccountService`:

```java
    /** Loads and locks a current account until the surrounding transaction ends. */
    @Transactional
    public LedgerAccount getForUpdate(Long id) {
        return accounts.findByIdForUpdate(id)
                .filter(account -> account.getType() == AccountType.LIABILITY)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }
```

And in `drawDown`, use it:

```java
        LedgerAccount customer = accountService.getForUpdate(customerAccountId);
```

(A new loan has no loan row to lock yet, so "loan first" is trivially satisfied.)

Run `ConcurrentRepaymentTest` again. Green. Here are three consecutive runs:

```
failures=[]
instalment paid: interest=GBP 52.93 principal=GBP 247.07
Interest income: personal loans cached=GBP 52.93 derived=GBP 52.93
Alice                          cached=GBP 9700.00 derived=GBP 9700.00
Loan principal: Alice          cached=GBP 9752.93 derived=GBP 9752.93
Loan interest: Alice           cached=GBP 0.00 derived=GBP 0.00
accrual on due date used GBP 9782.93; value-dated principal at end of day=GBP 9782.93
```

```
accrual on due date used GBP 9782.93; value-dated principal at end of day=GBP 9782.93
```

```
accrual on due date used GBP 9752.93; value-dated principal at end of day=GBP 9752.93
```

(The other lines were identical in all three runs.)

- **Interest is paid exactly once**, £52.93, and the remaining £247.07 reduces the principal. The instalment, the current account and both loan accounts all agree with their postings.
- **The accrual line differs between runs, and that's correct.** In the first two runs, EOD got the loan's lock after nine repayments, so it accrued on £9,782.93 (£10,000 − £247.07 + £30, the last £30 not yet paid). The tenth repayment then found the 28th already accrued and was value-dated 1 March. In the third run, EOD went last. Either way, the accrual matches the principal **as at the end of the 28th by value date**, which is the invariant that matters. You can't control the order in which concurrent requests arrive. You *can* make every order produce a correct, explainable result.

Run the whole suite two or three times. Concurrency tests must pass **every** time.

> **Sidebar: why not `@Version` (optimistic locking)?** A version column on `Loan` would turn the lost updates into `ObjectOptimisticLockingFailureException`s: the data would stay correct, but nine of the ten repayments would fail and need retrying. That's a fine trade when collisions are rare. Here, one loan being hit by several requests and a batch at the same moment is exactly the case to design for, so making the others **wait** is the better behaviour. The Core ledger tutorial compares the two approaches in detail, and keeping `@Version` as a safety net alongside pessimistic locks is common practice.

> **Sidebar: the cost of locking in a batch.** `AccrualService` now holds a lock on every loan in a chunk until the chunk commits: up to 100 loans for the duration of one chunk. A customer paying one of those loans at that moment waits for the chunk to finish, typically a fraction of a second. Smaller chunks mean shorter waits but more commits. That trade-off (throughput of the batch against latency of online requests on the same rows) is one reason banks schedule EOD for the quietest hours, and why some core banking platforms restrict online changes while a customer's accounts are being processed.

> **Sidebar: lock ordering.** Every path locks the loan **before** the current account, and never the other way round. If one transaction locked account → loan while another locked loan → account, each could end up holding what the other needs: a **deadlock**, which the database breaks by rolling one back. One global order, followed everywhere, makes that cycle impossible. The interest income account isn't locked at all, because only the single-threaded billing step writes to it. If billing were made multi-threaded, that income account would become a [hot account](../glossary.md#hot-account), and you'd need a strategy for it.

---

## Your turn

### Exercise 1: a settlement quote

Add `GET /loans/{id}/settlement-quote`, returning the amount that would clear the loan today: principal outstanding, plus billed interest still unpaid, plus interest accrued since the last bill (guide 03's `interestAccrued`). Return the three parts as well as the total, and the business date it's valid for.

Test it: after EOD for 31 January to 2 February, a settlement quote for Alice's loan is £10,000.00 principal + £5.67 accrued = **£10,005.67**.

<details>
<summary>Solution</summary>

`loan/SettlementQuote.java`:

```java
package com.example.loans.loan;

import com.example.loans.money.Money;

import java.time.LocalDate;

/** What it would take to clear the loan today. */
public record SettlementQuote(
        Long loanId,
        LocalDate asAt,
        Money principalOutstanding,
        Money interestBilled,
        Money interestAccrued,
        Money settlementAmount) {
}
```

`LoanService`:

```java
    /** Principal, plus interest billed but unpaid, plus interest accrued since the last bill. */
    @Transactional(readOnly = true)
    public SettlementQuote settlementQuote(Long loanId) {
        LoanSummary loan = summary(loanId);
        Money total = loan.principalOutstanding().plus(loan.interestOutstanding()).plus(loan.interestAccrued());
        return new SettlementQuote(loanId, businessDate.today(), loan.principalOutstanding(),
                loan.interestOutstanding(), loan.interestAccrued(), total);
    }
```

`LoanController`:

```java
    @GetMapping("/{id}/settlement-quote")
    public SettlementQuote settlementQuote(@PathVariable Long id) {
        return loanService.settlementQuote(id);
    }
```

`src/test/java/com/example/loans/loan/SettlementQuoteApiTest.java`:

```java
package com.example.loans.loan;

import com.example.loans.LoanFixtures;
import com.example.loans.LoansIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.time.LocalDate;

import static com.example.loans.LoanFixtures.DRAWDOWN;
import static org.assertj.core.api.Assertions.assertThat;

@LoansIntegrationTest
class SettlementQuoteApiTest {

    @Autowired MockMvcTester mvc;
    @Autowired LoanFixtures fixtures;

    @Test
    void quotesPrincipalPlusInterestToDate() throws Exception {
        LoanSummary loan = fixtures.freshLoan();
        fixtures.endOfDay(DRAWDOWN, LocalDate.of(2026, 2, 2)); // 3 days of interest

        var response = mvc.get().uri("/loans/{id}/settlement-quote", loan.id()).exchange();

        assertThat(response).hasStatus(HttpStatus.OK);
        assertThat(response).bodyJson().extractingPath("$.principalOutstanding.amount").isEqualTo("10000.00");
        assertThat(response).bodyJson().extractingPath("$.interestAccrued.amount").isEqualTo("5.67");
        assertThat(response).bodyJson().extractingPath("$.settlementAmount.amount").isEqualTo("10005.67");
    }
}
```

Later in the loan's life the quote has all three parts. For example, after Alice pays her first instalment on time and EOD runs up to 10 March:

```json
{
  "loanId": 1,
  "asAt": "2026-03-10",
  "principalOutstanding": { "amount": "9744.62", "currency": "GBP" },
  "interestBilled": { "amount": "0.00", "currency": "GBP" },
  "interestAccrued": { "amount": "20.26", "currency": "GBP" },
  "settlementAmount": { "amount": "9764.88", "currency": "GBP" }
}
```

Remember the simplifications from the Concepts section: no notice period, no statutory rebate calculation (there's no pre-charged future interest to rebate here), and no s.95A compensation.

</details>

### Exercise 2: notice of sums in arrears

Add `boolean arrearsNoticeRequired(LocalDate asAt)` to `Loan`, implementing the s.86B trigger for monthly payments: at least **two** payments have fallen due (billed, with a due date on or before `asAt`), and the total still unpaid is at least the total of the **last two** payments that fell due.

Test it in `ArrearsTest`: after Alice misses her first payment, on 1 March, no notice is needed. After she misses the second too, on 31 March, one is.

<details>
<summary>Hint</summary>

The test needs the loan's instalments, which are lazy. Load them inside a `TransactionTemplate` (inject it; Spring Boot provides one), touch them, and return the loan.

</details>

<details>
<summary>Solution</summary>

`Loan` (import `java.util.List` is already there):

```java
    /**
     * Consumer Credit Act 1974 s.86B (fixed-sum credit, monthly payments): a notice of sums in
     * arrears is needed once at least two payments have fallen due and the shortfall is at least
     * the total of the last two payments due.
     */
    public boolean arrearsNoticeRequired(LocalDate asAt) {
        List<Instalment> fallenDue = instalments.stream()
                .filter(i -> i.isBilled() && !i.getDueDate().isAfter(asAt))
                .toList();
        if (fallenDue.size() < 2) {
            return false;
        }
        Money shortfall = fallenDue.stream().map(Instalment::amountDue).reduce(Money.zero(Money.GBP), Money::plus);
        Money lastTwo = fallenDue.get(fallenDue.size() - 1).getBilledInterest()
                .plus(fallenDue.get(fallenDue.size() - 1).getBilledPrincipal())
                .plus(fallenDue.get(fallenDue.size() - 2).getBilledInterest())
                .plus(fallenDue.get(fallenDue.size() - 2).getBilledPrincipal());
        return !shortfall.isLessThan(lastTwo);
    }
```

`ArrearsTest` gains two fields (`@Autowired LoanRepository loans;` and `@Autowired TransactionTemplate transactions;`, importing `com.example.loans.loan.Loan`, `com.example.loans.loan.LoanRepository` and `org.springframework.transaction.support.TransactionTemplate`), a test and a helper:

```java
    @Test
    void twoMissedPaymentsTriggerANoticeOfSumsInArrears() throws Exception {
        LoanSummary summary = fixtures.freshLoan();

        fixtures.endOfDay(DRAWDOWN, FIRST_DUE.plusDays(1));
        assertThat(loanWithInstalments(summary.id()).arrearsNoticeRequired(FIRST_DUE.plusDays(1))).isFalse();

        LocalDate secondDue = LocalDate.of(2026, 3, 31);
        fixtures.endOfDay(FIRST_DUE.plusDays(2), secondDue);
        assertThat(loanWithInstalments(summary.id()).arrearsNoticeRequired(secondDue)).isTrue();
    }

    private Loan loanWithInstalments(Long id) {
        return transactions.execute(status -> {
            Loan loan = loans.findById(id).orElseThrow();
            loan.getInstalments().forEach(i -> i.getDueDate()); // load them while the session is open
            return loan;
        });
    }
```

This is a trigger, not a whole feature. The real obligations around it (sending the notice within 14 days in the prescribed form with the FCA's arrears information sheet, repeating it at least every six months, and different counts for weekly payments) belong in a compliance-reviewed collections system. Read [s.86B](https://www.legislation.gov.uk/ukpga/1974/39/section/86B) itself: statutory wording is precise, and "at least two" and "the last two" both matter.

</details>

### Exercise 3 (stretch, no solution given): settle the loan

Add `POST /loans/{id}/settlement`, which takes the settlement amount from Alice's current account and closes the loan. You'll need to:

- Lock the loan, then the customer account, as everywhere else.
- Post the accrued-but-unbilled interest (debit loan interest, credit interest income), then the payment (debit the current account, credit the interest and principal accounts).
- Set the loan's status to `SETTLED`. Guide 03's reader and services already skip loans that aren't `ACTIVE`, so nothing more will accrue or be billed.
- Test that afterwards both loan accounts are at £0.00, cached equals derived everywhere, and EOD the next day writes no accrual for the loan.

Think about the edge cases: what should happen if EOD has already accrued *today* when the customer settles? Who keeps the interest for today?

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 60, Failures: 0, Errors: 0, Skipped: 0
```

Run it two or three times. The concurrency test must pass every time.

New files since guide 03:

```
src/main/java/com/example/loans/
├── arrears/
│   └── ArrearsService.java
├── loan/
│   ├── ArrearsBucket.java
│   └── SettlementQuote.java
└── repayment/
    ├── RepaymentController.java
    ├── RepaymentRejectedException.java
    ├── RepaymentResult.java
    └── RepaymentService.java
src/main/resources/db/migration/
└── V4__repayments_and_arrears.sql
src/test/java/com/example/loans/
├── LoanFixtures.java
├── arrears/
│   ├── ArrearsBucketTest.java
│   └── ArrearsTest.java
├── loan/
│   └── SettlementQuoteApiTest.java
└── repayment/
    ├── ConcurrentRepaymentTest.java
    └── RepaymentApiTest.java
```

Modified: `Instalment.java`, `InstalmentView.java`, `Loan.java`, `LoanSummary.java`, `LoanRepository.java`, `LoanService.java`, `LoanController.java`, `LedgerAccountRepository.java`, `AccountService.java`, `AccrualService.java`, `BillingService.java`, `InterestAccrualRepository.java`, `EndOfDayJobConfig.java`, `ApiExceptionHandler.java`, `TestClockConfig.java`, `requests.http`.

**Try the story by hand.** Delete `data/`, start the app, and from `requests.http`: open Alice and draw down the £5,000 loan (it's drawn down on today's real date). Then send `POST /eod?businessDate=…` for each day from today up to the day before the first due date. `GET /loans/1/instalments` now shows instalment 1 billed, and you can repay it. Look at the repayment's `valueDate`: it's the day after the last EOD date you ran, because the value-date rule treats every day EOD has accrued as closed. That's guide 02 and this guide working together, and a good illustration of why tests drive the clock rather than waiting for real dates.

---

## Common errors

**The concurrency test fails with `expected: GBP 300.00 but was: GBP 30.00` (or similar), and `cached` ≠ `derived`**
Lost updates. Some code path changes the loan, its instalments or its accounts without `findByIdForUpdate`. Every path that writes must lock the loan first.

**The concurrency test passes even without the locks**
The threads aren't overlapping. Check every task calls `startingGun.await()` before doing anything, and that `countDown()` runs only after all tasks are submitted.

**The concurrency test hangs**
A task is stuck in `await()` because `countDown()` was never reached, often because an exception happened while submitting tasks. Use `future.get(60, TimeUnit.SECONDS)` so a hang becomes a failure.

**Worker threads can't see the loan the test created**
The test (or a helper it calls) is `@Transactional`, so the setup hasn't committed. Concurrency tests must not be.

**`LazyInitializationException: Could not initialize proxy [com.example.loans.loan.Loan#1] - no session`**
A test navigated a lazy relationship (for example `instalment.getLoan().getPrincipalAccount()`) after the repository call's transaction ended. Look the data up directly (as `ConcurrentRepaymentTest` does by account name), or use a `TransactionTemplate`.

**`422` with `"detail": "Loan 1: payment GBP 10.00 is more than the GBP 0.00 due"`**
Working as intended: nothing is billed and unpaid. An instalment can only be paid once EOD has billed it, which happens on the night before its due date.

**The arrears test says 0 DPD the day after the due date**
EOD hasn't run for that day. `daysPastDue` is only as fresh as the last EOD run, which `arrearsAsAt` tells you.

**Locking seems to have no effect**
`findByIdForUpdate` was called outside a transaction, so the lock was released immediately. The calling method must be `@Transactional` and called through the Spring proxy (from another bean, not as `this.repay(...)`).

---

## Recap

- **Allocation:** oldest instalment first, interest before principal. It's set by the agreement and the bank's policy, and it decides how much interest the customer pays.
- A payment that arrives after EOD has **closed** the day is **value-dated tomorrow**, which avoids back-valuation and interest adjustments.
- **Days past due** counts from the oldest unpaid instalment's due date. **Arrears buckets** (1–29, 30–59, 60–89, 90+) drive collections, reporting and provisioning. **More than 90 DPD** is a key default trigger under the prudential rules.
- UK law gives borrowers a right to **settle early** (CCA s.94), and requires **notices of sums in arrears** (s.86B) once arrears reach a statutory threshold.
- **Lost updates** in a loan system don't just corrupt balances: they can misallocate payments and overcharge interest, silently.
- **Lock the aggregate root first** (`SELECT … FOR UPDATE` on the loan) in **every** path that changes it, online or batch, and then any shared accounts in a fixed order.
- **Concurrent outcomes can differ and still all be correct.** Test the invariants (paid totals, cached = derived, accrual = value-dated balance), not a particular interleaving.

### Interview angle

<details>
<summary>"A customer repays at the same moment the overnight batch is accruing interest on their loan. What can go wrong, and how do you prevent it?"</summary>

Without coordination, both transactions read the loan, compute from stale state and write: the batch can accrue on a principal the repayment has just reduced (or not), and the repayment can overwrite instalment or balance changes made by the batch, or by another repayment. Prevent it by serialising changes per loan: every path, online and batch, takes a row lock on the loan (`SELECT … FOR UPDATE`) before reading what it will change, and takes any further locks in a fixed global order. Add a rule for the boundary: once the batch has accrued a day, a later payment is value-dated the next day. Test it with a concurrency test that releases many repayments and the batch together and asserts the invariants.

</details>

<details>
<summary>"How do you decide which part of a repayment pays interest and which pays principal?"</summary>

By the allocation rules in the agreement and the bank's policy, usually: oldest amounts due first, and within each instalment, interest (and fees) before principal. Implement it as an explicit, tested waterfall, recording interest paid and principal paid separately per instalment, and post the split to separate interest and principal ledger accounts in one balanced journal entry. Getting allocation wrong changes how much interest the customer pays, so it's a conduct risk, not just a bug.

</details>

<details>
<summary>"What's days past due, and why does 90 matter?"</summary>

DPD is the number of days since the due date of the oldest unpaid amount. It's the basis for arrears buckets (typically in 30-day bands), which drive collections, credit reporting and accounting provisions. Under the prudential definition of default banks use for capital purposes, an obligor more than 90 days past due on a material obligation is in default, so 90+ DPD exposures are treated very differently in risk and capital calculations. It should be computed consistently, in the overnight batch, with an "as at" date.

</details>

<details>
<summary>"Optimistic or pessimistic locking for a loan account?"</summary>

For an account that online requests and the overnight batch may hit at the same moment, pessimistic locking (`PESSIMISTIC_WRITE`) is usually the better fit: competing transactions wait briefly instead of failing and retrying. Optimistic locking with a version column is cheaper when conflicts are rare, and makes a good safety net alongside explicit locks. Either way, lock the aggregate root, keep transactions short, take locks in a consistent order to avoid deadlocks, and be aware that batch chunks holding locks add latency for online users.

</details>

### Further reading

- [Consumer Credit Act 1974, s.86B: notice of sums in arrears](https://www.legislation.gov.uk/ukpga/1974/39/section/86B)
- [Consumer Credit Act 1974, s.94: right to complete payments ahead of time](https://www.legislation.gov.uk/ukpga/1974/39/section/94), and [s.95A: compensation for early repayment](https://www.legislation.gov.uk/ukpga/1974/39/section/95A)
- [FCA Handbook, CONC 7.3: treatment of customers in default or arrears](https://handbook.fca.org.uk/handbook/CONC/7/3.html)
- [Capital Requirements Regulation, Article 178: default of an obligor](https://www.legislation.gov.uk/eur/2013/575/article/178)
- [Spring Data JPA reference: Locking](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)
- [PostgreSQL docs: Explicit Locking](https://www.postgresql.org/docs/current/explicit-locking.html)

---

## You've built a loans system

Across four guides you've built the servicing side of a personal loan book:

| Guide | Banking idea | Java/Spring skill |
|---|---|---|
| 01 | Principal, rate, term, APR; amortisation; day counts | `BigDecimal` with `MathContext` and `RoundingMode`, the annuity formula, `java.time` month arithmetic, property tests |
| 02 | Drawdown creates a deposit; booking date vs value date; business date | A small double-entry ledger, value-dated queries, an injected `Clock` |
| 03 | End of day: daily accrual, monthly charging | Spring Batch jobs, chunks, job parameters as identity, idempotency, restarts, keyset paging |
| 04 | Repayment allocation, arrears and DPD, early settlement | Pessimistic locking of an aggregate, lock ordering, a concurrency test across online and batch paths |

**Where to go next:**

- **The Core ledger tutorial** goes much deeper into the ledger this one only sketches: optimistic vs pessimistic locking compared, deadlocks, reversals, statements and idempotency keys.
- **Extend this system yourself:** an `Idempotency-Key` on repayments; full early settlement (Exercise 3); business-day due dates with a holiday calendar; a fee (and watch what it does to the APR); partitioning the EOD job so it processes loan ranges in parallel; running the suite against PostgreSQL to prove the migrations really are portable.
