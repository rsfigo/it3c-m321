package ch.benedict.m321.batchwriter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des batch-writer.
 *
 * Der Dienst ist der einzige Schreiber in die Tabelle message. Er hat keine
 * HTTP-Schnittstelle und öffnet keinen Port: seine einzige Eingangstür ist
 * die Queue chat.persist.
 */
@SpringBootApplication
public class BatchWriterApplication {

    /** Startet Spring Boot mit allem, was in diesem Paket und darunter liegt. */
    public static void main(String[] args) {
        SpringApplication.run(BatchWriterApplication.class, args);
    }
}
