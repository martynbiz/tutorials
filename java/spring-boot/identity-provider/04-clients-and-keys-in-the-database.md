# 04: Clients and signing keys in the database

> Written for **Spring Boot 4.1.2**, **Spring Security 7.1.2** and **Java 25**. Builds on [guide 03](03-real-users.md).

## What you'll learn

- What an authorisation server has to **remember**: clients, authorisations (codes and tokens) and consents
- Why keeping them in memory breaks on every restart, and when running more than one server
- How to store them with Spring's **JDBC** implementations: `JdbcRegisteredClientRepository`, `JdbcOAuth2AuthorizationService` and `JdbcOAuth2AuthorizationConsentService`
- How to register clients in code, with **hashed client secrets**
- How to keep the **RSA signing key** across restarts by supplying your own `JWKSource`, and what **key rotation** involves
- Why start-up work must finish **before** the server takes requests, and how to make sure it does

By the end, you can restart Passport without signing anyone out of anything.

---

## Concepts

### What Passport remembers

Three kinds of data sit behind the protocol:

| | Interface | Holds | So far, kept in |
|---|---|---|---|
| **Clients** | `RegisteredClientRepository` | Each application's id, hashed secret, redirect URIs, scopes and settings | Memory, from `application.properties` |
| **Authorisations** | `OAuth2AuthorizationService` | For each sign-in: the code, the tokens issued for it, the `state`, the PKCE challenge, the user | Memory |
| **Consents** | `OAuth2AuthorizationConsentService` | Which scopes each user has agreed to give each client (guide 05) | Memory |

Then there's the **signing key**. Spring Boot generated an RSA key pair at start-up, in memory.

In memory, all of it disappears on restart:

- a code issued just before the restart can't be swapped for tokens
- `/userinfo` no longer recognises any access token, because the authorisation behind it has gone
- worst of all, **every token ever issued fails signature checks**: its `kid` names a key that no longer exists. Any API that accepted Passport's tokens rejects them all

And production runs **several instances** of an identity provider behind a load balancer. A code issued by instance A must be redeemable at instance B. Every instance must sign with keys the others publish. So the state must be **shared**, and a database is the simplest way to share it.

### Storing the signing key

The private key is the most sensitive thing Passport has: anyone holding it can mint tokens for any user, for any application. This guide stores it in Passport's database, which is enough to survive restarts and share between instances, and easy to see. Real identity providers keep private keys in a **hardware security module (HSM)** or a cloud **key management service** (Azure Key Vault, AWS KMS, Google Cloud KMS). There, the key never leaves the device, and the server asks the device to sign. Entra does this.

### Key rotation

Signing keys should change from time to time, and immediately if one might have leaked. Because tokens name their key with `kid`, rotation can happen without breaking anyone:

1. **Publish** the new public key in the JWKS alongside the old one. Clients and APIs that cache the JWKS pick it up.
2. **Start signing** new tokens with the new key.
3. **Remove** the old public key once every token signed with it has expired.

Passport keeps all its keys in a table and signs with the newest. Publishing old keys during a rotation is left as a later improvement. You can see Entra's current public keys at its `jwks_uri`, and they do change.

---

## Steps

### 1. See the problem

With guide 03's Passport running, get an access token by hand, exactly as in [guide 01, steps 7 and 8](01-oauth2-oidc-and-a-first-idp.md#7-sign-in-by-hand-the-authorisation-request), and check it works:

```bash
curl -i -H "Authorization: Bearer PASTE_THE_ACCESS_TOKEN_HERE" http://login.idp.test:9000/userinfo
```

That's `200`. Note the `kid` at `http://login.idp.test:9000/oauth2/jwks`. Now **restart Passport**, and run the same `curl`:

```
HTTP/1.1 401
WWW-Authenticate: Bearer error="invalid_token", error_description="An error occurred while attempting to
decode the Jwt: Signed JWT rejected: Another algorithm expected, or no matching key(s) found", ...
```

The JWKS has a new `kid`. The token's signature was made with a key that no longer exists. That's what this guide fixes.

### 2. The authorisation server's tables

Spring's authorisation server ships the SQL for its tables inside its jar. In IntelliJ, open **External Libraries → Maven: org.springframework.security:spring-security-oauth2-authorization-server:7.1.2** and look under `org/springframework/security/oauth2/server/authorization/` for `oauth2-authorization-schema.sql` and `oauth2-authorization-consent-schema.sql`, and under `…/authorization/client/` for `oauth2-registered-client-schema.sql`.

Spring can run them for you, but Passport uses Flyway, so copy them into a migration. Create `db/migration/V2__create_authorization_server_tables.sql`:

```sql
-- Spring Authorization Server's own tables, copied unchanged from the schema files in
-- spring-security-oauth2-authorization-server.jar (see guide 04).

CREATE TABLE oauth2_registered_client (
    id varchar(100) NOT NULL,
    client_id varchar(100) NOT NULL,
    client_id_issued_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret varchar(200) DEFAULT NULL,
    client_secret_expires_at timestamp DEFAULT NULL,
    client_name varchar(200) NOT NULL,
    client_authentication_methods varchar(1000) NOT NULL,
    authorization_grant_types varchar(1000) NOT NULL,
    redirect_uris varchar(1000) DEFAULT NULL,
    post_logout_redirect_uris varchar(1000) DEFAULT NULL,
    scopes varchar(1000) NOT NULL,
    client_settings varchar(2000) NOT NULL,
    token_settings varchar(2000) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE oauth2_authorization (
    id varchar(100) NOT NULL,
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorization_grant_type varchar(100) NOT NULL,
    authorized_scopes varchar(1000) DEFAULT NULL,
    attributes blob DEFAULT NULL,
    state varchar(500) DEFAULT NULL,
    authorization_code_value blob DEFAULT NULL,
    authorization_code_issued_at timestamp DEFAULT NULL,
    authorization_code_expires_at timestamp DEFAULT NULL,
    authorization_code_metadata blob DEFAULT NULL,
    access_token_value blob DEFAULT NULL,
    access_token_issued_at timestamp DEFAULT NULL,
    access_token_expires_at timestamp DEFAULT NULL,
    access_token_metadata blob DEFAULT NULL,
    access_token_type varchar(100) DEFAULT NULL,
    access_token_scopes varchar(1000) DEFAULT NULL,
    oidc_id_token_value blob DEFAULT NULL,
    oidc_id_token_issued_at timestamp DEFAULT NULL,
    oidc_id_token_expires_at timestamp DEFAULT NULL,
    oidc_id_token_metadata blob DEFAULT NULL,
    refresh_token_value blob DEFAULT NULL,
    refresh_token_issued_at timestamp DEFAULT NULL,
    refresh_token_expires_at timestamp DEFAULT NULL,
    refresh_token_metadata blob DEFAULT NULL,
    user_code_value blob DEFAULT NULL,
    user_code_issued_at timestamp DEFAULT NULL,
    user_code_expires_at timestamp DEFAULT NULL,
    user_code_metadata blob DEFAULT NULL,
    device_code_value blob DEFAULT NULL,
    device_code_issued_at timestamp DEFAULT NULL,
    device_code_expires_at timestamp DEFAULT NULL,
    device_code_metadata blob DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE oauth2_authorization_consent (
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorities varchar(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);
```

The original files start with a comment about PostgreSQL and MySQL: `blob` becomes `text`, and `timestamp` becomes `timestamptz`. H2 needs no changes.

`oauth2_authorization` has a column for every kind of token, including refresh tokens and the device flow, which Passport doesn't use. One row is one sign-in by one user at one client.

And a table for signing keys, `db/migration/V3__create_signing_key.sql`:

```sql
CREATE TABLE signing_key (
    key_id      VARCHAR(36)              NOT NULL PRIMARY KEY,
    public_key  VARCHAR(1000)            NOT NULL,
    private_key VARCHAR(4000)            NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
```

### 3. Use the database

Create `security/AuthorizationServerConfig.java`:

```java
package com.example.idp.security;

import com.example.idp.key.SigningKeyStore;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/** Where the authorisation server keeps its data: all of it in Passport's own database. */
@Configuration
public class AuthorizationServerConfig {

    /** The client applications that may use Passport. */
    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcOperations jdbc) {
        return new JdbcRegisteredClientRepository(jdbc);
    }

    /** Authorisation codes, and the tokens issued for them. */
    @Bean
    OAuth2AuthorizationService authorizationService(JdbcOperations jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }

    /** Which scopes each user has agreed to give each client. */
    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(JdbcOperations jdbc,
                                                                  RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }

    /** The keys that sign tokens, and that /oauth2/jwks publishes so others can check the signatures. */
    @Bean
    JWKSource<SecurityContext> jwkSource(SigningKeyStore signingKeys) {
        return (selector, context) -> selector.select(new JWKSet(signingKeys.currentKey()));
    }
}
```

- **The three `Jdbc…` classes** are Spring's database implementations of the three interfaces. They take a `JdbcOperations`, the interface of the `JdbcTemplate` Spring Boot already created for the data source. The authorisation server looks up beans of these types, so declaring them is all it takes.
- **Spring Boot backs off.** Its in-memory client repository built from properties, and its generated key, are only created when no bean of that type exists. Now yours do.
- **A `JWKSource`** is the Nimbus library's "give me keys matching this description" interface. The authorisation server uses it both to sign tokens and to serve `/oauth2/jwks`. Here it offers one key, the current one. With a `JWKSource` of yours in place, Spring Boot still builds the `JwtDecoder` that `/userinfo` uses to check access tokens, from your keys.

Delete the client properties from `application.properties` (everything under `# The shop, registered…`). Only the issuer and the datasource settings remain:

```properties
spring.application.name=idp
server.port=9000

# The issuer: who signs the tokens. It appears in every token and in the discovery document.
spring.security.oauth2.authorizationserver.issuer=http://login.idp.test:9000

# Users, clients, authorisations, consents and signing keys live in ./data/idp.mv.db
spring.datasource.url=jdbc:h2:file:./data/idp
spring.datasource.username=sa
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
```

### 4. Register the shop in code

Clients now live in the database, but something has to put the shop there. A real IdP has an admin portal for that (Entra's **App registrations**). Passport registers its clients in code, at start-up. Create `client/ClientRegistrations.java`:

```java
package com.example.idp.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;

/**
 * Writes Passport's client applications to the database at start-up, before Passport accepts
 * any requests. The code here is the source of truth: change a client below, restart, and the
 * database row is updated to match.
 */
@Component
public class ClientRegistrations implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ClientRegistrations.class);

    private final RegisteredClientRepository clients;
    private final PasswordEncoder passwordEncoder;

    public ClientRegistrations(RegisteredClientRepository clients, PasswordEncoder passwordEncoder) {
        this.clients = clients;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void afterSingletonsInstantiated() {
        save(RegisteredClient.withId("shop")
                .clientId("shop")
                .clientName("The Shop")
                .clientSecret(passwordEncoder.encode("shop-secret"))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://shop.test:8080/login/oauth2/code/passport")
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                .build());
    }

    /** Inserts the client, or updates it if a client with the same id is already there. */
    private void save(RegisteredClient client) {
        clients.save(client);
        log.info("Saved client {}", client.getClientId());
    }
}
```

- **`RegisteredClient`** is the same information the properties held, as an object. `OidcScopes.OPENID` and friends are constants for `"openid"`, `"profile"` and `"email"`.
- **`withId("shop")`**: every registered client has an internal **id** (the table's primary key) as well as its public **client id**. Using a fixed id makes `save` an **upsert**: `JdbcRegisteredClientRepository.save` updates the row if that id exists, and inserts it otherwise. Change the shop's registration here, restart, and the database follows. (With a random id per start, the second start would try to insert another `shop`, and the repository refuses a duplicate client id.)
- **`passwordEncoder.encode("shop-secret")`** stores a BCrypt hash, exactly like a user's password. The client secret is a password for an application. If the database leaks, the thief can't use the hashes to pretend to be the shop. The shop still sends `shop-secret`, and Passport checks it with `matches`, as in guide 03.
- **`SmartInitializingSingleton`** is explained in step 6.

### 5. A signing key that survives restarts

Create the `key` package. `key/SigningKey.java` is an entity holding one RSA key pair:

```java
package com.example.idp.key;

import com.nimbusds.jose.jwk.RSAKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** An RSA key pair that Passport signs tokens with. */
@Entity
@Table(name = "signing_key")
public class SigningKey {

    @Id
    @Column(name = "key_id", length = 36)
    private String keyId;

    @Column(name = "public_key", nullable = false, length = 1000)
    private String publicKey;

    @Column(name = "private_key", nullable = false, length = 4000)
    private String privateKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SigningKey() {
        // for JPA
    }

    /** Makes a brand new 2048-bit RSA key pair, with a random id. */
    public static SigningKey generate(Instant now) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();

            SigningKey key = new SigningKey();
            key.keyId = UUID.randomUUID().toString();
            key.publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
            key.privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
            key.createdAt = now;
            return key;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not generate an RSA key pair", e);
        }
    }

    /** The key in the form the JWT library signs with, and publishes at /oauth2/jwks. */
    public RSAKey toRsaKey() {
        try {
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            RSAPublicKey publicKey = (RSAPublicKey) rsa.generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(this.publicKey)));
            RSAPrivateKey privateKey = (RSAPrivateKey) rsa.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(this.privateKey)));
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(keyId)
                    .build();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Stored signing key " + keyId + " is unreadable", e);
        }
    }

    public String getKeyId() {
        return keyId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```

- **2048-bit RSA** is the minimum for RS256 signing today.
- **`getEncoded()`** gives the standard binary forms, **X.509** for the public key and **PKCS#8** for the private one. Base64 makes them storable as text, and `KeyFactory` turns them back into keys.
- **`RSAKey`** is Nimbus's JWK type. Built with the private key, it can sign. When the JWKS endpoint publishes it, only the public parts (`n`, `e`) and the `kid` are written.

`key/SigningKeyRepository.java`:

```java
package com.example.idp.key;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SigningKeyRepository extends JpaRepository<SigningKey, String> {

    Optional<SigningKey> findFirstByOrderByCreatedAtDesc();
}
```

`key/SigningKeyStore.java`:

```java
package com.example.idp.key;

import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Keeps Passport's signing key in the database, so it survives restarts. */
@Component
public class SigningKeyStore implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyStore.class);

    private final SigningKeyRepository keys;

    public SigningKeyStore(SigningKeyRepository keys) {
        this.keys = keys;
    }

    /** At start-up, before Passport accepts any requests, creates the first key if there isn't one yet. */
    @Override
    public void afterSingletonsInstantiated() {
        if (keys.count() == 0) {
            SigningKey key = keys.save(SigningKey.generate(Instant.now()));
            log.info("Generated signing key {}", key.getKeyId());
        }
    }

    /** The newest key: the one new tokens are signed with. */
    @Transactional(readOnly = true)
    public RSAKey currentKey() {
        return keys.findFirstByOrderByCreatedAtDesc()
                .orElseThrow(() -> new IllegalStateException("No signing key in the database"))
                .toRsaKey();
    }
}
```

The first start generates a key. Every later start finds it there.

### 6. Do start-up work before the doors open

Both `ClientRegistrations` and `SigningKeyStore` must finish **before Passport handles its first request**. Otherwise a sign-in that arrives early finds no client and no key.

**`SmartInitializingSingleton`** is a Spring callback: `afterSingletonsInstantiated()` runs once, as soon as every bean has been created, during start-up, **before** the embedded web server starts accepting connections. The repositories are ready by then, and Flyway has already created the tables.

> **Sidebar: why not `ApplicationRunner` or `CommandLineRunner`?** They're the usual place for start-up work, but Spring Boot runs them **after** the web server has started. While this series was being written, they were used here first. On a fresh database, Tomcat was accepting requests for about a second and a half before the key and the client existed, and a sign-in in that window ended on an error page. With `SmartInitializingSingleton`, the log shows the work finishing first:
>
> ```
> c.e.idp.client.ClientRegistrations       : Saved client shop
> com.example.idp.key.SigningKeyStore      : Generated signing key 722fc806-1b62-427f-b5c2-ce6a2a39c93b
> o.s.boot.tomcat.TomcatWebServer          : Tomcat started on port 9000 (http) with context path '/'
> com.example.idp.IdpApplication           : Started IdpApplication in 17.22 seconds
> ```

### 7. Restart without losing anything

Restart Passport. Flyway applies the two new migrations, and the start-up work runs:

```
Migrating schema "PUBLIC" to version "2 - create authorization server tables"
Migrating schema "PUBLIC" to version "3 - create signing key"
...
Saved client shop
Generated signing key c9610929-427d-4461-9721-ba24e11a19cd
Tomcat started on port 9000 (http) with context path '/'
```

Sign in to the shop, in a private window: it works as before. Now repeat step 1: get an access token by hand, check `/userinfo` returns `200`, note the `kid`, and restart Passport. This time:

- the log says `Saved client shop`, but no new key is generated
- `/oauth2/jwks` shows the **same** `kid`
- the same access token still works:

```bash
curl -H "Authorization: Bearer PASTE_THE_ACCESS_TOKEN_HERE" http://login.idp.test:9000/userinfo
```

```json
{"sub":"alice"}
```

Both halves of the fix are needed. The token's signature still checks out (the key is stored), and Passport still knows the authorisation it belongs to (it's a row in `oauth2_authorization`).

### 8. Look inside the database

H2's command-line shell can query the database file directly. **Stop Passport first**: H2 allows only one process to open the file. Then, from Passport's project folder (one line):

```bash
java -cp ~/.m2/repository/com/h2database/h2/2.4.240/h2-2.4.240.jar org.h2.tools.Shell -url jdbc:h2:file:./data/idp -user sa -sql "SELECT client_id, client_secret, client_name, redirect_uris, scopes FROM oauth2_registered_client"
```

(In PowerShell, write the jar path as `"$HOME\.m2\repository\com\h2database\h2\2.4.240\h2-2.4.240.jar"`. If your H2 version differs, look in that folder.)

```
CLIENT_ID | CLIENT_SECRET                                                        | CLIENT_NAME | REDIRECT_URIS                                    | SCOPES
shop      | {bcrypt}$2a$10$oqoqXjJVxOfNZoSfl/6Nmu0ZlsS8ANOc4dIL2gTEPityuWFcyQml. | The Shop    | http://shop.test:8080/login/oauth2/code/passport | openid,profile,email
```

The secret is a hash. Try other queries the same way:

- **`SELECT client_settings FROM oauth2_registered_client`** shows the settings as JSON, including `"settings.client.require-proof-key":true`. That's the PKCE requirement you met in guide 01.
- **`SELECT principal_name, authorization_grant_type, authorized_scopes, access_token_expires_at FROM oauth2_authorization`** shows one row per sign-in:

  ```
  PRINCIPAL_NAME | AUTHORIZATION_GRANT_TYPE | AUTHORIZED_SCOPES    | ACCESS_TOKEN_EXPIRES_AT
  alice          | authorization_code       | openid,profile,email | 2026-11-11 11:44:55.401684
  ```

- **`SELECT key_id, created_at FROM signing_key`** shows the key's id and when it was made.

> **Sidebar: the table grows for ever.** Every sign-in adds a row to `oauth2_authorization`, and Spring never deletes expired ones. A production identity provider runs a scheduled job that deletes authorisations whose tokens have all expired.

---

## Your turn

### Exercise 1: rename the shop

Change the shop's client name to **"The Corner Shop"**, and confirm that the database row changes after a restart, without a migration.

<details>
<summary>Solution</summary>

In `ClientRegistrations`, change `.clientName("The Shop")` to `.clientName("The Corner Shop")`. Restart Passport (the log says `Saved client shop`), stop it, and query:

```bash
java -cp ~/.m2/repository/com/h2database/h2/2.4.240/h2-2.4.240.jar org.h2.tools.Shell -url jdbc:h2:file:./data/idp -user sa -sql "SELECT client_id, client_name FROM oauth2_registered_client"
```

`CLIENT_NAME` is `The Corner Shop`. Because the client's id is fixed, `save` updated the existing row. Change it back to `The Shop`, since guide 05 shows it on the consent page.

</details>

### Exercise 2: lose the key on purpose

Prove that the stored key is what keeps old tokens valid. Get an access token, stop Passport, delete the signing key with the H2 shell, start Passport, and try the token at `/userinfo`. Predict the result first.

<details>
<summary>Solution</summary>

```bash
java -cp ~/.m2/repository/com/h2database/h2/2.4.240/h2-2.4.240.jar org.h2.tools.Shell -url jdbc:h2:file:./data/idp -user sa -sql "DELETE FROM signing_key"
```

On start, the log says `Generated signing key …` with a new id, `/oauth2/jwks` shows the new `kid`, and the old token gets `401` with `no matching key(s) found`, as in step 1. The authorisation row is still there, but the signature can't be checked any more. That's exactly what happens to every token if a signing key is lost or must be revoked in a hurry.

</details>

---

## Checkpoint

- Restarting Passport keeps the same `kid` at `/oauth2/jwks`, and access tokens keep working.
- Signing in to the shop works as before.
- `oauth2_registered_client` holds the shop, with a `{bcrypt}` secret.

New and changed files:

```
idp/src/main/
├── java/com/example/idp/
│   ├── client/
│   │   └── ClientRegistrations.java
│   ├── key/
│   │   ├── SigningKey.java
│   │   ├── SigningKeyRepository.java
│   │   └── SigningKeyStore.java
│   └── security/
│       └── AuthorizationServerConfig.java
└── resources/
    ├── application.properties          (client properties removed)
    └── db/migration/
        ├── V2__create_authorization_server_tables.sql
        └── V3__create_signing_key.sql
```

---

## Common errors

**`Registered client must be unique. Found duplicate client identifier: shop`**
`ClientRegistrations` is creating a client with a new random id each time. Use `RegisteredClient.withId("shop")`, so `save` updates the existing row.

**`IllegalStateException: No signing key in the database`**
A token was requested before the key was generated: check `SigningKeyStore` implements `SmartInitializingSingleton` (step 6), not `ApplicationRunner`.

**The shop's sign-in ends with `Invalid credentials`, and Passport logs nothing**
Check the shop still sends the secret that's hashed in `ClientRegistrations` (`shop-secret`).

**`Database may be already in use` from the H2 shell**
Passport is still running. Stop it first.

**`Failed to select a key since there are multiple for the signing algorithm [RS256]`** (if you experiment with rotation)
The `JWKSource` offered more than one key that can sign RS256 tokens, and the encoder won't guess. Publishing old keys for verification while signing with only the newest needs the two kept apart: the message itself points at the encoder's `setJwkSelector`.

---

## Recap

- An authorisation server keeps **clients**, **authorisations** (codes and tokens) and **consents**, plus its **signing keys**. In memory, a restart loses them all, and several instances can't share them.
- **`JdbcRegisteredClientRepository`**, **`JdbcOAuth2AuthorizationService`** and **`JdbcOAuth2AuthorizationConsentService`** store them. The schema SQL ships in the jar.
- **Client secrets are hashed** with the same `PasswordEncoder` as user passwords.
- Your own **`JWKSource`** bean decides which keys sign and are published. Store the key, or every token dies with each restart. In production, keep private keys in an HSM or key vault.
- **Rotate** keys by publishing the new one first, then signing with it, then retiring the old one when its tokens have expired.
- Start-up work that requests depend on belongs in **`SmartInitializingSingleton`**, which runs before the web server starts, not in `ApplicationRunner`, which runs after.

### Interview angle

<details>
<summary>"How would you rotate the signing keys of an identity provider?"</summary>

Generate a new key pair and publish its public key in the JWKS alongside the current one, with a new `kid`. Wait for consumers' JWKS caches to refresh, then start signing new tokens with the new key. Keep publishing the old public key until the longest-lived tokens signed with it have expired, then remove it. Consumers find the right key by the `kid` in each token's header, and libraries refetch the JWKS when they see an unknown `kid`. For a suspected compromise, remove the old key immediately and accept that its tokens stop working. The private keys themselves should live in an HSM or key management service.

</details>

<details>
<summary>"Why store authorisation state in a database?"</summary>

Because codes, tokens and consents must outlive a restart, and be visible to every instance behind the load balancer: the code issued by one node is redeemed at another. It also enables revocation and auditing, since you can find and invalidate every token from a given authorisation, and reuse detection such as revoking tokens when a code is replayed. It needs housekeeping: expired authorisations have to be deleted, or the table grows without limit.

</details>

### Further reading

- [Spring Security reference: Authorization Server core model and components](https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/core-model-components.html)
- [RFC 7517: JSON Web Key (JWK)](https://www.rfc-editor.org/rfc/rfc7517)
- [Microsoft identity platform: signing key rollover](https://learn.microsoft.com/entra/identity-platform/signing-key-rollover)

**Next:** [05: Consent, claims and roles](05-consent-claims-and-roles.md). Passport asks before sharing, and tells the shop who you are.
