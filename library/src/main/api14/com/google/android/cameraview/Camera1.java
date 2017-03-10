/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.cameraview;

import static com.google.android.cameraview.Constants.FACING_BACK;
import static com.google.android.cameraview.Constants.FACING_FRONT;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.v4.util.SparseArrayCompat;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("deprecation")
class Camera1 extends CameraViewImpl {

    private static final String TAG = Camera1.class.getCanonicalName();

    private static final int INVALID_CAMERA_ID = -1;

    private static final SparseArrayCompat<String> FLASH_MODES = new SparseArrayCompat<>();
    private Size mPreviewSize;
    private Size mCaptureSize;

    static {
        FLASH_MODES.put(Constants.FLASH_OFF, Camera.Parameters.FLASH_MODE_OFF);
        FLASH_MODES.put(Constants.FLASH_ON, Camera.Parameters.FLASH_MODE_ON);
        FLASH_MODES.put(Constants.FLASH_TORCH, Camera.Parameters.FLASH_MODE_TORCH);
        FLASH_MODES.put(Constants.FLASH_AUTO, Camera.Parameters.FLASH_MODE_AUTO);
        FLASH_MODES.put(Constants.FLASH_RED_EYE, Camera.Parameters.FLASH_MODE_RED_EYE);
    }

    private int mCameraId;

    private LinkedBlockingQueue<Runnable> mQueue = new LinkedBlockingQueue<>();
    private ThreadPoolExecutor mPool;

    private Camera mCamera;

    private Camera.Parameters mCameraParameters;

    private final Camera.CameraInfo mCameraInfo = new Camera.CameraInfo();

    private boolean mAutoFocus;

    private Matrix rotateMatrix;

    @Facing
    private int mFacing;

    private int mFlash;

    private int mDisplayOrientation;

    private int cameraEye = 0;

    Camera1(Callback callback, PreviewImpl preview) {
        super(callback, preview);
        preview.setCallback(new PreviewImpl.Callback() {
            @Override
            public void onSurfaceChanged() {
                if (mCamera != null) {
                    setUpPreview();
                    adjustCameraParameters();
                }
            }
        });
    }

    private ThreadPoolExecutor getThreadPool() {
        if (mPool == null) {
            mPool = new ThreadPoolExecutor(1, 1,
                    60, TimeUnit.SECONDS, mQueue);
        }

        return (mPool);
    }

    @Override
    boolean start() {
        chooseCamera();
        openCamera();
        if (mPreview.isReady()) {
            setUpPreview();
        }
        mCamera.startPreview();
        return true;
    }

    @Override
    void stop() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.cancelAutoFocus();
        }
        releaseCamera();
    }

    @SuppressLint("NewApi")
    private void setUpPreview() {
        try {
            if (mPreview.getOutputClass() == SurfaceHolder.class) {
                mCamera.setPreviewDisplay(mPreview.getSurfaceHolder());
            } else {
                mCamera.setPreviewTexture(mPreview.getSurfaceTexture());
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    @Override
    boolean isCameraOpened() {
        return mCamera != null;
    }

    @Override
    void setFacing(int facing) {
        if (mFacing == facing) {
            return;
        }
        mFacing = facing;
        if (isCameraOpened()) {
            stop();
            start();
        }
    }

    @Override
    @Facing
    int getFacing() {
        return mFacing;
    }

    @Override
    void setAutoFocus(boolean autoFocus) {
        if (mAutoFocus == autoFocus) {
            return;
        }
        if (setAutoFocusInternal(autoFocus)) {
            mCamera.setParameters(mCameraParameters);
        }
    }

    @Override
    @Facing
    int toggleFacing() {
        int facing = getFacing();
        if (facing == FACING_BACK) {
            setFacing(FACING_FRONT);
        } else {
            setFacing(FACING_BACK);
        }
        return getFacing();
    }

    @Override
    boolean getAutoFocus() {
        if (!isCameraOpened()) {
            return mAutoFocus;
        }
        String focusMode = mCameraParameters.getFocusMode();
        return focusMode != null && focusMode.contains("continuous");
    }

    @Override
    void setFlash(int flash) {
        if (flash == mFlash) {
            return;
        }
        if (setFlashInternal(flash)) {
            mCamera.setParameters(mCameraParameters);
        }
    }

    @Override
    int getFlash() {
        return mFlash;
    }

    @Override
    int toggleFlash() {
        switch (mFlash) {
            case Constants.FLASH_AUTO:
                setFlash(Constants.FLASH_ON);
                break;
            case Constants.FLASH_OFF:
                setFlash(Constants.FLASH_AUTO);
                break;
            case Constants.FLASH_ON:
            default:
                setFlash(Constants.FLASH_OFF);
                break;
        }
        return mFlash;
    }

    @Override
    public void takePicture() {
        Log.d(TAG,
                "take picture: cameraId=" + mCameraId + " displayOrientation=" + mDisplayOrientation
                        + " cameraEye=" + cameraEye);
        if (!isCameraOpened()) {
            throw new IllegalStateException(
                    "Camera is not ready. Call start() before takePicture().");
        }
        if (getAutoFocus()) {
            mCamera.cancelAutoFocus();
            mCamera.autoFocus(new Camera.AutoFocusCallback() {
                @Override
                public void onAutoFocus(boolean success, Camera camera) {
                    takePictureInternal();
                }
            });
        } else {
            takePictureInternal();
        }
    }

    private void takePictureInternal() {
        getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    mCamera.takePicture(null, null, null, new Camera.PictureCallback() {
                        @Override
                        public void onPictureTaken(byte[] data, Camera camera) {
                            rotateMatrix = new Matrix();
                            rotateMatrix.postRotate(cameraEye);
                            camera.cancelAutoFocus();
                            CameraData cameraData = new CameraData();
                            cameraData.setJpegData(data);
                            cameraData.setRotateMatrix(rotateMatrix);
//                            cameraData.generateBitmap();
                            mCallback.onPictureTaken(cameraData);
                        }
                    });
                } catch (RuntimeException ex) {
                    ex.printStackTrace();
                    Log.e(TAG, "Take Picture Thrown Ex", ex);
                }
            }
        });
    }

    @Override
    void setDisplayOrientation(int displayOrientation) {
        mDisplayOrientation = displayOrientation;
        adjustCameraParameters();
    }

    @Override
    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    void setMeteringAndFocusAreas(@NonNull List<Camera.Area> meteringAndFocusAreas) {
        if (mCameraParameters.getSupportedFocusModes().contains(
                Camera.Parameters.FOCUS_MODE_MACRO)) {
            mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_MACRO);
        }
        if (meteringAndFocusAreas.size() > 1) {
            throw new RuntimeException("Multiple focus areas are not Supported");
        }
        if (mCameraParameters.getMaxNumFocusAreas() > 0) {
            mCameraParameters.setFocusAreas(meteringAndFocusAreas);
        }

        if (mCameraParameters.getMaxNumMeteringAreas() > 0) {
            mCameraParameters.setMeteringAreas(meteringAndFocusAreas);
        }
        mCamera.setParameters(mCameraParameters);
        mCamera.autoFocus(new Camera.AutoFocusCallback() {
            @Override
            public void onAutoFocus(boolean success, Camera camera) {
                mCamera.cancelAutoFocus();
                setAutoFocusInternal(mAutoFocus);
            }
        });
    }

    @Override
    Size getCaptureResolution() {
        if (mCameraParameters != null) {
            TreeSet<Size> sizes = new TreeSet<>();
            for (Camera.Size size : mCameraParameters.getSupportedPictureSizes()) {
                sizes.add(new Size(size.width, size.height));
            }

            TreeSet<AspectRatio> aspectRatios = findCommonAspectRatios(
                    mCameraParameters.getSupportedPreviewSizes(),
                    mCameraParameters.getSupportedPictureSizes()
            );
            AspectRatio targetRatio = aspectRatios.size() > 0 ? aspectRatios.last() : null;

            Iterator<Size> descendingSizes = sizes.descendingIterator();
            Size size;
            while (descendingSizes.hasNext() && mCaptureSize == null) {
                size = descendingSizes.next();
                if (targetRatio == null || targetRatio.matches(size)) {
                    mCaptureSize = size;
                    break;
                }
            }
        }

        return mCaptureSize;
    }

    @Override
    Size getPreviewResolution() {
        if (mCameraParameters != null) {
            TreeSet<Size> sizes = new TreeSet<>();
            for (Camera.Size size : mCameraParameters.getSupportedPreviewSizes()) {
                sizes.add(new Size(size.width, size.height));
            }

            TreeSet<AspectRatio> aspectRatios = findCommonAspectRatios(
                    mCameraParameters.getSupportedPreviewSizes(),
                    mCameraParameters.getSupportedPictureSizes()
            );
            AspectRatio targetRatio = aspectRatios.size() > 0 ? aspectRatios.last() : null;

            Iterator<Size> descendingSizes = sizes.descendingIterator();
            Size size;
            while (descendingSizes.hasNext() && mPreviewSize == null) {
                size = descendingSizes.next();
                if (targetRatio == null || targetRatio.matches(size)) {
                    mPreviewSize = size;
                    break;
                }
            }
        }

        return mPreviewSize;
    }

    private TreeSet<AspectRatio> findCommonAspectRatios(List<Camera.Size> previewSizes,
            List<Camera.Size> captureSizes) {
        Set<AspectRatio> previewAspectRatios = new HashSet<>();
        for (Camera.Size size : previewSizes) {
            if (size.width >= CameraView.Internal.screenHeight
                    && size.height >= CameraView.Internal.screenWidth) {
                previewAspectRatios.add(AspectRatio.of(size.width, size.height));
            }
        }

        Set<AspectRatio> captureAspectRatios = new HashSet<>();
        for (Camera.Size size : captureSizes) {
            captureAspectRatios.add(AspectRatio.of(size.width, size.height));
        }

        TreeSet<AspectRatio> output = new TreeSet<>();
        for (AspectRatio aspectRatio : previewAspectRatios) {
            if (captureAspectRatios.contains(aspectRatio)) {
                output.add(aspectRatio);
            }
        }

        return output;
    }


    /**
     * This rewrites {@link #mCameraId} and {@link #mCameraInfo}.
     */
    private void chooseCamera() {
        for (int i = 0, count = Camera.getNumberOfCameras(); i < count; i++) {
            Camera.getCameraInfo(i, mCameraInfo);
            if (mCameraInfo.facing == mFacing) {
                mCameraId = i;
                return;
            }
        }
        mCameraId = INVALID_CAMERA_ID;
    }

    private void openCamera() {
        if (mCamera != null) {
            releaseCamera();
        }
        mCamera = Camera.open(mCameraId);
        mCameraParameters = mCamera.getParameters();

        adjustCameraParameters();
        mCamera.setDisplayOrientation(calculateCameraRotation(mDisplayOrientation));
        mCallback.onCameraOpened();
    }

    private void adjustCameraParameters() {
        if (mCamera == null || mCameraParameters == null) return;
        cameraEye = calculateCameraRotation(mDisplayOrientation) + (mFacing == FACING_FRONT
                ? 180 : 0);

        mPreview.setTruePreviewSize(
                getPreviewResolution().getWidth(),
                getPreviewResolution().getHeight()
        );

        mCameraParameters.setPreviewSize(
                getPreviewResolution().getWidth(),
                getPreviewResolution().getHeight()
        );

        mCameraParameters.setPictureSize(
                getCaptureResolution().getWidth(),
                getCaptureResolution().getHeight()
        );

        setFlash(mFlash);
        Log.d(TAG, "adjustCameraParams preview[" + getPreviewResolution().getWidth() + ", "
                + getPreviewResolution().getHeight() + "]");
        mCamera.setParameters(mCameraParameters);
        mCamera.setDisplayOrientation(calculateCameraRotation(mDisplayOrientation));

    }

    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.release();
            mCamera = null;
            mCameraParameters = null;
            mPreviewSize = null;
            mCaptureSize = null;
            mCallback.onCameraClosed();
        }
    }

    private int calculateCameraRotation(int rotation) {
        if (mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (360 - (mCameraInfo.orientation + rotation) % 360) % 360;
        } else {
            return (mCameraInfo.orientation - rotation + 360) % 360;
        }
    }

    private boolean setAutoFocusInternal(boolean autoFocus) {
        mAutoFocus = autoFocus;
        if (isCameraOpened()) {
            final List<String> modes = mCameraParameters.getSupportedFocusModes();
            if (autoFocus && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (autoFocus && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } else if (autoFocus && modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            } else if (modes.contains(Camera.Parameters.FOCUS_MODE_FIXED)) {
                mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED);
            } else if (modes.contains(Camera.Parameters.FOCUS_MODE_INFINITY)) {
                mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);
            } else {
                mCameraParameters.setFocusMode(modes.get(0));
            }
            return true;
        } else {
            return false;
        }
    }

    private boolean setFlashInternal(int flash) {
        if (isCameraOpened()) {
            List<String> modes = mCameraParameters.getSupportedFlashModes();
            String mode = FLASH_MODES.get(flash);
            if (modes != null && modes.contains(mode)) {
                mCameraParameters.setFlashMode(mode);
                mFlash = flash;
                return true;
            }
            String currentMode = FLASH_MODES.get(mFlash);
            if (modes == null || !modes.contains(currentMode)) {
                mCameraParameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                mFlash = Constants.FLASH_OFF;
                return true;
            }
            return false;
        } else {
            mFlash = flash;
            return false;
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private void disableShutterSound() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(mCameraId, info);
        if (info.canDisableShutterSound && mCamera != null) {
            mCamera.enableShutterSound(false);
        }
    }

}
