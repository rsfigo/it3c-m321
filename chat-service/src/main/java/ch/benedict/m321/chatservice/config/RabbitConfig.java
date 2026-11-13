package ch.benedict.m321.chatservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * Legt beim Start alles an, was der Broker braucht.
 *
 * Spring meldet diese Beans beim Verbindungsaufbau an RabbitMQ. Existiert
 * eine Queue schon, passiert nichts — das Anlegen ist wiederholbar.
 */
@Configuration
public class RabbitConfig {

    /**
     * Der Schreibweg. "durable" heisst: die Queue überlebt einen Neustart
     * des Brokers. Was der Verbraucher endgültig ablehnt, landet über den
     * Standard-Exchange ("") in der Dead-Letter-Queue.
     */
    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(QueueNames.PERSIST_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE)
                .build();
    }

    /** Das Abstellgleis für Nachrichten, die niemand verarbeiten konnte. */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }

    /**
     * Der Zustellweg. Fanout heisst: JEDE gebundene Queue bekommt eine
     * Kopie. Das ist genau richtig, weil jede Instanz des chat-service jede
     * Nachricht sehen muss — nur sie weiss, welche ihrer Stream-Clients
     * im betroffenen Raum sitzen.
     */
    @Bean
    public FanoutExchange deliveryExchange() {
        return new FanoutExchange(QueueNames.DELIVERY_EXCHANGE, true, false);
    }

    /**
     * Die eigene Queue dieser Instanz (Spezifikation 4).
     *
     * Die UUID entsteht genau einmal, wenn Spring diese Bean anlegt. Nach
     * einem Verbindungsabbruch deklariert Spring die Queue darum wieder
     * unter demselben Namen. Nicht durable, exclusive und auto-delete:
     * Die Queue gehört nur dieser Instanz und verschwindet mit ihrer
     * Verbindung. Die Grenze mit drop-head wirft die älteste Nachricht
     * weg, damit eine hängende Instanz RabbitMQ nicht füllt (Szenario S6).
     */
    @Bean
    public Queue deliveryQueue() {
        String queueName = QueueNames.DELIVERY_QUEUE_PREFIX + UUID.randomUUID();
        return QueueBuilder.nonDurable(queueName)
                .exclusive()
                .autoDelete()
                .maxLength(QueueNames.DELIVERY_QUEUE_MAX_LENGTH)
                .overflow(QueueBuilder.Overflow.dropHead)
                .build();
    }

    /**
     * Bindet die Instanz-Queue an den Fanout-Exchange. Erst dadurch legt
     * RabbitMQ eine Kopie jeder Nachricht in diese Queue.
     *
     * Die Aufrufe der beiden Bean-Methoden erzeugen keine neuen Objekte:
     * In einer @Configuration-Klasse gibt Spring jedes Mal dieselbe Bean
     * zurück.
     */
    @Bean
    public Binding deliveryBinding() {
        Queue queue = deliveryQueue();
        FanoutExchange exchange = deliveryExchange();
        return BindingBuilder.bind(queue).to(exchange);
    }

    /**
     * Wie der Listener die Instanz-Queue liest.
     *
     * Genau ein Consumer, damit die Reihenfolge erhalten bleibt und S4
     * genau einen Consumer pro Queue sieht. Der prefetch ist Teil der
     * Grenze aus S6. Eine Nachricht, die der Listener nicht verarbeiten
     * kann, wird verworfen statt endlos neu zugestellt — sie ist nur eine
     * Anzeige-Kopie, das Original liegt in chat.persist.
     *
     * Der Configurer übernimmt zuerst die Grundeinstellungen von Spring
     * Boot. Den JSON-Konverter ersetzen wir danach durch den einfachen
     * Konverter: Der Listener will das JSON roh weitergeben, und der
     * JSON-Konverter würde vorher eine ChatMessage daraus bauen wollen.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(new SimpleMessageConverter());
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(QueueNames.DELIVERY_PREFETCH);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    /**
     * Nachrichten gehen als JSON über die Leitung, nicht als serialisiertes
     * Java-Objekt. Nur so kann später ein Dienst in einer anderen Sprache
     * mitlesen.
     *
     * Der ObjectMapper kommt von Spring Boot und kann bereits Instant im
     * ISO-8601-Format schreiben.
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
