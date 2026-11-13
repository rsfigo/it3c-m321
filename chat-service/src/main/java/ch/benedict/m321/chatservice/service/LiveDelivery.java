package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.LiveMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Kennt alle offenen Stream-Verbindungen dieser Instanz, sortiert nach
 * Raum, und schickt ihnen die Nachrichten (Spezifikation 3.1 und 3.2).
 *
 * Es gibt nur diese eine Stelle, die an Clients schreibt. Der POST
 * beliefert niemanden direkt — auch die eigenen Clients bekommen jede
 * Nachricht nur über die eigene Queue. Sonst käme sie bei ihnen doppelt an.
 */
@Slf4j
@Component
public class LiveDelivery {

    /**
     * Anmelden und Ausliefern laufen nie gleichzeitig. Das ist die
     * Grundlage dafür, dass beim Neuverbinden keine Lücke und kein Doppel
     * entsteht (Spezifikation 3.6).
     */
    private final Object lock = new Object();

    /**
     * Die Clients pro Raum. Die Listen sind CopyOnWriteArrayList, damit
     * eine Verbindung sich abmelden kann, während gerade jemand über die
     * Liste läuft — ohne dafür den Lock zu brauchen.
     */
    private final Map<UUID, List<SseEmitter>> clientsByRoom = new ConcurrentHashMap<>();

    /**
     * So viele Nachrichten behält die Instanz fürs Nachliefern. S7 braucht
     * nur 50, 10'000 ist eine Reserve für längere Unterbrüche
     * (Spezifikation 4, Umgebungsvariablen).
     */
    static final int BUFFER_SIZE = 10_000;

    /** Die letzten Nachrichten aller Räume, die älteste vorne. Nur unter dem Lock benutzen. */
    private final Deque<LiveMessage> buffer = new ArrayDeque<>();

    /**
     * Meldet eine neue Verbindung für einen Raum an. Ab jetzt bekommt sie
     * jede Nachricht dieses Raums. Bringt der Client eine Last-Event-ID mit
     * (darf null sein), bekommt er zuerst die verpassten Nachrichten.
     */
    public void connect(UUID roomId, String lastEventId, SseEmitter client) {
        removeWhenClosed(roomId, client);
        synchronized (lock) {
            sendConnectedComment(client);
            if (lastEventId != null && !lastEventId.isBlank()) {
                sendMissedMessages(roomId, lastEventId, client);
            }
            addClient(roomId, client);
        }
        log.debug("Client connected to room {} with Last-Event-ID {}", roomId, lastEventId);
    }

    /**
     * Liefert nach, was der Client seit lastEventId verpasst hat
     * (Spezifikation 3.6). Nur unter dem Lock aufrufen: Solange er gehalten
     * wird, kann keine neue Nachricht dazukommen. Jede Nachricht ist darum
     * entweder schon im Puffer und wird hier geschickt, oder sie kommt nach
     * der Anmeldung live — nie beides und nie keins.
     *
     * Die IDs sind zufällige UUIDs. Wir suchen deshalb die Position im
     * Puffer und schicken alles, was danach kam, statt IDs zu vergleichen.
     */
    private void sendMissedMessages(UUID roomId, String lastEventId, SseEmitter client) {
        boolean found = false;
        for (LiveMessage message : buffer) {
            if (found && message.roomId().equals(roomId)) {
                send(client, message);
            }
            if (message.id().toString().equals(lastEventId)) {
                found = true;
            }
        }

        if (!found) {
            sendGap(client, lastEventId);
        }
    }

    /**
     * Die ID ist nicht im Puffer: zu alt, oder diese Instanz lief damals
     * noch nicht. Das Ereignis "gap" sagt dem Client, dass ihm Nachrichten
     * fehlen könnten. Es hat bewusst keine id-Zeile (Spezifikation 2.2).
     */
    private void sendGap(SseEmitter client, String lastEventId) {
        SseEmitter.SseEventBuilder gap = SseEmitter.event().name("gap").data(lastEventId);
        sendEvent(client, gap);
        log.info("Unknown Last-Event-ID {}, sent gap", lastEventId);
    }

    /**
     * Schreibt sofort die Kommentarzeile ":connected".
     *
     * Ohne ein erstes Ereignis hält Spring die Antwort-Header zurück, und
     * der Client sieht bis zur ersten Nachricht nicht einmal den
     * Content-Type. Eine Zeile, die mit ":" beginnt, ist laut SSE-Standard
     * ein Kommentar: Jeder Client überliest sie, sie ist kein Ereignis.
     */
    private void sendConnectedComment(SseEmitter client) {
        SseEmitter.SseEventBuilder comment = SseEmitter.event().comment("connected");
        sendEvent(client, comment);
    }

    /**
     * Schickt eine Nachricht an alle Clients ihres Raums. Wird vom
     * DeliveryListener aufgerufen, für jede Nachricht genau einmal.
     */
    public void deliver(LiveMessage message) {
        synchronized (lock) {
            remember(message);
            sendToRoom(message);
        }
    }

    /**
     * Legt die Nachricht in den Ringpuffer. Ist er voll, fällt die älteste
     * hinaus. Nur unter dem Lock aufrufen.
     */
    private void remember(LiveMessage message) {
        buffer.addLast(message);
        if (buffer.size() > BUFFER_SIZE) {
            buffer.removeFirst();
        }
    }

    /**
     * Beim Herunterfahren alle Streams sauber beenden.
     *
     * Spring Boot wartet beim Stoppen, bis alle offenen Anfragen fertig
     * sind. Ein Stream ist nie von selbst fertig — ohne diese Methode würde
     * jede Instanz mit einem Client bis zum Abbruch durch Docker hängen.
     * Das Ereignis kommt, bevor der Webserver anfängt zu warten.
     */
    @EventListener(ContextClosedEvent.class)
    public void closeAllConnections() {
        for (List<SseEmitter> clients : clientsByRoom.values()) {
            for (SseEmitter client : clients) {
                client.complete();
            }
        }
        log.info("Closed all stream connections for shutdown");
    }

    /** Hängt den Client an die Liste seines Raums. Nur unter dem Lock aufrufen. */
    private void addClient(UUID roomId, SseEmitter client) {
        List<SseEmitter> clients = clientsByRoom.get(roomId);
        if (clients == null) {
            clients = new CopyOnWriteArrayList<>();
            clientsByRoom.put(roomId, clients);
        }
        clients.add(client);
    }

    /**
     * Schickt die Nachricht an jeden Client des Raums. Wer nicht mehr
     * erreichbar ist, wird abgemeldet. Nur unter dem Lock aufrufen.
     */
    private void sendToRoom(LiveMessage message) {
        List<SseEmitter> clients = clientsByRoom.get(message.roomId());
        if (clients == null) {
            return;
        }
        for (SseEmitter client : clients) {
            boolean sent = send(client, message);
            if (!sent) {
                removeClient(message.roomId(), client);
            }
        }
    }

    /**
     * Schreibt ein Ereignis: "id:" ist die Nachrichten-ID, "data:" das JSON
     * aus der Queue (Spezifikation 2.2).
     *
     * Gibt false zurück, wenn die Verbindung schon zu ist. Das ist kein
     * Fehler, sondern der normale Fall, wenn ein Client einfach geht.
     */
    boolean send(SseEmitter client, LiveMessage message) {
        String eventId = message.id().toString();
        SseEmitter.SseEventBuilder event = SseEmitter.event().id(eventId).data(message.json());
        return sendEvent(client, event);
    }

    /** Schreibt ein fertiges Ereignis und fängt eine geschlossene Verbindung ab. */
    boolean sendEvent(SseEmitter client, SseEmitter.SseEventBuilder event) {
        try {
            client.send(event);
            return true;
        } catch (IOException | IllegalStateException exception) {
            log.debug("Stream connection is gone: {}", exception.getMessage());
            return false;
        }
    }

    /**
     * Sorgt dafür, dass ein Client abgemeldet wird, sobald seine Verbindung
     * endet — egal ob er selbst geht, ein Fehler passiert oder der Server
     * herunterfährt. Spring ruft die übergebenen Methoden dann auf.
     */
    private void removeWhenClosed(UUID roomId, SseEmitter client) {
        client.onCompletion(() -> removeClient(roomId, client));
        client.onTimeout(() -> removeClient(roomId, client));
        client.onError(error -> removeClient(roomId, client));
    }

    /** Nimmt den Client aus der Liste seines Raums. Braucht keinen Lock. */
    private void removeClient(UUID roomId, SseEmitter client) {
        List<SseEmitter> clients = clientsByRoom.get(roomId);
        if (clients != null) {
            clients.remove(client);
        }
    }
}
