# 02: Sharing data safely

> Written for **Java 25**. Uses only the JDK: no build tool and no IDE. See the [series README](README.md) for setup.

## What you'll learn

- What a **race condition** is, and how to make one happen on purpose with a shared counter
- Why **`count++`** isn't one step but three, and how two threads lose an update between them
- How **`synchronized` methods** and **`synchronized (lock)` blocks** let only one thread in at a time, and why the lock should be a **private** object
- Why reading a shared value needs the lock too, not just changing it
- How **`AtomicInteger`** fixes the same counter without a lock
- What a **deadlock** is, and the one rule that avoids the common kind

By the end, four threads will add 100,000 each to a shared counter and get 400,000 every time, and the two workers from guide 01 will count the orders they process.

---

## Prerequisites for this guide

- [Guide 01](01-your-first-threads.md): starting threads with `new Thread(...)` and `start()`, waiting for them with `join()`, and the catch-and-restore pattern for `InterruptedException`.
- The code from guide 01's Checkpoint, in `workspace/java-basics/threads/`. Step 1 shows it in full, so you can start here.
- **A machine with more than one core.** Almost every laptop and desktop has several. On a single core the race in step 3 rarely shows (see the sidebar after step 3).

---

## Concepts

### Shared, changing data

In guide 01 the two workers never touched the same data. Each had its own batch of orders, and the one object they shared, the `OrderProcessor`, keeps nothing between calls. That's why nothing went wrong.

All of a process's threads share its memory, so two threads *can* use the same object. Reading the same object from several threads is safe as long as nobody changes it. Trouble starts when the data is **shared** *and* **changing**: one thread writes to a field while another reads or writes it too.

### Race conditions

A **race condition** is a bug where the result depends on the timing between threads: which one gets there first, and when the scheduler pauses each. Most runs might be fine, and some aren't, and you can't choose which.

Races are among the hardest bugs to find:

- **They don't happen every time.** A test can pass a hundred times and fail on the hundred-and-first.
- **They don't crash.** A lost update just leaves a wrong number behind, with no exception and no stack trace.
- **Watching makes them go away.** Adding a `println` or a debugger breakpoint changes the timing, and the race hides.

So you can't test your way to thread-safe code. You have to be able to look at code and say why it's safe. This guide shows you how.

### Atomic

An action is **atomic** if no other thread can see it half-done or step into the middle of it: it has either happened completely, or not at all. The word comes from the Greek for "can't be cut".

Most lines of Java aren't atomic, however short they look. Step 4 takes one apart.

### Locks

Every Java object has a **lock** built in (it's also called a **monitor**). A thread can **take** an object's lock and later **release** it. While one thread holds it, any other thread that tries to take the same lock has to wait until it's released.

That's how you make several lines act as one atomic step: take a lock, do the work, release it. If every thread that touches the data takes the *same* lock first, only one of them can be working on it at a time. The `synchronized` keyword is how you ask for this, from step 5.

---

## Steps

### 1. Start from guide 01

This guide starts where [guide 01](01-your-first-threads.md) ended. If you're carrying on, your `workspace/java-basics/threads/` folder should already have these three files. If you're starting here, create the folder (guide 01, step 1) and these files in it.

`Order.java`:

```java
public record Order(int id, String product, int quantity, long unitPricePence) {

    public long totalPence() {
        return quantity * unitPricePence;
    }
}
```

`OrderProcessor.java`:

```java
import java.time.Duration;

public class OrderProcessor {

    public void process(Order order) {
        String thread = Thread.currentThread().getName();
        System.out.println("[" + thread + "] started order " + order.id());

        try {
            Thread.sleep(Duration.ofMillis(300));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        System.out.println("[" + thread + "] finished order " + order.id() + ": " + order.totalPence() + "p");
    }
}
```

`Main.java`:

```java
import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("Running on: " + Thread.currentThread().getName());

        OrderProcessor processor = new OrderProcessor();
        List<Order> firstBatch = orders(1, 3);
        List<Order> secondBatch = orders(4, 3);
        long start = System.nanoTime();

        Thread worker1 = new Thread(() -> {
            for (Order order : firstBatch) {
                processor.process(order);
            }
        }, "worker-1");

        Thread worker2 = new Thread(() -> {
            for (Order order : secondBatch) {
                processor.process(order);
            }
        }, "worker-2");

        worker1.start();
        worker2.start();
        worker1.join();
        worker2.join();

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println("All orders processed in " + elapsedMs + " ms");
    }

    static List<Order> orders(int firstId, int count) {
        List<Order> orders = new ArrayList<>();
        for (int id = firstId; id < firstId + count; id++) {
            orders.add(new Order(id, "Widget", 1 + id % 5, 250));
        }
        return orders;
    }
}
```

Run `java Main.java` once to check. Two workers process six orders in a little over 900 ms, with their lines interleaved.

### 2. A counter, on one thread

The business wants to know how many orders were processed. That needs a counter. Create `OrderCounter.java`:

```java
public class OrderCounter {

    private int count;

    public void increment() {
        count++;
    }

    public int get() {
        return count;
    }
}
```

It's as plain as a class gets: an `int` field that starts at 0, a method to add one, and a method to read it.

Before the workers use it, try it on one thread, and make it work hard. You'll come back to the order processing in step 8, so move it out of `main` into a method of its own, `processOrders()`, and give `main` a counting loop instead. Replace `Main.java`:

```java
import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        OrderCounter counter = new OrderCounter();
        for (int i = 0; i < 400_000; i++) {
            counter.increment();
        }
        System.out.println("Expected 400000, counted " + counter.get());
    }

    static void processOrders() throws InterruptedException {
        System.out.println("Running on: " + Thread.currentThread().getName());

        OrderProcessor processor = new OrderProcessor();
        List<Order> firstBatch = orders(1, 3);
        List<Order> secondBatch = orders(4, 3);
        long start = System.nanoTime();

        Thread worker1 = new Thread(() -> {
            for (Order order : firstBatch) {
                processor.process(order);
            }
        }, "worker-1");

        Thread worker2 = new Thread(() -> {
            for (Order order : secondBatch) {
                processor.process(order);
            }
        }, "worker-2");

        worker1.start();
        worker2.start();
        worker1.join();
        worker2.join();

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println("All orders processed in " + elapsedMs + " ms");
    }

    static List<Order> orders(int firstId, int count) {
        List<Order> orders = new ArrayList<>();
        for (int id = firstId; id < firstId + count; id++) {
            orders.add(new Order(id, "Widget", 1 + id % 5, 250));
        }
        return orders;
    }
}
```

`processOrders()` is guide 01's `main`, word for word, under a new name. Nothing calls it for now, so it doesn't run. That's fine: step 8 calls it again.

```
Expected 400000, counted 400000
```

Run it as often as you like. One thread, 400,000 increments, 400,000 counted. The counter works.

### 3. The race: four threads, one counter

Now share the counter between four threads, each adding 100,000. That's still 400,000 increments in all. In `Main.java`, add two constants just inside `public class Main {`, replace `main`, and add a `countingRace()` method under it. Leave `processOrders()` and `orders(...)` as they are.

```java
    static final int WORKERS = 4;
    static final int INCREMENTS_PER_WORKER = 100_000;

    public static void main(String[] args) throws InterruptedException {
        countingRace();
    }

    static void countingRace() throws InterruptedException {
        OrderCounter counter = new OrderCounter();
        List<Thread> workers = new ArrayList<>();

        for (int w = 0; w < WORKERS; w++) {
            workers.add(new Thread(() -> {
                for (int i = 0; i < INCREMENTS_PER_WORKER; i++) {
                    counter.increment();
                }
            }));
        }

        for (Thread worker : workers) {
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        System.out.println("Expected " + (WORKERS * INCREMENTS_PER_WORKER) + ", counted " + counter.get());
    }
```

Everything here is from guide 01, with a list because there are four threads now:

- **The first loop creates four threads**, all with the same job: call `increment()` 100,000 times on the *one* shared `counter`. Every lambda uses the same `counter` variable, so every thread changes the same object.
- **The second loop starts them all, and the third joins them all**: start every thread before joining any, as in guide 01's step 8.
- **The last line runs after all four joins**, so all four threads have finished when it reads the count.

Make sure the single-thread loop from step 2 is gone from `main`, then run `java Main.java` three times.

One possible set of three runs:

```
Expected 400000, counted 107900
```

```
Expected 400000, counted 130875
```

```
Expected 400000, counted 125047
```

Yours will be different numbers, but almost certainly **below 400,000**, and **different each time**. The same code, the same input, a different wrong answer on every run. Hundreds of thousands of increments went missing, and nothing complained.

**There's no `sleep` in this step, on purpose.** In guide 01, each worker spent nearly all its time asleep, so the workers were hardly ever running at the same instant. Here, four threads call `increment()` as fast as they can, so they collide again and again. A race that's rare in slow code is still there. It just shows less often, which is worse: it gets past your tests and fails on a busy day.

> **Sidebar: what if you got 400,000?** Run it again, a few times. On a machine with one core, or one that's very busy, the threads may barely overlap, and the count can come out right. A right answer once doesn't make the code right. The code has a race whether or not this run lost anything, and it will lose updates when the timing is different. A second way to make the race hide is to run it **more than once inside the same program**. If you loop `countingRace()` five times in `main`, the first one or two rounds lose updates, and the rest usually come out exactly right. While a program runs, the JVM's JIT compiler (guide 01's first sidebar) rewrites the hottest code to run faster, and here that changes *how* the threads collide, so they rarely overlap any more. The code is no safer, only harder to catch. That's why this guide asks you to run `java Main.java` three times, one race per launch. Even step 2's single-thread loop is enough to warm the code up: left in front of `countingRace()`, it lets an occasional run come out at 400,000.

### 4. Why updates go missing

`count++` looks like one action. It's three:

1. **Read** the current value of `count` from memory.
2. **Add one** to it.
3. **Write** the new value back to `count`.

On one thread that's fine. With two, the scheduler can pause a thread between any of the three, and let another thread run. Here's one way it can go. Two threads each run `count++` once, starting from 41:

| Moment | Thread A | Thread B | `count` in memory |
|---|---|---|---|
| 1 | reads 41 | | 41 |
| 2 | | reads 41 | 41 |
| 3 | adds one: 42 | | 41 |
| 4 | | adds one: 42 | 41 |
| 5 | writes 42 | | 42 |
| 6 | | writes 42 | **42** |

Two increments ran, and `count` went up by **one**. Thread B read 41 before thread A had written its 42, so B's write overwrote A's. That's a **lost update**.

Four threads doing this 100,000 times each, on several cores at once, lose updates all the time. Losing *most* of them, as step 3 did, is the JIT at work again: once the loop is compiled, a thread can read `count` once, add to its own copy many times, and write the result back much later, wiping out everything the other threads wrote in between. Nothing in the code tells the JVM another thread is involved, so it's allowed to do that.

The fix has to make read, add and write happen as **one atomic step**, so that no other thread can come in between. Steps 5 to 7 show three ways.

### 5. `synchronized` methods

Add the word `synchronized` to both methods in `OrderCounter.java`:

```java
public class OrderCounter {

    private int count;

    public synchronized void increment() {
        count++;
    }

    public synchronized int get() {
        return count;
    }
}
```

Run `java Main.java` three times:

```
Expected 400000, counted 400000
```

400,000 every time. You haven't touched `Main.java`.

A **`synchronized` method** takes the lock of the object it's called on, `this`, before it runs, and releases it when it returns, even if it throws. Here that's the one shared counter. So:

- **Only one thread at a time can be inside `increment()`.** If thread B calls it while thread A is inside, B waits until A has returned. Read, add and write now happen as one atomic step, and step 4's table can't happen.
- **`get()` takes the same lock**, so it can't read the count while an `increment()` is half-done.
- **What a thread wrote before releasing a lock is seen by the next thread to take it.** That's called **visibility**, and step 6 explains why it matters for `get()`.

**This comes at a cost.** The four threads now take turns: each `increment()` has to wait for the lock, take it, and release it, and only one thread makes progress at a time. Counting under a lock is slower than counting without one, sometimes much slower, but the unlocked version is fast *and wrong*. Keep the code inside a lock as short as you can, so threads don't queue for longer than they need to.

### 6. `synchronized` blocks and a private lock

A `synchronized` method always locks `this`. You can choose which object to lock instead, and lock just part of a method, with a **`synchronized` block**. Replace `OrderCounter.java`:

```java
public class OrderCounter {

    private final Object lock = new Object();
    private int count;

    public void increment() {
        synchronized (lock) {
            count++;
        }
    }

    public int get() {
        synchronized (lock) {
            return count;
        }
    }
}
```

`synchronized (lock) { ... }` takes `lock`'s lock, runs the block, and releases it when the block ends, however it ends. Run it three times: 400,000 every time, as in step 5.

**Why a separate, private lock?** `lock` is an ordinary `new Object()`, used only for its built-in lock. Locking `this`, as step 5 did, works, but `this` is the counter itself, and any code that has the counter can lock it too, by writing `synchronized (counter) { ... }`. Someone else's code could then hold *your* lock for as long as it likes, and slow down or block every `increment()`. Nobody outside `OrderCounter` can reach a `private` field, so nobody else can take this lock. Making it **`final`** means it can never be swapped for a different object: every thread is guaranteed to lock the same one.

**Why does `get()` need the lock?** It only reads. But a thread that reads a field without a lock isn't promised the latest value. Without a lock, the JVM and the CPU may keep a value where only one thread can see it, such as a register or a core's cache, as step 4's JIT did. The only promise is the one from step 5: what a thread wrote before releasing a lock is seen by the next thread that takes **the same lock**. So the reader has to take it too.

In *this* program you'd get away with an unlocked `get()`, because it runs after `join()`, and `join()` also guarantees you see everything the joined thread did (guide 01's interview angle). But `OrderCounter` doesn't know who'll call it, or when. The rule is: **every read and every write of shared, changing data happens under the same lock.**

### 7. `AtomicInteger`

Locking works, but for a single number, Java has something made for the job. Replace `OrderCounter.java`:

```java
import java.util.concurrent.atomic.AtomicInteger;

public class OrderCounter {

    private final AtomicInteger count = new AtomicInteger();

    public void increment() {
        count.incrementAndGet();
    }

    public int get() {
        return count.get();
    }
}
```

**`AtomicInteger`**, in `java.util.concurrent.atomic`, is an `int` that's safe to share between threads:

- **`new AtomicInteger()`** starts at 0.
- **`incrementAndGet()`** adds one as a single atomic step and returns the new value. Here the return value isn't needed, so it's ignored.
- **`get()`** reads the current value, with the same visibility promise a lock gives: you see the latest value.

There's no `synchronized` anywhere. The read, add and write happen as one step in the processor itself, using a special instruction that only writes the new value if nobody has changed it since it was read, and tries again if they have. No thread ever holds a lock, so none ever waits for one.

Run it three times: 400,000 every time.

Notice that `Main.java` didn't change in steps 5, 6 or 7. `OrderCounter` kept the same two methods, `increment()` and `get()`, so the code that uses it never knew how it worked inside. Keeping shared data inside a class, behind its own methods, means there's one place to get the thread safety right.

**Locks or atomics?** For one value on its own, such as a count, an atomic class is simpler and usually faster. For **several values that must change together**, such as a count and a total that must always agree, an atomic class can't help: each one is atomic on its own, but nothing makes the pair change as one. That needs a lock around both.

This is the version of `OrderCounter` the rest of the series uses.

### 8. Back to orders

Now give the workers from guide 01 a counter of their own. Replace `Main.java`:

```java
import java.util.ArrayList;
import java.util.List;

public class Main {

    static final int WORKERS = 4;
    static final int INCREMENTS_PER_WORKER = 100_000;

    public static void main(String[] args) throws InterruptedException {
        countingRace();
        processOrders();
    }

    static void countingRace() throws InterruptedException {
        OrderCounter counter = new OrderCounter();
        List<Thread> workers = new ArrayList<>();

        for (int w = 0; w < WORKERS; w++) {
            workers.add(new Thread(() -> {
                for (int i = 0; i < INCREMENTS_PER_WORKER; i++) {
                    counter.increment();
                }
            }));
        }

        for (Thread worker : workers) {
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        System.out.println("Expected " + (WORKERS * INCREMENTS_PER_WORKER) + ", counted " + counter.get());
    }

    static void processOrders() throws InterruptedException {
        System.out.println("Running on: " + Thread.currentThread().getName());

        OrderProcessor processor = new OrderProcessor();
        OrderCounter counter = new OrderCounter();
        List<Order> firstBatch = orders(1, 3);
        List<Order> secondBatch = orders(4, 3);
        long start = System.nanoTime();

        Thread worker1 = new Thread(() -> {
            for (Order order : firstBatch) {
                processor.process(order);
                counter.increment();
            }
        }, "worker-1");

        Thread worker2 = new Thread(() -> {
            for (Order order : secondBatch) {
                processor.process(order);
                counter.increment();
            }
        }, "worker-2");

        worker1.start();
        worker2.start();
        worker1.join();
        worker2.join();

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println("All orders processed in " + elapsedMs + " ms");
        System.out.println("Expected " + (firstBatch.size() + secondBatch.size()) + " orders, counted " + counter.get());
    }

    static List<Order> orders(int firstId, int count) {
        List<Order> orders = new ArrayList<>();
        for (int id = firstId; id < firstId + count; id++) {
            orders.add(new Order(id, "Widget", 1 + id % 5, 250));
        }
        return orders;
    }
}
```

What changed:

- **`main` runs both**: the counting race first, as a check that the counter is safe, then the order processing.
- **`processOrders()` creates an `OrderCounter`**, and both workers share it. Each worker calls `counter.increment()` after it has processed an order.
- **The last line prints the expected count**, the size of both batches, beside the counted one.

Run it.

One possible run:

```
Expected 400000, counted 400000
Running on: main
[worker-2] started order 4
[worker-1] started order 1
[worker-1] finished order 1: 500p
[worker-1] started order 2
[worker-2] finished order 4: 1250p
[worker-2] started order 5
[worker-1] finished order 2: 750p
[worker-1] started order 3
[worker-2] finished order 5: 250p
[worker-2] started order 6
[worker-2] finished order 6: 500p
[worker-1] finished order 3: 1000p
All orders processed in 954 ms
Expected 6 orders, counted 6
```

Six orders, six counted.

Here's the uncomfortable part. With step 2's plain `int` counter, this would almost certainly *also* print 6. Two workers, six increments, each 300 ms apart: they'd practically never collide. The count is right because `OrderCounter` is safe, which you've shown with 400,000 increments, not because 6 came out right. A test with six orders would have passed the broken counter too. That's why the race in `countingRace()` stays in the program: it's the check that would fail.

That's the program for this guide.

---

## Your turn

### Exercise 1: a running total

The business also wants the total value of the orders processed, in pence. Add it to `OrderCounter`, beside the count:

- a `long totalPence` field
- an `addPence(long pence)` method that adds to it
- a `totalPence()` method that returns it

Make both methods thread-safe with `synchronized`, as in step 5. Then have each worker in `processOrders()` add its order's total after counting it, and print the total at the end. Before you run it, work out what the total should be from the six orders' totals.

<details>
<summary>Solution</summary>

`OrderCounter.java`:

```java
import java.util.concurrent.atomic.AtomicInteger;

public class OrderCounter {

    private final AtomicInteger count = new AtomicInteger();
    private long totalPence;

    public void increment() {
        count.incrementAndGet();
    }

    public int get() {
        return count.get();
    }

    public synchronized void addPence(long pence) {
        totalPence += pence;
    }

    public synchronized long totalPence() {
        return totalPence;
    }
}
```

In `processOrders()`, each worker's loop becomes:

```java
            for (Order order : firstBatch) {
                processor.process(order);
                counter.increment();
                counter.addPence(order.totalPence());
            }
```

(and the same with `secondBatch` for `worker2`). At the end of `processOrders()`:

```java
        System.out.println("Total value: " + counter.totalPence() + "p");
```

The run ends:

```
All orders processed in 920 ms
Expected 6 orders, counted 6
Total value: 4250p
```

500 + 750 + 1000 + 1250 + 250 + 500 = 4250p.

`totalPence += pence` is a read, an add and a write, just like `count++`, so it needs the lock as much. Six orders can't show that, as step 8 said. To check it under pressure, call `counter.addPence(250)` beside `counter.increment()` in `countingRace()`'s loop, and print the total there too: with `synchronized` it's always 100,000,000p (400,000 × 250). Take `synchronized` off `addPence` and it falls short, typically by tens of millions of pence. Put it back afterwards.

The count and the total are each correct, but they're protected separately: the count by `AtomicInteger`, the total by the counter's own lock. A thread reading both *while* the workers ran could see a count that includes an order whose pence hadn't been added yet. Here both are read after the `join()`s, so they agree. If they had to agree at every moment, both would need to change under the same lock.

You can keep the running total or remove it. Guide 03 starts from the version in step 8.

</details>

### Exercise 2: lock one method, not the other

Put step 5's `synchronized` version of `OrderCounter` back. Then remove `synchronized` from **`increment()` only**, leaving it on `get()`:

```java
public class OrderCounter {

    private int count;

    public void increment() {
        count++;
    }

    public synchronized int get() {
        return count;
    }
}
```

Before you run it, decide: does a `synchronized` `get()` protect the count? Run `java Main.java` three times and check the first line. Then fix it.

<details>
<summary>Solution</summary>

It doesn't. One possible set of first lines:

```
Expected 400000, counted 113753
Expected 400000, counted 128230
Expected 400000, counted 108336
```

Updates go missing just as in step 3. A lock only keeps out threads that **also take it**. The four threads in `countingRace()` call `increment()`, which takes no lock, so nothing stops them from colliding. `get()` waiting politely for a lock doesn't help anyone.

To fix it, put `synchronized` back on `increment()`, and the count is 400,000 every time. Then put the `AtomicInteger` version from step 7 back, because the rest of the series uses it.

If you'd removed `synchronized` from `get()` instead, and kept it on `increment()`, you'd have seen 400,000 every time, because `get()` runs after the `join()`s here. That version is still wrong, for the reason in step 6. See Common errors.

</details>

### Exercise 3: predict, then run

Answer each question **before** you run anything. Then check your answers using the versions of `OrderCounter` from this guide.

1. With step 5's `synchronized` counter, is the count in `countingRace()` **always** 400,000?
2. With step 2's unlocked counter, can the count ever be **more** than 400,000?
3. With step 2's unlocked counter, can the count ever be **exactly** 400,000?
4. Step 8 prints `Expected 6 orders, counted 6`. Does that prove `OrderCounter` is thread-safe?

<details>
<summary>Solution</summary>

1. **Yes.** Every `increment()` takes the counter's lock, so each one finishes its read, add and write before the next can start. None can be lost, and `get()` runs after all four threads have been joined.
2. **No.** Each increment writes back one more than a value it read, and every value it could read was written by an increment that really happened. Updates can be lost, but none can be made up. The count can only come out short.
3. **Yes, it can.** Nothing forces the threads to collide. On a quiet single-core machine, or when the JIT has already warmed up the loop (the sidebar after step 3), the count can come out right. That doesn't make the code right, only the run lucky.
4. **No.** Six increments, 300 ms apart, would practically never collide, so the broken counter would print 6 too. The evidence that the counter is safe is the 400,000 in the first line, and better still, the reasoning in answer 1.

Questions 1 and 2 have certain answers, because of what a lock guarantees and what an increment can do. Question 3 shows why "it worked when I ran it" never settles a thread-safety question.

</details>

---

## Checkpoint

Run `java Main.java` a few times from `workspace/java-basics/threads/`. In every run:

- the first line is `Expected 400000, counted 400000`
- the next line is `Running on: main`
- there are 12 order lines, as at the end of guide 01: 6 `started` and 6 `finished`, labelled `[worker-1]` (orders 1 to 3) and `[worker-2]` (orders 4 to 6), each worker's in order
- `All orders processed in … ms` follows, with a time a little over 900 ms
- the last line is `Expected 6 orders, counted 6`

How the two workers' lines interleave changes from run to run. That's expected.

`OrderCounter.java` is the `AtomicInteger` version from step 7. Your folder should look like this:

```
workspace/java-basics/threads/
├── Main.java
├── Order.java
├── OrderCounter.java
└── OrderProcessor.java
```

If you kept exercise 1's running total, you'll also have a `Total value: 4250p` line at the end. Guide 03 starts from the version shown in step 8.

---

## Common errors

**The count is still below 400,000, though there's a `synchronized` block**
Each thread is locking a *different* object. A lock only keeps out threads that take the same one. A common way to get this wrong is to create the lock where it's used:

```java
    public void increment() {
        Object lock = new Object();
        synchronized (lock) {
            count++;
        }
    }
```

Every call creates a brand-new object, so every call gets a lock nobody else holds, and nothing waits for anything. One run counted 119,973 out of 400,000. The same happens if each worker's lambda locks an object of its own. Make the lock one `private final` field of the class that owns the data, as in step 6, so every thread locks the same object.

**`get()` left without the lock**
It looks harmless, because it only reads. The program may even print the right answer, if `get()` happens to run after a `join()`, as in this guide. But a thread that reads shared data without taking the lock isn't promised the latest value, and can see an old one indefinitely. Lock every read as well as every write, with the same lock (step 6).

**`count.set(count.get() + 1)` on an `AtomicInteger`**
It uses an `AtomicInteger`, so it looks thread-safe. It isn't: `get()` is atomic and `set()` is atomic, but the gap between them isn't, and it's step 4's read, add and write all over again. One run counted 166,772 out of 400,000. Use the single method that does the whole change as one step: `incrementAndGet()`.

**The program hangs, and never finishes: a deadlock**
Two threads each hold one lock and wait for the other's, so both wait forever. This is the smallest version, with two locks taken in opposite orders. If you want to see it, save it as `Deadlock.java` beside your other files and run `java Deadlock.java`:

```java
import java.time.Duration;

public class Deadlock {

    public static void main(String[] args) throws InterruptedException {
        Object stockLock = new Object();
        Object paymentLock = new Object();

        Thread first = new Thread(() -> {
            synchronized (stockLock) {
                System.out.println("[first] has stockLock, wants paymentLock");
                try {
                    Thread.sleep(Duration.ofMillis(100));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                synchronized (paymentLock) {
                    System.out.println("[first] has both");
                }
            }
        }, "first");

        Thread second = new Thread(() -> {
            synchronized (paymentLock) {
                System.out.println("[second] has paymentLock, wants stockLock");
                try {
                    Thread.sleep(Duration.ofMillis(100));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                synchronized (stockLock) {
                    System.out.println("[second] has both");
                }
            }
        }, "second");

        first.start();
        second.start();
        first.join();
        second.join();
        System.out.println("Done");
    }
}
```

It prints two lines, and then nothing, ever:

```
[first] has stockLock, wants paymentLock
[second] has paymentLock, wants stockLock
```

`first` holds `stockLock` and waits for `paymentLock`. `second` holds `paymentLock` and waits for `stockLock`. Neither will let go of the lock it has until it gets the one it wants, so neither ever does. The 100 ms sleeps only make sure each thread takes its first lock before the other takes its second. Without them, the deadlock would happen only sometimes, which is worse.

**Press Ctrl+C** in the terminal to stop it. A deadlocked program never ends by itself, so you have to. In IntelliJ, use the red stop button. Then delete `Deadlock.java`.

The fix: **whenever code takes more than one lock, always take them in the same order everywhere.** If `second` also took `stockLock` first, then `paymentLock`, it would simply wait for `first` to finish with both, and the program would print `Done`. Better still, hold one lock at a time where you can.

---

## Recap

- When threads share data that **changes**, the result can depend on timing. That's a **race condition**. Races don't crash and don't happen every time, so you have to reason about them, not test for them.
- **`count++` is three steps**: read, add one, write back. Two threads can both read the same value, and one update is lost.
- Every object has a built-in **lock**. **`synchronized` methods** lock `this`. **`synchronized (lock) { ... }`** blocks lock an object you choose: make it a **`private final`** field, so nothing else can take it and it can't be swapped.
- A lock gives **mutual exclusion** (one thread inside at a time) and **visibility** (what was written before a release is seen after the next take of the same lock). **Reads need the lock too.**
- **`AtomicInteger`** does read, add and write as one atomic step with no lock: `incrementAndGet()` and `get()`. For one value, prefer it. For several values that must change together, use a lock around all of them.
- **Hiding the shared data inside a class** (`OrderCounter`) means there's one place to get it right, and the callers never change.
- A correct-looking run proves nothing: a race can hide when threads rarely overlap, as with six orders, or once the JIT has warmed up.
- A **deadlock** is threads waiting for each other's locks forever. Take locks in the same order everywhere.

### Interview angle

<details>
<summary>"What's the difference between a race condition and a data race?"</summary>

A **data race** is a precise term from the Java Memory Model: two threads access the same variable, at least one of them writes, and nothing orders the two accesses, such as a lock or `join()`. A **race condition** is the broader idea of a bug whose outcome depends on timing. An unsynchronised `count++` from several threads is both. They can occur separately, though. With an `AtomicInteger`, `if (count.get() < 10) { count.incrementAndGet(); }` has no data race, since every access is atomic, but it's still a race condition: two threads can both see 9 and both increment, ending at 11. Each step is atomic, but the check and the act together aren't.

</details>

<details>
<summary>"What does <code>synchronized</code> guarantee?"</summary>

Two things. **Mutual exclusion**: only one thread can hold a given object's lock at a time, so code guarded by the same lock never runs in two threads at once. **Visibility**: releasing a lock *happens before* every later take of the same lock, so everything a thread wrote before leaving a `synchronized` block is visible to the next thread that enters a block on that object. Without the second, a reader could see stale values even if writers took turns. A `synchronized` method locks `this` (or the class object, for a `static` method). A `synchronized` block locks the object you name. The lock is released however the block exits, including by an exception, and it's **reentrant**: a thread that already holds it can take it again, so one `synchronized` method can call another on the same object.

</details>

<details>
<summary>"When would you use an atomic class instead of a lock?"</summary>

When the shared state is a single variable and the update is one of the operations the atomic class offers, such as incrementing a counter. Atomic classes use the processor's compare-and-set instruction instead of a lock, so threads never block waiting for each other: under contention they retry instead. They're usually faster for a hot counter and can't deadlock. Their limit is that each one protects only itself. Once an update involves several variables that must stay consistent, such as a count and a total, or a check followed by an action, you need a lock around the whole thing.

</details>

<details>
<summary>"What is a deadlock, and how do you prevent one?"</summary>

A deadlock is two or more threads each waiting for a lock that another of them holds, so none can ever continue. It needs four conditions at once: **mutual exclusion** (a lock can be held by only one thread), **hold and wait** (a thread holds one lock while waiting for another), **no preemption** (a lock can't be taken away from the thread holding it), and **circular wait** (A waits for B, which waits for A). Break any one and deadlock can't happen. In practice you break circular wait by **always taking locks in the same global order**. Other options are holding one lock at a time, or using a lock that can give up after a timeout (from `java.util.concurrent.locks`, outside this series). A deadlocked Java program shows up in a thread dump, which names the threads and the locks they're waiting for.

</details>

<details>
<summary>"What does <code>volatile</code> do?"</summary>

`volatile` on a field gives **visibility without mutual exclusion**: every read sees the most recent write by any thread, and the JVM won't keep the value in a register or reorder accesses to it in ways other threads could notice. It's the right tool for a simple flag one thread sets and others read, such as `volatile boolean running`. It does **not** make compound actions atomic: `count++` on a `volatile int` is still read, add, write, and still loses updates. For that you need a lock or an atomic class. See *Java Concurrency in Practice*, chapter 3, in Further reading.

</details>

### Further reading

- [The Java Tutorials: Synchronization](https://docs.oracle.com/javase/tutorial/essential/concurrency/sync.html): thread interference, memory consistency, `synchronized` methods and blocks, atomic access and deadlock
- [`AtomicInteger` (Java SE 25 API)](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/atomic/AtomicInteger.html), and its [package summary](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/atomic/package-summary.html) for `AtomicLong` and the other atomic classes
- [The Java Language Specification, chapter 17: Threads and Locks](https://docs.oracle.com/javase/specs/jls/se25/html/jls-17.html): the Java Memory Model, with *happens-before*, `synchronized` and `volatile` defined precisely
- *Java Concurrency in Practice*, by Brian Goetz and others: chapters 2 and 3 cover thread safety, visibility and `volatile`, and chapter 10 covers deadlock
- [`java.util.concurrent.locks`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/locks/package-summary.html): `ReentrantLock` and the other explicit locks, for when `synchronized` isn't enough. Outside this series

**Simplification to notice:** every thread in this guide was created by hand, one `new Thread(...)` at a time, and the only way to get anything back from one was through a shared object like `OrderCounter`. That's fine for four threads. It doesn't scale to thousands of orders, and a thread has no way to *return* a value, such as an order's total.

**Next:** [03: Executors and futures](03-executors-and-futures.md). Hand the orders to a pool of reusable threads, get each order's total back through a `Future`, and find out what happens when an order fails on another thread.
