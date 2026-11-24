# 08: When the provider fails

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](README.md) for version notes.

## What you'll learn

- Why a call to another service needs **time limits**, and the two that exist: the **connect timeout** and the **read timeout**
- How to set both explicitly on the `RestClient`, through the JDK client underneath it
- Which failures are worth **another try** (a connection that failed or dropped, no reply in time, `502`, `503`, `504`) and which **never** are (other `4xx`s, `500`, errors in a `200`, the daily quota, data we can't store), and why every retry has a price when the provider counts requests
- How **exponential backoff** spaces the tries out (1 second, then 2), using guide 06's injected `Sleeper`, so tests never wait
- Why the retry goes **around** the call rather than in an interceptor, so every attempt passes the rate limits again
- How to fail honestly when the provider can't be reached: our own **`ProviderUnavailableException`**, answered with **`502 Bad Gateway`** or **`504 Gateway Timeout`**, and an import that ends `FAILED` with the reason
- How to **log every request** the importer sends, with the **`x-apisports-key` header always printed as `****`**, and a test that proves the key never reaches the log

By the end, a provider that's slow, unreachable or briefly broken can no longer hang the importer or turn into a `500`. Short hiccups are ridden out with a couple of polite retries; anything longer fails within a known time, with a message that says what happened. And every request the importer sends is in the log, without your key.

---

## Prerequisites for this guide

You need `importer` and `fake-provider` as they were at the end of [07: Validating what you receive](07-validating-what-you-receive.md), with all **65** tests passing. In particular:

- Guide 02's **exceptions**: `ProviderException`, with `ProviderRejectedException` (errors in a `200`), `NotFoundAtProviderException` (`results: 0`) and `ProviderHttpException` (a `4xx` or `5xx`, thrown by the `RestClient`'s status handler). `web/ApiExceptionHandler` answers "not found" with a `404` and every other `ProviderException` with a `502`.
- Guide 02's **Common errors** left one case open: with the fake provider stopped, `GET /provider/teams/900001` answers a plain **`500`**, and the log shows `ResourceAccessException: I/O error on GET request for "http://localhost:8089/teams": null`. This guide fixes it.
- Guide 06's **`RateLimitInterceptor`** on the `RestClient`, the **`QuotaTracker`**, and **injected time**: the `Sleeper` and `Clock` beans, replaced in every Spring test by `TestTime`'s `RecordingSleeper` and `TestClock`.
- Guide 05's **`ImportRunner`** marks an import `FAILED`, with the exception's message, for any provider problem except the daily quota.
- The fake provider's `misbehaving/` folder and its switches: `POST /__admin/mappings` (one stub), `POST /__admin/mappings/import` (a file of several), and `POST /__admin/mappings/reset` to switch everything off.
- Guide 01 promised that nothing logs the API key, and suggested printing `****` if you ever added request logging. This guide adds that logging.

No API-Football account is needed: everything happens against the fake provider.

---

## Concepts

### Three ways a call goes wrong

Guides 02 and 06 dealt with a provider that **answers**, but not with what you wanted: an error status, a `200` with `errors`, a `429`. There's always a reply to read. This guide is about the times there isn't one:

| What happens | What the importer gets | Until now |
| --- | --- | --- |
| The provider answers with an error | A status, headers, a body | Guide 02's exceptions, guide 06's limits |
| **Nobody answers the connection**: the provider is down, the address is wrong, the network is out | An exception, no response | Spring's `ResourceAccessException`, a `500` |
| The connection works, then **drops** before the reply arrives | An exception, no response | The same |
| The connection works, the request goes, and **the reply doesn't come**, or comes very slowly | Nothing at all, for as long as you're prepared to wait | Nothing: the importer waits |

The last row is the dangerous one, because "for as long as you're prepared to wait" is, right now, **for ever**.

### Waiting for ever

The importer's `RestClient` sends its requests with the JDK's own HTTP client (`java.net.http.HttpClient`: Spring Boot picks it when no other HTTP library is on the classpath, guide 01). Its documentation is clear about requests with no timeout: not setting one is the same as an infinite one, and the request **blocks for ever**. Spring Boot doesn't set one for you either: its `spring.http.clients.read-timeout` property has no default value.

So if API-Football accepts a request and then never replies (an overloaded server, a stuck proxy in between, a half-dead connection), the thread that sent it waits for ever. For the importer, that means:

- **An import stuck at `RUNNING`.** It never fails, so it never says why.
- **That team can't be imported again.** Guide 05 refuses a second import of a team that's already running, with a `409`. Only a restart clears it (and guide 05 then marks it `FAILED` as "Interrupted").
- **`GET /provider/teams/{id}`** never answers either, and curl waits with it.

A provider being slow is normal, and there's nothing you can do about it. **Waiting for it without limit** is a bug in the client.

### Two time limits

Every request has two phases where it can wait, so there are two limits:

| Limit | Covers | If it runs out |
| --- | --- | --- |
| **Connect timeout** | Setting up the connection to the provider's server | Nobody accepted the connection in time |
| **Read timeout** | Waiting for the reply, once the request has been sent | The provider took the request, then didn't answer in time |

The importer uses **5 seconds** to connect and **10 seconds** for the reply. The fake provider answers in milliseconds; any healthy API answers well inside 10 seconds; and 10 seconds is short enough that a stuck provider costs an import seconds, not hours. Both are settings (`api-football.http.connect-timeout` and `read-timeout`), so they can change without a rebuild, and this guide shortens the read timeout to make the demonstration quicker.

A connection that's **refused** (nothing listening on that port, like the fake provider when it's stopped) doesn't need a timeout at all: the refusal comes back at once. The connect timeout is for the slower failure, where nothing answers at all.

### Try again, or not?

When a request fails, the question is whether **the same request, sent again**, could work. Two things decide it.

**Is it safe to send again?** Every request the importer sends is a **`GET`**. `GET` is **safe** (it changes nothing at the provider) and **idempotent** (sending it twice has the same effect as once). So repeating one can't do any harm to the data. A `POST` that, say, placed an order would be a different matter: if the reply was lost, the order may already exist.

**Could the answer be different next time?**

| Failure | Retry? | Why |
| --- | --- | --- |
| No connection, or the connection dropped | **Yes** | A restart, a blip in the network: often gone a second later |
| No reply within the read timeout | **Yes** | The provider may have been briefly overloaded |
| `502 Bad Gateway`, `503 Service Unavailable`, `504 Gateway Timeout` | **Yes** | These say "not now": a proxy in front of the provider couldn't reach it, or the provider is busy or restarting |
| `429 Too Many Requests` | Guide 06 | Already handled, with its own wait, by `RateLimitInterceptor` |
| Other `4xx` (`400`, `401`, `404`…) | **Never** | Our request is wrong. Sent again unchanged, it's wrong again |
| `500 Internal Server Error` | **Never** | Most likely a bug at the provider, triggered by this request. It will probably fail the same way |
| `200` with `errors` (guide 02) | **Never** | The provider read the request and said no |
| The daily quota (guide 06) | **Never** | Nothing will change before 00:00 UTC |
| Data we can't store (guide 07) | **Never** | The provider would send the same data again |

`500` is a judgement call: some clients retry it once. This importer doesn't, because of the next point.

**Every retry costs quota.** API-Football counts requests, and guide 02 saw that even a malformed one appeared to count. Three attempts at a request that fails three times is three requests from your 100 for the day, with nothing to show for them. So retries are kept for failures that are **likely to go away**, and limited to **3 attempts** in all.

**A read timeout is the awkward case.** The request reached the provider; you just didn't get the reply. The provider may well have done the work, and **counted the request**, before the reply was lost or held up. Retrying a `GET` is still safe for the data, but it costs quota again. That's one more reason to keep the read timeout generous rather than tight: a timeout that fires on replies that were merely a little slow pays for every one of them twice.

### Backoff: wait a little longer each time

Retrying at once is the worst thing to do to a struggling server: if it's overloaded, every client that retries straight away adds to the load. So the importer waits before each retry, and **doubles** the wait each time: **1 second** before the second attempt, **2 seconds** before the third. That's **exponential backoff**. With 3 attempts, the third is the last, so the worst case for one request is:

```
attempt 1: no reply for 10 s   wait 1 s   attempt 2: 10 s   wait 2 s   attempt 3: 10 s   give up
≈ 33 seconds, then a clear failure
```

The waits go through guide 06's injected **`Sleeper`**, so in tests they're recorded, not slept: a test can check the importer waited exactly 1 second and then 2, and finish in milliseconds.

> **Sidebar: jitter.** If a thousand clients all fail at the same moment and all back off by exactly 1 second, they all come back at the same moment too, and knock the server over again. Production clients usually add **jitter**, a small random amount, to each wait. The importer doesn't: there's only one of it, and exact waits make the tests simple. With many instances, add it.

### Where the retry goes: around the call

Guide 06 put the rate limits in an **interceptor**, so every request goes through them. Retries could go there too, but they don't. They go **around** the whole call, in a small class of their own, **`ProviderRetry`**:

```
ApiFootballClient.fetchTeam(id)
  └── ProviderRetry.call(() -> apiFootball.teams(id))     tries up to 3 times, waiting 1 s, then 2 s
        └── ApiFootball (HTTP interface) → RestClient
              ├── RateLimitInterceptor        day gone? stop. minute gone? wait. (guide 06)
              │     └── ProviderLoggingInterceptor   log the request, with the key masked (this guide)
              │           └── JDK HttpClient  connect timeout, read timeout (this guide)
              └── status handler              4xx/5xx → ProviderHttpException (guide 02)
```

- **Each retry goes through everything again.** It's a whole new call, so the rate limits are checked again (if the minute ran out meanwhile, the retry waits for it; if the day ran out, it isn't sent), and the attempt is logged.
- **The retry sees our own exceptions.** By the time a failure leaves the `RestClient`, guide 02's status handler has turned a `503` into a `ProviderHttpException` with its status. `ProviderRetry` decides on our terms, not Spring's.
- **It's one line per call** in `ApiFootballClient`, the one class that talks to the provider. Nothing else needs to know.

### Failing honestly: `502` or `504`

After the last attempt, the importer has to say what happened. Spring's `ResourceAccessException` means nothing to the rest of the importer, so it becomes our own **`ProviderUnavailableException`**, a `ProviderException` like the others, with a message that says what went wrong in plain words:

- `API-Football did not answer within 10 s`, for a read timeout;
- `API-Football could not be reached (ConnectException)`, when there's no connection at all.

For `GET /provider/teams/{id}`, guide 02 answered provider problems with `502 Bad Gateway`: "we're fine, the service behind us isn't". HTTP has a more precise status for one case: **`504 Gateway Timeout`**, "the service behind us didn't answer in time". So a timeout is a `504`, and everything else stays a `502`. An import doesn't answer anyone: `ImportRunner` records it as `FAILED`, with the same message.

### Logging requests, never the key

When a provider misbehaves, the first question is "what exactly did we send, and what came back?". So the importer now logs **every request it actually sends**: the method, the full URL, the headers, the status and how long it took, or why it failed. One line per attempt, so a retry is visible as a second line.

The headers include **`x-apisports-key`**. Logs are read by more people than secrets should be, kept for months, copied into log services and pasted into chat. A key in a log line is a leaked key. So:

- The logging prints every header **except** the key, which is always **`****`**. The name is compared without regard to case, because HTTP header names are case-insensitive.
- `ApiFootballProperties`, a record, gets its own **`toString()`**. A record's generated `toString()` prints every component, key included (guide 01 warned about it), so logging the settings object would leak it.
- A **test** sends real requests with a made-up key and checks it was **sent**, but appears **nowhere** in the output. Masking code that nobody tests is masking code that someone will "simplify" one day.

> **Done the REST API series?** Its [guide 07](../rest-api/07-concurrency-and-retries.md) explained why `GET` can be retried and `POST` needs an idempotency key, and its [guide 09](../rest-api/09-a-client-service.md)'s scanner retried with exponential backoff, a cap and a `Retry-After`, and listed which statuses are worth a retry. Skim "Try again, or not?" and "Backoff". Three things are new here. Timeouts, which guide 09's sidebar only mentioned, are set for real. Retries cost **quota**, which changes which ones are worth it (that's why `500` isn't). And API-Football sends no `Retry-After`, so the waits are plain backoff.

---

## Steps

As in guide 06, the new main classes go in the `provider` package: failures are part of talking to API-Football.

### 1. The settings

Add four lines to the end of `src/main/resources/application.properties`:

```properties
# Time limits and retries for calls to the provider
api-football.http.connect-timeout=5s
api-football.http.read-timeout=10s
api-football.http.max-attempts=3
api-football.http.first-backoff=1s
```

And a record to bind them to, `provider/ProviderHttpProperties.java`:

```java
package com.example.importer.provider;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long we wait for the provider, and how hard we try. Bound from {@code api-football.http.*}.
 *
 * @param connectTimeout how long to wait for a connection
 * @param readTimeout how long to wait for the reply once connected
 * @param maxAttempts how many times to try a request that failed for a temporary reason (1 = no retries)
 * @param firstBackoff the wait before the second attempt; it doubles before each one after that
 */
@ConfigurationProperties("api-football.http")
public record ProviderHttpProperties(
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("1s") Duration firstBackoff) {
}
```

The same pattern as guide 06's `RateLimitProperties`: `@DefaultValue`s so the importer behaves sensibly without the lines, `Duration`s that Spring Boot converts from `5s`, and no registration, because guide 01's `@ConfigurationPropertiesScan` finds it. Guide 06's trap applies here too: **a number without a unit is milliseconds**, so `read-timeout=10` would give the provider 10 ms to answer. Always write the unit.

`maxAttempts` counts **every** try, the first included: `3` means one try and up to two retries, and `1` means no retries at all.

### 2. Time limits on the `RestClient`

Spring's `RestClient` doesn't send requests itself: a **request factory** creates them, using some HTTP library underneath. Until now, Spring Boot chose one for you (the JDK's `HttpClient`, with no time limits). Now you build it yourself, with the limits. In `provider/ProviderConfig.java`, the `RestClient` bean takes the new properties, and gets these lines after the `defaultHeader` block, before the interceptor:

```java
    @Bean
    RestClient apiFootballRestClient(RestClient.Builder builder, ApiFootballProperties properties,
            QuotaTracker quotaTracker, RateLimitProperties limits, ProviderHttpProperties http) {
        // ...baseUrl and the key header as before...
        // Never wait for ever: a time limit for connecting, and one for the reply
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(http.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(http.readTimeout());
        builder.requestFactory(requestFactory);
        // ...the interceptor and the status handler as before...
```

with the imports:

```java
import java.net.http.HttpClient;

import org.springframework.http.client.JdkClientHttpRequestFactory;
```

- **`HttpClient.newBuilder().connectTimeout(...)`** builds the JDK's HTTP client with a time limit for connecting. The connect timeout belongs to the **client**, because the client makes the connections.
- **`new JdkClientHttpRequestFactory(httpClient)`** is Spring's request factory for that client, and **`setReadTimeout(...)`** is the limit for the reply. If no reply arrives in time, the request fails with a `java.net.http.HttpTimeoutException`, which the `RestClient` passes on wrapped in a `ResourceAccessException`.
- **`builder.requestFactory(...)`** tells the `RestClient` to use it. Everything else (the base URL, the key, the interceptors, the status handler, the HTTP interface built on top) stays as it was.

Step 7 shows the whole method once the logging is added.

> **Sidebar: Spring Boot's own timeout settings.** Spring Boot can set these two limits for every `RestClient` it builds, with properties instead of code. In Spring Boot 4.1.2 they're `spring.http.clients.connect-timeout` and `spring.http.clients.read-timeout` (checked in its configuration metadata: neither has a default). Spring Boot 3 called them `spring.http.client.*`, and Boot 4 still accepts those but marks them deprecated, which is the kind of change that makes copied examples quietly stop working. This guide sets them in code instead, so you can see exactly which two limits exist and where they go, and so they apply to the provider's `RestClient` only.

### 3. An exception for "no answer"

Create `provider/ProviderUnavailableException.java`:

```java
package com.example.importer.provider;

/** We couldn't get an answer at all: no connection, a dropped connection, or no reply in time. */
public class ProviderUnavailableException extends ProviderException {

    private final boolean timedOut;

    public ProviderUnavailableException(String message, boolean timedOut) {
        super(message);
        this.timedOut = timedOut;
    }

    public boolean timedOut() {
        return timedOut;
    }
}
```

A `ProviderException`, like guide 02's family, so everything that copes with provider problems copes with this one: `ImportRunner`'s `catch (RuntimeException e)` marks the import `FAILED`, and `ApiExceptionHandler` would already answer it with a `502`. **`timedOut`** remembers which kind of "no answer" it was, so step 6 can choose between `502` and `504`.

### 4. `ProviderRetry`

Create `provider/ProviderRetry.java`:

```java
package com.example.importer.provider;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

/**
 * Tries a provider call again when it failed for a reason that might go away on its own.
 * <p>
 * Retried: no connection / dropped connection / no reply in time, and 502, 503, 504 (a gateway or the provider
 * says "not now"). All our calls are GETs, which are safe to repeat.
 * <p>
 * Never retried: 4xx (our request is wrong, it will be wrong again), 500 (probably a bug at the provider, and every
 * attempt costs quota), "errors" in a 200, and the daily quota. A 429 is handled by {@link RateLimitInterceptor}.
 */
@Component
public class ProviderRetry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRetry.class);
    private static final Set<Integer> TEMPORARY_STATUSES = Set.of(502, 503, 504);

    private final ProviderHttpProperties http;
    private final Sleeper sleeper;

    public ProviderRetry(ProviderHttpProperties http, Sleeper sleeper) {
        this.http = http;
        this.sleeper = sleeper;
    }

    public <T> T call(Supplier<T> request) {
        for (int attempt = 1; ; attempt++) {
            try {
                return request.get();
            } catch (ResourceAccessException e) { // I/O: couldn't connect, connection dropped, timed out
                if (attempt >= http.maxAttempts()) {
                    throw unavailable(e);
                }
                waitBeforeTrying(attempt + 1, e.getMessage());
            } catch (ProviderHttpException e) {
                if (!TEMPORARY_STATUSES.contains(e.status().value()) || attempt >= http.maxAttempts()) {
                    throw e;
                }
                waitBeforeTrying(attempt + 1, e.getMessage());
            }
        }
    }

    /** Exponential backoff: 1 s before attempt 2, 2 s before attempt 3, 4 s before attempt 4… */
    private void waitBeforeTrying(int nextAttempt, String reason) {
        Duration wait = http.firstBackoff().multipliedBy(1L << (nextAttempt - 2));
        log.warn("{} - attempt {} of {} in {} ms", reason, nextAttempt, http.maxAttempts(), wait.toMillis());
        sleeper.sleep(wait);
    }

    private ProviderUnavailableException unavailable(ResourceAccessException e) {
        if (e.getCause() instanceof HttpTimeoutException) {
            return new ProviderUnavailableException(
                    "API-Football did not answer within " + describe(http.readTimeout()), true);
        }
        String cause = e.getCause() == null ? e.getClass().getSimpleName() : e.getCause().getClass().getSimpleName();
        return new ProviderUnavailableException("API-Football could not be reached (" + cause + ")", false);
    }

    private static String describe(Duration duration) {
        return duration.toMillis() % 1000 == 0 ? duration.toSeconds() + " s" : duration.toMillis() + " ms";
    }
}
```

A piece at a time:

- **`call(Supplier<T> request)`** takes the call to make, not its result. A **`Supplier<T>`** is a function with no arguments that returns a `T`, so the caller passes a lambda, `() -> apiFootball.teams(id)`, and `ProviderRetry` decides when to run it, and how many times, with `request.get()`. The **`<T>`** makes the method generic: it returns whatever the lambda returns, so the same method works for teams, squads and profiles.
- **`for (int attempt = 1; ; attempt++)`** has no end condition: the loop only ends by **returning** the result, or **throwing**. Attempt 1 is the first try.
- **Two kinds of failure, two `catch` blocks.** A **`ResourceAccessException`** is Spring's wrapper for an I/O problem: no connection, a dropped connection, a read timeout. Those are always worth another try. A **`ProviderHttpException`** (guide 02) carries the provider's status, and only `502`, `503` and `504` are retried; anything else is thrown on at once, on the first attempt.
- **Everything else isn't caught at all**, so it passes straight through, on the first attempt: `ProviderRejectedException` (errors in a `200`), `NotFoundAtProviderException`, guide 06's `DailyQuotaExhaustedException`, guide 07's `InvalidTeamDataException`. Never retried, without a line of code to say so.
- **`attempt >= http.maxAttempts()`**: on the last attempt, there's no retry left, so the failure goes up. A `ProviderHttpException` goes up as it is, so a `503` three times is still `API-Football answered HTTP 503`. A `ResourceAccessException` becomes our own exception first.
- **`waitBeforeTrying`** is the backoff. `1L << n` is 2 to the power `n` (shifting the bits of 1 left by `n` places): 1, 2, 4… So before attempt 2 it waits `firstBackoff × 1` = 1 s, before attempt 3, `× 2` = 2 s. It logs a `WARN` first, with the reason, so the log shows what's being retried and when.
- **`sleeper.sleep(wait)`**, never `Thread.sleep`: guide 06's injected `Sleeper`, so tests record the waits instead of sitting through them.
- **`unavailable(e)`** turns the `ResourceAccessException` into a `ProviderUnavailableException`. Its **cause** (`e.getCause()`) is the JDK's exception underneath. An `HttpTimeoutException` means a timeout: `"API-Football did not answer within 10 s"`, `timedOut` true. Anything else means no connection: the message names the cause's class, such as `ConnectException`, because the JDK's `ConnectException` has no message at all (guide 02's `: null`).
- **`describe`** writes the read timeout the way you'd say it: `10 s`, or `300 ms` when it isn't a whole number of seconds (the tests use 300 ms).

One imprecision, from the JDK's documentation rather than anything seen while building: the JDK reports a **connect** timeout with `HttpConnectTimeoutException`, which is a kind of `HttpTimeoutException`. So a connect timeout would also come out as `did not answer within 10 s`, a `504`, naming the read timeout. It's still a timeout, and still a `504`; only the number in the message is the wrong one. (A connection that's refused, the usual local case, isn't a timeout, and comes out as `could not be reached (ConnectException)`.)

### 5. Retry every call

In `provider/ApiFootballClient.java`, give the class a `ProviderRetry`:

```java
    private final ApiFootball apiFootball;
    private final QuotaTracker quotaTracker;
    private final ProviderRetry retry;

    public ApiFootballClient(ApiFootball apiFootball, QuotaTracker quotaTracker, ProviderRetry retry) {
        this.apiFootball = apiFootball;
        this.quotaTracker = quotaTracker;
        this.retry = retry;
    }
```

and wrap each of the three calls to the provider in `retry.call(() -> ...)`:

```java
    /** GET /teams?id={id}: the team and its venue. */
    public TeamItem fetchTeam(long id) {
        return single(retry.call(() -> apiFootball.teams(id)), "team", id);
    }

    /** GET /players/squads?team={id}: the api ids of the team's current players. No results = no players. */
    public List<Long> fetchSquadIds(long teamId) {
        ApiFootballReply<SquadItem> reply = checked(retry.call(() -> apiFootball.squads(teamId)));
        // ...the rest as before...
    }

    /** GET /players/profiles?player={id}: the profile, or empty when the provider has none ("results": 0). */
    public Optional<ApiPlayer> fetchProfile(long playerId) {
        ApiFootballReply<ProfileItem> reply = checked(retry.call(() -> apiFootball.profiles(playerId)));
        return reply.response().stream().findFirst().map(ProfileItem::player);
    }
```

Only the HTTP call is inside the retry. `checked(...)` and `single(...)` read the envelope **after** it, so a `200` with `errors` is read once, and becomes a `ProviderRejectedException`, without being sent again. Nothing above `ApiFootballClient` (the team importer, the import runner, the lookup controller) changes at all.

### 6. `502` or `504`

In `web/ApiExceptionHandler.java`, add a handler for the new exception, before the general `ProviderException` one:

```java
    /** The provider didn't answer in time: 504 Gateway Timeout. Couldn't connect at all: 502 like the rest. */
    @ExceptionHandler
    ProblemDetail providerUnavailable(ProviderUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(e.timedOut() ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY,
                e.getMessage());
    }
```

with the import:

```java
import com.example.importer.provider.ProviderUnavailableException;
```

Guide 02's rule, "the most specific handler wins", does the rest: a `ProviderUnavailableException` is also a `ProviderException`, but Spring picks this closer match. A timeout is a `504 Gateway Timeout`; no connection is a `502`, as before, but now with a `ProblemDetail` and a message of our own instead of a `500`. **Guide 02's Common error, the `500` with `ResourceAccessException` when the fake provider isn't running, is fixed.**

### 7. Log every request, with the key masked

Create `provider/ProviderLoggingInterceptor.java`:

```java
package com.example.importer.provider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Logs every request to the provider: method, URL, headers, status and how long it took, or why it failed.
 * The API key header is ALWAYS printed as ****: logs are read by more people, and kept longer, than secrets should be.
 */
public class ProviderLoggingInterceptor implements ClientHttpRequestInterceptor {

    static final String KEY_HEADER = "x-apisports-key";

    private static final Logger log = LoggerFactory.getLogger(ProviderLoggingInterceptor.class);

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        long start = System.nanoTime();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            log.info("{} {} {} -> {} in {} ms", request.getMethod(), request.getURI(), masked(request.getHeaders()),
                    response.getStatusCode().value(), millisSince(start));
            return response;
        } catch (IOException e) {
            log.warn("{} {} {} -> failed after {} ms: {}", request.getMethod(), request.getURI(),
                    masked(request.getHeaders()), millisSince(start), e.toString());
            throw e;
        }
    }

    /** The request headers as text, with the key replaced by ****. */
    static String masked(HttpHeaders headers) {
        List<String> shown = new ArrayList<>();
        headers.forEach((name, values) -> shown.add(
                name + "=" + (name.equalsIgnoreCase(KEY_HEADER) ? "****" : String.join(",", values))));
        return shown.toString();
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
```

- **An interceptor again**, like guide 06's: `execution.execute(request, body)` sends the request, and everything around it is ours. Here, "around it" is a stopwatch and a log line.
- **Two outcomes, two lines.** A response, whatever its status, is an `INFO` line with the status and the time. An **`IOException`** (no connection, a dropped one, a timeout) never produces a response, so it's a `WARN` line with how long it took to fail and why, and then it's **thrown on** unchanged: logging mustn't change what happens.
- **`System.nanoTime()`** is the clock for measuring how long something took. Unlike `Clock` or `Instant.now()`, it isn't the time of day, only a counter that always goes forwards, so it's right for durations and nothing else. (It isn't injected: nothing decides anything based on it, and the tests check the line's shape, not its numbers.)
- **`masked(headers)`** goes through every header (`forEach` gives each name with its list of values) and writes `name=value`, except that **`x-apisports-key` is always `****`**. `equalsIgnoreCase`, because `X-Apisports-Key` is the same header to HTTP. Masking by name, rather than by searching for the key's value, means it works for any key, including one this code has never seen.
- **`KEY_HEADER`** is a constant now, because two classes need the header's name: this one to mask it, and `ProviderConfig` to send it. One spelling, in one place, so they can't drift apart. It's package-private (no `public`): only the `provider` package needs it.
- **The full URL** is logged (`request.getURI()`), so you can see which provider the importer talked to. The key is in a header, never in the URL, so the URL is safe to print.

Now plug it in. Here's the whole `RestClient` bean in `provider/ProviderConfig.java`, with step 2's time limits, the new interceptor and the constant:

```java
    @Bean
    RestClient apiFootballRestClient(RestClient.Builder builder, ApiFootballProperties properties,
            QuotaTracker quotaTracker, RateLimitProperties limits, ProviderHttpProperties http) {
        builder.baseUrl(properties.baseUrl());
        if (properties.hasKey()) {
            // Sent with every request. The fake provider doesn't need it.
            builder.defaultHeader(ProviderLoggingInterceptor.KEY_HEADER, properties.key());
        }
        // Never wait for ever: a time limit for connecting, and one for the reply
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(http.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(http.readTimeout());
        builder.requestFactory(requestFactory);
        // Limits are checked before every request and read from every response
        builder.requestInterceptor(new RateLimitInterceptor(quotaTracker, limits));
        // Every request actually sent is logged (with the key masked)
        builder.requestInterceptor(new ProviderLoggingInterceptor());
        // Any 4xx or 5xx becomes our own exception, instead of Spring's HttpClientErrorException and friends
        builder.defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
            throw new ProviderHttpException(response.getStatusCode());
        });
        return builder.build();
    }
```

**The order of the interceptors matters.** Interceptors run in the order they're added, each one **around** the next: `RateLimitInterceptor` first, on the outside, and `ProviderLoggingInterceptor` inside it, next to the actual sending. So the logging sees **every request that's really sent**:

- When guide 06's interceptor retries a `429` by calling `execution.execute(...)` again, the request goes through the logging interceptor again: one line per attempt. (Verified while building: Spring Framework 7 runs the inner interceptors again on a second `execute`.)
- When `QuotaTracker` stops a request because the day is used up, nothing is sent, and nothing is logged.
- For each request, the logging line comes **before** guide 06's line: the inner interceptor gets the response first.

Guide 06's rate-limit line stays, so each request now has two `INFO` lines. That's fine for learning; a real application would merge them, or lower one to `DEBUG`.

### 8. The settings never print the key

Guide 01 warned that a record's generated `toString()` prints every component. Nothing logs `ApiFootballProperties` today, but one `log.info("Using {}", properties)` added while debugging would print your key. Take the risk away. In `provider/ApiFootballProperties.java`, after the compact constructor:

```java
    /** Never print the key, even by accident (a log line, an error message, a debugger). */
    @Override
    public String toString() {
        return "ApiFootballProperties[baseUrl=" + baseUrl + ", key=" + (hasKey() ? "****" : "(none)") + "]";
    }
```

A record lets you write any of its generated methods yourself, and yours replaces the generated one. It still says **whether** there's a key (`****` or `(none)`), which is what you want to know when debugging, and never what it is.

Prove it in `ApiFootballPropertiesTest` (guide 01's plain unit test), with a fourth test:

```java
    @Test
    void printingTheSettingsNeverShowsTheKey() {
        assertThat(new ApiFootballProperties("https://v3.football.api-sports.io", "abc123"))
                .hasToString("ApiFootballProperties[baseUrl=https://v3.football.api-sports.io, key=****]");
    }
```

**`hasToString(...)`** is AssertJ's check of an object's `toString()`.

### 9. Tests: slow, broken and failing providers

WireMock can misbehave in the ways this guide is about. Create `src/test/java/com/example/importer/provider/ProviderFailureTest.java`:

```java
package com.example.importer.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.example.importer.ImporterIntegrationTest;
import com.example.importer.TestTime.RecordingSleeper;
import com.example.importer.imports.ImportRunner;
import com.example.importer.imports.ImportService;
import com.example.importer.imports.ImportView;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

@ImporterIntegrationTest
@WithMockUser(roles = "ADMIN")
@TestPropertySource(properties = "api-football.http.read-timeout=300ms") // so a "slow" reply only takes a moment
class ProviderFailureTest {

    static final Duration ONE_SECOND = Duration.ofSeconds(1);
    static final Duration TWO_SECONDS = Duration.ofSeconds(2);

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
    RecordingSleeper sleeper;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ImportService importService;

    @Autowired
    ImportRunner runner;

    @BeforeEach
    void noWaitsYet() {
        sleeper.clear();
    }

    @Test
    void aSlowReplyTimesOutIsTriedThreeTimesWithBackoffThenReported() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(team().withFixedDelay(1500)));

        assertThatExceptionOfType(ProviderUnavailableException.class)
                .isThrownBy(() -> client.fetchTeam(900070))
                .withMessage("API-Football did not answer within 300 ms");
        assertThat(sleeper.sleeps()).containsExactly(ONE_SECOND, TWO_SECONDS);
        provider.verify(exactly(3), getRequestedFor(urlPathEqualTo("/teams")));
    }

    @Test
    void aDroppedConnectionIsRetriedThenReportedAsUnreachable() {
        provider.stubFor(get(urlPathEqualTo("/teams"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatExceptionOfType(ProviderUnavailableException.class)
                .isThrownBy(() -> client.fetchTeam(900070))
                .withMessageStartingWith("API-Football could not be reached (");
        provider.verify(exactly(3), getRequestedFor(urlPathEqualTo("/teams")));
    }

    @Test
    void a503IsRetriedAndTheSecondTryCanSucceed() {
        provider.stubFor(get(urlPathEqualTo("/teams")).inScenario("busy").whenScenarioStateIs(STARTED)
                .willReturn(serviceUnavailable()).willSetStateTo("recovered"));
        provider.stubFor(get(urlPathEqualTo("/teams")).inScenario("busy").whenScenarioStateIs("recovered")
                .willReturn(team()));

        assertThat(client.fetchTeam(900070).team().id()).isEqualTo(900070);
        assertThat(sleeper.sleeps()).containsExactly(ONE_SECOND);
    }

    @Test
    void temporaryErrorsAreRetriedOnlySoOften() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(aResponse().withStatus(502)));

        assertThatExceptionOfType(ProviderHttpException.class)
                .isThrownBy(() -> client.fetchTeam(900070))
                .withMessage("API-Football answered HTTP 502");
        assertThat(sleeper.sleeps()).containsExactly(ONE_SECOND, TWO_SECONDS);
        provider.verify(exactly(3), getRequestedFor(urlPathEqualTo("/teams")));
    }

    @Test
    void errorsThatWouldHappenAgainAreNeverRetried() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(serverError()));
        assertThatExceptionOfType(ProviderHttpException.class).isThrownBy(() -> client.fetchTeam(900070));

        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(notFound()));
        assertThatExceptionOfType(ProviderHttpException.class).isThrownBy(() -> client.fetchTeam(900070));

        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(okJson(ProviderJson.rejected("""
                {"token":"Error/Missing application key."}
                """))));
        assertThatExceptionOfType(ProviderRejectedException.class).isThrownBy(() -> client.fetchTeam(900070));

        provider.verify(exactly(3), getRequestedFor(urlPathEqualTo("/teams"))); // one each
        assertThat(sleeper.sleeps()).isEmpty();
    }

    @Test
    void whenTheProviderCannotBeReachedTheLookupIsA502ProblemDetail() {
        provider.stubFor(get(urlPathEqualTo("/teams"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThat(mvc.get().uri("/provider/teams/900070"))
                .hasStatus(HttpStatus.BAD_GATEWAY)
                .bodyJson().extractingPath("$.detail").asString()
                .startsWith("API-Football could not be reached (");
    }

    @Test
    void whenTheProviderIsTooSlowTheLookupIsA504ProblemDetail() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(team().withFixedDelay(1500)));

        assertThat(mvc.get().uri("/provider/teams/900070"))
                .hasStatus(HttpStatus.GATEWAY_TIMEOUT)
                .bodyJson().isLenientlyEqualTo("""
                        {"status":504,"title":"Gateway Timeout","detail":"API-Football did not answer within 300 ms"}
                        """);
    }

    @Test
    void anImportThatCannotReachTheProviderFailsWithTheReason() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(team().withFixedDelay(1500)));

        ImportView started = importService.start(900071);
        runner.run(started.id(), 900071);

        assertThat(importService.view(started.id()).error()).isEqualTo("API-Football did not answer within 300 ms");
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder team() {
        return okJson(ProviderJson.team(900070, "Riverside Rovers", "RIV", "England", false));
    }
}
```

The new things in it:

- **`@TestPropertySource(properties = "api-football.http.read-timeout=300ms")`**, as in guides 01 and 06, changes one setting for this class. A slow reply then times out in 0.3 seconds instead of 10. This is the **only real waiting** in the whole suite: a timeout can't be faked with the `Sleeper`, because it's the JDK client waiting, not our code. Three attempts at 0.3 seconds is about a second per slow test. The backoff waits between them are still the `RecordingSleeper`'s, so they take no time.
- **`.withFixedDelay(1500)`** makes WireMock hold a response for 1,500 ms before sending it: longer than the read timeout, so the importer gives up first.
- **`aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)`** makes WireMock break the connection instead of answering, as a crashed server or a network fault would. The importer sees an I/O error, not a status. (`Fault` is in `com.github.tomakehurst.wiremock.http`.)
- **`serviceUnavailable()`** and **`notFound()`** are WireMock shortcuts for a `503` and a `404`, like guide 02's `serverError()` for a `500`. `aResponse().withStatus(502)` (guide 06) makes any other status.
- **`withMessageStartingWith(...)`** and **`.asString().startsWith(...)`** check only the start of a message. The end names the JDK's exception class, which is the JDK's business, not ours.
- **`@WithMockUser(roles = "ADMIN")`** (guide 05), because two tests call `GET /provider/teams/{id}`, which is behind security.
- **The scenario** in `a503IsRetriedAndTheSecondTryCanSucceed` is guide 06's: a `503` the first time, the team the second.
- **`errorsThatWouldHappenAgainAreNeverRetried`** proves what *isn't* sent: a `500`, a `404` and a `200` with errors, one request each (three in all), and no waits.
- **Team ids 900070 and 900071**, so they don't clash with other test classes' teams.

| Test | Proves |
| --- | --- |
| `aSlowReplyTimesOutIsTriedThreeTimesWithBackoffThenReported` | No reply in time: 3 requests, waits of 1 s and 2 s, then `did not answer within 300 ms` |
| `aDroppedConnectionIsRetriedThenReportedAsUnreachable` | A dropped connection: 3 requests, then `could not be reached (…)` |
| `a503IsRetriedAndTheSecondTryCanSucceed` | A `503`, a 1 s wait, and the second try works: the caller never knows |
| `temporaryErrorsAreRetriedOnlySoOften` | A `502` every time: 3 requests, then guide 02's `API-Football answered HTTP 502` |
| `errorsThatWouldHappenAgainAreNeverRetried` | `500`, `404`, errors in a `200`: one request each, no waits |
| `whenTheProviderCannotBeReachedTheLookupIsA502ProblemDetail` | No connection: `502` with our message, not a `500` |
| `whenTheProviderIsTooSlowTheLookupIsA504ProblemDetail` | Too slow: `504 Gateway Timeout` |
| `anImportThatCannotReachTheProviderFailsWithTheReason` | An import records the reason it failed |

### 10. Tests: the key never reaches the log

The test that guards the masking has to read what the application **printed**. Spring Boot's test support can capture it. Create `src/test/java/com/example/importer/provider/ProviderLoggingTest.java`:

```java
package com.example.importer.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.example.importer.ImporterIntegrationTest;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

@ImporterIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "api-football.key=" + ProviderLoggingTest.KEY)
class ProviderLoggingTest {

    static final String KEY = "secret-test-key-7f3a9c";

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
    ApiFootballProperties properties;

    @Test
    void everyRequestIsLoggedWithMethodUrlStatusAndTime(CapturedOutput output) {
        provider.stubFor(get(urlPathEqualTo("/teams"))
                .willReturn(okJson(ProviderJson.team(900080, "Riverside Rovers", "RIV", "England", false))));

        client.fetchTeam(900080);

        assertThat(output).containsPattern(
                "GET " + provider.baseUrl() + "/teams\\?id=900080 \\[.*x-apisports-key=\\*\\*\\*\\*.*] -> 200 in \\d+ ms");
    }

    @Test
    void theKeyIsSentButNeverAppearsInTheLogs(CapturedOutput output) {
        // a success, an error status and a broken connection: every kind of log line
        provider.stubFor(get(urlPathEqualTo("/teams"))
                .willReturn(okJson(ProviderJson.team(900080, "Riverside Rovers", "RIV", "England", false))));
        client.fetchTeam(900080);
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(serverError()));
        catchThrowable(() -> client.fetchTeam(900080));
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        catchThrowable(() -> client.fetchTeam(900080));
        System.out.println(properties); // even printing the settings must not show it

        provider.verify(getRequestedFor(urlPathEqualTo("/teams")).withHeader("x-apisports-key", equalTo(KEY)));
        assertThat(output).contains("x-apisports-key=****").contains("-> failed after");
        assertThat(output).doesNotContain(KEY);
    }
}
```

- **`@ExtendWith(OutputCaptureExtension.class)`** switches on Spring Boot's output capture for this class (`org.springframework.boot.test.system`). Everything written to the console during a test, log lines included, is collected, and JUnit hands it to any test method that asks for a **`CapturedOutput`** parameter. It's plain text, so AssertJ's string checks work on it.
- **`containsPattern(...)`** checks the text against a regular expression: the method, the URL, `x-apisports-key=****` somewhere in the headers, the status and a number of milliseconds. The `\\?`, `\\[` and `\\*` are there because `?`, `[` and `*` mean something in a regular expression, and here they're meant literally.
- **A made-up key, unlike any real one** (`secret-test-key-7f3a9c`), set with `@TestPropertySource` as guide 01 did. Searching the output for it can't accidentally match something else.
- **Every kind of log line**: a success (`INFO … -> 200`), an error status (`-> 500`, not retried) and a broken connection (`WARN … -> failed after`, three times, with `ProviderRetry`'s warnings between). If any of them printed the key, `doesNotContain(KEY)` fails.
- **`catchThrowable(() -> ...)`** (AssertJ) runs the code and catches whatever it throws. The test doesn't care about the exception, only about what got logged on the way.
- **`System.out.println(properties)`** prints the settings record: step 8's `toString()` is tested here too, in the output it would really end up in.
- **Two halves to the proof.** `provider.verify(... .withHeader("x-apisports-key", equalTo(KEY)))` checks the key **was** sent (guide 01's check), so the test can't pass just because there was no key. Then `doesNotContain(KEY)` checks it was never printed.

**Run them all.** In IntelliJ, right-click `src/test/java` and choose **Run 'All Tests'**. Or in a terminal, in `importer/`:

```bash
./mvnw test
```

(`.\mvnw test` in PowerShell.)

```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.029 s -- in com.example.importer.provider.ProviderFailureTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.526 s -- in com.example.importer.provider.ProviderLoggingTest
...
[INFO] Tests run: 76, Failures: 0, Errors: 0, Skipped: 0
```

`ProviderFailureTest` takes a few seconds: it starts a Spring context of its own (its read timeout is different), and its three slow tests really wait for the 0.3-second timeout, three times each. Every backoff wait, 1 and 2 seconds at a time, took no time at all.

### 11. Teach the fake provider to fail

Three new files go in `fake-provider/misbehaving/`. As before, none of them does anything until you switch it on.

**`misbehaving/slow.json`: every reply takes 15 seconds.** Make it a copy of guide 06's `minute-limit.json` (three stubs, normal bodies through templated file names), set the two remaining-counts back to the normal `9` and `89`, and add one line to **each** of the three responses, after `"transformers"`. The first stub ends up like this:

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
          "x-ratelimit-remaining": "9",
          "x-ratelimit-requests-limit": "100",
          "x-ratelimit-requests-remaining": "89"
        },
        "bodyFileName": "teams-{{request.query.id}}.json",
        "transformers": [
          "response-template"
        ],
        "fixedDelayMilliseconds": 15000
      }
    },
```

and the squads and profiles stubs the same, with their own `urlPath`, query parameter and `bodyFileName`, exactly as in `minute-limit.json`. **`"fixedDelayMilliseconds": 15000`** is the file version of the test's `withFixedDelay`: WireMock holds every reply for 15 seconds, longer than the importer's 10-second read timeout. The reply would be perfectly normal, if anyone waited for it.

**`misbehaving/service-unavailable.json`: one `503`, then back to normal.** The same one-shot scenario as guide 06's `too-many-requests.json`:

```json
{
  "priority": 1,
  "scenarioName": "service-unavailable",
  "requiredScenarioState": "Started",
  "newScenarioState": "503 sent",
  "request": {
    "method": "GET",
    "urlPattern": "/.*"
  },
  "response": {
    "status": 503,
    "headers": {
      "Content-Type": "application/json"
    },
    "jsonBody": {
      "message": "Service unavailable (fake provider: misbehaving/service-unavailable.json)"
    }
  }
}
```

The body is invented, like guide 02's `server-error.json`: a real API-Football `503` hasn't been seen.

**`misbehaving/connection-reset.json`: every connection is broken.**

```json
{
  "priority": 1,
  "request": {
    "method": "GET",
    "urlPattern": "/.*"
  },
  "response": {
    "fault": "CONNECTION_RESET_BY_PEER"
  }
}
```

**`"fault"`** is the file version of the test's `withFault`: instead of answering, WireMock resets the connection.

**The switches.** Run these from `fake-provider/`, with the fake provider running:

| File | What it pretends | Switch it on with |
| --- | --- | --- |
| `slow.json` | Every reply takes 15 seconds | `curl -X POST http://localhost:8089/__admin/mappings/import -d @misbehaving/slow.json` |
| `service-unavailable.json` | One `503`, then normal | `curl -X POST http://localhost:8089/__admin/mappings -d @misbehaving/service-unavailable.json` |
| `connection-reset.json` | Every connection is broken | `curl -X POST http://localhost:8089/__admin/mappings -d @misbehaving/connection-reset.json` |
| `server-error.json` (guide 02) | Every reply is a `500`: now the "never retried" case | `curl -X POST http://localhost:8089/__admin/mappings -d @misbehaving/server-error.json` |
| (none) | The provider is down | Stop WireMock with Ctrl+C |

`slow.json` has three stubs, so it goes to `/__admin/mappings/import`; the others to `/__admin/mappings`, as in guide 06. Switch everything off as before, and re-arm the one-shot `503` for next time:

```bash
curl -X POST http://localhost:8089/__admin/mappings/reset
curl -X POST http://localhost:8089/__admin/scenarios/reset
```

As with guide 06's one-shot `429`: if you `curl` the fake provider yourself after switching on `service-unavailable.json`, **your** request gets the `503`, and the importer won't see one. Reset the scenario first.

### 12. Watch it

With the default 10-second read timeout, a slow lookup takes about 33 seconds to give up. Start the importer with a **2-second** read timeout instead. And give it a **made-up key**, so you can watch it being masked: the fake provider ignores the key, and a dummy one costs nothing if anything goes wrong. Never use your real key for experiments with logging.

- **In IntelliJ:** open **Run → Edit Configurations…**, select `ImporterApplication`, and put this in **Program arguments** (guide 01, step 14):

  ```
  --api-football.http.read-timeout=2s
  ```

  and this in **Environment variables**:

  ```
  APISPORTS_KEY=demo-dummy-key-0000
  ```

  Run it. Remember to remove both afterwards.
- **Terminal alternative:** build the jar, then run it with the variable set. From `importer/`:

  ```bash
  ./mvnw package
  APISPORTS_KEY=demo-dummy-key-0000 java -jar target/importer-0.0.1-SNAPSHOT.jar --api-football.http.read-timeout=2s
  ```

  In PowerShell:

  ```powershell
  .\mvnw package
  $env:APISPORTS_KEY="demo-dummy-key-0000"
  java -jar target/importer-0.0.1-SNAPSHOT.jar --api-football.http.read-timeout=2s
  ```

  and afterwards `Remove-Item Env:APISPORTS_KEY`, so the dummy key doesn't follow you into the next guide.

Start from a fresh database to get the same import id (stop the importer, delete `importer/data/`, then start it). Have the fake provider running, and a second terminal in `fake-provider/` for the switches. The log lines below are trimmed to the time (where it matters), the logger and the message.

**A normal lookup.** Every request now has two lines: this guide's, then guide 06's:

```bash
curl -u admin:dev-only-password http://localhost:8080/provider/teams/900001
```

```
INFO  ProviderLoggingInterceptor : GET http://localhost:8089/teams?id=900001 [x-apisports-key=****, Content-Length=0] -> 200 in 326 ms
INFO  RateLimitInterceptor       : GET /teams?id=900001 -> 200 (left this minute: 9, today: 89)
```

The key was sent (it's set, so `ProviderConfig` adds the header), and it shows as `****`. The only other header at this point is `Content-Length=0`: the `Accept` header the `RestClient` adds isn't there yet when the interceptors run. (This first request took 326 ms; later ones in the session took tens of milliseconds.)

**A slow provider.** Switch on `slow.json` and ask again:

```bash
curl -X POST http://localhost:8089/__admin/mappings/import -d @misbehaving/slow.json
curl -i -u admin:dev-only-password http://localhost:8080/provider/teams/900001
```

About 10 seconds later:

```
HTTP/1.1 504
Content-Type: application/problem+json

{"detail":"API-Football did not answer within 2 s","instance":"/provider/teams/900001","status":504,"title":"Gateway Timeout"}
```

and the log shows the three attempts and the waits between them:

```
09:17:13.500 WARN ProviderLoggingInterceptor : GET http://localhost:8089/teams?id=900001 [x-apisports-key=****, Content-Length=0] -> failed after 2021 ms: java.net.http.HttpTimeoutException: get
09:17:13.501 WARN ProviderRetry              : I/O error on GET request for "http://localhost:8089/teams": get - attempt 2 of 3 in 1000 ms
09:17:16.525 WARN ProviderLoggingInterceptor : GET http://localhost:8089/teams?id=900001 [x-apisports-key=****, Content-Length=0] -> failed after 2015 ms: java.net.http.HttpTimeoutException: Request cancelled
09:17:16.526 WARN ProviderRetry              : I/O error on GET request for "http://localhost:8089/teams": Request cancelled - attempt 3 of 3 in 2000 ms
09:17:20.535 WARN ProviderLoggingInterceptor : GET http://localhost:8089/teams?id=900001 [x-apisports-key=****, Content-Length=0] -> failed after 2005 ms: java.net.http.HttpTimeoutException: Request cancelled
```

2 seconds, a 1-second wait, 2 seconds, a 2-second wait, 2 seconds: about 9 seconds, and a `504` with a sentence anyone can read. Three things to notice:

- **No `RateLimitInterceptor` line.** No response ever arrived, so guide 06's interceptor had nothing to record or log; the exception went straight through it.
- **The JDK's message isn't consistent**: `get` the first time, `Request cancelled` after. Our own message doesn't depend on it, which is one reason to write your own.
- **Each attempt reached the provider.** The fake provider got all three requests (step 9's test counts them with `exactly(3)`), and was still holding the replies when the importer gave up. A real provider would have counted all three against your quota.

Switch it off: `curl -X POST http://localhost:8089/__admin/mappings/reset`.

**One `503`.** Switch on `service-unavailable.json`:

```bash
curl -X POST http://localhost:8089/__admin/mappings -d @misbehaving/service-unavailable.json
curl -i -u admin:dev-only-password http://localhost:8080/provider/teams/900001
```

```
HTTP/1.1 200
```

with Riverside Rovers, as if nothing had happened. The log says otherwise:

```
09:17:23.935 INFO ProviderLoggingInterceptor : GET http://localhost:8089/teams?id=900001 [x-apisports-key=****, Content-Length=0] -> 503 in 13 ms
09:17:23.935 INFO RateLimitInterceptor       : GET /teams?id=900001 -> 503 (left this minute: 9, today: 89)
09:17:23.936 WARN ProviderRetry              : API-Football answered HTTP 503 - attempt 2 of 3 in 1000 ms
09:17:24.995 INFO ProviderLoggingInterceptor : GET http://localhost:8089/teams?id=900001 [x-apisports-key=****, Content-Length=0] -> 200 in 44 ms
09:17:24.995 INFO RateLimitInterceptor       : GET /teams?id=900001 -> 200 (left this minute: 9, today: 89)
```

A `503`, a second's wait, and the same request again, which worked. This is what retries are for: a hiccup the caller never has to know about. Switch it off and re-arm it:

```bash
curl -X POST http://localhost:8089/__admin/mappings/reset
curl -X POST http://localhost:8089/__admin/scenarios/reset
```

**A broken connection.** Switch on `connection-reset.json`:

```bash
curl -X POST http://localhost:8089/__admin/mappings -d @misbehaving/connection-reset.json
curl -i -u admin:dev-only-password http://localhost:8080/provider/teams/900001
```

About 3 seconds later (the two waits; each attempt fails in milliseconds):

```
HTTP/1.1 502
Content-Type: application/problem+json

{"detail":"API-Football could not be reached (SocketException)","instance":"/provider/teams/900001","status":502,"title":"Bad Gateway"}
```

```
WARN ProviderLoggingInterceptor : GET http://localhost:8089/teams?id=900001 [x-apisports-key=****, Content-Length=0] -> failed after 21 ms: java.net.SocketException: Connection reset
WARN ProviderRetry              : I/O error on GET request for "http://localhost:8089/teams": Connection reset - attempt 2 of 3 in 1000 ms
...(twice more, with "attempt 3 of 3 in 2000 ms" between)
```

Switch it off: `curl -X POST http://localhost:8089/__admin/mappings/reset`.

**A `500`, never retried.** Guide 02's `server-error.json`:

```bash
curl -X POST http://localhost:8089/__admin/mappings -d @misbehaving/server-error.json
curl -u admin:dev-only-password http://localhost:8080/provider/teams/900001
```

```
{"detail":"API-Football answered HTTP 500","instance":"/provider/teams/900001","status":502,"title":"Bad Gateway"}
```

```
INFO ProviderLoggingInterceptor : GET http://localhost:8089/teams?id=900001 [x-apisports-key=****, Content-Length=0] -> 500 in 10 ms
INFO RateLimitInterceptor       : GET /teams?id=900001 -> 500 (left this minute: 9, today: 89)
```

The same answer as in guide 02, and **one** request: no `ProviderRetry` line at all. Switch it off: `curl -X POST http://localhost:8089/__admin/mappings/reset`.

**The provider is down.** Stop the fake provider (Ctrl+C in its terminal), and start an import:

```bash
curl -u admin:dev-only-password -X POST http://localhost:8080/imports/teams/900002
curl -u admin:dev-only-password http://localhost:8080/imports/1
```

```
{"id":1,"teamApiId":900002,"status":"FAILED","squadSize":null,"profilesFetched":0,"skipped":0,"skippedReasons":[],"quotaRemaining":89,"error":"API-Football could not be reached (ConnectException)","createdAt":"2026-11-17T09:21:25.492194Z","finishedAt":"2026-11-17T09:21:28.643654Z"}
```

```
WARN ProviderLoggingInterceptor : GET http://localhost:8089/teams?id=900002 [x-apisports-key=****, Content-Length=0] -> failed after 6 ms: java.net.ConnectException
WARN ProviderRetry              : I/O error on GET request for "http://localhost:8089/teams": null - attempt 2 of 3 in 1000 ms
...
WARN ImportRunner               : Import 1 of team 900002 failed: com.example.importer.provider.ProviderUnavailableException: API-Football could not be reached (ConnectException)
```

Three refused connections, each in a few milliseconds, two waits, and a `FAILED` import that says why, about 3 seconds after it started. `quotaRemaining` is `89` because that's what the provider last said, in the lookups before. The `: null` in the retry line is guide 02's: Spring's message, with the JDK's empty `ConnectException` message on the end. It's why our own message names the class instead. (Give the import a moment before the `GET`: until the third attempt has failed, it's still `RUNNING`.)

**The key, one last time.** Search the importer's output for the made-up key: in IntelliJ, click in the **Run** window and press Ctrl+F; in a terminal, scroll back, or run the jar with `> importer.log` on the end next time and use `grep -c demo-dummy-key-0000 importer.log`. While building this guide, the log of the whole session above had **13** request lines, each with `x-apisports-key=****`, and **0** occurrences of `demo-dummy-key-0000`.

Start the fake provider again, and stop the importer to remove the program argument and the environment variable.

**In PowerShell**, the same commands with `curl.exe`, for example:

```powershell
curl.exe -X POST http://localhost:8089/__admin/mappings/import -d @misbehaving/slow.json
curl.exe -i -u admin:dev-only-password http://localhost:8080/provider/teams/900001
curl.exe -X POST http://localhost:8089/__admin/mappings/reset
```

> **Sidebar: retry libraries.** You don't have to write `ProviderRetry` yourself. **Spring Retry** and **Resilience4j**'s `Retry` both give you attempts, backoff and "retry on these exceptions only" by configuration or annotation, and **Spring Framework 7** now has `@Retryable` built in (`org.springframework.resilience.annotation`, switched on with `@EnableResilientMethods`; it's in the Spring Framework the importer uses, but it wasn't tried for this series). In a bigger application, use one of them. Here, the rules are the lesson, and forty lines you can read show them better than an annotation: exactly which failures are retried, why `500` isn't, and how the waits go through the injected `Sleeper` so tests never sleep.

> **Sidebar: circuit breakers.** Retries help with a provider that's down for a second. A provider that's down for an hour gets three attempts on **every** request, each one a little more load on a service that's already struggling. A **circuit breaker** notices: after, say, five failures in a row, it **opens** and fails every call at once, without trying, for a while; then it lets one call through (**half-open**) to see whether the provider is back, and closes again if it is. Resilience4j has one, and Spring Cloud Circuit Breaker wraps it (neither was tried with Spring Boot 4.1.2 for this series, so check for a Boot 4 compatible version first). The importer doesn't need one: imports run one at a time, in the background, and a dead provider costs three quick attempts and a `FAILED` import with the reason. It matters for calls made **while a user waits**, under load, where failing fast is kinder than making everyone wait for retries that won't work.

---

## Your turn

### Exercise 1: predict what happens

The importer runs with the default settings (10-second read timeout, 3 attempts, 1-second first backoff, guide 06's 60-second minute pause), against the fake provider. Without running anything, work out what `curl -u admin:dev-only-password http://localhost:8080/provider/teams/900001` answers in each case, how many requests reach the provider, and roughly how long it takes.

1. Guide 02's exercise 4 fixture, `maintenance.json`, is switched on: a `503` for every request.
2. Guide 02's `bad-key.json` is switched on.
3. `slow.json` is switched on.
4. `service-unavailable.json` is switched on, and the importer was started with `--api-football.http.max-attempts=1`.
5. Guide 06's `too-many-requests.json` is switched on (one `429`, then normal).
6. Guide 06's `daily-exhausted-429.json` is switched on.
7. The fake provider isn't running.

<details>
<summary>Solution</summary>

1. **3 requests**, waits of 1 s and 2 s, then **`502`** with `API-Football answered HTTP 503`, after about 3 seconds. A `503` is worth retrying, but only twice; after the last attempt the `ProviderHttpException` goes up unchanged. Guide 02's exercise said "guide 08 decides which failures are worth it": this is the decision. (`temporaryErrorsAreRetriedOnlySoOften` proves the same for `502`.)
2. **1 request**, no wait, **`502`** with `API-Football rejected the request: {token=…}`. A `200` with errors is read by `checked(...)`, outside the retry, and `ProviderRejectedException` isn't something `ProviderRetry` catches.
3. **3 requests**, each given up after 10 seconds, with waits of 1 s and 2 s: about **33 seconds**, then **`504`** with `API-Football did not answer within 10 s`.
4. **1 request**, no wait, **`502`** with `API-Football answered HTTP 503`. With `max-attempts=1`, the first attempt is already the last. (And the one-shot `503` is used up: the next lookup works.)
5. **2 requests**: the `429`, a **60-second** wait from guide 06's `RateLimitInterceptor`, then the same request again, and **`200`** with Riverside Rovers. `ProviderRetry` never sees the `429`: the interceptor deals with it inside the `RestClient`. The log has two `ProviderLoggingInterceptor` lines, one per attempt, because the logging interceptor runs inside the rate-limit one.
6. **1 request**, no wait, no retry, and **`502`** with `API-Football's daily request quota is used up (it resets at 00:00 UTC)`. `DailyQuotaExhaustedException` is a `ProviderException`, so the lookup answers `502`; `ProviderRetry` doesn't catch it. And until 00:00 UTC (or a restart), nothing more is sent at all.
7. **3 refused connections**, waits of 1 s and 2 s, then **`502`** with `API-Football could not be reached (ConnectException)`, after about 3 seconds. In guide 02, this was the `500`.

</details>

### Exercise 2: a failure halfway through an import

An import fails when the provider breaks partway through: guide 05 marks it `FAILED`. What happens to the work done before the failure, and what does importing the team again cost? Answer with a test in `ProviderFailureTest`, `aFailureHalfwayKeepsWhatWasDoneAndTheNextImportCarriesOn`:

- Team `900072`, whose squad is players `9000721` and `9000722`. Player `9000721`'s profile is fine; every request for `9000722`'s profile has its connection reset.
- The first import ends `FAILED`, with an error that starts `API-Football could not be reached (`, after fetching **1** profile; and `9000722`'s profile was asked for **3** times.
- Then the provider recovers (`9000722`'s profile answers normally), and the team is imported again. This time it's `COMPLETE`, with **1** profile fetched, and `9000721`'s profile has been asked for exactly **once** over both imports.

<details>
<summary>Solution</summary>

Add the test and two helpers to `ProviderFailureTest`. The helpers follow guide 06's `RateLimitTest`: one stubs one player's profile with whatever response you give it, the other runs an import and returns how it ended.

```java
    @Test
    void aFailureHalfwayKeepsWhatWasDoneAndTheNextImportCarriesOn() {
        provider.stubFor(get(urlPathEqualTo("/teams"))
                .willReturn(okJson(ProviderJson.team(900072, "Team 900072", null, "England", false))));
        provider.stubFor(get(urlPathEqualTo("/players/squads"))
                .willReturn(okJson(ProviderJson.squad(900072, 9000721, 9000722))));
        stubProfile(9000721, okJson(ProviderJson.profile(9000721, "Player 9000721")));
        stubProfile(9000722, aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

        ImportView first = runImport(900072);

        assertThat(first.status()).isEqualTo(ImportStatus.FAILED);
        assertThat(first.error()).startsWith("API-Football could not be reached (");
        assertThat(first.profilesFetched()).isEqualTo(1);
        provider.verify(exactly(3), getRequestedFor(urlPathEqualTo("/players/profiles"))
                .withQueryParam("player", equalTo("9000722")));

        // The provider recovers. Import the team again.
        stubProfile(9000722, okJson(ProviderJson.profile(9000722, "Player 9000722")));
        ImportView second = runImport(900072);

        assertThat(second.status()).isEqualTo(ImportStatus.COMPLETE);
        assertThat(second.profilesFetched()).isEqualTo(1); // only the one still missing
        provider.verify(exactly(1), getRequestedFor(urlPathEqualTo("/players/profiles"))
                .withQueryParam("player", equalTo("9000721")));
    }

    private void stubProfile(long id, com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
        provider.stubFor(get(urlPathEqualTo("/players/profiles")).withQueryParam("player", equalTo(String.valueOf(id)))
                .willReturn(response));
    }

    private ImportView runImport(long teamApiId) {
        ImportView started = importService.start(teamApiId);
        runner.run(started.id(), teamApiId); // synchronous in tests
        return importService.view(started.id());
    }
```

with two more imports:

```java
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;

import com.example.importer.imports.ImportStatus;
```

- **Restubbing `9000722`** replaces its broken profile: when two stubs match a request equally well, WireMock uses the one added last (`errorsThatWouldHappenAgainAreNeverRetried` relies on the same thing).
- **What the first import kept:** the team, and player `9000721`, linked to it as soon as their profile arrived (guide 04's "link as you go"). A `FAILED` import isn't rolled back, because there's no transaction around it (guide 03): each save was its own short transaction, and stays done.
- **What the second import costs:** the team, the squad, and **one** profile. `9000721` is already stored, so it's linked without a request (guide 04's "fetch only what's missing"). A failure costs a retry of the missing part, not the whole import.

In the log, the first import ends with three `failed after … ms: java.net.SocketException: Connection reset` lines for `player=9000722`, two `ProviderRetry` warnings, and `Import … of team 900072 failed: com.example.importer.provider.ProviderUnavailableException: API-Football could not be reached (SocketException)`. With this test, `./mvnw test` runs 77 tests.

</details>

### Exercise 3: a cap on the backoff

With `api-football.http.max-attempts=5`, the waits would be 1, 2, 4 and 8 seconds, and with more attempts they'd soon be minutes. Add a setting **`api-football.http.max-backoff`**, default `30s`, so that no wait is ever longer than it. With the defaults nothing changes (1 s and 2 s are both under 30 s).

Prove it with a new test class, `provider/BackoffCapTest`, whose context has `max-backoff` set to **1 second**: a provider that answers `503` every time gets 3 requests, with waits of **1 s and 1 s**, not 1 s and 2 s.

<details>
<summary>Solution</summary>

**`provider/ProviderHttpProperties.java`**: one more component, with its default:

```java
@ConfigurationProperties("api-football.http")
public record ProviderHttpProperties(
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("1s") Duration firstBackoff,
        @DefaultValue("30s") Duration maxBackoff) {
}
```

(Add `@param maxBackoff the longest any one wait may be` to its Javadoc, and optionally `api-football.http.max-backoff=30s` to `application.properties`, so the setting is easy to find.)

**`provider/ProviderRetry.java`**: in `waitBeforeTrying`, after working out the wait:

```java
        Duration wait = http.firstBackoff().multipliedBy(1L << (nextAttempt - 2));
        if (wait.toMillis() > http.maxBackoff().toMillis()) {
            wait = http.maxBackoff(); // never longer than the cap
        }
```

The log line and the `sleeper.sleep(wait)` below it then use the capped wait.

**`src/test/java/com/example/importer/provider/BackoffCapTest.java`**:

```java
package com.example.importer.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.example.importer.ImporterIntegrationTest;
import com.example.importer.TestTime.RecordingSleeper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

@ImporterIntegrationTest
@TestPropertySource(properties = "api-football.http.max-backoff=1s")
class BackoffCapTest {

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
    RecordingSleeper sleeper;

    @BeforeEach
    void noWaitsYet() {
        sleeper.clear();
    }

    @Test
    void noWaitIsLongerThanTheCap() {
        provider.stubFor(get(urlPathEqualTo("/teams")).willReturn(aResponse().withStatus(503)));

        assertThatExceptionOfType(ProviderHttpException.class)
                .isThrownBy(() -> client.fetchTeam(900073))
                .withMessage("API-Football answered HTTP 503");
        assertThat(sleeper.sleeps()).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1)); // not 1 s, 2 s
        provider.verify(exactly(3), getRequestedFor(urlPathEqualTo("/teams")));
    }
}
```

- **The cap is compared in milliseconds** with `toMillis()`, as `describe(...)` already does. If the doubled wait is longer, the cap is used instead.
- **A class of its own**, because the setting is different: as with guide 06's `ReserveTest`, a different `@TestPropertySource` means a Spring context of its own, and every other test keeps the 30-second default.
- The log shows the cap at work: `API-Football answered HTTP 503 - attempt 3 of 3 in 1000 ms`, where it said `2000 ms` before.

</details>

### Exercise 4: a provider that wants the key in the URL

API-Football takes its key in a header. Some providers want it in the URL instead, such as `GET /teams?id=900001&apikey=…`. Suppose the importer called such a provider, with everything else as built in this guide. Without writing code, answer:

1. Which of the importer's log lines would now contain the key?
2. Would `ProviderLoggingTest` notice?
3. What would you change?

<details>
<summary>Solution</summary>

1. **Both interceptors' lines.** `ProviderLoggingInterceptor` logs the full URL (`request.getURI()`), query and all, and only masks **headers**. Guide 06's `RateLimitInterceptor` logs the path **and query**. So every request would print the key twice. `ProviderRetry`'s `WARN` line wouldn't: look at its real output in step 12, `I/O error on GET request for "http://localhost:8089/teams"`, where Spring's message leaves the query off. Neither would `ApiFootballProperties`, thanks to its `toString()`.
2. **Yes**, which is the point of having it. `theKeyIsSentButNeverAppearsInTheLogs` asserts the captured output `doesNotContain(KEY)`, wherever the key is. It searches for the key's **value**, not for a header name, so it catches a leak through any route, including one nobody thought of when the masking was written. (You'd have to change its `withHeader` check, since the key would no longer be a header.)
3. Mask the key in **every** place a URL is printed: both interceptors would need to replace the `apikey` parameter's value with `****` before logging. Better still, if the provider allows a header, use the header: a URL also ends up in places your code doesn't control, such as proxy logs and the provider's own access logs. Masking by name, as `masked(...)` does for headers, is the robust way: it works for any key value.

</details>

---

## Checkpoint

In `importer/`:

```bash
./mvnw test
```

```
[INFO] Tests run: 76, Failures: 0, Errors: 0, Skipped: 0
```

That's guide 07's 65, unchanged, plus `ProviderFailureTest`'s 8, `ProviderLoggingTest`'s 2, and one more in `ApiFootballPropertiesTest`. With the tests from exercises 2 and 3, you'll have 78.

With the fake provider and the importer running, every request to the provider has a `ProviderLoggingInterceptor` line with `x-apisports-key=****` (when a key is set) and its status and time, followed by guide 06's line. With `slow.json` switched on, `GET /provider/teams/900001` answers `504 Gateway Timeout` after three attempts; with the fake provider stopped, a `502` with `API-Football could not be reached (ConnectException)`, never a `500`; and an import that can't reach the provider ends `FAILED` with that reason.

Your files (new ones marked **new**, changed ones **changed**; unchanged folders left out):

```
importer/src/
├── main/
│   ├── java/com/example/importer/
│   │   ├── provider/
│   │   │   ├── ApiFootballClient.java               changed
│   │   │   ├── ApiFootballProperties.java           changed
│   │   │   ├── ProviderConfig.java                  changed
│   │   │   ├── ProviderHttpProperties.java          new
│   │   │   ├── ProviderLoggingInterceptor.java      new
│   │   │   ├── ProviderRetry.java                   new
│   │   │   └── ProviderUnavailableException.java    new
│   │   └── web/
│   │       └── ApiExceptionHandler.java             changed
│   └── resources/
│       └── application.properties                   changed
└── test/java/com/example/importer/
    └── provider/
        ├── ApiFootballPropertiesTest.java           changed
        ├── ProviderFailureTest.java                 new
        └── ProviderLoggingTest.java                 new

fake-provider/
└── misbehaving/
    ├── bad-key.json
    ├── connection-reset.json                        new
    ├── daily-exhausted-200.json
    ├── daily-exhausted-429.json
    ├── daily-limit-reached.json
    ├── minute-limit.json
    ├── server-error.json
    ├── service-unavailable.json                     new
    ├── slow.json                                    new
    └── too-many-requests.json
```

No new dependencies (`OutputCaptureExtension` comes with Spring Boot's test starter), and no new migration. (If you did exercise 3, `provider/BackoffCapTest.java` is new as well, and `ProviderHttpProperties.java` and `ProviderRetry.java` are a little different.)

---

## Common errors

**`ProviderLoggingTest` fails, and the output shows the key**
`Tests run: 2, Failures: 2`, with the captured output in the failure message, including a line like this:

```
c.e.i.p.ProviderLoggingInterceptor       : GET http://localhost:51746/teams?id=900080 [x-apisports-key=secret-test-key-7f3a9c, Content-Length=0] -> 200 in 841 ms
```

and, at the end:

```
to contain:
  "x-apisports-key=****"
```

The headers are being logged without the mask: for example, `masked(...)` writes every value as it is, or the log line prints `request.getHeaders()` directly instead of `masked(request.getHeaders())`. (This was captured while building the guide, by taking the `****` replacement out.) In a test, it's a failure. In a real log, shipped to a log service, that line is a **leaked key**, and the fix is a new key, not a code change. That's why the test exists: put the mask back, and keep the test.

**`{"timestamp":"…","status":500,"error":"Internal Server Error","path":"/provider/teams/900001"}` when the fake provider isn't running**
And in the importer's log:

```
org.springframework.web.client.ResourceAccessException: I/O error on GET request for "http://localhost:8089/teams": null
```

This is guide 02's Common error, which this guide fixes. Once the steps are done, the same situation gives a `502` with `API-Football could not be reached (ConnectException)`, after three attempts (about 3 seconds). If you still get the `500`, the call didn't go through `ProviderRetry`: check `fetchTeam` in `ApiFootballClient` wraps the call in `retry.call(() -> ...)` (step 5). A `ResourceAccessException` that leaves `ApiFootballClient` isn't a `ProviderException`, so no handler of ours answers it.

**A lookup takes about half a minute, then answers `504`**
Not an error: `slow.json` is still switched on, and the importer is using its default 10-second read timeout, so it tries three times for 10 seconds each, with 1 and 2 seconds in between. Switch the fixture off with `curl -X POST http://localhost:8089/__admin/mappings/reset`. (Step 12 used a 2-second read timeout to make this quicker to watch.)

---

## Recap

- Without a time limit, the JDK's HTTP client **waits for ever** for a reply, and Spring Boot sets no limit for you. A stuck provider would leave an import `RUNNING` for good.
- Two limits, set explicitly on the `RestClient`: a **connect timeout** (5 s, on the JDK `HttpClient`) and a **read timeout** (10 s, on Spring's `JdkClientHttpRequestFactory`), both settings in **`ProviderHttpProperties`**.
- **Retry only what might work next time, and only what's safe to repeat.** Every call is a `GET`. Retried: no connection, a dropped connection, no reply in time, `502`, `503`, `504`. **Never** retried: other `4xx`s, `500`, errors in a `200`, the daily quota, data we can't store. `429` stays with guide 06.
- **Every retry costs quota**, and a request that timed out may already have been counted. So: at most **3 attempts**, and a generous read timeout.
- **Exponential backoff**: 1 s, then 2 s, through the injected `Sleeper`, so tests record the waits instead of sleeping them. Many clients would add **jitter**.
- **`ProviderRetry` goes around the call**, not in an interceptor, so every attempt passes the rate limits and the logging again, and decisions are made on our own exceptions.
- After the last attempt: **`ProviderUnavailableException`**, answered with **`504 Gateway Timeout`** for a timeout and **`502 Bad Gateway`** otherwise; an import ends **`FAILED`** with the reason. Guide 02's `500` is gone.
- **Every request is logged**, one line per attempt, with **`x-apisports-key` always `****`**; `ApiFootballProperties.toString()` hides it too; and **a test proves the key was sent but never printed**.
- The fake provider fails on purpose: **`fixedDelayMilliseconds`** for slowness, **`"fault"`** for broken connections, and a one-shot scenario for a `503`.

### Interview angle

<details>
<summary>"What timeouts would you set on an HTTP client, and why?"</summary>

At least two. A connect timeout, for setting up the connection, which can be short, because a healthy server accepts connections quickly. And a read timeout, for waiting for the reply once the request is sent, sized to how long the service normally takes plus a margin. Never leave them unset: many clients, the JDK's included, wait indefinitely by default, so one stuck server can tie up threads until the application stops working. Then think about the total: attempts times the read timeout, plus the waits between them, is the longest a caller can be kept waiting, and it has to be acceptable for whoever's waiting.

</details>

<details>
<summary>"Which failures should a client retry?"</summary>

Ones that might succeed next time, for requests that are safe to repeat. Connection failures, timeouts, `502`, `503` and `504` are usually temporary. `429` is worth retrying after the wait the server asks for. Other `4xx`s mean the request is wrong and will fail again, so never retry them. `500` is a judgement call: it's often a bug that will repeat, and if every request costs money or quota, retrying it is waste. Before retrying anything, check it's safe: `GET`, `PUT` and `DELETE` are idempotent; a `POST` needs something like an idempotency key, because a timeout doesn't tell you whether it worked. And limit the attempts, with backoff between them.

</details>

<details>
<summary>"What's exponential backoff, and why add jitter?"</summary>

Waiting longer before each retry, typically doubling: 1 second, 2, 4. A server that's struggling gets more and more room to recover, rather than a flood of immediate retries. Jitter is a random amount added to each wait. Without it, clients that failed at the same moment retry at the same moment, every time, and hit the server in synchronised waves. With it, they spread out. It matters most when many clients share one server; a single client gains little from it.

</details>

<details>
<summary>"Should a gateway answer `502` or `504` when the service behind it fails?"</summary>

`504 Gateway Timeout` when the service behind didn't answer in time; `502 Bad Gateway` when it answered with something unusable, or couldn't be reached at all. Either way, not `500`, which says the gateway itself is broken, and not the upstream's own status passed straight through, which would make the caller think its request was the problem. Include a clear message of your own, rather than the upstream's raw error, and log the details.

</details>

<details>
<summary>"How do you stop secrets leaking into logs?"</summary>

Don't log them in the first place, and make that structural rather than a matter of care. Mask sensitive headers by name wherever requests are logged, so any value is hidden, including ones the code has never seen. Override `toString()` on objects that hold secrets, such as configuration records, because someone will log one eventually. Keep secrets out of URLs, which end up in access logs you don't control. And write a test that uses a distinctive fake secret, checks it was really sent, and asserts it appears nowhere in the captured output: masking without a test is masking that will be "tidied up" one day.

</details>

<details>
<summary>"When would you add a circuit breaker?"</summary>

When calls to a failing service are made while users wait, under load. Retries help with brief failures, but during a long outage they multiply the load on the struggling service and keep every caller waiting for attempts that won't work. A circuit breaker counts failures; past a threshold it opens and fails calls at once, then lets an occasional trial call through and closes when the service recovers. For a background job that runs one call at a time and already fails cleanly, as here, it adds little. Libraries such as Resilience4j provide one.

</details>

### Further reading

- [Java 25 API: `HttpRequest.Builder.timeout`](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html#timeout(java.time.Duration)), on what happens when no timeout is set, and [`HttpClient.Builder.connectTimeout`](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.Builder.html#connectTimeout(java.time.Duration))
- [Spring Framework reference: REST clients](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html), including request factories and interceptors
- [RFC 9110 section 9.2: method properties](https://www.rfc-editor.org/rfc/rfc9110#section-9.2) (safe and idempotent methods), and sections [15.6.3](https://www.rfc-editor.org/rfc/rfc9110#section-15.6.3) to [15.6.5](https://www.rfc-editor.org/rfc/rfc9110#section-15.6.5): `502`, `503` and `504`
- [AWS Architecture Blog: Exponential Backoff and Jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/)
- [WireMock: simulating faults](https://wiremock.org/docs/simulating-faults/), for delays and broken connections
- [Spring Boot reference: test utilities](https://docs.spring.io/spring-boot/reference/testing/test-utilities.html), including output capture
- [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html), on what never to log
- [Resilience4j: CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker) and [Retry](https://resilience4j.readme.io/docs/retry)

**Simplification to notice:** `ProviderRetry` treats every call the same. A client calling something that costs more per request, or that a user is waiting on, might give different calls different budgets: fewer attempts for a lookup a person is waiting for, more for a background import.

---

**Next:** [09: The import queue](09-the-import-queue.md). Imports stop being something you start by hand, one at a time: a `POST` can queue them, and a scheduled worker works through the queue, carrying on by itself after 00:00 UTC when the daily quota resets. And a closing security checklist for the whole importer.
