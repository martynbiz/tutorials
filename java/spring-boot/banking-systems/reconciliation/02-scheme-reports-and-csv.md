# 02: Scheme Reports, CSV and Duplicate Files

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- What a payment scheme's **settlement report** is, and why it's the second external record a bank reconciles against
- Why CSV is harder than it looks (quotes, commas inside fields, Excel's byte order mark), and why to parse it with a well-known library
- How to validate **every line** of a file and collect the problems into an **error report**, instead of stopping at the first bad line
- Reading money and dates from text safely: `BigDecimal`, ISO dates, no silent rounding
- How to stop the same file being imported twice: a **SHA-256 checksum** plus a **unique constraint**
- Why "check, then insert" isn't enough on its own, proved with a concurrent test

By the end, the bank can import its payment scheme's daily CSV report alongside the nostro statement, keep the good lines, list the bad ones with line numbers, and refuse any file it has already seen.

---

## Concepts

### How it works in a real bank

When a bank is a member of a payment scheme, such as [Faster Payments](../glossary.md#faster-payments) or [Bacs](../glossary.md#bacs), payments flow between banks all day, but the money itself moves later, at [settlement](../glossary.md#settlement). Many schemes settle on a **deferred net** basis: at agreed times, each bank's payments sent and received are added up, and only the net difference moves between the banks' accounts at the central bank.

The bank needs to know that the scheme's view of what it sent and received agrees with its own. So, alongside the settlement itself, the scheme or its operator provides **reports**: which payments were included, in which cycle, in which direction. The bank reconciles those reports against the postings on its **scheme settlement account**, an [internal account](../glossary.md#internal-account) that holds what the bank owes the scheme, or is owed by it, until settlement.

Two things make this a different problem from the nostro statement in guide 01:

- **The format.** Report formats vary by scheme and by service provider. Some are ISO 20022, some are fixed-width text, and a great many are CSV, because everybody can produce and open one.
- **The tolerance for partial files.** A bank statement that doesn't balance is useless, so guide 01 rejects the whole file. A report with one malformed line out of thousands still contains thousands of good lines. Whether to accept the good ones is a **policy decision**: some banks reject the whole file and ask for a resend; others accept the good lines and **quarantine** the bad ones for someone to chase. This guide does the second, and keeps a permanent record of every rejected line.

> **Simplification:** the CSV layout in this guide is invented for the tutorial. Real scheme and provider reports have their own documented formats, usually with header and trailer records carrying control totals. The techniques (a library for the format, per-line validation, an error report, duplicate detection) carry straight over.

### Why not just `line.split(",")`?

CSV looks trivial until real files arrive:

```csv
settlement_date,cycle,reference,direction,amount,currency,description
2026-11-06,1,SCH-0002,DR,45.10,GBP,"Refund, order 77"
```

The description contains a comma, so it's quoted. Quotes inside quoted fields are doubled (`""`). A quoted field can even contain a line break. Files saved by Excel as "CSV UTF-8" start with an invisible **byte order mark** (BOM). [RFC 4180](https://www.rfc-editor.org/rfc/rfc4180) describes the common format, but many producers only roughly follow it.

`split(",")` gets the second line above wrong. You could write a proper state-machine parser, but that's code to maintain for a solved problem. This guide uses **[Apache Commons CSV](https://commons.apache.org/proper/commons-csv/)**: small, mature and widely used. The library handles the *format*. **Your** code still handles the *meaning*: is this a valid date, a positive amount, a known direction?

### Validate everything, report everything

When a file has problems, the person fixing it wants **every** problem, with line numbers, in one go. Stopping at the first error means fix, resend, find the next error, fix, resend…

So the parser catches each line's failure, records it as a `LineError(lineNumber, message)`, and carries on. Only problems that make the whole file unusable (missing columns, broken quoting) stop it.

### Never import the same file twice

Files get sent twice. A sender retries after a timeout, an operator uploads yesterday's file by mistake, a scheduled job runs twice. If the same report is imported twice, every line becomes two items, half of which can never match, and the break report fills with phantom breaks.

How do you recognise "the same file"?

| Idea | Problem |
|---|---|
| File name | Names repeat (`report.csv`), and the same content can arrive under a different name |
| The sender's id (`Stmt/Id`, `MsgId`) | Good when present, but CSV reports often have none, and you're trusting the sender to never reuse one |
| **A checksum of the bytes** | Identical bytes, identical checksum, whatever the file is called |

A **SHA-256** digest is a 64-hex-character fingerprint of the content. Two different files producing the same SHA-256 by accident is so unlikely it's treated as impossible. Store it with a **unique constraint**, and the database itself will refuse a second copy.

Why the constraint, if the code checks first? Because "check, then insert" has a gap. Two uploads of the same file at the same moment can both check, both find nothing, and both insert. You'll see that happen in a test, then see the constraint catch it.

---

## Steps

### 1. Add Commons CSV

Commons CSV isn't managed by Spring Boot, so give it an explicit version. In `pom.xml`, inside `<dependencies>`:

```xml
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-csv</artifactId>
    <version>1.14.1</version>
</dependency>
```

In IntelliJ, click the **Load Maven Changes** button that appears (or press <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>O</kbd>).

### 2. The migration

Create `src/main/resources/db/migration/V2__scheme_reports_and_duplicates.sql`:

```sql
-- Duplicate-file detection: the SHA-256 of the file's bytes, unique across all imports.
-- Nullable only so rows imported before this migration stay valid.
ALTER TABLE imported_file ADD COLUMN sha256 VARCHAR(64);
ALTER TABLE imported_file ADD CONSTRAINT uk_imported_file_sha256 UNIQUE (sha256);

ALTER TABLE imported_file ADD COLUMN error_count INT DEFAULT 0 NOT NULL;

-- Lines we couldn't accept, kept so someone can chase them
CREATE TABLE import_error (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    imported_file_id BIGINT       NOT NULL REFERENCES imported_file (id),
    line_number      INT          NOT NULL,
    message          VARCHAR(255) NOT NULL
);

-- The bank's settlement position with a payment scheme
INSERT INTO account (code, name, type, currency, external_id)
VALUES ('SCHEME_SETTLEMENT', 'Payment scheme settlement account', 'ASSET', 'GBP', NULL);
```

- **`sha256` is nullable** because your `data/` database already holds files from guide 01 with no checksum. Both H2 and PostgreSQL allow many `NULL`s in a unique column, so those old rows don't clash. Every new import sets it.
- **`error_count DEFAULT 0 NOT NULL`**: adding a `NOT NULL` column to a table with rows needs a default, or existing rows would break the rule.
- **The settlement account is an `ASSET` here**, following the same convention as the nostro: positive means money is owed *to* the bank. Real ledgers model scheme positions in different ways; what matters for matching is that both sides use the same sign.

### 3. A sample report

Create `samples/scheme-report-2026-11-06.csv`, and copy it to `src/test/resources/samples/`:

```csv
settlement_date,cycle,reference,direction,amount,currency,description
2026-11-06,1,SCH-0001,CR,120.00,GBP,Payment received
2026-11-06,1,SCH-0002,DR,45.10,GBP,"Refund, order 77"
2026-11-06,2,SCH-0003,CR,1000,GBP,Payment received
2026-11-06,2,SCH-0004,DR,12.345,GBP,Payment sent
2026-11-31,3,SCH-0005,CR,80.00,GBP,Payment received
2026-11-06,3,SCH-0006,XX,10.00,GBP,Payment sent
```

`CR` means money towards the bank, `DR` money away from it. Three of the six lines are wrong on purpose: half a penny, 31 November, and a direction of `XX`. Line numbers count the header as line 1, the way a text editor does, so those are lines 5, 6 and 7.

### 4. The report types

Create the package `com.example.recon.scheme`.

`scheme/SchemeLine.java`, one valid line:

```java
package com.example.recon.scheme;

import com.example.recon.money.Money;

import java.time.LocalDate;

/** One valid line of a scheme settlement report. */
public record SchemeLine(
        int lineNumber,
        LocalDate settlementDate,
        int cycle,
        String reference,
        Direction direction,
        Money amount,
        String description) {

    /** CR: the scheme owes us (money towards the bank). DR: we owe the scheme. */
    public enum Direction { CR, DR }

    public Money signedAmount() {
        return direction == Direction.CR ? amount : amount.negate();
    }
}
```

`scheme/LineError.java` and `scheme/ParsedReport.java`:

```java
package com.example.recon.scheme;

/** Why one line of a file was rejected. Line numbers count the header as line 1. */
public record LineError(long lineNumber, String message) {
}
```

```java
package com.example.recon.scheme;

import java.util.List;

/** Every line of a report ends up in exactly one of these two lists. */
public record ParsedReport(List<SchemeLine> lines, List<LineError> errors) {
}
```

And `scheme/SchemeReportFormatException.java`, for files that are unusable as a whole:

```java
package com.example.recon.scheme;

/** The file as a whole is unusable (wrong headers, not CSV): reject all of it. */
public class SchemeReportFormatException extends RuntimeException {

    public SchemeReportFormatException(String message) {
        super(message);
    }
}
```

### 5. The parser

`scheme/SchemeReportParser.java`:

```java
package com.example.recon.scheme;

import com.example.recon.money.Money;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads a scheme settlement report. Every line is checked; good lines and
 * errors are both returned, so one bad line doesn't hide the other 9,999.
 */
@Component
public class SchemeReportParser {

    static final List<String> HEADERS =
            List.of("settlement_date", "cycle", "reference", "direction", "amount", "currency", "description");

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()                 // read the column names from the first line
            .setSkipHeaderRecord(true)
            .setTrim(true)
            .setIgnoreEmptyLines(true)
            .get();

    public ParsedReport parse(InputStream in) {
        List<SchemeLine> lines = new ArrayList<>();
        List<LineError> errors = new ArrayList<>();
        Set<String> referencesSeen = new HashSet<>();

        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
             CSVParser csv = FORMAT.parse(reader)) {
            checkHeaders(csv.getHeaderNames());
            for (CSVRecord record : csv) {
                long lineNumber = record.getRecordNumber() + 1; // + 1 for the header line
                try {
                    SchemeLine line = toLine(record, (int) lineNumber);
                    if (!referencesSeen.add(line.reference())) {
                        throw new IllegalArgumentException("duplicate reference " + line.reference() + " in this file");
                    }
                    lines.add(line);
                } catch (IllegalArgumentException e) {
                    errors.add(new LineError(lineNumber, e.getMessage()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (UncheckedIOException e) {
            // Commons CSV reports broken quoting (e.g. an unclosed quote) this way
            throw new SchemeReportFormatException("Not valid CSV: " + e.getMessage());
        }
        return new ParsedReport(List.copyOf(lines), List.copyOf(errors));
    }

    private static void checkHeaders(List<String> actual) {
        List<String> missing = HEADERS.stream().filter(h -> !actual.contains(h)).toList();
        if (!missing.isEmpty()) {
            throw new SchemeReportFormatException("Missing columns " + missing + "; found " + actual);
        }
    }

    private static SchemeLine toLine(CSVRecord record, int lineNumber) {
        if (!record.isConsistent()) {
            throw new IllegalArgumentException(
                    "expected %d columns but found %d".formatted(HEADERS.size(), record.size()));
        }
        LocalDate date = date(record.get("settlement_date"));
        int cycle = cycle(record.get("cycle"));
        String reference = reference(record.get("reference"));
        SchemeLine.Direction direction = direction(record.get("direction"));
        Money amount = amount(record.get("amount"), record.get("currency"));
        String description = record.get("description");
        if (description.length() > 140) {
            throw new IllegalArgumentException("description is longer than 140 characters");
        }
        return new SchemeLine(lineNumber, date, cycle, reference, direction, amount, description);
    }

    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value); // ISO 8601: 2026-11-06, nothing else
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("settlement_date '" + value + "' is not a date like 2026-11-06");
        }
    }

    private static int cycle(String value) {
        try {
            int cycle = Integer.parseInt(value);
            if (cycle < 1) {
                throw new NumberFormatException();
            }
            return cycle;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("cycle '" + value + "' is not a positive whole number");
        }
    }

    private static String reference(String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("reference is blank");
        }
        if (value.length() > 35) {
            throw new IllegalArgumentException("reference is longer than 35 characters");
        }
        return value;
    }

    private static SchemeLine.Direction direction(String value) {
        return switch (value) {
            case "CR" -> SchemeLine.Direction.CR;
            case "DR" -> SchemeLine.Direction.DR;
            default -> throw new IllegalArgumentException("direction '" + value + "' must be CR or DR");
        };
    }

    private static Money amount(String value, String currency) {
        if (!"GBP".equals(currency)) {
            throw new IllegalArgumentException("currency '" + currency + "' is not GBP");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("amount '" + value + "' is not a number");
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive; the sign is in direction");
        }
        return new Money(amount, Money.GBP); // throws IllegalArgumentException for 10.001
    }
}
```

The ideas to take away:

- **One exception type per line.** Every field check throws `IllegalArgumentException` with a message written for the person who'll read the error report. The loop catches it, records the line number, and moves on. `NumberFormatException` is a subclass of `IllegalArgumentException`, and so is the exception `Money` throws for `12.345`, so those land in the report too.
- **Columns by name, not position.** `setHeader()` with no arguments reads the names from the first line, and `record.get("amount")` finds the right column even if the sender reorders them. `checkHeaders` turns a missing column into one clear whole-file error instead of thousands of identical line errors.
- **`record.isConsistent()`** is false when a line has a different number of fields from the header, usually a missing or extra comma.
- **Strict parsing of money and dates.** `new BigDecimal("1000")` is exact, and `Money` then fixes the scale to `1000.00`. `LocalDate.parse` accepts only ISO `2026-11-06`: no `06/11/2026`, which means 6 November in the UK and 11 June in the US. `2026-11-31` is rejected because November has 30 days.
- **Duplicates inside one file** are caught with a `Set` of references. The same reference twice in one report is a defect in the report.
- **`.get()`** builds the format. Older examples call `.build()`, which still works but is deprecated in recent Commons CSV versions.

### 6. Parser tests

Create `src/test/java/com/example/recon/scheme/SchemeReportParserTest.java`:

```java
package com.example.recon.scheme;

import com.example.recon.money.Money;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemeReportParserTest {

    private final SchemeReportParser parser = new SchemeReportParser();

    @Test
    void keepsTheGoodLinesAndReportsEveryBadOne() throws Exception {
        ParsedReport report;
        try (InputStream in = getClass().getResourceAsStream("/samples/scheme-report-2026-11-06.csv")) {
            report = parser.parse(in);
        }

        assertThat(report.lines()).extracting(SchemeLine::reference)
                .containsExactly("SCH-0001", "SCH-0002", "SCH-0003");
        assertThat(report.lines().get(1).signedAmount()).isEqualTo(Money.gbp("-45.10"));
        assertThat(report.lines().get(1).description()).isEqualTo("Refund, order 77");
        assertThat(report.lines().get(2).amount()).isEqualTo(Money.gbp("1000.00"));

        assertThat(report.errors()).containsExactly(
                new LineError(5, "12.345 has too many decimal places for GBP"),
                new LineError(6, "settlement_date '2026-11-31' is not a date like 2026-11-06"),
                new LineError(7, "direction 'XX' must be CR or DR"));
    }

    @Test
    void reportsAWrongNumberOfColumnsAndARepeatedReference() {
        ParsedReport report = parser.parse(csv("""
                settlement_date,cycle,reference,direction,amount,currency,description
                2026-11-06,1,SCH-1,CR,1.00,GBP,ok
                2026-11-06,1,SCH-2,CR,1.00,GBP
                2026-11-06,1,SCH-1,CR,1.00,GBP,again
                """));

        assertThat(report.lines()).hasSize(1);
        assertThat(report.errors()).containsExactly(
                new LineError(3, "expected 7 columns but found 6"),
                new LineError(4, "duplicate reference SCH-1 in this file"));
    }

    @Test
    void rejectsTheWholeFileWhenColumnsAreMissing() {
        assertThatThrownBy(() -> parser.parse(csv("""
                date,reference,amount
                2026-11-06,SCH-1,1.00
                """)))
                .isInstanceOf(SchemeReportFormatException.class)
                .hasMessageStartingWith("Missing columns");
    }

    @Test
    void rejectsTheWholeFileWhenTheQuotingIsBroken() {
        assertThatThrownBy(() -> parser.parse(csv("""
                settlement_date,cycle,reference,direction,amount,currency,description
                2026-11-06,1,SCH-1,CR,1.00,GBP,"Refund, order 77
                """)))
                .isInstanceOf(SchemeReportFormatException.class)
                .hasMessageStartingWith("Not valid CSV");
    }

    private static InputStream csv(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
```

Because `LineError` is a record, `containsExactly(new LineError(5, "…"))` compares by value. The test reads like the error report a person would get. Run it: four green ticks.

### 7. Checksums

`imports/Checksums.java`:

```java
package com.example.recon.imports;

import org.springframework.core.io.InputStreamSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 fingerprints of files, so the same file can't be imported twice. */
public final class Checksums {

    private Checksums() {
    }

    public static String sha256(InputStreamSource source) {
        try (InputStream in = source.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) != -1; ) {
                digest.update(buffer, 0, n); // a chunk at a time: big files never sit in memory
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime must support SHA-256", e);
        }
    }
}
```

- **`InputStreamSource`** is a one-method Spring interface: "something that can give you a *fresh* `InputStream` each time you ask". `MultipartFile` implements it, and so does `ByteArrayResource`, which tests will use. The service needs to read each file twice (once to fingerprint, once to parse), so a single `InputStream` won't do.
- **`HexFormat`** (Java 17+) turns the 32-byte digest into 64 lowercase hex characters.
- The Java specification requires every runtime to support SHA-256, so `NoSuchAlgorithmException` can't really happen. Wrapping it in an unchecked exception says so.

And `imports/DuplicateFileException.java`:

```java
package com.example.recon.imports;

public class DuplicateFileException extends RuntimeException {

    public DuplicateFileException(String sha256) {
        super("This file has already been imported (SHA-256 " + sha256 + ")");
    }
}
```

### 8. Storing checksums and line errors

In `ImportedFile`, add the two new columns, take the checksum in the constructor, and count errors in `completed`:

```java
    @Column(length = 64)
    private String sha256;

    @Column(name = "error_count", nullable = false)
    private int errorCount;

    ...

    public ImportedFile(FileType fileType, String fileName, String accountCode, String sha256) {
        this.fileType = fileType;
        this.fileName = fileName;
        this.accountCode = accountCode;
        this.sha256 = sha256;
        this.importedAt = Instant.now();
    }

    public void completed(String externalId, int itemCount, int errorCount) {
        this.externalId = externalId;
        this.itemCount = itemCount;
        this.errorCount = errorCount;
    }
```

Generate getters for `sha256` and `errorCount`. Add `SCHEME_CSV` to `FileType`, and a query to `ImportedFileRepository`:

```java
    boolean existsBySha256(String sha256);
```

Now the error entity, `imports/ImportError.java`:

```java
package com.example.recon.imports;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "import_error")
public class ImportError {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "imported_file_id", nullable = false)
    private Long importedFileId;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(nullable = false)
    private String message;

    protected ImportError() {
        // for JPA
    }

    public ImportError(Long importedFileId, int lineNumber, String message) {
        this.importedFileId = importedFileId;
        this.lineNumber = lineNumber;
        this.message = message.length() > 255 ? message.substring(0, 255) : message;
    }

    // + getters for lineNumber and message
}
```

The constructor truncates long messages to fit the column. A parse message quoting a long bad value shouldn't be able to make the import itself fail.

`imports/ImportErrorRepository.java`:

```java
package com.example.recon.imports;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImportErrorRepository extends JpaRepository<ImportError, Long> {

    List<ImportError> findByImportedFileIdOrderByLineNumber(Long importedFileId);
}
```

### 9. The import service, both formats

Replace `imports/ImportService.java`. Both imports now take an `InputStreamSource`, fingerprint it first, and refuse a file they've seen:

```java
package com.example.recon.imports;

import com.example.recon.account.Account;
import com.example.recon.account.AccountRepository;
import com.example.recon.account.UnknownAccountException;
import com.example.recon.item.ReconItem;
import com.example.recon.item.ReconItemRepository;
import com.example.recon.scheme.LineError;
import com.example.recon.scheme.ParsedReport;
import com.example.recon.scheme.SchemeLine;
import com.example.recon.scheme.SchemeReportParser;
import com.example.recon.statement.Camt053Parser;
import com.example.recon.statement.StatementEntry;
import com.example.recon.statement.StatementListener;
import com.example.recon.statement.StatementSummary;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

@Service
public class ImportService {

    static final String SCHEME_ACCOUNT = "SCHEME_SETTLEMENT";

    private final Camt053Parser camt053Parser;
    private final SchemeReportParser schemeReportParser;
    private final AccountRepository accounts;
    private final ImportedFileRepository files;
    private final ImportErrorRepository errors;
    private final ReconItemRepository items;

    public ImportService(Camt053Parser camt053Parser, SchemeReportParser schemeReportParser,
                         AccountRepository accounts, ImportedFileRepository files,
                         ImportErrorRepository errors, ReconItemRepository items) {
        this.camt053Parser = camt053Parser;
        this.schemeReportParser = schemeReportParser;
        this.accounts = accounts;
        this.files = files;
        this.errors = errors;
        this.items = items;
    }

    /** All or nothing: if anything in the file is wrong, the transaction rolls back every row. */
    @Transactional
    public ImportedFile importCamt053(String fileName, InputStreamSource content) {
        String sha256 = Checksums.sha256(content);
        rejectIfAlreadyImported(sha256);
        StatementWriter writer = new StatementWriter(fileName, sha256);
        try (InputStream in = content.getInputStream()) {
            StatementSummary summary = camt053Parser.parse(in, writer);
            writer.file.completed(summary.statementId(), summary.entryCount(), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return writer.file;
    }

    /** Good lines become items; bad lines are recorded, with their line numbers, for someone to chase. */
    @Transactional
    public ImportedFile importSchemeReport(String fileName, InputStreamSource content) {
        String sha256 = Checksums.sha256(content);
        rejectIfAlreadyImported(sha256);
        ParsedReport report;
        try (InputStream in = content.getInputStream()) {
            report = schemeReportParser.parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        ImportedFile file = save(new ImportedFile(FileType.SCHEME_CSV, fileName, SCHEME_ACCOUNT, sha256));
        for (SchemeLine line : report.lines()) {
            items.save(ReconItem.external(SCHEME_ACCOUNT, file.getId(), line.reference(), line.signedAmount(),
                    line.settlementDate(), line.settlementDate(), line.description()));
        }
        for (LineError error : report.errors()) {
            errors.save(new ImportError(file.getId(), (int) error.lineNumber(), error.message()));
        }
        file.completed(null, report.lines().size(), report.errors().size());
        return file;
    }

    @Transactional(readOnly = true)
    public ImportedFile get(Long id) {
        return files.findById(id).orElseThrow(() -> new ImportNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<ImportError> errorsFor(Long id) {
        return errors.findByImportedFileIdOrderByLineNumber(id);
    }

    /** The friendly check. Two uploads at the same instant can both pass it, so see save() too. */
    private void rejectIfAlreadyImported(String sha256) {
        if (files.existsBySha256(sha256)) {
            throw new DuplicateFileException(sha256);
        }
    }

    private ImportedFile save(ImportedFile file) {
        return files.saveAndFlush(file);
    }

    /** Saves each entry as the parser streams it past. */
    private class StatementWriter implements StatementListener {

        private final String fileName;
        private final String sha256;
        private Account account;
        private ImportedFile file;

        StatementWriter(String fileName, String sha256) {
            this.fileName = fileName;
            this.sha256 = sha256;
        }

        @Override
        public void account(String iban) {
            account = accounts.findByExternalId(iban)
                    .orElseThrow(() -> new UnknownAccountException("No account with IBAN " + iban));
            file = save(new ImportedFile(FileType.CAMT053, fileName, account.getCode(), sha256));
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

- **The checksum comes first**, before any parsing, so a duplicate is refused cheaply.
- **The CSV parser returns a list** rather than streaming to a listener like the XML one. For daily reports that's fine, and it keeps the service simple. If reports grew to millions of lines, you'd give it a listener too.
- **Scheme lines use the settlement date as both booking and value date.** That's the date the money actually moved.
- **`save` is a one-liner for now.** It's a method of its own because you're about to improve it.

### 10. The controller

Replace `imports/ImportController.java`. Both endpoints pass the `MultipartFile` straight to the service, since it *is* an `InputStreamSource`, and the response now includes the error report:

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

import java.net.URI;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/imports")
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    @PostMapping(path = "/camt053", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResponse> camt053(@RequestParam("file") MultipartFile file) {
        return created(importService.importCamt053(file.getOriginalFilename(), file));
    }

    @PostMapping(path = "/scheme-report", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResponse> schemeReport(@RequestParam("file") MultipartFile file) {
        return created(importService.importSchemeReport(file.getOriginalFilename(), file));
    }

    @GetMapping("/{id}")
    public ImportResponse get(@PathVariable Long id) {
        return response(importService.get(id));
    }

    private ResponseEntity<ImportResponse> created(ImportedFile file) {
        return ResponseEntity.created(URI.create("/imports/" + file.getId())).body(response(file));
    }

    private ImportResponse response(ImportedFile file) {
        List<LineErrorResponse> errors = importService.errorsFor(file.getId()).stream()
                .map(e -> new LineErrorResponse(e.getLineNumber(), e.getMessage()))
                .toList();
        return new ImportResponse(file.getId(), file.getFileType(), file.getFileName(), file.getAccountCode(),
                file.getExternalId(), file.getSha256(), file.getItemCount(), file.getErrorCount(), errors,
                file.getImportedAt());
    }

    public record ImportResponse(Long id, FileType fileType, String fileName, String accountCode,
                                 String externalId, String sha256, int itemCount, int errorCount,
                                 List<LineErrorResponse> errors, Instant importedAt) {
    }

    public record LineErrorResponse(int line, String message) {
    }
}
```

A report with bad lines still returns `201 Created`: the file *was* imported, and the body says exactly which lines weren't. A client that only checks the status code would miss them, which is why `errorCount` sits near the top of the response.

Map the two new exceptions in `ApiExceptionHandler` (import them):

```java
    @ExceptionHandler(SchemeReportFormatException.class)
    ProblemDetail handle(SchemeReportFormatException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid scheme report", ex.getMessage());
    }

    @ExceptionHandler(DuplicateFileException.class)
    ProblemDetail handle(DuplicateFileException ex) {
        return problem(HttpStatus.CONFLICT, "Duplicate file", ex.getMessage());
    }
```

A duplicate is **`409 Conflict`**: nothing is wrong with the file itself, but it conflicts with what the server already holds.

`StatementImportApiTest` only talks to the API, so it needs no changes. Run it: still green.

### 11. API tests for reports

From now on, tests in different classes share one database **and** the checksum rule. If two tests upload byte-for-byte the same file, the second one gets a `409`. So every test builds its own content with a unique reference.

`src/test/java/com/example/recon/imports/SchemeReportImportApiTest.java`:

```java
package com.example.recon.imports;

import com.example.recon.ReconIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ReconIntegrationTest
class SchemeReportImportApiTest {

    @Autowired MockMvcTester mvc;

    @Test
    void importsTheGoodLinesAndListsTheBadOnes() {
        String ref = unique();
        MvcTestResult response = upload("""
                settlement_date,cycle,reference,direction,amount,currency,description
                2026-11-06,1,%s-1,CR,120.00,GBP,Payment received
                2026-11-06,1,%s-2,DR,-45.10,GBP,Refund
                """.formatted(ref, ref));

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).bodyJson().extractingPath("$.accountCode").isEqualTo("SCHEME_SETTLEMENT");
        assertThat(response).bodyJson().extractingPath("$.itemCount").isEqualTo(1);
        assertThat(response).bodyJson().extractingPath("$.errorCount").isEqualTo(1);
        assertThat(response).bodyJson().extractingPath("$.errors[0].line").isEqualTo(3);
        assertThat(response).bodyJson().extractingPath("$.errors[0].message")
                .isEqualTo("amount must be positive; the sign is in direction");
    }

    @Test
    void theErrorsCanBeFetchedAgainLater() {
        MvcTestResult created = upload("""
                settlement_date,cycle,reference,direction,amount,currency,description
                2026-11-06,1,%s,CR,ten,GBP,Payment received
                """.formatted(unique()));
        String location = created.getResponse().getHeader("Location");

        MvcTestResult response = mvc.get().uri(location).exchange();

        assertThat(response).hasStatus(HttpStatus.OK);
        assertThat(response).bodyJson().extractingPath("$.errors[0].message").isEqualTo("amount 'ten' is not a number");
    }

    @Test
    void refusesTheSameFileTwice() {
        String csv = """
                settlement_date,cycle,reference,direction,amount,currency,description
                2026-11-06,1,%s,CR,120.00,GBP,Payment received
                """.formatted(unique());

        assertThat(upload(csv)).hasStatus(HttpStatus.CREATED);
        MvcTestResult again = upload(csv);

        assertThat(again).hasStatus(HttpStatus.CONFLICT);
        assertThat(again).bodyJson().extractingPath("$.title").isEqualTo("Duplicate file");
    }

    @Test
    void rejectsAFileWithTheWrongColumns() {
        MvcTestResult response = upload("""
                date,ref,amount
                2026-11-06,%s,1.00
                """.formatted(unique()));

        assertThat(response).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response).bodyJson().extractingPath("$.title").isEqualTo("Invalid scheme report");
    }

    private MvcTestResult upload(String csv) {
        return mvc.post().uri("/imports/scheme-report")
                .multipart()
                .file(new MockMultipartFile("file", "report.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                .exchange();
    }

    /** Every test uses its own references, so its files never collide with another test's. */
    private static String unique() {
        return "T-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
```

### 12. Eight uploads at once

Does the friendly `existsBySha256` check stop duplicates on its own? Test it the way production would break it: the same file, uploaded eight times at the same instant.

`src/test/java/com/example/recon/imports/ConcurrentDuplicateImportTest.java`:

```java
package com.example.recon.imports;

import com.example.recon.ReconIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The same file uploaded by eight people at the same instant must be imported once. */
@ReconIntegrationTest
class ConcurrentDuplicateImportTest {

    @Autowired ImportService importService;

    @Test
    void onlyOneOfManySimultaneousUploadsIsImported() throws Exception {
        ByteArrayResource file = new ByteArrayResource("""
                settlement_date,cycle,reference,direction,amount,currency,description
                2026-11-06,1,%s,CR,120.00,GBP,Payment received
                """.formatted(UUID.randomUUID().toString().substring(0, 30)).getBytes(StandardCharsets.UTF_8));

        int threads = 8;
        CountDownLatch startingGun = new CountDownLatch(1);
        AtomicInteger imported = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    try {
                        importService.importSchemeReport("report.csv", file);
                        imported.incrementAndGet();
                    } catch (DuplicateFileException e) {
                        duplicates.incrementAndGet();
                    } catch (RuntimeException e) {
                        synchronized (unexpected) {
                            unexpected.add(e);
                        }
                    }
                    return null;
                }));
            }
            startingGun.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        System.out.printf("imported=%d duplicates=%d unexpected=%s%n", imported.get(), duplicates.get(), unexpected);
        assertThat(unexpected).isEmpty();
        assertThat(imported.get()).isEqualTo(1);
        assertThat(duplicates.get()).isEqualTo(threads - 1);
    }
}
```

- **`CountDownLatch startingGun`**: every task blocks on `await()` until the main thread calls `countDown()`, so all eight start together. Without it, the first upload would usually finish before the last thread started, and the race would never happen.
- **No `@Transactional` on the test.** Each `importSchemeReport` call runs in its own real transaction on its own thread, exactly like eight HTTP requests.
- **`ByteArrayResource`** is the test's `InputStreamSource`. Each call to `getInputStream()` gives a new stream over the same bytes.

Run it. It fails, and the output shows why (trimmed):

```
imported=1 duplicates=0 unexpected=[org.springframework.dao.DataIntegrityViolationException: could not execute statement [Unique index or primary key violation: "PUBLIC.UK_IMPORTED_FILE_SHA256 INDEX PUBLIC.UK_IMPORTED_FILE_SHA256_INDEX_7 ON PUBLIC.IMPORTED_FILE(SHA256 NULLS FIRST) VALUES ( /* 2 */ '5471a1b7…' )"; SQL statement: …
```

Read it carefully. **`duplicates=0`**: the friendly check caught *none* of the seven copies. All eight threads ran `existsBySha256` before any of them had committed, so all eight saw "never imported". The file was still imported exactly once, but only because the **unique constraint** refused the other seven inserts. The data is safe; the callers just got an ugly `DataIntegrityViolationException` (a `500` over HTTP) instead of a clean `409`.

That's the pattern for every "is this a duplicate?" check: **the application check gives the right behaviour in the normal case, and the database constraint gives the guarantee**. Now make the guarantee produce the right behaviour too. Replace `save` in `ImportService`:

```java
    /** The guarantee: the unique constraint on sha256 lets only one of two simultaneous uploads in. */
    private ImportedFile save(ImportedFile file) {
        try {
            return files.saveAndFlush(file);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateFileException(file.getSha256());
        }
    }
```

(Import `org.springframework.dao.DataIntegrityViolationException`.) **`saveAndFlush`** makes sure the `INSERT` is sent *now*, so the violation surfaces inside this `try`. With `IDENTITY` ids Hibernate happens to insert straight away on a plain `save` too, but with sequence-generated ids it would wait until the transaction flushes at commit, after this method had returned, and the `catch` would never see the violation. Don't rely on the id strategy; flush. Run the test again:

```
imported=1 duplicates=7 unexpected=[]
```

Green. Seven clean refusals.

> **Sidebar: catching only *your* constraint.** `DataIntegrityViolationException` is thrown for *any* constraint: a `NOT NULL`, a foreign key, a different unique key. Here the insert into `imported_file` can only realistically break the checksum rule, so the catch is safe. In larger tables, check the constraint name in the exception's cause before translating it, or a genuine bug will be reported as "duplicate file".

### 13. Try it

Add to `requests.http`:

```http
### Import the scheme's settlement report (CSV)
POST http://localhost:8080/imports/scheme-report
Content-Type: multipart/form-data; boundary=WebAppBoundary

--WebAppBoundary
Content-Disposition: form-data; name="file"; filename="scheme-report-2026-11-06.csv"
Content-Type: text/csv

< ./samples/scheme-report-2026-11-06.csv
--WebAppBoundary--

### Look at it again, errors included
GET http://localhost:8080/imports/2
```

Delete your `data/` folder first so the ids below line up, restart, and import the statement and then the report (or with curl: `curl -F file=@samples/scheme-report-2026-11-06.csv localhost:8080/imports/scheme-report`):

```json
{
  "id": 2,
  "fileType": "SCHEME_CSV",
  "fileName": "scheme-report-2026-11-06.csv",
  "accountCode": "SCHEME_SETTLEMENT",
  "externalId": null,
  "sha256": "ca51e903…",
  "itemCount": 3,
  "errorCount": 3,
  "errors": [
    { "line": 5, "message": "12.345 has too many decimal places for GBP" },
    { "line": 6, "message": "settlement_date '2026-11-31' is not a date like 2026-11-06" },
    { "line": 7, "message": "direction 'XX' must be CR or DR" }
  ],
  "importedAt": "2026-11-07T14:14:50.084225041Z"
}
```

(Your checksum depends on the exact bytes in your file, line endings included.) Now upload the statement again:

```json
{"detail":"This file has already been imported (SHA-256 6ed6ceff…)","instance":"/imports/camt053","status":409,"title":"Duplicate file"}
```

---

## Your turn

### Exercise 1: Excel's invisible character

Operations staff often open a report in Excel to look at it, then save it. Excel's **CSV UTF-8** format starts the file with the bytes `EF BB BF`, the UTF-8 encoding of U+FEFF, the **byte order mark**. Add this test to `SchemeReportParserTest` and run it:

```java
    @Test
    void copesWithTheByteOrderMarkThatExcelAdds() {
        ParsedReport report = parser.parse(csv("﻿" + """
                settlement_date,cycle,reference,direction,amount,currency,description
                2026-11-06,1,SCH-1,CR,1.00,GBP,ok
                """));

        assertThat(report.errors()).isEmpty();
        assertThat(report.lines()).hasSize(1);
    }
```

It fails with:

```
SchemeReportFormatException: Missing columns [settlement_date]; found [﻿settlement_date, cycle, reference, direction, amount, currency, description]
```

The first header *looks* right, but it's really `"﻿settlement_date"`. Make the parser skip a leading BOM.

<details>
<summary>Hint</summary>

Wrap the `Reader` in a `java.io.PushbackReader` with a pushback buffer of 1. Read one character; if it isn't `'﻿'`, `unread` it.

</details>

<details>
<summary>Solution</summary>

In `SchemeReportParser`, change the reader in `parse`:

```java
        try (Reader reader = withoutByteOrderMark(new InputStreamReader(in, StandardCharsets.UTF_8));
             CSVParser csv = FORMAT.parse(reader)) {
```

and add (importing `java.io.BufferedReader` and `java.io.PushbackReader`):

```java
    /** Excel's "CSV UTF-8" starts the file with U+FEFF, which would otherwise end up in the first header. */
    private static Reader withoutByteOrderMark(Reader reader) throws IOException {
        PushbackReader pushback = new PushbackReader(new BufferedReader(reader), 1);
        int first = pushback.read();
        if (first != -1 && first != '﻿') {
            pushback.unread(first);
        }
        return pushback;
    }
```

The `BufferedReader` keeps reads efficient; `PushbackReader` itself only buffers the one character you push back. Commons IO has a `BOMInputStream` that does the same job for several encodings, but adding a library for four lines isn't worth it.

</details>

### Exercise 2: statements too

The checksum check protects statements as well as reports, because `importCamt053` calls `rejectIfAlreadyImported`. Add a test to `StatementImportApiTest` that uploads the same statement twice and expects `201` then `409`. Watch out: `importsEveryEntryAsAnOpenExternalItem` already uploads the sample file unchanged, and tests run in any order.

<details>
<summary>Solution</summary>

Change one byte of the sample so this test's file is different from every other test's:

```java
    @Test
    void refusesTheSameStatementTwice() throws Exception {
        byte[] xml = new String(sample()).replace("MSG-20261107-0001", "MSG-DUPLICATE-TEST").getBytes();

        assertThat(upload("statement.xml", xml)).hasStatus(HttpStatus.CREATED);
        assertThat(upload("statement-copy.xml", xml)).hasStatus(HttpStatus.CONFLICT);
    }
```

The second upload has a different file name, and is still refused: the checksum looks at content, not names.

</details>

---

## Checkpoint

```bash
./mvnw clean test
```

```
[INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
```

(14 from guide 01, plus 1 more in `StatementImportApiTest`, 5 in `SchemeReportParserTest`, 4 in `SchemeReportImportApiTest` and 1 in `ConcurrentDuplicateImportTest`.)

New files since guide 01:

```
samples/scheme-report-2026-11-06.csv
src/main/java/com/example/recon/
├── imports/  Checksums, DuplicateFileException, ImportError, ImportErrorRepository
└── scheme/   LineError, ParsedReport, SchemeLine, SchemeReportFormatException, SchemeReportParser
src/main/resources/db/migration/V2__scheme_reports_and_duplicates.sql
src/test/java/com/example/recon/
├── imports/  ConcurrentDuplicateImportTest, SchemeReportImportApiTest
└── scheme/   SchemeReportParserTest
src/test/resources/samples/scheme-report-2026-11-06.csv
```

Modified: `pom.xml`, `FileType`, `ImportedFile`, `ImportedFileRepository`, `ImportService`, `ImportController`, `ApiExceptionHandler`, `StatementImportApiTest`, `requests.http`.

---

## Common errors

**`Missing columns [settlement_date]; found [﻿settlement_date, …]`**
The file starts with a byte order mark (there's an invisible character before `settlement_date`). Do Exercise 1.

**`Not valid CSV: org.apache.commons.csv.CSVException: (startline 2) EOF reached before encapsulated token finished`**
A quoted field was opened and never closed, so the parser read to the end of the file looking for the closing quote. The whole file is rejected because nothing after that point can be trusted. Ask the sender for a corrected file.

**`Missing columns [settlement_date, cycle, …]; found [settlement_date;cycle;reference;…]`**
The file uses a different delimiter, usually `;` (common where decimals are written with a comma) or a tab, so the whole header was read as one column. Agree the format with the sender; if it's legitimately different, use `CSVFormat.DEFAULT.builder().setDelimiter(';')`.

**`'build()' is deprecated`**
Recent Commons CSV versions renamed `CSVFormat.Builder.build()` to `get()`. Both work.

**`DataIntegrityViolationException: … Unique index or primary key violation: "PUBLIC.UK_IMPORTED_FILE_SHA256 …"`**
Two imports of the same file raced past the friendly check. If you see it as a `500`, the `try`/`catch` in `save` is missing, or the insert isn't flushed inside it.

**A test gets `409 Conflict` it didn't expect**
Another test already uploaded identical bytes to the shared test database. Make each test's file unique, as `SchemeReportImportApiTest.unique()` does.

---

## Recap

- A **scheme settlement report** is the scheme's record of what the bank sent and received in each settlement cycle. The bank reconciles it against its **scheme settlement account**.
- **Use a library for the CSV format**, and write the business validation yourself. Read columns by header name, and treat dates and amounts strictly: ISO dates only, `BigDecimal` from strings, no silent rounding.
- **Validate every line, report every problem** with a line number. Whether to accept the good lines or reject the file is a business policy; make it explicit, and keep the rejected lines.
- **Detect duplicate files by content**: a SHA-256 checksum, stored under a unique constraint.
- **Check-then-insert is a race.** The application check gives friendly behaviour; the **unique constraint is the guarantee**. Translate its violation into the same friendly error, using `saveAndFlush` so it surfaces where you can catch it.
- Tests that share a database must **create their own, unique data**.

### Interview angle

<details>
<summary>"You receive a CSV file with 50,000 lines and 12 of them are malformed. What do you do?"</summary>

Validate every line and collect all 12 errors with line numbers and reasons, rather than stopping at the first one. Then apply the agreed policy: either reject the whole file and send the error report back, or (common for reports where each line stands alone) import the 49,988 good lines and quarantine the 12 bad ones for someone to chase. Either way, the outcome must be recorded and visible (counts and errors on the import record), and the file should be protected against re-import so a corrected resend doesn't duplicate the good lines. Mention using a proper CSV library because of quoting, encodings and the BOM.

</details>

<details>
<summary>"How would you stop the same file being processed twice?"</summary>

Compute a checksum (SHA-256) of the file's bytes and store it with a unique constraint. Check for it before processing for a friendly error, and rely on the constraint to stop concurrent duplicates that both pass the check; translate the constraint violation into the same "already imported" response. File names aren't reliable, and a sender's file id is useful but trusts the sender. For extra safety, many systems also deduplicate individual records by a natural key (such as the scheme's transaction reference), which catches the same transaction arriving in two *different* files.

</details>

<details>
<summary>"Why isn't checking 'does it exist?' before inserting enough?"</summary>

Because it's two steps, and another transaction can do its own check in between. Under READ COMMITTED, neither sees the other's uncommitted row, so both insert. Only something atomic closes the gap: a unique constraint (the database checks and inserts as one operation), a lock, or an `INSERT … ON CONFLICT`-style statement. Keep the check for a clean error in the common case, and let the constraint handle the race.

</details>

### Further reading

- [Apache Commons CSV user guide](https://commons.apache.org/proper/commons-csv/user-guide.html)
- [RFC 4180: Common Format and MIME Type for CSV Files](https://www.rfc-editor.org/rfc/rfc4180)
- [`MessageDigest` Javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/security/MessageDigest.html) and the [standard algorithm names](https://docs.oracle.com/en/java/javase/25/docs/specs/security/standard-names.html#messagedigest-algorithms) every Java runtime supports
- [Pay.UK: how Faster Payments works](https://www.wearepay.uk/what-we-do/payment-systems/faster-payment-system/how-faster-payments-work/)

**Next:** [03: The matching engine](03-the-matching-engine.md). The bank's own side of the story, and the rules that decide which items explain each other.
