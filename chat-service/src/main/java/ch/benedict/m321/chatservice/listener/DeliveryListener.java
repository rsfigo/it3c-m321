package ch.benedict.m321.chatservice.listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Liest die eigene Queue dieser Instanz (Spezifikation 3.1).
 *
 * Weil der Listener schon beim Start an der Queue hängt, baut der
 * chat-service seine Verbindung zu RabbitMQ sofort auf. Dabei legt Spring
 * auch die Queue und ihre Bindung an — nicht erst beim ersten POST.
 */
@Slf4j
@Component
public class DeliveryListener {

    /**
     * Nimmt eine Kopie vom Fanout-Exchange entgegen.
     *
     * Der Name der Queue entsteht erst beim Start. "#{deliveryQueue.name}"
     * fragt ihn bei der Bean deliveryQueue aus RabbitConfig ab.
     */
    @RabbitListener(queues = "#{deliveryQueue.name}")
    public void onMessage(Message message) {
        String json = new String(message.getBody(), StandardCharsets.UTF_8);
        log.debug("Received message for live delivery: {}", json);
    }
}
