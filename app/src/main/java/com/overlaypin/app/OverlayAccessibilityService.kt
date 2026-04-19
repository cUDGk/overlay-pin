package com.overlaypin.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import kotlin.math.max
import kotlin.math.min

private const val TAG = "OverlayPin"

/**
 * AccessibilityService-backed overlay. Uses TYPE_ACCESSIBILITY_OVERLAY so
 * MIUI/HyperOS does not apply its TYPE_APPLICATION_OVERLAY framing (which
 * otherwise makes every overlay look semi-transparent).
 *
 * Supports both images (ImageView + Bitmap) and videos (TextureView +
 * MediaPlayer, muted + looped).
 */
class OverlayAccessibilityService : AccessibilityService() {

    private var wm: WindowManager? = null
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoW: Int = 0
    private var videoH: Int = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.migrate(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
        Log.d(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* unused */ }
    override fun onInterrupt() { /* unused */ }

    override fun onDestroy() {
        hideOverlay()
        instance = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyAll()
    }

    // ---------------------------------------------------------------------

    fun isShowing(): Boolean = overlayView != null

    fun showOverlay(): Boolean {
        if (overlayView != null) return true
        val uri = Prefs.getImageUri(this) ?: run {
            Log.w(TAG, "No URI"); return false
        }
        val isVideo = Prefs.isVideo(this)

        val v: View? = if (isVideo) createVideoView(uri) else createImageView(uri)
        if (v == null) { Log.e(TAG, "view create failed"); return false }

        val p = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            format = PixelFormat.RGBA_8888
            gravity = Gravity.TOP or Gravity.START
            alpha = 1f
        }
        applyFromPrefs(v, p)

        return try {
            wm?.addView(v, p)
            overlayView = v
            params = p
            Log.d(TAG, "Overlay shown (video=$isVideo)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
            releaseMediaPlayer()
            false
        }
    }

    fun hideOverlay() {
        releaseMediaPlayer()
        try { overlayView?.let { wm?.removeView(it) } } catch (_: Exception) {}
        overlayView = null
        params = null
    }

    fun applyAll() {
        val v = overlayView ?: return
        val p = params ?: return
        applyFromPrefs(v, p)
        try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
        // Video aspect transform depends on new size.
        (v as? TextureView)?.let { applyVideoAspectTransform(it) }
    }

    // ---------------------------------------------------------------------

    private fun createImageView(uri: android.net.Uri): ImageView? {
        val drawable = try {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeDrawable(source)
        } catch (e: Exception) {
            Log.e(TAG, "decodeDrawable failed", e); null
        } ?: return null
        if (drawable is AnimatedImageDrawable) {
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            drawable.start()
        }
        return ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(drawable)
            background = null
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
    }

    private fun createVideoView(uri: android.net.Uri): TextureView {
        val tv = TextureView(this)
        tv.isOpaque = false
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                try {
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(this@OverlayAccessibilityService, uri)
                        setSurface(Surface(s))
                        setVolume(0f, 0f)
                        isLooping = true
                        setOnPreparedListener {
                            videoW = it.videoWidth
                            videoH = it.videoHeight
                            applyVideoAspectTransform(tv)
                            it.start()
                        }
                        setOnErrorListener { _, what, extra ->
                            Log.e(TAG, "MediaPlayer error $what / $extra"); true
                        }
                        prepareAsync()
                    }
                } catch (e: Exception) { Log.e(TAG, "MediaPlayer setup", e) }
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {
                applyVideoAspectTransform(tv)
            }
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                releaseMediaPlayer(); return true
            }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) { /* frame tick */ }
        }
        return tv
    }

    /** Apply a FIT_CENTER-style Matrix so video keeps aspect ratio. */
    private fun applyVideoAspectTransform(tv: TextureView) {
        if (videoW <= 0 || videoH <= 0 || tv.width <= 0 || tv.height <= 0) return
        val vw = tv.width.toFloat()
        val vh = tv.height.toFloat()
        val fitScale = min(vw / videoW, vh / videoH)
        val sx = (videoW * fitScale) / vw
        val sy = (videoH * fitScale) / vh
        val m = Matrix()
        m.setScale(sx, sy, vw / 2f, vh / 2f)
        tv.setTransform(m)
    }

    private fun releaseMediaPlayer() {
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun applyFromPrefs(v: View, p: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val base = min(dm.widthPixels, dm.heightPixels) / 2
        val pct = Prefs.getSizePct(this)
        val side = max(80, base * pct / 100)
        p.width = side
        p.height = side

        val fx = Prefs.getFracX(this)
        val fy = Prefs.getFracY(this)
        val centerX = (fx * dm.widthPixels).toInt()
        val centerY = (fy * dm.heightPixels).toInt()
        p.x = centerX - side / 2
        p.y = centerY - side / 2

        val alpha = Prefs.getAlpha(this)
        v.alpha = alpha
        if (v is ImageView) v.imageAlpha = (alpha * 255).toInt().coerceIn(0, 255)
        v.background = null
        p.alpha = 1f

        val rot = (getSystemService(WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.rotation
        v.rotation = when (rot) {
            Surface.ROTATION_0 -> 0f
            Surface.ROTATION_90 -> -90f
            Surface.ROTATION_180 -> 180f
            Surface.ROTATION_270 -> 90f
            else -> 0f
        }
    }

    companion object {
        @Volatile
        var instance: OverlayAccessibilityService? = null

        fun isAvailable(): Boolean = instance != null

        fun isShowing(): Boolean = instance?.isShowing() == true

        fun show(): Boolean = instance?.showOverlay() == true

        fun hide() { instance?.hideOverlay() }

        fun notifyChanged() {
            instance?.applyAll()
        }
    }
}
