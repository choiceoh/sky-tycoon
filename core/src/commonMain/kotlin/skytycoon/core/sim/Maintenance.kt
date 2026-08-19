package skytycoon.core.sim

import skytycoon.core.data.AircraftCatalog
import skytycoon.core.data.Difficulties
import skytycoon.core.model.AircraftType
import skytycoon.core.model.Airline
import skytycoon.core.model.BusinessType
import skytycoon.core.model.GameState
import skytycoon.core.model.Plane

/**
 * 중정비 — 기체를 통째로 뜯어 보는 일. 그 분기에는 뜨지 못한다.
 *
 * 이게 없으면 기단은 "산 순간부터 영원히 같은 좌석을 내놓는 숫자"라, 노선에 기재를
 * 딱 맞게 붙여 놓고 잊어도 아무 일이 없다. 넣고 나면 예비기 한 대를 놀리는 값
 * (기재당 간접비)과 정비 분기에 편수를 잃는 값을 저울질하게 된다.
 *
 * 주기는 **비행시간과 달력 중 먼저 차는 쪽**으로 잰다. 굴릴수록 자주 들어가니 가동률에
 * 값이 붙고, 세워 둔 기체도 언젠가는 들어가니 예비기가 공짜 보험이 되지 않는다.
 */
object Maintenance {

    /** 기령이 주기를 줄이는 비율 — 비행시간과 달력에 똑같이 걸린다. */
    private fun shrink(ageQuarters: Int): Double =
        (1.0 - ageQuarters * Balance.CHECK_INTERVAL_AGE_DECAY)
            .coerceAtLeast(Balance.CHECK_INTERVAL_MIN_RATIO)

    /** 이 기체의 다음 중정비까지 비행시간 — 늙을수록 짧아진다. */
    fun intervalHours(type: AircraftType, ageQuarters: Int): Double =
        // 기종은 가리지 않는다. 대형기의 부담은 시간이 아니라 값으로 물린다.
        Balance.CHECK_INTERVAL_HOURS * shrink(ageQuarters)

    fun intervalHours(plane: Plane): Double =
        intervalHours(AircraftCatalog[plane.typeId], plane.ageQuarters)

    /** 덜 굴려도 이 분기 수를 넘기면 들어간다. */
    fun intervalQuarters(ageQuarters: Int): Int =
        (Balance.CHECK_MAX_QUARTERS * shrink(ageQuarters)).toInt()
            .coerceAtLeast(Balance.CHECK_MIN_QUARTERS)

    fun intervalQuarters(plane: Plane): Int = intervalQuarters(plane.ageQuarters)

    /**
     * 다음 중정비까지 얼마나 왔나 (1.0 이면 입고).
     * 비행시간과 달력 중 **먼저 차는 쪽**을 본다.
     */
    fun progress(plane: Plane): Double = maxOf(
        plane.hoursSinceCheck / intervalHours(plane),
        plane.quartersSinceCheck.toDouble() / intervalQuarters(plane),
    ).coerceAtLeast(0.0)

    /** 지금 입고해야 하는가 — 비행시간이든 달력이든 하나라도 찼으면. */
    fun isDue(plane: Plane): Boolean =
        plane.hoursSinceCheck >= intervalHours(plane) ||
            plane.quartersSinceCheck >= intervalQuarters(plane)

    /** 곧 들어간다 — 예비기를 붙일 시간을 주려고 미리 알린다. */
    fun dueSoon(plane: Plane): Boolean =
        !isDue(plane) && progress(plane) >= Balance.CHECK_WARN_RATIO

    /**
     * 중정비 한 번의 값. 정가에 비례하고 기령에 따라 비싸진다.
     *
     * 정비창(HANGAR)을 가진 회사는 자기 격납고에서 처리하니 싸게 먹힌다 — 노선 정비비에
     * 걸리는 것과 같은 할인이다.
     */
    fun checkCost(state: GameState, airline: Airline, plane: Plane): Double {
        val type = AircraftCatalog[plane.typeId]
        val hangar = if (airline.businesses.any { it.type == BusinessType.HANGAR }) {
            1.0 - BusinessType.HANGAR.maintDiscount
        } else {
            1.0
        }
        return type.price * plane.priceMul *
            Balance.CHECK_COST_RATE *
            (1.0 + plane.ageQuarters * Balance.CHECK_COST_AGE_SLOPE) *
            hangar *
            Difficulties[state.difficultyId].costMul
    }

    /** 이번 분기에 중정비로 묶인 기재들 — 결산에서 그 값을 청구한다. */
    fun inCheckThisQuarter(state: GameState, airlineId: String): List<Plane> =
        state.planesOf(airlineId).filter { it.checkUntilTurn == state.turn }

    /**
     * 이번 분기의 중정비비 총액.
     *
     * 입고 분기에 한 번만 잡히도록 `checkUntilTurn == turn` 으로 고른다 — 정비가
     * 한 분기짜리라 성립하는 식이다 (여러 분기로 늘리려면 입고 시점을 따로 새겨야 한다).
     */
    fun quarterlyCheckCost(state: GameState, airline: Airline): Double =
        inCheckThisQuarter(state, airline.id).sumOf { checkCost(state, airline, it) }

    /**
     * 이번 분기에 입고할 기재를 정한다. 시장이 열리기 **전에** 불러야 한다 —
     * 좌석이 그만큼 줄어든 채로 분기가 굴러가야 하기 때문이다.
     */
    fun scheduleChecks(state: GameState): GameState {
        val due = state.planes.filter { !it.inCheck(state.turn) && isDue(it) }
        if (due.isEmpty()) return state
        val dueIds = due.map { it.id }.toSet()
        return state.copy(
            planes = state.planes.map {
                if (it.id in dueIds) {
                    it.copy(checkUntilTurn = state.turn, hoursSinceCheck = 0.0, quartersSinceCheck = 0)
                } else {
                    it
                }
            },
        )
    }
}
