# 07 — Testing: Unit and Web

> Written for **Spring Boot 4.1.2** and **Java 25**. Builds on [06 — CRUD: todos and ownership](06-crud-todos-and-ownership.md).

## What you'll learn

- The different **kinds** of test in a Spring app, and what each one is for
- **Plain unit tests** with JUnit, AssertJ and Mockito, with no Spring at all
- **Web slice tests** with `@WebMvcTest` and `MockMvc`: routes, views, validation, redirects, status codes
- Testing **security** in the web layer: `@WithMockUser`, CSRF tokens, anonymous access
- `@MockitoBean`, and why the old `@MockBean` no longer works
- Where `@Autowired` *is* the right choice
- **IntelliJ:** generating test classes, running and re-running tests from the gutter, reading failures and diffs in the test runner, **debugging a test**, and **code coverage**

By the end you'll have a fast test suite covering the service rules and the controllers, including the security behaviour.

> Until now you've tested by hand in the browser. Real projects write tests **alongside** features. This series saves them for the end so the testing concepts are taught together, but now that you know how, write the test with the feature.

---

## Concepts

### Four kinds of test

| Kind | Annotation | Starts Spring? | Speed | Best for |
|---|---|---|---|---|
| **Unit** | none (`@ExtendWith(MockitoExtension.class)`) | ❌ | milliseconds | Service logic, rules, edge cases |
| **Web slice** | `@WebMvcTest` | MVC layer only | ~1 s | Controllers: URLs, validation, views, redirects, security rules |
| **Data slice** | `@DataJpaTest` | JPA layer only | ~1–2 s | Custom queries, mappings, relationships (guide 08) |
| **Integration** | `@SpringBootTest` | ✅ everything | seconds | Full flows across all layers (guide 08) |

A **slice** starts only part of the application context. `@WebMvcTest` loads controllers, `@ControllerAdvice`, filters (including Spring Security), converters and Thymeleaf, but **not** your `@Service` or `@Repository` beans. You replace those with **mocks**. Slices start fast and fail for focused reasons.

The usual shape is a **test pyramid**: many unit tests, a fair number of slice tests, and a few integration tests.

### Mocks

A **mock** is a stand-in object whose behaviour you script:

```java
when(userRepository.existsByEmail("ada@example.com")).thenReturn(true);   // stub
...
verify(userRepository, never()).save(any());                               // verify
```

Mockito creates it, you **stub** what it returns, then **verify** how it was called. Mock the things *around* the code under test, never the code itself.

### The test you already have

`src/test/java/com/example/todoapp/TodoAppApplicationTests.java` came from Initializr:

```java
@SpringBootTest
class TodoAppApplicationTests {
    @Test
    void contextLoads() {
    }
}
```

It starts the **whole** app. If any bean fails to wire, it fails. Since guide 03 it checks more than wiring: starting the app runs your Flyway migrations, then Hibernate's `validate`. So this one empty test also fails if a migration has an SQL error, or if an entity and the schema have drifted apart, which makes it worth keeping. Notice it would use your **real `data/` database file**. Guide 08 fixes that; for now, leave it.

---

## Steps

### 1. Test dependencies

Initializr added `spring-boot-starter-test` (JUnit Jupiter, AssertJ, Mockito, Hamcrest, Spring Test) back in guide 01. In Boot 4, the test support for each technology comes as a **matching test starter**. Make sure your `pom.xml` has:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-flyway-test</artifactId>
    <scope>test</scope>
</dependency>
```

(`spring-boot-starter-flyway-test` is the test partner of the Flyway starter from guide 03. Initializr adds it whenever you pick Flyway.)

If any artifact doesn't resolve, open start.spring.io, select Spring Web, Thymeleaf, Spring Data JPA, H2, Flyway Migration and Spring Security, click **Explore**, and copy the `test`-scoped dependencies from there. They're always right for the selected version.

**Load Maven Changes** (<kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>O</kbd>).

### 2. Running tests in IntelliJ

In the **Project** window, `src/test/java` has a **green** folder icon. That marks it as a *test source root*: code there can use test libraries, and it's left out of the app. Always create tests under it, mirroring the package of the class being tested.

There are several ways to run tests. Try each one on `TodoAppApplicationTests`:

| To run… | Do this |
|---|---|
| One test method | Click the green ▶ in the gutter next to the `@Test` method → **Run** |
| A whole test class | Click the ▶ next to the class name, or put the cursor in the class and press <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>F10</kbd> |
| Every test in the project | Right-click `src/test/java` → **Run 'All Tests'** |
| The last test run again | <kbd>Shift</kbd>+<kbd>F10</kbd> (it's now the selected run configuration) |
| A test under the debugger | Gutter ▶ → **Debug** |
| With coverage | Gutter ▶ → **Run … with Coverage** |

The results open in the **Run** tool window's **test runner**:

- A tree of classes and methods, each with ✅ or ❌ and a time. Toggle **Show Passed** (the ✓ icon) to hide the passes and focus on failures.
- Click a test to see its own console output. Click a **stack-trace link** to jump to the failing line.
- For a failed `isEqualTo`, click **<Click to see difference>** to get a side-by-side diff of expected vs actual.
- **Rerun Failed Tests** (the ▶ with a red ✗) runs only what failed. This is a huge time-saver as the suite grows.
- The gutter icon next to each test turns ✅/❌ to show its last result.

**Navigating between code and tests:** in any class, press <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>T</kbd> (**Navigate → Test**). It jumps to the class's test, or offers **Create New Test…** if there isn't one. That dialog picks *JUnit 5* (Jupiter), puts the test in `src/test/java` under the same package, and can generate method stubs. You'll use it in the next step.

<details>
<summary>Terminal alternative</summary>

Run all tests:

```bash
./mvnw test
```

Run one class, or one method:

```bash
./mvnw test -Dtest=UserServiceTest
./mvnw test -Dtest=UserServiceTest#rejectsDuplicateEmail
```

In IntelliJ's **Maven** tool window, **Lifecycle → test** does the same as `./mvnw test`. This is what a CI server runs, so do it before you commit. IntelliJ's own runner and Maven *should* agree, and when they don't, Maven's result is the one that counts.

</details>

### 3. A plain unit test: `UserService`

Open `UserService`, press <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>T</kbd> → **Create New Test…**, check that **Testing library** is *JUnit 5* and the class name is `UserServiceTest`, and click **OK**. IntelliJ creates `src/test/java/com/example/todoapp/user/UserServiceTest.java`. Replace its contents with:

```java
package com.example.todoapp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    UserRepository userRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder);
    }

    @Test
    void registersUserWithNormalisedEmailAndHashedPassword() {
        when(userRepository.existsByEmail("ada@example.com")).thenReturn(false);
        when(passwordEncoder.encode("s3cret-pass")).thenReturn("{bcrypt}hashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User user = userService.register("  Ada@Example.COM ", "s3cret-pass");

        assertThat(user.getEmail()).isEqualTo("ada@example.com");
        assertThat(user.getPasswordHash()).isEqualTo("{bcrypt}hashed");
    }

    @Test
    void neverStoresTheRawPassword() {
        when(passwordEncoder.encode("s3cret-pass")).thenReturn("{bcrypt}hashed");

        userService.register("ada@example.com", "s3cret-pass");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isNotEqualTo("s3cret-pass");
    }

    @Test
    void rejectsDuplicateEmail() {
        when(userRepository.existsByEmail("ada@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.register("ada@example.com", "s3cret-pass"))
                .isInstanceOf(EmailAlreadyRegisteredException.class);

        verify(userRepository, never()).save(any());
    }
}
```

What's going on:

- **No Spring.** There's no `@SpringBootTest` and no context, so it runs in milliseconds. `@ExtendWith(MockitoExtension.class)` simply makes `@Mock` fields work.
- **You build the object under test yourself** with `new UserService(mock, mock)`. That's the pay-off for constructor injection, promised in guide 01: no reflection or container needed.
- **Arrange / Act / Assert**: stub, call, check. Keep that shape and each test reads like a sentence. The method names describe the behaviour, not the method being called.
- `thenAnswer(inv -> inv.getArgument(0))` makes `save` return whatever it was given, which is how a real repository behaves.
- `ArgumentCaptor` grabs the argument passed to a mock so you can assert on it.
- **AssertJ** (`assertThat(...).isEqualTo(...)`) is the assertion library Boot ships. It reads fluently and gives helpful failure messages.
- In `neverStoresTheRawPassword` the `existsByEmail` stub was left out. An unstubbed mock returns the default value (`false` for a `boolean`), which is what we want. Mockito's **strict stubs** (on by default with `MockitoExtension`) fail a test whose stubs are *never used*, which keeps tests honest.

Run the class with the gutter ▶ next to `class UserServiceTest`. All three pass in a few milliseconds each. Notice there's no Spring banner in the output, because no Spring context was started.

> **Typing static imports in IntelliJ:** type `assertThat(` and press <kbd>Alt</kbd>+<kbd>Enter</kbd> → **Import static method…** → choose `org.assertj.core.api.Assertions.assertThat`. **Watch out**: IntelliJ also offers Hamcrest's and JUnit's versions. Pick AssertJ to match the guide.

**Debug a test.** Put a breakpoint inside `UserService.register` on the `passwordEncoder.encode(...)` line, then click the gutter ▶ on `registersUserWithNormalisedEmailAndHashedPassword` → **Debug**. In **Variables**, `normalised` is `"ada@example.com"`, and `passwordEncoder` is a `PasswordEncoder$MockitoMock$…`, the mock standing in for the real encoder. Debugging a failing test is usually quicker than reading the code and guessing.

### 4. A unit test for the ownership rule

`src/test/java/com/example/todoapp/list/TodoListServiceTest.java`:

```java
package com.example.todoapp.list;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.todoapp.common.NotFoundException;
import com.example.todoapp.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class TodoListServiceTest {

    @Mock
    TodoListRepository todoListRepository;

    @Mock
    UserRepository userRepository;

    @InjectMocks
    TodoListService todoListService;

    @Test
    void getOwnedThrowsNotFoundWhenListBelongsToSomeoneElse() {
        when(todoListRepository.findByIdAndOwnerEmail(1L, "mallory@example.com"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoListService.getOwned(1L, "mallory@example.com"))
                .isInstanceOf(NotFoundException.class);
    }
}
```

`@InjectMocks` is Mockito's shortcut for "construct this with the mocks above". It's fine for simple cases, but it fails *silently*, leaving fields null, when constructor arguments don't match. The explicit `new` in `@BeforeEach` from step 2 is more robust. Both styles are common, so recognise them.

This test proves the service throws when the repository finds nothing. It **doesn't** prove that `findByIdAndOwnerEmail` actually filters by owner. That's a query, and only a **data slice test** against a real database can check it (guide 08). Knowing what each kind of test can and can't prove is the main skill here.

### 5. A web slice test: `TodoListController`

`src/test/java/com/example/todoapp/list/TodoListControllerTest.java`:

```java
package com.example.todoapp.list;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.todoapp.common.NotFoundException;
import com.example.todoapp.security.SecurityConfig;
import com.example.todoapp.todo.TodoService;

@WebMvcTest(TodoListController.class)
@Import(SecurityConfig.class)
class TodoListControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TodoListService todoListService;

    @MockitoBean
    TodoService todoService;

    @Test
    void anonymousUsersAreSentToLogin() throws Exception {
        mockMvc.perform(get("/lists"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = "alice@example.com")
    void indexShowsTheCurrentUsersLists() throws Exception {
        when(todoListService.findSummaries("alice@example.com"))
                .thenReturn(List.of(new TodoListSummary(1L, "Groceries", 2L)));

        mockMvc.perform(get("/lists"))
                .andExpect(status().isOk())
                .andExpect(view().name("lists/index"))
                .andExpect(model().attributeExists("lists"));
    }

    @Test
    @WithMockUser(username = "alice@example.com")
    void createRejectsBlankName() throws Exception {
        mockMvc.perform(post("/lists").param("name", "  ").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("lists/form"))
                .andExpect(model().attributeHasFieldErrors("form", "name"));

        verify(todoListService, never()).create(anyString(), anyString());
    }

    @Test
    @WithMockUser(username = "alice@example.com")
    void createRedirectsToTheNewList() throws Exception {
        TodoList created = mock(TodoList.class);
        when(created.getId()).thenReturn(7L);
        when(created.getName()).thenReturn("Holiday");
        when(todoListService.create("Holiday", "alice@example.com")).thenReturn(created);

        mockMvc.perform(post("/lists").param("name", "Holiday").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lists/7"))
                .andExpect(flash().attributeExists("success"));
    }

    @Test
    @WithMockUser
    void postWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/lists").param("name", "Holiday"))
                .andExpect(status().isForbidden());

        verify(todoListService, never()).create(any(), any());
    }

    @Test
    @WithMockUser(username = "mallory@example.com")
    void showingSomeoneElsesListIs404() throws Exception {
        when(todoListService.getOwned(eq(1L), eq("mallory@example.com")))
                .thenThrow(new NotFoundException("Todo list 1 not found"));

        mockMvc.perform(get("/lists/1"))
                .andExpect(status().isNotFound());
    }
}
```

Taking it apart:

- **`@WebMvcTest(TodoListController.class)`** starts the MVC slice with **only this controller**. Without the argument, it loads *all* controllers, and then every one of their dependencies needs a mock.
- **`@Import(SecurityConfig.class)`**: the slice includes Spring Security, but a plain `@Configuration` class like ours isn't picked up automatically. Importing it means the tests run against **your** rules (login page, `permitAll` list), not Boot's defaults. Without this line, the anonymous test would still get a redirect, but to Boot's default setup, and you'd be testing the wrong configuration.
- **`@Autowired MockMvc mockMvc`**: here's `@Autowired`, as promised in guide 01. JUnit creates test instances, not Spring, so you can't rely on Spring's constructor injection the way you do in beans. Field injection with `@Autowired` is the standard idiom in test classes. (Constructor injection is possible in tests, but it needs `@Autowired` on the constructor anyway.)
- **`MockMvc`** sends requests through the full Spring MVC stack (filters, security, binding, validation, view resolution) **without a real HTTP server**. It's fast, and you can inspect the model and view name, which a real browser can't see.
- **`@MockitoBean`** puts a Mockito mock into the Spring context in place of the real bean. Both services must be mocked because `TodoListController`'s constructor needs both (since guide 06). In Boot 3.4+ this **replaces `@MockBean`**, which was removed in Boot 4.
- **`@WithMockUser(username = "alice@example.com")`** runs the test as an authenticated user with that username. In your controller, `@AuthenticationPrincipal UserDetails` receives a user whose `getUsername()` is `alice@example.com`. Nothing touches the database. `@WithMockUser` with no arguments uses username `user`.
- **`.with(csrf())`** adds a valid CSRF token to the request. **Every** POST needs it, just like in the browser. The `postWithoutCsrfTokenIsForbidden` test proves the protection is switched on.
- **The 404 test** checks the controller's side of the ownership contract: when the service says "not found", the user gets a 404. Together with the unit test and guide 08's query test, the whole chain is covered.

> **Sidebar: `MockMvcTester`.** Spring Framework 6.2+ also offers `MockMvcTester`, an AssertJ-style API:
> ```java
> assertThat(mvc.get().uri("/lists")).hasStatusOk().hasViewName("lists/index");
> ```
> It's newer and pleasant to use. We use the classic `perform(...).andExpect(...)` style because it's what you'll find in nearly every codebase and answer online.

Run the class from the gutter ▶ (or `./mvnw test -Dtest=TodoListControllerTest`). This time you'll see a Spring banner in the output, because the slice context starts, but it's still only a second or two.

> **See what MockMvc did:** add `.andDo(print())` after `perform(...)` (`import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;`). Then click the test in the runner tree to see the full request, the handler that served it, the model, the view name and the response, all in that test's console output.

**Make a test fail on purpose.** Change `redirectedUrl("/lists/7")` to `"/lists/8"` and run just that method from its gutter icon. In the test runner, read the failure message (`Redirected URL expected:</lists/8> but was:</lists/7>`), click the stack-trace link to jump to the assertion, then put it back and press **Rerun Failed Tests**. Get into the habit: a test you've never seen fail might not be testing anything.

### 6. Code coverage

Right-click `src/test/java` → **More Run/Debug → Run 'All Tests' with Coverage**. When it finishes:

- The **Coverage** tool window lists the percentage of classes, methods and lines covered, per package.
- In the editor, the gutter shows a **green** bar next to lines the tests executed and a **red** bar next to lines they never reached. Open `TodoListController` and look at which handlers are still red.

Coverage tells you what was **run**, not what was **checked**. A test with no assertions still "covers" lines. Use it to find untested code, not as a score to maximise. (Maven's equivalent is the JaCoCo plugin, which is outside this series.)

---

## Your turn

### Exercise 1: unit test `TodoService.toggle`

Write `TodoServiceTest` with two tests:

1. `toggle` flips `done` from `false` to `true` on an owned todo.
2. `toggle` throws `NotFoundException` when `findOwned` returns empty.

<details>
<summary>Hint</summary>

`Todo` has a public constructor, `new Todo("Milk", null)`, so you can use a **real** entity as the stubbed return value. There's no need to mock it. Stub `todoRepository.findOwned(todoId, listId, email)` (note the argument order).

</details>

<details>
<summary>Solution</summary>

```java
package com.example.todoapp.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.todoapp.common.NotFoundException;
import com.example.todoapp.list.TodoListService;

@ExtendWith(MockitoExtension.class)
class TodoServiceTest {

    @Mock TodoRepository todoRepository;
    @Mock TodoListService todoListService;

    TodoService todoService;

    @BeforeEach
    void setUp() {
        todoService = new TodoService(todoRepository, todoListService);
    }

    @Test
    void toggleMarksAnOpenTodoAsDone() {
        Todo todo = new Todo("Milk", null);
        when(todoRepository.findOwned(5L, 1L, "alice@example.com")).thenReturn(Optional.of(todo));

        todoService.toggle(1L, 5L, "alice@example.com");

        assertThat(todo.isDone()).isTrue();
    }

    @Test
    void toggleRefusesTodosTheUserDoesNotOwn() {
        when(todoRepository.findOwned(5L, 1L, "mallory@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.toggle(1L, 5L, "mallory@example.com"))
                .isInstanceOf(NotFoundException.class);
    }
}
```

Note that there's no `save` to verify: in production, dirty checking writes the change. In a unit test, checking the entity's state is the right assertion.

</details>

### Exercise 2: web slice test for registration

Write `RegistrationControllerTest` covering:

1. `GET /register` is accessible **anonymously** and renders `register`.
2. Mismatched passwords re-render the form with an error on `confirmPassword`, and the service is never called.
3. A valid submission redirects to `/login?registered`.
4. A duplicate email (the service throws `EmailAlreadyRegisteredException`) re-renders with an error on `email`.

<details>
<summary>Solution</summary>

```java
package com.example.todoapp.user;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.todoapp.security.SecurityConfig;

@WebMvcTest(RegistrationController.class)
@Import(SecurityConfig.class)
class RegistrationControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean UserService userService;

    @Test
    void registrationPageIsPublic() throws Exception {
        mockMvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(view().name("register"));
    }

    @Test
    void mismatchedPasswordsAreRejected() throws Exception {
        mockMvc.perform(post("/register").with(csrf())
                        .param("email", "ada@example.com")
                        .param("password", "password123")
                        .param("confirmPassword", "password124"))
                .andExpect(status().isOk())
                .andExpect(view().name("register"))
                .andExpect(model().attributeHasFieldErrors("form", "confirmPassword"));

        verify(userService, never()).register(anyString(), anyString());
    }

    @Test
    void validRegistrationRedirectsToLogin() throws Exception {
        mockMvc.perform(post("/register").with(csrf())
                        .param("email", "ada@example.com")
                        .param("password", "password123")
                        .param("confirmPassword", "password123"))
                .andExpect(redirectedUrl("/login?registered"));

        verify(userService).register("ada@example.com", "password123");
    }

    @Test
    void duplicateEmailShowsAnError() throws Exception {
        when(userService.register("ada@example.com", "password123"))
                .thenThrow(new EmailAlreadyRegisteredException("ada@example.com"));

        mockMvc.perform(post("/register").with(csrf())
                        .param("email", "ada@example.com")
                        .param("password", "password123")
                        .param("confirmPassword", "password123"))
                .andExpect(view().name("register"))
                .andExpect(model().attributeHasFieldErrors("form", "email"));
    }
}
```

If you did guide 04's Exercise 2 (logged-in users are redirected away from `/register`), add a `@WithMockUser` test for that too.

</details>

### Exercise 3: web slice test for the todo controller

For `TodoController`, test that `POST /lists/1/todos/5/toggle`:

1. redirects to `/lists/1` for the owner, **and** verifies `todoService.toggle(1L, 5L, "alice@example.com")` was called;
2. returns 404 when the service throws `NotFoundException`.

Which beans need `@MockitoBean`? Look at the constructor.

<details>
<summary>Solution</summary>

`TodoController` needs `TodoService` **and** `TodoListService`, so mock both.

```java
@WebMvcTest(TodoController.class)
@Import(SecurityConfig.class)
class TodoControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean TodoService todoService;
    @MockitoBean TodoListService todoListService;

    @Test
    @WithMockUser(username = "alice@example.com")
    void toggleRedirectsBackToTheList() throws Exception {
        mockMvc.perform(post("/lists/1/todos/5/toggle").with(csrf()))
                .andExpect(redirectedUrl("/lists/1"));

        verify(todoService).toggle(1L, 5L, "alice@example.com");
    }

    @Test
    @WithMockUser(username = "mallory@example.com")
    void toggleOnSomeoneElsesTodoIs404() throws Exception {
        doThrow(new NotFoundException("nope"))
                .when(todoService).toggle(1L, 5L, "mallory@example.com");

        mockMvc.perform(post("/lists/1/todos/5/toggle").with(csrf()))
                .andExpect(status().isNotFound());
    }
}
```

`toggle` returns `void`, so you can't write `when(...).thenThrow(...)`. Use `doThrow(...).when(mock).method(...)` (`import static org.mockito.Mockito.doThrow;`).

</details>

---

## Checkpoint

Right-click `src/test/java` → **Run 'All Tests'**. Everything is green in the test runner, and the unit tests finish almost instantly. Each `@WebMvcTest` class takes a second or so to start its slice. Then run **Maven → Lifecycle → test** (or `./mvnw test`) and confirm Maven agrees: `Tests run: 17, Failures: 0, Errors: 0`, give or take, depending on how many exercises you did.

```
src/test/java/com/example/todoapp/
├── TodoAppApplicationTests.java
├── list/
│   ├── TodoListControllerTest.java
│   └── TodoListServiceTest.java
├── todo/
│   ├── TodoControllerTest.java
│   └── TodoServiceTest.java
└── user/
    ├── RegistrationControllerTest.java
    └── UserServiceTest.java
```

---

## Common errors

| Symptom | Cause / fix |
|---|---|
| `NoSuchBeanDefinitionException: No qualifying bean of type 'TodoService'` in a `@WebMvcTest` | The slice doesn't load services. Add `@MockitoBean` for **every** constructor dependency of the controller under test. |
| `cannot find symbol: MockBean` | Removed in Boot 4. Use `@MockitoBean` from `org.springframework.test.context.bean.override.mockito`. |
| `cannot find symbol: WebMvcTest` from `org.springframework.boot.test.autoconfigure.web.servlet` | That's the Boot 3 package. In Boot 4 it's `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`, from the `spring-boot-starter-webmvc-test` dependency. |
| POST tests return **403** | Missing `.with(csrf())`. |
| Anonymous test gets **401** instead of a redirect to `/login` | `SecurityConfig` isn't imported, so Boot's default configuration is used. Add `@Import(SecurityConfig.class)`. |
| `UnnecessaryStubbingException` | A `when(...)` stub was never used by the test. Remove it, or the code path isn't what you think. |
| `NullPointerException` on a field in a Mockito test | Missing `@ExtendWith(MockitoExtension.class)`, or `@InjectMocks` couldn't match the constructor. Construct the object yourself. |
| `@WithMockUser` user isn't the one the controller sees | Check `username = "..."` matches the email your stubs expect. With no username it's `user`. |
| `contextLoads` fails: database locked / unexpected data | It's using the real `data/` file (and the app may be running). Fixed in guide 08. In the meantime, stop the app before running all tests. |
| `contextLoads` fails with `Schema validation: ...` or a Flyway error | The same failure the app would have at start-up: a migration with an error, or an entity that doesn't match the schema. Start the app to see the same message, and fix it with a migration (guide 03's Common errors). |
| No green ▶ next to a test / "No tests were found" | The class is outside `src/test/java` (the folder must be green), the method isn't annotated with JUnit 5's `org.junit.jupiter.api.Test` (check the import: not `org.junit.Test`), or Maven wasn't reloaded after adding test dependencies. |
| Passes in IntelliJ, fails with `./mvnw test` (or the reverse) | They compile separately. Usually Maven wasn't reloaded, or the IntelliJ Project SDK differs from `JAVA_HOME`. **Build → Rebuild Project**, reload Maven, and check both JDKs are 25. |

---

## Recap

- **Unit tests**: `new` the class, mock its collaborators with Mockito, assert with AssertJ. They're the fastest feedback, and constructor injection makes them easy.
- **`@WebMvcTest(SomeController.class)`** loads only the MVC layer. Mock services with **`@MockitoBean`**, and `@Import` your `SecurityConfig` so your real rules apply.
- **`MockMvc`** tests status, view, model, redirects and flash attributes without a server.
- **Security in tests**: `@WithMockUser` for an authenticated user, `.with(csrf())` on every POST, and a test with *no* user to prove protection works.
- `@Autowired` field injection is the norm in test classes.
- Each test level proves different things. The ownership rule needs a unit test (the service throws), a web test (it becomes a 404) **and** a data test (the query filters), which comes next.
- **IntelliJ:** <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>T</kbd> creates or jumps to tests; the gutter ▶ runs or debugs one test or a class; **Rerun Failed Tests**, **<Click to see difference>** and **Run with Coverage** are your daily tools. Use Maven's `test` goal as the final word.

**Further reading**

- [Spring Boot reference: Testing](https://docs.spring.io/spring-boot/reference/testing/index.html)
- [Spring Framework reference: MockMvc](https://docs.spring.io/spring-framework/reference/testing/mockmvc.html)
- [Spring Security reference: Testing](https://docs.spring.io/spring-security/reference/servlet/test/index.html)
- [Mockito documentation](https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html)

**Next:** [08 — Testing: data and integration](08-testing-data-and-integration.md)
