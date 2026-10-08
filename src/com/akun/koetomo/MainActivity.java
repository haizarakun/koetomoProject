package com.akun.koetomo;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Movie;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Rational;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private ImageThumbCache thumbCache;
    private static final int FILE_CHOOSER_REQUEST = 100;
    /* access modifiers changed from: private */
    public ValueCallback<Uri[]> filePathCallback;
    public boolean inCall = false;
    /* access modifiers changed from: private */
    public TextView overlayAvatar = null;
    /* access modifiers changed from: private */
    public TextView overlayLabel = null;
    public boolean pipWanted = false;
    /* 通知・小窓のボタンから届く操作に付ける合言葉。他のアプリが同じ名前の通知を送って通話を切るのを防ぐ */
    private static volatile String CALL_TOKEN = null;
    static synchronized String callActionToken() {
        if (CALL_TOKEN == null) CALL_TOKEN = java.util.UUID.randomUUID().toString();
        return CALL_TOKEN;
    }
    public volatile boolean uiForeground = true; /* アプリが前面にある間は重ね表示を出さない */
    public String overlayIconUrl = "";
    public android.graphics.drawable.BitmapDrawable overlayIconBd = null;
    public int micState = 0; /* 0=オン 1=ミュート 2=聞き専 */
    /* access modifiers changed from: private */
    public View speakerOverlayView = null;
    /* access modifiers changed from: private */
    public WebView webView;

    /* access modifiers changed from: private */
    public void bringAppToFront() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(268566528);
            startActivity(intent);
        } catch (Exception e) {
        }
    }

    /* access modifiers changed from: private */
    public String ovInitial(String str) {
        return (str == null || str.length() == 0) ? "?" : str.substring(0, 1).toUpperCase();
    }

    public boolean hasOverlayPermission() {
        try {
            if (Build.VERSION.SDK_INT < 23) {
                return true;
            }
            return Settings.canDrawOverlays(this);
        } catch (Exception e) {
            return false;
        }
    }

    /* 画面に出ている重ね表示(この画面が作り直されても残っているものを含めて1つだけ) */
    private static View sLiveOverlay = null;
    private static WindowManager sLiveOverlayWm = null;

    private static void removeOverlayView(View v) {
        if (v == null) return;
        try {
            WindowManager wm = (sLiveOverlay == v && sLiveOverlayWm != null) ? sLiveOverlayWm : null;
            if (wm != null) wm.removeViewImmediate(v);
        } catch (Exception e) {
        }
        if (sLiveOverlay == v) { sLiveOverlay = null; sLiveOverlayWm = null; }
    }

    public void hideSpeakerOverlay() {
        runOnUiThread(new Runnable() {
            public void run() {
                View v = MainActivity.this.speakerOverlayView;
                // 取り外しに失敗しても参照は必ず空にする(残ると、次から「表示済み」と誤解して二度と出なくなる)
                MainActivity.this.speakerOverlayView = null;
                MainActivity.this.overlayAvatar = null;
                MainActivity.this.overlayLabel = null;
                removeOverlayView(v);
            }
        });
    }

    /* access modifiers changed from: protected */
    public void onActivityResult(int i, int i2, Intent intent) {
        if (i == FILE_CHOOSER_REQUEST) {
            Uri[] uriArr = (i2 != -1 || intent == null || intent.getData() == null) ? null : new Uri[]{intent.getData()};
            if (this.filePathCallback != null) {
                this.filePathCallback.onReceiveValue(uriArr);
                this.filePathCallback = null;
                return;
            }
            return;
        }
        super.onActivityResult(i, i2, intent);
    }

    /*
     * このアプリはSPA(WebView内はJSの画面遷移のみで実ページ遷移が無い)ため
     * webView.canGoBack()は通常常にfalseになる。以前はfalseの場合
     * super.onBackPressed()(=Activity終了)を直接呼んでいたため、通話中に
     * バックキーを押すとonUserLeaveHint()経由でtryEnterPip()が発火し、
     * ホーム画面上に通話がPinP表示される「ホーム画面に戻る」不具合になっていた。
     * JS側のwindow.__koeHandleBack()にまず処理させ(開いているモーダルを閉じる/
     * 展開中の通話をアプリ内最小化/ホームタブへ戻る、のいずれかを実施しtrueを返す)、
     * JS側で処理しきれなかった場合のみmoveTaskToBack()でタスクをバックグラウンドへ
     * (finish()はしない。プロセス/通話状態を破棄しないため)。
     */
    public void onBackPressed() {
        if (this.webView == null) { super.onBackPressed(); return; }
        if (this.webView.canGoBack()) {
            this.webView.goBack();
            return;
        }
        try {
            this.webView.evaluateJavascript("(function(){try{return !!(window.__koeHandleBack && window.__koeHandleBack());}catch(e){return false;}})()", new ValueCallback<String>() {
                public void onReceiveValue(String value) {
                    if (!"true".equals(value)) {
                        try {
                            MainActivity.this.moveTaskToBack(true);
                        } catch (Exception e) {
                        }
                    }
                }
            });
        } catch (Exception e) {
            try {
                moveTaskToBack(true);
            } catch (Exception e2) {
            }
        }
    }

    /* access modifiers changed from: protected */
    // ==== 不正防止: 改変・再署名された APK / デバッグ可能ビルドでは起動させない ====
    // リリース署名証明書の SHA-256(apksigner verify --print-certs と同じ値)
    static final String RELEASE_CERT_SHA256 = "8d638778babadabfa3f90cec5fbf0429db8cb595a4fd732bc02d99a79993f292";

    private boolean integrityOk() {
        try {
            android.content.pm.ApplicationInfo ai = getApplicationInfo();
            if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) return false;
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES);
            if (pi.signatures == null || pi.signatures.length != 1) return false;
            byte[] dg = java.security.MessageDigest.getInstance("SHA-256").digest(pi.signatures[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : dg) sb.append(String.format("%02x", b & 0xff));
            return RELEASE_CERT_SHA256.equalsIgnoreCase(sb.toString());
        } catch (Throwable t) {
            return false;
        }
    }

    private View splashView;
    /* スプラッシュのGIFは onDraw で毎フレーム自分を再描画し続ける。消すときにこれを true にして
       再描画ループを止めないと、透明になった後もUIスレッドを回し続けて画面が固まる。 */
    private final boolean[] splashGifStop = new boolean[]{false};
    private static final long BOOT_T0 = android.os.SystemClock.elapsedRealtime();
    private long tCreated = 0;

    /** 起動の内訳を診断ログに残す(遅い箇所の切り分け用) */
    private void bootLog(String phase) {
        try {
            long proc = BOOT_T0;
            long now = android.os.SystemClock.elapsedRealtime();
            final String line = "[BOOT] " + phase + " proc+" + (now - proc) + "ms create+" + (tCreated > 0 ? (now - tCreated) : 0) + "ms";
            if (this.apiBridge != null) this.apiBridge.session.dispatch("js_diag_log", new org.json.JSONArray().put(line));
        } catch (Throwable ig) {}
    }

    /** 起動直後に即描画する簡易スプラッシュ(画像デコードなしで軽い) */
    private View buildSplash() {
        try {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(android.view.Gravity.CENTER);
            box.setBackgroundColor(0xFF111111);
            /* ロード表示: マスコットのアニメGIFを出す。
               Android 9(API28)以降は AnimatedImageDrawable で再生する。これは描画スレッドで
               アニメーションするので、UIスレッドを回し続けず、ハードウェア描画の WebView とも
               衝突しない。以前の「Movie を毎フレーム描くソフトウェア層の自作View」は WebView と
               重なってスプラッシュを消した後に黒く固まっていた(「GIFのあとにフリーズ」)。
               API28 未満の古い端末だけ、従来の Movie 方式にフォールバックする。 */
            try {
                int px = (int) (getResources().getDisplayMetrics().density * 132);
                int mb = (int) (getResources().getDisplayMetrics().density * 10);
                LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(px, px);
                glp.bottomMargin = mb;
                boolean shown = false;
                if (Build.VERSION.SDK_INT >= 28) {
                    /* ビルドの android.jar が古く ImageDecoder/AnimatedImageDrawable を直接参照できないので、
                       実行時にリフレクションで呼ぶ(端末が API28+ なら必ず存在する)。 */
                    try {
                        Class<?> idc = Class.forName("android.graphics.ImageDecoder");
                        Class<?> srcC = Class.forName("android.graphics.ImageDecoder$Source");
                        Object src = idc.getMethod("createSource", android.content.res.AssetManager.class, String.class)
                                .invoke(null, getAssets(), "mascot_loading.gif");
                        Object d = idc.getMethod("decodeDrawable", srcC).invoke(null, src);
                        android.widget.ImageView iv = new android.widget.ImageView(this);
                        iv.setImageDrawable((android.graphics.drawable.Drawable) d);
                        iv.setLayoutParams(glp);
                        Class<?> aidC = Class.forName("android.graphics.drawable.AnimatedImageDrawable");
                        if (aidC.isInstance(d)) {
                            try { aidC.getMethod("setRepeatCount", int.class).invoke(d, -1); } catch (Throwable t) {} // -1 = REPEAT_INFINITE
                            aidC.getMethod("start").invoke(d);
                        }
                        box.addView(iv);
                        shown = true;
                    } catch (Throwable ig) {}
                }
                if (!shown) {
                    // 古い端末向けフォールバック: 従来の Movie 方式(停止フラグつき)
                    java.io.InputStream mis = getAssets().open("mascot_loading.gif");
                    java.io.ByteArrayOutputStream mbo = new java.io.ByteArrayOutputStream();
                    byte[] mbuf = new byte[8192]; int mr;
                    while ((mr = mis.read(mbuf)) != -1) mbo.write(mbuf, 0, mr);
                    mis.close();
                    byte[] mbytes = mbo.toByteArray();
                    final Movie movie = Movie.decodeByteArray(mbytes, 0, mbytes.length);
                    if (movie != null && movie.width() > 0) {
                        final boolean[] gifStop = this.splashGifStop;
                        View gif = new View(this) {
                            long start = 0L;
                            @Override protected void onDraw(Canvas c) {
                                if (gifStop[0]) return;
                                long now = SystemClock.uptimeMillis();
                                if (start == 0L) start = now;
                                int dur = movie.duration(); if (dur <= 0) dur = 1000;
                                movie.setTime((int) ((now - start) % dur));
                                int vw = getWidth(), vh = getHeight();
                                float sc = Math.min(vw / (float) movie.width(), vh / (float) movie.height());
                                c.save();
                                c.translate((vw - movie.width() * sc) / 2f, (vh - movie.height() * sc) / 2f);
                                c.scale(sc, sc);
                                movie.draw(c, 0, 0);
                                c.restore();
                                if (!gifStop[0]) postInvalidateOnAnimation();
                            }
                        };
                        gif.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                        gif.setLayoutParams(glp);
                        box.addView(gif);
                    }
                }
            } catch (Exception ig) {
            }
            TextView t = new TextView(this);
            t.setText("KoeTomo+");
            t.setTextSize(26);
            t.setTextColor(0xFFEDEFF5);
            t.setGravity(android.view.Gravity.CENTER);
            box.addView(t);
            TextView s = new TextView(this);
            s.setText("読み込み中…");
            s.setTextSize(12);
            s.setTextColor(0xFF8B93A7);
            s.setGravity(android.view.Gravity.CENTER);
            s.setPadding(0, 18, 0, 0);
            box.addView(s);
            return box;
        } catch (Exception e) {
            return null;
        }
    }

    /** WebView 側の描画準備ができたらスプラッシュを消す(JS から uiReady() で呼ばれる) */
    public void hideSplash() {
        try {
            final View v = this.splashView;
            if (v == null) return;
            bootLog("画面表示");
            this.splashView = null;
            splashGifStop[0] = true; // 旧仕様の名残(静止画化したので実害なし)
            runOnUiThread(new Runnable() {
                public void run() {
                    // フェードは使わず、即座に消して確実に取り除く。
                    try { v.setVisibility(View.GONE); } catch (Exception e) {}
                    try {
                        android.view.ViewParent p = v.getParent();
                        if (p instanceof android.view.ViewGroup) ((android.view.ViewGroup) p).removeView(v);
                    } catch (Exception e) {}
                    // WebView を確実に前面へ出して描き直させる(念のためレイアウトも要求)
                    try {
                        if (webView != null) {
                            webView.setVisibility(View.VISIBLE);
                            webView.bringToFront();
                            webView.requestLayout();
                            webView.invalidate();
                        }
                    } catch (Exception e) {}
                }
            });
        } catch (Exception e) {
        }
    }

    private void showTampered() {
        TextView tv = new TextView(this);
        tv.setText("このアプリは正規の署名ではないため起動できません。\n\nGitHub の Releases から公式の APK をインストールしてください。\n\ngithub.com/haizarakun/koetomoProject");
        tv.setTextSize(16);
        tv.setPadding(48, 96, 48, 48);
        tv.setTextColor(0xFFEEEEEE);
        tv.setBackgroundColor(0xFF14161C);
        setContentView(tv);
    }

    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        registerCallActionReceiver();
        if (!integrityOk()) {
            showTampered();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            ArrayList arrayList = new ArrayList();
            if (checkSelfPermission("android.permission.RECORD_AUDIO") != 0) {
                arrayList.add("android.permission.RECORD_AUDIO");
            }
            if (checkSelfPermission("android.permission.CAMERA") != 0) {
                arrayList.add("android.permission.CAMERA");
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
                arrayList.add("android.permission.POST_NOTIFICATIONS");
            }
            // Bluetooth のヘッドセットで通話するために必要(Android 12 以降)
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != 0) {
                arrayList.add("android.permission.BLUETOOTH_CONNECT");
            }
            // Android 6〜9 は画像・音声の保存に保存領域の許可が要る(10 以降は不要)
            if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != 0) {
                arrayList.add("android.permission.WRITE_EXTERNAL_STORAGE");
            }
            if (!arrayList.isEmpty()) {
                requestPermissions((String[]) arrayList.toArray(new String[0]), 1);
            }
        }
        // 起動直後の白い画面をなくす: ウィンドウとWebViewの地色を先にアプリの背景色にして、
        // 画面が用意できるまでネイティブ側の簡易スプラッシュを重ねる(体感速度)。
        try {
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF111111));
        } catch (Exception e) {
        }
        this.webView = new WebView(this);
        try { this.webView.setBackgroundColor(0xFF14161C); } catch (Exception e) {}
        android.widget.FrameLayout rootLayout = new android.widget.FrameLayout(this);
        rootLayout.setBackgroundColor(0xFF111111);
        rootLayout.addView(this.webView, new android.widget.FrameLayout.LayoutParams(-1, -1));
        this.splashView = buildSplash();
        if (this.splashView != null) {
            rootLayout.addView(this.splashView, new android.widget.FrameLayout.LayoutParams(-1, -1));
        }
        setContentView(rootLayout);
        // 画面が出ないまま取り残されないよう、合図が来なくても6秒で必ず消す
        try {
            this.webView.postDelayed(new Runnable() { public void run() { hideSplash(); } }, 6000);
        } catch (Exception e) {
        }
        WebSettings settings = this.webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);   // file:///android_asset の読み込みには影響しない
        // セキュリティ強化: ローカルHTMLから任意ファイル読み取り/ユニバーサル(クロスオリジン)アクセスを禁止し、
        // フォーム/パスワードの自動保存や位置情報も無効化する。
        try {
            settings.setAllowFileAccessFromFileURLs(false);
            settings.setAllowUniversalAccessFromFileURLs(false);
        } catch (Exception e) {
        }
        try {
            settings.setAllowContentAccess(false); // content:// 経由で他アプリのProviderを読ませない
        } catch (Exception e) {
        }
        try {
            settings.setSaveFormData(false);
        } catch (Exception e) {
        }
        try {
            settings.setGeolocationEnabled(false);
        } catch (Exception e) {
        }
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setDatabaseEnabled(true);
        // キャッシュは溜め込まない方針: 起動時に一時ファイルが上限を超えていたら古い順に捨てる。
        // あわせて Keystore を裏で温めておく(JS からの secureLoad が同期呼び出しで待たされるのを防ぐ)
        try {
            final android.content.Context cctx = getApplicationContext();
            new Thread(new Runnable() {
                public void run() {
                    try { new SecureStore(cctx).encrypt("w"); } catch (Throwable ig) {}
                    try { KoeApiBridge.trimCache(cctx, KoeApiBridge.CACHE_LIMIT_BYTES); } catch (Throwable ig) {}
                }
            }).start();
        } catch (Exception e) {
        }
        // どんな画面サイズ/密度の端末(タブレット・折りたたみ・Meta Questの2Dパネル等)でも
        // CSSのviewportメタタグ通りに正しく表示させるため、WebViewの幅計算をwide viewport方式にする。
        try {
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
        } catch (Exception e) {
        }
        // スクロールの滑らかさ向上(オフスクリーン先読みラスタライズ)
        try {
            // オフスクリーン先読みラスタライズは常時GPUで余分に描画して発熱の一因になるため無効化。
            // 画面外のスキップは CSS の content-visibility 側で行う。
            if (Build.VERSION.SDK_INT >= 23) {
                settings.setOffscreenPreRaster(false);
            }
        } catch (Exception e) {
        }
        WebView.setWebContentsDebuggingEnabled(false);
        this.thumbCache = new ImageThumbCache(this);
        this.webView.setWebViewClient(new WebViewClient() {
            private boolean handleNav(String url) {
                try {
                    if (url == null) return false;
                    String l = url.trim().toLowerCase();
                    if (l.startsWith("file:///android_asset/")) return false;
                    if (l.startsWith("http://") || l.startsWith("https://")) {
                        Intent intent = new Intent("android.intent.action.VIEW", Uri.parse(url));
                        intent.addFlags(268435456);
                        MainActivity.this.startActivity(intent);
                        return true;
                    }
                    return true;
                } catch (Exception e) { return true; }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return handleNav(url); }
            /** アイコン画像(koe_w= 付き)はネイティブで縮小したサムネイルを返す。発熱・メモリ対策。 */
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v, android.webkit.WebResourceRequest req) {
                try {
                    String url = req != null && req.getUrl() != null ? req.getUrl().toString() : null;
                    if (ImageThumbCache.handles(url)) {
                        android.webkit.WebResourceResponse thumb = MainActivity.this.thumbCache.serve(url);
                        if (thumb != null) return thumb;
                    }
                } catch (Throwable ignored) {
                }
                return super.shouldInterceptRequest(v, req);
            }
            @Override public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                MainActivity.this.bootLog("ページ読み込み完了");
                MainActivity.this.handleIncomingIntent(MainActivity.this.getIntent());
            }
        });
        this.webView.setWebChromeClient(new WebChromeClient() {
            public void onPermissionRequest(final PermissionRequest permissionRequest) {
                MainActivity.this.runOnUiThread(new Runnable() {
                    public void run() {
                        // 許可するのは同梱アセット(file://)から出た要求だけ。多層防御。
                        try {
                            android.net.Uri org = permissionRequest.getOrigin();
                            String o = org == null ? "" : org.toString();
                            if (!(o.startsWith("file://") || o.length() == 0)) {
                                permissionRequest.deny();
                                return;
                            }
                        } catch (Exception ig) {
                        }
                        String[] res = permissionRequest.getResources();
                        java.util.ArrayList<String> allow = new java.util.ArrayList<String>();
                        if (res != null) {
                            for (String r : res) {
                                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) || PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                                    allow.add(r);
                                }
                            }
                        }
                        if (allow.isEmpty()) {
                            permissionRequest.deny();
                        } else {
                            permissionRequest.grant(allow.toArray(new String[0]));
                        }
                    }
                });
            }

            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> valueCallback, WebChromeClient.FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue((Uri[]) null);
                }
                ValueCallback unused = MainActivity.this.filePathCallback = valueCallback;
                try {
                    Intent intent = new Intent("android.intent.action.GET_CONTENT");
                    intent.addCategory("android.intent.category.OPENABLE");
                    intent.setType("image/*");
                    MainActivity.this.startActivityForResult(Intent.createChooser(intent, "画像を選択"), MainActivity.FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    ValueCallback unused2 = MainActivity.this.filePathCallback = null;
                    return false;
                }
            }
        });
        this.apiBridge = new KoeApiBridge(this.webView, new KoeSession(getApplicationContext()));
        this.webView.addJavascriptInterface(this.apiBridge, "AndroidApi");
        this.tCreated = android.os.SystemClock.elapsedRealtime();
        bootLog("onCreate完了(WebView生成済み)");
        this.webView.loadUrl("file:///android_asset/web/index.html");
    }

    /* access modifiers changed from: protected */
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (this.webView != null) {
            this.webView.onResume();
            this.webView.resumeTimers();
        }
        handleIncomingIntent(intent);
    }

    /*
     * https://koetomo.fun/users/{id} (App Link) および koetomo://profile/{id} (旧カスタムスキーム、
     * 後方互換のため維持) のディープリンク受信、および通話中通知タップ時の
     * 「通話画面に戻る」JS側シグナル送信をまとめて処理する。
     * onPageFinished(初回起動時)とonNewIntent(既に起動中/バックグラウンド復帰時)の
     * 両方から呼ばれる。
     */
    private void handleIncomingIntent(Intent intent) {
        try {
            if (this.webView == null || intent == null) {
                return;
            }
            Uri data = intent.getData();
            // X(Twitter) OAuth のコールバック: koetomoplus://xauth?code=...&state=...
            if (data != null && "koetomoplus".equals(data.getScheme()) && "xauth".equals(data.getHost())) {
                String code = data.getQueryParameter("code");
                String state = data.getQueryParameter("state");
                if (this.apiBridge != null) {
                    this.apiBridge.onXAuthCode(code, state);
                }
                setIntent(new Intent(intent).setData(null));
                return;
            }
            // 通知タップ: 通知ページを開く
            String openPage = intent.getStringExtra("koe_open_page");
            if (openPage != null && openPage.length() > 0) {
                // 他アプリからも startActivity できるため、渡された値は既知のページ名だけに限定する
            if (!openPage.matches("[a-z_]{1,24}")) return;
            final String js = "window.__koeOpenPageFromNotif && window.__koeOpenPageFromNotif(" + org.json.JSONObject.quote(openPage) + ")";
                this.webView.post(new Runnable() {
                    public void run() {
                        try {
                            MainActivity.this.webView.evaluateJavascript(js, (ValueCallback) null);
                        } catch (Exception e) {
                        }
                    }
                });
                intent.removeExtra("koe_open_page");
                setIntent(intent);
                return;
            }
            boolean isHttpsProfileLink = data != null && ("https".equals(data.getScheme()) || "http".equals(data.getScheme())) && "koetomo.fun".equalsIgnoreCase(data.getHost());
            boolean isCustomSchemeProfileLink = data != null && "koetomo".equals(data.getScheme()) && "profile".equals(data.getHost());
            if (isHttpsProfileLink || isCustomSchemeProfileLink) {
                String id = null;
                if (isHttpsProfileLink) {
                    java.util.List<String> segs = data.getPathSegments();
                    if (segs != null && segs.size() >= 2 && "users".equals(segs.get(0))) {
                        id = segs.get(1);
                    }
                } else {
                    String path = data.getPath();
                    if (path != null && path.length() > 1) {
                        id = path.substring(1);
                    }
                    if (id == null || id.length() == 0) {
                        java.util.List<String> segs = data.getPathSegments();
                        if (segs != null && !segs.isEmpty()) {
                            id = segs.get(0);
                        }
                    }
                }
                if (id != null && id.length() > 0 && id.matches("\\d{1,12}")) {
                    // ディープリンクのIDは数字のみ許可(JSへの文字列注入を防ぐ)
                    final String js = "window.__koeOpenProfileFromLink && window.__koeOpenProfileFromLink('" + id + "')";
                    this.webView.post(new Runnable() {
                        public void run() {
                            try {
                                MainActivity.this.webView.evaluateJavascript(js, (ValueCallback) null);
                            } catch (Exception e) {
                            }
                        }
                    });
                }
                setIntent(new Intent(intent).setData(null));
                return;
            }
        } catch (Exception e) {
        }
        try {
            if (this.webView != null) {
                this.webView.post(new Runnable() {
                    public void run() {
                        try {
                            MainActivity.this.webView.evaluateJavascript("window.__koeShowCallIfActive && window.__koeShowCallIfActive()", (ValueCallback) null);
                        } catch (Exception e) {
                        }
                    }
                });
            }
        } catch (Exception e) {
        }
    }

    public void onRequestPermissionsResult(int i, String[] strArr, int[] iArr) {
        super.onRequestPermissionsResult(i, strArr, iArr);
        try {
            if (this.webView != null) {
                this.webView.post(new Runnable() {
                    public void run() {
                        try {
                            MainActivity.this.webView.evaluateJavascript("window.__onPermResult && window.__onPermResult()", (ValueCallback) null);
                        } catch (Exception e) {
                        }
                    }
                });
            }
        } catch (Exception e) {
        }
    }

    /* access modifiers changed from: protected */
    public void onDestroy() {
        try { if (callActionReceiver != null) { unregisterReceiver(callActionReceiver); callActionReceiver = null; } } catch (Throwable ig) {}
        try { stopBgNotifPoller(); } catch (Exception e) {}
        // アプリを閉じたときに、自分が開いたままの枠を閉じる（残ると次回の枠作成が拒否される）
        try {
            if (isFinishing() && this.apiBridge != null && this.apiBridge.session != null) {
                this.apiBridge.session.closeMyRoomOnExit();
            }
        } catch (Exception e) {}
        try {
            View ov = this.speakerOverlayView;
            this.speakerOverlayView = null;
            removeOverlayView(ov);
            removeOverlayView(sLiveOverlay);
        } catch (Exception e) {}
        try {
            // 通話を終える形で閉じるなら、通話中の常駐通知も残さない
            if (isFinishing()) stopService(new Intent(this, CallForegroundService.class));
        } catch (Exception e) {}
        try {
            // 通話中に閉じても、ロックや通話モードを残さない
            if (isFinishing() && this.apiBridge != null && this.apiBridge.isCallAudioActive()) this.apiBridge.stopCallAudio();
            if (isFinishing() && this.apiBridge != null) this.apiBridge.stopReceiverBeat();
        } catch (Throwable ig) {}
        try {
            if (this.webView != null && isFinishing()) {
                this.webView.loadUrl("about:blank");
                this.webView.destroy();
            }
        } catch (Exception e) {}
        super.onDestroy();
    }

    /* access modifiers changed from: protected */
    /* 通話中に本当に裏へ回ったとき、設定が「他のアプリの上に表示」なら丸いアイコンを出す。
       設定は画面側(localStorage)にあるので、画面側へ聞いてから出す。 */
    private void showOverlayIfWanted() {
        try {
            if (this.webView == null) return;
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return;
            this.webView.evaluateJavascript(
                "(function(){try{return (localStorage.getItem('koe_callmini')||'bubble')+'|'+String(window.__lastSpeakerName||'通話中').split('|~|').join('')+'|~|'+(window.__lastSpeakerIcon||'');}catch(e){return 'bubble|';}})()",
                new android.webkit.ValueCallback<String>() {
                    public void onReceiveValue(String v) {
                        try {
                            if (v == null) return;
                            String t = new org.json.JSONArray("[" + v + "]").getString(0); // evaluateJavascript はJSON形式の文字列で返すので、正しく読み戻す
                            if (!t.startsWith("overlay|")) return;
                            String name = t.substring(8);
                            showSpeakerOverlay(name.length() == 0 ? "通話中" : name);
                            if (webView != null) webView.evaluateJavascript("window.__overlayActive=true", null);
                        } catch (Throwable ig) {
                        }
                    }
                });
        } catch (Throwable ig) {
        }
    }

    public void onResume() {
        super.onResume();
        uiForeground = true;
        try { hideSpeakerOverlay(); } catch (Throwable ig) {} /* アプリを開いている間は重ね表示を出さない */
        stopBgNotifPoller();
        try { KoeNotifyService.stop(getApplicationContext()); } catch (Exception ig) {}
        if (this.webView != null) {
            this.webView.onResume();
            this.webView.resumeTimers();
        }
    }

    // ---- バックグラウンド通知ポーリング ----
    // アプリが裏に回っている間(WebViewのJSタイマーは止まる)、ネイティブ側で60秒ごとに
    // 通知一覧を取得し、新着があれば Android の通知として出す。復帰時に停止する。
    private Thread bgNotifThread = null;
    private volatile boolean bgNotifRunning = false;

    private void startBgNotifPoller() {
        try {
            if (this.apiBridge == null || bgNotifRunning) return;
            android.content.SharedPreferences sp = getSharedPreferences("koe_bgnotif", 0);
            if (!sp.getBoolean("enabled", true)) return;
            bgNotifRunning = true;
            final KoeApiBridge bridge = this.apiBridge;
            bgNotifThread = new Thread(new Runnable() {
                public void run() {
                    int n = 0;
                    int idle = 0; // 新着なしが続くほど間隔を広げる(バックグラウンド発熱・電池対策)
                    while (bgNotifRunning) {
                        long wait = n == 0 ? 6000 : (idle < 3 ? 20000 : (idle < 10 ? 45000 : (idle < 30 ? 90000 : 300000)));
                        try {
                            Thread.sleep(wait);
                        } catch (InterruptedException e) {
                            return;
                        }
                        if (!bgNotifRunning) return;
                        n++;
                        try {
                            if (pollNotificationsOnce(bridge)) idle = 0; else idle++;
                        } catch (Throwable t) {
                        }
                    }
                }
            }, "koe-bgnotif");
            bgNotifThread.setDaemon(true);
            bgNotifThread.start();
        } catch (Exception e) {
        }
    }

    void stopBgNotifPoller() {
        bgNotifRunning = false;
        try {
            if (bgNotifThread != null) bgNotifThread.interrupt();
        } catch (Exception e) {
        }
        bgNotifThread = null;
    }

    static long notifTsPublic(String x) { return notifTs(x); }

    private static long notifTs(String x) {
        if (x == null || x.length() == 0) return 0;
        try {
            if (x.matches("^\\d{9,13}$")) {
                long v = Long.parseLong(x);
                return x.length() <= 10 ? v * 1000L : v;
            }
            String d = x.replace("T", " ").replace("Z", "");
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
            if (x.endsWith("Z") || x.indexOf('+') > 10) {
                f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            }
            int plus = d.indexOf('+', 10);
            if (plus > 0) d = d.substring(0, plus);
            int dot = d.indexOf('.');
            if (dot > 0) d = d.substring(0, dot);
            return f.parse(d).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean pollNotificationsOnce(KoeApiBridge bridge) {
        try {
            if (bridge == null || bridge.session == null || !bridge.session.hasAuthToken()) return false; // 未ログイン時は取得しない
            // まず未読数(軽い 1 リクエスト)だけ見る。前回から増えていなければ一覧(重い: 通知+ユーザー名解決)は取らない。
            android.content.SharedPreferences spc = getSharedPreferences("koe_bgnotif", 0);
            int pendingCnt = -1;   // 一覧の取得に成功してから記録する(失敗した回の新着を取りこぼさないため)
            try {
                String cres = bridge.session.dispatch("get_unread_notif_count", new org.json.JSONArray());
                org.json.JSONObject co = cres == null ? null : new org.json.JSONObject(cres);
                if (co != null && co.optBoolean("ok")) {
                    int cnt = co.optInt("count", -1);
                    int prev = spc.getInt("last_cnt", -1);
                    if (cnt >= 0 && prev >= 0 && cnt <= prev) {
                        spc.edit().putInt("last_cnt", cnt).apply();
                        return false;
                    }
                    pendingCnt = cnt;
                }
            } catch (Throwable t) {
            }
            String res = bridge.session.dispatch("get_notifications", new org.json.JSONArray().put("normal"));
            if (res == null) return false;
            org.json.JSONObject o = new org.json.JSONObject(res);
            if (!o.optBoolean("ok")) return false;
            org.json.JSONArray arr = o.optJSONArray("notifications");
            if (arr == null) return false;
            if (pendingCnt >= 0) spc.edit().putInt("last_cnt", pendingCnt).apply();
            android.content.SharedPreferences sp = getSharedPreferences("koe_bgnotif", 0);
            long last = sp.getLong("last_ts", -1);
            long newest = 0;
            ArrayList<org.json.JSONObject> fresh = new ArrayList<org.json.JSONObject>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject n = arr.optJSONObject(i);
                if (n == null) continue;
                long t = notifTs(n.optString("created_at", ""));
                if (t > newest) newest = t;
                if (last >= 0 && t > last) fresh.add(n);
            }
            if (last < 0) {
                // 初回は基準時刻だけ覚える(過去分を一斉通知しない)
                sp.edit().putLong("last_ts", newest).apply();
                return false;
            }
            if (fresh.isEmpty()) return false;
            sp.edit().putLong("last_ts", Math.max(newest, last)).apply();
            int shown = 0;
            for (org.json.JSONObject n : fresh) {
                if (shown >= 5) break;
                String name = n.optString("name", "");
                String msg = n.optString("message", "");
                // 見出しは相手の名前、本文は内容。以前は見出しが全部「声とも+ 通知」で
                // 区別がつかなかったため、通知欄で誰から何が来たか読めるようにする。
                String title = name.length() > 0 ? name : "声とも+";
                String text = msg.length() > 0 ? (msg.startsWith("さん") ? name + msg : msg) : "新しい通知があります";
                bridge.showKoetomoNotification(title, text);
                shown++;
            }
            if (fresh.size() > shown) {
                bridge.showKoetomoNotification("声とも+", "ほか " + (fresh.size() - shown) + " 件の新しい通知");
            }
        } catch (Exception e) {
            return false;   // 通信できなかった回は「変化なし」と同じ扱いにせず、間隔を戻さない
        }
        return true;
    }

    public void onPause() {
        super.onPause();
        // 通話中でなければバックグラウンドでWebViewのJSタイマーを停止し、無駄な通信・電池消費を防ぐ(通話中は状態更新のため維持)
        if (this.webView != null && !this.inCall) {
            this.webView.onPause();
            this.webView.pauseTimers();
        }
        // 画面を離れたタイミングで一時ファイル(画像キャッシュ)が上限を超えていたら古い順に捨てる
        try {
            final android.content.Context c = getApplicationContext();
            new Thread(new Runnable() { public void run() { KoeApiBridge.trimCache(c, KoeApiBridge.CACHE_LIMIT_BYTES); } }).start();
        } catch (Exception e) {}
    }

    /* 裏側の通知ポーリングは「本当に裏に回った」onStop で起こす。
       onPause は画面内画面(PiP)への切り替え・ダイアログ・権限確認でも呼ばれるため、
       そこで起こすと、PiP に入った瞬間に常駐通知が出入りしたり二重にポーリングしていた。 */
    protected void onStop() {
        super.onStop();
        uiForeground = false;
        // 通知の裏側ポーリングは 1 系統だけ動かす。通話中は常駐サービスを起こさず Activity 側で軽く見る。
        if (this.inCall) {
            startBgNotifPoller();
            showOverlayIfWanted();
        } else {
            try { KoeNotifyService.start(getApplicationContext()); } catch (Exception ig) {}
        }
    }

    /* 画面内画面(PiP)に入った/出たことを画面側(JS)へ知らせる。
       画面側はこれを受けて、PiP の小窓では通話画面だけを大きく見せ、戻ったら元の表示にする。
       (API24以降で呼ばれる。API23のandroid.jarにはsuperのメソッドが無いので super は呼ばない) */
    public void onPictureInPictureModeChanged(boolean isInPip, android.content.res.Configuration newConfig) {
        final boolean inPip = isInPip;
        try {
            if (this.webView != null) {
                this.webView.post(new Runnable() {
                    public void run() {
                        try {
                            if (webView != null) {
                                // PiP 中も通話の画面更新・音声処理を止めない
                                if (inPip) {
                                    webView.onResume();
                                    webView.resumeTimers();
                                }
                                webView.evaluateJavascript("window.koeOnPip&&window.koeOnPip(" + inPip + ")", null);
                            }
                        } catch (Exception ig) {
                        }
                    }
                });
            }
        } catch (Exception ig) {
        }
    }

    public void onTrimMemory(int level) {
        try { super.onTrimMemory(level); } catch (Exception e) {}
        try {
            if (level >= 40 && this.webView != null) this.webView.clearMatches();
            final android.content.Context c = getApplicationContext();
            new Thread(new Runnable() { public void run() { KoeApiBridge.trimCache(c, KoeApiBridge.CACHE_LIMIT_BYTES / 2); } }).start();
        } catch (Exception e) {}
    }

    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (this.pipWanted && this.inCall) {
            tryEnterPip();
        }
    }

    public void requestAppPermissions() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                ArrayList arrayList = new ArrayList();
                if (checkSelfPermission("android.permission.RECORD_AUDIO") != 0) {
                    arrayList.add("android.permission.RECORD_AUDIO");
                }
                if (checkSelfPermission("android.permission.CAMERA") != 0) {
                    arrayList.add("android.permission.CAMERA");
                }
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
                    arrayList.add("android.permission.POST_NOTIFICATIONS");
                }
                if (!arrayList.isEmpty()) {
                    requestPermissions((String[]) arrayList.toArray(new String[0]), 2);
                }
            }
        } catch (Exception e) {
        }
    }

    /*
     * 通知(POST_NOTIFICATIONS)だけを単独で要求する。requestAppPermissions()は
     * マイク/カメラも同時に要求するため、ダウンロード完了時などに呼ぶと無関係な
     * 許可ダイアログが出てしまう。恒久拒否(2回拒否済み)でダイアログが出せない
     * 場合はアプリの通知設定画面へ誘導する。
     */
    private KoeApiBridge apiBridge = null;
    private boolean notifPermAsked = false;

    public void requestNotificationPermission() {
        try {
            if (Build.VERSION.SDK_INT < 33) {
                return;
            }
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") == 0) {
                return;
            }
            // shouldShowRequestPermissionRationale が false かつ一度要求済み = 恒久拒否
            boolean canAsk = true;
            try {
                canAsk = shouldShowRequestPermissionRationale("android.permission.POST_NOTIFICATIONS") || !this.notifPermAsked;
            } catch (Exception e) {
            }
            if (canAsk) {
                this.notifPermAsked = true;
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3);
                return;
            }
            openNotificationSettings();
        } catch (Exception e) {
        }
    }

    public void openNotificationSettings() {
        try {
            Intent intent = new Intent();
            if (Build.VERSION.SDK_INT >= 26) {
                intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
                intent.putExtra("android.provider.extra.APP_PACKAGE", getPackageName());
            } else {
                intent.setAction("android.settings.APPLICATION_DETAILS_SETTINGS");
                intent.setData(Uri.parse("package:" + getPackageName()));
            }
            intent.addFlags(268435456);
            startActivity(intent);
        } catch (Exception e) {
        }
    }

    public void requestOverlayPermission() {
        try {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                Intent intent = new Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION", Uri.parse("package:" + getPackageName()));
                intent.addFlags(268435456);
                startActivity(intent);
            }
        } catch (Exception e) {
        }
    }

    private String overlayFailedUrl = "";
    private long overlayFailedAt = 0;

    /* 「名前|~|アイコンURL」を分けて、名前だけ返す。アイコンは別スレッドで読み込んで丸く表示する。
       読み込むのは公式の画像配信元(https)だけ。大きすぎる画像は読まない。 */
    private String splitOverlayPayload(String payload) {
        String name = payload == null ? "" : payload;
        String icon = "";
        int k = name.indexOf("|~|");
        if (k >= 0) {
            icon = name.substring(k + 3);
            name = name.substring(0, k);
        }
        final String url = icon;
        boolean allowed = url.length() > 0 && ImageThumbCache.isAllowedHostPublic(url);
        boolean recentlyFailed = url.equals(overlayFailedUrl) && System.currentTimeMillis() - overlayFailedAt < 60000;
        if (allowed && !recentlyFailed && !uiForeground && !url.equals(overlayIconUrl)) {
            overlayIconUrl = url;
            overlayIconBd = null;
            new Thread(new Runnable() {
                public void run() {
                    java.net.HttpURLConnection c = null;
                    java.io.InputStream in = null;
                    android.graphics.Bitmap src = null;
                    try {
                        c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) KoeTomoPlus");
                        c.setConnectTimeout(6000);
                        c.setInstanceFollowRedirects(false);
                        c.setReadTimeout(8000);
                        in = c.getInputStream();
                        // 最大 4MB まで読む
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n, total = 0;
                        while ((n = in.read(buf)) > 0) {
                            total += n;
                            if (total > 4 * 1024 * 1024) throw new Exception("画像が大きすぎます");
                            bos.write(buf, 0, n);
                        }
                        byte[] data = bos.toByteArray();
                        android.graphics.BitmapFactory.Options bo = new android.graphics.BitmapFactory.Options();
                        bo.inJustDecodeBounds = true;
                        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, bo);
                        int sample = 1;
                        while (bo.outWidth / sample > 512 && bo.outHeight / sample > 512) sample *= 2;
                        bo.inJustDecodeBounds = false;
                        bo.inSampleSize = sample;
                        src = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, bo);
                        if (src == null) throw new Exception("画像をデコードできません");
                        final int size = (int) (60 * getResources().getDisplayMetrics().density);
                        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
                        android.graphics.Canvas cv = new android.graphics.Canvas(out);
                        android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                        cv.drawCircle(size / 2f, size / 2f, size / 2f, pt);
                        pt.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
                        int side = Math.min(src.getWidth(), src.getHeight());
                        android.graphics.Rect from = new android.graphics.Rect((src.getWidth() - side) / 2, (src.getHeight() - side) / 2, (src.getWidth() + side) / 2, (src.getHeight() + side) / 2);
                        cv.drawBitmap(src, from, new android.graphics.Rect(0, 0, size, size), pt);
                        final android.graphics.drawable.BitmapDrawable bd = new android.graphics.drawable.BitmapDrawable(getResources(), out);
                        runOnUiThread(new Runnable() {
                            public void run() {
                                // 読み込み中に別の人へ替わっていたら捨てる
                                if (!url.equals(overlayIconUrl)) return;
                                overlayIconBd = bd;
                                if (overlayAvatar != null) {
                                    overlayAvatar.setText("");
                                    overlayAvatar.setBackground(bd);
                                }
                            }
                        });
                    } catch (Throwable ig) {
                        overlayFailedUrl = url;
                        overlayFailedAt = System.currentTimeMillis();
                        if (url.equals(overlayIconUrl)) overlayIconUrl = "";
                        final String why = (ig.getClass().getSimpleName() + " " + String.valueOf(ig.getMessage())).replace("'", " ").replace("\\", " ");
                        final String host = (url.length() > 40 ? url.substring(0, 40) : url).replace("'", "").replace("\\", "");
                        runOnUiThread(new Runnable() {
                            public void run() {
                                try { if (webView != null) webView.evaluateJavascript("window.koeOverlayIconFail&&window.koeOverlayIconFail('" + why + " / " + host + "')", null); } catch (Throwable x) {}
                            }
                        });
                    } finally {
                        try { if (in != null) in.close(); } catch (Throwable x) {}
                        try { if (c != null) c.disconnect(); } catch (Throwable x) {}
                        if (src != null) src.recycle();
                    }
                }
            }).start();
        } else if (url.length() == 0) {
            overlayIconUrl = "";
            overlayIconBd = null;
            final String nm = name;
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        if (overlayAvatar != null) {
                            // アイコンが無い人は、丸い色地に頭文字へ戻す(前の人の画像を残さない)
                            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                            gd.setColor(-13975097);
                            gd.setStroke((int) (3.0f * getResources().getDisplayMetrics().density), -1);
                            overlayAvatar.setBackground(gd);
                            overlayAvatar.setText(ovInitial(nm));
                        }
                    } catch (Throwable ig) {
                    }
                }
            });
        }
        return name;
    }

    public void showSpeakerOverlay(final String payload) {
        if (uiForeground) return; /* アプリを開いている間は出さない(裏に回ったときに出る) */
        final String str = splitOverlayPayload(payload);
        runOnUiThread(new Runnable() {
            public void run() {
                try {
                    if (uiForeground) return; // 待っている間にアプリが前面に戻ったら出さない
                    if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(MainActivity.this)) {
                        return;
                    }
                    if (MainActivity.this.speakerOverlayView != null) {
                        if (MainActivity.this.overlayAvatar != null && MainActivity.this.overlayIconUrl.length() == 0) {
                            MainActivity.this.overlayAvatar.setText(MainActivity.this.ovInitial(str));
                        }
                        if (MainActivity.this.overlayLabel != null) {
                            MainActivity.this.overlayLabel.setText(str);
                            return;
                        }
                        return;
                    }
                    final float f = MainActivity.this.getResources().getDisplayMetrics().density;
                    LinearLayout linearLayout = new LinearLayout(MainActivity.this);
                    linearLayout.setOrientation(1);
                    linearLayout.setGravity(17);
                    TextView unused = MainActivity.this.overlayAvatar = new TextView(MainActivity.this);
                    MainActivity.this.overlayAvatar.setText(MainActivity.this.ovInitial(str));
                    MainActivity.this.overlayAvatar.setTextColor(-1);
                    MainActivity.this.overlayAvatar.setTextSize(22.0f);
                    MainActivity.this.overlayAvatar.setGravity(17);
                    GradientDrawable gradientDrawable = new GradientDrawable();
                    gradientDrawable.setShape(1);
                    gradientDrawable.setColor(-13975097);
                    gradientDrawable.setStroke((int) (3.0f * f), -1);
                    MainActivity.this.overlayAvatar.setBackground(gradientDrawable);
                    int i = (int) (60.0f * f);
                    MainActivity.this.overlayAvatar.setLayoutParams(new LinearLayout.LayoutParams(i, i));
                    linearLayout.addView(MainActivity.this.overlayAvatar);
                    TextView unused2 = MainActivity.this.overlayLabel = new TextView(MainActivity.this);
                    MainActivity.this.overlayLabel.setText(str);
                    MainActivity.this.overlayLabel.setTextColor(-1);
                    MainActivity.this.overlayLabel.setTextSize(10.0f);
                    MainActivity.this.overlayLabel.setGravity(17);
                    MainActivity.this.overlayLabel.setBackgroundColor(-1291845632);
                    MainActivity.this.overlayLabel.setPadding((int) (6.0f * f), (int) (1.0f * f), (int) (6.0f * f), (int) (1.0f * f));
                    LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-2, -2);
                    layoutParams.topMargin = (int) (3.0f * f);
                    MainActivity.this.overlayLabel.setLayoutParams(layoutParams);
                    linearLayout.addView(MainActivity.this.overlayLabel);
                    final WindowManager.LayoutParams layoutParams2 = new WindowManager.LayoutParams(-2, -2, Build.VERSION.SDK_INT >= 26 ? 2038 : 2002, 8, -3);
                    layoutParams2.gravity = 8388659;
                    layoutParams2.x = (int) (20.0f * f);
                    layoutParams2.y = (int) (120.0f * f);
                    final WindowManager windowManager = (WindowManager) MainActivity.this.getSystemService("window");
                    linearLayout.setOnTouchListener(new View.OnTouchListener() {
                        float dx;
                        float dy;
                        int ix;
                        int iy;
                        boolean moved;

                        public boolean onTouch(View view, MotionEvent motionEvent) {
                            switch (motionEvent.getAction()) {
                                case 0:
                                    this.dx = motionEvent.getRawX();
                                    this.dy = motionEvent.getRawY();
                                    this.ix = layoutParams2.x;
                                    this.iy = layoutParams2.y;
                                    this.moved = false;
                                    return true;
                                case 1:
                                    if (this.moved) {
                                        return true;
                                    }
                                    MainActivity.this.bringAppToFront();
                                    return true;
                                case 2:
                                    layoutParams2.x = this.ix + ((int) (motionEvent.getRawX() - this.dx));
                                    layoutParams2.y = this.iy + ((int) (motionEvent.getRawY() - this.dy));
                                    if (Math.abs(motionEvent.getRawX() - this.dx) + Math.abs(motionEvent.getRawY() - this.dy) > 10.0f * f) {
                                        this.moved = true;
                                    }
                                    try {
                                        windowManager.updateViewLayout(MainActivity.this.speakerOverlayView, layoutParams2);
                                        return true;
                                    } catch (Exception e) {
                                        return true;
                                    }
                                default:
                                    return false;
                            }
                        }
                    });
                    removeOverlayView(sLiveOverlay);   // 古いものが残っていたら先に外す(二重表示の防止)
                    windowManager.addView(linearLayout, layoutParams2);
                    // 追加に成功してから「表示済み」にする(失敗した時に表示済み扱いで残らないように)
                    MainActivity.this.speakerOverlayView = linearLayout;
                    sLiveOverlay = linearLayout;
                    sLiveOverlayWm = windowManager;
                    // 画像が先に読み込み終わっていた場合は、作った直後に反映する
                    if (MainActivity.this.overlayIconBd != null && MainActivity.this.overlayIconUrl.length() > 0 && MainActivity.this.overlayAvatar != null) {
                        MainActivity.this.overlayAvatar.setText("");
                        MainActivity.this.overlayAvatar.setBackground(MainActivity.this.overlayIconBd);
                    }
                } catch (Exception e) {
                    MainActivity.this.speakerOverlayView = null;
                    MainActivity.this.overlayAvatar = null;
                    MainActivity.this.overlayLabel = null;
                }
            }
        });
    }

    /*
     * API26+ (PictureInPictureParams) はこのビルド環境のandroid.jarがAPI23までしか無いため
     * リフレクション経由で呼び出す(端末が実際にAPI26+ならフレームワークに実クラスが存在する)。
     */
    /* 小窓(PiP)の下に出す操作ボタン(ミュート/退出)つきの設定を作る */
    private Object buildPipParams() throws Exception {
        Class<?> paramsCls = Class.forName("android.app.PictureInPictureParams");
        Class<?> builderCls = Class.forName("android.app.PictureInPictureParams$Builder");
        Object builder = builderCls.getConstructor(new Class[0]).newInstance(new Object[0]);
        try {
            builderCls.getMethod("setAspectRatio", Rational.class).invoke(builder, new Rational(1, 1));
        } catch (Exception e) {
        }
        try {
            java.util.ArrayList<Object> actions = new java.util.ArrayList<Object>();
            if (micState != 2) actions.add(pipAction(1, micState == 1 ? "ミュート解除" : "ミュート", "CALL_MUTE", 1));
            actions.add(pipAction(2, "退出", "CALL_LEAVE", 2));
            builderCls.getMethod("setActions", java.util.List.class).invoke(builder, actions);
        } catch (Throwable e) {
        }
        return builderCls.getMethod("build", new Class[0]).invoke(builder, new Object[0]);
    }

    private Object pipAction(int req, String title, String act, int kind) throws Exception {
        Intent in = new Intent("com.akun.koetomo." + act);
        in.setPackage(getPackageName());
        in.putExtra("t", callActionToken());
        PendingIntent pi = PendingIntent.getBroadcast(this, 10 + req, in, Build.VERSION.SDK_INT >= 23 ? 67108864 | 134217728 : 0);
        int res = kind == 1 ? (micState == 1 ? android.R.drawable.ic_lock_silent_mode : android.R.drawable.ic_btn_speak_now) : android.R.drawable.ic_menu_close_clear_cancel;
        Class<?> iconCls = Class.forName("android.graphics.drawable.Icon");
        Object icon = iconCls.getMethod("createWithResource", Context.class, int.class).invoke(null, this, res);
        Class<?> raCls = Class.forName("android.app.RemoteAction");
        return raCls.getConstructor(iconCls, CharSequence.class, CharSequence.class, PendingIntent.class).newInstance(icon, title, title, pi);
    }

    public void tryEnterPip() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Object params = buildPipParams();
                Class<?> paramsCls = Class.forName("android.app.PictureInPictureParams");
                Activity.class.getMethod("enterPictureInPictureMode", paramsCls).invoke(this, params);
            }
        } catch (Exception e2) {
        }
    }

    /* マイクの状態が変わったら、通知と小窓のボタンを書き換える */
    public void onMicStateChanged(final int st) {
        this.micState = st;
        runOnUiThread(new Runnable() {
            public void run() {
                try { CallForegroundService.refresh(MainActivity.this, st); } catch (Throwable ig) {}
                try {
                    if (Build.VERSION.SDK_INT >= 26 && (Boolean) Activity.class.getMethod("isInPictureInPictureMode").invoke(MainActivity.this)) {
                        Object params = buildPipParams();
                        Class<?> paramsCls = Class.forName("android.app.PictureInPictureParams");
                        Activity.class.getMethod("setPictureInPictureParams", paramsCls).invoke(MainActivity.this, params);
                    }
                } catch (Throwable ig) {}
            }
        });
    }

    /* 通知・小窓のボタンから届く操作 */
    private android.content.BroadcastReceiver callActionReceiver = null;
    private void registerCallActionReceiver() {
        try {
            if (callActionReceiver != null) return;
            callActionReceiver = new android.content.BroadcastReceiver() {
                public void onReceive(Context c, Intent i) {
                    try {
                        String a = i.getAction();
                        if (webView == null || a == null) return;
                        if (!callActionToken().equals(i.getStringExtra("t"))) return; // 合言葉が合わない送信元は無視
                        if (a.endsWith("CALL_MUTE")) webView.evaluateJavascript("window.koeNativeMute&&window.koeNativeMute()", null);
                        else if (a.endsWith("CALL_LEAVE")) webView.evaluateJavascript("window.koeNativeLeave&&window.koeNativeLeave()", null);
                    } catch (Throwable ig) {
                    }
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction("com.akun.koetomo.CALL_MUTE");
            f.addAction("com.akun.koetomo.CALL_LEAVE");
            if (Build.VERSION.SDK_INT >= 33) {
                // RECEIVER_NOT_EXPORTED = 4
                Context.class.getMethod("registerReceiver", android.content.BroadcastReceiver.class, android.content.IntentFilter.class, int.class).invoke(this, callActionReceiver, f, 4);
            } else {
                registerReceiver(callActionReceiver, f);
            }
        } catch (Throwable ig) {
            callActionReceiver = null;
        }
    }

    public void updateSpeakerOverlay(final String payload) {
        final String str = splitOverlayPayload(payload);
        runOnUiThread(new Runnable() {
            public void run() {
                try {
                    if (MainActivity.this.overlayAvatar != null) {
                        if (MainActivity.this.overlayIconUrl.length() == 0) {
                            MainActivity.this.overlayAvatar.setText(MainActivity.this.ovInitial(str));
                        }
                    }
                    if (MainActivity.this.overlayLabel != null) {
                        MainActivity.this.overlayLabel.setText(str);
                    }
                } catch (Exception e) {
                }
            }
        });
    }
}
