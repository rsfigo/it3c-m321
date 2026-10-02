package ch.benedict.m321.batchwriter.config;

/**
 * Die Namen der Queues an genau EINER Stelle.
 *
 * Eine eigene Kopie und kein gemeinsames Modul mit dem chat-service: die
 * Namen sind Teil des Vertrags auf der Leitung, nicht einer geteilten
 * Java-Klasse (Spezifikation 2.1).
 */
public final class QueueNames {

    /** Hier liegen die Nachrichten, die gespeichert werden sollen. */
    public static final String PERSIST_QUEUE = "chat.persist";

    /** Das Abstellgleis für Nachrichten, die in sich kaputt sind. */
    public static final String DEAD_LETTER_QUEUE = "chat.dlq";

    /** Diese Klasse ist eine reine Namenssammlung und wird nie erzeugt. */
    private QueueNames() {
    }
}
