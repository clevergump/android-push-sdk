package com.upyun.hardware;

import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class PushClient implements Camera.PreviewCallback, SurfaceHolder.Callback {

    static {
        System.loadLibrary("avutil-54");
        System.loadLibrary("swresample-1");
        System.loadLibrary("avcodec-56");
        System.loadLibrary("avformat-56");
        System.loadLibrary("swscale-3");
        System.loadLibrary("postproc-53");
        System.loadLibrary("avfilter-5");
        System.loadLibrary("avdevice-56");
        System.loadLibrary("uppush");
    }


    private static final String TAG = "PushClient";
    private SurfaceView surface;

    private Config config;
    private Camera mCamera;
    private byte[] mYuvFrameBuffer;
    private int width;
    private int height;
    private VideoEncoder videoEncoder;
    private AudioEncoder audioEncoder;
    private AudioRecord audioRecord;
    protected static boolean isPush = false;

    ExecutorService executor = Executors.newSingleThreadExecutor();

    public PushClient(SurfaceView surface) {
        this(surface, new Config.Builder().build());
    }

    public PushClient(SurfaceView surface, Config config) {
        this.surface = surface;
        this.config = config;
    }

    public void setConfig(Config config) {
        this.config = config;
    }

    private void initCamera(SurfaceHolder holder) throws IOException {

        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        holder.addCallback(this);
        switch (config.resolution) {
            case HIGH:
                width = 1280;
                height = 720;
                break;
            case NORMAL:
                width = 640;
                height = 480;
                break;
            case LOW:
                width = 320;
                height = 240;
                break;
        }

        mYuvFrameBuffer = new byte[width * height * 3 / 2];
        mCamera = getDefaultCamera(config.cameraType);
        Camera.Parameters parameters = mCamera.getParameters();
        parameters.setPreviewSize(width, height);
        int[] range = findClosestFpsRange(config.fps, parameters.getSupportedPreviewFpsRange());
        parameters.setPreviewFpsRange(range[0], range[1]);
        parameters.setPreviewFormat(ImageFormat.NV21);
        mCamera.setParameters(parameters);
        mCamera.setPreviewCallback(this);
        mCamera.addCallbackBuffer(mYuvFrameBuffer);
        mCamera.setPreviewDisplay(holder);
        if (config.orientation == Config.Orientation.VERTICAL) {
            mCamera.setDisplayOrientation(90);
        }
        mCamera.startPreview();
    }


    private void stopCamera() {
        if (mCamera != null) {
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }


    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (videoEncoder != null && isPush) {
            videoEncoder.fireVideo(data);
            camera.addCallbackBuffer(mYuvFrameBuffer);
        }
    }

    private int[] findClosestFpsRange(int expectedFps, List<int[]> fpsRanges) {
        expectedFps *= 1000;
        int[] closestRange = fpsRanges.get(0);
        int measure = Math.abs(closestRange[0] - expectedFps) + Math.abs(closestRange[1] - expectedFps);
        for (int[] range : fpsRanges) {
            if (range[0] <= expectedFps && range[1] >= expectedFps) {
                int curMeasure = Math.abs(range[0] - expectedFps) + Math.abs(range[1] - expectedFps);
                if (curMeasure < measure) {
                    closestRange = range;
                    measure = curMeasure;
                }
            }
        }
        return closestRange;
    }

    public void startPush() {
        if (isPush) {
            return;
        }
        try {
            initCamera(surface.getHolder());
        } catch (IOException e) {
            e.printStackTrace();
        }
        isPush = true;
        videoEncoder = new VideoEncoder();
        audioEncoder = new AudioEncoder();
        videoEncoder.setVideoOptions(width,
                height, config.bitRate, config.fps);
        videoEncoder.init(config.url, width, height);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (PushClient.class) {
                }
                int minBufferSize = AudioRecord.getMinBufferSize(44100,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);

                audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC, 44100,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, minBufferSize);
                audioRecord.startRecording();
                while (audioRecord != null && audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    if (!isPush) {
                        synchronized (PushClient.class) {
                            audioRecord.release();
                            audioRecord = null;
                        }
                        break;
                    }

                    byte[] buffer = new byte[minBufferSize];
                    int len = audioRecord.read(buffer, 0, minBufferSize);
                    if (0 < len) {
                        audioEncoder.fireAudio(buffer, len);
                    }
                }
            }
        });
    }

    public void stopPush() {

        if (isPush) {
            isPush = false;
            stopCamera();
            videoEncoder.stop();
            audioEncoder.stop();
            videoEncoder = null;
            audioEncoder = null;
        }
    }

    public static Camera getDefaultCamera(int position) {
        // Find the total number of cameras available
        int mNumberOfCameras = Camera.getNumberOfCameras();

        // Find the ID of the back-facing ("default") camera
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        for (int i = 0; i < mNumberOfCameras; i++) {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == position) {
                try {
                    return Camera.open(i);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        return null;
    }

    public boolean isStart() {
        return isPush;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (mCamera != null && isPush) {
            try {
                mCamera.setPreviewDisplay(holder);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {

    }
}