import generatedSite from "../generated/site.json";

export type Backlink = {
  id: string;
  type: string;
  route: string | null;
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
};

export const site = generatedSite as SiteData;
