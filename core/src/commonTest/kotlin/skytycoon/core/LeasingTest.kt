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
        assertTrue(abs((cash - fee) - r.state.player.cash) < 1.0, "위약금이 청구되지 않았다")
        assertTrue(r.state.planes.none { it.id == plane.id }, "반납했는데 기체가 남아 있다")
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

    /** 판이 끝난 뒤까지 가는 계약은 위약금만 물고 끝난다 — 발주·확장과 같은 함정이다. */
    @Test
    fun `남은 기간보다 긴 계약은 맺을 수 없다`() {
        val s = fresh().let { it.copy(turn = it.totalTurns - 2) }
        val r = lease(s)
        assertFalse(r.ok, "판이 두 분기 남았는데 6년 계약을 맺었다")
    }
}
