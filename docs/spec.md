# グラフ志向リンクを持ったMarkdown拡張記法を活用したマルチメディア対応複数時系列ナレッジグラフ

本文書は以下の構成で記述されている。すべての実装の正本となる

1. コンセプト
2. 仕様
    1. データモデルおよびモデルの継承
    2. 時系列および時系列の継承
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
| Timeline | 時系列 継承関係をもてる                                               |
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

`enum`はPropSchemaの共通フィールドであり、空でないRawValueの配列で指定する。候補値は暗黙変換せずに比較するが、整数と小数は同じ数値として扱う。通常の型ではProperty値そのもの、`type: array`では各要素、`type: text`では各キーの値に適用する。`{value, validTime}`形式の要素またはtextメンバーでは`value`に対して適用し、`items.enum`は配列要素へ再帰的に適用する。候補に含まれない値は制約違反として診断するが、型が正しい値は正規化結果に保持する。

```yaml
props:
  status:
    type: string
    enum:
      - draft
      - published
```

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
          "$ref": "#/$defs/timePoint"
        },
        "to": {
          "$ref": "#/$defs/timePoint"
        }
      },
      "required": [
        "timeline"
      ],
      "additionalProperties": false
    },
    "timePoint": {
      "type": "object",
      "properties": {
        "value": {
          "type": "string"
        },
        "timecode": {
          "$ref": "#/$defs/timecodeValue"
        }
      },
      "required": [ "timecode" ],
      "additionalProperties": false
    },
    "timecodeValue": {
      "type": "number"
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
        "enum": {
          "type": "array",
          "minItems": 1,
          "uniqueItems": true
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
        { "$ref": "#/$defs/legacyTimelineDefinition" }
      ]
    },
    "legacyTimelineDefinition": {
      "type": "array",
      "minItems": 1,
      "items": {
        "oneOf": [
          { "$ref": "#/$defs/timelineRef" },
          {
            "type": "object",
            "minProperties": 1,
            "maxProperties": 1,
            "patternProperties": {
              "^.+$": {
                "type": "object",
                "properties": { "mapped": { "type": "boolean" } },
                "additionalProperties": false
              }
            },
            "additionalProperties": false
          }
        ]
      }
    },
    "timelineRef": {
      "type": "object",
      "required": [
        "id",
        "mapped"
      ],
      "properties": {
        "id": {
          "type": "string",
          "minLength": 1
        },
        "mapped": {
          "type": "boolean"
        }
      },
      "additionalProperties": false
    }
  }
}
```

extendsで継承することができ、サブタイプはスーパータイプの全てのプロパティを持つ。サブタイプがスーパータイプの型を上書きすることはできない。extendsは推移的である。

複数のスーパータイプが同名Propertyを定義し、その型に互換性がない場合、実装は警告を出す。timeline selectorも同じProperty Schema制約として扱い、指定したTimeline同士がextends関係にあり整合する場合は互換とする。型やtimeline selectorなどの通常の定義はextendsに先に記載されたスーパータイプを優先し、requiredはすべての定義の論理積（AND）として扱う。

`enum`を継承する場合、子の候補集合は親の候補集合の部分集合でなければならない。親が`enum`を持たない場合、子はenumを追加できる。複数の親の候補集合が互いに包含関係にない場合は、他の互換性のないProperty Schemaと同じく警告する。

NodeTypeは型定義であり、validTimeを指定してはならない。validTimeはNodeとそのPropertyに指定する。

Property Schemaのtimeline selectorで指定したTimelineは、そのTimelineおよびextendsによるすべてのサブタイプを許容する。selectorに`mapped: true`を指定した場合は、さらにoffset mappingでそのTimelineとmappedな関係にあるTimelineも許容する。

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
        "enum": {
          "type": "array",
          "minItems": 1,
          "uniqueItems": true
        },
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
        { "$ref": "#/$defs/timelineDefinition" }
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
    },
    "timelineDefinition": {
      "type": "array",
      "minItems": 1,
      "items": {
        "oneOf": [
          { "$ref": "#/$defs/timelineRef" },
          {
            "type": "object",
            "minProperties": 1,
            "maxProperties": 1,
            "patternProperties": {
              "^.+$": {
                "type": "object",
                "properties": { "mapped": { "type": "boolean" } },
                "additionalProperties": false
              }
            },
            "additionalProperties": false
          }
        ]
      }
    },
    "timelineRef": {
      "type": "object",
      "required": [
        "id",
        "mapped"
      ],
      "properties": {
        "id": {
          "type": "string",
          "minLength": 1
        },
        "mapped": {
          "type": "boolean"
        }
      },
      "additionalProperties": false
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
        "from": { "$ref": "#/$defs/timePoint" },
        "to": { "$ref": "#/$defs/timePoint" }
      },
      "additionalProperties": false
    },
    "timePoint": {
      "type": "object",
      "required": [ "timecode" ],
      "properties": {
        "value": {
          "type": "string"
        },
        "timecode": {
          "$ref": "#/$defs/timecodeValue"
        }
      },
      "additionalProperties": false
    },
    "timecodeValue": {
      "type": "number"
    }
  }
}
```

### 時系列および時系列の継承

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://example.com/schemas/timeline.schema.json",
  "title": "Timeline Definition",
  "type": "object",
  "additionalProperties": false,
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
      "const": "Timeline"
    },
    "extends": {
      "type": "array",
      "minItems": 1,
      "uniqueItems": true,
      "items": { "type": "string", "minLength": 1 }
    },
    "timecode": {
      "$ref": "#/$defs/timecodeDefinition"
    },
    "mappings": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/mapping"
      }
    },
    "props": {
      "type": "object",
      "additionalProperties": true,
      "properties": {
        "label": {
          "$ref": "#/$defs/localizedProperty"
        },
        "note": {
          "type": "string"
        }
      }
    }
  },
  "allOf": [
    {
      "if": {
        "properties": { "mappings": { "minItems": 1 } },
        "required": [ "mappings" ]
      },
      "then": { "required": [ "timecode" ] }
    }
  ],
  "$defs": {
    "timecodeDefinition": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "type"
      ],
      "properties": {
        "type": { "const": "number" }
      }
    },
    "mapping": { "$ref": "#/$defs/offsetMapping" },
    "offsetMapping": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "kind",
        "offset"
      ],
      "properties": {
        "from": {
          "type": "string",
          "minLength": 1
        },
        "to": {
          "type": "string",
          "minLength": 1
        },
        "kind": {
          "const": "offset"
        },
        "offset": {
          "type": "number"
        }
      },
      "oneOf": [
        {
          "required": [ "from" ],
          "not": { "required": [ "to" ] }
        },
        {
          "required": [ "to" ],
          "not": { "required": [ "from" ] }
        }
      ]
    },
    "localizedProperty": {
      "type": "object",
      "additionalProperties": {
        "type": "string"
      },
      "required": [
        "default"
      ],
      "properties": {
        "default": {
          "type": "string"
        }
      }
    }
  }
}
```

Timelineは継承することができ、またマッピングすることができる。
マッピングはoffsetで定義する。timecodeおよびoffsetは有限のnumber（小数を含む）である。

extendsした場合は暗黙的にmapping offset 0になる。extendsしていないTimeline同士もmappingできる。

timecodeは人間向けの記述では省略できる。機械的な比較およびmappingにはnumber timecodeが必要である。

Timelineのextendsおよびmappingは推移的な関係として扱う。

offset mappingは、同じ単位および進行率を持つmappedなTimeline間でtimecodeを変換するときに用いる。単位または進行率が異なりscale変換を必要とするTimeline同士は、offset mappingではmappedな関係として表現できない。
各mappingでは`from`または`to`のいずれか一方を指定し、もう一方はそのmappingを記述するTimeline自身である。`from: A`は`A`から現在のTimelineへの変換であり、offsetを加算する。`to: B`は現在のTimelineから`B`への変換であり、offsetを加算する。逆方向の変換ではoffsetを減算する。したがって、`from: A`でoffsetが`d`のmappingと、`to: A`でoffsetが`-d`のmappingは同じ関係を表す。推移的なmappingでは経路上のoffsetを合計する。
同じTimelineへ複数経路で到達した結果の累積offsetは、絶対差が`1e-9`以下なら一致とみなす。絶対差が`1e-9`を超える場合、または循環経路のoffset合計と0との絶対差が`1e-9`を超える場合、実装は警告を出すべきである。timecodeおよびoffsetには有限値だけを許すため、`NaN`および正負の無限大は比較対象にならない。

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

```
{createdAt=1,updatedAt={value="2026年7月11日",timecode=1783738829},nextUpdate={value="2026年7月12日",timecode=1783792829,timeline=CommonEra}}
```

事前に型が判明している場合以下として解釈される

```json
{
  "createdAt": {
    "timecode": 1
  },
  "updatedAt": {
    "value": "2026年7月11日",
    "timecode": 1783738829
  },
  "nextUpdate": {
    "value": "2026年7月12日",
    "timecode": 1783792829,
    "timeline": "CommonEra"
  }
}
```

型が判明してない場合以下として解釈され、型が判明した時点で修正される(実装依存)

```json
{
  "createdAt": 1,
  "updatedAt": {
    "value": "2026年7月11日",
    "timecode": 1783738829
  },
  "nextUpdate": {
    "value": "2026年7月12日",
    "timecode": 1783792829,
    "timeline": "CommonEra"
  }
}
```

##### duration

duration自体のtimelineと、fromとto自体のtimelineを指定することができる。同じtimelineでなくてもmappedな関係にある場合は指定可能。
durationには`from`または`to`の少なくとも一方が必要である。両方を省略した空のdurationはエラーとする。
durationおよびfrom/toのtimePointで使用できるフィールドは、durationでは`timeline`、`from`、`to`、timePointでは`timeline`、`value`、`timecode`だけである。`timeline`と`value`は文字列、`timecode`は有限の数値でなければならない。

```
{eventTime={from=1,to=2},eventTime2={from={value="今日",timecode=3},to={value="明日",timecode=4}}
,eventTime3={timeline=CommonEra,from=1,to=2},eventTime4={from={timeline=CommonEra,timecode=1},to={timeline=CommonEra2,timecode=54542}}}
```

```json
{
  "eventTime": {
    "from": {
      "timecode": 1
    },
    "to": {
      "timecode": 2
    }
  },
  "eventTime2": {
    "from": {
      "value": "今日",
      "timecode": 3
    },
    "to": {
      "value": "明日",
      "timecode": 4
    }
  },
  "eventTime3": {
    "timeline": "CommonEra",
    "from": {
      "timecode": 1
    },
    "to": {
      "timecode": 2
    }
  },
  "eventTime4": {
    "from": {
      "timeline": "CommonEra",
      "timecode": 1
    },
    "to": {
      "timeline": "CommonEra2",
      "timecode": 54542
    }
  }
}
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
        "instant": {
          "timecode": 1
        }
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
            "from": {
              "timecode": 1
            },
            "to": {
              "timecode": 2
            }
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

検索assertionでは、Timelineの`extends`で結ばれたTimelineを同じ主張スコープとして扱う。一方、offset mappingはtimecodeを比較可能な座標へ変換するだけであり、mappingだけで結ばれた別のTimelineへ主張を拡張してはならない。例えばNodeが`TimelineA`と`TimelineB`の両方で有効でも、mappingだけで接続された`validTime=TimelineB`の本文ブロック内にあるLinkは`VALID ON TimelineA`に一致しない。

markdown-it実装は、構文的に完全な開始・終了フェンスだけを表示から除外し、wrapper要素を生成せず、内部のMarkdownを通常どおり描画する。不正または未閉鎖のフェンスは通常の本文として残す。

検索索引ではフェンス行を本文から除外し、その位置で本文断片を分割する。各本文断片の時間は最内ブロックから継承し、`VALID ON`を含む時間条件へ反映する。既存の静的検索bundleの形式は変更しないが、新しいブロック時間を反映するには索引を再生成する必要がある。

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

LSPはワークスペース内のMarkdownファイルを走査し、frontmatterに現れるすべての`id`定義とID参照、およびNodeまたはMediaのMarkdown本文に現れるすべてのID参照を索引化する。NodeType、RelType、Timelineの本文は通常のMarkdown本文として扱い、GraphMDのID参照を索引化しない。ここでいうIDには、少なくともNode、Media、NodeType、RelType、Timelineの`id`、`type`、`extends`、timeline selector、validTimeの`timeline`、instantおよびdurationの`timeline`、`@link`のリンク先`id`とRelType、名前付き本文ブロックで採用された最後の`validTime`、および拡張記法の値としてIDを取る箇所を含む。引用符の有無、配列内、ネストしたProperty内、`@link`のProperty部の省略有無によって索引対象から除外してはならない。名前付き本文ブロックのフェンス、ブロック名、`validTime`はsyntax highlightingの対象とする。

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
        timeline:
            - CommonEra:
                mapped: true
    arraySample:
        type: array
        items: number
    
---

# Person

Personの説明です
```

### Timeline

```markdown
---
id: CommonEra
kind: Timeline
timecode:
  type: number
---

# Common Era
```

```markdown
---
id: ProjectEra
kind: Timeline
timecode:
  type: number
mappings:
  - from: CommonEra
    kind: offset
    offset: 1000
---

# Project Era
```

`CommonEra`のtimecodeを`ProjectEra`へ変換するときは、1000を加算する。

```markdown
---
id: CommonEraBranch
kind: Timeline
extends:
  - CommonEra
timecode:
  type: number
---

# Common Era Branch
```

`CommonEraBranch`は`CommonEra`をextendsするため、暗黙的にoffset 0のmappingを持つ。

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
            from:
                value: hoge
                timecode: 1
            to:
                value: fuga
                timecode: 2
validTime:
  - timeline: CommonEra
  - timeline: TimelineB
  - timeline: TimelineA
    from:
        value: hoo
        timecode: 3
    to:
        value: bar
        timecode: 4
---

# Alice

名前は@props{name = alice}。
年齢は@props{age(validTime=CommonEra) = 20,age(validTime=TimelineA) = 21,age(validTime=TimelineB(from=1,to=2)) = 19}

@link(validTime=CommonEra){weight=0.9}[Bob](bob "friendOf")
```
