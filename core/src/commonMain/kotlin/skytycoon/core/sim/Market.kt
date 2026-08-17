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
            val capacityA = (a.slots + (state.cityState[a.id]?.extraSlots ?: 0)).coerceAtLeast(1)
            val capacityB = (b.slots + (state.cityState[b.id]?.extraSlots ?: 0)).coerceAtLeast(1)
            val hub = (
                airline.slotsAt(a.id).toDouble() / capacityA +
                    airline.slotsAt(b.id).toDouble() / capacityB
                ) / 2.0
            val prestige = planes.sumOf { AircraftCatalog[it.typeId].prestige } / planes.size
            val bizFacilities = airline.businesses
                .filter { it.city == a.id || it.city == b.id }
                .sumOf { it.type.demandBoost }

            val common = Balance.SHARE_BRAND_W * brand +
                Balance.SHARE_PRESTIGE_W * prestige +
                Balance.SHARE_HUB_W * hub +
                Balance.SHARE_SAFETY_W * (airline.safety - 1.0) +
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
     * 로짓 점유율로 수요를 나누고, 좌석이 모자라 넘친 몫을 여유 있는 곳으로 재배분한다.
     * [Offer.remaining] 을 소비하며 각 offer 가 실어 나른 인원 배열을 돌려준다.
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
            val maxU = utils.max()
            val weights = utils.map { exp(it - maxU) }
            val sum = weights.sum()
            if (sum <= 0.0) return@repeat

            var served = 0.0
            for ((k, idx) in active.withIndex()) {
                val want = left * (weights[k] / sum)
                val o = offers[idx]
                val give = minOf(want, o.remaining)
                o.remaining -= give
                taken[idx] += give
                served += give
            }
            if (served <= 1e-9) return@repeat
            left -= served
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
                        // 같은 항공사가 A–C 직항을 이미 갖고 있으면 굳이 태워 돌리지 않는다.
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

        for ((pairKey, offers) in candidates) {
            val (aId, cId) = pairKey.split("|")
            val a = Cities[aId]
            val c = Cities[cId]
            val demand = Demand.quarterly(state, a, c).total
            if (demand <= 0.0) continue
            // 직항이 이미 태운 몫은 빠진다. 환승은 어디까지나 남은 수요를 줍는 것이고,
            // 그마저도 일부만 경유를 감수한다.
            val unmet = (demand - (localPaxByPair[pairKey] ?: 0.0)).coerceAtLeast(0.0) *
                Balance.CONNECT_CAPTURE
            if (unmet <= 1.0) continue

            // 로짓으로 후보끼리 나눈 뒤, 각 여정의 병목 좌석까지만 태운다.
            val maxU = offers.maxOf { it.util }
            val weights = offers.map { exp(it.util - maxU) }
            val sum = weights.sum()
            if (sum <= 0.0) continue
            for ((k, o) in offers.withIndex()) {
                val want = unmet * (weights[k] / sum)
                val take = minOf(want, o.spare)
                if (take <= 0.0) continue
                o.pax = take
                // 수입은 구간 거리로 나눈다 — 긴 구간이 더 가져간다.
                val total = (o.distA + o.distB).coerceAtLeast(1.0)
                val revenue = take * o.fare * Balance.CONNECT_YIELD
                addPax[o.legA.routeId] = (addPax[o.legA.routeId] ?: 0.0) + take
                addPax[o.legB.routeId] = (addPax[o.legB.routeId] ?: 0.0) + take
                addRev[o.legA.routeId] = (addRev[o.legA.routeId] ?: 0.0) + revenue * (o.distA / total)
                addRev[o.legB.routeId] = (addRev[o.legB.routeId] ?: 0.0) + revenue * (o.distB / total)
            }
        }
        if (addPax.isEmpty()) return base

        return base.mapValues { (id, o) ->
            val p = addPax[id] ?: return@mapValues o
            // 여러 도시쌍이 같은 구간을 나눠 쓰므로, 합계가 남은 좌석을 넘지 않게 자른다.
            val room = (o.seats - o.localPax).coerceAtLeast(0.0)
            val fitted = p.coerceAtMost(room)
            val scale = if (p <= 0.0) 0.0 else fitted / p
            o.copy(connectPax = fitted, connectRevenue = (addRev[id] ?: 0.0) * scale)
        }
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
        val hubCap = (hubCity.slots + (state.cityState[hubId]?.extraSlots ?: 0)).coerceAtLeast(1)
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
