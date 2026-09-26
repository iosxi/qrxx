package io.qrxx;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Range;
import android.util.Size;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * かざした先の QR コードを、見つけ次第そのまま開く。
 *
 * 読み取り開始の操作も、開く前の確認も置かない。起動 → 映る → 開く、だけ。
 * 画面は 1 枚しかなく、レイアウト XML も使わない (読み込みの手間をそのぶん省く)。
 */
public final class ScanActivity extends Activity {

    private static final int REQ_CAMERA = 11;

    /** 同じコードを映し続けている間は開き直さない。画角から外れてこの時間が過ぎたら、また開く。 */
    private static final long REARM_MS = 1500L;

    /**
     * 復号に回す絵の大きさの目安。
     *
     * パスキー (FIDO ハイブリッド) の QR は 49〜53 モジュールあり、URL の QR (33〜37) より
     * ずっと密。720p だと画面高の 25% ほどまで近づけないと読めないが、1080p なら 15% で
     * 読める = 1.7 倍遠くから合う。1 フレームあたりの復号は 2ms 程度増えるだけで、
     * 30fps の 33ms にはまだ余裕がある。
     */
    private static final int ANALYSIS_PIXELS = 1920 * 1080;

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    // ---- 画面 ----------------------------------------------------------
    private FrameLayout root;
    private PreviewFrame stage;       // SurfaceView のはみ出しを切り落とす枠
    private SurfaceView preview;
    private View flash;
    private TextView hint;
    private FrameLayout.LayoutParams hintParams;
    private TextView zoomLabel;
    private FrameLayout.LayoutParams zoomParams;
    private TextView[] zoomButtons = new TextView[0];   // [i] が (i + 1) 倍
    private FrameLayout.LayoutParams zoomRowParams;
    private TextView torchButton;
    private FrameLayout.LayoutParams torchParams;
    private View scrim;
    private LinearLayout panel;
    private ScrollView scroller;
    private TextView panelText;
    private Button openButton;
    private LinearLayout permissionBox;

    // ---- カメラ --------------------------------------------------------
    private CameraManager manager;
    private String cameraId;
    private int sensorOrientation = 90;
    private boolean hasFlash;
    /** ズーム。倍率で指定できる端末 (API 30 以降) はそちら、駄目なら切り出し矩形で。 */
    private boolean zoomByRatio;
    private float zoom = 1f;
    private float sentZoom = 1f;   // 実際にカメラへ送った倍率
    private float minZoom = 1f;
    private float maxZoom = 1f;
    private Rect activeArray;
    private ScaleGestureDetector pinch;
    private Size previewSize;
    private Size analysisSize;
    private CameraDevice device;
    private CameraCaptureSession session;
    private CaptureRequest.Builder request;
    private volatile ImageReader reader;
    private Surface previewSurface;
    private boolean cameraOpening;
    private boolean torchOn;
    private boolean asked;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Executor cameraExecutor;
    private HandlerThread decodeThread;
    private Handler decodeHandler;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---- 読み取り ------------------------------------------------------
    private final QrDecoder decoder = new QrDecoder();
    private volatile boolean armed;
    private long lastHitAt;
    private String suppressed;
    private String shown;
    private Intent pending;

    // ====================================================================
    // 生き死に
    // ====================================================================

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        // 画面を組む前にカメラ側の下調べを済ませる。SurfaceView に渡す大きさもここで決まる。
        startWorkers();
        chooseCamera();
        // 解析用の絵の置き場は数 MB ある。主スレッドで確保すると最初の一枚が遅れるので、
        // カメラスレッドに逃がす。出来上がったら startSession() を呼び直す。
        cameraHandler.post(this::createReader);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 開いた先から戻ってきた直後に同じコードを開き直さないよう、時計を今に合わせる。
        lastHitAt = SystemClock.elapsedRealtime();
        armed = panel.getVisibility() != View.VISIBLE;
        applyPreviewAspect();

        if (hasCameraPermission()) {
            permissionBox.setVisibility(View.GONE);
            openCamera();
        } else if (!asked) {
            asked = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            permissionBox.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onPause() {
        armed = false;
        closeCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopWorkers();
        if (reader != null) {
            reader.close();
            reader = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (panel.getVisibility() == View.VISIBLE) {
            closePanel();
            return;
        }
        super.onBackPressed();
    }

    private void startWorkers() {
        cameraThread = new HandlerThread("qrxx-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraExecutor = command -> cameraHandler.post(command);

        // 復号は画面と同じくらい急ぐ仕事なので、既定より少し高い優先度で回す。
        decodeThread = new HandlerThread("qrxx-decode", Process.THREAD_PRIORITY_DISPLAY);
        decodeThread.start();
        decodeHandler = new Handler(decodeThread.getLooper());
    }

    private void stopWorkers() {
        if (cameraThread != null) cameraThread.quitSafely();
        if (decodeThread != null) decodeThread.quitSafely();
        cameraThread = null;
        decodeThread = null;
    }

    // ====================================================================
    // 画面づくり
    // ====================================================================

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        // --- カメラ映像。stage がはみ出しを切り落とし、中央だけを画面いっぱいに見せる ---
        stage = new PreviewFrame(this);
        root.addView(stage, new FrameLayout.LayoutParams(MATCH, MATCH));

        preview = new SurfaceView(this);
        stage.addView(preview, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
        final SurfaceHolder holder = preview.getHolder();
        if (previewSize != null) {
            holder.setFixedSize(previewSize.getWidth(), previewSize.getHeight());
        }
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder h) {
                previewSurface = h.getSurface();
                startSession();
            }

            @Override
            public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
                previewSurface = h.getSurface();
                startSession();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
                previewSurface = null;
            }
        });
        applyPreviewAspect();

        // --- 四隅の鉤 ---
        root.addView(new Viewfinder(this), new FrameLayout.LayoutParams(MATCH, MATCH));

        // --- 見つけた瞬間の白い明滅。音も振動も使わないので、合図はこれだけ ---
        flash = new View(this);
        flash.setBackgroundColor(0xFFFFFFFF);
        flash.setAlpha(0f);
        root.addView(flash, new FrameLayout.LayoutParams(MATCH, MATCH));

        // --- 上の案内。数秒で消える ---
        hint = new TextView(this);
        hint.setText(R.string.hint);
        hint.setTextColor(0xFFF2F2F5);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        hint.setPadding(dp(16), dp(9), dp(16), dp(9));
        hint.setBackground(pill(0x66000000));
        hintParams = new FrameLayout.LayoutParams(WRAP, WRAP,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hintParams.topMargin = dp(16);
        root.addView(hint, hintParams);
        hint.animate().alpha(0f).setStartDelay(3200).setDuration(600).start();

        // --- つまんでいる間だけ出る倍率表示。案内と同じ場所 ---
        zoomLabel = new TextView(this);
        zoomLabel.setTextColor(0xFFF2F2F5);
        zoomLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        zoomLabel.setPadding(dp(18), dp(9), dp(18), dp(9));
        zoomLabel.setBackground(pill(0x66000000));
        zoomLabel.setAlpha(0f);
        zoomLabel.setOnClickListener(v -> resetZoom());
        zoomParams = new FrameLayout.LayoutParams(WRAP, WRAP,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        zoomParams.topMargin = dp(16);
        root.addView(zoomLabel, zoomParams);

        pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                onPinch(detector.getScaleFactor());
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                flushZoom();
            }
        });
        // 既定ではダブルタップ + 上下の動きでもズームする。意図せず効くと厄介なので切る。
        pinch.setQuickScaleEnabled(false);

        // --- ライト ---
        torchButton = new TextView(this);
        torchButton.setText(R.string.torch);
        torchButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        torchButton.setPadding(dp(22), dp(12), dp(22), dp(12));
        torchButton.setOnClickListener(v -> toggleTorch());
        torchParams = new FrameLayout.LayoutParams(WRAP, WRAP,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        torchParams.bottomMargin = dp(28);
        root.addView(torchButton, torchParams);
        updateTorchLook();

        // --- 倍率の即決ボタン。つまむより速い。上限は起動時に読み済みの値なので、追加の問い合わせは無い ---
        final LinearLayout zoomRow = buildZoomRow();
        zoomRowParams = new FrameLayout.LayoutParams(WRAP, WRAP,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        zoomRowParams.bottomMargin = zoomRowBottom(0);
        if (zoomRow != null) root.addView(zoomRow, zoomRowParams);

        // --- 開けなかったときだけ出る、文字の受け皿 ---
        scrim = new View(this);
        scrim.setBackgroundColor(0xB3000000);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> closePanel());
        root.addView(scrim, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(buildPanel(), new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));

        // --- 権限が無いときの案内 ---
        root.addView(buildPermissionBox(), new FrameLayout.LayoutParams(MATCH, MATCH));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            final int top;
            final int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                final Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            hintParams.topMargin = top + dp(16);
            zoomParams.topMargin = top + dp(16);
            torchParams.bottomMargin = bottom + dp(28);
            zoomRowParams.bottomMargin = zoomRowBottom(bottom);
            hint.requestLayout();
            zoomLabel.requestLayout();
            torchButton.requestLayout();
            panel.setPadding(dp(20), dp(18), dp(20), bottom + dp(18));
            permissionBox.setPadding(dp(30), top + dp(30), dp(30), bottom + dp(30));
            return insets;
        });

        setContentView(root);
    }

    /**
     * 1x から、この端末で届く整数倍 (8x まで) を並べる。2 つ未満なら並べない。
     * 幅 360dp の画面にも 8 つ収まる大きさにしてある。
     */
    private LinearLayout buildZoomRow() {
        final int count = canZoom() ? Math.min(8, (int) Math.floor(maxZoom + 0.01f)) : 0;
        if (count < 2 || minZoom > 1f + 0.01f) return null;
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), dp(4), dp(4), dp(4));
        row.setBackground(pill(0x66000000));
        zoomButtons = new TextView[count];
        for (int i = 0; i < count; i++) {
            final int times = i + 1;
            final TextView b = new TextView(this);
            b.setText(times + "x");
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            b.setGravity(Gravity.CENTER);
            b.setOnClickListener(v -> setZoom(times));
            row.addView(b, new LinearLayout.LayoutParams(dp(40), dp(40)));
            zoomButtons[i] = b;
        }
        updateZoomButtons();
        return row;
    }

    /**
     * 倍率ボタンの列を、ライトの 12dp 上に置く。ライトの高さは文字の大きさの設定で
     * 変わるので、決め打ちせずに測る (XQ-FS44 の既定で 54dp)。
     */
    private int zoomRowBottom(int systemBottom) {
        torchButton.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        return systemBottom + dp(28) + torchButton.getMeasuredHeight() + dp(12);
    }

    /** 今の倍率に一致するボタンだけを白く塗る。つまんで半端な倍率なら、どれも塗らない。 */
    private void updateZoomButtons() {
        for (int i = 0; i < zoomButtons.length; i++) {
            final boolean on = Math.abs(zoom - (i + 1)) < 0.05f;
            zoomButtons[i].setBackground(on ? pill(0xE6FFFFFF) : null);
            zoomButtons[i].setTextColor(on ? 0xFF16161C : 0xFFF2F2F5);
        }
    }

    private GradientDrawable pill(int color) {
        final GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(dp(999));
        shape.setColor(color);
        return shape;
    }

    private LinearLayout buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);
        panel.setClickable(true);   // 下の scrim に触りを通さない
        final GradientDrawable back = new GradientDrawable();
        back.setColor(0xF216161C);
        back.setCornerRadii(new float[]{dp(22), dp(22), dp(22), dp(22), 0, 0, 0, 0});
        panel.setBackground(back);
        panel.setPadding(dp(20), dp(18), dp(20), dp(18));

        panelText = new TextView(this);
        panelText.setTextColor(0xFFF2F2F5);
        panelText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        panelText.setTextIsSelectable(true);

        scroller = new ScrollView(this);
        scroller.addView(panelText, new ViewGroup.LayoutParams(MATCH, WRAP));
        final LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(MATCH, WRAP);
        scrollParams.bottomMargin = dp(14);
        panel.addView(scroller, scrollParams);

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);

        // 「それでも開く」は、開けそうに見えたのに受け取り手がいなかったときだけ出す。
        openButton = button(R.string.open_anyway, v -> forceOpen());
        openButton.setVisibility(View.GONE);
        row.addView(openButton);
        row.addView(button(R.string.copy, v -> copy()));
        row.addView(button(R.string.share, v -> share()));
        row.addView(button(R.string.close, v -> closePanel()));
        panel.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        return panel;
    }

    private Button button(int label, View.OnClickListener action) {
        final Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setTextColor(0xFF35D48A);
        b.setAllCaps(false);
        b.setOnClickListener(action);
        return b;
    }

    private LinearLayout buildPermissionBox() {
        permissionBox = new LinearLayout(this);
        permissionBox.setOrientation(LinearLayout.VERTICAL);
        permissionBox.setGravity(Gravity.CENTER);
        permissionBox.setBackgroundColor(0xFF000000);
        permissionBox.setVisibility(View.GONE);
        permissionBox.setClickable(true);
        permissionBox.setPadding(dp(30), dp(30), dp(30), dp(30));

        final TextView title = new TextView(this);
        title.setText(R.string.need_camera);
        title.setTextColor(0xFFF2F2F5);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        permissionBox.addView(title);

        final TextView body = new TextView(this);
        body.setText(R.string.need_camera_body);
        body.setTextColor(0xFFA8A8B4);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        body.setLineSpacing(dp(4), 1f);
        final LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        bodyParams.topMargin = dp(12);
        bodyParams.bottomMargin = dp(20);
        permissionBox.addView(body, bodyParams);

        permissionBox.addView(button(R.string.grant, v -> askAgain()));
        return permissionBox;
    }

    // ====================================================================
    // カメラ
    // ====================================================================

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        if (code != REQ_CAMERA) {
            super.onRequestPermissionsResult(code, permissions, grants);
            return;
        }
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            permissionBox.setVisibility(View.GONE);
            openCamera();
        } else {
            permissionBox.setVisibility(View.VISIBLE);
        }
    }

    private void askAgain() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        // 「今後表示しない」を選んだあと。設定から戻してもらうしかない。
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        } catch (ActivityNotFoundException ignored) {
            // 設定画面が無い端末。ここでできることは無い。
        }
    }

    /** どのカメラを、どの大きさで使うかを決める。画面を組む前に済ませておく。 */
    private void chooseCamera() {
        if (manager == null) return;
        try {
            String chosen = null;
            for (String id : manager.getCameraIdList()) {
                if (chosen == null) chosen = id;
                final Integer facing = manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    chosen = id;
                    break;
                }
            }
            if (chosen == null) return;
            cameraId = chosen;

            final CameraCharacteristics c = manager.getCameraCharacteristics(cameraId);
            final Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (orientation != null) sensorOrientation = orientation;
            final Boolean flashAvailable = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            hasFlash = flashAvailable != null && flashAvailable;
            readZoomRange(c);

            final StreamConfigurationMap map =
                    c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return;
            previewSize = pickPreview(map.getOutputSizes(SurfaceHolder.class));
            analysisSize = pickAnalysis(map.getOutputSizes(ImageFormat.YUV_420_888));
        } catch (CameraAccessException | IllegalArgumentException | NullPointerException e) {
            cameraId = null;
        }
    }

    /** 見せる側。画面の縦横比に近いものを、1080p を上限に選ぶ。 */
    private Size pickPreview(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1280, 720);
        final DisplayMetrics metrics = getResources().getDisplayMetrics();
        final int longSide = Math.max(metrics.widthPixels, metrics.heightPixels);
        final int shortSide = Math.max(1, Math.min(metrics.widthPixels, metrics.heightPixels));
        final float want = longSide / (float) shortSide;

        Size best = null;
        float bestScore = Float.MAX_VALUE;
        for (Size s : sizes) {
            final int w = Math.max(s.getWidth(), s.getHeight());
            final int h = Math.min(s.getWidth(), s.getHeight());
            if (w < 640 || w > 1920 || h > 1088) continue;
            final float score = Math.abs(w / (float) h - want) * 10f
                    + Math.abs(w - longSide) / (float) longSide;
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best != null ? best : sizes[0];
    }

    /** 読み取る側。720p 前後で、見せている側と同じ縦横比のものを選ぶ。 */
    private Size pickAnalysis(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1280, 720);
        final float want = previewSize == null ? 16f / 9f
                : Math.max(previewSize.getWidth(), previewSize.getHeight())
                / (float) Math.min(previewSize.getWidth(), previewSize.getHeight());

        Size best = null;
        float bestScore = Float.MAX_VALUE;
        for (Size s : sizes) {
            final int w = Math.max(s.getWidth(), s.getHeight());
            final int h = Math.min(s.getWidth(), s.getHeight());
            if (w < 640 || w * h > 2100000) continue;
            final float score = Math.abs(w / (float) h - want) * 4f
                    + Math.abs(w * h - ANALYSIS_PIXELS) / (float) ANALYSIS_PIXELS;
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best != null ? best : sizes[0];
    }

    /**
     * この端末でどこまでズームできるかを読む。
     *
     * API 30 以降の CONTROL_ZOOM_RATIO は、望遠レンズへの切り替えまで含めて
     * 端末が面倒を見てくれる。使えない端末では、撮像面の一部だけを切り出す
     * SCALER_CROP_REGION に落とす (こちらは digital zoom だけ)。
     */
    private void readZoomRange(CameraCharacteristics c) {
        zoomByRatio = false;
        minZoom = 1f;
        maxZoom = 1f;
        activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final Range<Float> range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (range != null && range.getUpper() > range.getLower()) {
                minZoom = range.getLower();
                maxZoom = range.getUpper();
                zoomByRatio = true;
            }
        }
        if (!zoomByRatio) {
            final Float maxDigital = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            if (maxDigital != null && maxDigital > 1f && activeArray != null) {
                maxZoom = maxDigital;
            }
        }
        zoom = clamp(1f);
        sentZoom = zoom;
    }

    private float clamp(float value) {
        return Math.max(minZoom, Math.min(maxZoom, value));
    }

    private boolean canZoom() {
        return maxZoom > minZoom + 0.01f;
    }

    /** 今の倍率を撮影要求に載せる。applyRepeating() から毎回呼ぶので、ずれない。 */
    private void applyZoom(CaptureRequest.Builder builder) {
        if (zoomByRatio) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
            return;
        }
        if (activeArray == null) return;
        final int w = Math.max(1, Math.round(activeArray.width() / zoom));
        final int h = Math.max(1, Math.round(activeArray.height() / zoom));
        final int left = activeArray.left + (activeArray.width() - w) / 2;
        final int top = activeArray.top + (activeArray.height() - h) / 2;
        builder.set(CaptureRequest.SCALER_CROP_REGION, new Rect(left, top, left + w, top + h));
    }

    /** つまむ動きは画面のどこで始まってもよいので、触りの入口で横取りする。 */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (pinch != null && canZoom()) pinch.onTouchEvent(event);
        return super.dispatchTouchEvent(event);
    }

    /**
     * つまむ動きは 1 回ごとに「前回からの比」で来るので、小さすぎるからと捨てると
     * その分の動きが永久に失われる。倍率そのものは毎回きちんと積み、
     * カメラへ送るのだけを間引く。指を止めていても微細な揺れで毎秒 30 回ほど
     * 呼ばれるので、そのまま投げると要求を作っては捨てるだけになる。
     */
    private void onPinch(float factor) {
        final float next = clamp(zoom * factor);
        if (next == zoom) return;
        zoom = next;
        showZoom();
        if (Math.abs(zoom - sentZoom) >= sentZoom * 0.004f) {
            sentZoom = zoom;
            applyRepeating();
        }
    }

    /** 指を離したところで、間引いて送れていなかった分を送る。 */
    private void flushZoom() {
        if (zoom == sentZoom) return;
        sentZoom = zoom;
        applyRepeating();
    }

    /**
     * 倍率を出す。等倍に戻るまでは消さない。
     * ズームしたままだと次に開いたときに戸惑うので、今どうなっているかは見えていてほしい。
     */
    private void showZoom() {
        if (zoomLabel == null) return;
        updateZoomButtons();
        // 案内が出ている最中なら引っ込める。同じ場所に重ねない。
        hint.animate().cancel();
        hint.setAlpha(0f);
        zoomLabel.setText(String.format(Locale.US, "%.1fx", zoom));
        zoomLabel.animate().cancel();
        zoomLabel.setAlpha(1f);
        if (Math.abs(zoom - 1f) < 0.05f) {
            zoomLabel.animate().alpha(0f).setStartDelay(1200).setDuration(400).start();
        }
    }

    private void resetZoom() {
        setZoom(1f);
    }

    private void setZoom(float value) {
        if (!canZoom()) return;
        zoom = clamp(value);
        sentZoom = zoom;
        applyRepeating();
        showZoom();
    }

    private int displayRotation() {
        int rotation = Surface.ROTATION_0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final Display display = getDisplay();
            if (display != null) rotation = display.getRotation();
        } else {
            rotation = getWindowManager().getDefaultDisplay().getRotation();
        }
        switch (rotation) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    /**
     * 映像が画面に出るときの縦横を枠に教える。あとは PreviewFrame が中央を切り出す。
     * カメラから出る絵はセンサの向きのままなので、90 度ずれるときは縦横を入れ替えて考える。
     */
    private void applyPreviewAspect() {
        if (previewSize == null || stage == null) return;
        final int relative = (sensorOrientation - displayRotation() + 360) % 360;
        final boolean swap = relative == 90 || relative == 270;
        stage.setContentSize(
                swap ? previewSize.getHeight() : previewSize.getWidth(),
                swap ? previewSize.getWidth() : previewSize.getHeight());
    }

    private void openCamera() {
        if (cameraId == null) {
            fail();
            return;
        }
        if (device != null) {
            startSession();
            return;
        }
        if (cameraOpening) return;
        cameraOpening = true;
        try {
            manager.openCamera(cameraId, deviceCallback, cameraHandler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            cameraOpening = false;
            fail();
        }
    }

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            ui.post(() -> {
                cameraOpening = false;
                if (isFinishing() || isDestroyed()) {
                    camera.close();
                    return;
                }
                device = camera;
                startSession();
            });
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            ui.post(() -> {
                cameraOpening = false;
                if (device == camera) device = null;
            });
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            ui.post(() -> {
                cameraOpening = false;
                if (device == camera) device = null;
                fail();
            });
        }
    };

    /** 解析用の絵の置き場。大きさは変わらないので、画面が生きている間ずっと使い回す。 */
    private void createReader() {
        if (reader != null) return;
        if (analysisSize == null) analysisSize = new Size(1280, 720);
        final ImageReader created = ImageReader.newInstance(
                analysisSize.getWidth(), analysisSize.getHeight(), ImageFormat.YUV_420_888, 2);
        created.setOnImageAvailableListener(onFrame, decodeHandler);
        reader = created;
        ui.post(this::startSession);
    }

    /** 映す面と読む面が両方そろってから、一度だけ組む。 */
    private void startSession() {
        if (device == null || previewSurface == null || session != null || reader == null) return;
        try {
            request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(previewSurface);
            request.addTarget(reader.getSurface());
            request.set(CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            request.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);

            final List<OutputConfiguration> outputs = new ArrayList<>(2);
            outputs.add(new OutputConfiguration(previewSurface));
            outputs.add(new OutputConfiguration(reader.getSurface()));
            device.createCaptureSession(new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, outputs, cameraExecutor,
                    sessionCallback));
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
            fail();
        }
    }

    private final CameraCaptureSession.StateCallback sessionCallback =
            new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession configured) {
                    ui.post(() -> {
                        if (device == null) {
                            configured.close();
                            return;
                        }
                        session = configured;
                        applyRepeating();
                    });
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession configured) {
                    ui.post(ScanActivity.this::fail);
                }
            };

    private void applyRepeating() {
        if (session == null || request == null) return;
        request.set(CaptureRequest.FLASH_MODE,
                torchOn ? CameraMetadata.FLASH_MODE_TORCH : CameraMetadata.FLASH_MODE_OFF);
        applyZoom(request);
        try {
            session.setRepeatingRequest(request.build(), null, cameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            // 閉じた直後など。次に開き直すときに組み直される。
        }
    }

    private void closeCamera() {
        if (session != null) {
            try {
                session.close();
            } catch (IllegalStateException ignored) {
                // すでに閉じている。
            }
            session = null;
        }
        if (device != null) {
            device.close();
            device = null;
        }
        // reader はそのまま残す。裏から戻ったときに確保し直さずに済む。
        request = null;
        if (torchOn) {
            torchOn = false;
            updateTorchLook();
        }
    }

    private void toggleTorch() {
        if (!hasFlash || session == null) return;
        torchOn = !torchOn;
        updateTorchLook();
        applyRepeating();
    }

    private void updateTorchLook() {
        if (torchButton == null) return;
        torchButton.setBackground(pill(torchOn ? 0xE6FFFFFF : 0x66000000));
        torchButton.setTextColor(torchOn ? 0xFF16161C : 0xFFF2F2F5);
    }

    private void fail() {
        Toast.makeText(this, R.string.camera_failed, Toast.LENGTH_LONG).show();
    }

    // ====================================================================
    // 読み取りと、その先
    // ====================================================================

    private final ImageReader.OnImageAvailableListener onFrame = source -> {
        final Image image;
        try {
            image = source.acquireLatestImage();   // 溜まった古い絵は捨て、最新の 1 枚だけ見る
        } catch (IllegalStateException e) {
            return;
        }
        if (image == null) return;
        try {
            if (!armed) return;
            final String text = decoder.decode(image);
            if (text != null) ui.post(() -> onFound(text));
        } catch (Exception e) {
            // この 1 枚は諦める。次の絵がすぐ来る。
        } finally {
            // 裏に回った瞬間に ImageReader を閉じると、ここが後から走ることがある。
            // このスレッドで例外を取りこぼすとプロセスごと落ちるので、必ず受ける。
            try {
                image.close();
            } catch (RuntimeException ignored) {
                // すでに無効になっている。閉じる先が無いだけなので、何もしない。
            }
        }
    };

    private void onFound(String text) {
        if (!armed) return;

        final long now = SystemClock.elapsedRealtime();
        final boolean wasAway = now - lastHitAt > REARM_MS;
        lastHitAt = now;
        // 開いた先から戻ってきて、同じコードをまだ映しっぱなし、という場面。
        // いったん画角から外してもらうまでは開き直さない。
        if (text.equals(suppressed) && !wasAway) return;

        armed = false;
        suppressed = null;
        blink();

        pending = Opener.viewIntent(text);
        if (pending != null) {
            try {
                startActivity(pending);
                suppressed = text;
                return;   // armed は onResume で戻す
            } catch (ActivityNotFoundException | SecurityException e) {
                // 受け取れるアプリがいなかった。下で文字として見せる。
            }
        }
        showPanel(text);
    }

    private void blink() {
        flash.animate().cancel();
        flash.setAlpha(0.8f);
        flash.animate().alpha(0f).setDuration(220).start();
    }

    private void showPanel(String text) {
        shown = text;
        panelText.setText(text);
        // 長い文章のときだけ、受け皿の高さを抑えて中で送れるようにする。
        scroller.getLayoutParams().height = text.length() > 180 ? dp(200) : WRAP;
        openButton.setVisibility(pending != null ? View.VISIBLE : View.GONE);
        scrim.setVisibility(View.VISIBLE);
        panel.setVisibility(View.VISIBLE);
        panel.requestLayout();
    }

    private void closePanel() {
        scrim.setVisibility(View.GONE);
        panel.setVisibility(View.GONE);
        suppressed = shown;
        lastHitAt = SystemClock.elapsedRealtime();
        armed = true;
    }

    /** ブラウザから辿れる相手はいなかったが、それでも開いてみる。 */
    private void forceOpen() {
        if (pending == null) return;
        final Intent intent = new Intent(pending);
        intent.removeCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(intent);
            suppressed = shown;
            scrim.setVisibility(View.GONE);
            panel.setVisibility(View.GONE);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_openable, Toast.LENGTH_SHORT).show();
        }
    }

    private void copy() {
        final ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || shown == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("qrxx", shown));
        // Android 13 からは system 側が「コピーしました」を出すので、重ねない。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void share() {
        if (shown == null) return;
        final Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, shown);
        try {
            startActivity(Intent.createChooser(send, null));
        } catch (ActivityNotFoundException ignored) {
            // 共有できる相手がいない端末。
        }
    }
}
