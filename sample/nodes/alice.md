---
id: alice
kind: Node
type: Person

props:
  name:
    default: Alice
    ja: アリス
  aliases:
    - Alice
    - Al
---

# Alice

Aliceは架空サンプルの人物です。

@props{
  birthDate = { timeline = CommonEra, value = "AD 2001-04-12", timecode = 2001.279, precision = day }
  height = 162.5
}

Aliceは@[Bob](bob friendOf){
  since = { timeline = CommonEra, value = "AD 2024-04-01", timecode = 2024.25, precision = day }
  weight = 0.82
  note = { default = "close friend", ja = "親しい友人" }
}です。

Aliceは@[Example Inc](example-inc worksAt){
  since = { timeline = JapaneseEra, value = "令和6年4月1日", timecode = 2024.25, precision = day }
  role = { default = "backend engineer", ja = "バックエンドエンジニア" }
}で働いています。

コード中のGraph記法は抽出されない想定です。

```md
@[Bob](bob friendOf)
@props{name = "Not Alice"}
```
