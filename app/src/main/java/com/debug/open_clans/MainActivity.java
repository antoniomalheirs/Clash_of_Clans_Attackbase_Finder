package com.debug.open_clans;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
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
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    // ─── Actions ───
    public static final String ACTION_MEDIA_PROJECTION_STARTED = "com.debug.open_clans.ACTION_MEDIA_PROJECTION_STARTED";

    // ─── Tags ───
    private static final String TAG = "OpenClans";
    private static final String TAG_OCR = "OpenClans.OCR";

    // ─── Configuração ───
    private static final long ANALYSIS_INTERVAL_MS = 4000;
    private static final String PREFS_NAME = "open_clans_prefs";

    // Região de crop proporcional (calibrada para tela landscape).
    // Captura a área "Saque disponível:" no canto superior esquerdo.
    // Calculado para dispositivo 2712x1220 a partir de screenshot real.
    private static final float CROP_X_RATIO = 0.005f; // ~14px — evitar borda esquerda
    private static final float CROP_Y_RATIO = 0.12f; // ~146px — início do "Saque disponível:"
    private static final float CROP_W_RATIO = 0.14f; // ~380px — cobre os números sem pegar muito
    private static final float CROP_H_RATIO = 0.18f; // ~220px — cobre Gold + Elixir + Dark

    // Coordenadas do toque "Next" (pixels absolutos do dispositivo)
    private static final float TAP_X = 2400f;
    private static final float TAP_Y = 950f;

    // Defaults (alteráveis pela UI)
    private static final long DEFAULT_THRESHOLD_MILLION = 1_000_000;
    private static final long DEFAULT_THRESHOLD_TEN_THOUSAND = 5_000;
    private static final int DEFAULT_MIN_MILLION_COUNT = 2;
    private static final int DEFAULT_MIN_TEN_THOUSAND_COUNT = 1;

    // Pré-processamento OCR
    private static final int OCR_SCALE_FACTOR = 2; // Escalar imagem 2x para OCR (3x usa muita memória)

    // Confirmação de leitura
    private static final int EXPECTED_VALUE_COUNT = 3; // Gold + Elixir + Dark Elixir
    private static final int REQUIRED_SKIP_CONFIRMS = 2; // Reads consecutivos para confirmar Skip

    // Regex para encontrar números: grupos de dígitos possivelmente separados por ,
    // . ' :
    // Apostrophe e colon são usados pelo OCR quando confunde separadores de milhar
    private static final Pattern NUMBER_PATTERN = Pattern.compile("[0-9][0-9.,':]*[0-9]|[0-9]+");
    private static final Pattern SPACE_BETWEEN_DIGITS = Pattern.compile("(\\d)\\s+(\\d)");

    // Caracteres que o OCR confunde com dígitos
    // Mapeamento: letra → dígito que ela parece (mesma posição = mesma
    // substituição)
    // O→0, o→0, I→1, i→1, l→1, L→1, B→8, b→8, S→5, s→5, Z→2, z→2, D→0, |→1, !→1
    private static final String OCR_CONFUSED_FROM = "OoIilLBbSsZzD|!";
    private static final String OCR_CONFUSED_TO = "001111885522011";

    // ─── Runtime state ───
    private long thresholdMillion = DEFAULT_THRESHOLD_MILLION;
    private long thresholdTenThousand = DEFAULT_THRESHOLD_TEN_THOUSAND;
    private int minMillionCount = DEFAULT_MIN_MILLION_COUNT;
    private int minTenThousandCount = DEFAULT_MIN_TEN_THOUSAND_COUNT;

    private boolean isReceiverRegistered = false;
    private boolean isCapturing = false;
    private long lastAnalysisTime = 0;
    private int captureWidth = 0;
    private int captureHeight = 0;
    private int debugSaveCount = 0;
    private int consecutiveEmptyReads = 0; // Esperar animação terminar antes de clicar Next
    private int consecutiveSkips = 0; // Reads consecutivos com resultado SKIP
    private boolean matchLocked = false; // Uma vez MATCH → nunca mais toca Next nessa base

    // ─── Android components ───
    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private ImageReader mImageReader;
    private Handler mHandler;
    private ActivityResultLauncher<Intent> startMediaProjectionActivity;

    // ─── OCR ───
    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    // ─── UI ───
    private Button mButtonToggle;
    private TextView mStatusText;
    private EditText inputThresholdMillion;
    private EditText inputThresholdTenK;
    private EditText inputMinMillionCount;
    private EditText inputMinTenKCount;

    // ─── BroadcastReceiver ───
    public class MyBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_MEDIA_PROJECTION_STARTED.equals(intent.getAction())) {
                int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
                Intent data = intent.getParcelableExtra("data");

                if (data == null) {
                    Log.e(TAG, "BroadcastReceiver: data é null");
                    return;
                }

                MediaProjectionManager projMgr = (MediaProjectionManager) getSystemService(
                        Context.MEDIA_PROJECTION_SERVICE);
                mMediaProjection = projMgr.getMediaProjection(resultCode, data);

                if (mMediaProjection != null) {
                    startScreenCapture();
                } else {
                    Log.e(TAG, "Falha ao obter MediaProjection do service.");
                }
            }
        }
    }

    private final MyBroadcastReceiver receiver = new MyBroadcastReceiver();

    /** Receiver para quando o usuário clica \"Resume\" após um MATCH */
    private final BroadcastReceiver matchResumedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "MATCH_RESUMED → desbloqueando match, voltando a buscar");
            matchLocked = false;
            consecutiveSkips = 0;
            consecutiveEmptyReads = 0;
            updateStatus("Buscando novamente...");
        }
    };

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mHandler = new Handler(Looper.getMainLooper());
        mButtonToggle = findViewById(R.id.button);
        mStatusText = findViewById(R.id.statusText);
        inputThresholdMillion = findViewById(R.id.inputThresholdMillion);
        inputThresholdTenK = findViewById(R.id.inputThresholdTenK);
        inputMinMillionCount = findViewById(R.id.inputMinMillionCount);
        inputMinTenKCount = findViewById(R.id.inputMinTenKCount);

        loadSettings();

        // Registrar receiver que precisa viver durante toda a Activity
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(matchResumedReceiver, new IntentFilter("MATCH_RESUMED"));

        // Verificar acessibilidade
        if (!isAccessibilityServiceEnabled(this, TouchService.class)) {
            Toast.makeText(this,
                    "Ative o Touch Service nas configurações de acessibilidade",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } else {
            Toast.makeText(this, "Touch Service já está ativo!", Toast.LENGTH_SHORT).show();
        }

        mButtonToggle.setOnClickListener(view -> {
            if (!isCapturing) {
                readAndSaveSettings();
                setInputsEnabled(false);
                requestScreenCapturePermission();
            } else {
                stopScreenCapture();
                setInputsEnabled(true);
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();

        mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        startMediaProjectionActivity = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    int resultCode = result.getResultCode();
                    if (resultCode == Activity.RESULT_OK) {
                        Intent data = result.getData();
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            mMediaProjection = mediaProjectionManager
                                    .getMediaProjection(resultCode, data);
                            if (mMediaProjection != null) {
                                startScreenCapture();
                            }
                        } else {
                            try {
                                Intent serviceIntent = new Intent(this,
                                        MyMediaProjectionService.class);
                                serviceIntent.putExtra("resultCode", resultCode);
                                serviceIntent.putExtra("data", data);
                                ContextCompat.startForegroundService(this, serviceIntent);
                            } catch (RuntimeException e) {
                                Log.e(TAG, "Erro ao iniciar MediaProjection service", e);
                                updateStatus("Erro ao iniciar captura.");
                                setInputsEnabled(true);
                            }
                        }
                    } else {
                        Toast.makeText(this,
                                "Permissão de captura de tela negada",
                                Toast.LENGTH_SHORT).show();
                        setInputsEnabled(true);
                    }
                });

        // Registrar receiver
        if (!isReceiverRegistered) {
            LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
            lbm.registerReceiver(receiver, new IntentFilter(ACTION_MEDIA_PROJECTION_STARTED));
            isReceiverRegistered = true;
        }

        // Solicitar overlay
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
            isReceiverRegistered = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScreenCapture();
        recognizer.close();
        // Desregistrar o matchResumedReceiver (registrado no onCreate)
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(matchResumedReceiver);
        } catch (Exception e) {
            /* ignore */ }
    }

    // ═══════════════════════════════════════════════════════════════
    // Screen Capture
    // ═══════════════════════════════════════════════════════════════

    private void requestScreenCapturePermission() {
        if (startMediaProjectionActivity != null) {
            startMediaProjectionActivity.launch(
                    mediaProjectionManager.createScreenCaptureIntent());
        }
    }

    public void startScreenCapture() {
        if (mMediaProjection == null) {
            Log.w(TAG, "MediaProjection é null.");
            updateStatus("Erro: MediaProjection indisponível.");
            setInputsEnabled(true);
            return;
        }

        // Forçar LANDSCAPE (Clash of Clans é landscape)
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        int w = metrics.widthPixels;
        int h = metrics.heightPixels;
        int dpi = metrics.densityDpi;
        int width = Math.max(w, h);
        int height = Math.min(w, h);

        captureWidth = width;
        captureHeight = height;
        debugSaveCount = 0;
        consecutiveEmptyReads = 0;
        consecutiveSkips = 0;
        matchLocked = false;
        isCapturing = true;

        Log.d(TAG, "Captura LANDSCAPE: " + width + "x" + height
                + " @ " + dpi + "dpi (raw: " + w + "x" + h + ")");

        mMediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.d(TAG, "MediaProjection parou via callback.");
                mHandler.post(() -> stopScreenCapture());
            }
        }, mHandler);

        mImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4);
        mImageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null)
                    return;

                long now = System.currentTimeMillis();
                if (now - lastAnalysisTime >= ANALYSIS_INTERVAL_MS) {
                    lastAnalysisTime = now;
                    analyzeImage(image);
                    image = null; // analyzeImage agora é responsável por fechar
                }
            } finally {
                if (image != null)
                    image.close();
            }
        }, mHandler);

        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                "ScreenCapture", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mImageReader.getSurface(), null, mHandler);

        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent("CAPTURE_STARTED"));

        mButtonToggle.setText("⏹  Parar");
        mButtonToggle.setBackgroundResource(R.drawable.bg_button_stop);
        updateStatus("Captura ativa — analisando...");
    }

    private void stopScreenCapture() {
        isCapturing = false;

        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }

        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }

        if (mMediaProjection != null) {
            try {
                mMediaProjection.stop();
            } catch (Exception e) {
                /* ignore */ }
            mMediaProjection = null;
        }

        mButtonToggle.setText("▶  Iniciar");
        mButtonToggle.setBackgroundResource(R.drawable.bg_button_start);
        updateStatus("Captura parada.");

        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent("CAPTURE_STOPPED"));
    }

    // ═══════════════════════════════════════════════════════════════
    // OCR Analysis
    // ═══════════════════════════════════════════════════════════════

    private void analyzeImage(Image image) {
        Bitmap croppedBitmap = null;
        Bitmap processedBitmap = null;
        try {
            // Calcular crop proporcional
            int cropX = (int) (CROP_X_RATIO * captureWidth);
            int cropY = (int) (CROP_Y_RATIO * captureHeight);
            int cropW = (int) (CROP_W_RATIO * captureWidth);
            int cropH = (int) (CROP_H_RATIO * captureHeight);

            croppedBitmap = imageToBitmapCrop(image, cropX, cropY, cropW, cropH);
            if (croppedBitmap == null) {
                Log.w(TAG_OCR, "Crop retornou null. Image: " + image.getWidth()
                        + "x" + image.getHeight() + " Crop: " + cropX + "," + cropY
                        + " " + cropW + "x" + cropH);
                image.close();
                return;
            }

            // Pré-processar: escalar + grayscale + threshold binário
            processedBitmap = preprocessForOcr(croppedBitmap);

            // Debug: salvar as primeiras 5 capturas (crop + preprocessed + full)
            if (debugSaveCount < 5) {
                saveDebugBitmap(croppedBitmap, "crop_debug_" + debugSaveCount + ".png");
                saveDebugBitmap(processedBitmap, "crop_preprocessed_" + debugSaveCount + ".png");
                if (debugSaveCount == 0) {
                    Bitmap fullDebug = imageToBitmapFull(image);
                    if (fullDebug != null) {
                        saveDebugBitmap(fullDebug, "full_screen_debug.png");
                        fullDebug.recycle();
                    }
                }
                debugSaveCount++;
            }

            // Usar a imagem preprocessada para OCR
            InputImage inputImage = InputImage.fromBitmap(processedBitmap, 0);
            final Bitmap cropRef = croppedBitmap;
            final Bitmap procRef = processedBitmap;
            croppedBitmap = null;
            processedBitmap = null;

            recognizer.process(inputImage)
                    .addOnSuccessListener(text -> processOcrResult(text))
                    .addOnFailureListener(e -> Log.e(TAG_OCR, "Erro OCR", e))
                    .addOnCompleteListener(task -> {
                        image.close();
                        cropRef.recycle();
                        procRef.recycle();
                    });

        } catch (Exception e) {
            Log.e(TAG_OCR, "Falha analisando imagem", e);
            image.close();
            if (croppedBitmap != null)
                croppedBitmap.recycle();
            if (processedBitmap != null)
                processedBitmap.recycle();
        }
    }

    /**
     * Processa resultado do OCR com sistema de confirmação:
     * - MATCH encontrado → trava (nunca mais toca Next nessa base)
     * - SKIP só após 2 reads consecutivos confirmando (evita pular base boa)
     * - Reads com !=3 valores = incerto, não conta para confirmação de skip
     * - Reads vazios = animação, espera 3 antes de pular
     */
    private void processOcrResult(Text text) {
        // Se já travou como MATCH, não faz nada
        if (matchLocked) {
            return;
        }

        List<Long> allValues = new ArrayList<>();
        int countMillion = 0;
        int countTenThousand = 0;

        Log.d(TAG_OCR, "═══════ OCR RESULT ═══════");

        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String lineText = line.getText();
                Log.d(TAG_OCR, "RAW Line: \"" + lineText + "\"");

                for (Text.Element elem : line.getElements()) {
                    Log.v(TAG_OCR, "  Element: \"" + elem.getText()
                            + "\" conf=" + elem.getConfidence());
                }

                List<Long> numbers = extractNumbers(lineText);
                for (long val : numbers) {
                    allValues.add(val);
                    Log.d(TAG_OCR, "  → Número extraído: " + val);

                    if (val > thresholdMillion) {
                        countMillion++;
                    } else if (val > thresholdTenThousand) {
                        countTenThousand++;
                    }
                }
            }
        }

        // ─── Sem valores → animação ───
        if (allValues.isEmpty()) {
            consecutiveEmptyReads++;
            consecutiveSkips = 0; // Reset skip streak
            Log.d(TAG_OCR, "Nenhum valor (empty #" + consecutiveEmptyReads + "/3)");

            if (consecutiveEmptyReads >= 3) {
                Log.d(TAG_OCR, "3 reads vazios → pulando base");
                tapNextAndReset();
                updateStatus("Skip (sem valores)");
            } else {
                updateStatus("Aguardando... (" + consecutiveEmptyReads + "/3)");
            }
            return;
        }

        consecutiveEmptyReads = 0;

        boolean conditionsMatch = countMillion >= minMillionCount &&
                countTenThousand >= minTenThousandCount;
        boolean validCount = (allValues.size() >= EXPECTED_VALUE_COUNT);

        Log.d(TAG_OCR, "Totais: M=" + countMillion + " T=" + countTenThousand
                + " Valores(" + allValues.size() + ")=" + allValues
                + (validCount ? " [3✓]" : " [" + allValues.size() + "≠3]")
                + " → " + (conditionsMatch ? "MATCH" : "SKIP")
                + " (confirms: " + consecutiveSkips + "/" + REQUIRED_SKIP_CONFIRMS + ")");

        // ─── MATCH encontrado → travar e pausar TouchService ───
        if (conditionsMatch) {
            matchLocked = true;
            consecutiveSkips = 0;
            updateStatus("★ BASE BOA! ★ M:" + countMillion
                    + " T:" + countTenThousand + " " + allValues);
            Log.d(TAG_OCR, ">>> MATCH TRAVADO — enviando MATCH_FOUND <<<");
            // Pausar TouchService automaticamente
            LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(new Intent("MATCH_FOUND"));
            return;
        }

        // ─── SKIP: só contar se leitura tem exatamente 3 valores (confiável) ───
        if (validCount) {
            consecutiveSkips++;
        } else {
            // Leitura incerta (!=3 valores) → não reseta, mas não incrementa
            Log.d(TAG_OCR, "Leitura incerta (" + allValues.size()
                    + " valores, esperado " + EXPECTED_VALUE_COUNT + ")");
        }

        if (consecutiveSkips >= REQUIRED_SKIP_CONFIRMS) {
            Log.d(TAG_OCR, consecutiveSkips + " skips confirmados → Next");
            tapNextAndReset();
            updateStatus("Skip confirmado (M:" + countMillion + " T:" + countTenThousand + ")");
        } else {
            updateStatus("Verificando... skip " + consecutiveSkips
                    + "/" + REQUIRED_SKIP_CONFIRMS
                    + " (M:" + countMillion + " T:" + countTenThousand + ")");
        }
    }

    /** Toca Next e reseta todos os contadores para a próxima base. */
    private void tapNextAndReset() {
        Intent intent = new Intent("PERFORM_TAP");
        intent.putExtra("x", TAP_X);
        intent.putExtra("y", TAP_Y);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        consecutiveEmptyReads = 0;
        consecutiveSkips = 0;
        // matchLocked NÃO reseta aqui — só reseta quando nova captura começa
    }

    /**
     * Extrai números de uma string de texto.
     * Lida com separadores de milhar: espaço ("630 336"), vírgula, ponto.
     * O Clash of Clans usa espaço como separador de milhar no locale PT-BR.
     * Também corrige caracteres que o OCR confunde com dígitos.
     */
    private List<Long> extractNumbers(String text) {
        List<Long> numbers = new ArrayList<>();
        if (text == null || text.isEmpty())
            return numbers;

        // Passo 1: Limpar caracteres que o OCR confundiu
        String cleaned = cleanOcrText(text);
        if (!cleaned.equals(text)) {
            Log.d(TAG_OCR, "  CLEANED: \"" + text + "\" → \"" + cleaned + "\"");
        }

        // Passo 1.5: Remover pontuação solta antes de espaços ("549. 743" → "549 743")
        // No Clash of Clans todos os valores são inteiros, não existem decimais.
        cleaned = cleaned.replaceAll("(\\d)[.,]\\s+(\\d)", "$1 $2");

        // Passo 2: Juntar dígitos separados por espaços ("630 336" → "630336")
        String joined = cleaned;
        String prev;
        do {
            prev = joined;
            joined = SPACE_BETWEEN_DIGITS.matcher(joined).replaceAll("$1$2");
        } while (!joined.equals(prev));

        // Passo 3: Extrair números via regex
        Matcher matcher = NUMBER_PATTERN.matcher(joined);
        while (matcher.find()) {
            String numStr = matcher.group();
            numStr = numStr.replaceAll("[,.':\\s]", "");

            if (!numStr.isEmpty()) {
                try {
                    long val = Long.parseLong(numStr);
                    if (val > 0) {
                        numbers.add(val);
                    }
                } catch (NumberFormatException e) {
                    // Ignorar
                }
            }
        }

        return numbers;
    }

    /**
     * Corrige caracteres que o OCR tipicamente confunde com dígitos.
     * Substitui letras por dígitos APENAS quando estão adjacentes a dígitos reais,
     * para não corromper palavras normais como "Gold" ou "Elixir".
     */
    private String cleanOcrText(String text) {
        if (text == null || text.isEmpty())
            return text;

        char[] chars = text.toCharArray();
        boolean changed = false;

        for (int i = 0; i < chars.length; i++) {
            int idx = OCR_CONFUSED_FROM.indexOf(chars[i]);
            if (idx < 0)
                continue;

            // Só substituir se adjacente a um dígito real
            boolean hasDigitBefore = (i > 0 && Character.isDigit(chars[i - 1]));
            boolean hasDigitAfter = (i < chars.length - 1 && Character.isDigit(chars[i + 1]));

            if (hasDigitBefore || hasDigitAfter) {
                chars[i] = OCR_CONFUSED_TO.charAt(idx);
                changed = true;
            }
        }

        return changed ? new String(chars) : text;
    }

    /**
     * Pré-processa bitmap para OCR: escala 2x + inverte cores + boost de contraste.
     * ML Kit funciona melhor com texto ESCURO em fundo CLARO.
     * Clash of Clans tem texto BRANCO em fundo ESCURO — inverter resolve isso.
     * Não usar threshold binário — ML Kit é treinado com imagens naturais.
     */
    private Bitmap preprocessForOcr(Bitmap src) {
        int scaledW = src.getWidth() * OCR_SCALE_FACTOR;
        int scaledH = src.getHeight() * OCR_SCALE_FACTOR;

        // Passo 1: Escalar para tamanho maior (texto ~40-60px é ideal para ML Kit)
        Bitmap scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true);

        // Passo 2: Inverter cores + boost de contraste via ColorMatrix
        // Matriz que inverte (255-pixel) e adiciona contraste:
        // Escala negativa (-1.2) inverte e amplifica contraste
        // Offset (300) garante que o fundo fique claro após inversão
        Bitmap result = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        float contrast = -1.2f; // Negativo = inverte; >1 = mais contraste
        float brightness = 300f; // Offset para manter fundo claro
        ColorMatrix cm = new ColorMatrix(new float[] {
                contrast, 0, 0, 0, brightness,
                0, contrast, 0, 0, brightness,
                0, 0, contrast, 0, brightness,
                0, 0, 0, 1, 0
        });
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(scaled, 0, 0, paint);
        scaled.recycle();

        return result;
    }

    // ═══════════════════════════════════════════════════════════════
    // Image Processing
    // ═══════════════════════════════════════════════════════════════

    /**
     * Converte Image RGBA_8888 para Bitmap, cropando a região especificada.
     */
    private Bitmap imageToBitmapCrop(Image image, int cropX, int cropY,
            int cropWidth, int cropHeight) {
        if (image.getFormat() != PixelFormat.RGBA_8888) {
            Log.e(TAG, "Formato inesperado: " + image.getFormat());
            return null;
        }

        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();

        int bitmapWidth = image.getWidth() + rowPadding / pixelStride;
        int bitmapHeight = image.getHeight();

        Bitmap fullBitmap = Bitmap.createBitmap(
                bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
        fullBitmap.copyPixelsFromBuffer(buffer);

        // Validar limites
        int safeX = Math.max(0, Math.min(cropX, fullBitmap.getWidth() - 1));
        int safeY = Math.max(0, Math.min(cropY, fullBitmap.getHeight() - 1));
        int safeW = Math.min(cropWidth, fullBitmap.getWidth() - safeX);
        int safeH = Math.min(cropHeight, fullBitmap.getHeight() - safeY);

        if (safeW <= 0 || safeH <= 0) {
            Log.w(TAG, "Crop inválido: (" + safeX + "," + safeY + ") "
                    + safeW + "x" + safeH + " (bitmap: "
                    + fullBitmap.getWidth() + "x" + fullBitmap.getHeight() + ")");
            fullBitmap.recycle();
            return null;
        }

        Bitmap cropped = Bitmap.createBitmap(fullBitmap, safeX, safeY, safeW, safeH);
        // Só reciclar se createBitmap criou um novo bitmap (não é o mesmo ref)
        if (cropped != fullBitmap) {
            fullBitmap.recycle();
        }
        return cropped;
    }

    /**
     * Converte Image RGBA_8888 para Bitmap completo (sem crop). Usado para debug.
     */
    private Bitmap imageToBitmapFull(Image image) {
        try {
            if (image.getFormat() != PixelFormat.RGBA_8888)
                return null;

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();

            int bitmapWidth = image.getWidth() + rowPadding / pixelStride;
            Bitmap bitmap = Bitmap.createBitmap(
                    bitmapWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
            buffer.rewind();
            bitmap.copyPixelsFromBuffer(buffer);
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Erro criando bitmap full", e);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private boolean isAccessibilityServiceEnabled(Context context, Class<?> service) {
        int accessibilityEnabled = 0;
        final String serviceId = context.getPackageName() + "/" + service.getCanonicalName();

        try {
            accessibilityEnabled = Settings.Secure.getInt(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            Log.w(TAG, "Accessibility setting not found.");
        }

        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

            if (settingValue != null) {
                TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
                splitter.setString(settingValue);
                while (splitter.hasNext()) {
                    if (splitter.next().equalsIgnoreCase(serviceId)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void updateStatus(String message) {
        if (mStatusText != null) {
            mHandler.post(() -> {
                mStatusText.setText(message);
                // Contextual status colors
                if (message.contains("★") || message.contains("BASE BOA")) {
                    mStatusText.setTextColor(getColor(R.color.accent_gold));
                } else if (message.contains("Skip") || message.contains("Aguardando")) {
                    mStatusText.setTextColor(getColor(R.color.text_hint));
                } else if (message.contains("ativa") || message.contains("Buscando")) {
                    mStatusText.setTextColor(getColor(R.color.accent_green));
                } else if (message.contains("Erro")) {
                    mStatusText.setTextColor(getColor(R.color.accent_red));
                } else {
                    mStatusText.setTextColor(getColor(R.color.text_secondary));
                }
            });
        }
    }

    private void saveDebugBitmap(Bitmap bitmap, String filename) {
        try {
            File file = new File(getCacheDir(), filename);
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
            Log.d(TAG_OCR, "Debug salvo: " + file.getAbsolutePath()
                    + " (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
        } catch (Exception e) {
            Log.e(TAG, "Erro salvando debug", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Settings
    // ═══════════════════════════════════════════════════════════════

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        thresholdMillion = prefs.getLong("thresholdMillion", DEFAULT_THRESHOLD_MILLION);
        thresholdTenThousand = prefs.getLong("thresholdTenThousand", DEFAULT_THRESHOLD_TEN_THOUSAND);
        minMillionCount = prefs.getInt("minMillionCount", DEFAULT_MIN_MILLION_COUNT);
        minTenThousandCount = prefs.getInt("minTenThousandCount", DEFAULT_MIN_TEN_THOUSAND_COUNT);

        inputThresholdMillion.setText(String.valueOf(thresholdMillion));
        inputThresholdTenK.setText(String.valueOf(thresholdTenThousand));
        inputMinMillionCount.setText(String.valueOf(minMillionCount));
        inputMinTenKCount.setText(String.valueOf(minTenThousandCount));
    }

    private void readAndSaveSettings() {
        thresholdMillion = parseLongSafe(inputThresholdMillion.getText().toString(),
                DEFAULT_THRESHOLD_MILLION);
        thresholdTenThousand = parseLongSafe(inputThresholdTenK.getText().toString(),
                DEFAULT_THRESHOLD_TEN_THOUSAND);
        minMillionCount = parseIntSafe(inputMinMillionCount.getText().toString(),
                DEFAULT_MIN_MILLION_COUNT);
        minTenThousandCount = parseIntSafe(inputMinTenKCount.getText().toString(),
                DEFAULT_MIN_TEN_THOUSAND_COUNT);

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putLong("thresholdMillion", thresholdMillion)
                .putLong("thresholdTenThousand", thresholdTenThousand)
                .putInt("minMillionCount", minMillionCount)
                .putInt("minTenThousandCount", minTenThousandCount)
                .apply();

        Log.d(TAG, "Settings: M>" + thresholdMillion
                + " T>" + thresholdTenThousand
                + " MinM=" + minMillionCount
                + " MinT=" + minTenThousandCount);
    }

    private void setInputsEnabled(boolean enabled) {
        float alpha = enabled ? 1.0f : 0.5f;
        EditText[] inputs = { inputThresholdMillion, inputThresholdTenK,
                inputMinMillionCount, inputMinTenKCount };
        for (EditText input : inputs) {
            input.setEnabled(enabled);
            input.setAlpha(alpha);
        }
    }

    private long parseLongSafe(String text, long def) {
        try {
            return Long.parseLong(text.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private int parseIntSafe(String text, int def) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
