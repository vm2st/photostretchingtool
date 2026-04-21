package com.vm2st.photostretchingtool

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import android.provider.DocumentsContract

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

    private var tempImageFile: File? = null

    private val pickImageLauncher: ActivityResultLauncher<String> = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { imageUri ->
            loadImage(imageUri)
        }
    }

    // Для сохранения файла куда угодно
    private val saveFileLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                saveBitmapToUri(stretchedBitmap, uri)
                Toast.makeText(this, "Файл сохранен!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
    }

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
        btnShare = findViewById(R.id.btnShare) // Новая кнопка
    }

    private fun setupListeners() {
        btnLoadImage.setOnClickListener { openImagePicker() }
        btnSaveImage.setOnClickListener { saveImageToAnyFolder() }
        btnApplySize.setOnClickListener { applyCustomSize() }
        btnShare.setOnClickListener { shareImage() } // Обработчик для кнопки "Поделиться"

        seekBarWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                widthScale = progress / 100.0f
                tvWidthValue.text = "$progress%"
                stretchImage()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekBarHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                heightScale = progress / 100.0f
                tvHeightValue.text = "$progress%"
                stretchImage()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun openImagePicker() {
        pickImageLauncher.launch("image/*")
    }

    private fun loadImage(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            originalBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            stretchedBitmap = originalBitmap?.copy(Bitmap.Config.ARGB_8888, true)
            imageView.setImageBitmap(originalBitmap)
            updateSizeInfo()

            seekBarWidth.progress = 100
            seekBarHeight.progress = 100
            widthScale = 1.0f
            heightScale = 1.0f
            tvWidthValue.text = "100%"
            tvHeightValue.text = "100%"

            originalBitmap?.let {
                etWidth.setText(it.width.toString())
                etHeight.setText(it.height.toString())
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Ошибка загрузки изображения", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stretchImage() {
        if (originalBitmap == null) {
            Toast.makeText(this, "Сначала загрузите изображение", Toast.LENGTH_SHORT).show()
            return
        }

        val original = originalBitmap ?: return
        val newWidth = (original.width * widthScale).toInt()
        val newHeight = (original.height * heightScale).toInt()

        val finalWidth = newWidth.coerceAtLeast(1)
        val finalHeight = newHeight.coerceAtLeast(1)

        stretchedBitmap = Bitmap.createScaledBitmap(original, finalWidth, finalHeight, true)
        imageView.setImageBitmap(stretchedBitmap)
        updateSizeInfo()
    }

    private fun applyCustomSize() {
        if (originalBitmap == null) {
            Toast.makeText(this, "Сначала загрузите изображение", Toast.LENGTH_SHORT).show()
            return
        }

        val widthStr = etWidth.text.toString()
        val heightStr = etHeight.text.toString()

        if (widthStr.isEmpty() || heightStr.isEmpty()) {
            Toast.makeText(this, "Введите ширину и высоту", Toast.LENGTH_SHORT).show()
            return
        }

        val newWidth = widthStr.toIntOrNull()
        val newHeight = heightStr.toIntOrNull()

        if (newWidth == null || newHeight == null) {
            Toast.makeText(this, "Введите корректные числа", Toast.LENGTH_SHORT).show()
            return
        }

        if (newWidth <= 0 || newHeight <= 0) {
            Toast.makeText(this, "Размеры должны быть больше 0", Toast.LENGTH_SHORT).show()
            return
        }

        val original = originalBitmap ?: return
        stretchedBitmap = Bitmap.createScaledBitmap(original, newWidth, newHeight, true)
        imageView.setImageBitmap(stretchedBitmap)

        val widthPercent = (newWidth * 100.0f) / original.width
        val heightPercent = (newHeight * 100.0f) / original.height

        seekBarWidth.progress = widthPercent.toInt()
        seekBarHeight.progress = heightPercent.toInt()
        tvWidthValue.text = "${widthPercent.toInt()}%"
        tvHeightValue.text = "${heightPercent.toInt()}%"

        updateSizeInfo()
    }

    private fun updateSizeInfo() {
        if (stretchedBitmap != null && originalBitmap != null) {
            val info = """
                Исходный: ${originalBitmap!!.width}x${originalBitmap!!.height}px
                Текущий: ${stretchedBitmap!!.width}x${stretchedBitmap!!.height}px
            """.trimIndent()
            tvCurrentSize.text = info
        }
    }

    // ФУНКЦИЯ 1: Сохранить в любую папку (через системный диалог)
    private fun saveImageToAnyFolder() {
        if (stretchedBitmap == null) {
            Toast.makeText(this, "Нет изображения для сохранения", Toast.LENGTH_SHORT).show()
            return
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "stretched_${timeStamp}.jpg"

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/jpeg"
            putExtra(Intent.EXTRA_TITLE, fileName)

            // Можно добавить начальную папку (Download)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString()))
            }
        }

        saveFileLauncher.launch(intent)
    }

    // ФУНКЦИЯ 2: Сохранить Bitmap в выбранный URI
    private fun saveBitmapToUri(bitmap: Bitmap?, uri: Uri) {
        if (bitmap == null) return

        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ФУНКЦИЯ 3: Поделиться изображением
    private fun shareImage() {
        if (stretchedBitmap == null) {
            Toast.makeText(this, "Нет изображения для отправки", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Создаем временный файл в кэше приложения
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "share_${timeStamp}.jpg"

            val cacheDir = File(cacheDir, "shared_images")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            val imageFile = File(cacheDir, fileName)
            val fos = FileOutputStream(imageFile)
            stretchedBitmap!!.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            fos.close()

            tempImageFile = imageFile

            // Получаем URI через FileProvider
            val contentUri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.provider",
                imageFile
            )

            // Создаем Intent для отправки
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, contentUri)
                type = "image/jpeg"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // Создаем chooser для выбора приложения
            val chooserIntent = Intent.createChooser(shareIntent, "Поделиться изображением")
            startActivity(chooserIntent)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Ошибка при отправке: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Очищаем временные файлы при уничтожении Activity
    override fun onDestroy() {
        super.onDestroy()
        tempImageFile?.delete()
    }
}