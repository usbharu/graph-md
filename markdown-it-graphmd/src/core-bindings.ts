// Kotlin/JS production library output (UMD, interpreted as CommonJS via
// vendor/package.json). No first-party type declarations ship with it, so the
// import is intentionally untyped and re-exposed through this thin typed layer.
// @ts-expect-error - generated Kotlin/JS module without declaration file
import coreModule from "../vendor/graph-md-core.js";

export interface RelationParts {
  readonly target: string;
  readonly relType: string;
}

export interface EmbedParts {
  readonly kind: "query" | "back-link";
  readonly value: string;
}

interface GraphMdInlineApi {
  parseRelationTargetAndType(inside: string): RelationParts | null;
  parseInlineObjectJson(content: string): string;
  parsePropsDirectiveJson(content: string): string;
  isValidBlockHeader(content: string): boolean;
  parseEmbedHeader(content: string): EmbedParts | null;
}

interface CoreModule {
  readonly dev: {
    readonly usbharu: {
      readonly graphmd: {
        readonly core: {
          readonly GraphMdInline: GraphMdInlineApi;
        };
      };
    };
  };
}

const inline = (coreModule as unknown as CoreModule).dev.usbharu.graphmd.core.GraphMdInline;

/**
 * Splits `target relType` / `target "rel type"` content from the `(...)`
 * part of a relation link. Returns null on malformed input. Errors thrown by
 * the Kotlin parser are swallowed (rendering must never abort the document).
 */
export function parseRelationTargetAndType(inside: string): RelationParts | null {
  try {
    return inline.parseRelationTargetAndType(inside);
  } catch {
    return null;
  }
}

/**
 * Parses a `{ ... }` inline-object body into a JSON string. Throws if the
 * content is not a valid Graph Markdown inline object; callers should wrap.
 */
export function parseInlineObjectJson(content: string): string {
  return inline.parseInlineObjectJson(content);
}

export function parsePropsDirectiveJson(content: string): string {
  return inline.parsePropsDirectiveJson(content);
}

export function isValidBlockHeader(content: string): boolean {
  return inline.isValidBlockHeader(content);
}

export function parseEmbedHeader(content: string): EmbedParts | null {
  try {
    return inline.parseEmbedHeader(content);
  } catch {
    return null;
  }
}
