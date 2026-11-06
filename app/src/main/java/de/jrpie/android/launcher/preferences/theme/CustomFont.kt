package de.jrpie.android.launcher.preferences.theme

import android.content.ContentResolver
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import de.jrpie.android.launcher.R
import de.jrpie.android.launcher.preferences.LauncherPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Handles user supplied fonts. The font file is copied into app private storage once,
 * so reading it later does not depend on the document provider or URI permissions.
 */
object CustomFont {
    private const val FONT_DIRECTORY = "fonts"
    private const val MAX_FONT_SIZE = 32L * 1024 * 1024
    private const val BUFFER_SIZE = 8 * 1024

    private val cache = arrayOfNulls<Typeface>(FontSlot.entries.size)
    private val cacheVersions = LongArray(FontSlot.entries.size) { Long.MIN_VALUE }

    sealed class ImportResult {
        data class Success(val displayName: String) : ImportResult()
        data class Failure(val reason: Reason) : ImportResult()
    }

    enum class Reason(val messageRes: Int) {
        UNSUPPORTED(R.string.settings_custom_font_unsupported),
        TOO_LARGE(R.string.settings_custom_font_too_large),
        OPEN_FAILED(R.string.settings_custom_font_open_failed),
        INVALID(R.string.settings_custom_font_invalid)
    }

    fun getTypeface(context: Context, slot: FontSlot): Typeface? {
        val preferences = LauncherPreferences.getSharedPreferences()
        val version = preferences.getLong(context.getString(slot.versionKeyRes), 0L)

        synchronized(cache) {
            if (cacheVersions[slot.ordinal] == version) {
                return cache[slot.ordinal]
            }

            val file = file(context, slot)
            val typeface = if (file.isFile) {
                try {
                    Typeface.createFromFile(file)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

            cache[slot.ordinal] = typeface
            cacheVersions[slot.ordinal] = version
            return typeface
        }
    }

    fun isAvailable(context: Context, slot: FontSlot): Boolean {
        val file = file(context, slot)
        return file.isFile && file.length() > 0
    }

    fun displayName(context: Context, slot: FontSlot): String? {
        return LauncherPreferences.getSharedPreferences()
            .getString(context.getString(slot.nameKeyRes), null)
            ?.takeIf { it.isNotBlank() }
    }

    suspend fun import(context: Context, slot: FontSlot, uri: Uri): ImportResult {
        val copied = withContext(Dispatchers.IO) {
            copyToPrivateStorage(context, slot, uri)
        }
        if (copied is ImportResult.Failure) {
            return copied
        }

        val displayName = (copied as ImportResult.Success).displayName

        // Preference listeners (activities, clock widget) run on the calling thread, so
        // the write has to happen on the main thread. This also keeps the activation
        // atomic from their point of view.
        withContext(Dispatchers.Main.immediate) {
            val preferences = LauncherPreferences.getSharedPreferences()
            val versionKey = context.getString(slot.versionKeyRes)
            val newVersion = maxOf(
                System.currentTimeMillis(),
                preferences.getLong(versionKey, 0L) + 1L
            )

            invalidate(slot)

            // The name/version are written first. If the custom font is already active,
            // this notifies the views; otherwise activating the font below does it.
            preferences.edit()
                .putString(context.getString(slot.nameKeyRes), displayName)
                .putLong(versionKey, newVersion)
                .apply()

            preferences.edit()
                .putString(context.getString(slot.fontKeyRes), Font.CUSTOM.name)
                .apply()
        }

        return ImportResult.Success(displayName)
    }

    private fun copyToPrivateStorage(
        context: Context,
        slot: FontSlot,
        uri: Uri
    ): ImportResult {
        val (displayName, size) = queryMetadata(context.contentResolver, uri)

        if (size != null && size > MAX_FONT_SIZE) {
            return ImportResult.Failure(Reason.TOO_LARGE)
        }

        val extension = displayName
            ?.trim()
            ?.substringAfterLast('.', "")
            ?.lowercase()
            .orEmpty()
        if (extension.isNotEmpty() && extension != "ttf" && extension != "otf") {
            return ImportResult.Failure(Reason.UNSUPPORTED)
        }

        val fontDirectory = File(context.filesDir, FONT_DIRECTORY)
        if (!fontDirectory.exists() && !fontDirectory.mkdirs()) {
            return ImportResult.Failure(Reason.OPEN_FAILED)
        }

        val target = File(fontDirectory, slot.fileName)
        val temporary = File(fontDirectory, "${slot.fileName}.tmp")

        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return ImportResult.Failure(Reason.OPEN_FAILED)

            input.use { source ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break

                        total += read
                        if (total > MAX_FONT_SIZE) {
                            temporary.delete()
                            return ImportResult.Failure(Reason.TOO_LARGE)
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }

            if (temporary.length() <= 0L) {
                temporary.delete()
                return ImportResult.Failure(Reason.INVALID)
            }

            // Typeface.createFromFile throws or returns null if the file is not a
            // supported font.
            val validFont = try {
                Typeface.createFromFile(temporary) != null
            } catch (_: Exception) {
                false
            }
            if (!validFont) {
                temporary.delete()
                return ImportResult.Failure(Reason.INVALID)
            }

            if (!temporary.renameTo(target)) {
                target.delete()
                if (!temporary.renameTo(target)) {
                    temporary.delete()
                    return ImportResult.Failure(Reason.INVALID)
                }
            }

            val name = displayName?.trim()?.takeIf { it.isNotEmpty() }
                ?: context.getString(R.string.settings_custom_font_fallback_name)
            return ImportResult.Success(name)
        } catch (_: Exception) {
            temporary.delete()
            return ImportResult.Failure(Reason.OPEN_FAILED)
        }
    }

    private fun file(context: Context, slot: FontSlot): File {
        return File(File(context.filesDir, FONT_DIRECTORY), slot.fileName)
    }

    private fun invalidate(slot: FontSlot) {
        synchronized(cache) {
            cache[slot.ordinal] = null
            cacheVersions[slot.ordinal] = Long.MIN_VALUE
        }
    }

    private fun queryMetadata(
        resolver: ContentResolver,
        uri: Uri
    ): Pair<String?, Long?> {
        var name: String? = null
        var size: Long? = null

        try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameColumn >= 0) {
                        name = cursor.getString(nameColumn)
                    }

                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                        size = cursor.getLong(sizeColumn)
                    }
                }
            }
        } catch (_: Exception) {
            // Not all document providers return metadata. The stream is validated below.
        }

        return name to size
    }
}
