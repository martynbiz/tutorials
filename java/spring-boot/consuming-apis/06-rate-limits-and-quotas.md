# 06: Rate limits and quotas

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](README.md) for version notes.

## What you'll learn

- How API-Football tells you where you stand: **two pairs of headers** on every response, one for the **minute** and one for the **day**
- Why the importer **believes the provider's numbers** and never keeps its own count
- How to pause for the per-minute limit **before the next request**, and only for **what's left of the minute**
- How to treat a **`429 Too Many Requests`** as a safety net: wait, try again, but only a couple of times
- How to **stop cleanly** when the day's quota is gone, record the import as **`INCOMPLETE_DAILY_QUOTA`**, send nothing more until **00:00 UTC**, and carry on later without paying twice
- How a **`ClientHttpRequestInterceptor`** sees every request a `RestClient` makes, the HTTP interface's included
- Why **time should be injected** like any other dependency, so a test of a 60-second wait takes milliseconds
- How to make the fake provider run out of requests on purpose, with **WireMock scenarios**, **templated file names** and mapping files that hold **several stubs**

By the end, the importer sends requests as fast as the provider allows and no faster. When a minute's requests run out, it waits for the minute to pass and carries on. When the day's run out, the import stops with everything it received so far kept, and says why. You'll watch all of it happen against the fake provider, with the pause shortened to 5 seconds so you don't have to wait.

---

## Prerequisites for this guide

You need `importer` and `fake-provider` as they were at the end of [05: Imports in the background](05-imports-in-the-background.md), with all **48** tests passing. In particular:

- `POST /imports/teams/{apiId}` answers **`202 Accepted`** with a `Location: /imports/{id}`, and `ImportRunner` does the work in the background (`@Async`).
- `GET /imports/{id}` shows the import's status, counts, skipped reasons and **`quotaRemaining`**, which has been `null` so far. This guide fills it in.
- `ImportStatus` already has **`INCOMPLETE_DAILY_QUOTA`**, unused until now.
- Every endpoint needs the admin's HTTP Basic credentials: `curl -u admin:dev-only-password …`.
- An import fetches a profile **only for players we don't have yet** ([guide 04](04-squads-and-players.md)), so importing a team a second time is cheap.
- The fake provider has a `misbehaving/` folder, switched on with `POST /__admin/mappings` and off with `POST /__admin/mappings/reset` ([guide 02](02-reading-the-providers-replies.md), step 10).

No API-Football account is needed. There's one optional real request at the end, if you have a key.

---

## Concepts

### Two limits, and how the provider tells you

API-Football's free plan allows **10 requests a minute** and **100 a day**. A first import of a 25-player club is 2 + 25 = 27 requests, so a single import can run into both.

You don't have to guess where you stand. Every response carries four headers. These are from a real response, captured with a real key while the series was being planned:

```
x-ratelimit-limit: 10
x-ratelimit-remaining: 9
x-ratelimit-requests-limit: 100
x-ratelimit-requests-remaining: 89
```

| Header | Means |
| --- | --- |
| `x-ratelimit-limit` | Requests allowed per minute |
| `x-ratelimit-remaining` | Requests left **this minute**, counting the one just answered |
| `x-ratelimit-requests-limit` | Requests allowed per day |
| `x-ratelimit-requests-remaining` | Requests left **today** |

The names are confusing (`ratelimit` is the minute, `ratelimit-requests` is the day), so it's worth keeping that table to hand. Two things are **missing**, and that matters later: there's **no header saying when** the minute or the day starts again, and **no `Retry-After`**. The responses also say `Cache-Control: no-store`: nothing in between may keep a copy, so every request really goes to API-Football and really counts.

The fake provider has been sending the same four headers since guide 01, with fixed values (`9` and `89`). Nothing read them. This guide does.

### Believe the provider, not your own count

You could count requests yourself: 100 a day, minus one for each request sent. It would be wrong in ways you can't see:

- **Other clients share the key.** A `curl` you ran to check something, a second copy of the importer, a test on another machine. They all spend the same quota, and your counter doesn't know.
- **The provider's rules aren't yours.** The malformed request in guide 02 (`team=abc`) appeared to count against the daily quota. Would your counter have counted it? Would it count a request that timed out?
- **Your counter restarts with the application.** The provider's doesn't.

The provider is the only one who knows. So the importer reads **both** remaining-counts after **every** response, and believes them. The flip side: when a response arrives **without** the headers (some error replies, below), all it can do is react to what the body says.

### The minute: wait before the next request, not after the last one

When a response says `x-ratelimit-remaining: 0`, the next request in the same minute would be refused. So wait. But two details make the difference between waiting sensibly and wasting time:

**Wait before the next request, not straight after the reply that said 0.** If that reply answered the last request of an import, there's no next request, and a 60-second wait would just hold the import open for nothing.

**Wait only for what's left of the minute.** If the next request comes along 45 seconds after the reply that said 0, only 15 seconds are left to wait:

```
12:00:00   reply: x-ratelimit-remaining: 0      remember 0, and when
           (nothing to send yet, so no wait)
12:00:45   next request wanted                  60 s - 45 s = 15 s to go: wait 15 s, then send
```

**How long is "the minute"?** API-Football doesn't say when its minute started, or when it ends. So the importer assumes the worst: a full minute from the moment it heard "0". That's safe whether the provider counts in fixed minutes on the clock or over the last 60 seconds; in both cases, a minute after our last request, none of our requests count any more. The 60 seconds is a setting (`api-football.rate-limit.minute-pause`), so it can be changed if the provider changes, and shortened for a demonstration.

### The `429` safety net

If the importer believes the headers, it never sends a request the provider will refuse for the minute. But "never" assumes nobody else is using the key, and that the provider counts exactly as we think. When it does refuse, it answers **`429 Too Many Requests`**.

According to API-Football's documentation and examples people have posted (**not seen live** while writing this series), the per-minute `429` looks like this:

```json
{"get":"teams","parameters":[],"errors":{"rateLimit":"Too many requests. Your rate limit is 10 requests per minute."},"results":0,"paging":{"current":1,"total":1},"response":[]}
```

A `429` for the minute says "not now", not "no". So: treat the minute as used up, wait, and **send the same request again**. But only a couple of times (**2** retries by default, so 3 attempts in all). A provider that keeps saying `429` after two full minutes isn't going to change its mind on the tenth try, and a client that retries for ever is hammering a service that has asked it to stop. After that, the `429` is an error like any other: the import is `FAILED`, with `API-Football answered HTTP 429`.

### The day: stop cleanly

When the day's requests are gone, waiting a minute won't help, and retrying makes things worse (a request that's refused may still count). The only sensible thing is to **stop**: don't send anything else until the quota resets, which API-Football's documentation says happens at **00:00 UTC**.

The trouble is knowing the day is over. There are three ways it could show up, and only the first is based on something seen live:

| What arrives | Seen live? | What the importer does |
| --- | --- | --- |
| A normal reply with `x-ratelimit-requests-remaining: 0` | The header, yes. The value `0`, not yet | Use this reply. Send nothing more today |
| `200 OK` with `"errors":{"requests":"You have reached the request limit for the day…"}` | No: from the documentation and examples posted online | Send nothing more today |
| `429` with the same `requests` error | No: also from the documentation | Send nothing more today. **Don't** wait or retry |

The importer handles all three, because it can't know which one the real API uses until you hit the limit for real. That's also why the fake provider models all three, and says so.

**Stopping cleanly** means:

- The import ends with the status **`INCOMPLETE_DAILY_QUOTA`**, not `FAILED`. Nothing went wrong; the import ran out of time.
- **Everything received is kept.** Guide 04 links players to the team as it goes, and saves the team before the squad, so a stopped import has done real work.
- **Re-running it later carries on.** The import itself isn't resumed: you import the team again, and because profiles are fetched [only for players we don't have](04-squads-and-players.md), the second run costs 2 requests plus one per player still missing, not the whole squad again.
- Until 00:00 UTC, **no request is sent at all**. Asking a provider that has already said "no more today" costs you nothing extra to avoid.

For now, *you* import the team again the next day. [Guide 09](09-the-import-queue.md) adds a scheduled worker that picks up `INCOMPLETE_DAILY_QUOTA` imports after midnight UTC by itself.

### No reserve

You might expect the importer to keep a few requests back, say 5 a day, for checking things by hand. It doesn't, deliberately. The importer is meant to be the only thing spending this key's quota, and any request it holds back is a request not spent on the data you want: with 100 a day, a reserve of 5 is 5% of every day, used or not. When you do want to check something with `curl`, the cost shows up in the next response's headers anyway, and the importer believes them. If you'd rather have a reserve, it's a small change, and it's [exercise 2](#exercise-2-a-reserve).

### Time is a dependency

Waiting a minute is easy: `Thread.sleep(60_000)`. Testing it isn't. A test that checks the importer waits after a `0` would take a minute. A test that checks it gives up after two retries would take two. A test that checks the stop lifts at midnight UTC would have to run at midnight.

The problem is that `Thread.sleep(...)` and `Instant.now()` are **hidden dependencies**: the code reaches out to the real clock instead of being given one. The fix is the same as for any other dependency, such as a repository: **inject it**.

- **`Clock`** (from `java.time`) answers "what time is it?" The real one is `Clock.systemUTC()`. A test's clock says whatever the test sets.
- **`Sleeper`**, our own one-method interface, does the waiting. The real one calls `Thread.sleep`. A test's sleeper writes down how long it was asked to wait, moves the test's clock on by that much, and returns at once.

So a test can say "the clock says 12:00:45; import; the importer asked to wait exactly 15 seconds", and it runs in milliseconds. The ten tests in this guide "wait" several minutes between them and take about 2.5 seconds.

### Where it goes: an interceptor on the `RestClient`

Every request to API-Football (teams, squads, profiles) goes through the one `RestClient` built in `ProviderConfig`, including the ones made by the HTTP interface from guide 02. A **`ClientHttpRequestInterceptor`** is a piece of code the `RestClient` calls **around** every request: it gets the request, decides when to send it on, and gets the response before anyone else does. That's exactly the place for limits:

```
ApiFootballClient  →  ApiFootball (HTTP interface)  →  RestClient
                                                          │
                                         RateLimitInterceptor.intercept(...)
                                           1. tracker.beforeRequest()   day gone? stop.  minute gone? wait.
                                           2. send it
                                           3. tracker.record(headers)   remember what's left
                                           4. a 429? daily: stop.  minute: go round again (at most 2 more times)
                                                          │
                                                    API-Football
```

The interceptor does the HTTP part. What the provider last said, and what to do about it, lives in a separate bean, **`QuotaTracker`**, which is a singleton: there's one quota per key, so there's one tracker, shared by every import.

> **Done the REST API series?** Its [guide 08](../rest-api/08-hardening.md) built rate limiting from the **other** side: a token bucket in the server, handing out `429`s with a `Retry-After` to callers who went too fast, and its guide 09's scanner obeyed that `Retry-After`. Here you're the caller, living within someone else's limit, and that limit is set by rules you can't see and can't change. Two differences matter. API-Football tells you how much is **left** on every response, so a well-behaved client never needs to see a `429` at all. And it sends **no `Retry-After`**, so the importer waits a fixed, configurable minute instead. A provider that does send `Retry-After` should be obeyed: it knows better than your guess.

---

## Steps

All the new main classes go in the `provider` package, next to `ApiFootballClient`: rate limits are part of talking to API-Football.

### 1. The settings

Add two lines to the end of `src/main/resources/application.properties`:

```properties
# Rate limits. The per-minute limit lasts a minute; after a 429 we try again at most this many times.
api-football.rate-limit.minute-pause=60s
api-football.rate-limit.retries-after-429=2
```

And a record to bind them to, `provider/RateLimitProperties.java`:

```java
package com.example.importer.provider;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How we behave at the provider's limits. Bound from {@code api-football.rate-limit.*}.
 *
 * @param minutePause how long the per-minute limit lasts: we wait this long once it reaches 0
 * @param retriesAfter429 how many times to try again after a 429 Too Many Requests
 */
@ConfigurationProperties("api-football.rate-limit")
public record RateLimitProperties(@DefaultValue("60s") Duration minutePause, @DefaultValue("2") int retriesAfter429) {
}
```

- **`Duration minutePause`**: Spring Boot turns `60s` into a `java.time.Duration` for you. It understands `ms`, `s`, `m`, `h` and `d` (so `1m` works too). **A number without a unit means milliseconds**, which is a trap: see Common errors.
- **`@DefaultValue`** gives a record component a value when the property isn't set at all, so the importer behaves sensibly even if someone deletes the lines from `application.properties`. The lines are still worth having: they show what can be changed.
- **`minute-pause`** binds to `minutePause` and **`retries-after-429`** to `retriesAfter429`: Spring Boot matches the dashed names in properties to the camel-case names in Java.
- **No registration needed.** Guide 01 put `@ConfigurationPropertiesScan` on `ImporterApplication`, so every `@ConfigurationProperties` record in the application is found and becomes a bean.
- **A record of its own**, rather than two more components on `ApiFootballProperties`: guide 01's record, its fail-fast check and its tests stay exactly as they are.

### 2. Waiting, and the time

Create `provider/Sleeper.java`:

```java
package com.example.importer.provider;

import java.time.Duration;

/** Waits. A bean of its own so tests can swap in one that only records how long it was asked to wait. */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration);
}
```

**`@FunctionalInterface`** says the interface has exactly one abstract method, so a lambda can implement it (the compiler checks). Then add the real clock and the real sleeper to `provider/ProviderConfig.java`, after the `RestClient` bean:

```java
    /** The real clock, in UTC: the provider's daily quota resets at 00:00 UTC. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Really waits. Tests replace it. */
    @Bean
    Sleeper sleeper() {
        return (Duration duration) -> {
            try {
                Thread.sleep(duration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProviderException("Interrupted while waiting for the rate limit");
            }
        };
    }
```

with these imports:

```java
import java.time.Clock;
import java.time.Duration;
```

- **`Clock.systemUTC()`** is the real time, in UTC. "Today", for the daily quota, is API-Football's today, which starts at 00:00 UTC, not at midnight where you are. (In the UK, that's the same in winter and 1 am in summer.)
- **`Thread.sleep(duration)`** takes a `Duration` directly (Java 19 and later).
- **`InterruptedException`** is how Java asks a sleeping thread to stop, for example when the application shuts down. The polite answer is to put the "interrupted" flag back (`Thread.currentThread().interrupt()`) for whoever called us, and stop: here, by throwing a `ProviderException`, which makes the import `FAILED` like any other provider problem.

### 3. An exception for "no more today"

Create `provider/DailyQuotaExhaustedException.java`:

```java
package com.example.importer.provider;

/** No requests left today. Nothing to do but stop, and carry on after 00:00 UTC. */
public class DailyQuotaExhaustedException extends ProviderException {

    public DailyQuotaExhaustedException() {
        super("API-Football's daily request quota is used up (it resets at 00:00 UTC)");
    }
}
```

It's a `ProviderException`, so anything that already copes with provider trouble copes with this too: `GET /provider/teams/{id}` would answer it with a `502`, through guide 02's handler. But it's its own class, so the import runner can tell it apart (step 8).

### 4. The quota tracker

This is the class that remembers what the provider last said, and decides whether the next request may go. Create `provider/QuotaTracker.java`:

```java
package com.example.importer.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * What the provider last told us about our limits. We believe the provider, not our own count: it knows about
 * requests we can't see (another copy of the app, a test with curl, the provider's own counting rules).
 * Methods are synchronized: while one import waits for the minute to pass, every other one waits too.
 */
@Component
public class QuotaTracker {

    private static final Logger log = LoggerFactory.getLogger(QuotaTracker.class);

    private final Clock clock;
    private final Sleeper sleeper;
    private final RateLimitProperties limits;

    private Integer minuteRemaining;
    private Instant minuteSeenAt;
    private Integer dayRemaining;
    private LocalDate daySeenOn;

    public QuotaTracker(Clock clock, Sleeper sleeper, RateLimitProperties limits) {
        this.clock = clock;
        this.sleeper = sleeper;
        this.limits = limits;
    }

    /** Called before every request: stop if today's quota is gone, wait if this minute's is. */
    public synchronized void beforeRequest() {
        if (dailyQuotaExhausted()) {
            throw new DailyQuotaExhaustedException();
        }
        if (minuteRemaining != null && minuteRemaining == 0) {
            Duration wait = limits.minutePause().minus(Duration.between(minuteSeenAt, clock.instant()));
            if (wait.isPositive()) {
                long seconds = (wait.toMillis() + 999) / 1000; // rounded up, so 4.9 s says 5 s
                log.info("Per-minute limit reached: waiting {}s before the next request", seconds);
                sleeper.sleep(wait);
            }
            minuteRemaining = null; // a new minute: we don't know until the provider tells us
        }
    }

    /** Called after every response: remember what the provider says is left. */
    public synchronized void record(HttpHeaders headers) {
        Integer minute = number(headers.getFirst("x-ratelimit-remaining"));
        if (minute != null) {
            minuteRemaining = minute;
            minuteSeenAt = clock.instant();
        }
        Integer day = number(headers.getFirst("x-ratelimit-requests-remaining"));
        if (day != null) {
            dayRemaining = day;
            daySeenOn = today();
        }
    }

    /** A 429 means this minute is used up, whatever the headers said. */
    public synchronized void minuteExhausted() {
        minuteRemaining = 0;
        minuteSeenAt = clock.instant();
    }

    /** The provider told us in the body (not the headers) that today's quota is gone. */
    public synchronized void dayExhausted() {
        dayRemaining = 0;
        daySeenOn = today();
    }

    /** Today's quota is gone — until 00:00 UTC, when the provider resets it. */
    public synchronized boolean dailyQuotaExhausted() {
        return dayRemaining != null && dayRemaining == 0 && today().equals(daySeenOn);
    }

    public synchronized Integer minuteRemaining() {
        return minuteRemaining;
    }

    public synchronized Integer dayRemaining() {
        return dayRemaining;
    }

    /** Forget everything the provider told us (tests). */
    public synchronized void reset() {
        minuteRemaining = null;
        minuteSeenAt = null;
        dayRemaining = null;
        daySeenOn = null;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static Integer number(String header) {
        if (header == null) {
            return null;
        }
        try {
            return Integer.valueOf(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
```

Taking it a piece at a time:

- **Four fields, the provider's last word.** `minuteRemaining` and **when** we heard it; `dayRemaining` and **which UTC date** we heard it on. They're `Integer`, not `int`, because "we don't know yet" (`null`) is different from "none left" (`0`). Nothing here counts requests: every value comes from the provider.
- **`record(headers)`** runs after every response and keeps whatever the provider said. A response without the headers (or with something that isn't a number in them) changes nothing: `number(...)` gives back `null`, and the last known value stays.
- **`beforeRequest()`** runs before every request, and is where the Concepts become code:
  - **The day first.** If today's quota is gone, throw, and **nothing is sent**.
  - **Then the minute.** If the provider last said `0` for the minute, work out how much of the minute is left: `minutePause` minus the time since we heard it. Wait that long, if it's still positive. A request 45 seconds later waits 15 seconds; one 70 seconds later doesn't wait at all.
  - **After waiting, forget the minute's value** (`null`): a new minute has started, and only the provider's next response can say how much of it is left.
  - The rounding is only for the log message, so a wait of 4.98 seconds says `5s`, not `4s`. The wait itself is the exact `Duration`.
- **`dailyQuotaExhausted()`** checks the **date** as well as the `0`. The `0` was true on the day we heard it. At 00:00 UTC, `today()` moves on, the dates no longer match, and the stop lifts by itself. No timer, no scheduled job.
- **`minuteExhausted()`** and **`dayExhausted()`** are for the times the provider tells us in some other way than the headers: a `429` (step 5), or a `200` with a `requests` error (step 7).
- **`clock.instant()`, never `Instant.now()`**, and **`sleeper.sleep(...)`, never `Thread.sleep(...)`**: every question about time goes through what step 2 provides, which is what lets the tests control it.
- **`synchronized`**: every method holds the tracker's lock. Two imports running at once share one quota, because the limit is per key, not per import. So while one import is waiting for the minute inside `beforeRequest()`, another import that wants to send has to wait for the lock too. That's not a bug: it's exactly right, because the minute is used up for both of them.
- **It lives in memory.** Restart the importer and it forgets the daily stop. Its first request then asks the provider again, which costs one request and gets the "no more today" reply, which stops it again. That's acceptable here; guide 09 deals with resuming properly.

### 5. The interceptor

Now the piece that wraps every request. Create `provider/RateLimitInterceptor.java`:

```java
package com.example.importer.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Wraps every request to the provider: checks the limits before sending, reads them from every response, and
 * deals with a 429 Too Many Requests.
 */
public class RateLimitInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final QuotaTracker tracker;
    private final RateLimitProperties limits;

    public RateLimitInterceptor(QuotaTracker tracker, RateLimitProperties limits) {
        this.tracker = tracker;
        this.limits = limits;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        for (int attempt = 0; ; attempt++) {
            tracker.beforeRequest();
            ClientHttpResponse response = execution.execute(request, body);
            tracker.record(response.getHeaders());
            log.info("{} {} -> {} (left this minute: {}, today: {})", request.getMethod(), pathAndQuery(request),
                    response.getStatusCode().value(), tracker.minuteRemaining(), tracker.dayRemaining());

            if (response.getStatusCode().value() != 429) {
                return response;
            }
            // 429: the daily quota (stop), or the per-minute limit (wait, then try again a couple of times)
            if (tracker.dailyQuotaExhausted() || saysDailyQuota(response)) {
                response.close();
                tracker.dayExhausted();
                throw new DailyQuotaExhaustedException();
            }
            if (attempt >= limits.retriesAfter429()) {
                return response; // give up: the status handler turns it into a ProviderHttpException
            }
            response.close();
            tracker.minuteExhausted(); // so beforeRequest() waits for the minute to pass
        }
    }

    /** API-Football puts a "requests" error in the body when the daily quota is the problem. */
    private static boolean saysDailyQuota(ClientHttpResponse response) throws IOException {
        String text = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        return text.contains("\"requests\"");
    }

    private static String pathAndQuery(HttpRequest request) {
        String query = request.getURI().getRawQuery();
        return request.getURI().getRawPath() + (query == null ? "" : "?" + query);
    }
}
```

- **`intercept(request, body, execution)`** is the interceptor's one method. `request` is what's about to be sent (method, URI, headers), `body` its body (empty for a `GET`), and **`execution.execute(request, body)`** sends it and gives back the response. Whatever `intercept` returns is what the `RestClient` sees as the response.
- **Each time round the loop is one attempt**: check the limits, send, record what the provider said, log one line. For anything but a `429`, that's it: return the response, and the rest of the `RestClient` (status handler, JSON) carries on as before.
- **Calling `execution.execute(...)` again sends the request again.** That's how the retry after a `429` works: no new request object, just another time round the loop. The tests prove it, with WireMock counting 2 and 3 requests.
- **A `429` that's about the day** (the tracker already knows the day is over, or the body has a `"requests"` key) throws straight away: no wait, no retry. A `429` for the minute marks the minute as used up, so the next time round, `beforeRequest()` waits the full minute before trying again.
- **`attempt >= limits.retriesAfter429()`**: attempt 0 is the first try, 1 and 2 the retries. After the second retry the `429` response is returned as it is, and guide 02's status handler turns it into `ProviderHttpException("API-Football answered HTTP 429")`.
- **`response.close()`** before giving up on a response: it hands the connection back. A response we return is closed later by the `RestClient`.
- **`saysDailyQuota` reads the body** as text, and only for a `429`, so normal responses are never read twice. A simple `contains` is enough to tell `"requests"` from `"rateLimit"`. Reading the body uses it up, which is fine here: that response is never returned.
- **The log line has no headers in it**, only the method, the path and query, the status and the two numbers. The `x-apisports-key` header is in `request`, and a log line that printed the headers would print your key. ([Guide 08](08-when-the-provider-fails.md) logs requests properly, with the key masked.)
- **It isn't a `@Component`.** It's created in `ProviderConfig` (next step) with the two beans it needs, because it belongs to that one `RestClient`, not to the application.

### 6. Plug it into the `RestClient`

In `provider/ProviderConfig.java`, the `RestClient` bean takes two more parameters and adds the interceptor:

```java
    @Bean
    RestClient apiFootballRestClient(RestClient.Builder builder, ApiFootballProperties properties,
            QuotaTracker quotaTracker, RateLimitProperties limits) {
        builder.baseUrl(properties.baseUrl());
        if (properties.hasKey()) {
            // Sent with every request. The fake provider doesn't need it.
            builder.defaultHeader("x-apisports-key", properties.key());
        }
        // Limits are checked before every request and read from every response
        builder.requestInterceptor(new RateLimitInterceptor(quotaTracker, limits));
        // Any 4xx or 5xx becomes our own exception, instead of Spring's HttpClientErrorException and friends
        builder.defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
            throw new ProviderHttpException(response.getStatusCode());
        });
        return builder.build();
    }
```

**`builder.requestInterceptor(...)`** adds it to every request this `RestClient` makes, and so to every request the `ApiFootball` HTTP interface makes, because that's built on the same `RestClient`. Nothing in `ApiFootballClient`, `TeamImporter` or the import code needs to know rate limits exist.

The interceptor runs **before** the status handler: it sees the raw `429` first, and only a `429` it gives up on reaches the status handler.

### 7. A `200` that says the day is over

The interceptor handles the headers and the `429`. The third way the day can end, a `200` with `"errors":{"requests":…}`, isn't an HTTP problem at all: it's in the envelope, and guide 02 made `ApiFootballClient` the one class that reads envelopes. Right now, that reply would become a `ProviderRejectedException`, and the import would be `FAILED`.

In `provider/ApiFootballClient.java`, give the class the tracker:

```java
    private final ApiFootball apiFootball;
    private final QuotaTracker quotaTracker;

    public ApiFootballClient(ApiFootball apiFootball, QuotaTracker quotaTracker) {
        this.apiFootball = apiFootball;
        this.quotaTracker = quotaTracker;
    }
```

and check for `requests` **before** the general rule in `checked`:

```java
    private <T> ApiFootballReply<T> checked(ApiFootballReply<T> reply) {
        // The daily quota can run out with a 200 too: then "errors" has a "requests" entry
        if (reply.errors().containsKey("requests")) {
            quotaTracker.dayExhausted();
            throw new DailyQuotaExhaustedException();
        }
        // A 200 can still be a failure: the reasons are in "errors"
        if (reply.hasErrors()) {
            throw new ProviderRejectedException(reply.errors());
        }
        return reply;
    }
```

`checked` and `single` used to be `static`. Now that `checked` uses a field, **remove `static` from both** (`single` calls `checked`):

```java
    private <T> T single(ApiFootballReply<T> reply, String what, long id) {
```

The order matters for the same reason as in guide 02: the specific case first. `quotaTracker.dayExhausted()` makes sure the **next** request isn't sent either, since this reply came with no headers to tell the tracker.

### 8. Recording how it ended

The import runner from guide 05 records `COMPLETE` or `FAILED`. It now needs a third outcome, and the quota left.

**`imports/Import.java`**: `finish` takes the quota remaining too:

```java
    void finish(ImportStatus status, String error, Integer quotaRemaining, Instant when) {
        this.status = status;
        this.quotaRemaining = quotaRemaining;
        this.error = error;
        this.finishedAt = when;
    }
```

**`imports/ImportService.java`**: pass it through:

```java
    @Transactional
    public void finish(long importId, ImportStatus status, String error, Integer quotaRemaining) {
        get(importId).finish(status, error, quotaRemaining, Instant.now());
    }
```

and in `failInterruptedImports()`, which doesn't know the quota, pass `null`:

```java
            running.finish(ImportStatus.FAILED, "Interrupted: the importer stopped while it was running", null,
                    Instant.now());
```

**`imports/ImportRunner.java`**: take the tracker in the constructor:

```java
    private final TeamImporter teamImporter;
    private final ImportService importService;
    private final QuotaTracker quotaTracker;

    public ImportRunner(TeamImporter teamImporter, ImportService importService, QuotaTracker quotaTracker) {
        this.teamImporter = teamImporter;
        this.importService = importService;
        this.quotaTracker = quotaTracker;
    }
```

and catch the new exception **before** the general `RuntimeException`:

```java
        try {
            teamImporter.importTeam(teamApiId, progressOf(importId));
            importService.finish(importId, ImportStatus.COMPLETE, null, quotaTracker.dayRemaining());
            log.info("Import {} of team {} complete", importId, teamApiId);
        } catch (DailyQuotaExhaustedException e) {
            // Not a failure: stop cleanly. Importing the team again tomorrow fetches only what's missing.
            log.warn("Import {} of team {} stopped: {}", importId, teamApiId, e.getMessage());
            importService.finish(importId, ImportStatus.INCOMPLETE_DAILY_QUOTA, e.getMessage(), 0);
        } catch (RuntimeException e) {
            log.warn("Import {} of team {} failed: {}", importId, teamApiId, e.toString());
            importService.finish(importId, ImportStatus.FAILED, e.getMessage(), quotaTracker.dayRemaining());
        }
```

with the imports:

```java
import com.example.importer.provider.DailyQuotaExhaustedException;
import com.example.importer.provider.QuotaTracker;
```

- **The order of the `catch` blocks matters.** A `DailyQuotaExhaustedException` is also a `RuntimeException`; Java uses the first `catch` that matches, so the specific one has to come first. (The compiler refuses the other order, because the second block could never run.)
- **`INCOMPLETE_DAILY_QUOTA` keeps the work done so far.** The team was saved, and the players fetched were linked as they arrived (guide 04): the stop doesn't undo any of it. `lastImportedAt` stays as it was, because the import didn't complete.
- **`quotaRemaining`** is what the provider last said about the day, so `GET /imports/{id}` shows how much is left. It's `null` if the provider never said (the fake provider in the tests sends no limit headers).

The importer compiles and runs again. Now prove it, without waiting.

### 9. Time in tests

Every Spring test in the series uses `@ImporterIntegrationTest`. Give it a clock that only moves when a test moves it, and a sleeper that doesn't sleep. Create `src/test/java/com/example/importer/TestTime.java`:

```java
package com.example.importer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.example.importer.provider.Sleeper;

/** In tests, time only moves when a test moves it, and "sleeping" just moves the clock on. Nothing really waits. */
@TestConfiguration
public class TestTime {

    @Bean
    @Primary
    TestClock testClock() {
        return new TestClock();
    }

    @Bean
    @Primary
    RecordingSleeper recordingSleeper(TestClock clock) {
        return new RecordingSleeper(clock);
    }

    public static class TestClock extends Clock {

        private Instant now = Instant.parse("2030-01-01T12:00:00Z");

        public void set(Instant instant) {
            this.now = instant;
        }

        public void advance(Duration duration) {
            this.now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    public static class RecordingSleeper implements Sleeper {

        private final TestClock clock;
        private final List<Duration> sleeps = new ArrayList<>();

        RecordingSleeper(TestClock clock) {
            this.clock = clock;
        }

        @Override
        public void sleep(Duration duration) {
            sleeps.add(duration);
            clock.advance(duration);
        }

        public List<Duration> sleeps() {
            return sleeps;
        }

        public void clear() {
            sleeps.clear();
        }
    }
}
```

- **`TestClock extends Clock`**: `Clock` is an abstract class with three methods to fill in. `instant()` is the one that matters, and it returns whatever the test last set. It starts at a fixed moment, noon on 1 January 2030, so no test depends on the day it runs.
- **`RecordingSleeper`** writes down every wait it's asked for, and **moves the clock on** by that much. So after a "60-second wait", the clock really says a minute later, just as it would after a real wait, and the code after it behaves the same.
- **`@Primary`**: the application already has a `Clock` bean and a `Sleeper` bean (step 2). These have different bean names (`testClock`, `recordingSleeper`), so there are now two of each, and `@Primary` tells Spring which one to inject when a class asks for "a `Clock`". The `QuotaTracker` gets the test ones. Because the names differ, nothing is overridden, and Spring doesn't complain.
- **`@TestConfiguration`**, like guide 05's `SynchronousAsync`, is configuration for tests only, which a test brings in with `@Import`.

In `src/test/java/com/example/importer/ImporterIntegrationTest.java`, import it along with `SynchronousAsync`, and update the comment:

```java
/**
 * The whole app, with an in-memory database instead of ./data/importer, @Async methods run at once, and a clock
 * that only moves when a test moves it (nothing really sleeps).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:importer-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import({ SynchronousAsync.class, TestTime.class })
public @interface ImporterIntegrationTest {
}
```

Every existing test now runs with the test clock too, and nothing changes for them: their WireMock stubs (`okJson(...)`) send no limit headers, so the tracker never hears a `0`, and never waits.

### 10. The tests

Create `src/test/java/com/example/importer/provider/RateLimitTest.java`. It's long, because there are ten cases worth proving, so the helpers come at the end:

```java
package com.example.importer.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.importer.ImporterIntegrationTest;
import com.example.importer.TestTime.RecordingSleeper;
import com.example.importer.TestTime.TestClock;
import com.example.importer.imports.ImportRunner;
import com.example.importer.imports.ImportService;
import com.example.importer.imports.ImportStatus;
import com.example.importer.imports.ImportView;
import com.example.importer.team.TeamRepository;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

@ImporterIntegrationTest
class RateLimitTest {

    static final Duration MINUTE = Duration.ofSeconds(60);

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

    @Autowired
    QuotaTracker tracker;

    @Autowired
    RecordingSleeper sleeper;

    @Autowired
    TestClock clock;

    @Autowired
    ImportService importService;

    @Autowired
    ImportRunner runner;

    @Autowired
    TeamRepository teams;

    @BeforeEach
    void freshStart() {
        tracker.reset();
        sleeper.clear();
        clock.set(Instant.parse("2030-01-01T12:00:00Z"));
    }

    @Test
    void readsBothLimitsFromEveryResponseAndDoesNotWaitWhileRequestsAreLeft() {
        stubTeam(900060, limits(7, 81));
        stubSquad(900060, limits(6, 80));

        client.fetchTeam(900060);
        client.fetchSquadIds(900060);

        assertThat(tracker.minuteRemaining()).isEqualTo(6);
        assertThat(tracker.dayRemaining()).isEqualTo(80);
        assertThat(sleeper.sleeps()).isEmpty();
    }

    @Test
    void whenTheMinuteRunsOutItWaitsBeforeTheNextRequest() {
        stubTeam(900060, limits(0, 80));
        stubSquad(900060, limits(9, 79));

        client.fetchTeam(900060);
        assertThat(sleeper.sleeps()).isEmpty(); // nothing else to send yet: no point waiting
        client.fetchSquadIds(900060);

        assertThat(sleeper.sleeps()).containsExactly(MINUTE);
    }

    @Test
    void waitsOnlyForWhatIsLeftOfTheMinute() {
        stubTeam(900060, limits(0, 80));
        stubSquad(900060, limits(9, 79));

        client.fetchTeam(900060);
        clock.advance(Duration.ofSeconds(45));
        client.fetchSquadIds(900060);

        assertThat(sleeper.sleeps()).containsExactly(Duration.ofSeconds(15));
    }

    @Test
    void after429ItWaitsAMinuteAndTriesAgain() {
        provider.stubFor(get(urlPathEqualTo("/teams")).inScenario("limit").whenScenarioStateIs(STARTED)
                .willReturn(tooManyRequests("rateLimit")).willSetStateTo("a minute later"));
        provider.stubFor(get(urlPathEqualTo("/teams")).inScenario("limit").whenScenarioStateIs("a minute later")
                .willReturn(team(900060).withHeader("x-ratelimit-remaining", "9")));

        TeamItem item = client.fetchTeam(900060);

        assertThat(item.team().id()).isEqualTo(900060);
        assertThat(sleeper.sleeps()).containsExactly(MINUTE);
        provider.verify(exactly(2), getRequestedFor(urlPathEqualTo("/teams")));
    }

    @Test
    void givesUpAfterTwoRetries() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(tooManyRequests("rateLimit")));

        assertThatExceptionOfType(ProviderHttpException.class)
                .isThrownBy(() -> client.fetchTeam(900060))
                .withMessage("API-Football answered HTTP 429");
        assertThat(sleeper.sleeps()).containsExactly(MINUTE, MINUTE);
        provider.verify(exactly(3), getRequestedFor(urlPathEqualTo("/teams")));
    }

    @Test
    void whenTheDailyQuotaReachesZeroTheImportStopsCleanly() {
        stubTeam(900061, limits(8, 0));

        ImportView done = runImport(900061);

        assertThat(done.status()).isEqualTo(ImportStatus.INCOMPLETE_DAILY_QUOTA);
        assertThat(done.quotaRemaining()).isZero();
        assertThat(done.error()).isEqualTo("API-Football's daily request quota is used up (it resets at 00:00 UTC)");
        assertThat(teams.findByApiId(900061)).isPresent(); // what we received before stopping is kept
        provider.verify(exactly(0), getRequestedFor(urlPathEqualTo("/players/squads")));
    }

    @Test
    void aDailyQuotaReplyWith200AlsoStopsTheImport() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(okJson(dailyQuotaBody())));

        assertThat(runImport(900062).status()).isEqualTo(ImportStatus.INCOMPLETE_DAILY_QUOTA);
    }

    @Test
    void aDailyQuotaReplyWith429StopsWithoutWaitingOrRetrying() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(tooManyRequests("requests")));

        assertThat(runImport(900063).status()).isEqualTo(ImportStatus.INCOMPLETE_DAILY_QUOTA);
        assertThat(sleeper.sleeps()).isEmpty();
        provider.verify(exactly(1), getRequestedFor(urlPathEqualTo("/teams")));
    }

    @Test
    void onceTheDayIsUsedUpNothingIsSentUntilMidnightUtc() {
        stubTeam(900060, limits(9, 50));
        clock.set(Instant.parse("2030-01-01T23:59:00Z"));
        tracker.dayExhausted();

        assertThatExceptionOfType(DailyQuotaExhaustedException.class).isThrownBy(() -> client.fetchTeam(900060));
        provider.verify(exactly(0), getRequestedFor(urlPathEqualTo("/teams")));

        clock.set(Instant.parse("2030-01-02T00:00:01Z"));
        assertThat(client.fetchTeam(900060).team().id()).isEqualTo(900060);
    }

    @Test
    void importingAgainTheNextDayCarriesOnWhereItStopped() {
        stubTeam(900064, limits(9, 50));
        stubSquad(900064, limits(8, 49), 9000641, 9000642);
        stubProfile(9000641, limits(7, 0)); // the day's last request
        stubProfile(9000642, limits(9, 99));

        ImportView first = runImport(900064);
        assertThat(first.status()).isEqualTo(ImportStatus.INCOMPLETE_DAILY_QUOTA);
        assertThat(first.profilesFetched()).isEqualTo(1);

        clock.advance(Duration.ofDays(1));
        ImportView second = runImport(900064);

        assertThat(second.status()).isEqualTo(ImportStatus.COMPLETE);
        assertThat(second.profilesFetched()).isEqualTo(1); // only the one still missing
        assertThat(second.quotaRemaining()).isEqualTo(99);
        provider.verify(exactly(1), getRequestedFor(urlPathEqualTo("/players/profiles"))
                .withQueryParam("player", equalTo("9000641")));
    }

    private ImportView runImport(long teamApiId) {
        ImportView started = importService.start(teamApiId);
        runner.run(started.id(), teamApiId); // synchronous in tests
        return importService.view(started.id());
    }

    private record Limits(int minute, int day) {
    }

    private static Limits limits(int minute, int day) {
        return new Limits(minute, day);
    }

    private static ResponseDefinitionBuilder withLimits(ResponseDefinitionBuilder response, Limits limits) {
        return response.withHeader("x-ratelimit-remaining", String.valueOf(limits.minute()))
                .withHeader("x-ratelimit-requests-remaining", String.valueOf(limits.day()));
    }

    private static ResponseDefinitionBuilder team(long id) {
        return okJson(ProviderJson.team(id, "Team " + id, null, "England", false));
    }

    private void stubTeam(long id, Limits limits) {
        provider.stubFor(get(urlPathEqualTo("/teams")).withQueryParam("id", equalTo(String.valueOf(id)))
                .willReturn(withLimits(team(id), limits)));
    }

    private void stubSquad(long teamId, Limits limits, long... playerIds) {
        provider.stubFor(get(urlPathEqualTo("/players/squads")).withQueryParam("team", equalTo(String.valueOf(teamId)))
                .willReturn(withLimits(okJson(ProviderJson.squad(teamId, playerIds)), limits)));
    }

    private void stubProfile(long id, Limits limits) {
        provider.stubFor(get(urlPathEqualTo("/players/profiles")).withQueryParam("player", equalTo(String.valueOf(id)))
                .willReturn(withLimits(okJson(ProviderJson.profile(id, "Player " + id)), limits)));
    }

    private static ResponseDefinitionBuilder tooManyRequests(String errorKey) {
        return aResponse().withStatus(429).withHeader("Content-Type", "application/json")
                .withBody("""
                        {"get":"teams","parameters":[],"errors":{"%s":"Too many requests."},"results":0,
                         "paging":{"current":1,"total":1},"response":[]}
                        """.formatted(errorKey));
    }

    private static String dailyQuotaBody() {
        return """
                {"get":"teams","parameters":{"id":"900062"},
                 "errors":{"requests":"You have reached the request limit for the day."},
                 "results":0,"paging":{"current":1,"total":1},"response":[]}
                """;
    }
}
```

The new things in it:

- **`@Autowired RecordingSleeper sleeper` and `TestClock clock`**: the test asks for the test beans by their own types, so it can read the waits and set the time.
- **`@BeforeEach freshStart()`**: the tracker, sleeper and clock are beans, shared by every test in the class (and the tracker by every import). Each test starts from "the provider hasn't told us anything, nobody has waited, and it's noon".
- **`withHeader(name, value)`** on a WireMock response adds a header to it. `withLimits(...)` uses it to put the two remaining-counts on a normal reply, so each test says exactly what the provider "says": `limits(0, 80)` is "none left this minute, 80 left today".
- **`aResponse().withStatus(429)…withBody(...)`** builds a response from scratch, for the `429`s, which `okJson` can't make.
- **WireMock scenarios**, in `after429ItWaitsAMinuteAndTriesAgain`: a **scenario** is a named state machine inside WireMock, so the same request can get a different answer the second time. Every scenario starts in the state `STARTED`. The first stub only matches in that state, answers `429`, and moves the scenario to `"a minute later"` (`willSetStateTo`). The second stub only matches in `"a minute later"`, and answers normally. So the first request gets a `429`, and the retry gets the team.
- **`provider.verify(exactly(n), getRequestedFor(...))`** checks how many matching requests WireMock received. That's how the tests prove what **wasn't** sent: `exactly(0)` squad requests after the daily `0`, `exactly(1)` request for a daily `429` (no retry), `exactly(3)` before giving up. **`.withQueryParam(...)`** narrows it to one player.
- **`runImport(...)`** starts an import and runs it the same way `ImportController` does. Guide 05's `SynchronousAsync` makes `runner.run(...)` finish before it returns, so the next line can read the result.
- **The team ids are 900060 to 900064**, so they don't clash with the other test classes' teams in the shared test database (guide 03).

What each test proves:

| Test | Proves |
| --- | --- |
| `readsBothLimitsFromEveryResponseAndDoesNotWaitWhileRequestsAreLeft` | Both numbers come from the **latest** response, and nobody waits while requests are left |
| `whenTheMinuteRunsOutItWaitsBeforeTheNextRequest` | No wait straight after the `0`. A full minute before the **next** request |
| `waitsOnlyForWhatIsLeftOfTheMinute` | 45 seconds later, only 15 seconds to wait |
| `after429ItWaitsAMinuteAndTriesAgain` | A per-minute `429`: one wait, then the same request again, which works |
| `givesUpAfterTwoRetries` | 3 attempts, 2 waits, then `API-Football answered HTTP 429` |
| `whenTheDailyQuotaReachesZeroTheImportStopsCleanly` | `INCOMPLETE_DAILY_QUOTA`, `quotaRemaining` 0, the team kept, **no** squad request |
| `aDailyQuotaReplyWith200AlsoStopsTheImport` | The `200` with `errors.requests` (step 7) |
| `aDailyQuotaReplyWith429StopsWithoutWaitingOrRetrying` | The `429` with `errors.requests`: one request, no wait |
| `onceTheDayIsUsedUpNothingIsSentUntilMidnightUtc` | At 23:59 UTC nothing is sent. At 00:00:01 UTC the next day, it works again |
| `importingAgainTheNextDayCarriesOnWhereItStopped` | The second run fetches **only** the missing profile: player 9000641 is asked for exactly once, over two days |

**Run them all.** In IntelliJ, right-click `src/test/java` and choose **Run 'All Tests'**. Or in a terminal, in `importer/`:

```bash
./mvnw test
```

(`.\mvnw test` in PowerShell.)

```
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.559 s -- in com.example.importer.provider.RateLimitTest
...
[INFO] Tests run: 58, Failures: 0, Errors: 0, Skipped: 0
```

**2.5 seconds**, for ten tests that between them "wait" five minutes. That's what injecting the time buys.

### 11. Teach the fake provider to run out

The fake provider's fixtures always say `9` left this minute and `89` today. To see the importer react, you need replies that say otherwise. Five new files go in `fake-provider/misbehaving/`, next to guide 02's two. As before, none of them does anything until you switch it on.

**`misbehaving/minute-limit.json`: every reply says "none left this minute".** It has to cover teams, squads **and** profiles, so it's three stubs in one file:

```json
{
  "mappings": [
    {
      "priority": 1,
      "request": {
        "method": "GET",
        "urlPath": "/teams",
        "queryParameters": {
          "id": {
            "matches": "[0-9]+"
          }
        }
      },
      "response": {
        "status": 200,
        "headers": {
          "Content-Type": "application/json",
          "Cache-Control": "no-store, no-cache, must-revalidate",
          "x-ratelimit-limit": "10",
          "x-ratelimit-remaining": "0",
          "x-ratelimit-requests-limit": "100",
          "x-ratelimit-requests-remaining": "88"
        },
        "bodyFileName": "teams-{{request.query.id}}.json",
        "transformers": [
          "response-template"
        ]
      }
    },
    {
      "priority": 1,
      "request": {
        "method": "GET",
        "urlPath": "/players/squads",
        "queryParameters": {
          "team": {
            "matches": "[0-9]+"
          }
        }
      },
      "response": {
        "status": 200,
        "headers": {
          "Content-Type": "application/json",
          "Cache-Control": "no-store, no-cache, must-revalidate",
          "x-ratelimit-limit": "10",
          "x-ratelimit-remaining": "0",
          "x-ratelimit-requests-limit": "100",
          "x-ratelimit-requests-remaining": "88"
        },
        "bodyFileName": "squads-{{request.query.team}}.json",
        "transformers": [
          "response-template"
        ]
      }
    },
    {
      "priority": 1,
      "request": {
        "method": "GET",
        "urlPath": "/players/profiles",
        "queryParameters": {
          "player": {
            "matches": "[0-9]+"
          }
        }
      },
      "response": {
        "status": 200,
        "headers": {
          "Content-Type": "application/json",
          "Cache-Control": "no-store, no-cache, must-revalidate",
          "x-ratelimit-limit": "10",
          "x-ratelimit-remaining": "0",
          "x-ratelimit-requests-limit": "100",
          "x-ratelimit-requests-remaining": "88"
        },
        "bodyFileName": "profiles-{{request.query.player}}.json",
        "transformers": [
          "response-template"
        ]
      }
    }
  ]
}
```

- **`{"mappings": [ … ]}`** holds several stubs in one file. A file like this is switched on with a different admin URL, **`/__admin/mappings/import`**, because `/__admin/mappings` takes exactly one stub.
- **The bodies are the normal ones.** `"bodyFileName": "teams-{{request.query.id}}.json"` is a **templated file name**, as guide 04 used for squads and profiles: a request for team `900100` gets `__files/teams-900100.json`, the same body the normal fixture sends. Only the headers differ, so the importer gets real data and a limit at the same time.
- **`"x-ratelimit-remaining": "0"`** on every reply, and `88` for the day. Every reply says the minute is used up, so the importer waits before every request after the first.

**`misbehaving/daily-limit-reached.json`: the reply that used the day's last request.** Make it a copy of `minute-limit.json` and change the two remaining-counts in **all three** stubs:

```json
          "x-ratelimit-remaining": "9",
          "x-ratelimit-requests-remaining": "0"
```

The first request works and returns real data. The reply says nothing is left today, so the importer sends nothing more.

**`misbehaving/too-many-requests.json`: one per-minute `429`, then back to normal:**

```json
{
  "priority": 1,
  "scenarioName": "too-many-requests",
  "requiredScenarioState": "Started",
  "newScenarioState": "429 sent",
  "request": {
    "method": "GET",
    "urlPattern": "/.*"
  },
  "response": {
    "status": 429,
    "headers": {
      "Content-Type": "application/json",
      "x-ratelimit-limit": "10",
      "x-ratelimit-remaining": "0"
    },
    "body": "{\"get\":\"{{regexExtract request.path '[^/].*'}}\",\"parameters\":[],\"errors\":{\"rateLimit\": \"Too many requests. Your rate limit is 10 requests per minute.\"},\"results\":0,\"paging\":{\"current\":1,\"total\":1},\"response\":[]}",
    "transformers": [
      "response-template"
    ]
  }
}
```

- **The same scenario idea as in the test**, written as JSON: the stub only matches while the scenario `too-many-requests` is in the state `Started` (where every scenario begins), and moves it to `429 sent`. So it answers **one** request with a `429`, and after that it no longer matches, and the normal fixtures answer again.
- **`{{regexExtract request.path '[^/].*'}}`** fills in `"get"` from the path without its leading `/`: `"teams"`, `"players/squads"`, as the real envelope does.
- **The body and its wording come from API-Football's documentation and examples posted online, not from a real `429`.** None was seen while writing the series.

**`misbehaving/daily-exhausted-200.json`: the day is over, reported as a `200`:**

```json
{
  "priority": 1,
  "request": {
    "method": "GET",
    "urlPattern": "/.*"
  },
  "response": {
    "status": 200,
    "headers": {
      "Content-Type": "application/json"
    },
    "body": "{\"get\":\"{{regexExtract request.path '[^/].*'}}\",\"parameters\":[],\"errors\":{\"requests\": \"You have reached the request limit for the day, Go to https://dashboard.api-football.com to upgrade your plan.\"},\"results\":0,\"paging\":{\"current\":1,\"total\":1},\"response\":[]}",
    "transformers": [
      "response-template"
    ]
  }
}
```

**`misbehaving/daily-exhausted-429.json`**: an exact copy, with `"status": 429` instead of `200`.

Neither of these has any limit headers, so the importer can only go by the body. **Both are assumptions**: the real daily-exhausted reply wasn't seen while writing the series, so it could be either. The wording is from API-Football's documentation and examples posted online.

**The switches.** Run these from `fake-provider/`, so the file paths are right. The fake provider must be running (`java -jar wiremock-standalone-3.13.2.jar --port 8089`):

| File | What it pretends | Switch it on with |
| --- | --- | --- |
| `minute-limit.json` | Every reply: none left this minute | `curl -X POST http://localhost:8089/__admin/mappings/import -d @misbehaving/minute-limit.json` |
| `too-many-requests.json` | One per-minute `429`, then normal | `curl -X POST http://localhost:8089/__admin/mappings -d @misbehaving/too-many-requests.json` |
| `daily-limit-reached.json` | A reply that used the day's last request | `curl -X POST http://localhost:8089/__admin/mappings/import -d @misbehaving/daily-limit-reached.json` |
| `daily-exhausted-200.json` | The day is over, as a `200` (assumed) | `curl -X POST http://localhost:8089/__admin/mappings -d @misbehaving/daily-exhausted-200.json` |
| `daily-exhausted-429.json` | The day is over, as a `429` (assumed) | `curl -X POST http://localhost:8089/__admin/mappings -d @misbehaving/daily-exhausted-429.json` |

The three-stub files go to `/__admin/mappings/import`, which answers `200 OK`; the one-stub files to `/__admin/mappings`, which answers `201 Created`, as in guide 02.

**Switch everything off** with the same reset as guide 02:

```bash
curl -X POST http://localhost:8089/__admin/mappings/reset
```

It removes both kinds of stub, and also clears WireMock's **request journal**, its record of the requests it has received (so a request count starts again from 0). After `too-many-requests.json`, also reset the scenario, so its one `429` is ready to send again next time you switch it on:

```bash
curl -X POST http://localhost:8089/__admin/scenarios/reset
```

### 12. Watch it

With the default settings, the per-minute demonstration would take five minutes. Start the importer with a **5-second** pause instead.

- **In IntelliJ:** open **Run → Edit Configurations…**, select `ImporterApplication`, and put this in **Program arguments** (guide 01, step 14):

  ```
  --api-football.rate-limit.minute-pause=5s
  ```

  Run it. Remember to remove the argument afterwards.
- **Terminal alternative:** build the jar and run it. From `importer/`:

  ```bash
  ./mvnw package
  java -jar target/importer-0.0.1-SNAPSHOT.jar --api-football.rate-limit.minute-pause=5s
  ```

  `package` runs the tests first, so it takes about a minute. `./mvnw spring-boot:run -Dspring-boot.run.arguments=--api-football.rate-limit.minute-pause=5s` should do the same, but it wasn't the command used while building this guide. The outputs below came from the `java -jar` form.

Start from a fresh database to get the same numbers: stop the importer, delete `importer/data/`, then start it. Have the fake provider running, and open a second terminal in `fake-provider/` for the switches.

**A normal import first.** The log now shows both numbers after every response:

```bash
curl -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900001
curl -u admin:dev-only-password http://localhost:8080/imports/1
```

```
{"id":1,"teamApiId":900001,"status":"COMPLETE","squadSize":6,"profilesFetched":6,"skipped":0,"skippedReasons":[],"quotaRemaining":89,"error":null,"createdAt":"2026-11-16T15:10:28.297771Z","finishedAt":"2026-11-16T15:10:29.360310Z"}
```

`quotaRemaining` is `89` at last, which is what the fake provider's normal fixtures say. In the importer's log (trimmed to the time, the logger and the message):

```
15:10:28.488  ImportRunner          Import 1 of team 900001 started
15:10:28.799  RateLimitInterceptor  GET /teams?id=900001 -> 200 (left this minute: 9, today: 89)
15:10:28.920  RateLimitInterceptor  GET /players/squads?team=900001 -> 200 (left this minute: 9, today: 89)
15:10:29.169  RateLimitInterceptor  GET /players/profiles?player=9000001 -> 200 (left this minute: 9, today: 89)
...(9000002 to 9000006 the same)
15:10:29.368  ImportRunner          Import 1 of team 900001 complete
```

Eight requests in under a second. (A real provider's numbers would go down with every request. The fake provider's are fixed.)

**The minute runs out.** Switch on `minute-limit.json`, and look at what the provider now says:

```bash
curl -X POST http://localhost:8089/__admin/mappings/import -d @misbehaving/minute-limit.json
curl -i "http://localhost:8089/teams?id=900100"
```

```
HTTP/1.1 200 OK
x-ratelimit-limit: 10
x-ratelimit-remaining: 0
x-ratelimit-requests-limit: 100
x-ratelimit-requests-remaining: 88
...
```

Now import Scotland:

```bash
curl -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900100
```

```
15:10:31.370  ImportRunner          Import 2 of team 900100 started
15:10:31.400  RateLimitInterceptor  GET /teams?id=900100 -> 200 (left this minute: 0, today: 88)
15:10:31.409  QuotaTracker          Per-minute limit reached: waiting 5s before the next request
15:10:36.463  RateLimitInterceptor  GET /players/squads?team=900100 -> 200 (left this minute: 0, today: 88)
15:10:36.481  QuotaTracker          Per-minute limit reached: waiting 5s before the next request
15:10:41.500  RateLimitInterceptor  GET /players/profiles?player=9000102 -> 200 (left this minute: 0, today: 88)
...(9000011 and 9000012 the same, 5 seconds apart)
15:10:56.594  RateLimitInterceptor  GET /players/profiles?player=9000101 -> 200 (left this minute: 0, today: 88)
15:10:56.621  ImportRunner          Import 2 of team 900100 complete
```

Six requests, five waits, about 25 seconds. With the default 60 seconds, it would have taken five minutes. Notice the end: the last reply also said `0`, and the import **finished straight away**, with no pointless wait after it. (Only 4 profiles: D. Brennan was already stored from Riverside's import.) While it runs, `GET /imports/2` should show `RUNNING`, with `profilesFetched` going up as guide 05 records each profile. At the end:

```bash
curl -u admin:dev-only-password http://localhost:8080/imports/2
```

```
{"id":2,"teamApiId":900100,"status":"COMPLETE","squadSize":5,"profilesFetched":4,"skipped":0,"skippedReasons":[],"quotaRemaining":88,"error":null,"createdAt":"2026-11-16T15:10:31.365014Z","finishedAt":"2026-11-16T15:10:56.621356Z"}
```

Switch it off: `curl -X POST http://localhost:8089/__admin/mappings/reset`.

**A `429`.** Switch on `too-many-requests.json`, and see what it sends:

```bash
curl -X POST http://localhost:8089/__admin/mappings -d @misbehaving/too-many-requests.json
curl -i "http://localhost:8089/teams?id=900001"
```

```
HTTP/1.1 429 Too Many Requests
Content-Type: application/json
x-ratelimit-limit: 10
x-ratelimit-remaining: 0

{"get":"teams","parameters":[],"errors":{"rateLimit": "Too many requests. Your rate limit is 10 requests per minute."},"results":0,"paging":{"current":1,"total":1},"response":[]}
```

That `curl` **used up the one `429`**. Reset the scenario so the importer gets it instead, then import Riverside again:

```bash
curl -X POST http://localhost:8089/__admin/scenarios/reset
curl -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900001
```

```
15:11:41.275  RateLimitInterceptor  GET /teams?id=900001 -> 429 (left this minute: 0, today: 88)
15:11:41.275  QuotaTracker          Per-minute limit reached: waiting 5s before the next request
15:11:46.315  RateLimitInterceptor  GET /teams?id=900001 -> 200 (left this minute: 9, today: 89)
15:11:46.350  RateLimitInterceptor  GET /players/squads?team=900001 -> 200 (left this minute: 9, today: 89)
15:11:46.444  ImportRunner          Import 3 of team 900001 complete
```

The `429`, a wait, the **same** request again, and on with the import. `today: 88` on the `429` line is left over from the last import: the `429` carries no daily header, so the tracker still holds what it heard before. The import completes, with `"profilesFetched":0` (every player was already stored) and `"quotaRemaining":89`. Switch it all off:

```bash
curl -X POST http://localhost:8089/__admin/mappings/reset
curl -X POST http://localhost:8089/__admin/scenarios/reset
```

**The day runs out.** Switch on `daily-limit-reached.json`, and import Harbour Athletic:

```bash
curl -X POST http://localhost:8089/__admin/mappings/import -d @misbehaving/daily-limit-reached.json
curl -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900002
```

```
15:11:48.706  RateLimitInterceptor  GET /teams?id=900002 -> 200 (left this minute: 9, today: 0)
15:11:48.718  ImportRunner          Import 4 of team 900002 stopped: API-Football's daily request quota is used up (it resets at 00:00 UTC)
```

(That last line is a `WARN`.) One request, which worked, and then nothing.

```bash
curl -u admin:dev-only-password http://localhost:8080/imports/4
```

```
{"id":4,"teamApiId":900002,"status":"INCOMPLETE_DAILY_QUOTA","squadSize":null,"profilesFetched":0,"skipped":0,"skippedReasons":[],"quotaRemaining":0,"error":"API-Football's daily request quota is used up (it resets at 00:00 UTC)","createdAt":"2026-11-16T15:11:48.658703Z","finishedAt":"2026-11-16T15:11:48.720164Z"}
```

`squadSize` is `null`: the squad was never asked for. But Harbour Athletic itself was saved: `GET /teams` lists it.

**Same day, provider back to normal.** Now switch the fixture off, so the provider would happily answer, and try again:

```bash
curl -X POST http://localhost:8089/__admin/mappings/reset
curl -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900002
```

```
15:11:50.475  ImportRunner          Import 5 of team 900002 stopped: API-Football's daily request quota is used up (it resets at 00:00 UTC)
```

No request line at all. Ask WireMock how many team requests it has had since the reset (which emptied its journal):

```bash
curl -X POST http://localhost:8089/__admin/requests/count -d '{"method":"GET","urlPath":"/teams"}'
```

```
{
  "count" : 0,
  "requestJournalDisabled" : false
}
```

**Zero.** The provider said the day was over, and the importer believed it: nothing more is sent until 00:00 UTC, even though, this time, the provider would have answered.

**"The next day".** You don't have to wait until midnight to see what happens next. The tracker lives in memory, so **restarting the importer** makes it forget, which stands in for the date changing. Restart it, and import Harbour Athletic again:

```bash
curl -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900002
```

```
GET /teams?id=900002 -> 200 (left this minute: 9, today: 89)
GET /players/squads?team=900002 -> 200 (left this minute: 9, today: 89)
GET /players/profiles?player=9000013 -> 200 (left this minute: 9, today: 89)
GET /players/profiles?player=9000014 -> 200 (left this minute: 9, today: 89)
GET /players/profiles?player=9000015 -> 200 (left this minute: 9, today: 89)
```

```
{"id":33,"teamApiId":900002,"status":"COMPLETE","squadSize":5,"profilesFetched":3,"skipped":1,"skippedReasons":["Player 9000014: the provider has no profile"],"quotaRemaining":89,…}
```

Three profile requests, not five: A. MacLeod and R. Fraser were already stored from Scotland's import. The import id jumped from 5 to **33**. That's H2, not the importer: it hands out ids in blocks of 32, and a stopped process loses the rest of its block. Your ids may differ; the `Location` header of each `POST` always tells you the right one.

**The day is over, the other two ways.** Restart the importer before each of these, so the tracker starts clean. The `200` version:

```bash
curl -X POST http://localhost:8089/__admin/mappings -d @misbehaving/daily-exhausted-200.json
curl "http://localhost:8089/players/squads?team=900001"
```

```
{"get":"players/squads","parameters":[],"errors":{"requests": "You have reached the request limit for the day, Go to https://dashboard.api-football.com to upgrade your plan."},"results":0,"paging":{"current":1,"total":1},"response":[]}
```

```bash
curl -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900001
```

The import (`GET` it at the id in the `Location` header) ends:

```
{"id":34,"teamApiId":900001,"status":"INCOMPLETE_DAILY_QUOTA",…,"quotaRemaining":0,"error":"API-Football's daily request quota is used up (it resets at 00:00 UTC)",…}
```

Step 7's check caught it. Without it, this would have been `FAILED` with `API-Football rejected the request: {requests=…}`. Reset, restart the importer, then the `429` version:

```bash
curl -X POST http://localhost:8089/__admin/mappings/reset
curl -X POST http://localhost:8089/__admin/mappings -d @misbehaving/daily-exhausted-429.json
curl -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900001
```

```
GET /teams?id=900001 -> 429 (left this minute: null, today: null)
WARN Import 65 of team 900001 stopped: API-Football's daily request quota is used up (it resets at 00:00 UTC)
```

```
{"id":65,"teamApiId":900001,"status":"INCOMPLETE_DAILY_QUOTA",…,"quotaRemaining":0,…}
```

One request, no wait, no retry. `null` on the log line means the provider sent no limit headers, so the tracker knew nothing until it read the body. Switch it off: `curl -X POST http://localhost:8089/__admin/mappings/reset`, and stop the importer to remove the `--api-football.rate-limit.minute-pause=5s` argument.

**In PowerShell**, the same commands with `curl.exe`, for example:

```powershell
curl.exe -X POST http://localhost:8089/__admin/mappings/import -d @misbehaving/minute-limit.json
curl.exe -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900100
curl.exe -X POST http://localhost:8089/__admin/mappings/reset
```

and `.\mvnw package` to build the jar. One exception: Windows PowerShell 5.1 drops the double quotes inside `'{"method":"GET","urlPath":"/teams"}'` on the way to `curl.exe`, so WireMock gets broken JSON. Escape them there:

```powershell
curl.exe -X POST http://localhost:8089/__admin/requests/count -d '{\"method\":\"GET\",\"urlPath\":\"/teams\"}'
```

> **Sidebar: why not a rate-limiting library?** Libraries such as Resilience4j's `RateLimiter` limit how fast **you** send, for example "at most 10 calls per 60 seconds", by counting on your side. That's the right tool when a provider gives you no headers, and you have to guess. Spring's `RestClient` has nothing like it built in. It isn't used here, because API-Football tells you exactly where you stand after every request, and a count of your own would be the very thing the Concepts warned against: a second opinion that's less well informed than the first.

> **Sidebar: more than one importer.** Two copies of the importer, on two machines, share one key and one quota, but each has its own `QuotaTracker` in memory. They still behave correctly, because each believes the headers on its own responses, but each finds out about the other's requests only from those numbers, one request late. A single worker, or a lock in the database, is the proper fix; [guide 09](09-the-import-queue.md)'s sidebar covers it.

### 13. Then the real API (optional)

**This step is optional, and it wasn't run on the machine these guides were built on** (there was no key). It costs real requests from your 100 for today.

**See the headers for yourself.** One request, in the terminal where you set `APISPORTS_KEY` (guide 01, step 14):

```bash
curl -i -H "x-apisports-key: $APISPORTS_KEY" "https://v3.football.api-sports.io/teams?id=247"
```

In PowerShell:

```powershell
curl.exe -i -H "x-apisports-key: $env:APISPORTS_KEY" "https://v3.football.api-sports.io/teams?id=247"
```

The shell puts the key into the command for you, so it never appears in what you typed or in your history. Among the headers, you should see the four from the Concepts. When the series was planned, a real response said `x-ratelimit-remaining: 9` and `x-ratelimit-requests-remaining: 89`; yours depend on what you've already spent today. Run it a second time, and the daily number should be one lower.

**Import a real team.** Run the importer against the real API (guide 01, step 15: the `--api-football.base-url=https://v3.football.api-sports.io` program argument and `APISPORTS_KEY`), **without** the 5-second pause, and import a team, for example Celtic:

```bash
curl -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/247
```

A first import of a full squad is around 27 requests, so expect the log to show the per-minute count falling to `0` and a **60-second** wait, perhaps twice, and the import to take a few minutes. That's the importer doing its job. Check on it with `GET /imports/{id}` as before; `quotaRemaining` tells you what's left of your day. Two imports like that and most of today's quota is gone, so stop there unless you want to see the daily stop for real.

What you won't see on purpose: a real `429`, or the real daily-exhausted reply. If you do hit one, the importer copes with all the shapes the documentation describes, and the log line shows the status. If what you see differs from this guide's fixtures, the real reply wins: copy it into the fixture.

Then put things back as guide 01 says: remove the program argument and the environment variable, so the importer talks to the fake provider again.

---

## Your turn

### Exercise 1: predict what happens

Without running anything, work out what the importer does in each case: does it wait, and for how long; how many requests are sent; and how does the import end? The pause is the default 60 seconds, and the times are UTC.

1. A reply at 12:00:00 says `x-ratelimit-remaining: 0`. The import's next request is ready at 12:00:20.
2. The same reply at 12:00:00, but the next request comes from a new import, started at 12:01:10.
3. Every reply to `/teams` is a `429` with `"errors":{"rateLimit":"…"}`, and the provider has never sent any limit headers.
4. A reply at 23:58:00 says `x-ratelimit-requests-remaining: 0`. You start another import at 23:59:00, and another at 00:00:30.
5. A reply is `429` with `"errors":{"requests":"…"}`.
6. A reply is `200` with `"errors":{"token":"…"}` (guide 02's bad key).
7. An import stops with `INCOMPLETE_DAILY_QUOTA` at 15:00. You restart the importer at 15:05 and import a team again, while the provider is still out of requests for the day.

<details>
<summary>Solution</summary>

1. **Waits 40 seconds**, then sends. `beforeRequest()` works out 60 s minus the 20 s since the `0`.
2. **No wait.** 70 seconds have passed, so the wait would be negative, and `wait.isPositive()` is false. It doesn't matter that it's a different import: the tracker is shared, so it knows about the `0`, and it knows when it heard it.
3. **3 requests and 2 waits of 60 seconds**, then the import is **`FAILED`** with `API-Football answered HTTP 429`. `quotaRemaining` is `null`: the provider never said anything about the day. This is `givesUpAfterTwoRetries`.
4. At 23:59:00, **no request is sent**: the import ends straight away as `INCOMPLETE_DAILY_QUOTA`. At 00:00:30 it's a new UTC date, so `dailyQuotaExhausted()` is false, and the import runs normally. This is `onceTheDayIsUsedUpNothingIsSentUntilMidnightUtc`.
5. **One request, no wait, no retry**, and `INCOMPLETE_DAILY_QUOTA`. The interceptor finds `"requests"` in the body.
6. **`FAILED`**, with `API-Football rejected the request: {token=…}`. Only a `requests` entry means the day is over. Every other error in a `200` is still guide 02's rejection.
7. **One request goes out**, because the restarted importer has forgotten. The provider answers that the day is over (one of the three ways), the tracker believes it, and the import is `INCOMPLETE_DAILY_QUOTA` again. One request wasted, which is the price of keeping the tracker in memory. (Step 12's restart relied on the same forgetting, with a provider that was no longer out of requests.)

</details>

### Exercise 2: a reserve

The Concepts explain why the importer keeps no requests back. Suppose you want a reserve anyway: a setting **`api-football.rate-limit.reserve`**, default `0`, so that with a reserve of `5` the importer stops for the day as soon as the provider says **5 or fewer** are left, and leaves those for you. The import's `quotaRemaining` should then say how many are really left (5), not `0`.

Make the change, and prove it with a new test class, `provider/ReserveTest`, whose context has a reserve of 5:

1. `stopsWhenOnlyTheReserveIsLeft`: a team reply says 5 left today. Fetching the team again throws `DailyQuotaExhaustedException`, and WireMock received only **one** `/teams` request.
2. `carriesOnWhileMoreThanTheReserveIsLeft`: the reply says 6. Two fetches, two requests.
3. `theImportSaysHowManyRequestsAreLeft`: an import whose team reply says 5 ends `INCOMPLETE_DAILY_QUOTA`, with `quotaRemaining` 5.

<details>
<summary>Solution</summary>

**`provider/RateLimitProperties.java`**: one more component, with its default:

```java
@ConfigurationProperties("api-football.rate-limit")
public record RateLimitProperties(@DefaultValue("60s") Duration minutePause, @DefaultValue("2") int retriesAfter429,
        @DefaultValue("0") int reserve) {
}
```

**`provider/QuotaTracker.java`**: "used up" now means "down to the reserve":

```java
    public synchronized boolean dailyQuotaExhausted() {
        return dayRemaining != null && dayRemaining <= limits.reserve() && today().equals(daySeenOn);
    }
```

**`imports/ImportRunner.java`**: record what the tracker knows, rather than `0`:

```java
            importService.finish(importId, ImportStatus.INCOMPLETE_DAILY_QUOTA, e.getMessage(), quotaTracker.dayRemaining());
```

That's still `0` in the three original cases (the header said `0`, or `dayExhausted()` set it), and `5` for the reserve. Optionally, add `api-football.rate-limit.reserve=0` to `application.properties`, so the setting is easy to find.

**`src/test/java/com/example/importer/provider/ReserveTest.java`**:

```java
package com.example.importer.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.example.importer.ImporterIntegrationTest;
import com.example.importer.imports.ImportRunner;
import com.example.importer.imports.ImportService;
import com.example.importer.imports.ImportStatus;
import com.example.importer.imports.ImportView;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

@ImporterIntegrationTest
@TestPropertySource(properties = "api-football.rate-limit.reserve=5")
class ReserveTest {

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

    @Autowired
    QuotaTracker tracker;

    @Autowired
    ImportService importService;

    @Autowired
    ImportRunner runner;

    @BeforeEach
    void freshStart() {
        tracker.reset();
    }

    @Test
    void stopsWhenOnlyTheReserveIsLeft() {
        stubTeamWithDayRemaining(5);

        client.fetchTeam(900065);

        assertThatExceptionOfType(DailyQuotaExhaustedException.class).isThrownBy(() -> client.fetchTeam(900065));
        provider.verify(exactly(1), getRequestedFor(urlPathEqualTo("/teams")));
    }

    @Test
    void carriesOnWhileMoreThanTheReserveIsLeft() {
        stubTeamWithDayRemaining(6);

        client.fetchTeam(900065);
        client.fetchTeam(900065);

        provider.verify(exactly(2), getRequestedFor(urlPathEqualTo("/teams")));
    }

    @Test
    void theImportSaysHowManyRequestsAreLeft() {
        stubTeamWithDayRemaining(5);

        ImportView started = importService.start(900065);
        runner.run(started.id(), 900065);
        ImportView done = importService.view(started.id());

        assertThat(done.status()).isEqualTo(ImportStatus.INCOMPLETE_DAILY_QUOTA);
        assertThat(done.quotaRemaining()).isEqualTo(5);
    }

    private void stubTeamWithDayRemaining(int day) {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(
                okJson(ProviderJson.team(900065, "Team 900065", null, "England", false))
                        .withHeader("x-ratelimit-requests-remaining", String.valueOf(day))));
    }
}
```

- **`@TestPropertySource(properties = "api-football.rate-limit.reserve=5")`**, as guide 01 gave `ApiFootballClientTest` a key, sets the reserve for this class only. A different setting means a Spring context of its own, so `RateLimitTest` still runs with no reserve, and still passes.
- **Each test resets the tracker**: the three tests share one context, and so one tracker.
- **In `theImportSaysHowManyRequestsAreLeft`**, the team request is sent and the team is saved. The squad request is the one that's never sent, because `beforeRequest()` stops it.

The error message still says the quota is "used up", which is no longer quite true with a reserve. If that bothers you, give `DailyQuotaExhaustedException` a second constructor with a message of its own. And notice what the reserve costs: with `reserve=5`, 5 requests a day are never spent by the importer, whether or not you use them.

</details>

### Exercise 3: a header that isn't a number

`QuotaTracker.number(...)` ignores a limit header it can't read as a number. Add a test to `RateLimitTest`, `aLimitThatIsNotANumberIsIgnored`: every `/teams` reply has `x-ratelimit-remaining: lots`. Fetching team `900060` twice should send two requests, without waiting, and leave the tracker not knowing the minute's count (`null`).

Then answer: what would happen to an import if `number(...)` had no `try`/`catch`?

<details>
<summary>Solution</summary>

```java
    @Test
    void aLimitThatIsNotANumberIsIgnored() {
        provider.stubFor(get(urlPathEqualTo("/teams"))
                .willReturn(team(900060).withHeader("x-ratelimit-remaining", "lots")));

        client.fetchTeam(900060);
        client.fetchTeam(900060);

        assertThat(tracker.minuteRemaining()).isNull();
        assertThat(sleeper.sleeps()).isEmpty();
        provider.verify(exactly(2), getRequestedFor(urlPathEqualTo("/teams")));
    }
```

It uses the class's own `team(id)` helper, and needs no new imports. `@BeforeEach` has already reset the tracker, so the `null` really is "never heard a number".

**Without the `try`/`catch`**, `Integer.valueOf("lots")` throws a `NumberFormatException` inside the interceptor, after the response has arrived. It isn't a `ProviderException`, so it goes straight up through the `RestClient` to `ImportRunner`, whose `catch (RuntimeException e)` marks the import **`FAILED`**, with `For input string: "lots"`. (Try it: take the `try`/`catch` out, and your new test fails with `NumberFormatException: For input string: "lots"`. Put it back afterwards.) The data was fine; only a header we use for pacing was odd. Ignoring it is the better choice: the importer carries on as if the provider hadn't said (it won't wait for a minute it doesn't know is used up, and a `429` would still catch it if it was). Treating everything a provider sends as untrusted, headers included, is [guide 07](07-validating-what-you-receive.md)'s subject.

</details>

---

## Checkpoint

In `importer/`:

```bash
./mvnw test
```

```
[INFO] Tests run: 58, Failures: 0, Errors: 0, Skipped: 0
```

That's guide 05's 48, unchanged (they now run with `TestTime` too), plus `RateLimitTest`'s 10, which take about 2.5 seconds between them. With the tests from exercises 2 and 3, you'll have 62.

With the fake provider and the importer running, a normal import's log shows a `RateLimitInterceptor` line for every request, with `(left this minute: 9, today: 89)`, and `GET /imports/{id}` shows `"quotaRemaining":89`. With `minute-limit.json` switched on and a 5-second pause, the importer waits before every request after the first; with `daily-limit-reached.json`, it stops after one request with `INCOMPLETE_DAILY_QUOTA`.

Your files (new ones marked **new**, changed ones **changed**; unchanged folders left out):

```
importer/src/
├── main/
│   ├── java/com/example/importer/
│   │   ├── imports/
│   │   │   ├── Import.java                          changed
│   │   │   ├── ImportRunner.java                    changed
│   │   │   └── ImportService.java                   changed
│   │   └── provider/
│   │       ├── ApiFootballClient.java               changed
│   │       ├── DailyQuotaExhaustedException.java    new
│   │       ├── ProviderConfig.java                  changed
│   │       ├── QuotaTracker.java                    new
│   │       ├── RateLimitInterceptor.java            new
│   │       ├── RateLimitProperties.java             new
│   │       └── Sleeper.java                         new
│   └── resources/
│       └── application.properties                   changed
└── test/java/com/example/importer/
    ├── ImporterIntegrationTest.java                 changed
    ├── TestTime.java                                new
    └── provider/
        └── RateLimitTest.java                       new

fake-provider/
└── misbehaving/
    ├── bad-key.json
    ├── daily-exhausted-200.json                     new
    ├── daily-exhausted-429.json                     new
    ├── daily-limit-reached.json                     new
    ├── minute-limit.json                            new
    ├── server-error.json
    └── too-many-requests.json                       new
```

No new dependencies, and no new migration: `quota_remaining` has been in the `team_import` table since guide 05, waiting for this guide. (If you did exercise 2, `provider/ReserveTest.java` is new as well, and `RateLimitProperties.java` and `ImportRunner.java` are a little different.)

---

## Common errors

**Nothing waits, even with `minute-limit.json` switched on**
You started the importer with `--api-football.rate-limit.minute-pause=60`, meaning 60 seconds. With no "waiting" lines in the log at all, Scotland's import finished in under a second:

```
"createdAt":"…15:13:56.436357Z","finishedAt":"…15:13:57.239839Z"
```

**A number without a unit is milliseconds** for a `Duration` property. The importer paused for 60 ms, which had already passed by the time it wanted to send the next request, so `wait.isPositive()` was false every time. Write `60s` (or `1m`), as `application.properties` does.

**`too-many-requests.json` is on, but the importer never sees a `429`**
You checked it with `curl "http://localhost:8089/teams?id=900001"` first, and got the `429`. That **was** the one `429`: the scenario has moved on to `429 sent`, and the stub no longer matches, so the importer imports normally. Re-arm it before importing:

```bash
curl -X POST http://localhost:8089/__admin/scenarios/reset
```

**An import stops straight away with `INCOMPLETE_DAILY_QUOTA`, and the log shows no request**
```
WARN … Import 5 of team 900002 stopped: API-Football's daily request quota is used up (it resets at 00:00 UTC)
```

Working as intended. Earlier today (UTC), the provider said there was nothing left, and the tracker remembers, even after you switched the misbehaving fixture off. Against the real API, wait until 00:00 UTC. Against the fake provider, restart the importer, which makes it forget.

**Import ids jump: 5, then 33, then 65**
Not an error. Each time the importer was stopped, H2 lost the rest of the block of 32 ids it had handed out in advance. The ids are still unique; they just aren't consecutive. Always use the id in the `Location` header.

---

## Recap

- API-Football says where you stand on **every** response: **`x-ratelimit-remaining`** for the minute, **`x-ratelimit-requests-remaining`** for the day. There's **no reset header and no `Retry-After`**.
- **Believe the provider, not your own count.** Other clients, failed requests and restarts all make a count of your own wrong. `QuotaTracker` only remembers what the provider last said, and **when**.
- **The minute:** when the provider says `0`, wait **before the next request**, and only for **what's left** of a configurable minute (`60s` by default). No next request, no wait.
- **A `429`** is the safety net: for the minute, wait and send the same request again, **at most twice**; after that it's a failure. A `429` about the day is never retried.
- **The day:** a `0` in the header, a `200` with `errors.requests`, or a `429` with `errors.requests` stops the import **cleanly** as **`INCOMPLETE_DAILY_QUOTA`**, keeps everything received, and sends **nothing** until **00:00 UTC**. Importing the team again carries on, fetching only what's missing. (Only the header is based on a reply seen live; the other two follow the documentation.)
- **No reserve** by default: every request held back is one not spent on data.
- A **`ClientHttpRequestInterceptor`** on the `RestClient` sees every request, HTTP interface included: check before, record after, deal with the `429`.
- **Inject time.** `Clock` and a `Sleeper` are beans; tests swap in a clock they control and a sleeper that only moves it on, so a minute's wait takes no time at all, and midnight comes when the test says.
- The fake provider runs out on purpose: **templated `bodyFileName`s** for real bodies with different headers, **multi-stub files** through `/__admin/mappings/import`, and a **scenario** for a one-off `429`.

### Interview angle

<details>
<summary>"The API you call allows 10 requests a minute and 100 a day. How do you design the client?"</summary>

First find out how the provider tells you where you stand. Here every response carries the remaining counts for the minute and the day, so read both after every response and trust them over any count of your own, because other clients and the provider's own rules affect them. Before each request: if the day is used up, don't send; if the minute is used up, wait until it has passed. Treat `429` as a safety net: wait and retry a limited number of times. When the daily quota runs out, stop in a way that keeps the work done and can be resumed, and make re-running cheap by only fetching what you don't already have. Put all of this in one place, such as an interceptor on the HTTP client, so the rest of the code doesn't know limits exist.

</details>

<details>
<summary>"Should you retry a 429?"</summary>

Yes, but carefully. A `429` means "not now", so waiting and trying again is reasonable, and if the server sends `Retry-After`, wait exactly that long. Limit the number of retries, so a client that's being refused doesn't keep hammering the server. And check what the `429` is about: if it means a daily or monthly quota is gone, retrying in a minute won't help and may cost more, so stop until the quota resets. Better still, read the rate-limit headers on successful responses and slow down before you ever get a `429`.

</details>

<details>
<summary>"How do you test code that waits, or depends on the time of day?"</summary>

Don't call `Thread.sleep` or `Instant.now()` directly: inject them. In Java, inject a `java.time.Clock` instead of calling `now()`, and put waiting behind a small interface. In tests, use a clock the test controls and a sleeper that records the requested duration and moves the test clock on, instead of sleeping. Then a test can set the time to 23:59, assert nothing is sent, move it to 00:00:01, and assert it works again, in milliseconds. It also makes the tests deterministic: they don't depend on the day, or the speed of the machine.

</details>

<details>
<summary>"What's the difference between a rate limit and a quota?"</summary>

A rate limit caps how fast you may call, over a short window: 10 requests a minute. Hitting it means "slow down": wait a little and carry on. A quota caps how much you may use over a longer period: 100 requests a day, or a monthly allowance on a paid plan. Hitting it means "stop": nothing you do will help until it resets, so the client should stop cleanly and resume later. They need different handling, which is why a client should be able to tell them apart, from headers or from the error body.

</details>

<details>
<summary>"Your service runs on three instances and shares one API key. How do you stay within the limit?"</summary>

The limit belongs to the key, not to an instance, so in-memory counting on each instance isn't enough on its own. Options: make one instance (a single worker, chosen by a database lock or a tool such as ShedLock) do all the calling; or keep the shared state, such as "the day is used up until midnight", in a shared store like the database or Redis. If the provider reports remaining counts on every response, each instance can still believe them, which keeps it correct but one request late. A client-side rate limiter per instance would need its limit divided between the instances.

</details>

### Further reading

- [API-Football v3 documentation](https://www.api-football.com/documentation-v3): its rate limits, and the headers that report them
- [Spring Framework reference: REST clients](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html), including `RestClient`'s request interceptors
- [Spring Boot reference: externalized configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html), for `@ConfigurationProperties`, `@DefaultValue` and how `Duration` values are converted (and why a bare number is milliseconds)
- [`java.time.Clock`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/Clock.html), and why it exists: "to allow alternate clocks to be plugged in as and when required"
- [WireMock: stateful behaviour](https://wiremock.org/docs/stateful-behaviour/) (scenarios) and [response templating](https://wiremock.org/docs/response-templating/)
- [RFC 6585 section 4: `429 Too Many Requests`](https://www.rfc-editor.org/rfc/rfc6585#section-4), and [RFC 9110 section 10.2.3: `Retry-After`](https://www.rfc-editor.org/rfc/rfc9110#section-10.2.3)
- [IETF draft: RateLimit header fields for HTTP](https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/), an attempt to standardise headers like API-Football's `x-ratelimit-*`
- [Resilience4j: RateLimiter](https://resilience4j.readme.io/docs/ratelimiter), for limiting your own rate when a provider tells you nothing

**Simplification to notice:** the importer only learns about the minute when the provider's count reaches `0`. With 9 left, it sends as fast as it can. That's what the provider allows, and it's the fastest an import can go. A client calling something more fragile might spread its requests out instead, even when it's allowed to burst.

---

**Next:** [07: Validating what you receive](07-validating-what-you-receive.md). The provider's data is untrusted input: three rules for what to do when a required field is missing, an optional one is odd, or a value doesn't fit, and fixtures that test each one.
