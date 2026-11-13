package ch.benedict.m321.chatservice;

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
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Szenario S7: Ein Client trennt sich, verpasst 50 Nachrichten und
 * verbindet sich mit Last-Event-ID neu (Spezifikation 3.6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReconnectIntegrationTest {

    /** Die Nachrichten kommen über den Fanout-Exchange, also braucht es einen echten Broker. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @LocalServerPort
    private int port;

    /**
     * Genau die 50 verpassten Nachrichten, in Reihenfolge, keine doppelt,
     * die letzte gesehene nicht noch einmal, kein gap — und danach geht es
     * live weiter.
     */
    @Test
    void reconnectGetsExactlyTheMissedMessagesThenLive() throws Exception {
        UUID roomId = UUID.randomUUID();
        String lastEventId = receiveOneMessageAndDisconnect(roomId);

        for (int i = 1; i <= 50; i++) {
            int status = TestMessages.send(port, roomId, "s7", String.valueOf(i));
            assertEquals(202, status);
        }

        try (SseTestClient client = SseTestClient.connect(port, roomId, lastEventId)) {
            TestMessages.send(port, roomId, "s7live", "51");
            client.waitForDataLines(51, Duration.ofSeconds(10));
            Thread.sleep(300);

            List<String> dataLines = client.dataLines();
            assertEquals(51, dataLines.size());
            for (int i = 0; i < 50; i++) {
                String json = dataLines.get(i);
                assertEquals("s7", TestMessages.field(json, "senderId"));
                assertEquals(String.valueOf(i + 1), TestMessages.field(json, "content"));
            }
            assertEquals("s7live", TestMessages.field(dataLines.get(50), "senderId"));
            assertFalse(client.lines().contains("event:gap"));
        }
    }

    /**
     * Eine ID, die nicht im Puffer steht, ergibt das Ereignis gap mit der
     * ID in data — der Client erfährt so von der möglichen Lücke.
     */
    @Test
    void unknownLastEventIdGetsGap() throws Exception {
        String unknownId = UUID.randomUUID().toString();

        try (SseTestClient client = SseTestClient.connect(port, UUID.randomUUID(), unknownId)) {
            client.waitForLine("event:gap", Duration.ofSeconds(10));

            assertEquals(List.of(unknownId), client.dataLines());
        }
    }

    /**
     * Verbindet sich, empfängt eine Nachricht, merkt sich ihre ID und
     * trennt sich wieder — wie ein Browser, dessen Verbindung abreisst.
     */
    private String receiveOneMessageAndDisconnect(UUID roomId) throws Exception {
        try (SseTestClient client = SseTestClient.connect(port, roomId, null)) {
            TestMessages.send(port, roomId, "s7first", "0");
            client.waitForDataLines(1, Duration.ofSeconds(10));
            String json = client.dataLines().get(0);
            return TestMessages.field(json, "id");
        }
    }
}
