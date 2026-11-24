# 01: Your first threads

> Written for **Java 25**. Uses only the JDK: no build tool and no IDE. See the [series README](README.md) for setup.

## What you'll learn

- What a **thread** is, how it relates to the program it belongs to, and the **`main`** thread every Java program starts with
- How to start a new thread from a **`Runnable` lambda**, and why it's **`start()`** that does that, not `run()`
- How **`join()`** makes one thread wait for another, and what goes wrong without it
- Why the output of two threads **interleaves**, and why the order changes from one run to the next
- What to do with **`InterruptedException`**, the checked exception that comes with `Thread.sleep` and `join()`
- Why threads speed up work that spends its time **waiting**

By the end you'll have two worker threads processing six orders at the same time. On one thread, the six orders take 1.8 seconds. With two workers they take about 0.9 seconds.

---

## Prerequisites for this guide

This is the first guide in the series. It assumes you're comfortable with everyday Java:

- **Classes and objects:** fields, constructors, methods, `new`.
- **Interfaces**, and the idea that a class can implement one.
- **Lists and loops:** `List`, `ArrayList`, the enhanced `for` loop.
- **Exceptions:** `try`/`catch`, and the difference between checked and unchecked exceptions.
- **Records:** a one-line `record` declaration, and its accessor methods.
- **Lambda syntax:** reading and writing `() -> ...`.

You don't need to have written any concurrent code before.

You'll also need **JDK 25**, with `java` on your `PATH`. The [series README](README.md#before-you-start) shows how. Check with `java -version`.

---

## Concepts

### Programs, processes and threads

When you run `java Main.java`, your operating system starts a **process**: a running program with its own memory. Two processes can't see each other's memory. Your Java program and your web browser are separate processes.

Inside a process, the work is done by one or more **threads**. A thread is a single sequence of instructions running one after another: call this method, then that one, then return. Every thread in a process **shares the process's memory**, so they can all see the same objects. That's what makes threads useful, and it's also what makes them dangerous. [Guide 02](02-sharing-data-safely.md) is about the danger.

Every Java program starts with one thread, called **`main`**. It runs your `main` method. Until now, every line of Java you've written has run on it. In this guide you'll start more.

> **Sidebar: the JVM's own threads.** `main` isn't the only thread in a running Java program. The JVM starts several of its own, for garbage collection and for the JIT compiler, which turns your hottest code into machine code while it runs. You never see them in your output, but they're there, and the JIT will matter in guide 02.

### The scheduler decides

Your computer has a handful of **cores**, each able to run one thread at a time. There are usually far more threads than cores: hundreds across all the programs running on your machine. The operating system's **scheduler** shares the cores out. It runs a thread for a short while, pauses it, runs another, and keeps switching, thousands of times a second.

You don't control the scheduler, and you can't predict it. When two of your threads are both ready to run, you can't know which goes first, or when either will be paused. So **the order in which two threads' lines run changes from one run to the next.** Within *one* thread, the lines still run in the order you wrote them. Between threads, nothing is promised unless you make it so. `join()`, later in this guide, is the first way you'll do that.

That's why some output in this series is labelled **one possible run**. Yours will have the same lines, but probably not in the same order.

### Concurrency and parallelism

These two words are often used as if they meant the same thing. They don't:

- **Concurrency** is about *structure*: several tasks in progress at once, taking turns. One cook making three dishes is concurrent. They chop for one, stir another, check the oven, and all three dishes move forward.
- **Parallelism** is about *execution*: several tasks literally running at the same instant, on different cores. Three cooks are parallel.

Threads give you concurrency. If there are free cores, the scheduler *may* also run them in parallel. In this guide it barely matters, for the reason in the next section.

### Why threads help with waiting

A lot of real work is **waiting**: for a database to answer, a web service to reply, or a file to arrive. A thread that's waiting uses no CPU at all. It just sits there, and so does everything that thread was meant to do next.

The orders in this guide stand in for that. "Processing" an order means waiting 300 milliseconds, as if a payment service were taking its time. Processed one after another, six orders take 6 × 300 ms = 1.8 s. Almost all of that is waiting.

With two threads, one thread's waiting overlaps the other's. Two threads with three orders each finish in about 3 × 300 ms = 0.9 s. They'd do that even on a single core, because while one thread waits, the core is free for the other. That's concurrency paying off, without needing parallelism.

### A lambda can be a `Runnable`

To start a thread, you give it a job to do. The job is a **`Runnable`**, an interface from `java.lang` with one method:

```java
public interface Runnable {
    void run();
}
```

An interface with exactly one abstract method is a **functional interface**, and wherever one is expected, you can write a lambda instead of a class. These two are the same job:

```java
Runnable job = new Runnable() {
    @Override
    public void run() {
        System.out.println("Hello");
    }
};

Runnable sameJob = () -> System.out.println("Hello");
```

This series uses the lambda. Notice that `run()` has **no `throws` clause**. That will matter in step 4.

---

## Steps

### 1. Set up a folder

Check your setup first ([series README](README.md#before-you-start)): `java -version` should report version 25.

Then, from the `tutorials` folder, create a folder for this series and go into it.

PowerShell:

```powershell
mkdir workspace\java-basics\threads
cd workspace\java-basics\threads
```

Git Bash, macOS or Linux:

```bash
mkdir -p workspace/java-basics/threads
cd workspace/java-basics/threads
```

Every command in this series runs from that folder. You'll keep the same files for all four guides, and each guide changes them.

Create `Main.java`:

```java
public class Main {

    public static void main(String[] args) {
        System.out.println("Order processing starting");
    }
}
```

Run it:

```bash
java Main.java
```

```
Order processing starting
```

`java Main.java` compiles the file in memory and runs it. There's no separate `javac` step and no `.class` files to tidy up.

### 2. The `main` thread

Every line of code runs on *some* thread, and `Thread.currentThread()` tells you which. Add a line to `main`:

```java
public class Main {

    public static void main(String[] args) {
        System.out.println("Order processing starting");
        System.out.println("Running on: " + Thread.currentThread().getName());
    }
}
```

```
Order processing starting
Running on: main
```

`Thread` is the class in `java.lang` that represents a thread. `Thread.currentThread()` returns the `Thread` object for whichever thread is running that line, and `getName()` returns its name. The JVM names the starting thread `main`.

You'll print the thread's name a lot in this series. Once there's more than one thread, it's how you see which thread did what.

### 3. The `Order` record

Create a second file, `Order.java`, in the same folder:

```java
public record Order(int id, String product, int quantity, long unitPricePence) {

    public long totalPence() {
        return quantity * unitPricePence;
    }
}
```

Prices are in **whole pence**, as a `long`: `250` is £2.50. Using `double` for money gives rounding errors, and `BigDecimal` is a topic of its own, so whole pence keep the arithmetic exact and out of the way.

Replace `main` to try it:

```java
public class Main {

    public static void main(String[] args) {
        System.out.println("Running on: " + Thread.currentThread().getName());

        Order order = new Order(1, "Widget", 2, 250);
        System.out.println(order);
        System.out.println("Total: " + order.totalPence() + "p");
    }
}
```

```
Running on: main
Order[id=1, product=Widget, quantity=2, unitPricePence=250]
Total: 500p
```

You ran `java Main.java` again, and never mentioned `Order.java`. Since Java 22, when the file you run uses a class it can't find, `java` looks for a `.java` file of the same name in the same folder and compiles that too. So the series can split its code across files and still run with one command.

### 4. Processing orders, one at a time

Now the job itself. Create `OrderProcessor.java`:

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

`process` prints which thread started the order, waits 300 ms, then prints which thread finished it and the order's total.

**`Thread.sleep(Duration.ofMillis(300))`** pauses *the current thread* for 300 ms. Here it stands in for slow work, such as waiting for a payment service to answer. While a thread sleeps, it uses no CPU. You'll see `Thread.sleep(300)`, with the milliseconds as a plain number, in older code. The `Duration` version says its unit.

**`InterruptedException`** is a checked exception, so the compiler makes you deal with it. Here's what it means. One thread can ask another to stop by **interrupting** it. If the thread is asleep when that happens, `sleep` wakes up early and throws `InterruptedException`, and throwing it clears the thread's "interrupted" flag. The `catch` block does two things:

1. **`Thread.currentThread().interrupt()`** sets the flag again, so any code further up that checks it can still see that this thread was asked to stop.
2. **`return`** stops processing this order.

In short: someone asked this thread to stop, so put the flag back and stop. Nothing in this series interrupts a thread, so you won't see the `catch` block run. But you have to write *something* there, and this is the right something. What you must never write is an empty `catch` block, which throws the request away (see Common errors).

Now process three orders on `main`, and time them. Replace `Main.java`:

```java
import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) {
        System.out.println("Running on: " + Thread.currentThread().getName());

        OrderProcessor processor = new OrderProcessor();
        long start = System.nanoTime();

        for (Order order : orders(1, 3)) {
            processor.process(order);
        }

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

`orders(1, 3)` builds orders 1, 2 and 3. Every order is for widgets at £2.50, and `1 + id % 5` gives each one a quantity from 1 to 5, so the totals are different but predictable.

**`System.nanoTime()`** reads a clock that's only good for measuring how long something took. Take one reading before, one after, and subtract. Dividing by `1_000_000` turns nanoseconds into milliseconds.

```
Running on: main
[main] started order 1
[main] finished order 1: 500p
[main] started order 2
[main] finished order 2: 750p
[main] started order 3
[main] finished order 3: 1000p
All orders processed in 983 ms
```

Your time will differ slightly from run to run, but it will be a bit over 900 ms. Every order runs on `main`, one after the other, and `main` spends almost all of that time asleep.

### 5. Your first thread

Move the loop onto a new thread. In `main`, replace the `for` loop with this:

```java
        Thread worker = new Thread(() -> {
            for (Order order : orders(1, 3)) {
                processor.process(order);
            }
        });
        worker.start();
```

Leave the timing lines either side of it as they are. Here's what the new lines do:

- **`new Thread(...)`** creates a `Thread` object, and the lambda is its `Runnable`: the job it will do. Creating it doesn't run anything yet.
- **`worker.start()`** asks the JVM to start a new thread, which then calls the `Runnable`'s `run()`, and so your lambda, *on that new thread*. `start()` returns straight away, without waiting for the job, and `main` carries on with the next line.

Run it.

One possible run:

```
Running on: main
All orders processed in 1 ms
[Thread-0] started order 1
[Thread-0] finished order 1: 500p
[Thread-0] started order 2
[Thread-0] finished order 2: 750p
[Thread-0] started order 3
[Thread-0] finished order 3: 1000p
```

Three things to notice:

- **The orders ran on a different thread**, `Thread-0`. That's the name the JVM gives the first thread you create without naming it.
- **"All orders processed in 1 ms" came first, and it's wrong.** `main` started the worker, then went straight on to stop the clock and print, while the worker was still on its first order. Nothing told `main` to wait.
- **The orders were still processed** after `main` had finished. A Java program doesn't end when `main` ends. It ends when its last thread ends (strictly, its last *non-daemon* thread, but every thread you create in this series is one). So the JVM waited for `Thread-0`.

### 6. `join()`: waiting for a thread

To make `main` wait, call `join()` on the worker straight after starting it:

```java
        worker.start();
        worker.join();
```

**`worker.join()`** makes the current thread, `main`, wait until `worker` has finished. Only then does `main` carry on.

That doesn't compile yet, though:

```
Main.java:18: error: unreported exception InterruptedException; must be caught or declared to be thrown
        worker.join();
                   ^
```

`join()` waits, and like any waiting, it can be interrupted. This time, let `main` pass the exception on by declaring it:

```java
    public static void main(String[] args) throws InterruptedException {
```

Why can `main` declare it when `process` had to catch it? A method may declare any checked exception in its `throws` clause, and `main` is your method. A lambda used as a `Runnable` can't, because `Runnable.run()` declares no exceptions, and a lambda can't throw more than the method it implements. That's why `process` catches its own `InterruptedException`. It's called from inside a `Runnable`.

Run it:

```
Running on: main
[Thread-0] started order 1
[Thread-0] finished order 1: 500p
[Thread-0] started order 2
[Thread-0] finished order 2: 750p
[Thread-0] started order 3
[Thread-0] finished order 3: 1000p
All orders processed in 975 ms
```

Now the time is true again, and the closing line is always last. `join()` gives you a guarantee the scheduler doesn't: everything the worker did happens before the line after `join()`.

It's no faster than step 4, though. There's still only one thread processing orders. `main` handed the work over and then waited for it. Step 8 brings in a second worker.

### 7. `start()` versus `run()`

`Thread` also has a `run()` method, and calling it instead of `start()` is a classic mistake. Try it. Change `worker.start();` to `worker.run();` and delete the `worker.join();` line (`join()` only makes sense for a thread that was started):

```java
        worker.run();
```

```
Running on: main
[main] started order 1
[main] finished order 1: 500p
[main] started order 2
[main] finished order 2: 750p
[main] started order 3
[main] finished order 3: 1000p
All orders processed in 971 ms
```

It compiles, it runs, and the time looks right. But look at the names: every order ran on **`main`**. `run()` is just a method. Calling it runs your lambda right there, on the thread that called it, like any other method call. No new thread is involved.

Only **`start()`** creates a new thread. It's easy to miss in real code, because everything still *works*, just one thing at a time. The thread names give it away.

Change it back to `start()`, and put back the `worker.join();` line.

### 8. Two workers

Now split the orders between two workers, and name them so the output is easy to follow. Replace `Main.java`:

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

What's new:

- **The second argument to `new Thread`** is the thread's name, so the output says `worker-1` and `worker-2` instead of `Thread-0` and `Thread-1`.
- **Both workers start before either is joined.** If you wrote `worker1.start(); worker1.join(); worker2.start();`, worker 2 wouldn't start until worker 1 had finished, and you'd be back to one thing at a time.
- **The lambdas use `firstBatch`, `secondBatch` and `processor`**, which are local variables of `main`. A lambda may use a local variable from around it only if that variable is never reassigned after it's set (*effectively final*). These never are, so this compiles. Common errors shows the message you get when one is.

Run it three times.

One possible run:

```
Running on: main
[worker-1] started order 1
[worker-2] started order 4
[worker-1] finished order 1: 500p
[worker-1] started order 2
[worker-2] finished order 4: 1250p
[worker-2] started order 5
[worker-2] finished order 5: 250p
[worker-2] started order 6
[worker-1] finished order 2: 750p
[worker-1] started order 3
[worker-2] finished order 6: 500p
[worker-1] finished order 3: 1000p
All orders processed in 919 ms
```

Another run, from the same code:

```
Running on: main
[worker-2] started order 4
[worker-1] started order 1
[worker-1] finished order 1: 500p
[worker-2] finished order 4: 1250p
[worker-1] started order 2
[worker-2] started order 5
[worker-1] finished order 2: 750p
[worker-1] started order 3
[worker-2] finished order 5: 250p
[worker-2] started order 6
[worker-2] finished order 6: 500p
[worker-1] finished order 3: 1000p
All orders processed in 912 ms
```

The two workers' lines **interleave**, and differently each time. The scheduler decides which worker's next line runs first, and it never decides the same way twice. Yours will look different again.

Some things *are* the same in every run:

- **Each worker's own lines are in order.** `worker-1` always starts order 1, finishes it, then starts order 2. A single thread runs its code in sequence.
- **"All orders processed" is always last**, because of the two `join()`s.
- **The time is about 0.9 s for six orders**, half of the 1.8 s that one thread would take. While `worker-1` waits on an order, `worker-2` waits on another, so their waiting overlaps.

That's the program for this guide. In guide 02 the workers start sharing something, and it goes wrong.

---

## Your turn

### Exercise 1: a third worker

Add a third worker, `worker-3`, that processes orders 7, 8 and 9. Before you run it, decide roughly how long nine orders will take. Then run it and check.

<details>
<summary>Solution</summary>

In `main`, build a third batch next to the other two:

```java
        List<Order> thirdBatch = orders(7, 3);
```

Create the third worker after `worker2`:

```java
        Thread worker3 = new Thread(() -> {
            for (Order order : thirdBatch) {
                processor.process(order);
            }
        }, "worker-3");
```

Start all three before joining any, and join all three:

```java
        worker1.start();
        worker2.start();
        worker3.start();
        worker1.join();
        worker2.join();
        worker3.join();
```

One possible run ends:

```
[worker-3] finished order 9: 1250p
[worker-1] finished order 3: 1000p
[worker-2] finished order 6: 500p
All orders processed in 933 ms
```

Still about 0.9 s. Each worker handles three orders of 300 ms each, and all three workers wait at the same time, so a third worker adds three orders without adding time. On one thread, nine orders would take 2.7 s.

You can keep the third worker or remove it. Guide 02 starts from the two-worker version.

</details>

### Exercise 2: where did the time go?

A colleague's version of step 8 prints this. It's one possible run, but their runs nearly always start like this, with a tiny time near the top:

```
Running on: main
All orders processed in 2 ms
[worker-2] started order 4
[worker-1] started order 1
...
```

What did they leave out? Change your code to match theirs, run it to confirm, then put it right. Before you run it, decide whether all six orders will still be processed.

<details>
<summary>Solution</summary>

They left out both `join()` calls. Delete `worker1.join();` and `worker2.join();` from your `main`, and you get the same thing: `main` starts both workers, goes straight on to stop the clock, and prints a time of a couple of milliseconds, before the workers have finished their first orders.

**All six orders are still processed.** The program doesn't end when `main` does. It waits for the workers, so their lines appear after the misleading "All orders processed" (step 5).

To put it right, add the two `join()`s back after both `start()`s:

```java
        worker1.start();
        worker2.start();
        worker1.join();
        worker2.join();
```

The closing line goes back to being last, and the time goes back to about 0.9 s. You can delete the `throws InterruptedException` from `main` while there's no `join()`, but you'll need it again as soon as there is one.

</details>

### Exercise 3: predict, then run

Using step 8's program, answer each question **before** you run it. Then run it several times and check your answers against every run.

1. Is `All orders processed in … ms` always the **last** line?
2. Is `[worker-1] started order 1` always the **second** line?
3. Does `worker-1` always print `finished order 1` **before** `started order 2`?
4. Could `[worker-2] started order 4` ever appear **before** `Running on: main`?

<details>
<summary>Solution</summary>

1. **Yes.** `main` joins both workers before it prints that line, so everything the workers print comes before it.
2. **No.** Both workers are started one straight after the other, and the scheduler decides which runs first. The second run in step 8 starts with `worker-2`. You may need several runs to see it.
3. **Yes.** Both lines come from `worker-1`, and a single thread runs its code in order: `process` for order 1 returns before the loop calls it for order 2.
4. **No.** `main` prints `Running on: main` before it starts either worker, and a thread can't print anything before it has been started.

Questions 1, 3 and 4 have certain answers because something forces the order: a `join()`, a single thread's sequence, or `start()` itself. Question 2 has no certain answer, because nothing forces it. When you reason about threads, look for what forces the order. Where nothing does, assume any order can happen.

</details>

---

## Checkpoint

Run `java Main.java` a few times from `workspace/java-basics/threads/`. In every run:

- the first line is `Running on: main`
- there are 12 order lines: 6 `started` and 6 `finished`, labelled `[worker-1]` (orders 1 to 3) and `[worker-2]` (orders 4 to 6)
- each worker's lines are in order: started 1, finished 1, started 2, and so on
- the totals are 500p, 750p and 1000p for orders 1 to 3, and 1250p, 250p and 500p for orders 4 to 6
- the last line is `All orders processed in … ms`, with a time a little over 900 ms

How the two workers' lines interleave changes from run to run. That's expected.

Your folder should look like this:

```
workspace/java-basics/threads/
├── Main.java
├── Order.java
└── OrderProcessor.java
```

If you did exercise 1 and kept the third worker, you'll have 18 order lines and orders 7 to 9 as well. Guide 02 starts from the two-worker version shown in step 8.

---

## Common errors

**`error: unreported exception InterruptedException; must be caught or declared to be thrown`, pointing at `Thread.sleep(...)` inside a lambda**
A lambda used as a `Runnable` can't throw checked exceptions, because `Runnable.run()` declares none. Catch it where the sleep is: `catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }`, as `OrderProcessor.process` does (step 4).

**The same error, pointing at `worker.join()` in `main`**
`main` doesn't declare `throws InterruptedException`. Add it to `main`'s signature (step 6).

**`error: local variables referenced from a lambda expression must be final or effectively final`**
The lambda uses a local variable that changes after it's set, often a loop counter:

```java
for (int id = 1; id <= 3; id++) {
    Thread worker = new Thread(() -> processor.process(new Order(id, "Widget", 1, 250)));
```

The lambda might run after `id` has moved on, so Java won't let it see a variable that changes. Copy the value into a new variable inside the loop (`int orderId = id;`) and use that in the lambda, or build the orders first and loop over the list, as step 8 does.

**`Exception in thread "main" java.lang.IllegalThreadStateException`, at `java.lang.Thread.start`**
You called `start()` on the same `Thread` object twice. A thread runs once: when its job has finished, it can't be started again. To run the job again, create a new `Thread`.

**`error: cannot find symbol` … `symbol: class Order`**
`java Main.java` couldn't find `Order.java`. It has to be in the same folder as `Main.java`, and its name must match the record's name exactly, including the capital `O`. (`.java` files are case-sensitive in this way even on Windows.)

**Every line says `[main]`, and six orders take 1.8 s**
You called `run()` instead of `start()`. `run()` runs the job on the current thread, with no new thread (step 7). It isn't an error message, which is what makes it easy to miss.

**`All orders processed in 1 ms` comes first, and the worker lines come after it**
`main` didn't wait. There's a `join()` missing, or it comes before the matching `start()` (steps 5 and 6).

**An empty `catch (InterruptedException e) {}`**
It compiles, and it's tempting, but it's wrong. Catching the exception clears the thread's interrupted flag, so an empty block throws away a request to stop, and nothing further up can ever know it was made. Restore the flag with `Thread.currentThread().interrupt()` and stop what you're doing. Don't print the exception and carry on either: that still loses the request.

---

## Recap

- A **process** is a running program with its own memory. A **thread** runs one sequence of instructions inside it. All of a process's threads **share its memory**.
- Every Java program starts on the **`main`** thread. **`Thread.currentThread().getName()`** tells you which thread is running a line.
- **`new Thread(runnable, "name")`** creates a thread, and a lambda can be the `Runnable`. **`start()`** runs it on a **new** thread. **`run()`** just calls the job on the current one.
- **`join()`** makes the current thread wait for another to finish. Without it, `main` runs on regardless. A program ends when its last thread does, not when `main` does.
- The **scheduler** decides when each thread runs, so the order of two threads' output **changes from run to run**. Within one thread, code runs in order. Between threads, only something like `join()` forces an order.
- **`Thread.sleep(Duration)`** pauses the current thread. With **`InterruptedException`**, a `Runnable` must catch it, restore the flag with **`Thread.currentThread().interrupt()`**, and stop. `main` can declare **`throws InterruptedException`**. **Never write an empty `catch`.**
- Threads speed up work that **waits**: two workers that each wait 300 ms per order finish six orders in about 0.9 s instead of 1.8 s. That's **concurrency**, and it would work even on one core. **Parallelism** is threads running at the same instant on different cores.

### Interview angle

<details>
<summary>"What's the difference between calling <code>start()</code> and <code>run()</code> on a <code>Thread</code>?"</summary>

`start()` asks the JVM to create a new thread of execution, which then calls `run()`. The caller carries on immediately, and the two run concurrently. Calling `run()` directly is an ordinary method call: the job runs on the calling thread, to completion, before the call returns, and no new thread is created. The bug is easy to miss because the program still works, just sequentially. Thread names in logs are the giveaway. `start()` can also be called only once per `Thread` object. A second call throws `IllegalThreadStateException`.

</details>

<details>
<summary>"What does <code>join()</code> do?"</summary>

`t.join()` blocks the calling thread until `t` has finished. It's the simplest way to wait for work you've handed to another thread, and it also guarantees that everything `t` did is visible to the caller afterwards. There's a version with a timeout, `join(Duration)`, which waits at most that long. Like other blocking calls, it throws `InterruptedException` if the waiting thread is interrupted. A common mistake is joining each thread straight after starting it, which turns concurrent work back into sequential work: start them all first, then join them all.

</details>

<details>
<summary>"What's the difference between a process and a thread?"</summary>

A process is a running program with its own address space. The operating system keeps processes' memory apart, so they communicate only through things like files, sockets or pipes. A thread runs inside a process, and all of a process's threads share its heap, so they can work on the same objects directly. Each thread has its own stack, for its local variables and method calls. Threads are cheaper to create and switch between than processes. Sharing memory is also what makes them risky: two threads changing the same object at once can corrupt it.

</details>

<details>
<summary>"What should you do when you catch <code>InterruptedException</code>?"</summary>

Interruption is how one thread asks another to stop, and catching `InterruptedException` clears the thread's interrupted flag. So you should either let the exception propagate (declare `throws InterruptedException`), or, where you can't (inside `Runnable.run()`, for example), restore the flag with `Thread.currentThread().interrupt()` and stop what you're doing, so code further up can still see the request. Swallowing it with an empty `catch` means the request is lost, and a thread that was meant to stop carries on, which is how shutdowns hang.

</details>

<details>
<summary>"What's the difference between concurrency and parallelism?"</summary>

Concurrency is about dealing with several tasks at once: their lifetimes overlap, and they make progress by taking turns. Parallelism is about doing several things at the same instant, on separate cores. You can have concurrency without parallelism (many threads on one core, interleaved by the scheduler), and that's enough to speed up work that mostly waits on I/O. CPU-heavy work only speeds up with real parallelism: more cores working at once.

</details>

### Further reading

- [`Thread` (Java SE 25 API)](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Thread.html): the class itself, including `start`, `join`, `sleep` and interruption
- [The Java Tutorials: Concurrency](https://docs.oracle.com/javase/tutorial/essential/concurrency/), older but still accurate on threads, `sleep`, interrupts and `join`
- [JEP 458: Launch Multi-File Source-Code Programs](https://openjdk.org/jeps/458), the Java 22 change that lets `java Main.java` find `Order.java`
- *Java Concurrency in Practice*, by Brian Goetz and others: the classic book on the subject. It predates virtual threads, but its foundations haven't changed

**Simplification to notice:** the two workers never touch the same data. Each has its own batch, and the only thing they share is the `OrderProcessor`, which keeps nothing between calls. That's why nothing has gone wrong yet. The moment two threads change the same object, it will.

**Next:** [02: Sharing data safely](02-sharing-data-safely.md). Count the orders the workers process, watch the count come out wrong, and fix it with `synchronized` and `AtomicInteger`.
