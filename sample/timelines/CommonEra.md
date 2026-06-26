---
id: CommonEra
kind: Timeline

timecode:
  type: number
  direction: ascending

props:
  label:
    default: Common Era
    ja: 共通紀年
  note: Core does not parse AD/BC values. timecode is project-defined.
---

# CommonEra

一般的な共通紀年を表すTimeline。

Graph Markdown coreは`value`の`AD 2024-04-01`などを解析しない。
機械比較には`timecode`を使う。
