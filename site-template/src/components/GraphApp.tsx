import cytoscape from "cytoscape";
import { useEffect, useRef } from "react";

export default function GraphApp({ elements }: { elements: any[] }) {
  const container = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!container.current) return;
    const graph = cytoscape({
      container: container.current,
      elements,
      style: [
        {
          selector: "node",
          style: {
            label: "data(label)",
            "background-color": "#4f46e5",
            color: "#111827",
            "text-valign": "bottom",
            "text-margin-y": 8,
          },
        },
        {
          selector: "edge",
          style: {
            label: "data(label)",
            width: 2,
            "line-color": "#a5b4fc",
            "target-arrow-color": "#a5b4fc",
            "target-arrow-shape": "triangle",
            "curve-style": "bezier",
            "font-size": 10,
          },
        },
      ],
      layout: { name: "cose", animate: false },
    });
    graph.on("tap", "node", (event) => {
      const route = event.target.data("route");
      if (route) location.href = route;
    });
    return () => graph.destroy();
  }, [elements]);

  return <div className="graph" ref={container} />;
}
