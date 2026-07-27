import {
  buildFormQuery,
  buildLinkFormQuery,
  propertyConditionOperators,
  supportsPropertyCondition,
  type LinkSearchFormState,
  type PropertyCondition,
  type SearchFormState,
  type SearchMetadata,
} from "./search-query";

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
let metadata: SearchMetadata = { nodeTypes: [], relationTypes: [], timelines: [] };
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
    ["node", "link", "gmql"].forEach((tab) => {
      byId(`${tab}-panel`).classList.toggle("hidden", button.dataset.tab !== tab);
    });
  });
});

byId<HTMLSelectElement>("node-type").addEventListener("change", () => refreshConditionProperties("node"));
byId<HTMLSelectElement>("link-type").addEventListener("change", refreshLinkTypeFields);
byId<HTMLSelectElement>("node-temporal-mode").addEventListener("change", () => refreshTemporalFields("node"));
byId<HTMLSelectElement>("link-temporal-mode").addEventListener("change", () => refreshTemporalFields("link"));
byId("node-panel").addEventListener("input", () => updateGeneratedQuery("node"));
byId("node-panel").addEventListener("change", () => updateGeneratedQuery("node"));
byId("link-panel").addEventListener("input", () => updateGeneratedQuery("link"));
byId("link-panel").addEventListener("change", () => updateGeneratedQuery("link"));
byId("node-add-condition").addEventListener("click", () => addCondition("node"));
byId("link-add-condition").addEventListener("click", () => addCondition("link"));
byId("add-parameter").addEventListener("click", () => addParameter());
byId("node-search").addEventListener("click", runNodeSearch);
byId("link-search").addEventListener("click", runLinkSearch);
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
  const linkType = byId<HTMLSelectElement>("link-type");
  linkType.replaceChildren(option("", "すべて"), ...metadata.relationTypes.map((item) => option(item.id, item.id)));
  for (const kind of ["node", "link"] as const) {
    const timeline = byId<HTMLSelectElement>(`${kind}-timeline`);
    timeline.replaceChildren(option("", "指定なし"), ...metadata.timelines.map((item) => option(item, item)));
  }
  refreshConditionProperties("node");
  refreshLinkTypeFields();
  refreshTemporalFields("node");
  refreshTemporalFields("link");
  updateGeneratedQuery("node");
  updateGeneratedQuery("link");
}

type FormKind = "node" | "link";

function addCondition(kind: FormKind, initial?: Partial<PropertyCondition>): void {
  const row = document.createElement("div");
  row.className = "condition";
  row.dataset.kind = kind;
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
    updateGeneratedQuery(kind);
  });
  property.addEventListener("change", () => refreshOperator(row));
  row.append(property, operator, value, remove);
  byId(`${kind}-conditions`).append(row);
  fillPropertySelect(property, kind, initial?.property);
  refreshOperator(row, initial?.operator);
  value.value = initial?.value ?? "";
  updateGeneratedQuery(kind);
}

function refreshConditionProperties(kind: FormKind): void {
  byId(`${kind}-conditions`).querySelectorAll<HTMLSelectElement>(".condition-property").forEach((select) => {
    fillPropertySelect(select, kind, select.value);
    refreshOperator(select.closest(".condition") as HTMLElement);
  });
  updateGeneratedQuery(kind);
}

function fillPropertySelect(select: HTMLSelectElement, kind: FormKind, selected?: string): void {
  const typeId = byId<HTMLSelectElement>(kind === "node" ? "node-type" : "link-type").value;
  const properties = (kind === "node"
    ? metadata.nodeTypes.find((type) => type.id === typeId)
    : metadata.relationTypes.find((type) => type.id === typeId))?.properties
    .filter((property) => supportsPropertyCondition(property.type)) ?? [];
  select.replaceChildren(option("", "プロパティ"), ...properties.map((property) => {
    const item = option(property.name, property.name);
    item.dataset.propertyType = property.type;
    return item;
  }));
  if (selected && properties.some((property) => property.name === selected)) select.value = selected;
}

function refreshLinkTypeFields(): void {
  const relation = metadata.relationTypes.find((type) => type.id === byId<HTMLSelectElement>("link-type").value);
  populateEndpointTypes("link-source-type", relation?.sourceTypes ?? null);
  populateEndpointTypes("link-target-type", relation?.targetTypes ?? null);
  refreshConditionProperties("link");
}

function populateEndpointTypes(id: string, allowedTypes: string[] | null): void {
  const select = byId<HTMLSelectElement>(id);
  const selected = select.value;
  const types = metadata.nodeTypes.filter((type) => allowedTypes == null || allowedTypes.includes(type.id));
  select.replaceChildren(option("", "すべて"), ...types.map((type) => option(type.id, type.id)));
  if (types.some((type) => type.id === selected)) select.value = selected;
}

function refreshOperator(row: HTMLElement, selected?: string): void {
  const property = row.querySelector<HTMLSelectElement>(".condition-property");
  const operator = row.querySelector<HTMLSelectElement>(".condition-operator");
  if (!property || !operator) return;
  const type = property.selectedOptions[0]?.dataset.propertyType ?? "string";
  const names = propertyConditionOperators(type);
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

function runNodeSearch(): void {
  try {
    const state = collectNodeForm();
    const built = buildFormQuery(state);
    execute(built.query, built.parameters);
  } catch (error) {
    renderError(error instanceof Error ? error.message : String(error));
  }
}

function runLinkSearch(): void {
  try {
    const built = buildLinkFormQuery(collectLinkForm());
    execute(built.query, built.parameters);
  } catch (error) {
    renderError(error instanceof Error ? error.message : String(error));
  }
}

function collectConditions(kind: FormKind): PropertyCondition[] {
  return Array.from(byId(`${kind}-conditions`).querySelectorAll<HTMLElement>(".condition")).map((row) => {
    const property = row.querySelector<HTMLSelectElement>(".condition-property")!;
    return {
      property: property.value,
      propertyType: property.selectedOptions[0]?.dataset.propertyType ?? "string",
      operator: row.querySelector<HTMLSelectElement>(".condition-operator")!.value,
      value: row.querySelector<HTMLInputElement>(".condition-value")!.value,
    };
  });
}

function collectNodeForm(): SearchFormState {
  return {
    nodeType: byId<HTMLSelectElement>("node-type").value,
    keyword: byId<HTMLInputElement>("node-keyword").value,
    conditions: collectConditions("node"),
    temporalMode: byId<HTMLSelectElement>("node-temporal-mode").value as SearchFormState["temporalMode"],
    timeline: byId<HTMLSelectElement>("node-timeline").value,
    instant: byId<HTMLInputElement>("node-instant").value,
    from: byId<HTMLInputElement>("node-from").value,
    to: byId<HTMLInputElement>("node-to").value,
    sort: byId<HTMLSelectElement>("node-sort").value as SearchFormState["sort"],
    limit: byId<HTMLInputElement>("node-limit").valueAsNumber,
  };
}

function collectLinkForm(): LinkSearchFormState {
  return {
    relationType: byId<HTMLSelectElement>("link-type").value,
    sourceType: byId<HTMLSelectElement>("link-source-type").value,
    sourceId: byId<HTMLInputElement>("link-source-id").value,
    targetType: byId<HTMLSelectElement>("link-target-type").value,
    targetId: byId<HTMLInputElement>("link-target-id").value,
    keyword: byId<HTMLInputElement>("link-keyword").value,
    conditions: collectConditions("link"),
    temporalMode: byId<HTMLSelectElement>("link-temporal-mode").value as LinkSearchFormState["temporalMode"],
    timeline: byId<HTMLSelectElement>("link-timeline").value,
    instant: byId<HTMLInputElement>("link-instant").value,
    from: byId<HTMLInputElement>("link-from").value,
    to: byId<HTMLInputElement>("link-to").value,
    sort: byId<HTMLSelectElement>("link-sort").value as LinkSearchFormState["sort"],
    limit: byId<HTMLInputElement>("link-limit").valueAsNumber,
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

function refreshTemporalFields(kind: FormKind): void {
  const mode = byId<HTMLSelectElement>(`${kind}-temporal-mode`).value;
  const timeline = byId<HTMLSelectElement>(`${kind}-timeline`);
  const unspecified = timeline.options[0];
  if (unspecified) unspecified.disabled = mode !== "anytime";
  if (mode !== "anytime" && !timeline.value && timeline.options.length > 1) {
    timeline.selectedIndex = 1;
  }
  byId(`${kind}-at-fields`).classList.toggle("hidden", mode !== "at");
  byId(`${kind}-range-fields`).classList.toggle("hidden", mode !== "overlaps");
  updateGeneratedQuery(kind);
}

function updateGeneratedQuery(kind: FormKind): void {
  const output = byId<HTMLTextAreaElement>(`${kind}-generated-gmql`);
  try {
    output.value = kind === "node"
      ? buildFormQuery(collectNodeForm()).query
      : buildLinkFormQuery(collectLinkForm()).query;
  } catch (error) {
    output.value = `// ${error instanceof Error ? error.message : String(error)}`;
  }
}

function setBusy(busy: boolean): void {
  byId("status").textContent = busy ? "検索中…" : "";
  document.querySelectorAll<HTMLButtonElement>("#node-search, #link-search, #gmql-search").forEach((button) => {
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

addCondition("node");
addCondition("link");
refreshTemporalFields("node");
refreshTemporalFields("link");
updateGeneratedQuery("node");
updateGeneratedQuery("link");
vscodeApi.postMessage({ type: "ready" });
