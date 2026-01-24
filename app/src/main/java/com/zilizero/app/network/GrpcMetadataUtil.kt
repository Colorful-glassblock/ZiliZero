package com.zilizero.app.network

import android.util.Base64
import com.bapis.bilibili.metadata.Metadata

/**
 * Utility to generate gRPC headers like x-bili-metadata-bin.
 */
object GrpcMetadataUtil {

    /**
     * Generates the x-bili-metadata-bin header value.
     * This is a Base64 encoded Protobuf message of bilibili.metadata.Metadata.
     */
    fun generateMetadataBin(accessKey: String?, buvid: String): String {
        val metadata = Metadata.newBuilder().apply {
            accessKey?.let { this.accessKey = it }
            this.mobiApp = BiliConfig.MOBI_APP
            this.device = BiliConfig.DEVICE
            this.build = BiliConfig.BUILD_VERSION
            this.channel = BiliConfig.CHANNEL
            this.buvid = buvid
            this.platform = BiliConfig.PLATFORM
        }.build()

        val bytes = metadata.toByteArray()
        // Use NO_WRAP to avoid newline characters in header
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Generates a standard BiliDroid User-Agent.
     * Required for gRPC consistency.
     */
    fun generateUserAgent(buvid: String): String {
        return "Mozilla/5.0 BiliDroid/${BiliConfig.VERSION_NAME} (bbcallen@gmail.com) " +
                "os/android model/ZiliZero mobi_app/${BiliConfig.MOBI_APP} " +
                "build/${BiliConfig.BUILD_VERSION} channel/${BiliConfig.CHANNEL} " +
                "innerVer/${BiliConfig.BUILD_VERSION} osVer/13 network/2"
    }
}
