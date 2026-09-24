package com.vm2st.photostretchingtool

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.graphics.scale
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private enum class AppTheme(
    val preferenceValue: String,
    val labelRes: Int,
    val styleRes: Int
) {
    CURRENT("current", R.string.theme_current, R.style.Theme_PhotoStretchingTool),
    DARK("dark", R.string.theme_dark, R.style.Theme_PhotoStretchingTool_Dark),
    BURGUNDY("burgundy", R.string.theme_burgundy, R.style.Theme_PhotoStretchingTool_Burgundy);

    companion object {
        fun from(value: String?): AppTheme =
            entries.firstOrNull { it.preferenceValue == value } ?: CURRENT
    }
}

private data class ThemePalette(
    val background: Int,
    val imageSurface: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val toolbar: Int,
    val loadButton: Int,
    val saveButton: Int,
    val shareButton: Int,
    val applyButton: Int,
    val accent: Int
)

class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var seekBarWidth: SeekBar
    private lateinit var seekBarHeight: SeekBar
    private lateinit var tvWidthValue: TextView
    private lateinit var tvHeightValue: TextView
    private lateinit var tvCurrentSize: TextView
    private lateinit var etWidth: EditText
    private lateinit var etHeight: EditText
    private lateinit var btnLoadImage: Button
    private lateinit var btnSaveImage: Button
    private lateinit var btnApplySize: Button
    private lateinit var btnShare: Button

    private var originalBitmap: Bitmap? = null
    private var stretchedBitmap: Bitmap? = null
    private var widthScale = 1.0f
    private var heightScale = 1.0f
    private var updatingControls = false
    private var tempImageFile: File? = null
    private var loadGeneration = 0
    private lateinit var imageExecutor: ExecutorService

    private val selectedTheme: AppTheme by lazy {
        AppTheme.from(
            getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .getString(THEME_KEY, AppTheme.CURRENT.preferenceValue)
        )
    }

    private val pickImageLauncher: ActivityResultLauncher<String> = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let(::loadImage) }

    private val saveFileLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        if (result.resultCode == RESULT_OK) {
            val saved = saveBitmapToUri(stretchedBitmap, uri)
            Toast.makeText(
                this,
                getString(if (saved) R.string.save_success else R.string.save_error),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val theme = selectedTheme
        AppCompatDelegate.setDefaultNightMode(
            if (theme == AppTheme.DARK) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
        setTheme(theme.styleRes)
        super.onCreate(savedInstanceState)

        imageExecutor = Executors.newSingleThreadExecutor()
        setContentView(R.layout.activity_main)
        applySystemBarInsets()
        initViews()
        applyThemeColors()
        setupToolbar()
        setupListeners()
    }

    private fun applySystemBarInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<View>(R.id.root_container)
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                left + bars.left,
                top + bars.top,
                right + bars.right,
                bottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val dark = selectedTheme == AppTheme.DARK
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }

    private fun applyThemeColors() {
        val root = findViewById<View>(R.id.root_container)
        val toolbar = findViewById<MaterialToolbar>(R.id.top_app_bar)
        val imageFrame = findViewById<View>(R.id.image_frame)
        val palette = themePalette()

        root.setBackgroundColor(palette.background)
        toolbar.setBackgroundColor(palette.toolbar)
        toolbar.setTitleTextColor(palette.primaryText)
        imageFrame.setBackgroundColor(palette.imageSurface)

        listOf(
            btnLoadImage to palette.loadButton,
            btnSaveImage to palette.saveButton,
            btnShare to palette.shareButton,
            btnApplySize to palette.applyButton
        ).forEach { (button, color) ->
            button.background = createButtonBackground(color)
            button.setTextColor(Color.WHITE)
        }

        tintTextViews(findViewById(R.id.content_scroll), palette.primaryText)
        listOf(btnLoadImage, btnSaveImage, btnShare, btnApplySize).forEach {
            it.setTextColor(Color.WHITE)
        }
        tvCurrentSize.setTextColor(palette.secondaryText)
        tvWidthValue.setTextColor(palette.secondaryText)
        tvHeightValue.setTextColor(palette.secondaryText)
        etWidth.setHintTextColor(palette.secondaryText)
        etHeight.setHintTextColor(palette.secondaryText)
        if (selectedTheme != AppTheme.CURRENT) {
            val tint = ColorStateList.valueOf(palette.accent)
            seekBarWidth.progressTintList = tint
            seekBarWidth.thumbTintList = tint
            seekBarHeight.progressTintList = tint
            seekBarHeight.thumbTintList = tint
        }
    }

    private fun themePalette(): ThemePalette =
        when (selectedTheme) {
            AppTheme.CURRENT -> ThemePalette(
                background = Color.rgb(245, 245, 245),
                imageSurface = Color.WHITE,
                primaryText = Color.BLACK,
                secondaryText = Color.rgb(85, 85, 85),
                toolbar = Color.WHITE,
                loadButton = Color.rgb(76, 175, 80),
                saveButton = Color.rgb(33, 150, 243),
                shareButton = Color.rgb(156, 39, 176),
                applyButton = Color.rgb(255, 152, 0),
                accent = Color.rgb(0, 188, 188)
            )
            AppTheme.DARK -> ThemePalette(
                background = Color.rgb(23, 20, 20),
                imageSurface = Color.rgb(40, 32, 32),
                primaryText = Color.rgb(255, 244, 241),
                secondaryText = Color.rgb(216, 201, 197),
                toolbar = Color.rgb(33, 27, 27),
                loadButton = Color.rgb(53, 122, 74),
                saveButton = Color.rgb(53, 111, 164),
                shareButton = Color.rgb(126, 44, 145),
                applyButton = Color.rgb(167, 101, 0),
                accent = Color.rgb(185, 108, 108)
            )
            AppTheme.BURGUNDY -> ThemePalette(
                background = Color.rgb(255, 248, 247),
                imageSurface = Color.WHITE,
                primaryText = Color.rgb(45, 21, 21),
                secondaryText = Color.rgb(110, 85, 85),
                toolbar = Color.rgb(255, 241, 239),
                loadButton = Color.rgb(137, 41, 41),
                saveButton = Color.rgb(111, 32, 32),
                shareButton = Color.rgb(155, 61, 61),
                applyButton = Color.rgb(169, 80, 36),
                accent = Color.rgb(137, 41, 41)
            )
        }

    private fun tintTextViews(view: View, color: Int) {
        if (view is TextView && view !is Button) {
            view.setTextColor(color)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                tintTextViews(view.getChildAt(index), color)
            }
        }
    }

    private fun createButtonBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(color)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun initViews() {
        imageView = findViewById(R.id.imageView)
        seekBarWidth = findViewById(R.id.seekBarWidth)
        seekBarHeight = findViewById(R.id.seekBarHeight)
        tvWidthValue = findViewById(R.id.tvWidthValue)
        tvHeightValue = findViewById(R.id.tvHeightValue)
        tvCurrentSize = findViewById(R.id.tvCurrentSize)
        etWidth = findViewById(R.id.etWidth)
        etHeight = findViewById(R.id.etHeight)
        btnLoadImage = findViewById(R.id.btnLoadImage)
        btnSaveImage = findViewById(R.id.btnSaveImage)
        btnApplySize = findViewById(R.id.btnApplySize)
        btnShare = findViewById(R.id.btnShare)
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.top_app_bar)
        toolbar.menu.setGroupCheckable(R.id.theme_group, true, true)
        updateThemeMenu(toolbar)
        toolbar.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.theme_current -> selectTheme(AppTheme.CURRENT)
                R.id.theme_dark -> selectTheme(AppTheme.DARK)
                R.id.theme_burgundy -> selectTheme(AppTheme.BURGUNDY)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
    }

    private fun updateThemeMenu(toolbar: MaterialToolbar) {
        val currentTheme = selectedTheme
        val currentLabel = getString(currentTheme.labelRes)

        toolbar.menu.findItem(R.id.action_theme).title =
            getString(R.string.theme_menu_with_value, currentLabel)

        val themeItems = listOf(
            R.id.theme_current to AppTheme.CURRENT,
            R.id.theme_dark to AppTheme.DARK,
            R.id.theme_burgundy to AppTheme.BURGUNDY
        )
        themeItems.forEach { (itemId, theme) ->
            toolbar.menu.findItem(itemId).apply {
                isCheckable = true
                isChecked = theme == currentTheme
                title = getString(
                    if (theme == currentTheme) R.string.theme_selected
                    else theme.labelRes,
                    getString(theme.labelRes)
                )
            }
        }
    }

    private fun selectTheme(theme: AppTheme) {
        if (theme == selectedTheme) return
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .edit { putString(THEME_KEY, theme.preferenceValue) }
        recreate()
    }

    private fun setupListeners() {
        btnLoadImage.setOnClickListener { openImagePicker() }
        btnSaveImage.setOnClickListener { saveImageToAnyFolder() }
        btnApplySize.setOnClickListener { applyCustomSize() }
        btnShare.setOnClickListener { shareImage() }

        seekBarWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (updatingControls) return
                widthScale = progress / 100.0f
                tvWidthValue.text = getString(R.string.percent_value, progress)
                stretchImage()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        seekBarHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (updatingControls) return
                heightScale = progress / 100.0f
                tvHeightValue.text = getString(R.string.percent_value, progress)
                stretchImage()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun openImagePicker() {
        pickImageLauncher.launch("image/*")
    }

    private fun loadImage(uri: Uri) {
        val generation = ++loadGeneration
        btnLoadImage.isEnabled = false
        imageExecutor.execute {
            val result = runCatching { decodeImage(uri) }
            runOnUiThread {
                if (generation != loadGeneration || isFinishing || isDestroyed) {
                    result.getOrNull()?.recycle()
                    return@runOnUiThread
                }
                btnLoadImage.isEnabled = true
                result.onSuccess { bitmap ->
                    if (bitmap == null) {
                        Toast.makeText(this, R.string.image_not_supported, Toast.LENGTH_SHORT).show()
                    } else {
                        replaceOriginalBitmap(bitmap)
                    }
                }.onFailure {
                    Toast.makeText(this, R.string.load_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun decodeImage(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return null

        val limited = limitBitmapSize(decoded, MAX_IMAGE_DIMENSION, MAX_IMAGE_PIXELS)
        return applyExifOrientation(uri, limited)
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_IMAGE_DIMENSION * 2 ||
            height / sample > MAX_IMAGE_DIMENSION * 2
        ) {
            sample *= 2
        }
        return sample
    }

    private fun applyExifOrientation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }

        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        return rotated
    }

    private fun limitBitmapSize(bitmap: Bitmap, maxDimension: Int, maxPixels: Long): Bitmap {
        val dimensions = safeDimensions(bitmap.width, bitmap.height, 1f, 1f, maxDimension, maxPixels)
        if (dimensions.first == bitmap.width && dimensions.second == bitmap.height) return bitmap
        val limited = bitmap.scale(dimensions.first, dimensions.second, true)
        if (!bitmap.isRecycled) bitmap.recycle()
        return limited
    }

    private fun stretchImage() {
        val original = originalBitmap ?: return
        val dimensions = safeDimensions(
            original.width,
            original.height,
            widthScale,
            heightScale,
            MAX_OUTPUT_DIMENSION,
            MAX_OUTPUT_PIXELS
        )
        val resized = original.scale(dimensions.first, dimensions.second, true)
        replaceStretchedBitmap(resized)
        updateSizeInfo()
    }

    private fun applyCustomSize() {
        val original = originalBitmap
        if (original == null) {
            Toast.makeText(this, R.string.image_not_supported, Toast.LENGTH_SHORT).show()
            return
        }

        val newWidth = etWidth.text.toString().toLongOrNull()
        val newHeight = etHeight.text.toString().toLongOrNull()
        if (newWidth == null || newHeight == null || newWidth <= 0 || newHeight <= 0) {
            Toast.makeText(this, R.string.invalid_size, Toast.LENGTH_SHORT).show()
            return
        }
        if (newWidth > MAX_OUTPUT_DIMENSION || newHeight > MAX_OUTPUT_DIMENSION ||
            newWidth * newHeight > MAX_OUTPUT_PIXELS
        ) {
            Toast.makeText(this, R.string.image_too_large, Toast.LENGTH_SHORT).show()
            return
        }

        val resized = original.scale(newWidth.toInt(), newHeight.toInt(), true)
        replaceStretchedBitmap(resized)
        widthScale = newWidth.toFloat() / original.width
        heightScale = newHeight.toFloat() / original.height
        updateScaleControls()
        updateSizeInfo()
    }

    private fun safeDimensions(
        sourceWidth: Int,
        sourceHeight: Int,
        widthMultiplier: Float,
        heightMultiplier: Float,
        maxDimension: Int,
        maxPixels: Long
    ): Pair<Int, Int> {
        var width = max(1.0, sourceWidth * widthMultiplier.toDouble())
        var height = max(1.0, sourceHeight * heightMultiplier.toDouble())
        var factor = min(1.0, maxDimension / max(width, height))
        val pixels = width * height
        if (pixels * factor * factor > maxPixels) {
            factor = min(factor, sqrt(maxPixels.toDouble() / pixels))
        }
        width = max(1.0, width * factor)
        height = max(1.0, height * factor)
        return width.roundToInt() to height.roundToInt()
    }

    private fun updateScaleControls() {
        updatingControls = true
        try {
            val widthPercent = (widthScale * 100).roundToInt()
            val heightPercent = (heightScale * 100).roundToInt()
            seekBarWidth.progress = widthPercent.coerceIn(0, seekBarWidth.max)
            seekBarHeight.progress = heightPercent.coerceIn(0, seekBarHeight.max)
            tvWidthValue.text = getString(R.string.percent_value, widthPercent)
            tvHeightValue.text = getString(R.string.percent_value, heightPercent)
        } finally {
            updatingControls = false
        }
    }

    private fun replaceOriginalBitmap(bitmap: Bitmap) {
        clearBitmaps()
        originalBitmap = bitmap
        stretchedBitmap = bitmap
        imageView.setImageBitmap(bitmap)
        widthScale = 1f
        heightScale = 1f
        updateScaleControls()
        etWidth.setText(getString(R.string.number_value, bitmap.width))
        etHeight.setText(getString(R.string.number_value, bitmap.height))
        updateSizeInfo()
    }

    private fun replaceStretchedBitmap(bitmap: Bitmap) {
        val previous = stretchedBitmap
        stretchedBitmap = bitmap
        if (previous != null && previous !== originalBitmap && previous !== bitmap && !previous.isRecycled) {
            previous.recycle()
        }
        imageView.setImageBitmap(bitmap)
    }

    private fun clearBitmaps() {
        val oldOriginal = originalBitmap
        val oldStretched = stretchedBitmap
        originalBitmap = null
        stretchedBitmap = null
        imageView.setImageDrawable(null)
        if (oldStretched != null && oldStretched !== oldOriginal && !oldStretched.isRecycled) {
            oldStretched.recycle()
        }
        if (oldOriginal != null && !oldOriginal.isRecycled) oldOriginal.recycle()
    }

    private fun updateSizeInfo() {
        val original = originalBitmap ?: return
        val current = stretchedBitmap ?: return
        tvCurrentSize.text = getString(
            R.string.size_info,
            original.width,
            original.height,
            current.width,
            current.height
        )
    }

    private fun saveImageToAnyFolder() {
        if (stretchedBitmap == null) {
            Toast.makeText(this, R.string.no_image_save, Toast.LENGTH_SHORT).show()
            return
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/jpeg"
            putExtra(Intent.EXTRA_TITLE, "stretched_$timeStamp.jpg")
        }
        saveFileLauncher.launch(intent)
    }

    private fun saveBitmapToUri(bitmap: Bitmap?, uri: Uri): Boolean {
        if (bitmap == null) return false
        return try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun shareImage() {
        val bitmap = stretchedBitmap
        if (bitmap == null) {
            Toast.makeText(this, R.string.no_image_share, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val cacheDir = File(cacheDir, "shared_images").apply { mkdirs() }
            cacheDir.listFiles()?.forEach { it.delete() }
            val imageFile = File(
                cacheDir,
                "share_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
            )
            FileOutputStream(imageFile).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                    throw IOException("compression failed")
                }
            }
            tempImageFile = imageFile

            val contentUri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.provider",
                imageFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, contentUri)
                type = "image/jpeg"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_chooser)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.share_error, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        loadGeneration++
        if (::imageExecutor.isInitialized) imageExecutor.shutdownNow()
        tempImageFile?.delete()
        if (::imageView.isInitialized) clearBitmaps()
        super.onDestroy()
    }

    companion object {
        private const val PREFERENCES_NAME = "photo_stretching_preferences"
        private const val THEME_KEY = "app_theme"
        private const val THEME_CURRENT_ID = 1
        private const val THEME_DARK_ID = 2
        private const val THEME_BURGUNDY_ID = 3
        private const val MAX_IMAGE_DIMENSION = 4096
        private const val MAX_IMAGE_PIXELS = 16_777_216L
        private const val MAX_OUTPUT_DIMENSION = 8192
        private const val MAX_OUTPUT_PIXELS = 25_000_000L
    }
}
