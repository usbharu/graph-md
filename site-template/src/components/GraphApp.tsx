import cytoscape from "cytoscape";
import { useEffect, useMemo, useRef, useState } from "react";

type LayoutMode = "force" | "radial" | "hierarchy";
type SelectedNode = {
  id: string;
  label: string;
  type: string;
  kind: string;
  route?: string;
  incoming: number;
  outgoing: number;
  neighbors: Array<{ id: string; label: string; route?: string }>;
};

const palette = ["#4f7cff", "#8b5cf6", "#0ea5a4", "#f97316", "#e14b87", "#22a06b", "#b7791f", "#64748b"];

function colorFor(value: string) {
  let hash = 0;
  for (const character of value) hash = ((hash << 5) - hash + character.charCodeAt(0)) | 0;
  return palette[Math.abs(hash) % palette.length];
}

function nodeSize(linkCount: number) {
  return Math.round(30 + Math.min(28, Math.sqrt(linkCount) * 7));
}

function graphStyle(dark: boolean): any[] {
  const colors = dark ? {
    label: "#e8edf4", nodeBorder: "#1b2027", edge: "#657284", arrow: "#7f8da0",
    selectedEdge: "#79a6ff", edgeLabel: "#e8edf4", edgeLabelBackground: "#252c35",
    contextEdge: "#9aabc0", search: "#f6c85f",
  } : {
    label: "#29313d", nodeBorder: "#ffffff", edge: "#b9c2d0", arrow: "#9aa6b7",
    selectedEdge: "#4f7cff", edgeLabel: "#29313d", edgeLabelBackground: "#ffffff",
    contextEdge: "#7c8da8", search: "#f5b942",
  };
  return [
    { selector: "node", style: { label: "data(label)", "background-color": "data(color)", width: "data(size)", height: "data(size)", "border-width": 4, "border-color": colors.nodeBorder, "border-opacity": .95, color: colors.label, "font-size": 11, "font-weight": 600, "text-valign": "bottom", "text-margin-y": 9, "text-outline-color": dark ? "#171c22" : "#f8fafc", "text-outline-width": dark ? 2 : 1, "text-wrap": "ellipsis", "text-max-width": 130, "overlay-opacity": 0, "transition-property": "width height opacity border-width", "transition-duration": .18 } },
    { selector: "node:selected", style: { width: "data(selectedSize)", height: "data(selectedSize)", "border-width": 7, "border-color": "data(color)", "border-opacity": .35, "font-size": 13, "font-weight": 700, "z-index": 20 } },
    { selector: "node.hovered", style: { width: "data(hoverSize)", height: "data(hoverSize)", "z-index": 15 } },
    { selector: "edge", style: { width: 1.4, "line-color": colors.edge, "target-arrow-color": colors.arrow, "target-arrow-shape": "triangle", "arrow-scale": .7, "curve-style": "unbundled-bezier", "control-point-distances": 24, "control-point-weights": .5, opacity: dark ? .78 : .68, "overlay-opacity": 0, "transition-property": "opacity width line-color", "transition-duration": .18 } },
    { selector: "edge:selected", style: { width: 3, "line-color": colors.selectedEdge, "target-arrow-color": colors.selectedEdge, opacity: 1, label: "data(label)", color: colors.edgeLabel, "font-size": 9, "text-background-color": colors.edgeLabelBackground, "text-background-opacity": .96, "text-background-padding": 3 } },
    { selector: ".context-dim", style: { opacity: dark ? .12 : .08 } },
    { selector: ".context-edge", style: { width: 2.8, "line-color": colors.contextEdge, "target-arrow-color": colors.contextEdge, opacity: 1 } },
    { selector: ".search-match", style: { width: "data(searchSize)", height: "data(searchSize)", "border-width": 7, "border-color": colors.search, "border-opacity": .65 } },
    { selector: ".filtered-out", style: { display: "none" } },
  ];
}

export default function GraphApp({ elements }: { elements: any[] }) {
  const container = useRef<HTMLDivElement>(null);
  const graphRef = useRef<cytoscape.Core | null>(null);
  const [query, setQuery] = useState("");
  const [typeFilter, setTypeFilter] = useState("all");
  const [layout, setLayout] = useState<LayoutMode>("force");
  const [selected, setSelected] = useState<SelectedNode | null>(null);

  const nodeData = useMemo(() => elements.filter((element) => !element.data?.source).map((element) => element.data), [elements]);
  const edgeCount = elements.length - nodeData.length;
  const types = useMemo(() => [...new Set(nodeData.map((node) => String(node.type ?? node.kind ?? "Node")))].sort(), [nodeData]);
  const decoratedElements = useMemo(() => {
    const linkCounts = new Map<string, number>();
    for (const element of elements) {
      const source = element.data?.source;
      const target = element.data?.target;
      if (!source || !target) continue;
      linkCounts.set(String(source), (linkCounts.get(String(source)) ?? 0) + 1);
      if (target !== source) linkCounts.set(String(target), (linkCounts.get(String(target)) ?? 0) + 1);
    }
    return elements.map((element) => {
      if (element.data?.source) return element;
      const type = String(element.data.type ?? element.data.kind ?? "Node");
      const links = linkCounts.get(String(element.data.id)) ?? 0;
      const size = nodeSize(links);
      return {
        ...element,
        data: { ...element.data, type, color: colorFor(type), links, size, hoverSize: size + 7, searchSize: size + 12, selectedSize: size + 14 },
      };
    });
  }, [elements]);
  const matches = useMemo(() => {
    const value = query.trim().toLocaleLowerCase();
    if (!value) return [];
    return nodeData.filter((node) => `${node.label} ${node.id} ${node.type ?? ""}`.toLocaleLowerCase().includes(value)).slice(0, 7);
  }, [nodeData, query]);

  function selectedDetails(node: cytoscape.NodeSingular): SelectedNode {
    const neighbors = node.neighborhood("node").map((neighbor) => ({
      id: String(neighbor.id()), label: String(neighbor.data("label") ?? neighbor.id()), route: neighbor.data("route"),
    }));
    return {
      id: node.id(), label: String(node.data("label") ?? node.id()), type: String(node.data("type") ?? "Node"),
      kind: String(node.data("kind") ?? "Node"), route: node.data("route"),
      incoming: node.incomers("edge").length, outgoing: node.outgoers("edge").length, neighbors,
    };
  }

  function focusNode(id: string) {
    const graph = graphRef.current;
    if (!graph) return;
    const node = graph.getElementById(id);
    if (!node.length) return;
    graph.elements().unselect();
    node.select();
    setSelected(selectedDetails(node));
    graph.animate({ center: { eles: node }, zoom: Math.max(graph.zoom(), 1.25), duration: 350 });
  }

  function runLayout(mode: LayoutMode, animate = true) {
    const graph = graphRef.current;
    if (!graph) return;
    setLayout(mode);
    const visible = graph.elements().not(".filtered-out");
    const options = mode === "radial"
      ? { name: "concentric", concentric: (node: cytoscape.NodeSingular) => node.degree(), levelWidth: () => 2, minNodeSpacing: 65 }
      : mode === "hierarchy"
        ? { name: "breadthfirst", directed: true, spacingFactor: 1.35, circle: false }
        : { name: "cose", idealEdgeLength: 120, nodeRepulsion: 8500, gravity: .35, randomize: false };
    visible.layout({ ...options, animate, animationDuration: 480, fit: true, padding: 70 } as any).run();
  }

  useEffect(() => {
    if (!container.current) return;
    const graph = cytoscape({
      container: container.current,
      elements: decoratedElements,
      wheelSensitivity: .18,
      minZoom: .18,
      maxZoom: 3.2,
      boxSelectionEnabled: true,
      style: graphStyle(document.documentElement.dataset.theme === "dark") as any,
      layout: { name: "cose", animate: false, idealEdgeLength: 120, nodeRepulsion: 8500, gravity: .35 },
    });
    graphRef.current = graph;
    graph.on("mouseover", "node", (event) => event.target.addClass("hovered"));
    graph.on("mouseout", "node", (event) => event.target.removeClass("hovered"));
    graph.on("tap", "node", (event) => {
      const node = event.target;
      graph.elements().removeClass("context-dim context-edge");
      graph.elements().not(node.closedNeighborhood()).addClass("context-dim");
      node.connectedEdges().addClass("context-edge");
      setSelected(selectedDetails(node));
    });
    graph.on("dbltap", "node", (event) => { const route = event.target.data("route"); if (route) location.href = route; });
    graph.on("tap", (event) => { if (event.target === graph) { graph.elements().removeClass("context-dim context-edge").unselect(); setSelected(null); } });
    const themeObserver = new MutationObserver(() => {
      graph.style(graphStyle(document.documentElement.dataset.theme === "dark") as any);
    });
    themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
    return () => { themeObserver.disconnect(); graphRef.current = null; graph.destroy(); };
  }, [decoratedElements]);

  useEffect(() => {
    const graph = graphRef.current;
    if (!graph) return;
    graph.nodes().removeClass("search-match filtered-out");
    if (typeFilter !== "all") graph.nodes().filter((node) => node.data("type") !== typeFilter).addClass("filtered-out");
    const value = query.trim().toLocaleLowerCase();
    if (value) graph.nodes().filter((node) => `${node.data("label")} ${node.id()} ${node.data("type")}`.toLocaleLowerCase().includes(value)).not(".filtered-out").addClass("search-match");
  }, [query, typeFilter]);

  return <div className={`modern-graph ${selected ? "has-selection" : ""}`}>
    <div className="graph-commandbar">
      <div className="graph-search-wrap">
        <span aria-hidden="true">⌕</span>
        <input value={query} onChange={(event) => setQuery(event.target.value)} onKeyDown={(event) => event.key === "Enter" && matches[0] && focusNode(matches[0].id)} placeholder="ノードを検索" aria-label="ノードを検索" />
        {query && <button type="button" onClick={() => setQuery("")} aria-label="検索をクリア">×</button>}
        {query && <div className="graph-search-results">{matches.length ? matches.map((node) => <button type="button" key={node.id} onClick={() => { focusNode(node.id); setQuery(""); }}><span style={{ background: colorFor(String(node.type ?? node.kind)) }}></span><strong>{node.label}</strong><small>{node.type ?? node.kind}</small></button>) : <p>一致するノードはありません</p>}</div>}
      </div>
      <div className="layout-switcher" role="group" aria-label="レイアウト">
        {([['force','自由'],['radial','放射'],['hierarchy','階層']] as const).map(([mode,label]) => <button className={layout === mode ? "active" : ""} type="button" onClick={() => runLayout(mode)}>{label}</button>)}
      </div>
      <div className="graph-view-actions"><button type="button" onClick={() => graphRef.current?.zoom(graphRef.current.zoom() * 1.22)} aria-label="拡大">＋</button><button type="button" onClick={() => graphRef.current?.zoom(graphRef.current.zoom() / 1.22)} aria-label="縮小">−</button><button type="button" onClick={() => graphRef.current?.fit(graphRef.current.elements().not(".filtered-out"), 60)}>全体</button></div>
    </div>
    <div className="graph-typebar"><button className={typeFilter === "all" ? "active" : ""} onClick={() => setTypeFilter("all")}>すべて <span>{nodeData.length}</span></button>{types.map((type) => <button className={typeFilter === type ? "active" : ""} onClick={() => setTypeFilter(type)}><i style={{ background: colorFor(type) }}></i>{type}<span>{nodeData.filter((node) => (node.type ?? node.kind) === type).length}</span></button>)}</div>
    <div className="graph-stage">
      <div className="graph" ref={container} role="application" aria-label="記事の関係を探索する知識グラフ" />
      <div className="graph-stats"><span><strong>{nodeData.length}</strong> nodes</span><span><strong>{edgeCount}</strong> links</span></div>
      <p className="graph-hint">クリックで近傍を表示 · ダブルクリックで記事を開く</p>
      {selected && <aside className="node-inspector">
        <button className="inspector-close" type="button" onClick={() => { graphRef.current?.elements().removeClass("context-dim context-edge").unselect(); setSelected(null); }} aria-label="詳細を閉じる">×</button>
        <span className="inspector-type"><i style={{ background: colorFor(selected.type) }}></i>{selected.type}</span><h2>{selected.label}</h2><code>{selected.id}</code>
        <dl><div><dt>入ってくるリンク</dt><dd>{selected.incoming}</dd></div><div><dt>出ていくリンク</dt><dd>{selected.outgoing}</dd></div></dl>
        {selected.neighbors.length > 0 && <div className="neighbor-list"><h3>接続先</h3>{selected.neighbors.slice(0, 8).map((neighbor) => <button type="button" onClick={() => focusNode(neighbor.id)}><span>{neighbor.label}</span><small>→</small></button>)}</div>}
        {selected.route && <a className="inspector-open" href={selected.route}>記事を開く <span>↗</span></a>}
      </aside>}
    </div>
  </div>;
}
