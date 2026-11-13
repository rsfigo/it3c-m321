package ch.benedict.m321.chatservice.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

/**
 * Schickt Nachrichten per POST /messages und liest Felder aus dem JSON
 * am Stream — die beiden Dinge, die jeder Zustellungs-Test braucht.
 */
public final class TestMessages {

    /** HTTP/1.1 wie bei curl in der Abnahme. */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** Nur zum Lesen einzelner Felder aus dem JSON am Stream-Client. */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Reine Sammlung von Hilfsmethoden, wird nie erzeugt. */
    private TestMessages() {
    }

    /**
     * Schickt eine Nachricht wie ein echter Absender und gibt den
     * Statuscode zurück. Erwartet ist 202. Der Aufruf wartet auf die
     * Antwort, genau wie der Absender in Spezifikation 3.2.
     */
    public static int send(int port, UUID roomId, String senderId, String content) throws Exception {
        String json = "{\"roomId\":\"" + roomId + "\",\"senderId\":\"" + senderId
                + "\",\"senderName\":\"" + senderId + "\",\"content\":\"" + content + "\"}";
        URI uri = URI.create("http://localhost:" + port + "/messages");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    /** Liest ein Textfeld, zum Beispiel "senderId" oder "content", aus dem JSON einer data:-Zeile. */
    public static String field(String json, String fieldName) throws Exception {
        JsonNode node = OBJECT_MAPPER.readTree(json);
        JsonNode field = node.get(fieldName);
        return field.asText();
    }
}
