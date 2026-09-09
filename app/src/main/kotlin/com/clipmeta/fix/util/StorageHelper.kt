package com.clipmeta.fix.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object StorageHelper {

    /** Copy content Uri to a temp file (streaming, no size limit) */
    fun copyUriToTempFile(context: Context, uri: Uri, prefix: String): File {
        val tmp = File.createTempFile(prefix, ".mp4", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                }
            }
        } ?: error("Cannot open input stream for $uri")
        return tmp
    }

    /**
     * Try to overwrite the original MediaStore entry in-place.
     * Returns true if succeeds.
     * On Android 10+ this may require user consent / throw SecurityException.
     */
    fun tryOverwriteOriginal(context: Context, targetUri: Uri, patchedFile: File): Boolean {
        return try {
            context.contentResolver.openFileDescriptor(targetUri, "w")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    FileInputStream(patchedFile).use { input ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                        out.fd.sync()
                    }
                }
            } ?: return false
            // Trigger media scan: update date_modified
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                }
                context.contentResolver.update(targetUri, values, null, null)
            } catch (_: Exception) {}
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Fallback: insert as new MediaStore entry. Returns new Uri or null.
     */
    fun insertAsNewEntry(context: Context, patchedFile: File, displayName: String): Uri? {
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val newUri = resolver.insert(collection, values) ?: return null
            resolver.openFileDescriptor(newUri, "w")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    FileInputStream(patchedFile).use { input ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                        out.fd.sync()
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(newUri, values, null, null)
            }
            newUri
        } catch (e: Exception) {
            null
        }
    }

    fun validateWithRetriever(context: Context, file: File): Boolean {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val dur = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            dur != null
        } catch (e: Exception) {
            try { retriever.release() } catch (_: Exception) {}
            false
        }
    }
}
