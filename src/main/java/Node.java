import api.ChatHandler;
import api.NetworkClient;
import config.NodeDirectory;
import com.sun.net.httpserver.HttpServer;
import models.Clock;
import models.Scoreboard;
import sync.Election;
import sync.MutualExclusion;

import java.net.InetSocketAddress;
import java.util.List;
import models.Peer;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Application entry point. Arguments: node ID, local port, and shared node directory. */
public final class Node {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: java Node <nodeId> <port> <nodes.properties>");
            System.exit(1);
        }
        int nodeId = Integer.parseInt(args[0]);
        int port = Integer.parseInt(args[1]);
        NodeDirectory directory = NodeDirectory.load(Path.of(args[2]));
        List<Peer> peers = directory.all();
        Peer localPeer = directory.get(nodeId);
        if (localPeer.getPort() != port) {
            throw new IllegalArgumentException("The supplied port does not match node." + nodeId + ".port in the shared configuration");
        }

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        NetworkClient network = new NetworkClient();
        Clock clock = new Clock(nodeId, peers.size());
        Scoreboard scoreboard = new Scoreboard();
        MutualExclusion mutex = new MutualExclusion(nodeId, peers, nodeId == 0, scoreboard, network, scheduler);
        Election election = new Election(nodeId, peers, network);

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/api", new ChatHandler(clock, mutex, election));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        scheduler.scheduleAtFixedRate(election::probeLeader, 5, 5, TimeUnit.SECONDS);
        mutex.begin();
        System.out.println("Node " + nodeId + " running at " + localPeer + "; leader is Node " + election.getCurrentLeaderId());
        // HttpServer uses worker threads that do not by themselves keep every runtime alive.
        // Keep this node process available until it is deliberately stopped.
        new CountDownLatch(1).await();
    }
}
