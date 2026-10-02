# batch-writer — Spezifikation

**Modul M321 · Klasse IT3c · Bewertung 1 · Stand 02.10.2026**

Grundlage: [`PLANUNG.md`](../PLANUNG.md), Abschnitte 3.4 bis 3.7 und Schritt 4 der
Umsetzungsreihenfolge. Diese Spezifikation ist so geschrieben, dass man den Dienst allein
daraus bauen kann.

---

## 1. Zweck und Abgrenzung

### Was der Dienst tut

Der `batch-writer` ist der **einzige Schreiber** in die Tabelle `message`. Er holt die
Nachrichten aus der Queue `chat.persist`, sammelt sie zu Stapeln und schreibt jeden Stapel
in **einer** Datenbank-Transaktion nach PostgreSQL. Erst nach dem COMMIT bestätigt er
RabbitMQ den Empfang (ACK).

Warum es ihn gibt: PLANUNG.md rechnet mit 100'000 Nachrichten pro Minute, also 1'667 pro
Sekunde. Einzeln geschrieben wären das 1'667 Transaktionen pro Sekunde. Mit Stapeln von
500 Nachrichten sind es rund 3 — die Datenbank muss 500-mal weniger COMMITs leisten.

### Was der Dienst bewusst nicht tut

| Nicht Teil des Dienstes | Warum |
|---|---|
| Chat-Historie lesen | Das ist der Lesepfad des `chat-service`, nicht Teil dieser Aufgabe |
| Räume und Mitgliedschaften (`room`, `room_member`) | Nicht Teil dieser Aufgabe. Deshalb auch **kein** Fremdschlüssel von `message.room_id` auf `room` (siehe 4.2) |
| Keycloak, Token prüfen | Der Dienst hat keine Schnittstelle nach aussen, nur die Queue |
| HTTP-Schnittstelle | Der Dienst hört nur auf RabbitMQ. Er veröffentlicht keinen Port |
| Nachrichten verändern | Was in der Queue liegt, wird unverändert gespeichert. ID und Zeitstempel hat der `chat-service` bereits vergeben |
| Exactly-once | Wir garantieren at-least-once und machen Duplikate über den Primärschlüssel harmlos (siehe 3.3) |

---

## 2. Vertrag: was auf der Queue ankommt

### 2.1 Die Queue

| Eigenschaft | Wert |
|---|---|
| Name | `chat.persist` |
| durable | ja — die Queue überlebt einen Neustart von RabbitMQ |
| Argument `x-dead-letter-exchange` | `""` (der Standard-Exchange) |
| Argument `x-dead-letter-routing-key` | `chat.dlq` |
| Dead-Letter-Queue | `chat.dlq`, durable, ohne Argumente |

Der `batch-writer` deklariert beide Queues **selbst und mit genau diesen Argumenten**. Grund:
Er darf nicht davon abhängen, dass der `chat-service` zuerst gestartet ist, und im Test gibt
es gar keinen `chat-service`. Weichen die Argumente ab, lehnt RabbitMQ die Deklaration mit
`PRECONDITION_FAILED` ab — die Argumente sind also Teil des Vertrags.

### 2.2 Die Nachricht

So schreibt der `chat-service` eine Nachricht in `chat.persist`:

| Teil | Wert | Für den batch-writer |
|---|---|---|
| Exchange / Routing-Key | `""` / `chat.persist` | — |
| `content_type` | `application/json` | wird nicht ausgewertet |
| `content_encoding` | `UTF-8` | der Körper wird immer als UTF-8 gelesen |
| `delivery_mode` | `2` (persistent) | Nachricht überlebt einen Broker-Neustart |
| Header `__TypeId__` | `ch.benedict.m321.chatservice.dto.ChatMessage` | **wird ignoriert** (siehe unten) |
| Körper | JSON-Objekt, siehe Tabelle | wird gelesen |

| JSON-Feld | Typ im JSON | Bedeutung | Pflicht |
|---|---|---|---|
| `id` | String, UUID | vom `chat-service` vergeben, eindeutig | ja |
| `roomId` | String, UUID | Raum der Nachricht | ja |
| `senderId` | String | `sub` des Absenders | ja |
| `senderName` | String | Anzeigename | ja |
| `content` | String | Text der Nachricht | ja |
| `sentAt` | String, ISO-8601 in UTC mit bis zu 9 Nachkommastellen | Server-Zeitstempel | ja |

Unbekannte zusätzliche Felder werden ignoriert, damit der `chat-service` später Felder
ergänzen kann, ohne den `batch-writer` zu brechen.

**Warum `__TypeId__` ignoriert wird:** Der Header enthält einen Klassennamen aus dem
`chat-service`. Diese Klasse gibt es im `batch-writer` nicht, und es soll sie auch nicht
geben — der Vertrag ist das JSON, nicht eine gemeinsame Java-Klasse. Ausserdem kommen auch
Nachrichten ohne diesen Header an: Szenario S5 legt Nachrichten direkt in die Queue, nur
mit `content_type: application/json`. Der `batch-writer` liest deshalb immer den rohen
Körper und wandelt ihn selbst in seine eigene `ChatMessage`.

### 2.3 Woher ich das weiss

1. **Aus dem Code des `chat-service`:**
   - [`MessagePublisher.java`](../chat-service/src/main/java/ch/benedict/m321/chatservice/service/MessagePublisher.java)
     schickt mit `rabbitTemplate.convertAndSend(QueueNames.PERSIST_QUEUE, message)` über den
     Standard-Exchange in `chat.persist`.
   - [`ChatMessage.java`](../chat-service/src/main/java/ch/benedict/m321/chatservice/dto/ChatMessage.java)
     legt die sechs Felder und ihre Java-Typen fest (`UUID`, `UUID`, `String`, `String`,
     `String`, `Instant`).
   - [`RabbitConfig.java`](../chat-service/src/main/java/ch/benedict/m321/chatservice/config/RabbitConfig.java)
     legt `chat.persist` mit den Dead-Letter-Argumenten an und verwendet den
     `Jackson2JsonMessageConverter` mit dem ObjectMapper von Spring Boot. Der schreibt
     `Instant` als ISO-8601-Text und setzt den Header `__TypeId__`.
2. **Gemessen am laufenden Stack** (02.10.2026), nach einem `POST /messages`:

   ```bash
   docker compose exec -T rabbitmq sh -c 'rabbitmqadmin -u "$RABBITMQ_DEFAULT_USER" -p "$RABBITMQ_DEFAULT_PASS" -f raw_json get queue=chat.persist ackmode=ack_requeue_true count=1'
   ```

   Ergebnis (gekürzt):

   ```json
   {"routing_key": "chat.persist",
    "properties": {"priority": 0, "delivery_mode": 2,
                   "headers": {"__TypeId__": "ch.benedict.m321.chatservice.dto.ChatMessage"},
                   "content_encoding": "UTF-8", "content_type": "application/json"},
    "payload": "{\"id\":\"4a823a82-efc9-413b-b98e-b5414d6b9401\",\"roomId\":\"11111111-1111-1111-1111-111111111111\",\"senderId\":\"anna\",\"senderName\":\"Anna\",\"content\":\"Vertragsbeleg\",\"sentAt\":\"2026-10-02T09:53:41.916405321Z\"}"}
   ```

   Die Queue-Argumente stammen aus `rabbitmqctl list_queues name durable arguments`:
   `chat.persist true [{"x-dead-letter-exchange",[]},{"x-dead-letter-routing-key","chat.dlq"}]`.
3. **Eine direkt eingelegte Nachricht wie in S5** hat dagegen nur
   `"properties":{"content_type":"application/json"}` — kein `__TypeId__`, kein
   `delivery_mode`, kein `content_encoding`. Ebenfalls am laufenden Stack geprüft.

### 2.4 Was der Dienst schreibt

- In die Tabelle `message` (Abschnitt 4), eine Zeile pro Nachrichten-ID.
- In die Queue `chat.dlq` nur Nachrichten, die **in sich kaputt** sind (Abschnitt 3.7). Die
  Nachricht geht unverändert dorthin, ergänzt um den Header `x-rejected-reason` mit dem Grund.

---

## 3. Verhalten

### 3.1 Normalfall

1. **Empfangen.** Pro Instanz läuft **ein** Verbraucher an `chat.persist` mit
   `prefetch = 500`. RabbitMQ schickt also höchstens 500 unbestätigte Nachrichten an eine
   Instanz.
2. **Sammeln.** Die Nachrichten werden zu einem Stapel gesammelt, bis **500 Stück** beisammen
   sind oder **200 ms** lang keine weitere kam. Dann geht der Stapel an den Dienst.
3. **Lesen und prüfen.** Jede Nachricht wird aus dem rohen Körper (UTF-8, JSON) in eine
   `ChatMessage` gewandelt. Fehlt ein Pflichtfeld oder ist der Körper kein gültiges JSON,
   geht diese eine Nachricht nach `chat.dlq` (3.7). Der Rest des Stapels läuft weiter.
4. **Schreiben.** Alle gültigen Nachrichten des Stapels gehen in **einer** Transaktion als
   JDBC-Batch in die Tabelle:
   `INSERT INTO message (...) VALUES (...) ON CONFLICT (id) DO NOTHING`.
5. **Bestätigen.** Erst wenn das COMMIT zurück ist, bestätigt der Dienst **alle** Nachrichten
   des Stapels bei RabbitMQ. Damit verschwinden sie aus der Queue.
6. **Protokollieren.** Eine Logzeile pro Stapel: wie viele empfangen, neu gespeichert, als
   Duplikat übersprungen und nach `chat.dlq` gelegt wurden.

Begründung: Das ist at-least-once. Bestätigt wird erst, was sicher in der Datenbank steht.
Stürzt der Dienst zwischen COMMIT und ACK ab, kommt der Stapel noch einmal — das ist der
einzige Weg zu Duplikaten, und den fängt 3.3 ab.

### 3.2 batch-writer gestoppt (Szenario S4)

Läuft kein `batch-writer`, bleiben die Nachrichten in `chat.persist` liegen. Die Queue ist
durable und die Nachrichten des `chat-service` sind persistent, es geht also nichts verloren.

Startet der Dienst wieder, liefert RabbitMQ sofort 500 Nachrichten (prefetch), der erste
Stapel ist voll und wird geschrieben, dann der nächste. 1000 wartende Nachrichten ergeben
**2 Schreib-Transaktionen**. Beim Start kommen höchstens 2 weitere dazu, für
`CREATE TABLE IF NOT EXISTS` und `CREATE INDEX IF NOT EXISTS` (4.3). Die Obergrenze von
100 Transaktionen wird damit weit unterschritten.

### 3.3 Dieselbe Nachricht zweimal (Szenario S5)

`message.id` ist Primärschlüssel. Das INSERT lautet `ON CONFLICT (id) DO NOTHING`. Kommt
eine ID ein zweites Mal — im selben Stapel oder in einem späteren —, fügt PostgreSQL die
zweite Zeile nicht ein und meldet **keinen Fehler**. Ohne Fehler gibt es keinen Grund, die
Nachricht abzulehnen: sie wird bestätigt und landet **nicht** in `chat.dlq`. Im Log erscheint
sie als Duplikat.

Weil der Dienst den `__TypeId__`-Header ignoriert (2.2), wird eine Nachricht, die nur mit
`content_type: application/json` eingelegt wurde, genauso gelesen wie eine vom
`chat-service`.

Begründung: Duplikate sind bei at-least-once keine Ausnahme, sondern erwartet. Sie dürfen
deshalb nicht als Fehler behandelt werden.

### 3.4 Zwei Instanzen (Szenario S6)

`docker compose up -d --scale batch-writer=2` startet eine zweite Instanz. Beide hängen als
Verbraucher an **derselben** Queue `chat.persist` (Competing Consumers). RabbitMQ gibt jede
Nachricht genau einer Instanz; die beiden stören sich nicht, weil keine Nachricht zwei
Besitzer hat. Stirbt eine Instanz mit unbestätigten Nachrichten, gibt RabbitMQ diese der
anderen — mögliche Duplikate fängt der Primärschlüssel ab (3.3).

Einzige gemeinsame Stelle ist das Anlegen der Tabelle beim Start. Starten beide gleichzeitig
auf einer leeren Datenbank, kann eine Instanz das `CREATE TABLE IF NOT EXISTS` verlieren. Der
Dienst ignoriert Fehler beim Anlegen des Schemas deshalb und läuft weiter — die Tabelle hat
dann die andere Instanz angelegt.

### 3.5 Datenbank weg (Szenario S7)

Ist PostgreSQL nicht erreichbar, schlägt das Schreiben des Stapels fehl. Dann gilt:

1. Der Stapel wird **nicht** bestätigt. Seine Nachrichten bleiben in RabbitMQ als
   „unbestätigt" bei dieser Instanz.
2. Der Dienst wartet und versucht **denselben Stapel** erneut: zuerst nach 1 s, dann
   jeweils doppelt so lange, höchstens 10 s.
3. Er wartet beim Holen einer Verbindung höchstens 5 s (Connection-Timeout), damit jeder
   Versuch schnell scheitert und der nächste bald folgt.
4. Sobald PostgreSQL wieder antwortet, baut der Pool eine neue Verbindung auf, der Stapel
   wird geschrieben und bestätigt. Danach folgen die nächsten Stapel.
5. Der Dienst stürzt dabei **nicht** ab und muss nicht neu gestartet werden. Keine dieser
   Nachrichten geht nach `chat.dlq`.

Begründung:

- Ein Datenbankausfall ist ein Fehler **der Umgebung**, nicht der Nachricht. Die Nachricht
  in die Dead-Letter-Queue zu legen wäre falsch: dort holt sie niemand mehr ab.
- PLANUNG.md 3.6 sieht „NACK mit requeue" vor. Davon weiche ich bewusst ab: Ein NACK
  legt den Stapel sofort zurück in die Queue, er kommt sofort wieder, scheitert sofort
  wieder — eine Endlosschleife unter Volllast gegen Broker und Datenbank. Den Stapel zu
  behalten und mit wachsender Pause neu zu versuchen, erreicht dasselbe (nichts geht
  verloren) ohne diese Schleife.
- Die Pause ist nach oben begrenzt (10 s), damit der Dienst nach einem Ausfall von 15 s
  spätestens rund 15 s später wieder schreibt.

Bei einem Ausfall von 15 s wie in S7 sind die Nachrichten damit nach etwa 30 s in der
Tabelle, die Obergrenze ist 90 s.

### 3.6 Absturz mitten im Stapel

Stirbt der Dienst, bevor er bestätigt hat, schliesst RabbitMQ den Kanal und gibt alle
unbestätigten Nachrichten wieder frei. Die nächste Instanz liest sie erneut. War der Stapel
bereits geschrieben, sind es Duplikate und werden übersprungen (3.3). War er noch nicht
geschrieben, hat die Transaktion nichts hinterlassen — PostgreSQL schreibt einen Stapel ganz
oder gar nicht.

Wird der Dienst geordnet gestoppt, während er auf die Datenbank wartet (3.5), bricht er das
Warten ab. Der Stapel bleibt unbestätigt und geht an RabbitMQ zurück.

### 3.7 Kaputte Nachricht

Ist der Körper kein gültiges JSON, fehlt ein Pflichtfeld oder hat ein Feld den falschen Typ
(zum Beispiel keine UUID in `id`), dann legt der Dienst **genau diese Nachricht** sofort nach
`chat.dlq` und behandelt den Rest des Stapels normal.

Begründung: Dieser Fehler liegt in der Nachricht selbst. Ein zweiter oder dritter Versuch
ergibt dasselbe Ergebnis. PLANUNG.md nennt „nach 3 fehlgeschlagenen Versuchen" — für
Fehler, die sich durch Warten nicht bessern, wären die zwei zusätzlichen Versuche
verschwendet. Wichtiger noch: Bliebe eine kaputte Nachricht im Stapel, würde sie bei jedem
Versuch den ganzen Stapel scheitern lassen und die Queue für immer blockieren.

Damit ein Stapel nur noch an der Datenbank scheitern kann und nicht am Inhalt, ist die
Tabelle so gebaut, dass jede gültig gelesene Nachricht hineinpasst: Texte ohne
Längenbegrenzung, kein Fremdschlüssel (4.2).

### 3.8 RabbitMQ weg

Nicht Teil der Szenarien, der Vollständigkeit halber: Bricht die Verbindung zum Broker ab,
baut Spring AMQP sie selbständig wieder auf. Unbestätigte Nachrichten gibt RabbitMQ beim
Verbindungsabbruch frei; sie kommen nach dem Wiederaufbau erneut (3.6).

---

## 4. Datenmodell und Konfiguration

### 4.1 Tabelle `message`

Spalten wie in PLANUNG.md 3.7:

```sql
CREATE TABLE IF NOT EXISTS message (
    id          UUID        PRIMARY KEY,
    room_id     UUID        NOT NULL,
    sender_id   VARCHAR     NOT NULL,
    sender_name VARCHAR     NOT NULL,
    content     TEXT        NOT NULL,
    sent_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS message_room_id_sent_at_idx
    ON message (room_id, sent_at DESC);
```

| JSON-Feld | Spalte | Umwandlung |
|---|---|---|
| `id` | `id` | UUID |
| `roomId` | `room_id` | UUID |
| `senderId` | `sender_id` | Text |
| `senderName` | `sender_name` | Text |
| `content` | `content` | Text |
| `sentAt` | `sent_at` | `Instant` → `timestamptz`. PostgreSQL speichert Mikrosekunden, die Nanosekunden des `chat-service` werden abgeschnitten |

### 4.2 Begründete Entscheide

| Entscheid | Begründung |
|---|---|
| `id` ist Primärschlüssel | Er ist der Schutz gegen Duplikate (3.3). Die ID vergibt der `chat-service`, nicht die Datenbank |
| `ON CONFLICT (id) DO NOTHING` statt Fehler | Ein Duplikat ist bei at-least-once erwartet und kein Fehler |
| Kein Fremdschlüssel auf `room` | Räume sind nicht Teil der Aufgabe, eine Tabelle `room` gibt es nicht. Ein Fremdschlüssel würde jede Nachricht ablehnen, deren Raum nicht existiert — und damit den Stapel blockieren |
| `VARCHAR` ohne Länge | Eine feste Länge würde einen zu langen Namen ablehnen und damit den ganzen Stapel scheitern lassen. In PostgreSQL ist `VARCHAR` ohne Länge genauso schnell wie `TEXT` |
| `TIMESTAMPTZ` | Ein eindeutiger Zeitpunkt, unabhängig von der Zeitzone des Servers |
| Index `(room_id, sent_at DESC)` | Die einzige geplante Leseabfrage ist „die letzten 50 Nachrichten eines Raums" (PLANUNG.md 3.7). Der Index kostet beim Schreiben etwas, lohnt sich aber, sobald gelesen wird |

### 4.3 Wo das Schema entsteht

Das Schema steht in `batch-writer/src/main/resources/schema.sql`. Spring Boot führt die Datei
bei **jedem** Start des `batch-writer` aus (`spring.sql.init.mode=always`). Alle Befehle sind
mit `IF NOT EXISTS` geschrieben, ein zweiter Start ändert also nichts. Fehler beim Anlegen
werden ignoriert (`spring.sql.init.continue-on-error=true`, Grund in 3.4).

Begründung:

- Der einzige Schreiber besitzt seine Tabelle. Wer den `batch-writer` startet, hat danach
  auch die Tabelle.
- Die Tests verwenden **dieselbe** Datei. Was im Test grün ist, entspricht dem Schema im
  Betrieb.
- Verworfen: ein Init-Skript im Postgres-Container. Es läuft nur auf einem leeren
  Datenträger — ist das Volume schon da, fehlt die Tabelle.
- Verworfen: Flyway. Für eine einzige Tabelle ohne Änderungsgeschichte ist das ein
  Werkzeug mehr zum Erklären. Sobald sich das Schema ändert, wäre es der nächste Schritt.

### 4.4 Umgebungsvariablen

| Variable | Wo gesetzt | Vorgabe ausserhalb von Docker | Bedeutung |
|---|---|---|---|
| `RABBITMQ_HOST` | `docker-compose.yml`: `rabbitmq` | `localhost` | Hostname des Brokers |
| `RABBITMQ_USER` | `.env` | `guest` | Benutzer am Broker |
| `RABBITMQ_PASSWORD` | `.env` | `guest` | Passwort am Broker |
| `POSTGRES_HOST` | `docker-compose.yml`: `postgres` | `localhost` | Hostname der Datenbank |
| `POSTGRES_DB` | `.env` | `chat` | Name der Datenbank |
| `POSTGRES_USER` | `.env` | `chat` | Benutzer der Datenbank |
| `POSTGRES_PASSWORD` | `.env` | `chat` | Passwort der Datenbank |

`POSTGRES_DB`, `POSTGRES_USER` und `POSTGRES_PASSWORD` stehen mit Beispielwerten in
`.env.example`. Der Container `postgres` legt mit denselben drei Variablen beim ersten Start
Datenbank und Benutzer an. Port 5432 (Postgres) und 5672 (RabbitMQ) sind fest.

### 4.5 Feste Werte im Code

| Wert | Grösse | Begründung |
|---|---|---|
| Stapelgrösse | 500 | PLANUNG.md 3.6 |
| Wartezeit für einen unvollständigen Stapel | 200 ms | PLANUNG.md 3.6 |
| prefetch | 500 | Mindestens so gross wie ein Stapel, sonst kann nie ein voller Stapel entstehen |
| Verbraucher pro Instanz | 1 | Skaliert wird über Instanzen (`--scale`), nicht über Threads |
| Erste Pause nach einem Datenbankfehler | 1 s | 3.5 |
| Längste Pause | 10 s | 3.5 |
| Connection-Timeout des Pools | 5 s | 3.5 |

### 4.6 Docker

| Dienst | Image | Ports | Abhängigkeiten |
|---|---|---|---|
| `postgres` | `postgres:16` | keine | — · Healthcheck `pg_isready` · Volume `chat-db-data` |
| `batch-writer` | eigenes `batch-writer/Dockerfile` | keine | `rabbitmq` und `postgres` healthy · `restart: unless-stopped` |

Beide im Netz `chat-net`. `restart: unless-stopped` ist nur ein Sicherheitsnetz: Der Dienst
soll laut 3.5 gar nicht abstürzen. Ein von Hand gestoppter Dienst (S4) bleibt gestoppt.

Zwei Anpassungen an Bestehendem, ohne die die Szenarien nicht bestehen:

- **`chat-service/Dockerfile`** baut bisher mit `-pl chat-service -am`. Das liest das
  Eltern-POM samt Modulliste und scheitert, sobald dort `batch-writer` steht, dessen Ordner
  im Build-Kontext des `chat-service` fehlt. Neu: `mvn -f chat-service/pom.xml`.
- **Healthcheck von `rabbitmq`**: `rabbitmq-diagnostics ping` ist schon grün, bevor der
  AMQP-Port Verbindungen annimmt. Beim Vertragsbeleg in 2.3 hat der erste `POST /messages`
  direkt nach dem Start deshalb `503` geliefert. Neu: `check_port_connectivity`, damit
  `chat-service` und `batch-writer` erst starten, wenn der Broker wirklich bereit ist.

---

## 5. Abnahmekriterien

Alle Befehle laufen im Wurzelverzeichnis des Forks in einer Bash (unter Windows: Git Bash).
Die Szenarien laufen in dieser Reihenfolge auf demselben Stack. Damit jedes Szenario seine
eigenen Nachrichten zählen kann, schickt es sie mit eigener `senderId` (`s3`, `s4`, …).

**Hilfsbefehl „N Nachrichten senden"** — der `chat-service` hat keinen Port nach aussen,
gesendet wird deshalb aus einem Container im Netz `chat-net`. `N` und `SENDER` ersetzen:

```bash
docker run --rm --network chat-net curlimages/curl:8.11.1 sh -c '
i=1
while [ $i -le N ]; do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST http://chat-service:8080/messages \
    -H "Content-Type: application/json" \
    -d "{\"roomId\":\"11111111-1111-1111-1111-111111111111\",\"senderId\":\"SENDER\",\"senderName\":\"SENDER\",\"content\":\"Nachricht $i\"}"
  i=$((i+1))
done' | sort | uniq -c
```

Erwartet: genau eine Zeile `N 202`.

**Hilfsbefehl „Zeilen zählen":**

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT count(*) FROM message WHERE sender_id = '\''SENDER'\''"'
```

**Hilfsbefehl „Queues ansehen":**

```bash
docker compose exec -T rabbitmq rabbitmqctl list_queues name messages consumers
```

| Nr | Kriterium | Messung |
|---|---|---|
| S1 | `mvn clean test` endet mit `BUILD SUCCESS`, `Failures: 0, Errors: 0` | `mvn clean test` |
| S2 | Alle Dienste `running`, keiner hat ein Port-Mapping (`->`) | siehe unten |
| S3 | 1000 × `202`; nach ≤ 60 s `count = 1000` für `s3`, `chat.persist` = 0 | Senden mit `N=1000 SENDER=s3`, dann Zeilen zählen und Queues ansehen |
| S4 | `count = 1000` für `s4`; Differenz von `xact_commit` ≤ 100 | siehe unten |
| S5 | `count = 1` für `s5`; `chat.dlq` = 0 | siehe unten |
| S6 | `chat.persist` hat `consumers = 2`; `count = 1000` und 1000 verschiedene Inhalte für `s6` | siehe unten |
| S7 | Nach ≤ 90 s `count = 300` für `s7`; `batch-writer` läuft, `RestartCount = 0` | siehe unten |
| S8 | keine Streams, über jeder Klasse und Methode ein Kommentar, `.env` nicht im Repo | siehe unten |

**S2:**

```bash
cp .env.example .env
docker compose up -d --build
docker compose ps --format '{{.Service}} {{.State}} {{.Ports}}'
docker compose ps --format '{{.Ports}}' | grep -c -- '->'
```

Erwartet: `rabbitmq`, `chat-service`, `postgres`, `batch-writer` mit `running`; der letzte
Befehl zählt `0` Port-Mappings.

**S4:**

```bash
docker compose stop batch-writer
# Senden mit N=1000 SENDER=s4
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT xact_commit FROM pg_stat_database WHERE datname = current_database()"'
docker compose start batch-writer
# warten, bis Zeilen zählen für s4 = 1000 ergibt, dann erneut xact_commit lesen
```

Erwartet: `count = 1000`, die beiden `xact_commit`-Werte unterscheiden sich um höchstens
100. Jede `psql`-Abfrage zählt selbst als eine Transaktion mit.

**S5:**

```bash
docker compose exec -T rabbitmq sh -c '
for i in 1 2; do
  rabbitmqadmin -u "$RABBITMQ_DEFAULT_USER" -p "$RABBITMQ_DEFAULT_PASS" publish \
    exchange=amq.default routing_key=chat.persist \
    properties="{\"content_type\":\"application/json\"}" \
    payload="{\"id\":\"55555555-5555-5555-5555-555555555555\",\"roomId\":\"11111111-1111-1111-1111-111111111111\",\"senderId\":\"s5\",\"senderName\":\"s5\",\"content\":\"Duplikat\",\"sentAt\":\"2026-10-02T10:00:00Z\"}"
done'
# einige Sekunden warten, dann Zeilen zählen für s5 und Queues ansehen
```

Erwartet: `count = 1`, `chat.dlq 0`.

**S6:**

```bash
docker compose up -d --scale batch-writer=2
docker compose exec -T rabbitmq rabbitmqctl list_queues name consumers
# Senden mit N=1000 SENDER=s6
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT count(*), count(DISTINCT content) FROM message WHERE sender_id = '\''s6'\''"'
docker compose logs batch-writer | grep "Batch of"
```

Erwartet: `chat.persist 2`; danach `1000|1000`; im Log Stapel von **beiden** Instanzen
(`batch-writer-1` und `batch-writer-2`).

**S7:**

```bash
docker compose stop postgres
# Senden mit N=300 SENDER=s7
sleep 15
docker compose start postgres
# alle paar Sekunden Zeilen zählen für s7, höchstens 90 s lang
docker compose ps batch-writer
docker inspect --format '{{.RestartCount}}' $(docker compose ps -q batch-writer)
```

Erwartet: `count = 300` nach ≤ 90 s, alle `batch-writer` laufen, `RestartCount` ist `0`.

**S8:**

```bash
grep -rnE "\.stream\(|Stream\.|Collectors|\.forEach\(" batch-writer/src
git ls-files | grep -x ".env"
```

Erwartet: beide Befehle ohne Ausgabe. Kommentare: jede `class`/`record`-Zeile und jede
Methode in `batch-writer/src` hat direkt darüber (Annotationen dürfen dazwischen stehen)
einen Kommentar, der mit `*/` endet. Geprüft mit:

```bash
awk 'FNR==1{prev=""} /^[[:space:]]*@/{next} /^[[:space:]]*(public|protected|private|static|void|class|record|[A-Za-z<>\[\]]+[[:space:]]+[a-zA-Z]+\()/ && /(\(|class |record )/ && !/^[[:space:]]*(return|if|for|while|new|throw)/ && !/;[[:space:]]*$/ { if (prev !~ /\*\/[[:space:]]*$/) print FILENAME ":" FNR ": " $0 } /[^[:space:]]/{prev=$0}' $(find batch-writer/src -name "*.java")
```

Erwartet: keine Ausgabe.
