package com.akun.koetomo;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.PowerManager;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.json.JSONArray;
import org.json.JSONObject;

public class KoeApiBridge {
    /* access modifiers changed from: private */
    public final KoeSession session;
    /* access modifiers changed from: private */
    public final WebView webView;
    /* access modifiers changed from: private */
    public final ExecutorService apiExecutor = Executors.newCachedThreadPool(new ThreadFactory() {
        public Thread newThread(Runnable runnable) {
            Thread t = new Thread(runnable, "koe-api");
            t.setPriority(Thread.NORM_PRIORITY);
            t.setDaemon(true);
            return t;
        }
    });

    public KoeApiBridge(WebView webView2, KoeSession koeSession) {
        this.webView = webView2;
        this.session = koeSession;
    }

    /* access modifiers changed from: private */
    public void resolveBio(boolean z, String str) {
        final String str2 = "window.__onBiometricResult && window.__onBiometricResult(" + (z ? "true" : "false") + ",'" + str + "')";
        this.webView.post(new Runnable() {
            public void run() {
                try {
                    KoeApiBridge.this.webView.evaluateJavascript(str2, (ValueCallback) null);
                } catch (Exception e) {
                }
            }
        });
    }

    /* access modifiers changed from: private */
    public void toastOnJs(String str) {
        final String quote = JSONObject.quote(str);
        this.webView.post(new Runnable() {
            public void run() {
                KoeApiBridge.this.webView.evaluateJavascript("window.__nativeToast && window.__nativeToast(" + quote + ");", (ValueCallback) null);
            }
        });
    }

    // チャンネルは作成後に重要度/音を変更できないため、音+バイブを付けるにあたりIDを _v2 に更新する
    static final String DOWNLOAD_CHANNEL_ID = "koetomo_download_v2";
    private static int downloadNotiId = 2001;
    private static final long[] DOWNLOAD_VIBRATE = {0, 120, 80, 120};

    /*
     * ダウンロード完了時にAndroidの通知として表示する。API26+のNotificationChannelは
     * このビルド環境のandroid.jarがAPI23までしか無いためCallForegroundServiceと同様にリフレクションで作成する。
     * 通知権限が無い場合(API33+でユーザーが拒否)は例外を握りつぶして何もしない(トーストのみで代替される)。
     */
    private void showDownloadNotification(String title, String text) {
        postSystemNotification(DOWNLOAD_CHANNEL_ID, "ダウンロード", title, text, downloadNotiId++, true, null);
    }

    static final String NOTIF_CHANNEL_ID = "koetomo_notify_v1";
    private static int notifNotiId = 3001;

    /*
     * koetomo の通知(いいね/コメント/フォロー等)を Android の通知としても出す。
     * 権限が無い場合はダイアログを出さず黙って何もしない(バックグラウンドから呼ばれるため)。
     */
    public void showKoetomoNotification(String title, String text) {
        postSystemNotification(NOTIF_CHANNEL_ID, "お知らせ", title, text, notifNotiId++, false, "notifications");
    }

    /** JS から診断ログ(ネイティブ側)へ1行追記する。長さ制限あり */
    @JavascriptInterface
    public void log(String line) {
        try {
            if (line == null) return;
            String l = line.length() > 400 ? line.substring(0, 400) : line;
            l = l.replace('\n', ' ');
            session.dispatch("js_diag_log", new org.json.JSONArray().put(l));
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void showNotification(String title, String text) {
        showKoetomoNotification(title, text);
    }

    // WebView 側の秘密情報(保存アカウントのトークン等)を Keystore 暗号化で保存する
    @JavascriptInterface
    public void secureSave(String key, String json) {
        try {
            if (key == null || !key.matches("[a-zA-Z0-9_]{1,40}")) return;
            session.secure().put("js_" + key, json);
        } catch (Exception e) {
        }
    }

    @JavascriptInterface
    public String secureLoad(String key) {
        try {
            if (key == null || !key.matches("[a-zA-Z0-9_]{1,40}")) return null;
            return session.secure().get("js_" + key);
        } catch (Exception e) {
            return null;
        }
    }

    @JavascriptInterface
    public void setBackgroundNotify(boolean on) {
        try {
            this.webView.getContext().getSharedPreferences("koe_bgnotif", 0).edit().putBoolean("enabled", on).apply();
        } catch (Exception e) {
        }
    }

    private void postSystemNotification(String channelId, String channelName, String title, String text, int notiId, boolean askPerm, String openPage) {
        try {
            final Context context = this.webView.getContext();
            // 通知権限が無いと今まで「無言で何もしない」状態だった。理由を伝えて許可要求も出す。
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
                if (!askPerm) return;
                toastOnJs("通知が許可されていないため通知を出せません。表示された画面で通知を許可してください");
                try {
                    if (context instanceof MainActivity) {
                        final MainActivity act = (MainActivity) context;
                        act.runOnUiThread(new Runnable() {
                            public void run() {
                                try {
                                    // マイク/カメラまで巻き込む requestAppPermissions ではなく
                                    // 通知だけを要求する(無関係な許可ダイアログを出さない)
                                    act.requestNotificationPermission();
                                } catch (Exception e) {
                                }
                            }
                        });
                    }
                } catch (Exception ig) {
                }
                return;
            }
            NotificationManager notificationManager = (NotificationManager) context.getSystemService("notification");
            if (notificationManager == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                // チャンネル作成に失敗するとAndroidが通知を黙って捨てるため、
                // 装飾(音/バイブ)付きで失敗しても必ず素のチャンネルは作る。
                Class<?> channelCls = null;
                Constructor<?> ctor = null;
                try {
                    channelCls = Class.forName("android.app.NotificationChannel");
                    ctor = channelCls.getConstructor(String.class, CharSequence.class, int.class);
                } catch (Exception e) {
                }
                if (ctor != null) {
                    Object notificationChannel = null;
                    try {
                        // IMPORTANCE_DEFAULT(3) にしないと通知音が鳴らない(2=LOWは無音)
                        notificationChannel = ctor.newInstance(channelId, channelName, Integer.valueOf(3));
                        try {
                            channelCls.getMethod("setShowBadge", boolean.class).invoke(notificationChannel, Boolean.TRUE);
                            channelCls.getMethod("enableVibration", boolean.class).invoke(notificationChannel, Boolean.TRUE);
                            channelCls.getMethod("setVibrationPattern", long[].class).invoke(notificationChannel, (Object) DOWNLOAD_VIBRATE);
                        } catch (Exception ig) {
                        }
                        try {
                            android.media.AudioAttributes aa = new android.media.AudioAttributes.Builder()
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                                    .build();
                            channelCls.getMethod("setSound", Uri.class, android.media.AudioAttributes.class)
                                    .invoke(notificationChannel, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION), aa);
                        } catch (Exception ig) {
                        }
                    } catch (Exception e) {
                        notificationChannel = null;
                    }
                    if (notificationChannel == null) {
                        try {
                            notificationChannel = ctor.newInstance(channelId, channelName, Integer.valueOf(3));
                        } catch (Exception e) {
                        }
                    }
                    if (notificationChannel != null) {
                        try {
                            NotificationManager.class.getMethod("createNotificationChannel", channelCls).invoke(notificationManager, notificationChannel);
                        } catch (Exception e) {
                        }
                    }
                }
            }
            Intent intent = new Intent(context, MainActivity.class);
            intent.setFlags(603979776 | 268435456);
            if (openPage != null) {
                intent.putExtra("koe_open_page", openPage);
            }
            PendingIntent activity = PendingIntent.getActivity(context, openPage != null ? 1 : 0, intent, (Build.VERSION.SDK_INT >= 23 ? 67108864 : 0) | 134217728);
            Notification.Builder builder = null;
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    Constructor<Notification.Builder> ctor = Notification.Builder.class.getConstructor(Context.class, String.class);
                    builder = ctor.newInstance(context, channelId);
                } catch (Exception e) {
                }
            }
            if (builder == null) {
                builder = new Notification.Builder(context);
            }
            builder.setContentTitle(title).setContentText(text).setSmallIcon(17301558).setAutoCancel(true).setContentIntent(activity);
            // API26未満はチャンネルが無いので、通知自体に音とバイブを設定する
            if (Build.VERSION.SDK_INT < 26) {
                try {
                    builder.setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION));
                    builder.setVibrate(DOWNLOAD_VIBRATE);
                    builder.setPriority(0); // PRIORITY_DEFAULT
                } catch (Exception ig) {
                }
            }
            notificationManager.notify(notiId, builder.build());
        } catch (Exception e) {
            // 今までは全ての失敗が無言だったので、原因が分かるよう画面に出す
            if (askPerm) { try { toastOnJs("通知の表示に失敗しました: " + e); } catch (Exception ig) { } }
        }
    }

    /*
     * このビルド環境のandroid.jarはAPI23までしか無く、BiometricPrompt(API28+)/
     * getMainExecutor(API26+)のシンボルを直接参照できないため、リフレクションで呼び出す。
     * 実機がAPI28+であればフレームワークに実クラスが存在するので動作は変わらない。
     */
    @JavascriptInterface
    public void authBiometric() {
        final Context context = this.webView.getContext();
        if (Build.VERSION.SDK_INT < 28 || !(context instanceof Activity)) {
            resolveBio(false, "unsupported");
        } else {
            ((Activity) context).runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Object mainExecutor = Context.class.getMethod("getMainExecutor", new Class[0]).invoke(context, new Object[0]);
                        Class<?> execCls = Class.forName("java.util.concurrent.Executor");
                        Class<?> promptCls = Class.forName("android.hardware.biometrics.BiometricPrompt");
                        Class<?> builderCls = Class.forName("android.hardware.biometrics.BiometricPrompt$Builder");
                        Class<?> callbackCls = Class.forName("android.hardware.biometrics.BiometricPrompt$AuthenticationCallback");
                        Object builder = builderCls.getConstructor(Context.class).newInstance(context);
                        builderCls.getMethod("setTitle", CharSequence.class).invoke(builder, "ロック解除");
                        builderCls.getMethod("setSubtitle", CharSequence.class).invoke(builder, "指紋または顔認証で解除します");
                        builderCls.getMethod("setNegativeButton", CharSequence.class, execCls, DialogInterface.OnClickListener.class).invoke(builder, "PINを使う", mainExecutor, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialogInterface, int i) {
                                KoeApiBridge.this.resolveBio(false, "cancel");
                            }
                        });
                        Object prompt = builderCls.getMethod("build", new Class[0]).invoke(builder, new Object[0]);
                        Object callbackProxy = java.lang.reflect.Proxy.newProxyInstance(callbackCls.getClassLoader(), new Class[]{callbackCls}, new java.lang.reflect.InvocationHandler() {
                            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                                String name = method.getName();
                                if ("onAuthenticationError".equals(name)) {
                                    KoeApiBridge.this.resolveBio(false, "error");
                                } else if ("onAuthenticationSucceeded".equals(name)) {
                                    KoeApiBridge.this.resolveBio(true, "ok");
                                }
                                return null;
                            }
                        });
                        promptCls.getMethod("authenticate", CancellationSignal.class, execCls, callbackCls).invoke(prompt, new CancellationSignal(), mainExecutor, callbackProxy);
                    } catch (Exception e) {
                        KoeApiBridge.this.resolveBio(false, "exc");
                    }
                }
            });
        }
    }

    @JavascriptInterface
    public boolean biometricAvailable() {
        try {
            if (Build.VERSION.SDK_INT < 29) {
                return false;
            }
            Object biometricManager = this.webView.getContext().getSystemService("biometric");
            if (biometricManager == null) {
                return false;
            }
            Object result = biometricManager.getClass().getMethod("canAuthenticate", new Class[0]).invoke(biometricManager, new Object[0]);
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public void call(final String str, final String str2, final String str3) {
        this.apiExecutor.execute(new Runnable() {
            public void run() {
                String resultStr;
                try {
                    resultStr = KoeApiBridge.this.session.dispatch(str, (str2 == null || str2.trim().length() == 0) ? new JSONArray() : new JSONArray(str2));
                    if (KoeApiBridge.this.session.consumeSessionExpired()) {
                        try {
                            JSONObject jSONObject = new JSONObject(resultStr);
                            jSONObject.put("session_expired", true);
                            resultStr = jSONObject.toString();
                        } catch (Exception e) {
                        }
                    }
                } catch (Exception e2) {
                    resultStr = "{\"ok\":false,\"error\":\"dispatch_error\"}";
                }
                final String quote = JSONObject.quote(resultStr);
                final String quote2 = JSONObject.quote(str3);
                KoeApiBridge.this.webView.post(new Runnable() {
                    public void run() {
                        KoeApiBridge.this.webView.evaluateJavascript("window.__koeResolve(" + quote2 + ", " + quote + ");", (ValueCallback) null);
                    }
                });
            }
        });
    }

    @JavascriptInterface
    public void enterPip() {
        try {
            Context context = this.webView.getContext();
            if (context instanceof MainActivity) {
                final MainActivity mainActivity = (MainActivity) context;
                mainActivity.runOnUiThread(new Runnable() {
                    public void run() {
                        mainActivity.tryEnterPip();
                    }
                });
            }
        } catch (Exception e) {
        }
    }

    @JavascriptInterface
    public boolean hasCameraPermission() {
        try {
            return Build.VERSION.SDK_INT < 23 || this.webView.getContext().checkSelfPermission("android.permission.CAMERA") == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public boolean hasMicPermission() {
        try {
            return Build.VERSION.SDK_INT < 23 || this.webView.getContext().checkSelfPermission("android.permission.RECORD_AUDIO") == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public boolean hasNotifPermission() {
        try {
            return Build.VERSION.SDK_INT < 33 || this.webView.getContext().checkSelfPermission("android.permission.POST_NOTIFICATIONS") == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public boolean hasOverlayPermission() {
        try {
            Context context = this.webView.getContext();
            return (context instanceof MainActivity) && ((MainActivity) context).hasOverlayPermission();
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public void hideOverlay() {
        try {
            Context context = this.webView.getContext();
            if (context instanceof MainActivity) {
                ((MainActivity) context).hideSpeakerOverlay();
            }
        } catch (Exception e) {
        }
    }

    @JavascriptInterface
    public void openAppSettings() {
        try {
            Context context = this.webView.getContext();
            Intent intent = new Intent("android.settings.APPLICATION_DETAILS_SETTINGS");
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(268435456);
            context.startActivity(intent);
        } catch (Exception e) {
        }
    }

    // http/https 以外(file:/content:/javascript: 等)を拒否。SSRF・ローカルファイル持ち出し対策。
    private static boolean isHttpUrl(String s) {
        if (s == null) return false;
        String l = s.trim().toLowerCase();
        return l.startsWith("http://") || l.startsWith("https://");
    }

    @JavascriptInterface
    public void openUrl(String str) {
        try {
            Context context = this.webView.getContext();
            if (str == null) {
                str = "";
            }
            // http(s)以外のスキーム(intent:/file:/javascript:/market: 等)は拒否し、任意インテント発火を防ぐ
            String lower = str.trim().toLowerCase();
            if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
                return;
            }
            Uri uri = Uri.parse(str);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return;
            }
            Intent intent = new Intent("android.intent.action.VIEW", uri);
            intent.addFlags(268435456);
            context.startActivity(intent);
        } catch (Exception e) {
        }
    }

    @JavascriptInterface
    public void requestOverlayPermission() {
        try {
            Context context = this.webView.getContext();
            if (context instanceof MainActivity) {
                ((MainActivity) context).requestOverlayPermission();
            }
        } catch (Exception e) {
        }
    }

    /* ==================== X(Twitter) OAuth2 + PKCE ログイン ====================
     * パブリッククライアント方式のため clientSecret は存在しない(PKCEが代わりに保護する)。
     * 使うのは作者自身が登録したX開発者アプリのClient IDのみ。Client IDは
     * 認可URLに平文で載る公開情報なので、リポジトリに含めても問題ない。
     *
     *   1) startXLogin()  : code_verifier生成 -> 認可URLをブラウザで開く
     *   2) koetomoplus://xauth?code=... をMainActivityが受け取る
     *   3) onXAuthCode()  : トークン交換(シークレット無し) -> /2/users/me でXのuser id
     *                       -> KoeSession.twitter_login で声ともへログイン
     * 結果は window.__koeOnXLogin(json) でJSへ返す。
     */
    // X のクライアントIDは Secrets(難読化・署名結び付け)から実行時に復元する
    private static String X_CLIENT_ID() { return Secrets.xClientId(); }
    private static final String X_REDIRECT_URI = "koetomoplus://xauth";
    private static final String X_SCOPE = "users.read tweet.read";
    private static String xCodeVerifier = null;
    private static String xState = null;

    private static String b64url(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
    }

    private static String randB64(int nbytes) {
        byte[] buf = new byte[nbytes];
        new java.security.SecureRandom().nextBytes(buf);
        return b64url(buf);
    }

    @JavascriptInterface
    public boolean isXLoginConfigured() {
        return X_CLIENT_ID().length() > 0 && !X_CLIENT_ID().startsWith("PASTE_");
    }

    @JavascriptInterface
    public void startXLogin() {
        try {
            if (!isXLoginConfigured()) {
                postXResult("{\"ok\":false,\"message\":\"このビルドではXログインが未設定です\"}");
                return;
            }
            String verifier = randB64(64);
            if (verifier.length() > 128) verifier = verifier.substring(0, 128);
            xCodeVerifier = verifier;
            xState = randB64(16);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            String challenge = b64url(md.digest(verifier.getBytes("US-ASCII")));
            String url = "https://x.com/i/oauth2/authorize"
                    + "?response_type=code"
                    + "&client_id=" + java.net.URLEncoder.encode(X_CLIENT_ID(), "UTF-8")
                    + "&redirect_uri=" + java.net.URLEncoder.encode(X_REDIRECT_URI, "UTF-8")
                    + "&scope=" + java.net.URLEncoder.encode(X_SCOPE, "UTF-8")
                    + "&state=" + java.net.URLEncoder.encode(xState, "UTF-8")
                    + "&code_challenge=" + java.net.URLEncoder.encode(challenge, "UTF-8")
                    + "&code_challenge_method=S256";
            Intent intent = new Intent("android.intent.action.VIEW", Uri.parse(url));
            intent.addFlags(268435456);
            this.webView.getContext().startActivity(intent);
        } catch (Exception e) {
            postXResult("{\"ok\":false,\"message\":\"Xの認証画面を開けませんでした: " + jsEsc(String.valueOf(e)) + "\"}");
        }
    }

    /* MainActivity が koetomoplus://xauth を受け取ったら呼ぶ */
    public void onXAuthCode(final String code, final String state) {
        new Thread(new Runnable() {
            public void run() {
                finishXLogin(code, state);
            }
        }).start();
    }

    private void finishXLogin(String code, String state) {
        try {
            if (code == null || code.length() == 0) {
                postXResult("{\"ok\":false,\"message\":\"Xの認証がキャンセルされました\"}");
                return;
            }
            if (xCodeVerifier == null) {
                postXResult("{\"ok\":false,\"message\":\"認証の途中状態が失われました。もう一度お試しください\"}");
                return;
            }
            if (xState != null && (state == null || !xState.equals(state))) {
                postXResult("{\"ok\":false,\"message\":\"認証の検証に失敗しました(state不一致)\"}");
                return;
            }
            String form = "code=" + java.net.URLEncoder.encode(code, "UTF-8")
                    + "&grant_type=authorization_code"
                    + "&client_id=" + java.net.URLEncoder.encode(X_CLIENT_ID(), "UTF-8")
                    + "&redirect_uri=" + java.net.URLEncoder.encode(X_REDIRECT_URI, "UTF-8")
                    + "&code_verifier=" + java.net.URLEncoder.encode(xCodeVerifier, "UTF-8");

            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://api.x.com/2/oauth2/token").openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            OutputStream os = c.getOutputStream();
            os.write(form.getBytes("UTF-8"));
            os.close();
            int st = c.getResponseCode();
            String resp = readAllStream(st >= 400 ? c.getErrorStream() : c.getInputStream());
            c.disconnect();
            if (st < 200 || st >= 300) {
                postXResult("{\"ok\":false,\"message\":\"Xのトークン取得に失敗しました(HTTP " + st + ")\",\"raw\":\"" + jsEsc(cutStr(resp, 300)) + "\"}");
                return;
            }
            String accessToken = new org.json.JSONObject(resp).optString("access_token", "");
            if (accessToken.length() == 0) {
                postXResult("{\"ok\":false,\"message\":\"Xのアクセストークンが取得できませんでした\"}");
                return;
            }

            java.net.HttpURLConnection c2 = (java.net.HttpURLConnection) new java.net.URL("https://api.x.com/2/users/me").openConnection();
            c2.setRequestMethod("GET");
            c2.setConnectTimeout(20000);
            c2.setReadTimeout(20000);
            c2.setRequestProperty("Authorization", "Bearer " + accessToken);
            int st2 = c2.getResponseCode();
            String resp2 = readAllStream(st2 >= 400 ? c2.getErrorStream() : c2.getInputStream());
            c2.disconnect();
            if (st2 < 200 || st2 >= 300) {
                postXResult("{\"ok\":false,\"message\":\"Xのユーザー情報を取得できませんでした(HTTP " + st2 + ")\",\"raw\":\"" + jsEsc(cutStr(resp2, 300)) + "\"}");
                return;
            }
            org.json.JSONObject data = new org.json.JSONObject(resp2).optJSONObject("data");
            String xUserId = data != null ? data.optString("id", "") : "";
            if (xUserId.length() == 0) {
                postXResult("{\"ok\":false,\"message\":\"XのユーザーIDが取得できませんでした\"}");
                return;
            }
            // 公式と同じく Xのアクセストークンも渡す(KoeSession側で AES-GCM 暗号化して etat/vt/gt にする)
            postXResult(this.session.dispatch("twitter_login", new org.json.JSONArray().put(xUserId).put(accessToken)));
        } catch (Exception e) {
            postXResult("{\"ok\":false,\"message\":\"Xログイン処理でエラー: " + jsEsc(String.valueOf(e)) + "\"}");
        } finally {
            xCodeVerifier = null;
            xState = null;
        }
    }

    private static String cutStr(String v, int n) {
        if (v == null) return "";
        return v.length() > n ? v.substring(0, n) : v;
    }

    private static String jsEsc(String v) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '"' || ch == '\\') sb.append('\\').append(ch);
            else if (ch == '\n' || ch == '\r') sb.append(' ');
            else sb.append(ch);
        }
        return sb.toString();
    }

    private static String readAllStream(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private void postXResult(final String json) {
        try {
            final WebView wv = this.webView;
            wv.post(new Runnable() {
                public void run() {
                    try {
                        wv.evaluateJavascript("window.__koeOnXLogin && window.__koeOnXLogin(" + org.json.JSONObject.quote(json) + ");", (ValueCallback<String>) null);
                    } catch (Exception e) {
                    }
                }
            });
        } catch (Exception e) {
        }
    }

    /* 通知だけを要求する。マイク/カメラを巻き込まないので通知バナーから安全に呼べる。 */
    @JavascriptInterface
    public void requestNotificationPermission() {
        try {
            Context context = this.webView.getContext();
            if (context instanceof MainActivity) {
                final MainActivity act = (MainActivity) context;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        act.requestNotificationPermission();
                    }
                });
            }
        } catch (Exception e) {
        }
    }

    /* アプリの通知設定画面を開く(恒久拒否された場合の導線) */
    @JavascriptInterface
    public void openNotificationSettings() {
        try {
            Context context = this.webView.getContext();
            if (context instanceof MainActivity) {
                final MainActivity act = (MainActivity) context;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        act.openNotificationSettings();
                    }
                });
            }
        } catch (Exception e) {
        }
    }

    @JavascriptInterface
    public void requestPermissions() {
        try {
            Context context = this.webView.getContext();
            if (context instanceof MainActivity) {
                final MainActivity mainActivity = (MainActivity) context;
                mainActivity.runOnUiThread(new Runnable() {
                    public void run() {
                        mainActivity.requestAppPermissions();
                    }
                });
            }
        } catch (Exception e) {
        }
    }

    @JavascriptInterface
    public void saveAudio(final String str) {
        if (str == null || !str.toLowerCase().startsWith("https://")) { toastOnJs("保存元URLが不正です"); return; }
        if (!isHttpUrl(str)) { return; }
        new Thread(new Runnable() {
            public void run() {
                try {
                    HttpURLConnection httpURLConnection = (HttpURLConnection) new URL(str).openConnection();
                    httpURLConnection.setConnectTimeout(15000);
                    httpURLConnection.setReadTimeout(30000);
                    httpURLConnection.connect();
                    InputStream inputStream = httpURLConnection.getInputStream();
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    byte[] bArr = new byte[8192];
                    while (true) {
                        int read = inputStream.read(bArr);
                        if (read <= 0) {
                            break;
                        }
                        byteArrayOutputStream.write(bArr, 0, read);
                    }
                    inputStream.close();
                    httpURLConnection.disconnect();
                    byte[] byteArray = byteArrayOutputStream.toByteArray();
                    Context context = KoeApiBridge.this.webView.getContext();
                    String path = str;
                    int indexOf = path.indexOf(63);
                    if (indexOf >= 0) {
                        path = path.substring(0, indexOf);
                    }
                    String str2 = "m4a";
                    int lastIndexOf = path.lastIndexOf(46);
                    if (lastIndexOf >= 0 && lastIndexOf > path.lastIndexOf(47)) {
                        String lowerCase = path.substring(lastIndexOf + 1).toLowerCase();
                        if (lowerCase.length() >= 2 && lowerCase.length() <= 5) {
                            str2 = lowerCase;
                        }
                    }
                    String str3 = str2.equals("webm") ? "audio/webm" : str2.equals("mp3") ? "audio/mpeg" : str2.equals("ogg") ? "audio/ogg" : str2.equals("wav") ? "audio/wav" : "audio/mp4";
                    String str4 = "KoeTomo_" + System.currentTimeMillis() + "." + str2;
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues contentValues = new ContentValues();
                        contentValues.put("_display_name", str4);
                        contentValues.put("mime_type", str3);
                        contentValues.put("relative_path", "Music/KoeTomo");
                        Uri insert = context.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues);
                        if (insert == null) {
                            KoeApiBridge.this.toastOnJs("保存に失敗しました");
                            return;
                        }
                        OutputStream openOutputStream = context.getContentResolver().openOutputStream(insert);
                        openOutputStream.write(byteArray);
                        openOutputStream.flush();
                        openOutputStream.close();
                    } else {
                        File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "KoeTomo");
                        file.mkdirs();
                        File file2 = new File(file, str4);
                        FileOutputStream fileOutputStream = new FileOutputStream(file2);
                        fileOutputStream.write(byteArray);
                        fileOutputStream.flush();
                        fileOutputStream.close();
                        context.sendBroadcast(new Intent("android.intent.action.MEDIA_SCANNER_SCAN_FILE", Uri.fromFile(file2)));
                    }
                    KoeApiBridge.this.toastOnJs("音声を保存しました");
                    KoeApiBridge.this.showDownloadNotification("ダウンロード完了", "音声を保存しました");
                } catch (Exception e) {
                    KoeApiBridge.this.toastOnJs("保存に失敗しました");
                }
            }
        }).start();
    }

    @JavascriptInterface
    public void saveAudioData(final String str, final String str2) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String raw = str;
                    int indexOf = raw.indexOf(44);
                    if (raw.startsWith("data:") && indexOf >= 0) {
                        raw = raw.substring(indexOf + 1);
                    }
                    byte[] decode = Base64.decode(raw, 0);
                    Context context = KoeApiBridge.this.webView.getContext();
                    String lowerCase = (str2 == null || str2.length() == 0) ? "mp3" : str2.toLowerCase();
                    String mime = lowerCase.equals("wav") ? "audio/wav" : lowerCase.equals("m4a") ? "audio/mp4" : lowerCase.equals("ogg") ? "audio/ogg" : "audio/mpeg";
                    String str3 = "KoeTomo_" + System.currentTimeMillis() + "." + lowerCase;
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues contentValues = new ContentValues();
                        contentValues.put("_display_name", str3);
                        contentValues.put("mime_type", mime);
                        contentValues.put("relative_path", "Music/KoeTomo");
                        Uri insert = context.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues);
                        if (insert == null) {
                            KoeApiBridge.this.toastOnJs("保存に失敗しました");
                            return;
                        }
                        OutputStream openOutputStream = context.getContentResolver().openOutputStream(insert);
                        openOutputStream.write(decode);
                        openOutputStream.flush();
                        openOutputStream.close();
                    } else {
                        File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "KoeTomo");
                        file.mkdirs();
                        File file2 = new File(file, str3);
                        FileOutputStream fileOutputStream = new FileOutputStream(file2);
                        fileOutputStream.write(decode);
                        fileOutputStream.flush();
                        fileOutputStream.close();
                        context.sendBroadcast(new Intent("android.intent.action.MEDIA_SCANNER_SCAN_FILE", Uri.fromFile(file2)));
                    }
                    KoeApiBridge.this.toastOnJs("音声を保存しました");
                    KoeApiBridge.this.showDownloadNotification("ダウンロード完了", "音声を保存しました");
                } catch (Exception e) {
                    KoeApiBridge.this.toastOnJs("保存に失敗しました");
                }
            }
        }).start();
    }

    @JavascriptInterface
    public void saveImage(final String str) {
        if (str == null || !str.toLowerCase().startsWith("https://")) { toastOnJs("保存元URLが不正です"); return; }
        if (!isHttpUrl(str)) { return; }
        new Thread(new Runnable() {
            public void run() {
                try {
                    HttpURLConnection httpURLConnection = (HttpURLConnection) new URL(str).openConnection();
                    httpURLConnection.setConnectTimeout(15000);
                    httpURLConnection.setReadTimeout(20000);
                    httpURLConnection.connect();
                    InputStream inputStream = httpURLConnection.getInputStream();
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    byte[] bArr = new byte[8192];
                    while (true) {
                        int read = inputStream.read(bArr);
                        if (read <= 0) {
                            break;
                        }
                        byteArrayOutputStream.write(bArr, 0, read);
                    }
                    inputStream.close();
                    httpURLConnection.disconnect();
                    byte[] byteArray = byteArrayOutputStream.toByteArray();
                    Context context = KoeApiBridge.this.webView.getContext();
                    String str = "KoeTomo_" + System.currentTimeMillis() + ".jpg";
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues contentValues = new ContentValues();
                        contentValues.put("_display_name", str);
                        contentValues.put("mime_type", "image/jpeg");
                        contentValues.put("relative_path", "Pictures/KoeTomo");
                        Uri insert = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
                        if (insert == null) {
                            KoeApiBridge.this.toastOnJs("保存に失敗しました");
                            return;
                        }
                        OutputStream openOutputStream = context.getContentResolver().openOutputStream(insert);
                        openOutputStream.write(byteArray);
                        openOutputStream.flush();
                        openOutputStream.close();
                    } else {
                        File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "KoeTomo");
                        file.mkdirs();
                        File file2 = new File(file, str);
                        FileOutputStream fileOutputStream = new FileOutputStream(file2);
                        fileOutputStream.write(byteArray);
                        fileOutputStream.flush();
                        fileOutputStream.close();
                        context.sendBroadcast(new Intent("android.intent.action.MEDIA_SCANNER_SCAN_FILE", Uri.fromFile(file2)));
                    }
                    KoeApiBridge.this.toastOnJs("画像を保存しました");
                    KoeApiBridge.this.showDownloadNotification("ダウンロード完了", "画像を保存しました");
                } catch (Exception e) {
                    KoeApiBridge.this.toastOnJs("保存に失敗しました");
                }
            }
        }).start();
    }

    @JavascriptInterface
    public void setInCall(boolean z) {
        try {
            Context context = this.webView.getContext();
            if (context instanceof MainActivity) {
                ((MainActivity) context).inCall = z;
                if (!z) {
                    ((MainActivity) context).hideSpeakerOverlay();
                }
            }
            Intent intent = new Intent(context, CallForegroundService.class);
            if (!z) {
                context.stopService(intent);
            } else if (Build.VERSION.SDK_INT >= 26) {
                try {
                    Context.class.getMethod("startForegroundService", Intent.class).invoke(context, intent);
                } catch (Exception e2) {
                    context.startService(intent);
                }
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
        }
    }

    @JavascriptInterface
    public void setPipEnabled(boolean z) {
        try {
            Context context = this.webView.getContext();
            if (context instanceof MainActivity) {
                ((MainActivity) context).pipWanted = z;
            }
        } catch (Exception e) {
        }
    }

    @JavascriptInterface
    public void shareText(String str) {
        try {
            Context context = this.webView.getContext();
            Intent intent = new Intent("android.intent.action.SEND");
            intent.setType("text/plain");
            if (str == null) {
                str = "";
            }
            intent.putExtra("android.intent.extra.TEXT", str);
            Intent createChooser = Intent.createChooser(intent, "共有");
            createChooser.addFlags(268435456);
            context.startActivity(createChooser);
        } catch (Exception e) {
        }
    }

    @JavascriptInterface
    public void showOverlay(String str) {
        try {
            Context context = this.webView.getContext();
            if (context instanceof MainActivity) {
                ((MainActivity) context).showSpeakerOverlay(str);
            }
        } catch (Exception e) {
        }
    }

    @JavascriptInterface
    public void updateOverlay(String str) {
        try {
            Context context = this.webView.getContext();
            if (context instanceof MainActivity) {
                ((MainActivity) context).updateSpeakerOverlay(str);
            }
        } catch (Exception e) {
        }
    }

    /*
     * 通話開始直後に相手の声がすぐ聞こえない問題への追加対策。
     * これまでJS側の購読順序修正のみだったが、ネイティブ側で
     * オーディオフォーカスとモードを明示的に取得していなかったため、
     * 端末によってはWebViewのAudio再生がミュート/低音量状態から
     * 始まることがあった。通話開始時にAUDIOFOCUS_GAIN取得+
     * MODE_IN_COMMUNICATIONへ切り替え、終了時に元へ戻す。
     */
    private final AudioManager.OnAudioFocusChangeListener callFocusListener = new AudioManager.OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int i) {
        }
    };
    private boolean callAudioFocusHeld = false;
    /* 通話中にCPUスリープでSkyWay接続が切れないようにするための部分ウェイクロック(画面は点灯させない) */
    private PowerManager.WakeLock callWakeLock = null;

    @JavascriptInterface
    public void startCallAudio() {
        try {
            Context context = this.webView.getContext();
            final AudioManager audioManager = (AudioManager) context.getSystemService("audio");
            if (audioManager == null) {
                return;
            }
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true);
            int result = audioManager.requestAudioFocus(this.callFocusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            this.callAudioFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Exception e) {
        }
        try {
            Context context2 = this.webView.getContext();
            PowerManager powerManager = (PowerManager) context2.getSystemService("power");
            if (powerManager != null && this.callWakeLock == null) {
                PowerManager.WakeLock newWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KoeTomo:CallWakeLock");
                this.callWakeLock = newWakeLock;
                newWakeLock.setReferenceCounted(false);
                this.callWakeLock.acquire();
            }
        } catch (Exception e) {
        }
    }

    @JavascriptInterface
    public void stopCallAudio() {
        try {
            Context context = this.webView.getContext();
            AudioManager audioManager = (AudioManager) context.getSystemService("audio");
            if (audioManager == null) {
                return;
            }
            if (this.callAudioFocusHeld) {
                audioManager.abandonAudioFocus(this.callFocusListener);
                this.callAudioFocusHeld = false;
            }
            audioManager.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception e) {
        }
        try {
            if (this.callWakeLock != null && this.callWakeLock.isHeld()) {
                this.callWakeLock.release();
            }
            this.callWakeLock = null;
        } catch (Exception e) {
        }
    }

    /*
     * Androidのハプティクスを使った軽い振動フィードバック(通話接続完了/ミュート切替など)。
     * VibrationEffect(API26+)はこのビルド環境のandroid.jarに存在しないため、
     * どのAPIレベルでも動く非推奨のvibrate(long)を使用(実機では非推奨扱いでも動作する)。
     */
    @JavascriptInterface
    public void vibrate(long j) {
        try {
            Context context = this.webView.getContext();
            Vibrator vibrator = (Vibrator) context.getSystemService("vibrator");
            if (vibrator != null && vibrator.hasVibrator()) {
                long ms = j <= 0 ? 30 : j;
                if (ms > 2000) {
                    ms = 2000;
                }
                vibrator.vibrate(ms);
            }
        } catch (Exception e) {
        }
    }

    // ==== アプリ内アップデート(GitHub Releases) ====

    // 端末にインストール済みのバージョンを返す
    @JavascriptInterface
    public String appVersion() {
        try {
            Context context = this.webView.getContext();
            android.content.pm.PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return new JSONObject().put("ok", true).put("name", pi.versionName == null ? "" : pi.versionName).put("code", pi.versionCode).toString();
        } catch (Exception e) {
            try {
                return new JSONObject().put("ok", false).put("error", String.valueOf(e)).toString();
            } catch (Exception ig) {
                return "{\"ok\":false}";
            }
        }
    }

    // GitHub Releases の最新版を調べ、結果を window.__koeOnUpdateInfo(json) に返す
    @JavascriptInterface
    public void checkUpdate(final String apiUrl) {
        if (!isHttpUrl(apiUrl) || !isTrustedUpdateUrl(apiUrl)) {
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                String out;
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    conn.setRequestProperty("User-Agent", "KoeTomoPlus-updater");
                    int status = conn.getResponseCode();
                    InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while (is != null) {
                        n = is.read(buf);
                        if (n <= 0) {
                            break;
                        }
                        bos.write(buf, 0, n);
                    }
                    if (is != null) {
                        is.close();
                    }
                    conn.disconnect();
                    JSONObject j = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
                    String tag = j.optString("tag_name", "");
                    String apk = "";
                    JSONArray assets = j.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.optJSONObject(i);
                            if (a != null && a.optString("name", "").toLowerCase().endsWith(".apk")) {
                                apk = a.optString("browser_download_url", "");
                                break;
                            }
                        }
                    }
                    out = new JSONObject().put("ok", status == 200).put("status", status)
                            .put("tag", tag).put("apk", apk).put("notes", j.optString("body", "")).toString();
                } catch (Exception e) {
                    try {
                        out = new JSONObject().put("ok", false).put("error", String.valueOf(e)).toString();
                    } catch (Exception ig) {
                        out = "{\"ok\":false}";
                    }
                }
                final String quoted = JSONObject.quote(out);
                KoeApiBridge.this.webView.post(new Runnable() {
                    public void run() {
                        try {
                            KoeApiBridge.this.webView.evaluateJavascript("window.__koeOnUpdateInfo && window.__koeOnUpdateInfo(" + quoted + ");", (ValueCallback) null);
                        } catch (Exception e) {
                        }
                    }
                });
            }
        }).start();
    }

    // 「提供元不明のアプリ」のインストールが許可されているか(API26+)。26未満は常に可。
    @JavascriptInterface
    public boolean canInstallApks() {
        try {
            if (Build.VERSION.SDK_INT < 26) {
                return true;
            }
            Context context = this.webView.getContext();
            Object r = android.content.pm.PackageManager.class.getMethod("canRequestPackageInstalls", new Class[0])
                    .invoke(context.getPackageManager(), new Object[0]);
            return (r instanceof Boolean) && ((Boolean) r).booleanValue();
        } catch (Exception e) {
            return false;
        }
    }

    // 「提供元不明のアプリ」の許可画面を開く
    @JavascriptInterface
    public void openInstallSettings() {
        try {
            Context context = this.webView.getContext();
            Intent intent;
            if (Build.VERSION.SDK_INT >= 26) {
                intent = new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", Uri.parse("package:" + context.getPackageName()));
            } else {
                intent = new Intent("android.settings.SECURITY_SETTINGS");
            }
            intent.addFlags(268435456);
            context.startActivity(intent);
        } catch (Exception e) {
            toastOnJs("設定画面を開けませんでした");
        }
    }

    /*
     * 更新APKをDownloadManagerで取得し、完了したらインストール画面を開く。
     * DownloadManagerのcontent://URIを使うのでFileProviderは不要。
     * ※Androidの仕様上、インストール自体は必ずユーザーの確認が要る(無音インストールは不可)。
     */
    // 更新の確認・ダウンロード先は GitHub(自リポジトリ)の https のみ許可。
    // WebView 側が万一乗っ取られても任意サイトの APK を落とさせない。
    private static boolean isTrustedUpdateUrl(String url) {
        try {
            if (url == null) return false;
            Uri u = Uri.parse(url);
            if (u == null || !"https".equalsIgnoreCase(u.getScheme())) return false;
            String h = u.getHost() == null ? "" : u.getHost().toLowerCase();
            String path = u.getPath() == null ? "" : u.getPath();
            if (path.contains("..") || path.contains("//") || path.contains("\\")) return false; // パス正規化回避の防止
            if (h.equals("api.github.com")) return path.startsWith("/repos/haizarakun/koetomoProject/");
            if (h.equals("github.com")) return path.startsWith("/haizarakun/koetomoProject/");
            if (h.equals("objects.githubusercontent.com") || h.equals("release-assets.githubusercontent.com")) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public void downloadUpdate(final String url) {
        if (!isHttpUrl(url) || !isTrustedUpdateUrl(url)) {
            toastOnJs("更新URLが不正です");
            return;
        }
        try {
            final Context context = this.webView.getContext();
            final android.app.DownloadManager dm = (android.app.DownloadManager) context.getSystemService("download");
            if (dm == null) {
                toastOnJs("ダウンロードを開始できませんでした");
                return;
            }
            android.app.DownloadManager.Request req = new android.app.DownloadManager.Request(Uri.parse(url));
            req.setTitle("KoeTomo+ の更新");
            req.setDescription("新しいバージョンをダウンロードしています");
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(1);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "KoeTomoPlus-update-" + System.currentTimeMillis() + ".apk");
            final long id = dm.enqueue(req);
            toastOnJs("更新をダウンロードしています…");
            final android.content.BroadcastReceiver[] holder = new android.content.BroadcastReceiver[1];
            holder[0] = new android.content.BroadcastReceiver() {
                public void onReceive(Context ctx, Intent it) {
                    try {
                        if (it.getLongExtra("extra_download_id", -1) != id) {
                            return;
                        }
                        try {
                            ctx.unregisterReceiver(holder[0]);
                        } catch (Exception ig) {
                        }
                        Uri fileUri = dm.getUriForDownloadedFile(id);
                        if (fileUri == null) {
                            KoeApiBridge.this.toastOnJs("更新ファイルを取得できませんでした");
                            return;
                        }
                        Intent inst = new Intent("android.intent.action.VIEW");
                        inst.setDataAndType(fileUri, "application/vnd.android.package-archive");
                        inst.addFlags(268435457);
                        ctx.startActivity(inst);
                    } catch (Exception e) {
                        KoeApiBridge.this.toastOnJs("インストール画面を開けませんでした: " + e);
                    }
                }
            };
            context.registerReceiver(holder[0], new android.content.IntentFilter("android.intent.action.DOWNLOAD_COMPLETE"));
        } catch (Exception e) {
            toastOnJs("更新のダウンロードに失敗しました: " + e);
        }
    }
}
