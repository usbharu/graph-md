import { useEffect, useMemo, useState } from "react";

type Document = {
  id: string;
  title: string;
  body: string;
  route: string;
  kind: string;
  type: string | null;
};

type QueryResult = {
  columns: { name: string; type: string }[];
  rows: unknown[][];
  diagnostics: { code: string; message: string }[];
};

declare global {
  interface Window {
    GraphMdQueryRuntime: any;
  }
}

export default function SearchApp({
  documents,
  base,
}: {
  documents: Document[];
  base: string;
}) {
  const [term, setTerm] = useState("");
  const [advanced, setAdvanced] = useState(false);
  const [query, setQuery] = useState(
    "MATCH (n) RETURN ID(n) AS id LIMIT 100",
  );
  const [parameters, setParameters] = useState("{}");
  const [engine, setEngine] = useState<any>();
  const [result, setResult] = useState<QueryResult>();
  const [error, setError] = useState("");
  const [searched, setSearched] = useState(false);
  const routes = useMemo(
    () => Object.fromEntries(documents.map((document) => [document.id, document.route])),
    [documents],
  );
  const documentsById = useMemo(
    () => Object.fromEntries(documents.map((document) => [document.id, document])),
    [documents],
  );
  const simpleResults = useMemo(() => {
    if (!searched) return [];
    const ids = result?.rows.map((row) => String(row[0])) ?? [];
    if (ids.length) return ids.map((id) => documentsById[id]).filter(Boolean);
    const value = term.trim().toLocaleLowerCase();
    return documents.filter((document) => `${document.title}\n${document.body}\n${document.id}`.toLocaleLowerCase().includes(value));
  }, [documents, documentsById, result, searched, term]);

  useEffect(() => {
    void (async () => {
      try {
        const manifestText = await fetch(`${base}search-index/manifest.json`).then(
          (response) => {
            if (!response.ok) throw new Error("検索索引を取得できません");
            return response.text();
          },
        );
        const manifest = JSON.parse(manifestText);
        const names = [
          ...new Set(Object.values(manifest.shards).flat()),
        ] as string[];
        const shards: Record<string, string> = {};
        await Promise.all(
          names.map(async (name) => {
            shards[name] = await fetch(`${base}search-index/${name}`).then(
              (response) => response.text(),
            );
          }),
        );
        const api =
          window.GraphMdQueryRuntime?.dev?.usbharu?.graphmd?.query?.GraphMdWebSearch;
        if (!api) throw new Error("GMQLランタイムを読み込めません");
        setEngine(api.load(manifestText, JSON.stringify(shards)));
      } catch (cause) {
        setError(cause instanceof Error ? cause.message : String(cause));
      }
    })();
  }, [base]);

  useEffect(() => {
    const initialTerm = new URLSearchParams(location.search).get("q")?.trim();
    if (initialTerm) setTerm(initialTerm);
  }, []);

  useEffect(() => {
    const initialTerm = new URLSearchParams(location.search).get("q")?.trim();
    if (engine && initialTerm && !searched) {
      setTerm(initialTerm);
      setSearched(true);
      void run(
        "MATCH (n) WHERE FULLTEXT(n, $term) RETURN ID(n) AS id, SCORE() AS score ORDER BY score DESC, id ASC LIMIT 100",
        JSON.stringify({ term: initialTerm }),
      );
    }
  }, [engine]);

  async function run(text = query, params = parameters) {
    if (!engine) {
      setError("検索エンジンを読み込み中です");
      return;
    }
    try {
      setError("");
      setResult(JSON.parse(await engine.queryGmql(text, params)));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  }

  function simpleSearch() {
    const value = term.trim();
    if (!value) return;
    setAdvanced(false);
    setSearched(true);
    history.replaceState(null, "", `${location.pathname}?q=${encodeURIComponent(value)}`);
    void run(
      "MATCH (n) WHERE FULLTEXT(n, $term) RETURN ID(n) AS id, SCORE() AS score ORDER BY score DESC, id ASC LIMIT 100",
      JSON.stringify({ term: value }),
    );
  }

  return (
    <div className="search">
      <div className="search-box" role="search">
        <label className="sr-only" htmlFor="content-search">キーワード</label>
        <input
          id="content-search"
          value={term}
          onChange={(event) => setTerm(event.target.value)}
          onKeyDown={(event) => event.key === "Enter" && simpleSearch()}
          placeholder="記事名やキーワードを入力"
          autoFocus
        />
        <button className="primary-button" onClick={simpleSearch}>検索</button>
      </div>
      <div className="search-meta">
        <span>{searched ? `${simpleResults.length}件の記事` : engine ? "検索できます" : "検索索引を準備中…"}</span>
        <button className="secondary-button" onClick={() => setAdvanced(!advanced)}>
          {advanced ? "詳細検索を閉じる" : "GMQL詳細検索"}
        </button>
      </div>
      {advanced && (
        <section className="gmql">
          <label>
            GMQL
            <textarea value={query} onChange={(event) => setQuery(event.target.value)} />
          </label>
          <label>
            JSONパラメータ
            <textarea
              value={parameters}
              onChange={(event) => setParameters(event.target.value)}
            />
          </label>
          <button onClick={() => void run()}>実行</button>
        </section>
      )}
      {error && <p className="error">{error}</p>}
      {result?.diagnostics.map((diagnostic) => (
        <p className="error" key={diagnostic.code}>
          {diagnostic.code}: {diagnostic.message}
        </p>
      ))}
      {!advanced && searched && simpleResults.length > 0 && (
        <ol className="search-results">
          {simpleResults.map((document) => (
            <li className="search-result" key={document.id}>
              <span className="result-type">{document.kind}{document.type ? ` · ${document.type}` : ""}</span>
              <h2><a href={document.route}>{document.title}</a></h2>
              <p>{document.body.replace(/[#*`\[\]]/g, "").split("\n").filter(Boolean).slice(1, 3).join(" ").slice(0, 180) || document.id}</p>
            </li>
          ))}
        </ol>
      )}
      {!advanced && searched && simpleResults.length === 0 && !error && (
        <div className="empty-state"><strong>一致する記事はありません</strong><span>別のキーワードをお試しください。</span></div>
      )}
      {advanced && result && result.rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                {result.columns.map((column) => <th key={column.name}>{column.name}</th>)}
              </tr>
            </thead>
            <tbody>
              {result.rows.map((row, rowIndex) => (
                <tr key={rowIndex}>
                  {row.map((value, columnIndex) => {
                    const text = String(value ?? "");
                    const route = routes[text];
                    return (
                      <td key={columnIndex}>
                        {route ? <a href={route}>{text}</a> : text}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
