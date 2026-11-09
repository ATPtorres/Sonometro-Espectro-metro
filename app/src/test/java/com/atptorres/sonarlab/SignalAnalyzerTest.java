package com.atptorres.sonarlab;

import org.junit.Test;

import static org.junit.Assert.*;

public class SignalAnalyzerTest {
    @Test public void constantHalfScaleIsMinusSixDbfs() {
        short[] pcm = new short[4096];
        for (int i = 0; i < pcm.length; i++) pcm[i] = 16384;
        assertEquals(-6.0206, SignalAnalyzer.dbFs(SignalAnalyzer.meanSquare(pcm, pcm.length)), 0.01);
    }

    @Test public void zeroSignalHasNoDominantFrequency() {
        short[] pcm = new short[4096];
        SignalAnalyzer.Spectrum s = SignalAnalyzer.spectrum(pcm, pcm.length, 48000);
        assertEquals(0, s.dominantHz);
        for (float f : s.bands) assertEquals(0, f, 0);
    }

    @Test public void detects1000HzToneAt48k() {
        short[] pcm = sine(48000, 1000, 0.35);
        SignalAnalyzer.Spectrum s = SignalAnalyzer.spectrum(pcm, pcm.length, 48000);
        assertTrue("Frecuencia detectada: " + s.dominantHz, Math.abs(s.dominantHz - 1000) < 15);
        assertTrue("Alguna banda debe tener energía", max(s.bands) > 0.5);
    }

    @Test public void detects440HzToneAt44100() {
        short[] pcm = sine(44100, 440, 0.35);
        int peak = SignalAnalyzer.spectrum(pcm, pcm.length, 44100).dominantHz;
        assertTrue("Frecuencia detectada: " + peak, Math.abs(peak - 440) < 15);
    }

    @Test public void respectsNyquistAt16000() {
        short[] pcm = sine(16000, 3000, 0.3);
        int peak = SignalAnalyzer.spectrum(pcm, pcm.length, 16000).dominantHz;
        assertTrue("Frecuencia detectada: " + peak, Math.abs(peak - 3000) < 8);
    }

    @Test public void energyAveragingDiffersFromArithmeticDbAverage() {
        double a = Math.pow(10, -20.0 / 10.0);
        double b = Math.pow(10, -40.0 / 10.0);
        double leq = SignalAnalyzer.dbFs((a + b) / 2.0);
        assertEquals(-22.967, leq, 0.01);
    }

    private static short[] sine(int fs, double hz, double amplitude) {
        short[] pcm = new short[4096];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) Math.round(amplitude * 32767 * Math.sin(2.0 * Math.PI * hz * i / fs));
        }
        return pcm;
    }

    private static double max(float[] a) {
        double m = 0;
        for (float x : a) m = Math.max(m, x);
        return m;
    }
}
