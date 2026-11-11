# 06: Multi-factor authentication

> Written for **Spring Boot 4.1.2**, **Spring Security 7.1.2** and **Java 25**. Builds on [guide 05](05-consent-claims-and-roles.md). You'll need an **authenticator app**: Microsoft Authenticator, Google Authenticator, or a password manager that shows one-time codes (1Password, Bitwarden, KeePassXC and others).

## What you'll learn

- Why a password alone isn't enough, and what counts as a second **factor**
- How **TOTP** (the 6-digit codes in authenticator apps) works, from first principles, in 80 lines of Java
- How Spring Security 7 models factors as **authorities** (`FACTOR_PASSWORD`, …), and requires several with **`AuthorizationManagerFactories.multiFactor()`**
- How to send a user who's missing a factor to the right page, with **`defaultDeniedHandlerForMissingAuthority`**
- How to **enrol** a user, and how to **upgrade** the `Authentication` in the `SecurityContext` after a second step
- Why MFA belongs to **people** and not to client applications, and the bug you get if you forget
- How the ID token's **`amr`** claim tells the shop how the user signed in

By the end, signing in to anything through Passport needs a password and a code from your phone.

---

## Concepts

### Factors

A password is **something you know**, and it's the weakest kind of proof: it can be guessed, phished, reused from another site's breach, or watched being typed. Multi-factor authentication (MFA) asks for proof of a different kind as well:

| Factor | Examples |
|---|---|
| Something you **know** | Password, PIN |
| Something you **have** | A phone with an authenticator app, a security key, a smart card |
| Something you **are** | Fingerprint, face (usually unlocking something you have) |

An attacker with Alice's password still needs her phone. That one change stops most account takeovers, which is why Entra and most organisations require it, and why an identity provider is the right place to do it. Every application behind Passport gets MFA without writing a line of code.

### TOTP

**TOTP** (time-based one-time password, [RFC 6238](https://www.rfc-editor.org/rfc/rfc6238)) is what authenticator apps do:

1. **Enrolment.** Passport makes a random **secret** (160 bits) and shows it to the user, as text or as a QR code. Their app stores it. Now both sides know the secret, and nobody else does.
2. **Every 30 seconds**, both sides compute a code from the secret and the current **time step** (the number of 30-second periods since 1970):
   - **HMAC-SHA1**(secret, time step) gives 20 bytes.
   - "Dynamic truncation" ([RFC 4226](https://www.rfc-editor.org/rfc/rfc4226)) takes 4 of them, chosen by the last byte, as a number.
   - The last 6 digits of that number are the code.
3. **At sign-in**, the user types the code from their app, and Passport computes the same and compares. Phone clocks drift, so Passport also accepts the code from the time step before and the one after.

Nothing travels between the phone and Passport after enrolment. The code proves the user has the device holding the secret.

TOTP has known weaknesses, all covered later in this guide:

- a phishing site can relay a code in real time
- a code can be used more than once within its window
- six digits can be guessed, given enough attempts

**Passkeys** (WebAuthn) are the phishing-resistant successor, and Spring Security supports them too. TOTP is simpler to build and understand, so it's what Passport does here.

### Factors in Spring Security 7

Spring Security 7 records **how** a user authenticated, as authorities. Each authentication mechanism adds a **factor authority** to the `Authentication` it creates:

- `formLogin` adds **`FACTOR_PASSWORD`**
- one-time-token login adds `FACTOR_OTT`
- WebAuthn adds `FACTOR_WEBAUTHN`, and so on

These are constants on **`FactorGrantedAuthority`**. Each also records *when* it was issued.

Requiring MFA then means requiring **two** factor authorities:

```java
AuthorizationManagerFactories.multiFactor()
        .requireFactors(FactorGrantedAuthority.PASSWORD_AUTHORITY, "FACTOR_TOTP")
        .build()
```

That builds an **`AuthorizationManagerFactory`**: a factory for authorisation rules (`authenticated()`, `hasRole(...)` and the rest) that also insist on those factors.

When a signed-in user is refused only because a factor is missing, Spring Security's `ExceptionTranslationFilter` doesn't return a plain 403. It looks up an **entry point for the missing factor**, and sends the user there. You register one per factor with `exceptionHandling(e -> e.defaultDeniedHandlerForMissingAuthority(entryPoint, "FACTOR_TOTP"))`.

Spring Security has no TOTP of its own. So Passport adds the `FACTOR_TOTP` authority itself, when a code checks out, by putting an **upgraded `Authentication`** into the `SecurityContext`. It's the same user, with one more authority.

---

## Steps

### 1. Store the secret

`db/migration/V5__add_totp_secret.sql`:

```sql
-- The user's authenticator app secret, Base32. NULL means they haven't set one up yet.
ALTER TABLE user_account ADD COLUMN totp_secret VARCHAR(32);
```

In `UserAccount`, a field, a getter, and a method to enrol:

```java
    @Column(name = "totp_secret", length = 32)
    private String totpSecret;
...

    public String getTotpSecret() {
        return totpSecret;
    }

    /** Turns on the authenticator-app second step, with this (already verified) secret. */
    public void enrolTotp(String secret) {
        this.totpSecret = secret;
    }
```

The secret is stored as text so the guide stays readable. A real IdP encrypts it at rest: unlike a password, it can't be hashed, because Passport needs the secret itself to compute codes.

### 2. TOTP in Java

Create the `mfa` package and `mfa/Totp.java`. It uses only the JDK:

```java
package com.example.idp.mfa;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * Time-based one-time passwords (TOTP, RFC 6238), as used by authenticator apps:
 * a 6-digit code made from a shared secret and the current 30-second time step.
 */
public final class Totp {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int STEP_SECONDS = 30;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** A new random 160-bit secret, Base32-encoded (32 characters), as authenticator apps expect. */
    public static String newSecret() {
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        return base32Encode(secret);
    }

    /** The link an authenticator app understands. Most apps can also take the secret typed in by hand. */
    public static String otpauthUri(String issuer, String username, String secret) {
        String label = URLEncoder.encode(issuer + ":" + username, StandardCharsets.UTF_8);
        return "otpauth://totp/" + label + "?secret=" + secret
                + "&issuer=" + URLEncoder.encode(issuer, StandardCharsets.UTF_8);
    }

    /** True if the code matches the current time step, or the one either side (clocks drift). */
    public static boolean verify(String secret, String code, Instant now) {
        if (code == null || !code.matches("\\d{6}")) {
            return false;
        }
        long step = now.getEpochSecond() / STEP_SECONDS;
        for (long candidate = step - 1; candidate <= step + 1; candidate++) {
            byte[] expected = codeFor(secret, candidate).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, code.getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    /** RFC 6238: HMAC-SHA1 of the time step, then "dynamic truncation" to 6 digits (RFC 4226). */
    static String codeFor(String secret, long step) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA1");
            hmac.init(new SecretKeySpec(base32Decode(secret), "HmacSHA1"));
            byte[] hash = hmac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return "%06d".formatted(binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base32Encode(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    private static byte[] base32Decode(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : text.toUpperCase().toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                continue; // skip spaces and padding
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
```

- **`SecureRandom`**, never `Random`: the secret must be unpredictable.
- **Base32** (letters A to Z and digits 2 to 7) is what authenticator apps expect, because it's easy to type and has no confusable characters such as `0`/`O` or `1`/`l`.
- **`codeFor`** is RFC 6238, line for line: the time step as 8 bytes, HMAC-SHA1, then the 4 bytes at the offset given by the last byte's low 4 bits, with the top bit cleared, then modulo 1,000,000.
- **`MessageDigest.isEqual`** compares in constant time, so how long the comparison takes doesn't reveal how many digits were right.
- **`otpauth://` URIs** are what QR codes contain. Passport shows the URI as text, so it needs no QR library. Most apps also let you type the secret.

This class gives the RFC's own test results. For the test secret `12345678901234567890`, the 6-digit codes at times 59, 1111111109 and 1234567890 are `287082`, `081804` and `005924`.

### 3. Checking codes, and enrolling

`mfa/MfaService.java`:

```java
package com.example.idp.mfa;

import com.example.idp.user.UserAccount;
import com.example.idp.user.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Checks one-time codes, and saves a user's authenticator secret once they've proved it works. */
@Service
public class MfaService {

    private final UserAccountRepository users;

    public MfaService(UserAccountRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public boolean isEnrolled(String username) {
        return users.findByUsername(username).orElseThrow().getTotpSecret() != null;
    }

    /**
     * True if the code is right. For a user who isn't enrolled yet, the code is checked against
     * the new secret they're setting up, and that secret is saved only if the code matches.
     */
    @Transactional
    public boolean verify(String username, String code, String secretBeingSetUp) {
        UserAccount user = users.findByUsername(username).orElseThrow();
        String secret = user.getTotpSecret() != null ? user.getTotpSecret() : secretBeingSetUp;
        if (secret == null || !Totp.verify(secret, code, Instant.now())) {
            return false;
        }
        if (user.getTotpSecret() == null) {
            user.enrolTotp(secret);
        }
        return true;
    }
}
```

A new secret is saved **only after the user proves their app has it**, by typing a correct code. Otherwise a mistyped secret would lock them out on the next sign-in.

### 4. The code page

`mfa/MfaController.java`:

```java
package com.example.idp.mfa;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** The second step of signing in: a code from an authenticator app. */
@Controller
public class MfaController {

    /** The authority that says "this user has entered a valid one-time code". */
    public static final String TOTP_FACTOR = "FACTOR_TOTP";

    private static final String SECRET_BEING_SET_UP = "mfa.secretBeingSetUp";

    private final MfaService mfa;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();
    private final RequestCache requestCache = new HttpSessionRequestCache();

    public MfaController(MfaService mfa) {
        this.mfa = mfa;
    }

    @GetMapping("/mfa")
    public String mfa(Authentication authentication, HttpSession session, Model model) {
        if (mfa.isEnrolled(authentication.getName())) {
            return "mfa";
        }
        String secret = (String) session.getAttribute(SECRET_BEING_SET_UP);
        if (secret == null) {
            secret = Totp.newSecret();
            session.setAttribute(SECRET_BEING_SET_UP, secret);
        }
        model.addAttribute("secret", secret);
        model.addAttribute("otpauthUri", Totp.otpauthUri("Passport", authentication.getName(), secret));
        return "mfa-setup";
    }

    @PostMapping("/mfa")
    public String verify(@RequestParam String code, Authentication authentication, HttpSession session,
                         HttpServletRequest request, HttpServletResponse response) {
        String secretBeingSetUp = (String) session.getAttribute(SECRET_BEING_SET_UP);
        if (!mfa.verify(authentication.getName(), code.strip(), secretBeingSetUp)) {
            return "redirect:/mfa?error";
        }
        session.removeAttribute(SECRET_BEING_SET_UP);
        saveAuthentication(withTotpFactor(authentication), request, response);

        // Carry on to wherever the user was going before they were asked for a code
        SavedRequest saved = requestCache.getRequest(request, response);
        return "redirect:" + (saved != null ? saved.getRedirectUrl() : "/");
    }

    /** The same user, with one more authority: FACTOR_TOTP. */
    private Authentication withTotpFactor(Authentication current) {
        List<GrantedAuthority> authorities = new ArrayList<>(current.getAuthorities());
        authorities.add(FactorGrantedAuthority.withAuthority(TOTP_FACTOR).issuedAt(Instant.now()).build());
        UsernamePasswordAuthenticationToken upgraded =
                UsernamePasswordAuthenticationToken.authenticated(current.getPrincipal(), null, authorities);
        upgraded.setDetails(current.getDetails());
        return upgraded;
    }

    /** Makes the new Authentication current, for this request and (via the session) every later one. */
    private void saveAuthentication(Authentication authentication,
                                    HttpServletRequest request, HttpServletResponse response) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }
}
```

This controller does by hand what Spring Security's login filters do for you, so it shows the moving parts:

- **`GET /mfa`** shows the code form to an enrolled user. Anyone else gets the **set-up** page, with a new secret kept in their session until they confirm it.
- **`withTotpFactor`**: an `Authentication` can't be changed, so Passport builds a new one. It has the same principal, the same authorities (`ROLE_…` and `FACTOR_PASSWORD`), and one more: a `FactorGrantedAuthority` for `FACTOR_TOTP`, stamped with the time. `UsernamePasswordAuthenticationToken.authenticated(...)` makes an `Authentication` that's already authenticated. The credentials are `null`, because Passport has no reason to keep the password.
- **`saveAuthentication`** is the key Spring Security lesson. The `SecurityContextHolder` holds the context for **this request**. The **`SecurityContextRepository`** (here, the HTTP session) holds it for **later** ones. Since Spring Security 6, changing the holder alone isn't saved automatically, so you save it explicitly. Forget that line, and the user is asked for a code again on the next page.
- **`RequestCache`** is where `ExceptionTranslationFilter` saved the request that needed the code: usually the shop's authorisation request. Redirecting to it continues the sign-in. If there's none, for example after going straight to `/mfa`, go home.

`templates/mfa.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Passport: enter your code</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css">
</head>
<body class="bg-light">
<main class="container" style="max-width: 400px">
    <h1 class="h3 mt-5 mb-1">Passport</h1>
    <p class="text-secondary mb-4">Enter the 6-digit code from your authenticator app</p>

    <div class="alert alert-danger" th:if="${param.error}">That code isn't right. Try the current one.</div>

    <form th:action="@{/mfa}" method="post">
        <div class="mb-3">
            <label for="code" class="form-label">Code</label>
            <input id="code" name="code" class="form-control" inputmode="numeric" autocomplete="one-time-code"
                   pattern="[0-9]{6}" required autofocus>
        </div>
        <button type="submit" class="btn btn-primary w-100">Verify</button>
    </form>
</main>
</body>
</html>
```

`templates/mfa-setup.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Passport: set up your authenticator app</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css">
</head>
<body class="bg-light">
<main class="container" style="max-width: 480px">
    <h1 class="h3 mt-5 mb-1">Passport</h1>
    <p class="text-secondary mb-4">Passport now needs a second step when you sign in</p>

    <ol>
        <li class="mb-2">In your authenticator app (Microsoft Authenticator, Google Authenticator, 1Password, …),
            add an account and choose to <strong>enter a setup key</strong>.</li>
        <li class="mb-2">Enter this key, with time-based codes:
            <code class="d-block fs-5 my-2" th:text="${secret}">JBSWY3DPEHPK3PXP</code></li>
        <li>Type the 6-digit code the app shows.</li>
    </ol>
    <p class="small text-secondary">Or, if your app takes a link: <code th:text="${otpauthUri}">otpauth://…</code></p>

    <div class="alert alert-danger" th:if="${param.error}">That code isn't right. Check the key, and try the current code.</div>

    <form th:action="@{/mfa}" method="post">
        <div class="mb-3">
            <label for="code" class="form-label">Code</label>
            <input id="code" name="code" class="form-control" inputmode="numeric" autocomplete="one-time-code"
                   pattern="[0-9]{6}" required autofocus>
        </div>
        <button type="submit" class="btn btn-primary w-100">Turn on</button>
    </form>
</main>
</body>
</html>
```

`autocomplete="one-time-code"` lets phones offer a code from a text message or the app.

### 5. Require both factors

Now the rules. In `security/SecurityConfig.java`, add the imports:

```java
import com.example.idp.mfa.MfaController;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagerFactories;
import org.springframework.security.authorization.AuthorizationManagerFactory;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
```

a constant at the top of the class:

```java
@Configuration
public class SecurityConfig {

    /** Rules that need two factors: a password and a one-time code. */
    private static final AuthorizationManagerFactory<RequestAuthorizationContext> TWO_FACTORS =
            AuthorizationManagerFactories.<RequestAuthorizationContext>multiFactor()
                    .requireFactors(FactorGrantedAuthority.PASSWORD_AUTHORITY, MfaController.TOTP_FACTOR)
                    .build();
```

and change the two chains:

```java
    /** The OAuth2 and OpenID Connect protocol endpoints: /oauth2/authorize, /oauth2/token, /userinfo, ... */
    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .oauth2AuthorizationServer(authorizationServer -> {
                    http.securityMatcher(authorizationServer.getEndpointsMatcher());
                    authorizationServer
                            .authorizationEndpoint(endpoint -> endpoint.consentPage("/oauth2/consent"))
                            .oidc(Customizer.withDefaults());
                })
                .authorizeHttpRequests(authorize -> authorize
                        // A person signing in to a client: password and code
                        .requestMatchers("/oauth2/authorize").access(TWO_FACTORS.authenticated())
                        // Everything else here is called by the client applications themselves
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML))
                        .defaultDeniedHandlerForMissingAuthority(
                                new LoginUrlAuthenticationEntryPoint("/mfa"), MfaController.TOTP_FACTOR));
        return http.build();
    }

    /** Everything else: the login page, and Passport's own pages. */
    @Bean
    @Order(2)
    SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        // The code page needs a password, but (of course) not a code yet
                        .requestMatchers("/mfa").access(
                                AuthorityAuthorizationManager.hasAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY))
                        .anyRequest().access(TWO_FACTORS.authenticated()))
                .formLogin(form -> form.loginPage("/login").permitAll())
                .exceptionHandling(exceptions -> exceptions.defaultDeniedHandlerForMissingAuthority(
                        new LoginUrlAuthenticationEntryPoint("/mfa"), MfaController.TOTP_FACTOR));
        return http.build();
    }
```

- **`TWO_FACTORS.authenticated()`** is "authenticated, *and* holding `FACTOR_PASSWORD` *and* `FACTOR_TOTP`". `.access(...)` uses an authorisation manager you built yourself, in place of the built-in `authenticated()`.
- **`/oauth2/authorize`** needs both factors. That's the moment a *person* signs in to a client.
- **Every other protocol endpoint** keeps plain `authenticated()`. The token endpoint, `/userinfo` and the rest are called by **client applications**, whose `Authentication` is the client itself or a bearer token. They have no password and no phone. See the sidebar.
- **`/mfa`** needs only the password factor. Otherwise you'd need a code to reach the page where you type the code.
- **Passport's own pages** (the home page, consent) need both.
- **`defaultDeniedHandlerForMissingAuthority`**: when a signed-in user is refused for lacking `FACTOR_TOTP`, send them to `/mfa`. It's registered in both chains, because either can be the one that refuses.

> **Sidebar: the MFA rule that broke sign-in for everyone.** Spring Security can also apply a factor policy **globally**: declare the `AuthorizationManagerFactory` as a `@Bean`, and every `authenticated()` rule in every chain uses it. That was the first version here. Users got through the password and the code, and then the shop showed "Invalid credentials", with this in its debug log:
>
> ```
> [invalid_token_response] Empty OAuth 2.0 Access Token Response
> ```
>
> The token endpoint now wanted `FACTOR_PASSWORD` and `FACTOR_TOTP` from **the shop**, which had authenticated with its client secret. Look back at guide 01's filter list: in the protocol chain, `AuthorizationFilter` comes **before** `OAuth2TokenEndpointFilter`, so the rule ran first, refused the shop, and the token request got an empty error response. Rules about people must be applied only where people are.

### 6. Show the factors

Make Passport's home page list the user's authorities, so you can see the factors. In `templates/home.html`, after the "signed in" paragraph:

```html
    <p>Your authorities:</p>
    <ul>
        <li th:each="authority : ${#authentication.authorities}" th:text="${authority.authority}">ROLE_USER</li>
    </ul>
```

`#authentication` is the current `Authentication`, from the Spring Security dialect.

### 7. Tell the shop: the `amr` claim

The shop might want to know *how* Alice signed in: perhaps it lets her browse with a password alone, but needs MFA to change a delivery address. OpenID Connect has a claim for that. **`amr`** (authentication methods references, [RFC 8176](https://www.rfc-editor.org/rfc/rfc8176)) is a list of standard codes:

- `pwd` for a password
- `otp` for a one-time password
- `mfa` when more than one factor was used

Entra sends it too. Add it to `IdTokenClaims`, at the end of the `claims(...)` lambda:

```java
            // How the user proved who they are (RFC 8176): password, one-time code, so multi-factor
            boolean usedCode = context.getPrincipal().getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch(MfaController.TOTP_FACTOR::equals);
            claims.put("amr", usedCode ? new ArrayList<>(List.of("pwd", "otp", "mfa")) : new ArrayList<>(List.of("pwd")));
```

with the imports `com.example.idp.mfa.MfaController` and `org.springframework.security.core.GrantedAuthority`. `context.getPrincipal()` is the upgraded `Authentication` from step 4, factors and all. `ArrayList` again, for the reason in guide 05.

### 8. Try it

Restart Passport (Flyway applies `V5`). In a private window, go to the shop's **My account**, and sign in as **alice** / `password`. Instead of the shop, you get Passport's **set-up page**:

1. In your authenticator app, add an account by typing the key shown. Most apps call this **Enter a setup key**, and want it as a time-based code.
2. Type the 6-digit code the app shows, and click **Turn on**.

You're back at the shop. Its account page's claims table now has `amr` set to `[pwd, otp, mfa]`.

Look at the developer tools' Network tab. After the password, the authorisation request was answered:

```
302 Location: http://login.idp.test:9000/mfa?factor.type=totp&factor.reason=missing
```

That's the missing-factor handler: Spring Security names the factor (`totp`, from `FACTOR_TOTP`) and the reason. After the code, `POST /mfa` redirected to the saved authorisation request, and the sign-in carried on as usual.

Now, in a **new** private window, go straight to `http://login.idp.test:9000/` and sign in as alice again:

- You get the **"Enter the 6-digit code"** page, not the set-up page: Alice is enrolled.
- A wrong code (`000000`) gives "That code isn't right."
- The right one shows Passport's home page, listing `ROLE_ADMIN`, `ROLE_USER`, `FACTOR_PASSWORD` and `FACTOR_TOTP`.

Sign in as **bob** in another private window: he's asked to set up an app, because everyone must use MFA.

The token endpoint is unaffected, as it should be. A request with a wrong client secret still gets `{"error":"invalid_client"}`, not a redirect to `/mfa`.

> **Sidebar: what this MFA doesn't do yet.** Real identity providers add protections Passport leaves out:
> - **Rate limiting:** six digits are 1,000,000 possibilities, and three are valid at once. Lock out or slow down after a few wrong codes.
> - **Replay protection:** remember the last time step used per user, and refuse a code from it again.
> - **Safer enrolment:** here, anyone with a not-yet-enrolled user's password can enrol *their own* phone. Real systems enrol through a verified channel, or right after account creation, and offer **recovery codes** for lost phones.
> - **Phishing resistance:** a fake login page can ask for the code and relay it within 30 seconds. Passkeys (WebAuthn) can't be relayed, because they're bound to the real site's domain. Spring Security supports them, and its MFA works the same way, with `FACTOR_WEBAUTHN`.

---

## Your turn

### Exercise 1: show MFA in the shop

On the shop's account page, show "Signed in with multi-factor authentication" when the ID token's `amr` claim contains `mfa`, and "Signed in with a password only" otherwise.

<details>
<summary>Hint</summary>

Guide 05's roles mapper read a list claim with `getClaimAsStringList`. Thymeleaf can call it on `user.idToken`.

</details>

<details>
<summary>Solution</summary>

In the shop's `account.html`, after the greeting:

```html
<p th:if="${user.idToken.getClaimAsStringList('amr').contains('mfa')}">Signed in with multi-factor authentication.</p>
<p th:unless="${user.idToken.getClaimAsStringList('amr').contains('mfa')}">Signed in with a password only.</p>
```

Since every Passport sign-in now needs a code, you'll see the first message. An application could make the same check in a rule or a controller before allowing something sensitive, and send the user back to Passport if it's missing.

</details>

### Exercise 2: Bob lost his phone

Bob's phone is gone. Reset his authenticator, so he's asked to set up a new one the next time he signs in.

<details>
<summary>Hint</summary>

Enrolment is one column (step 1). Guide 04 showed how to change the database directly.

</details>

<details>
<summary>Solution</summary>

Stop Passport, then:

```bash
java -cp ~/.m2/repository/com/h2database/h2/2.4.240/h2-2.4.240.jar org.h2.tools.Shell -url jdbc:h2:file:./data/idp -user sa -sql "UPDATE user_account SET totp_secret = NULL WHERE username = 'bob'"
```

Start Passport, and sign in as bob in a private window: the set-up page appears. Think about what a help desk must do *before* running that statement. Anyone who can talk the help desk into resetting a victim's MFA can then enrol their own phone with the victim's password. Attackers do exactly this, which is why real resets need strong identity checks.

</details>

---

## Checkpoint

- Signing in to the shop needs a password, then a code (or, the first time, setting up an authenticator app).
- The shop's ID token has `"amr": ["pwd", "otp", "mfa"]`.
- Passport's home page lists `FACTOR_PASSWORD` and `FACTOR_TOTP`.
- The token endpoint still works for the shop.

New and changed files in Passport:

```
idp/src/main/
├── java/com/example/idp/
│   ├── mfa/
│   │   ├── MfaController.java
│   │   ├── MfaService.java
│   │   └── Totp.java
│   ├── security/IdTokenClaims.java     (amr)
│   ├── security/SecurityConfig.java    (two factors)
│   └── user/UserAccount.java           (totpSecret)
└── resources/
    ├── db/migration/V5__add_totp_secret.sql
    └── templates/
        ├── home.html                   (authorities)
        ├── mfa.html
        └── mfa-setup.html
```

---

## Common errors

**Every code is "not right"**
Check the phone's clock is set automatically: TOTP depends on both clocks agreeing to within about 30 seconds. Also check the app is set to **time-based** codes, not counter-based (HOTP), and that you typed the key from the *current* set-up page. Reloading the page in a new session makes a new key.

**After the code, you're asked for it again on the next page**
The upgraded `Authentication` wasn't saved: check `securityContextRepository.saveContext(...)` in `MfaController`.

**Redirect loop between `/mfa` and `/mfa`**
`/mfa` itself needs both factors. Check the `requestMatchers("/mfa").access(AuthorityAuthorizationManager.hasAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY))` rule comes before `anyRequest()`.

**The shop says "Invalid credentials", with `[invalid_token_response] Empty OAuth 2.0 Access Token Response`**
The two-factor rule is applied to the token endpoint. Don't declare the `AuthorizationManagerFactory` as a `@Bean`, and keep `anyRequest().authenticated()` in the protocol chain (the sidebar in step 5).

**A `403` page instead of the code page**
`defaultDeniedHandlerForMissingAuthority(...)` is missing from the chain that refused the request. It needs to be in both.

---

## Recap

- **MFA** combines factors of different kinds: something you know, have or are. An identity provider is the right place for it: every application gets it at once.
- **TOTP** is HMAC-SHA1 of a shared secret and the 30-second time step, truncated to six digits. Accept one step either side, compare in constant time, and save the secret only once the user proves it works.
- Spring Security 7 records each way of authenticating as a **factor authority** (`FACTOR_PASSWORD`, …). **`AuthorizationManagerFactories.multiFactor().requireFactors(...)`** builds rules that need several, and **`defaultDeniedHandlerForMissingAuthority`** sends users to the page for a missing one.
- A custom second step **upgrades the `Authentication`** with an extra `FactorGrantedAuthority`, and must **save** the new `SecurityContext` through the `SecurityContextRepository`.
- Apply MFA to **people** (`/oauth2/authorize` and the IdP's own pages), never to client authentication at the token endpoint.
- The ID token's **`amr`** claim tells applications how the user signed in.

### Interview angle

<details>
<summary>"How does an authenticator app's 6-digit code work?"</summary>

It's TOTP, RFC 6238. At enrolment the server generates a random secret and shares it with the app, usually as a QR code with an `otpauth://` URI. Both sides then compute HMAC-SHA1 of the secret and the current 30-second time step, apply RFC 4226's dynamic truncation, and keep 6 digits. The server accepts a small window either side for clock drift, compares in constant time, and should rate-limit attempts and refuse reuse of a time step. Nothing is sent from the phone. The code proves possession of the secret.

</details>

<details>
<summary>"Is TOTP phishing-resistant?"</summary>

No. A phishing proxy can collect the password and the current code and replay them to the real site within the 30-second window, which is a common attack against TOTP and push notifications. Phishing-resistant authenticators, such as FIDO2/WebAuthn passkeys and certificate-based authentication, bind the cryptographic response to the real site's origin, so a lookalike domain can't use it. TOTP is still far better than a password alone, because it stops credential stuffing and password reuse.

</details>

<details>
<summary>"How does an application know the user did MFA?"</summary>

Through claims in the ID token: `amr` (authentication methods references, RFC 8176, such as `pwd`, `otp`, `mfa`) and/or `acr` (authentication context class reference, a level of assurance). An application that needs MFA for a sensitive action checks those claims, and if they're missing or too old (`auth_time`), sends the user back to the IdP, optionally asking for a stronger level (`acr_values`, or `max_age` to force fresh authentication).

</details>

### Further reading

- [RFC 6238: TOTP](https://www.rfc-editor.org/rfc/rfc6238) and [RFC 4226: HOTP](https://www.rfc-editor.org/rfc/rfc4226)
- [Spring Security reference: Multi-Factor Authentication](https://docs.spring.io/spring-security/reference/servlet/authentication/mfa.html)
- [RFC 8176: Authentication Method Reference Values](https://www.rfc-editor.org/rfc/rfc8176)
- [NIST SP 800-63B: Authentication and Lifecycle Management](https://pages.nist.gov/800-63-4/sp800-63b.html)

**Next:** [07: Logout, and single sign-on](07-logout-and-single-sign-on.md). Signing out properly, and a second website.
