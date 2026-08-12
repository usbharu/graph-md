import generatedSite from "virtual:graphmd/site";

export type Backlink = {
  id: string;
  type: string;
  route: string | null;
};

export type SiteValidTime = {
  timeline?: string;
  from?: { coordinate?: unknown; value?: string | null } | null;
  to?: { coordinate?: unknown; value?: string | null } | null;
};

export type SiteDocument = {
  id: string;
  slug: string;
  route: string;
  title: string;
  kind: string;
  type: string | null;
  url: string | null;
  body: string;
  properties: Array<{
    name: string;
    value: unknown;
    validTime: SiteValidTime[];
    fallback: boolean;
  }>;
  schema: Array<{
    name: string;
    schema: {
      type: string;
      required: boolean;
      timeline: unknown;
      timelines: unknown[] | null;
      items: unknown;
      enum: unknown[] | null;
    };
  }>;
  nodeType: null | {
    parents: Array<{ id: string; title: string; route: string | null }>;
    children: Array<{ id: string; title: string; route: string | null }>;
    usage: Array<{ id: string; title: string; kind: "Node" | "Media"; route: string | null }>;
  };
  relationUsage: Array<{
    from: string;
    fromRoute: string | null;
    to: string;
    toRoute: string | null;
    label: string;
    properties: SiteDocument["properties"];
  }>;
  timeline: null | {
    id: string;
    axis: string;
    domain: string;
    coordinate: unknown;
    parent: string | null;
    parentRoute: string | null;
    lineage: null | { sourceTimeline: string; kind: string };
    lineageRoute: string | null;
    mappings: Array<{
      direction: "incoming" | "outgoing";
      source: string;
      sourceRoute: string | null;
      target: string;
      targetRoute: string | null;
      definition: {
        id: string;
        kind: string;
        precision: string;
        scale: { numerator: number; denominator: number };
        offset: { numerator: number; denominator: number };
        traits: Record<string, string>;
      };
    }>;
  };
  backlinks: Backlink[];
};

export type SiteData = {
  base: string;
  documents: SiteDocument[];
  routes: Record<string, string>;
  graph: {
    nodes: unknown[];
    edges: unknown[];
  };
  timelineGraph: {
    nodes: unknown[];
    edges: unknown[];
  };
};

export const site = generatedSite as SiteData;
