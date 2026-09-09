# 共有BANリスト: 「確定 bot」申請の優先受付と偽造防止（サーバー側への依頼書）

対象: redredfast.com のブロックサーバー（`/api/bl/report`）
依頼元: KoeTomo+ クライアント（Android v1.06 / iOS v1.03 以降）

## 1. いま起きていること

- クライアントは業者(bot)を自動判定し、`/api/bl/report` に申請している
- 申請には同一報告者あたりの流量制限があり、`429 rate_limited` で弾かれることがある
- 量産アカウントはまとめて現れる（例: ID 5109736 / 5109741 / 5109744 が連番、名前は「単語＋3桁数字」、自己紹介なし）ため、
  一番確度の高い申請ほど制限に当たりやすい。弾かれた分は v1.05 まで「申請済み」扱いで捨てられていた

## 2. クライアント側で変えたこと（v1.06 / iOS 1.03）

申請本文に 2 つのフィールドを追加した。

```json
{
  "target_uid": "5109741",
  "reason_code": "bot",
  "detail": "[KoeTomo+ 業者自動判定(自動申請) score=9.5 確定] 量産型アイコン名・交流0・自己紹介なし・年齢確認なし・名前が単語+3桁数字・同型の名前(単語+3桁)がID近接で複数",
  "evidence": "{ ...判定材料の生値(下記)... }",
  "reporter_uid": "4214303",
  "auto": true,
  "confidence": "confirmed",          ← 追加: "high" | "confirmed"
  "client": "koetomoplus-android/1.06" ← 追加
  "retry": true                        ← 再送のときだけ付く
}
```

- `confidence: "confirmed"` の条件（クライアント側）: スコア ≥ 6.0 かつ「量産群としての確証」が 1 つ以上
  - 既知 bot と ID が ±20 以内（同じ端末で連続作成された群）
  - 既知 bot と同一 `feature`（端末識別子）
  - 「単語＋3桁数字」型の名前が ID ±100 以内に他 2 人以上
- `confirmed` の申請が 429 / 5xx で通らなかった場合、端末内の再送キューに入れ、1 分以上あけて 1 件ずつ最長 7 日間再送する（`retry: true`）
- `evidence` に `verify` オブジェクトを追加。サーバーが自分で検証するための材料:

```json
"verify": {
  "target_uid": 5109741,
  "neighbor_uids": [5109736, 5109744],   ← クライアントが同型と見た近接 ID
  "checked_at_ms": 1788950000000,
  "rules": "core>=4 | core>=3+aux | core>=2+namepattern | namepattern+cluster>=2"
}
```

`evidence` の他の生値: `icon_file`, `icon_kind`(generated/none/normal), `follower_count`, `followee_count`, `friend_count`, `liked_count`,
`comment_empty`, `comment_suspicious`, `age_verification_status`, `core_hits`(0〜4), `name`, `name_cluster`, `feature`(先頭120字), `login_status`, `score`, `level`。

## 3. サーバー側にお願いしたいこと

### 3-1. 「確定」レーンを別の流量枠にする

- `confidence == "confirmed"` の申請は、通常の報告者別レート制限とは**別枠**で受ける
  - 例: 通常 = 1時間3件 / 1日10件（現状）、確定レーン = 1時間20件 / 1日100件（報告者別）
- ただし **確定レーンで受けるのは「サーバー側の検証に通ったもの」だけ**（3-2）。検証に落ちたものは通常枠に回し、通常枠も超えていれば 429 を返す
- `retry: true` の申請は重複扱いにせず、初回と同じ判定をする（`duplicate: true` で 200 を返してよい。クライアントは 2xx なら「申請済み」にする）

### 3-2. `confidence` を信用しない ― サーバーが自分で確かめる（偽造防止の本体）

クライアントが送る値は、改造アプリや手打ちの curl で何でも書ける。**フラグではなく事実で判定する**。

1. **対象の取り直し**: サーバー自身の koetomo アカウント（専用の閲覧用アカウント）で
   `GET https://api2.meetscom.com/api/v2/users?ids=<target_uid>` を叩き、公開プロフィール（名前・アイコンパス・各カウント・自己紹介・年齢確認）を取得する。
   クライアントの `evidence` の生値と突き合わせ、**一致しない申請は捨てる**（`400 evidence_mismatch`）。
   - 突き合わせは「その時点で変わり得る値」（フォロワー数など）は ±2 程度の誤差を許し、名前・アイコン種別・自己紹介の有無は一致必須
2. **判定の再計算**: 取り直した値で、クライアントと同じ規則（§4）をサーバー側で再計算する。`hard` かつスコア ≥ 6 でなければ「確定」として扱わない
3. **群の確認**: `verify.neighbor_uids` の各 ID も同じように取り直し、「単語＋3桁型の名前」「量産型の特徴」が実際に揃っているか確認する。
   2 人以上が確認できたときだけ `name_cluster` を認める。**クライアントが挙げた近接 ID は「ヒント」であり、証拠はサーバーの取得結果**
4. **複数報告者の一致**: 別の `reporter_uid` から同じ対象に `confirmed` が来ていれば、それ自体を強い裏付けとする（2 人以上で自動承認、1 人なら人手承認待ちのまま優先表示、など運用に合わせて）
5. **報告者の信用度**: 検証に落ちた申請が続く報告者は、確定レーンの枠を自動で 0 にする（通常枠は残す）。誤申請の連打で他人を巻き込めないようにする

これで「クライアントが `confirmed` と書けば通る」経路は無くなる。通るのは koetomo の公開情報がその時点で量産型の条件を満たしている相手だけ。

### 3-3. 追加でできること（任意）

- **群まとめ承認**: 検証済みの `neighbor_uids` を同じ群としてまとめ、群単位で承認・却下できる管理画面の項目
- **申請元の照合**: `client` の値と、そのクライアントが出す `User-Agent` を照合し、明らかに別物（curl など）は確定レーンに入れない。決定打ではないが手打ちの雑な偽造を弾ける
- **短時間の連番検知**: サーバー側で「直近 24 時間に申請された ID のうち、±100 以内に同型名が 3 つ以上」を集計し、報告が無くても候補として管理画面に出す（クライアントより広い視野で群が見える）
- **返答の拡張**: 200 の本文に `lane: "confirmed"|"normal"`, `verified: true|false`, `verify_reason` を返してもらえると、クライアント側の診断ログで動作を確認できる

## 4. クライアントの判定規則（サーバーで再計算するための定義）

4 つの「量産型の特徴」:

| 記号 | 条件 |
|---|---|
| A1 | アイコンのファイル名が 16 文字英数の自動生成名、または未設定 |
| A2 | follower / followee / friend / liked がすべて 0（1〜2 件の「ほぼ 0」＝follower≤2, followee≤2, friend=0, liked≤2 も同扱い） |
| A3 | 自己紹介が空、または勧誘・外部誘導の語句を含む（LINE／ライン／カカオ／TikTok／副業／副収入／稼げ／投資／FX／仮想通貨／パパ活／裏垢／割り切り／@ID／ID:／検索して など） |
| A4 | 年齢確認ステータス = 0 |

`core` = A1〜A4 のうち真の数。`namepattern` = 名前が `^[^\s]{1,20}[0-9]{3}$` に一致し、数字だけではない。

`hard`（量産型とみなす）:
- `core ≥ 4`
- または `core ≥ 3` かつ（namepattern ／ 既知 bot と ID ±20 ／ 既知 bot と同一 feature ／ 勧誘文）
- または `core ≥ 2` かつ namepattern
- または namepattern かつ 同型名が ID ±100 に 2 人以上

スコア（`hard` のときだけ意味を持つ）:
namepattern +3、既知 bot と ID 近接 +3、同一 feature +3、同型名クラスタ +3、勧誘文 +2、アイコン未設定 +1、
ランダムマッチ ON かつ交流ほぼ 0 +1.5、直近ログイン +0.5、直近 1 時間に 5 投稿以上 +2。
`level`: スコア ≥ 6.0 → high（自動申請）、≥ 3.0 → mid（表示のみ）。

## 5. 互換性

- 追加フィールドを知らないサーバーでも従来どおり動く（`confidence` を無視すれば v1.05 と同じ挙動）
- クライアントは 2xx と 429 以外の 4xx を「内容の問題」と見て再送しない。5xx と 429 だけ再送する

## 6. 管理画面（/api/bl/view）で通報者のプロフィールを見られるようにする

- 各申請の行に `reporter_uid` を表示し、`https://koetomo.fun/users/<reporter_uid>` へのリンクを付ける
  （この URL は公式アプリ／KoeTomo+ の App Link。スマホで開くとそのままプロフィール画面に飛ぶ。PC では koetomo.fun のページ）
- 対象 `target_uid` も同じ形式でリンクにする
- できれば、サーバーの閲覧用アカウントで `GET /api/v2/users?ids=<reporter_uid>` を叩いて通報者の名前・アイコンを取得し、ID の横に表示する（取得結果は 24 時間キャッシュでよい）
- 通報者ごとの集計（申請数・承認率・検証落ち率）を出すと、§3-2 の「報告者の信用度」を人手でも確認できる
- 通報者の情報は管理者だけに見せる。`/api/bl/list`（クライアントが取得する公開一覧）には引き続き含めない

## 7. 実装者向けの補足

### 7-1. `evidence` の正確なフィールド名（クライアントが送る JSON 文字列を parse した後のキー）

| キー | 型 | 意味 |
|---|---|---|
| `icon_file` | string | アイコン URL のファイル名部分（クエリ除く） |
| `icon_kind` | `"generated"` / `"none"` / `"normal"` | 16 文字英数の自動生成名 / 未設定 / 通常 |
| `follower_count` `followee_count` `friend_count` | number | 取得できなければ -1 |
| `liked_count` | number | 取得できなければ -1 |
| `comment_empty` | boolean | 自己紹介が空 |
| `comment_suspicious` | boolean | 自己紹介に勧誘・外部誘導の語句 |
| `age_verification_status` | number | 0 = 未確認、取得できなければ -1 |
| `core_hits` | number (0〜4) | A1〜A4 のうち真の数 |
| `A1_icon16` `A2_all_zero` `A2_near_zero` `A3_no_bio` `A4_no_age_verify` | boolean | 各特徴の判定結果 |
| `name` | string | 表示名（そのまま） |
| `name_cluster` | number | 同型名（単語＋3桁）が ID ±100 にいた他人の数 |
| `feature` | string | 端末識別子（先頭 120 文字） |
| `random_match_enabled` | boolean | |
| `login_status` | string | `login_status_with_unit` の生値 |
| `score` | number | クライアントの採点 |
| `level` | `""` / `"mid"` / `"high"` | |
| `checked_at` | string | クライアント時刻（表示用） |
| `checked_by` | string | `"KoeTomo+ auto"` |
| `posts_last_hour` | number | 直近 1 時間の投稿数（数えたときだけ） |
| `verify.target_uid` | number | 対象 ID |
| `verify.neighbor_uids` | number[] | クライアントが同型・近接と見た ID（ヒント。証拠はサーバーの取得結果） |
| `verify.checked_at_ms` | number | 判定時刻（ms） |
| `verify.rules` | string | `"core>=4 \| core>=3+aux \| core>=2+namepattern \| namepattern+cluster>=2"` |

申請本文（トップレベル）: `target_uid`(string) `reason_code`("bot") `detail`(string) `evidence`(string=JSON) `reporter_uid`(string) `auto`(true)
`confidence`("high"|"confirmed") `client`("koetomoplus-android/1.06" など) `retry`(true、再送時のみ) `queued_at`(ms、再送時のみ)。

### 7-2. koetomo API の呼び方（サーバーが取り直すとき）

```
GET https://api2.meetscom.com/api/v2/users?ids=<uid1>,<uid2>&version=3.9.101
Headers:
  X-Auth-Token: <閲覧用アカウントの auth_token>
  Authorization: <同じ auth_token>
  X-App-Version: android_3.9.101
  X-KOETOMO-REQUEST-ID: <UUID からハイフンを除いた 32 文字、毎回新規>   ← 無いと 503「リクエストエラー」
  Accept: application/json
  User-Agent: okhttp/4.12.0                                              ← 独自 UA は WAF に弾かれる
```
応答は `{"user_info":[{ user_id, name, comment, profile_picture_file_path, follower_count, followee_count, friend_count,
liked_count, age_verification_status, feature, login_status_with_unit, settings:{random_match_enabled,...}, ... }]}`。
`ids` は 20 件までまとめられる。1 秒に数回程度に抑え、結果は 10 分ほどキャッシュしてよい。

### 7-3. 閲覧用アカウントの認証情報の渡し方（Cloudflare）

- 専用の koetomo アカウントを 1 つ作り、その `auth_token` だけをサーバーに渡す（メール／パスワードは渡さない）。
  トークンは KoeTomo+ でそのアカウントにログインし、設定 → トークンのエクスポート で取り出せる
- Cloudflare では **環境変数ではなく Secret** として保存する（ダッシュボードに値が表示されない）
  - Workers: `wrangler secret put KOETOMO_VIEW_TOKEN`（`wrangler.toml` の `[vars]` には書かない）
  - Pages Functions: Dashboard → Workers & Pages → プロジェクト → Settings → Environment variables → 追加時に **Encrypt** を選ぶ。Production / Preview それぞれに設定
  - コードからは `env.KOETOMO_VIEW_TOKEN` で参照
- 推奨する変数名:
  - `KOETOMO_VIEW_TOKEN` … 閲覧用アカウントの auth_token（Secret）
  - `KOETOMO_VIEW_UID` … そのアカウントの user_id（平文でよい。自分自身への申請を弾く用）
  - `KOETOMO_API_BASE` … `https://api2.meetscom.com`（平文。省略時はこの値）
  - `BL_CONFIRMED_HOURLY` / `BL_CONFIRMED_DAILY` … 確定レーンの枠（既定 20 / 100、平文）
- トークンはログに出さない。`X-Auth-Token` を含むリクエストを丸ごとログに書かない
- 期限切れ／無効化されると koetomo 側が 401 または 403（`X-Vsns-Status: 101/102/119`）を返す。その場合は検証をスキップして通常レーンに回し、管理者に通知する（確定レーンを止めるだけで、申請自体は落とさない）
- トークンをこの文書やリポジトリ・チャットに貼らないこと
