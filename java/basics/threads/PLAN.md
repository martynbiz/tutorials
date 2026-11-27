# Java threads: implementation plan

How the series specified in [SPEC.md](SPEC.md) gets built: a working reference project, built guide by guide, and the README and four guides written from it.

## Context

The decisions are fixed in [SPEC.md](SPEC.md), so this plan doesn't repeat or revisit them. In short:

- **The series:** a README and four guides in this folder, built around one order processor.
- **The code:** Java 25, JDK only, run with `java Main.java`.
- **The template:** the same as `consuming-apis`.
- **Output:** wherever the scheduler decides it, the guides show one possible run.
- **Exercises:** only practise what the steps taught.

### Stack

| Choice | Value | Source |
|---|---|---|
| Language | Java 25. The JDK installed here is Temurin 25.0.2 | Spec, and checked on this machine |
| Libraries | None: the JDK only (`java.lang.Thread`, `java.util.concurrent`) | Spec |
| Build | None. Multi-file source launcher: `java Main.java` | Spec |
| Tests | None. Verification is by running the code and checking the properties each Checkpoint lists | Spec |
| Workflow | No TDD. Each milestone is built, run and checked by hand | Follows from "no tests" |
| Docker, deployment | Not applicable | |
| README | Yes: the series README in this folder, started in milestone 1 and finished in milestone 5 | Spec, and the repo's [CLAUDE.md](../../../CLAUDE.md) |

### Where things live

- **Guides, README, spec and plan:** this folder, `java/basics/threads/`. Only these are committed.
- **Reference project:** `workspace/java-basics/threads-reference/`.
  - It's a local git repo with one commit per guide, tagged `guide-01` to `guide-04`.
  - `workspace/` is git-ignored, so it's never committed to the tutorials repo.
  - It isn't in the session scratchpad, because the build may span several sessions and the scratchpad can be wiped.
  - It isn't in `workspace/java-basics/threads/` either, because that's the path the guides give readers, and you may want to follow the series yourself.
- **Build notes:** `workspace/java-basics/threads-build-notes.md`. Milestone 0's measurements go here: versions, race totals and timings on this machine.
- **Reader's files:** these grow guide by guide and have no packages.
  - `Main.java`
  - `Order.java`
  - `OrderProcessor.java`
  - `OrderCounter.java` (from guide 02)
- **Guide files:**
  - `01-your-first-threads.md`
  - `02-sharing-data-safely.md`
  - `03-executors-and-futures.md`
  - `04-virtual-threads.md`

### Guide format

As the spec sets out:

- What you'll learn → Prerequisites for this guide → Concepts → Steps → Your turn (3 exercises: extend, break and fix, predict then run) → Checkpoint → Common errors → Recap, with Interview angle and Further reading
- Sidebars as `> **Sidebar:** …`
- Each guide after 01 opens with the previous guide's finished code in full
- Each guide ends with a link to the next

The old basics README's JDK install section (from `git show e761afc:java/basics/README.md`) is reused as the starting point for the new README's setup. It's adapted to the `consuming-apis` README shape, not copied as a whole.

## Milestones

### 0. [x] Check the facts the guides rely on

Settles the measurements and behaviours that every later milestone quotes. The results go in `threads-build-notes.md`.

**Launcher and APIs.** Using throwaway files in `workspace/java-basics/threads-reference/scratch/`, confirm on JDK 25 that:

- `java Main.java` finds `Order.java` and the other files in the same folder, with no packages, and prints nothing extra
- the error a reader sees when a file is missing or misnamed, for Common errors
- `Thread.sleep(Duration)` and `ExecutorService` as `AutoCloseable` both exist without `--enable-preview`, and `close()` waits for submitted tasks
- the default name formats:
  - a `Thread` started without a name (`Thread-0`)
  - the `main` thread
  - pool threads (`pool-1-thread-1`)
  - what `Thread.currentThread()` prints on a virtual thread (`VirtualThread[#…]/runnable@ForkJoinPool-1-worker-…`)

  The guides quote these.

**Guide 02's race.** Run 4 × 100,000 `count++`, repeated 3 times, and record the totals. Then do the same with `synchronized` and with `AtomicInteger`.

**Guide 04's timings.** Record each of these:

- 10,000 orders × 100 ms on a fixed pool of 100
- the same orders on `newVirtualThreadPerTaskExecutor()`
- a CPU-bound loop calibrated to about 50 ms per order on this machine, then 200 such orders on each executor

For the CPU-bound loop, pick arithmetic the JIT can't remove: for example, sum a running value and use the result in the order's total.

**The 10,000-platform-threads claim.** Try 10,000 platform threads, one per task, and record whether it fails here. The Common errors entry is worded to match what happens ("on some machines…"). Don't claim a failure that isn't seen anywhere. See open question 2.

**Facts behind the out-of-scope notes.** Confirm that:

- structured concurrency is still a preview feature in Java 25
- `synchronized` no longer pins virtual threads in Java 24 and later (JEP 491)

These are only for sidebars and Further reading, so nothing in them is stated wrongly.

**Verify:**
- `threads-build-notes.md` has every figure above.
- The unsynchronised race totals are below 400,000 on 3 separate launches, one race per launch. This was changed from "3 runs in a loop" after the loop was found to hide the race.
- The waiting workload shows a clear gap. If it doesn't, stop and raise it, because the spec's numbers would need revisiting.

### 1. [x] Guide 01: Your first threads, and the README skeleton

**Depends on:** milestone 0.

**Delivers:** two named worker threads, each processing its own orders with interleaved output. `main` waits for both and then prints "All orders processed".

**Reference project files (tag `guide-01`):**
- **`Order.java`:** the record from the spec, with `totalPence()`.
- **`OrderProcessor.java`:** `process(Order)` prints `[thread name] processing order N`, then sleeps 300 ms using `Thread.sleep(Duration)`.
  - If interrupted, it catches the exception, calls `Thread.currentThread().interrupt()` and returns.
  - Settled here (open question in the spec): the interleaving demo uses two workers, `worker-1` and `worker-2`, with three orders each and a 300 ms sleep.
- **`Main.java`:** `main` declares `throws InterruptedException`. It builds the orders in a loop, starts the two named `Thread`s from `Runnable` lambdas, `join()`s both, then prints the closing line.

**Guide steps:**
1. Set up the folder. A one-line `Main.java` runs with `java Main.java`.
2. The `main` thread. Print `Thread.currentThread().getName()`.
3. The `Order` record, in its own file. This shows the launcher picking it up.
4. Process orders one after another on `main`. Time three orders to see the waiting add up. This introduces `sleep` and `InterruptedException`.
5. The first thread: `new Thread(() -> …)` and `start()`. Without `join()`, `main` finishes first.
6. `join()`, and `main` declaring `throws InterruptedException`.
7. `start()` versus `run()`. Calling `run()` executes on `main`, which shows in the printed name.
8. Two named workers. The output interleaves, so run it a few times.

**Concepts:**
- a process and its threads
- the scheduler
- concurrency versus parallelism
- why threads help with waiting
- `Runnable` as a functional interface, a reminder only

**Your turn:**
1. Extend: a third worker with its own name.
2. Break and fix: remove a `join()` and see the closing line jump ahead.
3. Predict, then run: is "All orders processed" always last? (Yes.) Is `worker-1` always first? (No.)

**Common errors:**
- unreported `InterruptedException` in a lambda
- calling `run()` instead of `start()`
- calling `start()` twice (`IllegalThreadStateException`)
- an empty `catch`

**Interview angle:**
- `start` versus `run`
- what `join` does
- thread versus process
- why an interrupt is restored

**README.md (skeleton):**
- What you'll build
- Versions (Java 25, Temurin, from milestone 0)
- What you need to know already: everyday Java, records, lambdas
- JDK setup, adapted from the old basics README
- Where things live: a tree of `workspace/java-basics/threads/`
- How to run, terminal first, plus one IntelliJ line
- The guides: only 01 linked so far
- How each guide works
- Conventions:
  - money in pence
  - "one possible run" output
  - PowerShell notes where commands differ

**Verify:**
- `java Main.java` at tag `guide-01` produces output of the shape shown, in several runs with differing order.
- Removing `join()` changes the output as the guide says it will.
- Each Your turn solution has been run.
- Every expected-output block that can vary is labelled as one possible run.

### 2. [x] Guide 02: Sharing data safely

**Depends on:** milestone 1.

**Delivers:** `OrderCounter`, first broken and then made correct, with workers counting processed orders correctly.

**Reference project files (tag `guide-02`):**
- **`OrderCounter.java`:**
  - its final form has an `AtomicInteger` field, `increment()` and `get()`
  - the guide passes through two earlier forms that aren't in the tag: a plain `int` with `count++`, and a `synchronized` version
- **`Main.java`:**
  - the race demo: 4 workers × 100,000 `increment()` calls with no sleep, run **once per launch**, with the expected count printed beside the actual one. The reader runs `java Main.java` 3 times. A loop inside `main` hides the race from its third run, as milestone 0 found.
  - then the workers process orders as in guide 01 and count each one

**Guide steps:**
1. Start from guide 01, with its full code.
2. A counter used from one thread. It's correct.
3. The race: 4 workers × 100,000. Run `java Main.java` 3 times and get different wrong totals. Say why there's no sleep here.
4. Why it happens: `count++` as read, add one, write back, with a timeline table of two threads losing an update.
5. `synchronized` methods. It's always 400,000. Mention the cost.
6. `synchronized (lock)` blocks with a `private final Object lock`. Same result. Why a private lock, and why `get()` needs locking too.
7. `AtomicInteger` instead: `incrementAndGet()` and `get()`.
8. Back to orders: the workers process orders and count them, and the count matches.

**Sidebar:** if you got 400,000 without locking. It also explains why looping the race inside one program soon makes it come out "right" (the JIT), and why that doesn't make the code correct.

**Your turn:**
1. Extend: a synchronized running `totalPence` alongside the count, using only `synchronized` and `long`, as taught. No new APIs such as `AtomicLong`.
2. Break and fix: remove `synchronized` from one of two methods.
3. Predict, then run: with locking, is it always 400,000? Without it, can it ever be more?

**Common errors:**
- locking on a different object in each thread
- `get()` left unsynchronised
- `counter.set(counter.get() + 1)` on an `AtomicInteger`
- **deadlock**: two `synchronized (lock)` blocks taken in opposite orders, with a minimal listing using only step 6's syntax

**Interview angle:**
- race condition versus data race
- what `synchronized` guarantees (mutual exclusion and visibility)
- atomic classes versus locks
- deadlock and the four conditions
- `volatile` in one paragraph, pointing to Further reading

**Verify:**
- Across several launches of `java Main.java`, the unsynchronised version gives totals below 400,000 that differ from launch to launch.
- Both fixed versions always print 400,000.
- The deadlock listing really hangs, stopped with Ctrl+C, and the guide says how to stop it.
- Each solution has been run.

**Built (2026-11-25).** Tag `guide-02`; the run results are in `threads-build-notes.md`. Settled or changed while building:
- **`Main.java`'s shape** (the plan didn't fix it): guide 01's `main` moves, word for word, into `processOrders()` in step 2. The race is a `countingRace()` method with `WORKERS` and `INCREMENTS_PER_WORKER` constants, and the final `main` calls `countingRace()` then `processOrders()`. Both print `Expected …, counted …`.
- **Step 2's single-thread loop must go before step 3.** Left in front of the race, it warmed the code up and 1 launch in 5 printed 400,000. Step 3 says to remove it, and the sidebar says why.
- **Exercise 1** puts `totalPence` in `OrderCounter` beside the `AtomicInteger` count, with `synchronized` `addPence` and `totalPence()`. The solution also shows a check under load in `countingRace()`.
- **Exercise 2** first restores step 5's `synchronized` counter, because the tag's counter is the `AtomicInteger` one.
- **Exercise 3** has four questions, not two. The added ones ("can it be exactly 400,000?" and "does counting 6 of 6 prove it?") have certain answers. Step 8 says the plain `int` counter would also count 6 of 6, which was checked in 5 launches.
- **The deadlock listing** is a separate `Deadlock.java` the reader runs and then deletes, so nothing extra is carried into guide 03.
- **Length:** about 860 lines, under open question 3's limit.

### 3. [x] Guide 03: Executors and futures

**Depends on:** milestone 2.

**Delivers:** a fixed pool that processes a batch of orders, returns each total through a `Future`, adds them up, and reports a bad order through `ExecutionException`.

**Reference project files (tag `guide-03`):**
- **`OrderProcessor.java`:** `process(Order)` now returns `long` (the order's total). It throws `IllegalArgumentException` for a negative quantity, as the spec decides.
- **`Main.java`:**
  - `try (var pool = Executors.newFixedThreadPool(4))`
  - submits a `Callable` per order and keeps a `List<Future<Long>>`
  - sums them with `get()`
  - counts with guide 02's `OrderCounter`
  - one deliberately bad order whose `ExecutionException` is caught and its cause printed

**Guide steps:**
1. Start from guide 02, with its full code.
2. Why not one `Thread` per order: creation cost, no limit, and no way to get a result back.
3. A fixed pool, submitting `Runnable`s. Pool thread names show threads being reused.
4. try-with-resources: `close()` waits for the tasks. Briefly show what `shutdown()` plus `awaitTermination()` used to look like, for recognition only.
5. `Callable`: `process` returns the total, and `submit(() -> processor.process(order))` gives a `Future<Long>`.
6. `Future.get()`: submit everything first, then collect. `get()` blocks, and the total is the same every run.
7. A bad order: a negative quantity throws on a pool thread, `get()` throws `ExecutionException`, and `getCause()` gives the original exception.

**Your turn:**
1. Extend: a second pool that totals only the large orders (quantity 4 or more).
2. Break and fix: forget `get()` and print the `Future`.
3. Predict, then run: does the grand total depend on which task finishes first? (No.) Does the order of the printed lines? (Yes.)

**Common errors:**
- calling `get()` inside the submit loop, which runs everything one at a time
- a pool that's never closed, so the program doesn't exit
- catching `ExecutionException` and printing only its message
- `Future` printed as `FutureTask@…`

**Interview angle:**
- `Runnable` versus `Callable`
- what `Future.get()` does and how it fails
- how to pick a pool size for waiting work versus computing work
- `submit` versus `execute`, in one line

**Verify:**
- The grand total equals the sum worked out by hand from the order loop, checked in the build notes.
- The bad order prints its `IllegalArgumentException` message through `getCause()`.
- The program exits without hanging.
- Each solution has been run.

**Built (2026-11-27).** Tag `guide-03`; the run results are in `threads-build-notes.md`. Settled or changed while building:
- **`process` declares `throws InterruptedException` from step 5** and drops its `try`/`catch`. The author decided this on 2026-11-25. It departs from the spec's catch-and-restore rule, which still applies to `Runnable`s. A method that returns a total would otherwise have to return a made-up 0, and a `Callable` may throw checked exceptions. Step 5 shows the compile error that motivates the switch from `Runnable` to `Callable`.
- **`ExecutorService pool`, not `var pool`**, because the series writes types out everywhere else.
- **`countingRace()` stays in `Main.java`, unchanged**, as guide 02 promised.
- **`processOrders()`** uses one list of 12 orders and adds order 13 with quantity -1 in step 7. It prints `Orders: 13 submitted, 12 processed, 1 failed` and `Total: 8750p`, and drops guide 02's `Running on: main` line.
- **Step 3 leaves the pool unclosed on purpose.** The reader sees the early, wrong closing lines and a program that doesn't end, and stops it with Ctrl+C. Step 4 fixes it. So the "pool never closed" Common error is also seen as a step.
- **Step 6 declares `ExecutionException` on `processOrders()` and `main`, and step 7 removes it again** once it's caught.
- **An extra Common error:** "unreported `InterruptedException`" inside `pool.submit` (a `Runnable` that should be a `Callable`).
- **Exercise 3** has four questions, not two. The added ones have certain answers.
- **Exercise 2** also shows that a failure goes unseen when its `Future` is never read (`0 failed`).
- **Length:** about 970 lines, under open question 3's limit.

### 4. [ ] Guide 04: Virtual threads

**Depends on:** milestone 3.

**Delivers:** the timing comparison from the spec and the CPU-bound counter-example, both with the count checked.

**Reference project files (tag `guide-04`):**
- **`OrderProcessor.java`:** a second method for the CPU-bound case, with the arithmetic loop from milestone 0. The waiting version uses a 100 ms sleep in this guide.
- **`Main.java`:** a small `run(String label, ExecutorService executor, …)` helper that:
  - submits the orders, waits through try-with-resources
  - times the run with `System.nanoTime()`
  - prints the time and the `OrderCounter` count beside the expected count

  It's called for Run A (fixed pool of 100) and Run B (virtual threads), on the waiting workload and then the computing one.

**Guide steps:**
1. Start from guide 03, with its full code.
2. What a platform thread costs. Run A: 10,000 × 100 ms on a pool of 100, taking about 10 s.
3. Virtual threads. Run B with `newVirtualThreadPerTaskExecutor()`, taking well under a second, with the same count.
4. What a virtual thread is: print `Thread.currentThread()` from inside a task, and explain carrier threads simply.
5. Computing, not waiting: 200 × about 50 ms of arithmetic, with Run A and Run B taking about the same time, and why.
6. Choosing between them. A short table: waiting, computing, or a mix.

**Sidebars:**
- why the figures on your machine will differ
- `synchronized` and pinning, now fixed since Java 24

**Your turn:**
1. Extend: run 100,000 orders on virtual threads.
2. Break and fix: wrap the virtual-thread run in a fixed pool of 10, see it slow right down, and fix it.
3. Predict, then run: is Run B's count always 10,000? (Yes.) Does doubling Run A's pool to 200 roughly halve its time? (Yes. Only the count part is certain, and the exercise wording makes that clear.)

**Common errors:**
- 10,000 platform threads, worded as milestone 0 found
- pooling virtual threads
- expecting virtual threads to speed up computing

**Interview angle:**
- platform versus virtual threads
- why virtual threads are cheap
- when not to use them
- a pointer to structured concurrency, which is still in preview

**Verify:**
- The timings come out in the spec's ballpark on this machine, and the guide quotes them as approximate.
- The counts are always exact.
- The CPU-bound runs come out about equal.
- Each solution has been run.

### 5. [ ] README completed and consistency review

**Depends on:** milestones 1–4.

**Delivers:** the finished README and a consistent series.

**Files:**
- **`README.md`:** completed with:
  - all four guides linked, with one-line descriptions
  - **Quick reference** (settled here, open question in the spec): a table of the APIs each guide introduces, and the `Order`, `OrderProcessor` and `OrderCounter` shapes at the end of the series
  - **Deliberately out of scope**: the spec's non-goals table
- **Small fixes** across the four guides.

**Verify:** a review pass, by an agent with fresh eyes, checking:
- every link resolves
- each guide's "start from" listing matches the previous tag exactly, checked by script against `threads-reference`
- every code snippet that names a file matches the reference project at that guide's tag
- no exercise uses an API that hasn't been taught yet
- every output that can vary is labelled, and every "predict" answer is certain
- no step uses anything from the out-of-scope list beyond a mention

Then you follow guide 01 once from an empty `workspace/java-basics/threads/` before committing.

## Open questions carried forward

1. **The CPU-bound comparison is noisy (affects milestone 4).** Milestone 0 found that one task's time swung between 30 ms and 540 ms on this machine. Across six launches the virtual and platform runs stayed within about 1–2× of each other, but which one won varied. The JIT didn't fold the loop away.
   - The guide's claim holds, but in a weaker form: computing shows nothing like the 30× gain that waiting does, and the figures and the winner vary between launches. "Roughly similar", not "the same".
   - Still to decide in milestone 4: whether Run A for computing stays a pool of 100, as the spec says, or becomes a pool sized to the cores, which is the fairer comparison but didn't reliably win either. Raise it with the author before writing that step.
2. **The 10,000-platform-thread failure. Resolved by milestone 0.** It doesn't fail on this Windows machine: 10,000 and even 50,000 platform threads started without an `OutOfMemoryError`. What does show is the cost: starting 10,000 took 4.5 s, against 0.3 s for the whole virtual-thread run. So milestone 4's Common errors entry is worded around that start-up cost and claims no failure. It can say that systems with lower thread limits, such as some Linux and container set-ups, may refuse, but only as a possibility.
3. **Guide length.** The old basics guides ran to about 1,000–1,200 lines. Guide 02 has the most steps and may run longer. If it passes about 1,300 lines, raise it before trimming, rather than cutting a decided step.

## Out of scope

From the spec's non-goals; these aren't to be picked up mid-build:
- recreating the records or interfaces guides, or a `java/basics/README.md`
- `CompletableFuture`
- structured concurrency
- `ReentrantLock` and the other locks
- `wait`/`notify`
- `volatile` in depth
- concurrent collections
- interruption in depth
- thread dumps
- deadlock as a step
- teaching lambdas or records
- JUnit or any build tool
