package ch.benedict.m321.chatservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Prüft, dass der Spring-Kontext überhaupt hochfährt.
 * Ein RabbitMQ wird dafür nicht gebraucht: die Verbindung wird erst
 * beim ersten Senden aufgebaut, nicht beim Start.
 */
@SpringBootTest
class ChatServiceApplicationTest {

    /** Schlägt fehl, sobald eine Bean oder eine Einstellung fehlt — noch bevor ein anderer Test etwas Genaueres prüft. */
    @Test
    void contextLoads() {
        // Kein Assert nötig. Fährt der Kontext nicht hoch, wirft Spring
        // eine Exception und der Test wird rot.
    }
}
