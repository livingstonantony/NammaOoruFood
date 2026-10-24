package dev.livin.nammaoorufood

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform