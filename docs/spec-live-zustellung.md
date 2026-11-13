# Live-Zustellung — Spezifikation

Modul M321 · Klasse IT3c · Bewertung 2

## 1. Zweck und Abgrenzung

### Was die Zustellung tut

Seit Bewertung 1 wird jede Nachricht gespeichert, aber angezeigt wird sie nirgends. Der chat-service publiziert zwar schon an den Fanout-Exchange `chat.delivery` (`MessagePublisher.java:36`), aber dort hängt keine Queue, also verwirft RabbitMQ die Kopie. Ich baue den Anzeigeweg: Jede Instanz des chat-service bindet eine eigene Queue an `chat.delivery` und schiebt die Nachrichten per Server-Sent Events an die offenen Verbindungen ihres Raums.

### Was sie bewusst nicht tut

Das PDF sagt wörtlich: «Keycloak, web-gateway, React-Oberfläche, JavaFX-Client, Räume anlegen und Mitglieder einladen. Wer davon etwas baut, bekommt dafür keine Punkte.» Warum, steht nicht ausdrücklich da. Meine Begründung: Diese Aufgabe ist nur der Anzeigeweg vom `POST` bis zum `data:` am Stream. Alles andere sind eigene Schritte der Umsetzungsreihenfolge. Geprüft wird ohnehin direkt im Netz `chat-net`, ohne Login und ohne Oberfläche.

**Historie beim ersten Verbinden:** Nein. Wer sich ohne `Last-Event-ID` verbindet, bekommt nur Nachrichten, die ab diesem Moment ankommen. Das Nachliefern aus S7 gilt nur für eine Neuverbindung, also für einen Client, der schon einmal verbunden war und eine ID mitbringt. Die Historie würde später ein eigener Lesepfad aus der Datenbank liefern. Kein Szenario verlangt sie, darum baue ich sie hier nicht.

### Abweichung von PLANUNG.md

Jetzt hängt der chat-service selbst daran. Das PDF verlangt es so: «Jede Instanz des chat-service holt sich ihre Kopie und schiebt sie per Server-Sent Events an die offenen Verbindungen.» Das web-gateway gehört ausdrücklich nicht zur Aufgabe. Technisch könnte das Gateway laut PLANUNG.md (Zeile 145–161) sehr wohl an RabbitMQ hängen: Es prüft JWTs als OAuth2 Resource Server und konsumiert `chat.delivery`, ist also «Consumer, aber kein Producer». Meine frühere Begründung «nginx kann kein AMQP» war falsch. Sie stammte aus der README-Tabelle, die dort nginx nennt und damit der PLANUNG widerspricht.

## 2. Vertrag

### 2.1 Was am Exchange ankommt

**Exchange, Typ, Publisher:** Er heisst `chat.delivery`, ist vom Typ fanout und durable. Nur der chat-service publiziert dorthin (`MessagePublisher.java:36`).

**Format:** Ja, gleich wie in `chat.persist`, weil dasselbe Objekt mit demselben JSON-Konverter verschickt wird. Die Felder sind `id`, `roomId`, `senderId`, `senderName`, `content` und `sentAt`.

### 2.2 Was am Stream hinausgeht

**Endpunkt:** `GET /stream?roomId=<UUID>`, dazu optional der Header `Last-Event-ID`.

**Content-Type:** `text/event-stream`

**Ein Ereignis:** Ein Ereignis besteht aus zwei Textzeilen und einer Leerzeile.

* In `id:` steht die `id` der Nachricht, also dieselbe UUID wie im JSON. Der Client merkt sich diese ID und schickt sie bei einer Neuverbindung als `Last-Event-ID` zurück.
* In `data:` steht die ganze Nachricht als JSON, im selben Format wie in `chat.persist`. Das JSON muss auf einer Zeile stehen, denn ein Zeilenumbruch würde es in mehrere `data:`-Zeilen zerteilen.
* Die Leerzeile sagt dem Client, dass das Ereignis fertig ist.

Eine `event:`-Zeile gibt es bei normalen Nachrichten nicht, der Client behandelt sie also als Standard-Ereignis `message`. Spring schreibt `id:` und `data:` ohne Leerzeichen nach dem Doppelpunkt, laut SSE-Standard ist beides gültig.

**Ungültige oder fehlende `roomId`:**

* Fehlt die `roomId`, kommt 400, und Spring ruft die Methode gar nicht auf.
* Ist sie keine gültige UUID (zum Beispiel `roomId=abc`), kommt ebenfalls 400, weil Spring sie nicht in eine UUID umwandeln kann.

In beiden Fällen wird kein Stream geöffnet und kein Client angemeldet. Die Antwort hat dann auch nicht den Content-Type `text/event-stream`. Eine gültige UUID für einen Raum, den es nicht gibt, ergibt dagegen 200 und einen offenen Stream, über den einfach nichts kommt. Der chat-service kennt keine Raumliste, und Räume anlegen gehört laut PDF nicht zur Aufgabe.

**Beispiel-Ereignis:**

```
id:4a823a82-efc9-413b-b98e-b5414d6b9401
data:{"id":"4a823a82-efc9-413b-b98e-b5414d6b9401","roomId":"11111111-1111-1111-1111-111111111111","senderId":"anna","senderName":"Anna","content":"Hallo","sentAt":"2026-10-02T09:53:41.916405321Z"}

```

Die letzte Zeile ist leer und schliesst das Ereignis ab.

**Das Ereignis `gap`:**

```
event:gap
data:4a823a82-efc9-413b-b98e-b5414d6b9401

```

* Es hat bewusst keine `id:`-Zeile. So bleibt die `Last-Event-ID` des Clients unverändert. Bricht die Verbindung gleich wieder ab, meldet der nächste Versuch dieselbe Lücke noch einmal. Das ist richtig, denn die Lücke ist ja noch da.
* In `data:` steht die unbekannte ID als reiner Text. Das Feld darf nicht leer sein, sonst verwirft der Browser das Ereignis.
* Ein Browser-Client empfängt es über `addEventListener("gap", …)` und nicht über `onmessage`.

### 2.3 Woher ich das weiss

Im Code:

* `MessagePublisher.java:35-36` schickt zuerst nach `chat.persist`, dann an `chat.delivery`.
* `RabbitConfig.java:48` legt `chat.delivery` als durable Fanout an.
* `RabbitConfig.java:61` setzt den JSON-Konverter für beide Wege.

Am laufenden Stack wird eine Probe-Queue an `chat.delivery` gebunden und ein `POST` geschickt. Danach wird die angekommene Nachricht mit `rabbitmqadmin get` gelesen und die Probe-Queue wieder gelöscht, damit sie S4 nicht verfälscht.

Befehle und Ausgabe vom 13.11.2026:

```
ADM='rabbitmqadmin -u "$RABBITMQ_DEFAULT_USER" -p "$RABBITMQ_DEFAULT_PASS"'
docker compose exec -T rabbitmq rabbitmqctl -q list_exchanges name type durable | grep chat
docker compose exec -T rabbitmq sh -c "$ADM declare queue name=probe.delivery durable=false && $ADM declare binding source=chat.delivery destination=probe.delivery"
docker run --rm --network chat-net curlimages/curl:8.11.1 -s -w '\n%{http_code}\n' -X POST http://chat-service:8080/messages -H 'Content-Type: application/json' -d '{"roomId":"11111111-1111-1111-1111-111111111111","senderId":"anna","senderName":"Anna","content":"Vertragsbeleg Live-Zustellung"}'
docker compose exec -T rabbitmq sh -c "$ADM get queue=probe.delivery ackmode=ack_requeue_false -f pretty_json"
docker compose exec -T rabbitmq sh -c "$ADM delete queue name=probe.delivery"
docker compose exec -T rabbitmq rabbitmqctl -q list_queues name

Ausgabe (13.11.2026):

chat.delivery	fanout	true
queue declared
binding declared
{"id":"96707b24-fb1d-4c2b-b224-edd4f0900c7e","sentAt":"2026-11-13T20:49:20.700155339Z"}
202
[
  {
    "exchange": "chat.delivery",
    "message_count": 0,
    "payload": "{\"id\":\"96707b24-fb1d-4c2b-b224-edd4f0900c7e\",\"roomId\":\"11111111-1111-1111-1111-111111111111\",\"senderId\":\"anna\",\"senderName\":\"Anna\",\"content\":\"Vertragsbeleg Live-Zustellung\",\"sentAt\":\"2026-11-13T20:49:20.700155339Z\"}",
    "payload_bytes": 215,
    "payload_encoding": "string",
    "properties": {
      "content_encoding": "UTF-8",
      "content_type": "application/json",
      "delivery_mode": 2,
      "headers": {
        "__TypeId__": "ch.benedict.m321.chatservice.dto.ChatMessage"
      },
      "priority": 0
    },
    "redelivered": false,
    "routing_key": ""
  }
]
queue deleted
name
chat.dlq
chat.persist
```

Den Exchange gab es erst nach dem ersten POST.

## 3. Verhalten

### 3.1 Normalfall

1. Der Absender schickt `POST /messages` an Instanz A und wartet auf die Antwort.
2. A vergibt ID und Zeitstempel (`MessageService`).
3. A publiziert zuerst nach `chat.persist`, dann an `chat.delivery` (`MessagePublisher.java:35-36`).
4. Danach antwortet A mit 202 (`MessageController.java:36`). Die 202 heisst nur: Die Nachricht ist über die Verbindung zu RabbitMQ abgeschickt. Ob der Broker sie wirklich angenommen hat, weiss der chat-service nicht, weil keine Publisher Confirms eingeschaltet sind. Sicher ist nur das Gegenteil: Ist RabbitMQ schon beim Senden nicht erreichbar, kommt 503 statt 202.
5. RabbitMQ legt in jede Instanz-Queue eine Kopie.
6. Der Listener jeder Instanz legt die Nachricht in den Ringpuffer und schickt sie an die Clients desselben Raums.
7. Das `data:` geht an den Client, danach folgt das ACK an RabbitMQ.

**Threads pro Instanz:** Einer. So bleibt die Reihenfolge erhalten, und S4 verlangt genau einen Consumer pro Queue.

### 3.2 Zwei Instanzen (S3)

**Warum kommt die Nachricht bei B an?** A schickt die Nachricht nicht direkt an B, A kennt B gar nicht. A publiziert sie einmal an den Fanout-Exchange `chat.delivery` und antwortet erst danach mit 202. Der Absender wartet auf diese 202, bevor er die nächste Nachricht schickt. Der Fanout-Exchange legt in jede gebundene Queue eine Kopie, ohne auf den Routing-Key zu schauen. Die Queue von B ist gebunden, also bekommt der Listener von B die Kopie und schickt sie an seine Clients in Raum A.

**Warum genau einmal?**

* Pro `POST` wird genau einmal an `chat.delivery` publiziert.
* Jede Instanz hat genau eine Queue mit genau einer Bindung, bekommt also genau eine Kopie.
* Jeder Client ist an genau einer Instanz und dort genau einmal angemeldet.

Wichtig ist, dass A seine eigenen Clients nicht direkt beim `POST` beliefert. Auch sie bekommen die Nachricht nur über die eigene Queue. Würde A zusätzlich direkt senden, kämen sie bei A doppelt an.

**Warum bekommt Raum B nichts?** Die Instanz filtert nach `roomId`.

**Warum bleibt die Reihenfolge eines Absenders erhalten?** Der Absender schickt die nächste Nachricht erst, wenn er die 202 der vorherigen hat, und die 202 kommt erst nach dem Publish. Ganz streng garantiert RabbitMQ die Reihenfolge nur pro Channel. Spring kann die Publishes auf mehrere Channels verteilen, die aber alle über dieselbe TCP-Verbindung laufen. Zusammen mit dem Warten auf die 202 bleibt die Reihenfolge so in der Praxis erhalten. Danach ist jede Queue FIFO, und pro Instanz liest nur ein Thread.

### 3.3 Herunterskalieren (S4)

**Die Queue der gestoppten Instanz:** Die Queue ist exclusive. RabbitMQ löscht sie selbst, sobald die Verbindung der Instanz zugeht, also sofort.

**Ihre Clients:** Ihre Verbindung bricht ab. Sie verbinden sich mit `Last-Event-ID` neu, dann bei einer anderen Instanz.

### 3.4 batch-writer und Datenbank weg (S5)

**Warum trotzdem sofort beim Client?** Der Anzeigeweg berührt die Datenbank nie.

**Wo liegen die Nachrichten?** In der Queue `chat.persist`. Weil der batch-writer gestoppt ist, hat sie keinen Consumer, und die Nachrichten bleiben dort liegen. Die Queue ist durable und die Nachrichten sind persistent, sie überleben also sogar einen Neustart von RabbitMQ. Sobald der batch-writer wieder läuft, holt er sie stapelweise ab, schreibt sie in die Tabelle und bestätigt sie erst danach. Die Kopien für die Anzeige liegen davon getrennt in den Instanz-Queues und sind bereits ausgeliefert.

### 3.5 Hängende Instanz (S6)

**Ohne Grenze:** Die Queue wächst, bis RabbitMQ Alarm auslöst und alle Publisher blockiert. Dann steht der ganze Chat.

**Die Grenze:** Die Grenze ist `x-max-length 950` mit `drop-head`. Ist sie erreicht, wird die älteste Nachricht verworfen.

**Wenn die Instanz wieder läuft:**

* Kurze Pause: Die Verbindung zu RabbitMQ steht noch. Die Instanz arbeitet zuerst den Rückstau ab, also die 50 unbestätigten und höchstens 950 wartenden Nachrichten, und ist dann wieder live. Was wegen der Grenze verworfen wurde, bekommen ihre Clients nicht.
* Pause länger als das Heartbeat-Timeout (Standard 60 s): RabbitMQ schliesst die Verbindung und löscht die exklusive Queue samt Inhalt. Nach dem Fortsetzen verbindet sich Spring neu und deklariert die Queue erneut. Der Name `chat.delivery.<UUID>` wurde einmal beim Start erzeugt, also entsteht eine leere Queue mit demselben Namen, wieder gebunden an `chat.delivery`. Ab dann stellt die Instanz neue Nachrichten wieder zu.

### 3.6 Neuverbindung mit Last-Event-ID (S7)

**Woher kommen die verpassten Nachrichten?** Sie kommen aus einem Ringpuffer im Speicher der Instanz (10'000 Nachrichten). Die Datenbank hinkt hinterher und kann sogar ganz weg sein.

**Keine Lücke, kein Doppel:** Es gibt einen Lock, und zwei Abläufe brauchen ihn:

* Der Listener nimmt den Lock, legt die neue Nachricht in den Ringpuffer und schickt sie an alle angemeldeten Clients des Raums. Erst dann gibt er den Lock frei.
* Die Neuverbindung nimmt denselben Lock, sucht im Puffer die `Last-Event-ID`, schickt alle späteren Nachrichten dieses Raums und meldet den Client an. Erst dann gibt sie den Lock frei.

Weil beide Abläufe nie gleichzeitig laufen, ist eine neue Nachricht entweder schon im Puffer und wird nachgeliefert, oder sie kommt nach der Anmeldung und wird live geschickt. Beides zugleich oder keins von beiden ist unmöglich. Die IDs sind zufällige UUIDs, deshalb sucht man die Position im Puffer und vergleicht nicht mit «grösser als».

**Unbekannte `Last-Event-ID`:** Eine ID «von einer anderen Instanz» ist meistens gar nicht unbekannt. Wegen Fanout bekommt jede Instanz jede Nachricht, also steht dieselbe ID in jedem Ringpuffer. Unbekannt ist sie nur in zwei Fällen:

* Sie ist zu alt und schon aus dem Puffer gefallen (mehr als 10'000 Nachrichten seither).
* Die Instanz lief noch nicht, als die Nachricht verschickt wurde, weil sie neu gestartet ist oder erst später dazukam. Ihr Puffer fängt erst danach an.

Dann kann der Server nicht wissen, was fehlt. Er schickt `event: gap` (ohne `id:`, Format siehe Vertrag 2.2), meldet den Client an und liefert ab dann live. So weiss der Client, dass es eine Lücke gibt, statt still Nachrichten zu verlieren.

## 4. Queues

**Name und Bindung:** `chat.delivery.<UUID>`. Die UUID erzeugt die Instanz beim Start selbst. So bekommt jede Instanz bei `--scale` einen eigenen Namen, ohne `container_name`. Die Queue ist an den Fanout-Exchange `chat.delivery` gebunden, mit leerem Routing-Key, weil Fanout ihn ohnehin ignoriert. Am Präfix `chat.delivery.` erkennt man die Instanz-Queues in `rabbitmqctl list_queues` sofort.

**durable / exclusive / auto-delete:**

* Nicht durable: Die Queue gehört genau einer laufenden Instanz. Startet RabbitMQ neu, sind die Verbindungen aller Instanzen ohnehin weg. Eine durable Queue würde ohne Consumer weiterleben und sich füllen, bis jemand sie löscht. Ihr Inhalt sind nur Anzeige-Kopien, die nach einem Neustart veraltet sind. Das Original liegt in `chat.persist`.
* Exclusive: Nur die Verbindung, die sie angelegt hat, darf sie benutzen, und mit dieser Verbindung verschwindet sie.
* Auto-delete: Fällt der letzte Consumer weg, wird sie gelöscht. Das ist eine zweite Sicherung neben exclusive.

**Lebensdauer:** Sie entsteht, wenn die Instanz sich mit RabbitMQ verbindet, und verschwindet, wenn diese Verbindung zugeht.

**Grenze (`x-max-length`):** 950. `x-max-length` zählt nur die ready-Nachrichten, also die, die in der Queue warten. Die Spalte `messages` von `rabbitmqctl list_queues`, mit der S6 gemessen wird, zählt aber ready + unacked. Unacked sind höchstens so viele wie der prefetch, also 50. Im schlimmsten Fall sind es 950 + 50 = 1000, genau die Grenze aus S6.

**prefetch:** Der prefetch ist 50. Für `x-max-length` zählt er nicht, für die Messung in S6 schon, weil `messages` auch die unbestätigten Nachrichten mitzählt. Darum muss die Grenze 1000 − 50 = 950 sein.

**Wenn die Grenze erreicht ist:** Mit `overflow: drop-head` wirft RabbitMQ die älteste wartende Nachricht vorne aus der Queue. Es gibt kein Dead-Lettering, also ist sie für diese Queue weg. Der `POST` merkt davon nichts. Die Kopien in `chat.persist` und in den anderen Instanz-Queues sind nicht betroffen, nur die Clients dieser einen Instanz verpassen sie.

Die Alternative wäre `reject-publish`. Weil der chat-service keine Publisher Confirms eingeschaltet hat (`application.yml` enthält nichts dazu), würde auch dort der `POST` nichts merken. RabbitMQ würde dann einfach die neueste Nachricht für diese eine Queue wegwerfen, und die anderen Queues bekämen sie trotzdem. Der echte Unterschied liegt darin, was nach dem Fortsetzen übrig ist:

* `drop-head` behält die neuesten 950. Die Instanz liefert nach dem Fortsetzen das aus, was gerade aktuell ist.
* `reject-publish` behält die ältesten 950. Die Instanz würde zuerst Nachrichten ausliefern, die längst veraltet sind, und die neuesten würden fehlen.

Für einen Chat ist das Aktuelle wichtiger, darum `drop-head`.

**Dead-Letter:** Nein, aus drei Gründen:

* Die verworfenen Nachrichten würden in einer DLQ landen, die niemand liest. Sie würde unbegrenzt wachsen, und genau das soll die Grenze verhindern.
* Verloren ist ohnehin nichts, weil das Original in `chat.persist` liegt.
* `chat.dlq` ist für Nachrichten da, die der batch-writer nicht speichern kann. Mischt man dort Anzeige-Kopien hinein, weiss niemand mehr, was ein echter Fehler ist.

**Was sich nicht ändern darf:** `chat.persist` (durable, Dead-Letter an Exchange `""` mit Routing-Key `chat.dlq`), `chat.dlq` (durable) und der Exchange `chat.delivery` (fanout, durable). Der Grund: chat-service und batch-writer deklarieren diese Queues beim Start. Wenn jemand sie mit anderen Eigenschaften neu deklariert, lehnt RabbitMQ das mit `PRECONDITION_FAILED` ab, und der Dienst startet nicht. Ausserdem prüft S4 ausdrücklich, dass `chat.persist` und `chat.dlq` bleiben. Neu kommen nur die Instanz-Queues dazu.

### Umgebungsvariablen

Keine.

* Aus `.env` kommen für den chat-service nur `RABBITMQ_USER` und `RABBITMQ_PASSWORD`. `RABBITMQ_HOST` steht fest in `docker-compose.yml:26`. Beides reicht auch für die neue Queue.
* Der Queue-Name wird beim Start erzeugt.
* Die Grenze 950 und der prefetch 50 sind aus S6 abgeleitet und stehen als Konstanten im Code. Über `.env` änderbar könnten sie S6 still kaputtmachen.
* Der Puffer von 10'000 ist nicht aus S7 abgeleitet, denn S7 braucht nur 50. Er ist eine gewählte Reserve, damit auch längere Unterbrüche nachgeholt werden können, ohne viel Speicher zu brauchen. Bei rund 1 KB pro Nachricht sind das etwa 10 MB.
* Neue Geheimnisse gibt es keine.

Laut PDF muss die README-Tabelle «Stand» trotzdem nachgeführt werden.

## 5. Abnahmekriterien

Reihenfolge: Die Szenarien laufen auf demselben Stack in der Reihenfolge S1 → S2 → S3 → S4 → S5 → S6 → S7 → S8, so wie im PDF. Die Befehle hängen voneinander ab:

* Nach S4 läuft nur noch eine Instanz, `ip_of 2` wäre dann leer. Darum holt S5 die Adresse neu.
* S6 skaliert wieder auf zwei Instanzen und setzt `$A` und `$B`.
* S7 braucht `$B` aus S6.

Vorbereitung in Git Bash, im Wurzelverzeichnis (gleich wie vorher):

```
ROOM_A=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa; ROOM_B=bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb
ip_of() { docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$(docker compose ps -q chat-service | sed -n "${1}p")"; }
listen() { docker run -d --name "$1" --network chat-net curlimages/curl:8.11.1 -sN $3 "http://$2:8080/stream?roomId=$4"; }
send_messages() { docker run --rm --network chat-net -e HOST="$1" -e ROOM="$2" -e SENDER="$3" -e COUNT="$4" curlimages/curl:8.11.1 sh -c '
i=1; while [ $i -le $COUNT ]; do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST "http://$HOST:8080/messages" -H "Content-Type: application/json" \
    -d "{\"roomId\":\"$ROOM\",\"senderId\":\"$SENDER\",\"senderName\":\"$SENDER\",\"content\":\"$i\"}"
  i=$((i+1)); done' | sort | uniq -c; }
in_order() { docker logs "$1" | grep '^data:' | grep "\"senderId\":\"$2\"" | sed 's/.*"content":"\([0-9]*\)".*/\1/' | awk 'NR!=$1{bad=1} END{print (bad||NR==0) ? "FALSCH" : "ok " NR}'; }
```

### S1: Tests

Kriterium: `BUILD SUCCESS`, `Failures: 0, Errors: 0`. In der Liste der Testklassen stehen der Zwei-Instanzen-Test (S3) und der Neuverbindungs-Test (S7).

```
mvn clean test > s1.txt 2>&1
grep -E "Tests run:.*Fail|BUILD" s1.txt | tail -n 2
grep "Tests run:" s1.txt | grep " - in "
```

### S2: Frischer Klon, keine offenen Ports

Kriterium:

* Alle vier Dienste sind `running`.
* Es gibt 0 Port-Mappings.
* `/stream` mit gültiger `roomId` liefert einen Content-Type, der mit `text/event-stream` beginnt.
* Ohne `roomId` oder mit `roomId=abc` kommt 400.

Zuerst wird der alte Stack gestoppt. Der Netzname ist fest auf `chat-net` gesetzt (`docker-compose.yml:89`). Liefe der alte Stack weiter, würden sich beide Stacks dieses Netz teilen, und `chat-service` könnte auf die falschen Container zeigen. Im neuen Ordner werden danach die Hilfsfunktionen neu geladen, sonst ist `$ROOM_A` dort leer.

```
docker compose down
git clone https://github.com/rsfigo/it3c-m321.git s2-check
cd s2-check
# hier den Vorbereitungsblock mit ROOM_A, ROOM_B, ip_of, listen, send_messages, in_order einfügen
cp .env.example .env
docker compose up -d --build
docker compose ps --format '{{.Service}} {{.State}}'
docker compose ps --format '{{.Ports}}' | grep -c -- '->'
docker run --rm --network chat-net curlimages/curl:8.11.1 -s -D - -o /dev/null --max-time 2 "http://chat-service:8080/stream?roomId=$ROOM_A" | grep -i '^content-type'
docker run --rm --network chat-net curlimages/curl:8.11.1 -s -o /dev/null -w '%{http_code}\n' "http://chat-service:8080/stream"
docker run --rm --network chat-net curlimages/curl:8.11.1 -s -o /dev/null -w '%{http_code}\n' "http://chat-service:8080/stream?roomId=abc"
```

Erwartet:

* vier Zeilen mit `running`
* `0`
* eine Content-Type-Zeile, die mit `text/event-stream` beginnt (zum Beispiel auch `text/event-stream;charset=UTF-8`)
* `400`
* `400`

Das Compose-Projekt heisst nach dem Ordner, im frischen Klon also s2-check. Darum bekommt dieser Stack einen eigenen, leeren Datenträger s2-check_chat-db-data, und schema.sql legt die Tabelle neu an. Für S2 ist das richtig, denn ein frischer Klon soll ohne Altlasten starten. Der alte Datenträger bleibt unangetastet.

### S3: Zwei Instanzen, zwei Räume

Kriterium:

* Alle 400 `POST`s geben 202.
* sse-a1 und sse-a2 zeigen je 400 `data:`-Zeilen und keine doppelte ID.
* `in_order` gibt für beide Absender `ok 200`.
* sse-b zeigt 0.

```
docker compose up -d --scale chat-service=2
A=$(ip_of 1); B=$(ip_of 2)
listen sse-a1 $A "" $ROOM_A
listen sse-a2 $B "" $ROOM_A
listen sse-b  $A "" $ROOM_B
sleep 2
send_messages $A $ROOM_A s3a 200
send_messages $B $ROOM_A s3b 200
sleep 5
for c in sse-a1 sse-a2; do docker logs $c | grep -c '^data:'; docker logs $c | grep '^id:' | sort | uniq -d | wc -l; in_order $c s3a; in_order $c s3b; done
docker logs sse-b | grep -c '^data:'
docker rm -f sse-a1 sse-a2 sse-b
```

Das `sleep 2` vor dem Senden ist wichtig. Ohne Historie bekommt ein Client nur, was nach seiner Anmeldung ankommt.

### S4: Zurück auf eine Instanz

Kriterium: Vorher sind 2 Queues `chat.delivery.*` gebunden, mit je 1 Consumer. Die gemessene Dauer ab dem Scale-Befehl ist höchstens 30 s. `chat.persist` und `chat.dlq` sind noch da.

```
docker compose exec -T rabbitmq rabbitmqctl -q list_bindings source_name destination_name | grep -c '^chat.delivery'
docker compose exec -T rabbitmq rabbitmqctl -q list_queues name consumers
START=$(date +%s)
docker compose up -d --scale chat-service=1
while [ "$(docker compose exec -T rabbitmq rabbitmqctl -q list_queues name | grep -c '^chat.delivery\.')" != 1 ]; do
  [ $(( $(date +%s) - START )) -gt 60 ] && echo "Abbruch nach 60 s" && break
  sleep 1
done
echo "Dauer: $(( $(date +%s) - START )) s"
docker compose exec -T rabbitmq rabbitmqctl -q list_queues name | grep -E '^chat\.(persist|dlq)$'
```

Die Startzeit wird vor dem Scale-Befehl genommen, weil dieser selbst wartet, bis der Container gestoppt ist. Das kann bis zu 10 s dauern.

### S5: Datenbank weg

Kriterium: 5 s nach dem letzten `POST` gibt `in_order` `ok 100`. Nach dem Neustart zählt `psql` 100 Zeilen von `s5`.

```
A=$(ip_of 1)
docker compose stop batch-writer postgres
listen sse-s5 $A "" $ROOM_A
sleep 2
send_messages $A $ROOM_A s5 100
sleep 5
in_order sse-s5 s5
docker rm -f sse-s5
docker compose start postgres batch-writer
sleep 15
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT count(*) FROM message WHERE sender_id = '\''s5'\''"'
```

Erwartet: `202` hundertmal, `ok 100`, dann `100`.

### S6: Eine Instanz hängt

Kriterium:

* Das Maximum von `messages` aller `chat.delivery.*`-Queues ist höchstens 1000.
* Der Client an B hat `ok 5000`.
* Der Client, der schon während der Pause an A hing, bekommt nach dem Fortsetzen die neue Nachricht `s6after`, und sie ist seine letzte `data:`-Zeile.

```
docker compose up -d --scale chat-service=2
A=$(ip_of 1); B=$(ip_of 2); PAUSED=$(docker compose ps -q chat-service | sed -n 1p)
listen sse-s6b $B "" $ROOM_A
listen sse-s6a $A "" $ROOM_A
sleep 2
docker pause $PAUSED
( while true; do docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages | grep '^chat.delivery\.'; sleep 1; done > s6-queues.txt ) & WATCH=$!
send_messages $B $ROOM_A s6 5000
sleep 10
kill $WATCH
awk '{ if ($2 > max) max = $2 } END { print "Maximum:", max }' s6-queues.txt
in_order sse-s6b s6
docker unpause $PAUSED
sleep 10
send_messages $B $ROOM_A s6after 1
sleep 20
docker logs sse-s6a | grep '^data:' | tail -1 | grep -c '"senderId":"s6after"'
docker rm -f sse-s6a sse-s6b
rm s6-queues.txt
```

Erwartet: `Maximum:` höchstens 1000, `ok 5000`, `1`.

* Das `sleep 10` nach dem Senden gibt dem Client an B Zeit, auch die letzten Nachrichten zu bekommen. Ausserdem misst `WATCH` so die vollste Phase mit.
* Das PDF nennt `docker compose pause`. Mit dem Dienstnamen hält das beide Instanzen an, darum hier `docker pause` auf einen einzelnen Container.
* Gesendet wird nur an B, weil ein `POST` an die angehaltene Instanz hängen bleibt.

### S7: Neuverbindung mit Last-Event-ID

Kriterium:

* `ok 50`
* 0 doppelte IDs
* 0× `s7first`
* 0× `event:gap`
* Die letzte `data:`-Zeile ist `s7live`.

```
listen sse-s7 $B "" $ROOM_A
sleep 2
send_messages $B $ROOM_A s7first 1
sleep 1
LAST=$(docker logs sse-s7 | grep '^id:' | tail -1 | sed 's/^id: *//')
docker rm -f sse-s7
send_messages $B $ROOM_A s7 50
sleep 10
listen sse-s7b $B "-H Last-Event-ID:$LAST" $ROOM_A
sleep 2
send_messages $B $ROOM_A s7live 1
sleep 2
in_order sse-s7b s7
docker logs sse-s7b | grep '^id:' | sort | uniq -d | wc -l
docker logs sse-s7b | grep -c '"senderId":"s7first"'
docker logs sse-s7b | grep -c '^event:gap'
docker logs sse-s7b | grep '^data:' | tail -1 | grep -c '"senderId":"s7live"'
docker rm -f sse-s7b
```

Erwartet: `ok 50`, `0`, `0`, `0`, `1`.

### S8: Codestil und Geheimnisse

Kriterium: Keiner der drei Befehle gibt etwas aus.

```
grep -rnE "\.stream\(|Stream\.|Collectors|\.forEach\(" chat-service/src
git ls-files | grep -x ".env"
awk 'FNR==1{prev=""} /^[[:space:]]*@/{next} /^[[:space:]]*(public|protected|private|static|void|class|record|[A-Za-z<>\[\]]+[[:space:]]+[a-zA-Z]+\()/ && /(\(|class |record )/ && !/^[[:space:]]*(return|if|for|while|new|throw)/ && !/;[[:space:]]*$/ { if (prev !~ /\*\/[[:space:]]*$/) print FILENAME ":" FNR ": " $0 } /[^[:space:]]/{prev=$0}' $(find chat-service/src -name "*.java")
```

Das ist derselbe `awk`-Check wie in meiner Spezifikation von Bewertung 1, nur mit `chat-service/src`. Im Moment gibt er noch 18 Methoden ohne Kommentar aus.
