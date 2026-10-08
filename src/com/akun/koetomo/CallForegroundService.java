package com.akun.koetomo;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class CallForegroundService extends Service {
    static final String CHANNEL_ID = "koetomo_call";
    static final int NOTI_ID = 1001;
    /* ServiceInfo.FOREGROUND_SERVICE_TYPE_* の値(API29/30 で追加。古いandroid.jarには定数が無いため直接書く) */
    static final int FOREGROUND_TYPE_DATA_SYNC = 1;
    static final int FOREGROUND_TYPE_MICROPHONE = 128;

    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    public IBinder onBind(Intent intent) {
        return null;
    }

    /*
     * API26+ (NotificationChannel) はこのビルド環境のandroid.jarがAPI23までしか無いため
     * リフレクション経由で呼び出す(端末が実際にAPI26+ならフレームワークに実クラスが存在する)。
     */
    static int lastMic = 0;
    static volatile boolean running = false; /* サービスが動いている間だけ通知を書き換える(終了後に通知が残らないように) */

    /* 通知を作る。マイクの状態(0=オン 1=ミュート 2=聞き専)を文面とボタンに反映する */
    static Notification buildNotification(Context ctx, int mic) {
        Intent intent2 = new Intent(ctx, MainActivity.class);
        // FLAG_ACTIVITY_NEW_TASK(0x10000000)が無いと、Serviceコンテキストから発火する通知タップ時に
        // タスクを正しく前面化できず「タップしても通話に戻らない」ことがあったため追加。
        intent2.setFlags(603979776 | 268435456);
        int fl = Build.VERSION.SDK_INT >= 23 ? 67108864 | 134217728 : 0;
        PendingIntent activity = PendingIntent.getActivity(ctx, 0, intent2, fl);
        Notification.Builder builder = null;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Constructor<Notification.Builder> ctor = Notification.Builder.class.getConstructor(Context.class, String.class);
                builder = ctor.newInstance(ctx, CHANNEL_ID);
            } catch (Exception e) {
            }
        }
        if (builder == null) {
            builder = new Notification.Builder(ctx);
        }
        String state = mic == 1 ? "マイク: ミュート中" : mic == 2 ? "聞き専(発言していません)" : "マイク: オン";
        builder.setContentTitle("KoeTomo 通話中").setContentText(state + " ・ タップで通話に戻る").setOngoing(true).setContentIntent(activity);
        try {
            Intent mi = new Intent("com.akun.koetomo.CALL_MUTE");
            mi.setPackage(ctx.getPackageName());
            mi.putExtra("t", MainActivity.callActionToken());
            Intent li = new Intent("com.akun.koetomo.CALL_LEAVE");
            li.setPackage(ctx.getPackageName());
            li.putExtra("t", MainActivity.callActionToken());
            if (mic != 2) {
                builder.addAction(android.R.drawable.ic_btn_speak_now, mic == 1 ? "ミュート解除" : "ミュート", PendingIntent.getBroadcast(ctx, 1, mi, fl));
            }
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "退出", PendingIntent.getBroadcast(ctx, 2, li, fl));
        } catch (Throwable ig) {
        }
        // 受話器アイコンをやめてアプリ自身のロゴにする（通知が全部同じ📞に見えていたため）
        KoeApiBridge.applySmallIcon(ctx, builder);
        return builder.build();
    }

    /* 通話中の通知だけを書き換える(サービスは作り直さない) */
    static void refresh(Context ctx, int mic) {
        lastMic = mic;
        if (!running) return;
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService("notification");
            if (nm != null) nm.notify(NOTI_ID, buildNotification(ctx, mic));
        } catch (Throwable ig) {
        }
    }

    public int onStartCommand(Intent intent, int i, int i2) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> channelCls = Class.forName("android.app.NotificationChannel");
                Constructor<?> ctor = channelCls.getConstructor(String.class, CharSequence.class, int.class);
                Object notificationChannel = ctor.newInstance(CHANNEL_ID, "通話中", Integer.valueOf(2));
                channelCls.getMethod("setShowBadge", boolean.class).invoke(notificationChannel, Boolean.FALSE);
                NotificationManager notificationManager = (NotificationManager) getSystemService("notification");
                if (notificationManager != null) {
                    NotificationManager.class.getMethod("createNotificationChannel", channelCls).invoke(notificationManager, notificationChannel);
                }
            } catch (Exception e) {
            }
        }
        running = true;
        Notification notification = buildNotification(this, lastMic);
        /*
         * Android 10(API29)以降は、常駐サービスの「種類」を宣言しないと
         * アプリが裏に回っている間はマイクを使わせてもらえない(通話が無音になる)。
         * マニフェストに microphone|dataSync を宣言したうえで、ここでも同じ種類を渡す。
         * この3引数版はAPI29以降にしか無く、このビルド環境のandroid.jarはAPI23までなので
         * リフレクションで呼ぶ。失敗したときは従来どおり種類なしで起動する(通話は止めない)。
         */
        boolean started = false;
        // マイクの許可が無い(聞き専で入る等)のに microphone 種類で起動すると SecurityException になるため、その時は dataSync だけにする
        boolean hasMic = Build.VERSION.SDK_INT < 23 || checkSelfPermission("android.permission.RECORD_AUDIO") == 0;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Method m = Service.class.getMethod("startForeground", int.class, Notification.class, int.class);
                int type = FOREGROUND_TYPE_DATA_SYNC | (hasMic ? FOREGROUND_TYPE_MICROPHONE : 0);
                m.invoke(this, Integer.valueOf(NOTI_ID), notification, Integer.valueOf(type));
                started = true;
            } catch (Throwable t) {
            }
        }
        if (!started) {
            try {
                startForeground(NOTI_ID, notification);
            } catch (Throwable t) {
                // 起動できなくても通話自体は止めない(通知が出ないだけ)
            }
        }
        return 2; // START_NOT_STICKY: プロセス再起動時に通話なしの「通話中」通知が残らないようにする
    }

    // 履歴からスワイプでアプリを終了した場合、Activity の onDestroy が来ないことがある。
    // 自分が開いている枠を閉じてからサービスを止める(枠が残ると次回作成が拒否される)。
    public void onTaskRemoved(Intent rootIntent) {
        try {
            new KoeSession(getApplicationContext()).closeMyRoomOnExit();
        } catch (Exception e) {
        }
        try { stopForeground(true); } catch (Exception e) {}
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }
}
