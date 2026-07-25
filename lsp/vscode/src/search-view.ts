import { randomBytes } from "node:crypto";
import * as vscode from "vscode";
import type { LanguageClient } from "vscode-languageclient/node";
import type { SearchMetadata } from "./search-query";

type SearchLocation = {
  uri: string;
  range: {
    start: { line: number; character: number };
    end: { line: number; character: number };
  };
};

type WebviewMessage =
  | { type: "ready" }
  | { type: "search"; requestId: number; query: string; parameters: Record<string, string> }
  | { type: "open"; location: SearchLocation };

export class GraphMdSearchViewProvider implements vscode.WebviewViewProvider {
  static readonly viewType = "graphmd.search";

  constructor(
    private readonly extensionUri: vscode.Uri,
    private readonly client: LanguageClient,
  ) {}

  resolveWebviewView(view: vscode.WebviewView): void {
    view.webview.options = {
      enableScripts: true,
      localResourceRoots: [vscode.Uri.joinPath(this.extensionUri, "dist")],
    };
    view.webview.html = this.html(view.webview);
    view.webview.onDidReceiveMessage((message: unknown) => void this.receive(view.webview, message));
  }

  private async receive(webview: vscode.Webview, value: unknown): Promise<void> {
    if (!isWebviewMessage(value)) return;
    try {
      if (value.type === "ready") {
        const metadata = await this.client.sendRequest<SearchMetadata>("graphmd/searchMetadata");
        await webview.postMessage({ type: "metadata", metadata });
      } else if (value.type === "search") {
        const result = await this.client.sendRequest("graphmd/search", {
          query: value.query,
          parameters: value.parameters,
        });
        await webview.postMessage({ type: "result", requestId: value.requestId, result });
      } else {
        const uri = vscode.Uri.parse(value.location.uri);
        const document = await vscode.workspace.openTextDocument(uri);
        const editor = await vscode.window.showTextDocument(document);
        const range = new vscode.Range(
          value.location.range.start.line,
          value.location.range.start.character,
          value.location.range.end.line,
          value.location.range.end.character,
        );
        editor.selection = new vscode.Selection(range.start, range.end);
        editor.revealRange(range, vscode.TextEditorRevealType.InCenterIfOutsideViewport);
      }
    } catch (error) {
      await webview.postMessage({
        type: "error",
        requestId: value.type === "search" ? value.requestId : undefined,
        message: error instanceof Error ? error.message : String(error),
      });
    }
  }

  private html(webview: vscode.Webview): string {
    const nonce = randomBytes(16).toString("base64");
    const scriptUri = webview.asWebviewUri(vscode.Uri.joinPath(this.extensionUri, "dist", "search-webview.js"));
    return `<!DOCTYPE html>
<html lang="ja">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'nonce-${nonce}'; script-src 'nonce-${nonce}';">
  <style nonce="${nonce}">
    * { box-sizing: border-box; }
    body { margin: 0; padding: 12px; color: var(--vscode-foreground); font-family: var(--vscode-font-family); }
    button, input, select, textarea { font: inherit; }
    button { color: var(--vscode-button-foreground); background: var(--vscode-button-background); border: 0; padding: 6px 10px; cursor: pointer; }
    button:hover { background: var(--vscode-button-hoverBackground); }
    button.secondary { color: var(--vscode-button-secondaryForeground); background: var(--vscode-button-secondaryBackground); }
    button.icon { padding: 3px 7px; }
    input, select, textarea { width: 100%; color: var(--vscode-input-foreground); background: var(--vscode-input-background); border: 1px solid var(--vscode-input-border, transparent); padding: 5px 6px; }
    textarea { min-height: 180px; resize: vertical; font-family: var(--vscode-editor-font-family); }
    label { display: block; margin: 10px 0 4px; font-size: 12px; }
    .tabs { display: flex; gap: 2px; border-bottom: 1px solid var(--vscode-panel-border); margin-bottom: 12px; }
    .tab { color: var(--vscode-foreground); background: transparent; border-bottom: 2px solid transparent; }
    .tab.active { border-bottom-color: var(--vscode-focusBorder); }
    .hidden { display: none !important; }
    .row { display: grid; grid-template-columns: 1fr auto; gap: 6px; margin: 6px 0; align-items: center; }
    .condition { display: grid; grid-template-columns: minmax(80px, 1.2fr) minmax(70px, .8fr) minmax(70px, 1fr) auto; gap: 4px; margin: 6px 0; }
    .actions { display: flex; gap: 6px; margin: 12px 0; }
    .status { min-height: 20px; color: var(--vscode-descriptionForeground); }
    .diagnostics { padding: 0; list-style: none; color: var(--vscode-errorForeground); }
    .diagnostics li { margin: 6px 0; }
    .results { overflow: auto; max-height: 45vh; border: 1px solid var(--vscode-panel-border); }
    table { width: 100%; border-collapse: collapse; font-size: 12px; }
    th, td { text-align: left; padding: 5px 7px; border-bottom: 1px solid var(--vscode-panel-border); white-space: nowrap; }
    th { position: sticky; top: 0; background: var(--vscode-sideBar-background); }
    tr.clickable { cursor: pointer; }
    tr.clickable:hover { background: var(--vscode-list-hoverBackground); }
    .two { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }
    .parameter { grid-template-columns: 1fr 1fr auto; }
    h3 { font-size: 13px; margin: 14px 0 6px; }
  </style>
</head>
<body>
  <div class="tabs">
    <button class="tab active" data-tab="form">フォーム</button>
    <button class="tab" data-tab="gmql">GMQL</button>
  </div>
  <section id="form-panel">
    <label for="node-type">ノード型</label><select id="node-type"><option value="">すべて</option></select>
    <label for="keyword">全文検索</label><input id="keyword" type="search" placeholder="キーワード">
    <h3>プロパティ条件</h3><div id="conditions"></div>
    <button id="add-condition" class="secondary">条件を追加</button>
    <div class="two">
      <div><label for="temporal-mode">時間条件</label><select id="temporal-mode"><option value="anytime">Anytime</option><option value="at">特定時点</option><option value="overlaps">期間の重複</option></select></div>
      <div id="timeline-wrap" class="hidden"><label for="timeline">タイムライン</label><select id="timeline"></select></div>
    </div>
    <div id="at-fields" class="hidden"><label for="instant">時点</label><input id="instant" type="number" step="any"></div>
    <div id="range-fields" class="two hidden"><div><label for="from">開始</label><input id="from" type="number" step="any"></div><div><label for="to">終了</label><input id="to" type="number" step="any"></div></div>
    <div class="two">
      <div><label for="sort">並び順</label><select id="sort"><option value="relevance">関連度</option><option value="id-asc">ID昇順</option><option value="id-desc">ID降順</option></select></div>
      <div><label for="limit">件数</label><input id="limit" type="number" min="1" max="1000" value="100"></div>
    </div>
    <div class="actions"><button id="form-search">検索</button></div>
  </section>
  <section id="gmql-panel" class="hidden">
    <label for="gmql">GMQLクエリ</label>
    <textarea id="gmql" spellcheck="false">MATCH (node)
RETURN ID(node) AS id, TYPE(node) AS type, SCORE() AS score, VALIDITY() AS validity
ORDER BY id ASC
LIMIT 100</textarea>
    <h3>パラメータ</h3><div id="parameters"></div>
    <button id="add-parameter" class="secondary">パラメータを追加</button>
    <div class="actions"><button id="gmql-search">実行</button></div>
  </section>
  <div id="status" class="status"></div>
  <ul id="diagnostics" class="diagnostics"></ul>
  <div id="results" class="results hidden"></div>
  <script nonce="${nonce}" src="${scriptUri}"></script>
</body>
</html>`;
  }
}

function isWebviewMessage(value: unknown): value is WebviewMessage {
  if (!value || typeof value !== "object" || !("type" in value)) return false;
  const message = value as Record<string, unknown>;
  if (message.type === "ready") return true;
  if (message.type === "search") {
    return typeof message.requestId === "number"
      && typeof message.query === "string"
      && !!message.parameters
      && typeof message.parameters === "object";
  }
  return message.type === "open" && !!message.location && typeof message.location === "object";
}
