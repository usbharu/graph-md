# GraphMD LSP

`core` を使って GraphMD 文書を解析する Kotlin/JVM の LSP サーバです。VSCode 用の薄いクライアントは `lsp/vscode` にあります。

## 対応内容

- `types/**/*.md` の `NodeType` / `RelType` を優先した補完
- ワークスペース内どこにあっても `kind: Node` 文書を索引
- `type`, `extends`, `from`, `to` の補完・定義ジャンプ
- `@[label](target relType)` の `target` / `relType` 補完・定義ジャンプ
- `@props{...}` と `@[label](target relType){...}` のシンタックスハイライト
- `core` の `GraphCompiler` による未定義参照や制約違反の診断
- Hover と references

## セットアップ

```bash
cd lsp/vscode
npm install
npm run build      # Kotlin LSP と core/JS をビルドして server/ に同梱し、拡張を esbuild でバンドル
```

`npm run build` は内部で `./gradlew :lsp:installDist` を実行して LSP 配布物を `lsp/vscode/server/` にコピーし、`src/extension.ts` を `dist/extension.js` にバンドルします。Markdown プレビュー用プラグイン（`markdown-it-graphmd`）もバンドル済みです。

## 配置ルール

- `NodeType` と `RelType` はワークスペース直下の `types/` 配下に置く
- `kind: Node` や `Timeline` など型定義以外の GraphMD 文書はワークスペース内のどこに置いてもよい
- 対象ファイルは `.md`

## 使い方

1. `lsp/vscode` で `npm install` と `npm run build` を実行する
2. VSCode で `lsp/vscode` を開く
3. `F5` を押して Extension Development Host を起動する
4. 起動した Extension Host で GraphMD のワークスペースを開く

この拡張は同梱した `server/bin/lsp` を起動して Kotlin 製 LSP サーバへ接続します。

## 配布 (vsix)

```bash
cd lsp/vscode
npm install
npx @vscode/vsce package     # vscode:prepublish が server 同梱 + esbuild バンドルを実行
```

`dist/` と `server/` がパッケージに含まれ、`node_modules` と `src/` は除外されます（依存は `dist/extension.js` にバンドル済み）。

## 期待する動作

- `kind: Node` の `type:` で `NodeType` 補完が出る
- `NodeType` / `RelType` の `extends`, `from`, `to` で補完と定義ジャンプが使える
- `@[label](target relType)` の `target` では Node、`relType` では RelType の補完が出る
- `@props` と relation 記法に Markdown 上でトークンスコープが付き、テーマに応じて色分けされる
- 未定義の `NodeType` / `RelType` / Node 参照に diagnostics が出る
- 参照上で hover と references が使える

## 例

`types/person.md`

```md
---
id: Person
kind: NodeType
---
```

`types/friend-of.md`

```md
---
id: friendOf
kind: RelType
from: [Person]
to: [Person]
---
```

`people/alice.md`

```md
---
id: alice
kind: Node
type: Person
---
Hello @[Bob](bob friendOf)
```
