package com.akun.koetomo;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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

    public IBinder onBind(Intent intent) {
        return null;
    }

    /*
     * API26+ (NotificationChannel) はこのビルド環境のandroid.jarがAPI23までしか無いため
     * リフレクション経由で呼び出す(端末が実際にAPI26+ならフレームワークに実クラスが存在する)。
     */
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
        Intent intent2 = new Intent(this, MainActivity.class);
        // FLAG_ACTIVITY_NEW_TASK(0x10000000)が無いと、Serviceコンテキストから発火する通知タップ時に
        // タスクを正しく前面化できず「タップしても通話に戻らない」ことがあったため追加。
        intent2.setFlags(603979776 | 268435456);
        PendingIntent activity = PendingIntent.getActivity(this, 0, intent2, Build.VERSION.SDK_INT >= 23 ? 67108864 : 0);
        Notification.Builder builder = null;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Constructor<Notification.Builder> ctor = Notification.Builder.class.getConstructor(android.content.Context.class, String.class);
                builder = ctor.newInstance(this, CHANNEL_ID);
            } catch (Exception e) {
            }
        }
        if (builder == null) {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle("KoeTomo 通話中").setContentText("タップで通話に戻る").setOngoing(true).setContentIntent(activity);
        // 受話器アイコンをやめてアプリ自身のロゴにする（通知が全部同じ📞に見えていたため）
        KoeApiBridge.applySmallIcon(this, builder);
        Notification notification = builder.build();
        /*
         * Android 10(API29)以降は、常駐サービスの「種類」を宣言しないと
         * アプリが裏に回っている間はマイクを使わせてもらえない(通話が無音になる)。
         * マニフェストに microphone|dataSync を宣言したうえで、ここでも同じ種類を渡す。
         * この3引数版はAPI29以降にしか無く、このビルド環境のandroid.jarはAPI23までなので
         * リフレクションで呼ぶ。失敗したときは従来どおり種類なしで起動する(通話は止めない)。
         */
        boolean started = false;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Method m = Service.class.getMethod("startForeground", int.class, Notification.class, int.class);
                m.invoke(this, Integer.valueOf(NOTI_ID), notification, Integer.valueOf(FOREGROUND_TYPE_MICROPHONE | FOREGROUND_TYPE_DATA_SYNC));
                started = true;
            } catch (Throwable t) {
            }
        }
        if (!started) {
            startForeground(NOTI_ID, notification);
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
