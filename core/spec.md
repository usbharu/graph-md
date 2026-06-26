# Graph Markdown Syntax Specification v0.1

## 0. Status

This document defines Graph Markdown v0.1.

Graph Markdown is a Markdown-based document format for representing graph nodes, node types, relation types, timelines, node properties, and relations embedded in human-readable Markdown text.

Normative keywords:

```txt
MUST      必須
MUST NOT  禁止
SHOULD    推奨
MAY       任意
```

---

# 1. Document Structure

A Graph Markdown document consists of:

```md
---
YAML front matter
---

Markdown body
```

The YAML front matter contains machine-readable graph metadata.

The Markdown body contains human-readable content.
Graph-specific data in the body is extracted only through Graph Markdown syntax.

A document MUST have YAML front matter.

---

# 2. Document Kinds

The front matter field `kind` determines the document kind.

Supported kinds:

```txt
Node
NodeType
RelType
Timeline
```

Example:

```yaml
id: alice
kind: Node
type: Person
```

---

# 3. Common Lexical Rules

## 3.1 Identifier

Identifiers are used for:

```txt
id
type
target
relType
from
to
props keys
timeline
extends
```

Syntax:

```txt
Identifier := [A-Za-z_][A-Za-z0-9_.:-]*
```

Examples:

```txt
alice
Person
friendOf
project-a
org:example
```

Identifiers are case-sensitive.

The following are distinct identifiers:

```txt
Person
person
PERSON
```

Whitespace is not allowed inside identifiers.

---

## 3.2 ID Namespaces

IDs are unique per document kind.

The following namespaces exist:

```txt
Node
NodeType
RelType
Timeline
```

Rules:

```txt
Node.id     MUST be unique among Node documents.
NodeType.id MUST be unique among NodeType documents.
RelType.id  MUST be unique among RelType documents.
Timeline.id MUST be unique among Timeline documents.
```

Different namespaces MAY contain the same identifier, but this is discouraged.

Reference resolution:

```txt
Node.type       -> NodeType.id
Relation target -> Node.id
Relation type   -> RelType.id
Prop timeline   -> Timeline.id
RelType.from    -> NodeType.id
RelType.to      -> NodeType.id
Timeline.extends -> Timeline.id
NodeType.extends -> NodeType.id
RelType.extends  -> RelType.id
```

---

## 3.3 Whitespace

For Graph Markdown inline syntax:

```txt
Space      := U+0020
Tab        := U+0009
Newline    := LF | CRLF
HSpace     := Space | Tab
Whitespace := HSpace | Newline
```

Relation link `target` and `relType` MUST be separated by one or more horizontal spaces.

Newline is not allowed between `target` and `relType`.

---

# 4. Text Type

`text` represents a display-oriented string.

A text value MAY be a plain string:

```yaml
name: Alice
```

Or a key-value text map:

```yaml
name:
  default: Alice
  ja: アリス
  en: Alice
  short: Al
  reading: アリス
```

Formal shape:

```txt
Text := string | TextMap

TextMap :=
  default: string
  <key>: string
```

`default` is the fallback string.

Text map keys MUST be identifiers.

Resolution rule:

```txt
If the requested key exists, use that value.
Otherwise, use default.
If default does not exist, validation error.
```

Example:

```yaml
default: Alice
ja: アリス
```

Requested key `ja` resolves to:

```txt
アリス
```

Requested key `en` resolves to:

```txt
Alice
```

---

# 5. Front Matter Schemas

## 5.1 Node

A `Node` document represents one graph node.

Schema:

```yaml
id: Identifier
kind: Node
type: Identifier
props?: Map<Identifier, PropValue>
```

Example:

```yaml
id: alice
kind: Node
type: Person

props:
  name:
    default: Alice
    ja: アリス
  birthDate:
    timeline: CommonEra
    value: "AD 2001-04-12"
```

Node front matter MUST NOT define these fields directly:

```txt
name
aliases
tags
lang
meta
```

Such information SHOULD be represented through `props`.

Example:

```yaml
props:
  name: Alice
  aliases:
    - Alice
    - Al
  tags:
    - sample
```

Unknown top-level fields are validation warnings.
In strict mode, unknown top-level fields are validation errors.

---

## 5.2 NodeType

A `NodeType` document defines a node type.

Schema:

```yaml
id: Identifier
kind: NodeType
extends?: Identifier[]
props?: Map<Identifier, PropSchema>
```

Example:

```yaml
id: Person
kind: NodeType

extends:
  - Entity

props:
  name:
    type: text
    required: true
    index: fulltext

  birthDate:
    type: instant
    timeline: CommonEra
    required: false
    index: range
```

`extends` references other `NodeType` documents.

---

## 5.3 RelType

A `RelType` document defines a relation type.

Schema:

```yaml
id: Identifier
kind: RelType
extends?: Identifier[]
from?: Identifier[]
to?: Identifier[]
props?: Map<Identifier, PropSchema>
```

Example:

```yaml
id: worksAt
kind: RelType

from:
  - Person

to:
  - Organization

props:
  since:
    type: instant
    timeline: CommonEra
    required: false
    index: range

  role:
    type: text
    required: false
    index: fulltext
```

`from` defines allowed source node types.

`to` defines allowed target node types.

If `from` is omitted, any source node type is allowed.

If `to` is omitted, any target node type is allowed.

All relation instances in v0.1 are directed:

```txt
current document node -> target node
```

`directed` is not part of v0.1.

Undirected or symmetric relation semantics are not defined in v0.1.

---

## 5.4 Timeline

A `Timeline` document defines a time system.

Schema:

```yaml
id: Identifier
kind: Timeline
extends?: Identifier[]

timecode?:
  type: number | tuple
  direction?: ascending | descending

props?: Map<Identifier, PropValue>
mappings?: TimelineMapping[]
```

If `timecode.type: tuple`, `direction` MUST NOT be specified.
`direction` is valid only for `timecode.type: number`.

Example:

```yaml
id: CommonEra
kind: Timeline
timecode:
  type: number
  direction: ascending
```

Example for fictional timeline:

```yaml
id: ThirdAge
kind: Timeline
timecode:
  type: tuple
```

---

# 6. PropSchema

`PropSchema` defines the expected type and constraints of a property.

Schema:

```yaml
type: string | text | integer | number | boolean | instant | interval | duration | array | object

required?: boolean
default?: PropValue
index?: none | exact | fulltext | range

timeline?: TimelineSelectorValue
timelines?: TimelineSelectorValue[]

items?: PropSchema
properties?: Map<Identifier, PropSchema>
```

`TimelineSelectorValue` selects which timelines are accepted by a temporal property:

```txt
TimelineSelectorValue :=
  Identifier
  | any
  | TimelineSelector

TimelineSelector :=
  mapped: Identifier
```

An `Identifier` selector matches the named timeline itself and any timeline that directly or indirectly `extends` it.

`any` matches every timeline.

`mapped: Identifier` matches the named timeline, any timeline that `extends` it, and additionally any timeline whose own `mappings` contain an entry with `to` equal to that identifier.

`mappings` do not imply `extends`. They are used for type permission only when a `mapped` selector is present.

If `timeline: B` is required, a value MAY specify timeline `B` or any Timeline that directly or indirectly `extends` `B`.

If `timelines: [B1, B2, ...]` is required, a value MAY specify any timeline accepted by at least one selector in the list.

If a property requires child Timeline `A`, a value on parent Timeline `B` where `A extends B` MUST NOT be accepted implicitly.

Example:

```yaml
props:
  name:
    type: text
    required: true
    index: fulltext

  birthDate:
    type: instant
    timeline: CommonEra
    required: false
    index: range

  aliases:
    type: array
    items:
      type: text
    index: fulltext
```

## 6.1 PropSchema.type

Supported property types:

```txt
string
text
integer
number
boolean
instant
interval
duration
array
object
```

## 6.2 required

If `required: true`, the property MUST exist after normalization.

If a required property has `default`, the property MAY be omitted in the input document.
The default value is inserted during normalization.

Example:

```yaml
name:
  type: text
  required: true
  default: Unknown
```

This is valid.

## 6.3 default

`default` MUST conform to the same schema as the property.

## 6.4 index

`index` is a hint for implementations.

It does not affect graph semantics.

Supported values:

```txt
none
exact
fulltext
range
```

If omitted, the default is implementation-defined.

Recommended defaults:

```txt
string  -> exact
text    -> fulltext
integer -> range
number  -> range
boolean -> exact
instant -> range
interval -> range
duration -> range
array   -> none
object  -> none
```

## 6.5 timeline / timelines

For temporal values, `timeline` restricts the allowed timeline.

```yaml
birthDate:
  type: instant
  timeline: CommonEra
```

`timeline: any` allows any timeline.

Multiple allowed timelines can be written as:

```yaml
eventTime:
  type: instant
  timelines:
    - CommonEra
    - ThirdAge
```

Each entry of `timeline` / `timelines` is a `TimelineSelectorValue`, so an entry MAY also be the literal `any`, or a `TimelineSelector` mapping:

```yaml
eventTime:
  type: instant
  timelines:
    - ThirdAge
    - mapped: CommonEra
```

A single `mapped` selector can also be used directly:

```yaml
eventTime:
  type: instant
  timeline:
    mapped: CommonEra
```

### Acceptance rules

```txt
timeline: X                actual == X  OR  actual extends X
timeline: any              any timeline
timeline: { mapped: X }    actual == X  OR  actual extends X
                           OR  actual has mappings.to == X

timelines: [ s1, s2, ... ] accepted if any selector si accepts actual
```

`mappings` are not an implicit `extends`. They contribute to acceptance only through a `mapped` selector.

`timeline` and `timelines` MUST NOT be used together.

---

# 7. PropValue Interpretation

Graph Markdown uses schema-driven value interpretation.

## 7.1 Schema-driven rule

A property value is interpreted according to its `PropSchema`.

Example:

```yaml
props:
  name: Alice
```

If schema says:

```yaml
name:
  type: text
```

Then the normalized value is:

```yaml
name:
  default: Alice
```

If schema says:

```yaml
name:
  type: string
```

Then the normalized value is:

```yaml
name: Alice
```

## 7.2 Schema-less rule

If no schema is available, values are interpreted only as primitive structural values:

```txt
string
integer
number
boolean
array
object
null
```

Without schema, a value MUST NOT be automatically interpreted as:

```txt
text
instant
interval
duration
```

Example:

```yaml
birthDate:
  timeline: CommonEra
  value: "AD 2001-04-12"
```

Without schema, this is an `object`.

With schema:

```yaml
birthDate:
  type: instant
```

it is interpreted as `instant`.

---

# 8. Property Value Types

## 8.1 string

A scalar string.

```yaml
name: Alice
```

## 8.2 text

A display-oriented text value.

```yaml
name:
  default: Alice
  ja: アリス
```

If the schema type is `text`, a plain string is normalized to:

```yaml
default: <string>
```

## 8.3 integer

A whole number.

```yaml
age: 20
```

## 8.4 number

A decimal number.

```yaml
weight: 0.82
```

## 8.5 boolean

```yaml
active: true
```

## 8.6 instant

A point on a timeline.

Shape:

```yaml
timeline: Identifier
value: string
timecode?: number | number[]
precision?: year | month | day | time | custom
```

Example:

```yaml
birthDate:
  timeline: CommonEra
  value: "AD 2001-04-12"
  timecode: 2001.279
  precision: day
```

If a `PropSchema` for an `instant` fixes `timeline` to a single specific Timeline identifier
(an `Identifier` selector, not `any`, not `mapped`, and not via `timelines`),
the value MAY be written as a bare string shortcut.

Example:

```yaml
birthDate: "2001-04-12"
```

This shortcut is equivalent to:

```yaml
birthDate:
  timeline: CommonEra
  value: "2001-04-12"
```

The shortcut MUST use the timeline specified by the schema.
If the schema does not fix a single concrete timeline (for example `timeline: any`,
`timeline: { mapped: ... }`, or a `timelines` list), the object form MUST be used.

## 8.7 interval

A range on a timeline.

Shape:

```yaml
timeline: Identifier
from?: string | { value: string, timecode?: number | number[], precision?: Identifier } | null
to?: string | { value: string, timecode?: number | number[], precision?: Identifier } | null
fromInclusive?: boolean
toInclusive?: boolean
```

Example:

```yaml
activeDuring:
  timeline: CommonEra
  from:
    value: "AD 2020-01-01"
    timecode: 2020.0
    precision: day
  to: null
  fromInclusive: true
  toInclusive: false
```

Defaults:

```txt
fromInclusive = true
toInclusive = false
```

At least one of `from` or `to` SHOULD be present.

If both are absent, validation warning.
In strict mode, validation error.

## 8.8 duration

A nominal duration or period.

Shape:

```yaml
unit: Identifier
value: number
timeline?: Identifier
```

Example:

```yaml
duration:
  unit: year
  value: 3
```

`duration` values are not normalized to seconds in v0.1.

When a `timeline` is present on a `duration` value, it MUST resolve to a `Timeline.id`
and MUST satisfy the property's `timeline` / `timelines` selector, exactly like an
`instant` or `interval`. A `duration` without a `timeline` is timeline-less and is not
checked against any selector.

If `timeline` is specified, `unit` SHOULD be included in that timeline's `units`.

## 8.9 array

A list of values.

```yaml
aliases:
  - Alice
  - Al
```

If schema defines `items`, each element MUST conform to `items`.

## 8.10 object

A map from identifier keys to values.

```yaml
source:
  title: Example
  url: https://example.com
```

If schema defines `properties`, each defined property is validated against its schema.

Unknown object keys are allowed unless the implementation provides strict object validation.

---

# 9. Timeline Details

The core specification defines only `timecode`, `props`, and `mappings` semantics for timelines.
Metadata such as calendar systems, eras, year zero rules, and date parsing conventions is out of scope.
Implementations MAY store such metadata inside `props`, but core does not interpret it.

## 9.1 TimelineMapping

Timeline mappings define comparability between timelines.

Supported mapping kinds:

```txt
none
offset
table
```

### none

No mapping to another timeline.

```yaml
mappings:
  - kind: none
```

Values on this timeline are only comparable within the same timeline.

### offset

A fixed offset mapping.

```yaml
mappings:
  - kind: offset
    to: CommonEra
    offset: 645
```

`offset` mapping applies only to number timecodes.
If a value has tuple timecode, core MUST NOT apply `offset` mapping to it.

### table

A mapping table.

```yaml
mappings:
  - kind: table
    to: CommonEra
    entries:
      - from:
          value: "TA 3018-09-23"
          timecode: [3018, 9, 23]
        to:
          value: "AD 2000-09-23"
          timecode: 2000.73
```

In `table` mapping, timecode MAY be number or tuple.
Tuple timecodes are exact-match keys only.
Core does not define tuple ordering, partial match, or tuple normalization.

## 9.2 Temporal Literal

Temporal values are opaque strings.
Core does not define calendar parsing, era handling, year-zero semantics, or date normalization.

## 9.3 Precision

`precision` is optional metadata.
Core MAY preserve it, but does not infer it and does not validate it against the temporal string.

---

# 10. Markdown Body Graph Syntax

Graph syntax is valid only in `kind: Node` documents.

In `NodeType`, `RelType`, and `Timeline` documents, Graph syntax is treated as normal text.
In strict mode, implementations MAY emit warnings for Graph syntax outside Node documents.

Graph syntax MUST NOT be extracted from:

```txt
fenced code blocks
indented code blocks
inline code spans
```

---

# 11. Relation Link Syntax

## 11.1 Basic form

A relation is written as:

```md
@[label](target relType)
```

`relType` MAY also be written as a double-quoted string:

```md
@[label](target "relType")
```

With properties:

```md
@[label](target relType){props}
```

Example:

```md
Aliceは@[Bob](bob friendOf){since = { timeline = CommonEra, value = "AD 2024-04-01" }}と友人です。
```

If the target `RelType` schema fixes an `instant` prop timeline, the prop value MAY be written as a bare string:

```md
Aliceは@[Bob](bob friendOf){since = "2024-04-01"}と友人です。
```

Meaning:

```yaml
from: current document id
to: bob
type: friendOf
sourceLabel: Bob
props:
  since:
    timeline: CommonEra
    value: "AD 2024-04-01"
```

## 11.2 RelationLink grammar

```txt
RelationLink :=
  "@[" Label "](" Target HSpace+ RelType ")" RelationProps?

RelationProps :=
  InlineObject

Target :=
  Identifier

RelType :=
  Identifier / QuotedString
```

`RelationProps` MUST immediately follow `)` with no intervening whitespace.

Valid:

```md
@[Bob](bob friendOf){weight = 0.82}
```

Also valid:

```md
@[Bob](bob "friendOf"){weight = 0.82}
```

No props:

```md
@[Bob](bob friendOf) と友人
```

Not props:

```md
@[Bob](bob friendOf) {weight = 0.82}
```

The last example is a relation link followed by normal text.

## 11.3 Label

`Label` is display text in the Markdown body.

It is not graph-semantic data.

Implementations MAY keep it as `sourceLabel` for diagnostics, preview rendering, or source mapping.

It MUST NOT be merged into relation props automatically.

If semantic label data is needed, write it explicitly:

```md
@[Bob](bob friendOf){label = "best friend"}
```

Label rules:

```txt
Label MUST NOT contain newline.
] MUST be escaped as \].
\ MUST be escaped as \\.
```

Example:

```md
@[Bob \] Jr.](bob-jr friendOf)
```

---

# 12. Node Property Bind Syntax

Node properties in the Markdown body are written with `@props`.

`@prop(...)` is not part of v0.1.

## 12.1 Basic form

```md
@props{
  key = value
}
```

Example:

```md
@props{
  birthDate = { timeline = CommonEra, value = "AD 2001-04-12" }
  height = 162.5
}
```

`@props{...}` binds properties to the current Node.

## 12.2 Inline form

Single-line form is also valid:

```md
@props{name = "Alice"}
```

## 12.3 PropsBind grammar

```txt
PropsBind :=
  "@props" InlineObject
```

---

# 13. Inline Props Syntax

Graph Markdown uses a small TOML-like inline property syntax.

This syntax is used by:

```txt
RelationLink props
@props
nested objects
```

## 13.1 InlineObject

```txt
InlineObject :=
  "{" AssignmentList? "}"

AssignmentList :=
  Assignment (Separator Assignment)* Separator?

Assignment :=
  Key HSpace* "=" HSpace* Value

Key :=
  Identifier

Separator :=
  HSpace* "," HSpace*
  | HSpace* Newline HSpace*
```

Duplicate keys in the same `InlineObject` are syntax errors.

Example:

```txt
{ name = "Alice", age = 20 }
```

Example multiline object:

```txt
{
  name = "Alice"
  age = 20
}
```

Trailing separators are allowed:

```txt
{
  name = "Alice",
}
```

## 13.2 Value

```txt
Value :=
  String
  | Integer
  | Number
  | Boolean
  | Null
  | BareString
  | Array
  | InlineObject
```

## 13.3 String

Only double-quoted strings are supported.

```txt
"hello"
"backend engineer"
"line\nbreak"
"quote: \""
```

Supported escapes:

```txt
\"  quote
\\  backslash
\n  newline
\r  carriage return
\t  tab
\uXXXX unicode code point
```

Single-quoted strings are not supported in v0.1.

## 13.4 BareString

A bare string is an unquoted identifier.

```txt
backend
CommonEra
AD
org:example
project-a
```

Bare strings MUST match `Identifier`.

Therefore this is valid:

```txt
role = backend
```

This is invalid:

```txt
role = backend engineer
```

Use quotes for strings containing spaces:

```txt
role = "backend engineer"
```

## 13.5 Integer

```txt
Integer := "-"? [0-9]+
```

Examples:

```txt
123
-123
```

## 13.6 Number

```txt
Number := "-"? [0-9]+ "." [0-9]+
```

Examples:

```txt
0.82
-0.82
```

Scientific notation is not supported in v0.1.

## 13.7 Boolean

```txt
true
false
```

Only lowercase forms are valid.

## 13.8 Null

```txt
null
```

Only lowercase `null` is valid.

## 13.9 Array

```txt
Array :=
  "[" ArrayItems? "]"

ArrayItems :=
  Value (ArraySeparator Value)* ArraySeparator?

ArraySeparator :=
  HSpace* "," HSpace*
```

Examples:

```txt
[foo, bar, "hello world", 123]
```

Newline may appear around values, but array elements MUST be comma-separated.

Valid:

```txt
[
  foo,
  bar,
  "hello world",
]
```

Invalid:

```txt
[
  foo
  bar
]
```

## 13.10 Comments

Comments are not supported inside Inline Props in v0.1.

Invalid:

```txt
{
  name = "Alice" # comment
}
```

---

# 14. Escaping Graph Syntax

To write literal Graph Markdown markers in normal text, escape the `@`.

```md
\@[Bob](bob friendOf)
```

This MUST NOT be extracted as a relation.

```md
\@props{name = "Alice"}
```

This MUST NOT be extracted as a props bind.

A graph marker is recognized only if `@` is not immediately preceded by an unescaped backslash.

---

# 15. Parsing Order

Implementations MUST process documents in this order:

```txt
1. Split YAML front matter and Markdown body.
2. Parse YAML front matter.
3. Validate required front matter fields: id, kind, etc.
4. Resolve document kind.
5. Identify Markdown code regions:
   - fenced code blocks
   - indented code blocks
   - inline code spans
6. If kind is Node, scan non-code body regions for:
   - RelationLink
   - PropsBind
7. Parse Inline Props.
8. Build raw DocumentIndex.
9. Resolve references:
   - Node.type
   - relation target
   - relation type
   - timeline
   - extends
10. Resolve inherited schemas.
11. Merge front matter props and body props.
12. Apply defaults.
13. Validate and normalize PropValues using PropSchema.
14. Validate relation constraints.
15. Emit normalized graph entities and diagnostics.
```

Graph syntax is extracted from raw Markdown text before normal Markdown rendering.

Graph Markdown does not guarantee that relation syntax is rendered consistently by ordinary Markdown renderers.

---

# 16. Props Merge

Node props can appear in:

```txt
front matter props
body @props blocks
```

Merge order:

```txt
front matter props
then body props in source order
```

Merge rule:

```txt
Merge is shallow and key-based.
If the same key appears later, the later value replaces the earlier value completely.
```

Example front matter:

```yaml
props:
  name:
    default: Alice
    ja: アリス
```

Body:

```md
@props{
  name = { en = "Alice" }
}
```

Merged result:

```yaml
props:
  name:
    en: Alice
```

No deep merge is performed.

If the same key appears in multiple `@props` blocks, the later occurrence wins.

Duplicate keys inside the same `@props{...}` block are syntax errors.

---

# 17. Relation Instances

Each `RelationLink` occurrence creates one relation instance.

Even if two relation links have the same `from`, `to`, and `type`, they are separate instances in v0.1.

Example:

```md
@[Bob](bob friendOf)
@[Bob](bob friendOf)
```

This creates two relation instances.

## 17.1 Relation instance identity

A relation instance is identified by source location.

Recommended identity shape:

```txt
document id + source range
```

Example:

```txt
alice@120..145
```

Implementations SHOULD store:

```txt
source file path
source byte range
source line/column range
```

Stable user-defined relation IDs are not part of v0.1.

---

# 18. Normalized Data Model

## 18.1 Normalized Node

```yaml
id: Identifier
kind: Node
type: Identifier
props: Map<Identifier, NormalizedPropValue>
source:
  path: string
```

## 18.2 Normalized Relation

```yaml
from: Identifier
to: Identifier
type: Identifier
props: Map<Identifier, NormalizedPropValue>
sourceLabel: string
source:
  documentId: Identifier
  path: string
  range: SourceRange
```

`sourceLabel` is not semantic graph data.

## 18.3 Normalized NodeType

```yaml
id: Identifier
kind: NodeType
props: Map<Identifier, ResolvedPropSchema>
source:
  path: string
```

## 18.4 Normalized RelType

```yaml
id: Identifier
kind: RelType
from?: Identifier[]
to?: Identifier[]
props: Map<Identifier, ResolvedPropSchema>
source:
  path: string
```

## 18.5 Normalized Timeline

```yaml
id: Identifier
kind: Timeline
timecode?:
  type: number | tuple
  direction?: ascending | descending
props?: Map<Identifier, NormalizedValue>
mappings?: TimelineMapping[]
source:
  path: string
```

---

# 19. Reference Resolution

## 19.1 Node.type

Every `Node.type` MUST resolve to a `NodeType.id`.

Unresolved type is a `ReferenceError`.

## 19.2 Relation target

Every relation target MUST resolve to a `Node.id`.

Unresolved target is a `ReferenceError`.

## 19.3 Relation type

Every relation type MUST resolve to a `RelType.id`.

Unresolved relation type is a `ReferenceError`.

## 19.4 Timeline

Every temporal value timeline MUST resolve to a `Timeline.id`.

Unresolved timeline is a `ReferenceError`.

---

# 20. Relation Validation

For a relation:

```yaml
from: alice
to: example-inc
type: worksAt
```

Given:

```yaml
id: worksAt
kind: RelType

from:
  - Person

to:
  - Organization
```

Validation rules:

```txt
source node type MUST be one of RelType.from, if from is specified.
target node type MUST be one of RelType.to, if to is specified.
```

If `from` is omitted, no source type constraint is applied.

If `to` is omitted, no target type constraint is applied.

Violations are `ConstraintError`.

---

# 21. Type Inheritance

## 21.1 NodeType inheritance

`NodeType.extends` references parent NodeTypes.

Example:

```yaml
id: Person
kind: NodeType

extends:
  - Entity
```

Resolution order:

```txt
parents in listed order
then child
```

Property inheritance rules:

```txt
Parent props are inherited by the child.
If multiple parents define the same prop, their schemas MUST be compatible.
If schemas are incompatible, SchemaError.
Child props MAY refine parent props.
Child props MUST NOT change the prop type.
required: true MUST NOT be relaxed to false.
```

`index` MAY be changed by the child.

`default` MAY be overridden by the child if the value conforms to the same schema.

## 21.2 RelType inheritance

`RelType.extends` references parent RelTypes.

Rules:

```txt
Parent props are inherited.
Child props follow the same compatibility rules as NodeType props.
Child from/to constraints are combined with parent constraints.
```

For `from` and `to`:

```txt
If child omits from/to, inherited constraints remain.
If child defines from/to, child constraints MUST be equal to or narrower than inherited constraints.
```

If compatibility cannot be determined, implementations SHOULD emit SchemaError.

## 21.3 Timeline inheritance

`Timeline.extends` references parent Timelines.

Rules:

```txt
If Timeline A directly or indirectly extends Timeline B, A is a subtimeline of B.
Child inherits timecode, props, and mappings from parent unless explicitly defined.
Subtimeline values keep their original timeline identifier after normalization.
Parent timeline values MUST NOT be implicitly assigned where a child timeline is required.
extends MUST be used only for lossless specialization on the same time axis.
If timecode schema or coordinate semantics differ, use mappings instead of extends.
```

---

# 22. Unknown Properties

If a Node has a property not defined by its NodeType schema:

```txt
The property is allowed.
The property is treated as schema-less.
Implementations MAY emit a warning.
```

If a Relation has a property not defined by its RelType schema:

```txt
The property is allowed.
The property is treated as schema-less.
Implementations MAY emit a warning.
```

In strict mode, unknown properties MAY be treated as validation errors.

---

# 23. Diagnostics

Implementations SHOULD classify diagnostics into the following categories.

## 23.1 SyntaxError

Invalid Graph Markdown syntax.

Examples:

```txt
Malformed RelationLink
Malformed @props block
Invalid Inline Props
Unclosed string
Duplicate key in same InlineObject
```

## 23.2 SchemaError

Invalid type definition.

Examples:

```txt
Invalid PropSchema
Invalid inheritance
Incompatible inherited prop schemas
Invalid Timeline definition
```

## 23.3 ReferenceError

Unresolved reference.

Examples:

```txt
Unknown Node target
Unknown NodeType
Unknown RelType
Unknown Timeline
Unknown parent type
```

## 23.4 TypeError

PropValue does not match PropSchema.

Examples:

```txt
string value given for number prop
object value given for text prop without valid default
instant missing timeline
```

## 23.5 ConstraintError

Graph constraint violation.

Examples:

```txt
RelType.from violation
RelType.to violation
required prop missing after normalization
invalid temporal precision
```

---

# 24. Compatibility with Markdown

Graph Markdown syntax is extracted before Markdown rendering.

Ordinary Markdown renderers are not required to understand Graph Markdown.

This specification does not guarantee that:

```md
@[Bob](bob friendOf)
```

is rendered consistently by non-Graph Markdown renderers.

A Graph Markdown renderer SHOULD render relation links as normal hyperlinks when possible.

Recommended rendering:

```html
<a href="bob" data-rel-type="friendOf">Bob</a>
```

This rendering is informative, not normative.

---

# 25. Minimal Examples

## 25.1 Node

```md
---
id: alice
kind: Node
type: Person

props:
  name:
    default: Alice
    ja: アリス
---

# Alice

Aliceの誕生日は2001年4月12日です。

@props{
  birthDate = { timeline = CommonEra, value = "AD 2001-04-12" }
}

Aliceは@[Bob](bob friendOf){
  since = { timeline = CommonEra, value = "AD 2024-04-01" }
  weight = 0.82
}と友人です。
```

## 25.2 NodeType

```md
---
id: Person
kind: NodeType

props:
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
```

## 25.3 RelType

```md
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
---

# friendOf

人と人の友人関係を表す。
```

## 25.4 Timeline

```md
---
id: CommonEra
kind: Timeline

calendar:
  type: gregorian

continuous: true
yearZero: false
defaultEra: AD

eras:
  BC:
    direction: backward
    before: AD

  AD:
    direction: forward
    after: BC

units:
  - year
  - month
  - day
---

# Common Era

西暦・紀元前を表す時系列。
```

---

# 26. v0.1 Design Decisions

Graph Markdown v0.1 intentionally keeps the syntax small.

Included:

```txt
Node
NodeType
RelType
Timeline
RelationLink
@props
schema-driven property typing
shallow props merge
directed relation instances
timeline-aware temporal values
text as string or simple key-value map
```

Excluded:

```txt
@prop(...)
directed field
undirected relation normalization
stable user-defined relation IDs
comments in Inline Props
single-quoted strings
scientific notation
automatic date parsing without schema
deep props merge
Markdown renderer compatibility guarantee
```
