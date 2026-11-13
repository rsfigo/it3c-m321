package ch.benedict.m321.chatservice.controller;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Ist RabbitMQ nicht erreichbar, darf der Benutzer keine 500 sehen und
 * schon gar kein stilles "ok". 503 heisst: nimm es nochmal, später.
 */
class MessageExceptionHandlerTest {

    /** Sichert ab, dass ein Broker-Ausfall als 503 mit Begründung beim Aufrufer ankommt, nicht als 500. */
    @Test
    void answersWithServiceUnavailable() {
        MessageExceptionHandler handler = new MessageExceptionHandler();
        AmqpException exception = new AmqpException("Broker nicht erreichbar");

        ResponseEntity<String> response = handler.handleBrokerNotAvailable(exception);

        assertEquals(503, response.getStatusCode().value());
        assertNotNull(response.getBody());
    }
}
