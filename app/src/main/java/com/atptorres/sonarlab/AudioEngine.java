package com.atptorres.sonarlab;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * AudioRecord -> PCM 16 bit -> medición RMS y FFT. No graba a disco, no emite sonido.
 * UNPROCESSED se solicita solo cuando Android lo anuncia, con alternativas automáticas.
 */
public final class AudioEngine {
    public interface Listener {
        void onSample(Sample sample);
        void onFailure(String detail);
    }

    public static final int FFT_SIZE = SignalAnalyzer.FFT_SIZE;
    public static final int SPECTRUM_BINS = SignalAnalyzer.SPECTRUM_BINS;

    public static final class Sample {
        public final double dbFs;
        public final double meanSquare;
        public final long frames;
        public final double minDbFs, maxDbFs;
        public final float[] bands;
        public final int dominantHz;
        public final String sourceName, inputName;
        public final int sampleRate;
        public final boolean requestedUnprocessed;

        Sample(double meanSquare, long frames, double minDbFs, double maxDbFs,
               float[] bands, int dominantHz, String sourceName, String inputName,
               int sampleRate, boolean requestedUnprocessed) {
            this.meanSquare = meanSquare;
            this.dbFs = SignalAnalyzer.dbFs(meanSquare);
            this.frames = frames;
            this.minDbFs = minDbFs;
            this.maxDbFs = maxDbFs;
            this.bands = bands;
            this.dominantHz = dominantHz;
            this.sourceName = sourceName;
            this.inputName = inputName;
            this.sampleRate = sampleRate;
            this.requestedUnprocessed = requestedUnprocessed;
        }
    }

    private static final class Capture {
        final AudioRecord record;
        final int rate, source;
        final Effects effects;
        Capture(AudioRecord record, int rate, int source, Effects effects) {
            this.record = record;
            this.rate = rate;
            this.source = source;
            this.effects = effects;
        }
        void close() {
            try { record.stop(); } catch (Exception ignored) { }
            effects.release();
            record.release();
        }
    }

    private static final class Effects {
        AutomaticGainControl agc;
        NoiseSuppressor ns;
        AcousticEchoCanceler aec;
        Effects(int session) {
            // Nunca abortar una captura porque un controlador de efectos sea defectuoso.
            try {
                if (AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(session);
                    if (agc != null) agc.setEnabled(false);
                }
            } catch (Exception ignored) { }
            try {
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(session);
                    if (ns != null) ns.setEnabled(false);
                }
            } catch (Exception ignored) { }
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(session);
                    if (aec != null) aec.setEnabled(false);
                }
            } catch (Exception ignored) { }
        }
        void release() {
            try { if (agc != null) agc.release(); } catch (Exception ignored) { }
            try { if (ns != null) ns.release(); } catch (Exception ignored) { }
            try { if (aec != null) aec.release(); } catch (Exception ignored) { }
        }
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile AudioRecord recorder;
    private volatile long generation;

    public AudioEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isRunning() { return running; }

    public synchronized void start() {
        if (running) return;
        running = true;
        long token = ++generation;
        Thread thread = new Thread(() -> recordLoop(token), "SonarLab-PCM");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        ++generation;
        AudioRecord r = recorder;
        recorder = null;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) { }
        }
    }

    private Capture initialize(AudioManager manager, long token) {
        boolean rawSupported = manager != null && "true".equalsIgnoreCase(
                manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        int[] sources = rawSupported ?
                new int[]{MediaRecorder.AudioSource.UNPROCESSED,
                        MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC} :
                new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        int[] rates = {48000, 44100, 16000};
        String lastError = "Ninguna configuración PCM admitida";
        for (int rate : rates) {
            for (int source : sources) {
                if (!running || token != generation) return null;
                int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) continue;
                AudioRecord candidate = null;
                Effects fx = null;
                try {
                    candidate = new AudioRecord.Builder()
                            .setAudioSource(source)
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(rate)
                                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                    .build())
                            .setBufferSizeInBytes(Math.max(min * 2, FFT_SIZE * 4))
                            .build();
                    if (candidate.getState() != AudioRecord.STATE_INITIALIZED)
                        throw new IllegalStateException("AudioRecord sin inicializar");
                    if (manager != null) {
                        for (AudioDeviceInfo d : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                                candidate.setPreferredDevice(d);
                                break;
                            }
                        }
                    }
                    fx = new Effects(candidate.getAudioSessionId());
                    if (!running || token != generation) return disposeAndCancel(candidate, fx);
                    candidate.startRecording();
                    if (candidate.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                        throw new IllegalStateException("No se pudo iniciar la captura PCM");
                    return new Capture(candidate, rate, source, fx);
                } catch (Exception ex) {
                    lastError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                    if (candidate != null) {
                        try { candidate.stop(); } catch (Exception ignored) { }
                        candidate.release();
                    }
                    if (fx != null) fx.release();
                }
            }
        }
        throw new IllegalStateException("No se pudo abrir el micrófono: " + lastError +
                ". Comprueba el permiso y el acceso al micrófono en ajustes.");
    }

    private static Capture disposeAndCancel(AudioRecord candidate, Effects fx) {
        try { candidate.stop(); } catch (Exception ignored) { }
        candidate.release();
        if (fx != null) fx.release();
        return null;
    }

    private void recordLoop(long token) {
        Capture capture = null;
        try {
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            capture = initialize(manager, token);
            if (capture == null) return;
            AudioRecord record = capture.record;
            recorder = record;
            String source = capture.source == MediaRecorder.AudioSource.UNPROCESSED ? "UNPROCESSED" :
                    capture.source == MediaRecorder.AudioSource.VOICE_RECOGNITION ? "VOICE_RECOGNITION" : "MIC";
            short[] pcm = new short[FFT_SIZE];
            double accumulatedSquares = 0.0;
            long accumulatedFrames = 0;
            double minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
            long lastUi = SystemClock.elapsedRealtime();
            while (running && token == generation) {
                int count = record.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                if (count <= 0) {
                    if (!running || token != generation) break;
                    throw new IllegalStateException("Fallo al leer del micrófono (" + count + ")");
                }
                double squares = SignalAnalyzer.meanSquare(pcm, count);
                double blockDb = SignalAnalyzer.dbFs(squares);
                accumulatedSquares += squares * count;
                accumulatedFrames += count;
                minimum = Math.min(minimum, blockDb);
                maximum = Math.max(maximum, blockDb);
                long now = SystemClock.elapsedRealtime();
                if (now - lastUi < 145) continue;
                lastUi = now;
                double intervalSquares = accumulatedSquares / accumulatedFrames;
                SignalAnalyzer.Spectrum bins = SignalAnalyzer.spectrum(pcm, count, capture.rate);
                AudioDeviceInfo routed = record.getRoutedDevice();
                String route = routed == null ? "Ruta no informada por Android" :
                        routed.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC ? "Micrófono integrado" :
                                routed.getProductName().toString();
                Sample sample = new Sample(intervalSquares, accumulatedFrames, minimum, maximum,
                        bins.bands, bins.dominantHz, source, route, capture.rate,
                        capture.source == MediaRecorder.AudioSource.UNPROCESSED);
                // Evita que lecturas de una sesión cerrada contaminen la siguiente.
                main.post(() -> { if (running && generation == token) listener.onSample(sample); });
                accumulatedSquares = 0.0;
                accumulatedFrames = 0;
                minimum = Double.POSITIVE_INFINITY;
                maximum = Double.NEGATIVE_INFINITY;
            }
        } catch (Exception ex) {
            if (running && token == generation) {
                String detail = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                main.post(() -> { if (token == generation) listener.onFailure(detail); });
            }
        } finally {
            // Nunca cerrar una captura nueva de la sesión siguiente.
            if (token == generation) { recorder = null; running = false; }
            if (capture != null) capture.close();
        }
    }
}
