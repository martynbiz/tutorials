# 03: Real users: database, BCrypt and a login page

> Written for **Spring Boot 4.1.2**, **Spring Security 7.1.2** and **Java 25**. Builds on [guide 02](02-the-shop-client.md).

## What you'll learn

- How passwords should be stored: **hashing**, **salts** and deliberately **slow** algorithms such as **BCrypt**
- How Spring Security checks a password: `UsernamePasswordAuthenticationFilter`, `AuthenticationManager`, `DaoAuthenticationProvider`, `UserDetailsService` and `PasswordEncoder`
- What the `{bcrypt}` prefix means, and why `DelegatingPasswordEncoder` makes changing algorithms painless
- How to give Passport its own **login page**, and why a login form needs a **CSRF** token
- Why every failed login, whatever the reason, gets the **same message**

By the end, Passport's users live in a database with BCrypt-hashed passwords, and sign in on a page that's clearly Passport's.

---

## Concepts

### Storing passwords

An identity provider's user table is the most valuable thing it has. Assume that one day it will be stolen, and store passwords so the thief learns as little as possible:

- **Never in plain text**, and never with reversible **encryption**: whoever steals the data may well steal the key too.
- **Hash** them: a one-way function. To check a password, hash what the user typed and compare.
- **Salt** each one: mix a random value, different per user, into the hash, and store it alongside. Without salt, everyone with the password `password` has the same hash, so a thief cracks them all at once, or looks the hash up in a precomputed table.
- **Make it slow.** A general-purpose hash such as SHA-256 is designed to be fast: a graphics card can try billions of guesses a second. Password-hashing functions such as **BCrypt**, **scrypt** and **Argon2** are deliberately slow, and have a **cost** you raise as computers get faster. Checking one password at login takes a fraction of a second. Guessing billions takes years.

A BCrypt hash carries everything needed to check it:

```
$2a$10$ViHIYGLxw6X6IloEIBMpUOkd8LruAZaYxGtF/mRs2Q72tXVX1It2W
 │  │  └─────── salt (22 chars) ─────┘└──── hash (31 chars) ────┘
 │  └ cost: 10 means 2^10 rounds
 └ algorithm version
```

### How Spring Security checks a password

When the login form is submitted:

1. **`UsernamePasswordAuthenticationFilter`** (from `formLogin()`) reads `username` and `password` from the `POST /login` request, and wraps them in an unauthenticated `UsernamePasswordAuthenticationToken`.
2. It hands that to the **`AuthenticationManager`**. Spring's implementation, `ProviderManager`, asks each of its **`AuthenticationProvider`s** in turn.
3. **`DaoAuthenticationProvider`** handles username and password. It:
   - calls your **`UserDetailsService.loadUserByUsername(username)`**
   - checks the account isn't disabled, locked or expired
   - asks the **`PasswordEncoder`** whether the typed password `matches` the stored hash
4. On success, the result is an *authenticated* `UsernamePasswordAuthenticationToken`, with the `UserDetails` as principal and its authorities. The filter stores it in the `SecurityContext` (and so the session), and redirects to the saved request.
5. On failure, the filter redirects to `/login?error`.

Your job is the two ends: a `UserDetailsService` that finds users, and a `PasswordEncoder` that matches hashes. Spring Boot wires them into `DaoAuthenticationProvider` automatically, as long as there's exactly one bean of each.

### `{bcrypt}` and `DelegatingPasswordEncoder`

You've already seen `{noop}password` and `{noop}shop-secret`. The prefix names the algorithm that made the stored value. **`DelegatingPasswordEncoder`** reads the prefix and delegates to the right encoder:

- `{bcrypt}$2a$10$…` → BCrypt
- `{noop}…` → plain text (only for development)
- `{argon2}…`, `{scrypt}…`, `{pbkdf2}…` → those algorithms

New hashes are made with the default, BCrypt. The point is **migration**: if you move to Argon2 one day, new and changed passwords get `{argon2}`, old `{bcrypt}` ones keep working, and nobody has to reset anything.

### The same message, whatever went wrong

A login can fail because the username doesn't exist, the password is wrong, or the account is disabled. Passport says **"Wrong username or password."** every time. If it said "No such user", an attacker could test which usernames or email addresses have accounts (**user enumeration**), then concentrate on guessing their passwords.

Spring Security helps here too. For an unknown username, `DaoAuthenticationProvider` still hashes the typed password against a dummy hash, so the response takes as long as a real check. Otherwise the timing alone would give the game away.

### CSRF, and why a login form needs it

**Cross-site request forgery (CSRF):** a page on another site makes your browser send a request to a site where you're signed in, and your cookies go with it. A form on `evil.test` could POST to Passport's `/logout`, or to any form of Passport's that changes something.

Spring Security's defence is the **synchroniser token pattern**:

1. Each session gets a random **CSRF token**.
2. Every form Passport renders includes it as a hidden field (`_csrf`).
3. **`CsrfFilter`** rejects any `POST` (or `PUT`, `PATCH`, `DELETE`) that doesn't carry the right token.

Another site can make your browser *send* a request, but can't *read* Passport's pages, so it can't know the token.

The login form needs it too. Without it, an attacker could sign *you* in to *their* account (**login CSRF**). You'd then do things, such as type card details, in an account they can later read.

You'll notice the protocol endpoints in chain 1 don't use CSRF tokens. They're protected by the client's credentials, `state` and PKCE instead.

---

## Steps

### 1. Add the database dependencies

In Passport's `pom.xml`, add next to the others:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-flyway</artifactId>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
</dependency>
```

Reload Maven (the **M** icon, or **Maven tool window → Reload All Maven Projects**).

Add to `application.properties`:

```properties

# Users (and, from guide 04, clients and keys) live in ./data/idp.mv.db
spring.datasource.url=jdbc:h2:file:./data/idp
spring.datasource.username=sa
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
```

Flyway creates the tables, and Hibernate only checks that the entities match them. Add `data/` to the project's `.gitignore`.

### 2. Hash some passwords

You need BCrypt hashes for the development users. Spring Security's `PasswordEncoder` is a plain Java class, so JShell can make them, given two jars from your Maven repository (`~/.m2`, under your home folder).

**Windows (PowerShell):**

```powershell
jshell --class-path "$HOME\.m2\repository\org\springframework\security\spring-security-crypto\7.1.2\spring-security-crypto-7.1.2.jar;$HOME\.m2\repository\commons-logging\commons-logging\1.3.6\commons-logging-1.3.6.jar"
```

**macOS or Linux:**

```bash
jshell --class-path ~/.m2/repository/org/springframework/security/spring-security-crypto/7.1.2/spring-security-crypto-7.1.2.jar:~/.m2/repository/commons-logging/commons-logging/1.3.6/commons-logging-1.3.6.jar
```

(The separator is `;` on Windows and `:` elsewhere. If your versions differ, look in those folders.) Then:

```java
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
encoder.encode("password")
encoder.encode("password")
encoder.encode("password")
```

```
$3 ==> "{bcrypt}$2a$10$ViHIYGLxw6X6IloEIBMpUOkd8LruAZaYxGtF/mRs2Q72tXVX1It2W"
$4 ==> "{bcrypt}$2a$10$7yNM0UULrZ/Tz5jwvygqW.Wx/hwtX631rDMBWFj5xvlNAjTSACldS"
$5 ==> "{bcrypt}$2a$10$5C65CkvBfceVCyTdM1rpeOOFKmAG9yJjFl6vdSxz5Zum7/sYmoYai"
```

The same password, three different hashes: each has its own random salt. `encoder.matches("password", "{bcrypt}$2a$10$Vi…")` is `true` for all three. Your hashes will differ from these. Either set works.

### 3. The user table

Create `src/main/resources/db/migration/V1__create_user_account.sql`:

```sql
CREATE TABLE user_account (
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    given_name    VARCHAR(50)  NOT NULL,
    family_name   VARCHAR(50)  NOT NULL,
    email         VARCHAR(255) NOT NULL,
    enabled       BOOLEAN      NOT NULL,
    CONSTRAINT uk_user_account_username UNIQUE (username)
);

-- Development users only. Every password is "password".
INSERT INTO user_account (username, password_hash, given_name, family_name, email, enabled) VALUES
    ('alice', '{bcrypt}$2a$10$ViHIYGLxw6X6IloEIBMpUOkd8LruAZaYxGtF/mRs2Q72tXVX1It2W', 'Alice', 'Smith', 'alice@example.com', TRUE),
    ('bob',   '{bcrypt}$2a$10$7yNM0UULrZ/Tz5jwvygqW.Wx/hwtX631rDMBWFj5xvlNAjTSACldS', 'Bob',   'Jones', 'bob@example.com',   TRUE),
    ('carol', '{bcrypt}$2a$10$5C65CkvBfceVCyTdM1rpeOOFKmAG9yJjFl6vdSxz5Zum7/sYmoYai', 'Carol', 'Brown', 'carol@example.com', FALSE);
```

Carol's account is **disabled**: she exists, and her password is right, but she can't sign in. That's how an organisation handles a leaver, keeping the record but blocking access.

> **Real systems** never put user passwords in a migration. Accounts come from a registration flow or an admin tool, and initial passwords are random and must be changed. Seeding is fine for development data.

### 4. The entity and repository

`user/UserAccount.java`:

```java
package com.example.idp.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Someone who can sign in to Passport. */
@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "given_name", nullable = false, length = 50)
    private String givenName;

    @Column(name = "family_name", nullable = false, length = 50)
    private String familyName;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private boolean enabled;

    protected UserAccount() {
        // for JPA
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getGivenName() {
        return givenName;
    }

    public String getFamilyName() {
        return familyName;
    }

    public String getEmail() {
        return email;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
```

`user/UserAccountRepository.java`:

```java
package com.example.idp.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByUsername(String username);
}
```

### 5. A `UserDetailsService`

`user/PassportUserDetailsService.java`:

```java
package com.example.idp.user;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tells Spring Security how to find a user by username, and what it needs to know about them. */
@Service
public class PassportUserDetailsService implements UserDetailsService {

    private final UserAccountRepository users;

    public PassportUserDetailsService(UserAccountRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        UserAccount account = users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("No user called " + username));
        return User.withUsername(account.getUsername())
                .password(account.getPasswordHash())
                .disabled(!account.isEnabled())
                .roles("USER")
                .build();
    }
}
```

- **`UserDetails`** is Spring Security's view of a user: username, password hash, authorities, and four flags (enabled, not locked, account not expired, credentials not expired). Spring's `User` class implements it, and its builder is the easiest way to make one.
- **`UsernameNotFoundException`** is how the interface says "no such user". `DaoAuthenticationProvider` turns it into the same `BadCredentialsException` as a wrong password, so the login page can't tell them apart.
- **`.disabled(...)`**: the provider checks this flag *after* loading the user. Carol gets a `DisabledException`.
- **`.roles("USER")`** becomes the authority `ROLE_USER`. Everyone is a plain user for now. Guide 05 stores roles.

It's a separate class, not `UserAccount implements UserDetails`, on purpose: the entity is Passport's model of a person, and `UserDetails` is what Spring Security needs at login. Keeping them apart stops security concerns leaking into the domain.

### 6. The password encoder

In `security/SecurityConfig.java`, **delete** the in-memory `userDetailsService()` bean and its imports (`User`, `UserDetails`, `UserDetailsService`, `InMemoryUserDetailsManager`), and add:

```java
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
...

    /** Hashes and checks passwords. The {id} prefix on each stored hash says which algorithm made it. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
```

Spring Boot now finds one `UserDetailsService` bean (yours) and one `PasswordEncoder`, and wires them into `DaoAuthenticationProvider`. The shop's client secret in `application.properties` is still `{noop}shop-secret`, and that still works: the authorisation server checks client secrets with the same `PasswordEncoder`, and the delegating encoder understands `{noop}`.

### 7. Passport's own login page

Spring's generated login page works, but in a real IdP the login page is the one page every user sees, so it should say clearly whose it is. That's also a defence against phishing: users learn what the real one looks like, and where it lives (`login.idp.test`).

Change the second chain's `formLogin` in `SecurityConfig`:

```java
    @Bean
    @Order(2)
    SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .formLogin(form -> form.loginPage("/login").permitAll());
        return http.build();
    }
```

- **`loginPage("/login")`** says "the login page is mine, at `/login`". Spring stops generating one, but `UsernamePasswordAuthenticationFilter` still handles `POST /login`.
- **`.permitAll()`** lets anonymous users see the login page. Otherwise they'd need to be signed in to sign in, and be redirected for ever.

Create `PassportController.java` in `com.example.idp`:

```java
package com.example.idp;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Passport's own pages: the login page, and a home page for anyone who signs in directly. */
@Controller
public class PassportController {

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    public String home() {
        return "home";
    }
}
```

`src/main/resources/templates/login.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Sign in to Passport</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css">
</head>
<body class="bg-light">
<main class="container" style="max-width: 400px">
    <h1 class="h3 mt-5 mb-1">Passport</h1>
    <p class="text-secondary mb-4">Sign in with your Passport account</p>

    <div class="alert alert-danger" th:if="${param.error}">Wrong username or password.</div>
    <div class="alert alert-success" th:if="${param.logout}">You've signed out of Passport.</div>

    <form th:action="@{/login}" method="post">
        <div class="mb-3">
            <label for="username" class="form-label">Username</label>
            <input id="username" name="username" class="form-control" autocomplete="username" required autofocus>
        </div>
        <div class="mb-3">
            <label for="password" class="form-label">Password</label>
            <input id="password" name="password" type="password" class="form-control"
                   autocomplete="current-password" required>
        </div>
        <button type="submit" class="btn btn-primary w-100">Sign in</button>
    </form>
</main>
</body>
</html>
```

- The fields must be called **`username`** and **`password`**, the names `UsernamePasswordAuthenticationFilter` reads.
- **`th:action="@{/login}"`**: when Thymeleaf renders a `POST` form with `th:action`, it adds the CSRF token as a hidden field automatically. View the page source and you'll see `<input type="hidden" name="_csrf" value="NhKwwuPisHv_…"/>`.
- **`${param.error}`** and **`${param.logout}`** read the query string. Spring redirects to `/login?error` after a failed login, and to `/login?logout` after signing out.
- **`autocomplete`** values help password managers: `current-password` is also a hint that this isn't a "new password" form.

And `templates/home.html`, the page you see if you go to Passport directly:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org" xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Passport</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css">
</head>
<body class="bg-light">
<main class="container" style="max-width: 600px">
    <h1 class="h3 mt-5">Passport</h1>
    <p>You're signed in to Passport as <strong sec:authentication="name">alice</strong>.</p>
</main>
</body>
</html>
```

### 8. Try it

Restart Passport. Flyway runs the migration:

```
Migrating schema "PUBLIC" to version "1 - create user account"
Successfully applied 1 migration to schema "PUBLIC", now at version v1
```

In a private window, go to the shop's **My account**. You land on Passport's own login page. Sign in as `alice` / `password`, and you're back at the shop.

Now try each way of failing, each in a **new** private window. Go straight to `http://login.idp.test:9000/` for these, which also shows Passport's home page when it works:

| Username | Password | Result |
|---|---|---|
| `alice` | `wrong` | `302` → `/login?error`: "Wrong username or password." |
| `carol` | `password` | `302` → `/login?error`: the same message, though the password was right |
| `nobody` | `password` | `302` → `/login?error`: the same message again |
| `alice` | `password` | `302` → `/?continue`: "You're signed in to Passport as alice." |

Now show that CSRF protection works. Send a login **without** the token, as another site's page would:

```bash
curl -i -d username=alice -d password=password http://login.idp.test:9000/login
```

```
HTTP/1.1 302
Location: http://login.idp.test:9000/login;jsessionid=010DCE47B2B500B18ACAEE64FB0F4193
```

Back to the login page, and nobody is signed in: `CsrfFilter` rejected the request before `UsernamePasswordAuthenticationFilter` ever saw it. (The `;jsessionid=…` in the URL appears because curl didn't send a cookie, so Tomcat tried the URL instead.) A forged token, `-d _csrf=forged`, gets the same treatment.

---

## Your turn

### Exercise 1: say hello properly

Make Passport's home page say "You're signed in to Passport as **Alice Smith** (alice)", using the names from the database.

<details>
<summary>Hint</summary>

The shop's controller got the signed-in user with `@AuthenticationPrincipal` (guide 02). At Passport, after a form login, the principal is the `UserDetails` your service built (Concepts), so its `getUsername()` is the username. `UserAccountRepository` can then find the user.

</details>

<details>
<summary>Solution</summary>

In `PassportController`:

```java
package com.example.idp;

import com.example.idp.user.UserAccountRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Passport's own pages: the login page, and a home page for anyone who signs in directly. */
@Controller
public class PassportController {

    private final UserAccountRepository users;

    public PassportController(UserAccountRepository users) {
        this.users = users;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    public String home(@AuthenticationPrincipal UserDetails principal, Model model) {
        model.addAttribute("user", users.findByUsername(principal.getUsername()).orElseThrow());
        return "home";
    }
}
```

In `home.html`:

```html
    <p>You're signed in to Passport as
        <strong th:text="${user.givenName + ' ' + user.familyName}">Alice Smith</strong>
        (<span th:text="${user.username}">alice</span>).</p>
```

The `UserDetails` holds what Spring Security needed at login: username, hash and authorities. The database holds everything else, so the controller looks the user up. The later guides keep the simpler page from step 7.

</details>

### Exercise 2: hash the client secret

The shop's client secret is still `{noop}shop-secret` in Passport's `application.properties`. Store a BCrypt hash of it instead, and check that the shop can still sign in.

<details>
<summary>Hint</summary>

Make the hash in JShell, as in step 2. The prefix tells the delegating encoder which algorithm to use.

</details>

<details>
<summary>Solution</summary>

In JShell (step 2): `encoder.encode("shop-secret")`, which gives something like `{bcrypt}$2a$10$…`. In `application.properties`:

```properties
spring.security.oauth2.authorizationserver.client.shop.registration.client-secret={bcrypt}$2a$10$...your hash...
```

Restart Passport and sign in to the shop: it still works. The shop still sends `shop-secret`. Passport hashes what it receives and compares, exactly as it does for users' passwords. A thief who reads Passport's configuration now gets a hash, not a secret that works. Guide 04 moves clients into the database, where the secret is hashed the same way.

</details>

---

## Checkpoint

- Signing in to the shop goes through **Passport's** login page.
- A wrong password, a disabled account and an unknown user all show "Wrong username or password."
- `http://login.idp.test:9000/` shows "You're signed in to Passport as alice" after signing in.

```
idp/
├── pom.xml
├── data/                         (created by H2, not committed)
└── src/main/
    ├── java/com/example/idp/
    │   ├── IdpApplication.java
    │   ├── PassportController.java
    │   ├── security/
    │   │   └── SecurityConfig.java
    │   └── user/
    │       ├── PassportUserDetailsService.java
    │       ├── UserAccount.java
    │       └── UserAccountRepository.java
    └── resources/
        ├── application.properties
        ├── db/migration/
        │   └── V1__create_user_account.sql
        └── templates/
            ├── home.html
            └── login.html
```

---

## Common errors

**Every login fails, even with the right password**
Check the stored hash has its prefix: `{bcrypt}$2a$10$…`. Without one, the delegating encoder doesn't know the algorithm, and logs `There is no PasswordEncoder mapped for the id "null"`. Also check you didn't leave the in-memory `UserDetailsService` bean in place as well: with two, Spring Boot doesn't know which to use.

**Redirected to `/login` for ever, `ERR_TOO_MANY_REDIRECTS`**
The login page isn't public. Check `.formLogin(form -> form.loginPage("/login").permitAll())`.

**Submitting the login form just shows the login page again, with no error message**
The form has no CSRF token, so `CsrfFilter` rejected it before the password was even checked (step 8). Use `th:action="@{/login}"`, not a plain `action="/login"`: Thymeleaf only adds the token through `th:action`. View the page source to check the hidden `_csrf` field is there.

**`Schema-validation: missing table [user_account]`**
Flyway didn't run the migration. Check the path is exactly `src/main/resources/db/migration/` and the file name has **two** underscores: `V1__create_user_account.sql`.

**`Database may be already in use: ".../data/idp.mv.db"`**
Passport is running twice, or something else has the file open. Stop the other one.

---

## Recap

- Store passwords as **salted, slow hashes** (BCrypt, scrypt or Argon2), never in plain text or encrypted. BCrypt hashes carry their own algorithm, cost and salt.
- **`DelegatingPasswordEncoder`** reads the `{id}` prefix, so stored hashes from several algorithms can coexist. That's how you move to a stronger one.
- At login: `UsernamePasswordAuthenticationFilter` → `AuthenticationManager` → `DaoAuthenticationProvider` → your **`UserDetailsService`** and **`PasswordEncoder`**.
- Give an IdP **its own login page**: `loginPage("/login").permitAll()`, fields called `username` and `password`, and `th:action` for the **CSRF** token.
- Fail every login with **one message**, so attackers can't enumerate accounts. Spring even equalises the timing.

### Interview angle

<details>
<summary>"How should passwords be stored?"</summary>

With a slow, salted, adaptive password-hashing function: BCrypt, scrypt or Argon2id, with a cost tuned so one hash takes tens to hundreds of milliseconds on your servers. Not plain text, not reversible encryption, and not a fast hash such as SHA-256, even salted. Store the algorithm and parameters with each hash (Spring's `{id}` prefix, or BCrypt's own `$2a$10$`), so you can raise the cost or change algorithm later, and rehash passwords as users sign in.

</details>

<details>
<summary>"What is CSRF, and why does a login form need protection against it?"</summary>

Cross-site request forgery is when another site causes the victim's browser to send a state-changing request to a site where the victim's cookies are attached. The defence is a secret token in each form that another origin can't read (Spring's synchroniser token), plus `SameSite` cookies. A login form needs it because of login CSRF: an attacker can sign the victim in to the attacker's own account, then later see whatever the victim did there.

</details>

### Further reading

- [Spring Security reference: Password Storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)
- [Spring Security reference: Username/Password Authentication](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/index.html)
- [OWASP: Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
- [OWASP: Cross-Site Request Forgery Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)

**Next:** [04: Clients and signing keys in the database](04-clients-and-keys-in-the-database.md). Restarting Passport stops losing everything.
