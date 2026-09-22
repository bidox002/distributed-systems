package sync;

import api.NetworkClient;
import models.Peer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bully election: the highest reachable node ID becomes coordinator. */
public final class Election {
    private final int nodeId;
    private final List<Peer> peers;
    private final NetworkClient network;
    private final AtomicBoolean electionInProgress = new AtomicBoolean();
    private final Set<Integer> higherResponders = ConcurrentHashMap.newKeySet();
    private volatile int currentLeaderId;

    public Election(int nodeId, List<Peer> peers, NetworkClient network) {
        this.nodeId = nodeId;
        this.peers = List.copyOf(peers);
        this.network = network;
        this.currentLeaderId = peers.size() - 1;
    }

    public int getCurrentLeaderId() { return currentLeaderId; }

    public void startElection() {
        if (!electionInProgress.compareAndSet(false, true)) return;
        higherResponders.clear();
        System.out.println("Node " + nodeId + " starting election");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "ELECTION");
        payload.put("sender_id", nodeId);
        List<CompletableFuture<Boolean>> replies = new java.util.ArrayList<>();
        for (int peerId = nodeId + 1; peerId < peers.size(); peerId++) {
            replies.add(network.postJson(peers.get(peerId), "/api/election", payload)
                    .thenApply(response -> response.statusCode() == 200 && response.body().contains("OK"))
                    .exceptionally(error -> false));
        }
        CompletableFuture.allOf(replies.toArray(new CompletableFuture[0])).thenRun(() -> {
            boolean higherNodeAnswered = !higherResponders.isEmpty()
                    || replies.stream().anyMatch(CompletableFuture::join);
            if (!higherNodeAnswered) declareLeadership();
        });
    }

    public void handleElectionMessage(int senderId) {
        if (senderId < nodeId) startElection();
    }

    /** Handles the explicit OK payload defined by the course API. */
    public void handleOkMessage(int senderId) {
        if (senderId > nodeId) {
            higherResponders.add(senderId);
        }
    }

    public void handleCoordinatorMessage(int newLeaderId) {
        currentLeaderId = newLeaderId;
        electionInProgress.set(false);
        System.out.println("New leader recognized: Node " + newLeaderId);
    }

    public void probeLeader() {
        int leader = currentLeaderId;
        if (leader != nodeId) network.isAlive(peers.get(leader)).thenAccept(alive -> { if (!alive) startElection(); });
    }

    private void declareLeadership() {
        currentLeaderId = nodeId;
        electionInProgress.set(false);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "COORDINATOR");
        payload.put("sender_id", nodeId);
        for (Peer peer : peers) {
            if (peer.getNodeId() != nodeId) network.postJson(peer, "/api/election", payload);
        }
        System.out.println("Node " + nodeId + " is the new leader");
    }
}
