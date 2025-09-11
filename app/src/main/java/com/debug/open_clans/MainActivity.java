package com.debug.open_clans;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

public class MainActivity extends AppCompatActivity {
    public static final String ACTION_MEDIA_PROJECTION_STARTED = "com.debug.open_clans.ACTION_MEDIA_PROJECTION_STARTED";
    public static final String TAG = "MediaProjectionSample";
    public static final String TAG1 = "VisualCapture";
    private boolean isReceiverRegistered = false;
    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;

    private Button mButtonToggle;
    private SurfaceView mSurfaceView;
    private Surface mSurface;
    private Handler mHandler;
    private ImageReader mImageReader;
    private ActivityResultLauncher<Intent> startMediaProjectionActivity;
    // OCR
    private final com.google.mlkit.vision.text.TextRecognizer recognizer =  TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private long lastAnalysisTime = 0;
    private static final long ANALYSIS_INTERVAL_MS = 4000; // 1 frame a cada 2s

    public class MyBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_MEDIA_PROJECTION_STARTED.equals(intent.getAction())) {
                int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
                Intent data = intent.getParcelableExtra("data");

                MediaProjectionManager projectionManager =
                        (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                mMediaProjection = projectionManager.getMediaProjection(resultCode, data);

                if (mMediaProjection != null) {
                    startScreenCapture();
                }
            }
        }
    }
    private final MyBroadcastReceiver receiver = new MyBroadcastReceiver();
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mSurfaceView = findViewById(R.id.surface);
        mHandler = new Handler(Looper.getMainLooper());
        mButtonToggle = findViewById(R.id.button);
        mButtonToggle.setOnClickListener(view -> {
            if (mVirtualDisplay == null) {
                requestScreenCapturePermission();
            } else {
                stopScreenCapture();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();


        boolean enabled = isAccessibilityServiceEnabled(this, TouchService.class);
        if (!enabled) {
            requestAccessibilityPermission();
        }

        registerAccessibilityObserver();


        mediaProjectionManager = (MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);

        startMediaProjectionActivity =
                registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                        result -> {
                            int resultCode = result.getResultCode();
                            if (result.getResultCode() == Activity.RESULT_OK) {
                                Intent data = result.getData();

                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                                    MediaProjectionManager projectionManager =
                                            (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                                    mMediaProjection = projectionManager.getMediaProjection(resultCode, data);

                                    if (mMediaProjection != null) {
                                        startScreenCapture();
                                    }
                                } else {
                                    try {
                                        Intent serviceIntent = new Intent(this, MyMediaProjectionService.class);
                                        serviceIntent.putExtra("resultCode", resultCode);
                                        serviceIntent.putExtra("data", data);
                                        ContextCompat.startForegroundService(this, serviceIntent);
                                    } catch (RuntimeException e) {
                                        Log.w(TAG, "Error while trying to get MediaProjection: " + e.getMessage());
                                    }
                                }
                            } else {
                                Toast.makeText(this, "Screen sharing permission denied", Toast.LENGTH_SHORT).show();
                            }
                        });

        if (!isReceiverRegistered) {
            IntentFilter filter = new IntentFilter(ACTION_MEDIA_PROJECTION_STARTED);
            filter.addCategory(Intent.CATEGORY_DEFAULT);
            LocalBroadcastManager.getInstance(this).registerReceiver(receiver, filter);
            isReceiverRegistered = true;
        }


    }
    private ContentObserver accessibilityObserver;
    private void registerAccessibilityObserver() {
        if (accessibilityObserver != null) return;

        accessibilityObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                boolean enabled = isAccessibilityServiceEnabled(MainActivity.this, TouchService.class);
                if (enabled) {
                    Toast.makeText(MainActivity.this, "Serviço de acessibilidade ativado", Toast.LENGTH_SHORT).show();
                    // opcional: continue fluxo que dependia da permissão
                }
            }
        };

        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                accessibilityObserver
        );
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (isReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
            isReceiverRegistered = false;
        }

        if (mMediaProjection != null) {
            mMediaProjection = null;
        }

        if (accessibilityObserver != null) {
            getContentResolver().unregisterContentObserver(accessibilityObserver);
            accessibilityObserver = null;
        }
    }
    private void requestScreenCapturePermission() {
        if (startMediaProjectionActivity != null) {
            Intent captureIntent = mediaProjectionManager.createScreenCaptureIntent();
            startMediaProjectionActivity.launch(captureIntent);
        }
    }

    private void requestAccessibilityPermission() {
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        boolean alreadyAsked = prefs.getBoolean("asked_accessibility", false);

        if (!alreadyAsked) {
            new AlertDialog.Builder(this)
                    .setTitle("Ativar acessibilidade")
                    .setMessage("Ative o serviço de acessibilidade para permitir que o app funcione corretamente.")
                    .setPositiveButton("Ir para Configurações", (dialog, which) -> {
                        prefs.edit().putBoolean("asked_accessibility", true).apply();
                        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                        startActivity(intent);
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        }
    }

    public static boolean isAccessibilityServiceEnabled(Context context, Class<?> accessibilityServiceClass) {
        String prefString = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (prefString == null) return false;
        final ComponentName expectedComponent = new ComponentName(context, accessibilityServiceClass);
        final String flat = expectedComponent.flattenToString();
        for (String enabled : prefString.split(":")) {
            if (enabled.equalsIgnoreCase(flat)) return true;
        }
        return false;
    }
    @SuppressLint("SetTextI18n")
    public void startScreenCapture() {
        if (mSurfaceView.getHolder().getSurface() == null || !mSurfaceView.getHolder().getSurface().isValid()) {
            mSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    mSurface = holder.getSurface();
                    startScreenCapture();
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    startScreenCapture();
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {}
            });
            return;
        }

        mSurface = mSurfaceView.getHolder().getSurface();
        mMediaProjection.registerCallback(new MediaProjection.Callback() {}, null);

        int width = mSurfaceView.getWidth();
        int height = mSurfaceView.getHeight();
        int dpi = getResources().getDisplayMetrics().densityDpi;

        mImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);

        mImageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image == null) return;

            long now = System.currentTimeMillis();
            if (now - lastAnalysisTime >= ANALYSIS_INTERVAL_MS) {
                lastAnalysisTime = now;
                analyzeImage(image);
            } else {
                image.close();
            }
        }, mHandler);

        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                "ScreenCapture",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY |
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                mImageReader.getSurface(),
                null,
                mHandler
        );

        mButtonToggle.setText("Stop");
    }
    private void analyzeImage(Image image) {
        try {
            InputImage inputImage;
            Bitmap bitmap = imageToBitmapCrop(image, 300, 155);
            inputImage = InputImage.fromBitmap(bitmap, 0);

            recognizer.process(inputImage)
                    .addOnSuccessListener(text -> {
                        int countMillion = 0;
                        int countTenThousand = 0;

                        for (com.google.mlkit.vision.text.Text.TextBlock block : text.getTextBlocks()) {
                            long valor = parseOCRValue(block.getText()); // função para sanitizar e converter

                            if (valor > 1000000) {
                                countMillion++;
                            } else if (valor > 5000) {
                                countTenThousand++;
                            }
                            Log.d(TAG1, String.valueOf(valor));
                        }

// Inversão da lógica: só clicamos se as condições **não** forem atendidas
                        if (!(countMillion >= 2 && countTenThousand >= 1)) {
                            Log.d(TAG1, "Condições não atendidas. Executando toque.");
                            Intent intent = new Intent("PERFORM_TAP");
                            intent.putExtra("x", 2400f); // ajuste para sua tela
                            intent.putExtra("y", 950f);
                            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
                        } else {
                            Log.d(TAG1, "Condições atendidas. Nenhum toque será executado.");
                        }

                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Erro OCR: " + e.getMessage());
                    })
                    .addOnCompleteListener(task -> {
                        image.close();
                    });
        } catch (Exception e) {
            Log.e(TAG, "Falha analisando imagem: " + e.getMessage());
            if (image != null) image.close();
        }
    }
    private long parseOCRValue(String text) {
        // Remove espaços e caracteres desnecessários
        text = text.trim();

        // Substituições comuns de OCR:
        // 'O' -> '0', 'l' -> '1', ',' e '.' -> nada
        text = text.replaceAll("[,\\.]", "")
                .replace('O', '0')
                .replace('o', '0')
                .replace('l', '1')
                .replace('L', '1')
                .replace('I', '1')
                .replace('i', '1')
                .replace('s', '5')
                .replace('S', '5')
                .replace('i', '1');

        // Remove tudo que não seja número
        text = text.replaceAll("[^0-9]", "");

        if (text.isEmpty()) return -1;

        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private Bitmap imageToBitmapCrop(Image image, int cropWidth, int cropHeight) {
        if (image.getFormat() != PixelFormat.RGBA_8888) {
            throw new IllegalArgumentException("Formato inesperado: " + image.getFormat());
        }

        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();

        Bitmap fullBitmap = Bitmap.createBitmap(
                image.getWidth() + rowPadding / pixelStride,
                image.getHeight(),
                Bitmap.Config.ARGB_8888
        );
        fullBitmap.copyPixelsFromBuffer(buffer);

        // Faz o crop da região superior esquerda
        int width = Math.min(cropWidth, fullBitmap.getWidth());
        int height = Math.min(cropHeight, fullBitmap.getHeight());

        return Bitmap.createBitmap(fullBitmap, 0, 125, width, height);
    }

    @SuppressLint("SetTextI18n")
    private void stopScreenCapture() {
        if (mVirtualDisplay == null) return;
        mVirtualDisplay.release();
        mVirtualDisplay = null;
        mButtonToggle.setText("Start");
    }
}
