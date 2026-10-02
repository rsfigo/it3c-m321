package ch.benedict.m321.batchwriter;

import org.junit.jupiter.api.Test;

/**
 * Der kleinste mögliche Beweis: die Anwendung startet gegen eine echte Queue
 * und eine echte Datenbank, ohne dass etwas fehlt.
 */
class BatchWriterApplicationTest extends IntegrationTestBase {

    /** Schlägt fehl, sobald eine Bean, eine Verbindung oder eine Einstellung fehlt. */
    @Test
    void contextLoads() {
    }
}
