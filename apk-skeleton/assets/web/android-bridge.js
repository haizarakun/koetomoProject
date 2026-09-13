/*
 * Android(WebView) ↔ JS のブリッジ
 *
 * ★画面が真っ黒になる事故への備え:
 *   アプリ本体の起動処理は "pywebviewready" を受け取ってから動き出す。
 *   以前はこのファイルの先頭で AndroidApi が未注入だとそのまま return しており、
 *   その場合 "pywebviewready" を誰も発火しないため起動処理が一度も走らず、
 *   ロード画面が消えたあと画面が真っ黒のままになっていた
 *   (WebView のJSインターフェース注入は端末やタイミングで遅れることがある)。
 *   いまは AndroidApi が来るまで少し待ち、来なくても必ず一度は合図を出す。
 */
(function () {
  var READY_EVENT = "pywebviewready";
  var WAIT_MS = 3000; // AndroidApi を待つ上限
  var POLL_MS = 50;

  /* 合図は何があっても1回だけ出す(ブリッジ側と保険側の二重発火を防ぐ) */
  function fireReady() {
    if (window.__koeReadyFired) return;
    window.__koeReadyFired = true;
    try {
      window.dispatchEvent(new Event(READY_EVENT));
    } catch (e) {}
  }
  window.__koeFireReady = fireReady; // app.js 側の保険からも呼べるようにする

  /* AndroidApi が使えるときだけ、実際の呼び出し口を組み立てる */
  function setupBridge() {
    if (window.__koeBridgeReady) return;
    window.__koeBridgeReady = true;

    var pending = {};
    var seq = 0;

    window.__koeResolve = function (id, jsonString) {
      var p = pending[id];
      if (!p) return;
      delete pending[id];
      try {
        clearTimeout(p.tm);
      } catch (e) {}
      try {
        p.resolve(JSON.parse(jsonString));
      } catch (e) {
        p.resolve({ ok: false, error: "parse_error", raw: jsonString });
      }
    };

    function call(method, args) {
      return new Promise(function (resolve) {
        var id = "c" + ++seq;
        pending[id] = { resolve: resolve };
        /* ネイティブから応答が返らない場合でも Promise が永遠に未解決にならないよう
           90 秒で打ち切る(同一呼び出しの相乗り待ちが詰まるのを防ぐ) */
        var tm = setTimeout(function () {
          if (pending[id]) {
            delete pending[id];
            resolve({ ok: false, error: "timeout", status: 0 });
          }
        }, 90000);
        pending[id].tm = tm;
        try {
          window.AndroidApi.call(method, JSON.stringify(args || []), id);
        } catch (e) {
          clearTimeout(tm);
          delete pending[id];
          resolve({ ok: false, error: "bridge_error: " + (e && e.message) });
        }
      });
    }

    var api = new Proxy(
      {},
      {
        get: function (_t, method) {
          return function () {
            return call(method, Array.prototype.slice.call(arguments));
          };
        },
      },
    );
    window.pywebview = { api: api };
    window.__isAndroid = true;
  }

  /* DOM が出来てから合図を出す(起動処理が要素を触るため) */
  function fireWhenDomReady() {
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", function () {
        setTimeout(fireReady, 0);
      });
    } else {
      setTimeout(fireReady, 0);
    }
  }

  if (window.AndroidApi) {
    setupBridge();
    fireWhenDomReady();
    return;
  }

  /* AndroidApi がまだ注入されていない: 少しだけ待つ。
     間に合っても間に合わなくても、最後は必ず合図を出して起動処理を動かす
     (動かさないと画面が真っ黒のままになるため)。 */
  var waited = 0;
  var timer = setInterval(function () {
    waited += POLL_MS;
    if (window.AndroidApi) {
      clearInterval(timer);
      setupBridge();
      fireWhenDomReady();
    } else if (waited >= WAIT_MS) {
      clearInterval(timer);
      fireWhenDomReady(); // ブリッジが無くても、画面だけは必ず出す
    }
  }, POLL_MS);
})();
