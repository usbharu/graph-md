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

第一級オブジェクトとして時系列をサポートし、すべてのノード、プロパティ、リンク属性、リンク種別が時系列に所属している状態を表現できる。

## 仕様

### 用語の定義

| 名前       | 定義                                                         |
|----------|------------------------------------------------------------|
| Node     | 一つのMarkdown文章。ナレッジグラフで一つの単位 NodeTypeを持つ                    |
| NodeType | NodeType Nodeが持つべきPropertyを定義する 継承関係をもてる                   |
| Timeline | 時系列 継承関係をもてる                                               |
| Property | Node・Linkに属する属性  どの時系列のどの期間有効かを宣言できる                       |
| Link     | Node間の関係を表現する LinkTypeを持つ どの時系列のどの期間有効かを宣言できる Propertyをもてる |
| LinkType | Node間の関係を定義する Linkが持つべきPropertyを定義する 継承関係をもてる              |
| Media    | Markdown文章ではないNode Nodeのサブクラスと同等 メディアへのURLを持つ              |

### 型

Propertyで使える型

| 型        | 説明                                                        |
|----------|-----------------------------------------------------------|
| number   | 数値                                                        |
| string   | 文字列                                                       |
| text     | Map<string,any> 多言語化やエイリアスとして利用 Key-ValueだがKeyの役割は決まっていない |
| instant  | 特定のTimeline中の一点                                           |
| duration | 特定のTimeline中の期間                                           |
| array    | 配列                                                        |

全てのPropertyの値はvalidTimeを持つことができ、validTimeは主張するTimelineと期間を指定することができる。

stringはtextの省略形であり、textのキーを省略して書いた形がstringである

### データモデルおよびモデルの継承

以下は概念であり、実際の構文ではない
より上位の場所で指定されたvalidTimeは、下位の場所で指定されたvalidTimeに上書きされる

#### Node

```json
{
  "$schema": "http://json-schema.org/draft-07/schema",
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
        "Node"
      ]
    },
    "type": {
      "type": "string",
      "description": "NodeType"
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
          "type": "integer"
        }
      },
      "required": [
        "value",
        "timecode"
      ],
      "additionalProperties": false
    },
    "shorthandValue": {
      "not": {
        "type": "array"
      }
    }
  }
}
```

#### NodeType

```json
{
  "$schema": "http://json-schema.org/draft-07/schema",
  "$id": "https://graph-md.usbharu.dev/schema/node-type",
  "type": "object",
  "required": [
    "id",
    "kind",
    "props"
  ],
  "properties": {
    "id": {
      "type": "string",
      "minLength": 1
    },
    "kind": {
      "const": "NodeType"
    },
    "extends": {
      "type": "array",
      "items": {
        "type": "string",
        "minLength": 1
      },
      "minItems": 1,
      "uniqueItems": true
    },
    "props": {
      "type": "object",
      "additionalProperties": {
        "$ref": "#/$defs/propertyDefinition"
      }
    }
  },
  "additionalProperties": false,
  "$defs": {
    "propertyDefinition": {
      "oneOf": [
        {
          "$ref": "#/$defs/scalarProperty"
        },
        {
          "$ref": "#/$defs/arrayProperty"
        }
      ]
    },
    "scalarProperty": {
      "type": "object",
      "required": [
        "type"
      ],
      "properties": {
        "type": {
          "type": "string",
          "enum": [
            "text",
            "instant",
            "number"
          ]
        },
        "required": {
          "type": "boolean"
        },
        "index": {
          "$ref": "#/$defs/indexType"
        },
        "timeline": {
          "$ref": "#/$defs/timelineDefinition"
        }
      },
      "additionalProperties": false
    },
    "arrayProperty": {
      "type": "object",
      "required": [
        "type",
        "items"
      ],
      "properties": {
        "type": {
          "const": "array"
        },
        "items": {
          "$ref": "#/$defs/arrayItemDefinition"
        },
        "required": {
          "type": "boolean"
        },
        "index": {
          "$ref": "#/$defs/indexType"
        },
        "timeline": {
          "$ref": "#/$defs/timelineDefinition"
        }
      },
      "additionalProperties": false
    },
    "arrayItemDefinition": {
      "type": "object",
      "required": [
        "type"
      ],
      "properties": {
        "type": {
          "type": "string",
          "enum": [
            "text",
            "instant",
            "number"
          ]
        },
        "timeline": {
          "$ref": "#/$defs/timelineDefinition"
        }
      },
      "additionalProperties": false
    },
    "timelineDefinition": {
      "type": "array",
      "minItems": 1,
      "items": {
        "$ref": "#/$defs/timelineRef"
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

extendsで継承することができ、サブタイプはスーパータイプの全てのプロパティを持つ。サブタイプがスーパータイプの型を上書きすることはできない。

#### RelType

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://example.com/schemas/rel-type-definition.schema.json",
  "type": "object",
  "required": [
    "id",
    "kind",
    "from",
    "to",
    "props"
  ],
  "properties": {
    "id": {
      "type": "string",
      "minLength": 1
    },
    "kind": {
      "const": "RelType"
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
        "$ref": "#/$defs/propertyDefinition"
      }
    }
  },
  "additionalProperties": false,
  "$defs": {
    "nodeTypeRefList": {
      "type": "array",
      "minItems": 1,
      "uniqueItems": true,
      "items": {
        "type": "string",
        "minLength": 1
      }
    },
    "propertyDefinition": {
      "oneOf": [
        {
          "$ref": "#/$defs/scalarProperty"
        },
        {
          "$ref": "#/$defs/arrayProperty"
        }
      ]
    },
    "scalarProperty": {
      "type": "object",
      "required": [
        "type"
      ],
      "properties": {
        "type": {
          "enum": [
            "text",
            "instant",
            "number"
          ]
        },
        "required": {
          "type": "boolean"
        },
        "index": {
          "$ref": "#/$defs/indexType"
        },
        "timeline": {
          "$ref": "#/$defs/timelineDefinition"
        }
      },
      "additionalProperties": false
    },
    "arrayProperty": {
      "type": "object",
      "required": [
        "type",
        "items"
      ],
      "properties": {
        "type": {
          "const": "array"
        },
        "items": {
          "$ref": "#/$defs/arrayItemDefinition"
        },
        "required": {
          "type": "boolean"
        },
        "index": {
          "$ref": "#/$defs/indexType"
        },
        "timeline": {
          "$ref": "#/$defs/timelineDefinition"
        }
      },
      "additionalProperties": false
    },
    "arrayItemDefinition": {
      "type": "object",
      "required": [
        "type"
      ],
      "properties": {
        "type": {
          "enum": [
            "text",
            "instant",
            "number"
          ]
        },
        "timeline": {
          "$ref": "#/$defs/timelineDefinition"
        }
      },
      "additionalProperties": false
    },
    "timelineDefinition": {
      "type": "array",
      "minItems": 1,
      "items": {
        "$ref": "#/$defs/timelineRef"
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

#### Media

Nodeにurlが追加され、kindがMediaになる それ以外はNodeとして扱われる

#### Link

RelTypeで定義されたリンク

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": [
    "linkto",
    "props"
  ],
  "properties": {
    "linkto": {
      "type": "string"
    },
    "props": {
      "type": "object",
      "additionalProperties": {
        "type": "array",
        "items": {
          "type": "object",
          "required": [
            "value"
          ],
          "properties": {
            "value": {
              "type": "string"
            },
            "validTime": {
              "type": "array",
              "items": {
                "type": "object",
                "required": [
                  "timeline"
                ],
                "properties": {
                  "timeline": {
                    "type": "string",
                    "enum": [
                      "publication"
                    ]
                  },
                  "from": {
                    "$ref": "#/$defs/timePoint"
                  },
                  "to": {
                    "$ref": "#/$defs/timePoint"
                  }
                },
                "additionalProperties": false
              }
            }
          },
          "additionalProperties": false
        }
      }
    }
  },
  "additionalProperties": false,
  "$defs": {
    "timePoint": {
      "type": "object",
      "required": [
        "value",
        "timecode"
      ],
      "properties": {
        "value": {
          "type": "string"
        },
        "timecode": {
          "type": "integer"
        }
      },
      "additionalProperties": false
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
    "kind",
    "timecode",
    "mappings",
    "props"
  ],
  "properties": {
    "id": {
      "type": "string",
      "minLength": 1
    },
    "kind": {
      "const": "Timeline"
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
  "$defs": {
    "timecodeDefinition": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "type"
      ],
      "properties": {
        "type": {
          "enum": [
            "number",
            "tuple"
          ]
        }
      }
    },
    "mapping": {
      "oneOf": [
        {
          "$ref": "#/$defs/tableMapping"
        },
        {
          "$ref": "#/$defs/offsetMapping"
        }
      ]
    },
    "tableMapping": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "to",
        "kind",
        "entries"
      ],
      "properties": {
        "to": {
          "type": "string",
          "minLength": 1
        },
        "kind": {
          "const": "table"
        },
        "entries": {
          "type": "array",
          "minItems": 1,
          "items": {
            "$ref": "#/$defs/tableEntry"
          }
        }
      }
    },
    "offsetMapping": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "to",
        "kind",
        "offset"
      ],
      "properties": {
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
      }
    },
    "tableEntry": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "from",
        "to"
      ],
      "properties": {
        "from": {
          "$ref": "#/$defs/timePoint"
        },
        "to": {
          "$ref": "#/$defs/timePoint"
        }
      }
    },
    "timePoint": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "value",
        "timecode"
      ],
      "properties": {
        "value": {
          "type": "string"
        },
        "timecode": {
          "$ref": "#/$defs/timecodeValue"
        }
      }
    },
    "timecodeValue": {
      "oneOf": [
        {
          "type": "number"
        },
        {
          "type": "array",
          "minItems": 1,
          "items": {
            "type": "integer"
          }
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
マッピングは一対一で定義するtableとオフセットで定義するoffsetがあり、timecodeがintegerの場合のみoffsetを利用できる。

extendsした場合暗黙的にmapping offset 0になり、extendsしていないTimeline同士もmappingできる

機械的な比較ができるのはtimecodeがintegerで、offsetが指定されているmappingのみである。

### Markdown拡張記法

Node/NodeType/RelType/Timeline/Mediaは一つのMarkdown文章として表現される。
LinkはMarkdown文章中に拡張記法として表現される。

MarkdownのYAML frontmatterとしてNode/NodeType/Timeline/Mediaを表現することができる。
ただし、Nodeに関しては拡張記法で自然言語の文章中にPropertyを記述することができる。
自然言語で記述されたPropertyは処理時にYAMLでの記述とマージされる。

#### {}部

データ構造を記述する本体 各記法のProperty指定で使われる

`{`とkeyと`=`とvalueと`,`と`}`で構成されている

keyは()を使ってtextのキーやvalidTimeを指定することができる

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

Aliceの誕生日は@props{birthDate = {timeline = CommonEra, value = "AD 2005-04-01"}}です。
```

このMarkdownは下記のようにレンダリングされることを期待する(実装依存)

```html
<h1 id="alice">Alice</h1>
<p>Aliceの誕生日は<span
        data-props-bind="{&quot;birthDate&quot; : {&quot;timeline&quot;: &quot;CommonEra&quot;,&quot;value&quot;:&quot;AD 2005-04-01&quot;}}"><span
        data-props-name="birthDate.timeline">CommonEra</span> <span
        data-props-name="birthDate.value">AD 2005-04-01</span></span></p>
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
Aliceは@link{weight = 0.2}[Bob](bob friendOf)です
```

このMarkdownは下記のようにレンダリングされることを期待する(実装依存)

```html
<p>Aliceは<a href="/path/to/bob.html" data-link-rel="friendOf" data-link-props="{&quot;weight&quot;:0.2}">Bob</a>です
</p>
```

`@`と`[`の間にスペースを開けることは許されないまた、`)`と`{`の間にスペースを開けることは許されない

@link{Property}[文字列](id RelType)になっている

RelTypeはダブルコーテーションで囲むことが可能それ以外はMarkdownのリンクと同様

@propsと同様にvalidTimeを表すことが可能。

```markdown
@link(validTime=CommonEra)[Bob](bob "friendOf"){weight=0.3}
```

### マルチメディア対応

URL(http/https/ローカルファイル)により、マルチメディア対応する。
ただし、kind: Mediaとして定義したMarkdownファイルへのリンクをメディアへのリンクとする。

Mediaはurlをもち、そのメディアの文章表現あるいは説明等をMarkdownへと記載する。(
そのMarkdownへのリンクはメディアのリンクと同じ扱いとする)

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
      - 3:
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
年齢は@props{age(timeline=CommonEra) = 20,age(timeline=TimelineA) = 21,age(timeline=TimelineB,from=1,to=2) = 19}

@link(CommonEra){weight=0.9}[Bob](bob "friendOf")
```

