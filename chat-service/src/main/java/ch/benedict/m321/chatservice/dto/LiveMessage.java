package ch.benedict.m321.chatservice.dto;

import java.util.UUID;

/**
 * Eine Nachricht auf dem Weg zum Stream-Client.
 *
 * Neben id und roomId, die wir zum Verteilen brauchen, behalten wir das
 * JSON genau so, wie es aus der Queue kam. So ist "data:" am Stream
 * Zeichen für Zeichen dasselbe wie in chat.persist (Spezifikation 2.2).
 */
public record LiveMessage(UUID id, UUID roomId, String json) {
}
