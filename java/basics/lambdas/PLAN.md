# Java lambdas: implementation plan

How the series specified in [SPEC.md](SPEC.md) gets built: a working reference project, built guide by guide, and the README and four guides written from it.

## Context

The decisions are fixed in [SPEC.md](SPEC.md), so this plan doesn't repeat or revisit them. In short:

- **The series:** a README and four guides in this folder, built around one reading list of `Book` records. It's standalone and links to no other series.
- **The code:** Java 25, JDK only, run with `java Main.java`. No streams.
- **The template:** the same as the threads series (`java/basics/threads/`).
- **Output:** always exact. Nothing in this series is nondeterministic, so every output block is the real output.
- **Exercises:** only practise what the steps taught.

### Stack

| Choice | Value | Source |
|---|---|---|
| Language | Java 25. The JDK installed here is Temurin 25.0.2 | Spec, and checked on this machine |
| Libraries | None: the JDK only (`java.util.function`, `java.util`) | Spec |
| Build | None. Multi-file source launcher: `java Main.java` | Spec |
| Tests | None. Verification is by running the code and comparing its output with the guide character for character | Spec |
| Workflow | No TDD. Each milestone is built, run and checked by hand | Follows from "no tests" |
| Docker, deployment | Not applicable | |
| README | Yes: the series README in this folder, started in milestone 1 and finished in milestone 5 | Spec, and the repo's [CLAUDE.md](../../../CLAUDE.md) |

### Where things live

- **Guides, README, spec and plan:** this folder, `java/basics/lambdas/`. Only these are committed.
- **Reference project:** `workspace/java-basics/lambdas-reference/`.
  - It's a local git repo with one commit per guide, tagged `guide-01` to `guide-04`.
  - `workspace/` is git-ignored, so it's never committed to the tutorials repo.
  - It isn't in `workspace/java-basics/lambdas/`, because that's the path the guides give readers.
- **Build notes:** `workspace/java-basics/lambdas-build-notes.md`. Milestone 0's results go here: versions, exact compiler error texts, and the sample-data check.
- **Reader's files** (no packages):
  - `Book.java`, `Books.java`: written in guide 01. `Books.java` gains `parse` and `lines()` in guide 04.
  - `BookFilter.java`: guide 01 only. Deleted in guide 02.
  - `ReadingList.java`: guide 04 only, for the `this` step (settled here; see milestone 4).
  - `BookFormatException.java`, `LineParser.java`: guide 04.
  - `Main.java`: starts fresh in each guide.
- **Guide files:**
  - `01-your-first-lambdas.md`
  - `02-built-in-functional-interfaces.md`
  - `03-method-references.md`
  - `04-capture-and-the-edges.md`

### Guide format

As the spec sets out:

- What you'll learn → Prerequisites for this guide → Concepts → Steps → Your turn (3 exercises: extend, break and fix, predict then run) → Checkpoint → Common errors → Recap, with Interview angle and Further reading
- Sidebars as `> **Sidebar:** …`
- Each guide after 01 opens with "Starting code": `Book.java` and `Books.java` in full, plus that guide's fresh starting `Main.java`
- Each guide ends with a link to the next

The threads README's "Before you start" section (JDK 25 install and `java -version` check) is reused for the new README, adapted where it mentions threads. It's the same setup, so it isn't rewritten from scratch.

### The sample data (settled here)

The spec left the exact list to this plan. This list was checked on JDK 25 against every rule in the spec (see milestone 0):

```java
new Book("Emma", "Jane Austen", 1815, 474),
new Book("Nineteen Eighty-Four", "George Orwell", 1949, 328),
new Book("The Hobbit", "J.R.R. Tolkien", 1937, 310),
new Book("Klara and the Sun", "Kazuo Ishiguro", 2021, 307),
new Book("Dune", "Frank Herbert", 1965, 412),
new Book("Pride and Prejudice", "Jane Austen", 1813, 432),
new Book("Wolf Hall", "Hilary Mantel", 2009, 653),
new Book("Animal Farm", "George Orwell", 1945, 112),
new Book("The Goldfinch", "Donna Tartt", 2013, 771),
new Book("Never Let Me Go", "Kazuo Ishiguro", 2005, 288)
```

- **10 books, with no tied page counts.**
- **Three authors have two books each** (Austen, Orwell, Ishiguro). Each pair is listed **later book first**, so sorting by author alone leaves them in the wrong year order and `thenComparing(Book::year)` visibly fixes it.
- **5 books before 1950 and 5 after.** `removeIf(book -> book.year() < 1950)` leaves 5.
- **The thresholds the guides use:** long means `pages() > 400` (5 books), and recent means `year() >= 2000`.
  - Long and recent: Wolf Hall, The Goldfinch.
  - Not long, and recent: Klara and the Sun, Never Let Me Go.
- **`Books.sample()` returns `new ArrayList<>(List.of(...))`**, a fresh, changeable list on every call. That way `removeIf` and `sort` work, and each demo starts from the full list. The `List.of` alone version becomes a Common errors entry (`UnsupportedOperationException`).
- **Guide 04's text lines**, returned by `Books.lines()`:

  ```
  Middlemarch;George Eliot;1871;880
  Atonement;Ian McEwan;2001;371
  Beloved;Toni Morrison;1987;three hundred
  Small Island;Andrea Levy;2004;441
  ```

  The third line is the one bad line. It sits before a good line, so the reader sees that a thrown exception stops the remaining lines being read.

## Milestones

### 0. [x] Check the facts the guides rely on

Settles the exact texts and behaviours that every later milestone quotes. The results go in `lambdas-build-notes.md`.

**Launcher and data.** Using throwaway files in `workspace/java-basics/lambdas-reference/scratch/`, confirm on JDK 25 that:

- `java Main.java` finds `Book.java` and `Books.java` in the same folder, with no packages
- a record's `toString()` prints as `Book[title=Emma, author=Jane Austen, year=1815, pages=474]`. Guides print books this way, and this line fits in a normal code block
- the sample list meets every rule above: rerun the check script from planning and keep its output in the build notes
- `TreeMap<String, …>` iterates authors in the order `Donna Tartt`, `Frank Herbert`, `George Orwell`, `Hilary Mantel`, `J.R.R. Tolkien`, `Jane Austen`, `Kazuo Ishiguro` (full names, by decision; see open question 1)
- the unnamed variable `_` in a lambda parameter compiles without `--enable-preview` (checked during planning: it does)

**Exact compiler and runtime messages.** Record the full text of each of these, because Common errors quotes them:

- `@FunctionalInterface` on an interface with two abstract methods
- a lambda assigned to a type that isn't a functional interface (for example `Object`)
- a block-body lambda missing its `return`
- `removeIf` on a `List.of(...)` list (`UnsupportedOperationException`)
- `Predicate<int>` (a primitive as a type argument)
- `Book::pages()` with parentheses
- a non-static method referenced as `Main::isClassic` from `main`
- `Comparator.comparing(b -> b.author()).reversed()` (inference falls back to `Object`)
- an ambiguous method reference, for example `Integer::toString` as a `Function<Integer, String>`
- a local variable changed after a lambda captures it ("must be final or effectively final")
- `this.owner()` inside an anonymous class in `ReadingList`
- an unreported `BookFormatException` inside `forEach`
- `computeIfAbsent(key, new ArrayList<>())`, passing a list instead of a function

**Stack trace shape.** Run the catch-and-wrap version and record the stack trace. Check that it shows `IllegalArgumentException`, then `Caused by: BookFormatException`, then `Caused by: NumberFormatException`. The line numbers depend on the reader's file, so the guide shows the first lines of each `Caused by` and says that line numbers will differ. This is the one output in the series that isn't exact character for character.

**Verify:**
- `lambdas-build-notes.md` has every message above, copied from real output.
- The sample-data check passes.
- If any message differs from what this plan expects, note it, and word the guide to match the real message.

### 1. [x] Guide 01: Your first lambdas, and the README skeleton

**As built:**
- **Exercise 2 became "a block body that forgets to return"**, not the `@FunctionalInterface` break and fix, because step 6 already walks through adding a second abstract method.
- **Step 6 shows the annotation's own error with `java BookFilter.java`.** With `BookFilter` in its own file, `java Main.java` only reports the errors at the lambdas.
- **Step 3's `LongBookFilter` and `Before1950Filter`** are small classes at the bottom of `Main.java`, deleted again in steps 4 and 5.

**Depends on:** milestone 0.

**Delivers:** a `filter(List<Book>, BookFilter)` method used with a named class, an anonymous class and a lambda, followed by `removeIf`, `forEach` and `sort` on the sample list.

**Reference project files (tag `guide-01`):**
- **`Book.java`:** the record from the spec.
- **`Books.java`:** `sample()`, returning the list above.
- **`BookFilter.java`:** `@FunctionalInterface interface BookFilter { boolean test(Book book); }`. The method is named `test` on purpose, so that swapping in `Predicate<Book>` in guide 02 changes only the type name.
- **`Main.java`:** a `static List<Book> filter(List<Book> books, BookFilter filter)` method, then the demos.

**Guide steps:**
1. Set up the folder. `Book.java` and `Books.java`, and a `Main` that prints the sample list with an enhanced `for`.
2. The problem: two near-identical methods, `longBooks` and `booksBefore1950`, where only the `if` differs.
3. Pass the test in. The `BookFilter` interface, a named class `LongBookFilter implements BookFilter`, and one `filter` method replacing both.
4. The old way: the same filter as an anonymous class, for recognition only.
5. The lambda: `filter(books, book -> book.pages() > 400)`. Taking the anonymous class apart line by line to show what the lambda leaves out.
6. What makes a functional interface: one abstract method, and `@FunctionalInterface` as the compiler's check. Add a second abstract method, see the error, and remove it.
7. Syntax forms: inferred versus declared parameter types, `(book) ->` versus `book ->`, expression body versus block body with `return`. A table of the zero-, one- and two-parameter forms. The zero-parameter form gets its first runnable example in guide 02 (`Supplier`), so no guide 01 exercise needs it.
8. Collection methods that take lambdas: `removeIf(book -> book.year() < 1950)`, then `forEach(book -> System.out.println(book))`. The guide says these take an interface just like `BookFilter`, and guide 02 names it.
9. Sorting: `Comparator` as an older functional interface with two parameters. Its contract (negative, zero or positive), `Integer.compare`, and `books.sort((a, b) -> Integer.compare(a.pages(), b.pages()))`. Then by title with `a.title().compareTo(b.title())`.

**Concepts:**
- behaviour as a value: passing *what to do* into a method
- interfaces as the contract that makes it possible
- what a lambda is: a short way to implement a one-method interface
- type inference: how the compiler knows what `book` is

**Sidebar:** the unnamed variable `_` for a parameter you don't use, for example `(a, _) -> …`.

**Your turn:**
1. Extend: a lambda `BookFilter` that keeps only George Orwell's books, passed to `filter`.
2. Break and fix: put `@FunctionalInterface` on an interface with two abstract methods, read the error, and fix it.
3. Predict, then run: after `removeIf(book -> book.year() < 1950)`, how many books are left? (5.) Sorted with `(a, b) -> Integer.compare(b.pages(), a.pages())`, which book comes first? (The Goldfinch.)

**Common errors:**
- `UnsupportedOperationException` from `removeIf` or `sort` on a `List.of` list
- a lambda where the target isn't a functional interface
- a missing `return` in a block body, or `return` in an expression body
- a second abstract method under `@FunctionalInterface`

**Interview angle:**
- what a functional interface is, and whether it can have default methods (yes)
- lambda versus anonymous class, in brief (guide 04 has more)
- what type inference does for a lambda

**README.md (skeleton):**
- What you'll build: the reading list, and what each guide does to it
- Versions (Java 25, Temurin, from milestone 0)
- What you need to know already: everyday Java, records, reading generic types such as `List<String>`. No lambdas, and no other series
- Before you start: JDK setup, adapted from the threads README
- Where things live: a tree of `workspace/java-basics/lambdas/`
- How to run, terminal first, plus one IntelliJ line
- The guides: only 01 linked so far
- How each guide works
- Conventions:
  - output is exact, so compare yours line by line
  - page counts are from one edition; yours may differ
  - authors are stored as full names (`"Jane Austen"`), so sorting by author sorts by first name
  - `Main.java` starts fresh in each guide, while `Book.java` and `Books.java` stay
  - PowerShell notes where commands differ

**Verify:**
- `java Main.java` at tag `guide-01` prints exactly what the guide shows.
- Each Common errors message matches milestone 0's notes.
- Each Your turn solution has been run.

### 2. [x] Guide 02: Built-in functional interfaces

**As built:**
- **Step 2 ends with a payoff:** `shortBooks.removeIf(isLong)` now compiles, printing a *Short books* section, and the guide quotes the compile error the same line gives while `isLong` is still a `BookFilter`. That error is also an extra Common errors entry.
- **`map` works on the long books**, so `titles` (reused by `replaceAll` in step 6) holds five titles, not ten, which keeps the printed lists short.
- **Step 2's list is `shortBooks`**, because the plan names step 5's `Supplier` `shelf`.
- **The primitive specialisations sidebar sits after step 6**, where `Integer` as a type argument first comes up.
- **Step 7 shows a simplified `Predicate.and`** (`return t -> test(t) && other.test(t);`), to show a default method returning a new lambda. The same shape was compiled and run separately.
- **Exercise 1's answer is the same five books as `isLong`**, because both Austen books are already long. The solution says so, and shows the `and` version for contrast.
- **The README is unchanged.** Milestone 2 doesn't list it, so guide 02 is still unlinked in its table until milestone 5.

**Depends on:** milestone 1.

**Delivers:** the guide 01 filter rewritten with `Predicate<Book>`, and a demo of each core interface on the sample list, ending with composed predicates and functions.

**Reference project files (tag `guide-02`):**
- **`BookFilter.java`:** deleted.
- **`Main.java`:** `filter(List<Book>, Predicate<Book>)` and `map(List<Book>, Function<Book, String>)`, which returns a `List<String>`. Neither method declares its own type parameters, which keeps the audience rule. Then the demos.

**Guide steps:**
1. Starting code: `Book`, `Books`, `BookFilter`, and a starting `Main` with guide 01's `filter` method.
2. `Predicate<Book>`: change `filter`'s parameter type from `BookFilter` to `Predicate<Book>`. The lambda calls don't change. Delete `BookFilter.java`. How to read `Predicate<Book>`: "a test of a `Book`". This is also the type `removeIf` takes.
3. `Function<Book, String>`: a `map` method that turns books into titles, then authors. How to read the two type arguments: input, then result.
4. `Consumer<Book>`: what `forEach` takes. Store a printing lambda in a variable and pass it in.
5. `Supplier`: the zero-parameter form at last. `Supplier<List<Book>> shelf = () -> Books.sample();`, calling `get()` twice to show the lambda runs each time.
6. `UnaryOperator<String>` and `BiFunction`:
   - `UnaryOperator<String>` with `List.replaceAll` on the titles list, upper-casing every title.
   - `BiFunction<Book, Integer, Book>` returns a new edition with extra pages, a reminder that records don't change.
7. Composing predicates: `isLong.and(isRecent)`, `or`, `negate()`. Each returns a **new** predicate.
8. Composing functions: `Function.andThen`, title then upper case.
9. Older functional interfaces: `Comparator` (guide 01) and `Runnable` were functional interfaces before `java.util.function` existed. Named only, with no `Runnable` demo.

**Sidebar:** primitive specialisations (`IntPredicate`, `ToIntFunction`, `comparingInt`). They exist to avoid boxing an `int` into an `Integer`. You'll meet them in IDE suggestions, and the series doesn't use them.

**Concepts:**
- why a standard vocabulary helps: any method that takes a `Predicate<Book>` accepts any lambda you've already written
- the shape table: takes / returns for `Predicate`, `Function`, `Consumer`, `Supplier`, `UnaryOperator`, `BiFunction`, with each one's method name (`test`, `apply`, `accept`, `get`)
- reading generic type arguments, without writing any
- default methods: how an interface with one abstract method can still offer `and` and `andThen`

**Your turn:**
1. Extend: a `Predicate<Book>` for books by Jane Austen, combined with `isLong` using `or`. The answer is a list the reader can check.
2. Break and fix: call `isLong.and(isRecent);` as a statement, then filter with `isLong`. The result is unchanged, because `and` returns a new predicate. Fix it by keeping the result.
3. Predict, then run: what does `isLong.negate().and(isRecent)` keep? (Klara and the Sun, Never Let Me Go.)

**Common errors:**
- calling the wrong method name (`apply` on a `Predicate`)
- `Predicate<int>`
- the wrong number of type arguments (`Function<Book>`)
- ignoring the result of `and`, `or`, `negate` or `andThen`
- `replaceAll` on a `List.of` list

**Interview angle:**
- the core interfaces and their methods
- why `java.util.function` exists
- how default methods make composition possible
- why primitive specialisations exist

**Verify:**
- Output at tag `guide-02` matches the guide exactly.
- `BookFilter.java` is gone, and nothing else changed when it was swapped out.
- Each solution has been run.

### 3. [ ] Guide 03: Method references

**Depends on:** milestone 2.

**Delivers:** the four kinds of method reference used on the sample list, ending with a `Comparator.comparing(Book::author).thenComparing(Book::year)` sort.

**Reference project files (tag `guide-03`):**
- **`Main.java`:** a starting `map` helper restated from guide 02, a `static boolean isClassic(Book book)` (before 1950), and the demos.

**Guide steps:**
1. Starting code: `Book`, `Books`, and a starting `Main` with the `map` helper.
2. A lambda that only calls a method: `book -> book.title()` becomes `Book::title`. This is the **unbound instance** kind: the book is supplied when the function is called.
3. **Bound instance:** `books.forEach(System.out::println)`. The object (`System.out`) is fixed when the reference is made.
4. **Static:** `books.removeIf(Main::isClassic)`.
5. **Constructor:** `Supplier<List<Book>> empty = ArrayList::new;`.
6. Unbound with two parameters: `titles.sort(String::compareToIgnoreCase)`. The first parameter becomes the object the method is called on.
7. When a lambda reads better: `book -> book.pages() > 400` has no method to point at, and arguments in a different order don't fit a reference.
8. `Comparator.comparing(Book::author)`. The Austen, Orwell and Ishiguro pairs come out in the wrong year order. One line notes that the order is by the whole string, so Donna Tartt comes first.
9. `.thenComparing(Book::year)` fixes the order within each author.
10. `.reversed()`, which reverses the whole comparator, and how to reverse only the year: `thenComparing(Comparator.comparing(Book::year).reversed())`.

**Concepts:**
- a method reference is a lambda written as "call this method"
- the four kinds, in a table with the equivalent lambda beside each
- how the functional interface decides which kind fits

**Your turn:**
1. Extend: sort by page count, longest first, with `Comparator.comparing(Book::pages).reversed()`.
2. Break and fix: write `Book::pages()` with parentheses, read the error, and fix it.
3. Predict, then run: with `comparing(Book::author).thenComparing(Book::year).reversed()`, which book comes first? (Klara and the Sun: the last author alphabetically, latest year.)

**Common errors:**
- `Book::pages()` with parentheses
- a non-static method referenced as `Main::isClassic` from `main`
- `comparing(b -> b.author()).reversed()` failing because inference falls back to `Object`, fixed with `Book::author` or `(Book b) ->`
- an ambiguous method reference

**Interview angle:**
- the four kinds, and bound versus unbound
- whether a method reference is faster than a lambda (no meaningful difference)
- how `Comparator.comparing` builds a comparator from a function

**Verify:**
- Output at tag `guide-03` matches the guide exactly, including all three sort orders.
- No predict answer depends on how ties fall. The only tied keys are authors, and `List.sort` is stable, but no exercise asks about tied keys anyway.
- Each solution has been run.

### 4. [ ] Guide 04: Capture and the edges

**Depends on:** milestone 3.

**Delivers:** capture and *effectively final*; a method that returns a predicate; `this` in a lambda; grouping and totalling with `computeIfAbsent` and `merge`; and parsing text lines with both checked-exception fixes.

**Reference project files (tag `guide-04`):**
- **`ReadingList.java` (settled here):** `record ReadingList(String owner, List<Book> books)` with a `Supplier<String> describer()` method returning `() -> this.owner() + " has " + books.size() + " books"`. It exists because `Main` is static and has no `this`. Showing `this` in an anonymous class by printing it would give a hash code that changes between runs, so the anonymous-class side of the step is shown as a **compile error** (`this.owner()` doesn't exist on the anonymous class). That keeps every output exact.
- **`BookFormatException.java`:** `class BookFormatException extends Exception`, with a `(String message, Throwable cause)` constructor.
- **`Books.java`:** gains `lines()`, returning the four text lines above, and `static Book parse(String line) throws BookFormatException`. It splits on `;`, checks there are four fields, and wraps a `NumberFormatException` with the cause kept.
- **`LineParser.java`:** the non-generic interface from the spec.
- **`Main.java`:** `static Predicate<Book> longerThan(int pages)`, a `parseAll(List<String> lines, LineParser parser) throws BookFormatException` using a plain `for` loop, and the demos.

**Settled here (open question in the spec):** the wrapper is **`IllegalArgumentException`**. The problem is bad input, and that's what the type means. `UncheckedIOException` was rejected because nothing here is I/O.

**Guide steps:**
1. Starting code: `Book`, `Books` (from guide 01), and a starting `Main` with guide 02's `filter` method.
2. Capture: `int minPages = 400; filter(books, book -> book.pages() > minPages)`.
3. Effectively final: change `minPages` after the lambda and read the error. Why: the lambda keeps a **copy** of the value, so Java forbids a change that would make the copy and the variable disagree.
4. Methods that return lambdas: `longerThan(400)` returns a `Predicate<Book>` that captures its parameter. Combine it with `and`, from guide 02.
5. `this` in a lambda versus an anonymous class, using `ReadingList`. The lambda's `this` is the `ReadingList`. In an anonymous class, `this` is the anonymous object, shown by the compile error.
6. `Map.computeIfAbsent`: group books by author in a `TreeMap<String, List<Book>>`. One line explains `TreeMap` as a `Map` that keeps its keys sorted, so the output is exact. `computeIfAbsent(book.author(), author -> new ArrayList<>()).add(book)`.
7. `Map.merge`: total pages per author with `merge(book.author(), book.pages(), Integer::sum)`. `Integer::sum` is a static method reference, from guide 03.
8. Checked exceptions: write `BookFormatException` and `Books.parse`. `Books.lines().forEach(line -> list.add(Books.parse(line)))` fails to compile. Why: `Consumer.accept` declares no `throws`.
9. Fix 1, catch and wrap: catch `BookFormatException` inside the lambda and throw `new IllegalArgumentException("Bad line: " + line, e)`. Run it and read the chain of `Caused by` lines. Note that *Small Island* was never added, because the exception stopped the loop.
10. Fix 2, your own throwing interface: `LineParser`, and `parseAll(Books.lines(), Books::parse)` with `main` catching `BookFormatException`. Compare it with guide 01's `BookFilter`: same shape, plus a `throws` clause.

**Sidebar:** the generic `ThrowingFunction<T, R, E extends Exception>` that libraries use. No exercise relies on it.

**Concepts:**
- what a lambda can see: its parameters, captured locals, fields, and `this`
- closures in one paragraph
- why checked exceptions and `java.util.function` don't mix

**Your turn:**
1. Extend: `static Predicate<Book> writtenBy(String author)`, in the same shape as `longerThan`, combined with `longerThan(400)`.
2. Break and fix: in step 2's code, add `minPages = 300;` after the lambda, read the error, and fix it with a second variable.
3. Predict, then run: after step 7's `merge`, what's Jane Austen's total? (906.) What's George Orwell's? (440.)

**Common errors:**
- "local variables referenced from a lambda expression must be final or effectively final"
- the one-element `int[]` counter trick: it compiles, but hides a design problem. Use a loop or a `merge` instead
- an empty `catch` inside a lambda
- wrapping without the cause (`new IllegalArgumentException("Bad line")`), which loses the original exception
- `computeIfAbsent(key, new ArrayList<>())`, passing a list where a function is expected
- expecting a `HashMap` to print in a set order (why the guide uses `TreeMap`)

**Interview angle:**
- why captured variables must be effectively final
- lambda versus anonymous class: `this`, scope, and (in one line) no extra `.class` file, because lambdas use `invokedynamic`
- the ways to handle a checked exception in a lambda
- what a closure is

**Verify:**
- Output at tag `guide-04` matches the guide exactly, except the stack trace's line numbers, which the guide says will differ.
- Step 8's code fails to compile with the message from milestone 0.
- The stack trace shows both `Caused by` lines.
- Each solution has been run.

### 5. [ ] README completed and consistency review

**Depends on:** milestones 1–4.

**Delivers:** the finished README and a consistent series.

**Files:**
- **`README.md`:** completed with:
  - all four guides linked, with one-line descriptions
  - **Quick reference** (settled here, open question in the spec): three small tables:
    - lambda syntax forms
    - the core functional interfaces (takes / returns / method)
    - the four kinds of method reference, each beside its equivalent lambda
  - **Deliberately out of scope**: the spec's non-goals table
- **Small fixes** across the four guides.

**Verify:** a review pass, by an agent with fresh eyes, checking:
- every link resolves
- each guide's starting `Book.java` and `Books.java` match the previous tag exactly, checked by script against `lambdas-reference`
- every code snippet that names a file matches the reference project at that guide's tag
- every output block matches the real output (rerun each tag)
- no exercise uses an API that hasn't been taught yet
- every "predict" answer is certain and doesn't depend on ties
- no step uses anything from the out-of-scope list beyond a mention, and nothing uses streams
- no guide links to another series as a prerequisite

Then you follow guide 01 once from an empty `workspace/java-basics/lambdas/` before committing.

## Open questions carried forward

1. **Authors sort by first name. Resolved: keep full names.** `author` holds `"Jane Austen"`, so `comparing(Book::author)` and the `TreeMap` order authors by first name: Donna Tartt first, Kazuo Ishiguro last. The author chose to keep full names. The README's Conventions and one line in guide 03 (step 8) say that sorting compares the whole string, so it starts with the first name.
2. **Guide 04 is long.** It has ten steps, including two checked-exception steps. If it goes past about 1,300 lines, raise it before trimming, rather than cutting a decided step. The likely candidate to move is the `Map` methods (steps 6–7), into guide 03's payoff.
3. **Page counts vary by edition.** The Conventions note covers it. No milestone is blocked.

## Out of scope

From the spec's non-goals; these aren't to be picked up mid-build:
- the Streams API, and `Optional`
- writing generic types or methods (beyond the guide 04 sidebar)
- teaching primitive specialisations beyond the guide 02 sidebar
- `invokedynamic` beyond one Interview angle line
- `var` in lambda parameters
- recursive lambdas, currying, functional programming theory
- serializable lambdas
- lambdas with threads, beyond naming `Runnable` in guide 02
- GUI event listeners
- Vavr or other libraries
- links to other series as prerequisites
- JUnit or any build tool
