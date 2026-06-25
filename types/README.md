# types

`NodeType` と `RelType` の GraphMD 文書をこのディレクトリ配下に置くと、`lsp` モジュールが VSCode 上で索引します。

例:

```md
---
id: Person
kind: NodeType
---
```

```md
---
id: friendOf
kind: RelType
from: [Person]
to: [Person]
---
```
