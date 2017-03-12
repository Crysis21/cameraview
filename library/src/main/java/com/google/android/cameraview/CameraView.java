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
import static com.google.android.cameraview.Constants.FLASH_AUTO;
import static com.google.android.cameraview.Constants.FLASH_OFF;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Build;
import android.os.HandlerThread;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.v4.os.ParcelableCompat;
import android.support.v4.os.ParcelableCompatCreatorCallbacks;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

public class CameraView extends FrameLayout {

    private static final String TAG = CameraView.class.getCanonicalName();

    static class Internal {
        static final int screenWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
        static final int screenHeight = Resources.getSystem().getDisplayMetrics().heightPixels;
    }

    private CameraViewImpl mImpl;
    private final CallbackBridge mCallbacks;
    private boolean mAdjustViewBounds;
    private final DisplayOrientationDetector mDisplayOrientationDetector;
    private PreviewImpl preview;
    private HandlerThread handlerThread;

    public CameraView(Context context) {
        this(context, null);
    }

    public CameraView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    @SuppressWarnings("WrongConstant")
    public CameraView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        handlerThread = new HandlerThread("camera_thread");
        handlerThread.start();

        preview = createPreviewImpl(context);
        mCallbacks = new CallbackBridge();
//        if (Build.VERSION.SDK_INT < 23) {
            mImpl = new Camera1(mCallbacks, preview);
//        } else {
//            mImpl = new Camera2Api23(mCallbacks, preview, context);
//        }

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.CameraView,
                defStyleAttr,
                R.style.Widget_CameraView);
        mAdjustViewBounds = a.getBoolean(R.styleable.CameraView_android_adjustViewBounds,
                false);
        setFacing(a.getInt(R.styleable.CameraView_facing, FACING_BACK));
        String aspectRatio = a.getString(R.styleable.CameraView_aspectRatio);
        setAutoFocus(a.getBoolean(R.styleable.CameraView_autoFocus, true));
        setFlash(a.getInt(R.styleable.CameraView_flash, FLASH_AUTO));
        a.recycle();

        mDisplayOrientationDetector = new DisplayOrientationDetector(context) {
            @Override
            public void onDisplayOrientationChanged(int displayOrientation) {
                mImpl.setDisplayOrientation(displayOrientation);
                preview.setDisplayOrientation(displayOrientation);
            }
        };

        setTouchToFocus();
    }

    @NonNull
    private PreviewImpl createPreviewImpl(Context context) {
        PreviewImpl preview = new TextureViewPreview(context, this);
        return preview;
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    private void setTouchToFocus() {
        if (Build.VERSION.SDK_INT > 14) {
            setOnTouchListener(new OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    int size = Math.min(getHeight(), getWidth());
                    float radius = size / 2.0f;
                    float delta = (getHeight() - getWidth()) / 2;
                    float eX = event.getX() - radius;
                    float eY = event.getY() - radius - delta;
                    float vector = (float) Math.sqrt(eX * eX + eY * eY);
                    if (vector < radius) {
                        int rectXMiddle = (int) (1000 * eX / (getWidth() / 2));
                        int rectYMiddle = (int) (1000 * eX / (getHeight() / 2));
                        List<Camera.Area> meteringAreas = new ArrayList<Camera.Area>();
                        int halfAreaSide = 50;
                        Rect areaRect = new Rect(Math.max(rectXMiddle - halfAreaSide, -1000),
                                Math.max(rectYMiddle - halfAreaSide, -1000),
                                Math.min(rectXMiddle + halfAreaSide, 1000),
                                Math.min(rectYMiddle + halfAreaSide, 1000));
                        meteringAreas.add(new Camera.Area(areaRect, 1000));
                        mImpl.setMeteringAndFocusAreas(meteringAreas);
                        Log.d(TAG,
                                event.getX() + " " + event.getY() + " size:" + getWidth() + "x"
                                        + getHeight() + " Rect: " + areaRect);
                    }
                    return false;
                }
            });

        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mDisplayOrientationDetector.enable(ViewCompat2.getDisplay(this));
    }

    @Override
    protected void onDetachedFromWindow() {
        mDisplayOrientationDetector.disable();
        super.onDetachedFromWindow();
    }

    public Size getPreviewSize() {
        return mImpl != null ? mImpl.getPreviewResolution() : null;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mAdjustViewBounds) {
            if (!isCameraOpened()) {
                mCallbacks.reserveRequestLayoutOnOpen();
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                return;
            }

            Size previewSize = getPreviewSize();
            if (getLayoutParams().width == LayoutParams.WRAP_CONTENT) {
                int height = MeasureSpec.getSize(heightMeasureSpec);
                float ratio = (float) height / (float) previewSize.getWidth();
                int width = (int) (previewSize.getHeight() * ratio);
                super.onMeasure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        heightMeasureSpec
                );
                return;
            } else if (getLayoutParams().height == LayoutParams.WRAP_CONTENT) {
                int width = MeasureSpec.getSize(widthMeasureSpec);
                float ratio = (float) width / (float) previewSize.getHeight();
                int height = (int) (previewSize.getWidth() * ratio);
                super.onMeasure(
                        widthMeasureSpec,
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
                );
                return;
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    public void start() {
        if (!mImpl.start()) {
            // Camera2 uses legacy hardware layer; fall back to Camera1
            mImpl = new Camera1(mCallbacks, createPreviewImpl(getContext()));
            mImpl.start();
        }
    }

    public void stop() {
        mImpl.stop();
    }

    public boolean isCameraOpened() {
        return mImpl.isCameraOpened();
    }

    public void addCallback(@NonNull CameraListener cameraListener) {
        mCallbacks.add(cameraListener);
    }

    public void removeCallback(@NonNull CameraListener cameraListener) {
        mCallbacks.remove(cameraListener);
    }

    public void setAdjustViewBounds(boolean adjustViewBounds) {
        if (mAdjustViewBounds != adjustViewBounds) {
            mAdjustViewBounds = adjustViewBounds;
            requestLayout();
        }
    }

    public boolean getAdjustViewBounds() {
        return mAdjustViewBounds;
    }


    public void setFacing(@Facing int facing) {
        mImpl.setFacing(facing);
    }

    @Facing
    public int getFacing() {
        //noinspection WrongConstant
        return mImpl.getFacing();
    }

    @Facing
    public int toggleFacing() {
        if (mImpl == null) return FACING_BACK;
        return mImpl.toggleFacing();
    }

    public void setAutoFocus(boolean autoFocus) {
        mImpl.setAutoFocus(autoFocus);
    }

    public boolean getAutoFocus() {
        return mImpl.getAutoFocus();
    }

    public void setFlash(@Flash int flash) {
        mImpl.setFlash(flash);
    }

    @Flash
    public int getFlash() {
        //noinspection WrongConstant
        return mImpl.getFlash();
    }


    @Flash
    public int toggleFlash() {
        if (mImpl == null) return FLASH_OFF;
        return mImpl.toggleFlash();
    }


    public void takePicture() {
        mImpl.takePicture();
    }

    private class CallbackBridge implements CameraViewImpl.Callback {

        private final ArrayList<CameraListener> mCameraListeners = new ArrayList<>();

        private boolean mRequestLayoutOnOpen;

        CallbackBridge() {
        }

        public void add(CameraListener cameraListener) {
            mCameraListeners.add(cameraListener);
        }

        public void remove(CameraListener cameraListener) {
            mCameraListeners.remove(cameraListener);
        }

        @Override
        public void onCameraOpened() {
            if (mRequestLayoutOnOpen) {
                mRequestLayoutOnOpen = false;
                requestLayout();
            }
            for (CameraListener cameraListener : mCameraListeners) {
                cameraListener.onCameraOpened(CameraView.this);
            }
        }

        @Override
        public void onCameraClosed() {
            for (CameraListener cameraListener : mCameraListeners) {
                cameraListener.onCameraClosed(CameraView.this);
            }
        }

        @Override
        public void onCameraFailed() {

        }

        @Override
        public void onPictureTaken(CameraData cameraData) {
            for (CameraListener cameraListener : mCameraListeners) {
                cameraListener.onPictureTaken(CameraView.this, cameraData);
            }
        }

        public void reserveRequestLayoutOnOpen() {
            mRequestLayoutOnOpen = true;
        }
    }

    protected static class SavedState extends BaseSavedState {

        @Facing
        int facing;

        AspectRatio ratio;

        boolean autoFocus;

        @Flash
        int flash;

        @SuppressWarnings("WrongConstant")
        public SavedState(Parcel source, ClassLoader loader) {
            super(source);
            facing = source.readInt();
            ratio = source.readParcelable(loader);
            autoFocus = source.readByte() != 0;
            flash = source.readInt();
        }

        public SavedState(Parcelable superState) {
            super(superState);
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(facing);
            out.writeParcelable(ratio, 0);
            out.writeByte((byte) (autoFocus ? 1 : 0));
            out.writeInt(flash);
        }

        public static final Parcelable.Creator<SavedState> CREATOR
                = ParcelableCompat.newCreator(new ParcelableCompatCreatorCallbacks<SavedState>() {

            @Override
            public SavedState createFromParcel(Parcel in, ClassLoader loader) {
                return new SavedState(in, loader);
            }

            @Override
            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }

        });

    }

}

