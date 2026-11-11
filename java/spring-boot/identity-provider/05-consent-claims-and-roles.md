# 05: Consent, claims and roles

> Written for **Spring Boot 4.1.2**, **Spring Security 7.1.2** and **Java 25**. Builds on [guide 04](04-clients-and-keys-in-the-database.md).

## What you'll learn

- What **consent** is for, and how to switch it on for a client
- How to replace the built-in consent page with Passport's own
- How **scopes** map to **claims**, and how to add claims to the ID token with an **`OAuth2TokenCustomizer`**
- How to carry a user's **roles** from Passport to the shop, and turn them into Spring Security **authorities** with a `GrantedAuthoritiesMapper`
- The difference between the **principal** and the **`Authentication`**, and why it matters for authorities
- A trap: why a claim built with `List.of(...)` breaks sign-in

By the end, Passport asks before sharing anything, the shop greets Alice by name, and only Passport admins can see the shop's admin page.

---

## Concepts

### Consent

When the shop asks for `profile` and `email`, it's asking Passport to share Alice's name and email address. Whose decision is that? In OAuth 2.0 it's **Alice's**: the authorisation server shows a **consent screen** ("The Shop would like to know your name and email address"), and only grants the scopes she approves.

Consent matters most when the client is a **third party**: a different company's app using "Sign in with Microsoft" gets a consent prompt, and an organisation can require an administrator's approval first. For a company's *own* applications, consent is often switched off, because the company is both sides. Passport turns it on for the shop, so you can see how it works.

Consent is remembered per user and per client, in `oauth2_authorization_consent`. Alice is asked once, and again only if the shop later asks for a scope she hasn't approved.

### Scopes and claims

A **scope** is what the client asks for. **Claims** are what it gets. OpenID Connect defines standard scopes that each unlock a set of standard claims:

| Scope | Claims |
|---|---|
| `openid` | `sub` (always) |
| `profile` | `name`, `given_name`, `family_name`, `preferred_username`, `picture`, `locale`, … |
| `email` | `email`, `email_verified` |

Claims can be delivered in the **ID token**, from the **`/userinfo`** endpoint, or both. Passport doesn't know where its users' names are, so out of the box its tokens carry no profile claims at all, as you saw in guide 01. Passport has to add them itself.

### Roles in tokens

Applications often need to know more than who someone is: what they're **allowed** to do. One option is for each application to keep its own permissions table. Another, common with Entra (its **app roles**), is for the identity provider to say "Alice is an ADMIN" in the token, and for applications to trust it.

Passport puts a custom **`roles`** claim in the ID token. The shop then converts it into Spring Security authorities (`ROLE_ADMIN`), so the usual rules (`hasRole("ADMIN")`) just work.

---

## Steps

### 1. Turn consent on

In `ClientRegistrations`, add **client settings** to the shop's registration, after the scopes:

```java
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
...
                .scope(OidcScopes.EMAIL)
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(true)
                        .build())
                .build());
```

`ClientSettings` holds per-client behaviour. The builder's defaults include `requireProofKey(true)` (PKCE), so you only state what's different. Because the client has a fixed id (guide 04), restarting updates its row.

Restart Passport and sign in to the shop in a private window. After the password, Passport shows its **built-in consent page**: "Consent required", "**shop** wants to access your account **alice**", with checkboxes for `profile` and `email`, and **Submit Consent** and **Cancel** buttons.

- Tick both and submit, and you're back at the shop.
- Sign in again in a **new** private window: no consent page. Your consent is in the database.

Look at the page's form in the developer tools (**Elements** tab): it `POST`s to `/oauth2/authorize`, the authorisation endpoint again, with `client_id`, `state` and the ticked `scope`s. It has **no CSRF token**: the protocol endpoints don't use them. The `state`, which only the real authorisation request contains, ties this form to it instead.

### 2. Cancelling

In a private window, sign in as **bob**, and click **Cancel** on the consent page. Passport redirects back to the shop with an error instead of a code:

```
http://shop.test:8080/login/oauth2/code/passport?error=access_denied&error_description=OAuth%202.0%20Parameter%3A%20client_id&...
```

`access_denied` is the standard OAuth error for "the user said no". The shop's sign-in fails, and it shows Spring's generated login page with **"Invalid credentials"** and a **Passport** link to try again. A real site would catch this and show a friendlier message.

### 3. Passport's own consent page

The built-in page shows the client **id** (`shop`) rather than its name, uses the scope names as labels, and doesn't look like Passport. Replace it.

In `SecurityConfig`'s first chain, tell the authorisation endpoint where the consent page is:

```java
                .oauth2AuthorizationServer(authorizationServer -> {
                    http.securityMatcher(authorizationServer.getEndpointsMatcher());
                    authorizationServer
                            .authorizationEndpoint(endpoint -> endpoint.consentPage("/oauth2/consent"))
                            .oidc(Customizer.withDefaults());
                })
```

When consent is needed, Passport now redirects to `/oauth2/consent?scope=…&client_id=…&state=…`. That URL isn't a protocol endpoint, so the second chain handles it, and needs a signed-in user, which Alice is by then.

Create `ConsentController.java` in `com.example.idp`:

```java
package com.example.idp;

import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Passport's own consent page: "The Shop wants to know your name and email address". */
@Controller
public class ConsentController {

    private final RegisteredClientRepository clients;
    private final OAuth2AuthorizationConsentService consents;

    public ConsentController(RegisteredClientRepository clients, OAuth2AuthorizationConsentService consents) {
        this.clients = clients;
        this.consents = consents;
    }

    @GetMapping("/oauth2/consent")
    public String consent(Principal principal, Model model,
                          @RequestParam(OAuth2ParameterNames.CLIENT_ID) String clientId,
                          @RequestParam(OAuth2ParameterNames.SCOPE) String scope,
                          @RequestParam(OAuth2ParameterNames.STATE) String state) {
        RegisteredClient client = clients.findByClientId(clientId);
        OAuth2AuthorizationConsent previous = consents.findById(client.getId(), principal.getName());
        Set<String> alreadyApproved = previous == null ? Set.of() : previous.getScopes();

        List<ScopeDescription> toApprove = new ArrayList<>();
        List<ScopeDescription> approvedBefore = new ArrayList<>();
        for (String requested : scope.split(" ")) {
            if (OidcScopes.OPENID.equals(requested)) {
                continue; // signing in at all; there's nothing to ask about
            }
            ScopeDescription description = ScopeDescription.of(requested);
            if (alreadyApproved.contains(requested)) {
                approvedBefore.add(description);
            } else {
                toApprove.add(description);
            }
        }

        model.addAttribute("clientId", clientId);
        model.addAttribute("clientName", client.getClientName());
        model.addAttribute("state", state);
        model.addAttribute("toApprove", toApprove);
        model.addAttribute("approvedBefore", approvedBefore);
        model.addAttribute("username", principal.getName());
        return "consent";
    }

    /** A scope, with a sentence a person can understand. */
    public record ScopeDescription(String scope, String description) {

        static ScopeDescription of(String scope) {
            return new ScopeDescription(scope, switch (scope) {
                case OidcScopes.PROFILE -> "Your name and username";
                case OidcScopes.EMAIL -> "Your email address";
                default -> scope;
            });
        }
    }
}
```

- **`Principal principal`**: Spring MVC can pass any controller method the current user as a `java.security.Principal`, the plain Java interface that Spring Security's `Authentication` implements. Its `getName()` is the username, the name consents are stored under.
- The **client's name** comes from its registration, so the page can say "The Shop", not `shop`.
- **Scopes already approved** (in an earlier consent) are listed separately. Passport only asks about new ones.
- **`openid` isn't shown**: it just means "sign in".

`templates/consent.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Passport: allow access?</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css">
</head>
<body class="bg-light">
<main class="container" style="max-width: 480px">
    <h1 class="h3 mt-5 mb-1">Passport</h1>
    <p class="text-secondary mb-4">Signed in as <strong th:text="${username}">alice</strong></p>

    <p><strong th:text="${clientName}">The Shop</strong> would like to know:</p>
    <ul>
        <li th:each="scope : ${toApprove}" th:text="${scope.description}">Your email address</li>
    </ul>

    <div th:unless="${approvedBefore.isEmpty()}">
        <p class="text-secondary mb-1">You've already allowed it to know:</p>
        <ul class="text-secondary">
            <li th:each="scope : ${approvedBefore}" th:text="${scope.description}">Your name and username</li>
        </ul>
    </div>

    <div class="d-flex gap-2 mt-4">
        <form th:action="@{/oauth2/authorize}" method="post">
            <input type="hidden" name="client_id" th:value="${clientId}">
            <input type="hidden" name="state" th:value="${state}">
            <input type="hidden" name="scope" th:each="scope : ${toApprove}" th:value="${scope.scope}">
            <button type="submit" class="btn btn-primary">Allow</button>
        </form>
        <form th:action="@{/oauth2/authorize}" method="post">
            <input type="hidden" name="client_id" th:value="${clientId}">
            <input type="hidden" name="state" th:value="${state}">
            <button type="submit" class="btn btn-outline-secondary">Cancel</button>
        </form>
    </div>
</main>
</body>
</html>
```

**Allow** posts every scope being asked about. **Cancel** posts none, which is how the protocol says no. (Thymeleaf adds a `_csrf` field to both forms. The authorisation endpoint ignores it.)

Restart Passport. Your earlier consents are still stored, so to see the new page, sign in as someone who hasn't consented yet. Bob cancelled in step 2, so sign in as **bob**: "**The Shop** would like to know: Your name and username, Your email address."

### 4. Roles in the database

Create `db/migration/V4__create_user_role.sql`:

```sql
CREATE TABLE user_role (
    user_id BIGINT      NOT NULL REFERENCES user_account (id),
    role    VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, role)
);

INSERT INTO user_role (user_id, role)
SELECT id, 'USER' FROM user_account;

INSERT INTO user_role (user_id, role)
SELECT id, 'ADMIN' FROM user_account WHERE username = 'alice';
```

Everyone is a `USER`. Alice is also an `ADMIN`.

In `UserAccount`, add the roles as an element collection, plus a getter:

```java
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;

import java.util.HashSet;
import java.util.Set;
...

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", length = 50)
    private Set<String> roles = new HashSet<>();
...

    public Set<String> getRoles() {
        return Set.copyOf(roles);
    }
```

`EAGER` because every use of a `UserAccount` in Passport needs the roles, and Passport's sessions don't stay open between queries (`open-in-view=false`).

In `PassportUserDetailsService`, use them instead of the hard-coded `USER`:

```java
                .roles(account.getRoles().toArray(String[]::new))
```

`roles(...)` adds the `ROLE_` prefix, so Alice's authorities at Passport become `ROLE_ADMIN` and `ROLE_USER`.

### 5. Add claims to the ID token

The authorisation server builds each token from a set of claims, then calls any **`OAuth2TokenCustomizer<JwtEncodingContext>`** bean to let you change them before signing. Create `security/IdTokenClaims.java`:

```java
package com.example.idp.security;

import com.example.idp.user.UserAccount;
import com.example.idp.user.UserAccountRepository;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Adds the user's details to the ID token, as far as the scopes they consented to allow. */
@Component
public class IdTokenClaims implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private final UserAccountRepository users;

    public IdTokenClaims(UserAccountRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public void customize(JwtEncodingContext context) {
        if (!OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue())) {
            return; // only the ID token; the access token is left alone
        }
        UserAccount user = users.findByUsername(context.getPrincipal().getName()).orElseThrow();
        Set<String> scopes = context.getAuthorizedScopes();

        context.getClaims().claims(claims -> {
            if (scopes.contains(OidcScopes.PROFILE)) {
                claims.put(StandardClaimNames.NAME, user.getGivenName() + " " + user.getFamilyName());
                claims.put(StandardClaimNames.GIVEN_NAME, user.getGivenName());
                claims.put(StandardClaimNames.FAMILY_NAME, user.getFamilyName());
                claims.put(StandardClaimNames.PREFERRED_USERNAME, user.getUsername());
            }
            if (scopes.contains(OidcScopes.EMAIL)) {
                claims.put(StandardClaimNames.EMAIL, user.getEmail());
            }
            // An ArrayList, not List.of(...) or stream().toList(): the claims are stored in the database
            // as JSON, and Spring only reads back collection types it trusts (see Common errors).
            List<String> roles = new ArrayList<>(user.getRoles());
            Collections.sort(roles);
            claims.put("roles", roles);
        });
    }
}
```

- **One customiser for every token.** The same bean is called for the access token too. The first `if` makes this one touch only ID tokens.
- **`context.getPrincipal()`** is the user's `Authentication` from the sign-in, so its name is the username.
- **`getAuthorizedScopes()`** are the scopes the user actually **consented to**, which may be fewer than the client asked for. Claims follow consent: no `email` scope, no `email` claim.
- **`StandardClaimNames`** holds the claim names from the OpenID Connect specification, so they're spelt right.
- **`roles`** isn't a standard claim, so any name works. It's added whatever the scopes, because the shop needs it to make decisions.
- **`ArrayList`**: see the sidebar.

> **Sidebar: the claim that broke sign-in.** The first version of this class used `user.getRoles().stream().sorted().toList()`. Sign-in then failed at the shop with "Invalid credentials", and the shop's debug log said:
>
> ```
> [invalid_user_info_response] ... UserInfo Error: Could not resolve type id
> 'java.util.ImmutableCollections$ListN' as a subtype of `java.lang.Object`:
> Configured `PolymorphicTypeValidator` ... denied resolution
> ```
>
> `JdbcOAuth2AuthorizationService` stores each authorisation, tokens and claims included, as JSON in `oauth2_authorization`. When it reads one back, for `/userinfo` here, it only re-creates Java types on an **allow-list**: letting JSON choose arbitrary classes is a well-known remote-code-execution hole. `stream().toList()` and `List.of()` return a JDK-internal class that isn't on the list. `ArrayList` is. The fix is one line, but the symptom is three systems away from the cause.

Restart Passport, and sign in to the shop in a private window, as alice. The account page's claims table now includes:

| Claim | Value |
|---|---|
| `name` | `Alice Smith` |
| `given_name`, `family_name` | `Alice`, `Smith` |
| `preferred_username` | `alice` |
| `email` | `alice@example.com` |
| `roles` | `[ADMIN, USER]` |

`/userinfo` answers too. Spring's default UserInfo response takes the ID token's claims and filters them by the standard scopes:

```json
{"sub":"alice","preferred_username":"alice","given_name":"Alice","name":"Alice Smith","family_name":"Smith","email":"alice@example.com"}
```

Notice that `roles` isn't there: it isn't a standard claim, so the default doesn't include it.

### 6. Roles in the shop

Back in the **shop**, the claims are there, but Spring Security doesn't know `roles` means anything. Its authorities are still `OIDC_USER` and `SCOPE_*`. Convert them as the user signs in. Replace the shop's `security/SecurityConfig.java`:

```java
package com.example.shop.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.web.SecurityFilterChain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2Login(login -> login
                        .userInfoEndpoint(userInfo -> userInfo.userAuthoritiesMapper(passportRoles())));
        return http.build();
    }

    /** Turns the "roles" claim in Passport's ID token into Spring Security roles: ADMIN becomes ROLE_ADMIN. */
    private GrantedAuthoritiesMapper passportRoles() {
        return authorities -> {
            Set<GrantedAuthority> mapped = new HashSet<>(authorities);
            for (GrantedAuthority authority : authorities) {
                if (authority instanceof OidcUserAuthority oidc) {
                    List<String> roles = oidc.getIdToken().getClaimAsStringList("roles");
                    if (roles != null) {
                        for (String role : roles) {
                            mapped.add(new SimpleGrantedAuthority("ROLE_" + role));
                        }
                    }
                }
            }
            return mapped;
        };
    }
}
```

- **A `GrantedAuthoritiesMapper`** gets the authorities Spring worked out at sign-in, and returns the ones to use instead. This one keeps them all, and adds a `ROLE_…` authority per role.
- **`OidcUserAuthority`** is the authority behind `OIDC_USER`. It carries the ID token, which is how the mapper reaches the `roles` claim.
- **`hasRole("ADMIN")`** checks for the authority `ROLE_ADMIN`. The rule for `/admin/**` comes before `anyRequest()`: first match wins.
- **The shop trusts Passport's roles completely.** That's a design decision: anyone who can change roles at Passport controls the shop's admin page.

Add an admin page. In `HomeController`, add an `Authentication` parameter to `account`, and a new method:

```java
import org.springframework.security.core.Authentication;
...

    @GetMapping("/account")
    public String account(@AuthenticationPrincipal OidcUser user, Authentication authentication, Model model) {
        model.addAttribute("user", user);
        model.addAttribute("authorities", authentication.getAuthorities());
        return "account";
    }

    @GetMapping("/admin")
    public String admin() {
        return "admin";
    }
```

`templates/admin.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
<head><title>Shop admin</title></head>
<body>
<h1>Shop admin</h1>
<p>Only Passport users with the ADMIN role can see this page. You're <span sec:authentication="name">alice</span>.</p>
<p><a href="/">Home</a></p>
</body>
</html>
```

And make `templates/account.html` greet the user, and list the authorities:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head><title>My account</title></head>
<body>
<h1>My account</h1>
<p>Hello, <strong th:text="${user.fullName}">Alice Smith</strong> (<span th:text="${user.email}">alice@example.com</span>).</p>
<h2>Authorities</h2>
<ul>
    <li th:each="authority : ${authorities}" th:text="${authority.authority}">ROLE_USER</li>
</ul>
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

`user.fullName` and `user.email` are `OidcUser` getters for the `name` and `email` claims.

> **Sidebar: the principal's authorities aren't the `Authentication`'s.** It's tempting to list `user.authorities`, the `OidcUser`'s own. Try it, and you'll see only `OIDC_USER` and the `SCOPE_` ones: no roles. The mapper's result is stored on the **`Authentication`** (the `OAuth2AuthenticationToken`), not on the principal inside it. Spring Security's authorisation rules always use the `Authentication`'s authorities, so `/admin` works either way, but display code must use the right one too. That's why `account` takes the `Authentication`.

### 7. Try it

Restart the shop. In a private window, sign in as **alice**:

- **My account** says "Hello, Alice Smith (alice@example.com)", and lists `OIDC_USER`, `SCOPE_openid`, `SCOPE_email`, `ROLE_USER`, `ROLE_ADMIN` and `SCOPE_profile` (in no particular order).
- `http://shop.test:8080/admin` shows the admin page.

In a new private window, sign in as **bob**, and visit `/admin`: **403 Forbidden** (Spring's "Whitelabel Error Page"). Bob is signed in, so there's nothing to redirect to. He just isn't allowed.

---

## Your turn

### Exercise 1: an admin link for admins only

Add an **Admin** link to the shop's home page that only users with the `ADMIN` role can see.

<details>
<summary>Hint</summary>

`sec:authorize` takes the same expressions as the security rules, such as `hasRole('ADMIN')`.

</details>

<details>
<summary>Solution</summary>

In `home.html`, inside the signed-in paragraph or next to it:

```html
<p sec:authorize="hasRole('ADMIN')"><a href="/admin">Admin</a></p>
```

Alice sees it. Bob doesn't. Hiding the link is only a convenience: the `/admin/**` rule in `SecurityConfig` is what actually protects the page, as Bob's 403 shows.

</details>

### Exercise 2: withdraw consent

Make Passport ask Alice for consent again, without changing any code.

<details>
<summary>Hint</summary>

Consent is a row in the database. Guide 04, step 8 showed how to change the database directly.

</details>

<details>
<summary>Solution</summary>

Stop Passport, then delete Alice's consents:

```bash
java -cp ~/.m2/repository/com/h2database/h2/2.4.240/h2-2.4.240.jar org.h2.tools.Shell -url jdbc:h2:file:./data/idp -user sa -sql "DELETE FROM oauth2_authorization_consent WHERE principal_name = 'alice'"
```

Start Passport and sign in to the shop as alice in a new private window: Passport's consent page appears again. Real identity providers give users a page to do this themselves. In a Microsoft account it's **Apps and services you've given access to**.

</details>

---

## Checkpoint

- Signing in for the first time shows Passport's consent page, naming **The Shop**.
- The shop's account page greets the user by name and email.
- Alice can open `/admin`. Bob gets `403`.

New and changed files:

```
idp/src/main/
├── java/com/example/idp/
│   ├── ConsentController.java
│   ├── client/ClientRegistrations.java        (consent required)
│   ├── security/IdTokenClaims.java
│   ├── security/SecurityConfig.java           (consent page)
│   └── user/                                  (roles)
└── resources/
    ├── db/migration/V4__create_user_role.sql
    └── templates/consent.html

shop/src/main/
├── java/com/example/shop/
│   ├── HomeController.java                    (account, admin)
│   └── security/SecurityConfig.java           (roles mapper, /admin rule)
└── resources/templates/
    ├── account.html
    └── admin.html
```

---

## Common errors

**The shop says "Invalid credentials" after consent, and its debug log mentions `Could not resolve type id 'java.util.ImmutableCollections$ListN'`**
A claim value is a `List.of(...)` or `stream().toList()`. Use an `ArrayList` (the sidebar in step 5).

**The consent page never appears**
The user has already consented (exercise 2 shows how to clear it), or `requireAuthorizationConsent(true)` isn't on the registration.

**`Whitelabel Error Page` with status 404 at `/oauth2/consent`**
`consentPage("/oauth2/consent")` is set, but the controller's `@GetMapping` path doesn't match it exactly.

**Alice gets `403` on `/admin`**
The mapper isn't applied (check `userInfoEndpoint(userInfo -> userInfo.userAuthoritiesMapper(...))`), or the claim is missing: check the account page's claims table for `roles`. The role must be `ADMIN`, not `ROLE_ADMIN`, because the mapper adds the prefix.

**The account page lists no `ROLE_` authorities, but `/admin` works**
You're listing `user.authorities` (the principal's). List the `Authentication`'s instead (the sidebar in step 6).

---

## Recap

- **Consent** lets the user decide what a client may know. It's switched on per client (`requireAuthorizationConsent(true)`) and remembered per user and client. A **custom consent page** is a controller at the URL given to `consentPage(...)`, posting back to `/oauth2/authorize`.
- **Scopes** are requested, **claims** are delivered. Standard scopes unlock standard claims, and only those the user consented to.
- An **`OAuth2TokenCustomizer<JwtEncodingContext>`** bean adds claims to tokens. Check the token type first. Use JSON-friendly types (`ArrayList`), because authorisations are stored as JSON with an allow-list.
- A **`roles` claim** plus a **`GrantedAuthoritiesMapper`** in the client turns the IdP's roles into Spring Security authorities, so `hasRole(...)` works.
- Mapped authorities live on the **`Authentication`**, not on the principal.

### Interview angle

<details>
<summary>"Should roles come from the identity provider, or from each application?"</summary>

Both patterns are common. Roles in the token (Entra app roles, a `roles` or `groups` claim) centralise access management: an administrator grants a role once, and every application trusting the IdP sees it at the next sign-in. But they're only as fresh as the token, and they couple applications to the IdP's model. Application-owned permissions, keyed by the token's subject, are more flexible and can change instantly. A common compromise is coarse roles from the IdP and fine-grained permissions in the application. Either way, the application must validate the token's issuer and audience, so a token for another app can't grant roles.

</details>

<details>
<summary>"What's the difference between a scope and a claim?"</summary>

A scope is a permission a client requests, and the user or policy grants: `profile`, `email`, `orders.read`. A claim is a piece of information asserted in a token or the UserInfo response: `name`, `email`, `roles`. In OpenID Connect, standard scopes are shorthand for sets of standard claims (`profile` means name, given name, family name and so on), and the provider only releases the claims the granted scopes allow.

</details>

### Further reading

- [Spring Security reference: the OAuth2 Authorization Endpoint](https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/protocol-endpoints.html#oauth2AuthorizationServer-oauth2-authorization-endpoint): including `consentPage`
- [OpenID Connect Core 1.0, section 5.4: Requesting Claims using Scope Values](https://openid.net/specs/openid-connect-core-1_0.html#ScopeClaims)
- [Microsoft identity platform: add app roles and get them from a token](https://learn.microsoft.com/entra/identity-platform/howto-add-app-roles-in-apps)

**Next:** [06: Multi-factor authentication](06-multi-factor-authentication.md). A password alone is no longer enough.
