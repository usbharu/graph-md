---
id: Entity
kind: NodeType

props:
  name:
    type: text
    required: true
    index: fulltext

  aliases:
    type: array
    items:
      type: text
    required: false
    index: fulltext

  note:
    type: text
    required: false
    index: fulltext
---

# Entity

すべての基本NodeType。
