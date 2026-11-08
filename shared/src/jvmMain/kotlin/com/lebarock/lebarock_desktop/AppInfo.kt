package com.lebarock.lebarock_desktop

import java.io.File

/** What the app is and where it keeps its files on this PC. */
object AppInfo {
    const val NAME = "AltosPOS"

    /** Set by the launcher (`-Daltospos.version`, desktopApp/build.gradle.kts); "dev" from an IDE run. */
    val version: String get() = System.getProperty("altospos.version") ?: "dev"

    /** Sent to the caisse as the Sanctum token name, so its staff can tell the counter's sessions apart. */
    const val DEVICE_NAME = "altospos-desktop"

    val isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    /**
     * The data folder: `%APPDATA%\AltosPOS` on Windows (per Windows account, kept across updates and
     * uninstalls), `~/.config/AltosPOS` elsewhere (development). `-Daltospos.home` overrides it (tests).
     */
    val dataDir: File by lazy {
        val override = System.getProperty("altospos.home")
        val dir = when {
            !override.isNullOrBlank() -> File(override)
            isWindows && !System.getenv("APPDATA").isNullOrBlank() -> File(System.getenv("APPDATA"), NAME)
            else -> File(System.getProperty("user.home"), ".config/$NAME")
        }
        dir.apply { mkdirs() }
    }

    val logsDir: File get() = File(dataDir, "logs").apply { mkdirs() }

    /** Root certificates imported in Réglages (e.g. mkcert's rootCA.pem), trusted on top of Windows'. */
    val certsDir: File get() = File(dataDir, "certs").apply { mkdirs() }
}
