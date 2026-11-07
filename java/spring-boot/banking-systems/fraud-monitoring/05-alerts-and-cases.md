# 05: Alerts and Cases

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- What happens to an alert after it's raised: **triage**, **case management**, investigation and an **outcome**
- What an AML outcome leads to: the **nominated officer**, **Suspicious Activity Reports** to the National Crime Agency, and why **tipping off** shapes the software
- The **four-eyes principle**, and why a bank wants two people on some decisions
- How to model a case lifecycle as a small **state machine** with an enum and a switch expression, and reject invalid transitions with `409` `ProblemDetail`s
- How **`@Version`** stops two analysts from both "taking" the same case, seen in a failing concurrency test
- A REST API for analysts: an alert queue, opening cases, assigning and closing them

By the end, analysts can see a queue of unworked alerts, group an account's alerts into a case, take it, and close it with an outcome, and the API refuses every step taken out of order.

---

## Concepts

### How it works in a real bank

Rules raise alerts. People decide what they mean. The journey from one to the other looks broadly like this, in both fraud and AML teams:

```
alerts ─▶ queue ─▶ triage ─▶ CASE: OPEN ─▶ INVESTIGATING ─▶ CLOSED (with an outcome)
                     │                                          │
                     └─ discounted as obviously fine            ├─ false positive: no further action
                                                                ├─ confirmed fraud: protect the customer, recover funds
                                                                └─ suspicion of money laundering: refer internally
```

- **The queue.** Alerts nobody has looked at yet, usually oldest first. How long things wait in the queue is one of the main things a monitoring team is measured on, and regulators look at backlogs too.
- **A case** groups related alerts, usually for one customer or account, so they're investigated together. Two alerts from the same scam payment are one story, not two.
- **Investigating** means an analyst owns the case: looking at the account's history, KYC information, previous cases and, for fraud, often contacting the customer.
- **The outcome** records what was decided and why. A closed case is part of the bank's audit trail: if a regulator asks "what did you do about this alert?", the case is the answer.

What happens after an outcome differs:

- **Confirmed fraud** leads to action to protect the customer and the bank: blocking cards or access, contacting the bank that received the money to try to freeze and recover it, and, for APP scams, the reimbursement process.
- **Suspected money laundering** leads to an internal referral. Under the [Proceeds of Crime Act 2002](https://www.legislation.gov.uk/ukpga/2002/29/part/7) (section 330), people working in the regulated sector who know or suspect, or have reasonable grounds to know or suspect, that someone is laundering money must disclose it, normally to their firm's **nominated officer** (often called the MLRO, money laundering reporting officer). The nominated officer decides whether to make a **[Suspicious Activity Report (SAR)](https://www.nationalcrimeagency.gov.uk/what-we-do/crime-threats/money-laundering-and-illicit-finance/suspicious-activity-reports)** to the UK Financial Intelligence Unit at the National Crime Agency. The analyst doesn't file the SAR; that decision belongs to the nominated officer.

> **Simplification:** this tutorial records "referred to the nominated officer" as a case outcome and stops there. The nominated officer's own process (their review, the SAR itself, and requests to the NCA for a defence before going ahead with a suspicious transaction) is a separate, tightly controlled workflow with its own legal deadlines. Real case-management systems also keep a full history of notes, evidence, reassignments and approvals, not just the final state.

### Tipping off

[Section 333A](https://www.legislation.gov.uk/ukpga/2002/29/section/333A) of the Proceeds of Crime Act makes it an offence, in the regulated sector, to disclose that a SAR has been made, or that a money laundering investigation is being contemplated or carried out, where that disclosure is likely to prejudice an investigation. This is called **tipping off**, and it has real consequences for the software:

- AML case details and outcomes **must never appear in customer-facing channels**: not in the banking app, not in statements, not in a customer service agent's standard screen.
- Access to AML cases is restricted to the people who need it.
- Fraud and AML cases often live in different systems, or at least behind different permissions, even though they come from the same monitoring.

This tutorial's API has no authentication at all (see the simplifications below), so treat it as an internal tool that only analysts can reach. The interview point is knowing that the restriction exists and why.

### The four-eyes principle

Some decisions in a bank need **two people**: one makes it, a different one checks and approves it. It's also called **maker-checker**. Closing a monitoring case is a good candidate, because one analyst on their own could close every alert on an account they had a reason to protect. Requiring a second person turns a single point of failure (or of corruption) into two. You'll add it in Exercise 1.

> **Simplification:** the analyst's name comes from the request body here, so anyone could claim to be anyone, and the four-eyes check would be easy to get round. In a real system the identity comes from authentication (for example, Spring Security and the bank's single sign-on), and the API never trusts a name the client sends.

### A case is a state machine

A case can only move forwards: `OPEN → INVESTIGATING → CLOSED`. You can't close a case nobody has investigated, and a closed case is final (if something new turns up, it's a new case, which keeps the record of the old decision intact). That's a **state machine**: a fixed set of states, and a fixed set of allowed transitions between them.

The API should **refuse** anything else, with a clear error. An attempt to close an `OPEN` case isn't a malformed request (`400`), and it isn't breaking a business rule about the request's contents (`422`). It **conflicts with the case's current state**, so it's a `409 Conflict`, the same distinction the Core ledger tutorial draws.

---

## Steps

### 1. The case table

`src/main/resources/db/migration/V5__create_fraud_case.sql`:

```sql
CREATE TABLE fraud_case (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    account_id   VARCHAR(40)              NOT NULL,
    status       VARCHAR(20)              NOT NULL,
    assignee     VARCHAR(50),
    outcome      VARCHAR(40),
    closing_note VARCHAR(1000),
    closed_by    VARCHAR(50),
    opened_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at    TIMESTAMP WITH TIME ZONE,
    version      BIGINT                   NOT NULL,
    CONSTRAINT ck_fraud_case_status CHECK (status IN ('OPEN', 'INVESTIGATING', 'CLOSED')),
    CONSTRAINT ck_fraud_case_closed CHECK ((status = 'CLOSED') = (outcome IS NOT NULL))
);

CREATE INDEX ix_fraud_case_status ON fraud_case (status);

ALTER TABLE alert ADD COLUMN case_id BIGINT REFERENCES fraud_case (id);

CREATE INDEX ix_alert_case ON alert (case_id);
```

- **The table is `fraud_case`, not `case`.** `CASE` is an SQL keyword, and `case` is a Java keyword too, which is why the Java class is `FraudCase` and the package is `casework`.
- **`ck_fraud_case_closed`** says "a case has an outcome if and only if it's closed", by comparing two booleans. Even a hand-written SQL fix can't leave a closed case without an outcome or an open case with one.
- **`alert.case_id`** is a nullable foreign key. `NULL` means "not in a case yet": the queue. Adding a nullable column to an existing table is a safe migration: existing rows simply get `NULL`.
- **`version`** is for optimistic locking, in step 8.

### 2. States, outcomes and errors

Create the `casework` package. `casework/CaseStatus.java`:

```java
package com.example.fraud.casework;

/** Where a case is in its life. The only allowed moves are OPEN → INVESTIGATING → CLOSED. */
public enum CaseStatus {
    OPEN,
    INVESTIGATING,
    CLOSED;

    public boolean canMoveTo(CaseStatus next) {
        return switch (this) {
            case OPEN -> next == INVESTIGATING;
            case INVESTIGATING -> next == CLOSED;
            case CLOSED -> false; // closed is final: reopening means a new case
        };
    }
}
```

The whole state machine is in that switch expression (Java 14+). Because it switches over an enum with no `default`, the compiler checks it's **exhaustive**: add a new status and this method won't compile until you've decided where it can go.

`casework/CaseOutcome.java`:

```java
package com.example.fraud.casework;

/** Why a case was closed. */
public enum CaseOutcome {
    /** The customer made the payments themselves; nothing was wrong. */
    FALSE_POSITIVE,
    /** Fraud against the customer or the bank: block, refund, recover as the bank's procedures say. */
    CONFIRMED_FRAUD,
    /** Possible money laundering: handed to the nominated officer, who decides whether to report it. */
    REFERRED_TO_NOMINATED_OFFICER
}
```

The outcomes match the three branches in the Concepts diagram. Note the third one's wording: the analyst **refers**; they don't "file a SAR".

Four exceptions, each with a clear message. `casework/InvalidCaseTransitionException.java`:

```java
package com.example.fraud.casework;

public class InvalidCaseTransitionException extends RuntimeException {

    public InvalidCaseTransitionException(Long caseId, CaseStatus from, CaseStatus to) {
        super("Case %d is %s and cannot move to %s".formatted(caseId, from, to));
    }
}
```

`casework/CaseNotFoundException.java`:

```java
package com.example.fraud.casework;

public class CaseNotFoundException extends RuntimeException {

    public CaseNotFoundException(Long id) {
        super("No case with id " + id);
    }
}
```

`casework/AlertAlreadyInCaseException.java`:

```java
package com.example.fraud.casework;

public class AlertAlreadyInCaseException extends RuntimeException {

    public AlertAlreadyInCaseException(Long alertId, Long caseId) {
        super("Alert %d is already part of case %d".formatted(alertId, caseId));
    }
}
```

`casework/InvalidCaseException.java`, for requests that break a case-management rule:

```java
package com.example.fraud.casework;

/** The request breaks a case-management rule. */
public class InvalidCaseException extends RuntimeException {

    public InvalidCaseException(String message) {
        super(message);
    }
}
```

And one in the `alert` package. `alert/AlertNotFoundException.java`:

```java
package com.example.fraud.alert;

public class AlertNotFoundException extends RuntimeException {

    public AlertNotFoundException(Long id) {
        super("No alert with id " + id);
    }
}
```

### 3. The `FraudCase` entity

`casework/FraudCase.java`:

```java
package com.example.fraud.casework;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/** One investigation into one account, covering one or more alerts. */
@Entity
@Table(name = "fraud_case")
public class FraudCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, length = 40)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CaseStatus status;

    @Column(length = 50)
    private String assignee;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private CaseOutcome outcome;

    @Column(name = "closing_note", length = 1000)
    private String closingNote;

    @Column(name = "closed_by", length = 50)
    private String closedBy;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    private long version;

    protected FraudCase() {
        // for JPA
    }

    public static FraudCase open(String accountId, Instant now) {
        FraudCase fraudCase = new FraudCase();
        fraudCase.accountId = accountId;
        fraudCase.status = CaseStatus.OPEN;
        fraudCase.openedAt = now;
        return fraudCase;
    }

    public void assignTo(String analyst) {
        moveTo(CaseStatus.INVESTIGATING);
        this.assignee = analyst;
    }

    public void close(CaseOutcome outcome, String note, String closedBy, Instant now) {
        moveTo(CaseStatus.CLOSED);
        this.outcome = outcome;
        this.closingNote = note;
        this.closedBy = closedBy;
        this.closedAt = now;
    }

    private void moveTo(CaseStatus next) {
        if (!status.canMoveTo(next)) {
            throw new InvalidCaseTransitionException(id, status, next);
        }
        status = next;
    }

    public Long getId() {
        return id;
    }

    public String getAccountId() {
        return accountId;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public String getAssignee() {
        return assignee;
    }

    public CaseOutcome getOutcome() {
        return outcome;
    }

    public String getClosingNote() {
        return closingNote;
    }

    public String getClosedBy() {
        return closedBy;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
```

- **There's no `setStatus`.** The only ways to change state are `assignTo` and `close`, named after what an analyst does, and both go through `moveTo`, which asks the enum whether the move is allowed. Invalid transitions are impossible however the entity is used.
- **Each transition sets its own data together with the state**, so a closed case always has an outcome, a note and a closer. The database's check constraint is the backstop.
- **`@Version`**: step 8.

`casework/FraudCaseRepository.java`:

```java
package com.example.fraud.casework;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FraudCaseRepository extends JpaRepository<FraudCase, Long> {

    List<FraudCase> findByStatusOrderByOpenedAtAsc(CaseStatus status);
}
```

### 4. Alerts know their case

In `alert/Alert.java`, add the column and two methods:

```java
    @Column(name = "case_id")
    private Long caseId;

    ...

    /** The case investigating this alert, or null if nobody has picked it up yet. */
    public Long getCaseId() {
        return caseId;
    }

    public void attachTo(Long caseId) {
        this.caseId = caseId;
    }
```

A plain `Long` rather than a `@ManyToOne FraudCase` relationship. The alert only needs to know *which* case; loading the whole case every time you load an alert (or managing a lazy proxy) buys nothing here. When you need a case's alerts, you ask the repository.

Replace `alert/AlertRepository.java`:

```java
package com.example.fraud.alert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    List<Alert> findByAccountIdOrderByIdAsc(String accountId);

    /** The work queue: alerts nobody has opened a case for, oldest first. */
    List<Alert> findByCaseIdIsNullOrderByIdAsc();

    List<Alert> findByCaseIdOrderByIdAsc(Long caseId);
}
```

And replace `alert/AlertController.java`, adding the queue endpoint and the `caseId` field:

```java
package com.example.fraud.alert;

import com.example.fraud.money.Money;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/alerts")
public class AlertController {

    private final AlertRepository alerts;

    public AlertController(AlertRepository alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    public List<AlertResponse> forAccount(@RequestParam String accountId) {
        return alerts.findByAccountIdOrderByIdAsc(accountId).stream().map(AlertResponse::from).toList();
    }

    /** Alerts waiting for an analyst, oldest first. */
    @GetMapping("/queue")
    public List<AlertResponse> queue() {
        return alerts.findByCaseIdIsNullOrderByIdAsc().stream().map(AlertResponse::from).toList();
    }

    public record AlertResponse(Long id, String accountId, UUID transactionEventId, String ruleCode,
                                String reason, Money amount, Instant raisedAt, Long caseId) {

        public static AlertResponse from(Alert alert) {
            return new AlertResponse(alert.getId(), alert.getAccountId(), alert.getTransactionEventId(),
                    alert.getRuleCode(), alert.getReason(), alert.getAmount(), alert.getRaisedAt(),
                    alert.getCaseId());
        }
    }
}
```

`AlertResponse.from` is now `public`, because the case API will show each case's alerts in the same shape.

> **Simplification:** the queue returns every unworked alert in one response. A real queue is paged, filtered (by team, rule, risk) and sorted by priority as well as age.

### 5. The case service

`casework/CaseService.java`:

```java
package com.example.fraud.casework;

import com.example.fraud.alert.Alert;
import com.example.fraud.alert.AlertNotFoundException;
import com.example.fraud.alert.AlertRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

@Service
public class CaseService {

    private final FraudCaseRepository cases;
    private final AlertRepository alerts;
    private final Clock clock;

    public CaseService(FraudCaseRepository cases, AlertRepository alerts, Clock clock) {
        this.cases = cases;
        this.alerts = alerts;
        this.clock = clock;
    }

    /** Groups one or more alerts for the same account into a new case. */
    @Transactional
    public FraudCase open(List<Long> alertIds) {
        List<Alert> found = alerts.findAllById(alertIds);
        if (found.size() != new HashSet<>(alertIds).size()) {
            Long missing = alertIds.stream()
                    .filter(id -> found.stream().noneMatch(a -> a.getId().equals(id)))
                    .findFirst().orElseThrow();
            throw new AlertNotFoundException(missing);
        }
        if (found.stream().map(Alert::getAccountId).distinct().count() > 1) {
            throw new InvalidCaseException("All alerts in a case must be for the same account");
        }
        for (Alert alert : found) {
            if (alert.getCaseId() != null) {
                throw new AlertAlreadyInCaseException(alert.getId(), alert.getCaseId());
            }
        }
        FraudCase fraudCase = cases.save(FraudCase.open(found.getFirst().getAccountId(), Instant.now(clock)));
        found.forEach(alert -> alert.attachTo(fraudCase.getId()));
        return fraudCase;
    }

    @Transactional
    public FraudCase assign(Long caseId, String analyst) {
        FraudCase fraudCase = get(caseId);
        fraudCase.assignTo(analyst);
        return fraudCase;
    }

    @Transactional
    public FraudCase close(Long caseId, CaseOutcome outcome, String note, String closedBy) {
        FraudCase fraudCase = get(caseId);
        fraudCase.close(outcome, note, closedBy, Instant.now(clock));
        return fraudCase;
    }

    @Transactional(readOnly = true)
    public FraudCase get(Long caseId) {
        return cases.findById(caseId).orElseThrow(() -> new CaseNotFoundException(caseId));
    }

    @Transactional(readOnly = true)
    public List<FraudCase> withStatus(CaseStatus status) {
        return cases.findByStatusOrderByOpenedAtAsc(status);
    }

    @Transactional(readOnly = true)
    public List<Alert> alertsIn(Long caseId) {
        return alerts.findByCaseIdOrderByIdAsc(caseId);
    }
}
```

- **`open` checks three rules** before creating anything: every alert exists (`findAllById` silently leaves out missing ids, so compare the counts; the `HashSet` ignores an id listed twice), they're all for one account, and none is already in a case.
- **No `save` after `assignTo` or `close`.** The case was loaded inside the transaction, so Hibernate tracks it; changes are written automatically at commit (**dirty checking**). The same goes for `alert.attachTo(...)` in `open`.
- **`get` joins the caller's transaction** when called from `assign` or `close`, because `@Transactional` methods called from inside a transaction take part in it. Called on its own (from the controller) it runs read-only.

> **Sidebar: "check, then act" again?** `open` checks that no alert has a case, then attaches them. Two analysts opening cases for the same alert at the same instant could both pass the check, the same shape of race as guide 04. Here it's much less likely (people, not machines, at human speed), and the failure is visible and harmless (one alert in the wrong one of two cases, easily fixed). A stricter version would lock the alerts with `@Lock(PESSIMISTIC_WRITE)`, or give `alert` a `version` column. Step 8 deals with the race that *is* common: two people clicking "take this case".

### 6. The case API

`casework/CaseController.java`:

```java
package com.example.fraud.casework;

import com.example.fraud.alert.AlertController.AlertResponse;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/cases")
public class CaseController {

    private final CaseService caseService;

    public CaseController(CaseService caseService) {
        this.caseService = caseService;
    }

    @PostMapping
    public ResponseEntity<CaseResponse> open(@Valid @RequestBody OpenCaseRequest request) {
        FraudCase fraudCase = caseService.open(request.alertIds());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(fraudCase.getId()).toUri();
        return ResponseEntity.created(location).body(response(fraudCase));
    }

    @GetMapping("/{id}")
    public CaseResponse get(@PathVariable Long id) {
        return response(caseService.get(id));
    }

    @GetMapping
    public List<CaseResponse> withStatus(@RequestParam CaseStatus status) {
        return caseService.withStatus(status).stream().map(this::response).toList();
    }

    @PostMapping("/{id}/assignment")
    public CaseResponse assign(@PathVariable Long id, @Valid @RequestBody AssignRequest request) {
        return response(caseService.assign(id, request.analyst()));
    }

    @PostMapping("/{id}/closure")
    public CaseResponse close(@PathVariable Long id, @Valid @RequestBody CloseRequest request) {
        return response(caseService.close(id, request.outcome(), request.note(), request.closedBy()));
    }

    private CaseResponse response(FraudCase c) {
        List<AlertResponse> alerts = caseService.alertsIn(c.getId()).stream().map(AlertResponse::from).toList();
        return new CaseResponse(c.getId(), c.getAccountId(), c.getStatus(), c.getAssignee(), c.getOutcome(),
                c.getClosingNote(), c.getClosedBy(), c.getOpenedAt(), c.getClosedAt(), alerts);
    }

    public record OpenCaseRequest(@NotEmpty List<@NotNull Long> alertIds) {
    }

    public record AssignRequest(@NotBlank @Size(max = 50) String analyst) {
    }

    public record CloseRequest(@NotNull CaseOutcome outcome,
                               @NotBlank @Size(max = 1000) String note,
                               @NotBlank @Size(max = 50) String closedBy) {
    }

    public record CaseResponse(Long id, String accountId, CaseStatus status, String assignee,
                               CaseOutcome outcome, String closingNote, String closedBy,
                               Instant openedAt, Instant closedAt, List<AlertResponse> alerts) {
    }
}
```

- **Transitions are their own resources**: `POST /cases/{id}/assignment` and `POST /cases/{id}/closure`, not `PUT /cases/{id}` with a `status` field. Each action has its own request shape and its own validation, and a client can't sneak a status change through a generic update.
- **`List<@NotNull Long>`** validates each element: `{"alertIds": [1, null]}` is rejected.
- **A closure note is required.** "Closed" with no reason is worthless to an auditor.
- **`@RequestParam CaseStatus status`** converts the query string to the enum. An unknown value is a `400`.

### 7. Map the errors

Add these to `web/ApiExceptionHandler.java` (with their imports, plus `HttpStatus` and `ExceptionHandler`):

```java
    @ExceptionHandler({CaseNotFoundException.class, AlertNotFoundException.class})
    ProblemDetail notFound(RuntimeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Not found");
        return problem;
    }

    @ExceptionHandler(InvalidCaseTransitionException.class)
    ProblemDetail handle(InvalidCaseTransitionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Invalid case transition");
        return problem;
    }

    @ExceptionHandler(AlertAlreadyInCaseException.class)
    ProblemDetail handle(AlertAlreadyInCaseException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Alert already in a case");
        return problem;
    }

    @ExceptionHandler(InvalidCaseException.class)
    ProblemDetail handle(InvalidCaseException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setTitle("Invalid case");
        return problem;
    }

    /** Two analysts changed the same case at once; the second one should reload and try again. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handle(ObjectOptimisticLockingFailureException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The case was changed by someone else. Reload it and try again.");
        problem.setTitle("Concurrent update");
        return problem;
    }
```

The status codes follow the rule from the Concepts section:

| Problem | Status | Why |
|---|---|---|
| Unknown case or alert | `404` | It doesn't exist |
| Close an `OPEN` case, reassign a `CLOSED` one | `409` | Conflicts with the case's current state |
| Alert is already in another case | `409` | Conflicts with the alert's current state |
| Alerts from two different accounts | `422` | The request itself breaks a rule; it will never work |
| Someone else changed the case first | `409` | Conflicts with a newer version; reload and retry |

`ObjectOptimisticLockingFailureException` lives in `org.springframework.orm`. Its own message mentions SQL and entity names, which shouldn't leak to API clients, so the handler writes its own `detail`.

### 8. Unit tests for the lifecycle

`src/test/java/com/example/fraud/casework/CaseStatusTest.java`:

```java
package com.example.fraud.casework;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CaseStatusTest {

    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @CsvSource({
            "OPEN,          INVESTIGATING, true",
            "OPEN,          CLOSED,        false",
            "INVESTIGATING, CLOSED,        true",
            "INVESTIGATING, OPEN,          false",
            "CLOSED,        OPEN,          false",
            "CLOSED,        INVESTIGATING, false"
    })
    void onlyAllowsForwardMoves(CaseStatus from, CaseStatus to, boolean allowed) {
        assertThat(from.canMoveTo(to)).isEqualTo(allowed);
    }
}
```

The whole transition table as a test: six rows, six tests. JUnit converts each CSV column to the parameter's type (enum names to `CaseStatus`, `true` to `boolean`) and trims the spaces, so the table can be lined up for reading.

`src/test/java/com/example/fraud/casework/FraudCaseTest.java`:

```java
package com.example.fraud.casework;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudCaseTest {

    Instant now = Instant.parse("2026-11-02T12:00:00Z");

    @Test
    void movesFromOpenToInvestigatingToClosed() {
        FraudCase fraudCase = FraudCase.open("acc-1", now);

        fraudCase.assignTo("sam");
        fraudCase.close(CaseOutcome.FALSE_POSITIVE, "Customer confirmed by phone", "priya", now);

        assertThat(fraudCase.getStatus()).isEqualTo(CaseStatus.CLOSED);
        assertThat(fraudCase.getAssignee()).isEqualTo("sam");
        assertThat(fraudCase.getOutcome()).isEqualTo(CaseOutcome.FALSE_POSITIVE);
    }

    @Test
    void cannotCloseACaseNobodyHasInvestigated() {
        FraudCase fraudCase = FraudCase.open("acc-1", now);

        assertThatThrownBy(() -> fraudCase.close(CaseOutcome.FALSE_POSITIVE, "Looks fine", "sam", now))
                .isInstanceOf(InvalidCaseTransitionException.class)
                .hasMessage("Case null is OPEN and cannot move to CLOSED");
    }
}
```

The entity is plain Java, so it's tested without Spring. (`Case null` is honest: the case hasn't been saved, so it has no id yet.)

### 9. API tests

`src/test/java/com/example/fraud/casework/CaseApiTest.java`:

```java
package com.example.fraud.casework;

import com.example.fraud.FraudIntegrationTest;
import com.example.fraud.alert.Alert;
import com.example.fraud.transaction.Channel;
import com.example.fraud.transaction.TransactionMonitoringService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.List;
import java.util.UUID;

import static com.example.fraud.TestTransactions.payment;
import static org.assertj.core.api.Assertions.assertThat;

@FraudIntegrationTest
class CaseApiTest {

    @Autowired MockMvcTester mvc;
    @Autowired TransactionMonitoringService monitoring;

    String account;
    List<Long> alertIds;

    @BeforeEach
    void raiseTwoAlerts() {
        account = "acc-" + UUID.randomUUID();
        // LARGE_AMOUNT and NEW_PAYEE, raised directly through the service: no Kafka needed here
        alertIds = monitoring.process(payment("2500.00").account(account)
                        .to("Safe Account Ltd").by(Channel.FASTER_PAYMENT).build())
                .stream().map(Alert::getId).toList();
    }

    @Test
    void anAnalystWorksACaseFromOpenToClosed() throws Exception {
        MvcTestResult opened = openCase(alertIds);
        assertThat(opened).hasStatus(HttpStatus.CREATED);
        assertThat(opened).bodyJson().extractingPath("$.status").isEqualTo("OPEN");
        assertThat(opened).bodyJson().extractingPath("$.alerts.length()").isEqualTo(2);
        long caseId = idOf(opened);

        assertThat(post("/cases/{id}/assignment", caseId, """
                {"analyst": "sam"}
                """)).bodyJson().extractingPath("$.status").isEqualTo("INVESTIGATING");

        MvcTestResult closed = post("/cases/{id}/closure", caseId, """
                {"outcome": "FALSE_POSITIVE", "note": "Customer confirmed the payment by phone", "closedBy": "priya"}
                """);
        assertThat(closed).hasStatus(HttpStatus.OK);
        assertThat(closed).bodyJson().extractingPath("$.status").isEqualTo("CLOSED");
        assertThat(closed).bodyJson().extractingPath("$.outcome").isEqualTo("FALSE_POSITIVE");
    }

    @Test
    void alertsLeaveTheQueueWhenACaseIsOpened() {
        assertThat(mvc.get().uri("/alerts/queue")).bodyJson()
                .extractingPath("$[?(@.accountId == '%s')].id".formatted(account)).asArray().hasSize(2);

        openCase(alertIds);

        assertThat(mvc.get().uri("/alerts/queue")).bodyJson()
                .extractingPath("$[?(@.accountId == '%s')].id".formatted(account)).asArray().isEmpty();
    }

    @Test
    void refusesToCloseACaseNobodyHasInvestigated() throws Exception {
        long caseId = idOf(openCase(alertIds));

        MvcTestResult response = post("/cases/{id}/closure", caseId, """
                {"outcome": "FALSE_POSITIVE", "note": "Looks fine", "closedBy": "sam"}
                """);

        assertThat(response).hasStatus(HttpStatus.CONFLICT);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Invalid case transition");
        assertThat(response).bodyJson().extractingPath("$.detail")
                .isEqualTo("Case %d is OPEN and cannot move to CLOSED".formatted(caseId));
    }

    @Test
    void anAlertCanOnlyBeInOneCase() {
        openCase(alertIds);

        MvcTestResult second = openCase(List.of(alertIds.getFirst()));

        assertThat(second).hasStatus(HttpStatus.CONFLICT);
        assertThat(second).bodyJson().extractingPath("$.title").isEqualTo("Alert already in a case");
    }

    @Test
    void refusesToMixAccountsInOneCase() {
        Long otherAccountsAlert = monitoring.process(payment("900.00").account("acc-" + UUID.randomUUID()).build())
                .getFirst().getId();

        MvcTestResult response = openCase(List.of(alertIds.getFirst(), otherAccountsAlert));

        assertThat(response).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response).bodyJson().extractingPath("$.detail")
                .isEqualTo("All alerts in a case must be for the same account");
    }

    @Test
    void returns404ForAnUnknownAlert() {
        assertThat(openCase(List.of(999_999L))).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void aClosureNeedsANote() throws Exception {
        long caseId = idOf(openCase(alertIds));

        MvcTestResult response = post("/cases/{id}/closure", caseId, """
                {"outcome": "CONFIRMED_FRAUD", "note": "", "closedBy": "priya"}
                """);

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors.note").isEqualTo("must not be blank");
    }

    private MvcTestResult openCase(List<Long> ids) {
        return mvc.post().uri("/cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"alertIds\": %s}".formatted(ids))
                .exchange();
    }

    private MvcTestResult post(String uri, long caseId, String body) {
        return mvc.post().uri(uri, caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private long idOf(MvcTestResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
```

- **Alerts are raised by calling `monitoring.process` directly** in `@BeforeEach`. These tests are about case management, not Kafka, so they don't wait for a broker. It's the same pattern as `IdempotentProcessingTest`.
- **`"{\"alertIds\": %s}".formatted(ids)`** relies on `List.toString()` printing `[12, 13]`, which happens to be valid JSON for a list of numbers.
- **The queue test filters with a JSONPath expression**, `$[?(@.accountId == '…')].id`, because the queue also contains alerts from every other test.

### 10. Two analysts, one case

Five analysts see the same case at the top of the queue and click "take" at the same moment. Exactly one should get it. `src/test/java/com/example/fraud/casework/ConcurrentAssignmentTest.java`:

```java
package com.example.fraud.casework;

import com.example.fraud.FraudIntegrationTest;
import com.example.fraud.alert.Alert;
import com.example.fraud.transaction.TransactionMonitoringService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.fraud.TestTransactions.payment;
import static org.assertj.core.api.Assertions.assertThat;

/** Five analysts click "take this case" at the same moment. */
@FraudIntegrationTest
class ConcurrentAssignmentTest {

    @Autowired TransactionMonitoringService monitoring;
    @Autowired CaseService caseService;

    @Test
    void onlyOneAnalystGetsTheCase() throws Exception {
        Alert alert = monitoring.process(payment("900.00").account("acc-" + UUID.randomUUID()).build()).getFirst();
        Long caseId = caseService.open(List.of(alert.getId())).getId();

        CountDownLatch startingGun = new CountDownLatch(1);
        AtomicInteger assigned = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                String analyst = "analyst-" + i;
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    try {
                        caseService.assign(caseId, analyst);
                        assigned.incrementAndGet();
                    } catch (ObjectOptimisticLockingFailureException | InvalidCaseTransitionException e) {
                        refused.incrementAndGet();
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

        assertThat(unexpected).isEmpty();
        assertThat(assigned.get()).isEqualTo(1);
        assertThat(refused.get()).isEqualTo(4);
        assertThat(caseService.get(caseId).getStatus()).isEqualTo(CaseStatus.INVESTIGATING);
    }
}
```

It passes. To see why, **comment out `@Version`** on `FraudCase.version` and run it again:

```
expected: 1
 but was: 5
```

All five analysts "got" the case. Each transaction read the case while it was still `OPEN`, so `canMoveTo(INVESTIGATING)` said yes to all of them, and each wrote `INVESTIGATING` with its own assignee. The last commit won, and four analysts went off to investigate a case that the system says belongs to someone else. It's the Core ledger's **lost update**, with a case instead of a balance.

Put `@Version` back. Now Hibernate adds `AND version = ?` to the `UPDATE`. The first commit increments the version; the others' updates match no rows and fail with `ObjectOptimisticLockingFailureException`. (Any transaction that starts after the first commit reads `INVESTIGATING` and gets `InvalidCaseTransitionException` instead.) Either way, one winner, four clean refusals, and through the API each refusal is a `409`.

**Why optimistic here, when guide 04 used a pessimistic lock?** Because the conflict is rare and human: two people occasionally clicking at the same time. Making the loser reload and see "already taken by analyst-3" is exactly the right behaviour, and there's nothing to retry automatically. Guide 04's conflict was routine and machine-driven, and needed every transaction to succeed in turn.

### 11. Try it

Restart the app. Add to `requests.http`:

```http
### The work queue
GET http://localhost:8080/alerts/queue

### Open a case for alerts 1 and 2
POST http://localhost:8080/cases
Content-Type: application/json

{"alertIds": [1, 2]}

### Try to close it straight away (should be 409)
POST http://localhost:8080/cases/1/closure
Content-Type: application/json

{"outcome": "FALSE_POSITIVE", "note": "Looks fine", "closedBy": "sam"}

### Sam takes the case
POST http://localhost:8080/cases/1/assignment
Content-Type: application/json

{"analyst": "sam"}

### Priya closes it
POST http://localhost:8080/cases/1/closure
Content-Type: application/json

{"outcome": "CONFIRMED_FRAUD", "note": "Customer confirmed a caller pretending to be the bank told her to move her savings. Reported as an APP scam.", "closedBy": "priya"}

### Closed cases
GET http://localhost:8080/cases?status=CLOSED
```

Starting from an empty database (delete `data/`) and the two payments from guide 02's `requests.http` for a new account, the queue shows the two alerts with `"caseId":null`. Opening the case returns `201 Created` with a `Location` header (`http://localhost:8080/cases/1`). Closing it before anyone has taken it:

```json
{"detail":"Case 1 is OPEN and cannot move to CLOSED","instance":"/cases/1/closure","status":409,"title":"Invalid case transition"}
```

After Sam takes it and Priya closes it:

```json
{"id":1,"accountId":"acc-2001","status":"CLOSED","assignee":"sam","outcome":"CONFIRMED_FRAUD","closingNote":"Customer confirmed a caller pretending to be the bank told her to move her savings. Reported as an APP scam.","closedBy":"priya","openedAt":"2026-11-07T14:38:02.353106Z","closedAt":"2026-11-07T14:38:02.444916296Z","alerts":[{"id":1,"accountId":"acc-2001","transactionEventId":"9ba55d31-d8e1-4012-a92e-142123ae4671","ruleCode":"LARGE_AMOUNT","reason":"GBP 2500.00 is more than 5 times the average of GBP 42.50","amount":{"amount":"2500.00","currency":"GBP"},"raisedAt":"2026-11-07T14:37:59.361961Z","caseId":1},{"id":2,"accountId":"acc-2001","transactionEventId":"9ba55d31-d8e1-4012-a92e-142123ae4671","ruleCode":"NEW_PAYEE","reason":"GBP 2500.00 to new payee 'Safe Account Ltd' by FASTER_PAYMENT","amount":{"amount":"2500.00","currency":"GBP"},"raisedAt":"2026-11-07T14:37:59.361961Z","caseId":1}]}
```

Try a few more ways to break it: reassign the closed case (`409`, `Case 1 is CLOSED and cannot move to INVESTIGATING`), open another case with alert 1 (`409`, `Alert 1 is already part of case 1`), fetch `/cases/99` (`404`, `No case with id 99`), or list `/cases?status=PENDING` (`400`, `Failed to convert 'status' with value: 'PENDING'`). The queue is now empty: `[]`.

---

## Your turn

### Exercise 1: four eyes

Make `CaseService.close` refuse a closure by the case's own assignee (ignoring case) with an `InvalidCaseException` and the message `Four eyes: case <id> must be closed by someone other than <assignee>`, which the handler already turns into a `422`. Add a test to `CaseApiTest`.

<details>
<summary>Solution</summary>

In `CaseService.close`, before `fraudCase.close(...)`:

```java
        if (closedBy.equalsIgnoreCase(fraudCase.getAssignee())) {
            throw new InvalidCaseException("Four eyes: case %d must be closed by someone other than %s"
                    .formatted(caseId, fraudCase.getAssignee()));
        }
```

`closedBy.equalsIgnoreCase(...)` is called on `closedBy`, which validation guarantees isn't blank, so a `null` assignee (an `OPEN` case) is simply "not equal", and the state machine then refuses the transition with its own `409`.

In `CaseApiTest`:

```java
    @Test
    void theInvestigatorCannotCloseTheirOwnCase() throws Exception {
        long caseId = idOf(openCase(alertIds));
        post("/cases/{id}/assignment", caseId, """
                {"analyst": "sam"}
                """);

        MvcTestResult response = post("/cases/{id}/closure", caseId, """
                {"outcome": "FALSE_POSITIVE", "note": "All fine", "closedBy": "sam"}
                """);

        assertThat(response).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response).bodyJson().extractingPath("$.detail")
                .isEqualTo("Four eyes: case %d must be closed by someone other than sam".formatted(caseId));
    }
```

Through the API:

```json
{"detail":"Four eyes: case 1 must be closed by someone other than sam","instance":"/cases/1/closure","status":422,"title":"Invalid case"}
```

It's a `422`, not a `409`: the case is in the right state, but *this request* (closure by its own investigator) breaks a rule and will never be allowed. Remember the simplification from the Concepts section: with names in the request body, this rule is only as strong as your clients' honesty. With real authentication, `closedBy` comes from the logged-in user.

</details>

### Exercise 2: my cases

An analyst wants to see the cases they're working on. Make `GET /cases?status=INVESTIGATING&assignee=sam` return only Sam's investigating cases, keeping `GET /cases?status=…` working as before. Add a test.

<details>
<summary>Solution</summary>

`FraudCaseRepository`:

```java
    List<FraudCase> findByStatusAndAssigneeOrderByOpenedAtAsc(CaseStatus status, String assignee);
```

`CaseService`:

```java
    @Transactional(readOnly = true)
    public List<FraudCase> withStatusAssignedTo(CaseStatus status, String assignee) {
        return cases.findByStatusAndAssigneeOrderByOpenedAtAsc(status, assignee);
    }
```

`CaseController`, replacing `withStatus`:

```java
    @GetMapping
    public List<CaseResponse> withStatus(@RequestParam CaseStatus status,
                                         @RequestParam(required = false) String assignee) {
        List<FraudCase> found = assignee == null
                ? caseService.withStatus(status)
                : caseService.withStatusAssignedTo(status, assignee);
        return found.stream().map(this::response).toList();
    }
```

`CaseApiTest`:

```java
    @Test
    void listsAnAnalystsOwnCases() throws Exception {
        long caseId = idOf(openCase(alertIds));
        String analyst = "analyst-" + UUID.randomUUID();
        post("/cases/{id}/assignment", caseId, """
                {"analyst": "%s"}
                """.formatted(analyst));

        assertThat(mvc.get().uri("/cases?status=INVESTIGATING&assignee={a}", analyst))
                .bodyJson().extractingPath("$[*].id").asArray().containsExactly((int) caseId);
    }
```

The analyst's name is random so that other tests' cases (all those assigned to `sam`) can't appear. JSONPath reads small numbers as `Integer`, hence `(int) caseId`.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 59, Failures: 0, Errors: 0, Skipped: 0
```

That's 18 new tests: 6 in `CaseStatusTest`, 2 in `FraudCaseTest`, 9 in `CaseApiTest` (2 of them from the exercises) and 1 in `ConcurrentAssignmentTest`, on top of guide 04's 41. Run it two or three times: both concurrency tests must pass every time.

The finished project:

```
fraud-monitoring/
├── compose.yaml
├── pom.xml
├── requests.http
└── src/
    ├── main/
    │   ├── java/com/example/fraud/
    │   │   ├── FraudMonitoringApplication.java
    │   │   ├── alert/        Alert, AlertController, AlertNotFoundException, AlertPublisher,
    │   │   │                 AlertRaised, AlertRepository
    │   │   ├── casework/     AlertAlreadyInCaseException, CaseController, CaseNotFoundException,
    │   │   │                 CaseOutcome, CaseService, CaseStatus, FraudCase, FraudCaseRepository,
    │   │   │                 InvalidCaseException, InvalidCaseTransitionException
    │   │   ├── config/       ClockConfig, KafkaConfig, Topics
    │   │   ├── money/        Money
    │   │   ├── rules/        AccountHistory, LargeAmountRule, NewPayeeRule, PastTransaction, Rule,
    │   │   │                 RuleEngine, RuleHit, RulesConfig, RulesProperties, StructuringRule,
    │   │   │                 UnusualHourRule, VelocityRule
    │   │   ├── simulator/    SimulatorController
    │   │   ├── transaction/  AccountTransaction, AccountTransactionRepository, Channel,
    │   │   │                 MonitoredAccount, MonitoredAccountRepository, ProcessedEvent,
    │   │   │                 ProcessedEventRepository, TransactionController, TransactionEvent,
    │   │   │                 TransactionListener, TransactionMonitoringService,
    │   │   │                 UnsupportedTransactionException
    │   │   └── web/          ApiExceptionHandler
    │   └── resources/
    │       ├── application.properties
    │       └── db/migration/
    │           ├── V1__create_account_transaction.sql
    │           ├── V2__create_alert.sql
    │           ├── V3__create_processed_event.sql
    │           ├── V4__create_monitored_account.sql
    │           └── V5__create_fraud_case.sql
    └── test/java/com/example/fraud/
        ├── DeadLetters.java, FraudIntegrationTest.java, TestTransactions.java,
        │   TestcontainersConfiguration.java
        ├── alert/        AlertPublishingTest, MonitoringFlowTest
        ├── casework/     CaseApiTest, CaseStatusTest, ConcurrentAssignmentTest, FraudCaseTest
        ├── money/        MoneyTest
        ├── rules/        LargeAmountRuleTest, NewPayeeRuleTest, RuleEngineTest, RulesConfigTest,
        │                 StructuringRuleTest, UnusualHourRuleTest, VelocityRuleTest
        ├── simulator/    SimulatorApiTest
        └── transaction/  ConcurrentProcessingTest, DuplicateDeliveryTest, IdempotentProcessingTest,
                          PoisonMessageTest, TransactionEventTest, TransactionListenerTest
```

---

## Common errors

**`Schema validation: missing column [case_id] in table [alert]`**
`Alert` has the new `caseId` field but `V5` hasn't run. Check the file name has two underscores, `V5__create_fraud_case.sql`, and restart.

**A check constraint violation naming `CK_FRAUD_CASE_CLOSED`**
Something saved a `CLOSED` case without an outcome, or an open case with one. Code has changed `status` without going through `close`: look for a setter someone added.

**`expected: 1 but was: 5` from `ConcurrentAssignmentTest`**
`@Version` is missing from `FraudCase`, so concurrent assignments overwrite each other. See step 10.

**`ConcurrentAssignmentTest` reports an unexpected `ObjectOptimisticLockingFailureException` as an error, or the API returns `500` for it**
The test's `catch` or the `ApiExceptionHandler` method is missing. A version conflict is an expected outcome for the loser, not a server error.

**`Failed to convert 'status' with value: 'PENDING'`**
The `status` query parameter isn't a `CaseStatus` constant. Values are case-sensitive: `OPEN`, `INVESTIGATING`, `CLOSED`.

**`Case 1 is OPEN and cannot move to CLOSED`**
Working as intended: assign the case before closing it.

**The alert queue test sees alerts from other tests**
Every integration test shares one database. Filter by the test's own account, as the JSONPath filter does.

---

## Recap

- Alerts wait in a **queue**, are grouped into **cases**, investigated, and **closed with an outcome**. The closed case is the bank's evidence of what it did.
- An analyst who suspects money laundering **refers it to the nominated officer**, who decides whether to submit a **SAR** to the NCA. **Tipping off** rules mean AML case information must be kept away from customer-facing systems.
- The **four-eyes principle** puts two people on sensitive decisions. It only means something when identities come from authentication.
- Model the lifecycle as a **state machine**: an enum with `canMoveTo`, an exhaustive switch, and an entity whose only state changes are named actions.
- **`409`** for "conflicts with the current state", **`422`** for "this request breaks a rule".
- **`@Version`** turns concurrent human edits into one winner and clean refusals. Use **optimistic** locking for rare human conflicts, **pessimistic** locking for routine machine contention.

### Interview angle

<details>
<summary>"An alert fires on a customer's account. Walk me through what happens."</summary>

The alert joins a work queue. An analyst triages it, usually grouping it with other alerts for the same customer into a case, and investigates: the account's history and KYC profile, previous cases, and for fraud often contacting the customer. The case is closed with an outcome and a reason. A false positive needs no further action (and is useful feedback for tuning the rules). Confirmed fraud leads to protecting the customer and trying to recover funds. Suspected money laundering is referred internally to the nominated officer, who decides whether to submit a SAR to the NCA. Throughout, the bank must avoid tipping off the customer about any money laundering investigation.

</details>

<details>
<summary>"How would you implement a workflow with states in Spring?"</summary>

Keep it in the domain. An enum of states with a method saying which transitions are allowed (an exhaustive switch, so new states can't be forgotten), an entity with no status setter whose named methods (`assignTo`, `close`) check the transition and set the related data together, and database check constraints as a backstop. Expose transitions as their own endpoints with their own validation. Map invalid transitions to `409` with a `ProblemDetail`. Add `@Version` so concurrent transitions can't both succeed. For complex workflows there are libraries such as Spring Statemachine, but a small enum is easier to read and test.

</details>

<details>
<summary>"What's the four-eyes principle, and how would you enforce it?"</summary>

Two different people for one sensitive action: one makes it, another checks and approves it (maker-checker). Enforce it on the server, using the authenticated identities of both people, never names sent by the client: store who did each step, and reject an approval by the same person who made the change. Record both identities in the audit trail. Typical uses in a bank are payments above a limit, changes to customer details, reference data or rule configuration, and closing investigations.

</details>

<details>
<summary>"When would you choose optimistic locking over pessimistic locking?"</summary>

Optimistic (`@Version`) when conflicts are rare and the right response to losing is to tell the user, as when two analysts edit the same case. It holds no locks and costs almost nothing. Pessimistic (`SELECT … FOR UPDATE`) when conflicts are routine and every transaction should succeed in turn, as with many events for one account or many debits from one balance, where optimistic locking would mostly produce failures and retries.

</details>

### Further reading

- [Proceeds of Crime Act 2002, Part 7](https://www.legislation.gov.uk/ukpga/2002/29/part/7): the money laundering offences, including failure to disclose (section 330) and tipping off (section 333A)
- [National Crime Agency: Suspicious Activity Reports](https://www.nationalcrimeagency.gov.uk/what-we-do/crime-threats/money-laundering-and-illicit-finance/suspicious-activity-reports)
- [FCA Financial Crime Guide (FCG)](https://www.handbook.fca.org.uk/handbook/FCG/)
- [JMLSG guidance](https://www.jmlsg.org.uk/guidance/current-guidance/), including internal reporting and the nominated officer
- [RFC 9457: Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457)

---

## You've built a fraud monitoring system

Across five guides you've built the event-driven core of a bank's transaction monitoring:

| Guide | Banking idea | Java/Spring skill |
|---|---|---|
| 01 | Fraud vs AML monitoring, APP fraud | Kafka topics, partitions and keys, `@KafkaListener`, Docker Compose, Testcontainers |
| 02 | Rules, alerts, false positives | A sealed rule interface, pure functions, validated `@ConfigurationProperties`, event time |
| 03 | Never miss a payment, never count one twice | Idempotent consumers, `Persistable`, `DefaultErrorHandler`, dead-letter topics |
| 04 | Stateful rules need one account at a time | Write skew, row locks, create-if-absent-then-lock, `TransactionTemplate` |
| 05 | Cases, outcomes, SARs, four eyes | State machines, `409` vs `422`, `@Version` for concurrent human edits |

**Where to go next:**

- **The [Customer onboarding tutorial](../customer-onboarding/README.md)** covers the other half of AML: KYC checks when a customer joins, and the customer risk ratings that monitoring rules would use.
- **Extend this system yourself:** a transactional outbox for `AlertRaised` events; Spring Security so `closedBy` comes from the logged-in analyst; a case history table recording every transition; a scoring step that combines rule hits into one risk score; or switching the database to PostgreSQL in `compose.yaml` and checking every migration and test still passes.
