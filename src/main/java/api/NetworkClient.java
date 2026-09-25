package api;

import models.Peer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Asynchronous HTTP helper so peer communication does not block request handlers. */
public final class NetworkClient {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public CompletableFuture<HttpResponse<String>> postJson(Peer peer, String path, Map<String, Object> payload) {
        HttpRequest request = HttpRequest.newBuilder(endpoint(peer, path))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(payload)))
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    public CompletableFuture<HttpResponse<String>> get(Peer peer, String path) {
        HttpRequest request = HttpRequest.newBuilder(endpoint(peer, path))
                .timeout(Duration.ofSeconds(2)).GET().build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    public CompletableFuture<Boolean> isAlive(Peer peer) {
        HttpRequest request = HttpRequest.newBuilder(endpoint(peer, "/api/health"))
                .timeout(Duration.ofSeconds(2)).GET().build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenApply(response -> response.statusCode() == 200)
                .exceptionally(error -> false);
    }

    private static URI endpoint(Peer peer, String path) {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Endpoint path must begin with '/'");
        }
        return URI.create("http://" + peer.getHost() + ":" + peer.getPort() + path);
    }
}
