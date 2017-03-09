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

package com.google.android.cameraview.demo;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.cameraview.AspectRatio;
import com.google.android.cameraview.Size;

public class PreviewActivity extends Activity {

    ImageView imageView;

    TextView nativeCaptureResolution;

    TextView actualResolution;

    TextView approxUncompressedSize;

    TextView captureLatency;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview);

        imageView = (ImageView) findViewById(R.id.image);
        nativeCaptureResolution= (TextView) findViewById(R.id.nativeCaptureResolution);
        actualResolution= (TextView) findViewById(R.id.actualResolution);
        approxUncompressedSize= (TextView) findViewById(R.id.approxUncompressedSize);
        captureLatency= (TextView) findViewById(R.id.captureLatency);
        Bitmap bitmap = ResultHolder.getImage();
        if (bitmap == null) {
            finish();
            return;
        }

        imageView.setImageBitmap(bitmap);

        Size captureSize = ResultHolder.getNativeCaptureSize();
        if (captureSize != null) {
            // Native sizes are landscape, hardcode flip because demo app forced to portrait.
            AspectRatio aspectRatio = AspectRatio.of(captureSize.getHeight(), captureSize.getWidth());
            nativeCaptureResolution.setText(captureSize.getHeight() + " x " + captureSize.getWidth() + " (" + aspectRatio.toString() + ")");
        }

        actualResolution.setText(bitmap.getWidth() + " x " + bitmap.getHeight());
        approxUncompressedSize.setText(getApproximateFileMegabytes(bitmap) + "MB");
        captureLatency.setText(ResultHolder.getTimeToCallback() + " milliseconds");
    }

    private static float getApproximateFileMegabytes(Bitmap bitmap) {
        return (bitmap.getRowBytes() * bitmap.getHeight()) / 1024 / 1024;
    }

}
