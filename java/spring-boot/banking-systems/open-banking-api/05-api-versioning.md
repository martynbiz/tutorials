# 05: API Versioning

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- Why an API that other companies depend on can't just *change*, and which changes count as **breaking**
- The four common ways to put a version in a request (path, header, query parameter, media type) and their trade-offs
- How UK Open Banking versions its APIs
- **Spring Framework 7's first-class API versioning**: the `version` attribute on mappings, baseline versions such as `"2+"`, supported versions, and `ApiVersionConfigurer`
- Two real gotchas when versioning by path segment, and how to fix them
- Announcing the end of a version with the **`Deprecation`** and **`Sunset`** headers
- Making Swagger UI understand a versioned path

By the end, the API serves **v1 and v2 side by side**. v2 changes how balances are represented, v1 clients keep working untouched, and every v1 response tells its caller when v1 will be switched off.

---

## Concepts

### How it works in a real bank

Your own front end and your own API ship together, so you can change both in one release. An Open Banking API is different: **hundreds of TPPs** have built software against it. You don't control their release schedules, and some of them won't touch their integration for a year. If you rename a field on a Tuesday, someone's budgeting app breaks on Tuesday, and some customers can't see their money. In a regulated API, that's also a compliance problem.

So once an API is published, its contract is **frozen**. Changes come in two kinds:

| Non-breaking (safe in place) | Breaking (needs a new version) |
|---|---|
| Adding a new endpoint | Removing or renaming an endpoint or field |
| Adding an optional request field | Making an optional field required |
| Adding a field to a response | Changing a field's type, format or **meaning** |
| Adding a new enum value… *if clients were told to expect them* | Changing status codes or error shapes clients rely on |

"Changing a field's meaning" is the sneaky one. The JSON looks the same, the tests still pass, and the clients are wrong.

### This guide's breaking change

In v1, a balance is a **signed** amount. Bob is overdrawn by £80:

```json
{ "type": "InterimBooked", "amount": { "amount": "-80.00", "currency": "GBP" } }
```

The UK standard represents a balance differently: a **positive** amount plus a `CreditDebitIndicator`. An overdrawn balance is a *debit* balance:

```json
{ "type": "InterimBooked", "creditDebitIndicator": "Debit", "amount": { "amount": "80.00", "currency": "GBP" } }
```

You want v1 to match the standard, but you can't change it: a v1 client that reads `amount` would now see `80.00` and think Bob has £80 **in** his account. Adding `creditDebitIndicator` alone isn't enough, because old clients ignore it. This is a change of meaning, so it gets a **v2**.

### Where to put the version

| Strategy | Example | For | Against |
|---|---|---|---|
| **Path segment** | `/open-banking/v2/aisp/accounts` | Visible in every log line, link and bookmark. Easy to route at a gateway. Easy to try in a browser or with curl. | A version is arguably not part of the resource's identity. Every URL changes, even for endpoints that didn't. |
| **Header** | `API-Version: 2` | URLs stay stable. | Invisible in logs and links unless you log headers. Easy to forget, so a sensible default is needed. |
| **Query parameter** | `?version=2` | Simple, visible. | Mixes with real query parameters. Caches may treat versions as different resources (or the same one). |
| **Media type** | `Accept: application/vnd.bank+json;version=2` | Purist HTTP content negotiation. | Hardest for clients to get right, awkward to test by hand. |

None is wrong. What matters is choosing **one** and being consistent. **UK Open Banking uses the path**, with a major and minor number: `/open-banking/v3.1/aisp/accounts`. Banks typically run more than one version at once for a while, so TPPs can migrate on their own schedule.

This tutorial follows the path style with whole numbers: `v1` and `v2`.

### Versioning support in Spring Framework 7

Before Spring Framework 7, path versioning meant duplicating controllers (`AccountControllerV1`, `AccountControllerV2`) or hard-coding `/v1/` and `/v2/` into every mapping. Spring Framework 7 (Spring Boot 4) builds versioning into Spring MVC:

1. **You tell Spring where to find the version**: a path segment, a header, a query parameter or a media type parameter.
2. **Mappings declare which versions they serve** with a new `version` attribute:
   - `@GetMapping(path = "/balances", version = "1")`: exactly version 1.
   - `@GetMapping(path = "/balances", version = "2+")`: a **baseline**: version 2 *and every later version*, until something newer overrides it.
   - No `version` attribute: the mapping serves **every** version. Endpoints that haven't changed carry forward automatically.
3. **Spring parses the version** (by default as semantic versioning: `v2` → `2.0.0`, ignoring a leading non-digit prefix), checks it against the **supported** versions, and routes the request to the best matching mapping.
4. **Unsupported versions are rejected** with `400 Bad Request` before reaching your code.

### Deprecation and sunset

Running old versions forever is expensive: every bug fix and security patch has to work in all of them. So versions are **deprecated** (still working, but don't build on it) and later **sunset** (switched off). Two IETF standards let the API tell clients this **in every response**, where automated tooling can spot it:

- **`Deprecation`** ([RFC 9745](https://www.rfc-editor.org/rfc/rfc9745)): when the version was deprecated, as a structured-field date (`@` and a Unix timestamp).
- **`Sunset`** ([RFC 8594](https://www.rfc-editor.org/rfc/rfc8594)): the date after which it may stop working.
- **`Link`** with `rel="deprecation"`: where to read the migration guide.

These headers complement, not replace, telling TPPs directly. A bank will also email them, publish notices on its developer portal, and agree timelines with its regulator and the standards body. But a header that a TPP's monitoring can alert on is hard to miss.

---

## Steps

### 1. A `{version}` placeholder, and the obvious configuration

In `AccountController` and `ConsentController`, replace the hard-coded `v1` in the class-level mapping with a placeholder:

```java
@RestController
@RequestMapping("/open-banking/{version}/aisp")
public class AccountController {
```

```java
@RestController
@RequestMapping("/open-banking/{version}/aisp/account-access-consents")
public class ConsentController {
```

The methods don't declare a `@PathVariable String version`. They don't need the value: Spring will use it for routing.

Spring Boot 4 can switch versioning on with properties. The version is the segment at index 1 (`/open-banking` is index 0, `v1` is index 1). Add to `application.properties`:

```properties
# API versioning: the version is path segment 1, as in /open-banking/v1/aisp/...
spring.mvc.apiversion.use.path-segment=1
```

Run the tests. Most of them fail, and they fail in **two** different ways:

```
expected: "Account acc-2001 does not belong to the customer"
 but was: "Invalid API version: 'account-access-consents'."
```

```
but was: "Invalid API version: '1.0.0'."
```

### 2. Two gotchas, fixed

**Gotcha 1: every path is now "versioned".** The path-segment resolver applies to **every** request Spring MVC handles. For `/internal/account-access-consents/…/authorise`, segment 1 is `account-access-consents`, which isn't a version, so the request is rejected before it reaches the controller. The same would happen to `/dev/tokens` and to anything else that isn't under `/open-banking`.

**Gotcha 2: version 1 isn't "supported".** By default Spring only treats as supported the versions that appear in some mapping's `version` attribute. No mapping declares one yet, so the list is empty and even `v1` is refused. The Spring Javadoc warns about exactly this: the initial version, if no mapping declares it, may need declaring as a supported version.

Neither can be fixed with properties alone, because the first needs a rule about *which paths* are versioned. Delete the property you just added, and configure versioning in Java instead. Create `api/ApiVersioningConfig.java`:

```java
package com.example.openbanking.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ApiVersioningConfig implements WebMvcConfigurer {

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer
                // /open-banking/v1/aisp/... -> segment 1 holds the version. Other paths aren't versioned.
                .usePathSegment(1, path -> path.value().startsWith("/open-banking/"))
                .setVersionRequired(false)
                .addSupportedVersions("1");
    }
}
```

- **`usePathSegment(1, predicate)`** only reads a version from paths the predicate accepts. For any other path the version is `null`. (This two-argument form was added in Spring Framework 7.0.6.)
- **`setVersionRequired(false)`**: once a resolver is configured, Spring requires a version by default, so a `null` version would be rejected with a "missing API version" error. Every `/open-banking/` path always has one anyway, so this only affects the unversioned paths.
- **`addSupportedVersions("1")`** declares v1 explicitly.

`WebMvcConfigurer` is Spring MVC's standard extension point: Spring Boot applies its own configuration and then yours. Don't combine `spring.mvc.apiversion.use.path-segment` with `usePathSegment(...)` in Java: you'd register two resolvers.

Run the tests: all 54 green again. The API behaves exactly as before, but the version is now a first-class concept rather than a string in a URL.

### 3. Balances, version 2

In `AccountController`, give the existing balances method a version, and add a second method for version 2 onwards:

```java
    /** Version 1: a signed amount. Overdrawn is a negative number. */
    @GetMapping(path = "/accounts/{accountId}/balances", version = "1")
    public ObResponse<BalancesData> balances(@AuthenticationPrincipal Jwt token, @PathVariable String accountId) {
        consentGuard.require(token, accountId, READ_BALANCES);
        Instant now = Instant.now();
        return ObResponse.of(new BalancesData(accountService.balances(accountId).stream()
                .map(b -> new BalanceResponse(accountId, b.type(), b.amount(), now))
                .toList()));
    }

    /** Version 2 onwards: a positive amount plus a Credit/Debit indicator, as in the UK standard. */
    @GetMapping(path = "/accounts/{accountId}/balances", version = "2+")
    public ObResponse<BalancesDataV2> balancesV2(@AuthenticationPrincipal Jwt token,
                                                 @PathVariable String accountId) {
        consentGuard.require(token, accountId, READ_BALANCES);
        Instant now = Instant.now();
        return ObResponse.of(new BalancesDataV2(accountService.balances(accountId).stream()
                .map(b -> BalanceResponseV2.from(accountId, b, now))
                .toList()));
    }
```

And the v2 response records, next to the v1 ones:

```java
    public record BalancesDataV2(List<BalanceResponseV2> balance) {
    }

    public record BalanceResponseV2(String accountId, BalanceType type, CreditDebit creditDebitIndicator,
                                    Money amount, Instant dateTime) {

        static BalanceResponseV2 from(String accountId, Balance balance, Instant dateTime) {
            Money amount = balance.amount();
            // Zero or more is in credit. Below zero the customer owes the bank: a debit balance.
            return amount.isNegative()
                    ? new BalanceResponseV2(accountId, balance.type(), CreditDebit.DEBIT, amount.negate(), dateTime)
                    : new BalanceResponseV2(accountId, balance.type(), CreditDebit.CREDIT, amount, dateTime);
        }
    }
```

Things to notice:

- **Same path, two methods.** The version is part of the mapping now, so Spring can tell them apart.
- **Why `version = "1"` and not nothing?** Try deleting it: the tests still pass. A mapping without a version serves every version, but Spring prefers the more specific `"2+"` mapping for v2, so the unversioned method only ends up handling v1. It works, but it hides your intent. Saying `"1"` documents that this shape belongs to v1 and nothing later.
- **`"2+"` is a baseline.** When v3 arrives, balances carry on serving the v2 shape without anyone touching this method, unless v3 changes balances again.
- **Everything else in the controller has no `version`**, so `/accounts` and `/transactions` serve v1 and v2 from the same code. Only what changed is duplicated.
- **The domain didn't change.** `Balance`, `AccountService` and the guard are shared. Versioning is a concern of the API layer: the shapes at the edge.
- **Zero is shown as `Credit`.** That's this tutorial's choice for a zero balance. Whatever a bank decides, it must document it, because clients will rely on it.

Make v2 a supported version in `ApiVersioningConfig`:

```java
                .addSupportedVersions("1", "2")
```

(With `"2+"` in a mapping, Spring would detect version 2 by itself. Listing both explicitly keeps all supported versions in one place, which is easier to read and review.)

### 4. Deprecate v1

Update `ApiVersioningConfig` to its final form:

```java
package com.example.openbanking.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.accept.StandardApiVersionDeprecationHandler;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

@Configuration
public class ApiVersioningConfig implements WebMvcConfigurer {

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        StandardApiVersionDeprecationHandler deprecations = new StandardApiVersionDeprecationHandler();
        deprecations.configureVersion("1")
                .setDeprecationDate(ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.UTC))
                .setDeprecationLink(URI.create("https://developer.example-bank.test/open-banking/v1-to-v2"))
                .setSunsetDate(ZonedDateTime.of(2027, 5, 1, 0, 0, 0, 0, ZoneOffset.UTC));

        configurer
                // /open-banking/v1/aisp/... -> segment 1 holds the version. Other paths aren't versioned.
                .usePathSegment(1, path -> path.value().startsWith("/open-banking/"))
                .setVersionRequired(false)
                .addSupportedVersions("1", "2")
                .setDeprecationHandler(deprecations);
    }
}
```

`StandardApiVersionDeprecationHandler` is Spring's implementation of RFC 9745 and RFC 8594. `configureVersion("1")` returns a spec where you set the dates and links. Spring then adds the headers to **every** response for that version, whichever controller handles it. The dates and URL are made up for this tutorial.

### 5. Try it

Restart, and fetch Bob's balances both ways (with a consent-bound token for a consent Bob authorised for `acc-2001`, created as in guide 04). Version 1:

```bash
curl -i localhost:8080/open-banking/v1/aisp/accounts/acc-2001/balances -H "Authorization: Bearer $DATA"
```

```
HTTP/1.1 200
x-fapi-interaction-id: 84f315f3-fbeb-45e5-8d61-de348cfc1922
Deprecation: @1793491200
Link: <https://developer.example-bank.test/open-banking/v1-to-v2>; rel="deprecation"; type="text/html"
Sunset: Sat, 1 May 2027 00:00:00 GMT
Content-Type: application/json

{"data":{"balance":[{"accountId":"acc-2001","type":"InterimBooked","amount":{"amount":"-80.00","currency":"GBP"},"dateTime":"2026-11-06T23:26:37.306708884Z"},{"accountId":"acc-2001","type":"InterimAvailable","amount":{"amount":"-85.00","currency":"GBP"},"dateTime":"2026-11-06T23:26:37.306708884Z"}]},"links":{"self":"http://localhost:8080/open-banking/v1/aisp/accounts/acc-2001/balances"},"meta":{"totalPages":1}}
```

`@1793491200` is 1 November 2026, 00:00 UTC, as a Unix timestamp: RFC 9745's format. `Sunset` uses the ordinary HTTP date format. Version 2:

```
HTTP/1.1 200
x-fapi-interaction-id: 7b7f543c-c9c4-4009-9f14-a70264a9cd87
Content-Type: application/json

{"data":{"balance":[{"accountId":"acc-2001","type":"InterimBooked","creditDebitIndicator":"Debit","amount":{"amount":"80.00","currency":"GBP"},"dateTime":"2026-11-06T23:26:37.449575427Z"},{"accountId":"acc-2001","type":"InterimAvailable","creditDebitIndicator":"Debit","amount":{"amount":"85.00","currency":"GBP"},"dateTime":"2026-11-06T23:26:37.449575427Z"}]},"links":{"self":"http://localhost:8080/open-banking/v2/aisp/accounts/acc-2001/balances"},"meta":{"totalPages":1}}
```

No deprecation headers, and an overdraft is now a positive `Debit` balance. Versions Spring doesn't support are refused before any of your code runs:

```json
{"detail":"Invalid API version: '3.0.0'.","instance":"/open-banking/v3/aisp/accounts","status":400,"title":"Bad Request"}
```

The same happens for `v1.1` (`'1.1.0'`) and `latest` (`'latest'`). Notice the version in the message is **parsed**: `v3` became `3.0.0`.

Add the v2 calls to `requests.http`:

```http
### Balances, v1 (deprecated: look at the response headers)
GET http://localhost:8080/open-banking/v1/aisp/accounts/acc-1001/balances
Authorization: Bearer {{dataToken}}

### Balances, v2 (credit/debit indicator)
GET http://localhost:8080/open-banking/v2/aisp/accounts/acc-1001/balances
Authorization: Bearer {{dataToken}}

### Accounts work in both versions
GET http://localhost:8080/open-banking/v2/aisp/accounts
Authorization: Bearer {{dataToken}}

### Unsupported version (400)
GET http://localhost:8080/open-banking/v3/aisp/accounts
Authorization: Bearer {{dataToken}}
```

### 6. Versioning tests

`src/test/java/com/example/openbanking/api/ApiVersioningTest.java`:

```java
package com.example.openbanking.api;

import com.example.openbanking.OpenBankingIntegrationTest;
import com.example.openbanking.TestConsents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.util.List;

import static com.example.openbanking.TestTokens.consentBound;
import static com.example.openbanking.consent.Permission.READ_ACCOUNTS_BASIC;
import static com.example.openbanking.consent.Permission.READ_BALANCES;
import static org.assertj.core.api.Assertions.assertThat;

@OpenBankingIntegrationTest
class ApiVersioningTest {

    @Autowired MockMvcTester mvc;
    @Autowired TestConsents consents;

    String consentId;

    @BeforeEach
    void bobSharesHisOverdrawnAccount() {
        consentId = consents.authorised(List.of("acc-2001"), READ_ACCOUNTS_BASIC, READ_BALANCES);
    }

    @Test
    void version1ShowsAnOverdraftAsANegativeAmount() {
        var response = mvc.get().uri("/open-banking/v1/aisp/accounts/acc-2001/balances").with(consentBound(consentId));

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.data.balance[0].amount.amount").isEqualTo("-80.00");
        assertThat(response).bodyJson().doesNotHavePath("$.data.balance[0].creditDebitIndicator");
    }

    @Test
    void version2ShowsAnOverdraftAsADebitBalance() {
        var response = mvc.get().uri("/open-banking/v2/aisp/accounts/acc-2001/balances").with(consentBound(consentId));

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.data.balance[0].amount.amount").isEqualTo("80.00");
        assertThat(response).bodyJson().extractingPath("$.data.balance[0].creditDebitIndicator").isEqualTo("Debit");
    }

    @Test
    void unchangedEndpointsServeBothVersions() {
        assertThat(mvc.get().uri("/open-banking/v1/aisp/accounts").with(consentBound(consentId))).hasStatusOk();
        assertThat(mvc.get().uri("/open-banking/v2/aisp/accounts").with(consentBound(consentId))).hasStatusOk();
    }

    @Test
    void anUnsupportedVersionIs400() {
        var response = mvc.get().uri("/open-banking/v3/aisp/accounts").with(consentBound(consentId));

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.detail").isEqualTo("Invalid API version: '3.0.0'.");
    }

    @Test
    void version1AnnouncesItsDeprecationAndSunset() {
        var response = mvc.get().uri("/open-banking/v1/aisp/accounts").with(consentBound(consentId)).exchange();

        assertThat(response).headers().containsHeader("Deprecation");
        assertThat(response).headers().containsHeader("Sunset");
        assertThat(response.getResponse().getHeader("Link")).contains("rel=\"deprecation\"");
    }

    @Test
    void version2IsNotDeprecated() {
        var response = mvc.get().uri("/open-banking/v2/aisp/accounts").with(consentBound(consentId)).exchange();

        assertThat(response).headers().doesNotContainHeader("Deprecation");
    }
}
```

The important pair is the first two tests: **the same data, through two versions, with two different representations**, both pinned down. If anyone "tidies up" v1 to match v2, `version1ShowsAnOverdraftAsANegativeAmount` fails. Old versions need tests at least as much as new ones: nobody is looking at them day to day, which is exactly when they break.

All the earlier tests still use `/v1/` paths and still pass. That's the point: v1 clients didn't have to change anything.

### 7. Swagger UI and the version

Open `http://localhost:8080/swagger-ui.html`, or look at the raw document at `/v3/api-docs`. SpringDoc 3 understands Spring's versioning partly. Listing the paths it generates:

```
GET /open-banking/{version}/aisp/accounts
GET /open-banking/{version}/aisp/accounts/{accountId}
GET /open-banking/{version}/aisp/accounts/{accountId}/transactions
GET /open-banking/2/aisp/accounts/{accountId}/balances
GET /open-banking/1/aisp/accounts/{accountId}/balances
GET /open-banking/{version}/aisp/account-access-consents/{consentId}
…
```

- **The two balances methods** have `version` attributes, so SpringDoc lists each separately with the version filled in. It writes `1` and `2` rather than `v1` and `v2`, but those URLs work too, because Spring's parser ignores a leading non-digit prefix.
- **Every other path still contains `{version}`**, and SpringDoc doesn't describe it as a parameter. Swagger UI then has no box to fill it in. (Strictly, an OpenAPI document must describe every path template variable, so this document isn't valid either.)

Fix the second problem by describing the parameter yourself. SpringDoc's `OpenApiCustomizer` gets the generated document and can change it. Add a bean to `api/OpenApiConfig.java`, and bump the document's `version` to `v2` while you're there:

```java
package com.example.openbanking.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.PathParameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Describes the bearer-token security in the OpenAPI document, so Swagger UI
 * shows an Authorize button and sends "Authorization: Bearer ..." on every call.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(title = "Open Banking API", version = "v2",
                description = "Account information for authorised third parties (a teaching system)"),
        security = @SecurityRequirement(name = "bearer"))
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {

    /** Paths like /open-banking/{version}/... need {version} described, so Swagger UI can fill it in. */
    @Bean
    OpenApiCustomizer versionPathParameter() {
        return openApi -> openApi.getPaths().forEach((path, item) -> {
            if (path.contains("{version}")) {
                item.readOperations().forEach(operation -> operation.addParametersItem(new PathParameter()
                        .name("version")
                        .description("API version")
                        .schema(new StringSchema()._enum(List.of("v1", "v2"))._default("v2"))));
            }
        });
    }
}
```

- **`PathParameter`** is a path parameter, which OpenAPI requires to be marked required (the class does that for you).
- **`_enum(List.of("v1", "v2"))`** makes Swagger UI show a drop-down. (`_enum` and `_default` have underscores because `enum` and `default` are Java keywords.)
- **The list duplicates `addSupportedVersions`.** In a larger codebase you'd keep the supported versions in one constant used by both.

Restart. Every `{version}` operation now has a `version` drop-down defaulting to `v2`.

---

## Your turn

### Exercise 1: test the OpenAPI document

The OpenAPI document is part of your contract too: TPPs generate client code from it. Add a test to `ApiVersioningTest` proving that `/open-banking/{version}/aisp/accounts` declares a `version` parameter whose allowed values are exactly `v1` and `v2`.

<details>
<summary>Hint</summary>

JSONPath can address a key containing `/` and `{}` with bracket notation: `$.paths['/open-banking/{version}/aisp/accounts']`.

</details>

<details>
<summary>Solution</summary>

```java
@Test
void theOpenApiDocumentDescribesTheVersionParameter() {
    var response = mvc.get().uri("/v3/api-docs");

    assertThat(response).hasStatusOk();
    assertThat(response).bodyJson()
            .extractingPath("$.paths['/open-banking/{version}/aisp/accounts'].get.parameters[0].name")
            .isEqualTo("version");
    assertThat(response).bodyJson()
            .extractingPath("$.paths['/open-banking/{version}/aisp/accounts'].get.parameters[0].schema.enum")
            .asArray().containsExactly("v1", "v2");
}
```

(Note the irony of the path `/v3/api-docs`: that `v3` is the OpenAPI document format's version, and it's not under `/open-banking/`, so your versioning predicate rightly ignores it.)

</details>

### Exercise 2: unit-test the conversion

`BalanceResponseV2.from` holds the only real logic in v2. Test it without Spring: a −£80.00 balance becomes `Debit` £80.00, and a zero balance becomes `Credit` £0.00. Put the test in the `com.example.openbanking.account` package, so it can call the package-private `from`.

<details>
<summary>Solution</summary>

`src/test/java/com/example/openbanking/account/BalanceResponseV2Test.java`:

```java
package com.example.openbanking.account;

import com.example.openbanking.account.AccountController.BalanceResponseV2;
import com.example.openbanking.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BalanceResponseV2Test {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

    @Test
    void anOverdrawnBalanceIsAPositiveDebit() {
        var v2 = BalanceResponseV2.from("acc-2001", new Balance(BalanceType.INTERIM_BOOKED, Money.gbp("-80.00")), NOW);

        assertThat(v2.creditDebitIndicator()).isEqualTo(CreditDebit.DEBIT);
        assertThat(v2.amount()).isEqualTo(Money.gbp("80.00"));
    }

    @Test
    void aZeroBalanceIsACredit() {
        var v2 = BalanceResponseV2.from("acc-2001", new Balance(BalanceType.INTERIM_BOOKED, Money.gbp("0.00")), NOW);

        assertThat(v2.creditDebitIndicator()).isEqualTo(CreditDebit.CREDIT);
        assertThat(v2.amount()).isEqualTo(Money.gbp("0.00"));
    }
}
```

The zero case is the boundary that's easiest to get wrong (`isPositive()` instead of `!isNegative()` would make it a `Debit`), so it's the one most worth a test.

</details>

### Exercise 3 (stretch, no solution given): a header instead

Change the strategy: read the version from an `x-api-version` header instead of the path, with v2 as the default when the header is missing. Which tests have to change? Which of the trade-offs in the Concepts table do you notice while making the change? Change it back afterwards: the UK standard uses the path.

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 63, Failures: 0, Errors: 0, Skipped: 0
```

(54 from guide 04, plus 7 in `ApiVersioningTest` and 2 in `BalanceResponseV2Test`, if you did the first two exercises.)

New files since guide 04:

```
src/main/java/com/example/openbanking/api/
└── ApiVersioningConfig.java
src/test/java/com/example/openbanking/
├── account/BalanceResponseV2Test.java
└── api/ApiVersioningTest.java
```

Modified: `AccountController.java`, `ConsentController.java`, `OpenApiConfig.java`, `requests.http`.

---

## Common errors

**`400` with `"detail": "Invalid API version: 'account-access-consents'."`** (or `'tokens'`, or any other word)
The path-segment resolver is reading a segment from a path that isn't versioned. Use `usePathSegment(1, predicate)` so only `/open-banking/` paths are versioned, as in step 2.

**`400` with `"detail": "Invalid API version: '1.0.0'."`**
Version 1 isn't in the supported list. If no mapping declares `version = "1"`, Spring can't detect it: add it with `addSupportedVersions("1", …)`.

**`400` with `"detail": "Invalid API version: '3.0.0'."`**
Working as intended: v3 doesn't exist. Note the message shows the *parsed* version.

**Swagger UI has no way to enter the version for most operations**
The OpenAPI document doesn't describe `{version}` as a parameter. Add the `OpenApiCustomizer` from step 7.

**No `Deprecation` header on v1 responses**
The deprecation handler isn't registered: check `.setDeprecationHandler(deprecations)` is in `configureApiVersioning`. The version string in `configureVersion("1")` must parse to the same version as the request's (`v1` → `1.0.0`).

---

## Recap

- A published API's contract is **frozen**. Additive changes are safe; removing, renaming or changing the **meaning** of anything needs a **new version**.
- Path, header, query parameter and media type versioning all work. **UK Open Banking uses the path** (`/open-banking/v3.1/aisp/...`). Pick one and be consistent.
- Spring Framework 7 has versioning built in: tell `ApiVersionConfigurer` **where** the version is and **which** versions are supported, and put a **`version`** attribute on mappings that differ. `"2+"` is a **baseline**. No attribute means **every** version.
- Path versioning in Spring has two traps: the resolver applies to **every** path unless you give it a predicate, and the first version must be declared as **supported**.
- Keep versioning in the **API layer**: one domain, several representations.
- Announce retirement in every response with **`Deprecation`**, **`Sunset`** and a **`Link`**, using `StandardApiVersionDeprecationHandler`.
- **Test old versions** as carefully as new ones.

### Interview angle

<details>
<summary>"How would you introduce a breaking change to an API that third parties use?"</summary>

Don't change the existing version. Publish a new version alongside it, sharing the domain logic and differing only in the representation at the edge. Announce the old version's deprecation and sunset dates through every channel: developer portal, direct contact with integrators, and machine-readable `Deprecation`/`Sunset` headers on every response. Monitor who's still calling the old version, chase them, and only switch it off after the sunset date. Keep tests for both versions until the old one is gone.

</details>

<details>
<summary>"Path versioning or header versioning?"</summary>

Path versioning is explicit and visible in logs, links and gateways, and easy to try by hand; its downside is that every URL changes with a new version, and purists argue the version isn't part of the resource's identity. Header versioning keeps URLs stable but is invisible unless you log headers, and needs a sensible default. Both are fine. Consistency matters more than the choice. UK Open Banking uses path versioning, so for Open Banking APIs that's what you'd do.

</details>

<details>
<summary>"What counts as a breaking change?"</summary>

Anything that can make a correctly written existing client fail or misbehave: removing or renaming endpoints or fields, changing types or formats, making optional inputs required, changing status codes or error formats clients rely on, and changing a field's meaning without changing its name (for example, signed amounts becoming positive amounts with a separate indicator). Adding endpoints, optional request fields or response fields is normally safe, provided clients were told to ignore unknown fields.

</details>

### Further reading

- [Spring Framework reference: API versioning](https://docs.spring.io/spring-framework/reference/web/webmvc-versioning.html) and [the MVC configuration for it](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-config/api-version.html)
- [RFC 9745: The Deprecation HTTP Response Header Field](https://www.rfc-editor.org/rfc/rfc9745) and [RFC 8594: The Sunset HTTP Header Field](https://www.rfc-editor.org/rfc/rfc8594)
- [UK Open Banking: Read/Write Data API profile](https://openbankinguk.github.io/read-write-api-site3/v3.1.10/profiles/read-write-data-api-profile.html): the URI structure, including where the version goes
- [SpringDoc OpenAPI documentation](https://springdoc.org/)

**Simplification to notice:** real banks often put an **API gateway** in front of their APIs, which handles version routing, rate limiting, mutual TLS and certificate checks before a request ever reaches Spring. The versioning logic you wrote here would still be needed inside the service; the gateway just adds another layer.

---

## You've built an Open Banking API

Across five guides you've built the bank's side of UK Open Banking account information:

| Guide | Banking idea | Java/Spring skill |
|---|---|---|
| 01 | PSD2, the CMA Order, ASPSPs and TPPs. Booked vs available balances. | Seeded Flyway locations, the `Data`/`Links`/`Meta` envelope, a correlation-id filter with MDC |
| 02 | OAuth 2.0 in banking, SCA and FAPI | OAuth2 resource server, JWT validation (RFC 9068), scopes to authorities, `ProblemDetail` for 401/403, `jwt()` tests, Swagger security |
| 03 | Consents, permissions, data minimisation, the consent lifecycle | A state machine in the entity, `@ElementCollection`, idempotent creation, header validation, `Clock` |
| 04 | Enforcing consents, immediate revocation | A consent guard, permission-shaped responses, a race test with a `CountDownLatch`, optimistic locking with `@Version` |
| 05 | Evolving an API that third parties depend on | Spring Framework 7 API versioning, `Deprecation`/`Sunset`, OpenAPI customisation |

**Where to go next:**

- **The [Payments tutorial](../payments/README.md)** covers what happens after a payment is initiated: the bank sending it through Faster Payments. That's where idempotency gets serious, because a duplicate means money moving twice. (A PISP payment-initiation API would sit in front of that, and is a natural extension of this tutorial.)
- **Run a real authorisation server.** Replace `DevJwtConfig` with Spring Authorization Server or Keycloak in Docker, point `spring.security.oauth2.resourceserver.jwt.issuer-uri` at it, and watch every test that uses `jwt()` keep passing.
- **Extend the consent:** add `TransactionFromDateTime`/`TransactionToDateTime` and filter transactions by them; page the transactions endpoint with `links.next`; add `ReadTransactionsDetail`-only fields such as a running balance.
