import { buildFormQuery, type PropertyCondition, type SearchFormState, type SearchMetadata } from "./search-query";

declare function acquireVsCodeApi(): {
  postMessage(message: unknown): void;
  getState(): unknown;
  setState(state: unknown): void;
};

type SearchLocation = {
  uri: string;
  range: { start: { line: number; character: number }; end: { line: number; character: number } };
};
type SearchResult = {
  columns: Array<{ name: string; type: string }>;
  rows: Array<{ values: unknown[]; location?: SearchLocation }>;
  diagnostics: Array<{ code: string; message: string; start?: number; end?: number }>;
};

const vscodeApi = acquireVsCodeApi();
let metadata: SearchMetadata = { nodeTypes: [], timelines: [] };
let requestId = 0;
let latestRequestId = 0;

const byId = <T extends HTMLElement>(id: string): T => {
  const element = document.getElementById(id);
  if (!element) throw new Error(`Missing element: ${id}`);
  return element as T;
};

document.querySelectorAll<HTMLButtonElement>(".tab").forEach((button) => {
  button.addEventListener("click", () => {
    document.querySelectorAll(".tab").forEach((tab) => tab.classList.toggle("active", tab === button));
    byId("form-panel").classList.toggle("hidden", button.dataset.tab !== "form");
    byId("gmql-panel").classList.toggle("hidden", button.dataset.tab !== "gmql");
  });
});

byId<HTMLSelectElement>("node-type").addEventListener("change", refreshConditionProperties);
byId<HTMLSelectElement>("temporal-mode").addEventListener("change", refreshTemporalFields);
byId("form-panel").addEventListener("input", updateGeneratedQuery);
byId("form-panel").addEventListener("change", updateGeneratedQuery);
byId("add-condition").addEventListener("click", () => addCondition());
byId("add-parameter").addEventListener("click", () => addParameter());
byId("form-search").addEventListener("click", runFormSearch);
byId("gmql-search").addEventListener("click", runGmqlSearch);

window.addEventListener("message", (event: MessageEvent) => {
  const message = event.data;
  if (message?.type === "metadata") {
    metadata = message.metadata;
    populateMetadata();
  } else if (message?.type === "result" && message.requestId === latestRequestId) {
    renderResult(message.result as SearchResult);
  } else if (message?.type === "error" && (message.requestId == null || message.requestId === latestRequestId)) {
    setBusy(false);
    renderError(String(message.message));
  }
});

function populateMetadata(): void {
  const nodeType = byId<HTMLSelectElement>("node-type");
  nodeType.replaceChildren(option("", "すべて"), ...metadata.nodeTypes.map((item) => option(item.id, item.id)));
  const timeline = byId<HTMLSelectElement>("timeline");
  timeline.replaceChildren(option("", "指定なし"), ...metadata.timelines.map((item) => option(item, item)));
  refreshConditionProperties();
  refreshTemporalFields();
  updateGeneratedQuery();
}

function addCondition(initial?: Partial<PropertyCondition>): void {
  const row = document.createElement("div");
  row.className = "condition";
  const property = document.createElement("select");
  property.className = "condition-property";
  const operator = document.createElement("select");
  operator.className = "condition-operator";
  const value = document.createElement("input");
  value.className = "condition-value";
  value.placeholder = "値";
  const remove = document.createElement("button");
  remove.className = "secondary icon";
  remove.textContent = "×";
  remove.title = "条件を削除";
  remove.addEventListener("click", () => {
    row.remove();
    updateGeneratedQuery();
  });
  property.addEventListener("change", () => refreshOperator(row));
  row.append(property, operator, value, remove);
  byId("conditions").append(row);
  fillPropertySelect(property, initial?.property);
  refreshOperator(row, initial?.operator);
  value.value = initial?.value ?? "";
  updateGeneratedQuery();
}

function refreshConditionProperties(): void {
  document.querySelectorAll<HTMLSelectElement>(".condition-property").forEach((select) => {
    fillPropertySelect(select, select.value);
    refreshOperator(select.closest(".condition") as HTMLElement);
  });
}

function fillPropertySelect(select: HTMLSelectElement, selected?: string): void {
  const typeId = byId<HTMLSelectElement>("node-type").value;
  const properties = metadata.nodeTypes.find((type) => type.id === typeId)?.properties
    .filter((property) => property.type !== "array" && property.type !== "text") ?? [];
  select.replaceChildren(option("", "プロパティ"), ...properties.map((property) => {
    const item = option(property.name, property.name);
    item.dataset.propertyType = property.type;
    return item;
  }));
  if (selected && properties.some((property) => property.name === selected)) select.value = selected;
}

function refreshOperator(row: HTMLElement, selected?: string): void {
  const property = row.querySelector<HTMLSelectElement>(".condition-property");
  const operator = row.querySelector<HTMLSelectElement>(".condition-operator");
  if (!property || !operator) return;
  const type = property.selectedOptions[0]?.dataset.propertyType ?? "string";
  const names = ["number", "instant", "duration"].includes(type)
    ? ["=", "!=", "<", "<=", ">", ">="]
    : ["=", "!=", "CONTAINS", "STARTS WITH", "ENDS WITH"];
  operator.replaceChildren(...names.map((name) => option(name, name)));
  if (selected && names.includes(selected)) operator.value = selected;
}

function addParameter(name = "", value = ""): void {
  const row = document.createElement("div");
  row.className = "row parameter";
  const nameInput = document.createElement("input");
  nameInput.placeholder = "name";
  nameInput.value = name;
  const valueInput = document.createElement("input");
  valueInput.placeholder = "value";
  valueInput.value = value;
  const remove = document.createElement("button");
  remove.className = "secondary icon";
  remove.textContent = "×";
  remove.addEventListener("click", () => row.remove());
  row.append(nameInput, valueInput, remove);
  byId("parameters").append(row);
}

function runFormSearch(): void {
  try {
    const state = collectForm();
    const built = buildFormQuery(state);
    execute(built.query, built.parameters);
  } catch (error) {
    renderError(error instanceof Error ? error.message : String(error));
  }
}

function collectForm(): SearchFormState {
  const conditions = Array.from(document.querySelectorAll<HTMLElement>(".condition")).map((row) => {
    const property = row.querySelector<HTMLSelectElement>(".condition-property")!;
    return {
      property: property.value,
      propertyType: property.selectedOptions[0]?.dataset.propertyType ?? "string",
      operator: row.querySelector<HTMLSelectElement>(".condition-operator")!.value,
      value: row.querySelector<HTMLInputElement>(".condition-value")!.value,
    };
  });
  return {
    nodeType: byId<HTMLSelectElement>("node-type").value,
    keyword: byId<HTMLInputElement>("keyword").value,
    conditions,
    temporalMode: byId<HTMLSelectElement>("temporal-mode").value as SearchFormState["temporalMode"],
    timeline: byId<HTMLSelectElement>("timeline").value,
    instant: byId<HTMLInputElement>("instant").value,
    from: byId<HTMLInputElement>("from").value,
    to: byId<HTMLInputElement>("to").value,
    sort: byId<HTMLSelectElement>("sort").value as SearchFormState["sort"],
    limit: byId<HTMLInputElement>("limit").valueAsNumber,
  };
}

function runGmqlSearch(): void {
  const parameters: Record<string, string> = {};
  for (const row of document.querySelectorAll<HTMLElement>(".parameter")) {
    const inputs = row.querySelectorAll<HTMLInputElement>("input");
    const name = inputs[0].value.trim();
    if (!name) continue;
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) {
      renderError(`不正なパラメータ名です: ${name}`);
      return;
    }
    if (name in parameters) {
      renderError(`パラメータ名が重複しています: ${name}`);
      return;
    }
    parameters[name] = inputs[1].value;
  }
  execute(byId<HTMLTextAreaElement>("gmql").value, parameters);
}

function execute(query: string, parameters: Record<string, string>): void {
  latestRequestId = ++requestId;
  setBusy(true);
  byId("diagnostics").replaceChildren();
  byId("results").classList.add("hidden");
  vscodeApi.postMessage({ type: "search", requestId: latestRequestId, query, parameters });
}

function renderResult(result: SearchResult): void {
  setBusy(false);
  const diagnostics = byId<HTMLUListElement>("diagnostics");
  diagnostics.replaceChildren(...result.diagnostics.map((item) => {
    const li = document.createElement("li");
    li.textContent = `${item.code}: ${item.message}`;
    return li;
  }));
  const results = byId("results");
  if (!result.rows.length) {
    results.classList.add("hidden");
    byId("status").textContent = result.diagnostics.length ? "検索に失敗しました" : "一致する結果はありません";
    return;
  }
  byId("status").textContent = `${result.rows.length}件`;
  const table = document.createElement("table");
  const head = document.createElement("thead");
  const headerRow = document.createElement("tr");
  result.columns.forEach((column) => {
    const th = document.createElement("th");
    th.textContent = column.name;
    th.title = column.type;
    headerRow.append(th);
  });
  head.append(headerRow);
  const body = document.createElement("tbody");
  result.rows.forEach((row) => {
    const tr = document.createElement("tr");
    if (row.location) {
      tr.className = "clickable";
      tr.title = "文書を開く";
      tr.addEventListener("click", () => vscodeApi.postMessage({ type: "open", location: row.location }));
    }
    row.values.forEach((value) => {
      const td = document.createElement("td");
      td.textContent = formatValue(value);
      tr.append(td);
    });
    body.append(tr);
  });
  table.append(head, body);
  results.replaceChildren(table);
  results.classList.remove("hidden");
}

function formatValue(value: unknown): string {
  if (value == null) return "null";
  if (typeof value !== "object") return String(value);
  const record = value as Record<string, unknown>;
  if (record.kind === "node") return String(record.id);
  if (record.kind === "node-type" || record.kind === "relation-type") return String(record.name);
  if (record.universal === true) return "Anytime";
  if (Array.isArray(record.intervals)) {
    return record.intervals.map((item) => {
      const interval = item as Record<string, unknown>;
      return `${interval.timeline}: ${formatBoundary(interval.start, "-∞")} – ${formatBoundary(interval.end, "+∞")}`;
    }).join(", ");
  }
  return JSON.stringify(value);
}

function formatBoundary(value: unknown, fallback: string): string {
  if (!value || typeof value !== "object") return fallback;
  return String((value as Record<string, unknown>).value);
}

function refreshTemporalFields(): void {
  const mode = byId<HTMLSelectElement>("temporal-mode").value;
  const timeline = byId<HTMLSelectElement>("timeline");
  const unspecified = timeline.options[0];
  if (unspecified) unspecified.disabled = mode !== "anytime";
  if (mode !== "anytime" && !timeline.value && timeline.options.length > 1) {
    timeline.selectedIndex = 1;
  }
  byId("at-fields").classList.toggle("hidden", mode !== "at");
  byId("range-fields").classList.toggle("hidden", mode !== "overlaps");
  updateGeneratedQuery();
}

function updateGeneratedQuery(): void {
  const output = byId<HTMLTextAreaElement>("generated-gmql");
  try {
    output.value = buildFormQuery(collectForm()).query;
  } catch (error) {
    output.value = `// ${error instanceof Error ? error.message : String(error)}`;
  }
}

function setBusy(busy: boolean): void {
  byId("status").textContent = busy ? "検索中…" : "";
  document.querySelectorAll<HTMLButtonElement>("#form-search, #gmql-search").forEach((button) => {
    button.disabled = busy;
  });
}

function renderError(message: string): void {
  setBusy(false);
  const li = document.createElement("li");
  li.textContent = message;
  byId("diagnostics").replaceChildren(li);
}

function option(value: string, label: string): HTMLOptionElement {
  const item = document.createElement("option");
  item.value = value;
  item.textContent = label;
  return item;
}

addCondition();
refreshTemporalFields();
updateGeneratedQuery();
vscodeApi.postMessage({ type: "ready" });
