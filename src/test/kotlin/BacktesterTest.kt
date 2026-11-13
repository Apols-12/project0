import com.apols.model.Backtester
import com.apols.model.Direction
import com.apols.model.ExitReason
import com.apols.model.FeeModel
import com.apols.model.Kline
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BacktesterTest {
    private fun series(n: Int): MutableList<Kline> {
        val out = ArrayList<Kline>(n + 10)
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

    private fun winningScenario(): List<Kline> = series(200).also {
        it.bar(0.02, 500.0)   // signal bar
        it.bar(0.015, 100.0)  // next bar runs into the target
        repeat(4) { i -> it.bar(if (i % 2 == 0) 0.001 else -0.001, 100.0) }
    }

    @Test fun quietMarketMakesNoTrades() {
        val r = Backtester().run(series(300))
        assertTrue(r.trades.isEmpty())
        assertEquals(r.initialEquity, r.finalEquity)
    }

    @Test fun longSignalHitsTargetAndEntersAfterSignal() {
        val r = Backtester().run(winningScenario())
        assertEquals(1, r.trades.size)
        val t = r.trades.single()
        assertEquals(Direction.LONG, t.direction)
        assertEquals(ExitReason.TARGET, t.exitReason)
        assertTrue(t.entryTime > t.signalTime, "entry must happen after the signal bar (no lookahead)")
        assertTrue(t.netPnl > 0)
        assertTrue(t.fees > 0)
    }

    @Test fun feesReducePnl() {
        val data = winningScenario()
        val free = Backtester(fees = FeeModel(0.0, 0.0)).run(data)
        val paid = Backtester(fees = FeeModel(10.0, 2.0)).run(data)
        assertTrue(free.netPnl > paid.netPnl)
    }

    @Test fun stopWinsWhenStopAndTargetTouchedInSameBar() {
        val k = series(200)
        k.bar(0.02, 500.0)
        val o = k.last().close
        k.add(Kline(k.last().openTime + 60_000L, o, o * 1.03, o * 0.97, o, 100.0))
        val r = Backtester().run(k)
        assertEquals(1, r.trades.size)
        assertEquals(ExitReason.STOP, r.trades.single().exitReason)
        assertTrue(r.trades.single().netPnl < 0)
    }
}
