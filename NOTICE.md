# Third-party components / 第三者コンポーネント

This project bundles or depends on the following third-party components. Their
licenses and terms are those of their respective owners.

## Bundled in the repository

### lame.min.js  (`apk-skeleton/assets/web/vendor/lame.min.js`)
- Purpose: client-side MP3 encoding of recorded voice.
- Origin: a JavaScript LAME/MP3 encoder (e.g. **lamejs**, MIT-licensed).
- Action: verify the exact build you ship and keep its upstream license/notice.
  Replace this note with the precise project name, version, and license URL.

## NOT bundled (must be obtained separately)

### skyway_room.js  (SkyWay WebRTC SDK)
- Purpose: real-time voice calls.
- Origin: **SkyWay** by NTT Communications — a proprietary commercial SDK.
- Redistribution of this SDK is **not** included in this repository. It is
  git-ignored. Obtain it from SkyWay under their terms and place it at
  `apk-skeleton/assets/web/vendor/skyway_room.js` before building.
  See that folder's `README.md`.

## Fonts
- The UI offers Google Fonts loaded at runtime from Google's CDN (SIL Open
  Font License). No font files are redistributed in this repository.

---

If you add or change any third-party component, update this file.
