package skytycoon.core

import skytycoon.core.sim.Ai
import skytycoon.core.sim.Maintenance
import skytycoon.core.sim.NewGame
import skytycoon.core.sim.Rng
import skytycoon.core.sim.TurnEngine
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * 중정비가 판에 실제로 얼마나 걸리는지 본다.
 *
 * 노리는 것은 "늘 기단의 한 자락이 빠져 있다"는 감각이다. 너무 적으면 있으나 마나고,
 * 너무 많으면 예비기를 강제로 사게 만드는 세금이 된다. 보고 싶은 값:
 *  * **입고율**: 분기마다 몇 %가 정비 중인가 (뜨는 기재 기준 8~15% 근처를 노린다)
 *  * **편수 손실**: 정비 때문에 실제로 못 띄운 편수 비율
 *  * **중정비비 비중**: 매출 대비. 1~3% 면 결산에서 눈에 띄되 판을 흔들지는 않는다
 *
 * 판정이 아니라 관찰이라 평소에는 돌리지 않는다:
 *   ./gradlew :core:jvmTest --tests '*FleetProbe*' -i
 */
@Ignore
class FleetProbe {
    @Test
    fun howMuchDoesMaintenanceBite() {
        for (seed in listOf(1, 42, 777)) {
            var s = NewGame.create(seed = seed, companyId = "hanseong")
            val rng = Rng(seed * 31 + 7)
            var inCheck = 0
            var fleetQuarters = 0
            var checkCost = 0.0
            var revenue = 0.0
            var lostFreq = 0
            var wantedFreq = 0
            var groundedRouteQuarters = 0
            var routeQuarters = 0
            val prevHours = HashMap<Int, Double>()
            var hoursFlown = 0.0
            var flyingPlaneQuarters = 0
            var idlePlaneQuarters = 0

            repeat(s.totalTurns) {
                if (s.outcome != null) return@repeat
                s = TurnEngine.advance(Ai.autoPilot(s, s.playerId, rng))
                val me = s.airlineOrNull(s.playerId)?.takeIf { it.alive } ?: return@repeat

                val mine = s.planesOf(me.id)
                fleetQuarters += mine.size
                idlePlaneQuarters += mine.count { it.routeId == null }
                for (p in mine) {
                    val prev = prevHours[p.id]
                    if (prev != null && p.hoursSinceCheck > prev) {
                        hoursFlown += p.hoursSinceCheck - prev
                        flyingPlaneQuarters++
                    }
                }
                prevHours.clear()
                for (p in mine) prevHours[p.id] = p.hoursSinceCheck
                // 결산 직후라 turn 은 이미 +1 이다 — 방금 정비한 기체는 checkUntilTurn == turn-1.
                inCheck += mine.count { it.checkUntilTurn == s.turn - 1 }

                val r = me.lastResult ?: return@repeat
                checkCost += r.checkCost
                revenue += r.totalRevenue

                for (route in s.routesOf(me.id).filter { it.active && it.freq > 0 }) {
                    routeQuarters++
                    val assigned = s.assignedTo(route.id)
                    val grounded = assigned.count { it.checkUntilTurn == s.turn - 1 }
                    if (grounded > 0 && grounded == assigned.size) groundedRouteQuarters++
                    wantedFreq += route.freq
                    if (grounded > 0) lostFreq += route.freq - (route.last?.let { route.freq } ?: 0)
                }
            }

            fun pct(v: Double) = "${(v * 1000).toInt() / 10.0}%"
            println(
                "seed=$seed  입고율 ${pct(inCheck.toDouble() / fleetQuarters.coerceAtLeast(1))}  " +
                    "전편 결항 노선분기 ${pct(groundedRouteQuarters.toDouble() / routeQuarters.coerceAtLeast(1))}  " +
                    "중정비비/매출 ${pct(checkCost / revenue.coerceAtLeast(1.0))}  " +
                    "뜨는 기체 평균 ${(hoursFlown / flyingPlaneQuarters.coerceAtLeast(1)).toInt()}h/분기  " +
                    "노는 기체 ${pct(idlePlaneQuarters.toDouble() / fleetQuarters.coerceAtLeast(1))}",
            )
        }
        println("── 기종별 점검 주기 (기령 0 / 40분기)")
        for (id in listOf("dc9", "b727", "b747_100", "b777")) {
            val t = skytycoon.core.data.AircraftCatalog[id]
            println(
                "   ${t.name}: ${Maintenance.intervalHours(t, 0).toInt()}h → " +
                    "${Maintenance.intervalHours(t, 40).toInt()}h",
            )
        }
        println("목표: 입고율 8~15% · 중정비비/매출 1~3%")
    }
}
