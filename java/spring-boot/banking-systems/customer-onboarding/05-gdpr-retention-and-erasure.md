# 05: GDPR, Retention and Erasure

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- The UK GDPR principles that shape onboarding code: **data minimisation** and **storage limitation**
- Why a bank must **keep** due diligence records for years, and how that interacts with the **right to erasure**
- What a bank does **after** onboarding: ongoing monitoring and periodic review
- **Masking** in API responses: show the applicant just enough
- Keeping personal data **out of logs**, proved by a test that captures log output, and a trap in Java **records**' `toString()`
- An **erasure request** that erases what it may, keeps what the law requires, and says when the rest will go
- A **scheduled purge** that deletes personal data when its retention period ends, tested by moving a **mutable `Clock`** five years forward

By the end, the applicant-facing API shows only a status and a masked email, no log line contains personal data, and the system can answer "please delete my data" correctly for every kind of application.

---

## Concepts

### How it works in a real bank

**Onboarding isn't the end of due diligence.** Once an account is open, the Money Laundering Regulations require **ongoing monitoring** of the relationship ([regulation 28(11)](https://www.legislation.gov.uk/uksi/2017/692/regulation/28)): scrutinising transactions to check they fit what the bank knows about the customer, and keeping the due diligence information up to date. Banks run **periodic reviews**, refreshing KYC on a cycle set by the customer's risk rating (high-risk customers are reviewed more often), and **event-driven reviews** when something changes, such as a customer appearing on a sanctions list update or their transactions suddenly looking very different. Transaction monitoring is the subject of the Fraud monitoring tutorial in this series. This guide is about what happens to the **data** collected during onboarding.

That data is personal data, protected by the **UK GDPR** and the **Data Protection Act 2018**, and regulated by the **ICO** (Information Commissioner's Office). Two of the principles in [Article 5](https://www.legislation.gov.uk/eur/2016/679/article/5) matter most here:

- **Data minimisation:** personal data must be "adequate, relevant and limited to what is necessary". In code: don't collect, return, copy or log more than the purpose needs.
- **Storage limitation:** keep personal data in an identifiable form for no longer than necessary. In code: every kind of record needs a **retention period**, and something must actually delete the data when it ends.

### Retention versus erasure

A bank collects due diligence data because the law requires it. Under UK GDPR that's the lawful basis of **legal obligation** ([Article 6(1)(c)](https://www.legislation.gov.uk/eur/2016/679/article/6)). The same law says how long to keep it. [MLR 2017 regulation 40](https://www.legislation.gov.uk/uksi/2017/692/regulation/40) requires a bank to keep a copy of the documents and information it obtained for customer due diligence for **five years beginning on the date it knows, or has reasonable grounds to believe, that the business relationship has come to an end**. And then regulation 40(5) requires it to **delete** the personal data it obtained for those purposes once the period has expired, unless another law requires it to be kept, the person has consented, or it's needed for legal proceedings.

Meanwhile, people have the **right to erasure** under [Article 17](https://www.legislation.gov.uk/eur/2016/679/article/17) ("the right to be forgotten"). But it isn't absolute. Article 17(3)(b) says it doesn't apply where processing is necessary to comply with a legal obligation. As the [ICO puts it](https://ico.org.uk/for-organisations/uk-gdpr-guidance-and-resources/individual-rights/individual-rights/right-to-erasure/), if you're required by law to process someone's data, the right to erasure won't apply to it. The ICO also says a request must be answered without undue delay and within one month.

So when an applicant asks the bank to delete everything, the honest answer is usually partial:

| What | Erase now? | Why |
|---|---|---|
| Contact details (email) | **Yes**, once no longer needed | Not due diligence evidence |
| Name, date of birth, address | **No**, until the retention date | Due diligence records (regulation 40) |
| Check results and the audit trail | **No**, until the retention date | Evidence of the due diligence done, and of the decisions taken |
| All of it, after the retention date | **Yes, automatically** | Regulation 40(5) and storage limitation |

**What this tutorial does for each status:**

- **Received, no checks run yet:** no due diligence has happened, so nothing has to be kept. Erase everything (and withdraw the application).
- **Declined:** a business relationship never started. Regulation 40's wording is about business relationships and occasional transactions, so how long to keep a declined application is the bank's own **policy** decision, often informed by other needs too, such as fraud prevention or a suspicious activity report. **This tutorial's policy** treats the decision date like the end of a relationship and keeps the due diligence records for five years from it. That's a labelled choice, not a rule quoted from the regulations.
- **Approved (an account was opened):** the relationship is ongoing, so the records must be kept while the account is open and for five years after it closes. Exercise 1 adds the "account closed" event.
- **Still being decided:** finish deciding first. (Real banks would usually treat "delete my data" from someone mid-application as a withdrawal too. Here, a reviewer can decline it.)

> **Sidebar: deleting versus anonymising.** When the retention period ends, this tutorial sets the identifying columns to `NULL` and keeps the application's id, status, dates and check outcomes for statistics (how many applications were referred last year?). Whether what's left is truly **anonymous** is a judgement call: if it can be linked back to a person by other means, it's still personal data. The ICO's [anonymisation guidance](https://ico.org.uk/for-organisations/uk-gdpr-guidance-and-resources/data-sharing/anonymisation/) covers how to decide. Deleting the rows outright is the simplest compliant option. Keeping a stripped-down record is something a bank does deliberately and documents.

### Logs are a data store too

Developers protect the database carefully and then write `log.info("Received application from {}", applicant)`. Logs are copied to central logging systems, kept for months, searched by many people, and attached to support tickets. They're usually outside the retention and erasure processes entirely. Personal data in a log line has escaped all the protections you built in guide 04.

The rule (it's in the [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html) too): **log identifiers, not personal data.** An application id is enough to find everything else, by someone who's allowed to.

---

## Steps

### 1. Show the applicant only what they need

Create the package `com.example.onboarding.privacy`. `privacy/Masking.java`:

```java
package com.example.onboarding.privacy;

/** Just enough of a value for the applicant to recognise it, and no more. */
public final class Masking {

    private Masking() {
    }

    /** "alice@example.com" becomes "a****@example.com". Always four stars, so the length doesn't leak. */
    public static String email(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at < 1) {
            return "****";
        }
        return email.charAt(0) + "****" + email.substring(at);
    }

    /** "G1 1XQ" becomes "G1 ***": the outward code only. */
    public static String postcode(String postcode) {
        if (postcode == null) {
            return null;
        }
        String compact = postcode.replace(" ", "");
        return compact.length() <= 3 ? "***" : compact.substring(0, compact.length() - 3) + " ***";
    }
}
```

A fixed number of stars means `a****@example.com` doesn't reveal whether the address was `al@` or `alexandra.mcdonald@`. A UK postcode's last three characters are always the inward code, so dropping them leaves only the broad area.

`src/test/java/com/example/onboarding/privacy/MaskingTest.java`:

```java
package com.example.onboarding.privacy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingTest {

    @Test
    void masksTheLocalPartOfAnEmailToAFixedLength() {
        assertThat(Masking.email("alice@example.com")).isEqualTo("a****@example.com");
        assertThat(Masking.email("al@example.com")).isEqualTo("a****@example.com");
    }

    @Test
    void keepsOnlyTheOutwardCodeOfAPostcode() {
        assertThat(Masking.postcode("G1 1XQ")).isEqualTo("G1 ***");
        assertThat(Masking.postcode("SW1A 2AA")).isEqualTo("SW1A ***");
    }

    @Test
    void handlesMissingValues() {
        assertThat(Masking.email(null)).isNull();
        assertThat(Masking.email("no-at-sign")).isEqualTo("****");
    }
}
```

Now cut down the response. In `ApplicationController`, replace `ApplicationResponse` (import `com.example.onboarding.privacy.Masking`, and remove the `RiskRating` import):

```java
    /**
     * What the applicant sees: enough to recognise their application and know where it is.
     * No date of birth, no address, no risk rating, no check results.
     */
    public record ApplicationResponse(
            UUID id,
            ApplicationStatus status,
            String email,
            Instant createdAt,
            Instant statusChangedAt) {

        public static ApplicationResponse from(Application application) {
            return new ApplicationResponse(application.getId(), application.getStatus(),
                    Masking.email(application.getApplicant().email()),
                    application.getCreatedAt(), application.getStatusChangedAt());
        }
    }
```

Two things have gone. The applicant's own details: they typed them in, and echoing them back only creates another copy (in browser caches, proxies, client logs). And the **risk rating**: that's the bank's internal assessment of the applicant, and as guide 03 explained, the applicant isn't told the reasons behind decisions.

Run the tests. `receivesAnApplication` fails, because it still reads `$.applicant.dateOfBirth`. Update it to assert the new, smaller shape, and check the postcode normalisation through the service instead (autowire `ApplicationService`, import `java.util.UUID`):

```java
    @Autowired
    ApplicationService applicationService;

    @Test
    void receivesAnApplication() {
        MvcTestResult response = apply(ALICE);

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).headers().containsHeader("Location");
        assertThat(response).bodyJson().extractingPath("$.status").isEqualTo("RECEIVED");
        assertThat(response).bodyJson().extractingPath("$.email").isEqualTo("a****@example.com");
        assertThat(response).bodyJson().doesNotHavePath("$.applicant");
        assertThat(response).bodyJson().doesNotHavePath("$.riskRating");
        Applicant stored = applicationService.get(UUID.fromString(idOf(response))).getApplicant();
        assertThat(stored.postcode()).isEqualTo("G1 1XQ");
    }
```

`doesNotHavePath` turns "we don't return that" into a regression test. If someone adds the date of birth back to the response, this fails.

The applicant now sees:

```json
{"id":"d1ab4415-9278-41cf-b7e0-71b66435e8b4","status":"RECEIVED","email":"a****@example.com","createdAt":"2026-11-07T14:26:29.598182260Z","statusChangedAt":"2026-11-07T14:26:29.598182260Z"}
```

Reviewers still see what they need through the review queue, and Exercise 2 gives them an audited view of the full record.

### 2. Keep personal data out of the logs

Add a log line to `ApplicationService`, the kind that appears in real codebases every day (imports: `org.slf4j.Logger`, `org.slf4j.LoggerFactory`):

```java
    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);
```

and at the end of `receive`, after the audit event:

```java
        log.info("Received application {} from {}", application.getId(), applicant);
```

Now write a test that reads everything the application printed. `src/test/java/com/example/onboarding/privacy/PersonalDataLoggingTest.java`:

```java
package com.example.onboarding.privacy;

import com.example.onboarding.OnboardingIntegrationTest;
import com.example.onboarding.application.Applicant;
import com.example.onboarding.application.ExpectedMonthlyDeposits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.time.LocalDate;

import static com.example.onboarding.TestApplicants.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

/** Logs are copied, shipped and kept for months. Personal data must not be in them. */
@OnboardingIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class PersonalDataLoggingTest {

    @Autowired MockMvcTester mvc;

    @Test
    void applyingAndCheckingLogsNoPersonalData(CapturedOutput output) {
        Applicant zeb = new Applicant("Zebedee", "Quixote-Lark", LocalDate.of(1987, 3, 14),
                uniqueEmail("zebedee"), "42 Wallaby Way", "KY16 9AJ", ExpectedMonthlyDeposits.UP_TO_1K);

        MvcTestResult created = mvc.post().uri("/applications")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"givenName": "%s", "familyName": "%s", "dateOfBirth": "%s", "email": "%s",
                         "addressLine1": "%s", "postcode": "%s", "expectedMonthlyDeposits": "UP_TO_1K"}
                        """.formatted(zeb.givenName(), zeb.familyName(), zeb.dateOfBirth(), zeb.email(),
                        zeb.addressLine1(), zeb.postcode()))
                .exchange();
        String location = created.getResponse().getHeader("Location");
        mvc.post().uri(location + "/checks").exchange();

        assertThat(output.getAll())
                .doesNotContain("Zebedee")
                .doesNotContain("Quixote")
                .doesNotContain(zeb.email())
                .doesNotContain("1987-03-14")
                .doesNotContain("Wallaby")
                .doesNotContain("KY16");
    }
}
```

- **`OutputCaptureExtension`** (from Spring Boot's test support) captures everything written to `System.out` and `System.err` during the test, which includes the console log. JUnit passes it in as the `CapturedOutput` parameter.
- **Distinctive test data.** "Smith" might appear in a log for innocent reasons. "Quixote" won't. Choosing values that can't occur by accident keeps the test honest.
- **It exercises the real journey,** through the HTTP layer and the checks, because log lines can come from anywhere along the way.

Run it. It fails, and the captured output contains:

```
Received application f24608a5-5a3c-4d4e-834d-d08f595deac5 from Applicant[givenName=Zebedee, familyName=Quixote-Lark, dateOfBirth=1987-03-14, email=zebedee+1c07d5ef-4333-47eb-b2f8-dd9c319042ba@example.com, addressLine1=42 Wallaby Way, postcode=KY16 9AJ, expectedMonthlyDeposits=UP_TO_1K]
```

Every field, in plain text, in the log, a few lines after the database went to the trouble of encrypting them. The culprit is the **record's generated `toString()`**, which prints every component. It's convenient for debugging and a hazard for personal data, because it's called implicitly: by logging, by string concatenation, by exception messages, by the debugger.

Fix it twice. First, log the identifier only:

```java
        log.info("Received application {}", application.getId());
```

Second, as defence in depth, make `Applicant` safe to print by accident. Records let you override the generated methods:

```java
public record Applicant(
        String givenName,
        String familyName,
        LocalDate dateOfBirth,
        String email,
        String addressLine1,
        String postcode,
        ExpectedMonthlyDeposits expectedMonthlyDeposits) {

    /** A record's generated toString prints every field. This one prints none of the personal ones. */
    @Override
    public String toString() {
        return "Applicant[personal data redacted, expectedMonthlyDeposits=%s]".formatted(expectedMonthlyDeposits);
    }
}
```

And pin it down, `src/test/java/com/example/onboarding/application/ApplicantTest.java`:

```java
package com.example.onboarding.application;

import org.junit.jupiter.api.Test;

import static com.example.onboarding.TestApplicants.alice;
import static org.assertj.core.api.Assertions.assertThat;

class ApplicantTest {

    @Test
    void toStringLeaksNoPersonalData() {
        Applicant alice = alice();

        assertThat(alice.toString())
                .doesNotContain(alice.givenName())
                .doesNotContain(alice.familyName())
                .doesNotContain(alice.email())
                .doesNotContain(alice.postcode())
                .isEqualTo("Applicant[personal data redacted, expectedMonthlyDeposits=FROM_1K_TO_5K]");
    }
}
```

Both tests pass. One side effect to know about: when an assertion such as `isEqualTo(alice)` fails on two `Applicant`s, AssertJ's message prints both with `toString()`, so you'll see the redacted text rather than the differing fields. That's the right trade: test failure output also ends up in CI logs.

> **In older codebases:** Lombok's `@Data` and `@ToString` generate the same print-everything `toString()`. Look for `@ToString.Exclude` on sensitive fields, or better, a hand-written `toString()`. See the [legacy cheat sheet](../legacy-cheatsheet.md#lombok).

### 3. A clock you can move

Retention is measured in years. To test it, the tests need to move time forward, and guide 01 prepared for exactly that by injecting a `Clock`. Create `src/test/java/com/example/onboarding/MutableClock.java`:

```java
package com.example.onboarding;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A clock tests can move. volatile, because concurrency tests read it from many threads. */
public class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant start, ZoneId zone) {
        this.instant = start;
        this.zone = zone;
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    public void advance(Duration duration) {
        this.instant = instant.plus(duration);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }
}
```

`java.time.Clock` is abstract with three methods to implement. (`Clock.fixed(...)` exists, but it can't be moved during a test, and here the same bean must change.)

`src/test/java/com/example/onboarding/TestClockConfig.java`:

```java
package com.example.onboarding;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.time.ZoneOffset;

@TestConfiguration
public class TestClockConfig {

    public static final Instant START = Instant.parse("2026-11-07T09:00:00Z");

    @Bean
    @Primary
    MutableClock testClock() { // not "clock": that name is taken by ClockConfig
        return new MutableClock(START, ZoneOffset.UTC);
    }
}
```

`@Primary` means every injection point that asks for a `Clock` gets this one. Both clock beans exist; the primary one wins.

Update `OnboardingIntegrationTest` so every integration test uses it, and so scheduled jobs (step 6) don't run in the background during tests:

```java
/**
 * Full application, real Flyway migrations, in-memory H2 (so tests never
 * touch ./data), a MockMvcTester for calling the API, a clock tests can move,
 * and no scheduled jobs running behind the tests' backs.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:onboarding-test;DB_CLOSE_DELAY=-1",
        "onboarding.scheduling.enabled=false"})
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public @interface OnboardingIntegrationTest {
}
```

(import `org.springframework.context.annotation.Import`.) Every test now starts on 7 November 2026, 09:00 UTC, whatever the real date. Tests that compute dates from the clock, like guide 01's age tests, carry on working because they ask the same clock.

### 4. The retention policy

Erased data becomes `NULL`, so the personal columns must allow it, and two new columns record the retention date and when the data was erased. `src/main/resources/db/migration/V7__retention_and_erasure.sql`:

```sql
-- Erased personal data becomes NULL, so these columns must allow it
ALTER TABLE application ALTER COLUMN given_name    DROP NOT NULL;
ALTER TABLE application ALTER COLUMN family_name   DROP NOT NULL;
ALTER TABLE application ALTER COLUMN date_of_birth DROP NOT NULL;
ALTER TABLE application ALTER COLUMN email         DROP NOT NULL;
ALTER TABLE application ALTER COLUMN address_line1 DROP NOT NULL;
ALTER TABLE application ALTER COLUMN postcode      DROP NOT NULL;

ALTER TABLE application ADD COLUMN retain_until DATE;
ALTER TABLE application ADD COLUMN personal_data_erased_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX ix_application_retain_until ON application (retain_until);
```

The index supports the purge job's daily query: "which applications' retention ended before today?"

In `Application.java`, remove `nullable = false` from the six personal-data `@Column`s (for example `@Column(name = "given_name", length = 700)`), and add:

```java
    @Column(name = "retain_until")
    private LocalDate retainUntil;

    @Column(name = "personal_data_erased_at")
    private Instant personalDataErasedAt;
```

with these methods:

```java
    /** Contact details aren't due diligence records, so they can go as soon as they're not needed. */
    public void eraseContactDetails() {
        this.email = null;
        this.emailHash = null;
    }

    /** Everything that identifies the person. Status, dates and check outcomes stay. */
    public void erasePersonalData(Instant now) {
        eraseContactDetails();
        this.givenName = null;
        this.familyName = null;
        this.dateOfBirth = null;
        this.addressLine1 = null;
        this.postcode = null;
        this.firstApprover = null;
        this.personalDataErasedAt = now;
    }

    public void retainUntil(LocalDate date) {
        this.retainUntil = date;
    }

    public LocalDate getRetainUntil() {
        return retainUntil;
    }

    public Instant getPersonalDataErasedAt() {
        return personalDataErasedAt;
    }
```

- **`emailHash` goes with the email.** The blind index is derived from personal data, and leaving it would let anyone with the key test whether a given address once applied. Erasing it also frees the address to apply again.
- **`firstApprover`** is a member of staff's identifier, not the applicant's. Clearing it is a choice: the audit trail still records who approved, until it too is purged with the rest.
- **The `Applicant` record happily holds `null`s**, and `Masking.email(null)` returns `null`, so the API keeps working for erased applications.

The retention length is configuration. Add to `application.properties`:

```properties
# How long customer due diligence records are kept after the relationship ends
# (Money Laundering Regulations 2017, regulation 40)
onboarding.retention.years=5
```

`privacy/RetentionProperties.java`:

```java
package com.example.onboarding.privacy;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("onboarding.retention")
public record RetentionProperties(int years) {
}
```

`privacy/RetentionPolicy.java`:

```java
package com.example.onboarding.privacy;

import com.example.onboarding.application.Application;
import com.example.onboarding.application.ApplicationStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * When an application's customer due diligence records may be deleted.
 * This tutorial's policy: a declined application is kept for N years from the decision.
 */
@Component
public class RetentionPolicy {

    private final RetentionProperties properties;

    public RetentionPolicy(RetentionProperties properties) {
        this.properties = properties;
    }

    public LocalDate retainUntil(LocalDate relationshipEnded) {
        return relationshipEnded.plusYears(properties.years());
    }

    /** Call after any decision. Sets the retention end date once the application is declined. */
    public void apply(Application application) {
        if (application.getStatus() == ApplicationStatus.DECLINED && application.getRetainUntil() == null) {
            LocalDate decided = LocalDate.ofInstant(application.getStatusChangedAt(), ZoneOffset.UTC);
            application.retainUntil(retainUntil(decided));
        }
    }
}
```

The date is computed **once, when the application is declined**, from the decision time, not recalculated later from "now". The retention date is a fact about the record, visible in the database and in the erasure response.

Call it after both kinds of decision. In `ApplicationService`, inject `RetentionPolicy` (add it to the fields and constructor before `Clock`) and in `decide`:

```java
        application.decide(risk, Decision.decide(results.values(), risk), clock.instant());
        retentionPolicy.apply(application);
```

In `ReviewService`, inject it the same way, and in `decide`:

```java
        application.review(reviewer, decision, clock.instant());
        retentionPolicy.apply(application);
```

`src/test/java/com/example/onboarding/privacy/RetentionPolicyTest.java`:

```java
package com.example.onboarding.privacy;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionPolicyTest {

    RetentionPolicy policy = new RetentionPolicy(new RetentionProperties(5));

    @Test
    void keepsRecordsForFiveYearsFromTheEndOfTheRelationship() {
        assertThat(policy.retainUntil(LocalDate.of(2026, 11, 7))).isEqualTo(LocalDate.of(2031, 11, 7));
    }

    @Test
    void aLeapDayEndsOnTheLastDayOfFebruary() {
        assertThat(policy.retainUntil(LocalDate.of(2028, 2, 29))).isEqualTo(LocalDate.of(2033, 2, 28));
    }
}
```

`plusYears` on 29 February lands on 28 February in a non-leap year. Whether that's the "right" end date is exactly the kind of question to settle with the bank's compliance team and then pin in a test, so nobody changes it by accident.

### 5. The erasure request

`privacy/PersonalDataField.java`:

```java
package com.example.onboarding.privacy;

public enum PersonalDataField {
    EMAIL,
    NAME,
    DATE_OF_BIRTH,
    ADDRESS,
    CHECK_RESULTS,
    AUDIT_TRAIL
}
```

`privacy/ErasureNotPossibleException.java`:

```java
package com.example.onboarding.privacy;

public class ErasureNotPossibleException extends RuntimeException {

    public ErasureNotPossibleException(String message) {
        super(message);
    }
}
```

Add three event types to `AuditEventType`:

```java
    WITHDRAWN,
    ERASURE_REQUESTED,
    PERSONAL_DATA_ERASED
}
```

Add the purge query to `ApplicationRepository` (import `java.time.LocalDate`):

```java
    List<Application> findByRetainUntilBeforeAndPersonalDataErasedAtIsNull(LocalDate date);
```

`privacy/ErasureService.java`:

```java
package com.example.onboarding.privacy;

import com.example.onboarding.application.Application;
import com.example.onboarding.application.ApplicationNotFoundException;
import com.example.onboarding.application.ApplicationRepository;
import com.example.onboarding.application.ApplicationStatus;
import com.example.onboarding.audit.AuditEventType;
import com.example.onboarding.audit.AuditTrail;
import com.example.onboarding.check.CheckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.example.onboarding.privacy.PersonalDataField.ADDRESS;
import static com.example.onboarding.privacy.PersonalDataField.AUDIT_TRAIL;
import static com.example.onboarding.privacy.PersonalDataField.CHECK_RESULTS;
import static com.example.onboarding.privacy.PersonalDataField.DATE_OF_BIRTH;
import static com.example.onboarding.privacy.PersonalDataField.EMAIL;
import static com.example.onboarding.privacy.PersonalDataField.NAME;

@Service
public class ErasureService {

    private static final Logger log = LoggerFactory.getLogger(ErasureService.class);

    /** What the Money Laundering Regulations make us keep: the due diligence and its evidence. */
    private static final Set<PersonalDataField> DUE_DILIGENCE_RECORDS =
            EnumSet.of(NAME, DATE_OF_BIRTH, ADDRESS, CHECK_RESULTS, AUDIT_TRAIL);

    private final ApplicationRepository applications;
    private final CheckService checkService;
    private final AuditTrail auditTrail;
    private final Clock clock;

    public ErasureService(ApplicationRepository applications, CheckService checkService,
                          AuditTrail auditTrail, Clock clock) {
        this.applications = applications;
        this.checkService = checkService;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    public record ErasureOutcome(Set<PersonalDataField> erased, Set<PersonalDataField> retained,
                                 LocalDate retainedUntil, String retentionBasis) {
    }

    @Transactional
    public ErasureOutcome requestErasure(UUID id) {
        Application application = applications.findByIdForUpdate(id)
                .orElseThrow(() -> new ApplicationNotFoundException(id));
        ApplicationStatus status = application.getStatus();
        if (application.getPersonalDataErasedAt() != null) {
            throw new ErasureNotPossibleException("Personal data for application %s has already been erased"
                    .formatted(id));
        }
        if (status == ApplicationStatus.APPROVED) {
            throw new ErasureNotPossibleException("Application %s opened an account. Its due diligence records "
                    .formatted(id) + "are kept while the account is open and for five years after it closes");
        }
        if (!status.isFinal() && status != ApplicationStatus.RECEIVED) {
            throw new ErasureNotPossibleException("Application %s is %s; ask again once it has been decided"
                    .formatted(id, status));
        }

        ErasureOutcome outcome;
        if (checkService.resultsFor(id).isEmpty()) {
            // No due diligence was ever done, so there is nothing the regulations make us keep
            if (status == ApplicationStatus.RECEIVED) {
                application.moveTo(ApplicationStatus.WITHDRAWN, clock.instant());
            }
            application.erasePersonalData(clock.instant());
            outcome = new ErasureOutcome(EnumSet.of(EMAIL, NAME, DATE_OF_BIRTH, ADDRESS),
                    EnumSet.noneOf(PersonalDataField.class), null, null);
        } else {
            application.eraseContactDetails();
            outcome = new ErasureOutcome(EnumSet.of(EMAIL), EnumSet.copyOf(DUE_DILIGENCE_RECORDS),
                    application.getRetainUntil(), "Money Laundering Regulations 2017, regulation 40");
        }
        auditTrail.record(id, AuditEventType.ERASURE_REQUESTED, AuditTrail.APPLICANT,
                "Erased %s; retained %s until %s".formatted(outcome.erased(), outcome.retained(),
                        outcome.retainedUntil()));
        log.info("Erasure request for application {}: erased {}", id, outcome.erased());
        return outcome;
    }
}
```

Points to notice:

- **The lock.** `findByIdForUpdate` from guide 03 stops an erasure racing a reviewer's decision on the same application.
- **The decision is made from facts, not from the request.** Whether due diligence was done is decided by looking for check results, not by trusting the status alone.
- **The outcome explains itself:** what was erased, what was kept, until when, and on what legal basis. That's what the bank's reply to the applicant would be built from, and it's what the ICO would expect a bank to be able to explain.
- **The erasure is itself audited**, and the audit detail lists *categories* ("EMAIL"), never values. (An audit trail that recorded "erased alice@example.com" would have defeated the point.)
- **`EnumSet`** is the `Set` counterpart of `EnumMap`: compact, and it iterates in declaration order, so the JSON is stable.

`privacy/ErasureController.java`:

```java
package com.example.onboarding.privacy;

import com.example.onboarding.privacy.ErasureService.ErasureOutcome;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ErasureController {

    private final ErasureService erasureService;

    public ErasureController(ErasureService erasureService) {
        this.erasureService = erasureService;
    }

    /** The applicant's right to erasure (UK GDPR Article 17), as far as the law allows. */
    @PostMapping("/applications/{id}/erasure-request")
    public ErasureOutcome requestErasure(@PathVariable UUID id) {
        return erasureService.requestErasure(id);
    }
}
```

In `ApiExceptionHandler`:

```java
    @ExceptionHandler(ErasureNotPossibleException.class)
    ProblemDetail handle(ErasureNotPossibleException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Erasure not possible");
        return problem;
    }
```

`src/test/java/com/example/onboarding/privacy/ErasureApiTest.java`:

```java
package com.example.onboarding.privacy;

import com.example.onboarding.OnboardingIntegrationTest;
import com.example.onboarding.application.Applicant;
import com.example.onboarding.application.ApplicationService;
import com.example.onboarding.workflow.OnboardingWorkflow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.Map;
import java.util.UUID;

import static com.example.onboarding.TestApplicants.applicant;
import static org.assertj.core.api.Assertions.assertThat;

@OnboardingIntegrationTest
class ErasureApiTest {

    @Autowired MockMvcTester mvc;
    @Autowired ApplicationService applicationService;
    @Autowired OnboardingWorkflow workflow;
    @Autowired JdbcTemplate jdbc;

    MvcTestResult requestErasure(UUID id) {
        return mvc.post().uri("/applications/{id}/erasure-request", id).exchange();
    }

    Map<String, Object> rawRow(UUID id) {
        return jdbc.queryForMap("SELECT given_name, email, email_hash FROM application WHERE id = ?", id);
    }

    @Test
    void beforeAnyChecksEverythingIsErasedAndTheApplicationWithdrawn() {
        UUID id = applicationService.receive(applicant("Alice", "Smith")).getId();

        MvcTestResult response = requestErasure(id);

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.erased").asArray()
                .containsExactlyInAnyOrder("EMAIL", "NAME", "DATE_OF_BIRTH", "ADDRESS");
        assertThat(response).bodyJson().extractingPath("$.retained").asArray().isEmpty();
        assertThat(rawRow(id)).containsEntry("GIVEN_NAME", null).containsEntry("EMAIL", null);
        assertThat(mvc.get().uri("/applications/{id}", id)).bodyJson()
                .extractingPath("$.status").isEqualTo("WITHDRAWN");
    }

    @Test
    void afterADeclineOnlyContactDetailsGoAndDueDiligenceIsKeptForFiveYears() {
        Applicant fred = applicant("Fred", "Fraudulent");
        UUID id = applicationService.receive(fred).getId();
        workflow.runChecks(id); // DECLINED on 2026-11-07, the test clock's date

        MvcTestResult response = requestErasure(id);

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.erased").asArray().containsExactly("EMAIL");
        assertThat(response).bodyJson().extractingPath("$.retained").asArray()
                .contains("NAME", "DATE_OF_BIRTH", "ADDRESS", "CHECK_RESULTS");
        assertThat(response).bodyJson().extractingPath("$.retainedUntil").isEqualTo("2031-11-07");
        assertThat(rawRow(id).get("GIVEN_NAME").toString()).startsWith("dev1:");
        assertThat(rawRow(id)).containsEntry("EMAIL", null).containsEntry("EMAIL_HASH", null);

        // the blind index has gone, so the same email address can apply again
        assertThat(applicationService.receive(fred).getId()).isNotEqualTo(id);
    }

    @Test
    void aCustomersDueDiligenceRecordsCannotBeErased() {
        UUID id = applicationService.receive(applicant("Alice", "Smith")).getId();
        workflow.runChecks(id); // APPROVED

        MvcTestResult response = requestErasure(id);

        assertThat(response).hasStatus(HttpStatus.CONFLICT);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Erasure not possible");
    }

    @Test
    void anApplicationUnderReviewMustBeDecidedFirst() {
        UUID id = applicationService.receive(applicant("Uma", "Unverified")).getId();
        workflow.runChecks(id); // REFERRED

        assertThat(requestErasure(id)).hasStatus(HttpStatus.CONFLICT);
    }
}
```

The decline test checks `retainedUntil` is exactly `2031-11-07`. That only works because the decision happened on the test clock's date, 7 November 2026. With the real clock, the expected date would change every day.

### 6. The purge job

Add `purgeExpired` to `ErasureService`:

```java
    /** Deletes personal data whose retention period has ended. Run daily by RetentionJob. */
    @Transactional
    public int purgeExpired() {
        LocalDate today = LocalDate.now(clock);
        List<Application> expired = applications.findByRetainUntilBeforeAndPersonalDataErasedAtIsNull(today);
        for (Application application : expired) {
            application.erasePersonalData(clock.instant());
            auditTrail.record(application.getId(), AuditEventType.PERSONAL_DATA_ERASED, AuditTrail.SYSTEM,
                    "Retention period ended on " + application.getRetainUntil());
        }
        log.info("Retention purge: erased personal data from {} application(s)", expired.size());
        return expired.size();
    }
```

**`RetainUntilBefore(today)`:** the data is kept *through* the retention date and erased from the day after. Note that this runs whether or not anyone asked for erasure. Storage limitation isn't a response to requests; it's a duty.

Schedule it. `privacy/RetentionJob.java`:

```java
package com.example.onboarding.privacy;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetentionJob {

    private final ErasureService erasureService;

    public RetentionJob(ErasureService erasureService) {
        this.erasureService = erasureService;
    }

    /** 02:00 every day, UK time. */
    @Scheduled(cron = "0 0 2 * * *", zone = "Europe/London")
    public void purge() {
        erasureService.purgeExpired();
    }
}
```

And switch scheduling on, in a way tests can switch off. `config/SchedulingConfig.java`:

```java
package com.example.onboarding.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Tests switch this off with onboarding.scheduling.enabled=false and call the jobs directly. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "onboarding.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
```

- **The job class is a thin shell.** All the logic is in `purgeExpired`, which tests call directly. Testing a cron expression by waiting until 2am isn't a plan.
- **Spring's cron has six fields**, starting with seconds: `0 0 2 * * *` is 02:00:00 every day.
- **`zone = "Europe/London"`** pins the schedule to UK time across clock changes. Without it, the job runs at 2am in whatever time zone the server happens to be configured with.
- **`matchIfMissing = true`:** scheduling is on unless explicitly turned off, which `OnboardingIntegrationTest` does.

> **Sidebar: more than one instance.** If the application runs on three servers, all three run the job at 2am. Here that's harmless: the query skips already-erased rows, and the rows are updated in a transaction. For jobs that must run exactly once, teams use a distributed lock such as the ShedLock library, or a scheduler outside the application.

Now the test that moves five years. `src/test/java/com/example/onboarding/privacy/RetentionPurgeTest.java`:

```java
package com.example.onboarding.privacy;

import com.example.onboarding.MutableClock;
import com.example.onboarding.OnboardingIntegrationTest;
import com.example.onboarding.TestClockConfig;
import com.example.onboarding.application.Application;
import com.example.onboarding.application.ApplicationService;
import com.example.onboarding.audit.AuditEvent;
import com.example.onboarding.audit.AuditEventType;
import com.example.onboarding.audit.AuditTrail;
import com.example.onboarding.check.CheckService;
import com.example.onboarding.workflow.OnboardingWorkflow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.example.onboarding.TestApplicants.applicant;
import static org.assertj.core.api.Assertions.assertThat;

@OnboardingIntegrationTest
class RetentionPurgeTest {

    @Autowired ApplicationService applicationService;
    @Autowired OnboardingWorkflow workflow;
    @Autowired ErasureService erasureService;
    @Autowired CheckService checkService;
    @Autowired AuditTrail auditTrail;
    @Autowired MutableClock clock;

    @BeforeEach
    @AfterEach
    void resetClock() {
        clock.setInstant(TestClockConfig.START); // the clock is shared by every test in the context
    }

    void moveTo(LocalDate date) {
        clock.setInstant(date.atTime(3, 0).toInstant(ZoneOffset.UTC));
    }

    @Test
    void personalDataIsKeptUntilTheRetentionDateAndErasedTheDayAfter() {
        UUID id = applicationService.receive(applicant("Fred", "Fraudulent")).getId();
        workflow.runChecks(id);
        LocalDate retainUntil = applicationService.get(id).getRetainUntil();
        assertThat(retainUntil).isEqualTo(LocalDate.of(2031, 11, 7));

        moveTo(retainUntil);
        erasureService.purgeExpired();
        assertThat(applicationService.get(id).getPersonalDataErasedAt()).as("kept on the last day").isNull();

        moveTo(retainUntil.plusDays(1));
        erasureService.purgeExpired();

        Application purged = applicationService.get(id);
        assertThat(purged.getPersonalDataErasedAt()).isNotNull();
        assertThat(purged.getApplicant().givenName()).isNull();
        assertThat(purged.getApplicant().dateOfBirth()).isNull();
        assertThat(checkService.resultsFor(id)).as("non-personal outcomes stay for statistics").hasSize(4);
        assertThat(auditTrail.eventsFor(id).getLast())
                .extracting(AuditEvent::getType, AuditEvent::getDetail)
                .containsExactly(AuditEventType.PERSONAL_DATA_ERASED, "Retention period ended on 2031-11-07");
    }
}
```

- **Both edges of the boundary:** on the retention date itself the data stays, and the day after it goes.
- **`@BeforeEach @AfterEach` on one method.** The `MutableClock` is a single bean shared by every test class in the cached context. A test that leaves it five years in the future would quietly break other tests' date logic, so this test puts it back both before and after.
- **The assertions are about this application only.** Other test classes leave declined applications in the shared database too, and the purge erases those as well, so asserting on the purge's count would make the test depend on test order.

Run the whole suite:

```
[INFO] Tests run: 96, Failures: 0, Errors: 0, Skipped: 0
```

### 7. Try it

Delete `data/` (the schema changed again) and restart. Add to `requests.http`:

```http
### Apply as Fred (the identity provider flags him: declined)
POST http://localhost:8080/applications
Content-Type: application/json

{
  "givenName": "Fred",
  "familyName": "Fraudulent",
  "dateOfBirth": "1990-05-17",
  "email": "fred@example.com",
  "addressLine1": "1 High Street",
  "postcode": "G1 1XQ",
  "expectedMonthlyDeposits": "FROM_1K_TO_5K"
}

> {% client.global.set("fredId", response.body.id); %}

### Run Fred's checks
POST http://localhost:8080/applications/{{fredId}}/checks

### Fred asks for his data to be erased
POST http://localhost:8080/applications/{{fredId}}/erasure-request
```

With curl, after applying as Fred and running his checks:

```bash
curl -s -X POST localhost:8080/applications/<fred's id>/erasure-request
```

```json
{
  "erased": ["EMAIL"],
  "retained": ["NAME", "DATE_OF_BIRTH", "ADDRESS", "CHECK_RESULTS", "AUDIT_TRAIL"],
  "retainedUntil": "2031-11-07",
  "retentionBasis": "Money Laundering Regulations 2017, regulation 40"
}
```

(Your `retainedUntil` is five years after the day you run it.) An application that was never checked:

```json
{"erased":["EMAIL","NAME","DATE_OF_BIRTH","ADDRESS"],"retained":[],"retainedUntil":null,"retentionBasis":null}
```

after which `GET /applications/{id}` shows `"status":"WITHDRAWN"` and `"email":null`. And an approved applicant:

```json
{"detail":"Application 0eff225a-0f95-4f44-9e30-918091a26eb8 opened an account. Its due diligence records are kept while the account is open and for five years after it closes","instance":"/applications/0eff225a-0f95-4f44-9e30-918091a26eb8/erasure-request","status":409,"title":"Erasure not possible"}
```

The application log for all of that contains identifiers and categories only:

```
Received application ad4b8e43-2ab9-47bd-b212-a5e047d81bb9
Erasure request for application ad4b8e43-2ab9-47bd-b212-a5e047d81bb9: erased [EMAIL]
```

---

## Your turn

### Exercise 1: the account closes

An approved applicant's records must be kept "while the account is open and for five years after it closes". The account lives in another system (the core banking ledger), which can tell this service when an account closes.

1. Add `ACCOUNT_CLOSED` to `AuditEventType`.
2. Add `accountClosed(UUID id, LocalDate closedOn)` to `ErasureService`. Only an `APPROVED` application can have its account closed (otherwise throw `ErasureNotPossibleException`). It sets `retainUntil` from the closure date using `RetentionPolicy`, and records an audit event.
3. Expose it as `POST /applications/{id}/account-closure` with a body `{"closedOn": "2027-01-31"}`, returning `{"retainUntil": "2032-01-31"}`.
4. Change `requestErasure` so an approved application **with** a retention date is treated like a declined one: contact details erased, due diligence kept until the retention date.
5. Test: closing on 31 January 2027 gives a retention date of 31 January 2032, and an erasure request afterwards erases only the email. A second test: an application that isn't approved can't be closed (`409`).

<details>
<summary>Hint</summary>

The purge job needs no changes: once `retainUntil` is set, an approved application's data is erased on schedule like any other. Only the "approved" guard in `requestErasure` needs to look at `getRetainUntil()`.

</details>

<details>
<summary>Solution</summary>

`AuditEventType`: add `ACCOUNT_CLOSED` after `PERSONAL_DATA_ERASED`.

`ErasureService`: inject `RetentionPolicy` (add it to the fields and the constructor before `Clock`), change the approved guard, and add the method:

```java
        if (status == ApplicationStatus.APPROVED && application.getRetainUntil() == null) {
```

```java
    /** The account this application opened has closed: the five years start now. */
    @Transactional
    public Application accountClosed(UUID id, LocalDate closedOn) {
        Application application = applications.findByIdForUpdate(id)
                .orElseThrow(() -> new ApplicationNotFoundException(id));
        if (application.getStatus() != ApplicationStatus.APPROVED) {
            throw new ErasureNotPossibleException("Application %s never opened an account".formatted(id));
        }
        application.retainUntil(retentionPolicy.retainUntil(closedOn));
        auditTrail.record(id, AuditEventType.ACCOUNT_CLOSED, AuditTrail.SYSTEM,
                "Account closed on %s; retain until %s".formatted(closedOn, application.getRetainUntil()));
        return application;
    }
```

`ErasureController` (imports: `jakarta.validation.Valid`, `jakarta.validation.constraints.NotNull`, `org.springframework.web.bind.annotation.RequestBody`, `java.time.LocalDate`):

```java
    /** Sent by the core banking system when the customer's account closes. */
    @PostMapping("/applications/{id}/account-closure")
    public AccountClosureResponse accountClosed(@PathVariable UUID id, @Valid @RequestBody AccountClosure request) {
        return new AccountClosureResponse(erasureService.accountClosed(id, request.closedOn()).getRetainUntil());
    }

    public record AccountClosure(@NotNull LocalDate closedOn) {
    }

    public record AccountClosureResponse(LocalDate retainUntil) {
    }
```

`ErasureApiTest` (import `org.springframework.http.MediaType`):

```java
    @Test
    void anAccountClosureStartsTheFiveYearsAndThenContactDetailsCanGo() {
        UUID id = applicationService.receive(applicant("Alice", "Smith")).getId();
        workflow.runChecks(id); // APPROVED

        assertThat(mvc.post().uri("/applications/{id}/account-closure", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"closedOn": "2027-01-31"}
                        """))
                .hasStatusOk()
                .bodyJson().extractingPath("$.retainUntil").isEqualTo("2032-01-31");

        MvcTestResult response = requestErasure(id);
        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.erased").asArray().containsExactly("EMAIL");
        assertThat(response).bodyJson().extractingPath("$.retainedUntil").isEqualTo("2032-01-31");
    }

    @Test
    void onlyAnApprovedApplicationHasAnAccountToClose() {
        UUID id = applicationService.receive(applicant("Alice", "Smith")).getId();

        assertThat(mvc.post().uri("/applications/{id}/account-closure", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"closedOn": "2027-01-31"}
                        """))
                .hasStatus(HttpStatus.CONFLICT);
    }
```

The closure date comes from the request, not the clock: the account system knows when the account closed, and a message about it might arrive a day late. In a real bank this would arrive as an event from the core banking platform rather than an HTTP call.

</details>

### Exercise 2: an audited view for reviewers

Reviewers need the unmasked details to do their job: they compare the date of birth with a sanctions entry, or the address with a utility bill. Give them `GET /reviews/{id}`, taking the `X-Reviewer` header, returning the queue item plus the full `Applicant`. Because every look at personal data should be accountable, record a `PERSONAL_DATA_VIEWED` audit event with the reviewer as the actor.

Add a test to `ReviewApiTest` that fetches Uma Unverified's record as `rita`, checks the family name and date of birth are visible, and checks the last audit event.

<details>
<summary>Solution</summary>

`AuditEventType`: add `PERSONAL_DATA_VIEWED`.

`ReviewService`:

```java
    /** The full record, for a reviewer. Every look at personal data is itself audited. */
    @Transactional
    public Application viewForReview(UUID id, String reviewer) {
        Application application = applications.findById(id)
                .orElseThrow(() -> new ApplicationNotFoundException(id));
        auditTrail.record(id, AuditEventType.PERSONAL_DATA_VIEWED, reviewer, "Viewed for review");
        return application;
    }
```

`ReviewController` (import `com.example.onboarding.application.Applicant`):

```java
    @GetMapping("/{id}")
    public ReviewDetail detail(@PathVariable UUID id,
                               @RequestHeader("X-Reviewer") @NotBlank @Size(max = 100) String reviewer) {
        Application application = reviewService.viewForReview(id, reviewer);
        return new ReviewDetail(toItem(application), application.getApplicant());
    }

    /** Reviewers need the unmasked details to do their job, so they get them, and the look is audited. */
    public record ReviewDetail(ReviewItem review, Applicant applicant) {
    }
```

`ReviewApiTest` (autowire `AuditTrail`; import `AuditEvent`, `AuditEventType`, `AuditTrail`):

```java
    @Test
    void aReviewerSeesTheFullRecordAndTheLookIsAudited() {
        UUID id = referred("Uma", "Unverified");

        MvcTestResult response = mvc.get().uri("/reviews/{id}", id).header("X-Reviewer", "rita").exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.applicant.familyName").isEqualTo("Unverified");
        assertThat(response).bodyJson().extractingPath("$.applicant.dateOfBirth").isEqualTo("1990-05-17");
        assertThat(auditTrail.eventsFor(id).getLast())
                .extracting(AuditEvent::getType, AuditEvent::getActor)
                .containsExactly(AuditEventType.PERSONAL_DATA_VIEWED, "rita");
    }
```

The `Applicant` is serialised by Jackson through its record components, so the overridden `toString()` doesn't affect the JSON. The method is `@Transactional` (not read-only), because it writes the audit event: with `Propagation.MANDATORY`, the event must be part of the read that caused it.

Recording *who looked at whose data* is a standard control for sensitive data. It's how a bank finds a member of staff browsing records they had no reason to open.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 99, Failures: 0, Errors: 0, Skipped: 0
```

(96 from the steps, plus 3 from the exercises.) Run it a couple of times; the concurrency tests from guides 03 and 04 must still pass every time.

New files since guide 04:

```
src/main/java/com/example/onboarding/
├── config/SchedulingConfig.java
└── privacy/
    ├── ErasureController.java
    ├── ErasureNotPossibleException.java
    ├── ErasureService.java
    ├── Masking.java
    ├── PersonalDataField.java
    ├── RetentionJob.java
    ├── RetentionPolicy.java
    └── RetentionProperties.java
src/main/resources/db/migration/
└── V7__retention_and_erasure.sql
src/test/java/com/example/onboarding/
├── MutableClock.java
├── TestClockConfig.java
├── application/ApplicantTest.java
└── privacy/
    ├── ErasureApiTest.java
    ├── MaskingTest.java
    ├── PersonalDataLoggingTest.java
    ├── RetentionPolicyTest.java
    └── RetentionPurgeTest.java
```

Modified: `application.properties`, `Applicant.java` (`toString`), `Application.java` (erasure, retention), `ApplicationRepository.java`, `ApplicationController.java` (minimal response), `ApplicationService.java` (logging, retention), `ReviewService.java`, `ReviewController.java`, `AuditEventType.java`, `ApiExceptionHandler.java`, `OnboardingIntegrationTest.java`, `ApplicationApiTest.java`, `ReviewApiTest.java`, `requests.http`.

---

## Common errors

**`PersonalDataLoggingTest` fails and the output contains `from Applicant[givenName=…`**
A log line (or a string concatenation, or an exception message) printed an `Applicant`. Log the application id instead, and keep the redacting `toString()` on the record.

**`DataIntegrityViolationException: could not execute statement` … `NULL not allowed for column "GIVEN_NAME"`**
`V7` is missing the `DROP NOT NULL` for that column, so the erasure can't write `NULL`. Add a new migration that drops it (don't edit `V7` if it has already run).

**`The bean 'clock', defined in class path resource [com/example/onboarding/config/ClockConfig.class], could not be registered. A bean with that name has already been defined in class path resource [com/example/onboarding/TestClockConfig.class] and overriding is disabled.`**
The test clock method is called `clock()`, the same bean name as the real one. Spring Boot refuses to let one bean silently replace another. Name it `testClock()` and mark it `@Primary`.

**`409 Erasure not possible` for an approved application**
That's the rule working: a customer's due diligence records are kept while the account is open. Exercise 1 lets the account's closure start the retention period.

**The retention job never seems to run when you start the app**
It runs at 02:00 UK time. To try it now, call `ErasureService.purgeExpired()` from a test, or temporarily change the cron to `0 * * * * *` (every minute). With nothing older than five years, it logs `Retention purge: erased personal data from 0 application(s)`.

---

## Recap

- **Data minimisation** applies to every output: API responses show the applicant only what they need (a status and a **masked** email). Internal assessments, such as risk ratings and check results, stay internal.
- **Logs are a data store** with weaker protection than your database. **Log identifiers, not personal data**, and override a record's `toString()` when it holds personal data. Prove it with **`OutputCaptureExtension`**.
- The Money Laundering Regulations require due diligence records to be **kept for five years after the relationship ends** (regulation 40), and then **deleted**.
- The **right to erasure** doesn't override a legal obligation to keep data (Article 17(3)(b)). Erase what you can, keep what you must, and **say what was kept, why and until when**.
- Compute retention dates **once, from the triggering event**, and store them. A **scheduled purge** enforces storage limitation whether or not anyone asks.
- Test time-based rules with a **mutable `Clock`**, check both sides of each boundary, and **reset shared state** around the test.
- Audit **access** to personal data, not just changes to it.

### Interview angle

<details>
<summary>"A customer asks you to delete all their data. What happens?"</summary>

It depends on why the data is held. Data held under a legal obligation, such as due diligence records under the Money Laundering Regulations, must be kept for the statutory period (five years after the relationship ends), and the right to erasure doesn't apply to it while that obligation lasts. Data that isn't needed any more, such as contact details for someone who was declined, can be erased now. Reply within a month, explaining what was erased, what's kept, the legal basis, and when it will be deleted. And make sure deletion really happens at the end of the retention period, through an automated job, not a reminder in someone's diary.

</details>

<details>
<summary>"How do you stop personal data ending up in logs?"</summary>

Log identifiers rather than personal data. Review log statements in code review. Override `toString()` on classes that hold personal data, especially records and Lombok `@Data` classes, whose generated `toString()` prints every field. Be careful with exception messages and request/response logging filters. Back it up with an automated test that captures log output during a realistic flow and asserts known test values don't appear. Some teams also mask known patterns, such as card numbers, in the logging pipeline as a last line of defence.

</details>

<details>
<summary>"What does data minimisation mean for an API design?"</summary>

Return only what the caller needs for its purpose. Don't echo back data the client just sent, mask values that are only needed for recognition (such as `a****@example.com`), and keep internal assessments out of customer-facing responses. Use separate response types for separate audiences (applicant versus reviewer) rather than one DTO with everything, and give privileged views their own access control and audit logging. Add regression tests that assert sensitive fields are absent.

</details>

### Further reading

- [UK GDPR Article 5 (principles)](https://www.legislation.gov.uk/eur/2016/679/article/5) and [Article 17 (right to erasure)](https://www.legislation.gov.uk/eur/2016/679/article/17)
- [ICO: right to erasure](https://ico.org.uk/for-organisations/uk-gdpr-guidance-and-resources/individual-rights/individual-rights/right-to-erasure/), [data minimisation](https://ico.org.uk/for-organisations/uk-gdpr-guidance-and-resources/data-protection-principles/a-guide-to-the-data-protection-principles/data-minimisation/) and [storage limitation](https://ico.org.uk/for-organisations/uk-gdpr-guidance-and-resources/data-protection-principles/a-guide-to-the-data-protection-principles/storage-limitation/)
- [MLR 2017 regulation 40 (record-keeping)](https://www.legislation.gov.uk/uksi/2017/692/regulation/40)
- [Data Protection Act 2018](https://www.legislation.gov.uk/ukpga/2018/12/contents)
- [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)
- [Spring Boot reference: test utilities (including output capture)](https://docs.spring.io/spring-boot/reference/testing/test-utilities.html)

**Simplification to notice:** retention here is per application. A real bank has a retention schedule covering every kind of record it holds (applications, accounts, transactions, complaints, call recordings), each with its own trigger and period, and legal holds that pause deletion when litigation or an investigation needs the data.

---

## You've built a customer onboarding system

Across five guides you've built the front door of a bank:

- A **workflow persisted as state**, with every transition guarded and illegal moves rejected
- **Due diligence checks** behind a provider interface: identity, address, sanctions with fuzzy matching, and PEPs, with a **risk rating** that chooses simplified, standard or enhanced due diligence
- A workflow that **survives provider outages** and resumes where it stopped
- A **review queue** with reasons, the **four-eyes** rule, and an **audit trail** of every decision
- **Two concurrency bugs** found by tests and fixed: reviewers racing each other (a row lock) and the same person applying ten times at once (a unique constraint)
- **Personal data encrypted at rest** with AES-GCM, rotatable keys, and a **blind index** for searching it
- **GDPR-aware design:** minimal responses, clean logs, and an erasure process that respects the law on both sides

Where to go next:

- **Authentication and roles.** Put Spring Security in front: applicants see only their own applications, reviewers are identified from the security context rather than a header, and a second approval of a PEP needs a senior role. The Open Banking API tutorial in this series covers an OAuth2 resource server.
- **Asynchronous checks.** Run the workflow from a queue, with retries and back-off, so slow providers never hold an HTTP request open.
- **A real database.** Run the migrations against PostgreSQL. They're written to be portable, and the concurrency tests make a good check that locking behaves the same way.
- **Ongoing monitoring.** The Fraud monitoring tutorial in this series watches transactions after the account opens.

Back to the [customer onboarding overview](README.md), or the [series README](../README.md).
