# 01: Calling an API

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](README.md) for version notes.

## What you'll learn

- What an **HTTP request and response** look like **from the caller's side**: method, URL, query string, headers, status code and body, and what your code has to decide about each
- What makes consuming **someone else's API** different from calling your own: you can't change it, you can't see inside it, and it has rules (a key, a daily quota) you must live with
- How to run a **fake provider** with **WireMock**: one jar, a `mappings/` folder that says which requests to answer, and a `__files/` folder that says what to answer with
- How to call an API with Spring's **`RestClient`**, with the base URL and a header set up once
- How **JSON becomes Java records**, taking only the fields you need and ignoring the rest
- Why an **API key** is a password, why it lives in an **environment variable** and never in a file, and how to make the application **refuse to start** without one
- How to test an HTTP client against a **WireMock server started by the test**, on a random port
- How to make **one call to the real API-Football**, if you have a key, without wasting your quota

By the end you'll have an importer that asks a provider for one football team and shows you what it said: `GET /provider/teams/900001` on the importer sends `GET /teams?id=900001` to the provider and returns the team and its ground as JSON. It doesn't store anything yet: that's guide 03.

---

## Prerequisites for this guide

This guide assumes you've done the [todo app](../todo-app/README.md) series, or know the same things:

- **Spring Boot basics:** generating a project, `application.properties`, beans and constructor injection, running with `mvnw` and from IntelliJ.
- **Spring MVC:** `@RestController`, `@GetMapping`, `@PathVariable`.
- **Tests:** JUnit and AssertJ, and `@SpringBootTest`.
- **Core Java:** records, `Optional`, streams.

You'll also need:

- **IntelliJ IDEA** (Community or Ultimate) and **JDK 25**, with `java` on your `PATH` (the todo app's guide 01 set this up). Check with `java -version`.
- **curl**, which comes with Windows 10 and later, macOS and Linux.
- **An API-Football key**, only for the last step, and only if you want to. Everything else works without one.

---

## Concepts

### Someone else's API

In the todo app you wrote the server, and the only caller was a browser. If you built the [REST API](../rest-api/README.md), you wrote both ends. Either way, when something didn't fit, you changed the server.

This series is about the other position, and it's the one you're in far more often at work: **calling a service someone else runs.** Here that's [API-Football](https://www.api-football.com), a commercial football-data service, and they call it the **provider**. You can't change it. You can't see its code. Its documentation is good but not complete. It has rules you didn't choose: you must send a **key** with every request, and the free plan allows **10 requests a minute and 100 a day**. Its data changes from one day to the next, and at some point it will be slow or down.

What you control is your side of the conversation:

- **What you send:** the right URL, the right parameters, the key.
- **How you read what comes back:** which parts of the reply you trust and use.
- **What you do when the reply isn't what you expected.** That's most of the series. This guide gets the first two working.

> **Sidebar: why not let the game call API-Football itself?** The author's real use for this importer is a football game. It would be simpler to have the game ask API-Football for a team whenever it needs one. Three reasons not to. **The key:** anything you ship to players' devices can be unpacked, so the key would effectively be public, and anyone could spend your quota. **The quota:** 100 requests a day, shared by every player, would last minutes. **Availability:** when the provider is down, the game would be broken too. So the importer **copies** the data into our own database, at its own pace, and everything else reads from there. The key never leaves the importer.

### An HTTP request, from the caller's side

> **Done the REST API series?** You know methods, status codes and JSON from the server's side. Skim this section. The table of what a **caller** has to decide is the new part.

An HTTP request is a few lines of text. Roughly what the importer sends to API-Football to ask for one team:

```
GET /teams?id=247 HTTP/1.1                ← the method, the path and the query string
Host: v3.football.api-sports.io           ← which server
x-apisports-key: <your key>               ← a header: who's asking
Accept: application/json                  ← another header: what we'd like back
```

And the reply has the same three parts: a **status line**, **headers**, and a **body**. Here's a real one, from the fake provider you'll set up in step 5, cut short:

```
HTTP/1.1 200 OK                                     ← the status code
Content-Type: application/json                      ← the body is JSON
x-ratelimit-requests-remaining: 89                  ← the provider's own headers
                                                    ← a blank line, then the body
{"get":"teams","parameters":{"id":"900001"},"errors":[],"results":1, … "response":[{"team":{"id":900001,"name":"Riverside Rovers", …
```

When you write the server, Spring hands you a request and you decide the answer. When you're the caller, every part is **your** job, either to get right or to check:

| Part | The caller's job |
| --- | --- |
| **Method** | `GET` for reading. It's **safe** (it changes nothing on the provider) and **idempotent** (sending it twice is the same as once), so it's always safe to try again. Every request in this series is a `GET` |
| **URL** | Built from parts: the provider's **base URL** (`https://v3.football.api-sports.io`), a **path** (`/teams`) and a **query string** (`?id=247`). Values in the query string have to be **encoded**, so let the library build it |
| **Request headers** | Who you are (`x-apisports-key`) and what you'd like back (`Accept`). The provider decides which ones it needs: its documentation says |
| **Status code** | **Check it first.** `2xx`: it worked. `4xx`: **you** sent something wrong, and sending it again unchanged won't help. `5xx`: **they** had a problem, and it might work later (guide 08) |
| **Response headers** | `Content-Type` says how to read the body. Providers add their own: API-Football's `x-ratelimit-…` headers say how many requests you have left (guide 06) |
| **Body** | Turn the JSON into Java objects, taking only what you need |

One warning for the "check the status" row: API-Football doesn't always use status codes the way the table says. Some of its errors arrive with **`200 OK`**. Guide 02 deals with that. For now, assume the happy path.

### What API-Football sends back

Every reply from API-Football is wrapped in the same **envelope**. This is a **real reply from API-Football**, for Celtic (team `247`), spread over several lines to make it readable:

```json
{"get":"teams","parameters":{"id":"247"},"errors":[],"results":1,"paging":{"current":1,"total":1},
 "response":[{"team":{"id":247,"name":"Celtic","code":"CEL","country":"Scotland","founded":1888,"national":false,
   "logo":"https://media.api-sports.io/football/teams/247.png"},
  "venue":{"id":1386,"name":"Celtic Park","address":"18 Kerrydale Street","city":"Glasgow","capacity":60832,
   "surface":"grass","image":"https://media.api-sports.io/football/venues/1386.png"}}]}
```

| Field | Means |
| --- | --- |
| `get` | Which endpoint answered (`teams`) |
| `parameters` | What you asked for, echoed back |
| `errors` | What was wrong with the request. Empty here |
| `results` | How many entries are in `response` |
| `paging` | Which page this is, for long lists |
| `response` | **The data:** a list, even when you asked for one team by id |

Each entry in `response` is a `team` and its `venue` (home ground). In this guide the importer reads **only `response`**, and only some of the fields in it. Guide 02 reads the rest of the envelope, because that's where API-Football puts its errors.

### A fake provider

You could develop against the real API from day one. You'd regret it:

- **The quota.** 100 requests a day is used up in an afternoon of restarting and retrying, and one team's import (guide 04) takes around 30.
- **The data changes.** Players transfer, squads change, and a guide that says "you should see this" would be wrong by next week.
- **You can't make it misbehave.** The interesting code handles errors, rate limits and slow replies. You can't ask the real API for a `500`, or to run out of quota at the request of your choice.
- **Tests must not use the network.** A test that depends on someone else's server fails when their server does, and spends your quota every time it runs.

So the importer talks to a **fake provider** on your own machine by default. It's **[WireMock](https://wiremock.org)**: a program that answers HTTP requests with replies you describe in files. Each answer is two files:

```
fake-provider/
├── mappings/teams-900001.json    "when a GET /teams arrives with id=900001 ..."
└── __files/teams-900001.json     "... answer with this body"
```

The replies are **copies of API-Football's real shapes**, with the same envelope and the same headers, filled with **fictional** teams and players. Their ids start at `900001` for teams (and `9000001` for players, later), a range no real team uses, so you can always tell fake data from real.

The importer doesn't know or care which one it's talking to. It has one setting, the **base URL**: `http://localhost:8089` for the fake provider, `https://v3.football.api-sports.io` for the real one. Everything else is the same.

WireMock also works **inside tests**: a test starts its own WireMock server on a random port, tells it what to answer, and checks what the importer sent. You'll do that in step 13.

### `RestClient`

> **Done the REST API series?** Guide 09's scanner used `RestClient`, and this is the same client. Skim to the next section. What's new here is the configuration around it: a base URL and a key from properties, and a header sent only when there is a key.

**`RestClient`** is Spring's HTTP client for code that waits for each answer. A call reads like a sentence:

```java
restClient.get().uri("/teams?id={id}", id).retrieve().body(TeamsReply.class)
```

Send a `GET` to `/teams?id=…` (with the id filled in, and encoded), get the response, and turn its JSON body into a `TeamsReply`. If the status is `4xx` or `5xx`, `retrieve()` throws instead: an **`HttpClientErrorException`** for `4xx` (with subclasses such as `HttpClientErrorException.NotFound`), or an **`HttpServerErrorException`** for `5xx`. You'll see one in step 12.

You build a `RestClient` once, from a **`RestClient.Builder`**, with everything every request shares: the **base URL**, so calls only give the path, and **default headers**, such as the key. Spring Boot provides the builder, already set up with Jackson for JSON. In Spring Boot 4 it comes from its own starter, **`spring-boot-starter-restclient`** ("HTTP Client" on start.spring.io). Without it there's no `RestClient.Builder` to ask for.

`RestTemplate` is the older client you'll find in most examples online, and in a lot of existing code. It still works, but it's in maintenance. `WebClient` is for reactive applications, which this isn't.

### JSON into records

Spring Boot 4 uses **Jackson 3** to turn the JSON body into Java objects. With a record, Jackson matches JSON properties to record components **by name**:

```json
"venue":{"id":1386,"name":"Celtic Park","address":"18 Kerrydale Street","city":"Glasgow","capacity":60832,"surface":"grass","image":"…"}
```

```java
public record ApiVenue(Long id, String name, String city, Integer capacity) {
}
```

Four of the seven properties have a component to go into. **The other three (`address`, `surface`, `image`) are ignored**, without an error: that's Spring Boot's Jackson default. It matters more than it looks:

- **You only describe what you use.** The records are small, and they say exactly which of the provider's fields the importer depends on.
- **The provider can add fields without breaking you.** API-Football adding `"pitchLength"` tomorrow changes nothing for the importer.

A JSON object inside another becomes a record inside another (`TeamItem` holds an `ApiTeam` and an `ApiVenue`), and a JSON array becomes a `List`. Fields the provider sometimes sends as `null` need a type that can hold `null`: `String`, and `Long` or `Integer` rather than `long` or `int`. A team's `code` can be `null`, for example.

These records are **the provider's model, not ours.** They mirror API-Football's JSON, so they're named after it (`ApiTeam`, `ApiVenue`) and live in a package of their own, `provider`. Guide 03 adds our own `Team` entity, and one place copies from theirs to ours. When API-Football changes something, only the `provider` package should need to know.

### An API key is a password

To use API-Football you register, and they give you a **key**: a long random string you send in an **`x-apisports-key`** header with every request. It tells the provider which account is asking, and so **whose quota to spend**.

**Treat it as a password**, because it is one. Anyone who has it can use your 100 requests a day (on a paid plan, your money), and everything they do with it is in your name.

**So it never goes in a file in your project.** Not in `application.properties`, not in a test, not "just for now":

- **Files get committed.** One `git add .` and the key is in the repository.
- **Git never forgets.** Deleting the line in the next commit doesn't remove it from the history. Anyone with a copy of the repository can find it.
- **Repositories travel.** They're pushed, shared, forked and made public, zipped up for a colleague, pasted into questions online, and shown in screenshots. Automated scanners search public repositories for keys all the time.
- **Every environment has its own.** Your laptop, a test server and production should each have their own key, so one can be replaced without touching the others. A key in a file is the same everywhere.

Instead, the key lives in an **environment variable**, **`APISPORTS_KEY`**, which belongs to your terminal session or your run configuration, not to the project. The properties file only says **where** the key comes from:

```properties
api-football.key=${APISPORTS_KEY:}
```

`${APISPORTS_KEY:}` means "the value of the environment variable `APISPORTS_KEY`, or, if there isn't one, nothing". The file is safe to commit, because it doesn't contain the key.

Two more rules complete the picture:

- **Fail fast.** If the importer is pointed at the real API with no key, every request would be rejected, and a rejected request may still count against the day's quota (on the real API, a malformed request appeared to). So the importer **refuses to start**, with a message that says exactly what's missing. A clear error at start-up beats a confusing failure halfway through an import.
- **Never print it.** Not in log lines, not in error messages, not in an object's `toString()`. The start-up error names the **variable**, never the value.

The key travels in a header, so it's only as safe as the connection: API-Football's base URL is `https://`, which encrypts the whole request, headers included. Never turn certificate checks off to "make HTTPS work". The fake provider is plain `http://` on your own machine, and needs no key, so without one the importer doesn't send the header at all.

> **Sidebar: where real systems keep keys.** An environment variable is where the key **arrives**, not where it's **kept**. On a server, it's injected by whatever runs the application, from a **secrets manager** (AWS Secrets Manager, Azure Key Vault, HashiCorp Vault and the like) or a CI/CD system's secret store, which keep keys encrypted, control who can read them, and record who did. Many teams also use a `.env` file on their laptop that a tool loads into the environment. That's a file, so it must be in `.gitignore` before the key goes in it, and it's still a copy of the key on disk. This series keeps it simplest: you type the key into the session that needs it.

---

## Steps

### 1. Generate the importer

**In IntelliJ Ultimate:** **File → New → Project… → Spring Boot**. Set **Location** to `tutorials/workspace/consuming-apis` and **Name** to `importer`, so the project lands in `workspace/consuming-apis/importer/`. IntelliJ's wizard talks to [start.spring.io](https://start.spring.io), so the settings are the same as the website's.

**With IntelliJ Community, or in a browser:** go to [start.spring.io](https://start.spring.io), choose the same settings, click **Generate**, and unzip the download into `tutorials/workspace/consuming-apis/`. The zip contains an `importer` folder: make sure you don't end up with `importer/importer/`. Then **File → Open…** in IntelliJ, choose the `importer` folder (the one containing `pom.xml`), and trust the project.

| Setting | Value |
| --- | --- |
| Project | **Maven** |
| Language | **Java** |
| Spring Boot | **4.1.2** (or the newest 4.1.x that isn't SNAPSHOT/M/RC) |
| Group | `com.example` |
| Artifact | `importer` |
| Name | `importer` |
| Description | `Imports football teams and players from API-Football into our own database` |
| Package name | `com.example.importer` |
| Packaging | **Jar** |
| Configuration | **Properties** |
| Java | **25** |

The main class is `ImporterApplication`. Add five dependencies:

| Dependency | Why |
| --- | --- |
| **Spring Web** | A controller you can call with curl, and the embedded Tomcat server |
| **HTTP Client** | `RestClient`, and the `RestClient.Builder` Spring Boot sets up for you (`spring-boot-starter-restclient`) |
| **Spring Data JPA** | Entities and repositories: guide 03 |
| **Flyway Migration** | Database migrations: guide 03 |
| **H2 Database** | A database stored in a file: guide 03 |

The last three aren't used until guide 03, but choosing everything now means one generated project, and no hand-editing of the `pom.xml` later. Until guide 03, Flyway has no migrations to run, and says so with a warning at every start (step 12). It's harmless.

Check **File → Project Structure → Project → SDK** is a Java 25 JDK.

### 2. Tidy what was generated

Four small changes to the generated project.

**Delete the generated test**, `src/test/java/com/example/importer/ImporterApplicationTests.java`. It would start the application against the real file database in `./data`. Every test in this series uses an in-memory database instead (step 13).

**Remove `spring-boot-h2console` from `pom.xml`.** start.spring.io adds this dependency by itself whenever you pick both Spring Web and H2. It's a web page for browsing the database, which we don't need, and which would need securing in guide 05. Delete the whole `<dependency>` element whose `<artifactId>` is `spring-boot-h2console`.

**Add WireMock for the tests.** In `pom.xml`, add a version property to `<properties>`:

```xml
    <properties>
        <java.version>25</java.version>
        <wiremock.version>3.13.2</wiremock.version>
    </properties>
```

and this dependency, after the generated test dependencies:

```xml
        <!-- A pretend API-Football for the tests. The standalone jar has its own copy of Jetty inside,
             so it can't clash with the versions Spring Boot manages. -->
        <dependency>
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock-standalone</artifactId>
            <version>${wiremock.version}</version>
            <scope>test</scope>
        </dependency>
```

WireMock is a web server, and the ordinary `org.wiremock:wiremock` artifact depends on Jetty, whose version Spring Boot's dependency management would override. The **standalone** artifact has its own copies of everything it needs hidden inside it, so nothing clashes. It's also the very same jar you'll run as the fake provider in the next step, so there's one thing to download, not two. Reload Maven in IntelliJ (the **M** icon that appears, or **Maven tool window → Reload All Maven Projects**).

The rest of the dependencies are as generated:

| Generated dependency | From |
| --- | --- |
| `spring-boot-starter-webmvc` | Spring Web |
| `spring-boot-starter-restclient` | HTTP Client |
| `spring-boot-starter-data-jpa` | Spring Data JPA |
| `spring-boot-starter-flyway` | Flyway Migration |
| `h2` (runtime) | H2 Database |
| `spring-boot-starter-…-test` (one for each of the four starters) | Added automatically, for tests |

**Ignore the database folder.** Add one line to the end of the generated `.gitignore`:

```
data/
```

### 3. The fake provider

The fake provider lives next to the importer, in its own folder:

```
workspace/consuming-apis/
├── importer/
└── fake-provider/
    ├── mappings/
    └── __files/
```

Create `fake-provider` and the two folders inside it. It isn't a Java project, so any editor will do; in IntelliJ you can open it in a second window with **File → Open…**, choosing `fake-provider` and **New Window**. **Terminal alternative**, from `workspace/consuming-apis`:

```bash
mkdir -p fake-provider/mappings fake-provider/__files
```

(In PowerShell: `mkdir fake-provider/mappings, fake-provider/__files`.)

**Download WireMock.** It's one jar, from Maven Central: [wiremock-standalone-3.13.2.jar](https://repo1.maven.org/maven2/org/wiremock/wiremock-standalone/3.13.2/wiremock-standalone-3.13.2.jar) (about 19.5 MB). Save it in `fake-provider/`. Or let Maven fetch it: from `importer/`, run

```bash
./mvnw dependency:copy -Dartifact=org.wiremock:wiremock-standalone:3.13.2 -DoutputDirectory=../fake-provider
```

(In PowerShell, `.\mvnw`, and put each `-D…` argument in quotes: `.\mvnw dependency:copy "-Dartifact=org.wiremock:wiremock-standalone:3.13.2" "-DoutputDirectory=../fake-provider"`.)

The jar is downloaded, not written, so keep it out of version control: create `fake-provider/.gitignore` containing

```
wiremock-standalone-*.jar
```

### 4. One team for the fake provider

A WireMock **mapping** says which requests to answer, and how. Create `fake-provider/mappings/teams-900001.json`:

```json
{
  "request": {
    "method": "GET",
    "urlPath": "/teams",
    "queryParameters": { "id": { "equalTo": "900001" } }
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
    "bodyFileName": "teams-900001.json"
  }
}
```

- **`request`** is what has to match. **`urlPath`** is the path without the query string, and **`queryParameters`** checks the query string separately: `id` must be exactly `900001`. (Using `"url": "/teams?id=900001"` would also work, but would break as soon as a request had a second parameter, or the same ones in another order.)
- **`response`** is the answer: a status, headers and a body.
- **The headers are the real API's.** API-Football sends exactly these on every reply: the content type, "don't cache this", and four rate-limit counters (10 a minute with 9 left, 100 a day with 89 left). The fake provider's numbers never change. Guide 06 reads them, and adds fixtures where they run out.
- **`bodyFileName`** names a file in `__files/`, so the body can be written as plain JSON rather than squeezed into a string here.

Create the body, `fake-provider/__files/teams-900001.json`:

```json
{"get":"teams","parameters":{"id":"900001"},"errors":[],"results":1,"paging":{"current":1,"total":1},
 "response":[{"team":{"id":900001,"name":"Riverside Rovers","code":"RIV","country":"England","founded":1923,"national":false,
   "logo":"https://media.api-sports.io/football/teams/900001.png"},
  "venue":{"id":800001,"name":"Riverside Park","address":"1 River Road","city":"Riverside","capacity":21000,
   "surface":"grass","image":"https://media.api-sports.io/football/venues/800001.png"}}]}
```

Compare it with Celtic's real reply in the Concepts: same envelope, same fields, same order. Only the values are made up. Riverside Rovers don't exist, and neither do team `900001` or venue `800001`.

### 5. Run the fake provider

**In IntelliJ:** open the **Terminal** tool window (<kbd>Alt</kbd>+<kbd>F12</kbd>), go to `fake-provider`, and start WireMock. **Or** do the same in any terminal:

```bash
java -jar wiremock-standalone-3.13.2.jar --port 8089
```

WireMock reads `mappings/` and `__files/` from the folder you start it in, which is why you run it from `fake-provider/`. After a banner, it prints its settings:

```
version:                      3.13.2
port:                         8089
```

Leave it running. <kbd>Ctrl</kbd>+<kbd>C</kbd> stops it. **WireMock reads the mapping files only when it starts**, so after adding or changing one, stop it and start it again.

Before writing any Java, ask it for the team yourself. The `-i` option shows the status line and headers as well as the body, and the quotes stop your shell from treating `?` specially:

```bash
curl -i "http://localhost:8089/teams?id=900001"
```

(In PowerShell, type `curl.exe` instead of `curl`. Plain `curl` there is an alias for a different command.)

```
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: no-store, no-cache, must-revalidate
x-ratelimit-limit: 10
x-ratelimit-remaining: 9
x-ratelimit-requests-limit: 100
x-ratelimit-requests-remaining: 89
Matched-Stub-Id: ed3fc1b5-246a-4039-b7cd-3f987f79a615
Transfer-Encoding: chunked

{"get":"teams","parameters":{"id":"900001"},"errors":[],"results":1,"paging":{"current":1,"total":1},
 "response":[{"team":{"id":900001,"name":"Riverside Rovers","code":"RIV","country":"England","founded":1923,"national":false,
   "logo":"https://media.api-sports.io/football/teams/900001.png"},
  "venue":{"id":800001,"name":"Riverside Park","address":"1 River Road","city":"Riverside","capacity":21000,
   "surface":"grass","image":"https://media.api-sports.io/football/venues/800001.png"}}]}
```

Everything in the mapping is there: the `200`, your six headers and the body file. Two headers are WireMock's own. **`Matched-Stub-Id`** says which mapping answered (a random id that changes every time WireMock starts, because the mapping file doesn't give one), and **`Transfer-Encoding: chunked`** is how it sends the body. API-Football doesn't send `Matched-Stub-Id`: it's how you tell the fake from the real thing.

Now ask for a team the fake provider doesn't know:

```bash
curl -i "http://localhost:8089/teams?id=1"
```

No mapping matches, so WireMock answers with its own **`404`**, a plain-text body that starts `Request was not matched`, and a table comparing your request with the **closest stub** it has, with the difference marked:

```
Query: id = 900001                                          | id: 1                                                  <<<<< Query does not match
```

That table is the first thing to read when the fake provider doesn't answer the way you expect: usually a typo in the path or a parameter. Remember this `404`: in step 12 you'll see what the importer makes of it. (The real API **never** answers `404` for an unknown team, as guide 02 shows. This one is WireMock's.)

### 6. Configure the importer

Replace `src/main/resources/application.properties`:

```properties
spring.application.name=importer

# H2 database stored in ./data/importer.mv.db
spring.datasource.url=jdbc:h2:file:./data/importer
spring.datasource.username=sa

# Flyway owns the schema; Hibernate only checks it matches the entities
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false

# The provider. By default, the fake provider (WireMock) on this machine.
# For the real API: api-football.base-url=https://v3.football.api-sports.io
api-football.base-url=http://localhost:8089
# Never put the key in this file: it comes from the APISPORTS_KEY environment variable
api-football.key=${APISPORTS_KEY:}
```

- **The database block** is for guide 03, set now so it's in place: an H2 database in a file under `./data` (step 2 git-ignored it), Flyway in charge of the schema, and Hibernate only checking it. `open-in-view=false` keeps database sessions out of web requests; guide 03 explains why that matters for an importer.
- **`api-football.base-url`** is where the provider is. It's ours, not Spring's: step 7 reads it. The default is the fake provider. To use the real API you **override** it when you start the application (step 14), rather than editing this line, so the file's default is always the safe one.
- **`api-football.key`** is the line from the Concepts: the value of `APISPORTS_KEY`, or empty. Without the variable, the key is an empty string, not an error, which is right for the fake provider.

The importer runs on Spring Boot's default port, **8080**, so there's no `server.port` line.

### 7. The provider's settings, checked at start-up

Rather than reading the two properties wherever they're needed, put them in one **`@ConfigurationProperties`** record. Create `provider/ApiFootballProperties.java`:

```java
package com.example.importer.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where API-Football is, and our key for it. Bound from the {@code api-football.*} properties.
 */
@ConfigurationProperties("api-football")
public record ApiFootballProperties(String baseUrl, String key) {

    public static final String REAL_API = "https://v3.football.api-sports.io";

    public ApiFootballProperties {
        // Fail fast: without a key every call to the real API would fail, and might still count
        // against the daily quota. Better to refuse to start, with a clear reason.
        if (baseUrl.startsWith(REAL_API) && (key == null || key.isBlank())) {
            throw new IllegalStateException(
                    "api-football.base-url is the real API but no key is set. Set the APISPORTS_KEY environment variable.");
        }
    }

    public boolean hasKey() {
        return key != null && !key.isBlank();
    }
}
```

- **`@ConfigurationProperties("api-football")`** binds every property starting `api-football.` to the record. Spring Boot matches the property's kebab-case name to the component's camel-case one: `api-football.base-url` goes into `baseUrl`, and `api-football.key` into `key`. A record has one constructor, so Spring calls it with the values.
- **The fail-fast check is in the compact constructor**, so a record with a real base URL and no key **can't exist**. Spring can't create it, so the application can't start. `isBlank()` also catches a key that is only spaces, which is easy to end up with when pasting.
- **The message names the variable, not the value.** It tells you what to do, and gives nothing away.
- **`hasKey()`** answers "should we send the key?" for step 8. No key means the fake provider, which doesn't need one.

Spring has to be told to look for `@ConfigurationProperties` records. Add **`@ConfigurationPropertiesScan`** to `ImporterApplication.java`:

```java
package com.example.importer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ImporterApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImporterApplication.class, args);
    }

}
```

It finds every `@ConfigurationProperties` class in `com.example.importer` and below, and makes each one a bean, so other beans can ask for an `ApiFootballProperties` in their constructor.

**A record prints everything.** A record's generated `toString()` includes every component, so `log.info("Using {}", properties)` would print the key. Nothing in the importer logs this record. Keep it that way: log `properties.baseUrl()` if you need to, never the whole object.

### 8. One `RestClient` for API-Football

Create `provider/ProviderConfig.java`:

```java
package com.example.importer.provider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ProviderConfig {

    @Bean
    RestClient apiFootballRestClient(RestClient.Builder builder, ApiFootballProperties properties) {
        builder.baseUrl(properties.baseUrl());
        if (properties.hasKey()) {
            // Sent with every request. The fake provider doesn't need it.
            builder.defaultHeader("x-apisports-key", properties.key());
        }
        return builder.build();
    }
}
```

- **`RestClient.Builder`** comes from Spring Boot, already set up with Jackson. Ask for the builder rather than calling `RestClient.create()`, so you get that set-up. Spring Boot gives each bean that asks for one a **new** builder, so changing it here affects nobody else.
- **`baseUrl`** means every call gives only the path: `/teams?id=…`, not the whole URL. Pointing the importer at the real API is one property, and no code.
- **`defaultHeader("x-apisports-key", …)`** adds the header to **every** request this client sends, so no call can forget it. It's added **only when there's a key**. Without one (the fake provider), the header isn't sent at all, rather than sent empty.
- **There's one `RestClient` for API-Football**, and it's the only place that knows about the key. Every other class just makes calls.

Notice what that `if` means: **with `APISPORTS_KEY` set, the key is sent to whatever the base URL is**, the fake provider included. On your own machine that's harmless, and WireMock ignores headers its mappings don't mention. It's still a reason to set the variable only in the session that needs it (step 14).

Underneath, Spring Boot uses the JDK's own `java.net.http.HttpClient` to send the requests, since there's no other HTTP library on the classpath. You don't need to choose one.

### 9. The provider's JSON as records

Four records describe the part of the reply the importer reads, from the outside in. Create them in the `provider` package.

`provider/TeamsReply.java`, the envelope, or as much of it as we need for now:

```java
package com.example.importer.provider;

import java.util.List;

/** The part of the provider's reply we need for now: the "response" list. Other fields are ignored. */
public record TeamsReply(List<TeamItem> response) {
}
```

`provider/TeamItem.java`, one entry in `response`:

```java
package com.example.importer.provider;

/** One entry in the /teams "response" list: a team and its home ground. */
public record TeamItem(ApiTeam team, ApiVenue venue) {
}
```

`provider/ApiTeam.java`:

```java
package com.example.importer.provider;

/** A team, exactly as API-Football describes it. {@code code} and {@code country} can be null. */
public record ApiTeam(Long id, String name, String code, String country, Integer founded, boolean national,
        String logo) {
}
```

`provider/ApiVenue.java`:

```java
package com.example.importer.provider;

/** A team's home ground, as API-Football describes it. */
public record ApiVenue(Long id, String name, String city, Integer capacity) {
}
```

Lined up against the JSON:

```
{ "get":…, "parameters":…, "errors":…, "results":…, "paging":…,     ← ignored (guide 02 reads them)
  "response": [                                                      ← TeamsReply.response: List<TeamItem>
    { "team":  {"id", "name", "code", "country", "founded",          ← TeamItem.team:  ApiTeam
                "national", "logo"},
      "venue": {"id", "name", "address", "city", "capacity",         ← TeamItem.venue: ApiVenue
                "surface", "image"} } ] }                               (address, surface, image ignored)
```

- **`TeamsReply` takes one field out of six.** The others are ignored, as the Concepts described. Guide 02 replaces it with a record for the whole envelope.
- **`ApiTeam` has all seven of the team's fields**, because guide 03 uses most of them. `code` and `country` are `String`s, which can be `null`: API-Football doesn't have a code for every team. `id` and `founded` are boxed, `Long` and `Integer`, for the same reason. `national` is a plain `boolean`: it's `true` for national teams such as Scotland, which guide 03 stores.
- **`ApiVenue` takes four of seven**, to show that leaving fields out is fine. The importer will never store a venue, but it's useful to see in the next step's output where a team plays.

### 10. The client

One class makes the calls. The rest of the importer asks it for teams and never sees a URL. Create `provider/ApiFootballClient.java`:

```java
package com.example.importer.provider;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ApiFootballClient {

    private final RestClient restClient;

    public ApiFootballClient(RestClient apiFootballRestClient) {
        this.restClient = apiFootballRestClient;
    }

    /** GET /teams?id={id}: the team and its venue, or empty if the provider has no such team. */
    public Optional<TeamItem> fetchTeam(long id) {
        TeamsReply reply = restClient.get()
                .uri("/teams?id={id}", id)
                .retrieve()
                .body(TeamsReply.class);
        return reply.response().stream().findFirst();
    }
}
```

- **The constructor asks for a `RestClient`**: the one from step 8, the only one there is. The parameter has the bean's name, `apiFootballRestClient`, which also lets Spring pick the right one by name if there's ever a second `RestClient`.
- **`.uri("/teams?id={id}", id)`** is a **URI template**: `{id}` is filled in from the argument, and encoded if it needs to be. Never build URLs by joining strings: one day a value with a space or an `&` in it gives you a different request from the one you meant. The base URL goes in front, so the request is `GET http://localhost:8089/teams?id=900001`.
- **`.retrieve()`** sends the request and checks the status. **`.body(TeamsReply.class)`** turns the JSON into the records from step 9.
- **The result is an `Optional`.** `response` is a list, even when you ask for one team by id. One entry: that's the team. No entries: the provider has no team with that id, so the answer is empty. Guide 02 looks at "not found" on API-Football more closely: it's not a `404`.

### 11. A way to see it

There's nothing to store the team in until guide 03, but you'll want to see the client work. Create `provider/ProviderLookupController.java`:

```java
package com.example.importer.provider;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Shows what the provider says about a team, without storing anything. Handy for checking the connection. */
@RestController
public class ProviderLookupController {

    private final ApiFootballClient client;

    public ProviderLookupController(ApiFootballClient client) {
        this.client = client;
    }

    @GetMapping("/provider/teams/{id}")
    public ResponseEntity<TeamItem> team(@PathVariable long id) {
        return ResponseEntity.of(client.fetchTeam(id));
    }
}
```

**`ResponseEntity.of(optional)`** is `200` with the team as the body when the `Optional` has one, and `404` with no body when it's empty. The controller returns the provider's own `TeamItem`, so the JSON shows exactly what the importer understood of the reply. It's a tool for **you**: "what does the provider say about team 900001, as the importer sees it?" It stays in later guides for checking the connection.

`/provider/teams/{id}` is open to anyone who can reach port 8080, and every call to it spends a provider request. Fine on your laptop; guide 05 adds security.

### 12. Run it

Start the **fake provider first** (step 5), if it isn't still running. Then run the importer: open `ImporterApplication.java` and click the green arrow next to `main`. **Terminal alternative:** from `importer/`, `./mvnw spring-boot:run` (`.\mvnw spring-boot:run` in PowerShell).

The log, trimmed:

```
o.f.c.internal.database.base.Database    : Using H2 2.4.240 which is newer than the version Flyway has been verified with. The latest verified version of H2 is 2.3.232.
o.f.core.internal.command.DbValidate     : No migrations found. Are your locations set up correctly?
o.s.boot.tomcat.TomcatWebServer          : Tomcat started on port 8080 (http) with context path '/'
c.example.importer.ImporterApplication   : Started ImporterApplication in … seconds (process running for …)
```

Both warnings are harmless. Spring Boot 4.1 ships a newer H2 than Flyway has been tested with, and everything in this series works with it. And Flyway has no migrations to run until guide 03 adds the first one.

Ask the importer for team 900001:

```bash
curl -i http://localhost:8080/provider/teams/900001
```

```
HTTP/1.1 200
Content-Type: application/json

{"team":{"id":900001,"name":"Riverside Rovers","code":"RIV","country":"England","founded":1923,"national":false,"logo":"https://media.api-sports.io/football/teams/900001.png"},"venue":{"id":800001,"name":"Riverside Park","city":"Riverside","capacity":21000}}
```

That's two HTTP requests: curl asked the importer, and the importer asked the fake provider. Compare the two replies:

- **The envelope has gone.** `get`, `parameters`, `errors`, `results` and `paging` were never read.
- **The venue has four fields, not seven.** `address`, `surface` and `image` were dropped when the JSON became an `ApiVenue`, and so they aren't in the JSON made from it.
- **Everything else came through unchanged**, in the order of the records' components.

Now the team the fake provider doesn't know:

```bash
curl http://localhost:8080/provider/teams/1
```

```
{"timestamp":"…","status":500,"error":"Internal Server Error","path":"/provider/teams/1"}
```

A `500`, not the `404` that `ResponseEntity.of` would give for an empty `Optional`. The importer's log says why:

```
org.springframework.web.client.HttpClientErrorException$NotFound: 404 Not Found: "<EOL><EOL>  Request was not matched<EOL>…
```

WireMock answered `404` (step 5), and **`retrieve()` turns a `4xx` into an exception**. Nothing catches it, so Spring answers `500`. That's the honest result for now: the importer has no idea what to do when the provider says something unexpected. Guide 02 turns errors like this into the importer's own exceptions, with a sensible answer for each, and adds a fake-provider fixture that answers unknown ids the way the real API does.

### 13. Tests

The tests use **WireMock again**, but not the one in `fake-provider/`: each test class starts its **own** WireMock server inside the test, on a random free port, and tells it what to answer in Java. So the tests don't need the fake provider running, can't be broken by someone editing its files, and never touch the network.

**One annotation for every Spring test.** Every test class that starts the application needs the same setup, so bundle it into a **meta-annotation**. Create `src/test/java/com/example/importer/ImporterIntegrationTest.java`:

```java
package com.example.importer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/** The whole app, with an in-memory database instead of ./data/importer. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:importer-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
public @interface ImporterIntegrationTest {
}
```

- **`@SpringBootTest`** starts the whole application, with one property changed: an **in-memory** H2 database instead of the file in `./data`. Tests start with nothing, leave nothing behind, and don't fight a running importer for the database file. `DB_CLOSE_DELAY=-1` keeps it alive for as long as the tests run.
- **`@AutoConfigureMockMvc`** sets up **`MockMvcTester`**, which sends requests to your controllers without a real server. Note the package: in Spring Boot 4 it's `org.springframework.boot.webmvc.test.autoconfigure`.
- Put `@ImporterIntegrationTest` on a test class and it gets all of that.

**Replies in the provider's shape.** The tests need API-Football-shaped JSON, with different values each time. Create `src/test/java/com/example/importer/provider/ProviderJson.java`:

```java
package com.example.importer.provider;

/** Replies in API-Football's shape, for the tests' pretend provider. */
public final class ProviderJson {

    private ProviderJson() {
    }

    public static String team(long id, String name, String code, String country, boolean national) {
        return """
                {"get":"teams","parameters":{"id":"%d"},"errors":[],"results":1,"paging":{"current":1,"total":1},
                 "response":[{"team":{"id":%d,"name":"%s","code":%s,"country":"%s","founded":1923,"national":%b,
                   "logo":"https://media.api-sports.io/football/teams/%d.png"},
                  "venue":{"id":800001,"name":"Riverside Park","address":"1 River Road","city":"Riverside",
                   "capacity":21000,"surface":"grass","image":"https://media.api-sports.io/football/venues/800001.png"}}]}
                """.formatted(id, id, name, code == null ? "null" : "\"" + code + "\"", country, national, id);
    }

    public static String noTeam(long id) {
        return """
                {"get":"teams","parameters":{"id":"%d"},"errors":[],"results":0,"paging":{"current":1,"total":1},"response":[]}
                """.formatted(id);
    }
}
```

`team(...)` is the same reply as the fake provider's file, with the values as parameters. A `null` code is written as JSON `null`, not as the text `"null"`. `noTeam(...)` is what API-Football really sends for an id it doesn't have: the envelope, `results: 0`, and an empty `response`. (Guide 02's fake provider learns to send it too.)

**The fail-fast check.** It's in a record's constructor, so it needs no Spring at all. Create `src/test/java/com/example/importer/provider/ApiFootballPropertiesTest.java`:

```java
package com.example.importer.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

class ApiFootballPropertiesTest {

    @Test
    void refusesTheRealApiWithoutAKey() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new ApiFootballProperties("https://v3.football.api-sports.io", ""))
                .withMessageContaining("APISPORTS_KEY");
    }

    @Test
    void acceptsTheRealApiWithAKey() {
        assertThat(new ApiFootballProperties("https://v3.football.api-sports.io", "abc123").hasKey()).isTrue();
    }

    @Test
    void theFakeProviderNeedsNoKey() {
        assertThat(new ApiFootballProperties("http://localhost:8089", "").hasKey()).isFalse();
    }
}
```

**`assertThatIllegalStateException().isThrownBy(...)`** runs the lambda and passes only if it throws an `IllegalStateException`. **`withMessageContaining`** checks the message tells you which variable to set. The empty string `""` is what `${APISPORTS_KEY:}` gives you when the variable isn't set.

**The client, against a pretend provider.** Create `src/test/java/com/example/importer/provider/ApiFootballClientTest.java`:

```java
package com.example.importer.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.example.importer.ImporterIntegrationTest;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

@ImporterIntegrationTest
@TestPropertySource(properties = "api-football.key=test-key")
class ApiFootballClientTest {

    @RegisterExtension
    static WireMockExtension provider = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        registry.add("api-football.base-url", provider::baseUrl);
    }

    @Autowired
    ApiFootballClient client;

    @Test
    void fetchesATeamById() {
        provider.stubFor(get(urlPathEqualTo("/teams")).withQueryParam("id", equalTo("900001"))
                .willReturn(okJson(ProviderJson.team(900001, "Riverside Rovers", "RIV", "England", false))));

        TeamItem item = client.fetchTeam(900001).orElseThrow();

        assertThat(item.team().id()).isEqualTo(900001);
        assertThat(item.team().name()).isEqualTo("Riverside Rovers");
        assertThat(item.team().code()).isEqualTo("RIV");
        assertThat(item.venue().name()).isEqualTo("Riverside Park");
    }

    @Test
    void sendsTheKeyInTheHeader() {
        provider.stubFor(get(urlPathEqualTo("/teams"))
                .willReturn(okJson(ProviderJson.team(900001, "Riverside Rovers", "RIV", "England", false))));

        client.fetchTeam(900001);

        provider.verify(getRequestedFor(urlPathEqualTo("/teams"))
                .withQueryParam("id", equalTo("900001"))
                .withHeader("x-apisports-key", equalTo("test-key")));
    }

    @Test
    void anUnknownTeamIsEmpty() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(okJson(ProviderJson.noTeam(99999999))));

        assertThat(client.fetchTeam(99999999)).isEmpty();
    }
}
```

From the top:

- **`@RegisterExtension static WireMockExtension provider`** starts a WireMock server for this test class. **`wireMockConfig().dynamicPort()`** puts it on a random free port, so it never clashes with the fake provider on 8089, or with anything else. The stubs each test sets up are cleared before the next test.
- **`@DynamicPropertySource`** sets `api-football.base-url` to that server's URL, `http://localhost:<random port>`, before Spring starts. `provider::baseUrl` is asked for the value when it's needed, by which time the server is running. So the real `ProviderConfig` builds the real `RestClient`, pointed at the test's WireMock.
- **`@TestPropertySource(properties = "api-football.key=test-key")`** gives this test class a key, without any environment variable. `test-key` isn't a real key, and doesn't need to be: the pretend provider only checks it arrives.
- **`provider.stubFor(get(urlPathEqualTo("/teams")).withQueryParam("id", equalTo("900001")).willReturn(...))`** is step 4's mapping file, written in Java: a `GET` to `/teams` with `id` equal to `900001`. **`okJson(...)`** is a `200` with `Content-Type: application/json` and the given body.
- **`fetchesATeamById`** checks that values from all three levels of the JSON (the team, its code, its venue) arrive in the records. **`orElseThrow()`** unwraps the `Optional`, and fails the test if it's empty.
- **`sendsTheKeyInTheHeader`** checks the other direction: **what the importer sent**. Its stub matches any `/teams` request, and **`provider.verify(getRequestedFor(...))`** then asserts a request arrived with `id=900001` in the query string and `x-apisports-key: test-key` in its headers. If `ProviderConfig` forgot the header, this test would fail.
- **`anUnknownTeamIsEmpty`** answers with `noTeam`, the real "no such team" shape, and checks the importer turns it into an empty `Optional`.

**The controller.** Create `src/test/java/com/example/importer/provider/ProviderLookupControllerTest.java`:

```java
package com.example.importer.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.example.importer.ImporterIntegrationTest;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

@ImporterIntegrationTest
class ProviderLookupControllerTest {

    @RegisterExtension
    static WireMockExtension provider = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        registry.add("api-football.base-url", provider::baseUrl);
    }

    @Autowired
    MockMvcTester mvc;

    @Test
    void showsWhatTheProviderSaysAboutATeam() {
        provider.stubFor(get(urlPathEqualTo("/teams"))
                .willReturn(okJson(ProviderJson.team(900001, "Riverside Rovers", "RIV", "England", false))));

        assertThat(mvc.get().uri("/provider/teams/900001"))
                .hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        {"team":{"id":900001,"name":"Riverside Rovers","code":"RIV","country":"England","national":false},
                         "venue":{"name":"Riverside Park","city":"Riverside"}}
                        """);
    }

    @Test
    void anUnknownTeamIs404() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(okJson(ProviderJson.noTeam(99999999))));

        assertThat(mvc.get().uri("/provider/teams/99999999")).hasStatus(HttpStatus.NOT_FOUND);
    }
}
```

The same WireMock setup, this time with no key (the fake-provider case). **`MockMvcTester`** sends `GET /provider/teams/900001` through the whole application: controller, client, `RestClient`, the test's WireMock, and back. **`bodyJson().isLenientlyEqualTo(...)`** compares JSON, and "leniently" means the response may have **more** fields than the expected JSON lists, so the test names only the ones it cares about. **`anUnknownTeamIs404`** is the `404` from `ResponseEntity.of` that step 12 couldn't show you: the fake provider can't send the "no such team" reply yet, but the test's WireMock can.

Run the tests: right-click the `src/test/java` folder → **Run 'All Tests'**. **Terminal alternative:** `./mvnw test` (`.\mvnw test` in PowerShell).

```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
```

They pass whether or not the fake provider and the importer are running.

### 14. Your key, kept safe

This step needs no key. It shows how the key gets to the importer, and what happens when it doesn't.

**First, see the fail-fast check.** Point the importer at the real API **without** a key:

- **In IntelliJ:** stop the importer. Open **Run → Edit Configurations…**, select `ImporterApplication`, and put this in **Program arguments** (if the field isn't shown, **Modify options → Program arguments**):

  ```
  --api-football.base-url=https://v3.football.api-sports.io
  ```

  Run it.
- **Terminal alternative:** from `importer/`, make sure the variable isn't set in this terminal (`unset APISPORTS_KEY` in bash; `Remove-Item Env:APISPORTS_KEY` in PowerShell, which complains harmlessly if it wasn't set), then:

  ```bash
  ./mvnw spring-boot:run -Dspring-boot.run.arguments=--api-football.base-url=https://v3.football.api-sports.io
  ```

  (In PowerShell, `.\mvnw`, with the whole `-D…` argument in quotes.)

A `--` argument overrides `application.properties` for this run only. The importer refuses to start:

```
***************************
APPLICATION FAILED TO START
***************************

Description:

Failed to bind properties under 'api-football' to com.example.importer.provider.ApiFootballProperties:

    Reason: java.lang.IllegalStateException: api-football.base-url is the real API but no key is set. Set the APISPORTS_KEY environment variable.

Action:

Update your application's configuration
```

Spring couldn't create `ApiFootballProperties`, because step 7's constructor threw, and Spring Boot's failure analyser turned that into this summary. No request was sent, so no quota was spent. You'll notice the database starts before the error: Spring creates the properties record only when something needs it (the `RestClient`), and that comes later.

**Then, put the key where the importer will find it.** If you have an API-Football key, there are two places to set `APISPORTS_KEY`. Both last only as long as you need them, and neither is a file in the project.

**In IntelliJ:** in the same run configuration, put this in **Environment variables** (if the field isn't shown, **Modify options → Environment variables**):

```
APISPORTS_KEY=your-key-here
```

IntelliJ passes it to the importer whenever you use this run configuration. It keeps run configurations in `.idea/workspace.xml`, in the project folder but on your machine only: `.idea` is in the generated `.gitignore`. Don't tick **Store as project file**, which writes the configuration somewhere meant to be shared.

**In a terminal**, for this terminal window only. Typing `export APISPORTS_KEY=…` would work, but the key would end up in your shell's history file. Have the shell ask for it instead:

```bash
read -rsp "API-Football key: " APISPORTS_KEY && export APISPORTS_KEY
```

In PowerShell:

```powershell
$env:APISPORTS_KEY = Read-Host "API-Football key"
```

Paste the key when asked. It's kept in this window's environment, and goes when you close it. To check it's set without printing it: `[ -n "$APISPORTS_KEY" ] && echo set` in bash, or `if ($env:APISPORTS_KEY) { "set" }` in PowerShell. Programs started from this window, such as `./mvnw spring-boot:run`, get it; other windows don't.

**Keeping it out of logs.** The key could still leak the way most keys do: by being printed. The importer doesn't log requests, headers or the properties record, and the fail-fast message names the variable rather than the value. If you ever add request logging (to debug a provider, say), make it print `x-apisports-key: ****` rather than the header's value, and remember step 7's warning about a record's `toString()`. [Guide 08](08-when-the-provider-fails.md) does exactly that, with a test that proves the key never reaches the log.

**When you're not using the real API, remove both** the program argument and the environment variable from the run configuration. Otherwise the next time you run the importer, every request goes to API-Football and spends quota, and the key goes along to the fake provider too.

### 15. One call to the real API (optional)

**This step is optional, and the guides never depend on it.** It costs **1 of your 100 requests** for today. The daily count resets at midnight UTC, according to API-Football's documentation.

**It wasn't run on the machine these guides were built on** (there was no key), so there's no captured output from the importer here. What follows is what the importer should do, based on the code and on API-Football's real replies.

1. Make sure the run configuration has both the program argument `--api-football.base-url=https://v3.football.api-sports.io` and `APISPORTS_KEY` (step 14), and run the importer. **Terminal alternative:** in the window where you set `APISPORTS_KEY`, from `importer/`:

   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.arguments=--api-football.base-url=https://v3.football.api-sports.io
   ```

   (In PowerShell, `.\mvnw`, with the whole `-D…` argument in quotes, as in step 14.) This time it starts, because there's a key. The fake provider doesn't need to be running.

2. Ask for a real team. Celtic is team `247`:

   ```bash
   curl -i http://localhost:8080/provider/teams/247
   ```

3. The importer sends `GET https://v3.football.api-sports.io/teams?id=247` with your key in the header. **This is the reply API-Football sent when the series was planned**:

   ```json
   {"get":"teams","parameters":{"id":"247"},"errors":[],"results":1,"paging":{"current":1,"total":1},
    "response":[{"team":{"id":247,"name":"Celtic","code":"CEL","country":"Scotland","founded":1888,"national":false,
      "logo":"https://media.api-sports.io/football/teams/247.png"},
     "venue":{"id":1386,"name":"Celtic Park","address":"18 Kerrydale Street","city":"Glasgow","capacity":60832,
      "surface":"grass","image":"https://media.api-sports.io/football/venues/1386.png"}}]}
   ```

   You should get a `200` from the importer with the same shape as Riverside Rovers in step 12: the team (`Celtic`, `CEL`, `Scotland`, `1888`), and the venue's id, name, city and capacity. **Real data changes**, so a value or two may differ from this by the time you try it. That's normal, and it's why the guides use the fake provider.

That's the only real request in this guide. Asking for one of the fake provider's ids, such as `900001`, would spend another request, and get nothing: API-Football has no such team, so the importer would answer `404`.

Then put things back as step 14 says: remove the program argument and the environment variable (or close the terminal window), so the importer talks to the fake provider again.

If something goes wrong, the most likely reason is the key: copied with a character missing, or not set in the session the importer runs in. Guide 02 shows what API-Football's documentation says it sends back for a bad key, and it isn't what you'd expect.

---

## Your turn

### Exercise 1: a team of your own

Give the fake provider a second team: **Millbrook Town**, id `900050`, code `MIL`, from England, founded in 1902, playing at **Millbrook Lane** (venue id `800050`) in Millbrook, capacity 8,500. Then ask the **importer** for it with curl.

<details>
<summary>Solution</summary>

Copy the two files from step 4 and change the values. `fake-provider/mappings/teams-900050.json`:

```json
{
  "request": {
    "method": "GET",
    "urlPath": "/teams",
    "queryParameters": { "id": { "equalTo": "900050" } }
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
    "bodyFileName": "teams-900050.json"
  }
}
```

`fake-provider/__files/teams-900050.json`:

```json
{"get":"teams","parameters":{"id":"900050"},"errors":[],"results":1,"paging":{"current":1,"total":1},
 "response":[{"team":{"id":900050,"name":"Millbrook Town","code":"MIL","country":"England","founded":1902,"national":false,
   "logo":"https://media.api-sports.io/football/teams/900050.png"},
  "venue":{"id":800050,"name":"Millbrook Lane","address":"2 Mill Street","city":"Millbrook","capacity":8500,
   "surface":"grass","image":"https://media.api-sports.io/football/venues/800050.png"}}]}
```

Three places have the id: the mapping's `equalTo`, its `bodyFileName`, and `parameters` and `team.id` in the body. The address, surface, logo and image are up to you: the importer ignores the first three, and only passes the logo URL on.

**Restart WireMock** (<kbd>Ctrl</kbd>+<kbd>C</kbd>, then `java -jar …` again), because it reads mappings only at start-up. Then:

```bash
curl http://localhost:8080/provider/teams/900050
```

You don't need to restart the importer: nothing in it changed. If you get the `500` from step 12 instead, WireMock either wasn't restarted or didn't match: `curl -i "http://localhost:8089/teams?id=900050"` shows its closest-stub table.

Later guides add more fixtures, with other ids. Keep this one or delete it, as you like: nothing depends on it.

</details>

### Exercise 2: a national team

`ApiTeam.national` is `true` for national teams, and guide 03 depends on it arriving correctly. Add a test to `ApiFootballClientTest` that fetches a national team, **Scotland**, id `900100`, code `SCO`, and checks that `national` arrives as `true`.

<details>
<summary>Solution</summary>

```java
@Test
void fetchesANationalTeam() {
    provider.stubFor(get(urlPathEqualTo("/teams")).withQueryParam("id", equalTo("900100"))
            .willReturn(okJson(ProviderJson.team(900100, "Scotland", "SCO", "Scotland", true))));

    TeamItem item = client.fetchTeam(900100).orElseThrow();

    assertThat(item.team().name()).isEqualTo("Scotland");
    assertThat(item.team().national()).isTrue();
}
```

`ProviderJson.team`'s last argument becomes `"national":true` in the JSON. If someone renamed the record component, say to `isNational`, the value would no longer arrive, and this test would fail. That's the cost of Jackson quietly ignoring fields it has no place for: for a `String` or `Long` component, a misspelt name and a field the provider didn't send look exactly the same, `null`. A test for each field you rely on is how you notice.

</details>

### Exercise 3: will it start?

Without running anything, decide whether the importer **starts** with each of these, and if it does, whether it **sends the key**:

| | `api-football.base-url` | `APISPORTS_KEY` |
| --- | --- | --- |
| a | `http://localhost:8089` | not set |
| b | `http://localhost:8089` | `abc123` |
| c | `https://v3.football.api-sports.io` | `abc123` |
| d | `https://v3.football.api-sports.io` | three spaces |
| e | `https://v3.football.api-sports.io/` (a slash at the end) | not set |
| f | `http://v3.football.api-sports.io` (`http`, not `https`) | not set |

Then turn **d** and **e** into tests in `ApiFootballPropertiesTest`.

<details>
<summary>Solution</summary>

| | Starts? | Sends the key? | Why |
| --- | --- | --- | --- |
| a | Yes | No | The default. Not the real API, and `hasKey()` is `false` for `""` |
| b | Yes | **Yes**, to the fake provider | The `if` in `ProviderConfig` only asks whether there's a key, not where it's going. WireMock ignores it. This is why step 14 says to remove the key when you're done |
| c | Yes | Yes | Step 15 |
| d | **No** | | `isBlank()` is `true` for spaces, so it counts as no key |
| e | **No** | | `startsWith` still matches with a slash on the end |
| f | **Yes** | No | **The check doesn't catch it.** `http://v3…` doesn't start with `https://v3…`. The requests would go to API-Football over plain HTTP, with no key, so none of them could work |

`f` shows the limit of a simple check: it guards against the mistake you expect (forgetting the key), not every mistake. The base URL comes from your own configuration, so that's a reasonable trade-off. The fix is not to type the URL by hand: copy it from the comment in `application.properties`.

The two tests:

```java
@Test
void aKeyOfOnlySpacesIsNoKey() {
    assertThatIllegalStateException()
            .isThrownBy(() -> new ApiFootballProperties("https://v3.football.api-sports.io", "   "));
}

@Test
void aSlashOnTheEndIsStillTheRealApi() {
    assertThatIllegalStateException()
            .isThrownBy(() -> new ApiFootballProperties("https://v3.football.api-sports.io/", ""));
}
```

</details>

### Exercise 4: a field we ignore

The fake provider sends each venue's `surface` (`"grass"`), and the importer drops it. Make the importer read it, check it appears in `curl http://localhost:8080/provider/teams/900001`, and then **put `ApiVenue` back as it was**. Before you undo it: which tests needed changing, and why?

<details>
<summary>Solution</summary>

Add a component to the record:

```java
public record ApiVenue(Long id, String name, String city, Integer capacity, String surface) {
}
```

Restart the importer and ask again: the venue now ends with `"surface":"grass"`, because Jackson writes the record's components in order, and now there's a fifth.

**No test needed changing.** Nothing creates an `ApiVenue` with `new`, so adding a component breaks no code, and `ProviderLookupControllerTest` compares JSON **leniently**, so an extra field in the response doesn't fail it. That's the same reason the importer doesn't break when the provider adds a field: on both sides, taking only the fields you name lets the JSON grow.

Put it back because the importer has no use for the surface. Every field you read is a field you depend on: if the provider renamed or removed it, you'd want that to matter only for fields you actually use.

</details>

---

## Checkpoint

The fake provider running, then the importer:

```bash
curl http://localhost:8080/provider/teams/900001
```

```
{"team":{"id":900001,"name":"Riverside Rovers","code":"RIV","country":"England","founded":1923,"national":false,"logo":"https://media.api-sports.io/football/teams/900001.png"},"venue":{"id":800001,"name":"Riverside Park","city":"Riverside","capacity":21000}}
```

And the tests, from `importer/` (neither needs to be running):

```bash
./mvnw test
```

```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
```

(3 in `ApiFootballPropertiesTest`, 3 in `ApiFootballClientTest` and 2 in `ProviderLookupControllerTest`. With exercises 2 and 3 you'll have 11.)

Your workspace should look like this:

```
workspace/consuming-apis/
├── fake-provider/
│   ├── .gitignore
│   ├── wiremock-standalone-3.13.2.jar
│   ├── mappings/
│   │   └── teams-900001.json
│   └── __files/
│       └── teams-900001.json
└── importer/
    ├── .gitignore
    ├── .gitattributes
    ├── mvnw, mvnw.cmd, .mvn/
    ├── pom.xml
    └── src/
        ├── main/
        │   ├── java/com/example/importer/
        │   │   ├── ImporterApplication.java
        │   │   └── provider/
        │   │       ├── ApiFootballClient.java
        │   │       ├── ApiFootballProperties.java
        │   │       ├── ApiTeam.java
        │   │       ├── ApiVenue.java
        │   │       ├── ProviderConfig.java
        │   │       ├── ProviderLookupController.java
        │   │       ├── TeamItem.java
        │   │       └── TeamsReply.java
        │   └── resources/
        │       └── application.properties
        └── test/java/com/example/importer/
            ├── ImporterIntegrationTest.java
            └── provider/
                ├── ApiFootballClientTest.java
                ├── ApiFootballPropertiesTest.java
                ├── ProviderJson.java
                └── ProviderLookupControllerTest.java
```

(Plus `data/` in `importer/` once the importer has run. It's git-ignored, and empty of tables until guide 03.)

Modified from what start.spring.io generated: `pom.xml` (`spring-boot-h2console` removed, WireMock added), `.gitignore` (`data/`), `application.properties`, `ImporterApplication.java` (`@ConfigurationPropertiesScan`). Deleted: `ImporterApplicationTests.java`.

---

## Common errors

**`curl http://localhost:8080/provider/teams/1` answers `{"timestamp":"…","status":500,"error":"Internal Server Error","path":"/provider/teams/1"}`**
The importer's log has `org.springframework.web.client.HttpClientErrorException$NotFound: 404 Not Found: "<EOL><EOL>  Request was not matched<EOL>…`. The fake provider has no mapping for that id, so WireMock answered `404`, and `retrieve()` threw. Ask WireMock directly, `curl "http://localhost:8089/teams?id=1"`, and read the closest-stub table: `Query: id = 900001 | id: 1 <<<<< Query does not match`. Use an id that has a mapping, or add one (exercise 1). If you **did** add one, restart WireMock: it reads mappings only at start-up. Guide 02 turns this into a proper answer.

**The same `500`, with `org.springframework.web.client.ResourceAccessException: I/O error on GET request for "http://localhost:8089/teams": null` in the log**
The fake provider isn't running, or not on port 8089. Start it from `fake-provider/` (step 5), **before** the importer. The `: null` is the message of the connection error underneath, which the JDK's HTTP client leaves empty. Guide 08 deals with a provider that doesn't answer.

**`APPLICATION FAILED TO START` … `Reason: java.lang.IllegalStateException: api-football.base-url is the real API but no key is set. Set the APISPORTS_KEY environment variable.`**
Working as intended: step 14's fail-fast check. Either you meant to use the real API, and `APISPORTS_KEY` isn't set **where the importer runs** (an environment variable set in one terminal window isn't seen by another, or by IntelliJ), or you didn't, and a `--api-football.base-url=…` program argument is still in the run configuration. Remove it to go back to the fake provider.

**`Database may be already in use: "…/importer/data/importer.mv.db". Possible solutions: close all other connection(s); use the server mode [90020-240]`**
Wrapped in `FlywaySqlUnableToConnectToDbException: Unable to obtain connection from database`. A second copy of the importer is starting while the first still has the database file open: perhaps one in IntelliJ and one in a terminal. Stop the other one. (This is reported before the key check, because the database starts first.)

**Start-up fails because there's no `RestClient.Builder` bean**
The **HTTP Client** dependency (`spring-boot-starter-restclient`) is missing. In Spring Boot 4 the HTTP client has its own starter, and Spring Web doesn't include it. Add it to `pom.xml`, or regenerate with it ticked.

**`Using H2 2.4.240 which is newer than the version Flyway has been verified with.`** and **`No migrations found. Are your locations set up correctly?`**
Two `WARN`s at every start, not errors. The first is a version Flyway hasn't been tested with, which works fine. The second goes away in guide 03, with the first migration.

**`Web server failed to start. Port 8080 was already in use.`**
Another application (perhaps another tutorial's) has port 8080. Stop it.

**WireMock won't start because port 8089 is taken**
Most likely by a WireMock you started earlier in another window. Use that one, or stop it first.

**In PowerShell, `curl` complains about its parameters, or prints a table of `StatusCode` and `Content`**
That's `Invoke-WebRequest`, which plain `curl` is an alias for in Windows PowerShell. Type `curl.exe`.

---

## Recap

- **Consuming an API** means working with a **provider you don't control**: you send the right request, read only what you need from the reply, and decide what to do when it's not what you expected.
- A request is a **method**, a **URL** (base URL, path, query string) and **headers**. A reply is a **status code**, **headers** and a **body**. The caller builds the first and checks the second, **status first**.
- API-Football wraps every reply in an **envelope**, with the data in a **`response` list**, even for one team by id.
- A **fake provider** (WireMock standalone, on port 8089) saves the quota, gives the same answers every time, and can be made to misbehave later. A **mapping** says which request to answer; a file in **`__files/`** is the body. Restart WireMock after changing either.
- **`RestClient`**, built once from Spring Boot's **`RestClient.Builder`** with a **base URL** and a **default header**, sends requests with **URI templates**, and turns JSON into **records** with `.retrieve().body(...)`. A `4xx` or `5xx` becomes an exception.
- Records take **only the fields you name**; Jackson ignores the rest, so the provider can add fields without breaking you. The provider's records (`Api*`) are **their** model, kept apart from ours.
- An **API key is a password**. It lives in the **`APISPORTS_KEY` environment variable**, never in a file, reaches the importer through `${APISPORTS_KEY:}`, and is **never printed**. The importer **refuses to start** when pointed at the real API without one.
- Tests run their **own WireMock on a random port**, set the base URL with **`@DynamicPropertySource`**, and can check **what was sent** as well as what came back.

### Interview angle

<details>
<summary>"How do you keep an API key or other secret out of source control?"</summary>

Never write it in a file that's in the repository, not even temporarily, because Git history keeps it after the line is deleted and repositories get shared, forked and made public. The application reads it from the environment: in Spring, a property such as `api-football.key=${APISPORTS_KEY:}`, so the committed file names the variable, not the value. On developer machines it's set per session or per run configuration; on servers it's injected from a secrets manager or the deployment platform's secret store, so each environment has its own key and it can be rotated without a code change. Keep it out of logs and error messages too, and have the application fail at start-up with a clear message if it's missing. If a key is ever committed, rotate it: removing it from history isn't enough, because you can't know who copied it.

</details>

<details>
<summary>"Why develop and test against a fake version of a third-party API?"</summary>

The real one costs requests (often limited or paid), returns data that changes, can't be made to fail on demand, and makes tests depend on the network and someone else's uptime. A fake that copies the real API's shapes exactly, such as WireMock with recorded or hand-written responses, gives repeatable answers, can simulate errors, rate limits and slow responses, and runs offline. The risk is drift: the fake can diverge from the real API, so build its responses from real ones, keep them in the real shape, and check against the real API now and then, deliberately.

</details>

<details>
<summary>"What happens when the JSON has fields your class doesn't, or is missing fields your class has?"</summary>

With Spring Boot's Jackson defaults, unknown properties are ignored, so a client that maps only the fields it needs keeps working when the provider adds fields. That's tolerant reading, and it's what you want from a provider you don't control. Missing properties are left as `null` for reference types, which is why fields that can be absent or `null` should be `String`, `Long` or `Integer` rather than primitives. The downside is that a typo in a field name fails silently: the value just never arrives. Tests that assert each field you rely on is actually populated catch that.

</details>

<details>
<summary>"RestClient, RestTemplate or WebClient?"</summary>

`RestClient` for new code in a non-reactive Spring application: it's synchronous, fluent, and uses the same message converters and error handling as the rest of Spring MVC. `RestTemplate` is the older synchronous client, still widely used and supported but in maintenance, so you'll meet it in existing code. `WebClient` is the reactive, non-blocking client, for WebFlux applications. In Spring Boot 4, `RestClient`'s auto-configured builder comes from its own starter, `spring-boot-starter-restclient`. Whichever you use, configure it once (base URL, headers, timeouts) and inject it, rather than building a client in every call.

</details>

<details>
<summary>"Why make an application refuse to start when its configuration is wrong?"</summary>

Fail fast: a missing key or invalid setting is found at deployment, by the person deploying, with a message that says exactly what's wrong, rather than hours later as a confusing failure in the middle of some work, possibly after it has already cost something (here, requests against a daily quota). In Spring Boot, validating `@ConfigurationProperties` (in a record's constructor, or with Bean Validation) does this, and the failure analyser prints a readable `APPLICATION FAILED TO START` summary.

</details>

### Further reading

- [Spring Framework reference: REST clients](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html), covering `RestClient`
- [Spring Boot reference: Calling REST services](https://docs.spring.io/spring-boot/reference/io/rest-client.html)
- [Spring Boot reference: Externalized configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html), including environment variables and `@ConfigurationProperties` records
- [WireMock: Running as a standalone process](https://wiremock.org/docs/standalone/), [Stubbing](https://wiremock.org/docs/stubbing/), [Request matching](https://wiremock.org/docs/request-matching/) and [JUnit 5+ Jupiter](https://wiremock.org/docs/junit-jupiter/)
- [API-Football documentation (v3)](https://www.api-football.com/documentation-v3)
- [OWASP Secrets Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html)
- [The Twelve-Factor App: III. Config](https://12factor.net/config), the classic argument for configuration in the environment
- [RFC 9110: HTTP Semantics](https://www.rfc-editor.org/rfc/rfc9110), for methods, status codes and headers

**Simplification to notice:** the importer trusts the reply completely. It assumes the status is `200` whenever the request worked, that the body is a list of teams, and that `errors` is empty, because it never looks. API-Football breaks all three assumptions at some point, and the next guide deals with each.

**Next:** [02: Reading the provider's replies](02-reading-the-providers-replies.md). API-Football's envelope, errors that arrive with `200 OK`, a field that's sometimes a list and sometimes an object, and "not found" without a `404`.
