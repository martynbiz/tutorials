# 03: Executors and futures

> Written for **Java 25**. Uses only the JDK: no build tool and no IDE. See the [series README](README.md) for setup.

## What you'll learn

- Why real programs don't start a new `Thread` for every piece of work, and what a **thread pool** does instead
- How to create a fixed pool with **`Executors.newFixedThreadPool`** and hand it work with **`submit`**
- How **try-with-resources** closes a pool and waits for its work to finish, and what happens to a program whose pool is never closed
- How a **`Callable`** returns a value from another thread, and how a **`Future`** hands it back with **`get()`**
- What happens when a task throws on a pool thread, and how to unwrap **`ExecutionException`** with **`getCause()`**

By the end, a pool of four threads will process thirteen orders, add up their totals, and report the one bad order, all without a single `new Thread(...)` in the order code.

---

## Prerequisites for this guide

- [Guide 01](01-your-first-threads.md): `start()`, `join()`, and the catch-and-restore pattern for `InterruptedException`.
- [Guide 02](02-sharing-data-safely.md): the `OrderCounter` class, and why a counter shared between threads must be thread-safe.
- The code from guide 02's Checkpoint, in `workspace/java-basics/threads/`. Step 1 shows it in full, so you can start here.
- **Generics**, enough to read `List<Future<Long>>` as "a list of `Future`s, each holding a `Long`".

---

## Concepts

### Tasks and threads

So far, each thread you've made has been tied to one job: you wrote the job as a lambda and gave it to `new Thread(...)`. When the job ended, the thread ended with it.

It helps to pull those two ideas apart:

- A **task** is a piece of work: "process order 7".
- A **thread** is something that runs tasks.

Once they're separate, one thread can run many tasks, one after another. That's the idea this guide is built on.

### Thread pools

A **thread pool** is a set of threads that are started once and then kept, plus a queue of tasks waiting to run. You hand the pool a task, and it goes in the queue. Whenever one of the pool's threads is free, it takes the next task from the queue and runs it. When the task ends, the thread doesn't. It goes back for another.

So a pool of 4 threads can work through 13 tasks: 4 at a time, with the rest waiting in the queue for a thread to come free.

### Executors

Java's thread pools live in `java.util.concurrent`, under the interface **`ExecutorService`**: something you can hand tasks to, which runs them for you. You rarely write one yourself. The **`Executors`** class has static methods that build the common kinds. This guide uses one of them, `Executors.newFixedThreadPool(n)`, a pool with exactly `n` threads.

### Futures

A thread started with `new Thread(...)` can't hand a value back. Its `Runnable.run()` returns `void`, so the only way to get anything out was through a shared object, like guide 02's `OrderCounter`.

A pool can do better. When you submit a task that returns a value, the pool gives you a **`Future`** straight away: a placeholder for a result that may not exist yet. Later, you ask the `Future` for the result. If the task has finished, you get its value. If it hasn't, you wait until it has. If the task threw an exception, you get that instead.

---

## Steps

### 1. Start from guide 02

This guide starts where [guide 02](02-sharing-data-safely.md) ended. If you're carrying on, your `workspace/java-basics/threads/` folder should already have these four files. If you're starting here, create the folder (guide 01, step 1) and these files in it.

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

`OrderCounter.java`:

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

`Main.java`:

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

Run `java Main.java` once to check. The first line is `Expected 400000, counted 400000`, and the last is `Expected 6 orders, counted 6`.

This guide changes only `processOrders()` in `Main.java`, and `OrderProcessor.java`. `countingRace()` stays as guide 02 left it, as the check that `OrderCounter` is safe.

### 2. Why not one thread per order?

`processOrders()` splits six orders between two hand-made threads. The business now has twelve orders, and next month it will have thousands. The obvious next move is one thread per order:

```java
for (Order order : orders) {
    new Thread(() -> processor.process(order)).start();
}
```

Don't add this. It has three problems, and they get worse as the orders grow:

- **Each thread costs something to create.** A Java thread like the ones in this series is backed by a real operating-system thread, with its own memory for its stack. Starting one takes far longer than calling a method. Guide 04 measures how much.
- **There's no limit.** 10,000 orders would mean 10,000 threads at once, all competing for a handful of cores. Nothing in this loop says "at most four at a time".
- **There's no way to get a result back.** Each thread's job is a `Runnable`, and `run()` returns `void`. To add up the orders' totals, you'd need another shared, thread-safe object like `OrderCounter`, and a `join()` on every thread, which means keeping them all in a list.

A thread pool fixes all three. Its threads are created once and reused, there are only as many as you ask for, and, from step 5, every task can hand back a value.

### 3. A fixed pool

Replace `processOrders()` in `Main.java`, and add two imports at the top:

```java
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
```

```java
    static void processOrders() throws InterruptedException {
        OrderProcessor processor = new OrderProcessor();
        OrderCounter counter = new OrderCounter();
        List<Order> orders = orders(1, 12);
        long start = System.nanoTime();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (Order order : orders) {
            pool.submit(() -> {
                processor.process(order);
                counter.increment();
            });
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println("All orders processed in " + elapsedMs + " ms");
        System.out.println("Expected " + orders.size() + " orders, counted " + counter.get());
    }
```

What's new:

- **There's one list of twelve orders**, not two batches. You no longer decide which thread processes which order. The pool does.
- **`Executors.newFixedThreadPool(4)`** creates a pool of four threads. They wait for work.
- **`pool.submit(...)`** puts a task in the pool's queue and returns straight away. The task is the same kind of lambda as before, a `Runnable`: process the order, then count it. As in guide 02, the counter is shared by every task, so it has to be thread-safe, and it is.

`order`, the loop variable, can be used in the lambda because each pass round an enhanced `for` loop gets a fresh `order` that's never reassigned, so it's *effectively final* (guide 01, step 8).

Run it.

One possible run:

```
Expected 400000, counted 400000
All orders processed in 4 ms
[pool-1-thread-3] started order 3
[pool-1-thread-2] started order 2
[pool-1-thread-4] started order 4
Expected 12 orders, counted 0
[pool-1-thread-1] started order 1
[pool-1-thread-4] finished order 4: 1250p
[pool-1-thread-1] finished order 1: 500p
[pool-1-thread-3] finished order 3: 1000p
[pool-1-thread-2] finished order 2: 750p
[pool-1-thread-4] started order 5
[pool-1-thread-1] started order 6
[pool-1-thread-3] started order 8
[pool-1-thread-2] started order 7
[pool-1-thread-2] finished order 7: 750p
[pool-1-thread-2] started order 9
[pool-1-thread-4] finished order 5: 250p
[pool-1-thread-1] finished order 6: 500p
[pool-1-thread-1] started order 11
[pool-1-thread-3] finished order 8: 1000p
[pool-1-thread-4] started order 10
[pool-1-thread-3] started order 12
[pool-1-thread-2] finished order 9: 1250p
[pool-1-thread-3] finished order 12: 750p
[pool-1-thread-1] finished order 11: 500p
[pool-1-thread-4] finished order 10: 250p
```

Three things to notice:

- **Only four thread names appear**, `pool-1-thread-1` to `pool-1-thread-4`, for twelve orders. That's the pool reusing its threads: `pool-1-thread-2` processed orders 2, 7 and 9. Pool threads are named `pool-` plus the pool's number, then `-thread-` plus the thread's number.
- **The closing lines came first, and they're wrong**: 4 ms, and 0 counted. `submit` only queues the task, so `main` ran on to the end while the pool was still on its first four orders. It's guide 01's step 5 again, with nothing to `join()`.
- **The program doesn't end.** After the last order, nothing happens, and the prompt doesn't come back. The pool's threads are waiting for more work, and the program can't end until they do (guide 01, step 5). Nobody has told the pool there's no more work coming.

**Press Ctrl+C** to stop it.

### 4. Closing the pool with try-with-resources

A pool has to be **closed** when you've finished with it. Closing a pool tells it that no more tasks are coming, and waits for the ones already submitted to finish. Then its threads end.

`ExecutorService` is `AutoCloseable`, so you can let **try-with-resources** do it. In `processOrders()`, wrap the pool and the submit loop like this:

```java
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            for (Order order : orders) {
                pool.submit(() -> {
                    processor.process(order);
                    counter.increment();
                });
            }
        }
```

At the closing brace of the `try`, Java calls `pool.close()` for you, whether the block ended normally or with an exception. `close()` stops the pool accepting new tasks, then waits for every task already submitted to finish, and the pool's threads end. So the lines after the `try` run only once all twelve orders have been processed.

Run it.

One possible run:

```
Expected 400000, counted 400000
[pool-1-thread-1] started order 1
[pool-1-thread-2] started order 2
[pool-1-thread-4] started order 4
[pool-1-thread-3] started order 3
[pool-1-thread-3] finished order 3: 1000p
[pool-1-thread-1] finished order 1: 500p
[pool-1-thread-4] finished order 4: 1250p
[pool-1-thread-3] started order 5
[pool-1-thread-2] finished order 2: 750p
[pool-1-thread-2] started order 8
[pool-1-thread-1] started order 6
[pool-1-thread-4] started order 7
[pool-1-thread-1] finished order 6: 500p
[pool-1-thread-3] finished order 5: 250p
[pool-1-thread-2] finished order 8: 1000p
[pool-1-thread-2] started order 11
[pool-1-thread-4] finished order 7: 750p
[pool-1-thread-4] started order 12
[pool-1-thread-1] started order 9
[pool-1-thread-3] started order 10
[pool-1-thread-2] finished order 11: 500p
[pool-1-thread-3] finished order 10: 250p
[pool-1-thread-1] finished order 9: 1250p
[pool-1-thread-4] finished order 12: 750p
All orders processed in 916 ms
Expected 12 orders, counted 12
```

The closing lines are last and true, and the program ends. Twelve orders took about 0.9 s: four threads, each doing three orders of 300 ms.

**What you'll see in older code.** `ExecutorService` has only been `AutoCloseable` since Java 19. Before that, you closed a pool by hand, in two steps, and you'll meet this in a lot of existing code:

```java
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (Order order : orders) {
            pool.submit(() -> {
                processor.process(order);
                counter.increment();
            });
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);
```

`shutdown()` stops the pool accepting new tasks, but doesn't wait. `awaitTermination` then waits for the tasks to finish, for at most the time you give it (this needs `import java.util.concurrent.TimeUnit;`). It does the same job as `close()`, but it's easy to forget a step, or to skip both when an exception is thrown before them. Recognise it, and use try-with-resources in your own code.

### 5. `Callable`: a task that returns a value

Now make each order hand back its total. First, make `process` return it. Replace `OrderProcessor.java`:

```java
import java.time.Duration;

public class OrderProcessor {

    public long process(Order order) throws InterruptedException {
        String thread = Thread.currentThread().getName();
        System.out.println("[" + thread + "] started order " + order.id());

        Thread.sleep(Duration.ofMillis(300));

        System.out.println("[" + thread + "] finished order " + order.id() + ": " + order.totalPence() + "p");
        return order.totalPence();
    }
}
```

Two changes:

- **`process` returns a `long`**: the order's total, in pence.
- **The `try`/`catch` has gone, and `process` declares `throws InterruptedException` instead.** In guide 01, `process` had to catch it, because it was called from inside a `Runnable`, and `run()` can't throw checked exceptions. Guide 01 also said the better choice, where it's allowed, is to let the exception go to the caller. From this step, `process` is only called from tasks that *are* allowed to throw, as you're about to see. A method that returns a total also has no sensible value to return when it's interrupted, so letting the exception out is the honest choice.

Run it, and it won't compile:

```
Main.java:47: error: unreported exception InterruptedException; must be caught or declared to be thrown
                    processor.process(order);
                                     ^
1 error
error: compilation failed
```

The task in `processOrders()` is still a `Runnable`, and a `Runnable` can't throw `InterruptedException`. The fix is a different kind of task. In `processOrders()`, make the task return the total:

```java
                pool.submit(() -> {
                    long total = processor.process(order);
                    counter.increment();
                    return total;
                });
```

Now it compiles. A lambda that **returns a value** can't be a `Runnable`, so Java treats it as the other kind of task `submit` accepts, a **`Callable`**, from `java.util.concurrent`:

```java
public interface Callable<V> {
    V call() throws Exception;
}
```

`Callable` is `Runnable`'s sibling, with two differences: **`call()` returns a value**, of type `V`, and **it may throw any exception**, checked ones included. Here `V` is `Long`: the lambda returns a `long`, and Java boxes it into a `Long`.

If a task didn't need to count anything, it could be one line: `pool.submit(() -> processor.process(order))`. That's a `Callable` too, because `process` returns a value.

So where does the returned total go? `submit` hands it back, wrapped in a **`Future<Long>`**. Keep them in a list. Replace `processOrders()`, and add one more import, `java.util.concurrent.Future`:

```java
    static void processOrders() throws InterruptedException {
        OrderProcessor processor = new OrderProcessor();
        OrderCounter counter = new OrderCounter();
        List<Order> orders = orders(1, 12);
        List<Future<Long>> futures = new ArrayList<>();
        long start = System.nanoTime();

        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            for (Order order : orders) {
                futures.add(pool.submit(() -> {
                    long total = processor.process(order);
                    counter.increment();
                    return total;
                }));
            }
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println("All orders processed in " + elapsedMs + " ms");
        System.out.println("Expected " + orders.size() + " orders, counted " + counter.get());
    }
```

Run it. The output looks just like step 4's, ending:

```
All orders processed in 917 ms
Expected 12 orders, counted 12
```

The twelve totals are in the twelve `Future`s, but nothing reads them yet. That's the next step.

### 6. `Future.get()`: collecting the results

A **`Future<Long>`** is a handle on one task's result. Its main method is **`get()`**:

- If the task has finished, `get()` returns its result straight away.
- If it hasn't, `get()` **blocks**: the calling thread waits until the task has finished, then returns the result.

In `processOrders()`, add a running total next to `futures`:

```java
        long grandTotal = 0;
```

Then add a second loop inside the `try`, after the submit loop, to collect every result:

```java
            for (Future<Long> future : futures) {
                grandTotal += future.get();
            }
```

And print it, after the other two lines at the end:

```java
        System.out.println("Total: " + grandTotal + "p");
```

`get()` throws two checked exceptions: `InterruptedException`, because it waits, and **`ExecutionException`**, which step 7 is about. For now, let them go to the caller. Add `import java.util.concurrent.ExecutionException;`, and add `ExecutionException` to the `throws` clause of both `processOrders()` and `main`:

```java
    public static void main(String[] args) throws InterruptedException, ExecutionException {
```

```java
    static void processOrders() throws InterruptedException, ExecutionException {
```

Run it a few times.

One possible run ends:

```
[pool-1-thread-1] finished order 12: 750p
All orders processed in 926 ms
Expected 12 orders, counted 12
Total: 8750p
```

**The total is 8750p in every run**, however the order lines interleave. You can check it by hand: `1 + id % 5` gives the quantities 2, 3, 4, 5, 1 for orders 1 to 5, and the same again for 6 to 10, then 2 and 3 for orders 11 and 12. That's 15 + 15 + 5 = 35 widgets, at 250p each: 8750p, or £87.50.

Notice the shape: **submit everything first, then collect.** All twelve tasks are in the pool before the first `get()`. While `main` waits in `get()` for order 1, the pool is already working on orders 1 to 4, and the others wait in the queue. By the time order 1's `get()` returns, order 2's result is often there already, so its `get()` doesn't wait at all.

The collecting loop reads the `Future`s in the order they were submitted, not the order the tasks finished. That doesn't change the total, because adding up gives the same answer in any order. And it means you always know which result belongs to which order.

### 7. A bad order

`OrderProcessor` should refuse an order with a negative quantity. Add a check at the start of `process`, so `OrderProcessor.java` becomes:

```java
import java.time.Duration;

public class OrderProcessor {

    public long process(Order order) throws InterruptedException {
        if (order.quantity() < 0) {
            throw new IllegalArgumentException("Order " + order.id() + " has a negative quantity: " + order.quantity());
        }

        String thread = Thread.currentThread().getName();
        System.out.println("[" + thread + "] started order " + order.id());

        Thread.sleep(Duration.ofMillis(300));

        System.out.println("[" + thread + "] finished order " + order.id() + ": " + order.totalPence() + "p");
        return order.totalPence();
    }
}
```

The check is in `process`, not in `Order`, so a bad order can still be created, and it's the pool thread that finds the problem.

In `processOrders()`, add a bad order after the twelve good ones:

```java
        List<Order> orders = orders(1, 12);
        orders.add(new Order(13, "Widget", -1, 250));
```

Run it.

One possible run ends:

```
[pool-1-thread-4] finished order 11: 500p
Exception in thread "main" java.util.concurrent.ExecutionException: java.lang.IllegalArgumentException: Order 13 has a negative quantity: -1
	at java.base/java.util.concurrent.FutureTask.report(FutureTask.java:124)
	at java.base/java.util.concurrent.FutureTask.get(FutureTask.java:193)
	at Main.processOrders(Main.java:59)
	at Main.main(Main.java:15)
Caused by: java.lang.IllegalArgumentException: Order 13 has a negative quantity: -1
	at OrderProcessor.process(OrderProcessor.java:7)
	at Main.lambda$processOrders$0(Main.java:52)
	at java.base/java.util.concurrent.FutureTask.run(FutureTask.java:328)
	at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1090)
	at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:614)
	at java.base/java.lang.Thread.run(Thread.java:1474)
```

Read it from the bottom half up:

- **`Caused by: java.lang.IllegalArgumentException`** is the exception `process` threw. Its stack trace runs through `ThreadPoolExecutor` and `Thread.run`: it happened on a **pool thread**, not on `main`.
- The pool didn't let the exception kill its thread. It caught it and **stored it in that task's `Future`**, where the result would have gone.
- When `main` called **`get()`** on order 13's `Future`, `get()` threw the stored exception, wrapped in an **`ExecutionException`**. That's the top half of the trace, and it's on `main`.

So an exception on another thread doesn't vanish, and it doesn't crash that thread. It travels back to whoever asks for the result. All twelve good orders were still processed: the first twelve `get()`s returned normally.

Now handle it. A bad order shouldn't stop the run. It should be reported, and the rest should be added up. Catch `ExecutionException` around each `get()`, and use **`getCause()`** to get at the original exception inside. Here's the whole of `Main.java` now:

```java
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
        OrderProcessor processor = new OrderProcessor();
        OrderCounter counter = new OrderCounter();
        List<Order> orders = orders(1, 12);
        orders.add(new Order(13, "Widget", -1, 250));
        List<Future<Long>> futures = new ArrayList<>();
        long grandTotal = 0;
        int failed = 0;
        long start = System.nanoTime();

        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            for (Order order : orders) {
                futures.add(pool.submit(() -> {
                    long total = processor.process(order);
                    counter.increment();
                    return total;
                }));
            }

            for (Future<Long> future : futures) {
                try {
                    grandTotal += future.get();
                } catch (ExecutionException e) {
                    failed++;
                    System.out.println("An order failed: " + e.getCause());
                }
            }
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println("All orders processed in " + elapsedMs + " ms");
        System.out.println("Orders: " + orders.size() + " submitted, " + counter.get() + " processed, " + failed + " failed");
        System.out.println("Total: " + grandTotal + "p");
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

What changed from step 6:

- **Each `get()` has its own `try`/`catch`**, so one failed order doesn't stop the loop from collecting the others.
- **`e.getCause()`** is the `IllegalArgumentException` that `process` threw. Printing it shows its type and message.
- **`failed`** counts the failures. It's a plain `int`, and that's safe: only `main` touches it.
- **The summary line** now shows submitted, processed and failed. The bad order threw before `counter.increment()`, so it isn't counted as processed.
- **`ExecutionException` has gone from both `throws` clauses**, because it's caught now. `InterruptedException` stays: `get()` can still be interrupted while it waits.

Run it.

One possible run:

```
Expected 400000, counted 400000
[pool-1-thread-1] started order 1
[pool-1-thread-3] started order 3
[pool-1-thread-4] started order 4
[pool-1-thread-2] started order 2
[pool-1-thread-3] finished order 3: 1000p
[pool-1-thread-2] finished order 2: 750p
[pool-1-thread-1] finished order 1: 500p
[pool-1-thread-4] finished order 4: 1250p
[pool-1-thread-4] started order 6
[pool-1-thread-2] started order 5
[pool-1-thread-1] started order 7
[pool-1-thread-3] started order 8
[pool-1-thread-1] finished order 7: 750p
[pool-1-thread-3] finished order 8: 1000p
[pool-1-thread-3] started order 10
[pool-1-thread-2] finished order 5: 250p
[pool-1-thread-2] started order 11
[pool-1-thread-4] finished order 6: 500p
[pool-1-thread-4] started order 12
[pool-1-thread-1] started order 9
[pool-1-thread-4] finished order 12: 750p
[pool-1-thread-1] finished order 9: 1250p
[pool-1-thread-2] finished order 11: 500p
[pool-1-thread-3] finished order 10: 250p
An order failed: java.lang.IllegalArgumentException: Order 13 has a negative quantity: -1
All orders processed in 916 ms
Orders: 13 submitted, 12 processed, 1 failed
Total: 8750p
```

Order 13 never prints `started`, because the check comes first. The total is still 8750p: the twelve good orders, and nothing from the bad one.

That's the program for this guide.

---

## Your turn

### Exercise 1: a second pool for large orders

The warehouse wants a separate total for **large orders**, those with a quantity of 4 or more. After the summary lines in `processOrders()`, add a second pool of **2** threads that processes only the large orders from the same list, collects their totals through `Future`s, and prints them. Work out the answer before you run it.

<details>
<summary>Solution</summary>

At the end of `processOrders()`, after `System.out.println("Total: " + grandTotal + "p");`:

```java
        long largeTotal = 0;
        List<Future<Long>> largeFutures = new ArrayList<>();

        try (ExecutorService largePool = Executors.newFixedThreadPool(2)) {
            for (Order order : orders) {
                if (order.quantity() >= 4) {
                    largeFutures.add(largePool.submit(() -> processor.process(order)));
                }
            }

            for (Future<Long> future : largeFutures) {
                try {
                    largeTotal += future.get();
                } catch (ExecutionException e) {
                    System.out.println("A large order failed: " + e.getCause());
                }
            }
        }

        System.out.println("Large orders total: " + largeTotal + "p");
```

One possible run ends:

```
Total: 8750p
[pool-2-thread-1] started order 3
[pool-2-thread-2] started order 4
[pool-2-thread-2] finished order 4: 1250p
[pool-2-thread-1] finished order 3: 1000p
[pool-2-thread-2] started order 8
[pool-2-thread-1] started order 9
[pool-2-thread-2] finished order 8: 1000p
[pool-2-thread-1] finished order 9: 1250p
Large orders total: 4500p
```

The large orders are 3 and 8 (quantity 4) and 4 and 9 (quantity 5): 18 widgets at 250p, so 4500p. Order 13's quantity is -1, so it never reaches the second pool, but `get()` can still throw `ExecutionException`, so the compiler needs the `catch` anyway.

The second pool's threads are called `pool-2-thread-…`: it's the second pool the program created. It has two threads for four orders, so it takes about 0.6 s. These tasks don't count anything, so each one is the one-line `Callable` from step 5.

You can keep the second pool or remove it. Guide 04 starts from the version in step 7.

</details>

### Exercise 2: where did the total go?

A colleague wanted to see what the pool returned, so they replaced the collecting loop with this:

```java
            for (Future<Long> future : futures) {
                System.out.println(future);
            }
```

Before you run it, decide: what will the `Total` line say, and will `An order failed` appear? Make the change, run it, then put it right.

<details>
<summary>Solution</summary>

One possible run:

```
Expected 400000, counted 400000
java.util.concurrent.FutureTask@1e6a3214[Not completed, task = Main$$Lambda/0x0000000084169c30@20140db9]
java.util.concurrent.FutureTask@1a6d8329[Not completed, task = Main$$Lambda/0x0000000084169c30@368247b9]
...
[pool-1-thread-3] started order 3
[pool-1-thread-2] started order 2
...
All orders processed in 919 ms
Orders: 13 submitted, 12 processed, 0 failed
Total: 0p
```

Thirteen lines like the first two, then the orders.

- **Printing a `Future` doesn't print its result.** It prints the `Future` object itself: its class, `FutureTask`, its hash code, and its state. Most say `Not completed`, because the loop ran straight after the submits, before the tasks had finished. The numbers after `@` will be different on your machine.
- **The total is `0p`.** Nothing called `get()`, so nothing was added.
- **The bad order disappeared.** The `IllegalArgumentException` is still sitting in order 13's `Future`, and nobody asked for it. It says `0 failed`, and no error is printed anywhere. That's the dangerous part: a pool keeps a task's exception until someone calls `get()`, so a `Future` you never read can hide a failure completely.

To put it right, go back to step 7's loop, with `get()` inside a `try`/`catch`:

```java
            for (Future<Long> future : futures) {
                try {
                    grandTotal += future.get();
                } catch (ExecutionException e) {
                    failed++;
                    System.out.println("An order failed: " + e.getCause());
                }
            }
```

</details>

### Exercise 3: predict, then run

Using step 7's program, answer each question **before** you run it. Then run it several times and check your answers against every run.

1. Does the `Total` depend on which task **finishes first**?
2. Does the **order of the `[pool-1-thread-…]` lines** depend on it?
3. Is `An order failed: …` always printed **before** `All orders processed in … ms`?
4. Does order 13's failure **stop** any of orders 1 to 12 from being processed?

<details>
<summary>Solution</summary>

1. **No.** The collecting loop reads the `Future`s in the order they were submitted, and each `get()` waits for its own task, however long that takes. The same twelve totals are added up every time, and addition gives the same answer in any order: 8750p.
2. **Yes.** Four threads run at once, and the scheduler decides which of them prints next. The lines interleave differently from run to run, as in guide 01.
3. **Yes.** Both lines are printed by `main`, one after the other in the code: the failure is printed inside the `try`, and the summary after it. A single thread runs its code in order.
4. **No.** Each order is its own task. Order 13's exception is caught by the pool and stored in order 13's `Future`. It affects nothing else, and the summary always says `12 processed`.

Questions 1, 3 and 4 have certain answers because something forces them: the order of the `Future`s in the list, a single thread's sequence, and tasks that don't depend on each other. Question 2 has nothing forcing it.

</details>

---

## Checkpoint

Run `java Main.java` a few times from `workspace/java-basics/threads/`. In every run:

- the first line is `Expected 400000, counted 400000`
- there are 24 order lines: `started` and `finished` for orders 1 to 12, each from one of `pool-1-thread-1` to `pool-1-thread-4`, with each order's `finished` after its `started`
- the totals are as in guide 01: 500p, 750p, 1000p, 1250p and 250p for quantities 2, 3, 4, 5 and 1
- `An order failed: java.lang.IllegalArgumentException: Order 13 has a negative quantity: -1` appears once, and order 13 is never `started`
- then `All orders processed in … ms`, with a time a little over 900 ms
- then `Orders: 13 submitted, 12 processed, 1 failed`
- the last line is `Total: 8750p`
- the program ends by itself

How the order lines interleave, and which pool thread processes which order, can change from run to run. That's expected.

Your folder still has the same four files:

```
workspace/java-basics/threads/
├── Main.java
├── Order.java
├── OrderCounter.java
└── OrderProcessor.java
```

If you kept exercise 1's second pool, you'll also have eight `pool-2-thread-…` lines and `Large orders total: 4500p` at the end. Guide 04 starts from the version shown in step 7.

---

## Common errors

**Everything runs one order at a time, and twelve orders take 3.6 s: `get()` inside the submit loop**
It looks tidy to submit a task and read its result in the same loop:

```java
            for (Order order : orders) {
                Future<Long> future = pool.submit(() -> {
                    long total = processor.process(order);
                    counter.increment();
                    return total;
                });
                try {
                    grandTotal += future.get();
                } catch (ExecutionException e) {
                    failed++;
                    System.out.println("An order failed: " + e.getCause());
                }
            }
```

But `get()` blocks until that task has finished, and the next task isn't submitted until then. So the pool only ever has one task, and its four threads take turns at one order each. One run took 3638 ms instead of about 900. Submit everything first, then collect (step 6).

**The program prints everything, then never ends**
The pool was never closed, so its threads wait for more work for ever (step 3). Press Ctrl+C to stop it, then create the pool with try-with-resources (step 4). If you close it by hand, make sure `shutdown()` is called even when an exception is thrown.

**Printing an `ExecutionException`, or only its message**
The `ExecutionException` itself is just the envelope. Printing `e` gives `java.util.concurrent.ExecutionException: java.lang.IllegalArgumentException: Order 13 …`, and its stack trace points at `get()` in `main`, not at the code that failed. `e.getMessage()` happens to include the cause's text, but only as a `String`: you can't tell from it what kind of failure it was, and the original exception, with the stack trace showing where it went wrong, is lost if that's all you keep. Use `e.getCause()`: that's the real exception (step 7).

**`java.util.concurrent.FutureTask@1e6a3214[Not completed, task = …]`**
You printed a `Future` instead of its result. Call `get()` to get the result (exercise 2). The same mistake shows up as a total of 0 when the `Future` is never read, and any exception inside it is never seen.

**`error: unreported exception InterruptedException; must be caught or declared to be thrown`, pointing at `processor.process(order)` inside `pool.submit(...)`**
The task doesn't return anything, so Java has made it a `Runnable`, and a `Runnable` can't throw checked exceptions. Make the lambda return the result of `process`, so it becomes a `Callable` (step 5).

---

## Recap

- A **task** is a piece of work, and a **thread** runs tasks. A **thread pool** keeps a fixed set of threads and a queue of tasks, and reuses the threads for task after task.
- **`Executors.newFixedThreadPool(n)`** creates an **`ExecutorService`** with `n` threads. **`submit`** queues a task and returns at once.
- A pool must be **closed**, or its threads keep the program alive. Use **try-with-resources**: `close()` stops the pool accepting tasks and waits for every one already submitted. Older code does the same with `shutdown()` and `awaitTermination(...)`.
- A **`Runnable`** returns nothing and can't throw checked exceptions. A **`Callable<V>`** returns a `V` and may throw anything. A lambda that returns a value is a `Callable`.
- **`submit`** returns a **`Future`**: a handle on a result that may not exist yet. **`get()`** returns the result, waiting for it if necessary. **Submit everything first, then collect**, or the work runs one task at a time.
- An exception thrown by a task is stored in its `Future`. **`get()`** throws it wrapped in an **`ExecutionException`**, and **`getCause()`** gives the original. A `Future` nobody reads hides its exception for good.

### Interview angle

<details>
<summary>"What's the difference between <code>Runnable</code> and <code>Callable</code>?"</summary>

Both are functional interfaces describing a task. `Runnable.run()` returns `void` and declares no checked exceptions, so a `Runnable` can neither hand back a result nor throw `InterruptedException` or `IOException` without catching them. `Callable<V>.call()` returns a `V` and declares `throws Exception`. `Runnable` dates from Java 1.0 and is what `Thread` takes. `Callable` came with `java.util.concurrent` in Java 5, for use with executors. Submitting a `Callable` to an `ExecutorService` gives a `Future<V>` holding its result. Submitting a `Runnable` gives a `Future<?>` whose `get()` returns `null` when the task is done.

</details>

<details>
<summary>"What does <code>Future.get()</code> do, and how can it fail?"</summary>

It returns the task's result, blocking the calling thread until the task has completed if it hasn't already. It can fail in several ways. If the task threw an exception, `get()` throws `ExecutionException`, with the task's exception as its cause. If the waiting thread is interrupted, it throws `InterruptedException`. If the task was cancelled, it throws `CancellationException`. There's also `get(timeout, unit)`, which throws `TimeoutException` if the result isn't ready in time, and is the safer choice when a task might hang. Calling `get()` straight after each `submit` serialises the work, so the usual pattern is to submit all the tasks, then collect.

</details>

<details>
<summary>"How do you choose the size of a thread pool?"</summary>

It depends on what the tasks spend their time doing. For **computing** work, more threads than cores doesn't help, because only as many threads as there are cores can run at once, and extra ones just add switching. A pool of about `Runtime.getRuntime().availableProcessors()` threads is the usual starting point. For **waiting** work, such as calls to databases or other services, a thread spends most of its time blocked, so you need many more threads to keep the cores busy. A rule of thumb from *Java Concurrency in Practice* is cores × (1 + wait time ÷ compute time). In practice you measure, and you also respect outside limits, such as the size of the database connection pool. Since Java 21, virtual threads are often a better answer for waiting work than a large pool.

</details>

<details>
<summary>"What's the difference between <code>submit</code> and <code>execute</code>?"</summary>

`execute(Runnable)` comes from the `Executor` interface, returns nothing, and lets an exception from the task reach the pool thread's uncaught-exception handler, which by default prints it. `submit` comes from `ExecutorService`, accepts a `Runnable` or a `Callable`, returns a `Future`, and captures any exception in that `Future`, so it's only seen when someone calls `get()`.

</details>

### Further reading

- [The Java Tutorials: Executors](https://docs.oracle.com/javase/tutorial/essential/concurrency/executors.html): executor interfaces and thread pools. It predates try-with-resources for pools, so it closes them by hand
- [`ExecutorService` (Java SE 25 API)](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ExecutorService.html), including `close()`, `shutdown()` and `awaitTermination`
- [`Executors` (Java SE 25 API)](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/Executors.html): the other kinds of pool, such as cached and scheduled ones
- [`Future` (Java SE 25 API)](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/Future.html), and [`ExecutionException`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ExecutionException.html)
- *Java Concurrency in Practice*, by Brian Goetz and others: chapter 6 (task execution) and chapter 8 (sizing and configuring thread pools)
- [`CompletableFuture` (Java SE 25 API)](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/CompletableFuture.html): a `Future` you can chain further steps onto, instead of blocking in `get()`. Outside this series

**Simplification to notice:** the pool has four threads, and each order spends 300 ms doing nothing but waiting. Four threads that mostly wait is a waste. With 10,000 orders, this pool would take 10,000 ÷ 4 × 0.3 s, or 750 s. A bigger pool would help, but each of its threads is a real operating-system thread, and those are expensive.

**Next:** [04: Virtual threads](04-virtual-threads.md). Process 10,000 waiting orders in well under a second with threads so cheap you can have one per order, and find out why they don't help with computing.
