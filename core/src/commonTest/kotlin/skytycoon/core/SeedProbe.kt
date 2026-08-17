package skytycoon.core

import skytycoon.core.model.GameState
import skytycoon.core.sim.Ai
import skytycoon.core.sim.Economics
import skytycoon.core.sim.NewGame
import skytycoon.core.sim.Rng
import skytycoon.core.sim.TurnEngine
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * 시드 편차 진단 하네스 — 자동조종 10년을 시드 10개로 돌려 **한 시드의 운이 아닌지**
 * 본다. 40분기 캠페인은 경로 의존이 심해서 노선 하나를 다르게 고르면 결과가 크게
 * 갈린다. 밸런스 상수를 만진 뒤 `SimulationTest` 하나만 보고 판단하면 안 되는 이유다.
 *   ./gradlew :core:jvmTest --tests '*SeedProbe*' -i
 */
@Ignore
class SeedProbe {

    @Test
    fun seeds() {
        var grew = 0
        var died = 0
        val ratios = mutableListOf<Double>()
        for (seed in listOf(7, 42, 99, 123, 555, 777, 1234, 4242, 31337, 60606)) {
            var s: GameState = NewGame.create(seed = seed, companyId = "hanseong")
            val start = Economics.equity(s, s.player)
            repeat(40) {
                if (s.outcome != null) return@repeat
                s = Ai.autoPilot(s, s.playerId, Rng(s.rngState xor 0x5EED))
                s = TurnEngine.advance(s)
            }
            val me = s.airlineOrNull("hanseong")
            if (me == null || !me.alive) {
                died++
                println("seed $seed → 파산")
                continue
            }
            val end = Economics.equity(s, me)
            ratios += end / start
            if (end > start) grew++
            println(
                "seed $seed → ${(start / 1e6).toInt()}M → ${(end / 1e6).toInt()}M " +
                    "(x${((end / start) * 100).toInt() / 100.0}) 노선 ${s.routesOf("hanseong").size}",
            )
        }
        println("[요약] 성장 $grew/10 · 파산 $died/10 · 배수 중앙값 ${ratios.sorted().getOrNull(ratios.size / 2)}")
    }
}
