package models;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared state is changed only while a node owns the circulating token. */
public final class Scoreboard {
    private final Map<String, Integer> scores = new LinkedHashMap<>();

    public synchronized void add(String player, int delta) {
        scores.merge(player, delta, Integer::sum);
    }

    public synchronized void replace(Map<String, Integer> newScores) {
        scores.clear();
        scores.putAll(newScores);
    }

    public synchronized Map<String, Integer> snapshot() {
        return new LinkedHashMap<>(scores);
    }
}
