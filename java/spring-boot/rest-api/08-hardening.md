# 08: Hardening

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](README.md) for version notes.

## What you'll learn

- The **same-origin policy**, and how **CORS** lets a browser front end on another origin call the API
- What a **preflight** request is, which requests need one, and why **`Access-Control-Expose-Headers`** is the part people forget
- Why CORS is **not** a security control for curl, scripts or other servers, and how the API's CORS differs from the auth-server's in guide 06
- **Rate limiting** per caller with a hand-written **token bucket**, answering **`429 Too Many Requests`** with **`Retry-After`**, as a `ProblemDetail`
- Where a filter belongs in **Spring Security's filter chain**, and why this one isn't a `@Component`
- The limits of an in-memory rate limiter, and what real APIs use instead: **Bucket4j**, a shared store, or an **API gateway**
- The **security headers** Spring Security already sends, and the two this API adds
- **Errors that give nothing away**: a generic `500` with no message and no stack trace, so that every error the API answers is now a `ProblemDetail`
- The **size limits** the series already has, and the one it doesn't
- The **OWASP API Security Top 10 (2023)** as a checklist for the whole series

By the end, a single-page app on `http://localhost:5173` can call the API from the browser, and no other website can. A caller who sends more than 20 requests in a burst, or more than 5 a second for long, gets a `429` that says when to try again. Every response carries the headers a browser expects, and a bug in the code produces a polite `500` that tells the caller nothing about the code, the database or the libraries behind it.

---

## Prerequisites for this guide

You need both projects as they were at the end of [07: Concurrency and retries](07-concurrency-and-retries.md): the `api` with all 74 tests passing, and the `auth-server` with all 11.

For trying things out by hand you also need:

- **The auth-server running** (port 9000), then the API, as in guide 06. Nothing in this guide needs a token by hand, but the API checks tokens against the auth-server as soon as one arrives.
- **Git Bash** (or another Unix shell) for step 9's burst of requests, which uses `xargs`.

You don't need a front end. The guide talks about a single-page app on `http://localhost:5173` (the port a Vite development server uses), and the tests and curl play its part.

---

## Concepts

### Origins, again

Guide 06 met the **same-origin policy**. An **origin** is a scheme, host and port together, so `http://localhost:5173` and `http://localhost:8080` are different origins. JavaScript on a page from one origin may send a request to another, but the browser won't let it **read the response** unless the other server says, in **CORS** headers, that pages from that origin are allowed to.

The policy exists to protect **users**. Alice is signed in to her bank in one tab, and opens a dodgy website in another. Without the policy, that website's JavaScript could call the bank's API from alice's browser, with whatever the browser sends automatically, and read her statements. With it, the browser refuses to hand the answer to a page from the wrong origin.

In guide 06, Swagger UI on `localhost:8080` needed a token from `localhost:9000`, so the **auth-server** got a CORS rule: one origin, one path, one method. Swagger UI's calls to the **API** needed nothing, because Swagger UI is served by the API: same origin.

A real ticket seller's customers wouldn't use Swagger UI. They'd use a website, usually a **single-page app** (SPA) built with React, Vue or Angular, served from its own origin: `https://www.tickets.example` in production, `http://localhost:5173` on a developer's machine. Its JavaScript calls the API on a different origin, so now the **API** needs a CORS rule.

| | Guide 06 | This guide |
| --- | --- | --- |
| Server | auth-server (`:9000`) | API (`:8080`) |
| Page allowed | Swagger UI, `http://localhost:8080` | The SPA, `http://localhost:5173` |
| Paths | `/oauth2/token` only | Everything (`/**`) |
| Methods | `POST` | `GET`, `POST`, `PUT`, `PATCH`, `DELETE` |
| How it's switched on | A `CorsConfigurationSource` bean, picked up without writing a filter chain | The same kind of bean, plus `.cors(...)` in the API's own filter chain |

### Preflight requests

Some cross-origin requests the browser just sends, and then decides whether the page may read the answer. These **simple requests** are a `GET`, `HEAD` or `POST` with only a few ordinary headers, and no `Content-Type` other than the ones an HTML form could send. A plain `GET /events/2` from the SPA is one.

Anything else could do something an HTML form never could, so the browser **asks first**. It sends a **preflight**: an `OPTIONS` request to the same URL, with no body and no token.

```
OPTIONS /bookings
Origin: http://localhost:5173
Access-Control-Request-Method: POST
Access-Control-Request-Headers: authorization,content-type,idempotency-key
```

"A page from `http://localhost:5173` wants to `POST` here, with these headers. May it?" The server answers with what it allows:

```
Access-Control-Allow-Origin: http://localhost:5173
Access-Control-Allow-Methods: GET,POST,PUT,PATCH,DELETE
Access-Control-Allow-Headers: authorization, content-type, idempotency-key
Access-Control-Max-Age: 3600
```

Only if the origin, method and every header are allowed does the browser send the real request. `Access-Control-Max-Age` lets it remember the answer for an hour, instead of asking before every request.

Almost every request the SPA makes to this API needs a preflight: any request with an `Authorization` header, any JSON body (`Content-Type: application/json` isn't one of the form types), `If-Match` (guide 07), `Idempotency-Key` (guide 07), and any `PUT`, `PATCH` or `DELETE`. That's guide 01's promised use of `OPTIONS`.

There's one more part, and it's the one people forget. Even when the request is allowed, the page's JavaScript can only read a handful of response headers, such as `Content-Type` and `Cache-Control`. `ETag`, `Location`, `Retry-After` and guide 07's `Idempotent-Replayed` are all invisible to it unless the server lists them in **`Access-Control-Expose-Headers`**. Without that, the header is right there on the wire, in the browser's network tab, and `response.headers.get("ETag")` is still `null`. The SPA could never send `If-Match`.

### CORS isn't a lock

CORS is enforced by **browsers**, and only browsers. curl, Postman, a Python script and the scanner service in guide 09 never send a preflight and never look at `Access-Control-Allow-Origin`. If the API's only protection were CORS, an attacker would simply not use a browser.

So CORS doesn't decide who may call the API. **Tokens** do, as they have since guide 05. CORS decides which **web pages** may use a visitor's browser to call it and read the answer. That's worth keeping tight, but it's a rule for browsers, not a wall around the API.

Two more points follow from that:

- **Never allow every origin** (`*`) on an API that real users' data comes out of. Name the origins you serve your front end from.
- **`allowCredentials` stays off.** It's for APIs that rely on **cookies**, which the browser attaches by itself, which is exactly what makes cross-site requests dangerous. This API uses bearer tokens, which the SPA's own code adds. Spring refuses the combination of `*` and credentials outright.

### Rate limiting

Nothing so far stops one caller sending a thousand requests a second. It might be a buggy client stuck in a retry loop, a scraper copying every event, or a bot trying to buy every front-row ticket the moment they go on sale. Each request is perfectly valid. Together they use up the API's threads and database connections, and everyone else waits.

**Rate limiting** gives each caller a budget. Over it, the API answers straight away, before doing any real work, with **`429 Too Many Requests`** ([RFC 6585](https://www.rfc-editor.org/rfc/rfc6585#section-4)) and a **`Retry-After`** header saying how many seconds to wait. A well-behaved client waits that long and tries again. Guide 09's scanner will.

**Who** gets a budget matters:

- **Per caller with a token**: the token's `sub`. For a customer that's the user (`alice`); for a service using client credentials it's the client id (`organiser`, `scanner`). Guide 05 showed that this server's tokens have no separate `client_id` claim, so `sub` covers both.
- **Per IP address without a token**: the public `GET`s are open to anyone, so the address is all there is to go on.

One limit for the whole API would be no good: one greedy caller would use it up and lock everybody else out.

### The token bucket

The most common way to count is a **token bucket** (nothing to do with access tokens):

```
         refill: 5 tokens a second
                  │
                  ▼
           ┌─────────────┐
           │ ● ● ● ● ● ● │  capacity: 20 tokens
           │ ● ● ● ● ● ● │
           │ ● ● ● ●     │
           └──────┬──────┘
                  │  each request takes 1
                  ▼
     a token left? → carry on        none left? → 429, Retry-After
```

Each caller has a bucket that holds up to **20** tokens, and starts full. Every request takes one. Tokens drip back in at **5 a second**, and the bucket never holds more than 20. So a caller can **burst** 20 requests at once, which is what a page loading several things at a time looks like, and then keep going at **5 a second** for as long as they like. Only a caller going faster than that for longer than that runs dry.

Nothing has to drip on a timer. Each bucket remembers how many tokens it had and **when** it last counted. When a request arrives, it works out how many tokens have dripped in since, adds them (up to the capacity), and takes one. With none to take, `Retry-After` is the time until the next whole token arrives, rounded up to whole seconds.

The alternatives you'll hear of are the **fixed window** ("100 requests per minute", reset on the minute, which lets a caller send 200 across the boundary), and the **sliding window**, which fixes that by counting the last 60 seconds whatever the clock says. A token bucket behaves like a sliding window, is cheap to work out, and makes the burst size and the steady rate two separate settings.

### Security headers

Since guide 05, every response has carried some extra headers. They're **Spring Security's defaults**, and they're instructions to **browsers**:

| Header | What it tells the browser |
| --- | --- |
| `X-Content-Type-Options: nosniff` | Believe the `Content-Type`. Don't guess, and so never run a JSON response as a script or show it as HTML |
| `X-Frame-Options: DENY` | Don't show this response inside a frame on any page. That stops **clickjacking**, where another site puts yours in an invisible frame and tricks the user into clicking it |
| `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` (with `Pragma` and `Expires`) | Don't store this response anywhere. Added only when the response hasn't set its own `Cache-Control`, as guide 05 showed |
| `X-XSS-Protection: 0` | Switch **off** old browsers' "XSS auditor". It caused more problems than it solved, and current advice is to disable it |
| `Strict-Transport-Security` (HSTS) | Only ever use HTTPS for this host. Sent **only over HTTPS**, so you won't see it on `http://localhost` |

For a JSON API, the OWASP [REST Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html#security-headers) adds a **`Content-Security-Policy`** of `frame-ancestors 'none'`, the modern version of `X-Frame-Options: DENY`. This guide adds that, and a **`Referrer-Policy: no-referrer`**, so a browser following a link out of anything the API serves doesn't send the API's URL, with whatever ids and query parameters are in it, to the next site.

What about the rest of a Content Security Policy, such as `default-src 'none'` ("this response may load nothing at all")? For pure JSON it would be ideal. But the same application serves **Swagger UI**, an HTML page that loads scripts and styles, and the policy would apply to it too. This guide deliberately stops at `frame-ancestors`.

### Errors that give nothing away

Guide 02 left a gap. An exception nobody planned for, such as a `NullPointerException`, reaches Spring Boot's default error handling and comes back as Boot's own JSON:

```json
{"timestamp":"2026-11-12T18:22:53.763Z","status":500,"error":"Internal Server Error","path":"/events"}
```

That's not dangerous, since Boot includes no message and no stack trace by default, but it's **not a `ProblemDetail`**, so every client needs a second way to read errors. Worse is the fix people reach for: a handler that returns `ex.getMessage()`. Messages from deep in the code say things like `Could not connect to jdbc:h2:file:./data/tickets as sa`, `could not execute statement [Unique index or primary key violation: "PUBLIC.UK_…"]`, or the name of a class and a library version. Each is a little map of the system behind the API for an attacker.

The rule: **the details are for your logs, not for the caller.** A bug on the server is a `500` whose detail says, politely, that something went wrong on our side. The full exception goes to the server log, where the people who can fix it will look.

### Size limits

**API4:2023, Unrestricted Resource Consumption**, is about callers who make the API do too much work, and rate limiting covers only the *number* of requests. The *size* of each one matters too: one request for a million rows, or with a 50 MB description, can do more damage than a hundred small ones. Step 11 lists what the series already limits, and the one gap.

---

## Steps

### 1. Let the front end in

Everything in this guide happens in the **api** project. Add a bean to `security/SecurityConfig.java`, with imports for `java.time.Duration`, `java.util.List`, and `CorsConfiguration`, `CorsConfigurationSource` and `UrlBasedCorsConfigurationSource` from `org.springframework.web.cors`:

```java
    /**
     * The browser front end (a single-page app on another origin) may call the API.
     * Swagger UI doesn't need this: it's served by the API itself, so it's the same origin.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration frontEnd = new CorsConfiguration();
        frontEnd.setAllowedOrigins(List.of("http://localhost:5173"));
        frontEnd.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        frontEnd.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-Match", "If-None-Match", "Idempotency-Key"));
        // Response headers the front end's JavaScript may read (a few simple ones are always allowed)
        frontEnd.setExposedHeaders(List.of("Location", "ETag", "Retry-After", "Idempotent-Replayed"));
        // Browsers may cache this answer to the preflight for an hour
        frontEnd.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", frontEnd);
        return source;
    }
```

- **One origin**, written out in full. In production you'd list the real website's origin instead, or as well. It's one line to change, so it isn't a property here.
- **The methods and request headers** are exactly what this API uses: a token, JSON, guide 03's `If-None-Match`, and guide 07's `If-Match` and `Idempotency-Key`. A header that isn't on the list fails the preflight.
- **The exposed headers** are the API's own response headers that the SPA needs: `Location` after a `201`, `ETag` for `If-Match`, `Retry-After` after a `429`, and `Idempotent-Replayed`.
- **`/**`**: the rule applies to every path. Tokens still decide what each caller may do.

At the top of the `api` filter chain, switch on Spring Security's CORS support:

```java
        http
                // Answer browsers' CORS preflight requests, using corsConfigurationSource() below
                .cors(Customizer.withDefaults())
                // No cookies or sessions: every request carries its own bearer token
                .csrf(csrf -> csrf.disable())
```

`.cors(...)` adds Spring Security's `CorsFilter`, which uses the `CorsConfigurationSource` bean. (Spring Security would find the bean even without this line, which is how guide 06's auth-server got CORS without a filter chain of its own. Writing it down makes the chain easier to read.) The filter runs **before authentication**, and that matters: a preflight carries no token, so if it had to pass the token check it would always get a `401`, and the browser would never send the real request.

### 2. Try a preflight

Restart the API, and send the preflight the SPA's browser would send before booking. No token, no body:

```bash
curl -i -X OPTIONS -H 'Origin: http://localhost:5173' -H 'Access-Control-Request-Method: POST' -H 'Access-Control-Request-Headers: authorization,content-type,idempotency-key' http://localhost:8080/bookings
```

```
HTTP/1.1 200
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Access-Control-Allow-Origin: http://localhost:5173
Access-Control-Allow-Methods: GET,POST,PUT,PATCH,DELETE
Access-Control-Allow-Headers: authorization, content-type, idempotency-key
Access-Control-Expose-Headers: Location, ETag, Retry-After, Idempotent-Replayed
Access-Control-Max-Age: 3600
X-Content-Type-Options: nosniff
X-XSS-Protection: 0
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Expires: 0
X-Frame-Options: DENY
Content-Length: 0
```

A yes. `Vary: Origin` tells caches that the answer depends on who's asking, so one origin's answer is never served to another. The `200` comes from the `CorsFilter` itself: the request never reached the token check or a controller, which is why it needed no token.

Now be another website. Change the origin to `https://evil.example` and send it again:

```
HTTP/1.1 403
...

Invalid CORS request
```

No `Access-Control-Allow-Origin`, so a browser wouldn't send the real request. It's the same answer the auth-server gave in guide 06. (It's plain text, not a `ProblemDetail`: it comes from Spring's `CorsFilter`, and it's meant for a browser, which never shows it to the page's JavaScript anyway.)

Nothing stops **curl** sending the real request regardless, from any "origin" it likes. That's the point the Concepts made: CORS is for browsers. `POST /bookings` itself still needs a customer's token.

### 3. The rate-limit settings

Add the limits to `application.properties`:

```properties
# Rate limiting (RateLimitFilter): each caller may burst 20 requests, then 5 a second
tickets.rate-limit.capacity=20
tickets.rate-limit.refill-per-second=5
```

These are this API's own properties, not Spring Boot's. They're properties rather than constants because the tests need different numbers (step 15).

### 4. The exception

The filter needs a way to say "429" that `ApiExceptionHandler` understands. Create `web/TooManyRequestsException.java`:

```java
package com.example.tickets.web;

/** The caller has used up their requests for now. ApiExceptionHandler turns it into a 429. */
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(long retryAfterSeconds) {
        super("Too many requests. Try again in " + retryAfterSeconds + " seconds");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
```

It carries the number of seconds, for the `Retry-After` header. (The message says "1 seconds" when it's one. Fix the grammar if it bothers you, and change the test in step 15 to match.)

### 5. The token-bucket filter

Create `security/RateLimitFilter.java`:

```java
package com.example.tickets.security;

import com.example.tickets.web.TooManyRequestsException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting with a token bucket per caller. Each caller's bucket holds up to
 * {@code capacity} tokens and refills at {@code refillPerSecond}. Every request takes
 * one token; with none left, the answer is 429 Too Many Requests and a Retry-After.
 *
 * Kept in memory, so it's per instance of the API and forgets on restart. Real APIs
 * use a shared store (Redis), a library such as Bucket4j, or the API gateway in front.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final int capacity;
    private final double refillPerSecond;
    private final HandlerExceptionResolver resolver;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(int capacity, double refillPerSecond, HandlerExceptionResolver resolver) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Bucket bucket = buckets.computeIfAbsent(caller(request), key -> new Bucket(capacity, System.nanoTime()));
        long waitSeconds = bucket.take(capacity, refillPerSecond, System.nanoTime());
        if (waitSeconds > 0) {
            // Same trick as SecurityProblemHandler: let ApiExceptionHandler write the ProblemDetail
            resolver.resolveException(request, response, null, new TooManyRequestsException(waitSeconds));
            return;
        }
        chain.doFilter(request, response);
    }

    /** Who's calling: the token's subject (a user, or a service's client id), else the IP address. */
    private static String caller(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return "sub:" + token.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }

    /** How many tokens a caller has left, and when we last topped them up. */
    private static final class Bucket {

        private double tokens;
        private long lastRefill;

        Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.lastRefill = now;
        }

        /** Takes a token and returns 0, or returns how many seconds until there's one to take. */
        synchronized long take(int capacity, double refillPerSecond, long now) {
            double secondsSinceLastRefill = (now - lastRefill) / 1_000_000_000.0;
            tokens = Math.min(capacity, tokens + secondsSinceLastRefill * refillPerSecond);
            lastRefill = now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return (long) Math.ceil((1 - tokens) / refillPerSecond);
        }
    }
}
```

From the bottom up:

- **`Bucket`** is the token bucket from the Concepts, and nothing more. `tokens` is a `double` because tokens drip back in fractions: a tenth of a second at 5 a second is half a token. `take` first tops the bucket up for the time since it last looked (never past `capacity`), then either takes a whole token and returns `0`, or works out how long until there is one. With `0.2` tokens left at 5 a second, that's `0.8 / 5 = 0.16` seconds, rounded **up** to `1`: `Retry-After` takes whole seconds, and rounding down would send the client back too soon. With less than one token left, the answer is always at least 1.
- **`System.nanoTime()`** is Java's clock for measuring how long something took. Unlike the time of day, it never jumps backwards when the computer's clock is corrected.
- **`synchronized`** on `take`: one caller's requests can arrive on several threads at once (guide 07's lesson), and two threads mustn't both see "1 token left" and both take it. Each bucket is its own lock, so different callers never wait for each other.
- **`caller`** decides whose bucket to use. By the time this filter runs, Spring Security has checked any bearer token and put a `JwtAuthenticationToken` in the security context. Its `getName()` is the token's `sub`. Without a token, the key is the IP address. The `sub:` and `ip:` prefixes stop a user called `127.0.0.1` from sharing a bucket with that address.
- **`buckets`** is a `ConcurrentHashMap`, and `computeIfAbsent` creates a full bucket the first time a caller is seen, safely, even when a new caller's first two requests arrive together.
- **`doFilterInternal`** takes a token, then either lets the request carry on down the chain or stops it there. Stopping uses guide 05's trick from `SecurityProblemHandler`: the filter runs before Spring MVC, so it hands the exception to Spring MVC's **`HandlerExceptionResolver`**, which finds the matching `@ExceptionHandler` in `ApiExceptionHandler` and writes its response. The `429` becomes a `ProblemDetail` like every other error, written in one place.
- **`OncePerRequestFilter`** is Spring's base class for a filter that must do its work only once per request, even if the request passes it twice. That will matter in step 6.

The comment at the top says what this filter **isn't**. The map lives in this one process's memory: run the API on three servers and each caller gets three budgets; restart it and everyone's bucket is full again. The map is also never cleaned up, so every caller ever seen keeps a bucket until the API restarts, and someone with a great many IP addresses could make it big. The sidebar after Common errors looks at what real APIs use.

### 6. Into the security chain

The filter has to run **after** the bearer token has been checked, or every caller would look anonymous and be limited by IP address. Spring Security's `BearerTokenAuthenticationFilter` is the one that checks it, so add ours straight after that, at the end of the chain in `SecurityConfig`, with an import for `org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter`:

```java
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                // After the token is checked, so we know who's calling
                .addFilterAfter(rateLimit, BearerTokenAuthenticationFilter.class)
```

The chain's method gets the filter as a new parameter:

```java
    @Bean
    SecurityFilterChain api(HttpSecurity http, SecurityProblemHandler problems, RateLimitFilter rateLimit)
            throws Exception {
```

And the filter is a bean made in `SecurityConfig`, with imports for `org.springframework.beans.factory.annotation.Qualifier`, `org.springframework.beans.factory.annotation.Value` and `org.springframework.web.servlet.HandlerExceptionResolver`:

```java
    /** Added to Spring Security's chain below, after authentication, so it knows who's calling. */
    @Bean
    RateLimitFilter rateLimitFilter(@Value("${tickets.rate-limit.capacity}") int capacity,
                                    @Value("${tickets.rate-limit.refill-per-second}") double refillPerSecond,
                                    @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return new RateLimitFilter(capacity, refillPerSecond, resolver);
    }
```

- **`@Value`** reads the two properties from step 3. **`@Qualifier("handlerExceptionResolver")`** picks Spring MVC's main resolver out of the several `HandlerExceptionResolver` beans, exactly as `SecurityProblemHandler` does.
- **Where it sits in the chain decides what it sees.** Spring Security's own filters run in a fixed order. `CorsFilter` (step 1) comes before `BearerTokenAuthenticationFilter`, and answers preflights itself, so a preflight never reaches the rate limiter. A request with a token that isn't valid is turned away with a `401` by `BearerTokenAuthenticationFilter`, so it doesn't reach the rate limiter either. Everything else does: anonymous `GET`s, valid tokens, and requests with no token that the authorisation rules, which come later in the chain, are about to refuse.
- **A filter bean is also a servlet filter.** Spring Boot registers every `Filter` bean it finds with the web server, as an ordinary servlet filter in its own right, whether it's a `@Bean` like this one or a `@Component`. So a request passes this filter twice: inside Spring Security's chain, which runs first, and again afterwards. That's harmless here, thanks to `OncePerRequestFilter`: the first pass marks the request, and the second sees the mark and does nothing. Step 15's `eachCallerHasTheirOwnLimit` proves that the pass that counts is the one that knows who's calling. The thing to avoid is a rate-limit filter that's **only** a servlet filter, not added to the security chain: then it would depend on where Boot happened to put it. (If you'd rather Boot didn't register it a second time at all, a `FilterRegistrationBean` with `setEnabled(false)` switches that off.)

### 7. A `429` as a `ProblemDetail`

Add a handler to `web/ApiExceptionHandler.java`, after the `OptimisticLockingFailureException` one (trimmed):

```java
    /** From RateLimitFilter: the caller's bucket is empty. */
    @ExceptionHandler(TooManyRequestsException.class)
    ResponseEntity<ProblemDetail> handle(TooManyRequestsException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage()));
    }
```

It returns a `ResponseEntity` rather than a bare `ProblemDetail`, because it needs a header as well as a body. `Retry-After` can be a number of seconds or a date ([RFC 9110](https://www.rfc-editor.org/rfc/rfc9110#section-10.2.3)); seconds are simpler for everyone.

### 8. Security headers

At the end of the chain's configuration, after `addFilterAfter`, add the two headers, with an import for `org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy`:

```java
                // Spring Security already sends nosniff, X-Frame-Options: DENY and no-store caching
                // (and HSTS over HTTPS). Add: no other site may frame us, and don't leak our URLs
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER)));
```

`.headers(...)` **adds to** Spring Security's defaults; it doesn't replace them, so every response now carries both. The order of the calls on `http` doesn't decide the order of the filters: Spring Security puts its own in a fixed order, and `addFilterAfter` places ours relative to one of them.

### 9. Try the rate limit

Restart the API. One request first, to see the headers:

```bash
curl -i http://localhost:8080/venues
```

The headers end `X-Frame-Options: DENY`, `Content-Security-Policy: frame-ancestors 'none'`, `Referrer-Policy: no-referrer` and `Content-Type: application/json`: Spring Security's defaults, and this guide's two.

Now a burst. A loop of curl commands one after another is too slow on most machines: each curl takes long enough to start that 5 tokens a second keep up. So send 30 at the same moment, in Git Bash. `xargs -P 30` runs 30 curls in parallel, each printing only its status code, and `sort | uniq -c` counts them:

```bash
seq 30 | xargs -P 30 -I{} curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/venues | sort | uniq -c
```

On the build machine:

```
     27 200
      3 429
```

Your numbers will differ. The 30 curls don't really start at exactly the same moment, and the bucket refills by one token every fifth of a second while they start. More than 20 succeed, but not all 30. Every request came from the same address with no token, so they shared one bucket, `ip:127.0.0.1` (or `ip:0:0:0:0:0:0:0:1`, if curl used IPv6). One of those `429`s, in full:

```
HTTP/1.1 429
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Retry-After: 1
X-Content-Type-Options: nosniff
X-XSS-Protection: 0
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Expires: 0
X-Frame-Options: DENY
Content-Security-Policy: frame-ancestors 'none'
Referrer-Policy: no-referrer
Content-Type: application/problem+json

{"detail":"Too many requests. Try again in 1 seconds","instance":"/venues","status":429,"title":"Too Many Requests"}
```

A `ProblemDetail`, with `Retry-After: 1`, and every security header. At 5 tokens a second, the next token is never more than a fifth of a second away, so `Retry-After` is always `1` with these settings. Wait a few seconds and `curl localhost:8080/venues` works again.

**Behind a load balancer**, `getRemoteAddr()` is the load balancer's address, not the caller's, and every anonymous caller would share one bucket. The real address arrives in an `X-Forwarded-For` header, which Spring Boot can apply for you (`server.forward-headers-strategy`). Only trust that header from your own proxy: anyone can send one, with any address in it.

### 10. A generic `500`

The last gap in the error format. In `web/ApiExceptionHandler.java`, add a logger, with imports for `org.slf4j.Logger` and `org.slf4j.LoggerFactory`:

```java
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
```

And a handler for everything else, after the `429` one (trimmed):

```java
    /**
     * Anything else is a bug on our side. Log the details for us; tell the client nothing
     * about our code, database or libraries (no message, no stack trace).
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handle(Exception ex) {
        log.error("Unexpected error", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong on our side. Please try again later.");
    }
```

- **`Exception.class`** matches every exception, but Spring always picks the **most specific** handler for the exception it has. A `NotFoundException` still gets its `404`, a validation failure still gets its `400` from `ResponseEntityExceptionHandler`, and an `AccessDeniedException` still gets its `403`. Only what nothing else claims ends up here.
- **The response** names nothing: no exception class, no message, no stack trace. Its `title` is the standard `"Internal Server Error"`, and the detail tells the caller it wasn't their fault, so there's no point changing the request.
- **The log** gets everything. Passing `ex` as the last argument to `log.error` writes the full stack trace, so whoever is on call can find the cause:

  ```
  ERROR … c.e.tickets.web.ApiExceptionHandler      : Unexpected error
  ```

  followed by the exception and its stack trace. Real systems also add a **correlation id** to the log line and the response, so a customer's support ticket can be matched to the right log entry.

With this handler, the promise from guide 02 is kept. **Every error the API answers is now a `ProblemDetail`**: validation failures and bad JSON (02), unknown URLs and query parameters that can't be converted (01 and 03), `401` and `403` from Spring Security (05), `404` and `409` from the services (06), `412`, `422` and `428` (07), and now `429` and `500`. Guide 02's missing-`@Valid` example, which got Boot's `{"timestamp",…,"error":"Internal Server Error"}`, would now get this handler's `500`.

One kind of failure still can't reach it: an exception thrown inside a **servlet filter** that doesn't hand it over, as `RateLimitFilter` and `SecurityProblemHandler` do. A bug in a filter happens before Spring MVC, so `@RestControllerAdvice` never sees it, and Spring Boot's `/error` answers in its own format. That's still safe: `server.error.include-message` and `server.error.include-stacktrace` are `never` by default. Leave them that way in production.

### 11. Size limits

API4 again. Most of the limits were already in place:

| Limit | Where | Guide |
| --- | --- | --- |
| A page holds at most **100** rows. `?size=500` is quietly reduced to 100, and that includes `GET /bookings` | `spring.data.web.pageable.max-page-size=100` | 03 |
| Text fields have a maximum length: an event's title 200 characters, a description 2000, a ticket type's name 50 | `@Size` on the request records | 02 |
| A booking is for at most **10** tickets | `@Max(10)` on `quantity` | 06 |
| An `Idempotency-Key` is at most **100** characters | Checked in `IdempotencyService` | 07 |
| A request's headers are at most **8 KB** in total | Tomcat, through Spring Boot's `server.max-http-request-header-size` (8 KB is the default) | Nothing to do |
| **The request body** | **Not limited** | |

The last row is the gap. `@Size` is checked **after** the JSON has been read, so a 50 MB description has already been received and parsed before it's rejected. Tomcat's `server.tomcat.max-http-form-post-size` only covers HTML form posts, not JSON. The usual fix is a limit in front of the API, in the reverse proxy or gateway (nginx's `client_max_body_size`, for example), or a small filter that refuses a large `Content-Length` with `413 Content Too Large`. This guide leaves it there, to stay focused.

### 12. Swagger UI after hardening

Open `http://localhost:8080/swagger-ui.html`. It still works: `/swagger-ui/index.html` and `/v3/api-docs` answer `200`, now with the new headers. None of this guide's changes gets in its way:

- **CORS** doesn't apply. Swagger UI is served by the API, so it's the same origin.
- **`frame-ancestors 'none'`** stops other pages putting Swagger UI in a frame. Swagger UI doesn't use one: its sign-in opens in a separate window (guide 06).
- **The rate limit** is per caller, and 20 is comfortably above the half-dozen requests Swagger UI makes as it loads. Click **Try it out** very fast and you can get a `429` there too.
- **A stricter Content Security Policy** would break it. That's why step 8 stopped at `frame-ancestors`.

(The headers and status codes were checked with curl against the running API. The page's behaviour in a browser with them is Swagger UI's standard behaviour, and depends on its version.)

Guide 04's sidebar asked whether the documentation should be served **in production** at all, and said this guide would come back to it. The answer is a decision, not a setting:

- **A public API** serves its documentation: it's how other developers find out how to use it. Keep it, as this series does.
- **An internal API** is better off without it in production. The document is a complete map of every endpoint and field, and a map helps an attacker. Switch both off for production only, with `springdoc.api-docs.enabled=false` and `springdoc.swagger-ui.enabled=false` in a production profile, and publish the exported document (guide 04 step 10) somewhere only your own teams can read.

Either way, hiding the documentation hides nothing that matters: every endpoint must be safe for someone who has guessed its URL.

### 13. The OWASP API Security Top 10, as a checklist

The [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x11-t10/) is the list of serious flaws most often found in real APIs. Here's where this series deals with each one, or why it doesn't apply:

| # | Risk | In this series |
| --- | --- | --- |
| **API1** | Broken Object Level Authorisation (BOLA) | **Guide 06.** Every booking query is scoped to the token's `sub`, and someone else's booking is a `404`, the same as one that doesn't exist |
| **API2** | Broken Authentication | **Guide 05.** The API checks each token's signature, issuer, audience and expiry. No passwords reach the API at all |
| **API3** | Broken Object Property Level Authorisation | **Guides 01 and 06.** Response records since guide 01, so no entity field leaks out by accident. Request records, so a client can't set a price, a status or an owner (mass assignment) |
| **API4** | Unrestricted Resource Consumption | **This guide**: rate limiting, and step 11's size limits (page size, field sizes, quantity). The request body size is the gap |
| **API5** | Broken Function Level Authorisation | **Guides 05 and 06.** A scope per kind of caller (`events:write`, `bookings`, `tickets:check-in`) on every endpoint, with a test that a customer can't check themselves in |
| **API6** | Unrestricted Access to Sensitive Business Flows | **Partly.** Buying tickets is the sensitive flow: a bot could buy every front-row seat to resell. The rate limit, `@Max(10)` per booking and idempotency keys (07) help. A real ticket seller adds a limit per customer per event, a queue for big on-sales, and bot detection such as a CAPTCHA |
| **API7** | Server-Side Request Forgery (SSRF) | **Doesn't apply.** The API never fetches a URL that a caller supplies. The only URL it calls is the auth-server's, from its own configuration |
| **API8** | Security Misconfiguration | **This guide**: security headers, a CORS allow-list rather than `*`, a generic `500`. Development secrets are marked as such in every guide (05, 06), and step 12 covers the documentation in production. HTTPS is out of scope (see the README) |
| **API9** | Improper Inventory Management | **Guide 04.** The OpenAPI document lists every endpoint, and its tests notice when it changes. Versioning, the other half of this risk, is out of scope here: see the README |
| **API10** | Unsafe Consumption of APIs | **Guide 09.** The scanner is a client of this API, and must treat what comes back as data to check, not something to trust |

The table is a checklist, not a certificate. Each row says where a defence is, and a later change can still undo it: a new endpoint that forgets the owner check, a new field on a response record. That's what the tests from each guide are for.

### 14. Update `requests.http`

Add the preflight to the end of `requests.http`:

```http
### ---------- Hardening (guide 08) ----------
### The CORS preflight a browser on http://localhost:5173 sends before POST /bookings
OPTIONS http://localhost:8080/bookings
Origin: http://localhost:5173
Access-Control-Request-Method: POST
Access-Control-Request-Headers: authorization,content-type,idempotency-key
```

### 15. Tests

**First, the tests this guide broke.** Run the API's tests, and many of the old ones fail with `429`, such as `expected: 200 but was: 429`. The tests share one Spring context, so they share one rate limiter, and they send requests far faster than any person could: as the same few callers (`organiser()`, `alice()` and anonymous `127.0.0.1`), more than 20 at a time.

Tests aren't what the limit is for, so turn it right up for them. In `src/test/java/com/example/tickets/TicketsIntegrationTest.java`, the `@SpringBootTest` gets a second property:

```java
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tickets-test;DB_CLOSE_DELAY=-1",
        // Tests send requests much faster than people do: don't let rate limiting get in the way
        "tickets.rate-limit.capacity=100000"
})
```

Run them again, and all 74 pass.

**The rate limit's own tests** need the opposite: a small limit. They can't use `@TicketsIntegrationTest`, whose limit is now 100,000, so they spell out their own `@SpringBootTest`. Different properties mean a second Spring context, with its own rate limiter. It uses the same in-memory database, which is fine: these tests only read. Create `src/test/java/com/example/tickets/security/RateLimitApiTest.java`:

```java
package com.example.tickets.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static com.example.tickets.TestTokens.customer;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Not @TicketsIntegrationTest: that one turns rate limiting right up. Here each caller
 * may make 3 requests, and gets 1 more each second.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tickets-test;DB_CLOSE_DELAY=-1",
        "tickets.rate-limit.capacity=3",
        "tickets.rate-limit.refill-per-second=1"
})
@AutoConfigureMockMvc
class RateLimitApiTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void theFourthRequestInASecondIs429WithRetryAfter() {
        for (int i = 0; i < 3; i++) {
            assertThat(mvc.get().uri("/bookings").with(customer("greedy"))).hasStatusOk();
        }

        var response = mvc.get().uri("/bookings").with(customer("greedy")).exchange();

        assertThat(response).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response).headers().hasValue("Retry-After", "1");
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"title": "Too Many Requests", "status": 429, "detail": "Too many requests. Try again in 1 seconds"}
                """);
    }

    @Test
    void eachCallerHasTheirOwnLimit() {
        for (int i = 0; i < 4; i++) {
            mvc.get().uri("/bookings").with(customer("busy")).exchange();
        }

        assertThat(mvc.get().uri("/bookings").with(customer("busy"))).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(mvc.get().uri("/bookings").with(customer("patient"))).hasStatusOk();
    }

    @Test
    void callersWithoutATokenAreLimitedByIpAddress() {
        for (int i = 0; i < 3; i++) {
            mvc.get().uri("/venues").with(request -> {
                request.setRemoteAddr("203.0.113.7");
                return request;
            }).exchange();
        }

        var sameAddress = mvc.get().uri("/venues").with(request -> {
            request.setRemoteAddr("203.0.113.7");
            return request;
        });
        var otherAddress = mvc.get().uri("/venues").with(request -> {
            request.setRemoteAddr("203.0.113.8");
            return request;
        });

        assertThat(sameAddress).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(otherAddress).hasStatusOk();
    }
}
```

- **A different customer in each test** (`greedy`, `busy`, `patient`) means each test starts with a full bucket, whatever order JUnit runs them in. It's the same idea as guide 06's own ticket type per test.
- **One request a second** is slow enough that nothing refills between the requests of a test. The fourth request in `theFourthRequestInASecondIs429WithRetryAfter` finds the bucket empty, or nearly, so `Retry-After` is `1`.
- **No `.exchange()`** on the first three requests of that test, on purpose: each `assertThat` sends its request once, which is all they need to do. Where a test makes more than one assertion on a response, it uses `.exchange()`, as usual.
- **`with(request -> …)`** is a `RequestPostProcessor` written as a lambda: it can change the mock request before it's sent. `jwt()` from guide 05 is one too. Here it sets the address the request comes from, so the anonymous test has its own address. `203.0.113.x` is a range reserved for documentation, so it can't be anybody real.
- **`eachCallerHasTheirOwnLimit`** proves more than its name says. If the rate limiter counted before the token was checked (step 6), `busy` and `patient` would be the same anonymous caller, and `patient` would get the `429`.

**CORS, headers and the `500`** go in `src/test/java/com/example/tickets/security/HardeningApiTest.java`:

```java
package com.example.tickets.security;

import com.example.tickets.TicketsIntegrationTest;
import io.swagger.v3.oas.annotations.Hidden;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.example.tickets.TestTokens.organiser;
import static org.assertj.core.api.Assertions.assertThat;

@TicketsIntegrationTest
@Import(HardeningApiTest.Broken.class)
class HardeningApiTest {

    static final String FRONT_END = "http://localhost:5173";

    @Autowired
    MockMvcTester mvc;

    @Test
    void theFrontEndMayCallTheApiFromTheBrowser() {
        // The preflight a browser sends before a cross-origin request with a token
        var preflight = mvc.options().uri("/bookings")
                .header("Origin", FRONT_END)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key")
                .exchange();

        assertThat(preflight).hasStatusOk();
        assertThat(preflight).headers()
                .hasValue("Access-Control-Allow-Origin", FRONT_END)
                .hasValue("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE")
                .hasValue("Access-Control-Allow-Headers", "authorization, content-type, idempotency-key")
                .hasValue("Access-Control-Max-Age", "3600");
    }

    @Test
    void theFrontEndCanReadTheETag() {
        var response = mvc.get().uri("/events/2").header("Origin", FRONT_END).exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).headers()
                .hasValue("Access-Control-Allow-Origin", FRONT_END)
                .hasHeaderSatisfying("Access-Control-Expose-Headers",
                        values -> assertThat(values.getFirst()).contains("ETag", "Location", "Retry-After"));
    }

    @Test
    void otherSitesMayNot() {
        var preflight = mvc.options().uri("/bookings")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST");

        assertThat(preflight).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(preflight).headers().doesNotContainHeader("Access-Control-Allow-Origin");
    }

    @Test
    void sendsSecurityHeaders() {
        var response = mvc.get().uri("/events/2").exchange();

        assertThat(response).headers()
                .hasValue("X-Content-Type-Options", "nosniff")
                .hasValue("X-Frame-Options", "DENY")
                .hasValue("Content-Security-Policy", "frame-ancestors 'none'")
                .hasValue("Referrer-Policy", "no-referrer");
    }

    @Test
    void aBugIsAGeneric500ThatGivesNothingAway() {
        var response = mvc.get().uri("/test/broken").with(organiser()).exchange();

        assertThat(response).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isStrictlyEqualTo("""
                {"title": "Internal Server Error", "status": 500, "instance": "/test/broken",
                 "detail": "Something went wrong on our side. Please try again later."}
                """);
        assertThat(response).bodyText().doesNotContain("jdbc", "Exception", "at com.example");
    }

    /** A controller with a bug, only for this test. */
    @Hidden
    @RestController
    static class Broken {

        @GetMapping("/test/broken")
        String broken() {
            throw new IllegalStateException("Could not connect to jdbc:h2:file:./data/tickets as sa");
        }
    }
}
```

- **`mvc.options()`** sends an `OPTIONS` request. With `Origin` and `Access-Control-Request-Method`, it's a preflight, exactly as a browser would send it.
- **`theFrontEndCanReadTheETag`** sends a simple `GET` with an `Origin`, as a browser would without a preflight, and checks the answer lets the page read `ETag`. `GET /events/2` sends an `ETag` (guide 03), so it's the case that matters. Spring writes the exposed headers as one comma-separated value, so the test checks that value contains the names.
- **`otherSitesMayNot`** asserts without `.exchange()`, so the preflight is sent twice. Sending a preflight changes nothing, so that's fine.
- **`sendsSecurityHeaders`** doesn't check `Cache-Control`: `GET /events/2` sets its own (guide 03), so Spring Security leaves it alone.
- **`Broken`** is a controller with a bug, which exists only for this test. A class nested inside a test class isn't picked up by component scanning, so `@Import` brings it into this test's context. `@Hidden` keeps it out of the OpenAPI document. Its message is the kind that mustn't leak: a database URL and a username. The last assertion checks that no part of it, and no stack trace, made it into the body. Look at the test output: the `ERROR … Unexpected error` line and the stack trace are there, in the log, where they belong.
- `@TicketsIntegrationTest` means these five share the main test context, with the high rate limit.

Run all the API's tests: 82 pass.

---

## Your turn

### Exercise 1: the real website

The ticket seller's real website will be `https://tickets.example`. Allow it as well as the development server, and add a test to `HardeningApiTest` that a preflight from it is allowed. Which existing test proves you haven't allowed everyone?

<details>
<summary>Solution</summary>

In `corsConfigurationSource()`:

```java
        frontEnd.setAllowedOrigins(List.of("http://localhost:5173", "https://tickets.example"));
```

The test:

```java
@Test
void theRealWebsiteMayCallTheApiToo() {
    var preflight = mvc.options().uri("/bookings")
            .header("Origin", "https://tickets.example")
            .header("Access-Control-Request-Method", "POST")
            .exchange();

    assertThat(preflight).hasStatusOk();
    assertThat(preflight).headers().hasValue("Access-Control-Allow-Origin", "https://tickets.example");
}
```

`Access-Control-Allow-Origin` holds **one** origin: Spring sends back the one that asked, if it's on the list, which is why the answer says `Vary: Origin`. `otherSitesMayNot` still passes, so `https://evil.example` is still refused. In a real project the list would differ between environments, so this is the point where it would become a property.

</details>

### Exercise 2: do the arithmetic

With this guide's settings (20 tokens, 5 a second), and without running anything:

1. A script sends 25 requests at exactly the same moment, with alice's token. How many get a `200`, how many a `429`, and what is `Retry-After`?
2. How long after that until alice could burst 20 again?
3. If `refill-per-second` were `0.5`, what would `Retry-After` be for a caller with an empty bucket? And with `0.6` of a token left?
4. Why can `take` never return a `Retry-After` of `0` to a caller it refuses?

<details>
<summary>Solution</summary>

1. **20** `200`s and **5** `429`s. Each `429` has `Retry-After: 1`: the bucket has (almost) 0 tokens, and `ceil((1 - 0) / 5) = ceil(0.2) = 1`.
2. **4 seconds**: 20 tokens at 5 a second. Any less and the bucket isn't full.
3. Empty: `ceil(1 / 0.5) = 2` seconds. With `0.6` left: `ceil(0.4 / 0.5) = ceil(0.8) = 1` second.
4. `take` only refuses when `tokens` is less than 1, so `1 - tokens` is more than 0, and `Math.ceil` of a number more than 0 is at least 1.

</details>

### Exercise 3: preflights are free

Step 6 said a preflight never reaches the rate limiter. Prove it in `RateLimitApiTest`: from one IP address, send five preflights (more than the test's limit of 3), then check that an ordinary `GET /venues` from the same address still gets a `200`. Use an address no other test uses.

<details>
<summary>Solution</summary>

```java
@Test
void preflightsAreNotRateLimited() {
    for (int i = 0; i < 5; i++) {
        mvc.options().uri("/bookings")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .with(request -> {
                    request.setRemoteAddr("203.0.113.9");
                    return request;
                })
                .exchange();
    }

    assertThat(mvc.get().uri("/venues").with(request -> {
        request.setRemoteAddr("203.0.113.9");
        return request;
    })).hasStatusOk();
}
```

If the preflights used tokens, the bucket would have been empty after the third, and the `GET` would be a `429`. They don't, because `CorsFilter` answers a preflight and stops the chain before `RateLimitFilter`. That's what you want: the browser sends a preflight on the user's behalf, and with `Access-Control-Max-Age` it sends few of them anyway.

</details>

---

## Checkpoint

In the **api**:

```bash
./mvnw test
```

```
[INFO] Tests run: 82, Failures: 0, Errors: 0, Skipped: 0
```

(74 from guide 07, 3 in `RateLimitApiTest` and 5 in `HardeningApiTest`. With the tests from exercises 1 and 3, you'll have 84.) The **auth-server** hasn't changed: its 11 tests still pass.

New files in the api:

```
src/main/java/com/example/tickets/
├── security/
│   └── RateLimitFilter.java
└── web/
    └── TooManyRequestsException.java
src/test/java/com/example/tickets/
└── security/
    ├── HardeningApiTest.java
    └── RateLimitApiTest.java
```

Modified in the api: `SecurityConfig.java` (CORS, the rate-limit filter, security headers), `ApiExceptionHandler.java` (`429` and the generic `500`), `application.properties` (the rate limit), `requests.http`, `TicketsIntegrationTest.java` (a high rate limit for tests).

---

## Common errors

**Old tests fail with `429` after adding the filter**
In full, for the whole run:

```
[ERROR] Tests run: 82, Failures: 14, Errors: 1, Skipped: 0
```

with assertions such as `expected: 200 but was: 429` and `expected: 400 but was: 429`. The tests share one context, and so one rate limiter, and they're much faster than people. Add `tickets.rate-limit.capacity=100000` to `@TicketsIntegrationTest` (step 15).

**A curl loop never gets a `429`**
`for i in $(seq 1 25); do curl …; done` gave 25 `200`s on the build machine. Each curl, one after another, takes long enough to start that 5 tokens a second keep up. Send them in parallel with `xargs -P` (step 9), or lower `tickets.rate-limit.refill-per-second` while you experiment.

**A preflight gets `403` with the body `Invalid CORS request`**
The origin, method or one of the requested headers isn't allowed. Check the `Origin` is exactly `http://localhost:5173`: scheme, host and port, with no slash on the end. `http://127.0.0.1:5173` is a different origin. Check each name in `Access-Control-Request-Headers` is in `setAllowedHeaders`.

**The front end's request works, but `response.headers.get("ETag")` is `null`**
The header is on the wire but not exposed. Add it to `setExposedHeaders` (step 1). The same goes for `Location`, `Retry-After` and any other header of the API's own.

**`ERROR … c.e.tickets.web.ApiExceptionHandler : Unexpected error` in the log, with a stack trace**
That's the generic handler doing its job: there's a bug, and the details are in the log, not the response. When you see it in the test output for `aBugIsAGeneric500ThatGivesNothingAway`, the bug is `Broken`'s, on purpose. Anywhere else, read the stack trace.

**Every anonymous caller shares one limit in production**
The API is behind a proxy or load balancer, so `getRemoteAddr()` is always the proxy's address. Set up forwarded headers (step 9), and trust them only from your own proxy.

---

> **Sidebar: rate limiting in the real world.** `RateLimitFilter` is about 80 lines, which makes it a good way to see how a token bucket works, and a poor choice for production. Its buckets live in one process's memory: with three instances of the API behind a load balancer, each caller gets three times the limit, and a restart forgets everything. The usual answers, from least to most infrastructure: **[Bucket4j](https://github.com/bucket4j/bucket4j)** is a Java library with the same token-bucket idea, well tested, with bandwidth rules such as "20 a second and 1000 an hour", and it can keep its buckets in a **shared store** such as Redis, so every instance draws from the same bucket. Or the limit moves out of the application altogether, into the **API gateway** or reverse proxy in front of it: Spring Cloud Gateway, nginx's `limit_req`, or a cloud provider's API management service. Then it's applied before a request uses any of the API's threads, the same way for every service behind the gateway, and the API's code doesn't change. Whichever you choose, the contract with clients stays the same as here: `429` and `Retry-After`. Some APIs also tell clients their budget on **every** response, so they can slow down before they hit the limit; the IETF is standardising `RateLimit` and `RateLimit-Policy` headers for that.

---

## Recap

- **CORS** lets a page from another origin read the API's responses in the browser. Name the origins (`http://localhost:5173` here), the methods, the request headers, and the response headers the page may read (**`Access-Control-Expose-Headers`**, the one people forget).
- Requests with a token, JSON, `If-Match` or `Idempotency-Key`, or a `PUT`, `PATCH` or `DELETE`, get a **preflight** `OPTIONS` first. Spring Security's `CorsFilter` answers it before authentication, so it needs no token.
- **CORS isn't access control.** It protects users from other websites' scripts, in browsers. curl and servers ignore it, and tokens still decide who may do what.
- **Rate limiting** gives each caller a budget: the token's `sub`, or the IP address without one. Over it, the answer is **`429`** with **`Retry-After`**, as a `ProblemDetail`.
- A **token bucket** allows a **burst** (the capacity) and a **steady rate** (the refill), worked out lazily from the time since the last request.
- A filter's **place in the chain** decides what it knows: after `BearerTokenAuthenticationFilter`, it knows who's calling. Filters hand errors to the **`HandlerExceptionResolver`**, so `ApiExceptionHandler` writes every error.
- An in-memory limiter is **per instance** and forgets on restart. Real APIs use **Bucket4j** with a shared store, or the **API gateway**.
- Spring Security already sends **`nosniff`**, **`X-Frame-Options: DENY`**, **no-store** caching and, over HTTPS, **HSTS**. This API adds **`Content-Security-Policy: frame-ancestors 'none'`** and **`Referrer-Policy: no-referrer`**.
- An unexpected exception is a **generic `500`**: the details go to the **log**, never to the caller. Every error the API answers is now a `ProblemDetail`.
- **Size limits**: page size, field sizes, quantity and header size are capped. Request bodies aren't, and the fix belongs in front of the API.
- The **OWASP API Security Top 10** makes a checklist for any API. A test for each defence keeps it there.

### Interview angle

<details>
<summary>"What is CORS? Does it protect your API?"</summary>

Cross-Origin Resource Sharing. Browsers follow the same-origin policy: a page's JavaScript can send requests to another origin but can't read the responses unless that server allows the page's origin with CORS headers. For anything beyond a simple request, the browser first sends an `OPTIONS` preflight asking whether the origin, method and headers are allowed. It doesn't protect the API: curl, scripts and other servers ignore CORS entirely. It protects users, by stopping other websites from using their browser to read your API's responses. Authentication and authorisation still have to be done with tokens. Keep the allowed origins to a list, never `*` with credentials.

</details>

<details>
<summary>"A front-end developer says the ETag header is missing, but you can see it in the network tab. What's wrong?"</summary>

It's a cross-origin response, and the browser only lets JavaScript read a few safelisted response headers, such as `Content-Type` and `Cache-Control`. Any others must be listed in `Access-Control-Expose-Headers`. Add `ETag`, and `Location`, `Retry-After` and any custom headers the front end needs, to the CORS configuration's exposed headers.

</details>

<details>
<summary>"How would you implement rate limiting for an API?"</summary>

Decide who the limit is for: per authenticated client or user, falling back to the IP address for anonymous calls, never one global limit. Use a token bucket: each caller has a capacity for bursts and a steady refill rate. Over the limit, answer `429 Too Many Requests` with `Retry-After`, before doing any real work. For one instance, an in-memory map works. For several, the counts must be shared, in Redis, for example with Bucket4j, or the limit moves to the API gateway, which protects every service the same way. Behind a proxy, take the client's address from forwarded headers, but only trust them from your own proxy.

</details>

<details>
<summary>"Your rate limiter keeps its counts in memory, and the API runs on five instances. What's the problem?"</summary>

Each instance has its own counts, so a client gets up to five times the limit, depending on how the load balancer spreads its requests, and every restart resets everyone. The counts also grow with the number of callers unless old entries are removed. The fix is a shared store, such as Redis, with an atomic "take a token" operation, or rate limiting in the gateway in front of all five instances.

</details>

<details>
<summary>"What should an API return when something unexpected goes wrong?"</summary>

A `500` in the same error format as every other error, such as an RFC 9457 `ProblemDetail`, with a generic message: no exception message, class name, SQL or stack trace, because those tell an attacker about your code, database and libraries. Log the full details on the server, ideally with a correlation id that's also in the response, so support can find the log entry. More specific exceptions keep their own handlers and status codes; the catch-all is only for real bugs.

</details>

<details>
<summary>"Which security headers does a JSON API need?"</summary>

Fewer than a website, because it serves no HTML, but a few still matter: `X-Content-Type-Options: nosniff` so a browser never treats JSON as HTML or script, `Cache-Control: no-store` for anything private, `Strict-Transport-Security` so browsers only use HTTPS, and `Content-Security-Policy: frame-ancestors 'none'` (or `X-Frame-Options: DENY`) against clickjacking. A strict `default-src 'none'` is ideal for pure JSON, but if the same application serves documentation pages it would break them. Spring Security sends most of these by default.

</details>

<details>
<summary>"Walk me through the OWASP API Security Top 10."</summary>

The 2023 list: API1 broken object level authorisation, checking the caller may access this particular object; API2 broken authentication; API3 broken object property level authorisation, which includes mass assignment and leaking fields; API4 unrestricted resource consumption, which is rate limits and size limits; API5 broken function level authorisation, such as a customer reaching an admin endpoint; API6 unrestricted access to sensitive business flows, such as bots buying all the tickets; API7 server-side request forgery; API8 security misconfiguration; API9 improper inventory management, meaning forgotten old versions and undocumented endpoints; API10 unsafe consumption of other APIs. For each, name where your API defends against it, and the test that proves it.

</details>

### Further reading

- [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x11-t10/), especially [API4: Unrestricted Resource Consumption](https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/) and [API8: Security Misconfiguration](https://owasp.org/API-Security/editions/2023/en/0xa8-security-misconfiguration/)
- [OWASP REST Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html), including its list of security headers for APIs
- [MDN: Cross-Origin Resource Sharing (CORS)](https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS), on simple requests, preflights and exposed headers
- [Spring Security reference: CORS](https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html) and [security HTTP response headers](https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html)
- [RFC 6585 section 4: `429 Too Many Requests`](https://www.rfc-editor.org/rfc/rfc6585#section-4) and [RFC 9110 section 10.2.3: `Retry-After`](https://www.rfc-editor.org/rfc/rfc9110#section-10.2.3)
- [Bucket4j](https://github.com/bucket4j/bucket4j), a token-bucket rate limiter for Java
- [RateLimit header fields for HTTP](https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/), the IETF draft

**Simplification to notice:** every caller of this API so far has been curl, Swagger UI or a test. Nobody has written a program that calls it: one that gets its own token and gets a new one when it expires, that waits as long as `Retry-After` says when it meets a `429`, and that makes sense of a `409` from a booking that's already checked in.

**Next:** [09: A client service](09-a-client-service.md). The scanner as a Spring application of its own: tokens with client credentials, calls to the API with `RestClient`, and what to do with `401`, `409` and `429`.
