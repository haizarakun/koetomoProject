# Koetomo Project

Koetomo (koetomo.fun) の非公式デスクトップクライアント、APIラッパー、および専用Chrome拡張機能をまとめたプロジェクトです。

## 📦 プロジェクト構成

本リポジトリは以下のコンポーネントで構成されています。

- **`koetomo_viewer/` (`koetomo-client/`)**: Python製の非公式APIラッパー & デスクトップGUIクライアント
- **`koetomo_theme/`**: ダークモード化や通話オーバーレイ機能を提供するChrome拡張機能

---

# 1. Koetomo Unofficial Desktop Client & API Wrapper (`koetomo_viewer` / `koetomo-client`)

[![Python Version](https://img.shields.io/badge/python-3.8%2B-blue.svg)](https://www.python.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](https://opensource.org/licenses/MIT)

Koetomo (koetomo.fun) の非公式デスクトップクライアントおよびPython APIラッパーです。ルーム一覧・タイムライン・プロフィール・DMを閲覧でき、自動入室やbot的な自動化は一切含みません。

## 📖 主な機能

- **ルーム一覧**: 閲覧（並び替え・検索・複数ページ取得）、グループ通話を固定サイズのChromeウィンドウで表示
- **タイムライン & 投稿**: タイムライン閲覧、いいね・ブックマーク、フィードの新規投稿
- **プロフィール管理**: 自分/他ユーザーのプロフィール閲覧・編集（アイコン/ヘッダー画像変更含む）、ユーザー検索、フォロー・フレンド申請・ブロック
- **DM機能**: LINE風の吹き出し表示、画像/音声の送受信、既読表示
- **マルチアカウント & UIカスタム**: 複数アカウントの切り替え・管理、テーマカラー・ボタンスタイルのカスタマイズ
- **API ラッパー (`api.py`)**: セッション管理、自動認証ハンドリング、各種エンドポイントへのアクセス関数を提供

## 📁 ディレクトリ構成

```text
koetomo-client/ (koetomo_viewer/)
├── api.py            # Koetomo APIラッパー
├── gui_app.py        # GUIアプリケーション
├── token_fetcher.py  # ログイン＆トークン取得スクリプト
├── start_hidden.vbs # Windows向けバックグラウンド起動スクリプト
├── config.json      # 設定・認証情報
└── requirements.txt  # 依存ライブラリ
🚀 セットアップ & 使い方
依存ライブラリのインストール

Bash
pip install -r requirements.txt
ログイン・トークン取得

Bash
python token_fetcher.py
※ブラウザでログインすると、自動的に認証情報が config.json に保存されます。

アプリの起動

Bash
python gui_app.py
※Windowsでコンソールを表示せずに起動したい場合は、start_hidden.vbs をダブルクリックしてください。

2. Koetomo Theme & Call Extension (koetomo_theme)
koetomo.fun の見た目をダークモード化し、グループ通話中に音量調整・ミュート・しゃべり検知・入退室ログを表示するChrome拡張機能です。

🤝 デスクトップクライアントとの連携
デスクトップクライアント（koetomo_viewer）側の設定で「拡張機能を使う」をONにすると、グループ通話がタブ付きの通常のChromeウィンドウで開くようになり、本拡張機能がそのまま適用されます。これにより、通話中に「今しゃべっている人」が光って分かるようになります。

✨ 機能一覧
ダークモード化: 背景色・アクセントカラーのカスタマイズ

マイク制御: 自分のマイク音量メーター・ミュート / ミュート解除

音量調整: 参加者ごとの個別音量調整 ＋ 声とも全体のマスター音量調整（該当タブの音声のみ）

しゃべり検知: 話した時に光るアニメーション（音量をリアルタイム解析）

入退室ログ: 入室 / 退室ごとの色分け表示、個別削除・全削除対応

通話履歴: 端末ローカルに保存（いつでも削除可能、⚙アイコンからアクセス）

⚠️ 注意事項: 本機能はブラウザのタブ内で完結する機能です。Discordのゲームオーバーレイのように、ブラウザの外にある他のアプリやゲーム画面の上に重ねて表示することはできません（ブラウザ拡張機能の権限では技術的に不可能なため）。

🔧 インストール方法（Chrome拡張機能）
Chromeで chrome://extensions を開く

右上の「デベロッパーモード」をONにする

「パッケージ化されていない拡張機能を読み込む」をクリックし、koetomo_theme フォルダを選択

🛠 開発者向け情報
API仕様 & 制約
api.py 冒頭のコメントに、推測実装している箇所や既知の制約をまとめてあります。

Chrome拡張機能の実装仕様
参加者IDの解決: 音声要素のみでは発言者の特定が困難なため、サイトが発行する fetch("/api/users?ids[]=...") を横取りして対応付けています。通信のタイミングにより、一時的に「ID不明#1」と表示される場合がありますが、情報取得次第自動で書き換わります。

実行コンテキスト:

mic_hook.js: MAIN worldで動作し、getUserMedia と fetch をラップ。

call_overlay.js: ISOLATED worldで動作。window への CustomEvent でやり取りを行う際、複雑なオブジェクトはJSON文字列化して渡しています。

DOM構造のハッシュ化対策: クラス名等がビルド毎に変更されるため、guessLabel 等はDOM構造に直接依存せず、audio 要素の存在と fetch 横取りのみで判定する設計にしています。

⚠️ 免責事項・注意事項
非公式ツール: 本プロジェクトは非公式です。KoeTomo運営とは一切関係がありません。

仕様変更: サイトの仕様変更やUI変更によって動作しなくなる可能性があります。

セキュリティ: config.json にはログインセッション情報が含まれます。絶対に他人と共有しないでください（.gitignore 設定済み）。

プライバシー: 拡張機能による通信は一切外部へ送信されず、すべてローカル（chrome.storage）で完結します。

💬 サポート & お問合せ
バグ報告や機能要望は以下までお願いいたします。

GitHub: haizarakun/koetomoProject

公式サポート　https://discord.gg/wETHFNZSXU

ライセンス

このソフトウェアは学習目的として公開されています。

免責事項
このソフトウェアは開発者のリバースエンジニアリング学習を目的として作成

ツールの利用はすべてユーザー自身の責任
https://koetomo.fun/termの利用規約を遵守
