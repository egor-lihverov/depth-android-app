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
      // Always stretch to fill the entire container
      textureView.layoutParams = FrameLayout.LayoutParams(containerWidth, containerHeight)
      setMeasuredDimension(containerWidth, containerHeight)
      return
    }

    var width = containerWidth
    var height = containerHeight

    if (displayMode == DisplayMode.ORIGINAL && mVideoWidth > 0 && mVideoHeight > 0) {
      val videoAspectRatio = mVideoWidth.toFloat() / mVideoHeight
      val containerAspectRatio = containerWidth.toFloat() / containerHeight

      if (videoAspectRatio > containerAspectRatio) {
        height = (containerWidth / videoAspectRatio).toInt()
      } else {
        width = (containerHeight * videoAspectRatio).toInt()
      }
    }

    textureView.layoutParams = FrameLayout.LayoutParams(width, height)
    setMeasuredDimension(width, height)
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
