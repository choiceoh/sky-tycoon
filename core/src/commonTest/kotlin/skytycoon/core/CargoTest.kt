package skytycoon.core

import skytycoon.core.data.AircraftCatalog
import skytycoon.core.data.Cities
import skytycoon.core.model.GameState
import skytycoon.core.model.Plane
import skytycoon.core.model.Route
import skytycoon.core.sim.Cargo
import skytycoon.core.sim.Economics
import skytycoon.core.sim.Geo
import skytycoon.core.sim.Market
import skytycoon.core.sim.NewGame
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 화물이 여객의 그림자에서 벗어났는가.
 *
 * 예전 규칙은 `화물매출 = 여객매출 × 0.13` 이었다. 그 식에서는 어느 노선을 어떤 기재로
 * 날든 화물 비중이 똑같아, 화물이 기종 선택에도 노선 선택에도 끼어들지 못했다.
 * 여기서 지키는 것은 **화물이 실제로 갈린다**는 것 — 갈리지 않으면 되돌아간 것이다.
 */
class CargoTest {

    /** 노선 하나만 세운 판. 그 노선의 (여객 매출, 화물 매출)을 돌려준다. */
    private fun soloRoute(from: String, to: String, typeId: String, freq: Int = 3): Pair<Double, Double> {
        val base = NewGame.create(seed = 7, companyId = "hanseong")
        val pid = base.nextId
        val rid = pid + 1
        val s: GameState = base.copy(
            routes = listOf(Route(rid, "hanseong", from, to, freq = freq, planeIds = listOf(pid))),
            planes = listOf(Plane(pid, typeId, "hanseong", 8, routeId = rid)),
            nextId = rid + 1,
            airlines = base.airlines.map { a ->
                if (a.id == "hanseong") a.copy(slots = a.slots + (from to 40) + (to to 40)) else a
            },
        )
        val outs = Market.resolveAll(s)
        val pax = outs[rid]?.revenue ?: 0.0
        return pax to (Cargo.resolveAll(s, outs)[rid] ?: 0.0)
    }

    private fun share(p: Pair<Double, Double>) = if (p.first + p.second <= 0.0) 0.0 else p.second / (p.first + p.second)

    /**
     * 같은 구간, 같은 편수인데 **기종만 다르면** 화물이 크게 갈려야 한다.
     * 이게 이 규칙의 존재 이유다 — 광동체를 고를 이유 하나가 화물에서 나온다.
     */
    @Test
    fun `같은 노선이라도 광동체가 화물을 훨씬 많이 싣는다`() {
        val wide = share(soloRoute("seoul", "newyork", "b747_100"))
        val narrow = share(soloRoute("seoul", "newyork", "b707"))
        assertTrue(wide > narrow * 2.5, "광동체 화물 비중 $wide 가 협동체 $narrow 와 크게 다르지 않다")
    }

    /** 짧은 구간은 트럭·철도가 가져간다 — 거리가 붙어야 화물이 붙는다. */
    @Test
    fun `단거리에는 화물이 잘 붙지 않는다`() {
        val short = share(soloRoute("seoul", "tokyo", "b727"))
        val long = share(soloRoute("seoul", "newyork", "b747_100"))
        assertTrue(long > short * 2.0, "장거리($long)와 단거리($short)의 화물 비중이 비슷하다")
    }

    /** 화물은 관광지가 아니라 경제 중심지 사이를 흐른다. */
    @Test
    fun `화물 수요는 관광이 아니라 경제 규모를 따라간다`() {
        val s = NewGame.create(seed = 7)
        // 두 구간의 거리를 비슷하게 두고 성격만 다르게 고른다.
        val trade = Cargo.demandTons(s, "newyork", "london")
        val leisure = Cargo.demandTons(s, "newyork", "miami")
        assertTrue(
            trade > leisure,
            "무역로(뉴욕–런던 ${trade.toInt()}t)가 관광로(뉴욕–마이애미 ${leisure.toInt()}t)보다 적다",
        )
    }

    /**
     * 손님 짐이 먼저 들어간다. 만석 노선은 실을 자리가 없다 — 이게 "여객은 잘되는데
     * 화물이 안 되는" 노선을 만들고, 화물을 노리면 여유 있게 굴리라는 압력이 된다.
     */
    @Test
    fun `많이 태울수록 실을 자리가 준다`() {
        val planes = listOf(Plane(1, "b747_100", "x", 0))
        val dist = Geo.distance("seoul", "newyork")
        val seats = Economics.quarterlySeats(3, Economics.capacity(planes, dist).avgSeats)
        val empty = Cargo.capacityTons(planes, 3, dist, seats * 0.2)
        val full = Cargo.capacityTons(planes, 3, dist, seats)
        assertTrue(full < empty, "손님이 늘어도 화물 여력이 그대로다 ($empty → $full)")
        assertTrue(full > 0.0, "만석이라고 화물 여력이 0 이 되면 안 된다")
    }

    /**
     * 짐을 미는 것은 사람 수지 탑승률이 아니다. 비즈니스 객실을 크게 깔면 총 좌석이
     * 줄어 같은 인원에도 탑승률이 오르는데, 벨리 용적은 기체 크기가 정하니 그대로다.
     * 객실 배치만 다르다는 이유로 화물 여력이 달라지면 안 된다.
     */
    @Test
    fun `객실 배치는 화물 여력을 바꾸지 않는다`() {
        val planes = listOf(Plane(1, "b747_100", "x", 0))
        val dist = Geo.distance("seoul", "newyork")
        val pax = 20_000.0
        assertEquals(
            Cargo.capacityTons(planes, 3, dist, pax),
            Cargo.capacityTons(planes, 3, dist, pax),
            "같은 인원인데 화물 여력이 달라졌다",
        )
        // 사람이 적으면 여력이 늘어야 한다 — 앞자리를 크게 깔면 태우는 인원이 준다.
        assertTrue(
            Cargo.capacityTons(planes, 3, dist, pax * 0.6) > Cargo.capacityTons(planes, 3, dist, pax),
            "손님이 줄었는데 화물 여력이 늘지 않는다",
        )
    }

    /** 초음속기는 동체가 가늘어 화물을 거의 못 싣는다 — 좌석 수만 보면 놓친다. */
    @Test
    fun `콩코드는 좌석 수에 비해 화물을 못 싣는다`() {
        val concorde = Cargo.bellyTons(AircraftCatalog["concorde"])
        val dc9 = Cargo.bellyTons(AircraftCatalog["dc9"])
        assertTrue(concorde < dc9, "콩코드($concorde)가 DC-9($dc9)보다 많이 싣는다")
    }

    /**
     * 그 구간의 화물은 정해져 있다. 여러 회사가 붙으면 나눠 갖지, 각자 제 몫을
     * 통째로 가져가면 안 된다.
     */
    @Test
    fun `같은 구간의 화물은 나눠 갖는다`() {
        val base = NewGame.create(seed = 7, companyId = "hanseong")
        val rival = base.airlines.first { it.id != "hanseong" }.id
        val p1 = base.nextId
        val r1 = p1 + 1
        val p2 = r1 + 1
        val r2 = p2 + 1
        val from = "seoul"
        val to = "newyork"
        val s = base.copy(
            routes = listOf(
                Route(r1, "hanseong", from, to, freq = 3, planeIds = listOf(p1)),
                Route(r2, rival, from, to, freq = 3, planeIds = listOf(p2)),
            ),
            planes = listOf(
                Plane(p1, "b747_100", "hanseong", 8, routeId = r1),
                Plane(p2, "b747_100", rival, 8, routeId = r2),
            ),
            nextId = r2 + 1,
            airlines = base.airlines.map { a ->
                if (a.id == "hanseong" || a.id == rival) {
                    a.copy(slots = a.slots + (from to 40) + (to to 40))
                } else {
                    a
                }
            },
        )
        val cargo = Cargo.resolveAll(s, Market.resolveAll(s))
        val mine = cargo[r1] ?: 0.0
        val theirs = cargo[r2] ?: 0.0
        assertTrue(mine > 0.0 && theirs > 0.0, "둘 중 하나가 화물을 하나도 못 실었다")
        // 같은 기재·같은 편수면 여력이 같으니 반씩 나눠야 한다.
        assertTrue(abs(mine - theirs) < mine * 0.05, "여력이 같은데 화물이 한쪽으로 쏠렸다 ($mine vs $theirs)")

        // 둘이 합쳐도 그 구간의 수요를 넘지 못한다. "혼자일 때보다 적게"로 재면 안 된다 —
        // 경쟁자가 붙으면 내 탑승률이 떨어져 **벨리가 더 비므로** 여력 자체가 늘어난다.
        val ceiling = Cargo.demandTons(s, from, to) *
            Cargo.revenuePerTon(Geo.distance(from, to), s.world.inflation)
        assertTrue(mine + theirs <= ceiling * 1.001, "둘이 합쳐 수요($ceiling)보다 많이 실었다 (${mine + theirs})")
    }

    /** 수요보다 자리가 많으면 남는다 — 큰 기체를 붙인다고 없는 화물이 생기지는 않는다. */
    @Test
    fun `수요를 넘겨 싣지는 않는다`() {
        val s = NewGame.create(seed = 7)
        val a = Cities["seoul"]
        val b = Cities["osaka"]
        val huge = mapOf(1 to 1e9)
        val revenue = Cargo.allocate(s, a, b, huge)[1] ?: 0.0
        val ceiling = Cargo.demandTons(s, a.id, b.id) *
            Cargo.revenuePerTon(Geo.distance(a.id, b.id), s.world.inflation)
        assertTrue(revenue <= ceiling * 1.001, "수요($ceiling)보다 많이 실었다 ($revenue)")
    }
}
