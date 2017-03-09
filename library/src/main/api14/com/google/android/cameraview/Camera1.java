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
import android.graphics.SurfaceTexture;
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
import java.util.SortedSet;
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

    Camera mCamera;

    private Camera.Parameters mCameraParameters;

    private final Camera.CameraInfo mCameraInfo = new Camera.CameraInfo();

    private final SizeMap mPreviewSizes = new SizeMap();

    private final SizeMap mPictureSizes = new SizeMap();

    private AspectRatio mAspectRatio;

    private boolean mShowingPreview;

    private boolean mAutoFocus;

    private Matrix rotateMatrix;

    @Facing
    private int mFacing;

    private int mFlash;

    private int mDisplayOrientation;

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
        mShowingPreview = true;
        mCamera.startPreview();
        return true;
    }

    @Override
    void stop() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.cancelAutoFocus();
        }
        mShowingPreview = false;
        releaseCamera();
    }

    // Suppresses Camera#setPreviewTexture
    @SuppressLint("NewApi")
    void setUpPreview() {
        try {
            if (mPreview.getOutputClass() == SurfaceHolder.class) {
                final boolean needsToStopPreview = mShowingPreview && Build.VERSION.SDK_INT < 14;
                if (needsToStopPreview) {
                    mCamera.stopPreview();
                }
                mCamera.setPreviewDisplay(mPreview.getSurfaceHolder());
                if (needsToStopPreview) {
                    mCamera.startPreview();
                }
            } else {
                mCamera.setPreviewTexture((SurfaceTexture) mPreview.getSurfaceTexture());
            }
        } catch (IOException e) {
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
                setFacing(Constants.FLASH_OFF);
                break;
        }
        return mFlash;
    }

    @Override
    void takePicture() {
        rotateMatrix = new Matrix();
        rotateMatrix.postRotate(cameraEye, 0.5f, 0.5f);
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

    void takePictureInternal() {
        getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    mCamera.takePicture(null, null, null, new Camera.PictureCallback() {
                        @Override
                        public void onPictureTaken(byte[] data, Camera camera) {
                            camera.cancelAutoFocus();
                            mCallback.onPictureTaken(data, rotateMatrix);
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
        if (mCaptureSize == null && mCameraParameters != null) {
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
        if (mPreviewSize == null && mCameraParameters != null) {
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
        // Supported preview sizes
        mPreviewSizes.clear();
        for (Camera.Size size : mCameraParameters.getSupportedPreviewSizes()) {
            mPreviewSizes.add(new Size(size.width, size.height));
        }
        // Supported picture sizes;
        mPictureSizes.clear();
        for (Camera.Size size : mCameraParameters.getSupportedPictureSizes()) {
            mPictureSizes.add(new Size(size.width, size.height));
        }
        // AspectRatio
        if (mAspectRatio == null) {
            mAspectRatio = Constants.DEFAULT_ASPECT_RATIO;
        }
        adjustCameraParameters();

        mCamera.setDisplayOrientation(
                calculateCameraRotation(mDisplayOrientation)
        );

        mCallback.onCameraOpened();
    }

    private AspectRatio chooseAspectRatio() {
        AspectRatio r = null;
        for (AspectRatio ratio : mPreviewSizes.ratios()) {
            r = ratio;
            if (ratio.equals(Constants.DEFAULT_ASPECT_RATIO)) {
                return ratio;
            }
        }
        return r;
    }

    int cameraEye = 0;

    void adjustCameraParameters() {
        mPreview.setTruePreviewSize(
                getPreviewResolution().getWidth(),
                getPreviewResolution().getHeight()
        );

        SortedSet<Size> sizes = mPreviewSizes.sizes(mAspectRatio);
        if (sizes == null) { // Not supported
            mAspectRatio = chooseAspectRatio();
            sizes = mPreviewSizes.sizes(mAspectRatio);
        }
        Size size = chooseOptimalSize(sizes);
        final Camera.Size currentSize = mCameraParameters.getPictureSize();
        if (currentSize.width != size.getWidth() || currentSize.height != size.getHeight()) {
            // Largest picture size in this ratio
            final Size pictureSize = mPictureSizes.sizes(mAspectRatio).last();
            if (mShowingPreview) {
                mCamera.stopPreview();
            }

            mCameraParameters.setPreviewSize(
                    getPreviewResolution().getWidth(),
                    getPreviewResolution().getHeight()
            );

            mCameraParameters.setPictureSize(pictureSize.getWidth(), pictureSize.getHeight());
            cameraEye = calculateCameraRotation(mDisplayOrientation) + (mFacing == FACING_FRONT ? 180 : 0);

            mCameraParameters.setRotation(cameraEye);
            setAutoFocusInternal(mAutoFocus);
            setFlashInternal(mFlash);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                disableShutterSound();
            }
            mCamera.setParameters(mCameraParameters);
            if (mShowingPreview) {
                mCamera.startPreview();
            }
        }
//        CameraUtil.setCameraDisplayOrientation(mPreview.getView().getContext(), mCameraId, mCamera);

    }

    @SuppressWarnings("SuspiciousNameCombination")
    private Size chooseOptimalSize(SortedSet<Size> sizes) {
        if (!mPreview.isReady()) { // Not yet laid out
            return sizes.first(); // Return the smallest size
        }
        int desiredWidth;
        int desiredHeight;
        final int surfaceWidth = mPreview.getWidth();
        final int surfaceHeight = mPreview.getHeight();
        if (mDisplayOrientation == 90 || mDisplayOrientation == 270) {
            desiredWidth = surfaceHeight;
            desiredHeight = surfaceWidth;
        } else {
            desiredWidth = surfaceWidth;
            desiredHeight = surfaceHeight;
        }
        Size result = null;
        for (Size size : sizes) { // Iterate from small to large
            if (desiredWidth <= size.getWidth() && desiredHeight <= size.getHeight()) {
                return size;

            }
            result = size;
        }
        return result;
    }

    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.release();
            mCamera = null;
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

    private int calcCameraRotation(int rotation) {
        if (mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (360 - (mCameraInfo.orientation + rotation) % 360) % 360;
        } else {  // back-facing
            return (mCameraInfo.orientation - rotation + 360) % 360;
        }
//        return rotation;
    }

    /**
     * @return {@code true} if {@link #mCameraParameters} was modified.
     */
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

    /**
     * @return {@code true} if {@link #mCameraParameters} was modified.
     */
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
        if (info.canDisableShutterSound) {
            mCamera.enableShutterSound(false);
        }
    }

}
