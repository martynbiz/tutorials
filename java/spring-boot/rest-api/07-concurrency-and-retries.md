# 07: Concurrency and retries

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](README.md) for version notes.

## What you'll learn

- What a **race condition** is, and why "read, check, then write" sells the last ticket twice, even inside a transaction
- How to **prove** a race with a test: twenty threads, one starting gun, and a `CountDownLatch`
- How an **atomic conditional `UPDATE`** makes the database check and change a row in one step, and why that fixes the race
- **Lost updates** between clients, and **optimistic locking** over HTTP: guide 03's `ETag`, sent back in **`If-Match`**
- **`412 Precondition Failed`** and **`428 Precondition Required`**, and why this API makes `If-Match` compulsory
- Why the `@Version` that events have had since guide 01 wasn't enough on its own
- Why clients **retry**, why retrying a `POST` is dangerous, and how an **`Idempotency-Key`** header makes it safe
- The answers an idempotency key can get: a **replay** of the first booking, **`422`** for a key reused with a different request, and **`409`** while the first request is still running
- How a **unique constraint** settles two requests that arrive at exactly the same moment

By the end, twenty customers racing for five tickets get exactly five bookings, and the other fifteen get a `409`. An organiser can't silently overwrite a change they never saw. And a customer whose booking request times out can send it again, with the same key, and get the booking they already have instead of a second one.

---

## Prerequisites for this guide

You need both projects as they were at the end of [06: Users and their bookings](06-users-and-their-bookings.md): the `api` with all 65 tests passing, and the `auth-server` with all 11.

For trying things out by hand you also need:

- **The auth-server running** (port 9000), then the API, as in guide 06.
- **Alice's token**, from Swagger UI's Authorize button (guide 06 step 14), and the **organiser's token**, from curl (guide 05 step 10).
- **Git Bash**, IntelliJ's HTTP client or Swagger UI for requests with a JSON body, as before.

You don't need to have written multi-threaded Java before. The one test that uses threads explains each piece as it goes.

---

## Concepts

### Two requests at the same moment

A web server doesn't handle one request at a time. Tomcat, inside Spring Boot, keeps a pool of threads, and each request runs on its own. Two customers who press **Book** at the same moment are two threads running `BookingService.book` **at the same time**, each in its own transaction.

Guide 06's `book` does this:

1. **Read** the ticket type: how many are left?
2. **Check**: are there enough?
3. **Write**: add the quantity to `sold`, and save a booking.

Here's ticket type 57, with one Front Row ticket left, and two customers who want it:

```
 Request A                               Request B                              ticket_type 57 in the database
                                                                                capacity 2, sold 1
 1. read: sold is 1, so 1 left
                                         1. read: sold is 1, so 1 left
 2. 1 left, 1 wanted: fine
                                         2. 1 left, 1 wanted: fine
 3. sold = 2, save booking, commit                                              sold 2
                                         3. sold = 2, save booking, commit      sold 2
```

Both customers get a booking. One ticket has been sold twice, and the count doesn't even show it: `sold` says 2, because each request wrote the number **it** worked out in Java, and B's 2 simply replaced A's 2. That's called a **lost update**, and the gap between a check and the write that depends on it is a **race condition**: who wins depends on timing nobody controls.

**`@Transactional` doesn't prevent this.** A transaction makes a request's writes all-or-nothing, and at the **READ COMMITTED** isolation level (the default for H2 and PostgreSQL) it means you never see another transaction's uncommitted changes. It doesn't stop two transactions reading the same committed value and both acting on it. Both reads above were perfectly correct when they happened.

The window is tiny, a few milliseconds, so it never shows up when you test one request at a time. That's what makes races dangerous: everything works until the evening tickets go on sale and ten thousand people want the last few seats in the same second, which is exactly when it matters.

### Let the database do the check

The fix is to stop reading the number into Java at all. Ask the database to check and change the row **in one statement**:

```sql
update ticket_type set sold = sold + 1 where id = 57 and capacity - sold >= 1
```

The database locks a row while it updates it. If A's statement is updating row 57, B's statement **waits** for A's transaction to finish, and then evaluates its `where` against the row **as A left it**: `capacity - sold` is now 0, so the condition is false and B's statement changes nothing.

The database tells you how many rows a statement changed. **1 means you sold the tickets; 0 means there weren't enough.** No decision depends on a value read earlier, so there's no gap for another request to slip into. This is an **atomic conditional update**, and it's the simplest correct answer whenever the rule fits in a `where` clause.

There are two other well-known fixes. Both work, and both cost more here:

| Technique | How | The cost |
|---|---|---|
| **Atomic conditional update** | One `update … where` that checks and changes together | The rule must fit in one statement |
| **Pessimistic locking** | Read the row with `select … for update` (in JPA, `@Lock(PESSIMISTIC_WRITE)`), so other transactions wait until you commit | Locks are held for the whole transaction, and every booking for that ticket type queues behind them |
| **Optimistic locking** | A `@Version` on `TicketType`: Hibernate's update includes `where version = ?`, and fails if someone else changed the row first | Losers fail with an error and must retry, even when there were tickets left for them |

The third deserves a second look, because you'll use it in a minute for something else. With twenty customers racing for five tickets, most of them would read the same version, and all but the first writer would fail with an optimistic-locking error, including customers who could have had a ticket. The code would have to retry them. Optimistic locking suits rows that are **rarely** changed at the same moment. A ticket type on sale night is the opposite.

### Lost updates between clients

Events have a different problem. Two organisers both open event 3 in their tools:

```
 Organiser A                                   Organiser B                                 event 3
 GET /events/3        ETag: "0"                GET /events/3        ETag: "0"              version 0
 PATCH: add "Now with a support act."                                                      version 1
                                               PUT: the whole event, as B read it,          version 2
                                               with B's new start time
```

B's `PUT` sends every field, including the description **as it was when B read it**. A's support act is gone, and nobody was told. It's another lost update, but this time the gap isn't milliseconds inside the server: it's however long B spent looking at the event before pressing **Save**.

**Doesn't the event's `@Version` catch this?** Events have had a `version` column since guide 01, and Hibernate checks it: every update it writes says `where id = ? and version = ?`. But Hibernate compares with the version **it** read, at the start of **this** request's transaction. B's `PUT` is a new request. It reads the event (version 1, with A's change), applies B's fields over it, and writes version 2. From Hibernate's point of view nothing went wrong.

What's missing is the version **the client** saw. It has to travel with the request, and HTTP already has a header for it. Guide 03 gave every event an `ETag` built from its version, and promised it would work in the other direction too:

```
PUT /events/3
If-Match: "0"
```

**`If-Match`** means "only do this if the resource's current `ETag` is still `"0"`". If it is, the change goes ahead, and the response carries the **new** `ETag`. If it isn't, someone else changed it first, and the server refuses with **`412 Precondition Failed`**. B's tool can then `GET` the event again, show B what changed, and let B decide.

This is **optimistic locking over HTTP**: assume nobody else is editing, and check at the end. The alternative, pessimistic locking, would mean "check out" the event and lock it until B saves, which would need the server to remember who's editing what, and decide what happens when B goes home without saving. An optimistic check needs nothing but the version.

### Compulsory, not optional

An `If-Match` that's optional only protects careful clients. A client that never sends it can still overwrite anybody's change. So this API **requires** it on `PUT` and `PATCH`, and a change without it gets **`428 Precondition Required`** ([RFC 6585](https://www.rfc-editor.org/rfc/rfc6585#section-3)): "this server only accepts conditional changes; send `If-Match`".

That's a **breaking change** for existing clients. Every `PUT` and `PATCH` from guides 02 and 03, and from your `requests.http`, now needs `If-Match`. On a live API you'd announce it, and probably accept requests without it for a while. In this series, you'll update the tests and `requests.http` in this guide.

Two details from HTTP's rules, which the code follows:

- **`If-Match` uses strong comparison.** A weak ETag such as `W/"0"` never matches (guide 03 said `If-None-Match` ignores the `W/`; `If-Match` doesn't). This API's ETags are always strong.
- **`If-Match: *`** means "if it exists at all". This API doesn't support it, and answers `412`: a client that wants to change an event must say which version it read.

### Retries: did it work or not?

A customer's app sends `POST /bookings`, and the connection drops before the answer arrives. Three things could have happened:

1. The request never reached the API.
2. It arrived, and failed.
3. It arrived, the booking was made, and the **response** was lost.

The app can't tell which. For `GET`, `PUT` and `DELETE` that doesn't matter: guide 01 said they're **idempotent**, so sending them again has the same effect as sending them once, and the app can just retry. `POST` isn't. In case 3, retrying books the tickets a second time. If the booking took payment, the customer pays twice.

The standard answer is an **idempotency key**. The client makes up a unique value for **each booking it means to make**, typically a UUID, and sends it in a header:

```
POST /bookings
Idempotency-Key: 7f3c9a2e-booking-1

{"ticketTypeId": 57, "quantity": 2}
```

If it has to retry, it sends **the same key** again. The server remembers each key and the booking it made. The first request with a key books; a repeat gets **the same booking back**, without booking again. The key belongs to the *operation*, "alice books two Front Row tickets", not to one HTTP attempt at it. Stripe popularised the header, and the IETF is standardising it ([the `Idempotency-Key` draft](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/)).

Every way a key can arrive needs an answer:

| A request arrives with a key that… | Answer |
|---|---|
| hasn't been seen | Book as normal: `201` |
| was used for **the same** request, which finished | **Replay**: `201`, the same `Location` and booking, and `Idempotent-Replayed: true` |
| was used for **a different** request | **`422 Unprocessable Content`**. The client has a bug: it reused a key for a new booking |
| was used for a request that's **still running** | **`409 Conflict`**: try again shortly |
| was used for a request that **failed** (sold out, say) | Nothing was booked, so the key is forgotten, and this request is handled as new |

Without a key, booking works exactly as in guide 06. Guide 02 said this API uses `400` for every invalid request, and keeps `422` for one different situation in guide 07. This is it: the JSON is perfectly valid, but it can't be processed with that key.

**Keys belong to a customer.** Alice's key `abc` and bob's key `abc` are different keys. If they weren't, bob could send alice's key and have alice's booking "replayed" to him: guide 06's BOLA, through a side door.

**Two requests with the same key at the same moment** are the race from the top of this guide again: both look for the key, neither finds it, both book. So the key is stored in a table with a **unique constraint** on (customer, key). The database accepts the first insert and refuses the second, however close together they arrive, and the refused one answers `409`.

**How long are keys kept?** In this guide, forever. Real APIs delete them after a while: Stripe, for example, keeps them for at least 24 hours. A retry after a network error comes within seconds or minutes, not next week. The table has a `created_at` column so a clean-up job could delete old keys; this guide doesn't build one.

### Four status codes

This guide uses four status codes, which are easy to mix up:

| Code | Means | Here |
|---|---|---|
| **`409 Conflict`** | Valid, but it clashes with the resource's current state | Not enough tickets left; the event has started; a request with this key is still running |
| **`412 Precondition Failed`** | The condition the client sent (`If-Match`) is false | The event has changed since the client read it |
| **`422 Unprocessable Content`** | Well-formed, but can't be processed as sent | An idempotency key reused for a different request |
| **`428 Precondition Required`** | The server requires a condition, and none was sent | `PUT` or `PATCH` without `If-Match` |

---

## Steps

### 1. Prove the race first

**Don't change any code yet.** A bug you can't reproduce is a bug you can't prove you've fixed, so the first step is a test that fails.

`BookingService.book` is still guide 06's read-check-write. This is the **wrong** version, the one this guide replaces:

```java
if (ticketType.getAvailable() < request.quantity()) {
    throw new ConflictException("Only " + ticketType.getAvailable() + " tickets of this type are left");
}
ticketType.sell(request.quantity());          // TicketType: public void sell(int q) { this.sold += q; }
return bookings.save(new Booking(customerId, ticketType, request.quantity()));
```

Create `src/test/java/com/example/tickets/booking/LastTicketRaceTest.java`:

```java
package com.example.tickets.booking;

import com.example.tickets.TicketsIntegrationTest;
import com.example.tickets.event.TicketTypeRepository;
import com.example.tickets.web.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.example.tickets.booking.Bookings.newTicketType;
import static org.assertj.core.api.Assertions.assertThat;

/** Many customers try to buy the last few tickets at the same moment. */
@TicketsIntegrationTest
class LastTicketRaceTest {

    static final int CAPACITY = 5;
    static final int CUSTOMERS = 20;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    BookingService bookingService;

    @Autowired
    BookingRepository bookings;

    @Autowired
    TicketTypeRepository ticketTypes;

    @Test
    void neverSellsMoreTicketsThanThereAre() throws Exception {
        long ticketType = newTicketType(mvc, CAPACITY);
        CountDownLatch startingGun = new CountDownLatch(1);

        List<Future<String>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(CUSTOMERS)) {
            for (int i = 0; i < CUSTOMERS; i++) {
                String customer = "racer-" + i;
                results.add(pool.submit(() -> {
                    startingGun.await(); // every thread waits here, then they all go at once
                    try {
                        bookingService.book(customer, new BookingRequest(ticketType, 1));
                        return "booked";
                    } catch (ConflictException e) {
                        return "sold out";
                    }
                }));
            }
            startingGun.countDown();
        }

        List<String> outcomes = new ArrayList<>();
        for (Future<String> result : results) {
            outcomes.add(result.get());
        }
        long booked = outcomes.stream().filter("booked"::equals).count();
        long bookingRows = bookings.findAll().stream()
                .filter(booking -> booking.getTicketType().getId() == ticketType)
                .count();

        assertThat(booked).as("successful bookings").isEqualTo(CAPACITY);
        assertThat(bookingRows).as("booking rows").isEqualTo(CAPACITY);
        assertThat(ticketTypes.findById(ticketType).orElseThrow().getSold()).as("sold").isEqualTo(CAPACITY);
    }
}
```

Twenty customers, five tickets, one ticket each. The right answer is five bookings and fifteen "sold out". Piece by piece:

- **`Executors.newFixedThreadPool(CUSTOMERS)`** makes an `ExecutorService` with twenty threads: one per customer, so they really can run at the same time.
- **`pool.submit(() -> …)`** hands a task to the pool and returns a **`Future`**: a handle to the task's result, which isn't ready yet. Each task returns `"booked"` or `"sold out"`.
- **`CountDownLatch startingGun = new CountDownLatch(1)`** is the starting gun. A latch is a counter that threads can wait on: **`await()`** blocks until the count reaches zero, and **`countDown()`** lowers it by one. Every task starts by waiting. When all twenty have been submitted, the one `countDown()` releases them together. Without it, the first threads would finish before the last ones started, and there'd be no race to see.
- **`try (ExecutorService pool = …)`**: since Java 19, an `ExecutorService` can be closed with try-with-resources, and **closing it waits for every task to finish**. After the block, all twenty bookings have been tried.
- **`result.get()`** collects each task's answer. If a task had thrown something other than a `ConflictException`, `get()` would throw it here and fail the test.
- **It calls `BookingService.book` directly**, not through `MockMvcTester`. The race is in the service, and calling it directly keeps the test simple and the threads truly parallel. Each call gets its own transaction from `@Transactional`, as a real request would.
- **Three assertions**: how many calls succeeded, how many booking rows exist for **this** ticket type (other tests share the database), and what the ticket type's `sold` says. A correct fix makes all three equal the capacity.

Run it, against guide 06's code: click the green arrow next to the class in IntelliJ, or in a terminal:

```bash
./mvnw test -Dtest=LastTicketRaceTest
```

```
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 18.25 s <<< FAILURE! -- in com.example.tickets.booking.LastTicketRaceTest
[ERROR]   LastTicketRaceTest.neverSellsMoreTicketsThanThereAre:70 [successful bookings]
expected: 5L
 but was: 20L
```

**All twenty customers got a ticket, for a ticket type with five.** It failed like this every time it was run while this guide was written. Every thread read the ticket type before any of them had committed, every one saw enough tickets left, and every one booked. (AssertJ stops at the first failed assertion, so the other two never ran.)

A word of warning about race tests in general: they're about probability. Twenty threads and a starting gun make the overlap close to certain on this code, but a race test that **passes** once doesn't prove there's no race. This one earns its keep by failing reliably on the wrong code, and passing reliably on the right one.

### 2. Sell in one statement

Add two queries to `event/TicketTypeRepository.java`:

```java
package com.example.tickets.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TicketTypeRepository extends JpaRepository<TicketType, Long> {

    List<TicketType> findByEventIdOrderByPricePenceAsc(long eventId);

    Optional<TicketType> findByIdAndEventId(long id, long eventId);

    /**
     * Sells tickets if there are enough left, in one statement: the database checks and
     * changes the row together, so two customers can't both buy the last ticket.
     * Returns how many rows changed: 1 if sold, 0 if there weren't enough left.
     */
    @Modifying
    @Query("update TicketType t set t.sold = t.sold + :quantity where t.id = :id and t.capacity - t.sold >= :quantity")
    int sell(long id, int quantity);

    /** Puts tickets back on sale (also in one statement, so it can't undo someone else's sale). */
    @Modifying
    @Query("update TicketType t set t.sold = t.sold - :quantity where t.id = :id")
    int release(long id, int quantity);
}
```

- **`@Query`** gives Spring Data the query to run instead of deriving one from the method name. It's **JPQL**, not SQL: it names the **entity** (`TicketType`) and its **fields** (`t.sold`, `t.capacity`), and Hibernate translates it into the `update ticket_type set sold = …` from the Concepts section.
- **`@Modifying`** tells Spring Data this query changes data, so it runs it as an update and returns the **number of rows changed**, as an `int`. Leave it out and Spring Data treats the query as a `select`, and every booking fails (see Common errors).
- **`:quantity` and `:id`** are named parameters, matched to the method's parameter names. That works because Spring Boot's Maven setup compiles with parameter names kept.
- **`sell`** is the conditional update: `t.capacity - t.sold >= :quantity` is the "are there enough?" check, inside the statement that does the selling.
- **`release`** has no condition, but it's still one statement. Guide 06's `release` read `sold` into Java, subtracted, and wrote the result back: if a sale committed in between, that write would have **undone the sale**. `sold = sold - :quantity` is worked out by the database, on the current value.

### 3. Use it in the service

In `booking/BookingService.java`, replace the check and the `sell` call in `book`:

```java
    @Transactional
    public Booking book(String customerId, BookingRequest request) {
        TicketType ticketType = ticketTypes.findById(request.ticketTypeId())
                .orElseThrow(() -> new InvalidFieldException("ticketTypeId",
                        "ticket type " + request.ticketTypeId() + " does not exist"));
        if (hasStarted(ticketType)) {
            throw new ConflictException("The event has already started");
        }
        // Not "if (available < quantity) ... sold += quantity": between the check and the
        // update, another request can sell the same tickets. The database does both at once.
        if (ticketTypes.sell(ticketType.getId(), request.quantity()) == 0) {
            throw new ConflictException("Not enough tickets of this type are left");
        }
        return bookings.save(new Booking(customerId, ticketType, request.quantity()));
    }
```

And in `cancel`, release the tickets with the repository instead of the entity (trimmed: one line changes):

```java
                booking.cancel();
                ticketTypes.release(booking.getTicketType().getId(), booking.getQuantity());
                return booking;
```

Then **delete** `sell` and `release` from `event/TicketType.java`. Nothing should change `sold` through the entity any more. `getAvailable()` stays.

Some things to notice:

- **The ticket type is still loaded**, for the "has the event started?" check and the booking's price. Reading it is fine. What mattered was **deciding** from the `sold` it read, and the decision is now the database's.
- **The loaded `TicketType` is out of date** after `sell`: an `@Modifying` query goes straight to the database, past the `TicketType` object Hibernate already has, so that object still holds the old `sold`. That's harmless here, because Hibernate only writes back entities whose fields **changed** (its *dirty checking*), and nothing changes this one. It's exactly why the entity's `sell` and `release` had to go: calling either would change the field, Hibernate would write the stale number back at commit, and the race would be back.
- **The `409` message changed**, from `Only 1 tickets of this type are left` to `Not enough tickets of this type are left`. After an update that changed nothing, the service doesn't know how many are left without reading again, and by the time it had, the number could have changed. A count that might already be wrong is worse than no count.
- **`@Transactional` still matters.** The update and the booking's insert commit together. If saving the booking failed, the sale would roll back with it.

The changed message breaks one test. In `BookingApiTest.cannotBookMoreTicketsThanAreLeft`, change the expected detail (trimmed):

```java
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"title": "Conflict", "status": 409, "detail": "Not enough tickets of this type are left"}
                """);
```

Run `LastTicketRaceTest` again. It passes: five booked, five rows, `sold` is 5. Run it a few more times; it keeps passing. Then run all the API's tests: 66 pass.

### 4. Preconditions: `428` and `412`

Now the organisers. Create `web/Preconditions.java`:

```java
package com.example.tickets.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Optimistic locking over HTTP. A client sends back the ETag it was given (If-Match: "3")
 * to say "change this only if it's still version 3". Spring MVC turns an
 * ErrorResponseException into a ProblemDetail response for us.
 */
public final class Preconditions {

    private Preconditions() {
    }

    /** The version in an If-Match header, such as "3". Missing: 428 Precondition Required. */
    public static long ifMatchVersion(String ifMatch) {
        if (ifMatch == null) {
            throw new ErrorResponseException(HttpStatus.PRECONDITION_REQUIRED, ProblemDetail.forStatusAndDetail(
                    HttpStatus.PRECONDITION_REQUIRED, "Send an If-Match header with the ETag from your last GET"), null);
        }
        try {
            return Long.parseLong(ifMatch.replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw changedSinceYouReadIt();
        }
    }

    /** 412 Precondition Failed: someone else changed it after the client read it. */
    public static ErrorResponseException changedSinceYouReadIt() {
        return new ErrorResponseException(HttpStatus.PRECONDITION_FAILED, ProblemDetail.forStatusAndDetail(
                HttpStatus.PRECONDITION_FAILED, "It has changed since you read it. GET it again for the new ETag"), null);
    }
}
```

- **`ErrorResponseException`** is Spring's own exception for "send this status with this `ProblemDetail`". `ApiExceptionHandler` extends `ResponseEntityExceptionHandler`, which already knows how to turn one into a response, so there's **no new exception class and no new handler**. The titles come out as the standard reason phrases, `"Precondition Required"` and `"Precondition Failed"`. (Guide 06 wrote its own `ConflictException` because it's thrown in several places with different messages. These two have one message each.)
- **`ifMatchVersion`** turns `"3"` into `3`: it strips the quotes, which are part of the header's value (guide 03), and parses the number.
- **A header that isn't a number** gets a `412`, not a `400`. `W/"0"` and `*` end up here. Neither can match one of this API's ETags, and "your precondition is false" is the honest answer.

### 5. Check the version in the service

In `event/EventService.java`, `replace` and `patch` take the version the client expects, and fetch the event with a new private `get` that checks it (trimmed: the field changes are as before), with an import for `com.example.tickets.web.Preconditions`:

```java
    /** PUT: replace every field. */
    @Transactional
    public Event replace(long id, long expectedVersion, EventRequest request) {
        Event event = get(id, expectedVersion);
        event.setTitle(request.title());
```

```java
    /** PATCH: change only the fields that were sent. */
    @Transactional
    public Event patch(long id, long expectedVersion, EventPatch patch) {
        Event event = get(id, expectedVersion);
        if (patch.title() != null) {
```

```java
    /**
     * The event, if it's still the version the client last read. (If someone changes it
     * between this check and our commit, Hibernate's @Version check catches that instead.)
     */
    private Event get(long id, long expectedVersion) {
        Event event = get(id);
        if (event.getVersion() != expectedVersion) {
            throw Preconditions.changedSinceYouReadIt();
        }
        return event;
    }
```

`get(id)` is the existing one, so a missing event is still a `404`. Then the comparison: the version in the database against the version in the client's `If-Match`. Different means someone changed it since the client read it: `412`, and nothing is changed.

**Is that check itself a race?** Between reading the version here and committing, another request could change the event. This is where the `@Version` from guide 01 earns its place. Hibernate's update says `where id = ? and version = ?`, with the version it read **in this transaction**. If another transaction committed in between, the update matches no row, and Hibernate throws an optimistic-locking exception instead of overwriting. So there are two checks, each covering a different gap:

| Gap | Covered by |
|---|---|
| Between the client's `GET` and its `PUT` (seconds to hours) | `If-Match`, compared in `get(id, expectedVersion)` |
| Between that comparison and the commit (milliseconds) | Hibernate's `@Version` |

Step 7 turns the second one into the same `412`.

### 6. The controller: `If-Match` in, `ETag` out

In `event/EventController.java`, `replace` and `patch` read the header, and return the event with its new `ETag`. Here's `replace` (trimmed: the `400`, `404`, `401` and `403` responses after the new ones are unchanged), with an import for `org.springframework.web.bind.annotation.RequestHeader` and `com.example.tickets.web.Preconditions`:

```java
    @PutMapping("/{id}")
    @SecurityRequirement(name = OpenApiConfig.OAUTH2, scopes = "events:write")
    @Operation(operationId = "replaceEvent", summary = "Replace an event",
            description = "Send every field. A field you leave out is cleared. "
                    + "Send the ETag from your last GET in If-Match, so you don't overwrite someone else's change.")
    @ApiResponse(responseCode = "200", description = "The updated event",
            headers = @Header(name = "ETag", description = "The event's new version"))
    @ApiResponse(responseCode = "412", description = "The event has changed since the ETag in If-Match",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "428", description = "No If-Match header",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
```

```java
    public ResponseEntity<EventResponse> replace(@Parameter(description = "The event's id", example = "2") @PathVariable long id,
                                                 @Parameter(description = "The ETag from your last GET", example = "\"0\"")
                                                 @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                 @Valid @RequestBody EventRequest request) {
        return withETag(events.replace(id, Preconditions.ifMatchVersion(ifMatch), request));
    }
```

`patch` changes the same way. Its description gains `"Send the ETag from your last GET in If-Match."`, it gets the same three new responses, and its method becomes:

```java
    public ResponseEntity<EventResponse> patch(@Parameter(description = "The event's id", example = "2") @PathVariable long id,
                                               @Parameter(description = "The ETag from your last GET", example = "\"0\"")
                                               @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                               @Valid @RequestBody EventPatch patch) {
        return withETag(events.patch(id, Preconditions.ifMatchVersion(ifMatch), patch));
    }

    /** The changed event, with its new ETag (Hibernate increased the version when it saved). */
    private static ResponseEntity<EventResponse> withETag(Event event) {
        return ResponseEntity.ok()
                .eTag(String.valueOf(event.getVersion()))
                .body(EventResponse.from(event));
    }
```

- **`@RequestHeader(name = "If-Match", required = false)`** binds the header to a `String`, or `null` when it's missing. Why not `required = true`? Because then Spring would answer a missing header itself, with a `400` (a `MissingRequestHeaderException`). The right answer is `428`, so the parameter is optional and `Preconditions` decides.
- **`withETag`** returns the event with its **new** ETag, the same way guide 03's `GET` does. After a first change, that's `"1"`. Hibernate increases the version when it writes the change, which happens when `EventService`'s transaction commits, as `replace` returns. By the time the controller reads `event.getVersion()`, it's the new one. The client can use it for its next change without another `GET`.
- **The order of the answers** follows from where each check is. `@Valid` runs before the method, so an invalid body is a `400` whether or not `If-Match` was sent. The header is checked in the controller, before the service looks for the event, so a missing header is a `428` even for an event that doesn't exist. With the header, a missing event is a `404`, and a stale version is a `412`.

`DELETE` doesn't need `If-Match` here. Deleting doesn't overwrite anybody's change, and guide 06 already refuses to delete an event with bookings.

### 7. The last few milliseconds

When Hibernate's `@Version` check fails, at commit, Spring translates Hibernate's exception into its own **`OptimisticLockingFailureException`**. Without a handler, that's a `500`. It means exactly what a `412` means, so add a handler to `web/ApiExceptionHandler.java`, next to the `ConflictException` one (trimmed), with an import for `org.springframework.dao.OptimisticLockingFailureException`:

```java
    /** Hibernate's @Version check: someone else changed the row after we read it. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handle(OptimisticLockingFailureException ex) {
        return Preconditions.changedSinceYouReadIt().getBody();
    }
```

`getBody()` is the `ProblemDetail` inside the exception, so both kinds of failure give the client the same `412` with the same detail. The client doesn't care which of the two checks caught it: either way, it should `GET` the event again.

No test triggers this one. It needs two transactions to interleave inside those few milliseconds, which a test can't arrange reliably without reaching into Hibernate. It's a short handler for a rare case, and it turns what would be a `500` into an answer the client can act on.

### 8. Try `If-Match`

Run the tests now and some fail: guides 02 and 03's tests change events without `If-Match`. Step 15 fixes them. The app itself is fine, so start the auth-server, then the API, and get an organiser token as in guide 05. In Git Bash:

```bash
curl -u organiser:organiser-secret -d grant_type=client_credentials -d scope=events:write http://localhost:9000/oauth2/token
```

```bash
ORGANISER_TOKEN=eyJraWQ...
```

These requests send JSON, so use Git Bash (or `requests.http`, step 14). Change event 3 the guide 06 way, without `If-Match`:

```bash
curl -i -X PATCH -H "Authorization: Bearer $ORGANISER_TOKEN" -H 'Content-Type: application/json' -d '{"description": "Now with a support act."}' localhost:8080/events/3
```

```
HTTP/1.1 428

{"detail":"Send an If-Match header with the ETag from your last GET","instance":"/events/3","status":428,"title":"Precondition Required"}
```

Event 3 has never been changed, so its ETag is `"0"`. (If you've changed it, `curl -i localhost:8080/events/3` shows its current `ETag`: use that.) Send it with `If-Match`:

```bash
curl -i -X PATCH -H "Authorization: Bearer $ORGANISER_TOKEN" -H 'If-Match: "0"' -H 'Content-Type: application/json' -d '{"description": "Now with a support act."}' localhost:8080/events/3
```

```
HTTP/1.1 200
ETag: "1"

{"id":3,"title":"Winter Open Mic","category":"COMEDY","venue":{"id":2,"name":"The Lantern Rooms","city":"London"},"startsAt":"2030-01-25T20:00:00Z","description":"Now with a support act."}
```

The change, and its new ETag. Now be the second organiser, who also read version `"0"` and wants a different description:

```bash
curl -i -X PATCH -H "Authorization: Bearer $ORGANISER_TOKEN" -H 'If-Match: "0"' -H 'Content-Type: application/json' -d '{"description": "Something else."}' localhost:8080/events/3
```

```
HTTP/1.1 412

{"detail":"It has changed since you read it. GET it again for the new ETag","instance":"/events/3","status":412,"title":"Precondition Failed"}
```

The support act survives. The second organiser's tool should now `GET` event 3, show them the new description, and let them try again with `If-Match: "1"`.

Swagger UI shows the new `If-Match` field on both operations, with `"0"` as its example. You'd need an organiser's token in Swagger UI to use it, though, and guide 06 kept those to curl and `requests.http`.
