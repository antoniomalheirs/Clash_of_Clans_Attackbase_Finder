package com.debug.open_clans;

import static com.debug.open_clans.MainActivity.ACTION_MEDIA_PROJECTION_STARTED;


import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class MyMediaProjectionService extends Service {
    public static int SERVICE_ID = 1667;

    private Notification notification;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String NOTIFICATION_CHANNEL_ID = "com.debug.open_clans.MyMediaProjectionService";
            String channelName = "MyMediaProjectionService";
            NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_NONE);
            chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(chan);
                notification = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID).build();
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // ---------------- STEP 3.1 ---------------------
            startForeground(SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        }

        int resultCode = intent.getIntExtra("resultCode", -1);
        Intent data = intent.getParcelableExtra("data");

        Intent broadcastIntent = new Intent(this, MainActivity.MyBroadcastReceiver.class);
        broadcastIntent.setAction(ACTION_MEDIA_PROJECTION_STARTED);
        broadcastIntent.putExtra("resultCode", resultCode);
        broadcastIntent.putExtra("data", data);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);

        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("CAPTURE_STARTED"));

        return START_STICKY;
    }
}

