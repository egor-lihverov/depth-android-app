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

package com.google.ai.edge.examples.image_segmentation

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(private val depthHelper: DepthHelper) : ViewModel() {
  companion object {
    fun getFactory(context: Context) =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
          val depthHelper = DepthHelper(context)
          return if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            MainViewModel(depthHelper) as T
          } else {
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
          }
        }
      }
  }

  init {
    viewModelScope.launch { depthHelper.initEstimator() }
  }

  private var estimateJob: Job? = null

  private val sourceAspectRatio = MutableStateFlow<Float?>(null)
  
  private val depthUiShareFlow =
    MutableStateFlow<Pair<OverlayInfo?, Long>>(Pair(null, 0L)).also { flow ->
      viewModelScope.launch {
        depthHelper.depth
          .filter { it.depth.data.isNotEmpty() }
          .combine(sourceAspectRatio) { depthData, aspectRatio ->
            val depth = depthData.depth
            val depthValues = depth.data
            val width = depth.width
            val height = depth.height

            val overlayInfo = OverlayInfo(
              depthValues = depthValues,
              width = width,
              height = height,
              aspectRatio = aspectRatio // Use source image/video aspect ratio
            )

            val inferenceTime = depthData.inferenceTime
            Pair(overlayInfo, inferenceTime)
          }
          .collect { flow.emit(it) }
      }
    }

  private val mediaUri = MutableStateFlow<Uri>(Uri.EMPTY)

  private val originalBitmap = MutableStateFlow<Bitmap?>(null)

  private val errorMessage =
    MutableStateFlow<Throwable?>(null).also {
      viewModelScope.launch { depthHelper.error.collect(it) }
    }

  private val lensFacing = MutableStateFlow(CameraSelector.LENS_FACING_BACK)

  val uiState: StateFlow<UiState> =
    combine(mediaUri, depthUiShareFlow, originalBitmap, errorMessage, lensFacing) {
        uri,
        depthUiPair,
        bitmap,
        error,
        lensFace ->
        UiState(
          mediaUri = uri,
          overlayInfo = depthUiPair.first,
          originalBitmap = bitmap,
          inferenceTime = depthUiPair.second,
          errorMessage = error?.message,
          lensFacing = lensFace,
        )
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

  fun flipCamera() {
    val newFacing =
      if (lensFacing.value == CameraSelector.LENS_FACING_BACK) {
        CameraSelector.LENS_FACING_FRONT
      } else {
        CameraSelector.LENS_FACING_BACK
      }

    lensFacing.update { newFacing }
  }

  /**
   * Start depth estimation on an image.
   *
   * @param imageProxy contain `imageBitMap` and imageInfo as `image rotation degrees`.
   */
  fun estimateDepth(imageProxy: ImageProxy) {
    estimateJob =
      viewModelScope.launch {
        val bitmap = imageProxy.toBitmap()
        originalBitmap.emit(bitmap)
        sourceAspectRatio.emit(bitmap.width.toFloat() / bitmap.height.toFloat())
        depthHelper.estimate(bitmap, imageProxy.imageInfo.rotationDegrees)
        imageProxy.close()
      }
  }

  /**
   * Start depth estimation on an image.
   *
   * @param bitmap Tries to make a new bitmap based on the dimensions of this bitmap, setting the
   *   new bitmap's config to Bitmap.Config.ARGB_8888
   * @param rotationDegrees to correct the rotationDegrees during depth estimation
   */
  fun estimateDepth(bitmap: Bitmap, rotationDegrees: Int) {
    estimateJob =
      viewModelScope.launch {
        val argbBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        originalBitmap.emit(argbBitmap)
        sourceAspectRatio.emit(argbBitmap.width.toFloat() / argbBitmap.height.toFloat())
        depthHelper.estimate(argbBitmap, rotationDegrees)
      }
  }

  /** Stop current depth estimation */
  fun stopDepthEstimation() {
    viewModelScope.launch {
      estimateJob?.cancel()
      depthUiShareFlow.emit(Pair(null, 0L))
      originalBitmap.emit(null)
    }
  }

  /** Update display media uri */
  fun updateMediaUri(uri: Uri) {
    if (uri != mediaUri.value || uri.toString().contains("video")) {
      stopDepthEstimation()
    }
    mediaUri.update { uri }
  }

  /** Set Accelerator for DepthHelper(CPU/GPU) */
  fun setAccelerator(accleratorEnum: DepthHelper.AcceleratorEnum) {
    viewModelScope.launch { depthHelper.initEstimator(accleratorEnum) }
  }

  /** Clear error message after it has been consumed */
  fun errorMessageShown() {
    errorMessage.update { null }
  }
}
