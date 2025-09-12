package com.debug.open_clans;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.view.accessibility.AccessibilityEvent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class TouchService extends AccessibilityService {
    public static final String TAG1 = "VisualCapture";

    private boolean paused = false;

    private WindowManager windowManager;
    private View floatingButton;

    private final BroadcastReceiver tapReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("PERFORM_TAP".equals(intent.getAction())) {
                float x = intent.getFloatExtra("x", 0f);
                float y = intent.getFloatExtra("y", 0f);
                if (paused) {
                    Log.d(TAG1, "Ignorando toque porque está pausado.");
                } else {
                    Log.d(TAG1, "Executando toque em: " + x + ", " + y);
                    performTap(x, y);
                }
            }
        }
    };
    private final BroadcastReceiver captureReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG1, "Recebido CAPTURE_STARTED → mostrando botão flutuante");
            showFloatingButton();
        }
    };

    private final BroadcastReceiver captureStopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG1, "Recebido CAPTURE_STOPPED → removendo botão flutuante");
            removeFloatingButton();
        }
    };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();

        // Registrar broadcast para taps
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(tapReceiver, new IntentFilter("PERFORM_TAP"));

        // Registrar broadcast para captura iniciada
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(captureReceiver, new IntentFilter("CAPTURE_STARTED"));

        LocalBroadcastManager.getInstance(this)
                .registerReceiver(captureStopReceiver, new IntentFilter("CAPTURE_STOPPED"));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(tapReceiver);

        if (floatingButton != null && windowManager != null) {
            windowManager.removeView(floatingButton);
        }

        LocalBroadcastManager.getInstance(this).unregisterReceiver(captureReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(captureStopReceiver);


    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(tapReceiver);

        if (floatingButton != null && windowManager != null) {
            windowManager.removeView(floatingButton);
        }

        LocalBroadcastManager.getInstance(this).unregisterReceiver(captureReceiver);
    }

    private void performTap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 100);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);

        dispatchGesture(builder.build(), null, null);
    }

    // --------- BOTÃO FLUTUANTE ----------
    private void showFloatingButton() {
        if (floatingButton != null) {
            // Já existe, não recria
            return;
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 50;
        params.y = 200;

        Button button = new Button(this);
        button.setText("Pause");

        button.setOnClickListener(v -> {
            paused = !paused;
            button.setText(paused ? "Resume" : "Pause");
            Log.i(TAG1, "TouchService paused = " + paused);
        });

        floatingButton = button;
        windowManager.addView(floatingButton, params);
    }

    private void removeFloatingButton() {
        if (floatingButton != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(floatingButton);
            } catch (Exception e) {
                Log.w(TAG1, "Erro ao remover botão: " + e.getMessage());
            }
            floatingButton = null;
        }
    }

}
