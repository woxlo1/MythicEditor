package dev.mythiceditor.core.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.mythiceditor.core.Kinds;
import dev.mythiceditor.core.index.ProjectIndex;

/**
 * Builds the Skill/Mob relationship graph (mob -> skill, skill -> skill, skill -> mechanic) from the index.
 * Pure read-side view: recomputed on demand from ProjectIndex, never stored.
 */
public final class RelationGraph {
    public record Node(String id, String kind, String file, boolean unresolved) {}

    public record Edge(String from, String to, String kind, String label) {}

    public record Graph(List<Node> nodes, List<Edge> edges) {}

    private final ProjectIndex index;

    public RelationGraph(ProjectIndex index) {
        this.index = index;
    }

    /** Whole-project graph (mobs + skills), capped to keep the browser responsive. */
    public Graph full(int maxNodes) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        List<Edge> edges = new ArrayList<>();
        for (ProjectIndex.BlockInfo b : index.all(Kinds.MOB)) addMob(b, nodes, edges);
        for (ProjectIndex.BlockInfo b : index.all(Kinds.SKILL)) addSkill(b, nodes, edges);
        return cap(nodes, edges, maxNodes);
    }

    /** Neighbourhood graph around one entry, expanded {@code depth} hops. */
    public Graph around(String kind, String id, int depth, int maxNodes) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        List<Edge> edges = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        List<String[]> frontier = new ArrayList<>();
        frontier.add(new String[]{kind, id});
        for (int d = 0; d <= depth && !frontier.isEmpty(); d++) {
            List<String[]> next = new ArrayList<>();
            for (String[] kv : frontier) {
                String key = kv[0] + ":" + kv[1];
                if (!visited.add(key)) continue;
                List<ProjectIndex.BlockInfo> found = index.find(kv[0], kv[1]);
                if (found.isEmpty()) {
                    nodes.putIfAbsent(key, new Node(kv[1], kv[0], null, true));
                    continue;
                }
                ProjectIndex.BlockInfo b = found.get(0);
                if (kv[0].equals(Kinds.MOB)) addMob(b, nodes, edges); else addSkill(b, nodes, edges);
                for (String s : b.refs().skills()) next.add(new String[]{Kinds.SKILL, s});
                for (String m : b.refs().mobs()) next.add(new String[]{Kinds.MOB, m});
                // mobs that reference this skill (reverse edge), only meaningful for skill nodes
                if (kv[0].equals(Kinds.SKILL)) {
                    for (ProjectIndex.BlockInfo mb : index.all(Kinds.MOB)) {
                        if (mb.refs().skills().contains(kv[1])) next.add(new String[]{Kinds.MOB, mb.id()});
                    }
                }
            }
            frontier = next;
        }
        return cap(nodes, edges, maxNodes);
    }

    private void addMob(ProjectIndex.BlockInfo b, Map<String, Node> nodes, List<Edge> edges) {
        nodes.putIfAbsent(Kinds.MOB + ":" + b.id(), new Node(b.id(), Kinds.MOB, b.file(), false));
        for (String skill : b.refs().skills()) {
            String to = Kinds.SKILL + ":" + skill;
            if (index.find(Kinds.SKILL, skill).isEmpty()) nodes.putIfAbsent(to, new Node(skill, Kinds.SKILL, null, true));
            edges.add(new Edge(Kinds.MOB + ":" + b.id(), to, "uses", null));
        }
        for (String mob : b.refs().mobs()) {
            String to = Kinds.MOB + ":" + mob;
            if (index.find(Kinds.MOB, mob).isEmpty()) nodes.putIfAbsent(to, new Node(mob, Kinds.MOB, null, true));
            edges.add(new Edge(Kinds.MOB + ":" + b.id(), to, "extends", null));
        }
    }

    private void addSkill(ProjectIndex.BlockInfo b, Map<String, Node> nodes, List<Edge> edges) {
        String from = Kinds.SKILL + ":" + b.id();
        nodes.putIfAbsent(from, new Node(b.id(), Kinds.SKILL, b.file(), false));
        for (String skill : b.refs().skills()) {
            if (skill.equals(b.id())) continue;
            String to = Kinds.SKILL + ":" + skill;
            if (index.find(Kinds.SKILL, skill).isEmpty()) nodes.putIfAbsent(to, new Node(skill, Kinds.SKILL, null, true));
            edges.add(new Edge(from, to, "calls", null));
        }
        for (String mech : b.refs().mechanics()) {
            String to = "mechanic:" + mech;
            nodes.putIfAbsent(to, new Node(mech, "mechanic", null, false));
            edges.add(new Edge(from, to, "mechanic", null));
        }
    }

    private static Graph cap(Map<String, Node> nodes, List<Edge> edges, int maxNodes) {
        if (nodes.size() <= maxNodes) return new Graph(new ArrayList<>(nodes.values()), edges);
        Set<String> keep = new LinkedHashSet<>();
        int i = 0;
        for (String k : nodes.keySet()) {
            if (i++ >= maxNodes) break;
            keep.add(k);
        }
        List<Node> ns = new ArrayList<>();
        for (String k : keep) ns.add(nodes.get(k));
        List<Edge> es = new ArrayList<>();
        for (Edge e : edges) if (keep.contains(e.from()) && keep.contains(e.to())) es.add(e);
        return new Graph(ns, es);
    }
}
