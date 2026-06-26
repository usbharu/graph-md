---
id: Event
kind: NodeType
extends:
  - Entity

props:
  at:
    type: instant
    timeline: 
      mapped: CommonEra
    required: false
    index: range

  during:
    type: interval
    timeline:
      mapped: CommonEra
    required: false
    index: range
---

# Event

出来事を表すNodeType。
