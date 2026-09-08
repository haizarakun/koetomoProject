/*
 * KoeSched — 定期処理(ポーリング/解析ループ)の中央スケジューラ
 *
 * 目的
 *   画面のあちこちに散らばった setInterval を 1 か所で管理し、
 *   端末の発熱・電池消費を抑えつつ「画面に戻った瞬間は即時反映」を保証する。
 *
 * 方針
 *   - 画面が見えている間  : 指定した間隔(ms)で実行する
 *   - 画面が隠れている間  : hiddenMs があればその間隔に落とす。無ければ実行しない
 *   - 画面に戻った瞬間    : runOnShow が true のタスクを即座に 1 回実行し、通常間隔へ戻す
 *   - 実行中(Promise 未解決)のタスクは重ねて起動しない(通信の詰まり防止)
 *
 * 使い方
 *   KoeSched.start("roomState", pollRoomState, { ms: 1000, hiddenMs: 4000, runOnShow: true });
 *   KoeSched.stop("roomState");
 *   KoeSched.trigger("roomState");   // 手動で今すぐ 1 回
 */
(function (global) {
  "use strict";

  /** @type {Object<string, Task>} */
  var tasks = {};

  /**
   * @typedef {Object} Task
   * @property {Function} fn         実行する処理(同期でも Promise を返してもよい)
   * @property {number}   ms         画面表示中の実行間隔
   * @property {number}   hiddenMs   画面非表示中の実行間隔(0 = 非表示中は実行しない)
   * @property {boolean}  runOnShow  画面復帰時に即実行するか
   * @property {number}   timer      現在の setInterval ID(0 = 停止中)
   * @property {boolean}  busy       fn が実行中か
   */

  function isHidden() {
    return typeof document !== "undefined" && !!document.hidden;
  }

  /** 現在の表示状態に合った間隔を返す。0 なら「今は回さない」。 */
  function currentInterval(task) {
    return isHidden() ? task.hiddenMs : task.ms;
  }

  /** タスクを 1 回実行する。多重実行と例外は内部で吸収する。 */
  function run(task) {
    if (task.busy) return;
    task.busy = true;
    var done = function () {
      task.busy = false;
    };
    try {
      var result = task.fn();
      if (result && typeof result.then === "function") {
        result.then(done, done);
      } else {
        done();
      }
    } catch (e) {
      done();
    }
  }

  /** 表示状態に応じて setInterval を張り直す。 */
  function arm(task) {
    if (task.timer) {
      clearInterval(task.timer);
      task.timer = 0;
    }
    var interval = currentInterval(task);
    if (interval > 0) {
      task.timer = setInterval(function () {
        run(task);
      }, interval);
    }
  }

  /**
   * 定期タスクを登録して開始する。同名のタスクがあれば置き換える。
   * @param {string}   name
   * @param {Function} fn
   * @param {{ms:number, hiddenMs?:number, runOnShow?:boolean, immediate?:boolean}} opts
   */
  function start(name, fn, opts) {
    stop(name);
    var task = {
      fn: fn,
      ms: Math.max(50, opts.ms | 0),
      hiddenMs: Math.max(0, opts.hiddenMs | 0),
      runOnShow: opts.runOnShow !== false,
      timer: 0,
      busy: false,
    };
    tasks[name] = task;
    if (opts.immediate) run(task);
    arm(task);
    return name;
  }

  /** タスクを停止して破棄する。 */
  function stop(name) {
    var task = tasks[name];
    if (!task) return;
    if (task.timer) clearInterval(task.timer);
    delete tasks[name];
  }

  /** 今すぐ 1 回実行する(間隔は変えない)。 */
  function trigger(name) {
    var task = tasks[name];
    if (task) run(task);
  }

  function isRunning(name) {
    return !!tasks[name];
  }

  /* 画面の表示/非表示が切り替わったら全タスクの間隔を見直し、復帰時は即時反映する。 */
  if (typeof document !== "undefined") {
    document.addEventListener("visibilitychange", function () {
      var visible = !isHidden();
      Object.keys(tasks).forEach(function (name) {
        var task = tasks[name];
        arm(task);
        if (visible && task.runOnShow) run(task);
      });
    });
  }

  global.KoeSched = { start: start, stop: stop, trigger: trigger, isRunning: isRunning };
})(window);
