# 04: Encrypting Personal Data

> Written for **Spring Boot 4.1.2** and **Java 25**. See the [series README](../README.md) for version notes.

## What you'll learn

- The layers of **encryption at rest** in a bank, and why sensitive fields are often encrypted by the **application** itself
- **AES-GCM**: authenticated encryption, why every value needs a fresh random **IV**, and what the **tag** protects against
- Where keys come from: a **development-only key** with a loud warning, and in production a **KMS or HSM**
- **Key rotation**, made possible by storing a **key id** with every value
- A JPA **`AttributeConverter`** that encrypts on write and decrypts on read, with Spring constructor injection
- How to **prove** in a test that the database column holds ciphertext
- How to search data you can't read: a **blind index** (HMAC) for spotting duplicate applicants
- What happens when **the same person applies ten times at once**, fixed with a **unique constraint** on the blind index

By the end, names, dates of birth, email addresses and postal addresses are unreadable in the database, in backups and in the H2 console, while the Java code works exactly as before, and a duplicate application is refused even under concurrent load.

---

## Concepts

### How it works in a real bank

Customer data is a bank's most sensitive asset after the money itself. [UK GDPR Article 32](https://www.legislation.gov.uk/eur/2016/679/article/32) requires "appropriate technical and organisational measures" to keep [personal data](../glossary.md#pii) secure, and names encryption as one example. The ICO's [encryption guidance](https://ico.org.uk/for-organisations/uk-gdpr-guidance-and-resources/security/encryption/) is a good plain-English overview. There's also a practical reason: if a breach exposes data that's properly encrypted, with the key kept safe, the data is unintelligible to whoever took it, and the consequences for the people affected (and for the bank) are far less severe.

Banks encrypt in **layers**:

| Layer | Protects against | Doesn't protect against |
|---|---|---|
| Disk / volume encryption | A stolen disk or a decommissioned server | Anyone who can query the running database |
| Database encryption (often called TDE) | Stolen database files and backups | Anyone who can query the running database |
| **Application-level (field) encryption** | Database administrators, SQL injection that dumps tables, copied backups, data leaking into analytics copies | A compromised application server, which holds the key |

You'll build the last layer. The application encrypts each sensitive value **before** it reaches the database, so the database only ever sees ciphertext.

**Keys** are the hard part. In a bank, encryption keys live in a **hardware security module (HSM)** or a cloud **key management service (KMS)**, and never in source code or plain configuration files. A common pattern is **envelope encryption**: the application encrypts data with a *data key*, and the data key is itself encrypted by a *master key* that never leaves the HSM/KMS. Keys are **rotated** periodically and whenever compromise is suspected, so the system must cope with data encrypted under older keys. Storing a key id with every value makes that possible.

### AES-GCM in one page

**AES** is the standard symmetric cipher. **GCM** (Galois/Counter Mode) is a mode of operation that gives you two things at once:

- **Confidentiality:** the ciphertext reveals nothing about the plaintext except its length.
- **Integrity:** a 128-bit **authentication tag** is computed over the ciphertext. Change a single bit of the stored value, or decrypt with the wrong key, and decryption **fails** instead of returning garbage. This is called **authenticated encryption**.

GCM needs an **initialisation vector (IV)**, 12 bytes, that must **never repeat for the same key**. Reusing an IV with GCM is catastrophic: it can reveal the relationship between two plaintexts and lets an attacker forge tags. So you generate a fresh random IV for every value, and store it alongside the ciphertext. (It isn't secret.)

A random IV has a useful side effect: encrypting "Alice" twice gives two different ciphertexts, so nobody can tell from the database that two customers share a name. It also has a cost: **you can no longer search**. `WHERE email = ?` can't work, because the same email is stored differently every time.

The stored format you'll use:

```
dev1:wKwaWsGf+3qUtSk2x+TM21Xvf1fUzhD5mIeFJKDG+0C6
└┬─┘ └──────────────── Base64 of: 12-byte IV │ ciphertext │ 16-byte tag
 key id
```

### Searching encrypted data: a blind index

The bank needs to spot the same person applying twice, by email. The answer is a **blind index**: a second column holding a **keyed hash** of the normalised value:

```
email_hash = HMAC-SHA256(blindIndexKey, lower-case(trim(email)))
```

- It's **deterministic**: the same email always gives the same hash, so you can look it up, and put a **unique constraint** on it.
- It's **keyed**: without the secret key, an attacker with the database can't take a list of email addresses and hash them to find matches. A plain SHA-256 would allow exactly that.
- It uses a **separate key** from encryption, so one leaked key doesn't give away both.

The trade-off: a blind index reveals **which rows share a value**. That's fine for email, where duplicates are what you're looking for. Don't blind-index low-variety fields like date of birth or postcode: the patterns of equal values leak information, and there are few enough possible values to guess.

### The same person, ten times at once

People double-click "Submit". Mobile apps retry when the network is slow. So "check whether this email has already applied, then insert" runs concurrently, and two requests can both check, both find nothing, and both insert. You've seen this shape before (check-then-act). The cure this time isn't a row lock, because there's no row yet to lock. It's a **unique constraint**: the database itself refuses the second insert.

---

## Steps

### 1. Keys in configuration

Generate two keys, one for encryption and one for the blind index:

```bash
openssl rand -base64 32
```

Each is 32 random bytes (256 bits) in Base64. Add them to `application.properties`:

```properties
# PII encryption. DEVELOPMENT KEYS ONLY: they are in git, so they protect nothing.
# Real keys come from a secrets manager or KMS, injected as environment variables.
# Generate a key with: openssl rand -base64 32
onboarding.crypto.active-key-id=dev1
onboarding.crypto.keys.dev1=HIq01wMbsNCbPeHFo1UVPbAANAImfOBgnKeq+jImDcY=
onboarding.crypto.blind-index-key=AwHQt7qzGRk8v6bY7IDU3re+JmPM35cueVJBXtGAkbk=
```

(Use your own generated values, or these, for development. Never use either in production.)

`keys.dev1` is a map entry: `keys` can hold several keys by id, which is what makes rotation possible. Spring Boot lets environment variables override properties, so a production deployment would supply, for example, `ONBOARDING_CRYPTO_KEYS_K2026` from a secrets manager instead.

Create the package `com.example.onboarding.crypto` and add `crypto/CryptoProperties.java`:

```java
package com.example.onboarding.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * @param activeKeyId   the key new values are encrypted with
 * @param keys          every key that may still be needed to decrypt, by id, Base64-encoded
 * @param blindIndexKey the separate HMAC key for searchable hashes, Base64-encoded
 */
@ConfigurationProperties("onboarding.crypto")
public record CryptoProperties(String activeKeyId, Map<String, String> keys, String blindIndexKey) {
}
```

Spring Boot binds `active-key-id` to `activeKeyId`, and every `keys.<id>` entry into the map. For Spring to find `@ConfigurationProperties` records, add `@ConfigurationPropertiesScan` to the application class:

```java
@SpringBootApplication
@ConfigurationPropertiesScan
public class CustomerOnboardingApplication {
```

### 2. The cipher

`crypto/PiiCipher.java`:

```java
package com.example.onboarding.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * AES-256-GCM encryption for personal data stored in the database.
 * Stored format: "<keyId>:<Base64 of IV + ciphertext + tag>", e.g. "dev1:3q2+7w...".
 */
@Component
public class PiiCipher {

    private static final Logger log = LoggerFactory.getLogger(PiiCipher.class);
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, SecretKey> keys = new HashMap<>();
    private final String activeKeyId;

    public PiiCipher(CryptoProperties properties) {
        properties.keys().forEach((id, base64) -> keys.put(id, aesKey(id, base64)));
        this.activeKeyId = properties.activeKeyId();
        if (!keys.containsKey(activeKeyId)) {
            throw new IllegalStateException("No key configured for active key id " + activeKeyId);
        }
        if (activeKeyId.startsWith("dev")) {
            log.warn("Encrypting personal data with development key '{}'. Never use it outside your own machine.",
                    activeKeyId);
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);                       // a new random IV for every value
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] stored = ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
            return activeKeyId + ":" + Base64.getEncoder().encodeToString(stored);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        int colon = stored.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("Not an encrypted value (no key id)");
        }
        SecretKey key = keys.get(stored.substring(0, colon));
        if (key == null) {
            throw new IllegalStateException("Unknown key id " + stored.substring(0, colon));
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(stored.substring(colon + 1));
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            byte[] plaintext = cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // AEADBadTagException lands here: wrong key, or the stored value was altered
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    private static SecretKey aesKey(String id, String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        if (bytes.length != 32) {
            throw new IllegalStateException("Key %s must be 32 bytes (AES-256), was %d".formatted(id, bytes.length));
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
```

Walking through it:

- **`AES/GCM/NoPadding`:** GCM works like a stream cipher, so it needs no padding. The JDK's built-in provider supports it: no extra libraries.
- **`SecureRandom`** for the IV, never `java.util.Random`, which is predictable. One `SecureRandom` per instance is thread-safe.
- **`doFinal` returns ciphertext with the 16-byte tag appended.** The stored bytes are IV + ciphertext + tag, Base64-encoded, behind the key id.
- **Decryption reads the key id first**, picks that key, and tells GCM where the IV ends (`GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES)`). If the tag doesn't verify, `doFinal` throws `AEADBadTagException`.
- **Start-up checks:** a key of the wrong length or a missing active key stops the application, and a development key logs a warning on every start.
- **A `Cipher` is not thread-safe**, so each call gets its own from `Cipher.getInstance`. It's cheap.

> **Sidebar: with a KMS.** In production `PiiCipher` would rarely see raw master keys. With envelope encryption, it would ask the KMS to generate or decrypt a **data key**, cache that briefly in memory, and use it exactly as above. The stored key id would identify which wrapped data key to fetch. The shape of the code stays the same, which is why the key id matters from day one.

### 3. Test the cipher

`src/test/java/com/example/onboarding/crypto/PiiCipherTest.java`:

```java
package com.example.onboarding.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PiiCipherTest {

    static final String KEY_1 = "HIq01wMbsNCbPeHFo1UVPbAANAImfOBgnKeq+jImDcY=";

    PiiCipher cipher = new PiiCipher(new CryptoProperties("test1", Map.of("test1", KEY_1), KEY_1));

    @Test
    void decryptsWhatItEncrypted() {
        assertThat(cipher.decrypt(cipher.encrypt("Séan O'Brien"))).isEqualTo("Séan O'Brien");
    }

    @Test
    void storesTheKeyIdAndNoTraceOfThePlaintext() {
        String stored = cipher.encrypt("alice@example.com");

        assertThat(stored).startsWith("test1:").doesNotContain("alice");
    }

    @Test
    void encryptingTheSameValueTwiceGivesDifferentCiphertext() {
        assertThat(cipher.encrypt("Alice")).isNotEqualTo(cipher.encrypt("Alice"));
    }

    @Test
    void detectsTampering() {
        String stored = cipher.encrypt("Alice");
        byte[] bytes = Base64.getDecoder().decode(stored.substring("test1:".length()));
        bytes[bytes.length - 1] ^= 1; // flip one bit of the authentication tag
        String tampered = "test1:" + Base64.getEncoder().encodeToString(bytes);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(AEADBadTagException.class);
    }

    @Test
    void refusesAKeyOfTheWrongLength() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new PiiCipher(new CryptoProperties("k", Map.of("k", shortKey), KEY_1)))
                .hasMessage("Key k must be 32 bytes (AES-256), was 16");
    }
}
```

`detectsTampering` is the one that shows what "authenticated" buys you. One flipped bit, and decryption refuses instead of handing back a slightly wrong name. The test key id is `test1`, so constructing it doesn't log the development-key warning.

### 4. The blind index

`crypto/BlindIndex.java`:

```java
package com.example.onboarding.crypto;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * A keyed hash (HMAC-SHA256) of a normalised value. Equal inputs give equal
 * hashes, so the database can find and enforce uniqueness on them, but without
 * the key nobody can work back to the value or test guesses against it.
 */
@Component
public class BlindIndex {

    private final SecretKeySpec key;

    public BlindIndex(CryptoProperties properties) {
        this.key = new SecretKeySpec(Base64.getDecoder().decode(properties.blindIndexKey()), "HmacSHA256");
    }

    public String ofEmail(String email) {
        return hmac(email.strip().toLowerCase(Locale.ROOT));
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }
}
```

- **Normalise before hashing.** `Alice@Example.COM` and `alice@example.com` are the same mailbox for practical purposes, so they must produce the same hash. (Strictly, the part before the `@` can be case-sensitive, but treating it as case-insensitive is the common, safe choice for duplicate detection.)
- **`HexFormat`** (Java 17+) turns the 32-byte result into 64 hex characters.

`src/test/java/com/example/onboarding/crypto/BlindIndexTest.java`:

```java
package com.example.onboarding.crypto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BlindIndexTest {

    static final String KEY_A = "AwHQt7qzGRk8v6bY7IDU3re+JmPM35cueVJBXtGAkbk=";
    static final String KEY_B = "HIq01wMbsNCbPeHFo1UVPbAANAImfOBgnKeq+jImDcY=";

    static BlindIndex withKey(String key) {
        return new BlindIndex(new CryptoProperties("x", Map.of(), key));
    }

    @Test
    void theSameEmailWrittenDifferentlyHasTheSameIndex() {
        BlindIndex index = withKey(KEY_A);

        assertThat(index.ofEmail(" Alice@Example.COM ")).isEqualTo(index.ofEmail("alice@example.com"));
        assertThat(index.ofEmail("alice@example.com")).hasSize(64).doesNotContain("alice");
    }

    @Test
    void withoutTheKeyTheIndexCannotBeReproduced() {
        assertThat(withKey(KEY_A).ofEmail("alice@example.com"))
                .isNotEqualTo(withKey(KEY_B).ofEmail("alice@example.com"));
    }
}
```

### 5. Encrypt the columns

The converter is the bridge between JPA and the cipher. `crypto/EncryptedStringConverter.java`:

```java
package com.example.onboarding.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Encrypts on the way into the database and decrypts on the way out.
 * Spring Boot lets Hibernate create converters as Spring beans, so constructor injection works.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final PiiCipher cipher;

    public EncryptedStringConverter(PiiCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : cipher.decrypt(dbData);
    }
}
```

An `AttributeConverter` maps one entity attribute to one column. Hibernate calls `convertToDatabaseColumn` when writing and `convertToEntityAttribute` when reading. Normally Hibernate creates converters itself, with a no-argument constructor. Spring Boot configures Hibernate to ask Spring for them instead, which is why the `PiiCipher` can be injected through the constructor.

Ciphertext is longer than plaintext: a key id, a colon, and Base64 (four characters for every three bytes) of 12 bytes of IV, the text as UTF-8 (up to four bytes a character) and 16 bytes of tag. A 100-character name in a non-Latin script could need nearly 600 characters. Widen the columns, and add the blind index. `src/main/resources/db/migration/V4__encrypt_personal_data.sql`:

```sql
-- Ciphertext is longer than the plaintext: key id + Base64 of (12-byte IV + data + 16-byte tag)
ALTER TABLE application ALTER COLUMN given_name    SET DATA TYPE VARCHAR(700);
ALTER TABLE application ALTER COLUMN family_name   SET DATA TYPE VARCHAR(700);
ALTER TABLE application ALTER COLUMN email         SET DATA TYPE VARCHAR(700);
ALTER TABLE application ALTER COLUMN address_line1 SET DATA TYPE VARCHAR(700);
ALTER TABLE application ALTER COLUMN postcode      SET DATA TYPE VARCHAR(700);

ALTER TABLE application ADD COLUMN email_hash VARCHAR(64);
```

`ALTER COLUMN … SET DATA TYPE` is the SQL-standard spelling, and both H2 and PostgreSQL accept it. `email_hash` is nullable because a column added to a table that already has rows can't be `NOT NULL` without a default.

Now the entity. In `Application.java` (imports: `jakarta.persistence.Convert`, `com.example.onboarding.crypto.EncryptedStringConverter`), change the personal fields and add `emailHash`:

```java
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "given_name", nullable = false, length = 700)
    private String givenName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "family_name", nullable = false, length = 700)
    private String familyName;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, length = 700)
    private String email;

    /** HMAC of the normalised email: searchable, but not reversible without the key. */
    @Column(name = "email_hash", length = 64)
    private String emailHash;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "address_line1", nullable = false, length = 700)
    private String addressLine1;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, length = 700)
    private String postcode;
```

The constructor and factory take the hash:

```java
    private Application(Applicant applicant, String emailHash, Instant now) {
        ...
        this.email = applicant.email();
        this.emailHash = emailHash;
        ...
    }

    public static Application receive(Applicant applicant, String emailHash, Instant now) {
        return new Application(applicant, emailHash, now);
    }
```

Date of birth stays readable for now. Exercise 2 encrypts it.

In `ApplicationService`, inject `BlindIndex` (add it to the fields and constructor, between `AuditTrail` and `Clock`), and pass the hash when receiving:

```java
        String emailHash = blindIndex.ofEmail(applicant.email());
        Application application = applications.save(Application.receive(applicant, emailHash, clock.instant()));
```

The unit tests call `Application.receive(alice(), NOW)`. In `ApplicationTest` and `ApplicationReviewTest`, change every call to `Application.receive(alice(), "hash", NOW)`. Unit tests don't touch the database, so any string will do.

Nothing else changes. `getApplicant()`, the controllers, the checks and the tests all see plain Java strings. That's the point of a converter: encryption is a storage detail.

### 6. Your existing database

If you start the application against the `data/` database from guide 03, Flyway applies `V4` happily, and then reading an old application fails:

```
JpaSystemException: Error attempting to apply AttributeConverter
...
Caused by: java.lang.IllegalArgumentException: Not an encrypted value (no key id)
```

The old rows hold plain text, and the converter quite rightly refuses to treat `Alice` as ciphertext. In development, stop the application and **delete the `data/` folder**. Flyway rebuilds the schema on the next start.

> **Sidebar: encrypting existing data for real.** A bank can't delete its database. The usual approach: deploy code that can read *both* formats (plain text without a key id, ciphertext with one) and always writes ciphertext; run a batch job that reads and rewrites every row in small transactions; once every row is encrypted, remove the plain-text fallback. Key rotation is done the same way: add the new key, make it active, and re-encrypt old values in the background, while the old key stays available for reading until the job finishes.

### 7. Prove it's ciphertext

The converter would be just as invisible if it did nothing at all, so the test has to look **underneath** JPA. `src/test/java/com/example/onboarding/crypto/EncryptionAtRestTest.java`:

```java
package com.example.onboarding.crypto;

import com.example.onboarding.OnboardingIntegrationTest;
import com.example.onboarding.application.Applicant;
import com.example.onboarding.application.ApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static com.example.onboarding.TestApplicants.alice;
import static org.assertj.core.api.Assertions.assertThat;

@OnboardingIntegrationTest
class EncryptionAtRestTest {

    @Autowired ApplicationService applicationService;
    @Autowired JdbcTemplate jdbc;
    @Autowired BlindIndex blindIndex;

    @Test
    void personalDataIsCiphertextInTheDatabaseAndPlaintextInJava() {
        Applicant alice = alice();
        UUID id = applicationService.receive(alice).getId();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT given_name, family_name, email, address_line1, postcode, email_hash"
                        + " FROM application WHERE id = ?", id);
        System.out.println("RAW ROW " + row);

        assertThat(row.get("GIVEN_NAME").toString()).startsWith("dev1:").doesNotContain("Alice");
        assertThat(row.get("EMAIL").toString()).startsWith("dev1:").doesNotContain("alice");
        assertThat(row.get("POSTCODE").toString()).startsWith("dev1:").doesNotContain("G1");
        assertThat(row.get("EMAIL_HASH")).isEqualTo(blindIndex.ofEmail(alice.email()));

        assertThat(applicationService.get(id).getApplicant()).isEqualTo(alice);
    }
}
```

**`JdbcTemplate`** runs plain SQL with no JPA, no converters, nothing between you and the column. `queryForMap` returns one row as a map; its keys are case-insensitive, and H2 reports column names in upper case. The output:

```
RAW ROW {GIVEN_NAME=dev1:wKwaWsGf+3qUtSk2x+TM21Xvf1fUzhD5mIeFJKDG+0C6, FAMILY_NAME=dev1:OkUV815ng8gfQoGZBSg2NQk3Bpz1l+vpHhq7yCnY66fP, EMAIL=dev1:cn+QkmhvLzOJ7/9jlTKO36EmIBT7ya0a818pcno/guNx7LGXdPm6TpCHjEqD/JOx1JUP6ZR+gz2d6rWan3andv1/uNnjraXixic7XWV1VQCu7w==, ADDRESS_LINE1=dev1:nr9SBh5quaEhqdLxb7w2a7cXP9G/JW6F+d1tu6FRKC7djsnS89krvHs=, POSTCODE=dev1:nTI1k93YKBuEULWz9GIaxVc2tEbIt/sSUgndsWFzzeyBsw==, EMAIL_HASH=8fe30fb1f1842ea4eb358496185d9ae7c99a7218fb68ac1d966e0d0d5569f5d1}
```

The last line of the test proves the round trip: through JPA, the applicant comes back exactly as given.

Run the app and look for yourself: open `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:file:./data/onboarding`, user `sa`), and `SELECT * FROM application`. The start-up log also carries the warning:

```
Encrypting personal data with development key 'dev1'. Never use it outside your own machine.
```

### 8. Refuse duplicate applications

Add a query to `ApplicationRepository`:

```java
    boolean existsByEmailHash(String emailHash);
```

`application/DuplicateApplicationException.java`:

```java
package com.example.onboarding.application;

public class DuplicateApplicationException extends RuntimeException {

    public DuplicateApplicationException() {
        super("An application already exists for this email address");
    }
}
```

In `ApplicationService.receive`, check before saving:

```java
        String emailHash = blindIndex.ofEmail(applicant.email());
        if (applications.existsByEmailHash(emailHash)) {
            throw new DuplicateApplicationException();
        }
        Application application = applications.save(Application.receive(applicant, emailHash, clock.instant()));
```

And in `ApiExceptionHandler`:

```java
    @ExceptionHandler(DuplicateApplicationException.class)
    ProblemDetail handle(DuplicateApplicationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Duplicate application");
        return problem;
    }
```

Run the whole suite. **Lots of tests now fail**:

```
[ERROR]   ReviewApiTest.aReviewerApprovesAReferral:52->referred:26 » DuplicateApplication An application already exists for this email address
[ERROR]   OnboardingWorkflowTest.noElectronicIdentityMatchIsReferred:61->receive:43 » DuplicateApplication An application already exists for this email address
...
expected: 201
 but was: 409
```

The feature works. The tests are the problem: they all share one in-memory database, and `TestApplicants` gives every Uma the email `uma@example.com`. Every test must now use its own email address. Update `TestApplicants`:

```java
package com.example.onboarding;

import com.example.onboarding.application.Applicant;
import com.example.onboarding.application.ExpectedMonthlyDeposits;

import java.time.LocalDate;
import java.util.UUID;

/** Applicants for tests. The fake providers react to some of these names on purpose. */
public final class TestApplicants {

    private TestApplicants() {
    }

    /** Every application needs its own email address now, so tests never share one. */
    public static String uniqueEmail(String name) {
        return name.toLowerCase() + "+" + UUID.randomUUID() + "@example.com";
    }

    public static Applicant applicant(String givenName, String familyName) {
        return new Applicant(givenName, familyName, LocalDate.of(1990, 5, 17),
                uniqueEmail(givenName), "1 High Street", "G1 1XQ",
                ExpectedMonthlyDeposits.FROM_1K_TO_5K);
    }

    public static Applicant alice() {
        return applicant("Alice", "Smith");
    }
}
```

`ApplicationApiTest` posts the fixed JSON in `ALICE`. Make its `apply` helper swap in a fresh address (import `com.example.onboarding.TestApplicants`):

```java
    MvcTestResult apply(String json) {
        return mvc.post().uri("/applications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.replace("alice@example.com", TestApplicants.uniqueEmail("alice")))
                .exchange();
    }
```

Everything is green again. Now add a test for the feature itself, in `ApplicationApiTest`:

```java
    @Test
    void refusesASecondApplicationWithTheSameEmailInAnyCase() {
        String email = TestApplicants.uniqueEmail("dup");
        assertThat(apply(ALICE.replace("alice@example.com", email))).hasStatus(HttpStatus.CREATED);

        MvcTestResult again = apply(ALICE.replace("alice@example.com", email.toUpperCase()));

        assertThat(again).hasStatus(HttpStatus.CONFLICT);
        assertThat(again).bodyJson().extractingPath("$.title").isEqualTo("Duplicate application");
    }
```

(The JSON no longer contains `alice@example.com` after the first `replace`, so `apply`'s own replacement leaves it alone.)

> **Sidebar: "an application already exists for this email address" is itself a leak.** It tells anyone who types an email address into your form whether that person has applied to this bank, which is personal information, and useful to a fraudster. Real onboarding journeys usually respond in the same way whether or not the address is known, and send an email *to that address* saying "you already have an application with us". The owner of the mailbox learns the truth; nobody else does. This tutorial returns the honest `409` because it's easier to see and test.

> **Simplification:** one application per email address, for ever. A real bank lets a declined or withdrawn applicant apply again later, so it would only count *open* applications, or keep a separate table of current applicants. Guide 05 relaxes the rule a little: erasing a declined application's email frees the address.

### 9. Ten applications at once

Create `src/test/java/com/example/onboarding/application/ConcurrentApplicationTest.java`:

```java
package com.example.onboarding.application;

import com.example.onboarding.OnboardingIntegrationTest;
import com.example.onboarding.crypto.BlindIndex;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.onboarding.TestApplicants.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

/** A double-clicked "Submit", or a retrying app: the same person applies ten times at once. */
@OnboardingIntegrationTest
class ConcurrentApplicationTest {

    @Autowired ApplicationService applicationService;
    @Autowired BlindIndex blindIndex;
    @Autowired JdbcTemplate jdbc;

    @Test
    void simultaneousApplicationsWithOneEmailCreateOneApplication() throws Exception {
        String email = uniqueEmail("alice");
        Applicant alice = new Applicant("Alice", "Smith", LocalDate.of(1990, 5, 17), email,
                "1 High Street", "G1 1XQ", ExpectedMonthlyDeposits.FROM_1K_TO_5K);

        int threads = 10;
        CountDownLatch startingGun = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    try {
                        applicationService.receive(alice);
                        created.incrementAndGet();
                    } catch (DuplicateApplicationException e) {
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
                future.get(30, TimeUnit.SECONDS);
            }
        }

        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM application WHERE email_hash = ?",
                Integer.class, blindIndex.ofEmail(email));
        System.out.printf("created=%d duplicates=%d rows=%d unexpected=%s%n", created.get(), duplicates.get(),
                rows, unexpected.stream().map(t -> t.getClass().getSimpleName()).toList());

        assertThat(unexpected).isEmpty();
        assertThat(created.get()).isEqualTo(1);
        assertThat(duplicates.get()).isEqualTo(threads - 1);
        assertThat(rows).isEqualTo(1);
    }
}
```

The same starting-gun pattern as guide 03: ten threads, released together, no test-level transaction, and assertions on what's actually in the database. Notice the test finds the rows by **blind index**. With the email encrypted, `WHERE email = ?` would find nothing.

Run it:

```
created=10 duplicates=0 rows=10 unexpected=[]
```

```
expected: 1
 but was: 10
```

Three runs in a row gave exactly that: ten applications for one person, every time. All ten threads ran `existsByEmailHash` before any of them had committed its insert, so all ten saw "no such email". The check is fine for the ordinary case (someone applying again tomorrow), but under concurrency it's only a hint.

### 10. Let the database decide

Create `src/main/resources/db/migration/V5__unique_email_hash.sql`:

```sql
ALTER TABLE application ADD CONSTRAINT uq_application_email_hash UNIQUE (email_hash);
```

A unique constraint is enforced by the database for every insert, whatever the timing: it's the one place that sees all the transactions. Both H2 and PostgreSQL allow any number of `NULL`s in a unique column, which matters in guide 05, where erased emails become `NULL`.

Run the test again:

```
created=1 duplicates=0 rows=1 unexpected=[DataIntegrityViolationException, DataIntegrityViolationException, … (9 in total)]
```

The **data** is now right: one row. The **behaviour** isn't: nine applicants would get a `500` instead of a clear `409`. The underlying error is:

```
DataIntegrityViolationException: could not execute statement [Unique index or primary key violation: "PUBLIC.UQ_APPLICATION_EMAIL_HASH INDEX PUBLIC.UQ_APPLICATION_EMAIL_HASH_INDEX_D ON PUBLIC.APPLICATION(EMAIL_HASH NULLS FIRST) VALUES ( /* 4 */ 'd14fcc1b…' )"; SQL statement: …
```

Translate it. `save` doesn't necessarily write anything until the transaction commits, by which time you're outside your method and can't catch the error in a useful place. **`saveAndFlush`** writes the `INSERT` immediately, so the violation is thrown right there. Replace the `save` line in `receive`:

```java
        Application application;
        try {
            // flush now, so a duplicate fails here, where we can explain it
            application = applications.saveAndFlush(Application.receive(applicant, emailHash, clock.instant()));
        } catch (DataIntegrityViolationException e) {
            if (isViolationOf(e, "uq_application_email_hash")) {
                throw new DuplicateApplicationException();
            }
            throw e;
        }
```

and add the helper (imports: `org.hibernate.exception.ConstraintViolationException`, `org.springframework.dao.DataIntegrityViolationException`, `java.util.Locale`):

```java
    /** H2 reports a longer name than PostgreSQL ("PUBLIC.UQ_... INDEX ..."), so match on "contains". */
    private static boolean isViolationOf(DataIntegrityViolationException e, String constraint) {
        return e.getCause() instanceof ConstraintViolationException violation
                && violation.getConstraintName() != null
                && violation.getConstraintName().toLowerCase(Locale.ROOT).contains(constraint);
    }
```

- **Only translate the violation you expect.** Any other integrity problem is a bug and should stay loud, hence `throw e`.
- **Hibernate's `ConstraintViolationException`** (not the Bean Validation class of the same name) carries the constraint name it extracted from the database's error.
- **Why `contains`?** The obvious version, `constraint.equalsIgnoreCase(violation.getConstraintName())`, never matched on H2. Printing the name showed why: H2 reports `PUBLIC.UQ_APPLICATION_EMAIL_HASH INDEX PUBLIC.UQ_APPLICATION_EMAIL_HASH_INDEX_D`, the constraint *and* the index behind it. PostgreSQL reports just the constraint name. A case-insensitive `contains` works for both.

Run the test three times:

```
created=1 duplicates=9 rows=1 unexpected=[]
```

✅ One application, nine clean `409`s, every time. The `existsByEmailHash` check stays: it gives a fast, friendly answer in the ordinary case, and the constraint is the **backstop** for the race.

Add to `requests.http`:

```http
### Apply again with the same email, different case (409)
POST http://localhost:8080/applications
Content-Type: application/json

{
  "givenName": "Alice",
  "familyName": "Smith",
  "dateOfBirth": "1990-05-17",
  "email": "ALICE@example.com",
  "addressLine1": "1 High Street",
  "postcode": "G1 1XQ",
  "expectedMonthlyDeposits": "FROM_1K_TO_5K"
}
```

```json
{"detail":"An application already exists for this email address","instance":"/applications","status":409,"title":"Duplicate application"}
```

---

## Your turn

### Exercise 1: rotate the key

Prove that rotation works. Add a test to `PiiCipherTest`: encrypt a value with the `test1` cipher, then build a new `PiiCipher` that knows **both** `test1` and a new key `test2`, with `test2` active. It must decrypt the old value, and new values must start with `test2:`.

<details>
<summary>Solution</summary>

```java
    static final String KEY_2 = "AwHQt7qzGRk8v6bY7IDU3re+JmPM35cueVJBXtGAkbk=";

    @Test
    void afterRotationNewValuesUseTheNewKeyAndOldValuesStillDecrypt() {
        String underOldKey = cipher.encrypt("Alice");
        PiiCipher rotated = new PiiCipher(new CryptoProperties("test2",
                Map.of("test1", KEY_1, "test2", KEY_2), KEY_1));

        assertThat(rotated.decrypt(underOldKey)).isEqualTo("Alice");
        assertThat(rotated.encrypt("Alice")).startsWith("test2:");
    }
```

In configuration, rotation is: add `onboarding.crypto.keys.k2=…`, set `active-key-id=k2`, deploy. Old rows still read. A background job re-encrypts them, and when it's finished (a query for `NOT LIKE 'k2:%'` returns nothing), you remove `k1`.

Notice the blind index key is **not** part of this scheme. Changing it changes every hash, so rotating it means recomputing the whole `email_hash` column in one go. That's another reason to keep it separate from the encryption keys.

</details>

### Exercise 2: encrypt the date of birth

Date of birth is still plain text, and it's one of the most useful pieces of data for identity fraud. Encrypt it.

1. Write `EncryptedLocalDateConverter implements AttributeConverter<LocalDate, String>`, storing the encrypted ISO date (`1990-05-17`).
2. Add `V6__encrypt_date_of_birth.sql` to make the column a `VARCHAR(700)`.
3. Use the converter on `dateOfBirth`.
4. Extend `EncryptionAtRestTest` to select `date_of_birth` and assert it starts with `dev1:` and doesn't contain `1990`.

<details>
<summary>Solution</summary>

`crypto/EncryptedLocalDateConverter.java`:

```java
package com.example.onboarding.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.LocalDate;

/** Stores a LocalDate as encrypted ISO text, e.g. "1990-05-17" before encryption. */
@Converter
public class EncryptedLocalDateConverter implements AttributeConverter<LocalDate, String> {

    private final PiiCipher cipher;

    public EncryptedLocalDateConverter(PiiCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(LocalDate attribute) {
        return attribute == null ? null : cipher.encrypt(attribute.toString());
    }

    @Override
    public LocalDate convertToEntityAttribute(String dbData) {
        return dbData == null ? null : LocalDate.parse(cipher.decrypt(dbData));
    }
}
```

`src/main/resources/db/migration/V6__encrypt_date_of_birth.sql`:

```sql
ALTER TABLE application ALTER COLUMN date_of_birth SET DATA TYPE VARCHAR(700);
```

In `Application.java`:

```java
    @Convert(converter = EncryptedLocalDateConverter.class)
    @Column(name = "date_of_birth", nullable = false, length = 700)
    private LocalDate dateOfBirth;
```

In `EncryptionAtRestTest`, add `date_of_birth` to the `SELECT` and:

```java
        assertThat(row.get("DATE_OF_BIRTH").toString()).startsWith("dev1:").doesNotContain("1990");
```

The entity still has a `LocalDate`, so the age check, the sanctions date-of-birth comparison and the JSON all work unchanged. The cost: the database can no longer compare dates of birth, so a query such as "everyone born before 1960" is impossible in SQL. Encrypting a field means deciding you'll never need the database to search or sort by it.

</details>

---

## Checkpoint

```bash
./mvnw test
```

```
[INFO] Tests run: 84, Failures: 0, Errors: 0, Skipped: 0
```

(83 from the steps, plus the rotation test. Exercise 2 extends an existing test.) Run it a few times: `ConcurrentApplicationTest` must pass every time.

New files since guide 03:

```
src/main/java/com/example/onboarding/
├── application/DuplicateApplicationException.java
└── crypto/
    ├── BlindIndex.java
    ├── CryptoProperties.java
    ├── EncryptedLocalDateConverter.java
    ├── EncryptedStringConverter.java
    └── PiiCipher.java
src/main/resources/db/migration/
├── V4__encrypt_personal_data.sql
├── V5__unique_email_hash.sql
└── V6__encrypt_date_of_birth.sql
src/test/java/com/example/onboarding/
├── application/ConcurrentApplicationTest.java
└── crypto/
    ├── BlindIndexTest.java
    ├── EncryptionAtRestTest.java
    └── PiiCipherTest.java
```

Modified: `application.properties` (keys), `CustomerOnboardingApplication.java` (`@ConfigurationPropertiesScan`), `Application.java`, `ApplicationRepository.java`, `ApplicationService.java`, `ApiExceptionHandler.java`, `TestApplicants.java`, `ApplicationApiTest.java`, `ApplicationTest.java`, `ApplicationReviewTest.java`, `requests.http`.

---

## Common errors

**`JpaSystemException: Error attempting to apply AttributeConverter` … `Caused by: java.lang.IllegalArgumentException: Not an encrypted value (no key id)`**
The database has rows written before encryption. In development, stop the app and delete the `data/` folder. In production you'd migrate the data (see the sidebar in step 6).

**`Value too long for column "POSTCODE CHARACTER VARYING(10)": "'dev1:oAv3bEgOCNg7v2Pbjbuetv+WoYhFffaEfJATtyXCdNhXew==' (53)"`**
A column wasn't widened in `V4`. Notice that **`ddl-auto=validate` didn't catch this**: the entity says `length = 700` and the column is 10, and the application still started. Hibernate's validation checks column *types*, not lengths, so the mistake only shows up at the first insert. Add a new migration that widens the column.

**`No key configured for active key id dev2`** (at start-up)
`onboarding.crypto.active-key-id` names a key that isn't in `onboarding.crypto.keys`. Check the spelling of both properties.

**`Key k must be 32 bytes (AES-256), was 16`**
The Base64 value isn't a 256-bit key. Generate one with `openssl rand -base64 32`.

**`Decryption failed` caused by `AEADBadTagException`**
The value was encrypted with a different key under the same key id (for example, you changed `keys.dev1` instead of adding `keys.dev2`), or the stored value was altered. Never change a key's value; add a new key id.

**`expected: 201 but was: 409` in tests that used to pass**
Two tests used the same email address. Use `TestApplicants.uniqueEmail(...)`.

**`expected: 409 but was: 400`** when testing the same email with spaces around it
`@Email` rejects `" ALICE@EXAMPLE.COM "` before the blind index ever sees it. The normalisation in `BlindIndex` handles case; validation handles the rest.

**`ConcurrentApplicationTest`: `unexpected=[DataIntegrityViolationException, …]`**
The unique constraint is working but the violation isn't being translated. Use `saveAndFlush` and check `isViolationOf`. If you used `equalsIgnoreCase` on the constraint name, see step 10.

---

## Recap

- Banks encrypt at several layers. **Application-level field encryption** protects personal data even from people who can query the database.
- **AES-GCM** gives confidentiality *and* integrity. Use a **fresh random 12-byte IV per value**, never reuse one with the same key, and let the tag catch tampering.
- **Keys don't belong in git.** Use a development key with a warning locally; in production use a **KMS/HSM**, often with envelope encryption.
- Store a **key id** with every value so you can **rotate keys** without a big-bang migration.
- A JPA **`AttributeConverter`** keeps encryption invisible to the rest of the code. Prove it works by reading the **raw column with `JdbcTemplate`**.
- Encrypted data can't be searched. A **blind index** (HMAC with its own key, over a normalised value) restores equality search, at the cost of revealing which rows are equal.
- **Check-then-insert races.** A **unique constraint** is the real guarantee; the check is a courtesy. **`saveAndFlush`** surfaces the violation where you can translate it.

### Interview angle

<details>
<summary>"How would you protect customers' personal data in the database?"</summary>

In layers: disk and database encryption against stolen media and backups, plus application-level encryption of the most sensitive fields, so database administrators, SQL injection and copied backups only ever see ciphertext. Use authenticated encryption such as AES-GCM with a unique random IV per value. Keep keys in a KMS or HSM, not in configuration, ideally with envelope encryption, and store a key id with each value so keys can be rotated. Add access control, auditing of access, data minimisation, and retention limits: data you don't store can't leak.

</details>

<details>
<summary>"If the data is encrypted, how do you search it?"</summary>

For exact matches, a blind index: a keyed hash (HMAC) of the normalised value, stored in its own column with its own key, which you can index and put a unique constraint on. It reveals equality, so only use it where that's acceptable, and avoid low-variety fields. Range queries, sorting and partial matching aren't possible on encrypted columns. If you need them, you either keep that field unencrypted (with other protections), or search in a separate, tightly controlled service.

</details>

<details>
<summary>"Why AES-GCM rather than, say, AES-CBC or AES-ECB?"</summary>

ECB encrypts identical blocks to identical ciphertext, so patterns leak; it should never be used for data. CBC hides patterns but gives no integrity on its own, so tampering goes undetected unless you add a separate MAC, and getting that combination right is error-prone. GCM is authenticated encryption: it provides confidentiality and an integrity tag in one standard construction. Its one sharp edge is that an IV must never repeat under the same key, which is why each value gets a fresh random IV.

</details>

<details>
<summary>"How do you stop duplicate records when two requests arrive at the same time?"</summary>

A check-then-insert is a race: both requests can check before either inserts. Put a unique constraint on the natural key (here the email blind index) so the database rejects the second insert, then translate that specific constraint violation into a meaningful error, such as `409`. Flush the insert inside the service so the violation surfaces where you can catch it. Keep the pre-check for a friendly answer in the common case, but rely on the constraint for correctness.

</details>

### Further reading

- [ICO: encryption](https://ico.org.uk/for-organisations/uk-gdpr-guidance-and-resources/security/encryption/), and [UK GDPR Article 32 (security of processing)](https://www.legislation.gov.uk/eur/2016/679/article/32)
- [OWASP Cryptographic Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html)
- [NIST SP 800-38D: the GCM specification](https://csrc.nist.gov/pubs/sp/800/38/d/final), section 8 for the IV uniqueness rules
- [Hibernate ORM 7.4 User Guide: `AttributeConverter`](https://docs.hibernate.org/orm/7.4/userguide/html_single/#basic-jpa-convert)

**Simplification to notice:** the key lives in `application.properties`, and the application holds it in memory for its whole life. Production systems fetch keys from a KMS at start-up (or per data key), restrict who can deploy configuration, and log every use of a master key.

**Next:** [05: GDPR, retention and erasure](05-gdpr-retention-and-erasure.md). Show applicants only what they need, keep personal data out of the logs, and answer the hardest question in onboarding data: "please delete everything about me".
