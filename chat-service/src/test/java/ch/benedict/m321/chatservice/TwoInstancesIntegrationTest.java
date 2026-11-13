package ch.benedict.m321.chatservice;

import ch.benedict.m321.chatservice.config.QueueNames;
import ch.benedict.m321.chatservice.support.SseTestClient;
import ch.benedict.m321.chatservice.support.TestMessages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Szenario S3: zwei Instanzen des chat-service am selben RabbitMQ, wie
 * mit "docker compose up --scale chat-service=2" (Spezifikation 3.2).
 *
 * Statt @SpringBootTest starten wir den Dienst zweimal selbst. Jede
 * Instanz hat ihren eigenen Webserver, ihre eigene Queue und ihren eigenen
 * Listener.
 */
@Testcontainers
class TwoInstancesIntegrationTest {

    /** Ein gemeinsamer Broker für beide Instanzen. */
    @Container
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    private static ConfigurableApplicationContext instanceA;

    private static ConfigurableApplicationContext instanceB;

    /** Startet beide Instanzen, nachdem der Container läuft. */
    @BeforeAll
    static void startTwoInstances() {
        instanceA = startInstance();
        instanceB = startInstance();
    }

    /** Fährt beide Instanzen wieder herunter. Ihre Queues verschwinden mit ihnen. */
    @AfterAll
    static void stopTwoInstances() {
        instanceA.close();
        instanceB.close();
    }

    /** S4 vorher: genau zwei Instanz-Queues, jede mit genau einem Consumer. */
    @Test
    void eachInstanceHasOwnQueueWithOneConsumer() throws Exception {
        ExecResult result = rabbitMq.execInContainer("rabbitmqctl", "-q", "list_queues", "name", "consumers");
        String[] lines = result.getStdout().split("\n");

        int deliveryQueues = 0;
        for (String line : lines) {
            if (line.startsWith(QueueNames.DELIVERY_QUEUE_PREFIX)) {
                deliveryQueues++;
                assertTrue(line.endsWith("\t1"), line);
            }
        }
        assertEquals(2, deliveryQueues, result.getStdout());
    }

    /**
     * Je 100 Nachrichten an jede Instanz. Beide Clients in Raum A — einer
     * an jeder Instanz — haben danach alle 200 genau einmal, pro Absender
     * in Reihenfolge. Der Client in Raum B hat nichts.
     */
    @Test
    void everyMessageArrivesOnceInOrderAndOnlyInItsRoom() throws Exception {
        int portA = portOf(instanceA);
        int portB = portOf(instanceB);
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();

        try (SseTestClient clientAtA = SseTestClient.connect(portA, roomA, null);
             SseTestClient clientAtB = SseTestClient.connect(portB, roomA, null);
             SseTestClient clientInRoomB = SseTestClient.connect(portA, roomB, null)) {
            for (int i = 1; i <= 100; i++) {
                assertEquals(202, TestMessages.send(portA, roomA, "s3a", String.valueOf(i)));
                assertEquals(202, TestMessages.send(portB, roomA, "s3b", String.valueOf(i)));
            }

            clientAtA.waitForDataLines(200, Duration.ofSeconds(20));
            clientAtB.waitForDataLines(200, Duration.ofSeconds(20));
            Thread.sleep(500);

            checkOnceAndInOrder(clientAtA.dataLines());
            checkOnceAndInOrder(clientAtB.dataLines());
            assertEquals(0, clientInRoomB.dataLines().size());
        }
    }

    /**
     * Genau 200 Nachrichten, keine ID doppelt, und pro Absender zählt der
     * Inhalt lückenlos von 1 aufwärts.
     */
    private void checkOnceAndInOrder(List<String> dataLines) throws Exception {
        assertEquals(200, dataLines.size());

        Set<String> seenIds = new HashSet<>();
        Map<String, Integer> lastContentBySender = new HashMap<>();
        for (String json : dataLines) {
            String id = TestMessages.field(json, "id");
            assertTrue(seenIds.add(id), "duplicate id " + id);

            String senderId = TestMessages.field(json, "senderId");
            int content = Integer.parseInt(TestMessages.field(json, "content"));
            int lastContent = lastContentBySender.getOrDefault(senderId, 0);
            assertEquals(lastContent + 1, content, "order of " + senderId);
            lastContentBySender.put(senderId, content);
        }
    }

    /**
     * Startet eine Instanz auf einem freien Port und verbindet sie mit dem
     * Container — so, wie Docker Compose es mit den Umgebungsvariablen tut.
     */
    private static ConfigurableApplicationContext startInstance() {
        SpringApplication application = new SpringApplication(ChatServiceApplication.class);
        return application.run(
                "--server.port=0",
                "--spring.rabbitmq.host=" + rabbitMq.getHost(),
                "--spring.rabbitmq.port=" + rabbitMq.getAmqpPort(),
                "--spring.rabbitmq.username=" + rabbitMq.getAdminUsername(),
                "--spring.rabbitmq.password=" + rabbitMq.getAdminPassword());
    }

    /** Der Port, den sich der Webserver der Instanz ausgesucht hat. */
    private static int portOf(ConfigurableApplicationContext instance) {
        WebServerApplicationContext webContext = (WebServerApplicationContext) instance;
        return webContext.getWebServer().getPort();
    }
}
