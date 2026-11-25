# Java lambdas: Reading List

A series that teaches you to write and use **lambdas** in Java: to pass behaviour into a method, to use the JDK's ready-made **functional interfaces**, to shorten lambdas into **method references**, and to handle the rules that catch people out, such as captured variables and checked exceptions.

Lambdas are everywhere in modern Java, and they're often met all at once, in a line of someone else's code that does five things. This series takes them one idea at a time. Each guide starts from a small working program and a problem with it, and only then shows the lambda that solves it.

---

## What you'll build

A **reading list**: ten books, each a `record` with a title, an author, a year and a page count. Over the four guides, you'll filter it, sort it, reshape it and group it, each time with less code:

```
  guide 01   one filter method + a lambda per test ──▶ long books, old books, any books
             removeIf, forEach, sort ──▶ trim, print and order the list in a line each

  guide 02   your own interface ──▶ the JDK's Predicate, Function, Consumer, Supplier
             small tests combined: "long and recent", "not long"

  guide 03   book -> book.title() ──▶ Book::title
             sort by author, then by year, then the other way round

  guide 04   lambdas that capture variables, lambdas returned from methods,
             books grouped by author, and reading books from text that may be wrong
```

Every guide uses the same two files for the books, and a fresh `Main.java` of its own.

---

## Versions

| Thing | Version | Notes |
| --- | --- | --- |
| Java | **25** (LTS) | Any OpenJDK 25 build. [Eclipse Temurin](https://adoptium.net/temurin/releases/?version=25) is a good free choice |
| Build tool | None | Code runs straight from source with `java Main.java` |
| Libraries | None | Everything is in the JDK: `java.util` and `java.util.function` |
| IDE | None needed | Any text editor will do |

Nothing in the series needs `--enable-preview`. Every feature it uses is final in Java 25.

---

## What you need to know already

This series assumes you're comfortable with everyday Java:

- **Classes, objects and interfaces**, including how a class implements an interface.
- **Lists, maps and loops:** `List`, `ArrayList`, `Map`, the enhanced `for` loop.
- **Exceptions:** `try`/`catch`, and checked versus unchecked.
- **Records:** a one-line `record` declaration and its accessors.
- **Using generic types** such as `List<String>` or `Map<String, Integer>`. You don't need to have written a generic class of your own. Guide 02 shows you how to read types like `Function<Book, String>`.

You **don't** need to have seen a lambda before. You don't need any other series in this repository either.

---

## Before you start

### 1. Install JDK 25

You need the **JDK** (Java Development Kit), not just a Java runtime, because it includes the compiler that `java Main.java` uses behind the scenes.

Any OpenJDK 25 build works. [Eclipse Temurin](https://adoptium.net/temurin/releases/?version=25) is a good free choice: pick your operating system and download the **JDK** installer.

On Windows, the Temurin installer offers to **Add to PATH** and **Set JAVA_HOME**. Turn both on.

### 2. Check it works

Open a **new** terminal (PowerShell, Git Bash, or Terminal on macOS or Linux), so it picks up the new `PATH`, and run:

```bash
java -version
```

You should see version 25, for example:

```
openjdk version "25.0.2" 2026-01-20 LTS
```

If it says `command not found` or `is not recognized`, the JDK's `bin` folder isn't on your `PATH`. Re-run the installer with **Add to PATH** turned on, or add the folder yourself, then open a new terminal.

If it shows an older version, another JDK comes first on your `PATH`. To see every `java` your terminal can find, in the order it searches them, use this in PowerShell:

```powershell
where.exe java
```

or this in Git Bash, macOS or Linux:

```bash
which -a java
```

Move the JDK 25 folder above the others in your `PATH`.

---

## Where things live

```
tutorials/
├── java/basics/lambdas/             ← these guides
└── workspace/java-basics/lambdas/   ← your code (git-ignored)
    ├── Book.java                    ← one book: title, author, year, pages
    ├── Books.java                   ← the reading list, Books.sample()
    ├── BookFilter.java              ← guide 01's own functional interface (gone by guide 02's end)
    └── Main.java                    ← each guide's program
```

Guide 01's first step creates the folder. You keep the same folder for the whole series.

**Running it.** From `workspace/java-basics/lambdas/`:

```bash
java Main.java
```

`java` compiles `Main.java` in memory, finds the other `.java` files it uses in the same folder, and runs it. There are no `.class` files and no build step. If you use IntelliJ IDEA, the green arrow next to `main` works too, but the guides always show the terminal.

---

## The guides

Follow them **in order**. Each builds on the ideas of the one before, and ends with a program that runs.

| # | Guide | You'll learn |
| --- | --- | --- |
| 01 | [Your first lambdas](01-your-first-lambdas.md) | Passing behaviour into a method. Named classes, anonymous classes, then lambdas. Functional interfaces and `@FunctionalInterface`. Lambda syntax forms. `removeIf`, `forEach`, and `sort` with a `Comparator` |
| 02 | Built-in functional interfaces | `Predicate`, `Function`, `Consumer`, `Supplier`, `UnaryOperator`, `BiFunction`. Reading their generic types. Combining them with `and`, `or`, `negate` and `andThen` |
| 03 | Method references | The four kinds of method reference, and when a lambda reads better. `Comparator.comparing`, `thenComparing` and `reversed` |
| 04 | Capture and the edges | Captured variables and *effectively final*. Methods that return lambdas. `this` in a lambda. `computeIfAbsent` and `merge`. Checked exceptions inside lambdas |

Allow roughly an evening per guide.

---

## How each guide works

1. **What you'll learn**
2. **Concepts**: the *why*, before any code.
3. **Steps**: numbered, with the code to type and the output to expect.
4. **Your turn**: exercises that practise what the steps taught. Solutions are hidden in collapsible blocks.
5. **Checkpoint**: what you should see, and the files you should have.
6. **Common errors**: messages you're likely to hit, and what they really mean.
7. **Recap**, with interview questions and further reading.

Boxes like this are **sidebars**. They're useful background, but you can skip them on a first read:

> **Sidebar:** …

---

## Conventions

- **Output is exact.** Nothing in this series is random or depends on timing, so every output block shows exactly what you should see. Compare yours line by line. If a line differs, something in your code differs too.
- **Page counts are from one edition.** Your copy of a book may have a different number of pages. The series' numbers are what matter for the code.
- **Authors are full names**, such as `"Jane Austen"`. So sorting by author sorts by **first** name: Donna Tartt comes before Jane Austen.
- **`Main.java` starts fresh in each guide.** `Book.java` stays the same throughout, and so does `Books.java` until guide 04 adds two methods to it. Each guide after 01 shows them in full at the start, so you can begin any guide from an empty folder.
- **No packages.** Every file sits in the same folder, so `java Main.java` can find it.
- **Commands** are shown for PowerShell and for Git Bash, macOS or Linux where they differ. `java Main.java` is the same everywhere.
