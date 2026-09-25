import api.ChatHandler;
import api.Json;
import api.NetworkClient;
import config.NodeDirectory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import models.Clock;
import models.Message;
import models.Peer;
import models.Scoreboard;
import sync.Election;
import sync.MutualExclusion;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free regression tests; run with scripts/Run-Tests.ps1. */
public final class ProjectTests {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        testClocksAndOrdering();
        System.out.println("PASS clock merge and deterministic message ordering");
        NodeDirectory directory = NodeDirectory.load(Path.of("config", "nodes.properties"));
        equal(750L, directory.getTokenHandoffDelayMillis(), "configured token hand-off delay");
        System.out.println("PASS configured token hand-off pacing");
        testEndpointsAndTokenSequence();
        System.out.println("PASS health, leader, chat, and token endpoints (including duplicate and sequence checks)");
        testTenNodeTokenRing();
        System.out.println("PASS ten-node token circulation and scoreboard convergence");
        testTokenHolderCrashRecovery();
        System.out.println("PASS token recovery after the current holder crashes");
        testElectionAndRestartRecovery();
        System.out.println("PASS three-node election, leader failure, and higher-node restart recovery");
        System.out.println("ALL TESTS PASSED");
    }

    private static void testClocksAndOrdering() {
        Clock receiver = new Clock(1, 2);
        receiver.updateOnReceive(3, new int[]{0, 1});
        equal(4, receiver.getLamportTime(), "first Lamport receive update");
        arrayEqual(new int[]{0, 2}, receiver.getVectorClock(), "first vector receive update");

        receiver.updateOnReceive(3, new int[]{1, 0});
        equal(5, receiver.getLamportTime(), "second Lamport receive update");
        arrayEqual(new int[]{1, 3}, receiver.getVectorClock(), "second vector receive update");

        List<Message> messages = new ArrayList<>(Arrays.asList(
                new Message(2, "later", 5, new int[]{0, 0}),
                new Message(1, "tie-high", 3, new int[]{0, 0}),
                new Message(0, "tie-low", 3, new int[]{0, 0})));
        messages.sort(Comparator.naturalOrder());
        equal(0, messages.get(0).getSenderId(), "sender ID tie break");
        equal(1, messages.get(1).getSenderId(), "second sender ID tie break");
        equal(2, messages.get(2).getSenderId(), "Lamport time ordering");
    }

    private static void testEndpointsAndTokenSequence() throws Exception {
        long largeSequence = 9_007_199_254_740_993L;
        long parsedSequence = ((Number) Json.object("{\"sequence_number\":9007199254740993}")
                .get("sequence_number")).longValue();
        if (parsedSequence != largeSequence) {
            throw new AssertionError("large token sequence lost precision: " + parsedSequence);
        }

        AtomicReference<Map<String, Object>> forwardedToken = new AtomicReference<>();
        HttpServer tokenSink = server();
        tokenSink.createContext("/api/token", exchange -> {
            forwardedToken.set(Json.object(readBody(exchange)));
            respond(exchange, 200, "{\"status\":\"Token Handled\"}");
        });
        tokenSink.createContext("/api/chat", exchange -> respond(exchange, 200,
                "{\"status\":\"Message Received\"}"));
        tokenSink.start();

        HttpServer nodeServer = server();
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        try {
            int nodePort = nodeServer.getAddress().getPort();
            List<Peer> peers = List.of(
                    new Peer(0, "127.0.0.1", tokenSink.getAddress().getPort()),
                    new Peer(1, "127.0.0.1", nodePort));
            NetworkClient network = new NetworkClient();
            Clock clock = new Clock(1, peers.size());
            Scoreboard scoreboard = new Scoreboard();
            Election election = new Election(1, peers, network);
            election.handleCoordinatorMessage(1);
            MutualExclusion mutex = new MutualExclusion(1, peers, false, scoreboard, network, scheduler);
            ChatHandler handler = new ChatHandler(clock, mutex, election, peers, scoreboard, network);
            nodeServer.createContext("/api", handler);
            nodeServer.start();

            HttpResponse<String> health = get(nodePort, "/api/health");
            equal(200, health.statusCode(), "health endpoint status");
            equal("ALIVE", Json.object(health.body()).get("status"), "health endpoint body");

            HttpResponse<String> leader = get(nodePort, "/api/leader");
            equal(200, leader.statusCode(), "leader endpoint status");
            equal(1, Json.object(leader.body()).get("leader_id"), "leader endpoint body");

            post(nodePort, "/api/chat", Map.of(
                    "sender_id", 1, "text", "tie-high", "lamport", 3, "vector", new int[]{0, 1}));
            post(nodePort, "/api/chat", Map.of(
                    "sender_id", 0, "text", "tie-low", "lamport", 3, "vector", new int[]{1, 0}));
            equal(0, handler.messagesSnapshot().get(0).getSenderId(), "chat endpoint ordering");
            equal(5, clock.getLamportTime(), "chat endpoint receive clock");
            arrayEqual(new int[]{1, 3}, clock.getVectorClock(), "chat endpoint vector clock");

            Map<String, Object> token = new LinkedHashMap<>();
            token.put("token_holder", 0);
            token.put("token_id", "test-token");
            token.put("sequence_number", 7);
            token.put("scores", Map.of("alice", 5));
            HttpResponse<String> accepted = post(nodePort, "/api/token", token);
            equal("Token Handled", Json.object(accepted.body()).get("status"), "token acceptance");
            equal(Map.of("alice", 5), scoreboard.snapshot(), "token score snapshot");

            HttpResponse<String> replay = post(nodePort, "/api/token", token);
            equal("Duplicate Token Ignored", Json.object(replay.body()).get("status"), "duplicate token response");
            equal(200, replay.statusCode(), "duplicate token endpoint response status");
            await(() -> forwardedToken.get() != null, 5000, "token hand-off to next peer");
            equal(8, forwardedToken.get().get("sequence_number"), "token sequence increments from received value");
            equal("test-token", forwardedToken.get().get("token_id"), "token ID preserved on hand-off");

            HttpResponse<String> localSend = post(nodePort, "/api/chat/send", Map.of(
                    "destination_id", 0, "text", "dashboard-send"));
            equal(202, localSend.statusCode(), "local chat send is queued");
            Map<String, Object> sendResult = Json.object(localSend.body());
            equal(6, sendResult.get("lamport"), "local chat send ticks Lamport clock");
            List<?> sentVector = (List<?>) sendResult.get("vector");
            equal(1, ((Number) sentVector.get(0)).intValue(), "local chat send preserves remote vector entry");
            equal(4, ((Number) sentVector.get(1)).intValue(), "local chat send ticks local vector entry");
            equal(6, clock.getLamportTime(), "local chat send updates authoritative clock");
            arrayEqual(new int[]{1, 4}, clock.getVectorClock(), "local chat send updates authoritative vector");
        } finally {
            scheduler.shutdownNow();
            nodeServer.stop(0);
            tokenSink.stop(0);
        }
    }

    private static void testElectionAndRestartRecovery() throws Exception {
        final int count = 3;
        HttpServer[] servers = new HttpServer[count];
        Election[] elections = new Election[count];
        NetworkClient network = new NetworkClient();
        for (int id = 0; id < count; id++) servers[id] = server();
        List<Peer> peers = new ArrayList<>();
        for (int id = 0; id < count; id++) {
            peers.add(new Peer(id, "127.0.0.1", servers[id].getAddress().getPort()));
        }

        try {
            for (int id = 0; id < count; id++) {
                final int nodeId = id;
                servers[id].createContext("/api/election", exchange -> {
                    Map<String, Object> message = Json.object(readBody(exchange));
                    int sender = ((Number) message.get("sender_id")).intValue();
                    String type = String.valueOf(message.get("type"));
                    if ("ELECTION".equals(type)) elections[nodeId].handleElectionMessage(sender);
                    else if ("OK".equals(type)) elections[nodeId].handleOkMessage(sender);
                    else if ("COORDINATOR".equals(type)) elections[nodeId].handleCoordinatorMessage(sender);
                    else throw new IllegalArgumentException("Unknown election message " + type);
                    respond(exchange, 200, "{\"status\":\"OK\"}");
                });
                servers[id].start();
            }
            for (int id = 0; id < count; id++) elections[id] = new Election(id, peers, network);
            for (Election election : elections) election.startElection();
            await(() -> allKnow(elections, 2), 10000, "highest available node elected initially");

            int failedPort = servers[2].getAddress().getPort();
            servers[2].stop(0);
            elections[0].startElection();
            await(() -> elections[0].getCurrentLeaderId() == 1 && elections[1].getCurrentLeaderId() == 1,
                    10000, "lower available node elected after leader failure");

            servers[2] = serverAt(failedPort);
            servers[2].createContext("/api/election", exchange -> {
                Map<String, Object> message = Json.object(readBody(exchange));
                int sender = ((Number) message.get("sender_id")).intValue();
                String type = String.valueOf(message.get("type"));
                if ("ELECTION".equals(type)) elections[2].handleElectionMessage(sender);
                else if ("OK".equals(type)) elections[2].handleOkMessage(sender);
                else if ("COORDINATOR".equals(type)) elections[2].handleCoordinatorMessage(sender);
                else throw new IllegalArgumentException("Unknown election message " + type);
                respond(exchange, 200, "{\"status\":\"OK\"}");
            });
            servers[2].start();
            elections[2].startElection();
            await(() -> allKnow(elections, 2), 10000, "higher node re-elected after restart");
        } finally {
            for (HttpServer server : servers) if (server != null) server.stop(0);
        }
    }

    private static void testTenNodeTokenRing() throws Exception {
        final int count = 10;
        HttpServer[] servers = new HttpServer[count];
        MutualExclusion[] mutexes = new MutualExclusion[count];
        Scoreboard[] scoreboards = new Scoreboard[count];
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
        NetworkClient network = new NetworkClient();
        List<Peer> peers = new ArrayList<>();
        try {
            for (int id = 0; id < count; id++) {
                servers[id] = server();
                peers.add(new Peer(id, "127.0.0.1", servers[id].getAddress().getPort()));
            }
            for (int id = 0; id < count; id++) {
                Clock clock = new Clock(id, count);
                scoreboards[id] = new Scoreboard();
                Election election = new Election(id, peers, network);
                mutexes[id] = new MutualExclusion(id, peers, id == 0, scoreboards[id], network, scheduler);
                servers[id].createContext("/api", new ChatHandler(
                        clock, mutexes[id], election, peers, scoreboards[id], network));
                servers[id].start();
            }

            mutexes[5].requestCriticalSection("alice", 7);
            mutexes[8].requestCriticalSection("alice", 3);
            mutexes[0].begin();
            await(() -> {
                for (Scoreboard scoreboard : scoreboards) {
                    if (!Map.of("alice", 10).equals(scoreboard.snapshot())) return false;
                }
                return true;
            }, 15000, "concurrent score updates to travel through all ten token holders");
        } finally {
            scheduler.shutdownNow();
            for (HttpServer server : servers) if (server != null) server.stop(0);
        }
    }

    private static void testTokenHolderCrashRecovery() throws Exception {
        final int count = 3;
        HttpServer[] servers = new HttpServer[count];
        MutualExclusion[] mutexes = new MutualExclusion[count];
        Scoreboard[] scoreboards = new Scoreboard[count];
        Election[] elections = new Election[count];
        ScheduledExecutorService[] schedulers = new ScheduledExecutorService[count];
        NetworkClient network = new NetworkClient();
        List<Peer> peers = new ArrayList<>();
        try {
            for (int id = 0; id < count; id++) {
                servers[id] = server();
                peers.add(new Peer(id, "127.0.0.1", servers[id].getAddress().getPort()));
            }
            for (int id = 0; id < count; id++) {
                schedulers[id] = Executors.newScheduledThreadPool(2);
                elections[id] = new Election(id, peers, network);
                elections[id].handleCoordinatorMessage(2);
                scoreboards[id] = new Scoreboard();
                mutexes[id] = new MutualExclusion(id, peers, id == 0, scoreboards[id],
                        network, schedulers[id], 1000);
                servers[id].createContext("/api", new ChatHandler(new Clock(id, count), mutexes[id],
                        elections[id], peers, scoreboards[id], network));
                servers[id].start();
            }

            mutexes[0].begin();
            await(() -> Boolean.TRUE.equals(mutexes[2].recoveryState().get("has_token")),
                    5000, "Node 2 to receive token before crashing as coordinator");
            servers[2].stop(0);
            schedulers[2].shutdownNow();
            elections[1].startElection();
            await(() -> elections[0].getCurrentLeaderId() == 1 && elections[1].getCurrentLeaderId() == 1,
                    7000, "Node 1 to become coordinator after token-holder failure");

            mutexes[1].requestCriticalSection("alice", 10);
            mutexes[1].checkForLostToken(true);
            Thread.sleep(150);
            mutexes[1].checkForLostToken(true);
            await(() -> Map.of("alice", 10).equals(scoreboards[0].snapshot())
                            && Map.of("alice", 10).equals(scoreboards[1].snapshot())
                            && ((Number) mutexes[1].recoveryState().get("token_recoveries")).longValue() > 0,
                    6000, "new coordinator to recover token and carry a queued score update around failed node");
        } finally {
            for (ScheduledExecutorService scheduler : schedulers) {
                if (scheduler != null) scheduler.shutdownNow();
            }
            for (HttpServer server : servers) if (server != null) server.stop(0);
        }
    }

    private static boolean allKnow(Election[] elections, int leaderId) {
        for (Election election : elections) {
            if (election == null || election.getCurrentLeaderId() != leaderId) return false;
        }
        return true;
    }

    private static HttpServer server() throws IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private static HttpServer serverAt(int port) throws IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(int port, String path, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body))).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void await(Check check, long timeoutMillis, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (check.passed()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private static void equal(Object expected, Object actual, String description) {
        boolean matches = expected instanceof Number && actual instanceof Number
                ? Double.compare(((Number) expected).doubleValue(), ((Number) actual).doubleValue()) == 0
                : expected.equals(actual);
        if (!matches) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
    }

    private static void arrayEqual(int[] expected, int[] actual, String description) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(description + ": expected " + Arrays.toString(expected)
                    + ", got " + Arrays.toString(actual));
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean passed() throws Exception;
    }
}
