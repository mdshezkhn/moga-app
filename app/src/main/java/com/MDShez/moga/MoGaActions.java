package com.mdshez.moga;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.telephony.SmsManager;
import android.webkit.JavascriptInterface;

public class MoGaActions {
    private final Activity act;

    public MoGaActions(Activity a) {
        act = a;
        NotificationManager nm = (NotificationManager) act.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel("moga", "MoGa", NotificationManager.IMPORTANCE_HIGH);
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);
    }

    @JavascriptInterface
    public String setAlarm(int hour, int minute, String label) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour);
        cal.set(java.util.Calendar.MINUTE, minute);
        cal.set(java.util.Calendar.SECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
        return schedule(cal.getTimeInMillis(), "ALARM: " + (label == null || label.isEmpty() ? "wake up" : label));
    }

    @JavascriptInterface
    public String setTimer(int seconds, String label) {
        return schedule(System.currentTimeMillis() + seconds * 1000L,
                "TIMER done: " + (label == null || label.isEmpty() ? String.valueOf(seconds) + "s" : label));
    }

    private String schedule(long at, String text) {
        try {
            AlarmManager am = (AlarmManager) act.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(act, AlarmRcv.class);
            i.putExtra("text", text);
            PendingIntent pi = PendingIntent.getBroadcast(act, (int) (at % 100000), i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } catch (Exception se) {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
                return "scheduled (approximate) for " + new java.util.Date(at);
            }
            return "scheduled for " + new java.util.Date(at);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @JavascriptInterface
    public String notify(String title, String text) {
        NotificationManager nm = (NotificationManager) act.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification n = new Notification.Builder(act, "moga")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(text).build();
        nm.notify((int) System.currentTimeMillis(), n);
        return "notification shown";
    }

    @JavascriptInterface
    public String sendSms(String number, String text) {
        try {
            SmsManager sm = Build.VERSION.SDK_INT >= 31
                    ? act.getSystemService(SmsManager.class) : SmsManager.getDefault();
            sm.sendTextMessage(number, null, text, null, null);
            return "SMS sent to " + number;
        } catch (Exception e) {
            return "Error sending SMS (check permission/number): " + e.getMessage();
        }
    }

    @JavascriptInterface
    public String openUrl(String url) {
        try {
            if (!url.startsWith("http")) url = "https://" + url;
            act.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
            return "opened " + url;
        } catch (Exception e) {
            return "Error: nothing can open that";
        }
    }

    @JavascriptInterface
    public String deviceInfo() {
        Intent bt = act.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int lvl = bt == null ? -1 : bt.getIntExtra("level", -1);
        int sc = bt == null ? 100 : bt.getIntExtra("scale", 100);
        return "model: " + Build.MODEL + ", android: " + Build.VERSION.RELEASE
                + ", battery: " + (lvl < 0 ? "unknown" : (lvl * 100 / sc) + "%")
                + ", time: " + new java.util.Date();
    }

    public static class AlarmRcv extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            String text = i.getStringExtra("text");
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("moga", "MoGa", NotificationManager.IMPORTANCE_HIGH));
            Notification n = new Notification.Builder(c, "moga")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("MoGa").setContentText(text)
                    .setCategory(Notification.CATEGORY_ALARM).setAutoCancel(true).build();
            nm.notify((int) System.currentTimeMillis(), n);
        }
    }
}
