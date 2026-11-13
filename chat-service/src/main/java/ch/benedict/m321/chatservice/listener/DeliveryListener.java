package ch.benedict.m321.chatservice.listener;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.dto.LiveMessage;
import ch.benedict.m321.chatservice.service.LiveDelivery;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Liest die eigene Queue dieser Instanz und gibt jede Nachricht an
 * LiveDelivery weiter (Spezifikation 3.1).
 *
 * Weil der Listener schon beim Start an der Queue hängt, baut der
 * chat-service seine Verbindung zu RabbitMQ sofort auf. Dabei legt Spring
 * auch die Queue und ihre Bindung an — nicht erst beim ersten POST.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryListener {

    /** Der ObjectMapper von Spring Boot: kann UUID und Instant. */
    private final ObjectMapper objectMapper;

    private final LiveDelivery liveDelivery;

    /**
     * Nimmt eine Kopie vom Fanout-Exchange entgegen.
     *
     * Der Name der Queue entsteht erst beim Start. "#{deliveryQueue.name}"
     * fragt ihn bei der Bean deliveryQueue aus RabbitConfig ab. Wir nehmen
     * die rohe Nachricht statt einer ChatMessage, damit wir das JSON
     * unverändert an den Stream weitergeben können.
     */
    @RabbitListener(queues = "#{deliveryQueue.name}")
    public void onMessage(Message message) {
        String json = new String(message.getBody(), StandardCharsets.UTF_8);
        ChatMessage chatMessage = read(json);
        if (chatMessage == null) {
            return;
        }

        LiveMessage liveMessage = new LiveMessage(chatMessage.id(), chatMessage.roomId(), json);
        liveDelivery.deliver(liveMessage);
    }

    /**
     * Liest id und roomId aus dem JSON. Ist die Nachricht kaputt, wird sie
     * nur protokolliert und verworfen: Es ist eine Anzeige-Kopie, das
     * Original liegt in chat.persist.
     */
    private ChatMessage read(String json) {
        ChatMessage chatMessage;
        try {
            chatMessage = objectMapper.readValue(json, ChatMessage.class);
        } catch (JsonProcessingException exception) {
            log.warn("Dropping unreadable message from delivery queue: {}", exception.getOriginalMessage());
            return null;
        }

        if (chatMessage == null || chatMessage.id() == null || chatMessage.roomId() == null) {
            log.warn("Dropping message without id or roomId from delivery queue: {}", json);
            return null;
        }
        return chatMessage;
    }
}
