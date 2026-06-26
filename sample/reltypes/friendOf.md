---
id: friendOf
kind: RelType

from:
  - Person

to:
  - Person

props:
  since:
    type: instant
    timeline: CommonEra
    required: false
    index: range

  weight:
    type: number
    required: false
    index: range

  note:
    type: text
    required: false
    index: fulltext
---

# friendOf

人物同士の友人関係。

v0.1ではすべてのrelation instanceは有向として保存される。
