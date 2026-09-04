<h1 align="center">KoeTomo+</h1>

<p align="center">
  <b>声のコミュニティ「koetomo（声とも）」の非公式 Android クライアント</b><br>
  An unofficial Android client for the "koetomo / 声とも" voice community.
</p>

<p align="center">
  <img alt="version" src="https://img.shields.io/badge/version-1.02-blue">
  <img alt="platform" src="https://img.shields.io/badge/platform-Android%206.0%2B-green">
  <img alt="license" src="https://img.shields.io/badge/license-source--available-lightgrey">
  <img alt="status" src="https://img.shields.io/badge/status-unofficial-orange">
</p>

> ⚠️ **非公式・ファンメイドのアプリです。** 運営元とは一切関係がありません。
> 公式に公開されていない API と通信するため、対象サービスの利用規約に抵触する可能性があります。
> 必ず [DISCLAIMER.md](DISCLAIMER.md) を読み、**自己責任**でご利用ください。

---

## これは何？

KoeTomo+ は、koetomo（声とも）を **WebView ＋ 薄いネイティブ層** で動かす軽量な非公式クライアントです。
公式アプリにはない使い勝手（複数アカウント、参加者ごとの音量調整、画像・音声の保存、フォント切替など）を目的に作られています。

- **軽い** — APK は約 600 KB。フロントエンドは単一ページの HTML/CSS/JS
- **通信はすべて HTTPS** — 認証トークンは Android Keystore で暗号化して保存
- **ソース公開** — 何をしているアプリか、誰でも読んで確認できます（[ライセンス](#ライセンス)参照）

## 主な機能

### ログイン・アカウント
- メールアドレス／X（Twitter）／LINE・Facebook（ID 指定）／トークンのみ、の各方式に対応
- **複数アカウントの保存と切替**（トークンは暗号化ストアに保存）
- 生体認証ロック

### 通話
- 枠への参加・作成、発言リクエスト（挙手）の承認、参加／退出／昇格の通知
- **参加者ごとの音量調整（0〜200%）**
- 通話中チャットのリアルタイム受信、新着バブル・未読バッジ
- 役割・コメント・枠名の変更を公式と同じ仕組み（Firebase Realtime Database）で即時反映
- フローティング通話バブル、PiP、バックグラウンド通話

### タイムライン・投稿
- つぶやく／話そう の閲覧・投稿・いいね・返信
- 投稿の削除、ブックマークの登録・解除・一覧
- 左スワイプ→オープン／右スワイプ→フォロー中
- 投稿者・返信者・通知のアイコンをタップでプロフィールへ

### プロフィール・マイページ
- 公式風レイアウト（ヘッダー全幅＋中央にアイコン）
- ヘッダー画像・プロフィール画像の変更、自己紹介の折りたたみ表示

### 通知
- 過去の通知をさかのぼって読み込み
- **アプリを閉じている間も Android の通知**で新着を知らせる（設定でオン／オフ）
- 通知からサークル・枠・投稿・プロフィールへ直接移動

### その他
- DM（テキスト・画像）、コミュニティ（参加／退会／投稿）
- 画像・音声・アイコン／ヘッダーの保存
- 30 種以上のフォント切替（Google Fonts / OFL）
- 最終オンラインの色分け表示、フォロー一覧のオンライン順並び替え、フォロー中の活動時間帯グラフ
- 自分の投稿が規制対象になったら自動削除して通知（設定でオン／オフ）
- 共有 BAN リスト連携（任意・同意制。[DISCLAIMER.md](DISCLAIMER.md) 参照）
- 新バージョン公開時のアプリ内アップデート

## ダウンロード

最新版の APK は **[Releases](../../releases/latest)** から入手できます。
「提供元不明のアプリ」のインストールを許可してから APK を開いてください。

- パッケージ名: `com.akun.koetomoplus`
- 対応: Android 6.0 以上
- 更新: 新しいバージョンが公開されると起動時に案内が出ます（同じ署名なので上書きインストール可）

## ソースコードについて

このリポジトリでは KoeTomo+ の**ソースコードを公開**しています。
目的は「このアプリが何をしているか」を誰でも確認できるようにすることです。

```
.
├── src/                     ネイティブ Java（WebView ホスト・API ブリッジ・暗号化ストア）
├── apk-skeleton/            APK の構成（マニフェスト・リソース・web 資産）
│   └── assets/web/          フロントエンド（index.html / app.js / style.css ほか）
└── LICENSE  DISCLAIMER.md  NOTICE.md  CHANGELOG.md
```

公開しているのは**閲覧・検証のため**です。
このソースからのビルドや、ビルドした APK の使用・配布は許可していません。
アプリは [Releases](../../releases/latest) で配布している APK のみをご利用ください。

## ライセンス

本リポジトリは **ソースコードを公開していますが（source-available）、オープンソースではありません。**
要点は次のとおりです。詳細は [LICENSE](LICENSE) を参照してください。

| | |
|---|---|
| ✅ できる | ソースの閲覧・学習・検証／不具合の報告・改善提案 |
| ❌ できない | **ソースからのビルド**／**改変版の公開・配布**／**APK・ソースの再配布・ミラー**／**商用利用**／APK の逆解析・改ざん・再署名／運営や他ユーザーへの迷惑行為／自動化・bot 利用 |

「koetomo」「声とも」等の名称・ロゴ・サービス上のコンテンツはそれぞれの権利者に帰属します（[DISCLAIMER.md](DISCLAIMER.md)）。
同梱・依存する第三者コンポーネントは [NOTICE.md](NOTICE.md) を参照してください。

---

<details>
<summary><b>English</b></summary>

**KoeTomo+** is an unofficial, fan-made Android client for the Japanese voice community
"koetomo / 声とも". It is not affiliated with or endorsed by the service operator, and it
talks to a non-public API, which may violate the service's Terms of Service. **Use at your own risk**
(see [DISCLAIMER.md](DISCLAIMER.md)).

**Highlights:** multi-account login (email / X / LINE / Facebook / token), per-participant volume
control in calls (0–200 %), real-time call chat, bookmarks and post deletion, official-style profile
layout, background Android notifications, image/audio download, 30+ fonts, encrypted token storage,
and a ~600 KB APK.

**Download:** grab the latest APK from [Releases](../../releases/latest). Android 6.0+.

**Source-viewable, not open source.** You may read the code for study and verification.
You may **not** build the app from this source, redistribute the app or source, publish modified
versions, use it commercially, or reverse-engineer / re-sign the APK. See [LICENSE](LICENSE).

</details>
