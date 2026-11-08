package com.lebarock.lebarock_desktop.data

import com.lebarock.lebarock_desktop.AppInfo
import java.io.File
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The app's journal: `logs\altospos-N.log` in the data folder (5 files of 1 MB, oldest dropped),
 * opened from Réglages → Application. What support reads when something went wrong at the counter.
 * Never logs a password, a token or a key.
 */
object AppLog {
    private val logger: Logger = Logger.getLogger("altospos").apply { useParentHandlers = false }
    @Volatile private var started = false

    @Synchronized
    fun start(dir: File = AppInfo.logsDir) {
        if (started) return
        runCatching {
            val handler = FileHandler(File(dir, "altospos-%g.log").path, 1_000_000, 5, true)
            handler.encoding = "UTF-8"
            handler.formatter = object : SimpleFormatter() {
                private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                override fun format(record: LogRecord): String {
                    val error = record.thrown?.let { " | ${it::class.java.simpleName}: ${it.message}" } ?: ""
                    return "${LocalDateTime.now().format(time)} ${record.level.name.padEnd(7)} ${record.message}$error\n"
                }
            }
            logger.addHandler(handler)
        }
        started = true
    }

    fun info(message: String) = logger.log(Level.INFO, message)

    fun warn(message: String, error: Throwable? = null) = logger.log(Level.WARNING, message, error)

    fun error(message: String, error: Throwable? = null) = logger.log(Level.SEVERE, message, error)
}
