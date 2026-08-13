import cytoscape from "cytoscape";
import { useEffect, useRef } from "react";

function timelineStyle(dark: boolean): any[] {
  const colors = dark ? {
    node: "#aab5c3", label: "#e8edf4", current: "#79a6ff", currentBorder: "#263c5c",
    edge: "#718095", background: "#252c35", lineage: "#79a6ff", mapping: "#c596f3", axis: "#58c7aa",
  } : {
    node: "#72777d", label: "#202122", current: "#3366cc", currentBorder: "#eaf3ff",
    edge: "#a2a9b1", background: "#ffffff", lineage: "#3366cc", mapping: "#7b3fb3", axis: "#14866d",
  };
  return [
    { selector: "node", style: { label: "data(label)", "background-color": colors.node, color: colors.label, "text-outline-color": dark ? "#171c22" : "#ffffff", "text-outline-width": dark ? 2 : 1, "text-valign": "bottom", "text-margin-y": 9, width: 16, height: 16, "font-size": 11 } },
    { selector: ".current-timeline", style: { "background-color": colors.current, width: 27, height: 27, "font-weight": 700, "border-width": 4, "border-color": colors.currentBorder } },
    { selector: "edge", style: { label: "data(label)", color: colors.label, width: 2, "curve-style": "taxi", "taxi-direction": "rightward", "target-arrow-shape": "triangle", "line-color": colors.edge, "target-arrow-color": colors.edge, "font-size": 9, "text-background-color": colors.background, "text-background-opacity": .96, "text-background-padding": 3 } },
    { selector: 'edge[kind = "lineage"]', style: { width: 4, "line-color": colors.lineage, "target-arrow-color": colors.lineage } },
    { selector: 'edge[kind = "mapping"]', style: { "line-style": "dashed", "line-color": colors.mapping, "target-arrow-color": colors.mapping } },
    { selector: 'edge[kind = "sameAxis"]', style: { "line-color": colors.axis, "target-arrow-color": colors.axis } },
  ];
}

export default function TimelineGraph({ elements, selected }: { elements: any[]; selected: string }) {
  const container = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!container.current) return;
    const graph = cytoscape({
      container: container.current,
      elements,
      layout: { name: "breadthfirst", directed: true, spacingFactor: 1.25, padding: 36 },
      style: timelineStyle(document.documentElement.dataset.theme === "dark") as any,
      minZoom: 0.4,
      maxZoom: 2.5,
    });
    graph.getElementById(selected).addClass("current-timeline");
    graph.on("tap", "node", (event) => {
      const route = event.target.data("route");
      if (route) location.href = route;
    });
    graph.fit(undefined, 34);
    const themeObserver = new MutationObserver(() => {
      graph.style(timelineStyle(document.documentElement.dataset.theme === "dark") as any);
    });
    themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
    return () => { themeObserver.disconnect(); graph.destroy(); };
  }, [elements, selected]);

  return <div className="timeline-graph" ref={container} role="application" aria-label={`${selected}のタイムライン派生グラフ`} />;
}
