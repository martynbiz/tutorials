# Spec: Java lambdas series

A four-guide series that takes a Java developer who has never written a lambda from passing their first piece of behaviour as a parameter to the rules that trip people up, built around one reading-list example.

## Context

Lambdas are part of everyday Java, but they arrive with a cluster of ideas a learner meets all at once: functional interfaces, the `java.util.function` vocabulary, method references, variable capture and checked exceptions. Some existing series (threads among them) assume lambda syntax and don't teach it. This series teaches it properly, in the same layout and template as the threads series (`java/basics/threads/`).

The topic suits a series better than a single page. It has a natural order of questions: how do I pass behaviour around, what does the standard library already give me, how do I write it shorter, and what are the rules. Each guide answers one, building on the guide before.

The series is **standalone**. The repository doesn't impose an order on its series, so this one neither requires nor is required by any other series, and it doesn't link to threads or anything else as a prerequisite.

## Goals

- A reader who knows everyday Java finishes able to write lambdas in every syntax form, write and recognise functional interfaces, use the core `java.util.function` interfaces and their composition methods, use all four kinds of method reference, build comparators with `Comparator.comparing`, explain *effectively final*, and handle a checked exception inside a lambda.
- Every guide ends with code that runs, and every step's output is exact and checkable line by line.
- The series reads like the rest of the repository's series: same section template, same README shape, same exercise rules.
- Nothing beyond the JDK is needed: no build tool, no IDE, no libraries.

## Non-goals

| Topic | Instead |
| --- | --- |
| The Streams API | A separate future series |
| `Optional` | Belongs with streams. Out |
| Writing your own generic types or methods | Out. Readers only *read* signatures such as `Function<T, R>`. The one exception is guide 04's `ThrowingFunction` sidebar |
| Primitive specialisations (`IntPredicate`, `ToIntFunction`, `comparingInt`, …) | One sidebar in guide 02 saying they exist and why (to avoid boxing). Not taught |
| How lambdas compile (`invokedynamic`, no extra `.class` file) | Interview angle in guide 04 at most |
| `var` in lambda parameters | Out |
| The unnamed variable `_` for unused parameters | One sidebar in guide 01 |
| Recursive lambdas, currying, functional programming theory | Out |
| Serializable lambdas | Out |
| Lambdas with threads (`Runnable`, `Callable`, `CompletableFuture`) | Out. `Runnable` and `Comparator` may be *named* in guide 02 as older functional interfaces from before `java.util.function` |
| GUI event listeners | Out |
| Libraries for checked exceptions in lambdas (Vavr and similar) | Out. Guide 04 teaches the plain-JDK approach |

## Decisions

### Location and shape

- **The series is a folder: `java/basics/lambdas/`**, holding `README.md`, `01-your-first-lambdas.md`, `02-built-in-functional-interfaces.md`, `03-method-references.md`, `04-capture-and-the-edges.md`, and this spec (plus `PLAN.md` later). It matches the repository's series layout and CLAUDE.md.
- **The series stops short of streams.** The payoff comes from the collection methods that take lambdas: `List.removeIf`, `forEach`, `List.sort`, `Map.computeIfAbsent` and `Map.merge`. A future streams series can assume this one, but nothing here points to it as a next step it depends on.
- **Four guides:**

  | Guide | Covers |
  | --- | --- |
  | `01-your-first-lambdas.md` | Passing *behaviour* as a parameter. An anonymous class rewritten as a lambda. Your own functional interface (`BookFilter`) and `@FunctionalInterface`. Syntax forms: zero, one or several parameters, inferred types, expression body versus block body with `return`. First payoff: `removeIf`, `forEach`, and `sort` with a `Comparator` lambda |
  | `02-built-in-functional-interfaces.md` | `java.util.function`: `Predicate`, `Function`, `Consumer`, `Supplier`, `BiFunction`, `UnaryOperator`. How to read `Function<Book, String>`. Replacing `BookFilter` with `Predicate<Book>`. Composing with `and`, `or`, `negate` and `andThen` |
  | `03-method-references.md` | The four kinds (static, bound instance such as `System.out::println`, unbound instance such as `Book::title`, constructor such as `ArrayList::new`), and when a lambda reads better. `Comparator.comparing(...).thenComparing(...).reversed()` as the showcase |
  | `04-capture-and-the-edges.md` | Capturing local variables, *effectively final*, and the compiler error when you break it. `this` in a lambda versus in an anonymous class. Checked exceptions inside lambdas. Methods that return lambdas. `Map.computeIfAbsent` and `Map.merge` |

  Each guide adds one idea on top of the last: the mechanism, the standard library's vocabulary, the shorthand, then the rules. Comparators sit in guide 03 because `Comparator.comparing(Book::author)` is where method references click.

### Template

- **The guides use the threads template:** What you'll learn → Prerequisites for this guide → Concepts → Steps → Your turn → Checkpoint → Common errors → Recap (with Interview angle and Further reading). Sidebars are marked `> **Sidebar:** …`.
- **The README uses the threads README shape:** What you'll build → Versions → What you need to know already → Before you start → Where things live → The guides → How each guide works → Conventions → Deliberately out of scope.
- **The Checkpoint is "run it and compare"**, not JUnit tests, because the series is JDK only.

### Audience and setup

- **The reader** is comfortable with everyday Java: classes, objects, interfaces (including implementing one), `List`, `ArrayList`, `Map`, the enhanced `for` loop, exceptions (checked versus unchecked) and **records**.
- **They can use generics but not write them.** They read `List<String>` and `Map<String, Integer>` comfortably but have never declared a generic type. Guide 02 explains how to read `Function<Book, String>` without teaching how to write generic types.
- **Anonymous classes aren't assumed.** Guide 01 shows one briefly as "the old way", so the reader can see what a lambda replaces. It isn't a topic in its own right.
- **They've never seen a lambda**, including reading `() -> ...`.
- **No other series is assumed**, threads included.
- **The series targets Java 25, using only the JDK.** Code is run with `java Main.java`. The multi-file source launcher finds the other `.java` files in the same folder, so neither `javac` nor a build tool is needed.
- **The guides are terminal-first.** Readers can use any editor. The README gives IntelliJ a single line ("the green arrow next to `main` works too").

### Running example

- **A reading list carries through all four guides:**

  ```java
  record Book(String title, String author, int year, int pages) {}
  ```

  It has only `String` and `int` fields: no money and no `double`, so nothing distracts from formatting or rounding.
- **`Books.sample()` returns a fixed list of real, well-known books.** There's no file or random data, so every filter and sort gives exact, predictable output. The README's Conventions note that page counts come from one edition and may differ from the reader's copy.
- **The sample data must meet these rules:**
  - 8 to 10 books, so a full printout fits on one screen.
  - At least two authors with two or more books in different years, so `thenComparing(Book::year)` visibly changes the order.
  - Some books before 1950 and some after, so `removeIf` and "recent" filters remove some but not all.
  - Some books over 400 pages and some under. "Long and recent" keeps 2 or 3 books, and `negate()` keeps a different, non-empty set.
  - No two books share a page count, so sorting by pages has exactly one right answer. No exercise answer may depend on how ties happen to fall.
- **Guide 04 also uses a separate list of text lines** in the format `title;author;year;pages`, with exactly one bad line (a page count that isn't a number). It's the input for the checked-exception steps.

### Code layout

- **The reader's code lives in `workspace/java-basics/lambdas/`**, with no packages:

  ```
  workspace/java-basics/lambdas/
  ├── Book.java        ← the record; never changes
  ├── Books.java       ← Books.sample(); never changes after guide 01 (guide 04 adds parse)
  ├── BookFilter.java  ← guide 01's own functional interface; deleted in guide 02
  └── Main.java        ← starts fresh at the top of each guide
  ```

- **`Book` and `Books` are written in guide 01 and are shown in full under each guide's starting code**, so a reader who skips ahead can still begin.
- **`Main.java` is replaced at the start of each guide** with a short starting version, shown in full, which then grows step by step within that guide. The Checkpoint shows the guide's final `Main.java` in full. One growing `Main` across the whole series was rejected because by guide 04 it would hold dozens of demos.
- **`BookFilter` survives guide 01.** Guide 02 has a step that swaps it for `Predicate<Book>`, shows that nothing else needs to change, and ends by deleting the file.

### Checked exceptions (guide 04)

- **The trigger needs no files or I/O.** Guide 04 adds `Books.parse(String line) throws BookFormatException`, where `BookFormatException` is a small checked exception the reader writes. Calling it inside `lines.forEach(line -> ...)` fails to compile with "unreported exception BookFormatException; must be caught or declared to be thrown".
- **Step: catch and wrap.** Catch the exception inside the lambda and rethrow it as an unchecked exception, **keeping the cause** (for example `throw new IllegalArgumentException(..., e)`). The bad line in the input makes it actually throw, so the reader sees the cause in the stack trace.
- **Step: your own throwing interface, non-generic:**

  ```java
  @FunctionalInterface
  interface LineParser {
      Book parse(String line) throws BookFormatException;
  }
  ```

  It teaches that your own functional interface can declare `throws` and `java.util.function`'s can't. It echoes guide 01's `BookFilter`, and `Books::parse` fits it as a method reference, which brings guide 03 back. It needs no generics, which keeps the audience rule.
- **A sidebar shows the generic `ThrowingFunction<T, R, E extends Exception>`** that libraries use. No exercise relies on it.
- **The guides never use an empty `catch`.** Swallowing the exception is a Common errors entry.

### Output and exercises

- **All output is exact.** Unlike threads, nothing here is nondeterministic, so step output blocks and Checkpoints show exactly what the reader should see, never "one possible run".
- **Three exercises per guide, always in this pattern:**
  1. **Extend:** apply a step's technique to something new. Examples: 01, a `BookFilter` for one author's books. 03, sort by page count, longest first.
  2. **Break and fix:** make a mistake the guide warned about, see it fail, and fix it. Examples: 01, put `@FunctionalInterface` on an interface with two abstract methods. 04, change a captured variable after the lambda and fix the "effectively final" error.
  3. **Predict, then run:** the answers are certain. Examples: "what does `isLong.negate().and(isRecent)` keep?" or "in what order does `comparing(Book::author).reversed()` put the books?"
- **Solutions go in collapsible `<details>` blocks.**
- **Exercises only practise what the steps taught.** Every API in a solution must appear in an earlier step of the same guide or an earlier guide. Anything new is taught as a step instead.

### Sidebars

- **Guide 01:** the unnamed variable `_` for unused lambda parameters (final since Java 22).
- **Guide 02:** primitive specialisations (`IntPredicate`, `ToIntFunction`, `comparingInt`) exist to avoid boxing.
- **Guide 04:** the generic `ThrowingFunction<T, R, E>`.

## Assumptions

- Readers have JDK 25 installed, or can follow the README's setup to install it.
- Readers can read a generic signature such as `Function<Book, String>` once guide 02 explains it, without generics being taught.
- Each guide showing `Book`, `Books` and its starting `Main` in full is enough for a reader who skips ahead.
- The multi-file source launcher and the unnamed variable `_` are final in Java 25. Nothing needs `--enable-preview`.

## Open questions / Risks

- **Page counts vary by edition.** The Conventions note covers it, but readers with a different edition may question the numbers.
- **The sample data has to satisfy every rule at once** (authors with several books, no ties on page count, filters that keep some books but not all). A mistake breaks the exact-output promise across all four guides, so `PLAN.md` should fix and check the list first.
- **Guide 04 is the busiest guide:** capture, `this`, checked exceptions (two steps), returning lambdas, and `Map` methods. It may need trimming once the steps are listed.
- **Not yet decided:**
  - The exact book list and guide 04's text lines.
  - The exact step list within each guide.
  - The content of each guide's Interview angle.
  - The README's Quick reference, if it has one.
  - Which unchecked exception type wraps `BookFormatException` (`IllegalArgumentException` is the working assumption).

  These belong in `PLAN.md`.

## Verification

- **Every code listing compiles and runs on JDK 25** with `java Main.java` from `workspace/java-basics/lambdas/`. Each guide's starting `Book` and `Books` match the previous guide's.
- **Every output block matches the actual output exactly**, character for character.
- **The sample data meets every rule** under Running example: no tied page counts, at least two multi-book authors, filters that keep some books but not all.
- **Guide 04's lambda without a fix fails to compile** with the stated error, and the catch-and-wrap version prints a stack trace showing `BookFormatException` as the cause.
- **Each exercise solution** uses only APIs taught in an earlier step.
- **Each "predict, then run" answer** is certain and doesn't depend on tie-breaking.
