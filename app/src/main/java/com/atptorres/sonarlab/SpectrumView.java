package com.atptorres.sonarlab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Gráfico FFT logarítmico: 40 Hz–16 kHz; barras relativas, no niveles calibrados por banda. */
public final class SpectrumView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] bands = new float[AudioEngine.SPECTRUM_BINS];
    private int accent = 0xff34d8a0;

    public SpectrumView(Context context) { super(context); }

    public void update(float[] values, double db) {
        if (values.length != bands.length) return;
        accent = db < 70 ? 0xff35d8a0 : db < 85 ? 0xfff2c45a : 0xfff47676;
        for (int i = 0; i < bands.length; i++) {
            // Persistencia VISUAL, no se filtran ni modifican muestras de audio.
            bands[i] = Math.max(values[i], bands[i] * .82f);
        }
        invalidate();
    }

    public void reset() {
        for (int i = 0; i < bands.length; i++) bands[i] = 0;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(2), right = getWidth() - dp(2);
        float top = dp(6), bottom = getHeight() - dp(29);
        float w = right - left, h = bottom - top;
        if (w < 10 || h < 10) return;
        paint.setStrokeWidth(dp(1));
        for (int i = 1; i <= 3; i++) {
            float yy = top + h * i / 4f;
            paint.setColor(0xff263240);
            canvas.drawLine(left, yy, right, yy, paint);
        }
        float gap = dp(2.0f);
        float barW = (w - (bands.length - 1) * gap) / bands.length;
        for (int i = 0; i < bands.length; i++) {
            float x = left + i * (barW + gap);
            float barHeight = Math.max(dp(2), (float) (h * bands[i]));
            // Zona fuerte tiene color más sólido, sin inferir un dB SPL por frecuencia.
            paint.setColor(accent);
            paint.setAlpha((int) (130 + 125 * bands[i]));
            rect.set(x, bottom - barHeight, x + barW, bottom);
            canvas.drawRoundRect(rect, Math.min(dp(3), barW / 2), Math.min(dp(3), barW / 2), paint);
        }
        paint.setAlpha(255);
        paint.setTextSize(dp(10));
        paint.setColor(0xff8998a7);
        drawLabel(canvas, "40", 0f, left, w);
        drawLabel(canvas, "100", ratio(100), left, w);
        drawLabel(canvas, "1k", ratio(1000), left, w);
        drawLabel(canvas, "4k", ratio(4000), left, w);
        drawLabel(canvas, "16k Hz", 1f, left, w);
    }

    private float ratio(double frequency) {
        return (float) (Math.log(frequency / 40) / Math.log(16000.0 / 40));
    }

    private void drawLabel(Canvas canvas, String label, float frac, float left, float width) {
        float x = left + frac * width;
        if (frac == 1) x -= paint.measureText(label);
        else if (frac > 0) x -= paint.measureText(label) / 2;
        canvas.drawText(label, x, getHeight() - dp(5), paint);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
