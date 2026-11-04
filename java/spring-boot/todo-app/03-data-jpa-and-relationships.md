# 03 — Data, JPA and Relationships

> Written for **Spring Boot 4.1.2** and **Java 25**. Builds on [02 — Controllers and Thymeleaf](02-controllers-and-thymeleaf.md).

## What you'll learn

- How JPA, Hibernate and Spring Data JPA fit together
- How to configure a file-based H2 database and browse it with the H2 console
- How to own the database schema with **Flyway** migrations, written by hand in SQL, while Hibernate only **validates** it
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

You'll trigger this on purpose in step 10.

### Open Session in View (OSIV)

By default, Spring Boot keeps a persistence context open for **the whole web request**, including template rendering, so lazy fields "just work" in templates. It logs a warning about this at start-up. It's convenient, but it hides database queries inside your templates and keeps connections open longer than necessary. We'll **turn it off** (`spring.jpa.open-in-view=false`) and load what each page needs deliberately in the service layer. It's a bit more work, and you'll learn much more from it.

### Repositories

```java
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
}
```

There's no implementation class. At start-up, Spring Data generates one. You get `save`, `findById`, `findAll`, `delete`, `count` and more for free, and `findByEmail` is **derived from the method name**: `SELECT u FROM User u WHERE u.email = ?`.

### Who creates the tables? Schema migrations

Entities describe tables, but something has to actually run `CREATE TABLE`. There are two approaches:

- **Let Hibernate generate the schema** (`spring.jpa.hibernate.ddl-auto=update` or `create`). It reads your entities at start-up and creates or alters tables to match. It's quick for a demo, but it can't rename a column (it adds a new one and leaves the old one behind), it never removes anything, it can't move data around, and nobody gets to review what it's about to do to a production database.
- **Write migrations.** A **migration tool** runs numbered SQL scripts in order: `V1__create_schema.sql`, then `V2__add_todo_list_created_at.sql`, and so on. It records each one in a **history table**, so every database (yours, a colleague's, the test database, production) goes through exactly the same steps and ends up with exactly the same schema. The scripts live in git next to the code that needs them, and get reviewed like any other code.

Real projects use the second approach, with **Flyway** or **Liquibase**. We'll use **Flyway**, which Spring Boot auto-configures. When the app starts, Flyway runs **before** Hibernate:

1. Flyway looks in `src/main/resources/db/migration` for scripts named `V<version>__<description>.sql` (note the **two** underscores).
2. It compares them with its history table, `flyway_schema_history`, and runs any that haven't been applied yet, in version order.
3. Then Hibernate starts with `ddl-auto=validate`. It changes nothing; it only **checks** that every entity has a matching table and every field has a matching column of a compatible type. If not, the app refuses to start, so a mismatch is caught on your machine and not in production.

Two rules follow from this, and they're the same in every real project:

- **Never edit a migration once it has run.** Flyway stores a checksum of each applied script. Change the file and the next start fails with a *checksum mismatch*. To change the schema, add a **new** migration. You'll do exactly that in guide 05.
- **The SQL is the source of truth for the schema.** Your `@Column(nullable = false, length = 100)` annotations should match it, but `validate` doesn't check lengths, `NOT NULL`, unique constraints or indexes, only that tables and columns exist with compatible types. Constraints exist because *your migration* created them.

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
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-flyway</artifactId>
</dependency>
```

- `spring-boot-starter-data-jpa`: Spring Data JPA, Hibernate and the HikariCP connection pool.
- `h2`: the database engine itself. `runtime` scope, because your code never imports H2 classes directly.
- `spring-boot-h2console`: in Boot 4 the H2 web console's auto-configuration is its own module. (Boot 3 bundled it, so older tutorials don't mention this.) If start.spring.io's **Explore** shows a different artifact for your version, use that.
- `spring-boot-starter-flyway`: Flyway itself, plus Boot's auto-configuration that runs your migrations at start-up. (In Boot 3 you added `flyway-core` directly. In Boot 4, without the starter, Flyway sits on the classpath and does nothing.) H2 support is built into Flyway. Other databases, such as PostgreSQL, need an extra `flyway-database-…` module.

**Load Maven Changes** (<kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>O</kbd>) and restart the app.

### 2. Configure the datasource

Add to `application.properties`:

```properties
# --- Database -------------------------------------------------------------
spring.datasource.url=jdbc:h2:file:./data/todo;AUTO_SERVER=TRUE
spring.datasource.username=sa
spring.datasource.password=

# --- JPA / Hibernate -------------------------------------------------------
# Flyway owns the schema; Hibernate only checks it matches the entities
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true

# --- H2 console (development only!) ---------------------------------------
spring.h2.console.enabled=true
```

What each part means:

- `jdbc:h2:file:./data/todo` stores the database in `data/todo.mv.db`, relative to where you start the app (the project folder). It survives restarts. Use `jdbc:h2:mem:todo` for an in-memory database that's wiped on every restart.
- `AUTO_SERVER=TRUE` lets a second process (an IDE database tool, say) open the same file while the app is running, instead of failing with "database is locked".
- `ddl-auto=validate` tells Hibernate to **check** the tables against your entities at start-up and fail if they don't match. It never creates or changes anything. Flyway does that (see Concepts).
- `open-in-view=false` turns off OSIV (see Concepts).
- `show-sql` / `format_sql` print every SQL statement to the console, neatly formatted. Watching these is the best way to learn what JPA is doing.

There's nothing to configure for Flyway. Boot points it at your datasource and at the default location, `classpath:db/migration`. Create that folder now: in the **Project** window, right-click `src/main/resources` → **New → Directory** and type `db/migration`. The slash creates both folders at once. It stays empty until step 6.

> **Sidebar: the other `ddl-auto` values.** `none` does nothing. `validate` checks, and it's what you use alongside Flyway. `update` alters tables to match the entities, but only ever by adding. `create` drops and recreates everything at start-up, and `create-drop` also drops everything at shutdown. Boot defaults to `create-drop` for an embedded database *unless* it finds Flyway or Liquibase, in which case it defaults to `none`. We set `validate` explicitly because it's the safety net you want. You'll still see `update` in a lot of tutorials. It's fine for a quick throwaway demo, and you now know why real projects avoid it.

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

### 6. The first migration: `V1__create_schema.sql`

You have three entities and no tables. If you start the app now, Flyway warns *"No migrations found. Are your locations set up correctly?"*, and then Hibernate's validation stops the start-up with something like:

```
Schema validation: missing table [todo]
```

That's `validate` doing its job. Now write the SQL that creates the tables. Right-click `src/main/resources/db/migration` → **New → File**, and name it `V1__create_schema.sql`. That's a capital `V`, then `1`, then **two** underscores. Get the name wrong and Flyway silently ignores the file.

```sql
create table users (
    id            bigint generated by default as identity primary key,
    email         varchar(254)                not null,
    password_hash varchar(255)                not null,
    created_at    timestamp(6) with time zone not null,
    constraint uk_users_email unique (email)
);

create table todo_list (
    id       bigint generated by default as identity primary key,
    name     varchar(100) not null,
    owner_id bigint       not null,
    constraint fk_todo_list_owner foreign key (owner_id) references users (id)
);

create table todo (
    id           bigint generated by default as identity primary key,
    title        varchar(200) not null,
    done         boolean      not null,
    due_date     date,
    todo_list_id bigint       not null,
    constraint fk_todo_todo_list foreign key (todo_list_id) references todo_list (id)
);

create index ix_todo_list_owner_id on todo_list (owner_id);
create index ix_todo_todo_list_id on todo (todo_list_id);
```

Put it side by side with your entities. Every line maps to something you've already written:

| Entity | Column |
|---|---|
| `@Table(name = "users")` | `create table users`. Without `@Table`, the table name is the class name in snake_case: `TodoList` → `todo_list`. |
| `@Id @GeneratedValue(strategy = IDENTITY) Long id` | `bigint generated by default as identity primary key`. The database generates the key. |
| `@Column(nullable = false, length = 254) String email` | `varchar(254) not null` |
| `@Column(unique = true)` | `constraint uk_users_email unique (email)` |
| `Instant createdAt` | `timestamp(6) with time zone`. An `Instant` is a point in time, so it needs a time zone. |
| `boolean done` | `boolean not null` |
| `LocalDate dueDate` | `date`, nullable |
| `@ManyToOne @JoinColumn(name = "owner_id", nullable = false)` | `owner_id bigint not null` plus a **foreign key** to `users (id)` |

`TodoList.todos` (`@OneToMany(mappedBy = ...)`) has **no column**. The foreign key lives on the "many" side, in `todo.todo_list_id`, exactly as in the Concepts diagram.

Things Hibernate wouldn't have done for you:

- **Named constraints** (`uk_users_email`, `fk_todo_list_owner`). When one is violated, the error message names it, which is much easier to read than `CONSTRAINT_8A`. Later migrations can also drop or change them by name.
- **Indexes on foreign keys.** Almost every query here filters by owner or by list, so those columns need indexes. H2 indexes foreign keys automatically anyway, but PostgreSQL doesn't, so writing them out is a good habit.
- **Portable SQL.** Everything here also runs unchanged on PostgreSQL, which is the database you're most likely to meet in a real job.

> **IntelliJ:** the file opens with SQL highlighting. **(Ultimate)** may show a banner saying the SQL dialect isn't configured. Choose **H2** so it stops flagging valid H2 syntax. If you've connected the **Database** tool window (step 9), it also autocompletes table and column names.

Start the app now. The log shows Flyway running before Hibernate:

```
Creating Schema History table "PUBLIC"."flyway_schema_history" ...
Current version of schema "PUBLIC": << Empty Schema >>
Migrating schema "PUBLIC" to version "1 - create schema"
Successfully applied 1 migration to schema "PUBLIC", now at version v1
```

Hibernate then validates the tables against your entities, finds everything present, and the app starts. Restart it and Flyway says *"Schema "PUBLIC" is up to date. No migration necessary."* Each migration runs **once** per database.

> You may also see `WARN ... Using H2 2.4.x which is newer than the version Flyway has been verified with`. That's harmless: Boot's H2 version is just newer than Flyway's test matrix.

**From now on, `V1__create_schema.sql` is frozen.** It has run against your database, and Flyway has stored its checksum. Any further schema change goes in a new `V2__...` file. You'll write one in guide 05.

> **Only on your machine.** In real teams the line is drawn at **sharing**. While a migration exists only on your machine, not committed or pushed, you can still edit it. Delete your local database (see the reset note in step 9) and let Flyway run the corrected version from scratch. Once anyone else might have run it (a teammate, CI, a server), it's frozen for good. In this series we treat V1 as frozen from here, so you build the habit.

### 7. Repositories

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

### 8. Seed demo data with a `CommandLineRunner`

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
- **Why not seed in a migration?** Flyway *could* insert these rows from a `V2__demo_data.sql`. But migrations run against **every** database, production included, and a fake user with a known password has no business there. Migrations hold the schema, and sometimes genuine reference data such as a list of countries. Development data belongs in code like this, which you can later limit to development with `@Profile("dev")`.
- **`@Transactional`** wraps the whole method in one transaction: everything commits together, or nothing does. Spring implements this with a **proxy** around the bean (more in guide 05).
- `"{noop}password"` is a deliberately odd password value. The `{noop}` prefix will mean something to Spring Security in guide 04 ("this is plain text, not hashed"). For now it's just a string.
- **Cascade in action:** you only save the lists, and the todos are inserted too.
- SLF4J (`LoggerFactory.getLogger`) is the logging API Boot sets up for you.

### 9. Run it and explore

Start the app (Debug, <kbd>Shift</kbd>+<kbd>F9</kbd>) and watch the **Debug/Run** console. The SQL log is long, so use <kbd>Ctrl</kbd>+<kbd>F</kbd> in the console to search for `Flyway` or `insert`. The **soft-wrap** toggle in the console's left toolbar helps with long lines. This time Flyway has nothing to do, because V1 ran in step 6, and then you'll see the loader's inserts:

```
Schema "PUBLIC" is up to date. No migration necessary.
...
insert into users (created_at, email, password_hash, id) values (?, ?, ?, default)
insert into todo_list (name, owner_id, id) values (?, ?, default)
```

There's no `create table` from Hibernate anywhere in the log. That's `validate` at work: the tables came from your migration.

Now open the **H2 console** at <http://localhost:8080/h2-console>:

- **JDBC URL:** `jdbc:h2:file:./data/todo` (it must match your properties; the `AUTO_SERVER` part is optional here)
- **User:** `sa`, **Password:** empty

Try:

```sql
SELECT * FROM USERS;
SELECT * FROM TODO_LIST;
SELECT l.NAME, COUNT(t.ID) FROM TODO_LIST l LEFT JOIN TODO t ON t.TODO_LIST_ID = l.ID GROUP BY l.NAME;
SELECT "version", "description", "script", "checksum", "success" FROM "flyway_schema_history";
```

The last query shows Flyway's history table: one row for creating the history table itself, and one for `1 | create schema | V1__create_schema.sql`, with its checksum and `TRUE` for success. That row is how Flyway knows not to run V1 again. Flyway created this table with lower-case names, so they need the double quotes. Unquoted names, like `USERS`, are upper-cased by H2.

**Or browse it from IntelliJ (Ultimate): the Database tool window.** This is nicer than the web console, and it's why the URL has `AUTO_SERVER=TRUE`: IntelliJ is a *second process* opening the same file while the app is running.

1. **View → Tool Windows → Database** → **+ → Data Source → H2**.
2. **Connection type:** *URL only*. **URL:** `jdbc:h2:file:C:/Users/<you>/Sites/java-tutorials/spring-boot/projects/todo-app/data/todo;AUTO_SERVER=TRUE`. Use the **absolute** path, because IntelliJ doesn't start from the project folder. **User:** `sa`, empty password.
3. If prompted, click **Download missing driver files**, then **Test Connection** → **OK**.
4. Expand the tree to find the tables. Double-click one to see its rows, or open a **query console** (<kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>F10</kbd> in the data source) and run the SQL above.

The free edition doesn't have this tool window, so use the H2 web console.

Restart the app. The log should say "Demo data already present — skipping", which proves the data persisted. You'll also find `data/todo.mv.db` in the **Project** window. (If it doesn't appear, right-click the project → **Reload from Disk**.)

> **Resetting your data:** **stop the app** (■), then in the Project window right-click `data` → **Delete**. On the next start, Flyway rebuilds the schema by running every migration from V1, and the loader reseeds. That's safe because `data/` only holds throwaway local data, and the schema can always be rebuilt from your migrations. Use it to get fresh **data** (to re-run the loader, say). **Don't use it to paper over a schema problem.** Fix those with a new migration, because a production database can't be deleted. (The one exception is a migration that has never left your machine; see "Only on your machine" in step 6.) If Windows says the file is in use, the app (or a Database tool window connection) still has it open. Stop the app and click **Disconnect** in the Database window.

### 10. Break it: `LazyInitializationException`

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

- Flyway has applied V1, creating three tables (`USERS`, `TODO_LIST`, `TODO`). `flyway_schema_history` shows version 1 with `success = TRUE`.
- `application.properties` has `spring.jpa.hibernate.ddl-auto=validate`, and the log contains no Hibernate `create table`.
- The app seeds one user, two lists and three todos. A second start logs "Demo data already present — skipping".
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
src/main/resources/
├── application.properties
└── db/migration/
    └── V1__create_schema.sql
```

---

## Common errors

| Symptom | Cause / fix |
|---|---|
| `Schema validation: missing table [...]` or `missing column [...] in table [...]` | An entity or field has no matching table or column. Either the migration doesn't create it (check the spelling: `todoList` maps to `todo_list`), or Flyway didn't run at all (see the next three rows). |
| `Schema validation: wrong column type encountered in column [...]` | The SQL type in your migration doesn't fit the Java type, e.g. `varchar` for a `boolean`. If the migration has only ever run on your machine, fix it, delete `data/` and restart (see "Only on your machine" in step 6). Otherwise, add a new migration that alters the column. |
| No Flyway lines in the log at all | Only `flyway-core` is on the classpath. In Boot 4 you need `spring-boot-starter-flyway`, which brings the auto-configuration. Reload Maven afterwards. |
| `No migrations found. Are your locations set up correctly?` | The file isn't in `src/main/resources/db/migration`, or its name is wrong: it must be `V1__create_schema.sql`, with a capital `V` and **two** underscores. |
| `Found non-empty schema(s) "PUBLIC" but no schema history table` | `data/` holds tables made *before* Flyway was added (e.g. by `ddl-auto=update`). Stop the app and delete `data/`, and Flyway builds it from V1. |
| `Migration checksum mismatch for migration version 1` | You edited `V1__create_schema.sql` after it had run. Undo the edit (<kbd>Ctrl</kbd>+<kbd>Z</kbd>, or **Git → Rollback**) and put the change in a new migration instead. |
| `Syntax error in SQL statement "create table user ..."` | `USER` is reserved. Name the table `users` in your migration, and add `@Table(name = "users")` to the entity. |
| `No default (no-argument) constructor for class` | Entities need a no-arg constructor (`protected` is fine). |
| `Not a managed type: class ...` | The entity is outside `com.example.todoapp`, or is missing `@Entity`. |
| `mappedBy reference an unknown target entity property` | The `mappedBy` value must be the **field name** on the other side (`todoList`), not the column name. |
| `LazyInitializationException: no session` | A lazy field was touched outside a transaction. Load what you need inside a `@Transactional` service method, or with a fetching query. |
| `Database may be already in use` / file locked | Two processes opened the file without `AUTO_SERVER=TRUE`, or a crashed run is still alive. Stop all Java processes and retry. |
| H2 console says `Database "…/data/todo" not found` | The JDBC URL in the console doesn't match `spring.datasource.url` (check `./data/todo`, *without* `.mv.db`). |
| H2 console gives 404 | The `spring-boot-h2console` dependency is missing (Boot 4) or Maven wasn't reloaded, or `spring.h2.console.enabled=true` is missing. |
| IntelliJ's Database window shows an empty database | It's pointing at a different file. Use the absolute path to `data/todo` (without `.mv.db`), with `;AUTO_SERVER=TRUE`. |
| `jakarta.persistence` imports are red | Maven wasn't reloaded after adding `spring-boot-starter-data-jpa`. |
| `Unique index or primary key violation ... EMAIL` | You seeded twice. Check the `count() > 0` guard, or delete `data/`. |

---

## Recap

- **JPA** is the spec, **Hibernate** the engine, and **Spring Data JPA** generates repositories.
- **Flyway** owns the schema. Numbered SQL migrations in `db/migration` run once each, in order, and are recorded in `flyway_schema_history`. Hibernate only checks them (`ddl-auto=validate`). Never edit a migration that has run; add a new one.
- Entities need `@Entity`, an `@Id`, and a no-arg constructor. Avoid reserved table names.
- `@ManyToOne` owns the foreign key; `@OneToMany(mappedBy)` is the inverse. Keep both sides in sync with helper methods.
- Make associations **lazy**. Lazy fields need an open transaction. Watch for **N+1**, and fix it with targeted queries, projections or `join fetch`.
- `cascade` and `orphanRemoval` act through JPA only, never in raw SQL.
- `CommandLineRunner` runs code at start-up. Keep it idempotent with a file database.

**Further reading**

- [Spring Data JPA reference: Query methods](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html)
- [Spring Boot reference: SQL databases](https://docs.spring.io/spring-boot/reference/data/sql.html)
- [Spring Boot how-to: Database initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html) (the Flyway section)
- [Flyway documentation](https://documentation.red-gate.com/flyway)
- [Hibernate ORM user guide: Associations](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#associations)

**Next:** [04 — Registration and login](04-registration-and-login.md)
