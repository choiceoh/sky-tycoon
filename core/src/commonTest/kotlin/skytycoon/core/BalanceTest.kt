package skytycoon.core

import skytycoon.core.data.AircraftCatalog
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
 * 게임의 재미는 "노선 하나가 얼마나 벌어야 하는가"에 달려 있다.
 * 독점 노선은 확실히 남아야 확장할 맛이 나고, 경쟁이 붙으면 마진이 깎여야 긴장이 생긴다.
 */
class BalanceTest {

    private fun blankWorld(): GameState {
        val base = NewGame.create(seed = 7)
        return base.copy(
            routes = emptyList(),
            planes = base.planes.map { it.copy(routeId = null) },
        )
    }

    /** 지정한 항공사들이 같은 구간에 각각 노선을 하나씩 연 상태를 만든다. */
    private fun withCarriers(
        from: String,
        to: String,
        typeId: String,
        freq: Int,
        fareMul: Double,
        airlineIds: List<String>,
    ): GameState {
        var s = blankWorld()
        var nextId = s.nextId
        val planes = mutableListOf<Plane>()
        val routes = mutableListOf<Route>()
        for (id in airlineIds) {
            val planeId = nextId++
            val routeId = nextId++
            planes += Plane(planeId, typeId, id, ageQuarters = 8, routeId = routeId)
            routes += Route(
                id = routeId,
                airlineId = id,
                from = from,
                to = to,
                fareMul = fareMul,
                freq = freq,
                planeIds = listOf(planeId),
            )
        }
        // 슬롯도 맞춰준다 (밸런스 측정이 목적이라 슬롯 제약은 논외).
        s = s.copy(
            planes = planes,
            routes = routes,
            nextId = nextId,
            airlines = s.airlines.map { a ->
                if (a.id in airlineIds) {
                    a.copy(slots = a.slots + (from to 40) + (to to 40))
                } else {
                    a
                }
            },
        )
        return s
    }

    private data class Economy(val revenue: Double, val cost: Double, val pax: Double, val lf: Double) {
        val margin: Double get() = if (revenue <= 0) 0.0 else (revenue - cost) / revenue
    }

    private fun measure(state: GameState, routeId: Int): Economy {
        val route = state.route(routeId)
        val airline = state.airline(route.airlineId)
        val a = Cities[route.from]
        val b = Cities[route.to]
        val sameRoutes = state.routes.filter { Geo.pairKey(it.from, it.to) == Geo.pairKey(a.id, b.id) }
        val outcome = Market.resolvePair(state, a, b, sameRoutes).first { it.routeId == routeId }
        val planes = state.planes.filter { it.routeId == routeId }

        val cargo = outcome.revenue * Balance.CARGO_RATE
        val gross = outcome.revenue + cargo
        val direct = Economics.routeCost(state, airline, route, planes, outcome.pax, gross).total
        val ownership = Economics.depreciation(planes)
        val overheadShare = planes.size * Balance.OVERHEAD_PER_AIRCRAFT * state.world.inflation +
            Balance.OVERHEAD_PER_ROUTE * state.world.inflation
        // 결산(TurnEngine.settle)이 노선에 물리는 것과 **같은** 슬롯 임차료를 여기서도
        // 물린다. 안 그러면 가드레일이 실제보다 후한 마진을 보고 통과시켜, 게임에서는
        // 적자인 노선을 "남는다"고 승인한다.
        val slotRent = route.freq * (
            Economics.slotRent(state, airline.id, route.from) +
                Economics.slotRent(state, airline.id, route.to)
            )
        return Economy(
            revenue = gross,
            cost = direct + ownership + overheadShare + slotRent,
            pax = outcome.pax,
            lf = outcome.seats.let { if (it <= 0) 0.0 else outcome.pax / it },
        )
    }

    /**
     * 독점 노선은 확실히 남아야 확장할 맛이 난다.
     *
     * 도시쌍 하나로 재지 않는다 — 로컬 항공사의 경쟁력이 도시쌍마다 종 모양으로
     * 흩어져 있어서(Balance.FRINGE_SIGMA), 특정 노선 하나의 수치는 그 노선이 어떤
     * 값을 뽑았는지를 잴 뿐 밸런스를 재지 못한다. 간선 여럿의 **중앙값**을 본다.
     */
    @Test
    fun `독점 노선은 확실히 남는다`() {
        // 거리대를 맞춘다 — 1,000km 아래 단거리는 착륙료·회항 고정비가 운임을 압도해
        // 성격이 아예 다르다 (`단거리 셔틀은 편수로 버틴다` 참고).
        val trunks = listOf(
            Triple("seoul", "tokyo", "b727"),
            Triple("newyork", "chicago", "b727"),
            Triple("tokyo", "hongkong", "b727"),
            Triple("paris", "rome", "b727"),
            Triple("losangeles", "chicago", "b727"),
        )
        val measured = trunks.map { (a, b, type) ->
            val s = withCarriers(a, b, type, freq = 14, fareMul = 1.0, airlineIds = listOf("hanseong"))
            Triple("$a-$b", measure(s, s.routes.first().id), 0)
        }
        for ((name, e, _) in measured) {
            println("[독점 $name] 탑승률 ${(e.lf * 100).toInt()}%, 마진 ${(e.margin * 100).toInt()}%")
        }
        val lfs = measured.map { it.second.lf }.sorted()
        val margins = measured.map { it.second.margin }.sorted()
        val medLf = lfs[lfs.size / 2]
        val medMargin = margins[margins.size / 2]
        println("[독점 중앙값] 탑승률 ${(medLf * 100).toInt()}%, 마진 ${(medMargin * 100).toInt()}%")

        assertTrue(medLf > 0.7, "독점 간선의 탑승률 중앙값이 $medLf 밖에 안 된다")
        assertTrue(medMargin in 0.12..0.55, "독점 노선 마진 중앙값 $medMargin 이 기대 범위를 벗어났다")
        // 최악의 노선도 구조적 적자는 아니어야 한다 (어려운 시장 ≠ 못 하는 시장).
        assertTrue(margins.first() > 0.0, "가장 빡빡한 독점 노선이 적자다 (${margins.first()})")
    }

    /**
     * 800km 아래 단거리도 **편수를 맞추면** 먹고살 수 있어야 한다.
     *
     * 예전에는 운임의 고정 몫([Balance.FARE_BASE])이 7.0 뿐이라 거리에 거의 비례해서만
     * 값을 받았고, 착륙료·회항 같은 편당 고정비를 못 건져 **어떤 편수로도 흑자가 나지
     * 않았다** (런던–파리 최선 마진 -30%). 거리대 하나가 통째로 죽은 콘텐츠였다.
     *
     * 대신 공짜는 아니다 — 좌석을 많이 깔수록 로컬에 밀려 탑승률이 떨어지므로,
     * 단거리는 편수를 욕심내면 바로 적자로 넘어간다. 그 균형점을 찾는 게 판단거리다.
     */
    @Test
    fun `단거리 셔틀은 편수로 버틴다`() {
        for ((a, b) in listOf("london" to "paris", "frankfurt" to "london")) {
            val byFreq = (2..26 step 4).mapNotNull { f ->
                val s = withCarriers(a, b, "b727", freq = f, fareMul = 1.0, airlineIds = listOf("hanseong"))
                val plane = s.planes.first()
                val cap = Economics.capacity(listOf(plane), Geo.distance(a, b))
                if (f > cap.maxFreq) null else f to measure(s, s.routes.first().id)
            }
            val best = byFreq.maxByOrNull { it.second.margin }!!
            val worst = byFreq.maxByOrNull { it.first }!!
            println(
                "[단거리 $a-$b] 최선 주${best.first}왕복 마진 ${(best.second.margin * 100).toInt()}% " +
                    "· 최다 주${worst.first}왕복 마진 ${(worst.second.margin * 100).toInt()}%",
            )
            assertTrue(
                best.second.margin > 0.10,
                "$a-$b 는 편수를 어떻게 잡아도 남지 않는다 (최선 ${best.second.margin})",
            )
            assertTrue(
                worst.second.margin < best.second.margin,
                "$a-$b 에서 편수를 최대로 밀어도 손해가 없으면 좌석 조절이 판단거리가 아니다",
            )
        }
    }

    /**
     * 로컬 항공사의 경쟁력이 도시쌍마다 갈려야 **어디를 뚫을지가 판단거리**가 된다.
     * 값 하나로 고정하면 같은 조건의 노선이 전부 똑같이 채워져 시장을 살펴볼 이유가 없다.
     */
    @Test
    fun `노선마다 로컬 경쟁 강도가 다르다`() {
        val pairs = listOf(
            "seoul" to "tokyo", "seoul" to "beijing", "tokyo" to "hongkong",
            "london" to "paris", "paris" to "rome", "newyork" to "chicago",
            "frankfurt" to "london", "losangeles" to "chicago",
        )
        // 수요 대비 좌석을 같은 비율로 맞춰 놓고 재야 로컬 경쟁력만 비교된다.
        val shares = pairs.map { (a, b) ->
            val s = withCarriers(a, b, "b727", freq = 14, fareMul = 1.0, airlineIds = listOf("hanseong"))
            val route = s.routes.first()
            val outcome = Market.resolvePair(s, Cities[a], Cities[b], s.routes).first { it.routeId == route.id }
            "$a-$b" to outcome.share
        }
        for ((name, sh) in shares) println("[로컬경쟁] $name 메이저 점유 ${(sh * 100).toInt()}%")
        val lo = shares.minOf { it.second }
        val hi = shares.maxOf { it.second }
        assertTrue(
            hi > lo * 1.4,
            "노선별 로컬 경쟁 강도가 사실상 같다 (점유율 $lo ~ $hi) — 시장을 고를 이유가 없어진다",
        )

        // 그리고 그 차이를 **취항 전에 볼 수 있어야** 한다. 숨겨 두면 편차는 시장을 고르는
        // 재미가 아니라 돈을 쓰고 나서야 알게 되는 함정이 된다 (노선 계획 화면과
        // Ai.attractiveness 가 이 값을 쓴다).
        val labels = Cities.all.flatMap { a ->
            Cities.all.mapNotNull { b -> if (a.id < b.id) Market.localStrengthLabel(a, b) else null }
        }
        val kinds = labels.toSet()
        println("[로컬경쟁 표시] ${kinds.sorted()} · 분포 ${kinds.associateWith { k -> labels.count { it == k } }}")
        assertTrue(
            kinds.size == 3,
            "로컬 경쟁 강도가 세 단계로 갈리지 않는다 ($kinds) — 표시해도 판단에 쓸 수 없다",
        )
    }

    @Test
    fun `경쟁이 붙으면 마진이 깎인다`() {
        val mono = withCarriers("seoul", "tokyo", "b727", 14, 1.0, listOf("hanseong"))
        val duo = withCarriers("seoul", "tokyo", "b727", 14, 1.0, listOf("hanseong", "fuji", "britannia"))
        val monoE = measure(mono, mono.routes.first().id)
        val duoE = measure(duo, duo.routes.first { it.airlineId == "hanseong" }.id)
        println(
            "[경쟁 3사] 탑승률 ${(duoE.lf * 100).toInt()}%, 마진 ${(duoE.margin * 100).toInt()}% " +
                "(독점 대비 ${((duoE.margin - monoE.margin) * 100).toInt()}%p)",
        )
        assertTrue(duoE.margin < monoE.margin, "경쟁이 붙었는데 마진이 줄지 않았다")
        assertTrue(duoE.pax < monoE.pax, "경쟁이 붙었는데 승객이 줄지 않았다")
        assertTrue(duoE.lf < 0.95, "경쟁 노선인데 여전히 만석이면 운임 경쟁이 성립하지 않는다")
    }

    /**
     * 같은 구간에 같은 기재를 같은 운임·편수로 띄워도 **경쟁력이 다르면 탑승률이 갈려야**
     * 한다. 다 같이 낮은 탑승률로 사이좋게 적자를 보면 네트워크를 지어 둔 보람이 없다.
     *
     * 여기서 셋을 가르는 것은 연고와 환승편이다 — 한성은 서울이 홈이고 인천발 스포크를
     * 세 개 더 갖고 있고, 후지는 도쿄가 홈이며, 브리타니아는 런던 기반이라 이 구간에
     * 아무 연고가 없다.
     */
    @Test
    fun `경쟁력이 높은 회사가 더 잘 채운다`() {
        var s = withCarriers("seoul", "tokyo", "b727", 14, 1.0, listOf("hanseong", "fuji", "britannia"))
        // 한성에만 서울발 스포크를 붙여 준다 (환승편 = 경쟁력).
        var nextId = s.nextId
        val extraPlanes = mutableListOf<Plane>()
        val extraRoutes = mutableListOf<Route>()
        for (spoke in listOf("beijing", "hongkong", "taipei")) {
            val planeId = nextId++
            val routeId = nextId++
            extraPlanes += Plane(planeId, "b727", "hanseong", ageQuarters = 8, routeId = routeId)
            extraRoutes += Route(routeId, "hanseong", "seoul", spoke, freq = 7, planeIds = listOf(planeId))
        }
        s = s.copy(
            planes = s.planes + extraPlanes,
            routes = s.routes + extraRoutes,
            nextId = nextId,
        )

        val lf = listOf("hanseong", "fuji", "britannia").associateWith { id ->
            measure(s, s.routes.first { it.airlineId == id && it.touches("tokyo") }.id).lf
        }
        println(
            "[경쟁력] 한성(홈+환승 3) ${(lf.getValue("hanseong") * 100).toInt()}% · " +
                "후지(홈) ${(lf.getValue("fuji") * 100).toInt()}% · " +
                "브리타니아(연고 없음) ${(lf.getValue("britannia") * 100).toInt()}%",
        )
        assertTrue(
            lf.getValue("hanseong") > lf.getValue("fuji"),
            "환승편을 더 가진 쪽이 더 채워야 한다 (한성 ${lf["hanseong"]} vs 후지 ${lf["fuji"]})",
        )
        assertTrue(
            lf.getValue("fuji") > lf.getValue("britannia") * 1.3,
            "연고가 있는 쪽이 확실히 더 채워야 한다 (후지 ${lf["fuji"]} vs 브리타니아 ${lf["britannia"]})",
        )
    }

    /**
     * 좌석이 모자란 독점 노선에서는 값을 올려도 승객이 그대로다(어차피 만석).
     * 운임이 의미를 갖는 건 경쟁이 붙어 좌석에 여유가 생겼을 때다.
     */
    @Test
    fun `경쟁 시장에서 운임을 올리면 승객을 뺏긴다`() {
        fun paxAt(fare: Double): Double {
            var s = withCarriers("seoul", "tokyo", "b727", 14, 1.0, listOf("hanseong", "fuji", "britannia"))
            val mine = s.routes.first { it.airlineId == "hanseong" }
            s = s.copy(routes = s.routes.map { if (it.id == mine.id) it.copy(fareMul = fare) else it })
            return measure(s, mine.id).pax
        }
        val cheap = paxAt(0.75)
        val dear = paxAt(1.50)
        println("[운임] 0.75배 → ${cheap.toInt()}명 / 1.50배 → ${dear.toInt()}명")
        assertTrue(cheap > dear * 1.2, "운임을 두 배 가까이 올렸는데 승객이 거의 안 줄었다")
    }

    @Test
    fun `장거리 대형기 노선도 채산이 맞는다`() {
        val s = withCarriers("tokyo", "losangeles", "b747_100", freq = 7, fareMul = 1.0, listOf("fuji"))
        val e = measure(s, s.routes.first().id)
        println(
            "[도쿄-LA 747] 승객 ${e.pax.toInt()}명, 탑승률 ${(e.lf * 100).toInt()}%, " +
                "마진 ${(e.margin * 100).toInt()}%",
        )
        assertTrue(e.margin > -0.1, "장거리 간판 노선이 구조적 적자면 곤란하다 (마진 ${e.margin})")
    }

    /**
     * 광동체 장거리는 **로컬 수요만으로는 못 채운다** — 도쿄–LA 의 분기 수요는 747 한 대를
     * 최대로 굴렸을 때 내놓는 좌석보다 작다. 그 자리를 메우는 것이 허브 연결 수요이고,
     * 그래서 "허브에 무엇을 붙였는가"가 장거리 채산을 정한다.
     *
     * 동시에 **연결 수요가 스포크 하나에 몰리면 안 된다**. 예전에는 도시쌍을 정렬 순서대로
     * 하나씩 끝까지 태워서, 도쿄–LA 의 빈자리를 사전순으로 앞선 베이징발이 통째로 가져가고
     * 서울·홍콩발은 한 명도 못 탔다 — 정렬 순서가 곧 허브 경제였다.
     */
    @Test
    fun `허브 연결이 장거리 광동체를 채우고 스포크에 고루 퍼진다`() {
        var s = blankWorld()
        var nextId = s.nextId
        val planes = mutableListOf<Plane>()
        val routes = mutableListOf<Route>()
        val slots = HashMap<String, Int>()

        fun add(from: String, to: String, type: String): Int {
            val planeId = nextId++
            val routeId = nextId++
            val cap = Economics.capacity(listOf(Plane(planeId, type, "fuji", 8)), Geo.distance(from, to))
            planes += Plane(planeId, type, "fuji", ageQuarters = 8, routeId = routeId)
            routes += Route(routeId, "fuji", from, to, freq = cap.maxFreq, planeIds = listOf(planeId))
            slots[from] = (slots[from] ?: 0) + 40
            slots[to] = (slots[to] ?: 0) + 40
            return routeId
        }

        val longHaul = add("tokyo", "losangeles", "b747_100")
        val spokes = listOf("seoul", "beijing", "hongkong", "taipei").map { add("tokyo", it, "b727") }

        s = s.copy(
            planes = planes,
            routes = routes,
            nextId = nextId,
            airlines = s.airlines.map { a -> if (a.id == "fuji") a.copy(slots = a.slots + slots) else a },
        )

        val all = Market.resolveAll(s)
        val lh = all.getValue(longHaul)
        val lf = lh.pax / lh.seats
        val connects = spokes.map { all.getValue(it).connectPax }
        println(
            "[허브] 도쿄-LA 로컬 ${lh.localPax.toInt()} + 환승 ${lh.connectPax.toInt()} → LF ${(lf * 100).toInt()}%" +
                " · 스포크 환승 ${connects.map { it.toInt() }}",
        )

        val solo = withCarriers("tokyo", "losangeles", "b747_100", freq = 7, fareMul = 1.0, listOf("fuji"))
        val soloLf = measure(solo, solo.routes.first().id).lf
        assertTrue(lf > soloLf * 1.3, "허브를 붙였는데 장거리 탑승률이 그대로다 ($soloLf → $lf)")
        assertTrue(
            connects.all { it > 0.0 },
            "스포크 중 환승을 한 명도 못 받은 곳이 있다 — 배분이 순서에 좌우된다 ($connects)",
        )
        assertTrue(
            connects.max() < connects.min() * 4.0,
            "환승이 스포크 하나에 쏠렸다 ($connects)",
        )
    }

    @Test
    fun `기재 한 대의 주간 편수 상한이 상식적이다`() {
        val b727 = AircraftCatalog["b727"]
        val short = Economics.capacity(listOf(Plane(1, "b727", "x", 0)), Geo.distance("seoul", "tokyo"))
        val long = Economics.capacity(listOf(Plane(1, "b747_100", "x", 0)), Geo.distance("tokyo", "losangeles"))
        println("[가동] ${b727.name} 서울-도쿄 주 ${short.maxFreq}왕복 / B747 도쿄-LA 주 ${long.maxFreq}왕복")
        assertTrue(short.maxFreq in 12..30, "단거리 편수 상한 ${short.maxFreq} 이 이상하다")
        assertTrue(long.maxFreq in 2..6, "장거리 편수 상한 ${long.maxFreq} 이 이상하다")
    }
}
