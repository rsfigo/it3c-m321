package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.service.LiveDelivery;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Der Stream für die Live-Zustellung: GET /stream?roomId=... liefert
 * Server-Sent Events (Spezifikation 2.2).
 *
 * Fehlt die roomId oder ist sie keine UUID, antwortet Spring selbst mit
 * 400 und ruft die Methode gar nicht erst auf. Es wird dann auch kein
 * Client angemeldet.
 */
@RestController
@RequiredArgsConstructor
public class StreamController {

    /**
     * 0 heisst: kein Timeout. Ohne diese Angabe beendet Spring jeden Stream
     * nach 30 Sekunden, und der Client würde ständig neu verbinden.
     */
    private static final long NO_TIMEOUT = 0L;

    private final LiveDelivery liveDelivery;

    /**
     * Öffnet einen Stream für einen Raum. Die Verbindung bleibt offen, und
     * LiveDelivery schreibt jede neue Nachricht dieses Raums hinein.
     *
     * Der Header Last-Event-ID ist freiwillig. Der Browser schickt ihn bei
     * einer Neuverbindung von selbst mit, dann wird nachgeliefert.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter openStream(@RequestParam UUID roomId,
                                 @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        SseEmitter client = new SseEmitter(NO_TIMEOUT);
        liveDelivery.connect(roomId, lastEventId, client);
        return client;
    }
}
