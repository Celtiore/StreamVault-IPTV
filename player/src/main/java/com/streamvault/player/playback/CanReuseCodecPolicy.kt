package com.streamvault.player.playback

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation

/**
 * Pure decision function for video [`canReuseCodec`][androidx.media3.exoplayer.video.MediaCodecVideoRenderer.canReuseCodec]
 * implementing the M7 fast-zap "P1 reuse strict" policy (SCOPE D4).
 *
 * Two consecutive channels are considered codec-identical and the existing decoder is
 * reused (no reconfiguration, no flush) when **all** of the following hold on the
 * incoming [Format]:
 *  1. `sampleMimeType` equals (and non-null)
 *  2. `width` and `height` equal (and not `Format.NO_VALUE`)
 *  3. `colorInfo` equals — `ColorInfo.equals` covers `colorSpace`, `colorRange`,
 *     `colorTransfer` and `hdrStaticInfo`, so HDR ↔ SDR transitions never reuse
 *  4. `pixelWidthHeightRatio` equals — Media3 produces exact values (1.0f for square
 *     pixels, 1.333…f for 4:3 anamorphic) so exact equality is safe without epsilon
 *
 * For any difference the decision is delegated to [fallback] (typically
 * `super.canReuseCodec(...)`), letting Media3's default heuristic still return
 * `REUSE_RESULT_YES_WITH_RECONFIGURATION` for safe cases (e.g. dim-compatible
 * H.264 → H.264).
 *
 * The pre-M7 codepath used a hardcoded `REUSE_RESULT_NO` /
 * `DISCARD_REASON_MAX_INPUT_SIZE_EXCEEDED`, suspected to date back to a Sigma SoC
 * green-frame protection. The strict equality criterion preserves that safety on
 * legacy SoCs that don't tolerate any in-session format change while unlocking
 * the common case where consecutive HLS channels share the same encoder profile
 * (the bulk of zaps measured on the test corpus, cf. `docs/m7/post-p2-bench.md`).
 *
 * Kept as a top-level package-private function so the closure renderer override in
 * `Media3PlayerEngine` stays thin and the policy is JVM-unit-testable without
 * standing up a `MediaCodecInfo` instance.
 *
 * @param codecName the active codec name, forwarded into the resulting
 *   [DecoderReuseEvaluation]. Extracted at call site from `codecInfo.name`.
 */
@UnstableApi
internal fun evaluateReuseStrict(
    codecName: String,
    oldFormat: Format,
    newFormat: Format,
    fallback: () -> DecoderReuseEvaluation
): DecoderReuseEvaluation {
    val sameMime = oldFormat.sampleMimeType != null &&
        oldFormat.sampleMimeType == newFormat.sampleMimeType
    val sameDim = oldFormat.width != Format.NO_VALUE &&
        oldFormat.height != Format.NO_VALUE &&
        oldFormat.width == newFormat.width &&
        oldFormat.height == newFormat.height
    val sameColor = oldFormat.colorInfo == newFormat.colorInfo
    val samePixelRatio = oldFormat.pixelWidthHeightRatio == newFormat.pixelWidthHeightRatio

    return if (sameMime && sameDim && sameColor && samePixelRatio) {
        DecoderReuseEvaluation(
            codecName,
            oldFormat,
            newFormat,
            DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION,
            0
        )
    } else {
        fallback()
    }
}
