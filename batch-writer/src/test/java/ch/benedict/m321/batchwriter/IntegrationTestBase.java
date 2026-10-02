package ch.benedict.m321.batchwriter;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

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

    static {
        RABBIT_MQ.start();
        POSTGRES.start();
    }

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
}
