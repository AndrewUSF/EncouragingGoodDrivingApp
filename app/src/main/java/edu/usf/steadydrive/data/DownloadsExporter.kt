package edu.usf.steadydrive.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File

class DownloadsExporter(
    private val context: Context,
) {
    fun exportCsv(sourceFile: File, desiredFileName: String): String? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportScoped(sourceFile, desiredFileName)
            } else {
                exportLegacy(sourceFile, desiredFileName)
            }
        }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportScoped(sourceFile: File, desiredFileName: String): String {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, desiredFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/SteadyDrive",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

        val uri =
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create the Downloads entry for $desiredFileName.")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: error("Unable to open the Downloads output stream for $desiredFileName.")

            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null,
            )
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }

        return desiredFileName
    }

    private fun exportLegacy(sourceFile: File, desiredFileName: String): String {
        val downloadsDirectory =
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "SteadyDrive",
            ).apply { mkdirs() }
        val destinationFile = File(downloadsDirectory, desiredFileName)
        sourceFile.copyTo(destinationFile, overwrite = true)
        return desiredFileName
    }
}
