package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.IntegrationTestBase;
import ch.benedict.m321.batchwriter.config.QueueNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Szenario S7 an echter Queue und echter Datenbank: die Datenbank fällt aus,
 * während Nachrichten ankommen. Danach muss jede Nachricht in der Tabelle
 * stehen, ohne dass jemand den Dienst neu startet (Spezifikation 3.5).
 */
class DatabaseOutageIntegrationTest extends IntegrationTestBase {

    private static final String SENDER = "outage-s7";

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    /** Leere Queues und Tabelle, damit nur die Nachrichten dieses Tests zählen. */
    @BeforeEach
    void cleanUp() {
        rabbitAdmin.purgeQueue(QueueNames.PERSIST_QUEUE);
        rabbitAdmin.purgeQueue(QueueNames.DEAD_LETTER_QUEUE);
        jdbcTemplate.update("DELETE FROM message");
    }

    /**
     * 300 Nachrichten kommen an, während die Datenbank 15 s lang weg ist.
     * Während des Ausfalls bleiben alle in RabbitMQ und keine geht nach
     * chat.dlq. Danach sind alle 300 gespeichert und der Verbraucher läuft
     * noch.
     */
    @Test
    void keepsMessagesUntilDatabaseIsBack() throws Exception {
        blockDatabase();
        try {
            for (int i = 1; i <= 300; i++) {
                String json = createJson(UUID.randomUUID(), SENDER, "Nachricht " + i);
                sendToPersistQueue(json);
            }

            Thread.sleep(15000);

            assertEquals(300, countMessagesIn(QueueNames.PERSIST_QUEUE));
            assertEquals(0, countMessagesIn(QueueNames.DEAD_LETTER_QUEUE));
        } finally {
            unblockDatabase();
        }

        waitForRows(SENDER, 300, 90);
        waitForQueue(QueueNames.PERSIST_QUEUE, 0, 10);
        assertEquals(0, countMessagesIn(QueueNames.DEAD_LETTER_QUEUE));
        assertListenerStillRunning();
    }

    /**
     * Macht die Datenbank für den Dienst unerreichbar: bestehende Verbindungen
     * werden beendet, neue abgewiesen. Genau das sieht der Dienst auch, wenn
     * der Container postgres gestoppt wird.
     *
     * Warum nicht den Container stoppen? Docker würde beim Neustart einen
     * neuen zufälligen Port vergeben, und die Adresse des Dienstes stimmte
     * nicht mehr. Im Stack (Szenario S7) gibt es dieses Problem nicht.
     */
    private void blockDatabase() throws SQLException {
        String databaseName = POSTGRES.getDatabaseName();
        executeAsAdministrator("ALTER DATABASE " + databaseName + " ALLOW_CONNECTIONS false");
        executeAsAdministrator("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '"
                + databaseName + "'");
    }

    /** Lässt wieder Verbindungen zu: die Datenbank ist zurück. */
    private void unblockDatabase() throws SQLException {
        String databaseName = POSTGRES.getDatabaseName();
        executeAsAdministrator("ALTER DATABASE " + databaseName + " ALLOW_CONNECTIONS true");
    }

    /**
     * Führt einen Befehl über die Verwaltungsdatenbank "postgres" aus. Über
     * die eigentliche Datenbank ginge es nicht: zu der lassen wir ja gerade
     * keine Verbindungen mehr zu.
     */
    private void executeAsAdministrator(String sql) throws SQLException {
        String administrationUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/postgres";
        try (Connection connection = DriverManager.getConnection(
                administrationUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Der Dienst darf beim Ausfall nicht stehen bleiben: der Verbraucher läuft noch. */
    private void assertListenerStillRunning() {
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            assertTrue(container.isRunning());
        }
    }
}
