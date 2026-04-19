package com.overlaypin.app

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var sizeLabel: TextView
    private lateinit var sizeBar: SeekBar
    private lateinit var previewFrame: FrameLayout
    private lateinit var previewImg: ImageView
    private lateinit var btnAccess: Button
    private lateinit var btnOverlayPerm: Button
    private lateinit var opacityLabel: TextView
    private lateinit var opacityBar: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.migrate(this)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        sizeLabel = findViewById(R.id.lbl_size)
        sizeBar = findViewById(R.id.seek_size)
        previewFrame = findViewById(R.id.preview_frame)
        previewImg = findViewById(R.id.preview_img)
        btnAccess = findViewById(R.id.btn_access)
        btnOverlayPerm = findViewById(R.id.btn_perm)
        opacityLabel = findViewById(R.id.lbl_opacity)
        opacityBar = findViewById(R.id.seek_opacity)

        findViewById<Button>(R.id.btn_pick).setOnClickListener { pickMedia() }
        btnAccess.setOnClickListener { openAccessibilitySettings() }
        btnOverlayPerm.setOnClickListener { openOverlaySettings() }
        findViewById<Button>(R.id.btn_start).setOnClickListener { startOverlay() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            OverlayAccessibilityService.hide()
            refresh()
        }
        findViewById<Button>(R.id.btn_miui_perm).setOnClickListener { openMiuiPermEditor() }

        sizeBar.max = 38
        sizeBar.progress = progressFromPct(Prefs.getSizePct(this))
        updateSizeLabel(sizeBar.progress)
        sizeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                applyPct(pctFromProgress(p))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        findViewById<Button>(R.id.btn_size_minus).setOnClickListener {
            applyPct((Prefs.getSizePct(this) - 5).coerceAtLeast(10))
        }
        findViewById<Button>(R.id.btn_size_plus).setOnClickListener {
            applyPct((Prefs.getSizePct(this) + 5).coerceAtMost(200))
        }

        opacityBar.max = 20
        opacityBar.progress = (Prefs.getOpacityPct(this) / 5).coerceIn(0, 20)
        updateOpacityLabel(opacityBar.progress)
        opacityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                applyOpacity(p * 5)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        findViewById<Button>(R.id.btn_op_minus).setOnClickListener {
            applyOpacity((Prefs.getOpacityPct(this) - 5).coerceAtLeast(0))
        }
        findViewById<Button>(R.id.btn_op_plus).setOnClickListener {
            applyOpacity((Prefs.getOpacityPct(this) + 5).coerceAtMost(100))
        }

        previewFrame.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    applyTouchToPosition(e); true
                }
                MotionEvent.ACTION_MOVE -> { applyTouchToPosition(e); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false); true
                }
                else -> true
            }
        }

        previewFrame.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val parent = previewFrame.parent as? android.view.ViewGroup ?: return
                if (parent.width == 0) return
                previewFrame.viewTreeObserver.removeOnGlobalLayoutListener(this)
                sizePreviewToScreenAspect(parent.width)
                refreshPreview()
            }
        })

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (previewFrame.width > 0) refreshPreview()
    }

    private fun progressFromPct(pct: Int): Int = ((pct - 10) / 5).coerceIn(0, 38)
    private fun pctFromProgress(progress: Int): Int = (10 + progress * 5).coerceIn(10, 200)

    private fun updateSizeLabel(progress: Int) {
        sizeLabel.text = "サイズ: ${pctFromProgress(progress)}%"
    }

    private fun applyPct(pct: Int) {
        Prefs.setSizePct(this, pct)
        val p = progressFromPct(pct)
        if (sizeBar.progress != p) sizeBar.progress = p
        updateSizeLabel(p)
        refreshPreview()
        OverlayAccessibilityService.notifyChanged()
    }

    private fun updateOpacityLabel(progress: Int) {
        opacityLabel.text = "透過度: ${progress * 5}%"
    }

    private fun applyOpacity(pct: Int) {
        Prefs.setOpacityPct(this, pct)
        val p = (pct / 5).coerceIn(0, 20)
        if (opacityBar.progress != p) opacityBar.progress = p
        updateOpacityLabel(p)
        refresh()
        refreshPreview()
        OverlayAccessibilityService.notifyChanged()
    }

    private fun sizePreviewToScreenAspect(parentWidth: Int) {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.toFloat()
        val sh = dm.heightPixels.toFloat()
        val maxHeightDp = 520f
        val maxHeightPx = (maxHeightDp * dm.density).toInt()
        val w1 = parentWidth
        val h1 = (w1 * sh / sw).toInt()
        val (pw, ph) = if (h1 <= maxHeightPx) w1 to h1
                       else ((maxHeightPx * sw / sh).toInt()) to maxHeightPx
        val lp = previewFrame.layoutParams
        lp.width = pw
        lp.height = ph
        previewFrame.layoutParams = lp
    }

    private fun refresh() {
        val uri = Prefs.getImageUri(this)
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccess = OverlayAccessibilityService.isAvailable()
        val running = OverlayAccessibilityService.isShowing()
        val opacity = Prefs.getOpacityPct(this)
        val kind = if (Prefs.isVideo(this)) "動画" else "画像"
        status.text = buildString {
            append("メディア:").append(if (uri != null) "✓$kind" else "×").append(" / ")
            append("Accessibility:").append(if (hasAccess) "✓" else "×").append(" / ")
            append("表示:").append(if (running) "ON" else "OFF").append("\n")
            append("オーバーレイ権限:").append(if (hasOverlay) "✓" else "×").append(" / ")
            append("透過度:").append(opacity).append("%")
        }
        btnAccess.text = if (hasAccess) "Accessibility: 有効" else "Accessibility を有効化（重要）"
        btnAccess.isEnabled = !hasAccess
        btnOverlayPerm.text = if (hasOverlay) "オーバーレイ権限: 許可済み" else "オーバーレイ権限を許可"
        btnOverlayPerm.isEnabled = !hasOverlay
    }

    private fun refreshPreview() {
        if (previewFrame.width <= 0 || previewFrame.height <= 0) return
        val uri = Prefs.getImageUri(this)
        if (uri != null) {
            try {
                if (Prefs.isVideo(this)) {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(this, uri)
                    val frame = retriever.getFrameAtTime(0)
                    retriever.release()
                    previewImg.setImageBitmap(frame)
                } else {
                    // ImageDecoder handles static images AND GIFs. Animated
                    // GIFs auto-loop in the preview.
                    val source = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                    val drawable = android.graphics.ImageDecoder.decodeDrawable(source)
                    previewImg.setImageDrawable(drawable)
                    if (drawable is android.graphics.drawable.AnimatedImageDrawable) {
                        drawable.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
                        drawable.start()
                    }
                }
            } catch (_: Exception) {
                previewImg.setImageResource(0)
            }
        } else {
            previewImg.setImageResource(0)
        }
        previewImg.alpha = Prefs.getAlpha(this)

        val dm = resources.displayMetrics
        val screenShort = min(dm.widthPixels, dm.heightPixels).toFloat()
        val scale = previewFrame.width.toFloat() / dm.widthPixels.toFloat()
        val pct = Prefs.getSizePct(this)
        val overlaySidePx = (screenShort / 2f) * (pct / 100f)
        val sidePx = max(16, (overlaySidePx * scale).toInt())

        val lp = previewImg.layoutParams as FrameLayout.LayoutParams
        lp.width = sidePx
        lp.height = sidePx
        previewImg.layoutParams = lp

        positionPreviewImage()
    }

    private fun applyTouchToPosition(e: MotionEvent) {
        val fw = previewFrame.width.toFloat()
        val fh = previewFrame.height.toFloat()
        if (fw <= 0 || fh <= 0) return
        val fx = (e.x / fw).coerceIn(0f, 1f)
        val fy = (e.y / fh).coerceIn(0f, 1f)
        Prefs.setFrac(this, fx, fy)
        positionPreviewImage()
        OverlayAccessibilityService.notifyChanged()
    }

    private fun positionPreviewImage() {
        if (previewFrame.width <= 0 || previewFrame.height <= 0) return
        val fx = Prefs.getFracX(this)
        val fy = Prefs.getFracY(this)
        val cx = fx * previewFrame.width
        val cy = fy * previewFrame.height
        previewImg.translationX = cx - previewImg.width / 2f
        previewImg.translationY = cy - previewImg.height / 2f
    }

    private fun pickMedia() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(i, REQ_PICK)
    }

    private fun openOverlaySettings() {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    private fun openAccessibilitySettings() {
        try {
            val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            Toast.makeText(
                this,
                "「ダウンロードしたアプリ」または「インストールされたサービス」から OverlayPin を探して有効化",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Accessibility設定を開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openMiuiPermEditor() {
        val miui = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity"
            )
            putExtra("extra_pkgname", packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { startActivity(miui); return } catch (_: Exception) {}
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            Toast.makeText(this, "設定画面を開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startOverlay() {
        if (!OverlayAccessibilityService.isAvailable()) {
            Toast.makeText(
                this,
                "先に Accessibility を有効化してください（下のボタン）",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (Prefs.getImageUri(this) == null) {
            Toast.makeText(this, "画像/動画を選択してください", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = OverlayAccessibilityService.show()
        if (!ok) Toast.makeText(this, "表示開始に失敗", Toast.LENGTH_SHORT).show()
        refresh()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val mime = contentResolver.getType(uri) ?: ""
            val isVideo = mime.startsWith("video/")
            Prefs.setImageUri(this, uri, isVideo)
            refresh()
            refreshPreview()
            OverlayAccessibilityService.notifyChanged()
        }
    }

    companion object {
        private const val REQ_PICK = 1001
    }
}
