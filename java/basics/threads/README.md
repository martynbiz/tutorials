# Java threads: Order Processor

A series that teaches you to run Java code on **more than one thread**: to start threads and wait for them, to share data between them without corrupting it, to hand work to a pool, and to use Java's **virtual threads** for work that spends its time waiting.

Concurrency has a reputation for being hard, and the reputation is earned. The bugs depend on timing, so they come and go between runs, and code that looks correct can be quietly wrong. This series takes it slowly. Each guide starts from a working program, shows you a problem happening for real on your own machine, and only then fixes it.

---

## What you'll build

An **order processor**: a small program that processes a batch of orders, where "processing" an order means waiting, as if for a payment service to answer. It grows over the four guides:

```
  guide 01   main ──▶ worker-1  ┐
                  └─▶ worker-2  ┴─▶ six orders in half the time

  guide 02   workers ──▶ one shared count of processed orders: wrong, then right

  guide 03   main ──▶ a pool of 4 threads ──▶ each order's total comes back ──▶ grand total

  guide 04   10,000 orders on virtual threads: about 10 s becomes well under 1 s
```

Every guide uses the same three or four small files, and each guide starts where the one before it finished.

---

## Versions

| Thing | Version | Notes |
| --- | --- | --- |
| Java | **25** (LTS) | Any OpenJDK 25 build. [Eclipse Temurin](https://adoptium.net/temurin/releases/?version=25) is a good free choice |
| Build tool | None | Code runs straight from source with `java Main.java` |
| Libraries | None | Everything is in the JDK: `java.lang.Thread` and `java.util.concurrent` |
| IDE | None needed | Any text editor will do |

Nothing in the series needs `--enable-preview`. Every feature it uses is final in Java 25.

---

## What you need to know already

This series assumes you're comfortable with everyday Java:

- **Classes, objects and interfaces.**
- **Lists and loops:** `List`, `ArrayList`, the enhanced `for` loop.
- **Exceptions:** `try`/`catch`, and checked versus unchecked.
- **Records:** a one-line `record` declaration and its accessors.
- **Lambda syntax:** reading and writing `() -> ...`. Guide 01 reminds you how a lambda stands in for an interface, but doesn't teach lambdas from scratch.

You **don't** need to have written any concurrent code before.

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
├── java/basics/threads/             ← these guides
└── workspace/java-basics/threads/   ← your code (git-ignored)
    ├── Main.java                    ← starts the workers
    ├── Order.java                   ← one order: id, product, quantity, unit price
    └── OrderProcessor.java          ← processes one order
```

Guide 01's first step creates the folder. You keep the same folder for the whole series, and each guide changes the files in it.

**Running it.** From `workspace/java-basics/threads/`:

```bash
java Main.java
```

`java` compiles `Main.java` in memory, finds the other `.java` files it uses in the same folder, and runs it. There are no `.class` files and no build step. If you use IntelliJ IDEA, the green arrow next to `main` works too, but the guides always show the terminal.

---

## The guides

Follow them **in order**. Each builds on the code from the one before, and ends with a program that runs.

| # | Guide | You'll learn |
| --- | --- | --- |
| 01 | [Your first threads](01-your-first-threads.md) | Processes and threads. The `main` thread. Starting a thread from a lambda. `start()` versus `run()`. `join()`. Interleaved output. `InterruptedException`. Why threads help with waiting |
| 02 | Sharing data safely | A race condition you can watch happen. `synchronized` methods and blocks. `AtomicInteger`. Deadlock |
| 03 | Executors and futures | Thread pools with `ExecutorService`. Getting results back with `Callable` and `Future`. A failure on another thread, and `ExecutionException` |
| 04 | Virtual threads | Thousands of waiting tasks on virtual threads. When they help, and when they don't |

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

- **"One possible run."** With more than one thread, the order of the output, and sometimes the numbers in it, can change from one run to the next. Output like that is labelled **one possible run**. Yours will have the same kind of lines, but probably not in the same order. Exercises that ask you to predict output only ask about things that are certain.
- **Timings are approximate.** Your machine will give different milliseconds from the guides. What matters is the size of the difference, not the exact figure.
- **Money is in whole pence**, as a `long`: `250` is £2.50. That keeps the arithmetic exact without bringing in `BigDecimal`.
- **No packages.** Every file sits in the same folder, so `java Main.java` can find it.
- **Commands** are shown for PowerShell and for Git Bash, macOS or Linux where they differ. `java Main.java` is the same everywhere.
