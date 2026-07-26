export type SearchProperty = {
  name: string;
  type: string;
  required: boolean;
};

export type SearchNodeType = {
  id: string;
  properties: SearchProperty[];
};

export type SearchRelationType = {
  id: string;
  sourceTypes: string[] | null;
  targetTypes: string[] | null;
  properties: SearchProperty[];
};

export type SearchMetadata = {
  nodeTypes: SearchNodeType[];
  relationTypes: SearchRelationType[];
  timelines: string[];
};

export type PropertyCondition = {
  property: string;
  propertyType: string;
  operator: string;
  value: string;
};

export type SearchFormState = {
  nodeType: string;
  keyword: string;
  conditions: PropertyCondition[];
  temporalMode: "anytime" | "at" | "overlaps";
  timeline: string;
  instant: string;
  from: string;
  to: string;
  sort: "relevance" | "id-asc" | "id-desc";
  limit: number;
};

export type LinkSearchFormState = {
  relationType: string;
  sourceType: string;
  sourceId: string;
  targetType: string;
  targetId: string;
  keyword: string;
  conditions: PropertyCondition[];
  temporalMode: "anytime" | "at" | "overlaps";
  timeline: string;
  instant: string;
  from: string;
  to: string;
  sort: "relevance" | "id-asc" | "id-desc";
  limit: number;
};

export type BuiltSearchQuery = {
  query: string;
  parameters: Record<string, string>;
};

const operators = new Set(["=", "!=", "<", "<=", ">", ">=", "CONTAINS", "STARTS WITH", "ENDS WITH"]);

export function supportsPropertyCondition(propertyType: string): boolean {
  return propertyType === "string" || propertyType === "number";
}

export function propertyConditionOperators(propertyType: string): string[] {
  return propertyType === "number"
    ? ["=", "!=", "<", "<=", ">", ">="]
    : ["=", "!=", "CONTAINS", "STARTS WITH", "ENDS WITH"];
}

export function buildFormQuery(state: SearchFormState): BuiltSearchQuery {
  const parameters: Record<string, string> = {};
  const predicates: string[] = [];
  const variable = "node";
  const patternType = state.nodeType ? `:${quoteIdentifier(state.nodeType)}` : "";

  if (state.keyword.trim()) {
    predicates.push(`FULLTEXT(${variable}, $keyword)`);
    parameters.keyword = JSON.stringify(state.keyword);
  }

  state.conditions.forEach((condition, index) => {
    if (
      !condition.property
      || !supportsPropertyCondition(condition.propertyType)
      || !operators.has(condition.operator)
    ) return;
    const parameter = `property${index}`;
    predicates.push(
      `${variable}.${quoteIdentifier(condition.property)} ${condition.operator} $${parameter}`,
    );
    parameters[parameter] = encodeParameter(condition.value, condition.propertyType);
  });

  const temporal = buildTemporal(state, parameters);
  const requestedLimit = Number.isFinite(state.limit) ? Math.trunc(state.limit) : 100;
  const limit = Math.min(1000, Math.max(1, requestedLimit));
  const orderBy = state.sort === "id-desc"
    ? "id DESC"
    : state.sort === "id-asc" || !state.keyword.trim()
      ? "id ASC"
      : "score DESC, id ASC";

  const lines = [
    `MATCH (${variable}${patternType})`,
    predicates.length ? `WHERE ${predicates.join("\n  AND ")}` : "",
    temporal,
    "RETURN ID(node) AS id, TYPE(node) AS type, SCORE() AS score, VALIDITY() AS validity",
    `ORDER BY ${orderBy}`,
    `LIMIT ${limit}`,
  ].filter(Boolean);

  return { query: lines.join("\n"), parameters };
}

export function buildLinkFormQuery(state: LinkSearchFormState): BuiltSearchQuery {
  const parameters: Record<string, string> = {};
  const predicates: string[] = [];
  const sourceType = state.sourceType ? `:${quoteIdentifier(state.sourceType)}` : "";
  const relationType = state.relationType ? `:${quoteIdentifier(state.relationType)}` : "";
  const targetType = state.targetType ? `:${quoteIdentifier(state.targetType)}` : "";

  if (state.sourceId.trim()) {
    predicates.push("ID(source) = $sourceId");
    parameters.sourceId = JSON.stringify(state.sourceId);
  }
  if (state.targetId.trim()) {
    predicates.push("ID(target) = $targetId");
    parameters.targetId = JSON.stringify(state.targetId);
  }
  if (state.keyword.trim()) {
    predicates.push("FULLTEXT(link, $linkKeyword)");
    parameters.linkKeyword = JSON.stringify(state.keyword);
  }
  state.conditions.forEach((condition, index) => {
    if (
      !condition.property
      || !supportsPropertyCondition(condition.propertyType)
      || !operators.has(condition.operator)
    ) return;
    const parameter = `linkProperty${index}`;
    predicates.push(`link.${quoteIdentifier(condition.property)} ${condition.operator} $${parameter}`);
    parameters[parameter] = encodeParameter(condition.value, condition.propertyType);
  });

  const temporal = buildTemporal(state, parameters);
  const requestedLimit = Number.isFinite(state.limit) ? Math.trunc(state.limit) : 100;
  const limit = Math.min(1000, Math.max(1, requestedLimit));
  const orderBy = state.sort === "id-desc"
    ? "id DESC"
    : state.sort === "id-asc" || !state.keyword.trim()
      ? "id ASC"
      : "score DESC, id ASC";
  const lines = [
    `MATCH (source${sourceType})-[link${relationType}]->(target${targetType})`,
    predicates.length ? `WHERE ${predicates.join("\n  AND ")}` : "",
    temporal,
    "RETURN ID(link) AS id, TYPE(link) AS type, ID(source) AS source, ID(target) AS target, SCORE() AS score, VALIDITY() AS validity",
    `ORDER BY ${orderBy}`,
    `LIMIT ${limit}`,
  ].filter(Boolean);
  return { query: lines.join("\n"), parameters };
}

export function quoteIdentifier(value: string): string {
  if (/^[A-Za-z_][A-Za-z0-9_]*$/.test(value)) return value;
  if (!value || value.includes("`")) {
    throw new Error(`GMQL identifier cannot be represented: ${value}`);
  }
  return `\`${value}\``;
}

function buildTemporal(
  state: Pick<SearchFormState, "temporalMode" | "timeline" | "instant" | "from" | "to">,
  parameters: Record<string, string>,
): string {
  if (state.temporalMode === "anytime") {
    return state.timeline
      ? `VALID ON ${quoteIdentifier(state.timeline)} ANYTIME`
      : "VALID ANYTIME";
  }
  if (!state.timeline) throw new Error("時間条件にはタイムラインが必要です。");
  const timeline = quoteIdentifier(state.timeline);
  if (state.temporalMode === "at") {
    if (!state.instant.trim()) throw new Error("時点を入力してください。");
    parameters.instant = state.instant.trim();
    return `VALID ON ${timeline} AT $instant`;
  }
  if (!state.from.trim() || !state.to.trim()) throw new Error("期間の開始と終了を入力してください。");
  parameters.from = state.from.trim();
  parameters.to = state.to.trim();
  return `VALID ON ${timeline} OVERLAPS [$from, $to]`;
}

function encodeParameter(value: string, propertyType: string): string {
  return propertyType === "number"
    ? value.trim()
    : JSON.stringify(value);
}
