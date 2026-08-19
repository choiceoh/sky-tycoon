package skytycoon.core.save

import kotlinx.serialization.json.Json
import skytycoon.core.data.Companies
import skytycoon.core.model.GameState
import skytycoon.core.data.AircraftCatalog

/** 세이브는 그냥 JSON 한 덩어리다. 파일로 내보내고 다시 불러오기 쉽다. */
object Save {
    /**
     * 세이브 형식 버전.
     *  * 1 — 버전을 새기기 전 (세이브에는 0 으로 읽힌다)
     *  * 2 — 기재 카탈로그 가격에 기체 급별 배수를 먹인 뒤
     *        ([AircraftCatalog.priceMultiplier])
     */
    const val FORMAT_VERSION = 2

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val pretty = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(state: GameState, indent: Boolean = false): String =
        (if (indent) pretty else json)
            .encodeToString(GameState.serializer(), state.copy(formatVersion = FORMAT_VERSION))

    fun decode(text: String): GameState =
        migrate(json.decodeFromString(GameState.serializer(), text))

    /**
     * 옛 세이브를 지금 규칙에 맞춰 채운다.
     *
     * 필드를 추가할 때 기본값만 믿으면 안 되는 경우가 있다. `foundingShares` 가 그렇다 —
     * 기본값 0 을 그대로 두면 [skytycoon.core.sim.Actions.dilutionBasis] 가 이미 증자로
     * 불어난 주식 수를 "원래 주식 수"로 읽어 평생 희석 한도가 통째로 되살아난다.
     * 하필 그 세이브가 무한 증자 문제를 겪던 판이다. 창업 주식 수는 회사 표에 있으니 거기서 되살린다.
     */
    private fun migrate(state: GameState): GameState =
        state.let(::fillFoundingShares).let(::keepAircraftCostBasis)

    private fun fillFoundingShares(state: GameState): GameState {
        if (state.airlines.all { it.foundingShares > 0.0 }) return state
        return state.copy(
            airlines = state.airlines.map { a ->
                if (a.foundingShares > 0.0) {
                    a
                } else {
                    a.copy(foundingShares = Companies.all.firstOrNull { c -> c.id == a.id }?.shares ?: a.shares)
                }
            },
        )
    }

    /**
     * 이미 산 기재의 취득가 기준을 지킨다.
     *
     * 값이 걸리는 모든 곳이 "카탈로그 가격 × [skytycoon.core.model.Plane.priceMul]" 이라,
     * 카탈로그를 손댄 순간 **옛 세이브의 기재까지 소급해서** 값이 뛴다. 그러면 불러오기만
     * 해도 자기자본이 부풀고, 산 값보다 비싸게 되팔 수 있다. 그 시절의 가격 체계를
     * 기종별 배수만큼 되돌려 새겨 둔다 (인도 대기 중인 발주도 같다).
     *
     * [skytycoon.core.model.Plane.valueMul] 은 건드리지 않는다 — 그쪽은 중고 할인이라
     * 가격 체계와 다른 축이다.
     */
    private fun keepAircraftCostBasis(state: GameState): GameState {
        if (state.formatVersion >= 2) return state
        fun back(typeId: String) = 1.0 / AircraftCatalog.priceMultiplier(AircraftCatalog[typeId])
        return state.copy(
            formatVersion = FORMAT_VERSION,
            planes = state.planes.map { it.copy(priceMul = it.priceMul * back(it.typeId)) },
            orders = state.orders.map { it.copy(priceMul = it.priceMul * back(it.typeId)) },
        )
    }

    fun decodeOrNull(text: String): GameState? = runCatching { decode(text) }.getOrNull()
}
