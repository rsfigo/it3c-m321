package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Die Eingangstür: macht aus dem rohen Körper einer AMQP-Nachricht eine
 * ChatMessage und prüft die Pflichtfelder.
 *
 * Der Leser schaut nur auf den Körper, nie auf die Header. Der Header
 * __TypeId__ nennt eine Klasse des chat-service, die es hier nicht gibt, und
 * direkt eingelegte Nachrichten haben ihn gar nicht (Spezifikation 2.2).
 */
@Component
@RequiredArgsConstructor
public class MessageReader {

    /** Der ObjectMapper von Spring Boot: kann UUID und Instant, ignoriert unbekannte Felder. */
    private final ObjectMapper objectMapper;

    /**
     * Liest eine Nachricht oder meldet, warum sie kaputt ist.
     *
     * Erst wenn alle sechs Felder da sind, passt die Nachricht sicher in die
     * Tabelle. Dann kann ein Stapel nur noch an der Datenbank scheitern, nie
     * an seinem Inhalt.
     */
    public ChatMessage read(Message message) throws InvalidMessageException {
        byte[] body = message.getBody();
        String json = new String(body, StandardCharsets.UTF_8);

        ChatMessage chatMessage = parse(json);
        checkRequiredFields(chatMessage);

        return chatMessage;
    }

    /**
     * Wandelt den Text in eine ChatMessage.
     *
     * Ungültiges JSON und falsche Typen (zum Beispiel keine UUID in id) meldet
     * Jackson als JsonProcessingException. Der Text "null" ergibt gar kein
     * Objekt und ist genauso kaputt.
     */
    private ChatMessage parse(String json) throws InvalidMessageException {
        ChatMessage chatMessage;
        try {
            chatMessage = objectMapper.readValue(json, ChatMessage.class);
        } catch (JsonProcessingException exception) {
            throw new InvalidMessageException("Body is not a valid chat message: " + exception.getOriginalMessage());
        }

        if (chatMessage == null) {
            throw new InvalidMessageException("Body is empty");
        }
        return chatMessage;
    }

    /** Jede Spalte der Tabelle ist NOT NULL, also muss jedes Feld da sein. */
    private void checkRequiredFields(ChatMessage chatMessage) throws InvalidMessageException {
        requireField(chatMessage.id(), "id");
        requireField(chatMessage.roomId(), "roomId");
        requireField(chatMessage.senderId(), "senderId");
        requireField(chatMessage.senderName(), "senderName");
        requireField(chatMessage.content(), "content");
        requireField(chatMessage.sentAt(), "sentAt");
    }

    /** Ein einzelnes Feld prüfen, damit der Grund genau dieses Feld nennt. */
    private void requireField(Object value, String fieldName) throws InvalidMessageException {
        if (value == null) {
            throw new InvalidMessageException("Missing field " + fieldName);
        }
    }
}
