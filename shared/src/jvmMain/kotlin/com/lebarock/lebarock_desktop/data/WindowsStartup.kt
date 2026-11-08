package com.lebarock.lebarock_desktop.data

import com.lebarock.lebarock_desktop.AppInfo
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/**
 * "Démarrer avec Windows" (client, 2026-11-08): the installed app is started at Windows sign-in,
 * in the tray (`--tray`), so the "Directe" printing works before anyone opens it. A value under
 * HKCU\…\Run, for this Windows account only (no administrator rights needed).
 */
object WindowsStartup {
    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE = "AltosPOS"
    const val TRAY_ARG = "--tray"

    /** The installed launcher (AltosPOS.exe); null in development (gradle run). */
    val launcher: String? get() = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }

    /** Can be switched on here: Windows, and an installed app to start. */
    val available: Boolean get() = AppInfo.isWindows && launcher != null

    fun isOn(): Boolean = available && runCatching {
        Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE)
    }.getOrDefault(false)

    fun set(on: Boolean): Result<Unit> = runCatching {
        check(available) { "Seulement pour l'application installée sous Windows." }
        if (on) {
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE, "\"$launcher\" $TRAY_ARG")
        } else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE)) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE)
        }
    }.onSuccess { AppLog.info("Démarrer avec Windows : ${if (on) "oui" else "non"}") }
        .onFailure { AppLog.warn("Démarrer avec Windows : impossible", it) }
}
