package skytycoon.core

import skytycoon.core.data.Cities
import skytycoon.core.model.GameState
import skytycoon.core.model.Plane
import skytycoon.core.model.Route
import skytycoon.core.sim.Balance
import skytycoon.core.sim.Economics
import skytycoon.core.sim.Geo
import skytycoon.core.sim.Market
import skytycoon.core.sim.NewGame
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * 운임 곡선 진단 하네스 — 거리대별로 편수를 훑어 **탑승률과 마진이 어떻게 갈리는지**
 * 표로 찍는다. [Balance.FARE_BASE]·[Balance.FARE_PER_KM] 을 만질 때 눈으로 보는 용도다.
 * 판정이 아니라 관찰이라 평소에는 돌리지 않는다:
 *   ./gradlew :core:jvmTest --tests '*FareCurveDiag*' -i
 *
 * 이걸로 잡은 것: 예전 `FARE_BASE = 7.0` 에서는 800km 아래가 **어떤 편수로도 흑자가
 * 나지 않았다** (런던–파리 343km 최선이 마진 -30%). 거리대 하나가 통째로 죽어 있었다.
 */
@Ignore
class FareCurveDiag {

    @Test
    fun probe() {
        val pairs = listOf(
            "london" to "paris", "frankfurt" to "london", "seoul" to "tokyo",
            "newyork" to "chicago", "tokyo" to "hongkong",
        )
        for ((x, y) in pairs) {
            val dist = Geo.distance(x, y)
            val fare = Economics.standardFare(dist, 1.0)
            val best = (2..30 step 4).mapNotNull { f -> run(x, y, f)?.let { f to it } }
            val line = best.joinToString(" ") { (f, e) -> "주$f:${(e.first * 100).toInt()}%/${(e.second * 100).toInt()}%" }
            println("[${x}-${y}] ${dist.toInt()}km 표준운임 \$${fare.toInt()}  (편수:LF/마진) $line")
        }
    }

    /** (탑승률, 마진) */
    private fun run(x: String, y: String, freq: Int): Pair<Double, Double>? {
        val base = NewGame.create(seed = 7)
        val s0 = base.copy(routes = emptyList(), planes = base.planes.map { it.copy(routeId = null) })
        val dist = Geo.distance(x, y)
        val pid = s0.nextId
        val rid = pid + 1
        val cap = Economics.capacity(listOf(Plane(pid, "b727", "hanseong", 8)), dist)
        if (!cap.usable || freq > cap.maxFreq) return null
        val s = s0.copy(
            planes = listOf(Plane(pid, "b727", "hanseong", 8, routeId = rid)),
            routes = listOf(Route(rid, "hanseong", x, y, freq = freq, planeIds = listOf(pid))),
            nextId = rid + 1,
            airlines = s0.airlines.map { a ->
                if (a.id == "hanseong") a.copy(slots = a.slots + (x to 40) + (y to 40)) else a
            },
        )
        val route = s.routes.first()
        val o = Market.resolvePair(s, Cities[x], Cities[y], s.routes).first()
        val planes = s.planes
        val cargo = o.revenue * Balance.CARGO_RATE
        val gross = o.revenue + cargo
        val direct = Economics.routeCost(s, s.airline("hanseong"), route, planes, o.pax, gross).total
        val cost = direct + Economics.depreciation(planes) +
            Balance.OVERHEAD_PER_AIRCRAFT + Balance.OVERHEAD_PER_ROUTE
        val seats = Economics.quarterlySeats(freq, cap.avgSeats)
        return (o.pax / seats) to ((gross - cost) / gross)
    }
}
