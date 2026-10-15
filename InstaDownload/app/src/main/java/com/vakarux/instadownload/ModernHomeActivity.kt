package com.vakarux.instadownload

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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

private val RexAccent = Color(0xFFFF477E)
private val RexAccentSoft = Color(0xFFFFD7E4)
private val RexViolet = Color(0xFF8B73FF)
private val RexCoral = Color(0xFFFF8868)

private enum class RexTab(val label: String) {
    HOME("Home"), LIBRARY("Library"), CAPTION("Captions"), SETTINGS("Settings")
}
private enum class MediaSource(val label: String) { INSTAGRAM("Instagram"), YOUTUBE("YouTube") }
private data class RexSavedItem(
    val title: String,
    val url: String,
    val count: Int,
    val savedAt: Long,
    val source: String = "Instagram"
)

class ModernHomeActivity : ComponentActivity() {
    private val appSettings by lazy { AppSettings(this) }
    private val selectedFolderName = mutableStateOf(AppSettings.DEFAULT_FOLDER_NAME)
    private val sharedUrl = mutableStateOf("")

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedFolderName.value = appSettings.downloadFolderName
        sharedUrl.value = extractSharedUrl(intent)
        setContent {
            var themeChoice by remember { mutableStateOf(appSettings.theme) }
            val isDark = when (themeChoice) {
                AppTheme.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
            }
            InstaDownloadTheme(darkTheme = isDark) {
                RexToolkitRoot(
                    incomingUrl = sharedUrl.value,
                    settings = appSettings,
                    themeChoice = themeChoice,
                    folderName = selectedFolderName.value,
                    onThemeChoice = { appSettings.theme = it; themeChoice = it },
                    onChooseFolder = { folderPickerLauncher.launch(null) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedUrl.value = extractSharedUrl(intent)
    }

    private fun extractSharedUrl(intent: Intent?): String {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return ""
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        return Regex("""https?://[^\s]+""").find(text)?.value?.trimEnd('.', ',', ')', ']', '}') ?: ""
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RexToolkitRoot(
    incomingUrl: String,
    settings: AppSettings,
    themeChoice: AppTheme,
    folderName: String,
    onThemeChoice: (AppTheme) -> Unit,
    onChooseFolder: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(RexTab.HOME) }
    var source by remember { mutableStateOf(if (YouTubeResolver.isYouTubeUrl(incomingUrl)) MediaSource.YOUTUBE else MediaSource.INSTAGRAM) }
    var instagramUrl by rememberSaveable { mutableStateOf("") }
    var youtubeUrl by rememberSaveable { mutableStateOf("") }
    var isFetching by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var isSavingAudio by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var infoText by remember { mutableStateOf<String?>(null) }
    var instagramItems by remember { mutableStateOf<List<MediaResult>>(emptyList()) }
    var selectedItems by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var youtubeInfo by remember { mutableStateOf<YouTubeMetadata?>(null) }
    var youtubeTranscript by rememberSaveable { mutableStateOf("") }
    var isTranscriptLoading by remember { mutableStateOf(false) }
    var captionText by rememberSaveable { mutableStateOf("") }
    var quality by remember { mutableStateOf(settings.quality) }
    var haptics by remember { mutableStateOf(settings.hapticsEnabled) }
    var customWidth by remember { mutableIntStateOf(settings.customWidth) }
    var libraryRefresh by remember { mutableIntStateOf(0) }

    val activeUrl = if (source == MediaSource.INSTAGRAM) instagramUrl else youtubeUrl
    val setActiveUrl: (String) -> Unit = { value ->
        // If the clipboard contains a link from the other supported platform, switch scopes automatically.
        when {
            value.isNotBlank() && YouTubeResolver.isYouTubeUrl(value) -> {
                source = MediaSource.YOUTUBE
                youtubeUrl = value
            }
            value.isNotBlank() && isInstagramLink(value) -> {
                source = MediaSource.INSTAGRAM
                instagramUrl = value
            }
            source == MediaSource.INSTAGRAM -> instagramUrl = value
            else -> youtubeUrl = value
        }
        errorText = null
        infoText = null
    }

    LaunchedEffect(incomingUrl) {
        if (incomingUrl.isNotBlank()) {
            if (YouTubeResolver.isYouTubeUrl(incomingUrl)) {
                source = MediaSource.YOUTUBE
                youtubeUrl = incomingUrl
            } else if (isInstagramLink(incomingUrl)) {
                source = MediaSource.INSTAGRAM
                instagramUrl = incomingUrl
            }
            tab = RexTab.HOME
        }
    }

    val copyToClipboard: (String, String) -> Unit = { label, value ->
        if (value.isBlank()) Toast.makeText(context, "Nothing to copy yet", Toast.LENGTH_SHORT).show()
        else {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
            Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
        }
    }
    val openExternal: (String) -> Unit = { target ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
            .onFailure { Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show() }
    }

    val fetchCurrent: () -> Unit = {
        val candidate = activeUrl.trim()
        when (source) {
            MediaSource.INSTAGRAM -> {
                when {
                    candidate.isBlank() -> errorText = "Paste an Instagram link to continue."
                    !isInstagramLink(candidate) -> errorText = "Use a valid Instagram post, Reel, carousel or public profile link."
                    candidate.contains("/stories/") -> errorText = "Stories aren't supported in this public-link build."
                    else -> scope.launch {
                        isFetching = true
                        errorText = null
                        infoText = null
                        instagramItems = emptyList()
                        selectedItems = emptySet()
                        youtubeInfo = null
                        val result = runCatching {
                            withContext(Dispatchers.IO) { InstagramDownloader.getMediaItems(candidate, settings.targetWidth()) }
                        }
                        isFetching = false
                        result.onSuccess { items ->
                            instagramItems = items
                            selectedItems = items.indices.toSet()
                            captionText = items.firstOrNull()?.meta?.caption.orEmpty()
                            infoText = if (items.size > 1) "${items.size} carousel items found" else "Preview is ready"
                        }.onFailure { errorText = friendlyInstagramError(it.message) }
                    }
                }
            }
            MediaSource.YOUTUBE -> {
                when {
                    candidate.isBlank() -> errorText = "Paste a YouTube video link to continue."
                    !YouTubeResolver.isYouTubeUrl(candidate) -> errorText = "Use a YouTube watch, Shorts or youtu.be link."
                    else -> scope.launch {
                        isFetching = true
                        errorText = null
                        infoText = null
                        youtubeInfo = null
                        youtubeTranscript = ""
                        instagramItems = emptyList()
                        selectedItems = emptySet()
                        val result = runCatching { withContext(Dispatchers.IO) { YouTubeResolver.fetchMetadata(candidate) } }
                        isFetching = false
                        result.onSuccess {
                            youtubeInfo = it
                            infoText = "Public video details loaded"
                        }.onFailure { errorText = it.message ?: "Video details aren't available for this link." }
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 7.dp)) {
                RexBottomBar(selected = tab, onSelect = { tab = it })
            }
        }
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(scaffoldPadding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RexTopBar(
                onGitHub = { openExternal("https://github.com/xboyrex/instatube-toolkit-by-xboidx") },
                onCreator = { openExternal("https://www.youtube.com/results?search_query=Xboy+Rex") },
                onSettings = { tab = RexTab.SETTINGS }
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    RexTab.HOME -> RexHomeScreen(
                        source = source,
                        onSourceChange = { newSource ->
                            source = newSource
                            errorText = null
                            infoText = null
                        },
                        activeUrl = activeUrl,
                        onUrlChange = setActiveUrl,
                        isFetching = isFetching,
                        isSaving = isSaving,
                        isSavingAudio = isSavingAudio,
                        errorText = errorText,
                        infoText = infoText,
                        instagramItems = instagramItems,
                        selectedItems = selectedItems,
                        youtubeInfo = youtubeInfo,
                        youtubeTranscript = youtubeTranscript,
                        isTranscriptLoading = isTranscriptLoading,
                        onUseTranscriptInCaption = { if (youtubeTranscript.isNotBlank()) { captionText = youtubeTranscript; tab = RexTab.CAPTION } },
                        onLoadTranscript = {
                            val loadedInfo = youtubeInfo
                            if (loadedInfo == null) {
                                errorText = "Preview a YouTube link first."
                            } else if (loadedInfo.transcriptUrl.isNullOrBlank()) {
                                errorText = "This video does not expose a public caption track. Protected audio is not extracted."
                            } else {
                                scope.launch {
                                    isTranscriptLoading = true
                                    errorText = null
                                    val transcriptResult = runCatching { withContext(Dispatchers.IO) { YouTubeResolver.fetchTranscript(loadedInfo) } }
                                    isTranscriptLoading = false
                                    transcriptResult.onSuccess { text ->
                                        youtubeTranscript = text
                                        infoText = "Public captions loaded. You can copy the transcript."
                                    }.onFailure { errorText = it.message ?: "Transcript is unavailable." }
                                }
                            }
                        },
                        onPaste = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clipboardValue = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                            if (clipboardValue.isNotBlank()) {
                                val urlMatch = Regex("""https?://[^\s]+""").find(clipboardValue)?.value ?: clipboardValue
                                setActiveUrl(urlMatch.trimEnd('.', ',', ')', ']', '}'))
                            } else Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                        },
                        onClear = {
                            setActiveUrl("")
                            instagramItems = emptyList()
                            selectedItems = emptySet()
                            youtubeInfo = null
                            youtubeTranscript = ""
                            errorText = null
                            infoText = null
                        },
                        onFetch = fetchCurrent,
                        onToggleItem = { index -> selectedItems = if (index in selectedItems) selectedItems - index else selectedItems + index },
                        onSelectAll = { selectedItems = instagramItems.indices.toSet() },
                        onSelectNone = { selectedItems = emptySet() },
                        onSaveInstagram = {
                            if (selectedItems.isEmpty()) errorText = "Select at least one item first."
                            else scope.launch {
                                isSaving = true
                                errorText = null
                                val selected = instagramItems.filterIndexed { idx, _ -> idx in selectedItems }
                                val result = runCatching {
                                    withContext(Dispatchers.IO) { selected.forEach { saveToDownloads(it, context, settings) } }
                                }
                                isSaving = false
                                result.onSuccess {
                                    val title = selected.firstOrNull()?.meta?.caption?.lineSequence()?.firstOrNull()?.take(64)
                                        ?.ifBlank { null } ?: selected.firstOrNull()?.baseName ?: "Instagram media"
                                    addLibraryEntry(context, RexSavedItem(title, instagramUrl.trim(), selected.size, System.currentTimeMillis(), "Instagram"))
                                    libraryRefresh++
                                    infoText = "Saved ${selected.size} ${if (selected.size == 1) "item" else "items"}"
                                    Toast.makeText(context, "Saved to Downloads/InstaDownload", Toast.LENGTH_LONG).show()
                                }.onFailure { errorText = it.message ?: "Saving failed. Try again." }
                            }
                        },
                        onSaveInstagramAudio = {
                            val selectedVideoIndex = instagramItems.indices.firstOrNull { index ->
                                index in selectedItems && instagramItems[index].isVideo
                            }
                            val selectedVideo = selectedVideoIndex?.let { instagramItems[it] }
                            if (selectedVideo == null) {
                                errorText = "Select at least one video/Reel first."
                            } else {
                                scope.launch {
                                    isSavingAudio = true
                                    errorText = null
                                    val audioResult = runCatching {
                                        withContext(Dispatchers.IO) { saveAudioToMusic(selectedVideo, context) }
                                    }
                                    isSavingAudio = false
                                    audioResult.onSuccess { path ->
                                        infoText = "Audio saved to $path"
                                        Toast.makeText(context, "Audio saved to Music/InstaTube", Toast.LENGTH_LONG).show()
                                    }.onFailure { errorText = it.message ?: "Audio extraction failed. This Reel may not have an extractable audio track." }
                                }
                            }
                        },
                        onSaveYouTubeLink = {
                            youtubeInfo?.let { info ->
                                addLibraryEntry(context, RexSavedItem(info.title, info.canonicalUrl, 1, System.currentTimeMillis(), "YouTube"))
                                libraryRefresh++
                                infoText = "Video link saved to Library"
                            }
                        },
                        onOpenYouTube = { youtubeInfo?.let { openExternal(it.canonicalUrl) } },
                        onYouTubeDownloadOptions = {
                            Toast.makeText(context, "Use YouTube’s built-in Download option where available, or a creator-provided download link. Protected streams are not extracted.", Toast.LENGTH_LONG).show()
                            youtubeInfo?.let { openExternal(it.canonicalUrl) }
                        },
                        onCopy = copyToClipboard,
                        onOpenCaptions = { tab = RexTab.CAPTION },
                        onResetStatus = { errorText = null; infoText = null }
                    )
                    RexTab.LIBRARY -> RexLibraryScreen(
                        refreshKey = libraryRefresh,
                        onCopy = { copyToClipboard("Source link", it) },
                        onOpen = openExternal,
                        onClear = { clearLibrary(context); libraryRefresh++ }
                    )
                    RexTab.CAPTION -> RexCaptionLabScreen(
                        text = captionText,
                        onTextChange = { captionText = it },
                        onCopy = copyToClipboard
                    )
                    RexTab.SETTINGS -> RexSettingsScreen(
                        themeChoice = themeChoice,
                        quality = quality,
                        haptics = haptics,
                        customWidth = customWidth,
                        folderName = folderName,
                        onThemeChoice = onThemeChoice,
                        onQuality = { quality = it; settings.quality = it },
                        onHaptics = { haptics = it; settings.hapticsEnabled = it },
                        onCustomWidth = { customWidth = it; settings.customWidth = it },
                        onChooseFolder = onChooseFolder,
                        onOpen = openExternal
                    )
                }
            }
        }
    }
}

@Composable
private fun RexTopBar(onGitHub: () -> Unit, onCreator: () -> Unit, onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = R.drawable.instatube_brand_mark),
            contentDescription = "InstaTube logo",
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)),
            contentScale = ContentScale.Fit
        )
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("InstaTube", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("Built by Xboy Rex", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onGitHub, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("GitHub", color = RexAccent, fontWeight = FontWeight.SemiBold) }
        IconButton(onClick = onCreator) {
            Text("YT", color = RexAccent, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelLarge)
        }
        IconButton(onClick = onSettings) { Icon(AppIcons.Settings, contentDescription = "Settings") }
    }
}

@Composable
private fun RexBottomBar(selected: RexTab, onSelect: (RexTab) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
        tonalElevation = 8.dp,
        shadowElevation = 16.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 7.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RexTab.values().forEach { destination ->
                val selectedNow = selected == destination
                val icon = when (destination) {
                    RexTab.HOME -> AppIcons.PlayArrow
                    RexTab.LIBRARY -> AppIcons.Image
                    RexTab.CAPTION -> AppIcons.ContentCopy
                    RexTab.SETTINGS -> AppIcons.Settings
                }
                Column(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(15.dp)).clickable { onSelect(destination) }
                        .padding(vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        modifier = Modifier.width(47.dp).height(29.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (selectedNow) RexAccent.copy(alpha = 0.14f) else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = destination.label, tint = if (selectedNow) RexAccent else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(21.dp))
                    }
                    Text(destination.label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        fontWeight = if (selectedNow) FontWeight.Bold else FontWeight.Normal,
                        color = if (selectedNow) RexAccent else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RexHomeScreen(
    source: MediaSource,
    onSourceChange: (MediaSource) -> Unit,
    activeUrl: String,
    onUrlChange: (String) -> Unit,
    isFetching: Boolean,
    isSaving: Boolean,
    isSavingAudio: Boolean,
    errorText: String?,
    infoText: String?,
    instagramItems: List<MediaResult>,
    selectedItems: Set<Int>,
    youtubeInfo: YouTubeMetadata?,
    youtubeTranscript: String,
    isTranscriptLoading: Boolean,
    onLoadTranscript: () -> Unit,
    onUseTranscriptInCaption: () -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onFetch: () -> Unit,
    onToggleItem: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onSaveInstagram: () -> Unit,
    onSaveInstagramAudio: () -> Unit,
    onSaveYouTubeLink: () -> Unit,
    onOpenYouTube: () -> Unit,
    onYouTubeDownloadOptions: () -> Unit,
    onCopy: (String, String) -> Unit,
    onOpenCaptions: () -> Unit,
    onResetStatus: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Your media, organized.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("A clean space for public links, previews and captions.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        RexSegmentedSources(selected = source, onSelect = { onResetStatus(); onSourceChange(it) })
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (source == MediaSource.INSTAGRAM) "Instagram link" else "YouTube link", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(if (source == MediaSource.INSTAGRAM) "Post · Reel · carousel · public profile" else "Video · Shorts · youtu.be", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("PASTE LINK", style = MaterialTheme.typography.labelSmall, color = RexAccent, fontWeight = FontWeight.Bold)
                }
                TextField(
                    value = activeUrl,
                    onValueChange = onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (source == MediaSource.INSTAGRAM) "instagram.com/reel/..." else "youtube.com/watch?v=...") },
                    leadingIcon = { Icon(if (source == MediaSource.INSTAGRAM) AppIcons.Image else AppIcons.PlayArrow, contentDescription = null, tint = RexAccent) },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = onPaste) { Icon(AppIcons.ContentPaste, contentDescription = "Paste link", tint = RexAccent) }
                            if (activeUrl.isNotBlank()) IconButton(onClick = onClear) { Text("×", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    minLines = 1,
                    maxLines = 3,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )
                Button(
                    onClick = onFetch,
                    enabled = !isFetching && !isSaving,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RexAccent, contentColor = Color.White)
                ) {
                    if (isFetching) {
                        CircularProgressIndicator(modifier = Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(9.dp)); Text("Checking link…")
                    } else {
                        Icon(AppIcons.PlayArrow, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Preview link", fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    if (source == MediaSource.INSTAGRAM)
                        "Public media only. Instagram may limit metadata or change its response."
                    else
                        "Loads public title, channel and thumbnail when available. Playback stays on YouTube; no protected-stream extraction.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (isFetching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = RexAccent)
        errorText?.let { RexMessage(text = it, isError = true) }
        infoText?.let { RexMessage(text = it, isError = false) }

        if (source == MediaSource.INSTAGRAM && instagramItems.isNotEmpty()) {
            RexInstagramResults(
                media = instagramItems,
                selected = selectedItems,
                isSaving = isSaving,
                isSavingAudio = isSavingAudio,
                onToggle = onToggleItem,
                onSelectAll = onSelectAll,
                onSelectNone = onSelectNone,
                onSave = onSaveInstagram,
                onSaveAudio = onSaveInstagramAudio,
                onCopy = onCopy,
                onOpenCaptions = onOpenCaptions
            )
        } else if (source == MediaSource.YOUTUBE && youtubeInfo != null) {
            RexYouTubeResult(
                info = youtubeInfo, transcript = youtubeTranscript, isTranscriptLoading = isTranscriptLoading,
                onOpen = onOpenYouTube, onDownloadOptions = onYouTubeDownloadOptions, onLoadTranscript = onLoadTranscript,
                onUseTranscriptInCaption = onUseTranscriptInCaption, onCopy = onCopy, onSave = onSaveYouTubeLink
            )
        } else if (!isFetching && errorText == null) {
            RexQuickStart(source = source)
        }
    }
}

@Composable
private fun RexSegmentedSources(selected: MediaSource, onSelect: (MediaSource) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MediaSource.values().forEach { item ->
                val active = item == selected
                Surface(
                    modifier = Modifier.weight(1f).clickable { onSelect(item) },
                    color = if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
                    shape = RoundedCornerShape(11.dp),
                    shadowElevation = if (active) 1.dp else 0.dp
                ) {
                    Row(Modifier.padding(vertical = 11.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (item == MediaSource.INSTAGRAM) AppIcons.Image else AppIcons.PlayArrow, contentDescription = null, modifier = Modifier.size(17.dp), tint = if (active) RexAccent else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(7.dp))
                        Text(item.label, style = MaterialTheme.typography.labelLarge, color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun RexMessage(text: String, isError: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else Color(0xFF193C30),
        shape = RoundedCornerShape(15.dp)
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (isError) "!" else "✓", fontWeight = FontWeight.Bold, color = if (isError) MaterialTheme.colorScheme.onErrorContainer else Color(0xFF9BE4C2))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (isError) MaterialTheme.colorScheme.onErrorContainer else Color(0xFFD0F5E2))
        }
    }
}

@Composable
private fun RexInstagramResults(
    media: List<MediaResult>, selected: Set<Int>, isSaving: Boolean, isSavingAudio: Boolean,
    onToggle: (Int) -> Unit, onSelectAll: () -> Unit, onSelectNone: () -> Unit,
    onSave: () -> Unit, onSaveAudio: () -> Unit, onCopy: (String, String) -> Unit, onOpenCaptions: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Preview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${media.size} ${if (media.size == 1) "item" else "items"} available", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text("${selected.size} selected", color = RexAccent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedButton(onClick = onSelectAll, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) { Text("Select all") }
            OutlinedButton(onClick = onSelectNone, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) { Text("Clear") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(11.dp), contentPadding = PaddingValues(end = 2.dp)) {
            itemsIndexed(media) { index, item -> RexMediaTile(item, index, index in selected) { onToggle(index) } }
        }
        val caption = media.firstOrNull()?.meta?.caption.orEmpty()
        if (caption.isNotBlank()) {
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(21.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("CAPTION", style = MaterialTheme.typography.labelMedium, color = RexAccent, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${caption.length} chars", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(caption, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onCopy("Caption", caption) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(horizontal = 7.dp)) {
                            Icon(AppIcons.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Caption")
                        }
                        OutlinedButton(onClick = { onCopy("Hashtags", hashtagsOrSuggestions(caption)) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(horizontal = 7.dp)) {
                            Text("#", fontWeight = FontWeight.Bold); Spacer(Modifier.width(5.dp)); Text("Hashtags")
                        }
                    }
                    OutlinedButton(onClick = { onCopy("Full caption", caption) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) { Text("Copy full caption") }
                    TextButton(onClick = onOpenCaptions, contentPadding = PaddingValues(0.dp)) { Text("Open Caption Studio →", color = RexAccent) }
                }
            }
        }
        Button(
            onClick = onSave, enabled = !isSaving && selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(53.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RexAccent, contentColor = Color.White)
        ) {
            if (isSaving) { CircularProgressIndicator(Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(9.dp)); Text("Saving…") }
            else { Icon(AppIcons.Download, null); Spacer(Modifier.width(8.dp)); Text("Save ${selected.size} ${if (selected.size == 1) "item" else "items"}", fontWeight = FontWeight.Bold) }
        }
        val selectedVideoExists = media.indices.any { index -> index in selected && media[index].isVideo }
        OutlinedButton(
            onClick = onSaveAudio,
            enabled = selectedVideoExists && !isSavingAudio && !isSaving,
            modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(15.dp)
        ) {
            if (isSavingAudio) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = RexAccent)
                Spacer(Modifier.width(8.dp)); Text("Extracting audio…")
            } else {
                Icon(AppIcons.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Extract audio from selected Reel")
            }
        }
        Text("Audio is extracted from the selected public video and saved to Music/InstaTube. Use only media you have permission to save.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RexMediaTile(item: MediaResult, index: Int, selected: Boolean, onClick: () -> Unit) {
    var preview by remember(item.previewUrl) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(item.previewUrl) { mutableStateOf(false) }
    LaunchedEffect(item.previewUrl) {
        val url = item.previewUrl ?: return@LaunchedEffect
        preview = runCatching {
            withContext(Dispatchers.IO) {
                val bytes = InstagramDownloader.fetchBytes(url)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }.getOrNull()
        failed = preview == null
    }
    Card(
        modifier = Modifier.width(156.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) RexAccent else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(190.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                if (preview != null) Image(preview!!, contentDescription = "Media preview ${index + 1}", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(if (failed) "Preview unavailable" else "Loading…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(Modifier.align(Alignment.TopEnd).padding(7.dp), color = if (selected) RexAccent else Color.Black.copy(alpha = .65f), shape = CircleShape) {
                    Text(if (selected) "✓" else "${index + 1}", Modifier.padding(horizontal = 8.dp, vertical = 5.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                if (item.isVideo) Surface(Modifier.align(Alignment.BottomStart).padding(7.dp), color = Color.Black.copy(alpha = .70f), shape = RoundedCornerShape(6.dp)) {
                    Text("VIDEO", Modifier.padding(horizontal = 6.dp, vertical = 4.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (item.isVideo) "Video / Reel" else "Photo", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(if (item.width > 0 && item.height > 0) "${item.width} × ${item.height}" else "Original size", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RexYouTubeResult(
    info: YouTubeMetadata,
    transcript: String,
    isTranscriptLoading: Boolean,
    onOpen: () -> Unit,
    onDownloadOptions: () -> Unit,
    onLoadTranscript: () -> Unit,
    onUseTranscriptInCaption: () -> Unit,
    onCopy: (String, String) -> Unit,
    onSave: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Video preview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Surface(
            modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .58f))
        ) {
            Column {
                RexRemoteThumbnail(url = info.thumbnailUrl, description = "YouTube video thumbnail", modifier = Modifier.fillMaxWidth().height(205.dp))
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("YOUTUBE VIDEO", style = MaterialTheme.typography.labelSmall, color = RexAccent, fontWeight = FontWeight.Bold)
                    Text(info.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(info.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onCopy("YouTube title", info.title) },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)
                        ) { Icon(AppIcons.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Copy title") }
                        OutlinedButton(
                            onClick = { onCopy("YouTube link", info.canonicalUrl) },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)
                        ) { Text("Copy link") }
                    }
                    if (info.description.isNotBlank()) {
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                        Text("DESCRIPTION", style = MaterialTheme.typography.labelMedium, color = RexAccent, fontWeight = FontWeight.Bold)
                        Text(info.description, style = MaterialTheme.typography.bodyMedium, maxLines = 7, overflow = TextOverflow.Ellipsis)
                        OutlinedButton(
                            onClick = { onCopy("YouTube description", info.description) },
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)
                        ) { Icon(AppIcons.ContentCopy, null, Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("Copy full description") }
                    } else {
                        Text("This video does not expose a description in public page metadata.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(
                            onClick = { onCopy("YouTube description", info.description) },
                            enabled = false, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)
                        ) { Text("Description unavailable") }
                    }
                    Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = RexAccent, contentColor = Color.White)) {
                        Icon(AppIcons.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Open in YouTube", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = onDownloadOptions, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Icon(AppIcons.Download, null); Spacer(Modifier.width(8.dp)); Text("Download options in YouTube")
                    }
                    Text(
                        "YouTube does not expose a public direct-download URL here. Use its built-in Download option where available, or a creator-provided downloadable file; this app does not extract protected streams.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = onLoadTranscript,
                        enabled = !isTranscriptLoading && !info.transcriptUrl.isNullOrBlank(),
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)
                    ) {
                        if (isTranscriptLoading) {
                            CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = RexAccent)
                            Spacer(Modifier.width(8.dp)); Text("Loading transcript…")
                        } else {
                            Icon(AppIcons.ContentCopy, null); Spacer(Modifier.width(8.dp))
                            Text(if (info.transcriptUrl.isNullOrBlank()) "Public transcript unavailable" else "Load public transcript")
                        }
                    }
                    if (transcript.isNotBlank()) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Text("TRANSCRIPT", style = MaterialTheme.typography.labelMedium, color = RexAccent, fontWeight = FontWeight.Bold)
                                Text(transcript, style = MaterialTheme.typography.bodySmall, maxLines = 12, overflow = TextOverflow.Ellipsis)
                                OutlinedButton(onClick = { onCopy("YouTube transcript", transcript) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                                    Icon(AppIcons.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(7.dp)); Text("Copy transcript")
                                }
                                OutlinedButton(onClick = onUseTranscriptInCaption, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                                    Text("Edit transcript in Caption Studio")
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) { Text("Save to Library") }
                    }
                    Text("Public metadata and captions may be incomplete or unavailable when YouTube changes its response or a video restricts access.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RexRemoteThumbnail(url: String, description: String, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = runCatching {
            withContext(Dispatchers.IO) {
                val client = okhttp3.OkHttpClient.Builder().callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
                val req = okhttp3.Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) error("Thumbnail unavailable")
                    val bytes = response.body?.bytes() ?: error("Empty thumbnail")
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }
        }.getOrNull()
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(AppIcons.PlayArrow, description, tint = RexAccent, modifier = Modifier.size(42.dp))
    }
}

@Composable
private fun RexQuickStart(source: MediaSource) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (source == MediaSource.INSTAGRAM) "Simple by design" else "YouTube, neatly organized", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        RexTip("01", if (source == MediaSource.INSTAGRAM) "Paste a public post" else "Paste a video URL", if (source == MediaSource.INSTAGRAM) "Reels, photos, carousels and public profile pictures." else "Watch links, Shorts and youtu.be links are supported.")
        RexTip("02", "Preview before acting", if (source == MediaSource.INSTAGRAM) "Select just the carousel items you need." else "See the public title, channel name and thumbnail before opening the video.")
        RexTip("03", "Keep your text", "Copy captions or hashtags and find saved links in your local Library.")
    }
}

@Composable
private fun RexTip(number: String, title: String, body: String) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .42f))) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
            Text(number, style = MaterialTheme.typography.labelLarge, color = RexAccent, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RexLibraryScreen(refreshKey: Int, onCopy: (String) -> Unit, onOpen: (String) -> Unit, onClear: () -> Unit) {
    val context = LocalContext.current
    val entries = remember(refreshKey) { loadLibrary(context) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text("Library", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Your recent links, saved on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (entries.isEmpty()) {
            RexEmptyState("No saved links yet", "Save an Instagram download or add a YouTube video link to start your Library.", AppIcons.Image)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${entries.size} recent items", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onClear) { Text("Clear history", color = RexAccent) }
            }
            entries.forEach { item ->
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))) {
                    Row(Modifier.fillMaxWidth().clickable { onOpen(item.url) }.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(45.dp).clip(RoundedCornerShape(13.dp)).background(Brush.linearGradient(listOf(RexAccent, RexViolet))), contentAlignment = Alignment.Center) {
                            Icon(if (item.source == "YouTube") AppIcons.PlayArrow else AppIcons.Download, null, tint = Color.White)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.title.ifBlank { "Media link" }, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text("${item.source} · ${item.count} ${if (item.count == 1) "item" else "items"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(SimpleDateFormat("dd MMM · hh:mm a", Locale.getDefault()).format(Date(item.savedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onCopy(item.url) }) { Icon(AppIcons.ContentCopy, contentDescription = "Copy source link", tint = RexAccent) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RexCaptionLabScreen(text: String, onTextChange: (String) -> Unit, onCopy: (String, String) -> Unit) {
    // This destination is intentionally self-contained: opening it never triggers a fetch or download.
    val tags = remember(text) { hashtagsOrSuggestions(text) }
    val literalHashtags = remember(text) { extractLiteralHashtags(text) }
    val captionIdeas = remember(text) { buildCaptionIdeas(text) }
    var showCaptionIdeas by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Caption Studio", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Edit your text. Copy exactly what you need.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CAPTION TEXT", style = MaterialTheme.typography.labelMedium, color = RexAccent, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${text.length} characters", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Paste or fetch a caption to edit it here") },
                    minLines = 6,
                    maxLines = 12,
                    shape = RoundedCornerShape(15.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RexAccent,
                        cursorColor = RexAccent
                    )
                )
                Button(onClick = { onCopy("Caption", text.trim()) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = RexAccent, contentColor = Color.White)) {
                    Icon(AppIcons.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Copy caption")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onCopy("Hashtags", tags) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) { Text("Copy hashtags") }
                    OutlinedButton(onClick = { onCopy("Full caption", text) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) { Text("Copy full text") }
                }
                OutlinedButton(onClick = { showCaptionIdeas = !showCaptionIdeas }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
                    Text(if (showCaptionIdeas) "Hide 5 caption ideas" else "Generate 5 quick caption ideas")
                }
                if (showCaptionIdeas) {
                    Text("Template-based variations — review them before posting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    captionIdeas.forEachIndexed { index, idea ->
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), shape = RoundedCornerShape(13.dp)) {
                            Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("0${index + 1}", color = RexAccent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
                                Text(idea, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                IconButton(onClick = { onCopy("Caption idea ${index + 1}", idea) }) { Icon(AppIcons.ContentCopy, contentDescription = "Copy caption idea", tint = RexAccent) }
                            }
                        }
                    }
                    OutlinedButton(onClick = { onCopy("Five caption ideas", captionIdeas.mapIndexed { index, idea -> "${index + 1}. $idea" }.joinToString("\n\n")) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
                        Text("Copy all 5 ideas")
                    }
                }
                Divider()
                Text(if (literalHashtags.isNotBlank()) "HASHTAGS FOUND" else "HASHTAG SUGGESTIONS", style = MaterialTheme.typography.labelMedium, color = RexAccent, fontWeight = FontWeight.Bold)
                if (tags.isBlank()) {
                    Text("No #hashtags or trailing {comma-separated keywords} were found.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(tags, style = MaterialTheme.typography.bodyMedium)
                    if (literalHashtags.isBlank()) Text("Suggestions are derived from the trailing keyword block; review them before posting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun buildCaptionIdeas(input: String): List<String> {
    val base = input.lineSequence().firstOrNull()?.trim().orEmpty()
        .replace(Regex("#[\\p{L}\\p{N}_]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim().trimEnd('.', '!', '?', '…')
        .take(72)
        .ifBlank { "A moment worth sharing" }
    return listOf(
        "POV: $base ✨",
        "$base — save this for later 💫",
        "Small details. Big mood. $base 🎬",
        "Which part is your favourite? 👇 $base",
        "More moments like this, coming soon. 🚀"
    )
}

@Composable
private fun RexSettingsScreen(
    themeChoice: AppTheme,
    quality: DownloadQuality,
    haptics: Boolean,
    customWidth: Int,
    folderName: String,
    onThemeChoice: (AppTheme) -> Unit,
    onQuality: (DownloadQuality) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onCustomWidth: (Int) -> Unit,
    onChooseFolder: () -> Unit,
    onOpen: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Personalize InstaTube for your workflow.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        RexSettingsGroup(title = "DOWNLOAD QUALITY") {
            DownloadQuality.values().forEach { option ->
                Row(Modifier.fillMaxWidth().clickable { onQuality(option) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = quality == option, onClick = { onQuality(option) }, colors = RadioButtonDefaults.colors(selectedColor = RexAccent))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(androidx.compose.ui.res.stringResource(option.labelRes), fontWeight = FontWeight.SemiBold)
                        Text(androidx.compose.ui.res.stringResource(option.descriptionRes), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (quality == DownloadQuality.CUSTOM) {
                Text("Target width · ${customWidth}px", style = MaterialTheme.typography.labelLarge)
                Slider(value = customWidth.toFloat(), onValueChange = { onCustomWidth(it.toInt()) }, valueRange = 320f..1080f, steps = 7, colors = SliderDefaults.colors(thumbColor = RexAccent, activeTrackColor = RexAccent))
            }
        }
        RexSettingsGroup(title = "APPEARANCE") {
            AppTheme.values().forEach { option ->
                Row(Modifier.fillMaxWidth().clickable { onThemeChoice(option) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = themeChoice == option, onClick = { onThemeChoice(option) }, colors = RadioButtonDefaults.colors(selectedColor = RexAccent))
                    Spacer(Modifier.width(8.dp)); Text(androidx.compose.ui.res.stringResource(option.labelRes))
                }
            }
            Divider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Haptic feedback", fontWeight = FontWeight.SemiBold)
                    Text("Vibrate on download events", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = haptics, onCheckedChange = onHaptics, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = RexAccent))
            }
        }
        RexSettingsGroup(title = "DOWNLOAD LOCATION") {
            Text(folderName, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onChooseFolder, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) { Text("Choose folder", color = RexAccent) }
            Text("Default: Downloads/InstaDownload", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RexSettingsGroup(title = "ABOUT & LINKS") {
            RexLinkRow("GitHub profile", "github.com/xboyrex", onClick = { onOpen("https://github.com/xboyrex") })
            Divider()
            RexLinkRow("Source repository", "InstaTube Toolkit source code", onClick = { onOpen("https://github.com/xboyrex/instatube-toolkit-by-xboidx") })
            Divider()
            RexLinkRow("YouTube", "Search for Xboy Rex", onClick = { onOpen("https://www.youtube.com/results?search_query=Xboy+Rex") })
            Divider()
            RexLinkRow("Privacy policy", "What stays on-device and what uses the network", onClick = { onOpen("https://github.com/xboyrex/instatube-toolkit-by-xboidx/blob/main/PRIVACY.md") })
            Divider()
            RexLinkRow("Open-source license", "GPL-3.0 · upstream attribution retained", onClick = { onOpen("https://github.com/xboyrex/instatube-toolkit-by-xboidx/blob/main/LICENSE") })
            Divider()
            RexLinkRow("Upstream project", "InstaDownload by Orang-Studio", onClick = { onOpen("https://github.com/Orang-Studio/InstaDownload") })
        }
        Text("Built by Xboy Rex. Not affiliated with Instagram, Meta or YouTube. Only access and save content you own or have permission to use.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RexSettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = RexAccent, modifier = Modifier.padding(start = 3.dp))
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
        }
    }
}

@Composable
private fun RexLinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("↗", color = RexAccent, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun RexEmptyState(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))) {
        Column(Modifier.fillMaxWidth().padding(25.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(55.dp).clip(RoundedCornerShape(17.dp)).background(Brush.linearGradient(listOf(RexAccent, RexViolet))), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun isInstagramLink(value: String): Boolean {
    val uri = runCatching { Uri.parse(value.trim()) }.getOrNull() ?: return false
    if (uri.scheme !in setOf("http", "https")) return false
    if (uri.host?.lowercase(Locale.ROOT) !in setOf("instagram.com", "www.instagram.com", "instagr.am", "www.instagr.am")) return false
    val parts = uri.pathSegments
    if (parts.isEmpty()) return false
    if (parts[0].lowercase(Locale.ROOT) in setOf("p", "reel", "reels", "tv")) return parts.size >= 2
    if (parts[0].lowercase(Locale.ROOT) in setOf("stories", "explore", "accounts", "direct")) return false
    return parts.size == 1 && parts[0].isNotBlank()
}

private fun friendlyInstagramError(message: String?): String {
    val value = message.orEmpty()
    return when {
        value.contains("HTTP 429", ignoreCase = true) -> "Instagram is rate-limiting requests. Wait a little and try again."
        value.contains("UnknownHost", ignoreCase = true) -> "No internet connection. Check your network and try again."
        value.isNotBlank() -> value
        else -> "Could not load public media. The post may be private, removed or temporarily unavailable."
    }
}

private fun extractLiteralHashtags(text: String): String = runCatching {
    Regex("(?<![\\w#])#[\\p{L}\\p{N}_]+")
        .findAll(text).map { it.value }.distinct().joinToString(" ")
}.getOrDefault("")

private fun hashtagsOrSuggestions(text: String): String {
    val literal = extractLiteralHashtags(text)
    if (literal.isNotBlank()) return literal
    val block = runCatching { Regex("\\{([^{}]+)}\\s*$").find(text)?.groupValues?.getOrNull(1) }.getOrNull().orEmpty()
    if (block.isBlank()) return ""
    return block.split(',').mapNotNull { phrase ->
        val cleaned = phrase.trim().trim('#').replace(Regex("[^\\p{L}\\p{N}_\\s-]"), "").trim()
        val tag = cleaned.replace(Regex("[\\s-]+"), "")
        tag.takeIf { it.isNotBlank() }?.let { "#$it" }
    }.distinct().joinToString(" ")
}

private fun loadLibrary(context: Context): List<RexSavedItem> = runCatching {
    val json = context.getSharedPreferences("instatube_rex_library", Context.MODE_PRIVATE).getString("items", "[]")
    val array = JSONArray(json)
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        RexSavedItem(
            title = item.optString("title", "Media link"),
            url = item.optString("url"),
            count = item.optInt("count", 1),
            savedAt = item.optLong("savedAt", 0L),
            source = item.optString("source", "Instagram")
        )
    }
}.getOrDefault(emptyList())

private fun addLibraryEntry(context: Context, item: RexSavedItem) {
    val recent = (listOf(item) + loadLibrary(context)).distinctBy { it.url }.take(30)
    val array = JSONArray()
    recent.forEach { value ->
        array.put(JSONObject().put("title", value.title).put("url", value.url).put("count", value.count).put("savedAt", value.savedAt).put("source", value.source))
    }
    context.getSharedPreferences("instatube_rex_library", Context.MODE_PRIVATE).edit().putString("items", array.toString()).apply()
}

private fun clearLibrary(context: Context) {
    context.getSharedPreferences("instatube_rex_library", Context.MODE_PRIVATE).edit().remove("items").apply()
}
