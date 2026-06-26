---
id: appearsIn
kind: RelType

from:
  - Entity

to:
  - Event

props:
  role:
    type: text
    required: false
    index: fulltext
---

# appearsIn

EntityがEventに登場する関係。
