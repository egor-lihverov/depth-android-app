/*
 * Copyright 2025 The Google AI Edge Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.examples.image_segmentation.view

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import com.google.ai.edge.examples.image_segmentation.OverlayInfo

@Composable
fun DepthOverlay(
  modifier: Modifier = Modifier,
  overlayInfo: OverlayInfo,
  lensFacing: Int,
  containerAspectRatio: Float? = null,  // Optional: override container aspect ratio
) {
  Canvas(modifier = modifier) {
    val containerWidth: Float = size.width
    val containerHeight: Float = size.height
    
    // Use provided aspect ratio or calculate from container
    val effectiveWidth = if (containerAspectRatio != null) {
      if (containerAspectRatio > containerWidth / containerHeight) {
        containerWidth
      } else {
        containerHeight * containerAspectRatio
      }
    } else {
      containerWidth
    }
    val effectiveHeight = if (containerAspectRatio != null) {
      if (containerAspectRatio > containerWidth / containerHeight) {
        containerWidth / containerAspectRatio
      } else {
        containerHeight
      }
    } else {
      containerHeight
    }

    // Convert depth values to colors using Viridis colormap
    val depthValues = overlayInfo.depthValues
    val width = overlayInfo.width
    val height = overlayInfo.height
    val pixelSize = width * height

    // Convert depth float values to ARGB pixels
    val pixels = IntArray(pixelSize)
    for (i in 0 until pixelSize) {
      val depth = depthValues[i]
      pixels[i] = depthToColor(depth)
    }

    val image = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    val orientedBitmap =
      if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
        // Create a matrix for horizontal flipping
        val matrix = Matrix().apply { preScale(-1f, 1f) }
        Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, false).also {
          image.recycle()
        }
      } else {
        image
      }

    // FIT_CENTER: calculate scale to fit image entirely within effective container
    val imageWidth = orientedBitmap.width.toFloat()
    val imageHeight = orientedBitmap.height.toFloat()

    val scaleW = effectiveWidth / imageWidth
    val scaleH = effectiveHeight / imageHeight
    val scale = minOf(scaleW, scaleH)

    val scaledWidth = imageWidth * scale
    val scaledHeight = imageHeight * scale

    // Center the image within the effective area
    val offsetX = (effectiveWidth - scaledWidth) / 2
    val offsetY = (effectiveHeight - scaledHeight) / 2

    // Scale bitmap to final size and draw at centered position
    val scaledBitmap = orientedBitmap.scale(scaledWidth.toInt(), scaledHeight.toInt(), true)
    drawImage(
      scaledBitmap.asImageBitmap(),
      topLeft = Offset(offsetX, offsetY)
    )
    orientedBitmap.recycle()
    scaledBitmap.recycle()
  }
}

/**
 * Converts a normalized depth value (0.0 to 1.0) to a color using the Inferno colormap.
 *
 * Inferno colormap: black -> red -> orange -> yellow -> white (near=far)
 *
 * @param depth Normalized depth value between 0.0 (closest) and 1.0 (farthest)
 * @return ARGB color integer
 */
fun depthToColor(depth: Float): Int {
  val t = depth.coerceIn(0f, 1f)

  // Inferno colormap approximation using piecewise linear interpolation
  // Based on the matplotlib Inferno colormap
  val (r, g, b) =
    when {
      t < 0.2f -> {
        // Black to dark red
        val r = (0.0f + (0.5f - 0.0f) * (t / 0.2f))
        val g = (0.0f + (0.01f - 0.0f) * (t / 0.2f))
        val b = (0.0f + (0.02f - 0.0f) * (t / 0.2f))
        Triple(r, g, b)
      }
      t < 0.4f -> {
        // Dark red to red
        val r = (0.5f + (0.8f - 0.5f) * ((t - 0.2f) / 0.2f))
        val g = (0.01f + (0.15f - 0.01f) * ((t - 0.2f) / 0.2f))
        val b = (0.02f + (0.03f - 0.02f) * ((t - 0.2f) / 0.2f))
        Triple(r, g, b)
      }
      t < 0.6f -> {
        // Red to orange
        val r = (0.8f + (0.95f - 0.8f) * ((t - 0.4f) / 0.2f))
        val g = (0.15f + (0.45f - 0.15f) * ((t - 0.4f) / 0.2f))
        val b = (0.03f + (0.02f - 0.03f) * ((t - 0.4f) / 0.2f))
        Triple(r, g, b)
      }
      t < 0.8f -> {
        // Orange to yellow-orange
        val r = (0.95f + (0.98f - 0.95f) * ((t - 0.6f) / 0.2f))
        val g = (0.45f + (0.75f - 0.45f) * ((t - 0.6f) / 0.2f))
        val b = (0.02f + (0.1f - 0.02f) * ((t - 0.6f) / 0.2f))
        Triple(r, g, b)
      }
      else -> {
        // Yellow-orange to bright yellow/white
        val r = (0.98f + (1.0f - 0.98f) * ((t - 0.8f) / 0.2f))
        val g = (0.75f + (0.9f - 0.75f) * ((t - 0.8f) / 0.2f))
        val b = (0.1f + (0.3f - 0.1f) * ((t - 0.8f) / 0.2f))
        Triple(r, g, b)
      }
    }

  return Color.rgb(
    (r * 255).toInt().coerceIn(0, 255),
    (g * 255).toInt().coerceIn(0, 255),
    (b * 255).toInt().coerceIn(0, 255),
  )
}
