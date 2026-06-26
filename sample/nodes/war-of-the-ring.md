---
id: war-of-the-ring
kind: Node
type: Event

props:
  name:
    default: War of the Ring
    ja: 指輪戦争
  during:
    timeline: ThirdAge
    from:
      value: "TA 3018-09-23"
      timecode: [3018, 9, 23]
      precision: day
    to:
      value: "TA 3019-03-25"
      timecode: [3019, 3, 25]
      precision: day
    fromInclusive: true
    toInclusive: true
---

# War of the Ring

第三紀上のEvent例です。

`ThirdAge`はtuple timecodeなので、coreはこのintervalの境界順序を検証しません。

table mappingにより、一部のtimecodeは`CommonEra`上のnumber timecodeへ対応付けられます。
