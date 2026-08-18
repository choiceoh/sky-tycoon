package skytycoon.core

import skytycoon.core.data.Cities
import skytycoon.core.sim.Ai
import skytycoon.core.sim.Actions
import skytycoon.core.sim.Economics
import skytycoon.core.sim.NewGame
import skytycoon.core.sim.Rng
import skytycoon.core.sim.Stock
import skytycoon.core.sim.TurnEngine
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * "중반이 루즈하다"를 수치로 본다. 재미가 빠지는 자리를 짚으려면 **무엇이 더 이상
 * 제약이 아닌가**를 연도별로 봐야 한다 — 돈이 남아돌기 시작하는 해, 슬롯이 흔해지는 해,
 * 순위가 굳는 해.
 *
 * 판정이 아니라 관찰이라 평소에는 돌리지 않는다:
 *   ./gradlew :core:jvmTest --tests '*SlackProbe*' -i
 */
@Ignore
class SlackProbe {
    @Test
    fun whereDoesItGoSlack() {
        for (seed in listOf(1, 42)) {
            var s = NewGame.create(seed = seed, companyId = "hanseong")
            val rng = Rng(seed * 31 + 7)
            println("── seed=$seed")
            println("  연도  자본   현금% 1위대비 미분양슬롯 홈여유 확장중 ROE  회사 최대지분 노선 유휴기 거점")

            repeat(s.totalTurns) { turn ->
                if (s.outcome != null) return@repeat
                s = TurnEngine.advance(Ai.autoPilot(s, s.playerId, rng))
                if (turn % 4 != 3) return@repeat

                val me = s.player
                val equity = Economics.equity(s, me)
                val top = s.livingAirlines.maxOf { Economics.equity(s, it) }
                val totalSlots = Cities.all.sumOf { s.totalSlots(it.id) }
                val unsold = Cities.all.sumOf { s.unsoldSlots(it) }
                val expanding = Cities.all.count { Actions.expansionInProgress(s, it.id) }
                val annual = me.results.takeLast(4).sumOf { it.net }
                val maxStake = s.livingAirlines.maxOf { holder ->
                    s.livingAirlines.filter { it.id != holder.id }
                        .maxOfOrNull { Stock.ownershipRatio(s, holder.id, it.id) } ?: 0.0
                }
                val idle = s.planesOf(me.id).count { it.routeId == null }

                println(
                    "  ${s.startYear + turn / 4}  " +
                        "${fmt(equity / 1e9)}B  " +
                        "${(me.cash / equity.coerceAtLeast(1.0) * 100).toInt()}%  " +
                        "${(equity / top * 100).toInt()}%  " +
                        "${(unsold.toDouble() / totalSlots * 100).toInt()}%  " +
                        "${s.freeSlots(me.id, me.home)}  " +
                        "$expanding  " +
                        "${(annual / equity.coerceAtLeast(1.0) * 100).toInt()}%  " +
                        "${s.livingAirlines.size}  " +
                        "${(maxStake * 100).toInt()}%  " +
                        "${s.routesOf(me.id).size}  " +
                        "$idle  " +
                        "${me.slots.count { it.value > 0 }}",
                )
            }
        }
    }

    private fun fmt(v: Double) = ((v * 10).toInt() / 10.0).toString()
}
