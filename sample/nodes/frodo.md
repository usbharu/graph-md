---
id: frodo
kind: Node
type: Person

props:
  name:
    default: Frodo
    ja: フロド
  birthDate:
    timeline: ThirdAge
    value: "TA 2968"
    timecode: [2968]
    precision: year
---

# Frodo

Frodoは第三紀Timelineを使うサンプル人物です。

@props{
  note = { default = "Uses tuple timecode", ja = "tuple timecodeを使う例" }
}

Frodoは@[War of the Ring](war-of-the-ring appearsIn){
  role = { default = "ring bearer", ja = "指輪所持者" }
}に登場します。
