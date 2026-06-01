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

import android.content.Context
import android.media.MediaPlayer
import android.view.TextureView
import android.view.View.MeasureSpec
import android.widget.FrameLayout

class ScalableVideoView(context: Context) : FrameLayout(context) {
  private var textureView: TextureView
  private var mediaPlayer: MediaPlayer? = null
  private var mVideoWidth = 0
  private var mVideoHeight = 0
  private var displayMode = DisplayMode.ORIGINAL
  private val appContext = context.applicationContext

  enum class DisplayMode {
    ORIGINAL,     // original aspect ratio
    FULL_SCREEN,  // fit to screen (stretches to fill)
    ZOOM          // zoom in
  }

  init {
    // Create TextureView programmatically
    textureView = TextureView(context).apply {
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
      )
    }
    addView(textureView)
  }

  private val surfaceListener = object : TextureView.SurfaceTextureListener {
    override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
      val surface = android.view.Surface(surface)
      mediaPlayer?.setSurface(surface)
      mediaPlayer?.prepareAsync()
    }

    override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
      mediaPlayer?.stop()
      return true
    }

    override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
  }

  init {
    textureView.surfaceTextureListener = surfaceListener
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val containerWidth = MeasureSpec.getSize(widthMeasureSpec)
    val containerHeight = MeasureSpec.getSize(heightMeasureSpec)

    if (displayMode == DisplayMode.FULL_SCREEN) {
      // FIT_CENTER: fit video entirely within container, maintain aspect ratio
      if (mVideoWidth > 0 && mVideoHeight > 0) {
        val videoAspectRatio = mVideoWidth.toFloat() / mVideoHeight
        val containerAspectRatio = containerWidth.toFloat() / containerHeight

        val (width, height) = if (videoAspectRatio > containerAspectRatio) {
          // Video is wider - fit to width
          Pair(containerWidth, (containerWidth / videoAspectRatio).toInt())
        } else {
          // Video is taller - fit to height
          Pair((containerHeight * videoAspectRatio).toInt(), containerHeight)
        }

        // Center the video
        val offsetX = (containerWidth - width) / 2
        val offsetY = (containerHeight - height) / 2
        textureView.layoutParams = FrameLayout.LayoutParams(width, height)
        textureView.translationX = offsetX.toFloat()
        textureView.translationY = offsetY.toFloat()
        setMeasuredDimension(containerWidth, containerHeight)
        return
      }
    }

    // DEFAULT: Fill container
    textureView.layoutParams = FrameLayout.LayoutParams(containerWidth, containerHeight)
    setMeasuredDimension(containerWidth, containerHeight)
  }

  fun setVideoURI(uri: android.net.Uri) {
    releasePlayer()
    createPlayer()
    try {
      mediaPlayer?.setDataSource(appContext, uri)
      mediaPlayer?.prepareAsync()
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  fun setOnPreparedListener(listener: (MediaPlayer) -> Unit) {
    mediaPlayer?.setOnPreparedListener { mp ->
      mVideoWidth = mp.videoWidth
      mVideoHeight = mp.videoHeight
      requestLayout()
      listener(mp)
    }
  }

  private fun createPlayer() {
    mediaPlayer = MediaPlayer().apply {
      setAudioStreamType(android.media.AudioManager.STREAM_MUSIC)
    }
  }

  private fun releasePlayer() {
    mediaPlayer?.apply {
      stop()
      release()
    }
    mediaPlayer = null
  }

  fun setDisplayMode(mode: DisplayMode) {
    displayMode = mode
    requestLayout()
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    releasePlayer()
  }
}
