package skytycoon.core

import skytycoon.core.data.AircraftCatalog
import skytycoon.core.sim.Ai
import skytycoon.core.sim.Cargo
import skytycoon.core.sim.Geo
import skytycoon.core.sim.NewGame
import skytycoon.core.sim.Rng
import skytycoon.core.sim.TurnEngine
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * 화물이 여객의 그림자에서 벗어났는지 본다.
 *
 * 예전 규칙(여객매출 × 0.13)에서는 어느 노선이든 화물 비중이 정확히 11.5% 였다.
 * 이제 봐야 할 것은 **편차**다 — 광동체 장거리 무역로와 협동체 관광 노선이 다른
 * 숫자를 내놓아야 화물이 판단에 끼어든다.
 *
 *   ./gradlew :core:jvmTest --tests '*CargoProbe*' -i
 */
@Ignore
class CargoProbe {
    @Test
    fun howMuchCargo() {
        for (seed in listOf(1, 42, 777)) {
            var s = NewGame.create(seed = seed, companyId = "hanseong")
            val rng = Rng(seed * 31 + 7)
            var cargo = 0.0
            var total = 0.0
            val shares = mutableListOf<Double>()
            repeat(s.totalTurns) {
                if (s.outcome != null) return@repeat
                s = TurnEngine.advance(Ai.autoPilot(s, s.playerId, rng))
                val r = s.airlineOrNull(s.playerId)?.takeIf { it.alive }?.lastResult ?: return@repeat
                cargo += r.cargoRevenue
                total += r.totalRevenue
                if (r.totalRevenue > 0) shares += r.cargoRevenue / r.totalRevenue
            }
            fun pct(v: Double) = "${(v * 1000).toInt() / 10.0}%"
            println(
                "seed=$seed  화물/총매출 ${pct(cargo / total.coerceAtLeast(1.0))}  " +
                    "분기 편차 ${pct(shares.minOrNull() ?: 0.0)}~${pct(shares.maxOrNull() ?: 0.0)}",
            )
        }

        // 같은 회사 안에서도 노선 성격에 따라 갈리는지 본다 — 이게 갈리지 않으면
        // 화물은 여전히 여객의 그림자다.
        run {
            var s2 = NewGame.create(seed = 42, companyId = "hanseong")
            val rng2 = Rng(99)
            repeat(40) { if (s2.outcome == null) s2 = TurnEngine.advance(Ai.autoPilot(s2, s2.playerId, rng2)) }
            val outs = skytycoon.core.sim.Market.resolveAll(s2)
            val cargo = Cargo.resolveAll(s2, outs)
            println("── 10년 뒤 내 노선의 화물 비중 (상·하위 4개)")
            val rows = s2.routesOf(s2.playerId).mapNotNull { r ->
                val o = outs[r.id] ?: return@mapNotNull null
                if (o.revenue <= 0) return@mapNotNull null
                val c = cargo[r.id] ?: 0.0
                val wide = s2.flyingOn(r.id).any { AircraftCatalog[it.typeId].widebody }
                Triple(
                    "${r.from}–${r.to} ${Geo.distance(r.from, r.to).toInt()}km${if (wide) " 광동체" else ""}",
                    c / (o.revenue + c),
                    c,
                )
            }.sortedByDescending { it.second }
            for (row in rows.take(4) + rows.takeLast(4)) {
                println("   ${row.first}: ${(row.second * 1000).toInt() / 10.0}% (${(row.third / 1e6).toInt()}M)")
            }
        }

        // 노선 성격별로 갈리는지 — 같은 회사 안에서도 달라야 한다.
        val s = NewGame.create(seed = 7, companyId = "hanseong")
        println("── 구간별 (분기 수요 톤 / 광동체 1대 주7왕복 적재여력 톤, 탑승률 0.75)")
        val pairs = listOf(
            "seoul" to "tokyo", "seoul" to "newyork", "london" to "newyork",
            "seoul" to "bangkok", "frankfurt" to "hongkong", "losangeles" to "sydney",
        )
        for ((a, b) in pairs) {
            val dist = Geo.distance(a, b)
            val wide = AircraftCatalog["b747_100"]
            val cap = Cargo.capacityTons(
                listOf(skytycoon.core.model.Plane(1, wide.id, "x", 0)), 7, dist, 0.75,
            )
            val demand = Cargo.demandTons(s, a, b)
            println(
                "   $a–$b ${dist.toInt()}km: 수요 ${demand.toInt()}t / 여력 ${cap.toInt()}t · " +
                    "톤당 ${Cargo.revenuePerTon(dist, 1.0).toInt()}달러",
            )
        }
        println("── 기종별 편당 적재량 (톤)")
        for (id in listOf("b737_200", "b727", "b757", "a300", "b747_100", "b777", "a380", "concorde")) {
            val t = AircraftCatalog[id]
            println("   ${t.name}: ${(Cargo.bellyTons(t) * 10).toInt() / 10.0}t (${t.seats}석, 광동체 ${t.widebody})")
        }
        // 광동체 장거리에서 실제로 크게 붙는지 — AI 노선망은 단거리 협동체뿐이라
        // 그것만 보면 확인이 안 된다. 같은 회사에 노선 하나만 세워 직접 잰다.
        println("── 광동체 장거리 한 노선만 세웠을 때")
        for ((from, to, typeId) in listOf(
            Triple("seoul", "newyork", "b747_100"),
            Triple("seoul", "newyork", "b707"),
            Triple("seoul", "tokyo", "b727"),
        )) {
            val base = NewGame.create(seed = 7, companyId = "hanseong")
            val dist = Geo.distance(from, to)
            val pid = base.nextId
            val rid = pid + 1
            val one = base.copy(
                routes = listOf(
                    skytycoon.core.model.Route(rid, "hanseong", from, to, freq = 3, planeIds = listOf(pid)),
                ),
                planes = listOf(skytycoon.core.model.Plane(pid, typeId, "hanseong", 8, routeId = rid)),
                nextId = rid + 1,
                airlines = base.airlines.map { a ->
                    if (a.id == "hanseong") a.copy(slots = a.slots + (from to 40) + (to to 40)) else a
                },
            )
            val outs = skytycoon.core.sim.Market.resolveAll(one)
            val o = outs[rid]
            val c = Cargo.resolveAll(one, outs)[rid] ?: 0.0
            val share = if (o == null || o.revenue + c <= 0) 0.0 else c / (o.revenue + c)
            println(
                "   ${AircraftCatalog[typeId].name} $from–$to ${dist.toInt()}km 주3왕복: " +
                    "화물 비중 ${(share * 1000).toInt() / 10.0}% (${(c / 1e6).toInt()}M)",
            )
        }
        println("목표: 단거리 협동체 1~3% · 장거리 광동체 9% 이상 (예전엔 어느 노선이든 11.5% 고정)")
        // 회사 전체 화물 비중이 예전(11.5%)보다 크게 낮은 건 정상이다 — 자동조종
        // 노선망이 단거리 협동체로 채워지기 때문이고, 실제 협동체 벨리가 그만큼밖에
        // 안 된다. 광동체 장거리를 직접 굴리면 그 노선에서 9~10% 가 붙는다.
        // 걷어낸 가짜 화물 보조는 FARE_BASE 40 → 48 로 단거리 운임에 되돌려 놨다.
    }
}
