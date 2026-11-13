# 04: Documenting the API

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](README.md) for version notes.

## What you'll learn

- What an **OpenAPI document** is, who reads it, and why "it compiles" isn't the same as "it's documented"
- How SpringDoc builds the document from your controllers, and where its guesses go wrong
- **Global information** for the whole API with `@OpenAPIDefinition`
- **Tags**, **operations** and stable **`operationId`s** with `@Tag` and `@Operation`
- Every status an endpoint can return with `@ApiResponse`, including **error responses** documented as `application/problem+json`
- **Request and response schemas** with `@Schema`, and the facts SpringDoc reads from your validation annotations for free
- **Query and path parameters** with `@Parameter` and `@ParameterObject`
- **Hiding internals**: what to leave out of the document, and how
- **Exporting** the document as `openapi.json` on every build, and **testing** that it says what you expect
- **Code-first vs contract-first**, and when writing the contract first is the better choice

By the end, `/v3/api-docs` describes every endpoint accurately: real status codes, real error bodies, examples that work, and names a client generator can use. A test checks the important parts, and every `./mvnw test` saves a copy to `target/openapi.json`.

---

## Prerequisites for this guide

You need the `api` project as it was at the end of [03: Collections and caching](03-collections-and-caching.md), with all 36 tests passing.

Nothing new to install. SpringDoc has been in `pom.xml` since [guide 01](01-rest-and-a-first-endpoint.md), and you've already used Swagger UI to try requests. This guide is about making what it shows *correct*.

---

## Concepts

### Who reads API documentation

A website documents itself: you can see the buttons. An API has nothing to look at. The only way another team finds out what your API does is to read about it, and they read in two ways:

- **People** read it to learn. "Which URL lists events? Which fields must I send to create one? What comes back when the venue doesn't exist?"
- **Tools** read it to do work. A client generator turns it into a Java, TypeScript or Kotlin client library. A test tool checks real responses against it. An API gateway imports it to know which routes exist. Swagger UI turns it into the page you've been using.

Both need the same thing: a **machine-readable description** of every endpoint, its parameters, its request and response bodies, and its status codes. The standard format for that is **OpenAPI**.

### OpenAPI in one page

An OpenAPI document is a JSON (or YAML) file. The parts you'll touch in this guide:

```
openapi: 3.1.0
info:          title, version and description of the whole API
servers:       where it runs
tags:          groups of operations ("Events", "Venues")
paths:
  /events/{id}:
    get:       one operation
      operationId, summary, description, tags
      parameters:   path, query and header parameters
      responses:
        "200":      description, headers, content (media type + schema)
        "404":      ...
components:
  schemas:     reusable descriptions of JSON bodies (EventResponse, Problem, ...)
```

SpringDoc 3 produces version **3.1**, which is what most tools support. (The specification has a newer minor version, but tool support lags behind it.) In 3.1, schemas are standard JSON Schema. The older **Swagger 2.0** format looks similar, and most blog posts still show it. "Swagger" today is the name of the tools (Swagger UI, Swagger Editor), not the format.

### Code-first: SpringDoc reads your controllers

There are two ways to get a document. **Code-first**: write the controllers, and let a library generate the document from them. **Contract-first** (or design-first): write the document by hand, then write or generate code that matches it. This series is code-first. The sidebar below covers the other way.

SpringDoc is code-first. At start-up it looks at every `@RestController`: the mappings, the parameter types, the return types. It knows a lot from those alone. What it *can't* know is anything that only happens at run time:

| What SpringDoc sees | What it can't see |
|---|---|
| `@PostMapping` returning `ResponseEntity<EventResponse>` | That you call `ResponseEntity.created(...)`, so the status is `201`, not `200` |
| A method that may throw `NotFoundException` | That `ApiExceptionHandler` turns it into a `404` with a `ProblemDetail` body |
| `@NotBlank String title` | What a good example title is |
| A method called `list` | That `list` is a poor name for a client library's method |

Everything in the right-hand column is your job, and the annotations in this guide are how you do it. They come from the **Swagger annotations** library (`io.swagger.v3.oas.annotations`), which SpringDoc brings in.

> **Sidebar: contract-first, and when it's the better choice.** In contract-first development, the OpenAPI document is written *before* any code, usually in YAML, in an editor such as Swagger Editor or an IDE plugin. Teams review it like any other design document. Then a generator such as **OpenAPI Generator** turns it into code: for a Spring server, its `spring` generator (run from the `openapi-generator-maven-plugin` on every build) creates the DTOs and a Java **interface** per tag, with every mapping annotation already on it, and your controllers implement those interfaces. The same contract generates the clients.
>
> Contract-first is the better choice when **the contract matters more than any one implementation**:
> - Several teams start at once. The mobile team can build against a mock of the contract while the API is still being written.
> - The API is public, or a partner integrates with it, so every change has to be agreed before it's made.
> - An organisation has API design standards (naming, paging, errors) and reviews every API against them.
> - The same API is implemented more than once, or in more than one language.
>
> Code-first is simpler when one team owns both the API and its main client, and the API changes often. You can't forget to update the document, because it *is* the code. The risk is the opposite one: the document changes whenever the code does, including by accident. The tests at the end of this guide guard against that, and the exported `target/openapi.json` is exactly what you'd compare (with a diff tool, or an OpenAPI-aware one such as `openapi-diff`) against a hand-written contract if you ever mix the two.

### The document is part of the API

Treat the document as an output of your API with the same care as its JSON. If it says `POST /events` returns `200` when it really returns `201`, a generated client may treat the real response as unexpected. If two operations are both called `list`, a generator has to invent `list_1` and `list_2`, and every client that uses them breaks the day the generator numbers them differently. Stable names and accurate status codes are part of your contract with callers, just like field names.

---

## Steps

### 1. Look at what you have

Run the API (the green arrow next to `TicketsApplication.main`, or `./mvnw spring-boot:run`, `.\mvnw spring-boot:run` in PowerShell) and open `http://localhost:8080/v3/api-docs`. That's the raw document. Firefox and Chrome both have a JSON viewer; in a terminal, `curl localhost:8080/v3/api-docs` prints it on one line.

Here's a summary of what guide 03's code produces:

```
info: {"title": "OpenAPI definition", "version": "v0"}   servers: [{"url": "http://localhost:8080", "description": "Generated server url"}]
GET    /events/{id}                        operationId=get     tags=[event-controller]       responses=[200]
DELETE /events/{id}                        operationId=delete  tags=[event-controller]       responses=[200]
GET    /events                             operationId=list    params=[filter, pageable]     responses=[200]
POST   /events                             operationId=create                                responses=[200]
GET    /events/{eventId}/ticket-types      operationId=list_1  tags=[ticket-type-controller]
GET    /venues                             operationId=list_2  tags=[venue-controller]
GET    /events/{eventId}/ticket-types/{id} operationId=get_2
schemas: EventRequest, EventResponse, VenueResponse, TicketTypeRequest, TicketTypeResponse, EventPatch, EventFilter, Pageable, PageMetadata, PagedModelEventResponse
```

And the responses of `GET /events/{id}`:

```json
{"200": {"description": "OK", "content": {"*/*": {"schema": {"$ref": "#/components/schemas/EventResponse"}}}}}
```

Count the problems:

- **The title and version** are placeholders.
- **Tags** are class names (`event-controller`), which is what Swagger UI uses as section headings.
- **Operation ids** are method names, numbered when they clash: `list`, `list_1`, `list_2`. A generated client would have methods called `list1()` and `list2()`.
- **`POST /events` is documented as `200`**, and `DELETE` as `200`. Neither is true. SpringDoc can't see inside `ResponseEntity.created(...)` or `noContent()`.
- **No error responses at all.** Not one `404` or `400` in the whole document.
- **The media type is `*/*`**: "this returns something". It returns JSON.
- **`GET /events` has two parameters, `filter` and `pageable`**, both objects. A caller actually sends `city`, `category`, `from`, `to`, `page`, `size` and `sort`.

Open `http://localhost:8080/swagger-ui.html` and you'll see the same problems laid out as a page. Keep both open; you'll watch them improve.

### 2. Global information

Some of the document isn't about any one endpoint: the API's name, version, description, and where it runs. Create `web/OpenApiConfig.java`:

```java
package com.example.tickets.web;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/** The parts of the OpenAPI document that aren't about any one endpoint. */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Event Tickets API",
                version = "1.0",
                description = "Browse events, book tickets and check them in. A teaching API with fictional data."),
        servers = @Server(url = "http://localhost:8080", description = "Local development"))
public class OpenApiConfig {
}
```

- **`@OpenAPIDefinition`** fills the top of the document. SpringDoc finds it on any Spring bean, which is why the class is a `@Configuration` even though it has no methods.
- **`@Info`** is the `info` block. `version` is the version of *your API's contract*, not of Spring or of your JAR. Change it when the contract changes.
- **`@Server`** replaces the "Generated server url" guess. Swagger UI sends its **Try it out** requests to this URL. In a real system you'd list each environment here (or set it per environment), because the URL SpringDoc guesses from the incoming request is often wrong behind a proxy.

Guide 05 adds the security scheme to this same class.

### 3. Two properties

Add to `application.properties`:

```properties
# OpenAPI: only document our own controllers, and say responses are JSON (not */*)
springdoc.packages-to-scan=com.example.tickets
springdoc.default-produces-media-type=application/json
```

- **`springdoc.packages-to-scan`** limits the document to controllers in your own packages. Libraries can register controllers of their own; this keeps them out. It's the first of the "hiding internals" tools in step 9.
- **`springdoc.default-produces-media-type`** says what to write for a response when nothing more specific is known. Without it, every response is documented as `*/*`.

Restart and look at `/v3/api-docs`. The title is now **Event Tickets API**, and `GET /events/{id}` returns `application/json`. Everything else is still a guess.

### 4. Tags, operations and parameters

Start with the smallest controller. Replace `venue/VenueController.java`:

```java
package com.example.tickets.venue;

import com.example.tickets.web.ApiProblem;
import com.example.tickets.web.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/venues")
@Tag(name = "Venues", description = "Where events happen (read-only)")
public class VenueController {

    private final VenueRepository venues;

    public VenueController(VenueRepository venues) {
        this.venues = venues;
    }

    @GetMapping
    @Operation(operationId = "listVenues", summary = "List venues", description = "In name order.")
    public List<VenueResponse> list() {
        return venues.findAllByOrderByNameAsc().stream()
                .map(VenueResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getVenue", summary = "Get one venue")
    @ApiResponse(responseCode = "200", description = "The venue")
    @ApiResponse(responseCode = "404", description = "No venue with this id",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    public VenueResponse get(@Parameter(description = "The venue's id", example = "1") @PathVariable long id) {
        return venues.findById(id)
                .map(VenueResponse::from)
                .orElseThrow(() -> new NotFoundException("Venue", id));
    }
}
```

It won't compile yet: `ApiProblem` is the next step. Here's what each annotation does.

**`@Tag`** on the class puts every operation in it into a group called `Venues`. Swagger UI shows one collapsible section per tag, and generators usually make one client class per tag (`VenuesApi`). The name is for humans, so use the plural noun, not the class name.

**`@Operation`** describes one endpoint:

- **`operationId`** is the operation's **name** in every tool. A generated client calls `venuesApi.listVenues()`. Make it unique across the whole API, stable, and a verb plus a noun. Never let it default: the default is the Java method name, and renaming a method would then change your public contract without anyone noticing.
- **`summary`** is the one-liner Swagger UI shows next to the URL.
- **`description`** is the longer text. It's Markdown, so `` `startsAt` `` renders as code.

**`@ApiResponse`** documents one status code. The method can carry as many as it needs; Java allows the same annotation more than once. List the success response as well as the errors, so you can describe it too. `getVenue` has two: `200` with the venue, and `404`.

**`@Parameter`** on the `@PathVariable` adds a description and an **example**. Swagger UI fills the example into the **Try it out** form, so a reader can press **Execute** straight away and get a real answer, not a 404.

`listVenues` has no `@ApiResponse` because it can't fail in an interesting way. SpringDoc documents its `200` from the return type.

> **SpringDoc and your `@RestControllerAdvice`.** Older SpringDoc versions added a response for every `@ExceptionHandler` in your advice to *every* operation, so every endpoint claimed it could return every error. SpringDoc 3.2.0 doesn't do that in this project: every error in the document is there because an `@ApiResponse` put it there. That's more typing, but each operation lists only what it can really return. If you read an older blog post that says errors appear "automatically", that's why it doesn't match.

### 5. One schema for every error

Every error in this API is a `ProblemDetail` (from guide 01), sent as `application/problem+json`. The `404` above points at a schema called `ApiProblem`. Why not `ProblemDetail.class`, which already exists?

Because SpringDoc describes `ProblemDetail` as the Java class looks, not as the JSON looks. Here's what it produces for `ProblemDetail.class`:

```json
{"type":"object","properties":{"type":{"type":"string","format":"uri"},"title":{…},"status":{…},"detail":{…},"instance":{"type":"string","format":"uri"},"properties":{"type":"object","additionalProperties":{}}}}
```

See the last field: `"properties"`, an object. That's the Java field where `ProblemDetail` keeps extras such as your `errors` map from guide 02. On the wire, Spring writes those extras **at the top level**, `"errors": {…}`, and there's never a `properties` field. A client generated from that schema would look for validation errors in the wrong place.

So describe the real JSON with a record that exists only for documentation. Create `web/ApiProblem.java`:

```java
package com.example.tickets.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Documentation only: the JSON of every error response. Spring's ProblemDetail class
 * would be documented with a "properties" object that never appears in the JSON,
 * because its extra properties (such as "errors") are written at the top level.
 */
@Schema(name = "Problem", description = "An error, as RFC 9457 problem details (application/problem+json)")
public record ApiProblem(
        @Schema(description = "A URI identifying the kind of problem. Left out when it's about:blank")
        String type,

        @Schema(example = "Not Found")
        String title,

        @Schema(example = "404")
        int status,

        @Schema(example = "Event 999 was not found")
        String detail,

        @Schema(description = "The path of the request", example = "/events/999")
        String instance,

        @Schema(description = "For invalid requests: field name to message", example = "{\"title\": \"must not be blank\"}")
        Map<String, String> errors) {
}
```

- **Nothing creates an `ApiProblem`.** No code uses it except the annotations. Its job is to be the truth about the JSON.
- **`@Schema(name = "Problem")`** names the schema in `components.schemas`. Clients see `Problem`, which is what RFC 9457 calls it, not a Java class name.
- **`type` is described as "left out when it's about:blank"**, because that's what Spring Framework 7 does (you saw it in guide 01: a 404 body has no `type`).
- **`errors`** is a map from field name to message, exactly as guide 02's `handleMethodArgumentNotValid` writes it.

Each error response then refers to it, with the right media type:

```java
    @ApiResponse(responseCode = "404", description = "No venue with this id",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
```

`content = @Content(mediaType = …, schema = …)` says "this response has a body, of this media type, matching this schema". `implementation = ApiProblem.class` makes SpringDoc generate the schema once, under `components`, and refer to it everywhere with a `$ref`.

The code compiles again. Restart and look at `getVenue` in Swagger UI: two responses, and the `404` shows an example `Problem` body.

### 6. The events controller

`EventController` has the most to say: paging and filtering parameters, a `201` with a `Location` header, an `ETag`, a `304`, and validation errors. Replace `event/EventController.java`:

```java
package com.example.tickets.event;

import com.example.tickets.web.ApiProblem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Duration;

@RestController
@RequestMapping("/events")
@Tag(name = "Events", description = "Concerts, comedy, theatre and sport")
public class EventController {

    private final EventService events;

    public EventController(EventService events) {
        this.events = events;
    }

    @GetMapping
    @Operation(operationId = "listEvents", summary = "List events",
            description = "Soonest first by default. Sort by `startsAt` or `title`. At most 100 per page.")
    @ApiResponse(responseCode = "200", description = "A page of events")
    @ApiResponse(responseCode = "400", description = "A filter or sort that isn't allowed",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    public PagedModel<EventResponse> list(@ParameterObject EventFilter filter,
                                          @ParameterObject @PageableDefault(size = 20, sort = "startsAt")
                                          Pageable pageable) {
        return new PagedModel<>(events.search(filter, pageable).map(EventResponse::from));
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getEvent", summary = "Get one event",
            description = "Send the ETag back in `If-None-Match` to get a `304` if the event hasn't changed.")
    @ApiResponse(responseCode = "200", description = "The event",
            headers = @Header(name = "ETag", description = "The event's version"))
    @ApiResponse(responseCode = "304", description = "Not modified since the ETag in If-None-Match",
            content = @Content)
    @ApiResponse(responseCode = "404", description = "No event with this id",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    public ResponseEntity<EventResponse> get(@Parameter(description = "The event's id", example = "2")
                                             @PathVariable long id) {
        Event event = events.get(id);
        // Returning an ETag lets Spring answer If-None-Match with 304 Not Modified for us
        return ResponseEntity.ok()
                .eTag(String.valueOf(event.getVersion()))
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(EventResponse.from(event));
    }

    @PostMapping
    @Operation(operationId = "createEvent", summary = "Create an event")
    @ApiResponse(responseCode = "201", description = "Created. The Location header is the new event's URL",
            headers = @Header(name = "Location", description = "The new event's URL"))
    @ApiResponse(responseCode = "400", description = "Invalid request content",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    public ResponseEntity<EventResponse> create(@Valid @RequestBody EventRequest request) {
        Event event = events.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(event.getId())
                .toUri();
        return ResponseEntity.created(location).body(EventResponse.from(event));
    }

    @PutMapping("/{id}")
    @Operation(operationId = "replaceEvent", summary = "Replace an event",
            description = "Send every field. A field you leave out is cleared.")
    @ApiResponse(responseCode = "200", description = "The updated event")
    @ApiResponse(responseCode = "400", description = "Invalid request content",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "No event with this id",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    public EventResponse replace(@Parameter(description = "The event's id", example = "2") @PathVariable long id,
                                 @Valid @RequestBody EventRequest request) {
        return EventResponse.from(events.replace(id, request));
    }

    @PatchMapping("/{id}")
    @Operation(operationId = "updateEvent", summary = "Change some of an event's fields",
            description = "Send only the fields to change. A field you leave out keeps its value.")
    @ApiResponse(responseCode = "200", description = "The updated event")
    @ApiResponse(responseCode = "400", description = "Invalid request content",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "No event with this id",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    public EventResponse patch(@Parameter(description = "The event's id", example = "2") @PathVariable long id,
                               @Valid @RequestBody EventPatch patch) {
        return EventResponse.from(events.patch(id, patch));
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "deleteEvent", summary = "Delete an event and its ticket types")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "No event with this id",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    public ResponseEntity<Void> delete(@Parameter(description = "The event's id", example = "2")
                                       @PathVariable long id) {
        events.delete(id);
        return ResponseEntity.noContent().build();
    }
}
```

The method bodies haven't changed since guide 03. What's new:

- **The real success codes.** `createEvent` documents `201` and `deleteEvent` documents `204`. Without these, the document says `200` for both.
- **Headers.** `@ApiResponse(headers = @Header(name = "Location", …))` documents the `Location` header on the `201`, and the `ETag` on `getEvent`'s `200`. A caller reading the document now knows to look for them.
- **A response with no body.** The `304` says `content = @Content`: an empty `@Content`. Without it, SpringDoc assumes every response of this method has the method's return type as its body, and documents the `304` with an `EventResponse`. A `304` never has a body.
- **Only the errors each operation can really return.** `listEvents` can return `400` (a bad filter or sort, from guide 03) but never `404`. `deleteEvent` can return `404` but never `400`: it has no body to be invalid.
- **Descriptions say what the code alone can't**: the sort whitelist and the page size cap on `listEvents`, and that `PUT` clears fields you leave out.

**`@ParameterObject`** fixes the `filter` and `pageable` problem from step 1. Spring MVC binds `EventFilter` and `Pageable` from separate query parameters, but SpringDoc only sees two object-typed method parameters, and documents each one as a single object. `@ParameterObject` (from `org.springdoc.core.annotations`) tells it to document the object's *fields* as query parameters instead. With it, `listEvents` has `city`, `category`, `from`, `to`, `page`, `size` and `sort`. SpringDoc reads `@PageableDefault` too: the `sort` parameter shows a default of `["startsAt,ASC"]`.

The filter's parameters can carry descriptions and examples as well. Replace `event/EventFilter.java`:

```java
package com.example.tickets.event;

import io.swagger.v3.oas.annotations.Parameter;

import java.time.Instant;

/**
 * The optional filters on GET /events, bound from query parameters:
 * ?city=London&category=MUSIC&from=2030-03-01T00:00:00Z&to=2030-04-01T00:00:00Z
 */
public record EventFilter(
        @Parameter(description = "Only events in this city (any case)", example = "London")
        String city,

        @Parameter(description = "Only events of this category")
        Category category,

        @Parameter(description = "Only events starting at or after this time", example = "2030-03-01T00:00:00Z")
        Instant from,

        @Parameter(description = "Only events starting before this time", example = "2030-04-01T00:00:00Z")
        Instant to) {
}
```

`@Parameter` on a record component works because `@ParameterObject` turns each component into a parameter. The comment that said "from is inclusive and to is exclusive" has moved into the descriptions, where callers can read it. `category` needs no example: SpringDoc lists the enum's values, and Swagger UI shows a drop-down.

### 7. The ticket types controller

The same patterns again. Replace `event/TicketTypeController.java`:

```java
package com.example.tickets.event;

import com.example.tickets.web.ApiProblem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/** Ticket types are a sub-resource of an event: /events/{eventId}/ticket-types. */
@RestController
@RequestMapping("/events/{eventId}/ticket-types")
@Tag(name = "Ticket types", description = "The kinds of ticket on sale for an event")
public class TicketTypeController {

    private final TicketTypeService ticketTypes;

    public TicketTypeController(TicketTypeService ticketTypes) {
        this.ticketTypes = ticketTypes;
    }

    @GetMapping
    @Operation(operationId = "listTicketTypes", summary = "List an event's ticket types", description = "Cheapest first.")
    @ApiResponse(responseCode = "200", description = "The event's ticket types")
    @ApiResponse(responseCode = "404", description = "No event with this id",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    public List<TicketTypeResponse> list(@Parameter(description = "The event's id", example = "2")
                                         @PathVariable long eventId) {
        return ticketTypes.list(eventId).stream()
                .map(TicketTypeResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getTicketType", summary = "Get one ticket type")
    @ApiResponse(responseCode = "200", description = "The ticket type")
    @ApiResponse(responseCode = "404", description = "No such ticket type for this event",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    public TicketTypeResponse get(@Parameter(description = "The event's id", example = "2") @PathVariable long eventId,
                                  @Parameter(description = "The ticket type's id", example = "57") @PathVariable long id) {
        return TicketTypeResponse.from(ticketTypes.get(eventId, id));
    }

    @PostMapping
    @Operation(operationId = "createTicketType", summary = "Add a ticket type to an event")
    @ApiResponse(responseCode = "201", description = "Created",
            headers = @Header(name = "Location", description = "The new ticket type's URL"))
    @ApiResponse(responseCode = "400", description = "Invalid request content",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "No event with this id",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    public ResponseEntity<TicketTypeResponse> create(@Parameter(description = "The event's id", example = "2")
                                                     @PathVariable long eventId,
                                                     @Valid @RequestBody TicketTypeRequest request) {
        TicketType ticketType = ticketTypes.create(eventId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(ticketType.getId())
                .toUri();
        return ResponseEntity.created(location).body(TicketTypeResponse.from(ticketType));
    }
}
```

The examples are chosen to work together: event `2` with ticket type `57` is the seeded "Front Row" from guide 02, so **Try it out** on `getTicketType` returns a real ticket type with its defaults.

### 8. Schemas: the request and response bodies

The last part of the document describes the JSON bodies themselves, under `components.schemas`. SpringDoc builds a schema from each record's components and their types. **`@Schema`** adds what types can't say.

Replace `event/EventRequest.java`:

```java
package com.example.tickets.event;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * The body of POST /events and PUT /events/{id}. Wrapper types (Long, not long)
 * so a missing field is null and fails @NotNull, instead of silently becoming 0.
 */
@Schema(description = "A new event, or every field of an existing one")
public record EventRequest(
        @Schema(example = "Autumn Folk Night")
        @NotBlank @Size(max = 200) String title,

        @Schema(example = "MUSIC")
        @NotNull Category category,

        @Schema(description = "The id of an existing venue", example = "4")
        @NotNull Long venueId,

        @Schema(description = "When the doors open, in UTC", example = "2030-10-03T19:30:00Z")
        @NotNull Instant startsAt,

        @Schema(example = "Three folk acts.")
        @Size(max = 2000) String description) {
}
```

- **`@Schema` on the record** describes the whole body.
- **`@Schema` on a component** adds a description and an example. Swagger UI builds its example request body from these, so **Try it out** on `createEvent` starts with a body that would actually succeed: a real venue id, a date in the future.
- **The validation annotations are documentation too.** You don't repeat them in `@Schema`. SpringDoc reads them.

Here's the schema it generates:

```json
{"type":"object","description":"A new event, or every field of an existing one",
 "properties":{"title":{"type":"string","example":"Autumn Folk Night","maxLength":200,"minLength":0},
   "category":{"type":"string","enum":["MUSIC","COMEDY","THEATRE","SPORT"],"example":"MUSIC"},
   "venueId":{"type":"integer","format":"int64","description":"The id of an existing venue","example":4},
   "startsAt":{"type":"string","format":"date-time","description":"When the doors open, in UTC","example":"2030-10-03T19:30:00Z"},
   "description":{"type":"string","example":"Three folk acts.","maxLength":2000,"minLength":0}},
 "required":["category","startsAt","title","venueId"]}
```

`@NotNull` and `@NotBlank` became the `required` list. `@Size(max = 200)` became `maxLength`. The enum's values are listed. (`@Size` also writes `"minLength": 0`, which is harmless.) That's one reason to keep validation on the request record rather than deeper in the service: the rules the API enforces and the rules the document shows come from the same line of code.

The patch body is the one where a description matters most, because "leave it out to keep it" isn't something a schema can express. Replace `event/EventPatch.java`:

```java
package com.example.tickets.event;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * The body of PATCH /events/{id}. Every field is optional: null means "leave it as it is".
 * (So a PATCH can't clear the description. See JSON Merge Patch, RFC 7396, for a format that can.)
 */
@Schema(description = "The fields to change. Leave a field out to keep its value.")
public record EventPatch(
        @Schema(example = "Autumn Folk Night (sold out)")
        @Size(max = 200) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String title,

        Category category,

        @Schema(description = "The id of an existing venue", example = "3")
        Long venueId,

        @Schema(description = "When the doors open, in UTC", example = "2030-10-10T19:30:00Z")
        Instant startsAt,

        @Schema(example = "Now with a ceilidh band.")
        @Size(max = 2000) String description) {
}
```

The `@Pattern` becomes a `pattern` in the schema, and there's no `required` list at all.

The response bodies. Replace `event/EventResponse.java`:

```java
package com.example.tickets.event;

import com.example.tickets.venue.VenueResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "An event")
public record EventResponse(
        @Schema(example = "2") long id,
        @Schema(example = "The Paper Lanterns: Live") String title,
        @Schema(example = "MUSIC") Category category,
        VenueResponse venue,
        @Schema(description = "When the doors open, in UTC", example = "2030-01-18T19:30:00Z") Instant startsAt,
        @Schema(example = "Indie folk five-piece on their first UK tour.") String description) {

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

`venue` has no `@Schema`: it's a `VenueResponse`, which has its own schema, and SpringDoc links to it with a `$ref`. Replace `venue/VenueResponse.java`:

```java
package com.example.tickets.venue;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A venue")
public record VenueResponse(
        @Schema(example = "1") long id,
        @Schema(example = "Riverside Hall") String name,
        @Schema(example = "London") String city) {

    public static VenueResponse from(Venue venue) {
        return new VenueResponse(venue.getId(), venue.getName(), venue.getCity());
    }
}
```

`event/TicketTypeRequest.java`:

```java
package com.example.tickets.event;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** The body of POST /events/{eventId}/ticket-types. Prices are whole pence: never use double for money. */
@Schema(description = "A new kind of ticket for an event")
public record TicketTypeRequest(
        @Schema(example = "Early Bird")
        @NotBlank @Size(max = 50) String name,

        @Schema(description = "Price of one ticket in pence", example = "1800")
        @NotNull @PositiveOrZero @Max(1_000_000) Long pricePence,

        @Schema(description = "How many tickets of this type there are", example = "50")
        @NotNull @Positive @Max(100_000) Integer capacity) {
}
```

And `event/TicketTypeResponse.java`:

```java
package com.example.tickets.event;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A kind of ticket for an event")
public record TicketTypeResponse(
        @Schema(example = "57") long id,
        @Schema(example = "Front Row") String name,
        @Schema(description = "Price of one ticket in pence", example = "7500") long pricePence,
        @Schema(example = "2") int capacity,
        @Schema(description = "How many are still for sale", example = "2") int available) {

    public static TicketTypeResponse from(TicketType ticketType) {
        return new TicketTypeResponse(ticketType.getId(), ticketType.getName(), ticketType.getPricePence(),
                ticketType.getCapacity(), ticketType.getAvailable());
    }
}
```

"Price … in pence" is worth saying in both directions. A caller who sees `pricePence: 7500` and nothing else can probably guess, but a description removes the guess, and `1800` as an example on the request shows that you send pence too.

The paged list needs nothing from you: SpringDoc documents `PagedModel<EventResponse>` as a schema called `PagedModelEventResponse`, with `content` (an array of `EventResponse`) and `page` (a `PageMetadata` with `size`, `number`, `totalElements` and `totalPages`).

### 9. Hiding internals

A document should describe **what callers can use**, and nothing else. Anything extra is noise at best. At worst, it advertises an endpoint you never meant to support, and someone builds on it.

This API uses two tools for that:

- **`springdoc.packages-to-scan`** (step 3) documents only controllers under `com.example.tickets`. Anything a library registers stays out.
- **Request records instead of entities** (guide 02). The `Event` entity has a `version` field and a `TicketType` has `sold`. Neither appears in the document, because neither appears in a request or response record. Documenting entities would have published both.

There are also annotations for hiding things one at a time. Nothing in this API needs them yet, so try one and then take it out again. Add `@Hidden` (from `io.swagger.v3.oas.annotations.Hidden`) to `VenueController.get`:

```java
    @Hidden
    @GetMapping("/{id}")
    @Operation(operationId = "getVenue", summary = "Get one venue")
```

Restart and reload Swagger UI. `GET /venues/{id}` has gone from the page and from `/v3/api-docs`. **The endpoint still works**: `curl localhost:8080/venues/1` still returns Riverside Hall. `@Hidden` only changes the document. It's not security, and anyone who guesses the URL can still call it. Remove the annotation (and its import) before you carry on.

`@Hidden` also works on a whole controller class, and on a record component to leave a field out of a schema. Its relative, **`@Parameter(hidden = true)`**, hides one method parameter: useful when Spring fills a parameter in from somewhere other than the request, so callers mustn't be told to send it. (In guide 06, a controller method gets the caller's token as a parameter, and SpringDoc hides that one by itself.)

> **Sidebar: the documentation in production.** At start-up, SpringDoc logs two warnings:
>
> ```
> SpringDoc /v3/api-docs endpoint is enabled by default. To disable it in production, set the property 'springdoc.api-docs.enabled=false'
> SpringDoc /swagger-ui.html endpoint is enabled by default. To disable it in production, set the property 'springdoc.swagger-ui.enabled=false'
> ```
>
> Whether to switch them off is a real decision. For a public API, the document is part of the product, and you want it served. For an internal API, a published document is a map for an attacker, and you'd rather publish it somewhere only your own teams can read: a developer portal, or the exported file from step 10. Guide 08 comes back to this.

### 10. Test the document, and export it

The document is generated, so a change in one place can quietly change it somewhere else. Renaming a method used to change an `operationId`; removing a `@Schema` removes an example; a new SpringDoc version can change how something is described. Tests catch that, the same way your other tests catch a changed status code.

Create `src/test/java/com/example/tickets/web/OpenApiDocumentTest.java`:

```java
package com.example.tickets.web;

import com.example.tickets.TicketsIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks the generated OpenAPI document, and saves it as target/openapi.json on every build. */
@TicketsIntegrationTest
class OpenApiDocumentTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void describesTheApi() {
        var doc = mvc.get().uri("/v3/api-docs");

        assertThat(doc).hasStatusOk();
        assertThat(doc).bodyJson().extractingPath("$.info.title").isEqualTo("Event Tickets API");
        assertThat(doc).bodyJson().extractingPath("$.paths['/events'].get.operationId").isEqualTo("listEvents");
        assertThat(doc).bodyJson().extractingPath("$.paths['/events'].get.tags").isEqualTo(List.of("Events"));
    }

    @Test
    void errorsAreDocumentedAsProblemDetails() {
        var doc = mvc.get().uri("/v3/api-docs");

        assertThat(doc).bodyJson()
                .extractingPath("$.paths['/events/{id}'].get.responses['404'].content['application/problem+json'].schema.$ref")
                .isEqualTo("#/components/schemas/Problem");
        assertThat(doc).bodyJson().extractingPath("$.components.schemas.Problem.properties.errors").isNotNull();
    }

    @Test
    void requestSchemasShowWhatIsRequired() {
        var doc = mvc.get().uri("/v3/api-docs");

        assertThat(doc).bodyJson().extractingPath("$.components.schemas.EventRequest.required")
                .isEqualTo(List.of("category", "startsAt", "title", "venueId"));
    }

    @Test
    void savesTheDocumentForOtherTools() throws IOException {
        var doc = mvc.get().uri("/v3/api-docs").exchange();
        assertThat(doc).hasStatusOk();

        Path file = Path.of("target", "openapi.json");
        Files.writeString(file, doc.getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(file).content().contains("\"openapi\"");
    }
}
```

The document is just another response, so you test it with the same `MockMvcTester` as everything else:

- **JSONPath with quoted keys.** Paths like `/events/{id}` and status codes like `404` aren't valid as bare JSONPath names, so they go in brackets and quotes: `$.paths['/events/{id}'].get.responses['404']`.
- **Test what callers rely on**, not every byte. An `operationId`, a tag, the error schema, the required fields. A test that compares the entire document would fail on every harmless change and soon be ignored.
- **`errorsAreDocumentedAsProblemDetails`** pins down step 5's decision: if someone "simplifies" `ApiProblem` back to `ProblemDetail.class`, the `$ref` changes and the test says so.
- **To prove something is *absent***, such as a body on a response that mustn't have one, use `bodyJson().doesNotHavePath("$…")`. `extractingPath` is for values that are there.

**`savesTheDocumentForOtherTools`** is the export. Other tools need the document as a file: a client generator, a diff in a pull request, a developer portal. Writing it from a test means **no running server**: the test already has the whole application up, in memory, on every `./mvnw test`. The file lands in `target/openapi.json`, which the build cleans and git ignores.

> **Why not a Maven plugin?** There's a `springdoc-openapi-maven-plugin` that does the same job. To use it, Maven has to *start your application* (with the Spring Boot plugin's `start` and `stop` goals around the `integration-test` phase), call `/v3/api-docs` over HTTP, and stop it again. That's a real port, a real database, and a process to clean up, which is a common source of trouble on Windows. The test does it in-process, and asserts on the content while it's there.

The YAML form is served live too, if a tool prefers it:

```bash
curl localhost:8080/v3/api-docs.yaml
```

```
openapi: 3.1.0
info:
  title: Event Tickets API
  ...
```

### 11. Look again

Run the tests (right-click the `src/test/java` folder → **Run 'All Tests'**, or `./mvnw test`), then restart the app and reload `/v3/api-docs`. The summary now:

```
GET    /events/{id}   getEvent      [Events] responses=[200, 304, 404]
PUT    /events/{id}   replaceEvent  [Events] responses=[200, 400, 404]
DELETE /events/{id}   deleteEvent   [Events] responses=[204, 404]
PATCH  /events/{id}   updateEvent   [Events] responses=[200, 400, 404]
GET    /events        listEvents    [Events] responses=[200, 400] params=[city, category, from, to, page, size, sort]
POST   /events        createEvent   [Events] responses=[201, 400]
GET    /events/{eventId}/ticket-types       listTicketTypes  [Ticket types] [200, 404]
POST   /events/{eventId}/ticket-types       createTicketType [Ticket types] [201, 400, 404]
GET    /events/{eventId}/ticket-types/{id}  getTicketType    [Ticket types] [200, 404]
GET    /venues        listVenues    [Venues] [200]
GET    /venues/{id}   getVenue      [Venues] [200, 404]
schemas: EventRequest, Problem, EventResponse, VenueResponse, TicketTypeRequest, TicketTypeResponse, EventPatch, PageMetadata, PagedModelEventResponse
```

And `GET /events/{id}`'s responses:

```json
{"200": {"description": "The event", "headers": {"ETag": {"description": "The event's version", "style": "simple"}},
         "content": {"application/json": {"schema": {"$ref": "#/components/schemas/EventResponse"}}}},
 "304": {"description": "Not modified since the ETag in If-None-Match"},
 "404": {"description": "No event with this id",
         "content": {"application/problem+json": {"schema": {"$ref": "#/components/schemas/Problem"}}}}}
```

Compare with step 1. Every operation has a name a generator can use, real status codes, and its errors. `EventFilter` and `Pageable` have gone from the schemas, because they're query parameters now. `Problem` has arrived.

In Swagger UI, open **Events → POST /events → Try it out**. The example body is the one from `EventRequest`'s `@Schema` examples. Press **Execute**: it returns `201` with a `Location` header, exactly as documented. (From guide 05 on, it needs an access token.)

---

## Your turn

### Exercise 1: test the conditional GET's documentation

Guide 03's `ETag` and `304` are easy to break in the document: drop the `headers = …` or the empty `@Content` and nothing else fails. Add a test to `OpenApiDocumentTest` proving that `getEvent`'s `200` documents an `ETag` header, and that its `304` has no `content`.

<details>
<summary>Solution</summary>

```java
@Test
void theConditionalGetIsDocumented() {
    var doc = mvc.get().uri("/v3/api-docs");

    assertThat(doc).bodyJson()
            .extractingPath("$.paths['/events/{id}'].get.responses['200'].headers.ETag.description")
            .isEqualTo("The event's version");
    assertThat(doc).bodyJson()
            .doesNotHavePath("$.paths['/events/{id}'].get.responses['304'].content");
}
```

Try removing `content = @Content` from the `304` and run it: the second assertion fails, because SpringDoc has given the `304` an `EventResponse` body.

</details>

### Exercise 2: a better "OK"

In Swagger UI, `listVenues`'s only response is described as **OK**: SpringDoc's default, because it has no `@ApiResponse`. Give it a description that says what comes back.

<details>
<summary>Solution</summary>

```java
    @GetMapping
    @Operation(operationId = "listVenues", summary = "List venues", description = "In name order.")
    @ApiResponse(responseCode = "200", description = "Every venue")
    public List<VenueResponse> list() {
```

The response's schema doesn't change: SpringDoc still takes it from the return type, `List<VenueResponse>`. You've only replaced the description.

</details>

### Exercise 3: read the export

Open `target/openapi.json` (IntelliJ formats it with **Code → Reformat Code**). Without looking at the controllers, list every operation that can return `404`. Then explain why `listEvents` isn't one of them, even though it reads from the same table as `getEvent`.

<details>
<summary>Solution</summary>

`getEvent`, `replaceEvent`, `updateEvent`, `deleteEvent`, `listTicketTypes`, `getTicketType`, `createTicketType` and `getVenue`. Every one of them names a single resource in its URL (`/events/{id}`, `/events/{eventId}/…`, `/venues/{id}`), and that resource may not exist.

`listEvents` names a *collection*. A collection always exists; a filter that matches nothing is an empty page with `totalElements: 0`, not a 404. Its only error is a `400`, for a filter or sort the API doesn't allow.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 40, Failures: 0, Errors: 0, Skipped: 0
```

(36 from guide 03 and 4 in `OpenApiDocumentTest`. If you kept the test from exercise 1, you'll have 41.)

`target/openapi.json` exists after the run.

New files since guide 03:

```
src/main/java/com/example/tickets/
└── web/
    ├── ApiProblem.java
    └── OpenApiConfig.java
src/test/java/com/example/tickets/
└── web/
    └── OpenApiDocumentTest.java
```

Modified: `EventController.java`, `TicketTypeController.java`, `VenueController.java` (tags, operations, responses, parameters), `EventRequest.java`, `EventPatch.java`, `EventResponse.java`, `EventFilter.java`, `TicketTypeRequest.java`, `TicketTypeResponse.java`, `VenueResponse.java` (`@Schema` and `@Parameter`), `application.properties`.

---

## Common errors

**Swagger UI says `No operations defined in spec!`**
`springdoc.packages-to-scan` doesn't match your package, for example `com.example.ticket` without the `s`. The application starts normally and nothing is logged, but the document has `"paths":{},"components":{}`. Check the property against the `package` line at the top of a controller.

**`GET /events` shows a single `filter` (object) and `pageable` (object) input**
`@ParameterObject` is missing from one or both parameters. The API still works the same way; only the document is wrong. Add the annotation from `org.springdoc.core.annotations`.

**The error schema has a `properties` object, and no `errors`**
You used `ProblemDetail.class` instead of `ApiProblem.class` in `@Schema(implementation = …)`. See step 5.

**Responses are documented as `*/*`**
`springdoc.default-produces-media-type=application/json` isn't set, or is misspelt. SpringDoc doesn't warn about unknown property names.

**`POST /events` is documented as `200`**
There's no `@ApiResponse(responseCode = "201", …)`. SpringDoc can't see that the method returns `ResponseEntity.created(...)`, so it falls back to `200`. The same goes for `204` on `DELETE`.

**A `304` (or `204`) is documented with a JSON body**
The `@ApiResponse` for it has no `content = @Content`. Without it, SpringDoc gives every response of the method the method's return type as its body.

**`Cannot resolve symbol 'ParameterObject'`**
It's SpringDoc's own annotation, not a Swagger one: import `org.springdoc.core.annotations.ParameterObject`.

---

## Recap

- An **OpenAPI** document describes every operation, parameter, body and status code, for people and for tools. SpringDoc 3 produces version 3.1.
- SpringDoc is **code-first**: it generates the document from your controllers, and you add what it can't see from the code alone.
- **`@OpenAPIDefinition`** holds the global info and servers. **`@Tag`** groups operations. **`@Operation`** names and describes one, and its **`operationId`** must be unique and stable.
- **`@ApiResponse`** documents each real status, with headers such as `Location` and `ETag`. Use an empty `@Content` for responses without a body.
- Document errors as **`application/problem+json`** with a schema that matches the real JSON. `ApiProblem` exists only for that.
- **`@Schema`** adds descriptions and working examples. **Validation annotations** become `required`, `maxLength` and `pattern` for free.
- **`@ParameterObject`** documents a record's fields (or a `Pageable`) as separate query parameters, and **`@Parameter`** describes each one.
- **Hide internals** with `packages-to-scan` and records instead of entities; `@Hidden` and `@Parameter(hidden = true)` hide single items. Hiding is not security.
- **Test the document** like any other response, and **export it** from a test to `target/openapi.json`.
- **Contract-first** is the better choice when the contract matters more than one implementation: many teams, public or partner APIs, design reviews.

### Interview angle

<details>
<summary>"Code-first or contract-first: which do you prefer?"</summary>

It depends who the contract is for. Contract-first means the OpenAPI document is designed and reviewed before code exists, and code is generated from it, so it suits public or partner APIs, several teams working in parallel against mocks, and organisations with API design standards. Code-first generates the document from the implementation, so it can't drift from the code and is less work when one team owns the API and its main client. Its risk is accidental contract changes, which you control with tests on the generated document and by diffing it in code review. Saying "it depends, and here's on what" is the answer interviewers want.

</details>

<details>
<summary>"Why does the operationId matter?"</summary>

Tools use it as the operation's name: a generated client turns `listEvents` into a method called `listEvents()`. If you let it default to the Java method name, it changes when someone renames the method, and clashes get numbered (`list_1`, `list_2`) in an order that can change too. Either way every generated client breaks. So set it explicitly, make it unique, and treat a change to it as a breaking change.

</details>

<details>
<summary>"How do you document error responses?"</summary>

Use one consistent error format, ideally RFC 9457 problem details with the `application/problem+json` media type, and document it as a reusable schema. Then list on each operation only the errors it can really return, with a description of when: `404` on operations that name one resource, `400` on those that take input or filters. Check the schema matches the real JSON; for example, Spring's `ProblemDetail` class is described with a nested `properties` object that never appears on the wire.

</details>

<details>
<summary>"How do you stop the documentation going out of date?"</summary>

Generate it from the code (or the code from it), so there's one source of truth. Then test it: assert on the parts clients depend on, such as operation ids, status codes, required fields and error schemas. Export it on every build so a pull request shows the change to the contract, and so tools such as client generators and API portals always get the current version.

</details>

### Further reading

- [SpringDoc OpenAPI documentation](https://springdoc.org/)
- [OpenAPI Specification 3.1.0](https://spec.openapis.org/oas/v3.1.0.html)
- [Swagger annotations reference](https://github.com/swagger-api/swagger-core/wiki/Swagger-2.X---Annotations): every annotation used in this guide
- [OpenAPI Generator](https://openapi-generator.tech/), and its [Spring generator](https://openapi-generator.tech/docs/generators/spring/), for the contract-first sidebar
- [RFC 9457: Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457)

**Simplification to notice:** the document says what an operation does, but not yet *who* may do it. Anyone can still create, change and delete events. Next, that changes.

**Next:** [05: Securing the API: services](05-securing-the-api-services.md). An authorisation server, access tokens, and an API that checks every one.
