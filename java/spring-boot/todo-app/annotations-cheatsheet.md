# Annotation Cheat Sheet

Every annotation used in the series, plus common ones you'll meet in other Spring code. **Guide** shows where it's introduced. A dash means it isn't used in this series, but is worth recognising.

---

## Core: beans and dependency injection

| Annotation | What it does | Guide |
|---|---|---|
| `@SpringBootApplication` | `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan` on the main class | 01 |
| `@Component` | Registers a class as a bean (generic) | 01, 03 |
| `@Service` | A `@Component` for business logic | 01 |
| `@Repository` | A `@Component` for data access; translates persistence exceptions. (Spring Data interfaces don't need it.) | — |
| `@Controller` / `@RestController` | A `@Component` that handles web requests (views / response body) | 01, 02 |
| `@Configuration` | A class whose `@Bean` methods define beans | 04 |
| `@Bean` | The method's return value becomes a bean. Use it for objects you *build* (often library classes). | 04 |
| `@Autowired` | "Inject here". **Not needed on a single constructor.** Used on test fields, on one of several constructors, on setters, and (in older code) on fields. | 01, 07 |
| `@Qualifier("name")` | Picks one bean by name when several share a type | 01 (exercise) |
| `@Primary` | Marks the default bean when several share a type | 01 (exercise) |
| `@Value("${prop}")` | Injects a single property value | 01 |
| `@ConfigurationProperties(prefix = "...")` | Binds a group of properties onto a type-safe class or record | 01 (sidebar) |
| `@ConfigurationPropertiesScan` | Finds `@ConfigurationProperties` types automatically | 01 (sidebar) |
| `@Lazy` | Creates the bean only when first used, rather than at start-up | — |
| `@Scope("prototype")` | A new instance on every injection, instead of the default single shared instance (*singleton*). Web apps also have `request` and `session` scopes. | — |
| `@Profile("dev")` | Registers the bean only when that profile is active | — |
| `@Conditional…` (`@ConditionalOnMissingBean`, `@ConditionalOnClass`, `@ConditionalOnProperty`) | How auto-configuration decides what to create. Useful in your own starters and config. | 01 (concept) |
| `@PostConstruct` / `@PreDestroy` | Runs a method after the bean is created / before shutdown (`jakarta.annotation`) | — |
| `@EventListener` | Handles application events, e.g. `ApplicationReadyEvent` | — |
| `@Order` | Orders beans of the same type (filters, runners, advice) | — |

## Web (Spring MVC)

| Annotation | What it does | Guide |
|---|---|---|
| `@RequestMapping("/prefix")` | Maps a URL, usually as a class-level prefix | 05 |
| `@GetMapping`, `@PostMapping` | Maps a GET / POST to a method | 01, 04 |
| `@PutMapping`, `@DeleteMapping`, `@PatchMapping` | Other HTTP methods (REST APIs, or HTML forms with the hidden-method filter) | 05 (sidebar) |
| `@PathVariable` | Binds a `{placeholder}` from the path | 02, 05 |
| `@RequestParam` | Binds a query or form parameter | 01, 02 |
| `@ModelAttribute("name")` | On a parameter: binds a form object and names it in the model. On a method: adds its return value to every model in the controller. | 04, 05 |
| `@Valid` | Triggers Bean Validation on a parameter (`jakarta.validation`) | 04 |
| `@Validated` | Spring's variant: supports validation groups, and method-level validation on beans | — |
| `@ResponseBody` | Writes the return value as the body (built into `@RestController`) | 02 |
| `@RequestBody` | Reads JSON (etc.) from the request body into an object (REST) | — |
| `@ResponseStatus(HttpStatus.X)` | On an exception class: the status to send when it's thrown. On a method: the success status. | 05 |
| `@ControllerAdvice` / `@RestControllerAdvice` | Cross-controller exception handling and shared model attributes | 05 (sidebar) |
| `@ExceptionHandler(X.class)` | A method that handles exception `X` | 05 (sidebar) |
| `@DateTimeFormat(iso = ...)` | How to parse and format a date field in forms | 06 |
| `@CrossOrigin` | Allows cross-origin (CORS) requests to an endpoint | — |

## Security

| Annotation | What it does | Guide |
|---|---|---|
| `@AuthenticationPrincipal` | Injects the logged-in user's `UserDetails` into a controller method | 04 |
| `@EnableWebSecurity` | Turns on web security. Boot does it for you. | 04 (note) |
| `@EnableMethodSecurity` | Enables `@PreAuthorize` etc. | 04 (sidebar) |
| `@PreAuthorize("expr")` | Checks an expression before a method runs, e.g. `hasRole('ADMIN')` | 04, 06 (sidebars) |

## Data / JPA

| Annotation | What it does | Guide |
|---|---|---|
| `@Entity` | Maps a class to a table | 03 |
| `@Table(name = "...")` | Overrides the table name (e.g. to avoid reserved words) | 03 |
| `@Id` | The primary key | 03 |
| `@GeneratedValue(strategy = IDENTITY)` | The database generates the key | 03 |
| `@Column(...)` | Column name, nullability, length, uniqueness | 03 |
| `@ManyToOne(fetch = LAZY)` | Many of this → one of that. Holds the foreign key. | 03 |
| `@OneToMany(mappedBy = "...")` | The inverse side of a `@ManyToOne` | 03 |
| `@JoinColumn(name = "...")` | Names the foreign-key column | 03 |
| `@OrderBy("field ASC")` | Sorts a collection when loaded | 03 |
| `@ManyToMany`, `@OneToOne`, `@Embedded`, `@Enumerated`, `@Version` | Other mappings: join tables, one-to-one, value objects, enums, optimistic locking | — |
| `@PrePersist`, `@PreUpdate` | Entity lifecycle callbacks (e.g. set timestamps) | — |
| `@CreationTimestamp`, `@UpdateTimestamp` | Hibernate-specific automatic timestamps | — |
| `@Query("JPQL")` | A custom repository query | 03, 06 |
| `@Param("name")` | Binds a `:name` parameter in `@Query` | 03 |
| `@Modifying` | Marks an update/delete `@Query` | 06 |
| `@EntityGraph(attributePaths = ...)` | Fetches relationships in the same query (an N+1 fix) | 03 (mention) |
| `@Transactional` | Runs the method in a transaction: commit on return, roll back on `RuntimeException`. Works through a proxy. | 03, 05 |
| `@Transactional(readOnly = true)` | A read-only transaction (skips dirty checking) | 05 |
| `@EnableJpaAuditing` + `@CreatedDate`, `@CreatedBy`, `@LastModifiedDate` | Automatic audit columns | 06 (sidebar) |

## Testing

| Annotation | What it does | Guide |
|---|---|---|
| `@Test` | A JUnit test method | 07 |
| `@BeforeEach` / `@AfterEach` | Runs before / after each test | 07 |
| `@BeforeAll` / `@AfterAll` | Runs once per class (`static`) | — |
| `@DisplayName`, `@Nested`, `@ParameterizedTest` + `@ValueSource`/`@CsvSource` | Readable names, grouped tests, data-driven tests | — |
| `@ExtendWith(MockitoExtension.class)` | Enables Mockito annotations in a plain unit test | 07 |
| `@Mock` | Creates a Mockito mock | 07 |
| `@InjectMocks` | Builds the object under test with the mocks | 07 |
| `@Captor` | Declares an `ArgumentCaptor` field | — |
| `@WebMvcTest(Controller.class)` | MVC slice: controllers, filters, security, Thymeleaf; no services/repositories | 07 |
| `@DataJpaTest` | JPA slice: entities, repositories, in-memory DB, rollback per test | 08 |
| `@SpringBootTest` | Full application context | 07, 08 |
| `@AutoConfigureMockMvc` | Adds `MockMvc` to a `@SpringBootTest` | 08 |
| `@MockitoBean` | Replaces a bean in the context with a mock (replaces Boot ≤3's `@MockBean`) | 07 |
| `@MockitoSpyBean` | Wraps a real bean in a spy (replaces `@SpyBean`) | — |
| `@Import(Config.class)` | Adds configuration to a slice | 07 |
| `@WithMockUser(username = ...)` | Runs the test as an authenticated user | 07 |
| `@Transactional` (on a test) | Rolls back after each test | 08 |
| `@ActiveProfiles("test")` | Activates profiles for the test context | 08 (mention) |
| `@TestPropertySource` / `@SpringBootTest(properties = ...)` | Overrides properties for a test | 08 |
| `@DirtiesContext` | Discards the cached context after the test (slow) | 08 |
| `@AutoConfigureTestDatabase` | Controls whether slices swap in an embedded DB | — |

---

**Rule of thumb:** if you can't explain what an annotation does, look it up before copying it. Many annotations *change behaviour by side effect* (proxies, conditions, scanning), and that's where the hardest bugs come from.

[Back to the index](README.md)
