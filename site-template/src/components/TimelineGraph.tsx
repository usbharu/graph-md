import cytoscape from "cytoscape";
import { useEffect, useRef } from "react";

export default function TimelineGraph({ elements, selected }: { elements: any[]; selected: string }) {
  const container = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!container.current) return;
    const graph = cytoscape({
      container: container.current,
      elements,
      layout: { name: "breadthfirst", directed: true, spacingFactor: 1.25, padding: 36 },
      style: [
        { selector: "node", style: { label: "data(label)", "background-color": "#72777d", color: "#202122", "text-valign": "bottom", "text-margin-y": 9, width: 16, height: 16, "font-size": 11 } },
        { selector: ".current-timeline", style: { "background-color": "#3366cc", width: 27, height: 27, "font-weight": 700, "border-width": 4, "border-color": "#eaf3ff" } },
        { selector: "edge", style: { label: "data(label)", width: 2, "curve-style": "taxi", "taxi-direction": "rightward", "target-arrow-shape": "triangle", "line-color": "#a2a9b1", "target-arrow-color": "#a2a9b1", "font-size": 9, "text-background-color": "#fff", "text-background-opacity": 1, "text-background-padding": 3 } },
        { selector: 'edge[kind = "lineage"]', style: { width: 4, "line-color": "#3366cc", "target-arrow-color": "#3366cc" } },
        { selector: 'edge[kind = "mapping"]', style: { "line-style": "dashed", "line-color": "#7b3fb3", "target-arrow-color": "#7b3fb3" } },
        { selector: 'edge[kind = "sameAxis"]', style: { "line-color": "#14866d", "target-arrow-color": "#14866d" } },
      ] as any,
      minZoom: 0.4,
      maxZoom: 2.5,
    });
    graph.getElementById(selected).addClass("current-timeline");
    graph.on("tap", "node", (event) => {
      const route = event.target.data("route");
      if (route) location.href = route;
    });
    graph.fit(undefined, 34);
    return () => graph.destroy();
  }, [elements, selected]);

  return <div className="timeline-graph" ref={container} role="application" aria-label={`${selected}のタイムライン派生グラフ`} />;
}
