package sync;

import api.NetworkClient;
import models.Scoreboard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Token-ring coordinator for all scoreboard updates. */
public final class MutualExclusion {
    private final int nodeId;
    private final List<Integer> peerPorts;
    private final Scoreboard scoreboard;
    private final NetworkClient network;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Integer> pendingUpdates = new LinkedHashMap<>();
    private boolean hasToken;
    private boolean transferInProgress;
    private int nextPeerIndex;

    public MutualExclusion(int nodeId, List<Integer> peerPorts, boolean startsWithToken, Scoreboard scoreboard,
                           NetworkClient network, ScheduledExecutorService scheduler) {
        this.nodeId = nodeId;
        this.peerPorts = List.copyOf(peerPorts);
        this.hasToken = startsWithToken;
        this.scoreboard = scoreboard;
        this.network = network;
        this.scheduler = scheduler;
        this.nextPeerIndex = (nodeId + 1) % peerPorts.size();
    }

    /** Records a requested high-score change until this node receives the token. */
    public synchronized void requestCriticalSection(String player, int delta) {
        pendingUpdates.merge(player, delta, Integer::sum);
    }

    /** Starts the logical ring after this node's HTTP server is available. */
    public void begin() {
        if (hasToken) forwardToken();
    }

    public void receiveToken(Map<String, Integer> remoteScores) {
        synchronized (this) {
            hasToken = true;
            nextPeerIndex = (nodeId + 1) % peerPorts.size();
            scoreboard.replace(remoteScores);
            pendingUpdates.forEach(scoreboard::add);
            pendingUpdates.clear();
        }
        forwardToken();
    }

    private void forwardToken() {
        final Map<String, Object> payload;
        final int destinationPort;
        synchronized (this) {
            if (!hasToken || transferInProgress) return;
            // Keep local ownership until the next node acknowledges the hand-off.
            // This is required to avoid losing the token when a peer is unavailable.
            transferInProgress = true;
            payload = new LinkedHashMap<>();
            payload.put("token_holder", nodeId);
            payload.put("scores", scoreboard.snapshot());
            destinationPort = peerPorts.get(nextPeerIndex);
        }
        scheduler.schedule(() -> network.postJson(destinationPort, "/api/token", payload)
                .thenAccept(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        synchronized (MutualExclusion.this) {
                            hasToken = false;
                            transferInProgress = false;
                        }
                        System.out.println("Node " + nodeId + " passed token to port " + destinationPort);
                    } else {
                        retryTransfer(destinationPort, "HTTP " + response.statusCode());
                    }
                })
                .exceptionally(error -> {
                    retryTransfer(destinationPort, error.getMessage());
                    return null;
                }), 250, TimeUnit.MILLISECONDS);
    }

    private void retryTransfer(int failedPort, String reason) {
        final int retryPort;
        synchronized (this) {
            // This node retains its token after an unsuccessful transfer.
            transferInProgress = false;
            nextPeerIndex = (nextPeerIndex + 1) % peerPorts.size();
            if (nextPeerIndex == nodeId) {
                nextPeerIndex = (nextPeerIndex + 1) % peerPorts.size();
            }
            retryPort = peerPorts.get(nextPeerIndex);
        }
        System.err.println("Token could not reach port " + failedPort + ": " + reason
                + "; retrying port " + retryPort);
        scheduler.schedule(this::forwardToken, 1, TimeUnit.SECONDS);
    }
}
