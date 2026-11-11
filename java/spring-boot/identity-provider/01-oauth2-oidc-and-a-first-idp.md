# 01: OAuth 2.0, OpenID Connect and a first identity provider

> Written for **Spring Boot 4.1.2**, **Spring Security 7.1.2** and **Java 25**. See the [series README](README.md), and set up your **hosts file** before starting.

## What you'll learn

- What problem single sign-on solves, and the difference between **OAuth 2.0** and **OpenID Connect**
- The four OAuth roles, and the **authorisation code flow** step by step, including **PKCE** and `state`
- What an **ID token** and an **access token** are, what's inside a **JWT**, and how anyone can check one with the **JWKS**
- How **Spring Security** works: servlet filters, the `SecurityFilterChain`, `Authentication` and the `SecurityContext`
- How to run Passport, a working OpenID Connect provider, with Spring Security's authorisation server
- How to perform a whole sign-in **by hand**, with a browser and `curl`, so nothing later is magic

By the end you'll have Passport running at `http://login.idp.test:9000`, and a pair of tokens you got from it yourself.

---

## Concepts

### The problem: one person, many websites

A company has a shop, a forum, a help desk and an HR system. If each one keeps its own user table and its own login page:

- every site stores passwords, so every site is a target, and one breach exposes them all (people reuse passwords)
- every site has to get password hashing, lockouts, MFA and password resets right
- users have a password per site, and leavers have an account to disable in each

The alternative is to trust **one** system with identities. Each website sends its users to that system to sign in, and gets back proof of who they are. The website never sees a password. That system is an **identity provider (IdP)**: Microsoft Entra ID (formerly Azure AD), Okta, Auth0, Keycloak, Google, or the Passport you're about to build.

When one IdP serves many sites, signing in to one site means you're already signed in at the IdP, so the next site can let you straight in. That's **single sign-on (SSO)**, and it comes in guide 07.

### OAuth 2.0 and OpenID Connect

**OAuth 2.0** ([RFC 6749](https://www.rfc-editor.org/rfc/rfc6749)) is about **delegated access**. A user lets an application do something on their behalf, such as "let this printing service read my photos", without giving it their password. The application gets an **access token**: a key to an API, with limited permissions (**scopes**). OAuth 2.0 says nothing about *who the user is*.

**OpenID Connect (OIDC)** ([OpenID Connect Core 1.0](https://openid.net/specs/openid-connect-core-1_0.html)) is a thin layer on top of OAuth 2.0 that adds **authentication**: a standard way for the application to learn who signed in. It adds:

- the `openid` scope, which says "this is a sign-in, not just API access"
- an **ID token**: a signed statement from the IdP saying "this is Alice, she signed in at 10:41, and this token is for the Shop"
- a **UserInfo** endpoint, standard **claims** (`name`, `email`, …), and **discovery**, a JSON document describing the IdP

"Sign in with Microsoft" and "Sign in with Google" are OpenID Connect. When this series says "OAuth", it usually means both.

### The four roles

| OAuth 2.0 role | Who | In this series |
|---|---|---|
| **Resource owner** | The person whose data or identity it is | Alice, in her browser |
| **Client** | The application that wants to sign her in, or act for her | The Shop (from guide 02) |
| **Authorisation server** | Authenticates the user, asks for consent, issues tokens | **Passport** |
| **Resource server** | An API that accepts access tokens | Not built here. Passport's own `/userinfo` endpoint is one |

OpenID Connect calls the authorisation server an **OpenID Provider** and the client a **Relying Party (RP)**. You'll see both sets of names in the specifications, and in Spring Security's class names.

### The authorisation code flow

This is how a website signs a user in with an IdP. It's the only flow you need for websites, and the one Entra uses for "Sign in with Microsoft".

```
 Browser              Shop (client)                    Passport (IdP)
    │  GET /account        │                                  │
    │─────────────────────▶│                                  │
    │  302 → Passport      │                                  │
    │◀─────────────────────│                                  │
    │  GET /oauth2/authorize?client_id=shop&redirect_uri=…    │
    │    &scope=openid…&state=…&code_challenge=…              │
    │────────────────────────────────────────────────────────▶│
    │  login page (password, later a code, consent)           │
    │◀───────────────────────────────────────────────────────▶│
    │  302 → shop.test:8080/login/oauth2/code/passport?code=…&state=…
    │◀────────────────────────────────────────────────────────│
    │  GET /login/oauth2/code/passport?code=…&state=…         │
    │─────────────────────▶│  POST /oauth2/token              │
    │                      │   code, code_verifier, secret    │
    │                      │─────────────────────────────────▶│
    │                      │  { id_token, access_token }      │
    │                      │◀─────────────────────────────────│
    │  302 → /account (signed in)                             │
    │◀─────────────────────│                                  │
```

Two kinds of traffic are involved, and the difference matters:

- **Front channel:** everything that goes **through the browser**, as redirects. The user can see and change it. So can malware in the browser, and anything that logs URLs. Only a short-lived, single-use **authorisation code** travels this way.
- **Back channel:** the shop calls Passport's **token endpoint** directly, server to server, proving it's the shop with its **client secret**. The tokens travel this way, and never through the browser.

Step by step:

1. The shop redirects the browser to Passport's **authorisation endpoint**, saying who it is (`client_id`), where to send the user back (`redirect_uri`), and what it wants (`scope`).
2. Passport signs the user in, however it likes: a password, a second factor, consent. The shop never sees any of it.
3. Passport redirects back to the `redirect_uri` with a **code**: a random, single-use string, valid for a few minutes.
4. The shop swaps the code for tokens at the **token endpoint**, authenticating as itself.

Three protections in the flow will come up again and again:

- **`redirect_uri` must match exactly** one that was registered for the client in advance. Otherwise an attacker could ask Passport to send the code to *their* site.
- **`state`** is a random value the shop puts in step 1 and checks in step 3. It ties the response to a login this browser actually started, which stops an attacker logging you in to *their* account (cross-site request forgery on the login).
- **PKCE** (Proof Key for Code Exchange, "pixie", [RFC 7636](https://www.rfc-editor.org/rfc/rfc7636)) protects the code itself:
  - The shop invents a random secret, the **code verifier**, and sends only its SHA-256 hash, the **code challenge**, in step 1.
  - In step 4 it must present the verifier itself.
  - Someone who steals the code from the front channel, from browser history, a log or a malicious extension, can't use it without the verifier, which never left the shop.
  - PKCE was designed for mobile apps, but current best practice ([RFC 9700](https://www.rfc-editor.org/rfc/rfc9700), the OAuth 2.0 Security Best Current Practice, and OAuth 2.1) requires it for **every** client. Spring Security's authorisation server does too, by default, as you'll see.

### Tokens, JWTs and JWKS

The token endpoint returns two tokens:

| | ID token | Access token |
|---|---|---|
| For | The **client** (the shop) | A **resource server** (an API) |
| Says | Who signed in, when, and how | What the bearer may do (scopes) |
| `aud` (audience) | The client's id: `shop` | The API(s) it's for |
| The shop should | Check it, then use it to sign the user in | Send it to APIs, without reading it |

Passport issues both as **JWTs** (JSON Web Tokens, [RFC 7519](https://www.rfc-editor.org/rfc/rfc7519)): three Base64url-encoded parts separated by dots, `header.payload.signature`:

- the **header** says how it's signed (`"alg": "RS256"`) and with which key (`"kid"`, the key id)
- the **payload** is a JSON object of **claims**: `iss` (issuer), `sub` (subject, the user), `aud`, `exp` (expiry), `iat` (issued at), and so on
- the **signature** covers the first two parts

A JWT is **signed, not encrypted**. Anyone holding one can read it. The signature stops anyone *changing* it.

Passport signs with an **RSA private key** that never leaves Passport. It publishes the matching **public keys** as a **JWK Set** (JSON Web Key Set) at its **JWKS endpoint**. Anyone can fetch them and check a signature, but nobody can create one. The `kid` in a token's header says which key in the set to use.

### Discovery

How does a client know where Passport's endpoints are, which algorithms it uses, and where its keys live? It reads Passport's **discovery document**, at a fixed path under the **issuer** URL:

```
http://login.idp.test:9000/.well-known/openid-configuration
```

The **issuer** is Passport's identity: the exact string in the `iss` claim of every token it issues. A client is configured with the issuer only, and works out everything else from discovery. Entra works the same way: its issuer for a tenant is `https://login.microsoftonline.com/{tenant-id}/v2.0`, and its discovery document sits under that URL.

> **Sidebar: why HTTPS is non-negotiable in production.** This series uses plain HTTP so you can read everything. In production every part of this must be HTTPS:
> - The authorisation code, tokens and session cookies would otherwise be readable by anyone on the network.
> - Discovery and the JWKS would otherwise be forgeable: an attacker who can change the JWKS response can make the shop accept tokens they signed themselves.
> - Session cookies need the `Secure` flag, which only works over HTTPS.
> - Passport would also enforce `https` redirect URIs, except for `localhost`-style development.

---

## How Spring Security works

Passport and the shop are both built almost entirely out of Spring Security, so it's worth seeing its shape now. The rest of the series fills in the details.

### Filters

In a Spring web application, every HTTP request passes through a list of **servlet filters** before it reaches your controller. Each filter can look at the request, change it, reject it, or answer it itself without the controller ever running.

Spring Security is one filter, registered by Spring Boot, called `FilterChainProxy`. Inside, it holds one or more **security filter chains**: a `SecurityFilterChain` is a **request matcher**, which decides which requests the chain handles, plus an ordered list of security filters. For each request, `FilterChainProxy` picks the **first** chain whose matcher matches, and runs only that chain's filters.

```
request ─▶ FilterChainProxy ─▶ chain 1 matches?  ─yes─▶ its filters ─▶ controller
                               │ no
                               ▼
                               chain 2 matches?  ─yes─▶ its filters ─▶ controller
```

You define chains as `SecurityFilterChain` beans, built with the `HttpSecurity` builder, and order them with `@Order`. Each call on the builder, such as `.formLogin(...)`, `.csrf(...)` or `.authorizeHttpRequests(...)`, adds or configures one or more filters.

### Authentication and the SecurityContext

When a filter authenticates someone, by checking a submitted password for example, the result is an **`Authentication`** object:

- **`getPrincipal()`**: who it is, typically a `UserDetails` object, or the user's name
- **`getAuthorities()`**: what they're allowed, as strings such as `ROLE_USER`
- **`isAuthenticated()`**: `true` once checked

The current `Authentication` lives in the **`SecurityContext`**, which Spring Security keeps in a thread-local called the **`SecurityContextHolder`** while a request is being handled. Between requests, it's saved in the **HTTP session**. That's what "being logged in" means to Spring Security: your session holds a `SecurityContext` with an `Authentication` in it, and your browser holds the session's cookie (`JSESSIONID`).

Someone who isn't logged in gets an **anonymous** `Authentication`, so filters always have something to look at.

### Authorisation, and what happens when it fails

Near the end of every chain, the **`AuthorizationFilter`** applies your rules, such as "this URL needs any authenticated user". If the rule says no:

- for an **anonymous** user, the **`ExceptionTranslationFilter`** (just before it) starts authentication. It asks an **`AuthenticationEntryPoint`** what to do, usually "redirect to the login page", and first saves the original request so the user can be sent back to it afterwards
- for a user who **is** logged in but not allowed, it returns **403 Forbidden**

Keep this picture in mind: **chain → filters → `Authentication` in the `SecurityContext` → `AuthorizationFilter` decides → entry point if needed.** Everything in this series is a variation on it.

---

## Steps

### 1. Check the hosts file

If you haven't already, add the hosts file line from the [README](README.md#before-you-start-the-hosts-file), then check:

```bash
ping -n 1 login.idp.test
```

(On macOS or Linux, `ping -c 1 login.idp.test`.) You should get replies from `127.0.0.1`.

### 2. Generate the project

Go to [start.spring.io](https://start.spring.io) and choose:

| Setting | Value |
|---|---|
| Project | Maven |
| Spring Boot | 4.1.2 (or the latest 4.1.x) |
| Group | `com.example` |
| Artifact | `idp` |
| Package name | `com.example.idp` |
| Java | 25 |
| Dependencies | **Spring Web**, **Spring Security**, **OAuth2 Authorization Server**, **Thymeleaf** |

Generate it, and unzip it so the project is at `tutorials/workspace/identity-provider/idp/`. Open it in IntelliJ and delete the generated test class under `src/test`: this series checks its work by hand.

The **OAuth2 Authorization Server** dependency appears in `pom.xml` as `spring-boot-starter-security-oauth2-authorization-server`. It brings Spring Security's authorisation server and the Nimbus library that creates and signs JWTs.

### 3. Configure the issuer and the client

Replace `src/main/resources/application.properties` with:

```properties
spring.application.name=idp
server.port=9000

# The issuer: who signs the tokens. It appears in every token and in the discovery document.
spring.security.oauth2.authorizationserver.issuer=http://login.idp.test:9000

# The shop, registered as a client of this identity provider
spring.security.oauth2.authorizationserver.client.shop.registration.client-id=shop
spring.security.oauth2.authorizationserver.client.shop.registration.client-secret={noop}shop-secret
spring.security.oauth2.authorizationserver.client.shop.registration.client-authentication-methods=client_secret_basic
spring.security.oauth2.authorizationserver.client.shop.registration.authorization-grant-types=authorization_code
spring.security.oauth2.authorizationserver.client.shop.registration.redirect-uris=http://shop.test:8080/login/oauth2/code/passport
spring.security.oauth2.authorizationserver.client.shop.registration.scopes=openid,profile,email
```

- **The issuer must be exactly the URL clients use.** Tokens say `"iss": "http://login.idp.test:9000"`, and the shop will reject any token whose issuer doesn't match the one it was configured with, character for character.
- **A client registration** is Passport's record of an application allowed to use it. Like registering an app in the Entra portal, it says:
  - its **client id** and **secret**
  - how it proves who it is: `client_secret_basic` means an HTTP Basic `Authorization` header
  - which **grant types** (flows) it may use
  - the **exact** redirect URIs it may receive codes at
  - which **scopes** it may ask for
- **`{noop}shop-secret`**: Spring Security stores secrets and passwords with a prefix naming the algorithm used to hash them. `{noop}` means "not hashed", which is fine for one development secret, and fixed in guide 04.
- **The redirect URI** `http://shop.test:8080/login/oauth2/code/passport` is where Spring Security's client support will listen in guide 02. `passport` is the name the shop will give this provider.

These properties keep the client in memory. Guide 04 moves clients into a database.

### 4. Two security filter chains

Create `security/SecurityConfig.java`:

```java
package com.example.idp.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

@Configuration
public class SecurityConfig {

    /** The OAuth2 and OpenID Connect protocol endpoints: /oauth2/authorize, /oauth2/token, /userinfo, ... */
    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .oauth2AuthorizationServer(authorizationServer -> {
                    http.securityMatcher(authorizationServer.getEndpointsMatcher());
                    authorizationServer.oidc(Customizer.withDefaults());
                })
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    /** Everything else: the login page, and Passport's own pages. */
    @Bean
    @Order(2)
    SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .formLogin(Customizer.withDefaults());
        return http.build();
    }

    /** One user, held in memory, for now. Guide 03 moves users into a database. */
    @Bean
    UserDetailsService userDetailsService() {
        UserDetails alice = User.withUsername("alice")
                .password("{noop}password")
                .roles("USER")
                .build();
        return new InMemoryUserDetailsManager(alice);
    }
}
```

This is the shape every Spring authorisation server has: **two chains**.

**Chain 1, the protocol endpoints** (`@Order(1)`, so it's checked first):

- **`http.oauth2AuthorizationServer(...)`** adds the authorisation server's filters: one per endpoint (authorise, token, JWKS, discovery, and others), plus filters that authenticate the *client* on the token endpoint.
- **`http.securityMatcher(authorizationServer.getEndpointsMatcher())`** restricts this chain to exactly those endpoints. Requests for anything else fall through to chain 2.
- **`.oidc(Customizer.withDefaults())`** turns on OpenID Connect on top of plain OAuth 2.0: ID tokens, `/userinfo`, the discovery document, and logout (guide 07). `Customizer.withDefaults()` means "the defaults, no changes".
- **`anyRequest().authenticated()`** means every request here needs *someone* authenticated. Which someone depends on the endpoint:
  - the **user**, for the authorisation endpoint
  - the **client**, for the token endpoint (via its secret)
  - a **bearer token**, for `/userinfo`
  - Discovery and the JWKS are public. The authorisation server lets those through itself, before this rule runs.
- **`exceptionHandling(...)`**: when a browser (one that accepts `text/html`) reaches the authorisation endpoint without being logged in, use a `LoginUrlAuthenticationEntryPoint` to redirect it to `/login`. That's the entry point from **How Spring Security works**. API-style callers get a 401 instead.

**Chain 2, everything else** (`@Order(2)`, with no `securityMatcher`, so it matches any request chain 1 didn't):

- **`formLogin(Customizer.withDefaults())`** adds the filters for a form login. One serves a login page at `/login` (Spring Security generates a plain one), and another checks the submitted username and password. Guide 03 replaces the page with Passport's own.

**The user** comes from a **`UserDetailsService`**: the interface Spring Security calls to load a user by username, when checking a password. `InMemoryUserDetailsManager` keeps users in a map. `{noop}password` is an unhashed password, which guide 03 replaces with BCrypt.

> **Sidebar: why two chains?** The two halves of Passport behave differently. The protocol endpoints answer machines in JSON, authenticate clients with secrets and tokens, and are protected by `state` and PKCE rather than CSRF tokens. The login page answers people in HTML, authenticates them with a form, and needs CSRF protection. One chain per kind of traffic keeps each set of rules simple. You'll meet this pattern in many Spring applications.

### 5. Run it, and read the discovery document

Run `IdpApplication` (the green arrow next to `main`), or from the project folder:

```bash
./mvnw spring-boot:run
```

(In PowerShell, `.\mvnw spring-boot:run`.) Wait for `Tomcat started on port 9000`.

Open `http://login.idp.test:9000/.well-known/openid-configuration` in your browser (Firefox formats JSON nicely), or use curl:

```bash
curl http://login.idp.test:9000/.well-known/openid-configuration
```

> **Windows PowerShell:** `curl` there is an alias for `Invoke-WebRequest`, which takes different options. Type **`curl.exe`** instead, in every command in this series. Git Bash, macOS and Linux have the real `curl`.

You'll see (trimmed):

```json
{
  "issuer": "http://login.idp.test:9000",
  "authorization_endpoint": "http://login.idp.test:9000/oauth2/authorize",
  "token_endpoint": "http://login.idp.test:9000/oauth2/token",
  "jwks_uri": "http://login.idp.test:9000/oauth2/jwks",
  "userinfo_endpoint": "http://login.idp.test:9000/userinfo",
  "end_session_endpoint": "http://login.idp.test:9000/connect/logout",
  "response_types_supported": ["code"],
  "grant_types_supported": ["authorization_code", "client_credentials", "refresh_token", "..."],
  "code_challenge_methods_supported": ["S256"],
  "id_token_signing_alg_values_supported": ["RS256"],
  "scopes_supported": ["openid"]
}
```

Everything a client needs is here: where to send the user (`authorization_endpoint`), where to swap the code (`token_endpoint`), where the public keys are (`jwks_uri`), and that PKCE uses SHA-256 (`S256`). The supported grant types are what the *server* can do. Each client is still limited to the grant types in its own registration.

Now the keys: open `http://login.idp.test:9000/oauth2/jwks`:

```json
{
  "keys": [
    {
      "kty": "RSA",
      "e": "AQAB",
      "kid": "2429957c-d8f1-47f7-accc-47dbbe72b4cd",
      "n": "n1cKNtN6gP_4BmKuwyfOFKLA4QzzvSNp_eNwVhkqlrvN4u_..."
    }
  ]
}
```

One RSA **public** key: `n` (the modulus) and `e` (the exponent), with an id, `kid`. Note the `kid`, then stop Passport, start it again, and reload this page. The `kid` has changed. Spring Boot generated a new key pair in memory at start-up, and threw the old one away. Hold that thought until guide 04.

### 6. Look at the filter chains

Spring Security logs each chain's filters at start-up, at `DEBUG` level. Turn that on for one run with a **command-line property**: any property can be overridden when you start the application, without editing `application.properties`.

- **IntelliJ:** **Run → Edit Configurations…**, select `IdpApplication`, **Modify options → Program arguments**, and enter `--logging.level.org.springframework.security=DEBUG`.
- **Terminal:**

  ```bash
  ./mvnw spring-boot:run -Dspring-boot.run.arguments=--logging.level.org.springframework.security=DEBUG
  ```

Search the log for `Will secure`. There are two lines, one per chain. Shortened, the first is:

```
Will secure ...OAuth2AuthorizationServerConfigurer$$Lambda... with filters: DisableEncodeUrlFilter,
    WebAsyncManagerIntegrationFilter, SecurityContextHolderFilter, AuthorizationServerContextFilter,
    HeaderWriterFilter, CsrfFilter, OidcLogoutEndpointFilter, LogoutFilter,
    OAuth2AuthorizationServerMetadataEndpointFilter, OAuth2AuthorizationCodeRequestValidatingFilter,
    OidcProviderConfigurationEndpointFilter, NimbusJwkSetEndpointFilter,
    OAuth2ProtectedResourceMetadataFilter, OAuth2ClientAuthenticationFilter,
    BearerTokenAuthenticationFilter, RequestCacheAwareFilter, SecurityContextHolderAwareRequestFilter,
    AnonymousAuthenticationFilter, ExceptionTranslationFilter, AuthorizationFilter,
    OAuth2AuthorizationEndpointFilter, OAuth2TokenEndpointFilter,
    OAuth2TokenIntrospectionEndpointFilter, OAuth2TokenRevocationEndpointFilter,
    OidcUserInfoEndpointFilter
```

and the second:

```
Will secure any request with filters: DisableEncodeUrlFilter, WebAsyncManagerIntegrationFilter,
    SecurityContextHolderFilter, HeaderWriterFilter, CsrfFilter, LogoutFilter,
    UsernamePasswordAuthenticationFilter, DefaultResourcesFilter, DefaultLoginPageGeneratingFilter,
    DefaultLogoutPageGeneratingFilter, RequestCacheAwareFilter,
    SecurityContextHolderAwareRequestFilter, AnonymousAuthenticationFilter,
    ExceptionTranslationFilter, AuthorizationFilter
```

The concepts from earlier, as real classes:

- **`SecurityContextHolderFilter`** loads the `SecurityContext` from the session at the start of each request.
- **`OidcProviderConfigurationEndpointFilter`** and **`NimbusJwkSetEndpointFilter`** answer discovery and the JWKS themselves. They sit *before* `AuthorizationFilter`, which is why those endpoints are public.
- **`OAuth2ClientAuthenticationFilter`** checks the client's secret on the token endpoint, and puts the *client* in the `SecurityContext` as the `Authentication`.
- **`UsernamePasswordAuthenticationFilter`**, in chain 2, checks the login form's username and password.
- **`AnonymousAuthenticationFilter`** gives everyone else an anonymous `Authentication`.
- **`ExceptionTranslationFilter`** and **`AuthorizationFilter`** apply the rules, and start a login when they fail.
- The **endpoint filters**, such as `OAuth2AuthorizationEndpointFilter` and `OAuth2TokenEndpointFilter`, run *after* `AuthorizationFilter`, so they only see requests the rules allowed.

Remove the program argument again: the log is very chatty.

### 7. Sign in by hand: the authorisation request

Now play the shop yourself. There's no shop yet, so you'll do its two jobs by hand: send the browser to Passport, and swap the code for tokens.

First, PKCE. The shop would invent a random **code verifier** and send its SHA-256 hash, Base64url-encoded, as the **code challenge**. Use this verifier:

```
this-is-my-pkce-code-verifier-for-guide-01-do-not-reuse
```

Compute its challenge in **JShell** (type `jshell` in a terminal, then each line):

```java
import java.security.MessageDigest;
import java.util.Base64;
var verifier = "this-is-my-pkce-code-verifier-for-guide-01-do-not-reuse"
var hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes())
Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
```

```
$5 ==> "rdUsJmvzIs9D2n3SzvvAKu30jjEjF3SYozOcYuRQ9xQ"
```

(A real verifier is random, between 43 and 128 characters, and used once. This one is fixed so your results match the guide.)

Open a **private** browser window, with the developer tools open on the **Network** tab and **Preserve log** ticked, and go to this URL (one line):

```
http://login.idp.test:9000/oauth2/authorize?response_type=code&client_id=shop&scope=openid%20profile&redirect_uri=http://shop.test:8080/login/oauth2/code/passport&state=xyz&code_challenge=rdUsJmvzIs9D2n3SzvvAKu30jjEjF3SYozOcYuRQ9xQ&code_challenge_method=S256
```

| Parameter | Meaning |
|---|---|
| `response_type=code` | "Send me back an authorisation code" (the authorisation code flow) |
| `client_id=shop` | Who's asking |
| `scope=openid profile` | `openid` makes it an OpenID Connect sign-in. `%20` is a URL-encoded space |
| `redirect_uri=…` | Where to send the code. It must match the registration exactly |
| `state=xyz` | Echoed back unchanged. A real client uses a random value |
| `code_challenge=…`, `code_challenge_method=S256` | PKCE |

You land on Spring Security's generated **Please sign in** page. In the Network tab, the authorise request answered `302` with `Location: http://login.idp.test:9000/login`: `AuthorizationFilter` refused an anonymous user, and `ExceptionTranslationFilter` saved the request and used the login entry point.

Sign in as `alice` / `password`. Passport sends you back to the saved authorise request, which now succeeds, and redirects to the shop:

```
http://shop.test:8080/login/oauth2/code/passport?code=36DBXAm2rp9zSLJyMDYKsOXfQdgjzGBRl0L6jPzh1E8...&state=xyz
```

There's no shop, so the browser shows a "can't connect" or "refused to connect" error. That's expected. The **address bar** has what you need: copy the `code` value (everything after `code=` up to `&state`). Notice `state=xyz` came back unchanged.

**Be quick.** The code expires five minutes after it was issued, and works only once.

### 8. Sign in by hand: the token request

Now be the shop's back channel. In a terminal, paste your code into this command (one line):

```bash
curl -u shop:shop-secret -d grant_type=authorization_code -d code=PASTE_THE_CODE_HERE -d redirect_uri=http://shop.test:8080/login/oauth2/code/passport -d code_verifier=this-is-my-pkce-code-verifier-for-guide-01-do-not-reuse http://login.idp.test:9000/oauth2/token
```

- **`-u shop:shop-secret`** sends the client credentials as HTTP Basic authentication: `client_secret_basic`, as registered.
- **`-d`** makes it a `POST` with form fields: the code, the same `redirect_uri` as before (Passport checks they match), and the PKCE **verifier**.

The response (tokens shortened):

```json
{
  "access_token": "eyJraWQiOiIyNDI5OTU3Yy1kOGYxLTQ3ZjctYWNjYy00N2RiYmU3MmI0Y2QiLCJhbGciOiJSUzI1NiJ9.eyJzdWIi...",
  "scope": "openid profile",
  "id_token": "eyJraWQiOiIyNDI5OTU3Yy1kOGYxLTQ3ZjctYWNjYy00N2RiYmU3MmI0Y2QiLCJhbGciOiJSUzI1NiJ9.eyJzdWIi...",
  "token_type": "Bearer",
  "expires_in": 299
}
```

You've signed in with OpenID Connect, by hand. The access token lasts five minutes (`expires_in` is in seconds), and there's no refresh token, because the client isn't registered for that grant.

### 9. Read the tokens

Decode the ID token's payload, the part between the two dots, in JShell:

```java
var token = "PASTE_THE_ID_TOKEN_HERE"
new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]))
```

> Websites such as jwt.io decode tokens too, but don't paste *real* tokens into someone else's website. These development tokens are harmless. A production ID token is a key to someone's session.

The ID token:

```json
{
  "sub": "alice",
  "aud": "shop",
  "azp": "shop",
  "auth_time": 1794395290,
  "iss": "http://login.idp.test:9000",
  "exp": 1794397091,
  "iat": 1794395291,
  "jti": "dae84bc9-c1d9-4798-8e71-863f887b84eb",
  "sid": "mnQt68yZRKTobhlLxq43eyLUosroQ8K6Cl5EnB2LxNc"
}
```

| Claim | Meaning |
|---|---|
| `iss` | Issuer: who made it. Must equal the issuer the client trusts |
| `sub` | Subject: the user's identifier at this issuer |
| `aud` | Audience: who it's for. The shop must reject an ID token meant for another client |
| `azp` | Authorised party: the client it was issued to |
| `iat`, `exp` | Issued at and expires, in seconds since 1970. ID tokens last 30 minutes here |
| `auth_time` | When the user actually signed in, which may be earlier than `iat` |
| `jti` | A unique id for this token |
| `sid` | The user's **session** id at Passport, used by logout in guide 07 |

There's no `name` or `email` yet, even though you asked for `profile`: Passport doesn't know anything about Alice except her username. Guide 05 adds claims.

Decode the header (`[0]` instead of `[1]`) as well: `{"kid":"2429957c-…","alg":"RS256"}`. That's the `kid` of the key in the JWKS, so anyone can find the right public key and check the signature.

The access token's payload has `"scope": ["openid", "profile"]` and `"aud": "shop"`, and expires five minutes after it was issued. Use it as Passport's own API expects, as a **bearer token**:

```bash
curl -H "Authorization: Bearer PASTE_THE_ACCESS_TOKEN_HERE" http://login.idp.test:9000/userinfo
```

```json
{"sub":"alice"}
```

**"Bearer"** means whoever holds the token may use it, like cash. That's why tokens must only travel over the back channel and HTTPS.

### 10. Try to break it

Each of these is a protection from the Concepts section, doing its job. Get a fresh code first: in the same private window, visit the authorise URL from step 7 again. This time there's **no login page**, and you go straight to the shop's address with a new code, because Passport remembers you in its session. That's the beginning of single sign-on.

**Use a code twice.** Run the same token request again with the same code:

```json
{"error":"invalid_grant"}
```

Now try the `/userinfo` request with the access token from the **first** exchange of that code: it's `401` too. When a code is reused, Passport assumes it was stolen, and **revokes the tokens already issued for it** ([RFC 6749, section 4.1.2](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.2)).

**Leave out, or change, the PKCE verifier** (with a fresh code):

```json
{"error":"invalid_grant"}
```

Without the verifier, a stolen code is useless.

**Use the wrong client secret:**

```bash
curl -i -u shop:wrong -d grant_type=authorization_code -d code=abc http://login.idp.test:9000/oauth2/token
```

```
HTTP/1.1 401
...
{"error":"invalid_client"}
```

**Ask for the code to go somewhere else.** Change the authorise URL's `redirect_uri` to `http://evil.test/steal`. Passport answers `400 Bad Request` itself, and does **not** redirect. Redirecting an unknown URI would send the code to whoever chose it.

**Leave out PKCE.** Remove `&code_challenge=…&code_challenge_method=S256` from the authorise URL. Passport checks the request before it even asks you to sign in, and sends you straight back with an error instead of a code:

```
http://shop.test:8080/login/oauth2/code/passport?error=invalid_request&error_description=OAuth%202.0%20Parameter%3A%20code_challenge&...
```

Spring Security's authorisation server requires PKCE from every client unless a client's registration explicitly turns it off, which you never should. This follows OAuth 2.1 and RFC 9700.

---

## Your turn

### Exercise 1: a second user

Add **bob** (password `password`, role `USER`) to Passport, and get tokens for him by hand.

Use a new private window, so Passport doesn't remember Alice.

<details>
<summary>Hint</summary>

`InMemoryUserDetailsManager` takes any number of `UserDetails`. Then repeat steps 7 and 8 in a fresh private window.

</details>

<details>
<summary>Solution</summary>

```java
    @Bean
    UserDetailsService userDetailsService() {
        UserDetails alice = User.withUsername("alice")
                .password("{noop}password")
                .roles("USER")
                .build();
        UserDetails bob = User.withUsername("bob")
                .password("{noop}password")
                .roles("USER")
                .build();
        return new InMemoryUserDetailsManager(alice, bob);
    }
```

Restart Passport. In a new private window, visit the authorise URL from step 7, sign in as `bob`, and swap the code as in step 8. The ID token's `sub` is `"bob"`. Because Passport restarted, the `kid` in the header is new too.

</details>

### Exercise 2: only `openid`

Repeat the flow asking only for `scope=openid`. Before you decode the tokens, predict what's different, then check.

<details>
<summary>Solution</summary>

In the authorise URL, use `scope=openid` (no `%20profile`). The token response then says `"scope": "openid"`, and the access token's `scope` claim is `["openid"]`. The ID token looks the same as before, because Passport doesn't add profile claims yet (guide 05). `/userinfo` still returns `{"sub":"alice"}`: an `openid` scope is always enough to ask who you are.

</details>

---

## Checkpoint

- `http://login.idp.test:9000/.well-known/openid-configuration` shows `"issuer": "http://login.idp.test:9000"`.
- The authorise URL from step 7 leads to a login page, then to the shop's address with a `code`.
- Swapping the code at the token endpoint returns an `id_token` and an `access_token`, and `/userinfo` with the access token returns `{"sub":"alice"}`.

Your project:

```
idp/
├── pom.xml
└── src/main/
    ├── java/com/example/idp/
    │   ├── IdpApplication.java
    │   └── security/
    │       └── SecurityConfig.java
    └── resources/
        └── application.properties
```

---

## Common errors

**The browser says `login.idp.test`'s server can't be found** (`DNS_PROBE_FINISHED_NXDOMAIN`)
The hosts file line is missing, misspelt, or wasn't saved (on Windows, Notepad must run as administrator). Check with `ping`. Some browsers cache DNS failures: close all browser windows and try again.

**`Web server failed to start. Port 9000 was already in use.`**
Passport is already running, perhaps once from IntelliJ and once from a terminal. Stop one.

**The browser goes straight to the shop with `error=invalid_request&error_description=OAuth 2.0 Parameter: code_challenge`**
The authorise URL had no `code_challenge`. PKCE is required.

**`{"error":"invalid_grant"}` from the token endpoint**
The code is wrong, expired (after five minutes), already used, or the `redirect_uri` or `code_verifier` doesn't match the authorisation request. Get a fresh code, and copy it exactly: it ends before `&state`.

**`{"error":"invalid_client"}` with `401`**
Wrong client id or secret. In **PowerShell**, make sure you typed `curl.exe`: plain `curl` there doesn't understand `-u`.

**`400 Bad Request` from `/oauth2/authorize`, with no redirect**
Unknown `client_id`, or a `redirect_uri` that doesn't exactly match the registration (check `http` vs `https`, the port, and any trailing `/`).

**`/userinfo` returns `401` with a token that worked a minute ago**
Access tokens last five minutes. Or you reused its code, which revoked it. Or Passport restarted, which (until guide 04) loses both the authorisation and the signing key.

---

## Recap

- An **identity provider** signs users in for many applications, which then never see passwords. Sharing one IdP session across them is **single sign-on**.
- **OAuth 2.0** delegates access with **access tokens**. **OpenID Connect** adds sign-in, with the **ID token**, `/userinfo` and **discovery**.
- The **authorisation code flow** sends only a short-lived, single-use **code** through the browser (front channel). The client swaps it for tokens server to server (back channel), authenticating with its own secret.
- **Exact redirect URIs**, **`state`** and **PKCE** protect the code. Spring Security's authorisation server requires PKCE from every client.
- Tokens are **signed JWTs**: readable by anyone, changeable by no one. The public keys are published in the **JWKS**, and found by `kid`.
- **Spring Security** is a chain of **filters** per `SecurityFilterChain`. Authentication puts an **`Authentication`** in the **`SecurityContext`** (kept in the session). `AuthorizationFilter` applies the rules, and `ExceptionTranslationFilter` starts a login through an **`AuthenticationEntryPoint`**.
- An authorisation server has **two chains**: the protocol endpoints, and the login page and everything else.

### Interview angle

<details>
<summary>"What's the difference between OAuth 2.0 and OpenID Connect?"</summary>

OAuth 2.0 is an authorisation framework. It lets a user grant an application limited access to an API, represented by an access token with scopes, but it doesn't define how the application learns who the user is. OpenID Connect is an identity layer on OAuth 2.0: with the `openid` scope, the provider also returns an ID token, a signed JWT about the authentication event (issuer, subject, audience, times, nonce), and offers a UserInfo endpoint and discovery. Using an OAuth access token as proof of login is a classic mistake, because it wasn't issued *to* the client and doesn't say who it was for.

</details>

<details>
<summary>"Walk me through the authorisation code flow with PKCE."</summary>

The client creates a random code verifier and sends its SHA-256 hash as the code challenge, with its client id, an exactly registered redirect URI, the scopes and a random state, by redirecting the browser to the authorisation endpoint. The provider authenticates the user and redirects back with a one-time authorisation code and the state. The client checks the state, then POSTs the code, the redirect URI and the verifier to the token endpoint, authenticating with its own credentials. The provider checks that the verifier hashes to the challenge before issuing tokens. A stolen code is useless without the verifier, and the tokens never pass through the browser.

</details>

<details>
<summary>"Why does an authorisation server publish a JWKS?"</summary>

So anyone can verify its signatures without being able to create them. Tokens are signed with a private key that never leaves the server, and the JWKS publishes the matching public keys, each with a key id. A token's header names the `kid` used, so verifiers pick the right key and refetch the set when they see an unknown `kid`. That's also what makes key rotation possible: publish the new key, start signing with it, and remove the old key only once tokens signed with it have expired.

</details>

### Further reading

- [RFC 6749: The OAuth 2.0 Authorization Framework](https://www.rfc-editor.org/rfc/rfc6749)
- [RFC 7636: Proof Key for Code Exchange (PKCE)](https://www.rfc-editor.org/rfc/rfc7636)
- [RFC 9700: Best Current Practice for OAuth 2.0 Security](https://www.rfc-editor.org/rfc/rfc9700)
- [OpenID Connect Core 1.0](https://openid.net/specs/openid-connect-core-1_0.html)
- [Spring Security reference: Authorization Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/index.html)
- [Microsoft identity platform: the OAuth 2.0 authorization code flow](https://learn.microsoft.com/entra/identity-platform/v2-oauth2-auth-code-flow): the same flow, as Entra implements it

**Next:** [02: The Shop: signing in with Passport](02-the-shop-client.md). A real client does all of this for you.
