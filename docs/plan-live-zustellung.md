# Live-Zustellung — Umsetzungsplan

**Ziel:** Jede Instanz des chat-service bekommt eine eigene Queue am Fanout-Exchange
`chat.delivery` und schiebt die Nachrichten per Server-Sent Events an die offenen
Verbindungen ihres Raums. Eine Neuverbindung mit `Last-Event-ID` bekommt die verpassten
Nachrichten aus einem Ringpuffer nachgeliefert.

**Spezifikation:** [`spec-live-zustellung.md`](spec-live-zustellung.md). Jeder Schritt nennt
den Abschnitt, den er umsetzt.

**Tech-Stack:** Java 21, Spring Boot 3.5.16, Spring AMQP, Spring MVC mit `SseEmitter`,
RabbitMQ 3.13, JUnit 5, Testcontainers.

## Globale Vorgaben

Gelten für **jeden** Schritt:

- Regeln aus [`CLAUDE.md`](../CLAUDE.md): Code auf Englisch, Kommentare und Commits auf
  Deutsch, ein Ergebnis pro Zeile, `for` statt Stream, Lombok nur für `@Slf4j` und
  `@RequiredArgsConstructor`, Datenklassen als `record`.
- Über jeder Klasse und jeder Methode ein Kommentar, auch in den Tests (S8).
- Kein `ports:` und kein `container_name` beim chat-service (S2, S3).
- `chat.persist`, `chat.dlq` und der Exchange `chat.delivery` bleiben unverändert (Spezifikation 4).
- Gearbeitet wird auf dem Branch `live-zustellung`. Pro Schritt ein Commit.
- Ablauf jedes Schritts: KI-Auftrag geben → Ergebnis lesen → Test laufen lassen → prüfen und
  korrigieren → Commit → Spalte «Geprüft und korrigiert» ausfüllen.

## Abgrenzung

Nicht in diesem Plan (Spezifikation 1): Keycloak, web-gateway, React-Oberfläche,
JavaFX-Client, Räume anlegen, Mitglieder einladen, Historie beim ersten Verbinden.

## Dateien

```
chat-service/src/main/java/ch/benedict/m321/chatservice/
├── config/QueueNames.java            # + Präfix chat.delivery. und die Grenzen
├── config/RabbitConfig.java          # + Instanz-Queue, Bindung, Listener-Fabrik
├── controller/StreamController.java  # NEU: GET /stream
├── listener/DeliveryListener.java    # NEU: liest die Instanz-Queue
└── service/LiveDelivery.java         # NEU: Lock, Ringpuffer, angemeldete Clients
chat-service/src/test/java/ch/benedict/m321/chatservice/
├── config/DeliveryQueueIntegrationTest.java
├── controller/StreamControllerIntegrationTest.java
├── service/LiveDeliveryTest.java
├── TwoInstancesIntegrationTest.java  # S3
└── ReconnectIntegrationTest.java     # S7
README.md                             # Tabelle «Stand»
```

`.env.example` bleibt unverändert, es gibt keine neuen Variablen (Spezifikation 4,
Umgebungsvariablen).

---

## Schritt 1: Fehlende Kommentare im bestehenden chat-service

**Ziel:** Der `awk`-Check aus S8 findet im bestehenden Code nichts mehr (heute 18 Stellen).

**Warum hier:** Zuerst, damit danach jede Meldung des Checks sicher vom neuen Code kommt.

**KI-Auftrag im Wortlaut:**

> Lies CLAUDE.md. Führe im Ordner it3c-m321 den awk-Check aus Abschnitt 5, S8 von
> docs/spec-live-zustellung.md aus. Ergänze bei jeder gemeldeten Klasse und Methode im
> chat-service einen deutschen Kommentar, der erklärt, warum sie existiert. Ändere keinen
> Code, nur Kommentare. Führe den Check danach nochmals aus und zeig mir die Ausgabe.

**Test:** Der `awk`-Check gibt nichts aus, `mvn clean test` im chat-service ist grün.

**Geprüft und korrigiert:**

---

## Schritt 2: Instanz-Queue und Listener

**Ziel:** Jede Instanz deklariert beim Start `chat.delivery.<UUID>` mit den Eigenschaften aus
Spezifikation 4 und liest sie mit genau einem Thread.

**Warum hier:** Ohne Queue kommt nichts an. Alles Weitere baut darauf auf.

**KI-Auftrag im Wortlaut:**

> Lies CLAUDE.md und Abschnitt 4 von docs/spec-live-zustellung.md. Ergänze im chat-service
> eine Queue chat.delivery.<UUID>, die UUID wird einmal beim Start erzeugt. Die Queue ist nicht
> durable, exclusive, auto-delete, hat x-max-length 950 und x-overflow drop-head und kein
> Dead-Lettering. Binde sie an den bestehenden Fanout-Exchange chat.delivery. Lies sie mit
> einem DeliveryListener mit genau einem Consumer und prefetch 50. Die Verbindung zu RabbitMQ
> muss gleich beim Start aufgebaut werden, nicht erst beim ersten POST. Ändere chat.persist,
> chat.dlq und chat.delivery nicht. Schreib einen Integrationstest mit Testcontainers, der
> Name, Eigenschaften, Bindung und Consumer-Anzahl prüft.

**Test:** `DeliveryQueueIntegrationTest`: Queue existiert mit Präfix `chat.delivery.`,
`exclusive`, `auto-delete`, nicht durable, `x-max-length` 950, `x-overflow` `drop-head`, an
`chat.delivery` gebunden, 1 Consumer.

**Geprüft und korrigiert:**

---

## Schritt 3: SSE-Endpunkt und Verteilung nach Raum

**Ziel:** `GET /stream?roomId=<UUID>` liefert `text/event-stream`. Jede Nachricht aus der
Instanz-Queue geht als `id:` + `data:` an alle Clients desselben Raums (Spezifikation 2.2,
3.1, 3.2).

**Warum hier:** Erst wenn Nachrichten ankommen, lohnt sich das Nachliefern.

**KI-Auftrag im Wortlaut:**

> Lies CLAUDE.md und die Abschnitte 2.2, 3.1 und 3.2 von docs/spec-live-zustellung.md. Baue
> einen StreamController mit GET /stream?roomId=<UUID>, der einen SseEmitter ohne Timeout
> zurückgibt. Fehlt die roomId oder ist sie keine UUID, kommt 400. Baue eine Klasse
> LiveDelivery, die die angemeldeten Clients pro Raum kennt. Der DeliveryListener gibt jede
> Nachricht an LiveDelivery, und LiveDelivery schickt sie mit id = Nachrichten-ID und
> data = JSON auf einer Zeile an alle Clients dieses Raums. Der POST darf die eigenen Clients
> nicht direkt beliefern. Eine abgebrochene Verbindung wird abgemeldet. Schreib Tests für
> 400, für eine Nachricht im eigenen Raum und dafür, dass ein anderer Raum nichts bekommt.

**Test:** `StreamControllerIntegrationTest`: 400 ohne und mit ungültiger `roomId`,
Content-Type beginnt mit `text/event-stream`, ein `POST` kommt als Ereignis im eigenen Raum
an, im anderen Raum nicht.

**Geprüft und korrigiert:**

---

## Schritt 4: Ringpuffer, Last-Event-ID und `gap`

**Ziel:** Eine Neuverbindung mit `Last-Event-ID` bekommt genau die verpassten Nachrichten
ihres Raums in Reihenfolge, danach live. Unbekannte ID ergibt `event:gap` (Spezifikation 2.2,
3.6).

**Warum hier:** Baut auf der Verteilung aus Schritt 3 auf und braucht denselben Lock.

**KI-Auftrag im Wortlaut:**

> Lies CLAUDE.md und die Abschnitte 2.2 und 3.6 von docs/spec-live-zustellung.md. Ergänze in
> LiveDelivery einen Ringpuffer für die letzten 10'000 Nachrichten. Der Listener legt jede
> Nachricht unter einem Lock in den Puffer und schickt sie an die Clients. Der StreamController
> liest den Header Last-Event-ID. Unter demselben Lock sucht LiveDelivery die ID im Puffer,
> schickt alle späteren Nachrichten dieses Raums und meldet den Client an. Ist die ID unbekannt,
> geht zuerst ein Ereignis event:gap ohne id-Zeile mit der ID in data, dann wird live geliefert.
> Schreib einen Unit-Test für LiveDelivery: Nachliefern, keine Lücke, kein Doppel, gap.

**Test:** `LiveDeliveryTest` ohne Container und `ReconnectIntegrationTest` (S7): 50
Nachrichten während der Trennung, Neuverbindung mit `Last-Event-ID` bekommt genau diese 50
in Reihenfolge, danach eine Live-Nachricht, kein `gap`.

**Geprüft und korrigiert:**

---

## Schritt 5: Zwei-Instanzen-Test (S3)

**Ziel:** Ein Test startet zwei chat-service-Kontexte am selben RabbitMQ und zeigt: Jede
Nachricht kommt bei beiden Instanzen genau einmal an, in Reihenfolge, und nur im richtigen
Raum (Spezifikation 3.2).

**Warum hier:** Erst jetzt gibt es alles, was S3 braucht.

**KI-Auftrag im Wortlaut:**

> Lies CLAUDE.md und Abschnitt 3.2 von docs/spec-live-zustellung.md. Schreib einen
> Integrationstest, der zwei Spring-Kontexte des chat-service mit zufälligem Port am selben
> RabbitMQ-Testcontainer startet. Je ein Client in Raum A an jeder Instanz, ein Client in Raum
> B. Schick abwechselnd Nachrichten an beide Instanzen. Prüfe: beide Clients in Raum A haben
> alle Nachrichten genau einmal, pro Absender in Reihenfolge, der Client in Raum B hat keine.
> Prüfe ausserdem, dass genau zwei Instanz-Queues mit je einem Consumer existieren.

**Test:** `TwoInstancesIntegrationTest` grün.

**Geprüft und korrigiert:**

---

## Schritt 6: README und Docker

**Ziel:** README-Tabelle «Stand» nachgeführt, Stack baut aus frischem Klon, chat-service lässt
sich mit `--scale` starten und sauber herunterfahren.

**Warum hier:** Am Schluss, wenn der Code steht.

**KI-Auftrag im Wortlaut:**

> Lies CLAUDE.md. Führe die README-Tabelle «Stand» nach: der chat-service liefert jetzt auch
> live per SSE aus. Prüfe in docker-compose.yml, dass der chat-service kein container_name und
> kein ports hat. Prüfe, dass ein Container mit offenen SSE-Verbindungen bei docker compose
> stop innert der Stop-Frist herunterfährt, und korrigiere es, falls nicht.

**Test:** `docker compose up -d --build --scale chat-service=2` startet, `docker compose ps`
zeigt keine Ports, `--scale chat-service=1` beendet eine Instanz.

**Geprüft und korrigiert:**

---

## Schritt 7: Abnahme S1 bis S8

**Ziel:** Alle Befehle aus Spezifikation 5 laufen in einem frischen Klon mit dem erwarteten
Ergebnis.

**Warum hier:** Erst wenn alles gebaut ist.

**KI-Auftrag im Wortlaut:**

> Führe die Abnahme aus Abschnitt 5 von docs/spec-live-zustellung.md Schritt für Schritt in
> einem frischen Klon aus, in der Reihenfolge S1 bis S8. Zeig mir zu jedem Szenario die
> Ausgabe und ob sie dem Erwarteten entspricht. Repariere nichts ohne Rückfrage.

**Test:** Jedes Szenario entspricht «Erwartet». Danach Merge auf `main`, Tag `bewertung-2`,
Push.

**Geprüft und korrigiert:**

---

## Änderungen am Plan

Änderungen nach dem Push kommen nur hier als neuer Eintrag dazu, mit Datum und Änderungsgrund.
Die Schritte oben bleiben stehen, wie sie gepusht wurden.

### Änderung 1 — 13.11.2026, beim Bauen von Schritt 3 und 4

- **Kommentarzeile `:connected` beim Verbinden.** Änderungsgrund: Spring schickt die
  Antwort-Header erst mit dem ersten Ereignis. Ohne erstes Ereignis sah der Client bis zur
  ersten Nachricht nicht einmal den Content-Type, und `curl -D -` in S2 hätte nichts gezeigt.
  Eine Zeile mit `:` ist laut SSE-Standard ein Kommentar, jeder Client überliest sie. Sie ist
  kein Ereignis, Vertrag 2.2 bleibt also gültig.
- **Einfacher Konverter für den DeliveryListener.** Änderungsgrund: Der JSON-Konverter wollte
  die Nachricht vor dem Listener in eine ChatMessage umwandeln und lehnte die Klasse ab. Der
  Listener braucht das JSON roh, damit `data:` genau dem Format in `chat.persist` entspricht.
- **Kein eigener `LiveDeliveryTest` in Schritt 4.** Änderungsgrund: Nachliefern, kein Doppel,
  Reihenfolge und `gap` prüft `ReconnectIntegrationTest` schon am echten Stream. Ein zweiter
  Test derselben Regeln ohne Server hätte nichts Neues gezeigt.
- **Schritt 6:** Beim Prüfen von S8 fand `grep "Stream\."` zwei Kommentare, in denen ein Satz
  mit dem Wort «Stream» endet. Die Kommentare wurden umformuliert, der Code blieb gleich.
