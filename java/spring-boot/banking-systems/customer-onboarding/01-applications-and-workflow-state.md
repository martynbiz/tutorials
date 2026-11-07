# 01: Applications and Workflow State

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- What a bank must do before it opens a current account for someone: **KYC** and **customer due diligence**, and the law behind them
- How to model a business process as a **workflow persisted as state**: a status column, plus explicit rules about which status may follow which
- How to make illegal moves **impossible** rather than merely unlikely, and report them as `409 Conflict`
- Why public-facing records get **UUID** ids instead of 1, 2, 3
- Why services ask an injected **`Clock`** for the time instead of calling `Instant.now()`
- How to test every legal and illegal transition in a few lines with **parameterised tests**

By the end you'll have an API that receives account-opening applications, refuses applicants under 18, lets an applicant withdraw, and rejects any status change the workflow doesn't allow. A test suite proves it.

---

## Concepts

### How it works in a real bank

Before a UK bank opens a current account, the law requires it to know who it's dealing with. In the industry this is called [KYC](../glossary.md#kyc), *Know Your Customer*. The legal term is **customer due diligence** (CDD), and the law is the [Money Laundering, Terrorist Financing and Transfer of Funds (Information on the Payer) Regulations 2017](https://www.legislation.gov.uk/uksi/2017/692/contents), usually shortened to the **MLRs** or "MLR 2017". They are part of the UK's [AML](../glossary.md#aml) (anti-money laundering) regime.

[Regulation 28](https://www.legislation.gov.uk/uksi/2017/692/regulation/28) sets out the core CDD measures. For a personal current account, the essentials are:

1. **Identify the customer:** collect who they say they are (name, date of birth, address).
2. **Verify that identity** "on the basis of documents or information obtained from a reliable source which is independent of the customer". That can be documents, such as a passport, or information obtained by an **electronic identification process** (regulation 28(19)), such as a check against credit reference agency data.
3. **Understand the purpose and intended nature of the relationship:** what the account is for, and roughly how much money will go through it.

Banks also screen every applicant against **sanctions lists** and for **politically exposed persons** (PEPs), and give each customer a **risk rating** that decides how much due diligence they get. Guide 02 builds those checks. Banks follow the regulations through detailed industry guidance from the [Joint Money Laundering Steering Group (JMLSG)](https://www.jmlsg.org.uk/guidance/current-guidance/), which is approved by HM Treasury, and are supervised by the FCA.

An account-opening journey therefore looks roughly like this:

```
  applicant fills in the form ──► checks run ──► automatic decision ──► account opened
         (app, web, branch)       (identity,       approve / refer /
                                   address,         decline
                                   sanctions,          │
                                   PEP)                ▼ refer
                                                  a person reviews it
```

Most applications are decided automatically in seconds. Some are **referred** to a person in an onboarding or financial-crime operations team, for example because the electronic identity check found no match and the applicant needs to provide documents. That review might take hours or days. The application has to survive all of it: restarts, deployments, a provider being down, two members of staff looking at it at once.

That's why onboarding systems store the application's **status** in the database and treat the status as the single source of truth about where the process is. Larger banks often use a workflow or business process management (BPM) product for this. The underlying idea is the one you'll build: **a persisted state, and rules about how it may change.**

### The onboarding workflow

This is the workflow you'll build across the tutorial:

```
                 ┌──────────► WITHDRAWN
                 │
  RECEIVED ──────┴──► CHECKS_IN_PROGRESS ──┬──► APPROVED
                                           ├──► DECLINED
                                           └──► REFERRED ──┬──► APPROVED
                                                           └──► DECLINED
```

- **RECEIVED:** the application is stored, and nothing has been checked yet. The applicant can still withdraw.
- **CHECKS_IN_PROGRESS:** the checks are running. Guide 02 makes this step resumable if a provider fails part-way through.
- **REFERRED:** a person must look at it. Guide 03 adds the review queue.
- **APPROVED, DECLINED, WITHDRAWN:** final. Nothing happens after these.

Guide 03 adds one more status, for applications that need a second approver.

### State as data, transitions as rules

The tempting implementation is a `status` column with a public setter. Then any code anywhere can write `application.setStatus(APPROVED)`, including code that skipped the checks. In a regulated process, "how did this get approved without a sanctions check?" is a question nobody wants to answer.

Instead you'll give the status enum itself the rules. Each status lists the statuses that may come next:

```java
public Set<ApplicationStatus> allowedNext() {
    return switch (this) {
        case RECEIVED -> Set.of(CHECKS_IN_PROGRESS, WITHDRAWN);
        ...
    };
}
```

The entity has exactly one method that changes the status, `moveTo(next)`. It checks the rule and throws if the move isn't allowed. A **switch expression** over an enum must cover every constant, so when you add a new status the compiler makes you decide where it can go next.

An illegal move is reported to the client as **`409 Conflict`**: the request was understood, but it conflicts with the resource's current state, such as withdrawing an application that's already been withdrawn.

### Why UUIDs, not 1, 2, 3

Applicants will see their application id, for example in a link to check its progress. Sequential ids have two problems in a public API:

- **They leak information.** If your application is number 48,213 and next week a friend's is 51,002, you know roughly how many people apply each week.
- **They invite guessing.** If `/applications/48213` works, a bug in your authorisation checks means `/applications/48212` is someone else's personal data. A random UUID such as `7e0b7c52-1547-411b-aa08-34aecad0d22a` can't be guessed.

Random ids are no substitute for access control (this tutorial leaves authentication out, like the rest of the series), but they're a cheap extra layer, and a common choice for anything customer-facing.

### Inject the clock

The application needs "now" for timestamps and "today" for the age check. If services call `Instant.now()` directly, a test can't control the date. You'd never be able to test "turns 18 today", and in guide 05 you'll need to jump five years ahead to test data retention.

So there is one `Clock` bean, and everything that needs the time asks it. Production uses `Clock.systemUTC()`. Tests can swap in a clock they control.

---

## Steps

### 1. Generate the project

Go to [start.spring.io](https://start.spring.io) and choose:

| Setting | Value |
|---|---|
| Project | Maven |
| Language | Java |
| Spring Boot | 4.1.2 (or the latest 4.1.x) |
| Group | `com.example` |
| Artifact | `customer-onboarding` |
| Package name | `com.example.onboarding` |
| Packaging | Jar |
| Java | 25 |

Add these dependencies:

| Dependency | Why |
|---|---|
| **Spring Web** | REST controllers (it pulls in `spring-boot-starter-webmvc`) |
| **Spring Data JPA** | Entities and repositories |
| **Flyway Migration** | Versioned SQL migrations |
| **H2 Database** | The embedded database |
| **Validation** | `@Valid`, `@NotBlank`, `@Email`, `@Past` |
| **SpringDoc OpenAPI** | Swagger UI, for trying the API in a browser |

Click **Generate** and unzip the download so the project folder ends up at:

```
tutorials/workspace/banking-systems/customer-onboarding/
```

Open it in IntelliJ with **File → Open…**, choose the `customer-onboarding` folder (the one containing `pom.xml`), and trust the project. Check **File → Project Structure → Project → SDK** is set to a Java 25 JDK.

Delete the generated test `src/test/java/com/example/onboarding/CustomerOnboardingApplicationTests.java`. You'll replace it with better tests.

### 2. Configure the application

Replace `src/main/resources/application.properties` with:

```properties
spring.application.name=customer-onboarding

# H2 database stored in ./data/onboarding.mv.db
spring.datasource.url=jdbc:h2:file:./data/onboarding
spring.datasource.username=sa
spring.h2.console.enabled=true

# Flyway owns the schema; Hibernate only checks it matches the entities
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
```

- `username=sa`: Spring Boot only defaults the username to `sa` for *in-memory* H2. For a file database it would otherwise use a blank username, and the H2 console (which suggests `sa`) would refuse to log in.
- `ddl-auto=validate`: Hibernate never changes the schema. It checks at start-up that your entities match the tables Flyway created, and stops the app if they don't.
- `open-in-view=false`: the database session closes when the service method's transaction ends, so forgotten lazy loading fails loudly instead of quietly running queries from the controller.

Add `data/` to the project's `.gitignore`:

```
### Local database ###
data/
```

This matters more than usual here. The database will hold people's names, dates of birth and addresses. Even test data that looks real shouldn't end up in git.

### 3. The workflow states

Create the package `com.example.onboarding.application` and add `application/ApplicationStatus.java`:

```java
package com.example.onboarding.application;

import java.util.Set;

/**
 * Where an account-opening application is in the onboarding workflow.
 * Each status lists the statuses it may move to next. Anything else is illegal.
 */
public enum ApplicationStatus {
    RECEIVED,
    CHECKS_IN_PROGRESS,
    REFERRED,
    APPROVED,
    DECLINED,
    WITHDRAWN;

    public Set<ApplicationStatus> allowedNext() {
        return switch (this) {
            case RECEIVED -> Set.of(CHECKS_IN_PROGRESS, WITHDRAWN);
            case CHECKS_IN_PROGRESS -> Set.of(APPROVED, REFERRED, DECLINED);
            case REFERRED -> Set.of(APPROVED, DECLINED);
            case APPROVED, DECLINED, WITHDRAWN -> Set.of();
        };
    }

    public boolean canMoveTo(ApplicationStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isFinal() {
        return allowedNext().isEmpty();
    }
}
```

The whole workflow diagram from the Concepts section is in that one `switch`. It's a **switch expression** (Java 14+): it returns a value, and because there's no `default`, the compiler checks that every constant is handled. Add a status without deciding where it can go, and the code won't compile.

`isFinal()` is derived from the rules rather than stored separately, so the two can never disagree.

Now test it. Create `src/test/java/com/example/onboarding/application/ApplicationStatusTest.java`:

```java
package com.example.onboarding.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationStatusTest {

    @ParameterizedTest
    @CsvSource({
            "RECEIVED,           CHECKS_IN_PROGRESS",
            "RECEIVED,           WITHDRAWN",
            "CHECKS_IN_PROGRESS, APPROVED",
            "CHECKS_IN_PROGRESS, REFERRED",
            "CHECKS_IN_PROGRESS, DECLINED",
            "REFERRED,           APPROVED",
            "REFERRED,           DECLINED"
    })
    void allowsTheWorkflowsOwnSteps(ApplicationStatus from, ApplicationStatus to) {
        assertThat(from.canMoveTo(to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "RECEIVED,           APPROVED",
            "CHECKS_IN_PROGRESS, WITHDRAWN",
            "REFERRED,           CHECKS_IN_PROGRESS",
            "DECLINED,           APPROVED",
            "APPROVED,           DECLINED"
    })
    void rejectsShortcutsAndU_turns(ApplicationStatus from, ApplicationStatus to) {
        assertThat(from.canMoveTo(to)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ApplicationStatus.class, names = {"APPROVED", "DECLINED", "WITHDRAWN"})
    void finalStatusesGoNowhere(ApplicationStatus status) {
        assertThat(status.isFinal()).isTrue();
        assertThat(status.allowedNext()).isEmpty();
    }

    @Test
    void noStatusCanMoveToItself() {
        for (ApplicationStatus status : ApplicationStatus.values()) {
            assertThat(status.canMoveTo(status)).as(status.name()).isFalse();
        }
    }
}
```

- **`@ParameterizedTest` with `@CsvSource`** runs the method once per line. JUnit converts the text `RECEIVED` to the enum constant for you, and trims the padding, so you can line the columns up.
- **`@EnumSource`** runs once per listed constant.
- `RECEIVED → APPROVED` is the one that matters most: an application must never be approved without its checks.

Run it: click the green arrow next to `class ApplicationStatusTest` → **Run 'ApplicationStatusTest'**. IntelliJ shows each parameterised case as its own row: 16 green ticks.

**Terminal alternative:**

```bash
./mvnw test -Dtest=ApplicationStatusTest
```

### 4. The application table and entity

Create `src/main/resources/db/migration/V1__create_application.sql`:

```sql
CREATE TABLE application (
    id                        UUID                     PRIMARY KEY,
    status                    VARCHAR(30)              NOT NULL,
    given_name                VARCHAR(100)             NOT NULL,
    family_name               VARCHAR(100)             NOT NULL,
    date_of_birth             DATE                     NOT NULL,
    email                     VARCHAR(254)             NOT NULL,
    address_line1             VARCHAR(200)             NOT NULL,
    postcode                  VARCHAR(10)              NOT NULL,
    expected_monthly_deposits VARCHAR(20)              NOT NULL,
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    status_changed_at         TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX ix_application_status ON application (status);
```

The SQL is portable: `UUID` is a native column type in both H2 and PostgreSQL. The index on `status` is for the review queue in guide 03, which asks "which applications are `REFERRED`?".

> **Sidebar: these columns hold personal data in plain text.** Name, date of birth, email and address are [PII](../glossary.md#pii), personally identifiable information. Right now anyone who can read the database, or a backup of it, can read them. Guide 04 encrypts them, and guide 05 decides how long to keep them. Building the workflow first keeps each guide focused on one idea.

The applicant's details travel together, so give them a record. `application/Applicant.java`:

```java
package com.example.onboarding.application;

import java.time.LocalDate;

/** What the applicant told us about themselves. */
public record Applicant(
        String givenName,
        String familyName,
        LocalDate dateOfBirth,
        String email,
        String addressLine1,
        String postcode,
        ExpectedMonthlyDeposits expectedMonthlyDeposits) {
}
```

`expectedMonthlyDeposits` is the "intended nature of the relationship" from regulation 28. Application forms usually ask it in bands rather than as an exact amount. `application/ExpectedMonthlyDeposits.java`:

```java
package com.example.onboarding.application;

/** How much the applicant expects to pay in each month, in bands, as banks usually ask it. */
public enum ExpectedMonthlyDeposits {
    UP_TO_1K,
    FROM_1K_TO_5K,
    OVER_5K
}
```

The exception for illegal moves, `application/IllegalTransitionException.java`:

```java
package com.example.onboarding.application;

import java.util.UUID;

public class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(UUID id, ApplicationStatus from, ApplicationStatus to) {
        super("Application %s cannot move from %s to %s".formatted(id, from, to));
    }
}
```

And the entity, `application/Application.java`:

```java
package com.example.onboarding.application;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "application")
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ApplicationStatus status;

    @Column(name = "given_name", nullable = false, length = 100)
    private String givenName;

    @Column(name = "family_name", nullable = false, length = 100)
    private String familyName;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "address_line1", nullable = false, length = 200)
    private String addressLine1;

    @Column(nullable = false, length = 10)
    private String postcode;

    @Enumerated(EnumType.STRING)
    @Column(name = "expected_monthly_deposits", nullable = false, length = 20)
    private ExpectedMonthlyDeposits expectedMonthlyDeposits;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    protected Application() {
        // for JPA
    }

    private Application(Applicant applicant, Instant now) {
        this.status = ApplicationStatus.RECEIVED;
        this.givenName = applicant.givenName();
        this.familyName = applicant.familyName();
        this.dateOfBirth = applicant.dateOfBirth();
        this.email = applicant.email();
        this.addressLine1 = applicant.addressLine1();
        this.postcode = applicant.postcode();
        this.expectedMonthlyDeposits = applicant.expectedMonthlyDeposits();
        this.createdAt = now;
        this.statusChangedAt = now;
    }

    public static Application receive(Applicant applicant, Instant now) {
        return new Application(applicant, now);
    }

    /** The only way the status changes. Illegal moves throw instead of happening. */
    public void moveTo(ApplicationStatus next, Instant now) {
        if (!status.canMoveTo(next)) {
            throw new IllegalTransitionException(id, status, next);
        }
        this.status = next;
        this.statusChangedAt = now;
    }

    public Applicant getApplicant() {
        return new Applicant(givenName, familyName, dateOfBirth, email,
                addressLine1, postcode, expectedMonthlyDeposits);
    }

    public UUID getId() {
        return id;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStatusChangedAt() {
        return statusChangedAt;
    }
}
```

The design choices:

- **No setters, and one way to change the status.** `moveTo` checks the rule *before* changing anything, so a refused move leaves the entity exactly as it was. Every caller gets the workflow rules for free; there's no way round them.
- **`@GeneratedValue(strategy = GenerationType.UUID)`** makes Hibernate generate a random UUID just before the insert. Spring Data sees a `null` id on a new entity and correctly calls `persist`. (See **Common errors** for what happens if you leave the annotation off.)
- **The time is passed in** (`Instant now`) rather than read inside the entity. Entities aren't Spring beans, so they can't have a `Clock` injected. Passing the time in keeps them easy to test.
- **`statusChangedAt`** records when the application arrived in its current status. The review queue in guide 03 uses it to show the longest-waiting applications first.
- **`@Enumerated(EnumType.STRING)`** stores `"RECEIVED"`, not `0`. With the default `ORDINAL`, inserting a status in the middle of the enum (which guide 03 does) would silently change the meaning of every row.

> **In older codebases:** you'll often see `@Data` or `@Setter` from Lombok on entities like this, and a workflow that's really a series of `if (status.equals("PENDING"))` checks spread across services, with statuses as `String`s. It works until someone adds a code path that forgets one of the checks. See the [legacy cheat sheet](../legacy-cheatsheet.md#lombok).

### 5. Unit-test the entity

`src/test/java/com/example/onboarding/application/ApplicationTest.java`:

```java
package com.example.onboarding.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationTest {

    static final Instant NOW = Instant.parse("2026-11-07T09:00:00Z");

    static Applicant alice() {
        return new Applicant("Alice", "Smith", LocalDate.of(1990, 5, 17), "alice@example.com",
                "1 High Street", "G1 1XQ", ExpectedMonthlyDeposits.FROM_1K_TO_5K);
    }

    @Test
    void startsAsReceived() {
        Application application = Application.receive(alice(), NOW);

        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.RECEIVED);
        assertThat(application.getCreatedAt()).isEqualTo(NOW);
        assertThat(application.getApplicant()).isEqualTo(alice());
    }

    @Test
    void movesAlongALegalPathAndRecordsWhen() {
        Application application = Application.receive(alice(), NOW);
        Instant later = NOW.plusSeconds(5);

        application.moveTo(ApplicationStatus.CHECKS_IN_PROGRESS, later);

        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.CHECKS_IN_PROGRESS);
        assertThat(application.getStatusChangedAt()).isEqualTo(later);
    }

    @Test
    void refusesAnIllegalMoveAndStaysWhereItWas() {
        Application application = Application.receive(alice(), NOW);

        assertThatThrownBy(() -> application.moveTo(ApplicationStatus.APPROVED, NOW))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("cannot move from RECEIVED to APPROVED");
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.RECEIVED);
    }
}
```

No Spring, no database: these run in milliseconds. Records get `equals` generated from their components, which is why `getApplicant()` can be compared with `isEqualTo(alice())`.

### 6. One clock for the whole application

Create `config/ClockConfig.java`:

```java
package com.example.onboarding.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** The one place the application gets "now" from. Tests can replace it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

UTC, because banks record when things happened as absolute instants. Turning an instant into "the 7th of November" is a presentation decision, made at the edges.

### 7. Repository and service

`application/ApplicationRepository.java`:

```java
package com.example.onboarding.application;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {
}
```

`application/ApplicationNotFoundException.java`:

```java
package com.example.onboarding.application;

import java.util.UUID;

public class ApplicationNotFoundException extends RuntimeException {

    public ApplicationNotFoundException(UUID id) {
        super("No application with id " + id);
    }
}
```

`application/ApplicationService.java`:

```java
package com.example.onboarding.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class ApplicationService {

    private final ApplicationRepository applications;
    private final Clock clock;

    public ApplicationService(ApplicationRepository applications, Clock clock) {
        this.applications = applications;
        this.clock = clock;
    }

    @Transactional
    public Application receive(Applicant applicant) {
        return applications.save(Application.receive(applicant, clock.instant()));
    }

    @Transactional(readOnly = true)
    public Application get(UUID id) {
        return applications.findById(id).orElseThrow(() -> new ApplicationNotFoundException(id));
    }

    @Transactional
    public Application withdraw(UUID id) {
        Application application = get(id);
        application.moveTo(ApplicationStatus.WITHDRAWN, clock.instant());
        return application;
    }
}
```

`withdraw` never calls `save`. The application was loaded inside a transaction, so Hibernate is tracking it. When the transaction commits, Hibernate notices the changed fields (**dirty checking**) and writes an `UPDATE`. If `moveTo` throws instead, the exception rolls the transaction back and nothing is written.

### 8. The controller

`application/ApplicationController.java`:

```java
package com.example.onboarding.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/applications")
public class ApplicationController {

    private final ApplicationService applicationService;

    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping
    public ResponseEntity<ApplicationResponse> apply(@Valid @RequestBody ApplyRequest request) {
        Application application = applicationService.receive(request.toApplicant());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(application.getId())
                .toUri();
        return ResponseEntity.created(location).body(ApplicationResponse.from(application));
    }

    @GetMapping("/{id}")
    public ApplicationResponse get(@PathVariable UUID id) {
        return ApplicationResponse.from(applicationService.get(id));
    }

    @PostMapping("/{id}/withdrawal")
    public ApplicationResponse withdraw(@PathVariable UUID id) {
        return ApplicationResponse.from(applicationService.withdraw(id));
    }

    public record ApplyRequest(
            @NotBlank @Size(max = 100) String givenName,
            @NotBlank @Size(max = 100) String familyName,
            @NotNull @Past LocalDate dateOfBirth,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 200) String addressLine1,
            @NotBlank @Pattern(regexp = "[A-Za-z]{1,2}\\d[A-Za-z\\d]? ?\\d[A-Za-z]{2}",
                    message = "must be a UK postcode") String postcode,
            @NotNull ExpectedMonthlyDeposits expectedMonthlyDeposits) {

        Applicant toApplicant() {
            return new Applicant(givenName.strip(), familyName.strip(), dateOfBirth, email.strip(),
                    addressLine1.strip(), postcode.strip().toUpperCase(), expectedMonthlyDeposits);
        }
    }

    public record ApplicationResponse(
            UUID id,
            ApplicationStatus status,
            Applicant applicant,
            Instant createdAt,
            Instant statusChangedAt) {

        static ApplicationResponse from(Application application) {
            return new ApplicationResponse(application.getId(), application.getStatus(),
                    application.getApplicant(), application.getCreatedAt(), application.getStatusChangedAt());
        }
    }
}
```

- **Withdrawal is a `POST` to `/withdrawal`**, not a `PATCH` that sets `status`. Clients ask for a business action, and the server decides whether it's allowed. An API that lets clients write `{"status": "APPROVED"}` has a workflow in name only.
- **`@Past`** rejects a date of birth in the future. The postcode pattern is a simple format check, not proof that the postcode exists; the address check in guide 02 does that.
- **`toApplicant()` tidies the input:** strips stray spaces and upper-cases the postcode, so `g1 1xq` is stored as `G1 1XQ`.
- **`@PathVariable UUID id`:** Spring converts the path segment to a `UUID`, and returns `400` for anything that isn't one.

> **Sidebar: this response shows everything.** The response echoes the applicant's date of birth and address back. That's convenient while you build the workflow, but it isn't **data minimisation**, a GDPR principle. Guide 05 cuts the applicant-facing response down to what the applicant actually needs.

### 9. Run it

Run `CustomerOnboardingApplication` with the green arrow next to `main`.

**Terminal alternative:**

```bash
./mvnw spring-boot:run
```

Watch for Flyway:

```
Migrating schema "PUBLIC" to version "1 - create application"
Successfully applied 1 migration to schema "PUBLIC", now at version v1
```

Create `requests.http` in the project root. You'll add to it in every guide:

```http
### Apply for an account
POST http://localhost:8080/applications
Content-Type: application/json

{
  "givenName": "Alice",
  "familyName": "Smith",
  "dateOfBirth": "1990-05-17",
  "email": "alice@example.com",
  "addressLine1": "1 High Street",
  "postcode": "g1 1xq",
  "expectedMonthlyDeposits": "FROM_1K_TO_5K"
}

> {% client.global.set("applicationId", response.body.id); %}

### Get the application
GET http://localhost:8080/applications/{{applicationId}}

### Withdraw it (run twice: the second is a 409)
POST http://localhost:8080/applications/{{applicationId}}/withdrawal
```

**(Ultimate)** IntelliJ's HTTP Client shows a green ▶ beside each `###` request. The `> {% … %}` block is a response handler: it saves the new id in a variable so the next requests can use `{{applicationId}}`.

**Free edition / terminal alternatives:** open `http://localhost:8080/swagger-ui.html`, expand **POST /applications**, click **Try it out**, paste the JSON and **Execute**. Or use curl:

```bash
curl -i -X POST localhost:8080/applications -H 'Content-Type: application/json' -d '{
  "givenName": "Alice", "familyName": "Smith", "dateOfBirth": "1990-05-17",
  "email": "alice@example.com", "addressLine1": "1 High Street",
  "postcode": "g1 1xq", "expectedMonthlyDeposits": "FROM_1K_TO_5K"}'
```

Copy the id from the response and use it in place of `{{applicationId}}`.

You get `201 Created`, a `Location` header, and:

```json
{
  "id": "7e0b7c52-1547-411b-aa08-34aecad0d22a",
  "status": "RECEIVED",
  "applicant": {
    "givenName": "Alice",
    "familyName": "Smith",
    "dateOfBirth": "1990-05-17",
    "email": "alice@example.com",
    "addressLine1": "1 High Street",
    "postcode": "G1 1XQ",
    "expectedMonthlyDeposits": "FROM_1K_TO_5K"
  },
  "createdAt": "2026-11-07T09:34:59.853680683Z",
  "statusChangedAt": "2026-11-07T09:34:59.853680683Z"
}
```

Withdraw it, then withdraw it again. The first returns the application with `"status": "WITHDRAWN"`. The second returns a `500 Internal Server Error`: Spring doesn't know what `IllegalTransitionException` means yet.

### 10. Errors as `ProblemDetail`

Create `web/ApiExceptionHandler.java`:

```java
package com.example.onboarding.web;

import com.example.onboarding.application.ApplicationNotFoundException;
import com.example.onboarding.application.IllegalTransitionException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;
import java.util.TreeMap;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

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

    @ExceptionHandler(ApplicationNotFoundException.class)
    ProblemDetail handle(ApplicationNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Application not found");
        return problem;
    }

    @ExceptionHandler(IllegalTransitionException.class)
    ProblemDetail handle(IllegalTransitionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Illegal status change");
        return problem;
    }
}
```

- **Extending `ResponseEntityExceptionHandler`** turns all of Spring MVC's own errors (bad JSON, a wrong path type) into [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) `ProblemDetail` responses.
- **The override** adds an `errors` map of field → message to validation failures. A `TreeMap` keeps the fields in alphabetical order, so the output is stable.
- **Methods that return a `ProblemDetail`** get the right status and the `application/problem+json` content type automatically.

Restart and withdraw the application twice again. The second response is now:

```json
{
  "detail": "Application 7e0b7c52-1547-411b-aa08-34aecad0d22a cannot move from WITHDRAWN to WITHDRAWN",
  "instance": "/applications/7e0b7c52-1547-411b-aa08-34aecad0d22a/withdrawal",
  "status": 409,
  "title": "Illegal status change"
}
```

And an application with several mistakes:

```json
{
  "detail": "Invalid request content.",
  "instance": "/applications",
  "status": 400,
  "title": "Bad Request",
  "errors": {
    "dateOfBirth": "must be a past date",
    "email": "must be a well-formed email address",
    "givenName": "must not be blank",
    "postcode": "must be a UK postcode"
  }
}
```

### 11. API tests

Every integration test needs the same setup: the full application, the real Flyway migrations, and an **in-memory** database, so tests never touch your `./data` file. Bundle it into your own annotation. Create `src/test/java/com/example/onboarding/OnboardingIntegrationTest.java`:

```java
package com.example.onboarding;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Full application, real Flyway migrations, in-memory H2 (so tests never
 * touch ./data), and a MockMvcTester for calling the API.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:onboarding-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
public @interface OnboardingIntegrationTest {
}
```

This is a **meta-annotation**: Spring looks through it and finds `@SpringBootTest` and `@AutoConfigureMockMvc`. Because every test class uses identical configuration, Spring builds the application context once and caches it, which keeps the suite fast. `DB_CLOSE_DELAY=-1` keeps the in-memory database alive for as long as the JVM runs.

Now `src/test/java/com/example/onboarding/application/ApplicationApiTest.java`:

```java
package com.example.onboarding.application;

import com.example.onboarding.OnboardingIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

@OnboardingIntegrationTest
class ApplicationApiTest {

    static final String ALICE = """
            {
              "givenName": "Alice",
              "familyName": "Smith",
              "dateOfBirth": "1990-05-17",
              "email": "alice@example.com",
              "addressLine1": "1 High Street",
              "postcode": "g1 1xq",
              "expectedMonthlyDeposits": "FROM_1K_TO_5K"
            }
            """;

    @Autowired
    MockMvcTester mvc;

    @Test
    void receivesAnApplication() {
        MvcTestResult response = apply(ALICE);

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).headers().containsHeader("Location");
        assertThat(response).bodyJson().extractingPath("$.status").isEqualTo("RECEIVED");
        assertThat(response).bodyJson().extractingPath("$.applicant.dateOfBirth").isEqualTo("1990-05-17");
        assertThat(response).bodyJson().extractingPath("$.applicant.postcode").isEqualTo("G1 1XQ");
    }

    @Test
    void rejectsAnInvalidApplicationFieldByField() {
        MvcTestResult response = apply("""
                {"givenName": "", "familyName": "Smith", "dateOfBirth": "2999-01-01",
                 "email": "not-an-email", "addressLine1": "1 High Street", "postcode": "12345",
                 "expectedMonthlyDeposits": "UP_TO_1K"}
                """);

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors.givenName").isEqualTo("must not be blank");
        assertThat(response).bodyJson().extractingPath("$.errors.dateOfBirth").isEqualTo("must be a past date");
        assertThat(response).bodyJson().extractingPath("$.errors.email")
                .isEqualTo("must be a well-formed email address");
        assertThat(response).bodyJson().extractingPath("$.errors.postcode").isEqualTo("must be a UK postcode");
    }

    @Test
    void returnsProblemDetailForAnUnknownApplication() {
        MvcTestResult response = mvc.get().uri("/applications/00000000-0000-0000-0000-000000000000").exchange();

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Application not found");
    }

    @Test
    void withdrawingTwiceIsAnIllegalStatusChange() {
        String id = idOf(apply(ALICE));

        assertThat(mvc.post().uri("/applications/{id}/withdrawal", id))
                .hasStatus(HttpStatus.OK)
                .bodyJson().extractingPath("$.status").isEqualTo("WITHDRAWN");

        MvcTestResult again = mvc.post().uri("/applications/{id}/withdrawal", id).exchange();
        assertThat(again).hasStatus(HttpStatus.CONFLICT);
        assertThat(again).bodyJson().extractingPath("$.title").isEqualTo("Illegal status change");
        assertThat(again).bodyJson().extractingPath("$.detail").asString()
                .endsWith("cannot move from WITHDRAWN to WITHDRAWN");
    }

    MvcTestResult apply(String json) {
        return mvc.post().uri("/applications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    static String idOf(MvcTestResult response) {
        String location = response.getResponse().getHeader("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }
}
```

**`MockMvcTester`** (Spring Framework 6.2+) sends requests through the whole Spring MVC stack (validation, Jackson, the exception handler) without opening a network port. `bodyJson().extractingPath(...)` picks values out with JSONPath.

A small but useful habit: `withdrawingTwice…` gets the id from the `Location` header, the way a real client would, rather than reaching into the database.

Run every test: right-click `src/test/java` → **Run 'All Tests'**.

**Terminal alternative:**

```bash
./mvnw test
```

```
[INFO] Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
```

> **In older codebases:** the same assertion with classic MockMvc is `mockMvc.perform(post("/applications")...).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("RECEIVED"))`. Both work. `MockMvcTester` gives clearer failure messages.

---

## Your turn

### Exercise 1: applicants must be 18

A UK bank's standard current account is for adults. (Banks have separate accounts for children and teenagers.) Refuse an applicant who isn't 18 yet with a `422 Unprocessable Content` `ProblemDetail` titled `"Not eligible"`.

1. Create `IneligibleApplicantException`.
2. In `ApplicationService.receive`, work out today's date **from the injected `Clock`** and refuse anyone whose 18th birthday is after today.
3. Map the exception in `ApiExceptionHandler`.
4. Add two tests to `ApplicationApiTest`: someone whose 18th birthday is tomorrow is refused, and someone whose 18th birthday is today is accepted. Autowire the `Clock` so the tests keep working next year.

<details>
<summary>Hint</summary>

`LocalDate.now(clock)` gives today's date according to the clock. Someone born on `dateOfBirth` turns 18 on `dateOfBirth.plusYears(18)`. In the test, `LocalDate.now(clock).minusYears(18)` is the date of birth of someone whose 18th birthday is today. `ALICE.replace("1990-05-17", ...)` changes the date in the JSON.

</details>

<details>
<summary>Solution</summary>

`application/IneligibleApplicantException.java`:

```java
package com.example.onboarding.application;

public class IneligibleApplicantException extends RuntimeException {

    public IneligibleApplicantException(String message) {
        super(message);
    }
}
```

In `ApplicationService` (import `java.time.LocalDate`):

```java
    static final int MINIMUM_AGE = 18;

    @Transactional
    public Application receive(Applicant applicant) {
        LocalDate today = LocalDate.now(clock);
        if (applicant.dateOfBirth().plusYears(MINIMUM_AGE).isAfter(today)) {
            throw new IneligibleApplicantException(
                    "Applicants must be at least %d years old".formatted(MINIMUM_AGE));
        }
        return applications.save(Application.receive(applicant, clock.instant()));
    }
```

In `ApiExceptionHandler`:

```java
    @ExceptionHandler(IneligibleApplicantException.class)
    ProblemDetail handle(IneligibleApplicantException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setTitle("Not eligible");
        return problem;
    }
```

In `ApplicationApiTest` (import `java.time.Clock` and `java.time.LocalDate`):

```java
    @Autowired
    Clock clock;

    @Test
    void refusesAnApplicantUnderEighteen() {
        LocalDate seventeen = LocalDate.now(clock).minusYears(18).plusDays(1);

        MvcTestResult response = apply(ALICE.replace("1990-05-17", seventeen.toString()));

        assertThat(response).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Not eligible");
    }

    @Test
    void acceptsAnApplicantOnTheirEighteenthBirthday() {
        LocalDate eighteenToday = LocalDate.now(clock).minusYears(18);

        assertThat(apply(ALICE.replace("1990-05-17", eighteenToday.toString())))
                .hasStatus(HttpStatus.CREATED);
    }
```

The response for a 16-year-old:

```json
{"detail":"Applicants must be at least 18 years old","instance":"/applications","status":422,"title":"Not eligible"}
```

Why `422` and not `400`? The request is well-formed and every field is valid on its own. It's the business rule that says no. Testing the boundary on both sides (the day before and the day itself) is where off-by-one bugs live.

</details>

### Exercise 2: no way back

Nothing should ever be able to move *back* to `RECEIVED`: that would let an application re-run its checks and erase what they found. Add a test to `ApplicationStatusTest` that proves no status can move to `RECEIVED`.

<details>
<summary>Solution</summary>

```java
    @Test
    void nothingCanGoBackToReceived() {
        for (ApplicationStatus status : ApplicationStatus.values()) {
            assertThat(status.canMoveTo(ApplicationStatus.RECEIVED)).as(status.name()).isFalse();
        }
    }
```

Looping over `values()` means the test also covers statuses that don't exist yet. When guide 03 adds one, this test checks it automatically. `.as(status.name())` labels the assertion, so a failure tells you which status broke the rule.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
```

(17 in `ApplicationStatusTest`, 3 in `ApplicationTest` and 6 in `ApplicationApiTest`, if you did both exercises. Each row of a parameterised test counts as one test.)

You can receive an application, fetch it, withdraw it once but not twice, get field-by-field `400`s for bad input, and a `422` for an applicant under 18.

```
customer-onboarding/
├── pom.xml
├── requests.http
└── src/
    ├── main/
    │   ├── java/com/example/onboarding/
    │   │   ├── CustomerOnboardingApplication.java
    │   │   ├── application/
    │   │   │   ├── Applicant.java
    │   │   │   ├── Application.java
    │   │   │   ├── ApplicationController.java
    │   │   │   ├── ApplicationNotFoundException.java
    │   │   │   ├── ApplicationRepository.java
    │   │   │   ├── ApplicationService.java
    │   │   │   ├── ApplicationStatus.java
    │   │   │   ├── ExpectedMonthlyDeposits.java
    │   │   │   ├── IllegalTransitionException.java
    │   │   │   └── IneligibleApplicantException.java
    │   │   ├── config/
    │   │   │   └── ClockConfig.java
    │   │   └── web/
    │   │       └── ApiExceptionHandler.java
    │   └── resources/
    │       ├── application.properties
    │       └── db/migration/
    │           └── V1__create_application.sql
    └── test/java/com/example/onboarding/
        ├── OnboardingIntegrationTest.java
        └── application/
            ├── ApplicationApiTest.java
            ├── ApplicationStatusTest.java
            └── ApplicationTest.java
```

---

## Common errors

**`Schema validation: wrong column type encountered in column [id] in table [application]; found [character varying (Types#VARCHAR)], but expecting [uuid (Types#UUID)]`**
You declared the id column as `VARCHAR(36)`. Hibernate maps `java.util.UUID` to a native `UUID` column, and `validate` checks the type. Use `UUID` in the migration. Because Flyway has already recorded `V1` as applied, delete the `data/` folder (fine in development) or add a `V2` that changes the column. **Never edit a migration that has already run.**

**`JpaSystemException: Identifier of entity 'com.example.onboarding.application.Application' must be manually assigned before calling 'persist()'`**
The `@GeneratedValue(strategy = GenerationType.UUID)` annotation is missing, so Hibernate expects you to set the id yourself. Add it back.

**`{"detail":"Failed to read request", ..., "status":400}`**
Jackson couldn't turn the body into an `ApplyRequest`, before validation even ran. The usual causes are an enum value that doesn't exist (`"expectedMonthlyDeposits": "LOTS"`) or a date that isn't ISO format (`"17/05/1990"` instead of `"1990-05-17"`). The response doesn't say which field, because the JSON couldn't be read at all.

**`{"detail":"Failed to convert 'id' with value: '123'", ..., "status":400}`**
The id in the URL isn't a UUID. Copy the whole `id` from the response that created the application.

**`500 Internal Server Error` when withdrawing twice**
`ApiExceptionHandler` has no `@ExceptionHandler` for `IllegalTransitionException`, or the class isn't in a package under `com.example.onboarding`, so Spring never finds it.

**`Cannot resolve symbol 'AutoConfigureMockMvc'`**
In Spring Boot 4 it lives in `org.springframework.boot.webmvc.test.autoconfigure`. Older answers online use `org.springframework.boot.test.autoconfigure.web.servlet`.

---

## Recap

- **KYC / customer due diligence** is a legal requirement (MLR 2017, regulation 28): identify the customer, verify the identity from a reliable independent source, and understand what the account is for.
- Onboarding is a **long-running, multi-step process**, so its progress lives in the database as a **status**.
- Put the workflow rules **in one place** (an enum with `allowedNext()`), and give the entity **one method** that changes the status and enforces them. An exhaustive switch makes the compiler check new statuses.
- Expose **business actions** (`POST /withdrawal`), never "set status", and report illegal moves as **`409 Conflict`**.
- Use **UUIDs** for ids that customers see.
- Get the time from an injected **`Clock`**, so tests can control it.
- **Parameterised tests** cover every transition cheaply.

### Interview angle

<details>
<summary>"What does KYC involve when a bank opens an account?"</summary>

Customer due diligence under the Money Laundering Regulations 2017: identify the customer, verify their identity using documents or reliable independent information (often electronically, for example against credit reference data), and understand the purpose and intended nature of the relationship. Alongside that, screen against sanctions lists and for politically exposed persons, and risk-rate the customer, because the risk rating decides whether simplified, standard or enhanced due diligence applies. It doesn't stop at account opening: the bank must keep the information up to date and monitor the relationship.

</details>

<details>
<summary>"How would you implement a workflow with several states?"</summary>

Persist the current state on the entity. Define the allowed transitions in one place, for example an enum with a method returning the allowed next states, and make the only way to change state a method that checks the rule and throws on an illegal move. Expose business actions in the API rather than a writable status field. Map illegal transitions to `409`. Unit-test every legal and a selection of illegal transitions. For complex processes with timers, human tasks and versioning, a workflow engine may be worth it, but the core idea is the same.

</details>

<details>
<summary>"Why might you use a UUID rather than an auto-increment id?"</summary>

For anything exposed to customers: sequential ids leak business volumes and make it easy to guess other records' URLs if authorisation is ever wrong. UUIDs can also be generated without a round-trip to the database. The costs are size (16 bytes) and less index-friendly random insertion order. Time-ordered UUID versions reduce that. Some systems keep a sequential internal key and expose a separate public UUID.

</details>

### Further reading

- [The Money Laundering Regulations 2017](https://www.legislation.gov.uk/uksi/2017/692/contents), especially [regulation 28 (customer due diligence measures)](https://www.legislation.gov.uk/uksi/2017/692/regulation/28)
- [JMLSG guidance](https://www.jmlsg.org.uk/guidance/current-guidance/): Part I covers customer due diligence in detail
- [FCA: money laundering and terrorist financing](https://www.fca.org.uk/firms/financial-crime/money-laundering-terrorist-financing)
- [JUnit User Guide](https://docs.junit.org/current/user-guide/): see "Parameterized Classes and Tests"

**Next:** [02: Due diligence checks](02-due-diligence-checks.md). The checks themselves: identity, address, sanctions and PEPs, a risk rating, and a workflow that survives a provider going down.
