import { h } from "preact";
import { useEffect, useRef, useState } from "preact/hooks";
import { forceSimulation, forceLink, forceManyBody, forceCenter, forceCollide, type SimulationNodeDatum, type SimulationLinkDatum } from "d3-force";
import { select } from "d3-selection";
import { drag } from "d3-drag";
import { zoom, zoomIdentity } from "d3-zoom";
import { Api } from "../api";
import type { GraphData } from "../types";

interface Node extends SimulationNodeDatum {
  id: string;
  kind: string;
  unresolved: boolean;
}
interface Link extends SimulationLinkDatum<Node> {
  kind: string;
}

const COLORS: Record<string, string> = {
  skill: "#7fd97f",
  mob: "#7fb3ff",
  mechanic: "#f0b64c"
};

export function GraphView(props: { focus?: { kind: string; id: string } | null; onSelect: (kind: string, id: string) => void }) {
  const hostRef = useRef<HTMLDivElement | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let disposed = false;
    let cleanupSim: (() => void) | null = null;

    (async () => {
      try {
        const data: GraphData = props.focus
          ? await Api.graph(props.focus.kind, props.focus.id, 2)
          : await Api.graph();
        if (disposed || !hostRef.current) return;
        cleanupSim = renderGraph(hostRef.current, data, props.onSelect);
      } catch (e: any) {
        setError(e?.message ?? "Failed to load the graph");
      }
    })();

    return () => {
      disposed = true;
      cleanupSim?.();
    };
  }, [props.focus?.kind, props.focus?.id]);

  return (
    <div class="graph-host">
      {error && <div class="center-screen">{error}</div>}
      <div ref={hostRef} style={{ width: "100%", height: "100%" }} />
    </div>
  );
}

function renderGraph(host: HTMLDivElement, data: GraphData, onSelect: (kind: string, id: string) => void): () => void {
  host.innerHTML = "";
  const width = host.clientWidth || 800;
  const height = host.clientHeight || 500;

  const svg = select(host).append("svg").attr("viewBox", `0 0 ${width} ${height}`);
  const root = svg.append("g");
  svg.call(
    zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.2, 3])
      .on("zoom", (event) => root.attr("transform", event.transform)) as any
  );
  svg.call((zoom() as any).transform, zoomIdentity);

  const nodes: Node[] = data.nodes.map((n) => ({ id: n.kind + ":" + n.id, kind: n.kind, unresolved: n.unresolved }));
  const nodeById = new Map(nodes.map((n) => [n.id, n]));
  const links: Link[] = data.edges
    .filter((e) => nodeById.has(e.from) && nodeById.has(e.to))
    .map((e) => ({ source: e.from, target: e.to, kind: e.kind }));

  const sim = forceSimulation<Node>(nodes)
    .force("link", forceLink<Node, Link>(links).id((d) => d.id).distance(90).strength(0.5))
    .force("charge", forceManyBody().strength(-220))
    .force("center", forceCenter(width / 2, height / 2))
    .force("collide", forceCollide(26));

  const link = root.append("g").attr("stroke", "#4a4b52").selectAll("line").data(links).join("line").attr("stroke-width", 1.2);

  const node = root
    .append("g")
    .selectAll("g")
    .data(nodes)
    .join("g")
    .style("cursor", "pointer")
    .call(
      drag<SVGGElement, Node>()
        .on("start", (event, d) => {
          if (!event.active) sim.alphaTarget(0.2).restart();
          d.fx = d.x;
          d.fy = d.y;
        })
        .on("drag", (event, d) => {
          d.fx = event.x;
          d.fy = event.y;
        })
        .on("end", (event, d) => {
          if (!event.active) sim.alphaTarget(0);
          d.fx = null;
          d.fy = null;
        }) as any
    )
    .on("click", (_event, d) => {
      if (d.kind === "skill" || d.kind === "mob") onSelect(d.kind, d.id.split(":").slice(1).join(":"));
    });

  node
    .append("circle")
    .attr("r", (d) => (d.kind === "mechanic" ? 5 : 9))
    .attr("fill", (d) => (d.unresolved ? "#e06666" : COLORS[d.kind] ?? "#999"))
    .attr("stroke", "#1e1f22")
    .attr("stroke-width", 1.5);

  node
    .append("text")
    .text((d) => d.id.split(":").slice(1).join(":"))
    .attr("x", 12)
    .attr("y", 4)
    .attr("fill", "#c8c9cf")
    .attr("font-size", 11)
    .attr("font-family", "JetBrains Mono, monospace");

  sim.on("tick", () => {
    link
      .attr("x1", (d) => (d.source as Node).x ?? 0)
      .attr("y1", (d) => (d.source as Node).y ?? 0)
      .attr("x2", (d) => (d.target as Node).x ?? 0)
      .attr("y2", (d) => (d.target as Node).y ?? 0);
    node.attr("transform", (d) => `translate(${d.x ?? 0},${d.y ?? 0})`);
  });

  return () => sim.stop();
}
