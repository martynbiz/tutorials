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

### 9. The idempotency key table

Back to bookings. Create `src/main/resources/db/migration/V4__idempotency_keys.sql`:

```sql
-- One row per Idempotency-Key a customer has sent with POST /bookings
create table idempotency_key (
    id              bigint generated by default as identity primary key,
    customer_id     varchar(100)             not null,
    idempotency_key varchar(100)             not null,
    -- What was asked for, so the same key with a different request can be refused
    request         varchar(200)             not null,
    -- Null while the first request is still being processed
    booking_id      bigint references booking (id),
    created_at      timestamp with time zone not null,
    -- The database refuses a second row for the same key, even if two requests arrive together
    constraint idempotency_key_unique unique (customer_id, idempotency_key)
);
```

- **`customer_id` and `idempotency_key`** together identify a key: keys belong to a customer. The column is called `idempotency_key`, not `key`, because `key` is a reserved word in some databases.
- **`request`** records what the first request asked for, such as `ticketTypeId=57&quantity=2`, so a different request with the same key can be recognised.
- **`booking_id`** is the booking the key made, and **`null` while the first request is still running**. That one column tells a repeat apart from a request that's still in flight.
- **`created_at`** is for the clean-up job a real system would run, deleting keys older than a day or so.
- **The unique constraint** is the part that copes with two requests arriving together. The service checks for the key first, but two requests can both check before either has inserted. The constraint is what guarantees only one insert succeeds.

### 10. The key, as an entity

Create `booking/IdempotencyKey.java`:

```java
package com.example.tickets.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;

/** An Idempotency-Key a customer has used, and the booking it made. */
@Entity
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String customerId;

    @Column(name = "idempotency_key")
    private String key;

    private String request;

    private Long bookingId;

    private Instant createdAt;

    protected IdempotencyKey() {
        // for JPA
    }

    public IdempotencyKey(String customerId, String key, String request) {
        this.customerId = customerId;
        this.key = key;
        this.request = request;
        this.createdAt = Instant.now();
    }

    public String getRequest() {
        return request;
    }

    /** The booking this key made, or null while the first request is still being processed. */
    public Long getBookingId() {
        return bookingId;
    }

    void setBookingId(Long bookingId) {
        this.bookingId = bookingId;
    }
}
```

And `booking/IdempotencyKeyRepository.java`:

```java
package com.example.tickets.booking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByCustomerIdAndKey(String customerId, String key);
}
```

- **`@Column(name = "idempotency_key")`** maps the field `key` to the column. Everywhere in Java, including the repository's method name, it's `key`.
- **A generated `id`**, rather than making (customer, key) the primary key. With an id you assign yourself, Spring Data's `save()` can't tell a new row from an existing one, so it does a `merge`: a `select`, then an `insert` **or an `update`**. A second request with the same key could quietly **update** the first one's row instead of failing on the constraint. With a generated id, a new `IdempotencyKey` is always an `insert`, and a duplicate always fails.
- **`bookingId` is a plain `Long`**, not a `@ManyToOne Booking`. The service only needs the id, to look the booking up when it replays.
- **`setBookingId` is package-private**, like `Booking.cancel()` in guide 06: only the service in the same package records which booking a key made.

### 11. The idempotency service

Create `booking/IdempotencyService.java`:

```java
package com.example.tickets.booking;

import com.example.tickets.web.ConflictException;
import com.example.tickets.web.InvalidFieldException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponseException;

import java.util.Optional;

/**
 * POST /bookings with an Idempotency-Key header: if the client sends the same request
 * again (say, after a timeout), it gets the first booking back instead of a second one.
 *
 * Deliberately not @Transactional: each step commits on its own, so the key is saved
 * (and visible to other requests) before the booking is made.
 */
@Service
public class IdempotencyService {

    private final IdempotencyKeyRepository keys;
    private final BookingService bookings;

    public IdempotencyService(IdempotencyKeyRepository keys, BookingService bookings) {
        this.keys = keys;
        this.bookings = bookings;
    }

    public record Result(Booking booking, boolean replayed) {
    }

    public Result book(String customerId, String key, BookingRequest request) {
        if (key.isBlank() || key.length() > 100) {
            throw new InvalidFieldException("Idempotency-Key", "must be 1 to 100 characters");
        }
        String requested = "ticketTypeId=" + request.ticketTypeId() + "&quantity=" + request.quantity();

        Optional<IdempotencyKey> previous = keys.findByCustomerIdAndKey(customerId, key);
        if (previous.isPresent()) {
            return replay(customerId, key, previous.get(), requested);
        }

        IdempotencyKey saved;
        try {
            saved = keys.saveAndFlush(new IdempotencyKey(customerId, key, requested));
        } catch (DataIntegrityViolationException e) {
            // Another request with this key saved it between our find and our insert
            throw stillInProgress();
        }
        try {
            Booking booking = bookings.book(customerId, request);
            saved.setBookingId(booking.getId());
            keys.save(saved);
            return new Result(booking, false);
        } catch (RuntimeException e) {
            keys.delete(saved); // nothing was booked, so the client may try again with the same key
            throw e;
        }
    }

    private Result replay(String customerId, String key, IdempotencyKey previous, String requested) {
        if (!previous.getRequest().equals(requested)) {
            throw new ErrorResponseException(HttpStatus.UNPROCESSABLE_CONTENT, ProblemDetail.forStatusAndDetail(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "Idempotency-Key " + key + " was already used for a different request"), null);
        }
        if (previous.getBookingId() == null) {
            throw stillInProgress();
        }
        return new Result(bookings.get(customerId, previous.getBookingId()), true);
    }

    private static ConflictException stillInProgress() {
        return new ConflictException("A request with this Idempotency-Key is still being processed. Try again shortly");
    }
}
```

It wraps guide 06's `BookingService.book`, which doesn't change. In order:

1. **Check the key.** Empty, or longer than the column, is a `400`, reported like any other invalid field: `errors["Idempotency-Key"]`.
2. **Describe the request** as a string: `ticketTypeId=57&quantity=2`. Two requests are "the same" when these strings are equal. A booking request has two fields, so a readable string does the job. An API with bigger bodies would store a hash of the body instead (the [payments](../banking-systems/payments/03-idempotency-in-depth.md) tutorial uses SHA-256).
3. **Look for the key.** If it's there, `replay` decides between three answers. A different request: **`422`**, as an `ErrorResponseException`, like step 4's preconditions. No booking yet: the first request is still running, **`409`**. Otherwise, **the booking it made**, flagged as a replay.
4. **Save the key**, with no booking id yet. **`saveAndFlush`** sends the `insert` to the database **now**, so that if another request saved the same key a moment ago, the unique constraint fails **here**, as a `DataIntegrityViolationException`. That means another request with this key got in first and is still running: **`409`**.
5. **Book**, with guide 06's service and this guide's atomic `sell`.
6. **Record the booking's id** against the key. From now on, a repeat is a replay.
7. **If booking failed** (sold out, the event has started, an unknown ticket type), **delete the key** and rethrow. Nothing was booked, so there's nothing to replay, and the client may fix the request and send it with the same key.

**Why isn't this method `@Transactional`?** Because each step needs to be **visible to other requests** as soon as it happens. Without a transaction around the method, each repository call (and `BookingService.book`, which has its own) commits on its own. The key row is committed in step 4, before the booking starts, so a second request with the same key finds it in step 3 and answers `409`, instead of booking. If the whole method were one transaction, the key wouldn't be visible to anyone until the booking had committed too. The [payments](../banking-systems/payments/03-idempotency-in-depth.md) tutorial makes the **opposite** choice, with the key and the work in one transaction, and explains why it suits payments: it's worth reading the two side by side.

**The replay is the booking as it is now**, not a stored copy of the first response: `bookings.get(customerId, …)` loads it again. If alice has cancelled the booking since, the replay says `CANCELLED`. That's a simplification. Stripe and the IETF draft store the first response and return it exactly. For a booking API, "here's your booking" is what a retrying client needs, and storing responses would mean another column and another serialisation. And `get` is guide 06's ownership-checked lookup, so even a replay can only ever return the caller's own booking.

**A gap to know about.** Steps 4, 5 and 6 commit separately, so if the server crashed between them, the key would be left with no booking id: in flight forever, and every retry would get a `409`. A real system would treat a key that's been in flight for more than a minute or so as abandoned, or expire keys as the Concepts section described. This guide leaves that out.

### 12. The controller

In `booking/BookingController.java`, inject the `IdempotencyService`, read the header, and build the response in a helper (trimmed: the other methods are unchanged), with an import for `org.springframework.web.bind.annotation.RequestHeader`:

```java
    private final BookingService bookings;
    private final IdempotencyService idempotency;

    public BookingController(BookingService bookings, IdempotencyService idempotency) {
        this.bookings = bookings;
        this.idempotency = idempotency;
    }

    @PostMapping
    @SecurityRequirement(name = OpenApiConfig.OAUTH2, scopes = "bookings")
    @Operation(operationId = "createBooking", summary = "Book tickets",
            description = "Send an Idempotency-Key so a retry can't book twice: the same key and request "
                    + "again returns the first booking.")
    @ApiResponse(responseCode = "201", description = "Booked (or, for a repeated Idempotency-Key, the booking it made)",
            headers = {
                    @Header(name = "Location", description = "The new booking's URL"),
                    @Header(name = "Idempotent-Replayed", description = "true if this is the answer to an earlier request")
            })
    @ApiResponse(responseCode = "400", description = "Invalid request content",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "Not enough tickets left, the event has started, "
            + "or a request with the same Idempotency-Key is still being processed",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "422", description = "The Idempotency-Key was already used for a different request",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    public ResponseEntity<BookingResponse> book(@AuthenticationPrincipal Jwt token,
                                                @Parameter(description = "A unique value you make up for this booking, "
                                                        + "such as a UUID. Send the same one if you retry.")
                                                @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                                @Valid @RequestBody BookingRequest request) {
        if (idempotencyKey == null) {
            return created(bookings.book(token.getSubject(), request), false);
        }
        IdempotencyService.Result result = idempotency.book(token.getSubject(), idempotencyKey, request);
        return created(result.booking(), result.replayed());
    }

    private static ResponseEntity<BookingResponse> created(Booking booking, boolean replayed) {
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/bookings/{id}")
                .buildAndExpand(booking.getId())
                .toUri();
        var response = ResponseEntity.created(location);
        if (replayed) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(BookingResponse.from(booking));
    }
```

- **The header is optional.** Without it, booking is guide 06's, exactly. You *could* require it, as `If-Match` is required, and an API that took payment should. Here it stays optional so existing clients keep working, which is also how Stripe does it.
- **A replay is still a `201`**, with the same `Location` and body as the first answer. A client that never saw the first response can't tell the difference, and doesn't need to: it gets its booking. **`Idempotent-Replayed: true`** (the name Stripe uses) is there for anyone who does want to know, such as someone reading the logs.
- **`fromCurrentContextPath().path("/bookings/{id}")`** builds the `Location` from the application's root, rather than from the current request's URL as guide 06 did. The result is the same, `http://localhost:8080/bookings/2`, but the helper no longer depends on which request called it.
- **The `422` and the new `409`** are documented, so client developers know what to expect.

### 13. Try an idempotency key

Restart the API. Flyway applies `V4` on startup.

The outputs below came from a **fresh database**, where ticket type 57's two Front Row tickets were still for sale. In yours, alice bought them in guide 06. To match the outputs, stop the API, delete the API project's `data` folder, and start it again: Flyway rebuilds everything from the migrations. (Or use another ticket type, and expect different ids and prices.)

Get alice's token from Swagger UI, as in guide 06 step 14, and put it in `ALICE`. In Git Bash, book with a key:

```bash
curl -i -H "Authorization: Bearer $ALICE" -H 'Idempotency-Key: 7f3c9a2e-booking-1' -H 'Content-Type: application/json' -d '{"ticketTypeId": 57, "quantity": 2}' localhost:8080/bookings
```

```
HTTP/1.1 201
Location: http://localhost:8080/bookings/2

{"id":2,"eventId":2,"ticketTypeId":57,"quantity":2,"totalPence":15000,"status":"CONFIRMED","createdAt":"2026-11-13T12:00:45Z"}
```

Now pretend the response was lost, and send **exactly the same request** again:

```
HTTP/1.1 201
Location: http://localhost:8080/bookings/2
Idempotent-Replayed: true

{"id":2,"eventId":2,"ticketTypeId":57,"quantity":2,"totalPence":15000,"status":"CONFIRMED","createdAt":"2026-11-13T12:00:45Z"}
```

The same booking, the same `Location`, and the replay header. Nothing was booked a second time. Send the same key with `"quantity": 1`:

```
HTTP/1.1 422

{"detail":"Idempotency-Key 7f3c9a2e-booking-1 was already used for a different request","instance":"/bookings","status":422,"title":"Unprocessable Content"}
```

And check the ticket type:

```bash
curl localhost:8080/events/2/ticket-types/57
```

```json
{"id":57,"name":"Front Row","pricePence":7500,"capacity":2,"available":0}
```

Two tickets, two sold, one booking, however many times the request was sent.

Swagger UI's **`POST /bookings`** now has an **Idempotency-Key** field. Try it there too: fill in a key of your own, send a booking, then press **Execute** again.

### 14. Update `requests.http`

Every `PUT` and `PATCH` in `requests.http` now needs `If-Match`. Change the two event changes from guide 02, and add a request that shows the `428` (trimmed: the bodies are unchanged):

```http
### Replace an event (PUT: send every field, and the ETag from your last GET in If-Match)
PUT http://localhost:8080/events/29
Authorization: Bearer {{organiserToken}}
If-Match: "0"
Content-Type: application/json
```

```http
### Change part of an event (PATCH: send only what changes). The PUT made it version 1
PATCH http://localhost:8080/events/29
Authorization: Bearer {{organiserToken}}
If-Match: "1"
Content-Type: application/json
```

```http
### Without If-Match (428)
PATCH http://localhost:8080/events/29
Authorization: Bearer {{organiserToken}}
Content-Type: application/json

{"description": "Who changed it last?"}
```

Put the `428` request after **Delete an event**, before **Without a token (401)**. It still gets a `428` after event 29 has been deleted, because the header is checked before the event is looked up (step 6). The `If-Match` values assume event 29 is newly created by **Create an event**, so run that first, then the `PUT`, then the `PATCH`. If your event 29 has been changed before, use the `ETag` from a `GET`.

In the customers' section, add a key to the booking, and a request that reuses it for something else:

```http
### Book two Front Row tickets for event 2. Send it twice: the second answer is the same booking
POST http://localhost:8080/bookings
Authorization: Bearer {{aliceToken}}
Idempotency-Key: 7f3c9a2e-booking-1
Content-Type: application/json

{"ticketTypeId": 57, "quantity": 2}

### The same Idempotency-Key with a different request is a 422
POST http://localhost:8080/bookings
Authorization: Bearer {{aliceToken}}
Idempotency-Key: 7f3c9a2e-booking-1
Content-Type: application/json

{"ticketTypeId": 57, "quantity": 1}
```

A real client makes a new key for every booking, usually a UUID. A fixed one in a file is only for trying replays by hand.

### 15. Tests

**First, the tests this guide broke.** Run the API's tests, and the ones from guides 02 and 03 that change events fail with `428` where they expected a `200` or a `404`. They need `If-Match`.

In `EventWriteApiTest`, the `PUT` test, the `PATCH` test and `updatingAMissingEventIs404` each get the header. The `PUT` test also gets `.exchange()` (trimmed: the assertions are unchanged):

```java
        var response = mvc.put().uri("/events/{id}", id).with(organiser())
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title": "Renamed Gig", "category": "COMEDY", "venueId": 3, "startsAt": "2030-10-01T20:00:00Z"}
                        """)
                .exchange();
```

The `PATCH` test changes the same way. `updatingAMissingEventIs404` only needs the header: without it, the answer is a `428`, because the controller checks the header before the service looks for the event.

**Why `.exchange()` now?** It's guide 06's trap again. Without `.exchange()`, each `assertThat(response)` sends the request **again**. Before this guide, sending the same `PUT` twice did no harm. Now the first one makes the event version 1, so the second, still saying `If-Match: "0"`, gets a `412`, and the test fails on its second assertion.

In `EventCachingApiTest`, the `PATCH` in `changingTheEventChangesTheETag` (guide 03's exercise 1, if you did it) needs the header too (trimmed):

```java
        mvc.patch().uri(location).with(organiser())
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
```

Run the tests again: the 66 from step 3 pass.

**Now the new tests.** Create `src/test/java/com/example/tickets/event/EventIfMatchApiTest.java`:

```java
package com.example.tickets.event;

import com.example.tickets.TicketsIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static com.example.tickets.TestTokens.organiser;
import static org.assertj.core.api.Assertions.assertThat;

/** Optimistic locking: a change must say which version of the event it's changing. */
@TicketsIntegrationTest
class EventIfMatchApiTest {

    static final String CHANGE = """
            {"title": "Changed"}
            """;

    @Autowired
    MockMvcTester mvc;

    @Test
    void aChangeWithoutIfMatchIs428() {
        String event = newEvent();

        var response = mvc.patch().uri(event).with(organiser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(CHANGE)
                .exchange();

        assertThat(response).hasStatus(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"title": "Precondition Required", "status": 428,
                 "detail": "Send an If-Match header with the ETag from your last GET"}
                """);
    }

    @Test
    void aChangeReturnsTheNewETag() {
        String event = newEvent();

        var response = mvc.patch().uri(event).with(organiser())
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CHANGE)
                .exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).headers().hasValue("ETag", "\"1\"");
    }

    @Test
    void theSecondOfTwoChangesFromTheSameVersionIs412() {
        String event = newEvent();
        // Two organisers both GET the event (ETag "0"), then both try to change it
        var first = mvc.patch().uri(event).with(organiser())
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CHANGE)
                .exchange();
        var second = mvc.put().uri(event).with(organiser())
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(EventWriteApiTest.NEW_EVENT)
                .exchange();

        assertThat(first).hasStatusOk();
        assertThat(second).hasStatus(HttpStatus.PRECONDITION_FAILED);
        assertThat(second).bodyJson().isLenientlyEqualTo("""
                {"title": "Precondition Failed", "status": 412,
                 "detail": "It has changed since you read it. GET it again for the new ETag"}
                """);
        assertThat(mvc.get().uri(event)).bodyJson().extractingPath("$.title").isEqualTo("Changed");
    }

    private String newEvent() {
        return mvc.post().uri("/events").with(organiser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(EventWriteApiTest.NEW_EVENT)
                .exchange()
                .getResponse()
                .getHeader("Location");
    }
}
```

- **Each test makes its own event**, at version 0, so the `If-Match` values are certain whatever other tests have done to the seeded events.
- **`theSecondOfTwoChangesFromTheSameVersionIs412`** is the two-organiser story from the Concepts section: a `PATCH` and then a `PUT`, both from version `"0"`. The last line checks the first change **survived**. A refused change must change nothing.
- **`newEvent()`** returns the new event's `Location`, which `mvc.patch().uri(...)` accepts as it is.

Create `src/test/java/com/example/tickets/booking/IdempotencyApiTest.java`:

```java
package com.example.tickets.booking;

import com.example.tickets.TicketsIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.UUID;

import static com.example.tickets.TestTokens.alice;
import static com.example.tickets.TestTokens.bob;
import static com.example.tickets.booking.Bookings.available;
import static com.example.tickets.booking.Bookings.newTicketType;
import static com.example.tickets.booking.Bookings.request;
import static org.assertj.core.api.Assertions.assertThat;

@TicketsIntegrationTest
class IdempotencyApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    IdempotencyKeyRepository keys;

    @Test
    void aRetryWithTheSameKeyGetsTheFirstBookingBack() throws Exception {
        long ticketType = newTicketType(mvc, 10);
        String key = UUID.randomUUID().toString();

        var first = book(alice(), key, request(ticketType, 2));
        var retry = book(alice(), key, request(ticketType, 2));

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(retry).hasStatus(HttpStatus.CREATED);
        assertThat(retry).headers().hasValue("Idempotent-Replayed", "true");
        assertThat(retry).headers().hasValue("Location", first.getResponse().getHeader("Location"));
        assertThat(retry).bodyJson().isStrictlyEqualTo(first.getResponse().getContentAsString());
        assertThat(available(mvc, ticketType)).isEqualTo(8); // booked once, not twice
    }

    @Test
    void theSameKeyForADifferentRequestIs422() {
        long ticketType = newTicketType(mvc, 10);
        String key = UUID.randomUUID().toString();
        book(alice(), key, request(ticketType, 2));

        var response = book(alice(), key, request(ticketType, 3));

        assertThat(response).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().extractingPath("$.detail")
                .isEqualTo("Idempotency-Key " + key + " was already used for a different request");
    }

    @Test
    void aKeyWhoseFirstRequestIsStillRunningIs409() {
        long ticketType = newTicketType(mvc, 10);
        String key = UUID.randomUUID().toString();
        // As if alice's first request had saved its key and was still booking
        keys.save(new IdempotencyKey("alice", key, "ticketTypeId=" + ticketType + "&quantity=1"));

        var response = book(alice(), key, request(ticketType, 1));

        assertThat(response).hasStatus(HttpStatus.CONFLICT);
        assertThat(response).bodyJson().extractingPath("$.detail")
                .isEqualTo("A request with this Idempotency-Key is still being processed. Try again shortly");
        assertThat(available(mvc, ticketType)).isEqualTo(10);
    }

    @Test
    void eachCustomerHasTheirOwnKeys() {
        long ticketType = newTicketType(mvc, 10);
        String key = "my-first-booking";

        var alices = book(alice(), key, request(ticketType, 1));
        var bobs = book(bob(), key, request(ticketType, 1));

        assertThat(bobs).hasStatus(HttpStatus.CREATED);
        assertThat(bobs).headers().doesNotContainHeader("Idempotent-Replayed");
        assertThat(bobs.getResponse().getHeader("Location")).isNotEqualTo(alices.getResponse().getHeader("Location"));
    }

    @Test
    void aBookingThatFailsDoesNotUseUpTheKey() {
        long ticketType = newTicketType(mvc, 1);
        String key = UUID.randomUUID().toString();

        var tooMany = book(alice(), key, request(ticketType, 2));
        var fewer = book(alice(), key, request(ticketType, 1));

        assertThat(tooMany).hasStatus(HttpStatus.CONFLICT);
        assertThat(fewer).hasStatus(HttpStatus.CREATED);
    }

    private MvcTestResult book(JwtRequestPostProcessor customer, String key, String body) {
        return mvc.post().uri("/bookings").with(customer)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }
}
```

- **`book(...)`** sends a booking with a key and **`.exchange()`s it**, so each call is exactly one request. `MvcTestResult` is the type `.exchange()` returns.
- **A fresh `UUID` per test**, as a real client would make, so tests never collide in the shared database. `eachCustomerHasTheirOwnKeys` uses a fixed key on purpose: the point is that alice and bob both use it.
- **`aRetryWithTheSameKeyGetsTheFirstBookingBack`** compares the two bodies with `isStrictlyEqualTo`, as guide 06 did for check-ins, and checks only **two** tickets were sold.
- **`aKeyWhoseFirstRequestIsStillRunningIs409`** can't easily make a real request pause halfway. So it does what that request would have done by step 4: saves the key with no booking id, through the repository. Then a request with that key must get a `409`, and book nothing.
- **`aBookingThatFailsDoesNotUseUpTheKey`** asks for two tickets of a type with one. The `409` deletes the key, so the same key works for a request for one.

Run all the API's tests: **74 pass**.

---

## Your turn

### Exercise 1: recover from a `412`

A client that gets a `412` should fetch the event again and retry with the new ETag. Add a test to `EventIfMatchApiTest`, `afterA412TheClientGetsTheNewETagAndTriesAgain`:

1. Make an event, and `PATCH` it with `If-Match: "0"`.
2. `PUT` it with `If-Match: "0"`. Expect a `412`.
3. `GET` the event and read its `ETag` header.
4. `PUT` again with that ETag. Expect a `200`, and say what the new ETag should be.

<details>
<summary>Solution</summary>

```java
@Test
void afterA412TheClientGetsTheNewETagAndTriesAgain() {
    String event = newEvent();
    mvc.patch().uri(event).with(organiser())
            .header("If-Match", "\"0\"")
            .contentType(MediaType.APPLICATION_JSON)
            .content(CHANGE)
            .exchange();

    var stale = mvc.put().uri(event).with(organiser())
            .header("If-Match", "\"0\"")
            .contentType(MediaType.APPLICATION_JSON)
            .content(EventWriteApiTest.NEW_EVENT)
            .exchange();
    String current = mvc.get().uri(event).exchange().getResponse().getHeader("ETag");
    var retry = mvc.put().uri(event).with(organiser())
            .header("If-Match", current)
            .contentType(MediaType.APPLICATION_JSON)
            .content(EventWriteApiTest.NEW_EVENT)
            .exchange();

    assertThat(stale).hasStatus(HttpStatus.PRECONDITION_FAILED);
    assertThat(current).isEqualTo("\"1\"");
    assertThat(retry).hasStatusOk();
    assertThat(retry).headers().hasValue("ETag", "\"2\"");
}
```

The `PATCH` made it version 1, so the `GET` says `"1"`. The `ETag` header's value includes its quotes, so it can go straight back into `If-Match`. The `PUT` changes the title back from `"Changed"` to `"Test Gig"`, so Hibernate writes it, and the version becomes 2.

A real client would show the organiser what changed before retrying. Sending the same `PUT` blindly with the new ETag is exactly the overwrite `If-Match` was meant to stop. The test does it only to prove the ETag from the `GET` works.

</details>

### Exercise 2: predict the idempotency answers

Sign in to Swagger UI as **alice**, and in a private window as **bob** (guide 06 step 14). For each request, predict the status, whether there's an `Idempotent-Replayed` header, and the booking's id and `status` or the problem's `detail`. Then send it. All of them are `POST /bookings` with **Idempotency-Key** `exercise-2`, except step 3.

1. Alice: `{"ticketTypeId": 3, "quantity": 1}`.
2. Alice: exactly the same again.
3. Alice: cancel that booking (`POST /bookings/{id}/cancel`).
4. Alice: step 1's request again.
5. Alice: `{"ticketTypeId": 3, "quantity": 2}`.
6. Bob: `{"ticketTypeId": 3, "quantity": 1}`.

<details>
<summary>Solution</summary>

1. `201`, a new booking (call its id N), `"status":"CONFIRMED"`, no replay header.
2. `201`, booking N again, with `Idempotent-Replayed: true`. Ticket type 3's `available` didn't change.
3. `200`, booking N is `CANCELLED`, and its ticket goes back on sale.
4. `201`, booking N, `Idempotent-Replayed: true`, and **`"status":"CANCELLED"`**. The replay is the booking **as it is now**, not a copy of the first response (step 11). A retry can't un-cancel a booking or book again: the key already made booking N.
5. `422`: `Idempotency-Key exercise-2 was already used for a different request`. The request string is now `ticketTypeId=3&quantity=2`, not `…quantity=1`.
6. `201`, a **new** booking for bob, with no replay header. Keys belong to a customer, so bob's `exercise-2` has never been used.

</details>

### Exercise 3: two cancels at once

`BookingService.cancel` still reads the booking's status in Java, then decides. Suppose alice's app sends `POST /bookings/2/cancel`, times out, and sends it again, and both arrive at the same moment. Without writing code:

1. What could go wrong, and what would `available` show afterwards?
2. Which technique from this guide fixes it, and what would the query look like?
3. Why would adding `@Version` to `Booking` be a worse fix in **this** API, given step 7?

<details>
<summary>Solution</summary>

1. Both requests read the booking as `CONFIRMED`, both pass the check, both set it to `CANCELLED` (harmless: it ends up cancelled either way), and **both call `release`**. Two tickets go back on sale for a booking of one, so `available` is one higher than it should be, and the API can later sell a ticket that doesn't exist. It's the read-check-write race again, with cancelling instead of booking.
2. An atomic conditional update on the booking, so only one request can move it from `CONFIRMED` to `CANCELLED`:

   ```java
   @Modifying
   @Query("update Booking b set b.status = com.example.tickets.booking.BookingStatus.CANCELLED "
           + "where b.id = :id and b.status = com.example.tickets.booking.BookingStatus.CONFIRMED")
   int cancelIfConfirmed(long id);
   ```

   The service releases the tickets only if it returns 1. (This sketch isn't in the reference code, and wasn't run. Wiring it in needs the care from step 3: the loaded `Booking` is now stale, so the service mustn't change its status afterwards, or Hibernate would write it back over whatever is in the database.)
3. With `@Version`, the second cancel's update would fail Hibernate's version check, and step 7's handler would turn that into **`412 Precondition Failed`**: "It has changed since you read it. GET it again for the new ETag". Alice's app sent no `If-Match` and has no ETag for a booking, so that answer makes no sense to it. The conditional update lets the second cancel see the booking already cancelled and answer `200`, as guide 06's rules say a repeated cancel should.

</details>

---

## Checkpoint

In the **api**:

```bash
./mvnw test
```

```
[INFO] Tests run: 74, Failures: 0, Errors: 0, Skipped: 0
```

(65 from guide 06, 1 in `LastTicketRaceTest`, 3 in `EventIfMatchApiTest` and 5 in `IdempotencyApiTest`. With the test from exercise 1, you'll have 75.) The **auth-server** hasn't changed: its 11 tests still pass.

New files in the api:

```
src/main/java/com/example/tickets/
├── booking/
│   ├── IdempotencyKey.java
│   ├── IdempotencyKeyRepository.java
│   └── IdempotencyService.java
└── web/
    └── Preconditions.java
src/main/resources/db/migration/
└── V4__idempotency_keys.sql
src/test/java/com/example/tickets/
├── booking/
│   ├── IdempotencyApiTest.java
│   └── LastTicketRaceTest.java
└── event/
    └── EventIfMatchApiTest.java
```

Modified in the api: `BookingService.java` (atomic `sell` and `release`), `BookingController.java` (`Idempotency-Key`, `422` documented), `TicketTypeRepository.java` (`sell` and `release` queries), `TicketType.java` (`sell` and `release` removed), `EventService.java` (expected version), `EventController.java` (`If-Match` in, `ETag` out, `412` and `428` documented), `ApiExceptionHandler.java` (`OptimisticLockingFailureException`), `requests.http`, `BookingApiTest.java` (the `409` message), `EventWriteApiTest.java` and `EventCachingApiTest.java` (`If-Match`).

---

## Common errors

**`LastTicketRaceTest` fails with `[successful bookings] expected: 5L but was: 20L`**
In full:

```
[ERROR]   LastTicketRaceTest.neverSellsMoreTicketsThanThereAre:70 [successful bookings]
expected: 5L
 but was: 20L
```

The booking is still read-check-write: guide 06's code, or something that reads `sold` into Java and writes it back, such as calling `ticketType.sell(...)` on the entity. Use the repository's `sell` (step 2), and check `TicketType` no longer has `sell` and `release` (step 3).

**Every booking is a `500`, and the log shows `InvalidDataAccessApiUsageException`**
In full:

```
org.springframework.dao.InvalidDataAccessApiUsageException: Query executed via 'getResultList()' or 'getSingleResult()' must be a 'select' query [update TicketType t set t.sold = t.sold + :quantity where t.id = :id and t.capacity - t.sold >= :quantity]
```

caused by `org.hibernate.query.IllegalSelectQueryException: Expecting a SELECT Query … but found … SqmUpdateStatement`. The `@Query` has no `@Modifying`, so Spring Data ran the update as if it were a `select`. Add `@Modifying` to `sell` and `release`.

**`428` on every `PUT` and `PATCH`, from requests that worked before this guide**

```json
{"detail":"Send an If-Match header with the ETag from your last GET","instance":"/events/3","status":428,"title":"Precondition Required"}
```

Working as intended: changes now need `If-Match`. That includes the curl commands in guides 02 and 03, and the tests from those guides (step 15). `GET` the event, and send its `ETag` in `If-Match`.

**`412` even though you just read the event**
Someone (or one of your own earlier requests) changed it after your `GET`, so your ETag is out of date. `GET` it again. If you're sure it hasn't changed, look at what you sent: a weak `W/"0"`, `*` or anything that isn't a version number never matches, by design (step 4).

**An event test that used to pass fails with `412` on its second assertion**
The request wasn't `.exchange()`d, so the assertion sent it again. The first `PUT` made the event version 1, and the re-sent one still said `If-Match: "0"`. Add `.exchange()` (step 15).

**`422` when booking with a key that worked a minute ago**

```json
{"detail":"Idempotency-Key 7f3c9a2e-booking-1 was already used for a different request","instance":"/bookings","status":422,"title":"Unprocessable Content"}
```

The key belongs to an earlier booking with a different ticket type or quantity. A key is for **one** booking and its retries: make a new key, such as a UUID, for every new booking.

**`409` with `A request with this Idempotency-Key is still being processed. Try again shortly`**
Another request with the same key is booking right now: wait a moment, then send it again, and you'll get the replay. If it never clears, the first request died between saving the key and recording the booking (the gap in step 11). On your development database, use a new key.

**`400` with `"errors":{"Idempotency-Key":"must be 1 to 100 characters"}`**
The header was empty, or longer than the column allows. A UUID is 36 characters.

---

> **Sidebar: H2 vs PostgreSQL.** This series runs on H2, and everything in this guide was verified there. Most real APIs run on a database such as PostgreSQL, so here's what would and wouldn't change. (The PostgreSQL behaviour below is from its documentation; the series has no PostgreSQL to run it on.) **The conditional update behaves the same.** On H2 (READ COMMITTED by default, with row locks) the second writer waits for the row lock, then evaluates its `where capacity - sold >= :quantity` against the committed row, and updates nothing. PostgreSQL at READ COMMITTED does the same: after waiting, it re-checks the `where` against the newly committed version of the row. At the stricter **REPEATABLE READ** or **SERIALIZABLE** levels, PostgreSQL would instead fail the second transaction with a **serialization failure** (SQLSTATE `40001`), which the application must catch and retry. On either database, a **`check (sold <= capacity)`** constraint on `ticket_type` would be a cheap safety net under the code. **The naive version fails the same way on both**: Hibernate writes the `sold` worked out in Java, so concurrent bookings overwrite each other. `select … for update` (`@Lock(LockModeType.PESSIMISTIC_WRITE)` on a repository method) would also fix it on both, at the cost of holding the lock until commit. **The idempotency table** relies on its unique constraint on both. On PostgreSQL, "insert the key, or find it's already there" could be one statement, `insert … on conflict (customer_id, idempotency_key) do nothing returning id`. H2 has `merge` but no `on conflict`, so this guide finds, then inserts, and treats a unique violation as "in flight". And on PostgreSQL, a failed `insert` **aborts the whole transaction** it's in: one more reason the service saves the key in a transaction of its own.

> **Sidebar: allocated seating.** Tickets in this API are general admission: each ticket type has a capacity, and a booking takes some of it. A real theatre sells **seats**: row F, seat 12. Allocated seating would change the model and, interestingly, make this guide's race easier to solve. There would be a **`seat`** table for each venue's seat map, and each booked ticket would reference a specific seat, with a **unique constraint on (event, seat)** so two bookings can't hold the same seat at the same event. The last-ticket race becomes the **same-seat** race, and the database's unique constraint settles it, exactly as it settles two requests with the same idempotency key, with no counter to keep right. Booking would become **hold, then confirm**: choosing seats on a seat map takes a customer a minute or two, so the seats are held for them (with an expiry, like the payment holds in guide 06's sidebar) and confirmed when they pay. Most of the new work is in that hold: what happens when it expires, and what the seat map shows other customers while it lasts.

---

## Recap

- A **race condition** is a gap between a check and the write that depends on it. Two requests can both pass the check. **`@Transactional` doesn't close the gap**: two transactions can read the same committed value and both act on it.
- **Prove a race with a test first**: many threads, a `CountDownLatch` as a starting gun, and assertions on the totals. Guide 06's read-check-write sold 20 tickets of 5.
- An **atomic conditional `UPDATE`** (`set sold = sold + :q where … capacity - sold >= :q`) checks and changes in one statement. The rows-changed count says whether you won. In Spring Data that's `@Query` with **`@Modifying`**, returning an `int`.
- Don't change an entity's field that a bulk update also changes: Hibernate would write the stale value back.
- **Lost updates between clients** need the version the **client** read. Send the `ETag` back in **`If-Match`**: a match applies the change and returns the new `ETag`, a mismatch is **`412`**, and a missing header is **`428`**. Hibernate's `@Version` covers the last milliseconds before commit; map its `OptimisticLockingFailureException` to the same `412`.
- **`ErrorResponseException`** sends any status with a `ProblemDetail`, with no new exception class or handler.
- **Retries are unavoidable**, and a retried `POST` repeats its effect. An **`Idempotency-Key`** names the operation, not the attempt: the same key and request again **replays** the first answer, a different request is **`422`**, and a request still running is **`409`**. Keys belong to a customer, and a failed request doesn't use up its key.
- A **unique constraint** is what settles two identical requests that arrive together. Check first for a friendly answer, and let the constraint guarantee it.
- **`409`** is the resource's state, **`412`** a false precondition, **`422`** unprocessable content, **`428`** a missing precondition.

### Interview angle

<details>
<summary>"Two customers try to buy the last ticket at the same moment. How do you make sure only one gets it?"</summary>

Don't read the count into the application and then decide, because both requests can read "1 left" before either writes. Make the database do the check and the change together, with a conditional update: `update ticket_type set sold = sold + 1 where id = ? and capacity - sold >= 1`. The row lock makes the second update wait, then re-evaluate its `where` against the committed row, so it updates nothing, and the application turns "0 rows" into a `409`. Alternatives are a pessimistic lock (`select … for update`), which holds the row for the whole transaction, or optimistic locking with a version column, which makes the losers fail and retry even when tickets were left. And prove it with a concurrent test before fixing it.

</details>

<details>
<summary>"Why doesn't @Transactional prevent race conditions?"</summary>

A transaction gives atomicity (all or nothing) and isolation from **uncommitted** changes, but at the usual default, READ COMMITTED, two transactions can both read the same committed value, both decide on it, and both write. Each transaction is correct on its own; together they lose an update. You need something that serialises the decision: a conditional update, a row lock, a version check, a unique constraint, or a stricter isolation level with retries.

</details>

<details>
<summary>"What's the difference between optimistic and pessimistic locking? When would you use each?"</summary>

Pessimistic locking takes a lock when you read (`select … for update`), so others wait until you finish. It's simple and certain, but holds locks and can queue or deadlock under load. Optimistic locking takes no lock: it records a version when you read, and the write succeeds only if the version is unchanged, otherwise it fails and the caller retries or reports a conflict. Optimistic suits data that's rarely changed by two people at once, such as an event's details, and it's the only option across HTTP requests, where you can't hold a database lock while a user thinks. Pessimistic, or better a conditional update, suits hot rows that many requests change at once, such as a stock count.

</details>

<details>
<summary>"How do ETags prevent lost updates in a REST API? What are 412 and 428?"</summary>

The server sends an `ETag` with each representation, here the entity's version. A client that wants to change it sends that value back in `If-Match`. The server applies the change only if the current ETag still matches, and returns the new one. If someone else changed the resource first, the precondition is false, and the answer is `412 Precondition Failed`: the client should re-read and decide again. `428 Precondition Required` is for a request that should have had a precondition and didn't. Making `If-Match` required stops clients that don't bother from overwriting other people's changes. Note that `If-Match` uses strong comparison, so weak ETags never match.

</details>

<details>
<summary>"What's an idempotency key, and how would you implement one?"</summary>

A unique value the client generates per operation, such as a UUID per booking, and sends in an `Idempotency-Key` header, reusing it only for retries. The server stores each key, scoped to the caller, with a fingerprint of the request and the result. A repeat with the same request returns the original result instead of repeating the work. The same key with a different request is a client bug: `422`. A repeat while the first is still running: `409`. A unique constraint on (caller, key) handles duplicates that arrive at the same moment. If the operation fails, release the key so the client can retry. Keys expire after a while, typically a day.

</details>

<details>
<summary>"Two requests with the same idempotency key arrive at exactly the same time. What happens?"</summary>

Both look the key up and neither finds it, so a "check first" alone isn't enough. Both try to insert it, and the unique constraint lets exactly one succeed. The other gets a constraint violation, which the server turns into `409`, "still being processed". When the client retries a moment later, the first request has finished, so it gets the replay. For that to work, the key's insert must be visible to the other request, which is why this API commits the key in its own transaction before doing the booking.

</details>

### Further reading

- [RFC 9110: HTTP Semantics](https://www.rfc-editor.org/rfc/rfc9110): [`If-Match`](https://www.rfc-editor.org/rfc/rfc9110#section-13.1.1), [`412 Precondition Failed`](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.13) and [`422 Unprocessable Content`](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.21)
- [RFC 6585 section 3: `428 Precondition Required`](https://www.rfc-editor.org/rfc/rfc6585#section-3)
- [The `Idempotency-Key` HTTP header field](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/), the IETF draft, including when to answer `409` and `422`
- [Stripe: idempotent requests](https://docs.stripe.com/api/idempotent_requests), the design most APIs copy
- [Spring Data JPA reference: modifying queries](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.modifying-queries)
- [PostgreSQL: transaction isolation](https://www.postgresql.org/docs/current/transaction-iso.html), on what READ COMMITTED does when two updates meet
- [Core ledger 04: statements, reversals and idempotency](../banking-systems/core-ledger/04-statements-reversals-and-idempotency.md), for idempotent money transfers, why the idempotency check must come after taking the locks, and more on `409` versus `422`. Its guide [03: transfers and concurrency](../banking-systems/core-ledger/03-transfers-and-concurrency.md) goes further into locking.
- [Payments 03: idempotency in depth](../banking-systems/payments/03-idempotency-in-depth.md), with stored responses, SHA-256 fingerprints, key expiry, and the key saved in the same transaction as the work

**Simplification to notice:** the API now copes with customers who arrive together and clients that retry, but it still trusts every caller to behave. Nothing stops one client sending thousands of booking requests a second. A browser app on another origin couldn't call it at all. Responses carry none of the security headers a browser expects. And an error nobody planned for still gets whatever Spring's defaults say.

**Next:** [08: Hardening](08-hardening.md). Rate limiting with `429 Too Many Requests`, CORS for browser apps, security headers, error messages that don't leak, and the OWASP API Security Top 10 as a checklist.
