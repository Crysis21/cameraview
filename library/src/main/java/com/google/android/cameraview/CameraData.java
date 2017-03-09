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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

/**
 * Created by Cristian Holdunu on 09/03/2017.
 */

public class CameraData {
    private byte[] jpegData;
    private Matrix rotateMatrix;
    private Bitmap bitmap;

    public byte[] getJpegData() {
        return jpegData;
    }

    public void setJpegData(byte[] jpegData) {
        this.jpegData = jpegData;
    }

    public Matrix getRotateMatrix() {
        return rotateMatrix;
    }

    public void setRotateMatrix(Matrix rotateMatrix) {
        this.rotateMatrix = rotateMatrix;
    }

    public Bitmap getBitmap() {
        if (bitmap==null) generateBitmap();
        return bitmap;
    }

    public void setBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
    }

    public void generateBitmap() {
        Bitmap temp = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
        if (rotateMatrix != null) {
            bitmap = Bitmap.createBitmap(temp, 0, 0, temp.getWidth(), temp.getHeight(),
                    rotateMatrix, true);
        }
        temp.recycle();
    }
}
