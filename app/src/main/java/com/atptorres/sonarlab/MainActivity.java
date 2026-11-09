package com.atptorres.sonarlab;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Pantalla nativa, sin anuncios, sin red y sin servicios de grabación en segundo plano. */
public final class MainActivity extends Activity implements AudioEngine.Listener {
    private static final int MICROPHONE_PERMISSION = 17;
    private static final int BG = 0xff0a0f15;
    private static final int PANEL = 0xff141c25;
    private static final int BORDER = 0xff293645;
    private static final int TEXT = 0xfff1f5fa;
    private static final int MUTED = 0xff91a0ae;
    private static final int GREEN = 0xff35d8a0;
    private static final int YELLOW = 0xfff2c45a;
    private static final int RED = 0xfff47676;

    private AudioEngine audio;
    private TextView bigValue, stateBadge, refText, adviceText, avgValue, minValue, maxValue;
    private TextView sourceValue, calibrationValue, dominantValue, durationValue, pathBadge, rawValue;
    private Button recordingButton;
    private LevelBarView levelBar;
    private HistoryChartView history;
    private SpectrumView spectrum;
    private long totalFrames;
    private int silentWindows;
    private double minDb = Double.POSITIVE_INFINITY;
    private double maxDb = Double.NEGATIVE_INFINITY;
    private double energySum; // suma de cuadrados de PCM normalizado, ponderada por muestras
    private long startedAt;
    private double offsetDb;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        offsetDb = getPreferences(MODE_PRIVATE).getFloat("calibration_offset_db", 130f);
        audio = new AudioEngine(this, this);
        buildUi();
        showIdle();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        // Android 15 (target SDK 35) dibuja detrás de las barras del sistema.
        // Ajustar insets evita superponer contenido y botones con barra de estado/navegación.
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            scroll.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.setBackgroundColor(BG);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        body.setPadding(pad, dp(22), pad, dp(30));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);

        LinearLayout masthead = horizontal();
        masthead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout mastTexts = vertical();
        TextView brand = text("SONAR  /  LAB", 13, TEXT, true);
        brand.setLetterSpacing(.23f);
        mastTexts.addView(brand);
        TextView brandSub = text("SONÓMETRO + ESPECTRO", 10, MUTED, false);
        brandSub.setLetterSpacing(.11f);
        mastTexts.addView(brandSub, withTop(-1, -2, 3));
        masthead.addView(mastTexts, new LinearLayout.LayoutParams(0, -2, 1f));
        stateBadge = text("●  LISTO", 11, MUTED, true);
        stateBadge.setGravity(Gravity.CENTER);
        stateBadge.setPadding(dp(11), dp(9), dp(11), dp(9));
        stateBadge.setBackground(shape(0xff1b2631, 24, 0xff303e4c));
        masthead.addView(stateBadge);
        body.addView(masthead);

        // PANEL DE INTENSIDAD
        LinearLayout hero = card();
        body.addView(hero, withTop(-1, -2, 25));
        hero.addView(sectionHeader("NIVEL ACTUAL", "TIEMPO REAL"));

        LinearLayout numRow = horizontal();
        numRow.setGravity(Gravity.BOTTOM);
        bigValue = text("--.-", 68, TEXT, true);
        bigValue.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        bigValue.setIncludeFontPadding(false);
        bigValue.setLetterSpacing(-.055f);
        numRow.addView(bigValue);
        TextView unit = text("dB", 20, MUTED, true);
        numRow.addView(unit, withMargins(-2, -2, 10, 0, 0, 11));
        hero.addView(numRow, withTop(-1, -2, 16));

        TextView estimated = text("dB SPL estimados · sin ponderación A", 12, MUTED, false);
        hero.addView(estimated, withTop(-1, -2, 0));

        refText = text("Sin lectura · inicia para medir el entorno", 17, TEXT, true);
        hero.addView(refText, withTop(-1, -2, 24));
        adviceText = text("Las equivalencias son ejemplos de intensidad, no identificación del sonido.", 12, MUTED, false);
        adviceText.setLineSpacing(dp(3), 1f);
        hero.addView(adviceText, withTop(-1, -2, 6));
        levelBar = new LevelBarView(this);
        hero.addView(levelBar, withTop(-1, dp(44), 17));
        pathBadge = text("VÍA DE CAPTURA POR COMPROBAR", 11, MUTED, true);
        hero.addView(pathBadge, withTop(-1, -2, 13));

        // BOTONES
        LinearLayout controls = horizontal();
        recordingButton = new Button(this);
        recordingButton.setText("▶  INICIAR MEDICIÓN");
        recordingButton.setAllCaps(false);
        recordingButton.setTextColor(BG);
        recordingButton.setTextSize(13);
        recordingButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        recordingButton.setBackground(shape(GREEN, 14, GREEN));
        recordingButton.setElevation(0);
        recordingButton.setPadding(dp(8), 0, dp(8), 0);
        controls.addView(recordingButton, new LinearLayout.LayoutParams(0, dp(52), 1f));
        recordingButton.setOnClickListener(v -> toggle());
        Button resetButton = secondaryButton("↺  Reiniciar");
        controls.addView(resetButton, withMargins(-2, dp(52), 9, 0, 0, 0));
        resetButton.setOnClickListener(v -> clearSession());
        body.addView(controls, withTop(-1, -2, 14));

        // RESUMEN DE SESIÓN: Leq energético, no media aritmética de decibelios.
        LinearLayout metrics = horizontal();
        metrics.setWeightSum(3f);
        LinearLayout m1 = metricCard("PROMEDIO Leq", "--.-", GREEN);
        LinearLayout m2 = metricCard("MÍNIMO", "--.-", MUTED);
        LinearLayout m3 = metricCard("MÁXIMO", "--.-", RED);
        avgValue = (TextView) m1.getChildAt(1);
        minValue = (TextView) m2.getChildAt(1);
        maxValue = (TextView) m3.getChildAt(1);
        metrics.addView(m1, new LinearLayout.LayoutParams(0, -2, 1f));
        metrics.addView(m2, withMarginsWeight(0, -2, 1f, 8));
        metrics.addView(m3, withMarginsWeight(0, -2, 1f, 8));
        body.addView(metrics, withTop(-1, -2, 16));

        // GRÁFICO TEMPORAL
        LinearLayout temporalCard = card();
        body.addView(temporalCard, withTop(-1, -2, 17));
        temporalCard.addView(sectionHeader("HISTORIAL DE INTENSIDAD", "~ 90 SEG"));
        history = new HistoryChartView(this);
        temporalCard.addView(history, withTop(-1, dp(190), 16));
        LinearLayout legend = horizontal();
        legend.addView(legendLabel("●  Tranquilo", GREEN));
        legend.addView(legendLabel("●  Precaución", YELLOW), withMargins(-2, -2, 13, 0, 0, 0));
        legend.addView(legendLabel("●  Intenso", RED), withMargins(-2, -2, 13, 0, 0, 0));
        temporalCard.addView(legend, withTop(-1, -2, 6));
        temporalCard.addView(text("Líneas punteadas: mínimo · promedio Leq · máximo", 10, MUTED, false), withTop(-1, -2, 9));

        // FFT
        LinearLayout spectrumCard = card();
        body.addView(spectrumCard, withTop(-1, -2, 17));
        spectrumCard.addView(sectionHeader("ANALIZADOR DE ESPECTRO", "FFT 4096"));
        TextView spectrumSub = text("Frecuencia · intensidad espectral relativa", 12, MUTED, false);
        spectrumCard.addView(spectrumSub, withTop(-1, -2, 8));
        spectrum = new SpectrumView(this);
        spectrumCard.addView(spectrum, withTop(-1, dp(176), 10));
        LinearLayout mainFrequency = horizontal();
        mainFrequency.setGravity(Gravity.CENTER_VERTICAL);
        mainFrequency.addView(text("FRECUENCIA DOMINANTE", 11, MUTED, true), new LinearLayout.LayoutParams(0, -2, 1f));
        dominantValue = text("-- Hz", 18, TEXT, true);
        mainFrequency.addView(dominantValue);
        spectrumCard.addView(mainFrequency, withTop(-1, -2, 17));

        // DIAGNÓSTICO DE RUTA Y AJUSTES
        LinearLayout diagnosisCard = card();
        body.addView(diagnosisCard, withTop(-1, -2, 17));
        diagnosisCard.addView(sectionHeader("FUENTE Y CALIBRACIÓN", "TRANSPARENTE"));
        diagnosisCard.addView(text("ENTRADA DE AUDIO", 10, MUTED, true), withTop(-1, -2, 15));
        sourceValue = text("Micrófono no activado", 14, TEXT, false);
        diagnosisCard.addView(sourceValue, withTop(-1, -2, 5));
        diagnosisCard.addView(text("SEÑAL ORIGINAL / dBFS", 10, MUTED, true), withTop(-1, -2, 15));
        rawValue = text("-- dBFS", 14, TEXT, false);
        diagnosisCard.addView(rawValue, withTop(-1, -2, 5));
        diagnosisCard.addView(text("OFFSET DE CALIBRACIÓN", 10, MUTED, true), withTop(-1, -2, 15));
        calibrationValue = text("", 14, TEXT, false);
        diagnosisCard.addView(calibrationValue, withTop(-1, -2, 5));
        updateCalibrationLabel();
        diagnosisCard.addView(text("TIEMPO DE SESIÓN", 10, MUTED, true), withTop(-1, -2, 15));
        durationValue = text("00:00", 14, TEXT, false);
        diagnosisCard.addView(durationValue, withTop(-1, -2, 5));
        Button calibrateButton = secondaryButton("Ajustar calibración  ↗");
        diagnosisCard.addView(calibrateButton, withTop(-1, dp(47), 15));
        calibrateButton.setOnClickListener(v -> openCalibration());

        LinearLayout disclaimer = vertical();
        disclaimer.setPadding(dp(14), dp(14), dp(14), dp(14));
        disclaimer.setBackground(shape(0xff18212a, 13, 0xff324353));
        disclaimer.addView(text("ⓘ  LECTURA ORIENTATIVA", 12, TEXT, true));
        TextView warning = text("Los dB SPL requieren calibración con sonómetro de referencia. El micrófono, los DSP del fabricante, la saturación y la falta de ponderación A afectan la precisión. Los colores son ayudas visuales, no límites de exposición certificados.", 12, MUTED, false);
        warning.setLineSpacing(dp(4), 1f);
        disclaimer.addView(warning, withTop(-1, -2, 7));
        body.addView(disclaimer, withTop(-1, -2, 16));
        TextView footer = text("SONAR LAB  •  AUDIO LOCAL  •  SIN INTERNET  •  SIN GRABACIONES", 10, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        body.addView(footer, withTop(-1, -2, 24));
    }

    private void toggle() {
        if (audio.isRunning()) {
            audio.stop();
            showIdle();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION);
            return;
        }
        begin();
    }

    private void begin() {
        clearSession();
        startedAt = SystemClock.elapsedRealtime();
        audio.start();
        recordingButton.setText("■  DETENER MEDICIÓN");
        stateBadge.setText("●  EN VIVO");
        stateBadge.setTextColor(GREEN);
        pathBadge.setText("INICIANDO MICRÓFONO…");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == MICROPHONE_PERMISSION) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) begin();
            else Toast.makeText(this, "El micrófono necesita permiso para medir.", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onSample(AudioEngine.Sample reading) {
        // Una entrada silenciada por privacidad puede devolver PCM todo a cero.
        // Ese 0 digital NO equivale a un nivel sonoro físico de 10 dB SPL.
        if (reading.meanSquare <= 1e-12) {
            silentWindows++;
            if (silentWindows >= 4) {
                bigValue.setText("--.-");
                bigValue.setTextColor(MUTED);
                refText.setText("Sin señal PCM válida");
                adviceText.setText("El micrófono devuelve silencio digital. Comprueba permisos, privacidad y que no esté ocupado por otra app.");
                levelBar.setLevel(55, false);
                spectrum.reset();
                dominantValue.setText("-- Hz");
            }
            pathBadge.setText(reading.sourceName + " · captura activa; señal digital nula");
            sourceValue.setText(String.format(Locale.US, "%s · %d Hz", reading.inputName, reading.sampleRate));
            rawValue.setText("≤ -120 dBFS · silencio digital");
            stateBadge.setText("●  RECIBIENDO PCM");
            stateBadge.setTextColor(YELLOW);
            return;
        }
        silentWindows = 0;
        // Acumular TODAS las muestras PCM recibidas, no solo los cuadros mostrados.
        // Para Leq se promedian energías, nunca directamente valores logarítmicos dB.
        energySum += reading.meanSquare * reading.frames;
        totalFrames += reading.frames;
        minDb = Math.min(minDb, reading.minDbFs + offsetDb);
        maxDb = Math.max(maxDb, reading.maxDbFs + offsetDb);
        double db = Math.max(0, Math.min(140, reading.dbFs + offsetDb));
        double leq = Math.max(0, Math.min(140,
                SignalAnalyzer.dbFs(energySum / totalFrames) + offsetDb));
        int severity = db < 70 ? GREEN : db < 85 ? YELLOW : RED;
        bigValue.setText(String.format(Locale.US, "%.1f", db));
        bigValue.setTextColor(severity);
        avgValue.setText(String.format(Locale.US, "%.1f", leq));
        minValue.setText(String.format(Locale.US, "%.1f", Math.max(0, Math.min(140, minDb))));
        maxValue.setText(String.format(Locale.US, "%.1f", Math.max(0, Math.min(140, maxDb))));
        refText.setText(referenceFor(db));
        adviceText.setText(adviceFor(db));
        levelBar.setLevel(db, true);
        history.add(db);
        history.setStatistics(Math.max(0, minDb), leq, Math.min(140, maxDb));
        spectrum.update(reading.bands, db);
        dominantValue.setText(reading.dominantHz > 0 ? formatHz(reading.dominantHz) : "-- Hz");
        String signalRoute = reading.requestedUnprocessed ?
                "UNPROCESSED solicitado · PCM directo" :
                reading.sourceName + " · posible DSP del fabricante";
        pathBadge.setText(signalRoute);
        pathBadge.setTextColor(reading.requestedUnprocessed ? GREEN : YELLOW);
        sourceValue.setText(String.format(Locale.US, "%s · %d Hz", reading.inputName, reading.sampleRate));
        rawValue.setText(String.format(Locale.US, "%.1f dBFS (sin calibrar)", reading.dbFs));
        long seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000;
        durationValue.setText(String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60));
        stateBadge.setText("●  RECIBIENDO PCM");
        stateBadge.setTextColor(GREEN);
    }

    @Override public void onFailure(String detail) {
        audio.stop();
        showIdle();
        pathBadge.setText("ERROR DE CAPTURA");
        pathBadge.setTextColor(RED);
        Toast.makeText(this, "No se pudo medir: " + detail, Toast.LENGTH_LONG).show();
    }

    private String formatHz(int frequency) {
        return frequency >= 1000 ? String.format(Locale.US, "%.2f kHz", frequency / 1000.0) : frequency + " Hz";
    }

    /** Referencias: no es reconocimiento del origen físico del sonido. */
    private String referenceFor(double db) {
        if (db < 40) return "Referencia: biblioteca tranquila";
        if (db < 55) return "Referencia: habitación silenciosa";
        if (db < 70) return "Referencia: conversación normal";
        if (db < 85) return "Referencia: calle concurrida / tráfico";
        if (db < 100) return "Referencia: moto acelerando / herramienta";
        if (db < 110) return "Referencia: concierto de rock";
        return "Referencia: sirena cercana / concierto intenso";
    }

    private String adviceFor(double db) {
        if (db < 70) return "Zona verde · nivel moderado como referencia general.";
        if (db < 85) return "Zona amarilla · la exposición prolongada podría producir fatiga.";
        return "Zona roja · intensidad elevada: limita la exposición y protégete.";
    }

    private void clearSession() {
        totalFrames = 0;
        silentWindows = 0;
        energySum = 0;
        minDb = Double.POSITIVE_INFINITY;
        maxDb = Double.NEGATIVE_INFINITY;
        startedAt = SystemClock.elapsedRealtime();
        avgValue.setText("--.-");
        minValue.setText("--.-");
        maxValue.setText("--.-");
        durationValue.setText("00:00");
        history.reset();
        spectrum.reset();
    }

    private void showIdle() {
        if (recordingButton == null) return;
        recordingButton.setText("▶  INICIAR MEDICIÓN");
        stateBadge.setText("●  EN ESPERA");
        stateBadge.setTextColor(MUTED);
        if (totalFrames == 0) {
            bigValue.setText("--.-");
            bigValue.setTextColor(TEXT);
            refText.setText("Sin lectura · inicia para medir el entorno");
            levelBar.setLevel(55, false);
            if (rawValue != null) rawValue.setText("-- dBFS");
        }
    }

    private void openCalibration() {
        LinearLayout content = vertical();
        content.setPadding(dp(24), dp(8), dp(24), 0);
        TextView explanation = text("Compara con un sonómetro físico. Si este indica 75 dB y SonarLab 70 dB, aumenta el offset en 5 dB. Sin referencia, los valores son estimaciones.", 14, 0xff303d49, false);
        explanation.setLineSpacing(dp(3), 1f);
        content.addView(explanation);
        TextView sliderLabel = text("", 20, 0xff14202a, true);
        sliderLabel.setGravity(Gravity.CENTER);
        content.addView(sliderLabel, withTop(-1, -2, 20));
        SeekBar slider = new SeekBar(this);
        slider.setMax(800); // +90,0 a +170,0 dB
        slider.setProgress((int) Math.round((offsetDb - 90) * 10));
        sliderLabel.setText(String.format(Locale.US, "+%.1f dB", offsetDb));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sliderLabel.setText(String.format(Locale.US, "+%.1f dB", 90 + progress / 10.0));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        content.addView(slider);
        new AlertDialog.Builder(this)
                .setTitle("Calibración de intensidad")
                .setView(content)
                .setNegativeButton("Cancelar", null)
                .setNeutralButton("Restablecer", (dialog, which) -> saveCalibration(130))
                .setPositiveButton("Guardar", (dialog, which) -> saveCalibration(90 + slider.getProgress() / 10.0))
                .show();
    }

    private void saveCalibration(double newOffset) {
        offsetDb = newOffset;
        getPreferences(MODE_PRIVATE).edit().putFloat("calibration_offset_db", (float) newOffset).apply();
        updateCalibrationLabel();
        // Los anteriores valores de una misma sesión dejarían de ser comparables.
        clearSession();
    }

    private void updateCalibrationLabel() {
        calibrationValue.setText(String.format(Locale.US, "+%.1f dB · %s", offsetDb,
                Math.abs(offsetDb - 130.0) < .01 ? "referencia inicial" : "ajuste manual"));
    }

    @Override protected void onPause() {
        super.onPause();
        if (audio != null) { audio.stop(); showIdle(); }
    }

    @Override protected void onDestroy() {
        if (audio != null) audio.stop();
        super.onDestroy();
    }

    // Utilidades de interfaz: solo SDK Android, sin librerías externas.
    private LinearLayout vertical() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout horizontal() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); return v; }
    private LinearLayout card() {
        LinearLayout l = vertical();
        l.setPadding(dp(18), dp(18), dp(18), dp(18));
        l.setBackground(shape(PANEL, 18, BORDER));
        return l;
    }
    private GradientDrawable shape(int color, int corner, int border) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(corner));
        g.setStroke(dp(1), border);
        return g;
    }
    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setFontFeatureSettings("kern");
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }
    private LinearLayout sectionHeader(String title, String detail) {
        LinearLayout r = horizontal();
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView a = text(title, 12, TEXT, true);
        a.setLetterSpacing(.09f);
        r.addView(a, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView b = text(detail, 10, MUTED, true);
        b.setLetterSpacing(.06f);
        r.addView(b);
        return r;
    }
    private LinearLayout metricCard(String label, String value, int accent) {
        LinearLayout l = vertical();
        l.setPadding(dp(12), dp(14), dp(9), dp(13));
        l.setBackground(shape(PANEL, 15, BORDER));
        TextView title = text(label, 9, MUTED, true);
        title.setSingleLine(true);
        title.setTextScaleX(.90f);
        l.addView(title);
        TextView amount = text(value, 23, accent, true);
        amount.setIncludeFontPadding(false);
        l.addView(amount, withTop(-1, -2, 11));
        l.addView(text("dB", 10, MUTED, false), withTop(-1, -2, 3));
        return l;
    }
    private TextView legendLabel(String value, int color) {
        TextView t = text(value, 10, color, false);
        t.setSingleLine(true);
        return t;
    }
    private Button secondaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(shape(0xff18222d, 14, BORDER));
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setElevation(0);
        return b;
    }
    private LinearLayout.LayoutParams withTop(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(top);
        return p;
    }
    private LinearLayout.LayoutParams withMargins(int w, int h, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return p;
    }
    private LinearLayout.LayoutParams withMarginsWeight(int w, int h, float weight, int left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.leftMargin = dp(left);
        return p;
    }
    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }
}
