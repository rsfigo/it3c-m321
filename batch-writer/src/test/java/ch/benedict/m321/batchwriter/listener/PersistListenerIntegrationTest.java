package ch.benedict.m321.batchwriter.listener;

import ch.benedict.m321.batchwriter.IntegrationTestBase;
import ch.benedict.m321.batchwriter.config.QueueNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Der ganze Weg an echter Queue und echter Datenbank: Nachricht in
 * chat.persist, Zeile in der Tabelle, Queue leer (Spezifikation 3.1, 3.3, 3.7).
 */
class PersistListenerIntegrationTest extends IntegrationTestBase {

    @Autowired
    private RabbitAdmin rabbitAdmin;

    /**
     * Queues und Tabelle sind gemeinsamer Zustand aller Tests. Ohne Aufräumen
     * würde ein Test die Nachrichten des vorigen mitzählen.
     */
    @BeforeEach
    void cleanUp() {
        rabbitAdmin.purgeQueue(QueueNames.PERSIST_QUEUE);
        rabbitAdmin.purgeQueue(QueueNames.DEAD_LETTER_QUEUE);
        jdbcTemplate.update("DELETE FROM message");
    }

    /**
     * Der chat-service deklariert chat.persist beim Start mit seinen
     * Argumenten. Weichen unsere ab, lehnt RabbitMQ seine Deklaration mit
     * PRECONDITION_FAILED ab. Dieser Test deklariert genau so wie der
     * chat-service — wirft er nicht, stimmen die Argumente überein.
     */
    @Test
    void declaresQueuesWithChatServiceArguments() {
        Queue queueAsChatServiceDeclaresIt = QueueBuilder.durable("chat.persist")
                .deadLetterExchange("")
                .deadLetterRoutingKey("chat.dlq")
                .build();
        Queue deadLetterQueueAsChatServiceDeclaresIt = QueueBuilder.durable("chat.dlq").build();

        rabbitAdmin.declareQueue(queueAsChatServiceDeclaresIt);
        rabbitAdmin.declareQueue(deadLetterQueueAsChatServiceDeclaresIt);
    }

    /** S3 im Kleinen: 1000 Nachrichten sind gespeichert, und die Queue ist leer. */
    @Test
    void storesThousandMessagesAndEmptiesQueue() throws Exception {
        for (int i = 1; i <= 1000; i++) {
            String json = createJson(UUID.randomUUID(), "listener-s3", "Nachricht " + i);
            sendToPersistQueue(json);
        }

        waitForRows("listener-s3", 1000, 60);
        waitForQueue(QueueNames.PERSIST_QUEUE, 0, 10);
    }

    /**
     * S5: dieselbe Nachricht zweimal direkt in die Queue, nur mit
     * content_type. Ergebnis: genau eine Zeile, nichts in chat.dlq.
     */
    @Test
    void storesDuplicateOnlyOnceAndNothingInDeadLetterQueue() throws Exception {
        String json = createJson(UUID.randomUUID(), "listener-s5", "Duplikat");

        sendToPersistQueue(json);
        sendToPersistQueue(json);

        waitForQueue(QueueNames.PERSIST_QUEUE, 0, 30);
        assertEquals(1, countRowsOf("listener-s5"));
        assertEquals(0, countMessagesIn(QueueNames.DEAD_LETTER_QUEUE));
    }

    /**
     * Eine kaputte Nachricht geht nach chat.dlq, mit Grund im Header. Die
     * gültige Nachricht im selben Stapel wird trotzdem gespeichert.
     */
    @Test
    void movesBrokenMessageToDeadLetterQueue() throws Exception {
        String validJson = createJson(UUID.randomUUID(), "listener-dlq", "gültig");

        sendToPersistQueue("das ist kein JSON");
        sendToPersistQueue(validJson);

        waitForRows("listener-dlq", 1, 30);
        waitForQueue(QueueNames.DEAD_LETTER_QUEUE, 1, 10);

        Message deadLetter = rabbitTemplate.receive(QueueNames.DEAD_LETTER_QUEUE, 5000);
        assertNotNull(deadLetter);
        Object reason = deadLetter.getMessageProperties().getHeader("x-rejected-reason");
        assertNotNull(reason);
    }
}
