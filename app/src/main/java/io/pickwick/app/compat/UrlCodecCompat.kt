package io.pickwick.app.compat

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset

/**
 * Stand-ins for the Charset overloads of [URLDecoder.decode] / [URLEncoder.encode],
 * which only exist from API 33. The build rewrites calls to them — including
 * those inside NewPipeExtractor — to land here; see `JavaApiBackports` in
 * buildSrc. Both overloads below use the String-named variants, which have
 * been available since API 1.
 */
object UrlCodecCompat {

    @JvmStatic
    fun decode(s: String, charset: Charset): String = URLDecoder.decode(s, charset.name())

    @JvmStatic
    fun encode(s: String, charset: Charset): String = URLEncoder.encode(s, charset.name())
}
