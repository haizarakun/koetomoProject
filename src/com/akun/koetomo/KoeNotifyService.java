package com.akun.koetomo;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import java.lang.reflect.Constructor;
import java.util.ArrayList;

/**
 * アプリが裏に回っている間も新着通知を取りに行くための常駐サービス。
 *
 * これまでのバックグラウンド通知は Activity 内の素のスレッドで回していたため、
 * ホームに戻ってしばらくすると Android がプロセスを止めて通知が来なくなっていた。
 * フォアグラウンドサービスにすることで、アプリを離れていても鳴るようにする。
 *
 * 常駐中の表示は IMPORTANCE_MIN の無音チャンネルなので、これ自体は音も出ないし
 * 通知欄でも最小表示になる。新着そのものは既存の「お知らせ」チャンネル
 * (KoeApiBridge.NOTIF_CHANNEL_ID / 音・バイブあり) で出す。
 */
public class KoeNotifyService extends Service {
    static final String CHANNEL_ID = "koetomo_bg_v1";
    static final int NOTI_ID = 1002;
    static final String PREF = "koe_bgnotif";

    private static volatile boolean running = false;
    private Thread worker = null;

    public IBinder onBind(Intent intent) {
        return null;
    }

    static boolean enabled(Context c) {
        try {
            return c.getSharedPreferences(PREF, 0).getBoolean("enabled", true);
        } catch (Exception e) {
            return true;
        }
    }

    /** アプリが裏に回ったときに呼ぶ。設定がオフなら何もしない。 */
    static void start(Context c) {
        try {
            if (!enabled(c)) return;
            Intent i = new Intent(c, KoeNotifyService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    Context.class.getMethod("startForegroundService", Intent.class).invoke(c, i);
                    return;
                } catch (Exception e) {
                }
            }
            c.startService(i);
        } catch (Exception e) {
        }
    }

    /** アプリが前面に戻ったときに呼ぶ。 */
    static void stop(Context c) {
        try {
            running = false;
            c.stopService(new Intent(c, KoeNotifyService.class));
        } catch (Exception e) {
        }
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTI_ID, buildOngoing());
        } catch (Exception e) {
            // フォアグラウンド化できない端末では通知だけ諦めて終了する(クラッシュさせない)
            stopSelf();
            return 2;
        }
        if (!running) {
            running = true;
            worker = new Thread(new Runnable() {
                public void run() {
                    Context app = getApplicationContext();
                    KoeSession session;
                    try {
                        session = new KoeSession(app);
                    } catch (Throwable t) {
                        return;
                    }
                    int n = 0;
                    while (running) {
                        try {
                            Thread.sleep(n == 0 ? 5000 : 20000);
                        } catch (InterruptedException e) {
                            return;
                        }
                        if (!running) return;
                        n++;
                        try {
                            pollOnce(app, session);
                        } catch (Throwable t) {
                        }
                    }
                }
            }, "koe-bgnotify");
            worker.setDaemon(true);
            worker.start();
        }
        return 1; // START_STICKY: 一時的に落とされても復帰させる
    }

    public void onDestroy() {
        running = false;
        try {
            if (worker != null) worker.interrupt();
        } catch (Exception e) {
        }
        worker = null;
        try { stopForeground(true); } catch (Exception e) {}
        super.onDestroy();
    }

    private Notification buildOngoing() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> ch = Class.forName("android.app.NotificationChannel");
                Constructor<?> ctor = ch.getConstructor(String.class, CharSequence.class, int.class);
                Object c = ctor.newInstance(CHANNEL_ID, "新着の確認", Integer.valueOf(1)); // IMPORTANCE_MIN
                try { ch.getMethod("setShowBadge", boolean.class).invoke(c, Boolean.FALSE); } catch (Exception ig) {}
                try { ch.getMethod("setSound", android.net.Uri.class, android.media.AudioAttributes.class).invoke(c, null, null); } catch (Exception ig) {}
                NotificationManager nm = (NotificationManager) getSystemService("notification");
                if (nm != null) NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, c);
            } catch (Exception e) {
            }
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(603979776 | 268435456);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, Build.VERSION.SDK_INT >= 23 ? 67108864 : 0);
        Notification.Builder b = null;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                b = Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(this, CHANNEL_ID);
            } catch (Exception e) {
            }
        }
        if (b == null) b = new Notification.Builder(this);
        b.setContentTitle("新着を確認しています").setContentText("アプリを開いていなくても通知を受け取ります")
         .setOngoing(true).setContentIntent(pi);
        try { Notification.Builder.class.getMethod("setPriority", int.class).invoke(b, Integer.valueOf(-2)); } catch (Exception ig) {}
        KoeApiBridge.applySmallIcon(this, b);
        return b.build();
    }

    /**
     * 新着通知を1回だけ取得して、増えていれば Android の通知として出す。
     * 既読位置(last_ts)は MainActivity 側のポーラーと同じ SharedPreferences を共有するので、
     * 前面/背面が切り替わっても同じ通知が二重に出ることはない。
     */
    static void pollOnce(Context c, KoeSession session) {
        try {
            if (session == null || !session.hasAuthToken()) return;
            String res = session.dispatch("get_notifications", new org.json.JSONArray().put("normal"));
            if (res == null) return;
            org.json.JSONObject o = new org.json.JSONObject(res);
            if (!o.optBoolean("ok")) return;
            org.json.JSONArray arr = o.optJSONArray("notifications");
            if (arr == null) return;
            SharedPreferences sp = c.getSharedPreferences(PREF, 0);
            long last = sp.getLong("last_ts", -1);
            long newest = 0;
            ArrayList<org.json.JSONObject> fresh = new ArrayList<org.json.JSONObject>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject n = arr.optJSONObject(i);
                if (n == null) continue;
                long t = MainActivity.notifTsPublic(n.optString("created_at", ""));
                if (t > newest) newest = t;
                if (last >= 0 && t > last) fresh.add(n);
            }
            if (last < 0) {
                sp.edit().putLong("last_ts", newest).apply();
                return;
            }
            if (fresh.isEmpty()) return;
            sp.edit().putLong("last_ts", Math.max(newest, last)).apply();
            int shown = 0;
            for (int i = 0; i < fresh.size(); i++) {
                if (shown >= 5) break;
                org.json.JSONObject n = fresh.get(i);
                String name = n.optString("name", "");
                String msg = n.optString("message", "");
                String title = name.length() > 0 ? name : "声とも+";
                String text = msg.length() > 0 ? (msg.startsWith("さん") ? name + msg : msg) : "新しい通知があります";
                KoeApiBridge.postNotifyFromBackground(c, title, text);
                shown++;
            }
            if (fresh.size() > shown) {
                KoeApiBridge.postNotifyFromBackground(c, "声とも+", "ほか " + (fresh.size() - shown) + " 件の新しい通知");
            }
        } catch (Exception e) {
        }
    }
}
