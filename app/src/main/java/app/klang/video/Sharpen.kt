package app.klang.video

import android.content.Context
import android.media.MediaCodecList
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Real-time sharpening for the video screen: contrast adaptive sharpening (the method behind
 * AMD FidelityFX CAS) on the GPU. It brings out edges and texture without the bright halos of a
 * plain sharpen. It does not invent detail: for a truly sharper picture, pick a higher quality.
 * [amount] is read every frame, so the slider works while the film plays.
 */
@OptIn(UnstableApi::class)
class SharpenEffect(private val amount: () -> Float) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = SharpenProgram(useHdr, amount)
}

@OptIn(UnstableApi::class)
private class SharpenProgram(useHdr: Boolean, private val amount: () -> Float) : BaseGlShaderProgram(useHdr, 1) {
    private val program = GlProgram(VERTEX, FRAGMENT)
    private var w = 1
    private var h = 1

    init {
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        w = inputWidth; h = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        program.setFloatsUniform("uTexel", floatArrayOf(1f / w, 1f / h))
        program.setFloatUniform("uSharp", amount().coerceIn(0f, 1f))
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {
        super.release()
        runCatching { program.delete() }
    }

    companion object {
        private const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}"""
        private const val FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec2 uTexel;
uniform float uSharp;
varying vec2 vTexSamplingCoord;
void main() {
  vec2 t = vTexSamplingCoord;
  vec3 a = texture2D(uTexSampler, t + vec2(0.0, -uTexel.y)).rgb;
  vec3 b = texture2D(uTexSampler, t + vec2(-uTexel.x, 0.0)).rgb;
  vec3 c = texture2D(uTexSampler, t).rgb;
  vec3 d = texture2D(uTexSampler, t + vec2(uTexel.x, 0.0)).rgb;
  vec3 e = texture2D(uTexSampler, t + vec2(0.0, uTexel.y)).rgb;
  if (uSharp <= 0.001) { gl_FragColor = vec4(c, 1.0); return; }
  vec3 mn = min(min(min(a, b), min(d, e)), c);
  vec3 mx = max(max(max(a, b), max(d, e)), c);
  vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(0.0001)), 0.0, 1.0));
  float peak = -1.0 / mix(8.0, 4.5, uSharp);
  vec3 wgt = amp * peak;
  vec3 res = (c + (a + b + d + e) * wgt) / (1.0 + 4.0 * wgt);
  gl_FragColor = vec4(clamp(res, 0.0, 1.0), 1.0);
}"""
    }
}

/** The tallest picture this phone can decode for a codec ("avc1…", "vp09…", "av01…"). */
object Decoders {
    private val cache = HashMap<String, Int>()

    private fun mimeOf(codec: String): String? = when {
        codec.startsWith("avc") -> "video/avc"
        codec.startsWith("vp9") || codec.startsWith("vp09") -> "video/x-vnd.on2.vp9"
        codec.startsWith("av01") -> "video/av01"
        else -> null
    }

    @Synchronized
    fun maxHeight(codec: String): Int {
        val mime = mimeOf(codec) ?: return 1080
        cache[mime]?.let { return it }
        var best = 0
        runCatching {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder || mime !in info.supportedTypes.map { it.lowercase() }) continue
                val caps = info.getCapabilitiesForType(mime).videoCapabilities ?: continue
                val hw = if (android.os.Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !info.name.startsWith("OMX.google") && !info.name.startsWith("c2.android")
                for (hgt in intArrayOf(2160, 1440, 1080, 720)) {
                    val wdt = hgt * 16 / 9
                    // software decoders are allowed up to 1080p only: 4K in software stutters
                    if ((hw || hgt <= 1080) && runCatching { caps.areSizeAndRateSupported(wdt, hgt, 30.0) }.getOrDefault(false)) {
                        if (hgt > best) best = hgt
                        break
                    }
                }
            }
        }
        if (best == 0) best = 720
        cache[mime] = best
        return best
    }
}
