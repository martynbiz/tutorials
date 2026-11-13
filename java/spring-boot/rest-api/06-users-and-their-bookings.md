# 06: Users and their bookings

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](README.md) for version notes.

## What you'll learn

- Why an app running in a browser is a **public client** that can't keep a secret, and how it gets a token for a person with the **authorisation code flow with PKCE**
- What **PKCE** prevents, and why a stolen authorisation code is useless without it
- How to add **users**, a **public client** and a **CORS** rule to the auth-server, still without writing a filter chain
- How the OpenAPI **`authorizationCode` flow** turns Swagger UI's **Authorize** button into a real sign-in
- How the API knows **which customer** is calling: the token's **`sub`** claim
- **Ownership checks**, and the vulnerability they prevent: **BOLA**, number one on the OWASP API Security Top 10
- Why someone else's booking is a **`404`**, not a `403`, and why random ids are **not** a substitute for the check
- **Mass assignment**, and why the booking request deliberately has no price or status
- **Actions** that aren't create, read, update or delete (cancel and check-in), their **state rules**, and **`409 Conflict`**
- How to test as several different users with **`jwt()`**

By the end, alice and bob can sign in through Swagger UI and book tickets. Each sees only their own bookings: bob asking for alice's booking gets exactly the same `404` as for a booking that doesn't exist. Customers can cancel their bookings until the event starts, the scanner service can check them in at the door, and an event that people have booked can no longer be deleted.

---

## Prerequisites for this guide

You need both projects as they were at the end of [05: Securing the API: services](05-securing-the-api-services.md): the `api` with all 46 tests passing, and the `auth-server` with all 5.

You also need:

- **A web browser**, for signing in through Swagger UI.
- **Port 9000 free** for the auth-server, as in guide 05.
- For the optional step 15 only, **Git Bash** (it comes with Git for Windows, and includes `openssl`), or any other Unix shell with `openssl`.

You don't need to know the authorisation code flow, PKCE or CORS. This guide teaches them.

---

## Concepts

### Tokens for a person

Guide 05's tokens belong to **services**. The organiser service asks for a token with its own id and secret, and the token's `sub` (subject) is `organiser`. Nobody else is involved.

A booking is different. It belongs to a **customer**: a person. When alice books two tickets through an app, the API needs a token that says *this request is from alice*, and alice has to prove that to someone. She's the **resource owner** from guide 05's table of OAuth roles: the bookings are hers, and she allows the app to manage them for her.

She doesn't prove it to the app, and certainly not to the API. She proves it to the **authorisation server**, by signing in there. The app never sees her password. It only gets a token, issued **for alice**, with a `sub` of `alice` and only the scopes it asked for. If the app is compromised, the attacker has a token that lasts five minutes, not alice's password.

In this series, the app is **Swagger UI**. It stands in for the customer-facing website or mobile app a real ticket seller would have, and it has the same problem they do.

### Public clients can't keep a secret

The organiser service runs on a server. Its secret lives in that server's configuration, and nobody outside can read it. OAuth calls that a **confidential client**.

Swagger UI is JavaScript. Every visitor's browser downloads it, and anyone can read what it downloaded, in the browser's developer tools. A secret put in it isn't a secret: it's published to everyone who opens the page. The same goes for a mobile app, which can be unpacked and read. OAuth calls these **public clients**, and gives them **no secret at all**.

That leaves a gap. The token endpoint has no way to check *which* program is asking. Something else has to stop a different program from collecting a token meant for this one. That something is PKCE.

### The authorisation code flow with PKCE

A browser app gets a user's token with the **authorisation code** flow ([RFC 6749 section 4.1](https://www.rfc-editor.org/rfc/rfc6749#section-4.1)), with **PKCE** (Proof Key for Code Exchange, [RFC 7636](https://www.rfc-editor.org/rfc/rfc7636), pronounced "pixy"):

```
 Swagger UI, in the browser (:8080)            auth-server (:9000)                  Tickets API (:8080)
       │                                             │                                     │
       │ 1. make up a random verifier;               │                                     │
       │    challenge = SHA-256(verifier)            │                                     │
       │                                             │                                     │
       │ 2. GET /oauth2/authorize                    │                                     │
       │    ?client_id=swagger-ui&scope=bookings     │                                     │
       │    &code_challenge=…&state=…                │                                     │
       │────────────────────────────────────────────▶│                                     │
       │ 3. login page; alice signs in               │ remembers the challenge             │
       │◀───────────────────────────────────────────▶│                                     │
       │ 4. 302 to …/swagger-ui/oauth2-redirect.html │                                     │
       │    ?code=…&state=…                          │                                     │
       │◀────────────────────────────────────────────│                                     │
       │ 5. POST /oauth2/token                       │                                     │
       │    code + verifier (no secret)              │ SHA-256(verifier) == challenge?     │
       │────────────────────────────────────────────▶│                                     │
       │ 6. {"access_token": "eyJ…"}  (sub=alice)    │                                     │
       │◀────────────────────────────────────────────│                                     │
       │                                                                                   │
       │ 7. POST /bookings                                                                 │
       │    Authorization: Bearer eyJ…                                                     │
       │──────────────────────────────────────────────────────────────────────────────────▶│
```

1. Swagger UI makes up a long random string, the **code verifier**, and keeps it to itself. It hashes it with SHA-256 to get the **code challenge**.
2. It sends the browser to the authorisation server's **authorisation endpoint**, with its client id, the scopes it wants, the **challenge** and a random **`state`** value. This is an ordinary page visit, not a background request.
3. The authorisation server shows its login page, and alice signs in **there**, on `localhost:9000`.
4. The authorisation server sends the browser back to Swagger UI's registered **redirect URI**, with a short-lived, single-use **authorisation code** in the URL, and the same `state`. Swagger UI checks the `state` matches what it sent, so it knows this answer belongs to a sign-in it started.
5. Swagger UI swaps the code for a token at the **token endpoint**. There's no secret to send. Instead it sends the **verifier**. The authorisation server hashes it and compares the result with the challenge from step 2.
6. They match, so it issues an access token **for alice**: `sub` is `alice`, `scope` is `bookings`, and `aud` is `tickets-api` (guide 05's `TokenConfig` does that for every access token).
7. Swagger UI calls the API with the token, exactly as the organiser does.

The token request in step 5 is a background `fetch()` from a page on `localhost:8080` to `localhost:9000`, which matters for CORS, below.

### What PKCE prevents

The authorisation code travels in a URL (step 4). URLs leak: into browser history, into server logs, to browser extensions, and on some phones to another app that has registered the same redirect address. Without PKCE, a public client's code would be as good as a token: whoever saw it could swap it, since there's no secret to check.

With PKCE, the code is useless on its own. Swapping it needs the **verifier**, which never left Swagger UI: only its hash travelled, and a SHA-256 hash can't be reversed. An attacker who intercepts the code gets `{"error":"invalid_grant"}`.

PKCE was invented for public clients, but [RFC 9700](https://www.rfc-editor.org/rfc/rfc9700), the current OAuth security advice, recommends it for **every** client using the authorisation code flow, confidential ones included. The auth-server will **require** it of Swagger UI, so a request without a challenge is refused.

### CORS: the browser asks the auth-server's permission

Browsers enforce the **same-origin policy**. An **origin** is a scheme, host and port together, so `http://localhost:8080` and `http://localhost:9000` are **different** origins, even on the same machine. JavaScript on a page from one origin can send a request to another, but the browser won't let it **read the response** unless the other server says, in **CORS** headers (Cross-Origin Resource Sharing), that pages from that origin are allowed to.

For some requests the browser asks before sending. Swagger UI's token request carries an `X-Requested-With: XMLHttpRequest` header, which isn't one of the few headers a browser sends cross-origin without asking. So the browser first sends a **preflight**: an `OPTIONS` request saying "a page from `http://localhost:8080` wants to `POST` here with these headers; may it?". Only if the answer is yes does it send the real request.

The auth-server from guide 05 has no CORS configuration, so it says nothing, the browser assumes no, and Swagger UI never gets its token. That's why guide 05's Authorize button couldn't work. Step 3 adds a rule to the auth-server, and only for the token endpoint:

- The **authorisation endpoint** (step 2 above) is a page the browser navigates to, not a `fetch()`, so CORS doesn't apply to it.
- The **API** doesn't need CORS for Swagger UI: Swagger UI is served by the API itself, from `localhost:8080`, so its calls to the API are same-origin. A customer website on a different origin *would* need CORS on the API, which is guide 08's job.

CORS doesn't protect the server: curl ignores it completely. It protects **users**, by stopping some other website's JavaScript from reading responses meant for them. So keep the list short: one origin, one path, one method.

### Who owns a booking: the `sub` claim

Every booking stores a **`customerId`**, and it's always taken from the token's `sub` claim, never from the request. The URL and the body say *what* the caller wants; the token says *who* the caller is, and only the token is signed by the authorisation server.

Here, `sub` is the username (`alice`), because that's what Spring's authorisation server puts there. Real identity providers usually use a stable, opaque id, such as `a3f6c1e0-…`, because usernames and email addresses change and the subject mustn't. Either way the API treats it as an opaque string and never parses it.

### BOLA: the number one API vulnerability

The [OWASP API Security Top 10](https://owasp.org/API-Security/editions/2023/en/0x11-t10/) lists the most common serious flaws in APIs. Number one, **API1:2023**, is **Broken Object Level Authorisation**, or **BOLA**:

```
GET /bookings/2
Authorization: Bearer <bob's perfectly valid token>
```

If the API looks up booking 2 and returns it, bob has just read alice's booking. His token is genuine and it has the `bookings` scope, so every check from guide 05 passes. Nothing is wrong with the token. What's missing is the question **"is this booking his?"**

Scopes can't answer it. They're coarse, as guide 05 said: `bookings` means "may manage *their* bookings", and only your code knows which bookings are theirs. So every query for a customer's data must be **scoped to that customer**:

```java
bookings.findByIdAndCustomerId(id, customerId)   // not findById(id)
```

BOLA tops the list because it's so easy to write. The endpoint works, the tests with one user pass, and the hole only shows when a second user asks for the first user's id.

### `404`, not `403`

When bob asks for alice's booking, the honest answer might seem to be `403 Forbidden`: it exists, and he may not see it. This API answers **`404 Not Found`**, with exactly the same body as for a booking that doesn't exist:

```json
{"detail":"Booking 2 was not found","instance":"/bookings/2","status":404,"title":"Not Found"}
```

A `403` would tell bob something: *booking 2 exists*. Try ids 1 to 10,000 and he'd know how many bookings there are, and roughly how fast they're being made. A `404` tells him nothing he didn't know. [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.4) allows exactly this: a server that wants to hide whether a forbidden resource exists may answer `404` instead.

It's simpler code, too. There's one query, "find booking 2 **belonging to bob**", and one outcome when it finds nothing. There's no "exists, but not yours" branch that could be written wrongly, or leak a different message or a different response time.

Compare guide 05's `403`: a valid token without the right **scope**. That's about what kind of operation the caller may do, and says nothing about any particular booking, so it's safe to say.

### Guessable ids, and why random ids aren't the fix

Booking ids are **sequential**: 1, 2, 3. If alice's booking is 2, bob can guess that 1 and 3 exist, and BOLA is one curl command away. That's why sequential ids get the blame for BOLA.

The usual suggestion is random ids, such as UUIDs. They make guessing impractical, which is worth something. But **a random id is not authorisation**. Ids leak: they're in URLs, which end up in logs, browser history, screenshots, support emails and shared links. Once someone has the id, the only thing between them and the data is the ownership check. With the check, a guessed id gets a `404`. Without it, a random id only delays the breach until the id leaks.

So this API keeps simple sequential ids and **always** checks ownership. Random ids on top would be defence in depth, and they also hide how many bookings exist. They're never the defence itself.

### Mass assignment: what the client may set

A booking has seven fields. A customer chooses two of them: **which ticket type** and **how many**. The API decides the rest: the id, who the customer is, the price, the status, and when it was made.

**Mass assignment** is what happens when an API copies whatever JSON arrives onto its objects. A client sends `{"ticketTypeId": 57, "quantity": 2, "totalPence": 1, "status": "CHECKED_IN"}` and gets two front-row tickets for a penny, already checked in. OWASP files it under **API3:2023, Broken Object Property Level Authorisation**: a caller who may change an object, but not *every property* of it.

Guide 02 already chose the defence: a **request DTO** lists exactly the fields a client may send. `BookingRequest` has `ticketTypeId` and `quantity`, and **deliberately nothing else**. Anything else in the JSON is ignored, because Spring Boot's Jackson doesn't fail on unknown properties. The price is worked out from the ticket type, the status always starts as `CONFIRMED`, and the customer comes from the token.

### Actions with state rules

Cancelling a booking and checking it in aren't create, read, update or delete. Each is an **action** with rules about when it's allowed:

| The booking is | Cancel (the customer) | Check in (the scanner) |
|---|---|---|
| `CONFIRMED`, event not started | → `CANCELLED`, tickets back on sale | → `CHECKED_IN` |
| `CONFIRMED`, event started | `409`: too late to cancel | → `CHECKED_IN` |
| `CANCELLED` | `200`, nothing changes | `409`: it was cancelled |
| `CHECKED_IN` | `409`: they've been in | `200`, nothing changes |

The API models each as a **`POST` to a sub-resource**: `POST /bookings/2/cancel` and `POST /bookings/2/check-in`. Why not something more "RESTful"?

- **`DELETE /bookings/2`** would say the booking is gone. It isn't: a cancelled booking is still a record, and `GET /bookings/2` still shows it, with status `CANCELLED`.
- **`PATCH /bookings/2` with `{"status": "CANCELLED"}`** would let the client set the status, which is mass assignment again. Every rule in the table would turn into "which status changes may a `PATCH` make?", and the check-in rule would need a different caller for the same field.

A `POST` to a named action says exactly what's happening, gets its own security rule, and gets its own documentation. Many large APIs do the same (Stripe's `POST /v1/payment_intents/{id}/cancel`, for example).

**Two answers are worth looking at closely.** A request that's valid but arrives when the booking is in the wrong state gets **`409 Conflict`**: "this conflicts with the current state of the resource". It's not the client's request that's wrong (`400`), and it's not who they are (`401`, `403`). And **repeating** an action that's already happened gets a `200` with the booking unchanged, not an error. A scanner whose network drops after checking someone in will try again, and "already checked in, fine" is the answer it needs. `POST` isn't idempotent in general, but these two actions are designed to be safe to repeat. Booking itself isn't yet: sending "book two tickets" twice still books four, until guide 07.

---

## Steps

### 1. Customers who can sign in

The first steps are in the **auth-server**. It needs people who can sign in. Create `UserConfig.java` in `com.example.authserver`:

```java
package com.example.authserver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

@Configuration
public class UserConfig {

    /**
     * The customers who can log in. DEVELOPMENT ONLY: two users with plain-text
     * passwords ({noop}), kept in memory. A real authorisation server keeps hashed
     * passwords in a database, or hands logging in to another identity provider.
     */
    @Bean
    UserDetailsService users() {
        return new InMemoryUserDetailsManager(
                User.withUsername("alice").password("{noop}password").roles("CUSTOMER").build(),
                User.withUsername("bob").password("{noop}password").roles("CUSTOMER").build());
    }
}
```

A `UserDetailsService` is the same interface the todo app used to look up users at login. `InMemoryUserDetailsManager` keeps them in a list. Two customers, so you can see one trying to read the other's bookings.

**This is development only**, like guide 05's client secrets: `{noop}` means "not hashed", the passwords are in the source code, and the users disappear when the server stops. A real authorisation server stores hashed passwords in a database, or doesn't store passwords at all and hands sign-in to another identity provider. The [identity provider](../identity-provider/README.md) series builds that properly.

`roles("CUSTOMER")` is only there because a user needs at least one authority. Roles on the authorisation server don't go into the access token: the API sees `sub` and `scope`, nothing else.

Where's the login page? Guide 05 step 3 said Spring Boot creates two filter chains for the auth-server, and the second has a **login form** for people. It was there all along, with nobody to sign in. It's Spring Security's generated form, served at `http://localhost:9000/login`. You still don't write a filter chain.

### 2. Swagger UI as a public client

Add a third client to the auth-server's `application.properties`, after the scanner:

```properties
# Swagger UI, acting for a customer: a PUBLIC client (no secret: it runs in the browser, where a
# secret can't be kept), so it must use the authorisation code flow with PKCE
spring.security.oauth2.authorizationserver.client.swagger-ui.registration.client-id=swagger-ui
spring.security.oauth2.authorizationserver.client.swagger-ui.registration.client-authentication-methods=none
spring.security.oauth2.authorizationserver.client.swagger-ui.registration.authorization-grant-types=authorization_code
spring.security.oauth2.authorizationserver.client.swagger-ui.registration.redirect-uris=http://localhost:8080/swagger-ui/oauth2-redirect.html
spring.security.oauth2.authorizationserver.client.swagger-ui.registration.scopes=bookings
spring.security.oauth2.authorizationserver.client.swagger-ui.require-proof-key=true
```

Compared with the organiser:

- **No `client-secret`**, and **`client-authentication-methods=none`**. This is what makes it a public client.
- **`authorization-grant-types=authorization_code`**: it may only get tokens through a person signing in. It can't use client credentials, which would have nobody's identity in it.
- **`redirect-uris`**: the **only** address the authorisation server will send a code to. It must match the redirect URI in the request **exactly**, character for character. Without this list, an attacker could start a sign-in with `redirect_uri=https://evil.example/` and collect the code. `oauth2-redirect.html` is a small page that comes with Swagger UI: it receives the code and passes it back to the Swagger UI page that started the sign-in.
- **`scopes=bookings`**: the most it may ask for. Swagger UI can never get `events:write`, whoever signs in.
- **`require-proof-key=true`**: PKCE is **required**. An authorisation request without a `code_challenge` is refused.

Three things you might expect, and won't see:

- **No consent screen.** Spring's authorisation server can ask "Swagger UI wants to manage your bookings: allow?", but only when a client's `require-authorization-consent` is `true`, and it defaults to `false`. A consent screen matters when the app belongs to someone else. Swagger UI here plays the ticket seller's own app.
- **No refresh token.** Spring's authorisation server doesn't give public clients one. When the five-minute token expires, Swagger UI has to go through the flow again. (While the auth-server still remembers alice's sign-in, that takes a click, not a password.)
- **No ID token.** Swagger UI doesn't ask for the `openid` scope, so this is plain OAuth, not OpenID Connect sign-in. The API only needs the access token. Guide 05's `TokenConfig` still checks the token type, so it's ready for a client that does ask for `openid`.

### 3. Let the browser call the token endpoint

Create `CorsConfig.java` in the auth-server, in `com.example.authserver`:

```java
package com.example.authserver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class CorsConfig {

    /**
     * Swagger UI runs in the browser on http://localhost:8080 and swaps the authorisation
     * code for a token with a fetch() to http://localhost:9000/oauth2/token. That's a
     * cross-origin request, so the browser blocks it unless this server says the API's
     * origin is allowed. Spring Security picks up this bean and adds CORS headers.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration swaggerUi = new CorsConfiguration();
        swaggerUi.setAllowedOrigins(List.of("http://localhost:8080"));
        swaggerUi.setAllowedMethods(List.of("POST"));
        // The headers Swagger UI's token request sends (Accept is always allowed)
        swaggerUi.setAllowedHeaders(List.of("Content-Type", "X-Requested-With"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/oauth2/token", swaggerUi);
        return source;
    }
}
```

- **`CorsConfigurationSource`** answers "which CORS rules apply to this URL?". `UrlBasedCorsConfigurationSource` maps paths to rules, and only `/oauth2/token` has one. Any other path gets no CORS headers, so browsers keep blocking cross-origin reads of it.
- **One origin**, the API's, where Swagger UI is served. Not `*`.
- **One method**, `POST`, the only one the token endpoint needs.
- **Two headers.** `Content-Type` because the request has a form body, and `X-Requested-With` because Swagger UI adds it. Leave that one out and the browser's preflight fails, even though everything else is right.

**You still don't write a filter chain.** When a bean of type `CorsConfigurationSource` exists, Spring Security's `HttpSecurity` switches CORS on and uses it, and that includes the two chains Spring Boot builds for the auth-server. The two beans from steps 1 and 3, the token customiser and the properties file are still the whole authorisation server.

Restart the auth-server and ask the preflight question yourself (in PowerShell, type `curl.exe` instead of `curl`):

```bash
curl -i -X OPTIONS -H 'Origin: http://localhost:8080' -H 'Access-Control-Request-Method: POST' -H 'Access-Control-Request-Headers: content-type,x-requested-with' http://localhost:9000/oauth2/token
```

```
HTTP/1.1 200
Access-Control-Allow-Origin: http://localhost:8080
Access-Control-Allow-Methods: POST
Access-Control-Allow-Headers: content-type, x-requested-with
Content-Length: 0
```

That's the "yes" the browser needs. The same request with `Origin: http://localhost:3000` gets a `403` with the body `Invalid CORS request`, and no `Access-Control-Allow-Origin`.

### 4. Test the auth-server

Create `src/test/java/com/example/authserver/AuthorizationCodeTest.java` in the auth-server. It plays the browser and Swagger UI, one step of the flow at a time:

```java
package com.example.authserver;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/** The authorisation code flow with PKCE, as Swagger UI uses it for a customer. */
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationCodeTest {

    static final String REDIRECT_URI = "http://localhost:8080/swagger-ui/oauth2-redirect.html";
    static final String SWAGGER_UI = "http://localhost:8080";

    // PKCE: the client makes up a random secret (the verifier) and sends only its hash (the challenge)
    static final String VERIFIER = "a-random-string-of-at-least-43-characters-made-up-by-the-client";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JwtDecoder jwtDecoder;

    // The authorisation endpoint only reads query parameters (the browser is sent there by a redirect),
    // so these requests use queryParam(), not param()

    @Test
    void someoneWhoIsNotLoggedInIsSentToTheLoginPage() {
        var response = mvc.get().uri("/oauth2/authorize")
                .accept(MediaType.TEXT_HTML)
                .queryParam("response_type", "code")
                .queryParam("client_id", "swagger-ui")
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("scope", "bookings")
                .queryParam("code_challenge", challenge(VERIFIER))
                .queryParam("code_challenge_method", "S256");

        assertThat(response).hasStatus(HttpStatus.FOUND);
        assertThat(response).hasRedirectedUrl("/login");
    }

    @Test
    void customersCanLogIn() {
        assertThat(mvc.perform(formLogin().user("alice").password("password"))).hasRedirectedUrl("/");
        assertThat(mvc.perform(formLogin().user("bob").password("password"))).hasRedirectedUrl("/");
        assertThat(mvc.perform(formLogin().user("alice").password("wrong"))).hasRedirectedUrl("/login?error");
    }

    @Test
    void aLoggedInCustomerGetsACodeAndSwapsItForATokenAboutThem() throws Exception {
        var authorize = mvc.get().uri("/oauth2/authorize")
                .with(user("alice"))
                .queryParam("response_type", "code")
                .queryParam("client_id", "swagger-ui")
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("scope", "bookings")
                .queryParam("state", "xyz")
                .queryParam("code_challenge", challenge(VERIFIER))
                .queryParam("code_challenge_method", "S256")
                .exchange();

        assertThat(authorize).hasStatus(HttpStatus.FOUND);
        String location = authorize.getResponse().getRedirectedUrl();
        assertThat(location).startsWith(REDIRECT_URI + "?code=").endsWith("&state=xyz");
        String code = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("code");

        // No client secret: Swagger UI proves it started the flow by sending the verifier
        var token = mvc.post().uri("/oauth2/token")
                .header("Origin", SWAGGER_UI)
                .param("grant_type", "authorization_code")
                .param("client_id", "swagger-ui")
                .param("code", code)
                .param("redirect_uri", REDIRECT_URI)
                .param("code_verifier", VERIFIER)
                .exchange();

        assertThat(token).hasStatusOk();
        assertThat(token).headers().hasValue("Access-Control-Allow-Origin", SWAGGER_UI);
        assertThat(token).bodyJson().extractingPath("$.scope").isEqualTo("bookings");
        assertThat(token).bodyJson().doesNotHavePath("$.refresh_token");

        Jwt jwt = jwtDecoder.decode(JsonPath.read(token.getResponse().getContentAsString(), "$.access_token"));
        assertThat(jwt.getSubject()).isEqualTo("alice");
        assertThat(jwt.getAudience()).isEqualTo(List.of("tickets-api"));
        assertThat(jwt.getClaimAsStringList("scope")).containsExactly("bookings");
    }

    @Test
    void theWrongVerifierGetsNoToken() {
        String location = mvc.get().uri("/oauth2/authorize")
                .with(user("alice"))
                .queryParam("response_type", "code")
                .queryParam("client_id", "swagger-ui")
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("scope", "bookings")
                .queryParam("code_challenge", challenge(VERIFIER))
                .queryParam("code_challenge_method", "S256")
                .exchange()
                .getResponse()
                .getRedirectedUrl();
        String code = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("code");

        // Someone who stole the code but not the verifier
        var token = mvc.post().uri("/oauth2/token")
                .param("grant_type", "authorization_code")
                .param("client_id", "swagger-ui")
                .param("code", code)
                .param("redirect_uri", REDIRECT_URI)
                .param("code_verifier", "not-the-verifier-that-matches-the-challenge-0123456789");

        assertThat(token).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(token).bodyJson().extractingPath("$.error").isEqualTo("invalid_grant");
    }

    @Test
    void swaggerUiMustUsePkce() {
        var response = mvc.get().uri("/oauth2/authorize")
                .with(user("alice"))
                .queryParam("response_type", "code")
                .queryParam("client_id", "swagger-ui")
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("scope", "bookings");

        assertThat(response).hasStatus(HttpStatus.FOUND);
        assertThat(response).redirectedUrl().contains("error=invalid_request");
    }

    @Test
    void theTokenEndpointLetsSwaggerUiCallItFromTheBrowser() {
        // The "preflight" request a browser sends before a cross-origin POST
        var allowed = mvc.options().uri("/oauth2/token")
                .header("Origin", SWAGGER_UI)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,x-requested-with");

        assertThat(allowed).hasStatusOk();
        assertThat(allowed).headers().hasValue("Access-Control-Allow-Origin", SWAGGER_UI);
        assertThat(allowed).headers().hasValue("Access-Control-Allow-Methods", "POST");

        var otherSite = mvc.options().uri("/oauth2/token")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST");

        assertThat(otherSite).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(otherSite).headers().doesNotContainHeader("Access-Control-Allow-Origin");
    }

    static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- **`challenge(...)`** is PKCE's `S256` method in Java: SHA-256 of the verifier, base64url-encoded without padding. That's what Swagger UI does in JavaScript.
- **`.with(user("alice"))`** (from spring-security-test) makes the request as a signed-in alice, so the tests can skip the login page. **`formLogin()`** tests the login page itself, with a right and a wrong password.
- **`.queryParam(...)`, not `.param(...)`,** for the authorisation endpoint. It only reads the query string, because a browser arrives there by following a link or redirect. `.param(...)` on a `GET` gave a `400` while this test was being written. The token endpoint takes a form body, so `.param(...)` is right there, as in guide 05.
- **`aLoggedInCustomerGetsACodeAndSwapsItForATokenAboutThem`** is the whole flow: a code from the redirect, a token for the code and verifier, the CORS header on the response, no refresh token, and a token whose `sub` is `alice`.
- **`theWrongVerifierGetsNoToken`** is the attack PKCE stops: someone with the code but not the verifier.
- **`swaggerUiMustUsePkce`** proves `require-proof-key=true` works. Without a challenge, the user is sent back to the client with `error=invalid_request` instead of a code.
- **`theTokenEndpointLetsSwaggerUiCallItFromTheBrowser`** checks both halves of the CORS rule: the API's origin is allowed, and another origin isn't.

Run the auth-server's tests. Eleven pass: five from guide 05 and these six.

### 5. The bookings table

Now switch to the **api** project. Create `src/main/resources/db/migration/V3__bookings.sql`:

```sql
create table booking (
    id             bigint generated by default as identity primary key,
    -- The "sub" claim of the customer's access token, such as 'alice'
    customer_id    varchar(100)             not null,
    -- No "on delete cascade": an event with bookings can't be deleted
    ticket_type_id bigint                   not null references ticket_type (id),
    quantity       int                      not null,
    total_pence    bigint                   not null,
    status         varchar(20)              not null,
    created_at     timestamp with time zone not null
);

-- GET /bookings finds a customer's bookings by customer_id
create index booking_customer_id on booking (customer_id);

-- Booking 1: alice's two Standing tickets for event 1, which has already happened
insert into booking (customer_id, ticket_type_id, quantity, total_pence, status, created_at)
values ('alice', 1, 2, 5000, 'CONFIRMED', '2025-10-20 12:00:00+00');

update ticket_type set sold = 2 where id = 1;
```

- **`customer_id`** is a string, not a foreign key. The customers live in the auth-server, not in this database. All the API knows about alice is the `sub` in her tokens.
- **`ticket_type_id`** references the ticket type, and **doesn't** cascade on delete. Guide 02's ticket types are deleted with their event (`on delete cascade`), but bookings are what customers have paid for. Step 12 refuses to delete an event with bookings, and this foreign key is the safety net under that check.
- **The index** is for `GET /bookings`, which always looks bookings up by customer.
- **Booking 1** is seeded for the past event from guide 01 (event 1, 5 November 2025). It lets you try "too late to cancel" and "can't delete an event with bookings" straight away, and it's alice's, so bob can try to read it. `sold = 2` keeps ticket type 1's count honest. Your own first booking will be number **2**.

### 6. The `Booking` entity

Create `booking/BookingStatus.java`:

```java
package com.example.tickets.booking;

public enum BookingStatus {
    CONFIRMED,
    CANCELLED,
    CHECKED_IN
}
```

And `booking/Booking.java`:

```java
package com.example.tickets.booking;

import com.example.tickets.event.TicketType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Entity
public class Booking {

    // Sequential ids (1, 2, 3...) are easy to guess. That's fine, because guessing an id
    // gets you nothing: BookingService only ever looks up the caller's own bookings.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Who made the booking: the "sub" claim of their access token. */
    private String customerId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private TicketType ticketType;

    private int quantity;

    private long totalPence;

    @Enumerated(EnumType.STRING)
    private BookingStatus status;

    private Instant createdAt;

    protected Booking() {
        // for JPA
    }

    public Booking(String customerId, TicketType ticketType, int quantity) {
        this.customerId = customerId;
        this.ticketType = ticketType;
        this.quantity = quantity;
        // Worked out here, never taken from the request
        this.totalPence = ticketType.getPricePence() * quantity;
        this.status = BookingStatus.CONFIRMED;
        // Whole seconds: the database would round nanoseconds, so the 201 and a later GET would differ
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    public Long getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public TicketType getTicketType() {
        return ticketType;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getTotalPence() {
        return totalPence;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    void cancel() {
        this.status = BookingStatus.CANCELLED;
    }

    void checkIn() {
        this.status = BookingStatus.CHECKED_IN;
    }
}
```

- **The constructor takes only what the customer chose**, plus who they are. It works out `totalPence` from the ticket type's price, and starts every booking as `CONFIRMED`. There's no other way to make a `Booking`, so there's no way to make one with a price somebody typed in.
- **No setters.** The only changes are `cancel()` and `checkIn()`, and they're package-private: only `BookingService`, in the same package, can call them, after checking the rules.
- **The price is copied** into the booking, not looked up each time. If the organiser changes the ticket price next week, alice still paid what she paid.
- **`truncatedTo(ChronoUnit.SECONDS)`.** `Instant.now()` has nanoseconds, and H2 stores microseconds. Without truncating, the `201` said `"2026-11-13T11:35:05.837945100Z"` and a `GET` of the same booking said `"…05.837945Z"`. Whole seconds come back from the database exactly as they went in.
- **`@Enumerated(EnumType.STRING)`** stores `CONFIRMED`, not `0`, so adding a status later can't silently change the meaning of existing rows.

Booking and cancelling change how many tickets are left. Add two methods to `event/TicketType.java` (trimmed: the rest is unchanged):

```java
    public void sell(int quantity) {
        this.sold += quantity;
    }

    public void release(int quantity) {
        this.sold -= quantity;
    }
```

`getAvailable()` from guide 02 is still `capacity - sold`, so the public `available` figure follows bookings and cancellations.

### 7. The repository

Create `booking/BookingRepository.java`:

```java
package com.example.tickets.booking;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /** A booking, but only if it belongs to this customer. */
    @EntityGraph(attributePaths = "ticketType")
    Optional<Booking> findByIdAndCustomerId(long id, String customerId);

    @EntityGraph(attributePaths = "ticketType")
    Page<Booking> findByCustomerId(String customerId, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "ticketType")
    Optional<Booking> findById(Long id);

    boolean existsByTicketTypeEventId(long eventId);
}
```

- **`findByIdAndCustomerId`** is the ownership check, written as a query: `where id = ? and customer_id = ?`. Someone else's booking simply isn't found. The check can't be forgotten later in the code, because it's in the only query customers' requests use.
- **`findByCustomerId`** is the list, also scoped to the customer.
- **`findById`** is overridden only to add the entity graph. It isn't scoped to a customer, and only the scanner's check-in uses it.
- **`existsByTicketTypeEventId`** is a derived query through two relationships: "does any booking's `ticketType.event.id` equal this?". Step 12 uses it.
- **`@EntityGraph(attributePaths = "ticketType")`** loads the ticket type in the same query. `spring.jpa.open-in-view=false` (since guide 01) means the response is built after the transaction has finished, so anything lazy that the response needs must already be loaded. The response also needs the event's id, but Hibernate knows the id of a lazy `Event` without loading it.

### 8. What a customer may send, and what they get back

Create `booking/BookingRequest.java`:

```java
package com.example.tickets.booking;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The body of POST /bookings: only what the customer chooses. The price, status and
 * customer are deliberately missing, so a client can't set them (mass assignment).
 */
@Schema(description = "Tickets to book. The API works out the price and who you are.")
public record BookingRequest(
        @Schema(description = "The id of a ticket type", example = "57")
        @NotNull Long ticketTypeId,

        @Schema(description = "How many tickets", example = "2")
        @NotNull @Min(1) @Max(10) Integer quantity) {
}
```

Two fields, and that's the point of this record. **Not having** `totalPence`, `status` or `customerId` here is the defence against mass assignment from the Concepts section. If a future change adds one "for convenience", every client can set it.

`Long` and `Integer`, not `long` and `int`, for the reason guide 02 gave for `venueId`: a missing wrapper is `null`, which `@NotNull` catches, while a missing primitive is a silent `0`. **`@Max(10)`** is a business limit: no one customer buys the whole front row in one go.

Create `booking/BookingResponse.java`:

```java
package com.example.tickets.booking;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "A customer's booking")
public record BookingResponse(
        @Schema(example = "2") long id,
        @Schema(example = "2") long eventId,
        @Schema(example = "57") long ticketTypeId,
        @Schema(example = "2") int quantity,
        @Schema(description = "The price of all the tickets, in pence", example = "15000") long totalPence,
        @Schema(example = "CONFIRMED") BookingStatus status,
        @Schema(example = "2030-01-02T10:15:30Z") Instant createdAt) {

    public static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getTicketType().getEvent().getId(),
                booking.getTicketType().getId(),
                booking.getQuantity(),
                booking.getTotalPence(),
                booking.getStatus(),
                booking.getCreatedAt());
    }
}
```

No `customerId`. Guide 01 said a DTO lists what's public, and this is the field it had in mind. The customer already knows who they are, and the scanner doesn't need to.

### 9. The service: ownership and state rules

The service needs an exception for "valid, but the booking (or ticket type) is in the wrong state". Create `web/ConflictException.java`:

```java
package com.example.tickets.web;

/**
 * The request is valid, but the resource is in the wrong state for it, such as booking
 * a sold-out ticket type. ApiExceptionHandler turns it into a 409 ProblemDetail.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
```

And add its handler to `web/ApiExceptionHandler.java`, next to the `NotFoundException` one (trimmed):

```java
    @ExceptionHandler(ConflictException.class)
    ProblemDetail handle(ConflictException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }
```

The title is the standard reason phrase, `"Conflict"`, like every other error's. The detail is the exception's message, which the service writes for the caller.

Now create `booking/BookingService.java`:

```java
package com.example.tickets.booking;

import com.example.tickets.event.TicketType;
import com.example.tickets.event.TicketTypeRepository;
import com.example.tickets.web.ConflictException;
import com.example.tickets.web.InvalidFieldException;
import com.example.tickets.web.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@Transactional(readOnly = true)
public class BookingService {

    private final BookingRepository bookings;
    private final TicketTypeRepository ticketTypes;

    public BookingService(BookingRepository bookings, TicketTypeRepository ticketTypes) {
        this.bookings = bookings;
        this.ticketTypes = ticketTypes;
    }

    @Transactional
    public Booking book(String customerId, BookingRequest request) {
        TicketType ticketType = ticketTypes.findById(request.ticketTypeId())
                .orElseThrow(() -> new InvalidFieldException("ticketTypeId",
                        "ticket type " + request.ticketTypeId() + " does not exist"));
        if (hasStarted(ticketType)) {
            throw new ConflictException("The event has already started");
        }
        if (ticketType.getAvailable() < request.quantity()) {
            throw new ConflictException("Only " + ticketType.getAvailable() + " tickets of this type are left");
        }
        ticketType.sell(request.quantity());
        return bookings.save(new Booking(customerId, ticketType, request.quantity()));
    }

    /** The customer's own bookings, newest first. */
    public Page<Booking> list(String customerId, Pageable pageable) {
        Pageable newestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "id"));
        return bookings.findByCustomerId(customerId, newestFirst);
    }

    /**
     * One of the customer's own bookings. Someone else's booking is "not found", exactly
     * like a booking that doesn't exist, so the answer doesn't reveal that it exists.
     */
    public Booking get(String customerId, long id) {
        return bookings.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new NotFoundException("Booking", id));
    }

    @Transactional
    public Booking cancel(String customerId, long id) {
        Booking booking = get(customerId, id);
        switch (booking.getStatus()) {
            case CANCELLED -> {
                return booking; // already done: cancelling again changes nothing
            }
            case CHECKED_IN -> throw new ConflictException(
                    "Booking " + id + " has been checked in, so it can't be cancelled");
            case CONFIRMED -> {
                if (hasStarted(booking.getTicketType())) {
                    throw new ConflictException("The event has started, so booking " + id + " can't be cancelled");
                }
                booking.cancel();
                booking.getTicketType().release(booking.getQuantity());
                return booking;
            }
        }
        throw new IllegalStateException("Unknown status " + booking.getStatus());
    }

    /** For the scanner at the door: any customer's booking. */
    @Transactional
    public Booking checkIn(long id) {
        Booking booking = bookings.findById(id).orElseThrow(() -> new NotFoundException("Booking", id));
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            throw new ConflictException("Booking " + id + " was cancelled");
        }
        // Checking in twice is fine (a scanner may retry): the booking stays CHECKED_IN
        booking.checkIn();
        return booking;
    }

    private static boolean hasStarted(TicketType ticketType) {
        return !ticketType.getEvent().getStartsAt().isAfter(Instant.now());
    }
}
```

From the top:

- **`book`** takes the `customerId` as an argument, from the controller, which gets it from the token. An unknown ticket type is a `400` on the `ticketTypeId` field, the same pattern as guide 02's unknown `venueId`. Then two state checks, each a `409`: the event mustn't have started, and there must be enough tickets left. Then it sells the tickets and saves the booking, in one transaction.
- **`list`** ignores any `sort` the client sent and always sorts newest first. `PageRequest.of(...)` keeps the client's page number and size (still capped at 100 by guide 03's `max-page-size`) and replaces the sort.
- **`get`** is the ownership check from the Concepts section. Someone else's booking and a missing booking throw the same `NotFoundException`, with the same message.
- **`cancel` calls `get`**, so it has the same ownership check: bob cancelling alice's booking also gets a `404`. Then a `switch` on the status follows the state table from the Concepts section, row by row. Cancelling puts the tickets back on sale with `release`.
- **`checkIn` uses plain `findById`.** The scanner isn't a customer and owns no bookings: it checks in *any* booking at the door, and its `tickets:check-in` scope is what allows that. Every customer-facing method goes through `get`, and the only unscoped lookup is in the one method the scanner can reach.
- **`hasStarted`** is true from the moment the event starts. Event 1 started in 2025, so you can try both "too late" rules on it.

The `switch` has no `default`. If a fourth status is ever added, the compiler warns that the `switch` doesn't cover it, and the last line catches it at run time.

**Look closely at `book`.** It reads how many tickets are left, decides, then writes. Two customers asking for the last ticket at the same moment can both read "1 left", both pass the check, and both get a booking. That's a real bug, and it's left here on purpose: guide 07 proves it with a test, then fixes it.

### 10. The controller

Create `booking/BookingController.java`:

```java
package com.example.tickets.booking;

import com.example.tickets.web.ApiProblem;
import com.example.tickets.web.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * A customer's bookings. Who the customer is comes from the access token (its "sub"
 * claim), never from the URL or the request body.
 */
@RestController
@RequestMapping("/bookings")
@Tag(name = "Bookings", description = "Book tickets, cancel them, and check them in at the door")
@ApiResponse(responseCode = "401", description = "No access token, or not a valid one",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
@ApiResponse(responseCode = "403", description = "The access token doesn't have the scope this operation needs",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
public class BookingController {

    private final BookingService bookings;

    public BookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    @PostMapping
    @SecurityRequirement(name = OpenApiConfig.OAUTH2, scopes = "bookings")
    @Operation(operationId = "createBooking", summary = "Book tickets")
    @ApiResponse(responseCode = "201", description = "Booked",
            headers = @Header(name = "Location", description = "The new booking's URL"))
    @ApiResponse(responseCode = "400", description = "Invalid request content",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "Not enough tickets left, or the event has started",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    public ResponseEntity<BookingResponse> book(@AuthenticationPrincipal Jwt token,
                                                @Valid @RequestBody BookingRequest request) {
        Booking booking = bookings.book(token.getSubject(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(booking.getId())
                .toUri();
        return ResponseEntity.created(location).body(BookingResponse.from(booking));
    }

    @GetMapping
    @SecurityRequirement(name = OpenApiConfig.OAUTH2, scopes = "bookings")
    @Operation(operationId = "listBookings", summary = "List your bookings",
            description = "Only your own, newest first. At most 100 per page.")
    @ApiResponse(responseCode = "200", description = "A page of your bookings")
    // Bookings are always newest first, so document page and size but not sort
    @Parameter(in = ParameterIn.QUERY, name = "page", description = "Which page, starting at 0",
            schema = @Schema(type = "integer", defaultValue = "0"))
    @Parameter(in = ParameterIn.QUERY, name = "size", description = "How many per page (at most 100)",
            schema = @Schema(type = "integer", defaultValue = "20"))
    public PagedModel<BookingResponse> list(@AuthenticationPrincipal Jwt token,
                                            @Parameter(hidden = true) @PageableDefault(size = 20) Pageable pageable) {
        return new PagedModel<>(bookings.list(token.getSubject(), pageable).map(BookingResponse::from));
    }

    @GetMapping("/{id}")
    @SecurityRequirement(name = OpenApiConfig.OAUTH2, scopes = "bookings")
    @Operation(operationId = "getBooking", summary = "Get one of your bookings")
    @ApiResponse(responseCode = "200", description = "The booking")
    @ApiResponse(responseCode = "404", description = "You have no booking with this id",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    public BookingResponse get(@AuthenticationPrincipal Jwt token,
                               @Parameter(description = "The booking's id", example = "2") @PathVariable long id) {
        return BookingResponse.from(bookings.get(token.getSubject(), id));
    }

    @PostMapping("/{id}/cancel")
    @SecurityRequirement(name = OpenApiConfig.OAUTH2, scopes = "bookings")
    @Operation(operationId = "cancelBooking", summary = "Cancel one of your bookings",
            description = "Cancelling a booking that's already cancelled changes nothing.")
    @ApiResponse(responseCode = "200", description = "The cancelled booking")
    @ApiResponse(responseCode = "404", description = "You have no booking with this id",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "The event has started, or the booking has been checked in",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    public BookingResponse cancel(@AuthenticationPrincipal Jwt token,
                                  @Parameter(description = "The booking's id", example = "2") @PathVariable long id) {
        return BookingResponse.from(bookings.cancel(token.getSubject(), id));
    }

    @PostMapping("/{id}/check-in")
    @SecurityRequirement(name = OpenApiConfig.OAUTH2, scopes = "tickets:check-in")
    @Operation(operationId = "checkInBooking", summary = "Check a booking in at the door",
            description = "For the scanner. Checking in a booking that's already checked in changes nothing.")
    @ApiResponse(responseCode = "200", description = "The checked-in booking")
    @ApiResponse(responseCode = "404", description = "No booking with this id",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "The booking was cancelled",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    public BookingResponse checkIn(@Parameter(description = "The booking's id", example = "2") @PathVariable long id) {
        return BookingResponse.from(bookings.checkIn(id));
    }
}
```

- **`@AuthenticationPrincipal Jwt token`** asks Spring Security for the current caller. For a request with a bearer token, that's the decoded `Jwt`, and `token.getSubject()` is its `sub`. It's the **only** place the customer's identity comes from. There's no `customerId` path variable or request field for anyone to fill in.
- **SpringDoc leaves the `Jwt` parameter out of the document** by itself, as guide 04 promised: clients don't send it as a parameter, they send a token.
- **The cancel and check-in actions** are `@PostMapping("/{id}/cancel")` and `@PostMapping("/{id}/check-in")`, with no body. They return the booking as it is afterwards, so the caller sees the new status.
- **Class-level `@ApiResponse`s** for `401` and `403` apply to every operation in the class, since every one needs a token. Each method adds the responses only it can give.
- **`@SecurityRequirement`** names the scope each operation needs: `bookings` for the customer's four, `tickets:check-in` for the scanner's one.
- **The list's parameters.** Guide 03's `@ParameterObject` would document `page`, `size` **and** `sort`, and bookings can't be sorted. So the `Pageable` is hidden with `@Parameter(hidden = true)`, and `page` and `size` are described by two method-level `@Parameter`s instead. (Hiding only `sort` with `@Parameter(name = "sort", hidden = true)` was tried first, and SpringDoc documented `sort` anyway.)

### 11. Who may call what

The controller is written, but `SecurityConfig` doesn't mention `/bookings` yet, so `anyRequest().authenticated()` lets **any** valid token in, the organiser's included. Add two rules to `security/SecurityConfig.java`, after the `/events/**` rule and before `anyRequest()` (trimmed):

```java
                        .requestMatchers("/events/**").hasAuthority("SCOPE_events:write")
                        .requestMatchers(HttpMethod.POST, "/bookings/*/check-in").hasAuthority("SCOPE_tickets:check-in")
                        .requestMatchers("/bookings/**").hasAuthority("SCOPE_bookings")
                        .anyRequest().authenticated())
```

**The order matters.** First match wins, so the check-in rule must come **before** the general `/bookings/**` rule. The other way round, a check-in request would match `/bookings/**` first: the scanner, with no `bookings` scope, would get `403`, and alice, who has it, could check **herself** in. `*` matches exactly one path segment, the booking id.

That's the split between the two layers of authorisation in this API:

| Layer | Question | Where |
|---|---|---|
| **Scope** | May this caller do this *kind* of thing? | `SecurityConfig`, before the controller runs. Fails with `403` |
| **Ownership** | Is *this* booking theirs? | `BookingService`, in the query. Fails with `404` |

A customer token passes the first for every booking, and only the second stops bob reading alice's.

### 12. Events with bookings can't be deleted

Guide 02 left a promise: `DELETE /events/1` would delete an event people have booked. Now it mustn't. In `event/EventService.java`, inject the `BookingRepository` and check before deleting (trimmed: the rest is unchanged), with imports for `com.example.tickets.booking.BookingRepository` and `com.example.tickets.web.ConflictException`:

```java
    private final EventRepository events;
    private final VenueRepository venues;
    private final BookingRepository bookings;

    public EventService(EventRepository events, VenueRepository venues, BookingRepository bookings) {
        this.events = events;
        this.venues = venues;
        this.bookings = bookings;
    }
```

```java
    @Transactional
    public void delete(long id) {
        Event event = get(id);
        // Deleting it would delete what customers have paid for
        if (bookings.existsByTicketTypeEventId(id)) {
            throw new ConflictException("Event " + id + " has bookings, so it can't be deleted");
        }
        events.delete(event);
    }
```

The foreign key from step 5 would refuse the delete anyway, but the organiser would get an unexplained server error. The check gives a `409` that says why. **Any** booking counts, cancelled ones included: they're still records of what customers did, and an event that's had bookings should be cancelled, not deleted. (Cancelling an event, and refunding everyone, is a feature this series doesn't build.)

Document the new response on `deleteEvent` in `EventController` (trimmed):

```java
    @DeleteMapping("/{id}")
    @SecurityRequirement(name = OpenApiConfig.OAUTH2, scopes = "events:write")
    @Operation(operationId = "deleteEvent", summary = "Delete an event and its ticket types",
            description = "Only while nobody has booked it.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "No event with this id",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "The event has bookings",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
```

The `401` and `403` responses after it are unchanged.

### 13. The Authorize button

Guide 05's OpenAPI document offers only the client credentials flow. Add the authorisation code flow to the same `@SecurityScheme` in `web/OpenApiConfig.java` (trimmed: the rest is unchanged):

```java
@SecurityScheme(
        name = OpenApiConfig.OAUTH2,
        type = SecuritySchemeType.OAUTH2,
        description = "Tokens from the authorisation server",
        flows = @OAuthFlows(
                // Customers: log in through the browser (Swagger UI's Authorize button)
                authorizationCode = @OAuthFlow(
                        authorizationUrl = "${spring.security.oauth2.resourceserver.jwt.issuer-uri}/oauth2/authorize",
                        tokenUrl = "${spring.security.oauth2.resourceserver.jwt.issuer-uri}/oauth2/token",
                        scopes = @OAuthScope(name = "bookings", description = "Book tickets, and see and cancel your bookings")),
                // Services: the organiser and the scanner, with a client id and secret
                clientCredentials = @OAuthFlow(
                        tokenUrl = "${spring.security.oauth2.resourceserver.jwt.issuer-uri}/oauth2/token",
                        scopes = {
                                @OAuthScope(name = "events:write", description = "Create and change events"),
                                @OAuthScope(name = "tickets:check-in", description = "Check tickets in at the door")
                        })))
```

- **One scheme, two flows.** It's still the same authorisation server and the same kind of token. The scheme lists every way to get one, and each operation's `@SecurityRequirement` names the scope it needs.
- **`authorizationUrl`** is where the browser goes to sign in (step 2 of the flow), and **`tokenUrl`** is where the code is swapped (step 5). Both follow `issuer-uri`, as in guide 05.

Then tell Swagger UI which client it is. Add to the API's `application.properties`:

```properties
# Swagger UI's Authorize button logs customers in as the public "swagger-ui" client, with PKCE
springdoc.swagger-ui.oauth.client-id=swagger-ui
springdoc.swagger-ui.oauth.use-pkce-with-authorization-code-grant=true
```

The first fills in the client id for you. The second makes Swagger UI do PKCE: make a verifier, send the challenge, send the verifier with the code. Without it, Swagger UI would ask for the code without a challenge, and step 2's `require-proof-key=true` would refuse it.

You don't configure the **redirect URI**. SpringDoc works it out from the address you opened Swagger UI on: `http://localhost:8080/swagger-ui/oauth2-redirect.html`. It has to match the registered one exactly, so **open Swagger UI on `localhost:8080`**, not `127.0.0.1:8080`. That page is under `/swagger-ui/**`, which guide 05 made public, so it loads without a token.

### 14. Try it in Swagger UI

Start the **auth-server first**, then the API, and open `http://localhost:8080/swagger-ui.html`. There's a new **Bookings** section, and every booking operation has a padlock.

Click **Authorize**. Here's what to expect. (The flow behind it was checked step by step with curl and in the tests. The browser screens themselves are Swagger UI's and Spring Security's standard ones, so the exact wording and layout depend on their versions.)

1. **The Authorize dialog** lists the `oauth2` scheme once for each flow: `authorizationCode` and `clientCredentials`. Use the **authorizationCode** one. Its client id is already filled in as `swagger-ui`. If there's a client secret field, leave it empty: this client has no secret.
2. **Tick the `bookings` scope**, and click that section's **Authorize** button.
3. **The auth-server's login page** opens, on `localhost:9000`, in a new window or tab. It's Spring Security's plain generated form, with **Username** and **Password** fields. Sign in as **`alice`**, password **`password`**.
4. **No consent screen**: step 2 didn't ask for one. The auth-server sends the browser straight back to Swagger UI's `oauth2-redirect.html` with a code. That page hands the code to Swagger UI and closes, and Swagger UI swaps it for a token in the background. This is the request that needed CORS.
5. **Back in the dialog**, the authorizationCode section now shows you're authorised, with a **Logout** button. Close the dialog. The padlocks are now shown locked.

Now use the API as alice. Open **`POST /bookings`**, click **Try it out**, and send:

```json
{"ticketTypeId": 57, "quantity": 2}
```

Ticket type 57 is guide 02's Front Row ticket for event 2: £75, and only two of them.

```
HTTP/1.1 201
Location: http://localhost:8080/bookings/2

{"id":2,"eventId":2,"ticketTypeId":57,"quantity":2,"totalPence":15000,"status":"CONFIRMED","createdAt":"2026-11-13T11:35:05Z"}
```

`totalPence` is 2 × 7500, worked out by the API. Your `createdAt` will differ. Under the response, Swagger UI shows the request as a **curl** command, `Authorization: Bearer eyJ…` header included. That's alice's token, and it's the easiest way to get one for curl.

Try **`GET /bookings`** as alice:

```json
{"content":[{"id":2,"eventId":2,"ticketTypeId":57,"quantity":2,"totalPence":15000,"status":"CONFIRMED","createdAt":"2026-11-13T11:35:05Z"},{"id":1,"eventId":1,"ticketTypeId":1,"quantity":2,"totalPence":5000,"status":"CONFIRMED","createdAt":"2025-10-20T12:00:00Z"}],"page":{"size":20,"number":0,"totalElements":2,"totalPages":1}}
```

Her new booking and the seeded one, newest first.

**Now be bob.** Clicking **Logout** in the dialog only makes Swagger UI forget alice's token. The auth-server still remembers alice signed in (with a session cookie), so authorising again would quietly give you another token for **alice**. The simplest way to be someone else is a **private (incognito) window**, which has its own cookies: open `http://localhost:8080/swagger-ui.html` there, authorise, and sign in as **`bob`**, password **`password`**.

As bob, try `GET /bookings/2`, alice's booking:

```
HTTP/1.1 404
Content-Type: application/problem+json

{"detail":"Booking 2 was not found","instance":"/bookings/2","status":404,"title":"Not Found"}
```

Then `GET /bookings/999`. The body says `Booking 999 was not found`, and otherwise it's the same: bob can't tell a booking that's alice's from one that doesn't exist. His list is empty:

```json
{"content":[],"page":{"size":20,"number":0,"totalElements":0,"totalPages":0}}
```

And `POST /bookings` with `{"ticketTypeId": 57, "quantity": 1}`: alice has both front-row tickets.

```
HTTP/1.1 409
Content-Type: application/problem+json

{"detail":"Only 0 tickets of this type are left","instance":"/bookings","status":409,"title":"Conflict"}
```

**Back as alice**, try to cancel booking 1, the seeded one for the event that has already happened (`POST /bookings/{id}/cancel` with id `1`):

```json
{"detail":"The event has started, so booking 1 can't be cancelled","instance":"/bookings/1/cancel","status":409,"title":"Conflict"}
```

**Mass assignment.** As alice, book one ticket and try to set what only the API should:

```json
{"ticketTypeId": 3, "quantity": 1, "totalPence": 1, "status": "CHECKED_IN", "customerId": "bob"}
```

You get a `201`, with `"totalPence":2500`, `"status":"CONFIRMED"`, and a booking that's in alice's list, not bob's. The extra fields were never read.

> **The clientCredentials section of the dialog is still for curl.** This guide sets Swagger UI up for customers only. The CORS rule from step 3 allows exactly what Swagger UI's authorisation code request sends, and nothing more. More to the point, typing a service's client secret into a web page is exactly what the Concepts section said not to do. Get the organiser's and scanner's tokens with curl or `requests.http`, as in guide 05.

**The scanner and the organiser, with curl.** Put alice's token (copied from Swagger UI's curl command) in a variable, and get the scanner's and the organiser's as in guide 05. In Git Bash:

```bash
ALICE=eyJraWQ...
```

```bash
curl -u scanner:scanner-secret -d grant_type=client_credentials -d scope=tickets:check-in http://localhost:9000/oauth2/token
```

```bash
SCANNER_TOKEN=eyJraWQ...
```

(In PowerShell, `$ALICE = "eyJraWQ..."` and so on. None of the requests below has a JSON body, so they all work with `curl.exe`.) Check booking 2 in:

```bash
curl -i -X POST -H "Authorization: Bearer $SCANNER_TOKEN" localhost:8080/bookings/2/check-in
```

```
HTTP/1.1 200

{"id":2,"eventId":2,"ticketTypeId":57,"quantity":2,"totalPence":15000,"status":"CHECKED_IN","createdAt":"2026-11-13T11:35:05Z"}
```

Send it again, and you get the same `200` and the same body. Now alice can't cancel:

```bash
curl -i -X POST -H "Authorization: Bearer $ALICE" localhost:8080/bookings/2/cancel
```

```json
{"detail":"Booking 2 has been checked in, so it can't be cancelled","instance":"/bookings/2/cancel","status":409,"title":"Conflict"}
```

And the organiser can't delete event 1, which has a booking (with a fresh `$ORGANISER_TOKEN`, as in guide 05):

```bash
curl -i -X DELETE -H "Authorization: Bearer $ORGANISER_TOKEN" localhost:8080/events/1
```

```json
{"detail":"Event 1 has bookings, so it can't be deleted","instance":"/events/1","status":409,"title":"Conflict"}
```

Event 2 can't be deleted now either.

Your token only lasts five minutes. When requests start failing with `401`, authorise again in Swagger UI: while the auth-server remembers you, it won't ask for the password.

### 15. The same flow with curl (optional)

Swagger UI hides the protocol behind one button. If you'd like to see every request it makes, here they are, one at a time. This needs **Git Bash** (or another Unix shell) with `openssl`. Each command is one line.

**The verifier and the challenge.** A random verifier, and its SHA-256 hash in base64url, without padding:

```bash
VERIFIER=$(openssl rand -base64 48 | tr '+/' '-_' | tr -d '=\r\n')
```

```bash
CHALLENGE=$(printf '%s' "$VERIFIER" | openssl dgst -sha256 -binary | openssl base64 | tr '+/' '-_' | tr -d '=\r\n')
```

The `\r` in `tr -d '=\r\n'` matters on Windows, where `openssl` ends its output with a carriage return. Left in, it ends up inside the URL below, and curl fails without a helpful message.

**The authorisation request**, as the browser would send it. `-c jar` and `-b jar` keep cookies in a file called `jar`, as a browser would, and `Accept: text/html` says "I'm a browser":

```bash
curl -i -c jar -H 'Accept: text/html' "http://localhost:9000/oauth2/authorize?response_type=code&client_id=swagger-ui&redirect_uri=http://localhost:8080/swagger-ui/oauth2-redirect.html&scope=bookings&state=abc123&code_challenge=$CHALLENGE&code_challenge_method=S256"
```

```
HTTP/1.1 302
Set-Cookie: JSESSIONID=15C6AFAF8B7499534F570ACA41236905; Path=/; HttpOnly
Location: http://localhost:9000/login
```

Not signed in, so it's off to the login page. The session cookie is how the auth-server will remember the request while you sign in.

**The login page.** It has a CSRF token, because this *is* a cookie-based login form, the kind guide 05 said needs one:

```bash
curl -s -b jar -c jar http://localhost:9000/login | grep _csrf
```

```
<input name="_csrf" type="hidden" value="ss0aqrIMCjvdgSlkThRI…" />
```

**Sign in**, with the whole `_csrf` value in place of `PASTE_CSRF`:

```bash
curl -i -b jar -c jar -d username=alice -d password=password -d _csrf=PASTE_CSRF http://localhost:9000/login
```

```
HTTP/1.1 302
Location: http://localhost:9000/oauth2/authorize?response_type=code&client_id=swagger-ui&…&code_challenge_method=S256&continue
```

Signed in, and sent back to the authorisation request it saved. **Follow that `Location`**, pasting it whole:

```bash
curl -i -b jar -c jar 'PASTE_THE_LOCATION'
```

```
HTTP/1.1 302
Location: http://localhost:8080/swagger-ui/oauth2-redirect.html?code=Dig8NnD-8twX1TNzHACqagfd3LS-iR0Wn1bf…&state=abc123
```

There's the code, on its way to the redirect URI, with the `state` you sent. **Swap it**, with the verifier, from the "browser's" origin:

```bash
CODE=PASTE_THE_CODE
```

```bash
curl -i -H 'Origin: http://localhost:8080' -H 'X-Requested-With: XMLHttpRequest' -d grant_type=authorization_code -d client_id=swagger-ui -d code=$CODE -d redirect_uri=http://localhost:8080/swagger-ui/oauth2-redirect.html -d code_verifier=$VERIFIER http://localhost:9000/oauth2/token
```

```
HTTP/1.1 200
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Access-Control-Allow-Origin: http://localhost:8080
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Content-Type: application/json;charset=UTF-8

{"access_token":"eyJraWQiOiI4MDEzZWIz…","scope":"bookings","token_type":"Bearer","expires_in":299}
```

- **No client secret.** The verifier is the proof.
- **`Access-Control-Allow-Origin`** is the CORS header from step 3, which lets the browser hand this response to Swagger UI. curl doesn't care, but a browser would.
- **No `refresh_token`**, as step 2 said.

Decode the token in JShell, as in guide 05:

```json
{"sub":"alice","aud":"tickets-api","nbf":1794567829,"scope":["bookings"],"iss":"http://localhost:9000","exp":1794568129,"iat":1794567829,"jti":"c1a9fba2-…"}
```

`sub` is `alice`. Run the same command again, with the same code: `400 {"error":"invalid_grant"}`. A code works once. The same answer comes back for the right code with the wrong verifier, which is the attack PKCE stops.

To get bob's token, delete the `jar` file (or the auth-server will remember alice) and start again from the verifier.

### 16. Customers in `requests.http`

IntelliJ's HTTP client can't sign in through a login page for you, so `requests.http` takes alice's token as a variable you paste in. Add to the end of the API's `requests.http`:

```http
### ---------- Customers (guide 06) ----------
### Log in through Swagger UI (http://localhost:8080/swagger-ui.html, Authorize, authorizationCode,
### tick "bookings", alice / password). Swagger UI shows each request as curl, token included:
### paste the token between "Bearer " and the quote here.
@aliceToken = paste-alices-token-here

### Book two Front Row tickets for event 2
POST http://localhost:8080/bookings
Authorization: Bearer {{aliceToken}}
Content-Type: application/json

{"ticketTypeId": 57, "quantity": 2}

### Your bookings, newest first
GET http://localhost:8080/bookings
Authorization: Bearer {{aliceToken}}

### One of your bookings (someone else's is a 404)
GET http://localhost:8080/bookings/2
Authorization: Bearer {{aliceToken}}

### Cancel a booking (again: still 200, nothing changes)
POST http://localhost:8080/bookings/2/cancel
Authorization: Bearer {{aliceToken}}

### Booking 1 is for an event that has already happened: cancelling is a 409
POST http://localhost:8080/bookings/1/cancel
Authorization: Bearer {{aliceToken}}

### ---------- The scanner service ----------
POST http://localhost:9000/oauth2/token
Authorization: Basic scanner scanner-secret
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&scope=tickets:check-in

> {% client.global.set("scannerToken", response.body.access_token); %}

### Check a booking in (again: still 200, the same answer)
POST http://localhost:8080/bookings/2/check-in
Authorization: Bearer {{scannerToken}}
```

**`@aliceToken = …`** declares a variable in the file itself. Paste a fresh token over the placeholder every five minutes. The scanner's token is fetched with the same response handler as guide 05's organiser token.

### 17. Tests

Run the API's tests now. All 46 still pass: nothing old broke. Now test the new rules, as several different users.

`jwt()` from guide 05 can be anyone you like: the subject is just a claim. Add customers and the scanner to `TestTokens.java` (trimmed: `organiser()` is unchanged):

```java
    /** A customer, logged in through Swagger UI: a user token with the bookings scope. */
    public static JwtRequestPostProcessor customer(String name) {
        return jwt().jwt(token -> token
                .subject(name)
                .claim("scope", "bookings"));
    }

    public static JwtRequestPostProcessor alice() {
        return customer("alice");
    }

    public static JwtRequestPostProcessor bob() {
        return customer("bob");
    }

    /** The scanner service at the door: a client-credentials token with tickets:check-in. */
    public static JwtRequestPostProcessor scanner() {
        return jwt().jwt(token -> token
                .subject("scanner")
                .claim("scope", "tickets:check-in"));
    }
```

`customer(name)` makes a token for any customer, so a test can use people nobody else's tests have booked for.

All the test classes share one database, so tests can't rely on how many front-row tickets other tests have already sold. Each test makes its **own** ticket type. Create a helper, `src/test/java/com/example/tickets/booking/Bookings.java`:

```java
package com.example.tickets.booking;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.io.UnsupportedEncodingException;

import static com.example.tickets.TestTokens.organiser;

/**
 * Test helpers. All test classes share one database, so each test makes its own ticket
 * type instead of relying on how many seeded tickets other tests have already booked.
 */
final class Bookings {

    private Bookings() {
    }

    /** A new ticket type for event 4 (in 2030), at £10 a ticket. */
    static long newTicketType(MockMvcTester mvc, int capacity) {
        var response = mvc.post().uri("/events/4/ticket-types").with(organiser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Test", "pricePence": 1000, "capacity": %d}
                        """.formatted(capacity))
                .exchange();
        return id(response.getResponse().getContentAsByteArray());
    }

    /** Books tickets and returns the new booking's id. */
    static long book(MockMvcTester mvc, JwtRequestPostProcessor customer, long ticketTypeId, int quantity) {
        var response = mvc.post().uri("/bookings").with(customer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(ticketTypeId, quantity))
                .exchange();
        return id(response.getResponse().getContentAsByteArray());
    }

    static String request(long ticketTypeId, int quantity) {
        return """
                {"ticketTypeId": %d, "quantity": %d}
                """.formatted(ticketTypeId, quantity);
    }

    static int available(MockMvcTester mvc, long ticketTypeId) {
        var response = mvc.get().uri("/events/4/ticket-types/{id}", ticketTypeId).exchange();
        return JsonPath.read(new String(response.getResponse().getContentAsByteArray()), "$.available");
    }

    private static long id(byte[] json) {
        Number id = JsonPath.read(new String(json), "$.id");
        return id.longValue();
    }
}
```

(The `UnsupportedEncodingException` import is unused; you can leave it out.) `newTicketType` uses the organiser's token, as the organiser service would. `available` reads the public ticket type, so tests check the effect of booking and cancelling the way a client would see it.

Create `booking/BookingApiTest.java` in the test sources:

```java
package com.example.tickets.booking;

import com.example.tickets.TicketsIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static com.example.tickets.TestTokens.alice;
import static com.example.tickets.TestTokens.bob;
import static com.example.tickets.TestTokens.customer;
import static com.example.tickets.TestTokens.organiser;
import static com.example.tickets.booking.Bookings.available;
import static com.example.tickets.booking.Bookings.book;
import static com.example.tickets.booking.Bookings.newTicketType;
import static com.example.tickets.booking.Bookings.request;
import static org.assertj.core.api.Assertions.assertThat;

@TicketsIntegrationTest
class BookingApiTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void booksTicketsAndWorksOutThePrice() {
        long ticketType = newTicketType(mvc, 10);

        // exchange() sends the request once. Without it, every assertThat(response) below
        // would send it again, and book another 3 tickets
        var response = mvc.post().uri("/bookings").with(alice())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(ticketType, 3))
                .exchange();

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).headers().hasHeaderSatisfying("Location",
                values -> assertThat(values.getFirst()).matches("http://localhost/bookings/\\d+"));
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"eventId": 4, "ticketTypeId": %d, "quantity": 3, "totalPence": 3000, "status": "CONFIRMED"}
                """.formatted(ticketType));
        assertThat(available(mvc, ticketType)).isEqualTo(7);
    }

    @Test
    void ignoresAPriceOrStatusInTheRequest() {
        long ticketType = newTicketType(mvc, 10);

        // Mass assignment: the client tries to set fields that only the API should set
        var response = mvc.post().uri("/bookings").with(alice())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"ticketTypeId": %d, "quantity": 2, "totalPence": 1, "status": "CHECKED_IN", "customerId": "bob"}
                        """.formatted(ticketType))
                .exchange();

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"quantity": 2, "totalPence": 2000, "status": "CONFIRMED"}
                """);
        String location = response.getResponse().getHeader("Location");
        assertThat(mvc.get().uri(location).with(alice())).hasStatusOk();
        assertThat(mvc.get().uri(location).with(bob())).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void someoneElsesBookingIsNotFound() {
        long id = book(mvc, alice(), newTicketType(mvc, 10), 1);

        var asAlice = mvc.get().uri("/bookings/{id}", id).with(alice()).exchange();
        var asBob = mvc.get().uri("/bookings/{id}", id).with(bob()).exchange();
        var missing = mvc.get().uri("/bookings/{id}", 999_999).with(bob()).exchange();

        assertThat(asAlice).hasStatusOk();
        // 404, not 403: a 403 would tell bob that booking exists
        assertThat(asBob).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(asBob).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(asBob).bodyJson().extractingPath("$.detail").isEqualTo("Booking " + id + " was not found");
        assertThat(missing).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void bobCannotCancelAlicesBooking() {
        long id = book(mvc, alice(), newTicketType(mvc, 10), 1);

        assertThat(mvc.post().uri("/bookings/{id}/cancel", id).with(bob())).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/bookings/{id}", id).with(alice()))
                .bodyJson().extractingPath("$.status").isEqualTo("CONFIRMED");
    }

    @Test
    void listsOnlyTheCallersBookingsNewestFirst() {
        long ticketType = newTicketType(mvc, 10);
        long first = book(mvc, customer("carol"), ticketType, 1);
        long second = book(mvc, customer("carol"), ticketType, 2);
        book(mvc, customer("dave"), ticketType, 1);

        var response = mvc.get().uri("/bookings").with(customer("carol")).exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.content[*].id")
                .asArray().containsExactly((int) second, (int) first);
        assertThat(response).bodyJson().extractingPath("$.page.totalElements").isEqualTo(2);
    }

    @Test
    void cannotBookMoreTicketsThanAreLeft() {
        long ticketType = newTicketType(mvc, 2);
        book(mvc, alice(), ticketType, 1);

        var response = mvc.post().uri("/bookings").with(bob())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(ticketType, 2))
                .exchange();

        assertThat(response).hasStatus(HttpStatus.CONFLICT);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"title": "Conflict", "status": 409, "detail": "Only 1 tickets of this type are left"}
                """);
        assertThat(available(mvc, ticketType)).isEqualTo(1);
    }

    @Test
    void cannotBookAnEventThatHasStarted() {
        // Ticket type 1 is the Standing ticket for event 1, which was in 2025
        var response = mvc.post().uri("/bookings").with(alice())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(1, 1))
                .exchange();

        assertThat(response).hasStatus(HttpStatus.CONFLICT);
        assertThat(response).bodyJson().extractingPath("$.detail").isEqualTo("The event has already started");
    }

    @Test
    void rejectsAnInvalidBooking() {
        var invalid = mvc.post().uri("/bookings").with(alice())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"quantity": 0}
                        """)
                .exchange();
        var unknownTicketType = mvc.post().uri("/bookings").with(alice())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(999_999, 1))
                .exchange();

        assertThat(invalid).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(invalid).bodyJson().isLenientlyEqualTo("""
                {"errors": {"quantity": "must be greater than or equal to 1", "ticketTypeId": "must not be null"}}
                """);
        assertThat(unknownTicketType).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(unknownTicketType).bodyJson().isLenientlyEqualTo("""
                {"errors": {"ticketTypeId": "ticket type 999999 does not exist"}}
                """);
    }

    @Test
    void bookingNeedsACustomerToken() {
        var noToken = mvc.get().uri("/bookings").exchange();
        var organiserToken = mvc.get().uri("/bookings").with(organiser()).exchange();

        assertThat(noToken).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(organiserToken).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(organiserToken).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }
}
```

- **`.exchange()` on every booking request.** This one is a trap worth knowing. A `MockMvcTester` request that hasn't been `exchange()`d is sent **again for every `assertThat(...)`** on it. Guides 02 to 05 never noticed, because sending a create or a `GET` twice had no visible effect there. Here, without `exchange()`, `booksTicketsAndWorksOutThePrice` makes three bookings of 3 tickets and fails with `expected: 7 but was: 1`. Send once, then assert on the result.
- **`someoneElsesBookingIsNotFound`** is the BOLA test: the same booking as its owner (`200`) and as someone else (`404`, with the same detail as any missing booking), and a booking that doesn't exist (`404`).
- **`ignoresAPriceOrStatusInTheRequest`** is the mass assignment test. It also proves `customerId` in the body was ignored: the booking is alice's and not bob's.
- **`listsOnlyTheCallersBookingsNewestFirst`** uses carol and dave, who appear in no other test, so the list contains exactly what this test made. `isEqualTo(2)` on `totalElements` would be fragile for alice, who has bookings from every other test.
- **`bookingNeedsACustomerToken`** checks the scope rule: no token is `401`, and the organiser's genuine token without `bookings` is `403`.

Create `booking/BookingStateApiTest.java` for the state rules:

```java
package com.example.tickets.booking;

import com.example.tickets.TicketsIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static com.example.tickets.TestTokens.alice;
import static com.example.tickets.TestTokens.scanner;
import static com.example.tickets.booking.Bookings.available;
import static com.example.tickets.booking.Bookings.book;
import static com.example.tickets.booking.Bookings.newTicketType;
import static org.assertj.core.api.Assertions.assertThat;

/** Cancelling and checking in: what's allowed depends on the booking's status. */
@TicketsIntegrationTest
class BookingStateApiTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void cancellingPutsTheTicketsBackOnSale() {
        long ticketType = newTicketType(mvc, 5);
        long id = book(mvc, alice(), ticketType, 2);
        assertThat(available(mvc, ticketType)).isEqualTo(3);

        var response = mvc.post().uri("/bookings/{id}/cancel", id).with(alice()).exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED");
        assertThat(available(mvc, ticketType)).isEqualTo(5);
    }

    @Test
    void cancellingTwiceChangesNothing() {
        long ticketType = newTicketType(mvc, 5);
        long id = book(mvc, alice(), ticketType, 2);
        mvc.post().uri("/bookings/{id}/cancel", id).with(alice()).exchange();

        var again = mvc.post().uri("/bookings/{id}/cancel", id).with(alice()).exchange();

        assertThat(again).hasStatusOk();
        assertThat(again).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED");
        assertThat(available(mvc, ticketType)).isEqualTo(5); // not 7
    }

    @Test
    void cannotCancelAfterTheEventHasStarted() {
        // Booking 1 is alice's, for event 1, which was in 2025 (seeded by V3__bookings.sql)
        var response = mvc.post().uri("/bookings/1/cancel").with(alice()).exchange();

        assertThat(response).hasStatus(HttpStatus.CONFLICT);
        assertThat(response).bodyJson().extractingPath("$.detail")
                .isEqualTo("The event has started, so booking 1 can't be cancelled");
    }

    @Test
    void cannotCancelAfterCheckingIn() {
        long id = book(mvc, alice(), newTicketType(mvc, 5), 1);
        mvc.post().uri("/bookings/{id}/check-in", id).with(scanner()).exchange();

        var response = mvc.post().uri("/bookings/{id}/cancel", id).with(alice()).exchange();

        assertThat(response).hasStatus(HttpStatus.CONFLICT);
        assertThat(response).bodyJson().extractingPath("$.detail")
                .isEqualTo("Booking " + id + " has been checked in, so it can't be cancelled");
    }

    @Test
    void theScannerChecksABookingIn() {
        long id = book(mvc, alice(), newTicketType(mvc, 5), 2);

        var response = mvc.post().uri("/bookings/{id}/check-in", id).with(scanner()).exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"id": %d, "quantity": 2, "status": "CHECKED_IN"}
                """.formatted(id));
    }

    @Test
    void checkingInTwiceGivesTheSameAnswer() throws Exception {
        long id = book(mvc, alice(), newTicketType(mvc, 5), 2);
        String first = mvc.post().uri("/bookings/{id}/check-in", id).with(scanner())
                .exchange().getResponse().getContentAsString();

        var again = mvc.post().uri("/bookings/{id}/check-in", id).with(scanner()).exchange();

        assertThat(again).hasStatusOk();
        assertThat(again).bodyJson().isStrictlyEqualTo(first);
    }

    @Test
    void cannotCheckInACancelledBooking() {
        long id = book(mvc, alice(), newTicketType(mvc, 5), 1);
        mvc.post().uri("/bookings/{id}/cancel", id).with(alice()).exchange();

        var response = mvc.post().uri("/bookings/{id}/check-in", id).with(scanner()).exchange();

        assertThat(response).hasStatus(HttpStatus.CONFLICT);
        assertThat(response).bodyJson().extractingPath("$.detail").isEqualTo("Booking " + id + " was cancelled");
    }

    @Test
    void checkingInNeedsTheScannersScope() {
        long id = book(mvc, alice(), newTicketType(mvc, 5), 1);

        assertThat(mvc.post().uri("/bookings/{id}/check-in", id).with(alice())).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.post().uri("/bookings/{id}/check-in", 999_999).with(scanner())).hasStatus(HttpStatus.NOT_FOUND);
    }
}
```

One test per cell of the state table that matters, plus the scope rule:

- **`cancellingTwiceChangesNothing`** checks the tickets were released **once**: 5 available, not 7.
- **`checkingInTwiceGivesTheSameAnswer`** compares the two bodies with **`isStrictlyEqualTo`**: every field, the same. That's what a scanner retrying after a network error needs.
- **`cannotCancelAfterTheEventHasStarted`** uses the seeded booking 1. It's safe to share, because the request is refused, so booking 1 never changes.
- **`checkingInNeedsTheScannersScope`** guards step 11's rule order: alice's token, which has `bookings`, can't check in. Its second line shows the scanner gets an honest `404` for a booking that doesn't exist: it may see every booking, so there's nothing to hide.

Two more tests go in existing classes. In `EventWriteApiTest`, with `import static com.example.tickets.TestTokens.organiser;` already there from guide 05:

```java
    @Test
    void anEventWithBookingsCannotBeDeleted() {
        // Event 1 has booking 1 (seeded by V3__bookings.sql)
        var response = mvc.delete().uri("/events/1").with(organiser());

        assertThat(response).hasStatus(HttpStatus.CONFLICT);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"title": "Conflict", "status": 409, "detail": "Event 1 has bookings, so it can't be deleted"}
                """);
        assertThat(mvc.get().uri("/events/1")).hasStatusOk();
    }
```

The last line checks the event is really still there. A delete that's refused must change nothing.

And in `OpenApiDocumentTest`:

```java
    @Test
    void customersLogInWithTheAuthorizationCodeFlow() {
        var doc = mvc.get().uri("/v3/api-docs");

        assertThat(doc).bodyJson()
                .extractingPath("$.components.securitySchemes.oauth2.flows.authorizationCode.authorizationUrl")
                .isEqualTo("http://localhost:9000/oauth2/authorize");
        assertThat(doc).bodyJson().extractingPath("$.paths['/bookings'].post.security[0].oauth2")
                .isEqualTo(List.of("bookings"));
        assertThat(doc).bodyJson().extractingPath("$.paths['/bookings/{id}/check-in'].post.security[0].oauth2")
                .isEqualTo(List.of("tickets:check-in"));
        // The token (@AuthenticationPrincipal) isn't a parameter clients send, and bookings can't be sorted
        assertThat(doc).bodyJson().extractingPath("$.paths['/bookings'].get.parameters[*].name")
                .isEqualTo(List.of("page", "size"));
    }
```

The last assertion pins down two things from step 10: the `Jwt` parameter is hidden, and `sort` isn't offered. Run all the API's tests: 65 pass.

---

## Your turn

### Exercise 1: the seeded booking is alice's

Write a test in `BookingApiTest` for booking 1, the one `V3__bookings.sql` seeds. Alice should see it, with the values from the migration. Bob should get a `404` whose detail is exactly what he'd get for a booking that doesn't exist. Use `.exchange()` where you make more than one assertion on a response.

<details>
<summary>Solution</summary>

```java
@Test
void theSeededBookingIsOnlyAlices() {
    var asAlice = mvc.get().uri("/bookings/1").with(alice()).exchange();
    var asBob = mvc.get().uri("/bookings/1").with(bob()).exchange();

    assertThat(asAlice).hasStatusOk();
    assertThat(asAlice).bodyJson().isLenientlyEqualTo("""
            {"id": 1, "eventId": 1, "ticketTypeId": 1, "quantity": 2, "totalPence": 5000}
            """);
    assertThat(asBob).hasStatus(HttpStatus.NOT_FOUND);
    assertThat(asBob).bodyJson().extractingPath("$.detail").isEqualTo("Booking 1 was not found");
}
```

It's safe to rely on booking 1 in a shared database: the only other test that touches it, `cannotCancelAfterTheEventHasStarted`, is refused, so it never changes. The status is left out of the comparison anyway, so the test doesn't depend on that.

`"Booking 1 was not found"` is the same text `NotFoundException` produces for an id that was never used. That's the whole point: bob can't use the answer to learn whether booking 1 exists.

</details>

### Exercise 2: bob's evening, in Swagger UI

In a private window, sign in to Swagger UI as **bob**. Before each request, predict the status and, where there is one, the booking's `status` or the problem's `detail`. Then run it.

1. Book **one** ticket of ticket type **3** (the Standing ticket for event 3).
2. `GET /events/3/ticket-types/3`. What's `available`?
3. Cancel the booking, then read the ticket type again.
4. Cancel it a second time, then read the ticket type again.
5. Try `POST /bookings/{id}/check-in` on it, with bob's token.

<details>
<summary>Solution</summary>

1. `201`, with `"totalPence":2500` and `"status":"CONFIRMED"`. The `Location` has bob's new booking's id.
2. `available` is one less than before. If nobody else has booked ticket type 3, that's `199` (capacity 200). If you tried mass assignment with alice in step 14, it's `198`.
3. `200`, `"status":"CANCELLED"`, and `available` goes back up by one: `release` gave the ticket back.
4. `200` again, still `"CANCELLED"`, and `available` **doesn't** change. The `CANCELLED` row of the state table returns the booking as it is, without releasing the tickets a second time.
5. `403 Forbidden`. Bob's token has `bookings`, not `tickets:check-in`, and step 11's first booking rule is checked before `/bookings/**`. The request never reaches the controller, so it doesn't matter that the booking is cancelled. With the scanner's token, the same request would reach the service and get a `409`: `Booking N was cancelled`.

</details>

### Exercise 3: the scanner can't cancel

The scanner can check in any booking. Can it cancel one? Write a test in `BookingStateApiTest` that answers the question, and checks the booking is unchanged afterwards. Then, without running it, say what would happen to the check-in tests if the two booking rules in `SecurityConfig` were the other way round.

<details>
<summary>Solution</summary>

```java
@Test
void theScannerCannotCancel() {
    long id = book(mvc, alice(), newTicketType(mvc, 5), 1);

    assertThat(mvc.post().uri("/bookings/{id}/cancel", id).with(scanner())).hasStatus(HttpStatus.FORBIDDEN);
    assertThat(mvc.get().uri("/bookings/{id}", id).with(alice()))
            .bodyJson().extractingPath("$.status").isEqualTo("CONFIRMED");
}
```

Add `import static com.example.tickets.TestTokens.alice;` if it isn't there. `/bookings/2/cancel` doesn't match `/bookings/*/check-in`, so it falls to `/bookings/**`, which needs `SCOPE_bookings`. The scanner's token only has `SCOPE_tickets:check-in`: `403`.

With the rules swapped, **every** check-in request matches `/bookings/**` first and needs `SCOPE_bookings`. The scanner gets `403`, so `theScannerChecksABookingIn`, `checkingInTwiceGivesTheSameAnswer` and `cannotCheckInACancelledBooking` fail. `cannotCancelAfterCheckingIn` fails too: its check-in is quietly refused, so the cancel succeeds. And `checkingInNeedsTheScannersScope` fails on its first line, which is the worst news of all: alice's token has `bookings`, so her check-in is allowed, and a customer could check themselves in. That's why that test exists.

</details>

---

## Checkpoint

In the **auth-server**:

```bash
./mvnw test
```

```
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
```

(5 from guide 05 and 6 in `AuthorizationCodeTest`.)

In the **api**:

```bash
./mvnw test
```

```
[INFO] Tests run: 65, Failures: 0, Errors: 0, Skipped: 0
```

(46 from guide 05, 9 in `BookingApiTest`, 8 in `BookingStateApiTest`, and 1 more each in `EventWriteApiTest` and `OpenApiDocumentTest`. With the tests from exercises 1 and 3, you'll have 67.) As before, neither project needs the other running for its tests.

New files in the auth-server:

```
src/main/java/com/example/authserver/
├── CorsConfig.java
└── UserConfig.java
src/test/java/com/example/authserver/
└── AuthorizationCodeTest.java
```

Modified in the auth-server: `application.properties` (the `swagger-ui` client).

New files in the api:

```
src/main/java/com/example/tickets/
├── booking/
│   ├── Booking.java
│   ├── BookingController.java
│   ├── BookingRepository.java
│   ├── BookingRequest.java
│   ├── BookingResponse.java
│   ├── BookingService.java
│   └── BookingStatus.java
└── web/
    └── ConflictException.java
src/main/resources/db/migration/
└── V3__bookings.sql
src/test/java/com/example/tickets/
└── booking/
    ├── BookingApiTest.java
    ├── BookingStateApiTest.java
    └── Bookings.java
```

Modified in the api: `TicketType.java` (`sell` and `release`), `EventService.java` (409 on delete), `EventController.java` (409 documented), `SecurityConfig.java` (booking rules), `ApiExceptionHandler.java` (`ConflictException`), `OpenApiConfig.java` (authorisation code flow), `application.properties` (Swagger UI's client), `requests.http`, `TestTokens.java`, `EventWriteApiTest.java`, `OpenApiDocumentTest.java`.

---

## Common errors

**Swagger UI's Authorize fails after you sign in, with an error such as `Auth ErrorTypeError: Failed to fetch`, and the browser's console mentions CORS**
The browser blocked the token request. `CorsConfig` is missing, or doesn't match what the browser sends: the origin must be exactly `http://localhost:8080`, and the allowed headers must include `X-Requested-With`. Try the preflight with curl (step 3). A `403` with the body `Invalid CORS request` and no `Access-Control-Allow-Origin` means the auth-server said no. (The exact wording in the browser depends on the browser and Swagger UI versions.)

**Swagger UI was opened on `http://127.0.0.1:8080`, and signing in doesn't come back**
Two things are now wrong. The redirect URI SpringDoc works out is `http://127.0.0.1:8080/swagger-ui/oauth2-redirect.html`, which isn't the one registered for `swagger-ui`, so the authorisation server won't send a code to it. And the origin is `http://127.0.0.1:8080`, which `CorsConfig` doesn't allow. Open `http://localhost:8080/swagger-ui.html` instead.

**Signing in as bob gives you alice's bookings**
The auth-server still remembers alice's sign-in, with a session cookie, so Authorize gave you another token for her without asking. Swagger UI's **Logout** only forgets the token. Use a private window for the second user (step 14), or delete the `jar` file if you're using curl (step 15).

**Authorize sends you back to Swagger UI with `error=invalid_request` instead of a code**
The authorisation request had no PKCE challenge, and the client requires one. Check `springdoc.swagger-ui.oauth.use-pkce-with-authorization-code-grant=true` in the API's `application.properties`. In curl, check `code_challenge` and `code_challenge_method=S256` are in the URL.

**`{"error":"invalid_grant"}` from the token endpoint**
The code has already been used (each works once), it has expired, or the `code_verifier` doesn't match the challenge it was issued for. Start the flow again from the authorisation request, with the same `VERIFIER` you made the challenge from.

**curl to `/oauth2/authorize` gets a `401` instead of a redirect to the login page**
In full:

```
HTTP/1.1 401
WWW-Authenticate: Bearer resource_metadata="http://localhost:9000/.well-known/oauth-protected-resource"
```

The authorisation server only sends **browsers** to the login page, and decides you're a browser by `Accept: text/html`. Add `-H 'Accept: text/html'`.

**The curl flow fails silently right after making the challenge**
On Windows, `openssl` ends its output with `\r`, and that carriage return ended up inside `$CHALLENGE` and so inside the URL. Use the `tr -d '=\r\n'` from step 15, which removes it.

**`403` on a booking request with a valid token**
The token doesn't have the scope the operation needs. The organiser's and the scanner's tokens don't have `bookings`, and a customer's doesn't have `tickets:check-in`. Decode the token and look at `scope`.

**`404` for a booking you've just made**
You're asking with a different user's token from the one that made it. A booking is only found for the `sub` that booked it. Decode both tokens and compare `sub`.

**`409` with `Event 2 has bookings, so it can't be deleted`**
Working as intended (step 12). Even cancelled bookings count. Delete an event nobody has booked, or one you've just created.

**A booking test fails with `expected: 7 but was: 1`, or books more tickets than it should**
An assertion re-sent the request. Without `.exchange()`, every `assertThat(response)` on a `MockMvcTester` request sends it again, which books again. Add `.exchange()` and assert on the result (step 17).

**In `AuthorizationCodeTest`, the authorisation request gets a `400`**
It was built with `.param(...)`. The authorisation endpoint reads only the query string, so use `.queryParam(...)` for it. The token endpoint takes a form body, so `.param(...)` is right there.

---

> **Sidebar: taking payment.** Every booking in this API is `CONFIRMED` the moment it's made, and nobody pays. That's deliberate: payments are a big subject of their own, and they'd bury this guide's lessons. A real ticket seller would change three things. **Bookings would start as `PENDING_PAYMENT`**, holding the tickets, and move to `CONFIRMED` only when the payment provider (Stripe, Adyen, a bank) says the money arrived, usually through a **webhook**: a request from the provider to your API, which you must check really came from them. **Holds would expire**: a customer who abandons the checkout mustn't keep the last front-row seats forever, so a pending booking that isn't paid within, say, ten minutes is cancelled and its tickets released. And **the API would never see a card number**: the customer types it into the provider's own hosted page or component, and your API only receives a reference to the payment. That keeps card data, and most of the rules about handling it, out of your systems. The state rules in this guide would grow (no checking in a booking that isn't paid, refunds when a paid booking is cancelled), and guide 07's idempotency keys would matter even more: "charge the customer" must never happen twice because a request was retried. The [card authorisation](../banking-systems/card-authorisation/README.md) tutorial covers holds that expire, from the bank's side, and the [payments](../banking-systems/payments/README.md) tutorial covers a payment's lifecycle and asynchronous answers from the other side.

---

## Recap

- A token for a **person** comes from the **authorisation code flow**: the person signs in **at the authorisation server**, and the app gets a code, then a token whose `sub` is that person. The app never sees the password.
- An app in a browser is a **public client**: it can't keep a secret, so it has none. **PKCE** takes the secret's place: only the app that made the verifier can swap the code, so a stolen code is useless. Require it.
- The auth-server needed two beans: **users** (in memory, development only) and a **`CorsConfigurationSource`**, which Spring Security picks up without a filter chain. CORS lets Swagger UI's JavaScript on `localhost:8080` read the token response from `localhost:9000`. Keep it to one origin, one path, one method.
- The OpenAPI **`authorizationCode` flow** and two SpringDoc properties make Swagger UI's **Authorize** button sign customers in, with PKCE.
- The customer's identity comes **only** from the token: `@AuthenticationPrincipal Jwt`, then `getSubject()`. Never from the URL or the body.
- **BOLA** (OWASP API1) is the number one API vulnerability. Every customer query is **scoped to the customer** (`findByIdAndCustomerId`). Someone else's booking is a **`404`**, identical to a missing one, so the answer reveals nothing. Random ids make guessing harder but are **not** authorisation.
- **Mass assignment** (OWASP API3): the request DTO has only what the client may choose. The price, status and owner are set by the API.
- **Actions** like cancel and check-in are `POST`s to sub-resources, with **state rules**. A valid request in the wrong state is **`409 Conflict`**. Repeating an action that's already happened is a harmless `200`.
- Two layers of authorisation: **scopes** in `SecurityConfig` (`403`), and **ownership** in the service's queries (`404`). Rule **order** in `SecurityConfig` matters.
- With **`jwt()`**, a test can be any user: give each test its own users and its own data in a shared database, and `.exchange()` any request that changes something.

### Interview angle

<details>
<summary>"What is BOLA, and how do you prevent it?"</summary>

Broken Object Level Authorisation, number one on the OWASP API Security Top 10. The API checks that the caller is authenticated and has the right kind of permission, but not that the specific object they asked for, such as `/bookings/2`, is one they're allowed to touch. So any user can read or change any other user's data by changing an id. You prevent it by checking ownership on every access to a user's object, ideally in the query itself (`where id = ? and customer_id = ?`), with the owner taken from the authenticated identity, never from the request. And you test it with two users: user B asking for user A's object.

</details>

<details>
<summary>"Someone requests a resource that exists but belongs to another user. Do you return 403 or 404?"</summary>

Usually `404`, the same response as for a resource that doesn't exist. A `403` confirms the resource exists, and with guessable ids that lets an attacker map out what's there. RFC 9110 explicitly allows a server to answer `404` to hide a forbidden resource's existence. It's also simpler: one query scoped to the owner, one "not found" outcome. `403` is right when the refusal doesn't reveal anything about a particular object, such as a token without the required scope.

</details>

<details>
<summary>"Would switching to UUIDs fix BOLA?"</summary>

No. Random ids make guessing impractical, but ids leak: in URLs, logs, browser history, shared links and support tickets. Once someone has the id, only an ownership check stops them. Random ids are a reasonable extra layer, and they also hide how many records you have, but they're never a substitute for authorisation.

</details>

<details>
<summary>"What is PKCE, and why do public clients need it?"</summary>

Proof Key for Code Exchange. The client makes up a random code verifier, sends its SHA-256 hash (the code challenge) with the authorisation request, and sends the verifier itself when it swaps the code for a token. The authorisation server checks they match. A public client, such as a browser app or mobile app, can't keep a client secret, so without PKCE anyone who intercepted the authorisation code from the redirect could swap it for a token. With PKCE, the code is useless without the verifier, which never left the client. Current guidance (RFC 9700) recommends PKCE for confidential clients too.

</details>

<details>
<summary>"Why did the authorisation server need CORS, and the API didn't?"</summary>

Swagger UI is served by the API on `localhost:8080`, so its requests to the API are same-origin. But it swaps the authorisation code for a token with a `fetch()` to `localhost:9000`, a different origin, and the browser won't let the page read that response unless the authorisation server allows `http://localhost:8080` with CORS headers. The sign-in itself is a page navigation, which CORS doesn't apply to. A browser app served from a different origin would need CORS on the API as well. CORS protects users' data from other sites' scripts; it doesn't protect the server, since non-browser clients ignore it.

</details>

<details>
<summary>"What is mass assignment, and how do you avoid it?"</summary>

Binding a request body directly onto an internal object, so a client can set fields it was never meant to, such as a price, a status, an owner or an admin flag. OWASP now files it under API3, Broken Object Property Level Authorisation. The defence is a request DTO with only the fields the client may set, so everything else is set by the server: computed (the price), defaulted (the status) or taken from the token (the owner). A test that sends the forbidden fields and checks they were ignored keeps it that way.

</details>

<details>
<summary>"How would you model 'cancel a booking' in a REST API?"</summary>

As an action on the booking, `POST /bookings/{id}/cancel`, returning the booking with its new status. `DELETE` would suggest the booking is gone, when it's really a record that changes state. `PATCH` with a new status would let clients set the status field directly, and turn the business rules into rules about which field changes are allowed. The action gets its own security rule and documentation, answers `409 Conflict` when the booking's state doesn't allow it, and is safe to repeat: cancelling a cancelled booking returns `200` and changes nothing.

</details>

### Further reading

- [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x11-t10/), especially [API1: Broken Object Level Authorization](https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/) and [API3: Broken Object Property Level Authorization](https://owasp.org/API-Security/editions/2023/en/0xa3-broken-object-property-level-authorization/)
- [RFC 6749 section 4.1: the authorisation code grant](https://www.rfc-editor.org/rfc/rfc6749#section-4.1) and [RFC 7636: PKCE](https://www.rfc-editor.org/rfc/rfc7636)
- [RFC 9700: OAuth 2.0 Security Best Current Practice](https://www.rfc-editor.org/rfc/rfc9700), on PKCE, redirect URIs and public clients
- [OAuth 2.0 for Browser-Based Applications](https://datatracker.ietf.org/doc/draft-ietf-oauth-browser-based-apps/), the IETF's guidance for apps like Swagger UI
- [MDN: Cross-Origin Resource Sharing (CORS)](https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS), including preflight requests
- [Spring Security reference: CORS](https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html)
- [RFC 9110 section 15.5.4: 403 Forbidden](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.4), and when a `404` may be sent instead
- [SpringDoc properties](https://springdoc.org/#properties), including the `springdoc.swagger-ui.oauth.*` ones

**Simplification to notice:** `BookingService.book` reads how many tickets are left, checks, then writes. Two customers asking for the last ticket at the same moment can both see "1 left", and both get it. The tests never notice, because they send one request at a time.

**Next:** [07: Concurrency and retries](07-concurrency-and-retries.md). A test that oversells the last ticket, and the fix. Organisers who overwrite each other's changes, stopped with `ETag` and `If-Match`. And idempotency keys, so a booking retried after a network error never books twice.
