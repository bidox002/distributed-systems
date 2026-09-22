import api.ChatHandler;
import api.NetworkClient;
import com.sun.net.httpserver.HttpServer;
import models.Clock;
import models.Scoreboard;
import sync.Election;
import sync.MutualExclusion;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Application entry point. Arguments: node ID and its listening port. */
public final class Node {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: java Node <nodeId> <port>");
            System.exit(1);
        }
        int nodeId = Integer.parseInt(args[0]);
        int port = Integer.parseInt(args[1]);
        List<Integer> peerPorts = Arrays.asList(8000, 8001, 8002, 8003, 8004, 8005, 8006, 8007, 8008, 8009);
        if (nodeId < 0 || nodeId >= peerPorts.size() || peerPorts.get(nodeId) != port) {
            throw new IllegalArgumentException("nodeId must match this node's position in the peer port list");
        }

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        NetworkClient network = new NetworkClient();
        Clock clock = new Clock(nodeId, peerPorts.size());
        Scoreboard scoreboard = new Scoreboard();
        MutualExclusion mutex = new MutualExclusion(nodeId, peerPorts, nodeId == 0, scoreboard, network, scheduler);
        Election election = new Election(nodeId, peerPorts, network);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api", new ChatHandler(clock, mutex, election));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        scheduler.scheduleAtFixedRate(election::probeLeader, 5, 5, TimeUnit.SECONDS);
        mutex.begin();
        System.out.println("Node " + nodeId + " running on port " + port + "; leader is Node " + election.getCurrentLeaderId());
        // HttpServer uses worker threads that do not by themselves keep every runtime alive.
        // Keep this node process available until it is deliberately stopped.
        new CountDownLatch(1).await();
    }
}
