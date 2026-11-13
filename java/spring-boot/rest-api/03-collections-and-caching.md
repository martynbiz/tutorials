# 03: Collections and caching

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](README.md) for version notes.

## What you'll learn

- Why a collection endpoint must return **pages**, and how Spring Data's **`Pageable`** reads `?page=`, `?size=` and `?sort=` for you
- How to return a page as stable, documented JSON with **`PagedModel`**, instead of Spring Data's internal `Page` structure
- Why every page needs a **maximum size**, and how to set one
- How to **filter** a collection with query parameters bound into a record, and turn them into a query with **Specifications**
- Why letting clients sort by **any** property is dangerous, and how a **sort whitelist** fixes it
- What **`Cache-Control`** tells browsers, proxies and other caches
- How an **`ETag`** built from the entity's `@Version`, plus **`If-None-Match`**, lets a client ask "has it changed?" and get **`304 Not Modified`** with no body

By the end, `GET /events` returns pages of at most 100 events, filtered by city, category and date, sortable by start time or title only, and `GET /events/{id}` can be cached for a minute and then revalidated cheaply.

---

## Prerequisites for this guide

- Guide 02 finished, with its 25 tests passing.
- From the todo app (or Spring Data generally): derived query methods such as `findByEventIdOrderByPricePenceAsc`. This guide adds a second way to build queries.

---

## Concepts

### Why collections come in pages

Guide 01's `GET /events` returns **every** event in one array. With 28 rows that's fine. Now imagine 200,000 events, years of them. Every call would:

- load every row from the database and hold all of them in memory as entities, then as DTOs, then as one huge JSON string;
- send megabytes over the network, to a client that was probably going to show the first twenty;
- take long enough that the client times out, retries, and doubles the load.

And it isn't only accidents. A client that **can** ask for everything in one request can do it in a loop, deliberately. The OWASP API Security Top 10 lists this as [API4:2023, Unrestricted Resource Consumption](https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/).

So a collection endpoint returns one **page** at a time, and says how to get the rest:

```
GET /events?page=0&size=20       the first 20
GET /events?page=1&size=20       the next 20
```

Pages are numbered from **0**. The response includes the page's own details, so the client knows how many there are:

```json
{"content":[{"id":1,"title":"Bonfire Night Comedy Special",…},{"id":2,"title":"The Paper Lanterns: Live",…}],"page":{"size":2,"number":0,"totalElements":28,"totalPages":14}}
```

**A page size has a ceiling.** Without one, `?size=1000000` brings back the "give me everything" problem. Spring clamps an over-sized request **silently** to a maximum, and the response's `page.size` tells the client what it actually got. Spring Boot's default maximum is 2000, which is far too many for this API; it'll be **100**.

> **Sidebar: offset paging and cursor paging.** `page` and `size` are **offset** paging: page 3 of 20 is "skip 60 rows, take 20". It's simple, clients can jump to any page, and Spring Data supports it directly, so this series uses it. It has two weaknesses. If a row is inserted or deleted between two requests, everything after it shifts, so a client walking through the pages can see a row twice or miss one. And the database still has to step over all the skipped rows, so very deep pages get slow. The alternative is **cursor** (or *keyset*) paging: the response includes a token meaning "after the event that starts at 19:30 on 18 January with id 2", and the next request asks for rows after that. It's stable and fast at any depth, but a client can only go forwards from where it is. Large feeds (social timelines, transaction histories) use cursors. For browsing a few thousand events, offsets are fine. Spring Data supports keyset scrolling too, with `ScrollPosition` and `Window`.

### Filtering with query parameters

A client rarely wants "all events". It wants "theatre in Edinburgh" or "anything in March". Filters go in **query parameters** on the collection's URL:

```
GET /events?city=Edinburgh&category=THEATRE
GET /events?from=2030-03-01T00:00:00Z&to=2030-04-01T00:00:00Z
```

They're query parameters, not path segments (`/events/city/Edinburgh`), because they don't identify a different resource: it's still the events collection, narrowed down. They can be combined, and they work together with paging and sorting.

The date range is **half-open**: `from` is inclusive and `to` is exclusive. "March" is `from=2030-03-01T00:00:00Z&to=2030-04-01T00:00:00Z`, with no need to work out the last instant of 31 March, and "March" and "April" written this way never overlap.

### Sorting, and why it needs a whitelist

Spring Data's `Pageable` also reads `?sort=`:

```
GET /events?sort=title             by title, A to Z
GET /events?sort=title,desc        by title, Z to A
```

Out of the box, it will sort by **any** property of the entity, including property **paths** through relationships. `?sort=venue.city` works, by joining another table. That's convenient and dangerous:

- **Unknown properties are a `500`.** `?sort=foo` throws a `PropertyReferenceException` deep in Spring Data. The client did something wrong, and the API answers "server error".
- **Any column becomes sortable by anyone,** including columns with no index, which turns a cheap query into a slow one on a big table, and columns you never meant to expose. Sorting by a hidden field leaks information about it, even if the field never appears in the response: sort by it and the order tells you something about the values.
- **The sort parameter becomes part of your API by accident.** Rename an entity field and a client's `?sort=` breaks.

The fix is a **whitelist**: a short list of properties clients may sort by (`startsAt` and `title`), and a `400` for anything else.

**A stable order.** Two events can start at the same time. If the sort is only by `startsAt`, the database may return those two in either order, and may choose differently for page 1 and page 2, so one event appears on both pages and the other on neither. Adding the **id** as a final tie-breaker gives every row a unique position.

### HTTP caching

The fastest request is the one never sent. HTTP has caching built in, and every layer between your API and its client may take part: the HTTP client library, a browser, a company proxy, a CDN in front of the API. They all obey the same headers.

**`Cache-Control`** says what a cache may do with a response:

| Directive | Means |
| --- | --- |
| `max-age=60` | The response is **fresh** for 60 seconds. Until then, a cache may reuse it without asking the server |
| `public` | **Shared** caches (proxies, CDNs) may store it, not only the client's own cache |
| `private` | Only the client's own cache may store it: use this for anything specific to one user |
| `no-cache` | A cache may store it, but must check with the server before **every** reuse |
| `no-store` | Don't store it anywhere: for sensitive data |

An event is public information that changes rarely, so `GET /events/{id}` will send `Cache-Control: max-age=60, public`. The list sends nothing, so caches don't keep it.

**`ETag` and conditional requests.** After 60 seconds, the cached copy is **stale**. It might still be right; the cache just doesn't know. Rather than download the whole event again, it can ask "has it changed since this version?":

```
GET /events/5                       →   200, ETag: "0", and the event
...a minute later...
GET /events/5
If-None-Match: "0"                  →   304 Not Modified, no body
```

An **`ETag`** (entity tag) is an opaque label for one version of a representation. The client stores it with the response, and sends it back in **`If-None-Match`**. If the resource still has that ETag, the server answers **`304 Not Modified`** with no body, and the client uses the copy it has. If the resource has changed, it's a normal `200` with the new body and the new ETag.

- **ETags are quoted strings.** The header is `ETag: "0"`, quotes included, and `If-None-Match` must send them back the same way.
- **Strong and weak.** `"0"` is a **strong** ETag: it promises the bytes are identical. `W/"0"` is **weak**: "equivalent, but maybe not byte-for-byte" (perhaps formatted differently). `If-None-Match` uses weak comparison, so either form matches.

**Where the ETag comes from.** Something has to change whenever the event changes. This API already has exactly that: the `version` column from guide 01, which Hibernate increases on every update. Version 0 is `ETag: "0"`, and after one `PATCH` it's `"1"`. It costs nothing to compute.

The alternative is to **hash the response body**. Spring's `ShallowEtagHeaderFilter` does that for any response. It needs no code, but it still loads the data and builds the whole body before hashing it, so it saves only network bandwidth. A version-based ETag says the same thing more cheaply and more precisely.

One thing to notice: the event's JSON includes its **venue's** name and city, but the ETag is only the **event's** version. If a venue could be renamed, its events' ETags wouldn't change, and clients would keep a stale venue name. Here venues are read-only, so it's correct. If they weren't, the ETag would have to combine both versions.

---

## Steps

### 1. Cap the page size

Add to `application.properties`:

```properties
# ?size=500 is quietly reduced to 100
spring.data.web.pageable.max-page-size=100
```

This applies to every `Pageable` parameter in the application, so any collection you add later gets the same limit.

### 2. The filters, as a record

Create `event/EventFilter.java`:

```java
package com.example.tickets.event;

import java.time.Instant;

/**
 * The optional filters on GET /events, bound from query parameters:
 * ?city=London&category=MUSIC&from=2030-03-01T00:00:00Z&to=2030-04-01T00:00:00Z
 * "from" is inclusive and "to" is exclusive.
 */
public record EventFilter(String city, Category category, Instant from, Instant to) {
}
```

Spring MVC can bind query parameters into an object, not only into separate `@RequestParam`s. A controller parameter of a type like this, with **no annotation**, is bound from the request's parameters by name: `?city=Leeds` becomes the `city` component. For a record, Spring calls its constructor. Parameters that aren't in the URL are `null`, which is how "no filter" will be represented. Strings are converted to `Category` (by the constant's name) and to `Instant` (ISO-8601, such as `2030-03-01T00:00:00Z`) along the way.

Four `@RequestParam(required = false)` parameters would work too. The record keeps the controller's signature short, and gives the service a single thing to pass around.

### 3. Turn the filters into a query

A derived query method can't do this. `findByVenueCityAndCategoryAndStartsAtBetween` would need every filter, every time, and you'd need a different method for every combination. Spring Data JPA's **Specifications** build the `WHERE` clause from pieces, adding only the ones you need.

Create `event/EventSpecifications.java`:

```java
package com.example.tickets.event;

import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/** Builds a WHERE clause from whichever filters were given. */
final class EventSpecifications {

    private EventSpecifications() {
    }

    static Specification<Event> matching(EventFilter filter) {
        List<Specification<Event>> conditions = new ArrayList<>();
        if (filter.city() != null) {
            conditions.add((event, query, cb) ->
                    cb.equal(cb.lower(event.get("venue").get("city")), filter.city().toLowerCase()));
        }
        if (filter.category() != null) {
            conditions.add((event, query, cb) -> cb.equal(event.get("category"), filter.category()));
        }
        if (filter.from() != null) {
            conditions.add((event, query, cb) -> cb.greaterThanOrEqualTo(event.get("startsAt"), filter.from()));
        }
        if (filter.to() != null) {
            conditions.add((event, query, cb) -> cb.lessThan(event.get("startsAt"), filter.to()));
        }
        // No conditions at all means "every event"
        return Specification.allOf(conditions);
    }
}
```

- **A `Specification<Event>` is a lambda** that receives the query's root (`event`, the entity being selected), the query itself, and a `CriteriaBuilder` (`cb`), and returns one condition. It's the JPA Criteria API with the boilerplate removed.
- **`event.get("venue").get("city")`** follows the relationship, so the query joins the venue table. **`cb.lower(...)`** on the column, and `toLowerCase()` on the value, make the city match case-insensitive: `?city=edinburgh` finds Edinburgh.
- **`greaterThanOrEqualTo` and `lessThan`** implement the half-open range: `from` inclusive, `to` exclusive.
- **`Specification.allOf(conditions)`** combines them with `AND`. With an empty list, there's no `WHERE` clause at all. (Older tutorials start with `Specification.where(null)` and chain `.and(…)`. That style isn't needed in Spring Data 4.)

The class and its method are package-private: only `EventService`, in the same package, uses them.

### 4. The repository

Replace `event/EventRepository.java` with:

```java
package com.example.tickets.event;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long>, JpaSpecificationExecutor<Event> {

    // Load each event's venue in the same query (a join), not with one extra SELECT per event
    @Override
    @EntityGraph(attributePaths = "venue")
    Page<Event> findAll(Specification<Event> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "venue")
    Optional<Event> findById(Long id);
}
```

- **`JpaSpecificationExecutor<Event>`** adds methods that take a `Specification`, including `findAll(Specification, Pageable)`, which returns one **`Page`**: the rows for that page, plus the total count.
- **It's overridden only to add `@EntityGraph`**, for the same reason as in guide 01: every event in the page needs its venue. The venue is a *to-one* relationship, so joining it doesn't multiply rows, and the database can still do the paging itself, with an offset and a row limit in the SQL.
- **`findAllByOrderByStartsAtAsc` is gone.** Nothing will use it after the next step.

To fill in `totalElements` and `totalPages`, Spring Data runs a second query, `select count(*)` with the same `WHERE` clause. That's the price of telling clients how many pages there are.

### 5. The service: search, with a sort whitelist

In `event/EventService.java`, add the whitelist at the top of the class:

```java
    /** The only properties clients may sort by. Anything else is a 400, not a slow query or a 500. */
    static final List<String> SORTABLE = List.of("startsAt", "title");
```

and replace the `list()` method with `search`:

```java
    public Page<Event> search(EventFilter filter, Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE.contains(order.getProperty())) {
                throw new InvalidFieldException("sort", "must be one of " + String.join(", ", SORTABLE));
            }
        }
        // Add the id as a tie-breaker, so events that start at the same time keep a stable order across pages
        Pageable stable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                pageable.getSort().and(Sort.by("id")));
        return events.findAll(EventSpecifications.matching(filter), stable);
    }
```

The new imports are `org.springframework.data.domain.Page`, `PageRequest`, `Pageable` and `Sort`.

- **A `Pageable`** carries the page number, the size, and a `Sort`, which is a list of `Sort.Order`s, each with a property and a direction. The loop checks every one the client asked for.
- **`InvalidFieldException`** from guide 02 reports the problem in the usual `errors` shape, against the field `sort`. No new error handling needed.
- **The tie-breaker** builds a new `PageRequest` with the same page and size and the client's sort **followed by** `id`. `?sort=startsAt` becomes `order by starts_at, id`.

### 6. The controller: return a page

In `event/EventController.java`, replace the `list` method:

```java
    @GetMapping
    public PagedModel<EventResponse> list(EventFilter filter,
                                          @PageableDefault(size = 20, sort = "startsAt") Pageable pageable) {
        return new PagedModel<>(events.search(filter, pageable).map(EventResponse::from));
    }
```

with these imports (and remove the now-unused `java.util.List`):

```java
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
```

- **A `Pageable` parameter** is filled in by Spring Data's web support from `?page=`, `?size=` and `?sort=` (more than one `sort` is allowed: `?sort=title&sort=startsAt,desc`). Spring Boot switches that support on for you.
- **`@PageableDefault(size = 20, sort = "startsAt")`** is what a client gets when it sends none of them: the first 20 events, soonest first. That's the same order as before, so a client that ignored paging still sees the soonest events first.
- **`EventFilter filter`** has no annotation, so it's bound from the query parameters as described in step 2.
- **`Page.map(EventResponse::from)`** converts each entity in the page to a DTO and keeps the paging details.
- **`new PagedModel<>(page)`** is the step that decides the JSON.

**Why `PagedModel` and not `Page`.** You *can* return the `Page<EventResponse>` directly, and Jackson will serialise it. But `Page`'s implementation, `PageImpl`, is an internal class, and serialising it writes out everything it has, including details of the `Pageable` and `Sort` objects (see Common errors). Spring Data doesn't promise that structure will stay the same between versions, and logs a warning saying so on every request. `PagedModel` is Spring Data's **stable** JSON shape for a page, made for exactly this:

```json
{"content":[…],"page":{"size":20,"number":0,"totalElements":28,"totalPages":2}}
```

`content` is the list of events, and `page` is everything a client needs to ask for the next one.

Spring Data can also do this conversion globally: with `spring.data.web.pageable.serialization-mode=via-dto` in `application.properties`, every `Page` a controller returns is written as a `PagedModel`, and the JSON is identical. This guide wraps it explicitly so the controller's return type shows what the client gets.

(If you've used **Spring HATEOAS**, it also has a class called `PagedModel`, which adds links. This one is `org.springframework.data.web.PagedModel`, and has no links: see guide 01's sidebar.)

**Update `EventApiTest`.** The body is no longer an array, so `listsEventsSoonestFirst`'s paths need `content`:

```java
        assertThat(response).bodyJson().extractingPath("$.content[0].title").isEqualTo("Bonfire Night Comedy Special");
        assertThat(response).bodyJson().extractingPath("$.content[1].title").isEqualTo("The Paper Lanterns: Live");
```

This is a **breaking change** for any client that expected an array. In a real API with clients already using it, you'd introduce paging from the start, or release it as a new version. (The [Open Banking API](../banking-systems/open-banking-api/05-api-versioning.md) tutorial covers versioning.)

### 7. Query parameters that can't be converted

`?category=OPERA` can't become a `Category`, and `?from=yesterday` can't become an `Instant`. For a bound object like `EventFilter`, Spring reports these as binding failures in a `MethodArgumentNotValidException`, the same exception as a failed `@Valid`, so your guide 02 handler already turns them into an `errors` list. But Spring's default message for a conversion failure is written for Java developers, and names Java types:

```
Failed to convert property value of type 'java.lang.String' to required type 'java.time.Instant' for property 'from'; …
```

In `web/ApiExceptionHandler.java`, change the loop in `handleMethodArgumentNotValid` so binding failures get the same message as guide 02's wrong-typed JSON values:

```java
    /**
     * Bean Validation failures (@Valid), and query parameters that can't be converted:
     * add an "errors" map of field name to message.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new TreeMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            // A conversion failure's default message names Java types, so don't send it to the client
            String message = error.isBindingFailure() ? "has an invalid value" : error.getDefaultMessage();
            errors.putIfAbsent(error.getField(), message);
        }
        ProblemDetail problem = ex.getBody();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }
```

`isBindingFailure()` is `true` when the value couldn't even be converted, and `false` when it was converted and then failed a constraint such as `@NotBlank`, whose message (`must not be blank`) is fine to send.

### 8. Try the collection

Restart. On a fresh database (if you've created events in guide 02, your totals will be higher):

```bash
curl 'localhost:8080/events?size=2'
```

```
{"content":[{"id":1,"title":"Bonfire Night Comedy Special",…},{"id":2,"title":"The Paper Lanterns: Live",…}],"page":{"size":2,"number":0,"totalElements":28,"totalPages":14}}
```

The quotes around the URL stop the shell from treating `?` and `&` as special characters. They work the same way in PowerShell, with `curl.exe`.

Filter by city, in any case, and by category:

```bash
curl 'localhost:8080/events?city=edinburgh&category=THEATRE'
```

```
{"content":[{"id":7,"title":"The Quiet Detective","category":"THEATRE","venue":{"id":6,"name":"Castle Wynd Playhouse","city":"Edinburgh"},"startsAt":"2030-02-21T19:30:00Z","description":"A whodunnit where nobody is allowed to speak."},{"id":19,"title":"The Lighthouse Keeper","category":"THEATRE","venue":{"id":6,"name":"Castle Wynd Playhouse","city":"Edinburgh"},"startsAt":"2030-05-17T19:30:00Z","description":"A one-man show about forty years on a rock."}],"page":{"size":20,"number":0,"totalElements":2,"totalPages":1}}
```

Ask for a page that's too big:

```bash
curl 'localhost:8080/events?size=500'
```

```
…"page":{"size":100,"number":0,"totalElements":28,"totalPages":1}}
```

No error: the size was quietly reduced to 100, and `page.size` says so.

Sort by something that isn't on the list:

```bash
curl 'localhost:8080/events?sort=venue.city'
```

```
{"detail":"Invalid request content.","instance":"/events","status":400,"title":"Bad Request","errors":{"sort":"must be one of startsAt, title"}}
```

And filters that can't be converted:

```bash
curl 'localhost:8080/events?category=OPERA&from=yesterday'
```

```
{"detail":"Invalid request content.","instance":"/events","status":400,"title":"Bad Request","errors":{"category":"has an invalid value","from":"has an invalid value"}}
```

Both wrong values are reported at once, so the client can fix everything in one go.

### 9. `ETag` and `Cache-Control` on one event

In `event/EventController.java`, replace the `get` method:

```java
    @GetMapping("/{id}")
    public ResponseEntity<EventResponse> get(@PathVariable long id) {
        Event event = events.get(id);
        // Returning an ETag lets Spring answer If-None-Match with 304 Not Modified for us
        return ResponseEntity.ok()
                .eTag(String.valueOf(event.getVersion()))
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(EventResponse.from(event));
    }
```

with the imports `org.springframework.http.CacheControl` and `java.time.Duration`.

- **The method now returns `ResponseEntity`**, because it sets headers as well as the body.
- **`.eTag(String.valueOf(event.getVersion()))`** sets the `ETag` header. You pass `0`; Spring adds the quotes, so the header is `ETag: "0"`.
- **`CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic()`** builds `Cache-Control: max-age=60, public`.

And that's all. **The `304` needs no code.** When a controller returns a `ResponseEntity` with an `ETag` for a `GET` request, Spring MVC compares it with the request's `If-None-Match` header itself. If they match, it throws the body away and sends `304 Not Modified` instead.

Notice what that does and doesn't save. Your method still runs, and still loads the event from the database to find its version. What the `304` saves is building the JSON and sending it over the network. For one small event that's modest. For a large representation, or a client on a slow mobile connection, it adds up, and with `max-age`, most requests within the minute never reach the API at all.

### 10. Try a conditional request

These requests put double quotes inside a header, which Windows PowerShell mangles on the way to `curl.exe`. Use Git Bash, or the `requests.http` file in step 11.

```bash
curl -i localhost:8080/events/5
```

```
HTTP/1.1 200
Cache-Control: max-age=60, public
ETag: "0"
Content-Type: application/json

{"id":5,"title":"Manchester Indoor Netball Final","category":"SPORT","venue":{"id":3,"name":"Northern Works","city":"Manchester"},"startsAt":"2030-02-09T14:00:00Z","description":"The regional league decider."}
```

Event 5 has never been changed, so its version and ETag are `0`. Ask again, as a cache would, sending the ETag back:

```bash
curl -i -H 'If-None-Match: "0"' localhost:8080/events/5
```

```
HTTP/1.1 304
Cache-Control: max-age=60, public
ETag: "0"

```

`304`, no body. The `ETag` and `Cache-Control` headers are still there, so the cache can refresh its copy's freshness for another 60 seconds.

Now change the event, and ask with the old ETag:

```bash
curl -X PATCH -H 'Content-Type: application/json' -d '{"description":"Final confirmed."}' localhost:8080/events/5
```

```bash
curl -i -H 'If-None-Match: "0"' localhost:8080/events/5
```

```
HTTP/1.1 200
Cache-Control: max-age=60, public
ETag: "1"

…"description":"Final confirmed."}
```

The `PATCH` increased the version to 1, so the ETag no longer matches, and the client gets the new event and its new ETag.

`GET /events` sends neither header, so caches don't store the list.

> **Looking ahead: guide 05 and `Cache-Control`.** Guide 05 adds Spring Security. Among other things, it adds `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` (plus `Pragma: no-cache` and `Expires: 0`) to responses, so that pages behind a login aren't cached by mistake. It only does that when a response **doesn't already have** a `Cache-Control` header. So `GET /events/{id}` keeps its `max-age=60, public`, on the `304` too, and the tests in step 12 keep passing unchanged. `GET /events`, which sets no `Cache-Control` of its own, gets the `no-cache, no-store` headers. That's fine here. If you wanted the list cacheable as well, you'd give it its own `Cache-Control`, just like the single event.

> **Looking further ahead: guide 07.** The same ETag works in the other direction. A client that sends `If-Match: "1"` with a `PUT` is saying "only replace it if it's still version 1", which stops two organisers overwriting each other's changes. That's guide 07.

### 11. Update `requests.http`

Replace the first request in `requests.http` (`### List events`) with these, keeping the rest of the file as it is:

```http
### List events (first page of 20, soonest first)
GET http://localhost:8080/events

### A smaller page, the second one
GET http://localhost:8080/events?size=5&page=1

### Filter and sort
GET http://localhost:8080/events?city=Edinburgh&category=THEATRE&sort=title,desc

### Filter by date (from is inclusive, to is exclusive)
GET http://localhost:8080/events?from=2030-03-01T00:00:00Z&to=2030-04-01T00:00:00Z

### Sorting by anything else is a 400
GET http://localhost:8080/events?sort=venue.city

### Conditional GET: use the ETag from "One event" (304 Not Modified while it hasn't changed)
GET http://localhost:8080/events/2
If-None-Match: "0"
```

Run **One event** first and look at its `ETag` in the response headers, then run the conditional `GET`. If you've changed event 2 since the database was created, its version won't be `0`: put the ETag you saw into `If-None-Match`.

### 12. Tests

Create `src/test/java/com/example/tickets/event/EventCollectionApiTest.java`:

```java
package com.example.tickets.event;

import com.example.tickets.TicketsIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Paging, filtering and sorting GET /events. Edinburgh and Leeds events are never changed by other tests. */
@TicketsIntegrationTest
class EventCollectionApiTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void returnsAPageWithPageMetadata() {
        var response = mvc.get().uri("/events?size=5");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.content.length()").isEqualTo(5);
        assertThat(response).bodyJson().extractingPath("$.page.size").isEqualTo(5);
        assertThat(response).bodyJson().extractingPath("$.page.number").isEqualTo(0);
        assertThat(response).bodyJson().extractingPath("$.page.totalElements").asNumber().satisfies(
                total -> assertThat(total.intValue()).isGreaterThanOrEqualTo(28));
    }

    @Test
    void theSecondPageHasTheRest() {
        var response = mvc.get().uri("/events?city=Edinburgh&size=3&page=1");

        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "content": [{"title": "Summer Strings"}],
                  "page": {"size": 3, "number": 1, "totalElements": 4, "totalPages": 2}
                }
                """);
    }

    @Test
    void filtersByCityIgnoringCaseAndByCategory() {
        var response = mvc.get().uri("/events?city=edinburgh&category=THEATRE");

        assertThat(response).bodyJson().extractingPath("$.content[*].title")
                .isEqualTo(List.of("The Quiet Detective", "The Lighthouse Keeper"));
    }

    @Test
    void filtersByDateRange() {
        var response = mvc.get().uri("/events?city=Leeds&from=2030-03-01T00:00:00Z&to=2030-05-01T00:00:00Z");

        assertThat(response).bodyJson().extractingPath("$.content[*].title")
                .isEqualTo(List.of("Stand-Up for Beginners", "A Midsummer Night's Dream"));
    }

    @Test
    void sortsByTitle() {
        var response = mvc.get().uri("/events?city=Edinburgh&sort=title,desc");

        assertThat(response).bodyJson().extractingPath("$.content[*].title").isEqualTo(List.of(
                "The Quiet Detective", "The Lighthouse Keeper", "Summer Strings", "Improv Lab"));
    }

    @Test
    void refusesToSortByAnythingElse() {
        var response = mvc.get().uri("/events?sort=venue.city");

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors.sort").isEqualTo("must be one of startsAt, title");
    }

    @Test
    void capsThePageSizeAt100() {
        var response = mvc.get().uri("/events?size=500");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.page.size").isEqualTo(100);
    }

    @Test
    void rejectsFiltersThatCannotBeConverted() {
        var response = mvc.get().uri("/events?category=OPERA&from=yesterday");

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"errors": {"category": "has an invalid value", "from": "has an invalid value"}}
                """);
    }
}
```

- **Shared data again.** Other test classes create events (all at Riverside Hall in London, from `EventWriteApiTest.NEW_EVENT`), so these tests filter on **Edinburgh** and **Leeds**, whose events no test changes. The one test without a filter only checks that there are **at least** 28 events.
- **`extractingPath("$.content[*].title")`**: `[*]` picks `title` from every element and gives you a list, which `isEqualTo(List.of(...))` compares **in order**. That's how the sorting tests check the order, which `isLenientlyEqualTo` wouldn't.
- **`.asNumber().satisfies(...)`** switches to a number assertion, so you can check a range rather than one value.

And `src/test/java/com/example/tickets/event/EventCachingApiTest.java`, with two tests for now:

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
class EventCachingApiTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void anEventHasAnETagAndMayBeCachedForAMinute() {
        var response = mvc.get().uri("/events/5");

        assertThat(response).hasStatusOk();
        assertThat(response).headers().hasValue("ETag", "\"0\"");
        assertThat(response).headers().hasValue("Cache-Control", "max-age=60, public");
    }

    @Test
    void aMatchingIfNoneMatchIs304WithNoBody() {
        var response = mvc.get().uri("/events/5").header("If-None-Match", "\"0\"");

        assertThat(response).hasStatus(HttpStatus.NOT_MODIFIED);
        assertThat(response).body().isEmpty();
        assertThat(response).headers().hasValue("ETag", "\"0\"");
    }
}
```

(`MediaType` is imported for the third test, which you'll write in exercise 1.)

- **`headers().hasValue("ETag", "\"0\"")`** checks a header's exact value. The quotes are part of the value, so they're escaped in the Java string.
- **`.header("If-None-Match", "\"0\"")`** adds a request header.
- **`body().isEmpty()`** checks the raw body has no bytes at all.
- These tests rely on event 5 never being changed by another test. Nothing else in the test suite touches it.

Run all the tests.

---

## Your turn

### Exercise 1: a change means a new ETag

Add a third test to `EventCachingApiTest`, `changingTheEventChangesTheETag`:

1. Create an event (reuse `EventWriteApiTest.NEW_EVENT`), and check that its ETag is `"0"`.
2. `PATCH` its title.
3. `GET` it with `If-None-Match: "0"`, and check that the answer is a `200` with the ETag `"1"`.

Don't use event 5: the other two tests depend on it being unchanged.

<details>
<summary>Solution</summary>

```java
    @Test
    void changingTheEventChangesTheETag() {
        String location = mvc.post().uri("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(EventWriteApiTest.NEW_EVENT)
                .exchange()
                .getResponse()
                .getHeader("Location");
        assertThat(mvc.get().uri(location)).headers().hasValue("ETag", "\"0\"");

        mvc.patch().uri(location)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title": "Changed"}
                        """)
                .exchange();

        var response = mvc.get().uri(location).header("If-None-Match", "\"0\"");
        assertThat(response).hasStatusOk();
        assertThat(response).headers().hasValue("ETag", "\"1\"");
    }
```

The `Location` header from guide 02 earns its keep again: the test never needs to know the new event's id. `NEW_EVENT` is package-private and `static` in `EventWriteApiTest`, so any test in the same package can reuse it. `.exchange()` on the `PATCH` sends it without asserting anything about it; the assertions that matter come after.

</details>

### Exercise 2: what does the client really get?

Predict the `page` part of the response to each of these, then run them:

```bash
curl 'localhost:8080/events?size=0&page=-1'
```

```bash
curl 'localhost:8080/events?size=500'
```

<details>
<summary>Solution</summary>

```
…"page":{"size":20,"number":0,"totalElements":28,"totalPages":2}}
```

```
…"page":{"size":100,"number":0,"totalElements":28,"totalPages":1}}
```

(On a fresh database. Your `totalElements` may be higher.)

Spring Data doesn't reject nonsense page parameters; it **corrects** them. A size of `0` falls back to the default from `@PageableDefault` (20), a negative page becomes page 0, and a size over the maximum becomes the maximum. That's why a client should always read `page.size` and `page.number` from the response rather than assume it got what it asked for.

</details>

### Exercise 3: quoting ETags

Send these two conditional requests for event 5 (in Git Bash, or as two extra requests in `requests.http`). One gets a `304` and one doesn't. Which, and why?

```bash
curl -i -H 'If-None-Match: 0' localhost:8080/events/5
```

```bash
curl -i -H 'If-None-Match: W/"0"' localhost:8080/events/5
```

(If you did step 10's `PATCH`, event 5 is now at version 1. Use `1` instead of `0`, or try it on another event.)

<details>
<summary>Solution</summary>

`If-None-Match: 0` gets a `200` with the full body, every time. An ETag is a **quoted** string, and `0` without quotes isn't the same value as `"0"`, so it never matches. Clients should send back exactly what they received in `ETag`, quotes included.

`If-None-Match: W/"0"` gets a `304`. `W/` marks a **weak** ETag, and `If-None-Match` uses weak comparison, which ignores the `W/` prefix: `W/"0"` and `"0"` match. That's deliberate in HTTP: for "can I keep using my copy?", equivalent is good enough.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 36, Failures: 0, Errors: 0, Skipped: 0
```

(25 from guides 01 and 02, 8 in `EventCollectionApiTest` and 3 in `EventCachingApiTest`, if you did exercise 1.)

New files since guide 02:

```
src/main/java/com/example/tickets/event/
├── EventFilter.java
└── EventSpecifications.java
src/test/java/com/example/tickets/event/
├── EventCachingApiTest.java
└── EventCollectionApiTest.java
```

Modified: `EventRepository.java` (`JpaSpecificationExecutor`, `findAll(Specification, Pageable)` with the entity graph, `findAllByOrderByStartsAtAsc` removed), `EventService.java` (`search` and `SORTABLE`, replacing `list`), `EventController.java` (a paged list, and `ETag` and `Cache-Control` on one event), `ApiExceptionHandler.java` (binding failures), `application.properties` (`max-page-size`), `EventApiTest.java` (`$.content[…]`), `requests.http`.

---

## Common errors

**`Serializing PageImpl instances as-is is not supported, meaning that there is no guarantee about the stability of the resulting JSON structure!`**
A warning, logged on every request, when a controller returns a `Page` instead of a `PagedModel`. The request still works, but the JSON is Spring Data's internal structure, with fields clients shouldn't depend on: `{"content":[…],"empty":false,"first":true,"last":false,"number":0,"numberOfElements":1,"pageable":{"offset":0,"pageNumber":0,"pageSize":1,"paged":true,"sort":{"empty":false,"sorted":true,"unsorted":false},"unpaged":false},"size":1,"sort":{…},"totalElements":28,"totalPages":28}`. Wrap it: `new PagedModel<>(page)`.

**A `500` for `?sort=foo`, and `org.springframework.data.core.PropertyReferenceException: No property 'foo' found for type 'Event'` in the log**
The sort whitelist isn't being checked, so the unknown property reached Spring Data. The body is Spring Boot's default `{"timestamp":"…","status":500,"error":"Internal Server Error","path":"/events"}`, not a `ProblemDetail`. Check the loop at the start of `EventService.search`. (The exception's package moved in Spring Data 4: older answers show `org.springframework.data.mapping.PropertyReferenceException`.)

**`?sort=venue.city` returns `200`, sorted by city**
Same cause. Without the whitelist, any property path in the entity can be sorted by, through joins, by anyone. That's the reason for the whitelist rather than a bug in itself.

**`?size=1000` returns 1000 events**
`spring.data.web.pageable.max-page-size` isn't set (or is misspelt). Without it, Spring Boot's limit is 2000 rows per page: a limit, but far too high.

**`EventApiTest.listsEventsSoonestFirst` fails after step 6**
The body is now a page object, not an array, so `$[0]` finds nothing. Use `$.content[0]`, as in step 6.

**`If-None-Match` never gives a `304`**
The ETag was sent without its quotes (`If-None-Match: 0`), which never matches, or PowerShell stripped the quotes before `curl.exe` saw them. Send exactly what the `ETag` header contained, `"0"`. See exercise 3.

---

## Recap

- Collection endpoints return **pages**, never everything. A `Pageable` parameter reads `page` (from 0), `size` and `sort`; **`@PageableDefault`** sets what a client gets by default.
- Return **`PagedModel`**, not `Page`: a stable `{"content": […], "page": {…}}` shape, instead of Spring Data's internals.
- **Cap the page size** with `spring.data.web.pageable.max-page-size`. Over-size requests are clamped quietly, and `page.size` tells the client.
- **Filters** are query parameters, bound into a record with no annotation, and turned into a query with **Specifications**. Date ranges are half-open: `from` inclusive, `to` exclusive.
- **Whitelist sortable properties**: anything else is a `400`, not a `500` or a slow query. Add the id as a **tie-breaker** for a stable order across pages.
- **`Cache-Control`** says who may cache a response and for how long. An **`ETag`** labels a version; **`If-None-Match`** asks whether it has changed, and **`304 Not Modified`** says no, with no body.
- An entity's **`@Version`** makes a cheap, exact ETag. Return a `ResponseEntity` with `.eTag(…)`, and Spring MVC does the `304` for you.

### Interview angle

<details>
<summary>"How would you design paging for a REST API? Offset or cursor?"</summary>

Offset paging (`page` and `size`, or `offset` and `limit`) is simple, lets clients jump to any page and show "page 3 of 14", and is supported directly by Spring Data. Its weaknesses are that inserts and deletes between requests shift rows across page boundaries, so clients can miss or repeat items, and that deep pages get slow because the database still scans the skipped rows. Cursor (keyset) paging returns an opaque token for "after this row" and queries `where (sort_key, id) > (…)`: stable and fast at any depth, but forward-only. Either way: a default and a **maximum** page size, a deterministic sort with a unique tie-breaker, and page metadata in the response. Mention the cost of the total count on large tables.

</details>

<details>
<summary>"Why not let clients sort by any field?"</summary>

Because the sort parameter then reaches straight into your persistence model. Unknown fields cause server errors; fields without indexes cause slow queries that a client can trigger at will (a resource-consumption risk); fields never meant to be public can be probed through ordering; and renaming an entity field silently breaks clients. A whitelist of supported sort keys, mapped to columns you've indexed and decided to expose, makes sorting part of the documented contract, with a `400` for anything else.

</details>

<details>
<summary>"What's an ETag, and how does a 304 work?"</summary>

An `ETag` is a validator: an opaque, quoted label for one version of a representation, sent as a response header. A client or cache stores it with the response. Later it sends `If-None-Match` with that value; if the resource's current ETag matches, the server answers `304 Not Modified` with no body, and the client keeps using its copy. Otherwise it's a normal `200` with the new body and ETag. ETags can be strong or weak (`W/"…"`); `If-None-Match` uses weak comparison. A good source of ETags is a version number the database already maintains, which is cheaper than hashing the body.

</details>

<details>
<summary>"What's the difference between `no-cache` and `no-store`?"</summary>

`no-store` means don't keep the response anywhere: for sensitive data such as account details. `no-cache` is misleadingly named: a cache **may** store the response, but must revalidate it with the server (for example with `If-None-Match`) before every reuse. `max-age` sets how long a response is fresh, and `public` versus `private` says whether shared caches such as CDNs may store it. Anything that depends on who's asking should be `private` or not cached at all.

</details>

<details>
<summary>"Does a 304 save the server any work?"</summary>

It depends how the ETag is produced. With Spring's `ShallowEtagHeaderFilter`, the server builds the whole response and hashes it, so a `304` saves only bandwidth. With a version-based ETag, the server still loads the entity to read its version, but skips serialising and sending the body. To save the database work too, you'd need a cheaper way to find the current version, such as a lightweight query for the version column only, or a cache. And `max-age` saves the most of all: requests within the freshness period don't reach the server.

</details>

### Further reading

- [RFC 9110: HTTP Semantics](https://www.rfc-editor.org/rfc/rfc9110): [`ETag`](https://www.rfc-editor.org/rfc/rfc9110#section-8.8.3), [`If-None-Match`](https://www.rfc-editor.org/rfc/rfc9110#section-13.1.2) and [`304 Not Modified`](https://www.rfc-editor.org/rfc/rfc9110#section-15.4.5)
- [RFC 9111: HTTP Caching](https://www.rfc-editor.org/rfc/rfc9111), including the `Cache-Control` directives
- [MDN: HTTP caching](https://developer.mozilla.org/en-US/docs/Web/HTTP/Caching), a readable introduction
- [Spring Framework reference: HTTP caching](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-caching.html): `CacheControl`, ETags and `ShallowEtagHeaderFilter`
- [Spring Data JPA reference: Specifications](https://docs.spring.io/spring-data/jpa/reference/jpa/specifications.html)
- [Spring Data Commons reference: web support](https://docs.spring.io/spring-data/commons/reference/repositories/core-extensions.html#core.web), including `Pageable` and `PagedModel`
- [OWASP API Security Top 10: API4:2023 Unrestricted Resource Consumption](https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/)

**Simplification to notice:** the API now works, but nothing describes it properly. Swagger UI still calls the controllers `event-controller`, shows `GET /events`'s filter and paging parameters as two opaque objects, and says nothing about `404`s, `ETag`s or `ProblemDetail`s. A client developer would have to read your code.

**Next:** [04: Documenting the API](04-documenting-the-api.md). OpenAPI properly: descriptions, examples, error responses, and exporting the document for other tools.
