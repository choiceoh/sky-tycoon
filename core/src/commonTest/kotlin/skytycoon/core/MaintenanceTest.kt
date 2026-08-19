package skytycoon.core

import skytycoon.core.data.AircraftCatalog
import skytycoon.core.model.Business
import skytycoon.core.model.BusinessType
import skytycoon.core.model.GameState
import skytycoon.core.model.Plane
import skytycoon.core.sim.Balance
import skytycoon.core.sim.Maintenance
import skytycoon.core.sim.NewGame
import skytycoon.core.sim.TurnEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 중정비 — 기재가 "영원히 같은 좌석을 내놓는 숫자"이길 그만두는 지점.
 *
 * 여기서 지켜야 하는 것은 두 가지다. 정비 중인 기체가 **손님을 태우지 않는 것**, 그리고
 * 그 분기가 **적자 분기로 기록되지 않는 것**. 뒤쪽을 놓치면 공항 폐쇄 때와 똑같이
 * (ClosureTest 참고) 노선 정리 규칙이 멀쩡한 간선을 정비 한 번에 접어 버린다.
 */
class MaintenanceTest {

    private fun fresh() = NewGame.create(seed = 2026, companyId = "hanseong")

    /** 플레이어가 실제로 굴리고 있는 노선 하나. */
    private fun flyingRoute(s: GameState) =
        s.routesOf(s.playerId).first { it.active && it.freq > 0 && s.assignedTo(it.id).isNotEmpty() }

    /** 그 노선의 기재를 전부 입고 직전까지 굴린 상태로 만든다. */
    private fun wearOut(s: GameState, routeId: Int): GameState {
        val ids = s.assignedTo(routeId).map { it.id }.toSet()
        return s.copy(
            planes = s.planes.map {
                if (it.id in ids) it.copy(hoursSinceCheck = Maintenance.intervalHours(it) + 1.0) else it
            },
        )
    }

    @Test
    fun `주기를 채운 기체는 그 분기에 뜨지 않는다`() {
        val s0 = fresh()
        val route = flyingRoute(s0)
        val s = TurnEngine.advance(wearOut(s0, route.id))

        val flown = s.routes.first { it.id == route.id }.last
        assertTrue(flown != null, "노선이 결산 전에 사라졌다")
        assertTrue(flown.seats <= 0.0, "정비 중인 기체가 좌석을 내놨다 (${flown.seats})")
        assertTrue(
            s.planes.filter { it.routeId == route.id }.all { it.checkUntilTurn == s0.turn },
            "입고 표시가 남지 않았다",
        )
    }

    /**
     * 못 뜬 분기는 **적자로 기록되지 않아야** 한다. AI 의 노선 정리는 `last.profit < 0`
     * 을 근거로 삼으므로, 여기에 원가가 실리면 정비 한 번이 곧 폐선이 된다.
     */
    @Test
    fun `정비로 못 뜬 분기를 적자로 적지 않는다`() {
        val s0 = fresh()
        val route = flyingRoute(s0)
        val s = TurnEngine.advance(wearOut(s0, route.id))
        val flown = s.routes.first { it.id == route.id }.last!!
        assertTrue(flown.cost <= 0.0, "뜨지도 않은 기체의 운항비가 청구됐다 (${flown.cost})")
        assertTrue(flown.profit >= 0.0, "정비 분기가 적자로 기록됐다 — 노선 정리가 이걸 근거로 삼는다")
    }

    @Test
    fun `정비를 마치면 시계가 0 에서 다시 시작한다`() {
        val s0 = fresh()
        val route = flyingRoute(s0)
        var s = TurnEngine.advance(wearOut(s0, route.id))
        val afterCheck = s.planes.filter { it.routeId == route.id }
        assertTrue(
            afterCheck.all { it.hoursSinceCheck == 0.0 },
            "입고했는데 시계가 그대로다: ${afterCheck.map { it.hoursSinceCheck }}",
        )

        // 다음 분기에는 다시 뜬다.
        s = TurnEngine.advance(s)
        val back = s.routes.first { it.id == route.id }.last!!
        assertTrue(back.seats > 0.0, "정비가 끝났는데도 뜨지 못한다")
        assertTrue(
            s.planes.filter { it.routeId == route.id }.all { it.hoursSinceCheck > 0.0 },
            "다시 뜬 분기의 비행시간이 쌓이지 않았다",
        )
    }

    @Test
    fun `중정비비는 입고 분기에 한 번만 청구된다`() {
        val s0 = fresh()
        val route = flyingRoute(s0)
        val worn = wearOut(s0, route.id)
        // 나머지 기단의 시계를 갓 정비한 상태로 돌려 둔다 — 안 그러면 다음 분기에
        // 다른 기체가 제 차례로 들어가 "또 청구됐다"로 잘못 읽힌다.
        val target = worn.assignedTo(route.id).map { it.id }.toSet()
        var s = worn.copy(
            planes = worn.planes.map {
                if (it.id in target) it else it.copy(hoursSinceCheck = 0.0, quartersSinceCheck = 0)
            },
        )

        s = TurnEngine.advance(s)
        assertTrue(s.player.lastResult!!.checkCost > 0.0, "입고했는데 중정비비가 0 이다")

        s = TurnEngine.advance(s)
        assertEquals(0.0, s.player.lastResult!!.checkCost, "정비가 끝난 분기에 또 청구됐다")
    }

    /**
     * 비행시간은 **실제로 난 만큼만** 쌓인다 — 세워 둔 기체에는 붙지 않는다.
     * 대신 달력은 그대로 가므로, 예비기도 언젠가는 들어간다 (공짜 보험이 아니다).
     */
    @Test
    fun `세워 둔 기체는 비행시간이 아니라 달력으로 늙는다`() {
        val s0 = fresh()
        val idle = s0.planesOf(s0.playerId).firstOrNull { it.routeId == null }
            ?: return // 창업 기단이 전부 배속됐다면 볼 것이 없다
        // 정비 차례가 돌아오지 않도록 시계를 되돌려 둔다 (입고하면 0 으로 리셋된다).
        val s1 = s0.copy(
            planes = s0.planes.map {
                if (it.id == idle.id) it.copy(hoursSinceCheck = 0.0, quartersSinceCheck = 0) else it
            },
        )
        val s = TurnEngine.advance(s1)
        val after = s.planes.first { it.id == idle.id }
        assertEquals(0.0, after.hoursSinceCheck, "세워 둔 기체에 비행시간이 붙었다")
        assertEquals(1, after.quartersSinceCheck, "세워 둔 기체의 달력이 멈췄다")
        assertEquals(idle.ageQuarters + 1, after.ageQuarters, "기령은 그대로 늘어야 한다")
    }

    /** 굴리지 않아도 달력만으로 들어간다. */
    @Test
    fun `덜 굴린 기체도 달력이 차면 들어간다`() {
        val s0 = fresh()
        val idle = s0.planesOf(s0.playerId).firstOrNull { it.routeId == null }
            ?: return
        val s1 = s0.copy(
            planes = s0.planes.map {
                if (it.id == idle.id) {
                    it.copy(hoursSinceCheck = 0.0, quartersSinceCheck = Maintenance.intervalQuarters(it))
                } else {
                    it
                }
            },
        )
        val s = TurnEngine.advance(s1)
        assertTrue(
            s.planes.first { it.id == idle.id }.checkUntilTurn == s0.turn,
            "비행시간이 0 이어도 달력이 찼으면 들어가야 한다",
        )
    }

    @Test
    fun `늙은 기체가 더 자주 들어간다`() {
        val t = AircraftCatalog["b727"]
        val young = Maintenance.intervalHours(t, 0)
        val old = Maintenance.intervalHours(t, 60)
        assertTrue(old < young, "기령이 주기를 줄이지 않는다 ($young → $old)")
        // 아무리 늙어도 바닥이 있다 — 없으면 노후기가 영구히 정비 중이 된다.
        val ancient = Maintenance.intervalHours(t, 400)
        assertTrue(
            ancient >= Balance.CHECK_INTERVAL_HOURS * Balance.CHECK_INTERVAL_MIN_RATIO - 1e-9,
            "주기가 바닥 아래로 내려갔다 ($ancient)",
        )
    }

    @Test
    fun `정비창을 가지면 중정비비가 싸다`() {
        val s = fresh()
        val plane = s.planesOf(s.playerId).first()
        val plain = s.player
        val withHangar = plain.copy(businesses = plain.businesses + Business(BusinessType.HANGAR, plain.home))
        assertTrue(
            Maintenance.checkCost(s, withHangar, plane) < Maintenance.checkCost(s, plain, plane),
            "정비창이 중정비비를 깎지 않는다",
        )
    }

    /**
     * 이 규칙이 만들려는 **선택**이 실제로 존재하는지 본다 — 예비기를 한 대 붙여 두면
     * 정비 분기에도 편수를 지킬 수 있어야 한다. 그러지 못하면 정비는 선택이 아니라
     * 그냥 무작위 세금이다.
     */
    @Test
    fun `예비기를 붙여 두면 정비 분기에도 좌석이 남는다`() {
        val s0 = fresh()
        val route = flyingRoute(s0)
        val onRoute = s0.assignedTo(route.id)
        val worn = wearOut(s0, route.id)

        // 같은 기종 예비기를 한 대 더 붙인다 (시계는 갓 정비를 마친 상태).
        val spare = Plane(
            id = worn.nextId,
            typeId = onRoute.first().typeId,
            airlineId = worn.playerId,
            ageQuarters = 4,
            routeId = route.id,
        )
        val withSpare = worn.copy(
            planes = worn.planes + spare,
            nextId = worn.nextId + 1,
            routes = worn.routes.map {
                if (it.id == route.id) it.copy(planeIds = it.planeIds + spare.id) else it
            },
        )

        val without = TurnEngine.advance(worn).routes.first { it.id == route.id }.last!!
        val with = TurnEngine.advance(withSpare).routes.first { it.id == route.id }.last!!
        assertTrue(without.seats <= 0.0, "예비기 없이도 떴다 — 준비 상태가 잘못됐다")
        assertTrue(with.seats > 0.0, "예비기를 붙였는데도 한 좌석도 못 냈다")
    }
}
