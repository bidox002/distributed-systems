package models;

import java.util.Arrays;

/** Thread-safe Lamport and vector clock state for one node. */
public final class Clock {
    private int lamportTime;
    private final int[] vectorClock;
    private final int nodeId;

    public Clock(int nodeId, int totalNodes) {
        if (nodeId < 0 || nodeId >= totalNodes) {
            throw new IllegalArgumentException("nodeId must be within the configured node range");
        }
        this.nodeId = nodeId;
        this.vectorClock = new int[totalNodes];
    }

    /** Records a local event. */
    public synchronized void tick() {
        lamportTime++;
        vectorClock[nodeId]++;
    }

    /** Merges a received message clock, then records the receive event. */
    public synchronized void updateOnReceive(int incomingLamport, int[] incomingVector) {
        if (incomingVector == null || incomingVector.length != vectorClock.length) {
            throw new IllegalArgumentException("Incoming vector has the wrong node count");
        }
        lamportTime = Math.max(lamportTime, incomingLamport) + 1;
        for (int i = 0; i < vectorClock.length; i++) {
            vectorClock[i] = Math.max(vectorClock[i], incomingVector[i]);
        }
        vectorClock[nodeId]++;
    }

    public synchronized int getLamportTime() {
        return lamportTime;
    }

    public synchronized int[] getVectorClock() {
        return Arrays.copyOf(vectorClock, vectorClock.length);
    }
}
