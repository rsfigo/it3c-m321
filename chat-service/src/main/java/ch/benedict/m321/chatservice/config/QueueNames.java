package ch.benedict.m321.chatservice.config;

/**
 * Die Namen der Queues und des Exchange an genau EINER Stelle.
 *
 * Sie werden an drei Orten gebraucht — beim Anlegen, beim Senden und im
 * Test. Ein Tippfehler in einem String wäre sonst erst zur Laufzeit
 * sichtbar, und zwar als "die Nachricht kommt nie an".
 */
public final class QueueNames {

    /** Schreibweg: hier holt der batch-writer die Nachrichten ab. */
    public static final String PERSIST_QUEUE = "chat.persist";

    /** Zustellweg: fanout an alle Instanzen des chat-service. */
    public static final String DELIVERY_EXCHANGE = "chat.delivery";

    /**
     * Anfang des Namens jeder Instanz-Queue. Dahinter folgt eine UUID, die
     * jede Instanz beim Start selbst erzeugt (Spezifikation 4). Am Präfix
     * erkennt man die Instanz-Queues in "rabbitmqctl list_queues" sofort.
     */
    public static final String DELIVERY_QUEUE_PREFIX = "chat.delivery.";

    /**
     * So viele Nachrichten dürfen in einer Instanz-Queue warten. Zusammen
     * mit dem prefetch ergibt das höchstens 950 + 50 = 1000 (Szenario S6).
     */
    public static final int DELIVERY_QUEUE_MAX_LENGTH = 950;

    /** So viele unbestätigte Nachrichten darf eine Instanz gleichzeitig halten. */
    public static final int DELIVERY_PREFETCH = 50;

    /** Dead Letter: was der batch-writer endgültig nicht verarbeiten kann. */
    public static final String DEAD_LETTER_QUEUE = "chat.dlq";

    /** Diese Klasse ist eine reine Namenssammlung und wird nie erzeugt. */
    private QueueNames() {
    }
}
