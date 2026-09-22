package models;

/** Immutable network address for one configured distributed-system node. */
public final class Peer {
    private final int nodeId;
    private final String host;
    private final int port;

    public Peer(int nodeId, String host, int port) {
        if (nodeId < 0) {
            throw new IllegalArgumentException("nodeId cannot be negative");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host cannot be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        this.nodeId = nodeId;
        this.host = host.trim();
        this.port = port;
    }

    public int getNodeId() {
        return nodeId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    @Override
    public String toString() {
        return "Node " + nodeId + " (" + host + ":" + port + ")";
    }
}
