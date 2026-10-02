package ch.benedict.m321.batchwriter.repository;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Die einzige Stelle im ganzen System, die in die Tabelle message schreibt.
 *
 * Ein Stapel wird in EINER Transaktion geschrieben. Das ist der Grund für
 * den ganzen Dienst: 500 Nachrichten kosten die Datenbank ein COMMIT statt
 * 500 (Spezifikation, Abschnitt 1).
 */
@Repository
@RequiredArgsConstructor
public class MessageRepository {

    /**
     * ON CONFLICT (id) DO NOTHING: Steht die ID schon in der Tabelle, wird die
     * Zeile still übersprungen, ohne Fehler. So richten Duplikate, die bei
     * at-least-once vorkommen, keinen Schaden an (Spezifikation 3.3).
     */
    private static final String INSERT_SQL =
            "INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (id) DO NOTHING";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Schreibt alle Nachrichten eines Stapels und gibt zurück, wie viele davon
     * neu sind. Die Differenz zur Stapelgrösse sind Duplikate.
     *
     * Transactional macht aus dem ganzen Stapel eine Transaktion: entweder
     * stehen danach alle Zeilen in der Tabelle oder keine.
     */
    @Transactional
    public int insertBatch(List<ChatMessage> messages) {
        List<Object[]> rows = new ArrayList<>();
        for (ChatMessage message : messages) {
            Object[] row = toRow(message);
            rows.add(row);
        }

        int[] insertedPerRow = jdbcTemplate.batchUpdate(INSERT_SQL, rows);

        return sum(insertedPerRow);
    }

    /**
     * Ordnet die Felder den Platzhaltern zu, in der Reihenfolge von INSERT_SQL.
     * Der Zeitstempel geht als OffsetDateTime in UTC hinein, so passt er
     * ohne Umrechnung in die Spalte timestamptz.
     */
    private Object[] toRow(ChatMessage message) {
        OffsetDateTime sentAt = message.sentAt().atOffset(ZoneOffset.UTC);
        return new Object[] {
                message.id(),
                message.roomId(),
                message.senderId(),
                message.senderName(),
                message.content(),
                sentAt
        };
    }

    /** Für jede Zeile meldet PostgreSQL 1 (eingefügt) oder 0 (Duplikat übersprungen). */
    private int sum(int[] insertedPerRow) {
        int total = 0;
        for (int inserted : insertedPerRow) {
            total = total + inserted;
        }
        return total;
    }
}
