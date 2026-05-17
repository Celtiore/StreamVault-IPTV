package com.streamvault.player.playback

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM-only tests for the M7 strict reuse policy ([evaluateReuseStrict]).
 *
 * The tests build [Format] instances via [Format.Builder] (Media3's public API)
 * and pass the codec name as a plain [String] so no `MediaCodecInfo` instance is
 * required. The `fallback` lambda is a closure capturing a boolean: when the
 * policy delegates we assert it was invoked and the returned evaluation is the
 * fallback's own.
 */
@UnstableApi
class CanReuseCodecPolicyTest {

    private val codecName = "c2.android.avc.decoder"

    private val baseH264_1080p: Format = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .setWidth(1920)
        .setHeight(1080)
        .setPixelWidthHeightRatio(1.0f)
        .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
        .build()

    /**
     * Wraps a fallback lambda that returns a sentinel `DecoderReuseEvaluation`
     * and records whether it was invoked. Allows asserting both that the policy
     * delegates **and** that the returned object is the fallback's, not a
     * `REUSE_RESULT_YES_WITHOUT_RECONFIGURATION` short-circuit.
     */
    private class RecordingFallback(
        oldFormat: Format,
        newFormat: Format,
        codecName: String = "c2.android.avc.decoder",
        result: Int = DecoderReuseEvaluation.REUSE_RESULT_NO,
        discardReasons: Int = DecoderReuseEvaluation.DISCARD_REASON_MIME_TYPE_CHANGED
    ) {
        var called: Boolean = false
            private set

        val sentinel: DecoderReuseEvaluation = DecoderReuseEvaluation(
            codecName, oldFormat, newFormat, result, discardReasons
        )

        val lambda: () -> DecoderReuseEvaluation = {
            called = true
            sentinel
        }
    }

    @Test
    fun `identical H264 1080p formats reuse without reconfiguration`() {
        val newFormat = baseH264_1080p.buildUpon().build()
        val fb = RecordingFallback(baseH264_1080p, newFormat)

        val evaluation = evaluateReuseStrict(codecName, baseH264_1080p, newFormat, fb.lambda)

        assertThat(fb.called).isFalse()
        assertThat(evaluation.result)
            .isEqualTo(DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION)
        assertThat(evaluation.discardReasons).isEqualTo(0)
        assertThat(evaluation.decoderName).isEqualTo(codecName)
    }

    @Test
    fun `cross codec H264 to HEVC delegates to fallback`() {
        val hevc = baseH264_1080p.buildUpon()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .build()
        val fb = RecordingFallback(baseH264_1080p, hevc)

        val evaluation = evaluateReuseStrict(codecName, baseH264_1080p, hevc, fb.lambda)

        assertThat(fb.called).isTrue()
        assertThat(evaluation).isSameInstanceAs(fb.sentinel)
    }

    @Test
    fun `cross resolution 1080p to 720p delegates to fallback`() {
        val hd720 = baseH264_1080p.buildUpon()
            .setWidth(1280)
            .setHeight(720)
            .build()
        val fb = RecordingFallback(baseH264_1080p, hd720)

        val evaluation = evaluateReuseStrict(codecName, baseH264_1080p, hd720, fb.lambda)

        assertThat(fb.called).isTrue()
        assertThat(evaluation).isSameInstanceAs(fb.sentinel)
    }

    @Test
    fun `cross color HDR10 vs SDR delegates to fallback`() {
        val hdr10 = baseH264_1080p.buildUpon()
            .setColorInfo(
                ColorInfo.Builder()
                    .setColorSpace(C.COLOR_SPACE_BT2020)
                    .setColorRange(C.COLOR_RANGE_FULL)
                    .setColorTransfer(C.COLOR_TRANSFER_ST2084)
                    .build()
            )
            .build()
        val fb = RecordingFallback(baseH264_1080p, hdr10)

        val evaluation = evaluateReuseStrict(codecName, baseH264_1080p, hdr10, fb.lambda)

        assertThat(fb.called).isTrue()
        assertThat(evaluation).isSameInstanceAs(fb.sentinel)
    }

    @Test
    fun `cross pixel ratio square vs anamorphic delegates to fallback`() {
        val anamorphic = baseH264_1080p.buildUpon()
            .setPixelWidthHeightRatio(1.333f)
            .build()
        val fb = RecordingFallback(baseH264_1080p, anamorphic)

        val evaluation = evaluateReuseStrict(codecName, baseH264_1080p, anamorphic, fb.lambda)

        assertThat(fb.called).isTrue()
        assertThat(evaluation).isSameInstanceAs(fb.sentinel)
    }

    @Test
    fun `missing mime type in old format delegates to fallback`() {
        val noMime: Format = Format.Builder()
            .setWidth(1920)
            .setHeight(1080)
            .setPixelWidthHeightRatio(1.0f)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build()
        val fb = RecordingFallback(noMime, baseH264_1080p)

        val evaluation = evaluateReuseStrict(codecName, noMime, baseH264_1080p, fb.lambda)

        assertThat(fb.called).isTrue()
        assertThat(evaluation).isSameInstanceAs(fb.sentinel)
    }
}
