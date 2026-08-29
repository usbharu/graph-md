import type { ReactNode } from "react";

type ValidTime = {
  timeline?: string;
  from?: { coordinate?: unknown; value?: string | null } | null;
  to?: { coordinate?: unknown; value?: string | null } | null;
};

type Props = {
  value: unknown;
  validTime?: ValidTime[];
  fallback?: boolean;
  showFallback?: boolean;
};

function isRecord(value: unknown): value is Record<string, any> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isRational(value: unknown): value is { numerator: number; denominator: number } {
  return isRecord(value) && typeof value.numerator === "number" && typeof value.denominator === "number" && Object.keys(value).length === 2;
}

function rational(value: { numerator: number; denominator: number }) {
  return value.denominator === 1 ? String(value.numerator) : `${value.numerator}⁄${value.denominator}`;
}

function Point({ point, edge }: { point: ValidTime["from"]; edge: "start" | "end" }) {
  if (!point) return <span className="time-open">{edge === "start" ? "始点なし" : "継続中"}</span>;
  if (point.value) return <span>{point.value}</span>;
  return <ValueNode value={point.coordinate} compact />;
}

function Validity({ entries }: { entries: ValidTime[] }) {
  if (!entries.length) return null;
  return <div className="validity-list" aria-label="有効期間">
    {entries.map((entry, index) => <div className="validity" key={index}>
      <span className="validity-timeline">{entry.timeline ?? "Timeline"}</span>
      <span className="validity-range"><Point point={entry.from} edge="start" /><span aria-hidden="true">—</span><Point point={entry.to} edge="end" /></span>
    </div>)}
  </div>;
}

function ValueNode({ value, compact = false }: { value: unknown; compact?: boolean }): ReactNode {
  if (value === null || value === undefined) return <span className="human-empty">—</span>;
  if (typeof value === "boolean") return <span className={`boolean-value ${value ? "true" : "false"}`}>{value ? "はい" : "いいえ"}</span>;
  if (typeof value === "number") return <span className="number-value">{new Intl.NumberFormat("ja-JP", { maximumFractionDigits: 12 }).format(value)}</span>;
  if (typeof value === "string") {
    if (/^https?:\/\//i.test(value)) return <a className="url-value" href={value}>{value}</a>;
    return <span className={compact ? "compact-value" : "string-value"}>{value}</span>;
  }
  if (isRational(value)) return <span className="number-value">{rational(value)}</span>;
  if (Array.isArray(value)) {
    if (!value.length) return <span className="human-empty">なし</span>;
    return <ul className="human-list">{value.map((item, index) => <li key={index}><ValueNode value={item} /></li>)}</ul>;
  }
  if (!isRecord(value)) return <span>{String(value)}</span>;

  if ("value" in value && "validTime" in value && "fallback" in value) {
    return <div className="nested-entry"><ValueNode value={value.value} />{!value.fallback && <Validity entries={Array.isArray(value.validTime) ? value.validTime : []} />}{value.fallback && <span className="fallback-badge">その他の期間</span>}</div>;
  }
  if (value.kind === "id" && typeof value.id === "string") return <span className="reference-value">{value.id}</span>;
  if (value.kind === "mapped" && typeof value.to === "string") return <span className="reference-value">mapped → {value.to}</span>;
  if ("coordinate" in value && ("timeline" in value || "value" in value)) {
    return <div className="instant-value"><strong><ValueNode value={value.value ?? value.coordinate} /></strong>{value.timeline && <span>@ {value.timeline}</span>}{value.value != null && <small><ValueNode value={value.coordinate} compact /></small>}</div>;
  }
  if ("from" in value && "to" in value && "timeline" in value) {
    return <div className="duration-value">{value.timeline && <span>{value.timeline}</span>}<Point point={value.from} edge="start" /><span aria-hidden="true">→</span><Point point={value.to} edge="end" /></div>;
  }

  const entries = Object.entries(value);
  if (!entries.length) return <span className="human-empty">なし</span>;
  return <dl className="human-object">{entries.map(([key, child]) => <div key={key}><dt>{key}</dt><dd><ValueNode value={child} /></dd></div>)}</dl>;
}

export default function HumanValue({ value, validTime = [], fallback = false, showFallback = fallback }: Props) {
  return <div className="human-value"><ValueNode value={value} />{!fallback && <Validity entries={validTime} />}{showFallback && <span className="fallback-badge">その他の期間</span>}</div>;
}
