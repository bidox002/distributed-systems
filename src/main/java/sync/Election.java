package sync;

import api.NetworkClient;
import models.Peer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

//Bully election: the highest reachable node ID becomes coordinator.
public final class Election {
    // Give higher-ID peers time to answer, then allow one more interval for the coordinator notice.
    private static final long ELECTION_TIMEOUT_MILLIS = 2000;

    private final int nodeId;
    private final List<Peer> peers;
    private final NetworkClient network;
    private final AtomicBoolean electionInProgress = new AtomicBoolean();
    // Timeout callbacks carry this number so they cannot affect a later election round.
    private final AtomicLong electionRound = new AtomicLong();
    // Only an explicit OK from a higher-ID peer means another node can still become coordinator.
    private final Set<Integer> higherResponders = ConcurrentHashMap.newKeySet();
    private volatile int currentLeaderId;

    public Election(int nodeId, List<Peer> peers, NetworkClient network) {
        this.nodeId = nodeId;
        this.peers = List.copyOf(peers);
        this.network = network;
        // Wait for the startup election to discover which configured nodes are actually available.
        this.currentLeaderId = -1;
    }

    public int getCurrentLeaderId() { return currentLeaderId; }

    public void startElection() {
        // The compare-and-set prevents simultaneous triggers from launching duplicate elections.
        if (!electionInProgress.compareAndSet(false, true)) return;
        long round = electionRound.incrementAndGet();
        higherResponders.clear();
        System.out.println("Node " + nodeId + " starting election round " + round);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "ELECTION");
        payload.put("sender_id", nodeId);
        for (int peerId = nodeId + 1; peerId < peers.size(); peerId++) {
            int higherPeerId = peerId;
            network.postJson(peers.get(peerId), "/api/election", payload)
                    .thenAccept(response -> {
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            System.err.println("Election request from Node " + nodeId + " to Node "
                                    + higherPeerId + " returned HTTP " + response.statusCode());
                        }
                    })
                    .exceptionally(error -> {
                        System.err.println("Election request from Node " + nodeId + " to Node "
                                + higherPeerId + " failed: " + error.getMessage());
                        return null;
                    });
        }
        // Decide leadership after replies have had time to arrive; network calls complete asynchronously
        scheduleElectionTimeout(round);
    }

    //Sends the protocol's explicit OK message before starting this higher node's own election.
    public void handleElectionMessage(int senderId) {
        // Only a valid lower-ID node should trigger this node's Bully election response.
        if (!isValidNode(senderId) || senderId >= nodeId) return;

        System.out.println("Node " + nodeId + " received ELECTION from Node " + senderId
                + "; sending OK");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "OK");
        payload.put("sender_id", nodeId);
        network.postJson(peers.get(senderId), "/api/election", payload)
                .thenAccept(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        System.out.println("Node " + nodeId + " sent OK to Node " + senderId);
                    } else {
                        System.err.println("OK reply from Node " + nodeId + " to Node " + senderId
                                + " returned HTTP " + response.statusCode());
                    }
                })
                .exceptionally(error -> {
                    System.err.println("OK reply from Node " + nodeId + " to Node " + senderId
                            + " failed: " + error.getMessage());
                    return null;
                });
        startElection();
    }

    // Handles the explicit OK payload defined by the course API.
    public void handleOkMessage(int senderId) {
        if (isValidNode(senderId) && senderId > nodeId && electionInProgress.get()
                && higherResponders.add(senderId)) {
            System.out.println("Node " + nodeId + " received OK from higher Node " + senderId);
        }
    }

    public void handleCoordinatorMessage(int newLeaderId) {
        if (!isValidNode(newLeaderId)) {
            System.err.println("Node " + nodeId + " ignored invalid coordinator Node " + newLeaderId);
            return;
        }
        // In the Bully algorithm, a live higher-ID node must challenge a lower-ID coordinator.
        if (newLeaderId < nodeId) {
            System.out.println("Node " + nodeId + " received lower-ID coordinator Node " + newLeaderId
                    + "; starting election");
            startElection();
            return;
        }
        currentLeaderId = newLeaderId;
        electionInProgress.set(false);
        System.out.println("Node " + nodeId + " recognized Node " + newLeaderId + " as coordinator");
    }

    public void probeLeader() {
        int leader = currentLeaderId;
        if (!isValidNode(leader)) {
            startElection();
            return;
        }
        if (leader != nodeId) {
            network.isAlive(peers.get(leader)).thenAccept(alive -> {
                if (!alive) {
                    System.out.println("Node " + nodeId + " detected coordinator Node " + leader
                            + " is unavailable");
                    startElection();
                }
            });
        }
    }

    private void scheduleElectionTimeout(long round) {
        CompletableFuture.delayedExecutor(ELECTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .execute(() -> onElectionTimeout(round));
    }

    private void onElectionTimeout(long round) {
        if (!isActiveRound(round)) return;
        if (higherResponders.isEmpty()) {
            // No higher node answered, so this node is the highest reachable candidate.
            declareLeadership(round);
            return;
        }

        // A higher node answered; wait for its coordinator broadcast before retrying.
        System.out.println("Node " + nodeId + " received OK from higher node(s) but no coordinator yet; "
                + "waiting one more timeout");
        CompletableFuture.delayedExecutor(ELECTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .execute(() -> onCoordinatorTimeout(round));
    }

    private void onCoordinatorTimeout(long round) {
        if (!isActiveRound(round)) return;
        System.out.println("Node " + nodeId + " timed out waiting for COORDINATOR; restarting election");
        if (electionInProgress.compareAndSet(true, false)) startElection();
    }

    private void declareLeadership(long round) {
        // Recheck the round and atomically end it so a stale timeout cannot announce leadership.
        if (!isActiveRound(round) || !electionInProgress.compareAndSet(true, false)) return;
        currentLeaderId = nodeId;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "COORDINATOR");
        payload.put("sender_id", nodeId);
        System.out.println("Node " + nodeId + " is the new leader; broadcasting COORDINATOR");
        for (Peer peer : peers) {
            if (peer.getNodeId() == nodeId) continue;
            network.postJson(peer, "/api/election", payload)
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            System.out.println("Node " + nodeId + " notified Node " + peer.getNodeId()
                                    + " of the coordinator change");
                        } else {
                            System.err.println("COORDINATOR broadcast from Node " + nodeId + " to Node "
                                    + peer.getNodeId() + " returned HTTP " + response.statusCode());
                        }
                    })
                    .exceptionally(error -> {
                        System.err.println("COORDINATOR broadcast from Node " + nodeId + " to Node "
                                + peer.getNodeId() + " failed: " + error.getMessage());
                        return null;
                    });
        }
    }

    private boolean isActiveRound(long round) {
        // Both conditions matter: a coordinator message can end a round before its timer fires.
        return electionInProgress.get() && electionRound.get() == round;
    }

    private boolean isValidNode(int id) {
        // Node IDs are used as indexes into the configured peer list.
        return id >= 0 && id < peers.size();
    }
}
