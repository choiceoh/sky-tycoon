package skytycoon.core

import skytycoon.core.model.CityState
import skytycoon.core.sim.Ai
import skytycoon.core.sim.NewGame
import skytycoon.core.sim.Rng
import skytycoon.core.sim.TurnEngine
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 공항이 한 분기 닫히면 그 노선은 아예 못 뜬다. 결산이 빈 결과(좌석 0)를 남기는데,
 * 노선 정리 규칙이 그걸 "임차료도 못 갚은 분기"로 읽으면 **화산 한 번에 멀쩡한 간선을
 * 영구히 접는다**. 슬롯 임차료를 노선 원가에 넣으면서 실제로 그렇게 됐었다.
 */
class ClosureTest {

    @Test
    fun `공항이 닫힌 분기를 근거로 노선을 접지 않는다`() {
        var s = NewGame.create(seed = 11)
        val victim = s.routesOf("liberty").firstOrNull() ?: s.routes.first()
        val airlineId = victim.airlineId
        val routeId = victim.id

        // 한 분기 동안 한쪽 공항을 닫는다.
        s = s.copy(
            cityState = s.cityState + (
                victim.from to (s.cityState[victim.from] ?: CityState())
                    .copy(closedUntilTurn = s.turn)
                ),
        )
        s = TurnEngine.advance(s)

        val grounded = s.routes.firstOrNull { it.id == routeId }?.last
        assertTrue(grounded != null, "노선이 결산 전에 사라졌다")
        assertTrue(grounded.seats <= 0.0, "폐쇄 분기인데 좌석이 잡혔다 (${grounded.seats})")
        // 핵심 불변식: 못 뜬 분기는 **적자로 기록되지 않아야** 한다. 슬롯 임차료를
        // 노선 원가에 실으면서 여기에 임차료를 물리면, 폐쇄 분기가 곧 적자 분기가 되어
        // 노선 정리 규칙이 멀쩡한 간선을 접는다. 그 경로를 여기서 막는다.
        assertTrue(
            grounded.profit >= 0.0,
            "못 뜬 분기가 적자로 기록됐다 (원가 ${grounded.cost}) — 노선 정리가 이걸 근거로 삼는다",
        )

        // 이제 AI 를 여러 번 돌려도 이 분기 실적만 근거로는 접지 않아야 한다.
        // (확률 0.45 이므로 한 번으로는 못 잡는다 — 여러 번 돌려 확실히 본다.)
        var alive = s
        repeat(12) { i ->
            alive = Ai.autoPilot(alive, airlineId, Rng(1234 + i))
        }
        assertTrue(
            alive.routes.any { it.id == routeId },
            "공항 폐쇄로 못 뜬 분기를 근거로 노선을 접었다",
        )
    }
}
