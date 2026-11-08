package com.lebarock.lebarock_desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "LeBarock-Desktop",
    ) {
        App()
    }
}