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
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 비즈니스 객실은 **좌석과 단가를 맞바꾸는** 결정이어야 한다.
 *
 * 처음 넣었을 때는 맞바꿈이 아니었다 — 태우는 인원은 그대로인데 단가만 올라,
 * 레저 위주인 런던–파리조차 30% 가 10% 보다 나았다. 무조건 최대치가 답이면
 * 그건 선택지가 아니라 공짜 상향이다. 그래서 두 가지를 넣었다:
 *
 * 1. **프리미엄 수요는 희소하다** ([Balance.BIZ_CABIN_TAKEUP]) — 출장 수요 전체가
 *    앞자리 값을 내지는 않으므로, 크게 깔면 빈 채로 난다.
 * 2. **깔아 놓은 좌석에 유지비가 붙는다** ([Balance.BIZ_CABIN_SEAT_COST]) —
 *    비어 있어도 승무원·갤리·정비는 나간다.
 */
class CabinTest {

    private class R(val seats: Double, val cabinSeats: Double, val cabinPax: Double, val margin: Double)

    private fun measure(from: String, to: String, type: String, share: Double, freq: Int): R {
        val base = NewGame.create(seed = 7)
        var s: GameState = base.copy(routes = emptyList(), planes = base.planes.map { it.copy(routeId = null) })
        val dist = Geo.distance(from, to)
        val pid = s.nextId
        val rid = pid + 1
        val cap = Economics.capacity(listOf(Plane(pid, type, "hanseong", 8)), dist)
        val f = freq.coerceAtMost(cap.maxFreq)
        s = s.copy(
            planes = listOf(Plane(pid, type, "hanseong", 8, routeId = rid)),
            routes = listOf(Route(rid, "hanseong", from, to, freq = f, planeIds = listOf(pid))),
            nextId = rid + 1,
            airlines = s.airlines.map { a ->
                if (a.id == "hanseong") a.copy(slots = a.slots + (from to 40) + (to to 40), bizShare = share) else a
            },
        )
        val route = s.routes.first()
        val o = Market.resolvePair(s, Cities[from], Cities[to], s.routes).first()
        val gross = o.revenue * (1 + Balance.CARGO_RATE)
        val direct = Economics
            .routeCost(s, s.airline("hanseong"), route, s.planes, o.pax, gross, o.bizCabinPax, o.bizSeatsOffered)
            .total
        val rent = route.freq * (
            Economics.slotRent(s, "hanseong", route.from) + Economics.slotRent(s, "hanseong", route.to)
            )
        val cost = direct + Economics.depreciation(s.planes) +
            Balance.OVERHEAD_PER_AIRCRAFT + Balance.OVERHEAD_PER_ROUTE + rent
        return R(o.seats, o.bizSeatsOffered, o.bizCabinPax, (gross - cost) / gross)
    }

    @Test
    fun `앞자리를 깔면 총 좌석이 줄어든다`() {
        val none = measure("seoul", "tokyo", "b727", 0.0, 14)
        val some = measure("seoul", "tokyo", "b727", 0.20, 14)
        println("[좌석] 이코노미 전용 ${none.seats.toInt()} → 비즈 20% ${some.seats.toInt()}")
        assertTrue(some.seats < none.seats, "객실을 깔았는데 총 좌석이 안 줄었다 — 바닥이 공짜다")
        assertTrue(some.cabinSeats > 0.0, "비즈 좌석이 깔리지 않았다")
    }

    @Test
    fun `객실을 키울수록 무조건 이득이면 안 된다`() {
        for ((a, b, type) in listOf(
            Triple("seoul", "tokyo", "b727"),
            Triple("london", "paris", "b727"),
            Triple("newyork", "miami", "b727"),
            Triple("tokyo", "losangeles", "b747_100"),
        )) {
            val curve = listOf(0.0, 0.10, 0.20, Balance.BIZ_SHARE_MAX).map { it to measure(a, b, type, it, 14) }
            val best = curve.maxByOrNull { it.second.margin }!!
            val most = curve.last()
            println(
                "[$a-$b] " + curve.joinToString(" ") { "${(it.first * 100).toInt()}%:${(it.second.margin * 100).toInt()}%" } +
                    " → 최적 ${(best.first * 100).toInt()}%",
            )
            assertTrue(
                best.first < Balance.BIZ_SHARE_MAX,
                "$a-$b 에서 최대치가 최적이다 — 객실 크기가 판단거리가 아니라 공짜 상향이다",
            )
            assertTrue(
                most.second.margin < best.second.margin,
                "$a-$b 에서 최대치로 밀어도 손해가 없다 (${most.second.margin} vs ${best.second.margin})",
            )
        }
    }

    @Test
    fun `프리미엄 수요보다 크게 깔면 앞자리가 빈다`() {
        val big = measure("london", "paris", "b727", Balance.BIZ_SHARE_MAX, 14)
        val fill = big.cabinPax / big.cabinSeats
        println("[빈 앞자리] 런던-파리 최대 객실 ${big.cabinSeats.toInt()}석 중 ${big.cabinPax.toInt()}명 (${(fill * 100).toInt()}%)")
        assertTrue(fill < 0.85, "객실을 최대로 깔았는데도 다 찬다 — 프리미엄 수요가 희소하지 않다 (충전율 $fill)")
    }
}
