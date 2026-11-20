package com.nuvio.app.features.player

import java.io.File

/**
 * Copies of subtitle files picked from the device, in `cache/device-subtitles`.
 *
 * A copy is referenced only by the player of the process that made it: it is never saved as a
 * per-title preference (see [selectDeviceSubtitle]). So every import first removes the copies
 * left by earlier processes, and keeps every copy made by this one, which a player may still be
 * showing. Names are unique, so a new import never overwrites a file in use.
 */
internal class DeviceSubtitleCache(private val dir: File) {
    private val madeHere = mutableSetOf<String>()

    /** A new, unused file for an import, after removing the copies of earlier processes. */
    @Synchronized
    fun newFile(displayName: String, extension: String): File {
        removeOldCopies()
        dir.mkdirs()
        val stem = displayName.substringBeforeLast('.', missingDelimiterValue = displayName)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(60)
            .ifBlank { "subtitle" }
        val unique = System.currentTimeMillis().toString(36)
        var attempt = 0
        var file: File
        do {
            val suffix = if (attempt == 0) "" else "-$attempt"
            file = File(dir, "$stem-$unique$suffix.$extension")
            attempt++
        } while (file.exists() || file.name in madeHere)
        madeHere += file.name
        return file
    }

    /** Removes the files this process did not make. Returns how many were removed. */
    @Synchronized
    fun removeOldCopies(): Int =
        dir.listFiles().orEmpty().count { it.isFile && it.name !in madeHere && it.delete() }

    companion object {
        private var instance: DeviceSubtitleCache? = null

        /** The cache of this process (one per process: its memory of files is what keeps them). */
        @Synchronized
        fun forProcess(cacheDir: File): DeviceSubtitleCache =
            instance ?: DeviceSubtitleCache(File(cacheDir, "device-subtitles")).also { instance = it }
    }
}
