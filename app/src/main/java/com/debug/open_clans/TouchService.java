package com.debug.open_clans;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class TouchService extends AccessibilityService {
    public static final String TAG1 = "VisualCapture";
    private final BroadcastReceiver tapReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("PERFORM_TAP".equals(intent.getAction())) {
                float x = intent.getFloatExtra("x", 0f);
                float y = intent.getFloatExtra("y", 0f);
                Log.d(TAG1, "Executando toque em: " + x + ", " + y);
                performTap(x, y);
            }
        }
    };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(tapReceiver, new IntentFilter("PERFORM_TAP"));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(tapReceiver);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    private void performTap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 100);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);

        dispatchGesture(builder.build(), null, null);
    }
}
