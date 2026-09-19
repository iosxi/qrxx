package io.qrxx;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * 画面の中央に四隅の鉤だけを描く。
 * 読み取りは画角の全体で行うので、「ここに入れろ」と誤解させる閉じた枠は引かない。
 */
final class Viewfinder extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float corner;

    Viewfinder(Context context) {
        super(context);
        final float density = context.getResources().getDisplayMetrics().density;
        corner = 22f * density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(0x8CFFFFFF);
        setWillNotDraw(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float side = Math.min(getWidth(), getHeight()) * 0.66f;
        final float l = (getWidth() - side) / 2f;
        final float t = (getHeight() - side) / 2f;
        final float r = l + side;
        final float b = t + side;

        path.rewind();
        path.moveTo(l, t + corner); path.lineTo(l, t); path.lineTo(l + corner, t);
        path.moveTo(r - corner, t); path.lineTo(r, t); path.lineTo(r, t + corner);
        path.moveTo(r, b - corner); path.lineTo(r, b); path.lineTo(r - corner, b);
        path.moveTo(l + corner, b); path.lineTo(l, b); path.lineTo(l, b - corner);
        canvas.drawPath(path, paint);
    }
}
