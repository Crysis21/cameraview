package com.google.android.cameraview;

import static android.content.ContentValues.TAG;

import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;

abstract class PreviewImpl {

    interface Callback {
        void onSurfaceChanged();
    }

    private Callback mCallback;

    private int mWidth;
    private int mHeight;

    protected int mTrueWidth;
    protected int mTrueHeight;
    protected int mDisplayOrientation;

    void setCallback(Callback callback) {
        mCallback = callback;
    }

    abstract Surface getSurface();

    abstract View getView();

    abstract Class getOutputClass();

    public void setDisplayOrientation(int displayOrientation) {
        mDisplayOrientation = displayOrientation;
    }

    abstract boolean isReady();

    protected void dispatchSurfaceChanged() {
        mCallback.onSurfaceChanged();
    }

    SurfaceHolder getSurfaceHolder() {
        return null;
    }

    SurfaceTexture getSurfaceTexture() {
        return null;
    }

    void setSize(int width, int height) {
        mWidth = width;
        mHeight = height;

        // Refresh true preview size to adjust scaling
        setTruePreviewSize(width, height);
    }

    int getWidth() {
        return mWidth;
    }

    int getHeight() {
        return mHeight;
    }


    void setTruePreviewSize(int width, int height) {
        this.mTrueWidth = width;
        this.mTrueHeight = height;
//        if (mDisplayOrientation == 90
//                || mDisplayOrientation == 270) {
//            mTrueHeight = mTrueWidth;
//            mTrueWidth = mTrueHeight;
//        }
        Log.d(TAG, "setPreviewSize [" + width + " x " + height + "]" + " orientation="
                + mDisplayOrientation);
        if (width != 0 && height != 0) {
            AspectRatio aspectRatio = AspectRatio.of(width, height);
            int targetHeight = (int) (getView().getWidth() * aspectRatio.toFloat());
            float scaleY;
            if (getView().getHeight() > 0) {
                scaleY = (float) targetHeight / (float) getView().getHeight();
            } else {
                scaleY = 1;
            }

            if (scaleY > 1) {
                getView().setScaleX(1);
                getView().setScaleY(scaleY);
            } else {
                getView().setScaleX(1 / scaleY);
                getView().setScaleY(1);
            }
        }
    }

    int getTrueWidth() {
        return mTrueWidth;
    }

    int getTrueHeight() {
        return mTrueHeight;
    }

}
