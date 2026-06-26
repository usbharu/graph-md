---
id: StoryTime
kind: Timeline

timecode:
  type: number
  direction: ascending

mappings:
  - to: CommonEra
    kind: offset
    offset: 1900

props:
  label:
    default: Story Time
    ja: 物語内時刻
  note: target.timecode = source.timecode + 1900
---

# StoryTime

物語内の単純な数値Timeline。

`offset` mappingはnumber timecodeにだけ適用できる。
