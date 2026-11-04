# 05 — CRUD: Todo Lists

> Written for **Spring Boot 4.1.2** and **Java 25**. Builds on [04 — Registration and login](04-registration-and-login.md).

## What you'll learn

- Why apps have a **service layer**, and what `@Transactional` really does (proxies, commit/rollback, `readOnly`)
- **Dirty checking**: updating an entity without calling `save()`
- The full **CRUD** cycle in Spring MVC: list, show, create, edit, update, delete
- **Post/Redirect/Get**, and **flash attributes** for one-off success messages
- Turning "not found" into a proper **404** with `@ResponseStatus`, and custom **error pages**
- Scoping every query to the **current user**
- Your first real **schema change**: a Flyway `V2` migration that adds a `NOT NULL` column to a table that already has rows, and what to do when a migration fails
- **IntelliJ:** seeing the `@Transactional` **proxy** and **dirty checking** in the debugger, and navigating between controllers, services and templates

By the end, each user can create, view, rename and delete **their own** todo lists, newest first, and can't see anybody else's.

---

## Concepts

### Controller → Service → Repository

```
┌────────────────┐     ┌────────────────────┐     ┌─────────────────────┐
│ Controller     │ ──► │ Service            │ ──► │ Repository          │
│ HTTP in/out:   │     │ business rules,    │     │ queries only        │
│ params, forms, │     │ transactions,      │     │ (Spring Data)       │
│ views,redirects│     │ ownership checks   │     │                     │
└────────────────┘     └────────────────────┘     └─────────────────────┘
```

Why not call the repository straight from the controller?

- **Transactions** belong around a *unit of business work* ("rename a list"), which can involve several repository calls.
- **Rules live in one place.** "A user may only touch their own lists" is enforced in the service, so *every* caller gets it, whether a controller, a future REST API or a scheduled job.
- **Testing.** Services are plain classes you can unit test with mocks (guide 07).

### `@Transactional` in depth

```java
@Service
public class TodoListService {
    @Transactional
    public void rename(Long id, String name, String email) { ... }
}
```

At start-up, Spring wraps the bean in a **proxy**. Callers get the proxy, not your object. When `rename` is called through it:

1. The proxy **begins** a transaction (and opens a persistence context).
2. It calls your real method.
3. If the method returns normally, it **commits**: Hibernate flushes changes (dirty checking) and the database commits.
4. If the method throws a **`RuntimeException`**, it **rolls back**. (By default, *checked* exceptions don't trigger a rollback. That surprises people.)

Two consequences of the proxy design to remember:

- **Self-invocation bypasses the proxy.** If method `a()` calls `this.b()` in the same class, `b`'s `@Transactional` settings are *ignored*, because the call never went through the proxy. That's fine if `a` is already transactional (`b` simply joins `a`'s transaction), but a `@Transactional` method called from a non-transactional method in the same class gets no transaction at all.
- The method must be **public** and called from **outside** the bean.

`@Transactional(readOnly = true)` hints to Hibernate that nothing will change, so it skips dirty checking, which is a small optimisation. A common pattern is to put `readOnly = true` on the class and override it with a plain `@Transactional` on the methods that write.

### Post/Redirect/Get (PRG)

If a POST handler renders a page directly and the user presses **F5**, the browser re-submits the POST, creating a duplicate list. With PRG:

```
POST /lists          ──► 302 Location: /lists/7     (redirect)
GET  /lists/7        ──► 200 HTML                   (refresh-safe)
```

Refreshing now only repeats the harmless GET. **Rule:** after a successful POST, always redirect. The exception is a validation failure, where you re-render the form (nothing was saved, so re-submitting is fine).

### Flash attributes

A redirect starts a *new* request, so model attributes are lost. **Flash attributes** survive exactly one redirect: Spring stores them in the session, adds them to the next request's model, then deletes them. They're perfect for "List created" messages.

### URL design

| Method | URL | Action |
|---|---|---|
| GET | `/lists` | Index: all my lists |
| GET | `/lists/new` | Show the create form |
| POST | `/lists` | Create |
| GET | `/lists/{id}` | Show one list |
| GET | `/lists/{id}/edit` | Show the edit form |
| POST | `/lists/{id}` | Update |
| POST | `/lists/{id}/delete` | Delete |

HTML forms can only send GET and POST, so update and delete are POSTs to distinct URLs.

> **Sidebar: PUT and DELETE from HTML forms.** Set `spring.mvc.hiddenmethod.filter.enabled=true` and add `<input type="hidden" name="_method" value="delete">` to a POST form. Spring then treats it as a `DELETE`, so you could use `@DeleteMapping("/lists/{id}")`. It's a matter of taste. We use plain POSTs because they're explicit and need no extra configuration.

---

## Steps

### 1. A "not found" exception

Create `common/NotFoundException.java`:

```java
package com.example.todoapp.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
```

When this exception escapes a controller, `@ResponseStatus` makes Spring respond with **404**. It's a `RuntimeException`, so it also rolls back any transaction it passes through.

### 2. The service

Create `list/TodoListService.java`:

```java
package com.example.todoapp.list;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.todoapp.common.NotFoundException;
import com.example.todoapp.user.User;
import com.example.todoapp.user.UserRepository;

@Service
@Transactional(readOnly = true)
public class TodoListService {

    private final TodoListRepository todoListRepository;
    private final UserRepository userRepository;

    public TodoListService(TodoListRepository todoListRepository, UserRepository userRepository) {
        this.todoListRepository = todoListRepository;
        this.userRepository = userRepository;
    }

    public List<TodoListSummary> findSummaries(String ownerEmail) {
        return todoListRepository.findSummariesByOwnerEmail(ownerEmail);
    }

    /** Returns the list only if it belongs to ownerEmail; otherwise behaves as if it doesn't exist. */
    public TodoList getOwned(Long id, String ownerEmail) {
        return todoListRepository.findByIdAndOwnerEmail(id, ownerEmail)
                .orElseThrow(() -> new NotFoundException("Todo list " + id + " not found"));
    }

    @Transactional
    public TodoList create(String name, String ownerEmail) {
        User owner = userRepository.findByEmail(ownerEmail)
                .orElseThrow(() -> new NotFoundException("User " + ownerEmail + " not found"));
        return todoListRepository.save(new TodoList(name.trim(), owner));
    }
}
```

What to notice:

- **Every method takes the owner's email.** There's deliberately no `getById(id)` that ignores ownership, so nobody can accidentally call one.
- `getOwned` uses `findByIdAndOwnerEmail`. A list that exists but belongs to someone else gives the **same 404** as one that doesn't exist, which avoids revealing that ID 7 exists.
- The class is `readOnly`; `create` overrides it to read-write.

### 3. The form object

`list/TodoListForm.java`:

```java
package com.example.todoapp.list;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class TodoListForm {

    @NotBlank(message = "Please give the list a name")
    @Size(max = 100, message = "Keep it under 100 characters")
    private String name;

    public TodoListForm() {
    }

    public TodoListForm(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
```

The `@Size(max = 100)` matches the entity's `@Column(length = 100)`. Without it, a 101-character name would pass validation and then blow up with a database error.

### 4. The controller: index, new, create, show

Create `list/TodoListController.java`:

```java
package com.example.todoapp.list;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.validation.Valid;

@Controller
@RequestMapping("/lists")
public class TodoListController {

    private final TodoListService todoListService;

    public TodoListController(TodoListService todoListService) {
        this.todoListService = todoListService;
    }

    @GetMapping
    public String index(@AuthenticationPrincipal UserDetails currentUser, Model model) {
        model.addAttribute("lists", todoListService.findSummaries(currentUser.getUsername()));
        return "lists/index";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("form", new TodoListForm());
        return "lists/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") TodoListForm form,
                         BindingResult result,
                         @AuthenticationPrincipal UserDetails currentUser,
                         RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            return "lists/form";
        }
        TodoList list = todoListService.create(form.getName(), currentUser.getUsername());
        redirectAttributes.addFlashAttribute("success", "List \"" + list.getName() + "\" created");
        return "redirect:/lists/" + list.getId();
    }

    @GetMapping("/{id}")
    public String show(@PathVariable Long id,
                       @AuthenticationPrincipal UserDetails currentUser,
                       Model model) {
        model.addAttribute("list", todoListService.getOwned(id, currentUser.getUsername()));
        return "lists/show";
    }
}
```

- `@RequestMapping("/lists")` on the class sets a **prefix** for every method. A bare `@GetMapping` is `GET /lists`, and `@GetMapping("/{id}")` is `GET /lists/{id}`.
- `currentUser` can't be `null` here, because `SecurityConfig` requires a login for everything under `/lists`.
- `@PathVariable Long id`: Spring converts `"7"` to `7L`. A non-number (`/lists/abc`) gives a 400 Bad Request automatically.
- `"lists/index"` is a view in a sub-folder: `templates/lists/index.html`.
- Note the **parameter order** in `create`: `form`, then *its* `BindingResult` immediately after, then anything else.

### 5. Templates

First, add **flash message** display to the layout so every page can show it. In `fragments/layout.html`, add a new fragment:

```html
<div th:fragment="flash" class="container">
    <div class="alert alert-success alert-dismissible fade show" th:if="${success}" role="alert">
        <span th:text="${success}">Saved</span>
        <button type="button" class="btn-close" data-bs-dismiss="alert" aria-label="Close"></button>
    </div>
</div>
```

`templates/lists/index.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('My lists')}"></head>
<body>
<nav th:replace="~{fragments/layout :: navbar}"></nav>
<div th:replace="~{fragments/layout :: flash}"></div>

<main class="container">
    <div class="d-flex justify-content-between align-items-center mb-3">
        <h1 class="h3 mb-0">My lists</h1>
        <a class="btn btn-primary" th:href="@{/lists/new}">New list</a>
    </div>

    <div class="list-group" th:unless="${#lists.isEmpty(lists)}">
        <a class="list-group-item list-group-item-action d-flex justify-content-between align-items-center"
           th:each="list : ${lists}"
           th:href="@{/lists/{id}(id=${list.id()})}">
            <span th:text="${list.name()}">Groceries</span>
            <span class="badge text-bg-secondary rounded-pill" th:text="${list.todoCount()}">3</span>
        </a>
    </div>

    <p class="text-muted" th:if="${#lists.isEmpty(lists)}">
        You don't have any lists yet. Create your first one!
    </p>
</main>

<th:block th:replace="~{fragments/layout :: scripts}"></th:block>
</body>
</html>
```

- `${list.id()}`: `lists` holds `TodoListSummary` **records**, whose accessors are `id()` and `name()`, not `getId()`. Calling the method explicitly always works. (Depending on versions, `${list.id}` may also resolve for records, but the explicit call is unambiguous.)
- `#lists` is a Thymeleaf **utility object** (`#lists.isEmpty`, `#strings.abbreviate`, `#temporals.format`, …).

`templates/lists/form.html` is used for both create **and** edit:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head(${listId} == null ? 'New list' : 'Edit list')}"></head>
<body>
<nav th:replace="~{fragments/layout :: navbar}"></nav>

<main class="container" style="max-width: 560px">
    <h1 class="h3 mb-3" th:text="${listId} == null ? 'New list' : 'Rename list'">New list</h1>

    <form th:action="${listId} == null ? @{/lists} : @{/lists/{id}(id=${listId})}"
          th:object="${form}" method="post" novalidate>
        <div class="mb-3">
            <label for="name" class="form-label">Name</label>
            <input type="text" th:field="*{name}" class="form-control" autofocus
                   th:classappend="${#fields.hasErrors('name')} ? 'is-invalid'">
            <div class="invalid-feedback" th:errors="*{name}">Name error</div>
        </div>
        <button type="submit" class="btn btn-primary">Save</button>
        <a class="btn btn-link" th:href="${listId} == null ? @{/lists} : @{/lists/{id}(id=${listId})}">Cancel</a>
    </form>
</main>

<th:block th:replace="~{fragments/layout :: scripts}"></th:block>
</body>
</html>
```

The trick is a `listId` model attribute: absent (`null`) means "create", present means "edit". The `th:action` and labels switch on it.

`templates/lists/show.html` (todos arrive in guide 06):

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head(${list.name})}"></head>
<body>
<nav th:replace="~{fragments/layout :: navbar}"></nav>
<div th:replace="~{fragments/layout :: flash}"></div>

<main class="container">
    <a th:href="@{/lists}" class="small">&larr; All lists</a>

    <div class="d-flex justify-content-between align-items-center my-3">
        <h1 class="h3 mb-0" th:text="${list.name}">Groceries</h1>
        <div>
            <!-- Your turn: Edit and Delete buttons go here -->
        </div>
    </div>

    <p class="text-muted">Todos are coming in guide 06.</p>
</main>

<th:block th:replace="~{fragments/layout :: scripts}"></th:block>
</body>
</html>
```

> **OSIV is off, remember.** This template reads `list.name` and `list.id`, which are plain columns already loaded. If it touched `list.owner.email` or `list.todos`, you'd get a `LazyInitializationException` *while rendering*, because the transaction closed when `getOwned` returned. That's the point of turning OSIV off: the service must load everything the view needs.

### 6. Send users to their lists after login

In `SecurityConfig`, change:

```java
.defaultSuccessUrl("/lists", true)
```

and add a "My lists" link to the navbar's authenticated block:

```html
<li class="nav-item"><a class="nav-link" th:href="@{/lists}">My lists</a></li>
```

### 7. A friendly 404 page

Spring Boot's error handling looks for templates named after the status code. Create `templates/error/404.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('Not found')}"></head>
<body>
<nav th:replace="~{fragments/layout :: navbar}"></nav>
<main class="container text-center py-5">
    <h1 class="display-4">404</h1>
    <p class="lead">We couldn't find that page.</p>
    <a class="btn btn-primary" th:href="@{/lists}">Back to my lists</a>
</main>
</body>
</html>
```

You can also add `error/403.html`, `error/500.html`, or a catch-all `error/4xx.html`. This works because `/error` is `permitAll()` in `SecurityConfig`.

Try it: log in and visit `/lists/9999`.

### 8. See the transaction proxy in the debugger

The Concepts section said callers get a **proxy**, not your object. You can see it:

1. Put a breakpoint on the `todoListService.create(...)` line in `TodoListController.create`.
2. Run with **Debug** and create a list in the browser.
3. In **Variables**, expand `this` and look at the `todoListService` field. Its class isn't `TodoListService`: it's something like `TodoListService$$SpringCGLIB$$0`. That's the generated subclass that starts and commits transactions around your methods.
4. Press **Force Step Into** (<kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>F7</kbd>). Plain Step Into skips library code, but Force Step Into enters the proxy, where you'll pass through `CglibAopProxy` and `TransactionInterceptor` before reaching your method. Press **Step Out** a few times to get back.
5. Back in your `create` method, step over `todoListRepository.save(...)` and watch the console: the `insert` appears **immediately**. That's because `IDENTITY` keys force Hibernate to insert straight away to get the ID. Other changes wait until commit, as you'll see in Exercise 1.

> **Navigating in IntelliJ:** <kbd>Ctrl</kbd>+<kbd>B</kbd> (or <kbd>Ctrl</kbd>+click) goes to a declaration, <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>B</kbd> goes to implementations, <kbd>Alt</kbd>+<kbd>F7</kbd> **finds usages**, and <kbd>Ctrl</kbd>+<kbd>E</kbd> lists recent files. **(Ultimate)** adds <kbd>Ctrl</kbd>+click from a view name like `"lists/index"` to its template, and the **Endpoints** tool window, which lists every URL mapping in the app.

### 9. Your first schema change: newest lists first

A feature request arrives: *"Show my newest lists at the top."* Lists are sorted by name at the moment, and the database doesn't record when a list was created. You need a new column, `todo_list.created_at`.

This is where Flyway earns its place. Your `data/todo.mv.db` already contains rows: the demo lists, and any you created while trying out steps 4–8. A production database would contain thousands. The change has to work **on a table that already has data**, without deleting anything.

> **Don't delete `data/` during this step.** The point is to watch a migration run against existing rows, as it would in production. If your lists page is empty, create a couple of lists first.

#### 9a. Change the entity first

In `TodoList`, add a field, set it in the constructor, and add a getter:

```java
@Column(name = "created_at", nullable = false)
private Instant createdAt;

public TodoList(String name, User owner) {
    this.name = name;
    this.owner = owner;
    this.createdAt = Instant.now();
}

public Instant getCreatedAt() { return createdAt; }
```

(`import java.time.Instant;`)

Restart the app. It **refuses to start**:

```
Schema validation: missing column [created_at] in table [todo_list]
```

That's `ddl-auto=validate` catching the drift between code and database, before a single request could fail. With `ddl-auto=update`, Hibernate would have quietly altered the table instead. Here, *you* decide how the column gets added.

#### 9b. The obvious migration, and why it fails

Create `src/main/resources/db/migration/V2__add_todo_list_created_at.sql` with what looks like the obvious change:

```sql
alter table todo_list add column created_at timestamp(6) with time zone not null;
```

Restart. Flyway tries it and fails:

```
Migrating schema "PUBLIC" to version "2 - add todo list created at"
Migration of schema "PUBLIC" to version "2 - add todo list created at" failed! Please restore backups and roll back database and code!
Message    : NULL not allowed for column "CREATED_AT"
```

Think about what the database was asked to do: add a column that must never be null to a table whose existing rows have no value for it. It can't, so it refuses. On an empty table (in a test, say), this migration works perfectly, which is exactly why this bug reaches production in real projects.

Now fix the migration and restart again. It **still** won't start:

```
Validate failed: Migrations have failed validation
Detected failed migration to version 2 (add todo list created at).
```

Flyway recorded the failed attempt in `flyway_schema_history` (with `success = FALSE`), and it won't carry on until someone confirms the database is in a known state. That someone is you.

> **Why did the failure get recorded?** H2, like MySQL, can't roll back `ALTER TABLE` inside a transaction. Flyway can't undo a half-run migration, so it writes down that the migration failed and stops. **PostgreSQL** *can* roll back DDL, so there the whole failed migration simply disappears and you could just fix it and restart. Expect to meet both behaviours.

#### 9c. Repair the history

In real projects you'd run Flyway's `repair` command. It removes failed entries from the history table (and realigns checksums). Here's the same thing by hand, and it's worth doing once so you see there's no magic involved.

The app can't start, so its H2 console isn't available. H2 has its own console built into the H2 jar that Maven already downloaded. In IntelliJ's **Terminal** (<kbd>Alt</kbd>+<kbd>F12</kbd>, which opens in the project folder), run:

```powershell
# PowerShell
java -jar "$env:USERPROFILE\.m2\repository\com\h2database\h2\2.4.240\h2-2.4.240.jar"
```
```bash
# Git Bash / macOS / Linux
java -jar ~/.m2/repository/com/h2database/h2/2.4.240/h2-2.4.240.jar
```

(If that file doesn't exist, check the H2 version under **Dependencies** in the **Maven** tool window and adjust the path.)

A browser tab opens with the same H2 console you've used before. Connect with **JDBC URL** `jdbc:h2:file:./data/todo;AUTO_SERVER=TRUE`, user `sa`, empty password, and run:

```sql
SELECT "installed_rank", "version", "description", "success" FROM "flyway_schema_history";
DELETE FROM "flyway_schema_history" WHERE "success" = FALSE;
```

The first query shows V1 with `TRUE` and V2 with `FALSE`. The second deletes the failed row. Then **stop the console**: press <kbd>Ctrl</kbd>+<kbd>C</kbd> in the terminal.

> **(Ultimate)** If you connected the **Database** tool window in guide 03, you can run the same SQL there instead. It doesn't need the app to be running.

The failed `ALTER` changed nothing, so deleting its history row puts everything back to "V1 applied, V2 not yet run". Always check that before repairing: if a failed migration had done *half* its work, you'd have to undo that half by hand first.

#### 9d. The migration that works

Replace the contents of `V2__add_todo_list_created_at.sql` with the standard three-step pattern for adding a required column to a table that has data:

```sql
-- 1. Add the column as nullable, so existing rows are allowed
alter table todo_list add column created_at timestamp(6) with time zone;

-- 2. Backfill: give every existing row a value
update todo_list set created_at = current_timestamp where created_at is null;

-- 3. Now that no row is null, enforce it
alter table todo_list alter column created_at set not null;
```

You can edit V2 because it never ran successfully anywhere, so nothing depends on its checksum yet.

Restart:

```
Migrating schema "PUBLIC" to version "2 - add todo list created at"
Successfully applied 1 migration to schema "PUBLIC", now at version v2
```

and the app starts, because Hibernate's validation is happy now. In the H2 console (the app's own one is back at `/h2-console`):

```sql
SELECT ID, NAME, CREATED_AT FROM TODO_LIST;
```

Every existing list has the **same** `CREATED_AT`: the moment the migration ran. That's the honest answer, because nobody recorded when those lists were really made. Choosing a backfill value is a real design decision in production migrations: a fixed date, a value copied from another column, or "now", as here. There's no universal right answer, only one you've thought about.

#### 9e. Use the column

Change the `order by` in `TodoListRepository.findSummariesByOwnerEmail`:

```java
@Query("""
        select new com.example.todoapp.list.TodoListSummary(l.id, l.name, count(t))
        from TodoList l
        left join l.todos t
        where l.owner.email = :email
        group by l.id, l.name
        order by l.createdAt desc, l.id desc
        """)
```

- Only the last line changes. Sorting by a column that isn't in `group by` is allowed here because the rows are grouped by `l.id`, the primary key, so each group has exactly one `createdAt`.
- `l.id desc` is a **tie-breaker**. Your backfilled lists all share one timestamp, and without a second sort key their order would be up to the database. Ordering by a unique column last makes the result predictable.

Restart, create a list called "Holiday", and go back to **My lists**. "Holiday" is at the top.

> **Sidebar: generated migrations (Ultimate).** Recent versions of IntelliJ Ultimate can *generate* a Flyway migration by comparing your entities with a database connection. Look in the **New** menu on the `db/migration` folder for a Flyway migration option, and expect the exact wording to vary between versions. Try it against a copy of the V1-only schema and compare the result with your V2. You'll most likely get a single `ADD COLUMN ... NOT NULL`, which is the migration that failed in 9b. Generators are good at *what* changed, not at *how* to change it safely with data in the table. Teams that use them always review and edit the output, and now you know what to look for.

---

## Your turn

You've seen **create** in full. Now build **edit/update** and **delete** yourself, following the same pattern.

### Exercise 1: rename a list

Add these:

1. `TodoListService.rename(Long id, String name, String ownerEmail)`. Hint: you don't need to call `save`. Why not?
2. `GET /lists/{id}/edit` shows `lists/form` pre-filled with the current name, and with `listId` in the model.
3. `POST /lists/{id}` validates the form, renames, flashes "List renamed", and redirects to the list.
4. An **Edit** button on `show.html`.

<details>
<summary>Solution: service</summary>

```java
@Transactional
public void rename(Long id, String name, String ownerEmail) {
    TodoList list = getOwned(id, ownerEmail);
    list.setName(name.trim());
}
```

There's no `save()` because `list` is a **managed** entity inside an open transaction. When `rename` returns, the transaction commits, Hibernate's **dirty checking** notices `name` changed, and it issues the `UPDATE`. Look for it in the SQL log.

**Prove it with the debugger:** put a breakpoint on `list.setName(...)`, rename a list, and step over that line (<kbd>F8</kbd>). There's no SQL in the console yet. Now press **Step Out** (<kbd>Shift</kbd>+<kbd>F8</kbd>) back to the controller. The `update todo_list set name=?` appears as you leave the method, which is when the proxy commits.

`getOwned` is called on `this`, so it doesn't go through the proxy. That's fine: it simply runs inside `rename`'s transaction.

</details>

<details>
<summary>Solution: controller</summary>

```java
@GetMapping("/{id}/edit")
public String editForm(@PathVariable Long id,
                       @AuthenticationPrincipal UserDetails currentUser,
                       Model model) {
    TodoList list = todoListService.getOwned(id, currentUser.getUsername());
    model.addAttribute("form", new TodoListForm(list.getName()));
    model.addAttribute("listId", id);
    return "lists/form";
}

@PostMapping("/{id}")
public String update(@PathVariable Long id,
                     @Valid @ModelAttribute("form") TodoListForm form,
                     BindingResult result,
                     @AuthenticationPrincipal UserDetails currentUser,
                     Model model,
                     RedirectAttributes redirectAttributes) {
    if (result.hasErrors()) {
        model.addAttribute("listId", id);     // the form template needs it again
        return "lists/form";
    }
    todoListService.rename(id, form.getName(), currentUser.getUsername());
    redirectAttributes.addFlashAttribute("success", "List renamed");
    return "redirect:/lists/" + id;
}
```

The edit form is pre-filled from a *form object*, not the entity itself, for the same mass-assignment reason as registration.

</details>

<details>
<summary>Solution: template</summary>

```html
<a class="btn btn-outline-secondary btn-sm" th:href="@{/lists/{id}/edit(id=${list.id})}">Rename</a>
```

</details>

### Exercise 2: delete a list

Add `TodoListService.delete(...)`, `POST /lists/{id}/delete` (flash "List deleted", redirect to `/lists`), and a **Delete** button that asks "Are you sure?" first.

<details>
<summary>Solution</summary>

```java
// TodoListService
@Transactional
public void delete(Long id, String ownerEmail) {
    todoListRepository.delete(getOwned(id, ownerEmail));
}
```

```java
// TodoListController
@PostMapping("/{id}/delete")
public String delete(@PathVariable Long id,
                     @AuthenticationPrincipal UserDetails currentUser,
                     RedirectAttributes redirectAttributes) {
    todoListService.delete(id, currentUser.getUsername());
    redirectAttributes.addFlashAttribute("success", "List deleted");
    return "redirect:/lists";
}
```

```html
<form th:action="@{/lists/{id}/delete(id=${list.id})}" method="post" class="d-inline"
      onsubmit="return confirm('Delete this list and all its todos?');">
    <button type="submit" class="btn btn-outline-danger btn-sm">Delete</button>
</form>
```

Delete the demo "Groceries" list and watch the SQL log: Hibernate deletes its **todos first**, then the list. That's `cascade = CascadeType.ALL` from guide 03, working *through JPA* (unlike the raw SQL delete that failed in guide 03's exercise).

</details>

### Exercise 3: the home page

When a logged-in user visits `/`, redirect them to `/lists`. Visitors still see the welcome page.

<details>
<summary>Solution</summary>

```java
@GetMapping("/")
public String home(@AuthenticationPrincipal UserDetails currentUser, Model model) {
    if (currentUser != null) {
        return "redirect:/lists";
    }
    model.addAttribute("appName", "Todo App");
    return "home";
}
```

</details>

### Exercise 4: break it, edit a migration that has already run

Lists can be up to 100 characters, and you decide 150 would be better. Try the tempting shortcut: in `V1__create_schema.sql`, change `name varchar(100)` to `name varchar(150)` and restart.

1. What happens, and why?
2. Undo it, and make the change properly.

<details>
<summary>Answer</summary>

The app won't start:

```
Migration checksum mismatch for migration version 1
-> Applied to database : -1966830733     (your numbers will differ)
-> Resolved locally    : 666639235
Either revert the changes to the migration, or run repair to update the schema history.
```

Flyway stored V1's checksum when it ran in guide 03. The file no longer matches, so Flyway can't be sure what your database actually looks like. And even if it carried on, it would never re-run V1, so the column would stay at 100. Every other database that already ran V1 (a teammate's, the test server, production) would never get the change either.

"Run repair" would only make Flyway accept the edited file. The database would still have `varchar(100)` while the file claimed 150, which is worse than failing.

The fix: undo the edit (<kbd>Ctrl</kbd>+<kbd>Z</kbd>, or right-click the file → **Git → Rollback** if you've committed it) and add a new migration, `V3__widen_todo_list_name.sql`:

```sql
alter table todo_list alter column name set data type varchar(150);
```

Then change the entity's `@Column(length = 150)` and the form's `@Size(max = 150)` to match. `validate` won't complain if you forget those, because it doesn't check lengths. It's up to you to keep them in step.

</details>

---

## Checkpoint

Test by hand. You'll automate this in guides 07 and 08.

1. Log in as `demo@example.com`: you land on **My lists** with "Groceries" and "Work" and their todo counts, newest first.
2. `flyway_schema_history` shows versions 1 and 2, both with `success = TRUE`. Every list has a `CREATED_AT`.
3. Create a list with a blank name and see the validation error. Then create "Holiday" and see the flash message on the new list's page. Back on **My lists**, it's at the top.
4. Press **F5** on that page. No duplicate is created (PRG).
5. Rename it, then delete it.
6. **Ownership check:** note the URL of one of demo's lists (e.g. `/lists/1`). Open a **private/incognito browser window** (it has a separate session, so you can be logged in as two users at once), register a second account there, and visit `/lists/1`. You must get **your 404 page**, not demo's list. Also try `/lists/1/edit` and submitting a rename to `/lists/1` (you can change a form's `action` in the browser's dev tools).

```
src/main/java/com/example/todoapp/
├── common/NotFoundException.java
├── list/
│   ├── TodoList.java
│   ├── TodoListController.java
│   ├── TodoListForm.java
│   ├── TodoListRepository.java
│   ├── TodoListService.java
│   └── TodoListSummary.java
└── … (dev, greeting, security, todo, user, web)
src/main/resources/templates/
├── error/404.html
├── lists/
│   ├── form.html
│   ├── index.html
│   └── show.html
└── … (home, about, greet, login, register, fragments/layout)
src/main/resources/db/migration/
├── V1__create_schema.sql
└── V2__add_todo_list_created_at.sql
```

---

## Common errors

| Symptom | Cause / fix |
|---|---|
| `LazyInitializationException` while rendering a template | The template touched a lazy association (`list.owner`, `list.todos`). Load what you need in the service, or put a projection in the model. |
| Changes aren't saved | The method isn't `@Transactional` (or inherits `readOnly = true`), so there's no commit and no dirty check. Add `@Transactional` to the writing method. |
| `@Transactional` seems ignored | Self-invocation from a *non*-transactional method, or a non-public method. Call it from another bean. |
| `Request method 'POST' is not supported` | The form posts to a URL with no `@PostMapping`. Check `th:action` against the controller. |
| Duplicate rows after refreshing | You returned a view from a successful POST. Redirect instead. |
| Flash message never shows | You used `model.addAttribute` before a redirect. Use `RedirectAttributes.addFlashAttribute`. Also check the page includes the `flash` fragment. |
| Whitelabel page instead of `error/404.html` | The template is in the wrong folder (it must be `templates/error/404.html`), or `/error` isn't permitted in `SecurityConfig`. |
| `NullPointerException` on `currentUser` | The URL is reachable anonymously. Check `SecurityConfig` covers it with `authenticated()`. |
| `Schema validation: missing column [created_at] in table [todo_list]` | You changed the entity but V2 hasn't run. Check the file is in `db/migration` and named `V2__…` with two underscores. |
| `NULL not allowed for column "CREATED_AT"` during migration | A `NOT NULL` column was added to a table with rows. Use the three-step V2 from step 9d. |
| `Detected failed migration to version 2` | An earlier attempt failed and was recorded. Delete the failed row from `flyway_schema_history` (step 9c), then restart. |
| `Migration checksum mismatch for migration version 1` | A migration that already ran was edited. Revert the file and put the change in a new migration (Exercise 4). |
| Lists in an unpredictable order | The query sorts only by `createdAt`, and backfilled rows share one timestamp. Add `l.id desc` as a tie-breaker. |

---

## Recap

- Controllers handle HTTP, services hold rules and transactions, repositories query.
- `@Transactional` works through a **proxy**: it commits on return and rolls back on `RuntimeException`. Self-invocation skips the proxy. `readOnly = true` suits reads.
- Managed entities are saved automatically by **dirty checking**.
- **PRG**: redirect after a successful POST. Flash attributes carry a message across that redirect.
- `@ResponseStatus` on an exception maps it to an HTTP status. `templates/error/{status}.html` customises the page.
- **Every** data method takes the owner's identity, and queries filter by it. Someone else's data gives a 404.
- Schema changes go in a **new** migration. Add a required column to a populated table in three steps: nullable → backfill → `NOT NULL`. A failed migration on H2 is recorded and must be repaired. Never edit a migration that has run.

> **Sidebar: more ways to handle errors and shared model data.**
> - `@ControllerAdvice` + `@ExceptionHandler(NotFoundException.class)`: one class that handles exceptions from all controllers. Use it when you need custom logic or a custom model, not just a status code.
> - `@ModelAttribute` on a *method* (not a parameter): runs before every handler in the controller and adds its return value to the model. Handy for things every page needs.
> - **Pagination:** Spring Data repositories accept a `Pageable` parameter and return `Page<T>`. Useful once lists get long.

**Further reading**

- [Spring Framework reference: Declarative transaction management](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative.html)
- [Spring Framework reference: Handler methods](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods.html)
- [Spring Boot reference: Error handling](https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.spring-mvc.error-handling)

**Next:** [06 — CRUD: todos and ownership](06-crud-todos-and-ownership.md)
