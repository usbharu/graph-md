---
id: Person
kind: NodeType
extends:
  - Entity

props:
  birthDate:
    type: instant
    timeline: 
      mapped: CommonEra
    required: false
    index: range

  height:
    type: number
    required: false
    index: range
---

# Person

人物を表すNodeType。

`birthDate.timeline: CommonEra`なので、`CommonEra`またはそのサブTimelineを指定できる。
