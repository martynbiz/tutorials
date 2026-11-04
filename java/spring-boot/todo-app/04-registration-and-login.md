# 04 — Registration and Login

> Written for **Spring Boot 4.1.2** (Spring Security 7) and **Java 25**. Builds on [03 — Data, JPA and relationships](03-data-jpa-and-relationships.md).

## What you'll learn

- How Spring Security works: the **filter chain**, authentication vs authorisation, the `SecurityContext`
- How to configure it with a `SecurityFilterChain` bean (the modern, lambda-style API)
- How Spring Security loads your users through `UserDetailsService`, and checks passwords with a `PasswordEncoder`
- Password hashing with BCrypt, and what `{bcrypt}` / `{noop}` prefixes mean
- **Bean Validation** (`@NotBlank`, `@Email`, `@Size`) and binding forms with `th:object` / `th:field`
- **CSRF** protection, and why Thymeleaf's `th:action` matters
- A custom login page, logout, and showing the logged-in user in templates
- **IntelliJ:** implementing interface methods, **watching the security filter chain in the debugger**, and conditional breakpoints

By the end, visitors can register, log in and log out. Everything except a few public pages requires a login.

---

## Concepts

### Authentication vs authorisation

- **Authentication** answers *who are you?* by checking an email and password.
- **Authorisation** answers *are you allowed to do this?*, e.g. "must be logged in to see `/lists`".

This guide covers both at the URL level. Guide 06 adds the finer-grained rule: you may only touch **your own** data.

### The filter chain

Spring Security is a chain of **servlet filters** that every request passes through **before** it reaches the `DispatcherServlet` and your controllers:

```
Request ─► [ SecurityFilterChain                                        ] ─► DispatcherServlet ─► Controller
           │ CsrfFilter               – rejects POSTs without a valid token │
           │ LogoutFilter             – handles POST /logout                │
           │ UsernamePasswordAuthenticationFilter – handles POST /login     │
           │ … session / context filters …                                  │
           │ AuthorizationFilter      – "is this user allowed this URL?"     │
           └──────────────────────────────────────────────────────────────┘
```

Note that **you don't write a controller for `POST /login` or `POST /logout`**. Filters handle those before Spring MVC sees them. You only provide the login *page* (`GET /login`).

### What happens at login

1. The browser POSTs `email` + `password` to `/login`.
2. The authentication filter asks the `AuthenticationManager` to authenticate them.
3. It calls **your** `UserDetailsService.loadUserByUsername(email)` to fetch the stored user and password hash.
4. The `PasswordEncoder` checks whether the submitted password matches that hash.
5. On success, an `Authentication` object is stored in the **`SecurityContext`**, which is saved in the HTTP **session**. The browser gets a `JSESSIONID` cookie, and later requests are recognised by that cookie.
6. The user is redirected to the success URL.

### Password hashing

**Never store passwords**, only one-way **hashes**. BCrypt is deliberately slow and salts each hash, so leaked hashes are very expensive to crack. A BCrypt hash looks like `$2a$10$N9qo8uLOickgx2ZMRZoMy...`.

Spring Security's recommended encoder is a **`DelegatingPasswordEncoder`**. It stores the algorithm in front of the hash:

```
{bcrypt}$2a$10$N9qo8uLOickgx2ZMRZoMye...
{noop}password                              ← plain text, never use in production
```

That's why the demo user in guide 03 has the password `{noop}password`: it will work for logging in today. The prefix also means you can switch algorithms later without breaking existing users.

### CSRF

**Cross-Site Request Forgery**: a malicious site tricks your logged-in browser into POSTing to your app (say, "delete my list"), and the browser helpfully sends your session cookie along. The defence is a secret **CSRF token**, embedded in each of *your* forms, that the other site can't read. Spring Security **rejects any POST/PUT/DELETE without a valid token** with a 403.

The good news: **Thymeleaf adds the hidden token automatically to any form that uses `th:action`.** Use `th:action="@{/path}"`, never a plain `action="/path"`, for every form.

---

## Steps

### 1. Add the dependencies

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<dependency>
    <groupId>org.thymeleaf.extras</groupId>
    <artifactId>thymeleaf-extras-springsecurity6</artifactId>
</dependency>
```

- `spring-boot-starter-security`: Spring Security plus Boot's auto-configuration for it.
- `spring-boot-starter-validation`: Hibernate Validator, the implementation of Jakarta Bean Validation (`@NotBlank` etc.).
- `thymeleaf-extras-springsecurity…`: the `sec:` dialect for templates. **Check the artifact name** against start.spring.io → *Explore* with *Spring Security* + *Thymeleaf* selected. Initializr adds the correct one for your Boot version, and its version is managed by the parent.

**Load Maven Changes** (<kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>O</kbd>) and restart the app. A new dependency always needs a full restart.

### 2. See what Boot does by default

Restart and visit <http://localhost:8080/>. You're redirected to a generated login page. In the start-up log:

```
Using generated security password: 3f6c1a2e-....
```

Log in with user **`user`** and that password. This is Boot's default: one in-memory user, and every URL locked. It disappears as soon as you define your own `UserDetailsService`, because of `@ConditionalOnMissingBean` from guide 01.

### 3. Security configuration

Create `security/SecurityConfig.java`:

```java
package com.example.todoapp.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/about", "/register", "/login", "/error").permitAll()
                .requestMatchers("/css/**", "/js/**", "/images/**").permitAll()
                .requestMatchers("/h2-console/**").permitAll()
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/login")
                .usernameParameter("email")
                .defaultSuccessUrl("/", true)
                .permitAll())
            .logout(logout -> logout
                .logoutSuccessUrl("/login?logout")
                .permitAll())
            // H2 console only: it posts without our CSRF token and renders in frames
            .csrf(csrf -> csrf.ignoringRequestMatchers("/h2-console/**"))
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));

        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
```

Taking it apart:

- `@Configuration` + `@Bean`: the **other** way to define beans. Use it when you need to *build* an object (often from a library) rather than annotate your own class. Spring calls each `@Bean` method once and registers the return value. The `HttpSecurity` parameter is itself injected by Spring.
- **`authorizeHttpRequests`**: rules are checked **top to bottom, first match wins**, so always finish with `anyRequest()`. We make public pages, static files and the H2 console accessible to everyone, and require login for everything else (including `/hello` and `/greet/**` from earlier guides).
- **`formLogin`**: `loginPage("/login")` swaps Boot's generated page for yours. `usernameParameter("email")` means our form field is called `email`, not the default `username`. `defaultSuccessUrl("/", true)` always sends the user home after login (guide 05 changes this to `/lists`).
- **`logout`**: `POST /logout` clears the session and redirects to `/login?logout`.
- **CSRF / frame options**: the H2 console is a separate little web app that isn't written for Spring Security. These two lines exist only for it. Never carry them into production.
- The **`PasswordEncoder`** bean is used both to hash passwords at registration and to check them at login.

> You'll see `@EnableWebSecurity` in many examples. Spring Boot applies it for you, so it isn't needed here. It's harmless if you add it.
>
> Older tutorials extend `WebSecurityConfigurerAdapter` and use `.and()` chains or `antMatchers(...)`. **All of those have been removed** in Spring Security 7. If an example uses them, it's out of date.

### 4. Load users from the database: `UserDetailsService`

Spring Security doesn't know about your `User` entity. You bridge the two by implementing `UserDetailsService`.

> **IntelliJ:** write `public class AppUserDetailsService implements UserDetailsService {}`, put the cursor on the red class name, press <kbd>Alt</kbd>+<kbd>Enter</kbd> → **Implement methods** (or <kbd>Ctrl</kbd>+<kbd>I</kbd>), and IntelliJ writes the `loadUserByUsername` signature for you. <kbd>Ctrl</kbd>+click `UserDetailsService` to read Spring's source and Javadoc. IntelliJ downloads library sources on request (click **Download Sources** at the top of the decompiled file). Reading framework code this way is a good habit.

Create `security/AppUserDetailsService.java`:

```java
package com.example.todoapp.security;

import java.util.Locale;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.example.todoapp.user.User;
import com.example.todoapp.user.UserRepository;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        String normalised = email.trim().toLowerCase(Locale.ROOT);

        User user = userRepository.findByEmail(normalised)
                .orElseThrow(() -> new UsernameNotFoundException("No user " + normalised));

        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .roles("USER")
                .build();
    }
}
```

- The method is called `loadUserByUsername`, but our "username" is the email address.
- **The name clash:** Spring Security has its own class called `User` (an implementation of `UserDetails`). Ours is imported, so theirs is written with its fully qualified name. This is a common annoyance. Some teams call their entity `AppUser` or `Account` to avoid it.
- `.roles("USER")`: every user gets one role. We don't use roles for anything, but `UserDetails` needs at least an empty authority list, and this is the conventional default.
- We **normalise** the email (trim and lower-case) so that `Demo@Example.com` and `demo@example.com` are the same account. Registration must do exactly the same.
- The `UsernameNotFoundException` message is never shown to the user. Spring Security reports "Bad credentials" whether the email or the password was wrong, so attackers can't probe for which emails exist.

Because this is a `UserDetailsService` bean and a `PasswordEncoder` bean exists, Boot wires them into its authentication provider automatically. The "generated security password" disappears from the log.

### 5. A custom login page

`security/LoginController.java`:

```java
package com.example.todoapp.security;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
```

`templates/login.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('Log in')}"></head>
<body>
<nav th:replace="~{fragments/layout :: navbar}"></nav>

<main class="container" style="max-width: 420px">
    <h1 class="h3 mb-3">Log in</h1>

    <div class="alert alert-danger" th:if="${param.error}">Invalid email or password.</div>
    <div class="alert alert-success" th:if="${param.logout}">You have been logged out.</div>

    <form th:action="@{/login}" method="post">
        <div class="mb-3">
            <label for="email" class="form-label">Email</label>
            <input type="email" id="email" name="email" class="form-control" required autofocus>
        </div>
        <div class="mb-3">
            <label for="password" class="form-label">Password</label>
            <input type="password" id="password" name="password" class="form-control" required>
        </div>
        <button type="submit" class="btn btn-primary w-100">Log in</button>
    </form>

    <p class="mt-3 text-center">No account? <a th:href="@{/register}">Register</a></p>
</main>

<th:block th:replace="~{fragments/layout :: scripts}"></th:block>
</body>
</html>
```

- `${param.error}` reads the **request parameter** `?error`. Spring Security redirects to `/login?error` on failure (and `/login?logout` after logout).
- `th:action="@{/login}"`: view the page source in your browser and you'll find `<input type="hidden" name="_csrf" value="...">`, which Thymeleaf added for you.
- The field names `email` and `password` must match `usernameParameter("email")` and the default password parameter.

Restart and log in as **`demo@example.com` / `password`**. It works, thanks to `{noop}`. You land on the home page.

**Watch a login happen in the debugger.** This is the best way to understand the filter chain:

1. Put a breakpoint on the first line of `AppUserDetailsService.loadUserByUsername`.
2. Restart with **Debug**, log out (or use a private browser window), and log in.
3. When it stops, look at the **call stack** in the Debug window. Reading from the bottom up, you'll find Tomcat, then a run of Spring Security filters (`FilterChainProxy`, `CsrfFilter`, `LogoutFilter`, `UsernamePasswordAuthenticationFilter`), then `ProviderManager`, `DaoAuthenticationProvider` and finally your method. Notice there's **no `DispatcherServlet` and no controller anywhere**: the login was handled entirely by filters, exactly as described in Concepts.
4. Click a frame to see its variables. In `UsernamePasswordAuthenticationFilter`, you can see the submitted request.
5. Press **Step Out** (<kbd>Shift</kbd>+<kbd>F8</kbd>) a few times to watch your `UserDetails` travel back up, and see the password check happen in `DaoAuthenticationProvider`.

> **Conditional breakpoints:** right-click the red dot and enter a **Condition** such as `email.startsWith("demo")`. The debugger then only stops for that user. This is useful once lots of requests are going through.

In the browser, open **dev tools (<kbd>F12</kbd>) → Application/Storage → Cookies** to see the `JSESSIONID` cookie appear after login, and check that its value changes. Spring Security issues a new session ID at login to prevent *session fixation*.

### 6. The registration form object

A **form-backing object** holds whatever a form submits. Create `user/RegistrationForm.java`:

```java
package com.example.todoapp.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class RegistrationForm {

    @NotBlank(message = "Email is required")
    @Email(message = "Enter a valid email address")
    @Size(max = 254)
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    private String password;

    @NotBlank(message = "Please confirm your password")
    private String confirmPassword;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getConfirmPassword() { return confirmPassword; }
    public void setConfirmPassword(String confirmPassword) { this.confirmPassword = confirmPassword; }
}
```

- These are **Jakarta Bean Validation** constraints. They do nothing by themselves; they run when a controller parameter is marked `@Valid` (step 8).
- Why `max = 72`? BCrypt only looks at the first **72 bytes** of a password. Rejecting longer ones avoids a surprising edge case.
- Why a class with getters and setters, rather than a record? Spring's form binding and Thymeleaf's `th:field` are built around **JavaBean properties** (`getX`/`setX`), and an empty form object for the "new" page is natural with a no-arg constructor. Records are great for read-only data such as `TodoListSummary`. For mutable form objects, a plain class is the conventional, friction-free choice.
- Why not validate the entity itself? The form and the entity have different jobs. The form has `confirmPassword` and a *raw* password; the entity has a *hash* and no confirmation. Keeping them separate also means a user can never set fields you didn't put on the form (e.g. `id`). That attack is called **mass assignment**.

### 7. The registration service

`user/EmailAlreadyRegisteredException.java`:

```java
package com.example.todoapp.user;

public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException(String email) {
        super("Email already registered: " + email);
    }
}
```

`user/UserService.java`:

```java
package com.example.todoapp.user;

import java.util.Locale;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User register(String email, String rawPassword) {
        String normalised = email.trim().toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmail(normalised)) {
            throw new EmailAlreadyRegisteredException(normalised);
        }

        String hash = passwordEncoder.encode(rawPassword);
        return userRepository.save(new User(normalised, hash));
    }
}
```

- The service takes plain values rather than the form object. The service layer shouldn't depend on web classes, which also makes it easier to unit test (guide 07).
- `passwordEncoder.encode(...)` produces `{bcrypt}$2a$10$...`.
- The `existsByEmail` check gives a friendly error. The **unique constraint** on `users.email` from guide 03 is the real guarantee: two simultaneous registrations could both pass the check, but only one insert would succeed.

### 8. The registration controller

`user/RegistrationController.java`:

```java
package com.example.todoapp.user;

import java.util.Objects;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

import jakarta.validation.Valid;

@Controller
public class RegistrationController {

    private final UserService userService;

    public RegistrationController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/register")
    public String showForm(Model model) {
        model.addAttribute("form", new RegistrationForm());
        return "register";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("form") RegistrationForm form,
                           BindingResult result) {

        if (!Objects.equals(form.getPassword(), form.getConfirmPassword())) {
            result.rejectValue("confirmPassword", "mismatch", "Passwords do not match");
        }

        if (result.hasErrors()) {
            return "register";                       // re-render the form, showing errors
        }

        try {
            userService.register(form.getEmail(), form.getPassword());
        } catch (EmailAlreadyRegisteredException e) {
            result.rejectValue("email", "duplicate", "An account with that email already exists");
            return "register";
        }

        return "redirect:/login?registered";
    }
}
```

This is **the standard Spring MVC form-handling pattern**, and you'll repeat it in guides 05 and 06:

1. **GET** puts an empty form object in the model and renders the form.
2. **POST** has Spring **bind** request parameters onto a `RegistrationForm` by calling its setters (`@ModelAttribute("form")` names it `form` in the model).
3. `@Valid` runs the Bean Validation constraints. Failures go into the `BindingResult`.
4. **The `BindingResult` parameter must come *immediately after* the object it belongs to.** If it's missing or in the wrong place, Spring throws an exception on validation failure instead of letting you handle it.
5. You can add your **own** errors with `rejectValue(field, code, message)` for rules the annotations can't express, such as comparing two fields, or checking the database.
6. On errors, **return the view name** (not a redirect), so the same form object, with its errors and the user's input, is re-rendered.
7. On success, **redirect**. This is the Post/Redirect/Get pattern (guide 05 explains why).

> **About "an account with that email already exists":** this tells an attacker which emails are registered (*account enumeration*). For a learning app it's a reasonable usability trade-off. High-security sites instead show a neutral message and send an email. Just be aware of the choice.

### 9. The registration template

`templates/register.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('Register')}"></head>
<body>
<nav th:replace="~{fragments/layout :: navbar}"></nav>

<main class="container" style="max-width: 420px">
    <h1 class="h3 mb-3">Create an account</h1>

    <form th:action="@{/register}" th:object="${form}" method="post" novalidate>

        <div class="mb-3">
            <label for="email" class="form-label">Email</label>
            <input type="email" th:field="*{email}" class="form-control"
                   th:classappend="${#fields.hasErrors('email')} ? 'is-invalid'">
            <div class="invalid-feedback" th:errors="*{email}">Email error</div>
        </div>

        <div class="mb-3">
            <label for="password" class="form-label">Password</label>
            <input type="password" th:field="*{password}" class="form-control"
                   th:classappend="${#fields.hasErrors('password')} ? 'is-invalid'">
            <div class="invalid-feedback" th:errors="*{password}">Password error</div>
        </div>

        <div class="mb-3">
            <label for="confirmPassword" class="form-label">Confirm password</label>
            <input type="password" th:field="*{confirmPassword}" class="form-control"
                   th:classappend="${#fields.hasErrors('confirmPassword')} ? 'is-invalid'">
            <div class="invalid-feedback" th:errors="*{confirmPassword}">Confirm error</div>
        </div>

        <button type="submit" class="btn btn-primary w-100">Register</button>
    </form>

    <p class="mt-3 text-center">Already registered? <a th:href="@{/login}">Log in</a></p>
</main>

<th:block th:replace="~{fragments/layout :: scripts}"></th:block>
</body>
</html>
```

- `th:object="${form}"` selects the form object. Inside it, `*{email}` means `form.email`.
- `th:field="*{email}"` generates `id="email" name="email" value="..."` for you. The `name` matches the setter Spring will call on submit, and `value` re-fills the user's input after a validation failure. (Thymeleaf deliberately doesn't refill `type="password"` fields.)
- `#fields.hasErrors('email')` and `th:errors="*{email}"` read the `BindingResult`. `th:errors` renders the error messages, or nothing at all when there are none.
- `is-invalid` / `invalid-feedback` are Bootstrap classes that show the red border and message.
- `novalidate` turns off the browser's own validation, so you can see *server-side* validation working. Keep it while learning; remove it if you like the instant browser feedback. **Server-side validation is the one that counts**, because browser checks are trivial to bypass.

### 10. Show who's logged in

Add the `sec` namespace and update the navbar in `fragments/layout.html`:

```html
<html lang="en" xmlns:th="http://www.thymeleaf.org"
      xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
```

```html
<nav th:fragment="navbar" class="navbar navbar-expand navbar-dark bg-dark mb-4">
    <div class="container">
        <a class="navbar-brand" th:href="@{/}">Todo App</a>
        <ul class="navbar-nav ms-auto align-items-center">
            <li class="nav-item"><a class="nav-link" th:href="@{/about}">About</a></li>

            <th:block sec:authorize="isAnonymous()">
                <li class="nav-item"><a class="nav-link" th:href="@{/login}">Log in</a></li>
                <li class="nav-item"><a class="nav-link" th:href="@{/register}">Register</a></li>
            </th:block>

            <th:block sec:authorize="isAuthenticated()">
                <li class="nav-item">
                    <span class="navbar-text me-3" sec:authentication="name">user@example.com</span>
                </li>
                <li class="nav-item">
                    <form th:action="@{/logout}" method="post" class="d-inline">
                        <button type="submit" class="btn btn-outline-light btn-sm">Log out</button>
                    </form>
                </li>
            </th:block>
        </ul>
    </div>
</nav>
```

- `sec:authorize="..."` renders the element only if the expression is true. The expressions are Spring Security's: `isAuthenticated()`, `isAnonymous()`, `hasRole('USER')`, …
- `sec:authentication="name"` prints the current principal's name, which is our email.
- **Logout is a POST form, not a link.** A GET logout could be triggered by any `<img src="/logout">` on another site. The form carries the CSRF token.

### 11. Use the current user in a controller

There are three ways to get the logged-in user in Spring MVC:

```java
// 1. The UserDetails object you built in AppUserDetailsService
@GetMapping("/")
public String home(@AuthenticationPrincipal UserDetails currentUser, Model model) { ... }

// 2. The generic java.security.Principal (null when nobody is logged in)
public String home(Principal principal) { ... }

// 3. Static access, from anywhere (avoid in controllers — hard to test)
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
```

The home page is public, so `currentUser` will be `null` for visitors. Update `HomeController`:

```java
@GetMapping("/")
public String home(@AuthenticationPrincipal UserDetails currentUser, Model model) {
    model.addAttribute("appName", "Todo App");
    model.addAttribute("email", currentUser != null ? currentUser.getUsername() : null);
    return "home";
}
```

(`import org.springframework.security.core.annotation.AuthenticationPrincipal;` and `org.springframework.security.core.userdetails.UserDetails`.)

And in `home.html`, under the heading:

```html
<p th:if="${email}" class="lead">Welcome back, <strong th:text="${email}">you</strong>!</p>
<p th:unless="${email}">
    <a class="btn btn-primary" th:href="@{/register}">Get started</a>
</p>
```

From guide 05 on, **every controller method that touches data** will take `@AuthenticationPrincipal UserDetails currentUser` and pass `currentUser.getUsername()` (the email) to the service.

### 12. Hash the demo user's password properly

`{noop}` proved the delegating encoder works. Now do it properly. Inject `PasswordEncoder` into `DemoDataLoader` and use it:

```java
private final PasswordEncoder passwordEncoder;   // add to the constructor too

User demo = userRepository.save(
        new User("demo@example.com", passwordEncoder.encode("password")));
```

The loader skips seeding when a user already exists, so it won't touch the old `{noop}` row. This is a **data** reset, not a schema change, so wiping your local data is fine. Stop the app (■), **delete `data/`** (right-click → **Delete** in the Project window), and restart. Flyway rebuilds the tables from V1 and the loader reseeds. The H2 console (or IntelliJ's Database window) now shows a `{bcrypt}$2a$10$...` value in `USERS.PASSWORD_HASH`.

---

## Your turn

### Exercise 1: "registration successful" message

After registering, the user lands on `/login?registered`. Show a green "Account created — please log in." alert in that case.

<details>
<summary>Solution</summary>

```html
<div class="alert alert-success" th:if="${param.registered}">Account created — please log in.</div>
```

</details>

### Exercise 2: keep logged-in users away from `/register` and `/login`

A logged-in user who visits `/register` or `/login` should be redirected to `/`.

<details>
<summary>Hint</summary>

Use `@AuthenticationPrincipal` in `RegistrationController.showForm` and `LoginController.login`. You can return `"redirect:/"` from a GET handler too.

</details>

<details>
<summary>Solution</summary>

```java
@GetMapping("/register")
public String showForm(@AuthenticationPrincipal UserDetails currentUser, Model model) {
    if (currentUser != null) {
        return "redirect:/";
    }
    model.addAttribute("form", new RegistrationForm());
    return "register";
}
```

The same idea applies in `LoginController`.

</details>

### Exercise 3: try to break it

Work through each of these and explain the result:

1. Register with `Ada@Example.COM`, then log in with `ada@example.com`.
2. Register the same email twice.
3. Submit the registration form with everything blank.
4. In your browser's dev tools, **delete the hidden `_csrf` input** from the login form, then submit.
5. Log out, then visit <http://localhost:8080/hello>.

<details>
<summary>Expected results</summary>

1. ✅ Works: both registration and login normalise the email.
2. "An account with that email already exists" under the email field.
3. Three "… is required" messages. (Two errors on password? `@NotBlank` and `@Size` can both fire. Try it.)
4. **403 Forbidden**: the `CsrfFilter` rejected it before it got anywhere near authentication.
5. Redirect to `/login`. After you log in, Spring Security sends you **back to `/hello`**, because it saved the original request. (`defaultSuccessUrl(…, true)` overrides that; try `false` and compare.)

</details>

---

## Checkpoint

- Visiting `/hello` logged out redirects to your Bootstrap login page.
- You can register a new account, are redirected to the login page with a success message, and can log in.
- The navbar shows your email and a working **Log out** button.
- `demo@example.com` / `password` still logs in (with a BCrypt hash, after you reset `data/`).
- The H2 console still works.

```
src/main/java/com/example/todoapp/
├── security/
│   ├── AppUserDetailsService.java
│   ├── LoginController.java
│   └── SecurityConfig.java
├── user/
│   ├── EmailAlreadyRegisteredException.java
│   ├── RegistrationController.java
│   ├── RegistrationForm.java
│   ├── User.java
│   ├── UserRepository.java
│   └── UserService.java
└── … (dev, greeting, list, todo, web)
src/main/resources/templates/
├── login.html
├── register.html
└── … (home, about, greet, fragments/layout)
```

---

## Common errors

| Symptom | Cause / fix |
|---|---|
| Endless redirect / "too many redirects" on `/login` | `/login` isn't permitted. Check `permitAll()` on `formLogin` or in `requestMatchers`. The same goes for `/error`, otherwise errors redirect to login. |
| **403 Forbidden** on any form POST | Missing CSRF token. The form uses `action=` instead of `th:action=`, or you built the form by hand. |
| `There is no PasswordEncoder mapped for the id "null"` | A stored hash has no `{id}` prefix (e.g. a raw `$2a$...` or plain `password`). Store passwords via `passwordEncoder.encode(...)`, or prefix with `{bcrypt}` / `{noop}`. |
| "Invalid email or password" with the right password | The email case doesn't match (check normalisation), the form field isn't named `email`, or the password was saved unhashed (see above). |
| `An Errors/BindingResult argument is expected to be declared immediately after the model attribute argument` | Move `BindingResult` directly after the `@Valid @ModelAttribute` parameter. |
| Validation annotations are ignored | Missing `spring-boot-starter-validation`, or `@Valid` missing on the parameter. |
| `sec:` attributes are printed literally / ignored | The thymeleaf-extras-springsecurity dependency is missing or the wrong version for your Spring Security. Check with Initializr's *Explore*. |
| H2 console shows a blank page or "refused to connect" | Frame options or CSRF aren't relaxed for `/h2-console/**`. |
| `Could not resolve WebSecurityConfigurerAdapter` / `antMatchers` | You're following a pre-Security-6 tutorial. Use the `SecurityFilterChain` style above. |

---

## Recap

- Spring Security is a **filter chain** in front of Spring MVC. It handles `POST /login` and `POST /logout` itself.
- Configure it with a `SecurityFilterChain` `@Bean` and lambdas. Rules are first-match-wins, so end with `anyRequest()`.
- Your `UserDetailsService` adapts your `User` entity to Spring Security. The `PasswordEncoder` (delegating, so `{bcrypt}` by default) hashes and checks passwords.
- Bean Validation + `@Valid` + `BindingResult` + `th:field`/`th:errors` is the standard form pattern: re-render on error, redirect on success.
- Always use `th:action` so forms carry the CSRF token. Logout is a POST.
- Use `@AuthenticationPrincipal UserDetails` to get the current user in controllers.

> **Sidebar: things you may want later.**
> - **Method security:** `@EnableMethodSecurity` plus `@PreAuthorize("hasRole('ADMIN')")` on service methods, for role checks closer to the business logic.
> - **Remember-me:** `.rememberMe(...)` in the filter chain, for a long-lived login cookie.
> - **Roles and authorities:** `.requestMatchers("/admin/**").hasRole("ADMIN")`.
> - **OAuth2 login:** `spring-boot-starter-oauth2-client` and `.oauth2Login(...)` for "Log in with GitHub/Google".

**Further reading**

- [Spring Security reference: Servlet architecture](https://docs.spring.io/spring-security/reference/servlet/architecture.html)
- [Spring Security reference: Form login](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/form.html)
- [Spring Security reference: Password storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)
- [Thymeleaf + Spring Security integration](https://github.com/thymeleaf/thymeleaf-extras-springsecurity)

**Next:** [05 — CRUD: todo lists](05-crud-todo-lists.md)
