package skytycoon.core.sim

import skytycoon.core.data.AircraftCatalog
import skytycoon.core.data.Cities
import skytycoon.core.model.City
import skytycoon.core.model.GameState
import skytycoon.core.model.Route
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * 한 노선이 이번 분기에 실제로 실어 나른 결과.
 *
 * [bizPax]·[leiPax] 는 이 구간이 목적지인 **로컬 승객**이고, [connectPax] 는 이 노선을
 * 중간 구간으로 쓴 **환승 승객**이다. 나눠 두면 "이 노선이 스스로 버는가, 허브 연결이
 * 먹여 살리는가"를 읽을 수 있다 — 허브 전략의 손익이 그 구분에 달려 있다.
 */
data class RouteOutcome(
    val routeId: Int,
    val bizPax: Double,
    val leiPax: Double,
    val seats: Double,
    val fare: Double,
    val localRevenue: Double,
    val share: Double,
    val connectPax: Double = 0.0,
    val connectRevenue: Double = 0.0,
) {
    val localPax: Double get() = bizPax + leiPax
    val pax: Double get() = localPax + connectPax
    val revenue: Double get() = localRevenue + connectRevenue
}

private class Offer(
    val route: Route,
    val fare: Double,
    val seats: Double,
    val bizUtil: Double,
    val leiUtil: Double,
) {
    var remaining: Double = seats
    var biz: Double = 0.0
    var lei: Double = 0.0
}

object Market {
    /**
     * 도시쌍 하나의 승객 배분.
     *
     * 비즈니스 승객이 먼저 좌석을 가져가고(수익 관리), 남은 좌석을 레저 승객이 채운다.
     * 각 세그먼트 안에서는 다항 로짓으로 점유율이 갈리며, 좌석이 모자라 넘친 수요는
     * 여유가 있는 다른 항공사로 몇 차례에 걸쳐 흘러간다.
     */
    fun resolvePair(state: GameState, a: City, b: City, routes: List<Route>): List<RouteOutcome> {
        if (routes.isEmpty()) return emptyList()
        // 공항이 닫히면 비행기가 안 뜬다. 빈 결과를 돌려줘야 결산에서 연료·승무원·착륙료가
        // 청구되지 않는다 (수요만 0 으로 만들면 원가는 그대로 나간다).
        if (state.cityState[a.id]?.isClosed(state.turn) == true ||
            state.cityState[b.id]?.isClosed(state.turn) == true
        ) {
            return emptyList()
        }
        val dist = Geo.distance(a.id, b.id)
        val standard = Economics.standardFare(dist, state.world.inflation)

        val offers = ArrayList<Offer>(routes.size)
        for (r in routes) {
            if (!r.active || r.freq <= 0 || r.planeIds.isEmpty()) continue
            val airline = state.airlineOrNull(r.airlineId) ?: continue
            if (!airline.alive) continue
            val planes = r.planeIds.mapNotNull { id -> state.planes.firstOrNull { it.id == id } }
            if (planes.isEmpty()) continue
            val cap = Economics.capacity(planes, dist)
            if (!cap.usable) continue
            val freq = r.freq.coerceAtMost(cap.maxFreq)
            if (freq <= 0) continue

            val seats = Economics.quarterlySeats(freq, cap.avgSeats)
            val fare = standard * r.fareMul
            val fareRatio = (fare / standard).coerceAtLeast(0.05)

            val brand = (airline.brandIn(a.region) + airline.brandIn(b.region)) / 2.0
            // 확장으로 늘어난 슬롯까지 분모에 넣는다. 안 그러면 확장된 공항에서
            // 점유율이 1.0 을 넘어 있지도 않은 지배력 보너스가 붙는다.
            val capacityA = state.totalSlots(a.id).coerceAtLeast(1)
            val capacityB = state.totalSlots(b.id).coerceAtLeast(1)
            val hub = (
                airline.slotsAt(a.id).toDouble() / capacityA +
                    airline.slotsAt(b.id).toDouble() / capacityB
                ) / 2.0
            val prestige = planes.sumOf { AircraftCatalog[it.typeId].prestige } / planes.size
            val bizFacilities = airline.businesses
                .filter { it.city == a.id || it.city == b.id }
                .sumOf { it.type.demandBoost }

            // 양 끝에서 이 회사가 **다른 어디로 더 갈 수 있는가**. 연결편이 많은 회사가
            // 선택받는다 — 갈아탈 곳이 있고 일정이 틀어져도 대안이 있기 때문이다.
            // 이게 없으면 노선 하나만 띄운 회사와 허브를 가진 회사가 똑같이 취급돼,
            // 같은 구간에 붙은 경쟁자들이 다 같이 낮은 탑승률로 사이좋게 적자를 본다.
            val feed = feedStrength(state, airline.id, a.id, r.id) +
                feedStrength(state, airline.id, b.id, r.id)

            // 기반 국가 프리미엄 — 자국 적을 단 항공사가 그 나라를 드나드는 손님에게
            // 갖는 인지도·영업망의 이점. 홈 공항이 끝점이면 온전히, 같은 권역이면 절반.
            val homeCity = Cities[airline.home]
            val homeEdge = when {
                airline.home == a.id || airline.home == b.id -> 1.0
                homeCity.region == a.region || homeCity.region == b.region -> 0.5
                else -> 0.0
            }

            val common = Balance.SHARE_BRAND_W * brand +
                Balance.SHARE_PRESTIGE_W * prestige +
                Balance.SHARE_HUB_W * hub +
                Balance.SHARE_SAFETY_W * (airline.safety - 1.0) +
                Balance.SHARE_FEED_W * feed +
                Balance.SHARE_HOME_W * homeEdge +
                bizFacilities

            val service = (airline.serviceLevel + r.serviceExtra).toDouble()
            val logFreq = ln(1.0 + freq.toDouble())
            val logFare = ln(fareRatio)

            offers += Offer(
                route = r,
                fare = fare,
                seats = seats,
                bizUtil = -Balance.BIZ_PRICE_SENS * logFare +
                    Balance.BIZ_SERVICE_W * service +
                    Balance.BIZ_FREQ_W * logFreq + common,
                leiUtil = -Balance.LEI_PRICE_SENS * logFare +
                    Balance.LEI_SERVICE_W * service +
                    Balance.LEI_FREQ_W * logFreq + common,
            )
        }
        if (offers.isEmpty()) return emptyList()

        val demand = Demand.quarterly(state, a, b)
        // 시장 평균 운임이 싸면 원래 안 움직였을 사람들까지 움직인다.
        val capacityTotal = offers.sumOf { it.seats }
        val weightedFareRatio = if (capacityTotal <= 0) {
            1.0
        } else {
            offers.sumOf { it.seats * (it.fare / standard) } / capacityTotal
        }
        val induced = weightedFareRatio.coerceIn(0.3, 3.0).pow(-Balance.INDUCED_ELASTICITY)

        allocate(offers, demand.business * induced) { it.bizUtil }
            .also { served -> offers.forEachIndexed { i, o -> o.biz = served[i] } }
        allocate(offers, demand.leisure * induced) { it.leiUtil }
            .also { served -> offers.forEachIndexed { i, o -> o.lei = served[i] } }

        val totalPax = offers.sumOf { it.biz + it.lei }
        return offers.map { o ->
            val revenue = o.biz * o.fare * Balance.BIZ_YIELD + o.lei * o.fare * Balance.LEI_YIELD
            RouteOutcome(
                routeId = o.route.id,
                bizPax = o.biz,
                leiPax = o.lei,
                seats = o.seats,
                fare = o.fare,
                localRevenue = revenue,
                share = if (totalPax <= 0) 0.0 else (o.biz + o.lei) / totalPax,
            )
        }
    }

    /**
     * 이 공항에서 그 항공사가 가진 **다른 취항지 수**를 로그로 눌러 돌려준다.
     * 이 노선 자신은 세지 않는다 — 자기 자신은 연결편이 아니다.
     */
    private fun feedStrength(state: GameState, airlineId: String, cityId: String, selfRouteId: Int): Double {
        var n = 0
        for (r in state.routes) {
            if (r.id == selfRouteId || r.airlineId != airlineId || !r.active || r.freq <= 0) continue
            if (r.touches(cityId)) n++
        }
        return ln(1.0 + n.toDouble())
    }

    /**
     * 로짓으로 수요를 나눈다. 후보에는 게임에 등장하는 항공사들뿐 아니라 **로컬 항공사**
     * ([Balance.FRINGE_UTIL]) 라는 바깥 선택지가 늘 함께 있다.
     *
     * 게임에 나오는 여덟 회사는 그 시대의 **주요 항공사**다. 나머지 수요가 비어 있는 게
     * 아니라, 모델에 없는 지역 항공사들이 낮은 경쟁력으로 실어 나르고 있다고 본다.
     * 이 가정이 있어야 수요를 실물 규모로 둬도 "내놓은 좌석은 무조건 팔린다"가 되지 않는다 —
     * 로컬보다 매력이 있어야 팔리고, 그래서 **탑승률이 곧 경쟁력의 함수**가 된다.
     * 운임을 올리면 손님이 로컬로 새고, 편수·서비스·환승편·브랜드가 좋으면 되찾아 온다.
     *
     * 좌석이 모자라 못 태운 몫만 다음 라운드로 넘긴다. 로컬을 택한 손님까지 다시 돌리면
     * 라운드를 거듭할수록 바깥 선택지가 무력해져(50% 가 93.75% 가 된다) 이 모델이 무너진다.
     */
    private fun allocate(offers: List<Offer>, demand: Double, utility: (Offer) -> Double): DoubleArray {
        val taken = DoubleArray(offers.size)
        if (demand <= 0.0) return taken
        var left = demand

        repeat(Balance.SPILL_ROUNDS + 1) {
            if (left <= 1e-6) return@repeat
            val active = offers.indices.filter { offers[it].remaining > 1e-6 }
            if (active.isEmpty()) return@repeat

            val utils = active.map { utility(offers[it]) }
            val maxU = maxOf(utils.max(), Balance.FRINGE_UTIL)
            val weights = utils.map { exp(it - maxU) }
            val fringe = exp(Balance.FRINGE_UTIL - maxU)
            val denom = weights.sum() + fringe
            if (denom <= 0.0) return@repeat

            var spilled = 0.0
            for ((k, idx) in active.withIndex()) {
                val want = left * (weights[k] / denom)
                val o = offers[idx]
                val give = minOf(want, o.remaining)
                o.remaining -= give
                taken[idx] += give
                // 좌석이 없어 흘린 몫만 다시 돌린다. 로컬을 택한 손님은 이미 떠났다.
                spilled += want - give
            }
            if (spilled <= 1e-9) return@repeat
            left = spilled
        }
        return taken
    }

    /**
     * 도시쌍 단위로 모든 항공사의 노선을 모아 한 번에 푼다.
     *
     * 두 단계다. 먼저 **직항 시장**을 도시쌍마다 풀고(1단계), 그다음 남은 좌석을
     * **허브 경유 환승 승객**이 채운다(2단계). 실제 허브 운영이 그 순서로 돌아간다 —
     * 로컬 수요로 채우지 못한 좌석을 연결 수요로 메워 기재를 띄울 만하게 만드는 것.
     *
     * 이 2단계가 노선의 가치를 **네트워크에 의존하게** 만든다. 스포크 하나는 로컬 수요만
     * 보면 적자여도, 허브에서 뻗은 다른 노선에 승객을 물어다 주면 살아난다. 그래서
     * "어디에 허브를 세우고 무엇을 붙일 것인가"가 비로소 판단거리가 된다.
     */
    fun resolveAll(state: GameState): Map<Int, RouteOutcome> {
        val byPair = HashMap<String, MutableList<Route>>()
        for (r in state.routes) {
            if (!r.active || r.freq <= 0 || r.planeIds.isEmpty()) continue
            byPair.getOrPut(Geo.pairKey(r.from, r.to)) { mutableListOf() }.add(r)
        }
        val out = HashMap<Int, RouteOutcome>()
        val localPaxByPair = HashMap<String, Double>()
        for ((key, routes) in byPair) {
            val (idA, idB) = key.split("|")
            for (outcome in resolvePair(state, Cities[idA], Cities[idB], routes)) {
                out[outcome.routeId] = outcome
                localPaxByPair[key] = (localPaxByPair[key] ?: 0.0) + outcome.localPax
            }
        }
        return resolveConnections(state, out, localPaxByPair)
    }

    /** 환승 후보 하나 — 한 항공사가 허브 하나를 거쳐 A→C 를 잇는 여정. */
    private class ConnectOffer(
        val legA: RouteOutcome,
        val legB: RouteOutcome,
        val hubId: String,
        val fare: Double,
        val distA: Double,
        val distB: Double,
        val util: Double,
        val spare: Double,
    ) {
        var pax: Double = 0.0
    }

    /**
     * 2단계 — 직항이 채우지 못한 수요를 허브 경유 여정이 가져간다.
     *
     * 좌석은 **양쪽 구간에서 동시에** 소비되므로 병목은 둘 중 여유가 적은 쪽이다.
     * 우회가 심하면 승객이 타지 않으므로 [Balance.CONNECT_MAX_DETOUR] 로 잘라낸다.
     */
    private fun resolveConnections(
        state: GameState,
        base: Map<Int, RouteOutcome>,
        localPaxByPair: Map<String, Double>,
    ): Map<Int, RouteOutcome> {
        // 노선별 남은 좌석. 환승 승객이 여기서만 태워진다.
        val spare = HashMap<Int, Double>()
        // 항공사 → 도시 → 그 도시에서 뻗은 (상대 도시, 노선) 목록.
        val links = HashMap<String, HashMap<String, MutableList<Pair<String, Route>>>>()
        for (r in state.routes) {
            val o = base[r.id] ?: continue
            val left = o.seats - o.localPax
            if (left <= 1.0) continue
            spare[r.id] = left
            val perAirline = links.getOrPut(r.airlineId) { HashMap() }
            perAirline.getOrPut(r.from) { mutableListOf() } += r.to to r
            perAirline.getOrPut(r.to) { mutableListOf() } += r.from to r
        }
        if (spare.isEmpty()) return base

        // 도시쌍별 후보 모으기. 허브에서 뻗은 노선 쌍만 보면 되므로 전 도시쌍을 훑지 않는다.
        val candidates = HashMap<String, MutableList<ConnectOffer>>()
        for ((airlineId, byCity) in links) {
            val airline = state.airlineOrNull(airlineId) ?: continue
            if (!airline.alive) continue
            for ((hubId, spokes) in byCity) {
                if (spokes.size < 2) continue
                for (i in spokes.indices) {
                    for (j in i + 1 until spokes.size) {
                        val (aId, ra) = spokes[i]
                        val (cId, rc) = spokes[j]
                        if (aId == cId) continue
                        // 같은 항공사가 A–C 직항을 갖고 있어도 후보로 둔다. 직항은 1단계에서
                        // 이미 로컬 수요를 가져갔고, 여기서 채우는 것은 그러고도 남은 좌석이다.
                        val pairKey = Geo.pairKey(aId, cId)
                        val oa = base[ra.id] ?: continue
                        val ob = base[rc.id] ?: continue
                        val sa = spare[ra.id] ?: continue
                        val sb = spare[rc.id] ?: continue

                        val direct = Geo.distance(aId, cId)
                        if (direct < 1.0) continue
                        val da = Geo.distance(aId, hubId)
                        val db = Geo.distance(hubId, cId)
                        if (da + db > direct * Balance.CONNECT_MAX_DETOUR) continue

                        val fare = Economics.standardFare(direct, state.world.inflation) *
                            ((ra.fareMul + rc.fareMul) / 2.0) * Balance.CONNECT_FARE_MUL
                        val util = connectUtility(state, airline, ra, rc, aId, cId, hubId, fare, direct)
                        candidates.getOrPut(pairKey) { mutableListOf() } += ConnectOffer(
                            legA = oa,
                            legB = ob,
                            hubId = hubId,
                            fare = fare,
                            distA = da,
                            distB = db,
                            util = util,
                            spare = minOf(sa, sb),
                        )
                    }
                }
            }
        }
        if (candidates.isEmpty()) return base

        // 노선별로 환승 승객·수입을 누적한다.
        val addPax = HashMap<Int, Double>()
        val addRev = HashMap<Int, Double>()

        // 구간별 남은 좌석을 **소비해 가며** 배분한다. 여정 하나는 두 구간의 좌석을
        // 동시에 먹으므로, 나중에 노선별로 따로 자르면 한쪽만 깎여 "한 승객이 두 구간을
        // 쓴다"는 전제가 깨진다 (A 구간은 80% 로 줄고 B 구간은 그대로 남는 식).
        val spareLeft = HashMap(spare)
        // 도시쌍 순서가 배분에 영향을 주므로 키로 정렬해 결정론을 지킨다 —
        // 같은 시드가 같은 전개를 재현해야 한다.
        for (pairKey in candidates.keys.sorted()) {
            val offers = candidates.getValue(pairKey)
            val (aId, cId) = pairKey.split("|")
            val demand = Demand.quarterly(state, Cities[aId], Cities[cId]).total
            if (demand <= 0.0) continue
            // 직항이 이미 태운 몫은 빠진다. 환승은 남은 수요를 줍는 것이다.
            val unmet = (demand - (localPaxByPair[pairKey] ?: 0.0)).coerceAtLeast(0.0)
            if (unmet <= 1.0) continue

            // 로짓에 **"환승하지 않는다"는 선택지**를 함께 넣는다. 환승 후보끼리만
            // 정규화하면 CONNECT_PENALTY 가 모든 항에서 똑같이 빠져 그대로 상쇄되고,
            // 후보가 하나뿐이면 아무리 비싸고 불편해도 남은 수요를 통째로 가져간다.
            val maxU = maxOf(offers.maxOf { it.util }, Balance.CONNECT_OUTSIDE_UTIL)
            val weights = offers.map { exp(it.util - maxU) }
            val outside = exp(Balance.CONNECT_OUTSIDE_UTIL - maxU)
            val denom = weights.sum() + outside
            if (denom <= 0.0) continue

            // 좌석이 모자라 못 태운 몫은 여유 있는 다른 여정으로 흘려보낸다 (직항 시장과
            // 같은 스필). 이게 없으면 인기 있는 여정이 배정만 받고 못 태운 승객이 그대로
            // 증발해, 옆에 빈 좌석이 남아도 허브 수송량이 실제보다 적게 잡힌다.
            var left = unmet
            repeat(Balance.SPILL_ROUNDS + 1) {
                if (left <= 1.0) return@repeat
                val open = offers.indices.filter { i ->
                    minOf(
                        spareLeft[offers[i].legA.routeId] ?: 0.0,
                        spareLeft[offers[i].legB.routeId] ?: 0.0,
                    ) > 1e-6
                }
                if (open.isEmpty()) return@repeat
                // 남은 후보만으로 다시 정규화한다. 바깥 선택지(환승 안 함)는 계속 겨룬다.
                val openDenom = open.sumOf { weights[it] } + outside
                if (openDenom <= 0.0) return@repeat
                var served = 0.0
                for (i in open) {
                    val o = offers[i]
                    val want = left * (weights[i] / openDenom)
                    val roomA = spareLeft[o.legA.routeId] ?: 0.0
                    val roomB = spareLeft[o.legB.routeId] ?: 0.0
                    val take = minOf(want, roomA, roomB)
                    if (take <= 0.0) continue
                    spareLeft[o.legA.routeId] = roomA - take
                    spareLeft[o.legB.routeId] = roomB - take
                    o.pax += take
                    served += take
                    accumulate(o, take, addPax, addRev)
                }
                if (served <= 1e-9) return@repeat
                left -= served
            }
        }
        if (addPax.isEmpty()) return base

        // 배분 단계에서 이미 좌석을 소비했으므로 사후 보정이 없다.
        return base.mapValues { (id, o) ->
            val p = addPax[id] ?: return@mapValues o
            o.copy(connectPax = p, connectRevenue = addRev[id] ?: 0.0)
        }
    }

    /** 환승 한 건이 실린 결과를 두 구간에 나눠 적는다. */
    private fun accumulate(
        o: ConnectOffer,
        take: Double,
        addPax: HashMap<Int, Double>,
        addRev: HashMap<Int, Double>,
    ) {
        // 수입은 구간 거리로 나눈다 — 긴 구간이 더 가져간다.
        val total = (o.distA + o.distB).coerceAtLeast(1.0)
        val revenue = take * o.fare * Balance.CONNECT_YIELD
        addPax[o.legA.routeId] = (addPax[o.legA.routeId] ?: 0.0) + take
        addPax[o.legB.routeId] = (addPax[o.legB.routeId] ?: 0.0) + take
        addRev[o.legA.routeId] = (addRev[o.legA.routeId] ?: 0.0) + revenue * (o.distA / total)
        addRev[o.legB.routeId] = (addRev[o.legB.routeId] ?: 0.0) + revenue * (o.distB / total)
    }

    /** 경유 여정의 효용 — 직항보다 확실히 불리해야 허브가 만능이 되지 않는다. */
    private fun connectUtility(
        state: GameState,
        airline: skytycoon.core.model.Airline,
        ra: Route,
        rc: Route,
        aId: String,
        cId: String,
        hubId: String,
        fare: Double,
        direct: Double,
    ): Double {
        val standard = Economics.standardFare(direct, state.world.inflation)
        val logFare = ln((fare / standard).coerceAtLeast(0.05))
        val service = (airline.serviceLevel * 2 + ra.serviceExtra + rc.serviceExtra) / 2.0
        // 편수는 병목 구간이 정한다 — 한쪽이 주 2회면 그 여정은 주 2회짜리다.
        val freq = minOf(ra.freq, rc.freq).toDouble()
        val brand = (airline.brandIn(Cities[aId].region) + airline.brandIn(Cities[cId].region)) / 2.0
        val hubCity = Cities[hubId]
        val hubCap = state.totalSlots(hubId).coerceAtLeast(1)
        val hubGrip = airline.slotsAt(hubId).toDouble() / hubCap
        return -Balance.LEI_PRICE_SENS * 0.5 * logFare -
            Balance.BIZ_PRICE_SENS * 0.5 * logFare +
            Balance.BIZ_SERVICE_W * service +
            Balance.BIZ_FREQ_W * ln(1.0 + freq) +
            Balance.SHARE_BRAND_W * brand +
            Balance.SHARE_HUB_W * hubGrip +
            Balance.SHARE_SAFETY_W * (airline.safety - 1.0) -
            Balance.CONNECT_PENALTY
    }
}
