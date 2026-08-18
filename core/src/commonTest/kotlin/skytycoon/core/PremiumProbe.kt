package skytycoon.core

import skytycoon.core.data.Cities
import skytycoon.core.model.GameState
import skytycoon.core.sim.Ai
import skytycoon.core.sim.Market
import skytycoon.core.sim.NewGame
import skytycoon.core.sim.Rng
import skytycoon.core.sim.TurnEngine
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * 프리미엄 매력도 재료(브랜드·서비스 등급·부대시설)가 **노선 단위로** 얼마나 벌어지는지
 * 재는 진단 하네스. 회사 평균이 아니라 시장 계산이 실제로 보는 값이어야 한다.
 *
 * 광고비에 정비례하던 시절에는 세 해 만에 아홉 회사가 전부 브랜드 100 이라 이름값이
 * 아무것도 가르지 못했다 — 이 하네스가 그걸 잡아냈다.
 *   ./gradlew :core:jvmTest --tests '*PremiumProbe*' -i   (@Ignore 를 잠시 지우고)
 */
@Ignore
class PremiumProbe {

    @Test
    fun spread() {
        var s: GameState = NewGame.create(seed = 7, companyId = "hanseong")
        for (turn in 0 until 40) {
            for (a in s.airlines.filter { it.alive }) {
                s = Ai.autoPilot(s, a.id, Rng(s.rngState xor a.id.length))
            }
            s = TurnEngine.advance(s)
            if (turn % 16 != 15) continue
            println("--- ${s.year}년")
            for (a in s.airlines.filter { it.alive }) {
                val brands = mutableListOf<Double>()
                val facs = mutableListOf<Double>()
                for (r in s.routesOf(a.id)) {
                    val ca = Cities[r.from]
                    val cb = Cities[r.to]
                    brands += (a.brandIn(ca.region) + a.brandIn(cb.region)) / 2.0
                    facs += a.businesses
                        .filter { it.city == r.from || it.city == r.to }
                        .sumOf { it.type.premiumAppeal }
                }
                if (brands.isEmpty()) continue
                fun d(x: Double) = (x * 10).toInt() / 10.0
                val regions = s.routesOf(a.id)
                    .flatMap { listOf(Cities[it.from].region, Cities[it.to].region) }.distinct().size
                println(
                    "  ${a.short} svc=${a.serviceLevel} 노선=${brands.size} 매출 ${((a.lastResult?.totalRevenue ?: 0.0) / 1e6).toInt()}M " +
                        "광고 ${((a.lastResult?.adSpend ?: 0.0) / 1e6).toInt()}M/${regions}권역 " +
                        "brand[${d(brands.min())}~${d(brands.max())}] 평균 ${d(brands.average())} · " +
                        "부대매력 평균 ${d(facs.average())} 최대 ${d(facs.max())} · 부대 ${a.businesses.size}개 · " +
                        "프리미엄율 " + (0 until brands.size).map {
                            Market.premiumTakeup(brands[it], a.serviceLevel.toDouble(), facs[it])
                        }.let { t -> "평균 ${(t.average() * 1000).toInt() / 10.0}% 최대 ${(t.max() * 1000).toInt() / 10.0}%" },
                )
            }
        }
    }
}
