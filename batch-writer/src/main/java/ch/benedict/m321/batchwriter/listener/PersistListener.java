package ch.benedict.m321.batchwriter.listener;

import ch.benedict.m321.batchwriter.config.QueueNames;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import ch.benedict.m321.batchwriter.service.InvalidMessageException;
import ch.benedict.m321.batchwriter.service.MessageReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Nimmt die Stapel aus chat.persist entgegen und entscheidet für jede
 * Nachricht: speichern oder, wenn sie kaputt ist, ab nach chat.dlq.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PersistListener {

    /** In diesem Header steht in chat.dlq, warum die Nachricht dort liegt. */
    private static final String REJECTED_REASON_HEADER = "x-rejected-reason";

    private final MessageReader messageReader;
    private final MessageRepository messageRepository;
    private final RabbitTemplate rabbitTemplate;

    /**
     * Verarbeitet einen ganzen Stapel.
     *
     * Kehrt die Methode ohne Ausnahme zurück, bestätigt Spring AMQP ALLE
     * Nachrichten des Stapels bei RabbitMQ. Das passiert also erst nach dem
     * COMMIT in insertBatch — vorher ist nichts bestätigt, und bei einem
     * Absturz liefert RabbitMQ den Stapel erneut (Spezifikation 3.1, 3.6).
     */
    @RabbitListener(queues = QueueNames.PERSIST_QUEUE, containerFactory = "batchListenerContainerFactory")
    public void onBatch(List<Message> messages) {
        List<ChatMessage> validMessages = new ArrayList<>();
        int deadLettered = 0;

        for (Message message : messages) {
            try {
                ChatMessage chatMessage = messageReader.read(message);
                validMessages.add(chatMessage);
            } catch (InvalidMessageException exception) {
                sendToDeadLetterQueue(message, exception.getMessage());
                deadLettered = deadLettered + 1;
            }
        }

        int stored = 0;
        if (!validMessages.isEmpty()) {
            stored = messageRepository.insertBatch(validMessages);
        }

        int duplicates = validMessages.size() - stored;
        log.info("Batch of {} messages: {} stored, {} duplicates, {} dead-lettered",
                messages.size(), stored, duplicates, deadLettered);
    }

    /**
     * Legt eine kaputte Nachricht unverändert nach chat.dlq, mit dem Grund im
     * Header.
     *
     * Sofort und nicht erst nach drei Versuchen: der Fehler liegt in der
     * Nachricht selbst, ein weiterer Versuch ergäbe dasselbe. Bliebe sie im
     * Stapel, würde sie jeden Versuch scheitern lassen (Spezifikation 3.7).
     */
    private void sendToDeadLetterQueue(Message message, String reason) {
        MessageProperties properties = message.getMessageProperties();
        properties.setHeader(REJECTED_REASON_HEADER, reason);
        // Persistent, damit die Nachricht auch in chat.dlq einen Neustart des Brokers übersteht.
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);

        rabbitTemplate.send("", QueueNames.DEAD_LETTER_QUEUE, message);
        log.warn("Message moved to {}: {}", QueueNames.DEAD_LETTER_QUEUE, reason);
    }
}
