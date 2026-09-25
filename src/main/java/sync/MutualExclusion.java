package sync;

import api.Json;
import api.NetworkClient;
import models.Peer;
import models.Scoreboard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Token-ring coordinator for all scoreboard updates. */
public final class MutualExclusion {
    private final int nodeId;
    private final List<Peer> peers;
    private final Scoreboard scoreboard;
    private final NetworkClient network;
    private final ScheduledExecutorService scheduler;
    private final long handoffDelayMillis;
    private final Map<String, Integer> pendingUpdates = new LinkedHashMap<>();
    private boolean hasToken;
    private boolean transferInProgress;
    private int nextPeerIndex;
    private String tokenId;
    private long sequenceNumber;
    private long lastReceivedSequence = -1;
    private long recoveryCount;
    private final AtomicBoolean recoveryScanInProgress = new AtomicBoolean();
    private int consecutiveMissingTokenScans;

    public MutualExclusion(int nodeId, List<Peer> peers, boolean startsWithToken, Scoreboard scoreboard,
                           NetworkClient network, ScheduledExecutorService scheduler) {
        this(nodeId, peers, startsWithToken, scoreboard, network, scheduler, 250);
    }

    public MutualExclusion(int nodeId, List<Peer> peers, boolean startsWithToken, Scoreboard scoreboard,
                           NetworkClient network, ScheduledExecutorService scheduler, long handoffDelayMillis) {
        if (handoffDelayMillis < 1) {
            throw new IllegalArgumentException("handoffDelayMillis must be positive");
        }
        this.nodeId = nodeId;
        this.peers = List.copyOf(peers);
        this.hasToken = startsWithToken;
        this.scoreboard = scoreboard;
        this.network = network;
        this.scheduler = scheduler;
        this.handoffDelayMillis = handoffDelayMillis;
        this.nextPeerIndex = (nodeId + 1) % peers.size();
        this.tokenId = startsWithToken ? "token-" + UUID.randomUUID() : null;
    }

    /** Records a requested high-score change until this node receives the token. */
    public synchronized void requestCriticalSection(String player, int delta) {
        pendingUpdates.merge(player, delta, Integer::sum);
    }

    /** Exposes the local token replica state for coordinator failure-recovery scans. */
    public synchronized Map<String, Object> recoveryState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("has_token", hasToken);
        state.put("transfer_in_progress", transferInProgress);
        state.put("token_id", tokenId);
        state.put("sequence_number", sequenceNumber);
        state.put("last_received_sequence", lastReceivedSequence);
        state.put("token_recoveries", recoveryCount);
        return state;
    }

    /**
     * A coordinator may restore a lost token after two complete scans find no live owner.
     * Network partitions can still look like crashes, as with any timeout-based failure detector.
     */
    public void checkForLostToken(boolean isCoordinator) {
        if (!isCoordinator || !recoveryScanInProgress.compareAndSet(false, true)) return;
        List<CompletableFuture<Map<String, Object>>> requests = new java.util.ArrayList<>();
        for (Peer peer : peers) {
            if (peer.getNodeId() == nodeId) continue;
            requests.add(network.get(peer, "/api/state").thenApply(response -> {
                if (response.statusCode() != 200) return null;
                try { return Json.object(response.body()); }
                catch (IllegalArgumentException ignored) { return null; }
            }).exceptionally(error -> null));
        }
        CompletableFuture.allOf(requests.toArray(new CompletableFuture<?>[0]))
                .whenComplete((ignored, error) -> {
                    try {
                        List<Map<String, Object>> states = new java.util.ArrayList<>();
                        states.add(localRecoveryState());
                        for (CompletableFuture<Map<String, Object>> request : requests) {
                            Map<String, Object> state = request.getNow(null);
                            if (state != null) states.add(state);
                        }
                        inspectRecoveryStates(states);
                    } finally {
                        recoveryScanInProgress.set(false);
                    }
                });
    }

    private synchronized Map<String, Object> localRecoveryState() {
        Map<String, Object> state = recoveryState();
        state.put("leader_id", nodeId);
        state.put("node_id", nodeId);
        state.put("scores", scoreboard.snapshot());
        return state;
    }

    private void inspectRecoveryStates(List<Map<String, Object>> states) {
        String observedTokenId = null;
        long maxSequence = -1;
        Map<String, Integer> freshestScores = null;
        boolean tokenExists = false;
        boolean consistentLeadership = true;
        Set<String> tokenIds = new HashSet<>();

        for (Map<String, Object> state : states) {
            if (!(state.get("leader_id") instanceof Number)
                    || ((Number) state.get("leader_id")).intValue() != nodeId) {
                consistentLeadership = false;
            }
            Object id = state.get("token_id");
            if (id instanceof String && !((String) id).isBlank()) tokenIds.add((String) id);
            if (Boolean.TRUE.equals(state.get("has_token"))) tokenExists = true;
            long stateSequence = number(state.get("sequence_number"), -1);
            long receivedSequence = number(state.get("last_received_sequence"), -1);
            long freshestSequence = Math.max(stateSequence, receivedSequence);
            if (freshestSequence > maxSequence && state.get("scores") instanceof Map) {
                maxSequence = freshestSequence;
                freshestScores = integerMap((Map<?, ?>) state.get("scores"));
            }
        }

        synchronized (this) {
            if (!consistentLeadership || tokenExists) {
                consecutiveMissingTokenScans = 0;
                return;
            }
            if (tokenIds.size() != 1) {
                consecutiveMissingTokenScans = 0;
                if (tokenIds.size() > 1) System.err.println("Node " + nodeId
                        + " cannot recover token: live nodes report conflicting token IDs " + tokenIds);
                return;
            }
            if (maxSequence < 0 || freshestScores == null) {
                consecutiveMissingTokenScans = 0;
                return;
            }
            consecutiveMissingTokenScans++;
            if (consecutiveMissingTokenScans < 2 || hasToken || transferInProgress) return;
            observedTokenId = tokenIds.iterator().next();
            tokenId = observedTokenId;
            sequenceNumber = Math.max(sequenceNumber, maxSequence);
            lastReceivedSequence = Math.max(lastReceivedSequence, maxSequence);
            scoreboard.replace(freshestScores);
            hasToken = true;
            transferInProgress = false;
            applyPendingUpdates();
            recoveryCount++;
            consecutiveMissingTokenScans = 0;
        }
        System.err.println("Node " + nodeId + " recovered token " + observedTokenId
                + " after two scans found no live holder; resuming at sequence " + (maxSequence + 1));
        forwardToken();
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private static Map<String, Integer> integerMap(Map<?, ?> values) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (entry.getValue() instanceof Number) {
                result.put(String.valueOf(entry.getKey()), ((Number) entry.getValue()).intValue());
            }
        }
        return result;
    }

    /** Starts the logical ring after this node's HTTP server is available. */
    public void begin() {
        if (hasToken) forwardToken();
    }

    public synchronized boolean receiveToken(String receivedTokenId, long receivedSequence,
                                             Map<String, Integer> remoteScores) {
        synchronized (this) {
            if ((tokenId != null && !tokenId.equals(receivedTokenId))
                    || receivedSequence <= lastReceivedSequence || hasToken) {
                System.out.println("Node " + nodeId + " rejected duplicate or stale token "
                        + receivedTokenId + " sequence " + receivedSequence);
                return false;
            }
            tokenId = receivedTokenId;
            lastReceivedSequence = receivedSequence;
            sequenceNumber = Math.max(sequenceNumber, receivedSequence);
            hasToken = true;
            nextPeerIndex = (nodeId + 1) % peers.size();
            scoreboard.replace(remoteScores);
            System.out.println("Node " + nodeId + " received token " + tokenId
                    + " sequence " + receivedSequence + "; entering scoreboard critical section");
            applyPendingUpdates();
        }
        forwardToken();
        return true;
    }

    private void applyPendingUpdates() {
        pendingUpdates.forEach(scoreboard::add);
        pendingUpdates.forEach((player, delta) -> System.out.println("Node " + nodeId
                + " updated score for " + player + " by " + delta + "; scoreboard=" + scoreboard.snapshot()));
        pendingUpdates.clear();
    }

    private void forwardToken() {
        final Map<String, Object> payload;
        final Peer destination;
        final long transferSequence;
        synchronized (this) {
            if (!hasToken || transferInProgress) return;
            // Keep local ownership until the next node acknowledges the hand-off.
            // This is required to avoid losing the token when a peer is unavailable.
            transferInProgress = true;
            payload = new LinkedHashMap<>();
            payload.put("token_holder", nodeId);
            payload.put("token_id", tokenId);
            transferSequence = ++sequenceNumber;
            payload.put("sequence_number", transferSequence);
            payload.put("scores", scoreboard.snapshot());
            destination = peers.get(nextPeerIndex);
        }
        scheduler.schedule(() -> network.postJson(destination, "/api/token", payload)
                .thenAccept(response -> {
                    if (isTokenAccepted(response.statusCode(), response.body())) {
                        synchronized (MutualExclusion.this) {
                            hasToken = false;
                            transferInProgress = false;
                        }
                        System.out.println("TOKEN_HANDOFF token=" + tokenId + " sequence="
                                + transferSequence + " from=" + nodeId + " to=" + destination);
                    } else {
                        retryTransfer(destination, "token was not accepted (HTTP "
                                + response.statusCode() + "): " + response.body());
                    }
                })
                .exceptionally(error -> {
                    retryTransfer(destination, error.getMessage());
                    return null;
                }), handoffDelayMillis, TimeUnit.MILLISECONDS);
    }

    private static boolean isTokenAccepted(int statusCode, String responseBody) {
        if (statusCode < 200 || statusCode >= 300) return false;
        try {
            return "Token Handled".equals(Json.object(responseBody).get("status"));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void retryTransfer(Peer failedPeer, String reason) {
        final Peer retryPeer;
        synchronized (this) {
            // This node retains its token after an unsuccessful transfer.
            transferInProgress = false;
            nextPeerIndex = (nextPeerIndex + 1) % peers.size();
            if (nextPeerIndex == nodeId) {
                nextPeerIndex = (nextPeerIndex + 1) % peers.size();
            }
            retryPeer = peers.get(nextPeerIndex);
        }
        System.err.println("Token could not reach " + failedPeer + ": " + reason
                + "; retrying " + retryPeer);
        scheduler.schedule(this::forwardToken, 1, TimeUnit.SECONDS);
    }
}
