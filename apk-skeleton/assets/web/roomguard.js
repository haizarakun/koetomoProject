/*
 * KoeGuard — 枠(通話ルーム)内のスパムコメントと爆音の自動対策
 *
 * 目的
 *   - 短時間に大量のコメント / 同じ文の連投をする人のコメントを自分の画面から隠す
 *   - 極端に大きな音を出し続ける人の音量を自分側で自動的に下げる
 *   - 設定に応じて、検知した人をキック / リスナーに戻す まで自動で行う
 *     (自分の役割は見ない。権限が無ければサーバーが拒否するので、その結果を通知する)
 *
 * 方針
 *   - 判定も対処もすべてこの端末の中だけで行う(他の参加者には何も送らない)
 *   - 枠を出たら判定状態は捨てる(次の枠に持ち越さない)
 *   - 誤検知に備えて、対処のたびに「戻す」ボタン付きの通知を出す
 *
 * 連携(app.js 側の呼び出し)
 *   KoeGuard.onComments(added)          新着コメント配列を渡す(reloadRoomComments)
 *   KoeGuard.isHidden(userId)           コメント描画時に非表示にするか(koeChatRender)
 *   KoeGuard.onLevel(userId, rms, now)  受信音声の音量(RMS 0〜1)を渡す(speakLoop)
 *   KoeGuard.reset()                    枠を出た時
 */
(function (global) {
  "use strict";

  var STORAGE_KEY = "koe_guard";

  var DEFAULTS = {
    spamEnabled: true,
    spamMaxPer10s: 6, // 10 秒間にこの件数以上でスパム
    spamSameText: 3, // 同じ文を 20 秒以内にこの回数以上でスパム(3 文字未満の相づち「w」「草」等は数えない)
    spamAction: "none", // none | listener | kick
    loudEnabled: true,
    loudLevel: 5, // 1(鈍い)〜10(敏感)
    loudSeconds: 2, // この秒数以上、爆音が続いたら対処
    loudVolume: 20, // 対処後の相手の音量(%)
    loudAction: "none", // none | listener | kick
  };

  function loadSettings() {
    var s = {};
    try {
      s = JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}") || {};
    } catch (e) {}
    var out = {};
    Object.keys(DEFAULTS).forEach(function (k) {
      out[k] = s[k] === undefined ? DEFAULTS[k] : s[k];
    });
    return out;
  }

  function saveSettings(patch) {
    var s = loadSettings();
    Object.keys(patch || {}).forEach(function (k) {
      if (k in DEFAULTS) s[k] = patch[k];
    });
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(s));
    } catch (e) {}
    settings = s;
    return s;
  }

  var settings = loadSettings();

  /* ---- 枠ごとの状態 ---- */
  var hiddenUsers = {}; // uid -> true   コメントを隠している人
  var commentTimes = {}; // uid -> [投稿時刻...]
  var lastTexts = {}; // uid -> [{t: 投稿時刻, text: 本文}...]
  var loudFrames = {}; // uid -> [{t: 時刻, loud: true/false}...] 直近の音量判定
  var loudHandled = {}; // uid -> true   すでに音量を下げた人
  var savedVolume = {}; // uid -> 下げる前の音量(戻す用)

  function reset() {
    hiddenUsers = {};
    commentTimes = {};
    lastTexts = {};
    loudFrames = {};
    loudHandled = {};
    savedVolume = {};
    renderPanel();
  }

  function myId() {
    return Number(global.__myUserId || 0);
  }

  function nameOf(uid) {
    try {
      var u = typeof global.findRosterUser === "function" ? global.findRosterUser(uid) : null;
      if (u && u.name) return u.name;
    } catch (e) {}
    return "user " + uid;
  }

  function notify(message, undoLabel, undo) {
    try {
      if (typeof global.toastAction === "function" && undo) {
        global.toastAction(message, undoLabel, undo, 8000);
      } else if (typeof global.toast === "function") {
        global.toast(message);
      }
    } catch (e) {}
    try {
      if (typeof global.koeChatSystem === "function") global.koeChatSystem(message);
    } catch (e) {}
  }

  /* ---- キック / リスナーに戻す ----
     自分の役割では判断しない(枠主でなくても要求は送る)。権限が無ければサーバーが拒否するので
     その結果を通知し、端末内の対処(非表示・音量)だけが残る。 */
  function roomAction(uid, action, reason) {
    if (action === "none" || uid === myId() || !global.currentRoomId) return;
    var roomId = global.currentRoomId;
    var label = action === "kick" ? "キック" : "リスナーに戻す";
    var api = action === "kick" ? "room_kick_user" : "reject_speaker";
    var run = function () {
      return global.callApi(api, roomId, String(uid));
    };
    run()
      .then(function (r) {
        if (r && r.ok) {
          notify(nameOf(uid) + " を自動で" + label + "しました(" + reason + ")");
          try {
            global.refreshRoomStateNow && global.refreshRoomStateNow();
          } catch (e) {}
        } else {
          var why = r && (r.status === 403 || r.status === 401) ? "権限がありません" : "失敗(HTTP " + ((r && r.status) || "?") + ")";
          notify(nameOf(uid) + " の" + label + "は実行できませんでした: " + why + "(" + reason + ")", "再試行", function () {
            roomAction(uid, action, reason);
          });
        }
      })
      .catch(function () {
        notify(nameOf(uid) + " の" + label + "に失敗しました(" + reason + ")");
      });
  }

  /* ---- スパムコメント ----
     荒らしと普通の人を区別するための決まり:
       - 枠に入った直後に届く「過去のコメント一覧」は数えない(履歴がまとめて届くため)
       - 時刻はコメントの投稿時刻(created_at)を使う。通信の遅れでまとめて届いても連投にはならない
       - 「w」「草」「88」のような 3 文字未満の相づちは、繰り返しの判定に使わない
       - 同じ文の繰り返しは 20 秒以内に限る(数分おきに同じ挨拶をしても対象外) */
  var SPAM_WINDOW_MS = 10000;
  var REPEAT_WINDOW_MS = 20000;
  var REPEAT_MIN_CHARS = 3;

  function commentTime(c, fallback) {
    var t = c && c.created_at ? Date.parse(String(c.created_at).replace(" ", "T")) : NaN;
    return isNaN(t) ? fallback : t;
  }

  function onComments(added) {
    if (!settings.spamEnabled || !Array.isArray(added) || !added.length) return;
    if (!global.__chatNotifiedInit) return; // 入室直後の最初の取得(過去の履歴)は判定に使わない
    var now = Date.now();
    added.forEach(function (c) {
      var uid = Number(c && c.user_id) || 0;
      if (!uid || uid === myId() || hiddenUsers[uid]) return;
      var t = commentTime(c, now);
      var text = String(c.text || "").trim();

      var times = (commentTimes[uid] = (commentTimes[uid] || []).filter(function (x) {
        return t - x < SPAM_WINDOW_MS;
      }));
      times.push(t);

      var texts = (lastTexts[uid] = (lastTexts[uid] || []).filter(function (x) {
        return t - x.t < REPEAT_WINDOW_MS;
      }));
      texts.push({ t: t, text: text });
      var same = 0;
      if (text.length >= REPEAT_MIN_CHARS) {
        same = texts.filter(function (x) {
          return x.text === text;
        }).length;
      }

      var tooMany = times.length >= settings.spamMaxPer10s;
      var repeated = same >= settings.spamSameText;
      if (!tooMany && !repeated) return;

      hiddenUsers[uid] = true;
      renderPanel();
      var reason = tooMany ? "10秒に" + times.length + "件の連投" : "同じ文を" + same + "回繰り返し";
      notify(nameOf(uid) + " のコメントを非表示にしました(" + reason + ")", "戻す", function () {
        unhide(uid);
      });
      redrawChat();
      roomAction(uid, settings.spamAction, "コメントの" + reason);
    });
  }

  function unhide(uid) {
    delete hiddenUsers[uid];
    commentTimes[uid] = [];
    lastTexts[uid] = [];
    redrawChat();
    renderPanel();
  }

  function redrawChat() {
    try {
      var log = document.getElementById("callChatLog");
      if (log) log.__sig = ""; // 描画キャッシュを無効にして描き直す
      if (typeof global.koeChatRender === "function") global.koeChatRender();
    } catch (e) {}
  }

  function isHidden(uid) {
    return !!hiddenUsers[Number(uid)];
  }

  /* ---- 爆音 ----
     大声で話す人と、音楽やノイズを流し続ける人を区別するための決まり:
       - しきい値は会話の音量(RMS 0.05〜0.15 程度)より十分高い(感度 5 で 0.44、最大感度でも 0.24)
       - 「設定した秒数の間、判定フレームの 85% 以上がしきい値超え」で爆音とみなす。
         人の声は子音や息継ぎで必ず途切れるので 85% には届きにくく、流しっぱなしの音は届く */
  var LOUD_RATIO = 0.85;

  function loudThreshold() {
    var lv = Math.max(1, Math.min(10, Number(settings.loudLevel) || 5));
    return 0.6 - (lv - 1) * 0.04;
  }

  function onLevel(uid, rms, now) {
    if (!settings.loudEnabled) return;
    uid = Number(uid);
    if (!uid || uid === myId() || loudHandled[uid]) return;
    var windowMs = Math.max(1, Number(settings.loudSeconds) || 2) * 1000;
    var frames = (loudFrames[uid] = (loudFrames[uid] || []).filter(function (f) {
      return now - f.t < windowMs;
    }));
    frames.push({ t: now, loud: rms >= loudThreshold() });
    if (frames.length < 6 || now - frames[0].t < windowMs * 0.8) return; // 判定に十分な長さが溜まるまで待つ
    var loudCount = frames.filter(function (f) {
      return f.loud;
    }).length;
    if (loudCount / frames.length >= LOUD_RATIO) {
      loudFrames[uid] = [];
      handleLoud(uid);
    }
  }

  function handleLoud(uid) {
    loudHandled[uid] = true;
    var before = 1;
    try {
      before = typeof global.koeGetVol === "function" ? global.koeGetVol(uid) : 1;
    } catch (e) {}
    savedVolume[uid] = before;
    renderPanel();
    setVolumeTemporary(uid, Math.max(0, Math.min(1, settings.loudVolume / 100)));
    notify(nameOf(uid) + " の音量を " + settings.loudVolume + "% に下げました(爆音検知)", "戻す", function () {
      restoreVolume(uid);
    });
    roomAction(uid, settings.loudAction, "爆音");
  }

  /* 音量を一時的に変える(端末の保存値 koe_vol_* は変えない → 次回の枠には影響しない) */
  function setVolumeTemporary(uid, v) {
    try {
      (global.__koeGains && global.__koeGains[uid] ? global.__koeGains[uid] : []).forEach(function (g) {
        g.gain.value = v;
      });
      (global.__koeRemoteAudio && global.__koeRemoteAudio[uid] ? global.__koeRemoteAudio[uid] : []).forEach(
        function (a) {
          if (!a.__koeGain) a.volume = Math.min(1, v);
        },
      );
    } catch (e) {}
  }

  function restoreVolume(uid) {
    delete loudHandled[uid];
    setVolumeTemporary(uid, savedVolume[uid] === undefined ? 1 : savedVolume[uid]);
    delete savedVolume[uid];
    renderPanel();
  }

  /* ---- 設定 UI ----
     設定項目は data-guard="キー" を持つ input/select。設定ページと通話中パネルの両方にあり、
     どちらで変えてももう一方へ反映する。 */
  function bindSettingsUI() {
    var controls = document.querySelectorAll("[data-guard]");
    controls.forEach(function (el) {
      var key = el.getAttribute("data-guard");
      if (!(key in DEFAULTS) || el.__guardBound) return;
      el.__guardBound = true;
      el.addEventListener("change", function () {
        var p = {};
        p[key] = el.type === "checkbox" ? el.checked : typeof DEFAULTS[key] === "number" ? Number(el.value) : el.value;
        saveSettings(p);
        syncSettingsUI();
      });
    });
    syncSettingsUI();
  }

  function syncSettingsUI() {
    document.querySelectorAll("[data-guard]").forEach(function (el) {
      var key = el.getAttribute("data-guard");
      if (!(key in settings)) return;
      if (el.type === "checkbox") el.checked = !!settings[key];
      else el.value = String(settings[key]);
    });
  }

  /* ---- 通話中パネル(Guard マーク) ----
     いま対処中の人を一覧し、その場で「戻す」「キック」「リスナーに戻す」ができる。 */
  function guardedUsers() {
    var ids = {};
    Object.keys(hiddenUsers).forEach(function (u) {
      ids[u] = ids[u] || {};
      ids[u].hidden = true;
    });
    Object.keys(loudHandled).forEach(function (u) {
      ids[u] = ids[u] || {};
      ids[u].loud = true;
    });
    return Object.keys(ids).map(function (u) {
      return { uid: Number(u), hidden: !!ids[u].hidden, loud: !!ids[u].loud };
    });
  }

  function esc(t) {
    return String(t).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }

  function renderPanel() {
    var list = document.getElementById("callGuardList");
    var badge = document.getElementById("callGuardBadge");
    var users = guardedUsers();
    if (badge) {
      badge.textContent = String(users.length);
      badge.style.display = users.length ? "flex" : "none";
    }
    if (!list) return;
    if (!users.length) {
      list.innerHTML = '<p class="callv2-note">いま対処している人はいません。</p>';
      return;
    }
    list.innerHTML = users
      .map(function (u) {
        var why = [];
        if (u.hidden) why.push("コメント非表示");
        if (u.loud) why.push("音量 " + settings.loudVolume + "%");
        return (
          '<div class="koe-guard-row" data-uid="' + u.uid + '">' +
          '<div class="koe-guard-name">' + esc(nameOf(u.uid)) + '<small>' + esc(why.join("・")) + "</small></div>" +
          '<div class="koe-guard-btns">' +
          '<button class="btn-secondary" data-act="restore">戻す</button>' +
          '<button class="btn-secondary" data-act="listener">リスナーに</button>' +
          '<button class="btn-danger" data-act="kick">キック</button>' +
          "</div></div>"
        );
      })
      .join("");
  }

  function onPanelClick(e) {
    var b = e.target && e.target.closest ? e.target.closest("[data-act]") : null;
    if (!b) return;
    var row = b.closest("[data-uid]");
    var uid = row ? Number(row.getAttribute("data-uid")) : 0;
    if (!uid) return;
    var act = b.getAttribute("data-act");
    if (act === "restore") {
      if (hiddenUsers[uid]) unhide(uid);
      if (loudHandled[uid]) restoreVolume(uid);
      renderPanel();
    } else {
      roomAction(uid, act, "手動");
    }
  }

  function bindPanel() {
    var list = document.getElementById("callGuardList");
    if (list && !list.__guardBound) {
      list.__guardBound = true;
      list.addEventListener("click", onPanelClick);
    }
    renderPanel();
  }

  function init() {
    bindSettingsUI();
    bindPanel();
  }

  if (typeof document !== "undefined") {
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
    else init();
  }

  global.KoeGuard = {
    onComments: onComments,
    isHidden: isHidden,
    unhide: unhide,
    onLevel: onLevel,
    restoreVolume: restoreVolume,
    reset: reset,
    renderPanel: renderPanel,
    settings: function () {
      return settings;
    },
    save: saveSettings,
  };
})(window);
