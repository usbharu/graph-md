---
id: Organization
kind: NodeType
extends:
  - Entity

props:
  founded:
    type: instant
    timeline: CommonEra
    required: false
    index: range
---

# Organization

組織を表すNodeType。
