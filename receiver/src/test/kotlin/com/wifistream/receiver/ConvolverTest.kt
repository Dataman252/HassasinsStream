package com.wifistream.receiver

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/** The FFT convolver must match plain, slow, direct convolution (apart from its fixed delay and level). */
class ConvolverTest {
    private fun direct(x: FloatArray, h: FloatArray): FloatArray =
        FloatArray(x.size) { n -> var acc = 0f; for (k in h.indices) if (n - k >= 0) acc += h[k] * x[n - k]; acc }

    private fun check(irLength: Int, channels: Int) {
        val rnd = Random(7)
        val ir = Array(channels) { FloatArray(irLength) { rnd.nextFloat() * 2 - 1 } }
        val conv = Convolver(Impulse(ir, 48_000, "test"), 48_000)
        val n = 6000
        val left = FloatArray(n) { rnd.nextFloat() * 2 - 1 }
        val right = FloatArray(n) { rnd.nextFloat() * 2 - 1 }
        val outL = FloatArray(n)
        val outR = FloatArray(n)
        val pair = FloatArray(2)
        for (i in 0 until n) {
            conv.process(left[i], right[i], pair)
            outL[i] = pair[0]
            outR[i] = pair[1]
        }
        val expectL = when (channels) {
            4 -> direct(left, ir[0]).zip(direct(right, ir[2])) { a, b -> a + b }.toFloatArray()
            else -> direct(left, ir[0])
        }
        // Output is delayed by one block; the level is normalised, so compare shapes by a fitted scale.
        val d = Convolver.B
        var num = 0.0
        var den = 0.0
        for (i in d until n) { num += outL[i] * expectL[i - d]; den += expectL[i - d] * expectL[i - d] }
        val scale = (num / den).toFloat()
        var worst = 0f
        var peak = 0f
        for (i in d until n) {
            worst = maxOf(worst, abs(outL[i] - expectL[i - d] * scale))
            peak = maxOf(peak, abs(expectL[i - d] * scale))
        }
        println("ir=$irLength channels=$channels relative error=${worst / peak}")
        assertTrue("relative error ${worst / peak}", worst / peak < 1e-3f)
    }

    @Test fun monoShort() = check(300, 1)
    @Test fun monoLong() = check(3000, 1)
    @Test fun stereo() = check(1500, 2)
    @Test fun trueStereo() = check(1200, 4)
}
