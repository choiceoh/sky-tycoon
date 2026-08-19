package skytycoon.core.sim

import skytycoon.core.data.AircraftCatalog
import skytycoon.core.data.Cities
import skytycoon.core.model.AircraftType
import skytycoon.core.model.City
import skytycoon.core.model.GameState
import skytycoon.core.model.Plane
import skytycoon.core.model.Route
import kotlin.math.pow

/**
 * 화물 — 손님 발밑에 실려 가는 또 하나의 사업.
 *
 * 예전에는 `화물매출 = 여객매출 × 0.13` 이었다. 시뮬레이션이라고 부르기 민망한 줄이다.
 * 그 식에서는 광동체를 장거리 무역로에 붙이든 협동체를 관광 노선에 붙이든 화물이
 * 똑같은 비율로 따라오고, **기종 선택에도 노선 선택에도 화물이 아무 영향을 주지 않는다**.
 *
 * 실제 벨리 카고는 세 가지에 달려 있고, 셋 다 플레이어가 만지는 손잡이다:
 *  * **어디로 가는가** — 화물은 관광지가 아니라 경제·산업 중심지 사이를 흐른다.
 *    수요는 [City.standing] 으로 잡는다 (관광 지수 [City.tour] 는 화물을 만들지 않는다).
 *  * **무엇으로 가는가** — 컨테이너가 들어가는 광동체가 협동체의 서너 배를 싣는다.
 *  * **얼마나 비었는가** — 손님 짐이 먼저 들어간다. 만석 노선은 실을 자리가 없다.
 *    이게 "여객은 남는데 화물이 안 되는" 노선을 만든다.
 */
object Cargo {

    /** 이 기종이 한 편에 실을 수 있는 화물 (톤). */
    fun bellyTons(type: AircraftType): Double {
        val perSeat = if (type.widebody) Balance.CARGO_TONS_PER_SEAT_WIDE else Balance.CARGO_TONS_PER_SEAT
        // 초음속기는 동체가 가늘고 연료가 무거워 화물을 거의 못 싣는다.
        val supersonic = if (type.speed > 1500.0) 0.25 else 1.0
        return type.seats * perSeat * supersonic
    }

    /**
     * 도시쌍의 분기 화물 수요 (톤).
     *
     * 여객과 달리 **거리가 멀수록 유리하다** — 짧은 구간은 트럭이 가져간다.
     * 기준 거리까지는 비례해서 늘고 그 위로는 평평하다.
     */
    fun quarterlyTons(state: GameState, a: City, b: City): Double {
        val dist = Geo.distance(a.id, b.id)
        val trade = (a.standing * b.standing).pow(Balance.CARGO_TRADE_EXP)
        val distFactor = (dist / Balance.CARGO_DIST_REF).coerceAtMost(1.0).pow(Balance.CARGO_DIST_EXP)
        val devA = state.cityState[a.id]?.dev ?: 1.0
        val devB = state.cityState[b.id]?.dev ?: 1.0
        val economy = state.world.economy *
            (state.world.regionEconomy[a.region] ?: 1.0).coerceAtLeast(0.1).pow(0.5) *
            (state.world.regionEconomy[b.region] ?: 1.0).coerceAtLeast(0.1).pow(0.5)
        return Balance.CARGO_K * trade * distFactor * economy * (devA * devB).pow(0.5)
    }

    /**
     * 이 노선이 이번 분기에 내놓는 화물 적재 여력 (톤).
     *
     * 손님 짐이 먼저 들어간다 — 많이 태울수록 남는 자리가 준다. 여객으로 꽉 찬 노선이
     * 화물까지 쓸어 담지는 못한다는 뜻이고, 그래서 화물은 큰 기체를 **여유 있게**
     * 굴리는 쪽에 붙는다.
     *
     * 짐을 미는 것은 **탑승률이 아니라 사람 수**다. 탑승률로 재면 안 된다 — 비즈니스
     * 객실을 크게 깔면 총 좌석이 줄어 같은 인원에도 탑승률이 올라가는데, 벨리 용적은
     * 기체 크기가 정하는 것이라 그대로다. 그러면 같은 손님을 실어 나르는 두 편이
     * **객실 배치만 다르다는 이유로** 화물 여력이 달라진다.
     *
     * @param pax 이번 분기 수송 인원
     */
    fun capacityTons(planes: List<Plane>, freq: Int, distanceKm: Double, pax: Double): Double {
        if (planes.isEmpty() || freq <= 0) return 0.0
        val legs = Economics.quarterlyLegs(freq)
        val shares = Economics.legShares(planes, distanceKm)
        var tons = 0.0
        for ((i, p) in planes.withIndex()) {
            tons += bellyTons(AircraftCatalog[p.typeId]) * legs * shares[i]
        }
        // 분모는 **물리 좌석**(전 좌석 이코노미 기준)이다 — 객실을 어떻게 나누든 같다.
        val physicalSeats = Economics.quarterlySeats(freq, Economics.capacity(planes, distanceKm).avgSeats)
        val load = if (physicalSeats <= 0.0) 0.0 else (pax / physicalSeats).coerceIn(0.0, 1.0)
        val baggage = (Balance.CARGO_BAGGAGE_SHARE * load).coerceAtMost(0.95)
        return tons * (1.0 - baggage)
    }

    /** 톤당 운임 — 거리에 비례하되 장거리일수록 톤·km 단가는 떨어진다. */
    fun revenuePerTon(distanceKm: Double, inflation: Double): Double =
        Balance.CARGO_YIELD_PER_TON_KM * distanceKm.pow(Balance.CARGO_YIELD_DIST_EXP) * inflation

    /**
     * 도시쌍 하나의 화물 배분.
     *
     * 화주는 브랜드를 보지 않는다 — 자리가 있는 곳에 싣는다. 그래서 적재 여력에 비례해
     * 나누고, 수요가 여력보다 적으면 그만큼만 채운다. 여객처럼 로짓으로 갈라도 되지만
     * 그러면 서비스 등급·객실 같은 여객 손잡이가 화물까지 좌우해, 화물이 여객의
     * 그림자로 되돌아간다.
     *
     * @param capacity 노선 id → 적재 여력(톤)
     * @return 노선 id → 이번 분기 화물 매출
     */
    fun allocate(
        state: GameState,
        a: City,
        b: City,
        capacity: Map<Int, Double>,
    ): Map<Int, Double> {
        if (capacity.isEmpty()) return emptyMap()
        val supply = capacity.values.sum()
        if (supply <= 0.0) return emptyMap()
        val dist = Geo.distance(a.id, b.id)
        // 세계 화물의 일부만 모델 안의 항공사가 가져간다 — 나머지는 화물 전용사와
        // 해운의 몫이다. 이게 없으면 큰 기체 몇 대로 그 구간 화물을 통째로 쓸어 담는다.
        val demand = quarterlyTons(state, a, b) * Balance.CARGO_ADDRESSABLE
        val carried = minOf(demand, supply)
        val perTon = revenuePerTon(dist, state.world.inflation)
        return capacity.mapValues { (_, tons) -> carried * (tons / supply) * perTon }
    }

    /**
     * 판 전체의 화물을 푼다 (노선 id → 이번 분기 화물 매출).
     *
     * 여객이 먼저 풀린 뒤에 부른다 — 빈자리가 얼마인지 알아야 실을 양이 정해지기 때문이다.
     */
    fun resolveAll(state: GameState, outcomes: Map<Int, RouteOutcome>): Map<Int, Double> {
        val byPair = HashMap<String, MutableList<Route>>()
        for (r in state.routes) {
            if (!r.active || r.freq <= 0) continue
            if (outcomes[r.id] == null) continue
            byPair.getOrPut(Geo.pairKey(r.from, r.to)) { mutableListOf() }.add(r)
        }
        val out = HashMap<Int, Double>()
        for ((key, routes) in byPair) {
            val (idA, idB) = key.split("|")
            val dist = Geo.distance(idA, idB)
            val capacity = HashMap<Int, Double>(routes.size)
            for (r in routes) {
                val o = outcomes[r.id] ?: continue
                if (o.seats <= 0.0) continue
                val planes = state.flyingOn(r.id)
                val freq = r.freq.coerceAtMost(Economics.capacity(planes, dist).maxFreq)
                val tons = capacityTons(planes, freq, dist, o.pax)
                if (tons > 0.0) capacity[r.id] = tons
            }
            out.putAll(allocate(state, Cities[idA], Cities[idB], capacity))
        }
        return out
    }

    /**
     * 한 노선의 이번 분기 화물 매출.
     *
     * 같은 구간의 **다른 항공사까지 넣은** 결과를 줘야 맞는다 — 화물은 그 구간의
     * 적재 여력을 나눠 갖는 것이라, 혼자만 넣으면 경쟁자 몫까지 제 것으로 잡는다.
     */
    fun revenueFor(state: GameState, routeId: Int, outcomes: Map<Int, RouteOutcome>): Double =
        resolveAll(state, outcomes)[routeId] ?: 0.0

    /** 화면·해설에 쓰는 한 노선의 이번 분기 화물 적재 여력. */
    fun routeCapacityTons(state: GameState, routeId: Int): Double {
        val route = state.routes.firstOrNull { it.id == routeId } ?: return 0.0
        val planes = state.flyingOn(routeId)
        if (planes.isEmpty()) return 0.0
        val dist = Geo.distance(route.from, route.to)
        val freq = route.freq.coerceAtMost(Economics.capacity(planes, dist).maxFreq)
        return capacityTons(planes, freq, dist, route.last?.pax ?: 0.0)
    }

    /** 화면에 쓰는 도시쌍 화물 수요 (톤). */
    fun demandTons(state: GameState, from: String, to: String): Double =
        quarterlyTons(state, Cities[from], Cities[to]) * Balance.CARGO_ADDRESSABLE
}
