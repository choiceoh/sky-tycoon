package skytycoon.core

import skytycoon.core.data.AircraftCatalog
import skytycoon.core.model.GameState
import skytycoon.core.sim.Actions
import skytycoon.core.sim.Balance
import skytycoon.core.sim.Command
import skytycoon.core.sim.Economics
import skytycoon.core.sim.Leasing
import skytycoon.core.sim.NewGame
import skytycoon.core.sim.TurnEngine
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 운용리스 — 자본을 안 쓰는 대신 고정비를 지는 길.
 *
 * 여기서 지켜야 하는 불변식은 하나로 모인다: **빌린 기체는 내 자산이 아니다.**
 * 기단 가치·상각·매각 중 한 군데라도 리스기를 세면, 목돈 한 푼 없이 자기자본을 부풀려
 * 차입 한도와 최종 순위를 살 수 있다 (중고기 장부가에서 한 번 막았던 것과 같은 구멍).
 */
class LeasingTest {

    private fun fresh(): GameState = NewGame.create(seed = 2026, companyId = "hanseong")
    private val term = Balance.LEASE_TERMS.max()

    private fun lease(s: GameState, typeId: String = "b727", count: Int = 1, quarters: Int = term) =
        Actions.execute(s, Command.LeaseAircraft(s.playerId, typeId, count, quarters))

    @Test
    fun `빌린 기체는 기단 가치에 잡히지 않는다`() {
        val s = fresh()
        val before = Economics.equity(s, s.player)
        val r = lease(s)
        assertTrue(r.ok, r.message)
        val after = Economics.equity(r.state, r.state.player)
        assertTrue(
            abs(after - before) < 1.0,
            "리스 한 대로 자기자본이 $before → $after 로 움직였다 — 자본 없이 순위를 살 수 있다",
        )
    }

    @Test
    fun `빌린 기체는 상각하지 않는다`() {
        val s = fresh()
        val before = Economics.depreciation(s.planesOf(s.playerId))
        val leased = lease(s).state
        assertEquals(
            before,
            Economics.depreciation(leased.planesOf(leased.playerId)),
            "리스기까지 상각하면 같은 기체값을 리스료와 두 번 턴다",
        )
    }

    @Test
    fun `빌린 기체는 팔 수 없다`() {
        val s = lease(fresh()).state
        val plane = s.planesOf(s.playerId).first { it.leased }
        val r = Actions.execute(s, Command.SellAircraft(s.playerId, plane.id))
        assertFalse(r.ok, "빌린 기체를 팔았다 — 빌려서 되파는 것만으로 현금을 찍을 수 있다")
        assertTrue("리스" in r.message, "실패 사유가 리스여야 한다: ${r.message}")
    }

    /** 목돈이 들지 않는 것이 리스의 값이다 — 계약할 때 현금이 나가면 안 된다. */
    @Test
    fun `리스는 목돈을 쓰지 않고 즉시 인도된다`() {
        val s = fresh()
        val cash = s.player.cash
        val r = lease(s)
        assertEquals(cash, r.state.player.cash, "계약하는 자리에서 현금이 나갔다")
        assertEquals(1, r.state.planesOf(r.state.playerId).count { it.leased }, "기체가 바로 들어오지 않았다")
        assertTrue(r.state.orders.none { it.airlineId == r.state.playerId }, "리스는 발주 대기가 없어야 한다")
    }

    @Test
    fun `리스료는 분기마다 나간다`() {
        val s = lease(fresh(), count = 1).state
        val rate = s.planesOf(s.playerId).first { it.leased }.leaseRate
        val after = TurnEngine.advance(s)
        val billed = after.player.lastResult!!.leaseCost
        assertTrue(abs(billed - rate) < 1.0, "리스료가 $rate 인데 $billed 이 청구됐다")
    }

    /** 세워 둬도 나간다 — 그게 고정비라는 뜻이고, 리스의 위험이 거기 있다. */
    @Test
    fun `노선에 붙이지 않아도 리스료는 나간다`() {
        val s = lease(fresh()).state
        val plane = s.planesOf(s.playerId).first { it.leased }
        assertEquals(null, plane.routeId, "준비 상태가 잘못됐다 — 새 리스기는 유휴여야 한다")
        val after = TurnEngine.advance(s)
        assertTrue(after.player.lastResult!!.leaseCost > 0.0, "세워 둔 리스기의 리스료가 빠졌다")
    }

    @Test
    fun `계약이 끝나면 돌아간다`() {
        var s = lease(fresh(), quarters = Balance.LEASE_TERMS.min()).state
        val id = s.planesOf(s.playerId).first { it.leased }.id
        repeat(Balance.LEASE_TERMS.min()) { s = TurnEngine.advance(s) }
        assertTrue(s.planes.none { it.id == id }, "계약이 끝났는데 기체가 남아 있다")
        assertTrue(
            s.routes.none { id in it.planeIds },
            "돌아간 기체가 노선 배속에 남아 있다 — 유령 기재로 좌석이 잡힌다",
        )
    }

    @Test
    fun `중도 반납에는 위약금이 붙는다`() {
        val s = lease(fresh()).state
        val plane = s.planesOf(s.playerId).first { it.leased }
        val fee = Leasing.breakFee(s, plane)
        assertTrue(fee > 0.0, "기간이 잔뜩 남았는데 위약금이 0 이다")
        val cash = s.player.cash
        val r = Actions.execute(s, Command.ReturnLease(s.playerId, plane.id))
        assertTrue(r.ok, r.message)
        assertTrue(r.state.planes.none { it.id == plane.id }, "반납했는데 기체가 남아 있다")

        // 위약금은 현금에서 바로 빼지 않는다 — 그러면 순익과 실제 현금 변동이 어긋나
        // 그 순익으로 매기는 주가까지 부풀려진다. 파업 합의금과 같은 자리에 얹는다.
        assertEquals(cash, r.state.player.cash, "위약금이 결산을 거치지 않고 현금에서 빠져나갔다")
        assertTrue(abs(r.state.player.pendingCharges - fee) < 1.0, "위약금이 일시 비용에 잡히지 않았다")

        val settled = TurnEngine.advance(r.state)
        val result = settled.player.lastResult!!
        assertTrue(abs(result.extraordinaryCost - fee) < 1.0, "위약금이 결산에 실리지 않았다")
        // 순익과 현금이 같은 이야기를 해야 한다 — 현금흐름 = 순익 + 감가상각.
        assertTrue(
            abs((r.state.player.cash + result.net + result.depreciation) - result.cash) < 1.0,
            "위약금을 낸 분기의 순익과 현금 변동이 어긋난다",
        )
    }

    /** 짧게 빌릴수록 분기 요율이 비싸야 한다 — 유연함에는 값이 붙는다. */
    @Test
    fun `짧은 계약이 분기당 더 비싸다`() {
        val t = AircraftCatalog["b727"]
        val short = Leasing.quarterlyRate(t, Balance.LEASE_TERMS.min())
        val long = Leasing.quarterlyRate(t, Balance.LEASE_TERMS.max())
        assertTrue(short > long, "짧게 빌리는 값이 안 붙는다 ($short vs $long)")
    }

    /**
     * 총액으로는 사는 쪽이 싸야 한다. 반대가 되면 리스가 지배 전략이 되어,
     * 기재를 사는 결정 자체가 사라진다.
     */
    @Test
    fun `총액으로는 사는 쪽이 싸다`() {
        val t = AircraftCatalog["b727"]
        val total = Leasing.quarterlyRate(t, term) * term
        assertTrue(total > t.price * term / Balance.DEPRECIATION_QUARTERS, "리스가 상각보다도 싸다")
        // 그렇다고 터무니없이 비싸도 안 된다 — 아무도 안 쓰는 수단이 된다.
        assertTrue(total < t.price * 1.2, "6년 리스료가 기체값을 넘는다 (${total / t.price}배)")
    }

    /** 상한이 없으면 기단 전체를 리스로 채우고 자산 없는 회사로 숨는 판이 최적이 된다. */
    @Test
    fun `기단의 일정 비율까지만 빌릴 수 있다`() {
        var s = fresh()
        val room = Leasing.leaseRoom(s, s.player)
        assertTrue(room >= 1, "창업 기단으로도 한 대는 빌릴 수 있어야 한다")
        val filled = Actions.execute(s, Command.LeaseAircraft(s.playerId, "b727", room, term))
        assertTrue(filled.ok, filled.message)
        s = filled.state
        val over = Actions.execute(s, Command.LeaseAircraft(s.playerId, "b727", 1, term))
        assertFalse(over.ok, "한도를 넘겨 빌렸다")
        assertTrue("한도" in over.message, "실패 사유가 한도여야 한다: ${over.message}")
    }

    /**
     * 한도까지 빌린 뒤 **분모가 된 소유기를 팔아** 상한을 뒤에서 무너뜨릴 수 없어야 한다.
     * 반복하면 기단 전체가 리스가 되어, 자산 없는 회사로 숨는 판이 다시 열린다.
     */
    @Test
    fun `소유기를 팔아 리스 한도를 우회할 수 없다`() {
        var s = fresh()
        val room = Leasing.leaseRoom(s, s.player)
        s = Actions.execute(s, Command.LeaseAircraft(s.playerId, "b727", room, term)).state

        // 배속을 푼 소유기 하나를 팔아 본다.
        val owned = s.planesOf(s.playerId).first { !it.leased }
        s = Actions.execute(s, Command.CloseRoute(s.playerId, owned.routeId ?: -1)).state
        val idle = s.planesOf(s.playerId).first { !it.leased && it.routeId == null }
        val r = Actions.execute(s, Command.SellAircraft(s.playerId, idle.id))
        assertFalse(r.ok, "소유기를 팔아 리스 비중이 한도를 넘겼다")
        assertTrue("한도" in r.message, "실패 사유가 한도여야 한다: ${r.message}")
    }

    /**
     * 계약이 끝나 기재가 하나도 안 남은 노선은 **세워야** 한다.
     *
     * 편수와 active 를 그대로 두면 좌석은 0 인데 슬롯은 계속 물고 임차료를 낸다 —
     * 게다가 노선 정리는 좌석 0 인 분기를 판단 근거에서 빼므로 영영 남는다.
     */
    @Test
    fun `리스가 끝나 기재가 없어진 노선은 멈춘다`() {
        val short = Balance.LEASE_TERMS.min()
        var s = lease(fresh(), quarters = short).state
        val leased = s.planesOf(s.playerId).first { it.leased }

        // 기존 노선 하나를 **리스기 한 대만으로** 굴리게 바꾼다. 계약이 끝나면
        // 그 노선에는 기재가 하나도 남지 않는다.
        val routeId = s.routesOf(s.playerId).first { it.active && it.freq > 0 }.id
        val assigned = Actions.execute(s, Command.AssignPlanes(s.playerId, routeId, listOf(leased.id)))
        assertTrue(assigned.ok, assigned.message)
        s = assigned.state
        val home = s.route(routeId).from
        val slotsBefore = s.usedSlots(s.playerId, home)

        repeat(short) { s = TurnEngine.advance(s) }
        val route = s.routes.firstOrNull { it.id == routeId }
        assertTrue(route != null, "노선이 통째로 사라졌다 — 슬롯 반납 판단을 플레이어가 해야 한다")
        assertFalse(route.active, "기재가 없는데 노선이 살아 있다")
        assertEquals(0, route.freq, "기재가 없는데 편수가 남아 있다")
        assertTrue(
            s.usedSlots(s.playerId, home) < slotsBefore,
            "유령 노선이 슬롯을 계속 물고 있다",
        )
    }

    /** 판이 끝난 뒤까지 가는 계약은 위약금만 물고 끝난다 — 발주·확장과 같은 함정이다. */
    @Test
    fun `남은 기간보다 긴 계약은 맺을 수 없다`() {
        val s = fresh().let { it.copy(turn = it.totalTurns - 2) }
        val r = lease(s)
        assertFalse(r.ok, "판이 두 분기 남았는데 6년 계약을 맺었다")
    }
}
