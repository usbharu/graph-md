---
id: ThirdAge
kind: Timeline

timecode:
  type: tuple

mappings:
  - to: CommonEra
    kind: table
    entries:
      - from:
          value: "TA 3018-09-23"
          timecode: [3018, 9, 23]
        to:
          value: "AD 2000-09-23"
          timecode: 2000.73

      - from:
          value: "TA 3019-03-25"
          timecode: [3019, 3, 25]
        to:
          value: "AD 2001-03-25"
          timecode: 2001.23

props:
  label:
    default: Third Age
    ja: 第三紀
  note: Tuple timecode is not comparable by core, but can be used in table mappings.
---

# ThirdAge

架空世界の第三紀Timeline。

`timecode.type: tuple`なので、coreは大小比較を行わない。
`mappings.kind: table`ではtuple timecodeを完全一致キーとして使える。
