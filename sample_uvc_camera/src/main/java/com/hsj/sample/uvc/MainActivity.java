package com.hsj.sample.uvc;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.hsj.camera.DeviceFilter;
import com.hsj.camera.IFrameCallback;
import com.hsj.camera.Size;
import com.hsj.camera.USBMonitor;
import com.hsj.camera.UVCCamera;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * @Author:hsj
 * @Date:2020-06-22 16:50
 * @Class:MainActivity
 * @Desc:Sample of UVCCamera
 */
public final class MainActivity extends AppCompatActivity implements Handler.Callback, SurfaceHolder.Callback {

    private static final String TAG = "MainActivity";
    //TODO Set your usb camera productId
    private static final int PRODUCT_ID = 12384;
    //TODO Set your usb camera display width and height
    private static int PREVIEW_WIDTH = 1280;
    private static int PREVIEW_HEIGHT = 720;

    private static final int CAMERA_CREATE = 1;
    private static final int CAMERA_PREVIEW = 2;
    private static final int CAMERA_START = 3;
    private static final int CAMERA_STOP = 4;
    private static final int CAMERA_DESTROY = 5;

    private int index = 0;
    private Context context;
    private USBMonitor mUSBMonitor;
    private Handler cameraHandler;
    private HandlerThread cameraThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SurfaceView sv = findViewById(R.id.sv);
        sv.getHolder().addCallback(this);
        context = getApplicationContext();

        this.cameraThread = new HandlerThread("camera_uvc_thread");
        this.cameraThread.start();
        this.cameraHandler = new Handler(cameraThread.getLooper(), this);

        if (hasPermissions(Manifest.permission.CAMERA)) {
            createUsbMonitor();
        }
    }

    private boolean hasPermissions(String... permissions) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        if (permissions == null || permissions.length == 0) return true;
        boolean allGranted = true;
        for (String permission : permissions) {
            if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                ActivityCompat.requestPermissions(this, permissions, 0);
            }
        }
        return allGranted;
    }

    private void createUsbMonitor() {
        this.mUSBMonitor = new USBMonitor(context, dcl);
        this.mUSBMonitor.register();
        showSingleChoiceDialog(false);
    }

//==========================================Menu====================================================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.item_camera) {
            showSingleChoiceDialog(true);
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSingleChoiceDialog(boolean show) {
        if (mUSBMonitor == null) return;
        List<UsbDevice> deviceList = mUSBMonitor.getDeviceList();
        if (deviceList.size() == 0) return;
        //Open first UsbDevice by default
        if (!show) {
            mUSBMonitor.requestPermission(deviceList.get(index));
            return;
        }
        String[] items = new String[deviceList.size()];
        for (int i = 0; i < deviceList.size(); ++i) {
            UsbDevice device = deviceList.get(i);
            items[i] = device.getProductName() + " -> " + device.getDeviceName();
        }
        AlertDialog.Builder selectDialog = new AlertDialog.Builder(this);
        selectDialog.setTitle(R.string.select_camera);
        final int lastSelected = index;
        selectDialog.setSingleChoiceItems(items, index, (dialog, which) -> {
            index = which;
        });
        selectDialog.setPositiveButton(R.string.btn_confirm, (dialog, which) -> {
            if (mUSBMonitor != null && lastSelected != index) {
                cameraHandler.obtainMessage(CAMERA_DESTROY).sendToTarget();
                mUSBMonitor.requestPermission(deviceList.get(index));
            }
        });
        selectDialog.show();
    }

//==========================================Button Click============================================

    public void startPreview(View view) {
        cameraHandler.obtainMessage(CAMERA_START).sendToTarget();
    }

    public void stopPreview(View view) {
        cameraHandler.obtainMessage(CAMERA_STOP).sendToTarget();
    }

    public void destroyCamera(View view) {
        cameraHandler.obtainMessage(CAMERA_DESTROY).sendToTarget();
    }

//=========================================Activity=================================================

    @Override
    protected void onStart() {
        super.onStart();
        if (mUSBMonitor != null && !mUSBMonitor.isRegistered()) {
            mUSBMonitor.register();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mUSBMonitor != null && mUSBMonitor.isRegistered()) {
            mUSBMonitor.unregister();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mUSBMonitor != null) {
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean hasAllPermissions = true;
        for (int granted : grantResults) {
            hasAllPermissions &= (granted == PackageManager.PERMISSION_GRANTED);
        }
        if (hasAllPermissions) {
            createUsbMonitor();
        }
    }

//===================================Surface========================================================

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        cameraHandler.obtainMessage(CAMERA_PREVIEW, holder.getSurface()).sendToTarget();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.e(TAG, "->surfaceDestroyed");
        cameraHandler.obtainMessage(CAMERA_DESTROY, holder.getSurface()).sendToTarget();
    }

//====================================UsbDevice Status==============================================

    private final USBMonitor.OnDeviceConnectListener dcl = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(UsbDevice device) {
            Log.i(TAG, "Usb->onAttach->" + device.getProductId());
        }

        @Override
        public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
            Log.i(TAG, "Usb->onConnect->" + device.getProductId());
            cameraHandler.obtainMessage(CAMERA_CREATE, ctrlBlock).sendToTarget();
        }

        @Override
        public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
            Log.i(TAG, "Usb->onDisconnect->" + device.getProductId());
        }

        @Override
        public void onCancel(UsbDevice device) {
            Log.i(TAG, "Usb->onCancel->" + device.getProductId());
        }

        @Override
        public void onDetach(UsbDevice device) {
            Log.i(TAG, "Usb->onDetach->" + device.getProductId());
        }
    };

//=====================================UVCCamera Action=============================================

    private boolean isStart;
    private Surface surface;
    private UVCCamera camera;

    @Override
    public boolean handleMessage(@NonNull Message msg) {
        switch (msg.what) {
            case CAMERA_CREATE:
                initCamera((USBMonitor.UsbControlBlock) msg.obj);
                break;
            case CAMERA_PREVIEW:
                setSurface((Surface) msg.obj);
                break;
            case CAMERA_START:
                startCamera();
                break;
            case CAMERA_STOP:
                stopCamera();
                break;
            case CAMERA_DESTROY:
                destroyCamera();
                break;
            default:
                break;
        }
        return true;
    }

    private void initCamera(@NonNull USBMonitor.UsbControlBlock block) {
        long t = System.currentTimeMillis();
        if (camera != null) {
            destroyCamera();
        }
        try {
            camera = new UVCCamera();
            camera.open(block);
            camera.setPreviewRotate(UVCCamera.PREVIEW_ROTATE.ROTATE_90);
            camera.setPreviewFlip(UVCCamera.PREVIEW_FLIP.FLIP_H);
            checkSupportSize(camera);
            camera.setPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT,
                    UVCCamera.FRAME_FORMAT_MJPEG, 1.0f);
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            e.printStackTrace();
            camera.destroy();
            camera = null;
        }
        Log.i(TAG, "camera create time=" + (System.currentTimeMillis() - t));
        if (surface != null) {
            startCamera();
        }
    }

    private void checkSupportSize(UVCCamera mCamera) {
        List<Size> sizes = mCamera.getSupportedSizeList();
        //Most UsbCamera support 640x480
        //A few UsbCamera may fail to obtain the supported resolution
        if (sizes == null || sizes.size() == 0) return;
        Log.i(TAG, mCamera.getSupportedSize());
        boolean isSupport = false;
        for (Size size : sizes) {
            if (size.width == PREVIEW_WIDTH && size.height == PREVIEW_HEIGHT) {
                isSupport = true;
                break;
            }
        }
        if (!isSupport) {
            //Use intermediate support size
            Size size = sizes.get(sizes.size() / 2);
            PREVIEW_WIDTH = size.width;
            PREVIEW_HEIGHT = size.height;
        }
        Log.i(TAG, String.format("SupportSize->with=%d,height=%d", PREVIEW_WIDTH, PREVIEW_HEIGHT));
    }

    private void setSurface(Surface surface) {
        this.surface = surface;
        if (isStart) {
            stopCamera();
            startCamera();
        } else if (camera != null) {
            startCamera();
        }
    }

    private void startCamera() {
        long t = System.currentTimeMillis();
        if (!isStart && camera != null) {
            isStart = true;
            if (surface != null) {
                Log.i(TAG, "setPreviewDisplay()");
                camera.setPreviewDisplay(surface);
            }
            //camera.setFrameCallback(frame -> {}, UVCCamera.PIXEL_FORMAT_NV21);
            camera.startPreview();
        }
        Log.i(TAG, "camera start time=" + (System.currentTimeMillis() - t));
    }

    private void stopCamera() {
        long t = System.currentTimeMillis();
        if (isStart && camera != null) {
            isStart = false;
            camera.stopPreview();
        }
        Log.i(TAG, "camera stop time=" + (System.currentTimeMillis() - t));
    }

    private void destroyCamera() {
        long t = System.currentTimeMillis();
        stopCamera();
        if (camera != null) {
            camera.destroy();
            camera = null;
        }
        Log.i(TAG, "camera destroy time=" + (System.currentTimeMillis() - t));
    }

}


