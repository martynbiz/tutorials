# 02: Securing the API with OAuth 2.0

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- The four OAuth 2.0 roles, and which one an Open Banking API plays: the **resource server**
- What an **access token** is, what's inside a **JWT**, and which claims a resource server must check
- **Scopes** such as `accounts`, and how Spring Security turns them into **authorities**
- How a `SecurityFilterChain` bean protects URLs, and why a token API turns off sessions and CSRF
- How to make Spring Security's **401** and **403** responses `ProblemDetail`s, like every other error
- How to run and test the API **without a real authorisation server**: a dev-only signing key, and spring-security-test's `jwt()`
- Why a servlet filter's **order** matters once security is involved
- How to give Swagger UI an **Authorize** button for bearer tokens
- What **strong customer authentication** and **FAPI** add on top of plain OAuth in banking

By the end, every Open Banking URL needs a valid, signed access token with the `accounts` scope. Without one you get a `401` or `403`, as a `ProblemDetail`, with the interaction id still on it.

---

## Prerequisites for this guide

This guide assumes you've used Spring Security before:

- **The filter chain:** requests pass through a chain of servlet filters before reaching a controller, and Spring Security is one of them.
- **A `SecurityFilterChain` bean** configured with `HttpSecurity` and lambdas (`authorizeHttpRequests`, `requestMatchers`, …), rather than the old `WebSecurityConfigurerAdapter`.
- **Authentication vs authorisation**, and what an `Authentication` and its `GrantedAuthority`s are.

You don't need to have used OAuth before. That's what this guide teaches.

---

## Concepts

### How it works in a real bank

In guide 01 anyone could read any account. A real Open Banking API has to answer three questions on every request:

1. **Who is calling?** Which TPP, and is it really them?
2. **Who are they acting for?** Which customer?
3. **What has that customer allowed them to see?**

UK Open Banking answers all three with **OAuth 2.0** ([RFC 6749](https://www.rfc-editor.org/rfc/rfc6749)). The TPP never sees the customer's password. Instead, the customer logs in **at their own bank**, approves the request, and the bank's **authorisation server** gives the TPP an **access token**. The TPP sends that token with every API call, and the bank's API checks it.

This guide answers the first question with tokens and scopes. Guide 03 builds the consent that answers the third, and guide 04 links the two together.

### The four OAuth roles

| OAuth role | Who it is in Open Banking | In this tutorial |
|---|---|---|
| **Resource owner** | The customer (PSU) who owns the data | Alice and Bob |
| **Client** | The TPP's application | moneywise |
| **Authorisation server** | The bank's login and consent service. It authenticates the customer and **issues** tokens. | Not built. A dev-only stand-in mints tokens. |
| **Resource server** | The bank's API. It **accepts** tokens and serves data. | **This application** |

The split matters. A resource server never sees a password and never issues a token. It only has to decide, for each request, whether the token it was handed is genuine, unexpired, meant for it, and allows what's being asked. That's a far smaller job, and it's what you build here.

### Access tokens and JWTs

An access token is a string sent in a header:

```
Authorization: Bearer eyJraWQiOiJ1ajQw...
```

"Bearer" ([RFC 6750](https://www.rfc-editor.org/rfc/rfc6750)) means *whoever holds this token can use it*, like cash. That's why tokens are short-lived and only travel over TLS.

The token can be **opaque** (a random string the resource server looks up by calling the authorisation server, called *introspection*) or a **JWT**, a JSON Web Token ([RFC 7519](https://www.rfc-editor.org/rfc/rfc7519)) that carries its own data and a signature. A JWT is three base64url-encoded parts separated by dots: `header.payload.signature`. Decoded, the first two of a token you'll mint later in this guide look like this:

```json
{"kid":"uj40DhRzwB2c5RhvVw-FtX_XVq_Yw3prr45ny9EGLpg","typ":"at+jwt","alg":"HS256"}
```

```json
{
  "sub": "moneywise",
  "aud": "open-banking-api",
  "scope": "accounts",
  "iss": "http://localhost:8080/dev",
  "exp": 1794009695,
  "iat": 1794006095,
  "jti": "afc885c9-8a00-499b-9f7e-bc9b495b4ede",
  "client_id": "moneywise"
}
```

[RFC 9068](https://www.rfc-editor.org/rfc/rfc9068) is the standard profile for **JWT access tokens**. It says what the claims mean and what a resource server must check:

| Claim | Meaning | Resource server checks |
|---|---|---|
| `typ` (header) | `at+jwt`: "this is an access token", so an ID token can't be passed off as one | It's `at+jwt` |
| `iss` | Issuer: which authorisation server made it | It's the one we trust |
| `aud` | Audience: which API it's for | It's **us**. A token for some other API must be refused. |
| `exp`, `iat` | Expiry, issued-at (seconds since 1970) | Not expired |
| `sub` | Subject: who the token acts for (a customer, or the client itself) | Present |
| `client_id` | Which client (TPP) it was issued to | Present |
| `jti` | A unique id for this token | Present |
| `scope` | Space-separated permissions, such as `accounts` | Used for authorisation |

And above all: **the signature**. Anyone can write JSON. Only the authorisation server holds the key that makes the signature valid.

> **Sidebar: opaque or JWT?** UK Open Banking doesn't require JWT access tokens. Many banks issue opaque tokens and have the API introspect them. A JWT avoids a network call per request, but it can't be revoked before it expires, because nothing is looked up. Remember that limitation: it's exactly why, in guide 04, the API checks the **consent** in the database on every request instead of trusting what the token says.

### Scopes and authorities

A **scope** is a coarse permission the client asked for and was granted. UK Open Banking uses `accounts` for account information, `payments` for payment initiation, and `openid` for the OpenID Connect login.

Spring Security works in **authorities** (`GrantedAuthority`). The resource server support converts each scope into an authority with a `SCOPE_` prefix, so a token with `"scope": "openid accounts"` becomes an authentication with authorities `SCOPE_openid` and `SCOPE_accounts`. Your security rules then say `hasAuthority("SCOPE_accounts")`.

Scopes are deliberately coarse. "This client may use the accounts API" is a scope. "This client may read Alice's current account's balances until 1 February" is **not**: that's a consent, and it lives in the bank's database (guide 03).

### Strong Customer Authentication and FAPI

Banking adds two layers on top of plain OAuth. You won't build either here, but you should know what they are.

**Strong Customer Authentication (SCA)** is a PSD2 requirement: when a customer logs in to their bank (including when approving a TPP's access), they must prove at least **two** of three things: something they **know** (a password or PIN), something they **have** (their phone, a card reader) and something they **are** (a fingerprint or face). SCA happens at the **authorisation server**, before any token is issued. The resource server relies on it having happened.

In the UK, the FCA changed the rules in 2022 ([PS21/19](https://www.fca.org.uk/publication/policy/ps21-19.pdf)): customers no longer have to re-authenticate with their bank every 90 days to keep an AISP connected. Instead, the AISP must reconfirm the customer's consent at least every 90 days. Check the current [FCA SCA guidance](https://www.fca.org.uk/firms/strong-customer-authentication) before relying on the details.

**FAPI** (the OpenID Foundation's [Financial-grade API](https://openid.net/wg/fapi/) security profiles) tightens OAuth for high-value APIs. The UK Open Banking security profile is based on it. Among other things, FAPI-style profiles require:

- **Strong client authentication.** The TPP proves who it is with a certificate (mutual TLS) or a signed JWT, never a shared password.
- **Sender-constrained tokens.** A token is bound to the TPP's TLS client certificate ([RFC 8705](https://www.rfc-editor.org/rfc/rfc8705)), so a stolen token is useless without the certificate's private key. That removes the "bearer token is like cash" weakness.
- **Signed requests**, so parameters can't be tampered with on the way through the customer's browser.

Mutual TLS and certificate-bound tokens are configured at the TLS and authorisation server level, which is out of scope for this tutorial. The resource server code you write here (checking signature, issuer, audience, expiry and scope) is the same either way.

### Not having an authorisation server

To *run* a resource server you need tokens, and tokens come from an authorisation server. Real options include Keycloak, Spring Authorization Server or a cloud identity provider. Setting one up would take a guide of its own, so this tutorial uses two shortcuts, both clearly labelled:

- **In tests**, spring-security-test's `jwt()` request post-processor puts an already-decoded token straight into the security context, skipping signature checks. Most tests use it.
- **When running locally**, a `dev` Spring profile signs and checks tokens with a **shared secret** (the HS256 algorithm) and exposes a `POST /dev/tokens` endpoint that hands out tokens to anyone who asks.

A real deployment verifies tokens with the authorisation server's **public** keys (RS256, PS256 or ES256), fetched from its JWKS endpoint. The resource server then never holds anything that could *create* a token. A shared secret is only acceptable here because the issuer and the API are the same development process.

---

## Steps

### 1. Add the dependency, and watch everything lock

Add the resource server starter and its test companion to `pom.xml`. In Spring Boot 4 the starters have `security` in their names:

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

The first brings Spring Security 7, its OAuth 2.0 resource server support and the Nimbus JOSE library that handles JWTs. The second brings `spring-security-test`. Reload Maven in IntelliJ (the **M** icon that appears, or **Maven tool window → Reload All Maven Projects**).

Run the tests:

```
[ERROR] Tests run: 2, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 7.729 s <<< FAILURE! -- in com.example.openbanking.api.FapiInteractionIdTest
expected: 404
 but was: 401
[ERROR] Tests run: 7, Failures: 7, Errors: 0, Skipped: 0, Time elapsed: 0.222 s <<< FAILURE! -- in com.example.openbanking.account.AccountApiTest
expected: 200
 but was: 401
```

Just adding Spring Security to the classpath locks **every** URL. That's its secure-by-default behaviour. Now you'll configure what "allowed" means.

### 2. The security filter chain

Create `security/SecurityConfig.java`:

```java
package com.example.openbanking.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
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
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/dev/**").permitAll()
                        .requestMatchers("/open-banking/**").hasAuthority("SCOPE_accounts")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems));
        return http.build();
    }

    /** Turns the token's "scope" claim into authorities: scope "accounts" becomes SCOPE_accounts. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        scopes.setAuthoritiesClaimName("scope");
        scopes.setAuthorityPrefix("SCOPE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(scopes);
        converter.setPrincipalClaimName("sub");
        return converter;
    }
}
```

Reading it from the top:

- **CSRF off, sessions stateless.** CSRF attacks trick a *browser* into sending its cookies to your site. This API has no cookies: the credential is a header the client adds explicitly, which a forged cross-site request can't do. With `STATELESS`, Spring Security never creates an HTTP session, so nothing survives between requests except what the token says.
- **Rules are checked top to bottom, first match wins.** Swagger UI and the OpenAPI document are public. `/dev/**` is public (the endpoint behind it only exists in the dev profile). Every Open Banking URL needs the `accounts` scope. Anything else needs *some* valid token.
- **`oauth2ResourceServer(... jwt(...))`** adds the filter that reads `Authorization: Bearer …`, hands the token to a `JwtDecoder` to check, and converts it into an `Authentication`.
- **`authenticationEntryPoint` and `accessDeniedHandler`** say what to send back on a `401` and a `403`. You'll write `SecurityProblemHandler` in step 4.

The `JwtAuthenticationConverter` bean is how **claims become authorities**. Every value here is actually the default. It's spelled out so you can see where the mapping happens and what to change when an authorisation server does things differently (some put scopes in a claim called `scp`, or roles in a `roles` claim that should become `ROLE_` authorities). Spring Security picks up a bean of this type automatically.

> **In older codebases:** you'll see `class SecurityConfig extends WebSecurityConfigurerAdapter` with an overridden `configure(HttpSecurity http)`, chained calls with `.and()`, and `.antMatchers(...)`. The adapter was removed in Spring Security 6. See the [legacy cheat sheet](../legacy-cheatsheet.md#other-things-youll-see).

### 3. A dev-only way to sign and check tokens

The resource server needs a `JwtDecoder` bean. In production Spring Boot builds one for you from a property such as `spring.security.oauth2.resourceserver.jwt.issuer-uri`, pointing at the real authorisation server. For local development, create `security/DevJwtConfig.java`:

```java
package com.example.openbanking.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * DEVELOPMENT ONLY. Signs and checks tokens with a shared secret (HS256), so you
 * can run the API without a real authorisation server. A real deployment verifies
 * tokens with the authorisation server's public keys instead.
 */
@Configuration
@Profile("dev")
public class DevJwtConfig {

    public static final String ISSUER = "http://localhost:8080/dev";
    public static final String AUDIENCE = "open-banking-api";

    @Bean
    SecretKey devJwtKey(@Value("${openbanking.dev.jwt-secret}") String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey devJwtKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(devJwtKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // RFC 9068 access-token checks: typ at+jwt, exp, iat, sub, jti, client_id, plus our issuer and audience
        decoder.setJwtValidator(JwtValidators.createAtJwtValidator()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .build());
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey devJwtKey) {
        return NimbusJwtEncoder.withSecretKey(devJwtKey).algorithm(MacAlgorithm.HS256).build();
    }
}
```

- **`@Profile("dev")`**: these beans only exist when the `dev` profile is active.
- **`NimbusJwtDecoder.withSecretKey(...)`** checks the signature. By itself, a decoder only checks the signature and the timestamps.
- **`JwtValidators.createAtJwtValidator()`** adds the RFC 9068 checks from the Concepts table. Its `build()` refuses to finish unless you've configured the issuer and the audience, so you can't forget the audience check. Without that check, a token the authorisation server issued for some *other* API (say, the bank's internal HR system) would work here too.
- **`NimbusJwtEncoder.withSecretKey(...)`** is the other half: it *creates* signed tokens with the same key. Only the dev token endpoint uses it.

Make `dev` the default profile. Add to `application.properties`:

```properties
# Use the dev profile unless another one is chosen (see application-dev.properties)
spring.profiles.default=dev
```

`spring.profiles.default` only applies when **no** profile has been activated. Running with `--spring.profiles.active=prod` switches the dev beans off entirely.

And create `src/main/resources/application-dev.properties`:

```properties
# DEVELOPMENT ONLY: the shared secret for signing local test tokens (HS256 needs at least 32 bytes)
openbanking.dev.jwt-secret=dev-only-secret-change-me-0123456789abcdef
```

HS256 requires a key of at least 256 bits (32 bytes). Anything shorter fails at start-up (see Common errors).

> **Sidebar: what production configuration looks like.** With a real authorisation server you'd delete `DevJwtConfig` (or leave it in the `dev` profile) and add one property: `spring.security.oauth2.resourceserver.jwt.issuer-uri=https://auth.yourbank.example`. Spring Boot fetches the server's discovery document, finds its public keys, and builds a decoder that checks the signature and the issuer. You'd still add the audience check, with `spring.security.oauth2.resourceserver.jwt.audiences=open-banking-api`.

### 4. 401 and 403 as `ProblemDetail`

Spring Security rejects requests inside a **servlet filter**, before Spring MVC and your `@RestControllerAdvice` ever run. Left alone, a `401` has an empty body. To keep one error format, hand security's exceptions **back** to Spring MVC's exception handling. Create `security/SecurityProblemHandler.java`:

```java
package com.example.openbanking.security;

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
 * Security rejects requests in a filter, before Spring MVC runs, so its errors
 * would bypass ApiExceptionHandler. This hands them back to Spring MVC's
 * exception resolvers, so 401s and 403s are ProblemDetails like every other error.
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

- **An `AuthenticationEntryPoint`** runs when there's no valid authentication: no token, or a bad one. That's a `401`.
- **An `AccessDeniedHandler`** runs when the caller *is* authenticated but not allowed: a valid token without the `accounts` scope. That's a `403`.
- **The standard `BearerToken…` handlers run first.** They set the status and the `WWW-Authenticate` header that RFC 6750 requires, with details such as `error="invalid_token"` or `error="insufficient_scope"`.
- **Then the `HandlerExceptionResolver`** (the bean Spring MVC itself uses, hence the `@Qualifier`) is asked to handle the exception, which reaches `ApiExceptionHandler` exactly as if a controller had thrown it.

Add the two handlers to `web/ApiExceptionHandler.java` (with imports for `org.springframework.security.access.AccessDeniedException` and `org.springframework.security.core.AuthenticationException`):

```java
    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handle(AuthenticationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setTitle("Unauthorised");
        return problem;
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handle(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "The access token does not allow this request");
        problem.setTitle("Forbidden");
        return problem;
    }
```

The `401` passes on the exception's message, which says *why* the token was refused ("Jwt expired at…"). That's useful to a TPP's developers and reveals nothing secret. The `403` uses a fixed message.

> **Sidebar: the UK standard says a 401 has no body.** The Read/Write API profile asks ASPSPs to return `401` "without any error response", with only the `WWW-Authenticate` header. This series uses `ProblemDetail` for every error to stay consistent. At a real bank you'd follow its standard, which here would simply mean *not* delegating to the resolver in `commence`.

### 5. The dev token endpoint

To call the API with curl you need tokens. Create `security/DevTokenIssuer.java`, which builds one the way an authorisation server would:

```java
package com.example.openbanking.security;

import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DEVELOPMENT ONLY. Mints the kind of access token an authorisation server would issue. */
@Component
@Profile("dev")
public class DevTokenIssuer {

    public static final Duration LIFETIME = Duration.ofHours(1);

    private final JwtEncoder encoder;

    public DevTokenIssuer(JwtEncoder encoder) {
        this.encoder = encoder;
    }

    /**
     * @param clientId  the TPP (OAuth client) the token was issued to
     * @param subject   who the token acts for: a customer id, or the client id itself
     * @param scope     space-separated scopes, such as "accounts"
     * @param consentId the consent the customer authorised, or null for a client-credentials token
     */
    public String mint(String clientId, String subject, String scope, String consentId) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(DevJwtConfig.ISSUER)
                .audience(List.of(DevJwtConfig.AUDIENCE))
                .subject(subject)
                .claim("client_id", clientId)
                .claim("scope", scope)
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(LIFETIME));
        if (consentId != null) {
            claims.claim("consent_id", consentId);
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("at+jwt").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
```

`consent_id` isn't used yet. It's there for guide 04, where a token gets linked to the consent the customer approved.

And `security/DevTokenController.java`:

```java
package com.example.openbanking.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEVELOPMENT ONLY. Hands out tokens to anyone who asks, so you can try the API
 * with curl or Swagger UI. It is NOT an authorisation server: no client
 * authentication, no customer login, no SCA. It only exists in the dev profile.
 */
@RestController
@Profile("dev")
public class DevTokenController {

    private final DevTokenIssuer issuer;

    public DevTokenController(DevTokenIssuer issuer) {
        this.issuer = issuer;
    }

    @PostMapping("/dev/tokens")
    public TokenResponse mint(@Valid @RequestBody TokenRequest request) {
        String subject = request.subject() != null ? request.subject() : request.clientId();
        String token = issuer.mint(request.clientId(), subject, request.scope(), request.consentId());
        return new TokenResponse(token, "Bearer", DevTokenIssuer.LIFETIME.toSeconds());
    }

    public record TokenRequest(@NotBlank String clientId, String subject, @NotBlank String scope,
                               String consentId) {
    }

    /** Field names as in an OAuth 2.0 token response (RFC 6749 section 5.1). */
    public record TokenResponse(@JsonProperty("access_token") String accessToken,
                                @JsonProperty("token_type") String tokenType,
                                @JsonProperty("expires_in") long expiresIn) {
    }
}
```

**Read the Javadoc twice.** This endpoint gives any token to anyone. It's safe only because it exists solely under the `dev` profile, and the `dev` profile must never be active anywhere real. A real token endpoint authenticates the client (with a certificate or signed JWT under FAPI) and, for customer-facing tokens, only issues them after the customer has logged in with SCA.

### 6. Try it

Restart the app. Without a token:

```bash
curl -i localhost:8080/open-banking/v1/aisp/accounts
```

```
HTTP/1.1 401
x-fapi-interaction-id: 085acd6d-0a36-40b7-b5a2-4b4991970df2
WWW-Authenticate: Bearer resource_metadata="http://localhost:8080/.well-known/oauth-protected-resource"
Content-Type: application/problem+json

{"detail":"Full authentication is required to access this resource","instance":"/open-banking/v1/aisp/accounts","status":401,"title":"Unauthorised"}
```

(Security headers such as `Cache-Control` and `X-Frame-Options` are trimmed here.) Notice the `resource_metadata` in `WWW-Authenticate`: Spring Security 7 also serves `/.well-known/oauth-protected-resource`, a small JSON document ([RFC 9728](https://www.rfc-editor.org/rfc/rfc9728)) that tells clients how to use this resource server.

Get a token as moneywise, then use it:

```bash
curl -s -X POST localhost:8080/dev/tokens -H 'Content-Type: application/json' \
     -d '{"clientId":"moneywise","scope":"accounts"}'
```

```json
{"access_token":"eyJraWQiOiJ1ajQwRGhSendCMmM1Umh2VnctRnRYX1hWcV9ZdzNwcnI0NW55OUVHTHBnIiwidHlwIjoiYXQrand0IiwiYWxnIjoiSFMyNTYifQ.eyJzdWIiOiJtb25leXdpc2UiLCJhdWQiOiJvcGVuLWJhbmtpbmctYXBpIiwic2NvcGUiOiJhY2NvdW50cyIsImlzcyI6Imh0dHA6Ly9sb2NhbGhvc3Q6ODA4MC9kZXYiLCJleHAiOjE3OTQwMDk2OTUsImlhdCI6MTc5NDAwNjA5NSwianRpIjoiYWZjODg1YzktOGEwMC00OTliLTlmN2UtYmM5YjQ5NWI0ZWRlIiwiY2xpZW50X2lkIjoibW9uZXl3aXNlIn0.vG-RrBU3JDsIdI7ZVJdb1jLgrrq7p1JpxeH9WhKK1VA","token_type":"Bearer","expires_in":3600}
```

Paste the token into [jwt.io](https://jwt.io/) to see the header and claims from the Concepts section. (Only ever paste **development** tokens into a website.) Then:

```bash
TOKEN=eyJraWQ...   # the access_token value
curl -s localhost:8080/open-banking/v1/aisp/accounts -H "Authorization: Bearer $TOKEN"
```

`200`, with the accounts. A token with the wrong scope, `{"clientId":"moneywise","scope":"payments"}`, gets a `403`:

```
HTTP/1.1 403
WWW-Authenticate: Bearer error="insufficient_scope", error_description="The request requires higher privileges than provided by the access token.", error_uri="https://tools.ietf.org/html/rfc6750#section-3.1"
Content-Type: application/problem+json

{"detail":"The access token does not allow this request","instance":"/open-banking/v1/aisp/accounts","status":403,"title":"Forbidden"}
```

And garbage, `Authorization: Bearer abc.def.ghi`, gets a `401` with `error="invalid_token"` and the detail `An error occurred while attempting to decode the Jwt: Malformed token`.

**But look at the `401` response again.** Is the `x-fapi-interaction-id` header there? It is in the output above, because that output was captured *after* step 8's fix. Before the fix it isn't, and the next step's test proves it.

Add the token steps to `requests.http`. IntelliJ's HTTP client can store a value from one response and use it in later requests:

```http
### Get a dev token for moneywise (dev profile only)
POST http://localhost:8080/dev/tokens
Content-Type: application/json

{"clientId": "moneywise", "scope": "accounts"}

> {% client.global.set("tppToken", response.body.access_token); %}

### List accounts with the token
GET http://localhost:8080/open-banking/v1/aisp/accounts
Authorization: Bearer {{tppToken}}

### No token (401)
GET http://localhost:8080/open-banking/v1/aisp/accounts
```

Update the other `GET` requests from guide 01 to send `Authorization: Bearer {{tppToken}}` too. With curl, use the `TOKEN` shell variable as above.

### 7. Tests with `jwt()`

Most tests shouldn't need a real signed token. They're testing *your* rules, not Nimbus's signature maths. spring-security-test's `jwt()` request post-processor creates an authenticated request directly. Create a helper, `src/test/java/com/example/openbanking/TestTokens.java`:

```java
package com.example.openbanking;

import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Pretend access tokens for MockMvc tests. jwt() skips signature checking and
 * puts an already-decoded token straight into the security context.
 */
public final class TestTokens {

    public static final String TPP = "moneywise";

    private TestTokens() {
    }

    /** A token with the "accounts" scope. */
    public static JwtRequestPostProcessor accounts() {
        return jwt().jwt(token -> token
                .subject(TPP)
                .claim("client_id", TPP)
                .claim("scope", "accounts"));
    }
}
```

`jwt().jwt(customiser)` builds a `Jwt` object with whatever claims you set and derives its authorities from the `scope` claim, so `"accounts"` becomes `SCOPE_accounts`.

Fix the existing tests by adding `.with(accounts())` to every request in `AccountApiTest` and to both requests in `FapiInteractionIdTest`, with `import static com.example.openbanking.TestTokens.accounts;`. For example:

```java
        var response = mvc.get().uri(BASE + "/accounts").with(accounts());
```

Now the security tests. `src/test/java/com/example/openbanking/security/SecurityApiTest.java`:

```java
package com.example.openbanking.security;

import com.example.openbanking.OpenBankingIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@OpenBankingIntegrationTest
class SecurityApiTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void noTokenIs401AsProblemDetail() {
        var response = mvc.get().uri("/open-banking/v1/aisp/accounts").exchange();

        assertThat(response).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getResponse().getHeader("WWW-Authenticate")).startsWith("Bearer");
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Unauthorised");
    }

    @Test
    void tokenWithoutTheAccountsScopeIs403() {
        var response = mvc.get().uri("/open-banking/v1/aisp/accounts")
                .with(jwt().jwt(token -> token.claim("scope", "payments")));

        assertThat(response).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Forbidden");
    }

    @Test
    void apiDocsArePublic() {
        assertThat(mvc.get().uri("/v3/api-docs")).hasStatusOk();
    }
}
```

The `WWW-Authenticate` check uses `startsWith("Bearer")` rather than an exact match, because Spring Security 7 adds the `resource_metadata` parameter you saw in step 6. An exact `"Bearer"` check fails with:

```
expected: "Bearer"
 but was: "Bearer resource_metadata="http://localhost/.well-known/oauth-protected-resource""
```

### 8. The filter-order bug

The UK standard says `x-fapi-interaction-id` goes on **every** response, errors included. Add this test to `FapiInteractionIdTest`:

```java
    @Test
    void isOnErrorsFromSpringSecurityToo() {
        var response = mvc.get().uri("/open-banking/v1/aisp/accounts"); // no token

        assertThat(response).hasStatus(401);
        assertThat(response).headers().containsHeader("x-fapi-interaction-id");
    }
```

Run it. It fails:

```
[check headers contain HTTP header 'x-fapi-interaction-id']
Expecting CaseInsensitiveHeaderNameSet:
  ["WWW-Authenticate",
    "Content-Type",
    "X-Content-Type-Options",
    "X-XSS-Protection",
    "Cache-Control",
    "Pragma",
    "Expires",
    "X-Frame-Options"]
to contain:
  ["x-fapi-interaction-id"]
```

Servlet filters run in **order**. Spring Security's filter chain is registered with a fixed, early order. A plain `@Component` filter without an order gets the *lowest* precedence, so it runs **after** security. When security rejects a request, it writes the `401` and stops the chain, and your filter never runs.

The fix is one annotation on `FapiInteractionIdFilter` (with imports for `org.springframework.core.Ordered` and `org.springframework.core.annotation.Order`):

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // before Spring Security, so rejected requests get the header too
public class FapiInteractionIdFilter extends OncePerRequestFilter {
```

Run the tests again. All green. This is a general lesson: anything that must apply to *every* response (correlation ids, request logging, CORS headers) has to run **before** security.

### 9. Tests with real tokens

`jwt()` skips the `JwtDecoder` entirely, so none of the tests so far prove that signatures, expiry or the RFC 9068 rules are actually checked. A few tests should send real tokens. `src/test/java/com/example/openbanking/security/RealTokenTest.java`:

```java
package com.example.openbanking.security;

import com.example.openbanking.OpenBankingIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** These tests send real signed tokens, so the JwtDecoder and its validators run. */
@OpenBankingIntegrationTest
class RealTokenTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JwtEncoder encoder;

    @Test
    void aTokenFromTheDevEndpointIsAccepted() throws Exception {
        var tokenResponse = mvc.post().uri("/dev/tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"clientId": "moneywise", "scope": "accounts"}
                        """)
                .exchange();
        assertThat(tokenResponse).hasStatusOk();
        String token = JsonPath.read(
                tokenResponse.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.access_token");

        var response = mvc.get().uri("/open-banking/v1/aisp/accounts")
                .header("Authorization", "Bearer " + token);

        assertThat(response).hasStatusOk();
    }

    @Test
    void anExpiredTokenIsRejected() {
        Instant tenMinutesAgo = Instant.now().minus(Duration.ofMinutes(10));
        String token = sign(encoder, validClaims()
                .issuedAt(tenMinutesAgo)
                .expiresAt(tenMinutesAgo.plus(Duration.ofMinutes(5)))
                .build());

        var response = mvc.get().uri("/open-banking/v1/aisp/accounts")
                .header("Authorization", "Bearer " + token);

        assertThat(response).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(response).bodyJson().extractingPath("$.detail").asString().contains("expired");
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() {
        JwtEncoder forger = NimbusJwtEncoder.withSecretKey(new SecretKeySpec(
                        "not-the-real-secret-not-the-real-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .algorithm(MacAlgorithm.HS256)
                .build();
        String forged = sign(forger, validClaims().build());

        var response = mvc.get().uri("/open-banking/v1/aisp/accounts")
                .header("Authorization", "Bearer " + forged);

        assertThat(response).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    /** Every claim the decoder insists on, valid for five minutes. */
    private static JwtClaimsSet.Builder validClaims() {
        Instant now = Instant.now();
        return JwtClaimsSet.builder()
                .issuer(DevJwtConfig.ISSUER)
                .audience(List.of(DevJwtConfig.AUDIENCE))
                .subject("moneywise")
                .claim("client_id", "moneywise")
                .claim("scope", "accounts")
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(5)));
    }

    private static String sign(JwtEncoder encoder, JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("at+jwt").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
```

- **The tests run under the `dev` profile** (it's the default, and the test meta-annotation doesn't change it), so the real `JwtEncoder` bean is available to inject.
- **The expired token is issued ten minutes ago and expired five minutes ago.** `JwtClaimsSet` refuses an expiry *before* the issue time, and Spring's timestamp validator allows 60 seconds of clock skew, so "just expired" isn't enough.
- **The forged token** has perfect claims and a perfectly formed signature, made with the wrong key. That's the attack a signature exists to stop.
- **`JsonPath.read`** (from the Jayway library that Spring's test support already uses) pulls the token out of the response.

### 10. An Authorize button in Swagger UI

Open `http://localhost:8080/swagger-ui.html`. Every call now fails with `401`, because Swagger UI doesn't know how to send a token. Describe the security scheme in the OpenAPI document. Create `api/OpenApiConfig.java`:

```java
package com.example.openbanking.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Describes the bearer-token security in the OpenAPI document, so Swagger UI
 * shows an Authorize button and sends "Authorization: Bearer ..." on every call.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(title = "Open Banking API", version = "v1",
                description = "Account information for authorised third parties (a teaching system)"),
        security = @SecurityRequirement(name = "bearer"))
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {
}
```

- **`@SecurityScheme`** declares a scheme called `bearer`: HTTP authentication with the `Bearer` scheme and a JWT.
- **`security = @SecurityRequirement(name = "bearer")`** applies it to every operation by default.

The generated document at `/v3/api-docs` now contains `"security": [{"bearer": []}]` and a `bearer` entry under `components.securitySchemes`. In Swagger UI: run **POST /dev/tokens** with `{"clientId": "moneywise", "scope": "accounts"}`, copy the `access_token`, click **Authorize** at the top of the page, paste the token (without the word `Bearer`), and every **Try it out** call now sends it.

---

## Your turn

### Exercise 1: the audience check

Write a test in `RealTokenTest` proving that a correctly signed token whose audience is `some-other-api` is refused, and that the `detail` mentions `aud`.

<details>
<summary>Solution</summary>

```java
@Test
void aTokenForAnotherApiIsRejected() {
    String token = sign(encoder, validClaims().audience(List.of("some-other-api")).build());

    var response = mvc.get().uri("/open-banking/v1/aisp/accounts")
            .header("Authorization", "Bearer " + token);

    assertThat(response).hasStatus(HttpStatus.UNAUTHORIZED);
    assertThat(response).bodyJson().extractingPath("$.detail").asString().contains("aud");
}
```

The detail is `An error occurred while attempting to decode the Jwt: The aud claim is not valid`. Try commenting out `.audience(AUDIENCE)` in `DevJwtConfig`: the application no longer starts, failing with `IllegalArgumentException: aud must be validated`. `createAtJwtValidator().build()` insists on an audience check.

</details>

### Exercise 2: more than one scope

Real tokens often carry several scopes, such as `"openid accounts"`. Write a test with a **real** token (not `jwt()`) proving such a token is accepted. Why does it need to be a real token to prove anything about *your* configuration?

<details>
<summary>Solution</summary>

```java
@Test
void scopesAreSpaceSeparated() {
    String token = sign(encoder, validClaims().claim("scope", "openid accounts").build());

    var response = mvc.get().uri("/open-banking/v1/aisp/accounts")
            .header("Authorization", "Bearer " + token);

    assertThat(response).hasStatusOk();
}
```

A real token goes through your `JwtDecoder` *and your `JwtAuthenticationConverter` bean*, which splits `"openid accounts"` into `SCOPE_openid` and `SCOPE_accounts`. `jwt()` builds its own authorities from the claims and never calls your converter, so it can't prove the converter is configured correctly.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
```

(13 from guide 01, 3 in `SecurityApiTest`, 5 in `RealTokenTest` and 1 new test in `FapiInteractionIdTest`.)

New files since guide 01:

```
src/main/java/com/example/openbanking/
├── api/OpenApiConfig.java
└── security/
    ├── DevJwtConfig.java
    ├── DevTokenController.java
    ├── DevTokenIssuer.java
    ├── SecurityConfig.java
    └── SecurityProblemHandler.java
src/main/resources/
└── application-dev.properties
src/test/java/com/example/openbanking/
├── TestTokens.java
└── security/
    ├── RealTokenTest.java
    └── SecurityApiTest.java
```

Modified: `pom.xml`, `application.properties`, `FapiInteractionIdFilter.java` (`@Order`), `ApiExceptionHandler.java`, `AccountApiTest.java`, `FapiInteractionIdTest.java`, `requests.http`.

---

## Common errors

**Every test fails with `expected: 200 but was: 401`**
You've added the dependency but the requests carry no token. Add `.with(accounts())` to them.

**`Method api in com.example.openbanking.security.SecurityConfig required a bean of type 'org.springframework.security.oauth2.jwt.JwtDecoder' that could not be found.`**
The app started with a profile other than `dev` (for example `--spring.profiles.active=prod`), so `DevJwtConfig` didn't load, and nothing else provides a decoder. Either run with `dev`, or configure a real authorisation server with `spring.security.oauth2.resourceserver.jwt.issuer-uri`.

**`Factory method 'jwtEncoder' threw exception with message: com.nimbusds.jose.KeyLengthException: The secret length must be at least 256 bits`**
`openbanking.dev.jwt-secret` is shorter than 32 bytes. Use a longer value.

**`401` with `An error occurred while attempting to decode the Jwt: the given typ value needs to be one of [at+jwt, application/at+jwt]`**
The token's header says `"typ": "JWT"`. The RFC 9068 validator only accepts access tokens. If you mint tokens yourself, set `.type("at+jwt")` on the `JwsHeader`.

**`401` with `An error occurred while attempting to decode the Jwt: client_id must have a value`**
The token has no `client_id` claim. RFC 9068 requires one, and `createAtJwtValidator()` enforces it.

**`401` with `An error occurred while attempting to decode the Jwt: Jwt expired at 2026-11-07T09:06:30Z`**
The token has expired. Dev tokens last an hour. Get a new one.

**`401` with `An error occurred while attempting to decode the Jwt: The aud claim is not valid`**
The token was issued for a different API. That check is doing its job.

**`401` responses have no `x-fapi-interaction-id`**
`FapiInteractionIdFilter` is missing `@Order(Ordered.HIGHEST_PRECEDENCE)`, so it runs after Spring Security. See step 8.

**`401`/`403` responses have an empty body**
`SecurityProblemHandler` isn't wired in: check `.authenticationEntryPoint(problems)` and `.accessDeniedHandler(problems)` are inside `oauth2ResourceServer(...)`, and that `ApiExceptionHandler` has the `AuthenticationException` and `AccessDeniedException` handlers from step 4.

**`expected: "Bearer" but was: "Bearer resource_metadata=..."`**
Spring Security 7 adds protected-resource metadata to `WWW-Authenticate`. Assert with `startsWith("Bearer")`.

**`Cannot resolve symbol 'jwt'` in a test**
Static import `org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt`, and check the `spring-boot-starter-security-oauth2-resource-server-test` dependency is there.

---

## Recap

- In OAuth 2.0 the bank's API is a **resource server**: it accepts tokens, it never issues them or sees passwords. The **authorisation server** authenticates the customer (with **SCA**) and issues tokens.
- A **JWT access token** is signed JSON. Check the **signature, issuer, audience, expiry and type** (`at+jwt`); RFC 9068 lists what's required, and `JwtValidators.createAtJwtValidator()` enforces it.
- **Scopes** are coarse permissions. Spring maps them to `SCOPE_…` authorities through a `JwtAuthenticationConverter`.
- A token API is **stateless**: no sessions, no CSRF protection needed.
- Spring Security's errors happen **in a filter**. Route them to your `@RestControllerAdvice` with the `HandlerExceptionResolver` to keep one error format.
- Filters that must touch **every** response need an **order** ahead of security.
- Test rules with **`jwt()`**. Test token validation with a few **real** signed tokens.
- **FAPI** hardens OAuth for banking: strong client authentication, sender-constrained tokens, signed requests.

### Interview angle

<details>
<summary>"How does a resource server validate a JWT access token?"</summary>

It verifies the signature with the authorisation server's public key (usually fetched from its JWKS endpoint and cached), then checks the claims: the issuer is the trusted one, the audience includes this API, it hasn't expired (with a little clock skew), and for RFC 9068 tokens that the type is `at+jwt` and `sub`, `client_id`, `iat` and `jti` are present. Only then does it look at scopes for authorisation. Mention that an audience check stops a token issued for another API being replayed against this one.

</details>

<details>
<summary>"What's the difference between a 401 and a 403?"</summary>

`401 Unauthorized` means the request isn't authenticated: no token, or a token that's invalid, expired or forged. The response carries `WWW-Authenticate` telling the client how to authenticate. `403 Forbidden` means the caller is authenticated, but isn't allowed to do this: the token lacks the required scope (`insufficient_scope`), or a business rule such as the customer's consent forbids it. Getting a new token fixes a 401. It usually doesn't fix a 403.

</details>

<details>
<summary>"Why don't you need CSRF protection on this API?"</summary>

CSRF works by making a victim's browser send a request that automatically includes their cookies. This API doesn't use cookies or sessions; the client has to put the bearer token in the `Authorization` header explicitly, which a cross-site form or image request can't do. So with stateless token authentication, CSRF protection adds nothing. If you ever authenticate with cookies, turn it back on.

</details>

<details>
<summary>"What does FAPI add on top of OAuth 2.0, and why do banks need it?"</summary>

Plain OAuth leaves choices open that are risky for financial data. FAPI profiles close them: confidential clients must authenticate strongly (mutual TLS or `private_key_jwt`, not shared secrets), access tokens are sender-constrained (bound to the client's certificate, so a stolen token is useless), authorisation requests are signed or pushed so they can't be tampered with, and more. UK Open Banking's security profile is based on FAPI. The resource server code still validates tokens and scopes in the same way, but also checks the certificate binding.

</details>

### Further reading

- [Spring Security reference: OAuth 2.0 Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Spring Security reference: testing OAuth 2.0 with MockMvc](https://docs.spring.io/spring-security/reference/servlet/test/mockmvc/oauth2.html)
- [RFC 9068: JWT Profile for OAuth 2.0 Access Tokens](https://www.rfc-editor.org/rfc/rfc9068) and [RFC 6750: Bearer Token Usage](https://www.rfc-editor.org/rfc/rfc6750)
- [OpenID Foundation: FAPI working group](https://openid.net/wg/fapi/) and the [FAPI 2.0 Security Profile](https://openid.net/specs/fapi-2_0-security-profile.html)
- [FCA: Strong Customer Authentication](https://www.fca.org.uk/firms/strong-customer-authentication)

**Simplification to notice:** any TPP with an `accounts` token can still read *every* customer's accounts. The token says who the caller is, not what the customer agreed to. That's the consent's job.

**Next:** [03: Account-access consents](03-account-access-consents.md). The TPP asks, the customer decides, and the bank keeps the record.
