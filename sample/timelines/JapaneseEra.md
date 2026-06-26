---
id: JapaneseEra
kind: Timeline
extends: CommonEra

props:
  label:
    default: Japanese Era
    ja: 和暦
  note: This timeline shares CommonEra number timecode via extends.
---

# JapaneseEra

`CommonEra`を継承するTimeline。

`extends`により、`JapaneseEra`の`timecode`は`CommonEra`の`timecode`としてそのまま扱える。
ただし`value`文字列は自動変換しない。
