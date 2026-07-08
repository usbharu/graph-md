import * as path from "node:path";
import * as vscode from "vscode";
import { Executable, LanguageClient, LanguageClientOptions, ServerOptions, TransportKind } from "vscode-languageclient/node";
import { graphMdPlugin } from "markdown-it-graphmd";

let client: LanguageClient | undefined;
const semanticLegend = new vscode.SemanticTokensLegend([
  "graphmdRelationOperator",
  "graphmdRelationLabel",
  "graphmdRelationTarget",
  "graphmdRelationType",
  "graphmdProperty",
]);

export interface GraphMdMarkdownApi {
  extendMarkdownIt(md: any): any;
}

export async function activate(context: vscode.ExtensionContext): Promise<GraphMdMarkdownApi> {
  const scriptName = process.platform === "win32" ? "lsp.bat" : "lsp";
  const command = context.asAbsolutePath(path.join("server", "bin", scriptName));
  const serverOptions: ServerOptions = {
    run: executable(command),
    debug: executable(command),
  };

  const watcher = vscode.workspace.createFileSystemWatcher("**/*.md");
  context.subscriptions.push(watcher);
  context.subscriptions.push(
    vscode.languages.registerDocumentSemanticTokensProvider(
      { language: "markdown", scheme: "file" },
      new GraphMdSemanticTokensProvider(),
      semanticLegend,
    ),
  );

  const clientOptions: LanguageClientOptions = {
    documentSelector: [{ scheme: "file", language: "markdown" }],
    synchronize: {
      fileEvents: watcher,
    },
  };

  client = new LanguageClient("graphmd-lsp", "GraphMD LSP", serverOptions, clientOptions);
  await client.start();
  context.subscriptions.push({
    dispose: () => {
      void client?.stop();
    },
  });

  return {
    extendMarkdownIt(md: any): any {
      md.use(graphMdPlugin);
      return md;
    },
  };
}

export async function deactivate(): Promise<void> {
  await client?.stop();
}

function executable(command: string): Executable {
  return {
    command,
    transport: TransportKind.stdio,
  };
}

class GraphMdSemanticTokensProvider implements vscode.DocumentSemanticTokensProvider {
  provideDocumentSemanticTokens(document: vscode.TextDocument): vscode.ProviderResult<vscode.SemanticTokens> {
    const builder = new vscode.SemanticTokensBuilder(semanticLegend);
    const text = document.getText();

    for (const relation of scanRelations(text)) {
      pushRange(builder, document, relation.operatorStart, 2, "graphmdRelationOperator");
      pushRange(builder, document, relation.labelStart, relation.labelEnd - relation.labelStart, "graphmdRelationLabel");
      pushRange(builder, document, relation.labelEnd, 1, "graphmdRelationOperator");
      pushRange(builder, document, relation.targetStart, relation.targetEnd - relation.targetStart, "graphmdRelationTarget");
      pushRange(builder, document, relation.typeStart, relation.typeEnd - relation.typeStart, "graphmdRelationType");
      for (const prop of relation.props) {
        pushRange(builder, document, prop.start, prop.end - prop.start, "graphmdProperty");
      }
    }

    for (const propsBlock of scanPropsBlocks(text)) {
      pushRange(builder, document, propsBlock.keywordStart, 6, "graphmdRelationOperator");
      for (const prop of propsBlock.props) {
        pushRange(builder, document, prop.start, prop.end - prop.start, "graphmdProperty");
      }
    }

    return builder.build();
  }
}

function pushRange(
  builder: vscode.SemanticTokensBuilder,
  document: vscode.TextDocument,
  start: number,
  length: number,
  tokenType:
    | "graphmdRelationOperator"
    | "graphmdRelationLabel"
    | "graphmdRelationTarget"
    | "graphmdRelationType"
    | "graphmdProperty",
): void {
  if (length <= 0) {
    return;
  }
  const range = new vscode.Range(document.positionAt(start), document.positionAt(start + length));
  builder.push(range, tokenType);
}

function scanRelations(text: string): Array<{
  operatorStart: number;
  labelStart: number;
  labelEnd: number;
  targetStart: number;
  targetEnd: number;
  typeStart: number;
  typeEnd: number;
  props: Array<{ start: number; end: number }>;
}> {
  const relations: Array<{
    operatorStart: number;
    labelStart: number;
    labelEnd: number;
    targetStart: number;
    targetEnd: number;
    typeStart: number;
    typeEnd: number;
    props: Array<{ start: number; end: number }>;
  }> = [];

  for (let index = 0; index < text.length; index += 1) {
    if (text[index] !== "@" || text[index + 1] !== "[" || isEscaped(text, index)) {
      continue;
    }
    const closeLabel = findUnescaped(text, "]", index + 2);
    if (closeLabel == null || text[closeLabel + 1] !== "(") {
      continue;
    }
    const closeParen = findUnescaped(text, ")", closeLabel + 2);
    if (closeParen == null) {
      continue;
    }
    const inside = text.slice(closeLabel + 2, closeParen);
    const parsed = parseRelationTargetAndType(inside);
    if (!parsed) {
      continue;
    }
    const targetStart = closeLabel + 2 + inside.indexOf(parsed.target);
    const relTypeToken = parsed.relTypeToken;
    const typeStart = closeLabel + 2 + inside.lastIndexOf(relTypeToken);
    const props = scanInlineObjectProperties(text, skipWhitespace(text, closeParen + 1));
    relations.push({
      operatorStart: index,
      labelStart: index + 2,
      labelEnd: closeLabel,
      targetStart,
      targetEnd: targetStart + parsed.target.length,
      typeStart,
      typeEnd: typeStart + relTypeToken.length,
      props,
    });
    index = closeParen;
  }

  return relations;
}

function scanPropsBlocks(text: string): Array<{
  keywordStart: number;
  props: Array<{ start: number; end: number }>;
}> {
  const blocks: Array<{
    keywordStart: number;
    props: Array<{ start: number; end: number }>;
  }> = [];

  for (let index = 0; index < text.length; index += 1) {
    if (!text.startsWith("@props", index)) {
      continue;
    }
    const braceStart = skipWhitespace(text, index + 6);
    if (text[braceStart] !== "{") {
      continue;
    }
    blocks.push({
      keywordStart: index,
      props: scanInlineObjectProperties(text, braceStart),
    });
    index = braceStart;
  }

  return blocks;
}

function scanInlineObjectProperties(text: string, braceStart: number): Array<{ start: number; end: number }> {
  if (braceStart < 0 || text[braceStart] !== "{") {
    return [];
  }
  const props: Array<{ start: number; end: number }> = [];
  const stack: number[] = [braceStart];
  let index = braceStart + 1;
  let inString = false;
  let escaped = false;

  while (index < text.length && stack.length > 0) {
    const char = text[index];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (char === "\\") {
        escaped = true;
      } else if (char === "\"") {
        inString = false;
      }
      index += 1;
      continue;
    }

    if (char === "\"") {
      inString = true;
      index += 1;
      continue;
    }
    if (char === "{") {
      stack.push(index);
      index += 1;
      continue;
    }
    if (char === "}") {
      stack.pop();
      index += 1;
      continue;
    }

    if (isIdentifierStart(char)) {
      const start = index;
      let end = index + 1;
      while (end < text.length && isIdentifierPart(text[end])) {
        end += 1;
      }
      let cursor = end;
      while (cursor < text.length && /\s/.test(text[cursor])) {
        cursor += 1;
      }
      if (text[cursor] === "=") {
        props.push({ start, end });
      }
      index = end;
      continue;
    }

    index += 1;
  }

  return props;
}

function skipWhitespace(text: string, index: number): number {
  let cursor = index;
  while (cursor < text.length && /\s/.test(text[cursor])) {
    cursor += 1;
  }
  return cursor;
}

function findUnescaped(text: string, target: string, start: number): number | undefined {
  let escaped = false;
  for (let index = start; index < text.length; index += 1) {
    const char = text[index];
    if (escaped) {
      escaped = false;
      continue;
    }
    if (char === "\\") {
      escaped = true;
      continue;
    }
    if (char === "\n") {
      return undefined;
    }
    if (char === target) {
      return index;
    }
  }
  return undefined;
}

function isEscaped(text: string, index: number): boolean {
  let slashCount = 0;
  for (let cursor = index - 1; cursor >= 0 && text[cursor] === "\\"; cursor -= 1) {
    slashCount += 1;
  }
  return slashCount % 2 === 1;
}

function isIdentifierStart(char: string): boolean {
  return /[A-Za-z_]/.test(char);
}

function isIdentifierPart(char: string): boolean {
  return /[A-Za-z0-9_.:-]/.test(char);
}

function parseRelationTargetAndType(value: string): { target: string; relType: string; relTypeToken: string } | undefined {
  const trimmed = value.trim();
  const separator = trimmed.search(/[ \t]/);
  if (separator <= 0) {
    return undefined;
  }
  const target = trimmed.slice(0, separator);
  const typePart = trimmed.slice(separator).trim();
  if (!typePart) {
    return undefined;
  }
  if (typePart.startsWith("\"")) {
    const relType = parseQuotedRelationType(typePart);
    if (!relType) {
      return undefined;
    }
    return { target, relType, relTypeToken: typePart };
  }
  if (/[ \t)]/.test(typePart)) {
    return undefined;
  }
  return { target, relType: typePart, relTypeToken: typePart };
}

function parseQuotedRelationType(value: string): string | undefined {
  let result = "";
  let escaped = false;
  for (let index = 1; index < value.length; index += 1) {
    const char = value[index];
    if (escaped) {
      result += char;
      escaped = false;
      continue;
    }
    if (char === "\\") {
      escaped = true;
      continue;
    }
    if (char === "\"") {
      return value.slice(index + 1).trim().length === 0 ? result : undefined;
    }
    result += char;
  }
  return undefined;
}
