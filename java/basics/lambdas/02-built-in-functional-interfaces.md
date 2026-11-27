# 02: Built-in functional interfaces

> Written for **Java 25**. Uses only the JDK: no build tool and no IDE. See the [series README](README.md) for setup.

## What you'll learn

- Why the JDK has a **standard set of functional interfaces**, in `java.util.function`, and what you gain by using them instead of your own
- How to swap guide 01's `BookFilter` for **`Predicate<Book>`**, and why nothing else has to change
- The six you'll meet most: **`Predicate`**, **`Function`**, **`Consumer`**, **`Supplier`**, **`UnaryOperator`** and **`BiFunction`**, and the method each one asks a lambda to write
- How to **read generic types** such as `Function<Book, String>`, without writing any of your own
- How to **combine** small lambdas into bigger ones with `and`, `or`, `negate` and `andThen`, and why each of them returns something **new**

By the end you'll have a `filter` method and a `map` method that take the JDK's own interfaces, and you'll build "long and recent" or "the title, in capitals" out of lambdas you already wrote.

---

## Prerequisites for this guide

- [Guide 01](01-your-first-lambdas.md): writing a lambda, functional interfaces and `@FunctionalInterface`, the syntax forms, and `removeIf`, `forEach` and `sort`.
- The code from guide 01, in `workspace/java-basics/lambdas/`. Step 1 shows everything you need in full, so you can start here.

---

## Concepts

### One vocabulary instead of many

In guide 01 you wrote `BookFilter`, an interface for "a test of a book". It worked, but nothing outside your program knows about it. `removeIf` was written years before it, and takes a test of its own type. A library for films would need a `FilmFilter`, and a library for orders an `OrderFilter`. Each would have the same shape, one method that takes a thing and returns a `boolean`, and none would fit the others' methods.

Java 8 added lambdas and, with them, the package **`java.util.function`**: about forty ready-made functional interfaces for the shapes that come up again and again. "A test of something" is `Predicate`. "Turn one thing into another" is `Function`. The JDK's own methods take these types, and so can yours.

The payoff is that lambdas fit everywhere. A method that takes a `Predicate<Book>` accepts **any** test of a book you've already written, whether you wrote it for your own `filter` method or for the JDK's `removeIf`. You'll see that in step 2.

### The six you'll use most

Each interface is defined by what its one method **takes** and what it **returns**:

| Interface | Takes | Returns | Its method | Example from this guide |
| --- | --- | --- | --- | --- |
| `Predicate<T>` | a `T` | `boolean` | `test` | `book -> book.pages() > 400` |
| `Function<T, R>` | a `T` | an `R` | `apply` | `book -> book.title()` |
| `Consumer<T>` | a `T` | nothing | `accept` | `book -> System.out.println(book)` |
| `Supplier<T>` | nothing | a `T` | `get` | `() -> Books.sample()` |
| `UnaryOperator<T>` | a `T` | a `T` | `apply` | `title -> title.toUpperCase()` |
| `BiFunction<T, U, R>` | a `T` and a `U` | an `R` | `apply` | `(book, extraPages) -> …` |

You don't have to learn this table by heart. When you write a lambda you rarely type the method name, because the target type decides it. You need the method name when you **call** the interface yourself, as `filter` does with `filter.test(book)`.

### Reading generic type arguments

`T`, `R` and `U` in the table are **type parameters**: placeholders that each use fills in. You've read them before without the name: in `List<Book>`, the list's element type is filled in with `Book`.

- **`Predicate<Book>`** fills in `T` with `Book`: "a test of a `Book`". Its method is `boolean test(Book book)`, the same as `BookFilter`'s.
- **`Function<Book, String>`** fills in `T` with `Book` and `R` with `String`: "a function from `Book` to `String`". Its method is `String apply(Book book)`.
- **`BiFunction<Book, Integer, Book>`** takes a `Book` and an `Integer`, and returns a `Book`.

The rule of thumb for `java.util.function`: **inputs first, result last**. Once you know which letter is the result, you can read any of them.

Type arguments must be **class** types, such as `Integer` or `String`, never a primitive such as `int`. That's why the `BiFunction` above says `Integer`, and why `Predicate<int>` is a Common error.

You're only ever *reading* these types in this series. Writing a generic class or method of your own, with your own `T`, is a separate topic.

### Default methods: how a one-method interface does more

A functional interface has exactly one **abstract** method, but guide 01's sidebar said it can have `default` methods too. A default method has a body, written in the interface itself, so every object that implements the interface gets it for free, and a lambda doesn't have to supply it.

The interfaces in `java.util.function` use this heavily. `Predicate` has one abstract method, `test`, and default methods called `and`, `or` and `negate`. `Function` has `apply`, and a default method called `andThen`. Those default methods are how you'll combine lambdas in steps 7 and 8, and they're why a lambda you wrote in one line can be combined with another without any extra code from you.

---

## Steps

### 1. Start from guide 01

This guide starts from the code [guide 01](01-your-first-lambdas.md) ended with, cut down to a short `Main`. If you're carrying on, you already have `Book.java`, `Books.java` and `BookFilter.java`, and you only need to replace `Main.java`. If you're starting here, create the folder (guide 01, step 1) and all four files.

`Book.java`:

```java
record Book(String title, String author, int year, int pages) {}
```

`Books.java`:

```java
import java.util.ArrayList;
import java.util.List;

class Books {
    static List<Book> sample() {
        return new ArrayList<>(List.of(
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
        ));
    }
}
```

`BookFilter.java`:

```java
@FunctionalInterface
interface BookFilter {
    boolean test(Book book);
}
```

`Main.java`, which replaces guide 01's:

```java
import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) {
        List<Book> books = Books.sample();

        BookFilter isLong = book -> book.pages() > 400;

        print("Long books:", filter(books, isLong));
        print("Before 1950:", filter(books, book -> book.year() < 1950));
    }

    static List<Book> filter(List<Book> books, BookFilter filter) {
        List<Book> result = new ArrayList<>();
        for (Book book : books) {
            if (filter.test(book)) {
                result.add(book);
            }
        }
        return result;
    }

    static void print(String heading, List<Book> books) {
        System.out.println(heading);
        for (Book book : books) {
            System.out.println("  " + book);
        }
    }
}
```

Run it:

```bash
java Main.java
```

```
Long books:
  Book[title=Emma, author=Jane Austen, year=1815, pages=474]
  Book[title=Dune, author=Frank Herbert, year=1965, pages=412]
  Book[title=Pride and Prejudice, author=Jane Austen, year=1813, pages=432]
  Book[title=Wolf Hall, author=Hilary Mantel, year=2009, pages=653]
  Book[title=The Goldfinch, author=Donna Tartt, year=2013, pages=771]
Before 1950:
  Book[title=Emma, author=Jane Austen, year=1815, pages=474]
  Book[title=Nineteen Eighty-Four, author=George Orwell, year=1949, pages=328]
  Book[title=The Hobbit, author=J.R.R. Tolkien, year=1937, pages=310]
  Book[title=Pride and Prejudice, author=Jane Austen, year=1813, pages=432]
  Book[title=Animal Farm, author=George Orwell, year=1945, pages=112]
```

These are the first two sections from guide 01. As before, **your output should match exactly.**

### 2. `Predicate<Book>`: the JDK's `BookFilter`

The JDK already has an interface for "a test of something": **`Predicate`**, in the package `java.util.function`. Here's how it compares with yours:

| | Your interface | The JDK's |
| --- | --- | --- |
| Type | `BookFilter` | `Predicate<Book>` |
| Its one abstract method | `boolean test(Book book)` | `boolean test(Book book)` |

Read `Predicate<Book>` as "a test of a `Book`". It's the same shape, and even the method name is the same: guide 01 called `BookFilter`'s method `test` on purpose, so this swap would be easy.

Make three changes to `Main.java`. Add an import at the top:

```java
import java.util.function.Predicate;
```

Change the type of `isLong`:

```java
        Predicate<Book> isLong = book -> book.pages() > 400;
```

And change the type of `filter`'s second parameter:

```java
    static List<Book> filter(List<Book> books, Predicate<Book> filter) {
```

Run it. **The output is exactly the same as step 1's.**

Look at what you *didn't* change:

- **The lambdas.** `book -> book.pages() > 400` and `book -> book.year() < 1950` are the same text. A lambda doesn't name its interface. The target type does, so the same lambda becomes a `Predicate<Book>` instead of a `BookFilter`.
- **The body of `filter`.** It still calls `filter.test(book)`, because `Predicate`'s method is also `test`.
- **The calls to `filter`.**

Nothing in `Main.java` uses `BookFilter` any more, so **delete `BookFilter.java`**, and run `java Main.java` again to check. The output is still the same.

Now the payoff. Add this to the end of `main`:

```java
        List<Book> shortBooks = Books.sample();
        shortBooks.removeIf(isLong);
        print("Short books:", shortBooks);
```

The new section is:

```
Short books:
  Book[title=Nineteen Eighty-Four, author=George Orwell, year=1949, pages=328]
  Book[title=The Hobbit, author=J.R.R. Tolkien, year=1937, pages=310]
  Book[title=Klara and the Sun, author=Kazuo Ishiguro, year=2021, pages=307]
  Book[title=Animal Farm, author=George Orwell, year=1945, pages=112]
  Book[title=Never Let Me Go, author=Kazuo Ishiguro, year=2005, pages=288]
```

`removeIf` takes a `Predicate`. That's the interface guide 01 said it took without naming it. So the **same** `isLong` you pass to your own `filter` now works with the JDK's `removeIf` too, and removes the five long books.

With `isLong` still a `BookFilter`, that line doesn't compile:

```
Main.java:15: error: method removeIf in interface Collection<E> cannot be applied to given types;
        shortBooks.removeIf(isLong);
                  ^
  required: Predicate<? super Book>
  found:    BookFilter
  reason: argument mismatch; BookFilter cannot be converted to Predicate<? super Book>
  where E is a type-variable:
    E extends Object declared in interface Collection
```

`BookFilter` and `Predicate<Book>` have the same shape, but Java doesn't match interfaces by shape. A `BookFilter` object is a `BookFilter`, and nothing else. A lambda can become either one, but only when it's written: once it's stored in a `BookFilter` variable, that's what it is. Using the JDK's interface from the start avoids the problem.

(`Predicate<? super Book>` reads as "a `Predicate` of `Book`, or of a more general type than `Book`". `? super` lets `removeIf` also accept, say, a `Predicate<Object>`. A `Predicate<Book>` fits, and that's all you need to know about it.)

### 3. `Function<Book, String>`: turning books into something else

A `Predicate` answers yes or no. Often you want something else back: a book's title, say. That's a **`Function`**.

`Function<Book, String>` has two type arguments: the **input**, then the **result**. Read it as "a function from `Book` to `String`". Its one abstract method is:

```java
String apply(Book book)
```

Write a `map` method that uses one. Where `filter` keeps some books and drops others, `map` turns **every** book into something else, and returns a list of the results. Add the import:

```java
import java.util.function.Function;
```

Add `map` after `filter`:

```java
    static List<String> map(List<Book> books, Function<Book, String> function) {
        List<String> result = new ArrayList<>();
        for (Book book : books) {
            result.add(function.apply(book));
        }
        return result;
    }
```

And add this to the end of `main`:

```java
        Function<Book, String> toTitle = book -> book.title();
        List<String> titles = map(filter(books, isLong), toTitle);
        System.out.println("Long titles: " + titles);
        System.out.println("Their authors: " + map(filter(books, isLong), book -> book.author()));
```

`Main.java` now looks like this:

```java
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

public class Main {

    public static void main(String[] args) {
        List<Book> books = Books.sample();

        Predicate<Book> isLong = book -> book.pages() > 400;

        print("Long books:", filter(books, isLong));
        print("Before 1950:", filter(books, book -> book.year() < 1950));

        List<Book> shortBooks = Books.sample();
        shortBooks.removeIf(isLong);
        print("Short books:", shortBooks);

        Function<Book, String> toTitle = book -> book.title();
        List<String> titles = map(filter(books, isLong), toTitle);
        System.out.println("Long titles: " + titles);
        System.out.println("Their authors: " + map(filter(books, isLong), book -> book.author()));
    }

    static List<Book> filter(List<Book> books, Predicate<Book> filter) {
        List<Book> result = new ArrayList<>();
        for (Book book : books) {
            if (filter.test(book)) {
                result.add(book);
            }
        }
        return result;
    }

    static List<String> map(List<Book> books, Function<Book, String> function) {
        List<String> result = new ArrayList<>();
        for (Book book : books) {
            result.add(function.apply(book));
        }
        return result;
    }

    static void print(String heading, List<Book> books) {
        System.out.println(heading);
        for (Book book : books) {
            System.out.println("  " + book);
        }
    }
}
```

Run it. The new lines at the end are:

```
Long titles: [Emma, Dune, Pride and Prejudice, Wolf Hall, The Goldfinch]
Their authors: [Jane Austen, Frank Herbert, Jane Austen, Hilary Mantel, Donna Tartt]
```

- **`map(filter(books, isLong), toTitle)`** filters first, then maps: the long books, turned into their titles. One method's result is the other's input.
- **`toTitle`** is stored in a variable, because step 8 uses it again. The authors' lambda is written straight into the call, as most lambdas are.
- **`"Long titles: " + titles`** prints the list the way every `List` prints itself: square brackets, with commas between the elements.
- **`map` calls `function.apply(book)`**, not `test`. Each interface has its own method name, and the Concepts table lists them.

`map` only works for `Book` to `String`. A version that could map anything to anything would need a generic method, with its own type parameters, which this series doesn't teach. The JDK has that version, in the Streams API.

### 4. `Consumer<Book>`: what `forEach` takes

A **`Consumer`** takes a value and returns **nothing**. It's for lambdas that *do* something, such as print, rather than work something out. `Consumer<Book>`'s method is:

```java
void accept(Book book)
```

That's the type `forEach` takes, the other interface guide 01 left unnamed. Store a printing lambda in a variable, and pass it to `forEach`. Add the import:

```java
import java.util.function.Consumer;
```

And add this to the end of `main`:

```java
        Consumer<Book> printTitle = book -> System.out.println("  " + book.title());
        System.out.println("Short titles:");
        shortBooks.forEach(printTitle);
```

The new section is:

```
Short titles:
  Nineteen Eighty-Four
  The Hobbit
  Klara and the Sun
  Animal Farm
  Never Let Me Go
```

`forEach` calls `printTitle.accept(book)` once for each book in `shortBooks`, in order. `System.out.println` returns nothing, and so does the lambda, which is exactly what a `Consumer` is.

### 5. `Supplier`: a lambda with no parameters

Guide 01's table of syntax forms had a row with **no** parameters, `() -> ...`, and no interface to try it on. A **`Supplier`** is that interface. It takes nothing, and returns a value. `Supplier<List<Book>>`'s method is:

```java
List<Book> get()
```

Read `Supplier<List<Book>>` as "something that supplies a `List<Book>`". The type argument is itself a generic type, which is fine: it reads from the outside in.

Add the import:

```java
import java.util.function.Supplier;
```

And add this to the end of `main`:

```java
        Supplier<List<Book>> shelf = () -> Books.sample();
        List<Book> first = shelf.get();
        List<Book> second = shelf.get();
        first.removeIf(isLong);
        System.out.println("First shelf: " + first.size() + " books");
        System.out.println("Second shelf: " + second.size() + " books");
```

The new lines are:

```
First shelf: 5 books
Second shelf: 10 books
```

- **`() -> Books.sample()`** has empty parentheses, which the zero-parameter form needs.
- **Creating `shelf` doesn't call `Books.sample()`.** A lambda's body runs only when its method is called, here `get()`.
- **Each `get()` runs the body again.** `first` and `second` are two separate lists, so removing the long books from `first` leaves `second` with all ten.

That's what a `Supplier` is for: handing out a fresh value each time it's asked, or putting off work until a value is actually needed.

### 6. `UnaryOperator` and `BiFunction`

Two more shapes complete the set.

**`UnaryOperator<String>`** is a function from `String` to `String`: the input and the result are the **same** type. It's a `Function<String, String>` under a shorter name, and its method is also `apply`. `List.replaceAll` takes one, and uses it to replace every element of the list with the lambda's result.

Add the import:

```java
import java.util.function.UnaryOperator;
```

And add this to the end of `main`:

```java
        UnaryOperator<String> shout = title -> title.toUpperCase();
        titles.replaceAll(shout);
        System.out.println("Shouted: " + titles);
```

The new line is:

```
Shouted: [EMMA, DUNE, PRIDE AND PREJUDICE, WOLF HALL, THE GOLDFINCH]
```

`titles` is the list `map` made in step 3. `replaceAll` changes the list itself, like `removeIf` and `sort` in guide 01, so it needs a changeable list, and `map` returns a new `ArrayList`, so this one is. On a `List.of(...)` list it throws an exception (Common errors).

**`BiFunction<Book, Integer, Book>`** takes **two** inputs, a `Book` and an `Integer`, and returns a `Book`. Inputs first, result last. Its method is `Book apply(Book book, Integer extraPages)`.

Use one to make a new edition of a book with some extra pages. Add the import:

```java
import java.util.function.BiFunction;
```

And add this to the end of `main`:

```java
        BiFunction<Book, Integer, Book> newEdition = (book, extraPages) ->
                new Book(book.title(), book.author(), book.year(), book.pages() + extraPages);
        Book emma = books.get(0);
        Book bigEmma = newEdition.apply(emma, 26);
        System.out.println("New edition: " + bigEmma);
        System.out.println("Original:    " + emma);
```

The new lines are:

```
New edition: Book[title=Emma, author=Jane Austen, year=1815, pages=500]
Original:    Book[title=Emma, author=Jane Austen, year=1815, pages=474]
```

- **`(book, extraPages) -> ...`** is the two-parameter form, as in guide 01's comparator.
- **The lambda's body is on its own line.** A long lambda can break after the arrow, like any long expression.
- **`emma` didn't change.** A record's fields can't be changed after it's made, so "a book with more pages" has to be a **new** `Book`. The function returns it, and the original still has 474 pages.
- **`Integer`, not `int`.** Type arguments must be classes. Java converts between `int` and `Integer` for you, which is why `apply(emma, 26)` and `book.pages() + extraPages` both work.

> **Sidebar: primitive specialisations.** Converting an `int` to an `Integer` is called **boxing**: Java wraps the number in an object. It's automatic, but each `Integer` is a small object to create and later throw away, and in a loop over millions of numbers that adds up. So `java.util.function` also has versions that work on `int`, `long` and `double` directly, such as `IntPredicate` (a test of an `int`) and `ToIntFunction<Book>` (a function from `Book` to `int`). `Comparator` has `comparingInt` for the same reason. You'll meet them in IDE suggestions and other people's code. This series doesn't use them: for ten books, boxing costs nothing worth measuring, and the general interfaces are easier to read.

### 7. Combining predicates: `and`, `or`, `negate`

You have `isLong`. Say you also want recent books, written in 2000 or later, and then books that are long **and** recent. You could write a third lambda that tests both. Or you could combine the two you have.

`Predicate` has three default methods for that (Concepts):

- **`a.and(b)`** returns a predicate that's true when **both** `a` and `b` are true.
- **`a.or(b)`** returns one that's true when **either** is.
- **`a.negate()`** returns one that's true when `a` is **false**.

Add this to the end of `main`:

```java
        Predicate<Book> isRecent = book -> book.year() >= 2000;

        print("Long and recent:", filter(books, isLong.and(isRecent)));
        print("Long or recent:", filter(books, isLong.or(isRecent)));
        print("Not long:", filter(books, isLong.negate()));
```

The new sections are:

```
Long and recent:
  Book[title=Wolf Hall, author=Hilary Mantel, year=2009, pages=653]
  Book[title=The Goldfinch, author=Donna Tartt, year=2013, pages=771]
Long or recent:
  Book[title=Emma, author=Jane Austen, year=1815, pages=474]
  Book[title=Klara and the Sun, author=Kazuo Ishiguro, year=2021, pages=307]
  Book[title=Dune, author=Frank Herbert, year=1965, pages=412]
  Book[title=Pride and Prejudice, author=Jane Austen, year=1813, pages=432]
  Book[title=Wolf Hall, author=Hilary Mantel, year=2009, pages=653]
  Book[title=The Goldfinch, author=Donna Tartt, year=2013, pages=771]
  Book[title=Never Let Me Go, author=Kazuo Ishiguro, year=2005, pages=288]
Not long:
  Book[title=Nineteen Eighty-Four, author=George Orwell, year=1949, pages=328]
  Book[title=The Hobbit, author=J.R.R. Tolkien, year=1937, pages=310]
  Book[title=Klara and the Sun, author=Kazuo Ishiguro, year=2021, pages=307]
  Book[title=Animal Farm, author=George Orwell, year=1945, pages=112]
  Book[title=Never Let Me Go, author=Kazuo Ishiguro, year=2005, pages=288]
```

*Not long* is the same five books as *Short books* in step 2. Keeping what `isLong` rejects and removing what it accepts are the same thing.

Each method **returns a new predicate**. `isLong.and(isRecent)` doesn't change `isLong`: it builds a third predicate whose `test` calls the other two. Here's roughly how `and` is written inside `Predicate`, slightly simplified:

```java
default Predicate<T> and(Predicate<T> other) {
    return t -> test(t) && other.test(t);
}
```

It's a default method that returns a **lambda**. `test(t)` is the `test` of the predicate you called `and` on, here `isLong`, and `other` is the one you passed in, here `isRecent`. Nothing is stored or changed. That matters in exercise 2.

### 8. Combining functions: `andThen`

Functions combine too. `f.andThen(g)` returns a function that runs `f`, then passes its result to `g`.

You already have `toTitle`, from `Book` to `String`, and `shout`, from `String` to `String`. Joined, they give a function from `Book` to its title in capitals. Add this to the end of `main`:

```java
        Function<Book, String> toShoutedTitle = toTitle.andThen(shout);
        System.out.println("Recent, shouted: " + map(filter(books, isRecent), toShoutedTitle));
```

The new line is:

```
Recent, shouted: [KLARA AND THE SUN, WOLF HALL, THE GOLDFINCH, NEVER LET ME GO]
```

- **The order is the order you read it in:** `toTitle` first, `andThen` `shout`.
- **The types have to line up.** `toTitle` returns a `String`, and `shout` takes a `String`. `andThen` accepts `shout` because a `UnaryOperator<String>` *is* a `Function<String, String>`.
- **The result is a `Function<Book, String>`:** it takes what `toTitle` takes and returns what `shout` returns. So `map` accepts it like any other.
- **`toTitle` and `shout` haven't changed.** Like `and`, `andThen` returns a new function.

### 9. Older functional interfaces

`java.util.function` arrived in Java 8, but some interfaces already had exactly one abstract method long before that. Lambdas work with them too, because the only rule is "one abstract method". Two you'll meet often:

| Interface | Package | Takes | Returns | Its method |
| --- | --- | --- | --- | --- |
| `Comparator<T>` | `java.util` | two `T`s | `int` | `compare` |
| `Runnable` | `java.lang` | nothing | nothing | `run` |

`Comparator` is the one you used with `sort` in guide 01. `Runnable` is the interface for "a piece of work to run", used with threads. Both have carried `@FunctionalInterface` since Java 8.

So when a method takes a `Comparator` or a `Runnable`, you can hand it a lambda, even though neither interface is in `java.util.function`. Guide 03 does a lot more with `Comparator`.

That's the program for this guide. In guide 03, you'll make the lambdas shorter still, with method references.

---

## Your turn

### Exercise 1: Jane Austen's books, or long ones

Add a `Predicate<Book>` called `isByAusten`, for books by Jane Austen. Combine it with `isLong` using `or`, and print the result with `filter` under the heading `By Jane Austen, or long:`. Before you run it, work out which books it should print.

<details>
<summary>Solution</summary>

Add this at the end of `main`:

```java
        Predicate<Book> isByAusten = book -> book.author().equals("Jane Austen");
        print("By Jane Austen, or long:", filter(books, isByAusten.or(isLong)));
```

The new section is:

```
By Jane Austen, or long:
  Book[title=Emma, author=Jane Austen, year=1815, pages=474]
  Book[title=Dune, author=Frank Herbert, year=1965, pages=412]
  Book[title=Pride and Prejudice, author=Jane Austen, year=1813, pages=432]
  Book[title=Wolf Hall, author=Hilary Mantel, year=2009, pages=653]
  Book[title=The Goldfinch, author=Donna Tartt, year=2013, pages=771]
```

That's the same five books as *Long books*, and it's right. Both of Jane Austen's books, *Emma* and *Pride and Prejudice*, are over 400 pages, so they're already long. `or` keeps a book if **either** test passes, so it can only add books that `isLong` missed, and here there are none.

Change `or` to `and`, with the heading `By Jane Austen, and long:`, and you get only the books that pass both:

```
By Jane Austen, and long:
  Book[title=Emma, author=Jane Austen, year=1815, pages=474]
  Book[title=Pride and Prejudice, author=Jane Austen, year=1813, pages=432]
```

</details>

### Exercise 2: combine, then forget

Add these two lines to the end of `main`:

```java
        isLong.and(isRecent);
        print("Long and recent, again:", filter(books, isLong));
```

The intent is "make `isLong` mean long and recent, then filter with it". Run it. How many books does the new section print, and why? Then fix it.

<details>
<summary>Solution</summary>

It prints all five long books, not the two long and recent ones:

```
Long and recent, again:
  Book[title=Emma, author=Jane Austen, year=1815, pages=474]
  Book[title=Dune, author=Frank Herbert, year=1965, pages=412]
  Book[title=Pride and Prejudice, author=Jane Austen, year=1813, pages=432]
  Book[title=Wolf Hall, author=Hilary Mantel, year=2009, pages=653]
  Book[title=The Goldfinch, author=Donna Tartt, year=2013, pages=771]
```

`and` doesn't change `isLong`. It **returns a new predicate** (step 7), and the first line throws that new predicate away. The compiler doesn't complain, because Java lets you call a method and ignore what it returns. `isLong` is still the plain "more than 400 pages" test.

Fix it by keeping the result:

```java
        Predicate<Book> isLongAndRecent = isLong.and(isRecent);
        print("Long and recent, again:", filter(books, isLongAndRecent));
```

```
Long and recent, again:
  Book[title=Wolf Hall, author=Hilary Mantel, year=2009, pages=653]
  Book[title=The Goldfinch, author=Donna Tartt, year=2013, pages=771]
```

Passing `isLong.and(isRecent)` straight into `filter`, as step 7 does, works too. The same is true of `or`, `negate` and `andThen`: their result is the whole point, so always use it.

</details>

### Exercise 3: predict, then run

Answer **before** you run anything. Which books does this keep?

```java
filter(books, isLong.negate().and(isRecent))
```

Then check by adding it to the end of `main`, with the heading `Not long, but recent:`.

<details>
<summary>Solution</summary>

**Klara and the Sun and Never Let Me Go.** Read it left to right: `isLong.negate()` is "not long", and `.and(isRecent)` adds "and recent". The recent books are *Klara and the Sun*, *Wolf Hall*, *The Goldfinch* and *Never Let Me Go*. Of those, *Wolf Hall* and *The Goldfinch* are long, so they're dropped.

```java
        print("Not long, but recent:", filter(books, isLong.negate().and(isRecent)));
```

```
Not long, but recent:
  Book[title=Klara and the Sun, author=Kazuo Ishiguro, year=2021, pages=307]
  Book[title=Never Let Me Go, author=Kazuo Ishiguro, year=2005, pages=288]
```

The answer is certain: the data is fixed, and each book is either over 400 pages or not, and either from 2000 or later or not. `filter` keeps the books in their original order.

</details>

---

## Checkpoint

Run `java Main.java` from `workspace/java-basics/lambdas/`. Without the exercises' extra sections, you should see exactly this:

```
Long books:
  Book[title=Emma, author=Jane Austen, year=1815, pages=474]
  Book[title=Dune, author=Frank Herbert, year=1965, pages=412]
  Book[title=Pride and Prejudice, author=Jane Austen, year=1813, pages=432]
  Book[title=Wolf Hall, author=Hilary Mantel, year=2009, pages=653]
  Book[title=The Goldfinch, author=Donna Tartt, year=2013, pages=771]
Before 1950:
  Book[title=Emma, author=Jane Austen, year=1815, pages=474]
  Book[title=Nineteen Eighty-Four, author=George Orwell, year=1949, pages=328]
  Book[title=The Hobbit, author=J.R.R. Tolkien, year=1937, pages=310]
  Book[title=Pride and Prejudice, author=Jane Austen, year=1813, pages=432]
  Book[title=Animal Farm, author=George Orwell, year=1945, pages=112]
Short books:
  Book[title=Nineteen Eighty-Four, author=George Orwell, year=1949, pages=328]
  Book[title=The Hobbit, author=J.R.R. Tolkien, year=1937, pages=310]
  Book[title=Klara and the Sun, author=Kazuo Ishiguro, year=2021, pages=307]
  Book[title=Animal Farm, author=George Orwell, year=1945, pages=112]
  Book[title=Never Let Me Go, author=Kazuo Ishiguro, year=2005, pages=288]
Long titles: [Emma, Dune, Pride and Prejudice, Wolf Hall, The Goldfinch]
Their authors: [Jane Austen, Frank Herbert, Jane Austen, Hilary Mantel, Donna Tartt]
Short titles:
  Nineteen Eighty-Four
  The Hobbit
  Klara and the Sun
  Animal Farm
  Never Let Me Go
First shelf: 5 books
Second shelf: 10 books
Shouted: [EMMA, DUNE, PRIDE AND PREJUDICE, WOLF HALL, THE GOLDFINCH]
New edition: Book[title=Emma, author=Jane Austen, year=1815, pages=500]
Original:    Book[title=Emma, author=Jane Austen, year=1815, pages=474]
Long and recent:
  Book[title=Wolf Hall, author=Hilary Mantel, year=2009, pages=653]
  Book[title=The Goldfinch, author=Donna Tartt, year=2013, pages=771]
Long or recent:
  Book[title=Emma, author=Jane Austen, year=1815, pages=474]
  Book[title=Klara and the Sun, author=Kazuo Ishiguro, year=2021, pages=307]
  Book[title=Dune, author=Frank Herbert, year=1965, pages=412]
  Book[title=Pride and Prejudice, author=Jane Austen, year=1813, pages=432]
  Book[title=Wolf Hall, author=Hilary Mantel, year=2009, pages=653]
  Book[title=The Goldfinch, author=Donna Tartt, year=2013, pages=771]
  Book[title=Never Let Me Go, author=Kazuo Ishiguro, year=2005, pages=288]
Not long:
  Book[title=Nineteen Eighty-Four, author=George Orwell, year=1949, pages=328]
  Book[title=The Hobbit, author=J.R.R. Tolkien, year=1937, pages=310]
  Book[title=Klara and the Sun, author=Kazuo Ishiguro, year=2021, pages=307]
  Book[title=Animal Farm, author=George Orwell, year=1945, pages=112]
  Book[title=Never Let Me Go, author=Kazuo Ishiguro, year=2005, pages=288]
Recent, shouted: [KLARA AND THE SUN, WOLF HALL, THE GOLDFINCH, NEVER LET ME GO]
```

Your folder should look like this, with `BookFilter.java` gone:

```
workspace/java-basics/lambdas/
├── Book.java
├── Books.java
└── Main.java
```

`Main.java`:

```java
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public class Main {

    public static void main(String[] args) {
        List<Book> books = Books.sample();

        Predicate<Book> isLong = book -> book.pages() > 400;

        print("Long books:", filter(books, isLong));
        print("Before 1950:", filter(books, book -> book.year() < 1950));

        List<Book> shortBooks = Books.sample();
        shortBooks.removeIf(isLong);
        print("Short books:", shortBooks);

        Function<Book, String> toTitle = book -> book.title();
        List<String> titles = map(filter(books, isLong), toTitle);
        System.out.println("Long titles: " + titles);
        System.out.println("Their authors: " + map(filter(books, isLong), book -> book.author()));

        Consumer<Book> printTitle = book -> System.out.println("  " + book.title());
        System.out.println("Short titles:");
        shortBooks.forEach(printTitle);

        Supplier<List<Book>> shelf = () -> Books.sample();
        List<Book> first = shelf.get();
        List<Book> second = shelf.get();
        first.removeIf(isLong);
        System.out.println("First shelf: " + first.size() + " books");
        System.out.println("Second shelf: " + second.size() + " books");

        UnaryOperator<String> shout = title -> title.toUpperCase();
        titles.replaceAll(shout);
        System.out.println("Shouted: " + titles);

        BiFunction<Book, Integer, Book> newEdition = (book, extraPages) ->
                new Book(book.title(), book.author(), book.year(), book.pages() + extraPages);
        Book emma = books.get(0);
        Book bigEmma = newEdition.apply(emma, 26);
        System.out.println("New edition: " + bigEmma);
        System.out.println("Original:    " + emma);

        Predicate<Book> isRecent = book -> book.year() >= 2000;

        print("Long and recent:", filter(books, isLong.and(isRecent)));
        print("Long or recent:", filter(books, isLong.or(isRecent)));
        print("Not long:", filter(books, isLong.negate()));

        Function<Book, String> toShoutedTitle = toTitle.andThen(shout);
        System.out.println("Recent, shouted: " + map(filter(books, isRecent), toShoutedTitle));
    }

    static List<Book> filter(List<Book> books, Predicate<Book> filter) {
        List<Book> result = new ArrayList<>();
        for (Book book : books) {
            if (filter.test(book)) {
                result.add(book);
            }
        }
        return result;
    }

    static List<String> map(List<Book> books, Function<Book, String> function) {
        List<String> result = new ArrayList<>();
        for (Book book : books) {
            result.add(function.apply(book));
        }
        return result;
    }

    static void print(String heading, List<Book> books) {
        System.out.println(heading);
        for (Book book : books) {
            System.out.println("  " + book);
        }
    }
}
```

`Book.java` and `Books.java` are as shown in step 1.

---

## Common errors

**`error: cannot find symbol` … `symbol: method apply(Book)` … `of type Predicate<Book>`**

```
Main.java:6: error: cannot find symbol
        System.out.println(isLong.apply(Books.sample().get(0)));
                                 ^
  symbol:   method apply(Book)
  location: variable isLong of type Predicate<Book>
```

You called the wrong method for the interface. A `Predicate`'s method is `test`, not `apply`. Each interface has its own name: `test` for `Predicate`, `apply` for `Function`, `UnaryOperator` and `BiFunction`, `accept` for `Consumer`, and `get` for `Supplier` (the Concepts table). The `location` line tells you the variable's type, so you know which one to look up.

**`error: unexpected type` … `required: reference` … `found: int`**

```
Main.java:5: error: unexpected type
        Predicate<int> isBig = n -> n > 400;
                  ^
  required: reference
  found:    int
```

A primitive type, such as `int`, can't be a type argument. "Reference" means a class type. Use the wrapper class instead: `Predicate<Integer>`. (`IntPredicate` exists too; see the sidebar after step 6.)

**`error: wrong number of type arguments; required 2`**

```
Main.java:5: error: wrong number of type arguments; required 2
        Function<Book> title = book -> book.title();
                ^
```

`Function` needs **two** type arguments, the input and the result: `Function<Book, String>`. `BiFunction` needs three. `Predicate`, `Consumer`, `Supplier` and `UnaryOperator` need one.

**`error: method removeIf in interface Collection<E> cannot be applied to given types` … `BookFilter cannot be converted to Predicate<? super Book>`**

You passed an object of your own interface to a method that takes a JDK one (step 2). Interfaces with the same shape still aren't interchangeable. Declare the variable as `Predicate<Book>` instead, or write the lambda straight into the call.

**No error, but a combined predicate filters the same as before**

```java
        isLong.and(isRecent);
```

`and`, `or`, `negate` and `andThen` **return** a new predicate or function. They never change the one you call them on. Called as a statement like this, the result is thrown away (exercise 2). Store it in a variable, or pass it straight to the method that needs it.

**`Exception in thread "main" java.lang.UnsupportedOperationException`, at `ImmutableCollections…replaceAll`**

```
Exception in thread "main" java.lang.UnsupportedOperationException
	at java.base/java.util.ImmutableCollections.uoe(ImmutableCollections.java:159)
	at java.base/java.util.ImmutableCollections$AbstractImmutableList.replaceAll(ImmutableCollections.java:280)
	at Main.main(Main.java:6)
```

You called `replaceAll` on a list made with `List.of(...)`, which can't be changed. It's the same problem as `removeIf` and `sort` in guide 01. Copy it into a changeable list first, `new ArrayList<>(List.of(...))`, or use a list your own code built, as `map` does.

---

## Recap

- **`java.util.function`** is the JDK's standard set of functional interfaces. Using them instead of your own means your lambdas fit the JDK's methods, and anyone else's.
- **`Predicate<Book>`** replaced `BookFilter` with three type changes and no change to any lambda, because a lambda takes its type from where it's used. The same `isLong` then worked with `removeIf`.
- The core six, by shape: **`Predicate`** (`T` → `boolean`, `test`), **`Function`** (`T` → `R`, `apply`), **`Consumer`** (`T` → nothing, `accept`), **`Supplier`** (nothing → `T`, `get`), **`UnaryOperator`** (`T` → `T`, `apply`) and **`BiFunction`** (`T` and `U` → `R`, `apply`).
- **Read type arguments as inputs first, result last.** `Function<Book, String>` is a function from `Book` to `String`. Type arguments are always classes, so `Integer`, never `int`.
- **A `Supplier`'s lambda runs each time `get()` is called**, not when it's created.
- **Default methods** let a functional interface offer more than its one abstract method. **`and`**, **`or`** and **`negate`** combine predicates, and **`andThen`** chains functions.
- Every one of those **returns something new**. Nothing is changed in place, so keep the result.
- **`Comparator`** and **`Runnable`** were functional interfaces before `java.util.function` existed, and take lambdas just the same.

### Interview angle

<details>
<summary>"Name the main functional interfaces in <code>java.util.function</code>, and their methods."</summary>

`Predicate<T>` takes a `T` and returns a `boolean`, with `test`. `Function<T, R>` takes a `T` and returns an `R`, with `apply`. `Consumer<T>` takes a `T` and returns nothing, with `accept`. `Supplier<T>` takes nothing and returns a `T`, with `get`. Then there are variations: `UnaryOperator<T>` is a `Function<T, T>`, `BinaryOperator<T>` takes two `T`s and returns a `T`, and `BiFunction`, `BiPredicate` and `BiConsumer` take two inputs. A good answer groups them by shape, what goes in and what comes out, rather than reciting the list.

</details>

<details>
<summary>"Why does <code>java.util.function</code> exist? Why not just write your own interfaces?"</summary>

So that code agrees on one type for each common shape. Before Java 8, every library that wanted "a test of something" or "a callback" wrote its own interface, and none of them fitted each other. A lambda can implement any functional interface, but once it's stored in a variable, its type is fixed, and Java doesn't convert between interfaces just because they have the same shape. With a standard vocabulary, a `Predicate<Book>` written for one method can be passed to any other method that takes a `Predicate<Book>`, including the JDK's own, such as `removeIf`. You still write your own when the standard ones don't fit: for example, when the method needs to throw a checked exception, which guide 04 covers.

</details>

<details>
<summary>"How can a functional interface have methods like <code>and</code> or <code>andThen</code> if it only has one method?"</summary>

It has only one **abstract** method. `and`, `or`, `negate` and `andThen` are **default** methods: they have bodies, written in the interface itself, so every implementation gets them, lambdas included, and they don't count towards the "one abstract method" rule. Each one returns a new lambda that calls the original. `Predicate.and`, for example, returns `t -> test(t) && other.test(t)`. Default methods were added in Java 8, at the same time as lambdas, partly so that interfaces like these could have them, and so that existing interfaces such as `Collection` and `Iterable` could gain methods like `removeIf` and `forEach` without breaking every class that already implemented them.

</details>

<details>
<summary>"Why are there interfaces like <code>IntPredicate</code> and <code>ToIntFunction</code>?"</summary>

To avoid **boxing**. Type arguments must be classes, so a `Predicate<Integer>` has to wrap each `int` in an `Integer` object before testing it, and a `Function<Book, Integer>` has to wrap each result. In a hot loop that's extra objects to create and collect. The primitive specialisations, such as `IntPredicate`, `ToIntFunction`, `IntSupplier` and the `int`, `long` and `double` versions of the others, work on primitives directly. They matter for performance-sensitive code and for the Streams API. For everyday code with small lists, the general versions are fine.

</details>

### Further reading

- [`java.util.function` package summary (Java SE 25 API)](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/function/package-summary.html): every interface in the package, grouped by shape
- [`Predicate` (Java SE 25 API)](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/function/Predicate.html): `test`, and the default methods `and`, `or` and `negate`
- [`Function` (Java SE 25 API)](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/function/Function.html): `apply`, `andThen`, and its mirror image `compose`
- [`List.replaceAll` (Java SE 25 API)](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/List.html#replaceAll(java.util.function.UnaryOperator)): the `UnaryOperator` method from step 6
- [Default Methods](https://docs.oracle.com/javase/tutorial/java/IandI/defaultmethods.html), from *The Java Tutorials*: how default methods work, and why they were added

**Simplification to notice:** `filter` and `map` only work on a `List<Book>`, and `map` only produces strings. The JDK's versions, in the Streams API, work on any type, because they're **generic methods** with their own type parameters. This series stays with `Book` so you only ever have to read generic types, not write them.

**Next:** [03: Method references](03-method-references.md). Shorten `book -> book.title()` to `Book::title`, meet the four kinds of method reference, and sort the reading list by author, then by year, with `Comparator.comparing`.
