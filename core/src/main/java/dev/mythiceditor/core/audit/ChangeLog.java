package dev.mythiceditor.core.audit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** In-memory ring buffer of changes (made through the editor or detected on disk). */
public final class ChangeLog {
    public record Change(long seq, long time, String actor, String action, String kind, String id, String file) {}

    private final Deque<Change> ring = new ArrayDeque<>();
    private final int capacity;
    private long seq = 0;

    public ChangeLog(int capacity) {
        this.capacity = capacity;
    }

    public synchronized Change add(String actor, String action, String kind, String id, String file) {
        Change c = new Change(++seq, System.currentTimeMillis(), actor, action, kind, id, file);
        ring.addLast(c);
        while (ring.size() > capacity) ring.removeFirst();
        return c;
    }

    public synchronized long lastSeq() {
        return seq;
    }

    public synchronized List<Change> since(long afterSeq, int limit) {
        List<Change> out = new ArrayList<>();
        for (Change c : ring) if (c.seq() > afterSeq) out.add(c);
        return out.size() > limit ? out.subList(out.size() - limit, out.size()) : out;
    }

    public synchronized List<Change> recent(int limit) {
        List<Change> all = new ArrayList<>(ring);
        int from = Math.max(0, all.size() - limit);
        List<Change> out = new ArrayList<>(all.subList(from, all.size()));
        java.util.Collections.reverse(out);
        return out;
    }
}
