package com.gpic.android.editor

import android.content.Context
import android.graphics.Matrix
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.MatrixTransformation
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The outgoing part's last frame, handed to the next part for a dissolve or slide. Media3 draws a sequence's items
 * one after the other through the same effect pipeline, so the frame is complete before the next part's first one.
 * It's a CPU copy because GL objects belong to the program that made them (a part's program is released at its end).
 * One per built composition; used only on that composition's GL thread.
 */
internal class CarryOver {
    var pixels: ByteBuffer? = null
    var width = 0
    var height = 0
    var part = -1
    /** Bumped on every copy, so the incoming part uploads the newest one. */
    var version = 0
}

/** Ken Burns alone (a part without transitions): a matrix, so Media3 merges it with the fit into one pass. */
@OptIn(UnstableApi::class)
internal class KenBurnsMotion(private val motion: Motion, private val clock: PartClock) : MatrixTransformation {
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val f = if (clock.durationUs > 0) clock.toLocal(presentationTimeUs).toFloat() / clock.durationUs else 0f
        val (s, x, y) = KenBurns.at(motion, f)
        return Matrix().apply { postScale(s, s); postTranslate(x, y) }
    }
}

/**
 * Part [index]'s move and transitions in one pass ([TransitionMath] says what to draw): its Ken Burns move, zooms,
 * whips and spins with motion blur, fades and flashes, glitch, and mixing in the previous part's last frame. A part
 * followed by a dissolve or slide also copies out its last frames for the next part.
 */
@OptIn(UnstableApi::class)
internal class PartFx(val index: Int, val edges: PartEdges, val clock: PartClock, val carry: CarryOver) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = PartFxProgram(this, useHdr)
}

@OptIn(UnstableApi::class)
private class PartFxProgram(private val fx: PartFx, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
    private val program = GlProgram(VERTEX, FRAGMENT)
    private var width = 0
    private var height = 0
    private var prevTex = 0
    private var prevVersion = -1
    private var readFbo = 0
    private var lastLocalUs = Long.MIN_VALUE
    /** Largest gap between this part's frames so far (slow motion has fewer), for knowing which frames may be the last. */
    private var maxGapUs = 40_000L

    init {
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val e = fx.edges
            val local = fx.clock.toLocal(presentationTimeUs)
            val prevReady = e.inT.blends && local < e.inUs && uploadPrev()
            val st = TransitionMath.state(e, local, fx.clock.durationUs, width, height, prevReady)
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setSamplerTexIdUniform("uPrevSampler", if (prevReady) prevTex else inputTexId, 1)
            program.setFloatsUniformIfPresent("uLive0", st.live0.toMat3())
            program.setFloatsUniformIfPresent("uLive1", st.live1.toMat3())
            program.setFloatsUniformIfPresent("uPrev0", st.prev0.toMat3())
            program.setFloatsUniformIfPresent("uPrev1", st.prev1.toMat3())
            program.setFloatsUniformIfPresent("uTaps", floatArrayOf(st.taps.toFloat()))
            program.setFloatsUniformIfPresent("uPrevAlpha", floatArrayOf(st.prevAlpha))
            program.setFloatsUniformIfPresent("uPrevClip", floatArrayOf(if (st.prevClipped) 1f else 0f))
            program.setFloatsUniformIfPresent("uShadeK", floatArrayOf(st.k))
            program.setFloatsUniformIfPresent("uShadeWhite", floatArrayOf(st.white))
            program.setFloatsUniformIfPresent("uGlitch", floatArrayOf(st.glitch))
            program.setFloatsUniformIfPresent("uSeed", floatArrayOf(st.seed))
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
            if (e.keepsLastFrame) keepIfLast(inputTexId, local)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    /** Puts the previous part's last frame in [prevTex]; false when there's none (the preview seeked into this part). */
    private fun uploadPrev(): Boolean {
        val c = fx.carry
        val px = c.pixels ?: return false
        if (c.part != fx.index - 1 || c.width != width || c.height != height) return false
        if (prevTex != 0 && prevVersion == c.version) return true
        if (prevTex == 0) prevTex = GlUtil.createTexture(width, height, false)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTex)
        px.position(0)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
        GlUtil.checkGlError()
        prevVersion = c.version
        return true
    }

    /**
     * Copies the frame (before this pass: the next part redraws it with this part's move carried on) when it may be
     * the part's last one. Which frame is last isn't known in advance, so every frame near the end is copied.
     */
    private fun keepIfLast(inputTexId: Int, local: Long) {
        if (lastLocalUs != Long.MIN_VALUE && local > lastLocalUs) maxGapUs = maxOf(maxGapUs, local - lastLocalUs)
        lastLocalUs = local
        if (fx.clock.durationUs - local > (maxGapUs * 5 / 2).coerceIn(60_000, 400_000)) return
        val c = fx.carry
        val size = width * height * 4
        val buf = c.pixels?.takeIf { it.capacity() == size } ?: ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        if (readFbo == 0) readFbo = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
        val bound = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, it, 0) }[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, readFbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, inputTexId, 0)
        buf.position(0)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, 0, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bound)
        GlUtil.checkGlError()
        c.pixels = buf
        c.width = width
        c.height = height
        c.part = fx.index
        c.version++
    }

    override fun flush() {
        super.flush()
        lastLocalUs = Long.MIN_VALUE
    }

    override fun release() {
        super.release()
        try {
            program.delete()
            if (prevTex != 0) GlUtil.deleteTexture(prevTex)
            if (readFbo != 0) GlUtil.deleteFbo(readFbo)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

        // GLSL ES 1.00 so it runs on every GPU Media3 supports. Samples outside the frame are mirrored back in (a
        // moving or turned picture never shows black edges, and the blur hides the seam).
        const val FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform sampler2D uPrevSampler;
uniform mat3 uLive0;
uniform mat3 uLive1;
uniform mat3 uPrev0;
uniform mat3 uPrev1;
uniform float uTaps;
uniform float uPrevAlpha;
uniform float uPrevClip;
uniform float uShadeK;
uniform float uShadeWhite;
uniform float uGlitch;
uniform float uSeed;
varying vec2 vTexSamplingCoord;

vec2 mirrored(vec2 uv) {
  vec2 t = mod(uv, 2.0);
  return 1.0 - abs(t - 1.0);
}

float inside(vec2 p) {
  vec2 s = step(vec2(0.0), p) * step(p, vec2(1.0));
  return s.x * s.y;
}

vec3 tap(vec2 l, vec2 p) {
  vec3 live = texture2D(uTexSampler, mirrored(l)).rgb;
  if (uPrevAlpha <= 0.0) return live;
  float w = uPrevAlpha * mix(1.0, inside(p), uPrevClip);
  return mix(live, texture2D(uPrevSampler, mirrored(p)).rgb, w);
}

void main() {
  vec3 uv = vec3(vTexSamplingCoord, 1.0);
  vec2 l0 = (uLive0 * uv).xy;
  vec2 p0 = (uPrev0 * uv).xy;
  vec3 col;
  if (uGlitch > 0.0) {
    float band = floor(vTexSamplingCoord.y * 18.0);
    float r = fract(sin(band * 12.9898 + uSeed * 78.233) * 43758.5453);
    vec2 jump = vec2(step(1.0 - 0.5 * uGlitch, r) * (r - 0.5) * 0.2 * uGlitch, 0.0);
    vec2 split = vec2(0.025 * uGlitch, 0.0);
    col = vec3(tap(l0 + jump + split, p0 + jump + split).r, tap(l0 + jump, p0 + jump).g, tap(l0 + jump - split, p0 + jump - split).b);
  } else {
    vec2 l1 = (uLive1 * uv).xy;
    vec2 p1 = (uPrev1 * uv).xy;
    int n = int(uTaps + 0.5);
    if (n <= 1) {
      col = tap(l0, p0);
    } else {
      col = vec3(0.0);
      for (int i = 0; i < 16; i++) {
        if (i >= n) break;
        float f = float(i) / float(n - 1);
        col += tap(mix(l0, l1, f), mix(p0, p1, f));
      }
      col /= float(n);
    }
  }
  gl_FragColor = vec4(col * uShadeK + vec3(uShadeWhite), 1.0);
}
"""
    }
}
