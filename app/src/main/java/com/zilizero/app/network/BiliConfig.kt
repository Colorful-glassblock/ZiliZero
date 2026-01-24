package com.zilizero.app.network

/**
 * Bilibili constants based on reverse engineering report.
 */
object BiliConfig {
    // Android (Pink) AppKey & Secret
    const val APP_KEY = "1d8b6e7d45233436"
    const val APP_SECRET = "560c52ccd288fed045859ed18bffd973"

    // Version info for User-Agent and Metadata
    const val BUILD_VERSION = 7650300
    const val VERSION_NAME = "7.65.0"
    
    // Common Headers
    const val MOBI_APP = "android"
    const val PLATFORM = "android"
    const val DEVICE = "phone"
    const val CHANNEL = "master"
}
