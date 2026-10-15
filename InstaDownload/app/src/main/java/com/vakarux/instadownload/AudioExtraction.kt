package com.vakarux.instadownload

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer

/** Extracts and remuxes an existing audio track; no audio re-encoding occurs. */
internal fun saveInstagramAudioToDownloads(item: MediaResult, context: Context, settings: AppSettings): Boolean {
    require(item.isVideo) { "This media item is a photo; there is no audio track to extract." }
    val base = item.baseName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifBlank { "instagram" }
    val stamp = System.currentTimeMillis()
    val input = File(context.cacheDir, "${base}-${stamp}-source.mp4")
    val output = File(context.cacheDir, "${base}-${stamp}-audio.m4a")
    try {
        input.outputStream().buffered().use { InstagramDownloader.downloadToStream(item.url, it) }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var started = false
            try {
                val tracks = linkedMapOf<Int, Int>()
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    if (format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                        tracks[index] = muxer.addTrack(format)
                        extractor.selectTrack(index)
                    }
                }
                if (tracks.isEmpty()) throw IllegalStateException("No audio track was found in this Reel/video.")
                muxer.start()
                started = true
                val info = MediaCodec.BufferInfo()
                val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
                var samples = 0
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val targetTrack = tracks[extractor.sampleTrackIndex]
                    if (targetTrack != null) {
                        info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                        muxer.writeSampleData(targetTrack, buffer, info)
                        samples++
                    }
                    extractor.advance()
                }
                if (samples == 0) throw IllegalStateException("The video did not contain readable audio samples.")
                muxer.stop()
                started = false
            } finally {
                if (started) runCatching { muxer.stop() }
                muxer.release()
            }
        } finally {
            extractor.release()
        }
        return copyAudioToDownloads(output, context, settings, base)
    } finally {
        input.delete()
        output.delete()
    }
}

private fun copyAudioToDownloads(file: File, context: Context, settings: AppSettings, base: String): Boolean {
    val name = "${base}-audio.m4a"
    val treeValue = settings.downloadTreeUri
    if (treeValue != null) {
        try {
            val treeUri = Uri.parse(treeValue)
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            val target = DocumentsContract.createDocument(context.contentResolver, parent, "audio/mp4", name)
                ?: throw IllegalStateException("Could not create audio in the selected folder.")
            context.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Could not write audio to the selected folder.")
            return false
        } catch (_: Exception) {
            settings.downloadTreeUri = null
            settings.downloadFolderName = AppSettings.DEFAULT_FOLDER_NAME
            writeToDefaultDownloads(file, context, name)
            return true
        }
    }
    writeToDefaultDownloads(file, context, name)
    return false
}

private fun writeToDefaultDownloads(file: File, context: Context, name: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/InstaDownload")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Could not create the audio file in Downloads/InstaDownload.")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Could not write audio to Downloads/InstaDownload.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    } else {
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "InstaDownload")
        if (!folder.exists() && !folder.mkdirs()) throw IllegalStateException("Could not create Downloads/InstaDownload.")
        File(folder, name).outputStream().buffered().use { out -> file.inputStream().use { it.copyTo(out) } }
    }
}
