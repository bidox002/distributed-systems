package api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import models.Clock;
import models.Message;
import models.Peer;
import models.Scoreboard;
import sync.Election;
import sync.MutualExclusion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Routes the REST endpoints specified in the course brief. */
public final class ChatHandler implements HttpHandler {
    private final Clock clock;
    private final MutualExclusion mutex;
    private final Election election;
    private final List<Peer> peers;
    private final Scoreboard scoreboard;
    private final List<Message> messages = Collections.synchronizedList(new ArrayList<>());

    public ChatHandler(Clock clock, MutualExclusion mutex, Election election, List<Peer> peers, Scoreboard scoreboard) {
        this.clock = clock;
        this.mutex = mutex;
        this.election = election;
        this.peers = peers;
        this.scoreboard = scoreboard;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("OPTIONS".equals(method)) respond(exchange, 204, "");
            else if ("GET".equals(method) && "/api/health".equals(path)) respond(exchange, 200, status("ALIVE"));
            else if ("GET".equals(method) && "/api/state".equals(path)) receiveState(exchange);
            else if ("GET".equals(method) && "/api/nodes".equals(path)) receiveNodes(exchange);
            else if ("GET".equals(method) && "/api/leader".equals(path)) receiveLeader(exchange);
            else if ("POST".equals(method) && "/api/chat".equals(path)) receiveChat(exchange);
            else if ("POST".equals(method) && "/api/token".equals(path)) receiveToken(exchange);
            else if ("POST".equals(method) && "/api/election".equals(path)) receiveElection(exchange);
            else respond(exchange, 404, status("Not Found"));
        } catch (IllegalArgumentException exception) {
            respond(exchange, 400, Json.stringify(Map.of("error", exception.getMessage())));
        } catch (Exception exception) {
            exception.printStackTrace();
            respond(exchange, 500, status("Internal Server Error"));
        }
    }

    private void receiveNodes(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Peer peer : peers) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("node_id", peer.getNodeId()); item.put("host", peer.getHost()); item.put("port", peer.getPort());
            result.add(item);
        }
        respond(exchange, 200, Json.stringify(result));
    }

    private void receiveState(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> log = new ArrayList<>();
        for (Message message : messagesSnapshot()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sender_id", message.getSenderId()); item.put("text", message.getText());
            item.put("lamport", message.getLamport()); item.put("vector", message.getVector()); log.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node_id", clock.getNodeId()); result.put("lamport", clock.getLamportTime());
        result.put("vector", clock.getVectorClock()); result.put("leader_id", election.getCurrentLeaderId());
        result.put("messages", log); result.put("scores", scoreboard.snapshot());
        respond(exchange, 200, Json.stringify(result));
    }

    /** Reports the coordinator ID currently known by this node. */
    private void receiveLeader(HttpExchange exchange) throws IOException {
        respond(exchange, 200, Json.stringify(Map.of("leader_id", election.getCurrentLeaderId())));
    }

    private void receiveChat(HttpExchange exchange) throws IOException {
        Map<String, Object> body = body(exchange);
        int sender = integer(body, "sender_id");
        int lamport = integer(body, "lamport");
        int[] vector = vector(body.get("vector"));
        String text = String.valueOf(body.get("text"));
        clock.updateOnReceive(lamport, vector);
        record(new Message(sender, text, lamport, vector));
        System.out.println("Clock after receive: Lamport=" + clock.getLamportTime()
                + ", vector=" + Arrays.toString(clock.getVectorClock()));
        respond(exchange, 200, status("Message Received"));
    }

    /** Records a locally sent chat event without applying receive-side clock merging. */
    public void recordLocalMessage(Message message) {
        record(message);
    }

    /** Returns an ordered copy for the local console without exposing mutable chat state. */
    public List<Message> messagesSnapshot() {
        synchronized (messages) {
            return List.copyOf(messages);
        }
    }

    private void record(Message message) {
        synchronized (messages) {
            messages.add(message);
            Collections.sort(messages);
            System.out.println("Ordered chat log: " + messages);
        }
    }

    private void receiveToken(HttpExchange exchange) throws IOException {
        Map<String, Object> body = body(exchange);
        integer(body, "token_holder");
        Object sequence = body.get("sequence_number");
        if (!(body.get("token_id") instanceof String) || !(sequence instanceof Number)) {
            throw new IllegalArgumentException("Missing token_id or numeric sequence_number");
        }
        boolean accepted = mutex.receiveToken((String) body.get("token_id"),
                ((Number) sequence).longValue(), integerMap(body.get("scores")));
        respond(exchange, 200, status(accepted ? "Token Handled" : "Duplicate Token Ignored"));
    }

    private void receiveElection(HttpExchange exchange) throws IOException {
        Map<String, Object> body = body(exchange);
        String type = String.valueOf(body.get("type"));
        int sender = integer(body, "sender_id");
        if ("ELECTION".equals(type)) election.handleElectionMessage(sender);
        else if ("OK".equals(type)) election.handleOkMessage(sender);
        else if ("COORDINATOR".equals(type)) election.handleCoordinatorMessage(sender);
        else throw new IllegalArgumentException("Unknown election type");
        respond(exchange, 200, status("OK"));
    }

    private static Map<String, Object> body(HttpExchange exchange) throws IOException {
        return Json.object(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }
    private static int integer(Map<String, Object> map, String key) {
        Object value = map.get(key); if (!(value instanceof Number)) throw new IllegalArgumentException("Missing numeric field: " + key); return ((Number) value).intValue();
    }
    private static int[] vector(Object value) {
        if (!(value instanceof List)) throw new IllegalArgumentException("Missing array field: vector");
        List<?> values = (List<?>) value; int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) { if (!(values.get(i) instanceof Number)) throw new IllegalArgumentException("Vector values must be numbers"); result[i] = ((Number) values.get(i)).intValue(); }
        return result;
    }
    private static Map<String, Integer> integerMap(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("Missing object field: scores");
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) { if (!(entry.getValue() instanceof Number)) throw new IllegalArgumentException("Scores must be numeric"); result.put(String.valueOf(entry.getKey()), ((Number) entry.getValue()).intValue()); }
        return result;
    }
    private static String status(String value) { return Json.stringify(Map.of("status", value)); }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
}
