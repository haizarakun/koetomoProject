# koetomo_qt

koetomoクライアント！ の PySide6(Qt) 版です。**ブラウザエンジン(Chromium/WebView)を
一切使わない、ネイティブ描画のデスクトップアプリ**です。## Python 3.12を推奨

## セットアップ

```bash
py -3.12 -m pip install -r requirements.txt
py -3.12 token_fetcher.py   # 初回のみ: ブラウザでログイン→config.jsonに保存
py -3.12 qt_app.py
```

## ブラウザについて

グループ通話・投稿ボタンは、**声ともがログインを同時に1ブラウザでしか維持できない**ため、
`token_fetcher.py`でログインしたのと同じ**専用のChromeプロファイル**を自動的に使って開きます
（普段使いのデフォルトプロファイルだと、既にChromeが起動していると失敗するため）。
別のブラウザを使いたい場合は、設定(⚙)から実行ファイルのパスを指定できます。

## .exe化する

```powershell
build_exe.bat
```

（内部で`py -3.12`を使うようになっています。Python 3.12が無ければエラーで止まり、
インストールを促します）

`dist`フォルダに`koetomo_client.exe`と`token_fetcher.exe`ができます。同じフォルダに
コピーし、`config.example.json`も一緒に置いてください（自動コピーはされません）。

## ファイル構成

| ファイル | 役割 |
|---|---|
| `qt_app.py` | アプリ本体。PySide6によるGUI一式（ルーム一覧・タイムライン・プロフィール・DM・検索・フレンド・お知らせ・設定） |
| `api.py` | koetomo.funの非公開APIへの薄いラッパー。`koetomo_viewer`と共通。GUI要素は一切含まない、純粋な通信ロジック |
| `token_fetcher.py` | 初回ログイン用の別プロセス。Chromeを起動し、DevTools Protocol経由でログイントークンを検出して`config.json`に保存する |
| `style.qss` | 見た目の定義（Qt Style Sheet、CSSに似た記法）。Discord風の配色・角丸・ホバー効果 |
| `requirements.txt` | 依存パッケージ一覧（`requests`, `websocket-client`, `PySide6`） |
| `config.example.json` | 設定ファイルのひな形。`token_fetcher.py`実行後、実際の`config.json`が自動生成される（**Gitには含めないこと**、ログイン情報が入るため） |
| `build_exe.bat` | Python 3.12を使って`.exe`(インストーラーではなく単体exe)をビルドするスクリプト |
| `LICENSE` | 独自ライセンス全文（全著作権留保・商用利用と改変版の配布を禁止） |
| `.gitignore` | `config.json`・`app.log`・`crash.log`・Chromeプロファイル等、ローカル環境固有・機密情報を含むファイルを除外 |
| `app.log`（実行時に生成） | 通常のログ。起動したPythonのバージョンも記録される |
| `crash.log`（実行時に生成） | ネイティブレベルのクラッシュ(Pythonの例外にすらならないもの)が起きた場合の記録。`faulthandler`で出力。起動のたびに新規作成される |


[公式サポートDiscord](https://discord.gg/WsmjcnB6wq) までお願いします。

## 注意事項

- 非公式クライアントです。KoeTomo運営とは無関係です
- API仕様の変更で動かなくなる可能性があります
- `config.json`にはログインセッション情報が入ります。**絶対に他人と共有しない**でください
  （`.gitignore`済みです）

## ライセンス

独自ライセンス（All Rights Reserved）。商用利用・改変版の配布・再配布を禁止しています。
詳細は [LICENSE](LICENSE) を参照してください。
