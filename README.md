# Koetomo Unofficial Client & API Wrapper

[![Python Version](https://img.shields.io/badge/python-3.8%2B-blue.svg)](https://www.python.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](https://opensource.org/licenses/MIT)

koetomo.fun の非公式Python APIラッパー（`api.py`）および、それを活用したデスクトップ向けGUIクライアント（`gui_app.py`）です。

## 📖 目次
- [概要](#概要)
- [主な機能](#主な機能)
- [ディレクトリ構成](#ディレクトリ構成)
- [インストール](#インストール)
- [使い方](#使い方)
- [既知の問題と今後の課題](#既知の問題と今後の課題)
- [免責事項](#免責事項)
- [ライセンス](#ライセンス)

## 概要
本プロジェクトは、KoetomoのAPIをPython環境から操作するためのカスタムラッパーです。フィードの閲覧・投稿、プロファイルの更新、複数アカウントの管理機能を提供します。また、`pywebview` を用いたGUIアプリケーションにより、直感的な操作とシームレスなトークルームアクセスを可能にします。

## 主な機能
- **API ラッパー (`api.py`)**
  - セッション管理と自動認証ハンドリング
  - ユーザープロファイル・ヘッダー画像の取得・更新
  - フィードの取得と新規投稿
- **GUI アプリケーション (`gui_app.py`)**
  - 複数アカウントの切り替えと管理
  - デスクトップ環境での利用に最適化されたUI
  - 組み込みWebviewによるトークルーム閲覧

## ディレクトリ構成
```text
koetomo-client/
├── api.py           # Koetomo APIラッパー
├── gui_app.py       # GUIアプリケーション
├── config.json      # 設定・認証情報
├── README.md        # このファイル
└── requirements.txt # 依存ライブラリ**
