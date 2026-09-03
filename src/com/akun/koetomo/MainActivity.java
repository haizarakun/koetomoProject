package com.akun.koetomo;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
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
    private static final int FILE_CHOOSER_REQUEST = 100;
    /* access modifiers changed from: private */
    public ValueCallback<Uri[]> filePathCallback;
    public boolean inCall = false;
    /* access modifiers changed from: private */
    public TextView overlayAvatar = null;
    /* access modifiers changed from: private */
    public TextView overlayLabel = null;
    public boolean pipWanted = false;
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

    public void hideSpeakerOverlay() {
        runOnUiThread(new Runnable() {
            public void run() {
                try {
                    if (MainActivity.this.speakerOverlayView != null) {
                        ((WindowManager) MainActivity.this.getSystemService("window")).removeView(MainActivity.this.speakerOverlayView);
                        View unused = MainActivity.this.speakerOverlayView = null;
                        TextView unused2 = MainActivity.this.overlayAvatar = null;
                        TextView unused3 = MainActivity.this.overlayLabel = null;
                    }
                } catch (Exception e) {
                }
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
    private static final String RELEASE_CERT_SHA256 = "8d638778babadabfa3f90cec5fbf0429db8cb595a4fd732bc02d99a79993f292";

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
            if (!arrayList.isEmpty()) {
                requestPermissions((String[]) arrayList.toArray(new String[0]), 1);
            }
        }
        this.webView = new WebView(this);
        setContentView(this.webView);
        WebSettings settings = this.webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        // セキュリティ強化: ローカルHTMLから任意ファイル読み取り/ユニバーサル(クロスオリジン)アクセスを禁止し、
        // フォーム/パスワードの自動保存や位置情報も無効化する。
        try {
            settings.setAllowFileAccessFromFileURLs(false);
            settings.setAllowUniversalAccessFromFileURLs(false);
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
        // どんな画面サイズ/密度の端末(タブレット・折りたたみ・Meta Questの2Dパネル等)でも
        // CSSのviewportメタタグ通りに正しく表示させるため、WebViewの幅計算をwide viewport方式にする。
        try {
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
        } catch (Exception e) {
        }
        try {
            settings.setRenderPriority(WebSettings.RenderPriority.HIGH);
        } catch (Exception e) {
        }
        // スクロールの滑らかさ向上(オフスクリーン先読みラスタライズ)
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                settings.setOffscreenPreRaster(true);
            }
        } catch (Exception e) {
        }
        WebView.setWebContentsDebuggingEnabled(false);
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
            @Override public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                MainActivity.this.handleIncomingIntent(MainActivity.this.getIntent());
            }
        });
        this.webView.setWebChromeClient(new WebChromeClient() {
            public void onPermissionRequest(final PermissionRequest permissionRequest) {
                MainActivity.this.runOnUiThread(new Runnable() {
                    public void run() {
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
                final String js = "window.__koeOpenPageFromNotif && window.__koeOpenPageFromNotif('" + openPage.replace("'", "") + "')";
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
        try { stopBgNotifPoller(); } catch (Exception e) {}
        try {
            if (this.speakerOverlayView != null) {
                ((WindowManager) getSystemService("window")).removeView(this.speakerOverlayView);
                this.speakerOverlayView = null;
            }
        } catch (Exception e) {}
        try {
            if (this.webView != null && isFinishing()) {
                this.webView.loadUrl("about:blank");
                this.webView.destroy();
            }
        } catch (Exception e) {}
        super.onDestroy();
    }

    /* access modifiers changed from: protected */
    public void onResume() {
        super.onResume();
        stopBgNotifPoller();
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
                    while (bgNotifRunning) {
                        try {
                            Thread.sleep(n == 0 ? 20000 : 60000);
                        } catch (InterruptedException e) {
                            return;
                        }
                        if (!bgNotifRunning) return;
                        n++;
                        try {
                            pollNotificationsOnce(bridge);
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

    private void stopBgNotifPoller() {
        bgNotifRunning = false;
        try {
            if (bgNotifThread != null) bgNotifThread.interrupt();
        } catch (Exception e) {
        }
        bgNotifThread = null;
    }

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

    private void pollNotificationsOnce(KoeApiBridge bridge) {
        try {
            if (bridge == null || bridge.session == null || !bridge.session.hasAuthToken()) return; // 未ログイン時は取得しない
            String res = bridge.session.dispatch("get_notifications", new org.json.JSONArray().put("normal"));
            if (res == null) return;
            org.json.JSONObject o = new org.json.JSONObject(res);
            if (!o.optBoolean("ok")) return;
            org.json.JSONArray arr = o.optJSONArray("notifications");
            if (arr == null) return;
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
                return;
            }
            if (fresh.isEmpty()) return;
            sp.edit().putLong("last_ts", Math.max(newest, last)).apply();
            int shown = 0;
            for (org.json.JSONObject n : fresh) {
                if (shown >= 5) break;
                String name = n.optString("name", "");
                String msg = n.optString("message", "");
                String title = name.length() > 0 ? name : "声とも+";
                String text = (name.length() > 0 && msg.startsWith("さん")) ? name + msg : (name.length() > 0 && msg.length() > 0 ? name + " " + msg : (msg.length() > 0 ? msg : "新しい通知があります"));
                bridge.showKoetomoNotification("声とも+ 通知", text);
                shown++;
            }
            if (fresh.size() > shown) {
                bridge.showKoetomoNotification("声とも+ 通知", "ほか " + (fresh.size() - shown) + " 件の新しい通知");
            }
        } catch (Exception e) {
        }
    }

    public void onPause() {
        super.onPause();
        // 通話中でなければバックグラウンドでWebViewのJSタイマーを停止し、無駄な通信・電池消費を防ぐ(通話中は状態更新のため維持)
        if (this.webView != null && !this.inCall) {
            this.webView.onPause();
            this.webView.pauseTimers();
        }
        startBgNotifPoller();
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

    public void showSpeakerOverlay(final String str) {
        runOnUiThread(new Runnable() {
            public void run() {
                try {
                    if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(MainActivity.this)) {
                        return;
                    }
                    if (MainActivity.this.speakerOverlayView != null) {
                        if (MainActivity.this.overlayAvatar != null) {
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
                    View unused3 = MainActivity.this.speakerOverlayView = linearLayout;
                    windowManager.addView(linearLayout, layoutParams2);
                } catch (Exception e) {
                }
            }
        });
    }

    /*
     * API26+ (PictureInPictureParams) はこのビルド環境のandroid.jarがAPI23までしか無いため
     * リフレクション経由で呼び出す(端末が実際にAPI26+ならフレームワークに実クラスが存在する)。
     */
    public void tryEnterPip() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Class<?> paramsCls = Class.forName("android.app.PictureInPictureParams");
                Class<?> builderCls = Class.forName("android.app.PictureInPictureParams$Builder");
                Object builder = builderCls.getConstructor(new Class[0]).newInstance(new Object[0]);
                try {
                    builderCls.getMethod("setAspectRatio", Rational.class).invoke(builder, new Rational(2, 3));
                } catch (Exception e) {
                }
                Object params = builderCls.getMethod("build", new Class[0]).invoke(builder, new Object[0]);
                Activity.class.getMethod("enterPictureInPictureMode", paramsCls).invoke(this, params);
            }
        } catch (Exception e2) {
        }
    }

    public void updateSpeakerOverlay(final String str) {
        runOnUiThread(new Runnable() {
            public void run() {
                try {
                    if (MainActivity.this.overlayAvatar != null) {
                        MainActivity.this.overlayAvatar.setText(MainActivity.this.ovInitial(str));
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
