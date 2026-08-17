package skytycoon.core.save

import kotlinx.serialization.json.Json
import skytycoon.core.data.Companies
import skytycoon.core.model.GameState

/** 세이브는 그냥 JSON 한 덩어리다. 파일로 내보내고 다시 불러오기 쉽다. */
object Save {
    const val FORMAT_VERSION = 1

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
        (if (indent) pretty else json).encodeToString(GameState.serializer(), state)

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
    private fun migrate(state: GameState): GameState {
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

    fun decodeOrNull(text: String): GameState? = runCatching { decode(text) }.getOrNull()
}
