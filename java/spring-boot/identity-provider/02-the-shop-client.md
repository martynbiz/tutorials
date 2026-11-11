# 02: The Shop: signing in with Passport

> Written for **Spring Boot 4.1.2**, **Spring Security 7.1.2** and **Java 25**. Builds on [guide 01](01-oauth2-oidc-and-a-first-idp.md).

## What you'll learn

- How to make a Spring Boot website an **OpenID Connect client** ("Relying Party") with `oauth2Login`
- How the client finds everything it needs from Passport's **issuer** alone
- How to follow a real sign-in, hop by hop, in the browser's developer tools
- What the client checks in the ID token (signature, issuer, audience, expiry and **nonce**), and why
- What `OidcUser`, `OAuth2AuthenticationToken` and the `SecurityContext` hold after sign-in
- Why the two sites keep **separate sessions**, and why the session id changes when you sign in

By the end, the shop at `http://shop.test:8080` has a public home page and a **My account** page that only Passport users can see.

---

## Concepts

### What the client has to do

In guide 01 you played the shop by hand. A real client has to:

1. Notice that a page needs a signed-in user, and **remember** which page it was.
2. Generate a random **`state`**, a random **`nonce`** and a **PKCE verifier**, and keep them in the user's session.
3. Redirect to Passport's authorisation endpoint.
4. At its redirect URI, check that the `state` matches the one in the session, then swap the code for tokens with its secret and the verifier.
5. **Validate the ID token** (below), then create a signed-in session for the user.
6. Send the user back to the page from step 1.

Spring Security's **OAuth2 Client** support does all six. You give it the issuer and the client credentials.

### Validating the ID token

The ID token is the only proof the shop gets of who signed in, so it checks it thoroughly before trusting a word:

| Check | Why |
|---|---|
| **Signature**, with the key from Passport's JWKS whose `kid` matches the header | Proves Passport made it, and nobody changed it |
| **`iss`** is exactly the configured issuer | A token from another provider, even a genuine one, proves nothing here |
| **`aud`** contains this client's id (`shop`) | A token Passport issued to *another* website must not log you in here. Otherwise any site you sign in to could replay your token at the shop |
| **`exp`** is in the future, and `iat` is reasonable | Old tokens are refused |
| **`nonce`** equals the one the shop put in its authorisation request | Ties the token to *this* login attempt, so a token captured earlier can't be replayed |

The `nonce` and `state` sound alike, but they protect different things. `state` protects the **redirect** (the code arrives in response to a login this browser started). `nonce` protects the **token** (the ID token was issued for that login).

### Two sites, two sessions

Cookies belong to a **host**. The shop's session cookie is sent only to `shop.test`, and Passport's only to `login.idp.test`. So the browser holds **two** sessions:

- **Passport's session** says "this browser has signed in to Passport as Alice". It's what lets Passport skip the login page next time: that's single sign-on.
- **The shop's session** says "this browser is Alice, according to an ID token from Passport". It's what the shop checks on every page.

They live and die separately. That separation is what guide 07's logout has to deal with.

> **Sidebar: why not `localhost`?** Cookies ignore port numbers. If Passport ran on `localhost:9000` and the shop on `localhost:8080`, both would set a cookie called `JSESSIONID` for `localhost`, and each would keep overwriting the other's. Signing in would then fail in confusing ways, often as a redirect loop. That's the most common problem in local OAuth set-ups, and it's why this series uses a separate hostname per site.

---

## Steps

### 1. Generate the project

At [start.spring.io](https://start.spring.io):

| Setting | Value |
|---|---|
| Spring Boot | 4.1.2 |
| Group / Artifact | `com.example` / `shop` |
| Package name | `com.example.shop` |
| Java | 25 |
| Dependencies | **Spring Web**, **OAuth2 Client**, **Thymeleaf** |

Unzip it to `tutorials/workspace/identity-provider/shop/`, open it in IntelliJ (in a new window, so Passport stays open too), and delete the generated test class.

The OAuth2 Client dependency is `spring-boot-starter-security-oauth2-client` in `pom.xml`. It brings Spring Security too. Start.spring.io also added `thymeleaf-extras-springsecurity6`, a Thymeleaf dialect for showing security information in templates.

### 2. Tell the shop about Passport

Replace `src/main/resources/application.properties` with:

```properties
spring.application.name=shop
server.port=8080

# Passport, the identity provider. Spring reads everything else from its discovery document.
spring.security.oauth2.client.provider.passport.issuer-uri=http://login.idp.test:9000

# How the shop is registered at Passport
spring.security.oauth2.client.registration.passport.provider=passport
spring.security.oauth2.client.registration.passport.client-id=shop
spring.security.oauth2.client.registration.passport.client-secret=shop-secret
spring.security.oauth2.client.registration.passport.client-name=Passport
spring.security.oauth2.client.registration.passport.authorization-grant-type=authorization_code
spring.security.oauth2.client.registration.passport.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}
spring.security.oauth2.client.registration.passport.scope=openid,profile,email
```

The client side has two kinds of configuration:

- A **provider** is an identity provider: here, only its **issuer URI**. At start-up, Spring fetches `http://login.idp.test:9000/.well-known/openid-configuration` and learns the endpoints and the JWKS location from it. The shop will refuse to start if Passport isn't running.
- A **registration** is *this application's* account at a provider: client id, secret, the flow, the scopes, and the redirect URI. Its name, `passport`, is the **registration id**, and it appears in URLs:
  - `/oauth2/authorization/passport` starts a sign-in
  - `/login/oauth2/code/passport` receives the code
- **`{baseUrl}/login/oauth2/code/{registrationId}`** is filled in per request: `http://shop.test:8080/login/oauth2/code/passport`. That's exactly the redirect URI registered at Passport in guide 01. If the two differ by a single character, Passport refuses the request.
- **`client-name`** is the label on Spring's generated login page.

Real systems keep the secret out of the file, in an environment variable or a secrets store. The property has the same name either way.

### 3. Security configuration

Create `security/SecurityConfig.java`:

```java
package com.example.shop.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(Customizer.withDefaults());
        return http.build();
    }
}
```

- **`authorizeHttpRequests`** lists rules, and the **first rule that matches** a request decides. The home page is public (`permitAll()`). Everything else needs a signed-in user. Put specific rules first and `anyRequest()` last; Spring refuses to start if `anyRequest()` comes before another rule.
- **`oauth2Login(...)`** adds two filters:
  - **`OAuth2AuthorizationRequestRedirectFilter`** answers `/oauth2/authorization/{registrationId}`. It creates `state`, `nonce` and the PKCE verifier, saves them in the session, and redirects to the provider.
  - **`OAuth2LoginAuthenticationFilter`** answers the redirect URI. It checks `state`, swaps the code, validates the ID token, and signs the user in.
- It also sets the **authentication entry point**: with only one provider configured, an anonymous visitor to a protected page is sent straight to `/oauth2/authorization/passport`, with no "choose a provider" page.

### 4. Pages

Create `HomeController.java` in `com.example.shop`:

```java
package com.example.shop;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "home";
    }

    @GetMapping("/account")
    public String account(@AuthenticationPrincipal OidcUser user, Model model) {
        model.addAttribute("user", user);
        return "account";
    }
}
```

**`@AuthenticationPrincipal`** gives a controller method the principal of the current `Authentication` from the `SecurityContext`. After an OpenID Connect sign-in, that's an **`OidcUser`**, which holds the ID token and its claims.

`src/main/resources/templates/home.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
<head><title>Shop</title></head>
<body>
<h1>The Shop</h1>
<p sec:authorize="isAnonymous()">You're browsing as a guest. <a href="/account">My account</a></p>
<p sec:authorize="isAuthenticated()">Signed in as <span sec:authentication="name">user</span>. <a href="/account">My account</a></p>
</body>
</html>
```

`sec:authorize` shows an element only if the expression is true, and `sec:authentication="name"` prints the current `Authentication`'s name. Both come from the Thymeleaf Spring Security dialect.

`src/main/resources/templates/account.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head><title>My account</title></head>
<body>
<h1>My account</h1>
<p>Signed in as <strong th:text="${user.subject}">alice</strong>.</p>
<h2>Claims in the ID token</h2>
<table>
    <tr th:each="claim : ${user.idToken.claims}">
        <td th:text="${claim.key}">name</td>
        <td th:text="${claim.value}">value</td>
    </tr>
</table>
<p><a href="/">Home</a></p>
</body>
</html>
```

The pages are deliberately plain. This series is about what's behind them.

### 5. Run both

Start **Passport first**, then the shop.

If the shop can't reach Passport, it doesn't start. That's the discovery request from step 2:

```
Caused by: org.springframework.web.client.ResourceAccessException: I/O error on GET request for
"http://login.idp.test:9000/.well-known/openid-configuration": Connection refused: getsockopt
```

### 6. Follow a sign-in

Open a **private** window, open the developer tools on the **Network** tab with **Preserve log** ticked, and go to `http://shop.test:8080`. You're a guest. Click **My account**.

You're on Passport's login page. Sign in as `alice` / `password`, and you're back on **My account**. Now read the Network tab from the top. These are the requests (parameters shortened):

| # | Request | Response | Who and why |
|---|---|---|---|
| 1 | `GET shop.test:8080/account` | `302` → `/oauth2/authorization/passport` | Shop: anonymous, so the entry point starts a sign-in. The page you wanted is saved in the session first |
| 2 | `GET shop.test:8080/oauth2/authorization/passport` | `302` → `login.idp.test:9000/oauth2/authorize?response_type=code&client_id=shop&scope=openid profile email&state=YVoh…&redirect_uri=…&nonce=tpeJ…&code_challenge=gV6G…&code_challenge_method=S256` | Shop: `state`, `nonce` and PKCE generated, and kept in the shop's session |
| 3 | `GET login.idp.test:9000/oauth2/authorize?…` | `302` → `/login` | Passport: no one is signed in to Passport |
| 4 | `GET login.idp.test:9000/login` | `200` | Passport's login page |
| 5 | `POST login.idp.test:9000/login` | `302` → `/oauth2/authorize?…&continue` | Passport: password checked. Back to the saved authorisation request |
| 6 | `GET login.idp.test:9000/oauth2/authorize?…&continue` | `302` → `shop.test:8080/login/oauth2/code/passport?code=86JF…&state=YVoh…` | Passport: a signed-in user, so issue a code |
| 7 | `GET shop.test:8080/login/oauth2/code/passport?code=…&state=…` | `302` → `/account?continue` | Shop: `state` checked, code swapped, ID token validated, signed in |
| 8 | `GET shop.test:8080/account?continue` | `200` | The page from request 1 |

The token request, the JWKS fetch, and Passport's checks of the secret and the verifier aren't in the list. They're the **back channel**, server to server, so the browser never sees them. The `continue` parameter is how Spring Security marks a request it's replaying from its saved-request cache.

Click request 2 and look at the **Location** header: that's the authorise URL you built by hand in guide 01, with a random `state`, `nonce` and code challenge.

Now look at the cookies: **Application** tab (Chrome or Edge) or **Storage** tab (Firefox) → **Cookies**. There are two `JSESSIONID` cookies, one for `http://shop.test:8080` and one for `http://login.idp.test:9000`, with different values: two sites, two sessions.

### 7. The signed-in user

My account lists the ID token's claims:

| Claim | Value |
|---|---|
| `sub` | `alice` |
| `aud` | `[shop]` |
| `azp` | `shop` |
| `auth_time` | `2026-11-11T11:06:04Z` |
| `iss` | `http://login.idp.test:9000` |
| `exp` | `2026-11-11T11:36:05Z` |
| `iat` | `2026-11-11T11:06:05Z` |
| `nonce` | `tpeJF5znxnBkGOZU-hqLuQwXQrTVAXf5RYqesVpbTLM` |
| `jti`, `sid` | … |

The `nonce` is the one from request 2: the token was issued for that login. Spring has turned the epoch numbers into readable times.

After sign-in, the shop's `SecurityContext` holds an **`OAuth2AuthenticationToken`**, the `Authentication` for an OAuth 2.0 login, whose principal is a **`DefaultOidcUser`**, the `OidcUser` your controller received. Its authorities are:

- **`OIDC_USER`**: signed in with OpenID Connect
- **`SCOPE_openid`**, **`SCOPE_profile`**, **`SCOPE_email`**: one per scope Passport granted

You can use these in rules, such as `hasAuthority("SCOPE_email")`. Guide 05 adds roles.

> **Sidebar: the shop's session outlives the ID token.** The ID token expires after 30 minutes, but the shop's session doesn't care: the token was checked **once**, at sign-in, and the session is now the proof. A real site sets its own session timeout. When the session ends, the next protected page sends the user to Passport, which, if its own session is still alive, signs them straight back in.

### 8. Watch Spring Security work

Run the shop with Spring Security's debug log, as in guide 01, step 6, with the program argument `--logging.level.org.springframework.security=DEBUG`. Then sign in again, in a new private window. Among the noise, you'll find the story of the table above, from the shop's side:

```
Securing GET /account
Set SecurityContextHolder to anonymous SecurityContext
Saved request http://shop.test:8080/account?continue to session
Redirecting to /oauth2/authorization/passport
Securing GET /oauth2/authorization/passport
Redirecting to http://login.idp.test:9000/oauth2/authorize?response_type=code&client_id=shop&...
Securing GET /login/oauth2/code/passport?code=nSdU...&state=XyCd...
HTTP GET http://login.idp.test:9000/oauth2/jwks
Changed session id from B0E2AF3E9BF6D44AD265D4969368722B
Stored SecurityContextImpl [Authentication=OAuth2AuthenticationToken [Principal=Name: [alice],
    Granted Authorities: [[OIDC_USER, SCOPE_email, SCOPE_openid, SCOPE_profile]], ...]] to HttpSession
Redirecting to http://shop.test:8080/account?continue
Securing GET /account?continue
Loaded matching saved request http://shop.test:8080/account?continue
Secured GET /account?continue
```

Two lines are worth a closer look:

- **`HTTP GET …/oauth2/jwks`**: on the first sign-in, the shop fetches Passport's public keys to check the ID token's signature. It caches them, and fetches again only when a token arrives with a `kid` it doesn't know.
- **`Changed session id`**: at sign-in, Spring Security gives the session a **new id**. This defeats **session fixation**: an attacker who tricked you into using a session id they know (from an anonymous visit of their own, say) would otherwise share your signed-in session. After sign-in, the id they know is worthless.

Remove the program argument when you're done.

---

## Your turn

### Exercise 1: a public page

Add an **About** page at `/about` that anyone can see without signing in, with a link to it from the home page.

<details>
<summary>Hint</summary>

The rule for `/` in step 3 makes one page public. `requestMatchers` takes more than one path.

</details>

<details>
<summary>Solution</summary>

In `SecurityConfig`:

```java
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/about").permitAll()
                        .anyRequest().authenticated())
```

In `HomeController`:

```java
    @GetMapping("/about")
    public String about() {
        return "about";
    }
```

`templates/about.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head><title>About the Shop</title></head>
<body>
<h1>About the Shop</h1>
<p>We sell teapots. You sign in with Passport.</p>
<p><a href="/">Home</a></p>
</body>
</html>
```

And a link in `home.html`: `<p><a href="/about">About</a></p>`. In a private window, `/about` opens without a trip to Passport.

</details>

### Exercise 2: ask for less

Change the shop to request only the `openid` scope. Before signing in again, predict the shop's authorities, then check them in the debug log.

<details>
<summary>Solution</summary>

In `application.properties`:

```properties
spring.security.oauth2.client.registration.passport.scope=openid
```

Restart the shop and sign in in a new private window. The debug log's `Stored SecurityContextImpl` line now shows `Granted Authorities: [[OIDC_USER, SCOPE_openid]]`. Request 2's URL has `scope=openid`. Put the scope back to `openid,profile,email` afterwards, since later guides need it.

A client should ask for the **least** it needs. Users are more willing to consent to less (guide 05), and a leaked token can do less harm.

</details>

---

## Checkpoint

- `http://shop.test:8080` shows "You're browsing as a guest".
- **My account** sends you to Passport, and after signing in you're back on My account, with the claims table.
- The home page now says "Signed in as alice".
- The browser holds separate `JSESSIONID` cookies for `shop.test` and `login.idp.test`.

```
shop/
├── pom.xml
└── src/main/
    ├── java/com/example/shop/
    │   ├── HomeController.java
    │   ├── ShopApplication.java
    │   └── security/
    │       └── SecurityConfig.java
    └── resources/
        ├── application.properties
        └── templates/
            ├── account.html
            └── home.html
```

---

## Common errors

**The shop won't start: `I/O error on GET request for "http://login.idp.test:9000/.well-known/openid-configuration": Connection refused`**
Passport isn't running. Start Passport first.

**The shop won't start: `Caused by: java.net.UnknownHostException: login.idp.test`**
The hosts file line is missing. Java uses the same hosts file as your browser (see the [README](README.md#before-you-start-the-hosts-file)).

**Passport says `400 Bad Request` as soon as you click My account**
You opened the shop as `http://localhost:8080` rather than `http://shop.test:8080`. The redirect URI is built from the address you used (`{baseUrl}`), so it became `http://localhost:8080/login/oauth2/code/passport`, which isn't registered. Use `shop.test`.

**After signing in, the shop shows a "Login with OAuth 2.0" page saying "Invalid credentials"**
The shop's sign-in failed after the redirect back. Run with the debug log (step 8) and look for `Authentication failed with provider OidcAuthorizationCodeAuthenticationProvider since [...]`: the part in brackets says why. A wrong `client-secret` makes the token request fail. A clock that's badly wrong on one machine makes the ID token look expired or not yet valid.

**`[authorization_request_not_found]`**
The callback arrived in a session that didn't start the sign-in, so the shop has no `state` to compare against. Typically the shop restarted mid-sign-in (sessions are in memory), or you pasted a callback URL into a different browser. Start again from the shop.

---

## Recap

- `oauth2Login` makes a Spring Boot app an OpenID Connect client. Configure a **provider** (just the issuer: discovery fills in the rest) and a **registration** (client id, secret, scopes, redirect URI).
- **`/oauth2/authorization/{registrationId}`** starts a sign-in. **`/login/oauth2/code/{registrationId}`** finishes it.
- The client keeps **`state`**, **`nonce`** and the **PKCE verifier** in its session, and checks them when the code comes back.
- It validates the ID token's **signature** (via the JWKS and `kid`), **`iss`**, **`aud`**, **`exp`** and **`nonce`** before trusting it.
- The result is an **`OAuth2AuthenticationToken`** whose principal is an **`OidcUser`**, with authorities `OIDC_USER` and `SCOPE_*`, stored in the shop's session.
- Each site has **its own session**, and cookies never cross hosts. The session id changes at sign-in, to defeat **session fixation**.

### Interview angle

<details>
<summary>"What must a client validate in an ID token?"</summary>

The signature, using the provider's published key identified by `kid` and an expected algorithm. The issuer must exactly match the configured provider. The audience must contain the client's own id (and `azp` must be the client, if there are several audiences). The token must not be expired, and `iat` must be reasonable. The nonce must match the one the client sent in this login's authorisation request. Skipping the audience check is the classic bug: then any ID token the provider issued to any other application could be replayed to log in.

</details>

<details>
<summary>"What's the difference between state and nonce?"</summary>

`state` is round-tripped through the authorisation redirect. The client checks it when the code comes back, which ties the response to a login this browser initiated (it's CSRF protection for the redirect, and can carry application state). `nonce` goes into the ID token. The client checks the token's nonce against the one it sent, which ties the token to this particular authentication and prevents replay of a previously issued token. PKCE adds a third binding, between the code and the client instance that requested it.

</details>

### Further reading

- [Spring Security reference: OAuth 2.0 Login](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/index.html)
- [OpenID Connect Core 1.0, section 3.1.3.7: ID Token Validation](https://openid.net/specs/openid-connect-core-1_0.html#IDTokenValidation)
- [OWASP: Session fixation](https://owasp.org/www-community/attacks/Session_fixation)

**Next:** [03: Real users](03-real-users.md). Passport gets a database, hashed passwords and its own login page.
