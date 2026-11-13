package ch.benedict.m321.chatservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft die Instanz-Queue so, wie RabbitMQ sie selbst sieht
 * (Spezifikation 4). Gefragt wird rabbitmqctl im Container — dasselbe
 * Werkzeug, mit dem die Abnahme in S4 und S6 misst.
 */
@SpringBootTest
@Testcontainers
class DeliveryQueueIntegrationTest {

    /** Ein echter Broker, weil Queue-Eigenschaften nur im Broker existieren. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private Queue deliveryQueue;

    /** Der Name beginnt mit dem Präfix, damit man die Instanz-Queues erkennt. */
    @Test
    void queueNameStartsWithPrefix() {
        String queueName = deliveryQueue.getName();

        assertTrue(queueName.startsWith(QueueNames.DELIVERY_QUEUE_PREFIX), queueName);
    }

    /**
     * Nicht durable, exclusive, auto-delete, Grenze 950 mit drop-head und
     * genau ein Consumer. Eine Zeile von rabbitmqctl enthält alle diese Werte.
     */
    @Test
    void queueHasPropertiesFromSpecification() throws Exception {
        String line = findLine("list_queues", deliveryQueue.getName(),
                "name", "durable", "exclusive", "auto_delete", "consumers", "arguments");

        String[] columns = line.split("\t");
        assertEquals("false", columns[1], "durable");
        assertEquals("true", columns[2], "exclusive");
        assertEquals("true", columns[3], "auto_delete");
        assertEquals("1", columns[4], "consumers");
        assertTrue(columns[5].contains("950"), columns[5]);
        assertTrue(columns[5].contains("drop-head"), columns[5]);
    }

    /**
     * Ohne Bindung an den Fanout-Exchange käme nie eine Kopie an. Jede Queue
     * hat zusätzlich eine Bindung an den Standard-Exchange (Quelle leer),
     * darum suchen wir genau die Zeile mit chat.delivery als Quelle.
     */
    @Test
    void queueIsBoundToDeliveryExchange() throws Exception {
        ExecResult result = rabbitMq.execInContainer(
                "rabbitmqctl", "-q", "list_bindings", "source_name", "destination_name");
        String expectedLine = QueueNames.DELIVERY_EXCHANGE + "\t" + deliveryQueue.getName();

        assertTrue(result.getStdout().contains(expectedLine), result.getStdout());
    }

    /**
     * Ruft rabbitmqctl im Container auf und gibt die Zeile zurück, die den
     * gesuchten Queue-Namen enthält. Die Spalten trennt rabbitmqctl mit Tabs.
     */
    private String findLine(String command, String queueName, String... columns) throws Exception {
        String[] arguments = new String[columns.length + 3];
        arguments[0] = "rabbitmqctl";
        arguments[1] = "-q";
        arguments[2] = command;
        for (int i = 0; i < columns.length; i++) {
            arguments[i + 3] = columns[i];
        }

        ExecResult result = rabbitMq.execInContainer(arguments);
        String[] lines = result.getStdout().split("\n");
        for (String line : lines) {
            if (line.contains(queueName)) {
                return line;
            }
        }
        throw new AssertionError("Queue not found in rabbitmqctl " + command + ": " + result.getStdout());
    }
}
