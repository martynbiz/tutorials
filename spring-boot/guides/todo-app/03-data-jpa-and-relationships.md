# 03 — Data, JPA and Relationships

> Written for **Spring Boot 4.1.2** and **Java 25**. Builds on [02 — Controllers and Thymeleaf](02-controllers-and-thymeleaf.md).

## What you'll learn

- How JPA, Hibernate and Spring Data JPA fit together
- How to configure a file-based H2 database and browse it with the H2 console
- How to map Java classes to tables with `@Entity`, `@Id`, `@Column`
- How to model **relationships**: `User 1──* TodoList 1──* Todo`, with `@ManyToOne` and `@OneToMany`
- How Spring Data **repositories** turn interface method names into SQL
- What **lazy loading** is, why `LazyInitializationException` happens, and the **N+1 query** problem
- How to seed demo data at start-up with a `CommandLineRunner`
- **IntelliJ:** generating constructors and getters, searching the SQL log, the **Database** tool window, and seeing Hibernate **proxies** in the debugger

There's no UI work in this guide. It's all about the data layer, which you'll check through the H2 console and the SQL log.

---

## Concepts

### Three layers of "JPA"

| Name | What it is |
|---|---|
| **JPA** (Jakarta Persistence) | A **specification**: annotations (`@Entity`, `@Id`, …) and interfaces (`EntityManager`) for mapping objects to relational tables. Package `jakarta.persistence`. |
| **Hibernate** | The **implementation** Spring Boot uses. It generates SQL, tracks changes and manages caches. |
| **Spring Data JPA** | A layer **on top** that generates repository implementations from interfaces, so you rarely touch `EntityManager` directly. |

You'll write JPA annotations on your entities and Spring Data interfaces for queries. Hibernate does the work underneath.

### Entities and the persistence context

An **entity** is a class whose instances map to rows. When you load an entity, Hibernate keeps it in the **persistence context**, a per-transaction cache of "managed" objects. Two important consequences:

- **Dirty checking.** Change a field on a managed entity inside a transaction, and Hibernate writes an `UPDATE` automatically when the transaction commits. You don't need to call `save()` (guide 05 uses this).
- **Identity.** Within one transaction, loading the same row twice gives you the **same Java object**.

Once the transaction ends, entities become **detached**. They still hold their data, but they can no longer load anything they haven't already loaded. Keep that in mind for lazy loading below.

### Relationships

Our model:

```
┌──────────┐ 1      * ┌────────────┐ 1      * ┌──────────┐
│  User    │──────────│  TodoList  │──────────│   Todo   │
│ id       │  owns    │ id         │ contains │ id       │
│ email    │          │ name       │          │ title    │
│ password │          │ owner_id ──┘          │ done     │
└──────────┘          └────────────┘          │ due_date │
                                              │ todo_list_id
                                              └──────────┘
```

In the database, the **foreign key lives on the "many" side**: `todo_list.owner_id`, `todo.todo_list_id`. In JPA:

- `@ManyToOne` goes on the field that holds the foreign key (`TodoList.owner`, `Todo.todoList`). That side **owns** the relationship.
- `@OneToMany(mappedBy = "...")` is the optional **inverse** side (`TodoList.todos`). `mappedBy` says "the foreign key is managed by that field over there, don't create another one".

We'll make `TodoList ↔ Todo` **bidirectional**, because it's handy to say `list.getTodos()` and to cascade deletes. `User → TodoList` will be **unidirectional** (`TodoList.owner` only). `User` has no `lists` collection. Fewer bidirectional links means less to keep in sync and fewer accidental loads.

### Lazy vs eager loading

When you load a `TodoList`, should Hibernate also load its owner? And all its todos?

- **Eager:** yes, always, in the same query or straight after.
- **Lazy:** no. It puts a **proxy** (for `@ManyToOne`) or an uninitialised collection (for `@OneToMany`) in the field, and only runs a query the first time you *touch* it.

JPA defaults: `@ManyToOne` is **eager** and `@OneToMany` is **lazy**. Experienced teams make **everything lazy** and load what they need explicitly, because eager loading quietly multiplies queries everywhere. We'll do the same.

Lazy loading needs an open persistence context. Touch a lazy field **after** the transaction has ended and you get:

```
org.hibernate.LazyInitializationException: could not initialize proxy - no Session
```

You'll trigger this on purpose in step 9.

### Open Session in View (OSIV)

By default, Spring Boot keeps a persistence context open for **the whole web request**, including template rendering, so lazy fields "just work" in templates. It logs a warning about this at start-up. It's convenient, but it hides database queries inside your templates and keeps connections open longer than necessary. We'll **turn it off** (`spring.jpa.open-in-view=false`) and load what each page needs deliberately in the service layer. It's a bit more work, and you'll learn much more from it.

### Repositories

```java
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
}
```

There's no implementation class. At start-up, Spring Data generates one. You get `save`, `findById`, `findAll`, `delete`, `count` and more for free, and `findByEmail` is **derived from the method name**: `SELECT u FROM User u WHERE u.email = ?`.

---

## Steps

### 1. Add the dependencies

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-h2console</artifactId>
</dependency>
```

- `spring-boot-starter-data-jpa`: Spring Data JPA, Hibernate and the HikariCP connection pool.
- `h2`: the database engine itself. `runtime` scope, because your code never imports H2 classes directly.
- `spring-boot-h2console`: in Boot 4 the H2 web console's auto-configuration is its own module. (Boot 3 bundled it, so older tutorials don't mention this.) If start.spring.io's **Explore** shows a different artifact for your version, use that.

**Load Maven Changes** (<kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>O</kbd>) and restart the app.

### 2. Configure the datasource

Add to `application.properties`:

```properties
# --- Database -------------------------------------------------------------
spring.datasource.url=jdbc:h2:file:./data/todo;AUTO_SERVER=TRUE
spring.datasource.username=sa
spring.datasource.password=

# --- JPA / Hibernate -------------------------------------------------------
spring.jpa.hibernate.ddl-auto=update
spring.jpa.open-in-view=false
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true

# --- H2 console (development only!) ---------------------------------------
spring.h2.console.enabled=true
```

What each part means:

- `jdbc:h2:file:./data/todo` stores the database in `data/todo.mv.db`, relative to where you start the app (the project folder). It survives restarts. Use `jdbc:h2:mem:todo` for an in-memory database that's wiped on every restart.
- `AUTO_SERVER=TRUE` lets a second process (an IDE database tool, say) open the same file while the app is running, instead of failing with "database is locked".
- `ddl-auto=update` makes Hibernate compare your entities with the tables at start-up and **add** missing tables and columns. It never drops anything.
- `open-in-view=false` turns off OSIV (see Concepts).
- `show-sql` / `format_sql` print every SQL statement to the console, neatly formatted. Watching these is the best way to learn what JPA is doing.

> **Sidebar: why real projects don't use `ddl-auto=update`.** It can't rename a column (it adds a new one and leaves the old one), it never removes anything, it can't migrate data, and you can't review what it will do to production before it does it. Real projects use a **migration tool**, either **Flyway** or **Liquibase**: numbered SQL scripts (`V1__create_users.sql`, `V2__add_due_date.sql`) applied in order and recorded in a history table. Once you use one, set `ddl-auto=validate` so Hibernate only *checks* that the schema matches. Spring Boot auto-configures both tools. They're out of scope here, but now you know what to search for.

### 3. The `User` entity

Create `user/User.java`:

```java
package com.example.todoapp.user;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected User() {
        // required by JPA
    }

    public User(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- `@Entity` marks this class as mapped to a table.
- `@Table(name = "users")`: **`USER` is a reserved word in H2** (and in PostgreSQL), so a table called `user` breaks with confusing SQL syntax errors. Always rename it.
- `@Id` + `@GeneratedValue(strategy = IDENTITY)`: the database generates the primary key (an auto-increment column). `id` is `null` until the entity is saved.
- `@Column(...)` is optional. Without it, Hibernate maps `passwordHash` to a column named `password_hash` anyway (snake_case is Boot's default naming strategy). We spell it out to add constraints: `nullable = false` becomes `NOT NULL`, and `unique = true` becomes a unique index.
- A `protected` **no-argument constructor** is required by JPA, because Hibernate creates instances by reflection. `protected` stops your own code from creating half-built entities.
- There's **no setter for `id`**, because it belongs to the database. There's no setter for `email` either, since we don't need one. Only add setters when something actually changes.

> **Let IntelliJ write the boilerplate.** Type the fields, then press <kbd>Alt</kbd>+<kbd>Insert</kbd> (**Generate**) inside the class → **Constructor** (pick the fields to include), **Getter**, or **Getter and Setter**. This is the "IDE-generated, not Lombok" approach from the planning: you see every line, and you don't have to type it. <kbd>Ctrl</kbd>+<kbd>F12</kbd> shows the file's structure, so you can check what you have.
>
> **(Ultimate)** adds JPA support: gutter icons on entities, warnings for mapping mistakes (e.g. a `mappedBy` naming a field that doesn't exist), and autocompletion inside `@Query` JPQL strings.

> **Sidebar: `equals`/`hashCode` on entities.** We don't override them, so identity is object identity. That works well with the persistence context, which guarantees one object per row per transaction. If you ever put entities into `HashSet`s across transactions you'll need a careful implementation (search for "Vlad Mihalcea entity equals hashCode"). Never generate them from *all* fields, and never use Lombok's `@Data` on entities: it includes lazy associations, so `hashCode()` or `toString()` can trigger queries or infinite recursion between `TodoList` and `Todo`.

### 4. The `TodoList` entity

Create `list/TodoList.java`:

```java
package com.example.todoapp.list;

import java.util.ArrayList;
import java.util.List;

import com.example.todoapp.todo.Todo;
import com.example.todoapp.user.User;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;

@Entity
public class TodoList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @OneToMany(mappedBy = "todoList", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<Todo> todos = new ArrayList<>();

    protected TodoList() {
    }

    public TodoList(String name, User owner) {
        this.name = name;
        this.owner = owner;
    }

    /** Keeps both sides of the relationship in sync. */
    public void addTodo(Todo todo) {
        todos.add(todo);
        todo.setTodoList(this);
    }

    public void removeTodo(Todo todo) {
        todos.remove(todo);
        todo.setTodoList(null);
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public User getOwner() { return owner; }
    public List<Todo> getTodos() { return todos; }
}
```

The interesting parts:

- `@ManyToOne(fetch = FetchType.LAZY, optional = false)`: many lists belong to one user. `LAZY` overrides the eager default. `optional = false` tells Hibernate a list always has an owner.
- `@JoinColumn(name = "owner_id")` names the foreign key column.
- `@OneToMany(mappedBy = "todoList")` is the inverse side. `"todoList"` is the **field name in `Todo`**, not a column name.
- `cascade = CascadeType.ALL`: operations on a list flow down to its todos. Save a new list containing new todos and they're saved too; delete a list and its todos are deleted too.
- `orphanRemoval = true`: remove a todo from the collection and Hibernate deletes its row. A todo can't exist without a list.
- `@OrderBy("id ASC")`: when the collection loads, sort it.
- `addTodo`/`removeTodo` are **helper methods**. With a bidirectional relationship, *you* must keep both sides consistent in memory. Hibernate only looks at the owning side (`todo.todoList`) when writing the foreign key. Always go through these helpers.

### 5. The `Todo` entity

Create `todo/Todo.java`:

```java
package com.example.todoapp.todo;

import java.time.LocalDate;

import com.example.todoapp.list.TodoList;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

@Entity
public class Todo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false)
    private boolean done;

    private LocalDate dueDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "todo_list_id", nullable = false)
    private TodoList todoList;

    protected Todo() {
    }

    public Todo(String title, LocalDate dueDate) {
        this.title = title;
        this.dueDate = dueDate;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public boolean isDone() { return done; }
    public void setDone(boolean done) { this.done = done; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public TodoList getTodoList() { return todoList; }

    /** Use TodoList.addTodo/removeTodo rather than calling this directly. */
    public void setTodoList(TodoList todoList) { this.todoList = todoList; }
}
```

Ideally `setTodoList` would be hidden, so that only `TodoList.addTodo`/`removeTodo` could call it. But `TodoList` lives in a different package (`list`), so a package-private method wouldn't be visible to it. It has to be `public`, and the Javadoc states the intended use. This is a real trade-off of feature packages: sometimes you need wider visibility than you'd like. Putting both entities in one package is the alternative.

`LocalDate dueDate` (with no `@Column`) is nullable, mapped to a `DATE` column called `due_date`. JPA maps `java.time` types natively.

### 6. Repositories

`user/UserRepository.java`:

```java
package com.example.todoapp.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
```

`todo/TodoRepository.java`:

```java
package com.example.todoapp.todo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TodoRepository extends JpaRepository<Todo, Long> {

    List<Todo> findByTodoListIdOrderByDoneAscIdAsc(Long todoListId);
}
```

`findByTodoListIdOrderByDoneAscIdAsc` reads as: find todos **by** `todoList.id`, **order by** `done` ascending (unfinished first), then `id`. Spring Data walks the property path: `TodoListId` → `todoList.id`.

`list/TodoListRepository.java`:

```java
package com.example.todoapp.list;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TodoListRepository extends JpaRepository<TodoList, Long> {

    Optional<TodoList> findByIdAndOwnerEmail(Long id, String ownerEmail);

    @Query("""
            select new com.example.todoapp.list.TodoListSummary(l.id, l.name, count(t))
            from TodoList l
            left join l.todos t
            where l.owner.email = :email
            group by l.id, l.name
            order by l.name
            """)
    List<TodoListSummary> findSummariesByOwnerEmail(@Param("email") String email);
}
```

- `findByIdAndOwnerEmail` is the method guide 05 relies on for security: "give me list *X*, but **only if** it belongs to this email". If it doesn't, the result is empty, as if it didn't exist.
- `@Query` takes **JPQL**, a query language over *entities and fields*, not tables and columns (`TodoList l`, `l.owner.email`). A Java text block (`"""`) keeps it readable.
- `select new ...TodoListSummary(...)` is a **constructor expression**: instead of entities, Hibernate builds lightweight read-only objects from the selected columns, in **one query**.
- `@Param("email")` binds `:email`. (Boot compiles with `-parameters`, so it would usually work without it, but being explicit is conventional and safer.)

Create the projection as a record, `list/TodoListSummary.java`:

```java
package com.example.todoapp.list;

public record TodoListSummary(Long id, String name, Long todoCount) {
}
```

A record is perfect here: immutable data, with no JPA lifecycle. (`count(...)` returns a `Long`, so the component is `Long`, not `long`.)

### 7. Seed demo data with a `CommandLineRunner`

Any bean implementing `CommandLineRunner` runs once the application has started. Create `dev/DemoDataLoader.java`:

```java
package com.example.todoapp.dev;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.todoapp.list.TodoList;
import com.example.todoapp.list.TodoListRepository;
import com.example.todoapp.todo.Todo;
import com.example.todoapp.user.User;
import com.example.todoapp.user.UserRepository;

@Component
public class DemoDataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    private final UserRepository userRepository;
    private final TodoListRepository todoListRepository;

    public DemoDataLoader(UserRepository userRepository, TodoListRepository todoListRepository) {
        this.userRepository = userRepository;
        this.todoListRepository = todoListRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.count() > 0) {
            log.info("Demo data already present — skipping");
            return;
        }

        User demo = userRepository.save(new User("demo@example.com", "{noop}password"));

        TodoList groceries = new TodoList("Groceries", demo);
        groceries.addTodo(new Todo("Milk", null));
        groceries.addTodo(new Todo("Bread", LocalDate.now().plusDays(1)));

        TodoList work = new TodoList("Work", demo);
        work.addTodo(new Todo("Finish Spring guide 03", LocalDate.now()));

        todoListRepository.save(groceries);   // cascades to its todos
        todoListRepository.save(work);

        log.info("Seeded demo user {} with {} lists", demo.getEmail(), todoListRepository.count());
    }
}
```

Points to notice:

- **It's idempotent.** The database is a file, so data survives restarts. Without the `count() > 0` check you'd get a duplicate demo user, and a unique-constraint failure, on every start.
- **`@Transactional`** wraps the whole method in one transaction: everything commits together, or nothing does. Spring implements this with a **proxy** around the bean (more in guide 05).
- `"{noop}password"` is a deliberately odd password value. The `{noop}` prefix will mean something to Spring Security in guide 04 ("this is plain text, not hashed"). For now it's just a string.
- **Cascade in action:** you only save the lists, and the todos are inserted too.
- SLF4J (`LoggerFactory.getLogger`) is the logging API Boot sets up for you.

### 8. Run it and explore

Start the app (Debug, <kbd>Shift</kbd>+<kbd>F9</kbd>) and watch the **Debug/Run** console. The SQL log is long, so use <kbd>Ctrl</kbd>+<kbd>F</kbd> in the console to search for `create table` or `insert`. The **soft-wrap** toggle in the console's left toolbar helps with long lines. You'll see Hibernate's `create table` statements (from `ddl-auto=update`) and then the inserts:

```sql
create table users (
    id bigint generated by default as identity,
    created_at timestamp(6) with time zone not null,
    email varchar(254) not null,
    password_hash varchar(255) not null,
    primary key (id)
)
...
insert into todo_list (name, owner_id, id) values (?, ?, default)
```

Now open the **H2 console** at <http://localhost:8080/h2-console>:

- **JDBC URL:** `jdbc:h2:file:./data/todo` (it must match your properties; the `AUTO_SERVER` part is optional here)
- **User:** `sa`, **Password:** empty

Try:

```sql
SELECT * FROM USERS;
SELECT * FROM TODO_LIST;
SELECT l.NAME, COUNT(t.ID) FROM TODO_LIST l LEFT JOIN TODO t ON t.TODO_LIST_ID = l.ID GROUP BY l.NAME;
```

**Or browse it from IntelliJ (Ultimate): the Database tool window.** This is nicer than the web console, and it's why the URL has `AUTO_SERVER=TRUE`: IntelliJ is a *second process* opening the same file while the app is running.

1. **View → Tool Windows → Database** → **+ → Data Source → H2**.
2. **Connection type:** *URL only*. **URL:** `jdbc:h2:file:C:/Users/<you>/Sites/java-tutorials/spring-boot/projects/todo-app/data/todo;AUTO_SERVER=TRUE`. Use the **absolute** path, because IntelliJ doesn't start from the project folder. **User:** `sa`, empty password.
3. If prompted, click **Download missing driver files**, then **Test Connection** → **OK**.
4. Expand the tree to find the tables. Double-click one to see its rows, or open a **query console** (<kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>F10</kbd> in the data source) and run the SQL above.

The free edition doesn't have this tool window, so use the H2 web console.

Restart the app. The log should say "Demo data already present — skipping", which proves the data persisted. You'll also find `data/todo.mv.db` in the **Project** window. (If it doesn't appear, right-click the project → **Reload from Disk**.)

> **Resetting the database:** **stop the app** (■), then in the Project window right-click `data` → **Delete**. The next start recreates everything and reseeds. You'll do this whenever a schema change confuses `ddl-auto=update`. If Windows says the file is in use, the app (or a Database tool window connection) still has it open. Stop the app and click **Disconnect** in the Database window.

### 9. Break it: `LazyInitializationException`

Temporarily add this to the **end** of `run(...)` (above the final `log.info` is fine), then **remove `@Transactional`** from the method and delete the `data/` folder:

```java
todoListRepository.findAll().forEach(list ->
        log.info("{} is owned by {}", list.getName(), list.getOwner().getEmail()));
```

Restart and you'll get:

```
org.hibernate.LazyInitializationException: Could not initialize proxy [com.example.todoapp.user.User#1] - no session
```

Why? Without `@Transactional` on `run`, each repository call runs in **its own short transaction**. `findAll()` loads the lists, *its* transaction closes, and `list.getOwner()` is now a lazy proxy with no session to load from.

**See the proxy for yourself.** Put a breakpoint on the `log.info(...)` line inside the lambda, delete `data/`, and start with **Debug**. When it stops, expand `list` in the **Variables** pane. `owner` isn't a plain `User`: it's something like `User$HibernateProxy$Ab12Cd`, with `id` set and the other fields empty. That's the placeholder Hibernate puts in a lazy field. Now open **Evaluate Expression** (<kbd>Alt</kbd>+<kbd>F8</kbd>) and run `list.getOwner().getEmail()`, and the exception happens right there.

> **Careful:** the debugger itself can *trigger* lazy loading. Expanding a collection or calling `toString()` in the Variables pane runs queries if a session is open. If the SQL log shows queries you didn't expect while you're paused, that's why.

Put `@Transactional` back. It works now, but **look at the SQL log**: one query loads the lists, then a separate query loads the owner. Change the log line to touch the todos too:

```java
log.info("{} has {} todos", list.getName(), list.getTodos().size())
```

You'll see one `select ... from todo where todo_list_id=?` **per list**. That's the **N+1 problem**: 1 query for N lists, then N more queries, one per list. With 2 lists you won't notice; with 500 lists on a page, you will.

The fixes, which you'll use from guide 05:

1. **Ask for exactly what the page needs in one query**: the `TodoListSummary` constructor query does this, with counts for every list in a single `select`.
2. **Load children explicitly with their own query** (`TodoRepository.findByTodoListId...`) rather than walking lazy collections.
3. **`join fetch`**, which loads a relationship in the same query: `select l from TodoList l join fetch l.owner where ...`. (Spring Data also offers `@EntityGraph` for the same purpose.)

Remove the experiment lines before moving on (keep `@Transactional`).

---

## Your turn

### Exercise 1: a derived query

Add a repository method that counts the **unfinished** todos in a given list. Don't write any JPQL; derive it from the method name.

<details>
<summary>Solution</summary>

```java
// TodoRepository
long countByTodoListIdAndDoneFalse(Long todoListId);
```

`count…` returns a number, `By…` starts the criteria, `TodoListId` walks `todoList.id`, and `DoneFalse` means `done = false`. Spring Data supports `True`/`False`, `IsNull`, `GreaterThan`, `Containing`, `IgnoreCase`, `In`, `Between` and more. See the "Query Methods" link below.

</details>

### Exercise 2: use the summary query

In `DemoDataLoader.run`, after seeding, call `findSummariesByOwnerEmail("demo@example.com")` and log each summary. Before you restart, **predict** how many SQL statements it will produce. Then check the log.

<details>
<summary>Solution</summary>

```java
todoListRepository.findSummariesByOwnerEmail("demo@example.com")
        .forEach(s -> log.info("{}: {} todos", s.name(), s.todoCount()));
```

**One** `select` with a `left join` and `group by`, however many lists there are. (Remember the loader skips everything after the first start, so delete `data/` to see it run again, or move the call above the `if`.) Remove it when you're done.

</details>

### Exercise 3: explore cascade and orphan removal in the H2 console

Write down what you expect to happen to the `TODO` table if you run `DELETE FROM TODO_LIST WHERE NAME = 'Work'` **in the H2 console**. Then try it.

<details>
<summary>Answer</summary>

It **fails** with a foreign-key violation. `cascade` and `orphanRemoval` are **Hibernate** features that only apply when you delete *through JPA*. The database knows nothing about them. Raw SQL has to delete children first. (Delete `data/` afterwards if you want the demo data back intact.)

</details>

---

## Checkpoint

- The app starts, creates three tables (`USERS`, `TODO_LIST`, `TODO`), and seeds one user, two lists and three todos.
- A second start logs "Demo data already present — skipping".
- The H2 console shows the data.
- Your pages from guide 02 still work.

```
src/main/java/com/example/todoapp/
├── TodoAppApplication.java
├── dev/DemoDataLoader.java
├── greeting/...
├── list/
│   ├── TodoList.java
│   ├── TodoListRepository.java
│   └── TodoListSummary.java
├── todo/
│   ├── Todo.java
│   └── TodoRepository.java
├── user/
│   ├── User.java
│   └── UserRepository.java
└── web/...
```

---

## Common errors

| Symptom | Cause / fix |
|---|---|
| `Syntax error in SQL statement "create table user ..."` | `USER` is reserved. Add `@Table(name = "users")`. |
| `No default (no-argument) constructor for class` | Entities need a no-arg constructor (`protected` is fine). |
| `Not a managed type: class ...` | The entity is outside `com.example.todoapp`, or is missing `@Entity`. |
| `mappedBy reference an unknown target entity property` | The `mappedBy` value must be the **field name** on the other side (`todoList`), not the column name. |
| `LazyInitializationException: no session` | A lazy field was touched outside a transaction. Load what you need inside a `@Transactional` service method, or with a fetching query. |
| `Database may be already in use` / file locked | Two processes opened the file without `AUTO_SERVER=TRUE`, or a crashed run is still alive. Stop all Java processes and retry. |
| H2 console says `Database "…/data/todo" not found` | The JDBC URL in the console doesn't match `spring.datasource.url` (check `./data/todo`, *without* `.mv.db`). |
| H2 console gives 404 | The `spring-boot-h2console` dependency is missing (Boot 4) or Maven wasn't reloaded, or `spring.h2.console.enabled=true` is missing. |
| IntelliJ's Database window shows an empty database | It's pointing at a different file. Use the absolute path to `data/todo` (without `.mv.db`), with `;AUTO_SERVER=TRUE`. |
| `jakarta.persistence` imports are red | Maven wasn't reloaded after adding `spring-boot-starter-data-jpa`. |
| Odd leftover columns after renaming a field | `ddl-auto=update` added the new column and kept the old one. Delete `data/` and restart. |
| `Unique index or primary key violation ... EMAIL` | You seeded twice. Check the `count() > 0` guard, or delete `data/`. |

---

## Recap

- **JPA** is the spec, **Hibernate** the engine, and **Spring Data JPA** generates repositories.
- Entities need `@Entity`, an `@Id`, and a no-arg constructor. Avoid reserved table names.
- `@ManyToOne` owns the foreign key; `@OneToMany(mappedBy)` is the inverse. Keep both sides in sync with helper methods.
- Make associations **lazy**. Lazy fields need an open transaction. Watch for **N+1**, and fix it with targeted queries, projections or `join fetch`.
- `cascade` and `orphanRemoval` act through JPA only, never in raw SQL.
- `CommandLineRunner` runs code at start-up. Keep it idempotent with a file database.

**Further reading**

- [Spring Data JPA reference: Query methods](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html)
- [Spring Boot reference: SQL databases](https://docs.spring.io/spring-boot/reference/data/sql.html)
- [Hibernate ORM user guide: Associations](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#associations)

**Next:** [04 — Registration and login](04-registration-and-login.md)
