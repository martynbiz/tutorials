# 02 — Controllers and Thymeleaf

> Written for **Spring Boot 4.1.2** and **Java 25**. Builds on [01 — Getting started](01-getting-started.md).

## What you'll learn

- What happens between a browser sending a request and Spring returning HTML (the **Spring MVC request cycle**)
- The difference between `@Controller` and `@RestController`
- How to pass data to a template with `Model`
- Thymeleaf's core syntax: `th:text`, `th:each`, `th:if`, `th:href`, and the four expression types
- How to share a layout (head, navbar, scripts) across pages using **fragments**
- How static files (CSS) are served
- `@PathVariable` vs `@RequestParam`, and what `redirect:` does
- **IntelliJ:** reloading Maven after `pom.xml` changes, creating templates, seeing template edits without a restart, inspecting the `Model` in the debugger

By the end you'll have a styled home page and an "About" page sharing one layout, ready for the real features.

---

## Concepts

### The request cycle

When a browser requests `GET /about`:

```
Browser ──► Tomcat ──► DispatcherServlet ──► HandlerMapping: "who handles GET /about?"
                              │
                              ▼
                   AboutController.about(Model model)
                     · puts data into the Model
                     · returns the view name "about"
                              │
                              ▼
                   ViewResolver: "about" → templates/about.html
                              │
                              ▼
                   Thymeleaf renders the template with the Model's data
                              │
                              ▼
Browser ◄────────────── HTML response
```

- **`DispatcherServlet`** is Spring MVC's *front controller*. Every request goes through it. Boot registers it for you.
- **Handler mapping** matches the URL and HTTP method to one of your `@GetMapping`/`@PostMapping` methods.
- Your **controller method** does the work and returns a **view name** (a `String`).
- The **view resolver** turns `"about"` into `classpath:/templates/about.html`. Thymeleaf's auto-configuration sets this up, using the `templates/` prefix and `.html` suffix.
- The **`Model`** is a map of named values the template can read.

### `@Controller` vs `@RestController`

| | `@Controller` | `@RestController` |
|---|---|---|
| Return value means | A **view name** to render | The **response body** itself |
| Typical output | HTML (via Thymeleaf) | JSON / plain text |
| Equivalent to | — | `@Controller` + `@ResponseBody` on every method |

The `HelloController` from guide 01 is a `@RestController`. Everything from here on uses `@Controller`.

### Why Thymeleaf

Thymeleaf templates are **valid HTML files**. All the dynamic behaviour lives in `th:*` attributes, so a template opens fine in a browser even without the server, showing its placeholder text. It is Spring Boot's best-supported server-side template engine, and it integrates with Spring's form binding (guide 04) and Spring Security (guide 04).

### Thymeleaf's four expression types

| Syntax | Name | Example | Meaning |
|---|---|---|---|
| `${...}` | Variable | `${user.email}` | Read from the Model |
| `*{...}` | Selection | `*{email}` | Read from the object chosen by `th:object` (forms, guide 04) |
| `@{...}` | Link URL | `@{/lists/{id}(id=${list.id})}` | Build a URL, with the app's context path and correct encoding |
| `#{...}` | Message | `#{home.title}` | Look up text in `messages.properties` (i18n; not used in this series) |

There are also **fragment expressions**, `~{...}`, used for layouts.

---

## Steps

### 1. Add the Thymeleaf starter

In `pom.xml`, inside `<dependencies>`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-thymeleaf</artifactId>
</dependency>
```

There's no version, because the parent manages it.

**Reload Maven in IntelliJ.** After any `pom.xml` edit, a small floating **Load Maven Changes** button (an "m" with a 🔄) appears at the top right of the editor. Click it, or press <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>O</kbd>, or click 🔄 in the **Maven** tool window. Until you reload, IntelliJ doesn't know about the new library: imports stay red, and running from IntelliJ won't include it. **Every guide that adds a dependency needs this reload.** Restart the app afterwards, because new dependencies need a full restart, not just a DevTools reload.

> **Tip:** in `pom.xml`, press <kbd>Alt</kbd>+<kbd>Insert</kbd> → **Add dependency** to search Maven Central instead of typing XML by hand.

<details>
<summary>Terminal alternative</summary>

`./mvnw spring-boot:run` resolves new dependencies on every start.

</details>

### 2. A home controller

Create `web/HomeController.java`:

```java
package com.example.todoapp.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("appName", "Todo App");
        return "home";
    }
}
```

- `Model model`: Spring sees this parameter type and passes in a fresh model. You don't create it yourself. Spring MVC resolves many parameter types this way: `Model`, `@RequestParam`, `@PathVariable`, `HttpServletRequest`, `Principal` (guide 04) and more.
- `return "home"` names a view, so Spring renders `templates/home.html`.

### 3. A first template

**In IntelliJ:** right-click `src/main/resources/templates` → **New → HTML File**, and name it `home`. Replace the generated content with the template below. (To create a sub-folder such as `fragments`, use **New → Directory**.)

> **Ultimate** understands Thymeleaf: it autocompletes `th:` attributes and model variable names, and lets you <kbd>Ctrl</kbd>+click from `return "home"` in a controller to the template (and from `~{fragments/layout :: head}` to the fragment). The free edition edits templates as plain HTML, which works fine but without the Thymeleaf hints.

Create `src/main/resources/templates/home.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <title>Todo App</title>
</head>
<body>
    <h1 th:text="${appName}">Placeholder title</h1>
    <p>Welcome! Lists and todos are coming soon.</p>
</body>
</html>
```

- `xmlns:th="..."` declares the `th` namespace. Thymeleaf works without it, but your IDE uses it for autocompletion.
- `th:text="${appName}"` **replaces** the element's body with the model value. The text "Placeholder title" only appears if you open the file directly in a browser. This is Thymeleaf's *natural templating*.
- `th:text` **escapes HTML** (so `<script>` becomes `&lt;script&gt;`), which protects you against XSS. `th:utext` (unescaped) exists, but avoid it for anything a user typed.

Restart and open <http://localhost:8080/>. There's no more Whitelabel page. (If you still get one, you're probably missing the Thymeleaf dependency from step 1. See [Common errors](#common-errors).)

**Editing templates while the app runs.** Thymeleaf's cache is off under DevTools, but the app reads templates from the **compiled output** (`target/classes/templates`), not from `src`. After editing an HTML file, press **Build** (<kbd>Ctrl</kbd>+<kbd>F9</kbd>), or rely on the auto-build you set up in guide 01. That copies the file across, and a browser refresh shows the change **without a restart**. (DevTools deliberately doesn't restart for template changes.)

**Look inside the request with the debugger.** Start the app with **Debug**, put a breakpoint on `return "home";` and refresh the page. In the **Variables** pane, expand `model` and you'll see `appName → "Todo App"`. That map is exactly what the template receives. Resume with <kbd>F9</kbd>.

### 4. A shared layout with fragments

Every page needs the same `<head>` (Bootstrap, your CSS), navbar and scripts. Copying them into every template would be painful, so define them once as **fragments**.

Create `templates/fragments/layout.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">

<head th:fragment="head(title)">
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title th:text="|${title} · Todo App|">Todo App</title>
    <link rel="stylesheet"
          href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
    <link rel="stylesheet" th:href="@{/css/app.css}">
</head>

<body>

<nav th:fragment="navbar" class="navbar navbar-expand navbar-dark bg-dark mb-4">
    <div class="container">
        <a class="navbar-brand" th:href="@{/}">Todo App</a>
        <ul class="navbar-nav ms-auto">
            <li class="nav-item"><a class="nav-link" th:href="@{/about}">About</a></li>
        </ul>
    </div>
</nav>

<th:block th:fragment="scripts">
    <script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
</th:block>

</body>
</html>
```

- `th:fragment="head(title)"` defines a fragment called `head` that takes a **parameter**, `title`.
- `|...|` is a **literal substitution**, a neat way to mix text and expressions: `|${title} · Todo App|`.
- `@{/css/app.css}` builds a URL relative to the application root. Always use `@{}` for your own links rather than hard-coding `/...`. It adds the context path if the app is ever deployed under one (e.g. `/todo`), and it adds session IDs and CSRF details where needed.
- `<th:block>` is a Thymeleaf-only element that disappears from the output. It's handy for grouping.

Now rewrite `home.html` to use the layout:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('Home')}"></head>
<body>
<nav th:replace="~{fragments/layout :: navbar}"></nav>

<main class="container">
    <h1 class="display-5" th:text="${appName}">Todo App</h1>
    <p class="lead">Organise your todos into lists.</p>
</main>

<th:block th:replace="~{fragments/layout :: scripts}"></th:block>
</body>
</html>
```

`th:replace="~{fragments/layout :: head('Home')}"` means: replace this whole element with the `head` fragment from `templates/fragments/layout.html`, passing `'Home'` as `title`.

> **Sidebar: `th:replace` vs `th:insert`.** `th:replace` swaps the host element out for the fragment. `th:insert` puts the fragment *inside* the host element. For layouts, `th:replace` is usually what you want.
>
> **Sidebar: the Layout Dialect.** A popular add-on, `thymeleaf-layout-dialect`, flips this around: pages *decorate* a master layout (`layout:decorate`). It's elegant, but it's a separate library with its own concepts. Plain fragments are part of Thymeleaf itself and are enough here.

### 5. Static files

Anything in `src/main/resources/static/` is served at the root URL, **unprocessed**. Create `static/css/app.css`:

```css
body {
    background-color: #f8f9fa;
}

.todo-done {
    text-decoration: line-through;
    color: #6c757d;
}
```

This is served at `/css/app.css`, which is what the layout links to. (`.todo-done` will be used in guide 06.)

### 6. Iteration and conditionals: an About page

Create `web/AboutController.java`:

```java
package com.example.todoapp.web;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AboutController {

    @GetMapping("/about")
    public String about(Model model) {
        model.addAttribute("features", List.of(
                "Register and log in",
                "Create todo lists",
                "Add, tick off and delete todos"));
        model.addAttribute("comingSoon", true);
        return "about";
    }
}
```

And `templates/about.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('About')}"></head>
<body>
<nav th:replace="~{fragments/layout :: navbar}"></nav>

<main class="container">
    <h1>About</h1>

    <ul class="list-group mb-3">
        <li class="list-group-item" th:each="feature, stat : ${features}">
            <span th:text="${stat.count}">1</span>.
            <span th:text="${feature}">A feature</span>
        </li>
    </ul>

    <div class="alert alert-info" th:if="${comingSoon}">
        These features are being built over the next few guides.
    </div>
    <p th:unless="${comingSoon}">Everything is ready!</p>
</main>

<th:block th:replace="~{fragments/layout :: scripts}"></th:block>
</body>
</html>
```

- `th:each="feature, stat : ${features}"` **repeats the element** once per item. `stat` is an optional *status variable* with `index` (0-based), `count` (1-based), `size`, `first`, `last`, `odd` and `even`.
- `th:if` renders the element only when the expression is "truthy". `th:unless` is the opposite.

### 7. Path variables and request parameters

Two ways to get data from a URL:

```java
// GET /greet/Ada          → @PathVariable: part of the path, identifies a resource
@GetMapping("/greet/{name}")
public String greetPath(@PathVariable String name, Model model) { ... }

// GET /search?q=milk      → @RequestParam: a query parameter, optional, filters or modifies
@GetMapping("/search")
public String search(@RequestParam(required = false) String q, Model model) { ... }
```

The rule of thumb: **path variables identify *which* thing** (`/lists/42`), while **request parameters tweak *how*** (`?sort=name&page=2`).

Building those URLs in a template with `@{}`:

```html
<a th:href="@{/greet/{name}(name=${user.name})}">Greet</a>   <!-- /greet/Ada -->
<a th:href="@{/search(q=${term})}">Search</a>                 <!-- /search?q=milk -->
```

Parameters in `(...)` that match a `{placeholder}` fill the path. Any others become query parameters. Everything is URL-encoded for you.

### 8. Redirects

A controller can return `"redirect:/some/path"` instead of a view name. Spring then sends an HTTP **302** telling the browser to request `/some/path`. You'll use this after every form submission from guide 04 onwards (the *Post/Redirect/Get* pattern, explained in guide 05).

---

## Your turn

### Exercise 1: add a footer fragment

Add a `footer` fragment to `layout.html` showing "© 2026 Todo App" in small, muted text, and include it on both pages.

<details>
<summary>Solution</summary>

In `layout.html`:

```html
<footer th:fragment="footer" class="container text-muted small py-4 mt-4 border-top">
    &copy; 2026 Todo App
</footer>
```

In each page, before the scripts:

```html
<footer th:replace="~{fragments/layout :: footer}"></footer>
```

To compute the year rather than hard-code it, Thymeleaf has utility objects such as `#dates` and `#temporals`: `th:text="|© ${#temporals.year(#temporals.createNow())} Todo App|"`.

</details>

### Exercise 2: a greeting page with a path variable

Create `GET /greet/{name}` in a new `@Controller` that renders `templates/greet.html`, showing "Hello, {name}!" in an `<h1>`, using the layout. Reuse `GreetingService` from guide 01 through constructor injection.

<details>
<summary>Solution</summary>

```java
package com.example.todoapp.greeting;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class GreetingPageController {

    private final GreetingService greetingService;

    public GreetingPageController(GreetingService greetingService) {
        this.greetingService = greetingService;
    }

    @GetMapping("/greet/{name}")
    public String greet(@PathVariable String name, Model model) {
        model.addAttribute("message", greetingService.greet(name));
        return "greet";
    }
}
```

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('Greeting')}"></head>
<body>
<nav th:replace="~{fragments/layout :: navbar}"></nav>
<main class="container">
    <h1 th:text="${message}">Hello!</h1>
</main>
<th:block th:replace="~{fragments/layout :: scripts}"></th:block>
</body>
</html>
```

Note that the same `GreetingService` bean is now shared by two controllers. One instance, injected twice.

</details>

### Exercise 3: try the XSS protection

Visit `/greet/<b>bold</b>` (your browser will encode it) and look at the page source. Then temporarily change `th:text` to `th:utext` and compare. Change it back afterwards.

---

## Checkpoint

- <http://localhost:8080/> shows a Bootstrap-styled home page with a dark navbar.
- <http://localhost:8080/about> lists three numbered features and an info box.
- <http://localhost:8080/greet/Ada> says "Hello, Ada!".
- <http://localhost:8080/css/app.css> returns your CSS.

```
src/main/java/com/example/todoapp/
├── TodoAppApplication.java
├── greeting/
│   ├── GreetingPageController.java
│   ├── GreetingService.java
│   └── HelloController.java
└── web/
    ├── AboutController.java
    └── HomeController.java
src/main/resources/
├── application.properties
├── static/css/app.css
└── templates/
    ├── about.html
    ├── greet.html
    ├── home.html
    └── fragments/layout.html
```

---

## Common errors

| Symptom | Cause / fix |
|---|---|
| `TemplateInputException: Error resolving template [home]` | No `templates/home.html`, or the name is misspelt. View names have **no** leading slash and **no** `.html`. |
| Browser shows the literal text `home` | You used `@RestController`, which writes the string as the body. Use `@Controller`. |
| Still a **Whitelabel 404** at `/` even though `HomeController` exists (a breakpoint in `home()` is hit) | Thymeleaf isn't on the classpath: step 1 was skipped, or Maven wasn't reloaded. With no template engine, Spring treats `"home"` as a servlet path and forwards to `/home`, which nothing handles, so you get a 404. Check `pom.xml` contains `spring-boot-starter-thymeleaf`, reload Maven (<kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>O</kbd>), and **restart** the app. |
| `Circular view path [greet]: would dispatch back to the current handler URL` | Usually Thymeleaf isn't on the classpath (Maven not reloaded), so Spring falls back to forwarding to `/greet`. Reload Maven and restart. |
| Template edits don't show | Templates are served from `target/classes`. In IntelliJ press **Build** (<kbd>Ctrl</kbd>+<kbd>F9</kbd>). From the terminal run `./mvnw compile`. Alternatively, while developing, add `spring.thymeleaf.prefix=file:src/main/resources/templates/` so templates are read straight from source. DevTools already turns off Thymeleaf's cache. |
| New dependency "not found" when running from IntelliJ, even though `pom.xml` has it | Maven wasn't reloaded. Click **Load Maven Changes** (<kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>O</kbd>), then restart the app. |
| `SpelEvaluationException: Property or field 'x' cannot be found` | The template refers to a model attribute or property that doesn't exist. Check spelling, and that the object has a public getter (`getX()`). |
| Fragment not found | Check the path in `~{fragments/layout :: navbar}` and that `th:fragment="navbar"` matches exactly. |

---

## Recap

- `DispatcherServlet` routes each request to a controller method. The method fills a `Model` and returns a **view name**, which Thymeleaf renders.
- `@Controller` returns views; `@RestController` returns data.
- Thymeleaf: `${}` reads the model, `@{}` builds links, `th:each`/`th:if` control structure, and `th:text` escapes output.
- Layouts are built from `th:fragment` and `th:replace`.
- `static/` is served as-is; `templates/` is rendered.

**Further reading**

- [Thymeleaf: Using Thymeleaf (tutorial)](https://www.thymeleaf.org/doc/tutorials/3.1/usingthymeleaf.html)
- [Spring Framework reference: Spring Web MVC](https://docs.spring.io/spring-framework/reference/web/webmvc.html)

**Next:** [03 — Data, JPA and relationships](03-data-jpa-and-relationships.md)
