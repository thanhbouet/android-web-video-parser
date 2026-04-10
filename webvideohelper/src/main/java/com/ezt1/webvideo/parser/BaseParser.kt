package com.ezt1.webvideo.parser

// ════════════════════════════════════════════════════════════════════════════
//  BaseParser — abstract class mà mọi site-specific parser đều extends
//  Tương đương BaseParse.java trong app gốc
// ════════════════════════════════════════════════════════════════════════════

// BaseParser — mọi parser đều phải implement parse() với withContext(Dispatchers.IO)
abstract class BaseParser(protected val core: CoreURLParser) {

    abstract fun canHandle(url: String): Boolean

    // QUAN TRỌNG: Mọi subclass phải wrap network calls trong withContext(Dispatchers.IO)
    abstract suspend fun parse(
        url: String,
        html: String,
        onLog: (String) -> Unit
    ): List<VideoInfo>

    protected fun log(onLog: (String) -> Unit, msg: String) = onLog(msg)
}
