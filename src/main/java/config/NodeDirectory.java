package config;

import models.Peer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Loads the shared five-laptop network directory from a Java properties file. */
public final class NodeDirectory {
    public static final int NODE_COUNT = 10;

    private final List<Peer> peers;

    private NodeDirectory(List<Peer> peers) {
        this.peers = Collections.unmodifiableList(new ArrayList<>(peers));
    }

    public static NodeDirectory load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Node configuration was not found: " + path.toAbsolutePath());
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }

        List<Peer> loadedPeers = new ArrayList<>();
        Set<String> endpoints = new HashSet<>();
        for (int nodeId = 0; nodeId < NODE_COUNT; nodeId++) {
            String prefix = "node." + nodeId + ".";
            String host = required(properties, prefix + "host");
            int port = parsePort(required(properties, prefix + "port"), nodeId);
            Peer peer = new Peer(nodeId, host, port);
            if (!endpoints.add(host + ":" + port)) {
                throw new IllegalArgumentException("Each node must have a unique host and port: " + peer);
            }
            loadedPeers.add(peer);
        }
        return new NodeDirectory(loadedPeers);
    }

    public List<Peer> all() {
        return peers;
    }

    public Peer get(int nodeId) {
        if (nodeId < 0 || nodeId >= peers.size()) {
            throw new IllegalArgumentException("Unknown node ID: " + nodeId);
        }
        return peers.get(nodeId);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required configuration value: " + key);
        }
        return value.trim();
    }

    private static int parsePort(String value, int nodeId) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("node." + nodeId + ".port must be a number", exception);
        }
    }
}
