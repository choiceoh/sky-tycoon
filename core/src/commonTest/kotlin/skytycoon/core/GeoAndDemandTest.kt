package skytycoon.core

import skytycoon.core.data.Cities
import skytycoon.core.sim.Demand
import skytycoon.core.sim.Geo
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class GeoAndDemandTest {

    @Test
    fun `대권거리가 실제 값과 맞는다`() {
        val pairs = listOf(
            Triple("seoul", "tokyo", 1160.0),
            Triple("newyork", "london", 5570.0),
            Triple("london", "sydney", 16990.0),
            Triple("losangeles", "newyork", 3940.0),
        )
        for ((a, b, expected) in pairs) {
            val d = Geo.distance(a, b)
            assertTrue(
                abs(d - expected) / expected < 0.03,
                "$a-$b 거리 ${d.toInt()}km 가 기대값 ${expected.toInt()}km 와 3% 넘게 다르다",
            )
        }
    }

    @Test
    fun `날짜변경선을 넘는 경로는 두 조각으로 쪼개진다`() {
        val segments = Geo.greatCirclePath(Cities["tokyo"], Cities["losangeles"])
        assertTrue(segments.size >= 2, "태평양 횡단 경로가 지도에서 끊기지 않았다")
        assertTrue(segments.all { seg -> seg.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 } })
    }

    @Test
    fun `수요는 거리가 멀수록 줄고 큰 도시일수록 는다`() {
        val seoulTokyo = Demand.annualBase(Cities["seoul"], Cities["tokyo"]).total
        val seoulLima = Demand.annualBase(Cities["seoul"], Cities["lima"]).total
        assertTrue(seoulTokyo > seoulLima * 5, "가깝고 큰 시장이 훨씬 커야 한다")

        val nycLondon = Demand.annualBase(Cities["newyork"], Cities["london"]).total
        val nycLima = Demand.annualBase(Cities["newyork"], Cities["lima"]).total
        assertTrue(nycLondon > nycLima)
    }

    @Test
    fun `주요 노선 수요가 게임 스케일 안에 있다`() {
        // 간선 노선 하나가 여객기 두세 대(분기 5만 석 안팎)를 먹여 살리는 규모여야
        // 운임·편수 경쟁이 의미를 갖는다. Balance.DEMAND_K 주석 참고.
        //
        // 범위는 그 문장에서 되짚어 잡았다. 727 두 대를 서울–도쿄(1,150km)에 넣으면
        // 분기 약 5만 4천 석이므로, 간선의 분기 수요가 그 안팎(≒ 연 20만)이어야
        // 비로소 "두세 대짜리 노선"이다. 예전 범위(연 35만~80만)는 3~7대에 해당해
        // 수요가 공급을 압도했고, 그래서 좌석이 언제나 다 팔려 운임 경쟁이 죽어 있었다.
        val checks = listOf(
            Triple("seoul", "tokyo", 150_000.0..320_000.0),
            Triple("newyork", "london", 80_000.0..220_000.0),
            Triple("newyork", "losangeles", 100_000.0..260_000.0),
        )
        for ((a, b, range) in checks) {
            val d = Demand.annualBase(Cities[a], Cities[b]).total
            assertTrue(d in range, "$a-$b 연간 수요 ${d.toInt()} 명이 기대 범위 $range 밖이다")
        }

        // 변두리 노선은 간선의 몇 분의 일이어야 한다.
        val trunk = Demand.annualBase(Cities["seoul"], Cities["tokyo"]).total
        val thin = Demand.annualBase(Cities["nairobi"], Cities["lima"]).total
        assertTrue(thin < trunk / 4, "변두리 노선 수요 ${thin.toInt()} 가 간선 대비 너무 크다")
    }

    @Test
    fun `장거리 노선일수록 출장 비중이 높다`() {
        // 예전에는 반대로 잡혀 있었다 — 장거리가 레저 위주라, 수익계수가 낮은 손님만
        // 태우고 대형기 장거리 노선의 마진이 12% 까지 눌렸다. 값과 시간을 감당하는 쪽은
        // 출장 수요라고 보는 편이 채산에도 맞다 (BIZ_YIELD 1.90 vs LEI_YIELD 0.95).
        val short = Demand.annualBase(Cities["seoul"], Cities["tokyo"])
        val long = Demand.annualBase(Cities["seoul"], Cities["sydney"])
        assertTrue(
            long.businessShare > short.businessShare,
            "장거리에서 출장 비중이 더 높아야 한다 (단거리 ${short.businessShare}, 장거리 ${long.businessShare})",
        )
    }
}
