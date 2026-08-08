# Timelineガイド

GraphMDのTimelineは、「いつ有効な情報か」を表すための座標系です。最初から暦や変換を設計する必要はありません。まず最小のTimelineを作り、別表記や別の時間軸が必要になった時点で設定を足します。

このガイドでは、物語と映像を管理する例を段階的に組み立てます。フィールドごとの厳密な規則は[仕様書の時間モデル](spec.md#時間モデル)を参照してください。

## 最初に選ぶもの

Timeline同士の関係は、次の基準で選びます。

| やりたいこと | 使用するフィールド |
| --- | --- |
| 独立した時間軸を作る | 追加設定なし |
| 同じ瞬間を別の目盛りで表す | `sameAxisAs` |
| 分岐・録画・編集などの由来だけを残す | `derivedFrom` |
| 異なる時間軸の値を実際に変換する | `mapsTo` |

迷った場合は、設定なしのTimelineから始めます。GraphMDが他のTimelineを暗黙に関連付けることはありません。

## 1. 物語内の時間を作る

物語の開始を0とするTimelineは、次の1ファイルだけで定義できます。

`Story.md`:

```yaml
---
id: Story
kind: Timeline
---
```

これは内部的に、Story専用のDomain、Axis、`number` CoordinateSystemへ展開されます。座標には整数、小数、`1e-6`のような指数表記、分数を使用できます。

```yaml
validTime:
  - timeline: Story
    from: 10
    to: 20
```

このvalidTimeは「Storyの10から20まで」を表します。境界は両方とも含まれます。`from`だけならその値以後、`to`だけならその値以前、両方を省略すればStory全体です。

たとえば、場面を表すNodeは次のように記述できます。

`Scene.md`:

```yaml
---
id: Scene
kind: NodeType
props:
  title:
    type: string
    required: true
---
```

`Opening.md`:

```markdown
---
id: Opening
kind: Node
type: Scene
validTime:
  - timeline: Story
    from: 0
    to: 10
props:
  title: オープニング
---

# オープニング
```

CLIでは、この期間と重なる主張だけを取得できます。

```sh
graphmd list . --valid-time 'Story(from=5,to=5)'
```

Storyとは別に設定なしの`Production` Timelineを作っても、両者は独立しています。両方に値10があっても、同じ瞬間とはみなしません。

## 2. 同じAxisに別の目盛りを付ける

Storyの値に1000を加えた「プロジェクト内通算番号」も使いたいとします。この場合、新しい時間軸ではなく、同じAxisの別表記なので`sameAxisAs`を使います。

`ProjectEra.md`:

```yaml
---
id: ProjectEra
kind: Timeline
sameAxisAs: Story
offset: 1000
---
```

参照先Storyの値を`x`、ProjectEraの値を`y`とすると、変換は次の式です。

```text
y = x * scale + offset
```

この例では`scale`の既定値が1なので、`Story(20)`と`ProjectEra(1020)`は同じAxis上の同じ位置です。

```yaml
validTime:
  - timeline: ProjectEra
    from: 1010
    to: 1020
```

この主張はStoryの10から20に相当します。そのため、Storyを指定した通常検索でも一致します。

```sh
graphmd list . --valid-time 'Story(from=15,to=15)'
```

単なる別名が欲しいだけなら、`scale`、`offset`、`coordinate`を省略します。

```yaml
---
id: MainStory
kind: Timeline
sameAxisAs: Story
---
```

`sameAxisAs`は「同じ出来事を別の書き方で指す」場合にだけ使います。続編、IF世界、録画のように時間の進み方や存在範囲が別になるものには使いません。

## 3. 暦を使う

日付を直接書きたい場合は、presetを1行追加します。

`CommonEra.md`:

```yaml
---
id: CommonEra
kind: Timeline
coordinate: gregorian
---
```

NodeのvalidTimeには日付を直接記述できます。

```yaml
validTime:
  - timeline: CommonEra
    from: 2026-01-01
    to: 2026-12-31
```

本文の`@props`や`@link`ではquoteした日付も使用できます。

```markdown
@props{status(validTime=CommonEra(from="2026-01-01",to="2026-12-31"))="公開中"}
```

和暦はCommonEraと別の世界ではなく、同じ日を別の表記で指します。そのため`sameAxisAs`と`coordinate.kind: era`を組み合わせます。

`JapaneseEra.md`:

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

これにより、次の2つは同じ日として比較できます。

```text
CommonEra("2019-05-01")
JapaneseEra("令和 1-05-01")
```

皇紀のように年番号だけをずらす場合は、calendarの詳細形式を使用します。

```yaml
---
id: ImperialYear
kind: Timeline
sameAxisAs: CommonEra
coordinate:
  kind: calendar
  calendar: gregorian
  numbering:
    kind: offset
    offset: 660
    yearZero: false
---
```

## 4. 由来だけを記録する

現実から分岐したIF世界を表す場合、2つのTimelineは同じAxisではありません。ただし、どこから生まれたかは記録したいので`derivedFrom`を使います。

`Reality.md`:

```yaml
---
id: Reality
kind: Timeline
---
```

`IfWorld.md`:

```yaml
---
id: IfWorld
kind: Timeline
derivedFrom:
  timeline: Reality
  kind: fork
  sourceAt: 100
  origin: 0
---
```

これは「Realityの100を起点に、IfWorldの0が始まった」という由来を記録します。ただし、`derivedFrom`は変換規則ではありません。

```text
Reality(110) と IfWorld(10) は自動比較されない
```

分岐後の進み方が同じとは限らず、途中で停止や再分岐もあり得るためです。変換が本当に必要な範囲だけ、別途`mapsTo`で定義します。

## 5. 録画フレームを実時間へ対応させる

録画TimelineはRealityに由来しますが、座標はフレーム番号です。由来を`derivedFrom`、値の変換を`mapsTo`へ分けて記述します。

`Recording.md`:

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

Mappingでは現在のTimelineがsource、`timeline`で指定したTimelineがtargetです。この例の変換は次のようになります。

```text
Realityの値 = Recordingのフレーム / 30 + 100

Recording(0)   -> Reality(100)
Recording(300) -> Reality(110)
```

`range`の外側にはMappingがありません。たとえば`Recording(4000)`はRealityへ変換できず、`Unmappable`になります。録画停止や欠落を、存在しない対応としてそのまま表現できます。

30 fpsのタイムコード表示も欲しい場合は、Recordingと同じframe Axisの別表記を追加します。

`RecordingTimecode.md`:

```yaml
---
id: RecordingTimecode
kind: Timeline
sameAxisAs: Recording
coordinate:
  kind: timecode
  actualFps: 30
  nominalFps: 30
  dropFrame: false
---
```

タイムコードはコロンを含むため、YAMLとCLIではquoteします。

```yaml
validTime:
  - timeline: RecordingTimecode
    from: "00:00:10:00"
    to: "00:00:20:00"
```

## 6. 1対多の対応を表す

編集やイベント集約では、1つの値が複数の値に対応することがあります。この場合は`pairs`を使用します。

```yaml
---
id: Highlight
kind: Timeline
coordinate: frame
derivedFrom:
  timeline: Recording
  kind: edit
mapsTo:
  - timeline: Recording
    kind: correspondence
    pairs:
      - from: 10
        to: [300, 900]
      - from: 11
        to: 301
---
```

`Highlight(10)`の変換結果は単一値ではなく、`Recording(300)`と`Recording(900)`の`Alternatives`です。このような曖昧なMappingは変換APIでは取得できますが、通常検索の一致には使用されません。

同様に、`precision: approximate`、逆順を含むnon-monotonic mapping、変換にcontextが必要なMappingも通常検索には使われません。検索が暗黙に情報を推測しないための制限です。

## 7. PropertyのTimeline制約

NodeTypeのinstantやdurationには、使用できるTimelineを指定できます。

```yaml
---
id: Release
kind: NodeType
props:
  releasedAt:
    type: instant
    timeline: CommonEra
  availableDuring:
    type: duration
    timeline: CommonEra
---
```

値は`timeline + value`または`timeline + from/to`で記述します。

```yaml
props:
  releasedAt:
    timeline: JapaneseEra
    value: "令和 1-05-01"
  availableDuring:
    timeline: CommonEra
    from: 2026-01-01
    to: 2026-12-31
```

Schemaの`timeline: CommonEra`はCommonEraというIDだけでなく、そのAxisを制約します。そのため、`sameAxisAs: CommonEra`であるJapaneseEraも`releasedAt`に使用できます。`mapsTo`だけで接続された別Axisは使用できません。

## 8. CLIとGMQLで検索する

CLIの`--valid-time`はTimelineに応じて値を解釈します。

```sh
graphmd list . --valid-time 'Story(from=10,to=20)'
graphmd list . --valid-time 'CommonEra(from=2026-01-01,to=2026-12-31)'
graphmd list . --valid-time 'RecordingTimecode(from="00:00:10:00",to="00:00:20:00")'
```

GMQLでも同じ考え方で検索できます。

```gmql
MATCH (scene:Scene)
VALID ON Story OVERLAPS [10, 20]
RETURN scene
```

```gmql
MATCH (release:Release)
VALID ON CommonEra AT "2026-08-01"
RETURN release
```

同じAxisの別表記は自動的に正規化されます。異なるAxis間では、一意・exact・順序保存の`mapsTo`経路だけが通常検索に使用されます。詳しいquery semanticsは[query module guide](../query/README.md#temporal-behavior)を参照してください。

## 部分日付・周期日付を宣言する

誕生日のような毎年の月日は、ダミー年を使わずfieldと周期を宣言します。

```yaml
---
id: Birthday
kind: Timeline
sameAxisAs: CommonEra
coordinate:
  kind: calendar-pattern
  calendar: gregorian
  fields: [month, day]
  repeatsEvery: year
---
```

このTimelineでは`08-08`が毎年8月8日の1日を表します。`validTime`で片方だけを指定すると値の自然期間になります。

```yaml
validTime:
  - timeline: Birthday
    from: "08-08"
```

月・年・四半期・ISO週もfieldから構成します。

```yaml
# YYYY-MM
fields: [year, month]

# YYYY-Qn（4月開始、終了年で年度を表示）
fields: [year, quarter]
quarterStartMonth: 4
quarterYearLabel: end

# YYYY-Www
fields: [weekYear, week]
```

周期値の検索は無限展開を避けるため、完全日付による半開の`WITHIN`窓を必須とします。

```gmql
MATCH (person:Person)
VALID ON Birthday AT "02-29"
WITHIN ["2000-01-01", "2031-01-01")
RETURN person
```

`AT`と`OVERLAPS`は探索窓内での交差、`CONTAINS`はassertionがquery範囲を包含すること、`DURING`はquery範囲がassertionを包含することを意味します。`02-29`はうるう年だけに一致します。

## よくある間違い

### 続編やIF世界に`sameAxisAs`を使う

`sameAxisAs`は同じAxisそのものを共有します。由来だけが同じなら`derivedFrom`を使用してください。

### `derivedFrom`を書けば変換できると思う

Lineageは履歴metadataです。変換には明示的な`mapsTo`が必要です。

### すべてのTimelineに`domain`を書く

通常は不要です。独立、Axis共有、Lineage kindからコンパイラが決定します。特別なグルーピングが必要な場合だけ指定してください。

### Mappingの性質をすべて手書きする

cardinality、totality、order、invertibility、continuityはrule、range、segments、pairsから推論されます。`traits`は推論結果をより保守的にしたい場合だけ使用します。

### 古い`extends`、`timecode`、`mappings`を使う

Timelineの旧フィールドは廃止されています。次のように置き換えます。

| 旧構文 | 新構文 |
| --- | --- |
| Timeline `extends` | `sameAxisAs`または`derivedFrom` |
| Timeline `timecode` | `coordinate` |
| Timeline `mappings` | `mapsTo` |
| Property Schema `mapped: true` | `sameAxisAs`でAxisを共有するTimeline ID |

## 段階的に書くための目安

1. まず`id`と`kind: Timeline`だけを書く。
2. 数値以外を入力したくなったら`coordinate`を追加する。
3. 同じ瞬間の別表記なら`sameAxisAs`を追加する。
4. 分岐・録画・編集の由来を残したければ`derivedFrom`を追加する。
5. 異なるAxis間で変換が必要になった範囲だけ`mapsTo`を追加する。

この順番なら、単純な用途の記述量を増やさず、必要なときだけ時間モデルを詳しくできます。
