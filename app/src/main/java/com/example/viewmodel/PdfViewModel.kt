package com.example.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.database.ExtractionRecord
import com.example.database.ExtractionRepository
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

sealed class UiEvent {
    data class ShowToast(val message: String) : UiEvent()
    data class OperationSuccess(val summary: String, val filePath: String?, val actionType: String) : UiEvent()
}

class PdfViewModel(private val repository: ExtractionRepository) : ViewModel() {

    // UI state
    private val _selectedFileUri = MutableStateFlow<Uri?>(null)
    val selectedFileUri: StateFlow<Uri?> = _selectedFileUri.asStateFlow()

    private val _selectedFileName = MutableStateFlow<String?>(null)
    val selectedFileName: StateFlow<String?> = _selectedFileName.asStateFlow()

    private val _selectedFileSize = MutableStateFlow<Long>(0L)
    val selectedFileSize: StateFlow<Long> = _selectedFileSize.asStateFlow()

    private val _pageCount = MutableStateFlow<Int>(0)
    val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    private val _isPasswordProtected = MutableStateFlow<Boolean>(false)
    val isPasswordProtected: StateFlow<Boolean> = _isPasswordProtected.asStateFlow()

    private val _isProcessing = MutableStateFlow<Boolean>(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _eventFlow = MutableSharedFlow<UiEvent>()
    val eventFlow: SharedFlow<UiEvent> = _eventFlow.asSharedFlow()

    // New toolbox states
    private val _selectedMergeFiles = MutableStateFlow<List<Pair<Uri, String>>>(emptyList())
    val selectedMergeFiles: StateFlow<List<Pair<Uri, String>>> = _selectedMergeFiles.asStateFlow()

    private val _selectedImagesForPdf = MutableStateFlow<List<Pair<Uri, String>>>(emptyList())
    val selectedImagesForPdf: StateFlow<List<Pair<Uri, String>>> = _selectedImagesForPdf.asStateFlow()

    private val _pdfTitle = MutableStateFlow("")
    val pdfTitle: StateFlow<String> = _pdfTitle.asStateFlow()

    private val _pdfAuthor = MutableStateFlow("")
    val pdfAuthor: StateFlow<String> = _pdfAuthor.asStateFlow()

    private val _pdfSubject = MutableStateFlow("")
    val pdfSubject: StateFlow<String> = _pdfSubject.asStateFlow()

    private val _pdfKeywords = MutableStateFlow("")
    val pdfKeywords: StateFlow<String> = _pdfKeywords.asStateFlow()

    private val _pdfCreator = MutableStateFlow("")
    val pdfCreator: StateFlow<String> = _pdfCreator.asStateFlow()

    // History Records
    val historyRecords: StateFlow<List<ExtractionRecord>> = repository.allRecords
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun selectFile(context: Context, uri: Uri) {
        _selectedFileUri.value = uri
        _isProcessing.value = true
        _isPasswordProtected.value = false
        _pageCount.value = 0

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Get meta info
                var fileName = "document.pdf"
                var fileSize = 0L

                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                        if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                    }
                }

                _selectedFileName.value = fileName
                _selectedFileSize.value = fileSize

                // Copy to a temp file to read with PDFBox and verify encryption
                val tempFile = File(context.cacheDir, "temp_check.pdf")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                try {
                    // Try to load without password
                    val doc = PDDocument.load(tempFile)
                    _pageCount.value = doc.numberOfPages
                    _isPasswordProtected.value = doc.isEncrypted
                    
                    val info = doc.documentInformation
                    _pdfTitle.value = info.title ?: ""
                    _pdfAuthor.value = info.author ?: ""
                    _pdfSubject.value = info.subject ?: ""
                    _pdfKeywords.value = info.keywords ?: ""
                    _pdfCreator.value = info.creator ?: ""
                    
                    doc.close()
                } catch (e: Exception) {
                    // It failed to load or requires password
                    _isPasswordProtected.value = true
                    // Try to extract page count via native PdfRenderer which doesn't always fail on unencrypted metadata,
                    // or let it be 0 until correct password is typed.
                    try {
                        val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                        val renderer = PdfRenderer(pfd)
                        _pageCount.value = renderer.pageCount
                        renderer.close()
                        pfd.close()
                    } catch (ex: Exception) {
                        Log.e("PdfViewModel", "Native renderer also failed to read page count", ex)
                    }
                }
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Failed to analyze selected PDF", e)
                _eventFlow.emit(UiEvent.ShowToast("解析 PDF 失败: ${e.localizedMessage}"))
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun clearSelectedFile() {
        _selectedFileUri.value = null
        _selectedFileName.value = null
        _selectedFileSize.value = 0L
        _pageCount.value = 0
        _isPasswordProtected.value = false
        _pdfTitle.value = ""
        _pdfAuthor.value = ""
        _pdfSubject.value = ""
        _pdfKeywords.value = ""
        _pdfCreator.value = ""
    }

    // Task 1: Extract Text
    fun extractText(context: Context, password: String = "") {
        val uri = _selectedFileUri.value ?: return
        val fileName = _selectedFileName.value ?: "document.pdf"
        _isProcessing.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tempFile = copyUriToTempFile(context, uri)
                val document = try {
                    if (password.isNotEmpty()) {
                        PDDocument.load(tempFile, password)
                    } else {
                        PDDocument.load(tempFile)
                    }
                } catch (e: Exception) {
                    _eventFlow.emit(UiEvent.ShowToast("打开 PDF 失败，可能密码错误"))
                    return@launch
                }

                val stripper = PDFTextStripper()
                val text = stripper.getText(document)
                document.close()

                if (text.isNullOrBlank()) {
                    _eventFlow.emit(UiEvent.ShowToast("此 PDF 中没有检测到可提取的文本内容"))
                    return@launch
                }

                // Save to output folder
                val baseName = fileName.substringBeforeLast(".")
                val outputFolder = File(context.getExternalFilesDir("extracted_text"), "${baseName}_txt")
                outputFolder.mkdirs()
                val txtFile = File(outputFolder, "${baseName}_提取文本.txt")
                txtFile.writeText(text)

                val summary = "成功提取 ${text.length} 个字符"
                
                // Save history
                val record = ExtractionRecord(
                    fileName = fileName,
                    actionType = "TEXT",
                    resultSummary = summary,
                    filePath = txtFile.absolutePath
                )
                repository.insert(record)

                _eventFlow.emit(UiEvent.OperationSuccess(summary, txtFile.absolutePath, "TEXT"))
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Text extraction failed", e)
                _eventFlow.emit(UiEvent.ShowToast("提取文本失败: ${e.localizedMessage}"))
            } finally {
                _isProcessing.value = false
            }
        }
    }

    // Task 2: Render whole pages to images
    fun renderPagesToImages(context: Context, password: String = "") {
        val uri = _selectedFileUri.value ?: return
        val fileName = _selectedFileName.value ?: "document.pdf"
        _isProcessing.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tempFile = copyUriToTempFile(context, uri)
                
                // Verify password if encrypted
                if (_isPasswordProtected.value) {
                    try {
                        val doc = PDDocument.load(tempFile, password)
                        doc.close()
                    } catch (e: Exception) {
                        _eventFlow.emit(UiEvent.ShowToast("密码校验失败，无法渲染页面"))
                        return@launch
                    }
                }

                val baseName = fileName.substringBeforeLast(".")
                val outputFolder = File(context.getExternalFilesDir("extracted_images"), "${baseName}_全页_${System.currentTimeMillis().hashCode().coerceAtLeast(0)}")
                outputFolder.mkdirs()

                val parcelFileDescriptor = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val pdfRenderer = PdfRenderer(parcelFileDescriptor)
                val totalPages = pdfRenderer.pageCount

                val savedPaths = mutableListOf<String>()
                for (i in 0 until totalPages) {
                    val page = pdfRenderer.openPage(i)
                    // Scale for high quality (density ratio 2x)
                    val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE) // default white background
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    
                    val imageFile = File(outputFolder, "page_${i + 1}.png")
                    FileOutputStream(imageFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    bitmap.recycle()
                    savedPaths.add(imageFile.absolutePath)
                    page.close()
                }
                pdfRenderer.close()
                parcelFileDescriptor.close()

                val summary = "成功将 ${totalPages} 页渲染为高质PNG图片"
                
                // Save history
                val record = ExtractionRecord(
                    fileName = fileName,
                    actionType = "IMAGES_FULL",
                    resultSummary = summary,
                    filePath = outputFolder.absolutePath
                )
                repository.insert(record)

                _eventFlow.emit(UiEvent.OperationSuccess(summary, outputFolder.absolutePath, "IMAGES_FULL"))
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Render pages failed", e)
                _eventFlow.emit(UiEvent.ShowToast("页面转换图片失败: ${e.localizedMessage}"))
            } finally {
                _isProcessing.value = false
            }
        }
    }

    // Task 3: Extract embedded images
    fun extractEmbeddedImages(context: Context, password: String = "") {
        val uri = _selectedFileUri.value ?: return
        val fileName = _selectedFileName.value ?: "document.pdf"
        _isProcessing.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tempFile = copyUriToTempFile(context, uri)
                val document = try {
                    if (password.isNotEmpty()) {
                        PDDocument.load(tempFile, password)
                    } else {
                        PDDocument.load(tempFile)
                    }
                } catch (e: Exception) {
                    _eventFlow.emit(UiEvent.ShowToast("打开 PDF 失败，可能密码错误"))
                    return@launch
                }

                val baseName = fileName.substringBeforeLast(".")
                val outputFolder = File(context.getExternalFilesDir("extracted_images"), "${baseName}_素材_${System.currentTimeMillis().hashCode().coerceAtLeast(0)}")
                outputFolder.mkdirs()

                var imageCount = 0
                val totalPages = document.numberOfPages

                for (pageIndex in 0 until totalPages) {
                    val page = document.getPage(pageIndex)
                    val resources = page.resources ?: continue
                    for (name in resources.xObjectNames) {
                        if (resources.isImageXObject(name)) {
                            val xObject = resources.getXObject(name)
                            if (xObject is PDImageXObject) {
                                val bitmap = xObject.image
                                imageCount++
                                val imgFile = File(outputFolder, "img_p${pageIndex + 1}_$imageCount.png")
                                FileOutputStream(imgFile).use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                }
                            }
                        }
                    }
                }
                document.close()

                if (imageCount == 0) {
                    _eventFlow.emit(UiEvent.ShowToast("没有在 PDF 中检测到内嵌的独立图片素材"))
                    return@launch
                }

                val summary = "成功提取出 ${imageCount} 张内嵌素材图片"
                
                // Save history
                val record = ExtractionRecord(
                    fileName = fileName,
                    actionType = "IMAGES_EMBEDDED",
                    resultSummary = summary,
                    filePath = outputFolder.absolutePath
                )
                repository.insert(record)

                _eventFlow.emit(UiEvent.OperationSuccess(summary, outputFolder.absolutePath, "IMAGES_EMBEDDED"))
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Embedded image extraction failed", e)
                _eventFlow.emit(UiEvent.ShowToast("提取素材图片失败: ${e.localizedMessage}"))
            } finally {
                _isProcessing.value = false
            }
        }
    }

    // Task 4: Split / Extract selected pages
    fun splitPdfPages(context: Context, pageRangeStr: String, password: String = "") {
        val uri = _selectedFileUri.value ?: return
        val fileName = _selectedFileName.value ?: "document.pdf"
        _isProcessing.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tempFile = copyUriToTempFile(context, uri)
                val sourceDoc = try {
                    if (password.isNotEmpty()) {
                        PDDocument.load(tempFile, password)
                    } else {
                        PDDocument.load(tempFile)
                    }
                } catch (e: Exception) {
                    _eventFlow.emit(UiEvent.ShowToast("打开 PDF 失败，密码错误"))
                    return@launch
                }

                val totalPages = sourceDoc.numberOfPages
                val selectedPages = parsePageRange(pageRangeStr, totalPages)

                if (selectedPages.isEmpty()) {
                    _eventFlow.emit(UiEvent.ShowToast("请输入有效的页面范围 (例如: 1-3, 5)"))
                    sourceDoc.close()
                    return@launch
                }

                val destinationDoc = PDDocument()
                for (pageIndex in selectedPages) {
                    // importPage clones page elements and resources safely
                    destinationDoc.importPage(sourceDoc.getPage(pageIndex))
                }

                val baseName = fileName.substringBeforeLast(".")
                val outputFolder = File(context.getExternalFilesDir("extracted_split"), "split_pdfs")
                outputFolder.mkdirs()
                val splitFile = File(outputFolder, "${baseName}_拆分.pdf")
                
                destinationDoc.save(splitFile)
                destinationDoc.close()
                sourceDoc.close()

                val summary = "成功将 ${selectedPages.size} 页拆分并保存为新PDF"
                
                // Save history
                val record = ExtractionRecord(
                    fileName = fileName,
                    actionType = "SPLIT",
                    resultSummary = summary,
                    filePath = splitFile.absolutePath
                )
                repository.insert(record)

                _eventFlow.emit(UiEvent.OperationSuccess(summary, splitFile.absolutePath, "SPLIT"))
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Split PDF failed", e)
                _eventFlow.emit(UiEvent.ShowToast("拆分 PDF 失败: ${e.localizedMessage}"))
            } finally {
                _isProcessing.value = false
            }
        }
    }

    // Task 5: Decrypt PDF (Remove Password)
    fun decryptPdf(context: Context, password: String) {
        val uri = _selectedFileUri.value ?: return
        val fileName = _selectedFileName.value ?: "document.pdf"
        _isProcessing.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tempFile = copyUriToTempFile(context, uri)
                val sourceDoc = try {
                    PDDocument.load(tempFile, password)
                } catch (e: Exception) {
                    _eventFlow.emit(UiEvent.ShowToast("密码不正确，无法解密"))
                    return@launch
                }

                // Remove security features
                sourceDoc.setAllSecurityToBeRemoved(true)

                val baseName = fileName.substringBeforeLast(".")
                val outputFolder = File(context.getExternalFilesDir("extracted_unlocked"), "unlocked_pdfs")
                outputFolder.mkdirs()
                val unlockedFile = File(outputFolder, "${baseName}_已解密.pdf")

                sourceDoc.save(unlockedFile)
                sourceDoc.close()

                val summary = "成功移除密码限制，保存为无密PDF"
                
                // Save history
                val record = ExtractionRecord(
                    fileName = fileName,
                    actionType = "UNLOCKED",
                    resultSummary = summary,
                    filePath = unlockedFile.absolutePath
                )
                repository.insert(record)

                _eventFlow.emit(UiEvent.OperationSuccess(summary, unlockedFile.absolutePath, "UNLOCKED"))
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Decrypt PDF failed", e)
                _eventFlow.emit(UiEvent.ShowToast("解密 PDF 失败: ${e.localizedMessage}"))
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun deleteHistoryRecord(record: ExtractionRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Also delete the physical files if possible
                record.filePath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        if (file.isDirectory) {
                            file.deleteRecursively()
                        } else {
                            file.delete()
                        }
                    }
                }
                repository.deleteById(record.id)
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Failed to delete history record", e)
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                for (record in historyRecords.value) {
                    record.filePath?.let { path ->
                        val file = File(path)
                        if (file.exists()) {
                            if (file.isDirectory) {
                                file.deleteRecursively()
                            } else {
                                file.delete()
                            }
                        }
                    }
                }
                repository.deleteAll()
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Failed to clear history", e)
            }
        }
    }

    // Helper functions
    private suspend fun copyUriToTempFile(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "temp_process.pdf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw Exception("无法读取选择的文件")
        tempFile
    }

    private fun parsePageRange(rangeStr: String, maxPages: Int): List<Int> {
        val pages = mutableSetOf<Int>()
        val parts = rangeStr.split(",")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.contains("-")) {
                val bounds = trimmed.split("-")
                if (bounds.size == 2) {
                    val start = bounds[0].trim().toIntOrNull()
                    val end = bounds[1].trim().toIntOrNull()
                    if (start != null && end != null) {
                        val low = start.coerceIn(1, maxPages)
                        val high = end.coerceIn(1, maxPages)
                        val rStart = minOf(low, high)
                        val rEnd = maxOf(low, high)
                        for (i in rStart..rEnd) {
                            pages.add(i - 1) // 0-indexed
                        }
                    }
                }
            } else {
                val page = trimmed.toIntOrNull()
                if (page != null && page in 1..maxPages) {
                    pages.add(page - 1) // 0-indexed
                }
            }
        }
        return pages.toList().sorted()
    }

    // --- NEW MULTI-FUNCTIONAL PDF TOOLBOX METHODS ---

    fun addMergeFile(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var fileName = "document.pdf"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex != -1) {
                        fileName = cursor.getString(nameIndex)
                    }
                }
                _selectedMergeFiles.value = _selectedMergeFiles.value + Pair(uri, fileName)
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Failed to add merge file", e)
            }
        }
    }

    fun removeMergeFile(index: Int) {
        val current = _selectedMergeFiles.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _selectedMergeFiles.value = current
        }
    }

    fun clearMergeFiles() {
        _selectedMergeFiles.value = emptyList()
    }

    fun mergePdfFiles(context: Context) {
        val files = _selectedMergeFiles.value
        if (files.size < 2) {
            viewModelScope.launch {
                _eventFlow.emit(UiEvent.ShowToast("请至少选择两个 PDF 文件进行合并"))
            }
            return
        }
        _isProcessing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val destinationDoc = PDDocument()
                val tempFiles = mutableListOf<File>()
                
                for (i in files.indices) {
                    val uri = files[i].first
                    val tempFile = File(context.cacheDir, "temp_merge_${System.currentTimeMillis().hashCode().coerceAtLeast(0)}_$i.pdf")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempFiles.add(tempFile)
                    
                    val doc = PDDocument.load(tempFile)
                    for (pageIndex in 0 until doc.numberOfPages) {
                        destinationDoc.importPage(doc.getPage(pageIndex))
                    }
                    doc.close()
                }
                
                val outputFolder = File(context.getExternalFilesDir("extracted_merge"), "merged_pdfs")
                outputFolder.mkdirs()
                val mergedFile = File(outputFolder, "Merged_${System.currentTimeMillis().hashCode().coerceAtLeast(0)}.pdf")
                destinationDoc.save(mergedFile)
                destinationDoc.close()
                
                // Cleanup
                for (f in tempFiles) {
                    f.delete()
                }
                
                val summary = "成功合并 ${files.size} 个 PDF 文件"
                val record = ExtractionRecord(
                    fileName = mergedFile.name,
                    actionType = "SPLIT",
                    resultSummary = summary,
                    filePath = mergedFile.absolutePath
                )
                repository.insert(record)
                
                _eventFlow.emit(UiEvent.OperationSuccess(summary, mergedFile.absolutePath, "SPLIT"))
                _selectedMergeFiles.value = emptyList()
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Merge PDF failed", e)
                _eventFlow.emit(UiEvent.ShowToast("合并 PDF 失败: ${e.localizedMessage}"))
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun addImageForPdf(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var fileName = "image.png"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex != -1) {
                        fileName = cursor.getString(nameIndex)
                    }
                }
                _selectedImagesForPdf.value = _selectedImagesForPdf.value + Pair(uri, fileName)
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Failed to add image for PDF", e)
            }
        }
    }

    fun removeImageForPdf(index: Int) {
        val current = _selectedImagesForPdf.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _selectedImagesForPdf.value = current
        }
    }

    fun clearImagesForPdf() {
        _selectedImagesForPdf.value = emptyList()
    }

    fun convertImagesToPdf(context: Context) {
        val images = _selectedImagesForPdf.value
        if (images.isEmpty()) {
            viewModelScope.launch {
                _eventFlow.emit(UiEvent.ShowToast("请选择至少一张图片进行转换"))
            }
            return
        }
        _isProcessing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pdfDoc = android.graphics.pdf.PdfDocument()
                
                for (i in images.indices) {
                    val uri = images[i].first
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        if (bitmap != null) {
                            val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, i + 1).create()
                            val page = pdfDoc.startPage(pageInfo)
                            val canvas = page.canvas
                            canvas.drawBitmap(bitmap, 0f, 0f, null)
                            pdfDoc.finishPage(page)
                            bitmap.recycle()
                        }
                    }
                }
                
                val outputFolder = File(context.getExternalFilesDir("extracted_images_to_pdf"), "converted_pdfs")
                outputFolder.mkdirs()
                val convertedFile = File(outputFolder, "ImagesToPdf_${System.currentTimeMillis().hashCode().coerceAtLeast(0)}.pdf")
                
                FileOutputStream(convertedFile).use { out ->
                    pdfDoc.writeTo(out)
                }
                pdfDoc.close()
                
                val summary = "成功将 ${images.size} 张图片转换为 PDF 电子书"
                val record = ExtractionRecord(
                    fileName = convertedFile.name,
                    actionType = "SPLIT",
                    resultSummary = summary,
                    filePath = convertedFile.absolutePath
                )
                repository.insert(record)
                
                _eventFlow.emit(UiEvent.OperationSuccess(summary, convertedFile.absolutePath, "SPLIT"))
                _selectedImagesForPdf.value = emptyList()
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Convert images to PDF failed", e)
                _eventFlow.emit(UiEvent.ShowToast("转换 PDF 失败: ${e.localizedMessage}"))
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun addWatermarkToPdf(context: Context, watermarkText: String, fontSize: Float, password: String = "") {
        val uri = _selectedFileUri.value ?: return
        val fileName = _selectedFileName.value ?: "document.pdf"
        if (watermarkText.trim().isEmpty()) {
            viewModelScope.launch {
                _eventFlow.emit(UiEvent.ShowToast("请输入水印内容"))
            }
            return
        }
        _isProcessing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tempFile = copyUriToTempFile(context, uri)
                val doc = try {
                    if (password.isNotEmpty()) {
                        PDDocument.load(tempFile, password)
                    } else {
                        PDDocument.load(tempFile)
                    }
                } catch (e: Exception) {
                    _eventFlow.emit(UiEvent.ShowToast("打开 PDF 失败，可能密码错误"))
                    return@launch
                }

                val font = PDType1Font.HELVETICA_BOLD
                val totalPages = doc.numberOfPages
                
                for (i in 0 until totalPages) {
                    val page = doc.getPage(i)
                    val contentStream = PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                    
                    contentStream.beginText()
                    contentStream.setFont(font, fontSize)
                    contentStream.setNonStrokingColor(220, 220, 220) // light gray
                    
                    val width = page.mediaBox.width
                    val height = page.mediaBox.height
                    
                    contentStream.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(45.0), width * 0.15f, height * 0.15f))
                    contentStream.showText(watermarkText)
                    contentStream.endText()
                    contentStream.close()
                }

                val baseName = fileName.substringBeforeLast(".")
                val outputFolder = File(context.getExternalFilesDir("extracted_watermark"), "watermarked_pdfs")
                outputFolder.mkdirs()
                val watermarkedFile = File(outputFolder, "${baseName}_加水印.pdf")
                doc.save(watermarkedFile)
                doc.close()

                val summary = "成功为 PDF 添加了文字水印"
                val record = ExtractionRecord(
                    fileName = watermarkedFile.name,
                    actionType = "SPLIT",
                    resultSummary = summary,
                    filePath = watermarkedFile.absolutePath
                )
                repository.insert(record)

                _eventFlow.emit(UiEvent.OperationSuccess(summary, watermarkedFile.absolutePath, "SPLIT"))
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Failed to add watermark", e)
                _eventFlow.emit(UiEvent.ShowToast("加水印失败: ${e.localizedMessage}"))
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun updatePdfMetadata(
        context: Context, 
        title: String, 
        author: String, 
        subject: String, 
        keywords: String, 
        password: String = ""
    ) {
        val uri = _selectedFileUri.value ?: return
        val fileName = _selectedFileName.value ?: "document.pdf"
        _isProcessing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tempFile = copyUriToTempFile(context, uri)
                val doc = try {
                    if (password.isNotEmpty()) {
                        PDDocument.load(tempFile, password)
                    } else {
                        PDDocument.load(tempFile)
                    }
                } catch (e: Exception) {
                    _eventFlow.emit(UiEvent.ShowToast("打开 PDF 失败，可能密码错误"))
                    return@launch
                }

                val info = doc.documentInformation
                info.title = title
                info.author = author
                info.subject = subject
                info.keywords = keywords
                info.creator = "PDF Toolbox"

                val baseName = fileName.substringBeforeLast(".")
                val outputFolder = File(context.getExternalFilesDir("extracted_metadata"), "metadata_pdfs")
                outputFolder.mkdirs()
                val modifiedFile = File(outputFolder, "${baseName}_修改属性.pdf")
                
                doc.save(modifiedFile)
                doc.close()

                _pdfTitle.value = title
                _pdfAuthor.value = author
                _pdfSubject.value = subject
                _pdfKeywords.value = keywords

                val summary = "成功修改 PDF 文档元属性/属性信息"
                val record = ExtractionRecord(
                    fileName = modifiedFile.name,
                    actionType = "SPLIT",
                    resultSummary = summary,
                    filePath = modifiedFile.absolutePath
                )
                repository.insert(record)

                _eventFlow.emit(UiEvent.OperationSuccess(summary, modifiedFile.absolutePath, "SPLIT"))
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Failed to update metadata", e)
                _eventFlow.emit(UiEvent.ShowToast("修改属性失败: ${e.localizedMessage}"))
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun loadMetadataWithPassword(context: Context, password: String) {
        val uri = _selectedFileUri.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tempFile = copyUriToTempFile(context, uri)
                val doc = PDDocument.load(tempFile, password)
                val info = doc.documentInformation
                _pdfTitle.value = info.title ?: ""
                _pdfAuthor.value = info.author ?: ""
                _pdfSubject.value = info.subject ?: ""
                _pdfKeywords.value = info.keywords ?: ""
                _pdfCreator.value = info.creator ?: ""
                doc.close()
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Failed to load metadata with password", e)
            }
        }
    }
}

class PdfViewModelFactory(private val repository: ExtractionRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PdfViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PdfViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
