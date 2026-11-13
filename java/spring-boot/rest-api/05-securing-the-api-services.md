# 05: Securing the API: services

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](README.md) for version notes.

## What you'll learn

- Why an API uses **access tokens** instead of a login form, and the four **OAuth 2.0 roles**, applied to this system
- How a service gets a token for itself with the **client credentials** flow
- How to build a minimal **authorisation server** with Spring Boot: a properties file and one bean
- What's inside a **JWT** access token, and how to read one safely
- How the API, as a **resource server**, checks every token: **signature**, **issuer**, **audience** and **expiry**
- **Scopes**, and how Spring Security turns them into `SCOPE_` **authorities**
- **401 vs 403**, both as `ProblemDetail`s like every other error
- How to test secured endpoints with spring-security-test's **`jwt()`**, without a running authorisation server
- How to describe the security in the **OpenAPI document**
- What changes when you add security to an API that already sets caching headers

By the end, anyone can still browse venues and events, but only a caller holding a token with the `events:write` scope can create, change or delete them. The token comes from your own authorisation server, and the API refuses one that's forged, expired, or issued for some other API.

---

## Prerequisites for this guide

You need the `api` project as it was at the end of [04: Documenting the API](04-documenting-the-api.md), with all 40 tests passing.

From the [todo app](../todo-app/README.md) series, you should know the basics of Spring Security:

- Requests pass through a **filter chain** before they reach a controller, and Spring Security is a filter in that chain.
- A **`SecurityFilterChain` bean** is configured with `HttpSecurity`, using lambdas: `authorizeHttpRequests`, `requestMatchers`, `permitAll`, `authenticated`.
- **Authentication** (who are you?) is different from **authorisation** (what may you do?).

You don't need to know OAuth 2.0 or JWTs. This guide teaches them.

**Port 9000 must be free.** The authorisation server runs on it. If you've done the [identity provider](../identity-provider/README.md) series, its `idp` (Passport) uses port 9000 too: stop it first.

---

## Concepts

### Who may change events?

Since guide 01, anyone who can reach the API can delete every event in it. The README's table says who should be able to do what:

| Caller | May |
|---|---|
| Anyone | Read venues, events and ticket types |
| The **organiser service** | Create, change and delete events and ticket types |
| A **customer**, through an app | Book tickets, and see and cancel their own bookings (guide 06) |
| The **scanner service** at the door | Check bookings in (guide 06) |

The todo app answered "who is this?" with a login form and a session cookie. That works for a person in a browser. It doesn't work here: the organiser service is a program running on a server, with no browser, no person to type a password, and no reason to keep a session. It needs to send something with **each request** that proves who it is and what it's allowed to do. That something is an **access token**.

### OAuth 2.0 and its four roles

**OAuth 2.0** ([RFC 6749](https://www.rfc-editor.org/rfc/rfc6749)) is the standard way to issue and use access tokens. It splits the work between four roles:

| OAuth role | What it does | In this system |
|---|---|---|
| **Resource owner** | Owns the data, and can allow an app to use it | A **customer**, who owns their bookings (guide 06) |
| **Client** | An application that wants to call the API | The **organiser service** and the **scanner service**. From guide 06, also **Swagger UI**, acting for a customer |
| **Authorisation server** | Checks who's asking, and **issues** tokens | **`auth-server`**, a new project you build in this guide, on port 9000 |
| **Resource server** | **Accepts** tokens, and serves the data | The **Tickets API**, on port 8080 |

The split is the point. The API never sees a password or a client secret, and never issues a token. Its whole security job is, for each request: *is this token genuine, still valid, meant for me, and does it allow this?* Everything about *who* gets a token, and how they prove it, lives in one place: the authorisation server. Many APIs can trust one authorisation server.

> **The identity provider series** builds a full authorisation server by hand, with users, consent screens, a database and multi-factor sign-in. You don't need it for this series. If you've done it, the auth-server here is a much smaller relative of Passport.

### The client credentials flow

OAuth has several **flows** (also called grant types): ways for a client to get a token. Which one depends on *whose* token it is.

The organiser service acts **for itself**. No customer is involved, so nobody needs to sign in or approve anything. It uses the simplest flow, **client credentials** ([RFC 6749 section 4.4](https://www.rfc-editor.org/rfc/rfc6749#section-4.4)):

```
 Organiser service                     auth-server (:9000)                 Tickets API (:8080)
       │                                      │                                    │
       │  1. POST /oauth2/token               │                                    │
       │     client id + secret               │                                    │
       │     grant_type=client_credentials    │                                    │
       │     scope=events:write               │                                    │
       │─────────────────────────────────────▶│                                    │
       │                                      │ checks the secret,                 │
       │  2. {"access_token": "eyJ…",         │ and that this client may           │
       │      "expires_in": 300}              │ have events:write                  │
       │◀─────────────────────────────────────│                                    │
       │                                                                           │
       │  3. POST /events                                                          │
       │     Authorization: Bearer eyJ…                                            │
       │──────────────────────────────────────────────────────────────────────────▶│
       │                                                                           │ checks the token
       │  4. 201 Created                                                           │ (no call to the
       │◀──────────────────────────────────────────────────────────────────────────│  auth-server)
```

1. The client sends its **client id** and **client secret** (a password for an application) to the authorisation server's **token endpoint**, and says which **scopes** it wants.
2. The authorisation server checks both, and returns an access token that lasts a few minutes.
3. The client sends the token with every API request, in the `Authorization` header.
4. The API checks the token, and does the work.

The client keeps using the same token until it expires, then asks for another. Customers in guide 06 use a different flow, **authorisation code with PKCE**, because a person has to sign in, and the app acting for them must never see their password.

### Access tokens and JWTs

An access token is sent like this ([RFC 6750](https://www.rfc-editor.org/rfc/rfc6750)):

```
Authorization: Bearer eyJraWQiOiI0NzU0N2QyZi03MDNkLT...
```

**Bearer** means *whoever holds this token may use it*. There's no further proof. That's why tokens are short-lived (five minutes here) and why, in a real system, they only ever travel over HTTPS.

The tokens in this series are **JWTs** (JSON Web Tokens, [RFC 7519](https://www.rfc-editor.org/rfc/rfc7519)): three base64url-encoded parts joined by dots, `header.payload.signature`. Decoded, the first two parts of a real organiser token look like this:

```json
{"kid": "47547d2f-703d-4064-a797-8e515c00e429", "alg": "RS256"}
```

```json
{"sub": "organiser", "aud": "tickets-api", "nbf": 1794560384, "scope": ["events:write"], "iss": "http://localhost:9000", "exp": 1794560684, "iat": 1794560384, "jti": "b3e2aa6a-dbe2-4581-96b7-6bc6eba8e793"}
```

The payload's fields are called **claims**:

| Claim | Meaning | What the API does with it |
|---|---|---|
| `iss` | **Issuer**: which authorisation server made the token | Must be exactly the one it trusts, `http://localhost:9000` |
| `aud` | **Audience**: which API the token is for | Must include `tickets-api`. A token for another API is refused |
| `exp` | **Expires**, in seconds since 1970 | Must be in the future (with 60 seconds' allowance for clocks that disagree) |
| `nbf`, `iat` | **Not before** and **issued at** | `nbf` must be in the past |
| `sub` | **Subject**: who the token acts for. For client credentials, the client itself | Guide 06 uses it to know which customer is calling |
| `scope` | What the token **allows** | Used for authorisation |
| `jti` | A unique id for this token | Not used here |

The header's **`alg`** says how it was signed: **RS256**, an RSA signature. The authorisation server signs with its **private** key, which never leaves it. Anyone can check the signature with the matching **public** key, which the authorisation server publishes at its **JWKS** endpoint (JSON Web Key Set). **`kid`** (key id) says which of the published keys to use.

The signature is what makes the rest trustworthy. Anyone can base64-encode some JSON that says `"scope": ["events:write"]`. Only the authorisation server can sign it.

### How the API checks a token

The API needs one setting to trust the authorisation server: the **issuer URI**. From that, Spring Boot does the rest:

1. It fetches the authorisation server's **metadata** (a JSON document at a well-known URL under the issuer), which says where the JWKS endpoint is.
2. It fetches the **public keys** from the JWKS endpoint, and caches them.
3. For each request with a token, it checks the **signature** with the key named by `kid`, then the **issuer**, the **timestamps** and (with one more setting) the **audience**.

No call to the authorisation server per request: once it has the keys, the API checks tokens on its own. That's fast, and it keeps working if the authorisation server has a short outage. The cost is that a JWT **can't be withdrawn** before it expires: nothing is looked up, so a stolen token works until `exp`. That's why access tokens are short-lived.

**Why check the audience?** A company's authorisation server usually issues tokens for many APIs. Without an audience check, a token issued for some other API (an internal reporting service, say) would work here too, if its scopes happened to match. With it, each API only accepts tokens made for it.

### Scopes and authorities

A **scope** is a permission the client asks for and is granted. This system has three, and this guide uses the first:

| Scope | For |
|---|---|
| `events:write` | The organiser service: create, change and delete events and ticket types |
| `tickets:check-in` | The scanner service: check bookings in (guide 06) |
| `bookings` | Customers: book and manage their own tickets (guide 06) |

Spring Security makes decisions with **authorities** (`GrantedAuthority`), the same idea as the roles in the todo app. Its resource server support turns each scope in the token into an authority named **`SCOPE_` + the scope**:

| In the token | Authority |
|---|---|
| `"scope": ["events:write"]` | `SCOPE_events:write` |
| `"scope": ["tickets:check-in"]` | `SCOPE_tickets:check-in` |
| no `scope` claim | none |

So the security rule for writing events is `hasAuthority("SCOPE_events:write")`. You don't write anything to get this mapping: it's Spring Security's default, done by a class called `JwtGrantedAuthoritiesConverter`. It accepts the claim as a JSON array (as here) or a space-separated string (as some other authorisation servers send it). You'd only configure it if a provider puts its permissions somewhere else (the sidebar at the end has examples).

Scopes are coarse on purpose: "this client may change events", not "this client may change event 12". Finer rules, such as "a customer may only see their own bookings", are your code's job. Guide 06 writes them.

### 401 or 403?

Two different refusals, and clients need to tell them apart:

| Status | Means | What fixes it |
|---|---|---|
| **`401 Unauthorized`** | "I don't know who you are." No token, or one that's forged, expired, malformed or for another API | Get a (new) valid token |
| **`403 Forbidden`** | "I know who you are, and you may not do this." A valid token without the scope | A new token won't help, unless it has a different scope |

(Yes, HTTP's name for 401 is "Unauthorized" even though it's about *authentication*. It's a historical accident that everyone lives with.) Both responses also carry a **`WWW-Authenticate`** header, which RFC 6750 defines, saying what went wrong in a form programs can read.

---

## Steps

### 1. Generate the auth-server project

The authorisation server is a separate application, as it would be in real life: a different team owns it, and many APIs trust it.

In IntelliJ: **File → New → Project → Spring Boot** (or use [start.spring.io](https://start.spring.io) and unzip the result):

| Setting | Value |
|---|---|
| Location | `tutorials/workspace/rest-api/auth-server` |
| Language / Type | Java, Maven |
| Group | `com.example` |
| Artifact | `auth-server` |
| Package name | `com.example.authserver` |
| JDK / Java | 25 |
| Spring Boot | 4.1.2 |
| Dependencies | **OAuth2 Authorization Server**, **Spring Web** |

The main class is `AuthServerApplication`. Delete the generated test class under `src/test`: step 5 replaces it with real tests.

The dependencies in `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-oauth2-authorization-server</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-oauth2-authorization-server-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc-test</artifactId>
    <scope>test</scope>
</dependency>
```

The authorisation server is part of Spring Security 7. The first starter brings it, and the Nimbus library that creates and signs JWTs.

### 2. Configure the issuer and the clients

Replace the auth-server's `src/main/resources/application.properties`:

```properties
spring.application.name=auth-server
server.port=9000

# Who signs the tokens. Every token's "iss" claim, and the API's configuration, must match it exactly.
spring.security.oauth2.authorizationserver.issuer=http://localhost:9000

# DEVELOPMENT ONLY: {noop} means the secret is stored as plain text. Real secrets are hashed, and never committed.

# The organiser service: creates and changes events
spring.security.oauth2.authorizationserver.client.organiser.registration.client-id=organiser
spring.security.oauth2.authorizationserver.client.organiser.registration.client-secret={noop}organiser-secret
spring.security.oauth2.authorizationserver.client.organiser.registration.client-authentication-methods=client_secret_basic
spring.security.oauth2.authorizationserver.client.organiser.registration.authorization-grant-types=client_credentials
spring.security.oauth2.authorizationserver.client.organiser.registration.scopes=events:write

# The scanner service: checks tickets in at the door (used from guide 06)
spring.security.oauth2.authorizationserver.client.scanner.registration.client-id=scanner
spring.security.oauth2.authorizationserver.client.scanner.registration.client-secret={noop}scanner-secret
spring.security.oauth2.authorizationserver.client.scanner.registration.client-authentication-methods=client_secret_basic
spring.security.oauth2.authorizationserver.client.scanner.registration.authorization-grant-types=client_credentials
spring.security.oauth2.authorizationserver.client.scanner.registration.scopes=tickets:check-in
```

**The issuer** is the authorisation server's identity. It goes into every token's `iss`, and the API will be configured with the same string. They must match **character for character**: `http://127.0.0.1:9000` is a different issuer from `http://localhost:9000`, even though both reach the same server.

**Each client** is a block of properties under `client.<key>.registration`, where `<key>` is just a name for the block. A registration says:

- **`client-id`** and **`client-secret`**: the client's username and password, in effect.
- **`client-authentication-methods`**: how it sends them. `client_secret_basic` is an HTTP Basic `Authorization` header, the same as `curl -u`.
- **`authorization-grant-types`**: which flows it may use. Both services may only use `client_credentials`.
- **`scopes`**: the **most** it may ask for. The organiser can never get `tickets:check-in`, and the scanner can never get `events:write`.

**`{noop}` is for development only.** Spring Security stores secrets with a prefix naming how they were hashed, and `{noop}` means "not hashed at all". That keeps this guide readable. A real system stores a **hash** (for example `{bcrypt}$2a$10$…`), keeps it out of source control (in environment variables or a secrets manager), and for important clients avoids shared secrets altogether, with stronger client authentication such as a signed JWT (`private_key_jwt`) or a client certificate.

The scanner is registered now, so you can see two clients with different permissions. It gets an endpoint to call in guide 06, and its own application in guide 09.

One more property is worth knowing, though you don't need it yet. Access tokens last **five minutes** by default, and each client can change that:

```properties
spring.security.oauth2.authorizationserver.client.organiser.token.access-token-time-to-live=10s
```

### 3. What Spring Boot does for you

Run `AuthServerApplication` now (the green arrow next to `main`, or `./mvnw spring-boot:run` in the auth-server folder). It starts on port 9000, and it already works as an authorisation server. You haven't written a single security class. Here's what Spring Boot set up, because you have the starter and the properties:

- **Two security filter chains.** The first handles the protocol endpoints: it calls `http.oauth2AuthorizationServer(...)` (the method the [README](README.md#versions) mentions) to add the token endpoint, the JWKS endpoint, the metadata endpoints and the rest, with OpenID Connect switched on. Clients authenticate there with their secrets. The second chain covers everything else, with a login form for people, which this guide doesn't use.
- **A signing key.** An RSA key pair, generated **in memory** at start-up. The public half is published at `/oauth2/jwks`. Because it's in memory, **every restart makes a new key**, and tokens signed with the old one stop working.
- **The client registrations**, from your properties, held in memory.

Spring Boot only does this when *you* haven't defined a `SecurityFilterChain` bean of your own. Write one, and it backs off, leaving you to write both chains yourself.

That's a reasonable trade for this series: the chains Boot creates are the standard ones, the same ones you'd write by hand. If you want to see them written out and explained line by line, [identity provider 01](../identity-provider/01-oauth2-oidc-and-a-first-idp.md) does exactly that. It's optional reading.

Read the metadata (in PowerShell, type `curl.exe` instead of `curl` throughout):

```bash
curl http://localhost:9000/.well-known/oauth-authorization-server
```

Trimmed and formatted:

```
{
    "issuer": "http://localhost:9000",
    "authorization_endpoint": "http://localhost:9000/oauth2/authorize",
    "token_endpoint": "http://localhost:9000/oauth2/token",
    "token_endpoint_auth_methods_supported": ["client_secret_basic","client_secret_post","client_secret_jwt","private_key_jwt","tls_client_auth","self_signed_tls_client_auth"],
    "jwks_uri": "http://localhost:9000/oauth2/jwks",
    "response_types_supported": ["code"],
    "grant_types_supported": ["authorization_code","client_credentials","refresh_token","urn:ietf:params:oauth:grant-type:token-exchange"],
    "revocation_endpoint": "http://localhost:9000/oauth2/revoke",
    "introspection_endpoint": "http://localhost:9000/oauth2/introspect",
    ...
}
```

This document ([RFC 8414](https://www.rfc-editor.org/rfc/rfc8414)) is how anyone, including the API in a few steps' time, finds out where everything is from the issuer alone. `jwks_uri` is where the public keys are. Open it in a browser to see your server's current key.

### 4. Tokens for the tickets API: the audience

Get a token as the organiser:

```bash
curl -u organiser:organiser-secret -d grant_type=client_credentials -d scope=events:write http://localhost:9000/oauth2/token
```

```json
{"access_token":"eyJraWQiOiI0NzU0N2QyZi03MDNkLT...","scope":"events:write","token_type":"Bearer","expires_in":300}
```

- **`-u organiser:organiser-secret`** sends the client id and secret as HTTP Basic authentication (`client_secret_basic`).
- **`-d`** sends form fields, as `application/x-www-form-urlencoded`, which is what a token endpoint expects (not JSON).
- **`expires_in`** is in seconds: five minutes.
- **`scope`** in the *response* is a space-separated string. Inside the token, it's an array.

**Decode it** to see what's inside. You don't need a website for that, and you shouldn't use one for any token that matters: a token pasted into someone else's page is a token you've given away, and it works for whoever holds it until it expires. Decode it on your own machine instead. Start JShell, which comes with the JDK (type `jshell` in a terminal), and paste:

```java
var token = "PASTE_THE_ACCESS_TOKEN_HERE"
new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]))
```

`[1]` is the payload; `[0]` is the header. The payload:

```json
{"sub": "organiser", "aud": "organiser", ...}
```

Look at **`aud`**: it's `organiser`, the client's own id. That's Spring's default, and it's no use to the API, which wants to know the token was issued **for the tickets API**. Change it with a **token customiser**: a bean the authorisation server calls while it builds each token. Create `TokenConfig.java` in the auth-server (in `com.example.authserver`):

```java
package com.example.authserver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.util.List;

@Configuration
public class TokenConfig {

    /** The API's name, as it appears in the "aud" (audience) claim of every access token. */
    static final String TICKETS_API = "tickets-api";

    /**
     * By default an access token's audience is the client that asked for it. Say instead
     * that it's for the tickets API, so the API can refuse tokens meant for anything else.
     */
    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> audienceCustomizer() {
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                context.getClaims().audience(List.of(TICKETS_API));
            }
        };
    }
}
```

- **`OAuth2TokenCustomizer<JwtEncodingContext>`** is called for every JWT the server creates. `context.getClaims()` is the builder for the payload, before it's signed.
- **The `if` matters.** The same customiser runs for **ID tokens** too: the tokens a website gets when a person signs in with OpenID Connect. This series never asks for one, but the guard keeps the customiser correct if a client ever does. An ID token's audience must stay the client's own id, because it's *for* that client. Only access tokens are for the API.

That class and the properties file are the **whole** authorisation server. Restart it, get a new token, and decode it again:

```json
{"kid": "47547d2f-703d-4064-a797-8e515c00e429", "alg": "RS256"}
{"sub": "organiser", "aud": "tickets-api", "nbf": 1794560384, "scope": ["events:write"], "iss": "http://localhost:9000", "exp": 1794560684, "iat": 1794560384, "jti": "b3e2aa6a-dbe2-4581-96b7-6bc6eba8e793"}
```

Your `kid`, times and `jti` will differ. `aud` is now `tickets-api`.

**Now the trap.** Ask for a token *without* `-d scope=events:write`:

```bash
curl -u organiser:organiser-secret -d grant_type=client_credentials http://localhost:9000/oauth2/token
```

```json
{"access_token":"…","token_type":"Bearer","expires_in":300}
```

It succeeds. But there's no `scope` in the response, and if you decode the token, no `scope` claim at all:

```json
{"sub":"organiser","aud":"tickets-api","nbf":…,"iss":…,"exp":…,"iat":…,"jti":…}
```

Spring's authorisation server grants **only the scopes the client asks for**. The `scopes` property in the registration is the most the client *may* have, not what it gets by default. A token with no scopes is a perfectly valid token that allows nothing, so the API will answer every write with `403`. It's the most common mistake in this guide, and it's easy to miss because the token request itself works. Other authorisation servers have their own defaults, so with any provider, decode the token and look.

Two more ways for a token request to fail:

```bash
curl -i -u organiser:wrong -d grant_type=client_credentials http://localhost:9000/oauth2/token
```

```
HTTP/1.1 401
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Content-Type: application/json;charset=UTF-8

{"error":"invalid_client"}
```

```bash
curl -i -u scanner:scanner-secret -d grant_type=client_credentials -d scope=events:write http://localhost:9000/oauth2/token
```

```
HTTP/1.1 400
{"error":"invalid_scope"}
```

A wrong secret is `invalid_client`. A scope the client isn't registered for is `invalid_scope`: the scanner can't promote itself to organiser.

### 5. Test the auth-server

Those four curl commands are worth keeping as tests. Create `src/test/java/com/example/authserver/ClientCredentialsTest.java` in the auth-server:

```java
package com.example.authserver;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.io.UnsupportedEncodingException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

@SpringBootTest
@AutoConfigureMockMvc
class ClientCredentialsTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JwtDecoder jwtDecoder; // checks tokens with this server's own public key

    @Test
    void theOrganiserGetsATokenForTheTicketsApi() throws UnsupportedEncodingException {
        var response = mvc.post().uri("/oauth2/token")
                .with(httpBasic("organiser", "organiser-secret"))
                .param("grant_type", "client_credentials")
                .param("scope", "events:write")
                .exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.token_type").isEqualTo("Bearer");
        assertThat(response).bodyJson().extractingPath("$.scope").isEqualTo("events:write");

        String token = JsonPath.read(response.getResponse().getContentAsString(), "$.access_token");
        Jwt jwt = jwtDecoder.decode(token);
        assertThat(jwt.getIssuer()).hasToString("http://localhost:9000");
        assertThat(jwt.getAudience()).isEqualTo(List.of("tickets-api"));
        assertThat(jwt.getSubject()).isEqualTo("organiser");
        assertThat(jwt.getClaimAsStringList("scope")).containsExactly("events:write");
    }

    @Test
    void withoutAScopeParameterTheTokenHasNoScopes() throws UnsupportedEncodingException {
        var response = mvc.post().uri("/oauth2/token")
                .with(httpBasic("organiser", "organiser-secret"))
                .param("grant_type", "client_credentials")
                .exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().doesNotHavePath("$.scope");
        String token = JsonPath.read(response.getResponse().getContentAsString(), "$.access_token");
        assertThat(jwtDecoder.decode(token).hasClaim("scope")).isFalse();
    }

    @Test
    void theScannerCannotAskForTheOrganisersScope() {
        var response = mvc.post().uri("/oauth2/token")
                .with(httpBasic("scanner", "scanner-secret"))
                .param("grant_type", "client_credentials")
                .param("scope", "events:write");

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.error").isEqualTo("invalid_scope");
    }

    @Test
    void aWrongSecretIs401() {
        var response = mvc.post().uri("/oauth2/token")
                .with(httpBasic("organiser", "wrong"))
                .param("grant_type", "client_credentials");

        assertThat(response).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(response).bodyJson().extractingPath("$.error").isEqualTo("invalid_client");
    }

    @Test
    void publishesItsMetadata() {
        var response = mvc.get().uri("/.well-known/oauth-authorization-server");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.issuer").isEqualTo("http://localhost:9000");
        assertThat(response).bodyJson().extractingPath("$.jwks_uri").isEqualTo("http://localhost:9000/oauth2/jwks");
    }
}
```

- **`httpBasic(...)`** (from spring-security-test, which the `…-authorization-server-test` starter brings) adds the Basic `Authorization` header, like `curl -u`. **`.param(...)`** sends form fields, like `curl -d`.
- **`JwtDecoder`** is a bean Spring Boot created for the authorisation server's own use, and it checks tokens with the server's current public key. Decoding with it proves the token is signed correctly, not just shaped correctly.
- **`withoutAScopeParameterTheTokenHasNoScopes`** pins down step 4's trap, so nobody "fixes" it by accident, and so the behaviour is written down where the next developer will find it.
- **`.exchange()`** is needed in the first two tests because they read the raw response body after asserting on it. **`JsonPath.read`** (the Jayway library Spring's test support uses) pulls out the token.

Run them (right-click `src/test/java` → **Run 'All Tests'**, or `./mvnw test`). Five tests pass. The tests don't need port 9000: MockMvc calls the application in memory.

### 6. The API: add the resource server, and watch it lock

Now switch to the `api` project. Add two dependencies to its `pom.xml`, the first next to the other starters and the second with the test dependencies:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
</dependency>
```

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-oauth2-resource-server-test</artifactId>
    <scope>test</scope>
</dependency>
```

The first brings Spring Security 7 with its resource server support. The second brings spring-security-test. Reload Maven (the **M** icon that appears, or **Maven tool window → Reload All Maven Projects**) and run the API's tests:

```
[ERROR] Tests run: 40, Failures: 32, Errors: 8, Skipped: 0
```

Spring Security is **secure by default**: with it on the classpath and no configuration, every URL needs authentication. Two kinds of failure are mixed up in there, and they're worth telling apart:

- **Reads** fail with `expected: 200 but was: 401`. No credentials, so no entry. You expected that.
- **Writes** (`POST`, `PUT`, `PATCH`, `DELETE`) fail with `but was: 403`. That's **not** a missing scope. It's **CSRF protection**, which Spring Security switches on by default and which rejects any state-changing request without a CSRF token before it even looks at who's calling. The next steps explain why this API turns it off.

(The 8 errors are tests that failed further along for the same reasons.)

### 7. Trust the auth-server

Add to the API's `application.properties`, above the SpringDoc properties:

```properties
# Trust tokens signed by the authorisation server, and only if they were issued for this API.
# Spring Boot reads the issuer's metadata (on the first request with a token) to find its public keys.
spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9000
spring.security.oauth2.resourceserver.jwt.audiences=tickets-api
```

These two properties are the whole token check:

- **`issuer-uri`** makes Spring Boot build a `JwtDecoder` that finds the public keys through the issuer's metadata and checks the **signature**, the **`iss`** claim (it must equal this string exactly) and the **timestamps** (`exp` and `nbf`, with 60 seconds of clock skew allowed).
- **`audiences`** adds the **`aud`** check: the token must list `tickets-api`.

**The decoder is created lazily.** Spring Boot doesn't contact the authorisation server at start-up. It does that the first time a request arrives **with a token**: it fetches the metadata from `http://localhost:9000/.well-known/openid-configuration`, then the keys. So:

- The API starts even when the auth-server isn't running.
- Public `GET`s work, and requests with no token get a `401`, without the auth-server.
- The first request **with a token** needs the auth-server. If it's down, that request fails (see Common errors), and the next one tries again.

That's why the [README](README.md#where-things-live) says to start the **authorisation server first**: the API fetches its keys when the first token arrives, and the auth-server has to be there to give them.

### 8. The security rules

Create `security/SecurityConfig.java` in the API:

```java
package com.example.tickets.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, SecurityProblemHandler problems) throws Exception {
        http
                // No cookies or sessions: every request carries its own bearer token
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Rules are checked top to bottom; the first match wins
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/venues/**", "/events/**").permitAll()
                        .requestMatchers("/events/**").hasAuthority("SCOPE_events:write")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems));
        return http.build();
    }
}
```

It won't compile until the next step creates `SecurityProblemHandler`. From the top:

**CSRF off, and no sessions.** A CSRF attack makes a victim's *browser* send a request to your site, and relies on the browser attaching the victim's cookies automatically. The todo app needed protection, because it signed people in with a cookie. This API has no cookies: the credential is an `Authorization` header that the client adds on purpose, and a forged cross-site request can't add it. So CSRF protection has nothing to protect, and it would block every write. `STATELESS` tells Spring Security never to create an HTTP session: each request stands alone, with its token.

**The rules, top to bottom, first match wins:**

1. **Swagger UI and the OpenAPI document** are public, so anyone can read how to use the API.
2. **`/error`** is public. It's where Spring Boot forwards a request when something fails; if it needed a token, an error during an anonymous request would turn into a confusing `401`.
3. **Every `GET` under `/venues` and `/events`** is public: browsing is for anyone. This covers ticket types too, since they live under `/events/{id}/ticket-types`.
4. **Anything else under `/events`** (`POST`, `PUT`, `PATCH`, `DELETE`) needs the **`SCOPE_events:write`** authority. Order matters: rule 3 has already let the `GET`s through, so only writes reach this line.
5. **Anything else at all** needs a valid token. That includes URLs that don't exist (step 10 shows why that's on purpose).

**`oauth2ResourceServer(... jwt(...))`** adds the filter that reads `Authorization: Bearer …`, passes the token to the `JwtDecoder` from step 7, and turns the decoded token into an `Authentication` with the `SCOPE_` authorities from the Concepts section. `Customizer.withDefaults()` means you're keeping Spring Security's default conversion; there's no `JwtAuthenticationConverter` bean in this project, because the default already does what's needed.

**`authenticationEntryPoint` and `accessDeniedHandler`** say what to send back for a `401` and a `403`. That's the next step.

> **Sidebar: a bit of RFC 9068.** You may find blog posts that validate access tokens with `JwtValidators.createAtJwtValidator()`. It applies [RFC 9068](https://www.rfc-editor.org/rfc/rfc9068), a profile for JWT access tokens that requires a `typ: at+jwt` header and a `client_id` claim. Spring's authorisation server doesn't produce tokens in that shape (look at the decoded header: no `typ`, and the payload has no `client_id`). Add that validator here and **every** token is refused. The two properties from step 7 check what matters: signature, issuer, timestamps and audience.

### 9. 401 and 403 as `ProblemDetail`

Every error from this API has been a `ProblemDetail` since guide 01. Security errors would break that, because of **where** they happen. Spring Security runs as a servlet **filter**, before Spring MVC. When it refuses a request, it writes the response there and then, and Spring MVC, with your `ApiExceptionHandler`, never runs. Left alone, a `401` has an empty body.

The fix is to pass security's exceptions **into** Spring MVC's exception handling. Create `security/SecurityProblemHandler.java`:

```java
package com.example.tickets.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * Spring Security rejects requests in a servlet filter, before Spring MVC runs, so its
 * 401s and 403s would never reach ApiExceptionHandler. This hands the exceptions to
 * Spring MVC's exception resolvers, so they become ProblemDetails like every other error.
 */
@Component
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    // The standard bearer-token handlers set the status and the WWW-Authenticate header
    private final AuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final AccessDeniedHandler bearerAccessDenied = new BearerTokenAccessDeniedHandler();
    private final HandlerExceptionResolver resolver;

    public SecurityProblemHandler(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException ex) throws IOException, ServletException {
        bearerEntryPoint.commence(request, response, ex);
        resolver.resolveException(request, response, null, ex);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException ex) throws IOException, ServletException {
        bearerAccessDenied.handle(request, response, ex);
        resolver.resolveException(request, response, null, ex);
    }
}
```

It plays both of Spring Security's "say no" roles:

- **`AuthenticationEntryPoint.commence`** is called when the request **isn't authenticated**: no token, or a bad one. That's the `401`.
- **`AccessDeniedHandler.handle`** is called when the request **is** authenticated but **not allowed**: a valid token without `events:write`. That's the `403`.

Each does two things:

1. **The standard bearer-token handler goes first.** `BearerTokenAuthenticationEntryPoint` and `BearerTokenAccessDeniedHandler` are what Spring Security would have used anyway. They set the status and the **`WWW-Authenticate`** header, with details such as `error="invalid_token"` or `error="insufficient_scope"`. You keep all of that.
2. **Then the exception goes to Spring MVC's `HandlerExceptionResolver`**, the same object that handles exceptions thrown by controllers. The `@Qualifier` picks Spring MVC's main resolver (there's more than one bean of that type). From there, the exception reaches `ApiExceptionHandler` exactly as if a controller had thrown it.

Now give `ApiExceptionHandler` something to do with those exceptions. Add these two methods to `web/ApiExceptionHandler.java`, with imports for `org.springframework.security.access.AccessDeniedException` and `org.springframework.security.core.AuthenticationException` (trimmed: the rest of the class is unchanged):

```java
    /** No token, or a token that isn't valid (from SecurityProblemHandler). */
    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handle(AuthenticationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    /** A valid token that doesn't allow this request, such as a missing scope (from SecurityProblemHandler). */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handle(AccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "The access token does not allow this request");
    }
```

- **The `401`'s detail is the exception's message**, which says *why* the token was refused: "Jwt expired at …", "The aud claim is not valid". That helps the developer of the calling service, and gives nothing away: they already have the token.
- **The `403`'s detail is fixed.** The caller only needs to know the token doesn't allow this request.
- **No custom titles.** `ProblemDetail` uses the HTTP reason phrase, so the titles are `"Unauthorized"` and `"Forbidden"`, matching `"Not Found"` and `"Bad Request"` from earlier guides.

### 10. Try it

Start the **auth-server first**, then the API. Run the tests in a moment; first, see it from the outside.

**No token:**

```bash
curl -i -H 'Content-Type: application/json' -d '{"title":"Autumn Folk Night","category":"MUSIC","venueId":4,"startsAt":"2030-10-03T19:30:00Z"}' localhost:8080/events
```

```
HTTP/1.1 401
WWW-Authenticate: Bearer resource_metadata="http://localhost:8080/.well-known/oauth-protected-resource"
X-Content-Type-Options: nosniff
X-XSS-Protection: 0
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Expires: 0
X-Frame-Options: DENY
Content-Type: application/problem+json

{"detail":"Full authentication is required to access this resource","instance":"/events","status":401,"title":"Unauthorized"}
```

A `ProblemDetail`, with `WWW-Authenticate: Bearer` telling the caller what kind of credential to bring. The `resource_metadata` points at a small JSON document Spring Security 7 serves for you ([RFC 9728](https://www.rfc-editor.org/rfc/rfc9728)), describing this API as a protected resource:

```bash
curl localhost:8080/.well-known/oauth-protected-resource
```

```json
{"resource":"http://localhost:8080","bearer_methods_supported":["header"],"tls_client_certificate_bound_access_tokens":true}
```

The other new headers (`X-Content-Type-Options`, `X-Frame-Options` and so on) are Spring Security's default security headers. Guide 08 looks at them.

> **Windows PowerShell and JSON.** Windows PowerShell 5.1 strips the double quotes out of arguments it passes to `curl.exe`, so a JSON body like the one above arrives broken. For requests with a JSON body, use `requests.http` (step 11) or Git Bash. Requests without a body, and the token requests (form fields, not JSON), work fine with `curl.exe`.

**With the organiser's token.** Put a fresh token in a variable. In Git Bash or another Unix shell:

```bash
ORGANISER_TOKEN=eyJraWQ...
```

(In PowerShell, `$ORGANISER_TOKEN = "eyJraWQ..."`.) Then:

```bash
curl -i -H "Authorization: Bearer $ORGANISER_TOKEN" -H 'Content-Type: application/json' -d '{"title":"Autumn Folk Night","category":"MUSIC","venueId":4,"startsAt":"2030-10-03T19:30:00Z"}' localhost:8080/events
```

```
HTTP/1.1 201
Location: http://localhost:8080/events/29
…security headers…

{"id":29,"title":"Autumn Folk Night","category":"MUSIC","venue":{"id":4,"name":"The Mill Theatre","city":"Leeds"},"startsAt":"2030-10-03T19:30:00Z","description":null}
```

That was the first request with a token, so behind the scenes the API fetched the auth-server's metadata and keys, then checked the signature, `iss`, `aud` and `exp`, then mapped `scope` to `SCOPE_events:write` and let it through.

**With the scanner's token** (`curl -u scanner:scanner-secret -d grant_type=client_credentials -d scope=tickets:check-in http://localhost:9000/oauth2/token`), the same request:

```
HTTP/1.1 403
WWW-Authenticate: Bearer error="insufficient_scope", error_description="The request requires higher privileges than provided by the access token.", error_uri="https://tools.ietf.org/html/rfc6750#section-3.1"
Content-Type: application/problem+json

{"detail":"The access token does not allow this request","instance":"/events","status":403,"title":"Forbidden"}
```

A genuine token, so not a `401`; the wrong scope, so `403` with `error="insufficient_scope"`. An organiser token requested **without** `-d scope=events:write` gets exactly the same `403`: step 4's trap, seen from the API's side.

**Not a token at all:**

```bash
curl -i -H "Authorization: Bearer abc.def.ghi" -X DELETE localhost:8080/events/28
```

```
HTTP/1.1 401
WWW-Authenticate: Bearer error="invalid_token", error_description="An error occurred while attempting to decode the Jwt: Malformed token", error_uri="https://tools.ietf.org/html/rfc6750#section-3.1", resource_metadata="http://localhost:8080/.well-known/oauth-protected-resource"
{"detail":"An error occurred while attempting to decode the Jwt: Malformed token","instance":"/events/28","status":401,"title":"Unauthorized"}
```

**A forged token.** Take the organiser's token and change the last four characters (the end of the signature). The claims are untouched, and it still looks like a JWT:

```
{"detail":"An error occurred while attempting to decode the Jwt: Signed JWT rejected: Invalid signature","instance":"/events/28","status":401,"title":"Unauthorized"}
```

That's the signature doing its job: change one bit of the token and it's worthless.

**Reading still needs nothing**, and guide 03's caching still works. Look at the headers now that Spring Security is involved:

```bash
curl -i localhost:8080/events/5
```

```
HTTP/1.1 200
Cache-Control: max-age=60, public
ETag: "0"
X-Content-Type-Options: nosniff
X-XSS-Protection: 0
X-Frame-Options: DENY
Content-Type: application/json
```

```bash
curl -i 'localhost:8080/events?size=1'
```

```
HTTP/1.1 200
X-Content-Type-Options: nosniff
X-XSS-Protection: 0
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Expires: 0
X-Frame-Options: DENY
Content-Type: application/json
```

The two responses differ, and the reason matters. Spring Security adds `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` (plus `Pragma` and `Expires`) to every response, **unless the response already has a `Cache-Control` header**. Guide 03 set one on `GET /events/{id}`, so Security leaves it alone: one event is still cacheable for 60 seconds, and `If-None-Match` still gets a `304` with the same headers. The list never set one, so it now says `no-store`. Security's default is the safe one: a response that might be private is never cached unless you say otherwise. If you want the list cached too, give it its own `Cache-Control`, as guide 03 did for one event.

**Unknown URLs.** Try `curl -i localhost:8080/evnts` (the typo from guide 01). Before this guide it was a `404`. Now it's a `401`: no rule matches `/evnts` except `anyRequest().authenticated()`. Send any valid token and it's a `404` again. That's deliberate. An anonymous caller can't tell "this URL doesn't exist" from "this URL exists and needs a token", so probing for hidden endpoints teaches them nothing.

### 11. Tokens in `requests.http`

IntelliJ's HTTP client can ask for a token and remember it for later requests. Add a token request to the API's `requests.http`, before the first write:

```http
### Get a token for the organiser service (the auth-server must be running)
POST http://localhost:9000/oauth2/token
Authorization: Basic organiser organiser-secret
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&scope=events:write

> {% client.global.set("organiserToken", response.body.access_token); %}
```

- **`Authorization: Basic organiser organiser-secret`** is IntelliJ's shorthand: it encodes the id and secret for you.
- **`> {% … %}`** is a response handler. It stores the `access_token` in a variable called `organiserToken`.

Then add `Authorization: Bearer {{organiserToken}}` to every `POST`, `PUT`, `PATCH` and `DELETE` request. For example:

```http
### Create an event
POST http://localhost:8080/events
Authorization: Bearer {{organiserToken}}
Content-Type: application/json

{
  "title": "Autumn Folk Night",
  "category": "MUSIC",
  "venueId": 4,
  "startsAt": "2030-10-03T19:30:00Z",
  "description": "Three folk acts."
}
```

And one at the end that shows the refusal:

```http
### Without a token (401)
DELETE http://localhost:8080/events/29
```

Run the token request first (and again after five minutes, when the token expires).

### 12. Tests with `jwt()`

Run the API's tests now. Every write still fails, `401` this time: the tests don't send tokens.

They shouldn't need a real one. The tests are about **your** rules (which URLs need which scope, what the errors look like), not about RSA signatures, which Nimbus and Spring Security test themselves. spring-security-test's **`jwt()`** request post-processor skips the `JwtDecoder` entirely and puts an already-decoded token straight into the security context. No auth-server, no keys, no network.

Create a helper, `src/test/java/com/example/tickets/TestTokens.java`:

```java
package com.example.tickets;

import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Pretend access tokens for MockMvc tests. jwt() skips the JwtDecoder (no signature,
 * issuer or audience checks) and puts an already-decoded token in the security context.
 * Its authorities come from the "scope" claim, as they would for a real token.
 */
public final class TestTokens {

    private TestTokens() {
    }

    /** The organiser service: a client-credentials token with events:write. */
    public static JwtRequestPostProcessor organiser() {
        return jwt().jwt(token -> token
                .subject("organiser")
                .claim("scope", "events:write"));
    }
}
```

`jwt().jwt(customiser)` builds a token with the claims you set, and gives it authorities from its `scope` claim the same way the default converter would: `SCOPE_events:write`. Plain `jwt()`, with no customiser, is a valid token for a subject called `user` with only the authority `SCOPE_read`. That's enough to be authenticated, and not enough to change events.

Add `.with(organiser())` to every write request in `EventWriteApiTest`, `TicketTypeApiTest` and `EventCachingApiTest`, with `import static com.example.tickets.TestTokens.organiser;`. For example, in `EventWriteApiTest`:

```java
        var response = mvc.post().uri("/events").with(organiser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(NEW_EVENT);
```

The `GET`s don't change: they're public.

One existing test needs a different fix. `EventApiTest.anUnknownUrlIsA404ProblemDetailToo` now gets a `401` (step 10). It's meant to test the `404`, so give it a token, any token, with a comment saying why. In `EventApiTest`, with `import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;` (trimmed):

```java
    @Test
    void anUnknownUrlIsA404ProblemDetailToo() {
        // Since guide 05, only known public URLs work without a token: anonymous callers get a 401
        var response = mvc.get().uri("/evnts").with(jwt());
```

Now the security rules themselves. Create `src/test/java/com/example/tickets/security/SecurityApiTest.java`:

```java
package com.example.tickets.security;

import com.example.tickets.TicketsIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static com.example.tickets.TestTokens.organiser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@TicketsIntegrationTest
class SecurityApiTest {

    static final String NEW_TICKET_TYPE = """
            {"name": "Security test", "pricePence": 1000, "capacity": 10}
            """;

    @Autowired
    MockMvcTester mvc;

    @Test
    void readingEventsNeedsNoToken() {
        assertThat(mvc.get().uri("/events")).hasStatusOk();
        assertThat(mvc.get().uri("/events/2/ticket-types")).hasStatusOk();
        assertThat(mvc.get().uri("/venues")).hasStatusOk();
    }

    @Test
    void changingEventsWithoutATokenIs401AsProblemDetail() {
        var response = mvc.post().uri("/events/2/ticket-types")
                .contentType(MediaType.APPLICATION_JSON)
                .content(NEW_TICKET_TYPE);

        assertThat(response).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).headers().hasHeaderSatisfying("WWW-Authenticate",
                values -> assertThat(values.getFirst()).startsWith("Bearer"));
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"title": "Unauthorized", "status": 401, "detail": "Full authentication is required to access this resource"}
                """);
    }

    @Test
    void aTokenWithoutEventsWriteIs403() {
        var response = mvc.post().uri("/events/2/ticket-types")
                .with(jwt().jwt(token -> token.claim("scope", "tickets:check-in")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(NEW_TICKET_TYPE);

        assertThat(response).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).headers().hasHeaderSatisfying("WWW-Authenticate",
                values -> assertThat(values.getFirst()).contains("error=\"insufficient_scope\""));
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Forbidden");
    }

    @Test
    void aTokenWithEventsWriteIsAllowed() {
        var response = mvc.post().uri("/events/2/ticket-types")
                .with(organiser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(NEW_TICKET_TYPE);

        assertThat(response).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void theApiDocsArePublic() {
        assertThat(mvc.get().uri("/v3/api-docs")).hasStatusOk();
        assertThat(mvc.get().uri("/swagger-ui/index.html")).hasStatusOk();
    }
}
```

- **The same request three ways**: no token (`401`), the scanner's scope (`403`), the organiser's scope (`201`). Together they prove the rule `hasAuthority("SCOPE_events:write")` and nothing else decides the outcome.
- **`hasHeaderSatisfying("WWW-Authenticate", …)`** checks the header by its start, not its whole value, because Spring Security 7 adds the `resource_metadata` parameter you saw in step 10.
- **`theApiDocsArePublic`** guards the first rule. Without it, someone tidying `SecurityConfig` could lock the documentation away, and nothing else would notice.

Remember what `jwt()` **doesn't** test: the signature, the issuer and the audience checks. Those come from the two properties in step 7 and Spring's decoder, and step 10 tried them for real.

### 13. Security in the OpenAPI document

The document from guide 04 doesn't yet say that writes need a token, or how to get one. Replace `web/OpenApiConfig.java`:

```java
package com.example.tickets.web;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.OAuthFlow;
import io.swagger.v3.oas.annotations.security.OAuthFlows;
import io.swagger.v3.oas.annotations.security.OAuthScope;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/**
 * The parts of the OpenAPI document that aren't about any one endpoint, including
 * how to get a token. Operations that need one say so with @SecurityRequirement.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Event Tickets API",
                version = "1.0",
                description = "Browse events, book tickets and check them in. A teaching API with fictional data."),
        servers = @Server(url = "http://localhost:8080", description = "Local development"))
@SecurityScheme(
        name = OpenApiConfig.OAUTH2,
        type = SecuritySchemeType.OAUTH2,
        description = "Tokens from the authorisation server",
        flows = @OAuthFlows(
                clientCredentials = @OAuthFlow(
                        tokenUrl = "${spring.security.oauth2.resourceserver.jwt.issuer-uri}/oauth2/token",
                        scopes = {
                                @OAuthScope(name = "events:write", description = "Create and change events"),
                                @OAuthScope(name = "tickets:check-in", description = "Check tickets in at the door")
                        })))
public class OpenApiConfig {

    /** The name of the security scheme, for @SecurityRequirement(name = OpenApiConfig.OAUTH2, ...). */
    public static final String OAUTH2 = "oauth2";
}
```

- **`@SecurityScheme`** declares, once, how callers get credentials: OAuth 2.0, with the client credentials flow, at this token URL, with these scopes. It appears under `components.securitySchemes.oauth2`.
- **`${spring.security.oauth2.resourceserver.jwt.issuer-uri}`** is a property placeholder, and SpringDoc resolves it: the document says `http://localhost:9000/oauth2/token`. Point the API at another issuer and the document follows, with no second place to update.
- **`OAUTH2`**, a constant, so controllers refer to the scheme by name without a typo-prone string.

Then mark each write operation. In `EventController`, each of `createEvent`, `replaceEvent`, `updateEvent` and `deleteEvent` gets a `@SecurityRequirement` and two more responses. Here's `createEvent` (trimmed: the method body is unchanged), with imports for `com.example.tickets.web.OpenApiConfig` and `io.swagger.v3.oas.annotations.security.SecurityRequirement`:

```java
    @PostMapping
    @SecurityRequirement(name = OpenApiConfig.OAUTH2, scopes = "events:write")
    @Operation(operationId = "createEvent", summary = "Create an event")
    @ApiResponse(responseCode = "201", description = "Created. The Location header is the new event's URL",
            headers = @Header(name = "Location", description = "The new event's URL"))
    @ApiResponse(responseCode = "400", description = "Invalid request content",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "401", description = "No access token, or not a valid one",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "403", description = "The access token doesn't have the events:write scope",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ApiProblem.class)))
    public ResponseEntity<EventResponse> create(@Valid @RequestBody EventRequest request) {
```

Add the same `@SecurityRequirement` line and the same `401` and `403` responses (after the existing ones) to `replaceEvent`, `updateEvent` and `deleteEvent`, and to `createTicketType` in `TicketTypeController`, with the same two imports there.

- **`@SecurityRequirement(name = …, scopes = "events:write")`** says this operation needs a token from that scheme, with that scope. The operation gets `"security": [{"oauth2": ["events:write"]}]`.
- **The `GET`s get nothing**, so the document shows them as public, which they are. (Compare a global `security = …` on `@OpenAPIDefinition`, which would claim every operation needs a token.)
- **`401` and `403` are real responses** of these operations now, with the same `Problem` schema as every other error.

A test keeps it that way. Add to `OpenApiDocumentTest`:

```java
    @Test
    void saysHowToGetATokenAndWhichScopeEachOperationNeeds() {
        var doc = mvc.get().uri("/v3/api-docs");

        assertThat(doc).bodyJson()
                .extractingPath("$.components.securitySchemes.oauth2.flows.clientCredentials.tokenUrl")
                .isEqualTo("http://localhost:9000/oauth2/token");
        assertThat(doc).bodyJson().extractingPath("$.paths['/events'].post.security[0].oauth2")
                .isEqualTo(List.of("events:write"));
        assertThat(doc).bodyJson().doesNotHavePath("$.paths['/events'].get.security");
    }
```

Open Swagger UI. There's an **Authorize** button now, and the write operations have a padlock. The Authorize dialog offers the client credentials flow with a client id and secret. **Don't expect it to work yet.** Swagger UI runs in your browser, on `localhost:8080`, and it would call the token endpoint on `localhost:9000`. That's a **cross-origin** request, and the browser blocks it unless the auth-server says it's allowed (with **CORS** headers), which it doesn't yet. Guide 06 adds CORS to the auth-server, and uses Authorize for real, to sign customers in. Until then, use curl or `requests.http` for writes. Swagger UI is still fine for everything public.

---

## Your turn

### Exercise 1: plain `jwt()` can't delete

Step 12 said plain `jwt()` is authenticated but only has `SCOPE_read`. Prove it: write a test in `SecurityApiTest` showing that `DELETE /events/2` with plain `jwt()` is a `403`, not a `401`, and not a `204`.

<details>
<summary>Solution</summary>

```java
@Test
void anAuthenticatedCallerWithoutTheScopeCannotDelete() {
    var response = mvc.delete().uri("/events/2").with(jwt());

    assertThat(response).hasStatus(HttpStatus.FORBIDDEN);
    assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Forbidden");
}
```

It's safe to aim at a seeded event: the request is refused in the security filter, before the controller runs, so event 2 is never touched. If the test ever deletes it (`204`), the rule is broken and other tests will fail too.

</details>

### Exercise 2: the scanner in `requests.http`

Add two requests to `requests.http`: one that gets a token for the **scanner** and stores it as `scannerToken`, and one that tries to create an event with it. Before you run them, predict the status and the `WWW-Authenticate` header. Then decode the scanner's token in JShell and point at the claim that explains the result.

<details>
<summary>Solution</summary>

```http
### Get a token for the scanner service
POST http://localhost:9000/oauth2/token
Authorization: Basic scanner scanner-secret
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&scope=tickets:check-in

> {% client.global.set("scannerToken", response.body.access_token); %}

### The scanner can't create events (403)
POST http://localhost:8080/events
Authorization: Bearer {{scannerToken}}
Content-Type: application/json

{"title": "Not allowed", "category": "MUSIC", "venueId": 4, "startsAt": "2030-10-03T19:30:00Z"}
```

`403 Forbidden`, with `WWW-Authenticate: Bearer error="insufficient_scope", …`. The token is genuine (so not `401`): its payload is `{"sub": "scanner", "aud": "tickets-api", …, "scope": ["tickets:check-in"], "iss": "http://localhost:9000", …}`. The `scope` claim becomes `SCOPE_tickets:check-in`, and writes need `SCOPE_events:write`.

</details>

### Exercise 3: watch a token expire

Set the organiser's tokens to last **10 seconds**, with the property from step 2, in the auth-server's `application.properties`. Restart the auth-server, get a token, and use it to `PATCH` an event straight away, then after 30 seconds, then after two minutes. What happens each time, and why does the 30-second attempt behave the way it does? Remove the property afterwards.

<details>
<summary>Solution</summary>

```properties
spring.security.oauth2.authorizationserver.client.organiser.token.access-token-time-to-live=10s
```

The token response now says `"expires_in":10`.

- **Straight away:** `200`.
- **After 30 seconds:** still `200`, even though `exp` has passed. Spring's timestamp check allows **60 seconds of clock skew**, because the API's clock and the auth-server's clock are never exactly the same on real machines.
- **After two minutes:** `401`, with a detail like `An error occurred while attempting to decode the Jwt: Jwt expired at 2026-11-13T09:06:19Z`.

The lesson for clients: get a new token *before* the old one expires (`expires_in` says when), and treat a `401` as "get a new token and try once more". Guide 09's scanner lets Spring do that for it.

</details>

---

## Checkpoint

In the **auth-server**:

```bash
./mvnw test
```

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

In the **api**:

```bash
./mvnw test
```

```
[INFO] Tests run: 46, Failures: 0, Errors: 0, Skipped: 0
```

(40 from guide 04, 5 in `SecurityApiTest` and 1 more in `OpenApiDocumentTest`. If you kept the test from exercise 1, you'll have 47.) Neither project needs the other running for its tests.

The new project:

```
auth-server/
├── pom.xml
└── src/
    ├── main/java/com/example/authserver/
    │   ├── AuthServerApplication.java
    │   └── TokenConfig.java
    ├── main/resources/application.properties
    └── test/java/com/example/authserver/
        └── ClientCredentialsTest.java
```

New files in the api:

```
src/main/java/com/example/tickets/
└── security/
    ├── SecurityConfig.java
    └── SecurityProblemHandler.java
src/test/java/com/example/tickets/
├── TestTokens.java
└── security/
    └── SecurityApiTest.java
```

Modified: `pom.xml`, `application.properties`, `ApiExceptionHandler.java` (401 and 403 handlers), `OpenApiConfig.java` (security scheme), `EventController.java` and `TicketTypeController.java` (`@SecurityRequirement`, 401 and 403 responses), `EventApiTest.java`, `EventWriteApiTest.java`, `TicketTypeApiTest.java`, `EventCachingApiTest.java`, `OpenApiDocumentTest.java`, `requests.http`.

---

## Common errors

**`Web server failed to start. Port 9000 was already in use.`**
Something else is on port 9000. Most likely it's the [identity provider](../identity-provider/README.md) series' `idp` (Passport), which uses the same port, or a second copy of the auth-server (one in IntelliJ, one in a terminal). Stop it and start the auth-server again.

**A valid organiser token gets `403`**
The token was requested without `-d scope=events:write` (or without `scope=events:write` in `requests.http`). It has no `scope` claim, so no `SCOPE_` authorities. Decode it and look: step 4.

**`401` with `An error occurred while attempting to decode the Jwt: The aud claim is not valid`**
The token's audience isn't `tickets-api`. Either the auth-server's `TokenConfig` customiser is missing (so `aud` is the client id, `organiser`), or the API's `spring.security.oauth2.resourceserver.jwt.audiences` says something else. They must agree.

**The first request with a token is a `500`, and the log says `The Issuer "http://localhost:9000" provided in the configuration did not match the requested issuer "http://127.0.0.1:9000"`**
The full log starts `org.springframework.security.oauth2.jwt.JwtDecoderInitializationException: Failed to lazily resolve the supplied JwtDecoder instance`, and the response is Spring Boot's plain `{"timestamp":…,"status":500,"error":"Internal Server Error",…}`. The API's `issuer-uri` and the auth-server's `issuer` aren't the same string. They must match character for character; `localhost` and `127.0.0.1` are different issuers.

**The first request with a token is a `500`, and the log says `Connection refused`**
In full:

```
JwtDecoderInitializationException: Failed to lazily resolve the supplied JwtDecoder instance
Caused by: java.lang.IllegalArgumentException: Unable to resolve the Configuration with the provided Issuer of "http://localhost:9000"
Caused by: org.springframework.web.client.ResourceAccessException: I/O error on GET request for "http://localhost:9000/.well-known/openid-configuration": Connection refused: getsockopt
```

The auth-server isn't running. Public `GET`s and token-less `401`s still work, because neither needs the decoder. Start the auth-server; the API tries again on the next request, with no restart needed.

**`401` with `An error occurred while attempting to decode the Jwt: Signed JWT rejected: Another algorithm expected, or no matching key(s) found`**
The auth-server has restarted since the token was issued. Its signing key lives in memory, so a restart makes a new one, and the old token's `kid` no longer matches any published key. Get a new token. (A real authorisation server keeps its keys in a store and rotates them deliberately.)

**`401` with `An error occurred while attempting to decode the Jwt: Jwt expired at 2026-11-13T09:06:19Z`**
The token is more than five minutes old (plus the 60 seconds' allowance). Get a new one.

**`401` with `An error occurred while attempting to decode the Jwt: Malformed token`**
What follows `Bearer ` isn't a JWT. Check you copied the whole `access_token` value, without the quotes, and that `$ORGANISER_TOKEN` is set in *this* terminal.

**`401` and `403` responses have an empty body**
In a test: `expected: application/problem+json but was: null`. `SecurityProblemHandler` isn't wired in. Check that `.authenticationEntryPoint(problems)` and `.accessDeniedHandler(problems)` are inside `oauth2ResourceServer(...)`, and that `ApiExceptionHandler` has the two handlers from step 9. With curl you'll see the `WWW-Authenticate` header and the security headers, but no body.

**Every write fails with `403` straight after adding the dependency**
That's CSRF protection, which Spring Security turns on by default, not a missing scope. `SecurityConfig` turns it off with `.csrf(csrf -> csrf.disable())`. See step 6.

**`package org.springframework.security.test.web.servlet.request does not exist`**
A compile error in `TestTokens`: `spring-boot-starter-security-oauth2-resource-server-test` is missing from the API's `pom.xml`, or Maven hasn't been reloaded.

**`{"error":"invalid_client"}`** from the token endpoint
Wrong client id or secret. With curl, check the `-u id:secret` part; in `requests.http`, the `Authorization: Basic` line.

**`{"error":"invalid_scope"}`** from the token endpoint
The client asked for a scope it isn't registered for: the scanner can't ask for `events:write`. Check the `scopes` property of that client's registration.

**Swagger UI's Authorize doesn't get a token**
Expected in this guide: the browser blocks Swagger UI's cross-origin call to the auth-server until guide 06 adds CORS. Use curl or `requests.http`.

---

> **Sidebar: switching the issuer to a real identity provider.** Because the API only knows the issuer, moving to a different authorisation server is mostly **configuration**, not code. What follows is the general shape. **None of it was tested against the real providers for this series**, and providers change their defaults, so check their documentation.
>
> Three things change:
>
> 1. **`issuer-uri`** must be the provider's issuer exactly as it appears in its tokens' `iss`. For **Microsoft Entra ID** (v2.0 tokens) that's `https://login.microsoftonline.com/<tenant-id>/v2.0`. For **Keycloak** it's the realm's URL, ending in `/realms/<realm>`.
> 2. **`audiences`** must match what the provider puts in `aud`. In Entra, that's the API's **Application ID URI** or its client id, depending on how the app registration is set up. Keycloak doesn't put your API in `aud` by default (you'll often see `account`): you add an **audience mapper** to the client, which is the same job `TokenConfig` does here.
> 3. **Where the permissions are.** Spring's default converter reads `scope`, and also `scp` when there's no `scope`, which is where Entra puts **delegated** scopes (tokens for a user). An Entra **client credentials** token carries **application permissions** in a `roles` claim instead, and Keycloak puts realm roles under `realm_access.roles`. To use a different, top-level claim, set `spring.security.oauth2.resourceserver.jwt.authorities-claim-name` (and `authority-prefix` if you want something other than `SCOPE_`). A nested claim like Keycloak's needs a small `JwtAuthenticationConverter` bean of your own.
>
> The OpenAPI `tokenUrl` follows `issuer-uri` automatically only when the provider's token endpoint is `<issuer>/oauth2/token`, as Spring's is. Keycloak's is `<issuer>/protocol/openid-connect/token`, and Entra's isn't under the issuer either, so you'd set `tokenUrl` from its own property instead.
>
> **Passport**, from the [identity provider](../identity-provider/README.md) series, is a Spring authorisation server like this one, so its endpoints have the same shape and `tokenUrl` would follow. You'd set `issuer-uri=http://login.idp.test:9000`, stop this auth-server (both use port 9000), and register `organiser` and `scanner` as clients in Passport. Passport's access tokens have the client id as their audience, so it would also need an access-token audience customiser like `TokenConfig`'s. That's the theory; it hasn't been tried end to end.

---

## Recap

- APIs authenticate **each request** with an **access token**, not a session cookie. **OAuth 2.0** defines who issues tokens (the **authorisation server**), who uses them (**clients**), and who accepts them (the **resource server**: your API).
- A service gets a token for itself with **client credentials**: client id and secret in, short-lived token out. It must **ask for the scopes it needs**.
- Spring Boot turns the authorisation server starter plus **properties** into a working server: two filter chains, a signing key and the client registrations. A **token customiser** sets the access token's **audience**.
- A **JWT** is signed JSON. Decode it locally (JShell) rather than pasting real tokens into websites.
- With **`issuer-uri`** and **`audiences`**, Spring Boot checks the **signature** (keys from the JWKS), **`iss`**, **`exp`/`nbf`** and **`aud`**. The decoder is created on the **first request with a token**.
- **Scopes** become **`SCOPE_` authorities** by default. Writes to events need `SCOPE_events:write`.
- A token API is **stateless**, so **CSRF protection is off**.
- **`401`** means "who are you?", **`403`** means "not allowed". Route Spring Security's errors through the **`HandlerExceptionResolver`** to keep one `ProblemDetail` format.
- Spring Security adds **no-cache headers** only where you haven't set `Cache-Control`. Unknown URLs are now **`401`** for anonymous callers.
- Test **your rules** with **`jwt()`**; it skips the decoder, so it doesn't test signatures.
- **`@SecurityScheme`** and **`@SecurityRequirement`** put the token flow and per-operation scopes in the OpenAPI document.

### Interview angle

<details>
<summary>"How does a resource server validate a JWT access token?"</summary>

It gets the authorisation server's public keys, usually by reading its metadata from the issuer URL to find the JWKS endpoint, and caches them. For each token it picks the key by `kid`, verifies the signature, then checks the claims: `iss` is exactly the trusted issuer, `aud` includes this API, and `exp` and `nbf` are within limits (allowing a little clock skew). Only then does it look at `scope` to decide what the caller may do. No call to the authorisation server is needed per request, which is fast, but means a JWT can't be revoked before it expires, so lifetimes are kept short.

</details>

<details>
<summary>"What's the difference between a 401 and a 403?"</summary>

`401 Unauthorized` means the request isn't authenticated: no token, or one that's malformed, forged, expired or for another API. It comes with a `WWW-Authenticate` header, and the fix is a new, valid token. `403 Forbidden` means the caller is authenticated but not allowed to do this, for example a valid token without the required scope (`error="insufficient_scope"`). A new token with the same permissions won't help.

</details>

<details>
<summary>"Why does the API check the token's audience?"</summary>

An authorisation server usually issues tokens for many APIs. Without an audience check, a token issued for another API could be replayed against this one, and if the scope names happened to match, it would work. Checking that `aud` names this API means each API only accepts tokens made for it. It's one of the checks that's easiest to forget, because everything works without it.

</details>

<details>
<summary>"When would you use client credentials, and when authorisation code?"</summary>

Client credentials is for a service acting **as itself**: a scheduled job, or a back-end service calling another, with no user involved. The client authenticates with its own secret (or something stronger) and gets a token whose subject is the client. Authorisation code, with PKCE, is for acting **on behalf of a user**: the user signs in at the authorisation server and approves the request, and the client gets a token whose subject is the user. Using client credentials where there's really a user loses the user's identity, so the API can't tell whose data is being touched.

</details>

<details>
<summary>"Why can you turn CSRF protection off on this API?"</summary>

CSRF relies on the browser automatically attaching credentials, usually a session cookie, to a request forged by another site. This API doesn't use cookies or sessions: the client sends the token in the `Authorization` header explicitly, which a cross-site form or image can't do. So there's nothing for CSRF to exploit, and the protection would only block legitimate writes. If the API ever authenticated with cookies, it would need CSRF protection again.

</details>

<details>
<summary>"What does spring-security-test's `jwt()` test, and what doesn't it?"</summary>

It puts an already-decoded token, with the claims and authorities you choose, straight into the security context. That tests your authorisation rules and your error handling quickly, with no authorisation server. It skips the `JwtDecoder`, so it doesn't test the signature, issuer, audience or expiry checks. Those come from configuration, and you check them with a few real tokens, by hand or in tests that sign their own.

</details>

### Further reading

- [Spring Security reference: OAuth 2.0 Resource Server, JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Spring Security reference: testing OAuth 2.0 with MockMvc](https://docs.spring.io/spring-security/reference/servlet/test/mockmvc/oauth2.html)
- [Spring Boot reference: Spring Security](https://docs.spring.io/spring-boot/reference/web/spring-security.html), including the authorisation server and resource server properties
- [RFC 6749 section 4.4: the client credentials grant](https://www.rfc-editor.org/rfc/rfc6749#section-4.4) and [RFC 6750: bearer tokens](https://www.rfc-editor.org/rfc/rfc6750)
- [RFC 7519: JSON Web Token](https://www.rfc-editor.org/rfc/rfc7519) and [RFC 8414: authorisation server metadata](https://www.rfc-editor.org/rfc/rfc8414)
- [RFC 9700: OAuth 2.0 Security Best Current Practice](https://www.rfc-editor.org/rfc/rfc9700)
- [Identity provider 01: OAuth 2.0, OpenID Connect and a first identity provider](../identity-provider/01-oauth2-oidc-and-a-first-idp.md), for the authorisation server's filter chains written by hand (optional)

**Simplification to notice:** the organiser's token can change *any* event. That's right for a single trusted service, but a token tells the API who's calling, not which data belongs to them. Customers are different: Alice must never see Bob's bookings, whatever her token's scopes say.

**Next:** [06: Users and their bookings](06-users-and-their-bookings.md). Tokens issued for a person, through Swagger UI's Authorize button, and the ownership checks that top the OWASP API list.
