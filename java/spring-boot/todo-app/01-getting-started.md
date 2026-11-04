# 01 — Getting Started

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [README](README.md) for version notes.

## What you'll learn

- How to generate a Spring Boot project with Spring Initializr, and what every generated file is for
- How to build and run it with the Maven wrapper
- The two ideas the rest of Spring rests on: **beans** and **dependency injection**
- What **auto-configuration** is, and how to see what it decided
- How to read settings from `application.properties`
- How DevTools restarts the app for you
- **IntelliJ:** opening a Maven project, setting the JDK, the Project, Maven and Run tool windows, run configurations, creating classes and packages, fixing imports, and **debugging with breakpoints**

By the end you'll have a running app that answers `http://localhost:8080/hello` with a greeting produced by a Spring-managed service.

---

## Concepts

### What Spring is, and what Spring *Boot* adds

**Spring Framework** is a large toolkit for building Java applications. At its core is a **container** (also called the *application context*). It creates your objects, wires them together and manages their lifecycle. Around that core sit modules for web (Spring MVC), data access, security, testing and more.

Plain Spring needs a lot of configuration: which web server to start, how to connect to the database, which JSON library to use, and so on. **Spring Boot** is an opinionated layer on top that:

- gives you **starters**: one dependency (e.g. `spring-boot-starter-webmvc`) pulls in a tested set of libraries that work together
- **auto-configures** sensible defaults based on what's on your classpath. Add the web starter and you get an embedded Tomcat server on port 8080 without writing any configuration
- runs as a plain `java -jar` application with the web server *inside* it, with no separate application server to install

Spring does the plumbing. You write the parts that are specific to your app.

### Beans and the container

A **bean** is an object that Spring creates and manages for you. You don't call `new` on it. You tell Spring the class exists, Spring creates **one instance** of it (by default), and hands that instance to anything that needs it.

You mark a class as a bean with a **stereotype annotation**:

| Annotation | Means | Used for |
|---|---|---|
| `@Component` | "This is a bean" | Generic helpers |
| `@Service` | A `@Component`, labelled as business logic | Service layer |
| `@Repository` | A `@Component`, labelled as data access (also translates database exceptions) | Data layer |
| `@Controller` / `@RestController` | A `@Component` that handles web requests | Web layer |

At start-up, Spring **component-scans** your package and its sub-packages, finds those annotations, and registers each class as a bean.

### Dependency injection (DI)

Say `HelloController` needs a `GreetingService`. Without Spring, you'd write:

```java
private final GreetingService greetingService = new GreetingService(); // controller builds its own dependency
```

With DI, the controller simply **declares** what it needs in its constructor, and the container **supplies** it:

```java
public HelloController(GreetingService greetingService) {
    this.greetingService = greetingService;
}
```

Why bother?

- **Loose coupling.** The controller doesn't know or care how the service is built or what it depends on.
- **Testability.** In a unit test you call `new HelloController(fakeService)` yourself. No Spring needed (guide 07).
- **One place to change.** Swap an implementation and every class that uses it picks up the change.

This is called **inversion of control**: you hand control of object creation over to the container.

### Auto-configuration

When the app starts, Spring Boot looks at the classpath and your settings and asks questions like: "Is Tomcat present? Is there a `DataSource`? Has the developer already defined their own bean of this type?" Each auto-configuration class is guarded by **conditions** such as `@ConditionalOnClass` or `@ConditionalOnMissingBean`. That last one matters a lot: **if you define your own bean, Boot's default backs off.** You'll rely on this in guide 04, when your own security configuration replaces Boot's default one.

---

## Steps

### 1. Check your JDK

IntelliJ can download and manage JDKs for you, so you don't have to install Java separately. You'll pick the JDK in step 3. If you're happy to let IntelliJ handle it, skip to step 2.

To use Java from the **terminal** too (handy, but optional), check:

```bash
java -version
```

You want something like `openjdk version "25..."`. If you get an older version, or "command not found":

- **Windows:** `winget install EclipseAdoptium.Temurin.25.JDK`, or download from [adoptium.net](https://adoptium.net). Then open a **new** terminal.
- **macOS:** `brew install --cask temurin@25`
- **Linux:** use your distribution's package manager, or [SDKMAN!](https://sdkman.io) (`sdk install java 25-tem`)

Check that `JAVA_HOME` points to the JDK 25 install too, because the Maven wrapper uses it:

```powershell
# PowerShell
echo $env:JAVA_HOME
```
```bash
# Git Bash / macOS / Linux
echo $JAVA_HOME
```

### 2. Generate the project

There are two routes. Both use the same Spring Initializr service, so the result is identical.

**Option A, inside IntelliJ (Ultimate):** **File → New → Project…**, then choose **Spring Boot** in the left-hand list. Set the **Location** to `…\spring-boot\projects` and fill in the values from the table below (**Type: Maven**, **JDK: 25**; if it's not listed, choose *Download JDK…* → version 25, vendor *Eclipse Temurin*). Click **Next**, pick the dependencies below, and click **Create**. IntelliJ opens the project for you, so you can skip step 3's "Open the project" part.

**Option B, on the website (works with any edition):** go to [start.spring.io](https://start.spring.io) and choose:

| Setting | Value |
|---|---|
| Project | **Maven** |
| Language | **Java** |
| Spring Boot | **4.1.2** (or the newest 4.1.x that isn't SNAPSHOT/M/RC) |
| Group | `com.example` |
| Artifact | `todo-app` |
| Name | `todo-app` |
| Package name | `com.example.todoapp` |
| Packaging | **Jar** |
| Configuration | **Properties** |
| Java | **25** |

Under **Dependencies**, add only two:

- **Spring Web** (adds `spring-boot-starter-webmvc`)
- **Spring Boot DevTools**

We'll add the others (Thymeleaf, JPA, Security, …) in later guides, one at a time, so you can see what each one changes.

Click **Explore** first and read the generated `pom.xml`. Then click **Generate**, and unzip the download so the project sits at:

```
spring-boot/projects/todo-app/
```

(The zip contains a `todo-app` folder. Make sure you don't end up with `todo-app/todo-app/`.)

### 3. Open the project in IntelliJ

1. **File → Open…**, select the `spring-boot/projects/todo-app` **folder** (the one containing `pom.xml`), and click **OK**.
2. If asked, choose **Trust Project**. IntelliJ won't run the build scripts of an untrusted project.
3. IntelliJ spots `pom.xml` and **imports it as a Maven project**. Watch the progress bar at the bottom right: it's downloading dependencies. Wait for it to finish before doing anything else.
4. **Set the JDK.** Open **File → Project Structure…** (<kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>S</kbd>) → **Project**:
   - **SDK:** choose a 25 JDK. If none is listed: **Add SDK → Download JDK…** → *Version 25*, *Vendor Eclipse Temurin* → **Download**.
   - **Language level:** *SDK default*.

   Click **OK**.

   > This sets the JDK that **IntelliJ** compiles and runs with. The terminal's `./mvnw` uses `JAVA_HOME` instead. They're independent, which is why something can work in one and not the other.

**Find your way around.** These tool windows (**View → Tool Windows**) are the ones you'll use most:

| Tool window | Shortcut | What for |
|---|---|---|
| **Project** | <kbd>Alt</kbd>+<kbd>1</kbd> | The file tree |
| **Maven** | (right-hand edge) | Lifecycle goals (`clean`, `test`, `package`), plugins such as `spring-boot:run`, the **Reload** button, and the dependency tree |
| **Run** | <kbd>Alt</kbd>+<kbd>4</kbd> | Console output of a running app |
| **Debug** | <kbd>Alt</kbd>+<kbd>5</kbd> | Breakpoints, variables, stepping |
| **Services** | <kbd>Alt</kbd>+<kbd>8</kbd> | All running apps in one place, with start/stop buttons |
| **Terminal** | <kbd>Alt</kbd>+<kbd>F12</kbd> | A shell in the project folder |

Two shortcuts worth learning from day one: <kbd>Shift</kbd> twice (**Search Everywhere**: files, classes, actions, settings) and <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>A</kbd> (**Find Action**).

### 4. Tour the generated files

```
todo-app/
├── .gitignore
├── .mvn/wrapper/maven-wrapper.properties
├── mvnw                      ← Maven wrapper (macOS/Linux/Git Bash)
├── mvnw.cmd                  ← Maven wrapper (Windows)
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/example/todoapp/
    │   │   └── TodoAppApplication.java
    │   └── resources/
    │       ├── application.properties
    │       ├── static/       ← CSS, JS, images (served as-is)
    │       └── templates/    ← Thymeleaf templates (guide 02)
    └── test/java/com/example/todoapp/
        └── TodoAppApplicationTests.java
```

Open each file as you read about it:

**`mvnw` / `mvnw.cmd`** is the **Maven wrapper**. The first time you run it, it downloads the exact Maven version named in `.mvn/wrapper/maven-wrapper.properties`. Everyone on the project builds with the same Maven, and you never install it yourself.

**`pom.xml`** — three parts to notice:

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.2</version>
</parent>
```

The **parent POM** manages versions for hundreds of libraries. That's why your starter dependencies have **no `<version>`**: Boot has already chosen versions that work together. Change this one number and you upgrade everything together.

> **IntelliJ:** <kbd>Ctrl</kbd>+click `spring-boot-starter-parent` to open it, then <kbd>Ctrl</kbd>+click its own parent (`spring-boot-dependencies`) to see the full list of managed versions. In the **Maven** tool window, expand **Dependencies** to see every library each starter pulls in.

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
```

A **starter**: Spring MVC, embedded Tomcat, Jackson for JSON, logging and so on, all in one dependency.

```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
</plugin>
```

The Boot plugin adds `spring-boot:run` and packages a runnable "fat jar".

You'll also see a test dependency (`spring-boot-starter-test`, and possibly a `…-webmvc-test` starter). Leave those alone until guide 07.

**`TodoAppApplication.java`:**

```java
@SpringBootApplication
public class TodoAppApplication {
    public static void main(String[] args) {
        SpringApplication.run(TodoAppApplication.class, args);
    }
}
```

`@SpringBootApplication` combines three annotations:

- `@Configuration`: this class can define beans
- `@EnableAutoConfiguration`: turn on Boot's auto-configuration
- `@ComponentScan`: scan **this package and every package below it** for components

That last point is important: **every class you write must live in `com.example.todoapp` or a sub-package**, or Spring will never find it.

`SpringApplication.run(...)` creates the container, runs auto-configuration, starts Tomcat and keeps running until you stop it.

### 5. Run it

**In IntelliJ:**

1. Open `TodoAppApplication.java` (press <kbd>Shift</kbd> twice and type `TodoApp`).
2. Click the **green ▶ arrow** in the gutter next to `public class TodoAppApplication` (or next to `main`) and choose **Run 'TodoAppApplication'**.
3. The **Run** tool window opens with the console. Look for:

```
Tomcat started on port 8080 (http) with context path '/'
Started TodoAppApplication in 1.9 seconds
```

Open <http://localhost:8080>. You'll see a **Whitelabel Error Page** with a 404. That's correct: the server is running, but no code handles `/` yet.

What just happened, and the controls you'll use all the time:

- IntelliJ created a **run configuration** called *TodoAppApplication*. It now appears in the drop-down at the top of the window, next to the ▶ (Run), 🐞 (Debug) and ■ (Stop) buttons. Next time, just press **Run** (<kbd>Shift</kbd>+<kbd>F10</kbd>) or **Debug** (<kbd>Shift</kbd>+<kbd>F9</kbd>).
- **Stop** the app with the red ■ (<kbd>Ctrl</kbd>+<kbd>F2</kbd>). **Rerun** (<kbd>Ctrl</kbd>+<kbd>F5</kbd>) stops and starts it in one go.
- IntelliJ compiled your code **itself**, without Maven, then launched `main()`. That's quicker than going through Maven, and it's why the JDK from step 3 matters.
- With an **Ultimate** licence, the configuration is a *Spring Boot* type, with extra options such as **Active profiles** and **On 'Update' action**. With the free edition it's a plain *Application* type. Both run the same `main()`.

To see or edit a configuration: drop-down → **Edit Configurations…**. You'll add settings there later.

<details>
<summary>Terminal alternative</summary>

From `spring-boot/projects/todo-app/` (IntelliJ's Terminal opens there):

```bash
./mvnw spring-boot:run
```

The first run downloads Maven itself, so give it a minute. Stop the app with <kbd>Ctrl</kbd>+<kbd>C</kbd>.

You can also double-click **Plugins → spring-boot → spring-boot:run** in IntelliJ's **Maven** tool window. That runs the same Maven goal.

</details>

**Stop the app** before moving on.

### 6. Your first bean: a service

**Creating classes in IntelliJ:** in the **Project** window, right-click `src/main/java/com.example.todoapp` → **New → Java Class**, and type `greeting.GreetingService`. Typing a dotted name creates the `greeting` package and the class in one step, with the correct `package` line.

Create `src/main/java/com/example/todoapp/greeting/GreetingService.java`:

```java
package com.example.todoapp.greeting;

import org.springframework.stereotype.Service;

@Service
public class GreetingService {

    public String greet(String name) {
        return "Hello, " + name + "!";
    }
}
```

`@Service` is all Spring needs. At start-up, component scanning finds it and creates one `GreetingService` bean.

> We're putting classes in **feature packages** (`greeting`, and later `user`, `list`, `todo`) rather than layer packages (`controllers`, `services`). That keeps everything about one feature together. Either works with Spring, as long as everything sits under `com.example.todoapp`.

### 7. A controller that uses it

> **Imports in IntelliJ:** when you type a class name such as `GetMapping`, it turns red until it's imported. Put the cursor on it and press <kbd>Alt</kbd>+<kbd>Enter</kbd> → **Import class**. To make this automatic: **Settings → Editor → General → Auto Import → Java** → tick *Add unambiguous imports on the fly* and *Optimize imports on the fly*. Be careful when there's a choice. IntelliJ may offer several `Value` or `User` classes, so pick the package the guide shows.

Create `greeting/HelloController.java`:

```java
package com.example.todoapp.greeting;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    private final GreetingService greetingService;

    public HelloController(GreetingService greetingService) {
        this.greetingService = greetingService;
    }

    @GetMapping("/hello")
    public String hello(@RequestParam(defaultValue = "Spring") String name) {
        return greetingService.greet(name);
    }
}
```

Line by line:

- `@RestController` marks a web controller whose method return values are written **directly into the HTTP response body** (plain text here, or JSON for objects). In guide 02 you'll meet `@Controller`, which returns *templates* instead.
- **Constructor injection.** The class has exactly one constructor, so Spring uses it automatically and passes in the `GreetingService` bean. The field is `final`, so it can never be null or reassigned. This is the recommended way to inject dependencies.
- `@GetMapping("/hello")` means this method handles `GET /hello` requests.
- `@RequestParam(defaultValue = "Spring")` binds the `?name=` query parameter, falling back to `"Spring"` when it's missing.

Run the app again (<kbd>Shift</kbd>+<kbd>F10</kbd>) and visit:

- <http://localhost:8080/hello> → `Hello, Spring!`
- <http://localhost:8080/hello?name=Martyn> → `Hello, Martyn!`

> **Ultimate:** a small globe icon appears in the gutter next to `@GetMapping("/hello")`. Click it to open the URL, or to generate a request in IntelliJ's HTTP Client.

### 8. Watch it happen in the debugger

The debugger is the best tool for learning Spring, because you can stop *inside* a request and look around. Try it now:

1. Stop the app, then start it with **Debug** (🐞, <kbd>Shift</kbd>+<kbd>F9</kbd>).
2. In `GreetingService.greet`, click the **gutter to the left of the line number** on the `return` line. A red dot appears: that's a **breakpoint**.
3. Visit <http://localhost:8080/hello?name=Ada>. The browser hangs, and IntelliJ jumps to the breakpoint with the line highlighted.
4. Look at the **Debug** tool window:
   - **Threads & Variables** shows `name = "Ada"` and `this`, your `GreetingService`.
   - The **call stack** (frames list) shows how you got here. Scroll down: your `HelloController.hello`, then dozens of Spring frames (`DispatcherServlet.doDispatch`, `FrameworkServlet.service`, …), then Tomcat. That's the request cycle from guide 02, live.
5. Try the stepping controls:
   - **Step Over** (<kbd>F8</kbd>): run this line.
   - **Step Into** (<kbd>F7</kbd>): go into the method being called.
   - **Step Out** (<kbd>Shift</kbd>+<kbd>F8</kbd>): finish this method.
   - **Resume** (<kbd>F9</kbd>): carry on until the next breakpoint.
6. **Evaluate Expression** (<kbd>Alt</kbd>+<kbd>F8</kbd>): type `name.toUpperCase()` and press Enter. You can run any Java against the paused program.
7. Press **Resume** (<kbd>F9</kbd>). The browser gets its response.

Click the red dot to remove the breakpoint. **View Breakpoints** (<kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>F8</kbd>) lists them all.

From here on, there's no reason to run with **Run** rather than **Debug**. Debug costs almost nothing extra and lets you add breakpoints whenever you like.

> **Sidebar: when you'll still see `@Autowired`.**
>
> `@Autowired` is the older, explicit way of saying "inject something here". You don't need it on a class with **one constructor**, because Spring injects through that constructor automatically. You'll still meet it in these situations:
>
> - **Test classes.** JUnit, not Spring, creates test instances, so field injection is the norm there: `@Autowired MockMvc mockMvc;`. You'll write exactly this in guide 07. This is the one place in the series where we use it.
> - **More than one constructor.** Spring can't guess which one to use, so you put `@Autowired` on the one it should call.
> - **Optional dependencies.** `@Autowired(required = false)` on a setter, or better, inject `ObjectProvider<Foo>` or `Optional<Foo>`, for a bean that might not exist.
> - **Setter injection**, for a dependency that can change after construction, or (rarely) to break a circular dependency. A circular dependency is usually a sign the design needs rethinking.
> - **Field injection in old code:** `@Autowired private FooService foo;`. It works, but you can't make the field `final`, you can't construct the class in a test without Spring or reflection, and it hides how many dependencies a class has. Constructor injection makes a class with 9 dependencies look as bloated as it is, which is a useful warning.
>
> See the [annotation cheat sheet](annotations-cheatsheet.md) for other DI annotations: `@Qualifier`, `@Primary`, `@Value`, `@Bean`, `@Lazy`, `@Profile` and more.

### 9. Configuration with `application.properties`

Spring reads `src/main/resources/application.properties` at start-up. Built-in settings go here, and so can your own.

> **Ultimate:** start typing `server.` in this file and IntelliJ autocompletes every Spring Boot property, with its documentation (<kbd>Ctrl</kbd>+<kbd>Q</kbd>). <kbd>Ctrl</kbd>+click a property to jump to the Java class it binds to. The free edition treats the file as plain text.

Add:

```properties
spring.application.name=todo-app
app.greeting.prefix=Hello
```

Now inject the value into `GreetingService` with `@Value`:

```java
@Service
public class GreetingService {

    private final String prefix;

    public GreetingService(@Value("${app.greeting.prefix}") String prefix) {
        this.prefix = prefix;
    }

    public String greet(String name) {
        return prefix + ", " + name + "!";
    }
}
```

(`import org.springframework.beans.factory.annotation.Value;`)

`${...}` is a **property placeholder**. Change the value to `Howdy`, restart, and the greeting changes without any code change.

Other built-in properties you could try (don't keep them):

```properties
server.port=8081          # change the port
logging.level.org.springframework.web=DEBUG   # very chatty web logging
```

> **Sidebar: `@ConfigurationProperties`.** Once you have more than a couple of related settings, `@Value` gets messy. The type-safe alternative binds a whole prefix onto a record:
>
> ```java
> @ConfigurationProperties(prefix = "app.greeting")
> public record GreetingProperties(String prefix) {}
> ```
>
> You enable it with `@ConfigurationPropertiesScan` on the application class, then inject `GreetingProperties` like any other bean. Prefer this in real projects. We use `@Value` here because it's the simplest to see.

### 10. Peek at auto-configuration

Add this line to `application.properties` temporarily:

```properties
debug=true
```

Restart, and scroll up through the start-up log to the **CONDITIONS EVALUATION REPORT**. (In the **Run** console, click inside the output and press <kbd>Ctrl</kbd>+<kbd>F</kbd> to search it for `CONDITIONS`.) You'll see:

- **Positive matches**: auto-configurations that ran, and *why* (e.g. `DispatcherServletAutoConfiguration matched: @ConditionalOnClass found required class ...`)
- **Negative matches**: ones that didn't (e.g. `DataSourceAutoConfiguration: did not match`, because there's no database library yet)

This report is how you answer "why is Spring doing that?" and "why *isn't* it doing this?". Remove `debug=true` when you're done.

> **The IntelliJ way, without editing the file:** **Edit Configurations…** → your *TodoAppApplication* configuration → **Modify options → Add VM options**, and enter `-Ddebug=true`. (Ultimate's Spring Boot configuration has an **Enable debug output** checkbox instead.) Any property can be set this way, e.g. `-Dserver.port=8081`. It's handy for one-off experiments.

### 11. DevTools: automatic restarts

DevTools restarts the app whenever **compiled classes change**. When you run from IntelliJ, that happens when IntelliJ **builds** the project:

- **Manual:** after editing, **Build → Build Project** (<kbd>Ctrl</kbd>+<kbd>F9</kbd>). Within a second or two the **Run** console shows the app restarting.
- **Automatic (recommended):**
  1. **Settings → Build, Execution, Deployment → Compiler** → tick **Build project automatically**.
  2. **Settings → Advanced Settings** → tick **Allow auto-make to start even if developed application is currently running**.

  Now IntelliJ compiles shortly after you stop typing, and DevTools restarts the app.
- **Ultimate:** in the run configuration, set **On 'Update' action** and **On frame deactivation** to *Update classes and resources*. IntelliJ then rebuilds whenever you switch to the browser.

Try it: with the app running, change the greeting text in `GreetingService`, build (or wait), and refresh the browser.

A DevTools restart takes about a second, because it only reloads *your* classes and keeps the libraries loaded.

<details>
<summary>Terminal alternative</summary>

Leave `./mvnw spring-boot:run` running, and in a second terminal run `./mvnw compile` after editing.

</details>

> **DevTools restart vs the debugger's HotSwap.** While debugging, **Run → Debugging Actions → Reload Changed Classes** swaps in edited method bodies *without* restarting, but it can't handle new methods, fields or annotations. DevTools restarts handle anything. Use whichever is quicker for the change you made.

### 12. `.gitignore`

Initializr's `.gitignore` already ignores `target/`. Add one line now, because guide 03 will create a database folder:

```gitignore
data/
```

IntelliJ also creates a `.idea/` folder (project settings) and possibly `*.iml` files. Initializr's `.gitignore` already covers them. If you ever see them under **Commit** (<kbd>Ctrl</kbd>+<kbd>K</kbd>), add `.idea/` and `*.iml`.

> Your other Java tutorial folders have `.class` files staged in git. Compiled output shouldn't be committed, so consider adding `*.class` to a `.gitignore` at the repository root too.

---

## Your turn

### Exercise 1: a second endpoint

Add a `GET /hello/shout` endpoint to `HelloController` that returns the greeting in upper case, e.g. `/hello/shout?name=ada` → `HELLO, ADA!`. Put the upper-casing logic in `GreetingService`, not the controller.

<details>
<summary>Solution</summary>

```java
// GreetingService
public String shout(String name) {
    return greet(name).toUpperCase();
}

// HelloController
@GetMapping("/hello/shout")
public String shout(@RequestParam(defaultValue = "Spring") String name) {
    return greetingService.shout(name);
}
```

The controller's job is HTTP: reading parameters and choosing the response. Logic belongs in the service, where it's easy to unit test.

</details>

### Exercise 2: break dependency injection on purpose

1. Turn `GreetingService` into an **interface** with a `greet(String name)` method.
2. Create two implementations, `FriendlyGreetingService` and `FormalGreetingService` ("Good day, Martyn."), both annotated with `@Service`.
3. Run the app. Read the error carefully.
4. Fix it in **two different ways**: first with `@Primary` on one implementation, then (after removing `@Primary`) with `@Qualifier` on the constructor parameter.

<details>
<summary>What error you should see</summary>

```
Parameter 0 of constructor in com.example.todoapp.greeting.HelloController
required a single bean, but 2 were found:
    - formalGreetingService
    - friendlyGreetingService
```

Spring injects **by type**, and now there are two beans of type `GreetingService`, so it refuses to guess.

</details>

<details>
<summary>Solution</summary>

**Fix 1, `@Primary`.** This marks the default whenever there's ambiguity:

```java
@Service
@Primary
public class FriendlyGreetingService implements GreetingService { ... }
```

**Fix 2, `@Qualifier`.** The *injection point* picks a bean by name. The default bean name is the class name with a lower-case first letter:

```java
public HelloController(@Qualifier("formalGreetingService") GreetingService greetingService) {
    this.greetingService = greetingService;
}
```

`@Primary` is a global default; `@Qualifier` is a per-use choice. When you're done, go back to the single `GreetingService` class from step 9.

> **IntelliJ tips for this exercise:** to turn the class into an interface quickly, use **Refactor → Extract Interface…**. With two implementations, IntelliJ shows a green "implemented by" icon in the gutter of the interface; click it to jump between them. **(Ultimate)** also marks the ambiguous injection point in the controller with a warning *before* you run, along with the bean icons in the gutter. The rest of the series doesn't use the greeting code, so keeping it simple is fine.

</details>

---

## Checkpoint

- The *TodoAppApplication* run configuration starts the app from IntelliJ without errors, and **Debug** stops at a breakpoint in `GreetingService`.
- (Optional) `./mvnw spring-boot:run` also starts it from the terminal.
- <http://localhost:8080/hello?name=You> returns `Hello, You!` (or whatever your prefix is).
- <http://localhost:8080/hello/shout?name=you> returns `HELLO, YOU!`.

Expected files (generated ones omitted):

```
src/main/java/com/example/todoapp/
├── TodoAppApplication.java
└── greeting/
    ├── GreetingService.java
    └── HelloController.java
src/main/resources/
└── application.properties     ← spring.application.name, app.greeting.prefix
```

---

## Common errors

| Symptom | Cause / fix |
|---|---|
| `release version 25 not supported` / `invalid target release: 25` | In **IntelliJ**: set the Project SDK to 25 (**File → Project Structure → Project**), and check **Settings → Build, Execution, Deployment → Build Tools → Maven → Importing → JDK for importer**. In the **terminal**: fix `JAVA_HOME` and open a new terminal. |
| Everything is red / `Cannot resolve symbol 'springframework'` | Maven hasn't finished importing, or needs a refresh. Open the **Maven** tool window and click **Reload All Maven Projects** (🔄). If that doesn't fix it: **File → Invalidate Caches… → Invalidate and Restart**. |
| Maven import fails with `Unable to create injector` / `[Guice/MissingImplementation]: No implementation for ArtifactHandlerManager was bound` | IntelliJ is importing with the **Maven wrapper**, and the wrapper's Maven version (see `.mvn/wrapper/maven-wrapper.properties`, e.g. 3.10.x) is newer than your IntelliJ supports. **Settings → Build, Execution, Deployment → Build Tools → Maven → Maven home path** → choose **Bundled (Maven 3)**, then **Reload All Maven Projects**. `./mvnw` in the terminal is unaffected. Alternatively, update IntelliJ, or point the wrapper's `distributionUrl` at a 3.9.x release. |
| No green ▶ next to `main` | The project wasn't imported as Maven (`src/main/java` isn't blue). Right-click `pom.xml` → **Add as Maven Project**. |
| Code changes have no effect while running | IntelliJ hasn't rebuilt. <kbd>Ctrl</kbd>+<kbd>F9</kbd>, or set up auto-build (step 11). |
| Debugger never stops at the breakpoint | You started with **Run**, not **Debug**, or the breakpoint is on a line that never executes (a grey or crossed-out dot). |
| `Web server failed to start. Port 8080 was already in use.` | Another app (or an earlier run you didn't stop) holds the port. Stop it, or set `server.port=8081`. |
| Whitelabel 404 on your own endpoint | Typo in the path, *or* the class is outside `com.example.todoapp` so it was never scanned. Check the `package` line. |
| `required a bean of type '...' that could not be found` | The class is missing `@Service`/`@Component`, or it's outside the scanned package. |
| `Could not resolve placeholder 'app.greeting.prefix'` | The property is missing or misspelt in `application.properties`. |
| `./mvnw: Permission denied` (macOS/Linux) | `chmod +x mvnw` |
| `'.' is not recognized` / `mvnw not found` in PowerShell | Use `.\mvnw`, and run it from the project folder. |

---

## Recap

- **Spring Boot** = Spring Framework + starters + auto-configuration + embedded server.
- A **bean** is an object the container creates and manages. Stereotype annotations (`@Component`, `@Service`, `@Repository`, `@Controller`) plus **component scanning** register your classes as beans.
- **Constructor injection** is the default way to get dependencies. With a single constructor, no annotation is needed. `@Autowired` is still used in tests and a few edge cases.
- `application.properties` configures both Boot and your own code (`@Value`, `@ConfigurationProperties`).
- Auto-configuration **backs off** when you define your own bean. `debug=true` shows the report.
- **IntelliJ:** open the project folder; set the Project SDK; run and debug from the gutter ▶ (<kbd>Shift</kbd>+<kbd>F10</kbd> / <kbd>Shift</kbd>+<kbd>F9</kbd>); <kbd>Ctrl</kbd>+<kbd>F9</kbd> builds, which triggers a DevTools restart; <kbd>Alt</kbd>+<kbd>Enter</kbd> fixes imports; breakpoints and **Evaluate Expression** let you look inside a running request.

**Further reading**

- [Spring Boot reference: Developing with Spring Boot](https://docs.spring.io/spring-boot/reference/using/index.html)
- [Spring Framework reference: The IoC container](https://docs.spring.io/spring-framework/reference/core/beans.html)

**Next:** [02 — Controllers and Thymeleaf](02-controllers-and-thymeleaf.md)
