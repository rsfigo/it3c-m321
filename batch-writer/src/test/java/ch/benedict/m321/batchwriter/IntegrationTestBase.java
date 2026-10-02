package ch.benedict.m321.batchwriter;

import ch.benedict.m321.batchwriter.config.QueueNames;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Gemeinsame Grundlage aller Integrationstests: eine echte RabbitMQ und eine
 * echte PostgreSQL, beide in Containern.
 *
 * Die Container starten EINMAL für den ganzen Testlauf und nicht für jede
 * Testklasse neu. Das spart pro Klasse etliche Sekunden, und Spring kann
 * denselben Anwendungskontext für alle Integrationstests wiederverwenden.
 * Aufgeräumt wird am Ende des Laufs von Testcontainers selbst.
 */
@SpringBootTest
public abstract class IntegrationTestBase {

    protected static final RabbitMQContainer RABBIT_MQ = new RabbitMQContainer("rabbitmq:3.13-management");

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    /** Wie oft beim Warten nachgeschaut wird: alle 500 ms. */
    private static final int POLL_INTERVAL_MILLIS = 500;

    static {
        RABBIT_MQ.start();
        POSTGRES.start();
    }

    @Autowired
    protected RabbitTemplate rabbitTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * Trägt Adresse und Zugangsdaten der Container in die Konfiguration ein.
     *
     * Die Ports vergibt Docker zufällig, deshalb können sie nicht fest in
     * application.yml stehen.
     */
    @DynamicPropertySource
    static void registerContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT_MQ::getHost);
        registry.add("spring.rabbitmq.port", RABBIT_MQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT_MQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT_MQ::getAdminPassword);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Baut den JSON-Körper einer Nachricht im Format des chat-service
     * (Spezifikation 2.2). Die senderId trennt die Nachrichten der Tests.
     */
    protected String createJson(UUID id, String senderId, String content) {
        String sentAt = Instant.now().toString();
        return "{\"id\":\"" + id + "\","
                + "\"roomId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"senderId\":\"" + senderId + "\","
                + "\"senderName\":\"" + senderId + "\","
                + "\"content\":\"" + content + "\","
                + "\"sentAt\":\"" + sentAt + "\"}";
    }

    /**
     * Legt einen Text direkt in chat.persist, nur mit dem Header content_type —
     * genau so, wie Szenario S5 es tut.
     */
    protected void sendToPersistQueue(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        Message message = new Message(bodyBytes, properties);
        rabbitTemplate.send("", QueueNames.PERSIST_QUEUE, message);
    }

    /** Zählt die gespeicherten Zeilen eines Absenders. */
    protected int countRowsOf(String senderId) {
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM message WHERE sender_id = ?", Integer.class, senderId);
        return rows;
    }

    /**
     * Zählt die Nachrichten in einer Queue, so wie es die Bewertung mit
     * rabbitmqctl tut. "messages" enthält auch die zugestellten, aber noch
     * nicht bestätigten Nachrichten — leer heisst also wirklich: alles bestätigt.
     */
    protected int countMessagesIn(String queueName) throws Exception {
        Container.ExecResult result = RABBIT_MQ.execInContainer(
                "rabbitmqctl", "list_queues", "--quiet", "--no-table-headers", "name", "messages");
        String[] lines = result.getStdout().split("\n");
        for (String line : lines) {
            String[] columns = line.trim().split("\\s+");
            if (columns.length == 2 && columns[0].equals(queueName)) {
                return Integer.parseInt(columns[1]);
            }
        }
        throw new IllegalStateException("Queue not found: " + queueName);
    }

    /**
     * Wartet, bis ein Absender die erwartete Zahl Zeilen hat, höchstens
     * maxSeconds lang. Der batch-writer arbeitet in einem eigenen Thread,
     * das Ergebnis ist also nicht sofort da.
     */
    protected void waitForRows(String senderId, int expectedRows, int maxSeconds) throws InterruptedException {
        int attempts = maxSeconds * 1000 / POLL_INTERVAL_MILLIS;
        for (int i = 0; i < attempts; i++) {
            int rows = countRowsOf(senderId);
            if (rows == expectedRows) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        assertEquals(expectedRows, countRowsOf(senderId), "Zeilen von " + senderId + " nach " + maxSeconds + " s");
    }

    /** Wartet, bis eine Queue die erwartete Zahl Nachrichten hat, höchstens maxSeconds lang. */
    protected void waitForQueue(String queueName, int expectedMessages, int maxSeconds) throws Exception {
        int attempts = maxSeconds * 1000 / POLL_INTERVAL_MILLIS;
        for (int i = 0; i < attempts; i++) {
            int messages = countMessagesIn(queueName);
            if (messages == expectedMessages) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        assertEquals(expectedMessages, countMessagesIn(queueName), "Nachrichten in " + queueName + " nach " + maxSeconds + " s");
    }
}
