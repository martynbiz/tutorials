# Identity Provider: build your own "Entra"

A series that teaches **OAuth 2.0**, **OpenID Connect** and **Spring Security** by building both sides of a single sign-on system:

- **Passport**, an identity provider (IdP) in the style of Microsoft Entra ID, Okta or Auth0. It owns the user accounts, shows the login page, asks for a second factor, and issues signed tokens.
- **The Shop**, a website on a different domain that has no passwords of its own. Its users "Sign in with Passport" and are sent back, signed in.

By the end you'll have both running on your machine, with:

- users and passwords in a database
- a branded login page
- a consent screen
- roles carried in the ID token
- one-time codes from an authenticator app (MFA)
- proper single logout

The final exercise adds a second website and shows **single sign-on** in action: sign in once, and the second site lets you straight in.

```
   Browser
   │  ▲
   │  │   1. /account              ┌──────────────────────────┐
   ├──┼──────────────────────────▶ │ The Shop                 │
   │  │   2. go and sign in at     │ shop.test:8080           │
   │  └─────── Passport ────────── │ (OAuth2 client)          │
   │                               └───────────┬──────────────┘
   │      3. login page, password,             │ 5. code → tokens
   │         code, consent                     │    (server to server)
   │                               ┌───────────▼──────────────┐
   └─────────────────────────────▶ │ Passport                 │
          4. back to the shop,     │ login.idp.test:9000      │
             with a one-time code  │ (authorisation server)   │
                                   └──────────────────────────┘
```

---

## Versions

| Thing | Version | Notes |
|---|---|---|
| Spring Boot | **4.1.2** | Latest GA when these guides were written (November 2026) |
| Spring Security | **7.1.2** | Comes with Boot. The authorisation server is now part of Spring Security itself |
| Java | **25** (LTS) | |
| Build tool | Maven, via each project's `mvnw` wrapper | |
| Database | H2 (file-based) | Passport only, from guide 03 |
| Schema | Flyway migrations | Hibernate set to `validate` |
| Views | Thymeleaf, Bootstrap 5.3 from a CDN | |

If [start.spring.io](https://start.spring.io) offers a newer **4.1.x** patch, pick it.

> **Spring Authorization Server moved.** It used to be a separate project (`spring-security-oauth2-authorization-server` 1.x, with its own `OAuth2AuthorizationServerConfigurer.authorizationServer()` setup). In Spring Security 7 it's part of Spring Security, configured with `http.oauth2AuthorizationServer(...)`, and Spring Boot 4 has a starter for it. Most blog posts and Stack Overflow answers still show the old setup. When an import won't resolve, it's usually that.

---

## What you need to know already

- **Spring Boot**: controllers, Thymeleaf, Spring Data JPA, Flyway and `application.properties`. These are covered briefly. Search for anything unfamiliar.
- **Core Java**: records, lambdas and generics.
- **Spring Security**: nothing. It's the main subject, and every concept is explained when it first appears: the filter chain, `SecurityFilterChain`, `Authentication`, the `SecurityContext`, authorities, password encoders, CSRF, sessions, and authorisation rules.

You'll use a browser's **developer tools** a lot. In Chrome, Edge or Firefox, press <kbd>F12</kbd> and open the **Network** tab. Tick **Preserve log** (Chrome or Edge) or **Persist Logs** (Firefox, under the cog), so redirects don't wipe the list.

---

## Before you start: the hosts file

Real identity providers live on their own domain, and so do the websites that use them. Your browser keeps cookies **per site**, and that separation is part of how OAuth 2.0 stays secure. So rather than running everything on `localhost`, this series gives each application its own hostname:

| Application | URL |
|---|---|
| Passport (the identity provider) | `http://login.idp.test:9000` |
| The Shop | `http://shop.test:8080` |
| The Forum (final exercise) | `http://forum.test:8081` |

`.test` is a top-level domain reserved for testing ([RFC 2606](https://www.rfc-editor.org/rfc/rfc2606)), so these names can never belong to a real website. To make them point at your own machine, add one line to your computer's **hosts file**:

```
127.0.0.1  login.idp.test  shop.test  forum.test
```

**Windows:** the file is `C:\Windows\System32\drivers\etc\hosts`. Editing it needs administrator rights:

1. Press <kbd>Start</kbd>, type **Notepad**, right-click it and choose **Run as administrator**.
2. **File → Open**, paste `C:\Windows\System32\drivers\etc\hosts` into the file name box, and change the file type from *Text Documents* to **All Files**.
3. Add the line at the end, and save.

**macOS or Linux:** run `sudo nano /etc/hosts`, add the line at the end, and save with <kbd>Ctrl</kbd>+<kbd>O</kbd>, <kbd>Enter</kbd>, <kbd>Ctrl</kbd>+<kbd>X</kbd>.

Check it worked. Each name should answer from `127.0.0.1`:

```bash
ping -n 1 login.idp.test
```

(`-n 1` is Windows. On macOS or Linux use `ping -c 1 login.idp.test`.)

You can remove the line when you've finished the series.

> **Plain HTTP, on purpose.** Everything here runs over `http://`, so you can read every request and response in the developer tools, with no certificates to set up. **A real identity provider must use HTTPS everywhere.** Over plain HTTP, anyone on the network can read passwords, codes, tokens and session cookies. Guide 01 says more.

---

## Where things live

```
tutorials/
├── java/spring-boot/identity-provider/   ← these guides
└── workspace/identity-provider/
    ├── idp/                               ← Passport: generated in guide 01
    └── shop/                              ← The Shop: generated in guide 02
```

Two separate Maven projects, as they would be in real life: the identity provider and the websites that use it are built, deployed and run independently, often by different teams.

**Running both.** From guide 02 on, start **Passport first**. The shop downloads Passport's settings when it starts, and fails if Passport isn't there. In IntelliJ, open both projects (**File → Open**, then choose **New Window**), or add the second as a module. Run each from the green arrow next to its `main` method. In a terminal, use two windows, one per project, each running `./mvnw spring-boot:run`. In **PowerShell**, type `.\mvnw` instead of `./mvnw`.

**Starting fresh.** You'll often want to see a login "from scratch". Open a **private** (incognito) browser window. It has no cookies, so neither site knows you. Closing all private windows forgets everything again.

---

## The guides

Follow them **in order**. Each builds on the code from the one before.

| # | Guide | You'll learn |
|---|---|---|
| 01 | [OAuth 2.0, OpenID Connect and a first identity provider](01-oauth2-oidc-and-a-first-idp.md) | Why SSO, the four OAuth roles, the authorisation code flow, PKCE, ID tokens vs access tokens, JWTs, JWKS and discovery. How Spring Security works: filters, `SecurityFilterChain`, `Authentication`, the `SecurityContext`. Passport with an in-memory user. The whole flow by hand, with a browser and `curl` |
| 02 | [The Shop: signing in with Passport](02-the-shop-client.md) | `oauth2Login`, following every redirect in the developer tools, `state` and `nonce`, how the shop checks the ID token, `OidcUser`, sessions per site, session fixation, Spring Security's debug log |
| 03 | [Real users: database, BCrypt and a login page](03-real-users.md) | `UserDetailsService`, `PasswordEncoder`, BCrypt and salts, `DelegatingPasswordEncoder`, a custom login page, CSRF, why every failed login gets the same message |
| 04 | [Clients and signing keys in the database](04-clients-and-keys-in-the-database.md) | `RegisteredClientRepository`, the authorisation and consent services, hashed client secrets, persistent RSA signing keys, what goes wrong without them, key rotation, start-up ordering |
| 05 | [Consent, claims and roles](05-consent-claims-and-roles.md) | The consent screen (built in, then your own), scopes and claims, an `OAuth2TokenCustomizer`, a `roles` claim, mapping it to Spring Security roles in the shop, `Authentication` vs principal |
| 06 | [Multi-factor authentication](06-multi-factor-authentication.md) | TOTP (authenticator apps) from first principles, Spring Security 7's factor authorities, `AuthorizationManagerFactories.multiFactor()`, enrolment, upgrading the `Authentication`, the `amr` claim |
| 07 | [Logout, and single sign-on](07-logout-and-single-sign-on.md) | Local vs RP-initiated logout, `end_session_endpoint`, `id_token_hint`, post-logout redirects, front- and back-channel logout. **Final exercise:** a second website, and SSO |

---

## How each guide works

1. **What you'll learn**
2. **Concepts**: the *why*, before any code.
3. **Steps**: numbered, with focused snippets.
4. **Your turn**: exercises that practise what the steps taught. Solutions are hidden in collapsible blocks.
5. **Checkpoint**: what you should see, and the files you should have.
6. **Common errors**: messages you're likely to hit, and what they really mean.
7. **Recap**, with interview questions and further reading.

Boxes like this are **sidebars**. They're useful background, but you can skip them on a first read:

> **Sidebar:** …

---

## Conventions

- **Base packages** are `com.example.idp` (Passport) and `com.example.shop` (the shop). File paths are shortened, so `security/SecurityConfig.java` means `src/main/java/com/example/idp/security/SecurityConfig.java` in whichever project the step is about.
- **Plain Java**: no Lombok, constructor injection.
- **Development secrets are in the code** (`shop-secret`, the users' passwords). That keeps the guides readable. Each guide points out what a real system would do instead.

## Deliberately out of scope

HTTPS, self-registration, password reset and anything that sends email, an admin UI, tenants, social login ("Sign in with Google"), refresh tokens, single-page and mobile apps, machine-to-machine clients, and APIs (resource servers). The Open Banking API tutorial in this repository covers the resource-server side.
