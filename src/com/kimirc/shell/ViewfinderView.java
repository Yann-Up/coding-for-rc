package com.kimirc.shell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class ViewfinderView extends View {

    private final Paint dimPaint = new Paint();
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float cornerLength;
    private final float cornerInset;

    public ViewfinderView(Context context) {
        this(context, null);
    }

    public ViewfinderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dimPaint.setColor(context.getColor(R.color.scan_scrim));
        cornerPaint.setColor(context.getColor(R.color.white));
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);
        cornerPaint.setStrokeWidth(context.getResources().getDimension(
                R.dimen.scan_corner_stroke));
        cornerLength = context.getResources().getDimension(R.dimen.scan_corner_length);
        cornerInset = context.getResources().getDimension(R.dimen.scan_corner_inset);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        int size = (int) (Math.min(width, height) * 0.62f);
        int left = (width - size) / 2;
        int top = (height - size) / 2 - (int) (height * 0.03f);
        int right = left + size;
        int bottom = top + size;

        canvas.drawRect(0, 0, width, top, dimPaint);
        canvas.drawRect(0, bottom, width, height, dimPaint);
        canvas.drawRect(0, top, left, bottom, dimPaint);
        canvas.drawRect(right, top, width, bottom, dimPaint);

        canvas.drawLine(left - cornerInset, top, left + cornerLength, top, cornerPaint);
        canvas.drawLine(left, top - cornerInset, left, top + cornerLength, cornerPaint);
        canvas.drawLine(right + cornerInset, top, right - cornerLength, top, cornerPaint);
        canvas.drawLine(right, top - cornerInset, right, top + cornerLength, cornerPaint);
        canvas.drawLine(left - cornerInset, bottom, left + cornerLength, bottom, cornerPaint);
        canvas.drawLine(left, bottom + cornerInset, left, bottom - cornerLength, cornerPaint);
        canvas.drawLine(right + cornerInset, bottom, right - cornerLength, bottom, cornerPaint);
        canvas.drawLine(right, bottom + cornerInset, right, bottom - cornerLength, cornerPaint);
    }
}
