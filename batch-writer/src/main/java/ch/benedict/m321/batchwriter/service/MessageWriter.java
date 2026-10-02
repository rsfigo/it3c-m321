package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import java.util.List;

/**
 * Schreibt einen Stapel und gibt nicht auf, solange die Datenbank weg ist.
 *
 * Ein Datenbankausfall ist ein Fehler der Umgebung, nicht der Nachricht. Die
 * Nachrichten gehören deshalb weder in chat.dlq noch zurück in die Queue:
 * der Stapel bleibt unbestätigt bei uns, und wir versuchen es mit wachsender
 * Pause erneut (Spezifikation 3.5).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MessageWriter {

    /** Die erste Pause nach einem Fehlschlag. */
    static final long FIRST_PAUSE_MILLIS = 1000;

    /** Länger wird nie gewartet, damit der Dienst nach dem Ausfall bald wieder schreibt. */
    static final long MAX_PAUSE_MILLIS = 10000;

    private final MessageRepository messageRepository;

    /**
     * Schreibt den Stapel und kehrt erst zurück, wenn er in der Datenbank
     * steht. Erst danach bestätigt der Listener die Nachrichten bei RabbitMQ.
     *
     * Abgefangen werden nur Fehler der Datenbank: DataAccessException (ein
     * Befehl scheitert) und TransactionException (schon die Transaktion lässt
     * sich nicht öffnen, weil es keine Verbindung gibt). Wird der Dienst
     * gestoppt, beendet die InterruptedException das Warten — der Stapel ist
     * dann unbestätigt und RabbitMQ liefert ihn neu aus.
     */
    public int writeUntilStored(List<ChatMessage> messages) throws InterruptedException {
        long pauseMillis = FIRST_PAUSE_MILLIS;
        int attempt = 1;

        while (true) {
            try {
                int stored = messageRepository.insertBatch(messages);
                return stored;
            } catch (DataAccessException | TransactionException failure) {
                log.warn("Attempt {} to write {} messages failed, next attempt in {} ms: {}",
                        attempt, messages.size(), pauseMillis, failure.getMessage());
                Thread.sleep(pauseMillis);
                pauseMillis = nextPause(pauseMillis);
                attempt = attempt + 1;
            }
        }
    }

    /** Verdoppelt die Pause, aber nie über MAX_PAUSE_MILLIS hinaus. */
    private long nextPause(long pauseMillis) {
        long doubledPause = pauseMillis * 2;
        if (doubledPause > MAX_PAUSE_MILLIS) {
            return MAX_PAUSE_MILLIS;
        }
        return doubledPause;
    }
}
