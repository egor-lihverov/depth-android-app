///*
// * Copyright 2025 The Google AI Edge Authors. All Rights Reserved.
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *       http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//
//package com.google.ai.edge.examples.image_segmentation.view
//
//import android.graphics.Bitmap
//import android.graphics.Matrix
//import androidx.camera.core.CameraSelector
//import androidx.compose.foundation.Canvas
//import androidx.compose.runtime.Composable
//import androidx.compose.runtime.remember
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.graphics.asImageBitmap
//import androidx.compose.ui.layout.ContentScale
//import androidx.compose.ui.platform.LocalContext
//import androidx.compose.ui.platform.LocalDensity
//import androidx.compose.ui.unit.dp
//import coil3.asDrawable
//import coil3.compose.AsyncImage
//import coil3.imageLoader
//import coil3.request.SuccessResult
//import coil3.request.ImageRequest
//import com.google.ai.edge.examples.image_segmentation.OverlayInfo
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.withContext
//
///**
// * Composable that renders an original bitmap using getPixels approach,
// * similar to how DepthOverlay renders depth data.
// */
//@Composable
//fun OriginalImageOverlay(
//    bitmap: android.graphics.Bitmap?,
//    modifier: Modifier = Modifier,
//    lensFacing: Int = CameraSelector.LENS_FACING_BACK,
//    contentScale: ContentScale = ContentScale.Crop,
//) {
//    val context = LocalContext.current
//    val density = LocalDensity.current
//
//    // Convert to ARGB_8888 format for consistent pixel extraction
//    val processedBitmap = remember(bitmap) {
//        bitmap?.let {
//            if (it.config == android.graphics.Bitmap.Config.ARGB_8888) {
//                it
//            } else {
//                it.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
//            }
//        }
//    }
//
//    processedBitmap?.let { bmp ->
//        // Get the actual dimensions from the bitmap
//        val bmpWidth = bmp.width
//        val bmpHeight = bmp.height
//
//        Canvas(modifier = modifier) {
//            val imageWidth: Float = size.width
//            val imageHeight: Float = size.height
//
//            // Calculate scale to fit the image while maintaining aspect ratio
//            val scaleX = imageWidth / bmpWidth
//            val scaleY = imageHeight / bmpHeight
//            val scale = when (contentScale) {
//                ContentScale.Crop -> minOf(scaleX, scaleY)
//                ContentScale.Fit -> minOf(scaleX, scaleY)
//                else -> minOf(scaleX, scaleY)
//            }
//
//            val scaledWidth = bmpWidth * scale
//            val scaledHeight = bmpHeight * scale
//
//            // Center the image
//            val offsetX = (imageWidth - scaledWidth) / 2
//            val offsetY = (imageHeight - scaledHeight) / 2
//
//            // Create a matrix for scaling and positioning
//            val matrix = Matrix().apply {
//                preScale(scale, scale)
//                preTranslate(offsetX, offsetY)
//
//                // Flip horizontally for front camera
//                if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
//                    preScale(-1f, 1f)
//                    preTranslate(-imageWidth, 0f)
//                }
//            }
//
//            // Draw the bitmap with the transformation matrix
//            drawImage(
//                image = bmp.asImageBitmap(),
//                matrix = matrix
//            )
//        }
//    }
//}
//
///**
// * Alternative implementation that extracts pixels manually using getPixels.
// * This matches the same approach used in DepthHelper for consistency.
// */
//@Composable
//fun OriginalImageOverlayWithPixels(
//    bitmap: android.graphics.Bitmap?,
//    modifier: Modifier = Modifier,
//    lensFacing: Int = CameraSelector.LENS_FACING_BACK,
//) {
//    val context = LocalContext.current
//
//    val processedBitmap = remember(bitmap) {
//        bitmap?.let {
//            if (it.config == android.graphics.Bitmap.Config.ARGB_8888) {
//                it
//            } else {
//                it.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
//            }
//        }
//    }
//
//    processedBitmap?.let { bmp ->
//        Canvas(modifier = modifier) {
//            val imageWidth: Float = size.width
//            val imageHeight: Float = size.height
//
//            // Extract pixels using getPixels (same approach as DepthHelper)
//            val width = bmp.width
//            val height = bmp.height
//            val numPixels = width * height
//            val pixelsIntArray = IntArray(numPixels)
//
//            bmp.getPixels(pixelsIntArray, 0, width, 0, 0, width, height)
//
//            // Create a new bitmap from the extracted pixels
//            val pixelBitmap = Bitmap.createBitmap(pixelsIntArray, width, height, Bitmap.Config.ARGB_8888)
//
//            // Create matrix for scaling and positioning
//            val matrix = Matrix().apply {
//                preScale(imageWidth / width, imageHeight / height)
//
//                // Flip horizontally for front camera
//                if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
//                    preScale(-1f, 1f)
//                    preTranslate(-imageWidth, 0f)
//                }
//            }
//
//            drawImage(
//                image = pixelBitmap.asImageBitmap(),
//                matrix = matrix
//            )
//        }
//    }
//}
