# Todo App — Learn Spring Boot by Building One App

A step-by-step series that teaches Spring Boot by building one small, real application: a **multi-user todo app**. Users register and log in, then manage their own **todo lists**, and each list holds **todos**.

You write all the code yourself. The guides explain the concepts, show focused snippets for anything new, and hand repetitive parts back to you as **Your turn** exercises.

---

## Versions

| Thing | Version | Notes |
|---|---|---|
| Spring Boot | **4.1.2** | Latest GA when these guides were written (3 November 2026) |
| Java | **25** (LTS) | Spring Boot 4 needs at least 17; we use 25 |
| Build tool | Maven, via the bundled `mvnw` wrapper | Nothing to install globally |
| Database | H2 (file-based) | Stored in `./data/` inside the project |
| Views | Thymeleaf + Bootstrap 5.3 (CDN) | |

If [start.spring.io](https://start.spring.io) offers a newer **4.1.x** patch when you begin, pick it. Patch releases only fix bugs, so everything here still applies. If it offers **4.2** or later, most of it will still work, but check the release notes when something doesn't match.

> **Spring Boot 4 vs what you'll find online.** Most blog posts and Stack Overflow answers are for Spring Boot 2 or 3. In Boot 4 (Spring Framework 7, Spring Security 7, Hibernate 7, Jakarta EE 11):
> - `WebSecurityConfigurerAdapter` **no longer exists**. Security is configured with a `SecurityFilterChain` bean and lambdas (guide 04).
> - `@MockBean` is **gone**. Use `@MockitoBean` (guide 07).
> - Spring Boot was split into smaller modules, so some test annotations moved package. For example, `@WebMvcTest` now lives in `org.springframework.boot.webmvc.test.autoconfigure`. If an import from an old answer won't resolve, let your IDE find the class by name.
> - The web starter is `spring-boot-starter-webmvc` (it used to be `spring-boot-starter-web`).
> - Imports use `jakarta.*`, never `javax.*`.
>
> When you're unsure of the exact dependency for your version, open start.spring.io, pick the same dependencies, and click **Explore** to see the generated `pom.xml`. It is always correct for the version selected.

---

## Prerequisites

- Comfortable with core Java: classes, interfaces, generics, lambdas, exceptions, annotations as a language feature.
- **IntelliJ IDEA**, the IDE these guides teach alongside Spring (see below).
- A terminal for the occasional command. IntelliJ has one built in (**View → Tool Windows → Terminal**, or <kbd>Alt</kbd>+<kbd>F12</kbd>).

### IntelliJ IDEA

Every guide shows how to do each step **in IntelliJ** (running, debugging, testing, Maven, the database) and also gives the terminal equivalent, so you learn both.

- Download it from [jetbrains.com/idea](https://www.jetbrains.com/idea/download/).
- **Free features are enough for the whole series.** Some extras are **Ultimate**-only: the *New Project → Spring Boot* wizard, Spring-aware autocompletion in `application.properties` and templates, the Spring tool window, and the Database tool window. They're marked **(Ultimate)** where they come up. As a university user you can get Ultimate free with a [JetBrains educational licence](https://www.jetbrains.com/community/education/#students), and it's worth doing.
- Shortcuts are given for **Windows/Linux**. On macOS, <kbd>Ctrl</kbd> is usually <kbd>⌘</kbd> and <kbd>Alt</kbd> is <kbd>⌥</kbd>. If you forget a shortcut, press <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>A</kbd> (**Find Action**) and type what you want, e.g. "reload maven". It's the most useful shortcut in IntelliJ.
- Menu paths are written like **Run → Debug…**. Settings are under **File → Settings** (<kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>S</kbd>).

You do **not** need prior Spring experience, and you don't need to know any other web framework.

---

## Where things live

```
spring-boot/
├── guides/
│   └── todo-app/          ← you are here (these Markdown files)
└── projects/
    └── todo-app/          ← your Maven project — you generate it in guide 01
```

The guides and your code are kept apart on purpose, so build output (`target/`) and the database file (`data/`) never mix with the Markdown.

---

## The guides

Follow them **in order**. Each guide builds on the code from the one before.

| # | Guide | You'll learn |
|---|---|---|
| 01 | [Getting started](01-getting-started.md) | Spring Initializr, project layout, running with `mvnw`, beans and dependency injection, auto-configuration, `application.properties`, DevTools |
| 02 | [Controllers and Thymeleaf](02-controllers-and-thymeleaf.md) | The Spring MVC request cycle, `@Controller`, `Model`, Thymeleaf syntax, layout fragments, Bootstrap, static files |
| 03 | [Data, JPA and relationships](03-data-jpa-and-relationships.md) | H2 and its console, entities, repositories, `User → TodoList → Todo`, `@OneToMany` / `@ManyToOne`, lazy loading and N+1 |
| 04 | [Registration and login](04-registration-and-login.md) | The Spring Security filter chain, `SecurityFilterChain`, `UserDetailsService`, password hashing, validation, CSRF, login and logout |
| 05 | [CRUD: todo lists](05-crud-todo-lists.md) | Services and `@Transactional`, form binding, `@Valid`, Post/Redirect/Get, flash messages, error pages, data scoped to the current user |
| 06 | [CRUD: todos and ownership](06-crud-todos-and-ownership.md) | Nested resources, ownership checks, IDOR, 404 vs 403, `@Modifying` queries |
| 07 | [Testing: unit and web](07-testing-unit-and-web.md) | JUnit, AssertJ, Mockito, `@WebMvcTest`, `MockMvc`, `@WithMockUser`, CSRF in tests |
| 08 | [Testing: data and integration](08-testing-data-and-integration.md) | `@DataJpaTest`, `@SpringBootTest`, slices vs the full context, context caching, end-to-end ownership tests |
| — | [Annotation cheat sheet](annotations-cheatsheet.md) | Every annotation used in the series, plus common ones you'll meet elsewhere (`@Autowired`, `@Qualifier`, `@Profile`, …) |

---

## How each guide works

Every guide has the same shape:

1. **What you'll learn**: the goal, and what it builds on.
2. **Concepts**: the *why*, before any code.
3. **Steps**: numbered, with focused snippets. New annotations are explained when they first appear.
4. **Your turn**: exercises for you to build yourself. Solutions are hidden in collapsible blocks:

   <details>
   <summary>Solution</summary>

   Like this. Try first, then peek.

   </details>

5. **Checkpoint**: how to run it, what you should see, and the expected file tree.
6. **Common errors**: the messages you're likely to hit, and what they really mean.
7. **Recap**: what you now know, plus further reading.

Boxes like this are **sidebars**. They're useful background, but you can skip them on a first read:

> **Sidebar:** …

---

## Conventions

- **IntelliJ first, terminal second.** Each "run it" or "test it" step shows the IntelliJ way, then a **Terminal alternative**. Both do the same thing.
- **Terminal commands** are written as `./mvnw …`. That works in Git Bash, macOS and Linux. In **PowerShell** (IntelliJ's default terminal on Windows) type `.\mvnw …`; in `cmd.exe` type `mvnw …`.
- **Only run the app one way at a time.** If it's running from IntelliJ, a second start from the terminal fails because port 8080 is taken (and the H2 file may be locked).
- **Base package** is `com.example.todoapp`. File paths in the guides are shortened, so `list/TodoList.java` means `src/main/java/com/example/todoapp/list/TodoList.java`.
- **Code style**: plain Java (no Lombok), constructor injection, Spring's own annotations throughout.

## Deliberately out of scope

PostgreSQL, Docker, Flyway migrations, profiles, roles and admin users, remember-me, password reset and email, OAuth/social login, REST APIs and SPA frontends. Each guide's recap points to where to go next if you want to add one later.
