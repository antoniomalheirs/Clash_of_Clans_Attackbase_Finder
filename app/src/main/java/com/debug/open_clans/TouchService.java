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
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class TouchService extends AccessibilityService {

    private static final String TAG = "OpenClans.Touch";
    private static final long MIN_TAP_INTERVAL_MS = 500;

    private volatile boolean paused = false;
    private volatile boolean matchPaused = false; // Pausado por MATCH (envia MATCH_RESUMED ao despausar)
    private long lastTapTime = 0;
    private WindowManager windowManager;
    private View floatingButton;

    // ─── BroadcastReceivers ───

    private final BroadcastReceiver tapReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!"PERFORM_TAP".equals(intent.getAction()))
                return;

            float x = intent.getFloatExtra("x", 0f);
            float y = intent.getFloatExtra("y", 0f);

            if (paused) {
                Log.d(TAG, "Toque ignorado — pausado.");
                return;
            }

            long now = System.currentTimeMillis();
            if (now - lastTapTime < MIN_TAP_INTERVAL_MS) {
                Log.d(TAG, "Toque ignorado — intervalo curto.");
                return;
            }
            lastTapTime = now;

            Log.d(TAG, "Toque em: (" + x + ", " + y + ")");
            performTap(x, y);
        }
    };

    private final BroadcastReceiver captureReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "CAPTURE_STARTED → mostrando botão");
            paused = false; // Resetar estado ao reiniciar captura
            showFloatingButton();
        }
    };

    private final BroadcastReceiver captureStopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "CAPTURE_STOPPED → removendo botão");
            removeFloatingButton();
        }
    };

    private final BroadcastReceiver matchReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "MATCH_FOUND → auto-pausando");
            paused = true;
            matchPaused = true;
            if (floatingButton instanceof Button) {
                ((Button) floatingButton).setText("▶ Resume");
            }
        }
    };

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(tapReceiver, new IntentFilter("PERFORM_TAP"));
        lbm.registerReceiver(captureReceiver, new IntentFilter("CAPTURE_STARTED"));
        lbm.registerReceiver(captureStopReceiver, new IntentFilter("CAPTURE_STOPPED"));
        lbm.registerReceiver(matchReceiver, new IntentFilter("MATCH_FOUND"));
        Log.i(TAG, "TouchService conectado e pronto.");
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Não processamos eventos de acessibilidade
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "TouchService interrompido.");
    }

    // ═══════════════════════════════════════════════════════════════
    // Tap Gesture
    // ═══════════════════════════════════════════════════════════════

    private void performTap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 100))
                .build();

        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription g) {
                Log.d(TAG, "Toque OK.");
            }

            @Override
            public void onCancelled(GestureDescription g) {
                Log.w(TAG, "Toque cancelado.");
            }
        }, null);

        if (!dispatched) {
            Log.e(TAG, "dispatchGesture retornou false! Serviço pode não ter permissão.");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Floating Button
    // ═══════════════════════════════════════════════════════════════

    private void showFloatingButton() {
        if (floatingButton != null)
            return;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 50;
        params.y = 200;

        Button button = new Button(this);
        button.setText("⏸ Pause");
        button.setOnClickListener(v -> {
            paused = !paused;
            button.setText(paused ? "▶ Resume" : "⏸ Pause");
            Log.i(TAG, "Paused = " + paused);

            // Se estava pausado por MATCH e o usuário clicou Resume → desbloquear match
            if (!paused && matchPaused) {
                matchPaused = false;
                Log.i(TAG, "Match desbloqueado → enviando MATCH_RESUMED");
                LocalBroadcastManager.getInstance(TouchService.this)
                        .sendBroadcast(new Intent("MATCH_RESUMED"));
            }
        });

        floatingButton = button;

        try {
            windowManager.addView(floatingButton, params);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao adicionar botão flutuante", e);
            floatingButton = null;
        }
    }

    private void removeFloatingButton() {
        if (floatingButton != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(floatingButton);
            } catch (Exception e) {
                Log.w(TAG, "Erro ao remover botão: " + e.getMessage());
            }
            floatingButton = null;
        }
        paused = false;
    }

    // ═══════════════════════════════════════════════════════════════
    // Cleanup
    // ═══════════════════════════════════════════════════════════════

    private void cleanup() {
        try {
            LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
            lbm.unregisterReceiver(tapReceiver);
            lbm.unregisterReceiver(captureReceiver);
            lbm.unregisterReceiver(captureStopReceiver);
            lbm.unregisterReceiver(matchReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Erro no unregister: " + e.getMessage());
        }
        removeFloatingButton();
        Log.i(TAG, "Cleanup completo.");
    }
}
