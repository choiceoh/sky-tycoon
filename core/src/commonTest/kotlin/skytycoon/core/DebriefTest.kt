package skytycoon.core

import skytycoon.core.model.QuarterResult
import skytycoon.core.sim.Debrief
import skytycoon.core.sim.DebriefTone
import skytycoon.core.sim.NewGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 결산 해설. 지키는 것은 **원인이 실제로 지목되는가**와 **잔돈까지 떠들지 않는가**다.
 */
class DebriefTest {

    private fun stateWith(vararg results: QuarterResult) = run {
        val base = NewGame.create(seed = 3, companyId = "hanseong")
        base.copy(
            airlines = base.airlines.map {
                if (it.id == base.playerId) it.copy(results = results.toList()) else it
            },
        )
    }

    private fun quarter(
        turn: Int,
        revenue: Double = 100_000_000.0,
        fuel: Double = 20_000_000.0,
        net: Double = 10_000_000.0,
        pax: Double = 200_000.0,
        oil: Double = 0.30,
        rpk: Double = 800_000_000.0,
        asks: Double = 1_000_000_000.0,
    ) = QuarterResult(
        turn = turn,
        revenue = revenue,
        fuelCost = fuel,
        net = net,
        pax = pax,
        rpk = rpk,
        asks = asks,
        oil = oil,
    )

    @Test
    fun firstQuarterExplainsMarginInsteadOfComparing() {
        val current = quarter(0)
        val s = stateWith(current)
        val lines = Debrief.lines(s, s.player, current)
        assertEquals(1, lines.size)
        assertTrue(lines.first().text.contains("첫 결산"), lines.first().text)
    }

    /** 유가가 오른 분기의 연료비 급증은 유가를 원인으로 지목해야 한다. */
    @Test
    fun blamesOilForFuelSpike() {
        val prev = quarter(0, fuel = 20_000_000.0, oil = 0.30)
        val now = quarter(1, fuel = 34_000_000.0, oil = 0.51, net = -4_000_000.0)
        val s = stateWith(prev, now)

        val fuelLine = Debrief.lines(s, s.player, now).firstOrNull { it.text.contains("연료비") }
        assertTrue(fuelLine != null, "연료비가 70% 뛰었는데 언급이 없다")
        assertTrue(fuelLine.text.contains("항공유"), "유가를 원인으로 짚지 않았다: ${fuelLine.text}")
        assertEquals(DebriefTone.BAD, fuelLine.tone)
    }

    /** 유가가 없던 시절 세이브(0)에서는 원인을 지어내지 않는다. */
    @Test
    fun staysQuietAboutOilOnLegacySaves() {
        val prev = quarter(0, fuel = 20_000_000.0, oil = 0.0)
        val now = quarter(1, fuel = 34_000_000.0, oil = 0.0)
        val s = stateWith(prev, now)

        val fuelLine = Debrief.lines(s, s.player, now).firstOrNull { it.text.contains("연료비") }
        assertTrue(fuelLine != null)
        assertTrue(!fuelLine.text.contains("항공유"), "유가를 모르면서 원인을 붙였다: ${fuelLine.text}")
    }

    /** 같은 매출 하락이라도 손님이 준 것과 값이 눌린 것은 다른 이야기다. */
    @Test
    fun separatesVolumeFromPrice() {
        val prev = quarter(0, revenue = 100_000_000.0, pax = 200_000.0)
        // 손님은 그대로인데 단가만 20% 눌렸다.
        val now = quarter(1, revenue = 80_000_000.0, pax = 200_000.0, net = -2_000_000.0)
        val s = stateWith(prev, now)
        val lines = Debrief.lines(s, s.player, now)

        assertTrue(lines.any { it.text.contains("단가") }, "단가 하락을 못 짚었다: $lines")
        assertTrue(lines.none { it.text.contains("수송객") }, "손님은 그대로인데 수송객을 원인으로 댔다: $lines")
    }

    @Test
    fun namesPassengerLossWhenVolumeDrops() {
        val prev = quarter(0, revenue = 100_000_000.0, pax = 200_000.0)
        val now = quarter(1, revenue = 75_000_000.0, pax = 150_000.0, net = -3_000_000.0)
        val s = stateWith(prev, now)
        assertTrue(Debrief.lines(s, s.player, now).any { it.text.contains("수송객") })
    }

    /** 부대사업 수입이 오르내려도 그건 승객 단가가 아니다. */
    @Test
    fun doesNotBlameSideBusinessOnFares() {
        val prev = quarter(0, revenue = 100_000_000.0, pax = 200_000.0)
        // 손님도 운임도 그대로인데 라운지를 지어 부대사업 수입만 늘었다.
        val now = quarter(1, revenue = 100_000_000.0, pax = 200_000.0, net = 18_000_000.0)
            .copy(businessIncome = 8_000_000.0)
        val s = stateWith(prev, now)
        val lines = Debrief.lines(s, s.player, now)

        assertTrue(lines.none { it.text.contains("단가") }, "부대사업 수입을 단가 탓으로 돌렸다: $lines")
        assertTrue(lines.any { it.text.contains("부대사업") }, "부대사업 증가를 짚지 못했다: $lines")
    }

    /** 운항이 끊겨 매출이 0 인 분기에 "순익 +$0" 을 늘어놓지 않는다. */
    @Test
    fun staysQuietWhenGrounded() {
        val prev = quarter(0, revenue = 0.0, fuel = 0.0, net = 0.0, pax = 0.0, rpk = 0.0, asks = 0.0)
        val now = quarter(1, revenue = 0.0, fuel = 0.0, net = 0.0, pax = 0.0, rpk = 0.0, asks = 0.0)
        val s = stateWith(prev, now)
        val lines = Debrief.lines(s, s.player, now)

        assertEquals(1, lines.size, "변한 것이 없는데 ${lines.size}줄이 나왔다: $lines")
        assertEquals(DebriefTone.FLAT, lines.first().tone)
    }

    /** 손님을 한 명도 못 태운 분기의 원인은 "운항 정지" 그 자체다 — 침묵하면 안 된다. */
    @Test
    fun namesTheStopWhenTrafficGoesToZero() {
        val prev = quarter(0, revenue = 100_000_000.0, pax = 200_000.0)
        val now = quarter(1, revenue = 0.0, pax = 0.0, net = -40_000_000.0, rpk = 0.0, asks = 0.0)
        val s = stateWith(prev, now)
        val lines = Debrief.lines(s, s.player, now)

        assertTrue(lines.any { it.text.contains("운항이 끊겨") }, "운항 정지를 짚지 못했다: $lines")
    }

    /** 잔돈 수준으로 움직인 분기는 조용해야 한다 — 매번 네 줄을 채우면 아무도 안 읽는다. */
    @Test
    fun saysNothingWhenNothingMoved() {
        val prev = quarter(0)
        val now = quarter(1, revenue = 100_400_000.0, fuel = 20_100_000.0, net = 10_200_000.0)
        val s = stateWith(prev, now)
        val lines = Debrief.lines(s, s.player, now)

        assertEquals(1, lines.size, "변화가 없는데 ${lines.size}줄이 나왔다: $lines")
        assertEquals(DebriefTone.FLAT, lines.first().tone)
    }

    /**
     * 노선망 줄은 **지금 노선망**을 읽으므로, 결산을 닫고 노선을 열거나 닫으면 근거가
     * 발밑에서 바뀐다. 언제든 다시 그려지는 화면에서는 끌 수 있어야 한다.
     */
    @Test
    fun canLeaveOutTheMutableNetworkLines() {
        val prev = quarter(0, rpk = 800_000_000.0, asks = 1_000_000_000.0)
        val now = quarter(1, rpk = 400_000_000.0, asks = 1_000_000_000.0, net = -5_000_000.0)
        val s = stateWith(prev, now)

        assertTrue(
            Debrief.lines(s, s.player, now).any { it.text.contains("탑승률") },
            "탑승률이 40%p 떨어졌는데 결산 직후에도 언급이 없다",
        )
        val moneyOnly = Debrief.lines(s, s.player, now, includeNetwork = false)
        assertTrue(moneyOnly.none { it.text.contains("탑승률") }, "노선망 줄이 빠지지 않았다: $moneyOnly")
        assertTrue(moneyOnly.first().text.contains("순익"), "금액 줄은 그대로 남아야 한다: $moneyOnly")
    }

    /** 줄 수 상한을 지킨다. */
    @Test
    fun respectsLimit() {
        val prev = quarter(0)
        val now = quarter(
            1,
            revenue = 40_000_000.0,
            fuel = 45_000_000.0,
            net = -30_000_000.0,
            pax = 90_000.0,
            oil = 0.62,
            rpk = 400_000_000.0,
        )
        val s = stateWith(prev, now)
        assertTrue(Debrief.lines(s, s.player, now, limit = 3).size <= 3)
    }

    /** 첫 줄은 언제나 총평이다 — 나머지는 그 아래 원인이다. */
    @Test
    fun leadsWithTheBottomLine() {
        val prev = quarter(0, net = 10_000_000.0)
        val now = quarter(1, net = -20_000_000.0, fuel = 40_000_000.0, oil = 0.55)
        val s = stateWith(prev, now)
        val first = Debrief.lines(s, s.player, now).first()

        assertTrue(first.text.contains("순익"), first.text)
        assertEquals(DebriefTone.BAD, first.tone)
        assertEquals(-30_000_000.0, first.amount)
        assertTrue(first.signed, "증감인데 부호를 안 붙인다")
    }
}
