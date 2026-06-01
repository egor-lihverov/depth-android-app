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
import android.graphics.Color
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.scale
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class DepthHelper(private val context: Context) {
  /** As the result of depth estimation, this value emits depth map */
  val depth: SharedFlow<DepthResult>
    get() = _depth

  private val _depth =
    MutableSharedFlow<DepthResult>(
      extraBufferCapacity = 64,
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

  val error: SharedFlow<Throwable?>
    get() = _error

  private val _error = MutableSharedFlow<Throwable?>()

  // Accessed only with singleThreadDispatcher.
  private var depthEstimator: DepthEstimator? = null
  // Use Dispatchers.Default for CPU-bound compute work - allow parallelism to keep CPU hot
  private val inferenceDispatcher = Dispatchers.Default

  
  /** Init a CompiledModel from AI Pack. */
  suspend fun initEstimator(acceleratorEnum: AcceleratorEnum = AcceleratorEnum.CPU) {
    cleanup()
    try {
      withContext(inferenceDispatcher) {
        val model =
          CompiledModel.create(
            context.assets,
            "depth_model.tflite",
            CompiledModel.Options(toAccelerator(acceleratorEnum)),
            null,
          )
        depthEstimator = DepthEstimator(model)
        // Warmup: run a dummy inference to keep model hot in CPU cache
        depthEstimator?.warmup()
        Log.d(TAG, "Created a depth estimator with warmup completed")
      }
    } catch (e: Exception) {
      Log.i(TAG, "Create LiteRT from depth model is failed: ${e.message}")
      _error.emit(e)
    }
  }

  /** Cleanup resources when the helper is no longer needed */
  suspend fun cleanup() {
    try {
      withContext(inferenceDispatcher) {
        depthEstimator?.cleanup()
        depthEstimator = null
        Log.d(TAG, "Destroyed the depth estimator")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error during cleanup: ${e.message}")
    }
  }

  suspend fun estimate(bitmap: Bitmap, rotationDegrees: Int) {
    try {
      withContext(inferenceDispatcher) {
        depthEstimator?.estimate(bitmap, rotationDegrees)?.let {
          if (isActive) _depth.emit(it)
        }
      }
    } catch (e: Exception) {
      Log.i(TAG, "Depth estimation error occurred: ${e.message}")
      _error.emit(e)
    }
  }

  private class DepthEstimator(private val model: CompiledModel) {
    private val inputBuffers = model.createInputBuffers()
    private val outputBuffers = model.createOutputBuffers()

    fun cleanup() {
      inputBuffers.forEach { it.close() }
      outputBuffers.forEach { it.close() }
      // The CompiledModel will handle the cleanup of tensor buffers
      model.close()
    }

    /**
     * Warmup inference to keep model hot in CPU cache.
     * Runs a single dummy inference immediately after model creation.
     */
    fun warmup() {
      try {
        val warmupBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val warmupResult = estimate(warmupBitmap, 0)
        Log.d(TAG, "Warmup completed - inference time: ${warmupResult.inferenceTime} ms")
        warmupBitmap.recycle()
      } catch (e: Exception) {
        Log.w(TAG, "Warmup inference failed: ${e.message}")
      }
    }

    fun estimate(bitmap: Bitmap, rotationDegrees: Int): DepthResult {
      val totalStartTime = SystemClock.uptimeMillis()
      val rotation = -rotationDegrees / 90
      val (h, w) = Pair(256, 256)

      // Preprocessing timing
      val preprocessStartTime = SystemClock.uptimeMillis()
      var image = bitmap.scale(w, h, true)
      image = rot90Clockwise(image, rotation)
      val inputFloatArray = normalize(image, 0.0f, 256.0f)
//      val inputFloatArray = normalizeToZeroOne(image)
      val preprocessTime = SystemClock.uptimeMillis() - preprocessStartTime
      Log.d(TAG, "Preprocessing time: $preprocessTime ms")

      // Inference timing (includes both model execution and depth processing)
      val inferenceStartTime = SystemClock.uptimeMillis()
      val depthResult = estimate(inputFloatArray, w, h)
      val inferenceTime = SystemClock.uptimeMillis() - inferenceStartTime
      Log.d(TAG, "=== Inference time: $inferenceTime ms ===")

      val totalTime = SystemClock.uptimeMillis() - totalStartTime
      Log.d(TAG, "Total depth estimation time: $totalTime ms")

      return DepthResult(depthResult, inferenceTime)
    }

    private fun rot90Clockwise(image: Bitmap, numRotation: Int): Bitmap {
      val effectiveRotation = numRotation % 4

      if (effectiveRotation == 0) {
        return image
      }

      val (w, h) = Pair(image.width, image.height)

      val matrix = Matrix()
      matrix.postTranslate(w * -0.5f, h * -0.5f)
      matrix.postRotate(-90f * effectiveRotation)
      val newW = if (effectiveRotation % 2 == 0) w else h
      val newH = if (effectiveRotation % 2 == 0) h else w
      matrix.postTranslate(newW * 0.5f, newH * 0.5f)
      return Bitmap.createBitmap(image, 0, 0, w, h, matrix, false)
    }

    private fun normalizeToZeroOne(image: Bitmap): FloatArray {
      val (width, height) = Pair(image.width, image.height)
      val numPixels = width * height
      val pixelsIntArray = IntArray(numPixels)
      val outputFloatArray = FloatArray(numPixels * 3) // 3 channels (R, G, B)

      image.getPixels(pixelsIntArray, 0, width, 0, 0, width, height)

      for (i in 0 until numPixels) {
        val pixel = pixelsIntArray[i]

        // Extract channels (ARGB_8888 format assumed) and scale to [0, 1]
        val outputBaseIndex = i * 3
        outputFloatArray[outputBaseIndex + 0] = Color.red(pixel).toFloat() / 255f // Red
        outputFloatArray[outputBaseIndex + 1] = Color.green(pixel).toFloat() / 255f // Green
        outputFloatArray[outputBaseIndex + 2] = Color.blue(pixel).toFloat() / 255f // Blue
      }

      return outputFloatArray
    }

    private fun normalize(image: Bitmap, mean: Float, stddev: Float): FloatArray {
      val (width, height) = Pair(image.width, image.height)
      val numPixels = width * height
      val pixelsIntArray = IntArray(numPixels)
      val outputFloatArray = FloatArray(numPixels * 3) // 3 channels (R, G, B)

      image.getPixels(pixelsIntArray, 0, width, 0, 0, width, height)

      for (i in 0 until numPixels) {
        val pixel = pixelsIntArray[i]

        // Extract channels (ARGB_8888 format assumed)
        val (r, g, b) =
          Triple(
            Color.red(pixel).toFloat(),
            Color.green(pixel).toFloat(),
            Color.blue(pixel).toFloat(),
          )

        // Normalize and store in interleaved format
        val outputBaseIndex = i * 3
        outputFloatArray[outputBaseIndex + 0] = (r - mean) / stddev // Red
        outputFloatArray[outputBaseIndex + 1] = (g - mean) / stddev // Green
        outputFloatArray[outputBaseIndex + 2] = (b - mean) / stddev // Blue
      }

      return outputFloatArray
    }

    private fun estimate(inputFloatArray: FloatArray, width: Int, height: Int): Depth {
      // MODEL EXECUTION PHASE
      val modelExecStartTime = SystemClock.uptimeMillis()

      // Write input data - measure time
      val bufferWriteStartTime = SystemClock.uptimeMillis()
      inputBuffers[0].writeFloat(inputFloatArray)
      val bufferWriteTime = SystemClock.uptimeMillis() - bufferWriteStartTime

      // Run model inference - measure time
      val modelRunStartTime = SystemClock.uptimeMillis()
      model.run(inputBuffers, outputBuffers)
      val modelRunTime = SystemClock.uptimeMillis() - modelRunStartTime

      // Read output data - measure time
      val bufferReadStartTime = SystemClock.uptimeMillis()
      val outputFloatArray = outputBuffers[0].readFloat()
      val bufferReadTime = SystemClock.uptimeMillis() - bufferReadStartTime

      val modelExecTime = SystemClock.uptimeMillis() - modelExecStartTime
      // Only log model run time for performance monitoring
      Log.d(TAG, "Model run: $modelRunTime ms")

      // POSTPROCESSING PHASE
      val postprocessStartTime = SystemClock.uptimeMillis()

      // Process depth map from model output
      val depthMap = processDepth(outputFloatArray, width, height)
      Log.d(TAG, "Depth map size: ${depthMap.size}, width=$width, height=$height")

      val postprocessTime = SystemClock.uptimeMillis() - postprocessStartTime
      Log.d(TAG, "Postprocessing time (depth map creation): $postprocessTime ms")

      return Depth(depthMap, width, height)
    }

    private fun processDepth(outputArray: FloatArray, width: Int, height: Int): FloatArray {
      val expectedSize = width * height
      
      // Handle case where output has different size (e.g., multiple channels or different layout)
      val actualSize = outputArray.size
      val channels = if (actualSize > expectedSize) actualSize / expectedSize else 1
      
      Log.d(TAG, "Processing depth: outputSize=$actualSize, expected=$expectedSize, inferredChannels=$channels")
      
      val depthMap = FloatArray(expectedSize)
      var minDepth = Float.MAX_VALUE
      var maxDepth = Float.MIN_VALUE

      // First pass: find min and max for normalization
      // For multi-channel output, take the first channel or average across channels
      for (y in 0 until height) {
        for (x in 0 until width) {
          val pixelIndex = y * width + x
          val depthValue = if (channels == 1) {
            outputArray[pixelIndex]
          } else {
            // Take first channel value for each pixel
            outputArray[channels * pixelIndex]
          }
          depthMap[pixelIndex] = depthValue
          minDepth = minOf(minDepth, depthValue)
          maxDepth = maxOf(maxDepth, depthValue)
        }
      }

      val depthRange = maxDepth - minDepth
      if (depthRange == 0f) {
        // All depths are the same, return uniform depth
        return FloatArray(width * height) { 0.5f }
      }

      // Second pass: normalize depth to [0, 1] range
      for (i in depthMap.indices) {
        depthMap[i] = (depthMap[i] - minDepth) / depthRange
      }

      return depthMap
    }
  }

  enum class AcceleratorEnum {
    CPU,
    GPU,
    // GPU is disabled for depth models due to GATHER_ND operation not being supported
    // by LiteRT CompiledModel API
  }

  data class Depth(val data: FloatArray, val width: Int, val height: Int)

  data class DepthResult(val depth: Depth, val inferenceTime: Long)

  private companion object {
    const val TAG = "DepthEstimation"

    fun toAccelerator(acceleratorEnum: AcceleratorEnum): Accelerator {
      return when (acceleratorEnum) {
           AcceleratorEnum.CPU -> Accelerator.CPU
           AcceleratorEnum.GPU -> Accelerator.GPU
      }
  }
}
  }
