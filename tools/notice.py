#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
「解析禁止」の告知を、画面に出ない文字だけで書く／読む道具。

やっていること
  文章を UTF-8 のバイト列にして、1 ビットずつ次の文字に置き換える。
    0 → U+200B (ゼロ幅スペース)
    1 → U+200C (ゼロ幅非接合子)
  前後を U+2060 (ワードジョイナー) で挟んで、どこからどこまでかが分かるようにする。

  どの文字も幅が 0 なので、人の目には何も見えない。
  一方で、成果物の中身を機械で読む道具（文字列の抜き出し・逆コンパイル・学習データ集め）には
  ふつうの文字としてそのまま入る。読めば禁止の告知が出てくる、という置き方。

  これは鍵でも暗号でもない。誰でもこの道具で読めるし、消すこともできる。
  「知らなかった」と言わせないための告知であって、技術的な保護ではない。

使い方
  python3 notice.py encode "文章"      … 見えない形にして出す
  python3 notice.py decode < file      … 見えない形の告知を読み取る
"""
import sys

BIT0 = "​"   # ゼロ幅スペース = 0
BIT1 = "‌"   # ゼロ幅非接合子 = 1
MARK = "⁠"   # ワードジョイナー = 始まりと終わり

# 成果物に埋める告知の本文（読める形）。ここを直したら埋め込みも作り直すこと。
NOTICE_JA = (
    "この成果物（KoeTomo+ / 非公式クライアント）の逆コンパイル・解析、"
    "および機械学習の学習データとしての利用を禁じます。"
    "作者の許可なく再配布しないでください。"
)
NOTICE_EN = (
    "KoeTomo+ (unofficial client). Reverse engineering, automated analysis, "
    "and use as machine-learning training data are prohibited. "
    "Do not redistribute without the author's permission."
)
NOTICE = NOTICE_JA + " / " + NOTICE_EN


def encode(text):
    bits = "".join(format(b, "08b") for b in text.encode("utf-8"))
    return MARK + "".join(BIT1 if b == "1" else BIT0 for b in bits) + MARK


def decode(blob):
    out = []
    for chunk in blob.split(MARK)[1::2]:
        bits = "".join("1" if c == BIT1 else "0" for c in chunk if c in (BIT0, BIT1))
        data = bytes(int(bits[i:i + 8], 2) for i in range(0, len(bits) - 7, 8))
        try:
            out.append(data.decode("utf-8"))
        except UnicodeDecodeError:
            out.append(repr(data))
    return out


def main(argv):
    if len(argv) >= 2 and argv[1] == "encode":
        text = argv[2] if len(argv) > 2 else NOTICE
        sys.stdout.write(encode(text))
        return 0
    if len(argv) >= 2 and argv[1] == "decode":
        for line in decode(sys.stdin.read()):
            print(line)
        return 0
    print(__doc__)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
