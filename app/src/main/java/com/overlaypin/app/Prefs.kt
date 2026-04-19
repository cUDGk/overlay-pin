package com.overlaypin.app

import android.content.Context
import android.net.Uri

object Prefs {
    private const val NAME = "overlay_pin"
    private const val KEY_URI = "image_uri"
    private const val KEY_SIZE = "size_pct"
    private const val KEY_FX = "frac_x"
    private const val KEY_FY = "frac_y"
    private const val KEY_TRANSLUCENT = "translucent"
    private const val KEY_IS_VIDEO = "is_video"
    private const val KEY_VERSION = "prefs_version"
    private const val CURRENT_VERSION = 3

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun migrate(ctx: Context) {
        val s = sp(ctx)
        if (s.getInt(KEY_VERSION, 0) < CURRENT_VERSION) {
            s.edit()
                .putBoolean(KEY_TRANSLUCENT, false)
                .putInt(KEY_VERSION, CURRENT_VERSION)
                .apply()
        }
    }

    fun getImageUri(ctx: Context): Uri? =
        sp(ctx).getString(KEY_URI, null)?.let { Uri.parse(it) }

    fun setImageUri(ctx: Context, uri: Uri, isVideo: Boolean) {
        sp(ctx).edit()
            .putString(KEY_URI, uri.toString())
            .putBoolean(KEY_IS_VIDEO, isVideo)
            .apply()
    }

    fun isVideo(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_IS_VIDEO, false)

    fun getSizePct(ctx: Context): Int = sp(ctx).getInt(KEY_SIZE, 100).coerceIn(10, 200)
    fun setSizePct(ctx: Context, pct: Int) {
        sp(ctx).edit().putInt(KEY_SIZE, pct.coerceIn(10, 200)).apply()
    }

    fun getFracX(ctx: Context): Float = sp(ctx).getFloat(KEY_FX, 0.5f).coerceIn(0f, 1f)
    fun getFracY(ctx: Context): Float = sp(ctx).getFloat(KEY_FY, 0.5f).coerceIn(0f, 1f)
    fun setFrac(ctx: Context, fx: Float, fy: Float) {
        sp(ctx).edit()
            .putFloat(KEY_FX, fx.coerceIn(0f, 1f))
            .putFloat(KEY_FY, fy.coerceIn(0f, 1f))
            .apply()
    }

    fun isTranslucent(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_TRANSLUCENT, false)
    fun setTranslucent(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_TRANSLUCENT, on).apply()
    }
}
