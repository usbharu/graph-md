package dev.usbharu.graphmd.cli

import dev.usbharu.graphmd.core.GraphCompiler
import dev.usbharu.graphmd.core.model.*
import dev.usbharu.graphmd.query.GraphSearchEngine

internal fun GraphMdCli.site(command: CliCommand.Site, json: Boolean): CliResult {
    val sources = WorkspaceLoader(fileSystem).load(command.paths)
    val compiler = GraphCompiler()
    val parsed = sources.map { compiler.parseDocument(it.text, it.sourcePath) }
    val compilation = compiler.compileParsed(parsed)
    val errors = compilation.diagnostics.filter { it.severity == Severity.Error }
    if (errors.isNotEmpty()) {
        val stderr = if (json) jsonArray(errors.map(Diagnostic::toJson)).encode() + "\n"
        else errors.joinToString("") { "${it.source?.path ?: "<workspace>"}: ${it.message}\n" }
        return CliResult(stderr = stderr, exitCode = 1)
    }

    val output = if (fileSystem.kind(command.outputDirectory) == null) {
        val normalized = command.outputDirectory.replace('\\', '/').trimEnd('/')
        val parent = normalized.substringBeforeLast('/', missingDelimiterValue = ".").ifEmpty { "/" }
        val name = normalized.substringAfterLast('/')
        if (name.isEmpty() || fileSystem.kind(parent) != FileKind.Directory) {
            return CliResult(stderr = "Output parent directory does not exist: $parent\n", exitCode = 2)
        }
        fileSystem.child(fileSystem.canonical(parent), name)
    } else {
        fileSystem.canonical(command.outputDirectory)
    }
    val current = fileSystem.canonical(".")
    if (output == "/" || output == current || sources.any { fileSystem.canonical(it.sourcePath) == output }) {
        return CliResult(stderr = "Refusing unsafe site output directory: ${command.outputDirectory}\n", exitCode = 2)
    }
    when (fileSystem.kind(output)) {
        FileKind.File, FileKind.Other -> return CliResult(stderr = "Output path is not a directory: $output\n", exitCode = 1)
        FileKind.Directory -> if (fileSystem.children(output).isNotEmpty() && !command.force) {
            return CliResult(stderr = "Output directory must be empty (use --force to replace it): $output\n", exitCode = 1)
        }
        null -> Unit
    }

    val documents = parsed.mapNotNull { it.document }.sortedBy { it.id }
    val generator = AstroSiteGenerator(command.base, documents, compilation)
    val files = generator.files().toMutableMap()
    val search = GraphSearchEngine.build(compilation, sources).exportStatic()
    search.files().forEach { (name, content) -> files["public/search-index/$name"] = content }

    try {
        if (fileSystem.kind(output) == FileKind.Directory && command.force) clearOutput(fileSystem, output)
        fileSystem.createDirectories(output)
        files.forEach { (relative, content) ->
            val target = relative.split('/').fold(output) { parent, child -> fileSystem.child(parent, child) }
            val parent = target.substringBeforeLast('/', output)
            fileSystem.createDirectories(parent)
            fileSystem.writeText(target, content)
        }
    } catch (exception: Throwable) {
        return CliResult(stderr = "Cannot generate site in $output: ${exception.message ?: "I/O error"}\n", exitCode = 1)
    }

    val warnings = compilation.diagnostics.filter { it.severity == Severity.Warning }
    val summary = jsonObject(
        "outputDirectory" to jsonString(output),
        "documents" to jsonNumber(documents.size),
        "routes" to jsonNumber(documents.size + 3),
        "searchIndexFiles" to jsonNumber(search.files().size),
        "diagnostics" to jsonArray(warnings.map(Diagnostic::toJson)),
    )
    return if (json) CliResult(stdout = summary.encode() + "\n") else CliResult(
        stdout = "Generated Astro site project in $output (${documents.size} documents)\n",
        stderr = warnings.joinToString("") { "${it.source?.path ?: "<workspace>"}: warning: ${it.message}\n" },
    )
}

private fun clearOutput(fileSystem: CliFileSystem, output: String) {
    fileSystem.children(output).forEach { child ->
        val name = child.replace('\\', '/').substringAfterLast('/')
        // Dependency installs can contain symlink forests that kotlinx-io cannot
        // portably unlink. Keep node_modules, but discard the lockfile so a
        // regenerated package.json cannot remain pinned to an incompatible set.
        if (name != "node_modules") deleteTree(fileSystem, child)
    }
}

private fun deleteTree(fileSystem: CliFileSystem, path: String) {
    if (fileSystem.kind(path) == FileKind.Directory) fileSystem.children(path).forEach { child ->
        val canonical = runCatching { fileSystem.canonical(child) }.getOrNull()
        if (canonical != null && canonical.replace('\\', '/') != child.replace('\\', '/')) {
            fileSystem.delete(child, mustExist = false)
        } else {
            deleteTree(fileSystem, child)
        }
    }
    try {
        fileSystem.delete(path, mustExist = false)
    } catch (exception: Throwable) {
        throw CliIoException("Cannot delete $path: ${exception.message ?: "deletion failed"}")
    }
}

private class AstroSiteGenerator(
    private val base: String,
    private val documents: List<GraphDocument>,
    private val graph: GraphCompilationResult,
) {
    private val routes = documents.associate { it.id to "${base}documents/${safeSlug(it.id)}/" }

    fun files(): Map<String, String> = linkedMapOf(
        "package.json" to PACKAGE_JSON,
        "pnpm-workspace.yaml" to PNPM_WORKSPACE,
        "astro.config.mjs" to ASTRO_CONFIG.replace("@@BASE@@", base),
        "tsconfig.json" to TS_CONFIG,
        "src/generated/site.json" to siteJson(),
        "src/layouts/WikiLayout.astro" to LAYOUT,
        "src/lib/markdown.ts" to MARKDOWN,
        "src/pages/index.astro" to INDEX_PAGE,
        "src/pages/documents/[slug].astro" to DOCUMENT_PAGE,
        "src/pages/search.astro" to SEARCH_PAGE,
        "src/pages/graph.astro" to GRAPH_PAGE,
        "src/components/SearchApp.tsx" to SEARCH_APP,
        "src/components/GraphApp.tsx" to GRAPH_APP,
        "src/styles/global.css" to CSS,
    ).apply {
        embeddedWebRuntimeFiles.forEach { (name, content) ->
            put("runtime-encoded/$name.gz.b64", content)
        }
    }

    private fun siteJson(): String {
        val incoming = graph.relations.groupBy { it.to }
        val docs = documents.map { document ->
            val node = graph.nodes.firstOrNull { it.id == document.id }
            jsonObject(
                "id" to jsonString(document.id),
                "slug" to jsonString(safeSlug(document.id)),
                "route" to jsonString(routes.getValue(document.id)),
                "title" to jsonString(firstHeading(document.body) ?: document.id),
                "kind" to jsonString(document.kind.name),
                "type" to jsonNullableString(node?.type),
                "url" to jsonNullableString(node?.url),
                "body" to jsonString(document.body),
                "backlinks" to jsonArray(incoming[document.id].orEmpty().map { relation ->
                    jsonObject(
                        "id" to jsonString(relation.from),
                        "type" to jsonString(relation.type),
                        "route" to jsonNullableString(routes[relation.from]),
                    )
                }),
            )
        }
        val nodes = graph.nodes.map { node ->
            jsonObject("data" to jsonObject(
                "id" to jsonString(node.id),
                "label" to jsonString(firstHeading(documents.firstOrNull { it.id == node.id }?.body.orEmpty()) ?: node.id),
                "route" to jsonNullableString(routes[node.id]),
                "kind" to jsonString(node.kind.name),
            ))
        }
        val edges = graph.relations.mapIndexed { index, relation ->
            jsonObject("data" to jsonObject(
                "id" to jsonString("e$index"), "source" to jsonString(relation.from),
                "target" to jsonString(relation.to), "label" to jsonString(relation.type),
            ))
        }
        return jsonObject(
            "base" to jsonString(base),
            "documents" to jsonArray(docs),
            "routes" to JsonValue.Object(routes.mapValues { jsonString(it.value) }),
            "graph" to jsonObject("nodes" to jsonArray(nodes), "edges" to jsonArray(edges)),
        ).encode() + "\n"
    }
}

internal fun safeSlug(id: String): String = buildString {
    id.encodeToByteArray().forEach { byte ->
        val value = byte.toInt() and 0xff
        val character = value.toChar()
        if (character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' || character in setOf('_', '-', '.')) append(character)
        else append('~').append(value.toString(16).uppercase().padStart(2, '0'))
    }
}

private fun firstHeading(body: String): String? = body.lineSequence().map(String::trim)
    .firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()?.trimEnd('#')?.trim()?.takeIf(String::isNotEmpty)

private const val PACKAGE_JSON = """{"name":"graphmd-wiki","private":true,"type":"module","packageManager":"pnpm@11.21.0","scripts":{"dev":"astro dev","check":"astro check","build":"astro build","preview":"astro preview"},"dependencies":{"@astrojs/react":"5.0.7","astro":"6.4.8","cytoscape":"^3.33.1","markdown-it":"^14.1.0","react":"^19.2.0","react-dom":"^19.2.0"},"devDependencies":{"@types/markdown-it":"^14.1.2","@types/react":"^19.2.0","@types/react-dom":"^19.2.0","typescript":"^5.9.0"}}"""
private const val PNPM_WORKSPACE = """packages:
  - .
allowBuilds:
  esbuild: true
  sharp: true
"""
private const val ASTRO_CONFIG = """import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { defineConfig } from 'astro/config';
import react from '@astrojs/react';
function materialize(input, output) {
  const outputUrl = new URL(output, import.meta.url);
  mkdirSync(new URL('.', outputUrl), { recursive: true });
  writeFileSync(outputUrl, gunzipSync(Buffer.from(readFileSync(new URL(input, import.meta.url), 'utf8'), 'base64')));
}
materialize('./runtime-encoded/markdown-it-graphmd.js.gz.b64', './src/vendor/markdown-it-graphmd.js');
materialize('./runtime-encoded/graph-md-query-runtime.js.gz.b64', './public/runtime/graph-md-query-runtime.js');
export default defineConfig({ output: 'static', base: '@@BASE@@', build: { format: 'directory' }, integrations: [react()] });
"""
private const val TS_CONFIG = """{"extends":"astro/tsconfigs/strict","compilerOptions":{"jsx":"react-jsx","jsxImportSource":"react","resolveJsonModule":true}}"""

private const val LAYOUT = """---
import site from '../generated/site.json';
import '../styles/global.css';
const { title } = Astro.props;
---
<!doctype html><html lang="ja"><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width"/><title>{title} · GraphMD</title></head>
<body><header><a class="brand" href={site.base}>GraphMD Wiki</a><nav><a href={site.base + 'search/'}>検索</a><a href={site.base + 'graph/'}>グラフ</a></nav></header>
<div class="shell"><aside><h2>Documents</h2>{site.documents.map((doc) => <a href={doc.route}>{doc.title}<small>{doc.kind} · {doc.id}</small></a>)}</aside><main><slot /></main></div></body></html>
"""

private const val MARKDOWN = """import MarkdownIt from 'markdown-it';
// @ts-ignore bundled GraphMD plugin is generated from the repository package.
import { graphMdPlugin } from '../vendor/markdown-it-graphmd.js';
import site from '../generated/site.json';
export function renderMarkdown(source: string): string {
  const md = new MarkdownIt({ html: false, linkify: true });
  graphMdPlugin(md, { hrefTransform: (target: string) => (site.routes as Record<string,string>)[target] ?? null });
  return md.render(source);
}
"""

private const val INDEX_PAGE = """---
import WikiLayout from '../layouts/WikiLayout.astro'; import site from '../generated/site.json';
const groups = site.documents.reduce((all, doc) => { (all[doc.kind] ??= []).push(doc); return all; }, {} as Record<string, typeof site.documents>);
---
<WikiLayout title="Home"><h1>Documents</h1><p class="lead">{site.documents.length}件のGraphMD文書</p>{Object.entries(groups).map(([kind, docs]) => <section><h2>{kind}</h2><div class="cards">{docs?.map(doc => <a class="card" href={doc.route}><strong>{doc.title}</strong><span>{doc.id}{doc.type ? ' · ' + doc.type : ''}</span></a>)}</div></section>)}</WikiLayout>
"""
private const val DOCUMENT_PAGE = """---
import WikiLayout from '../../layouts/WikiLayout.astro'; import site from '../../generated/site.json'; import { renderMarkdown } from '../../lib/markdown';
export function getStaticPaths() { return site.documents.map(doc => ({ params: { slug: doc.slug }, props: { doc } })); }
const { doc } = Astro.props; const html = renderMarkdown(doc.body);
---
<WikiLayout title={doc.title}><article><div class="eyebrow">{doc.kind}{doc.type && ' · ' + doc.type}</div><h1>{doc.title}</h1>{doc.url && <p><a href={doc.url}>メディアを開く ↗</a></p>}<div class="markdown" set:html={html}/><footer><h2>Backlinks</h2>{doc.backlinks.length ? <ul>{doc.backlinks.map(link => <li><a href={link.route}>{link.id}</a> <small>{link.type}</small></li>)}</ul> : <p>リンクはありません。</p>}</footer></article></WikiLayout>
"""
private const val SEARCH_PAGE = """---
import WikiLayout from '../layouts/WikiLayout.astro'; import SearchApp from '../components/SearchApp'; import site from '../generated/site.json';
---
<WikiLayout title="検索"><h1>検索</h1>
<script is:inline src={site.base + 'runtime/graph-md-query-runtime.js'}></script>
<SearchApp client:load documents={site.documents} base={site.base}/></WikiLayout>
"""
private const val GRAPH_PAGE = """---
import WikiLayout from '../layouts/WikiLayout.astro'; import GraphApp from '../components/GraphApp'; import site from '../generated/site.json';
---
<WikiLayout title="グラフ"><h1>グラフ</h1><GraphApp client:only="react" elements={[...site.graph.nodes, ...site.graph.edges]}/></WikiLayout>
"""

private const val SEARCH_APP = """import { useEffect, useMemo, useState } from 'react';
type Doc={id:string;title:string;body:string;route:string;kind:string;type:string|null};
type Result={columns:{name:string;type:string}[];rows:unknown[][];diagnostics:{code:string;message:string}[]};
declare global { interface Window { GraphMdQueryRuntime: any } }
export default function SearchApp({documents,base}:{documents:Doc[];base:string}) { const [term,setTerm]=useState(''); const [advanced,setAdvanced]=useState(false); const [query,setQuery]=useState('MATCH (n) RETURN ID(n) AS id LIMIT 100'); const [parameters,setParameters]=useState('{}'); const [engine,setEngine]=useState<any>(); const [result,setResult]=useState<Result>(); const [error,setError]=useState('');
 const routes=useMemo(()=>Object.fromEntries(documents.map(d=>[d.id,d.route])),[documents]);
 useEffect(()=>{(async()=>{try{const manifestText=await fetch(base+'search-index/manifest.json').then(r=>{if(!r.ok)throw new Error('検索索引を取得できません');return r.text()});const manifest=JSON.parse(manifestText);const names=[...new Set(Object.values(manifest.shards).flat())] as string[];const shards:any={};await Promise.all(names.map(async name=>{shards[name]=await fetch(base+'search-index/'+name).then(r=>r.text())}));const api=window.GraphMdQueryRuntime?.dev?.usbharu?.graphmd?.query?.GraphMdWebSearch;if(!api)throw new Error('GMQLランタイムを読み込めません');setEngine(api.load(manifestText,JSON.stringify(shards)))}catch(e){setError(e instanceof Error?e.message:String(e))}})()},[base]);
 async function run(text=query,params=parameters){if(!engine){setError('検索エンジンを読み込み中です');return}try{setError('');setResult(JSON.parse(await engine.queryGmql(text,params)))}catch(e){setError(e instanceof Error?e.message:String(e))}}
 function simple(){const value=term.trim();if(!value)return;setAdvanced(false);void run('MATCH (n) WHERE FULLTEXT(n, ${'$'}term) RETURN ID(n) AS id, SCORE() AS score ORDER BY score DESC, id ASC LIMIT 100',JSON.stringify({term:value}))}
 return <div className="search"><label>キーワード<input value={term} onChange={e=>setTerm(e.target.value)} onKeyDown={e=>e.key==='Enter'&&simple()} placeholder="本文を検索"/></label><button onClick={simple}>検索</button> <button onClick={()=>setAdvanced(!advanced)}>{advanced?'GMQLを閉じる':'GMQL'}</button>{advanced&&<section className="gmql"><label>GMQL<textarea value={query} onChange={e=>setQuery(e.target.value)}/></label><label>JSONパラメータ<textarea value={parameters} onChange={e=>setParameters(e.target.value)}/></label><button onClick={()=>void run()}>実行</button></section>}{error&&<p className="error">{error}</p>}{result?.diagnostics.map(d=><p className="error" key={d.code}>{d.code}: {d.message}</p>)}{result&&result.rows.length>0&&<div className="table-wrap"><table><thead><tr>{result.columns.map(c=><th key={c.name}>{c.name}</th>)}</tr></thead><tbody>{result.rows.map((row,i)=><tr key={i}>{row.map((value,j)=>{const text=String(value??'');const route=routes[text];return <td key={j}>{route?<a href={route}>{text}</a>:text}</td>})}</tr>)}</tbody></table></div>}</div> }
"""
private const val GRAPH_APP = """import { useEffect, useRef } from 'react'; import cytoscape from 'cytoscape';
export default function GraphApp({elements}:{elements:any[]}) { const ref=useRef<HTMLDivElement>(null); useEffect(()=>{if(!ref.current)return;const cy=cytoscape({container:ref.current,elements,style:[{selector:'node',style:{label:'data(label)','background-color':'#4f46e5',color:'#111827','text-valign':'bottom','text-margin-y':8}},{selector:'edge',style:{label:'data(label)',width:2,'line-color':'#a5b4fc','target-arrow-color':'#a5b4fc','target-arrow-shape':'triangle','curve-style':'bezier','font-size':10}}],layout:{name:'cose',animate:false}});cy.on('tap','node',e=>{const route=e.target.data('route');if(route)location.href=route});return()=>cy.destroy()},[elements]);return <div className="graph" ref={ref}/>}
"""
private const val CSS = """:root{font-family:Inter,ui-sans-serif,system-ui;color:#172033;background:#f6f7fb;line-height:1.6}*{box-sizing:border-box}body{margin:0}a{color:#4338ca;text-decoration:none}header{height:64px;padding:0 28px;background:#111827;color:white;display:flex;align-items:center;justify-content:space-between;position:sticky;top:0;z-index:5}.brand{color:white;font-weight:800;font-size:1.1rem}nav{display:flex;gap:20px}nav a{color:#dbeafe}.shell{display:grid;grid-template-columns:260px minmax(0,1fr);max-width:1500px;margin:auto}aside{padding:28px 18px;border-right:1px solid #e5e7eb;min-height:calc(100vh - 64px)}aside h2{font-size:.75rem;text-transform:uppercase;letter-spacing:.12em;color:#6b7280}aside>a{display:block;padding:9px 10px;border-radius:8px;color:#1f2937}aside>a:hover{background:#eef2ff}aside small,.card span,.result span{display:block;color:#6b7280;font-size:.75rem}main{padding:48px clamp(24px,6vw,92px);max-width:1040px;width:100%}.lead,.eyebrow{color:#6b7280}.eyebrow{text-transform:uppercase;letter-spacing:.1em;font-size:.78rem}.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(210px,1fr));gap:12px}.card,.result{background:white;border:1px solid #e5e7eb;border-radius:10px;padding:16px;display:block}.markdown{font-size:1.05rem}.markdown pre{overflow:auto;background:#111827;color:#e5e7eb;padding:18px;border-radius:10px}.markdown img{max-width:100%}.broken-link{color:#dc2626;text-decoration:underline dotted}article footer{margin-top:64px;border-top:1px solid #e5e7eb}.search label{display:block;font-weight:700}.search input,.search textarea{display:block;width:100%;padding:12px;margin:6px 0 14px;border:1px solid #cbd5e1;border-radius:8px;background:white}.search textarea{min-height:120px;font-family:ui-monospace,monospace}.search button{padding:9px 14px}.graph{height:72vh;background:white;border:1px solid #e5e7eb;border-radius:12px}.error{color:#b91c1c;background:#fef2f2;padding:10px}.table-wrap{overflow:auto;margin-top:24px}table{border-collapse:collapse;width:100%;background:white}th,td{border:1px solid #dbe2ea;padding:8px;text-align:left}@media(max-width:760px){.shell{display:block}aside{min-height:auto;border-right:0;border-bottom:1px solid #e5e7eb;max-height:220px;overflow:auto}main{padding:28px 20px}header{padding:0 18px}}
"""
