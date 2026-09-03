# vendor/ — third-party front-end libraries

## skyway_room.js  (required for voice calls, NOT committed)

This file is the **SkyWay** WebRTC SDK (NTT Communications), a proprietary
commercial SDK. It is intentionally **git-ignored** and not redistributed in
this repository.

To build an APK with working voice calls, obtain the SkyWay Room SDK under
SkyWay's own terms and save it here as:

```
apk-skeleton/assets/web/vendor/skyway_room.js
```

The app loads it locally as `vendor/skyway_room.js`. Without it, the app builds
and runs, but voice-call features are disabled.

## lame.min.js  (committed)

MP3 encoder used for recording voice clips. See ../../../NOTICE.md for its
license. Verify the exact upstream/version you ship.
