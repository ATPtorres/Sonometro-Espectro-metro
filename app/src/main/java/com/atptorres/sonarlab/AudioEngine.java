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

import java.util.Arrays;

/**
 * Captura mono PCM directamente desde AudioRecord: sin reproducción, ecualización,
 * compresión, normalización de amplitud ni filtros sobre las muestras capturadas.
 * La ventana de Hann se aplica SOLO a una copia para dibujar el espectro FFT.
 */
public final class AudioEngine {
    public interface Listener {
        void onSample(Sample sample);
        void onFailure(String detail);
    }

    public static final int SAMPLE_RATE = 48000;
    public static final int FFT_SIZE = 4096;
    public static final int SPECTRUM_BINS = 48;

    public static final class Sample {
        public final double dbFs;
        public final float[] bands;
        public final int dominantHz;
        public final boolean unprocessed;
        public final String inputName;

        Sample(double dbFs, float[] bands, int dominantHz, boolean unprocessed, String inputName) {
            this.dbFs = dbFs;
            this.bands = bands;
            this.dominantHz = dominantHz;
            this.unprocessed = unprocessed;
            this.inputName = inputName;
        }
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile AudioRecord recorder;
    private Thread readerThread;
    private volatile long generation;

    public AudioEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        long token = ++generation;
        readerThread = new Thread(() -> recordLoop(token), "SonarLab-PCM-capture");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public synchronized void stop() {
        running = false;
        generation++;
        AudioRecord source = recorder;
        recorder = null;
        if (source != null) {
            try { source.stop(); } catch (IllegalStateException ignored) { }
        }
    }

    private void recordLoop(long token) {
        AudioRecord local = null;
        AutomaticGainControl agc = null;
        NoiseSuppressor ns = null;
        AcousticEchoCanceler aec = null;
        try {
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            boolean raw = manager != null && "true".equalsIgnoreCase(
                    manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
            int source = raw ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.MIC;
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalStateException("El dispositivo no admite captura mono PCM a 48 kHz");

            local = new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, FFT_SIZE * 4))
                    .build();

            if (local.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("No se pudo inicializar el micrófono");
            }

            if (manager != null) {
                for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                    if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                        local.setPreferredDevice(device);
                        break;
                    }
                }
            }
            // Desactivar los efectos Android controlables. Los DSP del fabricante
            // pueden seguir activos si el sistema no expone una ruta genuinamente raw.
            int audioSession = local.getAudioSessionId();
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(audioSession);
                if (agc != null) agc.setEnabled(false);
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(audioSession);
                if (ns != null) ns.setEnabled(false);
            }
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(audioSession);
                if (aec != null) aec.setEnabled(false);
            }

            if (!running || token != generation) return;
            recorder = local;
            local.startRecording();
            if (local.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Android no permitió iniciar la grabación");
            }

            short[] pcm = new short[FFT_SIZE];
            long lastUi = 0L;
            while (running && token == generation) {
                int count = local.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                if (count <= 0) {
                    if (!running) break;
                    throw new IllegalStateException("Error al leer PCM (" + count + ")");
                }
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastUi < 145) continue;
                lastUi = now;

                double sum = 0.0;
                for (int i = 0; i < count; i++) {
                    double sample = pcm[i] / 32768.0;
                    sum += sample * sample;
                }
                // dBFS = 20 log10(RMS). No confundir con dB SPL físico.
                double rms = Math.sqrt(sum / count);
                double dbFs = 20.0 * Math.log10(Math.max(1.0e-9, rms));
                Spectrum result = computeSpectrum(pcm, count);
                AudioDeviceInfo route = local.getRoutedDevice();
                String input = route == null ? "Ruta de entrada no informada" :
                        route.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC ? "Micrófono integrado" :
                        route.getProductName().toString();
                Sample snapshot = new Sample(dbFs, result.bands, result.dominantHz, raw, input);
                main.post(() -> { if (running && token == generation) listener.onSample(snapshot); });
            }
        } catch (Exception error) {
            if (running && token == generation) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                main.post(() -> listener.onFailure(message));
            }
        } finally {
            if (token == generation) { running = false; recorder = null; }
            if (agc != null) agc.release();
            if (ns != null) ns.release();
            if (aec != null) aec.release();
            if (local != null) {
                try { local.stop(); } catch (IllegalStateException ignored) { }
                local.release();
            }
        }
    }

    private static final class Spectrum {
        final float[] bands;
        final int dominantHz;
        Spectrum(float[] bands, int dominantHz) {
            this.bands = bands;
            this.dominantHz = dominantHz;
        }
    }

    private Spectrum computeSpectrum(short[] pcm, int sampleCount) {
        double[] re = new double[FFT_SIZE];
        double[] im = new double[FFT_SIZE];
        for (int i = 0; i < FFT_SIZE && i < sampleCount; i++) {
            // Ventana analítica; NO modifica el flujo PCM ni el cálculo de nivel.
            double hann = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)));
            re[i] = pcm[i] / 32768.0 * hann;
        }
        fft(re, im);
        float[] buckets = new float[SPECTRUM_BINS];
        Arrays.fill(buckets, -100f);
        double strongest = 0;
        int peakHz = 0;
        double startLog = Math.log(40.0);
        double logSpan = Math.log(16000.0) - startLog;
        for (int k = 1; k < FFT_SIZE / 2; k++) {
            double freq = k * (double) SAMPLE_RATE / FFT_SIZE;
            if (freq < 40 || freq > 16000) continue;
            double mag = Math.hypot(re[k], im[k]);
            if (mag > strongest) { strongest = mag; peakHz = (int) Math.round(freq); }
            // 2/N corregido por la ganancia coherente de 0.5 de Hann.
            double amplitude = Math.max(1e-9, 4 * mag / FFT_SIZE);
            float db = (float) (20 * Math.log10(amplitude));
            int bin = Math.min(SPECTRUM_BINS - 1,
                    Math.max(0, (int) (Math.log(freq / 40.0) / logSpan * SPECTRUM_BINS)));
            buckets[bin] = Math.max(buckets[bin], db);
        }
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = Math.max(0f, Math.min(1f, (buckets[i] + 90f) / 78f));
        }
        return new Spectrum(buckets, peakHz);
    }

    /** FFT radix-2 iterativa, solo análisis local y sin librerías externas. */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) { j ^= bit; bit >>= 1; }
            j ^= bit;
            if (i < j) {
                double tmp = re[i]; re[i] = re[j]; re[j] = tmp;
                tmp = im[i]; im[i] = im[j]; im[j] = tmp;
            }
        }
        for (int size = 2; size <= n; size <<= 1) {
            double theta = -2.0 * Math.PI / size;
            double rootRe = Math.cos(theta), rootIm = Math.sin(theta);
            for (int begin = 0; begin < n; begin += size) {
                double wr = 1.0, wi = 0.0;
                for (int k = 0; k < size / 2; k++) {
                    int a = begin + k, b = a + size / 2;
                    double tr = wr * re[b] - wi * im[b];
                    double ti = wr * im[b] + wi * re[b];
                    re[b] = re[a] - tr; im[b] = im[a] - ti;
                    re[a] += tr; im[a] += ti;
                    double nw = wr * rootRe - wi * rootIm;
                    wi = wr * rootIm + wi * rootRe;
                    wr = nw;
                }
            }
        }
    }
}
