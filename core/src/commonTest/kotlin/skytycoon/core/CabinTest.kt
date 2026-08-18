package skytycoon.core

import skytycoon.core.data.Cities
import skytycoon.core.model.Business
import skytycoon.core.model.BusinessType
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

    private fun measure(
        from: String,
        to: String,
        type: String,
        share: Double,
        freq: Int,
        serviceLevel: Int = 3,
        brand: Double = -1.0,
        facilities: List<BusinessType> = emptyList(),
    ): R {
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
                if (a.id != "hanseong") {
                    a
                } else {
                    a.copy(
                        slots = a.slots + (from to 40) + (to to 40),
                        bizShare = share,
                        serviceLevel = serviceLevel,
                        brand = if (brand < 0) a.brand else a.brand.mapValues { brand },
                        // 시설은 양 끝에 하나씩 — 프리미엄 매력은 끝점에서만 값을 한다.
                        businesses = facilities.flatMap { listOf(Business(it, from), Business(it, to)) },
                    )
                }
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

    /**
     * 환승 승객은 **이코노미 빈자리만** 쓴다.
     *
     * 총 빈자리로 계산하면, 앞자리에 못 앉게 막아 둔 비프리미엄 수요가 환승으로
     * 우회해 그 자리를 채운다 — 객실을 크게 깔아도 빈자리가 메워지니 과대 객실의
     * 대가가 사라지고, 그 손님은 환승 운임만 내면서 앞자리 승객으로도 안 잡힌다.
     */
    @Test
    fun `환승 승객이 빈 앞자리를 채우지 않는다`() {
        val base = NewGame.create(seed = 7)
        var s: GameState = base.copy(routes = emptyList(), planes = base.planes.map { it.copy(routeId = null) })
        var nextId = s.nextId
        val planes = mutableListOf<Plane>()
        val routes = mutableListOf<Route>()
        val slots = HashMap<String, Int>()
        var longHaul = -1
        fun add(from: String, to: String, type: String): Int {
            val pid = nextId++
            val rid = nextId++
            val cap = Economics.capacity(listOf(Plane(pid, type, "fuji", 8)), Geo.distance(from, to))
            planes += Plane(pid, type, "fuji", ageQuarters = 8, routeId = rid)
            routes += Route(rid, "fuji", from, to, freq = cap.maxFreq, planeIds = listOf(pid))
            slots[from] = (slots[from] ?: 0) + 40
            slots[to] = (slots[to] ?: 0) + 40
            return rid
        }
        longHaul = add("tokyo", "losangeles", "b747_100")
        for (spoke in listOf("seoul", "beijing", "hongkong", "taipei")) add("tokyo", spoke, "b727")

        s = s.copy(
            planes = planes,
            routes = routes,
            nextId = nextId,
            airlines = s.airlines.map { a ->
                if (a.id == "fuji") a.copy(slots = a.slots + slots, bizShare = Balance.BIZ_SHARE_MAX) else a
            },
        )
        val o = Market.resolveAll(s).getValue(longHaul)
        val emptyCabin = o.bizSeatsOffered - o.bizCabinPax
        println(
            "[환승·객실] 좌석 ${o.seats.toInt()} (앞자리 ${o.bizSeatsOffered.toInt()}, 그중 착석 " +
                "${o.bizCabinPax.toInt()}) · 로컬 ${o.localPax.toInt()} + 환승 ${o.connectPax.toInt()} " +
                "= ${o.pax.toInt()}",
        )
        assertTrue(emptyCabin > 1.0, "앞자리가 다 차 버려 이 테스트가 아무것도 못 잡는다")
        assertTrue(
            o.pax <= o.seats - emptyCabin + 1.0,
            "환승 승객이 빈 앞자리(${emptyCabin.toInt()}석)까지 채웠다 " +
                "(총 ${o.pax.toInt()} > 쓸 수 있는 ${(o.seats - emptyCabin).toInt()})",
        )
    }

    @Test
    fun `프리미엄 수요보다 크게 깔면 앞자리가 빈다`() {
        val big = measure("london", "paris", "b727", Balance.BIZ_SHARE_MAX, 14)
        val fill = big.cabinPax / big.cabinSeats
        println("[빈 앞자리] 런던-파리 최대 객실 ${big.cabinSeats.toInt()}석 중 ${big.cabinPax.toInt()}명 (${(fill * 100).toInt()}%)")
        assertTrue(fill < 0.85, "객실을 최대로 깔았는데도 다 찬다 — 프리미엄 수요가 희소하지 않다 (충전율 $fill)")
    }

    // ----------------------------------------------------- 앞자리 수요는 어디로 몰리나

    /**
     * 앞자리는 좌석이 아니라 대접을 사는 자리다 — 같은 객실을 깔아도 **라운지가 있고
     * 서비스가 좋고 이름이 알려진 회사**가 더 많이 판다.
     *
     * 이게 없으면 객실은 순수한 산수 문제라 아홉 개 회사가 전부 같은 답(20%)을 낸다.
     */
    @Test
    fun `앞자리 수요는 대접이 좋은 회사로 몰린다`() {
        // 객실을 최대로 깔아 **좌석이 아니라 수요가** 한계가 되게 한다. 좁게 깔면
        // 양쪽 다 꽉 차서 이 테스트가 아무것도 못 잡는다.
        val big = Balance.BIZ_SHARE_MAX
        val plain = measure("london", "paris", "b727", big, 14, serviceLevel = 3, brand = 30.0)
        val posh = measure(
            "london", "paris", "b727", big, 14,
            serviceLevel = 5, brand = 90.0, facilities = listOf(BusinessType.LOUNGE),
        )
        val plainFill = plain.cabinPax / plain.cabinSeats
        val poshFill = posh.cabinPax / posh.cabinSeats
        println(
            "[프리미엄 쏠림] 평범(서비스3·브랜드30) ${(plainFill * 100).toInt()}% · " +
                "고급(서비스5·브랜드90·라운지) ${(poshFill * 100).toInt()}%",
        )
        assertTrue(plainFill < 0.9, "평범한 회사도 앞자리가 다 찬다 — 이 테스트가 아무것도 못 잡는다")
        assertTrue(
            poshFill > plainFill * 1.2,
            "대접이 좋은 회사인데 앞자리가 더 안 팔린다 ($poshFill vs $plainFill)",
        )
    }

    /**
     * 그 쏠림이 **객실 크기 결정을 바꿔야** 한다. 앞자리를 더 파는 회사는 더 크게 깔 수
     * 있어야 하고, 아니라면 매력도는 마진만 조금 올려 주는 장식이지 판단거리가 아니다.
     */
    @Test
    fun `대접이 좋은 회사는 객실을 더 크게 깐다`() {
        val curve = listOf(0.0, 0.10, 0.20, 0.30, Balance.BIZ_SHARE_MAX)
        fun best(service: Int, brand: Double, fac: List<BusinessType>): Double = curve
            .maxByOrNull { measure("seoul", "tokyo", "b727", it, 14, service, brand, fac).margin }!!
        fun curveOf(service: Int, brand: Double, fac: List<BusinessType>) = curve.joinToString(" ") {
            "${(it * 100).toInt()}%:${(measure("seoul", "tokyo", "b727", it, 14, service, brand, fac).margin * 100).toInt()}%"
        }
        val plain = best(3, 30.0, emptyList())
        val posh = best(5, 90.0, listOf(BusinessType.LOUNGE, BusinessType.HOTEL))
        println("[최적 객실] 평범 ${(plain * 100).toInt()}% ← ${curveOf(3, 30.0, emptyList())}")
        println(
            "[최적 객실] 고급 ${(posh * 100).toInt()}% ← " +
                curveOf(5, 90.0, listOf(BusinessType.LOUNGE, BusinessType.HOTEL)),
        )
        assertTrue(posh > plain, "대접이 좋아도 최적 객실 크기가 그대로다 ($posh vs $plain)")
    }
}
