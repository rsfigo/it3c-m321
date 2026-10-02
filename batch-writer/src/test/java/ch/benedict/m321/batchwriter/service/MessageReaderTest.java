package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Prüft den Vertrag aus der Spezifikation, Abschnitt 2.2: was der Leser
 * annimmt und was er als kaputt erkennt.
 *
 * Ohne Container und ohne Spring-Kontext — das Lesen hängt nur am JSON.
 */
class MessageReaderTest {

    /** Genau der Körper, der im Vertragsbeleg (Spezifikation 2.3) in chat.persist lag. */
    private static final String CHAT_SERVICE_BODY = "{\"id\":\"4a823a82-efc9-413b-b98e-b5414d6b9401\","
            + "\"roomId\":\"11111111-1111-1111-1111-111111111111\",\"senderId\":\"anna\","
            + "\"senderName\":\"Anna\",\"content\":\"Vertragsbeleg\","
            + "\"sentAt\":\"2026-10-02T09:53:41.916405321Z\"}";

    private MessageReader messageReader;

    /**
     * Derselbe Baukasten, mit dem Spring Boot seinen ObjectMapper baut. So
     * liest der Test JSON genau so wie der Dienst im Betrieb.
     */
    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        messageReader = new MessageReader(objectMapper);
    }

    /** Eine Nachricht, wie sie der chat-service schickt: mit __TypeId__ und Encoding. */
    @Test
    void readsMessageInChatServiceFormat() throws InvalidMessageException {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setContentEncoding("UTF-8");
        properties.setHeader("__TypeId__", "ch.benedict.m321.chatservice.dto.ChatMessage");
        Message message = createMessage(CHAT_SERVICE_BODY, properties);

        ChatMessage chatMessage = messageReader.read(message);

        assertEquals(UUID.fromString("4a823a82-efc9-413b-b98e-b5414d6b9401"), chatMessage.id());
        assertEquals(UUID.fromString("11111111-1111-1111-1111-111111111111"), chatMessage.roomId());
        assertEquals("anna", chatMessage.senderId());
        assertEquals("Anna", chatMessage.senderName());
        assertEquals("Vertragsbeleg", chatMessage.content());
        assertEquals(Instant.parse("2026-10-02T09:53:41.916405321Z"), chatMessage.sentAt());
    }

    /**
     * Szenario S5 legt Nachrichten direkt in die Queue, nur mit content_type.
     * Ohne __TypeId__ muss der Leser genauso funktionieren.
     */
    @Test
    void readsMessageWithOnlyContentType() throws InvalidMessageException {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        Message message = createMessage(CHAT_SERVICE_BODY, properties);

        ChatMessage chatMessage = messageReader.read(message);

        assertEquals(UUID.fromString("4a823a82-efc9-413b-b98e-b5414d6b9401"), chatMessage.id());
    }

    /**
     * Spezifikation 2.2: Ergänzt der chat-service später ein Feld, darf der
     * batch-writer nicht brechen. Das zusätzliche Feld wird einfach übergangen.
     */
    @Test
    void ignoresUnknownFields() throws InvalidMessageException {
        String bodyWithExtraField = CHAT_SERVICE_BODY.replace("{\"id\"", "{\"priority\":\"high\",\"id\"");
        Message message = createMessage(bodyWithExtraField, new MessageProperties());

        ChatMessage chatMessage = messageReader.read(message);

        assertEquals("Vertragsbeleg", chatMessage.content());
    }

    /** Ein Körper, der gar kein JSON ist, wird erkannt und nicht durchgereicht. */
    @Test
    void rejectsBodyThatIsNotJson() {
        Message message = createMessage("das ist kein JSON", new MessageProperties());

        assertThrows(InvalidMessageException.class, () -> messageReader.read(message));
    }

    /** Ohne id gäbe es keinen Primärschlüssel und keinen Schutz gegen Duplikate. */
    @Test
    void rejectsMessageWithoutId() {
        String bodyWithoutId = CHAT_SERVICE_BODY.replace("\"id\":\"4a823a82-efc9-413b-b98e-b5414d6b9401\",", "");
        Message message = createMessage(bodyWithoutId, new MessageProperties());

        assertThrows(InvalidMessageException.class, () -> messageReader.read(message));
    }

    /** Eine id, die keine UUID ist, passt nicht in die Spalte und gilt als kaputt. */
    @Test
    void rejectsIdThatIsNotAUuid() {
        String bodyWithBadId = CHAT_SERVICE_BODY.replace("4a823a82-efc9-413b-b98e-b5414d6b9401", "123");
        Message message = createMessage(bodyWithBadId, new MessageProperties());

        assertThrows(InvalidMessageException.class, () -> messageReader.read(message));
    }

    /**
     * Das Nullzeichen (Zeichencode 0) ist in JSON erlaubt, PostgreSQL speichert
     * es aber in keiner Textspalte. Im JSON steht es als Escape-Folge, die
     * Jackson beim Lesen in das echte Zeichen verwandelt.
     */
    @Test
    void rejectsTextWithNullCharacter() {
        String bodyWithNullCharacter = CHAT_SERVICE_BODY.replace("Vertragsbeleg", "vorher\\u0000nachher");
        Message message = createMessage(bodyWithNullCharacter, new MessageProperties());

        assertThrows(InvalidMessageException.class, () -> messageReader.read(message));
    }

    /** Das Jahr 300000 ist kein echter Sendezeitpunkt, und timestamptz kann es nicht speichern. */
    @Test
    void rejectsSentAtOutsideYearsOneToNineThousandNineHundredNinetyNine() {
        String bodyFromTheFarFuture = CHAT_SERVICE_BODY.replace(
                "2026-10-02T09:53:41.916405321Z", "+300000-01-01T00:00:00Z");
        Message message = createMessage(bodyFromTheFarFuture, new MessageProperties());

        assertThrows(InvalidMessageException.class, () -> messageReader.read(message));
    }

    /** Baut eine AMQP-Nachricht aus Text und Eigenschaften, wie sie aus der Queue käme. */
    private Message createMessage(String body, MessageProperties properties) {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        return new Message(bodyBytes, properties);
    }
}
