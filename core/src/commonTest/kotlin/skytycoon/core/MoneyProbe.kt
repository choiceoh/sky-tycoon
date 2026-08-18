package skytycoon.core

import skytycoon.core.sim.Ai
import skytycoon.core.sim.Balance
import skytycoon.core.sim.Economics
import skytycoon.core.sim.NewGame
import skytycoon.core.sim.Rng
import skytycoon.core.sim.TurnEngine
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * "돈이 너무 쉽게 벌린다"를 수치로 본다.
 *
 * 경영 시뮬레이션에서 돈이 헐거워졌는지는 세 지표에 그대로 나온다 —
 *  * **순이익률**: 늘 두 자릿수 후반이면 어떤 선택도 대가가 없다.
 *  * **기재 회수 기간**: 한 대가 제 값을 뽑는 데 걸리는 햇수. 짧으면 기재가 공짜다.
 *  * **적자 분기**: 20년을 굴려도 아무도 손실을 안 보면 위험이라는 축이 없는 것이다.
 *
 * 판정이 아니라 관찰이라 평소에는 돌리지 않는다:
 *   ./gradlew :core:jvmTest --tests '*MoneyProbe*' -i
 */
@Ignore
class MoneyProbe {
    @Test
    fun howEasyIsMoney() {
        for (seed in listOf(1, 42, 777)) {
            var s = NewGame.create(seed = seed, companyId = "hanseong")
            val rng = Rng(seed * 31 + 7)
            val margins = mutableListOf<Double>()
            val paybacks = mutableListOf<Double>()
            var quarters = 0
            var lossQuarters = 0
            var cashRatioSum = 0.0
            var samples = 0

            repeat(s.totalTurns) {
                if (s.outcome != null) return@repeat
                s = TurnEngine.advance(Ai.autoPilot(s, s.playerId, rng))

                for (a in s.livingAirlines) {
                    val r = a.lastResult ?: continue
                    quarters++
                    if (r.net < 0) lossQuarters++
                }

                val me = s.airlineOrNull(s.playerId)?.takeIf { it.alive } ?: return@repeat
                val r = me.lastResult ?: return@repeat
                if (r.totalRevenue <= 0) return@repeat
                margins += r.net / r.totalRevenue

                // 기재 한 대가 제 값을 뽑는 데 걸리는 햇수.
                // 장부가는 상각액에서 되짚는다 (상각 = 취득가 / DEPRECIATION_QUARTERS).
                val fleet = s.planesOf(me.id).size
                if (fleet > 0 && r.net > 0) {
                    val bookValue = r.depreciation * Balance.DEPRECIATION_QUARTERS
                    paybacks += bookValue / (r.net * 4)
                }
                cashRatioSum += me.cash / Economics.equity(s, me).coerceAtLeast(1.0)
                samples++
            }

            fun median(xs: List<Double>) = if (xs.isEmpty()) 0.0 else xs.sorted()[xs.size / 2]
            fun pct(v: Double) = "${(v * 100).toInt()}%"

            println(
                "seed=$seed  순이익률 중앙값 ${pct(median(margins))} " +
                    "(최악 ${pct(margins.minOrNull() ?: 0.0)}, 최고 ${pct(margins.maxOrNull() ?: 0.0)})  " +
                    "기재 회수 ${((median(paybacks)) * 10).toInt() / 10.0}년  " +
                    "적자 분기 ${lossQuarters}/${quarters} (${pct(lossQuarters.toDouble() / quarters.coerceAtLeast(1))})  " +
                    "현금/자본 ${pct(cashRatioSum / samples.coerceAtLeast(1))}",
            )
        }
        println(
            "목표: 순이익률 10~18% · 기재 회수 4~6년 · 적자 분기 5% 이상 " +
                "(호황엔 남고 불황엔 물리는 폭)",
        )
        // 남은 거리: 순이익률이 아직 목표 위이고 적자 분기가 0 이다. 더 조이려면
        // **단거리부터 죽는다**는 벽을 먼저 넘어야 한다 — BalanceTest 의 런던–파리는
        // 최선 편수에서도 마진이 10% 언저리라, 고정비를 올리는 손잡이(기재당 간접비,
        // 운항 원가 배수)는 어느 것이든 그 거리대를 통째로 적자로 만든다.
        // 측정해 본 것: 기재당 간접비 55만(-5%), 운항 원가 ×1.5(-35%), 기재값 ×2.6(-41%).
        // 적자 분기를 만들려면 고정비가 필요한데 고정비가 단거리를 죽이므로,
        // 순서는 "단거리 여유 넓히기 → 고정비 올리기" 여야 한다.

    }
}
