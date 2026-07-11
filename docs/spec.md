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

時点のtimecodeは数値（小数を含む）である。

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

利用可能なPropSchemaがないため、値の配列を「Property自体の時系列バリエーション」と「validTimeを持つarray要素」のどちらとして解釈するか判別できない場合、既定では前者として解釈する。実装はこの曖昧な解釈について警告を出すべきである。

validTimeが複数指定された場合、各validTimeはORとして扱う。fromとtoの端点はともに含む。fromまたはtoが指定されない場合、その方向の境界は確定していない。fromとtoの両方が指定され、比較可能であってfromがtoより後の場合、実装は警告を出すべきである。

### データモデルおよびモデルの継承

以下は概念であり、実際の構文ではない
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
      "type": "string"
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
      "additionalProperties": {
        "oneOf": [
          {
            "$ref": "#/$defs/propEntries"
          },
          {
            "$ref": "#/$defs/shorthandValue"
          }
        ]
      }
    }
  },
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
    "shorthandValue": {
      "not": { "$ref": "#/$defs/propEntries" }
    },
    "timecodeValue": {
      "type": "number"
    }
  }
}
```

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
        "index": {
          "$ref": "#/$defs/indexType"
        },
        "timeline": {
          "$ref": "#/$defs/timelineSelector"
        },
        "items": {
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
    },
    "indexType": {
      "enum": [
        "fulltext",
        "range"
      ]
    }
  }
}
```

extendsで継承することができ、サブタイプはスーパータイプの全てのプロパティを持つ。サブタイプがスーパータイプの型を上書きすることはできない。extendsは推移的である。

複数のスーパータイプが同名Propertyを定義し、その型に互換性がない場合、実装は警告を出す。timeline selectorも同じProperty Schema制約として扱い、指定したTimeline同士がextends関係にあり整合する場合は互換とする。型やtimeline selectorなどの通常の定義はextendsに先に記載されたスーパータイプを優先し、requiredはすべての定義の論理積（AND）として扱う。

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
      "minLength": 1
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
        "index": { "$ref": "#/$defs/indexType" },
        "timeline": { "$ref": "#/$defs/timelineSelector" },
        "items": {
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
    },
    "indexType": {
      "enum": [
        "fulltext",
        "range"
      ]
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
      "minLength": 1
    },
    "validTime": {
      "type": "array",
      "minItems": 1,
      "items": { "$ref": "#/$defs/validTime" }
    },
    "props": {
      "type": "object",
      "additionalProperties": {
        "oneOf": [
          { "$ref": "#/$defs/propEntries" },
          { "not": { "$ref": "#/$defs/propEntries" } }
        ]
      }
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
マッピングはoffsetで定義する。timecodeはnumber（小数を含む）である。

extendsした場合は暗黙的にmapping offset 0になる。extendsしていないTimeline同士もmappingできる。

timecodeは人間向けの記述では省略できる。機械的な比較およびmappingにはnumber timecodeが必要である。

Timelineのextendsおよびmappingは推移的な関係として扱う。

offset mappingは、mappedなTimeline間でtimecodeを変換するときに用いる。各mappingでは`from`または`to`のいずれか一方を指定し、もう一方はそのmappingを記述するTimeline自身である。`from: A`は`A`から現在のTimelineへの変換であり、offsetを加算する。`to: B`は現在のTimelineから`B`への変換であり、offsetを加算する。逆方向の変換ではoffsetを減算する。したがって、`from: A`でoffsetが`d`のmappingと、`to: A`でoffsetが`-d`のmappingは同じ関係を表す。推移的なmappingでは経路上のoffsetを合計する。同じTimelineへ複数経路で到達した結果の累積offsetが一致しない場合、または循環経路のoffset合計が0でない場合、実装は警告を出すべきである。

### Markdown拡張記法

Node/NodeType/RelType/Timeline/Mediaは一つのMarkdown文章として表現される。
LinkはMarkdown文章中に拡張記法として表現される。

MarkdownのYAML frontmatterとしてNode/NodeType/RelType/Timeline/Mediaを表現することができる。
ただし、Nodeに関しては拡張記法で自然言語の文章中にPropertyを記述することができる。
自然言語で記述されたPropertyは処理時にYAMLでの記述とマージされる。

#### {}部

データ構造を記述する本体 各記法のProperty指定で使われる

`{`とkeyと`=`とvalueと`,`と`}`で構成されている

keyは()を使ってtextのキーやvalidTimeを指定することができる

validTimeは`validTime=...`として指定する。Timelineと期間を指定する場合は`validTime=CommonEra(from=1234,to=12345)`のように記述する。`timeline`、`from`、`to`を単独の引数として指定してはならない。

valueの内容がidの場合、ダブルコーテーションは省略して記述できる

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

Nodeで使える。NodeのYAMLで記述されたpropsをマージする。完全に同じvalidTimeで存在する場合は上書きする。

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
        data-props-name="name">Alice</span></span></p>
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

`@`と`[`の間にスペースを開けることは許されないまた、`)`と`{`の間にスペースを開けることは許されない

@link(validTime){Property}[文字列](id RelType)になっている

RelTypeはダブルコーテーションで囲むことが可能それ以外はMarkdownのリンクと同様

Link自身のvalidTimeは`@link(validTime){...}`のように指定する。LinkのPropertyのvalidTimeは@propsと同様に指定できる。

```markdown
@link(validTime=CommonEra){weight=0.3}[Bob](bob "friendOf")
```

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

### markdown-itプラグイン

TypeScriptで実装する
Markdown拡張記法パーサに依存する(Kotlin/JS)

### VSCode拡張(lsp)

LSP4Jで構築する
markdown-itプラグインなどでプレビューをGraphMD対応させる

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
