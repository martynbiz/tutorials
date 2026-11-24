# 02: Reading the provider's replies

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](README.md) for version notes.

## What you'll learn

- How API-Football wraps **every** reply in the same **envelope**: `get`, `parameters`, `errors`, `results`, `paging` and `response`
- Why a **`200 OK` can be a failure**, and where the provider puts the real reason
- How to read a field that is **sometimes a list and sometimes an object**, with a **custom Jackson 3 deserialiser**
- Why "not found" arrives as **`results: 0`**, not as a `404`
- How to describe the provider as a Java interface, an **HTTP interface client**, and let Spring write the class
- How to turn every kind of provider trouble into **your own exceptions**, and those into **`ProblemDetail`** responses: **`404`** when the provider has no such thing, **`502 Bad Gateway`** when the provider let us down
- How to switch the fake provider into **misbehaving** on purpose, and back again, and how to test each case against WireMock

By the end, the importer no longer believes a reply just because its status is `200`. Every reply from API-Football goes through one class that checks the envelope and gives back either the data or a clear exception, and `GET /provider/teams/{id}` answers with a `ProblemDetail` that says whose fault it was.

---

## Prerequisites for this guide

You need `importer` and `fake-provider` as they were at the end of [01: Calling an API](01-calling-an-api.md), with all **8** tests passing. In particular:

- `ApiFootballClient.fetchTeam(id)` calls `GET /teams?id={id}` with the `RestClient` from `ProviderConfig`, and returns an `Optional<TeamItem>`.
- `GET /provider/teams/{id}` shows what the provider says about a team.
- The fake provider (WireMock standalone, port **8089**) has one fixture: team `900001`, Riverside Rovers.

No API-Football account is needed. There's one optional real request in step 1, if you have a key.

---

## Concepts

### Every reply comes in an envelope

Here's what the fake provider sent back for Riverside Rovers in guide 01 (laid out over several lines here; it arrives as one):

```json
{"get":"teams","parameters":{"id":"900001"},"errors":[],"results":1,"paging":{"current":1,"total":1},
 "response":[{"team":{"id":900001,"name":"Riverside Rovers","code":"RIV","country":"England","founded":1923,"national":false,
   "logo":"https://media.api-sports.io/football/teams/900001.png"},
  "venue":{"id":800001,"name":"Riverside Park","address":"1 River Road","city":"Riverside","capacity":21000,
   "surface":"grass","image":"https://media.api-sports.io/football/venues/800001.png"}}]}
```

The team is only the `response` part. Around it is an **envelope**, and API-Football uses the same one for every endpoint: teams, squads, player profiles, all of them.

| Field | What it holds |
| --- | --- |
| `get` | Which endpoint answered: `"teams"`, `"players/squads"` |
| `parameters` | What you asked for, sent back to you, every value as a string: `{"id":"900001"}` |
| `errors` | Why the provider wouldn't do what you asked. **Empty when all is well** |
| `results` | How many entries there are in `response` |
| `paging` | Which page this is, and how many there are. Every request in this series fits on one page |
| `response` | The data. **Always a list**, even when you asked for one team by its id |

Guide 01 read only `response`, and Jackson ignored the rest. That's fine on a good day. This guide is about the other days, and for those, `errors` and `results` matter more than `response`.

### A `200 OK` that isn't a success

Ask API-Football for the squad of team `abc`, which can't be a team id. This is a real request, made with a real key while the series was being written:

```
GET /players/squads?team=abc
```

```
HTTP/1.1 200 OK
Content-Type: application/json

{"get":"players\/squads","parameters":{"team":"abc"},"errors":{"team":"The Team field must contain an integer."},"results":0,"paging":{"current":1,"total":1},"response":[]}
```

The status says everything went fine. The body says it didn't: `errors` has the reason, and `response` is empty. A `400 Bad Request` would have been the textbook answer, but this provider uses the status code to say "your request reached us and we answered it", and puts the outcome in the body. Plenty of real APIs work this way. You can't change them, so the client has to cope.

For the importer, it means **a `200` doesn't mean success**. `RestClient` only treats `4xx` and `5xx` as errors, so this reply would pass straight through as "no players". Every reply has to be checked for `errors` before anything else is believed.

(The request also appeared to count against the daily quota of 100. A mistake costs a request, which is one more reason to get requests right before you send them.)

### A field that changes type

Look at `errors` in the two replies above:

```json
"errors":[]
"errors":{"team":"The Team field must contain an integer."}
```

When there are no errors, it's an empty **array**. When there are some, it's an **object**, field name to message. That's almost certainly an accident of the language the provider is written in. In PHP, an empty list and an empty map are the same thing, and it turns into `[]` as JSON. (The `\/` in `"players\/squads"` is another PHP habit: its JSON encoder escapes slashes by default. JSON allows it, and `"players\/squads"` reads as `players/squads`.)

Java has no type that's "a list or a map", and Jackson won't guess. Declare `errors` as a `Map<String, String>` and every **successful** reply fails, because success is `[]`. Declare it as a `List` and every error fails. So the importer gets a small **custom deserialiser**: code that tells Jackson how to read that one field, whichever shape arrives. Either way, the rest of the code sees a `Map<String, String>`, empty or not.

### "Not found" isn't a `404`

Ask for a team that doesn't exist. The real API was asked exactly this while the series was being written, and answered `200 OK` with `errors` empty, `results` 0 and an empty `response`, in this shape:

```
GET /teams?id=99999999
```

```json
{"get":"teams","parameters":{"id":"99999999"},"errors":[],"results":0,"paging":{"current":1,"total":1},"response":[]}
```

`200 OK`, no errors, and nothing in `response`. Not a `404`. From the provider's point of view, you searched for teams with id `99999999` and it found none, which isn't an error. From the importer's point of view, the team you wanted doesn't exist. Only the client can turn `results: 0` into "not found", because only the client knows it asked for exactly one thing.

Guide 01's `Optional.empty()` was one way to say it. This guide turns it into an exception that says what was missing, so nobody can forget to check.

### Our own exceptions

So far, a provider problem shows up as whatever Spring throws: guide 01's Common errors showed an `HttpClientErrorException$NotFound` for a fixture that didn't exist. Code further up (storing teams in guide 03, imports in guide 05) shouldn't need to know about Spring's HTTP exceptions, or about API-Football's envelope. It needs to know **what went wrong, in our terms**. So the importer gets its own small family of exceptions:

```
ProviderException                    something went wrong talking to API-Football
├── ProviderRejectedException        it answered, usually 200 OK, but "errors" says why not
├── NotFoundAtProviderException      it answered, but has nothing with that id ("results": 0)
└── ProviderHttpException            it answered with a 4xx or 5xx status
```

**One class**, `ApiFootballClient`, knows the provider's habits and throws these. Everything else catches `ProviderException`, or one of its children when it cares about the difference.

And when the importer's own endpoint is asked about a team, each case gets an honest answer:

| API-Football says | We throw | `GET /provider/teams/{id}` answers |
| --- | --- | --- |
| `200`, no `errors`, one result | Nothing: here's the team | `200` and the team |
| `200` with something in `errors` | `ProviderRejectedException` | **`502 Bad Gateway`** |
| `200`, no `errors`, `results: 0` | `NotFoundAtProviderException` | **`404 Not Found`** |
| A `4xx` or `5xx` status | `ProviderHttpException` | **`502 Bad Gateway`** |
| Nothing at all: it isn't running, or the network is down | Spring's `ResourceAccessException`, for now | `500`. That's [guide 08](08-when-the-provider-fails.md)'s job |

**Why `502`?** `500 Internal Server Error` means "we have a bug". `502 Bad Gateway` means "we're fine, but the server we depend on gave us a bad answer". It's the status a proxy or gateway returns when the server behind it fails, and the importer is exactly that, for API-Football. Passing the provider's own `500` straight on would be wrong too: our caller would think *we* had broken. **`404`** is honest for an unknown team: the caller asked about a team, and there's no such team.

### HTTP interface clients

Guide 01 called the provider with `RestClient`, one method chain per request:

```java
restClient.get().uri("/teams?id={id}", id).retrieve().body(TeamsReply.class);
```

An **HTTP interface client** describes the same request as a method on a Java interface:

```java
@GetExchange("/teams")
ApiFootballReply<TeamItem> teams(@RequestParam("id") long id);
```

and Spring writes the class that implements it, using a `RestClient` underneath. The annotations mirror a controller's: `@GetExchange` here, `@GetMapping` there, and the same `@RequestParam`. Guide 04 adds the squad and profile requests as two more methods, so the interface ends up as a one-page summary of everything the importer asks API-Football for.

Because the same `RestClient` makes the call, everything set on it applies: the base URL, the key header, and (step 5) what to do with an error status.

> **Done the REST API series?** Its guide 09 built the scanner's `TicketsApi` the same way, with `@PostExchange`, `HttpServiceProxyFactory` and `RestClientAdapter`. Skim the Concepts and step 6 here. What's new is the generic return type and the status handler.

---

## Steps

### 1. Teach the fake provider two real replies

The fake provider only knows team `900001`. Ask it for any other team and WireMock answers with its own `404` and "Request was not matched", which the real API never does. Give it the two replies from the Concepts, copied from the real API with the ids changed.

**Any other team id → the real "unknown id" reply.** Create `fake-provider/mappings/teams-unknown.json`:

```json
{
  "priority": 9,
  "request": {
    "method": "GET",
    "urlPath": "/teams",
    "queryParameters": { "id": { "matches": "[0-9]+" } }
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
    "bodyFileName": "teams-unknown.json",
    "transformers": ["response-template"]
  }
}
```

and its body, `fake-provider/__files/teams-unknown.json` (one line):

```json
{"get":"teams","parameters":{"id":"{{request.query.id}}"},"errors":[],"results":0,"paging":{"current":1,"total":1},"response":[]}
```

- **`"matches": "[0-9]+"`** matches any id made of digits, so this stub would match `900001` too. That's what **`priority`** is for. When several stubs match a request, WireMock picks the one with the **lowest** number. Stubs without a `priority`, like `teams-900001.json`, count as `5`. This one is `9`, so it only answers when no specific team fixture does: a **catch-all**.
- **`"transformers": ["response-template"]`** turns on WireMock's **response templating** for this stub, so **`{{request.query.id}}`** in the body is replaced with the `id` from the request. The reply then says `"parameters":{"id":"99999999"}` for team `99999999`, exactly as the real one does. Without the transformer, the body would be sent as it is, braces and all. WireMock 3 standalone needs no command-line option for this: listing the transformer on the stub is enough.
- **The headers** are the same real ones as `teams-900001.json`. Guide 06 reads the rate-limit numbers, and adds fixtures where they run out.

**A squad request with a team that isn't a number → the real validation error.** Create `fake-provider/mappings/squads-validation-error.json`:

```json
{
  "request": {
    "method": "GET",
    "urlPath": "/players/squads",
    "queryParameters": { "team": { "doesNotMatch": "[0-9]+" } }
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
    "bodyFileName": "squads-validation-error.json",
    "transformers": ["response-template"]
  }
}
```

and `fake-provider/__files/squads-validation-error.json`, copied from the real reply, `\/` and all:

```json
{"get":"players\/squads","parameters":{"team":"{{request.query.team}}"},"errors":{"team":"The Team field must contain an integer."},"results":0,"paging":{"current":1,"total":1},"response":[]}
```

**Status `200`** with the reason in `errors`, as the real API does. The importer never sends a team that isn't a number (its ids are `long`s), so it will never see this reply. It's here so you can see the real shape with curl, and guide 04 adds the squads themselves alongside it.

**Load the new mappings.** WireMock reads `mappings/` when it starts. Stop it with **Ctrl+C** in its terminal and start it again, from `fake-provider/`:

```bash
java -jar wiremock-standalone-3.13.2.jar --port 8089
```

(Or leave it running and ask it to reload from disk: `curl -X POST http://localhost:8089/__admin/mappings/reset`.)

**Try them.** In PowerShell, type `curl.exe` for `curl`:

```bash
curl -i "http://localhost:8089/teams?id=99999999"
```

```
HTTP/1.1 200 OK
Content-Type: application/json
...

{"get":"teams","parameters":{"id":"99999999"},"errors":[],"results":0,"paging":{"current":1,"total":1},"response":[]}
```

(The other headers are the same as in guide 01.) And:

```bash
curl "http://localhost:8089/players/squads?team=abc"
```

```
{"get":"players\/squads","parameters":{"team":"abc"},"errors":{"team":"The Team field must contain an integer."},"results":0,"paging":{"current":1,"total":1},"response":[]}
```

`curl "http://localhost:8089/teams?id=900001"` still gets Riverside Rovers: the specific fixture wins over the catch-all.

**Optional: see it on the real API.** If you have a key in `APISPORTS_KEY` (guide 01), you can make the validation error happen for real. It uses **1** of your 100 requests for the day:

```bash
curl -i -H "x-apisports-key: $APISPORTS_KEY" "https://v3.football.api-sports.io/players/squads?team=abc"
```

In PowerShell:

```powershell
curl.exe -i -H "x-apisports-key: $env:APISPORTS_KEY" "https://v3.football.api-sports.io/players/squads?team=abc"
```

The shell puts the key in from the environment variable, so it isn't typed on the command line or saved in your shell's history. You should get `HTTP/1.1 200 OK` and the same body as the fake provider's. The `x-ratelimit-*` headers will show your own numbers.

### 2. A deserialiser for `errors`

Create `provider/ProviderErrorsDeserializer.java`:

```java
package com.example.importer.provider;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * API-Football sends "errors" as an empty ARRAY when there are none ({@code "errors":[]}) and as an
 * OBJECT when there are ({@code "errors":{"team":"The Team field must contain an integer."}}).
 * Either way, we want a map of field to message.
 */
public class ProviderErrorsDeserializer extends ValueDeserializer<Map<String, String>> {

    @Override
    public Map<String, String> deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode node = context.readTree(parser);
        Map<String, String> errors = new LinkedHashMap<>();
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> error : node.properties()) {
                errors.put(error.getKey(), text(error.getValue()));
            }
        } else if (node.isArray()) {
            // Normally empty. If it ever holds messages, keep them, numbered.
            for (int i = 0; i < node.size(); i++) {
                errors.put(String.valueOf(i), text(node.get(i)));
            }
        }
        return errors;
    }

    private static String text(JsonNode value) {
        return value.isString() ? value.asString() : value.toString();
    }
}
```

- **`ValueDeserializer<Map<String, String>>`** is Jackson's base class for "read one value of this type". Jackson calls `deserialize` when it reaches the field, with the **parser** positioned on the field's value.
- **`context.readTree(parser)`** reads that value, whatever it is, into a **`JsonNode`**: a tree you can ask questions of. That's the trick for a field with two shapes: read first, then look at what arrived.
- **An object** (`{"team":"…"}`): `node.properties()` gives each name and value, and each becomes one entry in the map.
- **An array**: always empty in every reply seen so far, so the loop normally does nothing and the map stays empty. If the provider ever puts messages in it, they're kept, under the keys `"0"`, `"1"` and so on, rather than silently dropped.
- **`text(...)`**: a message is normally a JSON string, and `asString()` gives it without the quotes. Anything else (a number, a nested object) is kept as its JSON text, so nothing is lost.
- **A `LinkedHashMap`** keeps the messages in the order the provider sent them.

**Jackson 3, not 2.** Spring Boot 4 uses Jackson 3, and most examples online are for Jackson 2. The names that differ here:

| Jackson 2 (what you'll find online) | Jackson 3 (what compiles here) |
| --- | --- |
| `com.fasterxml.jackson.databind.*`, `com.fasterxml.jackson.core.*` | `tools.jackson.databind.*`, `tools.jackson.core.*` |
| `extends JsonDeserializer<T>` | `extends ValueDeserializer<T>` |
| `deserialize(...) throws IOException` | No `throws`: Jackson 3's exceptions are unchecked |
| `node.fields()` | `node.properties()` |
| `node.isTextual()`, `node.asText()` | `node.isString()`, `node.asString()` |

The annotations you put on your own classes, such as `@JsonProperty`, are the exception: they kept their `com.fasterxml.jackson.annotation` package, so they work with both.

### 3. The envelope as a record

Create `provider/ApiFootballReply.java`:

```java
package com.example.importer.provider;

import java.util.List;
import java.util.Map;

import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * The envelope around every API-Football reply. {@code T} is the type of each entry in {@code response}.
 * ("parameters" is ignored: we know what we asked for.)
 */
public record ApiFootballReply<T>(
        String get,
        @JsonDeserialize(using = ProviderErrorsDeserializer.class) Map<String, String> errors,
        int results,
        Paging paging,
        List<T> response) {

    public ApiFootballReply {
        errors = errors == null ? Map.of() : errors;
        response = response == null ? List.of() : response;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public record Paging(int current, int total) {
    }
}
```

- **`<T>`**: one record for every endpoint. A team reply is an `ApiFootballReply<TeamItem>`, and guide 04's squad and profile replies use the same record with a different `T`.
- **`@JsonDeserialize(using = ProviderErrorsDeserializer.class)`** on the `errors` component: "read this one field with that class". Everything else is read the normal way. Note the package, `tools.jackson.databind.annotation`: unlike `@JsonProperty`, this annotation belongs to Jackson's databind, which moved in Jackson 3.
- **The compact constructor** replaces a missing or `null` `errors` or `response` with an empty one, so no other code ever has to check for `null`. It's the provider's data, and we don't get to choose what's missing.
- **`hasErrors()`** says whether the provider refused. The next steps use it.
- **`Paging`** is nested because it only means something inside the envelope. Nothing uses it yet: every request in this series gets one page.
- **`parameters` isn't mapped.** We know what we asked for. It's also very likely to be another `[]`-or-object field, for the same PHP reason as `errors`, and leaving it out means one less thing to go wrong. Jackson ignores fields with no place in the record.

The envelope replaces guide 01's stopgap. **Delete `provider/TeamsReply.java`**. `ApiFootballClient` still uses it, so the project won't compile until step 7.

### 4. Our own exceptions

Four small classes, all in the `provider` package.

`provider/ProviderException.java`, the family name:

```java
package com.example.importer.provider;

/** Something went wrong talking to API-Football. The subclasses say what. */
public class ProviderException extends RuntimeException {

    public ProviderException(String message) {
        super(message);
    }
}
```

`provider/ProviderRejectedException.java`:

```java
package com.example.importer.provider;

import java.util.Map;

/** The provider answered (usually with 200 OK) but put reasons in "errors". */
public class ProviderRejectedException extends ProviderException {

    private final Map<String, String> errors;

    public ProviderRejectedException(Map<String, String> errors) {
        super("API-Football rejected the request: " + errors);
        this.errors = Map.copyOf(errors);
    }

    public Map<String, String> errors() {
        return errors;
    }
}
```

`provider/NotFoundAtProviderException.java`:

```java
package com.example.importer.provider;

/** The provider has nothing with that id ("results": 0). */
public class NotFoundAtProviderException extends ProviderException {

    public NotFoundAtProviderException(String what, long id) {
        super("API-Football has no " + what + " with id " + id);
    }
}
```

`provider/ProviderHttpException.java`:

```java
package com.example.importer.provider;

import org.springframework.http.HttpStatusCode;

/** The provider answered with an HTTP error status (4xx or 5xx). */
public class ProviderHttpException extends ProviderException {

    private final HttpStatusCode status;

    public ProviderHttpException(HttpStatusCode status) {
        super("API-Football answered HTTP " + status.value());
        this.status = status;
    }

    public HttpStatusCode status() {
        return status;
    }
}
```

- **Unchecked** (`RuntimeException`), like Spring's own exceptions. Nearly every caller would only pass a checked exception on, so `throws` clauses everywhere would add nothing.
- **The messages are written for a person**: `API-Football has no team with id 99999999` says what was missing, and `API-Football rejected the request: {team=The Team field must contain an integer.}` includes the provider's own reasons. Step 8 puts them in front of whoever called the importer.
- **Each keeps what a caller might act on**: the `errors` map, or the HTTP status. Guide 06 needs the status, to tell a `429` from the rest.
- **`Map.copyOf(errors)`** keeps an unchangeable copy, so nobody can change the exception's errors after it was thrown.

### 5. HTTP error statuses become ours

Out of the box, `RestClient` throws an `HttpClientErrorException` for a `4xx` and an `HttpServerErrorException` for a `5xx`: Spring's exceptions, with a subclass for each status. A **status handler** on the builder replaces that, for every request the client makes. In `provider/ProviderConfig.java`, add it to the `RestClient` bean, just before `build()`:

```java
        // Any 4xx or 5xx becomes our own exception, instead of Spring's HttpClientErrorException and friends
        builder.defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
            throw new ProviderHttpException(response.getStatusCode());
        });
```

- **`HttpStatusCode::isError`** decides which responses this handler deals with: `isError()` is true for any `4xx` or `5xx`.
- **The lambda** is called with the request and the response, and throws our exception with the status. Whatever it throws comes out of the call that made the request.
- **`defaultStatusHandler`** sets it once, on the builder, so it applies to every request. That includes the requests the HTTP interface makes (next step), because they go through this same `RestClient`.

**What it doesn't catch.** A `200` with errors in the body: as far as HTTP is concerned, that's a success. Step 7 deals with it. And a provider that doesn't answer at all: no response, no status, so no status handler. That's still Spring's `ResourceAccessException`, and it's [guide 08](08-when-the-provider-fails.md)'s job.

### 6. The provider as a Java interface

Create `provider/ApiFootball.java`:

```java
package com.example.importer.provider;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

/** The API-Football endpoints we use, as a Java interface. Spring writes the implementation. */
public interface ApiFootball {

    @GetExchange("/teams")
    ApiFootballReply<TeamItem> teams(@RequestParam("id") long id);
}
```

- **`@GetExchange("/teams")`** sends a `GET` to `/teams`, after the `RestClient`'s base URL. (There's a `@PostExchange`, `@PutExchange` and so on for the other methods.)
- **`@RequestParam("id")`** adds the argument to the URL as `?id=900001`. Give the name in the brackets: it's the provider's name for the parameter, and it shouldn't depend on what the Java parameter happens to be called.
- **The return type** is what the JSON body becomes, envelope and all. The generic `ApiFootballReply<TeamItem>` works as written: Spring reads the full type from the method, so the entries in `response` really are `TeamItem`s. (With `RestClient` directly, a generic type needs a `ParameterizedTypeReference`. The interface saves you that.)

Spring implements the interface with a factory. Add a second bean to `ProviderConfig`, which now looks like this in full:

```java
package com.example.importer.provider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
public class ProviderConfig {

    @Bean
    RestClient apiFootballRestClient(RestClient.Builder builder, ApiFootballProperties properties) {
        builder.baseUrl(properties.baseUrl());
        if (properties.hasKey()) {
            // Sent with every request. The fake provider doesn't need it.
            builder.defaultHeader("x-apisports-key", properties.key());
        }
        // Any 4xx or 5xx becomes our own exception, instead of Spring's HttpClientErrorException and friends
        builder.defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
            throw new ProviderHttpException(response.getStatusCode());
        });
        return builder.build();
    }

    @Bean
    ApiFootball apiFootball(RestClient apiFootballRestClient) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(apiFootballRestClient))
                .build()
                .createClient(ApiFootball.class);
    }
}
```

Read the new bean from the inside out. **`RestClientAdapter.create(...)`** wraps guide 01's `RestClient`, so the interface's requests get its base URL, key header and status handler. **`HttpServiceProxyFactory`** makes implementations of HTTP interfaces, and **`createClient(ApiFootball.class)`** returns one: an object whose `teams(900001)` sends `GET /teams?id=900001` and turns the answer into an `ApiFootballReply<TeamItem>`.

The importer's API key is still only ever in the `APISPORTS_KEY` environment variable, read in guide 01's `ApiFootballProperties`. Nothing in this guide touches it.

### 7. One class that reads every reply

`ApiFootballClient` now uses the interface, and checks the envelope before believing anything in it. Replace `provider/ApiFootballClient.java`:

```java
package com.example.importer.provider;

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

    private static <T> T single(ApiFootballReply<T> reply, String what, long id) {
        // A 200 can still be a failure: the reasons are in "errors"
        if (reply.hasErrors()) {
            throw new ProviderRejectedException(reply.errors());
        }
        // Unknown ids are not a 404: just no results
        if (reply.results() == 0 || reply.response().isEmpty()) {
            throw new NotFoundAtProviderException(what, id);
        }
        return reply.response().getFirst();
    }
}
```

- **`fetchTeam` returns a `TeamItem`, not an `Optional`.** A missing team is now an exception that says which team, and from where. Every caller gets the same rule, and nobody can forget to check.
- **`single(...)`** is for any request that asks for exactly one thing by its id. `what` is only for the message ("no **team** with id …"). It's generic, so it works for any kind of record. (Guide 04's player profiles need something slightly different: there, an empty reply means "no player", not an error.)
- **The order of the checks matters.** A rejected request *also* has `results: 0`, as both replies in the Concepts show. Check `results` first and the validation error would be reported as "no team with id …", which is wrong and hides the real reason. **Errors first, then results.**
- **`results == 0 || response().isEmpty()`**: the two should always agree. Checking both costs nothing, and means a reply that says `"results":1` with an empty list can't cause an exception later on.
- **`getFirst()`** is the first element of a list (Java 21's name for `get(0)`).

`ApiFootballClient` is now the **only** class that knows about the envelope, `errors` or `results`. Everything above it gets a `TeamItem`, or a `ProviderException`.

### 8. Problems as `ProblemDetail`s

The lookup controller no longer needs `ResponseEntity.of(...)`. In `provider/ProviderLookupController.java`, the method becomes:

```java
    @GetMapping("/provider/teams/{id}")
    public TeamItem team(@PathVariable long id) {
        return client.fetchTeam(id);
    }
```

(Remove the `ResponseEntity` import.) A missing team now arrives as an exception, so something has to turn exceptions into responses. Create `web/ApiExceptionHandler.java`, in a new `web` package next to `provider`:

```java
package com.example.importer.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.example.importer.provider.NotFoundAtProviderException;
import com.example.importer.provider.ProviderException;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler
    ProblemDetail notFoundAtProvider(NotFoundAtProviderException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** Any other provider problem: we're fine, the service we depend on isn't. 502 Bad Gateway. */
    @ExceptionHandler
    ProblemDetail providerProblem(ProviderException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
}
```

> **Done the REST API series?** This is the same pattern as its guides 01 and 02: a `@RestControllerAdvice` returning `ProblemDetail`s. Skim to "The most specific handler wins".

- **`@RestControllerAdvice`** applies to every controller. An **`@ExceptionHandler`** method is called when a controller throws the exception type in its parameter, and what it returns becomes the response.
- **`ProblemDetail`** is Spring's version of the standard error format for HTTP APIs ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)): a JSON object with `status`, `title`, `detail` and `instance`, sent with the content type `application/problem+json`. `forStatusAndDetail` fills in the status, its usual title (`"Not Found"`, `"Bad Gateway"`) and our message. Spring fills in `instance`, the path that was asked for.
- **The most specific handler wins.** A `NotFoundAtProviderException` is also a `ProviderException`, so both methods could take it. Spring picks the closest match, so a missing team is a `404`, and **every other** `ProviderException` (rejected, HTTP error, and any kind added later) is a `502`. A new kind of provider problem gets a sensible answer without anyone touching this class.
- **`extends ResponseEntityExceptionHandler`** makes the errors Spring MVC raises itself, such as a request with the wrong HTTP method, come out as `ProblemDetail`s as well, so the importer has one error format.

> **Sidebar: the provider's words in our responses.** The `502` for a rejected request includes the provider's own messages in `detail`. For the importer, that's what you want: its only callers are you and, from guide 05, its admin, and the provider's reason is the most useful thing to show. A public API would log the provider's message and send its callers something more general instead, because the provider's text is data from another system ([guide 07](07-validating-what-you-receive.md) treats all of it as untrusted input), and it can say more about your set-up than strangers need to know. Either way, our messages never contain the API key: it's only ever in a request header.

### 9. Tests

The project compiles again. Now prove each kind of reply against WireMock, as guide 01's tests did. All of these files are in `src/test/java/com/example/importer/provider/`.

**A rejected reply.** Add a third method to `ProviderJson`, which builds replies in the provider's shape:

```java
    /** A 200 OK that is really a failure: the reasons are in "errors". */
    public static String rejected(String errorsObject) {
        return """
                {"get":"teams","parameters":{"id":"900001"},"errors":%s,"results":0,"paging":{"current":1,"total":1},"response":[]}
                """.formatted(errorsObject.strip());
    }
```

**The client.** In `ApiFootballClientTest`, `fetchTeam` no longer returns an `Optional`, so in `fetchesATeamById` drop the `.orElseThrow()`:

```java
        TeamItem item = client.fetchTeam(900001);
```

Replace `anUnknownTeamIsEmpty` with `noResultsMeansNotFound`, and add two more tests:

```java
    @Test
    void noResultsMeansNotFound() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(okJson(ProviderJson.noTeam(99999999))));

        assertThatExceptionOfType(NotFoundAtProviderException.class)
                .isThrownBy(() -> client.fetchTeam(99999999))
                .withMessage("API-Football has no team with id 99999999");
    }

    @Test
    void errorsInA200AreARejection() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(okJson(ProviderJson.rejected(
                """
                {"token":"Error/Missing application key."}
                """))));

        assertThatExceptionOfType(ProviderRejectedException.class)
                .isThrownBy(() -> client.fetchTeam(900001))
                .satisfies(e -> assertThat(e.errors()).containsEntry("token", "Error/Missing application key."));
    }

    @Test
    void anHttpErrorBecomesOurOwnException() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(serverError()));

        assertThatExceptionOfType(ProviderHttpException.class)
                .isThrownBy(() -> client.fetchTeam(900001))
                .withMessage("API-Football answered HTTP 500");
    }
```

with two more static imports:

```java
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
```

- **`assertThatExceptionOfType(X.class).isThrownBy(() -> …)`** is AssertJ's way to say "this code must throw an `X`". The test fails if it throws nothing, or something else. Then **`withMessage(...)`** checks the message exactly, and **`satisfies(e -> …)`** runs more assertions on the exception itself, here on its `errors()` map.
- **`serverError()`** is WireMock's shortcut for a `500` with no body, as `okJson(...)` is for a `200` with JSON.
- **`anHttpErrorBecomesOurOwnException`** goes through the HTTP interface, so it proves step 5's status handler works for the interface's requests too.
- **`noResultsMeansNotFound`** sends the same reply as before. Only what "not found" looks like has changed.

**The deserialiser.** It doesn't need Spring, or WireMock: give Jackson some JSON and look at what comes out. Create `ProviderErrorsDeserializerTest.java`:

```java
package com.example.importer.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class ProviderErrorsDeserializerTest {

    JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void anEmptyArrayMeansNoErrors() {
        ApiFootballReply<?> reply = mapper.readValue("""
                {"get":"teams","errors":[],"results":0,"paging":{"current":1,"total":1},"response":[]}
                """, ApiFootballReply.class);

        assertThat(reply.errors()).isEmpty();
        assertThat(reply.hasErrors()).isFalse();
    }

    @Test
    void anObjectGivesFieldAndMessage() {
        ApiFootballReply<?> reply = mapper.readValue("""
                {"get":"players\\/squads","parameters":{"team":"abc"},"errors":{"team":"The Team field must contain an integer."},
                 "results":0,"paging":{"current":1,"total":1},"response":[]}
                """, ApiFootballReply.class);

        assertThat(reply.errors()).containsExactly(entry("team", "The Team field must contain an integer."));
        assertThat(reply.get()).isEqualTo("players/squads");
    }
}
```

- **`JsonMapper.builder().build()`** is a plain Jackson 3 mapper: the class that turns JSON into objects. `readValue(json, ApiFootballReply.class)` reads the JSON into the record, and the record's `@JsonDeserialize` brings in our deserialiser.
- **`anObjectGivesFieldAndMessage`** uses the real reply from step 1. **`"players\\/squads"`** in a Java text block is `players\/squads` in the JSON, as the provider sends it, and the last assertion proves Jackson reads it as `players/squads`.
- **`containsExactly(entry(...))`**: the map has exactly that one entry, and nothing else.

**The endpoint.** In `ProviderLookupControllerTest`, replace `anUnknownTeamIs404` with a test that checks the `ProblemDetail`, and add one for a provider failure:

```java
    @Test
    void anUnknownTeamIsA404ProblemDetail() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(okJson(ProviderJson.noTeam(99999999))));

        assertThat(mvc.get().uri("/provider/teams/99999999"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().isLenientlyEqualTo("""
                        {"status":404,"title":"Not Found","detail":"API-Football has no team with id 99999999"}
                        """);
    }

    @Test
    void aProviderFailureIsA502ProblemDetail() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(serverError()));

        assertThat(mvc.get().uri("/provider/teams/900001"))
                .hasStatus(HttpStatus.BAD_GATEWAY)
                .bodyJson().isLenientlyEqualTo("""
                        {"status":502,"title":"Bad Gateway","detail":"API-Football answered HTTP 500"}
                        """);
    }
```

with `import static com.github.tomakehurst.wiremock.client.WireMock.serverError;`. **`isLenientlyEqualTo`**, as in guide 01, checks the fields it's given and ignores the rest (here, `instance`).

**Run them all.** In IntelliJ, right-click `src/test/java` and choose **Run 'All Tests'**. Or in a terminal, in `importer/`:

```bash
./mvnw test
```

(`.\mvnw test` in PowerShell.) The tests start their own WireMock on a random port, so the fake provider doesn't need to be running.

```
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
```

**Now see why the deserialiser is there.** In `ApiFootballReply`, comment out `@JsonDeserialize(using = ProviderErrorsDeserializer.class)`, and run `ProviderErrorsDeserializerTest` again (the green arrow next to the class name in IntelliJ, or `./mvnw test -Dtest=ProviderErrorsDeserializerTest`). `anEmptyArrayMeansNoErrors` fails:

```
tools.jackson.databind.exc.MismatchedInputException:
Cannot deserialize value of type `java.util.LinkedHashMap<java.lang.String,java.lang.String>` from Array value (token `JsonToken.START_ARRAY`)
 ... (through reference chain: com.example.importer.provider.ApiFootballReply["errors"])
```

Jackson knows how to read a JSON object into a `Map`, but not an array. And the reply that fails is the **successful** one, `"errors":[]`, so without the deserialiser *every* good reply from the provider would fail. Put the annotation back, and the tests pass again.

### 10. Try it, and make the provider misbehave

Start the **fake provider** (it should already be running from step 1), then the importer: in IntelliJ, the green arrow next to `main` in `ImporterApplication`, or `./mvnw spring-boot:run` in `importer/`.

**A team the provider doesn't have:**

```bash
curl -i http://localhost:8080/provider/teams/99999999
```

```
HTTP/1.1 404
Content-Type: application/problem+json

{"detail":"API-Football has no team with id 99999999","instance":"/provider/teams/99999999","status":404,"title":"Not Found"}
```

The fake provider answered `200` with `results: 0` (step 1's catch-all), and the importer turned it into an honest `404`. The fields come out in alphabetical order, and without a `type` field, as in the REST API series. Riverside Rovers, `curl http://localhost:8080/provider/teams/900001`, still works as before.

**Misbehaving on purpose.** The real API rarely fails when you want it to, so the fake provider has a third folder, next to `mappings/` and `__files/`: **`misbehaving/`**. WireMock doesn't load it at start-up (it only reads `mappings/`), so the files in it do nothing until you **switch one on**. Each has `"priority": 1`, so once it's on, it wins over every normal fixture.

Create `fake-provider/misbehaving/server-error.json`, a provider that fails every request:

```json
{
  "priority": 1,
  "request": {
    "method": "ANY",
    "urlPattern": ".*"
  },
  "response": {
    "status": 500,
    "headers": { "Content-Type": "application/json" },
    "jsonBody": { "message": "Something went wrong (fake provider: misbehaving/server-error.json)" }
  }
}
```

- **`"method": "ANY"`** and **`"urlPattern": ".*"`** match every request, whatever its method and URL.
- **`jsonBody`** is a JSON body written directly in the mapping, instead of in a file under `__files/`. The body is made up: the real API's `500` has never been seen, so the message says where it came from.

And `fake-provider/misbehaving/bad-key.json`, a provider that doesn't accept your key:

```json
{
  "priority": 1,
  "request": {
    "method": "GET",
    "urlPath": "/teams"
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
    "body": "{\"get\":\"teams\",\"parameters\":{\"id\":\"{{request.query.id}}\"},\"errors\":{\"token\":\"Error/Missing application key. Go to https://www.api-football.com/documentation-v3 to learn how to get your API application key.\"},\"results\":0,\"paging\":{\"current\":1,\"total\":1},\"response\":[]}",
    "transformers": ["response-template"]
  }
}
```

**Be aware:** this reply's wording comes from API-Football's documentation and examples people have posted, **not** from a real call made for this series. The shape (`200`, the reason in `errors`) matches the validation error that *was* seen live. For now it only covers `/teams`.

**Switch one on** by sending it to WireMock's admin API, from `fake-provider/` (so the file path is right):

```bash
curl -i -X POST http://localhost:8089/__admin/mappings -d @misbehaving/server-error.json
```

```
HTTP/1.1 201 Created
```

`-d @file` sends the file's contents as the request body. Now the provider fails, whatever you ask it:

```bash
curl -i "http://localhost:8089/teams?id=900001"
```

```
HTTP/1.1 500 Server Error
Content-Type: application/json

{"message":"Something went wrong (fake provider: misbehaving/server-error.json)"}
```

and so the importer says so:

```bash
curl -i http://localhost:8080/provider/teams/900001
```

```
HTTP/1.1 502
Content-Type: application/problem+json

{"detail":"API-Football answered HTTP 500","instance":"/provider/teams/900001","status":502,"title":"Bad Gateway"}
```

The status handler turned the `500` into a `ProviderHttpException`, and the handler made it a `502`: the importer is fine, the provider isn't.

**Switch it off** with:

```bash
curl -i -X POST http://localhost:8089/__admin/mappings/reset
```

```
HTTP/1.1 200 OK
```

**Reset** throws away every stub added over the admin API and reloads `mappings/` from disk, so the provider is back to normal. (Restarting WireMock does the same.)

**Now the bad key**, on and then off again:

```bash
curl -X POST http://localhost:8089/__admin/mappings -d @misbehaving/bad-key.json
curl http://localhost:8080/provider/teams/900001
curl -X POST http://localhost:8089/__admin/mappings/reset
```

The importer's answer, from the middle command:

```
{"detail":"API-Football rejected the request: {token=Error/Missing application key. Go to https://www.api-football.com/documentation-v3 to learn how to get your API application key.}","instance":"/provider/teams/900001","status":502,"title":"Bad Gateway"}
```

A `200` from the provider, and still a `502` from us, with the provider's reason in `detail`. Without step 7's check, the importer would have reported "no team with id 900001", and you'd have gone looking for the wrong problem.

**In PowerShell**, the same commands, from `fake-provider\`:

```powershell
curl.exe -i -X POST http://localhost:8089/__admin/mappings -d @misbehaving/server-error.json
curl.exe -i http://localhost:8080/provider/teams/900001
curl.exe -i -X POST http://localhost:8089/__admin/mappings/reset
```

**Don't forget the reset.** A misbehaving fixture stays on until you reset or restart WireMock, and later guides add more of them (rate limits, an exhausted daily quota, a slow provider). Always switch one off when you've finished with it.

---

## Your turn

### Exercise 1: predict the answer

Without running anything, work out what `curl -i http://localhost:8080/provider/teams/900001` answers in each case: the status, and for errors, the `detail`.

1. The provider answers `200` with `"errors":[]`, `"results":1` and Riverside Rovers in `response`.
2. The provider answers `200` with `"errors":{"token":"…"}`, **and** `"results":1` with Riverside Rovers in `response`.
3. The provider answers `200` with `"errors":[]`, `"results":0` and `"response":[]`.
4. The provider answers `429 Too Many Requests`.
5. WireMock has no stub that matches, and answers with its own `404` and "Request was not matched".
6. The provider answers `200` with `"errors":["Something went wrong"]`, `"results":0`.
7. The fake provider isn't running.

<details>
<summary>Solution</summary>

1. **`200`** and the team. Nothing in `errors`, one result.
2. **`502`**, `API-Football rejected the request: {token=…}`. `single(...)` checks `errors` **first**, and doesn't look at `results` at all once there are errors. A provider that says "no" and sends data anyway isn't to be trusted with that data.
3. **`404`**, `API-Football has no team with id 900001`.
4. **`502`**, `API-Football answered HTTP 429`. The status handler treats every `4xx` and `5xx` the same for now. Guide 06 gives `429` the special treatment it deserves: wait, and try again.
5. **`502`**, `API-Football answered HTTP 404`. A `404` **from the provider** isn't the same as "not found" **for us**. This provider says "not found" with `results: 0`, so a `404` from it means we asked for a URL it doesn't have: our bug, or the wrong base URL. Passing it on to our caller as a `404` would send them looking for a missing team when the problem is ours. (You'll see this one for real in Common errors.)
6. **`502`**, `API-Football rejected the request: {0=Something went wrong}`. An array **with** something in it isn't empty, so `hasErrors()` is true. The deserialiser numbered the message `0`.
7. **`500`**, with Spring's default error body, not a `ProblemDetail`. There's no response at all, so no status handler runs, and `ResourceAccessException` isn't a `ProviderException`. That's [guide 08](08-when-the-provider-fails.md)'s job.

</details>

### Exercise 2: a rejection, end to end

`ApiFootballClientTest` proves a `200` with errors becomes a `ProviderRejectedException`. Nothing yet proves what `GET /provider/teams/{id}` answers. Add a test to `ProviderLookupControllerTest`, `aRejectionIsA502ProblemDetail`: the provider answers with `ProviderJson.rejected(...)` and the errors `{"token":"Error/Missing application key."}`, and the endpoint must answer `502` with the provider's reason in `detail`.

<details>
<summary>Solution</summary>

```java
    @Test
    void aRejectionIsA502ProblemDetail() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(okJson(ProviderJson.rejected(
                """
                {"token":"Error/Missing application key."}
                """))));

        assertThat(mvc.get().uri("/provider/teams/900001"))
                .hasStatus(HttpStatus.BAD_GATEWAY)
                .bodyJson().isLenientlyEqualTo("""
                        {"status":502,"title":"Bad Gateway",
                         "detail":"API-Football rejected the request: {token=Error/Missing application key.}"}
                        """);
    }
```

The `detail` is `ProviderRejectedException`'s message: `"API-Football rejected the request: "` followed by the map, which Java writes as `{token=…}`. The `502` comes from `providerProblem`: a `ProviderRejectedException` isn't a `NotFoundAtProviderException`, so the more general handler takes it.

</details>

### Exercise 3: the other shapes of `errors`

The deserialiser handles two cases no test covers yet. Add two tests to `ProviderErrorsDeserializerTest`:

1. `anArrayWithMessagesIsNumbered`: `errors` is `["Something went wrong","And something else"]`. The map should hold exactly `"0"` → `"Something went wrong"` and `"1"` → `"And something else"`, in that order.
2. `noErrorsFieldMeansNoErrors`: a reply with no `errors` field at all. `hasErrors()` should be false.

<details>
<summary>Solution</summary>

```java
    @Test
    void anArrayWithMessagesIsNumbered() {
        ApiFootballReply<?> reply = mapper.readValue("""
                {"get":"teams","errors":["Something went wrong","And something else"],"results":0,"paging":{"current":1,"total":1},"response":[]}
                """, ApiFootballReply.class);

        assertThat(reply.errors()).containsExactly(entry("0", "Something went wrong"), entry("1", "And something else"));
    }

    @Test
    void noErrorsFieldMeansNoErrors() {
        ApiFootballReply<?> reply = mapper.readValue("""
                {"get":"teams","results":0,"paging":{"current":1,"total":1},"response":[]}
                """, ApiFootballReply.class);

        assertThat(reply.hasErrors()).isFalse();
    }
```

The first goes through the deserialiser's array branch. `containsExactly` checks the order too, which passes because of the `LinkedHashMap`. The second never reaches the deserialiser at all: with no `errors` field, Jackson passes `null` to the record, and the compact constructor makes it `Map.of()`. Without that line in the constructor, `hasErrors()` would throw a `NullPointerException` instead.

</details>

### Exercise 4: down for maintenance

Add a third misbehaving fixture, `fake-provider/misbehaving/maintenance.json`: a provider that answers **every** request with `503 Service Unavailable` and a JSON body `{"message":"Down for maintenance (fake provider: misbehaving/maintenance.json)"}`. Switch it on, ask the importer for team `900001`, then switch it off again and check Riverside Rovers is back.

<details>
<summary>Solution</summary>

`fake-provider/misbehaving/maintenance.json`, a copy of `server-error.json` with a different status and message:

```json
{
  "priority": 1,
  "request": {
    "method": "ANY",
    "urlPattern": ".*"
  },
  "response": {
    "status": 503,
    "headers": { "Content-Type": "application/json" },
    "jsonBody": { "message": "Down for maintenance (fake provider: misbehaving/maintenance.json)" }
  }
}
```

From `fake-provider/`:

```bash
curl -i -X POST http://localhost:8089/__admin/mappings -d @misbehaving/maintenance.json
curl -i http://localhost:8080/provider/teams/900001
curl -i -X POST http://localhost:8089/__admin/mappings/reset
curl http://localhost:8080/provider/teams/900001
```

The first answers `HTTP/1.1 201 Created`. The importer then says:

```
HTTP/1.1 502
Content-Type: application/problem+json

{"detail":"API-Football answered HTTP 503","instance":"/provider/teams/900001","status":502,"title":"Bad Gateway"}
```

After the reset, the last command gets Riverside Rovers again. No Java changed: `HttpStatusCode::isError` covers `503` as it covers `500`, and every `ProviderException` that isn't "not found" is a `502`. A `503` is one a client might reasonably try again later. [Guide 08](08-when-the-provider-fails.md) decides which failures are worth it.

</details>

---

## Checkpoint

In `importer/`:

```bash
./mvnw test
```

```
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
```

That's `ApiFootballPropertiesTest` 3 (unchanged), `ApiFootballClientTest` 5, `ProviderErrorsDeserializerTest` 2 and `ProviderLookupControllerTest` 3. With the tests from exercises 2 and 3, you'll have 16.

With the fake provider and the importer running, `curl -i http://localhost:8080/provider/teams/99999999` answers `404` with a `ProblemDetail`, and `curl http://localhost:8080/provider/teams/900001` still gets Riverside Rovers.

Your files (new ones marked **new**, changed ones **changed**):

```
importer/src/
├── main/java/com/example/importer/
│   ├── ImporterApplication.java
│   ├── provider/
│   │   ├── ApiFootball.java                       new
│   │   ├── ApiFootballClient.java                 changed
│   │   ├── ApiFootballProperties.java
│   │   ├── ApiFootballReply.java                  new
│   │   ├── ApiTeam.java
│   │   ├── ApiVenue.java
│   │   ├── NotFoundAtProviderException.java       new
│   │   ├── ProviderConfig.java                    changed
│   │   ├── ProviderErrorsDeserializer.java        new
│   │   ├── ProviderException.java                 new
│   │   ├── ProviderHttpException.java             new
│   │   ├── ProviderLookupController.java          changed
│   │   ├── ProviderRejectedException.java         new
│   │   └── TeamItem.java
│   └── web/
│       └── ApiExceptionHandler.java               new
└── test/java/com/example/importer/
    ├── ImporterIntegrationTest.java
    └── provider/
        ├── ApiFootballClientTest.java             changed
        ├── ApiFootballPropertiesTest.java
        ├── ProviderErrorsDeserializerTest.java    new
        ├── ProviderJson.java                      changed
        └── ProviderLookupControllerTest.java      changed

fake-provider/
├── mappings/
│   ├── squads-validation-error.json               new
│   ├── teams-900001.json
│   └── teams-unknown.json                         new
├── __files/
│   ├── squads-validation-error.json               new
│   ├── teams-900001.json
│   └── teams-unknown.json                         new
└── misbehaving/
    ├── bad-key.json                               new
    └── server-error.json                          new
```

**Deleted:** `provider/TeamsReply.java`. (If you did exercise 4, `misbehaving/` also has `maintenance.json`. It does nothing until you switch it on, so it can stay.)

---

## Common errors

**`MismatchedInputException: Cannot deserialize value of type java.util.LinkedHashMap<…> from Array value (token JsonToken.START_ARRAY)`**
In full:

```
tools.jackson.databind.exc.MismatchedInputException:
Cannot deserialize value of type `java.util.LinkedHashMap<java.lang.String,java.lang.String>` from Array value (token `JsonToken.START_ARRAY`)
 ... (through reference chain: com.example.importer.provider.ApiFootballReply["errors"])
```

`errors` was read as a plain `Map`, without the custom deserialiser, and the reply had `"errors":[]`. That's every successful reply. Check that `@JsonDeserialize(using = ProviderErrorsDeserializer.class)` is on the `errors` component of `ApiFootballReply`, and that it's imported from `tools.jackson.databind.annotation`, not from Jackson 2's `com.fasterxml.jackson.databind.annotation`.

**`curl "http://localhost:8089/teams?id=99999999"` says `Request was not matched`**
The fake provider is answering, but without `teams-unknown.json`:

```
HTTP/1.1 404 Not Found
Content-Type: text/plain;charset=UTF-8

                                               Request was not matched
                                               =======================
...
Query: id = 900001                                         | id: 99999999                                        <<<<< Query does not match
```

WireMock reads `mappings/` only when it starts, and its closest stub is still guide 01's `900001`. Restart it, or run `curl -X POST http://localhost:8089/__admin/mappings/reset`. If it still happens, check the file is in `mappings/` (not `__files/`) and its `queryParameters` say `"matches": "[0-9]+"`. Meanwhile, the importer answers that WireMock `404` with:

```
{"detail":"API-Football answered HTTP 404","instance":"/provider/teams/99999999","status":502,"title":"Bad Gateway"}
```

a `502`, not a `404`, as exercise 1 predicted: a `404` from the provider means we asked for something it doesn't have a URL for, not that the team doesn't exist.

**Every request is a `502` with `"detail":"API-Football answered HTTP 500"`**
A misbehaving fixture is still switched on: `misbehaving/server-error.json` answers every request with a `500`. Switch it off with `curl -X POST http://localhost:8089/__admin/mappings/reset`. Likewise, a `502` with `rejected the request: {token=…}` means `bad-key.json` is still on.

**`{"timestamp":"…","status":500,"error":"Internal Server Error","path":"/provider/teams/900001"}`**
And in the importer's log:

```
org.springframework.web.client.ResourceAccessException: I/O error on GET request for "http://localhost:8089/teams": null
```

The fake provider isn't running, so there's no reply to read: no status for the status handler, and no envelope to check. Start it (`java -jar wiremock-standalone-3.13.2.jar --port 8089` in `fake-provider/`). The `: null` is the message of the connection error underneath, which the JDK's HTTP client leaves empty. Turning this into a proper answer, and deciding whether to try again, is [guide 08](08-when-the-provider-fails.md)'s job.

---

## Recap

- API-Football wraps every reply in an **envelope**: `get`, `parameters`, `errors`, `results`, `paging` and `response`. One generic record, **`ApiFootballReply<T>`**, reads it for every endpoint.
- **A `200` can be a failure.** The provider reports refused requests with `200 OK` and the reason in **`errors`**. Check `errors` in every reply, **before** anything else.
- **`errors` is `[]` when empty and an object when not.** A **custom deserialiser** (Jackson 3's `ValueDeserializer`, put on the field with `@JsonDeserialize`) reads the value as a tree first, then handles either shape, so the rest of the code sees a `Map<String, String>`.
- **An unknown id is `results: 0`, not a `404`.** Only the client knows it asked for exactly one thing, so the client turns "no results" into "not found".
- An **HTTP interface client** (`@GetExchange`, `@RequestParam`, `HttpServiceProxyFactory` over a `RestClientAdapter`) describes the provider as a Java interface, generic return types included.
- **`defaultStatusHandler`** turns every `4xx` and `5xx` into **our own exception**, for every request the `RestClient` makes. Failing to connect at all isn't a status, so it isn't covered: that's guide 08.
- **One class**, `ApiFootballClient`, knows the provider's habits. Everything above it sees data or a **`ProviderException`**: rejected, not found, or an HTTP error.
- Our endpoint answers with **`ProblemDetail`s**: **`404`** when the provider has no such thing, **`502 Bad Gateway`** when the provider let us down. Not `500`, which would mean *we* had a bug, and never the provider's own `404`, which means something else entirely.
- **Test every kind of reply** against WireMock, with fixtures copied from **real** replies. Switch the standalone fake provider into **misbehaving** with a priority-1 stub posted to `/__admin/mappings`, and back with `/__admin/mappings/reset`.

### Interview angle

<details>
<summary>"An API you depend on returns 200 OK even when the request failed. How do you deal with that?"</summary>

You don't trust the status code alone. Find out where the provider really reports failure (here, an `errors` field in a wrapper around every response) and check it in one place, the client class that talks to that provider, before any data is used. Turn it into an exception of your own that carries the provider's reason, so callers can't mistake a refusal for an empty result. Then write tests with the provider's real failure responses, captured from real calls, so the check is proven, and stays proven when someone refactors the client.

</details>

<details>
<summary>"Why wrap a third-party API's errors in your own exceptions?"</summary>

So the rest of the application depends on your concepts, not on the provider's quirks or the HTTP library's exception types. Callers catch "the provider rejected the request" or "the provider has no such item", whether that arrived as a `200` with an error body, an empty result, or a `5xx`. Changing the HTTP client, or the provider, then only changes one class. It's also the natural place to keep what callers need to act on, such as the status code for deciding whether to retry, and to leave out what they mustn't see, such as credentials.

</details>

<details>
<summary>"Your service calls another service, which fails. What status do you return?"</summary>

Not `500`: that says your service has a bug. Usually `502 Bad Gateway`, meaning the server you depend on gave you a bad or failed response, or `504 Gateway Timeout` if it didn't answer in time. Some teams use `503 Service Unavailable` instead, meaning "we can't serve this right now", especially when the caller could try again later. And don't pass the other service's status straight through: its `404` might mean you called the wrong URL, not that the thing your caller asked for doesn't exist. Return a clear error body, such as a `ProblemDetail`, that says which dependency failed.

</details>

<details>
<summary>"A JSON field can arrive as two different types. How do you read it?"</summary>

With a custom deserialiser for that field. In Jackson, extend the deserialiser base class (`ValueDeserializer` in Jackson 3, `JsonDeserializer` in Jackson 2), read the value as a tree (`JsonNode`), check whether it's an object, an array or a string, and convert each into the one type your code uses. Attach it to that field only, with `@JsonDeserialize(using = …)`, so the rest of the mapping stays normal. Test it with each real shape, including the empty one, because that's often the one that breaks.

</details>

<details>
<summary>"How do you test code that calls a third-party API?"</summary>

Never against the real API in automated tests: it costs quota, its data changes, and it's down at the worst moments. Use a fake server, such as WireMock, on a random port, and point the client at it through configuration. Stub responses copied from the real API, including the awkward ones: errors with a `200`, empty results, server errors. Then assert on what your code does with each. Verify the requests too, such as the right path, parameters and authentication header. A standalone copy of the same fake server is also useful by hand, for development and demonstrations.

</details>

### Further reading

- [API-Football v3 documentation](https://www.api-football.com/documentation-v3): the endpoints, and the envelope around their responses
- [Spring Framework reference: REST clients](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html), covering `RestClient`, its status handlers, and HTTP interface clients
- [Spring Framework reference: error responses](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html), on `ProblemDetail` and `ResponseEntityExceptionHandler`
- [WireMock: stubbing](https://wiremock.org/docs/stubbing/) (including stub priority), [request matching](https://wiremock.org/docs/request-matching/) and [response templating](https://wiremock.org/docs/response-templating/)
- [RFC 9457: Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457), and [RFC 9110 section 15.6.3: `502 Bad Gateway`](https://www.rfc-editor.org/rfc/rfc9110#section-15.6.3)
- [Jackson on GitHub](https://github.com/FasterXML/jackson), for what changed in Jackson 3

**Simplification to notice:** `parameters` isn't read at all, and `paging` is read but never used. Every request in this series asks for one team, one squad or one player, so the reply always fits on one page. An importer that searched (all the teams in a league, say) would need to follow `paging.total` and ask for each page, and every page would cost a request.

---

**Next:** [03: Storing teams](03-storing-teams.md). The importer keeps its own copy of each team, in its own database: our model, not theirs, created or updated by the provider's id, with the HTTP call kept outside the database transaction.
