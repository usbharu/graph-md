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
./gradlew :lsp:installDist
cd lsp/vscode
npm install
npm run compile
```

## 配置ルール

- `NodeType` と `RelType` はワークスペース直下の `types/` 配下に置く
- `kind: Node` や `Timeline` など型定義以外の GraphMD 文書はワークスペース内のどこに置いてもよい
- 対象ファイルは `.md`

## 使い方

1. リポジトリルートで `./gradlew :lsp:installDist` を実行する
2. `lsp/vscode` で `npm install` と `npm run compile` を実行する
3. VSCode で `lsp/vscode` を開く
4. `F5` を押して Extension Development Host を起動する
5. 起動した Extension Host で GraphMD のワークスペースを開く

この拡張は `lsp/build/install/lsp/bin/lsp` を起動して Kotlin 製 LSP サーバへ接続します。

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
