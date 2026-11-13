package ch.benedict.m321.chatservice.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ein Stream-Client für die Tests: verbindet sich mit GET /stream und
 * sammelt jede empfangene Zeile, so wie curl -N in der Abnahme.
 *
 * Das Lesen läuft in einem eigenen Thread, weil ein Stream nie von selbst
 * endet. Der Test fragt die gesammelten Zeilen ab, während der Stream
 * weiterläuft.
 */
public final class SseTestClient implements AutoCloseable {

    private final HttpResponse<InputStream> response;

    /** Alle Zeilen, die nicht leer sind, in der Reihenfolge des Empfangs. */
    private final List<String> lines = new CopyOnWriteArrayList<>();

    /** Der Konstruktor ist privat, verbunden wird über connect(). */
    private SseTestClient(HttpResponse<InputStream> response) {
        this.response = response;
    }

    /**
     * Öffnet einen Stream für einen Raum. Mit lastEventId (darf null sein)
     * wird der Header Last-Event-ID mitgeschickt, wie bei einer
     * Neuverbindung des Browsers.
     *
     * Kehrt zurück, sobald die Antwort-Header da sind. Dann ist der Client
     * beim Server schon angemeldet.
     */
    public static SseTestClient connect(int port, UUID roomId, String lastEventId) throws Exception {
        URI uri = URI.create("http://localhost:" + port + "/stream?roomId=" + roomId);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri).GET();
        if (lastEventId != null) {
            requestBuilder.header("Last-Event-ID", lastEventId);
        }
        HttpRequest request = requestBuilder.build();

        HttpClient httpClient = newHttpClient();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        SseTestClient client = new SseTestClient(response);
        client.startReading();
        return client;
    }

    /** Fragt eine Adresse ab und gibt nur den Statuscode zurück, für die Fälle mit 400. */
    public static int statusOf(int port, String pathAndQuery) throws Exception {
        URI uri = URI.create("http://localhost:" + port + pathAndQuery);
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        HttpClient httpClient = newHttpClient();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    /**
     * Jeder Stream bekommt einen eigenen HttpClient, also eine eigene
     * Verbindung. Ein gemeinsamer Client hielt eine neue Anfrage zurück,
     * solange ein anderer Stream noch offen war. HTTP/1.1 wie bei curl.
     */
    private static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** Der Content-Type der Antwort, für den Vertrag text/event-stream. */
    public String contentType() {
        return response.headers().firstValue("Content-Type").orElse("");
    }

    /** Alle empfangenen Zeilen, zum Beispiel "id:...", "data:..." oder "event:gap". */
    public List<String> lines() {
        return new ArrayList<>(lines);
    }

    /** Nur der Inhalt der data:-Zeilen, also das JSON jeder Nachricht. */
    public List<String> dataLines() {
        List<String> dataLines = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("data:")) {
                dataLines.add(line.substring("data:".length()));
            }
        }
        return dataLines;
    }

    /**
     * Wartet, bis mindestens so viele data:-Zeilen da sind. Ein festes
     * sleep wäre entweder zu kurz und der Test wackelt, oder zu lang und
     * der Test ist langsam.
     */
    public void waitForDataLines(int count, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (dataLines().size() < count) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Expected " + count + " data lines, got " + dataLines().size());
            }
            Thread.sleep(50);
        }
    }

    /** Wartet, bis eine bestimmte Zeile da ist, zum Beispiel "event:gap". */
    public void waitForLine(String expectedLine, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (!lines.contains(expectedLine)) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Line not received: " + expectedLine + ", got " + lines);
            }
            Thread.sleep(50);
        }
    }

    /** Trennt die Verbindung, so wie ein Client, der weggeht. */
    @Override
    public void close() throws IOException {
        response.body().close();
    }

    /** Startet den Thread, der Zeile für Zeile liest, bis die Verbindung zu ist. */
    private void startReading() {
        Thread reader = new Thread(this::readLines, "sse-test-client");
        reader.setDaemon(true);
        reader.start();
    }

    /** Liest Zeilen, bis der Stream endet oder close() ihn schliesst. */
    private void readLines() {
        InputStream body = response.body();
        InputStreamReader streamReader = new InputStreamReader(body, StandardCharsets.UTF_8);
        try (BufferedReader reader = new BufferedReader(streamReader)) {
            String line = reader.readLine();
            while (line != null) {
                if (!line.isEmpty()) {
                    lines.add(line);
                }
                line = reader.readLine();
            }
        } catch (IOException exception) {
            // Die Verbindung wurde geschlossen. Für einen Test-Client ist das das normale Ende.
        }
    }
}
