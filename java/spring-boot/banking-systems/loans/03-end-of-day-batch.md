# 03: End of Day with Spring Batch

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- What a bank's **end-of-day (EOD)** run does, and why interest is **accrued** daily but **charged** monthly
- **Spring Batch** from the ground up: jobs, steps, **chunk-oriented processing**, the job repository, and `@StepScope`
- What Spring Boot 4.1 and Spring Batch 6 need: the `batch-jdbc` starter, schema initialisation, and stopping jobs from running at start-up
- **Job parameters as identity:** keying the job by business date, so the same day can't be processed twice by accident
- **Idempotency in depth:** why "Spring Batch won't run it twice" isn't enough, and the domain check plus unique constraint that make a re-run harmless
- **Restartability:** a job that fails halfway, gets fixed, and carries on from where it stopped without redoing finished work
- **Keyset paging** in batch readers, and how offset paging silently skips records

By the end, a nightly job accrues interest on every active loan to six decimal places, bills each instalment the day before it's due with exactly the interest the schedule promised, and can be re-run or restarted safely.

---

## Concepts

### How it works in a real bank

During the day, a core banking system handles transactions one at a time: a repayment here, a drawdown there. Some work, though, has to happen to **every** account, once per day, after the day's transactions are in. That's the [end-of-day](../glossary.md#end-of-day) run (also called close of business, COB, or the overnight batch). Typical EOD work includes:

- **Interest accrual** on every interest-bearing account: loans, savings, overdrafts
- **Charging** interest and fees that fall due
- **Arrears** processing: ageing missed payments, triggering collections letters (guide 04)
- Producing **statements**, regulatory and risk reports, and feeds to the general ledger
- **Rolling the business date** forward, so the next day's transactions are booked on the new date

At a large bank, EOD runs against millions of accounts in a fixed **batch window** overnight, and must finish before the next business day opens. When it fails at 3 a.m., an operations team fixes the cause and **restarts** it. Re-running the whole thing from scratch might take too long, and re-running work that already finished (accruing interest twice, say) would be a serious error. Restartability and idempotency aren't nice-to-haves in batch banking. They're the job.

### Accrual vs charging

Interest on a loan is **earned** every day, but **charged** to the customer once a month, when an instalment is billed. Those are two different events:

| | Accrual | Charging (billing) |
|---|---|---|
| When | Every night, for every active loan | When an instalment falls due |
| Amount | One day's interest, to 6 decimal places | The sum of the period's accruals, rounded to the penny |
| Why | The bank's income must be recognised as it's earned, day by day | The customer owes whole pennies, on a schedule |
| In this tutorial | A row in an `interest_accrual` table | A journal entry: debit loan interest, credit interest income |

Accounting rules require a bank to recognise interest income as it's earned, not when it's received, which is why accrual is a daily, every-account job. Many banks also post each day's accrual to the general ledger (to an **accrued interest receivable** account), so the books show income building up day by day.

**This tutorial's simplification:** daily accruals are kept in the loans sub-ledger only, and the ledger receives one posting per instalment, when it's billed. The amounts are the same. Only the timing of the ledger postings differs.

The accrual for a day uses the loan's principal **as at that value date**, using guide 02's `balanceAsAt`. So a repayment value-dated on the 15th reduces the interest from the 15th onwards, whichever day it was booked.

### When an instalment is billed

An instalment falls due on its due date, so the amount due has to be known **before** that day starts, or a customer paying first thing in the morning would find nothing to pay. So **EOD for date D bills every instalment due on D + 1**. By then, the accruals for every day of the period (from the previous due date up to, but not including, the due date) exist, and their sum is exactly that period's interest.

Billing posts the interest with **booking date D** (the business day the posting was made) and **value date D + 1** (the due date: the day it becomes owed). That's a real-world example of a forward-valued posting.

### Spring Batch in five ideas

[Spring Batch](https://docs.spring.io/spring-batch/reference/) is the standard Java framework for this kind of work. You need five ideas:

1. A **`Job`** is a sequence of **`Step`s**. Here: *accrue interest*, then *bill instalments* (and, in guide 04, *update arrears*).
2. A **chunk-oriented step** reads items one at a time, processes each, and writes them in **chunks**. Each chunk is one database transaction: read 100 loan ids → work out 100 accruals → save 100 rows → commit. If the step fails, only the current chunk rolls back. Everything committed before it stays.
3. The **`JobRepository`** records every run in its own `BATCH_*` tables: which job, with which parameters, which steps finished, how many items each read and wrote, and where each reader had got to.
4. A **`JobInstance`** is a job plus its **identifying parameters**. *"endOfDay for 2026-11-06"* is one instance. Each attempt to run it is a **`JobExecution`**. Once an instance has a `COMPLETED` execution, Spring Batch refuses to start it again. If an execution `FAILED`, you can **restart** the same instance, and Spring Batch skips the steps that already completed.
5. **`@StepScope`** beans are created when a step starts, not when the application starts, so they can be given that run's job parameters, such as the business date.

```
JobInstance  endOfDay {businessDate=2026-02-27}
 └── JobExecution #1  FAILED
 │     ├── Step accrueInterest   COMPLETED  read=1 write=1   (committed)
 │     └── Step billInstalments  FAILED                       (rolled back)
 └── JobExecution #2  COMPLETED   ← a restart of the same instance
       └── Step billInstalments  COMPLETED                    (accrueInterest is not repeated)
```

### Two levels of idempotency

Making the business date the job's identifying parameter means "run EOD for 6 November" twice in a row is refused by Spring Batch. That's the first line of defence.

It isn't enough on its own. A **new** job instance for the same date is easy to create by accident: add an extra identifying parameter (a `run.id` from Spring Batch's `RunIdIncrementer`, a timestamp, a "rerun=true" flag an operator typed), and Spring Batch sees a brand-new instance. So the **domain** must be [idempotent](../glossary.md#idempotency) too:

- **Logic:** before accruing for a loan and a date, check whether that accrual already exists. If it does, skip it.
- **Constraint:** a unique constraint on *(loan, accrual date)*, so even a bug in the logic can't store two.

This is the same "logic for correct behaviour, database for guaranteed correctness" pairing the Core ledger tutorial uses for idempotency keys. You'll watch the constraint catch a re-run, then add the logic that makes the re-run succeed quietly.

---

## Steps

### 1. Add Spring Batch

Add the Spring Batch JDBC starter, and its test companion, to `pom.xml`:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-batch-jdbc</artifactId>
		</dependency>
```

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-batch-jdbc-test</artifactId>
			<scope>test</scope>
		</dependency>
```

In IntelliJ, click the floating **Load Maven Changes** button (the small Maven icon) that appears. Or run `./mvnw compile` once.

These are the names start.spring.io uses in Boot 4 when you tick **Spring Batch JDBC**: Boot 4 split Spring Batch support into a plain `spring-boot-starter-batch` and a `-jdbc` variant that stores job history in your database. Bank batch jobs need that history, because restart depends on it.

> **In older codebases:** Spring Boot 2 and 3 used a single `spring-boot-starter-batch`, and Spring Batch 4 and 5 put its classes in packages like `org.springframework.batch.core.Job` and `org.springframework.batch.item.ItemReader`. Spring Batch 6 (which Boot 4.1 uses) moved them: `org.springframework.batch.core.job.Job`, `org.springframework.batch.core.step.Step`, and the readers and writers under `org.springframework.batch.infrastructure.item`. You'll also see `@EnableBatchProcessing` on older configuration classes. With Spring Boot you don't need it, and adding it switches off Boot's batch auto-configuration.

### 2. The accrual table

Create `src/main/resources/db/migration/V3__interest_accrual_and_billing.sql`:

```sql
-- One row per loan per day: the interest earned that day, to 6 decimal places
CREATE TABLE interest_accrual (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    loan_id      BIGINT         NOT NULL REFERENCES loan (id),
    accrual_date DATE           NOT NULL,
    balance      NUMERIC(19, 4) NOT NULL,
    amount       NUMERIC(19, 6) NOT NULL,
    CONSTRAINT uk_interest_accrual_loan_date UNIQUE (loan_id, accrual_date)
);

-- What was actually charged when the instalment fell due
ALTER TABLE loan_instalment ADD COLUMN billed_interest NUMERIC(19, 4);
ALTER TABLE loan_instalment ADD COLUMN billed_principal NUMERIC(19, 4);
ALTER TABLE loan_instalment ADD COLUMN billed_on DATE;
```

- **`amount NUMERIC(19, 6)`** matches `InterestMath.ACCRUAL_SCALE`. Fractions of a penny are stored, not lost.
- **`balance`** records the principal the day's interest was calculated on. When a customer asks "why was I charged that?", the answer is in the table, day by day. Storing the inputs to a calculation alongside its result is a habit worth having in banking code.
- **`uk_interest_accrual_loan_date`** is the idempotency backstop: one accrual per loan per day, guaranteed by the database.
- **The `billed_*` columns are nullable**: they stay `NULL` until the instalment is billed.

### 3. The accrual entity and service

Create `interest/InterestAccrual.java`:

```java
package com.example.loans.interest;

import com.example.loans.loan.Loan;
import com.example.loans.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One day's interest on one loan. Fractions of a penny are kept until billing. */
@Entity
@Table(name = "interest_accrual")
public class InterestAccrual {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_id")
    private Loan loan;

    @Column(name = "accrual_date", nullable = false)
    private LocalDate accrualDate;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal amount;

    protected InterestAccrual() {
        // for JPA
    }

    public InterestAccrual(Loan loan, LocalDate accrualDate, Money balance, BigDecimal amount) {
        this.loan = loan;
        this.accrualDate = accrualDate;
        this.balance = balance.amount();
        this.amount = amount;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getAccrualDate() {
        return accrualDate;
    }

    public Money getBalance() {
        return new Money(balance, Money.GBP);
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
```

`amount` is a plain `BigDecimal`, not `Money`, on purpose: `Money` refuses fractions of a penny, and an accrual *is* fractions of a penny.

Create `interest/InterestAccrualRepository.java`:

```java
package com.example.loans.interest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface InterestAccrualRepository extends JpaRepository<InterestAccrual, Long> {

    boolean existsByLoanIdAndAccrualDate(Long loanId, LocalDate accrualDate);

    long countByLoanId(Long loanId);

    /** Accruals from {@code from} (inclusive) up to {@code to} (exclusive). */
    @Query("""
            select coalesce(sum(a.amount), 0) from InterestAccrual a
            where a.loan.id = :loanId and a.accrualDate >= :from and a.accrualDate < :to
            """)
    BigDecimal sumForPeriod(Long loanId, LocalDate from, LocalDate to);

    @Query("""
            select count(a) from InterestAccrual a
            where a.loan.id = :loanId and a.accrualDate >= :from and a.accrualDate < :to
            """)
    long countForPeriod(Long loanId, LocalDate from, LocalDate to);
}
```

The period is **half-open**: from the previous due date (inclusive) up to the due date (exclusive). Half-open ranges are the standard way to describe consecutive periods, because they meet without overlapping or leaving gaps, and the number of days in one is exactly `ChronoUnit.DAYS.between(from, to)`.

Now `interest/AccrualService.java`. For now, it has **no idempotency check**. You'll see why it needs one in step 9.

```java
package com.example.loans.interest;

import com.example.loans.ledger.LedgerService;
import com.example.loans.loan.Loan;
import com.example.loans.loan.LoanNotFoundException;
import com.example.loans.loan.LoanRepository;
import com.example.loans.loan.LoanStatus;
import com.example.loans.money.Money;
import com.example.loans.schedule.InterestMath;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

@Service
public class AccrualService {

    private final LoanRepository loans;
    private final InterestAccrualRepository accruals;
    private final LedgerService ledger;

    public AccrualService(LoanRepository loans, InterestAccrualRepository accruals, LedgerService ledger) {
        this.loans = loans;
        this.accruals = accruals;
        this.ledger = ledger;
    }

    /**
     * Works out one day's interest for one loan, or returns null if there's nothing to do.
     * The caller saves it. (Returning null is how a Spring Batch processor filters an item.)
     */
    @Transactional(readOnly = true)
    public InterestAccrual calculate(Long loanId, LocalDate date) {
        Loan loan = loans.findById(loanId).orElseThrow(() -> new LoanNotFoundException(loanId));
        if (loan.getStatus() != LoanStatus.ACTIVE || date.isBefore(loan.getDrawdownDate())) {
            return null;
        }
        // The principal for interest purposes at the end of that day: by VALUE date
        Money principal = ledger.balanceAsAt(loan.getPrincipalAccount(), date);
        BigDecimal amount = InterestMath.dailyInterest(principal, loan.getAnnualRate());
        return new InterestAccrual(loan, date, principal, amount);
    }
}
```

`InterestMath.dailyInterest` is the same method guide 01's schedule used. That's what will make the billed interest match the schedule exactly.

### 4. Billing

The instalment needs to remember what it was billed. Add the three fields to `loan/Instalment.java`, after `scheduledPrincipal`:

```java
    @Column(name = "billed_interest", precision = 19, scale = 4)
    private BigDecimal billedInterest;

    @Column(name = "billed_principal", precision = 19, scale = 4)
    private BigDecimal billedPrincipal;

    @Column(name = "billed_on")
    private LocalDate billedOn;
```

And these methods at the end of the class:

```java
    /** Fixes what the customer actually owes for this instalment. Happens once. */
    public void bill(Money interest, Money principal, LocalDate businessDate) {
        if (isBilled()) {
            throw new IllegalStateException("Instalment %d of loan %d is already billed"
                    .formatted(number, loan.getId()));
        }
        this.billedInterest = interest.amount();
        this.billedPrincipal = principal.amount();
        this.billedOn = businessDate;
    }

    public boolean isBilled() {
        return billedOn != null;
    }

    public Money getBilledInterest() {
        return billedInterest == null ? null : new Money(billedInterest, Money.GBP);
    }

    public Money getBilledPrincipal() {
        return billedPrincipal == null ? null : new Money(billedPrincipal, Money.GBP);
    }

    public LocalDate getBilledOn() {
        return billedOn;
    }
```

`bill` refuses to bill twice, the entity's own idempotency guard.

Create `interest/BillingService.java`:

```java
package com.example.loans.interest;

import com.example.loans.ledger.JournalEntry;
import com.example.loans.ledger.LedgerAccount;
import com.example.loans.ledger.LedgerService;
import com.example.loans.loan.Instalment;
import com.example.loans.loan.Loan;
import com.example.loans.loan.LoanNotFoundException;
import com.example.loans.loan.LoanRepository;
import com.example.loans.loan.LoanStatus;
import com.example.loans.money.Money;
import com.example.loans.schedule.InterestMath;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
public class BillingService {

    private final LoanRepository loans;
    private final InterestAccrualRepository accruals;
    private final LedgerService ledger;

    public BillingService(LoanRepository loans, InterestAccrualRepository accruals, LedgerService ledger) {
        this.loans = loans;
        this.accruals = accruals;
        this.ledger = ledger;
    }

    /** Bills every instalment of the loan that falls due on or before {@code billUpTo}. */
    @Transactional
    public void billDue(Long loanId, LocalDate businessDate, LocalDate billUpTo) {
        Loan loan = loans.findById(loanId).orElseThrow(() -> new LoanNotFoundException(loanId));
        if (loan.getStatus() != LoanStatus.ACTIVE) {
            return; // nothing more is owed
        }
        LocalDate periodStart = loan.getDrawdownDate();
        Money principalBilledSoFar = Money.zero(Money.GBP);
        for (Instalment instalment : loan.getInstalments()) {
            if (instalment.getDueDate().isAfter(billUpTo)) {
                break;
            }
            if (!instalment.isBilled()) { // billed by an earlier run? leave it alone
                bill(loan, instalment, periodStart, principalBilledSoFar, businessDate);
            }
            principalBilledSoFar = principalBilledSoFar.plus(instalment.getBilledPrincipal());
            periodStart = instalment.getDueDate();
        }
    }

    /** Charges the interest accrued over the period and fixes the amount due. */
    private void bill(Loan loan, Instalment instalment, LocalDate periodStart,
                      Money principalBilledSoFar, LocalDate businessDate) {
        LocalDate dueDate = instalment.getDueDate();
        long expectedDays = ChronoUnit.DAYS.between(periodStart, dueDate);
        long accruedDays = accruals.countForPeriod(loan.getId(), periodStart, dueDate);
        if (accruedDays != expectedDays) {
            throw new IllegalStateException("Loan %d: expected %d days of accruals from %s to %s, found %d"
                    .formatted(loan.getId(), expectedDays, periodStart, dueDate, accruedDays));
        }
        BigDecimal accrued = accruals.sumForPeriod(loan.getId(), periodStart, dueDate);
        Money interest = InterestMath.toPence(accrued, loan.getPrincipal());

        // The last instalment takes whatever principal is left, so the total is always exact
        Money principalLeft = loan.getPrincipal().minus(principalBilledSoFar);
        boolean last = instalment.getNumber() == loan.getTermMonths();
        Money principalDue = last ? principalLeft : loan.getMonthlyPayment().minus(interest);
        if (principalLeft.isLessThan(principalDue)) {
            principalDue = principalLeft;
        }

        if (interest.isPositive()) {
            LedgerAccount income = ledger.getSystemAccount("INTEREST_INCOME");
            ledger.post(JournalEntry.builder(
                            "Loan %d interest, instalment %d".formatted(loan.getId(), instalment.getNumber()),
                            businessDate, dueDate)
                    .debit(loan.getInterestAccount(), interest) // the customer owes the bank more
                    .credit(income, interest)                   // the bank has earned it
                    .build());
        }
        instalment.bill(interest, principalDue, businessDate);
    }
}
```

This is guide 01's schedule logic again, but driven by **what actually happened** rather than what was projected:

- **Interest = the sum of the period's daily accruals, rounded once.** If the customer paid on time, every accrual in the period used the same balance as the schedule did, so the result is identical to the scheduled interest. If they paid late, the balance stayed higher for longer, and the billed interest is correspondingly more.
- **Principal due = level payment − interest**, except for the last instalment, which takes whatever principal is left. Same reconciliation rule as guide 01.
- **The completeness check is a safety net.** If even one day's accrual is missing (because EOD didn't run that day), billing would undercharge. Instead it **fails loudly**, naming the loan, the period and the shortfall. You'll rely on that in step 10.
- **The posting is booked on the business date and value-dated to the due date.**
- **A 0% loan posts nothing** (a journal entry can't have a £0.00 posting), but the instalment is still billed with its principal.
- **It works per loan, not per instalment,** and walks the instalments in order, which it needs for `periodStart` and `principalBilledSoFar`. In guide 04, that also gives one obvious place to take a lock.

Also create `loan/InstalmentRepository.java`. The services don't need it (instalments are reached through their loan), but tests will:

```java
package com.example.loans.loan;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InstalmentRepository extends JpaRepository<Instalment, Long> {
}
```

### 5. The job

Create `eod/EndOfDayJobConfig.java`:

```java
package com.example.loans.eod;

import com.example.loans.interest.AccrualService;
import com.example.loans.interest.BillingService;
import com.example.loans.interest.InterestAccrual;
import com.example.loans.interest.InterestAccrualRepository;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.DefaultJobParametersValidator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.Map;

@Configuration
public class EndOfDayJobConfig {

    public static final String JOB_NAME = "endOfDay";
    public static final String BUSINESS_DATE = "businessDate";
    static final int CHUNK_SIZE = 100;

    @Bean
    public Job endOfDayJob(JobRepository jobRepository, Step accrueInterestStep, Step billInstalmentsStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(new DefaultJobParametersValidator(new String[] {BUSINESS_DATE}, new String[0]))
                .start(accrueInterestStep)
                .next(billInstalmentsStep)
                .build();
    }

    // ---- Step 1: accrue one day's interest on every active loan ----

    @Bean
    public Step accrueInterestStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                   JdbcPagingItemReader<Long> activeLoanIds,
                                   ItemProcessor<Long, InterestAccrual> accrualProcessor,
                                   InterestAccrualRepository accruals) {
        return new StepBuilder("accrueInterest", jobRepository)
                .<Long, InterestAccrual>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(activeLoanIds)
                .processor(accrualProcessor)
                .writer(accruals::saveAll)
                .build();
    }

    @Bean
    @StepScope
    public JdbcPagingItemReader<Long> activeLoanIds(
            DataSource dataSource, @Value("#{jobParameters['businessDate']}") LocalDate businessDate) throws Exception {
        return new JdbcPagingItemReaderBuilder<Long>()
                .name("activeLoanIds")
                .dataSource(dataSource)
                .selectClause("select id")
                .fromClause("from loan")
                .whereClause("where status = 'ACTIVE' and drawdown_date <= :businessDate")
                .parameterValues(Map.of("businessDate", businessDate))
                .sortKeys(Map.of("id", Order.ASCENDING))
                .rowMapper((rs, rowNum) -> rs.getLong("id"))
                .pageSize(CHUNK_SIZE)
                .build();
    }

    @Bean
    @StepScope
    public ItemProcessor<Long, InterestAccrual> accrualProcessor(
            AccrualService accrualService, @Value("#{jobParameters['businessDate']}") LocalDate businessDate) {
        return loanId -> accrualService.calculate(loanId, businessDate);
    }

    // ---- Step 2: bill every instalment due by tomorrow that isn't billed yet ----

    @Bean
    public Step billInstalmentsStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                    JdbcPagingItemReader<Long> loansToBill,
                                    ItemWriter<Long> billingWriter) {
        return new StepBuilder("billInstalments", jobRepository)
                .<Long, Long>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(loansToBill)
                .writer(billingWriter)
                .build();
    }

    @Bean
    @StepScope
    public JdbcPagingItemReader<Long> loansToBill(
            DataSource dataSource, @Value("#{jobParameters['businessDate']}") LocalDate businessDate) throws Exception {
        return new JdbcPagingItemReaderBuilder<Long>()
                .name("loansToBill")
                .dataSource(dataSource)
                .selectClause("select distinct loan_id")
                .fromClause("from loan_instalment")
                .whereClause("where billed_on is null and due_date <= :billUpTo")
                .parameterValues(Map.of("billUpTo", businessDate.plusDays(1)))
                .sortKeys(Map.of("loan_id", Order.ASCENDING))
                .rowMapper((rs, rowNum) -> rs.getLong("loan_id"))
                .pageSize(CHUNK_SIZE)
                .build();
    }

    @Bean
    @StepScope
    public ItemWriter<Long> billingWriter(
            BillingService billingService, @Value("#{jobParameters['businessDate']}") LocalDate businessDate) {
        LocalDate billUpTo = businessDate.plusDays(1);
        return chunk -> chunk.forEach(loanId -> billingService.billDue(loanId, businessDate, billUpTo));
    }
}
```

There's a lot here. Take it piece by piece.

**The job.** `JobBuilder` names the job and lists its steps in order. The `DefaultJobParametersValidator` makes `businessDate` a **required** parameter: a run without it is rejected before anything happens.

**Step 1 is a textbook read → process → write step.**

- The **reader** returns loan **ids**, not `Loan` entities. Ids are tiny, and the processor loads each loan fresh in the chunk's transaction. Readers that return whole entities are common too, but they hand you objects loaded in a different transaction, which causes trouble the moment something else changes them (guide 04 is all about that).
- The **processor** calls `AccrualService.calculate`. When it returns `null`, Spring Batch **filters** the item: it's counted in the step's *filter count* and never reaches the writer.
- The **writer** is `accruals::saveAll`. In Spring Batch 6, `ItemWriter` is a functional interface that takes a `Chunk<? extends T>`, and `Chunk` is `Iterable`, so a repository's `saveAll` fits it directly.
- **`chunk(CHUNK_SIZE)`** commits every 100 items. Bigger chunks mean fewer commits (faster). Smaller chunks mean less work lost when one fails. 100 is a common starting point.

**`@StepScope` and `#{jobParameters['businessDate']}`.** The readers, processor and writer need the business date, which only exists once a job is launched. `@StepScope` delays creating those beans until the step runs, and the SpEL expression injects the job parameter, already converted to a `LocalDate`.

**Both readers are `JdbcPagingItemReader`s with a sort key.** A paging reader fetches one page of results at a time. This one pages by **key** (keyset or "seek" paging): the first page is "the first 100 ids", and every later page is "the next 100 ids greater than the last one I saw". It does not use `OFFSET`. That distinction matters a great deal for step 2, because billing removes loans from its own query as it goes (`billed_on` stops being `NULL`). An offset-based reader would skip rows. You'll see that happen in Exercise 3.

**Step 2 has no processor.** For each loan id, the writer calls `billDue`. Billing is "read an id, do a unit of work", which is a perfectly normal shape for a batch step.

> **Sidebar: `throws Exception` on a `@Bean` method?** `JdbcPagingItemReaderBuilder.build()` checks its configuration and declares `throws Exception`. Spring is happy for `@Bean` methods to throw checked exceptions, so declaring it is the simplest fix.

> **In older codebases:** you'll see `stepBuilderFactory.get("step").<I, O>chunk(100)` (Spring Batch 4; the factories were removed in 5) and `new StepBuilder("step", jobRepository).<I, O>chunk(100, transactionManager)` (Spring Batch 5). In Spring Batch 6 that two-argument `chunk` still exists but is **deprecated**. The new style is `.chunk(100).transactionManager(transactionManager)`, which builds the reworked `ChunkOrientedStep`.

### 6. Launching the job

Create `eod/EndOfDayService.java`:

```java
package com.example.loans.eod;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class EndOfDayService {

    private final JobOperator jobOperator;
    private final Job endOfDayJob;

    public EndOfDayService(JobOperator jobOperator, Job endOfDayJob) {
        this.jobOperator = jobOperator;
        this.endOfDayJob = endOfDayJob;
    }

    /** Runs end of day for one business date. The date IS the job's identity. */
    public JobExecution run(LocalDate businessDate) throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addLocalDate(EndOfDayJobConfig.BUSINESS_DATE, businessDate)
                .toJobParameters();
        return jobOperator.start(endOfDayJob, parameters);
    }
}
```

- **`JobOperator`** is Spring Batch 6's single entry point for starting, restarting and stopping jobs. Spring Boot creates one for you.
- **`addLocalDate`** adds an *identifying* parameter (the default). The `JobInstance` is now *endOfDay + this date*, and nothing else.
- **By default the launch is synchronous**: `start` returns once the job has finished, with its final `JobExecution`. That keeps tests and the API simple. A long real EOD would be launched asynchronously and monitored.

> **In older codebases:** jobs are launched with `jobLauncher.run(job, parameters)`. In Spring Batch 6, `JobOperator` extends `JobLauncher`, and `JobLauncher` itself is deprecated.

Now a small API, so you can run EOD by hand. Create `eod/EndOfDayResult.java`:

```java
package com.example.loans.eod;

import org.springframework.batch.core.job.JobExecution;

import java.time.LocalDate;
import java.util.List;

/** What happened in one end-of-day run, step by step. */
public record EndOfDayResult(Long jobExecutionId, LocalDate businessDate, String status, List<StepResult> steps) {

    public record StepResult(String name, String status, long read, long filtered, long written) {
    }

    static EndOfDayResult from(JobExecution execution) {
        List<StepResult> steps = execution.getStepExecutions().stream()
                .map(step -> new StepResult(step.getStepName(), step.getStatus().name(),
                        step.getReadCount(), step.getFilterCount(), step.getWriteCount()))
                .toList();
        LocalDate businessDate = execution.getJobParameters().getLocalDate(EndOfDayJobConfig.BUSINESS_DATE);
        return new EndOfDayResult(execution.getId(), businessDate, execution.getStatus().name(), steps);
    }
}
```

`eod/EndOfDayController.java`:

```java
package com.example.loans.eod;

import com.example.loans.time.BusinessDate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/eod")
public class EndOfDayController {

    private final EndOfDayService endOfDayService;
    private final BusinessDate businessDate;

    public EndOfDayController(EndOfDayService endOfDayService, BusinessDate businessDate) {
        this.endOfDayService = endOfDayService;
        this.businessDate = businessDate;
    }

    /** Runs end of day for the given date, or for today's business date. */
    @PostMapping
    public EndOfDayResult run(@RequestParam(required = false) LocalDate businessDate) throws Exception {
        LocalDate date = businessDate != null ? businessDate : this.businessDate.today();
        return EndOfDayResult.from(endOfDayService.run(date));
    }
}
```

In `ApiExceptionHandler`, map "already run" to a `409 Conflict` (import `JobInstanceAlreadyCompleteException` from `org.springframework.batch.core.launch`):

```java
    @ExceptionHandler(JobInstanceAlreadyCompleteException.class)
    ProblemDetail handle(JobInstanceAlreadyCompleteException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "End of day has already completed for this business date");
        problem.setTitle("End of day already run");
        return problem;
    }
```

`409` because the request conflicts with the current state: that day is closed. The detail is written for the client. The exception's own message mentions job parameter internals, which belong in the log, not the API.

> **Sidebar: who calls `POST /eod`?** In this tutorial, you do (and the tests). In a bank, EOD is started by a **scheduler**: an enterprise job scheduler such as Control-M or Autosys, or a Kubernetes `CronJob`, which also orchestrates the many other systems that run overnight. You could add Spring's `@Scheduled` here, but banks rarely let an application decide for itself when its batch runs, and an HTTP endpoint is effectively "an operator pressed the button". It would sit behind authentication in real life.

### 7. Start the app, and meet Boot's batch defaults

Run the app. It fails to start:

```
org.springframework.jdbc.BadSqlGrammarException: PreparedStatementCallback; bad SQL grammar [SELECT JOB_INSTANCE_ID, JOB_NAME
FROM BATCH_JOB_INSTANCE
...
Caused by: org.h2.jdbc.JdbcSQLSyntaxErrorException: Table "BATCH_JOB_INSTANCE" not found; SQL statement:
```

Two of Spring Boot's batch defaults just collided with this project:

1. **Spring Batch needs its `BATCH_*` tables, and Boot only creates them automatically for embedded, in-memory databases.** Your tests use in-memory H2, so they'd be fine. The app uses a **file** H2 database, which Boot treats like a real server database, and leaves the schema alone.
2. **When there's a single `Job` bean, Boot runs it at start-up** (that's why anything queried `BATCH_JOB_INSTANCE` at all). That's handy for one-off batch applications, and exactly wrong for an EOD job.

Fix the first by adding to `application.properties`:

```properties
# Spring Batch keeps its job history in BATCH_* tables. Create them if missing.
spring.batch.jdbc.initialize-schema=always
```

Run again. The tables now exist, so the start-up run gets further, and the validator from step 5 stops it:

```
Caused by: org.springframework.batch.core.job.parameters.InvalidJobParametersException: The JobParameters do not contain required keys: [businessDate]
```

Good: without that validator, Boot would have happily run the job with **no** business date. Now fix the second problem:

```properties
# Don't run the job at start-up: end of day is triggered on purpose, for a date
spring.batch.job.enabled=false
```

The app now starts. Stop it and start it again: it starts cleanly a second time too. `initialize-schema=always` runs Spring Batch's `CREATE TABLE` script on every start-up, and Boot's batch initialiser is set to **continue on error** by default, so "table already exists" doesn't stop the app.

You'll also see two warnings like this in the log, one for the processor and one for the writer:

```
WARN ... o.s.b.c.l.AbstractListenerFactoryBean : org.springframework.batch.infrastructure.item.ItemProcessor is an interface. The implementing class will not be queried for annotation based listener configurations. If using @StepScope on a @Bean method, be sure to return the implementing class so listener annotations can be used.
```

They're harmless here. They only matter if you put Spring Batch listener annotations (like `@BeforeStep`) on a step-scoped class, because Spring Batch can only see the declared return type (here a lambda behind an interface).

> **Sidebar: Batch tables in a real bank.** `initialize-schema=always` is fine for development. Production teams usually create the `BATCH_*` tables in a database migration, alongside their own schema, so that nothing creates tables at runtime. Spring Batch ships one DDL script per database (`schema-h2.sql`, `schema-postgresql.sql` and so on, inside `spring-batch-core`), so unlike this tutorial's own migrations, that DDL isn't portable. It's one of the few places where you'd keep database-specific migrations.

### 8. Test the job

The EOD job processes **every** active loan. Other test classes leave loans behind in the shared in-memory database, with drawdown dates all over the calendar, and some of those would fail billing's completeness check. So EOD tests start from a known state. Create `src/test/java/com/example/loans/CleanDatabase.java`:

```java
package com.example.loans;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.test.jdbc.JdbcTestUtils;

/**
 * End of day processes EVERY active loan, so its tests need a known starting point.
 * Deletes loans, the ledger (except the seeded income account) and Spring Batch's history.
 */
@Component
public class CleanDatabase {

    private final JdbcTemplate jdbc;

    public CleanDatabase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void reset() {
        JdbcTestUtils.deleteFromTables(jdbc,
                "BATCH_STEP_EXECUTION_CONTEXT", "BATCH_STEP_EXECUTION", "BATCH_JOB_EXECUTION_CONTEXT",
                "BATCH_JOB_EXECUTION_PARAMS", "BATCH_JOB_EXECUTION", "BATCH_JOB_INSTANCE",
                "posting", "journal_entry", "interest_accrual", "loan_instalment", "loan");
        JdbcTestUtils.deleteFromTableWhere(jdbc, "ledger_account", "system_code is null");
        jdbc.update("update ledger_account set balance = 0");
    }
}
```

- **The tables are listed children first**, so no foreign key is violated.
- **The batch history is cleared too.** Otherwise a test that runs EOD for 31 January 2026 would be refused because an earlier test already completed that job instance.
- **The income account is kept** (it's reference data from a migration) but its balance is reset.

Register it in `TestClockConfig` so every integration test can inject it:

```java
@TestConfiguration
@Import(CleanDatabase.class)
public class TestClockConfig {
```

(Import `org.springframework.context.annotation.Import`.)

Now `src/test/java/com/example/loans/eod/EndOfDayJobTest.java`:

```java
package com.example.loans.eod;

import com.example.loans.CleanDatabase;
import com.example.loans.LoansIntegrationTest;
import com.example.loans.MutableClock;
import com.example.loans.account.AccountService;
import com.example.loans.interest.InterestAccrualRepository;
import com.example.loans.ledger.LedgerService;
import com.example.loans.loan.Instalment;
import com.example.loans.loan.InstalmentRepository;
import com.example.loans.loan.LoanService;
import com.example.loans.loan.LoanSummary;
import com.example.loans.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

@LoansIntegrationTest
class EndOfDayJobTest {

    static final LocalDate DRAWDOWN = LocalDate.of(2026, 1, 31);

    @Autowired CleanDatabase cleanDatabase;
    @Autowired MutableClock clock;
    @Autowired AccountService accountService;
    @Autowired LoanService loanService;
    @Autowired EndOfDayService endOfDay;
    @Autowired InterestAccrualRepository accruals;
    @Autowired InstalmentRepository instalments;
    @Autowired LedgerService ledger;

    LoanSummary loan;

    @BeforeEach
    void setUp() {
        cleanDatabase.reset();
        clock.setDate(DRAWDOWN);
        Long alice = accountService.open("Alice").getId();
        loan = loanService.drawDown(alice, Money.gbp("10000.00"), new BigDecimal("6.9"), 36);
    }

    @Test
    void accruesOneDayOfInterestOnTheValueDatedPrincipal() throws Exception {
        JobExecution execution = endOfDay.run(DRAWDOWN);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(accruals.findAll()).singleElement().satisfies(accrual -> {
            assertThat(accrual.getAccrualDate()).isEqualTo(DRAWDOWN);
            assertThat(accrual.getBalance()).isEqualTo(Money.gbp("10000.00"));
            assertThat(accrual.getAmount()).isEqualByComparingTo("1.890411");
        });
    }

    @Test
    void billsTheFirstInstalmentWithExactlyTheScheduledInterest() throws Exception {
        // EOD every day from drawdown to the day before the first due date (28 February)
        for (LocalDate day = DRAWDOWN; day.isBefore(LocalDate.of(2026, 2, 28)); day = day.plusDays(1)) {
            assertThat(endOfDay.run(day).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        Instalment first = instalments.findAll().stream()
                .min(Comparator.comparing(Instalment::getDueDate)).orElseThrow();
        assertThat(first.isBilled()).isTrue();
        assertThat(first.getBilledOn()).isEqualTo(LocalDate.of(2026, 2, 27));
        assertThat(first.getBilledInterest()).isEqualTo(first.getScheduledInterest());
        assertThat(first.getBilledInterest()).isEqualTo(Money.gbp("52.93"));
        assertThat(first.getBilledPrincipal()).isEqualTo(Money.gbp("255.38"));
        assertThat(ledger.getSystemAccount("INTEREST_INCOME").getBalance()).isEqualTo(Money.gbp("52.93"));
        assertThat(loanService.summary(loan.id()).interestOutstanding()).isEqualTo(Money.gbp("52.93"));
    }
}
```

The second test is the payoff from guide 01's design decision. It runs EOD 28 times, once for every night from 31 January to 27 February. On the 27th, the job bills instalment 1, and the billed interest is **exactly** the £52.93 the schedule promised: 28 accruals of 1.890411, summed and rounded once. The income account and the loan's interest account both show £52.93, because billing posted a balanced entry between them.

The test isn't `@Transactional`. Each EOD run commits its own chunks, exactly as it would in production, and Spring Batch's job repository wouldn't work inside a test transaction anyway.

Then `src/test/java/com/example/loans/eod/EndOfDayApiTest.java`:

```java
package com.example.loans.eod;

import com.example.loans.CleanDatabase;
import com.example.loans.LoansIntegrationTest;
import com.example.loans.MutableClock;
import com.example.loans.account.AccountService;
import com.example.loans.loan.LoanService;
import com.example.loans.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@LoansIntegrationTest
class EndOfDayApiTest {

    @Autowired MockMvcTester mvc;
    @Autowired CleanDatabase cleanDatabase;
    @Autowired MutableClock clock;
    @Autowired AccountService accountService;
    @Autowired LoanService loanService;

    @BeforeEach
    void setUp() {
        cleanDatabase.reset();
        clock.setDate(LocalDate.of(2026, 11, 6));
        Long alice = accountService.open("Alice").getId();
        loanService.drawDown(alice, Money.gbp("5000.00"), new BigDecimal("7.9"), 12);
    }

    @Test
    void runsEndOfDayForTodaysBusinessDateByDefault() {
        var response = mvc.post().uri("/eod").exchange();

        assertThat(response).hasStatus(HttpStatus.OK);
        assertThat(response).bodyJson().extractingPath("$.businessDate").isEqualTo("2026-11-06");
        assertThat(response).bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");
        assertThat(response).bodyJson().extractingPath("$.steps[0].name").isEqualTo("accrueInterest");
        assertThat(response).bodyJson().extractingPath("$.steps[0].written").isEqualTo(1);
    }

    @Test
    void refusesToRunTheSameDateTwice() {
        mvc.post().uri("/eod?businessDate=2026-11-06").exchange();

        var response = mvc.post().uri("/eod?businessDate=2026-11-06").exchange();

        assertThat(response).hasStatus(HttpStatus.CONFLICT);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("End of day already run");
    }
}
```

Run the tests. All pass. Try it by hand too. Add to `requests.http`:

```http
### Run end of day for today's business date
POST http://localhost:8080/eod

### Run end of day for a specific date
POST http://localhost:8080/eod?businessDate=2026-11-07
```

With Alice's £5,000 loan from guide 02 in place, `POST /eod` on 6 November 2026 returns:

```json
{
  "jobExecutionId": 1,
  "businessDate": "2026-11-06",
  "status": "COMPLETED",
  "steps": [
    { "name": "accrueInterest", "status": "COMPLETED", "read": 1, "filtered": 0, "written": 1 },
    { "name": "billInstalments", "status": "COMPLETED", "read": 0, "filtered": 0, "written": 0 }
  ]
}
```

And the log tells the same story:

```
Job: [SimpleJob: [name=endOfDay]] launched with the following parameters: [{JobParameter{name='businessDate', value=2026-11-06, type=class java.time.LocalDate, identifying=true}}]
Executing step: [accrueInterest]
Step: [accrueInterest] executed in 450ms
Executing step: [billInstalments]
Step: [billInstalments] executed in 27ms
Job: [SimpleJob: [name=endOfDay]] completed with the following parameters: [{JobParameter{name='businessDate', value=2026-11-06, type=class java.time.LocalDate, identifying=true}}] and the following status: [COMPLETED] in 597ms
```

Send the same request again and you get the `409`:

```json
{"detail":"End of day has already completed for this business date","instance":"/eod","status":409,"title":"End of day already run"}
```

### 9. Idempotency: watch a re-run fail

Spring Batch refused the second run. Now try the way it goes wrong in practice: the same date, as a **new** job instance. Create `src/test/java/com/example/loans/eod/EndOfDayIdempotencyTest.java`:

```java
package com.example.loans.eod;

import com.example.loans.CleanDatabase;
import com.example.loans.LoansIntegrationTest;
import com.example.loans.MutableClock;
import com.example.loans.account.AccountService;
import com.example.loans.interest.InterestAccrualRepository;
import com.example.loans.loan.LoanService;
import com.example.loans.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@LoansIntegrationTest
class EndOfDayIdempotencyTest {

    static final LocalDate DAY = LocalDate.of(2026, 3, 2);

    @Autowired CleanDatabase cleanDatabase;
    @Autowired MutableClock clock;
    @Autowired AccountService accountService;
    @Autowired LoanService loanService;
    @Autowired EndOfDayService endOfDay;
    @Autowired JobOperator jobOperator;
    @Autowired Job endOfDayJob;
    @Autowired InterestAccrualRepository accruals;

    @BeforeEach
    void setUp() {
        cleanDatabase.reset();
        clock.setDate(DAY);
        for (int i = 1; i <= 3; i++) {
            Long customer = accountService.open("Customer " + i).getId();
            loanService.drawDown(customer, Money.gbp("5000.00"), new BigDecimal("7.9"), 12);
        }
    }

    @Test
    void secondRunForTheSameDateIsRefusedBySpringBatch() throws Exception {
        endOfDay.run(DAY);

        assertThatThrownBy(() -> endOfDay.run(DAY))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);
        assertThat(accruals.count()).isEqualTo(3);
    }

    @Test
    void aRerunAsANewJobInstanceDoesNotAccrueTwice() throws Exception {
        endOfDay.run(DAY);

        // Same business date, but an extra identifying parameter makes it a NEW job instance,
        // exactly what a RunIdIncrementer or a well-meaning operator would do
        JobExecution rerun = jobOperator.start(endOfDayJob, new JobParametersBuilder()
                .addLocalDate(EndOfDayJobConfig.BUSINESS_DATE, DAY)
                .addLong("run.id", 2L)
                .toJobParameters());

        assertThat(rerun.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(accruals.count()).isEqualTo(3);
        assertThat(rerun.getStepExecutions()).filteredOn(s -> s.getStepName().equals("accrueInterest"))
                .singleElement().satisfies(step -> {
                    assertThat(step.getReadCount()).isEqualTo(3);
                    assertThat(step.getFilterCount()).isEqualTo(3);
                    assertThat(step.getWriteCount()).isZero();
                });
    }
}
```

Run it. The first test passes. The second fails:

```
expected: COMPLETED
 but was: FAILED
```

The log shows why:

```
WARN  ... org.hibernate.orm.jdbc.error : Unique index or primary key violation: "PUBLIC.UK_INTEREST_ACCRUAL_LOAN_DATE INDEX PUBLIC.UK_INTEREST_ACCRUAL_LOAN_DATE_INDEX_F ON PUBLIC.INTEREST_ACCRUAL(LOAN_ID NULLS FIRST, ACCRUAL_DATE NULLS FIRST) VALUES ( /* key:1 */ CAST(1 AS BIGINT), DATE '2026-03-02')"; SQL statement:
ERROR ... o.s.b.core.step.item.ChunkOrientedStep : Chunk write failed with an exception that cannot be skipped
ERROR ... o.s.b.core.step.item.ChunkOrientedStep : Rolling back chunk transaction
ERROR ... o.s.batch.core.step.AbstractStep : Encountered an error executing step accrueInterest in job endOfDay
```

Read that as good news and bad news:

- ✅ **No customer was charged twice.** The unique constraint stopped the second accrual for loan 1 on 2 March, the chunk rolled back, and the three original accruals are untouched.
- ❌ **The job failed.** In production, that's a page to the on-call engineer in the middle of the night, and every *other* loan in that chunk wasn't processed either. A harmless re-run shouldn't be an incident.

The constraint is the **backstop**. The behaviour you actually want comes from the logic.

### 10. Make the accrual idempotent

In `AccrualService.calculate`, skip a day that's already been accrued. Add this after the status check:

```java
        if (accruals.existsByLoanIdAndAccrualDate(loanId, date)) {
            return null; // already accrued by an earlier run: doing it again would charge twice
        }
```

Run `EndOfDayIdempotencyTest` again. Green. The re-run **read** three loans, **filtered** all three (the processor returned `null`), and **wrote** nothing. That's exactly what the assertions on the step's counts check: a re-run that's visibly a no-op.

Billing was idempotent from the start: `billDue` skips instalments that are already billed, and the reader only finds loans with unbilled instalments in the first place.

> **Sidebar: why not just `RunIdIncrementer`?** Many Spring Batch examples add `.incrementer(new RunIdIncrementer())` to every job, so it can be "run again" with `startNextInstance`. For a reporting job, that's fine. For a job whose identity *is* a business date, it removes the guard you want: every launch becomes a new instance, and only your domain logic stands between a double run and double-charged interest. Key business batch jobs by the business date, and make the processing idempotent anyway.

### 11. Restart after a failure

A real EOD run fails for real reasons. Here's one this design catches: **a day is missed.** If EOD didn't run on 10 February, that day's interest was never accrued, and billing on the 27th would undercharge. The completeness check in `BillingService` turns that into a loud failure instead. Then operations fix the cause and **restart** the failed run.

Create `src/test/java/com/example/loans/eod/EndOfDayRestartTest.java`:

```java
package com.example.loans.eod;

import com.example.loans.CleanDatabase;
import com.example.loans.LoansIntegrationTest;
import com.example.loans.MutableClock;
import com.example.loans.account.AccountService;
import com.example.loans.loan.Instalment;
import com.example.loans.loan.InstalmentRepository;
import com.example.loans.loan.LoanService;
import com.example.loans.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

@LoansIntegrationTest
class EndOfDayRestartTest {

    static final LocalDate DRAWDOWN = LocalDate.of(2026, 1, 31);
    static final LocalDate MISSED = LocalDate.of(2026, 2, 10);
    static final LocalDate DAY_BEFORE_DUE = LocalDate.of(2026, 2, 27);

    @Autowired CleanDatabase cleanDatabase;
    @Autowired MutableClock clock;
    @Autowired AccountService accountService;
    @Autowired LoanService loanService;
    @Autowired EndOfDayService endOfDay;
    @Autowired JobOperator jobOperator;
    @Autowired InstalmentRepository instalments;

    @BeforeEach
    void setUp() {
        cleanDatabase.reset();
        clock.setDate(DRAWDOWN);
        Long alice = accountService.open("Alice").getId();
        loanService.drawDown(alice, Money.gbp("10000.00"), new BigDecimal("6.9"), 36);
    }

    @Test
    void aFailedRunCanBeRestartedOnceTheProblemIsFixed() throws Exception {
        for (LocalDate day = DRAWDOWN; day.isBefore(DAY_BEFORE_DUE); day = day.plusDays(1)) {
            if (!day.equals(MISSED)) {
                endOfDay.run(day);
            }
        }

        // Billing needs 28 days of accruals but finds 27, so the job stops
        JobExecution failed = endOfDay.run(DAY_BEFORE_DUE);
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failed.getAllFailureExceptions()).singleElement().satisfies(e ->
                assertThat(e).rootCause().hasMessageContaining("expected 28 days of accruals from 2026-01-31 to 2026-02-28, found 27"));
        assertThat(firstInstalment().isBilled()).isFalse();

        // Ops fix the cause: run the missed day
        assertThat(endOfDay.run(MISSED).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Restart the SAME job instance. The completed accrual step is not repeated.
        JobExecution restarted = jobOperator.restart(failed);
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getJobInstance().getId()).isEqualTo(failed.getJobInstance().getId());
        assertThat(restarted.getStepExecutions()).extracting(s -> s.getStepName())
                .contains("billInstalments")
                .doesNotContain("accrueInterest");
        assertThat(firstInstalment().getBilledInterest()).isEqualTo(Money.gbp("52.93"));
    }

    private Instalment firstInstalment() {
        return instalments.findAll().stream().min(Comparator.comparing(Instalment::getDueDate)).orElseThrow();
    }
}
```

Follow it through:

1. EOD runs every night from 31 January to 26 February, **except 10 February**.
2. EOD for 27 February: `accrueInterest` completes (27 February's accrual is committed), then `billInstalments` throws. The job is `FAILED`, and instalment 1 isn't billed.

   ```
   java.lang.IllegalStateException: Loan 1: expected 28 days of accruals from 2026-01-31 to 2026-02-28, found 27
   ```

3. Running EOD for 10 February late is fine: accruals use the **value-dated** principal for that date, whenever they're calculated.
4. **`jobOperator.restart(failed)`** creates a new execution of the **same** job instance (*endOfDay, 27 February*). Spring Batch looks at the job repository, sees `accrueInterest` already `COMPLETED` in that instance, and skips it. Only `billInstalments` runs, and this time it finds all 28 days and bills £52.93.

Two details about the assertions:

- Spring Batch wraps the step's exception: the outer exception's message is `Unable to process chunk` (a `FatalStepExecutionException`). AssertJ's `rootCause()` reaches the `IllegalStateException` underneath.
- The step-name assertion says "contains `billInstalments`, doesn't contain `accrueInterest`" rather than listing the steps exactly, so it keeps working when guide 04 adds a third step.

> **Sidebar: restarting *inside* a step.** This restart skipped a whole completed step. Spring Batch can also resume a step partway through: readers like `JdbcPagingItemReader` save their position (the last sort key they read) in the step's `ExecutionContext` at every commit, and a restarted step carries on after it. That's why every reader has a `name`: it's the key its state is saved under. Here, the domain checks (skip accrued days, skip billed instalments) make reprocessing a chunk harmless anyway, which is the safest kind of restartability: **correct even if the framework's bookmark is wrong.**

---

## Your turn

### Exercise 1: interest accrued so far

Customers (and guide 04's settlement quote) need to know how much interest has built up **since the last bill**. Add an `interestAccrued` field to `LoanSummary`: the sum of the loan's accruals from the start of the current, not-yet-billed period, rounded to the penny.

Then add a test to `EndOfDayJobTest`: after EOD for 31 January, 1 February and 2 February, the summary shows £5.67 accrued and £0.00 interest outstanding.

<details>
<summary>Hint</summary>

The current period starts at the due date of the last **billed** instalment, or the drawdown date if none is billed yet. Put that in a `Loan.currentPeriodStart()` method. Add a `sumFrom(loanId, from)` query to `InterestAccrualRepository`. `LoanService.summary` can then pass the result into `LoanSummary.from`. In `drawDown`, nothing has accrued yet, so pass zero.

</details>

<details>
<summary>Solution</summary>

`InterestAccrualRepository`:

```java
    /** Everything accrued on or after {@code from}: interest earned but not yet billed. */
    @Query("select coalesce(sum(a.amount), 0) from InterestAccrual a where a.loan.id = :loanId and a.accrualDate >= :from")
    BigDecimal sumFrom(Long loanId, LocalDate from);
```

`Loan`:

```java
    /** Where the current, not-yet-billed interest period started. */
    public LocalDate currentPeriodStart() {
        LocalDate start = drawdownDate;
        for (Instalment instalment : instalments) {
            if (instalment.isBilled()) {
                start = instalment.getDueDate();
            }
        }
        return start;
    }
```

`LoanSummary` gains a last component and a parameter:

```java
        Money interestOutstanding,
        Money interestAccrued) {

    static LoanSummary from(Loan loan, Money interestAccrued) {
        return new LoanSummary(
                loan.getId(), loan.getCustomerAccount().getId(), loan.getStatus(),
                loan.getPrincipal(), loan.getAnnualRate(), loan.getTermMonths(),
                loan.getDrawdownDate(), loan.getMonthlyPayment(),
                loan.getPrincipalAccount().getBalance(), loan.getInterestAccount().getBalance(),
                interestAccrued);
    }
```

`LoanService` gets an `InterestAccrualRepository accruals` constructor parameter and field, and:

```java
        return LoanSummary.from(loan, Money.zero(Money.GBP));   // at the end of drawDown
```

```java
    @Transactional(readOnly = true)
    public LoanSummary summary(Long loanId) {
        Loan loan = get(loanId);
        BigDecimal accrued = accruals.sumFrom(loanId, loan.currentPeriodStart());
        return LoanSummary.from(loan, InterestMath.toPence(accrued, loan.getPrincipal()));
    }
```

`EndOfDayJobTest`:

```java
    @Test
    void summaryShowsInterestAccruedSinceTheLastBill() throws Exception {
        for (LocalDate day = DRAWDOWN; day.isBefore(LocalDate.of(2026, 2, 3)); day = day.plusDays(1)) {
            endOfDay.run(day);
        }

        // 3 days × 1.890411 = 5.671233 → £5.67, accrued but not billed yet
        LoanSummary summary = loanService.summary(loan.id());
        assertThat(summary.interestAccrued()).isEqualTo(Money.gbp("5.67"));
        assertThat(summary.interestOutstanding()).isEqualTo(Money.gbp("0.00"));
    }
```

`interestOutstanding` is **billed** interest the customer owes now. `interestAccrued` is interest **earned** but not yet billed. Customers see both in a real banking app, under names like "interest due" and "interest to date".

</details>

### Exercise 2: an interest-free loan

Write a test, in `EndOfDayJobTest`, for a **0%** loan of £1,200 over 12 months, drawn on 31 January. After EOD up to 27 February, the first instalment must be billed with £0.00 interest and £100.00 principal, every run must be `COMPLETED`, and the income account must still be at £0.00. Predict first: what would go wrong if `BillingService` posted the interest without the `interest.isPositive()` check?

<details>
<summary>Solution</summary>

```java
    @Test
    void billsAnInterestFreeLoanWithoutPostingZeroInterest() throws Exception {
        cleanDatabase.reset();
        Long bob = accountService.open("Bob").getId();
        loanService.drawDown(bob, Money.gbp("1200.00"), BigDecimal.ZERO, 12);

        for (LocalDate day = DRAWDOWN; day.isBefore(LocalDate.of(2026, 2, 28)); day = day.plusDays(1)) {
            assertThat(endOfDay.run(day).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        Instalment first = instalments.findAll().stream()
                .min(Comparator.comparing(Instalment::getDueDate)).orElseThrow();
        assertThat(first.getBilledInterest()).isEqualTo(Money.gbp("0.00"));
        assertThat(first.getBilledPrincipal()).isEqualTo(Money.gbp("100.00"));
        assertThat(ledger.getSystemAccount("INTEREST_INCOME").getBalance()).isEqualTo(Money.gbp("0.00"));
    }
```

(It calls `cleanDatabase.reset()` again so Alice's loan from `@BeforeEach` doesn't get in the way.)

Without the check, `JournalEntry.Builder.add` would throw `IllegalArgumentException: Posting amounts must be positive, got GBP 0.00`, and billing would fail for every interest-free loan. The daily accruals are still written (as zeros), which is harmless and keeps the completeness check simple.

</details>

### Exercise 3: break it on purpose (offset paging)

This one is an experiment. Revert it afterwards.

1. Replace the `loansToBill` reader with a **`JpaPagingItemReader`**, which pages with `OFFSET`:

   ```java
   @Bean
   @StepScope
   public JpaPagingItemReader<Long> loansToBill(
           EntityManagerFactory entityManagerFactory,
           @Value("#{jobParameters['businessDate']}") LocalDate businessDate) throws Exception {
       return new JpaPagingItemReaderBuilder<Long>()
               .name("loansToBill")
               .entityManagerFactory(entityManagerFactory)
               .queryString("""
                       select distinct i.loan.id from Instalment i
                       where i.billedOn is null and i.dueDate <= :billUpTo
                       order by i.loan.id""")
               .parameterValues(Map.of("billUpTo", businessDate.plusDays(1)))
               .pageSize(CHUNK_SIZE)
               .build();
   }
   ```

   (Change the `billInstalmentsStep` parameter type to match, and import `JpaPagingItemReader`, `JpaPagingItemReaderBuilder` and `jakarta.persistence.EntityManagerFactory`.)

2. Set `CHUNK_SIZE` to `1`, so every item is its own page and its own transaction.
3. Write a test that draws down **four** £1,000 loans on 31 January, runs EOD up to 27 February, and asserts that four instalments are billed.

What happens, and why? Then put the `JdbcPagingItemReader` back (keep `CHUNK_SIZE = 1` for a moment) and run the test again.

<details>
<summary>What you'll see, and why</summary>

With the JPA reader:

```
Expected size: 4 but was: 2
```

**Two of the four loans were never billed, and the step still reported `COMPLETED`.** The reader asks for page 0 (`OFFSET 0`, one row) and gets loan 1, which is billed and committed, so it drops out of the query. Page 1 is `OFFSET 1` of what's **left** (loans 2, 3, 4), which skips loan 2 and returns loan 3. Page 2 is `OFFSET 2` of loans 2 and 4, which is past the end. Loans 2 and 4 are silently skipped.

With the `JdbcPagingItemReader`, all four are billed: each page asks for "loan ids greater than the last one I read", which doesn't care that earlier rows have disappeared.

With the original `CHUNK_SIZE` of 100, both readers happen to work in this test, because all four ids are read into one page before anything is committed. That's what makes the bug dangerous: it hides in testing and appears in production, when the batch is bigger than one page. **When a step changes the rows its own reader is selecting, use keyset paging (or a cursor), never offsets.**

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 43, Failures: 0, Errors: 0, Skipped: 0
```

The EOD tests are the slowest so far, because several of them run the job 28 times. Expect the suite to take noticeably longer.

New files since guide 02:

```
src/main/java/com/example/loans/
├── eod/
│   ├── EndOfDayController.java
│   ├── EndOfDayJobConfig.java
│   ├── EndOfDayResult.java
│   └── EndOfDayService.java
├── interest/
│   ├── AccrualService.java
│   ├── BillingService.java
│   ├── InterestAccrual.java
│   └── InterestAccrualRepository.java
└── loan/
    └── InstalmentRepository.java
src/main/resources/db/migration/
└── V3__interest_accrual_and_billing.sql
src/test/java/com/example/loans/
├── CleanDatabase.java
└── eod/
    ├── EndOfDayApiTest.java
    ├── EndOfDayIdempotencyTest.java
    ├── EndOfDayJobTest.java
    └── EndOfDayRestartTest.java
```

Modified: `pom.xml`, `application.properties`, `Instalment.java`, `Loan.java`, `LoanService.java`, `LoanSummary.java`, `ApiExceptionHandler.java`, `TestClockConfig.java`, `requests.http`.

---

## Common errors

**`Table "BATCH_JOB_INSTANCE" not found`**
Spring Batch's tables don't exist. Boot only creates them automatically for in-memory databases. Add `spring.batch.jdbc.initialize-schema=always` (or create them in a migration).

**`InvalidJobParametersException: The JobParameters do not contain required keys: [businessDate]` at start-up**
Boot runs a single `Job` bean when the application starts. Add `spring.batch.job.enabled=false`.

**`JobInstanceAlreadyCompleteException: A job instance already exists and is complete for identifying parameters={JobParameter{name='businessDate', value=2030-01-01, …}}.  If you want to run this job again, change the parameters.`**
Working as intended: that business date has been processed. In a test, call `cleanDatabase.reset()` in `@BeforeEach`, which clears Spring Batch's history. Don't "change the parameters" to get round it in production code. That's how double processing happens.

**`unreported exception java.lang.Exception; must be caught or declared to be thrown`**
`JdbcPagingItemReaderBuilder.build()` declares `throws Exception`. Add `throws Exception` to the `@Bean` method.

**`DataIntegrityViolationException` … `Unique index or primary key violation: "PUBLIC.UK_INTEREST_ACCRUAL_LOAN_DATE …"`**
A run tried to accrue a loan for a date that was already accrued. The constraint protected the data, but the job failed. Check `AccrualService.calculate` has the `existsByLoanIdAndAccrualDate` check.

**`FatalStepExecutionException: Unable to process chunk`**
A wrapper. The real exception is its cause: look further down the stack trace, or use `rootCause()` in AssertJ.

**`IllegalStateException: Loan 1: expected 28 days of accruals from 2026-01-31 to 2026-02-28, found 27`**
EOD didn't run for at least one day in that period. Run the missing dates, then restart the failed execution with `jobOperator.restart(...)`. In a test, check your loop covers every day: `day.isBefore(end)` stops one day earlier than `!day.isAfter(end)`.

**Some loans are never billed, but the step says `COMPLETED`**
An offset-paging reader over rows that the step itself changes (Exercise 3). Use `JdbcPagingItemReader` with a sort key. Also make sure the sort key is **unambiguous**: when this guide was written, a version of the billing reader that joined `loan_instalment` to `loan` (both have an `id` column) with the sort key `id` billed only one of four loans and still reported success. The generated "next page" condition is a bare `id > ?`. Select from one table, or sort by a column name that exists only once.

**A WARN that `ItemProcessor is an interface` (or `ItemWriter`)**
Harmless here. See step 7.

---

## Recap

- **End of day** runs the work that applies to every account once per day: accrual, charging, arrears, reporting. It must finish in its window and be **restartable**.
- Interest is **accrued daily** (at high precision, on the value-dated balance) and **charged** when an instalment is billed. Because the schedule and the accrual share one interest function, on-time loans are billed exactly the scheduled interest.
- **Spring Batch:** a `Job` of `Step`s. **Chunk-oriented** steps read, process and write items, committing per chunk. The `JobRepository` records every run. A `JobInstance` is the job plus its **identifying parameters**.
- **Boot 4.1 + Spring Batch 6:** use `spring-boot-starter-batch-jdbc`, initialise the schema yourself for non-embedded databases, set `spring.batch.job.enabled=false`, use `chunk(n).transactionManager(tm)` and `JobOperator`.
- **Key the job by business date** so a completed day can't be re-run, and **make the domain idempotent anyway**: check before writing, and back it with a unique constraint.
- A **failed** job instance can be **restarted**. Completed steps are skipped, and committed chunks stay committed.
- **Use keyset paging** (`JdbcPagingItemReader` with an unambiguous sort key) when a step changes the rows it reads.

### Interview angle

<details>
<summary>"How would you design a nightly interest accrual job?"</summary>

A batch job keyed by business date, reading active accounts in chunks. For each account, calculate one day's interest on the value-dated balance at high precision and store it (with the balance used) in an accrual table with a unique key on account and date. Make it idempotent (skip dates already accrued, plus the constraint), restartable (chunk commits, a persisted job repository, readers that save their position, completed steps skipped on restart), and observable (read, filter and write counts per step). Charge interest to the customer periodically from the sum of accruals, rounding once. Mention the batch window, and that real banks also post accruals to the general ledger daily.

</details>

<details>
<summary>"What happens if your batch job fails halfway through?"</summary>

Each chunk is its own transaction, so everything committed before the failing chunk stays, and the failing chunk rolls back. The job execution is marked `FAILED` in the job repository. After fixing the cause, you restart the same job instance: Spring Batch skips completed steps, and a restartable reader resumes from its last committed position. The processing must also be idempotent, so that redoing part of a chunk can never double-apply anything. That second property is what you rely on when the framework's bookkeeping isn't enough.

</details>

<details>
<summary>"Spring Batch already refuses to run a completed job twice. Why do you need idempotency as well?"</summary>

Because job identity is only as good as the parameters. A `RunIdIncrementer`, an extra parameter or a manual re-run creates a new job instance for the same business date, and the framework will happily run it. Two schedulers firing, or a restart that reprocesses a chunk, have the same effect. The domain has to make "accrue loan X for date D" safe to repeat: check for an existing accrual, and enforce uniqueness in the database as a backstop.

</details>

<details>
<summary>"What's wrong with OFFSET pagination in a batch job?"</summary>

If the job changes rows so that they leave the query's result set (marking them processed, billed, closed), each new page's offset is applied to a shrinking set, and rows are skipped silently. Use keyset ("seek") pagination: order by a unique key and ask for rows after the last key seen, or use a cursor, or select by a criterion the processing doesn't change. Keyset pagination is also faster on large tables, because it doesn't scan past the skipped rows.

</details>

### Further reading

- [Spring Batch reference documentation](https://docs.spring.io/spring-batch/reference/): start with "The Domain Language of Batch" and "Chunk-oriented Processing"
- [Spring Batch 6.0 migration guide](https://github.com/spring-projects/spring-batch/wiki/Spring-Batch-6.0-Migration-Guide): the package moves and API changes, if you meet older code
- [Spring Boot reference: Spring Batch](https://docs.spring.io/spring-boot/reference/io/spring-batch.html): running jobs on start-up, `spring.batch.job.enabled`, and when auto-configuration backs off
- [Spring Boot how-to: batch applications](https://docs.spring.io/spring-boot/how-to/batch.html): custom batch data sources, command-line parameters, restarting failed jobs
- [Use The Index, Luke: paging through results](https://use-the-index-luke.com/no-offset): why keyset paging beats `OFFSET`

**Simplification to notice:** this EOD runs every calendar day and processes loans one by one in a single thread. Banks run EOD on **business days** from a holiday calendar (accruing weekend days in a catch-up), and parallelise it with **partitioned** steps: the loan book is split into ranges and each range is processed by its own worker. Spring Batch supports both.

**Next:** [04: Repayments, arrears and concurrency](04-repayments-arrears-and-concurrency.md). Customers start paying (and not paying), and a repayment arrives at the same moment as the nightly batch.
