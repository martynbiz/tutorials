# Spec: Java threads series

A four-guide series that takes a Java developer who has never written concurrent code from their first `Thread` to virtual threads, built around one order-processing example.

## Context

The old `java/basics/` folder (README, records, interfaces) and the loose `java/threads/` demo have been deleted on purpose. Threads are being started again as their own series, using the same layout and template as the current Spring Boot series (`java/spring-boot/consuming-apis/`) rather than the old single-page basics format.

The topic suits a series better than a single page. It has a natural order of questions: how do I run things at once, why does it break, how do I manage many, and how does modern Java make that cheap. Each guide answers one, building on the code from the guide before.

## Goals

- A reader who knows everyday Java finishes able to start and join threads, explain and fix a race condition, run work on an `ExecutorService` and collect results through `Future`s, and say when virtual threads help and when they don't.
- Every guide ends with code that runs, and every change to the running example is motivated by a problem the reader has just seen.
- The series reads like the rest of the repository's series: same section template, same README shape, same exercise rules.
- Nothing beyond the JDK is needed: no build tool, no IDE, no libraries.

## Non-goals

| Topic | Instead |
| --- | --- |
| Recreating the old records and interfaces guides, or a `java/basics/README.md` | Deferred: separate work |
| `CompletableFuture` | Further reading at most |
| Structured concurrency (still a preview feature in Java 25) | Further reading at most |
| `ReentrantLock` and other `java.util.concurrent.locks` | Further reading at most |
| `wait` / `notify` | Further reading at most |
| `volatile` in depth | Interview angle or Further reading at most |
| Concurrent collections (`ConcurrentHashMap` and friends) | Further reading at most |
| Interruption in depth | Only the catch-and-restore pattern is taught (see Decisions) |
| Thread dumps (`jcmd <pid> Thread.print`) | Further reading at most |
| Deadlock as a step | Common errors (guide 02) and Interview angle only |
| Teaching lambdas or records from scratch | Assumed knowledge |

## Decisions

### Location and shape

- **The series is a folder: `java/basics/threads/`**, holding `README.md`, `01-your-first-threads.md`, `02-sharing-data-safely.md`, `03-executors-and-futures.md`, `04-virtual-threads.md`, and this spec (plus `PLAN.md` later). It matches the repository's series layout and CLAUDE.md.
- **There's no `java/basics/README.md`.** The `java/spring-boot/` series have no parent README either. JDK 25 setup goes in the series README.
- **Four guides:**

  | Guide | Covers |
  | --- | --- |
  | `01-your-first-threads.md` | What a thread is; the `main` thread (`Thread.currentThread().getName()`); creating a thread from a `Runnable` lambda; `start()` versus `run()`; `join()`; interleaving made visible with `Thread.sleep` |
  | `02-sharing-data-safely.md` | A race condition on a shared counter; fixing it with `synchronized` (methods **and** `synchronized (lock)` blocks); fixing it with `AtomicInteger` instead |
  | `03-executors-and-futures.md` | `ExecutorService`; a fixed pool; `submit`; try-with-resources instead of a manual `shutdown()`; `Callable` and `Future.get()`; unwrapping `ExecutionException` with `getCause()` |
  | `04-virtual-threads.md` | `Executors.newVirtualThreadPerTaskExecutor()`; the timing comparison against a fixed pool; why virtual threads help with waiting but not with computing |

  The split follows the order in which a learner runs into each problem.

### Template

- **The guides use the `consuming-apis` template:** What you'll learn → Prerequisites for this guide → Concepts → Steps → Your turn → Checkpoint → Common errors → Recap (with Interview angle and Further reading). Sidebars are marked `> **Sidebar:** …`. This keeps the guides consistent with the repository's current series.
- **The README uses the `consuming-apis` README shape:** What you'll build → Versions → What you need to know already → Where things live → Quick reference → The guides → How each guide works → Conventions → Deliberately out of scope.
- **The Checkpoint is "run it and check these things hold"**, not JUnit tests, because the series is JDK only.

### Audience and setup

- **The reader** is comfortable with everyday Java: classes, interfaces, collections, exceptions, **records** and **lambda syntax** (`() -> ...`). They have never written concurrent code. The guides remind them that a lambda can stand in for `Runnable`, but don't teach lambdas. The README's "What you need to know already" lists records and lambdas.
- **The series targets Java 25, using only the JDK.** Code is run with `java Main.java`. Since Java 22 the source launcher finds the other `.java` files in the same folder, so neither `javac` nor a build tool is needed.
- **The guides are terminal-first.** Readers can use any editor. The README gives IntelliJ a single line ("the green arrow next to `main` works too"). The run command needs no configuration, and timings are clearer outside an IDE.
- **The reader's code lives in `workspace/java-basics/threads/`.** It's one folder that grows through the series, split into several small files (`Main.java`, `Order.java`, `OrderProcessor.java`, `OrderCounter.java`, …) with **no packages**. The `java-basics` prefix leaves room for future basics series beside it.

### Running example

- **One order processor carries through all four guides.** Each guide starts from the previous guide's finished code and **shows that starting code in full**, so a reader who skips ahead can still begin.
  - 01: worker threads "process" orders, using `sleep` to stand in for slow work, and their output interleaves.
  - 02: workers share a processed count. Updates go missing, then get fixed with `synchronized`, then with `AtomicInteger`.
  - 03: workers move onto a pool. Each task is a `Callable` returning the order's total, and `main` adds up the `Future`s.
  - 04: the example scales to 10,000 orders on virtual threads.
- **This is the `Order` record:**

  ```java
  record Order(int id, String product, int quantity, long unitPricePence) {
      long totalPence() { return quantity * unitPricePence; }
  }
  ```

  Money is in **whole pence (`long`)**. That avoids both `double` rounding and a `BigDecimal` detour, and a one-line note in the guide says why.
- **Orders are generated in a loop**, for example `new Order(i, "Widget", 1 + i % 5, 250)`. There's no file or random data, so totals are predictable and can be checked.
- **A negative quantity is rejected by `OrderProcessor` when the order is processed**, not in the record's constructor. Otherwise the bad order couldn't be created, and the worker thread would never be the one to throw, which guide 03's `ExecutionException` step needs.

### Exceptions

- **`main` declares `throws InterruptedException`.** That's honest and short.
- **Inside a worker**, the guides use `catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }`, explained in one sentence: someone asked this thread to stop, so put the flag back and stop. Guide 01 teaches this where `Thread.sleep` and `join()` first appear, because a `Runnable` lambda can't throw checked exceptions.
- **The guides never use an empty `catch`.** Swallowing the interrupt is a Common errors entry.
- **Guide 03 teaches `ExecutionException` as a step:** an order with a negative quantity throws on the worker, and `main` unwraps the exception with `getCause()`. Exercises can then use it.
- **The guides never hide `try`/`catch` behind a `sleep(ms)` helper.** A helper would teach readers that the exception doesn't matter.

### Guide 02: making the race show

- **4 workers × 100,000 increments each, against an expected 400,000.** On a multi-core machine that almost always loses thousands of updates. Small numbers often hide the race.
- **The increment is a plain `count++`** on a field of a small `OrderCounter` class. The step explains it as "read, add one, write back" happening in three moves.
- **The reader runs `java Main.java` 3 times**, one race per launch, and sees *different* wrong totals. That shows the bug is nondeterministic.
  - It's not a loop in `main`. Milestone 0 measured a loop in one launch and found that only the first two runs lose updates. From the third run the JIT has optimised the worker loop, and the total comes out 400,000.
  - One race per fresh launch lost updates in 10 launches out of 10.
- **The workers don't sleep in this step**, because sleeping spreads the threads apart and hides the race. The guide says so.
- **A sidebar covers the reader who gets 400,000:** run it again or raise the count. A correct result once doesn't mean correct code. The sidebar also says that if you loop the race inside one program, it soon starts coming out "right": the JIT can change how racy code behaves, which is part of why races are so hard to catch.
- **The `synchronized` step teaches both methods and `synchronized (lock)` blocks**, so the deadlock example in Common errors (two locks taken in opposite orders) only uses syntax the reader has seen.

### Guide 04: the timing comparison

- **There are 10,000 orders, each waiting 100 ms** (`Thread.sleep(Duration.ofMillis(100))`, standing in for a database or HTTP call).
  - **Run A uses a fixed pool of 100 platform threads:** about 10,000 ÷ 100 × 0.1 s ≈ **10 s**.
  - **Run B uses `newVirtualThreadPerTaskExecutor()`:** typically **well under a second**.
- **Both runs are timed** (with `System.nanoTime()` or `Instant`/`Duration`), and the processed count is checked with guide 02's `AtomicInteger`. The reader sees the code is faster *and* still correct.
- **A CPU-bound version follows:** 200 orders, each doing about 50 ms of arithmetic instead of sleeping. Both runs take about the same time, which shows that virtual threads help with waiting but not with computing.
- **The guide never runs 10,000 platform threads, one per task.** On some machines that fails with `OutOfMemoryError: unable to create native thread`, so readers wouldn't all see the same result. It's a Common errors entry only.

### Output and exercises

- **Wherever the scheduler decides the output, the guide shows one possible run.** Any expected-output block where order or a racy count can vary is labelled as one possible run ("yours will likely differ in order").
- **Three exercises per guide, always in this pattern:**
  1. **Extend:** apply a step's technique to something new. Examples: 01, a third worker with its own name. 03, a second pool that totals only the large orders.
  2. **Break and fix:** introduce a mistake the guide warned about, see it fail, and fix it. Examples: 02, remove `synchronized` from one of two methods. 03, forget `get()` and print the `Future`.
  3. **Predict, then run:** only things that are certain. For example: "with `synchronized`, is the total always 400,000?" or "without it, can it ever be *more* than 400,000?"
- **Solutions go in collapsible `<details>` blocks.**
- **Exercises only practise what the steps taught.** Every API in a solution must appear in an earlier step of the same guide or an earlier guide. Anything new is taught as a step instead.

## Assumptions

- Readers know records and lambda syntax. The series won't teach either.
- Readers have JDK 25 installed, or can follow the README's setup to install it.
- Readers have multi-core machines, which guide 02's race demo relies on.
- `Thread.sleep(Duration)`, the multi-file source launcher and virtual threads are all final in Java 25. None needs `--enable-preview`.

## Open questions / Risks

- **Timings vary by machine.** Guide 04 must give approximate figures ("about 10 s", "well under a second"), not promises.
- **The race may not show** on a single-core or heavily throttled machine. The sidebar covers this, but the lesson is weaker for those readers.
- **Running 10,000 platform threads** can exhaust native threads. It's described in Common errors and never part of the main demo.
- **One growing folder carries mistakes forward.** A reader who breaks guide 02's code takes that into 03. Each guide showing its full starting code is the safety net.
- **Not yet decided:**
  - The exact step list within each guide.
  - The full set of classes beyond `Main`, `Order`, `OrderProcessor` and `OrderCounter`.
  - Guide 01's exact interleaving demo.
  - The content of each guide's Interview angle.
  - The README's Quick reference.

  These belong in `PLAN.md`.

## Verification

- **Every code listing compiles and runs on JDK 25** with `java Main.java` from `workspace/java-basics/threads/`. Each guide's full starting code matches the previous guide's Checkpoint.
- **Guide 02's race** loses updates on a typical multi-core machine with the agreed numbers. The `synchronized` and `AtomicInteger` versions always print 400,000.
- **Guide 04's timings** come out in the stated ballpark: about 10 s against under 1 s for the waiting workload, and roughly equal for the CPU-bound one.
- **Each exercise solution** uses only APIs taught in an earlier step.
- **Each "predict, then run" answer** is certain, not dependent on scheduling.
- **Each Checkpoint** lists properties the reader can check, not exact output where the scheduler decides it.
