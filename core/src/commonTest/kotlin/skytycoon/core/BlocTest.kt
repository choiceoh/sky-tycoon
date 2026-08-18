package skytycoon.core

import skytycoon.core.data.AircraftCatalog
import skytycoon.core.sim.Actions
import skytycoon.core.sim.Command
import skytycoon.core.sim.NewGame
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 냉전기에는 양쪽이 서로의 기재를 못 샀다.
 *
 * 이 제약이 없으면 소련기가 값싸다는 이유만으로 전 세계에 팔려 나간다 — 실제로
 * 제약 없이 넣어 보니 브리타니아·리버티까지 Tu-154 를 굴려 서방 협동체가 통째로
 * 밀려났고(투폴레프 110대), 진영마다 기재가 다르다는 시대의 질감이 사라졌다.
 */
class BlocTest {

    @Test
    fun `서방 항공사는 구소련권 기종을 못 산다`() {
        val s = NewGame.create(companyId = "britannia")
        val r = Actions.execute(s, Command.BuyAircraft("britannia", "tu154", 1))
        assertTrue(!r.ok, "서방 항공사가 Tu-154 를 발주했다")
        assertTrue(
            AircraftCatalog.newFor(s.year, "london").none { it.bloc == "east" },
            "런던 기반 항공사의 신조기 목록에 구소련권 기종이 보인다",
        )
    }

    @Test
    fun `냉전기 동구권 항공사는 서방 기재를 못 산다`() {
        val s = NewGame.create(companyId = "soyuz")
        val r = Actions.execute(s, Command.BuyAircraft("soyuz", "b727", 1))
        assertTrue(!r.ok, "냉전기에 모스크바 항공사가 727 을 발주했다")
        val list = AircraftCatalog.newFor(s.year, "moscow")
        assertTrue(list.isNotEmpty(), "동구권이 살 수 있는 기종이 하나도 없다")
        assertTrue(list.all { it.bloc == "east" }, "냉전기 목록에 서방 기종이 섞였다")
    }

    @Test
    fun `냉전이 풀리면 양쪽 다 살 수 있다`() {
        val after = AircraftCatalog.IRON_CURTAIN_UNTIL + 1
        assertTrue(
            AircraftCatalog.newFor(after, "moscow").any { it.bloc != "east" },
            "냉전 이후에도 모스크바가 서방 기재를 못 산다",
        )
        // 소련기는 그 뒤로도 그 진영 전용이다 — 서방이 뒤늦게 Tu-204 를 사들이지 않는다.
        assertTrue(
            AircraftCatalog.newFor(after, "london").none { it.bloc == "east" },
            "냉전 이후 서방이 구소련권 기종을 살 수 있게 됐다",
        )
    }

    @Test
    fun `모스크바 항공사는 자국기로 판을 시작한다`() {
        val s = NewGame.create(companyId = "soyuz")
        val fleet = s.planesOf("soyuz")
        assertTrue(fleet.isNotEmpty(), "소유즈에 기재가 없다")
        assertTrue(
            fleet.all { AircraftCatalog[it.typeId].bloc == "east" },
            "소유즈가 서방기로 시작한다 (${fleet.map { it.typeId }.distinct()})",
        )
    }
}
