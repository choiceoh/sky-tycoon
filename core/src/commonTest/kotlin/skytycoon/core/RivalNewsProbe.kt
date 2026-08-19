package skytycoon.core

import skytycoon.core.model.NewsKind
import skytycoon.core.sim.Ai
import skytycoon.core.sim.Debrief
import skytycoon.core.sim.NewGame
import skytycoon.core.sim.Rng
import skytycoon.core.sim.TurnEngine
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * 경쟁사 소식과 결산 해설이 **사람이 읽을 문장으로 나오는지** 눈으로 보는 하네스.
 * 판정이 아니라 관찰이라 평소에는 돌리지 않는다:
 *   ./gradlew :core:jvmTest --tests '*RivalNewsProbe*' -i
 */
@Ignore
class RivalNewsProbe {
    @Test
    fun sample() {
        var s = NewGame.create(seed = 42, companyId = "hanseong")
        val rng = Rng(99)
        repeat(20) {
            if (s.outcome != null) return@repeat
            val before = s
            s = TurnEngine.advance(Ai.autoPilot(s, s.playerId, rng))
            val fresh = s.news.drop(before.news.size).filter { it.kind == NewsKind.RIVAL }
            if (fresh.isEmpty()) return@repeat
            println("── ${s.startYear + before.turn / 4}년 ${before.turn % 4 + 1}분기")
            for (n in fresh) println("   • ${n.headline}\n     ${n.body}")
        }

        println("\n── 마지막 분기 결산 해설")
        s.player.lastResult?.let { last ->
            for (line in Debrief.lines(s, s.player, last)) {
                println("   • ${line.text}  [${line.tone}${line.amount?.let { a -> " ${(a / 1e6).toInt()}M" } ?: ""}]")
            }
        }
        val total = s.news.count { it.kind == NewsKind.RIVAL }
        println("\n5년간 경쟁사 소식 $total 건 (분기당 ${(total / 20.0 * 10).toInt() / 10.0}건)")
    }
}
