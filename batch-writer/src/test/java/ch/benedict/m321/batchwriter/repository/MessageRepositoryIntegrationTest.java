package ch.benedict.m321.batchwriter.repository;

import ch.benedict.m321.batchwriter.IntegrationTestBase;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Prüft den Kern des Dienstes an der echten Datenbank: ein Stapel, eine
 * Transaktion, und jede ID höchstens einmal (Spezifikation 3.1, 3.3).
 */
class MessageRepositoryIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MessageRepository messageRepository;

    /** Jeder Test beginnt mit einer leeren Tabelle, sonst zählt er die Zeilen des vorigen mit. */
    @BeforeEach
    void emptyTable() {
        jdbcTemplate.update("DELETE FROM message");
    }

    /** Alle Nachrichten eines Stapels stehen danach in der Tabelle, mit allen Feldern. */
    @Test
    void storesEveryMessageOfTheBatch() {
        List<ChatMessage> batch = createMessages(500);

        int stored = messageRepository.insertBatch(batch);

        assertEquals(500, stored);
        assertEquals(500, countRows());

        ChatMessage first = batch.get(0);
        String content = jdbcTemplate.queryForObject(
                "SELECT content FROM message WHERE id = ?", String.class, first.id());
        assertEquals(first.content(), content);
    }

    /** S5: dieselbe ID in einem späteren Stapel ergibt keine zweite Zeile und keinen Fehler. */
    @Test
    void storesSameIdOnlyOnceAcrossBatches() {
        ChatMessage message = createMessage(UUID.randomUUID(), "einmal");
        List<ChatMessage> firstBatch = List.of(message);
        List<ChatMessage> secondBatch = List.of(message);

        int storedFirst = messageRepository.insertBatch(firstBatch);
        int storedSecond = messageRepository.insertBatch(secondBatch);

        assertEquals(1, storedFirst);
        assertEquals(0, storedSecond);
        assertEquals(1, countRows());
    }

    /** S5: dieselbe ID zweimal im selben Stapel — auch das ist kein Fehler. */
    @Test
    void storesSameIdOnlyOnceWithinOneBatch() {
        ChatMessage message = createMessage(UUID.randomUUID(), "doppelt im Stapel");
        List<ChatMessage> batch = List.of(message, message);

        int stored = messageRepository.insertBatch(batch);

        assertEquals(1, stored);
        assertEquals(1, countRows());
    }

    /**
     * S4: Ein Stapel ist genau EINE Transaktion. Beweis über xmin: jede Zeile
     * trägt die Nummer der Transaktion, die sie geschrieben hat. Zwei Stapel
     * mit je 500 Zeilen ergeben genau zwei verschiedene Nummern.
     */
    @Test
    void writesWholeBatchInOneTransaction() {
        List<ChatMessage> firstBatch = createMessages(500);
        List<ChatMessage> secondBatch = createMessages(500);

        messageRepository.insertBatch(firstBatch);
        messageRepository.insertBatch(secondBatch);

        Integer transactions = jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT xmin::text) FROM message", Integer.class);
        assertEquals(2, transactions);
        assertEquals(1000, countRows());
    }

    /** Zählt alle Zeilen der Tabelle. */
    private int countRows() {
        Integer rows = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        return rows;
    }

    /** Baut einen Stapel aus lauter verschiedenen Nachrichten. */
    private List<ChatMessage> createMessages(int count) {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ChatMessage message = createMessage(UUID.randomUUID(), "Nachricht " + i);
            messages.add(message);
        }
        return messages;
    }

    /** Baut eine vollständige Nachricht, damit die Tests kurz bleiben. */
    private ChatMessage createMessage(UUID id, String content) {
        UUID roomId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Instant sentAt = Instant.now();
        return new ChatMessage(id, roomId, "anna", "Anna", content, sentAt);
    }
}
