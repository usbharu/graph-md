import { isValidBlockHeader } from "./core-bindings";

const COLON = 0x3a;
const SPACE = 0x20;
const TAB = 0x09;
const VALID_BODY_BLOCKS = "__graphmdValidBodyBlocks";

interface BlockMarker {
  fenceLength: number;
  header: string | null;
}

interface Fence {
  marker: number;
  length: number;
}

interface SourceLine {
  start: number;
  end: number;
}

interface ValidBodyBlock {
  openLine: number;
  opening: BlockMarker & { header: string };
  closeLine: number;
}

export function bodyBlockRule(state: any, startLine: number, endLine: number, silent: boolean): boolean {
  if (state.blkIndent !== 0) return false;
  if (
    state.parentType !== "root" &&
    state.parentType !== "graphmd_block" &&
    !(silent && state.parentType === "paragraph")
  ) {
    return false;
  }
  const block = validBodyBlocks(state).get(startLine);
  if (block === undefined || block.closeLine >= endLine) return false;
  if (silent) return true;

  const { opening, closeLine } = block;
  const openToken = state.push("graphmd_body_block_open", "", 1);
  openToken.block = true;
  openToken.map = [startLine, closeLine + 1];
  openToken.markup = ":".repeat(opening.fenceLength);
  openToken.meta = { header: opening.header, fenceLength: opening.fenceLength };

  const previousParent = state.parentType;
  state.parentType = "graphmd_block";
  try {
    state.md.block.tokenize(state, startLine + 1, closeLine);
  } finally {
    state.parentType = previousParent;
  }

  const closeToken = state.push("graphmd_body_block_close", "", -1);
  closeToken.block = true;
  closeToken.markup = openToken.markup;
  state.line = closeLine + 1;
  return true;
}

export function renderBodyBlockBoundary(): string {
  return "";
}

function validBodyBlocks(state: any): Map<number, ValidBodyBlock> {
  const cached = state[VALID_BODY_BLOCKS] as Map<number, ValidBodyBlock> | undefined;
  if (cached !== undefined) return cached;

  interface OpenBodyBlock {
    line: number;
    opening: BlockMarker & { header: string };
    completedDescendants: ValidBodyBlock[];
  }

  const src: string = state.src;
  const lines = sourceLines(src);
  const committed = new Map<number, ValidBodyBlock>();
  const stack: OpenBodyBlock[] = [];
  const listIndents: number[] = [];
  let codeFence: Fence | null = null;
  lines.forEach((sourceLine, line) => {
    if (isContainerLine(src, sourceLine, listIndents)) return;
    const fence = codeFenceAt(src, sourceLine);
    if (codeFence !== null) {
      if (fence?.marker === codeFence.marker && fence.length >= codeFence.length && fence.trailingBlank) {
        codeFence = null;
      }
      return;
    }
    if (fence !== null) {
      codeFence = { marker: fence.marker, length: fence.length };
      return;
    }

    const marker = markerAt(src, sourceLine);
    if (marker === null) return;
    if (marker.header !== null) {
      const parent = stack[stack.length - 1];
      if (
        (parent === undefined || marker.fenceLength > parent.opening.fenceLength) &&
        isValidBlockHeader(marker.header)
      ) {
        stack.push({
          line,
          opening: { fenceLength: marker.fenceLength, header: marker.header },
          completedDescendants: [],
        });
      }
      return;
    }

    const open = stack[stack.length - 1];
    if (open === undefined || marker.fenceLength !== open.opening.fenceLength) return;
    stack.pop();
    const completed = { openLine: open.line, opening: open.opening, closeLine: line };
    const completedSubtree = [...open.completedDescendants, completed];
    const parent = stack[stack.length - 1];
    if (parent === undefined) {
      completedSubtree.forEach((entry) => committed.set(entry.openLine, entry));
    } else {
      parent.completedDescendants.push(...completedSubtree);
    }
  });
  state[VALID_BODY_BLOCKS] = committed;
  return committed;
}

function sourceLines(src: string): SourceLine[] {
  const lines: SourceLine[] = [];
  let start = 0;
  while (start < src.length) {
    const newline = src.indexOf("\n", start);
    const rawEnd = newline < 0 ? src.length : newline;
    const end = rawEnd > start && src.charCodeAt(rawEnd - 1) === 0x0d ? rawEnd - 1 : rawEnd;
    lines.push({ start, end });
    if (newline < 0) break;
    start = newline + 1;
  }
  return lines;
}

function markerAt(src: string, line: SourceLine): BlockMarker | null {
  let cursor = line.start;
  const lineEnd = line.end;
  let spaces = 0;
  while (cursor < lineEnd && src.charCodeAt(cursor) === SPACE && spaces < 4) {
    cursor += 1;
    spaces += 1;
  }
  if (spaces > 3) return null;
  if (src.charCodeAt(cursor) !== COLON) return null;
  const start = cursor;
  while (cursor < lineEnd && src.charCodeAt(cursor) === COLON) cursor += 1;
  const fenceLength = cursor - start;
  if (fenceLength < 3) return null;
  if (cursor === lineEnd) return { fenceLength, header: null };
  if (src.charCodeAt(cursor) !== SPACE && src.charCodeAt(cursor) !== TAB) return null;
  while (cursor < lineEnd && (src.charCodeAt(cursor) === SPACE || src.charCodeAt(cursor) === TAB)) cursor += 1;
  if (cursor === lineEnd) return { fenceLength, header: null };
  return { fenceLength, header: src.slice(cursor, lineEnd).trimEnd() };
}

function codeFenceAt(
  src: string,
  line: SourceLine,
): (Fence & { trailingBlank: boolean }) | null {
  let cursor = line.start;
  const lineEnd = line.end;
  let spaces = 0;
  while (cursor < lineEnd && src.charCodeAt(cursor) === SPACE && spaces < 4) {
    cursor += 1;
    spaces += 1;
  }
  if (spaces > 3) return null;
  const marker = src.charCodeAt(cursor);
  if (marker !== 0x60 && marker !== 0x7e) return null;
  const start = cursor;
  while (cursor < lineEnd && src.charCodeAt(cursor) === marker) cursor += 1;
  const length = cursor - start;
  if (length < 3) return null;
  const remainder = src.slice(cursor, lineEnd);
  if (marker === 0x60 && remainder.includes("`")) return null;
  return {
    marker,
    length,
    trailingBlank: remainder.trim().length === 0,
  };
}

function isContainerLine(src: string, line: SourceLine, listIndents: number[]): boolean {
  const lineStart = line.start;
  const lineEnd = line.end;
  let cursor = lineStart;
  while (cursor < lineEnd && src.charCodeAt(cursor) === SPACE) cursor += 1;
  const indent = cursor - lineStart;
  if (cursor === lineEnd) return listIndents.length > 0;

  while (listIndents.length > 0 && indent < listIndents[listIndents.length - 1]) {
    listIndents.pop();
  }
  const insideList = listIndents.length > 0;
  const markerLength = listMarkerLength(src, cursor, lineEnd);
  if (markerLength !== null && (insideList || indent <= 3)) {
    const markerEnd = cursor + markerLength;
    let contentStart = markerEnd;
    let spaces = 0;
    while (contentStart < lineEnd && src.charCodeAt(contentStart) === SPACE) {
      contentStart += 1;
      spaces += 1;
    }
    if (contentStart < lineEnd && src.charCodeAt(contentStart) === TAB) contentStart += 1;
    if (spaces > 4) contentStart = markerEnd + 1;
    const empty = contentStart >= lineEnd;
    const contentIndent = empty
      ? indent + markerLength + 1
      : indent + columnWidth(src, cursor, contentStart);
    listIndents.push(contentIndent);
    return true;
  }
  if (insideList) return true;
  return src.charCodeAt(cursor) === 0x3e;
}

function listMarkerLength(src: string, start: number, end: number): number | null {
  const marker = src.charCodeAt(start);
  if (
    (marker === 0x2d || marker === 0x2b || marker === 0x2a) &&
    (start + 1 === end || isHorizontal(src.charCodeAt(start + 1)))
  ) {
    return 1;
  }
  let cursor = start;
  while (cursor < end && cursor - start < 9) {
    const code = src.charCodeAt(cursor);
    if (code < 0x30 || code > 0x39) break;
    cursor += 1;
  }
  if (
    cursor > start &&
    (src.charCodeAt(cursor) === 0x2e || src.charCodeAt(cursor) === 0x29) &&
    (cursor + 1 === end || isHorizontal(src.charCodeAt(cursor + 1)))
  ) {
    return cursor - start + 1;
  }
  return null;
}

function isHorizontal(code: number): boolean {
  return code === SPACE || code === TAB;
}

function columnWidth(src: string, start: number, end: number): number {
  let columns = 0;
  for (let cursor = start; cursor < end; cursor += 1) {
    columns = src.charCodeAt(cursor) === TAB
      ? columns + 4 - (columns % 4)
      : columns + 1;
  }
  return columns;
}
