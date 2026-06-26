---
id: bob
kind: Node
type: Person

props:
  name:
    default: Bob
    ja: ボブ
  birthDate:
    timeline: CommonEra
    value: "AD 2000-08-20"
    timecode: 2000.635
    precision: day
---

# Bob

BobはAliceの友人です。

@props{
  note = { default = "A sample person node", ja = "サンプル人物ノード" }
}

Bobも@[Alice](alice friendOf){
  since = { timeline = CommonEra, value = "AD 2024-04-01", timecode = 2024.25, precision = day }
  weight = 0.82
}です。
