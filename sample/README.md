# Graph Markdown v0.1 Sample Documents

このzipは、現状のGraph Markdown v0.1仕様に従うサンプルドキュメント集です。

含まれる要素:

- `kind: Timeline`
  - `CommonEra`: number timecode
  - `JapaneseEra`: `CommonEra`を`extends`
  - `ThirdAge`: tuple timecode + table mapping
  - `StoryTime`: number timecode + offset mapping
- `kind: NodeType`
  - `Entity`, `Person`, `Organization`, `Event`
- `kind: RelType`
  - `friendOf`, `worksAt`, `appearsIn`, `happenedDuring`
- `kind: Node`
  - `alice`, `bob`, `example-inc`, `frodo`, `war-of-the-ring`

注意:

- core仕様は`value`を解析しません。
- `number` timecodeは比較可能です。
- `tuple` timecodeは保存・参照・table mappingには使えますが、coreでは大小比較しません。
- `@prop(...)`は使っていません。本文中のnode property bindは`@props{...}`だけです。
- 関係リンクは`@[label](target relType){props}`形式です。
