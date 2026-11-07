# 02: Due Diligence Checks

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- How banks verify identity and address: **electronic ID&V** versus documents
- **Sanctions screening** and **PEP screening**: what they look for, why a name match is never an automatic decline, and why false positives are a fact of life
- The **risk-based approach**: low, standard and high risk, and the **simplified, standard and enhanced** due diligence that goes with each
- The three automatic outcomes: **approve, refer, decline**
- How to put each check behind an **interface** with swappable providers, and let Spring hand you all of them as a `List`
- A simple **fuzzy name matcher**: normalisation plus edit distance
- How to make a multi-step workflow **resumable**: each step commits on its own, external calls happen outside database transactions, and a retry carries on from the step that failed
- Returning **`503 Service Unavailable` with `Retry-After`** when a dependency is down

By the end, `POST /applications/{id}/checks` runs four checks in order, rates the risk, and moves the application to `APPROVED`, `REFERRED` or `DECLINED`. If a provider times out half-way, the checks that finished are kept, and calling it again picks up where it stopped.

---

## Concepts

### How it works in a real bank

Guide 01 introduced the legal duty: identify the customer and verify who they are ([regulation 28](https://www.legislation.gov.uk/uksi/2017/692/regulation/28) of the Money Laundering Regulations 2017). In practice a bank runs a set of **checks**, almost all of them calls to specialist providers.

**Identity verification (ID&V).** Two broad approaches, often combined:

- **Electronic verification:** the applicant's name, date of birth and address are matched against data held by credit reference agencies and other databases. Regulation 28(19) explicitly allows information from a secure **electronic identification process** to count as a reliable, independent source. This is how most online applications are approved in minutes.
- **Documents:** a passport or driving licence, increasingly photographed in an app alongside a selfie or video so the provider can check the document is genuine and belongs to the person holding it.

If the electronic check can't find a match (people who've just moved to the UK, or who have little credit history, often have a thin "data footprint"), that isn't evidence of anything wrong. The bank **refers** the application and asks for documents instead.

**Address verification** works the same way: matched electronically where possible, or with a document such as a recent utility bill.

**Sanctions screening.** UK law freezes the assets of **designated persons** and forbids making funds available to them. Since 28 January 2026, the [UK Sanctions List](https://www.gov.uk/government/publications/the-uk-sanctions-list) has been the single official source of UK designations; the older OFSI Consolidated List of Asset Freeze Targets closed on that date. ([OFSI](https://www.gov.uk/government/organisations/office-of-financial-sanctions-implementation), part of HM Treasury, still implements and enforces UK financial sanctions.) Banks screen every applicant, and then **re-screen their whole customer base** whenever the list changes. Many also screen against other countries' lists, depending on where they operate.

Screening compares *names*, and names are messy: transliteration from other alphabets (Viktor, Victor), accents, word order, typos. So screening engines do **fuzzy matching**, and fuzzy matching produces **false positives**: alerts for people who merely have a similar name. Each alert goes to an analyst, who compares other details (date of birth, nationality) to confirm or discount it. Only a **confirmed** match leads to freezing and reporting to OFSI. So:

> **A possible sanctions match is a referral, never an automatic decline.**

**PEP screening.** A **politically exposed person** is someone entrusted with a prominent public function (a minister, an MP, a senior judge, and similar roles), and regulation 35 extends the same treatment to their **family members and known close associates**. Being a PEP isn't wrongdoing. But PEPs are at higher risk of involvement in bribery and corruption, so [regulation 35](https://www.legislation.gov.uk/uksi/2017/692/regulation/35) requires **enhanced due diligence**: senior management approval for the relationship, measures to establish the **source of wealth and source of funds**, and enhanced ongoing monitoring. There's no official UK list of PEPs; banks buy PEP data from commercial providers. The regulations also say the starting point for a **domestic** (UK) PEP is a lower level of risk than for a foreign one, unless other factors apply.

**The risk-based approach.** The regulations don't ask for the same checks on everyone. Banks assess risk and scale their due diligence:

| Risk | Due diligence | Meaning |
|---|---|---|
| Low | **Simplified (SDD)**, [regulation 37](https://www.legislation.gov.uk/uksi/2017/692/regulation/37) | The *extent, timing or type* of measures can be adjusted. SDD is still due diligence: the customer must still be identified, and the relationship still monitored. |
| Standard | **Standard CDD** | The regulation 28 measures as normal. |
| High | **Enhanced (EDD)**, [regulation 33](https://www.legislation.gov.uk/uksi/2017/692/regulation/33) | Required for PEPs, for some high-risk countries, and wherever the bank identifies a high risk. More evidence, more scrutiny, more monitoring. |

Real customer risk models weigh many factors: occupation, country of residence, product, expected account activity, delivery channel. You'll build a deliberately tiny one.

**Outcomes.** Every application ends the automatic stage in one of three places:

- **Approve:** everything checked out.
- **Refer:** a person must look. A thin-file identity, an address that couldn't be matched, a possible sanctions or PEP match, a high-risk rating.
- **Decline:** the checks found a clear reason not to proceed, such as a fraud indicator from the identity provider.

### One interface, many providers

Every check has the same shape: take the applicant, ask someone, get an answer. That's an interface:

```java
public interface Check {
    CheckType type();
    CheckResult run(Applicant applicant);
}
```

Each check is a Spring bean implementing it. Ask Spring for a `List<Check>` and it injects every one. To swap the fake identity provider for a real one, you write a new class; the workflow doesn't change. (This is the **strategy pattern**.)

Real providers are slow and sometimes unavailable, so a check has **two** ways to finish without a normal answer, and they mean very different things:

- **`CheckResult.refer(...)`:** the provider answered, and the answer needs a human. That's a *result*. Store it.
- **`ProviderUnavailableException`:** the provider didn't answer. That's *not* a result. Store nothing, and try again later.

### Resumable workflows

Suppose identity passes and then the address provider times out. What should happen?

The naive approach puts the whole sequence in one `@Transactional` method. The address failure rolls everything back, identity included, and the retry calls the identity provider again. That can cost money (providers often charge per check), and worse, it holds a database transaction open while waiting on slow network calls. Long transactions hold connections from a small pool and keep locks longer than they need to.

The approach you'll build:

```
  runChecks(id)                       (not @Transactional)
   ├─ startChecks(id)          tx 1:  RECEIVED → CHECKS_IN_PROGRESS, commit
   ├─ identity.run(applicant)         no transaction: a network call
   ├─ record(IDENTITY, result) tx 2:  insert check result, commit
   ├─ address.run(applicant)          ✗ provider timed out → stop here, 503
   │                                     (IDENTITY is already safely committed)
  runChecks(id) again
   ├─ startChecks(id)          tx 3:  already CHECKS_IN_PROGRESS: a resume
   ├─ IDENTITY done? skip it
   ├─ address.run(applicant)  ...and carry on
   └─ decide(id)               tx n:  rate the risk, choose the outcome, commit
```

Each step's result is its own small transaction. The status says "checks in progress", the stored results say how far they got, and together they're enough to resume. This is the same idea as a **checkpoint** in a batch job.

The API reports the outage as **`503 Service Unavailable`** with a **`Retry-After`** header: "not now, try again in 30 seconds". The client (or an operations dashboard, or a scheduled retry job) simply calls the same endpoint again.

---

## Steps

### 1. The vocabulary of checks

Create the package `com.example.onboarding.check`. `check/CheckType.java`:

```java
package com.example.onboarding.check;

/** The checks every application goes through, in the order they run. */
public enum CheckType {
    IDENTITY,
    ADDRESS,
    SANCTIONS,
    PEP
}
```

The order of the constants *is* the order the checks run. `CheckType.values()` always returns them in declaration order.

`check/CheckOutcome.java`:

```java
package com.example.onboarding.check;

public enum CheckOutcome {
    /** Nothing found that needs a human. */
    CLEAR,
    /** Something a person must look at before the bank decides. */
    REFER,
    /** A reason to decline outright. */
    FAIL
}
```

`check/CheckResult.java`:

```java
package com.example.onboarding.check;

import java.util.Objects;

/**
 * What one check found. The detail is for the bank's reviewers, never for the
 * applicant, and must not repeat the applicant's personal data.
 */
public record CheckResult(CheckOutcome outcome, String detail) {

    public CheckResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
    }

    public static CheckResult clear(String detail) {
        return new CheckResult(CheckOutcome.CLEAR, detail);
    }

    public static CheckResult refer(String detail) {
        return new CheckResult(CheckOutcome.REFER, detail);
    }

    public static CheckResult fail(String detail) {
        return new CheckResult(CheckOutcome.FAIL, detail);
    }
}
```

Notice the Javadoc rule: the detail says "No electronic match for name and date of birth", never "No match for Alice Smith, born 17/05/1990". Results end up in the review queue and the audit trail. Keeping personal data out of them now makes guide 05's erasure work much simpler.

`check/Check.java`:

```java
package com.example.onboarding.check;

import com.example.onboarding.application.Applicant;

/**
 * One step of customer due diligence. Real implementations call an external
 * provider (an identity-verification service, a screening engine).
 */
public interface Check {

    CheckType type();

    /**
     * @throws ProviderUnavailableException if the provider couldn't give an answer.
     *         That's not a result: the check must be tried again later.
     */
    CheckResult run(Applicant applicant);
}
```

`check/ProviderUnavailableException.java`:

```java
package com.example.onboarding.check;

public class ProviderUnavailableException extends RuntimeException {

    private final CheckType checkType;

    public ProviderUnavailableException(CheckType checkType, String message) {
        super(message);
        this.checkType = checkType;
    }

    public CheckType getCheckType() {
        return checkType;
    }
}
```

### 2. Fake providers

Real identity providers offer a **sandbox**: a test environment where particular names or numbers trigger particular answers. Your fakes work the same way:

| Applicant | Identity | Address |
|---|---|---|
| Family name `Unverified` | `REFER`: no electronic match | |
| Family name `Fraudulent` | `FAIL`: fraud flag | |
| Postcode starting `ZZ` | | `REFER`: not found |
| Anyone else | `CLEAR` | `CLEAR` |

Tests also need to make a provider "go down" and count how often it was called. Put that in a small helper, `check/ProviderSimulator.java`:

```java
package com.example.onboarding.check;

import java.util.concurrent.atomic.AtomicInteger;

/** Lets tests count calls to a fake provider and make it fail for a while. */
public class ProviderSimulator {

    private final CheckType type;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger outagesRemaining = new AtomicInteger();

    public ProviderSimulator(CheckType type) {
        this.type = type;
    }

    /** Called at the start of every fake provider call. */
    void call() {
        calls.incrementAndGet();
        if (outagesRemaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new ProviderUnavailableException(type, type + " provider timed out");
        }
    }

    public void failNextCalls(int count) {
        outagesRemaining.set(count);
    }

    public int calls() {
        return calls.get();
    }

    public void reset() {
        calls.set(0);
        outagesRemaining.set(0);
    }
}
```

`getAndUpdate` reads the remaining-outages counter and decrements it (never below zero) in one atomic step, so it stays correct even when several requests call the provider at once.

`check/FakeIdentityCheck.java`:

```java
package com.example.onboarding.check;

import com.example.onboarding.application.Applicant;
import org.springframework.stereotype.Component;

/**
 * Stands in for an electronic identity-verification provider. Like a real
 * provider's sandbox, it answers according to magic test values.
 */
@Component
public class FakeIdentityCheck implements Check {

    private final ProviderSimulator simulator = new ProviderSimulator(CheckType.IDENTITY);

    @Override
    public CheckType type() {
        return CheckType.IDENTITY;
    }

    @Override
    public CheckResult run(Applicant applicant) {
        simulator.call();
        return switch (applicant.familyName().toLowerCase()) {
            case "unverified" -> CheckResult.refer("No electronic match for name and date of birth");
            case "fraudulent" -> CheckResult.fail("Identity flagged by fraud-prevention data");
            default -> CheckResult.clear("Name and date of birth matched");
        };
    }

    public ProviderSimulator simulator() {
        return simulator;
    }
}
```

`check/FakeAddressCheck.java`:

```java
package com.example.onboarding.check;

import com.example.onboarding.application.Applicant;
import org.springframework.stereotype.Component;

/** Stands in for an address-verification provider. Postcodes starting ZZ are "not found". */
@Component
public class FakeAddressCheck implements Check {

    private final ProviderSimulator simulator = new ProviderSimulator(CheckType.ADDRESS);

    @Override
    public CheckType type() {
        return CheckType.ADDRESS;
    }

    @Override
    public CheckResult run(Applicant applicant) {
        simulator.call();
        if (applicant.postcode().startsWith("ZZ")) {
            return CheckResult.refer("Address not found in reference data");
        }
        return CheckResult.clear("Address matched");
    }

    public ProviderSimulator simulator() {
        return simulator;
    }
}
```

> **Sidebar: what a real provider integration adds.** A real `IdentityCheck` would call the provider's REST API with Spring's `RestClient`, with a short timeout, and convert timeouts and `5xx` responses into `ProviderUnavailableException`. It would also send a **reference** with each request, such as your application id, so that if you retry after a timeout the provider can tell you it already answered, and you aren't charged twice. That's [idempotency](../glossary.md#idempotency) again, from the other side.

### 3. Fuzzy name matching

Create the package `com.example.onboarding.screening`. The matcher does two things:

1. **Normalise** both names so that trivial differences disappear: remove accents, lower-case, turn punctuation into spaces, and sort the words, so "Fakeovich, Viktor" and "Viktor Fakeovich" become identical.
2. **Score** how similar the normalised names are using the **Levenshtein distance**: the number of single-character insertions, deletions or substitutions needed to turn one into the other. "viktor" → "victor" is one substitution. The score is `1 − distance / length of the longer name`, so 1.0 is identical.

`screening/NameMatcher.java`:

```java
package com.example.onboarding.screening;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Deliberately simple fuzzy name matching for screening.
 * Normalises both names, then scores them by edit distance.
 */
public final class NameMatcher {

    private NameMatcher() {
    }

    /**
     * "  O'Brien-Smith,  Séan " becomes "brien o sean smith": accents removed,
     * lower case, punctuation turned into spaces, words sorted so order doesn't matter.
     */
    public static String normalise(String name) {
        String withoutAccents = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return Arrays.stream(withoutAccents.toLowerCase(Locale.ROOT).split("[^a-z]+"))
                .filter(word -> !word.isEmpty())
                .sorted()
                .collect(Collectors.joining(" "));
    }

    /** 1.0 for identical normalised names, falling towards 0.0 as they differ. */
    public static double similarity(String a, String b) {
        String x = normalise(a);
        String y = normalise(b);
        int longest = Math.max(x.length(), y.length());
        if (longest == 0) {
            return 0.0;
        }
        return 1.0 - (double) levenshtein(x, y) / longest;
    }

    /** The number of single-character edits needed to turn one string into the other. */
    static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
```

- **`Normalizer.Form.NFD`** splits "é" into "e" plus a separate combining accent mark. `\p{M}` matches those marks, so removing them leaves plain "e".
- **`Locale.ROOT`** for `toLowerCase`. With the default locale, a Turkish machine lower-cases `I` to a dotless `ı`, and names stop matching.
- **The Levenshtein loop** is the classic dynamic-programming algorithm, keeping only two rows of the table at a time.

`src/test/java/com/example/onboarding/screening/NameMatcherTest.java`:

```java
package com.example.onboarding.screening;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NameMatcherTest {

    @Test
    void normalisesCaseAccentsPunctuationAndWordOrder() {
        assertThat(NameMatcher.normalise("  O'Brien-Smith,  Séan ")).isEqualTo("brien o sean smith");
        assertThat(NameMatcher.normalise("FAKEOVICH, Viktor")).isEqualTo(NameMatcher.normalise("Viktor Fakeovich"));
    }

    @Test
    void identicalAfterNormalisingScoresOne() {
        assertThat(NameMatcher.similarity("Mariam Al-Testí", "mariam al testi")).isEqualTo(1.0);
    }

    @Test
    void aOneLetterSpellingVariantStillScoresHighly() {
        assertThat(NameMatcher.similarity("Victor Fakeovich", "Viktor Fakeovich")).isGreaterThan(0.9);
    }

    @Test
    void differentPeopleScoreLow() {
        assertThat(NameMatcher.similarity("Alice Smith", "Viktor Fakeovich")).isLessThan(0.5);
    }

    @Test
    void levenshteinCountsSingleCharacterEdits() {
        assertThat(NameMatcher.levenshtein("kitten", "sitting")).isEqualTo(3);
        assertThat(NameMatcher.levenshtein("", "abc")).isEqualTo(3);
    }
}
```

> **Sidebar: real screening engines.** This matcher is a teaching toy. Commercial screening engines handle transliteration between alphabets, phonetic similarity, nicknames, aliases listed against each designation, partial names, and secondary identifiers such as date of birth and nationality. Every bank tunes the match threshold: too low and analysts drown in false positives; too high and a real designated person slips through, which is a far worse outcome. That trade-off, and the evidence that it was made deliberately, is something regulators look at.

### 4. Sanctions and PEP checks

A list entry, `screening/WatchlistEntry.java`:

```java
package com.example.onboarding.screening;

/** One person on a screening list. */
public record WatchlistEntry(String listId, String name, String note) {
}
```

`screening/SanctionsCheck.java`:

```java
package com.example.onboarding.screening;

import com.example.onboarding.application.Applicant;
import com.example.onboarding.check.Check;
import com.example.onboarding.check.CheckResult;
import com.example.onboarding.check.CheckType;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Screens the applicant's name against a sanctions list. The entries here are
 * invented. A real bank screens against the UK Sanctions List (and usually
 * other countries' lists too), through a screening engine.
 */
@Component
public class SanctionsCheck implements Check {

    static final double MATCH_THRESHOLD = 0.85;

    static final List<WatchlistEntry> LIST = List.of(
            new WatchlistEntry("FAKE-SAN-001", "Viktor Fakeovich", "Asset freeze (fictitious)"),
            new WatchlistEntry("FAKE-SAN-002", "Mariam Al-Testi", "Asset freeze (fictitious)"),
            new WatchlistEntry("FAKE-SAN-003", "Jon O'Sample", "Asset freeze (fictitious)"));

    @Override
    public CheckType type() {
        return CheckType.SANCTIONS;
    }

    @Override
    public CheckResult run(Applicant applicant) {
        String fullName = applicant.givenName() + " " + applicant.familyName();
        return LIST.stream()
                .map(entry -> new Scored(entry, NameMatcher.similarity(fullName, entry.name())))
                .filter(scored -> scored.score() >= MATCH_THRESHOLD)
                .max(Comparator.comparingDouble(Scored::score))
                .map(best -> CheckResult.refer(String.format(Locale.ROOT,
                        "Possible match with %s (score %.2f)", best.entry().listId(), best.score())))
                .orElse(CheckResult.clear("No match"));
    }

    private record Scored(WatchlistEntry entry, double score) {
    }
}
```

- **Every name on the list is invented** and marked fictitious. Don't put real designated people's names in test fixtures: test data gets copied into places you don't expect.
- **The result names the list entry and the score**, which is exactly what an analyst needs to start. It doesn't repeat the applicant's name.
- **`String.format(Locale.ROOT, ...)`**, not `"...".formatted(...)`. `formatted` uses the machine's default locale, and in a German or French locale `%.2f` prints `0,92` instead of `0.92`. Anything that's compared, parsed or stored should be locale-independent.
- **`private record Scored`:** a local helper type to carry an entry and its score through the stream.

`screening/PepCheck.java`:

```java
package com.example.onboarding.screening;

import com.example.onboarding.application.Applicant;
import com.example.onboarding.check.Check;
import com.example.onboarding.check.CheckResult;
import com.example.onboarding.check.CheckType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Screens for politically exposed persons. The entries are invented. Banks buy
 * PEP data from specialist providers: there is no official UK PEP list.
 */
@Component
public class PepCheck implements Check {

    static final double MATCH_THRESHOLD = 0.85;

    static final List<WatchlistEntry> LIST = List.of(
            new WatchlistEntry("FAKE-PEP-001", "Edmund Exampleshire", "Member of Parliament (fictitious)"),
            new WatchlistEntry("FAKE-PEP-002", "Priya Placeholder", "Spouse of a government minister (fictitious)"));

    @Override
    public CheckType type() {
        return CheckType.PEP;
    }

    @Override
    public CheckResult run(Applicant applicant) {
        String fullName = applicant.givenName() + " " + applicant.familyName();
        for (WatchlistEntry entry : LIST) {
            if (NameMatcher.similarity(fullName, entry.name()) >= MATCH_THRESHOLD) {
                return CheckResult.refer("Possible PEP: %s, %s".formatted(entry.listId(), entry.note()));
            }
        }
        return CheckResult.clear("Not a PEP");
    }
}
```

`FAKE-PEP-002` is there as a reminder that family members of PEPs count too.

Tests will build applicants in many places, so make a shared helper. `src/test/java/com/example/onboarding/TestApplicants.java`:

```java
package com.example.onboarding;

import com.example.onboarding.application.Applicant;
import com.example.onboarding.application.ExpectedMonthlyDeposits;

import java.time.LocalDate;

/** Applicants for tests. The fake providers react to some of these names on purpose. */
public final class TestApplicants {

    private TestApplicants() {
    }

    public static Applicant applicant(String givenName, String familyName) {
        return new Applicant(givenName, familyName, LocalDate.of(1990, 5, 17),
                givenName.toLowerCase() + "@example.com", "1 High Street", "G1 1XQ",
                ExpectedMonthlyDeposits.FROM_1K_TO_5K);
    }

    public static Applicant alice() {
        return applicant("Alice", "Smith");
    }
}
```

`src/test/java/com/example/onboarding/screening/SanctionsCheckTest.java`:

```java
package com.example.onboarding.screening;

import com.example.onboarding.check.CheckOutcome;
import com.example.onboarding.check.CheckResult;
import org.junit.jupiter.api.Test;

import static com.example.onboarding.TestApplicants.applicant;
import static org.assertj.core.api.Assertions.assertThat;

class SanctionsCheckTest {

    SanctionsCheck check = new SanctionsCheck();

    @Test
    void clearsSomeoneNotOnTheList() {
        assertThat(check.run(applicant("Alice", "Smith")).outcome()).isEqualTo(CheckOutcome.CLEAR);
    }

    @Test
    void refersANearMatchWithTheListIdAndScore() {
        CheckResult result = check.run(applicant("John", "O'Sample"));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.REFER);
        assertThat(result.detail()).isEqualTo("Possible match with FAKE-SAN-003 (score 0.92)");
    }

    @Test
    void matchesDespitePunctuationAndAccents() {
        assertThat(check.run(applicant("Jon", "OSample")).outcome()).isEqualTo(CheckOutcome.REFER);
        assertThat(check.run(applicant("Mariam", "Al Testí")).outcome()).isEqualTo(CheckOutcome.REFER);
    }
}
```

"John O'Sample" normalises to `john o sample` and the list entry to `jon o sample`: one edit in 13 characters, a score of 0.92. A real analyst would now look at the date of birth. (Exercise 2 teaches the check to do some of that.)

### 5. The risk rating

Create the package `com.example.onboarding.risk`. `risk/DueDiligence.java`:

```java
package com.example.onboarding.risk;

public enum DueDiligence {
    SIMPLIFIED,
    STANDARD,
    ENHANCED
}
```

`risk/RiskRating.java`:

```java
package com.example.onboarding.risk;

public enum RiskRating {
    LOW(DueDiligence.SIMPLIFIED),
    STANDARD(DueDiligence.STANDARD),
    HIGH(DueDiligence.ENHANCED);

    private final DueDiligence dueDiligence;

    RiskRating(DueDiligence dueDiligence) {
        this.dueDiligence = dueDiligence;
    }

    /** How much customer due diligence this level of risk calls for. */
    public DueDiligence dueDiligence() {
        return dueDiligence;
    }
}
```

Enums can have fields and constructors. Putting the mapping on the constant means it can't be forgotten or contradicted somewhere else.

`risk/RiskAssessor.java`:

```java
package com.example.onboarding.risk;

import com.example.onboarding.application.Applicant;
import com.example.onboarding.application.ExpectedMonthlyDeposits;
import com.example.onboarding.check.CheckOutcome;
import com.example.onboarding.check.CheckResult;
import com.example.onboarding.check.CheckType;
import org.springframework.stereotype.Component;

import java.util.Map;

/** A toy customer risk assessment. Real banks' models have many more factors. */
@Component
public class RiskAssessor {

    public RiskRating assess(Applicant applicant, Map<CheckType, CheckResult> results) {
        if (outcomeOf(results, CheckType.PEP) != CheckOutcome.CLEAR
                || outcomeOf(results, CheckType.SANCTIONS) != CheckOutcome.CLEAR) {
            return RiskRating.HIGH;
        }
        boolean allClear = results.values().stream().allMatch(r -> r.outcome() == CheckOutcome.CLEAR);
        if (allClear && applicant.expectedMonthlyDeposits() == ExpectedMonthlyDeposits.UP_TO_1K) {
            return RiskRating.LOW;
        }
        return RiskRating.STANDARD;
    }

    private static CheckOutcome outcomeOf(Map<CheckType, CheckResult> results, CheckType type) {
        CheckResult result = results.get(type);
        if (result == null) {
            throw new IllegalStateException("No result for " + type);
        }
        return result.outcome();
    }
}
```

The rules, labelled honestly as a simplification:

- A possible **PEP** or **sanctions** match makes the customer **high risk**: enhanced due diligence.
- Everything clear and **small expected deposits** gives **low risk**.
- Anything else is **standard**.

`outcomeOf` throws if a result is missing. Rating a customer on incomplete checks is a bug, and should fail loudly.

`src/test/java/com/example/onboarding/risk/RiskAssessorTest.java`:

```java
package com.example.onboarding.risk;

import com.example.onboarding.application.Applicant;
import com.example.onboarding.application.ExpectedMonthlyDeposits;
import com.example.onboarding.check.CheckResult;
import com.example.onboarding.check.CheckType;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static com.example.onboarding.TestApplicants.alice;
import static org.assertj.core.api.Assertions.assertThat;

class RiskAssessorTest {

    RiskAssessor assessor = new RiskAssessor();

    static Map<CheckType, CheckResult> allClear() {
        Map<CheckType, CheckResult> results = new EnumMap<>(CheckType.class);
        for (CheckType type : CheckType.values()) {
            results.put(type, CheckResult.clear("ok"));
        }
        return results;
    }

    @Test
    void cleanApplicantWithStandardDepositsIsStandardRisk() {
        assertThat(assessor.assess(alice(), allClear())).isEqualTo(RiskRating.STANDARD);
    }

    @Test
    void cleanApplicantWithSmallDepositsIsLowRisk() {
        Applicant small = new Applicant("Alice", "Smith", alice().dateOfBirth(), "a@example.com",
                "1 High Street", "G1 1XQ", ExpectedMonthlyDeposits.UP_TO_1K);

        RiskRating rating = assessor.assess(small, allClear());

        assertThat(rating).isEqualTo(RiskRating.LOW);
        assertThat(rating.dueDiligence()).isEqualTo(DueDiligence.SIMPLIFIED);
    }

    @Test
    void aPossiblePepIsHighRiskAndNeedsEnhancedDueDiligence() {
        Map<CheckType, CheckResult> results = allClear();
        results.put(CheckType.PEP, CheckResult.refer("Possible PEP"));

        RiskRating rating = assessor.assess(alice(), results);

        assertThat(rating).isEqualTo(RiskRating.HIGH);
        assertThat(rating.dueDiligence()).isEqualTo(DueDiligence.ENHANCED);
    }
}
```

`EnumMap` is a `Map` implementation for enum keys: compact, fast, and it iterates in the enum's declaration order.

### 6. The decision

Create the package `com.example.onboarding.workflow`. `workflow/Decision.java`:

```java
package com.example.onboarding.workflow;

import com.example.onboarding.application.ApplicationStatus;
import com.example.onboarding.check.CheckOutcome;
import com.example.onboarding.check.CheckResult;
import com.example.onboarding.risk.RiskRating;

import java.util.Collection;

/** The automatic decision once every check has a result. */
public final class Decision {

    private Decision() {
    }

    public static ApplicationStatus decide(Collection<CheckResult> results, RiskRating risk) {
        if (results.stream().anyMatch(r -> r.outcome() == CheckOutcome.FAIL)) {
            return ApplicationStatus.DECLINED;
        }
        if (results.stream().anyMatch(r -> r.outcome() == CheckOutcome.REFER) || risk == RiskRating.HIGH) {
            return ApplicationStatus.REFERRED;
        }
        return ApplicationStatus.APPROVED;
    }
}
```

A pure function: no Spring, no database, easy to read and easy to test. `src/test/java/com/example/onboarding/workflow/DecisionTest.java`:

```java
package com.example.onboarding.workflow;

import com.example.onboarding.application.ApplicationStatus;
import com.example.onboarding.check.CheckResult;
import com.example.onboarding.risk.RiskRating;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionTest {

    @Test
    void allClearAndNotHighRiskIsApproved() {
        assertThat(Decision.decide(List.of(CheckResult.clear("a"), CheckResult.clear("b")), RiskRating.STANDARD))
                .isEqualTo(ApplicationStatus.APPROVED);
    }

    @Test
    void anyReferralGoesToAPerson() {
        assertThat(Decision.decide(List.of(CheckResult.clear("a"), CheckResult.refer("b")), RiskRating.STANDARD))
                .isEqualTo(ApplicationStatus.REFERRED);
    }

    @Test
    void highRiskGoesToAPersonEvenWhenEveryCheckIsClear() {
        assertThat(Decision.decide(List.of(CheckResult.clear("a")), RiskRating.HIGH))
                .isEqualTo(ApplicationStatus.REFERRED);
    }

    @Test
    void aFailBeatsAReferral() {
        assertThat(Decision.decide(List.of(CheckResult.refer("a"), CheckResult.fail("b")), RiskRating.HIGH))
                .isEqualTo(ApplicationStatus.DECLINED);
    }
}
```

### 7. Storing check results

Create `src/main/resources/db/migration/V2__create_application_check.sql`:

```sql
CREATE TABLE application_check (
    id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    application_id UUID                     NOT NULL REFERENCES application (id),
    check_type     VARCHAR(20)              NOT NULL,
    outcome        VARCHAR(10)              NOT NULL,
    detail         VARCHAR(500)             NOT NULL,
    completed_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_application_check UNIQUE (application_id, check_type)
);

ALTER TABLE application ADD COLUMN risk_rating VARCHAR(10);
```

The **unique constraint** says each application has at most one result per check type. If two retries ever raced each other, the database would refuse the second result rather than store two contradictory ones. `risk_rating` is nullable, because it's unknown until the checks finish.

`check/ApplicationCheck.java`:

```java
package com.example.onboarding.check;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** The stored result of one check on one application. Written once, never changed. */
@Entity
@Table(name = "application_check")
public class ApplicationCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_type", nullable = false, length = 20)
    private CheckType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CheckOutcome outcome;

    @Column(nullable = false, length = 500)
    private String detail;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    protected ApplicationCheck() {
        // for JPA
    }

    public ApplicationCheck(UUID applicationId, CheckType type, CheckResult result, Instant completedAt) {
        this.applicationId = applicationId;
        this.type = type;
        this.outcome = result.outcome();
        this.detail = result.detail();
        this.completedAt = completedAt;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public CheckType getType() {
        return type;
    }

    public CheckResult getResult() {
        return new CheckResult(outcome, detail);
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
```

It holds the application's id rather than a `@ManyToOne Application`. Check results are only ever looked up by application id, so a plain column keeps things simple and avoids accidentally loading the whole application.

`check/ApplicationCheckRepository.java`:

```java
package com.example.onboarding.check;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApplicationCheckRepository extends JpaRepository<ApplicationCheck, Long> {

    List<ApplicationCheck> findByApplicationIdOrderById(UUID applicationId);
}
```

Ordering by `id` gives the order the checks ran in. (Ordering by `check_type` would sort the *strings* alphabetically: ADDRESS, IDENTITY, PEP, SANCTIONS.)

`check/CheckService.java`:

```java
package com.example.onboarding.check;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CheckService {

    private final ApplicationCheckRepository checks;
    private final Clock clock;

    public CheckService(ApplicationCheckRepository checks, Clock clock) {
        this.checks = checks;
        this.clock = clock;
    }

    /** Saves one check's result in its own transaction, so it survives a later failure. */
    @Transactional
    public void record(UUID applicationId, CheckType type, CheckResult result) {
        checks.save(new ApplicationCheck(applicationId, type, result, clock.instant()));
    }

    @Transactional(readOnly = true)
    public Map<CheckType, CheckResult> resultsFor(UUID applicationId) {
        Map<CheckType, CheckResult> results = new EnumMap<>(CheckType.class);
        for (ApplicationCheck check : checks.findByApplicationIdOrderById(applicationId)) {
            results.put(check.getType(), check.getResult());
        }
        return results;
    }

    @Transactional(readOnly = true)
    public List<ApplicationCheck> checksFor(UUID applicationId) {
        return checks.findByApplicationIdOrderById(applicationId);
    }
}
```

### 8. Teach the application to be decided

In `Application.java`, add the risk rating (import `com.example.onboarding.risk.RiskRating`), next to the other columns:

```java
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_rating", length = 10)
    private RiskRating riskRating;
```

And two methods:

```java
    /** Records the automatic decision: the risk rating and where the application goes next. */
    public void decide(RiskRating riskRating, ApplicationStatus outcome, Instant now) {
        if (status != ApplicationStatus.CHECKS_IN_PROGRESS) {
            throw new IllegalTransitionException(id, status, outcome);
        }
        moveTo(outcome, now);
        this.riskRating = riskRating;
    }

    public RiskRating getRiskRating() {
        return riskRating;
    }
```

`decide` is stricter than `moveTo`. A referred application may legally move to `APPROVED`, but only a *reviewer* may do that (guide 03). The automatic decision only ever applies to an application whose checks are in progress.

Now `ApplicationService`. Add `CheckService` and `RiskAssessor` to the constructor:

```java
    private final ApplicationRepository applications;
    private final CheckService checkService;
    private final RiskAssessor riskAssessor;
    private final Clock clock;

    public ApplicationService(ApplicationRepository applications, CheckService checkService,
                              RiskAssessor riskAssessor, Clock clock) {
        this.applications = applications;
        this.checkService = checkService;
        this.riskAssessor = riskAssessor;
        this.clock = clock;
    }
```

And two new methods (import `java.util.Map`, the `check` types, `RiskAssessor`, `RiskRating` and `Decision`):

```java
    /** RECEIVED moves to CHECKS_IN_PROGRESS. CHECKS_IN_PROGRESS stays put: that's a resume. */
    @Transactional
    public Application startChecks(UUID id) {
        Application application = get(id);
        if (application.getStatus() != ApplicationStatus.CHECKS_IN_PROGRESS) {
            application.moveTo(ApplicationStatus.CHECKS_IN_PROGRESS, clock.instant());
        }
        return application;
    }

    @Transactional
    public Application decide(UUID id) {
        Application application = get(id);
        Map<CheckType, CheckResult> results = checkService.resultsFor(id);
        RiskRating risk = riskAssessor.assess(application.getApplicant(), results);
        application.decide(risk, Decision.decide(results.values(), risk), clock.instant());
        return application;
    }
```

`startChecks` relies on the workflow rules for its safety. For a `WITHDRAWN` or `APPROVED` application, `moveTo(CHECKS_IN_PROGRESS)` throws `IllegalTransitionException`, so nobody can re-run checks on a decided application.

In `ApplicationController.ApplicationResponse`, add `RiskRating riskRating` after `applicant`, pass `application.getRiskRating()` in `from`, and make `from` **`public static`**, because the new controller in the `workflow` package will use it:

```java
    public record ApplicationResponse(
            UUID id,
            ApplicationStatus status,
            Applicant applicant,
            RiskRating riskRating,
            Instant createdAt,
            Instant statusChangedAt) {

        public static ApplicationResponse from(Application application) {
            return new ApplicationResponse(application.getId(), application.getStatus(),
                    application.getApplicant(), application.getRiskRating(), application.getCreatedAt(), application.getStatusChangedAt());
        }
    }
```

### 9. The workflow

`workflow/OnboardingWorkflow.java`:

```java
package com.example.onboarding.workflow;

import com.example.onboarding.application.Applicant;
import com.example.onboarding.application.Application;
import com.example.onboarding.application.ApplicationService;
import com.example.onboarding.check.Check;
import com.example.onboarding.check.CheckResult;
import com.example.onboarding.check.CheckService;
import com.example.onboarding.check.CheckType;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs the checks for an application, in order, and then decides.
 * Deliberately NOT @Transactional: provider calls happen outside any database
 * transaction, and each step commits on its own, so a failure part-way through
 * keeps everything done so far. Calling it again resumes where it stopped.
 */
@Service
public class OnboardingWorkflow {

    private final ApplicationService applicationService;
    private final CheckService checkService;
    private final Map<CheckType, Check> checks = new EnumMap<>(CheckType.class);

    public OnboardingWorkflow(ApplicationService applicationService, CheckService checkService,
                              List<Check> allChecks) {
        this.applicationService = applicationService;
        this.checkService = checkService;
        for (Check check : allChecks) {
            if (checks.put(check.type(), check) != null) {
                throw new IllegalStateException("Two checks for " + check.type());
            }
        }
        for (CheckType type : CheckType.values()) {
            if (!checks.containsKey(type)) {
                throw new IllegalStateException("No check for " + type);
            }
        }
    }

    public Application runChecks(UUID applicationId) {
        Application application = applicationService.startChecks(applicationId);
        Applicant applicant = application.getApplicant();
        Map<CheckType, CheckResult> done = checkService.resultsFor(applicationId);

        for (CheckType type : CheckType.values()) {   // EnumMap and values() keep the order
            if (done.containsKey(type)) {
                continue;                              // finished on an earlier attempt
            }
            CheckResult result = checks.get(type).run(applicant); // may throw ProviderUnavailableException
            checkService.record(applicationId, type, result);
        }
        return applicationService.decide(applicationId);
    }
}
```

- **`List<Check> allChecks`:** Spring injects every bean that implements `Check`. The constructor turns them into a map by type and **fails at start-up** if a check is missing or duplicated. A misconfigured due diligence process should stop the application starting, not quietly skip sanctions screening.
- **No `@Transactional`.** Each call to `applicationService` or `checkService` is its own transaction, and commits when that call returns. The provider calls in between run with no transaction open.
- **The resume logic is one `if`.** Results already stored are skipped.
- **`ProviderUnavailableException` simply propagates.** Everything committed so far stays committed.

> **Sidebar: what if two requests run the checks at once?** Both could call the same provider, and both could try to record the same check. The unique constraint on `(application_id, check_type)` stops the second result being stored, so the data stays consistent, but you'd pay for a duplicate provider call. Production systems usually run checks from a queue or a scheduled job, one worker per application, rather than from an HTTP request. Guide 03 shows the locking technique you'd use if you needed to prevent it.

### 10. The API

`workflow/CheckController.java`:

```java
package com.example.onboarding.workflow;

import com.example.onboarding.application.ApplicationController.ApplicationResponse;
import com.example.onboarding.check.ApplicationCheck;
import com.example.onboarding.check.CheckOutcome;
import com.example.onboarding.check.CheckService;
import com.example.onboarding.check.CheckType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/applications/{id}/checks")
public class CheckController {

    private final OnboardingWorkflow workflow;
    private final CheckService checkService;

    public CheckController(OnboardingWorkflow workflow, CheckService checkService) {
        this.workflow = workflow;
        this.checkService = checkService;
    }

    /** Runs (or resumes) the checks and returns the decided application. */
    @PostMapping
    public ApplicationResponse run(@PathVariable UUID id) {
        return ApplicationResponse.from(workflow.runChecks(id));
    }

    /** The check results, for the bank's own staff. */
    @GetMapping
    public List<CheckResponse> list(@PathVariable UUID id) {
        return checkService.checksFor(id).stream().map(CheckResponse::from).toList();
    }

    public record CheckResponse(CheckType type, CheckOutcome outcome, String detail, Instant completedAt) {

        static CheckResponse from(ApplicationCheck check) {
            return new CheckResponse(check.getType(), check.getResult().outcome(),
                    check.getResult().detail(), check.getCompletedAt());
        }
    }
}
```

In a real bank, `GET …/checks` would only be available to staff. Check results are internal: guide 03 explains why an applicant usually isn't told them.

Map the outage in `ApiExceptionHandler` (import `ProviderUnavailableException`):

```java
    @ExceptionHandler(ProviderUnavailableException.class)
    ResponseEntity<ProblemDetail> handle(ProviderUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                ex.getMessage() + ". Completed checks are kept; retry to resume.");
        problem.setTitle("Check provider unavailable");
        problem.setProperty("pendingCheck", ex.getCheckType());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "30")
                .body(problem);
    }
```

This handler returns `ResponseEntity<ProblemDetail>` rather than a bare `ProblemDetail`, because it needs to add a header. `Retry-After: 30` means "try again in 30 seconds".

Add to `requests.http`:

```http
### Run the checks (Alice Smith: approved)
POST http://localhost:8080/applications/{{applicationId}}/checks

### See the check results
GET http://localhost:8080/applications/{{applicationId}}/checks

### Apply as someone close to a sanctions list entry
POST http://localhost:8080/applications
Content-Type: application/json

{
  "givenName": "John",
  "familyName": "O'Sample",
  "dateOfBirth": "1990-05-17",
  "email": "john@example.com",
  "addressLine1": "1 High Street",
  "postcode": "G1 1XQ",
  "expectedMonthlyDeposits": "FROM_1K_TO_5K"
}

> {% client.global.set("johnId", response.body.id); %}

### Run John's checks (referred, high risk)
POST http://localhost:8080/applications/{{johnId}}/checks

### John's check results
GET http://localhost:8080/applications/{{johnId}}/checks
```

Restart (Flyway applies `V2`), apply as Alice and run the checks. With curl, use the id from the `POST /applications` response:

```bash
curl -s -X POST localhost:8080/applications/<id>/checks
curl -s localhost:8080/applications/<id>/checks
```

```json
{"id":"07b79650-5945-46bb-8e55-74c472725b79","status":"APPROVED","applicant":{…},"riskRating":"STANDARD",…}
```

```json
[
  {"type":"IDENTITY","outcome":"CLEAR","detail":"Name and date of birth matched","completedAt":"2026-11-07T14:06:48.591214Z"},
  {"type":"ADDRESS","outcome":"CLEAR","detail":"Address matched","completedAt":"2026-11-07T14:06:48.597057Z"},
  {"type":"SANCTIONS","outcome":"CLEAR","detail":"No match","completedAt":"2026-11-07T14:06:48.604865Z"},
  {"type":"PEP","outcome":"CLEAR","detail":"Not a PEP","completedAt":"2026-11-07T14:06:48.607875Z"}
]
```

John O'Sample comes back `"status":"REFERRED"` with `"riskRating":"HIGH"`, and his sanctions result reads:

```json
{"type":"SANCTIONS","outcome":"REFER","detail":"Possible match with FAKE-SAN-003 (score 0.92)", …}
```

Run Alice's checks a second time:

```json
{"detail":"Application 07b79650-5945-46bb-8e55-74c472725b79 cannot move from APPROVED to CHECKS_IN_PROGRESS","instance":"/applications/07b79650-5945-46bb-8e55-74c472725b79/checks","status":409,"title":"Illegal status change"}
```

The workflow rules from guide 01 protect the new endpoint without any extra code.

### 11. Workflow tests, including an outage

`src/test/java/com/example/onboarding/workflow/OnboardingWorkflowTest.java`:

```java
package com.example.onboarding.workflow;

import com.example.onboarding.OnboardingIntegrationTest;
import com.example.onboarding.application.Applicant;
import com.example.onboarding.application.Application;
import com.example.onboarding.application.ApplicationService;
import com.example.onboarding.application.ApplicationStatus;
import com.example.onboarding.application.IllegalTransitionException;
import com.example.onboarding.check.CheckOutcome;
import com.example.onboarding.check.CheckService;
import com.example.onboarding.check.CheckType;
import com.example.onboarding.check.FakeAddressCheck;
import com.example.onboarding.check.FakeIdentityCheck;
import com.example.onboarding.check.ProviderUnavailableException;
import com.example.onboarding.risk.RiskRating;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static com.example.onboarding.TestApplicants.alice;
import static com.example.onboarding.TestApplicants.applicant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@OnboardingIntegrationTest
class OnboardingWorkflowTest {

    @Autowired OnboardingWorkflow workflow;
    @Autowired ApplicationService applicationService;
    @Autowired CheckService checkService;
    @Autowired FakeIdentityCheck identity;
    @Autowired FakeAddressCheck address;

    @BeforeEach
    void resetFakes() {
        identity.simulator().reset();
        address.simulator().reset();
    }

    UUID receive(Applicant applicant) {
        return applicationService.receive(applicant).getId();
    }

    @Test
    void aCleanApplicantIsApprovedAfterAllFourChecks() {
        UUID id = receive(alice());

        Application decided = workflow.runChecks(id);

        assertThat(decided.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(decided.getRiskRating()).isEqualTo(RiskRating.STANDARD);
        assertThat(checkService.resultsFor(id)).containsOnlyKeys(CheckType.values());
        assertThat(checkService.resultsFor(id).values())
                .allMatch(result -> result.outcome() == CheckOutcome.CLEAR);
    }

    @Test
    void noElectronicIdentityMatchIsReferred() {
        assertThat(workflow.runChecks(receive(applicant("Uma", "Unverified"))).getStatus())
                .isEqualTo(ApplicationStatus.REFERRED);
    }

    @Test
    void aFraudFlagIsDeclined() {
        assertThat(workflow.runChecks(receive(applicant("Fred", "Fraudulent"))).getStatus())
                .isEqualTo(ApplicationStatus.DECLINED);
    }

    @Test
    void aPossibleSanctionsMatchIsReferredAsHighRisk() {
        Application decided = workflow.runChecks(receive(applicant("Jon", "O'Sample")));

        assertThat(decided.getStatus()).isEqualTo(ApplicationStatus.REFERRED);
        assertThat(decided.getRiskRating()).isEqualTo(RiskRating.HIGH);
    }

    @Test
    void aProviderOutageKeepsCompletedChecksAndARetryResumes() {
        UUID id = receive(alice());
        address.simulator().failNextCalls(1);

        assertThatThrownBy(() -> workflow.runChecks(id))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("ADDRESS provider timed out");
        assertThat(applicationService.get(id).getStatus()).isEqualTo(ApplicationStatus.CHECKS_IN_PROGRESS);
        assertThat(checkService.resultsFor(id)).containsOnlyKeys(CheckType.IDENTITY);

        Application decided = workflow.runChecks(id);

        assertThat(decided.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(identity.simulator().calls()).as("identity is not checked twice").isEqualTo(1);
        assertThat(address.simulator().calls()).as("address: the failure, then the retry").isEqualTo(2);
    }

    @Test
    void aWithdrawnApplicationCannotBeChecked() {
        UUID id = receive(alice());
        applicationService.withdraw(id);

        assertThatThrownBy(() -> workflow.runChecks(id))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageEndingWith("cannot move from WITHDRAWN to CHECKS_IN_PROGRESS");
    }
}
```

The outage test is the important one. It proves three things at once: the application is left `CHECKS_IN_PROGRESS` (not rolled back, not stuck in a half-decided state); the identity result survived the failure; and the retry **didn't call the identity provider again**.

**Reset the fakes in `@BeforeEach`, not `@AfterEach`.** The fakes are singleton beans in a Spring context that's cached and shared by every test class. Any test that runs checks without resetting afterwards leaves its call count behind. Resetting *before* each test means this test starts clean however the others behave.

And the HTTP side, `src/test/java/com/example/onboarding/workflow/CheckApiTest.java`:

```java
package com.example.onboarding.workflow;

import com.example.onboarding.OnboardingIntegrationTest;
import com.example.onboarding.application.ApplicationService;
import com.example.onboarding.check.FakeAddressCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.UUID;

import static com.example.onboarding.TestApplicants.alice;
import static org.assertj.core.api.Assertions.assertThat;

@OnboardingIntegrationTest
class CheckApiTest {

    @Autowired MockMvcTester mvc;
    @Autowired ApplicationService applicationService;
    @Autowired FakeAddressCheck address;

    @BeforeEach
    void resetFakes() {
        address.simulator().reset();
    }

    @Test
    void runsTheChecksAndListsTheResultsInOrder() {
        UUID id = applicationService.receive(alice()).getId();

        assertThat(mvc.post().uri("/applications/{id}/checks", id))
                .hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("APPROVED");

        assertThat(mvc.get().uri("/applications/{id}/checks", id))
                .hasStatusOk()
                .bodyJson().extractingPath("$[*].type")
                .asArray().containsExactly("IDENTITY", "ADDRESS", "SANCTIONS", "PEP");
    }

    @Test
    void aProviderOutageIsA503WithRetryAfter() {
        UUID id = applicationService.receive(alice()).getId();
        address.simulator().failNextCalls(1);

        MvcTestResult response = mvc.post().uri("/applications/{id}/checks", id).exchange();

        assertThat(response).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response).headers().hasValue("Retry-After", "30");
        assertThat(response).bodyJson().extractingPath("$.pendingCheck").isEqualTo("ADDRESS");
    }
}
```

The 503 body looks like this:

```json
{
  "detail": "ADDRESS provider timed out. Completed checks are kept; retry to resume.",
  "instance": "/applications/d306e975-3d6f-4295-b21b-b25ee314d708/checks",
  "status": 503,
  "title": "Check provider unavailable",
  "pendingCheck": "ADDRESS"
}
```

Run everything:

```
[INFO] Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
```

---

## Your turn

### Exercise 1: test the PEP check

Nothing tests `PepCheck` yet.

1. Create `PepCheckTest` with two tests: Alice Smith is `CLEAR`, and Edmund Exampleshire is `REFER` with the detail `"Possible PEP: FAKE-PEP-001, Member of Parliament (fictitious)"`.
2. Add a test to `OnboardingWorkflowTest` proving that a possible PEP ends up `REFERRED` with a `HIGH` risk rating.

<details>
<summary>Solution</summary>

`src/test/java/com/example/onboarding/screening/PepCheckTest.java`:

```java
package com.example.onboarding.screening;

import com.example.onboarding.check.CheckOutcome;
import com.example.onboarding.check.CheckResult;
import org.junit.jupiter.api.Test;

import static com.example.onboarding.TestApplicants.applicant;
import static org.assertj.core.api.Assertions.assertThat;

class PepCheckTest {

    PepCheck check = new PepCheck();

    @Test
    void clearsSomeoneWhoIsNotAPep() {
        assertThat(check.run(applicant("Alice", "Smith")).outcome()).isEqualTo(CheckOutcome.CLEAR);
    }

    @Test
    void refersAPossiblePepWithTheirRole() {
        CheckResult result = check.run(applicant("Edmund", "Exampleshire"));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.REFER);
        assertThat(result.detail()).isEqualTo("Possible PEP: FAKE-PEP-001, Member of Parliament (fictitious)");
    }
}
```

In `OnboardingWorkflowTest`:

```java
    @Test
    void aPossiblePepIsReferredAsHighRisk() {
        Application decided = workflow.runChecks(receive(applicant("Edmund", "Exampleshire")));

        assertThat(decided.getStatus()).isEqualTo(ApplicationStatus.REFERRED);
        assertThat(decided.getRiskRating()).isEqualTo(RiskRating.HIGH);
    }
```

Identity, address and sanctions are all clear for Edmund. It's the risk rating alone that sends him to a person, which is exactly what regulation 35 requires: enhanced due diligence and senior management approval. Guide 03 builds that approval.

</details>

### Exercise 2: use the date of birth to discount a match

Analysts discount most sanctions alerts by comparing secondary details. Many list entries include a date of birth.

1. Add a `LocalDate dateOfBirth` component to `WatchlistEntry` (after `name`), `null` when the list doesn't give one. Give `FAKE-SAN-001` (Viktor Fakeovich) the date of birth 2 March 1961 and pass `null` for every other entry, including the PEP entries.
2. In `SanctionsCheck`, when the best name match has a listed date of birth **different** from the applicant's, return `CLEAR` with the detail `"Name match with FAKE-SAN-001 (score 0.94) discounted: date of birth differs"`. Otherwise refer as before.
3. Add a test: "Victor Fakeovich", born 1990 (the `TestApplicants` default), is cleared with exactly that detail.

<details>
<summary>Hint</summary>

Replace the `.map(best -> CheckResult.refer(...))` step with `.map(best -> resultFor(best, applicant.dateOfBirth()))`, and write `resultFor` as a private static method. Keep using `String.format(Locale.ROOT, ...)`.

</details>

<details>
<summary>Solution</summary>

`WatchlistEntry`:

```java
package com.example.onboarding.screening;

import java.time.LocalDate;

/** One person on a screening list. The date of birth is null when the list doesn't give one. */
public record WatchlistEntry(String listId, String name, LocalDate dateOfBirth, String note) {
}
```

In `SanctionsCheck` (import `java.time.LocalDate`):

```java
    static final List<WatchlistEntry> LIST = List.of(
            new WatchlistEntry("FAKE-SAN-001", "Viktor Fakeovich", LocalDate.of(1961, 3, 2), "Asset freeze (fictitious)"),
            new WatchlistEntry("FAKE-SAN-002", "Mariam Al-Testi", null, "Asset freeze (fictitious)"),
            new WatchlistEntry("FAKE-SAN-003", "Jon O'Sample", null, "Asset freeze (fictitious)"));

    @Override
    public CheckResult run(Applicant applicant) {
        String fullName = applicant.givenName() + " " + applicant.familyName();
        return LIST.stream()
                .map(entry -> new Scored(entry, NameMatcher.similarity(fullName, entry.name())))
                .filter(scored -> scored.score() >= MATCH_THRESHOLD)
                .max(Comparator.comparingDouble(Scored::score))
                .map(best -> resultFor(best, applicant.dateOfBirth()))
                .orElse(CheckResult.clear("No match"));
    }

    private static CheckResult resultFor(Scored best, LocalDate applicantDateOfBirth) {
        LocalDate listed = best.entry().dateOfBirth();
        if (listed != null && !listed.equals(applicantDateOfBirth)) {
            return CheckResult.clear(String.format(Locale.ROOT,
                    "Name match with %s (score %.2f) discounted: date of birth differs",
                    best.entry().listId(), best.score()));
        }
        return CheckResult.refer(String.format(Locale.ROOT, "Possible match with %s (score %.2f)",
                best.entry().listId(), best.score()));
    }
```

In `PepCheck`, add `null` after each name:

```java
            new WatchlistEntry("FAKE-PEP-001", "Edmund Exampleshire", null, "Member of Parliament (fictitious)"),
            new WatchlistEntry("FAKE-PEP-002", "Priya Placeholder", null, "Spouse of a government minister (fictitious)"));
```

In `SanctionsCheckTest`:

```java
    @Test
    void discountsANameMatchWhenTheListedDateOfBirthDiffers() {
        CheckResult result = check.run(applicant("Victor", "Fakeovich")); // born 1990, listed 1961

        assertThat(result.outcome()).isEqualTo(CheckOutcome.CLEAR);
        assertThat(result.detail())
                .isEqualTo("Name match with FAKE-SAN-001 (score 0.94) discounted: date of birth differs");
    }
```

The result is still `CLEAR`, but the detail records that a match was found **and why it was discounted**. That reasoning is part of the audit trail. If the discounting rule turns out to be wrong, the bank can find every application it affected.

Be careful with automatic discounting in real life. Dates of birth on sanctions lists are sometimes approximate or missing, and a designated person may use false details. Banks decide which automatic discounting rules they're comfortable with, and document why.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 53, Failures: 0, Errors: 0, Skipped: 0
```

(49 from the steps, plus 4 from the exercises.)

New files since guide 01:

```
src/main/java/com/example/onboarding/
├── check/
│   ├── ApplicationCheck.java
│   ├── ApplicationCheckRepository.java
│   ├── Check.java
│   ├── CheckOutcome.java
│   ├── CheckResult.java
│   ├── CheckService.java
│   ├── CheckType.java
│   ├── FakeAddressCheck.java
│   ├── FakeIdentityCheck.java
│   ├── ProviderSimulator.java
│   └── ProviderUnavailableException.java
├── risk/
│   ├── DueDiligence.java
│   ├── RiskAssessor.java
│   └── RiskRating.java
├── screening/
│   ├── NameMatcher.java
│   ├── PepCheck.java
│   ├── SanctionsCheck.java
│   └── WatchlistEntry.java
└── workflow/
    ├── CheckController.java
    ├── Decision.java
    └── OnboardingWorkflow.java
src/main/resources/db/migration/
└── V2__create_application_check.sql
src/test/java/com/example/onboarding/
├── TestApplicants.java
├── risk/RiskAssessorTest.java
├── screening/
│   ├── NameMatcherTest.java
│   ├── PepCheckTest.java
│   └── SanctionsCheckTest.java
└── workflow/
    ├── CheckApiTest.java
    ├── DecisionTest.java
    └── OnboardingWorkflowTest.java
```

Modified: `Application.java` (risk rating, `decide`), `ApplicationService.java` (`startChecks`, `decide`), `ApplicationController.java` (`riskRating` in the response, public `from`), `ApiExceptionHandler.java`, `requests.http`.

---

## Common errors

**`Caused by: java.lang.IllegalStateException: No check for PEP`** (the application, or every test, fails to start)
One of the checks isn't a Spring bean, usually because the `@Component` annotation is missing. That's the workflow's start-up guard doing its job: better no application than one that silently skips PEP screening.

**`expected: CHECKS_IN_PROGRESS but was: RECEIVED`** in `aProviderOutageKeepsCompletedChecksAndARetryResumes`
`runChecks` is `@Transactional`. The provider's exception rolled back the whole method, including the move to `CHECKS_IN_PROGRESS` and the identity result. Remove the annotation: the workflow must not be one big transaction.

**`[identity is not checked twice] expected: 1 but was: 8`**
The fake's call counter includes calls from *other* test classes, because the fakes are singletons in the shared, cached Spring context. Reset the simulators in `@BeforeEach` so each test starts from zero.

**`expected: "Possible match with FAKE-SAN-003 (score 0.92)" but was: "Possible match with FAKE-SAN-003 (score 0,92)"`**
The detail was built with `"...".formatted(...)` (or `String.format` without a locale), and the machine's default locale uses a decimal comma. (This one was produced with a German locale.) Use `String.format(Locale.ROOT, ...)`.

**`409 Illegal status change` from `POST …/checks`**
The application has already been decided (or withdrawn). Checks only run once per application. Create a new application to try again.

---

## Recap

- **Customer due diligence** in practice is a series of **checks**: identity and address (electronic where possible, documents otherwise), **sanctions** screening and **PEP** screening.
- **A failed electronic match or a possible name match is a referral, not a decline.** Only clear evidence, such as a fraud flag, declines automatically.
- Fuzzy matching makes **false positives** inevitable. Thresholds are a deliberate, documented trade-off.
- **Risk drives the depth of due diligence:** simplified (still real CDD), standard, or enhanced (required for PEPs).
- Put each check behind an **interface**; inject **`List<Check>`**; fail at start-up if one is missing.
- A **"couldn't answer"** is not a **result**. Store results, retry outages.
- Make long workflows **resumable**: **one small transaction per step**, **no transaction around external calls**, and skip steps that already have a result.
- Report a dependency outage as **`503`** with **`Retry-After`**.

### Interview angle

<details>
<summary>"How does sanctions screening work, and what happens when there's a match?"</summary>

Every applicant (and then the whole customer base, whenever the list changes) is screened against the UK Sanctions List and usually other jurisdictions' lists. Matching is fuzzy, because of transliteration, spelling variants and word order, so it produces many false positives. A potential match creates an alert for an analyst, who compares secondary identifiers like date of birth and nationality. A confirmed match means the funds must be frozen and the match reported to OFSI. A possible match should never be auto-declined, because most are false positives, and an automated decline could still leave a true match unreported.

</details>

<details>
<summary>"What's a PEP and what does a bank have to do differently?"</summary>

A politically exposed person holds a prominent public function, such as a minister, MP or senior judge. Family members and known close associates are treated the same way. They aren't banned, but they're higher risk for bribery and corruption, so the Money Laundering Regulations require enhanced due diligence: senior management approval for the relationship, establishing the source of wealth and source of funds, and enhanced ongoing monitoring. The treatment continues for at least 12 months after the person leaves office. A domestic PEP starts from a lower level of risk than a foreign one.

</details>

<details>
<summary>"A workflow calls three external services. The second one times out. What do you do?"</summary>

Don't wrap the whole thing in one database transaction. Make each step record its result in its own short transaction, and call external services outside any transaction. On failure, leave the overall status as "in progress" and return something retryable (`503` with `Retry-After`, or put a message back on a queue). A retry skips completed steps. Distinguish "the service said no" (a result, stored) from "the service didn't answer" (not a result, retried). Send an idempotency reference to each provider so a retry after a timeout doesn't double-charge.

</details>

<details>
<summary>"How would you make it easy to swap identity-verification providers?"</summary>

Hide each provider behind an interface such as `Check` with a common result type, and implement one class per provider. The workflow depends only on the interface. Spring can inject all implementations as a `List` or `Map`, and configuration (or a conditional bean) chooses which one is active. Keep the provider's own response codes inside the adapter, and map them to your own outcomes there.

</details>

### Further reading

- [MLR 2017 regulation 33 (enhanced due diligence)](https://www.legislation.gov.uk/uksi/2017/692/regulation/33), [regulation 35 (PEPs)](https://www.legislation.gov.uk/uksi/2017/692/regulation/35) and [regulation 37 (simplified due diligence)](https://www.legislation.gov.uk/uksi/2017/692/regulation/37)
- [The UK Sanctions List](https://www.gov.uk/government/publications/the-uk-sanctions-list) and the [Office of Financial Sanctions Implementation](https://www.gov.uk/government/organisations/office-of-financial-sanctions-implementation)
- [FCA Financial Crime Guide](https://www.handbook.fca.org.uk/handbook/FCG/): what the FCA expects of firms' financial crime systems and controls, with examples of good and poor practice
- [JMLSG guidance](https://www.jmlsg.org.uk/guidance/current-guidance/), Part I, chapter 5 (customer due diligence)

**Simplification to notice:** real onboarding often runs the checks asynchronously from a queue, with automatic retries and back-off, and a dashboard showing applications stuck in progress. The resumable design you built is what makes that safe.

**Next:** [03: Manual review and the audit trail](03-manual-review-and-audit-trail.md). The people behind "refer": a review queue, the four-eyes rule, a record of every decision, and what happens when two reviewers click at the same moment.
