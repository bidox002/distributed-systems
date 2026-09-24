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
            pendingUpdates.forEach(scoreboard::add);
            pendingUpdates.forEach((player, delta) -> System.out.println("Node " + nodeId
                    + " updated score for " + player + " by " + delta + "; scoreboard=" + scoreboard.snapshot()));
            pendingUpdates.clear();
        }
        forwardToken();
        return true;
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
