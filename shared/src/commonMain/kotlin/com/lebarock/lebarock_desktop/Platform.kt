package com.lebarock.lebarock_desktop

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform