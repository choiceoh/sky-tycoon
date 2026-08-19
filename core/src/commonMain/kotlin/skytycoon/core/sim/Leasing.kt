package skytycoon.core.sim

import skytycoon.core.data.AircraftCatalog
import skytycoon.core.model.AircraftType
import skytycoon.core.model.Airline
import skytycoon.core.model.GameState
import skytycoon.core.model.Plane

/**
 * 운용리스 — 기체를 갖지 않고 빌려 쓰는 길.
 *
 * 지금까지 기재를 늘리는 방법은 "현금으로 사거나, 빚내서 사거나" 둘뿐이었다. 둘 다
 * **자본을 쓰는** 방법이라 자금 조달이 사실상 한 축이었다. 리스는 다른 축을 연다 —
 * 목돈 없이 즉시 기재를 붙이는 대신, 경기가 꺾여도 줄지 않는 분기 고정비를 진다.
 *
 * 규칙의 핵심은 **리스기가 내 자산이 아니라는 것**이다. 기단 가치에도, 상각에도,
 * 매각에도 잡히지 않는다. 그러지 않으면 리스로 자기자본을 부풀려 차입 한도와 최종
 * 순위를 살 수 있다 (중고기 장부가에서 이미 한 번 막았던 것과 같은 구멍이다).
 */
object Leasing {

    /**
     * 분기 리스료. 기체값에 비례하고, 짧게 빌릴수록 요율이 비싸다.
     *
     * 중고 기령이면 그만큼 싸다 — 빌려주는 쪽도 잔존가치로 값을 매긴다.
     */
    fun quarterlyRate(type: AircraftType, quarters: Int, ageQuarters: Int = 0): Double {
        val longest = Balance.LEASE_TERMS.max()
        val shortPremium = 1.0 +
            Balance.LEASE_SHORT_TERM_PREMIUM * ((longest - quarters).toDouble() / longest).coerceIn(0.0, 1.0)
        return type.price * AircraftCatalog.residualRatio(ageQuarters) *
            Balance.LEASE_RATE_PER_QUARTER * shortPremium
    }

    /**
     * 리스로 들어올 기체의 기령.
     *
     * 생산이 끝난 기종은 중고 매물과 같은 기령으로 들어오고, 그만큼 리스료가 싸다.
     * **견적 화면과 실제 계약이 같은 값을 써야 한다** — 화면이 기령 0 으로 계산하면
     * 표시된 리스료가 실제보다 비싸게 뜬다.
     */
    fun deliveryAge(state: GameState, type: AircraftType): Int =
        if (state.year > type.retire) Actions.usedAge(state, type.id) else 0

    /** 이번 분기에 이 회사가 낼 리스료 총액 — 노선에 붙었든 세워 뒀든 그대로 나간다. */
    fun quarterlyCost(state: GameState, airlineId: String): Double =
        state.planesOf(airlineId).filter { it.leased }.sumOf { it.leaseRate }

    /** 이 기체의 계약이 몇 분기 남았나 (이번 분기 포함). */
    fun quartersLeft(state: GameState, plane: Plane): Int {
        val until = plane.leaseUntilTurn ?: return 0
        return (until - state.turn + 1).coerceAtLeast(0)
    }

    /** 중도 반납 위약금 — 남은 기간과 [Balance.LEASE_BREAK_QUARTERS] 중 짧은 쪽의 리스료. */
    fun breakFee(state: GameState, plane: Plane): Double =
        plane.leaseRate * quartersLeft(state, plane).coerceAtMost(Balance.LEASE_BREAK_QUARTERS)

    /**
     * 이 회사가 리스기를 더 들일 수 있는가.
     *
     * 상한이 없으면 기단 전체를 리스로 채우고 자본은 현금으로만 들고 있는 판이 최적이 된다
     * (자산이 없으니 인수도 안 당한다). 기단의 일정 비율까지만 허용해, 리스를 "판단"으로
     * 남기고 "지배 전략"이 되지 않게 막는다.
     */
    fun leaseRoom(state: GameState, airline: Airline): Int =
        headroom(state, airline).coerceAtLeast(0)

    /**
     * 상한까지 남은 자리 — **음수도 그대로** 돌려준다.
     *
     * 0 으로 깎으면 "이미 넘겼다"와 "딱 맞다"를 구분할 수 없다. 소유기를 파는 쪽에서
     * 그 구분이 필요하다: 한도까지 빌린 뒤 분모가 된 소유기를 팔면 비중이 뒤에서
     * 무너지는데, 깎인 값으로 물으면 언제나 0 이라 그 매각이 통과한다.
     */
    fun headroom(state: GameState, airline: Airline): Int {
        val fleet = state.planesOf(airline.id)
        val leased = fleet.count { it.leased }
        // 소유기 n 대일 때 리스기는 최대 n * share / (1 - share) 대까지.
        val owned = fleet.size - leased
        val cap = (owned * Balance.LEASE_FLEET_SHARE_MAX / (1.0 - Balance.LEASE_FLEET_SHARE_MAX)).toInt()
        return cap - leased
    }

    /**
     * 계약이 끝난 리스기를 돌려보낸다.
     *
     * 결산이 끝난 뒤에 부르므로 이번 분기는 온전히 태우고, 배속돼 있었다면 **다음
     * 분기부터** 그 자리가 빈다 — 만료를 화면에 미리 보여줘야 하는 이유다.
     */
    fun returnExpired(state: GameState): Pair<GameState, List<Plane>> {
        val due = state.planes.filter { p -> p.leaseUntilTurn?.let { it <= state.turn } == true }
        if (due.isEmpty()) return state to emptyList()
        val dueIds = due.map { it.id }.toSet()
        val next = state.copy(
            planes = state.planes.filter { it.id !in dueIds },
            routes = state.routes.map { r ->
                if (r.planeIds.none { it in dueIds }) return@map r
                val left = r.planeIds - dueIds
                // 기재가 하나도 안 남은 노선은 **세워야** 한다. 편수와 active 를 그대로 두면
                // 좌석은 0 인데 슬롯은 계속 물고(usedSlots), 임차료를 내면서 부대사업
                // 자격까지 유지하는 유령 노선이 된다 — 게다가 AI 의 노선 정리는 좌석 0 인
                // 분기를 "판단 근거가 못 되는 분기"로 건너뛰므로 영영 정리되지 않는다.
                if (left.isEmpty()) r.copy(planeIds = left, freq = 0, active = false) else r.copy(planeIds = left)
            },
        )
        return next to due
    }
}
