package com.akun.koetomo;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Environment;
import android.net.wifi.WifiManager;
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
    public final ExecutorService apiExecutor = newBoundedPool(new ThreadFactory() {
        public Thread newThread(Runnable runnable) {
            Thread t = new Thread(runnable, "koe-api");
            t.setPriority(Thread.NORM_PRIORITY);
            t.setDaemon(true);
            return t;
        }
    });

    /** 同時に走る API 呼び出しの数に上限を付ける(JS が大量に投げてもスレッドが増え続けない) */
    private static ExecutorService newBoundedPool(ThreadFactory factory) {
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                32, 32, 30L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<Runnable>(), factory);
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    public KoeApiBridge(WebView webView2, KoeSession koeSession) {
        this.webView = webView2;
        this.session = koeSession;
    }

    /* access modifiers changed from: private */
    private long bioDoneAt = 0;
    public void resolveBio(boolean z, String str) {
        // 「PINを使う」ボタンのあとにフレームワークからもエラー通知が来て二重に呼ばれるので、短時間の2回目は捨てる
        long nowMs = System.currentTimeMillis();
        if (nowMs - bioDoneAt < 700) return;
        bioDoneAt = nowMs;
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

    /* ===== 通知アイコン =====
     * これまでは android.R.drawable.ic_menu_call(受話器)を全部の通知に使っていたため、
     * 保存完了もお知らせも通話中も、すべて「📞」に見えていた。
     * アプリ自身のロゴ(assets/web/logo.png)から白いシルエットを作って小アイコンにする。
     * 小アイコンは alpha しか使われないので、明るい部分だけを不透明にして作る。
     * Icon は API23 から。それ未満は受話器ではない無難な枠アイコンにする。 */
    static final int FALLBACK_SMALL_ICON = 17301659; // android.R.drawable.ic_dialog_info

    private static Object cachedSmallIcon = null;
    private static boolean smallIconTried = false;
    private static android.graphics.Bitmap cachedLogo = null;
    private static boolean logoTried = false;

    /** assets/web/logo.png をそのまま(カラーで)読む。通知の大アイコン用。 */
    static android.graphics.Bitmap appLogoBitmap(Context c) {
        if (logoTried) return cachedLogo;
        logoTried = true;
        InputStream in = null;
        try {
            in = c.getAssets().open("web/logo.png");
            cachedLogo = BitmapFactory.decodeStream(in);
        } catch (Throwable t) {
            cachedLogo = null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ig) {}
        }
        return cachedLogo;
    }

    /** ロゴの明るい部分だけを残した白いシルエット Icon を作る(API23+)。失敗したら null。 */
    static Object appSmallIcon(Context c) {
        if (smallIconTried) return cachedSmallIcon;
        smallIconTried = true;
        try {
            if (Build.VERSION.SDK_INT < 23) return null;
            android.graphics.Bitmap src = appLogoBitmap(c);
            if (src == null) return null;
            int n = 96;
            android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(src, n, n, true);
            int[] px = new int[n * n];
            small.getPixels(px, 0, n, 0, 0, n, n);
            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                int a = (p >>> 24) & 255;
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                int lum = (r * 30 + g * 59 + b * 11) / 100;
                // 明るいところを不透明、暗い背景を透明にする(0..255 に伸ばす)
                int outA = lum <= 96 ? 0 : (lum >= 200 ? 255 : ((lum - 96) * 255) / 104);
                if (a < 255) outA = (outA * a) / 255;
                px[i] = (outA << 24) | 0x00FFFFFF;
            }
            android.graphics.Bitmap sil = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888);
            sil.setPixels(px, 0, n, 0, 0, n, n);
            if (small != src) small.recycle();
            cachedSmallIcon = android.graphics.drawable.Icon.createWithBitmap(sil);
        } catch (Throwable t) {
            cachedSmallIcon = null;
        }
        return cachedSmallIcon;
    }

    /** 小アイコンを Builder に設定する。アプリのロゴが使えないときだけ既定の枠アイコン。 */
    static void applySmallIcon(Context c, Notification.Builder b) {
        try {
            Object ic = appSmallIcon(c);
            if (ic != null) {
                b.setSmallIcon((android.graphics.drawable.Icon) ic);
                return;
            }
        } catch (Throwable t) {
        }
        try { b.setSmallIcon(FALLBACK_SMALL_ICON); } catch (Throwable t) {}
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

    // 音が鳴らない端末があったため v2 に更新（既存チャンネルは作成後に重要度/音を変えられない）
    static final String NOTIF_CHANNEL_ID = "koetomo_notify_v2";
    private static int notifNotiId = 3001;
    private static boolean oldChannelsCleaned = false;

    /** 音の設定を変えられない古いチャンネルを消す（設定画面に残骸を残さない） */
    private static void cleanOldChannels(NotificationManager nm) {
        if (oldChannelsCleaned || Build.VERSION.SDK_INT < 26) return;
        oldChannelsCleaned = true;
        String[] old = {"koetomo_download", "koetomo_notify_v1", "koetomo_notify"};
        for (int i = 0; i < old.length; i++) {
            try {
                NotificationManager.class.getMethod("deleteNotificationChannel", String.class).invoke(nm, old[i]);
            } catch (Exception ig) {
            }
        }
    }

    /*
     * koetomo の通知(いいね/コメント/フォロー等)を Android の通知としても出す。
     * 権限が無い場合はダイアログを出さず黙って何もしない(バックグラウンドから呼ばれるため)。
     */
    /**
     * WebView を持たないバックグラウンドサービスから「お知らせ」通知を出す。
     * チャンネルは前面から出すものと同じ(音・バイブあり)なので、
     * アプリを開いていなくても同じ音で鳴る。
     */
    static void postNotifyFromBackground(Context context, String title, String text) {
        try {
            if (context == null) return;
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) return;
            NotificationManager nm = (NotificationManager) context.getSystemService("notification");
            if (nm == null) return;
            cleanOldChannels(nm);
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    Class<?> ch = Class.forName("android.app.NotificationChannel");
                    Constructor<?> ctor = ch.getConstructor(String.class, CharSequence.class, int.class);
                    // IMPORTANCE_DEFAULT(3) でないと音が鳴らない
                    Object c = ctor.newInstance(NOTIF_CHANNEL_ID, "お知らせ", Integer.valueOf(3));
                    try {
                        ch.getMethod("enableVibration", boolean.class).invoke(c, Boolean.TRUE);
                        ch.getMethod("setVibrationPattern", long[].class).invoke(c, (Object) DOWNLOAD_VIBRATE);
                    } catch (Exception ig) {
                    }
                    try {
                        android.media.AudioAttributes aa = new android.media.AudioAttributes.Builder()
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                                .build();
                        ch.getMethod("setSound", Uri.class, android.media.AudioAttributes.class)
                                .invoke(c, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION), aa);
                    } catch (Exception ig) {
                    }
                    NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, c);
                } catch (Exception ig) {
                }
            }
            android.content.Intent open = new android.content.Intent(context, MainActivity.class);
            open.setFlags(603979776 | 268435456);
            open.putExtra("koe_open_page", "notifications");
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    context, (int) (System.currentTimeMillis() & 0x7fffffff), open,
                    Build.VERSION.SDK_INT >= 23 ? 67108864 : 0);
            Notification.Builder b = null;
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    b = Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(context, NOTIF_CHANNEL_ID);
                } catch (Exception ig) {
                }
            }
            if (b == null) {
                b = new Notification.Builder(context);
                try {
                    Notification.Builder.class.getMethod("setPriority", int.class).invoke(b, Integer.valueOf(0));
                    Notification.Builder.class.getMethod("setSound", Uri.class)
                            .invoke(b, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION));
                    Notification.Builder.class.getMethod("setVibrate", long[].class).invoke(b, (Object) DOWNLOAD_VIBRATE);
                } catch (Exception ig) {
                }
            }
            b.setContentTitle(title).setContentText(text).setAutoCancel(true).setContentIntent(pi);
            try { b.setStyle(new Notification.BigTextStyle().bigText(text)); } catch (Exception ig) {}
            applySmallIcon(context, b);
            android.graphics.Bitmap logo = appLogoBitmap(context);
            if (logo != null) { try { b.setLargeIcon(logo); } catch (Exception ig) {} }
            nm.notify(bgNotiId++, b.build());
        } catch (Exception e) {
        }
    }

    private static int bgNotiId = 4001;

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

    /** 画面の描画準備ができたことを知らせる(起動スプラッシュを消す) */
    @JavascriptInterface
    public void uiReady() {
        try {
            android.content.Context c = webView.getContext();
            if (c instanceof MainActivity) ((MainActivity) c).hideSplash();
        } catch (Exception ignored) {}
    }

    /** アプリの一時ファイル使用量（バイト）を返す */
    @JavascriptInterface
    public String appStorageInfo() {
        try {
            android.content.Context c = webView.getContext();
            long cache = dirSize(c.getCacheDir()) + dirSize(c.getExternalCacheDir());
            long files = dirSize(c.getFilesDir());
            return new org.json.JSONObject().put("ok", true).put("cache", cache).put("files", files).toString();
        } catch (Exception e) {
            try { return new org.json.JSONObject().put("ok", false).toString(); } catch (Exception ig) { return "{\"ok\":false}"; }
        }
    }

    private long dirSize(java.io.File f) {
        try {
            if (f == null || !f.exists()) return 0;
            if (f.isFile()) return f.length();
            java.io.File[] fs = f.listFiles();
            long n = 0;
            for (int i = 0; fs != null && i < fs.length; i++) n += dirSize(fs[i]);
            return n;
        } catch (Exception e) { return 0; }
    }

    /** 一時ファイル(画像など)の上限。これを超えたら古いものから捨てる。 */
    public static final long CACHE_LIMIT_BYTES = 16L * 1024 * 1024;

    /** 上限を超えていたら古い一時ファイルから削除する（キャッシュを溜め込まないための自動整理） */
    @JavascriptInterface
    public String trimAppCache() {
        try {
            long freed = trimCache(webView.getContext(), CACHE_LIMIT_BYTES);
            return new org.json.JSONObject().put("ok", true).put("freed", freed).toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    /** 一時ファイルの合計が limit を超えていたら、古い順に limit の半分まで削る。 */
    public static long trimCache(android.content.Context c, long limit) {
        try {
            if (c == null) return 0;
            java.io.File dir = c.getCacheDir();
            java.util.ArrayList<java.io.File> all = new java.util.ArrayList<java.io.File>();
            collectFiles(dir, all);
            long total = 0;
            for (int i = 0; i < all.size(); i++) total += all.get(i).length();
            if (total <= limit) return 0;
            java.util.Collections.sort(all, new java.util.Comparator<java.io.File>() {
                public int compare(java.io.File a, java.io.File b) {
                    long d = a.lastModified() - b.lastModified();
                    return d < 0 ? -1 : (d > 0 ? 1 : 0);
                }
            });
            long target = limit / 2, freed = 0;
            for (int i = 0; i < all.size() && total > target; i++) {
                java.io.File f = all.get(i);
                long n = f.length();
                if (f.delete()) { total -= n; freed += n; }
            }
            return freed;
        } catch (Exception e) { return 0; }
    }

    private static void collectFiles(java.io.File f, java.util.ArrayList<java.io.File> out) {
        try {
            if (f == null || !f.exists()) return;
            if (f.isFile()) { out.add(f); return; }
            // WebView が使っている HTTP / コードキャッシュは削らない(再取得と再コンパイルで遅くなる)
            String dn = f.getName();
            if (dn.equals("WebView") || dn.equals("Code Cache") || dn.equals("GPUCache")) return;
            java.io.File[] fs = f.listFiles();
            for (int i = 0; fs != null && i < fs.length; i++) collectFiles(fs[i], out);
        } catch (Exception e) {}
    }

    /** WebView と一時ファイルのキャッシュを削除する（ログイン状態や設定は消さない） */
    @JavascriptInterface
    public String clearAppCache() {
        try {
            final android.content.Context c = webView.getContext();
            webView.post(new Runnable() {
                public void run() {
                    try { webView.clearCache(true); } catch (Exception e) {}
                }
            });
            long before = dirSize(c.getCacheDir());
            deleteDir(c.getCacheDir(), false);
            deleteDir(c.getExternalCacheDir(), false);
            long after = dirSize(c.getCacheDir());
            return new org.json.JSONObject().put("ok", true).put("freed", Math.max(0, before - after)).toString();
        } catch (Exception e) {
            try { return new org.json.JSONObject().put("ok", false).toString(); } catch (Exception ig) { return "{\"ok\":false}"; }
        }
    }

    private void deleteDir(java.io.File f, boolean self) {
        try {
            if (f == null || !f.exists()) return;
            // WebView が使用中の HTTP/コードキャッシュは触らない(消すのは webView.clearCache に任せる)
            String dn = f.getName();
            if (self && (dn.equals("WebView") || dn.equals("Code Cache") || dn.equals("GPUCache"))) return;
            if (f.isDirectory()) {
                java.io.File[] fs = f.listFiles();
                for (int i = 0; fs != null && i < fs.length; i++) deleteDir(fs[i], true);
            }
            if (self) f.delete();
        } catch (Exception e) {}
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
            cleanOldChannels(notificationManager);
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
            builder.setContentTitle(title).setContentText(text).setAutoCancel(true).setContentIntent(activity);
            applySmallIcon(context, builder);
            try {
                android.graphics.Bitmap logo = appLogoBitmap(context);
                if (logo != null) builder.setLargeIcon(logo);
            } catch (Throwable ig) {
            }
            // 長い本文が「…」で切れないように展開表示にする
            try {
                if (text != null && text.length() > 34) {
                    builder.setStyle(new Notification.BigTextStyle().bigText(text));
                }
            } catch (Throwable ig) {
            }
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
    /* 指紋・顔認証。BiometricPrompt の AuthenticationCallback は抽象クラスなので、
       リフレクション+Proxyでは作れない(実機で必ず失敗していた)。直接サブクラスにする。
       ビルド用の型だけは /tmp/bld/stubcls に置いてあり、APKには含まれない。 */
    @JavascriptInterface
    public void authBiometric() {
        final Context context = this.webView.getContext();
        if (Build.VERSION.SDK_INT < 28 || !(context instanceof Activity)) {
            resolveBio(false, "unsupported");
            return;
        }
        ((Activity) context).runOnUiThread(new Runnable() {
            public void run() {
                try {
                    java.util.concurrent.Executor mainExecutor = new java.util.concurrent.Executor() {
                        private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                        public void execute(Runnable r) { h.post(r); }
                    };
                    android.hardware.biometrics.BiometricPrompt.Builder builder = new android.hardware.biometrics.BiometricPrompt.Builder(context);
                    builder.setTitle("ロック解除");
                    builder.setSubtitle("指紋または顔認証で解除します");
                    builder.setNegativeButton("PINを使う", mainExecutor, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialogInterface, int i) {
                            KoeApiBridge.this.resolveBio(false, "cancel");
                        }
                    });
                    if (Build.VERSION.SDK_INT >= 30) {
                        // 顔認証は「弱い」区分の端末が多いので、弱い区分も許可する(0xFF = BIOMETRIC_WEAK)
                        try { builder.getClass().getMethod("setAllowedAuthenticators", int.class).invoke(builder, 0xFF); } catch (Exception ig) {}
                    }
                    android.hardware.biometrics.BiometricPrompt prompt = builder.build();
                    prompt.authenticate(new CancellationSignal(), mainExecutor, new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        public void onAuthenticationError(int code, CharSequence msg) {
                            KoeApiBridge.this.resolveBio(false, "error");
                        }
                        public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) {
                            KoeApiBridge.this.resolveBio(true, "ok");
                        }
                    });
                } catch (Throwable e) {
                    KoeApiBridge.this.resolveBio(false, "exc");
                }
            }
        });
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
            Object result;
            if (Build.VERSION.SDK_INT >= 30) {
                result = biometricManager.getClass().getMethod("canAuthenticate", int.class).invoke(biometricManager, 0xFF);
            } else {
                result = biometricManager.getClass().getMethod("canAuthenticate", new Class[0]).invoke(biometricManager, new Object[0]);
            }
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
    private static volatile String xCodeVerifier = null;
    private static volatile boolean xStateMatched = false;   // stateが合った時だけ途中状態を消す(他アプリの偽コールバック対策)
    private static volatile String xState = null;

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
            // state は必ず照合する(照合を飛ばせる抜け道を作らない)
            if (xState == null || state == null || !xState.equals(state)) {
                postXResult("{\"ok\":false,\"message\":\"認証の検証に失敗しました(state不一致)\"}");
                return;
            }
            xStateMatched = true;
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
            if (xStateMatched) {
                xCodeVerifier = null;
                xState = null;
                xStateMatched = false;
            }
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

    /* ===== 保存先フォルダ =====
     * app … Download/KoeTomo の下に Audio / Images を作って自動で振り分ける(おすすめ)
     * std … 画像は Pictures/KoeTomo、音声は Music/KoeTomo(ギャラリー・音楽アプリに出る)
     * dl  … ダウンロードフォルダの直下(他のアプリと同じ場所)
     * Android は MediaStore の都合で「最上位に KoeTomo」は作れないため、
     * 専用フォルダは Download の下に作る。 */
    static final String SAVE_PREF = "koe_save";
    // MediaStore.Downloads.EXTERNAL_CONTENT_URI(API29+)。ビルド用 android.jar が API23 のため定数で持つ。
    private static final String DOWNLOADS_URI = "content://media/external/downloads";

    static String saveFolderMode(Context c) {
        try {
            String m = c.getSharedPreferences(SAVE_PREF, 0).getString("folder", "std");
            if ("app".equals(m) || "dl".equals(m)) return m;
            return "std"; // 既定は従来どおり Pictures/KoeTomo・Music/KoeTomo
        } catch (Exception e) {
            return "std";
        }
    }

    @JavascriptInterface
    public void setSaveFolder(String mode) {
        try {
            String m = ("app".equals(mode) || "dl".equals(mode)) ? mode : "std";
            this.webView.getContext().getSharedPreferences(SAVE_PREF, 0).edit().putString("folder", m).apply();
        } catch (Exception e) {
        }
    }

    @JavascriptInterface
    public String getSaveFolder() {
        try {
            return saveFolderMode(this.webView.getContext());
        } catch (Exception e) {
            return "std";
        }
    }

    /** 保存先の決定結果。collection は API29+ の MediaStore 用、dir は API28以下のファイル用。 */
    static final class SaveTarget {
        Uri collection;
        String relativePath; // 例 "Download/KoeTomo/Audio"
        File dir;            // API28以下のときの実フォルダ
        String label;        // 画面に出す説明
    }

    static SaveTarget saveTargetFor(Context c, boolean isImage) {
        String mode = saveFolderMode(c);
        SaveTarget t = new SaveTarget();
        if ("std".equals(mode)) {
            if (isImage) {
                t.collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                t.relativePath = "Pictures/KoeTomo";
                t.dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "KoeTomo");
            } else {
                t.collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                t.relativePath = "Music/KoeTomo";
                t.dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "KoeTomo");
            }
        } else if ("dl".equals(mode)) {
            t.collection = Uri.parse(DOWNLOADS_URI);
            t.relativePath = "Download";
            t.dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        } else { // app: ダウンロード内の専用フォルダ
            String sub = isImage ? "Images" : "Audio";
            t.collection = Uri.parse(DOWNLOADS_URI);
            t.relativePath = "Download/KoeTomo/" + sub;
            t.dir = new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "KoeTomo"), sub);
        }
        t.label = t.relativePath;
        return t;
    }

    /**
     * 実際にファイルを書き出す。API29+ は MediaStore、それ未満は直接ファイル。
     * 保存できたら画面に出す保存先(例 "Download/KoeTomo/Audio")を返し、失敗したら null。
     */
    static String writeToTarget(Context c, boolean isImage, String name, String mime, byte[] data) {
        try {
            SaveTarget t = saveTargetFor(c, isImage);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put("_display_name", name);
                v.put("mime_type", mime);
                v.put("relative_path", t.relativePath);
                Uri uri = null;
                try {
                    uri = c.getContentResolver().insert(t.collection, v);
                } catch (Exception e) {
                    uri = null;
                }
                if (uri == null && !"std".equals(saveFolderMode(c))) {
                    // Downloads コレクションが使えない端末向けに、標準フォルダへ退避する
                    ContentValues v2 = new ContentValues();
                    v2.put("_display_name", name);
                    v2.put("mime_type", mime);
                    v2.put("relative_path", isImage ? "Pictures/KoeTomo" : "Music/KoeTomo");
                    uri = c.getContentResolver().insert(
                            isImage ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v2);
                    if (uri != null) t.label = isImage ? "Pictures/KoeTomo" : "Music/KoeTomo";
                }
                if (uri == null) return null;
                OutputStream os = null;
                boolean written = false;
                try {
                    os = c.getContentResolver().openOutputStream(uri);
                    if (os == null) return null;
                    os.write(data);
                    os.flush();
                    written = true;
                } finally {
                    try { if (os != null) os.close(); } catch (Exception ig) {}
                    // 書けなかった時に空のファイルが残らないよう、登録を取り消す
                    if (!written) { try { c.getContentResolver().delete(uri, null, null); } catch (Exception ig) {} }
                }
                return t.label;
            }
            t.dir.mkdirs();
            File f = new File(t.dir, name);
            FileOutputStream fo = new FileOutputStream(f);
            try {
                fo.write(data);
                fo.flush();
            } finally {
                fo.close();
            }
            try {
                c.sendBroadcast(new Intent("android.intent.action.MEDIA_SCANNER_SCAN_FILE", Uri.fromFile(f)));
            } catch (Exception ig) {
            }
            return t.label;
        } catch (Exception e) {
            return null;
        }
    }

    @JavascriptInterface
    public void saveAudio(final String str) {
        if (str == null || !str.toLowerCase().startsWith("https://") || !ImageThumbCache.isAllowedHostPublic(str)) { toastOnJs("保存元URLが不正です"); return; }
        if (!isHttpUrl(str)) { return; }
        new Thread(new Runnable() {
            public void run() {
                try {
                    HttpURLConnection httpURLConnection = (HttpURLConnection) new URL(str).openConnection();
                    httpURLConnection.setInstanceFollowRedirects(false);   // 許可ホスト以外へ飛ばされないように
                    httpURLConnection.setConnectTimeout(15000);
                    httpURLConnection.setReadTimeout(30000);
                    httpURLConnection.connect();
                    if (httpURLConnection.getResponseCode() != 200) throw new java.io.IOException("http " + httpURLConnection.getResponseCode());
                    InputStream inputStream = httpURLConnection.getInputStream();
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    byte[] bArr = new byte[8192];
                    while (true) {
                        int read = inputStream.read(bArr);
                        if (read <= 0) {
                            break;
                        }
                        if (byteArrayOutputStream.size() + read > 32 * 1024 * 1024) throw new java.io.IOException("too large");
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
                    String where = KoeApiBridge.writeToTarget(context, false, str4, str3, byteArray);
                    if (where == null) {
                        KoeApiBridge.this.toastOnJs("保存に失敗しました");
                        return;
                    }
                    KoeApiBridge.this.toastOnJs("音声を保存しました → " + where);
                    KoeApiBridge.this.showDownloadNotification("音声を保存しました", str4 + "\n保存先: 内部ストレージ/" + where);
                } catch (Throwable e) {   // OutOfMemoryError でもスレッドごと落とさず、失敗として知らせる
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
                    if (raw.length() > 44 * 1024 * 1024) {
                        toastOnJs("ファイルが大きすぎて保存できません");
                        return;
                    }   // saveAudio と同じ 32MB 相当の上限
                    byte[] decode = Base64.decode(raw, 0);
                    Context context = KoeApiBridge.this.webView.getContext();
                    String lowerCase = (str2 == null || str2.length() == 0) ? "mp3" : str2.toLowerCase();
                    if (!lowerCase.matches("[a-z0-9]{1,5}")) lowerCase = "mp3"; // ファイル名に .. や / を混ぜられないように
                    String mime = lowerCase.equals("wav") ? "audio/wav" : lowerCase.equals("m4a") ? "audio/mp4" : lowerCase.equals("ogg") ? "audio/ogg" : "audio/mpeg";
                    String str3 = "KoeTomo_" + System.currentTimeMillis() + "." + lowerCase;
                    String where = KoeApiBridge.writeToTarget(context, false, str3, mime, decode);
                    if (where == null) {
                        KoeApiBridge.this.toastOnJs("保存に失敗しました");
                        return;
                    }
                    KoeApiBridge.this.toastOnJs("音声を保存しました → " + where);
                    KoeApiBridge.this.showDownloadNotification("音声を保存しました", str3 + "\n保存先: 内部ストレージ/" + where);
                } catch (Throwable e) {   // OutOfMemoryError でもスレッドごと落とさず、失敗として知らせる
                    KoeApiBridge.this.toastOnJs("保存に失敗しました");
                }
            }
        }).start();
    }

    /** 先頭バイト(マジックナンバー)から画像形式を判定する。判定できないときは jpg。 */
    private static String sniffImageExt(byte[] b) {
        if (b == null || b.length < 12) return "jpg";
        int b0 = b[0] & 255, b1 = b[1] & 255, b2 = b[2] & 255, b3 = b[3] & 255;
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return "png";
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return "jpg";
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return "gif";
        if (b0 == 0x42 && b1 == 0x4D) return "bmp";
        if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46
                && (b[8] & 255) == 0x57 && (b[9] & 255) == 0x45 && (b[10] & 255) == 0x42 && (b[11] & 255) == 0x50) return "webp";
        if ((b[4] & 255) == 0x66 && (b[5] & 255) == 0x74 && (b[6] & 255) == 0x79 && (b[7] & 255) == 0x70) {
            int b8 = b[8] & 255, b9 = b[9] & 255, b10 = b[10] & 255;
            if (b8 == 0x68 && b9 == 0x65 && b10 == 0x69) return "heic"; // heic/heif
            if (b8 == 0x6D && b9 == 0x69 && b10 == 0x66) return "heic";
            if (b8 == 0x61 && b9 == 0x76 && b10 == 0x69) return "avif";
        }
        return "jpg";
    }

    private static String imageMimeOf(String ext) {
        if ("png".equals(ext)) return "image/png";
        if ("gif".equals(ext)) return "image/gif";
        if ("webp".equals(ext)) return "image/webp";
        if ("bmp".equals(ext)) return "image/bmp";
        if ("heic".equals(ext)) return "image/heic";
        if ("avif".equals(ext)) return "image/avif";
        return "image/jpeg";
    }

    @JavascriptInterface
    public void saveImage(final String str) {
        saveImage(str, "original");
    }

    /**
     * 画像を保存する。fmt が "original" のときは取得したデータをそのまま(正しい拡張子で)保存し、
     * "jpg" / "png" / "webp" のときは端末上で変換してから保存する。
     */
    @JavascriptInterface
    public void saveImage(final String str, final String fmt) {
        if (str == null || !str.toLowerCase().startsWith("https://") || !ImageThumbCache.isAllowedHostPublic(str)) { toastOnJs("保存元URLが不正です"); return; }
        if (!isHttpUrl(str)) { return; }
        new Thread(new Runnable() {
            public void run() {
                try {
                    HttpURLConnection httpURLConnection = (HttpURLConnection) new URL(str).openConnection();
                    httpURLConnection.setInstanceFollowRedirects(false);   // 許可ホスト以外へ飛ばされないように
                    httpURLConnection.setConnectTimeout(15000);
                    httpURLConnection.setReadTimeout(20000);
                    httpURLConnection.connect();
                    if (httpURLConnection.getResponseCode() != 200) throw new java.io.IOException("http " + httpURLConnection.getResponseCode());
                    InputStream inputStream = httpURLConnection.getInputStream();
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    byte[] bArr = new byte[8192];
                    while (true) {
                        int read = inputStream.read(bArr);
                        if (read <= 0) {
                            break;
                        }
                        if (byteArrayOutputStream.size() + read > 32 * 1024 * 1024) throw new java.io.IOException("too large");
                        byteArrayOutputStream.write(bArr, 0, read);
                    }
                    inputStream.close();
                    httpURLConnection.disconnect();
                    byte[] byteArray = byteArrayOutputStream.toByteArray();
                    Context context = KoeApiBridge.this.webView.getContext();
                    String ext = sniffImageExt(byteArray);
                    String want = (fmt == null || fmt.length() == 0) ? "original" : fmt.toLowerCase();
                    if ("jpeg".equals(want)) want = "jpg";
                    if (!"original".equals(want) && !want.equals(ext)) {
                        byte[] converted = KoeApiBridge.convertImage(byteArray, want);
                        if (converted != null) {
                            byteArray = converted;
                            ext = want;
                        } else {
                            KoeApiBridge.this.toastOnJs("変換できないため元の形式で保存します");
                        }
                    }
                    String mime = imageMimeOf(ext);
                    String name = "KoeTomo_" + System.currentTimeMillis() + "." + ext;
                    String where = KoeApiBridge.writeToTarget(context, true, name, mime, byteArray);
                    if (where == null) {
                        KoeApiBridge.this.toastOnJs("保存に失敗しました");
                        return;
                    }
                    KoeApiBridge.this.toastOnJs("画像を保存しました (" + ext.toUpperCase() + ") → " + where);
                    KoeApiBridge.this.showDownloadNotification("画像を保存しました", name + "\n保存先: 内部ストレージ/" + where);
                } catch (Throwable e) {   // OutOfMemoryError でもスレッドごと落とさず、失敗として知らせる
                    KoeApiBridge.this.toastOnJs("保存に失敗しました");
                }
            }
        }).start();
    }

    /** 端末上で画像形式を変換する。失敗したら null を返して呼び出し側で元の形式にフォールバックする。 */
    private static byte[] convertImage(byte[] src, String want) {
        try {
            Bitmap bm = BitmapFactory.decodeByteArray(src, 0, src.length);
            if (bm == null) return null;
            Bitmap.CompressFormat cf;
            int quality = 92;
            if ("png".equals(want)) {
                cf = Bitmap.CompressFormat.PNG;
                quality = 100;
            } else if ("webp".equals(want)) {
                cf = Bitmap.CompressFormat.WEBP;
            } else {
                cf = Bitmap.CompressFormat.JPEG;
            }
            if (cf == Bitmap.CompressFormat.JPEG && bm.hasAlpha()) {
                // JPEG は透過を持てないので白背景に合成してから変換する
                Bitmap flat = Bitmap.createBitmap(bm.getWidth(), bm.getHeight(), Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(flat);
                cv.drawColor(-1);
                cv.drawBitmap(bm, 0.0f, 0.0f, null);
                bm.recycle();
                bm = flat;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean ok = bm.compress(cf, quality, out);
            bm.recycle();
            if (!ok) return null;
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /* 応援通話の受け手: 画面が止まっている間(アプリが裏)も、3分ごとに受付状態を送り直して
       サーバー側で勝手にオフラインにならないようにする。state が空なら止める。 */
    private volatile String beatRid = "";
    private volatile String beatState = "";
    private Thread beatThread = null;

    /** 画面を閉じる時に、受付中の定期通知(オンライン表示)を止める */
    public synchronized void stopReceiverBeat() {
        beatRid = "";
        beatState = "";
        if (beatThread != null) beatThread.interrupt();
    }

    @JavascriptInterface
    public synchronized void setReceiverBeat(String rid, String state) {
        beatRid = rid == null ? "" : rid;
        beatState = state == null ? "" : state;
        if (beatRid.length() == 0 || beatState.length() == 0) return;
        if (beatThread != null && beatThread.isAlive()) return;
        beatThread = new Thread(new Runnable() {
            public void run() {
                while (beatRid.length() > 0 && beatState.length() > 0) {
                    for (int i = 0; i < 180 && beatRid.length() > 0 && beatState.length() > 0; i++) {
                        try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                    }
                    if (beatRid.length() == 0 || beatState.length() == 0) break;
                    try {
                        session.dispatch("update_cheering_receiver_status", new JSONArray().put(beatRid).put(beatState));
                    } catch (Throwable ig) {
                    }
                }
            }
        });
        beatThread.setDaemon(true);
        beatThread.start();
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
                CallForegroundService.lastMic = 0;
                context.stopService(intent);
                // 裏で通話が終わった時は、通知の確認を常駐サービスへ引き継ぐ(でないと次に開くまで通知が来ない)
                if (context instanceof MainActivity && !((MainActivity) context).uiForeground) {
                    try { KoeNotifyService.start(context.getApplicationContext()); } catch (Exception ig) {}
                    // Android 12 以降は裏からの常駐サービス起動が拒否されることがあるので、その場合に備えて Activity 側の確認を残す
                    if (Build.VERSION.SDK_INT < 31) ((MainActivity) context).stopBgNotifPoller();
                }
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

    /* マイクの状態(0=オン 1=ミュート 2=聞き専)。通知の表示と小窓のボタンを合わせる */
    @JavascriptInterface
    public void setCallState(int st) {
        try {
            Context context = this.webView.getContext();
            if (context instanceof MainActivity) {
                ((MainActivity) context).onMicStateChanged(st);
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
    public boolean copyText(final String text) {
        try {
            final Context c = this.webView.getContext();
            final boolean[] ok = new boolean[1];
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                public void run() {
                    try {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) c.getSystemService("clipboard");
                        android.content.ClipData cd = android.content.ClipData.newPlainText("KoeTomo+ log", text == null ? "" : text);
                        try {
                            if (Build.VERSION.SDK_INT >= 33) {
                                // 他のアプリのクリップボード表示に内容を出さない(EXTRA_IS_SENSITIVE)
                                android.os.PersistableBundle pb = new android.os.PersistableBundle();
                                pb.putBoolean("android.content.extra.IS_SENSITIVE", true);
                                cd.getDescription().getClass().getMethod("setExtras", android.os.PersistableBundle.class).invoke(cd.getDescription(), pb);
                            }
                        } catch (Throwable ig2) {
                        }
                        cm.setPrimaryClip(cd);
                        ok[0] = true;
                    } catch (Throwable ig) {
                    }
                    latch.countDown();
                }
            });
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS);
            return ok[0];
        } catch (Throwable e) {
            return false;
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
    /* 画面を消すとWi-Fiが省電力に入り、通話の音が途切れる・切断される端末があるため、通話中だけ保持する */
    private WifiManager.WifiLock callWifiLock = null;
    /* 通話前のスピーカー設定。通話が終わったら元へ戻す(戻さないと他のアプリの音まで受話口から出る) */
    private boolean speakerWasOn = false;

    /* 通話中に画面を暗くしない(常時点灯)かどうかを切り替える。設定で選べるようにするための口。 */
    @JavascriptInterface
    public void setKeepScreenOn(final boolean on) {
        try {
            Context context = this.webView.getContext();
            if (!(context instanceof Activity)) return;
            final Activity act = (Activity) context;
            act.runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        // WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON = 128
                        if (on) act.getWindow().addFlags(128);
                        else act.getWindow().clearFlags(128);
                    } catch (Exception ig) {
                    }
                }
            });
        } catch (Exception ig) {
        }
    }

    /* ---- 通話の音の出口(スピーカー/有線/USB/Bluetooth など全部に対応) ----
       API23の android.jar には後から増えた定数が無いので、種類の番号は数値で書く。 */
    private static final int DEV_WIRED_HEADSET = 3;
    private static final int DEV_WIRED_HEADPHONES = 4;
    private static final int DEV_BLUETOOTH_SCO = 7;
    private static final int DEV_BLUETOOTH_A2DP = 8;
    private static final int DEV_USB_DEVICE = 11;
    private static final int DEV_USB_ACCESSORY = 12;
    private static final int DEV_USB_HEADSET = 22;
    private static final int DEV_HEARING_AID = 23;
    private static final int DEV_BLE_HEADSET = 26;
    private static final int DEV_BLE_SPEAKER = 27;
    private static final int DEV_BLE_BROADCAST = 30;
    private android.media.AudioDeviceCallback callRouteWatcher = null;
    private volatile boolean scoStarted = false;

    private static boolean isBluetoothType(int t) {
        return t == DEV_BLUETOOTH_SCO || t == DEV_BLUETOOTH_A2DP || t == DEV_HEARING_AID
                || t == DEV_BLE_HEADSET || t == DEV_BLE_SPEAKER || t == DEV_BLE_BROADCAST;
    }

    private static boolean isWiredType(int t) {
        return t == DEV_WIRED_HEADSET || t == DEV_WIRED_HEADPHONES || t == DEV_USB_DEVICE
                || t == DEV_USB_ACCESSORY || t == DEV_USB_HEADSET;
    }

    /* つながっている出力機器を見て、スピーカーを使うかどうかと Bluetooth の通話用接続を決める */
    private void applyCallRoute(AudioManager am) {
        if (Build.VERSION.SDK_INT < 23) return;   // AudioDeviceInfo は API 23 から(21〜22 では NoClassDefFoundError になる)
        try {
            boolean btOut = false, wiredOut = false;
            android.media.AudioDeviceInfo[] outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (int i = 0; outs != null && i < outs.length; i++) {
                int t = outs[i].getType();
                if (isBluetoothType(t)) btOut = true;
                else if (isWiredType(t)) wiredOut = true;
            }
            if (btOut) {
                // Bluetooth は通話用(SCO)につなぐ。つながるまでの間はスピーカーを切って待つ
                am.setSpeakerphoneOn(false);
                try {
                    if (!scoStarted) {
                        am.startBluetoothSco();
                        scoStarted = true;
                    }
                    am.setBluetoothScoOn(true);
                } catch (Exception ig) {
                }
            } else {
                stopBluetoothScoSafe(am);
                am.setSpeakerphoneOn(!wiredOut); // 有線/USBがあればそちら、何も無ければスピーカー
            }
        } catch (Exception e) {
            try { am.setSpeakerphoneOn(true); } catch (Exception ig) {}
        }
    }

    private void stopBluetoothScoSafe(AudioManager am) {
        try {
            if (scoStarted) {
                am.setBluetoothScoOn(false);
                am.stopBluetoothSco();
                scoStarted = false;
            }
        } catch (Exception ig) {
        }
    }

    /* 通話中にイヤホンの抜き差しやBluetoothの接続・切断があったら、出口を選び直す */
    private void registerCallRouteWatcher(final AudioManager am) {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            if (callRouteWatcher != null) return;
            callRouteWatcher = new android.media.AudioDeviceCallback() {
                private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                private final Runnable again = new Runnable() { public void run() { if (callAudioActive) applyCallRoute(am); } };

                // 接続・切断の通知は短い間に何度も来て、機器が使える状態になるのも少し遅れる。
                // まとめて選び直し、落ち着いたころにもう一度選び直す(途中の切り替えで音が出なくなるのを防ぐ)。
                private void settle() {
                    h.removeCallbacks(again);
                    h.postDelayed(again, 400);
                    h.postDelayed(again, 2500);
                }

                public void onAudioDevicesAdded(android.media.AudioDeviceInfo[] added) {
                    settle();
                }

                public void onAudioDevicesRemoved(android.media.AudioDeviceInfo[] removed) {
                    boolean btGone = false;
                    try {
                        for (android.media.AudioDeviceInfo d : removed) {
                            int t = d.getType();
                            if (isBluetoothType(t)) btGone = true;
                        }
                    } catch (Exception ig) { btGone = true; }
                    if (btGone) {
                        try { am.setBluetoothScoOn(false); am.stopBluetoothSco(); } catch (Exception ig) {}   // 切れたBluetoothの接続要求を残さない
                    }
                    if (btGone) scoStarted = false;
                    settle();
                }
            };
            am.registerAudioDeviceCallback(callRouteWatcher, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (Exception e) {
            callRouteWatcher = null;
        }
    }

    private void unregisterCallRouteWatcher(AudioManager am) {
        try {
            if (callRouteWatcher != null) am.unregisterAudioDeviceCallback(callRouteWatcher);
        } catch (Exception ig) {
        }
        callRouteWatcher = null;
    }

    @JavascriptInterface
    public void startCallAudio() {
        try {
            Context context = this.webView.getContext();
            final AudioManager audioManager = (AudioManager) context.getSystemService("audio");
            if (audioManager == null) {
                return;
            }
            if (!callAudioActive) this.speakerWasOn = audioManager.isSpeakerphoneOn();   // 二重に呼ばれても元の状態を覚え直さない
            callAudioActive = true;
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            applyCallRoute(audioManager); // ヘッドセット・Bluetooth等があればそちら、無ければスピーカー
            registerCallRouteWatcher(audioManager);
            int result = audioManager.requestAudioFocus(this.callFocusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            this.callAudioFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Exception e) {
        }
        try {
            Context context2 = this.webView.getContext();
            PowerManager powerManager = (PowerManager) context2.getSystemService("power");
            if (powerManager != null && this.callWakeLock != null && !this.callWakeLock.isHeld()) {
                this.callWakeLock.acquire(4L * 60L * 60L * 1000L);   // 期限切れで外れていたら取り直す
            }
            if (powerManager != null && this.callWakeLock == null) {
                PowerManager.WakeLock newWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KoeTomo:CallWakeLock");
                this.callWakeLock = newWakeLock;
                newWakeLock.setReferenceCounted(false);
                this.callWakeLock.acquire(4L * 60L * 60L * 1000L);   // 解除し忘れ対策の保険(通話終了時に release する)
            }
        } catch (Exception e) {
        }
        try {
            Context context3 = this.webView.getContext().getApplicationContext();
            WifiManager wifiManager = (WifiManager) context3.getSystemService("wifi");
            if (wifiManager != null && this.callWifiLock == null) {
                /* 3 = WIFI_MODE_FULL_HIGH_PERF(API12以降)。省電力のスリープを止めて遅延を抑える */
                WifiManager.WifiLock lock = wifiManager.createWifiLock(3, "KoeTomo:CallWifiLock");
                this.callWifiLock = lock;
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception e) {
        }
    }

    /* マイクの入り切りで端末側が通話モードや出口を勝手に戻すことがあるため、JSから呼んで選び直す */
    private final android.os.Handler routeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile boolean callAudioActive = false;

    boolean isCallAudioActive() {
        return callAudioActive;
    }

    @JavascriptInterface
    public void refreshCallRoute() {
        final Context c = this.webView.getContext();
        final AudioManager am = (AudioManager) c.getSystemService("audio");
        if (am == null) return;
        final android.os.Handler h = routeHandler;
        if (!callAudioActive) return;   // 通話中でなければ何もしない(終わった後に通話モードへ戻さない)
        Runnable fix = new Runnable() {
            public void run() {
                try {
                    if (!callAudioActive) return; // 通話が終わったあとに通話モードへ戻さない
                    if (am.getMode() != AudioManager.MODE_IN_COMMUNICATION) am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    applyCallRoute(am);
                } catch (Exception ig) {
                }
            }
        };
        h.post(fix);
        h.postDelayed(fix, 300);
        h.postDelayed(fix, 1500);
    }

    @JavascriptInterface
    public void stopCallAudio() {
        callAudioActive = false;
        routeHandler.removeCallbacksAndMessages(null); // 予約済みの「通話モードに戻す」を取り消す
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
            unregisterCallRouteWatcher(audioManager);
            stopBluetoothScoSafe(audioManager);
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.setSpeakerphoneOn(this.speakerWasOn);
        } catch (Exception e) {
        }
        try {
            if (this.callWakeLock != null && this.callWakeLock.isHeld()) {
                this.callWakeLock.release();
            }
            this.callWakeLock = null;
        } catch (Exception e) {
        }
        try {
            if (this.callWifiLock != null && this.callWifiLock.isHeld()) {
                this.callWifiLock.release();
            }
            this.callWifiLock = null;
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
    /** 診断ログを同期で返す(非同期ブリッジが詰まっていても取り出せるようにする) */
    @JavascriptInterface
    public String nativeLog() {
        try {
            return this.session.dispatch("get_native_log", new JSONArray());
        } catch (Throwable t) {
            try { return new JSONObject().put("ok", false).put("error", String.valueOf(t)).toString(); } catch (Exception ig) { return "{\"ok\":false}"; }
        }
    }

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
                                // downloadUpdate はこの URL と完全一致したものだけを受け付ける
                                // (githubusercontent は誰でもアセットを置けるため、ホスト一致だけでは足りない)
                                if (isTrustedUpdateUrl(apk)) KoeApiBridge.lastUpdateApkUrl = apk;
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

    /** checkUpdate が実際に取得した更新APKのURL。これ以外はダウンロードしない。 */
    private static volatile String lastUpdateApkUrl = null;

    /**
     * 端末に落ちてきたAPKが、このアプリと同じ署名鍵で署名されているかを確認する。
     * 共有ストレージ上のファイルは他アプリに差し替えられうるため、
     * インストール画面を出す前に必ずここを通す。
     */
    private static boolean apkSignatureOk(Context c, String path) {
        try {
            if (path == null || path.length() == 0) return false;
            android.content.pm.PackageInfo pi = c.getPackageManager()
                    .getPackageArchiveInfo(path, android.content.pm.PackageManager.GET_SIGNATURES);
            if (pi == null || pi.signatures == null || pi.signatures.length != 1) return false;
            byte[] dg = java.security.MessageDigest.getInstance("SHA-256").digest(pi.signatures[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < dg.length; i++) sb.append(String.format("%02x", dg[i] & 0xff));
            return MainActivity.RELEASE_CERT_SHA256.equalsIgnoreCase(sb.toString());
        } catch (Throwable t) {
            return false;
        }
    }

    @JavascriptInterface
    public void downloadUpdate(final String url) {
        if (!isHttpUrl(url) || !isTrustedUpdateUrl(url)) {
            toastOnJs("更新URLが不正です");
            return;
        }
        String pinned = lastUpdateApkUrl;
        if (pinned == null || !pinned.equals(url)) {
            toastOnJs("更新URLが確認できません。もう一度「更新を確認」を押してください");
            return;
        }
        try {
            final Context context = this.webView.getContext().getApplicationContext();   // Activity を握らない(破棄後も完了通知を受けられる)
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
            // 共有ダウンロードフォルダに置くと、インストール確認までの間に他アプリが差し替えられる。
            // アプリ専用の外部領域に落とす。
            try {
                java.io.File stale = new java.io.File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "KoeTomoPlus-update.apk");
                if (stale.exists()) stale.delete();   // 前回の残りがあると別名で保存され、検証と実体がずれる
            } catch (Exception ignored) {}
            req.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "KoeTomoPlus-update.apk");
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
                        // 署名がこのアプリと同じでなければインストール画面を出さない
                        String local = null;
                        try {
                            java.io.File d = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                            if (d != null) local = new java.io.File(d, "KoeTomoPlus-update.apk").getAbsolutePath();
                        } catch (Exception ig) {
                        }
                        if (local == null || !apkSignatureOk(ctx, local)) {
                            try { if (local != null) new java.io.File(local).delete(); } catch (Exception ig) {}
                            KoeApiBridge.this.toastOnJs("更新ファイルの署名を確認できませんでした。インストールを中止しました");
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
            android.content.IntentFilter dlFilter = new android.content.IntentFilter("android.intent.action.DOWNLOAD_COMPLETE");
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    Context.class.getMethod("registerReceiver", android.content.BroadcastReceiver.class, android.content.IntentFilter.class, int.class)
                            .invoke(context, holder[0], dlFilter, Integer.valueOf(4)); // RECEIVER_NOT_EXPORTED
                } catch (Exception e) {
                    context.registerReceiver(holder[0], dlFilter);
                }
            } else {
                context.registerReceiver(holder[0], dlFilter);
            }
        } catch (Exception e) {
            toastOnJs("更新のダウンロードに失敗しました: " + e);
        }
    }
}
