# 08 — Testing: Data and Integration

> Written for **Spring Boot 4.1.2** and **Java 25**. Builds on [07 — Testing: unit and web](07-testing-unit-and-web.md).

## What you'll learn

- **`@DataJpaTest`**: testing repository queries and mappings against a real (in-memory) database
- Why `flush()` and `clear()` matter when testing JPA
- **`@SpringBootTest`** + **`@AutoConfigureMockMvc`**: full integration tests through every layer
- Keeping tests away from your development database
- **Test transactions** and automatic rollback
- How Spring **caches test contexts**, and what breaks the cache
- Proving the ownership rules end to end
- **IntelliJ:** inspecting a test database from the debugger, spotting context reuse in the test runner, and a fast break-and-rerun workflow

By the end, the ownership rule from guide 06 is proven at every level: service, web, query and full stack.

---

## Concepts

### What the data slice proves

Guide 07's unit test showed that `TodoListService.getOwned` throws when the repository returns empty. But does `findByIdAndOwnerEmail` *really* filter by owner? Does the JPQL in `findOwned` really check the list? Is `cascade` really configured? Only a test against **a real database, running real SQL**, can answer that.

`@DataJpaTest`:

- loads **only** JPA things: entities, repositories, the `EntityManager`, a `DataSource`. No controllers, services or security.
- **replaces your datasource with an embedded in-memory database** by default, so your `data/todo.mv.db` file is never touched.
- wraps **each test in a transaction that's rolled back** at the end, so every test starts with a clean database.

### The persistence-context trap

```java
todoListRepository.save(list);
TodoList found = todoListRepository.findById(list.getId()).get();   // no SQL at all!
```

Within one transaction, `findById` returns the *same object* from the persistence context (guide 03) without querying the database. Your test passes even if the mapping is broken. To really test the round trip:

```java
entityManager.flush();   // send pending INSERT/UPDATEs to the database now
entityManager.clear();   // empty the persistence context, so the next read must hit the DB
```

JPQL queries (`@Query`, derived queries) *do* flush automatically before running, but a `clear()` is still needed if you want freshly loaded objects.

### Context caching

Starting a Spring context takes time. The Spring TestContext Framework **caches** each context and reuses it for every test class with **identical configuration**: the same annotations, properties, `@MockitoBean`s and `@Import`s. Each *different* combination creates (and caches) another context.

So:

- Keep `@SpringBootTest` configuration **identical** across classes where you can, so they share one context.
- Every distinct set of `@MockitoBean`s makes a new context. In `@SpringBootTest` classes, prefer real beans.
- `@DirtiesContext` throws a context away after use. It's occasionally necessary, and always slow.

---

## Steps

### 1. A repository test: setup

Create `src/test/java/com/example/todoapp/list/TodoListRepositoryTest.java`:

```java
package com.example.todoapp.list;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.example.todoapp.todo.Todo;
import com.example.todoapp.todo.TodoRepository;
import com.example.todoapp.user.User;
import com.example.todoapp.user.UserRepository;

import jakarta.persistence.EntityManager;

@DataJpaTest
class TodoListRepositoryTest {

    @Autowired UserRepository userRepository;
    @Autowired TodoListRepository todoListRepository;
    @Autowired TodoRepository todoRepository;
    @Autowired EntityManager entityManager;

    User alice;
    User bob;
    TodoList aliceList;
    TodoList bobList;

    @BeforeEach
    void setUp() {
        alice = userRepository.save(new User("alice@example.com", "{noop}x"));
        bob = userRepository.save(new User("bob@example.com", "{noop}x"));

        aliceList = new TodoList("Groceries", alice);
        aliceList.addTodo(new Todo("Milk", null));
        Todo bread = new Todo("Bread", null);
        bread.setDone(true);
        aliceList.addTodo(bread);
        todoListRepository.save(aliceList);

        bobList = todoListRepository.save(new TodoList("Bob's empty list", bob));

        entityManager.flush();
        entityManager.clear();
    }
}
```

- If `DataJpaTest` won't import from that package, let your IDE find it. In Boot 4 it lives in the `spring-boot-data-jpa-test` module (Boot 3 had it in `org.springframework.boot.test.autoconfigure.orm.jpa`).
- `@BeforeEach` runs **inside** each test's transaction, so this data is rolled back after every test. No cleanup needed.
- `{noop}x` is fine as a password hash here, because nothing in this slice checks passwords.
- `@Autowired EntityManager` injects JPA's own API. Boot also provides **`TestEntityManager`**, a test helper with conveniences like `persistAndFlush`. Either works.

### 2. Test the ownership queries

Add these tests to the class:

```java
@Test
void findByIdAndOwnerEmailReturnsTheOwnersList() {
    assertThat(todoListRepository.findByIdAndOwnerEmail(aliceList.getId(), "alice@example.com"))
            .isPresent();
}

@Test
void findByIdAndOwnerEmailHidesOtherUsersLists() {
    assertThat(todoListRepository.findByIdAndOwnerEmail(aliceList.getId(), "bob@example.com"))
            .isEmpty();
}

@Test
void summariesCountTodosAndIncludeEmptyLists() {
    List<TodoListSummary> aliceSummaries = todoListRepository.findSummariesByOwnerEmail("alice@example.com");
    List<TodoListSummary> bobSummaries = todoListRepository.findSummariesByOwnerEmail("bob@example.com");

    assertThat(aliceSummaries)
            .extracting(TodoListSummary::name, TodoListSummary::todoCount)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("Groceries", 2L));

    assertThat(bobSummaries)
            .singleElement()
            .extracting(TodoListSummary::todoCount)
            .isEqualTo(0L);
}
```

- The second test is **the** important one: it proves the query filters by owner. If someone "simplifies" it to `findById`, this test fails.
- The third test checks the `left join`. With an inner `join`, Bob's empty list would vanish from his index page. That's a real bug a unit test with mocks could never catch.
- AssertJ's `extracting(...)` + `containsExactly(tuple(...))` checks several fields of each element at once. (You can `import static org.assertj.core.groups.Tuple.tuple;` to tidy that up.)

### 3. Test cascades

```java
@Test
void deletingAListDeletesItsTodos() {
    TodoList reloaded = todoListRepository.findById(aliceList.getId()).orElseThrow();

    todoListRepository.delete(reloaded);
    entityManager.flush();

    assertThat(todoRepository.findByTodoListIdOrderByDoneAscIdAsc(aliceList.getId())).isEmpty();
    assertThat(todoRepository.count()).isZero();
}
```

Turn on `show-sql` output (it's already on) and run this test. You'll see Hibernate delete the two todos, then the list. If you remove `cascade = CascadeType.ALL` from `TodoList.todos` and re-run, you get a **foreign-key violation**, which is exactly the failure from guide 03's raw SQL exercise.

Run the class from the gutter ▶ next to `class TodoListRepositoryTest` (terminal: `./mvnw test -Dtest=TodoListRepositoryTest`).

In the test's console output, press <kbd>Ctrl</kbd>+<kbd>F</kbd> and search for `jdbc:h2:mem` to confirm it's using a throwaway database, not your file.

**Look inside the test database while it's paused.** The in-memory database only exists inside the test JVM, so IntelliJ's Database window can't see it. The debugger can:

1. Put a breakpoint on the first `assertThat` in `deletingAListDeletesItsTodos`, and **Debug** that test from its gutter icon.
2. When it stops, open **Evaluate Expression** (<kbd>Alt</kbd>+<kbd>F8</kbd>) and run `todoRepository.findAll()`, then `todoRepository.count()`. You're querying the live test database, mid-test.
3. Expand the results. These are real entities loaded by real SQL, which you'll see appear in the console as you evaluate.

### 4. Keep `@SpringBootTest` away from your dev database

`@SpringBootTest` starts the **whole** app with your real `application.properties`, which means `jdbc:h2:file:./data/todo`. Tests would read and write your development data (and fail when the app is running and has the file open). Override the URL for tests.

Update `TodoAppApplicationTests`:

```java
package com.example.todoapp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:todo-test;DB_CLOSE_DELAY=-1")
class TodoAppApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

- `jdbc:h2:mem:todo-test` is an in-memory database, created fresh for the test run.
- `DB_CLOSE_DELAY=-1` keeps it alive between connections for the life of the JVM, rather than dropping it as soon as the last connection closes.
- `DemoDataLoader` still runs (it's a normal bean), seeding the demo user into the *test* database. That's harmless, and it proves the loader works.

> **Why not `src/test/resources/application.properties`?** A file with the **same name** on the test classpath *replaces* the main one entirely, so you'd lose every other setting too. The usual pattern is a **profile**: `application-test.properties` plus `@ActiveProfiles("test")`. Profiles are out of scope for this series, and the `properties` attribute keeps the override visible right in the test.

### 5. A full integration test

Create `src/test/java/com/example/todoapp/OwnershipIntegrationTest.java`:

```java
package com.example.todoapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.todoapp.list.TodoList;
import com.example.todoapp.list.TodoListService;
import com.example.todoapp.todo.Todo;
import com.example.todoapp.todo.TodoRepository;
import com.example.todoapp.todo.TodoService;
import com.example.todoapp.user.UserService;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:todo-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
class OwnershipIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired TodoListService todoListService;
    @Autowired TodoService todoService;
    @Autowired TodoRepository todoRepository;

    TodoList aliceList;
    Todo aliceTodo;

    @BeforeEach
    void setUp() {
        userService.register("alice@example.com", "alice-password");
        userService.register("bob@example.com", "bob-password");
        aliceList = todoListService.create("Holiday plans", "alice@example.com");
        aliceTodo = todoService.add(aliceList.getId(), "Book flights", null, "alice@example.com");
    }

    @Test
    void registeredUserCanLogInWithTheirRealPassword() throws Exception {
        mockMvc.perform(formLogin("/login").userParameter("email")
                        .user("alice@example.com").password("alice-password"))
                .andExpect(authenticated().withUsername("alice@example.com"))
                .andExpect(redirectedUrl("/lists"));
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mockMvc.perform(formLogin("/login").userParameter("email")
                        .user("alice@example.com").password("not-her-password"))
                .andExpect(unauthenticated());
    }

    @Test
    void ownerCanSeeTheirList() throws Exception {
        mockMvc.perform(get("/lists/{id}", aliceList.getId()).with(user("alice@example.com")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Holiday plans")))
                .andExpect(content().string(containsString("Book flights")));
    }

    @Test
    void otherUsersGet404ForTheList() throws Exception {
        mockMvc.perform(get("/lists/{id}", aliceList.getId()).with(user("bob@example.com")))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherUsersCannotToggleTheTodo() throws Exception {
        mockMvc.perform(post("/lists/{l}/todos/{t}/toggle", aliceList.getId(), aliceTodo.getId())
                        .with(user("bob@example.com")).with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(todoRepository.findById(aliceTodo.getId()).orElseThrow().isDone()).isFalse();
    }
}
```

Taking it apart:

- **`@SpringBootTest`** starts everything: real services, real repositories, the real `SecurityConfig` and `AppUserDetailsService`, real BCrypt hashing, and real Thymeleaf rendering. No mocks.
- **`@AutoConfigureMockMvc`** gives you a `MockMvc` wired to the full application.
- **The same `properties` value as `TodoAppApplicationTests`** means both classes share **one cached context**. Check the log: the Spring banner appears once for both classes.
- **`@Transactional` on the test class**: each test runs in a transaction that's rolled back afterwards, just like `@DataJpaTest`. That works because MockMvc runs the request **on the test's thread**, so it joins the same transaction. (With a real HTTP server, as in `webEnvironment = RANDOM_PORT`, requests run on server threads, rollback doesn't apply, and you must clean up yourself.)
- **`formLogin(...)`** submits a real login request, CSRF token included, through your filter chain → `AppUserDetailsService` → database → `DelegatingPasswordEncoder`. This is the only test that proves registration and login actually work together.
- **`.with(user("bob@example.com"))`** authenticates the request as Bob, like `@WithMockUser` but per request, which lets one test act as different users. Our services look Bob up by email, and he's really in the database because `setUp` registered him.
- **`otherUsersCannotToggleTheTodo`** goes further than checking the status code: it checks the **data** is unchanged. A 404 response after the change was already made would still be a bug.
- Why `"Holiday plans"` rather than `"Alice's list"`? Thymeleaf escapes `'` to `&#39;`, so `containsString("Alice's")` wouldn't match the HTML. That's a small reminder that `th:text` escaping really is happening.

Run everything: right-click `src/test/java` → **Run 'All Tests'** (terminal: `./mvnw test`).

> These tests no longer touch `data/`, so you can now run them **while the app is running** in IntelliJ. MockMvc doesn't open a port, so nothing clashes with 8080.

### 6. See what the suite costs

In IntelliJ's test runner, each class and method shows its time on the right. Click the **root node** for the total, and use the **Sort by duration** button (clock icon) in the toolbar to see the slowest tests first. Then click each class and search its output for the Spring Boot banner (or `Started`) to see where a new context was started and where one was **reused**:

- Unit tests: a few milliseconds each.
- `@WebMvcTest` classes: one small context **per distinct configuration**. Each controller test class mocks different beans, so each gets its own.
- `@DataJpaTest`: one JPA-only context.
- `@SpringBootTest`: one full context, shared by both classes.

This is the pyramid from guide 07, measured. As the suite grows, most new tests should be unit and slice tests.

---

## Your turn

### Exercise 1: the cross-list attack, at the query level

Add a test to a new `TodoRepositoryTest` (`@DataJpaTest`) proving that `findOwned` returns **empty** when Bob passes **his own list ID** with **Alice's todo ID**. That's the subtle IDOR variant from guide 06.

<details>
<summary>Solution</summary>

```java
@DataJpaTest
class TodoRepositoryTest {

    @Autowired UserRepository userRepository;
    @Autowired TodoListRepository todoListRepository;
    @Autowired TodoRepository todoRepository;

    @Test
    void findOwnedRejectsATodoAccessedThroughAnotherList() {
        User alice = userRepository.save(new User("alice@example.com", "{noop}x"));
        User bob = userRepository.save(new User("bob@example.com", "{noop}x"));

        TodoList aliceList = new TodoList("Alice", alice);
        Todo aliceTodo = new Todo("Secret", null);
        aliceList.addTodo(aliceTodo);
        todoListRepository.save(aliceList);
        TodoList bobList = todoListRepository.save(new TodoList("Bob", bob));

        assertThat(todoRepository.findOwned(aliceTodo.getId(), bobList.getId(), "bob@example.com"))
                .isEmpty();
        assertThat(todoRepository.findOwned(aliceTodo.getId(), aliceList.getId(), "bob@example.com"))
                .isEmpty();
        assertThat(todoRepository.findOwned(aliceTodo.getId(), aliceList.getId(), "alice@example.com"))
                .isPresent();
    }
}
```

Always include the **positive** case (`isPresent`) alongside the negatives. A query that always returns empty would pass every negative test.

</details>

### Exercise 2: test the bulk delete

If you did guide 06's "Clear completed" exercise, write a `@DataJpaTest` proving `deleteCompleted` removes **only** the done todos, and **only** in the given list.

<details>
<summary>Hint</summary>

Create two lists, each with one done and one open todo. Call `deleteCompleted(firstList.getId())`, then `entityManager.clear()` before checking. Remember that bulk queries bypass the persistence context, so without `clear()` you might read stale objects.

</details>

### Exercise 3: an end-to-end registration test

Write an integration test that **registers** through `POST /register` with MockMvc (with CSRF), then logs in with `formLogin(...)` using the same credentials, and expects `authenticated()`.

<details>
<summary>Solution</summary>

```java
@Test
void newUserCanRegisterThenLogIn() throws Exception {
    mockMvc.perform(post("/register").with(csrf())
                    .param("email", "Carol@Example.com")
                    .param("password", "carol-password")
                    .param("confirmPassword", "carol-password"))
            .andExpect(redirectedUrl("/login?registered"));

    mockMvc.perform(formLogin("/login").userParameter("email")
                    .user("carol@example.com").password("carol-password"))
            .andExpect(authenticated().withUsername("carol@example.com"));
}
```

Note the mixed-case email at registration and lower-case at login. This one test covers the normalisation in both `UserService` and `AppUserDetailsService`.

</details>

---

## Checkpoint

Run **all tests** in IntelliJ, then **Maven → Lifecycle → test** (`./mvnw test`) as the final word. Everything passes.

Then do the most useful final check: **break the app and watch the tests catch it.** Try each change, re-run the suite (<kbd>Shift</kbd>+<kbd>F10</kbd> re-runs "All Tests" if that was your last run), then undo it. In IntelliJ, <kbd>Ctrl</kbd>+<kbd>Z</kbd> undoes the edit, or right-click the file → **Local History → Show History** to roll back several changes at once.

| Break this | Which test should fail? |
|---|---|
| In `TodoListService.getOwned`, call `findById(id)` instead of `findByIdAndOwnerEmail(...)` | `OwnershipIntegrationTest.otherUsersGet404ForTheList` ✅ (The repository test still passes because the query itself is fine; only the full-stack test sees that the service stopped using it.) |
| Remove `and t.todoList.id = :listId` from `findOwned` | `TodoRepositoryTest` ✅ |
| Change `left join` to `join` in the summary query | `summariesCountTodosAndIncludeEmptyLists` ✅ |
| Remove `.toLowerCase(...)` from `UserService` | `UserServiceTest` ✅ and Exercise 3 ✅ |
| Add `.csrf(csrf -> csrf.disable())` to `SecurityConfig` | `postWithoutCsrfTokenIsForbidden` ✅ |
| Remove `@Valid` from `TodoListController.create` | `createRejectsBlankName` ✅ |

If a change doesn't break any test, you've found a gap. Write the missing test.

Final test tree:

```
src/test/java/com/example/todoapp/
├── OwnershipIntegrationTest.java
├── TodoAppApplicationTests.java
├── list/
│   ├── TodoListControllerTest.java
│   ├── TodoListRepositoryTest.java
│   └── TodoListServiceTest.java
├── todo/
│   ├── TodoControllerTest.java
│   ├── TodoRepositoryTest.java
│   └── TodoServiceTest.java
└── user/
    ├── RegistrationControllerTest.java
    └── UserServiceTest.java
```

---

## Common errors

| Symptom | Cause / fix |
|---|---|
| Test passes but the mapping is clearly wrong | You read back from the persistence context. `flush()` and `clear()` before re-reading. |
| `@SpringBootTest` fails with "Database may be already in use" | It's using the `data/` file while the app is running. Override `spring.datasource.url` to an in-memory URL. |
| Data from one test appears in another | The test class isn't `@Transactional`, or uses `RANDOM_PORT` (where rollback doesn't apply), or the data was committed by the `DemoDataLoader`. Use unique emails per test, or clean up. |
| `Unique index or primary key violation` in `setUp` | Rollback isn't happening (see above), or `setUp` collides with the demo data. Don't use `demo@example.com` in tests. |
| A new Spring banner for every test class | Configurations differ (properties, `@MockitoBean`, `@Import`), so contexts can't be shared. Make them identical where possible. |
| `formLogin()` test is unauthenticated with the right password | Missing `.userParameter("email")`: the helper defaults to `username`. |
| `cannot find symbol: DataJpaTest` / `AutoConfigureMockMvc` | Boot 4 package moves. Let the IDE import them, and check the `-test` starters from guide 07 are in the POM. |
| `LazyInitializationException` in a `@SpringBootTest` | Just like production. The test touched a lazy field outside a transaction. Add `@Transactional` to the test, or assert through repositories. |

---

## Recap

- **`@DataJpaTest`** runs queries against a real, in-memory database, with rollback after each test. It's the only way to prove a query does what its name says. Use `flush()`/`clear()` to test real round trips.
- **`@SpringBootTest` + `@AutoConfigureMockMvc`** tests the whole stack with no mocks. Point it at an in-memory database, and use `@Transactional` for rollback.
- `formLogin()`, `user(...)` and `csrf()` from `spring-security-test` let you test real authentication and authorisation.
- Spring **caches** contexts by configuration. Keep configurations identical to share them.
- Test **both** the positive and negative cases, and check the *data*, not just the status code.
- **IntelliJ:** query a paused test's in-memory database with **Evaluate Expression**, read timings and context start-ups in the test runner, and use **Local History** as a safety net when experimenting.

---

## Where next?

You've built and tested a complete, secure, multi-user Spring Boot application. Things the series deliberately left out, roughly in the order they'd be worth learning:

1. **Profiles** (`application-dev.properties`, `@Profile`, `@ActiveProfiles`): separate dev, test and prod configuration.
2. **PostgreSQL + Docker Compose**: Boot's `spring-boot-docker-compose` module starts the database for you. Use **Testcontainers** in tests.
3. **Flyway migrations**, replacing `ddl-auto=update` (see the sidebar in guide 03).
4. **REST APIs**: `@RestController`, JSON with Jackson, `ResponseEntity`, `@RestControllerAdvice` with `ProblemDetail` for errors.
5. **Spring Boot Actuator**: health checks, metrics, `/actuator/conditions` (the auto-configuration report from guide 01, live).
6. **Roles and method security**: `@EnableMethodSecurity`, `@PreAuthorize`.
7. **Packaging and deployment**: `./mvnw package` (or **Maven → Lifecycle → package** in IntelliJ), `java -jar target/todo-app-0.0.1-SNAPSHOT.jar`, and container images with `./mvnw spring-boot:build-image`.

**Further reading**

- [Spring Boot reference: Testing Spring Boot applications](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html)
- [Spring Framework reference: Context caching](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html)
- [Spring Security reference: Testing form-based authentication](https://docs.spring.io/spring-security/reference/servlet/test/mockmvc/authentication.html)

**See also:** [Annotation cheat sheet](annotations-cheatsheet.md) · [Back to the index](README.md)
