package io.qrxx;

import android.media.Image;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;

/**
 * カメラの 1 フレーム (YUV_420_888) から QR コードの文字列を取り出す。
 *
 * 色は見ない。輝度 (Y) の面だけを使うので、色差の面には一切触らない。
 * 復号用スレッドから 1 本ずつ呼ばれる前提で、作業用の配列を使い回す。
 */
final class QrDecoder {

    private final QRCodeReader reader = new QRCodeReader();
    private byte[] luma;   // 輝度面の写し
    private byte[] row;    // 画素が飛び飛びに並ぶ端末で使う 1 行分
    private int frame;

    /** 見つかれば文字列、見つからなければ null。 */
    String decode(Image image) {
        final Image.Plane plane = image.getPlanes()[0];
        final ByteBuffer buffer = plane.getBuffer();
        final int width = image.getWidth();
        final int height = image.getHeight();
        final int rowStride = plane.getRowStride();
        final int pixelStride = plane.getPixelStride();

        final int dataWidth;
        if (pixelStride == 1) {
            // 行末の余白ごとそのまま写し、余白込みの幅を ZXing に伝える。詰め直さない分だけ速い。
            final int available = buffer.remaining();
            final int reach = (height - 1) * rowStride + width;   // ZXing が触りうる最後の位置
            final int size = Math.max(available, reach);
            if (luma == null || luma.length < size) luma = new byte[size];
            buffer.get(luma, 0, available);
            dataWidth = rowStride;
        } else {
            // 端末によっては Y が飛び飛びに並ぶ。そのときだけ詰め直す。
            final int size = width * height;
            if (luma == null || luma.length < size) luma = new byte[size];
            if (row == null || row.length < rowStride) row = new byte[rowStride];
            for (int y = 0; y < height; y++) {
                final int start = y * rowStride;
                if (start >= buffer.limit()) break;
                buffer.position(start);
                final int n = Math.min(rowStride, buffer.remaining());
                buffer.get(row, 0, n);
                final int base = y * width;
                for (int x = 0, i = 0; x < width && i < n; x++, i += pixelStride) {
                    luma[base + x] = row[i];
                }
            }
            dataWidth = width;
        }

        // 画角の全体を見る。ZXing の検出器は切り出しシンボルを探すので、
        // コードが傾いていても回っていても、こちらで絵を回す必要はない。
        final LuminanceSource source =
                new PlanarYUVLuminanceSource(luma, dataWidth, height, 0, 0, width, height, false);

        String text = read(source);
        // 白黒が反転した QR も世の中にある。毎フレーム試すと重いので 1 つおきに。
        if (text == null && (++frame & 1) == 0) {
            text = read(source.invert());
        }
        return text;
    }

    private String read(LuminanceSource source) {
        try {
            final Result result = reader.decode(new BinaryBitmap(new HybridBinarizer(source)));
            final String text = result.getText();
            return (text == null || text.isEmpty()) ? null : text;
        } catch (Exception e) {
            // 「見つからない」「誤り訂正に失敗」が大半。どれも次のフレームで やり直せばよい。
            return null;
        }
    }
}
