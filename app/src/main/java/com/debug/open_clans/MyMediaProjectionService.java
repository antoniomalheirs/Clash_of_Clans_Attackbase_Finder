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
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class MyMediaProjectionService extends Service {

    private static final String TAG = "MediaProjectionService";
    private static final int SERVICE_ID = 1667;

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
            String channelId = "com.debug.open_clans.MediaProjection";
            String channelName = "Screen Capture";

            NotificationChannel channel = new NotificationChannel(
                    channelId, channelName, NotificationManager.IMPORTANCE_LOW);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
                notification = new Notification.Builder(this, channelId)
                        .setContentTitle("Open Clans")
                        .setContentText("Captura de tela ativa")
                        .setSmallIcon(android.R.drawable.ic_menu_camera)
                        .build();
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY pode reiniciar o service com intent null
        if (intent == null) {
            Log.w(TAG, "Service reiniciado com intent null. Parando.");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(SERVICE_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        }

        int resultCode = intent.getIntExtra("resultCode", -1);
        Intent data = intent.getParcelableExtra("data");

        // Enviar resultado para a MainActivity via LocalBroadcast
        Intent broadcastIntent = new Intent(ACTION_MEDIA_PROJECTION_STARTED);
        broadcastIntent.putExtra("resultCode", resultCode);
        broadcastIntent.putExtra("data", data);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);

        return START_NOT_STICKY;
    }
}
