# グラフ志向リンクを持ったMarkdown拡張記法を活用したマルチメディア対応複数時系列ナレッジグラフ

本文書は以下の構成で記述されている。すべての実装の正本となる

1. コンセプト
2. 仕様
    1. データモデルおよびモデルの継承
    2. 時間モデル
    3. Markdown拡張記法
    4. マルチメディア対応
3. 実装
    1. データモデル処理部分(コア)
    2. Markdown拡張記法パーサ
    3. markdown-itプラグイン
    4. VSCode拡張(lsp)
4. サンプル

## コンセプト

本プロジェクトのコンセプトは大きく分けて三つある

- グラフ志向リンクを持ったMarkdown拡張記法
- マルチメディア対応なMarkdownベースのドキュメント集
- 複数時系列ナレッジグラフ

### グラフ志向リンクについて

既存のRDFやJSON-LD、Neo4Jなどで実現されるナレッジグラフは編集に専用ソフトが必要であり、手間がかかる。

また、機械的に処理することを優先しているため、自然言語で表現したい内容を表すには不適である。

そこで、Markdownを拡張した記法で自然言語中に登場する文書間のリンクに属性やリンク種別を付与することで、ナレッジグラフを実現する。
また、文書、リンク種別、リンク属性などに型システムを導入し、検索性を向上させる。

### マルチメディア対応

Markdownのみではナレッジグラフとして使いにくいため、マルチメディア対応を行う。

メディアに対応したMarkdownファイルにメタデータを記述し、メディアファイルの文書での表現などを実現する。

### 複数時系列ナレッジグラフ

既存のナレッジグラフでは複数時系列で起こったことや、複数時系列に所属するものを表現できない。

第一級オブジェクトとして時系列をサポートし、Node、Link、およびそれらに属するPropertyが時系列に所属している状態を表現できる。

## 仕様

### 用語の定義

| 名前       | 定義                                                         |
|----------|------------------------------------------------------------|
| Node     | 一つのMarkdown文章。ナレッジグラフで一つの単位 NodeTypeを持つ                    |
| NodeType | NodeType Nodeが持つべきPropertyを定義する 継承関係をもてる                   |
| Timeline | 利用者が記述する時間座標表現。コンパイラがDomain、Axis、CoordinateSystemへ正規化する       |
| Property | Node・Linkに属する属性  どの時系列のどの期間有効かを宣言できる                       |
| Link     | Node間の関係を表現する RelTypeを持つ どの時系列のどの期間有効かを宣言できる Propertyをもてる |
| RelType  | Node間の関係を定義する Linkが持つべきPropertyを定義する 継承関係をもてる               |
| Media    | Markdown文章であるNode。メディア実体へのURLを持ち、実質的にその実体へのリンクを表現する |

### ID

frontmatterで定義する`id`は`[A-Za-z_][A-Za-z0-9_.:-]*`に一致しなければならない。
先頭にはASCII英字または`_`を使用し、2文字目以降にはASCII英数字、`_`、`.`、`:`、`-`を使用できる。
空の`id`はエラーとし、それ以外の規則に一致しない`id`は定義側で警告として報告する。非正規IDであっても、警告後は参照解決の対象として保持する。ただし、RelTypeの`id`に空白を含めてはならず、これはエラーとする。

ID参照は、構文から文字列として復元できる限り破棄せず、デコード後の文字列を正規化せずに定義IDと完全一致で解決する。参照値が上記の正規表現に一致しないことだけを理由に参照側で重ねて警告してはならない。解決先が存在しない場合は通常の未解決参照として診断する。YAML frontmatterの参照フィールドは任意の非空文字列を保持でき、本文中の参照は各Markdown拡張構文でパース可能な範囲で保持する。

### 検証モード

標準実装は`Default`と`Strict`の検証モードを提供する。
`Default`では未知のNodeトップレベルフィールドおよびNodeTypeまたはRelTypeで定義されていないPropertyを警告として報告する。
`Strict`ではこれらの未知フィールドおよび未知Propertyをエラーとして報告する。構文エラー、必須フィールドの欠落、型違反、参照エラーおよび制約違反の重大度は検証モードによって緩和しない。

### 型

Propertyで使える型

| 型        | 説明                                                        |
|----------|-----------------------------------------------------------|
| number   | 数値                                                        |
| string   | 文字列                                                       |
| text     | Map<string,any> 多言語化やエイリアスとして利用 Key-ValueだがKeyの役割は決まっていない |
| instant  | 特定のTimeline中の一点                                           |
| duration | 特定のTimeline中の期間                                           |
| array    | 配列。各要素は個別にvalidTimeを指定できる                              |

時点のtimecodeは有限の数値（小数を含む）である。`NaN`および正負の無限大は使用できない。

全てのPropertyの値はvalidTimeを持つことができ、validTimeは主張するTimelineと期間を指定することができる。

instantおよびdurationのTimelineは、値に明示的なtimelineが指定されている場合はそれに従う。明示指定がない場合は、そのPropertyに有効なvalidTimeのすべてのTimelineに所属する。

`string`は独立した型である。PropSchemaが`text`である場合に限り、文字列リテラルはtext値の`default`キーを省略した記法として扱う。したがって、次の二つは同じ値を表す。

```yaml
props:
  name:
    default: alice
```

```yaml
props:
  name: alice
```

PropSchemaが`string`である場合、値は文字列のままでありtext値にはならない。その他のtext以外の型でも、文字列をtext値として解釈してはならない。

arrayの`items`は任意であり、省略した場合は任意の型の要素を許容する。arrayの要素にvalidTimeを指定する場合、要素は`value`と`validTime`を持つエントリとして表現する。itemsで指定した型は、そのエントリの`value`に適用する。
`items`はPropSchemaの共通フィールドとして記述でき、`items`自体は通常のPropSchemaとして検証するが、Property値への適用は`type: array`のときだけ行う。それ以外の型に指定された`items`は、Property値の検証、継承互換性、および正規化結果へ影響しない。

PropSchemaに検索用の`index`設定は存在しない。`index`フィールドを記述した場合は未知のProperty Schemaフィールドとして診断する。検索インデックスの構築方法はGraphMD文書モデルの範囲外とする。

空配列`[]`の意味は解決済みPropSchemaによって決まる。`type: array`では、`[]`は要素を持たない有効なarray値であり、一つのProperty主張として扱う。それ以外の型、または利用可能なPropSchemaがない場合は、`[]`をPropertyの時系列バリエーションが0件であるものとして扱い、そのProperty自体が指定されなかった場合と同じ正規化結果にする。したがって、そのPropertyが`required`なら必須Property欠落として診断する。

利用可能なPropSchemaがないため、値の配列を「Property自体の時系列バリエーション」と「validTimeを持つarray要素」のどちらとして解釈するか判別できない場合、既定では前者として解釈する。実装はこの曖昧な解釈について警告を出すべきである。

validTimeが複数指定された場合、各validTimeはORとして扱う。fromとtoの端点はともに含む。fromまたはtoが指定されない場合、その方向の境界は確定していない。fromとtoの両方が指定され、比較可能であってfromがtoより後の場合、実装は警告を出すべきである。

Propertyの時系列バリエーションを配列で表現する場合、`validTime`を持たないエントリはフォールバック値を表し、一つのPropertyにつき最大一つだけ指定できる。
YAMLのPropertyと本文中のPropertyをマージするとき、「完全に同じvalidTime」はvalidTimeをOR集合として比較する。したがって、記述順だけが異なる同じvalidTime集合は同一とみなし、本文中の値で上書きする。異なるvalidTimeの主張とフォールバック値は保持する。

### データモデルおよびモデルの継承

以下は概念であり、実際の構文ではない。
掲載するJSON Schemaは文書の構造を検証する。Propertyの値がPropSchemaの`type`、`items`、`timeline`、`required`および継承後の制約に適合するかどうかは、参照先の型定義を解決した後の意味検証で判定する。
IDの字句規則も意味検証で判定する。`id`の欠落、文字列以外、および空文字列は構造エラーとする一方、空ではあるが`[A-Za-z_][A-Za-z0-9_.:-]*`に一致しない`id`は警告として文書を継続して扱う。この重大度の違いを保持するため、掲載するJSON Schemaは原則として`id`を文字列かつ1文字以上であることだけ検証する。RelTypeの`id`だけは例外として、空白を禁止する`pattern`も構造検証する。
validTimeはNodeを根として継承される。スコープの順序は`Node > (NodeのProperty = Link) > 各Propertyの内部要素`であり、Propertyのobjectおよびarrayの内部要素にも再帰的に同じ規則を適用する。上位のvalidTimeは、下位でvalidTimeが指定されなかった場合のフォールバックとして用いる。下位で指定されたvalidTimeはその要素と配下の要素にだけ適用し、上位のvalidTimeを変更・結合しない。

#### Node

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://graph-md.usbharu.dev/schema/node",
  "title": "Node",
  "type": "object",
  "description": "Node",
  "properties": {
    "id": {
      "type": "string",
      "minLength": 1
    },
    "kind": {
      "type": "string",
      "enum": [
        "Node",
        "Media"
      ]
    },
    "type": {
      "type": "string",
      "description": "NodeType"
    },
    "url": {
      "type": "string",
      "description": "Media実体のURL"
    },
    "validTime": {
      "type": "array",
      "minItems": 1,
      "items": {
        "$ref": "#/$defs/validTime"
      }
    },
    "props": {
      "type": "object",
      "description": "NodeTYpeで定義されているproperty",
      "additionalProperties": true
    }
  },
  "additionalProperties": false,
  "required": [
    "type",
    "id",
    "kind"
  ],
  "allOf": [
    {
      "if": {
        "properties": { "kind": { "const": "Media" } },
        "required": [ "kind" ]
      },
      "then": { "required": [ "url" ] }
    }
  ],
  "$defs": {
    "propEntries": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/propEntry"
      },
      "contains": {
        "type": "object",
        "not": {
          "required": [
            "validTime"
          ]
        }
      },
      "minContains": 0,
      "maxContains": 1
    },
    "propEntry": {
      "type": "object",
      "description": "Propertyまたは配列要素の値と、そのvalidTimeを表すエントリ",
      "properties": {
        "value": true,
        "validTime": {
          "type": "array",
          "minItems": 1,
          "items": {
            "$ref": "#/$defs/validTime"
          }
        }
      },
      "required": [
        "value"
      ],
      "additionalProperties": false
    },
    "validTime": {
      "type": "object",
      "properties": {
        "timeline": {
          "type": "string"
        },
        "from": {
          "$ref": "#/$defs/temporalCoordinate"
        },
        "to": {
          "$ref": "#/$defs/temporalCoordinate"
        }
      },
      "required": [
        "timeline"
      ],
      "additionalProperties": false
    },
    "temporalCoordinate": {
      "oneOf": [
        { "type": "number" },
        { "type": "string", "minLength": 1 }
      ]
    }
  }
}
```

NodeおよびMediaのトップレベルには、上記の`id`、`kind`、`type`、`url`、`validTime`、`props`だけを指定できる。
Propertyとして扱う`name`、`aliases`、`tags`、`lang`、`meta`をトップレベルへ記述してはならず、NodeTypeの`props`で定義したうえでNodeの`props`へ記述する。
未知のトップレベルフィールドおよび未知のPropertyは通常の検証では警告し、strict検証ではエラーとして報告する。予約済みトップレベルフィールドの使用は検証モードにかかわらずエラーとする。

#### NodeType

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://graph-md.usbharu.dev/schema/node-type",
  "type": "object",
  "required": [
    "id",
    "kind"
  ],
  "properties": {
    "id": {
      "type": "string",
      "minLength": 1
    },
    "kind": {
      "const": "NodeType"
    },
    "extends": { "$ref": "#/$defs/identifierList" },
    "props": {
      "type": "object",
      "additionalProperties": {
        "$ref": "#/$defs/propertyDefinition"
      }
    }
  },
  "additionalProperties": false,
  "$defs": {
    "identifierList": {
      "type": "array",
      "minItems": 1,
      "uniqueItems": true,
      "items": { "type": "string", "minLength": 1 }
    },
    "propertyDefinition": {
      "type": "object",
      "required": [ "type" ],
      "properties": {
        "type": {
          "type": "string",
          "enum": [
            "number",
            "string",
            "text",
            "instant",
            "duration",
            "array"
          ]
        },
        "required": {
          "type": "boolean"
        },
        "timeline": {
          "$ref": "#/$defs/timelineSelector"
        },
        "items": {
          "description": "通常のPropSchemaとして検証するが、Property値へはtypeがarrayの場合だけ適用する",
          "oneOf": [
            { "$ref": "#/$defs/propertyDefinition" },
            { "$ref": "#/$defs/propertyType" }
          ]
        }
      },
      "additionalProperties": false
    },
    "propertyType": {
      "enum": [ "number", "string", "text", "instant", "duration", "array" ]
    },
    "timelineSelector": {
      "oneOf": [
        { "type": "string", "minLength": 1 },
        {
          "type": "array",
          "minItems": 1,
          "items": { "type": "string", "minLength": 1 },
          "uniqueItems": true
        }
      ]
    }
  }
}
```

extendsで継承することができ、サブタイプはスーパータイプの全てのプロパティを持つ。サブタイプがスーパータイプの型を上書きすることはできない。extendsは推移的である。

複数のスーパータイプが同名Propertyを定義し、その型に互換性がない場合、実装は警告を出す。timeline selectorも同じProperty Schema制約として扱い、指定したTimelineが同じAxisに属する場合は互換とする。型やtimeline selectorなどの通常の定義はextendsに先に記載されたスーパータイプを優先し、requiredはすべての定義の論理積（AND）として扱う。

NodeTypeは型定義であり、validTimeを指定してはならない。validTimeはNodeとそのPropertyに指定する。

Property Schemaのtimeline selectorは指定Timelineの個体ではなくAxisを制約する。`sameAxisAs`で追加した別表記も許容するが、`mapsTo`だけで接続された別Axisは許容しない。

#### RelType

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://example.com/schemas/rel-type-definition.schema.json",
  "type": "object",
  "required": [
    "id",
    "kind"
  ],
  "properties": {
    "id": {
      "type": "string",
      "minLength": 1,
      "pattern": "^\\S+$"
    },
    "kind": {
      "const": "RelType"
    },
    "extends": {
      "type": "array",
      "minItems": 1,
      "uniqueItems": true,
      "items": { "type": "string", "minLength": 1 }
    },
    "from": {
      "$ref": "#/$defs/nodeTypeRefList"
    },
    "to": {
      "$ref": "#/$defs/nodeTypeRefList"
    },
    "props": {
      "type": "object",
      "additionalProperties": {
        "$ref": "#/$defs/propertySchema"
      }
    }
  },
  "additionalProperties": false,
  "$defs": {
    "propertySchema": {
      "type": "object",
      "required": [ "type" ],
      "properties": {
        "type": {
          "enum": [ "number", "string", "text", "instant", "duration", "array" ]
        },
        "required": { "type": "boolean" },
        "timeline": { "$ref": "#/$defs/timelineSelector" },
        "items": {
          "description": "通常のPropSchemaとして検証するが、Property値へはtypeがarrayの場合だけ適用する",
          "oneOf": [
            { "$ref": "#/$defs/propertySchema" },
            { "enum": [ "number", "string", "text", "instant", "duration", "array" ] }
          ]
        }
      },
      "additionalProperties": false
    },
    "timelineSelector": {
      "oneOf": [
        { "type": "string", "minLength": 1 },
        {
          "type": "array",
          "minItems": 1,
          "uniqueItems": true,
          "items": { "type": "string", "minLength": 1 }
        }
      ]
    },
    "nodeTypeRefList": {
      "type": "array",
      "minItems": 1,
      "uniqueItems": true,
      "items": {
        "type": "string",
        "minLength": 1
      }
    }
  }
}
```

fromとtoでリンク先/リンク元のNodeTypeを制約することができる(サブタイプは許容)

RelTypeは型定義であり、validTimeを指定してはならない。validTimeはLinkとそのPropertyに指定する。

RelTypeの継承規則はNodeTypeと同じである。サブタイプはスーパータイプの全てのPropertyを持ち、スーパータイプのProperty型を上書きしてはならない。fromとtoの制約も継承する。子がfromまたはtoを指定した場合は、親の制約との論理積（AND）、すなわち許容NodeTypeの積集合として扱う。積集合が空の場合、実装は警告を出すが、RelTypeの定義は継続して扱う。extendsは推移的である。

#### Media

Nodeはurlを持つことができる。kindがMediaの場合はurlが必須であり、メディア実体へのリンクを表現する。それ以外は通常のNodeとして扱われる。

#### Link

RelTypeで定義されたリンク

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": [ "linkto", "type" ],
  "properties": {
    "linkto": {
      "type": "string",
      "minLength": 1
    },
    "type": {
      "type": "string",
      "minLength": 1,
      "pattern": "^\\S+$"
    },
    "validTime": {
      "type": "array",
      "minItems": 1,
      "items": { "$ref": "#/$defs/validTime" }
    },
    "props": {
      "type": "object",
      "additionalProperties": true
    }
  },
  "additionalProperties": false,
  "$defs": {
    "propEntries": {
      "type": "array",
      "items": { "$ref": "#/$defs/propEntry" },
      "contains": {
        "type": "object",
        "not": { "required": [ "validTime" ] }
      },
      "minContains": 0,
      "maxContains": 1
    },
    "propEntry": {
      "type": "object",
      "description": "Propertyまたは配列要素の値と、そのvalidTimeを表すエントリ",
      "required": [ "value" ],
      "properties": {
        "value": true,
        "validTime": {
          "type": "array",
          "minItems": 1,
          "items": { "$ref": "#/$defs/validTime" }
        }
      },
      "additionalProperties": false
    },
    "validTime": {
      "type": "object",
      "required": [ "timeline" ],
      "properties": {
        "timeline": { "type": "string", "minLength": 1 },
        "from": { "$ref": "#/$defs/temporalCoordinate" },
        "to": { "$ref": "#/$defs/temporalCoordinate" }
      },
      "additionalProperties": false
    },
    "temporalCoordinate": {
      "oneOf": [
        { "type": "number" },
        { "type": "string", "minLength": 1 }
      ]
    }
  }
}
```

### 時間モデル

利用者はMarkdown上で`kind: Timeline`の文書だけを定義する。TemporalDomain、TemporalAxis、TemporalCoordinateSystem、AxisLineage、Mappingは個別のMarkdown文書として定義せず、コンパイラがTimeline文書から正規化モデルとして生成する。

#### Timeline文書

Timelineのトップレベルで使用できるフィールドは次のものに限る。

| フィールド | 必須 | 意味 |
| --- | --- | --- |
| `id` | 必須 | Timelineを参照する一意なID |
| `kind` | 必須 | 常に`Timeline` |
| `sameAxisAs` | 任意 | 別Timelineと同じDomain・Axisを使用する |
| `scale`、`offset` | 任意 | `sameAxisAs`間の数値座標変換 |
| `coordinate` | 任意 | 利用者が記述する座標表現 |
| `domain` | 任意 | Domainの明示的な上書き |
| `derivedFrom` | 任意 | Axisの由来をLineageとして記録する |
| `mapsTo` | 任意 | 異なるAxis間の明示的な変換を定義する |
| `aliases` | 任意 | 表示・検索用の別名 |
| `props` | 任意 | Timeline自体のメタデータ |

最小のTimelineは`id`と`kind`だけでよい。

```yaml
---
id: Story
kind: Timeline
---
```

設定のないTimelineは、そのID専用のDomainとAxisを生成し、`number`座標を使用する。他の独立Timelineとは自動的に比較・変換されない。`sameAxisAs`と`derivedFrom`は同時指定できない。

`aliases`は表示とテキスト検索のためのmetadataである。ID参照の解決、期間付き名称解決、Axis共有、変換経路には使用しない。

#### 正規化モデルと時間数値

コンパイラは各Timeline文書を完全なTemporalCoordinateSystemへ正規化する。正規化後のTimelineは少なくともDomain ID、Axis ID、Axis上の単位、CoordinateSystem、任意のLineage、およびそのTimelineをsourceとするMappingを持つ。

時間座標の整数、小数、`a/b`形式は`ExactRational`へ変換し、常に正の分母を持つ既約分数として保持する。たとえば`1.5`は`3/2`、`30/90`は`1/3`になる。通常のProperty `number`は従来どおりDoubleであり、ExactRationalを使用するのは時間座標だけである。JSONへ出力するときは`{"numerator": 1, "denominator": 3}`の形を使用する。

利用者が記述した値は、まずTimelineのCoordinateSystemで解釈し、Axisの標準座標へ正規化する。比較、Mapping、検索はこの標準座標に対して行い、結果を表示するときに対象Timelineの座標表現へ戻す。

#### 同じAxisの別表記

`sameAxisAs`は参照先と同じDomainおよびAxisを使用しつつ、別の座標表現を提供する。数値座標では参照先の値を`x`、現在のTimelineの値を`y`として、次の変換を使用する。

```text
y = x * scale + offset
```

`scale`の既定値は1、`offset`の既定値は0である。`scale`は0にできず、`scale`と`offset`は`sameAxisAs`を指定した`number`座標でのみ使用できる。`coordinate`、`scale`、`offset`をすべて省略した場合は、参照先と同じ座標表現を持つAlias相当のTimelineになる。

```yaml
---
id: ProjectEra
kind: Timeline
sameAxisAs: Story
offset: 1000
---
```

`sameAxisAs`の参照は循環してはならない。明示した`domain`が参照先のDomainと異なる場合、またはCoordinateが参照先Axisの単位と互換でない場合はSchemaErrorとする。同じAxisに属するTimelineはvalidTime検索とProperty Schemaのtimeline制約で同一の時間軸として扱う。

#### CoordinateSystem

`coordinate`を省略した独立Timelineは`number`になる。一般的なCoordinateSystemは次のpresetで指定できる。

| preset | 正規化されるCoordinateSystem | Axis単位 |
| --- | --- | --- |
| `number` | 既約な有理数 | tick |
| `gregorian` | Gregorian暦、common-era年番号 | day |
| `julian` | Julian暦、common-era年番号 | day |
| `frame` | 0始まりのFrameIndex | frame |

詳細設定が必要な場合はobject形式を使用する。

`calendar`は`calendar`に`gregorian`または`julian`を指定する。`numbering`は`common-era`、`astronomical`、または`offset`を使用できる。`offset`では表示年から差し引く値と、0年を許可するかを指定する。

```yaml
coordinate:
  kind: calendar
  calendar: gregorian
  numbering:
    kind: offset
    offset: 660
    yearZero: false
```

calendar値は`YYYY-MM-DD`形式で記述し、存在しない月日や、0年を持たないnumberingでの0年はエラーとする。GregorianとJulianは`sameAxisAs`を使い、同じday Axis上の異なる表記として使用できる。

`frame`は整数のFrameIndexを表し、`start`で表示上の開始番号を変更できる。

```yaml
coordinate:
  kind: frame
  start: 1
```

`timecode`はSMPTE形式を表す。`actualFps`は有理数、`nominalFps`は正の整数である。`dropFrame: true`はnominal FPSが30または60の場合だけ使用できる。`wrapHours`を指定すると表示時の時をその値で折り返す。

```yaml
coordinate:
  kind: timecode
  actualFps: 30000/1001
  nominalFps: 30
  dropFrame: true
  wrapHours: 24
```

timecode値は`HH:MM:SS:FF`または`HH:MM:SS;FF`形式で記述する。frame番号は0以上かつ`nominalFps`未満でなければならず、drop-frameでスキップされるラベルは使用できない。YAMLや本文でコロンを含む値を書く場合は文字列としてquoteする。

`era`は期間名をcalendar上の日付へ変換するCoordinateSystemであり、calendar Timelineを`sameAxisAs`で指定しなければならない。各periodは`name`、開始日`since`を必須とし、任意の`aliases`と`firstYear`を持つ。期間の開始前または次のperiodの開始以後の日付を、そのperiodの値として使用してはならない。

```yaml
---
id: JapaneseEra
kind: Timeline
sameAxisAs: CommonEra
coordinate:
  kind: era
  periods:
    - name: Reiwa
      aliases: [令和, R]
      since: 2019-05-01
      firstYear: 1
---
```

Era値は`Reiwa 1-05-01`や`令和 1-05-01`のように、period名またはそのalias、年、月、日を組み合わせて記述する。

標準機能の範囲はGregorian/Julian、common-era/astronomical/offset年番号、Era、FrameIndex、SMPTE drop/non-dropとする。タイムゾーン、UTC/TAI、うるう秒、不確実な歴史改暦、汎用変換式DSLは扱わない。

#### DomainとLineage

Domainは複数のAxisが属する世界・記録集合を表し、Axisはその中で順序付け可能な時間軸を表す。通常はDomainを記述する必要はない。コンパイラは次の規則でDomainとAxisを決定する。

| Timelineの形 | Domain | Axis |
| --- | --- | --- |
| 独立Timeline | Timeline専用の新Domain | 新Axis |
| `sameAxisAs` | 参照先Domain | 参照先Axis |
| `derivedFrom`の`fork`、`simulation` | 既定では新Domain | 新Axis |
| `derivedFrom`の`recording`、`edit`、`resample`、`copy` | 既定では参照先Domain | 新Axis |
| `domain`を明示（`sameAxisAs`以外） | 指定Domain | 上記規則によるAxis |

単純な由来は`derivedFrom: Reality`と書ける。この場合のkindは`derived`である。由来の種類や接続位置が必要な場合はobject形式を使用する。

```yaml
derivedFrom:
  timeline: Reality
  kind: fork
  sourceAt: 2026-01-01
  origin: 0
```

`derivedFrom`のkindは`fork`、`simulation`、`recording`、`edit`、`resample`、`copy`、`derived`のいずれかである。`sourceAt`は参照元の分岐・取得位置、`origin`は派生Axis側の原点を記録する。これらはLineage metadataであり、それ自体からMappingを生成しない。したがって、由来が分かっていても`mapsTo`がなければ異なるAxis間を比較・変換できない。

#### Mapping

`mapsTo`は現在のTimelineのAxisをsource、指定したTimelineのAxisをtargetとする、明示的な変換グラフを作る。完全なexact identity mappingはTimeline IDだけで記述できる。

```yaml
mapsTo: Reality
```

複数または詳細なMappingではobjectまたはlist形式を使用する。

| フィールド | 意味 |
| --- | --- |
| `id` | 任意のMapping ID。省略時はsource、target、記述順から生成する |
| `timeline` | target Timeline ID |
| `kind` | `coercion`、`isomorphism`、`embedding`、`projection`、`alignment`、`correspondence` |
| `precision` | `exact`、`approximate`、`uncertain`。誤差を持つ場合はobjectの`error`で指定する |
| `scale`、`offset` | `target = source * scale + offset`となる線形変換 |
| `range` | Mappingが定義されるsource範囲 |
| `segments` | 区分ごとのsource/target対応 |
| `pairs` | 離散的な1対1、1対多、多対多対応 |
| `traits` | 推論されたMapping性質を弱める明示的な上書き |
| `requiredContext` | Mappingの適用に必要なcontextキー |
| `provenance` | Mappingの出典metadata |

```yaml
mapsTo:
  - timeline: Reality
    kind: alignment
    precision: exact
    scale: 1/30
    offset: 100
    range: { from: 0, to: 3000 }
```

`segments`はsource範囲とtarget範囲から区分ごとの線形変換を推論する。source範囲の重複はエラー、gapは警告とする。targetの開始値が終了値より大きいsegmentは逆順編集を表現できる。

```yaml
mapsTo:
  - timeline: SourceVideo
    kind: correspondence
    segments:
      - source: { from: 0, to: 100 }
        target: { from: 500, to: 600 }
      - source: { from: 101, to: 200 }
        target: { from: 900, to: 800 }
```

`pairs`は離散的な対応を表す。`to`をlistにすると1つのsourceに複数のtargetを対応させられる。

```yaml
mapsTo:
  - timeline: EventLog
    kind: correspondence
    pairs:
      - from: 10
        to: [20, 21]
```

top-levelの`pairs`は`range`または`segments`と併用できない。segment内の`pairs`は、そのsegmentの`source`または`target`範囲と併用できない。`requiredContext`に指定したキーが変換時のcontextに存在しない場合、そのMappingは適用できない。

コンパイラはrule、range、segments、pairsから次の性質を推論する。

| trait | 値 |
| --- | --- |
| cardinality | `one-to-one`、`one-to-many`、`many-to-one`、`many-to-many` |
| totality | `total`、`partial` |
| order | `strictly-increasing`、`strictly-decreasing`、`monotonic`、`non-monotonic` |
| invertibility | `invertible`、`conditionally-invertible`、`non-invertible` |
| continuity | `continuous`、`piecewise`、`discrete` |

`traits`で上書きできるのは、推論結果を保守的に弱める場合だけである。たとえば1対多のMappingを`one-to-one`または`invertible`と宣言することはできない。

`precision: exact`は誤差のない変換を表す。`approximate`は`precision: { kind: approximate, error: 1/10 }`のように非負の`error`を必須とする。`uncertain`は単一値に確定できない変換を表し、`error`があれば下限と上限を持つRangeとして返せる。

Mapping経路は精度、情報損失、hop数の順に評価する。同順位の経路が異なる結果を返す場合は、単一の結果を選ばずambiguousまたはalternativesとして返す。Type、Lineage、AliasはMapping経路として使用しない。

#### 変換、比較、検索

`TemporalEngine.convert`の結果は次のいずれかである。

| 結果 | 意味 |
| --- | --- |
| `Exact` | 一意で誤差のない値 |
| `Alternatives` | 同順位の経路または1対多対応による複数候補 |
| `Range` | 不確実性による下限・上限 |
| `Approximate` | 代表値と任意の誤差 |
| `Unmappable` | 経路、範囲、context、または座標表現の制約により変換できない |

`TemporalEngine.compare`は、同一AxisまたはMappingで変換可能な値を比較する。確定した順序は`Ordered(Before|Equal|After)`、不確実な範囲と交差する場合は`Overlapping`、候補によって順序が変わる場合は`Ambiguous`、近似結果は`Approximate`、Mappingはあるが適用できない場合は`Unmappable`を返す。Lineageだけが存在しMappingがないAxis同士は`Unrelated`になる。

通常のvalidTime検索では、同じAxisに属するTimelineを同じ主張スコープとして扱う。異なるAxisへ検索範囲を展開するのは、結果が一意で、precisionがexact、contextが不要、かつ順序保存であるMapping経路だけである。approximate、uncertain、ambiguous、non-monotonicなMappingは変換APIでは利用できるが、通常検索の一致判定には使わない。

Property Schemaの`timeline`は特定のTimeline個体ではなく、そのTimelineが属するAxisを制約する。`sameAxisAs`で追加した別表記は許容するが、`mapsTo`でのみ接続された別Axisは許容しない。

#### 時間値

`validTime`はTimeline IDと任意の`from`、`to`を持つ。境界値は冗長なobjectで包まず、TimelineのCoordinateSystemで解釈できる数値、日付、Era、またはquoted timecodeを直接記述する。

```yaml
validTime:
  - timeline: Story
    from: 10
    to: 20
  - timeline: CommonEra
    from: 2020-01-01
    to: 2026-12-31
```

`from`だけを持つ期間はその値以後、`to`だけを持つ期間はその値以前、両方を省略した期間はそのTimeline全体を表す。instantは`timeline + value`、durationは`timeline + from/to`として正規化する。本文では`validTime=Story(from=1/30,to=2/30)`、`validTime=CommonEra(from="2020-01-01")`、`validTime=Video(from="01:00:00;00")`のように記述する。

#### 廃止された構文

旧Timelineフィールドの`extends`、`timecode`、`mappings`と、Property Schemaの`mapped: true`は廃止する。コンパイラはそれぞれ`sameAxisAs`または`derivedFrom`、`coordinate`、`mapsTo`への置換を示すSchemaErrorを返す。本節の規則を新しい正規形とする。

### Markdown拡張記法

Node/NodeType/RelType/Timeline/Mediaは一つのMarkdown文章として表現される。
LinkはMarkdown文章中に拡張記法として表現される。

MarkdownのYAML frontmatterとしてNode/NodeType/RelType/Timeline/Mediaを表現することができる。
NodeおよびMediaに関しては、拡張記法で自然言語の文章中にPropertyを記述することができる。
自然言語で記述されたPropertyは処理時にYAMLでの記述とマージされる。

本文中の`@props`および`@link`がグラフのPropertyまたはLinkとして意味を持つのはNodeおよびMediaだけである。NodeType、RelType、Timelineの本文に同じ文字列を記述してもエラーにはしないが、GraphMD拡張記法として抽出または意味解釈せず、通常のMarkdown本文として扱う。

#### {}部

データ構造を記述する本体 各記法のProperty指定で使われる

`{`とkeyと`=`とvalueと`,`と`}`で構成されている。複数のkey-valueは`,`で区切らなければならず、空白および改行だけを区切りとして使用してはならない。最後のvalueの後ろには末尾の`,`を記述できる。

keyは()を使ってtextのキーやvalidTimeを指定することができる。textの`key`と`validTime`は同時に指定できる。

validTimeは`validTime=...`として指定する。Timelineと期間を指定する場合は`validTime=CommonEra(from=1234,to=12345)`のように記述する。`timeline`、`from`、`to`を単独の引数として指定してはならない。

`{}`部におけるProperty宣言の重複は、記述順ではなく、Property名、textの`key`、および個々のvalidTimeの組で判定する。validTimeの各オブジェクトはフィールドの記述順を無視し、`timeline`、`from`、`to`の値が同じであれば同一とみなす。
validTimeが配列の場合は個々のvalidTimeへ分解して判定し、既に宣言されたvalidTimeの集合に同一の要素が一つでも含まれていれば重複とする。同じ宣言のvalidTime配列内に同一要素が複数ある場合も重複とする。validTimeを省略した宣言同士は、Property名とtextの`key`が同じ場合に重複とする。
この重複判定は一つの`{}`部内の宣言に適用する。YAMLのPropertyと本文中のPropertyをマージするときは、前述のマージ規則に従い、同じ主張を本文中の値で上書きする。

例えば`{name(key="lang:ja",validTime=CommonEra)="アリス"}`は有効である。一方、`{name(key="lang:ja",validTime=[TimelineA,TimelineB])="アリス",name(key="lang:ja",validTime=TimelineB)="Alice"}`は、分解後の`TimelineB`が同じkeyのvalidTime集合に既に含まれるため重複エラーとする。

valueには文字列、数値、真偽値、`null`、配列、およびネストした`{}`オブジェクトを記述できる。
valueの内容がidの場合、ダブルコーテーションは省略して記述できる。

##### スカラー

```
{string="This is string",number=123456}
```

以下として解釈される

```json
{
  "string": "This is string",
  "number": 123456
}
```

##### text

```
{text={lang:ja="テキスト",lang:us="This is text"},text2(key="lang:ja")="テキスト2",text2(key="lang:us")="This is text2"}
```

以下として解釈される

```json
{
  "text": {
    "lang:ja": "テキスト",
    "lang:us": "This is text"
  },
  "text2": {
    "lang:ja": "テキスト2",
    "lang:us": "This is text2"
  }
}
```

##### instant

instantは座標値だけならスカラーで、Timelineを明示する場合は`timeline + value`で記述する。`value`はTimelineのcoordinateに従って数値、日付、Era、またはタイムコードとして解釈される。

```text
{storyAt=1/30,publishedAt={timeline=CommonEra,value="2026-07-11"},videoAt={timeline=Video,value="01:00:00;00"}}
```

##### duration

durationは`timeline + from/to`で記述する。`from`または`to`の少なくとも一方が必要で、両境界はTimelineのcoordinateに従って直接解釈される。

```text
{storyRange={timeline=Story,from=1/30,to=2/30},releaseWindow={timeline=CommonEra,from="2026-07-11",to="2026-07-12"}}
```

instantとdurationの正規化モデルでは、Timeline IDと解析済みの`TemporalCoordinate`を保持する。通常のProperty `number`はDoubleのままだが、時間座標の数値だけは`ExactRational`になる。
```
##### 配列

```
{array=[1,2,3,4,"text",{string="a",object={number=1,array=[1,2,3,4],instant=1}}]}
```

型が事前に判明している場合以下として解釈される

```json
{
  "array": [
    1,
    2,
    3,
    4,
    "text",
    {
      "string": "a",
      "object": {
        "number": 1,
        "array": [
          1,
          2,
          3,
          4
        ],
        "instant": 1
      }
    }
  ]
}
```

#### @props拡張記法

NodeおよびMediaで使える。YAMLで記述されたpropsをマージする。完全に同じvalidTimeで存在する場合は上書きする。

自然言語の文中でPropertyを記述することができる。
各PropertyはvalidTimeで主張するTimelineと期間を表現できる。

```markdown

# Alice

Aliceの名前は@props{name = "Alice"}です。
```

このMarkdownは下記のようにレンダリングされることを期待する(実装依存)

```html
<h1 id="alice">Alice</h1>
<p>Aliceの名前は<span
        data-props-bind="{&quot;name&quot;:&quot;Alice&quot;}"><span
        data-props-name="name"><span class="graphmd-prop-value">Alice</span><span
        class="graphmd-prop-annotations"><sub
        class="graphmd-prop-name">name</sub></span></span></span></p>
```

`@props`はメタデータの宣言だけではなく、文中へbindしたProperty名、値およびvalidTimeを出力する記法である。プレビューでは、`@props`に記述された各Propertyについて`data-props-name`を持つ要素を生成し、最初にPropertyの値を出力する。validTimeのTimeline IDは、そのvalidTimeが適用される値の直後へ上付き文字として出力し、Property名はすべての値の後へ下付き文字として出力する。validTimeがない場合は上付き文字を省略する。同じ値に複数のvalidTimeがある場合は記述順を保ち、重複を除いて`,`で結合する。値と注釈の間に空白や区切り文字を自動挿入しない。複数のPropertyを指定した場合は記述順にすべて出力する。文字列と数値はその文字列表現を出力する。textに`default`キーがある場合はその値を優先して表示する。`default`キーがないtextの表示方法は規定せず、実装に委ねる。その他の構造化された値はJSON表現を出力する。Property名、値およびTimeline IDを安全なテキストとしてエスケープし、HTMLとして解釈してはならない。外側の`data-props-bind`には、bindしたすべてのPropertyをJSONとして保持する。

例えば`年齢は@props(validTime = CommonEra){age = 25}歳`は、プレビュー上で「年齢は25<sup>CommonEra</sup><sub>age</sub>歳」と表示する。説明上のテキスト表記では`年齢は25^CommonEra^_age_歳`と表す。また、`@props{age=26,age(validTime=CommonEra)=25}`は`[26,25^CommonEra^]_age_`と表示し、`CommonEra`が25にだけ適用されることを明示する。

`validTime`引数では`validTime=CommonEra`と`validTime = CommonEra`の両方を許容する。`validTime`と`=`の間、および`=`と値の間には任意個の空白を記述できる。この空白規則は`@props`と`@link`のvalidTime引数の両方に適用する。

例えば次の記述では、プレビュー上に`Alice`、下付き文字の`name`、`20`、下付き文字の`age`をこの順序で出力する。

```markdown
@props{name="Alice",age=20}
```

```html
<span data-props-bind="{&quot;name&quot;:&quot;Alice&quot;,&quot;age&quot;:20}"><span data-props-name="name"><span class="graphmd-prop-value">Alice</span><span class="graphmd-prop-annotations"><sub class="graphmd-prop-name">name</sub></span></span><span data-props-name="age"><span class="graphmd-prop-value">20</span><span class="graphmd-prop-annotations"><sub class="graphmd-prop-name">age</sub></span></span></span>
```

##### @propsのvalidTimeの主張

各Propertyが主張するtimelineと期間の表現に以下の記法を使う

```markdown
@props{age(validTime=CommonEra) = 25,age(validTime=[TimelineA,TimelineC]) = 24,age(validTime=[TimelineB(from=1,to=2)]) = 23}
```

このMarkdownは下記のように解釈される

```json
{
  "props": {
    "age": [
      {
        "value": 25,
        "validTime": [
          {
            "timeline": "CommonEra"
          }
        ]
      },
      {
        "value": 24,
        "validTime": [
          {
            "timeline": "TimelineA"
          },
          {
            "timeline": "TimelineC"
          }
        ]
      },
      {
        "value": 23,
        "validTime": [
          {
            "timeline": "TimelineB",
            "from": 1,
            "to": 2
          }
        ]
      }
    ]
  }
}
```

##### @props全体のvalidTimeの主張

そのProperty自体のvalidTimeの指定のエイリアス

```markdown
@props(validTime=[CommonEra]){age=25,name="Alice"}
```
これは以下と同等

```json
{
  "props": {
    "age": [
      {
        "value": 25,
        "validTime": [
          {
            "timeline": "CommonEra"
          }
        ]
      }
    ],
    "name": [
      {
        "value": "Alice",
        "validTime": [
          {
            "timeline": "CommonEra"
          }
        ]
      }
    ]
  }
}
```

#### 名前付き本文ブロック

NodeおよびMediaの本文では、3個以上の連続したコロンをフェンスとして、名前付きブロックを記述できる。ブロック名は将来の拡張用メタデータであり、この仕様の時点ではグラフの意味論やレンダリング結果を変更しない。

```markdown
::: history validTime=CommonEra(from=0 ,to=1)
外側の本文
::::: spoiler annotation validTime=Branch
内側の本文
:::::
:::
```

開始フェンスのheaderには、ブロック名と`validTime`を空白区切りで任意順に複数記述できる。ブロック名は`[A-Za-z_][A-Za-z0-9_.:-]*`に一致しなければならない。名前の出現順と重複は保持し、未知の名前も正当なブロック名として受理する。`validTime`と`=`の前後には空白を記述できる。

`validTime`式の括弧、配列、オブジェクトおよび文字列の内部にある空白はheaderの区切りではない。したがって、`validTime=CommonEra(from=0 ,to=1) history`ではvalidTime式を最後まで解析した後に`history`をブロック名として読む。同じ開始行に複数の`validTime`がある場合はすべてを構文検証し、最後の指定を採用する。途中に不正な指定があれば、後続の指定が正常でも開始行全体を構文エラーとする。

フェンスには次の規則を適用する。

- 行頭には最大3文字の空白を許可する。コードブロック、リストおよび引用の接頭辞内ではフェンスとして認識しない。
- 入れ子の開始フェンスは親より多いコロンを持たなければならない。差は1でなくてもよく、例えば3個、5個、8個と入れ子にできる。
- 終了フェンスは対応する開始フェンスと同数のコロンだけを記述する。最内ブロックと異なる個数では閉じず、1行で複数階層を閉じることもできない。
- headerを持たないフェンスは終了フェンスとして扱う。未閉鎖、孤立した終了フェンス、個数が一致しない終了フェンス、および親以下の個数で開始する入れ子は構文エラーである。不完全な範囲にはブロックの時間意味論を適用しない。

`validTime`の既定値は、Node、外側ブロック、内側ブロック、`@props`または`@link`、個別Propertyの順に解決する。最も近い明示値が上位の値を置換し、複数の時間を結合または交差しない。`validTime`を持たない名前付きブロックは、親ブロックまたはNodeの値を継承する。

検索assertionでは、同じAxisに属するTimelineを同じ主張スコープとして扱う。異なるAxisを`mapsTo`で横断する通常検索は、一意・exact・順序保存のMappingだけを使う。approximate、ambiguous、non-monotonicなMappingは変換APIでは利用できるが、検索一致には使わない。

markdown-it実装は、構文的に完全な開始・終了フェンスだけを表示から除外し、wrapper要素を生成せず、内部のMarkdownを通常どおり描画する。不正または未閉鎖のフェンスは通常の本文として残す。

検索索引ではフェンス行を本文から除外し、その位置で本文断片を分割する。各本文断片の時間は最内ブロックから継承し、`VALID ON`を含む時間条件へ反映する。静的検索bundleはformat v4で時間座標を有理数として保持し、旧formatはunsupportedとして拒否する。

#### グラフ志向リンク

RelTypeで定義されたリンクで、Propertyを持つ 各PropertyはvalidTimeで主張するTimelineと期間を表現できる

```markdown
Aliceは@link(validTime=CommonEra){weight = 0.2}[Bob](bob friendOf)です
```

このMarkdownは下記のようにレンダリングされることを期待する(実装依存)

```html
<p>Aliceは<a href="/path/to/bob.html" data-link-rel="friendOf" data-link-props="{&quot;weight&quot;:0.2}">Bob</a>です
</p>
```

`@link`の`{Property}`部は任意であり、LinkにPropertyがない場合は省略できる。省略形は次のように記述する。

```markdown
Aliceは@link[Bob](bob friendOf)です
Aliceは@link(validTime=CommonEra)[Bob](bob friendOf)です
```

省略形も空のPropertyを持つ通常形`@link{}[Bob](bob friendOf)`と同じLinkとして解釈する。ただし、Link自身のvalidTimeは引き続き`@link(...)`に保持される。`@link`またはその引数の直後と`[`の間、および`{Property}`を記述する場合は`}`と`[`の間に空白を入れてはならない。
リンク先IDとRelType IDは水平方向の空白で区切る。RelType ID自体に空白を含めてはならず、引用符を使って空白を含むRelType IDを記述する形式も許可しない。

完全形は`@link(validTime){Property}[文字列](id RelType)`、Propertyを省略した形は`@link(validTime)[文字列](id RelType)`である。`validTime`引数も省略可能である。

RelTypeはダブルコーテーションで囲むことが可能それ以外はMarkdownのリンクと同様

Link自身のvalidTimeは`@link(validTime){...}`のように指定する。LinkのPropertyのvalidTimeは@propsと同様に指定できる。

```markdown
@link(validTime=CommonEra){weight=0.3}[Bob](bob "friendOf")
```

リンク先の`id`は、ワークスペース内のMarkdown文書のfrontmatterに記述された`id`を参照する。レンダラは`id`を定義するMarkdownファイルを解決し、そのファイルに対応する出力文書への相対URLを`href`へ設定しなければならない。ファイル名と`id`が一致することを前提としてはならない。相対URLはリンク元文書の位置を基準にし、出力時の拡張子やディレクトリ構造を反映する。対象の`id`を解決できない場合も表示文字列は出力するが、診断を報告し、存在しないパスを推測して`href`へ設定してはならない。対象の`id`が複数ファイルで定義されている場合は曖昧な参照として診断を報告する。

### マルチメディア対応

urlにより、マルチメディア対応する。urlの形式には制約を設けない。
kind: Mediaとして定義したMarkdownファイルは、urlで指定するメディア実体へのリンクを表現する。そのMarkdownファイルへのリンクは、メディア実体へのリンクと同じ扱いとする。

Mediaはurlをもち、そのメディアの文章表現あるいは説明等をMarkdownへと記載する。

## 実装

本リポジトリでの標準実装

### データモデル処理部分(コア)

Kotlin Multiplatformで実装する

### Markdown拡張記法パーサ

Kotlin Multiplatformで実装する

`@link`について、`{Property}`を持つ完全形と省略形の両方を同一のLinkモデルへ変換する。省略形のPropertyは空として扱い、後続する`[文字列](id RelType)`を欠落なく解析する。

### markdown-itプラグイン

TypeScriptで実装する
Markdown拡張記法パーサに依存する(Kotlin/JS)

`@props`でbindしたPropertyの値を本文へ出力し、`data-props-bind`および`data-props-name`を付与する。`@link`の`id`はワークスペースのID索引を使って解決し、定義元Markdownファイルに対応する出力文書へのリンクとしてレンダリングする。Property部を省略した`@link`も完全形と同様にレンダリングする。

### VSCode拡張(lsp)

LSP4Jで構築する
markdown-itプラグインなどでプレビューをGraphMD対応させる

LSPはワークスペース内のMarkdownファイルを走査し、frontmatterに現れるすべての`id`定義とID参照、およびNodeまたはMediaのMarkdown本文に現れるすべてのID参照を索引化する。NodeType、RelType、Timelineの本文は通常のMarkdown本文として扱い、GraphMDのID参照を索引化しない。ここでいうIDには、少なくともNode、Media、NodeType、RelType、Timelineの`id`、`type`、NodeType/RelTypeの`extends`、Timelineの`sameAxisAs`、`derivedFrom.timeline`、`mapsTo.timeline`、timeline selector、validTimeの`timeline`、instantおよびdurationの`timeline`、`@link`のリンク先`id`とRelType、名前付き本文ブロックで採用された最後の`validTime`、および拡張記法の値としてIDを取る箇所を含む。引用符の有無、配列内、ネストしたProperty内、`@link`のProperty部の省略有無によって索引対象から除外してはならない。名前付き本文ブロックのフェンス、ブロック名、`validTime`はsyntax highlightingの対象とする。

索引化したすべてのID定義およびID参照に対して、LSPは次の機能を提供しなければならない。

- 定義へ移動: 参照位置から、そのIDをfrontmatterで定義しているファイルと範囲へ移動する。Nodeのfrontmatterおよび`@props`のPropertyキーはNodeType、`@link`のPropertyキーはRelTypeの`props`宣言へ移動し、継承されたPropertyは最も近い宣言を返す。定義位置自身から実行した場合も、その定義位置を返す。
- 参照を検索: 定義と、Markdown本文およびfrontmatter内のすべての参照を返す。
- ホバー: IDのkind、定義ファイル、および利用可能な型情報を表示する。
- 補完: 文脈に適合するkindのIDを候補として提示する。例えば`@link`のリンク先にはNodeまたはMedia、RelType位置にはRelType、timeline位置にはTimelineを提示する。
- 名前変更: 定義と索引化されたすべての参照をワークスペース編集として更新する。表示文字列など、IDではない同名テキストは変更しない。
- 診断: 未解決ID、重複定義、期待するkindと異なるID、および複数候補により曖昧な参照を報告する。

ファイルの追加、削除、名前変更、保存および編集中の変更に応じて索引を更新する。未保存文書についてはエディタ上の内容を優先する。ID解決はファイル名ではなくfrontmatterの`id`を基準とし、同じIDが複数定義されている場合、定義へ移動は候補をすべて返す。

VSCode拡張はLSPの定義へ移動、参照を検索、ホバー、補完、名前変更および診断を標準のVSCode機能として公開する。またGraphMDプレビューへ同じワークスペースID索引を提供し、`@props`のbind値を本文に表示し、Property部を省略した`@link`を認識し、解決した`id`の定義元ファイルに対応するプレビュー文書へのリンクを生成する。プレビュー内でそのリンクを選択した場合は対象文書を開けなければならない。

## サンプル

### NodeType

```markdown
---
id: Person
kind: NodeType
props:
    name:
        type: text
        required: true
    age:
        type: number
        required: false
    birthDate:
        type: instant
        timeline: CommonEra
    arraySample:
        type: array
        items: number
    
---

# Person

Personの説明です
```

### Timeline

以下は「仕様 / 時間モデル」で定義した構文の代表的な組み合わせである。

最小の独立Timeline:

```yaml
---
id: Story
kind: Timeline
---
```

同じAxisを使う別表記:

```yaml
---
id: ProjectEra
kind: Timeline
sameAxisAs: Story
offset: 1000
---
```

Gregorian暦とEra表記:

```yaml
---
id: CommonEra
kind: Timeline
coordinate: gregorian
---
```

```yaml
---
id: JapaneseEra
kind: Timeline
sameAxisAs: CommonEra
coordinate:
  kind: era
  periods:
    - name: Reiwa
      aliases: [令和, R]
      since: 2019-05-01
      firstYear: 1
---
```

Mappingを持たないIF世界のLineage:

```yaml
---
id: IfWorld
kind: Timeline
derivedFrom:
  timeline: Reality
  kind: fork
  sourceAt: 2026-01-01
  origin: 0
---
```

部分Mappingを持つ録画Timeline:

```yaml
---
id: Recording
kind: Timeline
coordinate: frame
derivedFrom:
  timeline: Reality
  kind: recording
mapsTo:
  - timeline: Reality
    kind: alignment
    precision: exact
    scale: 1/30
    offset: 100
    range: { from: 0, to: 3000 }
---
```

直接境界値を使うvalidTime:

```yaml
validTime:
  - timeline: Story
    from: 10
    to: 20
  - timeline: CommonEra
    from: 2020-01-01
    to: 2026-12-31
```

### Node

```markdown
---
id: Alice
kind: Node
type: Person
props:
    arraySample:
      - 0
      - 1
      - value: 2
        validTime:
          - timeline: CommonEra
      - value: 3
        validTime:
          - timeline: CommonEra
          - timeline: TimelineB
            from: 1
            to: 2
validTime:
  - timeline: CommonEra
  - timeline: TimelineB
  - timeline: TimelineA
    from: 3
    to: 4
---

# Alice

名前は@props{name = alice}。
年齢は@props{age(validTime=CommonEra) = 20,age(validTime=TimelineA) = 21,age(validTime=TimelineB(from=1,to=2)) = 19}

@link(validTime=CommonEra){weight=0.9}[Bob](bob "friendOf")
```
