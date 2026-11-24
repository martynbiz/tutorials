# 07: Validating what you receive

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](README.md) for version notes.

## What you'll learn

- Why a provider's data is **untrusted input**, just like a form a stranger fills in, and what the OWASP API Security Top 10 calls the risk (**API10:2023 Unsafe Consumption of APIs**)
- **Three rules** for anything the provider sends, written down in **one place**: **required** fields must be there, **optional** ones stay `null` when missing, and every value must **fit**
- Why text that's too long for its column is **skipped, never cut short**, and why that matters more in an importer that never refreshes a profile
- Why a bad **player** is skipped and the import carries on, but a bad **team** fails the import and nothing is saved
- Why **`ddl-auto=validate` doesn't check column lengths**, and how to keep the sizes in your entities and your migrations in step
- What validation here **doesn't** have to do: SQL injection and HTML escaping, and where they're dealt with instead
- How to test the rules with **plain unit tests**, and how to watch them work against **awkward fixtures** in the fake provider: a 121-character name, a `"Wing-back"`, a profile with `"name": null`, and a team whose name doesn't fit

By the end, importing the fake provider's new club, Northgate United, **completes** with three of its five players stored and the other two skipped, each with its reason. Importing a team whose name is too long for our database **fails**, with a message that says why, and leaves nothing behind.

---

## Prerequisites for this guide

You need `importer` and `fake-provider` as they were at the end of [06: Rate limits and quotas](06-rate-limits-and-quotas.md), with all **58** tests passing. In particular:

- `TeamImporter.importTeam` already **skips** a squad member whose profile is empty, or has no `id` or no `name`, records why (`Player 9000014: the provider has no profile`), and **counts them as done**, so the import still completes ([guide 04](04-squads-and-players.md)). The check is a private method, `problemWith`, which this guide replaces.
- `Position.fromProvider` turns `"Goalkeeper"` and friends into our enum, and anything else into `null`; `TeamService.createAndLinkPlayer` logs a warning when that happens. A birth date that won't parse becomes a `null` birth year (guide 04).
- The migrations set the column sizes: `team.name varchar(100)`, `team.hud_name varchar(10)`, `team.country varchar(60)` (`V1`, [guide 03](03-storing-teams.md)); `player.name varchar(100)`, `player.nationality varchar(60)` (`V2`, guide 04).
- `ImportRunner` records any exception from an import as **`FAILED`**, with the exception's message as the `error`, and `GET /imports/{id}` shows the status, the counts and the skipped reasons ([guide 05](05-imports-in-the-background.md)). Every endpoint needs `-u admin:dev-only-password`.
- The fake provider reads `mappings/` and `__files/` at start-up, so it needs a **restart** after you add files to them.

Nothing changes in `pom.xml` or `application.properties`, and there's no new migration. No API-Football account is needed.

---

## Concepts

### The provider's data is input

When a user fills in a form, you check what they typed before you store it. Nobody needs persuading of that. A reply from API-Football *feels* different: it's a well-known service, its JSON has the same shape every time, and you're paying it (or at least registered with it). It's easy to treat what it sends as if your own code had produced it.

It isn't. It's data from **another system**, written by people you don't know, which changes on a schedule you don't see. OWASP's [API Security Top 10](https://owasp.org/API-Security/editions/2023/en/0xaa-unsafe-consumption-of-apis/) has an entry for exactly this, **API10:2023 Unsafe Consumption of APIs**. Its point: developers tend to trust data from the APIs they integrate with more than they trust user input, and so check it less. A provider with a bug, a provider that changes its format, or a provider that has been compromised can then put whatever it likes into your system. OWASP's advice is to send all traffic over TLS (the importer only talks to `https://` for the real API, and never turns off certificate checks), and to **validate and sanitise everything an integrated API sends you before you use it**. That's this guide.

The risks for the importer are ordinary rather than dramatic. Nobody is likely to attack a football data provider to get at your game. But the same three things go wrong with any provider sooner or later:

| What arrives | What happens without a rule |
| --- | --- |
| A field you need is **missing** (`"name": null`) | A database error, or a player with no name |
| A value you didn't expect (`"position": "Wing-back"`) | An exception that ends the import, if you map it carelessly |
| Text that's **too long** for its column (a 121-character name) | A database error, and the import **fails**, taking the rest of the squad with it |

You've been defending against some of this since guide 02, one case at a time:

| Guide | The provider sends | The importer |
| --- | --- | --- |
| 02 | `errors` as an array **or** an object | Reads both (`ProviderErrorsDeserializer`) |
| 02 | `200 OK` with the reasons in `errors`; `results: 0` | Treats them as a rejection and "not found", not as data |
| 04 | An empty profile, or one with no `id` or `name` | Skips the player, and says why |
| 04 | A position it hasn't used before | Stores `null`, and logs a warning |
| 04 | A birth date that won't parse | Stores a `null` birth year |
| 06 | A limit header that isn't a number | Ignores it |

What's missing is **size**, and a **single place** that says what the rules are. This guide adds both.

### Three rules

Everything the importer receives is checked against three rules. They live in one class, `team/Validation`, so the next developer can read them in one go:

1. **Required fields must be there.** A team needs an `id` and a `name`; so does a player's profile. Without either, we can't store the record at all: the id is how we find it again, and the name is what anything using the data shows. A missing (or blank) required field means the record is **skipped**, and the reason is **recorded** on the import.
2. **Optional fields stay `null` when they're missing.** Nationality, birth year, country, the team's code: if the provider doesn't say, we store `null`. **We never invent a value.**
3. **Values must fit.** A position must be one we know; anything else becomes `null`, with a warning in the log (guide 04's `Position.fromProvider`). Text must fit its column; if it doesn't, that's treated like rule 1: the record is **skipped, never cut short**.

Rule 2 is the one that's easiest to break with good intentions. Why not store `"Unknown"` for a missing nationality, or `0` for a missing birth year? Because a default **looks like data**. A game that shows nationalities would show a country called "Unknown"; a stats screen would work out that the player is 2026 years old. `null` says "we don't know", and forces whatever reads it to decide what to do about that, which is the right place for the decision. (The HUD name from guide 03 isn't an invented value: it's a field of **ours**, worked out by a rule from data we do have. The provider's `code` is still stored as it came, or not at all.)

Rule 2 also means **not tidying** what the provider sends. Guide 03 left a question open: what about a code like `"rIv"`, in odd capitals? It fits its column, so it's stored as sent. Upper-casing it would be guessing what the provider meant, and the day the provider sends codes in lower case on purpose, the guess would be wrong. The importer checks what **our database** needs. What the game does with an odd code is the game's business.

### Too long: skip it, don't cut it short

`player.name` is `varchar(100)`. When a profile's name is 121 characters, there are two ways to make it fit, and only one is honest.

**Cut it to 100 characters** and store it. The import works, and it's wrong in a way nobody will ever notice:

- **A shortened name is a wrong name that looks right.** `Sir Bartholomew … de la Val` is in the database, with nothing to say it isn't the whole name. Two different long names could even be cut to the same text.
- **It never heals.** Guide 04 decided a profile is fetched **once** and never refreshed. A cut-short name would be stored for good, even after the provider fixed its data.

**Skip the player**, and record why: `Player 9000022: name is 121 characters long (at most 100 fit)`. The team is missing a player, **and the import says so**. Because no player was created, the **next** import of the team asks for that profile again, for 1 request, exactly as it does for guide 04's empty profile. Once the provider sends something that fits, the player arrives. That's the cost of the rule: invalid data costs a request on every import of that team, until the provider fixes it.

The same goes for a nationality longer than 60 characters, and for a team's name, code or country.

### A bad player is skipped; a bad team fails the import

The rules are the same for teams and players. What happens when they're broken isn't:

| Breaks a rule | What happens | Why |
| --- | --- | --- |
| A **player's** profile | The player is **skipped**, with a reason. The import carries on, and **completes** | One player is part of a squad. The rest of the squad is still worth having, and guide 04 already counts a skipped player as done |
| The **team** | The import **fails** (`FAILED`, with the reason as its `error`), and **nothing is saved** | The import is *of* this team. Without a team row there's nothing to link players to, so carrying on would only spend requests on profiles we can't use |

The team is checked **before** it's saved, and before the squad is asked for. A team that breaks the rules costs exactly one request, and leaves no row behind.

### Where the sizes live

The column sizes are in the migrations: `name varchar(100)`, `nationality varchar(60)`, and so on. The check needs the same numbers in Java. Copying `100` into the validation code would work until someone changes one and not the other.

You might hope Hibernate would catch that. It won't. Guide 03 noticed that **`ddl-auto=validate` checks that every column exists with a compatible type, but not its length**. That's still true when the entity says `@Column(length = 100)`: with H2 and Hibernate 7, an entity that says `length = 200` for a column the table has as `varchar(60)` starts without a word of complaint (this was tried while writing this guide). So the sizes go in **one place in Java**, constants on the entities that the `@Column` annotations and the validation both use, with a comment pointing at the migration. Keeping the constants and the SQL in step is **your job**, by hand: if a later migration widens a column, change the constant in the same commit.

### What validation doesn't have to do here

Two things that "untrusted input" usually brings to mind are handled elsewhere, and it's worth knowing where:

- **SQL injection.** A player called `Robert'); DROP TABLE player;--` is just a name to the importer. Spring Data JPA and Hibernate send values to the database as **bound parameters**, separately from the SQL, so text is stored as text and never run. That's true as long as nobody builds SQL by gluing strings together, which nothing in this series does.
- **HTML.** A name containing `<script>` is stored exactly as sent. If something later shows names on a web page, **that** code must escape them (Thymeleaf's `th:text`, from the todo app, does). Escaping on the way in would damage the data for everything that isn't a web page, such as the game.

So the rules here are about **shape and size**: is everything we need there, and does it fit?

> **Done the REST API series?** Its [guide 02](../rest-api/02-creating-and-changing-data.md) validated **requests** with Bean Validation: `@NotBlank` and `@Size(max = 200)` on a record, `@Valid` on the controller, and a `400` telling the caller which field to fix. Same idea, different situation. There, the caller is waiting for an answer and can fix their request. Here, nobody is listening: the provider won't read our error, and the outcome isn't a status code but **skip this player** or **fail this import**. So the rules are a small plain-Java class, whose messages go into the import's skipped reasons, rather than annotations and a `400`. Bean Validation could do the checking (with a `Validator` called by hand), but for five fields, a class you can read top to bottom is simpler, and needs no new dependency.

---

## Steps

Everything new goes in the `team` package, next to `TeamImporter`: the rules are about what we're willing to **store**, and that's the `team` package's business. The provider's records (`ApiTeam`, `ApiPlayer`) don't change.

### 1. Column sizes on the entities

Open `team/Team.java`. Add three constants at the top of the class, and use them in the `@Column` annotations of the three text fields:

```java
@Entity
public class Team {

    // Column sizes, as in V1__create_team.sql. Validation checks text fits before saving.
    static final int NAME_LENGTH = 100;
    static final int HUD_NAME_LENGTH = 10;
    static final int COUNTRY_LENGTH = 60;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private long apiId;

    @Column(nullable = false, length = NAME_LENGTH)
    private String name;

    @Column(nullable = false, length = HUD_NAME_LENGTH)
    private String hudName;

    @Column(length = COUNTRY_LENGTH)
    private String country;
```

The rest of `Team` is unchanged. Then the same in `team/Player.java`:

```java
@Entity
public class Player {

    // Column sizes, as in V2__create_player.sql. Validation checks text fits before saving.
    static final int NAME_LENGTH = 100;
    static final int NATIONALITY_LENGTH = 60;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private long apiId;

    @Column(nullable = false, length = NAME_LENGTH)
    private String name;

    @Column(length = NATIONALITY_LENGTH)
    private String nationality;
```

- **`static final int`, with no access modifier**: a constant, visible only inside the `team` package. The validation (next step) is in the same package, and nothing else needs the numbers.
- **`@Column(length = NAME_LENGTH)`** says what the column holds, in the same way guide 03's `nullable = false` does. With `ddl-auto=validate`, Hibernate creates nothing from it, and, as the Concepts said, **doesn't check it either**. It's there so the entity reads truthfully, and so the number has one home in Java.
- **The comment points at the migration**, because that's what really decides the size. Change one, change the other, in the same commit.
- **`hudName` holds the provider's `code`** when there is one (guide 03), so it's the `code` that has to fit in 10 characters. A HUD name made from the team's name is always 3.

Restart the importer if it's running: it starts exactly as before, and the tests still pass. Nothing has changed in the database.

### 2. The rules: `Validation`

Create `team/Validation.java`:

```java
package com.example.importer.team;

import com.example.importer.provider.ApiPlayer;
import com.example.importer.provider.ApiTeam;

/**
 * The rules for data we receive from the provider. It's untrusted input, like anything a user types in.
 * <ol>
 * <li>Required fields (the api id and the name) must be there. If not, the record is skipped and we say why.</li>
 * <li>Optional fields that are missing stay null. We never invent a value.</li>
 * <li>Values must fit: position must be one we know (otherwise null, with a warning), and text must fit its
 * column. Too long is treated like rule 1: skipped, never silently cut short.</li>
 * </ol>
 * Each method returns why the record can't be stored, or null if it can.
 */
final class Validation {

    private Validation() {
    }

    static String problemWithTeam(ApiTeam team) {
        if (team.id() == null) {
            return "team has no id";
        }
        if (isBlank(team.name())) {
            return "team has no name";
        }
        if (tooLong(team.name(), Team.NAME_LENGTH)) {
            return tooLongMessage("name", team.name(), Team.NAME_LENGTH);
        }
        if (tooLong(team.code(), Team.HUD_NAME_LENGTH)) {
            return tooLongMessage("code", team.code(), Team.HUD_NAME_LENGTH);
        }
        if (tooLong(team.country(), Team.COUNTRY_LENGTH)) {
            return tooLongMessage("country", team.country(), Team.COUNTRY_LENGTH);
        }
        return null;
    }

    static String problemWithProfile(ApiPlayer profile) {
        if (profile.id() == null) {
            return "profile has no id";
        }
        if (isBlank(profile.name())) {
            return "profile has no name";
        }
        if (tooLong(profile.name(), Player.NAME_LENGTH)) {
            return tooLongMessage("name", profile.name(), Player.NAME_LENGTH);
        }
        if (tooLong(profile.nationality(), Player.NATIONALITY_LENGTH)) {
            return tooLongMessage("nationality", profile.nationality(), Player.NATIONALITY_LENGTH);
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Null is fine: optional fields may be missing (rule 2). */
    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }

    private static String tooLongMessage(String field, String value, int max) {
        return field + " is " + value.length() + " characters long (at most " + max + " fit)";
    }
}
```

- **The class comment is the three rules**, word for word from the Concepts. Someone wondering "what does the importer accept?" finds the answer in one place.
- **`final class` with a `private` constructor**: a holder for static methods, which nobody can create or extend. No `public`: only `TeamImporter`, in the same package, uses it.
- **Each method returns a reason, or `null` for "fine"**, exactly like guide 04's `problemWith`, which this replaces. The reason is written to go after `Player 9000022: `, so it reads as a sentence in the import's skipped reasons.
- **Required first, then fit.** A record is checked in that order, and the **first** problem is the one reported. A profile with no name never gets as far as a length check.
- **`isBlank`** treats `null`, `""` and `"   "` the same: no usable name. That's guide 04's rule, unchanged.
- **`tooLong` says `null` is fine.** That's rule 2 at work: `nationality`, `code` and `country` are optional, so a missing one passes, and stays `null` when it's stored. Only text that's **there and too long** is a problem.
- **The length is `String.length()`**, the number of Java `char`s. For almost every name that's the number of characters. A character outside the basic range, such as an emoji, counts as 2, so the check can only ever be **stricter** than the database, never looser. That's the safe way round.
- **Position isn't here.** Rule 3's position half is guide 04's `Position.fromProvider` and the warning in `TeamService.createAndLinkPlayer`, both unchanged: an unknown position doesn't stop a player being stored, so there's nothing for these methods to report.

### 3. An exception for a team we can't store

Create `team/InvalidTeamDataException.java`:

```java
package com.example.importer.team;

import com.example.importer.provider.ProviderException;

/** The provider sent a team we can't store. Without the team there's nothing to import, so the import fails. */
public class InvalidTeamDataException extends ProviderException {

    public InvalidTeamDataException(long apiId, String problem) {
        super("Team " + apiId + " can't be stored: " + problem);
    }
}
```

It extends guide 02's **`ProviderException`**: the problem is what the provider sent, not anything wrong with the importer. The message is the one the import will show as its `error`, for example `Team 900004 can't be stored: name is 106 characters long (at most 100 fit)`.

### 4. Use the rules in `TeamImporter`

Open `team/TeamImporter.java`. In `importTeam(long apiId, ImportProgress progress)`, step 1 now checks the team **before** saving it:

```java
        // 1. The team itself: check it, then create or update
        ApiTeam apiTeam = provider.fetchTeam(apiId).team();
        String teamProblem = Validation.problemWithTeam(apiTeam);
        if (teamProblem != null) {
            throw new InvalidTeamDataException(apiId, teamProblem);
        }
        Team team = teamService.saveFromProvider(apiTeam);
```

and, in step 3's loop, the profile check uses `Validation`:

```java
            Optional<ApiPlayer> profile = provider.fetchProfile(playerApiId);
            profilesFetched++;
            progress.profileFetched();
            String problem = profile.isEmpty() ? "the provider has no profile"
                    : Validation.problemWithProfile(profile.get());
            if (problem != null) {
                String reason = "Player " + playerApiId + ": " + problem;
                skipped.add(reason);
                progress.playerSkipped(reason);
                continue;
            }
            teamService.createAndLinkPlayer(team.getId(), profile.get());
```

**Delete the private `problemWith` method** at the bottom of the class: its checks are in `Validation` now. Add one import:

```java
import com.example.importer.provider.ApiTeam;
```

Two things to notice:

- **The team is checked before `saveFromProvider`, and before the squad is fetched.** A team that breaks a rule throws straight out of `importTeam`: one request spent, and nothing saved. Guide 05's `ImportRunner` catches it like any other exception, so the import ends `FAILED`, with the exception's message as its `error`. No new code is needed there.
- **An empty profile isn't a validation problem**, so it stays here, in the importer: there's no record to check. The ternary (`condition ? a : b`) picks guide 04's reason when there's no profile, and asks `Validation` when there is one. Everything after it is guide 04's "skipped counts as done", unchanged: the reason is recorded, and the loop moves on.

`TeamService` doesn't change. By the time `createAndLinkPlayer` runs, the profile has an id and a name, and every piece of text fits.

### 5. Test the rules

The rules are plain Java, so they get plain unit tests, with no Spring and no WireMock. Create `src/test/java/com/example/importer/team/ValidationTest.java`:

```java
package com.example.importer.team;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.example.importer.provider.ApiPlayer;
import com.example.importer.provider.ApiTeam;

class ValidationTest {

    @Test
    void aProfileWithAnIdAndANameIsFineEvenWithEverythingElseMissing() {
        assertThat(Validation.problemWithProfile(profile(9000001L, "C. Lindqvist", null))).isNull();
    }

    @Test
    void aProfileWithoutAnIdOrANameIsRejected() {
        assertThat(Validation.problemWithProfile(profile(null, "C. Lindqvist", null))).isEqualTo("profile has no id");
        assertThat(Validation.problemWithProfile(profile(9000001L, null, null))).isEqualTo("profile has no name");
        assertThat(Validation.problemWithProfile(profile(9000001L, "   ", null))).isEqualTo("profile has no name");
    }

    @Test
    void textThatDoesNotFitIsRejectedNotCutShort() {
        assertThat(Validation.problemWithProfile(profile(9000001L, "x".repeat(101), null)))
                .isEqualTo("name is 101 characters long (at most 100 fit)");
        assertThat(Validation.problemWithProfile(profile(9000001L, "A. Player", "y".repeat(61))))
                .isEqualTo("nationality is 61 characters long (at most 60 fit)");
    }

    @Test
    void textThatExactlyFitsIsFine() {
        assertThat(Validation.problemWithProfile(profile(9000001L, "x".repeat(100), "y".repeat(60)))).isNull();
    }

    @Test
    void teamsFollowTheSameRules() {
        assertThat(Validation.problemWithTeam(team(900001L, "Riverside Rovers", "RIV"))).isNull();
        assertThat(Validation.problemWithTeam(team(900001L, "Riverside Rovers", null))).isNull();
        assertThat(Validation.problemWithTeam(team(null, "Riverside Rovers", "RIV"))).isEqualTo("team has no id");
        assertThat(Validation.problemWithTeam(team(900001L, "", "RIV"))).isEqualTo("team has no name");
        assertThat(Validation.problemWithTeam(team(900001L, "z".repeat(106), null)))
                .isEqualTo("name is 106 characters long (at most 100 fit)");
        assertThat(Validation.problemWithTeam(team(900001L, "Riverside Rovers", "RIVERSIDE-R")))
                .isEqualTo("code is 11 characters long (at most 10 fit)");
    }

    private static ApiPlayer profile(Long id, String name, String nationality) {
        return new ApiPlayer(id, name, null, null, null, null, nationality, null, null);
    }

    private static ApiTeam team(Long id, String name, String code) {
        return new ApiTeam(id, name, code, "England", null, false, null);
    }
}
```

- **The test is in the `team` package**, like the class it tests, so it can call the package-private `Validation` methods directly, as guide 03's `TeamTest` calls `Team.hudNameFor`.
- **`"x".repeat(101)`** makes a string of 101 `x`s. It's the easiest way to get text of an exact length, and the numbers in the test say exactly where the boundary is: 100 fits, 101 doesn't.
- **`textThatExactlyFitsIsFine`** is the other side of the boundary. Without it, a `>=` where there should be a `>` would go unnoticed, and a name of exactly 100 characters would be skipped for no reason.
- **The helpers build the provider's records directly**, with `null` for everything the test doesn't care about. That `null`s everywhere still pass is rule 2, proved: `aProfileWithAnIdAndANameIsFineEvenWithEverythingElseMissing` has no age, no birth, no nationality, no position.
- **`"RIVERSIDE-R"`** is 11 characters: the code check uses the HUD name's 10.

### 6. Test the two outcomes

The unit tests prove the rules. Two more tests prove what an **import** does when they're broken.

**A player is skipped, and the import completes.** Add to `team/SquadImportTest.java`:

```java
    @Test
    void aNameThatDoesNotFitIsSkippedWithTheReasonNotCutShort() {
        String longName = "Sir Bartholomew Alexander Montgomery-Fitzwilliam Ashcroft-Delacroix "
                + "de la Vallée-Hargreaves Junior the Third of Northgate";
        stubTeam(900050);
        stubSquad(900050, 9000501, 9000502);
        stubProfiles(9000501);
        stubProfile(9000502, ProviderJson.profile(9000502, longName));

        ImportResult result = importer.importTeam(900050);

        assertThat(result.skippedReasons())
                .containsExactly("Player 9000502: name is 121 characters long (at most 100 fit)");
        assertThat(players.existsByApiId(9000502)).isFalse();
        assertThat(apiIdsOfPlayersIn(900050)).containsExactly(9000501L);
        assertThat(result.team().lastImportedAt()).isNotNull(); // skipped still counts as done
    }
```

Every helper is guide 04's. The test checks all four parts of the outcome: the reason, **no** player row (so nothing was cut short and stored), the **other** player linked (so the import carried on), and `lastImportedAt` set (so it completed). Team 900050 is new to the test classes.

**A team fails the import, and isn't saved.** Add to `imports/ImportApiTest.java`:

```java
    @Test
    void aTeamWeCannotStoreFailsTheImportAndIsNotSaved() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(
                okJson(ProviderJson.team(900057, "z".repeat(106), null, "England", false))));

        String location = mvc.post().uri("/imports/teams/900057").exchange().getResponse().getHeader("Location");

        assertThat(mvc.get().uri(location))
                .bodyJson().isLenientlyEqualTo("""
                        {"status":"FAILED",
                         "error":"Team 900057 can't be stored: name is 106 characters long (at most 100 fit)"}
                        """);
        assertThat(teams.findByApiId(900057)).isEmpty();
    }

    @Autowired
    TeamRepository teams;
```

with the import:

```java
import com.example.importer.team.TeamRepository;
```

The same pattern as guide 05's `whenTheProviderFailsTheImportIsMarkedFailed`: start the import, follow the `Location`, read the status. The last line is the important one: `findByApiId` comes back **empty**, so a failed check left no team behind. No squad is stubbed, because none is asked for.

Run everything. In IntelliJ, right-click `src/test/java` → **Run 'All Tests'**, or in `importer/`:

```bash
./mvnw test
```

(`.\mvnw test` in PowerShell.)

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.012 s -- in com.example.importer.team.ValidationTest
...
[INFO] Tests run: 65, Failures: 0, Errors: 0, Skipped: 0
```

Five unit tests in a hundredth of a second. Guide 04's `aProfileWithoutANameIsSkipped` and `missingOrUnexpectedOptionalFieldsAreStoredAsNull` still pass unchanged: the rules they tested have moved, not changed.

### 7. Awkward fixtures for the fake provider

The tests build their awkward replies in Java. To watch the rules work in the running importer, the fake provider needs some too. Two new teams:

| Team | What's awkward about it |
| --- | --- |
| **Northgate United** (900003, code `NGU`) | Five players, three of them awkward: **9000022** has a **121-character name**, **9000023** O. Mensah plays **`"Wing-back"`**, and **9000024**'s profile says **`"name": null`**. 9000021 P. Quinn and 9000025 B. Hale are ordinary |
| **900004**, the reserves | The team's own name is **106 characters**, and its `code` is `null` |

(Guide 04's C. Lindqvist, 9000013, is still the player with a `null` nationality and birth date.)

**The teams.** Each team in the fake provider has its own mapping, as since guide 01. Create `fake-provider/mappings/teams-900003.json`:

```json
{
  "request": {
    "method": "GET",
    "urlPath": "/teams",
    "queryParameters": {
      "id": {
        "equalTo": "900003"
      }
    }
  },
  "response": {
    "status": 200,
    "headers": {
      "Content-Type": "application/json",
      "Cache-Control": "no-store, no-cache, must-revalidate",
      "x-ratelimit-limit": "10",
      "x-ratelimit-remaining": "9",
      "x-ratelimit-requests-limit": "100",
      "x-ratelimit-requests-remaining": "89"
    },
    "bodyFileName": "teams-900003.json"
  }
}
```

and `fake-provider/mappings/teams-900004.json`, the same with **`900004`** in both places. The squads and profiles need no mappings: guide 04's `squads.json` and `profiles.json` pick the file from the id.

The bodies go in `fake-provider/__files/`, each **on one line**, as before. `teams-900003.json`:

```json
{"get":"teams","parameters":{"id":"900003"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"team":{"id":900003,"name":"Northgate United","code":"NGU","country":"England","founded":1902,"national":false,"logo":"https://media.api-sports.io/football/teams/900003.png"},"venue":{"id":800003,"name":"Northgate Lane","address":"Lane End","city":"Northgate","capacity":4200,"surface":"grass","image":"https://media.api-sports.io/football/venues/800003.png"}}]}
```

`teams-900004.json`, the team whose name doesn't fit:

```json
{"get":"teams","parameters":{"id":"900004"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"team":{"id":900004,"name":"Northgate and District Amalgamated Workers' Recreational Association Football and Athletic Club (Reserves)","code":null,"country":"England","founded":1950,"national":false,"logo":"https://media.api-sports.io/football/teams/900004.png"},"venue":{"id":800004,"name":"Northgate Lane","address":"Lane End","city":"Northgate","capacity":4200,"surface":"grass","image":"https://media.api-sports.io/football/venues/800004.png"}}]}
```

`squads-900003.json`:

```json
{"get":"players/squads","parameters":{"team":"900003"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"team":{"id":900003,"name":"Northgate United","logo":"https://media.api-sports.io/football/teams/900003.png"},"players":[{"id":9000021,"name":"P. Quinn","age":25,"number":10,"position":"Goalkeeper","photo":"https://media.api-sports.io/football/players/9000021.png"},{"id":9000022,"name":"Sir Bartholomew Alexander Montgomery-Fitzwilliam Ashcroft-Delacroix de la Vallée-Hargreaves Junior the Third of Northgate","age":25,"number":10,"position":"Midfielder","photo":"https://media.api-sports.io/football/players/9000022.png"},{"id":9000023,"name":"O. Mensah","age":25,"number":10,"position":"Wing-back","photo":"https://media.api-sports.io/football/players/9000023.png"},{"id":9000024,"name":"Unknown","age":25,"number":10,"position":"Defender","photo":"https://media.api-sports.io/football/players/9000024.png"},{"id":9000025,"name":"B. Hale","age":25,"number":10,"position":"Attacker","photo":"https://media.api-sports.io/football/players/9000025.png"}]}]}
```

Notice 9000024: the **squad** calls him `"Unknown"`, but his **profile** has no name at all. Guide 04 takes only ids from the squad, so the squad's placeholder name is never used. The profile is the record of the player, and it says nothing.

The three awkward profiles. `profiles-9000022.json`, the long name:

```json
{"get":"players/profiles","parameters":{"player":"9000022"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"player":{"id":9000022,"name":"Sir Bartholomew Alexander Montgomery-Fitzwilliam Ashcroft-Delacroix de la Vallée-Hargreaves Junior the Third of Northgate","firstname":null,"lastname":null,"age":25,"birth":{"date":"2001-05-05","place":null,"country":"England"},"nationality":"England","height":"180","weight":"75","number":10,"position":"Midfielder","photo":"https://media.api-sports.io/football/players/9000022.png"}}]}
```

`profiles-9000023.json`, the unexpected position:

```json
{"get":"players/profiles","parameters":{"player":"9000023"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"player":{"id":9000023,"name":"O. Mensah","firstname":null,"lastname":null,"age":25,"birth":{"date":"2002-02-14","place":null,"country":"Ghana"},"nationality":"Ghana","height":"180","weight":"75","number":10,"position":"Wing-back","photo":"https://media.api-sports.io/football/players/9000023.png"}}]}
```

`profiles-9000024.json`, no name:

```json
{"get":"players/profiles","parameters":{"player":"9000024"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"player":{"id":9000024,"name":null,"firstname":null,"lastname":null,"age":25,"birth":{"date":"2000-08-08","place":null,"country":"England"},"nationality":"England","height":"180","weight":"75","number":10,"position":"Defender","photo":"https://media.api-sports.io/football/players/9000024.png"}}]}
```

<details>
<summary>The two ordinary profiles (copy each onto one line)</summary>

`profiles-9000021.json`:

```json
{"get":"players/profiles","parameters":{"player":"9000021"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"player":{"id":9000021,"name":"P. Quinn","firstname":null,"lastname":null,"age":25,"birth":{"date":"1999-10-02","place":null,"country":"England"},"nationality":"England","height":"180","weight":"75","number":10,"position":"Goalkeeper","photo":"https://media.api-sports.io/football/players/9000021.png"}}]}
```

`profiles-9000025.json`:

```json
{"get":"players/profiles","parameters":{"player":"9000025"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"player":{"id":9000025,"name":"B. Hale","firstname":null,"lastname":null,"age":25,"birth":{"date":"1998-12-12","place":null,"country":"Wales"},"nationality":"Wales","height":"180","weight":"75","number":10,"position":"Attacker","photo":"https://media.api-sports.io/football/players/9000025.png"}}]}
```

</details>

**Save the files as UTF-8.** The long name has an `é` in it (`Vallée`). IntelliJ saves as UTF-8 by default. The 121 is counted in characters, so the `é` counts as one.

**Restart the fake provider** (Ctrl+C, then `java -jar wiremock-standalone-3.13.2.jar --port 8089` from `fake-provider/`), so it loads the two new mappings. Check one:

```bash
curl "http://localhost:8089/players/profiles?player=9000024"
```

It prints `profiles-9000024.json`, with `"name":null`. (In PowerShell, `curl.exe`.)

### 8. Try it

Start from a fresh database, so your ids match these: stop the importer, delete `importer/data/`, and start it again. Have the fake provider running.

**Northgate United** (in PowerShell, `curl.exe`):

```bash
curl -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900003
curl -u admin:dev-only-password http://localhost:8080/imports/1
```

The `POST` answers `202` with `Location: /imports/1`, as in guide 05. A moment later:

```
{"id":1,"teamApiId":900003,"status":"COMPLETE","squadSize":5,"profilesFetched":5,"skipped":2,"skippedReasons":["Player 9000022: name is 121 characters long (at most 100 fit)","Player 9000024: profile has no name"],"quotaRemaining":89,"error":null,"createdAt":"2026-11-17T08:52:12.385224Z","finishedAt":"2026-11-17T08:52:15.265660Z"}
```

**`COMPLETE`**, with five profiles fetched and **two skipped**, each with its reason: the long name (rule 3, treated as rule 1) and the missing name (rule 1). The importer's log has one warning, for the third awkward player:

```
WARN … c.example.importer.team.TeamService    : Player 9000023 has an unexpected position 'Wing-back': stored without a position
```

Who made it in:

```bash
curl -u admin:dev-only-password http://localhost:8080/teams/1/players
```

```
[{"id":3,"apiId":9000025,"name":"B. Hale","nationality":"Wales","position":"ATTACKER","birthYear":1998},{"id":2,"apiId":9000023,"name":"O. Mensah","nationality":"Ghana","position":null,"birthYear":2002},{"id":1,"apiId":9000021,"name":"P. Quinn","nationality":"England","position":"GOALKEEPER","birthYear":1999}]
```

Three players. O. Mensah **is** there, with `"position":null`: an unknown position is no reason to lose a player. The two skipped players aren't there at all, and in particular there's no player called `Sir Bartholomew Alexander Montgomery-Fitzwilliam Ashcroft-Delacroix de la Val`.

**The team whose name doesn't fit:**

```bash
curl -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900004
curl -u admin:dev-only-password http://localhost:8080/imports/2
```

```
{"id":2,"teamApiId":900004,"status":"FAILED","squadSize":null,"profilesFetched":0,"skipped":0,"skippedReasons":[],"quotaRemaining":89,"error":"Team 900004 can't be stored: name is 106 characters long (at most 100 fit)","createdAt":"2026-11-17T08:52:16.029554Z","finishedAt":"2026-11-17T08:52:16.080527Z"}
```

**`FAILED`**, and the `error` says exactly why. `squadSize` is `null`: the squad was never asked for. The log (trimmed to the logger and the message) shows one request, and then the failure:

```
RateLimitInterceptor  GET /teams?id=900004 -> 200 (left this minute: 9, today: 89)
ImportRunner          Import 2 of team 900004 failed: com.example.importer.team.InvalidTeamDataException: Team 900004 can't be stored: name is 106 characters long (at most 100 fit)
```

(That last line is a `WARN`.) And nothing was saved:

```bash
curl -u admin:dev-only-password http://localhost:8080/teams
```

```
[{"id":1,"apiId":900003,"name":"Northgate United","hudName":"NGU","country":"England","national":false,"lastImportedAt":"2026-11-17T08:52:15.223325Z"}]
```

Only Northgate United. Team 900004 has no row.

**Import Northgate United again.** The squad hasn't changed, so you might expect 2 requests, as for guide 04's Riverside Rovers:

```bash
curl -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900003
curl -u admin:dev-only-password http://localhost:8080/imports/3
```

```
{"id":3,"teamApiId":900003,"status":"COMPLETE","squadSize":5,"profilesFetched":2,"skipped":2,"skippedReasons":["Player 9000022: name is 121 characters long (at most 100 fit)","Player 9000024: profile has no name"],"quotaRemaining":89,"error":null,"createdAt":"2026-11-17T16:26:04.408839Z","finishedAt":"2026-11-17T16:26:04.755938Z"}
```

```
RateLimitInterceptor  GET /teams?id=900003 -> 200 (left this minute: 9, today: 89)
RateLimitInterceptor  GET /players/squads?team=900003 -> 200 (left this minute: 9, today: 89)
RateLimitInterceptor  GET /players/profiles?player=9000022 -> 200 (left this minute: 9, today: 89)
RateLimitInterceptor  GET /players/profiles?player=9000024 -> 200 (left this minute: 9, today: 89)
```

**Four requests**: the two skipped players are asked for again, because the importer still doesn't have them. That's the price, and the point, of skipping rather than cutting short. If the provider had fixed either profile, this import would have stored the player. As long as it hasn't, each skipped player costs 1 request per import of the team. (This run is from a later session than the two above, hence the times.)

**In PowerShell**, the same commands with `curl.exe`, for example:

```powershell
curl.exe -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900003
curl.exe -u admin:dev-only-password http://localhost:8080/imports/1
```

> **Sidebar: what about `GET /provider/teams/{id}`?** Guide 02's lookup endpoint shows team 900004 with its 106-character name, without complaint. That's deliberate: it shows you what the provider sent, and **stores nothing**. The rules are about what we're willing to keep, so they sit in front of the database, not in front of every look at the provider's data.

---

## Your turn

### Exercise 1: which rule, and what happens?

No code. For each of these, say which rule applies (if any), and what the importer does: is the player or team stored, and with what; is anything skipped, and with which reason; does the import complete or fail?

1. A profile with `"position": "Forward"`.
2. A profile with `"birth": {"date": "31/12/1997", …}`.
3. A profile whose `nationality` is 61 characters long.
4. A profile whose `name` is exactly 100 characters long.
5. A profile whose `name` is `"   "`.
6. A team with `"code": "rIv"`.
7. A team with `"code": "RIVERSIDE-R"`.
8. The provider changes D. Brennan's profile (9000003) so that his name is 150 characters long, and you import Scotland again. You already have D. Brennan from guide 04.

<details>
<summary>Solution</summary>

1. **Rule 3, position.** The player is **stored**, with `position` `null`, and the log says `Player … has an unexpected position 'Forward': stored without a position`. Nothing is skipped. (`Position.fromProvider` knows only the four words API-Football uses. If the provider really did start writing `"Forward"`, you'd add one `case` to it.)
2. **Rule 2.** The player is **stored**, with `birthYear` `null`: guide 04's `birthYear` can't parse the date, and doesn't guess. Nothing is skipped, and nothing is logged.
3. **Rule 3, too long, treated as rule 1.** The player is **skipped**: `Player …: nationality is 61 characters long (at most 60 fit)`. The import still completes.
4. **No rule is broken.** 100 characters fit a `varchar(100)`, so the player is stored with the whole name. That's `textThatExactlyFitsIsFine`.
5. **Rule 1.** Skipped, with `profile has no name`: a name that's only spaces is no name.
6. **No rule is broken.** `"rIv"` fits in 10 characters, so the team is stored with `hudName` `"rIv"`, exactly as sent. Changing it would be inventing (rule 2), and guide 03 decided the provider's code is used as it is.
7. **Rule 3, too long.** But it's a **team**, so the import **fails**: `Team … can't be stored: code is 11 characters long (at most 10 fit)`. Nothing is saved, and the squad is never asked for. Exercise 2 proves it.
8. **Nothing happens to him.** The importer already has player 9000003, so it **links** him without fetching his profile (guide 04). No profile, nothing to validate. The rules only ever see profiles the importer actually fetches, which is only players it doesn't have yet.

(Cases 1, 2, 3, 6 and 7 were checked by running them against the importer.)

</details>

### Exercise 2: a code that doesn't fit

`ImportApiTest.aTeamWeCannotStoreFailsTheImportAndIsNotSaved` proves a team **name** that's too long fails the import. Add a test, `aTeamCodeThatDoesNotFitFailsTheImport`, that does the same for a **code** of 11 characters, for team 900058. Also check that the squad was never asked for, using what the import status shows.

<details>
<summary>Solution</summary>

In `imports/ImportApiTest.java`:

```java
    @Test
    void aTeamCodeThatDoesNotFitFailsTheImport() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(
                okJson(ProviderJson.team(900058, "Riverside Rovers", "RIVERSIDE-R", "England", false))));

        String location = mvc.post().uri("/imports/teams/900058").exchange().getResponse().getHeader("Location");

        assertThat(mvc.get().uri(location))
                .bodyJson().isLenientlyEqualTo("""
                        {"status":"FAILED",
                         "error":"Team 900058 can't be stored: code is 11 characters long (at most 10 fit)",
                         "squadSize":null}
                        """);
        assertThat(teams.findByApiId(900058)).isEmpty();
    }
```

- **`ProviderJson.team(...)`'s third argument is the code**, so `"RIVERSIDE-R"` goes straight into the reply.
- **`"squadSize":null`** is the proof the squad was never fetched: guide 05's progress only fills it in once the squad arrives. (`isLenientlyEqualTo` checks the fields you list, and a `null` you list must really be `null`.)
- **No squad is stubbed**, because none should be asked for.
- **Team 900058** isn't used by any other test class.

It needs no new imports. With it, `./mvnw test` runs **66**.

</details>

### Exercise 3: a profile for someone else

Here's a stranger thing a provider could do: you ask for player 9000592's profile, and the reply is a perfectly good profile **for player 9000591**. A bug at the provider, or in a cache somewhere between you and it, could do that.

1. Before changing anything, work out what the importer does now, if 9000591 and 9000592 are both in a squad, and the reply for 9000592 is 9000591's profile. Then check, with this test in `SquadImportTest`:

   ```java
       @Test
       void aProfileForSomeoneElseIsSkipped() {
           stubTeam(900059);
           stubSquad(900059, 9000591, 9000592);
           stubProfiles(9000591);
           stubProfile(9000592, ProviderJson.profile(9000591, "Player 9000591"));

           ImportResult result = importer.importTeam(900059);

           assertThat(result.skippedReasons())
                   .containsExactly("Player 9000592: the profile is for player 9000591");
           assertThat(apiIdsOfPlayersIn(900059)).containsExactly(9000591L);
       }
   ```

2. Make it pass. The check belongs with the other rules, in `Validation`, which doesn't currently know which player was asked for. Add a test for the new check to `ValidationTest`, too.

<details>
<summary>Solution</summary>

**What happens now.** The test fails with:

```
org.springframework.dao.DataIntegrityViolationException:
could not execute statement [Unique index or primary key violation: "PUBLIC.CONSTRAINT_8C INDEX PUBLIC.CONSTRAINT_INDEX_8 ON PUBLIC.PLAYER(API_ID NULLS FIRST) VALUES ( /* 7 */ CAST(9000591 AS BIGINT) )"; SQL statement:
```

The profile passes every rule (it has an id and a name, and they fit), so `createAndLinkPlayer` tries to create player **9000591**, from the reply's `id`. But 9000591 was created a moment earlier, from its own profile, and `player.api_id` is `unique`. The database refuses, and the exception ends the whole import: in the running importer, `FAILED`. If 9000591 hadn't been stored yet, it would have been worse: the import would have "worked", creating 9000591 and linking him **in 9000592's place**, with nothing to say anything was wrong. (The constraint name and the row number, `/* 7 */`, in your message may differ: they depend on which tests ran before.)

**The fix.** In `team/Validation.java`, add a second `problemWithProfile`, which knows which player was asked for, above the existing one:

```java
    /** As below, and the profile must be the one we asked for. */
    static String problemWithProfile(long requestedId, ApiPlayer profile) {
        String problem = problemWithProfile(profile);
        if (problem == null && profile.id() != requestedId) {
            return "the profile is for player " + profile.id();
        }
        return problem;
    }
```

and in `team/TeamImporter.java`, use it:

```java
            String problem = profile.isEmpty() ? "the provider has no profile"
                    : Validation.problemWithProfile(playerApiId, profile.get());
```

- **Two methods with the same name** and different parameters is an **overload**: Java picks the one whose parameters match the call. The new one runs the existing rules first, so a profile with no id still says `profile has no id`, and only checks the id once it knows there is one.
- **`profile.id() != requestedId`** compares a `Long` with a `long`. Java unboxes the `Long` to a `long`, so it compares the numbers. That's only safe because `problem == null` has already proved the id isn't `null`; unboxing a `null` throws a `NullPointerException`. (Comparing two `Long`s with `!=` would compare the objects, not the numbers, which is a classic bug. Here one side is a `long`, so it's fine.)
- **It's rule 1**: a profile that isn't for this player is as unusable as one with no id. It's skipped, with a reason, and costs a request on each import until the provider fixes it.

In `team/ValidationTest.java`:

```java
    @Test
    void aProfileForADifferentPlayerIsRejected() {
        assertThat(Validation.problemWithProfile(9000002L, profile(9000001L, "C. Lindqvist", null)))
                .isEqualTo("the profile is for player 9000001");
        assertThat(Validation.problemWithProfile(9000001L, profile(9000001L, "C. Lindqvist", null))).isNull();
        assertThat(Validation.problemWithProfile(9000001L, profile(null, "C. Lindqvist", null)))
                .isEqualTo("profile has no id");
    }
```

The three lines are: the wrong player, the right one, and a missing id, which must still give rule 1's message rather than a `NullPointerException`.

The existing tests keep calling the one-argument `problemWithProfile`, and still pass. With exercises 2 and 3, `./mvnw test` runs **68**, all passing.

</details>

---

## Checkpoint

In `importer/`:

```bash
./mvnw test
```

```
[INFO] Tests run: 65, Failures: 0, Errors: 0, Skipped: 0
```

That's guide 06's 58, plus `ValidationTest`'s 5, one more in `SquadImportTest` and one more in `ImportApiTest`. With exercises 2 and 3, **68**.

With a fresh database and the fake provider restarted, importing **900003** ends `COMPLETE` with `"profilesFetched":5,"skipped":2` and the two reasons, `GET /teams/1/players` lists B. Hale, O. Mensah (`"position":null`) and P. Quinn, and the log has the `Wing-back` warning. Importing **900004** ends `FAILED` with `Team 900004 can't be stored: name is 106 characters long (at most 100 fit)`, and `GET /teams` still lists only Northgate United.

Your files (new ones marked **new**, changed ones **changed**; unchanged folders left out):

```
importer/src/
├── main/java/com/example/importer/team/
│   ├── InvalidTeamDataException.java      new
│   ├── Player.java                        changed
│   ├── Team.java                          changed
│   ├── TeamImporter.java                  changed
│   └── Validation.java                    new
└── test/java/com/example/importer/
    ├── imports/
    │   └── ImportApiTest.java             changed
    └── team/
        ├── SquadImportTest.java           changed
        └── ValidationTest.java            new

fake-provider/
├── mappings/
│   ├── teams-900003.json                  new
│   └── teams-900004.json                  new
└── __files/
    ├── profiles-9000021.json              new
    ├── profiles-9000022.json              new
    ├── profiles-9000023.json              new
    ├── profiles-9000024.json              new
    ├── profiles-9000025.json              new
    ├── squads-900003.json                 new
    ├── teams-900003.json                  new
    └── teams-900004.json                  new
```

No new dependencies, no change to `application.properties`, and no new migration: the column sizes were already in `V1` and `V2`. `TeamService` and `Position` don't change either; their part of rule 3 has been there since guide 04.

---

## Common errors

**`DataIntegrityViolationException: could not execute statement [Value too long for column "NAME CHARACTER VARYING(100)": …]`**
The length check isn't happening. This was made by changing `tooLong` to always return `false`, and running the new `SquadImportTest` test:

```
org.springframework.dao.DataIntegrityViolationException:
could not execute statement [Value too long for column "NAME CHARACTER VARYING(100)": "U&'Sir Bartholomew Alexander Montgomery-Fitzwilliam Ashcroft-Delacroix de la Val... (121)"; SQL statement: …
Caused by: org.hibernate.exception.DataException: could not execute statement [Value too long for column "NAME CHARACTER VARYING(100)": …
```

The database is the last line of defence, and it held: nothing too long was stored. But it's the wrong place to find out. The exception ends the **whole import**, so instead of one skipped player, the import is `FAILED`, and the squad members after him aren't processed at all. Check that `TeamImporter` calls `Validation.problemWithProfile`, and that the constants match the migration. (`U&'…'` is H2's way of writing a string that contains non-ASCII characters, such as the `é`, and `... (121)` is H2 shortening the value for its message. Shortening it for a message is fine; shortening it **to store it** is what the rules forbid.)

**`IllegalArgumentException: No enum constant com.example.importer.team.Position.WING-BACK`**
Something maps the provider's position with `valueOf` instead of `Position.fromProvider`, for example `Position.valueOf(profile.position().toUpperCase())`. That's tempting, because it works for all four positions you've seen. Tried with O. Mensah's `"Wing-back"`:

```
java.lang.IllegalArgumentException: No enum constant com.example.importer.team.Position.WING-BACK
```

In an import, that one unusual position would end the import as `FAILED`. Rule 3 says an unknown position becomes `null`, with a warning. Use `Position.fromProvider`, which never throws.

**Importing 900003 ends `FAILED` with `API-Football has no team with id 900003`**
The fake provider is still running with the mappings it loaded before you added `teams-900003.json`:

```
{"id":1,"teamApiId":900003,"status":"FAILED","squadSize":null,"profilesFetched":0,"skipped":0,"skippedReasons":[],"quotaRemaining":89,"error":"API-Football has no team with id 900003","createdAt":"2026-11-17T16:04:08.205738Z","finishedAt":"2026-11-17T16:04:10.534862Z"}
```

Without its own mapping, the request falls through to guide 02's catch-all, `teams-unknown.json`, which answers like the real API does for an id it doesn't know: `results: 0`. Restart the fake provider (or `curl -X POST http://localhost:8089/__admin/mappings/reset`, which reloads `mappings/` from disk), and import again.
---

## Recap

- A provider's data is **untrusted input**, like anything a user types. OWASP calls trusting it **API10:2023 Unsafe Consumption of APIs**: validate what an integrated API sends before you use it, and talk to it over TLS.
- **Three rules**, in one class (`team/Validation`), with the rules as its comment:
  1. **Required** (the api id and the name of a team or a profile): missing or blank → the record is **skipped**, and the reason **recorded**.
  2. **Optional** (nationality, birth year, country, code): missing → **`null`**. **No invented defaults**, and no tidying of what was sent.
  3. **Fit**: an unknown position → `null`, with a warning; text longer than its column → treated as rule 1, **skipped, never cut short**.
- **Never cut short**: a shortened name is a wrong name that looks right, and since profiles are never refreshed, it would stay wrong for good. A skipped player is asked for again on every import (1 request each), and arrives once the provider fixes it.
- **A bad player is skipped**, and the import completes. **A bad team fails the import** (`InvalidTeamDataException`, a `ProviderException`): it's checked before it's saved, so one request is spent and **nothing is stored**.
- **Column sizes live in the migrations**, and as constants on the entities that `@Column(length = …)` and `Validation` both use. **`ddl-auto=validate` doesn't check lengths**, so keeping the constants and the SQL in step is a job for you.
- What validation here **doesn't** have to do: SQL injection is handled by bound parameters, and HTML escaping belongs to whatever shows the data on a page.
- The rules are plain Java, so they get **plain unit tests**, with tests on both sides of every boundary (`"x".repeat(100)` fits; `"x".repeat(101)` doesn't).

### Interview angle

<details>
<summary>"Your service imports data from a third-party API. How much do you trust it?"</summary>

As much as user input, which means not at all until it's checked. It's another organisation's system: it has bugs, it changes its format, and it could be compromised. OWASP lists this as API10:2023, Unsafe Consumption of APIs. In practice: use TLS and never disable certificate checks, map the reply into your own model in one place, and validate before storing. Check that the fields you need are present, leave optional ones empty rather than inventing values, map enumerations defensively (unknown values become "unknown", not an exception), and check sizes against what your database can hold. Then decide per record whether a problem skips that record or fails the whole batch, and record why either way.

</details>

<details>
<summary>"A value from an external source is longer than your column. Why not just truncate it?"</summary>

Because a truncated value is wrong data that looks right. Nothing marks it as shortened, two different values can truncate to the same thing, and if you never re-fetch the record, it stays wrong for ever. Skipping the record and recording why is honest: the gap is visible, and the next sync can pick the record up once the source sends something that fits. If the value genuinely needs to be longer, the answer is a wider column, through a migration, not quietly losing the end of it. Truncation can be acceptable for things that are explicitly lossy, such as a preview or a log message, but not for a stored fact like a name.

</details>

<details>
<summary>"In a batch import, when should one bad record fail the whole batch?"</summary>

When the rest of the batch depends on it. Here, a bad player is one of many, and the others are still useful, so the player is skipped and the import completes, with the reason recorded. The team is different: everything else in the import hangs off it, so if it can't be stored, there's nothing to import, and the import fails before spending more requests. The general rule is to fail at the smallest unit that still makes sense on its own, and always to say what was skipped and why, so "complete" doesn't hide missing data.

</details>

<details>
<summary>"Does Hibernate's `ddl-auto=validate` protect you from a mismatch in column sizes?"</summary>

No. It checks that each table and column the entities need exists, with a compatible type, but with H2 and Hibernate 7 it doesn't compare `varchar` lengths with `@Column(length = …)`. A mismatch shows up only when a value is too long for the real column, as a database error at insert time. So keep the sizes in one place in your code (constants, used by both the mapping and the validation), point to the migration that defines them, and change both together. A test that inserts a value of exactly the maximum length is a cheap extra check.

</details>

<details>
<summary>"Where would you use Bean Validation, and where plain code?"</summary>

Bean Validation (`@NotBlank`, `@Size`, `@Valid`) is a good fit for requests coming **into** your API: the framework runs it before your controller, and turns failures into a `400` that tells the caller which field to fix. For data you **fetch**, there's nobody to send a `400` to, and the outcome is a decision (skip this record, fail this batch, store `null`) rather than a response. You can still call a `Validator` yourself, but for a handful of rules, a small class with the rules written out is often clearer, and its messages can go straight into whatever records the outcome.

</details>

### Further reading

- [OWASP API Security Top 10 2023: API10:2023 Unsafe Consumption of APIs](https://owasp.org/API-Security/editions/2023/en/0xaa-unsafe-consumption-of-apis/)
- [OWASP Input Validation Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Input_Validation_Cheat_Sheet.html), on syntactic and semantic validation, and allow-lists
- [OWASP SQL Injection Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/SQL_Injection_Prevention_Cheat_Sheet.html), on why bound parameters are the defence
- [H2 data types: `CHARACTER VARYING`](https://h2database.com/html/datatypes.html#character_varying_type), and what its length means
- [`String.length()`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/String.html#length()), which counts UTF-16 code units, not characters
- [Jakarta Bean Validation](https://beanvalidation.org/), for the annotation-based alternative

**Simplification to notice:** the rules check that text **fits**, not that it **makes sense**. A name of `"???"` or a nationality of `"England "` with a trailing space passes. Checking meaning (real country names, sensible characters) would mean deciding what the provider *should* send, and every such rule is a chance to reject good data. For a game's football data, fitting is enough; for money or medical records, it wouldn't be.

---

**Next:** [08: When the provider fails](08-when-the-provider-fails.md). So far the provider has always answered, even when the answer was wrong. Next: a provider that's slow or doesn't answer at all, which failures are worth retrying, and which never are.
