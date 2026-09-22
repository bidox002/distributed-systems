package models;

import java.util.Arrays;

/** Immutable chat entry ordered deterministically by Lamport time then sender. */
public final class Message implements Comparable<Message> {
    private final int senderId;
    private final String text;
    private final int lamport;
    private final int[] vector;

    public Message(int senderId, String text, int lamport, int[] vector) {
        this.senderId = senderId;
        this.text = text;
        this.lamport = lamport;
        this.vector = Arrays.copyOf(vector, vector.length);
    }

    public int getSenderId() { return senderId; }
    public String getText() { return text; }
    public int getLamport() { return lamport; }
    public int[] getVector() { return Arrays.copyOf(vector, vector.length); }
    @Override
    public int compareTo(Message other) {
        int timeOrder = Integer.compare(lamport, other.lamport);
        return timeOrder != 0 ? timeOrder : Integer.compare(senderId, other.senderId);
    }

    @Override
    public String toString() {
        return "[" + lamport + ", sender=" + senderId + ", text=" + text + "]";
    }
}
