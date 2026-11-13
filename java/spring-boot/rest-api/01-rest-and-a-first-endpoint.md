# 01: REST and a first endpoint

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](README.md) for version notes.

## What you'll learn

- What a **REST API** is, and how it differs from the server-rendered pages you built in the todo app
- How to design **resources and URLs**: nouns, collections, ids and sub-resources
- What the **HTTP methods** mean, and which ones are **safe** and **idempotent**
- Which **status codes** an API uses, and why they are part of the contract
- How a Java object becomes **JSON**, and why every response goes through a **response DTO** (a record) instead of an entity
- How to return a **`404` as a `ProblemDetail`** ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)), so errors have one predictable shape
- How to test an API with **`MockMvcTester`**, and how one meta-annotation keeps the test setup in one place
- How to use **Swagger UI** to look at and call your API from a browser
- What **HATEOAS** is, and why this series doesn't use it

By the end you'll have a read-only API that lists venues and events from a seeded database, returns one event by id, and answers `404` with a `ProblemDetail` when the id doesn't exist.

---

## Prerequisites for this guide

This guide assumes you've done the [todo app](../todo-app/README.md) series, or know the same things:

- **Spring MVC controllers:** `@GetMapping`, `@PathVariable`, and how a request reaches a controller method.
- **Spring Data JPA:** entities, `JpaRepository`, `@ManyToOne` and lazy loading.
- **Flyway:** versioned migrations in `db/migration`, with Hibernate set to `validate`.
- **Tests:** JUnit and AssertJ.

You don't need to know anything about APIs yet. That's what this guide starts with.

---

## Concepts

### Pages for people, data for programs

In the todo app, a browser asked for a URL and the server sent back a **page**. A controller put some objects in the model, Thymeleaf turned them into HTML, and the browser drew it. When someone submitted a form, the controller saved the data and **redirected** the browser to another page. The server was in charge of what the user saw, and the user was a person.

A **REST API** is the other kind of back end. Its callers are **programs**: a website's server, a mobile app, another company's system, a background job. They don't want pages. They want **data**, in a format they can read, and an answer they can act on without a person looking at it:

| | Todo app (server-rendered) | Tickets API (REST) |
| --- | --- | --- |
| Caller | A person, through a browser | A program |
| Response body | An HTML page | JSON data |
| Who decides what's shown | The server (templates) | The client |
| After a change | Redirect to another page | A status code and the new data |
| How errors are shown | An error page, or a message in the form | A status code and a machine-readable error body |
| Who the caller is | A session cookie, after a login form | An access token on every request (guide 05) |

The biggest change in mindset: **in an API, the HTTP status code is part of the answer.** A browser shows a page whatever the status. A program checks the status first. `200` means "here's what you asked for", `404` means "that thing doesn't exist", and `400` means "your request was wrong, here's why". If an API sends `200` with `{"error": "not found"}` in the body, every client has to read the body to find out it failed. That's a bug in the API, not in the client.

### What makes it "REST"

REST (*representational state transfer*) is the name Roy Fielding gave, in his 2000 PhD thesis, to the style of the web itself. In day-to-day use, "a REST API" means an HTTP API built on a few ideas:

- **Resources.** The API exposes *things*, such as venues, events and bookings, each with its own URL.
- **Representations.** The client never gets the thing itself, only a **representation** of it: here, a JSON document. The same event could also be represented as XML or HTML. The database row stays on the server.
- **A uniform interface.** Every resource is used with the same small set of HTTP methods (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`), whose meaning is defined by the HTTP standard ([RFC 9110](https://www.rfc-editor.org/rfc/rfc9110)), not by your API.
- **Statelessness.** Each request carries everything the server needs to handle it. There's no "current page" or session on the server between requests.

### Resources and URLs

Designing an API starts with naming its resources. This series uses the usual conventions:

| URL | What it identifies |
| --- | --- |
| `/events` | The **collection** of all events |
| `/events/2` | One event, with id `2` |
| `/events/2/ticket-types` | The ticket types that belong to event 2: a **sub-resource** (guide 02) |
| `/venues`, `/venues/1` | The same pattern for venues |

- **Nouns, not verbs.** The URL names a thing; the method says what to do with it. `GET /events/2` and `DELETE /events/2`, never `/getEvent?id=2` or `/deleteEvent/2`.
- **Plural collection names.** `/events` is the collection, `/events/2` is a member of it.
- **Lower case, hyphens between words.** `/ticket-types`, not `/ticketTypes`.
- **Query parameters filter or shape a collection,** they don't identify a resource: `/events?city=Leeds` (guide 03).

### Methods: safe and idempotent

HTTP defines what each method means, and two properties that clients, caches and proxies rely on:

- **Safe**: the method only *reads*. Calling it never changes anything on the server, so a browser, crawler or cache can call it freely.
- **Idempotent**: calling it twice has the same effect on the server as calling it once. If a request times out and the client doesn't know whether it arrived, it can safely **send it again**.

| Method | Meaning | Safe | Idempotent | In this series |
| --- | --- | --- | --- | --- |
| `GET` | Read a resource or collection | Yes | Yes | This guide |
| `HEAD` | Like `GET`, but headers only | Yes | Yes | Spring handles it for you |
| `POST` | Create a resource in a collection, or perform an action | No | **No** | Guide 02 |
| `PUT` | Replace a resource with the one sent | No | Yes | Guide 02 |
| `PATCH` | Change part of a resource | No | Not necessarily | Guide 02 |
| `DELETE` | Delete a resource | No | Yes | Guide 02 |
| `OPTIONS` | Ask what's allowed (used by browsers for CORS) | Yes | Yes | Guide 08 |

`DELETE` is idempotent even though the second call gets a `404`: idempotency is about the **effect** on the server (the event is gone either way), not the response. `POST` is the odd one out. Sending "book two tickets" twice books four. Guide 07 shows how an **idempotency key** makes a `POST` safe to retry.

### Status codes

Status codes come in five classes. The first digit tells a client most of what it needs to know:

| Class | Means | Codes you'll meet in this series |
| --- | --- | --- |
| `2xx` | It worked | `200 OK`, `201 Created` (02), `204 No Content` (02) |
| `3xx` | Look elsewhere, or use what you have | `304 Not Modified` (03) |
| `4xx` | **The client** got something wrong | `400 Bad Request` (02), `401` and `403` (05), `404 Not Found` (here), `405 Method Not Allowed`, `409 Conflict` (06), `412`, `428` (07), `415 Unsupported Media Type` (02), `422` (07), `429 Too Many Requests` (08) |
| `5xx` | **The server** got something wrong | `500 Internal Server Error` |

The split between `4xx` and `5xx` matters. A `4xx` says "don't send that again unchanged". A `5xx` says "it might work if you try again later". Sending a `500` for bad input tells clients to retry something that will never work.

### JSON, and response DTOs

The API speaks **JSON**. Spring Boot 4 uses **Jackson 3** to turn the object your controller returns into the response body, and to read request bodies into objects in guide 02. A Java record turns into a JSON object with one property per component, in the same order:

```json
{"id":2,"title":"The Paper Lanterns: Live","category":"MUSIC","venue":{"id":1,"name":"Riverside Hall","city":"London"},"startsAt":"2030-01-18T19:30:00Z","description":"Indie folk five-piece on their first UK tour."}
```

An `Instant` becomes ISO-8601 text in UTC (the `Z` at the end), and an enum becomes its name. No configuration is needed for either.

The tempting shortcut is to return the JPA **entity** straight from the controller. Don't. From the very first endpoint, every response in this series goes through a **response DTO** (data transfer object): a record with exactly the fields the client should see, built from the entity. The reasons:

- **The API is a contract; the entity is your database.** Clients write code against your JSON. If the JSON *is* the entity, renaming a column or adding a field changes the API, and someone else's app breaks. With a DTO, the two can change separately.
- **Entities have fields clients shouldn't see.** `Event` has a `version` column for Hibernate. In guide 02 a ticket type has a `sold` counter, and the API shows `available` instead. In guide 06 a booking has the customer's id. A DTO lists what's public; everything else stays private by default.
- **Lazy loading and JSON don't mix.** An event's venue is a lazy Hibernate proxy. Serialising an entity either fails or triggers surprise database queries while the response is being written. A DTO is built inside your code, where you control what's loaded.
- **Relationships can loop.** Venue → events → venue → … sends Jackson round in circles. A DTO is a tree, not a graph.

Records make DTOs nearly free: one line for the fields, and a small `from(entity)` method to build one.

### One shape for every error

When something goes wrong, a client needs to know **what** went wrong in a form a program can read. Every API invents its own error format unless you choose a standard one. This series uses **Problem Details for HTTP APIs** ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)), which Spring supports as the `ProblemDetail` class:

```json
{"detail":"Event 999 was not found","instance":"/events/999","status":404,"title":"Not Found"}
```

| Member | Meaning |
| --- | --- |
| `type` | A URI identifying the kind of problem. The default, `about:blank`, means "just the status code"; Spring doesn't write it into the JSON |
| `title` | A short, human-readable summary of that kind of problem, usually the status's reason phrase |
| `status` | The HTTP status code, repeated in the body |
| `detail` | What went wrong **this time** |
| `instance` | Which request it happened to (Spring fills in the path) |

The response's `Content-Type` is `application/problem+json`, so a client can tell an error body from a normal one before parsing it. RFC 9457 also allows extra members; guide 02 adds an `errors` member listing the fields that failed validation.

(Spring writes a `ProblemDetail`'s members in alphabetical order, so `detail` comes first. Order doesn't matter in JSON objects.)

> **Sidebar: HATEOAS, and why this series doesn't use it.** Fielding's REST includes one more constraint: *hypermedia as the engine of application state* (HATEOAS). Responses carry **links** to related resources and to the actions that are possible next, so a client could navigate the API like a person clicks around a website, knowing only the first URL. A HATEOAS event might include `"_links": {"self": {"href": "/events/2"}, "ticket-types": {"href": "/events/2/ticket-types"}}` in the HAL format, and Spring has a whole project for it, [Spring HATEOAS](https://spring.io/projects/spring-hateoas). In practice, most real APIs, including most of those called "REST", don't do this: client developers read the documentation and build URLs themselves, and links in every response add size and complexity without changing that. This series follows common practice. It describes the API with an **OpenAPI** document instead (guide 04), which tools and people can read, and keeps the JSON plain.

---

## Steps

### 1. Generate the project

Use the website this time, even if you have IntelliJ Ultimate. IntelliJ's **New Project → Spring Boot** wizard talks to the same Spring Initializr service, but it also uses the **Name** field as the project folder's name, and in this project the name and the folder differ (see below).

Go to [start.spring.io](https://start.spring.io) and choose:

| Setting | Value |
| --- | --- |
| Project | **Maven** |
| Language | **Java** |
| Spring Boot | **4.1.2** (or the newest 4.1.x that isn't SNAPSHOT/M/RC) |
| Group | `com.example` |
| Artifact | `api` |
| Name | `tickets` |
| Description | `Event tickets REST API` |
| Package name | `com.example.tickets` |
| Packaging | **Jar** |
| Configuration | **Properties** |
| Java | **25** |

**Check the Name and the Package name.** The site fills them in from the other fields as you type. **Name** is what the generated classes are called after: `tickets` gives you `TicketsApplication`, while leaving it as `api` would give you `ApiApplication`. The folder and the Maven artifact are still called `api`, because this is the API project; guide 05 adds a second project called `auth-server` next to it.

Under **Dependencies**, add four:

| Dependency | Why |
| --- | --- |
| **Spring Web** | `@RestController` and the embedded Tomcat server (it adds `spring-boot-starter-webmvc`) |
| **Spring Data JPA** | Entities and repositories |
| **Flyway Migration** | Versioned SQL migrations |
| **H2 Database** | An embedded database, stored in a file |

You'll add SpringDoc (for Swagger UI) by hand in step 2, and validation in guide 02, so you can see what each one changes.

Click **Explore** first and read the generated `pom.xml`. Then click **Generate**, and unzip the download so the project sits at:

```
tutorials/workspace/rest-api/api/
```

(The zip contains an `api` folder. Make sure you don't end up with `api/api/`.)

Open it in IntelliJ with **File → Open…**, choose the `api` folder (the one containing `pom.xml`), and trust the project. Check **File → Project Structure → Project → SDK** is set to a Java 25 JDK.

**Delete the generated test** `src/test/java/com/example/tickets/TicketsApplicationTests.java`. It would start the application against the real file database in `./data`. Every test in this series uses an in-memory database instead, set up in step 13.

### 2. Add SpringDoc for Swagger UI

**SpringDoc** reads your controllers at runtime and produces an **OpenAPI** document describing the API, plus **Swagger UI**, a web page that displays that document and lets you call each endpoint from the browser. Guide 04 teaches it properly. Here you just want the tool.

SpringDoc isn't a Spring project, so it needs a version. Open `pom.xml` and add a property next to `java.version`:

```xml
<properties>
    <java.version>25</java.version>
    <springdoc.version>3.2.0</springdoc.version>
</properties>
```

And add the dependency, after the Flyway one:

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>${springdoc.version}</version>
</dependency>
```

SpringDoc **3.x** is the line for Spring Boot 4; the 2.x line you'll find in older articles is for Boot 3. Reload Maven in IntelliJ (the **M** icon that appears, or **Maven tool window → Reload All Maven Projects**).

Your `<dependencies>` section should now be:

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webmvc</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-flyway</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springdoc</groupId>
        <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        <version>${springdoc.version}</version>
    </dependency>
    <dependency>
        <groupId>com.h2database</groupId>
        <artifactId>h2</artifactId>
        <scope>runtime</scope>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webmvc-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-flyway-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

In Spring Boot 4, each starter has a matching `-test` starter, and start.spring.io adds them for you. `spring-boot-starter-webmvc-test` is the one that brings `MockMvcTester`. If your generated `pom.xml` has anything else (such as `spring-boot-h2console`), you can delete it: this series doesn't use the H2 console.

### 3. Configure the application

Replace `src/main/resources/application.properties` with:

```properties
spring.application.name=tickets

# H2 database stored in ./data/tickets.mv.db
spring.datasource.url=jdbc:h2:file:./data/tickets
spring.datasource.username=sa

# Flyway owns the schema; Hibernate only checks it matches the entities
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
```

- **A file database** in `./data`, inside the project folder, so your data survives a restart. `username=sa` is H2's usual default user.
- **`ddl-auto=validate`**: Flyway creates the tables; Hibernate only checks at start-up that your entities match them, and refuses to start if they don't.
- **`open-in-view=false`**: Spring Boot normally keeps the database session open until the response has been written, so lazy relationships can load at any point, even while JSON is being produced. Turning it off means lazy loading outside a transaction **fails loudly** instead of quietly running extra queries. Step 6 shows how to load what you need up front.

Add `data/` to the end of the project's `.gitignore`, so the database file is never committed:

```
### Local H2 database ###
data/
```

### 4. The schema and the seed data

Create `src/main/resources/db/migration/V1__venues_and_events.sql` (two underscores after `V1`):

```sql
create table venue (
    id   bigint generated by default as identity primary key,
    name varchar(100) not null,
    city varchar(100) not null
);

create table event (
    id          bigint generated by default as identity primary key,
    title       varchar(200)             not null,
    category    varchar(20)              not null,
    venue_id    bigint                   not null references venue (id),
    starts_at   timestamp with time zone not null,
    description varchar(2000),
    version     bigint                   not null default 0
);

-- Fictional venues: ids 1 to 6, in this order
insert into venue (name, city) values
    ('Riverside Hall', 'London'),
    ('The Lantern Rooms', 'London'),
    ('Northern Works', 'Manchester'),
    ('The Mill Theatre', 'Leeds'),
    ('Harbourside Arena', 'Bristol'),
    ('Castle Wynd Playhouse', 'Edinburgh');

-- Fictional events. Event 1 is in the past (guide 06 uses it); the rest are in 2030.
insert into event (title, category, venue_id, starts_at, description) values
    ('Bonfire Night Comedy Special', 'COMEDY', 2, '2025-11-05 20:00:00+00', 'Five comics, one night, lots of sparklers.'),
    ('The Paper Lanterns: Live', 'MUSIC', 1, '2030-01-18 19:30:00+00', 'Indie folk five-piece on their first UK tour.'),
    ('Winter Open Mic', 'COMEDY', 2, '2030-01-25 20:00:00+00', 'New acts try their first five minutes.'),
    ('Hamlet, Abridged', 'THEATRE', 4, '2030-02-02 19:00:00+00', 'Shakespeare in ninety minutes, with a cast of four.'),
    ('Manchester Indoor Netball Final', 'SPORT', 3, '2030-02-09 14:00:00+00', 'The regional league decider.'),
    ('Brass at the Works', 'MUSIC', 3, '2030-02-14 19:30:00+00', 'A Valentine''s night of brass band classics.'),
    ('The Quiet Detective', 'THEATRE', 6, '2030-02-21 19:30:00+00', 'A whodunnit where nobody is allowed to speak.'),
    ('Harbour Lights Orchestra', 'MUSIC', 5, '2030-03-01 19:00:00+00', 'Film scores from the last fifty years.'),
    ('Stand-Up for Beginners', 'COMEDY', 4, '2030-03-08 20:00:00+00', 'Graduates of a ten-week comedy course.'),
    ('Riverside Jazz Club', 'MUSIC', 1, '2030-03-15 20:30:00+00', 'A late-night quartet and a very small stage.'),
    ('Table Tennis Masters', 'SPORT', 5, '2030-03-22 13:00:00+00', 'Exhibition matches and a doubles knockout.'),
    ('The Glass Orchard', 'THEATRE', 2, '2030-03-29 19:30:00+00', 'A new play about three sisters and an apple farm.'),
    ('Northern Soul Weekender', 'MUSIC', 3, '2030-04-05 21:00:00+00', 'All-nighter with three DJs.'),
    ('Improv Lab', 'COMEDY', 6, '2030-04-12 20:00:00+00', 'Fully improvised, built from audience suggestions.'),
    ('Spring Boxing Night', 'SPORT', 3, '2030-04-19 18:30:00+00', 'Six amateur bouts.'),
    ('A Midsummer Night''s Dream', 'THEATRE', 4, '2030-04-26 19:00:00+00', 'Outdoor-style staging, indoors.'),
    ('Choir of the Valleys', 'MUSIC', 5, '2030-05-03 19:30:00+00', 'A sixty-voice community choir.'),
    ('Late Laughs', 'COMEDY', 1, '2030-05-10 22:00:00+00', 'Midnight comedy for night owls.'),
    ('The Lighthouse Keeper', 'THEATRE', 6, '2030-05-17 19:30:00+00', 'A one-man show about forty years on a rock.'),
    ('Five-a-Side Charity Cup', 'SPORT', 5, '2030-05-24 11:00:00+00', 'Local businesses play for charity.'),
    ('Synth Revival', 'MUSIC', 2, '2030-05-31 20:00:00+00', 'Analogue synthesisers, played live.'),
    ('Sketch Show Showcase', 'COMEDY', 3, '2030-06-07 19:30:00+00', 'Three sketch groups, one trophy.'),
    ('Pirates of the Pier', 'THEATRE', 5, '2030-06-14 14:00:00+00', 'A family musical with a lot of rope.'),
    ('Summer Strings', 'MUSIC', 6, '2030-06-21 19:00:00+00', 'A string quartet plays pop songs.'),
    ('City Fencing Open', 'SPORT', 1, '2030-06-28 10:00:00+00', 'Foil, epee and sabre.'),
    ('The Comedy Store Cupboard', 'COMEDY', 4, '2030-07-05 20:00:00+00', 'Tiny venue, big names, no refunds.'),
    ('An Evening of Ghost Stories', 'THEATRE', 2, '2030-07-12 21:00:00+00', 'Told by candlelight.'),
    ('Festival Warm-Up', 'MUSIC', 3, '2030-07-19 18:00:00+00', 'Six bands before festival season.');
```

A few things to notice:

- **The ids are predictable.** The inserts don't give ids, so H2 numbers the rows in insert order: venue 1 is Riverside Hall, event 1 is the Bonfire Night show, and events 2 to 28 run in date order. The guides and tests rely on this.
- **Event 1 is in the past** (5 November 2025). Guide 06 needs one event that has already started, to show that you can't book it. Every other event is in **2030**, so the examples keep working for years.
- **`timestamp with time zone`** stores an exact moment, which maps to Java's `Instant`. Every time in the API is UTC.
- **`version`** is there from the start. It costs nothing now, and saves a migration later: guide 03 uses it for caching, and guide 07 for safe concurrent updates.
- **Twenty-eight events** is enough to make paging worth having in guide 03.

### 5. The entities

The code is organised **by feature**, not by layer: everything about venues goes in a `venue` package, everything about events in `event`, and things shared by the whole API in `web`.

`venue/Venue.java`:

```java
package com.example.tickets.venue;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Venue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String city;

    protected Venue() {
        // for JPA
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCity() {
        return city;
    }
}
```

Venues are read-only in this API, so there's no public constructor and no setters. Only JPA creates them, from rows Flyway inserted.

`event/Category.java`:

```java
package com.example.tickets.event;

public enum Category {
    MUSIC, COMEDY, THEATRE, SPORT
}
```

`event/Event.java`:

```java
package com.example.tickets.event;

import com.example.tickets.venue.Venue;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @Enumerated(EnumType.STRING)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Venue venue;

    private Instant startsAt;

    private String description;

    // Hibernate adds 1 on every update. Guide 03 turns it into an ETag.
    @Version
    private long version;

    protected Event() {
        // for JPA
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public Category getCategory() {
        return category;
    }

    public Venue getVenue() {
        return venue;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public String getDescription() {
        return description;
    }

    public long getVersion() {
        return version;
    }
}
```

- **`@Enumerated(EnumType.STRING)`** stores the enum's name (`'MUSIC'`), which matches the seed data. The default, `ORDINAL`, stores a number that changes meaning if anyone reorders the constants.
- **`@ManyToOne(fetch = FetchType.LAZY, optional = false)`**: the venue is only loaded when something asks for it. The default for `@ManyToOne` is `EAGER`, which loads it every time, needed or not. `optional = false` matches the `not null` column.
- **`@Version`** marks the optimistic-locking column. You never set it yourself: Hibernate increases it on every update.

Guide 02 adds a constructor and setters, when the API starts creating and changing events.

### 6. Repositories

`venue/VenueRepository.java`:

```java
package com.example.tickets.venue;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VenueRepository extends JpaRepository<Venue, Long> {

    List<Venue> findAllByOrderByNameAsc();
}
```

`event/EventRepository.java`:

```java
package com.example.tickets.event;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    // Load each event's venue in the same query (a join), not with one extra SELECT per event
    @EntityGraph(attributePaths = "venue")
    List<Event> findAllByOrderByStartsAtAsc();

    @Override
    @EntityGraph(attributePaths = "venue")
    Optional<Event> findById(Long id);
}
```

Every event response includes its venue, and the venue is `LAZY`. With `open-in-view=false`, there are two ways that can go wrong:

- **Without the entity graph**, reading `event.getVenue().getName()` after the transaction has ended throws a `LazyInitializationException` (see Common errors).
- **If it did work** (with open-in-view on), listing 28 events would run one query for the events, then **one more query per event** to fetch its venue. That's the **N+1 problem**, and it gets worse as the table grows.

`@EntityGraph(attributePaths = "venue")` tells Spring Data to fetch the venue **in the same query**, with a join. The repository already has a `findById` from `JpaRepository`; overriding it here is only to add the annotation.

### 7. Response DTOs

`venue/VenueResponse.java`:

```java
package com.example.tickets.venue;

public record VenueResponse(long id, String name, String city) {

    public static VenueResponse from(Venue venue) {
        return new VenueResponse(venue.getId(), venue.getName(), venue.getCity());
    }
}
```

`event/EventResponse.java`:

```java
package com.example.tickets.event;

import com.example.tickets.venue.VenueResponse;

import java.time.Instant;

public record EventResponse(
        long id,
        String title,
        Category category,
        VenueResponse venue,
        Instant startsAt,
        String description) {

    public static EventResponse from(Event event) {
        return new EventResponse(
                event.getId(),
                event.getTitle(),
                event.getCategory(),
                VenueResponse.from(event.getVenue()),
                event.getStartsAt(),
                event.getDescription());
    }
}
```

These two records **are** the API's JSON. Read them as the contract: an event has an `id`, `title`, `category`, a nested `venue` with its own `id`, `name` and `city`, a `startsAt` time and a `description`. There's no `version`, because clients have no use for it as a field (guide 03 sends it as a header instead).

The static `from` method keeps the mapping next to the record, so there's one place to look when a field is added. Mapping libraries such as MapStruct do the same job with code generation. For a handful of small records, a few lines of plain Java are easier to read.

### 8. The service, and "not found"

`web/NotFoundException.java`:

```java
package com.example.tickets.web;

/** Thrown when a resource doesn't exist. ApiExceptionHandler turns it into a 404 ProblemDetail. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String resource, long id) {
        super(resource + " " + id + " was not found");
    }
}
```

One exception for every kind of resource: `new NotFoundException("Event", 999)` has the message `Event 999 was not found`. Ticket types and bookings will use it too.

`event/EventService.java`:

```java
package com.example.tickets.event;

import com.example.tickets.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class EventService {

    private final EventRepository events;

    public EventService(EventRepository events) {
        this.events = events;
    }

    public List<Event> list() {
        return events.findAllByOrderByStartsAtAsc();
    }

    public Event get(long id) {
        return events.findById(id).orElseThrow(() -> new NotFoundException("Event", id));
    }
}
```

- **A service from day one**, even though it does little yet. Guide 02 adds creating, replacing and deleting events, with rules that don't belong in a controller.
- **`@Transactional(readOnly = true)` on the class** makes every method a read-only transaction by default. Methods that write will say so with their own `@Transactional` in guide 02.

Venues are simpler: they're read-only and have no rules, so `VenueController` uses the repository directly. Adding a service that only forwards calls wouldn't make anything clearer.

### 9. The controllers

`event/EventController.java`:

```java
package com.example.tickets.event;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventService events;

    public EventController(EventService events) {
        this.events = events;
    }

    @GetMapping
    public List<EventResponse> list() {
        return events.list().stream()
                .map(EventResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public EventResponse get(@PathVariable long id) {
        return EventResponse.from(events.get(id));
    }
}
```

`venue/VenueController.java`:

```java
package com.example.tickets.venue;

import com.example.tickets.web.NotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/venues")
public class VenueController {

    private final VenueRepository venues;

    public VenueController(VenueRepository venues) {
        this.venues = venues;
    }

    @GetMapping
    public List<VenueResponse> list() {
        return venues.findAllByOrderByNameAsc().stream()
                .map(VenueResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public VenueResponse get(@PathVariable long id) {
        return venues.findById(id)
                .map(VenueResponse::from)
                .orElseThrow(() -> new NotFoundException("Venue", id));
    }
}
```

Compared with the todo app's controllers:

- **`@RestController`** instead of `@Controller`. It's `@Controller` plus `@ResponseBody` on every method: the return value **is** the response body, written as JSON by Jackson, rather than the name of a Thymeleaf template.
- **`@RequestMapping("/events")` on the class** gives every method the same base path. `@GetMapping` alone is `GET /events`; `@GetMapping("/{id}")` is `GET /events/{id}`.
- **No `Model`, no view names, no redirects.** The method returns data and Spring sends `200 OK` with it.

**Where the entity becomes a DTO.** The service returns entities, and the controller maps them to responses. The mapping reads `event.getVenue()`, which is only safe because the repository loaded the venue in the same query.

### 10. `404` as a `ProblemDetail`

When `EventService.get` throws `NotFoundException`, nothing handles it yet, so Spring would answer `500`. Create `web/ApiExceptionHandler.java`:

```java
package com.example.tickets.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into RFC 9457 ProblemDetail responses (application/problem+json).
 * The base class already does this for Spring MVC's own exceptions, such as an unknown URL.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail handle(NotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Not Found");
        return problem;
    }
}
```

- **`@RestControllerAdvice`** applies its `@ExceptionHandler` methods to every controller, and writes their return values as the response body.
- **Returning a `ProblemDetail`** sets the status from the problem's `status`, the `Content-Type` to `application/problem+json`, and fills in `instance` with the request path.
- **Extending `ResponseEntityExceptionHandler`** is the important part. The base class already handles Spring MVC's **own** exceptions (an unknown URL, a path variable that isn't a number, a method the URL doesn't support, unreadable JSON, and more) and turns each into a `ProblemDetail`. You get one error format for those without writing anything. Guide 02 overrides two of its methods to add detail.

You may have seen the property `spring.mvc.problemdetails.enabled=true`. It switches on a similar handler that Spring Boot provides. You don't need it: Boot's handler backs off when you define your own, and you need your own for `NotFoundException` anyway.

### 11. Run it

Run the app: open `TicketsApplication.java` and click the green arrow next to `main`. **Terminal alternative:** `./mvnw spring-boot:run` (`.\mvnw spring-boot:run` in PowerShell).

The log, trimmed:

```
o.f.c.internal.database.base.Database    : Using H2 2.4.240 which is newer than the version Flyway has been verified with. The latest verified version of H2 is 2.3.232.
o.f.core.internal.command.DbMigrate      : Migrating schema "PUBLIC" to version "1 - venues and events"
o.f.core.internal.command.DbMigrate      : Successfully applied 1 migration to schema "PUBLIC", now at version v1 (execution time 00:00.018s)
o.s.boot.tomcat.TomcatWebServer          : Tomcat started on port 8080 (http) with context path '/'
o.s.core.events.SpringDocAppInitializer  : SpringDoc /v3/api-docs endpoint is enabled by default. To disable it in production, set the property 'springdoc.api-docs.enabled=false'
o.s.core.events.SpringDocAppInitializer  : SpringDoc /swagger-ui.html endpoint is enabled by default. To disable it in production, set the property 'springdoc.swagger-ui.enabled=false'
com.example.tickets.TicketsApplication   : Started TicketsApplication in 5.74 seconds (process running for 6.199)
```

The first line is a harmless warning: Spring Boot 4.1 ships a newer H2 than Flyway has been tested with, and everything in this series works with it. The two SpringDoc lines are reminders that the API documentation is public by default, which a production system might not want. For now, it's exactly what you want.

Ask for one event. The `-i` option shows the status line and headers as well as the body:

```bash
curl -i localhost:8080/events/2
```

```
HTTP/1.1 200
Content-Type: application/json
Content-Length: 215

{"id":2,"title":"The Paper Lanterns: Live","category":"MUSIC","venue":{"id":1,"name":"Riverside Hall","city":"London"},"startsAt":"2030-01-18T19:30:00Z","description":"Indie folk five-piece on their first UK tour."}
```

(In PowerShell, type `curl.exe` instead of `curl`. Plain `curl` there is an alias for a different command.)

The list of events is a JSON array of all 28, soonest first:

```bash
curl localhost:8080/events
```

```
[{"id":1,"title":"Bonfire Night Comedy Special","category":"COMEDY","venue":{"id":2,"name":"The Lantern Rooms","city":"London"},"startsAt":"2025-11-05T20:00:00Z","description":"Five comics, one night, lots of sparklers."},{"id":2,"title":"The Paper Lanterns: Live",…},…]
```

Venues come back in name order:

```bash
curl localhost:8080/venues
```

```
[{"id":6,"name":"Castle Wynd Playhouse","city":"Edinburgh"},{"id":5,"name":"Harbourside Arena","city":"Bristol"},{"id":3,"name":"Northern Works","city":"Manchester"},{"id":1,"name":"Riverside Hall","city":"London"},{"id":2,"name":"The Lantern Rooms","city":"London"},{"id":4,"name":"The Mill Theatre","city":"Leeds"}]
```

Now an event that doesn't exist:

```bash
curl -i localhost:8080/events/999
```

```
HTTP/1.1 404
Content-Type: application/problem+json

{"detail":"Event 999 was not found","instance":"/events/999","status":404,"title":"Not Found"}
```

And a typo in the URL:

```bash
curl -i localhost:8080/evnts
```

```
HTTP/1.1 404
Content-Type: application/problem+json

{"detail":"No static resource evnts.","instance":"/evnts","status":404,"title":"Not Found"}
```

You wrote no code for that one. Spring MVC treats a URL no controller handles as a request for a static file (like the todo app's CSS), doesn't find one, and throws `NoResourceFoundException`. The base class of `ApiExceptionHandler` turns that into a `ProblemDetail`.

**With IntelliJ's HTTP client.** Create `requests.http` in the project root (next to `pom.xml`). You'll add to it in every guide:

```http
### List events
GET http://localhost:8080/events

### One event
GET http://localhost:8080/events/2

### An event that doesn't exist (404 ProblemDetail)
GET http://localhost:8080/events/999

### List venues
GET http://localhost:8080/venues

### One venue
GET http://localhost:8080/venues/1
```

**(Ultimate)** Click the green ▶ beside a `###` request, and the response opens below. Each `###` line starts a new request, and the text after it is its name.

> **Sidebar: this API is open to anyone, until guide 05.** There's no login, no token, and no check on who is calling. For reading public event listings that's fine; it's what the finished API does too. But guide 02 adds creating, changing and **deleting** events, and those will also be open to anyone with curl until guide 05 makes them require an organiser's access token. That's a deliberate order: it's much easier to learn how an API behaves first, and then lock it down, than to fight a token on every request while you're still learning what a `201` is. Never deploy an API in this state.

### 12. Swagger UI

With the app running, open `http://localhost:8080/swagger-ui.html` in a browser. It redirects to `/swagger-ui/index.html` and shows every endpoint, grouped by controller (`event-controller`, `venue-controller`).

Click **GET /events/{id}**, then **Try it out**, type `2` as the `id`, and click **Execute**. Swagger UI shows the curl command it ran, the response body and the response headers. Try `999` and you get the `404` `ProblemDetail`.

Swagger UI is a page on top of a JSON document, which SpringDoc builds from your controllers at runtime. You can read that document directly:

```bash
curl localhost:8080/v3/api-docs
```

It's an **OpenAPI** document: a standard, machine-readable description of every path, parameter and response shape. At the moment it's generated purely from your code, so the descriptions are thin (titles like `event-controller`, and nothing saying that `GET /events/{id}` can return `404`). Guide 04 fixes that. For now, treat Swagger UI as a convenient tool for trying requests.

### 13. Tests with `MockMvcTester`

An API's tests should check what a **client** sees: the status, the headers and the JSON. Spring's **`MockMvcTester`** sends requests through the whole of Spring MVC (controllers, Jackson, exception handlers) without starting a real server, and lets you check the response with AssertJ.

Every test class in this series needs the same setup, so bundle it into one **meta-annotation**. Create `src/test/java/com/example/tickets/TicketsIntegrationTest.java`:

```java
package com.example.tickets;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The whole application, real Flyway migrations and seed data, an in-memory H2
 * database (so tests never touch ./data), and a MockMvcTester for calling the API.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:tickets-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
public @interface TicketsIntegrationTest {
}
```

- **`@SpringBootTest`** starts the whole application context, including Flyway, so the tests run against the real migrations and the seed data.
- **The `properties` override** swaps the file database for an **in-memory** one. `DB_CLOSE_DELAY=-1` keeps it alive for as long as the application context is, instead of throwing it away when the last connection closes.
- **`@AutoConfigureMockMvc`** creates the `MockMvcTester` bean. Note the package: in Spring Boot 4 it's `org.springframework.boot.webmvc.test.autoconfigure`. Older articles import it from `org.springframework.boot.test.autoconfigure.web.servlet`.
- **Every test class uses exactly the same configuration**, so Spring's test framework starts the context **once** and shares it between them. That keeps the test run fast, but it also means **all test classes share one database**. A test should never assume, for example, exactly 28 events exist, if another test might have created one. From guide 02 on, tests that write create their own data.

Now `src/test/java/com/example/tickets/event/EventApiTest.java`:

```java
package com.example.tickets.event;

import com.example.tickets.TicketsIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

@TicketsIntegrationTest
class EventApiTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void listsEventsSoonestFirst() {
        var response = mvc.get().uri("/events");

        assertThat(response).hasStatusOk();
        assertThat(response).hasContentType(MediaType.APPLICATION_JSON);
        assertThat(response).bodyJson().extractingPath("$[0].title").isEqualTo("Bonfire Night Comedy Special");
        assertThat(response).bodyJson().extractingPath("$[1].title").isEqualTo("The Paper Lanterns: Live");
    }

    @Test
    void getsOneEventWithItsVenue() {
        var response = mvc.get().uri("/events/2");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "id": 2,
                  "title": "The Paper Lanterns: Live",
                  "category": "MUSIC",
                  "venue": {"id": 1, "name": "Riverside Hall", "city": "London"},
                  "startsAt": "2030-01-18T19:30:00Z"
                }
                """);
    }

    @Test
    void anUnknownEventIsA404ProblemDetail() {
        var response = mvc.get().uri("/events/999");

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "title": "Not Found",
                  "status": 404,
                  "detail": "Event 999 was not found",
                  "instance": "/events/999"
                }
                """);
    }

    @Test
    void anUnknownUrlIsA404ProblemDetailToo() {
        var response = mvc.get().uri("/evnts");

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }
}
```

How `MockMvcTester` reads:

- **`mvc.get().uri("/events")`** builds a request. Nothing is sent until you assert on it: `assertThat(response)` performs the request and gives you assertions about the result.
- **`hasStatusOk()`, `hasStatus(HttpStatus.NOT_FOUND)` and `hasContentType(...)`** check the status line and the `Content-Type` header.
- **`bodyJson().extractingPath("$[0].title")`** picks one value out of the JSON with a **JsonPath** expression. `$` is the whole document, `$[0]` the first element of an array, `.title` a property. `$.length()` is the number of elements in an array, which you'll need in exercise 1.
- **`bodyJson().isLenientlyEqualTo("""…""")`** compares the whole body with the JSON you give. *Leniently* means the actual body may have **extra** fields (here `description`) and the order of fields doesn't matter, but every field you list must match. It's the right tool for "this is what a client relies on" without making the test break every time a field is added.
- **The 404 test checks the `ProblemDetail` itself:** the media type and the members a client would read.

Run them: right-click `src/test/java` → **Run 'All Tests'**. **Terminal alternative:** `./mvnw test`.

---

## Your turn

### Exercise 1: test the venues

Write `src/test/java/com/example/tickets/venue/VenueApiTest.java` with three tests:

1. `GET /venues` returns all six venues, and the first one (by name) is Castle Wynd Playhouse.
2. `GET /venues/3` returns Northern Works in Manchester.
3. `GET /venues/42` is a `404` `ProblemDetail` whose `detail` is `Venue 42 was not found`.

<details>
<summary>Solution</summary>

```java
package com.example.tickets.venue;

import com.example.tickets.TicketsIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

@TicketsIntegrationTest
class VenueApiTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void listsVenuesByName() {
        var response = mvc.get().uri("/venues");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.length()").isEqualTo(6);
        assertThat(response).bodyJson().extractingPath("$[0].name").isEqualTo("Castle Wynd Playhouse");
    }

    @Test
    void getsOneVenue() {
        var response = mvc.get().uri("/venues/3");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"id": 3, "name": "Northern Works", "city": "Manchester"}
                """);
    }

    @Test
    void anUnknownVenueIsA404ProblemDetail() {
        var response = mvc.get().uri("/venues/42");

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().extractingPath("$.detail").isEqualTo("Venue 42 was not found");
    }
}
```

Counting the venues is safe even though all test classes share one database: venues are read-only in this API, so no test can add one.

</details>

### Exercise 2: errors you didn't write

Before you run them, predict the status code of each of these requests. Then run them with `curl -i` and read the `ProblemDetail`s:

```bash
curl -i localhost:8080/events/abc
```

```bash
curl -i -X DELETE localhost:8080/events/2
```

(`-X DELETE` tells curl to use the `DELETE` method instead of `GET`.) Which header in the second response tells a client what it *could* have done?

<details>
<summary>Solution</summary>

```
HTTP/1.1 400
Content-Type: application/problem+json
Connection: close

{"detail":"Failed to convert 'id' with value: 'abc'","instance":"/events/abc","status":400,"title":"Bad Request"}
```

`abc` can't become the `long id` parameter, so it's the client's mistake: `400`, not `404` (the URL pattern exists) and not `500` (nothing failed on the server).

```
HTTP/1.1 405
Allow: GET
Content-Type: application/problem+json

{"detail":"Method 'DELETE' is not supported.","instance":"/events/2","status":405,"title":"Method Not Allowed"}
```

`405 Method Not Allowed`: the resource exists, but not with that method. The **`Allow`** header lists the methods it does support, which HTTP requires on a `405`. After guide 02 it says more.

Both came from `ResponseEntityExceptionHandler`, without a line of code from you.

</details>

### Exercise 3: break the entity graph on purpose

Comment out the `@EntityGraph(attributePaths = "venue")` line above `findById` in `EventRepository`, run `EventApiTest`, and read the failure. Then put it back.

<details>
<summary>Solution</summary>

`getsOneEventWithItsVenue` fails with:

```
jakarta.servlet.ServletException: Request processing failed: org.hibernate.LazyInitializationException: Could not initialize proxy [com.example.tickets.venue.Venue#1] - no session
```

`findById` now loads only the event, and the venue is a lazy **proxy**: an empty stand-in that loads itself on first use. The service's read-only transaction ends when `get` returns, so by the time `EventResponse.from` in the controller calls `getVenue().getName()`, there's no database session to load it with. With `open-in-view=false` that's an error rather than a hidden extra query, which is exactly why the setting is there. The running app would answer `500`.

The fix is the one you had: load the venue in the same query. (Another option is to map to the DTO inside the transaction, in the service. Either way, decide **where** loading happens, rather than letting the JSON writer trigger it.)

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
```

(4 in `EventApiTest` and 3 in `VenueApiTest`, if you did exercise 1.)

Your project should look like this:

```
api/
├── .gitignore
├── mvnw, mvnw.cmd, .mvn/
├── pom.xml
├── requests.http
└── src/
    ├── main/
    │   ├── java/com/example/tickets/
    │   │   ├── TicketsApplication.java
    │   │   ├── event/
    │   │   │   ├── Category.java
    │   │   │   ├── Event.java
    │   │   │   ├── EventController.java
    │   │   │   ├── EventRepository.java
    │   │   │   ├── EventResponse.java
    │   │   │   └── EventService.java
    │   │   ├── venue/
    │   │   │   ├── Venue.java
    │   │   │   ├── VenueController.java
    │   │   │   ├── VenueRepository.java
    │   │   │   └── VenueResponse.java
    │   │   └── web/
    │   │       ├── ApiExceptionHandler.java
    │   │       └── NotFoundException.java
    │   └── resources/
    │       ├── application.properties
    │       └── db/migration/
    │           └── V1__venues_and_events.sql
    └── test/java/com/example/tickets/
        ├── TicketsIntegrationTest.java
        ├── event/
        │   └── EventApiTest.java
        └── venue/
            └── VenueApiTest.java
```

Modified from what start.spring.io generated: `pom.xml` (SpringDoc), `.gitignore` (`data/`), `application.properties`. Deleted: `TicketsApplicationTests.java`.

---

## Common errors

**`org.hibernate.LazyInitializationException: Could not initialize proxy [com.example.tickets.venue.Venue#1] - no session`**
The venue was never loaded, and the transaction had ended by the time the controller read it. In tests it's wrapped as `jakarta.servlet.ServletException: Request processing failed: …`; in the running app it's a `500`. Check that both `findAllByOrderByStartsAtAsc` and `findById` in `EventRepository` have `@EntityGraph(attributePaths = "venue")`. See exercise 3.

**`Schema validation: missing column [version] in table [event]`**
The root cause of a long `BeanCreationException: Error creating bean with name 'entityManagerFactory' … Unable to build Hibernate SessionFactory`. The entity and the migration disagree: here, `Event` has a `version` field but the table has no `version` column. Fix the migration (or, if it has already run, add a new one). Never switch `ddl-auto` to `update` to make it go away: Flyway owns the schema.

**`Migration checksum mismatch for migration version 1`**
Followed by `Either revert the changes to the migration, or run repair to update the schema history.` You edited `V1__venues_and_events.sql` after it had already run against `./data/tickets`. Flyway records a checksum of every migration it applies and refuses to start when one changes. Revert the edit and put the change in a new `V2__…` migration. In development only, you can instead stop the app and delete the `data` folder, so everything is recreated from scratch.

**`Using H2 2.4.240 which is newer than the version Flyway has been verified with.`**
A `WARN` at start-up, not an error. Everything in this series works. Ignore it.

**`Web server failed to start. Port 8080 was already in use.`**
The app is already running, perhaps once from IntelliJ and once from the terminal, or another tutorial's app is on port 8080. Stop the other one.

---

## Recap

- A **REST API** serves **data** to **programs**: resources at URLs, represented as JSON, used through standard HTTP methods. The **status code** is part of every answer.
- URLs name **things** (plural collections, ids, sub-resources); methods say what to do. **Safe** methods only read; **idempotent** ones can be repeated with the same effect. `POST` is neither.
- `2xx` worked, `4xx` is the client's fault, `5xx` is the server's.
- Every response goes through a **response DTO** (a record), never an entity: the JSON is a contract, entities have private fields and lazy relationships, and the two must be free to change separately.
- `@EntityGraph` loads what a response needs **in one query**, avoiding both `LazyInitializationException` and N+1 queries.
- Errors are **`ProblemDetail`s** (`application/problem+json`). Extending `ResponseEntityExceptionHandler` gives Spring MVC's own errors the same shape for free.
- **`MockMvcTester`** tests the API the way a client sees it, and one **meta-annotation** keeps the test setup in one place.
- **Swagger UI** is a browser front end for the OpenAPI document SpringDoc generates.

### Interview angle

<details>
<summary>"What makes an API RESTful?"</summary>

It exposes resources, each identified by a URL, and clients work with representations of them (usually JSON) through HTTP's uniform interface: the standard methods with their standard meanings, and standard status codes. It's stateless: each request carries everything needed to handle it. Fielding's definition also includes hypermedia (HATEOAS), which most real-world "REST" APIs don't implement; it's fair to say so, and to say that what most people mean is "resource-oriented HTTP with JSON". A good answer gives an example: `GET /events/2` reads event 2, `DELETE /events/2` deletes it, rather than `POST /deleteEvent`.

</details>

<details>
<summary>"What's the difference between safe and idempotent? Is PUT idempotent? Is POST?"</summary>

A safe method doesn't change server state: `GET`, `HEAD`, `OPTIONS`. An idempotent method can be applied several times with the same effect as once: all the safe methods, plus `PUT` and `DELETE`. `PUT` replaces a resource with the representation sent, so sending it twice leaves the same result. `DELETE` twice still leaves the resource deleted, even though the second response is a `404`. `POST` is neither safe nor idempotent: two identical "create" requests create two things. That's why clients can automatically retry `GET`, `PUT` and `DELETE` after a timeout, but need something extra, such as an idempotency key, to retry a `POST` safely.

</details>

<details>
<summary>"Why not return JPA entities from a controller?"</summary>

The JSON becomes coupled to the database schema, so a schema change silently changes the API contract. Entities often have fields clients shouldn't see (internal flags, version columns, other users' ids). Serialising them triggers lazy loading outside a transaction (errors or N+1 queries) and can loop forever on bidirectional relationships. On the way in, binding request JSON straight to an entity lets clients set fields they shouldn't (mass assignment). Response and request DTOs make the contract explicit and keep the entity private.

</details>

<details>
<summary>"How should an API report errors?"</summary>

With the right status code, so clients can react without parsing the body, and a consistent, machine-readable body. RFC 9457 Problem Details is the standard format: `type`, `title`, `status`, `detail` and `instance`, as `application/problem+json`, with extra members allowed for things like a list of invalid fields. In Spring, return `ProblemDetail` from a `@RestControllerAdvice`, and extend `ResponseEntityExceptionHandler` so the framework's own errors use the same format. Never send `200` with an error in the body, and never use `500` for bad input.

</details>

<details>
<summary>"What is HATEOAS? Would you use it?"</summary>

Hypermedia as the engine of application state: responses include links to related resources and available actions, so clients follow links instead of building URLs. It makes clients less dependent on URL structure, and suits APIs whose available actions depend on state. Most public APIs don't use it, because client developers work from documentation (often OpenAPI) and build URLs anyway, so the links add size and complexity for little gain. A balanced answer: know what it is and the formats (HAL, Spring HATEOAS), and choose it when clients will genuinely navigate by links.

</details>

### Further reading

- [RFC 9110: HTTP Semantics](https://www.rfc-editor.org/rfc/rfc9110): methods, including [safe](https://www.rfc-editor.org/rfc/rfc9110#section-9.2.1) and [idempotent](https://www.rfc-editor.org/rfc/rfc9110#section-9.2.2) methods, and every status code
- [RFC 9457: Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457)
- [Roy Fielding's dissertation, chapter 5: Representational State Transfer](https://ics.uci.edu/~fielding/pubs/dissertation/rest_arch_style.htm)
- [Spring Framework reference: error responses](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html): `ProblemDetail` and `ResponseEntityExceptionHandler`
- [Spring Framework reference: testing with MockMvcTester (AssertJ integration)](https://docs.spring.io/spring-framework/reference/testing/mockmvc/assertj.html)
- [Spring Data JPA reference: entity graphs](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.entity-graph)
- [springdoc-openapi](https://springdoc.org/)

**Simplification to notice:** `GET /events` returns **every** event in one array. With 28 rows that's fine. With 28,000 it isn't, and a client can't ask for "the next ones". Guide 03 replaces the array with pages.

**Next:** [02: Creating and changing data](02-creating-and-changing-data.md). `POST`, `PUT`, `PATCH` and `DELETE`, validation, and one error format for every kind of bad request.
