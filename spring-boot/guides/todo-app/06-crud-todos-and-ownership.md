# 06 — CRUD: Todos and Ownership

> Written for **Spring Boot 4.1.2** and **Java 25**. Builds on [05 — CRUD: todo lists](05-crud-todo-lists.md).

## What you'll learn

- CRUD for a **nested resource**: todos that live inside a list (`/lists/{listId}/todos/...`)
- **Insecure Direct Object Reference (IDOR)**, the most common access-control bug, and how to design it out
- Why ownership is checked in **queries**, not with `if` statements in controllers
- 404 vs 403 for someone else's data
- Binding **dates** (`@DateTimeFormat`) and **checkboxes**
- Re-rendering a page with a form error when the form is embedded in a larger page
- Bulk updates with `@Modifying` JPQL queries
- How `orphanRemoval` and the helper methods from guide 03 behave in practice
- **IntelliJ:** auditing ownership with **Find Usages**, and catching exceptions with **exception breakpoints**

By the end, the list page lets you add, tick off, edit and delete todos, and no user can touch another user's todos, whatever URL they type.

---

## Concepts

### IDOR: the bug every beginner ships

Say the "tick off" URL is `POST /lists/3/todos/17/toggle`, and the handler does:

```java
Todo todo = todoRepository.findById(todoId).orElseThrow();   // ❌
todo.setDone(!todo.isDone());
```

Any logged-in user can toggle **anyone's** todo #17 just by changing the number. The login check passed, so they're *authenticated*, but nobody checked they're *authorised* for **this object**. That's an **Insecure Direct Object Reference**, and it sits at the top of the OWASP Top 10 ("Broken Access Control").

There's a subtler variant too: the attacker uses **their own** list ID with **your** todo ID: `/lists/MY_LIST/todos/YOUR_TODO/toggle`. A check that only verifies "the user owns list 3" doesn't catch it. You must check that **the todo belongs to that list *and* that list belongs to the user**.

### Design it out, don't remember it

Adding `if (!todo.getTodoList().getOwner().getEmail().equals(email)) throw ...` to every handler works, until you forget one. A safer design:

1. **No ownership-free lookups in the service.** Every public method takes the user's email.
2. **Ownership is part of the query.** One JPQL query fetches the todo *only if* `todo.id = ?`, `todo.todoList.id = ?` **and** `todo.todoList.owner.email = ?`. If any part fails, the result is empty and you get a 404.
3. **Tests prove it** (guides 07–08).

### 404 or 403?

When user B asks for user A's todo:

- **403 Forbidden** says "this exists, but it's not yours". That leaks the fact that ID 17 exists.
- **404 Not Found** says "nothing here", which is the same answer as for an ID that really doesn't exist.

For user-owned data, **404 is the usual choice**, and it falls out naturally from the ownership-in-the-query design. Use 403 when the user can know the resource exists but lacks permission (e.g. a read-only member of a shared list).

---

## Steps

### 1. The ownership query

Add to `todo/TodoRepository.java`:

```java
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Query("""
        select t from Todo t
        where t.id = :todoId
          and t.todoList.id = :listId
          and t.todoList.owner.email = :email
        """)
Optional<Todo> findOwned(@Param("todoId") Long todoId,
                         @Param("listId") Long listId,
                         @Param("email") String email);
```

Hibernate turns the `t.todoList.owner.email` path into joins:

```sql
select t.* from todo t
join todo_list l on l.id = t.todo_list_id
join users u on u.id = l.owner_id
where t.id = ? and l.id = ? and u.email = ?
```

One query covers all three conditions. Spring Data could *derive* this from a method name (`findByIdAndTodoListIdAndTodoListOwnerEmail`), but beyond two or three conditions, an explicit `@Query` is easier to read.

### 2. The form object

`todo/TodoForm.java`:

```java
package com.example.todoapp.todo;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class TodoForm {

    @NotBlank(message = "What needs doing?")
    @Size(max = 200, message = "Keep it under 200 characters")
    private String title;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dueDate;

    public TodoForm() {
    }

    public TodoForm(String title, LocalDate dueDate) {
        this.title = title;
        this.dueDate = dueDate;
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
}
```

**`@DateTimeFormat(iso = ISO.DATE)`**: an HTML `<input type="date">` always submits `yyyy-MM-dd` (e.g. `2026-11-03`). This annotation tells Spring's conversion service to parse that format into a `LocalDate`. It also formats it back the same way when `th:field` renders the input. An empty input binds to `null`, which suits an optional due date.

### 3. The service

`todo/TodoService.java`:

```java
package com.example.todoapp.todo;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.todoapp.common.NotFoundException;
import com.example.todoapp.list.TodoList;
import com.example.todoapp.list.TodoListService;

@Service
@Transactional(readOnly = true)
public class TodoService {

    private final TodoRepository todoRepository;
    private final TodoListService todoListService;

    public TodoService(TodoRepository todoRepository, TodoListService todoListService) {
        this.todoRepository = todoRepository;
        this.todoListService = todoListService;
    }

    public List<Todo> findTodos(Long listId, String ownerEmail) {
        todoListService.getOwned(listId, ownerEmail);            // 404 unless it's theirs
        return todoRepository.findByTodoListIdOrderByDoneAscIdAsc(listId);
    }

    public Todo getOwned(Long listId, Long todoId, String ownerEmail) {
        return todoRepository.findOwned(todoId, listId, ownerEmail)
                .orElseThrow(() -> new NotFoundException("Todo " + todoId + " not found"));
    }

    @Transactional
    public Todo add(Long listId, String title, LocalDate dueDate, String ownerEmail) {
        TodoList list = todoListService.getOwned(listId, ownerEmail);
        Todo todo = new Todo(title.trim(), dueDate);
        list.addTodo(todo);
        return todoRepository.save(todo);
    }

    @Transactional
    public void toggle(Long listId, Long todoId, String ownerEmail) {
        Todo todo = getOwned(listId, todoId, ownerEmail);
        todo.setDone(!todo.isDone());
    }
}
```

- `findTodos` checks the **list** first. Without that, a request for someone else's list would simply return an empty todo list, which is a quiet leak of "list 3 exists" and a confusing result.
- `add` calls `list.addTodo(todo)` (keeping both sides in sync, guide 03), then saves. Strictly, cascade would save it when the transaction commits, but calling `save` explicitly means the returned `todo` has its generated `id` straight away.
- `toggle` relies on **dirty checking** (guide 05).
- `TodoService` calls `TodoListService`. That's a *different* bean, so the call goes through the proxy and **joins** the current transaction (the default propagation, `REQUIRED`).

### 4. The show page loads todos

Update `TodoListController`: inject `TodoService` as well, and fill the model with everything the page needs.

```java
private final TodoListService todoListService;
private final TodoService todoService;

public TodoListController(TodoListService todoListService, TodoService todoService) {
    this.todoListService = todoListService;
    this.todoService = todoService;
}

@GetMapping("/{id}")
public String show(@PathVariable Long id,
                   @AuthenticationPrincipal UserDetails currentUser,
                   Model model) {
    String email = currentUser.getUsername();
    model.addAttribute("list", todoListService.getOwned(id, email));
    model.addAttribute("todos", todoService.findTodos(id, email));
    model.addAttribute("todoForm", new TodoForm());
    model.addAttribute("today", LocalDate.now());
    return "lists/show";
}
```

(Import `com.example.todoapp.todo.TodoForm`, `com.example.todoapp.todo.TodoService`, `java.time.LocalDate`.)

This is "load everything the view needs in the controller and service" from guide 03 in practice: the template never walks lazy associations.

### 5. The show template

Replace the placeholder paragraph in `lists/show.html` with:

```html
<!-- Add form -->
<form th:action="@{/lists/{id}/todos(id=${list.id})}" th:object="${todoForm}"
      method="post" class="row g-2 mb-4" novalidate>
    <div class="col-md-7">
        <input type="text" th:field="*{title}" class="form-control" placeholder="Add a todo…"
               th:classappend="${#fields.hasErrors('title')} ? 'is-invalid'">
        <div class="invalid-feedback" th:errors="*{title}">Title error</div>
    </div>
    <div class="col-md-3">
        <input type="date" th:field="*{dueDate}" class="form-control">
    </div>
    <div class="col-md-2 d-grid">
        <button type="submit" class="btn btn-primary">Add</button>
    </div>
</form>

<!-- Todos -->
<ul class="list-group" th:unless="${#lists.isEmpty(todos)}">
    <li class="list-group-item d-flex align-items-center gap-3" th:each="todo : ${todos}">

        <form th:action="@{/lists/{l}/todos/{t}/toggle(l=${list.id}, t=${todo.id})}" method="post">
            <button type="submit" class="btn btn-sm"
                    th:classappend="${todo.done} ? 'btn-success' : 'btn-outline-secondary'"
                    th:attr="aria-label=${todo.done} ? 'Mark as not done' : 'Mark as done'">✓</button>
        </form>

        <div class="flex-grow-1">
            <span th:text="${todo.title}" th:classappend="${todo.done} ? 'todo-done'">Milk</span>
            <span th:if="${todo.dueDate != null}" class="badge ms-2"
                  th:classappend="${!todo.done and todo.dueDate.isBefore(today)} ? 'text-bg-danger' : 'text-bg-light'"
                  th:text="${#temporals.format(todo.dueDate, 'd MMM yyyy')}">3 Nov 2026</span>
        </div>

        <!-- Your turn: Edit and Delete go here -->
    </li>
</ul>

<p class="text-muted" th:if="${#lists.isEmpty(todos)}">Nothing to do. Add something above!</p>
```

- The toggle is a tiny **POST form** with a button, not a link: it changes data, so it must be a POST with a CSRF token.
- `${todo.dueDate.isBefore(today)}`: Thymeleaf expressions can call Java methods. Overdue, unfinished todos get a red badge.
- `#temporals.format(...)` formats `java.time` values.
- `.todo-done` is the strike-through style you added to `app.css` in guide 02.

### 6. The todo controller: add and toggle

`todo/TodoController.java`:

```java
package com.example.todoapp.todo;

import java.time.LocalDate;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.todoapp.list.TodoListService;

import jakarta.validation.Valid;

@Controller
@RequestMapping("/lists/{listId}/todos")
public class TodoController {

    private final TodoService todoService;
    private final TodoListService todoListService;

    public TodoController(TodoService todoService, TodoListService todoListService) {
        this.todoService = todoService;
        this.todoListService = todoListService;
    }

    @PostMapping
    public String add(@PathVariable Long listId,
                      @Valid @ModelAttribute("todoForm") TodoForm form,
                      BindingResult result,
                      @AuthenticationPrincipal UserDetails currentUser,
                      Model model,
                      RedirectAttributes redirectAttributes) {
        String email = currentUser.getUsername();

        if (result.hasErrors()) {
            // Re-render the list page with the error showing in the add form
            model.addAttribute("list", todoListService.getOwned(listId, email));
            model.addAttribute("todos", todoService.findTodos(listId, email));
            model.addAttribute("today", LocalDate.now());
            return "lists/show";
        }

        todoService.add(listId, form.getTitle(), form.getDueDate(), email);
        redirectAttributes.addFlashAttribute("success", "Todo added");
        return "redirect:/lists/" + listId;
    }

    @PostMapping("/{todoId}/toggle")
    public String toggle(@PathVariable Long listId,
                         @PathVariable Long todoId,
                         @AuthenticationPrincipal UserDetails currentUser) {
        todoService.toggle(listId, todoId, currentUser.getUsername());
        return "redirect:/lists/" + listId;
    }
}
```

- The class-level `@RequestMapping("/lists/{listId}/todos")` contains a **path variable**. Every method can bind `@PathVariable Long listId`.
- **Re-rendering an embedded form.** The add form lives on the list page, so on a validation error you must rebuild *the whole page's model* (list, todos, today), not just the form. `todoForm`, with its errors, is already in the model thanks to `@ModelAttribute("todoForm")`. The name must match the `th:object` in the template.

  This duplicates a little of `TodoListController.show`. If it grows, move "build the list page model" into a shared helper. A common alternative is to redirect with the errors in flash attributes, but that's fiddlier. Re-rendering is the conventional Spring MVC approach.
- `toggle` doesn't flash a message: ticking a box is feedback enough.

---

## Your turn

### Exercise 1: edit a todo

Add an edit page at `GET /lists/{listId}/todos/{todoId}/edit`, with a form for title and due date, posting to `POST /lists/{listId}/todos/{todoId}`. On success, flash "Todo updated" and redirect to the list. Add an **Edit** link on each todo.

<details>
<summary>Solution: service</summary>

```java
@Transactional
public void update(Long listId, Long todoId, String title, LocalDate dueDate, String ownerEmail) {
    Todo todo = getOwned(listId, todoId, ownerEmail);
    todo.setTitle(title.trim());
    todo.setDueDate(dueDate);
}
```

</details>

<details>
<summary>Solution: controller</summary>

```java
@GetMapping("/{todoId}/edit")
public String editForm(@PathVariable Long listId,
                       @PathVariable Long todoId,
                       @AuthenticationPrincipal UserDetails currentUser,
                       Model model) {
    Todo todo = todoService.getOwned(listId, todoId, currentUser.getUsername());
    model.addAttribute("todoForm", new TodoForm(todo.getTitle(), todo.getDueDate()));
    model.addAttribute("listId", listId);
    model.addAttribute("todoId", todoId);
    return "todos/form";
}

@PostMapping("/{todoId}")
public String update(@PathVariable Long listId,
                     @PathVariable Long todoId,
                     @Valid @ModelAttribute("todoForm") TodoForm form,
                     BindingResult result,
                     @AuthenticationPrincipal UserDetails currentUser,
                     Model model,
                     RedirectAttributes redirectAttributes) {
    if (result.hasErrors()) {
        model.addAttribute("listId", listId);
        model.addAttribute("todoId", todoId);
        return "todos/form";
    }
    todoService.update(listId, todoId, form.getTitle(), form.getDueDate(), currentUser.getUsername());
    redirectAttributes.addFlashAttribute("success", "Todo updated");
    return "redirect:/lists/" + listId;
}
```

(Add `import org.springframework.web.bind.annotation.GetMapping;`.)

</details>

<details>
<summary>Solution: templates</summary>

`templates/todos/form.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('Edit todo')}"></head>
<body>
<nav th:replace="~{fragments/layout :: navbar}"></nav>
<main class="container" style="max-width: 560px">
    <h1 class="h3 mb-3">Edit todo</h1>
    <form th:action="@{/lists/{l}/todos/{t}(l=${listId}, t=${todoId})}"
          th:object="${todoForm}" method="post" novalidate>
        <div class="mb-3">
            <label for="title" class="form-label">Title</label>
            <input type="text" th:field="*{title}" class="form-control"
                   th:classappend="${#fields.hasErrors('title')} ? 'is-invalid'">
            <div class="invalid-feedback" th:errors="*{title}">Title error</div>
        </div>
        <div class="mb-3">
            <label for="dueDate" class="form-label">Due date (optional)</label>
            <input type="date" th:field="*{dueDate}" class="form-control">
        </div>
        <button type="submit" class="btn btn-primary">Save</button>
        <a class="btn btn-link" th:href="@{/lists/{l}(l=${listId})}">Cancel</a>
    </form>
</main>
<th:block th:replace="~{fragments/layout :: scripts}"></th:block>
</body>
</html>
```

In `lists/show.html`, inside the `<li>`:

```html
<a class="btn btn-sm btn-outline-secondary"
   th:href="@{/lists/{l}/todos/{t}/edit(l=${list.id}, t=${todo.id})}">Edit</a>
```

</details>

### Exercise 2: delete a todo

Add `POST /lists/{listId}/todos/{todoId}/delete` and a **Delete** button. Think about *how* to delete. Is `todoRepository.delete(todo)` the right call, given the bidirectional relationship and `orphanRemoval`?

<details>
<summary>Solution</summary>

```java
// TodoService
@Transactional
public void delete(Long listId, Long todoId, String ownerEmail) {
    Todo todo = getOwned(listId, todoId, ownerEmail);
    todo.getTodoList().removeTodo(todo);     // orphanRemoval deletes the row
}
```

Removing the todo **from its parent's collection** is the cleanest approach for a bidirectional `orphanRemoval` relationship: it keeps the in-memory model consistent, and Hibernate issues the `DELETE` at commit. `todoRepository.delete(todo)` *usually* works too. But if the parent's `todos` collection was already loaded in the same persistence context, the deleted todo is still in it, and cascade rules on the parent can fight with the delete. Going through the parent avoids the question.

(`todo.getTodoList()` is a lazy proxy, which is fine because we're inside the transaction.)

```java
// TodoController
@PostMapping("/{todoId}/delete")
public String delete(@PathVariable Long listId,
                     @PathVariable Long todoId,
                     @AuthenticationPrincipal UserDetails currentUser,
                     RedirectAttributes redirectAttributes) {
    todoService.delete(listId, todoId, currentUser.getUsername());
    redirectAttributes.addFlashAttribute("success", "Todo deleted");
    return "redirect:/lists/" + listId;
}
```

```html
<form th:action="@{/lists/{l}/todos/{t}/delete(l=${list.id}, t=${todo.id})}" method="post"
      onsubmit="return confirm('Delete this todo?');">
    <button type="submit" class="btn btn-sm btn-outline-danger">Delete</button>
</form>
```

</details>

### Exercise 3: "Clear completed" with a bulk delete

Add a **Clear completed** button on the list page that deletes all done todos in that list **in a single SQL statement**.

<details>
<summary>Hint</summary>

Look up `@Modifying`. A JPQL `delete from Todo t where ...` query must be marked `@Modifying` and run inside a transaction. And don't forget the ownership check.

</details>

<details>
<summary>Solution</summary>

```java
// TodoRepository
@Modifying
@Query("delete from Todo t where t.todoList.id = :listId and t.done = true")
int deleteCompleted(@Param("listId") Long listId);
```

(`import org.springframework.data.jpa.repository.Modifying;`)

```java
// TodoService
@Transactional
public int clearCompleted(Long listId, String ownerEmail) {
    todoListService.getOwned(listId, ownerEmail);     // ownership first!
    return todoRepository.deleteCompleted(listId);
}
```

```java
// TodoController
@PostMapping("/clear-completed")
public String clearCompleted(@PathVariable Long listId,
                             @AuthenticationPrincipal UserDetails currentUser,
                             RedirectAttributes redirectAttributes) {
    int removed = todoService.clearCompleted(listId, currentUser.getUsername());
    redirectAttributes.addFlashAttribute("success", removed + " completed todo(s) cleared");
    return "redirect:/lists/" + listId;
}
```

A bulk JPQL `delete` goes **straight to the database**. It skips the persistence context, cascades and `orphanRemoval`, so entities already loaded in the same transaction won't know they've gone. That's fine here, because we redirect straight afterwards. If you need to keep working with entities after a bulk operation, use `@Modifying(clearAutomatically = true)`.

</details>

---

## Checkpoint

Manual ownership testing. Do all of these; guides 07–08 automate the most important ones.

1. As `demo@example.com`: add todos with and without due dates, including one dated yesterday (red badge). Toggle, edit and delete them, and clear the completed ones.
2. Submit an empty title: the error appears **on the list page**, with your todos still shown.
3. Note one of demo's todo URLs, e.g. `/lists/1/todos/2/edit`.
4. Log in as your **second** user. Then:
   - Visit `/lists/1/todos/2/edit` → **404**.
   - Create a list of your own (say it's list 5) and visit `/lists/5/todos/2/edit` (your list, demo's todo) → **404**.
   - In dev tools, change a toggle form's `action` to `/lists/1/todos/2/toggle` and submit → **404**, and demo's todo is unchanged.
5. Log back in as demo and confirm nothing changed.

**Use an exception breakpoint to watch the 404s happen.** In IntelliJ, open **Run → View Breakpoints…** (<kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>F8</kbd>) → **+ → Java Exception Breakpoints** → `NotFoundException`. Repeat step 4 while debugging: the debugger stops at the exact line that throws, and the call stack shows which controller and service method was attacked. Exception breakpoints are a fast way to find where *any* error starts. Disable this one afterwards, or it will stop on every 404.

**Audit the ownership rule with Find Usages.** Code review is part of security. In IntelliJ:

1. Open `TodoRepository`, put the cursor on the interface name, and press <kbd>Ctrl</kbd>+<kbd>F12</kbd> to see the methods it inherits from `JpaRepository`, including `findById`.
2. Press <kbd>Shift</kbd> twice, find `CrudRepository`, open it, put the cursor on `findById`, and press <kbd>Alt</kbd>+<kbd>F7</kbd> (**Find Usages**). Set the scope to **Project Files**.
3. In this app the result should be **empty**, or contain only test code. Every `findById` call in `src/main` is a place where an ID from a URL *might* be used without an ownership check, so each one needs a reason.

Do the same for `todoRepository.delete` and `todoListRepository.delete`: every caller should have obtained the entity via an `…Owned` method first.

```
src/main/java/com/example/todoapp/
├── todo/
│   ├── Todo.java
│   ├── TodoController.java
│   ├── TodoForm.java
│   ├── TodoRepository.java
│   └── TodoService.java
└── … (common, dev, greeting, list, security, user, web)
src/main/resources/templates/
├── todos/form.html
└── … (error/404, lists/*, fragments/layout, home, about, greet, login, register)
```

---

## Common errors

| Symptom | Cause / fix |
|---|---|
| `Failed to convert value of type 'java.lang.String' to required type 'java.time.LocalDate'` | Missing `@DateTimeFormat(iso = ISO.DATE)` on the form field. |
| Date input is blank on the edit page | Same cause: without the annotation, `th:field` formats the date in a style `<input type="date">` doesn't accept. |
| `Neither BindingResult nor plain target object for bean name 'todoForm'` | The template uses `th:object="${todoForm}"` but the model has no `todoForm`. On re-render, check the `@ModelAttribute("…")` name matches. |
| `InvalidDataAccessApiUsageException: Executing an update/delete query` | A `@Modifying` query was run outside a transaction. Make the service method `@Transactional`. |
| `TemplateProcessingException ... dueDate.isBefore(...)` on null | Guard with `todo.dueDate != null and ...`. Thymeleaf's `and` doesn't evaluate the right-hand side if the left is false. |
| Deleted todo reappears / `deleted instance passed to merge` | The entity was deleted while still in a loaded parent collection. Remove it via `list.removeTodo(todo)`. |
| Another user's list shows as empty instead of 404 | `findTodos` isn't checking list ownership first. |

---

## Recap

- **IDOR** happens whenever an ID from the URL is used without checking it belongs to the user. For nested resources, check the **whole chain**: todo → list → owner.
- Put ownership **in the query** (`findOwned`) and give the service no unscoped lookups. Then forgetting a check is hard.
- Answer **404** for other users' data.
- `@DateTimeFormat(iso = DATE)` binds HTML date inputs. Use POST forms for any state change, even a checkbox tick.
- When a form is embedded in a page, rebuild the page's whole model on a validation error.
- `@Modifying` queries are fast but bypass the persistence context and cascades.

> **Sidebar: other approaches to authorisation you'll meet.**
> - `@PreAuthorize("@todoSecurity.canEdit(#todoId, authentication)")` with method security: the check lives in an annotation and calls a bean. Powerful, but the rule is hidden in a string.
> - **Spring Data auditing** (`@CreatedBy`, `@CreatedDate`, `@LastModifiedDate` with `@EnableJpaAuditing`): automatically records who created or changed a row. It complements ownership rather than replacing it.
> - **Row-level security** in PostgreSQL: the database itself filters rows by the current user.

**Further reading**

- [OWASP: Insecure Direct Object Reference Prevention](https://cheatsheetseries.owasp.org/cheatsheets/Insecure_Direct_Object_Reference_Prevention_Cheat_Sheet.html)
- [Spring Data JPA reference: Modifying queries](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.modifying-queries)

**Next:** [07 — Testing: unit and web](07-testing-unit-and-web.md)
