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

import android.hardware.Camera;

import java.util.List;

abstract class CameraViewImpl {

    protected final Callback mCallback;

    protected final PreviewImpl mPreview;

    CameraViewImpl(Callback callback, PreviewImpl preview) {
        mCallback = callback;
        mPreview = preview;
    }

    abstract boolean start();

    abstract void stop();

    abstract boolean isCameraOpened();

    abstract void setFacing(int facing);

    @Facing
    abstract int getFacing();

    abstract void setAutoFocus(boolean autoFocus);

    @Facing
    abstract int toggleFacing();

    abstract boolean getAutoFocus();

    abstract void setFlash(int flash);

    abstract int getFlash();

    @Flash
    abstract int toggleFlash();

    abstract void takePicture();

    abstract void setDisplayOrientation(int displayOrientation);

    abstract void setMeteringAndFocusAreas(List<Camera.Area> meteringAndFocusAreas);

    abstract Size getCaptureResolution();

    abstract Size getPreviewResolution();

    interface Callback {

        void onCameraOpened();

        void onCameraClosed();

        void onCameraFailed();

        void onPictureTaken(CameraData cameraData);

    }

}
