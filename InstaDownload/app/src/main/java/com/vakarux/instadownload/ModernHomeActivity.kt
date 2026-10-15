package com.vakarux.instadownload

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vakarux.instadownload.ui.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val XPink = Color(0xFFFF4F91)
private val XCoral = Color(0xFFFF845C)
private val XViolet = Color(0xFF9D6BFF)

private enum class ToolkitDestination(val label: String) {
    HOME("Home"), LIBRARY("Library"), CAPTION("Caption Lab"), SETTINGS("Settings")
}

private data class SavedEntry(val title: String, val url: String, val count: Int, val savedAt: Long)

class ModernHomeActivity : ComponentActivity() {
    private val appSettings by lazy { AppSettings(this) }
    private val selectedFolderName = mutableStateOf(AppSettings.DEFAULT_FOLDER_NAME)
    private val incomingUrl = mutableStateOf("")

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val name = runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':')
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: getString(R.string.selected_folder_default)
        appSettings.downloadTreeUri = uri.toString()
        appSettings.downloadFolderName = name
        selectedFolderName.value = name
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedFolderName.value = appSettings.downloadFolderName
        incomingUrl.value = readSharedUrl(intent)
        setContent {
            var selectedTheme by remember { mutableStateOf(appSettings.theme) }
            val dark = when (selectedTheme) {
                AppTheme.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
            }
            InstaDownloadTheme(darkTheme = dark) {
                ModernToolkitScreen(
                    initialUrl = incomingUrl.value,
                    settings = appSettings,
                    selectedTheme = selectedTheme,
                    selectedFolderName = selectedFolderName.value,
                    onThemeChanged = {
                        appSettings.theme = it
                        selectedTheme = it
                    },
                    onChooseFolder = { folderPickerLauncher.launch(null) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUrl.value = readSharedUrl(intent)
    }

    private fun readSharedUrl(intent: Intent?): String {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return ""
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        return Regex("""https?://(?:www\.)?(?:instagram\.com|instagr\.am)/[^\s]+""")
            .find(text)?.value?.trimEnd('.', ',', ')', ']', '}') ?: ""
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModernToolkitScreen(
    initialUrl: String,
    settings: AppSettings,
    selectedTheme: AppTheme,
    selectedFolderName: String,
    onThemeChanged: (AppTheme) -> Unit,
    onChooseFolder: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf(ToolkitDestination.HOME) }
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var media by remember { mutableStateOf<List<MediaResult>>(emptyList()) }
    var selectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var quality by remember { mutableStateOf(settings.quality) }
    var haptics by remember { mutableStateOf(settings.hapticsEnabled) }
    var customWidth by remember { mutableIntStateOf(settings.customWidth) }
    var historyVersion by remember { mutableIntStateOf(0) }
    var captionDraft by remember { mutableStateOf("") }

    LaunchedEffect(media) { captionDraft = media.firstOrNull()?.meta?.caption.orEmpty() }

    val copyText: (String, String) -> Unit = { label, text ->
        if (text.isBlank()) {
            Toast.makeText(context, "Nothing to copy yet", Toast.LENGTH_SHORT).show()
        } else {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
        }
    }

    val fetchMedia: () -> Unit = {
        val trimmed = url.trim()
        when {
            trimmed.isBlank() -> error = "Paste an Instagram link first."
            !isSupportedInstagramLink(trimmed) -> error = "Use a public Instagram post, reel, carousel, or profile link."
            trimmed.contains("/stories/") -> error = "Stories are not supported by this public-link build."
            else -> scope.launch {
                loading = true
                error = null
                status = null
                media = emptyList()
                selectedIndices = emptySet()
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        InstagramDownloader.getMediaItems(trimmed, settings.targetWidth())
                    }
                }
                loading = false
                result.onSuccess { items ->
                    media = items
                    selectedIndices = items.indices.toSet()
                    status = if (items.size > 1) "Found " + items.size + " carousel items." else "Media is ready to save."
                }.onFailure { failure ->
                    error = failure.message ?: "Could not fetch this public post."
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
                ToolkitDestination.values().forEach { item ->
                    val icon = when (item) {
                        ToolkitDestination.HOME -> AppIcons.PlayArrow
                        ToolkitDestination.LIBRARY -> AppIcons.Image
                        ToolkitDestination.CAPTION -> AppIcons.ContentCopy
                        ToolkitDestination.SETTINGS -> AppIcons.Settings
                    }
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = { Icon(icon, contentDescription = item.label, modifier = Modifier.size(22.dp)) },
                        label = { Text(item.label, maxLines = 1) },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp).padding(top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            AppTopBar(
                onSettings = { destination = ToolkitDestination.SETTINGS },
                onHome = { destination = ToolkitDestination.HOME }
            )
            when (destination) {
                ToolkitDestination.HOME -> {
                    HeroPanel()
                    LinkInputCard(
                        url = url,
                        onUrlChange = { url = it; error = null; status = null },
                        loading = loading,
                        onPaste = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val pasted = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                            if (pasted.isNotBlank()) { url = pasted; error = null }
                            else Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                        },
                        onClear = { url = ""; media = emptyList(); selectedIndices = emptySet(); error = null; status = null },
                        onFetch = fetchMedia
                    )
                    if (loading) LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(), color = XPink,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    error?.let { FeedbackBanner(it, true) }
                    status?.let { FeedbackBanner(it, false) }
                    if (media.isNotEmpty()) {
                        MediaResultPanel(
                            items = media,
                            selectedIndices = selectedIndices,
                            onToggle = { index ->
                                selectedIndices = if (index in selectedIndices) selectedIndices - index else selectedIndices + index
                            },
                            onSelectAll = { selectedIndices = media.indices.toSet() },
                            onSelectNone = { selectedIndices = emptySet() },
                            saving = saving,
                            onDownload = {
                                if (selectedIndices.isEmpty()) {
                                    error = "Select at least one item to save."
                                } else {
                                    scope.launch {
                                        saving = true
                                        error = null
                                        status = null
                                        val chosen = media.filterIndexed { index, _ -> index in selectedIndices }
                                        val result = runCatching {
                                            withContext(Dispatchers.IO) {
                                                chosen.forEach { item -> saveToDownloads(item, context, settings) }
                                            }
                                        }
                                        saving = false
                                        result.onSuccess {
                                            vibrate(context, haptics)
                                            val title = chosen.firstOrNull()?.meta?.caption
                                                ?.lineSequence()?.firstOrNull()?.take(64)?.ifBlank { null }
                                                ?: chosen.firstOrNull()?.baseName ?: "Instagram media"
                                            saveHistory(context, SavedEntry(title, url.trim(), chosen.size, System.currentTimeMillis()))
                                            historyVersion++
                                            status = "Saved " + chosen.size + if (chosen.size == 1) " item to Downloads." else " items to Downloads."
                                            Toast.makeText(context, "Saved to Downloads/InstaDownload", Toast.LENGTH_LONG).show()
                                        }.onFailure { error = it.message ?: "Download failed. Try again." }
                                    }
                                }
                            },
                            onCopyCaption = { copyText("Caption", media.firstOrNull()?.meta?.caption.orEmpty()) },
                            onCopyHashtags = { copyText("Hashtags", extractHashtags(media.firstOrNull()?.meta?.caption.orEmpty())) },
                            onCopyFull = { copyText("Full caption", media.firstOrNull()?.meta?.caption.orEmpty()) },
                            onOpenCaptionLab = {
                                captionDraft = media.firstOrNull()?.meta?.caption.orEmpty()
                                destination = ToolkitDestination.CAPTION
                            }
                        )
                    } else if (!loading) QuickTipsPanel()
                }
                ToolkitDestination.LIBRARY -> LibraryPanel(
                    refreshKey = historyVersion,
                    onCopyLink = { copyText("Instagram link", it) },
                    onClear = { clearHistory(context); historyVersion++ }
                )
                ToolkitDestination.CAPTION -> CaptionLabPanel(
                    caption = captionDraft,
                    onCaptionChange = { captionDraft = it },
                    onCopy = copyText
                )
                ToolkitDestination.SETTINGS -> SettingsPanel(
                    selectedTheme = selectedTheme,
                    quality = quality,
                    haptics = haptics,
                    customWidth = customWidth,
                    selectedFolderName = selectedFolderName,
                    onThemeChanged = onThemeChanged,
                    onQualityChanged = { quality = it; settings.quality = it },
                    onHapticsChanged = { haptics = it; settings.hapticsEnabled = it },
                    onCustomWidthChanged = { customWidth = it; settings.customWidth = it },
                    onChooseFolder = onChooseFolder
                )
            }
            Text(
                "InstaTube Toolkit  •  by XboidX  •  v1.1.0",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AppTopBar(onSettings: () -> Unit, onHome: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(XPink, XCoral, XViolet))).clickable(onClick = onHome),
            contentAlignment = Alignment.Center
        ) {
            Icon(AppIcons.Download, contentDescription = "InstaTube home", tint = Color.White, modifier = Modifier.size(27.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("INSTAtUBE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            Text("Toolkit", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
            IconButton(onClick = onSettings, modifier = Modifier.size(46.dp)) {
                Icon(AppIcons.Settings, contentDescription = "Open settings", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun HeroPanel() {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF32183F), Color(0xFF6B1E53), Color(0xFF9E3157))))
            .padding(22.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = Color.White.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
                Text("PUBLIC INSTAGRAM MEDIA", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Text("Keep the posts\nyou want to keep.", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, color = Color.White))
            Text("Reels, photos and carousels — preview first, then save only what you need.", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.82f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FeaturePill("Preview first"); FeaturePill("Caption tools"); FeaturePill("Local history")
            }
        }
    }
}

@Composable
private fun FeaturePill(label: String) {
    Surface(color = Color.White.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
    }
}

@Composable
private fun LinkInputCard(
    url: String, onUrlChange: (String) -> Unit, loading: Boolean,
    onPaste: () -> Unit, onClear: () -> Unit, onFetch: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add a link", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("01 / FETCH", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
            OutlinedTextField(
                value = url, onValueChange = onUrlChange, modifier = Modifier.fillMaxWidth(),
                label = { Text("Instagram URL") }, placeholder = { Text("Paste a Reel, post or carousel link") },
                leadingIcon = { Icon(AppIcons.PlayArrow, contentDescription = null, tint = XPink) },
                trailingIcon = {
                    Row {
                        IconButton(onClick = onPaste) { Icon(AppIcons.ContentPaste, contentDescription = "Paste link", tint = XPink) }
                        if (url.isNotBlank()) IconButton(onClick = onClear) {
                            Text("×", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                minLines = 1, maxLines = 3, shape = RoundedCornerShape(17.dp)
            )
            Button(
                onClick = onFetch, modifier = Modifier.fillMaxWidth().height(54.dp), enabled = !loading,
                shape = RoundedCornerShape(17.dp),
                colors = ButtonDefaults.buttonColors(containerColor = XPink, contentColor = Color.White)
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp)); Text("Checking public media…")
                } else {
                    Icon(AppIcons.PlayArrow, contentDescription = null); Spacer(Modifier.width(8.dp))
                    Text("Fetch media", fontWeight = FontWeight.Bold)
                }
            }
            Text(
                "Only public links that Instagram exposes are supported. Private or login-gated content won't be bypassed.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun FeedbackBanner(message: String, isError: Boolean) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else Color(0xFF183B30),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (isError) "!" else "✓", color = if (isError) MaterialTheme.colorScheme.onErrorContainer else Color(0xFF8BE4BD), fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text(message, color = if (isError) MaterialTheme.colorScheme.onErrorContainer else Color(0xFFB9F2D8), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MediaResultPanel(
    items: List<MediaResult>, selectedIndices: Set<Int>, onToggle: (Int) -> Unit,
    onSelectAll: () -> Unit, onSelectNone: () -> Unit, saving: Boolean,
    onDownload: () -> Unit, onCopyCaption: () -> Unit, onCopyHashtags: () -> Unit,
    onCopyFull: () -> Unit, onOpenCaptionLab: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Preview & save", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(items.size.toString() + if (items.size == 1) " media item" else " media items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(color = Color(0x1FFF4F91), shape = RoundedCornerShape(50)) {
                    Text(selectedIndices.size.toString() + " selected", Modifier.padding(horizontal = 11.dp, vertical = 7.dp), color = XPink, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onSelectAll, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Select all") }
                OutlinedButton(onClick = onSelectNone, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Clear selection") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 3.dp)) {
                itemsIndexed(items) { index, item -> MediaPreviewTile(item, index, index in selectedIndices) { onToggle(index) } }
            }
            val caption = items.firstOrNull()?.meta?.caption.orEmpty()
            if (caption.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("CAPTION", style = MaterialTheme.typography.labelMedium, color = XPink, fontWeight = FontWeight.Bold)
                    Text(caption, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onCopyCaption, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Icon(AppIcons.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Caption", maxLines = 1)
                        }
                        OutlinedButton(onClick = onCopyHashtags, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("#", fontWeight = FontWeight.Bold); Spacer(Modifier.width(5.dp)); Text("Hashtags", maxLines = 1)
                        }
                    }
                    OutlinedButton(onClick = onCopyFull, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Copy full caption") }
                    TextButton(onClick = onOpenCaptionLab) { Text("Open Caption Lab →", color = XPink) }
                }
            }
            Button(
                onClick = onDownload, modifier = Modifier.fillMaxWidth().height(54.dp),
                enabled = !saving && selectedIndices.isNotEmpty(), shape = RoundedCornerShape(17.dp),
                colors = ButtonDefaults.buttonColors(containerColor = XPink, contentColor = Color.White)
            ) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp)); Text("Saving media…")
                } else {
                    Icon(AppIcons.Download, contentDescription = null); Spacer(Modifier.width(8.dp))
                    Text("Save " + selectedIndices.size + if (selectedIndices.size == 1) " item" else " items", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun MediaPreviewTile(item: MediaResult, index: Int, selected: Boolean, onClick: () -> Unit) {
    var preview by remember(item.previewUrl) { mutableStateOf<ImageBitmap?>(null) }
    var previewFailed by remember(item.previewUrl) { mutableStateOf(false) }
    LaunchedEffect(item.previewUrl) {
        val imageUrl = item.previewUrl ?: return@LaunchedEffect
        val bitmap = runCatching {
            withContext(Dispatchers.IO) {
                val bytes = InstagramDownloader.fetchBytes(imageUrl)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }.getOrNull()
        preview = bitmap
        previewFailed = bitmap == null
    }
    Card(
        Modifier.width(174.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) XPink else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().height(218.dp).background(Brush.linearGradient(listOf(Color(0xFF28162E), Color(0xFF4A2039)))),
                contentAlignment = Alignment.Center
            ) {
                if (preview != null) Image(preview!!, contentDescription = if (item.isVideo) "Video preview" else "Image preview", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (item.isVideo) AppIcons.PlayArrow else AppIcons.Image, contentDescription = null, tint = Color.White.copy(alpha = 0.86f), modifier = Modifier.size(34.dp))
                    Text(if (previewFailed) "Preview unavailable" else "Loading preview…", color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall)
                }
                Surface(Modifier.align(Alignment.TopEnd).padding(8.dp), color = if (selected) XPink else Color.Black.copy(alpha = 0.65f), shape = CircleShape) {
                    Text(if (selected) "✓" else (index + 1).toString(), color = Color.White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                if (item.isVideo) Surface(Modifier.align(Alignment.BottomStart).padding(8.dp), color = Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(8.dp)) {
                    Text("VIDEO", color = Color.White, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (item.isVideo) "Video / Reel" else "Photo", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(if (item.width > 0 && item.height > 0) item.width.toString() + " × " + item.height else "Resolution varies", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun QuickTipsPanel() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Made for quick saves", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TipCard("01", "Paste a link", "Copy a public Instagram post or Reel URL.", Modifier.weight(1f))
            TipCard("02", "Preview first", "Choose carousel items before saving.", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TipCard("03", "Copy caption", "Keep caption text and hashtags ready to reuse.", Modifier.weight(1f))
            TipCard("04", "Stay organized", "Find recent saves in your local library.", Modifier.weight(1f))
        }
    }
}

@Composable
private fun TipCard(number: String, title: String, body: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(number, color = XPink, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LibraryPanel(refreshKey: Int, onCopyLink: (String) -> Unit, onClear: () -> Unit) {
    val context = LocalContext.current
    val entries = remember(refreshKey) { loadHistory(context) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Your Library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Recent saves are stored locally on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        if (entries.isEmpty()) EmptyStateCard("Nothing saved yet", "Fetch a public post and save it. Your recent activity will appear here.", AppIcons.Image)
        else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entries.size.toString() + " recent saves", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onClear) { Text("Clear history", color = XPink) }
            }
            entries.forEach { entry ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(XPink, XViolet))), contentAlignment = Alignment.Center) {
                            Icon(AppIcons.Download, contentDescription = null, tint = Color.White)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(entry.title.ifBlank { "Instagram media" }, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(entry.count.toString() + if (entry.count == 1) " item saved" else " items saved", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(SimpleDateFormat("dd MMM · hh:mm a", Locale.getDefault()).format(Date(entry.savedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onCopyLink(entry.url) }) { Icon(AppIcons.ContentCopy, contentDescription = "Copy source link", tint = XPink) }
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), shape = RoundedCornerShape(16.dp)) {
            Text("Library history records what you saved in this app; it does not scan files created by other apps.", Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CaptionLabPanel(caption: String, onCaptionChange: (String) -> Unit, onCopy: (String, String) -> Unit) {
    val hashtags = remember(caption) { extractHashtags(caption) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Caption Lab", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Edit, extract and copy the text you want to reuse.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = caption, onValueChange = onCaptionChange, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Caption text") }, placeholder = { Text("Fetch a link first or paste caption text here") },
                    minLines = 6, maxLines = 14, shape = RoundedCornerShape(16.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onCopy("Caption", caption.trim()) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                        Icon(AppIcons.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Copy caption")
                    }
                    OutlinedButton(onClick = { onCopy("Hashtags", hashtags) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                        Text("#", fontWeight = FontWeight.Bold); Spacer(Modifier.width(5.dp)); Text("Copy tags")
                    }
                }
                Divider()
                val literalTagsExist = Regex("(?<![\\w#])#[\\p{L}\\p{N}_]+").containsMatchIn(caption)
                val keywordBlockExists = Regex("\\{([^{}]+)}\\s*$").containsMatchIn(caption)
                Text(
                    when {
                        literalTagsExist -> "HASHTAGS FOUND"
                        keywordBlockExists -> "SUGGESTED TAGS FROM KEYWORDS"
                        else -> "HASHTAGS"
                    },
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = XPink
                )
                if (hashtags.isBlank()) Text("No hashtags or keyword list found in this text yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    Text(hashtags, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onCopy("Hashtags", hashtags) }) { Text("Copy only hashtags →", color = XPink) }
                }
            }
        }
        Text("Caption metadata depends on what Instagram exposes. Literal #hashtags are extracted first; if no literal tags exist, a trailing {comma-separated keyword list} is converted into clearly labelled hashtag suggestions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsPanel(
    selectedTheme: AppTheme, quality: DownloadQuality, haptics: Boolean,
    customWidth: Int, selectedFolderName: String, onThemeChanged: (AppTheme) -> Unit,
    onQualityChanged: (DownloadQuality) -> Unit, onHapticsChanged: (Boolean) -> Unit,
    onCustomWidthChanged: (Int) -> Unit, onChooseFolder: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Tune quality, storage and the way the app feels.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("DOWNLOAD QUALITY", style = MaterialTheme.typography.labelMedium, color = XPink, fontWeight = FontWeight.Bold)
                DownloadQuality.values().forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onQualityChanged(option) }.padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = quality == option, onClick = { onQualityChanged(option) })
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(androidx.compose.ui.res.stringResource(option.labelRes), fontWeight = FontWeight.SemiBold)
                            Text(androidx.compose.ui.res.stringResource(option.descriptionRes), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (quality == DownloadQuality.CUSTOM) {
                    Text("Target width: " + customWidth + " px", style = MaterialTheme.typography.labelLarge)
                    Slider(value = customWidth.toFloat(), onValueChange = { onCustomWidthChanged(it.toInt()) }, valueRange = 320f..1080f, steps = 7)
                }
            }
        }
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("APPEARANCE", style = MaterialTheme.typography.labelMedium, color = XPink, fontWeight = FontWeight.Bold)
                AppTheme.values().forEach { option ->
                    Row(Modifier.fillMaxWidth().clickable { onThemeChanged(option) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedTheme == option, onClick = { onThemeChanged(option) })
                        Spacer(Modifier.width(9.dp)); Text(androidx.compose.ui.res.stringResource(option.labelRes))
                    }
                }
                Divider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Haptic feedback", fontWeight = FontWeight.SemiBold)
                        Text("Vibrate on download events", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = haptics, onCheckedChange = onHapticsChanged)
                }
            }
        }
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("STORAGE LOCATION", style = MaterialTheme.typography.labelMedium, color = XPink, fontWeight = FontWeight.Bold)
                Text(selectedFolderName, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onChooseFolder, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Choose folder") }
                Text("Default location: Downloads/InstaDownload", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("This app works with public media responses only. It does not log in to Instagram or bypass private-content restrictions.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyStateCard(title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(62.dp).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(XPink, XViolet))), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun isSupportedInstagramLink(value: String): Boolean {
    val uri = runCatching { Uri.parse(value.trim()) }.getOrNull() ?: return false
    if (uri.scheme != "http" && uri.scheme != "https") return false
    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    if (host !in setOf("instagram.com", "www.instagram.com", "instagr.am", "www.instagr.am")) return false
    val parts = uri.pathSegments
    if (parts.isEmpty()) return false
    if (parts.first().lowercase(Locale.ROOT) in setOf("p", "reel", "reels", "tv")) return parts.size >= 2
    if (parts.first().lowercase(Locale.ROOT) in setOf("stories", "explore", "accounts", "direct")) return false
    return parts.size == 1 && parts.first().isNotBlank()
}

private fun extractHashtags(text: String): String {
    val literal = Regex("(?<![\\w#])#[\\p{L}\\p{N}_]+")
        .findAll(text).map { it.value }.distinct().toList()
    if (literal.isNotEmpty()) return literal.joinToString(" ")

    // Instagram caption-like payloads sometimes append a brace-wrapped comma-separated keyword list.
    // Convert those source terms into suggestions only when the caption has no literal hashtags.
    val keywordBlock = Regex("\\{([^{}]+)}\\s*$").find(text)?.groupValues?.get(1) ?: return ""
    return keywordBlock.split(',').map { phrase ->
        val tagBody = phrase.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString("") { word ->
            word.replace(Regex("[^\\p{L}\\p{N}_]"), "").replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
        if (tagBody.isBlank()) "" else "#" + tagBody
    }.filter { it.length > 1 }.distinct().joinToString(" ")
}

private fun loadHistory(context: Context): List<SavedEntry> = runCatching {
    val array = JSONArray(context.getSharedPreferences("instatube_toolkit", Context.MODE_PRIVATE).getString("saved_history", "[]"))
    (0 until array.length()).mapNotNull { index ->
        val obj = array.optJSONObject(index) ?: return@mapNotNull null
        SavedEntry(obj.optString("title", "Instagram media"), obj.optString("url"), obj.optInt("count", 1), obj.optLong("savedAt", 0L))
    }
}.getOrDefault(emptyList())

private fun saveHistory(context: Context, entry: SavedEntry) {
    val recent = (listOf(entry) + loadHistory(context)).take(12)
    val array = JSONArray()
    recent.forEach { item ->
        array.put(JSONObject().put("title", item.title).put("url", item.url).put("count", item.count).put("savedAt", item.savedAt))
    }
    context.getSharedPreferences("instatube_toolkit", Context.MODE_PRIVATE).edit().putString("saved_history", array.toString()).apply()
}

private fun clearHistory(context: Context) {
    context.getSharedPreferences("instatube_toolkit", Context.MODE_PRIVATE).edit().remove("saved_history").apply()
}

private fun vibrate(context: Context, enabled: Boolean) {
    if (!enabled) return
    runCatching {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
