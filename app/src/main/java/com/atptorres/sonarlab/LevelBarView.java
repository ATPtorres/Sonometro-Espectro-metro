package com.atptorres.sonarlab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Escala sonora orientativa, NO un diagnóstico de riesgo auditivo. */
public final class LevelBarView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float current = 55f;
    private boolean hasData;

    public LevelBarView(Context context) { super(context); }

    public void setLevel(double level, boolean measured) {
        current = (float) level;
        hasData = measured;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float left = dp(5), right = width - dp(5);
        float top = dp(9), bottom = dp(22);
        float span = right - left;
        paint.setColor(0xff26303c);
        rect.set(left, top, right, bottom);
        canvas.drawRoundRect(rect, dp(7), dp(7), paint);
        paint.setColor(0xff33d49e);
        rect.set(left, top, left + span * ((70f - 30f) / 90f), bottom);
        canvas.drawRoundRect(rect, dp(6), dp(6), paint);
        paint.setColor(0xfff3c75a);
        rect.set(left + span * (40f / 90f), top, left + span * (55f / 90f), bottom);
        canvas.drawRect(rect, paint);
        paint.setColor(0xfff16f70);
        rect.set(left + span * (55f / 90f), top, right, bottom);
        canvas.drawRoundRect(rect, dp(6), dp(6), paint);
        if (hasData) {
            float x = left + span * Math.max(0, Math.min(1, (current - 30f) / 90f));
            paint.setColor(0xfff5f8fd);
            canvas.drawRoundRect(new RectF(x - dp(2), dp(1), x + dp(2), dp(29)), dp(2), dp(2), paint);
            canvas.drawCircle(x, dp(15), dp(4), paint);
        }
        paint.setColor(0xff84929e);
        paint.setTextSize(dp(10));
        canvas.drawText("30", left, height - dp(3), paint);
        String text = "70";
        canvas.drawText(text, left + span * 40f / 90f - paint.measureText(text) / 2, height - dp(3), paint);
        text = "85";
        canvas.drawText(text, left + span * 55f / 90f - paint.measureText(text) / 2, height - dp(3), paint);
        text = "120+";
        canvas.drawText(text, right - paint.measureText(text), height - dp(3), paint);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
