# Legacy Cheat Sheet

This series uses **Java 25** and **Spring Boot 4**. Many banks run **Java 17 or 21 on Spring Boot 3**, and you may find older systems on **Java 8 or 11 with Spring Boot 2**, or no Spring at all. This page maps what you learn here to what you're likely to see on your first day.

Use it as a translation table. Don't copy the old style into new code.

---

## Spring Boot versions at a glance

| | Boot 2.x | Boot 3.x | Boot 4.x (this series) |
|---|---|---|---|
| Minimum Java | 8 | 17 | 17 (we use 25) |
| Spring Framework | 5 | 6 | 7 |
| Enterprise APIs | `javax.*` | `jakarta.*` | `jakarta.*` |
| Hibernate | 5 | 6 | 7 |
| Jackson | 2 (`com.fasterxml.jackson.databind`) | 2 | 3 (`tools.jackson.databind`). Annotations are still `com.fasterxml.jackson.annotation` |
| Web starter | `spring-boot-starter-web` | `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| Mock a bean in a test | `@MockBean` | `@MockBean` (deprecated in 3.4) | `@MockitoBean` |
| Error responses | Hand-rolled error DTOs | `ProblemDetail` available | `ProblemDetail` |
| MockMvc assertions | `andExpect(jsonPath(...))` | `MockMvcTester` from 3.4 | `MockMvcTester` |

### The `javax` → `jakarta` switch

The biggest visible difference in older code. Everything from Java EE moved package in Boot 3:

```java
// Boot 2
import javax.persistence.Entity;
import javax.validation.constraints.NotBlank;
import javax.transaction.Transactional;

// Boot 3 and 4
import jakarta.persistence.Entity;
import jakarta.validation.constraints.NotBlank;
import org.springframework.transaction.annotation.Transactional; // prefer Spring's own
```

If you're ever upgrading an old service, the [OpenRewrite](https://docs.openrewrite.org/) Spring Boot migration recipes automate most of this.

---

## Lombok

This series avoids Lombok so you can see exactly what code does. Bank codebases often use it heavily. Here's what each annotation generates:

| Lombok | What it generates | Modern equivalent |
|---|---|---|
| `@Getter` / `@Setter` | Getters and setters for fields | Write the getters you need. Avoid setters on entities. |
| `@Data` | Getters, setters, `equals`, `hashCode`, `toString`, required-args constructor | A `record` for data carriers. **Never** `@Data` on a JPA entity: generated `equals`/`hashCode` and `toString` over lazy relationships cause bugs. |
| `@Value` | An immutable class: private final fields, getters, `equals`/`hashCode`/`toString` | A `record` |
| `@Builder` | A fluent builder | A hand-written builder (see `JournalEntry.Builder` in the core ledger) or a record with a static factory |
| `@RequiredArgsConstructor` | A constructor for all `final` fields | Write the constructor. It's the constructor injection you see in every guide. |
| `@NoArgsConstructor(access = PROTECTED)` | The no-args constructor JPA needs | `protected Account() {}` |
| `@Slf4j` | `private static final Logger log = LoggerFactory.getLogger(...)` | Write that line yourself |
| `@SneakyThrows` | Throws checked exceptions without declaring them | Wrap in an unchecked exception explicitly |

Typical Lombok-style service you might see:

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class TransferService {
    private final AccountRepository accounts;   // injected through the generated constructor
    ...
}
```

It's the same constructor injection used in these guides, just generated.

---

## Java language features used in this series

If your target codebase is on an older Java version, these won't compile there:

| Feature | Example | Minimum Java |
|---|---|---|
| `java.time` (`Instant`, `LocalDate`) | `Instant.now()` | 8. Older code uses `java.util.Date` or Joda-Time. |
| `var` | `var response = mvc.get()...` | 10 |
| Switch expressions | `case ASSET -> ...` | 14 |
| Text blocks | `"""{"name": "Alice"}"""` | 15 |
| `String.formatted(...)` | `"Account %d".formatted(id)` | 15 |
| Records | `record Money(BigDecimal amount, Currency currency)` | 16 |
| `Stream.toList()` | `.map(...).toList()` | 16. Older code uses `.collect(Collectors.toList())`. |
| Pattern matching `instanceof` | `if (e instanceof InsufficientFundsException ife)` | 16 |
| Sealed interfaces | `sealed interface PaymentState permits ...` | 17 |
| `ExecutorService` in try-with-resources | `try (var pool = Executors.newFixedThreadPool(10))` | 19 |
| `List.getFirst()` (sequenced collections) | `postings.getFirst()` | 21. Older code uses `.get(0)`. |
| Record patterns, pattern `switch` | `case Money(var amount, var currency) -> ...` | 21 |
| Virtual threads | `Executors.newVirtualThreadPerTaskExecutor()` | 21 |

---

## Money in older code

You will meet these. Recognise them and know why they're dangerous:

| You might see | Problem | Use instead |
|---|---|---|
| `double balance` | Binary floating point can't represent 0.10 exactly, so `0.1 + 0.2 != 0.3` | `BigDecimal` (or `long` pence) |
| `new BigDecimal(0.1)` | Copies the `double`'s inexact value: `0.1000000000000000055511151231257827…` | `new BigDecimal("0.1")` or `BigDecimal.valueOf(0.1)` |
| `BigDecimal.ROUND_HALF_UP` | Integer constants, deprecated since Java 9 | `RoundingMode.HALF_UP` |
| `a.equals(b)` on `BigDecimal` | `2.0` is not equal to `2.00` (it compares scale too) | `a.compareTo(b) == 0`, or normalise the scale as `Money` does |
| `long amountInPence` | Fine, but the unit is invisible and easy to confuse | A `Money` value type that makes the unit explicit |

---

## Testing

| Older | Modern |
|---|---|
| JUnit 4: `@RunWith(SpringRunner.class)`, `org.junit.Test`, `@Before` | JUnit Jupiter (JUnit 5, or JUnit 6 in Boot 4): no runner needed, `org.junit.jupiter.api.Test`, `@BeforeEach` |
| `assertEquals(expected, actual)` | AssertJ: `assertThat(actual).isEqualTo(expected)` |
| `@MockBean` | `@MockitoBean` |
| `mockMvc.perform(post(...)).andExpect(status().isCreated()).andExpect(jsonPath("$.id").exists())` | `assertThat(mvc.post().uri(...)).hasStatus(HttpStatus.CREATED).bodyJson()...` |
| `@DirtiesContext` everywhere to reset state | Tests that create their own data and don't depend on a clean database |

---

## Other things you'll see

| Older | Modern | Notes |
|---|---|---|
| `@Autowired` on fields | Constructor injection | Field injection hides dependencies and makes unit tests harder |
| `RestTemplate` | `RestClient` (Spring 6.1+) | `RestTemplate` still works, but isn't getting new features |
| `spring-retry`'s `@Retryable` | `@Retryable` in Spring Framework 7 core (`org.springframework.resilience.annotation`) | Same idea, now built in |
| `WebSecurityConfigurerAdapter` | A `SecurityFilterChain` bean | Removed in Spring Security 6 |
| `.antMatchers(...)` | `.requestMatchers(...)` | Spring Security 6 |
| `HttpStatus.UNPROCESSABLE_ENTITY` | `HttpStatus.UNPROCESSABLE_CONTENT` | The status (422) was renamed in RFC 9110 |
| XML bean configuration (`applicationContext.xml`) | `@Configuration` classes and auto-configuration | Common in very old systems |
| Hand-written `ErrorResponse` DTOs | `ProblemDetail` (RFC 9457) | Same idea, now a standard |
