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

import static com.google.android.cameraview.Constants.FLASH_AUTO;
import static com.google.android.cameraview.Constants.FLASH_OFF;
import static com.google.android.cameraview.Constants.FLASH_ON;
import static com.google.android.cameraview.Constants.FLASH_RED_EYE;
import static com.google.android.cameraview.Constants.FLASH_TORCH;

import android.support.annotation.IntDef;

@IntDef({FLASH_OFF, FLASH_ON, FLASH_TORCH, FLASH_AUTO, FLASH_RED_EYE})
    public @interface Flash {
    }
