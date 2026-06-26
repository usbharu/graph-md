---
id: worksAt
kind: RelType

from:
  - Person

to:
  - Organization

props:
  since:
    type: instant
    timeline: CommonEra
    required: false
    index: range

  role:
    type: text
    required: false
    index: fulltext
---

# worksAt

人物が組織で働いている関係。
