package skytycoon.core

import skytycoon.core.data.AircraftCatalog
import skytycoon.core.sim.Actions
import skytycoon.core.sim.Command
import skytycoon.core.sim.NewGame
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 진영은 셋이다 — 미국기와 구소련기는 서로 넘어가지 못하고, **유럽기는 양쪽 다** 산다.
 * 유럽 제작사가 두 진영 사이의 중립 공급처 노릇을 한다.
 *
 * 제약이 없으면 소련기가 값싸다는 이유만으로 전 세계에 팔려 나간다 — 실제로 넣어
 * 보니 브리타니아·리버티까지 Tu-154 를 굴려 서방 협동체가 통째로 밀려났다
 * (투폴레프 110대). 진영마다 기재가 다르다는 시대의 질감이 거기서 사라진다.
 */
class BlocTest {

    @Test
    fun `서방 항공사는 구소련권 기종을 못 산다`() {
        val s = NewGame.create(companyId = "britannia")
        assertTrue(!Actions.execute(s, Command.BuyAircraft("britannia", "tu154", 1)).ok, "서방이 Tu-154 를 발주했다")
        assertTrue(
            AircraftCatalog.newFor(s.year, "london").none { it.bloc == "east" },
            "런던 기반 항공사의 목록에 구소련권 기종이 보인다",
        )
    }

    @Test
    fun `냉전기 동구권은 미국기를 못 사고 유럽기는 살 수 있다`() {
        val s = NewGame.create(companyId = "soyuz")
        assertTrue(!Actions.execute(s, Command.BuyAircraft("soyuz", "b727", 1)).ok, "냉전기에 모스크바가 727 을 발주했다")

        val list = AircraftCatalog.newFor(s.year, "moscow")
        assertTrue(list.none { it.bloc == "west" }, "냉전기 목록에 미국기가 섞였다")
        // 유럽기는 중립이라 이 시점에도 살 수 있어야 한다.
        val euro = list.filter { it.bloc.isEmpty() }
        assertTrue(euro.isNotEmpty(), "냉전기 동구권이 유럽기를 하나도 못 산다 — 중립 공급처가 없다")
        val pick = euro.first()
        assertTrue(
            Actions.execute(s, Command.BuyAircraft("soyuz", pick.id, 1)).ok,
            "모스크바가 유럽기(${pick.name})를 못 샀다",
        )
    }

    @Test
    fun `냉전이 풀리면 동구권도 미국기를 산다`() {
        val after = AircraftCatalog.IRON_CURTAIN_UNTIL + 1
        assertTrue(
            AircraftCatalog.newFor(after, "moscow").any { it.bloc == "west" },
            "냉전 이후에도 모스크바가 미국기를 못 산다",
        )
        // 소련기는 그 뒤로도 그 진영 전용이다 — 서방이 뒤늦게 Tu-204 를 사들이지 않는다.
        assertTrue(
            AircraftCatalog.newFor(after, "london").none { it.bloc == "east" },
            "냉전 이후 서방이 구소련권 기종을 살 수 있게 됐다",
        )
    }

    @Test
    fun `유럽기는 어느 시대에도 양쪽 다 산다`() {
        for (year in listOf(1970, 1985, 2000, 2020)) {
            val eu = AircraftCatalog.newFor(year).filter { it.bloc.isEmpty() }
            if (eu.isEmpty()) continue
            for (home in listOf("london", "moscow")) {
                val seen = AircraftCatalog.newFor(year, home).filter { it.bloc.isEmpty() }
                assertTrue(
                    seen.size == eu.size,
                    "${year}년 $home 에서 유럽기가 걸러졌다 (${eu.size} → ${seen.size})",
                )
            }
        }
    }

    @Test
    fun `모스크바 항공사는 자국기로 판을 시작한다`() {
        val s = NewGame.create(companyId = "soyuz")
        val fleet = s.planesOf("soyuz")
        assertTrue(fleet.isNotEmpty(), "소유즈에 기재가 없다")
        assertTrue(
            fleet.all { AircraftCatalog[it.typeId].bloc == "east" },
            "소유즈가 자국기로 시작하지 않는다 (${fleet.map { it.typeId }.distinct()})",
        )
    }

    /**
     * 시나리오 연도에 없는 기종을 대체할 때 좌석수만 보면 항속거리가 무너진다.
     * 2012 시나리오에서 소유즈의 Tu-134(84석·2,900km)가 ATR 72(74석·**1,500km**)로
     * 바뀌어, 창업 슬롯이 깔린 목적지 어디에도 못 가는 기재를 들고 시작했었다.
     */
    @Test
    fun `창업 기재는 원래 기종만큼 날 수 있다`() {
        for (sc in listOf("goldenage", "megacarrier", "modern")) {
            val s = NewGame.create(scenarioId = sc, companyId = "soyuz")
            val home = s.airline("soyuz").home
            val reach = s.airline("soyuz").slots.keys.filter { it != home }
                .minOf { skytycoon.core.sim.Geo.distance(home, it) }
            for (p in s.planesOf("soyuz")) {
                val t = AircraftCatalog[p.typeId]
                assertTrue(
                    t.range >= reach,
                    "$sc: 소유즈 창업 기재 ${t.name}(${t.range}km)이 가장 가까운 취항 후보" +
                        "(${reach.toInt()}km)에도 못 간다",
                )
            }
        }
    }
}
