# 04: Breaks, Suspense and Maker-Checker

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- What happens to **breaks**: the items the matching engine couldn't explain
- **Ageing**: why a one-day-old break and a forty-day-old break are different problems, and how an **exception report** shows them
- What a **suspense account** is for, why banks park unexplained money there, and why operations teams work to keep it near zero
- **Maker-checker** (the "four-eyes" principle): one person proposes a fix, a *different* person approves it
- A pure, unit-tested report builder, and a `Clock` bean so "today" is never hard-coded
- A real race: two checkers approving the same write-off at the same moment, posting to suspense twice. You'll watch it fail, then fix it with a **conditional update** (compare-and-set in SQL)

By the end, operations staff can see every break by age, and resolve them by matching items by hand or writing them off to suspense, always with a second person's approval, and never twice.

---

## Concepts

### How it works in a real bank

After the automatic passes, every reconciliation leaves some items unmatched. Each one is a **break** (also called an *exception* or *open item*), and it lands in a work queue for the operations team. For each break, someone has to find out what happened and fix it:

- **It's a timing difference.** Leave it; tomorrow's run will match it.
- **It's the same money under a different description.** A customer paid £120 quoting their name instead of the invoice number. A person **matches it manually**.
- **Our books are missing something.** The correspondent charged a £25 fee nobody posted. The fix is an accounting entry, followed by a match.
- **Nobody can tell yet.** £75 arrived from "J SMITH" with no reference. The bank has the money (it's on the statement), so its books must show it too, but it can't yet say whose it is.

That last case is what a [suspense account](../glossary.md#suspense-account) is for.

### Ageing and the exception report

The older a break gets, the less likely it is to be a timing difference and the more likely it is to be a real problem: lost money, a duplicated payment, even fraud. So breaks are **aged** (how many days since the item's date) and grouped into **age buckets**. Reconciliation teams report on breaks by age and escalate as they get older, to their own managers, to finance, and in the end to the bank's risk and audit functions. Unresolved, ageing breaks are a classic **operational risk** indicator.

The **exception report** you'll build lists every open item with its age and bucket, and totals each bucket. Its totals are **gross**: a missing £100 credit and a missing £100 debit are two problems worth £200, not zero. A **net** total would let errors cancel each other out and look like nothing was wrong.

> **Simplification:** the age buckets here (0-1, 2-5, 6-30, over 30 days) are illustrative. Banks set their own, per account type, and usually count **business days**, skipping weekends and bank holidays. This tutorial counts calendar days.

### Suspense: parking money you can't yet explain

When money arrives that the bank can't identify, it can't simply ignore it: the statement shows the money, so the ledger must too. Nor can it guess whose it is. So it books the receipt to the nostro (matching the statement) and the other side to a **suspense account**, an [internal account](../glossary.md#internal-account) for "money we hold but can't yet place":

```
DEBIT   NOSTRO_GBP   £75.00   (our asset at the other bank went up: matches the statement)
CREDIT  SUSPENSE     £75.00   (we owe this to someone; we don't know who yet)
```

The break on the nostro is now resolved, because both records agree. But the **problem has only moved**: the £75 now sits in suspense until someone identifies the customer and moves it on to them. That's why suspense is watched closely. A suspense balance that grows, or items that sit there for weeks, mean unexplained money is building up. Operations teams work to keep suspense **near zero**, and auditors look at what's in it and how long it's been there.

> **In this tutorial**, writing off to suspense is the end of the story. Clearing suspense (moving the £75 to the right customer once identified) would be another maker-checker action posting `DEBIT SUSPENSE / CREDIT` the customer's account. It's left as a stretch exercise.

### Maker-checker: four eyes on every fix

Manual matches and suspense postings change the bank's books by hand, outside every automated control. A careless or dishonest person could use a "manual match" to hide a missing payment, or a suspense posting to move money. So banks require **maker-checker** (also called **four eyes** or *dual control*):

1. A **maker** proposes the change, with a reason. Nothing happens to the books yet.
2. A **checker**, who must be a **different person**, reviews it and approves or rejects it. Only an approval changes anything.

This is an example of **segregation of duties**: no single person can both initiate and authorise a sensitive change. Real systems add more: checkers need a specific role or permission, larger amounts need more senior approvers, and every decision is part of the [audit trail](../glossary.md#audit-trail).

> **Simplification:** who's calling is taken from an `X-User` request header. Anyone could send any name, so this is **not** security: it's a stand-in so you can see the rules working. In a real system, the user comes from authentication (for example the subject of an OAuth 2 access token, which the Open Banking API tutorial covers), never from a header the client chooses.

### Compare-and-set, in SQL

Approval has a classic race. Two checkers open the same pending write-off and click **Approve** within milliseconds. Each transaction reads the resolution, sees `PENDING`, and goes ahead. Each posts £75 to suspense. The bank's books now say £150 arrived when £75 did.

The fix is to make "is it still pending? then it's mine" a **single atomic step** in the database:

```sql
UPDATE resolution SET status = 'APPROVED', decided_by = 'bob', decided_at = …
WHERE id = 7 AND status = 'PENDING'
```

The database runs each `UPDATE` atomically. If two arrive together, the second waits for the first to commit, then re-checks its `WHERE`, finds the status is no longer `PENDING`, and updates **zero rows**. The code reads the row count: 1 means "you won, carry on", 0 means "someone else already decided". This is **compare-and-set**, and the conditional `UPDATE` is one of the most useful concurrency tools there is: no explicit locks, no version column, and it works the same way on H2 and PostgreSQL.

---

## Steps

### 1. A clock

The break report needs "today", which must be testable and must mean **today in London**, not in whatever time zone the server happens to run. Create `config/ClockConfig.java`:

```java
package com.example.recon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ClockConfig {

    /** "Today" means today in London, where this bank's business day runs. */
    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Europe/London"));
    }
}
```

Code that needs the date calls `LocalDate.now(clock)`, never `LocalDate.now()`.

### 2. Age buckets and the report

Create the package `com.example.recon.breaks`. `breaks/AgeBucket.java`:

```java
package com.example.recon.breaks;

/**
 * How old a break is, in calendar days. The boundaries are illustrative:
 * each bank sets its own, often per account, and escalates as breaks move right.
 */
public enum AgeBucket {
    DAYS_0_1("0-1 days"),
    DAYS_2_5("2-5 days"),
    DAYS_6_30("6-30 days"),
    OVER_30("over 30 days");

    private final String label;

    AgeBucket(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static AgeBucket of(long ageInDays) {
        if (ageInDays < 0) {
            throw new IllegalArgumentException("A break can't be dated in the future: " + ageInDays + " days");
        }
        if (ageInDays <= 1) return DAYS_0_1;
        if (ageInDays <= 5) return DAYS_2_5;
        if (ageInDays <= 30) return DAYS_6_30;
        return OVER_30;
    }
}
```

`breaks/BreakReport.java` is a record with a static factory that does all the work. Like the matching engine, it's **pure**: open items and a date in, report out:

```java
package com.example.recon.breaks;

import com.example.recon.item.ReconItem;
import com.example.recon.item.Side;
import com.example.recon.money.Money;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** The exception report: every open item, how old it is, and totals by age. */
public record BreakReport(String accountCode, LocalDate asOf, int totalCount, Money grossAmount,
                          List<BucketTotal> buckets, List<Break> breaks) {

    public record BucketTotal(String bucket, int count, Money grossAmount) {
    }

    public record Break(Long itemId, Side side, String reference, Money amount, LocalDate bookingDate,
                        long ageDays, String bucket, String description) {
    }

    /**
     * Pure: open items in, report out. Gross means "ignore the sign": a missing £100 credit
     * and a missing £100 debit are two problems, not zero.
     */
    public static BreakReport of(String accountCode, LocalDate asOf, List<ReconItem> openItems) {
        List<Break> breaks = openItems.stream()
                .map(i -> {
                    long age = ChronoUnit.DAYS.between(i.getBookingDate(), asOf);
                    return new Break(i.getId(), i.getSide(), i.getReference(), i.getAmount(), i.getBookingDate(),
                            age, AgeBucket.of(age).label(), i.getDescription());
                })
                .sorted(Comparator.comparingLong(Break::ageDays).reversed().thenComparing(Break::reference))
                .toList();
        List<BucketTotal> buckets = Arrays.stream(AgeBucket.values())
                .map(bucket -> {
                    List<Break> in = breaks.stream().filter(b -> b.bucket().equals(bucket.label())).toList();
                    return new BucketTotal(bucket.label(), in.size(), gross(in));
                })
                .toList();
        return new BreakReport(accountCode, asOf, breaks.size(), gross(breaks), buckets, breaks);
    }

    private static Money gross(List<Break> breaks) {
        return breaks.stream().map(b -> b.amount().abs()).reduce(Money.zero(Money.GBP), Money::plus);
    }
}
```

- **Records nested in a record** give the JSON its shape with no extra classes.
- **Oldest first.** The report is a work queue, and the oldest breaks are the most urgent.
- **Every bucket appears**, even empty ones, so a report always has the same shape and an empty "over 30 days" bucket is visibly empty, not missing.

Test it, `src/test/java/com/example/recon/breaks/BreakReportTest.java`:

```java
package com.example.recon.breaks;

import com.example.recon.item.ReconItem;
import com.example.recon.money.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BreakReportTest {

    static final LocalDate AS_OF = LocalDate.of(2026, 11, 6);

    @Test
    void bucketsBreaksByAge() {
        assertThat(AgeBucket.of(0)).isEqualTo(AgeBucket.DAYS_0_1);
        assertThat(AgeBucket.of(1)).isEqualTo(AgeBucket.DAYS_0_1);
        assertThat(AgeBucket.of(2)).isEqualTo(AgeBucket.DAYS_2_5);
        assertThat(AgeBucket.of(30)).isEqualTo(AgeBucket.DAYS_6_30);
        assertThat(AgeBucket.of(31)).isEqualTo(AgeBucket.OVER_30);
        assertThatThrownBy(() -> AgeBucket.of(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void totalsAreGrossAndOldestBreaksComeFirst() {
        BreakReport report = BreakReport.of("NOSTRO_GBP", AS_OF, List.of(
                item("NEW-CREDIT", "100.00", AS_OF),
                item("NEW-DEBIT", "-100.00", AS_OF),
                item("OLD", "-25.00", AS_OF.minusDays(40))));

        assertThat(report.totalCount()).isEqualTo(3);
        assertThat(report.grossAmount()).isEqualTo(Money.gbp("225.00")); // not 25.00
        assertThat(report.breaks().getFirst().reference()).isEqualTo("OLD");
        assertThat(report.breaks().getFirst().ageDays()).isEqualTo(40);
        assertThat(report.buckets()).containsExactly(
                new BreakReport.BucketTotal("0-1 days", 2, Money.gbp("200.00")),
                new BreakReport.BucketTotal("2-5 days", 0, Money.gbp("0.00")),
                new BreakReport.BucketTotal("6-30 days", 0, Money.gbp("0.00")),
                new BreakReport.BucketTotal("over 30 days", 1, Money.gbp("25.00")));
    }

    private static ReconItem item(String reference, String amount, LocalDate date) {
        return ReconItem.external("NOSTRO_GBP", null, reference, Money.gbp(amount), date, date, "test");
    }
}
```

The boundary values (0, 1, 2, 30, 31) are where off-by-one mistakes live, so test exactly those. The items are never saved, so their ids are `null`. That's fine here, but it's why the report sorts ties by reference and not by id: see Common errors.

### 3. The break report API

Add two queries to `ReconItemRepository` (import `Optional`):

```java
    @Query("""
            select i from ReconItem i
            where i.accountCode = :accountCode
              and i.status = com.example.recon.item.ItemStatus.OPEN and i.bookingDate <= :asOf
            """)
    List<ReconItem> findAllOpen(String accountCode, LocalDate asOf);

    Optional<ReconItem> findByPostingId(Long postingId);
```

`breaks/BreakService.java`:

```java
package com.example.recon.breaks;

import com.example.recon.account.AccountRepository;
import com.example.recon.account.UnknownAccountException;
import com.example.recon.item.ReconItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class BreakService {

    private final AccountRepository accounts;
    private final ReconItemRepository items;

    public BreakService(AccountRepository accounts, ReconItemRepository items) {
        this.accounts = accounts;
        this.items = items;
    }

    @Transactional(readOnly = true)
    public BreakReport report(String accountCode, LocalDate asOf) {
        if (accounts.findByCode(accountCode).isEmpty()) {
            throw new UnknownAccountException("No account " + accountCode);
        }
        return BreakReport.of(accountCode, asOf, items.findAllOpen(accountCode, asOf));
    }
}
```

`breaks/BreakController.java`:

```java
package com.example.recon.breaks;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;

@RestController
public class BreakController {

    private final BreakService breaks;
    private final Clock clock;

    public BreakController(BreakService breaks, Clock clock) {
        this.breaks = breaks;
        this.clock = clock;
    }

    @GetMapping("/breaks")
    public BreakReport breaks(@RequestParam String account,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return breaks.report(account, asOf != null ? asOf : LocalDate.now(clock));
    }
}
```

`asOf` defaults to today, and can be set to any date so the report can be re-created as it would have looked then. `@DateTimeFormat(iso = DATE)` accepts only `2026-11-06`.

`src/test/java/com/example/recon/breaks/BreakApiTest.java`:

```java
package com.example.recon.breaks;

import com.example.recon.ReconIntegrationTest;
import com.example.recon.TestAccounts;
import com.example.recon.account.AccountRepository;
import com.example.recon.item.ReconItem;
import com.example.recon.item.ReconItemRepository;
import com.example.recon.ledger.LedgerService;
import com.example.recon.money.Money;
import com.example.recon.run.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@ReconIntegrationTest
class BreakApiTest {

    @Autowired MockMvcTester mvc;
    @Autowired AccountRepository accounts;
    @Autowired ReconItemRepository items;
    @Autowired LedgerService ledger;
    @Autowired ReconciliationService reconciliation;

    @Test
    void reportsWhatTheRunCouldNotMatchByAge() {
        String nostro = TestAccounts.newNostro(accounts).getCode();
        LocalDate nov6 = LocalDate.of(2026, 11, 6);
        ledger.post(nostro, "CUSTOMER_FUNDS", Money.gbp("50.00"), "INV-1", nov6, "Matches");
        ledger.post("CUSTOMER_FUNDS", nostro, Money.gbp("300.00"), "PAY-7740", nov6, "Not on the statement yet");
        items.save(ReconItem.external(nostro, null, "INV-1", Money.gbp("50.00"), nov6, nov6, "Matches"));
        items.save(ReconItem.external(nostro, null, "CB-FEE", Money.gbp("-25.00"), nov6.minusDays(9), null, "Old fee"));
        reconciliation.run(nostro, nov6);

        MvcTestResult response = mvc.get().uri("/breaks?account={a}&asOf=2026-11-06", nostro).exchange();

        assertThat(response).hasStatus(HttpStatus.OK);
        assertThat(response).bodyJson().extractingPath("$.totalCount").isEqualTo(2);
        assertThat(response).bodyJson().extractingPath("$.grossAmount.amount").isEqualTo("325.00");
        assertThat(response).bodyJson().extractingPath("$.breaks[0].reference").isEqualTo("CB-FEE");
        assertThat(response).bodyJson().extractingPath("$.breaks[0].bucket").isEqualTo("6-30 days");
        assertThat(response).bodyJson().extractingPath("$.breaks[1].side").isEqualTo("INTERNAL");
        assertThat(response).bodyJson().extractingPath("$.buckets[0].count").isEqualTo(1);
    }
}
```

The test runs the whole pipeline (post, add statement items, reconcile) and then checks the report sees exactly the two items the run couldn't match.

### 4. Resolutions: the schema

Create `src/main/resources/db/migration/V4__resolutions.sql`:

```sql
-- A request to resolve breaks by hand. The maker asks; a different checker decides.
CREATE TABLE resolution (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    account_code VARCHAR(30)              NOT NULL,
    type         VARCHAR(20)              NOT NULL,
    status       VARCHAR(10)              NOT NULL,
    reason       VARCHAR(255)             NOT NULL,
    requested_by VARCHAR(50)              NOT NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_by   VARCHAR(50),
    decided_at   TIMESTAMP WITH TIME ZONE,
    match_id     BIGINT                   REFERENCES recon_match (id)
);

CREATE TABLE resolution_item (
    resolution_id BIGINT NOT NULL REFERENCES resolution (id),
    item_id       BIGINT NOT NULL REFERENCES recon_item (id),
    PRIMARY KEY (resolution_id, item_id)
);
```

A resolution records **who asked, why, who decided and when**. Nothing is ever deleted: a rejected resolution stays as a record that someone proposed it.

Add two rules to `MatchRule`, so manual matches explain themselves like automatic ones:

```java
    /** Matched by people, through maker-checker. */
    MANUAL,
    /** An unexplained external item, balanced by a posting to the suspense account. */
    SUSPENSE
```

### 5. The resolution entity

Create the package `com.example.recon.resolution`. Two enums first:

```java
package com.example.recon.resolution;

public enum ResolutionType {
    /** "These items explain each other": internal and external items that sum to the same amount. */
    MANUAL_MATCH,
    /** "We can't explain this yet": park one external item's money in suspense. */
    WRITE_OFF
}
```

```java
package com.example.recon.resolution;

public enum ResolutionStatus {
    PENDING,
    APPROVED,
    REJECTED
}
```

`resolution/Resolution.java`. This first version has a straightforward `approve` method: check, then set:

```java
package com.example.recon.resolution;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "resolution")
public class Resolution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_code", nullable = false, length = 30)
    private String accountCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResolutionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ResolutionStatus status;

    @Column(nullable = false)
    private String reason;

    @Column(name = "requested_by", nullable = false, length = 50)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "decided_by", length = 50)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "match_id")
    private Long matchId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "resolution_item", joinColumns = @JoinColumn(name = "resolution_id"))
    @Column(name = "item_id", nullable = false)
    private Set<Long> itemIds = new HashSet<>();

    protected Resolution() {
        // for JPA
    }

    public Resolution(String accountCode, ResolutionType type, Set<Long> itemIds, String reason, String requestedBy) {
        this.accountCode = accountCode;
        this.type = type;
        this.itemIds = new HashSet<>(itemIds);
        this.reason = reason;
        this.requestedBy = requestedBy;
        this.requestedAt = Instant.now();
        this.status = ResolutionStatus.PENDING;
    }

    /** Four eyes: whoever asked can never be the one who approves. */
    public void checkCanBeDecidedBy(String checker) {
        if (requestedBy.equalsIgnoreCase(checker)) {
            throw new MakerCheckerException(
                    "%s requested resolution %d, so someone else must decide it".formatted(checker, id));
        }
        if (status != ResolutionStatus.PENDING) {
            throw new ResolutionConflictException("Resolution %d is already %s".formatted(id, status));
        }
    }

    public void approve(String checker, Long matchId) {
        checkCanBeDecidedBy(checker);
        this.status = ResolutionStatus.APPROVED;
        this.decidedBy = checker;
        this.decidedAt = Instant.now();
        this.matchId = matchId;
    }

    public Set<Long> getItemIds() {
        return Set.copyOf(itemIds);
    }

    // + getters for id, accountCode, type, status, reason, requestedBy, decidedBy, decidedAt and matchId
}
```

- **`@ElementCollection`** maps a collection of simple values (here, item ids) to a table of its own, `resolution_item`, without making it an entity. `EAGER` is fine because a resolution only ever has a handful of items, and the service always needs them.
- **`equalsIgnoreCase`**: "Alice" can't approve "alice"'s request. User names are rarely case-sensitive, and a case trick shouldn't get round four eyes.

Create four exception classes in the same package, each a `RuntimeException` with a single `String message` constructor: `MakerCheckerException`, `ResolutionConflictException` and `InvalidResolutionException`, plus `ResolutionNotFoundException`, whose constructor takes a `Long id` and builds the message `"No resolution with id " + id`. And an empty `ResolutionRepository extends JpaRepository<Resolution, Long>`.

Map them in `ApiExceptionHandler` (with imports):

```java
    @ExceptionHandler(ResolutionNotFoundException.class)
    ProblemDetail handle(ResolutionNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Resolution not found", ex.getMessage());
    }

    @ExceptionHandler(InvalidResolutionException.class)
    ProblemDetail handle(InvalidResolutionException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid resolution", ex.getMessage());
    }

    @ExceptionHandler(ResolutionConflictException.class)
    ProblemDetail handle(ResolutionConflictException ex) {
        return problem(HttpStatus.CONFLICT, "Resolution conflict", ex.getMessage());
    }

    @ExceptionHandler(MakerCheckerException.class)
    ProblemDetail handle(MakerCheckerException ex) {
        return problem(HttpStatus.FORBIDDEN, "Maker-checker violation", ex.getMessage());
    }
```

Self-approval is **`403 Forbidden`**: the server understood the request and knows who's asking, and that person isn't allowed to do it. Approving something already decided is **`409 Conflict`** with the resolution's current state.

### 6. The resolution service

`resolution/ResolutionService.java`:

```java
package com.example.recon.resolution;

import com.example.recon.item.ItemStatus;
import com.example.recon.item.ReconItem;
import com.example.recon.item.ReconItemRepository;
import com.example.recon.item.Side;
import com.example.recon.ledger.JournalEntry;
import com.example.recon.ledger.LedgerService;
import com.example.recon.ledger.Posting;
import com.example.recon.matching.MatchRule;
import com.example.recon.money.Money;
import com.example.recon.run.ReconMatch;
import com.example.recon.run.ReconMatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Service
public class ResolutionService {

    static final String SUSPENSE = "SUSPENSE";

    private final ResolutionRepository resolutions;
    private final ReconItemRepository items;
    private final ReconMatchRepository matches;
    private final LedgerService ledger;
    private final Clock clock;

    public ResolutionService(ResolutionRepository resolutions, ReconItemRepository items,
                             ReconMatchRepository matches, LedgerService ledger, Clock clock) {
        this.resolutions = resolutions;
        this.items = items;
        this.matches = matches;
        this.ledger = ledger;
        this.clock = clock;
    }

    /** The maker's half: checks the request makes sense, and changes nothing else. */
    @Transactional
    public Resolution request(String maker, ResolutionType type, Set<Long> itemIds, String reason) {
        List<ReconItem> found = openItems(itemIds);
        String accountCode = found.getFirst().getAccountCode();
        if (found.stream().anyMatch(i -> !i.getAccountCode().equals(accountCode))) {
            throw new InvalidResolutionException("All items must belong to the same account");
        }
        switch (type) {
            case MANUAL_MATCH -> {
                Money internal = total(found, Side.INTERNAL);
                Money external = total(found, Side.EXTERNAL);
                if (found.stream().map(ReconItem::getSide).distinct().count() < 2) {
                    throw new InvalidResolutionException("A manual match needs at least one item from each side");
                }
                if (!internal.equals(external)) {
                    throw new InvalidResolutionException(
                            "Items don't add up: internal %s, external %s".formatted(internal, external));
                }
            }
            case WRITE_OFF -> {
                if (found.size() != 1 || found.getFirst().getSide() != Side.EXTERNAL) {
                    throw new InvalidResolutionException("A write-off is for exactly one external item");
                }
            }
        }
        return resolutions.save(new Resolution(accountCode, type, itemIds, reason, maker));
    }

    /** The checker's half: only now does anything get matched or posted. */
    @Transactional
    public Resolution approve(Long id, String checker) {
        Resolution resolution = get(id);
        resolution.checkCanBeDecidedBy(checker);
        List<ReconItem> resolved = openItems(resolution.getItemIds());

        ReconMatch match = switch (resolution.getType()) {
            case MANUAL_MATCH -> matches.save(new ReconMatch(resolution.getAccountCode(), MatchRule.MANUAL,
                    Money.zero(Money.GBP), null, checker));
            case WRITE_OFF -> writeOffToSuspense(resolution, resolved.getFirst(), checker);
        };
        resolved.forEach(item -> item.matchTo(match.getId()));
        resolution.approve(checker, match.getId());
        return resolution;
    }

    @Transactional(readOnly = true)
    public Resolution get(Long id) {
        return resolutions.findById(id).orElseThrow(() -> new ResolutionNotFoundException(id));
    }

    /**
     * Post the unexplained money between the reconciled account and suspense, so our books
     * agree with the statement, then match the new posting to the statement item.
     */
    private ReconMatch writeOffToSuspense(Resolution resolution, ReconItem external, String checker) {
        String account = external.getAccountCode();
        Money amount = external.getAmount();
        String description = "Suspense: %s (resolution %d)".formatted(external.getReference(), resolution.getId());
        LocalDate today = LocalDate.now(clock);
        JournalEntry entry = amount.isPositive()
                ? ledger.post(account, SUSPENSE, amount, external.getReference(), today, description)
                : ledger.post(SUSPENSE, account, amount.negate(), external.getReference(), today, description);

        ReconMatch match = matches.save(new ReconMatch(account, MatchRule.SUSPENSE,
                Money.zero(Money.GBP), null, checker));
        Posting onAccount = entry.getPostings().stream()
                .filter(p -> p.getAccount().getCode().equals(account))
                .findFirst().orElseThrow();
        items.findByPostingId(onAccount.getId()).orElseThrow().matchTo(match.getId());
        return match;
    }

    private List<ReconItem> openItems(Set<Long> ids) {
        List<ReconItem> found = items.findAllById(ids);
        if (found.size() != ids.size()) {
            throw new InvalidResolutionException("Unknown item ids in " + ids);
        }
        for (ReconItem item : found) {
            if (item.getStatus() != ItemStatus.OPEN) {
                throw new ResolutionConflictException("Item %d is already matched".formatted(item.getId()));
            }
        }
        return found;
    }

    private static Money total(List<ReconItem> items, Side side) {
        return items.stream().filter(i -> i.getSide() == side).map(ReconItem::getAmount)
                .reduce(Money.zero(Money.GBP), Money::plus);
    }
}
```

Follow the money in `writeOffToSuspense`:

- **A positive external item** (money arrived): `DEBIT` the nostro, `CREDIT` suspense, exactly the entry in Concepts. **A negative one** (money left, unexplained): the reverse.
- **The ledger feeds reconciliation as usual**, so the nostro posting creates a new `INTERNAL` item. The service finds it by posting id and matches it with the statement item under the `SUSPENSE` rule. The suspense posting creates no item, because `SUSPENSE` isn't a reconciled account.
- **The entry is booked today**, not on the statement's date. The correction is a new event, made today; history isn't rewritten.
- **`request` changes nothing but the resolution.** Its checks (same account, both sides present, totals equal) mean a checker is never asked to approve something that couldn't work, and `approve` repeats the "still open?" check because things may have changed in between.

### 7. The controller

`resolution/ResolutionController.java`:

```java
package com.example.recon.resolution;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/resolutions")
public class ResolutionController {

    /** Who is calling. A stand-in for the authenticated user: see the guide. */
    static final String USER_HEADER = "X-User";

    private final ResolutionService resolutions;

    public ResolutionController(ResolutionService resolutions) {
        this.resolutions = resolutions;
    }

    @PostMapping
    public ResponseEntity<ResolutionResponse> request(@RequestHeader(USER_HEADER) String user,
                                                      @Valid @RequestBody ResolutionRequest request) {
        Resolution resolution = resolutions.request(user, request.type(), request.itemIds(), request.reason());
        return ResponseEntity.created(URI.create("/resolutions/" + resolution.getId()))
                .body(ResolutionResponse.from(resolution));
    }

    @GetMapping("/{id}")
    public ResolutionResponse get(@PathVariable Long id) {
        return ResolutionResponse.from(resolutions.get(id));
    }

    @PostMapping("/{id}/approval")
    public ResolutionResponse approve(@RequestHeader(USER_HEADER) String user, @PathVariable Long id) {
        return ResolutionResponse.from(resolutions.approve(id, user));
    }

    public record ResolutionRequest(
            @NotNull ResolutionType type,
            @NotEmpty Set<Long> itemIds,
            @NotBlank @Size(max = 255) String reason) {
    }

    public record ResolutionResponse(Long id, String accountCode, ResolutionType type, ResolutionStatus status,
                                     List<Long> itemIds, String reason, String requestedBy,
                                     String decidedBy, Instant decidedAt, Long matchId) {

        static ResolutionResponse from(Resolution r) {
            return new ResolutionResponse(r.getId(), r.getAccountCode(), r.getType(), r.getStatus(),
                    r.getItemIds().stream().sorted().toList(), r.getReason(), r.getRequestedBy(),
                    r.getDecidedBy(), r.getDecidedAt(), r.getMatchId());
        }
    }
}
```

- **Approval is a `POST` to `/resolutions/{id}/approval`**: you create an approval, which is a business event, rather than `PATCH`ing a status field. That keeps "who may change the status, and how" entirely in the server's hands.
- **`@RequestHeader(USER_HEADER)` has no `@NotBlank` or `@Size`.** Adding a constraint to a header or request parameter switches Spring MVC to *method validation*, and `@Valid @RequestBody` errors then arrive as `HandlerMethodValidationException` instead of `MethodArgumentNotValidException`, skipping your `errors` map. A missing header still gets a `400` without it.

### 8. Resolution tests

`src/test/java/com/example/recon/resolution/ResolutionApiTest.java`:

```java
package com.example.recon.resolution;

import com.example.recon.ReconIntegrationTest;
import com.example.recon.TestAccounts;
import com.example.recon.account.AccountRepository;
import com.example.recon.item.ItemStatus;
import com.example.recon.item.ReconItem;
import com.example.recon.item.ReconItemRepository;
import com.example.recon.ledger.LedgerService;
import com.example.recon.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@ReconIntegrationTest
class ResolutionApiTest {

    static final LocalDate NOV_6 = LocalDate.of(2026, 11, 6);

    @Autowired MockMvcTester mvc;
    @Autowired AccountRepository accounts;
    @Autowired ReconItemRepository items;
    @Autowired LedgerService ledger;

    String nostro;

    @BeforeEach
    void newAccount() {
        nostro = TestAccounts.newNostro(accounts).getCode();
    }

    @Test
    void anUnidentifiedCreditGoesToSuspenseOnceAChecker() {
        Long credit = external("CB-0042", "75.00");
        Money suspenseBefore = ledger.balance("SUSPENSE");

        MvcTestResult requested = request("alice", "WRITE_OFF", credit, "Unidentified credit, J SMITH");
        assertThat(requested).hasStatus(HttpStatus.CREATED);
        assertThat(requested).bodyJson().extractingPath("$.status").isEqualTo("PENDING");
        assertThat(ledger.balance("SUSPENSE")).isEqualTo(suspenseBefore); // nothing happens until approval

        MvcTestResult approved = approve("bob", idOf(requested));

        assertThat(approved).hasStatus(HttpStatus.OK);
        assertThat(approved).bodyJson().extractingPath("$.status").isEqualTo("APPROVED");
        assertThat(approved).bodyJson().extractingPath("$.decidedBy").isEqualTo("bob");
        assertThat(ledger.balance("SUSPENSE")).isEqualTo(suspenseBefore.plus(Money.gbp("75.00")));
        assertThat(ledger.balance(nostro)).isEqualTo(Money.gbp("75.00"));
        assertThat(items.findById(credit).orElseThrow().getStatus()).isEqualTo(ItemStatus.MATCHED);
    }

    @Test
    void theMakerCannotApproveTheirOwnRequest() {
        MvcTestResult requested = request("alice", "WRITE_OFF", external("CB-0043", "10.00"), "Unidentified");

        MvcTestResult response = approve("ALICE", idOf(requested));

        assertThat(response).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Maker-checker violation");
    }

    @Test
    void aResolutionCanOnlyBeApprovedOnce() {
        MvcTestResult requested = request("alice", "WRITE_OFF", external("CB-0044", "10.00"), "Unidentified");
        approve("bob", idOf(requested));

        MvcTestResult again = approve("carol", idOf(requested));

        assertThat(again).hasStatus(HttpStatus.CONFLICT);
        assertThat(again).bodyJson().extractingPath("$.detail").asString().endsWith("is already APPROVED");
    }

    @Test
    void peopleCanMatchItemsWhoseReferencesDoNotAgree() {
        Long statementLine = external("SMITH J", "120.00");
        ledger.post(nostro, "CUSTOMER_FUNDS", Money.gbp("120.00"), "INV-2001", NOV_6, "Invoice 2001");
        Long posting = items.findAllOpen(nostro, NOV_6).stream()
                .filter(i -> i.getReference().equals("INV-2001")).findFirst().orElseThrow().getId();

        MvcTestResult requested = mvc.post().uri("/resolutions").header("X-User", "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"type": "MANUAL_MATCH", "itemIds": [%d, %d], "reason": "Customer quoted their name"}
                        """.formatted(statementLine, posting))
                .exchange();
        MvcTestResult approved = approve("bob", idOf(requested));

        assertThat(approved).hasStatus(HttpStatus.OK);
        assertThat(items.findById(statementLine).orElseThrow().getMatchId())
                .isNotNull()
                .isEqualTo(items.findById(posting).orElseThrow().getMatchId());
    }

    @Test
    void aManualMatchMustAddUp() {
        Long a = external("X-1", "120.00");
        ledger.post(nostro, "CUSTOMER_FUNDS", Money.gbp("119.00"), "X-2", NOV_6, "Close but not equal");
        Long b = items.findAllOpen(nostro, NOV_6).stream()
                .filter(i -> i.getReference().equals("X-2")).findFirst().orElseThrow().getId();

        MvcTestResult response = mvc.post().uri("/resolutions").header("X-User", "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"type": "MANUAL_MATCH", "itemIds": [%d, %d], "reason": "Same thing"}
                        """.formatted(a, b))
                .exchange();

        assertThat(response).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response).bodyJson().extractingPath("$.detail")
                .isEqualTo("Items don't add up: internal GBP 119.00, external GBP 120.00");
    }

    @Test
    void everyRequestMustSayWhoIsAsking() {
        MvcTestResult response = mvc.post().uri("/resolutions/1/approval").exchange();

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.detail")
                .isEqualTo("Required header 'X-User' is not present.");
    }

    private Long external(String reference, String amount) {
        return items.save(ReconItem.external(nostro, null, reference, Money.gbp(amount), NOV_6, NOV_6, "Statement"))
                .getId();
    }

    private MvcTestResult request(String user, String type, Long itemId, String reason) {
        return mvc.post().uri("/resolutions").header("X-User", user)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"type": "%s", "itemIds": [%d], "reason": "%s"}
                        """.formatted(type, itemId, reason))
                .exchange();
    }

    private MvcTestResult approve(String user, Long id) {
        return mvc.post().uri("/resolutions/{id}/approval", id).header("X-User", user).exchange();
    }

    private static Long idOf(MvcTestResult created) {
        return Long.valueOf(created.getResponse().getHeader("Location").replace("/resolutions/", ""));
    }
}
```

`SUSPENSE` is one shared account, so the tests compare its balance **before and after** rather than against a fixed number. Run them: six green ticks.

### 9. Try it

Add to `requests.http`:

```http
### The exception report: what's still unexplained, by age
GET http://localhost:8080/breaks?account=NOSTRO_GBP&asOf=2026-11-06

### Alice (maker) asks to park the unidentified £75 credit in suspense
POST http://localhost:8080/resolutions
X-User: alice
Content-Type: application/json

{"type": "WRITE_OFF", "itemIds": [5], "reason": "Unidentified credit from J SMITH, no reference"}

### Alice tries to approve her own request (should be 403)
POST http://localhost:8080/resolutions/1/approval
X-User: alice

### Bob (checker) approves it
POST http://localhost:8080/resolutions/1/approval
X-User: bob

### The suspense account now holds the £75
GET http://localhost:8080/accounts/SUSPENSE
```

With curl, pass the user with `-H 'X-User: alice'`. Start from an empty `data/`, then import the statement, post the four ledger entries and run the reconciliation from guide 03. The break report (trimmed):

```json
{
  "accountCode": "NOSTRO_GBP",
  "asOf": "2026-11-06",
  "totalCount": 3,
  "grossAmount": { "amount": "400.00", "currency": "GBP" },
  "buckets": [
    { "bucket": "0-1 days", "count": 3, "grossAmount": { "amount": "400.00", "currency": "GBP" } },
    { "bucket": "2-5 days", "count": 0, "grossAmount": { "amount": "0.00", "currency": "GBP" } },
    { "bucket": "6-30 days", "count": 0, "grossAmount": { "amount": "0.00", "currency": "GBP" } },
    { "bucket": "over 30 days", "count": 0, "grossAmount": { "amount": "0.00", "currency": "GBP" } }
  ],
  "breaks": [
    { "itemId": 5, "side": "EXTERNAL", "reference": "CB-20261106-0042", "amount": { "amount": "75.00" }, "ageDays": 0, "bucket": "0-1 days", "description": "J SMITH" },
    { "itemId": 4, "side": "EXTERNAL", "reference": "CB-FEE-20261106", "amount": { "amount": "-25.00" }, "ageDays": 0, "bucket": "0-1 days", "description": "Account maintenance fee November" },
    { "itemId": 9, "side": "INTERNAL", "reference": "PAY-7740", "amount": { "amount": "-300.00" }, "ageDays": 0, "bucket": "0-1 days", "description": "Supplier payment 7740" }
  ]
}
```

Gross £400.00 = 75 + 25 + 300. Item 5 is the J SMITH credit. Alice's request comes back `PENDING`; her own approval is refused:

```json
{"detail":"alice requested resolution 1, so someone else must decide it","instance":"/resolutions/1/approval","status":403,"title":"Maker-checker violation"}
```

Bob's approval returns `"status":"APPROVED"`, `"decidedBy":"bob"` and a `matchId`. Suspense now holds the money, `{"accountCode":"SUSPENSE","balance":{"amount":"75.00","currency":"GBP"}}`, and the break report is down to two items worth £325.00. A second approval, by Carol, gets `409` with `"Resolution 1 is already APPROVED"`.

### 10. Two checkers, one click each

Sequential tests say approvals are safe. Here's what happens when they aren't sequential. `src/test/java/com/example/recon/resolution/ConcurrentApprovalTest.java`:

```java
package com.example.recon.resolution;

import com.example.recon.ReconIntegrationTest;
import com.example.recon.TestAccounts;
import com.example.recon.account.AccountRepository;
import com.example.recon.item.ReconItem;
import com.example.recon.item.ReconItemRepository;
import com.example.recon.ledger.LedgerService;
import com.example.recon.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Five checkers click "approve" on the same write-off at the same moment. */
@ReconIntegrationTest
class ConcurrentApprovalTest {

    @Autowired AccountRepository accounts;
    @Autowired ReconItemRepository items;
    @Autowired LedgerService ledger;
    @Autowired ResolutionService resolutions;

    @Test
    void aWriteOffIsPostedToSuspenseExactlyOnce() throws Exception {
        String nostro = TestAccounts.newNostro(accounts).getCode();
        LocalDate nov6 = LocalDate.of(2026, 11, 6);
        Long credit = items.save(ReconItem.external(nostro, null, "CB-0042", Money.gbp("75.00"),
                nov6, nov6, "J SMITH")).getId();
        Long resolutionId = resolutions.request("alice", ResolutionType.WRITE_OFF, Set.of(credit), "Unidentified")
                .getId();
        Money suspenseBefore = ledger.balance("SUSPENSE");

        List<String> checkers = List.of("bob", "carol", "dave", "erin", "frank");
        CountDownLatch startingGun = new CountDownLatch(1);
        AtomicInteger approved = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(checkers.size())) {
            List<Future<?>> futures = new ArrayList<>();
            for (String checker : checkers) {
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    try {
                        resolutions.approve(resolutionId, checker);
                        approved.incrementAndGet();
                    } catch (ResolutionConflictException e) {
                        conflicts.incrementAndGet();
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

        Money moved = ledger.balance("SUSPENSE").minus(suspenseBefore);
        System.out.printf("approved=%d conflicts=%d unexpected=%s suspense moved=%s nostro=%s%n",
                approved.get(), conflicts.get(),
                unexpected.stream().map(t -> t.getClass().getSimpleName()).toList(), moved, ledger.balance(nostro));

        assertThat(unexpected).isEmpty();
        assertThat(approved.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(checkers.size() - 1);
        assertThat(moved).isEqualTo(Money.gbp("75.00"));
        assertThat(ledger.balance(nostro)).isEqualTo(Money.gbp("75.00"));
    }
}
```

The same pattern as guide 02's duplicate-upload test: a `CountDownLatch` starting gun, no `@Transactional` on the test, and assertions on **the money**, not just on success counts. Run it:

```
approved=5 conflicts=0 unexpected=[] suspense moved=GBP 375.00 nostro=GBP 375.00
```

```
expected: 1
 but was: 5
```

Five approvals of one £75 write-off. The bank's books now say £375 arrived from J SMITH. The nostro no longer agrees with the statement, and suspense holds £300 that never existed. And every check in the code ran: each transaction read the resolution, saw `PENDING`, checked the item was `OPEN`, and carried on, because none of the others had committed yet. **Check-then-act inside a transaction is not atomic.** It's the same gap as guide 02's duplicate check, but this time there's no unique constraint to catch it.

### 11. Fix it: compare-and-set

Make the database decide who wins. Replace `ResolutionRepository`:

```java
package com.example.recon.resolution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

public interface ResolutionRepository extends JpaRepository<Resolution, Long> {

    /**
     * Compare-and-set in SQL: only flips PENDING to APPROVED. Returns 1 for the one
     * caller that wins, 0 for everyone else. The database decides, not the Java code.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Resolution r
            set r.status = com.example.recon.resolution.ResolutionStatus.APPROVED,
                r.decidedBy = :checker, r.decidedAt = :decidedAt
            where r.id = :id and r.status = com.example.recon.resolution.ResolutionStatus.PENDING
            """)
    int markApproved(Long id, String checker, Instant decidedAt);
}
```

- **`@Modifying`** tells Spring Data this query changes data, so it runs it with `executeUpdate()` and returns the **row count**.
- **`flushAutomatically = true`** writes any pending changes before the update runs.
- **`clearAutomatically = true`** empties the persistence context afterwards. A bulk update goes straight to the database, so any `Resolution` already loaded in this transaction is now stale: it still says `PENDING`. Clearing forces the next read to fetch the new state.

In `Resolution`, replace `approve` with a method that only records the outcome, and only after the status has already been set by `markApproved`:

```java
    /** Called after markApproved has won the race, to record what the approval produced. */
    public void resolvedBy(Long matchId) {
        if (status != ResolutionStatus.APPROVED) {
            throw new IllegalStateException("Resolution " + id + " has not been approved");
        }
        this.matchId = matchId;
    }
```

And change the start and end of `ResolutionService.approve` (import `java.time.Instant`):

```java
    @Transactional
    public Resolution approve(Long id, String checker) {
        get(id).checkCanBeDecidedBy(checker); // friendly errors: wrong person, already decided
        if (resolutions.markApproved(id, checker, Instant.now()) == 0) {
            throw new ResolutionConflictException("Resolution %d has already been decided".formatted(id));
        }
        Resolution resolution = get(id); // reload: markApproved cleared the persistence context
        List<ReconItem> resolved = openItems(resolution.getItemIds());

        ReconMatch match = switch (resolution.getType()) {
            case MANUAL_MATCH -> matches.save(new ReconMatch(resolution.getAccountCode(), MatchRule.MANUAL,
                    Money.zero(Money.GBP), null, checker));
            case WRITE_OFF -> writeOffToSuspense(resolution, resolved.getFirst(), checker);
        };
        resolved.forEach(item -> item.matchTo(match.getId()));
        resolution.resolvedBy(match.getId());
        return resolution;
    }
```

The order is the whole point: **claim first, then act**. The friendly checks still run first, so self-approval and the sequential "already approved" case keep their clear messages. Then `markApproved` claims the resolution. Only the transaction whose `UPDATE` changed a row goes on to post anything. The others' `UPDATE`s wait for the winner's row lock, re-check `status = 'PENDING'` once it commits, match nothing, and throw `ResolutionConflictException`. Run the test again:

```
approved=1 conflicts=4 unexpected=[] suspense moved=GBP 75.00 nostro=GBP 75.00
```

Green. Run it a few more times: concurrency tests must pass every time, not just usually.

> **Sidebar: why not `@Version`?** Optimistic locking (the Core ledger tutorial's first fix) would also work: four of the five transactions would fail at commit with `ObjectOptimisticLockingFailureException`. But it detects the conflict at the **end**, after each loser has already done all its work (posted to the ledger, created a match), and relies on rollback to undo it. The conditional update detects it at the **start**, before anything is posted. For an action whose side effects are money, deciding first is clearer. It also gives a domain-specific error ("already decided") instead of a generic locking failure.

---

## Your turn

### Exercise 1: rejecting a resolution

A checker must be able to say no. Add `POST /resolutions/{id}/rejection`, with the same maker-checker rule and the same compare-and-set protection (a `markRejected` query that only changes `PENDING` to `REJECTED`). Nothing is matched or posted. Test that a rejected write-off leaves the break open, and can't then be approved.

<details>
<summary>Solution</summary>

`ResolutionRepository`:

```java
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Resolution r
            set r.status = com.example.recon.resolution.ResolutionStatus.REJECTED,
                r.decidedBy = :checker, r.decidedAt = :decidedAt
            where r.id = :id and r.status = com.example.recon.resolution.ResolutionStatus.PENDING
            """)
    int markRejected(Long id, String checker, Instant decidedAt);
```

`ResolutionService`:

```java
    /** The checker says no. Nothing was matched or posted, so there's nothing to undo. */
    @Transactional
    public Resolution reject(Long id, String checker) {
        get(id).checkCanBeDecidedBy(checker);
        if (resolutions.markRejected(id, checker, Instant.now()) == 0) {
            throw new ResolutionConflictException("Resolution %d has already been decided".formatted(id));
        }
        return get(id);
    }
```

`ResolutionController`:

```java
    @PostMapping("/{id}/rejection")
    public ResolutionResponse reject(@RequestHeader(USER_HEADER) String user, @PathVariable Long id) {
        return ResolutionResponse.from(resolutions.reject(id, user));
    }
```

`ResolutionApiTest`:

```java
    @Test
    void aRejectedWriteOffLeavesTheBreakOpen() {
        Long credit = external("CB-0045", "10.00");
        MvcTestResult requested = request("alice", "WRITE_OFF", credit, "Unidentified");

        MvcTestResult rejected = mvc.post().uri("/resolutions/{id}/rejection", idOf(requested))
                .header("X-User", "bob").exchange();

        assertThat(rejected).hasStatus(HttpStatus.OK);
        assertThat(rejected).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(items.findById(credit).orElseThrow().getStatus()).isEqualTo(ItemStatus.OPEN);
        assertThat(approve("carol", idOf(requested))).hasStatus(HttpStatus.CONFLICT);
    }
```

Because approval and rejection both only change `PENDING`, an approval racing a rejection can't both succeed either: whichever `UPDATE` commits first wins, and the other matches zero rows.

</details>

### Exercise 2: money that left

The £25 fee on the statement is money that **left** the nostro without the ledger knowing. The right long-term fix is to post it to a fees expense account, but until the fee is agreed with the correspondent, a bank may park it in suspense too. Write a test that writes off a `-25.00` external item and checks that suspense goes **down** by £25 and the test nostro's balance becomes `-25.00`.

<details>
<summary>Solution</summary>

```java
    @Test
    void anUnexplainedDebitIsAlsoParkedInSuspense() {
        Money suspenseBefore = ledger.balance("SUSPENSE");
        MvcTestResult requested = request("alice", "WRITE_OFF", external("CB-FEE", "-25.00"), "Fee not yet agreed");

        approve("bob", idOf(requested));

        assertThat(ledger.balance("SUSPENSE")).isEqualTo(suspenseBefore.minus(Money.gbp("25.00")));
        assertThat(ledger.balance(nostro)).isEqualTo(Money.gbp("-25.00"));
    }
```

`writeOffToSuspense` posts `DEBIT SUSPENSE / CREDIT nostro` for a negative item. `SUSPENSE` is a liability, so a debit **reduces** it: its balance can go negative, meaning "money has gone that we haven't explained", which is just as much a problem as a positive balance. That's why suspense is watched in both directions, and why "near zero" is the target rather than "not negative".

</details>

---

## Checkpoint

```bash
./mvnw clean test
```

```
[INFO] Tests run: 53, Failures: 0, Errors: 0, Skipped: 0
```

(41 from guide 03, plus 2 in `BreakReportTest`, 1 in `BreakApiTest`, 8 in `ResolutionApiTest` and 1 in `ConcurrentApprovalTest`.) Run it two or three times.

New files since guide 03:

```
src/main/java/com/example/recon/
├── breaks/      AgeBucket, BreakController, BreakReport, BreakService
├── config/      ClockConfig
└── resolution/  InvalidResolutionException, MakerCheckerException, Resolution, ResolutionConflictException,
                 ResolutionController, ResolutionNotFoundException, ResolutionRepository, ResolutionService,
                 ResolutionStatus, ResolutionType
src/main/resources/db/migration/V4__resolutions.sql
src/test/java/com/example/recon/
├── breaks/      BreakApiTest, BreakReportTest
└── resolution/  ConcurrentApprovalTest, ResolutionApiTest
```

Modified: `MatchRule`, `ReconItemRepository`, `ApiExceptionHandler`, `requests.http`.

---

## Common errors

**`approved=5 conflicts=0 … suspense moved=GBP 375.00`**
The approval is still check-then-act. Make sure `markApproved` runs **before** anything is posted, and that its return value is checked.

**`InvalidDataAccessApiUsageException: Query executed via 'getResultList()' or 'getSingleResult()' must be a 'select' query [update Resolution r …]`**
The update query is missing `@Modifying`, so Spring Data tried to run it as a `SELECT`.

**`IllegalStateException: Resolution 2 has not been approved`**
`@Modifying` is there, but without `clearAutomatically = true`. The reload in `approve` returned the **stale** `Resolution` still cached in the persistence context, which says `PENDING`, although the database row says `APPROVED`.

**`NullPointerException: Cannot invoke "java.lang.Comparable.compareTo(Object)" because the return value of "java.util.function.Function.apply(Object)" is null`**
A comparator used `Break::itemId` on items that were never saved (as in `BreakReportTest`), so the ids are `null`. Sort ties by something that's always present, such as the reference, or use `Comparator.nullsLast(...)`.

**`400` with `"detail": "Required header 'X-User' is not present."`**
Every resolution call must say who's asking. In curl, add `-H 'X-User: alice'`.

**`400` with `"detail": "Failed to convert 'asOf' with value: '06/11/2026'"`**
Dates in query parameters must be ISO: `asOf=2026-11-06`.

**`409` with `"Item 9 is already matched"`**
Something matched the item between the request and the approval, perhaps a run. That's the re-check in `approve` working. Guide 05 makes runs and approvals take turns properly.

---

## Recap

- Unmatched items are **breaks**. Each is either a **timing difference** or a real problem, and **ageing** tells them apart over time. An **exception report** groups breaks into age buckets with **gross** totals.
- A **suspense account** holds money the bank has but can't yet place, so the books agree with the outside world while it's investigated. It must be watched and kept **near zero**, in both directions.
- **Maker-checker**: a maker proposes, a **different** checker approves, and only approval changes the books. Record who did what, when and why.
- Pure functions again: the **report builder** is tested without a database; a **`Clock` bean** makes "today" explicit.
- **Check-then-act is not atomic**, even inside a transaction. A **conditional `UPDATE … WHERE status = 'PENDING'`** with a row count is compare-and-set: exactly one caller wins, and the losers find out **before** they act.
- After a bulk update, **clear the persistence context** (or reload), or JPA will hand you stale entities.

### Interview angle

<details>
<summary>"What is a suspense account, and why do banks try to keep it at zero?"</summary>

An internal account that temporarily holds money the bank has received (or paid) but can't yet attribute: no reference, an unknown account, a payment that needs investigating. Posting to suspense keeps the books in agreement with the external record while the item is investigated. But the problem has only moved: a growing or ageing suspense balance means unexplained money is accumulating, which could hide errors, customer detriment or fraud. So items are aged, investigated and cleared to their proper account, and the balance is monitored by operations, finance and audit.

</details>

<details>
<summary>"What is maker-checker and how would you implement it?"</summary>

The four-eyes principle: one person (the maker) proposes a sensitive change, and a different person (the checker) approves or rejects it; only approval takes effect. Implement it as a request entity with a state machine (pending, approved, rejected), store the maker, checker, timestamps and reason, refuse approval by the maker (comparing authenticated identities, not client-supplied names), check the checker's permissions and approval limits, and make the approval itself atomic so it can't happen twice. Everything stays as an audit record; nothing is deleted.

</details>

<details>
<summary>"Two users approve the same request at the same moment. What happens in your system?"</summary>

The approval starts with a conditional update: `UPDATE … SET status = 'APPROVED' … WHERE id = ? AND status = 'PENDING'`. The database executes it atomically and locks the row; the second update waits, re-evaluates the condition after the first commits, and changes no rows. The service checks the row count, so the loser gets a conflict error and posts nothing. Mention that a plain read-then-write in a transaction would let both through under READ COMMITTED, and that `@Version` optimistic locking would also prevent the double effect but only after the losing transaction had done its work.

</details>

<details>
<summary>"Why report breaks gross rather than net?"</summary>

Because breaks in opposite directions don't cancel each other out in reality: a missing £100 receipt and a missing £100 payment are two separate problems, and a net figure of zero would hide both. Net figures can be useful for liquidity or balance views, but the exception report is a list of things to investigate, so its totals must count every one.

</details>

### Further reading

- [Spring Data JPA reference: Modifying queries](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.modifying-queries)
- [PostgreSQL docs: `UPDATE` under READ COMMITTED](https://www.postgresql.org/docs/current/transaction-iso.html#XACT-READ-COMMITTED): exactly how a waiting update re-checks its `WHERE` clause
- [BCBS: Revisions to the Principles for the Sound Management of Operational Risk](https://www.bis.org/bcbs/publ/d515.htm) (2021): the international framework operational controls like these sit within

**Next:** [05: Re-runnable and concurrent runs](05-re-runs-and-concurrent-runs.md). Making a reconciliation safe to run again, and safe to run twice at once.
