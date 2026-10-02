# batch-writer — Umsetzungsplan

**Ziel:** Der `batch-writer` holt die Nachrichten stapelweise aus `chat.persist` und schreibt
jeden Stapel in einer Transaktion nach PostgreSQL. Duplikate sind harmlos, ein
Datenbankausfall kostet keine Nachricht.

**Spezifikation:** [`spec-batch-writer.md`](spec-batch-writer.md). Jede Aufgabe verweist auf
den Abschnitt, den sie umsetzt.

**Tech-Stack:** Java 21, Spring Boot 3.5.16 (wie `chat-service`), Spring AMQP, Spring JDBC
(`JdbcTemplate`, kein JPA), PostgreSQL 16, RabbitMQ 3.13, JUnit 5, Testcontainers.

## Globale Vorgaben

Gelten für **jede** Aufgabe:

- Regeln aus [`CLAUDE.md`](../CLAUDE.md): Code auf Englisch, Kommentare und Commits auf
  Deutsch, ein Ergebnis pro Zeile, `for` statt Stream, Lombok nur für `@Slf4j` und
  `@RequiredArgsConstructor`, Datenklassen als `record`.
- **Über jeder Klasse und jeder Methode ein Kommentar** — auch in den Tests (Szenario S8).
- **Kein `ports:`-Eintrag** in `docker-compose.yml` (Szenario S2).
- Zugangsdaten nur in `.env`, im Repo nur `.env.example`.
- Ablauf jeder Aufgabe: Test schreiben → laufen lassen, Fehlschlag sehen → Code → Test grün →
  **ein** Commit mit der angegebenen Message.
- Tests mit echter Queue und echter Datenbank laufen über Testcontainers (`rabbitmq:3.13-management`,
  `postgres:16`). Ein gemeinsamer Container-Satz für alle Integrationstests, damit `mvn test`
  nicht für jede Testklasse neue Container startet.

## Abgrenzung

Nicht in diesem Plan, weil nicht Teil der Aufgabe (Spezifikation 1): Lesen der Historie,
Räume und Mitgliedschaften, Keycloak, web-gateway, load-generator.

## Dateistruktur

```
pom.xml                                   # Modul batch-writer ergänzt
docker-compose.yml                        # postgres und batch-writer, KEIN ports:-Eintrag
.env.example                              # POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD
chat-service/Dockerfile                   # baut nur noch sein eigenes Modul
batch-writer/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/
    │   ├── java/ch/benedict/m321/batchwriter/
    │   │   ├── BatchWriterApplication.java
    │   │   ├── config/
    │   │   │   ├── QueueNames.java           # chat.persist, chat.dlq
    │   │   │   └── RabbitConfig.java         # Queues wie im chat-service, Stapel-Verbraucher
    │   │   ├── dto/
    │   │   │   └── ChatMessage.java          # eigene Kopie des Vertrags
    │   │   ├── listener/
    │   │   │   └── PersistListener.java      # Stapel annehmen, verteilen, bestätigen
    │   │   ├── repository/
    │   │   │   └── MessageRepository.java    # ein Stapel = eine Transaktion
    │   │   └── service/
    │   │       ├── InvalidMessageException.java
    │   │       ├── MessageReader.java        # rohes JSON -> ChatMessage, Pflichtfelder
    │   │       └── MessageWriter.java        # schreiben, bei Ausfall warten und wiederholen
    │   └── resources/
    │       ├── application.yml
    │       └── schema.sql                    # Tabelle message und Index
    └── test/java/ch/benedict/m321/batchwriter/
        ├── IntegrationTestBase.java          # RabbitMQ und Postgres für alle Integrationstests
        ├── BatchWriterApplicationTest.java
        ├── SchemaIntegrationTest.java
        ├── listener/PersistListenerIntegrationTest.java
        ├── repository/MessageRepositoryIntegrationTest.java
        └── service/
            ├── MessageReaderTest.java
            └── DatabaseOutageIntegrationTest.java
```

---

## Task 1: chat-service-Image baut nur sein eigenes Modul

**Warum zuerst:** Task 4 nimmt `batch-writer` ins Eltern-POM auf. Ab dann scheitert der
Docker-Build des `chat-service`, weil `-pl chat-service -am` die ganze Modulliste liest und
der Ordner `batch-writer` in seinem Build-Kontext fehlt (Spezifikation 4.6).

**Dateien:** Ändern: `chat-service/Dockerfile`

- [x] `RUN mvn -q -pl chat-service -am package -DskipTests` ersetzen durch
      `RUN mvn -q -f chat-service/pom.xml package -DskipTests`, Kommentar warum.
- [x] **Test:** `docker compose build chat-service` endet ohne Fehler.
- [x] **Commit:** `fix: chat-service-Image baut nur sein eigenes Modul`

## Task 2: RabbitMQ erst gesund, wenn der AMQP-Port bereit ist

**Warum jetzt:** Alle folgenden Abnahmen schicken Nachrichten direkt nach dem Start. Ist der
Broker dann noch nicht bereit, antwortet der `chat-service` mit 503 und die Nachricht kommt
nie in die Queue (Spezifikation 4.6).

**Dateien:** Ändern: `docker-compose.yml`

- [x] Healthcheck von `rabbitmq` auf
      `["CMD", "rabbitmq-diagnostics", "-q", "check_port_connectivity"]`, Kommentar warum.
- [x] **Test:** `docker compose down && docker compose up -d`, danach sofort eine Nachricht
      senden (Hilfsbefehl aus Spezifikation 5 mit `N=1`): Antwort `202`.
- [x] **Commit:** `fix: rabbitmq erst gesund, wenn der AMQP-Port Verbindungen annimmt`

## Task 3: PostgreSQL im Stack

**Warum jetzt:** Ohne laufende Datenbank kann der `batch-writer` später im Stack nicht
starten. Der Dienst selbst braucht noch keinen Code dafür.

**Dateien:** Ändern: `docker-compose.yml`, `.env.example` · lokal: `.env`

- [x] Dienst `postgres` (`postgres:16`) mit `POSTGRES_DB`, `POSTGRES_USER`,
      `POSTGRES_PASSWORD` aus `.env`, Volume `chat-db-data`, Healthcheck
      `pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}`, Netz `chat-net`, **kein** `ports:`.
- [x] `.env.example` um die drei Variablen mit Beispielwerten ergänzen.
- [x] **Test:** `docker compose up -d postgres` → `docker compose ps` zeigt `healthy` und kein
      `->`; `docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT 1"'` ergibt `1`.
- [x] **Commit:** `chore: PostgreSQL in docker-compose aufnehmen`

## Task 4: Maven-Modul batch-writer mit Anwendungsstart

**Warum jetzt:** Alles Weitere sind Klassen in diesem Modul. Der erste Test beweist, dass
die Anwendung gegen eine echte Queue und eine echte Datenbank startet.

**Dateien:** Ändern: `pom.xml` · Anlegen: `batch-writer/pom.xml`,
`BatchWriterApplication.java`, `application.yml`, Test `IntegrationTestBase.java`,
`BatchWriterApplicationTest.java`

- [x] Modul `batch-writer` im Eltern-POM.
- [x] Abhängigkeiten: `spring-boot-starter-amqp`, `spring-boot-starter-jdbc`,
      `spring-boot-starter-json`, `org.postgresql:postgresql` (runtime), Lombok;
      Test: `spring-boot-starter-test`, Testcontainers `junit-jupiter`, `rabbitmq`, `postgresql`.
- [x] `application.yml`: RabbitMQ und Datasource aus den Variablen der Spezifikation 4.4.
- [x] `IntegrationTestBase`: startet RabbitMQ und Postgres **einmal** (statischer Block) und
      trägt Host, Port und Zugangsdaten per `@DynamicPropertySource` ein.
- [x] **Test** `BatchWriterApplicationTest.contextLoads`: Kontext startet.
- [x] **Prüfen:** `mvn -pl batch-writer test` grün.
- [x] **Commit:** `feat: Maven-Modul batch-writer mit Anwendungsstart`

## Task 5: Tabelle message mit Primärschlüssel und Index

**Warum jetzt:** Das Repository in Task 7 braucht die Tabelle. Das Schema ist klein und
lässt sich für sich allein prüfen (Spezifikation 4.1 bis 4.3).

**Dateien:** Anlegen: `schema.sql`, Test `SchemaIntegrationTest.java` · Ändern: `application.yml`

- [x] `schema.sql` wie in Spezifikation 4.1, alles mit `IF NOT EXISTS`.
- [x] `spring.sql.init.mode: always`, `spring.sql.init.continue-on-error: true`.
- [x] **Tests** `SchemaIntegrationTest`:
  - `createsMessageTableWithPlannedColumns` — die sechs Spalten mit Typ aus
    `information_schema.columns`
  - `usesIdAsPrimaryKey` — Primärschlüssel ist `id`
  - `createsIndexForRoomAndTime` — Index `message_room_id_sent_at_idx` existiert
  - `canRunSchemaTwice` — Skript ein zweites Mal ausführen wirft nichts
- [x] **Commit:** `feat: Tabelle message mit Primärschlüssel und Index`

## Task 6: Nachricht aus dem rohen JSON lesen und prüfen

**Warum jetzt:** Der Vertrag (Spezifikation 2.2) ist die Eingangstür. Er lässt sich ohne
Container testen und muss stehen, bevor irgendetwas geschrieben wird.

**Dateien:** Anlegen: `dto/ChatMessage.java`, `service/MessageReader.java`,
`service/InvalidMessageException.java`, Test `MessageReaderTest.java`

- [x] `ChatMessage` als `record` mit den sechs Feldern.
- [x] `MessageReader.read(Message)`: Körper als UTF-8 lesen, mit dem ObjectMapper von
      Spring Boot in `ChatMessage` wandeln, Header ignorieren; jedes fehlende Pflichtfeld
      und jedes ungültige JSON wird zu `InvalidMessageException` mit Grund.
- [x] **Tests** `MessageReaderTest`:
  - `readsMessageInChatServiceFormat` — Körper und Header wie im Beleg der Spezifikation 2.3
  - `readsMessageWithOnlyContentType` — ohne `__TypeId__` (Format aus S5)
  - `rejectsBodyThatIsNotJson`
  - `rejectsMessageWithoutId`
  - `rejectsIdThatIsNotAUuid`
- [x] **Commit:** `feat: Nachricht aus dem rohen JSON lesen und prüfen`

## Task 7: Stapel in einer Transaktion schreiben, Duplikate überspringen

**Warum jetzt:** Das ist der Kern (Spezifikation 3.1 Schritt 4, 3.3). Er wird an der echten
Datenbank geprüft, bevor RabbitMQ dazukommt.

**Dateien:** Anlegen: `repository/MessageRepository.java`, Test
`MessageRepositoryIntegrationTest.java`

- [x] `insertBatch(List<ChatMessage>)`: `@Transactional`, `JdbcTemplate.batchUpdate` mit
      `INSERT ... ON CONFLICT (id) DO NOTHING`; gibt die Zahl der neu gespeicherten Zeilen
      zurück.
- [x] **Tests** `MessageRepositoryIntegrationTest`:
  - `storesEveryMessageOfTheBatch`
  - `storesSameIdOnlyOnceAcrossBatches` — Duplikat in einem zweiten Stapel (S5)
  - `storesSameIdOnlyOnceWithinOneBatch` — Duplikat im selben Stapel (S5)
  - `writesWholeBatchInOneTransaction` — `xact_commit` steigt für 500 Nachrichten um 1 (S4)
- [x] **Commit:** `feat: Stapel in einer Transaktion schreiben, Duplikate überspringen`

## Task 8: Nachrichten stapelweise aus chat.persist holen und speichern

**Warum jetzt:** Lesen (Task 6) und Schreiben (Task 7) stehen. Jetzt verbindet der Listener
beide mit der Queue (Spezifikation 2.1, 3.1, 3.7).

**Dateien:** Anlegen: `config/QueueNames.java`, `config/RabbitConfig.java`,
`listener/PersistListener.java`, Test `PersistListenerIntegrationTest.java`

- [x] `RabbitConfig`: `chat.persist` und `chat.dlq` mit den Argumenten aus Spezifikation 2.1;
      Container-Factory mit `batchListener`, `consumerBatchEnabled`, Stapel 500, Wartezeit
      200 ms, prefetch 500, ein Verbraucher.
- [x] `PersistListener.onBatch(List<Message>)`: jede Nachricht lesen; kaputte mit Header
      `x-rejected-reason` nach `chat.dlq`; gültige mit `MessageRepository` schreiben;
      eine Logzeile `Batch of …`. Rückkehr ohne Fehler = ACK für den Stapel.
- [x] **Tests** `PersistListenerIntegrationTest`:
  - `declaresQueuesWithChatServiceArguments`
  - `storesThousandMessagesAndEmptiesQueue` (S3)
  - `storesDuplicateOnlyOnceAndNothingInDeadLetterQueue` — zweimal dieselbe Nachricht, nur
    mit `content_type` (S5)
  - `movesBrokenMessageToDeadLetterQueue` — und die gültige Nachricht im selben Stapel wird
    trotzdem gespeichert
- [x] **Commit:** `feat: Nachrichten stapelweise aus chat.persist holen und speichern`

## Task 9: Bei Datenbankausfall warten und denselben Stapel erneut schreiben

**Warum jetzt:** Erst wenn der Normalweg steht, lässt sich der Ausfall dagegen prüfen
(Spezifikation 3.5, 3.6).

**Dateien:** Anlegen: `service/MessageWriter.java`, Test `DatabaseOutageIntegrationTest.java` ·
Ändern: `PersistListener.java`, `application.yml`

- [x] `MessageWriter.writeUntilStored(List<ChatMessage>)`: ruft `insertBatch`; bei
      `DataAccessException` oder `TransactionException` Pause 1 s, dann doppelt, höchstens
      10 s, und erneut; bei Unterbrechung (Stopp) Abbruch mit Ausnahme, damit der Stapel
      unbestätigt zurückgeht.
- [x] `PersistListener` schreibt über `MessageWriter`.
- [x] Pool: `connection-timeout: 5000`.
- [x] **Test** `DatabaseOutageIntegrationTest.keepsMessagesUntilDatabaseIsBack` (S7):
      Datenbank sperren (`ALLOW_CONNECTIONS false`, alle Verbindungen beenden), 300 Nachrichten
      senden, 15 s warten — es darf nichts gespeichert und nichts in `chat.dlq` sein —,
      Datenbank freigeben, nach höchstens 90 s sind alle 300 da und `chat.persist` ist leer.
- [x] **Commit:** `feat: bei Datenbankausfall warten und denselben Stapel erneut schreiben`

## Task 10: batch-writer in docker-compose aufnehmen

**Warum jetzt:** Der Dienst ist fertig getestet. Jetzt kommt er in den Stack, und erst damit
lassen sich S2 bis S7 messen (Spezifikation 4.6).

**Dateien:** Anlegen: `batch-writer/Dockerfile` · Ändern: `docker-compose.yml`, `README.md`

- [x] `Dockerfile` zweistufig wie beim `chat-service`, Build mit `-f batch-writer/pom.xml`.
- [x] Dienst `batch-writer`: Variablen aus Spezifikation 4.4, `depends_on` `rabbitmq` und
      `postgres` mit `service_healthy`, `restart: unless-stopped`, Netz `chat-net`, **kein**
      `ports:`.
- [x] README: Tabelle „Stand" für `batch-writer` und `postgres` nachführen.
- [x] **Test:** S2 aus Spezifikation 5 — alle vier Dienste laufen, `0` Port-Mappings.
- [x] **Commit:** `chore: batch-writer in docker-compose aufnehmen`

## Task 11: Abnahme S1 bis S8

**Warum zuletzt:** Die Szenarien prüfen das Ganze, in der Reihenfolge und auf einem Stack,
wie bei der Bewertung.

- [x] Frischer Klon in einem leeren Ordner, `.env` aus `.env.example`.
- [x] S1 bis S8 mit den Befehlen aus Spezifikation 5 nacheinander ausführen, ohne Aufräumen
      dazwischen. Gemessene Werte notieren.
- [x] **Commit:** nur wenn ein Szenario eine Korrektur verlangt — dann jede Korrektur als
      eigener Commit `fix: …`, mit Test.

---

## Nachtrag: Abweichungen bei der Umsetzung

Was anders kam als geplant, in der Reihenfolge der Aufgaben. Die Commits sind unverändert,
dieser Abschnitt erklärt sie.

| Task | Geplant | Tatsächlich | Grund |
|---|---|---|---|
| 2 | Nach `up -d` **sofort** senden | Senden, **sobald** `Started ChatServiceApplication` im Log steht | Direkt nach `up -d` lief Tomcat im `chat-service` noch nicht (`curl` meldete `000`). Der gesuchte Fehler war ein anderer: mit `ping` gab die erste Nachricht nach dem Start `503`, mit `check_port_connectivity` `202` — beides gemessen |
| 7 | Eine Transaktion pro Stapel über `xact_commit` prüfen | Über `xmin` der Zeilen prüfen | `pg_stat_database` wird verzögert nachgeführt, ein Test darauf wäre wackelig. `xmin` ist die Nummer der schreibenden Transaktion und damit exakt. Im Stack (S4) wird weiterhin `xact_commit` gemessen |
| 8 | — | `IntegrationTestBase` hat Hilfsmethoden bekommen (senden, zählen, warten) | Der Listener- und der Ausfalltest brauchen sie beide |
| 9 | Test zuerst rot | Test war schon **vor** dem `MessageWriter` grün | Spring AMQP gibt den Stapel bei einer Ausnahme per NACK mit requeue zurück, der Pool bremst jeden Versuch auf 30 s. Behalten habe ich den `MessageWriter` trotzdem, Begründung und Messung in Spezifikation 3.5: 24 s statt 52 s, 0 statt 2 Ausnahmen mit Stacktrace |
| 10 | — | Der erste `docker compose up --build` scheiterte | Vorübergehender Download-Fehler von Maven Central im Build des `chat-service`. Der zweite Build lief ohne Änderung durch |

## Nachtrag: Ergebnis der Abnahme (Task 11)

Gemessen am 02.10.2026 in einem frischen Klon des Branches, `.env` aus `.env.example`,
alle Szenarien nacheinander auf demselben Stack:

| Nr | Gemessen | Erwartet | Bestanden |
|---|---|---|---|
| S1 | `chat-service` 14 Tests, `batch-writer` 19 Tests, `BUILD SUCCESS` | ein Lauf, alles grün | ja |
| S2 | `rabbitmq`, `chat-service`, `postgres`, `batch-writer` laufen; 0 Port-Mappings | alle laufen, kein Port | ja |
| S3 | 1000 × `202`; 1000 Zeilen beim ersten Nachsehen; `chat.persist` 0 | ≤ 60 s, Queue leer | ja |
| S4 | 1000 Zeilen nach 7 s; `xact_commit` 99 → 133, also 34 (einschliesslich der eigenen Zählabfragen) | nichts verloren, ≤ 100 | ja |
| S5 | 1 Zeile; `chat.dlq` 0 | 1 Zeile, DLQ leer | ja |
| S6 | 2 Verbraucher an `chat.persist`; 1000 Zeilen, 1000 verschiedene Inhalte; Stapel im Log von `batch-writer-1` und `batch-writer-2` | alle da, keine doppelt | ja |
| S7 | 300 Zeilen 4 s nach dem Neustart von Postgres; beide `batch-writer` laufen, `RestartCount` 0; `chat.dlq` 0 | ≤ 90 s, ohne Neustart | ja |
| S8 | 0 Treffer für Streams; 0 Klassen oder Methoden ohne Kommentar; `.env` nicht im Repo | Regeln aus CLAUDE.md | ja |

Kein Szenario hat eine Korrektur verlangt.

---

## Task 12: Nachrichten, die PostgreSQL ablehnen würde, gehen nach chat.dlq

**Warum nach der Abnahme:** Beim Durchlesen des eigenen Codes aufgefallen. Die Spezifikation
behauptete in 3.7, jede gültig gelesene Nachricht passe in die Tabelle. Für Texte mit dem
Nullzeichen und für Zeitstempel ausserhalb des Bereichs von `timestamptz` stimmte das nicht.
Ein Test hat es bestätigt: Eine solche Nachricht blockiert die Instanz für immer, weil der
`MessageWriter` die Ablehnung wie einen Datenbankausfall behandelt. Keines der Szenarien
prüft das, deshalb hat die Abnahme es nicht gezeigt.

**Dateien:** Ändern: `MessageReader.java`, `MessageReaderTest.java`,
`PersistListenerIntegrationTest.java`

- [x] **Test** `PersistListenerIntegrationTest.movesMessageTheDatabaseWouldRefuseToDeadLetterQueue`:
      eine Nachricht mit Nullzeichen und eine gültige; die gültige ist gespeichert, die andere
      in `chat.dlq`, `chat.persist` leer. Vor der Korrektur rot: die gültige Nachricht kommt
      nie an.
- [x] **Tests** `MessageReaderTest.rejectsTextWithNullCharacter`,
      `rejectsSentAtOutsideYearsOneToNineThousandNineHundredNinetyNine`.
- [x] `MessageReader` prüft nach den Pflichtfeldern: kein Nullzeichen in den drei Texten,
      `sentAt` in den Jahren 1 bis 9999.
- [x] **Commit:** `fix: Nachrichten, die PostgreSQL ablehnen würde, blockieren den Stapel nicht mehr`

## Task 13: Test für unbekannte JSON-Felder

**Warum:** Spezifikation 2.2 verspricht, dass zusätzliche Felder den Leser nicht brechen. Das
war nur eine Eigenschaft des ObjectMappers von Spring Boot, aber nirgends geprüft.

**Dateien:** Ändern: `MessageReaderTest.java`

- [x] **Test** `MessageReaderTest.ignoresUnknownFields`: ein Körper mit einem zusätzlichen Feld
      wird gelesen.
- [x] **Commit:** `test: unbekannte JSON-Felder brechen den Leser nicht`

## Nachtrag: Abnahme nach Task 12 und 13

Wiederholt in einem frischen Klon nach dem Commit von Task 13, `.env` aus `.env.example`:
S1 mit `chat-service` 14 und `batch-writer` 23 Tests grün; S2 0 Port-Mappings; S3 1000 Zeilen
nach 4 s; S4 1000 Zeilen, 31 Transaktionen; S5 1 Zeile, `chat.dlq` 0; S6 2 Verbraucher,
1000 verschiedene Zeilen, beide Instanzen schreiben; S7 300 Zeilen 17 s nach dem Neustart von
Postgres, `RestartCount` 0; S8 ohne Befund. Alle acht bestanden.

Der Klassenkommentar des `MessageReader` nennt jetzt auch die neue Prüfung.
