package ch.benedict.m321.batchwriter.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine Nachricht, wie sie in chat.persist ankommt.
 *
 * Eine eigene Kopie und keine gemeinsame Klasse mit dem chat-service: der
 * Vertrag zwischen den Diensten ist das JSON auf der Leitung
 * (docs/spec-batch-writer.md, Abschnitt 2.2). Die Feldnamen müssen deshalb
 * genau so heissen wie die JSON-Felder.
 *
 * @param id         vom chat-service vergeben, wird zum Primärschlüssel
 * @param roomId     der Raum der Nachricht
 * @param senderId   die sub-Kennung des Absenders
 * @param senderName der Anzeigename des Absenders
 * @param content    der Text der Nachricht
 * @param sentAt     der Zeitstempel, den der chat-service gesetzt hat
 */
public record ChatMessage(
        UUID id,
        UUID roomId,
        String senderId,
        String senderName,
        String content,
        Instant sentAt) {
}
