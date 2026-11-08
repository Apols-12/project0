import com.apols.model.Direction
import com.apols.model.FeeModel
import com.apols.model.Kline
import com.apols.model.SignalConfig
import com.apols.model.SignalEngine
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SignalEngineTest {
    private val engine = SignalEngine()

    private fun series(n: Int): MutableList<Kline> {
        val out = ArrayList<Kline>(n + 5)
        var p = 100.0
        for (i in 0 until n) {
            val o = p
            p *= if (i % 2 == 0) 1.001 else 0.999
            out += Kline(i * 60_000L, o, max(o, p) * 1.001, min(o, p) * 0.999, p, 100.0)
        }
        return out
    }

    private fun MutableList<Kline>.bar(move: Double, volume: Double) {
        val o = last().close
        val c = o * (1 + move)
        add(Kline(last().openTime + 60_000L, o, max(o, c) * 1.001, min(o, c) * 0.999, c, volume))
    }

    @Test fun insufficientDataIsNeutral() {
        val p = engine.predict(series(10))
        assertEquals(Direction.NEUTRAL, p.direction)
        assertTrue(p.reasons.first().contains("insufficient"))
    }

    @Test fun quietMarketIsNeutral() {
        assertEquals(Direction.NEUTRAL, engine.predict(series(200)).direction)
    }

    @Test fun upBurstWithVolumeIsLong() {
        val k = series(200).also { it.bar(0.02, 500.0) }
        val p = engine.predict(k)
        assertEquals(Direction.LONG, p.direction)
        assertTrue(p.netEdgeBps > 0)
        assertTrue(p.stopPrice!! < p.entryPrice && p.targetPrice!! > p.entryPrice)
    }

    @Test fun downBurstWithVolumeIsShort() {
        val k = series(200).also { it.bar(-0.02, 500.0) }
        assertEquals(Direction.SHORT, engine.predict(k).direction)
    }

    @Test fun burstWithoutVolumeIsIgnored() {
        val k = series(200).also { it.bar(0.02, 100.0) }
        assertEquals(Direction.NEUTRAL, engine.predict(k).direction)
    }

    @Test fun highFeesBlockTrade() {
        val costly = SignalEngine(fees = FeeModel(takerBps = 100.0))
        val k = series(200).also { it.bar(0.02, 500.0) }
        val p = costly.predict(k)
        assertEquals(Direction.NEUTRAL, p.direction)
        assertTrue(p.reasons.any { it.contains("edge") })
    }

    @Test fun cooldownSuppressesBackToBackSignals() {
        val k = series(200).also { it.bar(0.02, 500.0); it.bar(0.02, 500.0) }
        val p = engine.predict(k)
        assertEquals(Direction.NEUTRAL, p.direction)
        assertTrue(p.reasons.any { it.contains("cooldown") })
    }

    @Test fun unsortedInputThrows() {
        val k = series(100)
        k.add(k[50])
        assertFailsWith<IllegalArgumentException> { engine.predict(k) }
    }

    @Test fun invalidKlineRejected() {
        assertFailsWith<IllegalArgumentException> { Kline(0, 10.0, 9.0, 11.0, 10.0, 1.0) }
    }

    @Test fun invalidConfigRejected() {
        assertFailsWith<IllegalArgumentException> { SignalConfig(fastPeriod = 30, slowPeriod = 21) }
    }
}
