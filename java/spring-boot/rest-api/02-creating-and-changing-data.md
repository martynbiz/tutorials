# 02: Creating and changing data

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](README.md) for version notes.

## What you'll learn

- How `POST`, `PUT`, `PATCH` and `DELETE` map onto creating, replacing, changing and deleting a resource
- Why `POST` answers **`201 Created`** with a **`Location`** header, and `DELETE` answers **`204 No Content`**
- Why an API has three kinds of object for one thing: the **entity**, the **request DTO** and the **response DTO**
- How **Bean Validation** (`@Valid`, `@NotBlank`, `@NotNull`, …) checks request bodies before your code sees them
- How to make every kind of bad request (invalid fields, malformed JSON, a wrong type, the wrong method or content type) come back as the **same** `ProblemDetail` shape, with a list of the fields that are wrong
- The difference between **`PUT`** and **`PATCH`**, and what **JSON Merge Patch** ([RFC 7396](https://www.rfc-editor.org/rfc/rfc7396)) would add
- How to model a **sub-resource**: an event's ticket types at `/events/{eventId}/ticket-types`

By the end, the API can create, replace, update and delete events and add ticket types to them, and anything a client gets wrong comes back as a `400`, `404`, `405` or `415` `ProblemDetail` that says what to fix.

---

## Prerequisites for this guide

- Guide 01 finished, with its 7 tests passing.
- From the todo app: you've used `@Valid` with a form-backing object and `BindingResult`. Here the same annotations check JSON instead of a form.

---

## Concepts

### Four methods, four jobs

Guide 01's table of methods becomes real in this guide:

| Request | Means | Success response |
| --- | --- | --- |
| `POST /events` | Create a new event in the collection; the **server** picks its id | `201 Created`, a `Location` header, and the new event |
| `PUT /events/29` | **Replace** event 29 with the one in the body | `200 OK` and the event as it is now |
| `PATCH /events/29` | **Change part** of event 29 | `200 OK` and the event as it is now |
| `DELETE /events/29` | Delete event 29 | `204 No Content`, no body |

**`201 Created` and `Location`.** When a `POST` creates something, the client needs to know where it now lives. HTTP's answer is the `201` status and a `Location` header with the new resource's URL ([RFC 9110, section 15.3.2](https://www.rfc-editor.org/rfc/rfc9110#section-15.3.2)). This API also sends the new event in the body, so the client doesn't need a second request to see the id and the venue details.

```
HTTP/1.1 201
Location: http://localhost:8080/events/29
```

Whatever `Location` points at must be something the client can `GET`. That's why this guide adds `GET /events/{eventId}/ticket-types/{id}` too: without it, the `Location` for a new ticket type would lead to a `404`.

**`204 No Content`.** A successful `DELETE` has nothing to return. `204` says "it worked, and there's deliberately no body". A client doesn't have to guess whether an empty `200` was a mistake.

**`PUT` on an id that doesn't exist** could, by the HTTP standard, *create* it there. That's useful when clients choose their own ids (a file path, say). Here the **server** generates ids, so a client can't sensibly invent one: `PUT /events/999` is a `404`.

### `PUT` or `PATCH`?

**`PUT` replaces.** The body is the **whole** new event. A field you leave out isn't "unchanged", it's **gone**: send a `PUT` without `description` and the event no longer has one. That's what makes `PUT` idempotent: send the same body twice and the event ends up identical. It also means a client must first `GET` the event, change what it wants and send everything back.

**`PATCH` changes part.** The body says only what should change ([RFC 5789](https://www.rfc-editor.org/rfc/rfc5789)). `{"description": "Now with a ceilidh band."}` changes the description and leaves the title, venue and time alone. That's simpler for a client that only wants to fix one field, and it doesn't overwrite a change someone else made to another field in the meantime.

The catch is in that last sentence of `PATCH`: what does a field that's *missing* mean, compared with a field that's present and `null`? This API takes the simple route: in a `PATCH`, **`null` and missing both mean "leave it alone"**. The consequence is that a `PATCH` can't **clear** the description. To remove it, use `PUT`. The sidebar in step 2 shows the standard format that can tell the two apart.

### Entity, request DTO, response DTO

Guide 01 introduced response DTOs. Writing adds a third kind of object:

| | `Event` (entity) | `EventRequest` (request DTO) | `EventResponse` (response DTO) |
| --- | --- | --- | --- |
| Shape of | A database row | What a client may **send** | What a client **gets back** |
| `id` | Yes | **No**: the server chooses it | Yes |
| `version` | Yes | No | No |
| The venue | A `Venue` entity | `venueId`, a number | A nested `VenueResponse` |
| Validation rules | No | Yes | No |

Why not reuse the response record for requests, or bind JSON straight onto the entity?

- **What a client may send is different from what it gets back.** It sends a `venueId`; it gets back the venue's name and city. It never sends an `id`.
- **Binding JSON to an entity lets clients set anything the entity has.** If `POST /events` accepted an `Event`, a client could send `"version": 7` or, in a later guide, a booking's `"status"` or `"totalPence"`. With a request DTO, the fields that exist are the fields a client is allowed to set. Anything else in the JSON is simply ignored: Spring Boot configures Jackson not to fail on unknown properties, so `"id": 1, "colour": "red"` in a `POST` changes nothing. (Guide 06 comes back to this as **mass assignment**, one of the OWASP API Top 10.)
- **Validation rules describe input,** so they belong on the request DTO, not on the entity.

There are two request DTOs for events: `EventRequest` for `POST` and `PUT` (every required field must be there) and `EventPatch` for `PATCH` (every field is optional).

### Validation: where each check lives

**Bean Validation** (Jakarta Validation, implemented by Hibernate Validator) checks objects against annotations such as `@NotBlank`, `@NotNull`, `@Size(max = 200)` and `@Positive`. Put `@Valid` on a controller's `@RequestBody` parameter, and Spring validates the object after reading the JSON and **before** calling your method. If anything fails, your method never runs.

Some checks can't be written as an annotation on a field. "Venue 99 exists" needs the database, so it lives in the service. It's still the client's mistake, and the client still needs to know **which field** is wrong, so it's reported exactly like a validation error.

Why `400` and not `404` when the venue doesn't exist? Because the **URL** is fine: `POST /events` exists. What's wrong is a value in the body. A `404` would tell the client it had the wrong address.

### One error format for every bad request

A client shouldn't need a different parser for each way a request can go wrong. By the end of this guide, all of these come back as a `ProblemDetail` with `Content-Type: application/problem+json`:

| What went wrong | Status | Produced by |
| --- | --- | --- |
| A field fails validation | `400` | Spring (`MethodArgumentNotValidException`), with an `errors` member you add |
| A venue id that doesn't exist | `400` | Your service, via `InvalidFieldException` |
| A value of the wrong type (`"category": "OPERA"`) | `400` | Jackson, with an `errors` member you add |
| The body isn't JSON at all | `400` | Jackson |
| The event doesn't exist | `404` | Your `NotFoundException` (guide 01) |
| A method the URL doesn't support | `405` | Spring MVC, with an `Allow` header |
| A body that isn't `application/json` | `415` | Spring MVC, with an `Accept` header |

RFC 9457 lets a problem carry **extension members** beyond the standard five. This API adds one, `errors`: an object mapping each bad field's name to a message.

```json
{"detail":"Invalid request content.","instance":"/events","status":400,"title":"Bad Request","errors":{"startsAt":"must not be null","title":"must not be blank","venueId":"must not be null"}}
```

A client can show each message next to the right input, without parsing English sentences.

---

## Steps

### 1. Add validation

Add the validation starter to `pom.xml`, after the Flyway one, and its test companion with the other test starters:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation-test</artifactId>
    <scope>test</scope>
</dependency>
```

Reload Maven.

You might notice that `@NotBlank` already compiles **before** you add it. SpringDoc happens to depend on Spring Boot's validation module, so Hibernate Validator is already on the classpath. Add the starter anyway. Your code uses validation directly, so it should say so in its own `pom.xml`, rather than relying on another library's dependency that could disappear in its next version.

### 2. The request DTOs

`event/EventRequest.java`:

```java
package com.example.tickets.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * The body of POST /events and PUT /events/{id}. Wrapper types (Long, not long)
 * so a missing field is null and fails @NotNull, instead of silently becoming 0.
 */
public record EventRequest(
        @NotBlank @Size(max = 200) String title,
        @NotNull Category category,
        @NotNull Long venueId,
        @NotNull Instant startsAt,
        @Size(max = 2000) String description) {
}
```

- **`@NotBlank`** fails on a missing title, an empty one, or one that's only spaces. **`@Size(max = 200)`** matches the `varchar(200)` column, so a long title is a `400` with a clear message instead of a database error.
- **`description` is optional**, so it has only a size limit.
- **`Long venueId`, not `long`.** This one matters. If a client leaves `venueId` out, Jackson leaves the record component at its default. For a `Long` that's `null`, which `@NotNull` catches. For a `long` it's **`0`**, which passes `@NotNull` (a primitive can't be null) and turns into a confusing "venue 0 does not exist". Use wrapper types for every required number in a request DTO.

`event/EventPatch.java`:

```java
package com.example.tickets.event;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * The body of PATCH /events/{id}. Every field is optional: null means "leave it as it is".
 * (So a PATCH can't clear the description. See JSON Merge Patch, RFC 7396, for a format that can.)
 */
public record EventPatch(
        @Size(max = 200) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String title,
        Category category,
        Long venueId,
        Instant startsAt,
        @Size(max = 2000) String description) {
}
```

Every field may be missing, so there's no `@NotNull`. But a title that **is** sent still mustn't be blank. `@NotBlank` can't express that: it would also reject a missing title, which would make the field required. Instead, `@Pattern(regexp = ".*\\S.*")` says "contains at least one character that isn't whitespace". Like most constraints, `@Pattern` treats `null` as valid, so a missing title passes, and `"   "` fails with the message given.

> **Sidebar: JSON Merge Patch (RFC 7396).** `EventPatch` can't tell "the client didn't mention `description`" from "the client sent `"description": null`", because both arrive as `null`. [JSON Merge Patch](https://www.rfc-editor.org/rfc/rfc7396) is a standard `PATCH` format that defines exactly that difference: a member that's **absent** is left alone, a member set to **`null`** is **removed**, and anything else is set. So `{"description": null}` clears the description. The request says it's using this format with `Content-Type: application/merge-patch+json`. Implementing it means reading the body as a tree (a Jackson `JsonNode`) or tracking which fields were present, rather than binding straight to a record, and validating the **result** of the merge rather than the patch. There's also **JSON Patch** ([RFC 6902](https://www.rfc-editor.org/rfc/rfc6902)), `application/json-patch+json`, a list of operations such as `{"op": "remove", "path": "/description"}`, which is more powerful and more work again. Many APIs do what this one does: optional fields, and `null` means unchanged. Whichever you choose, **document it**, because clients can't guess.

### 3. Let the entity change

`Event` was read-only. Add a public constructor for new events, and setters for the fields an organiser may change, to `event/Event.java`:

```java
    public Event(String title, Category category, Venue venue, Instant startsAt, String description) {
        this.title = title;
        this.category = category;
        this.venue = venue;
        this.startsAt = startsAt;
        this.description = description;
    }
```

```java
    public void setTitle(String title) {
        this.title = title;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public void setVenue(Venue venue) {
        this.venue = venue;
    }

    public void setStartsAt(Instant startsAt) {
        this.startsAt = startsAt;
    }

    public void setDescription(String description) {
        this.description = description;
    }
```

The constructor goes after the `protected` one JPA uses, and the setters at the end of the class. There's no `setId` or `setVersion`: the database and Hibernate own those.

### 4. The service

First, the exception for "a field that passed validation but is still wrong". `web/InvalidFieldException.java`:

```java
package com.example.tickets.web;

/**
 * A request field that passed Bean Validation but is still wrong, such as a venueId
 * that doesn't exist. ApiExceptionHandler reports it like a validation error (400).
 */
public class InvalidFieldException extends RuntimeException {

    private final String field;

    public InvalidFieldException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
```

Now `event/EventService.java`. It needs the `VenueRepository` too:

```java
package com.example.tickets.event;

import com.example.tickets.venue.Venue;
import com.example.tickets.venue.VenueRepository;
import com.example.tickets.web.InvalidFieldException;
import com.example.tickets.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class EventService {

    private final EventRepository events;
    private final VenueRepository venues;

    public EventService(EventRepository events, VenueRepository venues) {
        this.events = events;
        this.venues = venues;
    }

    public List<Event> list() {
        return events.findAllByOrderByStartsAtAsc();
    }

    public Event get(long id) {
        return events.findById(id).orElseThrow(() -> new NotFoundException("Event", id));
    }

    @Transactional
    public Event create(EventRequest request) {
        Event event = new Event(request.title(), request.category(), venue(request.venueId()),
                request.startsAt(), request.description());
        return events.save(event);
    }

    /** PUT: replace every field. */
    @Transactional
    public Event replace(long id, EventRequest request) {
        Event event = get(id);
        event.setTitle(request.title());
        event.setCategory(request.category());
        event.setVenue(venue(request.venueId()));
        event.setStartsAt(request.startsAt());
        event.setDescription(request.description());
        return event;
    }

    /** PATCH: change only the fields that were sent. */
    @Transactional
    public Event patch(long id, EventPatch patch) {
        Event event = get(id);
        if (patch.title() != null) {
            event.setTitle(patch.title());
        }
        if (patch.category() != null) {
            event.setCategory(patch.category());
        }
        if (patch.venueId() != null) {
            event.setVenue(venue(patch.venueId()));
        }
        if (patch.startsAt() != null) {
            event.setStartsAt(patch.startsAt());
        }
        if (patch.description() != null) {
            event.setDescription(patch.description());
        }
        return event;
    }

    @Transactional
    public void delete(long id) {
        events.delete(get(id));
    }

    private Venue venue(long venueId) {
        return venues.findById(venueId)
                .orElseThrow(() -> new InvalidFieldException("venueId", "venue " + venueId + " does not exist"));
    }
}
```

- **Each writing method has its own `@Transactional`**, which overrides the class's `readOnly = true`.
- **`replace` sets every field**, including `description`, even when the request's is `null`. That's what "replace" means.
- **`patch` sets only the fields that aren't `null`.** That's what "change part" means, with this API's rule that `null` is "leave it alone".
- **Neither calls `save`.** The event was loaded inside the transaction, so it's *managed*: Hibernate notices the changes and writes them when the transaction commits. It also adds 1 to `version` at that point.
- **`get(id)` throws `NotFoundException`**, so `PUT`, `PATCH` and `DELETE` on a missing event are all `404` without any extra code.
- **`venue(…)` turns a missing venue into `InvalidFieldException("venueId", …)`.** The venue in a `replace` or `patch` comes back with the event's response, which reads its name and city, so the venue must be loaded inside the transaction. `findById` does that.

### 5. The controller

Replace `event/EventController.java` with:

```java
package com.example.tickets.event;

import jakarta.validation.Valid;
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

    @PostMapping
    public ResponseEntity<EventResponse> create(@Valid @RequestBody EventRequest request) {
        Event event = events.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(event.getId())
                .toUri();
        return ResponseEntity.created(location).body(EventResponse.from(event));
    }

    @PutMapping("/{id}")
    public EventResponse replace(@PathVariable long id, @Valid @RequestBody EventRequest request) {
        return EventResponse.from(events.replace(id, request));
    }

    @PatchMapping("/{id}")
    public EventResponse patch(@PathVariable long id, @Valid @RequestBody EventPatch patch) {
        return EventResponse.from(events.patch(id, patch));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        events.delete(id);
        return ResponseEntity.noContent().build();
    }
}
```

- **`@RequestBody`** tells Spring to read the request body as JSON into the record. **`@Valid`** validates it first. Leave `@Valid` out and invalid data walks straight into your service (see Common errors).
- **`ResponseEntity`** is how a controller controls the status and headers, not only the body. `ResponseEntity.created(location)` sets `201` and the `Location` header in one call. Methods that return a plain object, like `replace`, get `200`.
- **`ServletUriComponentsBuilder.fromCurrentRequest()`** starts from the URL this request came in on, `http://localhost:8080/events`, and `.path("/{id}")` appends to it. Building from the current request means the `Location` is right whatever host and port the API runs on.
- **`ResponseEntity.noContent().build()`** is `204` with no body. `ResponseEntity<Void>` says so in the method's type.

The `create` method reads the new event's venue in `EventResponse.from(event)` after the service's transaction has ended. That's safe because the service loaded the venue with `findById`, so it's a real object rather than a lazy proxy.

### 6. Try it

Restart the app. The examples below are for Git Bash, macOS or Linux.

> **PowerShell and JSON.** Windows PowerShell removes the double quotes inside a single-quoted argument when it passes it to `curl.exe`, so a JSON body arrives as `{title:Autumn Folk Night,…}` and you get a `400`. For requests with a JSON body, use Git Bash, or the `requests.http` file from step 11, or Swagger UI.

Create an event:

```bash
curl -i -H 'Content-Type: application/json' -d '{"title":"Autumn Folk Night","category":"MUSIC","venueId":4,"startsAt":"2030-10-03T19:30:00Z","description":"Three folk acts."}' localhost:8080/events
```

`-d` sends a body, and makes curl use `POST`. `-H` adds a header: without `Content-Type: application/json`, curl would label the body as form data.

```
HTTP/1.1 201
Location: http://localhost:8080/events/29
Content-Type: application/json

{"id":29,"title":"Autumn Folk Night","category":"MUSIC","venue":{"id":4,"name":"The Mill Theatre","city":"Leeds"},"startsAt":"2030-10-03T19:30:00Z","description":"Three folk acts."}
```

The new event is number 29 on a fresh database (yours may differ if you've already created some). Replace it, moving it to Manchester a week later, **without** a description:

```bash
curl -i -X PUT -H 'Content-Type: application/json' -d '{"title":"Autumn Folk Night (moved)","category":"MUSIC","venueId":3,"startsAt":"2030-10-10T19:30:00Z"}' localhost:8080/events/29
```

```
HTTP/1.1 200
Content-Type: application/json

{"id":29,"title":"Autumn Folk Night (moved)","category":"MUSIC","venue":{"id":3,"name":"Northern Works","city":"Manchester"},"startsAt":"2030-10-10T19:30:00Z","description":null}
```

The description is now `null`. You didn't send it, and `PUT` replaces everything. Now put one back with `PATCH`, sending only that field:

```bash
curl -X PATCH -H 'Content-Type: application/json' -d '{"description":"Now with a ceilidh band."}' localhost:8080/events/29
```

```
{"id":29,"title":"Autumn Folk Night (moved)","category":"MUSIC","venue":{"id":3,"name":"Northern Works","city":"Manchester"},"startsAt":"2030-10-10T19:30:00Z","description":"Now with a ceilidh band."}
```

Everything else stayed as it was. Keep event 29 for now: step 10 adds a ticket type to it, then deletes it.

Now send something invalid: a blank title, and no venue or start time:

```bash
curl -i -H 'Content-Type: application/json' -d '{"title":" ","category":"MUSIC"}' localhost:8080/events
```

You get a `400` `ProblemDetail` whose detail is `Invalid request content.`, and nothing else. The validation worked, but the client has no idea **which** fields are wrong, or why. That's the next step.

### 7. One error format: tell the client what to fix

Replace `web/ApiExceptionHandler.java` with:

```java
package com.example.tickets.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Turns exceptions into RFC 9457 ProblemDetail responses (application/problem+json).
 * The base class already does this for Spring MVC's own exceptions, such as an unknown URL.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** Bean Validation failures (@Valid): add an "errors" map of field name to message. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new TreeMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ProblemDetail problem = ex.getBody();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /** JSON that can't be read. If it's a value of the wrong type, say which field. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Failed to read request");
        if (ex.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            problem.setDetail("Invalid request content.");
            problem.setProperty("errors", Map.of(fieldName(mismatch), "has an invalid value"));
        }
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @ExceptionHandler(InvalidFieldException.class)
    ProblemDetail handle(InvalidFieldException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request content.");
        problem.setProperty("errors", Map.of(ex.getField(), ex.getMessage()));
        return problem;
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail handle(NotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Not Found");
        return problem;
    }

    private static String fieldName(JacksonException ex) {
        return ex.getPath().stream()
                .map(reference -> reference.getPropertyName() != null
                        ? reference.getPropertyName()
                        : "[" + reference.getIndex() + "]")
                .collect(Collectors.joining("."));
    }
}
```

Three additions. Take them one at a time.

**Validation errors, with a list of fields.** When `@Valid` fails, Spring throws `MethodArgumentNotValidException`, and the base class's `handleMethodArgumentNotValid` turns it into the bare `400` you saw. Overriding it lets you add to the problem before it's sent:

- `ex.getBindingResult().getFieldErrors()` holds one `FieldError` per failed constraint, each with the field's name and the constraint's message (`must not be blank`).
- A **`TreeMap`** keeps the field names in alphabetical order, so the response is the same every time.
- **`putIfAbsent`** keeps one message per field. A field can fail two constraints at once; the client only needs one reason to fix it.
- `ex.getBody()` is the `ProblemDetail` Spring had already prepared (with `Invalid request content.`). `setProperty("errors", …)` adds the extension member. **`handleExceptionInternal`** is the base class's common exit, which fills in `instance` and builds the response. Calling it, rather than building a `ResponseEntity` yourself, keeps your overrides consistent with everything else the base class handles.

The `@Override` annotation matters here: if the signature is slightly wrong, the compiler tells you, instead of your method silently never being called.

**Wrong types, with the field's name.** When the body can't be turned into the record at all, there's nothing to validate. Jackson throws, and Spring wraps it in an `HttpMessageNotReadableException`. By default the detail is just `Failed to read request`, which is all you can say about JSON that's broken. But often the JSON is fine and **one value** is wrong: `"category": "OPERA"` isn't a `Category`, `"startsAt": "next Friday"` isn't an `Instant`. In those cases Jackson's exception is a `MismatchedInputException` (Jackson 3's package is `tools.jackson.*`), and its **path** says where in the JSON it was. The override reports that as an `errors` entry, in exactly the same shape as a validation error.

The message is deliberately generic: `has an invalid value`. Jackson's own message names Java classes and enum internals, which tells the outside world how your code is built and helps nobody fix their request.

**A field that's wrong for another reason.** The `InvalidFieldException` handler produces the same detail and `errors` shape as a validation failure. A client can't tell, and shouldn't need to, whether `venueId` was rejected by an annotation or by a database lookup.

Restart and send the invalid request again:

```bash
curl -i -H 'Content-Type: application/json' -d '{"title":" ","category":"MUSIC"}' localhost:8080/events
```

```
HTTP/1.1 400
Content-Type: application/problem+json

{"detail":"Invalid request content.","instance":"/events","status":400,"title":"Bad Request","errors":{"startsAt":"must not be null","title":"must not be blank","venueId":"must not be null"}}
```

(The extension member `errors` comes after the standard ones.)

### 8. Every kind of bad request

Try each of these, and notice that the shape never changes. With the body from step 6 and `"venueId":99`:

```json
{"detail":"Invalid request content.","instance":"/events","status":400,"title":"Bad Request","errors":{"venueId":"venue 99 does not exist"}}
```

With `"category":"OPERA"`:

```json
{"detail":"Invalid request content.","instance":"/events","status":400,"title":"Bad Request","errors":{"category":"has an invalid value"}}
```

With `"startsAt":"next Friday"`:

```json
{"detail":"Invalid request content.","instance":"/events","status":400,"title":"Bad Request","errors":{"startsAt":"has an invalid value"}}
```

With JSON that's cut off, `-d '{"title": '`:

```json
{"detail":"Failed to read request","instance":"/events","status":400,"title":"Bad Request"}
```

There's no field to blame when the JSON itself is broken, so there's no `errors` member.

The wrong **content type**:

```bash
curl -i -H 'Content-Type: text/plain' -d 'hello' localhost:8080/events
```

```
HTTP/1.1 415
Accept: application/json, application/yaml, application/*+json
Content-Type: application/problem+json

{"detail":"Content-Type 'text/plain;charset=UTF-8' is not supported.","instance":"/events","status":415,"title":"Unsupported Media Type"}
```

`415 Unsupported Media Type`, with an **`Accept`** header listing what the endpoint *would* read. (`application/yaml` is there because a YAML module for Jackson is on the classpath, via SpringDoc.)

A **method** the collection doesn't support:

```bash
curl -i -X DELETE localhost:8080/events
```

```
HTTP/1.1 405
Allow: GET, POST
Content-Type: application/problem+json

{"detail":"Method 'DELETE' is not supported.","instance":"/events","status":405,"title":"Method Not Allowed"}
```

You can delete **an** event, not the whole collection. The `Allow` header now lists `POST` as well as `GET`.

The last two came from `ResponseEntityExceptionHandler` without any code from you. That's the payoff of extending it in guide 01.

### 9. Ticket types: a sub-resource

An event has **ticket types**: "Standing" at £25, "Balcony" at £40, and so on. A ticket type only makes sense as part of its event, so it lives **under** the event's URL:

| Request | Does |
| --- | --- |
| `GET /events/2/ticket-types` | Event 2's ticket types, cheapest first |
| `GET /events/2/ticket-types/57` | One of event 2's ticket types |
| `POST /events/2/ticket-types` | Add a ticket type to event 2 |

The URL carries the relationship: there's no `eventId` in the request body, because the path already says which event.

**The migration.** Create `src/main/resources/db/migration/V2__ticket_types.sql`:

```sql
create table ticket_type (
    id          bigint generated by default as identity primary key,
    event_id    bigint      not null references event (id) on delete cascade,
    name        varchar(50) not null,
    price_pence bigint      not null,
    capacity    int         not null,
    sold        int         not null default 0
);

-- Ticket type n (for n = 1 to 28) is the Standing ticket for event n
insert into ticket_type (event_id, name, price_pence, capacity)
select id, 'Standing', 2500, 200 from event order by id;

-- Ticket types 29 to 56 are the Balcony tickets for events 1 to 28
insert into ticket_type (event_id, name, price_pence, capacity)
select id, 'Balcony', 4000, 80 from event order by id;

-- Ticket type 57: only two front-row seats for event 2, so selling out is easy to try
insert into ticket_type (event_id, name, price_pence, capacity) values (2, 'Front Row', 7500, 2);
```

- **A new migration, not an edit to V1.** V1 has already run against your `./data/tickets` database, and Flyway refuses to start if an applied migration changes.
- **Money is a whole number of pence** in a `bigint`: £25.00 is `2500`. No floating-point rounding, ever.
- **Predictable ids again.** Ticket type *n* is the Standing ticket for event *n*, 29 to 56 are the Balcony tickets, and 57 is a Front Row ticket for event 2 with a capacity of only **2**. Guides 06 and 07 use it to sell out.
- **`on delete cascade`**: deleting an event deletes its ticket types in the database, so `DELETE /events/{id}` keeps working without extra code.
- **`sold`** counts tickets sold. Nothing changes it until bookings arrive in guide 06.

**The entity.** Ticket types belong to events, so they live in the `event` package. `event/TicketType.java`:

```java
package com.example.tickets.event;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/** A kind of ticket for one event, such as "Standing". General admission: no seat numbers. */
@Entity
public class TicketType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Event event;

    private String name;

    private long pricePence;

    private int capacity;

    private int sold;

    protected TicketType() {
        // for JPA
    }

    public TicketType(Event event, String name, long pricePence, int capacity) {
        this.event = event;
        this.name = name;
        this.pricePence = pricePence;
        this.capacity = capacity;
    }

    public Long getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public String getName() {
        return name;
    }

    public long getPricePence() {
        return pricePence;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getSold() {
        return sold;
    }

    public int getAvailable() {
        return capacity - sold;
    }
}
```

**The repository.** `event/TicketTypeRepository.java`:

```java
package com.example.tickets.event;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TicketTypeRepository extends JpaRepository<TicketType, Long> {

    List<TicketType> findByEventIdOrderByPricePenceAsc(long eventId);

    Optional<TicketType> findByIdAndEventId(long id, long eventId);
}
```

`findByIdAndEventId` is the important one. Ticket type 31 belongs to event 3. `GET /events/4/ticket-types/31` must not return it, because the URL claims it's one of event 4's. Looking it up by **both** ids makes that a "not found".

**The DTOs.** `event/TicketTypeRequest.java`:

```java
package com.example.tickets.event;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** The body of POST /events/{eventId}/ticket-types. Prices are whole pence: never use double for money. */
public record TicketTypeRequest(
        @NotBlank @Size(max = 50) String name,
        @NotNull @PositiveOrZero @Max(1_000_000) Long pricePence,
        @NotNull @Positive @Max(100_000) Integer capacity) {
}
```

A price can be `0` (a free event) but never negative, and a capacity must be at least 1. The upper limits stop nonsense such as a capacity of two billion, which would also overflow an `int` once tickets were counted.

`event/TicketTypeResponse.java`:

```java
package com.example.tickets.event;

public record TicketTypeResponse(long id, String name, long pricePence, int capacity, int available) {

    public static TicketTypeResponse from(TicketType ticketType) {
        return new TicketTypeResponse(ticketType.getId(), ticketType.getName(), ticketType.getPricePence(),
                ticketType.getCapacity(), ticketType.getAvailable());
    }
}
```

The response shows **`available`**, not `sold`. A customer wants to know whether there are tickets left. How many have been sold is the organiser's business. This is the response-DTO idea from guide 01 again: the entity's field and the API's field don't have to be the same thing.

**The service.** `event/TicketTypeService.java`:

```java
package com.example.tickets.event;

import com.example.tickets.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class TicketTypeService {

    private final TicketTypeRepository ticketTypes;
    private final EventRepository events;

    public TicketTypeService(TicketTypeRepository ticketTypes, EventRepository events) {
        this.ticketTypes = ticketTypes;
        this.events = events;
    }

    public List<TicketType> list(long eventId) {
        if (!events.existsById(eventId)) {
            throw new NotFoundException("Event", eventId);
        }
        return ticketTypes.findByEventIdOrderByPricePenceAsc(eventId);
    }

    public TicketType get(long eventId, long id) {
        return ticketTypes.findByIdAndEventId(id, eventId)
                .orElseThrow(() -> new NotFoundException("Ticket type", id));
    }

    @Transactional
    public TicketType create(long eventId, TicketTypeRequest request) {
        Event event = events.findById(eventId).orElseThrow(() -> new NotFoundException("Event", eventId));
        return ticketTypes.save(new TicketType(event, request.name(), request.pricePence(), request.capacity()));
    }
}
```

**Why `list` checks the event first.** Without that check, `GET /events/999/ticket-types` would return `200` and an empty array, which says "event 999 exists and has no ticket types". That's false. The right answer is `404`: the thing the URL names doesn't exist.

**Why `create` uses `404` for a missing event, when `POST /events` used `400` for a missing venue.** Here the event's id is in the **URL**, so the URL names something that doesn't exist. There, the venue's id was in the **body**.

**The controller.** `event/TicketTypeController.java`:

```java
package com.example.tickets.event;

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
public class TicketTypeController {

    private final TicketTypeService ticketTypes;

    public TicketTypeController(TicketTypeService ticketTypes) {
        this.ticketTypes = ticketTypes;
    }

    @GetMapping
    public List<TicketTypeResponse> list(@PathVariable long eventId) {
        return ticketTypes.list(eventId).stream()
                .map(TicketTypeResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public TicketTypeResponse get(@PathVariable long eventId, @PathVariable long id) {
        return TicketTypeResponse.from(ticketTypes.get(eventId, id));
    }

    @PostMapping
    public ResponseEntity<TicketTypeResponse> create(@PathVariable long eventId,
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

The class-level `@RequestMapping` has a **path variable in it**, `{eventId}`, and each method receives it with `@PathVariable`. The `Location` is built the same way as for events, from the current request's URL, so it comes out as `/events/29/ticket-types/58`.

### 10. Try ticket types

Restart. Flyway applies `V2`:

```
Migrating schema "PUBLIC" to version "2 - ticket types"
```

Event 2's ticket types, cheapest first:

```bash
curl localhost:8080/events/2/ticket-types
```

```
[{"id":2,"name":"Standing","pricePence":2500,"capacity":200,"available":200},{"id":30,"name":"Balcony","pricePence":4000,"capacity":80,"available":80},{"id":57,"name":"Front Row","pricePence":7500,"capacity":2,"available":2}]
```

Add one to event 29, the event you created in step 6:

```bash
curl -i -H 'Content-Type: application/json' -d '{"name":"Early Bird","pricePence":1800,"capacity":50}' localhost:8080/events/29/ticket-types
```

```
HTTP/1.1 201
Location: http://localhost:8080/events/29/ticket-types/58
Content-Type: application/json

{"id":58,"name":"Early Bird","pricePence":1800,"capacity":50,"available":50}
```

Now delete the event, and check its ticket type went with it:

```bash
curl -i -X DELETE localhost:8080/events/29
```

```
HTTP/1.1 204
```

```bash
curl localhost:8080/events/29/ticket-types/58
```

```
{"detail":"Ticket type 58 was not found","instance":"/events/29/ticket-types/58","status":404,"title":"Not Found"}
```

That's `on delete cascade` at work. Delete the event a second time and you get `HTTP/1.1 404`: the effect is the same (the event is gone), which is what makes `DELETE` idempotent, but the response honestly says there was nothing there this time.

### 11. Update `requests.http`

Add these to the end of `requests.http`:

```http
### Create an event
POST http://localhost:8080/events
Content-Type: application/json

{
  "title": "Autumn Folk Night",
  "category": "MUSIC",
  "venueId": 4,
  "startsAt": "2030-10-03T19:30:00Z",
  "description": "Three folk acts."
}

### Create an invalid event (400 with a list of errors)
POST http://localhost:8080/events
Content-Type: application/json

{"title": " ", "category": "MUSIC"}

### Replace an event (PUT: send every field)
PUT http://localhost:8080/events/29
Content-Type: application/json

{
  "title": "Autumn Folk Night (moved)",
  "category": "MUSIC",
  "venueId": 3,
  "startsAt": "2030-10-10T19:30:00Z"
}

### Change part of an event (PATCH: send only what changes)
PATCH http://localhost:8080/events/29
Content-Type: application/json

{"description": "Now with a ceilidh band."}

### An event's ticket types
GET http://localhost:8080/events/2/ticket-types

### Add a ticket type
POST http://localhost:8080/events/29/ticket-types
Content-Type: application/json

{"name": "Early Bird", "pricePence": 1800, "capacity": 50}

### Delete an event
DELETE http://localhost:8080/events/29
```

A request with a body has a blank line between its headers and the body, exactly as on the wire. If you've deleted event 29, the requests that use it give `404`s: create a new event and change the id.

Swagger UI also picks up the new endpoints. Open **POST /events**, click **Try it out**, and it fills in an example body built from `EventRequest`'s fields for you to edit.

### 12. Tests

Create `src/test/java/com/example/tickets/event/EventWriteApiTest.java`:

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
class EventWriteApiTest {

    static final String NEW_EVENT = """
            {
              "title": "Test Gig",
              "category": "MUSIC",
              "venueId": 1,
              "startsAt": "2030-09-01T19:30:00Z",
              "description": "Created by a test"
            }
            """;

    @Autowired
    MockMvcTester mvc;

    @Test
    void createsAnEventWith201AndALocation() {
        var response = mvc.post().uri("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(NEW_EVENT);

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).headers().hasHeaderSatisfying("Location",
                values -> assertThat(values.getFirst()).matches("http://localhost/events/\\d+"));
        assertThat(response).bodyJson().extractingPath("$.venue.name").isEqualTo("Riverside Hall");
    }

    @Test
    void theLocationPointsAtTheNewEvent() {
        String location = mvc.post().uri("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(NEW_EVENT)
                .exchange()
                .getResponse()
                .getHeader("Location");

        assertThat(mvc.get().uri(location)).bodyJson().extractingPath("$.title").isEqualTo("Test Gig");
    }

    @Test
    void rejectsInvalidFieldsWithAListOfErrors() {
        var response = mvc.post().uri("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title": " ", "category": "MUSIC"}
                        """);

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "title": "Bad Request",
                  "detail": "Invalid request content.",
                  "errors": {
                    "startsAt": "must not be null",
                    "title": "must not be blank",
                    "venueId": "must not be null"
                  }
                }
                """);
    }

    @Test
    void rejectsAVenueThatDoesNotExist() {
        var response = mvc.post().uri("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(NEW_EVENT.replace("\"venueId\": 1", "\"venueId\": 99"));

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors.venueId").isEqualTo("venue 99 does not exist");
    }

    @Test
    void rejectsMalformedJson() {
        var response = mvc.post().uri("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": ");

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().extractingPath("$.detail").isEqualTo("Failed to read request");
    }

    @Test
    void saysWhichFieldHasTheWrongType() {
        var response = mvc.post().uri("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(NEW_EVENT.replace("\"MUSIC\"", "\"OPERA\""));

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors.category").isEqualTo("has an invalid value");
    }

    @Test
    void rejectsAContentTypeOtherThanJson() {
        var response = mvc.post().uri("/events")
                .contentType(MediaType.TEXT_PLAIN)
                .content("Test Gig");

        assertThat(response).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void putReplacesTheWholeEvent() {
        long id = createEvent();

        var response = mvc.put().uri("/events/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title": "Renamed Gig", "category": "COMEDY", "venueId": 3, "startsAt": "2030-10-01T20:00:00Z"}
                        """);

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"title": "Renamed Gig", "category": "COMEDY", "venue": {"id": 3}, "startsAt": "2030-10-01T20:00:00Z"}
                """);
        // PUT replaces everything: the description wasn't sent, so it's gone
        assertThat(response).bodyJson().extractingPath("$.description").isNull();
    }

    @Test
    void putNeedsTheWholeEvent() {
        long id = createEvent();

        var response = mvc.put().uri("/events/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title": "Renamed Gig"}
                        """);

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void patchChangesOnlyTheFieldsSent() {
        long id = createEvent();

        var response = mvc.patch().uri("/events/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title": "Patched Gig"}
                        """);

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"title": "Patched Gig", "category": "MUSIC", "description": "Created by a test"}
                """);
    }

    @Test
    void patchRejectsABlankTitle() {
        long id = createEvent();

        var response = mvc.patch().uri("/events/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title": "   "}
                        """);

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors.title").isEqualTo("must not be blank");
    }

    @Test
    void updatingAMissingEventIs404() {
        var response = mvc.patch().uri("/events/999")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title": "Nope"}
                        """);

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteIs204AndThenTheEventIsGone() {
        long id = createEvent();

        assertThat(mvc.delete().uri("/events/{id}", id)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(mvc.get().uri("/events/{id}", id)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.delete().uri("/events/{id}", id)).hasStatus(HttpStatus.NOT_FOUND);
    }

    private long createEvent() {
        var result = mvc.post().uri("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(NEW_EVENT)
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        String location = result.getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }
}
```

New things in `MockMvcTester`:

- **`mvc.post()`, `put()`, `patch()` and `delete()`** work like `get()`. **`.contentType(...)`** sets the `Content-Type` header and **`.content(...)`** the body.
- **`.uri("/events/{id}", id)`** fills in a URI template, the same way `@GetMapping("/{id}")` reads one.
- **`headers().hasHeaderSatisfying("Location", values -> …)`** gets the header's values and lets you assert on them. MockMvc's requests have the host `localhost` and **no port**, so the `Location` is `http://localhost/events/42`, not `…:8080/…`.
- **`.exchange()`** performs the request and returns the result, so you can read from it in your own code: here `getResponse().getHeader("Location")`. Tests use the `Location` to find the new event's id, which is another reason to send one.
- **Tests that change data create their own event first** (`createEvent()`), because every test class shares one database. A test that patched event 2 would break `EventApiTest`'s check on event 2's title, depending on the order they ran in.

---

## Your turn

### Exercise 1: test the ticket types

Write `src/test/java/com/example/tickets/event/TicketTypeApiTest.java` with five tests:

1. `listsAnEventsTicketTypesCheapestFirst`: event 3's two ticket types, Standing (id 3) then Balcony (id 31), with their prices, capacities and availability.
2. `ticketTypesOfAMissingEventIs404`: `GET /events/999/ticket-types`.
3. `aTicketTypeOfAnotherEventIs404`: ticket type 31 is found under event 3 but not under event 4.
4. `addsATicketType`: `POST` a "Box" ticket type (£120, capacity 4) to event 4; check the `201`, the `Location`, and the body.
5. `rejectsANegativePriceAndZeroCapacity`: a price of `-1` and a capacity of `0` give a `400` with an error for each field.

For number 5, send it once with curl first, to see the exact messages.

<details>
<summary>Solution</summary>

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
class TicketTypeApiTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void listsAnEventsTicketTypesCheapestFirst() {
        var response = mvc.get().uri("/events/3/ticket-types");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                [
                  {"id": 3, "name": "Standing", "pricePence": 2500, "capacity": 200, "available": 200},
                  {"id": 31, "name": "Balcony", "pricePence": 4000, "capacity": 80, "available": 80}
                ]
                """);
    }

    @Test
    void ticketTypesOfAMissingEventIs404() {
        assertThat(mvc.get().uri("/events/999/ticket-types")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void aTicketTypeOfAnotherEventIs404() {
        assertThat(mvc.get().uri("/events/3/ticket-types/31")).hasStatusOk();
        assertThat(mvc.get().uri("/events/4/ticket-types/31")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void addsATicketType() {
        var response = mvc.post().uri("/events/4/ticket-types")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Box", "pricePence": 12000, "capacity": 4}
                        """);

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).headers().hasHeaderSatisfying("Location",
                values -> assertThat(values.getFirst()).matches("http://localhost/events/4/ticket-types/\\d+"));
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"name": "Box", "pricePence": 12000, "capacity": 4, "available": 4}
                """);
    }

    @Test
    void rejectsANegativePriceAndZeroCapacity() {
        var response = mvc.post().uri("/events/4/ticket-types")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Box", "pricePence": -1, "capacity": 0}
                        """);

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"errors": {"pricePence": "must be greater than or equal to 0", "capacity": "must be greater than 0"}}
                """);
    }
}
```

`isLenientlyEqualTo` works with arrays too. Leniently, the **order** of array elements doesn't matter, so the first test checks the contents rather than "cheapest first". Event 3 only has two ticket types, so checking their prices covers it; an `extractingPath("$[0].name")` check would pin the order down if you wanted to.

Adding a ticket type to event 4 is safe in a shared database because no other test counts event 4's ticket types.

</details>

### Exercise 2: what the client can't set

Send a `POST /events` with the body from step 6, plus `"id": 1` and `"colour": "red"`. Before you run it: will it be a `400`? Will it overwrite event 1? Then run it and look at the response.

<details>
<summary>Solution</summary>

```bash
curl -i -H 'Content-Type: application/json' -d '{"id":1,"colour":"red","title":"Autumn Folk Night","category":"MUSIC","venueId":4,"startsAt":"2030-10-03T19:30:00Z","description":"Three folk acts."}' localhost:8080/events
```

A `201`, with a **new** id chosen by the database, and event 1 untouched. `EventRequest` has no `id` or `colour` component, so there's nowhere for them to go, and Spring Boot's Jackson setup ignores properties it doesn't recognise rather than failing.

That's the request DTO protecting you: the only fields a client can set are the ones you listed. If `POST /events` had accepted an `Event` entity, the `id` might have been bound, and any field you added to the entity later would become settable from outside without anyone deciding it should be.

(Some APIs prefer to **reject** unknown properties with a `400`, so clients find out about typos such as `"titel"`. Both are defensible. This series keeps Spring Boot's default.)

</details>

### Exercise 3: a missing number

Send a ticket type with only a name to event 2: `{"name":"Free"}`. What's in `errors`? Then explain what would happen if `TicketTypeRequest` declared `long pricePence` and `int capacity` instead of `Long` and `Integer`.

<details>
<summary>Solution</summary>

```bash
curl -i -H 'Content-Type: application/json' -d '{"name":"Free"}' localhost:8080/events/2/ticket-types
```

```
"errors":{"capacity":"must not be null","pricePence":"must not be null"}
```

With primitives, the missing values would become `0` instead of `null`. `@NotNull` can never fail on a primitive, and `@PositiveOrZero` accepts a price of `0`, so the price check would pass silently: a forgotten price would become a free ticket. Only `@Positive` on `capacity` would catch it, with the misleading message `must be greater than 0`. Wrapper types make "missing" visible.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
```

(7 from guide 01, 13 in `EventWriteApiTest` and 5 in `TicketTypeApiTest`, if you did exercise 1.)

New files since guide 01:

```
src/main/java/com/example/tickets/
├── event/
│   ├── EventPatch.java
│   ├── EventRequest.java
│   ├── TicketType.java
│   ├── TicketTypeController.java
│   ├── TicketTypeRepository.java
│   ├── TicketTypeRequest.java
│   ├── TicketTypeResponse.java
│   └── TicketTypeService.java
└── web/
    └── InvalidFieldException.java
src/main/resources/db/migration/
└── V2__ticket_types.sql
src/test/java/com/example/tickets/event/
├── EventWriteApiTest.java
└── TicketTypeApiTest.java
```

Modified: `pom.xml` (validation starters), `Event.java` (constructor and setters), `EventService.java` (create, replace, patch and delete), `EventController.java` (`POST`, `PUT`, `PATCH`, `DELETE`), `ApiExceptionHandler.java` (field errors, unreadable JSON, `InvalidFieldException`), `requests.http`.

---

## Common errors

**`500` instead of `400` for an invalid event, and `java.lang.NullPointerException: Cannot invoke "java.lang.Long.longValue()" because the return value of "com.example.tickets.event.EventRequest.venueId()" is null` in the log**
`@Valid` is missing from the `@RequestBody` parameter, so nothing checked the request and it reached the service with a `null` venue id. Put `@Valid` back. Notice the response body too: `{"timestamp":"2026-11-12T18:22:53.763Z","status":500,"error":"Internal Server Error","path":"/events"}`. That's Spring Boot's default error format, **not** a `ProblemDetail`, because nothing in `ApiExceptionHandler` handles a `NullPointerException`. Unexpected exceptions still look like this; guide 08 deals with them.

**A `400` for an invalid request, but no `errors` member**
`handleMethodArgumentNotValid` isn't being called. Check it's in `ApiExceptionHandler`, has `@Override`, and has exactly the signature from step 7 (`MethodArgumentNotValidException, HttpHeaders, HttpStatusCode, WebRequest`, returning `ResponseEntity<Object>`). Without `@Override`, a slightly different signature compiles fine as a new, never-called method.

**A test expects `201` but gets `200`**
`create` returns the `EventResponse` directly instead of `ResponseEntity.created(location).body(...)`. A plain return value is always `200`.

**The `Location` header is `http://localhost:8080/events/events/29`**
`fromCurrentRequest()` already includes `/events`, the path of the request being handled. Append only `.path("/{id}")`.

**A missing number is treated as `0`**
The request record uses a primitive (`long`, `int`) where it should use a wrapper (`Long`, `Integer`). `@NotNull` can't see a primitive's missing value. See exercise 3.

**A `400` with `Failed to read request` for JSON that looks fine, in PowerShell**
Windows PowerShell stripped the double quotes out of the `-d '…'` argument before `curl.exe` saw it. Use Git Bash, `requests.http` or Swagger UI for requests with a JSON body.

**`Migration checksum mismatch for migration version 1`**
You put the `ticket_type` table into `V1` instead of a new `V2`. Applied migrations must never change. Move it to `V2__ticket_types.sql` and restore `V1`; in development only, you can delete the `data` folder instead.

---

## Recap

- **`POST`** creates in a collection: **`201 Created`**, a **`Location`** you can `GET`, and the new resource. **`PUT`** replaces the whole resource; **`PATCH`** changes part of it; **`DELETE`** answers **`204 No Content`**.
- In this API, a `PATCH`'s `null` means "unchanged", so it can't clear a field. **JSON Merge Patch** (RFC 7396) distinguishes absent from `null`. Whichever rule you pick, document it.
- Keep three kinds of object apart: the **entity** (the database), the **request DTO** (what clients may send) and the **response DTO** (what they get back). A request DTO is also your first defence against clients setting fields they shouldn't.
- **`@Valid`** on a `@RequestBody` runs **Bean Validation** before your method. Use **wrapper types** for required numbers. Checks that need the database live in the service, and report the same way.
- Override **`handleMethodArgumentNotValid`** and **`handleHttpMessageNotReadable`**, and handle your own exceptions, so every bad request is a **`ProblemDetail`** with an **`errors`** member naming the fields. Don't echo framework error messages to clients.
- A **sub-resource** puts the relationship in the URL. A parent that doesn't exist is a `404`, never an empty `200`.

### Interview angle

<details>
<summary>"What's the difference between POST and PUT?"</summary>

`POST` to a collection asks the **server** to create a new member and choose its URL; it's not idempotent, so repeating it creates duplicates, and a successful create answers `201 Created` with a `Location` header. `PUT` sends a complete representation to a **known** URL and replaces whatever is there (or creates it, if the API lets clients choose ids); it's idempotent, so repeating it leaves the same state. When the server generates ids, as most do, you create with `POST` and replace with `PUT`.

</details>

<details>
<summary>"What's the difference between PUT and PATCH? Is PATCH idempotent?"</summary>

`PUT` replaces the whole resource, so any field left out is cleared. `PATCH` applies a partial change, described in some patch format: a JSON Merge Patch document (RFC 7396), a list of JSON Patch operations (RFC 6902), or, often, a DTO of optional fields. `PATCH` isn't *guaranteed* to be idempotent: "set the title to X" is, but an operation like "add 10 to capacity" or "append to a list" isn't. Mention the absent-versus-`null` problem and how your API resolves it.

</details>

<details>
<summary>"Why have separate request and response DTOs instead of using the entity?"</summary>

They describe different things. A request DTO lists exactly what a client may set and carries the validation rules; it has no id or version and refers to related things by id. A response DTO is what clients may see, often with related data nested in and internal fields left out. Binding requests straight to entities exposes you to mass assignment (clients setting ids, prices or statuses), and returning entities couples the API to the database and leaks internals. The cost is a little mapping code, which is cheap.

</details>

<details>
<summary>"Should a validation error be a 400 or a 422?"</summary>

Both are in use. `422 Unprocessable Content` (RFC 9110) means the request was well-formed but its content couldn't be processed, which some argue fits validation failures better; `400 Bad Request` is the general "the client sent something wrong" and is Spring's default for `@Valid` failures. What matters more is consistency and a useful body: one status for one kind of problem, and a machine-readable list of which fields failed and why. This API uses `400` for every invalid request and saves `422` for a different situation in guide 07.

</details>

<details>
<summary>"An API receives a reference to another entity that doesn't exist, such as a venue id. What status should it return?"</summary>

It depends where the id is. If it's in the **body** (`POST /events` with `"venueId": 99`), the URL is valid and the content is wrong: `400` (or `422`), naming the field. If it's in the **path** (`POST /events/999/ticket-types`), the URL names something that doesn't exist: `404`. Returning `404` for a bad body reference misleads the client into thinking it used the wrong URL.

</details>

### Further reading

- [RFC 9110: HTTP Semantics](https://www.rfc-editor.org/rfc/rfc9110): [`POST`](https://www.rfc-editor.org/rfc/rfc9110#section-9.3.3), [`PUT`](https://www.rfc-editor.org/rfc/rfc9110#section-9.3.4), [`DELETE`](https://www.rfc-editor.org/rfc/rfc9110#section-9.3.5) and [`201 Created`](https://www.rfc-editor.org/rfc/rfc9110#section-15.3.2)
- [RFC 5789: PATCH Method for HTTP](https://www.rfc-editor.org/rfc/rfc5789), [RFC 7396: JSON Merge Patch](https://www.rfc-editor.org/rfc/rfc7396) and [RFC 6902: JSON Patch](https://www.rfc-editor.org/rfc/rfc6902)
- [RFC 9457: Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457), especially the section on extension members
- [Spring Framework reference: Java Bean Validation](https://docs.spring.io/spring-framework/reference/core/validation/beanvalidation.html)
- [Spring Framework reference: error responses](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)
- [Jakarta Validation specification](https://jakarta.ee/specifications/bean-validation/): the list of built-in constraints

**Simplification to notice:** anyone with curl can now create, change and delete events. Guide 05 makes those methods require an organiser's access token. And `DELETE` happily deletes an event people have booked tickets for; guide 06 refuses that with a `409 Conflict`.

**Next:** [03: Collections and caching](03-collections-and-caching.md). Paging, filtering and sorting `GET /events` safely, and letting clients cache an event with `ETag` and `304 Not Modified`.
