# 03: Manual Review and the Audit Trail

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- What happens to a **referred** application: the review queue, evidence, and the reviewer's decision
- The **four-eyes** (maker-checker) rule, and why a PEP needs a second, more senior approval
- Why a bank often **can't tell a declined applicant why**, and the offence of **tipping off**
- How to build an **audit trail**: who did what, when and why, in insert-only rows that commit in the **same transaction** as the change they describe (`Propagation.MANDATORY`)
- Hibernate's **`@Immutable`**
- A Spring MVC trap: a constraint on a `@RequestHeader` switches the whole method to **method validation**, and your field errors disappear
- What happens when **two reviewers decide the same application at the same moment**, seen in a failing concurrency test, and fixed with `SELECT … FOR UPDATE`

By the end, referred applications wait in a queue, reviewers approve or decline them with a reason, high-risk approvals need two different people, every step is in the audit trail, and ten reviewers clicking at once can't produce ten decisions.

---

## Concepts

### How it works in a real bank

Guide 02's checks send a share of applications to `REFERRED`. Those land in a **work queue** for an onboarding operations or financial crime team. A reviewer opens the case, reads why it was referred, and does whatever that reason calls for:

- **No electronic identity match:** ask the applicant for documents, check them, approve.
- **Possible sanctions match:** compare the applicant's date of birth, nationality and other details with the list entry. Almost always a false positive, discounted with a written reason. If it's a real match, the case goes to specialists: the bank must not proceed, and must freeze and report.
- **Possible PEP:** confirm whether they really are one, establish the **source of wealth** and **source of funds**, and get **senior management approval** ([regulation 35(5)](https://www.legislation.gov.uk/uksi/2017/692/regulation/35)) before the account can be opened.

Every decision needs a **reason**. Months or years later, an auditor, the FCA, or the bank's own Money Laundering Reporting Officer (MLRO) may ask: "Why did you open this account?" "We clicked approve" is not an answer.

**Four eyes.** For higher-risk decisions, banks require two different people: one proposes (the *maker*), another confirms (the *checker*). The control works only if the system **enforces** that the two are different people. A PEP approval needs a senior sign-off by law; you'll model that as "two different approvers" for any high-risk application.

### Why a declined applicant may not be told the reason

The applicant only ever sees a status. They don't see "possible sanctions match" or "fraud flag", and the API you built in guide 02 doesn't show check results to applicants. That's deliberate:

- **Tipping off.** If the bank has made a suspicious activity report, or an investigation is under way, disclosing that in a way likely to prejudice the investigation is a criminal offence for people working in the regulated sector ([Proceeds of Crime Act 2002, section 333A](https://www.legislation.gov.uk/ukpga/2002/29/section/333A)). An honest "we declined you because we've reported you" is exactly what the law forbids.
- **Fraud prevention.** Telling a fraudster which check caught them teaches them what to change next time.

Banks therefore usually send a short, generic decline message. The *reason* lives in the bank's internal records, where reviewers, auditors and regulators can see it.

### The audit trail

An [audit trail](../glossary.md#audit-trail) is a complete, unchangeable record of what happened. For onboarding it answers: **who** (a reviewer's id, or "system"), **what** (checks started, sanctions result, approved), **when**, and **why** (the reviewer's reason).

Three rules make an audit trail trustworthy:

1. **Insert only.** Events are never updated or deleted by the application. Hibernate's `@Immutable` stops the entity being updated through JPA.
2. **Same transaction as the change.** If the status change commits but its audit row doesn't (or the other way round), the trail is lying. You'll make it impossible to write an audit event outside the transaction that makes the change, with `@Transactional(propagation = Propagation.MANDATORY)`.
3. **No personal data in the free text.** The trail is kept for years and copied into reports. "Approved: passport seen in branch" is fine. "Approved: Alice Smith's passport 123456789" is not, and guide 05 would have to scrub it.

### Two reviewers, one application

Reviewers work from a shared queue. Two of them can open the same case. One approves it while, at the same moment, the other declines it. With the code you'd naturally write (load the application, check it's under review, change the status, save), both transactions read `REFERRED`, both pass the check, and both commit. The final status is whichever committed last, and the audit trail records two contradictory decisions.

This is the **lost update** problem. The fix is the one banks use for account balances: make the second reviewer **wait** until the first has finished, then re-check. `SELECT … FOR UPDATE` does exactly that.

---

## Steps

### 1. A status for "waiting for the second approver"

In `ApplicationStatus`, add `AWAITING_SECOND_APPROVAL` after `REFERRED`, give it a rule, and add a helper:

```java
public enum ApplicationStatus {
    RECEIVED,
    CHECKS_IN_PROGRESS,
    REFERRED,
    AWAITING_SECOND_APPROVAL,
    APPROVED,
    DECLINED,
    WITHDRAWN;

    public Set<ApplicationStatus> allowedNext() {
        return switch (this) {
            case RECEIVED -> Set.of(CHECKS_IN_PROGRESS, WITHDRAWN);
            case CHECKS_IN_PROGRESS -> Set.of(APPROVED, REFERRED, DECLINED);
            case REFERRED -> Set.of(APPROVED, DECLINED, AWAITING_SECOND_APPROVAL);
            case AWAITING_SECOND_APPROVAL -> Set.of(APPROVED, DECLINED);
            case APPROVED, DECLINED, WITHDRAWN -> Set.of();
        };
    }

    public boolean canMoveTo(ApplicationStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isUnderReview() {
        return this == REFERRED || this == AWAITING_SECOND_APPROVAL;
    }

    public boolean isFinal() {
        return allowedNext().isEmpty();
    }
}
```

Try adding the constant *without* its `case` first: the switch expression stops compiling, because it no longer covers every constant. That's the compiler enforcing the workflow.

Because the column is stored as `STRING`, inserting a constant in the middle of the enum changes nothing in the database.

### 2. Reviewing an application

The schema for this guide: a column for the first approver, and the audit table you'll use in step 4. Create `src/main/resources/db/migration/V3__review_and_audit.sql`:

```sql
CREATE TABLE audit_event (
    id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    application_id UUID                     NOT NULL REFERENCES application (id),
    event_type     VARCHAR(40)              NOT NULL,
    actor          VARCHAR(100)             NOT NULL,
    detail         VARCHAR(500)             NOT NULL,
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX ix_audit_event_application ON audit_event (application_id);

ALTER TABLE application ADD COLUMN first_approver VARCHAR(100);
```

`application/ReviewDecision.java`:

```java
package com.example.onboarding.application;

public enum ReviewDecision {
    APPROVE,
    DECLINE
}
```

`application/NotUnderReviewException.java`:

```java
package com.example.onboarding.application;

import java.util.UUID;

public class NotUnderReviewException extends RuntimeException {

    public NotUnderReviewException(UUID id, ApplicationStatus status) {
        super("Application %s is %s, not under review".formatted(id, status));
    }
}
```

`application/FourEyesException.java`:

```java
package com.example.onboarding.application;

import java.util.UUID;

public class FourEyesException extends RuntimeException {

    public FourEyesException(UUID id, String reviewer) {
        super("%s already approved application %s; the second approval must come from someone else"
                .formatted(reviewer, id));
    }
}
```

In `Application.java`, add the column:

```java
    @Column(name = "first_approver", length = 100)
    private String firstApprover;
```

and the review logic:

```java
    /**
     * A reviewer's decision on a referred application. High-risk applications
     * need two approvals from two different people (the "four-eyes" rule).
     */
    public void review(String reviewer, ReviewDecision decision, Instant now) {
        if (!status.isUnderReview()) {
            throw new NotUnderReviewException(id, status);
        }
        if (decision == ReviewDecision.DECLINE) {
            moveTo(ApplicationStatus.DECLINED, now);
            return;
        }
        if (riskRating != RiskRating.HIGH) {
            moveTo(ApplicationStatus.APPROVED, now);
        } else if (status == ApplicationStatus.REFERRED) {
            moveTo(ApplicationStatus.AWAITING_SECOND_APPROVAL, now);
            firstApprover = reviewer;
        } else if (reviewer.equals(firstApprover)) {
            throw new FourEyesException(id, reviewer);
        } else {
            moveTo(ApplicationStatus.APPROVED, now);
        }
    }

    public String getFirstApprover() {
        return firstApprover;
    }
```

- **The `isUnderReview` guard comes first.** `CHECKS_IN_PROGRESS → APPROVED` is a legal transition (for the automatic decision), so `moveTo` alone wouldn't stop a reviewer approving an application whose checks haven't finished.
- **Anyone can decline**, even half-way through a four-eyes approval. Stopping is always the safe direction, so it doesn't need a second pair of eyes.
- **The rule lives in the entity**, so every route to a review decision enforces it.

> **Simplification:** a real bank would check that the second approver has the right *role* (for a PEP, someone with senior management authority), not just that they're a different person. That needs authentication and roles, which this tutorial leaves out.

### 3. Unit-test the review rules

`src/test/java/com/example/onboarding/application/ApplicationReviewTest.java`:

```java
package com.example.onboarding.application;

import com.example.onboarding.risk.RiskRating;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.example.onboarding.TestApplicants.alice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationReviewTest {

    static final Instant NOW = Instant.parse("2026-11-07T09:00:00Z");

    static Application referred(RiskRating risk) {
        Application application = Application.receive(alice(), NOW);
        application.moveTo(ApplicationStatus.CHECKS_IN_PROGRESS, NOW);
        application.decide(risk, ApplicationStatus.REFERRED, NOW);
        return application;
    }

    @Test
    void oneApprovalIsEnoughForAStandardRiskReferral() {
        Application application = referred(RiskRating.STANDARD);

        application.review("rita", ReviewDecision.APPROVE, NOW);

        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
    }

    @Test
    void aHighRiskApprovalWaitsForASecondPerson() {
        Application application = referred(RiskRating.HIGH);

        application.review("rita", ReviewDecision.APPROVE, NOW);
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.AWAITING_SECOND_APPROVAL);
        assertThat(application.getFirstApprover()).isEqualTo("rita");

        application.review("sam", ReviewDecision.APPROVE, NOW);
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
    }

    @Test
    void theFirstApproverCannotAlsoBeTheSecond() {
        Application application = referred(RiskRating.HIGH);
        application.review("rita", ReviewDecision.APPROVE, NOW);

        assertThatThrownBy(() -> application.review("rita", ReviewDecision.APPROVE, NOW))
                .isInstanceOf(FourEyesException.class);
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.AWAITING_SECOND_APPROVAL);
    }

    @Test
    void anyOneReviewerCanDecline() {
        Application application = referred(RiskRating.HIGH);
        application.review("rita", ReviewDecision.APPROVE, NOW);

        application.review("rita", ReviewDecision.DECLINE, NOW);

        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.DECLINED);
    }

    @Test
    void anApplicationThatWasNeverReferredCannotBeReviewed() {
        Application application = Application.receive(alice(), NOW);
        application.moveTo(ApplicationStatus.CHECKS_IN_PROGRESS, NOW);

        assertThatThrownBy(() -> application.review("rita", ReviewDecision.APPROVE, NOW))
                .isInstanceOf(NotUnderReviewException.class)
                .hasMessageEndingWith("is CHECKS_IN_PROGRESS, not under review");
    }
}
```

### 4. The audit trail

Create the package `com.example.onboarding.audit`. `audit/AuditEventType.java`:

```java
package com.example.onboarding.audit;

public enum AuditEventType {
    APPLICATION_RECEIVED,
    CHECKS_STARTED,
    CHECK_COMPLETED,
    AUTOMATIC_DECISION,
    REVIEW_DECISION,
    WITHDRAWN
}
```

`audit/AuditEvent.java`:

```java
package com.example.onboarding.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/** Who did what to an application, when, and why. Insert-only. */
@Entity
@Immutable
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private AuditEventType type;

    @Column(nullable = false, length = 100)
    private String actor;

    @Column(nullable = false, length = 500)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditEvent() {
        // for JPA
    }

    AuditEvent(UUID applicationId, AuditEventType type, String actor, String detail, Instant occurredAt) {
        this.applicationId = applicationId;
        this.type = type;
        this.actor = actor;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public AuditEventType getType() {
        return type;
    }

    public String getActor() {
        return actor;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
```

**`@Immutable`** (from Hibernate, not JPA) tells Hibernate that rows of this entity never change, so it never generates an `UPDATE` for one. It's a guard inside the application. It doesn't stop someone with database access running SQL by hand; a real bank adds database permissions (the application's user can `INSERT` and `SELECT` on the audit table but not `UPDATE` or `DELETE`), and often ships the events to separate tamper-evident storage.

The constructor is package-private: only the audit package can create events.

`audit/AuditEventRepository.java`:

```java
package com.example.onboarding.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByApplicationIdOrderById(UUID applicationId);
}
```

`audit/AuditTrail.java`:

```java
package com.example.onboarding.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class AuditTrail {

    public static final String SYSTEM = "system";
    public static final String APPLICANT = "applicant";

    private final AuditEventRepository events;
    private final Clock clock;

    public AuditTrail(AuditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /**
     * MANDATORY: there must already be a transaction, the one making the change
     * being audited. The event commits with the change or not at all.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID applicationId, AuditEventType type, String actor, String detail) {
        events.save(new AuditEvent(applicationId, type, actor, detail, clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> eventsFor(UUID applicationId) {
        return events.findByApplicationIdOrderById(applicationId);
    }
}
```

`Propagation` decides what `@Transactional` does when a method is called:

| Propagation | Already in a transaction | Not in a transaction |
|---|---|---|
| `REQUIRED` (the default) | Join it | Start a new one |
| `REQUIRES_NEW` | Suspend it, start a separate one | Start a new one |
| **`MANDATORY`** | Join it | **Throw** |

`MANDATORY` turns "always call this from inside the business transaction" from a convention into a rule. `REQUIRES_NEW` would be wrong here: the audit row would commit even if the change it describes rolled back.

An endpoint to read the trail, `audit/AuditController.java`:

```java
package com.example.onboarding.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class AuditController {

    private final AuditTrail auditTrail;

    public AuditController(AuditTrail auditTrail) {
        this.auditTrail = auditTrail;
    }

    @GetMapping("/applications/{id}/audit")
    public List<AuditEventResponse> list(@PathVariable UUID id) {
        return auditTrail.eventsFor(id).stream().map(AuditEventResponse::from).toList();
    }

    public record AuditEventResponse(AuditEventType type, String actor, String detail, Instant occurredAt) {

        static AuditEventResponse from(AuditEvent event) {
            return new AuditEventResponse(event.getType(), event.getActor(), event.getDetail(),
                    event.getOccurredAt());
        }
    }
}
```

Now record events where things happen. In `ApplicationService`, add `AuditTrail` to the constructor (import `AuditEventType` and `AuditTrail`):

```java
    private final ApplicationRepository applications;
    private final CheckService checkService;
    private final RiskAssessor riskAssessor;
    private final AuditTrail auditTrail;
    private final Clock clock;

    public ApplicationService(ApplicationRepository applications, CheckService checkService,
                              RiskAssessor riskAssessor, AuditTrail auditTrail, Clock clock) {
        this.applications = applications;
        this.checkService = checkService;
        this.riskAssessor = riskAssessor;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }
```

The end of `receive` becomes:

```java
        Application application = applications.save(Application.receive(applicant, clock.instant()));
        auditTrail.record(application.getId(), AuditEventType.APPLICATION_RECEIVED, AuditTrail.APPLICANT,
                "Application received");
        return application;
```

In `startChecks`, record inside the `if`, so a resume doesn't log a second "started":

```java
        if (application.getStatus() != ApplicationStatus.CHECKS_IN_PROGRESS) {
            application.moveTo(ApplicationStatus.CHECKS_IN_PROGRESS, clock.instant());
            auditTrail.record(id, AuditEventType.CHECKS_STARTED, AuditTrail.SYSTEM, "Checks started");
        }
```

And at the end of `decide`:

```java
        application.decide(risk, Decision.decide(results.values(), risk), clock.instant());
        auditTrail.record(id, AuditEventType.AUTOMATIC_DECISION, AuditTrail.SYSTEM,
                "%s (risk %s)".formatted(application.getStatus(), risk));
        return application;
```

In `CheckService`, inject `AuditTrail` the same way, and record each result:

```java
    @Transactional
    public void record(UUID applicationId, CheckType type, CheckResult result) {
        checks.save(new ApplicationCheck(applicationId, type, result, clock.instant()));
        auditTrail.record(applicationId, AuditEventType.CHECK_COMPLETED, AuditTrail.SYSTEM,
                "%s: %s".formatted(type, result.outcome()));
    }
```

The audit detail records the outcome but not the provider's free text. The full detail is already in `application_check`.

### 5. Test the audit trail

`src/test/java/com/example/onboarding/audit/AuditTrailTest.java`:

```java
package com.example.onboarding.audit;

import com.example.onboarding.OnboardingIntegrationTest;
import com.example.onboarding.application.ApplicationService;
import com.example.onboarding.application.ReviewDecision;
import com.example.onboarding.review.ReviewService;
import com.example.onboarding.workflow.OnboardingWorkflow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

import java.util.UUID;

import static com.example.onboarding.TestApplicants.applicant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@OnboardingIntegrationTest
class AuditTrailTest {

    @Autowired ApplicationService applicationService;
    @Autowired OnboardingWorkflow workflow;
    @Autowired ReviewService reviewService;
    @Autowired AuditTrail auditTrail;

    @Test
    void recordsWhoDidWhatAndWhyInOrder() {
        UUID id = applicationService.receive(applicant("Uma", "Unverified")).getId();
        workflow.runChecks(id);
        reviewService.decide(id, "rita", ReviewDecision.APPROVE, "Passport seen in branch");

        assertThat(auditTrail.eventsFor(id))
                .extracting(AuditEvent::getType, AuditEvent::getActor, AuditEvent::getDetail)
                .containsExactly(
                        tuple(AuditEventType.APPLICATION_RECEIVED, "applicant", "Application received"),
                        tuple(AuditEventType.CHECKS_STARTED, "system", "Checks started"),
                        tuple(AuditEventType.CHECK_COMPLETED, "system", "IDENTITY: REFER"),
                        tuple(AuditEventType.CHECK_COMPLETED, "system", "ADDRESS: CLEAR"),
                        tuple(AuditEventType.CHECK_COMPLETED, "system", "SANCTIONS: CLEAR"),
                        tuple(AuditEventType.CHECK_COMPLETED, "system", "PEP: CLEAR"),
                        tuple(AuditEventType.AUTOMATIC_DECISION, "system", "REFERRED (risk STANDARD)"),
                        tuple(AuditEventType.REVIEW_DECISION, "rita", "APPROVE -> APPROVED: Passport seen in branch"));
    }

    @Test
    void cannotBeWrittenOutsideTheTransactionThatMakesTheChange() {
        UUID id = applicationService.receive(applicant("Alice", "Smith")).getId();

        assertThatThrownBy(() -> auditTrail.record(id, AuditEventType.CHECKS_STARTED, "system", "orphan"))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("propagation 'mandatory'");
    }
}
```

`ReviewService` doesn't exist yet. That's next.

`extracting(...)` with several getters turns each event into a `tuple`, so one `containsExactly` checks type, actor, detail **and order**. The second test proves the `MANDATORY` rule: called from a test with no transaction, `record` refuses.

### 6. The review service and API

A queue query in `ApplicationRepository` (imports: `java.util.Collection`, `java.util.List`):

```java
    List<Application> findByStatusInOrderByStatusChangedAt(Collection<ApplicationStatus> statuses);
```

Create the package `com.example.onboarding.review`. `review/ReviewService.java`:

```java
package com.example.onboarding.review;

import com.example.onboarding.application.Application;
import com.example.onboarding.application.ApplicationNotFoundException;
import com.example.onboarding.application.ApplicationRepository;
import com.example.onboarding.application.ApplicationStatus;
import com.example.onboarding.application.ReviewDecision;
import com.example.onboarding.audit.AuditEventType;
import com.example.onboarding.audit.AuditTrail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class ReviewService {

    private final ApplicationRepository applications;
    private final AuditTrail auditTrail;
    private final Clock clock;

    public ReviewService(ApplicationRepository applications, AuditTrail auditTrail, Clock clock) {
        this.applications = applications;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    /** Everything waiting for a person, longest-waiting first. */
    @Transactional(readOnly = true)
    public List<Application> queue() {
        return applications.findByStatusInOrderByStatusChangedAt(
                List.of(ApplicationStatus.REFERRED, ApplicationStatus.AWAITING_SECOND_APPROVAL));
    }

    @Transactional
    public Application decide(UUID id, String reviewer, ReviewDecision decision, String reason) {
        Application application = applications.findById(id)
                .orElseThrow(() -> new ApplicationNotFoundException(id));
        application.review(reviewer, decision, clock.instant());
        auditTrail.record(id, AuditEventType.REVIEW_DECISION, reviewer,
                "%s -> %s: %s".formatted(decision, application.getStatus(), reason));
        return application;
    }
}
```

This `decide` looks perfectly reasonable. Keep it in mind for step 9.

`review/ReviewController.java`:

```java
package com.example.onboarding.review;

import com.example.onboarding.application.Application;
import com.example.onboarding.application.ApplicationController.ApplicationResponse;
import com.example.onboarding.application.ApplicationStatus;
import com.example.onboarding.application.ReviewDecision;
import com.example.onboarding.check.CheckOutcome;
import com.example.onboarding.check.CheckService;
import com.example.onboarding.risk.RiskRating;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The bank's manual review queue. In a real bank this sits behind staff authentication. */
@RestController
@RequestMapping("/reviews")
public class ReviewController {

    private final ReviewService reviewService;
    private final CheckService checkService;

    public ReviewController(ReviewService reviewService, CheckService checkService) {
        this.reviewService = reviewService;
        this.checkService = checkService;
    }

    @GetMapping
    public List<ReviewItem> queue() {
        return reviewService.queue().stream().map(this::toItem).toList();
    }

    @PostMapping("/{id}/decision")
    public ApplicationResponse decide(@PathVariable UUID id,
                                      @RequestHeader("X-Reviewer") @NotBlank @Size(max = 100) String reviewer,
                                      @Valid @RequestBody DecisionRequest request) {
        return ApplicationResponse.from(
                reviewService.decide(id, reviewer, request.decision(), request.reason().strip()));
    }

    private ReviewItem toItem(Application application) {
        List<String> reasons = checkService.checksFor(application.getId()).stream()
                .filter(check -> check.getResult().outcome() != CheckOutcome.CLEAR)
                .map(check -> check.getType() + ": " + check.getResult().detail())
                .toList();
        return new ReviewItem(application.getId(), application.getStatus(), application.getRiskRating(),
                application.getStatusChangedAt(), reasons, application.getFirstApprover());
    }

    public record DecisionRequest(@NotNull ReviewDecision decision, @NotBlank @Size(max = 400) String reason) {
    }

    public record ReviewItem(UUID id, ApplicationStatus status, RiskRating riskRating, Instant waitingSince,
                             List<String> reasons, String firstApprover) {
    }
}
```

- **`X-Reviewer`** stands in for the logged-in member of staff. In a real system the reviewer's identity comes from authentication (for example, the authenticated principal from Spring Security), never from a header the client can set to anything it likes.
- **The queue item** shows only what the reviewer needs to triage: status, risk, how long it's waited, and *why* it was referred. No name or date of birth in the list view.
- **A reason is required** (`@NotBlank`), and limited to 400 characters so the audit detail (decision, status and reason) fits its 500-character column.

Map the new exceptions in `ApiExceptionHandler` (import both):

```java
    @ExceptionHandler(NotUnderReviewException.class)
    ProblemDetail handle(NotUnderReviewException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Not under review");
        return problem;
    }

    @ExceptionHandler(FourEyesException.class)
    ProblemDetail handle(FourEyesException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Second approver required");
        return problem;
    }
```

A four-eyes violation is `403 Forbidden`: the request is valid, but *this person* isn't allowed to make it.

### 7. API tests, and a validation trap

`src/test/java/com/example/onboarding/review/ReviewApiTest.java`:

```java
package com.example.onboarding.review;

import com.example.onboarding.OnboardingIntegrationTest;
import com.example.onboarding.application.ApplicationService;
import com.example.onboarding.workflow.OnboardingWorkflow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.UUID;

import static com.example.onboarding.TestApplicants.applicant;
import static org.assertj.core.api.Assertions.assertThat;

@OnboardingIntegrationTest
class ReviewApiTest {

    @Autowired MockMvcTester mvc;
    @Autowired ApplicationService applicationService;
    @Autowired OnboardingWorkflow workflow;

    UUID referred(String givenName, String familyName) {
        UUID id = applicationService.receive(applicant(givenName, familyName)).getId();
        workflow.runChecks(id);
        return id;
    }

    MvcTestResult decide(UUID id, String reviewer, String json) {
        return mvc.post().uri("/reviews/{id}/decision", id)
                .header("X-Reviewer", reviewer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    @Test
    void theQueueShowsWhyEachApplicationWasReferred() {
        UUID id = referred("Uma", "Unverified");

        MvcTestResult response = mvc.get().uri("/reviews").exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$[?(@.id == '%s')].reasons[0]".formatted(id))
                .asArray().containsExactly("IDENTITY: No electronic match for name and date of birth");
    }

    @Test
    void aReviewerApprovesAReferral() {
        UUID id = referred("Uma", "Unverified");

        assertThat(decide(id, "rita", """
                {"decision": "APPROVE", "reason": "Passport and utility bill seen in branch"}
                """))
                .hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("APPROVED");
    }

    @Test
    void aHighRiskApprovalNeedsADifferentSecondReviewer() {
        UUID id = referred("Edmund", "Exampleshire");
        String approve = """
                {"decision": "APPROVE", "reason": "Source of wealth evidenced"}
                """;

        assertThat(decide(id, "rita", approve)).bodyJson()
                .extractingPath("$.status").isEqualTo("AWAITING_SECOND_APPROVAL");
        assertThat(decide(id, "rita", approve)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(decide(id, "sam", approve)).bodyJson()
                .extractingPath("$.status").isEqualTo("APPROVED");
    }

    @Test
    void aDecisionNeedsAReason() {
        UUID id = referred("Uma", "Unverified");

        MvcTestResult response = decide(id, "rita", """
                {"decision": "APPROVE", "reason": " "}
                """);

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors.reason").isEqualTo("must not be blank");
    }

    @Test
    void aDecisionNeedsAReviewer() {
        UUID id = referred("Uma", "Unverified");

        MvcTestResult response = decide(id, " ", """
                {"decision": "APPROVE", "reason": "Documents seen"}
                """);

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anApprovedApplicationIsNoLongerUnderReview() {
        UUID id = applicationService.receive(applicant("Alice", "Smith")).getId();
        workflow.runChecks(id);

        assertThat(decide(id, "rita", """
                {"decision": "DECLINE", "reason": "Changed my mind"}
                """)).hasStatus(HttpStatus.CONFLICT);
    }
}
```

- Uma Unverified is referred for identity, at standard risk. Edmund Exampleshire is a possible PEP, so high risk.
- The JSONPath filter `$[?(@.id == '…')]` picks Uma's item out of the queue, whatever else other tests have left in it.

Run it. **`aDecisionNeedsAReason` fails:**

```
Expecting:
  "{"detail":"Validation failure","instance":"/reviews/4e2d6851-2094-43e2-a2ea-67a49584972c/decision","status":400,"title":"Bad Request"}"
To match JSON path:
  "$.errors.reason"
```

The status is right (`400`), but the `errors` map has gone, and the `detail` is different from guide 01's `"Invalid request content."`. A different exception produced this response.

Here's why. On its own, `@Valid @RequestBody` makes Spring MVC validate the body and throw `MethodArgumentNotValidException`, which your handler enriches. But as soon as **any** parameter carries a constraint of its own (here `@NotBlank @Size` on the `@RequestHeader`), Spring switches the whole method to **method validation**. It validates every parameter in one go, the body included, and reports all the failures together as a **`HandlerMethodValidationException`**. Your override of `handleMethodArgumentNotValid` never runs.

Handle it as well. In `ApiExceptionHandler` add these imports:

```java
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
```

and this override:

```java
    /**
     * Once any controller parameter has a constraint of its own (like @NotBlank
     * on a header), Spring validates the whole method instead, and a bad @Valid
     * body arrives here rather than in handleMethodArgumentNotValid.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new TreeMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors body) {
                for (FieldError error : body.getFieldErrors()) {
                    errors.putIfAbsent(error.getField(), error.getDefaultMessage());
                }
            } else {
                errors.putIfAbsent(result.getMethodParameter().getParameterName(),
                        result.getResolvableErrors().getFirst().getDefaultMessage());
            }
        }
        ProblemDetail problem = ex.getBody();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }
```

- **`getParameterValidationResults()`** has one entry per parameter that failed.
- **A `@Valid` object parameter** (the body) comes back as **`ParameterErrors`**, which has the familiar field errors. `instanceof` with a pattern variable (Java 16+) tests and casts in one go.
- **A simple parameter** (the header) has no fields. Its errors are listed against the parameter, so use the parameter's name. (`getParameterName()` works because Spring Boot's Maven setup compiles with `-parameters`.)
- **`getFirst()`** on a `List` is Java 21+.

Run the tests: all green. Make `aDecisionNeedsAReviewer` stricter too, now that the header's error is reported:

```java
        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors.reviewer").isEqualTo("must not be blank");
```

With both mistakes at once (a blank header *and* a blank reason), you now get:

```json
{
  "detail": "Validation failure",
  "instance": "/reviews/9052d15a-09fc-4bac-8b0f-ebf64cfca908/decision",
  "status": 400,
  "title": "Bad Request",
  "errors": { "reason": "must not be blank", "reviewer": "must not be blank" }
}
```

### 8. Try it

Add to `requests.http`:

```http
### The review queue
GET http://localhost:8080/reviews

### Approve John (first approval: he's high risk)
POST http://localhost:8080/reviews/{{johnId}}/decision
X-Reviewer: rita
Content-Type: application/json

{"decision": "APPROVE", "reason": "Date of birth differs from FAKE-SAN-003; passport seen"}

### The same reviewer again: 403
POST http://localhost:8080/reviews/{{johnId}}/decision
X-Reviewer: rita
Content-Type: application/json

{"decision": "APPROVE", "reason": "Again"}

### A second reviewer approves
POST http://localhost:8080/reviews/{{johnId}}/decision
X-Reviewer: sam
Content-Type: application/json

{"decision": "APPROVE", "reason": "Second review agrees: false positive"}

### John's audit trail
GET http://localhost:8080/applications/{{johnId}}/audit
```

With curl:

```bash
curl -s localhost:8080/reviews
curl -s -X POST localhost:8080/reviews/<id>/decision -H 'X-Reviewer: rita' \
  -H 'Content-Type: application/json' -d '{"decision":"APPROVE","reason":"Passport seen"}'
curl -s localhost:8080/applications/<id>/audit
```

The queue, with an identity referral and a PEP:

```json
[
  {
    "id": "ae7be4c8-96d3-462c-985d-2a9d85803055",
    "status": "REFERRED",
    "riskRating": "STANDARD",
    "waitingSince": "2026-11-07T14:12:44.047785Z",
    "reasons": ["IDENTITY: No electronic match for name and date of birth"],
    "firstApprover": null
  },
  {
    "id": "799c4136-849d-46fd-ba2c-6c5de7766987",
    "status": "REFERRED",
    "riskRating": "HIGH",
    "waitingSince": "2026-11-07T14:12:44.109139Z",
    "reasons": ["PEP: Possible PEP: FAKE-PEP-001, Member of Parliament (fictitious)"],
    "firstApprover": null
  }
]
```

The same reviewer approving twice:

```json
{"detail":"rita already approved application 799c4136-849d-46fd-ba2c-6c5de7766987; the second approval must come from someone else","instance":"/reviews/799c4136-849d-46fd-ba2c-6c5de7766987/decision","status":403,"title":"Second approver required"}
```

And the PEP's audit trail once `sam` has approved (the earlier events are as in the test):

```json
  {"type": "AUTOMATIC_DECISION", "actor": "system", "detail": "REFERRED (risk HIGH)", …},
  {"type": "REVIEW_DECISION", "actor": "rita",
   "detail": "APPROVE -> AWAITING_SECOND_APPROVAL: Source of wealth evidenced: payslips and property sale", …},
  {"type": "REVIEW_DECISION", "actor": "sam",
   "detail": "APPROVE -> APPROVED: Senior management approval given", …}
```

Who, what, when and why, for every step.

### 9. Ten reviewers, one referral

Now the race. Create `src/test/java/com/example/onboarding/review/ConcurrentReviewTest.java`:

```java
package com.example.onboarding.review;

import com.example.onboarding.OnboardingIntegrationTest;
import com.example.onboarding.application.Application;
import com.example.onboarding.application.ApplicationService;
import com.example.onboarding.application.NotUnderReviewException;
import com.example.onboarding.application.ReviewDecision;
import com.example.onboarding.audit.AuditEvent;
import com.example.onboarding.audit.AuditEventType;
import com.example.onboarding.audit.AuditTrail;
import com.example.onboarding.workflow.OnboardingWorkflow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.example.onboarding.TestApplicants.applicant;
import static org.assertj.core.api.Assertions.assertThat;

/** Ten reviewers open the same referral and all click a button at the same moment. */
@OnboardingIntegrationTest
class ConcurrentReviewTest {

    @Autowired ApplicationService applicationService;
    @Autowired OnboardingWorkflow workflow;
    @Autowired ReviewService reviewService;
    @Autowired AuditTrail auditTrail;

    @Test
    void onlyOneReviewerCanDecideAReferral() throws Exception {
        UUID id = applicationService.receive(applicant("Uma", "Unverified")).getId();
        workflow.runChecks(id); // REFERRED, standard risk: one approval is enough

        int reviewers = 10;
        CountDownLatch startingGun = new CountDownLatch(1);
        List<String> decided = new ArrayList<>();
        List<Throwable> refused = new ArrayList<>();
        List<Throwable> unexpected = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(reviewers)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < reviewers; i++) {
                String reviewer = "reviewer-" + i;
                ReviewDecision decision = i % 2 == 0 ? ReviewDecision.APPROVE : ReviewDecision.DECLINE;
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    try {
                        Application result = reviewService.decide(id, reviewer, decision, "Looked at it");
                        synchronized (decided) {
                            decided.add(reviewer + " " + decision + " -> " + result.getStatus());
                        }
                    } catch (NotUnderReviewException e) {
                        synchronized (refused) {
                            refused.add(e);
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
                future.get(30, TimeUnit.SECONDS);
            }
        }

        List<AuditEvent> reviewEvents = auditTrail.eventsFor(id).stream()
                .filter(event -> event.getType() == AuditEventType.REVIEW_DECISION)
                .toList();
        System.out.println("decided=" + decided);
        System.out.println("refused=" + refused.size() + " unexpected=" + unexpected);
        System.out.println("final status=" + applicationService.get(id).getStatus()
                + ", review events=" + reviewEvents.size());

        assertThat(unexpected).isEmpty();
        assertThat(decided).hasSize(1);
        assertThat(refused).hasSize(reviewers - 1);
        assertThat(reviewEvents).hasSize(1);
    }
}
```

How the test works:

- **The `CountDownLatch` is a starting gun.** Every task blocks on `await()` until all ten are lined up; `countDown()` releases them together. Without it, the first reviewer would often finish before the tenth had started, and the test would pass by luck.
- **Half approve, half decline.** The correct outcome is that exactly one of them decides, and the other nine are refused with `NotUnderReviewException`, because by the time they look, it's no longer under review.
- **The class is not `@Transactional`.** Each `decide` call runs in its own real transaction on its own thread, exactly like ten HTTP requests. A test-level transaction would only cover the main thread, and the worker threads couldn't see its uncommitted data.
- **`future.get(30, SECONDS)`** turns a hang into a failure, and rethrows anything that escaped a task.

Run it. It fails. Here are three runs:

```
decided=[reviewer-4 APPROVE -> APPROVED, reviewer-1 DECLINE -> DECLINED, reviewer-8 APPROVE -> APPROVED, reviewer-3 DECLINE -> DECLINED, reviewer-9 DECLINE -> DECLINED, reviewer-6 APPROVE -> APPROVED, reviewer-0 APPROVE -> APPROVED]
refused=3 unexpected=[]
final status=APPROVED, review events=7
```

```
decided=[reviewer-8 APPROVE -> APPROVED, reviewer-2 APPROVE -> APPROVED, … (10 in total)]
refused=0 unexpected=[]
final status=DECLINED, review events=10
```

```
decided=[reviewer-0 APPROVE -> APPROVED, … reviewer-3 DECLINE -> DECLINED, reviewer-7 DECLINE -> DECLINED]
refused=4 unexpected=[]
final status=DECLINED, review events=6
```

```
Expected size: 1 but was: 10 in:
["reviewer-8 APPROVE -> APPROVED",
    "reviewer-2 APPROVE -> APPROVED",
    ...
```

Read that second run slowly. **Ten reviewers each got a success response.** Five were told they'd approved the account, five that they'd declined it. The application ended up `DECLINED`, and the audit trail contains ten contradictory decisions, any of which a regulator might ask about. The number changes from run to run because it depends on exactly how the threads interleave.

What happened is the lost update from the Concepts section. Each transaction ran `SELECT` (no lock), saw `REFERRED`, passed the `isUnderReview` check in memory, and wrote its `UPDATE`. The database ran the updates one after another, each overwriting the last, and nothing ever re-checked the status.

### 10. The fix: lock, then check

Add a locking query to `ApplicationRepository` (imports: `jakarta.persistence.LockModeType`, `org.springframework.data.jpa.repository.Lock`, `org.springframework.data.jpa.repository.Query`, `java.util.Optional`):

```java
    /** SELECT ... FOR UPDATE: a second reviewer waits until the first one's transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Application a where a.id = :id")
    Optional<Application> findByIdForUpdate(UUID id);
```

And use it in `ReviewService.decide`:

```java
        Application application = applications.findByIdForUpdate(id)
                .orElseThrow(() -> new ApplicationNotFoundException(id));
```

That one line is the whole fix. Run the test three times:

```
decided=[reviewer-7 DECLINE -> DECLINED]
refused=9 unexpected=[]
final status=DECLINED, review events=1
```

```
decided=[reviewer-0 APPROVE -> APPROVED]
refused=9 unexpected=[]
final status=APPROVED, review events=1
```

```
decided=[reviewer-9 DECLINE -> DECLINED]
refused=9 unexpected=[]
final status=DECLINED, review events=1
```

✅ Exactly one decision, nine clean refusals, one audit event. *Which* reviewer wins varies, and that's fine: what matters is that there's only one winner and everyone else is told.

How it works: `@Lock(PESSIMISTIC_WRITE)` makes Hibernate add `FOR UPDATE` to the `SELECT`. The first transaction to get there locks the application's row. The other nine block at the `SELECT` until it commits, then each reads the **new** status, `APPROVED` or `DECLINED`, and `review` throws `NotUnderReviewException`. The lock is held until the transaction ends, so the check, the status change and the audit event all happen while no one else can touch the row.

> **Sidebar: why not optimistic locking?** A `@Version` column would also stop the lost update: each `UPDATE` would include `AND version = ?`, the losers' updates would match no rows, and Hibernate would throw `ObjectOptimisticLockingFailureException`. The data would be safe. But the losers would get a generic locking failure rather than "already decided", so you'd map it to `409` and lose the useful message. Optimistic locking suits rare conflicts where a retry is fine. Here the right answer for the losers isn't "retry", it's "someone else has decided", and the pessimistic lock gives you that directly. Many systems add `@Version` anyway as a safety net for any code path that forgets to lock.

> **Sidebar: H2 and PostgreSQL.** Both support `SELECT … FOR UPDATE` with the same blocking behaviour, so this guide works unchanged on PostgreSQL. A blocked transaction waits up to the database's lock timeout, then fails. Reviews are quick, so that's plenty, but if you pause in a debugger while a transaction holds the lock, the other threads will eventually time out.

---

## Your turn

### Exercise 1: audit the withdrawal

Withdrawal is an action the applicant takes, and it isn't in the audit trail yet. Record a `WITHDRAWN` event, with actor `"applicant"` and detail `"Withdrawn by the applicant"`, and add a test to `AuditTrailTest` that checks the last event after a withdrawal.

<details>
<summary>Solution</summary>

`ApplicationService.withdraw`:

```java
    @Transactional
    public Application withdraw(UUID id) {
        Application application = get(id);
        application.moveTo(ApplicationStatus.WITHDRAWN, clock.instant());
        auditTrail.record(id, AuditEventType.WITHDRAWN, AuditTrail.APPLICANT, "Withdrawn by the applicant");
        return application;
    }
```

`AuditTrailTest`:

```java
    @Test
    void recordsAWithdrawalAsTheApplicantsOwnAction() {
        UUID id = applicationService.receive(applicant("Alice", "Smith")).getId();

        applicationService.withdraw(id);

        assertThat(auditTrail.eventsFor(id).getLast())
                .extracting(AuditEvent::getType, AuditEvent::getActor)
                .containsExactly(AuditEventType.WITHDRAWN, "applicant");
    }
```

The audit call comes **after** `moveTo`. If the move throws (withdrawing twice), no event is written, and even if one were, the exception would roll it back with everything else.

</details>

### Exercise 2: cover the new status in the transition tests

`ApplicationStatusTest` from guide 01 doesn't mention `AWAITING_SECOND_APPROVAL`. Add rows so that it proves:

- `REFERRED → AWAITING_SECOND_APPROVAL`, `AWAITING_SECOND_APPROVAL → APPROVED` and `AWAITING_SECOND_APPROVAL → DECLINED` are allowed;
- `AWAITING_SECOND_APPROVAL → REFERRED` and `CHECKS_IN_PROGRESS → AWAITING_SECOND_APPROVAL` are not.

<details>
<summary>Solution</summary>

Add to the `@CsvSource` of `allowsTheWorkflowsOwnSteps`:

```java
            "REFERRED,           AWAITING_SECOND_APPROVAL",
            "AWAITING_SECOND_APPROVAL, APPROVED",
            "AWAITING_SECOND_APPROVAL, DECLINED"
```

and to `rejectsShortcutsAndU_turns`:

```java
            "AWAITING_SECOND_APPROVAL, REFERRED",
            "CHECKS_IN_PROGRESS, AWAITING_SECOND_APPROVAL"
```

(Remember the comma at the end of the line before each addition.) The second illegal row matters: it proves the automatic decision can never skip straight to "half approved".

Notice that `noStatusCanMoveToItself` and `nothingCanGoBackToReceived` already cover the new status, because they loop over `values()`.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 73, Failures: 0, Errors: 0, Skipped: 0
```

(67 from the steps, plus 6 from the exercises: one new test and five new parameterised rows.) Run it two or three times: a concurrency test must pass every time, not just usually.

New files since guide 02:

```
src/main/java/com/example/onboarding/
├── application/
│   ├── FourEyesException.java
│   ├── NotUnderReviewException.java
│   └── ReviewDecision.java
├── audit/
│   ├── AuditController.java
│   ├── AuditEvent.java
│   ├── AuditEventRepository.java
│   ├── AuditEventType.java
│   └── AuditTrail.java
└── review/
    ├── ReviewController.java
    └── ReviewService.java
src/main/resources/db/migration/
└── V3__review_and_audit.sql
src/test/java/com/example/onboarding/
├── application/ApplicationReviewTest.java
├── audit/AuditTrailTest.java
└── review/
    ├── ConcurrentReviewTest.java
    └── ReviewApiTest.java
```

Modified: `ApplicationStatus.java`, `Application.java` (`review`, `firstApprover`), `ApplicationRepository.java` (queue query, `findByIdForUpdate`), `ApplicationService.java` and `CheckService.java` (audit events), `ApiExceptionHandler.java` (two handlers, method validation), `ApplicationStatusTest.java`, `requests.http`.

---

## Common errors

**`{"detail":"Validation failure", …, "status":400, "title":"Bad Request"}` with no `errors` map**
A parameter has its own constraint (`@NotBlank` on the `@RequestHeader`), so Spring used method validation and threw `HandlerMethodValidationException`. Override `handleHandlerMethodValidationException` as in step 7. If you never need constraints on simple parameters, the other fix is to validate the header yourself in the service.

**`IllegalTransactionStateException: No existing transaction found for transaction marked with propagation 'mandatory'`**
`AuditTrail.record` was called from code that isn't inside a transaction: a non-`@Transactional` service method, or a method called on `this` inside the same class (which bypasses Spring's transactional proxy). Record audit events from inside the `@Transactional` service method that makes the change.

**`{"detail":"Required header 'X-Reviewer' is not present.", …, "status":400}`**
The request has no `X-Reviewer` header. With curl, watch out: `-H 'X-Reviewer: '` (an empty value) makes curl *remove* the header entirely, so you get this message rather than the `"must not be blank"` one.

**`ConcurrentReviewTest`: `Expected size: 1 but was: 10`**
The review isn't locking. Check that `ReviewService.decide` calls `findByIdForUpdate`, not `findById`, and that `decide` is `@Transactional` and called through the Spring bean.

**`409 Not under review`**
The application was never referred, or has already been decided. That's correct behaviour. A reviewer who gets this should refresh the queue.

**`403 Second approver required`**
The same reviewer tried to give both approvals for a high-risk application. Use a different `X-Reviewer`.

---

## Recap

- Referred applications go to a **review queue**. Every reviewer decision needs a **recorded reason**.
- The **four-eyes** rule must be **enforced by the system**: the second approver must be a different person (and, in a real bank, have the right authority, such as senior management for a PEP).
- Applicants see a status, not the reasons. **Tipping off** is a criminal offence, and reasons help fraudsters.
- An **audit trail** is insert-only (`@Immutable`), written **in the same transaction** as the change (`Propagation.MANDATORY`), and free of personal data.
- A constraint on a `@RequestHeader` or `@RequestParam` switches to **method validation**: handle **`HandlerMethodValidationException`** too.
- **Check-then-act on shared state is a race.** Lock the row (`SELECT … FOR UPDATE`) **before** checking, and the losers see the winner's result.
- Prove concurrency bugs with a **`CountDownLatch` starting gun**, no test-level transaction, and assertions on the **invariants** (one decision, one audit event).

### Interview angle

<details>
<summary>"What is maker-checker, and how would you implement it?"</summary>

Two different people are needed for a sensitive action: one makes it, another checks and confirms it. Model the intermediate state explicitly (for example `AWAITING_SECOND_APPROVAL`), store who the maker was, and have the domain object refuse a confirmation from the same person. Do the check under a row lock so two people can't race past it. In a real system you'd also check the checker's role, and record both people in the audit trail.

</details>

<details>
<summary>"Why can't a bank just tell a customer why their application was declined?"</summary>

If the bank has made a suspicious activity report or there's an investigation, disclosing it in a way that's likely to prejudice the investigation is the offence of tipping off under the Proceeds of Crime Act 2002. More generally, explaining fraud or screening rules helps people get around them. So customer-facing messages are generic, and the real reasons are kept in internal records for staff, auditors and regulators.

</details>

<details>
<summary>"How do you make an audit trail trustworthy?"</summary>

Make it insert-only: no updates or deletes in the application (for example Hibernate's `@Immutable`), and database permissions that enforce it. Write each event in the same transaction as the change it describes, so they commit or roll back together. Record who, what, when and why. Keep personal data out of free-text fields. For stronger guarantees, ship events to separate, append-only or tamper-evident storage, so even a database administrator can't quietly rewrite history.

</details>

<details>
<summary>"Two users update the same record at the same time. What can go wrong and what do you do?"</summary>

A lost update: both read the old state, both pass their checks, and the second write silently overwrites the first. `@Transactional` alone doesn't prevent it under READ COMMITTED. Either lock the row when reading (`SELECT … FOR UPDATE`, `@Lock(PESSIMISTIC_WRITE)`), so the second waits and then sees the new state, or use optimistic locking with a version column and handle the failure. Pessimistic suits contended rows and "the loser needs a meaningful answer". Optimistic suits rare conflicts where retrying is fine.

</details>

### Further reading

- [Proceeds of Crime Act 2002, section 333A (tipping off: regulated sector)](https://www.legislation.gov.uk/ukpga/2002/29/section/333A)
- [MLR 2017 regulation 35 (politically exposed persons)](https://www.legislation.gov.uk/uksi/2017/692/regulation/35)
- [Spring Framework reference: method validation in Spring MVC](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html)
- [Spring Data JPA reference: locking](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)

**Simplification to notice:** reviewers are identified by a header anyone can set. A real review tool sits behind staff authentication, takes the reviewer from the security context, and checks their role before allowing a second approval.

**Next:** [04: Encrypting personal data](04-encrypting-personal-data.md). The database is full of names, dates of birth and addresses in plain text. Time to fix that, and still be able to spot the same person applying twice.
