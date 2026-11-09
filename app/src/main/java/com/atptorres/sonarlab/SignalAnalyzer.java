package com.atptorres.sonarlab;

import java.util.Arrays;

/** Cálculos sobre muestras PCM sin modificar el audio capturado. Sin dependencias de Android. */
final class SignalAnalyzer {
    static final int FFT_SIZE = 4096;
    static final int SPECTRUM_BINS = 48;
    private static final double MIN_MEAN_SQUARE = 1e-12;

    static double meanSquare(short[] pcm, int count) {
        if (count <= 0 || count > pcm.length) throw new IllegalArgumentException("Tamaño PCM inválido");
        double sum = 0;
        for (int i = 0; i < count; i++) {
            double normalized = pcm[i] / 32768.0;
            sum += normalized * normalized;
        }
        return sum / count;
    }

    static double dbFs(double meanSquare) {
        return 10.0 * Math.log10(Math.max(MIN_MEAN_SQUARE, meanSquare));
    }

    static final class Spectrum {
        final float[] bands;
        final int dominantHz;
        Spectrum(float[] bands, int dominantHz) {
            this.bands = bands;
            this.dominantHz = dominantHz;
        }
    }

    static Spectrum spectrum(short[] pcm, int count, int sampleRate) {
        float[] buckets = new float[SPECTRUM_BINS];
        if (count <= 0 || sampleRate <= 0) return new Spectrum(buckets, 0);
        // Evita representar un pico aleatorio cuando Android devuelve silencio digital.
        if (meanSquare(pcm, count) < MIN_MEAN_SQUARE) return new Spectrum(buckets, 0);

        double[] re = new double[FFT_SIZE];
        double[] im = new double[FFT_SIZE];
        int n = Math.min(count, FFT_SIZE);
        for (int i = 0; i < n; i++) {
            // Hann solo para análisis; dBFS usa PCM original sin ponderación ni filtros.
            double hann = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)));
            re[i] = pcm[i] / 32768.0 * hann;
        }
        fft(re, im);
        Arrays.fill(buckets, -100f);
        double maximum = 0;
        int peakHz = 0;
        double logSpan = Math.log(16000.0 / 40.0);
        double nyquist = sampleRate / 2.0;
        for (int k = 1; k < FFT_SIZE / 2; k++) {
            double hz = (double) k * sampleRate / FFT_SIZE;
            if (hz < 40.0 || hz > 16000 || hz >= nyquist) continue;
            double magnitude = Math.hypot(re[k], im[k]);
            if (magnitude > maximum) { maximum = magnitude; peakHz = (int) Math.round(hz); }
            // Se compensa la ganancia coherente ~0.5 de la ventana de Hann.
            double amplitude = Math.max(1e-9, 4.0 * magnitude / FFT_SIZE);
            float db = (float) (20.0 * Math.log10(amplitude));
            int bucket = Math.max(0, Math.min(SPECTRUM_BINS - 1,
                    (int) (Math.log(hz / 40.0) / logSpan * SPECTRUM_BINS)));
            buckets[bucket] = Math.max(buckets[bucket], db);
        }
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = Math.max(0f, Math.min(1f, (buckets[i] + 90f) / 78f));
        }
        return new Spectrum(buckets, peakHz);
    }

    private static void fft(double[] re, double[] im) {
        int length = re.length;
        for (int i = 1, j = 0; i < length; i++) {
            int bit = length >> 1;
            while ((j & bit) != 0) { j ^= bit; bit >>= 1; }
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int size = 2; size <= length; size <<= 1) {
            double angle = -2.0 * Math.PI / size;
            double rootRe = Math.cos(angle), rootIm = Math.sin(angle);
            for (int begin = 0; begin < length; begin += size) {
                double wr = 1, wi = 0;
                for (int k = 0; k < size / 2; k++) {
                    int a = begin + k, b = a + size / 2;
                    double tr = wr * re[b] - wi * im[b];
                    double ti = wr * im[b] + wi * re[b];
                    re[b] = re[a] - tr; im[b] = im[a] - ti;
                    re[a] += tr; im[a] += ti;
                    double nextRe = wr * rootRe - wi * rootIm;
                    wi = wr * rootIm + wi * rootRe;
                    wr = nextRe;
                }
            }
        }
    }
}
