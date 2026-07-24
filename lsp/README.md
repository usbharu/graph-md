# GraphMD LSP

`core` を使って GraphMD 文書を解析する Kotlin/JVM の LSP サーバです。VSCode 用の薄いクライアントは `lsp/vscode` にあります。

## 対応内容

- `types/**/*.md` の `NodeType` / `RelType` を優先した補完
- ワークスペース内どこにあっても `kind: Node` 文書を索引
- `type`, `extends`, `from`, `to` の補完・定義ジャンプ
- `@link(validTime=...){props}[label](target relType)` の `target` / `relType` 補完・定義ジャンプ
- NodeType / RelType の Property 定義に従うキー・値型・Timeline の補完と入力スニペット
- RelType の `from` / `to` 制約に従った Link 対象 Node・RelType の絞り込み
- 入力途中の front matter でも `kind` / `type` を推論し、既入力キーを除外した文脈補完
- 診断から利用できる VS Code Quick Fix
  - 未定義の Node / NodeType / RelType / Timeline を既存定義へ置換、または定義ファイルを作成
  - front matter の追加・閉じ、必須フィールド、未知フィールド、不正な kind / Property type / index の修正
  - 必須 Property の追加、未知 Property の削除または NodeType / RelType への宣言
  - 重複 ID、YAML 値型・リスト、Timeline selector、継承・from/to 制約の修正
  - `@props` / `@link` / relation の引数・空白・閉じ括弧・target/type 形式の修正
  - validTime の逆転、許可されない Timeline、duration 境界、relation endpoint 制約の修正
- `@props(validTime=...){...}` と `@link(validTime=...){...}[label](target relType)` のシンタックスハイライト
- `core` の `GraphCompiler` による未定義参照や制約違反の診断
- Hover と references

## セットアップ

```bash
pnpm install
pnpm --dir lsp/vscode build  # Kotlin LSP と core/JS をビルドして server/ に同梱し、拡張を esbuild でバンドル
```

`pnpm --dir lsp/vscode build` は内部で `./gradlew :lsp:installDist` を実行して LSP 配布物を `lsp/vscode/server/` にコピーし、`src/extension.ts` を `dist/extension.js` にバンドルします。Markdown プレビュー用プラグイン（`markdown-it-graphmd`）もバンドル済みです。

## 配置ルール

- `NodeType` と `RelType` はワークスペース直下の `types/` 配下に置く
- `kind: Node` や `Timeline` など型定義以外の GraphMD 文書はワークスペース内のどこに置いてもよい
- 対象ファイルは `.md`

## 使い方

1. リポジトリルートで `pnpm install` と `pnpm --dir lsp/vscode build` を実行する
2. VSCode で `lsp/vscode` を開く
3. `F5` を押して Extension Development Host を起動する
4. 起動した Extension Host で GraphMD のワークスペースを開く

この拡張は同梱した `server/bin/lsp` を起動して Kotlin 製 LSP サーバへ接続します。

## 配布 (vsix)

```bash
pnpm install
pnpm --dir lsp/vscode package  # vscode:prepublish が server 同梱 + esbuild バンドルを実行
```

`dist/` と `server/` がパッケージに含まれ、`node_modules` と `src/` は除外されます（依存は `dist/extension.js` にバンドル済み）。

## 期待する動作

- `kind: Node` の `type:` で `NodeType` 補完が出る
- `NodeType` / `RelType` の `extends`, `from`, `to` で補完と定義ジャンプが使える
- `@link(validTime=...){props}[label](target relType)` の `target` では Node、`relType` では RelType の補完が出る
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
Hello @link{}[Bob](bob friendOf)
```
