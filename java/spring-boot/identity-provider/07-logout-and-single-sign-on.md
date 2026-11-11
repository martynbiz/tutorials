# 07: Logout, and single sign-on

> Written for **Spring Boot 4.1.2**, **Spring Security 7.1.2** and **Java 25**. Builds on [guide 06](06-multi-factor-authentication.md).

## What you'll learn

- Why "Sign out" on a website that uses an identity provider is harder than it looks
- **Local logout**, and why on its own it signs you straight back in
- **RP-initiated logout**: `end_session_endpoint`, `id_token_hint` and `post_logout_redirect_uri`, with Spring's `OidcClientInitiatedLogoutSuccessHandler`
- Why logout is a `POST` with a CSRF token
- What **front-channel** and **back-channel** logout are, and what they'd add
- **Single sign-on** across two websites, in the final exercise

By the end, signing out of the shop signs you out of Passport too, and you'll have seen SSO, and its limits, with your own eyes.

---

## Concepts

### Two sessions, again

From guide 02: after signing in, the browser holds **two** sessions:

- **the shop's**, which says "this is Alice"
- **Passport's**, which says "this browser signed in to Passport as Alice, with a password and a code"

Spring Security's ordinary logout (`POST /logout`) ends **the shop's** session. That's **local logout**. Passport's session is still alive. The next time the shop sends the browser to Passport, Passport sees a signed-in user and immediately issues a code: no password, no MFA. For the user, "Sign out" seems to do nothing at all. On a shared computer, the next person can walk straight into Alice's account.

### RP-initiated logout

OpenID Connect's answer is [RP-Initiated Logout](https://openid.net/specs/openid-connect-rpinitiated-1_0.html): after its local logout, the client (the Relying Party) sends the browser to the IdP's **`end_session_endpoint`**, from discovery (`http://login.idp.test:9000/connect/logout`), with:

- **`id_token_hint`**: the ID token the user signed in with. It tells Passport which user and which Passport session (`sid`) to end, and proves the request comes from a client that really had a session.
- **`post_logout_redirect_uri`**: where to send the browser afterwards. Like a redirect URI, it must be **registered** for the client in advance, or the IdP could be used to bounce users to any site.

Passport ends its own session, and redirects back. Now both sessions are gone.

### And the other websites?

With two websites signed in through Passport, RP-initiated logout from the shop ends **the shop's** session and **Passport's**. The forum's session is a cookie on `forum.test`, which neither the shop nor Passport can touch. Two standards address this:

- **Front-channel logout**: Passport's "signed out" page loads a hidden `<iframe>` for each client's logout URL, so the browser sends each site its own logout request with its own cookies. Modern browsers increasingly block cookies in third-party iframes, which breaks it.
- **[Back-channel logout](https://openid.net/specs/openid-connect-backchannel-1_0.html)**: Passport calls each client **server to server**, POSTing a signed **logout token** that names the user and the session (`sid`). Each client ends the matching session. It's reliable, and it's what Entra and Keycloak support.

Spring Security's *client* can receive back-channel logout: `http.oidcLogout(logout -> logout.backChannel(Customizer.withDefaults()))`. Spring Security's *authorisation server* doesn't send logout tokens, though. So in this series, signing out of one site leaves the others signed in, and the final exercise lets you see it. That's also why real sites keep their sessions short.

### Logout and CSRF

Spring Security's logout only accepts `POST`, with the CSRF token. If a plain link, `GET /logout`, logged you out, any other website could sign you out of everything with an `<img src="http://shop.test:8080/logout">`. It's an annoyance attack rather than a theft, but it can also be a step in login-CSRF attacks. So "Sign out" is a small form with a button.

---

## Steps

### 1. A sign-out button, and local logout

In the shop's `templates/home.html`, replace the signed-in paragraph with:

```html
<div sec:authorize="isAuthenticated()">
    <p>Signed in as <span sec:authentication="name">user</span>. <a href="/account">My account</a></p>
    <form th:action="@{/logout}" method="post"><button type="submit">Sign out</button></form>
</div>
```

and add the same form to `templates/account.html`, after the "Home" link:

```html
<form th:action="@{/logout}" method="post"><button type="submit">Sign out</button></form>
```

`th:action` adds the CSRF token, and Spring Security's `LogoutFilter` already handles `POST /logout`.

Restart the shop, sign in (password, code), and click **Sign out**. Spring's default **local logout** ends the shop's session, and redirects to the shop's generated `/login?logout` page. Go to the home page: you're a guest.

Now click **My account**. You're back on your account page **at once**, without seeing Passport at all. In the Network tab: `/account` → `/oauth2/authorization/passport` → Passport's `/oauth2/authorize` → straight back with a code. Passport's session never ended. That's the problem.

### 2. Register where Passport may send users after logout

In Passport's `ClientRegistrations`, add a post-logout redirect URI to the shop, after its redirect URI:

```java
                .redirectUri("http://shop.test:8080/login/oauth2/code/passport")
                .postLogoutRedirectUri("http://shop.test:8080/")
```

The shop's home page is where users land after signing out. It's stored in `oauth2_registered_client.post_logout_redirect_uris`, and `/connect/logout` refuses any other value.

### 3. RP-initiated logout in the shop

In the shop's `SecurityConfig`, give the chain a **logout success handler** that sends the browser on to Passport:

```java
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
...

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ClientRegistrationRepository clients) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2Login(login -> login
                        .userInfoEndpoint(userInfo -> userInfo.userAuthoritiesMapper(passportRoles())))
                .logout(logout -> logout.logoutSuccessHandler(signOutOfPassportToo(clients)));
        return http.build();
    }

    /**
     * After signing out of the shop, send the browser to Passport's end_session_endpoint, so the
     * Passport session ends too. Passport then sends the browser back to the shop's home page.
     */
    private LogoutSuccessHandler signOutOfPassportToo(ClientRegistrationRepository clients) {
        OidcClientInitiatedLogoutSuccessHandler handler = new OidcClientInitiatedLogoutSuccessHandler(clients);
        handler.setPostLogoutRedirectUri("{baseUrl}/");
        return handler;
    }
```

- **`LogoutFilter`** first does the local part: invalidates the shop's session and clears its `SecurityContext`. Then it calls the **`LogoutSuccessHandler`** to decide where the browser goes next.
- **`OidcClientInitiatedLogoutSuccessHandler`** builds the redirect to the provider's `end_session_endpoint`, read from discovery, through the **`ClientRegistrationRepository`**. That's the bean Spring Boot made from the `spring.security.oauth2.client.registration.*` properties, so the chain method asks for it as a parameter. The handler adds the user's ID token as `id_token_hint`.
- **`{baseUrl}/`** becomes `http://shop.test:8080/`, exactly the URI registered in step 2.

### 4. Try it

Restart Passport, then the shop. In a private window, sign in to the shop, then click **Sign out**. The Network tab shows three hops:

| # | Request | Response |
|---|---|---|
| 1 | `POST shop.test:8080/logout` | `302` → `http://login.idp.test:9000/connect/logout?id_token_hint=eyJraWQiOi…&post_logout_redirect_uri=http://shop.test:8080/` |
| 2 | `GET login.idp.test:9000/connect/logout?…` | `302` → `http://shop.test:8080/` |
| 3 | `GET shop.test:8080/` | `200`: "You're browsing as a guest" |

In hop 2, Passport's `OidcLogoutEndpointFilter` (guide 01's filter list) checked the ID token's signature and audience, found the Passport session it names, ended it, checked the post-logout URI against the registration, and redirected.

Now click **My account**: Passport asks for your password **and** a code. Both sessions really are gone.

Try tampering. Copy the `/connect/logout` URL from hop 1 of a fresh sign-out, change `post_logout_redirect_uri` to `http://evil.test/`, and open it:

```json
{"timestamp":"…","status":400,"error":"Bad Request","path":"/connect/logout"}
```

Passport won't redirect anywhere that isn't registered.

### 5. Signing out at Passport itself

Someone who signed in directly at Passport should be able to sign out there too. In Passport's `templates/home.html`, after the list of authorities:

```html
    <form th:action="@{/logout}" method="post">
        <button type="submit" class="btn btn-outline-secondary">Sign out of Passport</button>
    </form>
```

Passport's second chain has Spring's default logout, so `POST /logout` ends Passport's session and redirects to `/login?logout`, which the login page from guide 03 already shows as "You've signed out of Passport."

This ends Passport's session only. A shop session elsewhere in the browser carries on until the shop's own session ends. That's the mirror image of the problem in the Concepts section.

---

## Your turn

### Exercise: single sign-on

Here's what the whole series has been building towards. Add a second website, **the Forum** at `http://forum.test:8081`, and see single sign-on.

To keep it short, the forum is **the shop's code, run a second time** with different settings. A real forum would be its own application, but to Passport the only differences that matter are its client registration and its address.

1. **Register the forum at Passport**: client id `forum`, secret `forum-secret`, name "The Forum", the redirect URI and post-logout URI on `http://forum.test:8081`, the same scopes, and consent required.
2. **Run a second copy of the shop** as the forum, overriding four properties:

   ```
   --server.port=8081 --spring.application.name=forum
   --spring.security.oauth2.client.registration.passport.client-id=forum
   --spring.security.oauth2.client.registration.passport.client-secret=forum-secret
   ```

   - **IntelliJ:** **Run → Edit Configurations…**, select the shop's configuration and click **Copy Configuration**. Name the copy `Forum`, put the four properties on one line in **Program arguments**, and run both.
   - **Terminal:** in a second terminal, in the shop's folder, run one line:

     ```bash
     ./mvnw spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081 --spring.application.name=forum --spring.security.oauth2.client.registration.passport.client-id=forum --spring.security.oauth2.client.registration.passport.client-secret=forum-secret"
     ```

3. In a private window, sign in to the **shop** (password and code). Then, in the same window, go to `http://forum.test:8081/account`. What does Passport ask you for, and what doesn't it?
4. **Sign out of the shop.** Then reload the forum's account page. Are you still signed in to the forum? Why?

<details>
<summary>Hint</summary>

Part 1 is guide 04's `save(RegisteredClient…)`, once more, with guide 05's consent setting and step 2's post-logout URI. Part 2 uses program arguments, as in guide 01, step 6.

</details>

<details>
<summary>Solution</summary>

In Passport's `ClientRegistrations.afterSingletonsInstantiated()`, after the shop:

```java
        save(RegisteredClient.withId("forum")
                .clientId("forum")
                .clientName("The Forum")
                .clientSecret(passwordEncoder.encode("forum-secret"))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://forum.test:8081/login/oauth2/code/passport")
                .postLogoutRedirectUri("http://forum.test:8081/")
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(true)
                        .build())
                .build());
```

Restart Passport (the log shows `Saved client shop` and `Saved client forum`), then run the forum as described.

**Step 3: single sign-on.** At the forum, the Network tab shows:

- `forum.test:8081/account` → `/oauth2/authorization/passport` → Passport's `/oauth2/authorize?client_id=forum&…`
- Passport's **consent page**: "**The Forum** would like to know: Your name and username, Your email address"
- Allow → `forum.test:8081/login/oauth2/code/passport?code=…` → the forum's account page: "Hello, Alice Smith"

**No password and no code.** Passport's session, from signing in to the shop, already holds both factors, so Passport only asked what's new: whether Alice agrees to share her details with *this* application. That's single sign-on. Passport did the work. The forum's claims table shows `aud` `[forum]`: a separate ID token, issued to the forum, that the shop couldn't use.

The forum has its own session cookie on `forum.test`, so the browser now holds three sessions: shop, forum and Passport.

**Step 4: the limits.** After signing out of the shop (RP-initiated), the forum's account page **still loads**: you're still signed in to the forum. Signing out ended the shop's session and Passport's, but the forum's session is a cookie on `forum.test`, which neither could touch. Passport can't tell the forum, because Spring's authorisation server doesn't send back-channel logout tokens.

Two more things you can check:

- `http://login.idp.test:9000/` asks you to sign in: Passport's session is gone.
- `http://shop.test:8080/account` takes you through a full sign-in.

Only the forum lives on, until its session times out, or you sign out there too. Signing out of the forum then sends you through Passport's logout again, which finds no Passport session and simply redirects back.

This is the trade-off every SSO system makes. Users love signing in once. Signing out *everywhere* needs back-channel logout (or front-channel, or short sessions), and every application must take part.

</details>

---

## Checkpoint

- **Sign out** in the shop goes through Passport's `/connect/logout` and back to the shop's home page, and the next sign-in needs a password and a code.
- Passport refuses an unregistered `post_logout_redirect_uri` with `400`.
- With the forum running: after signing in to the shop, the forum asks only for consent, not for a password or a code.

New and changed files:

```
idp/src/main/
├── java/com/example/idp/client/ClientRegistrations.java   (post-logout URI; the forum, after the exercise)
└── resources/templates/home.html                          (sign-out button)

shop/src/main/
├── java/com/example/shop/security/SecurityConfig.java     (RP-initiated logout)
└── resources/templates/
    ├── account.html                                       (sign-out button)
    └── home.html                                          (sign-out button)
```

---

## Common errors

**Sign out returns to the shop, but My account signs you straight back in**
The logout was only local. Check the shop's `.logout(logout -> logout.logoutSuccessHandler(...))`, and that the Network tab shows a request to `/connect/logout`.

**`400 Bad Request` from `/connect/logout`**
The `post_logout_redirect_uri` isn't registered for the client exactly: compare `http://shop.test:8080/`, including the trailing `/`, with `postLogoutRedirectUri(...)`. Or the `id_token_hint` was issued to a different client.

**Clicking "Sign out" shows a page asking "Are you sure you want to log out?"**
The sign-out is a link (`GET /logout`). Spring Security doesn't log out on a `GET`: it shows its generated confirmation page instead, whose button sends the `POST`. Make sign-out a form that `POST`s, with `th:action` so the CSRF token is included.

**The forum won't start: `Port 8081 was already in use`**
A forum from an earlier attempt is still running. Stop it first.

**The forum's sign-in fails at Passport with `400 Bad Request`**
The forum was opened as `http://localhost:8081`, or its redirect URI isn't registered. Use `http://forum.test:8081`, and check the forum client's `redirectUri`.

**Signed in to the shop, but the forum asks for a password**
Not the same browser session: a different browser, a different private window, or Passport restarted (its sessions are in memory). Use one private window for both.

---

## Recap

- After an OpenID Connect sign-in there are **at least two sessions**: each client's and the IdP's. **Local logout** ends only the client's, and the IdP signs the user straight back in.
- **RP-initiated logout** sends the browser to the IdP's **`end_session_endpoint`** with **`id_token_hint`** and a **registered `post_logout_redirect_uri`**. In Spring, that's `OidcClientInitiatedLogoutSuccessHandler` in the client, and `.postLogoutRedirectUri(...)` in the client's registration.
- Logout is a **`POST` with a CSRF token**.
- **Single sign-on** comes from the IdP's own session: a second client's sign-in needs no password, only consent, if it's required.
- Signing out of **every** site needs **back-channel** (or front-channel) logout. Spring's client can receive back-channel logout tokens, but Spring's authorisation server doesn't send them. Otherwise, sessions end when they time out.

### Interview angle

<details>
<summary>"A user clicks 'Sign out' on a site that uses SSO. What should happen?"</summary>

The site ends its own session, then redirects to the IdP's end-session endpoint with an `id_token_hint` and a registered post-logout redirect URI. The IdP ends its SSO session, and ideally notifies every other relying party with an active session for that user, either with back-channel logout tokens POSTed server to server, or front-channel iframes. Then it redirects back. Without the IdP step, the user is silently signed back in. Without the notification step, other applications keep their sessions until they expire, so their session lifetimes should be short.

</details>

<details>
<summary>"Why does the IdP require registered post-logout redirect URIs?"</summary>

For the same reason as redirect URIs: an open redirect. If the end-session endpoint redirected anywhere it was told, attackers could craft links on the trusted IdP domain that send users to phishing pages ("You've been signed out, please sign in again" on a lookalike site). Registering exact URIs per client, and checking the `id_token_hint`'s audience against the client, closes that.

</details>

<details>
<summary>"How does SSO actually work between two sites on different domains?"</summary>

Not through a shared cookie: browsers never share cookies across unrelated domains. Each site redirects to the same identity provider, whose own domain has its own session cookie. The first sign-in creates that session. When the second site redirects there, the IdP sees the session and issues a new authorisation code at once, perhaps after asking for consent for that client. The second site exchanges the code for its own ID token, with its own audience, and creates its own session. SSO is "the IdP remembers you", plus a fresh, per-client token each time.

</details>

### Further reading

- [OpenID Connect RP-Initiated Logout 1.0](https://openid.net/specs/openid-connect-rpinitiated-1_0.html)
- [OpenID Connect Back-Channel Logout 1.0](https://openid.net/specs/openid-connect-backchannel-1_0.html)
- [OpenID Connect Front-Channel Logout 1.0](https://openid.net/specs/openid-connect-frontchannel-1_0.html)
- [Spring Security reference: OIDC Logout](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/logout.html)
- [Spring Security reference: the OIDC Logout endpoint](https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/protocol-endpoints.html#oauth2AuthorizationServer-oidc-logout-endpoint)

---

## Where next

You've built the essential parts of an identity provider, and know the protocol well enough to work with any of them: Entra, Okta, Auth0 and Keycloak all speak the same OpenID Connect you've just traced. Ideas for going further, roughly in order of usefulness:

- **HTTPS everywhere**, with `Secure` cookies.
- **Refresh tokens**, so long-lived clients don't need the user, and **APIs** (resource servers) that accept Passport's access tokens. The Open Banking API tutorial covers the resource-server side.
- **Passkeys** (WebAuthn) as a phishing-resistant factor.
- **Back-channel logout**, sending logout tokens to every client.
- **Housekeeping:** expired authorisations deleted on a schedule, and signing keys rotated.
- **An admin UI** for users and clients, with its own MFA and audit log.
