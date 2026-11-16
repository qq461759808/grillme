package com.grillme.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** 常驻通知 + 响铃 + 震动。铃声走系统通知铃声，音量和静音模式由用户自己的设置决定。 */
public final class Notifier {

    public static final String CH = "grill_watch";
    public static final int ID_FOREGROUND = 1001;

    private Notifier() {
    }

    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (nm.getNotificationChannel(CH) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CH, Lang.t("notif_channel"),
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        ch.setDescription(Lang.t("tagline"));
        nm.createNotificationChannel(ch);
    }

    public static Notification build(Context c, String text) {
        ensureChannel(c);
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(c, 0, i, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(c, CH);
        } else {
            b = new Notification.Builder(c);
            b.setPriority(Notification.PRIORITY_LOW);
        }
        b.setContentTitle(Lang.t("app_name"))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false);
        return b.build();
    }

    public static void updateForeground(Context c, String text) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(ID_FOREGROUND, build(c, text));
            }
        } catch (Exception ignored) {
        }
    }

    /** 弹窗时叫一声。铃声和震动各自独立开关。 */
    public static void alert(Context c, Cfg cfg) {
        if (cfg.sound()) {
            try {
                Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                if (uri == null) {
                    uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                }
                Ringtone r = RingtoneManager.getRingtone(c, uri);
                if (r != null) {
                    r.play();
                }
            } catch (Exception ignored) {
            }
        }
        if (cfg.vibrate()) {
            vibrate(c, cfg.vibratePattern());
        }
    }

    public static void vibrate(Context c, String pattern) {
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) {
                return;
            }
            long[] p;
            if ("short".equals(pattern)) {
                p = new long[]{0, 180};
            } else if ("long".equals(pattern)) {
                p = new long[]{0, 700};
            } else if ("strong".equals(pattern)) {
                p = new long[]{0, 90, 90, 90, 90, 90, 90, 350};
            } else {
                p = new long[]{0, 150, 130, 150};
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(p, -1));
            } else {
                v.vibrate(p, -1);
            }
        } catch (Exception ignored) {
        }
    }
}
