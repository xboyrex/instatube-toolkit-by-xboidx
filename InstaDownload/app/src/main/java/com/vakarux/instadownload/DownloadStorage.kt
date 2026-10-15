package com.vakarux.instadownload

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore

internal fun saveToDownloads(item: MediaResult, context: Context, settings: AppSettings): Boolean {
    try {
        saveTo(item, context, settings.downloadTreeUri)
        return false
    } catch (e: Exception) {
        if (settings.downloadTreeUri == null) throw e
        settings.downloadTreeUri = null
        settings.downloadFolderName = AppSettings.DEFAULT_FOLDER_NAME
        saveTo(item, context, null)
        return true
    }
}

private fun saveTo(
    item: MediaResult,
    context: Context,
    downloadTreeUri: String?
) {
    val mediaUrl = item.url
    val isVideo = item.isVideo
    val base = item.baseName.ifBlank { "instagram_${System.currentTimeMillis()}" }
    val fileName = if (isVideo) "$base.mp4" else "$base.jpg"
    val mimeType = if (isVideo) "video/mp4" else "image/jpeg"

    if (downloadTreeUri != null) {
        val treeUri = Uri.parse(downloadTreeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val fileUri = DocumentsContract.createDocument(
            context.contentResolver, parent, mimeType, fileName
        ) ?: throw Exception(context.getString(R.string.error_create_file_in_folder))
        context.contentResolver.openOutputStream(fileUri)?.use { out ->
            InstagramDownloader.downloadToStream(mediaUrl, out)
        } ?: throw Exception(context.getString(R.string.error_write_to_folder))
        if (!isVideo) tagJpeg(item.meta) {
            context.contentResolver.openFileDescriptor(fileUri, "rw")?.use(it)
        }
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/InstaDownload")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            item.meta?.let {
                put(MediaStore.MediaColumns.TITLE, it.caption?.take(100) ?: base)
                put(MediaStore.MediaColumns.ARTIST, it.username)
            }
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw Exception(context.getString(R.string.error_create_file_in_downloads))
        resolver.openOutputStream(uri)?.use { out ->
            InstagramDownloader.downloadToStream(mediaUrl, out)
        }
        if (!isVideo) tagJpeg(item.meta) { resolver.openFileDescriptor(uri, "rw")?.use(it) }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    } else {
        try {
            val dir = java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "InstaDownload"
            ).apply { mkdirs() }
            val file = java.io.File(dir, fileName)
            file.outputStream().use { InstagramDownloader.downloadToStream(mediaUrl, it) }
            if (!isVideo) tagJpeg(item.meta) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use(it)
            }
        } catch (e: SecurityException) {
            throw Exception(context.getString(R.string.error_storage_permission_lost), e)
        }
    }
}

private fun tagJpeg(meta: PostMeta?, withFd: ((ParcelFileDescriptor) -> Unit) -> Unit) {
    if (meta == null) return
    runCatching {
        withFd { pfd ->
            val exif = android.media.ExifInterface(pfd.fileDescriptor)
            meta.username?.let { exif.setAttribute(android.media.ExifInterface.TAG_ARTIST, it) }
            meta.caption?.let { exif.setAttribute(android.media.ExifInterface.TAG_IMAGE_DESCRIPTION, it) }
            exif.setAttribute(
                android.media.ExifInterface.TAG_USER_COMMENT,
                listOfNotNull(meta.url, meta.song?.let { "Song: $it" }).joinToString("\n")
            )
            if (meta.takenAtSec > 0) exif.setAttribute(
                android.media.ExifInterface.TAG_DATETIME_ORIGINAL,
                java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(meta.takenAtSec * 1000))
            )
            exif.saveAttributes()
        }
    }
}


/** Extracts the existing audio track from a publicly accessible Instagram video and saves it under Music/InstaTube. */
internal fun saveAudioToMusic(item: MediaResult, context: Context): String {
    if (!item.isVideo) throw IllegalArgumentException("Select a video or Reel to extract audio.")
    val sourceFile = java.io.File.createTempFile("instatube-source-", ".mp4", context.cacheDir)
    val audioFile = java.io.File.createTempFile("instatube-audio-", ".m4a", context.cacheDir)
    try {
        sourceFile.outputStream().use { InstagramDownloader.downloadToStream(item.url, it) }
        extractAudioTrack(sourceFile, audioFile)
        val safeBase = item.baseName.ifBlank { "instatube_audio_${System.currentTimeMillis()}" }
            .replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').take(70)
            .ifBlank { "instatube_audio" }
        val fileName = "$safeBase.m4a"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                // Audio rows must use an audio-approved public directory; Download/ is not allowed for MediaStore.Audio.
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/InstaTube")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.Audio.Media.TITLE, safeBase.replace('_', ' '))
                item.meta?.username?.let { put(MediaStore.Audio.Media.ARTIST, it) }
            }
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create an audio file in Music/InstaTube.")
            try {
                resolver.openOutputStream(uri, "w")?.use { out -> audioFile.inputStream().use { it.copyTo(out) } }
                    ?: throw IllegalStateException("Could not open the destination audio file.")
                val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, ready, null, null)
                return "Music/InstaTube/$fileName"
            } catch (failure: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw failure
            }
        } else {
            // Scoped app storage avoids requesting broad storage access on older Android releases.
            val folder = java.io.File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, "InstaTube")
                .apply { if (!exists() && !mkdirs()) throw IllegalStateException("Could not create the app Music folder.") }
            val output = java.io.File(folder, fileName)
            audioFile.copyTo(output, overwrite = true)
            return output.absolutePath
        }
    } finally {
        sourceFile.delete()
        audioFile.delete()
    }
}

private fun extractAudioTrack(source: java.io.File, destination: java.io.File) {
    val extractor = android.media.MediaExtractor()
    var muxer: android.media.MediaMuxer? = null
    var muxerStarted = false
    try {
        extractor.setDataSource(source.absolutePath)
        val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalStateException("This Reel has no audio track to extract.")
        val audioFormat = extractor.getTrackFormat(audioTrack)
        extractor.selectTrack(audioTrack)
        muxer = android.media.MediaMuxer(destination.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outputTrack = muxer.addTrack(audioFormat)
        muxer.start()
        muxerStarted = true
        val configuredSize = if (audioFormat.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)) {
            audioFormat.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)
        } else 256 * 1024
        val buffer = java.nio.ByteBuffer.allocateDirect(configuredSize.coerceIn(64 * 1024, 4 * 1024 * 1024))
        val info = android.media.MediaCodec.BufferInfo()
        var sampleCount = 0
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(outputTrack, buffer, info)
            sampleCount++
            if (!extractor.advance()) break
        }
        if (sampleCount == 0) throw IllegalStateException("No audio samples were found in this Reel.")
    } finally {
        if (muxerStarted) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        runCatching { extractor.release() }
        if (destination.exists() && destination.length() == 0L) destination.delete()
    }
}
