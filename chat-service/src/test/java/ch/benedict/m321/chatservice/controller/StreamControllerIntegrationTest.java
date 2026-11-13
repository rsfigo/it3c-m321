package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.support.SseTestClient;
import ch.benedict.m321.chatservice.support.TestMessages;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Der Stream von aussen gesehen: echter Webserver auf zufälligem Port,
 * echter RabbitMQ, echte HTTP-Verbindung (Spezifikation 2.2, 3.1, 3.2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class StreamControllerIntegrationTest {

    /** Der Weg geht über den Fanout-Exchange, also braucht es einen echten Broker. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @LocalServerPort
    private int port;

    /** Ohne roomId weiss der Server nicht, was er schicken soll: 400, kein Stream. */
    @Test
    void rejectsMissingRoomId() throws Exception {
        int status = SseTestClient.statusOf(port, "/stream");

        assertEquals(400, status);
    }

    /** Eine roomId, die keine UUID ist, lehnt Spring beim Umwandeln mit 400 ab. */
    @Test
    void rejectsRoomIdThatIsNotAUuid() throws Exception {
        int status = SseTestClient.statusOf(port, "/stream?roomId=abc");

        assertEquals(400, status);
    }

    /** Der Vertrag verlangt den Content-Type für Server-Sent Events. */
    @Test
    void answersWithEventStream() throws Exception {
        try (SseTestClient client = SseTestClient.connect(port, UUID.randomUUID(), null)) {
            assertTrue(client.contentType().startsWith("text/event-stream"), client.contentType());
        }
    }

    /**
     * Der Normalfall: POST, dann kommt genau diese Nachricht als Ereignis
     * an. "id:" ist die ID aus dem JSON, damit der Client sie bei einer
     * Neuverbindung zurückschicken kann.
     */
    @Test
    void deliversMessageToClientInSameRoom() throws Exception {
        UUID roomId = UUID.randomUUID();
        try (SseTestClient client = SseTestClient.connect(port, roomId, null)) {
            int status = TestMessages.send(port, roomId, "anna", "Hallo");
            client.waitForDataLines(1, Duration.ofSeconds(10));

            assertEquals(202, status);
            String json = client.dataLines().get(0);
            assertEquals("Hallo", TestMessages.field(json, "content"));
            String messageId = TestMessages.field(json, "id");
            List<String> lines = client.lines();
            assertEquals(":connected", lines.get(0));
            assertEquals("id:" + messageId, lines.get(1));
            assertEquals("data:" + json, lines.get(2));
        }
    }

    /**
     * Raumfilter: Der Client in Raum B bekommt nichts. Wir warten zuerst,
     * bis Raum A die Nachricht hat — dann wäre sie auch bei B schon da.
     */
    @Test
    void doesNotDeliverToOtherRoom() throws Exception {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        try (SseTestClient clientA = SseTestClient.connect(port, roomA, null);
             SseTestClient clientB = SseTestClient.connect(port, roomB, null)) {
            TestMessages.send(port, roomA, "anna", "nur für Raum A");
            clientA.waitForDataLines(1, Duration.ofSeconds(10));
            Thread.sleep(500);

            assertEquals(0, clientB.dataLines().size());
        }
    }
}
