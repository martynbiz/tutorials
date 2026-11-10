# Records

> Written for **Java 25**. Uses only the JDK: no build tool and no IDE. See the [Java basics README](README.md) for setup.

## What you'll learn

- What a **record** is, and the code Java writes for you when you declare one
- How records are deliberately limited: final fields, no setters, no extra instance fields, no subclasses
- How to **validate** and **normalise** values in a **compact constructor**, and why normalising matters for `equals`
- How to add static members, your own methods, a custom `toString` and extra constructors
- Why a record is only **shallowly** immutable, and how to fix that for a `List`
- How records behave in a `HashSet`, as `HashMap` keys, and when sorting

By the end you'll have a `Person` record that refuses bad data, tidies up what it's given, and works properly in collections.

---

## Why records

A lot of classes exist only to hold a few values together: a name and an email, a street and a postcode, a date and an amount. Written properly, even a two-field class like that is surprisingly long:

```java
import java.util.Objects;

public final class Person {

    private final String name;
    private final String email;

    public Person(String name, String email) {
        this.name = name;
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Person other = (Person) o;
        return Objects.equals(name, other.name) && Objects.equals(email, other.email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, email);
    }

    @Override
    public String toString() {
        return "Person[name=" + name + ", email=" + email + "]";
    }
}
```

That's more than 40 lines to say "a person is a name and an email". Most of it is **boilerplate**: code that follows a pattern and carries no ideas of its own. It's also easy to get wrong. Add a third field and forget to add it to `equals`, and two different people are suddenly "equal", with nothing to warn you.

A **record** (Java 16+) says the same thing in one line:

```java
public record Person(String name, String email) {
}
```

From that line, the compiler writes the private final fields, the constructor, the accessor methods, `equals`, `hashCode` and `toString` for you, and it keeps them in step with the fields for ever.

A record is still a class. You can add methods, static members, extra constructors and validation, and you'll do all of that in this guide. What it gives up is change: once a record is built, its values are fixed. That suits anything that is simply a value, such as an amount, an address, a date range, or the data sent in a request.

---

## Steps

### 1. Set up a folder

Check your setup first ([Java basics README](README.md#before-you-start)): `java -version` should report version 25.

Then, from the `tutorials` folder, create a folder for this guide and go into it.

PowerShell:

```powershell
mkdir workspace\java-basics\records
cd workspace\java-basics\records
```

Git Bash, macOS or Linux:

```bash
mkdir -p workspace/java-basics/records
cd workspace/java-basics/records
```

Every command in this guide runs from that folder. You'll have two files in it: `Person.java`, which grows step by step, and `Main.java`, which you'll replace each time to try the latest feature.

Use any text editor. IntelliJ IDEA, VS Code or Notepad++ all colour Java code, which helps, but you won't need any of their build features.

### 2. Your first record

Create `Person.java`:

```java
public record Person(String name, String email) {
}
```

The part in brackets is the record's **header**. Each entry, such as `String name`, is a **component**. The components *are* the record's data. Everything else is generated from them.

Now create `Main.java`:

```java
public class Main {
    public static void main(String[] args) {
        Person alice = new Person("Alice", "alice@example.com");

        System.out.println(alice.name());
        System.out.println(alice.email());
        System.out.println(alice);

        Person sameAlice = new Person("Alice", "alice@example.com");
        System.out.println(alice == sameAlice);
        System.out.println(alice.equals(sameAlice));
        System.out.println(alice.hashCode() == sameAlice.hashCode());
    }
}
```

Run it:

```bash
java Main.java
```

```
Alice
alice@example.com
Person[name=Alice, email=alice@example.com]
false
true
true
```

You never wrote `name()`, `email()`, `toString()`, `equals` or `hashCode`, yet they all work:

- **`alice.name()`** reads the `name` component. The method is called `name()`, **not `getName()`**. Record accessors are named exactly after their components.
- **`toString()`** prints the record's name and every component, which makes records easy to debug.
- **`alice == sameAlice` is `false`**: `==` asks "is this the same object?", and `new` made two objects.
- **`alice.equals(sameAlice)` is `true`**: a record's `equals` compares the components. Two records with equal components are equal, because a record is its values.
- **The hash codes match.** Equal records always have equal hash codes, which is what `HashSet` and `HashMap` rely on (step 11).

> **How does `java Main.java` find `Person`?** This is **source-file mode**. `java` compiles `Main.java` in memory and runs it, without writing any `.class` files. Since Java 22 it also finds and compiles other `.java` files in the same folder when `Main` uses them, so `Person.java` is picked up automatically.

### 3. See what the compiler wrote

You don't have to take the "generated for you" claim on trust. `javac` compiles to real `.class` files, and `javap` lists what's inside one.

Compile `Person.java` into a separate folder called `out`, so it doesn't mix with your source files:

```bash
javac -d out Person.java
javap -p out/Person.class
```

```
Compiled from "Person.java"
public final class Person extends java.lang.Record {
  private final java.lang.String name;
  private final java.lang.String email;
  public Person(java.lang.String, java.lang.String);
  public final java.lang.String toString();
  public final int hashCode();
  public final boolean equals(java.lang.Object);
  public java.lang.String name();
  public java.lang.String email();
}
```

(`-p` means "include private members". Without it, you wouldn't see the fields.)

That's the hand-written class from **Why records**, almost line for line:

- **`final class`**: nothing can extend a record.
- **`extends java.lang.Record`**: every record extends this class automatically, which is why a record can't extend anything else.
- **`private final` fields**, one per component. Private, so other classes can only read them through the accessors. Final, so they can't change after the constructor.
- **One constructor taking every component, in order.** It's called the **canonical constructor**. You'll customise it from step 6.
- **`toString`, `hashCode`, `equals`**, and **one accessor per component**.

Delete the `out` folder afterwards. It was only for looking inside. PowerShell:

```powershell
Remove-Item -Recurse out
```

Git Bash, macOS or Linux:

```bash
rm -r out
```

### 4. Experiment in JShell

For quick questions such as "what does this method return?", writing a `Main` each time is slow. **JShell** is Java's interactive shell: type a line of Java, press Enter, see the result.

Start it with your record loaded:

```bash
jshell Person.java
```

Then type each of these lines (you don't need semicolons in JShell):

```
var alice = new Person("Alice", "alice@example.com")
alice.name()
alice.getName()
alice.equals(new Person("Alice", "alice@example.com"))
alice.name = "Bob"
```

```
jshell> var alice = new Person("Alice", "alice@example.com")
alice ==> Person[name=Alice, email=alice@example.com]

jshell> alice.name()
$3 ==> "Alice"

jshell> alice.getName()
|  Error:
|  cannot find symbol
|    symbol:   method getName()
|  alice.getName()
|  ^-----------^

jshell> alice.equals(new Person("Alice", "alice@example.com"))
$4 ==> true

jshell> alice.name = "Bob"
|  Error:
|  name has private access in Person
|  alice.name = "Bob"
|  ^--------^
```

- JShell prints each result with a name such as `$3`, so you could use it in a later line. The numbers you see may differ.
- **`getName()` doesn't exist.** If you're used to JavaBeans-style classes, this is the mistake you'll make most often with records.
- **The field is private**, so code outside the record can't even try to change it.

Type `/exit` to leave JShell. If you edit `Person.java`, restart JShell to load the new version.

### 5. What a record won't let you do

Records are limited on purpose. Each limit protects the promise that **a record is exactly its components, and they never change**. See each one for yourself: make the change to `Person.java`, run `java Main.java`, read the error, then **undo the change** before trying the next.

**A setter:**

```java
public record Person(String name, String email) {
    public void setName(String name) {
        this.name = name;
    }
}
```

```
Person.java:3: error: cannot assign a value to final variable name
        this.name = name;
            ^
1 error
error: compilation failed
```

**An extra instance field:**

```java
public record Person(String name, String email) {
    private int age;
}
```

```
Person.java:2: error: field declaration must be static
    private int age;
                ^
  (consider replacing field with record component)
```

If a person needs an age, it's part of the data, so it belongs in the header as a component: `record Person(String name, String email, int age)`. An extra field would be state that `equals`, `hashCode` and `toString` know nothing about. That's exactly the bug records exist to prevent.

**Extending another class:**

```java
public record Person(String name, String email) extends Object {
}
```

```
Person.java:1: error: '{' expected
public record Person(String name, String email) extends Object {
                                               ^
```

The error is unhelpful, but the rule is simple: a record already extends `java.lang.Record`, and Java classes can only extend one class. It works the other way too. A record is `final`, so a class that tries `extends Person` gets `cannot inherit from final Person`.

(The real error messages start with the full path to `Person.java`. They're shortened here.)

What a record *can* do is everything else a class can: static fields and methods, instance methods, extra constructors, and implementing interfaces. The rest of this guide adds all of those.

### 6. Validate in a compact constructor

Right now `Person` accepts anything: a blank name, a `null` email, an "email" with no `@`. A record should refuse to exist with bad data, so that every `Person` anywhere in a program is known to be valid.

You check values in a constructor, as in any class. Records have a short form for this, the **compact constructor**. Replace `Person.java` with:

```java
import java.util.Objects;

public record Person(String name, String email) {

    public Person {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(email, "email must not be null");
        requireNotBlank(name, "name");
        if (!email.contains("@")) {
            throw new IllegalArgumentException("email must contain @, but was: " + email);
        }
    }

    private static void requireNotBlank(String value, String field) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
```

- **`public Person { … }`** has no brackets and no parameter list. It's the canonical constructor with the boring parts left out. The parameters are the components (`name`, `email`), and after your code runs, Java assigns them to the fields for you.
- **`Objects.requireNonNull(value, message)`** throws a `NullPointerException` with your message if the value is `null`, and does nothing otherwise. The `null` checks come first, so the later checks can safely call methods on `name` and `email`.
- **Throwing an exception is how a constructor says no.** If any check fails, no `Person` is created at all.
- **`requireNotBlank` is a private helper.** A record can have private methods, like any class. It's `static` because it doesn't need a `Person`, only the `String` it's checking.

Replace `Main.java` to try good and bad values:

```java
public class Main {
    public static void main(String[] args) {
        System.out.println(new Person("Alice", "alice@example.com"));

        try {
            new Person("   ", "nobody@example.com");
        } catch (IllegalArgumentException e) {
            System.out.println("Refused: " + e.getMessage());
        }

        try {
            new Person("Bob", "bob.example.com");
        } catch (IllegalArgumentException e) {
            System.out.println("Refused: " + e.getMessage());
        }

        try {
            new Person("Carol", null);
        } catch (NullPointerException e) {
            System.out.println("Refused: " + e.getMessage());
        }
    }
}
```

```
Person[name=Alice, email=alice@example.com]
Refused: name must not be blank
Refused: email must contain @, but was: bob.example.com
Refused: email must not be null
```

> **Real email validation** is far more complicated than "contains an `@`", and the only real test is sending an email to the address. The check here is just enough to show the idea.

### 7. Normalise in the compact constructor

People type their details in all sorts of ways. Replace `Main.java`:

```java
public class Main {
    public static void main(String[] args) {
        Person typed = new Person("  Alice ", "Alice@Example.COM");
        Person stored = new Person("Alice", "alice@example.com");

        System.out.println(typed);
        System.out.println(typed.equals(stored));
    }
}
```

```
Person[name=  Alice , email=Alice@Example.COM]
false
```

Same person, same inbox (email addresses are, in practice, not case-sensitive), but the generated `equals` compares the strings exactly, so it says `false`. Store both, and you'd have a duplicate customer.

The fix is to **normalise** the values: put them into one standard form before they're stored. A compact constructor can do that too, because its parameters can be **reassigned**. Whatever value a parameter holds at the end is what goes into the field. In `Person.java`, add the `Locale` import and two lines after the `null` checks:

```java
import java.util.Locale;
import java.util.Objects;

public record Person(String name, String email) {

    public Person {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(email, "email must not be null");
        name = name.strip();
        email = email.strip().toLowerCase(Locale.ROOT);
        requireNotBlank(name, "name");
        if (!email.contains("@")) {
            throw new IllegalArgumentException("email must contain @, but was: " + email);
        }
    }

    private static void requireNotBlank(String value, String field) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
```

Run it again:

```
Person[name=Alice, email=alice@example.com]
true
```

- **`name = name.strip();`** reassigns the *parameter*, not the field. That's allowed, and it's the whole trick. Writing `this.name = …` in a compact constructor is a compile error (`cannot assign a value to final variable name`), because Java assigns the fields itself at the end.
- **`strip()`** removes spaces (and other whitespace) from both ends.
- **`toLowerCase(Locale.ROOT)`**: plain `toLowerCase()` uses the computer's language settings, and in a few languages that changes some letters unexpectedly. In Turkish, for example, `"TITLE".toLowerCase()` gives `"tıtle"`, with a dotless ı. `Locale.ROOT` means "no particular language", so the result is the same on every machine. The same goes for its partner, `toUpperCase(Locale.ROOT)`, when you want capitals.
- **Order matters.** Strip first, then check: `"   "` becomes `""`, which `requireNotBlank` refuses.

Normalising in the constructor means **every** `Person` is normalised, however it was created, so `equals` and `hashCode` just work. You'll see the benefit again in step 11.

> **The long way.** The compact constructor is shorthand for the full canonical constructor. Written out in full, which you're also allowed to do, the same constructor looks like this:
>
> ```java
> public Person(String name, String email) {
>     Objects.requireNonNull(name, "name must not be null");
>     Objects.requireNonNull(email, "email must not be null");
>     String strippedName = name.strip();
>     String normalisedEmail = email.strip().toLowerCase(Locale.ROOT);
>     requireNotBlank(strippedName, "name");
>     if (!normalisedEmail.contains("@")) {
>         throw new IllegalArgumentException("email must contain @, but was: " + normalisedEmail);
>     }
>     this.name = strippedName;
>     this.email = normalisedEmail;
> }
> ```
>
> Here *you* assign every field, and the compiler checks you haven't missed one. A record can have one or the other, not both (`constructor Person(String,String) is already defined in record Person`). The compact form is shorter and can't forget a field, so use it unless you have a reason not to.

### 8. Static members

A record can't have extra *instance* fields, but **static** fields and methods are fine. They belong to the record type, not to any one `Person`, so they don't affect `equals` or the record's data.

Add a constant and a factory method to `Person.java`. The record now looks like this:

```java
import java.util.Locale;
import java.util.Objects;

public record Person(String name, String email) {

    public static final int MAX_NAME_LENGTH = 100;

    public Person {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(email, "email must not be null");
        name = name.strip();
        email = email.strip().toLowerCase(Locale.ROOT);
        requireNotBlank(name, "name");
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        if (!email.contains("@")) {
            throw new IllegalArgumentException("email must contain @, but was: " + email);
        }
    }

    /** Reads text in the form "Alice <alice@example.com>", as an email client shows it. */
    public static Person parse(String text) {
        int open = text.indexOf('<');
        int close = text.indexOf('>');
        if (open < 0 || close < open) {
            throw new IllegalArgumentException("expected Name <email>, but was: " + text);
        }
        return new Person(text.substring(0, open), text.substring(open + 1, close));
    }

    private static void requireNotBlank(String value, String field) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
```

- **`MAX_NAME_LENGTH`** is a constant: one value shared by the whole program, and usable from outside as `Person.MAX_NAME_LENGTH`.
- **`parse` is a static factory**: a static method that builds an instance. Compared with a constructor, it can have a name that says what it does, and it can do work first, here splitting the text. It still ends by calling the constructor, so a parsed `Person` gets exactly the same validation and normalisation as any other. There's no way round the checks.
- `text.substring(0, open)` for `"Alice Smith <…>"` is `"Alice Smith "`, with a trailing space. The constructor's `strip()` tidies it, so `parse` doesn't need to.

Replace `Main.java`:

```java
public class Main {
    public static void main(String[] args) {
        Person alice = Person.parse("Alice Smith <Alice.Smith@Example.com>");
        System.out.println(alice);
        System.out.println(alice.name().length());

        try {
            new Person("x".repeat(Person.MAX_NAME_LENGTH + 1), "x@example.com");
        } catch (IllegalArgumentException e) {
            System.out.println("Refused: " + e.getMessage());
        }

        try {
            Person.parse("Alice Smith alice@example.com");
        } catch (IllegalArgumentException e) {
            System.out.println("Refused: " + e.getMessage());
        }
    }
}
```

```
Person[name=Alice Smith, email=alice.smith@example.com]
11
Refused: name must be at most 100 characters
Refused: expected Name <email>, but was: Alice Smith alice@example.com
```

The name is 11 characters, `Alice Smith`, so the trailing space really has gone.

### 9. Methods of your own, and a better `toString`

A record isn't just data. It can have behaviour too, as long as that behaviour doesn't change the record. Add these methods to `Person.java`, just above `requireNotBlank`:

```java
    /** A copy of this person with a different email. This person is unchanged. */
    public Person withEmail(String newEmail) {
        return new Person(name, newEmail);
    }

    /** The part of the email after the @, such as "example.com". */
    public String emailDomain() {
        return email.substring(email.indexOf('@') + 1);
    }

    public boolean hasSameEmailAs(Person other) {
        return email.equals(other.email);
    }

    @Override
    public String toString() {
        return name + " <" + email + ">";
    }
```

- **`withEmail` doesn't change anything.** A record can't change, so "changing" one means building a new one with one value different. The original is untouched. Methods like this are often called **withers**, from their `with…` names. Because `withEmail` calls the constructor, the new email is validated and normalised too.
- **Inside the record, use the fields directly.** `name` and `email` are the private fields. Calling `name()` would work too, but there's no need.
- **`other.email`**: private means "private to the `Person` type", not "private to this object", so a `Person` can read another `Person`'s private fields. Outside the record, you'd have to write `other.email()`.
- **Overriding `toString`** works exactly as in any class. The generated version is fine for debugging. This one prints the same form `parse` reads, which is neater for people. You can override `equals` and `hashCode` too, but you rarely should: the generated ones are correct, and keep themselves up to date.

Replace `Main.java`:

```java
public class Main {
    public static void main(String[] args) {
        Person alice = new Person("Alice", "alice@example.com");
        Person moved = alice.withEmail("  Alice@NewJob.org ");

        System.out.println(alice);
        System.out.println(moved);
        System.out.println(moved.emailDomain());

        Person bob = new Person("Bob", "Alice@example.com");
        System.out.println(alice.hasSameEmailAs(bob));
        System.out.println(alice.equals(bob));

        try {
            alice.withEmail("not an email");
        } catch (IllegalArgumentException e) {
            System.out.println("Refused: " + e.getMessage());
        }

        Person copy = Person.parse(alice.toString());
        System.out.println(copy.equals(alice));
    }
}
```

```
Alice <alice@example.com>
Alice <alice@newjob.org>
newjob.org
true
false
Refused: email must contain @, but was: not an email
true
```

- `alice` still has her old email after `withEmail`.
- Bob shares Alice's inbox, but he isn't Alice: same email, different name, so `equals` is `false`.
- The last line is a **round trip**: `toString` writes a `Person` as text, `parse` reads it back, and the result equals the original.

### 10. A list component, and an extra constructor

A person can have nicknames. Add a component of type `List<String>`. Change the header and the `null` checks, and add an import:

```java
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record Person(String name, String email, List<String> nicknames) {

    public static final int MAX_NAME_LENGTH = 100;

    public Person {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(nicknames, "nicknames must not be null");
        name = name.strip();
        email = email.strip().toLowerCase(Locale.ROOT);
        requireNotBlank(name, "name");
        // … the rest of the compact constructor is unchanged
    }
```

The canonical constructor now takes three values, so `new Person("Alice", "alice@example.com")` no longer compiles, and neither do `parse` and `withEmail`. Most people have no nicknames, so add a **second constructor** for them, just after the compact constructor:

```java
    /** A person with no nicknames. */
    public Person(String name, String email) {
        this(name, email, List.of());
    }
```

A record's extra constructors must start by calling another constructor with `this(...)`. In the end every path goes through the canonical one, so nothing can skip the validation. `List.of()` is an empty list.

`parse` and `withEmail` compile again, because they use the two-value constructor. That's fine for `parse`, but `withEmail` would quietly drop the person's nicknames. Make it keep them:

```java
    public Person withEmail(String newEmail) {
        return new Person(name, newEmail, nicknames);
    }
```

Now see whether a `Person` really can't change. Replace `Main.java`:

```java
import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        List<String> names = new ArrayList<>();
        names.add("Al");
        Person alice = new Person("Alice", "alice@example.com", names);
        System.out.println(alice.nicknames());

        names.add("Ally");
        System.out.println(alice.nicknames());

        try {
            alice.nicknames().add("Lissy");
        } catch (UnsupportedOperationException e) {
            System.out.println("Refused: the nicknames list can't be changed");
        }
        System.out.println(alice.nicknames());
    }
}
```

```
[Al]
[Al, Ally]
[Al, Ally, Lissy]
```

Alice's nicknames changed twice after she was created, once through the original list and once through the accessor. The record's *field* is final, so it always points to the same list. But that list is an `ArrayList`, and an `ArrayList` can change. A record is only **shallowly immutable**: its fields can't be reassigned, but the objects they point to are only as unchangeable as their own class makes them. `String` can't change, which is why steps 2 to 9 were safe. A list can.

The fix is to store a copy that can't change. Add one line to the compact constructor, after the email line:

```java
        nicknames = List.copyOf(nicknames);
```

**`List.copyOf`** returns an unmodifiable copy of the list. Changing the original afterwards doesn't affect the copy, and trying to change the copy throws `UnsupportedOperationException`. This is called a **defensive copy**. Run it again:

```
[Al]
[Al]
Refused: the nicknames list can't be changed
[Al]
```

So what if you *do* want to change a list you got from a `Person`, say to add a nickname? Make your own changeable copy. `new ArrayList<>(list)` creates a new `ArrayList` holding the same elements. Add this to the end of `main`:

```java
        List<String> mine = new ArrayList<>(alice.nicknames());
        mine.add("Lissy");
        System.out.println(mine);
        System.out.println(alice.nicknames());
```

```
[Al]
[Al]
Refused: the nicknames list can't be changed
[Al]
[Al, Lissy]
[Al]
```

`mine` changed. Alice didn't.

### 11. Records in sets and maps

`HashSet` and `HashMap` find things using `hashCode` and `equals`. With an ordinary class that doesn't override them, two objects are only "equal" if they're the same object. Records compare by value, and because `Person` normalises its values, "the same person, typed differently" counts as equal too.

Replace `Main.java`:

```java
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Main {
    public static void main(String[] args) {
        Set<Person> attendees = new HashSet<>();
        attendees.add(new Person("Alice", "alice@example.com"));
        attendees.add(Person.parse("Alice <ALICE@example.com>"));
        attendees.add(new Person("Bob", "bob@example.com"));
        System.out.println(attendees.size());

        Map<Person, String> teams = new HashMap<>();
        teams.put(new Person("Alice", "alice@example.com"), "Payments");
        System.out.println(teams.get(new Person(" Alice ", "Alice@Example.com")));

        Person withNickname = new Person("Alice", "alice@example.com", List.of("Al"));
        System.out.println(teams.get(withNickname));
    }
}
```

```
2
Payments
null
```

- **The set has 2 people, not 3.** The parsed Alice equals the first one, so the set doesn't add her again.
- **The map lookup works** with a brand-new `Person`, typed differently, because it's equal to the key.
- **`null` for the nickname version**: `equals` compares *every* component, and `[Al]` isn't `[]`. Lists are equal when they hold equal elements in the same order. When you design a record, every component is part of its identity, so only put in a component if it should be.

That's also why records make good keys, and why mutable keys are dangerous: if a key could change after it went into a `HashMap`, its hash code would change and the map would no longer find it. A record's components can't change (shallowly, and `List.copyOf` closes that gap), so its hash code can't change either.

### 12. Implementing an interface

A record can implement interfaces, just like a class. To make people sortable, implement `Comparable<Person>`, which says how two people compare. Change the header:

```java
public record Person(String name, String email, List<String> nicknames) implements Comparable<Person> {
```

and add `compareTo`, above `toString`:

```java
    /** Sorts by name, then by email when two names are the same. */
    @Override
    public int compareTo(Person other) {
        int byName = name.compareTo(other.name);
        return byName != 0 ? byName : email.compareTo(other.email);
    }
```

`compareTo` returns a negative number if this person comes first, a positive number if `other` does, and `0` if they're the same for sorting. `String.compareTo` already compares text alphabetically, so this method compares names, and only looks at the emails when the names are the same.

Replace `Main.java`:

```java
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        List<Person> people = new ArrayList<>();
        people.add(new Person("Carol", "carol@example.com"));
        people.add(new Person("Alice", "alice@work.org"));
        people.add(new Person("Bob", "bob@example.com"));
        people.add(new Person("Alice", "alice@example.com"));

        Collections.sort(people);
        for (Person person : people) {
            System.out.println(person);
        }
    }
}
```

```
Alice <alice@example.com>
Alice <alice@work.org>
Bob <bob@example.com>
Carol <carol@example.com>
```

`Collections.sort` sorts a list in place, using `compareTo`. The two Alices are ordered by email.

Your finished `Person.java`:

```java
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record Person(String name, String email, List<String> nicknames) implements Comparable<Person> {

    public static final int MAX_NAME_LENGTH = 100;

    public Person {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(nicknames, "nicknames must not be null");
        name = name.strip();
        email = email.strip().toLowerCase(Locale.ROOT);
        nicknames = List.copyOf(nicknames);
        requireNotBlank(name, "name");
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        if (!email.contains("@")) {
            throw new IllegalArgumentException("email must contain @, but was: " + email);
        }
    }

    /** A person with no nicknames. */
    public Person(String name, String email) {
        this(name, email, List.of());
    }

    /** Reads text in the form "Alice <alice@example.com>", as an email client shows it. */
    public static Person parse(String text) {
        int open = text.indexOf('<');
        int close = text.indexOf('>');
        if (open < 0 || close < open) {
            throw new IllegalArgumentException("expected Name <email>, but was: " + text);
        }
        return new Person(text.substring(0, open), text.substring(open + 1, close));
    }

    /** A copy of this person with a different email. This person is unchanged. */
    public Person withEmail(String newEmail) {
        return new Person(name, newEmail, nicknames);
    }

    /** The part of the email after the @, such as "example.com". */
    public String emailDomain() {
        return email.substring(email.indexOf('@') + 1);
    }

    public boolean hasSameEmailAs(Person other) {
        return email.equals(other.email);
    }

    /** Sorts by name, then by email when two names are the same. */
    @Override
    public int compareTo(Person other) {
        int byName = name.compareTo(other.name);
        return byName != 0 ? byName : email.compareTo(other.email);
    }

    @Override
    public String toString() {
        return name + " <" + email + ">";
    }

    private static void requireNotBlank(String value, String field) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
```

---

## Your turn

### Exercise 1: an `Address` record

Create `Address.java`: a record with three `String` components, `street`, `city` and `postcode`.

1. Refuse `null` and blank values for all three, with messages such as `city must not be blank`.
2. Strip all three, and store the postcode in capitals, so `" g1 1aa"` becomes `"G1 1AA"`.
3. Add `withCity(String newCity)`, returning a copy in a different city.
4. Override `toString` to print `1 High Street, Glasgow G1 1AA`.

In `Main`, show that `new Address("1 High Street ", "Glasgow", "g1 1aa")` equals `new Address("1 High Street", " Glasgow", " G1 1AA ")`, that `withCity` leaves the original unchanged, and that a blank city is refused.

<details>
<summary>Hint</summary>

Every part of this has a twin in `Person`: the `null` checks and `requireNotBlank` from step 6, `strip` from step 7, `withEmail` and `toString` from step 9. For capitals, use `toUpperCase(Locale.ROOT)` (step 7).

</details>

<details>
<summary>Solution</summary>

`Address.java`:

```java
import java.util.Locale;
import java.util.Objects;

public record Address(String street, String city, String postcode) {

    public Address {
        Objects.requireNonNull(street, "street must not be null");
        Objects.requireNonNull(city, "city must not be null");
        Objects.requireNonNull(postcode, "postcode must not be null");
        street = street.strip();
        city = city.strip();
        postcode = postcode.strip().toUpperCase(Locale.ROOT);
        requireNotBlank(street, "street");
        requireNotBlank(city, "city");
        requireNotBlank(postcode, "postcode");
    }

    /** A copy of this address in a different city. This address is unchanged. */
    public Address withCity(String newCity) {
        return new Address(street, newCity, postcode);
    }

    @Override
    public String toString() {
        return street + ", " + city + " " + postcode;
    }

    private static void requireNotBlank(String value, String field) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
```

`Main.java`:

```java
public class Main {
    public static void main(String[] args) {
        Address home = new Address("1 High Street ", "Glasgow", "g1 1aa");
        Address typed = new Address("1 High Street", " Glasgow", " G1 1AA ");
        System.out.println(home);
        System.out.println(home.equals(typed));
        System.out.println(home.withCity("Edinburgh"));
        System.out.println(home);

        try {
            new Address("1 High Street", "  ", "G1 1AA");
        } catch (IllegalArgumentException e) {
            System.out.println("Refused: " + e.getMessage());
        }
    }
}
```

```
1 High Street, Glasgow G1 1AA
true
1 High Street, Edinburgh G1 1AA
1 High Street, Glasgow G1 1AA
Refused: city must not be blank
```

`requireNotBlank` is now copied into two records. In a bigger program you'd move it to a shared helper class. Here, keeping each record self-contained is simpler.

</details>

### Exercise 2: add a nickname

Add `withNickname(String nickname)` to `Person`. It returns a copy of the person with one more nickname on the end, and leaves the original unchanged.

Show it in `Main` by giving Alice two nicknames, one at a time, and printing her nicknames at each stage.

<details>
<summary>Hint</summary>

`nicknames` can't be changed (step 10), so make a changeable copy with `new ArrayList<>(...)`, add to that, and build a new `Person` from it. You don't need to worry about the copy being changeable afterwards: the compact constructor copies it again with `List.copyOf`.

</details>

<details>
<summary>Solution</summary>

In `Person.java` (import `java.util.ArrayList`):

```java
    /** A copy of this person with one more nickname. This person is unchanged. */
    public Person withNickname(String nickname) {
        List<String> more = new ArrayList<>(nicknames);
        more.add(nickname);
        return new Person(name, email, more);
    }
```

In `Main.java`:

```java
public class Main {
    public static void main(String[] args) {
        Person alice = new Person("Alice", "alice@example.com");
        Person al = alice.withNickname("Al");
        Person ally = al.withNickname("Ally");
        System.out.println(alice.nicknames());
        System.out.println(al.nicknames());
        System.out.println(ally.nicknames());
    }
}
```

```
[]
[Al]
[Al, Ally]
```

Three `Person` values, and none of them changed. Each wither built a new one.

</details>

### Exercise 3: predict, then run

Without running it, decide what this prints:

```java
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Main {
    public static void main(String[] args) {
        Set<Person> set = new HashSet<>();
        set.add(new Person("Alice", "alice@example.com"));
        set.add(new Person("Alice", "alice@example.com", List.of()));
        set.add(new Person("alice", "alice@example.com"));
        set.add(new Person("Alice", "ALICE@example.com", List.of("Al")));
        System.out.println(set.size());
    }
}
```

Then run it and explain each line.

<details>
<summary>Solution</summary>

```
3
```

- **Lines 1 and 2 are equal.** The two-value constructor passes `List.of()`, so both have the same name, email and (empty) nicknames. The set keeps one.
- **Line 3 is different.** The email is normalised, but the name is only stripped, not lower-cased, so `alice` isn't `Alice`. That's a choice `Person` made: lower-casing names would turn `McDonald` into `mcdonald`.
- **Line 4 is different.** The email normalises to the same address, but the nicknames `[Al]` don't equal `[]`, and every component counts.

</details>

---

## Common errors

**`error: can't find main(String[]) or main() method in class: Person`**
You ran `java Person.java`. Run the class that has the `main` method: `java Main.java`.

**`Error: Could not find or load main class Main.java`** with **`Caused by: java.lang.ClassNotFoundException: Main.java`**
You're not in the folder that contains `Main.java`, or the file is named differently. Check with `ls` (or `dir`), then `cd` to the right folder.

**`error: cannot find symbol` pointing at `Person`, when `Person.java` is right there**
Your `java` is older than 22, which can only launch a single source file. Check `java -version`. If an older JDK is first on your `PATH`, see the [README](README.md#before-you-start).

**`error: cannot find symbol` … `symbol: method getName()`**
Record accessors are named after the component: `name()`, not `getName()`.

**`error: cannot assign a value to final variable name`**
You assigned to a field: in a setter, in a method, or with `this.name = …` inside a compact constructor. A record's fields can't change. In a compact constructor, assign the *parameter* instead (`name = name.strip();`). Everywhere else, return a new record (a wither).

**`error: field declaration must be static`** with **`(consider replacing field with record component)`**
You added an instance field to a record. If it's data, make it a component in the header. If it's a constant, make it `static final`.

**`error: constructor is not canonical, so it must invoke another constructor of class Person`**
An extra constructor must start with `this(...)`, ending up at the canonical constructor. That's how a record makes sure all its values go through one place.

**`error: constructor Person(String,String) is already defined in record Person`**
You wrote both a compact constructor and the full canonical constructor. They're the same constructor, written two ways. Keep one.

**`error: invalid accessor method in record Person`** with **`(accessor method must be public)`**
You wrote your own version of an accessor, such as `String name()`, without `public`. You're allowed to override an accessor, but it must be `public`, like the one it replaces.

**`error: cannot inherit from final Person`**
A class tried to extend a record. Records are always `final`.

**`'{' expected`** after the record header
You wrote `extends` on a record. Records already extend `java.lang.Record` and can't extend anything else. They *can* `implement` interfaces.

**A `Person` changed after it was created**
A component points to a mutable object, such as an `ArrayList`. Make a defensive copy in the compact constructor (`List.copyOf`). The record's fields are final, but the objects they point to are only as immutable as their own class.

---

## Recap

- **A record is a class whose components *are* its data.** From the header alone, Java generates private final fields, a canonical constructor, accessors (`name()`, not `getName()`), `equals`, `hashCode` and `toString`.
- **Records are limited on purpose:** no setters, no extra instance fields, no `extends`, and they're `final`. Everything else a class can do, a record can do too.
- **The compact constructor** is where you validate (throw to refuse) and normalise (reassign the parameters). Every way of making a record, including factories, withers and extra constructors, goes through it.
- **Normalising** puts values into one standard form, so `equals` and `hashCode` treat "the same thing, typed differently" as equal.
- **"Changing" a record means making a new one**, usually with a `with…` method.
- **Records are only shallowly immutable.** Defensively copy mutable components, such as with `List.copyOf`.
- **Value-based `equals` and `hashCode`** make records ideal for sets and map keys. Every component counts, so choose them carefully.

### Interview angle

<details>
<summary>"What do you get when you declare a record?"</summary>

A final class extending `java.lang.Record`. It has a private final field and a public accessor for each component, a canonical constructor taking every component in order, and `equals`, `hashCode` and `toString` based on all the components. You can add a compact constructor for validation, static members, instance methods, extra constructors (which must delegate with `this(...)`), and interfaces. You can't add instance fields or extend another class.

</details>

<details>
<summary>"Are records immutable?"</summary>

Shallowly. The fields are final, so they can't be reassigned, but if a component is a mutable object, such as an `ArrayList` or an array, its contents can still change, through the caller's reference or through the accessor. The standard fix is a defensive copy in the compact constructor, for example `List.copyOf`, which also makes the stored list unmodifiable.

</details>

<details>
<summary>"Records or Lombok's `@Value`?"</summary>

Both remove boilerplate for immutable value classes. Records are part of the language: no library, no annotation processor, and every tool understands them. Lombok's `@Value` gives you JavaBeans-style `getX()` getters, which some older frameworks expect, and it can generate builders (with `@Builder`) and do some things records can't, such as extending a class. For new code on a modern JDK, records are the default choice. Lombok is mostly found in codebases from before Java 16, and in classes that records can't model.

</details>

<details>
<summary>"Can a JPA entity be a record?"</summary>

No. JPA entities need a no-argument constructor and fields that the persistence provider can set after construction, and providers such as Hibernate often subclass entities to create lazy-loading proxies. All of that conflicts with records being final and immutable. Records are a good fit around entities, though: request and response objects (DTOs), query results (projections), and, in recent Hibernate versions, embeddable value types such as an address.

</details>

<details>
<summary>"Why does a record's `equals` matter in a `HashMap`?"</summary>

`HashMap` finds a key by its `hashCode`, then confirms with `equals`. Records generate both from all their components, consistently, so two equal records always find the same entry. And because the components can't change, a key's hash code can't change while it's in the map, which would otherwise make the entry impossible to find.

</details>

### Further reading

- [JEP 395: Records](https://openjdk.org/jeps/395): the proposal that added records in Java 16, including the reasons behind each restriction
- [Oracle: Record Classes](https://docs.oracle.com/en/java/javase/25/language/records.html): the official guide
- [`java.lang.Record` Javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Record.html): the exact contract for the generated `equals`, `hashCode` and `toString`
- [JEP 458: Launch Multi-File Source-Code Programs](https://openjdk.org/jeps/458): how `java Main.java` finds `Person.java`
