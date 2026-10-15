package com.kimirc.shell;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ScanActivity extends Activity implements TextureView.SurfaceTextureListener {

    public static final String EXTRA_RESULT = "com.kimirc.shell.extra.SCAN_RESULT";

    private static final String TAG = "KimiRC";
    private static final int REQ_CAMERA = 51;
    private static final int TARGET_PIXELS = 1280 * 720;

    private TextureView textureView;
    private Button torchButton;

    private CameraManager cameraManager;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader imageReader;
    private CaptureRequest.Builder previewRequest;
    private Surface previewSurface;
    private String cameraId;
    private Size previewSize;

    private HandlerThread bgThread;
    private Handler bgHandler;

    private boolean torchOn;
    private volatile boolean found;
    private int frameCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_scan);

        textureView = (TextureView) findViewById(R.id.scan_preview);
        torchButton = (Button) findViewById(R.id.torch_button);
        ImageButton closeButton = (ImageButton) findViewById(R.id.scan_close);
        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        torchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTorch();
            }
        });

        textureView.setSurfaceTextureListener(this);
        if (!hasCameraPermission()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBgThread();
        if (textureView.isAvailable()) {
            openCamera();
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBgThread();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                Toast.makeText(this, R.string.camera_denied, Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        openCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        closeCamera();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void startBgThread() {
        bgThread = new HandlerThread("scan");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
    }

    private void stopBgThread() {
        if (bgThread != null) {
            bgThread.quitSafely();
            try {
                bgThread.join(500);
            } catch (InterruptedException ignored) {
            }
            bgThread = null;
            bgHandler = null;
        }
    }

    private void openCamera() {
        if (camera != null || found || !hasCameraPermission()) {
            return;
        }
        if (textureView.getSurfaceTexture() == null) {
            return;
        }
        try {
            cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String[] ids = cameraManager.getCameraIdList();
            for (String id : ids) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null && ids.length > 0) {
                cameraId = ids[0];
            }
            if (cameraId == null) {
                fail(R.string.scan_error);
                return;
            }
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            previewSize = chooseSize(map.getOutputSizes(SurfaceTexture.class),
                    map.getOutputSizes(ImageFormat.YUV_420_888));
            textureView.getSurfaceTexture().setDefaultBufferSize(
                    previewSize.getWidth(), previewSize.getHeight());
            imageReader = ImageReader.newInstance(previewSize.getWidth(), previewSize.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(imageListener, bgHandler);
            Log.i(TAG, "opening camera " + cameraId + " preview=" + previewSize);
            cameraManager.openCamera(cameraId, cameraStateCallback, bgHandler);
        } catch (Exception e) {
            Log.w(TAG, "openCamera failed", e);
            fail(R.string.scan_error);
        }
    }

    private static Size chooseSize(Size[] textureSizes, Size[] yuvSizes) {
        Set<Size> textureSet = new HashSet<>(Arrays.asList(textureSizes));
        Size best = null;
        for (Size size : yuvSizes) {
            if (!textureSet.contains(size) || size.getWidth() < 640 || size.getHeight() < 480) {
                continue;
            }
            if (best == null || distance(size) < distance(best)) {
                best = size;
            }
        }
        if (best != null) {
            return best;
        }
        for (Size size : textureSizes) {
            if (size.getWidth() < 640 || size.getHeight() < 480) {
                continue;
            }
            if (best == null || distance(size) < distance(best)) {
                best = size;
            }
        }
        return best != null ? best : textureSizes[0];
    }

    private static long distance(Size size) {
        return Math.abs((long) size.getWidth() * size.getHeight() - TARGET_PIXELS);
    }

    private void closeCamera() {
        if (session != null) {
            session.close();
            session = null;
        }
        if (camera != null) {
            camera.close();
            camera = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        previewSurface = null;
        previewRequest = null;
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice device) {
            camera = device;
            try {
                previewSurface = new Surface(textureView.getSurfaceTexture());
                camera.createCaptureSession(
                        Arrays.asList(previewSurface, imageReader.getSurface()),
                        sessionStateCallback, bgHandler);
            } catch (Exception e) {
                Log.w(TAG, "createCaptureSession failed", e);
                fail(R.string.scan_error);
            }
        }

        @Override
        public void onDisconnected(CameraDevice device) {
            device.close();
            camera = null;
        }

        @Override
        public void onError(CameraDevice device, int error) {
            device.close();
            camera = null;
            Log.w(TAG, "camera error " + error);
            fail(R.string.scan_error);
        }
    };

    private final CameraCaptureSession.StateCallback sessionStateCallback =
            new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession captureSession) {
                    session = captureSession;
                    try {
                        previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        previewRequest.addTarget(previewSurface);
                        previewRequest.addTarget(imageReader.getSurface());
                        applyTorch();
                        session.setRepeatingRequest(previewRequest.build(), null, bgHandler);
                        Log.i(TAG, "session configured");
                    } catch (Exception e) {
                        Log.w(TAG, "setRepeatingRequest failed", e);
                        fail(R.string.scan_error);
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession captureSession) {
                    Log.w(TAG, "session configure failed");
                    fail(R.string.scan_error);
                }
            };

    private void toggleTorch() {
        torchOn = !torchOn;
        torchButton.setActivated(torchOn);
        torchButton.setText(torchOn ? R.string.torch_on : R.string.torch_off);
        applyTorch();
        if (session != null && previewRequest != null) {
            try {
                session.setRepeatingRequest(previewRequest.build(), null, bgHandler);
            } catch (Exception ignored) {
            }
        }
    }

    private void applyTorch() {
        if (previewRequest != null) {
            previewRequest.set(CaptureRequest.FLASH_MODE,
                    torchOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        }
    }

    private final ImageReader.OnImageAvailableListener imageListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = reader.acquireLatestImage();
                    if (image == null) {
                        return;
                    }
                    try {
                        if (found) {
                            return;
                        }
                        int width = image.getWidth();
                        int height = image.getHeight();
                        byte[] yuv = extractY(image, width, height);
                        PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                                yuv, width, height, 0, 0, width, height, false);
                        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
                        Result result = new QRCodeReader().decode(bitmap, hints());
                        onFound(result.getText());
                    } catch (NotFoundException e) {
                        frameCount++;
                        if (frameCount % 30 == 1) {
                            Log.i(TAG, "no qr yet, frames=" + frameCount);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "decode frame failed", e);
                    } finally {
                        image.close();
                    }
                }
            };

    private static Map<DecodeHintType, Object> hints() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS,
                Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        return hints;
    }

    private static byte[] extractY(Image image, int width, int height) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        byte[] yuv = new byte[width * height];
        if (pixelStride == 1 && rowStride == width) {
            int count = Math.min(buffer.remaining(), width * height);
            buffer.get(yuv, 0, count);
            return yuv;
        }
        for (int row = 0; row < height; row++) {
            int rowOffset = row * rowStride;
            for (int col = 0; col < width; col++) {
                int index = rowOffset + col * pixelStride;
                yuv[row * width + col] = index < buffer.capacity() ? buffer.get(index) : 0;
            }
        }
        return yuv;
    }

    private void onFound(final String text) {
        if (found) {
            return;
        }
        found = true;
        Log.i(TAG, "qr found, text length=" + text.length());
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                vibrate();
                Intent data = new Intent();
                data.putExtra(EXTRA_RESULT, text);
                setResult(RESULT_OK, data);
                finish();
            }
        });
    }

    private void vibrate() {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(120,
                        VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(120);
            }
        } catch (Exception ignored) {
        }
    }

    private void fail(final int messageRes) {
        Log.w(TAG, "scan failed, message=" + getString(messageRes));
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(ScanActivity.this, messageRes, Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }
}
