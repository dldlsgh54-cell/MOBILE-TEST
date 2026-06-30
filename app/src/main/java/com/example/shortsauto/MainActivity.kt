package com.example.shortsauto

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileOutputStream
import kotlin.math.floor

class MainActivity : ComponentActivity() {
    private val selectedImages = mutableStateListOf<Uri>()
    private var colsText by mutableStateOf("4")
    private var rowsText by mutableStateOf("2")
    private var aspectRatio by mutableStateOf("auto")
    private var resolution by mutableStateOf("original")
    private var saveFolderName by mutableStateOf("ShortsAutoSplit")
    private var statusText by mutableStateOf("갤러리에서 통합 이미지를 선택하세요.")
    private var progressValue by mutableFloatStateOf(0f)
    private var isSaving by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShortsAutoTheme {
                SplitOnlyUi(
                    selectedCount = selectedImages.size,
                    colsText = colsText,
                    rowsText = rowsText,
                    aspectRatio = aspectRatio,
                    resolution = resolution,
                    saveFolderName = saveFolderName,
                    statusText = statusText,
                    progressValue = progressValue,
                    isSaving = isSaving,
                    onImagesSelected = {
                        selectedImages.clear()
                        selectedImages.addAll(it)
                        statusText = "${it.size}개 통합 이미지를 선택했습니다."
                        progressValue = 0f
                    },
                    onColsChange = { colsText = it.filter(Char::isDigit).take(2) },
                    onRowsChange = { rowsText = it.filter(Char::isDigit).take(2) },
                    onAspectRatioChange = { aspectRatio = it },
                    onResolutionChange = { resolution = it },
                    onSaveFolderChange = { saveFolderName = sanitizeFolderName(it) },
                    onRunSplit = { splitSelectedImages() }
                )
            }
        }
    }

    private fun splitSelectedImages() {
        if (selectedImages.isEmpty()) {
            Toast.makeText(this, "먼저 갤러리에서 이미지를 선택하세요.", Toast.LENGTH_SHORT).show()
            return
        }

        val cols = colsText.toIntOrNull()?.coerceIn(1, 20) ?: 4
        val rows = rowsText.toIntOrNull()?.coerceIn(1, 20) ?: 2
        colsText = cols.toString()
        rowsText = rows.toString()
        val folderName = sanitizeFolderName(saveFolderName.ifBlank { "ShortsAutoSplit" })
        saveFolderName = folderName

        isSaving = true
        progressValue = 0f
        statusText = "이미지 분할 저장을 시작합니다."

        Thread {
            val totalPieces = selectedImages.size * cols * rows
            var savedCount = 0
            var failedCount = 0

            selectedImages.forEachIndexed { sourceIndex, uri ->
                val sourceBitmap = loadBitmap(uri)
                if (sourceBitmap == null) {
                    failedCount++
                    updateProgress(savedCount, totalPieces, "이미지 ${sourceIndex + 1}을 읽지 못했습니다.")
                    return@forEachIndexed
                }

                try {
                    splitBitmap(sourceBitmap, cols, rows).forEachIndexed { pieceIndex, bitmap ->
                        val fileName = "${sourceIndex + 1}-${pieceIndex + 1}.png"
                        if (saveBitmapToGallery(bitmap, folderName, fileName)) {
                            savedCount++
                        } else {
                            failedCount++
                        }
                        bitmap.recycle()
                        updateProgress(savedCount, totalPieces, "${savedCount}/${totalPieces}개 저장 중")
                    }
                } finally {
                    sourceBitmap.recycle()
                }
            }

            runOnUiThread {
                isSaving = false
                progressValue = 1f
                statusText = if (failedCount == 0) {
                    "${savedCount}개 이미지를 갤러리의 ${folderName} 폴더에 저장했습니다."
                } else {
                    "${savedCount}개 저장, ${failedCount}개 실패했습니다."
                }
                Toast.makeText(this, statusText, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun updateProgress(savedCount: Int, totalPieces: Int, message: String) {
        runOnUiThread {
            progressValue = if (totalPieces == 0) 0f else savedCount.toFloat() / totalPieces
            statusText = message
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        }.getOrNull()
    }

    private fun splitBitmap(source: Bitmap, cols: Int, rows: Int): List<Bitmap> {
        val gap = 0
        val margin = 0
        val cellW = ((source.width - margin * 2 - gap * (cols - 1)) / cols).coerceAtLeast(1)
        val cellH = ((source.height - margin * 2 - gap * (rows - 1)) / rows).coerceAtLeast(1)
        val ratio = ratioToNumber(aspectRatio)
        val crop = fitCropToRatio(cellW, cellH, ratio)
        val pieces = mutableListOf<Bitmap>()

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val x = margin + col * (cellW + gap) + crop.offsetX
                val y = margin + row * (cellH + gap) + crop.offsetY
                val cropped = Bitmap.createBitmap(source, x, y, crop.width, crop.height)
                val outputSize = resolveOutputSize(crop.width, crop.height)
                val output = if (outputSize.width == cropped.width && outputSize.height == cropped.height) {
                    cropped
                } else {
                    val scaled = Bitmap.createScaledBitmap(cropped, outputSize.width, outputSize.height, true)
                    cropped.recycle()
                    scaled
                }
                pieces.add(output)
            }
        }
        return pieces
    }

    private fun ratioToNumber(value: String): Float? {
        if (value == "auto") return null
        val parts = value.split(":").mapNotNull { it.toFloatOrNull() }
        if (parts.size != 2 || parts[1] == 0f) return null
        return parts[0] / parts[1]
    }

    private fun fitCropToRatio(width: Int, height: Int, ratio: Float?): CropRect {
        if (ratio == null) return CropRect(width, height, 0, 0)
        val current = width.toFloat() / height
        return if (current > ratio) {
            val targetW = floor(height * ratio).toInt().coerceAtLeast(1)
            CropRect(targetW, height, (width - targetW) / 2, 0)
        } else {
            val targetH = floor(width / ratio).toInt().coerceAtLeast(1)
            CropRect(width, targetH, 0, (height - targetH) / 2)
        }
    }

    private fun resolveOutputSize(width: Int, height: Int): OutputSize {
        if (resolution == "original") return OutputSize(width, height)
        val ratio = ratioToNumber(aspectRatio) ?: (width.toFloat() / height)
        val portrait = ratio <= 1f
        val longEdge = if (resolution == "4k") 3840 else 2560
        val shortEdge = if (resolution == "4k") 2160 else 1440
        return if (portrait) OutputSize(shortEdge, longEdge) else OutputSize(longEdge, shortEdge)
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, folderName: String, fileName: String): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val targetDir = File(pictures, folderName).apply { mkdirs() }
                val targetFile = File(targetDir, fileName)
                FileOutputStream(targetFile).use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
                sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(targetFile)))
                return@runCatching true
            }

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$folderName")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = contentResolver.insert(collection, values) ?: return false
            contentResolver.openOutputStream(uri)?.use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            } ?: return false

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            true
        }.getOrDefault(false)
    }

    private fun sanitizeFolderName(value: String): String {
        return value.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
    }

    private data class CropRect(val width: Int, val height: Int, val offsetX: Int, val offsetY: Int)
    private data class OutputSize(val width: Int, val height: Int)
}

@Composable
private fun ShortsAutoTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

@Composable
private fun SplitOnlyUi(
    selectedCount: Int,
    colsText: String,
    rowsText: String,
    aspectRatio: String,
    resolution: String,
    saveFolderName: String,
    statusText: String,
    progressValue: Float,
    isSaving: Boolean,
    onImagesSelected: (List<Uri>) -> Unit,
    onColsChange: (String) -> Unit,
    onRowsChange: (String) -> Unit,
    onAspectRatioChange: (String) -> Unit,
    onResolutionChange: (String) -> Unit,
    onSaveFolderChange: (String) -> Unit,
    onRunSplit: () -> Unit
) {
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) onImagesSelected(uris)
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("통합 이미지 분할", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("갤러리에서 통합 이미지를 선택하면 순서대로 잘라 갤러리에 저장합니다.")

            Button(
                onClick = { imagePicker.launch("image/*") },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (selectedCount == 0) "갤러리에서 이미지 선택" else "선택된 이미지 ${selectedCount}개")
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = colsText,
                            onValueChange = onColsChange,
                            label = { Text("가로") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = rowsText,
                            onValueChange = onRowsChange,
                            label = { Text("세로") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OptionRow(
                        title = "비율",
                        value = aspectRatio,
                        options = listOf("auto", "9:16", "16:9", "1:1"),
                        labels = listOf("원본", "9:16", "16:9", "1:1"),
                        onChange = onAspectRatioChange,
                        enabled = !isSaving
                    )
                    OptionRow(
                        title = "해상도",
                        value = resolution,
                        options = listOf("original", "2k", "4k"),
                        labels = listOf("원본", "2K", "4K"),
                        onChange = onResolutionChange,
                        enabled = !isSaving
                    )
                    OutlinedTextField(
                        value = saveFolderName,
                        onValueChange = onSaveFolderChange,
                        label = { Text("갤러리 저장 폴더명") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Button(onClick = onRunSplit, enabled = !isSaving, modifier = Modifier.fillMaxWidth()) {
                Text(if (isSaving) "저장 중" else "분할해서 갤러리에 저장")
            }
            LinearProgressIndicator(progress = { progressValue.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text(statusText)
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    value: String,
    options: List<String>,
    labels: List<String>,
    onChange: (String) -> Unit,
    enabled: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                val selected = option == value
                val label = labels.getOrElse(index) { option }
                if (selected) {
                    Button(
                        onClick = { onChange(option) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f)
                    ) { Text(label) }
                } else {
                    OutlinedButton(
                        onClick = { onChange(option) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f)
                    ) { Text(label) }
                }
            }
        }
    }
}
