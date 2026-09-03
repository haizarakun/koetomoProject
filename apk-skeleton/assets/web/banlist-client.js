/**
 * 共有BANリスト連携クライアント (KoeTomo)
 * 実HTTPはネイティブ(KoeSession)経由。CORS/file-origin問題を回避し、
 * reporter_uid はネイティブ側でログイン中のuidに固定される(改ざん不可)。
 *
 * createBanlistClient({ baseUrl, blockFn, isBlocked, loadEtag, saveEtag, onLog, intervalMs })
 *   メソッド: start() stop() sync() has(uid) getList() report(uid,code,detail) appeal(uid,message) setBaseUrl(u)
 */
(function () {
  function createBanlistClient(opts) {
    opts = opts || {};
    var baseUrl = opts.baseUrl || "";
    var blockFn = opts.blockFn || function () {};
    var isBlocked = opts.isBlocked || function () { return false; };
    var loadEtag = opts.loadEtag || function () { return ""; };
    var saveEtag = opts.saveEtag || function () {};
    var onLog = opts.onLog || function () {};
    var intervalMs = opts.intervalMs || 900000; // 15分
    var call = opts.call || (window.callApi);
    var timer = null, list = [], set = new Set(), started = false, syncing = false;

    function log(m) { try { onLog(m); } catch (e) {} }

    function applyList(banned) {
      list = banned || [];
      set = new Set();
      list.forEach(function (b) { if (b && b.uid != null) set.add(String(b.uid)); });
      // 自動ブロック(端末内非表示)。既にブロック済みは飛ばす。
      list.forEach(function (b) {
        try { if (b && b.uid != null && !isBlocked(String(b.uid))) blockFn(String(b.uid)); } catch (e) {}
      });
    }

    async function sync() {
      if (!baseUrl || syncing) return { ok: false, error: baseUrl ? "busy" : "no_url" };
      syncing = true;
      var attempt = 0, delay = 1000, r = null;
      try {
        while (attempt < 3) {
          try {
            r = await call("moderation_banlist", baseUrl, loadEtag() || "");
            if (r && r.ok) {
              if (r.not_modified) { log("最新(304) " + set.size + "件"); return r; }
              if (r.etag) { try { saveEtag(r.etag); } catch (e) {} }
              applyList(r.banned || []);
              log("同期 " + set.size + "件 (v" + (r.version || "?") + ")");
              return r;
            }
          } catch (e) { log("同期エラー " + e); }
          attempt++;
          if (attempt < 3) { await new Promise(function (res) { setTimeout(res, delay); }); delay *= 2; }
        }
        log("同期失敗(前回のリストで継続)");
        return r || { ok: false, error: "failed" };
      } finally { syncing = false; }
    }

    function start() {
      if (started) return;
      started = true;
      sync();
      timer = setInterval(sync, intervalMs);
    }
    function stop() { started = false; if (timer) { clearInterval(timer); timer = null; } }

    async function report(uid, code, detail) {
      if (!baseUrl) return { ok: false, error: "no_url" };
      return await call("moderation_report", baseUrl, String(uid), code || "other", detail || "", "");
    }
    async function appeal(uid, message) {
      if (!baseUrl) return { ok: false, error: "no_url" };
      return await call("moderation_appeal", baseUrl, String(uid), message || "");
    }

    function has(uid) { return set.has(String(uid)); }
    function getList() { return list.slice(); }
    function setBaseUrl(u) { baseUrl = u || ""; }

    return { start: start, stop: stop, sync: sync, has: has, getList: getList, report: report, appeal: appeal, setBaseUrl: setBaseUrl };
  }
  window.createBanlistClient = createBanlistClient;

  // 通報理由コード(サーバの契約と一致)
  window.KOE_REASON_CODES = { spam: "スパム", scam: "詐欺・宣伝", bot: "bot/自動化", nsfw: "不適切コンテンツ", harass: "嫌がらせ", other: "その他" };
})();
