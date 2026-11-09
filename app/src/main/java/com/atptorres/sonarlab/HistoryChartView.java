package com.atptorres.sonarlab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Trazo temporal de hasta 450 mediciones ~90 s con segmentos por nivel. */
public final class HistoryChartView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayDeque<Float> levels = new ArrayDeque<>();
    private static final int CAPACITY = 540;
    private final int teal = 0xff35d8a0;
    private final int amber = 0xfff2c45a;
    private final int coral = 0xfff47676;

    public HistoryChartView(Context context) { super(context); }

    public void add(double db) {
        if (levels.size() == CAPACITY) levels.removeFirst();
        levels.addLast((float) db);
        invalidate();
    }

    public void reset() { levels.clear(); invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float x0 = dp(34), x1 = getWidth() - dp(10);
        float y0 = dp(8), y1 = getHeight() - dp(26);
        float chartHeight = y1 - y0;
        if (x1 <= x0 || chartHeight <= 0) return;

        // Zonas por encima del umbral: orientativas; señal sin ponderar A.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x18f47676);
        canvas.drawRect(x0, y(120, y0, y1), x1, y(85, y0, y1), paint);
        paint.setColor(0x15f2c45a);
        canvas.drawRect(x0, y(85, y0, y1), x1, y(70, y0, y1), paint);
        paint.setColor(0x0c35d8a0);
        canvas.drawRect(x0, y(70, y0, y1), x1, y(30, y0, y1), paint);

        paint.setTextSize(dp(10));
        for (int level = 40; level <= 120; level += 20) {
            float yy = y(level, y0, y1);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0xff273442);
            canvas.drawLine(x0, yy, x1, yy, paint);
            paint.setColor(0xff8e9ba9);
            canvas.drawText(Integer.toString(level), dp(2), yy + dp(3), paint);
        }
        paint.setColor(0xff8998a7);
        canvas.drawText("−90 s", x0, getHeight() - dp(5), paint);
        String end = "AHORA";
        canvas.drawText(end, x1 - paint.measureText(end), getHeight() - dp(5), paint);

        List<Float> data = new ArrayList<>(levels);
        if (data.size() < 2) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        for (int i = 1; i < data.size(); i++) {
            float xa = x1 - (data.size() - i) * (x1 - x0) / (CAPACITY - 1f);
            float xb = x1 - (data.size() - 1 - i) * (x1 - x0) / (CAPACITY - 1f);
            float v0 = data.get(i - 1), v1 = data.get(i);
            paint.setColor(color((v0 + v1) / 2f));
            canvas.drawLine(xa, y(v0, y0, y1), xb, y(v1, y0, y1), paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color(data.get(data.size() - 1)));
        canvas.drawCircle(x1, y(data.get(data.size() - 1), y0, y1), dp(3), paint);
    }

    private float y(float value, float top, float bottom) {
        return bottom - Math.max(0, Math.min(1, (value - 30f) / 90f)) * (bottom - top);
    }

    private int color(float value) { return value < 70 ? teal : value < 85 ? amber : coral; }
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
