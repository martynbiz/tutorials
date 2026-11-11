# Interfaces

> Written for **Java 25**. Uses only the JDK: no build tool and no IDE. See the [Java basics README](README.md) for setup.

## What you'll learn

- What an **interface** is, how a class **implements** one, and why `@Override` is worth writing
- How to write code against an interface, so you can swap one implementation for another without changing it
- How a class can implement several interfaces, and how interfaces extend each other
- What interfaces can contain besides abstract methods: **constants**, **default methods**, **static methods** and **private methods**
- What happens when two interfaces give a class the same default method, and how to choose between them
- What a **functional interface** is, and how a **lambda** implements one in a single line

By the end you'll have an `OrderService` that tells customers about their orders by email, by text or on the console, without knowing or caring which.

---

## Why interfaces

An online shop has to tell customers when they've ordered something. The first version sends an email:

```java
public class OrderService {

    private final EmailNotifier email = new EmailNotifier();

    public void placeOrder(String customer, String item) {
        email.send(customer, "Thanks for ordering " + item);
    }
}
```

Then the shop wants to send texts as well, for customers who prefer them. Now `OrderService` needs an `SmsNotifier` field too, and some way to choose: a `String channel` parameter, perhaps, and an `if`. A push notification channel adds another field and another branch. For testing on your own machine, you want messages printed instead of sent, which adds another. Every new way of sending a message means editing `OrderService`, even though taking orders hasn't changed at all.

The problem is that `OrderService` depends on **how** messages are sent. All it needs to know is **that** they can be: "give me something I can call `send` on". That "something" is an **interface**:

```java
public interface Notifier {
    void send(String recipient, String message);
}
```

An interface is a **contract**. It lists methods, usually without any code, and any class that **implements** the interface promises to provide them. `OrderService` asks for "a `Notifier`" and calls `send`, and any class that keeps the promise will do: email, SMS, push, or one that just prints. Adding a channel means writing one new class. `OrderService` doesn't change.

You've used interfaces already. In `List<String> names = new ArrayList<>();`, `List` is an interface and `ArrayList` is one class that implements it. Code that takes a `List` works just as well with a `LinkedList`, or with the unchangeable lists that `List.of(...)` returns.

> **Interfaces vs abstract classes.** An abstract class can also declare methods without code for subclasses to fill in. The differences:
>
> | | Interface | Abstract class |
> |---|---|---|
> | How many can a class have? | As many as it likes | One (a class extends only one class) |
> | Instance fields (state)? | No, only constants | Yes |
> | Constructors? | No | Yes |
> | Says… | "can do this" | "is a kind of this" |
>
> Use an interface to describe what something can do. Reach for an abstract class only when the implementations genuinely share state or constructor logic. Most modern Java code uses interfaces far more often.

---

## Steps

### 1. Set up a folder

Check your setup first ([Java basics README](README.md#before-you-start)): `java -version` should report version 25.

Then, from the `tutorials` folder, create a folder for this guide and go into it.

PowerShell:

```powershell
mkdir workspace\java-basics\interfaces
cd workspace\java-basics\interfaces
```

Git Bash, macOS or Linux:

```bash
mkdir -p workspace/java-basics/interfaces
cd workspace/java-basics/interfaces
```

Every command in this guide runs from that folder. Each class and interface goes in its own file, named after it, such as `Notifier.java`. `Main.java` gets replaced at each step to try the latest feature, and `java Main.java` compiles everything it uses from the same folder automatically.

### 2. Your first interface

Create `Notifier.java`:

```java
/** Something that can send a message to someone. */
public interface Notifier {

    void send(String recipient, String message);
}
```

`send` has no body, just a semicolon. It's an **abstract method**: it says what can be done, not how.

Now a class that implements it. Create `EmailNotifier.java`:

```java
public class EmailNotifier implements Notifier {

    @Override
    public void send(String recipient, String message) {
        System.out.println("EMAIL to " + recipient + ": " + message);
    }
}
```

- **`implements Notifier`** is the promise. The compiler holds the class to it: leave `send` out and it won't compile (`EmailNotifier is not abstract and does not override abstract method send(String,String) in Notifier`).
- **`public` is required.** Every method in an interface is automatically `public`, and an implementation can't be less visible than the method it implements. Leave `public` off and you get `attempting to assign weaker access privileges; was public`.
- **`@Override`** asks the compiler to check that this method really does implement something. If you mistype the name as `sendd`, you get a clear `method does not override or implement a method from a supertype` pointing at the mistake, instead of a confusing error about `send` being missing. It's optional, but always write it.

A real `EmailNotifier` would talk to a mail server. Printing keeps this guide about interfaces.

Create `Main.java`:

```java
public class Main {
    public static void main(String[] args) {
        EmailNotifier email = new EmailNotifier();
        email.send("alice@example.com", "Your order has shipped");
    }
}
```

```bash
java Main.java
```

```
EMAIL to alice@example.com: Your order has shipped
```

To see what the compiler makes of the interface, compile it into a separate `out` folder and look inside with `javap`:

```bash
javac -d out Notifier.java
javap -p out/Notifier.class
```

```
Compiled from "Notifier.java"
public interface Notifier {
  public abstract void send(java.lang.String, java.lang.String);
}
```

You wrote `void send(…)`, and the compiler treats it as `public abstract void send(…)`. That's why the implementation had to be `public`. You *can* write `public abstract` yourself, but it's redundant, so nobody does.

Delete the `out` folder afterwards: `Remove-Item -Recurse out` in PowerShell, or `rm -r out` in Git Bash, macOS or Linux.

### 3. A second implementation, and the interface as a type

Create `SmsNotifier.java`:

```java
public class SmsNotifier implements Notifier {

    @Override
    public void send(String recipient, String message) {
        System.out.println("SMS to " + recipient + ": " + message);
    }
}
```

An interface is a **type**, just like a class. You can declare a variable, a parameter or a list of type `Notifier`, and put any implementation in it. Replace `Main.java`:

```java
import java.util.List;

public class Main {
    public static void main(String[] args) {
        Notifier notifier = new EmailNotifier();
        notifier.send("alice@example.com", "Your order has shipped");

        notifier = new SmsNotifier();
        notifier.send("+447700900123", "Your order has shipped");

        List<Notifier> channels = List.of(new EmailNotifier(), new SmsNotifier());
        for (Notifier channel : channels) {
            channel.send("Alice", "Our sale starts tomorrow");
        }
    }
}
```

```
EMAIL to alice@example.com: Your order has shipped
SMS to +447700900123: Your order has shipped
EMAIL to Alice: Our sale starts tomorrow
SMS to Alice: Our sale starts tomorrow
```

- **The same line, `notifier.send(…)`, ran different code** depending on which object `notifier` held. Java decides at run time, from the actual object. This is called **polymorphism** ("many forms").
- **The loop doesn't know or care** which channels are in the list. Add a third implementation to the list and the loop is unchanged.
- **You can't create an interface itself.** `new Notifier()` fails with `Notifier is abstract; cannot be instantiated`: there's no code for `send` to run. You always create a class that implements it.

### 4. Code against the interface

Now the payoff from **Why interfaces**. Create `OrderService.java`:

```java
/** Takes orders, and tells the customer about them. */
public class OrderService {

    private final Notifier notifier;

    public OrderService(Notifier notifier) {
        this.notifier = notifier;
    }

    public void placeOrder(String customer, String item) {
        // A real shop would take payment, reserve stock and save the order here
        notifier.send(customer, "Thanks for ordering " + item);
    }
}
```

`OrderService` mentions `Notifier` and nothing else. It doesn't create its notifier. It's **given** one through its constructor, by whoever creates the `OrderService`. Replace `Main.java`:

```java
public class Main {
    public static void main(String[] args) {
        OrderService byEmail = new OrderService(new EmailNotifier());
        byEmail.placeOrder("alice@example.com", "a teapot");

        OrderService byText = new OrderService(new SmsNotifier());
        byText.placeOrder("+447700900123", "a teapot");
    }
}
```

```
EMAIL to alice@example.com: Thanks for ordering a teapot
SMS to +447700900123: Thanks for ordering a teapot
```

One `OrderService` class, two behaviours, and no `if`. Supporting push notifications later means writing a `PushNotifier` and passing it in. `OrderService` never changes. That's **coding against an interface**, and it's what frameworks such as Spring are built around: you write classes that ask for interfaces in their constructors, and the framework passes in the implementations.

### 5. Several interfaces, and interfaces that extend interfaces

Texts cost the shop money. Emails, effectively, don't. Being *priced* has nothing to do with sending messages, since lots of things cost money, so it gets its own interface. Create `Priced.java`:

```java
/** Anything that costs money each time it's used. */
public interface Priced {

    int costInPence();
}
```

A class can implement **any number** of interfaces, separated by commas. Replace `SmsNotifier.java`:

```java
public class SmsNotifier implements Notifier, Priced {

    @Override
    public void send(String recipient, String message) {
        System.out.println("SMS to " + recipient + ": " + message);
    }

    @Override
    public int costInPence() {
        return 4;
    }
}
```

An `SmsNotifier` is now both a `Notifier` and a `Priced`. `EmailNotifier` is only a `Notifier`. Replace `Main.java`:

```java
public class Main {
    public static void main(String[] args) {
        SmsNotifier sms = new SmsNotifier();

        Notifier asNotifier = sms;
        Priced asPriced = sms;

        asNotifier.send("+447700900123", "Your order has shipped");
        System.out.println("That cost " + asPriced.costInPence() + "p");
    }
}
```

```
SMS to +447700900123: Your order has shipped
That cost 4p
```

All three variables point to the **same object**, but the type of the *variable* decides which methods you can call through it. Change the last line to `asNotifier.costInPence()` and it won't compile:

```
Main.java:9: error: cannot find symbol
        System.out.println("That cost " + asNotifier.costInPence() + "p");
                                                    ^
  symbol:   method costInPence()
  location: variable asNotifier of type Notifier
```

The object can do it, but a `Notifier` variable only promises `send`, and the compiler only trusts the promise. Change it back.

**Interfaces can extend other interfaces**, adding methods to the contract. The `List` you already use is built that way. `javap` shows the chain:

```bash
javap java.util.List
```

The first line of the output (after `Compiled from…`) is:

```
public interface java.util.List<E> extends java.util.SequencedCollection<E> {
```

and in turn, `SequencedCollection` extends `Collection`, and `Collection` extends `Iterable`. Each adds methods. `Iterable` only promises that you can loop over it, `Collection` adds `size()` and `add(…)`, and `List` adds position-based methods such as `get(int)`. An interface uses `extends`, not `implements`, and can extend several at once: `interface A extends B, C`.

An `ArrayList` implements `List`, so it's also a `Collection` and an `Iterable`. Try it in **JShell**, Java's interactive shell. Start it with `jshell` and type each line (it imports `java.util` for you):

```
List<String> names = new ArrayList<>()
Collection<String> asCollection = names
Iterable<String> asIterable = names
asCollection.size()
asIterable.get(0)
```

```
jshell> List<String> names = new ArrayList<>()
names ==> []

jshell> Collection<String> asCollection = names
asCollection ==> []

jshell> Iterable<String> asIterable = names
asIterable ==> []

jshell> asCollection.size()
$4 ==> 0

jshell> asIterable.get(0)
|  Error:
|  cannot find symbol
|    symbol:   method get(int)
|  asIterable.get(0)
|  ^------------^
```

The same rule as `asNotifier` applies: an `Iterable` variable only promises looping, so `get` isn't available through it. Type `/exit` to leave JShell.

### 6. Constants

An interface can't have instance fields, because it doesn't describe state. It can have **constants**. Add a limit to `Notifier.java`:

```java
/** Something that can send a message to someone. */
public interface Notifier {

    int MAX_MESSAGE_LENGTH = 500;

    void send(String recipient, String message);
}
```

Replace `Main.java`:

```java
public class Main {
    public static void main(String[] args) {
        System.out.println(Notifier.MAX_MESSAGE_LENGTH);
    }
}
```

```
500
```

`javap` shows what the compiler made of it:

```bash
javac -d out Notifier.java
javap -p -constants out/Notifier.class
```

```
Compiled from "Notifier.java"
public interface Notifier {
  public static final int MAX_MESSAGE_LENGTH = 500;
  public abstract void send(java.lang.String, java.lang.String);
}
```

Every field in an interface is automatically `public static final`: one shared, unchangeable value. (`-constants` asks `javap` to show the value too.) Delete `out` again.

Use this sparingly. A constant belongs in an interface when it's part of the contract, as this limit is: every notifier must respect it. Step 9 puts it to work. An interface that holds *only* constants, used so classes can "implement" it to get short names for them, is a well-known bad habit. It makes the constants part of every implementing class's public type. Put such constants in an ordinary class instead.

### 7. Default methods

Suppose the shop wants to send one message to a whole list of people. You could add an abstract `sendToAll` to `Notifier`, but then **every** class that implements `Notifier` would stop compiling until someone wrote a `sendToAll` for it. For an interface used across a big codebase, or in a library used by other people's code, that's a serious problem.

A **default method** solves it. It's a method in an interface **with a body**, which implementing classes get for free. Replace `Notifier.java`:

```java
import java.util.List;

/** Something that can send a message to someone. */
public interface Notifier {

    int MAX_MESSAGE_LENGTH = 500;

    void send(String recipient, String message);

    /** Sends the same message to everyone in the list, one at a time. */
    default void sendToAll(List<String> recipients, String message) {
        for (String recipient : recipients) {
            send(recipient, message);
        }
    }
}
```

- **`default`** marks a method that has a body. Like every interface method, it's automatically `public`.
- **It calls `send`**, the abstract method. The default doesn't know how sending works, so it uses the method each class provides. This is the usual shape: defaults are built from the abstract methods.
- **`EmailNotifier` and `SmsNotifier` haven't changed**, but they both have `sendToAll` now.

This is exactly why Java 8 added default methods: to let the JDK add methods such as `forEach` to `Iterable` and `sort` to `List` without breaking every class that already implemented them.

Replace `Main.java`:

```java
import java.util.List;

public class Main {
    public static void main(String[] args) {
        Notifier sms = new SmsNotifier();
        sms.sendToAll(List.of("+447700900123", "+447700900456"), "Stock take at 5pm");

        Notifier email = new EmailNotifier();
        email.sendToAll(List.of("alice@example.com", "bob@example.com"), "Stock take at 5pm");
    }
}
```

```
SMS to +447700900123: Stock take at 5pm
SMS to +447700900456: Stock take at 5pm
EMAIL to alice@example.com: Stock take at 5pm
EMAIL to bob@example.com: Stock take at 5pm
```

One email per person is wasteful when an email can have many recipients. A class can **override** a default method, just like any inherited method. Replace `EmailNotifier.java`:

```java
import java.util.List;

public class EmailNotifier implements Notifier {

    @Override
    public void send(String recipient, String message) {
        System.out.println("EMAIL to " + recipient + ": " + message);
    }

    /** One email with everyone on it, instead of one email each. */
    @Override
    public void sendToAll(List<String> recipients, String message) {
        System.out.println("EMAIL to " + String.join(", ", recipients) + ": " + message);
    }
}
```

(`String.join(", ", recipients)` joins the list into one string, with `", "` between the items.) Run it again:

```
SMS to +447700900123: Stock take at 5pm
SMS to +447700900456: Stock take at 5pm
EMAIL to alice@example.com, bob@example.com: Stock take at 5pm
```

SMS still uses the default. Email uses its own version.

### 8. Static methods

An interface can also have **static methods**: methods that belong to the interface itself, not to any implementation. A common use is a **factory**, which hands out a ready-made implementation so callers don't need to know its class.

Create `ConsoleNotifier.java`, for trying the shop out on your own machine without sending anything:

```java
/** For development: prints messages instead of sending them. */
public class ConsoleNotifier implements Notifier {

    @Override
    public void send(String recipient, String message) {
        System.out.println("CONSOLE (not sent) to " + recipient + ": " + message);
    }
}
```

and add a static method to `Notifier.java`, after `sendToAll`:

```java
    /** A notifier for development, which prints messages instead of sending them. */
    static Notifier console() {
        return new ConsoleNotifier();
    }
```

Replace `Main.java`:

```java
public class Main {
    public static void main(String[] args) {
        OrderService service = new OrderService(Notifier.console());
        service.placeOrder("alice@example.com", "a teapot");
    }
}
```

```
CONSOLE (not sent) to alice@example.com: Thanks for ordering a teapot
```

- **Call it on the interface: `Notifier.console()`.** Static interface methods are *not* inherited by implementing classes. `EmailNotifier.console()` fails with `cannot find symbol`, and so does calling it on an object.
- **The return type is `Notifier`,** so `Main` never mentions `ConsoleNotifier`. If you later replace `ConsoleNotifier` with something better, only `console()` changes. You'll see the same pattern in the JDK: `List.of(...)` returns *a* `List`, and you don't know or need to know which class.

### 9. Private methods

The shop wants to stop blank messages and over-long ones. That check belongs in `Notifier`, so every channel gets it. Add a second default method, `sendReminder`, and both defaults need the check. Rather than write it twice, put it in a **private method**, which is visible only inside the interface. Replace `Notifier.java`:

```java
import java.util.List;

/** Something that can send a message to someone. */
public interface Notifier {

    int MAX_MESSAGE_LENGTH = 500;

    void send(String recipient, String message);

    /** Sends the same message to everyone in the list, one at a time. */
    default void sendToAll(List<String> recipients, String message) {
        requireValidMessage(message);
        for (String recipient : recipients) {
            send(recipient, message);
        }
    }

    /** Sends a message with "Reminder: " in front. */
    default void sendReminder(String recipient, String message) {
        requireValidMessage(message);
        send(recipient, "Reminder: " + message);
    }

    /** A notifier for development, which prints messages instead of sending them. */
    static Notifier console() {
        return new ConsoleNotifier();
    }

    private void requireValidMessage(String message) {
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException(
                    "message is " + message.length() + " characters, but the limit is " + MAX_MESSAGE_LENGTH);
        }
    }
}
```

- **`private` methods in interfaces** (Java 9+) are helpers for the default and static methods. They're not part of the contract: implementing classes can't see them or call them, and don't have to provide them.
- **The constant from step 6** is the limit. Throwing `IllegalArgumentException` is how a method refuses bad input.

Replace `Main.java`:

```java
import java.util.List;

public class Main {
    public static void main(String[] args) {
        Notifier sms = new SmsNotifier();
        sms.sendReminder("+447700900123", "Your parcel arrives today");

        List<String> team = List.of("alice@example.com", "bob@example.com");
        String essay = "x".repeat(600);

        try {
            sms.sendToAll(team, essay);
        } catch (IllegalArgumentException e) {
            System.out.println("Refused: " + e.getMessage());
        }

        try {
            sms.sendReminder("+447700900123", "   ");
        } catch (IllegalArgumentException e) {
            System.out.println("Refused: " + e.getMessage());
        }

        Notifier email = new EmailNotifier();
        email.sendToAll(team, "   ");
    }
}
```

```
SMS to +447700900123: Reminder: Your parcel arrives today
Refused: message is 600 characters, but the limit is 500
Refused: message must not be blank
EMAIL to alice@example.com, bob@example.com:    
```

Look at the last line. The blank message was **sent** by email, with no `Refused`. `EmailNotifier` overrides `sendToAll` (step 7), and **an override replaces the default completely**, checks included. It can't call `requireValidMessage` either, because that's private to `Notifier` (`cannot find symbol`). When you override a default method, check what it did that you might need to do too.

### 10. When two defaults clash

Admin screens and logs want a short description of things. Add a default `description` to `Notifier.java`, just above `console()`:

```java
    /** A short description for admin screens and logs. */
    default String description() {
        return "sends messages";
    }
```

Anything priced should describe its price, so add one to `Priced.java` as well:

```java
/** Anything that costs money each time it's used. */
public interface Priced {

    int costInPence();

    /** A short description for admin screens and logs. */
    default String description() {
        return "costs " + costInPence() + "p per use";
    }
}
```

Replace `Main.java`:

```java
public class Main {
    public static void main(String[] args) {
        System.out.println(new EmailNotifier().description());
        System.out.println(new SmsNotifier().description());
    }
}
```

and run it:

```
SmsNotifier.java:1: error: types Notifier and Priced are incompatible;
public class SmsNotifier implements Notifier, Priced {
       ^
  class SmsNotifier inherits unrelated defaults for description() from types Notifier and Priced
1 error
error: compilation failed
```

`SmsNotifier` now inherits two different `description()` methods, one from each interface, and Java refuses to guess which you meant. (`EmailNotifier` is fine. It only implements `Notifier`.) A class can extend only one class, so this can't happen with classes. It's the price of implementing several interfaces that have default methods.

The fix is to **override** the method in the class and decide for yourself. To reuse an interface's version, write **`InterfaceName.super.method()`**. Add this to `SmsNotifier.java`:

```java
    @Override
    public String description() {
        return Notifier.super.description() + ", " + Priced.super.description();
    }
```

```
sends messages
sends messages, costs 4p per use
```

`Notifier.super.description()` means "run `Notifier`'s default version of `description`". Here the class uses both, but it could equally pick one, or return something completely different. What matters is that the choice is written down, not guessed.

### 11. Lambdas: implementing an interface in one line

`Notifier` has **one** abstract method, `send`. (Default, static and private methods don't count, because they already have bodies.) An interface with exactly one abstract method is called a **functional interface**, and Java gives you a very short way to implement one: a **lambda**.

Mark `Notifier` as one. Add `@FunctionalInterface` just above `public interface Notifier`:

```java
/** Something that can send a message to someone. */
@FunctionalInterface
public interface Notifier {
```

The annotation is optional, but it asks the compiler to check the rule. If someone later adds a second abstract method, they get an error right here, `Unexpected @FunctionalInterface annotation`, instead of breaking every lambda elsewhere.

Replace `Main.java`:

```java
import java.util.List;

public class Main {
    public static void main(String[] args) {
        Notifier shout = (recipient, message) ->
                System.out.println("SHOUTING AT " + recipient + ": " + message.toUpperCase());

        shout.send("Alice", "your order has shipped");

        OrderService service = new OrderService(shout);
        service.placeOrder("Bob", "a teapot");

        shout.sendToAll(List.of("Carol", "Dan"), "the sale starts tomorrow");
        System.out.println(shout.description());
    }
}
```

```
SHOUTING AT Alice: YOUR ORDER HAS SHIPPED
SHOUTING AT Bob: THANKS FOR ORDERING A TEAPOT
SHOUTING AT Carol: THE SALE STARTS TOMORROW
SHOUTING AT Dan: THE SALE STARTS TOMORROW
sends messages
```

Read the lambda as "given a `recipient` and a `message`, do this":

- **`(recipient, message)`** are the parameters of `send`. Java knows their types (`String`, `String`) from `Notifier`, so you don't write them.
- **`->`** separates the parameters from the body.
- **The body** is what `send` does. Here it's a single statement, so there are no braces and no semicolon inside.

The result is a real `Notifier`. `OrderService` takes it like any other, and it gets the default methods, `sendToAll` and `description`, like any other. It's the same as writing a whole `ShoutingNotifier` class with a `send` method, without the class.

Use a lambda when the implementation is short and used in one place. Write a class when it has a name worth giving it, needs fields, or overrides defaults, as `EmailNotifier` does.

> **There's much more to lambdas**: using variables from around them, method references such as `System.out::println`, and the ready-made functional interfaces in `java.util.function` (`Function`, `Predicate`, `Supplier`…). They're the subject of their own guide. For now, the idea to keep is: **a lambda is a short way to implement an interface that has one abstract method.**

Your finished `Notifier.java`:

```java
import java.util.List;

/** Something that can send a message to someone. */
@FunctionalInterface
public interface Notifier {

    int MAX_MESSAGE_LENGTH = 500;

    void send(String recipient, String message);

    /** Sends the same message to everyone in the list, one at a time. */
    default void sendToAll(List<String> recipients, String message) {
        requireValidMessage(message);
        for (String recipient : recipients) {
            send(recipient, message);
        }
    }

    /** Sends a message with "Reminder: " in front. */
    default void sendReminder(String recipient, String message) {
        requireValidMessage(message);
        send(recipient, "Reminder: " + message);
    }

    /** A short description for admin screens and logs. */
    default String description() {
        return "sends messages";
    }

    /** A notifier for development, which prints messages instead of sending them. */
    static Notifier console() {
        return new ConsoleNotifier();
    }

    private void requireValidMessage(String message) {
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException(
                    "message is " + message.length() + " characters, but the limit is " + MAX_MESSAGE_LENGTH);
        }
    }
}
```

and `SmsNotifier.java`:

```java
public class SmsNotifier implements Notifier, Priced {

    @Override
    public void send(String recipient, String message) {
        System.out.println("SMS to " + recipient + ": " + message);
    }

    @Override
    public int costInPence() {
        return 4;
    }

    @Override
    public String description() {
        return Notifier.super.description() + ", " + Priced.super.description();
    }
}
```

---

## Your turn

### Exercise 1: a new channel

The shop's app can show push notifications on the customer's phone. Write a `PushNotifier` that prints `PUSH to <recipient>: <message>`, and use it to place an order through `OrderService`, **without changing `OrderService`**.

<details>
<summary>Hint</summary>

Copy the shape of `SmsNotifier` from step 3, then pass a new `PushNotifier` to `OrderService`'s constructor, as in step 4.

</details>

<details>
<summary>Solution</summary>

`PushNotifier.java`:

```java
/** Sends a notification to the customer's phone, through the shop's app. */
public class PushNotifier implements Notifier {

    @Override
    public void send(String recipient, String message) {
        System.out.println("PUSH to " + recipient + ": " + message);
    }
}
```

`Main.java`:

```java
public class Main {
    public static void main(String[] args) {
        OrderService service = new OrderService(new PushNotifier());
        service.placeOrder("alice", "a teapot");
    }
}
```

```
PUSH to alice: Thanks for ordering a teapot
```

`OrderService` didn't change, and `PushNotifier` also got `sendToAll`, `sendReminder` and `description` for free.

</details>

### Exercise 2: urgent messages

1. Add a default method `sendUrgent(String recipient, String message)` to `Notifier`. It should check the message like the other defaults do, then send it with `"URGENT: "` in front.
2. Texts should be short, so make `SmsNotifier` override it to use `"!! "` instead.

In `Main`, send the same urgent message by email and by SMS.

<details>
<summary>Hint</summary>

`sendReminder` from step 9 is almost exactly what part 1 needs. For part 2, override it in `SmsNotifier` as `EmailNotifier` overrides `sendToAll` in step 7. Remember from step 9 that the override can't call the private `requireValidMessage`.

</details>

<details>
<summary>Solution</summary>

In `Notifier.java`, next to `sendReminder`:

```java
    /** Sends a message with "URGENT: " in front. */
    default void sendUrgent(String recipient, String message) {
        requireValidMessage(message);
        send(recipient, "URGENT: " + message);
    }
```

In `SmsNotifier.java`:

```java
    /** Texts should be short, so "!! " instead of "URGENT: ". */
    @Override
    public void sendUrgent(String recipient, String message) {
        send(recipient, "!! " + message);
    }
```

`Main.java`:

```java
public class Main {
    public static void main(String[] args) {
        Notifier email = new EmailNotifier();
        Notifier sms = new SmsNotifier();
        email.sendUrgent("alice@example.com", "Your card was declined");
        sms.sendUrgent("+447700900123", "Your card was declined");
    }
}
```

```
EMAIL to alice@example.com: URGENT: Your card was declined
SMS to +447700900123: !! Your card was declined
```

`sms` is declared as a `Notifier`, yet the SMS version ran: the object decides, not the variable (step 3). As in step 9, the SMS override skips the blank and length checks. To keep them, you could make `requireValidMessage` a `static` method instead of a `private` one, so classes can call `Notifier.requireValidMessage(...)`, at the cost of making it part of the interface's public contract.

</details>

### Exercise 3: predict, then run

Using the finished code from step 11, decide what this prints, **without running it**:

```java
import java.util.List;

public class Main {
    public static void main(String[] args) {
        Notifier log = (recipient, message) -> System.out.println("LOG " + recipient + ": " + message);
        Notifier email = new EmailNotifier();
        Notifier sms = new SmsNotifier();
        List<String> pair = List.of("alice", "bob");

        log.sendToAll(pair, "Hello");
        email.sendToAll(pair, "Hello");
        sms.sendReminder("carol", "Hello");
        System.out.println(log.description());
        System.out.println(sms.description());
    }
}
```

Then run it, and explain each line of output.

<details>
<summary>Solution</summary>

```
LOG alice: Hello
LOG bob: Hello
EMAIL to alice, bob: Hello
SMS to carol: Reminder: Hello
sends messages
sends messages, costs 4p per use
```

- **`log.sendToAll`**: the lambda only provides `send`, so it uses `Notifier`'s default `sendToAll`, which calls `send` once per person: two lines.
- **`email.sendToAll`**: `EmailNotifier` overrides the default, so one line with both recipients.
- **`sms.sendReminder`**: `SmsNotifier` doesn't override it, so the default adds `"Reminder: "` and calls the SMS `send`.
- **`log.description()`**: the lambda gets `Notifier`'s default.
- **`sms.description()`**: `SmsNotifier`'s own override, combining both interfaces' defaults (step 10).

</details>

---

## Common errors

**`error: … is not abstract and does not override abstract method send(String,String) in Notifier`**
The class says `implements Notifier` but has no `send` with exactly that signature: the same name, the same parameter types, in the same order. Check for typos in the name or a different parameter type.

**`error: method does not override or implement a method from a supertype`**
`@Override` is on a method that doesn't match anything in the interface, usually a misspelt name (`sendd`) or different parameters. This error is `@Override` doing its job.

**`attempting to assign weaker access privileges; was public`**
The implementing method isn't `public`. Interface methods are always `public`, so implementations must be too.

**`error: Notifier is abstract; cannot be instantiated`**
You wrote `new Notifier()`. Create an implementing class instead, or use a lambda (step 11).

**`error: cannot find symbol` … `location: variable … of type Notifier`**
You called a method through a variable whose type doesn't have it, such as `costInPence()` on a `Notifier`. The variable's type decides what you can call, even if the object could do it. Use a variable of the right type (`Priced`).

**`error: cannot find symbol` for a static interface method such as `console()`**
You called it through a class: `EmailNotifier.console()`. Static interface methods aren't inherited. Call `Notifier.console()`.

**`error: illegal static interface method call`** with **`the receiver expression should be replaced with the type qualifier 'Notifier'`**
You called a static interface method on an object, as in `notifier.console()`. Use `Notifier.console()`.

**`error: cannot find symbol` for a private interface method, inside an implementing class**
Private interface methods are only visible inside the interface. Do the work in the class, or make the helper a `static` method on the interface.

**`error: types Notifier and Priced are incompatible;`** with **`inherits unrelated defaults for description()`**
Two interfaces supply the same default method. Override it in the class, and use `Notifier.super.description()` or `Priced.super.description()` to reuse either version.

**`error: Unexpected @FunctionalInterface annotation`** with **`multiple non-overriding abstract methods found`**
An interface marked `@FunctionalInterface` has more than one abstract method. Give the extra method a default body, or remove it.

**`error: incompatible types: Notifier is not a functional interface`**
You assigned a lambda to an interface with more than one abstract method. A lambda can only supply one method.

**`error: cannot find symbol` pointing at `EmailNotifier` or `Notifier`, when the file is right there**
Your `java` is older than 22, which can only launch a single source file. Check `java -version`, and see the [README](README.md#before-you-start).

---

## Recap

- **An interface is a contract**: methods that implementing classes promise to provide. Its abstract methods are automatically `public abstract`, and its fields `public static final`.
- **An interface is a type.** Variables, parameters and lists of an interface type can hold any implementation, and Java runs the right method for the actual object (polymorphism).
- **Code against interfaces.** A class that asks for a `Notifier` works with every notifier, including ones written after it.
- **A class can implement many interfaces**, and interfaces can extend each other. The variable's type decides which methods you can call.
- **Default methods** add behaviour to an interface without breaking its implementations. Classes can override them, and an override replaces the default entirely.
- **Static methods** belong to the interface (call them as `Notifier.console()`), and **private methods** are helpers that only the interface can see.
- **When two interfaces supply the same default,** the class must override it, and can reuse either one with `X.super.method()`.
- **A functional interface** has one abstract method, and **a lambda** is a short way to implement it.

### Interview angle

<details>
<summary>"What's the difference between an interface and an abstract class?"</summary>

A class can implement many interfaces but extend only one class. An abstract class can have instance fields, constructors and non-public members, so it suits a family of classes that share state or setup code. An interface describes a capability, a contract, with no state, though since Java 8 it can supply behaviour through default and static methods, and since Java 9 private helpers. I'd default to an interface, and only add an abstract class (often one that implements the interface) when implementations genuinely share state.

</details>

<details>
<summary>"Why were default methods added?"</summary>

To let interfaces evolve. Before Java 8, adding a method to an interface broke every class that implemented it. That made it impossible to add, for example, `forEach` to `Iterable` or `sort` to `List` without breaking a huge amount of existing code. A default method gives the new method a body, so existing implementations get it automatically and can override it if they have something better.

</details>

<details>
<summary>"What happens if a class inherits the same default method from two interfaces?"</summary>

It doesn't compile until the class resolves it. The class overrides the method, and can call either version with `InterfaceName.super.method()`. Two related rules: if a superclass declares the method, the class's version always wins over interface defaults; and if one interface extends the other, the more specific interface's default wins.

</details>

<details>
<summary>"What is a functional interface?"</summary>

An interface with exactly one abstract method. Default, static and private methods don't count. Lambdas and method references can implement it. `@FunctionalInterface` is optional, but makes the compiler enforce the rule. Examples are `Runnable`, `Comparator`, and the interfaces in `java.util.function` such as `Function` and `Predicate`.

</details>

<details>
<summary>"What does 'program to an interface' mean, and why does it matter?"</summary>

Declare variables, fields and parameters with interface types (`List`, `Notifier`), not concrete classes (`ArrayList`, `EmailNotifier`), and have dependencies passed in rather than created inside. The code then works with any implementation, so you can swap implementations, add new ones, or pass in a fake one for testing, without changing it. It's the idea dependency-injection frameworks such as Spring are built on.

</details>

### Further reading

- [Dev.java: Interfaces](https://dev.java/learn/interfaces/): the official Java tutorial on interfaces
- [The Java Language Specification, chapter 9: Interfaces](https://docs.oracle.com/javase/specs/jls/se25/html/jls-9.html): the exact rules, including how default-method conflicts are resolved
- [`@FunctionalInterface` Javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/FunctionalInterface.html)
