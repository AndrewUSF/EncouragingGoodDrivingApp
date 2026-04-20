package edu.usf.steadydrive.data

import android.content.Context
import edu.usf.steadydrive.model.DriveSample
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class CsvSessionWriter(
    context: Context,
    participantId: String,
    sessionId: String,
) {
    private val directory = File(context.filesDir, "steady-drive-sessions").apply { mkdirs() }

    val fileName: String =
        "${participantId}_${TIMESTAMP_FORMATTER.format(Instant.now())}_${sessionId.take(8)}.csv"

    val file = File(directory, fileName)
    private val writer = FileWriter(file, false)

    init {
        writer.appendLine(DriveSample.CSV_HEADER)
        writer.flush()
    }

    fun append(sample: DriveSample) {
        writer.appendLine(sample.toCsvRow())
        writer.flush()
    }

    fun close() {
        writer.flush()
        writer.close()
    }

    companion object {
        private val TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC)
    }
}
