# 01: Your first lambdas

> Written for **Java 25**. Uses only the JDK: no build tool and no IDE. See the [series README](README.md) for setup.

## What you'll learn

- Why you'd want to pass **behaviour**, not just values, into a method
- How an **interface** lets you do that, first with a named class and then with an **anonymous class**
- How a **lambda** does the same job in one line, and what each part of it means
- What makes an interface a **functional interface**, and what **`@FunctionalInterface`** checks
- The **syntax forms** a lambda can take: with or without types, with an expression or a block
- How to use lambdas with methods the JDK already has: **`removeIf`**, **`forEach`** and **`sort`**

By the end you'll have one `filter` method that can pick out any books you like, depending on the lambda you hand it, and a reading list you can trim, print and sort with one line each.

---

## Prerequisites for this guide

This is the first guide in the series. It assumes you're comfortable with everyday Java:

- **Classes and objects:** fields, constructors, methods, `new`.
- **Interfaces**, and how a class implements one.
- **Lists and loops:** `List`, `ArrayList`, the enhanced `for` loop.
- **Records:** a one-line `record` declaration, and its accessor methods.
- **Using generic types** such as `List<Book>`. You don't need to have written a generic class of your own.

You don't need to have seen a lambda before, or an anonymous class.

You'll also need **JDK 25**, with `java` on your `PATH`. The [series README](README.md#before-you-start) shows how. Check with `java -version`.

---

## Concepts

### Passing behaviour, not just values

Most methods take **values**: a number, a string, a list. The method's code is fixed, and the values change what it works on.

Sometimes two methods are the same except for one small piece of **logic**. One method keeps books with more than 400 pages. Another keeps books written before 1950. Both loop over the list, both build a new list, and both return it. The only difference is the test in the `if`. You'd like to write the loop once and pass the test in.

A test isn't a value like `400`. It's a piece of behaviour: "given a book, answer yes or no". Java can't pass a bare piece of code to a method, but it can pass an **object**, and an object can carry behaviour in its methods.

### An interface describes the behaviour

To pass "a test of a book" into a method, you need a type for it. An **interface** is the natural choice:

```java
interface BookFilter {
    boolean test(Book book);
}
```

Any object whose class implements `BookFilter` has a `test` method that takes a book and answers yes or no. A method that takes a `BookFilter` parameter doesn't care which class it gets. It just calls `test`.

That's the whole idea. The rest of this guide is about writing those objects with less and less code.

### What a lambda is

Writing a whole class for a test that's one line long is a lot of ceremony. A **lambda** is a short way of writing an object that implements an interface with exactly one abstract method:

```java
BookFilter isLong = book -> book.pages() > 400;
```

Read it as "given a `book`, return whether `book.pages() > 400`". The left of the arrow, `book`, is the parameter. The right of it is the result.

A lambda isn't a free-floating function. It's still an **object**, and it always has an interface type. Here that type is `BookFilter`, so `isLong.test(someBook)` runs the code to the right of the arrow.

### How the compiler knows what a lambda means

`book -> book.pages() > 400` doesn't say that `book` is a `Book`, or that it implements `BookFilter`. The compiler works that out from **where the lambda is used**. Assigned to a `BookFilter` variable, or passed to a method parameter of type `BookFilter`, the lambda must be a `BookFilter`. `BookFilter`'s only abstract method takes a `Book` and returns a `boolean`, so `book` is a `Book`, and the right-hand side had better be a `boolean`.

The type a lambda gets from where it's used is called its **target type**. A lambda with no target type, somewhere the compiler can't tell which interface it should be, doesn't compile. That's the first entry in Common errors.

---

## Steps

### 1. Set up a folder, and the reading list

Check your setup first ([series README](README.md#before-you-start)): `java -version` should report version 25.

Then, from the `tutorials` folder, create a folder for this series and go into it.

PowerShell:

```powershell
mkdir workspace\java-basics\lambdas
cd workspace\java-basics\lambdas
```

Git Bash, macOS or Linux:

```bash
mkdir -p workspace/java-basics/lambdas
cd workspace/java-basics/lambdas
```

Every command in this series runs from that folder.

Create `Book.java`:

```java
record Book(String title, String author, int year, int pages) {}
```

Create `Books.java`, which holds the reading list:

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

You'll keep these two files, unchanged, for the whole series.

`sample()` builds a **new** list every time you call it. `List.of(...)` makes a list that can't be changed, and copying it into a `new ArrayList<>(...)` gives you one that can. That matters in step 8, where you'll remove books from the list and sort it.

Create `Main.java`:

```java
import java.util.List;

public class Main {

    public static void main(String[] args) {
        List<Book> books = Books.sample();

        for (Book book : books) {
            System.out.println(book);
        }
    }
}
```

Run it:

```bash
java Main.java
```

```
Book[title=Emma, author=Jane Austen, year=1815, pages=474]
Book[title=Nineteen Eighty-Four, author=George Orwell, year=1949, pages=328]
Book[title=The Hobbit, author=J.R.R. Tolkien, year=1937, pages=310]
Book[title=Klara and the Sun, author=Kazuo Ishiguro, year=2021, pages=307]
Book[title=Dune, author=Frank Herbert, year=1965, pages=412]
Book[title=Pride and Prejudice, author=Jane Austen, year=1813, pages=432]
Book[title=Wolf Hall, author=Hilary Mantel, year=2009, pages=653]
Book[title=Animal Farm, author=George Orwell, year=1945, pages=112]
Book[title=The Goldfinch, author=Donna Tartt, year=2013, pages=771]
Book[title=Never Let Me Go, author=Kazuo Ishiguro, year=2005, pages=288]
```

Ten books, printed the way every record prints itself. `java Main.java` compiles `Main.java` in memory, finds `Book.java` and `Books.java` in the same folder because `Main` uses them, and runs it. There's no separate `javac` step.

Nothing in this series is random and nothing depends on timing, so **your output should match this exactly**, line for line. That's true of every output block in the series unless it says otherwise.

### 2. The problem: two methods that differ by one line

Say you want two lists: the long books (more than 400 pages), and the books written before 1950. Replace `Main.java`:

```java
import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) {
        List<Book> books = Books.sample();

        print("Long books:", longBooks(books));
        print("Before 1950:", booksBefore1950(books));
    }

    static List<Book> longBooks(List<Book> books) {
        List<Book> result = new ArrayList<>();
        for (Book book : books) {
            if (book.pages() > 400) {
                result.add(book);
            }
        }
        return result;
    }

    static List<Book> booksBefore1950(List<Book> books) {
        List<Book> result = new ArrayList<>();
        for (Book book : books) {
            if (book.year() < 1950) {
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

`print` is a small helper that prints a heading and then each book, indented. You'll use it for the rest of the guide.

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

It works. But look at `longBooks` and `booksBefore1950` side by side. They're eight lines each, and seven of the eight are the same. Only the condition in the `if` differs. A third list, say books by one author, would mean a third copy of the loop.

The loop is the part that stays the same. The test is the part that changes. The next step pulls the test out, so the loop can be written once.

### 3. Pass the test in

Create `BookFilter.java`:

```java
interface BookFilter {
    boolean test(Book book);
}
```

This is the interface from Concepts: anything that can answer "does this book pass?".

Now replace `longBooks` and `booksBefore1950` with **one** method that takes the list and a `BookFilter`. Here's the whole of `Main.java`, with two small classes at the bottom of the file that implement `BookFilter`:

```java
import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) {
        List<Book> books = Books.sample();

        print("Long books:", filter(books, new LongBookFilter()));
        print("Before 1950:", filter(books, new Before1950Filter()));
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

class LongBookFilter implements BookFilter {
    @Override
    public boolean test(Book book) {
        return book.pages() > 400;
    }
}

class Before1950Filter implements BookFilter {
    @Override
    public boolean test(Book book) {
        return book.year() < 1950;
    }
}
```

Run it. **The output is exactly the same as step 2's.**

What changed:

- **`filter`** contains the loop, once. Where the old methods had a fixed condition, it calls `filter.test(book)`, and whatever object you passed in decides the answer.
- **`LongBookFilter` and `Before1950Filter`** each hold one test. `new LongBookFilter()` creates an object that carries the "more than 400 pages" behaviour into `filter`.
- **`test` is `public`** in both classes. Methods declared in an interface are public, so the methods that implement them must be too.

This is the shape every lambda in the series builds on: a method that takes an interface, and an object that supplies the behaviour. But there's a cost. Every new test needs a new class, with a name, a declaration and an `@Override`, all wrapped around one line of real logic.

### 4. The old way: an anonymous class

Before lambdas arrived in Java 8, the usual way to avoid a named class for each test was an **anonymous class**: a class with no name, declared and instantiated in one go, right where it's needed.

In `main`, replace the `Long books` line with this:

```java
        BookFilter isLong = new BookFilter() {
            @Override
            public boolean test(Book book) {
                return book.pages() > 400;
            }
        };

        print("Long books:", filter(books, isLong));
```

Then delete the `LongBookFilter` class from the bottom of the file. Leave `Before1950Filter` for now.

Run it. The output is the same again.

`new BookFilter() { ... }` reads as "create an object of a new, unnamed class that implements `BookFilter`, with this body". The test now sits next to the code that uses it, and there's no extra class to name.

You'll see anonymous classes in older code, which is why it's worth recognising one. But look how much of it is boilerplate: `new BookFilter()`, `@Override`, `public boolean test(Book book)`, the `return`, and two sets of braces. The one line that matters is `book.pages() > 400`.

### 5. The lambda

Replace the anonymous class with a lambda:

```java
        BookFilter isLong = book -> book.pages() > 400;
```

And replace `new Before1950Filter()` with a lambda written straight into the call:

```java
        print("Before 1950:", filter(books, book -> book.year() < 1950));
```

Delete the `Before1950Filter` class. `Main.java` now looks like this:

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

Run it. The output is still exactly the same as step 2's.

Here's what the lambda left out of the anonymous class, and why the compiler doesn't need it:

| Anonymous class | Lambda | Why it can go |
| --- | --- | --- |
| `new BookFilter()` | *(gone)* | The target type, `BookFilter`, comes from the variable or parameter the lambda is given to |
| `@Override public boolean test` | *(gone)* | `BookFilter` has only one abstract method, so there's only one method the lambda can be |
| `(Book book)` | `book` | The parameter's type comes from `test`'s declaration |
| `{ return book.pages() > 400; }` | `book.pages() > 400` | With a single expression after the arrow, its value is what's returned |

Two lambdas, two lines, and no extra classes. To filter by anything else, you write another lambda. `filter` never changes.

Notice the second lambda has no variable. It's written straight into the argument list of `filter`, which is how you'll usually see lambdas: passed directly to the method that uses them.

### 6. What makes a functional interface

A lambda can only implement an interface with **exactly one abstract method**. Such an interface is called a **functional interface**. `BookFilter` is one: `test` is its only method.

Java has an annotation that states this on purpose. Add it to `BookFilter.java`:

```java
@FunctionalInterface
interface BookFilter {
    boolean test(Book book);
}
```

Run `java Main.java`. Nothing changes: `BookFilter` already was a functional interface. The annotation is a **promise**, and the compiler holds you to it.

To see what it protects you from, add a second abstract method to `BookFilter`:

```java
@FunctionalInterface
interface BookFilter {
    boolean test(Book book);
    boolean alsoTest(Book book);
}
```

Run `java Main.java`:

```
Main.java:9: error: incompatible types: BookFilter is not a functional interface
        BookFilter isLong = book -> book.pages() > 400;
                            ^
    multiple non-overriding abstract methods found in interface BookFilter
Main.java:12: error: method filter in class Main cannot be applied to given types;
        print("Before 1950:", filter(books, book -> book.year() < 1950));
                              ^
  required: List<Book>,BookFilter
  found:    List<Book>,(book)->bo[...] 1950
  reason: argument mismatch; BookFilter is not a functional interface
      multiple non-overriding abstract methods found in interface BookFilter
2 errors
error: compilation failed
```

Both lambdas break: with two abstract methods, a lambda can't say which one it's implementing. Those errors appear in `Main.java`, where the lambdas are, whether or not `BookFilter` has the annotation.

What the annotation adds is an error **on the interface itself**, even if no lambda uses it yet. You can see it by compiling `BookFilter.java` on its own:

```bash
java BookFilter.java
```

```
BookFilter.java:1: error: Unexpected @FunctionalInterface annotation
@FunctionalInterface
^
  BookFilter is not a functional interface
    multiple non-overriding abstract methods found in interface BookFilter
1 error
error: compilation failed
```

(`java BookFilter.java` stops at the compile error. On a correct `BookFilter` it would complain instead that there's no `main` method to run, which is fine: you only used it to compile the one file.)

In a bigger project, that's the difference between the person who changes the interface seeing the error, and someone else finding all their lambdas broken later. Put `@FunctionalInterface` on any interface you mean lambdas to implement.

**Remove `alsoTest`** before you go on, and check `java Main.java` runs again.

> **Sidebar: other methods are allowed.** "Exactly one abstract method" leaves room for more. A functional interface can also have `default` methods, which have a body, and `static` methods. Neither counts, because a lambda doesn't need to supply them. Guide 02 relies on this: the JDK's own functional interfaces have several `default` methods each.

### 7. The syntax forms

`book -> book.pages() > 400` is the shortest way to write that lambda, but not the only one. These four are the same `BookFilter`:

```java
BookFilter a = (Book book) -> book.pages() > 400;
BookFilter b = (book) -> book.pages() > 400;
BookFilter c = book -> book.pages() > 400;
BookFilter d = book -> {
    return book.pages() > 400;
};
```

- **`a`** declares the parameter's type, as a method would. You rarely need to, because the compiler infers it.
- **`b`** drops the type but keeps the parentheses.
- **`c`** drops the parentheses too. That's allowed only with **one** parameter and no declared type.
- **`d`** has a **block body**: braces, statements, and an explicit `return`. Use one when the lambda needs more than a single expression.

With an expression body (`a`, `b`, `c`), the expression's value is returned for you, and writing `return` there is an error. With a block body (`d`), you must `return` a value yourself, just as in a method.

Use a block body for real. Add this to `main`, after the two `print` lines:

```java
        BookFilter isMidCentury = book -> {
            int year = book.year();
            return year >= 1930 && year < 1970;
        };

        print("Mid-century:", filter(books, isMidCentury));
```

Run it. The first two sections are the same as before. The new section at the end is:

```
Mid-century:
  Book[title=Nineteen Eighty-Four, author=George Orwell, year=1949, pages=328]
  Book[title=The Hobbit, author=J.R.R. Tolkien, year=1937, pages=310]
  Book[title=Dune, author=Frank Herbert, year=1965, pages=412]
  Book[title=Animal Farm, author=George Orwell, year=1945, pages=112]
```

The block declares a local variable, `year`, and returns a result built from it. Anything you can write in a method body, you can write in a block body.

The number of parameters changes the form too. `BookFilter` takes one, but interfaces can take none or several:

| Parameters | Form | Example |
| --- | --- | --- |
| None | Empty parentheses are required | `() -> "Untitled"` |
| One | Parentheses are optional | `book -> book.title()` |
| Two or more | Parentheses are required, commas between | `(a, b) -> a.pages() + b.pages()` |

You'll write a two-parameter lambda in step 9. The zero-parameter form needs an interface whose method takes nothing, and guide 02 introduces one from the JDK.

> **Sidebar: `_` for a parameter you don't use.** Sometimes an interface hands a lambda a parameter it doesn't need. Since Java 22 you can name it `_`, as in `(book, _) -> book.title()`. The underscore says "deliberately ignored", and you can't refer to it in the body. It isn't needed anywhere in this series, but you'll meet it in newer code.

### 8. Lambdas the JDK already accepts: `removeIf` and `forEach`

`filter` is your method, but the JDK's collections have methods that take lambdas too. Two of them:

- **`removeIf`** takes a test, runs it on every element, and **removes** the ones it answers `true` for. It changes the list itself.
- **`forEach`** takes an action, and runs it on every element, in order. It returns nothing.

Add this to the end of `main`:

```java
        List<Book> shelf = Books.sample();
        shelf.removeIf(book -> book.year() < 1950);

        System.out.println("On the shelf:");
        shelf.forEach(book -> System.out.println("  " + book));
```

The new section is:

```
On the shelf:
  Book[title=Klara and the Sun, author=Kazuo Ishiguro, year=2021, pages=307]
  Book[title=Dune, author=Frank Herbert, year=1965, pages=412]
  Book[title=Wolf Hall, author=Hilary Mantel, year=2009, pages=653]
  Book[title=The Goldfinch, author=Donna Tartt, year=2013, pages=771]
  Book[title=Never Let Me Go, author=Kazuo Ishiguro, year=2005, pages=288]
```

Five books written before 1950 have gone, and five are left.

`shelf` is a fresh list from `Books.sample()`, not `books`. `removeIf` changes the list you call it on, and the earlier lines still want all ten books. If `sample()` had returned the `List.of(...)` list directly, without copying it into an `ArrayList`, this line would throw an exception, because that kind of list can't be changed. Common errors shows the message.

The lambdas here aren't `BookFilter`s. `removeIf` was written long before your interface existed. It takes a functional interface **from the JDK**, with a single method that takes an element and returns a `boolean`: the same shape as `BookFilter`. `forEach` takes one whose method takes an element and returns nothing. Guide 02 names both. For now, notice that you didn't need to know their names to write the lambdas. The target type did that work for you.

### 9. Sorting: a lambda with two parameters

To sort a list, `sort` needs to know which of two elements comes first. It asks a **`Comparator`**, an interface from `java.util` that was around long before lambdas. Its one abstract method is:

```java
int compare(T a, T b);
```

(`T` is the type of element being sorted. For your list, it's `Book`.) The contract is:

- **a negative number** if `a` comes before `b`
- **zero** if they're equal for sorting purposes
- **a positive number** if `a` comes after `b`

`Integer.compare(x, y)` follows the same contract for two `int`s, so it does most of the work. Add this to the end of `main`:

```java
        shelf.sort((a, b) -> Integer.compare(a.pages(), b.pages()));
        print("Sorted by pages:", shelf);

        shelf.sort((a, b) -> a.title().compareTo(b.title()));
        print("Sorted by title:", shelf);
```

The new sections are:

```
Sorted by pages:
  Book[title=Never Let Me Go, author=Kazuo Ishiguro, year=2005, pages=288]
  Book[title=Klara and the Sun, author=Kazuo Ishiguro, year=2021, pages=307]
  Book[title=Dune, author=Frank Herbert, year=1965, pages=412]
  Book[title=Wolf Hall, author=Hilary Mantel, year=2009, pages=653]
  Book[title=The Goldfinch, author=Donna Tartt, year=2013, pages=771]
Sorted by title:
  Book[title=Dune, author=Frank Herbert, year=1965, pages=412]
  Book[title=Klara and the Sun, author=Kazuo Ishiguro, year=2021, pages=307]
  Book[title=Never Let Me Go, author=Kazuo Ishiguro, year=2005, pages=288]
  Book[title=The Goldfinch, author=Donna Tartt, year=2013, pages=771]
  Book[title=Wolf Hall, author=Hilary Mantel, year=2009, pages=653]
```

- **`(a, b) -> ...`** is the two-parameter form from step 7, with the parentheses it needs. The compiler infers that both are `Book`s, because `shelf` is a `List<Book>`.
- **`Integer.compare(a.pages(), b.pages())`** is negative when `a` has fewer pages, so shorter books come first.
- **`a.title().compareTo(b.title())`** does the same for strings. `String` already has a `compareTo` with the comparator contract, and it sorts alphabetically. It compares the whole title, `The` included, which is why *The Goldfinch* sits under **T**.
- **`sort` changes the list itself**, like `removeIf`. That's another reason `shelf` is a fresh, changeable list.

To sort the other way, swap `a` and `b` on the right of the arrow. That's exercise 3.

That's the program for this guide. In guide 02, you'll swap `BookFilter` for the JDK's own version of it, and meet the rest of the JDK's functional interfaces.

---

## Your turn

### Exercise 1: one author's books

Add a section to the end of `main` that prints George Orwell's books, using `filter` and a lambda. Give it the heading `By George Orwell:`.

<details>
<summary>Solution</summary>

Add this at the end of `main`:

```java
        print("By George Orwell:", filter(books, book -> book.author().equals("George Orwell")));
```

The new section is:

```
By George Orwell:
  Book[title=Nineteen Eighty-Four, author=George Orwell, year=1949, pages=328]
  Book[title=Animal Farm, author=George Orwell, year=1945, pages=112]
```

It's the same shape as `book -> book.year() < 1950` from step 5: a lambda that takes a book and returns a `boolean`. Use `equals` to compare strings, not `==`, as anywhere else in Java.

`filter` didn't change. That's the point of step 3: every new test is one lambda, not one more method.

You can keep this line or remove it. Guide 02 starts from fresh code.

</details>

### Exercise 2: a block body that forgets to return

In `isMidCentury`, delete the word `return` (and the space after it), so the last line of the block is just:

```java
            year >= 1930 && year < 1970;
```

Before you run it, decide: will the compiler complain about a missing `return`, or about something else? Run it, read the error, and fix it. Then fix it a second way, without a block body at all.

<details>
<summary>Solution</summary>

The compiler doesn't mention `return`:

```
Main.java:16: error: not a statement
            year >= 1930 && year < 1970;
                         ^
1 error
error: compilation failed
```

Inside a block body, every line must be a statement, as in a method. `year >= 1930 && year < 1970;` is a comparison whose result goes nowhere, and Java doesn't allow that as a statement. The real problem is the missing `return`, but the compiler trips over the stray expression first.

**Fix 1:** put the `return` back, as in step 7.

**Fix 2:** use an expression body, which returns its value without a `return`:

```java
        BookFilter isMidCentury = book -> book.year() >= 1930 && book.year() < 1970;
```

It calls `book.year()` twice instead of storing it in a local variable, but for something this short, that's a fair trade. Both fixes print the same `Mid-century` section as step 7.

</details>

### Exercise 3: predict, then run

Answer each question **before** you run anything. Then check with a short `main` of your own, or by adding lines to the end of yours.

1. You call `removeIf(book -> book.pages() > 400 || book.year() < 1950)` on a fresh `Books.sample()`. Which books are left?
2. You sort a fresh `Books.sample()` with `(a, b) -> Integer.compare(b.pages(), a.pages())`. Which book comes first, and which comes last?

<details>
<summary>Solution</summary>

1. **Klara and the Sun and Never Let Me Go.** `removeIf` removes every book the lambda answers `true` for: every long book, and every book before 1950. The only books that are neither are the two recent short ones, Klara and the Sun (2021, 307 pages) and Never Let Me Go (2005, 288 pages). Printed, they keep their order from the original list:

   ```
   Book[title=Klara and the Sun, author=Kazuo Ishiguro, year=2021, pages=307]
   Book[title=Never Let Me Go, author=Kazuo Ishiguro, year=2005, pages=288]
   ```

2. **The Goldfinch first (771 pages), Animal Farm last (112).** Swapping `a` and `b` on the right of the arrow flips the sign of every comparison, so the list sorts longest first:

   ```
   Book[title=The Goldfinch, author=Donna Tartt, year=2013, pages=771]
   Book[title=Animal Farm, author=George Orwell, year=1945, pages=112]
   ```

Both answers are certain. The data is fixed, and no two books have the same page count, so there's only one right order.

</details>

---

## Checkpoint

Run `java Main.java` from `workspace/java-basics/lambdas/`. Without exercise 1's extra section, you should see exactly this:

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
Mid-century:
  Book[title=Nineteen Eighty-Four, author=George Orwell, year=1949, pages=328]
  Book[title=The Hobbit, author=J.R.R. Tolkien, year=1937, pages=310]
  Book[title=Dune, author=Frank Herbert, year=1965, pages=412]
  Book[title=Animal Farm, author=George Orwell, year=1945, pages=112]
On the shelf:
  Book[title=Klara and the Sun, author=Kazuo Ishiguro, year=2021, pages=307]
  Book[title=Dune, author=Frank Herbert, year=1965, pages=412]
  Book[title=Wolf Hall, author=Hilary Mantel, year=2009, pages=653]
  Book[title=The Goldfinch, author=Donna Tartt, year=2013, pages=771]
  Book[title=Never Let Me Go, author=Kazuo Ishiguro, year=2005, pages=288]
Sorted by pages:
  Book[title=Never Let Me Go, author=Kazuo Ishiguro, year=2005, pages=288]
  Book[title=Klara and the Sun, author=Kazuo Ishiguro, year=2021, pages=307]
  Book[title=Dune, author=Frank Herbert, year=1965, pages=412]
  Book[title=Wolf Hall, author=Hilary Mantel, year=2009, pages=653]
  Book[title=The Goldfinch, author=Donna Tartt, year=2013, pages=771]
Sorted by title:
  Book[title=Dune, author=Frank Herbert, year=1965, pages=412]
  Book[title=Klara and the Sun, author=Kazuo Ishiguro, year=2021, pages=307]
  Book[title=Never Let Me Go, author=Kazuo Ishiguro, year=2005, pages=288]
  Book[title=The Goldfinch, author=Donna Tartt, year=2013, pages=771]
  Book[title=Wolf Hall, author=Hilary Mantel, year=2009, pages=653]
```

Your folder should look like this:

```
workspace/java-basics/lambdas/
├── Book.java
├── BookFilter.java
├── Books.java
└── Main.java
```

`BookFilter.java`:

```java
@FunctionalInterface
interface BookFilter {
    boolean test(Book book);
}
```

`Main.java`:

```java
import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) {
        List<Book> books = Books.sample();

        BookFilter isLong = book -> book.pages() > 400;

        print("Long books:", filter(books, isLong));
        print("Before 1950:", filter(books, book -> book.year() < 1950));

        BookFilter isMidCentury = book -> {
            int year = book.year();
            return year >= 1930 && year < 1970;
        };

        print("Mid-century:", filter(books, isMidCentury));

        List<Book> shelf = Books.sample();
        shelf.removeIf(book -> book.year() < 1950);

        System.out.println("On the shelf:");
        shelf.forEach(book -> System.out.println("  " + book));

        shelf.sort((a, b) -> Integer.compare(a.pages(), b.pages()));
        print("Sorted by pages:", shelf);

        shelf.sort((a, b) -> a.title().compareTo(b.title()));
        print("Sorted by title:", shelf);
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

`Book.java` and `Books.java` are as you wrote them in step 1.

---

## Common errors

**`Exception in thread "main" java.lang.UnsupportedOperationException`, at `ImmutableCollections…removeIf` or `…sort`**

```
Exception in thread "main" java.lang.UnsupportedOperationException
	at java.base/java.util.ImmutableCollections.uoe(ImmutableCollections.java:159)
	at java.base/java.util.ImmutableCollections$AbstractImmutableCollection.removeIf(ImmutableCollections.java:169)
	at Main.main(Main.java:8)
```

You called `removeIf` (or `sort`) on a list made with `List.of(...)`, which can't be changed. There's no message after the exception's name, so the stack trace is your only clue: `ImmutableCollections` means an unchangeable list. Copy it into a changeable one first, `new ArrayList<>(List.of(...))`, as `Books.sample()` does (step 1).

**`error: incompatible types: Object is not a functional interface`**

```
Main.java:3: error: incompatible types: Object is not a functional interface
        Object filter = book -> true;
                        ^
```

The lambda has no target type it can implement. A lambda must be assigned or passed to a **functional interface** type, so the compiler knows which method it's writing. Here the variable is an `Object`. Give it the interface type: `BookFilter filter = book -> true;`.

**`error: not a statement`, inside a lambda's braces**

A block-body lambda contains a bare expression where a statement should be, usually because the `return` is missing (exercise 2). Add `return`, or drop the braces and use an expression body.

**`error: incompatible types: bad return type in lambda expression` … `missing return value`**

```
Main.java:3: error: incompatible types: bad return type in lambda expression
        BookFilter isLong = book -> {
                            ^
    missing return value
```

A block-body lambda has a path that ends without returning anything, for example an `if` that returns `true` with no `return false` after it. Every path through a block body must return a value, just as in a method.

**`error: illegal start of expression`, at `return`**

```
Main.java:3: error: illegal start of expression
        BookFilter isLong = book -> return book.pages() > 400;
                                    ^
```

You wrote `return` in an expression body. Without braces, the expression's value is returned for you. Either drop `return`, or add braces and a semicolon: `book -> { return book.pages() > 400; }`.

**`error: incompatible types: BookFilter is not a functional interface` … `multiple non-overriding abstract methods found`**

The interface has more than one abstract method, so a lambda can't say which one it implements (step 6). Remove the extra method, or give it a body with `default`.

**`error: Unexpected @FunctionalInterface annotation`**

You put `@FunctionalInterface` on an interface that doesn't have exactly one abstract method. This is the annotation doing its job (step 6). Fix the interface, not the annotation.

---

## Recap

- Sometimes what varies between two methods is **behaviour**, not a value. Pull the shared part into one method, and pass the varying part in as an object that implements an **interface**.
- A **named class** or an **anonymous class** can implement that interface, but both wrap one line of logic in a lot of ceremony.
- A **lambda** is a short way to write an object that implements an interface with exactly one abstract method. `book -> book.pages() > 400` reads as "given a book, return whether it has more than 400 pages".
- An interface with exactly one abstract method is a **functional interface**. **`@FunctionalInterface`** asks the compiler to check that it stays one. `default` and `static` methods don't count.
- A lambda gets its type from where it's used: its **target type**. That's how the compiler knows the parameter types and the return type.
- **Syntax forms:** parameter types are optional, parentheses are optional for exactly one untyped parameter, and an **expression body** returns its value while a **block body** needs `return`.
- The JDK's collections take lambdas too: **`removeIf`** removes what a test accepts, **`forEach`** runs an action on each element, and **`sort`** orders a list with a **`Comparator`** lambda that takes two elements. `removeIf` and `sort` change the list itself, so it must be a changeable one.

### Interview angle

<details>
<summary>"What is a functional interface?"</summary>

An interface with exactly one abstract method. A lambda or method reference can implement it, because there's only one method for the lambda's body to be. `default` and `static` methods don't count towards the one, and nor do abstract methods that match public `Object` methods such as `equals`, since every object already has those. `Comparator` is the classic example: it declares `compare` and `equals`, plus many default and static methods, and it's still a functional interface. The `@FunctionalInterface` annotation is optional. It doesn't make an interface functional, but it makes the compiler report an error if the interface ever stops being one.

</details>

<details>
<summary>"What's the difference between a lambda and an anonymous class?"</summary>

Both create an object that implements an interface, inline. A lambda is far shorter, but it can only implement a **functional** interface, while an anonymous class can implement any interface, or extend a class, and can have several methods and its own fields. There are deeper differences too, including what `this` means inside each, and how they're compiled. Guide 04 covers those. In everyday code, use a lambda wherever the interface is functional, and reach for an anonymous class only when it isn't.

</details>

<details>
<summary>"How does the compiler know the type of a lambda's parameter?"</summary>

From the lambda's **target type**: the type of the variable it's assigned to, the parameter it's passed to, or the value it's returned as. That type must be a functional interface, and its single abstract method fixes the lambda's parameter types and its return type. So in `BookFilter f = book -> book.pages() > 400`, `book` is a `Book` because `BookFilter.test` takes a `Book`. The same lambda text could mean something different with a different target type, and with none at all, for example assigned to `Object`, it doesn't compile.

</details>

<details>
<summary>"Why use <code>Integer.compare(a, b)</code> in a comparator instead of <code>a - b</code>?"</summary>

Subtraction can overflow. With large values of opposite signs, `a - b` can wrap round past `Integer.MAX_VALUE` and come out with the wrong sign, which sorts those elements the wrong way round without any error. `Integer.compare` only compares, so it can't overflow. With page counts the subtraction would happen to work, but `Integer.compare` is correct for every `int`, and it says what it means.

</details>

### Further reading

- [Lambda Expressions](https://docs.oracle.com/javase/tutorial/java/javaOO/lambdaexpressions.html), from *The Java Tutorials*: an older walkthrough that builds up from anonymous classes, as this guide does
- [`FunctionalInterface` (Java SE 25 API)](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/FunctionalInterface.html): the annotation, and the exact rules for what counts
- [`Comparator` (Java SE 25 API)](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Comparator.html): the contract of `compare`. Guide 03 uses many of its other methods
- [`Collection.removeIf` (Java SE 25 API)](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Collection.html#removeIf(java.util.function.Predicate)): including the type of test it takes, which guide 02 explains
- [JEP 456: Unnamed Variables & Patterns](https://openjdk.org/jeps/456): the Java 22 change that allows `_` for an unused lambda parameter
- [The Java Language Specification, §15.27: Lambda Expressions](https://docs.oracle.com/javase/specs/jls/se25/html/jls-15.html#jls-15.27): the full rules, for when you want the exact answer

**Simplification to notice:** `BookFilter` is an interface you wrote, for one job. Every library that wanted "a test of something" would need its own version, and none of them would fit each other's methods. That's why `removeIf` couldn't take your `BookFilter`. The JDK solves this with a standard set of functional interfaces, and guide 02 swaps yours for one of them.

**Next:** [02: Built-in functional interfaces](02-built-in-functional-interfaces.md). Replace `BookFilter` with the JDK's `Predicate`, meet `Function`, `Consumer`, `Supplier` and friends, and combine small lambdas into bigger ones.
