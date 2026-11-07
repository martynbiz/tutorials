# 01: Bank Statements and camt.053

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- Why banks **reconcile**: two records of the same money must agree, every day
- What a **nostro** account is, and why your bank's statement from another bank is a **mirror image** of your own books
- How an ISO 20022 **camt.053** bank statement is structured: balances, entries, credit/debit indicators, booking and value dates, references
- How to read XML with **StAX**, one element at a time, so a large file never has to fit in memory
- What an **XXE** (XML external entity) attack is, how to watch your parser leak a file from the server, and how to stop it
- How to upload a file to a Spring MVC endpoint with **multipart**, and test it with `MockMvcTester`
- Why "the statement must balance" is the first check you run on any file

By the end you'll have an API that takes a camt.053 statement for the bank's nostro account, checks it adds up, and stores every entry ready to be matched against the ledger.

---

## Concepts

### How it works in a real bank

Every bank keeps its own books: the [general ledger](../glossary.md#general-ledger) and the [sub-ledgers](../glossary.md#sub-ledger) that feed it. But almost none of the money in those books is held by the bank alone. Its cash at the central bank, its balances with other banks, its position with each payment scheme: in every case **someone else keeps a record of the same money**.

[Reconciliation](../glossary.md#reconciliation) is the daily job of proving that the two records agree. Each morning the bank receives the other side's record for the previous day (a statement, a settlement report) and matches it, line by line, against its own. Most lines match. The ones that don't are called **breaks**, and every break is investigated until someone can explain it.

Breaks come in two kinds:

- **Timing differences.** Both sides will agree eventually. A payment sent at 11pm on Monday is in the bank's books on Monday but appears on the other bank's statement on Tuesday. Tomorrow's reconciliation will match it.
- **Genuine errors.** Something is actually wrong: a payment booked twice, a fee nobody told you about, a receipt with no reference, a typo in an amount. These need a person to fix them.

Reconciliation is how a bank finds out about the second kind *before* they turn into real losses. Regulators treat unexplained differences as an operational risk, and (for example) the FCA's client money rules in [CASS 7.15](https://www.handbook.fca.org.uk/handbook/CASS/7/15.html) require firms holding client money to reconcile it regularly. You'll build the whole loop across these five guides: import both sides, match them, report the breaks, and resolve them with proper controls.

### Nostro and vostro

A UK bank that pays and receives euros or dollars usually does it through an account at a bank in that currency's home market, called a **correspondent bank**. Even for sterling, smaller banks often hold their settlement money with a larger one. The account your bank holds at another bank is your **[nostro](../glossary.md#nostro-vostro)** ("ours"). From the other bank's side, the very same account is a **vostro** ("yours").

That gives you two records of one account:

| | Your bank's books | The correspondent's books |
|---|---|---|
| What the account is | An **asset**: money another bank owes you | A **liability**: money it owes you |
| Money arrives | You **debit** your nostro (the asset goes up) | They **credit** your account |
| Money leaves | You **credit** your nostro | They **debit** your account |

The two records are mirror images. When the correspondent's statement says **CRDT £1,250**, the line in your own books that should match it is a **debit** of £1,250 on the nostro. (If [debits](../glossary.md#debit) and [credits](../glossary.md#credit) on [assets](../glossary.md#asset) and [liabilities](../glossary.md#liability) are new to you, the Core ledger tutorial explains them properly.)

To make matching simple, this tutorial converts both sides to one **signed amount from your bank's point of view**: positive means your nostro balance went up, negative means it went down. A statement CRDT becomes `+1250.00`; a statement DBIT becomes `-400.00`. In guide 03 the ledger side gets the same treatment.

### The statement: ISO 20022 camt.053

Banks send statements to their customers, including to other banks, as files. The modern standard is [ISO 20022](../glossary.md#iso-20022), whose messages are named by business area: `pacs` for payments clearing and settlement, `pain` for payment initiation, `camt` for **cash management**. **camt.053** is the *Bank-to-Customer Statement*: the end-of-day record of every booked entry on an account. (Its siblings are camt.052, an intraday report, and camt.054, a notification of individual debits and credits.)

Older systems use the Swift MT940 (customer statement) and MT950 (statement for banks) formats instead, and you'll meet both in bank codebases. Swift ended the coexistence period for cross-border *payment instructions* in November 2025, but it has scheduled the move for statement and reporting messages separately and later, so for some years yet many banks will receive both.

Here's the shape of a camt.053 file, cut down to what this tutorial reads:

```
Document                                  (namespace says which message and version)
└── BkToCstmrStmt
    ├── GrpHdr/MsgId                      the file's unique id
    └── Stmt
        ├── Id                            the statement's id
        ├── Acct/Id/IBAN                  which account this is a statement for
        ├── Bal  (Tp/CdOrPrtry/Cd = OPBD) opening booked balance, Amt Ccy="GBP", CdtDbtInd
        ├── Bal  (Tp/CdOrPrtry/Cd = CLBD) closing booked balance
        └── Ntry  (one per booked movement)
            ├── NtryRef                   the entry's number within this statement
            ├── Amt Ccy="GBP"             always positive...
            ├── CdtDbtInd                 ...the direction is CRDT or DBIT
            ├── Sts/Cd                    BOOK for a statement entry
            ├── BookgDt/Dt                booking date
            ├── ValDt/Dt                  value date
            ├── AcctSvcrRef               the statement bank's own reference
            └── NtryDtls/TxDtls
                ├── Refs/EndToEndId       the payer's reference, carried end to end
                └── RmtInf/Ustrd          free-text remittance information
```

A few things worth knowing before you write the parser:

- **Amounts are never negative.** The direction is always in `CdtDbtInd`, and it's from the **account holder's** point of view (your bank's account at the correspondent).
- **Two dates.** The [booking date](../glossary.md#booking-date) is when the entry was posted. The value date is when it counts for interest. They usually agree, but not always.
- **Three references.** `NtryRef` only identifies the entry within this statement. `AcctSvcrRef` is the correspondent's own reference. `EndToEndId` is set by whoever started the payment and travels with it through every bank, which makes it the best thing to match on when it's there.
- **The statement proves itself.** Opening balance + credits − debits must equal the closing balance. If it doesn't, the file is broken or incomplete, and you must not use any of it.

> **Simplification:** the XML in this tutorial is a **simplified but structurally faithful** camt.053. Real statements carry much more: bank transaction codes (`BkTxCd`), transaction summaries (`TxsSummry`), batch entries with many `TxDtls`, related parties, charges, and several balance types. There are also several published versions of the message (this tutorial uses the `camt.053.001.08` namespace), and each bank publishes implementation guidelines saying which version and which optional elements it sends. The full message definitions are in the [ISO 20022 message catalogue](https://www.iso20022.org/iso-20022-message-definitions).

### Reading XML: DOM, binding or streaming?

There are three common ways to read XML in Java:

| Approach | How | Good for | Watch out for |
|---|---|---|---|
| **DOM** (`DocumentBuilder`) | Loads the whole file into a tree | Small files you need to walk around freely | Memory: a tree costs several times the file's size |
| **Binding** (Jakarta XML Binding, formerly JAXB) | Generates Java classes from the XSD and unmarshals into them | Full, schema-valid messages | Generated classes for camt.053 are large; still loads everything |
| **Streaming** (StAX, `javax.xml.stream`) | You pull one event (start tag, text, end tag) at a time | Large files, and reading only what you need | More code: you track where you are yourself |

A busy nostro can have tens of thousands of entries a day, and a bank may receive hundreds of statements. This tutorial uses **StAX**, which is built into the JDK. The parser keeps only the current entry and some running totals in memory, and hands each entry to a **listener** as soon as it's complete. The rest of the application can save it, count it or ignore it, without the parser caring.

### XXE: the file that reads your server

XML has an old feature called **entities**. A document can declare one in a `DOCTYPE` and then use it by name, and an *external* entity's value is loaded from a URI:

```xml
<!DOCTYPE Document [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
<Document>... <Ustrd>&xxe;</Ustrd> ...</Document>
```

A parser that resolves external entities replaces `&xxe;` with the contents of `/etc/passwd`. If that text then ends up in your database, a report or an error message, the attacker has just read a file from your server. The same trick can make the server call internal URLs. This is **XML External Entity (XXE)** injection, a classic finding in security reviews of any system that accepts XML from outside, and banks accept a great deal of it.

The JDK's StAX parser **resolves external entities by default**. You'll prove that with a test, then switch it off as the [OWASP XXE Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/XML_External_Entity_Prevention_Cheat_Sheet.html#xmlinputfactory-stax) recommends.

---

## Steps

### 1. Generate the project

Go to [start.spring.io](https://start.spring.io) and choose:

| Setting | Value |
|---|---|
| Project | Maven |
| Language | Java |
| Spring Boot | 4.1.2 (or the latest 4.1.x) |
| Group | `com.example` |
| Artifact | `reconciliation` |
| Package name | `com.example.recon` |
| Packaging | Jar |
| Java | 25 |

Add these dependencies:

| Dependency | Why |
|---|---|
| **Spring Web** | REST controllers and file uploads |
| **Spring Data JPA** | Entities and repositories |
| **Flyway Migration** | Versioned SQL migrations |
| **H2 Database** | The embedded database |
| **Validation** | `@Valid`, `@NotBlank` |
| **SpringDoc OpenAPI** | Swagger UI, for trying the API (and uploading files) in a browser |

Click **Generate** and unzip it so the project ends up at:

```
tutorials/workspace/banking-systems/reconciliation/
```

Open it in IntelliJ with **File → Open…**, choose the folder containing `pom.xml`, and trust the project. Check **File → Project Structure → Project → SDK** is a Java 25 JDK.

Delete the generated test `src/test/java/com/example/recon/ReconciliationApplicationTests.java`. You'll write better ones.

### 2. Configure the application

Replace `src/main/resources/application.properties` with:

```properties
spring.application.name=reconciliation

# H2 database stored in ./data/recon.mv.db
spring.datasource.url=jdbc:h2:file:./data/recon
spring.datasource.username=sa
spring.h2.console.enabled=true

# Flyway owns the schema; Hibernate only checks it matches the entities
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
```

These are the series' standard settings (the Core ledger tutorial explains each one). Add `data/` to `.gitignore`:

```
### Local database ###
data/
```

### 3. The `Money` record

Every system in this series has its own copy of a small `Money` type: a `BigDecimal` amount plus a `java.util.Currency`, held at exactly the currency's number of decimal places, refusing to round silently and refusing to mix currencies. The Core ledger tutorial covers why, and each trap it avoids, properly. Here's the finished version, plus an `abs()` you'll need for break reports in guide 04.

Create `money/Money.java` (paths like this mean `src/main/java/com/example/recon/money/Money.java`):

```java
package com.example.recon.money;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money in one currency. Immutable, and always held at the
 * currency's own number of decimal places (2 for GBP).
 */
@JsonAutoDetect(isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public record Money(
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        Currency currency) {

    public static final Currency GBP = Currency.getInstance("GBP");

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        try {
            // UNNECESSARY = "don't round; fail if rounding would be needed"
            amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "%s has too many decimal places for %s".formatted(amount.toPlainString(), currency));
        }
    }

    public static Money of(String amount, Currency currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money gbp(String amount) {
        return of(amount, GBP);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public Money abs() {
        return new Money(amount.abs(), currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) < 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: %s and %s".formatted(currency, other.currency));
        }
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + amount.toPlainString();
    }
}
```

The short version of what matters: `RoundingMode.UNNECESSARY` means `Money` never rounds (`Money.gbp("0.001")` throws), `@JsonFormat(shape = STRING)` sends amounts as `"10.50"` so JavaScript can't turn them into floating point, and `@JsonAutoDetect(isGetterVisibility = NONE)` stops `isZero()` and friends leaking into the JSON.

Add `src/test/java/com/example/recon/money/MoneyTest.java`:

```java
package com.example.recon.money;

import org.junit.jupiter.api.Test;

import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void normalisesToTwoDecimalPlaces() {
        assertThat(Money.gbp("10.5")).isEqualTo(Money.gbp("10.50"));
    }

    @Test
    void rejectsFractionsOfAPenny() {
        assertThatThrownBy(() -> Money.gbp("0.001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many decimal places");
    }

    @Test
    void addsSubtractsAndRefusesToMixCurrencies() {
        assertThat(Money.gbp("0.10").plus(Money.gbp("0.20"))).isEqualTo(Money.gbp("0.30"));
        assertThat(Money.gbp("5.00").minus(Money.gbp("7.50"))).isEqualTo(Money.gbp("-2.50"));
        Money euros = Money.of("1.00", Currency.getInstance("EUR"));
        assertThatThrownBy(() -> Money.gbp("1.00").plus(euros))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
    }
}
```

### 4. A sample statement

Create a folder called `samples` in the project root (next to `pom.xml`), and in it `camt053-nostro-2026-11-06.xml`. It's yesterday's statement for your GBP nostro, as your correspondent would send it:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- SIMPLIFIED camt.053: real statements carry many more elements -->
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08">
  <BkToCstmrStmt>
    <GrpHdr><MsgId>MSG-20261107-0001</MsgId><CreDtTm>2026-11-07T05:30:00Z</CreDtTm></GrpHdr>
    <Stmt>
      <Id>STMT-NOSTRO-20261106</Id>
      <Acct><Id><IBAN>GB33BUKB20201555555555</IBAN></Id><Ccy>GBP</Ccy></Acct>
      <Bal>
        <Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp>
        <Amt Ccy="GBP">10000.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Dt><Dt>2026-11-06</Dt></Dt>
      </Bal>
      <Bal>
        <Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp>
        <Amt Ccy="GBP">11899.50</Amt><CdtDbtInd>CRDT</CdtDbtInd><Dt><Dt>2026-11-06</Dt></Dt>
      </Bal>
      <Ntry>
        <NtryRef>1</NtryRef><Amt Ccy="GBP">1250.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Sts><Cd>BOOK</Cd></Sts>
        <BookgDt><Dt>2026-11-06</Dt></BookgDt><ValDt><Dt>2026-11-06</Dt></ValDt>
        <AcctSvcrRef>CB-20261106-0001</AcctSvcrRef>
        <NtryDtls><TxDtls><Refs><EndToEndId>INV-1001</EndToEndId></Refs><RmtInf><Ustrd>Invoice 1001 Acme Ltd</Ustrd></RmtInf></TxDtls></NtryDtls>
      </Ntry>
      <Ntry>
        <NtryRef>2</NtryRef><Amt Ccy="GBP">400.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts><Cd>BOOK</Cd></Sts>
        <BookgDt><Dt>2026-11-06</Dt></BookgDt><ValDt><Dt>2026-11-06</Dt></ValDt>
        <AcctSvcrRef>CB-20261106-0002</AcctSvcrRef>
        <NtryDtls><TxDtls><Refs><EndToEndId>PAY-7731</EndToEndId></Refs><RmtInf><Ustrd>Supplier payment 7731</Ustrd></RmtInf></TxDtls></NtryDtls>
      </Ntry>
      <Ntry>
        <NtryRef>3</NtryRef><Amt Ccy="GBP">999.50</Amt><CdtDbtInd>CRDT</CdtDbtInd><Sts><Cd>BOOK</Cd></Sts>
        <BookgDt><Dt>2026-11-06</Dt></BookgDt><ValDt><Dt>2026-11-06</Dt></ValDt>
        <AcctSvcrRef>CB-20261106-0003</AcctSvcrRef>
        <NtryDtls><TxDtls><Refs><EndToEndId>INV-1002</EndToEndId></Refs><RmtInf><Ustrd>Invoice 1002 less charges</Ustrd></RmtInf></TxDtls></NtryDtls>
      </Ntry>
      <Ntry>
        <NtryRef>4</NtryRef><Amt Ccy="GBP">25.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts><Cd>BOOK</Cd></Sts>
        <BookgDt><Dt>2026-11-06</Dt></BookgDt><ValDt><Dt>2026-11-06</Dt></ValDt>
        <AcctSvcrRef>CB-FEE-20261106</AcctSvcrRef>
        <NtryDtls><TxDtls><RmtInf><Ustrd>Account maintenance fee November</Ustrd></RmtInf></TxDtls></NtryDtls>
      </Ntry>
      <Ntry>
        <NtryRef>5</NtryRef><Amt Ccy="GBP">75.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Sts><Cd>BOOK</Cd></Sts>
        <BookgDt><Dt>2026-11-06</Dt></BookgDt><ValDt><Dt>2026-11-06</Dt></ValDt>
        <AcctSvcrRef>CB-20261106-0042</AcctSvcrRef>
        <NtryDtls><TxDtls><RmtInf><Ustrd>J SMITH</Ustrd></RmtInf></TxDtls></NtryDtls>
      </Ntry>
    </Stmt>
  </BkToCstmrStmt>
</Document>
```

Check it balances: £10,000.00 + (1,250.00 + 999.50 + 75.00) − (400.00 + 25.00) = £11,899.50. Each entry tells a story you'll come back to:

| # | Entry | What it will turn out to be |
|---|---|---|
| 1 | CRDT £1,250.00, `INV-1001` | A customer paying an invoice. It'll match your ledger exactly. |
| 2 | DBIT £400.00, `PAY-7731` | A payment you sent. You booked it a day earlier: a **timing difference**. |
| 3 | CRDT £999.50, `INV-1002` | You expected £1,000.00. A bank on the way took 50p in charges. |
| 4 | DBIT £25.00, no `EndToEndId` | The correspondent's monthly fee. Nobody told your ledger. |
| 5 | CRDT £75.00, no `EndToEndId` | Money from "J SMITH", with no reference. Whose is it? |

`GB33BUKB20201555555555` is the example IBAN widely used in documentation. It isn't a real account.

Copy the same file to `src/test/resources/samples/` so the tests can load it from the classpath.

### 5. The statement types

Everything about statements goes in a `statement` package.

`statement/CreditDebit.java`:

```java
package com.example.recon.statement;

/** ISO 20022 CdtDbtInd: which way the money moved, from the account holder's point of view. */
public enum CreditDebit {
    CRDT,
    DBIT
}
```

`statement/StatementEntry.java`, one `Ntry`:

```java
package com.example.recon.statement;

import com.example.recon.money.Money;

import java.time.LocalDate;

/** One Ntry from a camt.053 statement. */
public record StatementEntry(
        String entryRef,
        Money amount,
        CreditDebit creditDebit,
        LocalDate bookingDate,
        LocalDate valueDate,
        String accountServicerRef,
        String endToEndId,
        String remittanceInfo) {

    /** Credits increase our balance at the other bank; debits reduce it. */
    public Money signedAmount() {
        return creditDebit == CreditDebit.CRDT ? amount : amount.negate();
    }

    /** What we match on: the end-to-end id if the payer sent one, otherwise the bank's own reference. */
    public String matchingReference() {
        return endToEndId != null ? endToEndId : accountServicerRef;
    }
}
```

`statement/StatementListener.java`, the callback the parser streams into:

```java
package com.example.recon.statement;

/** Receives a statement as it is read, so the whole file never has to sit in memory. */
public interface StatementListener {

    /** Called once, before any entry, with the IBAN of the account the statement is for. */
    void account(String iban);

    /** Called once per Ntry, in file order. */
    void entry(StatementEntry entry);
}
```

`statement/StatementSummary.java`, what the parser returns once it reaches the end:

```java
package com.example.recon.statement;

import com.example.recon.money.Money;

/** What's left after streaming a statement: its identity, balances and control totals. */
public record StatementSummary(
        String messageId,
        String statementId,
        String iban,
        Money openingBalance,
        Money closingBalance,
        int entryCount,
        Money totalCredits,
        Money totalDebits) {
}
```

And `statement/StatementFormatException.java`, a plain `RuntimeException` with two constructors, `(String message)` and `(String message, Throwable cause)`, that just call `super`.

### 6. The StAX parser

StAX gives you an `XMLStreamReader`. You call `next()` and get back an event type: `START_ELEMENT`, `CHARACTERS`, `END_ELEMENT` and so on. The reader doesn't tell you *where* you are in the document, so the parser keeps a **path**: a stack of element names, pushed on each start tag and popped on each end tag. `Document/BkToCstmrStmt/Stmt/Ntry/Amt` then identifies exactly one kind of element, and a `switch` on the path string reads almost like the structure diagram above.

Create `statement/Camt053Parser.java`. The factory has **no XXE protection yet**. That's deliberate:

```java
package com.example.recon.statement;

import com.example.recon.money.Money;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Streams a (simplified) ISO 20022 camt.053 bank-to-customer statement with StAX.
 * Entries are handed to a listener one at a time; only the totals are kept.
 */
@Component
public class Camt053Parser {

    private static final String NAMESPACE_PREFIX = "urn:iso:std:iso:20022:tech:xsd:camt.053.";
    private static final String STMT = "Document/BkToCstmrStmt/Stmt";
    private static final String BAL = STMT + "/Bal";
    private static final String NTRY = STMT + "/Ntry";

    public StatementSummary parse(InputStream in, StatementListener listener) {
        try {
            XMLStreamReader reader = secureFactory().createXMLStreamReader(in);
            try {
                return read(reader, listener);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new StatementFormatException("Not well-formed XML: " + e.getMessage(), e);
        }
    }

    private StatementSummary read(XMLStreamReader reader, StatementListener listener) throws XMLStreamException {
        Deque<String> path = new ArrayDeque<>();
        String messageId = null;
        String statementId = null;
        String iban = null;
        int statements = 0;
        Money opening = null;
        Money closing = null;
        Money credits = Money.zero(Money.GBP);
        Money debits = Money.zero(Money.GBP);
        int entryCount = 0;

        Draft bal = null;    // the Bal or Ntry being read
        Draft ntry = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                path.addLast(reader.getLocalName());
                String p = String.join("/", path);
                if (path.size() == 1) { // the root element
                    String ns = reader.getNamespaceURI();
                    if (!p.equals("Document") || ns == null || !ns.startsWith(NAMESPACE_PREFIX)) {
                        throw new StatementFormatException(
                                "Not a camt.053 document (root element %s, namespace %s)".formatted(p, ns));
                    }
                    continue;
                }
                switch (p) {
                    case STMT -> {
                        if (++statements > 1) {
                            throw new StatementFormatException("Only one Stmt per file is supported");
                        }
                    }
                    case BAL -> bal = new Draft();
                    case NTRY -> ntry = new Draft();
                    case BAL + "/Amt", NTRY + "/Amt" -> {
                        Draft d = p.startsWith(BAL) ? bal : ntry;
                        d.currency = reader.getAttributeValue(null, "Ccy");
                        d.amount = text(reader, path);
                    }
                    case "Document/BkToCstmrStmt/GrpHdr/MsgId" -> messageId = text(reader, path);
                    case STMT + "/Id" -> statementId = text(reader, path);
                    case STMT + "/Acct/Id/IBAN" -> {
                        iban = text(reader, path);
                        listener.account(iban);
                    }
                    case BAL + "/Tp/CdOrPrtry/Cd" -> bal.code = text(reader, path);
                    case BAL + "/CdtDbtInd" -> bal.creditDebit = text(reader, path);
                    case NTRY + "/CdtDbtInd" -> ntry.creditDebit = text(reader, path);
                    case NTRY + "/NtryRef" -> ntry.entryRef = text(reader, path);
                    case NTRY + "/BookgDt/Dt" -> ntry.bookingDate = text(reader, path);
                    case NTRY + "/ValDt/Dt" -> ntry.valueDate = text(reader, path);
                    case NTRY + "/AcctSvcrRef" -> ntry.accountServicerRef = text(reader, path);
                    case NTRY + "/NtryDtls/TxDtls/Refs/EndToEndId" -> ntry.endToEndId = text(reader, path);
                    case NTRY + "/NtryDtls/TxDtls/RmtInf/Ustrd" -> ntry.remittanceInfo = text(reader, path);
                    default -> { } // an element we don't need: keep streaming
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String p = String.join("/", path);
                if (p.equals(BAL)) {
                    Money balance = signed(money(bal, "Bal"), creditDebit(bal.creditDebit, "Bal"));
                    switch (String.valueOf(bal.code)) {
                        case "OPBD" -> opening = balance;
                        case "CLBD" -> closing = balance;
                        default -> { } // other balance types (e.g. available) aren't needed here
                    }
                    bal = null;
                } else if (p.equals(NTRY)) {
                    StatementEntry entry = toEntry(ntry, entryCount + 1);
                    if (entry.creditDebit() == CreditDebit.CRDT) {
                        credits = credits.plus(entry.amount());
                    } else {
                        debits = debits.plus(entry.amount());
                    }
                    entryCount++;
                    listener.entry(entry);
                    ntry = null;
                }
                path.removeLast();
            }
        }

        if (statementId == null || iban == null) {
            throw new StatementFormatException("Missing Stmt/Id or Stmt/Acct/Id/IBAN");
        }
        if (opening == null || closing == null) {
            throw new StatementFormatException("Statement must have an OPBD and a CLBD balance");
        }
        Money expectedClosing = opening.plus(credits).minus(debits);
        if (!expectedClosing.equals(closing)) {
            throw new StatementFormatException(
                    "Statement %s does not balance: opening %s + credits %s - debits %s = %s, but closing balance is %s"
                            .formatted(statementId, opening, credits, debits, expectedClosing, closing));
        }
        return new StatementSummary(messageId, statementId, iban, opening, closing, entryCount, credits, debits);
    }

    /**
     * A new factory per file: XMLInputFactory isn't documented as thread-safe, and creating one is cheap.
     */
    private static XMLInputFactory secureFactory() {
        // The JDK's own StAX implementation, even if another one is on the classpath
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        return factory; // not secure yet!
    }

    /** Reads a leaf element's text. getElementText() consumes the END_ELEMENT, so pop the path here. */
    private static String text(XMLStreamReader reader, Deque<String> path) throws XMLStreamException {
        String text = reader.getElementText().strip();
        path.removeLast();
        return text;
    }

    private static StatementEntry toEntry(Draft d, int number) {
        String where = "Ntry " + number;
        if (d.bookingDate == null) {
            throw new StatementFormatException(where + ": missing BookgDt");
        }
        if (d.endToEndId == null && d.accountServicerRef == null) {
            throw new StatementFormatException(where + ": needs an EndToEndId or an AcctSvcrRef to match on");
        }
        return new StatementEntry(d.entryRef, money(d, where), creditDebit(d.creditDebit, where),
                date(d.bookingDate, where), d.valueDate == null ? null : date(d.valueDate, where),
                d.accountServicerRef, d.endToEndId, d.remittanceInfo);
    }

    private static Money money(Draft d, String where) {
        if (d.amount == null || d.currency == null) {
            throw new StatementFormatException(where + ": missing Amt or its Ccy attribute");
        }
        if (!Money.GBP.getCurrencyCode().equals(d.currency)) {
            throw new StatementFormatException(where + ": only GBP is supported, got " + d.currency);
        }
        try {
            BigDecimal value = new BigDecimal(d.amount);
            if (value.signum() < 0) {
                throw new StatementFormatException(where + ": amounts are never negative; the sign is in CdtDbtInd");
            }
            return new Money(value, Money.GBP);
        } catch (NumberFormatException e) {
            throw new StatementFormatException(where + ": invalid amount '" + d.amount + "'");
        } catch (IllegalArgumentException e) {
            throw new StatementFormatException(where + ": " + e.getMessage());
        }
    }

    private static CreditDebit creditDebit(String value, String where) {
        if ("CRDT".equals(value)) return CreditDebit.CRDT;
        if ("DBIT".equals(value)) return CreditDebit.DBIT;
        throw new StatementFormatException(where + ": CdtDbtInd must be CRDT or DBIT, got " + value);
    }

    private static Money signed(Money amount, CreditDebit creditDebit) {
        return creditDebit == CreditDebit.CRDT ? amount : amount.negate();
    }

    private static LocalDate date(String value, String where) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new StatementFormatException(where + ": invalid date '" + value + "'");
        }
    }

    /** Mutable scratch space for one Bal or Ntry while its child elements stream past. */
    private static final class Draft {
        String code, amount, currency, creditDebit;
        String entryRef, bookingDate, valueDate, accountServicerRef, endToEndId, remittanceInfo;
    }
}
```

It's the longest class in the tutorial, so take it in pieces:

- **The path stack.** `path.addLast(...)` on every start tag, `path.removeLast()` on every end tag. A `Deque` is Java's recommended stack (prefer it to the old `java.util.Stack`).
- **`text(reader, path)`.** `getElementText()` reads a leaf element's text *and consumes its end tag*, so the main loop never sees that `END_ELEMENT`. The helper pops the path itself to keep the stack right.
- **`Draft`** collects an entry's fields as strings while its child elements stream past. Only at `</Ntry>` does it become a validated, immutable `StatementEntry`, which goes straight to the listener. The parser never holds more than one entry.
- **`default -> { }`.** Every element you don't need (`CreDtTm`, `Ccy`, `ValDt` inside `Bal`, …) is skipped. A real camt.053 has dozens more, and they cost nothing.
- **Validation is strict.** Unknown root, wrong namespace, a second `Stmt`, a non-GBP amount, a negative amount, a bad date, a missing reference: all throw `StatementFormatException` with the entry number in the message, so whoever reads the error can find the line.
- **The balance check runs last.** Every entry has already gone to the listener by then. That's fine, because in step 9 the listener writes inside a transaction: an exception at the end rolls back every row. Streaming and all-or-nothing work together.
- **`newDefaultFactory()`** (Java 9+) always returns the JDK's built-in StAX implementation. `newFactory()` might pick up another one, such as Woodstox, if a library drags it onto the classpath, and its defaults could differ.

> **In older codebases:** you'll see camt.053 read with JAXB classes generated from the XSD (`javax.xml.bind` before Jakarta EE 9, `jakarta.xml.bind` after), or with DOM and XPath. Both work, and binding is fine when files are small. Streaming matters once files get large. Some teams combine them: StAX to walk to each `Ntry`, then JAXB to unmarshal just that element.

### 7. Parser tests

Create `src/test/java/com/example/recon/statement/Camt053ParserTest.java`:

```java
package com.example.recon.statement;

import com.example.recon.money.Money;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Camt053ParserTest {

    private final Camt053Parser parser = new Camt053Parser();

    /** Collects what the parser streams out, so tests can look at it. */
    static class Recorder implements StatementListener {
        String iban;
        final List<StatementEntry> entries = new ArrayList<>();

        @Override
        public void account(String iban) {
            this.iban = iban;
        }

        @Override
        public void entry(StatementEntry entry) {
            entries.add(entry);
        }
    }

    @Test
    void streamsEveryEntryAndChecksTheBalances() throws Exception {
        Recorder recorder = new Recorder();
        StatementSummary summary;
        try (InputStream in = getClass().getResourceAsStream("/samples/camt053-nostro-2026-11-06.xml")) {
            summary = parser.parse(in, recorder);
        }

        assertThat(recorder.iban).isEqualTo("GB33BUKB20201555555555");
        assertThat(summary.statementId()).isEqualTo("STMT-NOSTRO-20261106");
        assertThat(summary.entryCount()).isEqualTo(5);
        assertThat(summary.openingBalance()).isEqualTo(Money.gbp("10000.00"));
        assertThat(summary.closingBalance()).isEqualTo(Money.gbp("11899.50"));
        assertThat(summary.totalCredits()).isEqualTo(Money.gbp("2324.50"));
        assertThat(summary.totalDebits()).isEqualTo(Money.gbp("425.00"));

        StatementEntry first = recorder.entries.getFirst();
        assertThat(first.matchingReference()).isEqualTo("INV-1001");
        assertThat(first.signedAmount()).isEqualTo(Money.gbp("1250.00"));
        assertThat(first.bookingDate()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(first.remittanceInfo()).isEqualTo("Invoice 1001 Acme Ltd");
    }

    @Test
    void debitsAreNegativeAndEntriesWithoutAnEndToEndIdUseTheBanksReference() throws Exception {
        Recorder recorder = new Recorder();
        try (InputStream in = getClass().getResourceAsStream("/samples/camt053-nostro-2026-11-06.xml")) {
            parser.parse(in, recorder);
        }

        StatementEntry payment = recorder.entries.get(1);
        assertThat(payment.creditDebit()).isEqualTo(CreditDebit.DBIT);
        assertThat(payment.signedAmount()).isEqualTo(Money.gbp("-400.00"));

        StatementEntry fee = recorder.entries.get(3);
        assertThat(fee.endToEndId()).isNull();
        assertThat(fee.matchingReference()).isEqualTo("CB-FEE-20261106");
    }

    @Test
    void rejectsAStatementThatDoesNotBalance() {
        String xml = statement("10000.00", "10100.00", entry("1", "50.00", "CRDT", "INV-1"));

        assertThatThrownBy(() -> parser.parse(stream(xml), new Recorder()))
                .isInstanceOf(StatementFormatException.class)
                .hasMessageContaining("does not balance");
    }

    @Test
    void rejectsADocumentThatIsNotACamt053() {
        String xml = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.09"><CstmrCdtTrfInitn/></Document>
                """;

        assertThatThrownBy(() -> parser.parse(stream(xml), new Recorder()))
                .isInstanceOf(StatementFormatException.class)
                .hasMessageContaining("Not a camt.053 document");
    }

    @Test
    void rejectsXmlThatIsNotWellFormed() {
        assertThatThrownBy(() -> parser.parse(stream("""
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08"><BkToCstmrStmt></Document>
                """), new Recorder()))
                .isInstanceOf(StatementFormatException.class)
                .hasMessageStartingWith("Not well-formed XML");
    }

    static String statement(String opening, String closing, String... entries) {
        return """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08"><BkToCstmrStmt>
                  <GrpHdr><MsgId>MSG-1</MsgId></GrpHdr>
                  <Stmt><Id>STMT-1</Id>
                    <Acct><Id><IBAN>GB33BUKB20201555555555</IBAN></Id></Acct>
                    <Bal><Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy="GBP">%s</Amt><CdtDbtInd>CRDT</CdtDbtInd></Bal>
                    <Bal><Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy="GBP">%s</Amt><CdtDbtInd>CRDT</CdtDbtInd></Bal>
                    %s
                  </Stmt>
                </BkToCstmrStmt></Document>
                """.formatted(opening, closing, String.join("\n", entries));
    }

    static String entry(String ref, String amount, String creditDebit, String endToEndId) {
        return """
                <Ntry><NtryRef>%s</NtryRef><Amt Ccy="GBP">%s</Amt><CdtDbtInd>%s</CdtDbtInd>
                  <BookgDt><Dt>2026-11-06</Dt></BookgDt>
                  <NtryDtls><TxDtls><Refs><EndToEndId>%s</EndToEndId></Refs></TxDtls></NtryDtls></Ntry>
                """.formatted(ref, amount, creditDebit, endToEndId);
    }

    static InputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }
}
```

- **`Recorder`** is a hand-written test double: it implements the listener and just remembers what it was given. No mocking library needed.
- **`statement(...)` and `entry(...)`** build tiny statements in text blocks, so each test shows exactly the XML it's about. The full sample file covers the happy path.
- The parser is a plain class, so these are fast unit tests. No Spring context starts.

Run them: click the green arrow next to `class Camt053ParserTest`, or:

```bash
./mvnw test -Dtest=Camt053ParserTest
```

Five green ticks.

### 8. Watch it leak, then stop XXE

Create `src/test/java/com/example/recon/statement/XxeTest.java`. It writes a "secret" file to a temporary folder, then uploads a statement that tries to read it into the remittance information:

```java
package com.example.recon.statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An uploaded file must never be able to make the parser read files from our server. */
class XxeTest {

    @Test
    void externalEntitiesAreNeverResolved(@TempDir Path dir) throws Exception {
        Path secret = Files.writeString(dir.resolve("secret.txt"), "TOP-SECRET");
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE Document [ <!ENTITY xxe SYSTEM "%s"> ]>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08"><BkToCstmrStmt>
                  <GrpHdr><MsgId>MSG-1</MsgId></GrpHdr>
                  <Stmt><Id>STMT-1</Id>
                    <Acct><Id><IBAN>GB33BUKB20201555555555</IBAN></Id></Acct>
                    <Bal><Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy="GBP">0.00</Amt><CdtDbtInd>CRDT</CdtDbtInd></Bal>
                    <Bal><Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy="GBP">1.00</Amt><CdtDbtInd>CRDT</CdtDbtInd></Bal>
                    <Ntry><Amt Ccy="GBP">1.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><BookgDt><Dt>2026-11-06</Dt></BookgDt>
                      <NtryDtls><TxDtls><Refs><EndToEndId>E2E-1</EndToEndId></Refs>
                        <RmtInf><Ustrd>&xxe;</Ustrd></RmtInf></TxDtls></NtryDtls></Ntry>
                  </Stmt>
                </BkToCstmrStmt></Document>
                """.formatted(secret.toUri());
        Camt053ParserTest.Recorder recorder = new Camt053ParserTest.Recorder();

        assertThatThrownBy(() -> new Camt053Parser().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), recorder))
                .isInstanceOf(StatementFormatException.class)
                .hasMessageContaining("xxe");
        assertThat(recorder.entries)
                .noneMatch(e -> e.remittanceInfo() != null && e.remittanceInfo().contains("TOP-SECRET"));
    }
}
```

`@TempDir` gives the test a fresh folder that JUnit deletes afterwards. Run it:

```
Expecting code to raise a throwable.
```

The parser accepted the file without complaint. If you set a breakpoint and look at `recorder.entries`, the first entry's `remittanceInfo` is `TOP-SECRET`: the parser opened a file on the machine running your code and put its contents into a statement entry. In production that entry would be saved, shown on a break report and copied into tickets.

Now fix `secureFactory()` in `Camt053Parser`:

```java
    private static XMLInputFactory secureFactory() {
        // The JDK's own StAX implementation, even if another one is on the classpath
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        // XXE protection: no DTDs means no entity declarations, so no external entities
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        // Belt and braces: if anything still asks to load an external resource, refuse
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External references are not allowed: " + systemId);
        });
        return factory;
    }
```

Run `XxeTest` again. Green. The parser now refuses the document with a message like this (the row and column depend on where `&xxe;` sits in the file):

```
Not well-formed XML: ParseError at [row,col]:[…]
Message: The entity "xxe" was referenced, but not declared.
```

With DTD support off, the `<!ENTITY>` declaration is ignored, so `&xxe;` refers to an entity that doesn't exist, which is a well-formedness error. camt.053 files never need a DTD (they're defined by XML Schema), so switching DTDs off costs nothing. These are the settings the OWASP cheat sheet recommends for StAX.

> **Sidebar: XXE is a family.** The same switch also stops the "billion laughs" attack, where nested entities expand a tiny file into gigabytes in memory. Every XML API in Java has its own flags (`DocumentBuilderFactory`, `SAXParserFactory`, `TransformerFactory`, JAXB's unmarshaller). If you meet XML parsing in a bank codebase, check how each factory is configured. The OWASP cheat sheet lists them all.

### 9. Accounts, imported files and recon items

Now somewhere to put the entries. Create `src/main/resources/db/migration/V1__accounts_imports_and_items.sql`:

```sql
-- The bank's own accounts that we reconcile (a tiny chart of accounts)
CREATE TABLE account (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    code        VARCHAR(30)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    type        VARCHAR(20)  NOT NULL,
    currency    VARCHAR(3)   NOT NULL,
    external_id VARCHAR(34),
    CONSTRAINT uk_account_code UNIQUE (code)
);

-- Our GBP nostro: the account our bank holds at another bank.
-- external_id is the IBAN the other bank's statements will quote.
INSERT INTO account (code, name, type, currency, external_id)
VALUES ('NOSTRO_GBP', 'GBP nostro at our correspondent bank', 'ASSET', 'GBP', 'GB33BUKB20201555555555');

-- One row per file we have imported
CREATE TABLE imported_file (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    file_type    VARCHAR(20)              NOT NULL,
    file_name    VARCHAR(255)             NOT NULL,
    account_code VARCHAR(30)              NOT NULL,
    external_id  VARCHAR(35),
    item_count   INT                      NOT NULL,
    imported_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Everything that can be matched: one row per statement entry (and, later, per ledger posting)
CREATE TABLE recon_item (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    account_code     VARCHAR(30)    NOT NULL,
    side             VARCHAR(10)    NOT NULL,
    imported_file_id BIGINT         REFERENCES imported_file (id),
    reference        VARCHAR(35)    NOT NULL,
    amount           NUMERIC(19, 2) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    booking_date     DATE           NOT NULL,
    value_date       DATE,
    description      VARCHAR(140),
    status           VARCHAR(10)    NOT NULL
);

CREATE INDEX ix_recon_item_open ON recon_item (account_code, status, booking_date);
```

The design decision that shapes the whole tutorial is **`recon_item`**: one table for *everything that can be matched*, whichever side it came from. A statement entry is an `EXTERNAL` item; in guide 03 every ledger posting on the nostro becomes an `INTERNAL` item. The matching engine then only ever compares items. Real reconciliation platforms work the same way: they load each source into a common format first, and match second.

- **`amount` is signed** from the bank's point of view, as described in Concepts.
- **`reference VARCHAR(35)`**: ISO 20022 references such as `EndToEndId` are at most 35 characters.
- **`description VARCHAR(140)`**: unstructured remittance information is at most 140.
- **`external_id VARCHAR(34)`**: an IBAN is at most 34 characters.
- **The index** fits the query the matching engine will run all day: "open items for this account, up to this date".

Now the entities. From here on, **listings leave out plain getters** (a `getX()` that only returns field `x`) to keep the guides readable. Generate them in IntelliJ: put the cursor inside the class, press <kbd>Alt</kbd>+<kbd>Insert</kbd> (<kbd>Cmd</kbd>+<kbd>N</kbd> on macOS) → **Getter**, and select every field. Getters that do anything more are always shown.

`account/AccountType.java`:

```java
package com.example.recon.account;

/** Which side of the bank's balance sheet an account sits on. */
public enum AccountType {
    ASSET,
    LIABILITY
}
```

`account/Account.java`. These are the bank's own [internal accounts](../glossary.md#internal-account), seeded by Flyway, so there's no way to create one yet:

```java
package com.example.recon.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Currency;

/** One of the bank's own (internal) accounts. Seeded by Flyway, never created through the API. */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType type;

    @Column(name = "currency", nullable = false, length = 3)
    private String currencyCode;

    /** How the outside world identifies this account, e.g. the IBAN on a nostro statement. */
    @Column(name = "external_id", length = 34)
    private String externalId;

    protected Account() {
        // for JPA
    }

    public Currency getCurrency() {
        return Currency.getInstance(currencyCode);
    }

    // + getters for id, code, name, type and externalId
}
```

`account/AccountRepository.java`:

```java
package com.example.recon.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByCode(String code);

    Optional<Account> findByExternalId(String externalId);
}
```

Also create `account/UnknownAccountException.java`: a `RuntimeException` whose only constructor takes a `String message` and passes it to `super`.

The `item` package holds the matchable items. `item/Side.java` and `item/ItemStatus.java`:

```java
package com.example.recon.item;

/** Which record an item came from. */
public enum Side {
    /** Our own books: the ledger. */
    INTERNAL,
    /** Someone else's record of the same money: a statement or a scheme report. */
    EXTERNAL
}
```

```java
package com.example.recon.item;

public enum ItemStatus {
    OPEN,
    MATCHED
}
```

`item/ReconItem.java`:

```java
package com.example.recon.item;

import com.example.recon.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;

/**
 * One movement of money as one record saw it. The amount is signed from the bank's
 * point of view: positive means the balance of the account went up.
 */
@Entity
@Table(name = "recon_item")
public class ReconItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_code", nullable = false, length = 30)
    private String accountCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Side side;

    @Column(name = "imported_file_id")
    private Long importedFileId;

    @Column(nullable = false, length = 35)
    private String reference;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "booking_date", nullable = false)
    private LocalDate bookingDate;

    @Column(name = "value_date")
    private LocalDate valueDate;

    @Column(length = 140)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ItemStatus status;

    protected ReconItem() {
        // for JPA
    }

    private ReconItem(String accountCode, Side side, Long importedFileId, String reference, Money amount,
                      LocalDate bookingDate, LocalDate valueDate, String description) {
        this.accountCode = accountCode;
        this.side = side;
        this.importedFileId = importedFileId;
        this.reference = reference;
        this.amount = amount.amount();
        this.currencyCode = amount.currency().getCurrencyCode();
        this.bookingDate = bookingDate;
        this.valueDate = valueDate;
        this.description = description;
        this.status = ItemStatus.OPEN;
    }

    /** An item from a file someone else sent us. */
    public static ReconItem external(String accountCode, Long importedFileId, String reference, Money amount,
                                     LocalDate bookingDate, LocalDate valueDate, String description) {
        return new ReconItem(accountCode, Side.EXTERNAL, importedFileId, reference, amount,
                bookingDate, valueDate, description);
    }

    public Money getAmount() {
        return new Money(amount, Currency.getInstance(currencyCode));
    }

    // + getters for id, accountCode, side, importedFileId, reference,
    //   bookingDate, valueDate, description and status
}
```

The item refers to its account by `accountCode` and to its file by `importedFileId`: plain columns rather than `@ManyToOne` relationships. Items are loaded in thousands by the matching engine, and nothing ever needs to walk from an item to its file object, so keeping them flat avoids lazy-loading surprises entirely. The column stores a `BigDecimal`, but the getter hands out a `Money`.

`item/ReconItemRepository.java`:

```java
package com.example.recon.item;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconItemRepository extends JpaRepository<ReconItem, Long> {

    List<ReconItem> findByImportedFileIdOrderById(Long importedFileId);
}
```

Finally the `imports` package. `imports/FileType.java` is an enum with one constant for now, `CAMT053`. `imports/ImportedFile.java`:

```java
package com.example.recon.imports;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "imported_file")
public class ImportedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false, length = 20)
    private FileType fileType;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "account_code", nullable = false, length = 30)
    private String accountCode;

    /** The sender's own id for the file, e.g. the camt.053 Stmt/Id. */
    @Column(name = "external_id", length = 35)
    private String externalId;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    protected ImportedFile() {
        // for JPA
    }

    public ImportedFile(FileType fileType, String fileName, String accountCode) {
        this.fileType = fileType;
        this.fileName = fileName;
        this.accountCode = accountCode;
        this.importedAt = Instant.now();
    }

    public void completed(String externalId, int itemCount) {
        this.externalId = externalId;
        this.itemCount = itemCount;
    }

    // + getters for every field
}
```

`imports/ImportedFileRepository.java` is an empty `JpaRepository<ImportedFile, Long>`. And `imports/ImportNotFoundException.java`:

```java
package com.example.recon.imports;

public class ImportNotFoundException extends RuntimeException {

    public ImportNotFoundException(Long id) {
        super("No imported file with id " + id);
    }
}
```

### 10. The import service

`imports/ImportService.java` connects the parser to the database. Its listener saves each entry as soon as the parser hands it over:

```java
package com.example.recon.imports;

import com.example.recon.account.Account;
import com.example.recon.account.AccountRepository;
import com.example.recon.account.UnknownAccountException;
import com.example.recon.item.ReconItem;
import com.example.recon.item.ReconItemRepository;
import com.example.recon.statement.Camt053Parser;
import com.example.recon.statement.StatementEntry;
import com.example.recon.statement.StatementListener;
import com.example.recon.statement.StatementSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;

@Service
public class ImportService {

    private final Camt053Parser camt053Parser;
    private final AccountRepository accounts;
    private final ImportedFileRepository files;
    private final ReconItemRepository items;

    public ImportService(Camt053Parser camt053Parser, AccountRepository accounts,
                         ImportedFileRepository files, ReconItemRepository items) {
        this.camt053Parser = camt053Parser;
        this.accounts = accounts;
        this.files = files;
        this.items = items;
    }

    /** All or nothing: if anything in the file is wrong, the transaction rolls back every row. */
    @Transactional
    public ImportedFile importCamt053(String fileName, InputStream in) {
        StatementWriter writer = new StatementWriter(fileName);
        StatementSummary summary = camt053Parser.parse(in, writer);
        writer.file.completed(summary.statementId(), summary.entryCount());
        return writer.file;
    }

    @Transactional(readOnly = true)
    public ImportedFile get(Long id) {
        return files.findById(id).orElseThrow(() -> new ImportNotFoundException(id));
    }

    /** Saves each entry as the parser streams it past. */
    private class StatementWriter implements StatementListener {

        private final String fileName;
        private Account account;
        private ImportedFile file;

        StatementWriter(String fileName) {
            this.fileName = fileName;
        }

        @Override
        public void account(String iban) {
            account = accounts.findByExternalId(iban)
                    .orElseThrow(() -> new UnknownAccountException("No account with IBAN " + iban));
            file = files.save(new ImportedFile(FileType.CAMT053, fileName, account.getCode()));
        }

        @Override
        public void entry(StatementEntry entry) {
            items.save(ReconItem.external(account.getCode(), file.getId(),
                    entry.matchingReference(), entry.signedAmount(),
                    entry.bookingDate(), entry.valueDate(), entry.remittanceInfo()));
        }
    }
}
```

- **`StatementWriter` is an inner class** (not `static`), so it can use the service's repositories directly. It exists only for the length of one import.
- **The IBAN decides the account.** Because `Acct` comes before any `Ntry` in a camt.053, the account is always known before the first entry arrives. A statement for an IBAN the bank doesn't know is refused.
- **`completed(...)`** updates the file row once the parser has finished. Dirty checking writes it at commit, so no explicit `save` is needed.

> **Sidebar: really large files.** With `IDENTITY` ids, Hibernate must insert each row immediately to learn its id, so it can't batch the inserts. For files with hundreds of thousands of entries, teams switch to sequence-generated ids with `hibernate.jdbc.batch_size`, or bypass JPA with `JdbcTemplate.batchUpdate`, and flush and clear the persistence context every few thousand rows so it doesn't grow without limit. For a daily nostro statement, the simple version is fine.

### 11. The upload endpoint

File uploads use **`multipart/form-data`**, the same encoding a browser uses for `<input type="file">`. Spring MVC hands you each part as a `MultipartFile`.

`imports/ImportController.java`:

```java
package com.example.recon.imports;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;

@RestController
@RequestMapping("/imports")
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    @PostMapping(path = "/camt053", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResponse> camt053(@RequestParam("file") MultipartFile file) throws IOException {
        ImportedFile imported;
        try (InputStream in = file.getInputStream()) {
            imported = importService.importCamt053(file.getOriginalFilename(), in);
        }
        return ResponseEntity.created(URI.create("/imports/" + imported.getId()))
                .body(ImportResponse.from(imported));
    }

    @GetMapping("/{id}")
    public ImportResponse get(@PathVariable Long id) {
        return ImportResponse.from(importService.get(id));
    }

    public record ImportResponse(Long id, FileType fileType, String fileName, String accountCode,
                                 String externalId, int itemCount, Instant importedAt) {

        static ImportResponse from(ImportedFile file) {
            return new ImportResponse(file.getId(), file.getFileType(), file.getFileName(),
                    file.getAccountCode(), file.getExternalId(), file.getItemCount(), file.getImportedAt());
        }
    }
}
```

- **`consumes = MULTIPART_FORM_DATA_VALUE`** makes the mapping only accept multipart requests. Anything else gets a `415 Unsupported Media Type`.
- **`@RequestParam("file")`** names the form field the file must arrive in.
- **`file.getInputStream()`** streams the upload. Spring Boot's default upload limit is **1MB per file** (`spring.servlet.multipart.max-file-size`); raise it deliberately when real statements need it.
- **`getOriginalFilename()`** is only stored, never used to open a file on disk. Treat it as untrusted text: client-supplied file names are a classic path-traversal vector.

Now `web/ApiExceptionHandler.java`, which turns errors into `ProblemDetail` responses (the Core ledger tutorial walks through this class line by line):

```java
package com.example.recon.web;

import com.example.recon.account.UnknownAccountException;
import com.example.recon.statement.StatementFormatException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;
import java.util.TreeMap;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new TreeMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ProblemDetail problem = ex.getBody();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @ExceptionHandler(StatementFormatException.class)
    ProblemDetail handle(StatementFormatException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid statement file", ex.getMessage());
    }

    @ExceptionHandler(UnknownAccountException.class)
    ProblemDetail handle(UnknownAccountException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Unknown account", ex.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
```

A file that is well-formed enough to upload, but fails the rules, is **`422 Unprocessable Content`**: the request reached the server fine, but its content is wrong, and resending it unchanged won't help.

### 12. Try it

Run the app (green arrow next to `main` in `ReconciliationApplication`, or `./mvnw spring-boot:run`). Flyway applies the migration:

```
Migrating schema "PUBLIC" to version "1 - accounts imports and items"
Successfully applied 1 migration to schema "PUBLIC", now at version v1
```

Create `requests.http` in the project root. IntelliJ's HTTP client can send a file as a multipart part with `< ./path`:

```http
### Import yesterday's nostro statement (camt.053)
POST http://localhost:8080/imports/camt053
Content-Type: multipart/form-data; boundary=WebAppBoundary

--WebAppBoundary
Content-Disposition: form-data; name="file"; filename="camt053-nostro-2026-11-06.xml"
Content-Type: application/xml

< ./samples/camt053-nostro-2026-11-06.xml
--WebAppBoundary--

### Look at the import
GET http://localhost:8080/imports/1
```

**(Ultimate)** Click the green ▶ beside the first request.

**Free edition / terminal alternatives:** in Swagger UI (`http://localhost:8080/swagger-ui.html`), expand **POST /imports/camt053**, click **Try it out**, and choose the file with the file picker. Or use curl, whose `-F` option builds the multipart request for you:

```bash
curl -i -F file=@samples/camt053-nostro-2026-11-06.xml localhost:8080/imports/camt053
```

You get `201 Created`, `Location: /imports/1`, and:

```json
{
  "id": 1,
  "fileType": "CAMT053",
  "fileName": "camt053-nostro-2026-11-06.xml",
  "accountCode": "NOSTRO_GBP",
  "externalId": "STMT-NOSTRO-20261106",
  "itemCount": 5,
  "importedAt": "2026-11-07T14:10:04.155857460Z"
}
```

Now upload something that isn't a statement at all, such as your `pom.xml`:

```bash
curl -F file=@pom.xml localhost:8080/imports/camt053
```

```json
{"detail":"Not a camt.053 document (root element project, namespace http://maven.apache.org/POM/4.0.0)","instance":"/imports/camt053","status":422,"title":"Invalid statement file"}
```

Then upload the statement a second time. It's accepted again, and you now have ten items for five real movements. Guide 02 fixes that.

### 13. API tests for uploads

Every integration test needs the same setup: the whole application, the real migrations and an in-memory database. Bundle it into a meta-annotation, `src/test/java/com/example/recon/ReconIntegrationTest.java`:

```java
package com.example.recon;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Full application, real Flyway migrations, in-memory H2 (so tests never
 * touch ./data), and a MockMvcTester for calling the API.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:recon-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
public @interface ReconIntegrationTest {
}
```

Every test class with identical configuration shares one cached Spring context, so the suite stays fast. It also means **every test class shares one in-memory database**, which matters a lot once you start matching. Keep that in mind.

`src/test/java/com/example/recon/imports/StatementImportApiTest.java`:

```java
package com.example.recon.imports;

import com.example.recon.ReconIntegrationTest;
import com.example.recon.item.ReconItem;
import com.example.recon.item.ReconItemRepository;
import com.example.recon.item.Side;
import com.example.recon.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ReconIntegrationTest
class StatementImportApiTest {

    @Autowired MockMvcTester mvc;
    @Autowired ReconItemRepository items;

    @Test
    void importsEveryEntryAsAnOpenExternalItem() throws Exception {
        MvcTestResult response = upload("statement.xml", sample());

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).bodyJson().extractingPath("$.accountCode").isEqualTo("NOSTRO_GBP");
        assertThat(response).bodyJson().extractingPath("$.externalId").isEqualTo("STMT-NOSTRO-20261106");
        assertThat(response).bodyJson().extractingPath("$.itemCount").isEqualTo(5);

        Long id = Long.valueOf(response.getResponse().getHeader("Location").replace("/imports/", ""));
        List<ReconItem> imported = items.findByImportedFileIdOrderById(id);
        assertThat(imported).hasSize(5).allMatch(i -> i.getSide() == Side.EXTERNAL);
        assertThat(imported).extracting(ReconItem::getAmount)
                .containsExactly(Money.gbp("1250.00"), Money.gbp("-400.00"), Money.gbp("999.50"),
                        Money.gbp("-25.00"), Money.gbp("75.00"));
    }

    @Test
    void rejectsAStatementForAnAccountWeDoNotHave() throws Exception {
        String xml = new String(sample()).replace("GB33BUKB20201555555555", "GB94BARC10201530093459");

        MvcTestResult response = upload("other.xml", xml.getBytes());

        assertThat(response).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Unknown account");
    }

    @Test
    void savesNothingFromAStatementThatDoesNotBalance() throws Exception {
        long before = items.count();
        String xml = new String(sample()).replace("11899.50", "11899.51");

        MvcTestResult response = upload("bad.xml", xml.getBytes());

        assertThat(response).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response).bodyJson().extractingPath("$.detail").asString().contains("does not balance");
        assertThat(items.count()).isEqualTo(before);
    }

    private MvcTestResult upload(String name, byte[] content) {
        return mvc.post().uri("/imports/camt053")
                .multipart()
                .file(new MockMultipartFile("file", name, "application/xml", content))
                .exchange();
    }

    private byte[] sample() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/samples/camt053-nostro-2026-11-06.xml")) {
            return in.readAllBytes();
        }
    }
}
```

- **`.multipart().file(new MockMultipartFile(...))`** builds a multipart request without a real HTTP connection. The first argument, `"file"`, is the part name that must match `@RequestParam("file")`.
- **`savesNothingFromAStatementThatDoesNotBalance`** proves the all-or-nothing claim. The parser streamed all five entries to the listener, which saved them, and only then found the closing balance was 1p out. The item count afterwards is unchanged, so the transaction rolled every row back.

Run all the tests (right-click `src/test/java` → **Run 'All Tests'**, or `./mvnw test`). Green.

---

## Your turn

### Exercise 1: only booked entries

A camt.053 statement should only contain **booked** entries (`<Sts><Cd>BOOK</Cd></Sts>`). Pending ones (`PDNG`) belong in intraday reports such as camt.052, and an entry that might still change has no business in a reconciliation. Make the parser reject any entry whose status is present and isn't `BOOK`, with the message `Ntry 1: status PDNG; a statement only contains booked entries`, and add a test to `Camt053ParserTest`.

<details>
<summary>Hint</summary>

Add a `status` field to `Draft`, a `case` for `NTRY + "/Sts/Cd"`, and the check at the top of `toEntry`. For the test, take the `entry(...)` helper's XML and `.replace("<BookgDt>", "<Sts><Cd>PDNG</Cd></Sts><BookgDt>")`.

</details>

<details>
<summary>Solution</summary>

In `Camt053Parser`, next to the other `NTRY` cases:

```java
                    case NTRY + "/Sts/Cd" -> ntry.status = text(reader, path);
```

At the start of `toEntry`, after `where` is set:

```java
        if (d.status != null && !d.status.equals("BOOK")) {
            throw new StatementFormatException(where + ": status " + d.status + "; a statement only contains booked entries");
        }
```

And in `Draft`:

```java
        String entryRef, status, bookingDate, valueDate, accountServicerRef, endToEndId, remittanceInfo;
```

In `Camt053ParserTest`:

```java
    @Test
    void rejectsAnEntryThatIsNotBooked() {
        String pending = entry("1", "50.00", "CRDT", "INV-1")
                .replace("<BookgDt>", "<Sts><Cd>PDNG</Cd></Sts><BookgDt>");
        String xml = statement("0.00", "50.00", pending);

        assertThatThrownBy(() -> parser.parse(stream(xml), new Recorder()))
                .isInstanceOf(StatementFormatException.class)
                .hasMessage("Ntry 1: status PDNG; a statement only contains booked entries");
    }
```

The check allows a missing `Sts`, because the test helpers (and some older bank files) leave it out. Older versions of camt.053 (such as `001.02`) put the code directly in the element, `<Sts>BOOK</Sts>`; later versions wrap it in `<Cd>`. A parser for production would handle both, according to the version in the namespace.

</details>

### Exercise 2: a proper 404

`GET /imports/999999` currently throws `ImportNotFoundException`, which surfaces as a `500`. Map it to a `404` `ProblemDetail` titled `"Import not found"`, and add a test to `StatementImportApiTest` that checks the status, the `application/problem+json` content type and the title.

<details>
<summary>Solution</summary>

In `ApiExceptionHandler` (import `ImportNotFoundException`):

```java
    @ExceptionHandler(ImportNotFoundException.class)
    ProblemDetail handle(ImportNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Import not found", ex.getMessage());
    }
```

In `StatementImportApiTest` (import `org.springframework.http.MediaType`):

```java
    @Test
    void returnsProblemDetailForAnUnknownImport() {
        MvcTestResult response = mvc.get().uri("/imports/999999").exchange();

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Import not found");
    }
```

</details>

---

## Checkpoint

```bash
./mvnw clean test
```

```
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
```

(3 in `MoneyTest`, 6 in `Camt053ParserTest`, 1 in `XxeTest` and 4 in `StatementImportApiTest`, with both exercises done.) Use `clean` when you want an exact count: test classes you've renamed or deleted can linger in `target/` and still run.

Your project should look like this (`main` classes under `src/main/java/com/example/recon/`, tests under `src/test/java/com/example/recon/`):

```
reconciliation/
├── pom.xml
├── requests.http
├── samples/camt053-nostro-2026-11-06.xml
└── src/
    ├── main/
    │   ├── java/…/recon/
    │   │   ├── ReconciliationApplication.java
    │   │   ├── account/    Account, AccountRepository, AccountType, UnknownAccountException
    │   │   ├── imports/    FileType, ImportController, ImportNotFoundException, ImportService,
    │   │   │               ImportedFile, ImportedFileRepository
    │   │   ├── item/       ItemStatus, ReconItem, ReconItemRepository, Side
    │   │   ├── money/      Money
    │   │   ├── statement/  Camt053Parser, CreditDebit, StatementEntry, StatementFormatException,
    │   │   │               StatementListener, StatementSummary
    │   │   └── web/        ApiExceptionHandler
    │   └── resources/
    │       ├── application.properties
    │       └── db/migration/V1__accounts_imports_and_items.sql
    └── test/
        ├── java/…/recon/
        │   ├── ReconIntegrationTest.java
        │   ├── imports/    StatementImportApiTest
        │   ├── money/      MoneyTest
        │   └── statement/  Camt053ParserTest, XxeTest
        └── resources/samples/camt053-nostro-2026-11-06.xml
```

---

## Common errors

**`Expecting code to raise a throwable.` in `XxeTest`**
The parser resolved the external entity and read the file. `secureFactory()` is missing `SUPPORT_DTD = false`. That's the point of step 8: never ship a parser that fails this test.

**`Not well-formed XML: ParseError at [row,col]:[1,22] Message: elementGetText() function expects text only elment but START_ELEMENT was encountered.`**
You called `text(...)` (and so `getElementText()`) on an element that has child elements, such as `Ntry` or `Bal`. Only call it on leaf elements, the ones with nothing but text inside. (The typo "elment" is the JDK's, not yours.)

**`Not a camt.053 document (root element project, namespace http://maven.apache.org/POM/4.0.0)`**
You uploaded the wrong file. If it really is a statement, check its namespace: a camt.052 or camt.054 also starts with `<Document>`, but in a different namespace.

**`Statement STMT-… does not balance: opening GBP 10000.00 + credits … = …, but closing balance is …`**
The file is incomplete or corrupt, or you edited the sample and forgot to fix the closing balance. Nothing was saved. Ask the sender for a corrected file; never "fix" a bank's statement yourself.

**`400 Bad Request` with `"detail": "Required part 'file' is not present."`**
The multipart field isn't called `file`. Check `name="file"` in `requests.http`, `-F file=@…` in curl, or the first argument of `MockMultipartFile`.

**`415 Unsupported Media Type` with `"detail": "Content-Type 'application/xml' is not supported."`**
You POSTed the XML as the raw request body. The endpoint takes a multipart upload. Use `-F` with curl, not `--data-binary`.

**`413 Content Too Large` with `"detail": "Maximum upload size exceeded"`**
The file is bigger than Spring Boot's default 1MB limit. Set `spring.servlet.multipart.max-file-size` and `spring.servlet.multipart.max-request-size` to what your real statements need.

**`Cannot resolve symbol 'AutoConfigureMockMvc'`**
In Spring Boot 4 it lives in `org.springframework.boot.webmvc.test.autoconfigure`. Older answers online use `org.springframework.boot.test.autoconfigure.web.servlet`.

---

## Recap

- **Reconciliation** proves that two records of the same money agree. What doesn't match is a **break**: either a **timing difference** that will clear itself, or a **genuine error** someone must fix.
- A **nostro** is your account at another bank; the same account is their **vostro**. Their statement is the mirror image of your books: their **CRDT** is your **debit**. Converting both sides to one **signed amount** from the bank's point of view makes matching simple.
- **camt.053** is the ISO 20022 end-of-day statement: balances (`OPBD`, `CLBD`), and entries with a positive `Amt`, a `CdtDbtInd`, booking and value dates, and references (`NtryRef`, `AcctSvcrRef`, `EndToEndId`).
- **Always check a statement balances** (opening + credits − debits = closing) before trusting any line of it.
- **StAX** streams XML one event at a time. Track your position with a path stack, build each entry in a scratch object, and hand it to a listener. Memory use stays flat whatever the file size.
- **Streaming plus a transaction** gives all-or-nothing imports: rows are written as they stream, and a late failure rolls them all back.
- **XXE**: the JDK's StAX parser resolves external entities by default. Turn off DTD support and external entities, and prove it with a test.
- Uploads arrive as **`multipart/form-data`**; test them with `MockMvcTester`'s `.multipart().file(...)`.

### Interview angle

<details>
<summary>"What is reconciliation and why does a bank do it every day?"</summary>

Checking that the bank's own record of some money (its ledger) agrees with another party's record of the same money (a correspondent bank's statement, a payment scheme's settlement report, the central bank). Items are matched, and whatever doesn't match is a break to investigate. It's done daily because breaks are much easier to explain while they're fresh, because some are real losses or fraud that need acting on quickly, and because unexplained differences are an operational risk that auditors and regulators expect to be controlled. Mention timing differences versus genuine errors, and that ops teams track breaks by age.

</details>

<details>
<summary>"How would you parse a very large XML file in Java?"</summary>

Stream it with StAX (`XMLStreamReader`) or SAX instead of loading a DOM. Keep only the current record and running totals in memory, and pass each record on as soon as it's complete, for example to a listener that writes it to the database in batches. If you want typed objects, you can combine StAX with JAXB to unmarshal one element at a time. Configure the parser securely (no DTDs, no external entities), and decide what happens on a failure part-way through: for a bank statement, roll back the whole file.

</details>

<details>
<summary>"What is XXE and how do you prevent it?"</summary>

XML External Entity injection: a document declares an entity whose value is loaded from a URI (a local file or an internal URL), and a parser that resolves it puts that content into the parsed data, leaking files or making server-side requests. Prevent it by disabling DTD processing and external entities on every parser factory (for StAX, `SUPPORT_DTD` and `IS_SUPPORTING_EXTERNAL_ENTITIES` set to false), ideally in one shared, tested factory method. Write a test with a malicious payload to prove it. Disabling DTDs also stops entity-expansion ("billion laughs") attacks.

</details>

<details>
<summary>"A correspondent's statement says CRDT £500. What should the matching line in our books look like?"</summary>

A **debit** of £500 on our nostro account. The statement is written from the correspondent's side, where our account is a liability to them (their vostro), so money arriving is a credit. In our books the nostro is an asset, so the same money arriving is a debit. That mirror-image relationship is why reconciliation systems usually normalise both sides to one sign convention before matching.

</details>

### Further reading

- [ISO 20022 message definitions](https://www.iso20022.org/iso-20022-message-definitions): the official catalogue, including the camt.053 Bank-to-Customer Statement
- [OWASP XML External Entity Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/XML_External_Entity_Prevention_Cheat_Sheet.html): settings for every Java XML API
- [`XMLInputFactory` Javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/java.xml/javax/xml/stream/XMLInputFactory.html): the StAX properties, including `SUPPORT_DTD`
- [Bank of England: CHAPS transitions to the latest global financial messaging standard](https://www.bankofengland.co.uk/news/2023/june/chaps-transitions-to-latest-global-financial-messaging-standard): ISO 20022 in UK high-value payments

**Next:** [02: Scheme reports, CSV and duplicate files](02-scheme-reports-and-csv.md). The second external record, a CSV file that's never quite as tidy as XML, and making sure no file is ever imported twice.
