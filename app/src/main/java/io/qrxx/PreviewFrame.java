package io.qrxx;

import android.content.Context;
import android.widget.FrameLayout;

/**
 * カメラ映像を、縦横比を保ったまま画面いっぱいに見せる枠。
 *
 * 子 (SurfaceView) を枠より大きく置いて、はみ出した分を切り落とす。
 * レイアウトの最中に子の大きさを付け替えると測り直しが二度走るので、
 * 測る所と置く所で完結させている。
 */
final class PreviewFrame extends FrameLayout {

    /** 画面に出たときの映像の縦横。センサの向きの分はここに織り込んである。 */
    private int contentWidth;
    private int contentHeight;

    PreviewFrame(Context context) {
        super(context);
    }

    void setContentSize(int width, int height) {
        if (width == contentWidth && height == contentHeight) return;
        contentWidth = width;
        contentHeight = height;
        requestLayout();
    }

    /** 枠を覆うのに必要な倍率。短い辺ではなく長い辺に合わせるので、必ず全面が埋まる。 */
    private float coverScale(int width, int height) {
        return Math.max(width / (float) contentWidth, height / (float) contentHeight);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        if (contentWidth <= 0 || contentHeight <= 0) return;

        final float scale = coverScale(getMeasuredWidth(), getMeasuredHeight());
        final int childWidth = Math.round(contentWidth * scale);
        final int childHeight = Math.round(contentHeight * scale);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (contentWidth <= 0 || contentHeight <= 0) {
            super.onLayout(changed, left, top, right, bottom);
            return;
        }
        final int width = right - left;
        final int height = bottom - top;
        final float scale = coverScale(width, height);
        final int childWidth = Math.round(contentWidth * scale);
        final int childHeight = Math.round(contentHeight * scale);
        final int x = (width - childWidth) / 2;
        final int y = (height - childHeight) / 2;
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).layout(x, y, x + childWidth, y + childHeight);
        }
    }
}
