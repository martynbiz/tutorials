# 09: A client service

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](README.md) for version notes.

## What you'll learn

- How to build a **command-line Spring application**: no web server and no port, booking ids as **arguments**, and an **exit code** that a script can check
- How a service gets its own access token with **client credentials**, through **Spring Security's OAuth2 client**, instead of guide 05's curl
- What the framework does for you: it **asks for the token**, **keeps it**, **sends it** with every request, and **gets a new one** before the old one expires
- How to call an API with **`RestClient`**, and then with an **HTTP interface client**: a Java interface that Spring implements for you
- How a client should handle **`401`**, **`404`**, **`409`** and **`429`**: which errors are worth another try, and which never are
- **Retrying with backoff**, waiting as long as **`Retry-After`** says, with a cap, and a limit on attempts
- How to test an HTTP client: **`MockRestServiceServer`** for the rules, and a pretend server for the token

By the end, the scanner at the venue door is a Spring application of its own. You give it booking ids, as if their tickets had just been scanned, and for each one it prints whether to let them in. It logs in to the auth-server as itself, reuses its token until it's nearly expired, waits politely when the API says `429`, and tells the door staff why a cancelled booking is refused. It's the last piece of the series: every part of the API now has a caller.

---

## Prerequisites for this guide

You need both projects as they were at the end of [08: Hardening](08-hardening.md): the `api` with all 82 tests passing, and the `auth-server` with all 11. Neither changes in this guide.

For trying the scanner out (step 9) you also need:

- **The auth-server running** (port 9000), then **the API** (port 8080), as in guide 06.
- **Alice signed in through Swagger UI**, as in guide 06, to make two bookings for the scanner to check in.

The tests (step 10) need neither of them running.

---

## Concepts

### A client of your own

Every caller of the API so far has been curl, Swagger UI or a test. Each was a person typing, or a test written for one request. A **program** that calls an API has to do, by itself, everything you did by hand:

- **Get a token**, and get another when it expires. In guide 05 you copied `access_token` out of a JSON response and pasted it into the next command. Five minutes later you did it again.
- **Make the call**, and turn the JSON that comes back into Java objects.
- **Make sense of errors.** A person reads a `409` and understands. A program needs to be told what each status means for it, and whether trying again could help.

The scanner at the door is that program. Guide 05 registered it with the auth-server (client `scanner`, scope `tickets:check-in`), and guide 06 gave it an endpoint: `POST /bookings/{id}/check-in`. In real life it would be an app on a handheld device that reads a QR code. Here, it's a Spring application you run with booking ids, which stand in for the QR codes.

### A command-line application

The API and the auth-server are **web applications**: they start a server, listen on a port, and run until you stop them. The scanner isn't. Nothing calls it. It starts, does one job, and exits, like `git` or `curl`.

Spring Boot can run that way too:

- **`spring.main.web-application-type=none`** means no web server, so no port.
- **An `ApplicationRunner`** is a bean whose `run` method Spring calls once the application has started. The command-line arguments come with it, as `ApplicationArguments`.
- When `run` returns, there's nothing left to do, and the application **exits**.

A command-line program also says how it went with an **exit code**: `0` for success, anything else for failure. Scripts check it (`if java -jar … ; then …`), and so do schedulers and build servers. The scanner uses three:

| Exit code | Means |
|---|---|
| `0` | Every booking was checked in: let them all in |
| `1` | At least one was refused, or something went wrong |
| `2` | No booking ids were given: the scanner prints how to use it |

In Spring Boot, a bean that implements **`ExitCodeGenerator`** decides the code, and `SpringApplication.exit(...)` asks it.

### Client credentials, done for you

Guide 05 got the scanner's token with curl:

```bash
curl -u scanner:scanner-secret -d grant_type=client_credentials -d scope=tickets:check-in http://localhost:9000/oauth2/token
```

Spring Security's **OAuth2 client** does the same request, and the rest of the job around it:

| By hand (guide 05) | Spring Security's OAuth2 client |
|---|---|
| You type the client id, secret, scope and token URL into curl | A **client registration**, from `application.properties` |
| You `POST` to `/oauth2/token`, with the id and secret as Basic authentication | The **`OAuth2AuthorizedClientManager`** sends exactly that request |
| You copy `access_token` into a variable | The token is kept in the **`OAuth2AuthorizedClientService`** |
| You paste `Authorization: Bearer …` into every request | An **interceptor** on the `RestClient` adds the header to every request |
| After five minutes, a `401`, and you start again | The manager notices the token is **about to expire**, and asks for a new one first |
| If the API rejects a token anyway, you start again | The interceptor **forgets** a token the API answered `401` to, so the next request gets a new one |

**When is a token "about to expire"?** The token response says `"expires_in": 300`. The manager reuses the token until it has less than **60 seconds** left, then asks for another. It's the same 60 seconds of clock skew the API allowed in guide 05's exercise 3, from the other side: the client renews early because its clock and the API's might not agree. So a scanner that checks in a thousand people in four minutes asks the auth-server for **one** token, not a thousand. That matters: getting a token is a round trip to another server, and the auth-server would soon become the bottleneck, or rate-limit you itself.

Put together, the scanner looks like this:

```
  ScanRunner            the booking ids from the command line; one line of output each
      │
      ▼
  CheckInService        the rules for trying again: 401 once, 429 after waiting
      │
      ▼
  TicketsApi            a Java interface: checkIn(id). Spring writes the class
      │
      ▼
  RestClient ──▶ OAuth2 interceptor ──▶ POST /bookings/{id}/check-in     Tickets API (:8080)
                       │                Authorization: Bearer eyJ…
                       │ "a token for 'scanner', please"
                       ▼
             OAuth2AuthorizedClientManager ──▶ POST /oauth2/token        auth-server (:9000)
             (keeps the token in the            only when it has no token,
              OAuth2AuthorizedClientService)    or it's nearly expired
```

You'll write `ScanRunner`, `CheckInService`, `TicketsApi` and some configuration. Spring Boot and Spring Security provide everything below the interface.

### `RestClient` and HTTP interfaces

**`RestClient`** is Spring's HTTP client for code like this, which waits for each answer. (`RestTemplate` is the older one, and `WebClient` is for reactive code that doesn't wait.) It reads like a sentence:

```java
restClient.post().uri("/bookings/{id}/check-in", id).retrieve().body(CheckedInBooking.class)
```

Send a `POST` to this URL, get the response, and turn its JSON body into a `CheckedInBooking`. A `4xx` response is thrown as an **`HttpClientErrorException`**, with a subclass for each common status: `HttpClientErrorException.NotFound`, `.Conflict`, `.TooManyRequests`, `.Unauthorized` and so on. The exception carries the status, the headers and the body.

An **HTTP interface client** goes one step further. You describe the API as a Java interface, one annotated method per endpoint:

```java
@PostExchange("/bookings/{id}/check-in")
CheckedInBooking checkIn(@PathVariable long id);
```

and Spring writes the class that implements it, using a `RestClient` underneath. Your code calls `ticketsApi.checkIn(2)`, a plain Java method, and never builds a URL. The annotations are the mirror image of the API's controllers: `@PostExchange` here, `@PostMapping` there, and the same `@PathVariable`. The exceptions are the same `HttpClientErrorException`s, because the same `RestClient` makes the call.

### Which errors are worth another try?

A client that retries everything makes things worse: retrying a `404` gets another `404`, and retrying a request that failed because the server is overloaded overloads it more. A client that retries nothing gives up on problems that would have fixed themselves. So each answer needs a decision:

| The API answers | It means, for the scanner | The scanner |
|---|---|---|
| `200` | Checked in (or already was: guide 06 made a repeat check-in a `200`) | Lets them in |
| `401 Unauthorized` | The token has expired, or the API no longer accepts it | Gets a new token, and tries **once** more |
| `403 Forbidden` | The token is genuine, but has no `tickets:check-in` scope: a configuration bug | Refuses, and says so |
| `404 Not Found` | No such booking: a forged or mistyped ticket | Refuses |
| `409 Conflict` | The booking was cancelled | Refuses. Trying again won't un-cancel it |
| `429 Too Many Requests` | Slow down (guide 08) | Waits as long as `Retry-After` says, and tries again, up to four attempts in all |
| No answer at all | The API or the auth-server isn't running, or the network is down | Reports an error |

**Retrying is only safe because check-in is.** Guide 06 designed a repeat check-in to answer `200` and change nothing, so if the scanner can't tell whether its first try worked, trying again does no harm. Retrying `POST /bookings` without guide 07's `Idempotency-Key` would book twice. Before you retry anything, ask what happens if the first attempt did, in fact, work.

**Backoff.** When a `429` has no `Retry-After` (or one the scanner can't read), it waits longer each time: 1 second, then 2, then 4. That's **exponential backoff**: if the server is struggling, every client backing off gives it room to recover. And whatever `Retry-After` says, the scanner waits **30 seconds at most**. A queue at the door can't wait an hour, and a server that says "come back in an hour" has, for this purpose, said no.

---

## Steps

### 1. Generate the scanner project

In IntelliJ: **File → New → Project → Spring Boot** (or use [start.spring.io](https://start.spring.io) and unzip the result):

| Setting | Value |
|---|---|
| Location | `tutorials/workspace/rest-api/scanner` |
| Language / Type | Java, Maven |
| Group | `com.example` |
| Artifact | `scanner` |
| Package name | `com.example.scanner` |
| JDK / Java | 25 |
| Spring Boot | 4.1.2 |
| Dependencies | **OAuth2 Client**, **HTTP Client** (`RestClient`) |

The main class is `ScannerApplication`. Delete the generated test class under `src/test`, as in guide 01: step 10 replaces it with real tests.

**No Spring Web.** The scanner serves nothing, so it doesn't need a web server. It needs an HTTP **client**, and in Spring Boot 4 that's a starter of its own: without **HTTP Client** there's no `RestClient.Builder` for Spring to give you. The dependencies in `pom.xml` (the rest is as generated):

```xml
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-restclient</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security-oauth2-client</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-restclient-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security-oauth2-client-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
```

The two `-test` starters come with the other two. `spring-boot-starter-restclient-test` brings `MockRestServiceServer`, which step 10 uses.

### 2. Configure the scanner

Replace `src/main/resources/application.properties`:

```properties
spring.application.name=scanner

# A command-line tool, not a web app: no web server, no port
spring.main.web-application-type=none
spring.main.banner-mode=off
logging.level.root=warn

# Where the tickets API is
tickets.api.base-url=http://localhost:8080

# The scanner logs in to the authorisation server as itself (client credentials).
# DEVELOPMENT ONLY: a real secret comes from the environment or a vault, never from a committed file.
spring.security.oauth2.client.registration.scanner.provider=tickets
spring.security.oauth2.client.registration.scanner.client-id=scanner
spring.security.oauth2.client.registration.scanner.client-secret=scanner-secret
spring.security.oauth2.client.registration.scanner.authorization-grant-type=client_credentials
spring.security.oauth2.client.registration.scanner.scope=tickets:check-in
spring.security.oauth2.client.provider.tickets.token-uri=http://localhost:9000/oauth2/token
```

The first block makes it a quiet command-line tool: no web server, no Spring Boot banner, and only warnings in the log, so the output is just the scanner's own lines. `tickets.api.base-url` is a property of our own: step 4 reads it.

The OAuth2 properties come in two parts, and line up with guide 05's curl command:

- **A registration**, here called `scanner`: who the scanner is and what it wants. `client-id` and `client-secret` are curl's `-u scanner:scanner-secret`, `authorization-grant-type` is `-d grant_type=client_credentials`, and `scope` is `-d scope=tickets:check-in`. Forget the scope and you get guide 05's trap: a token with no scopes, and a `403` from the API.
- **A provider**, here called `tickets`: the authorisation server, which the registration points to with `provider=tickets`. For client credentials, all the scanner needs from it is the `token-uri`.

The registration id, `scanner`, is the name the rest of the code uses to ask for a token. It happens to match the client id, but it doesn't have to: it's only a key in this file.

**`token-uri`, not `issuer-uri`.** The API's configuration in guide 05 used `issuer-uri`, and a client registration could too: Spring would then look up the token endpoint in the auth-server's metadata. But it does that **at start-up**, so the scanner couldn't even start without the auth-server. With `token-uri`, it only contacts the auth-server when it needs a token.

From these properties, Spring Boot creates a **`ClientRegistrationRepository`** (the registrations) and an **`OAuth2AuthorizedClientService`** (where tokens are kept), even in an application without a web server.

### 3. The token manager

The piece that asks for tokens, and decides when a new one is needed, is the **`OAuth2AuthorizedClientManager`**. Spring Boot only creates one for web applications, so the scanner declares its own. Create `ClientConfig.java`, with this bean (step 7 shows the whole class):

```java
    /**
     * Gets client-credentials tokens and keeps them until they're about to expire.
     * (Spring Boot sets up the registration and the token store from application.properties;
     * this "manager" is only made for you in web applications, so we make it here.)
     */
    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
                                                          OAuth2AuthorizedClientService tokens) {
        return new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, tokens);
    }
```

- **`AuthorizedClientServiceOAuth2AuthorizedClientManager`** is the manager for code that runs outside a web request, such as a command-line tool or a background job. (The kind Spring Boot makes for web applications works within each HTTP request, for the user making it.)
- **It handles client credentials** without being told: that's its default. Asked for a token for `scanner`, it looks in the `OAuth2AuthorizedClientService` first. If there's one with more than 60 seconds left, that's the answer. If not, it sends the token request, stores the result, and returns it.
- **"Authorized client"** is Spring Security's name for "a registration, plus the token it was given". You'll see it in class names throughout.

### 4. A `RestClient` that sends the token

Add a second bean to `ClientConfig`:

```java
    /** Calls the tickets API with the scanner's token in an Authorization: Bearer header. */
    @Bean
    RestClient ticketsRestClient(RestClient.Builder builder, OAuth2AuthorizedClientManager manager,
                                 OAuth2AuthorizedClientService tokens,
                                 @Value("${tickets.api.base-url}") String baseUrl) {
        OAuth2ClientHttpRequestInterceptor oauth2 = new OAuth2ClientHttpRequestInterceptor(manager);
        oauth2.setClientRegistrationIdResolver(request -> "scanner");
        // On a 401 from the API, forget the token, so the next request gets a new one
        oauth2.setAuthorizationFailureHandler(OAuth2ClientHttpRequestInterceptor.authorizationFailureHandler(tokens));
        return builder
                .baseUrl(baseUrl)
                .requestInterceptor(oauth2)
                .build();
    }
```

- **`RestClient.Builder`** comes from Spring Boot (the **HTTP Client** starter), already set up with Jackson for JSON. Take the builder, not `RestClient.create()`, so you get that set-up.
- **`baseUrl`** means calls only give the path, such as `/bookings/2/check-in`. `@Value` reads it from `application.properties`, so another environment is one property away.
- **An interceptor** runs around every request the client sends. **`OAuth2ClientHttpRequestInterceptor`** asks the manager for a token, then adds `Authorization: Bearer …` to the request. The manager does the caching, so most requests cost no extra round trip.
- **`setClientRegistrationIdResolver(request -> "scanner")`**: which registration's token to use. Every request from this client uses the scanner's.
- **`setAuthorizationFailureHandler(...)`**: what to do when the API rejects the token anyway. It might have been revoked, or the auth-server restarted with a new signing key (it makes a new key at every start, as guide 05 showed). The handler removes the token from the `OAuth2AuthorizedClientService`, so the **next** request gets a new one. It doesn't retry the request itself: that's step 6's job.

Who is the token for? In a web application the manager would ask "for which signed-in user?". The scanner has no users and no `SecurityContext`, and that's fine: the interceptor asks on behalf of an anonymous principal, and for client credentials the token is the client's own anyway.

You could now check a booking in with this `RestClient` alone, using the record from the next step:

```java
CheckedInBooking booking = ticketsRestClient.post().uri("/bookings/{id}/check-in", 2).retrieve().body(CheckedInBooking.class);
```

That's one line, and for one endpoint it would do. The scanner doesn't keep it: the next step puts the same call behind an interface instead, which is how most Spring applications describe the APIs they call.

### 5. The API as a Java interface

Create `TicketsApi.java`:

```java
package com.example.scanner;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.PostExchange;

/**
 * The part of the tickets API the scanner uses, as a Java interface. Spring writes the
 * class that makes the HTTP calls (see ClientConfig). An error status, such as 409,
 * is thrown as an HttpClientErrorException.
 */
public interface TicketsApi {

    @PostExchange("/bookings/{id}/check-in")
    CheckedInBooking checkIn(@PathVariable long id);

    /** The fields of the API's booking that the scanner cares about. Others are ignored. */
    record CheckedInBooking(long id, long eventId, int quantity, String status) {
    }
}
```

- **`@PostExchange`** says "send a `POST` to this path". There's a `@GetExchange`, `@PutExchange` and so on for the other methods. `@PathVariable` fills in `{id}`, exactly as it does in a controller.
- **The return type** is what the JSON body becomes. The API's `BookingResponse` has seven fields: `id`, `eventId`, `ticketTypeId`, `quantity`, `totalPence`, `status` and `createdAt`. The scanner needs four, so the record has four. Jackson ignores the fields it has no place for, so the API can add fields without breaking the scanner.
- **The record is the scanner's own**, not a copy of the API's class. The two projects share no code: the API's JSON is the contract between them. That's what lets two teams build and release them separately.
- **`status` is a `String`**, not an enum. If the API one day adds a status, the scanner can still read the response.

Spring implements the interface with a factory. Add a third bean to `ClientConfig`:

```java
    /** The HTTP interface client: Spring implements TicketsApi using the RestClient. */
    @Bean
    TicketsApi ticketsApi(RestClient ticketsRestClient) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(ticketsRestClient))
                .build()
                .createClient(TicketsApi.class);
    }
```

**`RestClientAdapter`** connects the factory to step 4's `RestClient`, so every call the interface makes goes through the OAuth2 interceptor. **`createClient`** returns an object that implements `TicketsApi`: calling `checkIn(2)` sends `POST /bookings/2/check-in` and turns the answer into a `CheckedInBooking`. Errors are thrown just as they were from `RestClient`, as `HttpClientErrorException.Conflict`, `.NotFound` and so on, with the API's `ProblemDetail` in the body. On the build machine the interface needed no workarounds at all: it behaved exactly like the direct `RestClient` call.

> **Sidebar: even less configuration.** Spring Boot 4 and Spring Security 7 can do this step and the last one for you. `@ImportHttpServices` on a configuration class registers HTTP interfaces as beans, in named groups that share a base URL. Spring Security's `@ClientRegistrationId("scanner")` on an interface or method says which registration's token to send, and an `OAuth2RestClientHttpServiceGroupConfigurer` bean adds the interceptor to the groups. That's the way to go when a service calls several APIs, with several interfaces each. For one interface with one method, writing the beans out shows what's going on, and there are fewer annotations to look up.

### 6. Retries: `CheckInService`

The rules from the Concepts table go in one class. Create `CheckInService.java`:

```java
package com.example.scanner;

import com.example.scanner.TicketsApi.CheckedInBooking;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;

/**
 * Checks a booking in, coping with the two errors that are worth trying again:
 * 429 Too Many Requests (wait as long as Retry-After says) and 401 Unauthorized
 * (the token has expired or been rejected: try once more with a new one).
 * Anything else, such as 409 for a cancelled booking, goes back to the caller.
 */
public class CheckInService {

    static final int MAX_ATTEMPTS = 4;

    /** Waits. Tests pass one that only records how long it was asked to wait. */
    public interface Sleeper {
        void sleep(Duration duration);
    }

    private final TicketsApi api;
    private final Sleeper sleeper;
    private final Duration longestWait;

    public CheckInService(TicketsApi api, Sleeper sleeper, Duration longestWait) {
        this.api = api;
        this.sleeper = sleeper;
        this.longestWait = longestWait;
    }

    public CheckedInBooking checkIn(long bookingId) {
        boolean triedANewToken = false;
        for (int attempt = 1; ; attempt++) {
            try {
                return api.checkIn(bookingId);
            } catch (HttpClientErrorException.Unauthorized e) {
                // The interceptor has already forgotten the rejected token
                if (triedANewToken) {
                    throw e;
                }
                triedANewToken = true;
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                sleeper.sleep(waitBeforeRetrying(e, attempt));
            }
        }
    }

    /** What Retry-After says (but never more than longestWait), else 1, 2, 4... seconds. */
    Duration waitBeforeRetrying(HttpClientErrorException e, int attempt) {
        String retryAfter = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        Duration wait;
        try {
            wait = Duration.ofSeconds(Long.parseLong(retryAfter));
        } catch (NumberFormatException ex) {
            // Missing, or an HTTP date rather than seconds: back off exponentially instead
            wait = Duration.ofSeconds(1L << (attempt - 1));
        }
        return wait.compareTo(longestWait) > 0 ? longestWait : wait;
    }

    static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry", e);
        }
    }
}
```

From the top:

- **The loop** tries, and only `return`s on success. Each `catch` either decides to go round again or throws. An exception it doesn't catch, such as `HttpClientErrorException.Conflict` or `.NotFound`, leaves the method straight away: no retry, as the table said.
- **`401`: once.** By the time the exception arrives, the interceptor's failure handler has already removed the rejected token, so the next attempt makes the manager fetch a new one. If the new token is rejected too, something is wrong that another token won't fix (the API trusts a different auth-server, say), so the second `401` is thrown. Without `triedANewToken`, a scanner with a bad configuration would loop for ever.
- **`429`: up to `MAX_ATTEMPTS`**, four tries in all, so at most three waits. On the fourth `429` it gives up and throws.
- **`waitBeforeRetrying`** reads `Retry-After` from the exception's headers. Guide 08's API sends whole seconds, such as `Retry-After: 1`. The header may also be an HTTP date (`Retry-After: Fri, 13 Nov 2026 18:00:00 GMT`), which `Long.parseLong` can't read, and of course it may be missing (`Long.parseLong(null)` throws a `NumberFormatException` too). Both cases fall back to **exponential backoff**: `1L << (attempt - 1)` is 1, 2 and 4 seconds for attempts 1, 2 and 3. Either way, the wait is **capped** at `longestWait`. That cap is guide 08's **API10, Unsafe Consumption of APIs**, from the client's side: `Retry-After` is data from another system, to be checked before it's obeyed, not an order. A buggy or hostile API that says `Retry-After: 86400` mustn't be able to freeze the door for a day.
- **`Sleeper`** is a functional interface with one method, so a method reference or a lambda can be one. The real one is `CheckInService::sleep`, which calls `Thread.sleep`. Tests pass `waits::add`, which records the wait and returns at once, so a test of a 30-second wait takes no time at all. That's why `CheckInService` is given its `Sleeper` instead of calling `Thread.sleep` itself.
- **`InterruptedException`**: if something interrupts the thread while it waits, `sleep` puts the interrupted flag back and gives up, which is the polite thing to do with an interruption you can't handle.
- **No `@Service`.** The class needs a `Sleeper` and a `Duration` that Spring can't guess, so `ClientConfig` creates it, in the next step. That also keeps it a plain class that tests can build with `new`.

**Why not Spring Retry, or Resilience4j?** Both give you retries with backoff by configuration, and in a bigger application you'd probably use one. Here, the rules are the lesson, and they fit in twenty lines you can read, including the `Retry-After` that a general-purpose library would need extra configuration to honour.

### 7. `ClientConfig`, in full

The fourth and last bean creates `CheckInService`, with the real sleeper and a 30-second cap. Here's `ClientConfig.java` in full:

```java
package com.example.scanner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.time.Duration;

@Configuration
public class ClientConfig {

    /**
     * Gets client-credentials tokens and keeps them until they're about to expire.
     * (Spring Boot sets up the registration and the token store from application.properties;
     * this "manager" is only made for you in web applications, so we make it here.)
     */
    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
                                                          OAuth2AuthorizedClientService tokens) {
        return new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, tokens);
    }

    /** Calls the tickets API with the scanner's token in an Authorization: Bearer header. */
    @Bean
    RestClient ticketsRestClient(RestClient.Builder builder, OAuth2AuthorizedClientManager manager,
                                 OAuth2AuthorizedClientService tokens,
                                 @Value("${tickets.api.base-url}") String baseUrl) {
        OAuth2ClientHttpRequestInterceptor oauth2 = new OAuth2ClientHttpRequestInterceptor(manager);
        oauth2.setClientRegistrationIdResolver(request -> "scanner");
        // On a 401 from the API, forget the token, so the next request gets a new one
        oauth2.setAuthorizationFailureHandler(OAuth2ClientHttpRequestInterceptor.authorizationFailureHandler(tokens));
        return builder
                .baseUrl(baseUrl)
                .requestInterceptor(oauth2)
                .build();
    }

    /** The HTTP interface client: Spring implements TicketsApi using the RestClient. */
    @Bean
    TicketsApi ticketsApi(RestClient ticketsRestClient) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(ticketsRestClient))
                .build()
                .createClient(TicketsApi.class);
    }

    @Bean
    CheckInService checkInService(TicketsApi ticketsApi) {
        return new CheckInService(ticketsApi, CheckInService::sleep, Duration.ofSeconds(30));
    }
}
```

Read it from the bottom up and it's the diagram from the Concepts section: `CheckInService` uses `TicketsApi`, which uses the `RestClient`, whose interceptor uses the manager, which uses the registrations and the token store that Spring Boot made from `application.properties`.

The interceptor lives in a package called `web.client`, which can look as if it needs a web application. It doesn't: `web` there means HTTP. The scanner still has no web server.

### 8. The runner and the exit code

Create `ScanRunner.java`:

```java
package com.example.scanner;

import com.example.scanner.TicketsApi.CheckedInBooking;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * Checks in the booking ids given on the command line, as if their tickets had just been
 * scanned at the door:  java -jar target/scanner-0.0.1-SNAPSHOT.jar 2 3
 */
@Component
public class ScanRunner implements ApplicationRunner, ExitCodeGenerator {

    private final CheckInService checkIns;
    private int exitCode;

    public ScanRunner(CheckInService checkIns) {
        this.checkIns = checkIns;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.getNonOptionArgs().isEmpty()) {
            System.out.println("Usage: scanner <booking id> [<booking id>...]");
            exitCode = 2;
            return;
        }
        for (String id : args.getNonOptionArgs()) {
            System.out.println(scan(Long.parseLong(id)));
        }
    }

    String scan(long bookingId) {
        try {
            CheckedInBooking booking = checkIns.checkIn(bookingId);
            return "Booking " + bookingId + ": " + booking.status() + ", " + booking.quantity()
                    + " ticket(s) for event " + booking.eventId() + ". Let them in.";
        } catch (HttpClientErrorException e) {
            // The API's errors are ProblemDetails: show the reason it gives
            exitCode = 1;
            ProblemDetail problem = e.getResponseBodyAs(ProblemDetail.class);
            String reason = problem != null && problem.getDetail() != null ? problem.getDetail() : e.getStatusText();
            return "Booking " + bookingId + ": REFUSED (" + e.getStatusCode().value() + "): " + reason;
        } catch (OAuth2AuthorizationException e) {
            // The authorisation server wouldn't give us a token, such as for a wrong client secret
            exitCode = 1;
            return "Booking " + bookingId + ": ERROR: no access token: " + e.getError();
        } catch (RestClientException e) {
            // Includes not being able to reach the API or get a token
            exitCode = 1;
            return "Booking " + bookingId + ": ERROR: " + e.getMessage();
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
```

- **`getNonOptionArgs()`** are the arguments that don't start with `--`: the booking ids. Arguments that do, such as `--tickets.api.base-url=…`, are Spring Boot properties, and override `application.properties` for this run.
- **One line per booking**, whatever happens: the door staff need an answer for every ticket, so one failure mustn't stop the rest.
- **`HttpClientErrorException`** is every `4xx` that `CheckInService` didn't retry, or retried and gave up on. Since guide 08, every error the API sends is a `ProblemDetail`, and **`getResponseBodyAs(ProblemDetail.class)`** reads it, so the scanner can show the API's own reason: `Booking 3 was cancelled` is more use at the door than `409 Conflict`. If the body isn't a `ProblemDetail` after all, it falls back to the status text.
- **`OAuth2AuthorizationException`** is what the token manager throws when the auth-server won't give it a token: a wrong secret, or a scope the client isn't registered for. **It isn't a `RestClientException`**, so without its own `catch`, a wrong secret crashes the scanner with a stack trace. `e.getError()` is the OAuth2 error, such as `[invalid_scope]`.
- **`RestClientException`** is the parent of everything else `RestClient` throws, including **not being able to connect** at all.
- **`exitCode`** starts at `0`, and any refusal or error sets it to `1`.

Then change `ScannerApplication.java`, so the exit code reaches the operating system:

```java
package com.example.scanner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ScannerApplication {

    public static void main(String[] args) {
        // Runs ScanRunner, then exits with its exit code (0 = everyone was let in)
        System.exit(SpringApplication.exit(SpringApplication.run(ScannerApplication.class, args)));
    }
}
```

Read it from the inside out. **`SpringApplication.run`** starts the application, which runs `ScanRunner`, and returns when it's finished. **`SpringApplication.exit`** closes the application context cleanly, asks every `ExitCodeGenerator` bean for its code, and returns it. **`System.exit`** ends the program with that code. Without the outer two, the program would always exit with `0`, and a script couldn't tell a full house from a queue of forged tickets.

### 9. Check people in

Start the **auth-server**, then the **API**.

The outputs below came from a **fresh database**. To match them, stop the API, delete the API project's `data` folder, and start it again, as in guide 07. Then give the scanner something to scan. In Swagger UI (`http://localhost:8080/swagger-ui.html`), authorise as alice, as in guide 06, and:

1. Book two Front Row tickets for event 2: `POST /bookings` with `{"ticketTypeId": 57, "quantity": 2}`. That's **booking 2** (booking 1 is the seeded one).
2. Book one Standing ticket for event 3: `{"ticketTypeId": 3, "quantity": 1}`. That's **booking 3**.
3. Cancel booking 3: `POST /bookings/3/cancel`.

(If you'd rather keep your database, use your own booking ids: one that's confirmed, and one you've cancelled.)

**Build the scanner.** In the `scanner` project:

```bash
./mvnw package
```

That runs the tests (step 10 adds them) and builds `target/scanner-0.0.1-SNAPSHOT.jar`, a single jar with the scanner and every library it needs. Now scan four tickets: booking 2, booking 2 **again** (someone tries to get in twice on the same ticket, or the scanner's first try timed out), the cancelled booking 3, and a booking that doesn't exist:

```bash
java -jar target/scanner-0.0.1-SNAPSHOT.jar 2 2 3 999
```

```
Booking 2: CHECKED_IN, 2 ticket(s) for event 2. Let them in.
Booking 2: CHECKED_IN, 2 ticket(s) for event 2. Let them in.
Booking 3: REFUSED (409): Booking 3 was cancelled
Booking 999: REFUSED (404): Booking 999 was not found
```

Then the exit code:

```bash
echo $?
```

```
1
```

Four lines, one per ticket, and the exit code is `1` because two were refused. Look at what happened behind them: the scanner asked the auth-server for a token **once**, before the first check-in, and sent the same token four times. The second scan of booking 2 got the same `200` as the first, as guide 06 designed. The `409` and `404` were `ProblemDetail`s, and the scanner shows their `detail`.

**Booking 2 being let in twice is worth a second look.** For a retry, it's exactly right: a scanner that never heard the first answer must be able to ask again. At a real door, though, two people holding copies of the same ticket would both get in. The API can't tell a retry from a copy, but the door staff could, if the scanner told them "already checked in". That would need the API to say so, which is a change to its contract: a field such as `checkedInAt` in the response, say. It's a good example of an API design question that only shows up once you write a client.

Run it with no booking ids:

```bash
java -jar target/scanner-0.0.1-SNAPSHOT.jar
```

```
Usage: scanner <booking id> [<booking id>...]
```

and `echo $?` says `2`.

**In PowerShell**, the same commands are:

```powershell
.\mvnw package
java -jar target\scanner-0.0.1-SNAPSHOT.jar 2 2 3 999
$LASTEXITCODE
```

`$LASTEXITCODE` is PowerShell's `$?` for the last program's exit code. (PowerShell's own `$?` is only `True` or `False`.)

**In IntelliJ**, run `ScannerApplication` from the green arrow once, then open the run configuration (**Run → Edit Configurations…**) and put the booking ids, such as `2 3`, in **Program arguments**.

**With Maven**, `./mvnw spring-boot:run -Dspring-boot.run.arguments=2` should run the scanner without building a jar first. (In PowerShell, put the whole `-D…` argument in quotes.) That form wasn't tried on the build machine; the `java -jar` form was.

**If the API or the auth-server isn't on its usual port**, override the properties for this run, with `--` arguments after the jar:

```bash
java -jar target/scanner-0.0.1-SNAPSHOT.jar --tickets.api.base-url=http://localhost:8090 --spring.security.oauth2.client.provider.tickets.token-uri=http://localhost:9001/oauth2/token 2
```

Give each property once: see Common errors.

**Want to see the calls?** Add `--logging.level.org.springframework.web.client=debug`. Each check-in then logs a line such as:

```
o.s.web.client.DefaultRestClient : Reading to [com.example.scanner.TicketsApi$CheckedInBooking]
```

**What about `429` and `401`?** You won't see them here. The scanner checks its tickets in one at a time, and guide 08's limit of 20 at once and 5 a second is far more than a handful of scans needs. Its tokens last five minutes, and a run takes a second or two. Both rules are real, though, and the next step's tests prove them without waiting for a busy night at the door.

### 10. Tests

Two test classes, for two different questions:

- **Are the rules right?** `CheckInServiceTest` checks the retries against a pretend API, with no network, no tokens and no Spring context. It's fast, and it can produce a `429` or a `401` whenever it likes.
- **Is the configuration right?** `ScannerClientTest` starts the real Spring application against a tiny pretend auth-server and API, and checks the token is fetched, sent and reused.

**The rules.** Create `src/test/java/com/example/scanner/CheckInServiceTest.java` (trimmed: two tests in the same style are described after it):

```java
package com.example.scanner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The scanner's retry rules, against a pretend API (MockRestServiceServer): no network, no tokens. */
class CheckInServiceTest {

    static final String URL = "http://api.test/bookings/2/check-in";
    static final String CHECKED_IN = """
            {"id": 2, "eventId": 2, "ticketTypeId": 57, "quantity": 2, "totalPence": 15000,
             "status": "CHECKED_IN", "createdAt": "2030-01-02T10:15:30Z"}
            """;

    MockRestServiceServer api;
    List<Duration> waits = new ArrayList<>();
    CheckInService checkIns;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://api.test");
        api = MockRestServiceServer.bindTo(builder).build();
        TicketsApi ticketsApi = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build()))
                .build()
                .createClient(TicketsApi.class);
        checkIns = new CheckInService(ticketsApi, waits::add, Duration.ofSeconds(30));
    }

    @Test
    void checksABookingIn() {
        api.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(CHECKED_IN, MediaType.APPLICATION_JSON));

        var booking = checkIns.checkIn(2);

        assertThat(booking).isEqualTo(new TicketsApi.CheckedInBooking(2, 2, 2, "CHECKED_IN"));
        api.verify();
    }

    @Test
    void after429ItWaitsAsLongAsRetryAfterSaysAndTriesAgain() {
        api.expect(requestTo(URL)).andRespond(tooManyRequests("2"));
        api.expect(requestTo(URL)).andRespond(withSuccess(CHECKED_IN, MediaType.APPLICATION_JSON));

        assertThat(checkIns.checkIn(2).status()).isEqualTo("CHECKED_IN");
        assertThat(waits).containsExactly(Duration.ofSeconds(2));
        api.verify();
    }

    @Test
    void withoutRetryAfterItBacksOffExponentially() {
        api.expect(requestTo(URL)).andRespond(tooManyRequests(null));
        api.expect(requestTo(URL)).andRespond(tooManyRequests(null));
        api.expect(requestTo(URL)).andRespond(withSuccess(CHECKED_IN, MediaType.APPLICATION_JSON));

        checkIns.checkIn(2);

        assertThat(waits).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void givesUpAfterFourAttempts() {
        for (int i = 0; i < 4; i++) {
            api.expect(requestTo(URL)).andRespond(tooManyRequests("1"));
        }

        assertThatThrownBy(() -> checkIns.checkIn(2)).isInstanceOf(HttpClientErrorException.TooManyRequests.class);
        assertThat(waits).hasSize(3);
        api.verify();
    }

    @Test
    void after401ItTriesOnceMoreWithANewToken() {
        api.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        api.expect(requestTo(URL)).andRespond(withSuccess(CHECKED_IN, MediaType.APPLICATION_JSON));

        assertThat(checkIns.checkIn(2).status()).isEqualTo("CHECKED_IN");
        api.verify();
    }

    @Test
    void aCancelledBookingIsRefusedWithTheApisReasonAndNotRetried() {
        api.expect(requestTo("http://api.test/bookings/3/check-in")).andRespond(withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("""
                        {"detail": "Booking 3 was cancelled", "instance": "/bookings/3/check-in", "status": 409, "title": "Conflict"}
                        """));

        String line = new ScanRunner(checkIns).scan(3);

        assertThat(line).isEqualTo("Booking 3: REFUSED (409): Booking 3 was cancelled");
        assertThat(waits).isEmpty();
        api.verify();
    }

    private static org.springframework.test.web.client.response.DefaultResponseCreator tooManyRequests(String retryAfter) {
        var response = withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("""
                        {"detail": "Too many requests", "status": 429, "title": "Too Many Requests"}
                        """);
        if (retryAfter != null) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.RETRY_AFTER, retryAfter);
            response.headers(headers);
        }
        return response;
    }
}
```

The two trimmed tests: **`neverWaitsLongerThanItsLimit`** answers `429` with `Retry-After: 3600`, then `200`, and asserts the only wait was 30 seconds. **`a401AfterThatIsAnError`** answers `401` twice, and asserts `checkIn` throws `HttpClientErrorException.Unauthorized` after exactly those two requests.

- **`MockRestServiceServer`** is the client-side twin of `MockMvcTester`: instead of pretending to be a client of your controllers, it pretends to be the server your client calls. **`bindTo(builder)`** swaps the builder's real HTTP connection for the mock, so any `RestClient` built from it talks to the mock, and nothing goes over the network. `http://api.test` doesn't exist, which is the point.
- **The `TicketsApi` is made the same way as in `ClientConfig`**, over a `RestClient` from that builder. The test exercises the real interface client, minus the OAuth2 interceptor: these tests are about the retry rules, not tokens.
- **`api.expect(...)` sets up one request at a time, in order**: what it must look like (`requestTo`, `method`), and how to answer (`andRespond`). Two `expect`s means the test expects exactly two requests. `withSuccess` is a `200` with a body. `withStatus` is anything else, and you can add a content type, headers and a body.
- **`api.verify()`** fails the test if an expected request was never made. Together with the mock's refusal to answer requests it wasn't told to expect, that pins the number of attempts down exactly: `givesUpAfterFourAttempts` proves four tries, not three or five.
- **`waits::add` is the `Sleeper`.** Each "wait" is recorded in the list and returns immediately, so the test can assert `containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2))`, and `neverWaitsLongerThanItsLimit` doesn't take 30 seconds, or an hour.
- **`after401ItTriesOnceMoreWithANewToken`** only proves the retry. That the second attempt has a **new** token is the interceptor's work, and Spring Security's to test.
- **`aCancelledBookingIsRefusedWithTheApisReasonAndNotRetried`** goes one level up, through `ScanRunner.scan`, with a `ProblemDetail` body exactly like the API's. It proves the door staff see the API's reason, and, with `waits` empty and one expected request, that a `409` isn't retried. `scan` is package-private for exactly this.
- **`tooManyRequests(...)`** builds a `429` like guide 08's, with a `Retry-After` header or without one. `DefaultResponseCreator` is written out in full because it's needed only here.

**The configuration.** A unit test can't prove the token is fetched once and sent every time: that's Spring's configuration at work, with the real manager and interceptor. So the second test starts the whole application, and points it at a pretend auth-server and API: the JDK's built-in HTTP server, which comes with Java and needs no dependency. Create `src/test/java/com/example/scanner/ScannerClientTest.java`:

```java
package com.example.scanner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real Spring configuration against a tiny pretend authorisation server and API
 * (the JDK's built-in HTTP server): checks the token is fetched, sent, and reused.
 */
@SpringBootTest
class ScannerClientTest {

    static final List<String> requests = new CopyOnWriteArrayList<>();
    static final HttpServer server = startServer();

    @DynamicPropertySource
    static void pointAtThePretendServer(DynamicPropertyRegistry properties) {
        String url = "http://localhost:" + server.getAddress().getPort();
        properties.add("tickets.api.base-url", () -> url);
        properties.add("spring.security.oauth2.client.provider.tickets.token-uri", () -> url + "/oauth2/token");
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Autowired
    CheckInService checkIns;

    @Test
    void getsATokenOnceAndSendsItWithEveryCheckIn() {
        checkIns.checkIn(2);
        checkIns.checkIn(5);

        assertThat(requests).containsExactly(
                "POST /oauth2/token Basic c2Nhbm5lcjpzY2FubmVyLXNlY3JldA== grant_type=client_credentials&scope=tickets%3Acheck-in",
                "POST /bookings/2/check-in Bearer pretend-token",
                "POST /bookings/5/check-in Bearer pretend-token");
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/oauth2/token", exchange -> {
                record(exchange, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, """
                        {"access_token": "pretend-token", "token_type": "Bearer", "expires_in": 300, "scope": "tickets:check-in"}
                        """);
            });
            server.createContext("/bookings", exchange -> {
                record(exchange, null);
                respond(exchange, """
                        {"id": 2, "eventId": 2, "quantity": 2, "status": "CHECKED_IN"}
                        """);
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void record(HttpExchange exchange, String body) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + authorization
                + (body == null ? "" : " " + body));
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
```

- **`HttpServer.create(new InetSocketAddress("localhost", 0), 0)`**: port `0` asks the operating system for any free port, so the test never clashes with a running API or auth-server. `server.getAddress().getPort()` says which one it got.
- **Two contexts**, one per path prefix. `/oauth2/token` plays the auth-server: it records the form body and answers with a token response like the real one. `/bookings` plays the API: it records the request and answers like a check-in. Every request is recorded as one line: method, path, `Authorization` header and, for the token request, the body.
- **`static final HttpServer server = startServer()`** starts the server when the class loads, before Spring starts, because **`@DynamicPropertySource`** needs its port to set the two URLs. Those properties win over `application.properties` for this test's context, the same way the `--` arguments did in step 9.
- **The assertion is the whole conversation**, in order. The token request is a `POST` with `Basic c2Nhbm5lcjpzY2FubmVyLXNlY3JldA==`, which is `scanner:scanner-secret` in Base64: exactly what curl's `-u` sent in guide 05. Its body is `grant_type=client_credentials&scope=tickets%3Acheck-in`, the `:` encoded as `%3A`. Then **two** check-ins with `Bearer pretend-token`, and **no second token request**. That's the framework's caching, proved.
- **`pretend-token` isn't a JWT**, and the scanner doesn't care. To a client, an access token is just a string to send back. Only the API, which checks it, needs to read it. A client that decodes tokens to make decisions breaks the day the authorisation server changes its format.
- **`CopyOnWriteArrayList`**, because the JDK server handles requests on its own threads, while the test reads the list on the test's thread.

Run the scanner's tests:

```bash
./mvnw test
```

```
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
```

You'll also see `Usage: scanner <booking id> [<booking id>...]` in the output. That's `ScanRunner`: it's a bean, so it runs whenever the application starts, including `ScannerClientTest`'s, with no arguments. It's harmless: the exit code only matters in `main`, and tests don't go through `main`.

---

## Your turn

### Exercise 1: a forged ticket

Add a test to `CheckInServiceTest` for a booking that doesn't exist. The API answers `POST /bookings/999/check-in` with a `404` and this `ProblemDetail`:

```json
{"detail":"Booking 999 was not found","instance":"/bookings/999/check-in","status":404,"title":"Not Found"}
```

Assert the line `ScanRunner.scan` returns, that the scanner didn't wait, and that it made exactly one request.

<details>
<summary>Solution</summary>

```java
@Test
void anUnknownBookingIsRefusedAndNotRetried() {
    api.expect(requestTo("http://api.test/bookings/999/check-in")).andRespond(withStatus(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body("""
                    {"detail": "Booking 999 was not found", "instance": "/bookings/999/check-in", "status": 404, "title": "Not Found"}
                    """));

    String line = new ScanRunner(checkIns).scan(999);

    assertThat(line).isEqualTo("Booking 999: REFUSED (404): Booking 999 was not found");
    assertThat(waits).isEmpty();
    api.verify();
}
```

One `expect` and `api.verify()` together prove exactly one request: a retry would have been a request the mock wasn't expecting, and the test would fail. The line is the same one step 9 printed for booking 999.

</details>

### Exercise 2: predict the waits

Without running anything, work out what `CheckInService.checkIn` does with each of these sequences of answers from the API. Which waits are recorded, and does it return a booking or throw?

1. `429` with `Retry-After: 5`, then `200`.
2. `429` with no `Retry-After`, `429` with `Retry-After: 3`, then `200`.
3. `429` with `Retry-After: Fri, 13 Nov 2026 18:00:00 GMT`, then three more `429`s with no `Retry-After`.
4. `401`, then `429` with no `Retry-After` three times.
5. What's the longest a single `checkIn` can possibly spend waiting?

<details>
<summary>Solution</summary>

1. Waits **[5 s]**, then returns the booking.
2. Waits **[1 s, 3 s]**, then returns the booking. The first wait is the backoff for attempt 1, the second is what `Retry-After` said.
3. Waits **[1 s, 2 s, 4 s]**, then throws `TooManyRequests` on the fourth `429`. The date can't be read as a number of seconds, so the first wait is backoff too. `ScanRunner` would print `REFUSED (429)` with the API's detail.
4. Waits **[2 s, 4 s]**, then throws `TooManyRequests`. The `401` used up attempt 1, so the `429`s are attempts 2, 3 and 4: the backoff starts at `1L << 1`, and the fourth attempt is the last. A `401` counts towards the four attempts.
5. **90 seconds**: three waits, each capped at 30. That's a long time at a door, which is a reason to keep the cap small, or to make it a setting (the next exercise).

</details>

### Exercise 3: make the cap a setting

The 30-second cap is written into `ClientConfig`. Make it a property, `tickets.scanner.longest-wait-seconds`, set to `30` in `application.properties`, so a venue with a long queue can lower it with `--tickets.scanner.longest-wait-seconds=5` on the command line.

<details>
<summary>Solution</summary>

In `application.properties`:

```properties
# The longest the scanner waits before retrying after a 429, whatever Retry-After says
tickets.scanner.longest-wait-seconds=30
```

In `ClientConfig`:

```java
@Bean
CheckInService checkInService(TicketsApi ticketsApi,
                              @Value("${tickets.scanner.longest-wait-seconds}") long longestWaitSeconds) {
    return new CheckInService(ticketsApi, CheckInService::sleep, Duration.ofSeconds(longestWaitSeconds));
}
```

The same `@Value` as `tickets.api.base-url` in step 4. Spring converts the text `30` to a `long`. `CheckInService` and its tests don't change: they were always given the cap, which is what made this a one-bean change.

</details>

### Exercise 4 (stretch): two cancels at once, for real

**This one has no solution here.** It goes back to the API, and uses only what guide 07 taught.

Guide 07's exercise 3 found a race it didn't fix: two cancels of the same booking at the same moment can both see `CONFIRMED`, and both release the tickets. A real client makes it more likely than it sounds: alice's app times out and retries a cancel, exactly as this guide's scanner retries a check-in. Fix it properly:

1. **Prove it first**, as in guide 07 step 1. Write a race test in the `api` that books one ticket for a fresh ticket type (as `LastTicketRaceTest` does), then has several threads, held back by a `CountDownLatch`, all call `BookingService.cancel` for that booking as its customer. Assert that the ticket type's `sold` is back to exactly `0`. Run it against today's code and watch it fail. (If it passes, add threads and run it again: race tests are about probability.)
2. **Fix it** with guide 07 exercise 3's conditional update, `cancelIfConfirmed`, releasing the tickets only when it returns `1`. Mind guide 07 step 3's warning about the stale entity.
3. **Check the rules from guide 06 still hold**: a repeated cancel is still a `200`, and cancelling a checked-in booking is still a `409`. All the API's existing tests should pass, plus your new one.

---

## Checkpoint

In the **scanner**:

```bash
./mvnw test
```

```
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
```

(8 in `CheckInServiceTest` and 1 in `ScannerClientTest`. With exercise 1's test, you'll have 10.)

The **api** and the **auth-server** haven't changed: their 82 and 11 tests still pass. (If you've done exercise 4, the api has one more.) That's 102 tests across the three projects.

The new project:

```
scanner/
├── pom.xml
└── src/
    ├── main/java/com/example/scanner/
    │   ├── CheckInService.java
    │   ├── ClientConfig.java
    │   ├── ScannerApplication.java
    │   ├── ScanRunner.java
    │   └── TicketsApi.java
    ├── main/resources/application.properties
    └── test/java/com/example/scanner/
        ├── CheckInServiceTest.java
        └── ScannerClientTest.java
```

Modified: nothing in the api or the auth-server. In the scanner, `ScannerApplication.java` and `application.properties` were changed from what Spring generated, and the generated test class was deleted.

And with the auth-server and the API running, `java -jar target/scanner-0.0.1-SNAPSHOT.jar 2` says `Booking 2: CHECKED_IN, …. Let them in.` and exits with `0`.

---

## Common errors

**`Booking 2: ERROR: no access token: [invalid_token_response] … 401 Unauthorized on POST request for "http://localhost:9000/oauth2/token": "{"error":"invalid_client"}"`**
In full:

```
Booking 2: ERROR: no access token: [invalid_token_response] An error occurred while attempting to retrieve the OAuth 2.0 Access Token Response: 401 Unauthorized on POST request for "http://localhost:9000/oauth2/token": "{"error":"invalid_client"}"
```

The auth-server didn't accept the client id and secret: guide 05's `invalid_client`. Check `client-id` and `client-secret` in `application.properties` against the scanner's registration in the auth-server's.

**A wrong secret crashes the scanner with `org.springframework.security.oauth2.client.ClientAuthorizationException: [invalid_token_response] …` and a stack trace**
The same problem, but `ScanRunner` has no `catch (OAuth2AuthorizationException e)`. A token failure isn't a `RestClientException`, so the `catch` for that doesn't see it. Add the `catch` from step 8, and you get the one-line error above instead.

**`Booking 2: ERROR: no access token: [invalid_scope] `**
The scanner asked for a scope its registration on the auth-server doesn't allow, such as `events:write`. It can only ever have `tickets:check-in`. Check `spring.security.oauth2.client.registration.scanner.scope`.

**`Booking 2: ERROR: I/O error on POST request for "http://localhost:8080/bookings/2/check-in": null`**
The API isn't running, or not on that port. Start it, or point the scanner at it with `--tickets.api.base-url=…`. The `null` is the message of the connection error underneath, which on Windows is empty. If you'd like a better one, print `e.getMostSpecificCause()` in `ScanRunner` instead of `e.getMessage()`.

**`Booking 2: ERROR: no access token: [invalid_token_response] An error occurred while attempting to retrieve the OAuth 2.0 Access Token Response: I/O error on POST request for "http://localhost:9000/oauth2/token": null`**
The same, for the auth-server: it isn't running, or something else has its port. Start it first, as the README says. If port 9000 is taken by the [identity provider](../identity-provider/README.md) series' `idp`, stop that, as in guide 05.

**Start-up fails with `UnsatisfiedDependencyException: Error creating bean with name 'scanRunner'` … `'ticketsRestClient'` … `Failed to instantiate [org.springframework.web.client.RestClient]`**
Most likely the same property was given twice on the command line, such as two `--tickets.api.base-url=…`. Spring joins them into one value, with a comma between, and that isn't a URL. Give each property once.

**Start-up fails because there's no `RestClient.Builder` bean**
The project is missing `spring-boot-starter-restclient`. In Spring Boot 4 the HTTP client has its own starter, and it isn't part of the OAuth2 client's. Add the **HTTP Client** dependency from step 1.

**`Usage: scanner <booking id> [<booking id>...]` appears in the test output**
Not an error. `ScanRunner` runs whenever the application starts, and `ScannerClientTest` starts it with no arguments. See step 10.

**Every check-in is `REFUSED (403)`**
The token is genuine but has no `tickets:check-in`. With the properties from step 2 that can't happen, since `scope` is set. If you removed the `scope` line, put it back: guide 05's authorisation server only grants the scopes a client asks for.

---

> **Sidebar: a client in production.** The scanner keeps its secret in `application.properties` and its token in memory, which is fine for a tutorial and for a short-lived command-line run. A service that runs for weeks needs more. **Secrets** come from the environment or a vault (Spring Boot reads `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_SCANNER_CLIENTSECRET` from the environment with no code at all), and many organisations replace shared secrets altogether with **`private_key_jwt`**, where the client proves who it is by signing a short JWT with its own private key, so there's no secret to leak. **Timeouts** matter more than anything here: by default an HTTP client can wait a long time for a server that never answers, and a door queue can't. Set connect and read timeouts on the `RestClient`'s request factory. **Retries with many clients** need **jitter**: if a thousand scanners all get `Retry-After: 1` at the same moment, they all come back at the same moment too, so production clients add a small random amount to each wait. And when a service keeps failing, a **circuit breaker** (Resilience4j has one) stops calling it for a while instead of retrying every request, so a struggling API gets room to recover.

---

## Recap

- A **command-line Spring application** has `spring.main.web-application-type=none`, does its work in an **`ApplicationRunner`**, and reports how it went with an **exit code**, from an **`ExitCodeGenerator`**, through `System.exit(SpringApplication.exit(...))`.
- **Spring Security's OAuth2 client** does client credentials for you. A **registration** and a **provider** in `application.properties` say who the client is and where to get tokens (`token-uri`, so start-up doesn't need the auth-server). An **`OAuth2AuthorizedClientManager`**, which you declare yourself outside a web application, fetches tokens and keeps them in the **`OAuth2AuthorizedClientService`** until they have less than **60 seconds** left.
- **`OAuth2ClientHttpRequestInterceptor`** on a **`RestClient`** adds `Authorization: Bearer …` to every request, and its failure handler forgets a token the API answered `401` to.
- An **HTTP interface client** describes the API as a Java interface (`@PostExchange`, `@PathVariable`), and **`HttpServiceProxyFactory`** implements it over the `RestClient`. The client's records hold only the fields it needs, and unknown fields are ignored.
- **`4xx` answers are exceptions**, such as `HttpClientErrorException.Conflict`, and **`getResponseBodyAs(ProblemDetail.class)`** reads the API's reason.
- **Decide per status** whether to retry: **`401`** once with a new token, **`429`** after waiting **`Retry-After`** (with **exponential backoff** when there isn't one, a **cap**, and a **limit on attempts**), and never a `404` or `409`. Only retry an operation that's **safe to repeat**.
- **`MockRestServiceServer`** tests a client's rules with no network: expected requests in order, canned answers, and `verify()`. A **fake `Sleeper`** makes waiting instant and checkable. A small pretend server, such as the JDK's `HttpServer`, tests the real configuration end to end.

### Interview angle

<details>
<summary>"How does one of your services call another service's secured API?"</summary>

With the OAuth 2.0 client credentials flow: the calling service has its own client id and secret (or, better, a private key), and asks the authorisation server's token endpoint for an access token with only the scopes it needs. It sends the token as a bearer token on each call, reuses it until shortly before it expires, and then gets a new one. In Spring, the OAuth2 client does all of that: a client registration in configuration, an `OAuth2AuthorizedClientManager` that fetches and caches tokens, and `OAuth2ClientHttpRequestInterceptor` on a `RestClient`, which adds the header to every request. The called API checks the token's signature, issuer, audience, expiry and scope; it never sees the secret.

</details>

<details>
<summary>"Why cache access tokens? Why not get a new one for every call?"</summary>

Every token request is a network round trip to the authorisation server, often slower than the API call itself, and it puts load on a server that every service in the organisation depends on. A token is valid for its whole lifetime, so the client should reuse it until it's nearly expired, renewing a little early (Spring's default is 60 seconds) to allow for clock differences and the time a request takes. If the API rejects a token before then, it might have been revoked, or the keys rotated, so the client should drop it and get a new one once, not loop.

</details>

<details>
<summary>"How should a client handle a 429?"</summary>

Wait and retry, politely. If there's a `Retry-After` header, wait that long: it's either a number of seconds or an HTTP date. If there isn't, use exponential backoff, ideally with jitter so many clients don't come back at the same moment. Cap the wait and the number of attempts, because the caller can't wait for ever, and report the failure when you give up. Only retry operations that are safe to repeat: a check-in designed to be idempotent, a `GET`, or a `POST` with an idempotency key.

</details>

<details>
<summary>"Which errors should a client retry?"</summary>

The ones that might succeed next time: `429` after the wait it asks for, `503` and some other `5xx`s, connection failures and timeouts, and a `401` once, with a new token. Not the ones that will fail the same way: `400` and `422` (the request is wrong), `403` (not allowed), `404` (it isn't there), `409` (the state forbids it). And before retrying anything, check it's safe to repeat: a request that timed out may have worked, so retrying a non-idempotent `POST` can do the work twice. That's what idempotency keys are for.

</details>

<details>
<summary>"RestClient, WebClient, RestTemplate, Feign or HTTP interfaces: what would you use?"</summary>

In a current Spring application that isn't reactive, `RestClient`: a fluent, synchronous client that replaces `RestTemplate`, which is in maintenance. `WebClient` is for reactive applications. HTTP interface clients put a declarative interface on top of either, much like OpenFeign, but built into Spring Framework, with no extra dependency: you write `@GetExchange`/`@PostExchange` methods and Spring implements them. I'd describe each external API as an HTTP interface over a `RestClient` that has the cross-cutting concerns, such as the base URL, the OAuth2 interceptor and timeouts, configured once.

</details>

<details>
<summary>"How do you test code that calls another service's API?"</summary>

At two levels. For the client's own logic, such as mapping, error handling and retries, use a mock server bound to the HTTP client, such as Spring's `MockRestServiceServer`, which sets up expected requests and canned responses, with no network. Make waiting injectable so retry tests run instantly. For the wiring, such as authentication headers, token caching and configuration, start the application against a fake server on a random port: the JDK's `HttpServer` for something tiny, or WireMock. For the agreement between the two teams, contract tests (Spring Cloud Contract, Pact) check that the client's expectations match what the real API does.

</details>

### Further reading

- [Spring Framework reference: REST clients](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html), covering `RestClient` and HTTP interface clients
- [Spring Security reference: OAuth2 client](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/index.html), especially [authorized clients](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorized-clients.html), which covers the `RestClient` interceptor
- [Spring Boot reference: Calling REST services](https://docs.spring.io/spring-boot/reference/io/rest-client.html), and [`ApplicationRunner`](https://docs.spring.io/spring-boot/reference/features/spring-application.html#features.spring-application.command-line-runner) and [application exit](https://docs.spring.io/spring-boot/reference/features/spring-application.html#features.spring-application.application-exit)
- [RFC 6749 section 4.4: Client Credentials Grant](https://www.rfc-editor.org/rfc/rfc6749#section-4.4), and [RFC 9110 section 10.2.3: `Retry-After`](https://www.rfc-editor.org/rfc/rfc9110#section-10.2.3)
- [AWS Architecture Blog: Exponential Backoff and Jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/), on why jitter matters when many clients retry
- [Resilience4j](https://resilience4j.readme.io/), for retries, circuit breakers and rate limiters on the client side

**Simplification to notice:** the scanner is a command-line tool that checks in one ticket per argument. A real one runs on a handheld device, reads QR codes, has to keep working when the venue's Wi-Fi drops (checking tickets against a list it downloaded earlier, and sending the check-ins later), and needs to tell door staff "already checked in" apart from "checked in". Each of those is a design question for the API as much as for the client.

---

## What you've built

Nine guides ago, there was a `GET /events/1` that returned one event. Now there are three applications, built, tested and run separately, as they would be in a real organisation:

```
                    ┌──────────────────────┐
                    │ auth-server   :9000  │  client credentials for services,
                    │ (guide 05, 06)       │  authorisation code + PKCE for people
                    └──────────┬───────────┘
                 tokens        │         tokens
        ┌──────────────────────┼──────────────────────┐
        ▼                      ▼                      ▼
  Organiser service      Alice, through          scanner
  (curl)                 Swagger UI              (guide 09: a Spring client)
        │                      │                      │
        └──────────────────────┼──────────────────────┘
                               ▼
                    ┌──────────────────────┐
                    │ api           :8080  │  82 tests
                    │ (guides 01 to 08)    │
                    └──────────────────────┘
```

Every part of the API now earns its place:

| Guide | What the API gained | Who it protects, or helps |
|---|---|---|
| 01 | Resources, URLs, status codes, DTOs, `404` as a `ProblemDetail` | Every caller, who gets a predictable shape |
| 02 | Writes, validation, one error format | Organisers, who are told exactly which field is wrong |
| 03 | Paging, filtering, sorting, `ETag` and `304` | Everyone browsing, and the server's database |
| 04 | An OpenAPI document worth reading | The developers of every client, including the scanner's |
| 05 | Tokens, scopes, `401` and `403` | The organiser's data, from everyone but the organiser |
| 06 | Customers, ownership, actions with state rules | Alice's bookings, from bob |
| 07 | Atomic updates, `If-Match`, idempotency keys | The last ticket, and alice when her network drops mid-booking |
| 08 | CORS, rate limits, security headers, quiet `500`s | The API itself, from browsers, bots and its own bugs |
| 09 | A real client: tokens, retries, `429` and `Retry-After` | The door staff, who get one clear answer per ticket |

Three ideas ran through all of it, and they're the ones to take to your next API:

- **The contract is the HTTP, not the code.** The scanner shares no classes with the API. It relies on a URL, a status code, a few JSON fields and a `ProblemDetail`. That's why guide 01 used DTOs from the start, guide 02 settled on one error format, and guide 04 documented it all.
- **Assume the network fails, and requests arrive twice, or at once.** Guide 06's repeatable check-in, guide 07's atomic updates and idempotency keys, and this guide's retries are the same idea from both ends.
- **Every defence has a test.** 102 of them, across three projects, and each one names a rule: who may do what, what happens at the last ticket, what a `429` looks like, how often the scanner asks for a token.

### One last exercise

Trace **one booking** through the whole series, from alice deciding to go to a gig to the scanner letting her in. For each request she or the scanner makes, write down the method and URL, who sends it, which token it carries (if any), what could go wrong, and which guide's code stops it going wrong. Aim for six to eight requests.

<details>
<summary>Solution</summary>

One possible trace:

1. **`GET /events?city=…&category=…`**, alice's app, no token. Paged and filtered (03). A malicious page size is capped at 100 (03), and a flood of requests gets a `429` (08).
2. **`GET /events/2`**, no token. Cached with an `ETag`, so a repeat is a cheap `304` (03).
3. **Sign in**: the authorisation code flow with PKCE, at the auth-server (06). Alice types her password into the auth-server, never into the app, and a stolen code is useless without the verifier.
4. **`POST /bookings`** with alice's token (scope `bookings`) and an `Idempotency-Key`. Validated (02); the customer id comes from the token, not the body, so she can't book as bob (06); two people racing for the last ticket can't both get it (07); if her network drops and the app retries, the key means one booking, not two (07).
5. **`GET /bookings/2`** with alice's token. Bob asking for it gets a `404`, the same as for a booking that doesn't exist (06).
6. **The scanner gets a token**: `POST /oauth2/token` with client credentials and scope `tickets:check-in` (05, 09). It can't ask for `events:write` (05), and it keeps the token for nearly five minutes (09).
7. **`POST /bookings/2/check-in`** with the scanner's token. Only the scanner's scope may call it (06). A cancelled booking is a `409`, which the scanner shows the door staff (06, 09). A retry after a timeout is a harmless `200` (06), and a `429` makes the scanner wait as long as `Retry-After` says (08, 09).
8. **`POST /bookings/2/cancel`**, if alice tries afterwards: `409`, she's already been in (06).

Any trace that names, for each step, the caller, the token and the guide's defence is a good answer. If you can explain this to someone else, you can design an API.

</details>

**Next:** that's the end of the series. Look back at the [series README](README.md) for the whole API, the three projects and how to run them together. From here, the [banking systems](../banking-systems/README.md) series builds APIs where money moves, and assumes everything you've just learned. Or, to see the other side of guide 05, the [identity provider](../identity-provider/README.md) series builds a full authorisation server from the ground up.
