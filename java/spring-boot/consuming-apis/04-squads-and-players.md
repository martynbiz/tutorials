# 04: Squads and players

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](README.md) for version notes.

## What you'll learn

- Why a team's players come from **two** endpoints: the **squad** says *who*, each **profile** says *what*
- How to map a **many-to-many** relationship between players and teams, with a join table, owned by one side
- Why the squad you've just received is the truth, and why players who've left are **unlinked, not deleted**
- How to spend requests only on what you **don't already have**, and **link as you go** so nothing is lost when an import stops halfway
- Why an empty profile counts as **done**, and why `lastImportedAt` is set only when **every** member is done
- How a failed import becomes an import that **finishes another day**, at the cost of only the requests it still needs
- The trade-off of **never refreshing** a player's profile: what it saves, what goes stale, and what heals by itself
- How to **count the requests** the fake provider received, and prove in a test that a profile was fetched exactly once

By the end, `POST /imports/teams/900001` imports Riverside Rovers **and its six players**, `GET /teams/1/players` lists them, and importing Scotland afterwards fetches only the two profiles the clubs didn't already give you.

---

## Prerequisites for this guide

You need the `importer` and `fake-provider` projects as they were at the end of [03: Storing teams](03-storing-teams.md), with all **23** tests passing.

From the [todo app](../todo-app/README.md) series, you should know:

- **JPA relationships and lazy loading**, and what a `LazyInitializationException` is ([todo app 03](../todo-app/03-data-jpa-and-relationships.md)). This guide uses a relationship the todo app didn't: `@ManyToMany`. It's explained in step 4.
- **`open-in-view=false`** and loading what a response needs inside the service's transaction ([todo app 05](../todo-app/05-crud-todo-lists.md)).

And from guide 03: **HTTP calls stay outside transactions**, and that takes **two beans**, `TeamImporter` and `TeamService`. This guide leans on both rules much harder.

Nothing new goes in `pom.xml` or `application.properties`.

---

## Concepts

### Who's in the squad, and who they are

Guide 03 imported a team. A team is no use to a football game without its players, and API-Football hands them over in two steps:

| Request | What it gives you | How many |
| --- | --- | --- |
| `GET /players/squads?team=900001` | **Who** is in the squad today: a list of players, each with an `id`, a `name`, an `age`, a shirt `number` and a `position` | **1** per team |
| `GET /players/profiles?player=9000001` | **Who that player is**: name, first and last names, date and place of birth, `nationality`, height, weight, position | **1 per player** |

The squad's list already has names and positions in it. Why not create players from that, and save a request per player?

- **It doesn't have everything we need.** No nationality, no date of birth. We'd need the profile anyway.
- **It's a summary, and summaries drift.** The squad says what the team sheet says (that week's number, the position they're listed in). The profile is the record of the player. When the two disagree, the profile is the one to believe.

So the importer takes **only the ids** from the squad, and creates a player **only from their profile**. The squad answers "who?"; the profile answers "what?".

### One player, many teams

A player plays for a club *and*, if they're good enough, a national team. In the fake provider, D. Brennan is in Riverside Rovers' squad **and** Scotland's. He's one person, so he must be **one row** in `player`, not two copies that could drift apart.

So a team has many players, and a player has many teams: **many-to-many**. A relational database can't put a list in a column, so the relationship gets a table of its own, a **join table**, with one row per link:

```
team                      team_player               player
id  name                  team_id  player_id        id  name
1   Riverside Rovers ───▶ 1        3         ◀───── 3   D. Brennan
3   Scotland         ───▶ 3        3
```

Unlinking a player from a team deletes one row from `team_player`. The player, and their links to other teams, stay.

In JPA, one side of a many-to-many **owns** it: its entity has the `@JoinTable`, and changes to *its* collection are what Hibernate writes to the join table. Here that's `Team`, because every change happens from the team's point of view: an import of a team decides who's in **that** team. `Player` doesn't need to know its teams at all, so it has no collection back. One side, one direction, nothing to keep in step.

### The squad is the truth

Squads change. Players are sold, released, retire, or are left out of a national squad. Each time you import a team, the squad you've just received is the most recent truth, so:

- **Anyone linked to the team who isn't in it has left.** Unlink them, straight away, before anything else.
- **Unlink, don't delete.** The player who left Riverside Rovers may still be in Scotland's squad, and may sign for Harbour Athletic next month. Keeping the row means that if they turn up in any squad again, the importer links them **without spending a request** on their profile.

Our copy of a team's squad is therefore only as fresh as **that team's last import**. A player who moved from one club to another stays linked to the old one until you re-import it. That's the price of a copy, and it's why each team records when it was last imported.

### Spend requests only on what you don't have

API-Football's free plan allows **100 requests a day**. A first import of a club with 25 players costs:

```
1 (team) + 1 (squad) + 25 (profiles) = 27 requests
```

Three of those and you've used most of a day. So the importer never asks for something it already has. For each member of the squad:

- **We already have the player** (looked up by the provider's id, as guide 03 did for teams): **link them** to this team. No request.
- **We don't**: fetch the profile (**1 request**), create the player, and link them.

Re-importing that same club, with the same squad, costs **2** requests: the team and the squad. Scotland's squad, imported after the clubs, is mostly players the importer already has. In this guide's fake provider, three of its five players come from the clubs, so it costs 2 + **2**.

### Link as you go, and why an import can finish another day

Here's the whole import of one team, the algorithm the rest of this guide builds:

1. **Team**: `GET /teams?id=…`, then create or update it (guide 03).
2. **Squad**: `GET /players/squads?team=…`, keep the ids, and **unlink** anyone who's no longer in it.
3. **Each member**: already have them? Link. Don't? `GET /players/profiles?player=…`, then create **and link** them, together.
4. **Done**: every member has been dealt with, so set the team's **`lastImportedAt`** to now.

Look at step 3 again: a new player is created and linked **in the same moment**, not "fetch every profile, then save them all at the end". That's **link as you go**, and it matters when something goes wrong. Imports *will* stop halfway. In this guide, because the provider fails; in guide 06, because the day's 100 requests run out. Whatever the reason:

- **Everything done so far is kept.** The players already created are in the database and linked to the team. Their requests aren't wasted.
- **`lastImportedAt` stays as it was** (still empty, for a first import). Step 4 never ran, so nobody can mistake a half-imported team for a complete one.
- **The next attempt fetches only what's missing.** Step 3 asks "do we already have them?" for each member, so the players created last time cost nothing.

That's what the [README](README.md) calls **imports that finish another day**. An import that ran out of requests at player 18 of 25 today carries on tomorrow at the cost of 2 + 7 requests, not 27. Nothing special was written to "resume": an import that only fetches what's missing *is* a resumable import. (Guide 06 stops for the day on purpose; guide 09 carries on by itself after midnight.)

### An empty profile counts as done

Sometimes the squad lists a player whose profile comes back empty: `"results": 0`, `"response": []`. In the fake provider, that's Harbour Athletic's M. Duffy. We can't create a player from nothing, so:

- **Don't create a player.** The team is simply missing that member for now.
- **Count them as done.** The import carries on, and still completes: a team with 4 of its 5 players is the provider's data as it stands, not an unfinished import.
- **Record why**, as a skipped reason: `Player 9000014: the provider has no profile`.

Because no row was created, the **next** import of the team asks for that profile again, for **1** request. If the provider has filled it in by then, the player arrives. This case **heals by itself**.

A profile with no `id` or no `name` is treated the same way: we can't store a player without either (guide 07 makes this one of its three rules).

### The trade-off: a profile is fetched once, and never refreshed

The rule from the last few sections, stated plainly: **the importer fetches a player's profile only when it doesn't have that player. It never fetches it again**, however old it gets. There's no "refresh" and no "force" option. The [README](README.md#deliberately-out-of-scope) promised this guide would explain why, and what it costs.

**What it saves.** These are the numbers for one club of 25 players, on the free plan's 100 requests a day:

| | First import | Each re-import | Re-imports a day |
| --- | --- | --- | --- |
| **Fetch only what's missing** (this guide) | 27 | **2** (plus 1 per new player) | about 50 |
| **Refresh every profile, every time** | 27 | **27** | about 3 |

Refreshing would make every re-import cost as much as the first, and the day's quota would be gone after three or four teams. You'll see the real difference in step 13: the fake provider counts **13** profile requests after importing three teams, and **still 13** after importing Riverside Rovers again.

**What goes stale.** A player's stored name, nationality, position and birth year never change after the row is created. Things this design would miss (examples from football in general, not from API-Football's data):

- A **nationality switch**: a player who changes international allegiance.
- A **position change**: a winger retrained as a full-back.
- A **name correction**: a fixed spelling, or a different transliteration.

Birth year can't change, and nationality rarely does. Position is the one most likely to go stale.

**What doesn't go stale.** **Who plays for whom.** The squad is fetched on every import, and leavers are unlinked, so a team's player list is as fresh as its last import. Only the details *of* each player can age.

**What heals by itself, and what doesn't.**

- **An empty profile heals.** No player was created, so every import of the team asks again (1 request), until the provider has it.
- **A stale player doesn't.** The only way to get a new profile in this design is to delete the player (and their links in `team_player`, which the foreign keys insist on first). The next import of any team they're in then fetches the profile again.

**The next step**, if staleness ever mattered, would be a job that refreshes profiles older than some number of days, spending spare requests at a quiet time; or an admin "force" option for one player. Both are deliberately left out of this series.

**Why it's acceptable here.** The data feeds a game, not a live scores service. A position that's a season out of date is harmless. A quota that runs out at ten in the morning stops **every** import until midnight UTC.

### Many short transactions, none of them waiting on the network

Guide 03's rule, **HTTP calls stay outside transactions**, was easy to follow with one request. Now an import is dozens of requests, interleaved with database writes:

```
TeamImporter.importTeam(apiId)                         no transaction, ever
 ├── provider.fetchTeam(apiId)              ── HTTP ──▶
 ├── teamService.saveFromProvider(…)                   ◀ short transaction
 ├── provider.fetchSquadIds(apiId)          ── HTTP ──▶
 ├── teamService.unlinkPlayersNotIn(…)                 ◀ short transaction
 ├── for each member:
 │    ├── teamService.playerExists(…)                  ◀ short (read-only)
 │    ├── teamService.linkExistingPlayer(…)            ◀ short transaction
 │    │      or
 │    ├── provider.fetchProfile(…)          ── HTTP ──▶
 │    └── teamService.createAndLinkPlayer(…)           ◀ short transaction
 └── teamService.markImported(…)                       ◀ short transaction
```

`TeamImporter` still has **no** `@Transactional`. Every database write is a call to `TeamService`, another bean, so each goes through the proxy and gets **its own** transaction, which commits before the next HTTP call starts. That's what makes "link as you go" true: once `createAndLinkPlayer` returns, that player and that link are **committed**. A failure on the next profile can't roll them back, because their transaction is already over.

One big transaction around the whole import would get **both** things wrong: it would hold a database connection for minutes while the provider answered, and a failure on player 18 would roll back players 1 to 17, throwing away 17 requests you'd already paid for.

---

## Steps

The importer won't compile in the middle of this guide: step 10 changes what `TeamImporter.importTeam` returns, and the controller and tests catch up in steps 11 and 15. Work through to step 11 before running anything.

### 1. The tables: migration V2

Guide 03 said never to edit a migration that has run. The players get a **new** one. Right-click `src/main/resources/db/migration` → **New → File**, and name it `V2__create_player.sql`:

```sql
create table player (
    id          bigint generated by default as identity primary key,
    api_id      bigint       not null unique,
    name        varchar(100) not null,
    nationality varchar(60),
    position    varchar(20),
    birth_year  integer
);

-- which players are in which teams (club and national): many-to-many
create table team_player (
    team_id   bigint not null references team (id),
    player_id bigint not null references player (id),
    primary key (team_id, player_id)
);
```

- **`player`** follows `team`'s pattern: **our** `id`, **their** `api_id`, unique.
- **Only `name` is required.** `nationality`, `position` and `birth_year` can be missing from a profile, and missing stays `null`. No invented defaults.
- **`position` is text**, `varchar(20)`, holding `GOALKEEPER`, `DEFENDER`, `MIDFIELDER` or `ATTACKER` (step 2 explains the capitals).
- **`team_player`** is the join table from the Concepts. Two foreign keys, so a link can only point at a team and a player that exist.
- **`primary key (team_id, player_id)`**: a player can be linked to a team **once**. Linking them again can't make a duplicate row.

`team.last_imported_at` already exists: guide 03 created it so that this guide wouldn't need a migration just for it.

### 2. Positions: the `Position` enum

The provider sends positions as text: `"Goalkeeper"`, `"Defender"`, `"Midfielder"`, `"Attacker"`. Our model has a fixed list, so it's an enum. Create `team/Position.java`:

```java
package com.example.importer.team;

public enum Position {
    GOALKEEPER, DEFENDER, MIDFIELDER, ATTACKER;

    /** The provider's position text as our enum, or null for anything we don't recognise. */
    public static Position fromProvider(String text) {
        if (text == null) {
            return null;
        }
        return switch (text) {
            case "Goalkeeper" -> GOALKEEPER;
            case "Defender" -> DEFENDER;
            case "Midfielder" -> MIDFIELDER;
            case "Attacker" -> ATTACKER;
            default -> null;
        };
    }
}
```

- **One method maps their words to ours**, the same idea as guide 03's single mapping method. If the provider ever writes `"Forward"`, one line changes.
- **Anything unexpected becomes `null`**, not an exception: an odd position isn't a reason to lose a player. Step 9 logs a warning when it happens. [Guide 07](07-validating-what-you-receive.md) comes back to this as one of its rules for untrusted data.
- **The `switch` checks `null` first**, because switching on a `null` string throws a `NullPointerException`.

It's pure Java, so it gets a plain unit test. Create `src/test/java/com/example/importer/team/PositionTest.java`:

```java
package com.example.importer.team;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PositionTest {

    @Test
    void mapsTheFourPositionsTheProviderUses() {
        assertThat(Position.fromProvider("Goalkeeper")).isEqualTo(Position.GOALKEEPER);
        assertThat(Position.fromProvider("Defender")).isEqualTo(Position.DEFENDER);
        assertThat(Position.fromProvider("Midfielder")).isEqualTo(Position.MIDFIELDER);
        assertThat(Position.fromProvider("Attacker")).isEqualTo(Position.ATTACKER);
    }

    @Test
    void anythingElseIsNull() {
        assertThat(Position.fromProvider("Wing-back")).isNull();
        assertThat(Position.fromProvider(null)).isNull();
    }
}
```

### 3. The `Player` entity

Create `team/Player.java`:

```java
package com.example.importer.team;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** A player in OUR database. Created once, from their profile, and never refreshed. */
@Entity
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private long apiId;

    @Column(nullable = false)
    private String name;

    private String nationality;

    @Enumerated(EnumType.STRING)
    private Position position;

    private Integer birthYear;

    protected Player() {
        // for JPA
    }

    public Player(long apiId, String name, String nationality, Position position, Integer birthYear) {
        this.apiId = apiId;
        this.name = name;
        this.nationality = nationality;
        this.position = position;
        this.birthYear = birthYear;
    }

    public Long getId() {
        return id;
    }

    public long getApiId() {
        return apiId;
    }

    public String getName() {
        return name;
    }

    public String getNationality() {
        return nationality;
    }

    public Position getPosition() {
        return position;
    }

    public Integer getBirthYear() {
        return birthYear;
    }
}
```

The shape is `Team`'s from guide 03: our `Long id`, their `long apiId`, a protected constructor for JPA, getters and no setters. What's different:

- **`@Enumerated(EnumType.STRING)`** stores the enum constant's **name**, `GOALKEEPER`, in the `position` column. Without it, JPA stores the constant's **position in the list** (`0` for `GOALKEEPER`), and the day someone adds `WING_BACK` in the middle, every row after it silently changes meaning. Always use `STRING`.
- **`Integer birthYear`**, not `int`: it can be missing, and an `int` can't be `null`.
- **Everything is set in the constructor, and there's no `update` method.** That's the "never refreshed" rule from the Concepts, written into the class: a `Player` is created once, from a profile, and nothing in the importer can change it afterwards. The class comment says so for the next developer.
- **No `teams` field.** `Player` doesn't know which teams it's in. The relationship is owned by `Team` (next step), and nothing needs to go the other way.

> **Done the REST API series?** `@Enumerated(EnumType.STRING)` is its guide 01's `Category`, for the same reason. Skim to the next step.

### 4. A team's players: `@ManyToMany`

Open `team/Team.java`. Add a field after `lastImportedAt`:

```java
    /** Club and national teams share players, so this is many-to-many (table team_player). */
    @ManyToMany
    @JoinTable(name = "team_player",
            joinColumns = @JoinColumn(name = "team_id"),
            inverseJoinColumns = @JoinColumn(name = "player_id"))
    private Set<Player> players = new HashSet<>();
```

- **`@ManyToMany`**: a team has many players, and a player can be in many teams.
- **`@JoinTable`** names the join table from step 1 and its two columns. **`joinColumns`** is the column that points back at **this** entity (`team_id` → `team`); **`inverseJoinColumns`** is the one that points at the **other** entity (`player_id` → `player`). The entity with the `@JoinTable` is the **owning side**: when you add a player to this set, or remove one, Hibernate inserts or deletes a `team_player` row.
- **Lazy by default.** A many-to-many collection isn't loaded with the team. It's loaded from the database the first time you touch it, which only works **inside a transaction** (Common errors shows what happens otherwise).
- **A `Set`**, because a player is in a team once. It matches the join table's primary key.

Then add four methods, after `update(...)`:

```java
    public void addPlayer(Player player) {
        players.add(player);
    }

    /** Unlinks everyone whose api id is not in the squad we just received. Returns how many left. */
    public int removePlayersNotIn(Collection<Long> squadApiIds) {
        int before = players.size();
        players.removeIf(player -> !squadApiIds.contains(player.getApiId()));
        return before - players.size();
    }

    public void markImported(Instant when) {
        this.lastImportedAt = when;
    }
```

and, after `getLastImportedAt()`:

```java
    public Set<Player> getPlayers() {
        return Collections.unmodifiableSet(players);
    }
```

with these imports:

```java
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
```

- **`addPlayer`** links a player. Adding one who's already there does nothing, because it's a `Set`.
- **`removePlayersNotIn(squadApiIds)`** is "the squad is the truth" in one line: **`removeIf`** removes every player whose `apiId` isn't in the squad. Removing them from the set deletes their `team_player` rows; the players themselves stay. It returns how many left, which is handy for logging and tests.
- **`markImported(when)`** sets `lastImportedAt`. Only step 4 of the algorithm calls it.
- **`getPlayers()`** hands out a **read-only view** of the set. Code outside `Team` can read the players, but can only change them through `addPlayer` and `removePlayersNotIn`, the same "everything goes through a method" rule as guide 03's `update`.

> **Sidebar: why no `equals` and `hashCode`?** Putting entities in a `HashSet` without overriding `equals` and `hashCode` means "the same object", not "the same row". That's a problem when two different `Player` objects for the same row meet in one set, which happens if you mix objects loaded in different transactions. Here, every change to a team's players happens **inside one transaction** (step 9), where Hibernate guarantees one object per row, so object identity is enough. If you ever add players loaded elsewhere, you'd need `equals` and `hashCode` based on something stable, such as `apiId`.

### 5. The repository

Create `team/PlayerRepository.java`:

```java
package com.example.importer.team;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    Optional<Player> findByApiId(long apiId);

    boolean existsByApiId(long apiId);
}
```

- **`findByApiId`**, as for teams: find the player by **their** id.
- **`existsByApiId`** is a derived query too: Spring Data reads `existsBy` as "is there at least one row where…", and returns a `boolean` without loading the player. It's the question the import asks for every squad member: *do we already have this one?*

### 6. The provider's squads and profiles as records

Back in the `provider` package: the provider's model, in the provider's shape. Here's a squad reply from the fake provider (shortened to two players; the real one has the same shape):

```json
{"get":"players/squads","parameters":{"team":"900001"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"team":{"id":900001,"name":"Riverside Rovers","logo":"https://media.api-sports.io/football/teams/900001.png"},"players":[{"id":9000001,"name":"T. Marsh","age":29,"number":1,"position":"Goalkeeper","photo":"https://media.api-sports.io/football/players/9000001.png"},{"id":9000002,"name":"L. Okafor","age":24,"number":4,"position":"Defender","photo":"https://media.api-sports.io/football/players/9000002.png"}]}]}
```

The same envelope as every reply (guide 02), with **one** entry in `response`, holding the team and its `players`. Create `provider/SquadItem.java`:

```java
package com.example.importer.provider;

import java.util.List;

/** One entry in the /players/squads "response" list. We only need the players (and only their ids). */
public record SquadItem(List<SquadMember> players) {
}
```

and `provider/SquadMember.java`:

```java
package com.example.importer.provider;

/** A squad member as the squad endpoint describes them. We use only the id: profiles are the source of truth. */
public record SquadMember(Long id, String name, Integer age, Integer number, String position) {
}
```

`SquadItem` leaves out the squad's `team` object: we've just fetched the team itself. `SquadMember` describes more than we use, so a reader can see what the squad offers and why we don't take it.

A profile reply:

```json
{"get":"players/profiles","parameters":{"player":"9000001"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"player":{"id":9000001,"name":"T. Marsh","firstname":"Thomas","lastname":"Marsh","age":29,"birth":{"date":"1997-03-14","place":"Leeds","country":"England"},"nationality":"England","height":"188","weight":"84","number":1,"position":"Goalkeeper","photo":"https://media.api-sports.io/football/players/9000001.png"}}]}
```

Create `provider/ProfileItem.java`:

```java
package com.example.importer.provider;

/** One entry in the /players/profiles "response" list. */
public record ProfileItem(ApiPlayer player) {
}
```

and `provider/ApiPlayer.java`:

```java
package com.example.importer.provider;

/** A player profile, exactly as API-Football describes it. Almost anything can be null. */
public record ApiPlayer(Long id, String name, String firstname, String lastname, Integer age, Birth birth,
        String nationality, Integer number, String position) {

    public record Birth(String date, String place, String country) {
    }
}
```

- **`Birth`** is a **nested record** for the nested `birth` object. Its `date` is a `String`: the provider writes `"1997-03-14"`, or `null`, and step 9 decides what to make of it.
- **`height`, `weight` and `photo` aren't mapped.** Jackson ignores fields the record doesn't have (guide 01).
- **Boxed types everywhere** (`Long`, `Integer`): any of them can arrive as `null`, as the next step's fixtures show.
- **`nationality` isn't `birth.country`.** L. Okafor in the fake provider was born in England and plays for Nigeria. We store `nationality`.

Now tell the HTTP interface about the two endpoints. In `provider/ApiFootball.java`, add:

```java
    @GetExchange("/players/squads")
    ApiFootballReply<SquadItem> squads(@RequestParam("team") long team);

    @GetExchange("/players/profiles")
    ApiFootballReply<ProfileItem> profiles(@RequestParam("player") long player);
```

The same pattern as guide 02's `teams(...)`: the path, the query parameter by the provider's name for it, and the envelope with the right item type.

### 7. The client: squad ids and an optional profile

`ApiFootballClient` is still the only class that reads the envelope. Replace `provider/ApiFootballClient.java`:

```java
package com.example.importer.provider;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

/** Calls API-Football and turns its replies into either data or one of our own exceptions. */
@Component
public class ApiFootballClient {

    private final ApiFootball apiFootball;

    public ApiFootballClient(ApiFootball apiFootball) {
        this.apiFootball = apiFootball;
    }

    /** GET /teams?id={id}: the team and its venue. */
    public TeamItem fetchTeam(long id) {
        return single(apiFootball.teams(id), "team", id);
    }

    /** GET /players/squads?team={id}: the api ids of the team's current players. No results = no players. */
    public List<Long> fetchSquadIds(long teamId) {
        ApiFootballReply<SquadItem> reply = checked(apiFootball.squads(teamId));
        return reply.response().stream()
                .flatMap(squad -> squad.players().stream())
                .map(SquadMember::id)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /** GET /players/profiles?player={id}: the profile, or empty when the provider has none ("results": 0). */
    public Optional<ApiPlayer> fetchProfile(long playerId) {
        ApiFootballReply<ProfileItem> reply = checked(apiFootball.profiles(playerId));
        return reply.response().stream().findFirst().map(ProfileItem::player);
    }

    private static <T> ApiFootballReply<T> checked(ApiFootballReply<T> reply) {
        // A 200 can still be a failure: the reasons are in "errors"
        if (reply.hasErrors()) {
            throw new ProviderRejectedException(reply.errors());
        }
        return reply;
    }

    private static <T> T single(ApiFootballReply<T> reply, String what, long id) {
        checked(reply);
        // Unknown ids are not a 404: just no results
        if (reply.results() == 0 || reply.response().isEmpty()) {
            throw new NotFoundAtProviderException(what, id);
        }
        return reply.response().getFirst();
    }
}
```

**`checked(reply)`** is guide 02's "errors first" check, pulled out of `single` so every method uses it. A rejected squad or profile request is a `ProviderRejectedException`, just like a rejected team.

**`fetchSquadIds`** returns **only ids**, so nothing above the client can be tempted to build a player from the squad. Reading the stream line by line: every entry in `response` (**`flatMap`** turns each squad's list of players into one stream of players), each player's `id`, **drop `null` ids** (a member without an id can't be linked to anything), **`distinct()`** in case the provider lists someone twice, and into a `List`.

**A squad with `results: 0` is an empty squad, not "not found".** We fetched the team a moment ago, so it exists; it just has no players listed. That's why `fetchSquadIds` doesn't use `single(...)`. (What the real API sends for a team with no squad hasn't been checked for this series.)

**`fetchProfile` returns an `Optional`.** Guide 02 said `single(...)` could serve player profiles too. It turns out it shouldn't: `single` throws `NotFoundAtProviderException` for `results: 0`, and an empty profile isn't an error that should stop an import. It's a skipped player (Concepts). So an empty profile is `Optional.empty()`, and the importer decides what that means. `findFirst()` on the `response` stream gives the first entry if there is one, and `.map(ProfileItem::player)` unwraps it.

### 8. What the importer returns, and what the API shows

Three small records in the `team` package. First, guide 03's `TeamView` moves out of the controller, because the import result needs it too. Delete the nested `TeamView` record from `TeamController` (step 11 shows the whole controller), and create `team/TeamView.java`:

```java
package com.example.importer.team;

import java.time.Instant;

public record TeamView(Long id, long apiId, String name, String hudName, String country, boolean national,
        Instant lastImportedAt) {

    static TeamView from(Team team) {
        return new TeamView(team.getId(), team.getApiId(), team.getName(), team.getHudName(), team.getCountry(),
                team.isNational(), team.getLastImportedAt());
    }
}
```

`team/PlayerView.java`, the JSON shape of a player:

```java
package com.example.importer.team;

public record PlayerView(Long id, long apiId, String name, String nationality, Position position,
        Integer birthYear) {

    static PlayerView from(Player player) {
        return new PlayerView(player.getId(), player.getApiId(), player.getName(), player.getNationality(),
                player.getPosition(), player.getBirthYear());
    }
}
```

and `team/ImportResult.java`, what one import did:

```java
package com.example.importer.team;

import java.util.List;

/** What one import did. */
public record ImportResult(TeamView team, int squadSize, int profilesFetched, int skipped,
        List<String> skippedReasons) {
}
```

- **`squadSize`**: how many members the squad listed.
- **`profilesFetched`**: how many profile **requests** the import made. This is the number to watch: it's what the import cost, beyond the team and squad.
- **`skipped`** and **`skippedReasons`**: members we couldn't create, and why.

### 9. The database work: `TeamService`

Every database write in the algorithm is a `TeamService` method with its own short transaction. First, an exception for things that aren't in **our** database (as opposed to guide 02's `NotFoundAtProviderException`, for things the **provider** doesn't have). Create `web/NotFoundException.java`:

```java
package com.example.importer.web;

/** Something in OUR database doesn't exist. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String what, long id) {
        super(what + " " + id + " was not found");
    }
}
```

and give it a handler in `web/ApiExceptionHandler.java`, next to the other two:

```java
    @ExceptionHandler
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
```

The same shape as guide 02's handlers: a `404` `ProblemDetail` with the message as its `detail`.

Now replace `team/TeamService.java`:

```java
package com.example.importer.team;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.importer.provider.ApiPlayer;
import com.example.importer.provider.ApiTeam;
import com.example.importer.web.NotFoundException;

/** Database work for teams and players. Every method is one short transaction, and none calls the provider. */
@Service
public class TeamService {

    private static final Logger log = LoggerFactory.getLogger(TeamService.class);

    private final TeamRepository teams;
    private final PlayerRepository players;

    public TeamService(TeamRepository teams, PlayerRepository players) {
        this.teams = teams;
        this.players = players;
    }

    /** Create or update: the provider's id decides whether we already have this team. */
    @Transactional
    public Team saveFromProvider(ApiTeam apiTeam) {
        Team team = teams.findByApiId(apiTeam.id())
                .orElseGet(() -> new Team(apiTeam.id()));
        team.update(apiTeam.name(), apiTeam.code(), apiTeam.country(), apiTeam.national());
        return teams.save(team);
    }

    /** The squad we received is the truth: anyone linked to the team but not in it has left. */
    @Transactional
    public int unlinkPlayersNotIn(long teamId, Collection<Long> squadApiIds) {
        return team(teamId).removePlayersNotIn(squadApiIds);
    }

    @Transactional(readOnly = true)
    public boolean playerExists(long playerApiId) {
        return players.existsByApiId(playerApiId);
    }

    /** We already have this player (maybe from another team): just make sure they're linked. */
    @Transactional
    public void linkExistingPlayer(long teamId, long playerApiId) {
        Player player = players.findByApiId(playerApiId).orElseThrow();
        team(teamId).addPlayer(player);
    }

    /** A new player: create them from their profile and link them, in one transaction. */
    @Transactional
    public void createAndLinkPlayer(long teamId, ApiPlayer profile) {
        Position position = Position.fromProvider(profile.position());
        if (position == null && profile.position() != null) {
            log.warn("Player {} has an unexpected position '{}': stored without a position",
                    profile.id(), profile.position());
        }
        Player player = players.save(new Player(profile.id(), profile.name(), profile.nationality(), position,
                birthYear(profile)));
        team(teamId).addPlayer(player);
    }

    @Transactional
    public Team markImported(long teamId, Instant when) {
        Team team = team(teamId);
        team.markImported(when);
        return team;
    }

    @Transactional(readOnly = true)
    public List<Team> findAll() {
        return teams.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public List<PlayerView> playersOf(long teamId) {
        return team(teamId).getPlayers().stream()
                .sorted(Comparator.comparing(Player::getName))
                .map(PlayerView::from)
                .toList();
    }

    private Team team(long teamId) {
        return teams.findById(teamId).orElseThrow(() -> new NotFoundException("Team", teamId));
    }

    private static Integer birthYear(ApiPlayer profile) {
        if (profile.birth() == null || profile.birth().date() == null) {
            return null;
        }
        try {
            return LocalDate.parse(profile.birth().date()).getYear();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
```

`saveFromProvider` and `findAll` are unchanged from guide 03. The rest, one at a time:

- **The methods take our team's `id`, not the `Team` object.** Each one loads the team **fresh, inside its own transaction** (the private `team(teamId)`), so the team is managed there and changes to its players are written when that transaction commits. A `Team` object from an earlier, finished transaction would be **detached**: changing its set would change nothing in the database, and touching its lazy `players` would throw.
- **No `save` after changing a team's players.** The team was loaded in this transaction, so Hibernate's **dirty checking** (guide 03) notices the set has changed and inserts or deletes the `team_player` rows at commit.
- **`unlinkPlayersNotIn`**: one transaction that removes the leavers. It runs as soon as the squad arrives.
- **`playerExists`** is `readOnly`: it only asks the question.
- **`linkExistingPlayer`** finds the player we already have (`orElseThrow()`: `playerExists` just said they're there) and links them. If they're already linked, the `Set` ignores it.
- **`createAndLinkPlayer`** is "link as you go": the `INSERT` into `player` and the `INSERT` into `team_player` are **one** transaction. There's never a moment when the player exists but isn't linked to the team being imported, or the other way round.
- **The position warning** is logged only when the provider sent **something** we don't recognise. A missing position is ordinary; `"Wing-back"` is worth a line in the log. The player is stored either way.
- **`birthYear`**: `LocalDate.parse("1997-03-14")` reads an ISO date, and `getYear()` gives `1997`. No birth, no date, or a date that won't parse: `null`. A bad date shouldn't cost us the player.
- **`markImported`** sets `lastImportedAt` and returns the team for the import's result.
- **`playersOf`** must build the `PlayerView`s **inside** its `readOnly` transaction. `getPlayers()` is lazy, and with `open-in-view=false` there's no session once the method returns: hand the controller the entities instead, and it fails (Common errors). `Comparator.comparing(Player::getName)` sorts the players by name.
- **`team(teamId)`** throws `NotFoundException("Team", teamId)`: `Team 999999 was not found`, which the new handler makes a `404`.

### 10. The algorithm: `TeamImporter`

Replace `team/TeamImporter.java`:

```java
package com.example.importer.team;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.example.importer.provider.ApiFootballClient;
import com.example.importer.provider.ApiPlayer;

/**
 * Imports a team and its squad.
 * Deliberately NOT @Transactional: HTTP calls can take seconds, and a transaction would hold a database
 * connection (and maybe locks) the whole time. So: call the provider, then a short transaction, and repeat.
 */
@Service
public class TeamImporter {

    private final ApiFootballClient provider;
    private final TeamService teamService;

    public TeamImporter(ApiFootballClient provider, TeamService teamService) {
        this.provider = provider;
        this.teamService = teamService;
    }

    public ImportResult importTeam(long apiId) {
        // 1. The team itself: create or update
        Team team = teamService.saveFromProvider(provider.fetchTeam(apiId).team());

        // 2. The squad: only the members' ids. Anyone no longer in it is unlinked straight away.
        List<Long> squad = provider.fetchSquadIds(apiId);
        teamService.unlinkPlayersNotIn(team.getId(), squad);

        // 3. Each member: link if we have them, otherwise fetch their profile, create them and link them
        int profilesFetched = 0;
        List<String> skipped = new ArrayList<>();
        for (long playerApiId : squad) {
            if (teamService.playerExists(playerApiId)) {
                teamService.linkExistingPlayer(team.getId(), playerApiId);
                continue;
            }
            Optional<ApiPlayer> profile = provider.fetchProfile(playerApiId);
            profilesFetched++;
            String problem = problemWith(profile);
            if (problem != null) {
                skipped.add("Player " + playerApiId + ": " + problem);
                continue;
            }
            teamService.createAndLinkPlayer(team.getId(), profile.get());
        }

        // 4. Every member is done (linked or skipped): the import is complete
        team = teamService.markImported(team.getId(), Instant.now());
        return new ImportResult(TeamView.from(team), squad.size(), profilesFetched, skipped.size(), skipped);
    }

    /** Why we can't create a player from this profile, or null if we can. */
    private static String problemWith(Optional<ApiPlayer> profile) {
        if (profile.isEmpty()) {
            return "the provider has no profile";
        }
        if (profile.get().id() == null) {
            return "profile has no id";
        }
        if (profile.get().name() == null || profile.get().name().isBlank()) {
            return "profile has no name";
        }
        return null;
    }
}
```

The four numbered comments are the four steps from the Concepts, in order:

1. **The team.** Guide 03's fetch-then-save, in one line. The returned `Team` is only used for its `id` from here on.
2. **The squad.** `fetchSquadIds` (HTTP), then `unlinkPlayersNotIn` (a transaction) **immediately**, before any profile is fetched. If the import fails later, the leavers are already gone.
3. **Each member.** `playerExists`: yes → link, **no request**, next member. No → `fetchProfile` (HTTP) and count it, because the request has been made whatever comes back. If `problemWith` finds a reason not to create the player, record it and move on: the member is **done**. Otherwise `createAndLinkPlayer`.
4. **Complete.** Only reached when the loop has dealt with every member. `markImported` sets `lastImportedAt`, and the returned team (with its new timestamp) goes into the result.

**What happens when something throws?** Say the provider answers `500` on the third profile. `fetchProfile` throws `ProviderHttpException` (guide 02), which leaves `importTeam` straight away. The team was saved in step 1, the leavers were unlinked in step 2, and the first two new players were created and linked: each in a transaction that has **already committed**. Step 4 never runs, so `lastImportedAt` keeps whatever it had. The exception reaches guide 02's handler, which answers `502`. Run the import again later, and the two players already created count as "we have them": only the third profile onwards costs a request.

**There's no `try`/`catch`** for this. Not catching is the design: the transactions are what keep the work done so far, and the missing `lastImportedAt` is what says "not finished".

`problemWith` returns a reason, or `null` for "fine". An empty profile, or one with no `id` or no `name`, can't become a player (`Player` needs both). `isBlank()` treats `""` and `"   "` as no name, as guide 03's HUD rule did for codes.

### 11. The endpoints

Replace `team/TeamController.java`:

```java
package com.example.importer.team;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TeamController {

    private final TeamImporter importer;
    private final TeamService teamService;

    public TeamController(TeamImporter importer, TeamService teamService) {
        this.importer = importer;
        this.teamService = teamService;
    }

    /** Imports one team and its squad now, and waits for it. (Guide 05 moves imports into the background.) */
    @PostMapping("/imports/teams/{apiId}")
    public ImportResult importTeam(@PathVariable long apiId) {
        return importer.importTeam(apiId);
    }

    @GetMapping("/teams")
    public List<TeamView> teams() {
        return teamService.findAll().stream().map(TeamView::from).toList();
    }

    @GetMapping("/teams/{id}/players")
    public List<PlayerView> players(@PathVariable long id) {
        return teamService.playersOf(id);
    }
}
```

- **`POST /imports/teams/{apiId}`** now returns the `ImportResult`. It still waits for the whole import and answers `200`. With six players, that's quick on the fake provider; on the real API, with 25 players and guide 06's pauses, it can take minutes, which is why guide 05 moves it into the background.
- **`GET /teams/{id}/players`** takes **our** team id (the `id` in `GET /teams`), like every URL that's about our own data. Only the import URL takes the provider's id, because that's what you're asking the provider for. It reads only our database.
- The nested `TeamView` record has gone (it's in its own file now), and so has the `Instant` import.

The main code compiles again. The tests don't, yet: step 15 updates them.

### 12. Squads and profiles in the fake provider

The fake provider needs a squad for each of its three teams, and a profile for each player: 3 squads and 13 profiles. Rather than one mapping per file, use **one mapping per endpoint** and let WireMock pick the file from the request.

Create `fake-provider/mappings/squads.json`:

```json
{
  "request": {
    "method": "GET",
    "urlPath": "/players/squads",
    "queryParameters": { "team": { "matches": "[0-9]+" } }
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
    "bodyFileName": "squads-{{request.query.team}}.json",
    "transformers": ["response-template"]
  }
}
```

and `fake-provider/mappings/profiles.json`, the same with `profiles` for `squads` and `player` for `team`:

```json
{
  "request": {
    "method": "GET",
    "urlPath": "/players/profiles",
    "queryParameters": { "player": { "matches": "[0-9]+" } }
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
    "bodyFileName": "profiles-{{request.query.player}}.json",
    "transformers": ["response-template"]
  }
}
```

- **`"bodyFileName": "squads-{{request.query.team}}.json"`** uses guide 02's response templating in the **file name**: `GET /players/squads?team=900001` is answered with `__files/squads-900001.json`. Adding a player to the fake provider is now adding one file, with no mapping to write.
- **`"matches": "[0-9]+"`** only matches numbers, so guide 02's `squads-validation-error.json` (team that *isn't* a number) still answers `?team=abc`.
- **A number with no file** gets a `500` from WireMock. Common errors shows it.

Now the files, all in `fake-provider/__files/`, each **on one line**. Riverside Rovers' squad, `squads-900001.json`:

```json
{"get":"players/squads","parameters":{"team":"900001"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"team":{"id":900001,"name":"Riverside Rovers","logo":"https://media.api-sports.io/football/teams/900001.png"},"players":[{"id":9000001,"name":"T. Marsh","age":29,"number":1,"position":"Goalkeeper","photo":"https://media.api-sports.io/football/players/9000001.png"},{"id":9000002,"name":"L. Okafor","age":24,"number":4,"position":"Defender","photo":"https://media.api-sports.io/football/players/9000002.png"},{"id":9000003,"name":"D. Brennan","age":27,"number":5,"position":"Defender","photo":"https://media.api-sports.io/football/players/9000003.png"},{"id":9000004,"name":"S. Castillo","age":26,"number":8,"position":"Midfielder","photo":"https://media.api-sports.io/football/players/9000004.png"},{"id":9000005,"name":"J. Whitlow","age":21,"number":14,"position":"Midfielder","photo":"https://media.api-sports.io/football/players/9000005.png"},{"id":9000006,"name":"K. Adeyemi","age":23,"number":9,"position":"Attacker","photo":"https://media.api-sports.io/football/players/9000006.png"}]}]}
```

T. Marsh's profile, `profiles-9000001.json`, was shown in step 6. Two of Harbour Athletic's players are the awkward cases from the Concepts. C. Lindqvist, `profiles-9000013.json`, has no nationality, no date of birth and no age:

```json
{"get":"players/profiles","parameters":{"player":"9000013"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"player":{"id":9000013,"name":"C. Lindqvist","firstname":"Carl","lastname":"Lindqvist","age":null,"birth":{"date":null,"place":null,"country":null},"nationality":null,"height":"180","weight":"74","number":6,"position":"Midfielder","photo":"https://media.api-sports.io/football/players/9000013.png"}}]}
```

and M. Duffy, `profiles-9000014.json`, is in the squad but has **no profile**:

```json
{"get":"players/profiles","parameters":{"player":"9000014"},"errors":[],"results":0,"paging":{"current":1,"total":1},"response":[]}
```

Here's who's where:

| Team | Squad | Notes |
| --- | --- | --- |
| Riverside Rovers (900001) | 9000001 T. Marsh, 9000002 L. Okafor, 9000003 D. Brennan, 9000004 S. Castillo, 9000005 J. Whitlow, 9000006 K. Adeyemi | Okafor: born in England, nationality Nigeria. Adeyemi: born in Ghana, nationality England |
| Harbour Athletic (900002) | 9000011 A. MacLeod, 9000012 R. Fraser, 9000013 C. Lindqvist, 9000014 M. Duffy, 9000015 E. Byrne | Lindqvist: `null` fields. Duffy: empty profile |
| Scotland (900100, national) | 9000102 N. Paterson, 9000011 A. MacLeod, 9000012 R. Fraser, 9000003 D. Brennan, 9000101 G. Kerr | **Three** players from the clubs |

The squads are small on purpose: a full first import of Riverside Rovers is 1 + 1 + 6 = **8** requests, inside the real API's 10 a minute. Guide 06 uses that.

<details>
<summary>The other files in <code>fake-provider/__files/</code> (copy each onto one line)</summary>

`squads-900002.json`:

```json
{"get":"players/squads","parameters":{"team":"900002"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"team":{"id":900002,"name":"Harbour Athletic","logo":"https://media.api-sports.io/football/teams/900002.png"},"players":[{"id":9000011,"name":"A. MacLeod","age":31,"number":1,"position":"Goalkeeper","photo":"https://media.api-sports.io/football/players/9000011.png"},{"id":9000012,"name":"R. Fraser","age":28,"number":3,"position":"Defender","photo":"https://media.api-sports.io/football/players/9000012.png"},{"id":9000013,"name":"C. Lindqvist","age":null,"number":6,"position":"Midfielder","photo":"https://media.api-sports.io/football/players/9000013.png"},{"id":9000014,"name":"M. Duffy","age":26,"number":7,"position":"Attacker","photo":"https://media.api-sports.io/football/players/9000014.png"},{"id":9000015,"name":"E. Byrne","age":22,"number":10,"position":"Midfielder","photo":"https://media.api-sports.io/football/players/9000015.png"}]}]}
```

`squads-900100.json`:

```json
{"get":"players/squads","parameters":{"team":"900100"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"team":{"id":900100,"name":"Scotland","logo":"https://media.api-sports.io/football/teams/900100.png"},"players":[{"id":9000102,"name":"N. Paterson","age":30,"number":12,"position":"Goalkeeper","photo":"https://media.api-sports.io/football/players/9000102.png"},{"id":9000011,"name":"A. MacLeod","age":31,"number":1,"position":"Goalkeeper","photo":"https://media.api-sports.io/football/players/9000011.png"},{"id":9000012,"name":"R. Fraser","age":28,"number":3,"position":"Defender","photo":"https://media.api-sports.io/football/players/9000012.png"},{"id":9000003,"name":"D. Brennan","age":27,"number":5,"position":"Defender","photo":"https://media.api-sports.io/football/players/9000003.png"},{"id":9000101,"name":"G. Kerr","age":25,"number":11,"position":"Attacker","photo":"https://media.api-sports.io/football/players/9000101.png"}]}]}
```

`profiles-9000002.json`:

```json
{"get":"players/profiles","parameters":{"player":"9000002"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"player":{"id":9000002,"name":"L. Okafor","firstname":"Lewis","lastname":"Okafor","age":24,"birth":{"date":"2002-06-02","place":"London","country":"England"},"nationality":"Nigeria","height":"184","weight":"79","number":4,"position":"Defender","photo":"https://media.api-sports.io/football/players/9000002.png"}}]}
```

`profiles-9000003.json`:

```json
{"get":"players/profiles","parameters":{"player":"9000003"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"player":{"id":9000003,"name":"D. Brennan","firstname":"Daniel","lastname":"Brennan","age":27,"birth":{"date":"1999-01-21","place":"Paisley","country":"Scotland"},"nationality":"Scotland","height":"181","weight":"77","number":5,"position":"Defender","photo":"https://media.api-sports.io/football/players/9000003.png"}}]}
```

`profiles-9000004.json`:

```json
{"get":"players/profiles","parameters":{"player":"9000004"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"player":{"id":9000004,"name":"S. Castillo","firstname":"Samuel","lastname":"Castillo","age":26,"birth":{"date":"2000-09-30","place":"Valencia","country":"Spain"},"nationality":"Spain","height":"176","weight":"70","number":8,"position":"Midfielder","photo":"https://media.api-sports.io/football/players/9000004.png"}}]}
```

`profiles-9000005.json`:

```json
{"get":"players/profiles","parameters":{"player":"9000005"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"player":{"id":9000005,"name":"J. Whitlow","firstname":"Jacob","lastname":"Whitlow","age":21,"birth":{"date":"2005-04-11","place":"Bristol","country":"England"},"nationality":"England","height":"179","weight":"72","number":14,"position":"Midfielder","photo":"https://media.api-sports.io/football/players/9000005.png"}}]}
```

`profiles-9000006.json`:

```json
{"get":"players/profiles","parameters":{"player":"9000006"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"player":{"id":9000006,"name":"K. Adeyemi","firstname":"Kofi","lastname":"Adeyemi","age":23,"birth":{"date":"2003-11-05","place":"Accra","country":"Ghana"},"nationality":"England","height":"183","weight":"78","number":9,"position":"Attacker","photo":"https://media.api-sports.io/football/players/9000006.png"}}]}
```

`profiles-9000011.json`:

```json
{"get":"players/profiles","parameters":{"player":"9000011"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"player":{"id":9000011,"name":"A. MacLeod","firstname":"Alasdair","lastname":"MacLeod","age":31,"birth":{"date":"1995-02-17","place":"Inverness","country":"Scotland"},"nationality":"Scotland","height":"191","weight":"88","number":1,"position":"Goalkeeper","photo":"https://media.api-sports.io/football/players/9000011.png"}}]}
```

`profiles-9000012.json`:

```json
{"get":"players/profiles","parameters":{"player":"9000012"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"player":{"id":9000012,"name":"R. Fraser","firstname":"Ross","lastname":"Fraser","age":28,"birth":{"date":"1998-08-08","place":"Aberdeen","country":"Scotland"},"nationality":"Scotland","height":"185","weight":"80","number":3,"position":"Defender","photo":"https://media.api-sports.io/football/players/9000012.png"}}]}
```

`profiles-9000015.json`:

```json
{"get":"players/profiles","parameters":{"player":"9000015"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"player":{"id":9000015,"name":"E. Byrne","firstname":"Eoin","lastname":"Byrne","age":22,"birth":{"date":"2004-05-19","place":"Cork","country":"Ireland"},"nationality":"Ireland","height":"177","weight":"71","number":10,"position":"Midfielder","photo":"https://media.api-sports.io/football/players/9000015.png"}}]}
```

`profiles-9000101.json`:

```json
{"get":"players/profiles","parameters":{"player":"9000101"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"player":{"id":9000101,"name":"G. Kerr","firstname":"Graham","lastname":"Kerr","age":25,"birth":{"date":"2001-12-01","place":"Dundee","country":"Scotland"},"nationality":"Scotland","height":"182","weight":"76","number":11,"position":"Attacker","photo":"https://media.api-sports.io/football/players/9000101.png"}}]}
```

`profiles-9000102.json`:

```json
{"get":"players/profiles","parameters":{"player":"9000102"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"player":{"id":9000102,"name":"N. Paterson","firstname":"Neil","lastname":"Paterson","age":30,"birth":{"date":"1996-07-23","place":"Stirling","country":"Scotland"},"nationality":"Scotland","height":"190","weight":"86","number":12,"position":"Goalkeeper","photo":"https://media.api-sports.io/football/players/9000102.png"}}]}
```

</details>

Restart the fake provider (Ctrl+C, then `java -jar wiremock-standalone-3.13.2.jar --port 8089` from `fake-provider/`) so it loads the two new mappings, and check one of each:

```bash
curl "http://localhost:8089/players/squads?team=900002"
curl "http://localhost:8089/players/profiles?player=9000014"
```

The first prints Harbour Athletic's squad file, the second M. Duffy's empty profile.

### 13. Try it

Start from an empty database, as in guide 03, so your ids match these: stop the importer, delete `importer/data` (in IntelliJ, right-click `data` → **Delete**; or `rm -r data` in Git Bash, `Remove-Item -Recurse data` in PowerShell), and start the importer. Flyway now runs both migrations:

```
Migrating schema "PUBLIC" to version "1 - create team"
Migrating schema "PUBLIC" to version "2 - create player"
Successfully applied 2 migrations to schema "PUBLIC", now at version v2
```

(If you kept guide 03's database, Flyway runs only V2. Everything works the same; the ids may differ.)

Import the three teams, clubs first (in PowerShell, `curl.exe`):

```bash
curl -X POST http://localhost:8080/imports/teams/900001
```

```json
{"team":{"id":1,"apiId":900001,"name":"Riverside Rovers","hudName":"RIV","country":"England","national":false,"lastImportedAt":"2026-11-15T19:06:38.910006100Z"},"squadSize":6,"profilesFetched":6,"skipped":0,"skippedReasons":[]}
```

Six in the squad, six profiles fetched, and **`lastImportedAt` has a value** at last: this import completed.

```bash
curl -X POST http://localhost:8080/imports/teams/900002
```

```json
{"team":{"id":2,"apiId":900002,"name":"Harbour Athletic","hudName":"HAR","country":"Scotland","national":false,"lastImportedAt":"2026-11-15T19:06:39.371260800Z"},"squadSize":5,"profilesFetched":5,"skipped":1,"skippedReasons":["Player 9000014: the provider has no profile"]}
```

Five profiles **fetched**, one **skipped**: M. Duffy's empty profile cost a request, created nothing, and still counted as done, so `lastImportedAt` is set.

```bash
curl -X POST http://localhost:8080/imports/teams/900100
```

```json
{"team":{"id":3,"apiId":900100,"name":"Scotland","hudName":"SCO","country":"Scotland","national":true,"lastImportedAt":"2026-11-15T19:06:39.715179200Z"},"squadSize":5,"profilesFetched":2,"skipped":0,"skippedReasons":[]}
```

**Five in the squad, two profiles fetched.** MacLeod, Fraser and Brennan were already in the database from the clubs, so they were linked without a request. Only Paterson and Kerr were new.

**Count what the provider was asked.** WireMock keeps a log of the requests it has received, and its admin API can count them. In Git Bash:

```bash
curl -X POST http://localhost:8089/__admin/requests/count -d '{"method":"GET","urlPath":"/players/profiles"}'
```

```
{
  "count" : 13,
  "requestJournalDisabled" : false
}
```

The body describes which requests to count: every `GET` to `/players/profiles`. **13**: 6 + 5 + 2.

**In Windows PowerShell**, the double quotes inside the JSON need a backslash in front of each, because PowerShell 5.1 strips them out of arguments it passes to `curl.exe`:

```powershell
curl.exe -X POST http://localhost:8089/__admin/requests/count -d '{\"method\":\"GET\",\"urlPath\":\"/players/profiles\"}'
```

Without the backslashes, WireMock receives `{method:GET,urlPath:/players/profiles}` and answers `Error parsing JSON` (see Common errors).

**Now import Riverside Rovers again:**

```bash
curl -X POST http://localhost:8080/imports/teams/900001
```

```
{"team":{"id":1,…,"lastImportedAt":"2026-11-15T19:06:40.171742900Z"},"squadSize":6,"profilesFetched":0,"skipped":0,"skippedReasons":[]}
```

(Shortened.) **`profilesFetched` 0**, and a new `lastImportedAt`. Count again, and it's **still 13**. The re-import cost two requests, the team and the squad, which is the whole argument of the Concepts' trade-off in one number.

**Who's in each team.** Scotland is our team `3`:

```bash
curl http://localhost:8080/teams/3/players
```

```json
[{"id":7,"apiId":9000011,"name":"A. MacLeod","nationality":"Scotland","position":"GOALKEEPER","birthYear":1995},{"id":3,"apiId":9000003,"name":"D. Brennan","nationality":"Scotland","position":"DEFENDER","birthYear":1999},{"id":12,"apiId":9000101,"name":"G. Kerr","nationality":"Scotland","position":"ATTACKER","birthYear":2001},{"id":11,"apiId":9000102,"name":"N. Paterson","nationality":"Scotland","position":"GOALKEEPER","birthYear":1996},{"id":8,"apiId":9000012,"name":"R. Fraser","nationality":"Scotland","position":"DEFENDER","birthYear":1998}]
```

Sorted by name. D. Brennan is player `3` here **and** in Riverside Rovers: one row, two links. The ids aren't in squad order (Kerr is 12, Paterson 11) because players are numbered in the order they were **created**, across all three imports.

Harbour Athletic (shortened):

```bash
curl http://localhost:8080/teams/2/players
```

```
[{"id":7,"apiId":9000011,"name":"A. MacLeod",…},{"id":9,"apiId":9000013,"name":"C. Lindqvist","nationality":null,"position":"MIDFIELDER","birthYear":null},{"id":10,"apiId":9000015,"name":"E. Byrne","nationality":"Ireland","position":"MIDFIELDER","birthYear":2004},{"id":8,"apiId":9000012,"name":"R. Fraser",…}]
```

Four players, not five: M. Duffy isn't there. C. Lindqvist is, with `null` where the provider had nothing.

`curl http://localhost:8080/teams` now shows a `lastImportedAt` for each team. If you compare closely, it has **six** decimal places there and **seven** in the import's reply: the reply shows the Java `Instant` as the clock gave it, and H2's `timestamp with time zone` keeps microseconds. Harmless.

### 14. A player leaves

The fake provider's data never changes by itself, so this guide adds a third kind of switchable folder next to guide 02's `misbehaving/`: **`changes/`**, for "the provider's data has changed". (`misbehaving/` is for "the provider is failing".) Like `misbehaving/`, WireMock doesn't load it at start-up.

Create `fake-provider/changes/squad-900001-player-left.json`:

```json
{
  "priority": 1,
  "request": {
    "method": "GET",
    "urlPath": "/players/squads",
    "queryParameters": { "team": { "equalTo": "900001" } }
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
    "bodyFileName": "squads-900001-after-transfer.json"
  }
}
```

and its body, `fake-provider/__files/squads-900001-after-transfer.json`: Riverside Rovers' squad **without J. Whitlow** (9000005):

```json
{"get":"players/squads","parameters":{"team":"900001"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"team":{"id":900001,"name":"Riverside Rovers","logo":"https://media.api-sports.io/football/teams/900001.png"},"players":[{"id":9000001,"name":"T. Marsh","age":29,"number":1,"position":"Goalkeeper","photo":"https://media.api-sports.io/football/players/9000001.png"},{"id":9000002,"name":"L. Okafor","age":24,"number":4,"position":"Defender","photo":"https://media.api-sports.io/football/players/9000002.png"},{"id":9000003,"name":"D. Brennan","age":27,"number":5,"position":"Defender","photo":"https://media.api-sports.io/football/players/9000003.png"},{"id":9000004,"name":"S. Castillo","age":26,"number":8,"position":"Midfielder","photo":"https://media.api-sports.io/football/players/9000004.png"},{"id":9000006,"name":"K. Adeyemi","age":23,"number":9,"position":"Attacker","photo":"https://media.api-sports.io/football/players/9000006.png"}]}]}
```

`"priority": 1` beats the generic `squads.json` (default priority 5) for team 900001 only. No `transformers` this time: the file name is fixed.

Switch it on, from `fake-provider/`, and import Riverside Rovers:

```bash
curl -X POST http://localhost:8089/__admin/mappings -d @changes/squad-900001-player-left.json
curl -X POST http://localhost:8080/imports/teams/900001
```

The first answers `201 Created`. The import:

```
{"team":{"id":1,…},"squadSize":5,"profilesFetched":0,"skipped":0,"skippedReasons":[]}
```

```bash
curl http://localhost:8080/teams/1/players
```

```
[{"id":3,"apiId":9000003,"name":"D. Brennan",…},{"id":6,"apiId":9000006,"name":"K. Adeyemi",…},{"id":2,"apiId":9000002,"name":"L. Okafor","nationality":"Nigeria",…},{"id":4,"apiId":9000004,"name":"S. Castillo",…},{"id":1,"apiId":9000001,"name":"T. Marsh",…}]
```

J. Whitlow has gone from Riverside Rovers. He's still in the `player` table, only his `team_player` row was deleted. Now switch the change off, and import again: he's "back" in the squad.

```bash
curl -X POST http://localhost:8089/__admin/mappings/reset
curl -X POST http://localhost:8080/imports/teams/900001
```

```
{"team":{"id":1,…},"squadSize":6,"profilesFetched":0,"skipped":0,"skippedReasons":[]}
```

Six again, and **`profilesFetched` 0**: because he was unlinked rather than deleted, the importer still had him, and linked him again without asking the provider.

In PowerShell, the same commands, from `fake-provider\`, with `curl.exe`:

```powershell
curl.exe -X POST http://localhost:8089/__admin/mappings -d @changes/squad-900001-player-left.json
curl.exe -X POST http://localhost:8080/imports/teams/900001
curl.exe -X POST http://localhost:8089/__admin/mappings/reset
```

### 15. Tests

The tests build their own replies with guide 01's `ProviderJson`, so it needs squads and profiles. In `src/test/java/com/example/importer/provider/ProviderJson.java`, add:

```java
    /** A squad: each player is called "Player {id}" (the importer only uses the ids). */
    public static String squad(long teamId, long... playerIds) {
        StringBuilder players = new StringBuilder();
        for (long id : playerIds) {
            if (!players.isEmpty()) {
                players.append(',');
            }
            players.append("""
                    {"id":%d,"name":"Player %d","age":25,"number":7,"position":"Midfielder",\
                    "photo":"https://media.api-sports.io/football/players/%d.png"}""".formatted(id, id, id));
        }
        return """
                {"get":"players/squads","parameters":{"team":"%d"},"errors":[],"results":1,"paging":{"current":1,"total":1},
                 "response":[{"team":{"id":%d,"name":"Some Team","logo":"https://media.api-sports.io/football/teams/%d.png"},
                  "players":[%s]}]}
                """.formatted(teamId, teamId, teamId, players);
    }

    public static String profile(long id, String name, String nationality, String birthDate, String position) {
        return """
                {"get":"players/profiles","parameters":{"player":"%d"},"errors":[],"results":1,"paging":{"current":1,"total":1},
                 "response":[{"player":{"id":%d,"name":%s,"firstname":"First","lastname":"Last","age":25,
                   "birth":{"date":%s,"place":null,"country":null},"nationality":%s,"height":"180","weight":"75",
                   "number":7,"position":%s,"photo":"https://media.api-sports.io/football/players/%d.png"}}]}
                """.formatted(id, id, text(name), text(birthDate), text(nationality), text(position), id);
    }

    public static String profile(long id, String name) {
        return profile(id, name, "England", "2000-01-31", "Defender");
    }

    public static String noProfile(long id) {
        return """
                {"get":"players/profiles","parameters":{"player":"%d"},"errors":[],"results":0,"paging":{"current":1,"total":1},"response":[]}
                """.formatted(id);
    }

    private static String text(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
```

- **`squad(teamId, playerIds...)`**: `long... playerIds` takes any number of ids, including none. `squad(0)` is an empty squad.
- **The `\` at the end of a text block line** joins it to the next line without a line break, so each player is one line of JSON.
- **`profile(id, name, nationality, birthDate, position)`**: any of them can be `null`, and **`text(...)`** writes `null` without quotes and anything else as a JSON string. That's how a test asks for C. Lindqvist's kind of profile.
- **`profile(id, name)`** is the short form, with ordinary values for the rest.
- **`noProfile(id)`**: the empty profile.

**The client.** In `ApiFootballClientTest`, add three tests:

```java
    @Test
    void fetchesTheIdsOfASquad() {
        provider.stubFor(get(urlPathEqualTo("/players/squads")).withQueryParam("team", equalTo("900001"))
                .willReturn(okJson(ProviderJson.squad(900001, 9000001, 9000002))));

        assertThat(client.fetchSquadIds(900001)).containsExactly(9000001L, 9000002L);
    }

    @Test
    void fetchesAProfile() {
        provider.stubFor(get(urlPathEqualTo("/players/profiles")).withQueryParam("player", equalTo("9000001"))
                .willReturn(okJson(ProviderJson.profile(9000001, "T. Marsh", "England", "1997-03-14", "Goalkeeper"))));

        ApiPlayer player = client.fetchProfile(9000001).orElseThrow();

        assertThat(player.name()).isEqualTo("T. Marsh");
        assertThat(player.birth().date()).isEqualTo("1997-03-14");
        assertThat(player.position()).isEqualTo("Goalkeeper");
    }

    @Test
    void anEmptyProfileIsEmptyNotAnError() {
        provider.stubFor(get(urlPathEqualTo("/players/profiles"))
                .willReturn(okJson(ProviderJson.noProfile(9000014))));

        assertThat(client.fetchProfile(9000014)).isEmpty();
    }
```

`9000001L`: the list holds `Long`s, so the expected values must be `long`s too (`9000001` on its own is an `int`, and wouldn't be equal).

**The two existing test classes.** Every import now asks for a squad too, and guide 03's tests don't stub one. `TeamImporterTest` and `TeamApiTest` are about teams, so give them an empty squad for every team, **unless a test says otherwise**. In both classes, add:

```java
    @BeforeEach
    void everySquadIsEmpty() {
        // These tests are about the team itself; SquadImportTest is about players
        provider.stubFor(get(urlPathEqualTo("/players/squads")).atPriority(10)
                .willReturn(okJson(ProviderJson.squad(0))));
    }
```

(with `import org.junit.jupiter.api.BeforeEach;`; in `TeamApiTest` the method is called `squadsAreEmptyUnlessATestSaysOtherwise`, without the comment). **`@BeforeEach`** runs before every test. **`.atPriority(10)`** is guide 02's WireMock priority, set from Java: this stub matches any squad request, but at priority 10, **any** stub a test adds itself (default 5) wins. A catch-all, like guide 02's `teams-unknown.json`.

In `TeamImporterTest`, `importTeam` returns an `ImportResult` now. In `createsANewTeam`, the last assertion flips, because a completed import **sets** `lastImportedAt`:

```java
        assertThat(team.getLastImportedAt()).isNotNull();
```

and `updatesTheSameRowWhenImportedAgain` reads the team from the result:

```java
        TeamView first = importer.importTeam(900022).team();
        assertThat(first.hudName()).isEqualTo("HAR");

        stubTeam(900022, ProviderJson.team(900022, "Harbour Athletic FC", "HAF", "Scotland", false));
        TeamView second = importer.importTeam(900022).team();

        assertThat(second.id()).isEqualTo(first.id());
```

In `TeamApiTest`, `postImportsATeamAndReturnsIt` now expects an `ImportResult`, with one player:

```java
    @Test
    void postImportsATeamAndReturnsIt() {
        stubTeam(900031, ProviderJson.team(900031, "Harbour Athletic", null, "Scotland", false));

        stubSquad(900031, 9000311);
        stubProfile(9000311, ProviderJson.profile(9000311, "A. MacLeod"));

        assertThat(mvc.post().uri("/imports/teams/900031"))
                .hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        {"team":{"apiId":900031,"name":"Harbour Athletic","hudName":"HAR","country":"Scotland",
                                 "national":false},
                         "squadSize":1,"profilesFetched":1,"skipped":0,"skippedReasons":[]}
                        """);
    }
```

`lastImportedAt` is left out: it's a timestamp, different every run, and `isLenientlyEqualTo` ignores fields you don't mention. Add two tests for the new endpoint:

```java
    @Test
    void listsATeamsPlayers() {
        stubTeam(900033, ProviderJson.team(900033, "Riverside Rovers", "RIV", "England", false));
        stubSquad(900033, 9000331);
        stubProfile(9000331, ProviderJson.profile(9000331, "T. Marsh", "England", "1997-03-14", "Goalkeeper"));
        assertThat(mvc.post().uri("/imports/teams/900033")).hasStatusOk();
        long teamId = teamIdOf(900033);

        assertThat(mvc.get().uri("/teams/{id}/players", teamId))
                .hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        [{"apiId":9000331,"name":"T. Marsh","nationality":"England","position":"GOALKEEPER","birthYear":1997}]
                        """);
    }

    @Test
    void playersOfATeamWeDoNotHaveIs404() {
        assertThat(mvc.get().uri("/teams/999999/players"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().isLenientlyEqualTo("""
                        {"status":404,"detail":"Team 999999 was not found"}
                        """);
    }
```

and the helpers they use:

```java
    @Autowired
    TeamRepository teams;

    private long teamIdOf(long apiId) {
        return teams.findByApiId(apiId).orElseThrow().getId();
    }

    private void stubSquad(long teamId, long... playerIds) {
        provider.stubFor(get(urlPathEqualTo("/players/squads")).withQueryParam("team", equalTo(String.valueOf(teamId)))
                .willReturn(okJson(ProviderJson.squad(teamId, playerIds))));
    }

    private void stubProfile(long id, String json) {
        provider.stubFor(get(urlPathEqualTo("/players/profiles")).withQueryParam("player", equalTo(String.valueOf(id)))
                .willReturn(okJson(json)));
    }
```

- **`mvc.get().uri("/teams/{id}/players", teamId)`** fills `{id}` in, like `RestClient` did in guide 01. Our team id depends on what other classes have stored (guide 03's shared database), so the test looks it up by the provider's id with `teamIdOf`, rather than guessing.
- **The expected JSON is an array with one player**, and `id` is left out for the same reason.

**The algorithm.** The interesting tests get a class of their own. Create `src/test/java/com/example/importer/team/SquadImportTest.java`:

```java
package com.example.importer.team;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.importer.ImporterIntegrationTest;
import com.example.importer.provider.ProviderHttpException;
import com.example.importer.provider.ProviderJson;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

@ImporterIntegrationTest
class SquadImportTest {

    @RegisterExtension
    static WireMockExtension provider = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        registry.add("api-football.base-url", provider::baseUrl);
    }

    @Autowired
    TeamImporter importer;

    @Autowired
    TeamService teamService;

    @Autowired
    TeamRepository teams;

    @Autowired
    PlayerRepository players;

    @Test
    void importsTheSquadAndLinksEveryPlayer() {
        stubTeam(900041);
        stubSquad(900041, 9000411, 9000412, 9000413);
        stubProfiles(9000411, 9000412, 9000413);

        ImportResult result = importer.importTeam(900041);

        assertThat(result.squadSize()).isEqualTo(3);
        assertThat(result.profilesFetched()).isEqualTo(3);
        assertThat(result.skipped()).isZero();
        assertThat(result.team().lastImportedAt()).isNotNull();
        assertThat(apiIdsOfPlayersIn(900041)).containsExactlyInAnyOrder(9000411L, 9000412L, 9000413L);
    }

    @Test
    void fetchesProfilesOnlyForPlayersWeDoNotHave() {
        stubTeam(900042);
        stubSquad(900042, 9000421, 9000422);
        stubProfiles(9000421, 9000422);
        importer.importTeam(900042);

        ImportResult again = importer.importTeam(900042);

        assertThat(again.profilesFetched()).isZero();
        provider.verify(exactly(1), getRequestedFor(urlPathEqualTo("/players/profiles"))
                .withQueryParam("player", equalTo("9000421")));
        assertThat(apiIdsOfPlayersIn(900042)).containsExactlyInAnyOrder(9000421L, 9000422L);
    }

    @Test
    void unlinksPlayersWhoLeftButKeepsThem() {
        stubTeam(900043);
        stubSquad(900043, 9000431, 9000432);
        stubProfiles(9000431, 9000432);
        importer.importTeam(900043);

        stubSquad(900043, 9000431);
        importer.importTeam(900043);

        assertThat(apiIdsOfPlayersIn(900043)).containsExactly(9000431L);
        assertThat(players.existsByApiId(9000432)).isTrue();
    }

    @Test
    void aPlayerInAClubAndANationalTeamIsStoredOnce() {
        stubTeam(900044);
        stubSquad(900044, 9000441, 9000442);
        stubTeam(900144);
        stubSquad(900144, 9000442, 9000443);
        stubProfiles(9000441, 9000442, 9000443);

        importer.importTeam(900044);
        ImportResult national = importer.importTeam(900144);

        assertThat(national.profilesFetched()).isEqualTo(1);
        assertThat(apiIdsOfPlayersIn(900044)).contains(9000442L);
        assertThat(apiIdsOfPlayersIn(900144)).contains(9000442L);
        provider.verify(exactly(1), getRequestedFor(urlPathEqualTo("/players/profiles"))
                .withQueryParam("player", equalTo("9000442")));
    }

    @Test
    void anEmptyProfileIsSkippedAndTheImportStillCompletes() {
        stubTeam(900045);
        stubSquad(900045, 9000451, 9000452);
        stubProfiles(9000451);
        stubProfile(9000452, ProviderJson.noProfile(9000452));

        ImportResult result = importer.importTeam(900045);

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.skippedReasons()).containsExactly("Player 9000452: the provider has no profile");
        assertThat(result.team().lastImportedAt()).isNotNull();
        assertThat(players.existsByApiId(9000452)).isFalse();
        assertThat(apiIdsOfPlayersIn(900045)).containsExactly(9000451L);
    }

    @Test
    void aProfileWithoutANameIsSkipped() {
        stubTeam(900046);
        stubSquad(900046, 9000461);
        stubProfile(9000461, ProviderJson.profile(9000461, null));

        ImportResult result = importer.importTeam(900046);

        assertThat(result.skippedReasons()).containsExactly("Player 9000461: profile has no name");
        assertThat(players.existsByApiId(9000461)).isFalse();
    }

    @Test
    void missingOrUnexpectedOptionalFieldsAreStoredAsNull() {
        stubTeam(900047);
        stubSquad(900047, 9000471);
        stubProfile(9000471, ProviderJson.profile(9000471, "C. Lindqvist", null, null, "Wing-back"));

        importer.importTeam(900047);

        Player player = players.findByApiId(9000471).orElseThrow();
        assertThat(player.getName()).isEqualTo("C. Lindqvist");
        assertThat(player.getNationality()).isNull();
        assertThat(player.getBirthYear()).isNull();
        assertThat(player.getPosition()).isNull();
    }

    @Test
    void storesNationalityPositionAndBirthYear() {
        stubTeam(900048);
        stubSquad(900048, 9000481);
        stubProfile(9000481, ProviderJson.profile(9000481, "T. Marsh", "England", "1997-03-14", "Goalkeeper"));

        importer.importTeam(900048);

        Player player = players.findByApiId(9000481).orElseThrow();
        assertThat(player.getNationality()).isEqualTo("England");
        assertThat(player.getPosition()).isEqualTo(Position.GOALKEEPER);
        assertThat(player.getBirthYear()).isEqualTo(1997);
    }

    @Test
    void aFailedImportKeepsPlayersLinkedSoFarButIsNotMarkedImported() {
        stubTeam(900049);
        stubSquad(900049, 9000491, 9000492);
        stubProfiles(9000491);
        provider.stubFor(get(urlPathEqualTo("/players/profiles")).withQueryParam("player", equalTo("9000492"))
                .willReturn(serverError()));

        assertThatExceptionOfType(ProviderHttpException.class).isThrownBy(() -> importer.importTeam(900049));

        assertThat(apiIdsOfPlayersIn(900049)).containsExactly(9000491L);
        assertThat(teams.findByApiId(900049).orElseThrow().getLastImportedAt()).isNull();
    }

    private List<Long> apiIdsOfPlayersIn(long teamApiId) {
        long teamId = teams.findByApiId(teamApiId).orElseThrow().getId();
        return teamService.playersOf(teamId).stream().map(PlayerView::apiId).toList();
    }

    private void stubTeam(long id) {
        provider.stubFor(get(urlPathEqualTo("/teams")).withQueryParam("id", equalTo(String.valueOf(id)))
                .willReturn(okJson(ProviderJson.team(id, "Team " + id, null, "England", false))));
    }

    private void stubSquad(long teamId, long... playerIds) {
        provider.stubFor(get(urlPathEqualTo("/players/squads")).withQueryParam("team", equalTo(String.valueOf(teamId)))
                .willReturn(okJson(ProviderJson.squad(teamId, playerIds))));
    }

    private void stubProfiles(long... playerIds) {
        for (long id : playerIds) {
            stubProfile(id, ProviderJson.profile(id, "Player " + id));
        }
    }

    private void stubProfile(long id, String json) {
        provider.stubFor(get(urlPathEqualTo("/players/profiles")).withQueryParam("player", equalTo(String.valueOf(id)))
                .willReturn(okJson(json)));
    }
}
```

One test per rule from the Concepts. What's new in them:

- **`provider.verify(exactly(1), getRequestedFor(...))`** is guide 01's `verify`, with a **count**: WireMock must have received **exactly one** request matching it, no more, no fewer. In `fetchesProfilesOnlyForPlayersWeDoNotHave`, that's the proof the second import didn't ask for 9000421 again. Asserting `profilesFetched` alone would only prove our own counter; `verify` asks the provider's side. (`exactly` is a static import from `WireMock`, like `get` and `okJson`.)
- **`containsExactlyInAnyOrder(...)`**: the list has exactly these elements, in any order. A team's players are a `Set`, so their order means nothing, and the test shouldn't depend on it. `containsExactly`, as in guide 02, checks the order too; with **one** element, order can't matter.
- **`unlinksPlayersWhoLeftButKeepsThem`** calls `stubSquad` twice for the same team: guide 03's rule, the most recently added stub wins. The second squad has one player fewer; afterwards the team has one player, and `existsByApiId` proves the other wasn't deleted.
- **`aFailedImportKeepsPlayersLinkedSoFarButIsNotMarkedImported`** is "link as you go" and "complete only when done" in one test. The second profile answers `500`, the import throws, and still the first player is linked, and `lastImportedAt` is `null`.
- **`missingOrUnexpectedOptionalFieldsAreStoredAsNull`** sends `"Wing-back"`. Watch the test log as it runs:

  ```
  WARN … com.example.importer.team.TeamService    : Player 9000471 has an unexpected position 'Wing-back': stored without a position
  ```

- **`apiIdsOfPlayersIn`** reads a team's players through `teamService.playersOf`, which runs its own read-only transaction. Reading `team.getPlayers()` directly in the test would fail: the test has no transaction, and the collection is lazy.
- **Ids 900041 to 900049, and 900144**, this class's own (guide 03's step 10). Players use ids made from the team's (9000411 for team 900041), so no two tests share a player unless they mean to, as `aPlayerInAClubAndANationalTeamIsStoredOnce` does.

Run everything: right-click `src/test/java` → **Run 'All Tests'**, or in `importer/`:

```bash
./mvnw test
```

(`.\mvnw test` in PowerShell.)

```
[INFO] Tests run: 39, Failures: 0, Errors: 0, Skipped: 0
```

In the test log, the first Spring context migrates the shared in-memory database to `version "2 - create player"`, and every later one says `Schema "PUBLIC" is up to date. No migration necessary.`, as in guide 03.

---

## Your turn

### Exercise 1: who pays for the shared players?

Start again from an empty database (stop the importer, delete `data/`, start it), and restart the fake provider too, so its request count starts from 0. This time, import the **national team first**: Scotland (900100), then Riverside Rovers (900001), then Harbour Athletic (900002).

Before you run anything, predict for each import: `squadSize`, `profilesFetched` and `skipped`. Then predict the profile count from step 13's `requests/count`, and the total number of requests the importer sent the provider.

Finally, import Harbour Athletic once more. How many requests does that cost, and why isn't it 2?

<details>
<summary>Solution</summary>

| Import | `squadSize` | `profilesFetched` | `skipped` | Requests |
| --- | --- | --- | --- | --- |
| Scotland | 5 | **5** (everyone is new) | 0 | 2 + 5 = 7 |
| Riverside Rovers | 6 | **5** (D. Brennan came with Scotland) | 0 | 2 + 5 = 7 |
| Harbour Athletic | 5 | **3** (MacLeod and Fraser came with Scotland) | 1 | 2 + 3 = 5 |

**13 profiles, 19 requests**, exactly as in step 13. The order changes **which import** pays for the three shared players, not how many requests they cost in total: each profile is fetched once, by whichever import meets the player first.

The real replies, from a run of this exercise:

```
{"team":{"id":1,"apiId":900100,"name":"Scotland","hudName":"SCO","country":"Scotland","national":true,"lastImportedAt":"2026-11-17T09:12:49.956738800Z"},"squadSize":5,"profilesFetched":5,"skipped":0,"skippedReasons":[]}
{"team":{"id":2,"apiId":900001,"name":"Riverside Rovers","hudName":"RIV","country":"England","national":false,"lastImportedAt":"2026-11-17T09:12:50.579891400Z"},"squadSize":6,"profilesFetched":5,"skipped":0,"skippedReasons":[]}
{"team":{"id":3,"apiId":900002,"name":"Harbour Athletic","hudName":"HAR","country":"Scotland","national":false,"lastImportedAt":"2026-11-17T09:12:50.788865300Z"},"squadSize":5,"profilesFetched":3,"skipped":1,"skippedReasons":["Player 9000014: the provider has no profile"]}
```

The profile count says `"count" : 13`. (Count every request with `-d '{"method":"GET"}'` and you get 19.)

**Re-importing Harbour Athletic costs 3 requests**, not 2:

```
{"team":{"id":3,"apiId":900002,…},"squadSize":5,"profilesFetched":1,"skipped":1,"skippedReasons":["Player 9000014: the provider has no profile"]}
```

M. Duffy's profile was empty, so no player was created, so as far as the importer knows, it still doesn't have him. Every import of Harbour Athletic asks again, until the provider has a profile for him. That's the empty profile **healing by itself**, and it costs 1 request each time. The profile count is now **14**, and the total 22.

</details>

### Exercise 2: a player who comes back

Step 14 showed J. Whitlow leaving Riverside Rovers and coming back, linked again without a profile request. Prove it in `SquadImportTest`, with a test called `aPlayerWhoComesBackIsLinkedAgainWithoutARequest`: import a team with two players, import it again with only one, then again with both. After the third import, both players must be linked, `profilesFetched` must be 0, and the provider must have been asked for the returning player's profile **exactly once** in total.

Which team id should the test use?

<details>
<summary>Solution</summary>

```java
    @Test
    void aPlayerWhoComesBackIsLinkedAgainWithoutARequest() {
        stubTeam(900040);
        stubSquad(900040, 9000401, 9000402);
        stubProfiles(9000401, 9000402);
        importer.importTeam(900040);

        stubSquad(900040, 9000401);
        importer.importTeam(900040);
        assertThat(apiIdsOfPlayersIn(900040)).containsExactly(9000401L);

        stubSquad(900040, 9000401, 9000402);
        ImportResult back = importer.importTeam(900040);

        assertThat(back.profilesFetched()).isZero();
        assertThat(apiIdsOfPlayersIn(900040)).containsExactlyInAnyOrder(9000401L, 9000402L);
        provider.verify(exactly(1), getRequestedFor(urlPathEqualTo("/players/profiles"))
                .withQueryParam("player", equalTo("9000402")));
    }
```

- **`stubSquad` three times** for the same team: the most recent stub wins each time (guide 03), so each import sees the squad you've just set up.
- **The middle assertion** checks the player really had left. Without it, the test would also pass if unlinking were broken and 9000402 had never gone.
- **`verify(exactly(1), …)`** is the important line. `profilesFetched` is our own counter; `verify` checks what the provider actually received, across all three imports.
- **Team 900040**: not used by any other test class (`SquadImportTest` already uses 900041 to 900049 and 900144; `TeamImporterTest` 900021 to 900023 and 900120; `TeamApiTest` 900031 to 900039), and no later guide's tests use it either. A team id another class has used would bring that class's players with it, from the shared database.

No new imports are needed: the class already has them all. With this test kept, `./mvnw test` runs **40**, all passing.

</details>

### Exercise 3: a transfer between clubs

J. Whitlow leaves Riverside Rovers (step 14's change) **and signs for Harbour Athletic**. Make the second half happen in the fake provider:

1. Add `fake-provider/__files/squads-900002-after-signing.json`: Harbour Athletic's squad with J. Whitlow (9000005, age 21, number 14, Midfielder) added at the end.
2. Add `fake-provider/changes/squad-900002-signing.json`, which serves it for team 900002, the same way as step 14's change.

Switch **both** changes on. Then predict, and check:

- Import **only Harbour Athletic**. What does the import say? Which teams is J. Whitlow in now?
- Then import Riverside Rovers. Which teams is he in now?

Switch the changes off when you've finished.

<details>
<summary>Solution</summary>

`fake-provider/__files/squads-900002-after-signing.json`, on one line:

```json
{"get":"players/squads","parameters":{"team":"900002"},"errors":[],"results":1,"paging":{"current":1,"total":1},"response":[{"team":{"id":900002,"name":"Harbour Athletic","logo":"https://media.api-sports.io/football/teams/900002.png"},"players":[{"id":9000011,"name":"A. MacLeod","age":31,"number":1,"position":"Goalkeeper","photo":"https://media.api-sports.io/football/players/9000011.png"},{"id":9000012,"name":"R. Fraser","age":28,"number":3,"position":"Defender","photo":"https://media.api-sports.io/football/players/9000012.png"},{"id":9000013,"name":"C. Lindqvist","age":null,"number":6,"position":"Midfielder","photo":"https://media.api-sports.io/football/players/9000013.png"},{"id":9000014,"name":"M. Duffy","age":26,"number":7,"position":"Attacker","photo":"https://media.api-sports.io/football/players/9000014.png"},{"id":9000015,"name":"E. Byrne","age":22,"number":10,"position":"Midfielder","photo":"https://media.api-sports.io/football/players/9000015.png"},{"id":9000005,"name":"J. Whitlow","age":21,"number":14,"position":"Midfielder","photo":"https://media.api-sports.io/football/players/9000005.png"}]}]}
```

`fake-provider/changes/squad-900002-signing.json`, a copy of step 14's with the team and file changed:

```json
{
  "priority": 1,
  "request": {
    "method": "GET",
    "urlPath": "/players/squads",
    "queryParameters": { "team": { "equalTo": "900002" } }
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
    "bodyFileName": "squads-900002-after-signing.json"
  }
}
```

From `fake-provider/` (your team ids are in `GET /teams`; these are step 13's):

```bash
curl -X POST http://localhost:8089/__admin/mappings -d @changes/squad-900001-player-left.json
curl -X POST http://localhost:8089/__admin/mappings -d @changes/squad-900002-signing.json
curl -X POST http://localhost:8080/imports/teams/900002
curl http://localhost:8080/teams/2/players
curl http://localhost:8080/teams/1/players
curl -X POST http://localhost:8080/imports/teams/900001
curl http://localhost:8080/teams/1/players
curl -X POST http://localhost:8089/__admin/mappings/reset
```

**Harbour Athletic's import** (shortened):

```
{"team":{…,"apiId":900002,"name":"Harbour Athletic",…},"squadSize":6,"profilesFetched":1,"skipped":1,"skippedReasons":["Player 9000014: the provider has no profile"]}
```

Six in the squad, and **one** profile fetched: M. Duffy's, again (Exercise 1). J. Whitlow cost nothing: the importer already had him, from Riverside Rovers. Harbour Athletic's players now include J. Whitlow, **and so do Riverside Rovers'**. He's in both, because Riverside Rovers hasn't been imported since he left. Our copy of a team's squad is only as fresh as that team's last import.

**Riverside Rovers' import** then says `"squadSize":5,"profilesFetched":0`, and he's gone from Riverside Rovers. He's in Harbour Athletic only, and still one row in `player`. The whole transfer cost 4 requests (two teams, two squads) plus M. Duffy's retry, and no request for J. Whitlow at all.

(This was run on a database where the teams had been imported in a different order, so the team and player ids there were different. The counts and the lists are as above.)

</details>

### Exercise 4: the price of fresh data

No code this time. The provider corrects C. Lindqvist's profile: it now has `"nationality":"Sweden"` and a date of birth.

1. You re-import Harbour Athletic. What changes for C. Lindqvist in our database, and what does the import cost?
2. You need the correction. With the code as it is, how do you get it in, and what does that cost?
3. A colleague suggests fetching every profile on every import, "so the data is always fresh". For Harbour Athletic, what would a re-import cost instead of today's? For a club of 25 players, and the free plan's 100 requests a day?
4. Suggest something in between, and say what it would need in the database.

<details>
<summary>Solution</summary>

1. **Nothing changes for him.** He's already a `Player`, so the import links him (he's already linked) and moves on. No request for his profile, and no `update` method on `Player` that could change him anyway. The import costs 2 + 1 = 3 requests (team, squad, M. Duffy's retry). His `nationality` and `birthYear` stay `null`.
2. **Delete him**, links first: his rows in `team_player`, then his row in `player` (the foreign keys refuse the other order). The next import of Harbour Athletic then finds a squad member it doesn't have, and fetches his profile: **1 request**. There's no endpoint for this; it's a job for someone with access to the database.
3. Harbour Athletic: 2 + 5 = **7** requests per re-import instead of 3. A club of 25: **27** instead of 2. With 100 a day, that's about **3** re-imports a day instead of about **50**, and every first import of a new team competes for the same 100. Most of those 25 profiles would come back unchanged, so most of the requests would buy nothing.
4. **Refresh profiles older than some age**, say 30 days, a few at a time, with whatever requests are left at the end of a day. It would need to know **when** each profile was fetched: a `profile_fetched_at` column on `player` (a new migration, V3 or later), and an update method on `Player`, which this guide deliberately left out. The import itself wouldn't change: it still fetches only what's missing, and the refresh is a separate, lower-priority job.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 39, Failures: 0, Errors: 0, Skipped: 0
```

(23 from guide 03, plus 3 in `ApiFootballClientTest`, 2 in `PositionTest`, 9 in `SquadImportTest` and 2 in `TeamApiTest`. With Exercise 2's test, 40.)

With a fresh `data/` folder, importing 900001, 900002 and 900100 in that order gives `profilesFetched` 6, 5 and 2, the fake provider's profile count is **13**, and importing 900001 again leaves it at 13. `GET /teams/3/players` lists Scotland's five players, sorted by name, starting with A. MacLeod.

New files in the importer:

```
src/main/java/com/example/importer/
├── provider/
│   ├── ApiPlayer.java
│   ├── ProfileItem.java
│   ├── SquadItem.java
│   └── SquadMember.java
├── team/
│   ├── ImportResult.java
│   ├── Player.java
│   ├── PlayerRepository.java
│   ├── PlayerView.java
│   ├── Position.java
│   └── TeamView.java
└── web/
    └── NotFoundException.java
src/main/resources/
└── db/migration/
    └── V2__create_player.sql
src/test/java/com/example/importer/
└── team/
    ├── PositionTest.java
    └── SquadImportTest.java
```

Modified in the importer: `provider/ApiFootball.java`, `provider/ApiFootballClient.java`, `team/Team.java`, `team/TeamService.java`, `team/TeamImporter.java`, `team/TeamController.java` (its nested `TeamView` moved out), `web/ApiExceptionHandler.java`; tests `provider/ApiFootballClientTest.java`, `provider/ProviderJson.java`, `team/TeamApiTest.java`, `team/TeamImporterTest.java`. No changes to `pom.xml` or `application.properties`.

New files in the fake provider:

```
fake-provider/
├── changes/
│   └── squad-900001-player-left.json
├── mappings/
│   ├── profiles.json
│   └── squads.json
└── __files/
    ├── profiles-9000001.json … profiles-9000006.json
    ├── profiles-9000011.json … profiles-9000015.json
    ├── profiles-9000101.json
    ├── profiles-9000102.json
    ├── squads-900001.json
    ├── squads-900001-after-transfer.json
    ├── squads-900002.json
    └── squads-900100.json
```

---

## Common errors

**`LazyInitializationException: Cannot lazily initialize collection of role 'com.example.importer.team.Team.players' with key '2' (no session)`**
`GET /teams/{id}/players` fails with a `500`, and `TeamApiTest.listsATeamsPlayers` fails with:

```
jakarta.servlet.ServletException: Request processing failed: org.hibernate.LazyInitializationException: Cannot lazily initialize collection of role 'com.example.importer.team.Team.players' with key '2' (no session)
```

Something touched a team's `players` with no transaction open. This one was made by removing `@Transactional(readOnly = true)` from `TeamService.playersOf`: without it, the repository's `findById` runs in a transaction of its own that's over by the time `getPlayers()` is read, and with `open-in-view=false` there's nothing else keeping the session open. The same happens if a service hands the controller `Team` entities and the controller reads their players. Keep the annotation, and turn entities into `PlayerView`s **inside** it, as step 9 does.

**The import answers `502`, `API-Football answered HTTP 500`, and the fake provider's reply is an HTML page**
A squad lists a player with no profile file (or a team has no squad file). WireMock's templated `bodyFileName` points at a file that isn't there, and WireMock answers:

```
HTTP/1.1 500 Server Error
```

with an HTML page titled `Error 500 java.io.FileNotFoundException: …\fake-provider\.\__files\profiles-9999999.json (The system cannot find the file specified)`. The importer reports it as any other provider `500`. Check the file name in `__files/` matches the id exactly. Notice what the failure left behind, too: the team, and every player created before the missing one, are stored and linked, and the team's `lastImportedAt` didn't change. Add the file, import again, and only the missing profiles are fetched.

**`Error parsing JSON` from `__admin/requests/count`**
In Windows PowerShell, `-d '{"method":"GET","urlPath":"/players/profiles"}'` reaches WireMock as `{method:GET,urlPath:/players/profiles}`, and it answers:

```
"title" : "Error parsing JSON",
"detail" : "Unexpected character ('m' (code 109)): was expecting double-quote to start field name\n at [Source: (String)\"{method:GET,urlPath:/players/profiles}\"; line: 1, column: 2]"
```

PowerShell 5.1 strips double quotes from arguments it passes to programs like `curl.exe`. Put a backslash before each one (`'{\"method\":\"GET\", …}'`), as in step 13, or use Git Bash.

**`Migration checksum mismatch for migration version 1`**
You added the player tables to `V1__create_team.sql` instead of a new `V2__create_player.sql`, on a database where V1 had already run. Guide 03's Common errors explains the message. Put V1 back as it was, and put the new tables in V2.

---

## Recap

- A team's players come from **two** endpoints: the **squad** (one request per team) says **who**; each **profile** (one request per player) says **what**. We take **only ids** from the squad, and create players **only from profiles**.
- **Player ↔ Team is many-to-many**, through the `team_player` join table. **`Team` owns it** (`@ManyToMany` + `@JoinTable`); `Player` has no collection back. A player in a club and a national team is **one row, two links**.
- `Player` keeps the same two ids as `Team` (**ours** and the unique **`apiId`**), stores `position` with **`@Enumerated(EnumType.STRING)`**, and leaves `nationality`, `position` and `birthYear` **`null`** when the provider has nothing usable.
- **The squad is the truth.** Players no longer in it are **unlinked straight away, never deleted**, so they cost nothing if they turn up again.
- **Fetch only what's missing.** A player we already have is **linked**, with no request. A first import of 25 players costs 27 requests; a re-import costs **2**.
- **Link as you go.** A new player is created **and** linked in one short transaction, so a failure part-way keeps everything done so far.
- **An empty profile counts as done**: no player created, a skipped reason recorded, and the next import retries it for 1 request.
- **`lastImportedAt` is set only when every member is done.** A failed import leaves it alone, and the next attempt fetches only what's still missing: **an import that finishes another day**.
- **Profiles are never refreshed.** That keeps re-imports cheap; the price is that a player's details can go stale (who plays where can't: the squad is fetched every time).
- **`TeamImporter` has no transaction**; every database write is its own short `TeamService` transaction, and no transaction ever waits on the network.
- WireMock can **template a file name** (`squads-{{request.query.team}}.json`), **count** what it received (`__admin/requests/count`), and a test can demand **`verify(exactly(1), …)`**.

### Interview angle

<details>
<summary>"How would you model players who belong to several teams in a relational database?"</summary>

As a many-to-many relationship through a join table, `team_player(team_id, player_id)`, with a foreign key to each side and the pair as the primary key, so a link can't be duplicated. In JPA, one entity owns the relationship with `@ManyToMany` and `@JoinTable`, and changes to its collection are what's written; the other side can have a `mappedBy` collection, or nothing at all if nothing needs to navigate that way. If the link ever needed data of its own, such as a shirt number or a joining date, I'd turn the join table into an entity with two many-to-one relationships instead.

</details>

<details>
<summary>"You sync data from a rate-limited API. How do you keep the number of requests down?"</summary>

Only ask for what you don't already have. Fetch the cheap list (here a squad, one request) every time, compare it with what you've stored by the provider's id, and only fetch the expensive details (one request per item) for new entries. Re-syncing an unchanged list then costs one or two requests instead of one per item. The trade-off is staleness: details you've stored aren't refreshed, so you decide which fields can go stale and, if some can't, refresh them separately, by age, with spare quota.

</details>

<details>
<summary>"A long import fails halfway through. What should happen to the work it has done?"</summary>

It should be kept, as long as each piece is correct on its own. Write each piece in its own short transaction as you go, rather than one transaction around the whole import, which would hold a connection for minutes and roll back everything on the last failure, including work you've paid for in API requests. Then make "complete" a separate, final step (here, setting `lastImportedAt`), so a half-finished import is visible as such. If the import also skips what it already has, running it again simply continues: resuming needs no extra code.

</details>

<details>
<summary>"Why might you unlink a record rather than delete it, when it disappears from the source?"</summary>

Because disappearing from one list doesn't mean it stopped existing. A player who leaves a squad is still a player: they may be in another team's squad, or join another one later. Deleting them would break their other links, and getting them back would cost another request to the provider. Removing only the relationship row records the fact that actually changed, and keeps the entity for anything else that refers to it.

</details>

<details>
<summary>"What causes a `LazyInitializationException`, and how do you avoid it?"</summary>

A lazy association, such as a `@ManyToMany` collection, is loaded the first time it's accessed, which needs an open persistence context. If it's first touched after the transaction that loaded the entity has ended, with open-in-view turned off, Hibernate can't load it and throws. The fix is to load and map everything a response needs inside the service's transaction, for example by building DTOs there, or by fetching the association explicitly with a join fetch or an entity graph. Turning open-in-view back on hides the problem, at the cost of database access during view rendering.

</details>

### Further reading

- [Jakarta Persistence 3.2 specification](https://jakarta.ee/specifications/persistence/3.2/), for `@ManyToMany`, `@JoinTable` and owning sides
- [Hibernate ORM documentation](https://hibernate.org/orm/documentation/): the user guide's chapter on associations covers many-to-many in depth
- [Spring Data JPA reference: query methods](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html), for `existsBy…`
- [WireMock: response templating](https://wiremock.org/docs/response-templating/), including templated `bodyFileName`
- [WireMock: verifying requests](https://wiremock.org/docs/verifying/), for `verify(exactly(n), …)` and the request journal
- [API-Football documentation](https://www.api-football.com/documentation-v3), for the `players/squads` and `players/profiles` endpoints

**Simplification to notice:** importing a team still happens while your `curl` waits. Riverside Rovers takes a moment on the fake provider; a real club of 25 players is 27 requests, and once guide 06 respects the 10-a-minute limit, that's several minutes. No HTTP request should wait that long.

**Next:** [05: Imports in the background](05-imports-in-the-background.md). The same `POST` answers `202 Accepted` straight away, the import runs in the background, and an import record tells you how it's going.
