# Java basics

Short, standalone guides to core Java language features. Each one teaches one topic from scratch, with small examples you type and run yourself.

- **Java 25**, and nothing but the JDK: no Maven, no Gradle, no IDE required.
- Code runs straight from source with `java Main.java`.
- Each guide stands alone. Read them in any order.

## Guides

| Guide | What it covers |
|---|---|
| [Records](records.md) | Declaring records, what Java generates, compact constructors for validation and normalisation, withers, defensive copies, records in sets and maps |
| [Interfaces](interfaces.md) | Implementing interfaces, coding against an interface, several interfaces, constants, default, static and private methods, clashing defaults, functional interfaces and a first look at lambdas |

Coming later:

- **Lambdas:** capturing variables, method references, and `java.util.function`
- **Pattern matching:** record patterns, and `switch` and `instanceof` that take records apart
- **Sealed types:** sealed interfaces with records, for a fixed set of alternatives
- **Generic and local records**

## Before you start

### 1. Install JDK 25

You need the **JDK** (Java Development Kit), not just a Java runtime: it includes the compiler (`javac`), `jshell` and `javap`.

Any OpenJDK 25 build works. [Eclipse Temurin](https://adoptium.net/temurin/releases/?version=25) is a good free choice: pick your operating system and download the **JDK** installer.

On Windows, the Temurin installer offers to **Add to PATH** and **Set JAVA_HOME**. Turn both on.

### 2. Check it works

Open a **new** terminal (PowerShell, Git Bash, or Terminal on macOS or Linux), so it picks up the new `PATH`, and run:

```bash
java -version
javac -version
jshell --version
```

You should see version 25 from each, for example:

```
openjdk version "25.0.2" 2026-01-20 LTS
...
javac 25.0.2
jshell 25.0.2
```

If any of them says `command not found` or `is not recognized`, the JDK's `bin` folder isn't on your `PATH`. Re-run the installer with **Add to PATH** turned on, or add the folder yourself, then open a new terminal.

If `java -version` shows an older version, another JDK comes first on your `PATH`. To see every `java` your terminal can find, in the order it searches them, use this in PowerShell:

```powershell
where.exe java
```

or this in Git Bash, macOS or Linux:

```bash
which -a java
```

Move the JDK 25 folder above the others in your `PATH`.

### 3. Where to put your code

Each guide uses its own folder under `workspace/java-basics/`, next to the other tutorials' projects, for example `workspace/java-basics/records/`. Each guide's first step creates it.

You can use any text editor. One that colours Java code (IntelliJ IDEA, VS Code, Notepad++) makes typing easier, but every command runs in a plain terminal.
