---
id: Person
kind: NodeType
props: 
    gender:
        type: text
        required: true
    name: 
        type: text
        required: true
        index: fulltext
    birthDate:
        type: instant
        timeline: CommonEra
        required: false
        index: range 
---

# Person
人物を表す型。