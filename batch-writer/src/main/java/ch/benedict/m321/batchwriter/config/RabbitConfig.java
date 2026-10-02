package ch.benedict.m321.batchwriter.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Alles, was der batch-writer am Broker braucht: die beiden Queues und einen
 * Verbraucher, der Nachrichten zu Stapeln sammelt.
 */
@Configuration
public class RabbitConfig {

    /** Höchstens so viele Nachrichten gehen in einen Stapel (PLANUNG.md 3.6). */
    public static final int BATCH_SIZE = 500;

    /** So lange wird auf weitere Nachrichten gewartet, bevor ein halber Stapel losgeht. */
    public static final long BATCH_TIMEOUT_MILLIS = 200;

    /**
     * Die Queue, aus der wir lesen — mit GENAU den Argumenten, die auch der
     * chat-service verwendet. Weichen sie ab, lehnt RabbitMQ die zweite
     * Deklaration mit PRECONDITION_FAILED ab. Wir deklarieren sie trotzdem
     * selbst, damit der batch-writer auch startet, wenn der chat-service noch
     * nicht lief (Spezifikation 2.1).
     */
    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(QueueNames.PERSIST_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE)
                .build();
    }

    /** Das Abstellgleis, ebenfalls wie im chat-service. */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }

    /**
     * Der Verbraucher, der Stapel bildet.
     *
     * - batchListener + consumerBatchEnabled: die Listener-Methode bekommt
     *   eine ganze Liste statt einer einzelnen Nachricht.
     * - batchSize 500 oder batchReceiveTimeout 200 ms: was zuerst eintritt,
     *   schliesst den Stapel.
     * - prefetch 500: RabbitMQ darf uns so viele unbestätigte Nachrichten
     *   schicken. Wäre der Wert kleiner als der Stapel, entstünde nie ein
     *   voller Stapel.
     * - ein Verbraucher pro Instanz: skaliert wird über weitere Instanzen
     *   (docker compose --scale), nicht über Threads.
     *
     * Bestätigt wird automatisch: kehrt die Listener-Methode ohne Ausnahme
     * zurück, gehen die ACKs für den ganzen Stapel an RabbitMQ.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory batchListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setBatchListener(true);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(BATCH_SIZE);
        factory.setBatchReceiveTimeout(BATCH_TIMEOUT_MILLIS);
        factory.setPrefetchCount(BATCH_SIZE);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        return factory;
    }
}
