import type { AstroIntegration } from "astro";

export interface GraphMdSource {
  path: string;
  text: string;
}

export interface GraphMdDiagnostic {
  severity: "error" | "warning" | "info";
  category: string;
  message: string;
  source?: {
    path: string;
    documentId?: string;
    range?: { start: number; end: number };
  };
}

export interface GraphMdIntegrationOptions {
  /** Paths relative to Astro's root. Defaults to `["documents"]`. */
  roots?: string[];
  /** File extensions compiled as Graph Markdown. Defaults to `.md`. */
  extensions?: string[];
}

export declare const VIRTUAL_MODULES: Readonly<{
  graph: "virtual:graphmd/graph";
  sources: "virtual:graphmd/sources";
  search: "virtual:graphmd/search";
  diagnostics: "virtual:graphmd/diagnostics";
}>;

export default function graphMdIntegration(
  options?: GraphMdIntegrationOptions,
): AstroIntegration;
