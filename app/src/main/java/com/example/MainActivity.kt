package com.example

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import android.view.DragEvent
import android.view.View
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.database.AppDatabase
import com.example.database.ExtractionRecord
import com.example.database.ExtractionRepository
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.PdfViewModel
import com.example.viewmodel.PdfViewModelFactory
import com.example.viewmodel.UiEvent
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize PDFBox-Android resources
        try {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to initialize PDFBox", e)
        }
        
        enableEdgeToEdge()
        
        // Initialize DB and Repository
        val database = AppDatabase.getDatabase(applicationContext)
        val repository = ExtractionRepository(database.extractionRecordDao())
        
        setContent {
            MyApplicationTheme {
                PdfExtractorApp(repository)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfExtractorApp(repository: ExtractionRepository) {
    val context = LocalContext.current
    val viewModel: PdfViewModel = viewModel(
        factory = PdfViewModelFactory(repository)
    )

    // Collect values from VM
    val selectedUri by viewModel.selectedFileUri.collectAsStateWithLifecycle()
    val selectedName by viewModel.selectedFileName.collectAsStateWithLifecycle()
    val selectedSize by viewModel.selectedFileSize.collectAsStateWithLifecycle()
    val pageCount by viewModel.pageCount.collectAsStateWithLifecycle()
    val pdfThumbnail by viewModel.pdfThumbnail.collectAsStateWithLifecycle()
    val isPasswordProtected by viewModel.isPasswordProtected.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val processingProgress by viewModel.processingProgress.collectAsStateWithLifecycle()
    val processingStatus by viewModel.processingStatus.collectAsStateWithLifecycle()
    val historyRecords by viewModel.historyRecords.collectAsStateWithLifecycle()

    // New toolbox states collected from ViewModel
    val selectedMergeFiles by viewModel.selectedMergeFiles.collectAsStateWithLifecycle()
    val selectedImagesForPdf by viewModel.selectedImagesForPdf.collectAsStateWithLifecycle()
    
    val pdfTitle by viewModel.pdfTitle.collectAsStateWithLifecycle()
    val pdfAuthor by viewModel.pdfAuthor.collectAsStateWithLifecycle()
    val pdfSubject by viewModel.pdfSubject.collectAsStateWithLifecycle()
    val pdfKeywords by viewModel.pdfKeywords.collectAsStateWithLifecycle()

    // Screen tab selection (0: Tools, 1: History)
    var currentTab by remember { mutableIntStateOf(0) }

    // Dialog & internal states
    var showPasswordPromptAction by remember { mutableStateOf<String?>(null) } // action type when prompting password
    var actionPasswordInput by remember { mutableStateOf("") }
    
    var showSplitDialog by remember { mutableStateOf(false) }
    var splitPageRangeInput by remember { mutableStateOf("") }

    // New toolbox dialog and input states
    var showMergeDialog by remember { mutableStateOf(false) }
    var showImagesToPdfDialog by remember { mutableStateOf(false) }
    var showWatermarkDialog by remember { mutableStateOf(false) }
    var showMetadataDialog by remember { mutableStateOf(false) }
    var showEncryptionDialog by remember { mutableStateOf(false) }
    var encryptionPasswordInput by remember { mutableStateOf("") }
    
    // Scanner
    val scanner = remember {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(false)
                .setPageLimit(1)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
        )
    }
    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanningResult?.pdf?.uri?.let { uri ->
                viewModel.selectFile(context, uri)
            }
        }
    }
    val onScanClick: () -> Unit = {
        scanner.getStartScanIntent(context as android.app.Activity)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(intentSender).build()
                )
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "扫描启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
    
    var watermarkTextInput by remember { mutableStateOf("") }
    var watermarkFontSizeInput by remember { mutableFloatStateOf(36f) }
    var watermarkOpacityInput by remember { mutableFloatStateOf(0.3f) }
    var watermarkRotationInput by remember { mutableFloatStateOf(45f) }
    var watermarkPositionInput by remember { mutableStateOf("CENTER") }
    var watermarkColorInput by remember { mutableStateOf("#808080") }
    
    var metaTitleInput by remember { mutableStateOf("") }
    var metaAuthorInput by remember { mutableStateOf("") }
    var metaSubjectInput by remember { mutableStateOf("") }
    var metaKeywordsInput by remember { mutableStateOf("") }

    LaunchedEffect(showMetadataDialog) {
        if (showMetadataDialog) {
            metaTitleInput = pdfTitle
            metaAuthorInput = pdfAuthor
            metaSubjectInput = pdfSubject
            metaKeywordsInput = pdfKeywords
        }
    }
    
    // Internal views
    var textReaderContent by remember { mutableStateOf<String?>(null) }
    var textReaderTitle by remember { mutableStateOf("") }
    var imageGalleryFolderPath by remember { mutableStateOf<String?>(null) }
    var imageGalleryTitle by remember { mutableStateOf("") }
    var showSuccessDialogFile by remember { mutableStateOf<File?>(null) }
    var successDialogSummary by remember { mutableStateOf("") }

    // Launcher for file picker
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                viewModel.selectFile(context, uri)
            }
        }
    )

    val mergeFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                viewModel.addMergeFile(context, uri)
            }
        }
    )

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            uris.forEach { uri ->
                viewModel.addImageForPdf(context, uri)
            }
        }
    )

    // Handle single-shot UI events from VM
    LaunchedEffect(key1 = true) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is UiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                }
                is UiEvent.OperationSuccess -> {
                    Toast.makeText(context, "操作成功: ${event.summary}", Toast.LENGTH_SHORT).show()
                    // Auto open view if applicable
                    event.filePath?.let { path ->
                        val file = File(path)
                        if (file.exists()) {
                            when (event.actionType) {
                                "TEXT" -> {
                                    textReaderTitle = selectedName ?: "提取文本"
                                    textReaderContent = file.readText()
                                }
                                "IMAGES_FULL" -> {
                                    imageGalleryTitle = "全页渲染 - ${selectedName ?: ""}"
                                    imageGalleryFolderPath = path
                                }
                                "IMAGES_EMBEDDED" -> {
                                    imageGalleryTitle = "提取素材 - ${selectedName ?: ""}"
                                    imageGalleryFolderPath = path
                                }
                                "UNLOCKED", "SPLIT", "MERGE", "IMAGES_TO_PDF", "WATERMARK", "METADATA" -> {
                                    showSuccessDialogFile = file
                                    successDialogSummary = event.summary
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.FolderZip,
                            contentDescription = "Logo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (currentTab == 0) "PDF 工具箱" else "解压历史记录",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp
                        )
                    }
                },
                actions = {
                    if (currentTab == 1 && historyRecords.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearAllHistory() }) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear All",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == 0) Icons.Filled.Build else Icons.Outlined.Build,
                            contentDescription = "Tools"
                        )
                    },
                    label = { Text("解压工具") }
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == 1) Icons.Filled.History else Icons.Outlined.History,
                            contentDescription = "History"
                        )
                    },
                    label = { Text("处理历史") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Main views toggle
            Crossfade(targetState = currentTab, label = "tab_fade") { tab ->
                when (tab) {
                    0 -> {
                        // Tools view
                        ToolsScreen(
                            selectedUri = selectedUri,
                            selectedName = selectedName,
                            selectedSize = selectedSize,
                            pageCount = pageCount,
                            pdfThumbnail = pdfThumbnail,
                            isPasswordProtected = isPasswordProtected,
                            onPickFile = { documentPickerLauncher.launch(arrayOf("application/pdf")) },
                            onClearFile = { viewModel.clearSelectedFile() },
                            onFileSelected = { uri -> viewModel.selectFile(context, uri) },
                            onTriggerAction = { action ->
                                if (isPasswordProtected && actionPasswordInput.isEmpty()) {
                                    // File is encrypted, request password first
                                    showPasswordPromptAction = action
                                } else {
                                    // File is unencrypted or password already supplied, run action directly
                                    executeAction(context, viewModel, action, actionPasswordInput, onShowSplitDialog = {
                                        showSplitDialog = true
                                    })
                                }
                            },
                            onMergeClick = { showMergeDialog = true },
                            onImagesToPdfClick = { showImagesToPdfDialog = true },
                            onWatermarkClick = {
                                if (isPasswordProtected && actionPasswordInput.isEmpty()) {
                                    showPasswordPromptAction = "WATERMARK"
                                } else {
                                    showWatermarkDialog = true
                                }
                            },
                            onMetadataClick = {
                                if (isPasswordProtected && actionPasswordInput.isEmpty()) {
                                    showPasswordPromptAction = "METADATA"
                                } else {
                                    showMetadataDialog = true
                                }
                            },
                            onEncryptClick = { showEncryptionDialog = true },
                            onScanClick = onScanClick
                        )
                    }
                    1 -> {
                        // History view
                        HistoryScreen(
                            records = historyRecords,
                            onDeleteRecord = { viewModel.deleteHistoryRecord(it) },
                            onOpenRecord = { record ->
                                record.filePath?.let { path ->
                                    val file = File(path)
                                    if (file.exists()) {
                                        when (record.actionType) {
                                            "TEXT" -> {
                                                textReaderTitle = record.fileName
                                                textReaderContent = file.readText()
                                            }
                                            "IMAGES_FULL" -> {
                                                imageGalleryTitle = "历史渲染 - ${record.fileName}"
                                                imageGalleryFolderPath = path
                                            }
                                            "IMAGES_EMBEDDED" -> {
                                                imageGalleryTitle = "历史素材 - ${record.fileName}"
                                                imageGalleryFolderPath = path
                                            }
                                            "SPLIT", "UNLOCKED" -> {
                                                openFileInSystem(context, file)
                                            }
                                        }
                                    } else {
                                        Toast.makeText(context, "关联的文件已被删除或无法访问", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onShareRecord = { record ->
                                record.filePath?.let { path ->
                                    val file = File(path)
                                    if (file.exists()) {
                                        if (file.isDirectory) {
                                            shareImagesFolder(context, file)
                                        } else {
                                            shareFileInSystem(context, file)
                                        }
                                    } else {
                                        Toast.makeText(context, "关联的文件已被删除", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onDownloadRecord = { record ->
                                record.filePath?.let { path ->
                                    val file = File(path)
                                    if (file.exists()) {
                                        downloadFileToPublicDownloads(context, file)
                                    } else {
                                        Toast.makeText(context, "关联的文件已被删除", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // Spinner/Progress Overlay
            if (isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(24.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(72.dp)
                            ) {
                                CircularProgressIndicator(
                                    progress = processingProgress,
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 5.dp,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Text(
                                    text = "${(processingProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(20.dp))
                            
                            Text(
                                text = "正在处理 PDF 任务",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            
                            Spacer(modifier = Modifier.height(6.dp))
                            
                            val displayStatus = if (processingStatus.isNotEmpty()) {
                                processingStatus
                            } else {
                                "正在分析并处理，请稍候..."
                            }
                            
                            Text(
                                text = displayStatus,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            LinearProgressIndicator(
                                progress = processingProgress,
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Text(
                                text = "任务正在后台执行，请勿关闭应用",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Text Reader View (Overlay)
            textReaderContent?.let { content ->
                InteractiveTextReader(
                    title = textReaderTitle,
                    content = content,
                    onClose = { textReaderContent = null }
                )
            }

            // Image Gallery View (Overlay)
            imageGalleryFolderPath?.let { path ->
                InteractiveImageGallery(
                    title = imageGalleryTitle,
                    folderPath = path,
                    onClose = { imageGalleryFolderPath = null }
                )
            }

            // Password Prompt Dialog
            showPasswordPromptAction?.let { action ->
                var pwdInput by remember { mutableStateOf("") }
                var showPassword by remember { mutableStateOf(false) }

                Dialog(
                    onDismissRequest = { showPasswordPromptAction = null },
                    properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Password",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "此 PDF 文件已受密码保护",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "请输入打开或解密该文件所需的访问密码：",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = pwdInput,
                                onValueChange = { pwdInput = it },
                                label = { Text("PDF 密码") },
                                singleLine = true,
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    IconButton(onClick = { showPassword = !showPassword }) {
                                        Icon(
                                            imageVector = if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = "Toggle password visibility"
                                        )
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { showPasswordPromptAction = null }) {
                                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Button(
                                    onClick = {
                                        actionPasswordInput = pwdInput
                                        showPasswordPromptAction = null
                                        if (action == "METADATA") { viewModel.loadMetadataWithPassword(context, pwdInput); showMetadataDialog = true } else if (action == "WATERMARK") { showWatermarkDialog = true } else executeAction(context, viewModel, action, pwdInput, onShowSplitDialog = {
                                            showSplitDialog = true
                                        })
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("确认", color = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            // Encryption Dialog
            if (showEncryptionDialog) {
                var showPassword by remember { mutableStateOf(false) }
                Dialog(
                    onDismissRequest = { showEncryptionDialog = false },
                    properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Encrypt",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "PDF 加密保护",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = encryptionPasswordInput,
                                onValueChange = { encryptionPasswordInput = it },
                                label = { Text("设置访问密码") },
                                singleLine = true,
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    IconButton(onClick = { showPassword = !showPassword }) {
                                        Icon(
                                            imageVector = if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = "Toggle password visibility"
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { showEncryptionDialog = false }) {
                                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Button(
                                    onClick = {
                                        viewModel.encryptPdf(context, encryptionPasswordInput)
                                        showEncryptionDialog = false
                                        encryptionPasswordInput = ""
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("加密并保存", color = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            // Split PDF Page Range Prompt Dialog
            if (showSplitDialog) {
                Dialog(
                    onDismissRequest = { showSplitDialog = false },
                    properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.ContentCut,
                                    contentDescription = "Split",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "拆分/提取 PDF 页面",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "输入你想提取或合并生成的页面范围：\n• 单独页：1, 3, 5\n• 页面区间：2-6\n• 组合形式：1-3, 5, 8-10",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = splitPageRangeInput,
                                onValueChange = { splitPageRangeInput = it },
                                label = { Text("页面范围") },
                                placeholder = { Text("例如: 1-3, 5") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (pageCount > 0) {
                                Text(
                                    text = "此 PDF 文件共有 $pageCount 页",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { showSplitDialog = false }) {
                                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Button(
                                    onClick = {
                                        if (splitPageRangeInput.trim().isEmpty()) {
                                            Toast.makeText(context, "请输入页面范围", Toast.LENGTH_SHORT).show()
                                        } else {
                                            showSplitDialog = false
                                            viewModel.splitPdfPages(context, splitPageRangeInput, actionPasswordInput)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("执行拆分", color = Color.White)
                                }
                            }
                        }
                    }
                }

                // Success Dialog for Decrypted PDF Actions
                showSuccessDialogFile?.let { file ->
                    AlertDialog(
                        onDismissRequest = { showSuccessDialogFile = null },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Success",
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(40.dp)
                            )
                        },
                        title = {
                            Text(
                                text = "PDF 处理成功！",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        },
                        text = {
                            Column {
                                Text(
                                    text = successDialogSummary,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "文件名: ${file.name}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    downloadFileToPublicDownloads(context, file)
                                    showSuccessDialogFile = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Download", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("下载保存至本地", color = Color.White)
                            }
                        },
                        dismissButton = {
                            Row {
                                TextButton(onClick = {
                                    shareFileInSystem(context, file)
                                    showSuccessDialogFile = null
                                }) {
                                    Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("分享", color = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                  TextButton(onClick = {
                                    openFileInSystem(context, file)
                                    showSuccessDialogFile = null
                                }) {
                                    Icon(Icons.Default.Launch, contentDescription = "Open", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("打开", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    )
                }

                // --- Merge PDF Dialog ---
                if (showMergeDialog) {
                    Dialog(
                        onDismissRequest = { showMergeDialog = false },
                        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth(0.95f)
                                .padding(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Collections,
                                            contentDescription = "Merge",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "合并 PDF 文件",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 20.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    IconButton(onClick = { showMergeDialog = false }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "选择两个或多个 PDF 文件，按顺序将它们合并为一个全新的 PDF 档。",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 18.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))

                                Box(
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .heightIn(min = 120.dp, max = 260.dp)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f))
                                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                                        .padding(8.dp)
                                ) {
                                    if (selectedMergeFiles.isEmpty()) {
                                        Column(
                                            modifier = Modifier.fillMaxSize().padding(16.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PictureAsPdf,
                                                contentDescription = "No files",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                modifier = Modifier.size(40.dp)
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = "尚未添加任何 PDF 文件",
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            )
                                        }
                                    } else {
                                        LazyColumn(
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            itemsIndexed(selectedMergeFiles) { index, file ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(MaterialTheme.colorScheme.surface)
                                                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.PictureAsPdf,
                                                        contentDescription = "PDF",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Text(
                                                        text = "${index + 1}. ${file.second}",
                                                        fontSize = 13.sp,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    IconButton(
                                                        onClick = { viewModel.removeMergeFile(index) },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Delete,
                                                            contentDescription = "Delete",
                                                            tint = MaterialTheme.colorScheme.error,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { mergeFilePickerLauncher.launch(arrayOf("application/pdf")) },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Add, contentDescription = "Add")
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("添加文件")
                                    }

                                    Button(
                                        onClick = {
                                            if (selectedMergeFiles.size < 2) {
                                                Toast.makeText(context, "合并 PDF 至少需要两个文件", Toast.LENGTH_SHORT).show()
                                            } else {
                                                showMergeDialog = false
                                                viewModel.mergePdfFiles(context)
                                            }
                                        },
                                        enabled = selectedMergeFiles.size >= 2,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Check, contentDescription = "Merge")
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("开始合并")
                                    }
                                }
                            }
                        }
                    }
                }

                // --- Images To PDF Dialog ---
                if (showImagesToPdfDialog) {
                    Dialog(
                        onDismissRequest = { showImagesToPdfDialog = false },
                        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth(0.95f)
                                .padding(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp)
                              ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Image,
                                            contentDescription = "Images to PDF",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "图片转 PDF 电子书",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 20.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    IconButton(onClick = { showImagesToPdfDialog = false }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "选择多张手机相册照片或本地图片，系统将把它们依次按原尺寸打包编译进一个新的 PDF 文档。",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 18.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))

                                Box(
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .heightIn(min = 120.dp, max = 260.dp)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f))
                                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                                        .padding(8.dp)
                                ) {
                                    if (selectedImagesForPdf.isEmpty()) {
                                        Column(
                                            modifier = Modifier.fillMaxSize().padding(16.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Image,
                                                contentDescription = "No images",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                modifier = Modifier.size(40.dp)
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = "尚未选择任何图片",
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            )
                                        }
                                    } else {
                                        LazyColumn(
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            itemsIndexed(selectedImagesForPdf) { index, file ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(MaterialTheme.colorScheme.surface)
                                                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Image,
                                                        contentDescription = "Image",
                                                        tint = MaterialTheme.colorScheme.secondary,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Text(
                                                        text = "${index + 1}. ${file.second}",
                                                        fontSize = 13.sp,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    IconButton(
                                                        onClick = { viewModel.removeImageForPdf(index) },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Delete,
                                                            contentDescription = "Delete",
                                                            tint = MaterialTheme.colorScheme.error,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { imagePickerLauncher.launch(arrayOf("image/*")) },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Image, contentDescription = "Add Images")
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("添加图片")
                                    }

                                    Button(
                                        onClick = {
                                            if (selectedImagesForPdf.isEmpty()) {
                                                Toast.makeText(context, "请添加至少一张图片", Toast.LENGTH_SHORT).show()
                                            } else {
                                                showImagesToPdfDialog = false
                                                viewModel.convertImagesToPdf(context)
                                            }
                                        },
                                        enabled = selectedImagesForPdf.isNotEmpty(),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Check, contentDescription = "Convert")
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("开始转换")
                                    }
                                }
                            }
                        }
                    }
                }

                // --- Add Watermark Dialog ---
                if (showWatermarkDialog) {
                    Dialog(
                        onDismissRequest = { showWatermarkDialog = false },
                        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)
                    ) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(24.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Create,
                                        contentDescription = "Watermark",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "添加高级文字水印",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "在 PDF 页面上添加防伪、版权或保密水印。支持设置文字、大小、透明度、旋转、位置和颜色。",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 17.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                OutlinedTextField(
                                    value = watermarkTextInput,
                                    onValueChange = { watermarkTextInput = it },
                                    label = { Text("水印文字内容") },
                                    placeholder = { Text("如: CONFIDENTIAL, DRAFT") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                // Font Size
                                Text(
                                    text = "字体大小: ${watermarkFontSizeInput.toInt()} pt",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Slider(
                                    value = watermarkFontSizeInput,
                                    onValueChange = { watermarkFontSizeInput = it },
                                    valueRange = 12f..72f,
                                    steps = 9,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                // Opacity (Transparency)
                                Text(
                                    text = "水印透明度: ${(watermarkOpacityInput * 100).toInt()}%",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Slider(
                                    value = watermarkOpacityInput,
                                    onValueChange = { watermarkOpacityInput = it },
                                    valueRange = 0.05f..1.0f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                // Rotation
                                Text(
                                    text = "旋转角度: ${watermarkRotationInput.toInt()}°",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Slider(
                                    value = watermarkRotationInput,
                                    onValueChange = { watermarkRotationInput = it },
                                    valueRange = -90f..90f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                // Positioning Preset
                                Text(
                                    text = "摆放位置",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                val positionsList = listOf(
                                    "CENTER" to "页面中心",
                                    "TILE" to "网格平铺",
                                    "TOP_LEFT" to "左上角",
                                    "TOP_RIGHT" to "右上角",
                                    "BOTTOM_LEFT" to "左下角",
                                    "BOTTOM_RIGHT" to "右下角"
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (i in positionsList.indices step 2) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            for (j in 0..1) {
                                                if (i + j < positionsList.size) {
                                                    val (posVal, posLabel) = positionsList[i + j]
                                                    val isSelected = watermarkPositionInput == posVal
                                                    Box(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .height(38.dp)
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .background(
                                                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                                            )
                                                            .clickable { watermarkPositionInput = posVal }
                                                            .border(
                                                                width = 1.dp,
                                                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                                                shape = RoundedCornerShape(8.dp)
                                                            ),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            if (isSelected) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Check,
                                                                    contentDescription = null,
                                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                                    modifier = Modifier.size(14.dp)
                                                                )
                                                                Spacer(modifier = Modifier.width(4.dp))
                                                            }
                                                            Text(
                                                                text = posLabel,
                                                                fontSize = 12.sp,
                                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }
                                                } else {
                                                    Spacer(modifier = Modifier.weight(1f))
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))

                                // Color Selector
                                Text(
                                    text = "水印颜色",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                val colorsList = listOf(
                                    "#808080" to "经典灰",
                                    "#DC2626" to "防伪红",
                                    "#2563EB" to "科技蓝",
                                    "#16A34A" to "安全绿",
                                    "#000000" to "庄重黑"
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    colorsList.forEach { (hex, label) ->
                                        val isSelected = watermarkColorInput == hex
                                        val colorObj = Color(android.graphics.Color.parseColor(hex))
                                        
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { watermarkColorInput = hex }
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                                    .background(colorObj)
                                                    .border(
                                                        width = if (isSelected) 2.5.dp else 1.dp,
                                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                        shape = androidx.compose.foundation.shape.CircleShape
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = if (hex == "#000000") Color.White else Color.Black,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = label,
                                                fontSize = 10.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(24.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { showWatermarkDialog = false }) {
                                        Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Button(
                                        onClick = {
                                            if (watermarkTextInput.trim().isEmpty()) {
                                                Toast.makeText(context, "请输入水印文字", Toast.LENGTH_SHORT).show()
                                            } else {
                                                showWatermarkDialog = false
                                                viewModel.addWatermarkToPdf(
                                                    context = context,
                                                    watermarkText = watermarkTextInput,
                                                    fontSize = watermarkFontSizeInput,
                                                    opacity = watermarkOpacityInput,
                                                    rotation = watermarkRotationInput,
                                                    position = watermarkPositionInput,
                                                    colorHex = watermarkColorInput,
                                                    password = actionPasswordInput
                                                )
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Text("立即生成", color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }

                // --- Edit Metadata Dialog ---
                if (showMetadataDialog) {
                    Dialog(
                        onDismissRequest = { showMetadataDialog = false },
                        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)
                    ) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(24.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Metadata",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "编辑 PDF 属性信息",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "查看或修改 PDF 文档自带的元数据属性，让您的文档排版与归档更专业。",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                OutlinedTextField(
                                    value = metaTitleInput,
                                    onValueChange = { metaTitleInput = it },
                                    label = { Text("文档标题 (Title)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                                )
                                OutlinedTextField(
                                    value = metaAuthorInput,
                                    onValueChange = { metaAuthorInput = it },
                                    label = { Text("作者/版权方 (Author)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                                )
                                OutlinedTextField(
                                    value = metaSubjectInput,
                                    onValueChange = { metaSubjectInput = it },
                                    label = { Text("主题描述 (Subject)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                                )
                                OutlinedTextField(
                                    value = metaKeywordsInput,
                                    onValueChange = { metaKeywordsInput = it },
                                    label = { Text("关键字标签 (Keywords)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                
                                Spacer(modifier = Modifier.height(24.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { showMetadataDialog = false }) {
                                        Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Button(
                                        onClick = {
                                            showMetadataDialog = false
                                            viewModel.updatePdfMetadata(
                                                context = context,
                                                title = metaTitleInput,
                                                author = metaAuthorInput,
                                                subject = metaSubjectInput,
                                                keywords = metaKeywordsInput,
                                                password = actionPasswordInput
                                            )
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Text("保存修改", color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Trigger specific PDF operations
private fun executeAction(
    context: android.content.Context,
    viewModel: PdfViewModel,
    action: String,
    passwordInput: String,
    onShowSplitDialog: () -> Unit
) {
    when (action) {
        "TEXT" -> viewModel.extractText(context, passwordInput)
        "IMAGES_FULL" -> viewModel.renderPagesToImages(context, passwordInput)
        "IMAGES_EMBEDDED" -> viewModel.extractEmbeddedImages(context, passwordInput)
        "SPLIT" -> onShowSplitDialog()
        "ZIP_SPLIT" -> viewModel.splitPdfToZip(context, passwordInput)
        "UNLOCKED" -> {
            if (passwordInput.isEmpty()) {
                android.widget.Toast.makeText(context, "此文件不需要移除密码或密码未输入", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                viewModel.decryptPdf(context, passwordInput)
            }
        }
    }
}

@Composable
fun ToolsScreen(
    selectedUri: Uri?,
    selectedName: String?,
    selectedSize: Long,
    pageCount: Int,
    pdfThumbnail: android.graphics.Bitmap?,
    isPasswordProtected: Boolean,
    onPickFile: () -> Unit,
    onClearFile: () -> Unit,
    onFileSelected: (Uri) -> Unit,
    onTriggerAction: (String) -> Unit,
    onMergeClick: () -> Unit,
    onImagesToPdfClick: () -> Unit,
    onWatermarkClick: () -> Unit,
    onMetadataClick: () -> Unit,
    onEncryptClick: () -> Unit,
    onScanClick: () -> Unit
) {
    val context = LocalContext.current
    val requireFile = { action: () -> Unit ->
        if (selectedUri == null) {
            Toast.makeText(context, "请先导入一个 PDF 源文件", Toast.LENGTH_SHORT).show()
            onPickFile()
        } else {
            action()
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        val availableWidth = maxWidth
        val columns = if (availableWidth > 640.dp) 2 else 1

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 1000.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            DragAndDropUploadComponent(
                selectedUri = selectedUri,
                selectedName = selectedName,
                selectedSize = selectedSize,
                pageCount = pageCount,
                pdfThumbnail = pdfThumbnail,
                isPasswordProtected = isPasswordProtected,
                onPickFile = onPickFile,
                onClearFile = onClearFile,
                onFileDropped = onFileSelected
            )

            // Section: Popular Tools
            SectionHeader(
                text = "热门实用工具 (Popular Tools)",
                barColor = MaterialTheme.colorScheme.tertiary
            )

            val popularItems = listOf<@Composable () -> Unit>(
                {
                    ActionCard(
                        title = "解压提取纯文本",
                        description = "最常用的功能：智能扫描解析 PDF 书面文字，显示并导出为 TXT 文本。",
                        icon = Icons.Default.Description,
                        iconTint = MaterialTheme.colorScheme.primary,
                        badgeText = if (selectedUri == null) "需源文件" else null,
                        onClick = { requireFile { onTriggerAction("TEXT") } }
                    )
                },
                {
                    ActionCard(
                        title = "合并多个 PDF 文件",
                        description = "最常用的功能：合并两个或多个 PDF 文档，自定义排序，并快速编译。",
                        icon = Icons.Default.LibraryBooks,
                        iconTint = Color(0xFF06B6D4),
                        badgeText = "免选源文件",
                        badgeColor = Color(0xFF06B6D4),
                        onClick = onMergeClick
                    )
                },
                {
                    ActionCard(
                        title = "全页转换为高清图片",
                        description = "最常用的功能：将 PDF 每一页高精度渲染输出为 PNG 图像，保留完整版式。",
                        icon = Icons.Default.Image,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        badgeText = if (selectedUri == null) "需源文件" else null,
                        onClick = { requireFile { onTriggerAction("IMAGES_FULL") } }
                    )
                },
                {
                    ActionCard(
                        title = "PDF 加密 (Encrypt)",
                        description = "最常用的功能：为 PDF 文档添加高强度密码保护，防止未经授权的访问。",
                        icon = Icons.Default.Lock,
                        iconTint = MaterialTheme.colorScheme.error,
                        badgeText = if (selectedUri == null) "需源文件" else null,
                        onClick = { requireFile { onEncryptClick() } }
                    )
                },
                {
                    ActionCard(
                        title = "手机扫描 PDF",
                        description = "最常用的功能：使用相机扫描文档，自动边缘检测、修正透视，并直接生成 PDF。",
                        icon = Icons.Default.CameraAlt,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        badgeText = null,
                        onClick = onScanClick
                    )
                }
            )

            ResponsiveGrid(columnsCount = columns, content = popularItems)

            Spacer(modifier = Modifier.height(16.dp))

            // Section 1: Content Extraction & Security
            SectionHeader(
                text = "内容提取与安全 (Extraction & Security)",
                barColor = MaterialTheme.colorScheme.primary
            )

            val section1Items = mutableListOf<@Composable () -> Unit>()
            section1Items.add {
                ActionCard(
                    title = "解压提取纯文本",
                    description = "智能扫描解析 PDF 书面文字，显示并导出为 TXT 文本，支持离线检索、复制与分享。",
                    icon = Icons.Default.Description,
                    iconTint = MaterialTheme.colorScheme.primary,
                    badgeText = if (selectedUri == null) "需源文件" else null,
                    onClick = { requireFile { onTriggerAction("TEXT") } }
                )
            }
            section1Items.add {
                ActionCard(
                    title = "全页转换为高清图片",
                    description = "将 PDF 每一页高精度渲染输出为 PNG 图像，保留完整版式，适合手机相册保存与查看。",
                    icon = Icons.Default.Image,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    badgeText = if (selectedUri == null) "需源文件" else null,
                    onClick = { requireFile { onTriggerAction("IMAGES_FULL") } }
                )
            }
            section1Items.add {
                ActionCard(
                    title = "抽取提取内嵌素材",
                    description = "深入分析 PDF 结构，一键剥离、抽取其中嵌入的全部插图、流程图和照片素材文件。",
                    icon = Icons.Default.Collections,
                    iconTint = Color(0xFFF59E0B),
                    badgeText = if (selectedUri == null) "需源文件" else null,
                    onClick = { requireFile { onTriggerAction("IMAGES_EMBEDDED") } }
                )
            }
            section1Items.add {
                ActionCard(
                    title = "拆分/提取指定页面",
                    description = "裁剪文档，提取特定页面区间（例如：1, 3-5 页）来重新组合生成一份全新的 PDF。",
                    icon = Icons.Default.ContentCut,
                    iconTint = Color(0xFF6366F1),
                    badgeText = if (selectedUri == null) "需源文件" else null,
                    onClick = { requireFile { onTriggerAction("SPLIT") } }
                )
            }
            section1Items.add {
                ActionCard(
                    title = "单页拆分打包 (ZIP)",
                    description = "将 PDF 中的每一页分别拆分为独立的单页 PDF 文档，并自动打包为 ZIP 压缩包下载保存。",
                    icon = Icons.Default.FolderZip,
                    iconTint = Color(0xFF10B981),
                    badgeText = if (selectedUri == null) "需源文件" else null,
                    onClick = { requireFile { onTriggerAction("ZIP_SPLIT") } }
                )
            }
            if (selectedUri != null && isPasswordProtected) {
                section1Items.add {
                    ActionCard(
                        title = "永久移除密码限制",
                        description = "在输入密码验证后，永久剥离该 PDF 的加密与防复制限制，生成无密的独立 PDF 副本。",
                        icon = Icons.Default.LockOpen,
                        iconTint = Color(0xFFEF4444),
                        borderStroke = BorderStroke(1.2.dp, Color(0xFFEF4444).copy(alpha = 0.3f)),
                        onClick = { requireFile { onTriggerAction("UNLOCKED") } }
                    )
                }
            }

            ResponsiveGrid(columnsCount = columns, content = section1Items)

            Spacer(modifier = Modifier.height(8.dp))

            // Section 2: Assemble & Create
            SectionHeader(
                text = "文档合并与制作 (Assemble & Create)",
                barColor = Color(0xFF06B6D4)
            )

            val section2Items = listOf<@Composable () -> Unit>(
                {
                    ActionCard(
                        title = "合并多个 PDF 文件",
                        description = "合并两个或多个 PDF 文档，自定义排序，并快速编译输出为一个全新的连贯 PDF。",
                        icon = Icons.Default.LibraryBooks,
                        iconTint = Color(0xFF06B6D4),
                        badgeText = "免选源文件",
                        badgeColor = Color(0xFF06B6D4),
                        onClick = onMergeClick
                    )
                },
                {
                    ActionCard(
                        title = "图片/照片制作 PDF",
                        description = "批量挑选相册图片或相机拍照扫描件，依次将其打包，按原图分辨率输出为 PDF 电子书。",
                        icon = Icons.Default.Image,
                        iconTint = Color(0xFFEC4899),
                        badgeText = "免选源文件",
                        badgeColor = Color(0xFFEC4899),
                        onClick = onImagesToPdfClick
                    )
                }
            )

            ResponsiveGrid(columnsCount = columns, content = section2Items)

            Spacer(modifier = Modifier.height(8.dp))

            // Section 3: Edit & Decorate
            SectionHeader(
                text = "文档编辑与增强 (Edit & Decorate)",
                barColor = Color(0xFF8B5CF6)
            )

            val section3Items = listOf<@Composable () -> Unit>(
                {
                    ActionCard(
                        title = "修改 PDF 属性信息",
                        description = "查看、修正或编辑 PDF 内置的元数据属性（如标题、作者、主题、关键字），利于归档管理。",
                        icon = Icons.Default.Info,
                        iconTint = Color(0xFF3B82F6),
                        badgeText = if (selectedUri == null) "需源文件" else null,
                        badgeColor = Color(0xFF3B82F6),
                        onClick = { requireFile { onMetadataClick() } }
                    )
                },
                {
                    ActionCard(
                        title = "添加防伪文字水印",
                        description = "在当前选中 PDF 文件的所有页中心，倾斜添加防伪、保密或版权等可定制的水印标识。",
                        icon = Icons.Default.Create,
                        iconTint = Color(0xFF8B5CF6),
                        badgeText = if (selectedUri == null) "需源文件" else null,
                        badgeColor = Color(0xFF8B5CF6),
                        onClick = { requireFile { onWatermarkClick() } }
                    )
                }
            )

            ResponsiveGrid(columnsCount = columns, content = section3Items)
        }
    }
}

@Composable
fun DragAndDropBox(
    modifier: Modifier = Modifier,
    onFileDropped: (Uri) -> Unit,
    content: @Composable (isDragging: Boolean) -> Unit
) {
    var isDraggingOver by remember { mutableStateOf(false) }
    
    AndroidView(
        factory = { context ->
            ComposeView(context).apply {
                setContent {
                    content(isDraggingOver)
                }
                setOnDragListener { _, event ->
                    when (event.action) {
                        DragEvent.ACTION_DRAG_STARTED -> {
                            true
                        }
                        DragEvent.ACTION_DRAG_ENTERED -> {
                            isDraggingOver = true
                            true
                        }
                        DragEvent.ACTION_DRAG_EXITED -> {
                            isDraggingOver = false
                            true
                        }
                        DragEvent.ACTION_DROP -> {
                            isDraggingOver = false
                            val clipData = event.clipData
                            if (clipData != null && clipData.itemCount > 0) {
                                val item = clipData.getItemAt(0)
                                val uri = item.uri
                                if (uri != null) {
                                    onFileDropped(uri)
                                }
                            }
                            true
                        }
                        DragEvent.ACTION_DRAG_ENDED -> {
                            isDraggingOver = false
                            true
                        }
                        else -> false
                    }
                }
            }
        },
        update = { composeView ->
            composeView.setContent {
                content(isDraggingOver)
            }
        },
        modifier = modifier
    )
}

fun Modifier.dashedBorder(
    width: androidx.compose.ui.unit.Dp,
    color: Color,
    cornerRadius: androidx.compose.ui.unit.Dp = 0.dp
) = drawBehind {
    val stroke = Stroke(
        width = width.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f), 0f)
    )
    if (cornerRadius > 0.dp) {
        drawRoundRect(
            color = color,
            style = stroke,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
        )
    } else {
        drawRect(
            color = color,
            style = stroke
        )
    }
}

@Composable
fun DragAndDropUploadComponent(
    selectedUri: Uri?,
    selectedName: String?,
    selectedSize: Long,
    pageCount: Int,
    pdfThumbnail: android.graphics.Bitmap?,
    isPasswordProtected: Boolean,
    onPickFile: () -> Unit,
    onClearFile: () -> Unit,
    onFileDropped: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    DragAndDropBox(
        onFileDropped = onFileDropped,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp)
    ) { isDragging ->
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDragging) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                } else {
                    MaterialTheme.colorScheme.surface
                }
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = if (isDragging) 6.dp else 2.dp
            ),
            border = BorderStroke(
                width = if (isDragging) 2.dp else 1.dp,
                color = if (isDragging) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                }
            )
        ) {
            if (selectedUri == null) {
                // EMPTY STATE: Drag & Drop Dropzone
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onPickFile)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header title for workspace
                    Text(
                        text = "PDF 极速解压工坊",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "专业的本地离线 PDF 内容提取、文档合并与安全控制工具包",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Dashed border upload area
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isDragging) MaterialTheme.colorScheme.primary.copy(alpha = 0.04f)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.02f)
                            )
                            .dashedBorder(
                                width = 1.5.dp,
                                color = if (isDragging) MaterialTheme.colorScheme.primary 
                                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                cornerRadius = 16.dp
                            )
                            .padding(vertical = 32.dp, horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            // Upload icon
                            Box(
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isDragging) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isDragging) Icons.Default.CloudUpload else Icons.Default.FileOpen,
                                    contentDescription = "Upload",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Text(
                                text = if (isDragging) "释放以导入 PDF 文件" else "拖拽 PDF 文件到此处，或点击浏览文件",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center
                            )
                            
                            Spacer(modifier = Modifier.height(6.dp))
                            
                            Text(
                                text = "支持提取纯文本、转换高清图片与解除文档限制",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                // FILE SELECTED STATE: Unified beautiful upload area showing filename, size & replace options
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isDragging) {
                        // Overlay notification inside the card during drag-over
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f))
                                .dashedBorder(1.5.dp, MaterialTheme.colorScheme.primary, 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.CloudUpload,
                                    contentDescription = "Drop file",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "释放以替换当前的 PDF 文件",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    } else {
                        // Display selected file info
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // PDF icon badge / Thumbnail Preview
                            if (pdfThumbnail != null) {
                                Box(
                                    modifier = Modifier
                                        .size(60.dp, 80.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.White)
                                        .border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                                        .padding(2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    androidx.compose.foundation.Image(
                                        bitmap = pdfThumbnail.asImageBitmap(),
                                        contentDescription = "PDF First Page Preview",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PictureAsPdf,
                                        contentDescription = "Active PDF",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedName ?: "未知 PDF 文档",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                
                                Spacer(modifier = Modifier.height(6.dp))
                                
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Size Chip
                                    SuggestionChip(
                                        onClick = {},
                                        label = { Text(formatFileSize(selectedSize), fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                                        colors = SuggestionChipDefaults.suggestionChipColors(
                                            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                                        ),
                                        border = null,
                                        modifier = Modifier.height(24.dp)
                                    )
                                    
                                    // Page count Chip
                                    if (pageCount > 0) {
                                        SuggestionChip(
                                            onClick = {},
                                            label = { Text("$pageCount 页", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                            colors = SuggestionChipDefaults.suggestionChipColors(
                                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                                labelColor = MaterialTheme.colorScheme.primary
                                            ),
                                            border = null,
                                            modifier = Modifier.height(24.dp)
                                        )
                                    }
                                }
                            }

                            // Action buttons
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Reselect (replace) button
                                IconButton(
                                    onClick = onPickFile,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SwapHoriz,
                                        contentDescription = "Replace File",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // Clear button
                                IconButton(
                                    onClick = onClearFile,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.06f), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Unload File",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        // Security status Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isPasswordProtected) Color(0xFFEF4444).copy(alpha = 0.08f)
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                                )
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isPasswordProtected) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "Security Status",
                                    tint = if (isPasswordProtected) Color(0xFFEF4444) else Color(0xFF10B981),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isPasswordProtected) "文档受密码保护" else "文档安全 (未加密)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isPasswordProtected) Color(0xFFEF4444) else Color(0xFF10B981)
                                )
                            }
                            
                            Text(
                                text = "拖动新 PDF 文件到此处可快速替换",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ResponsiveGrid(
    columnsCount: Int,
    modifier: Modifier = Modifier,
    content: List<@Composable () -> Unit>
) {
    Column(modifier = modifier) {
        val chunked = content.chunked(columnsCount)
        chunked.forEach { rowContent ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowContent.forEach { item ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        item()
                    }
                }
                // Add empty spacers if the row is not complete
                if (rowContent.size < columnsCount) {
                    repeat(columnsCount - rowContent.size) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        }
    }

}

@Composable
fun SectionHeader(
    text: String,
    barColor: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(barColor)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
fun ActionCard(
    title: String,
    description: String,
    icon: ImageVector,
    iconTint: Color,
    badgeText: String? = null,
    badgeColor: Color? = null,
    borderStroke: BorderStroke? = null,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        border = borderStroke ?: BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon Badge with beautiful soft color background
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconTint.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (badgeText != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background((badgeColor ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.08f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = badgeText,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor ?: MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Enter",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun HistoryScreen(
    records: List<ExtractionRecord>,
    onDeleteRecord: (ExtractionRecord) -> Unit,
    onOpenRecord: (ExtractionRecord) -> Unit,
    onShareRecord: (ExtractionRecord) -> Unit,
    onDownloadRecord: (ExtractionRecord) -> Unit
) {
    if (records.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.HistoryToggleOff,
                    contentDescription = "Empty History",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f),
                    modifier = Modifier.size(80.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "暂无解压历史记录",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "你成功提取的文件和图片记录会显示在这里",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(records, key = { it.id }) { record ->
                HistoryRecordItem(
                    record = record,
                    onOpen = { onOpenRecord(record) },
                    onShare = { onShareRecord(record) },
                    onDelete = { onDeleteRecord(record) },
                    onDownload = { onDownloadRecord(record) }
                )
            }
        }
    }
}

@Composable
fun HistoryRecordItem(
    record: ExtractionRecord,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit
) {
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val dateStr = remember(record.timestamp) { sdf.format(Date(record.timestamp)) }

    val iconInfo = when (record.actionType) {
        "TEXT" -> Pair(Icons.Default.TextSnippet, Color(0xFF10B981)) // emerald
        "IMAGES_FULL" -> Pair(Icons.Default.PhotoLibrary, Color(0xFF3B82F6)) // blue
        "IMAGES_EMBEDDED" -> Pair(Icons.Default.Image, Color(0xFF8B5CF6)) // violet
        "SPLIT" -> Pair(Icons.Default.ContentCut, Color(0xFFF59E0B)) // amber
        "UNLOCKED" -> Pair(Icons.Default.LockOpen, Color(0xFFEF4444)) // red
        else -> Pair(Icons.Default.FileCopy, MaterialTheme.colorScheme.primary)
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(iconInfo.second.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = iconInfo.first,
                            contentDescription = "Action Icon",
                            tint = iconInfo.second,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = record.fileName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Record Details / Summary
            Text(
                text = record.resultSummary,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = dateStr,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val isFile = remember(record.filePath) {
                        record.filePath?.let { File(it).isFile } ?: false
                    }
                    if (isFile) {
                        TextButton(
                            onClick = onDownload,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download",
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("下载保存", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    TextButton(
                        onClick = onShare,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("分享发送", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(
                        onClick = onOpen,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Launch,
                            contentDescription = "View",
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("点击查看", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

// Built-in Overlay Reader for TXT
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InteractiveTextReader(
    title: String,
    content: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    
    // Filtered text lines based on search query
    val lines = remember(content) { content.split("\n") }
    val filteredLines = remember(searchQuery, lines) {
        if (searchQuery.isBlank()) {
            lines
        } else {
            lines.filter { it.contains(searchQuery, ignoreCase = true) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header bar
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("PDF Extracted Text", content)
                        clipboardManager.setPrimaryClip(clip)
                        Toast.makeText(context, "已成功复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy")
                    }
                    IconButton(onClick = {
                        // Create temp file and share
                        try {
                            val tempFile = File(context.cacheDir, "${title.substringBeforeLast(".")}_提取文本.txt")
                            tempFile.writeText(content)
                            shareFileInSystem(context, tempFile)
                        } catch (e: Exception) {
                            Toast.makeText(context, "分享失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Share")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )

            // Search Bar
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("在提取的文本中搜索关键字...") },
                singleLine = true,
                leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            )

            if (searchQuery.isNotEmpty()) {
                Text(
                    text = "搜索到 ${filteredLines.size} 行匹配内容",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            // Scrollable text display
            if (filteredLines.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("无匹配结果", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    items(filteredLines) { line ->
                        Text(
                            text = line,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 6.dp),
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        }
    }
}

// Built-in Overlay Reader for extracted image folder gallery
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InteractiveImageGallery(
    title: String,
    folderPath: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    
    // Load local PNG images
    val images = remember(folderPath) {
        val folder = File(folderPath)
        if (folder.exists() && folder.isDirectory) {
            folder.listFiles()?.filter {
                it.isFile && (it.name.endsWith(".png") || it.name.endsWith(".jpg") || it.name.endsWith(".jpeg"))
            }?.sortedBy { file ->
                // Sort by numbers inside name e.g. "page_1" vs "page_2" or "img_p1_1"
                try {
                    val numbers = file.name.filter { it.isDigit() }
                    if (numbers.isNotEmpty()) numbers.toInt() else 0
                } catch (e: Exception) {
                    0
                }
            } ?: emptyList()
        } else {
            emptyList()
        }
    }

    var activeZoomedFile by remember { mutableStateOf<File?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        shareImagesFolder(context, File(folderPath))
                    }) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Share All")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )

            if (images.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("文件夹中没有提取到图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    items(images) { file ->
                        Card(
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clickable { activeZoomedFile = file }
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                // Load local file as bitmap directly
                                val bitmap = remember(file) {
                                    try {
                                        BitmapFactory.decodeFile(file.absolutePath)
                                    } catch (e: Exception) {
                                        null
                                    }
                                }

                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Extracted Image",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(imageVector = Icons.Default.BrokenImage, contentDescription = "Corrupt Image")
                                    }
                                }

                                // Overlay page label
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .padding(4.dp)
                                ) {
                                    Text(
                                        text = file.name,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Expanded full-screen preview popup
        activeZoomedFile?.let { file ->
            Dialog(
                onDismissRequest = { activeZoomedFile = null },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    val bitmap = remember(file) {
                        try {
                            BitmapFactory.decodeFile(file.absolutePath)
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Zoomed Extracted Image",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("无法载入图片", color = Color.White)
                        }
                    }

                    // Top floating bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp, start = 16.dp, end = 16.dp)
                            .align(Alignment.TopCenter),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { activeZoomedFile = null },
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Close Preview",
                                tint = Color.White
                            )
                        }
                        
                        Text(
                            text = file.name,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )

                        IconButton(
                            onClick = { shareFileInSystem(context, file) },
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share Image",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

// Format raw bytes size to readable string e.g. 1.25 MB
private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
}

// Share single file via Android Intent (FileProvider)
private fun shareFileInSystem(context: Context, file: File) {
    try {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        
        val ext = file.extension.lowercase(Locale.getDefault())
        val type = when (ext) {
            "txt" -> "text/plain"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "*/*"
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "发送/共享解压文件"))
    } catch (e: Exception) {
        Log.e("MainActivity", "System share file failed", e)
        Toast.makeText(context, "无法启动系统分享: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

// Open single file in a supported system reader app (PDF/Text)
private fun openFileInSystem(context: Context, file: File) {
    try {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        
        val ext = file.extension.lowercase(Locale.getDefault())
        val type = when (ext) {
            "txt" -> "text/plain"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "*/*"
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.e("MainActivity", "System open file failed", e)
        Toast.makeText(context, "未找到支持打开此类型（.${file.extension}）的阅读器应用", Toast.LENGTH_LONG).show()
    }
}

// Share images inside a directory as multiple images in one send intent
private fun shareImagesFolder(context: Context, folder: File) {
    try {
        val files = folder.listFiles() ?: emptyArray()
        val uris = ArrayList<Uri>()
        val authority = "${context.packageName}.fileprovider"
        
        for (file in files) {
            if (file.isFile && (file.extension.lowercase(Locale.getDefault()) in listOf("png", "jpg", "jpeg"))) {
                val uri = FileProvider.getUriForFile(context, authority, file)
                uris.add(uri)
            }
        }

        if (uris.isEmpty()) {
            Toast.makeText(context, "文件夹中没有可分享的图片", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/png"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "共享提取的所有图片"))
    } catch (e: Exception) {
        Log.e("MainActivity", "System share multiple images failed", e)
        Toast.makeText(context, "分享多图失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

// Download/Save single file to the system's public Downloads directory
private fun downloadFileToPublicDownloads(context: Context, file: File) {
    try {
        val resolver = context.contentResolver
        val ext = file.extension.lowercase(Locale.getDefault())
        val mimeType = when (ext) {
            "txt" -> "text/plain"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }

        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        } else {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val destFile = File(downloadsDir, file.name)
            Uri.fromFile(destFile)
        }

        if (uri != null) {
            resolver.openOutputStream(uri)?.use { out ->
                java.io.FileInputStream(file).use { input ->
                    input.copyTo(out)
                }
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            Toast.makeText(context, "文件已成功下载保存至系统 Downloads (下载) 文件夹！", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "保存失败，无法在系统下载中创建该文件", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Log.e("MainActivity", "Download file failed", e)
        // Fallback: Copy directly for older Android versions or if MediaStore fails
        try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val destFile = File(downloadsDir, file.name)
            java.io.FileInputStream(file).use { input ->
                java.io.FileOutputStream(destFile).use { out ->
                    input.copyTo(out)
                }
            }
            Toast.makeText(context, "文件已成功保存至: ${destFile.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (ex: Exception) {
            Log.e("MainActivity", "Fallback download failed", ex)
            Toast.makeText(context, "保存到本地失败: ${ex.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}
