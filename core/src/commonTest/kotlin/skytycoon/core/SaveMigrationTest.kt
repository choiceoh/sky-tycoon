package skytycoon.core

import skytycoon.core.save.Save
import skytycoon.core.data.AircraftCatalog
import skytycoon.core.sim.Actions
import skytycoon.core.sim.Economics
import skytycoon.core.sim.Maintenance
import skytycoon.core.sim.NewGame
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 옛 세이브를 지금 규칙으로 여는 일.
 *
 * 장부가가 "카탈로그 가격 × valueMul" 이라, 카탈로그 가격을 손대면 **이미 산 기재까지
 * 소급해서** 값이 바뀐다. 불러오기만 해도 자기자본이 부풀거나, 산 값보다 비싸게
 * 되팔 수 있게 된다.
 */
class SaveMigrationTest {

    /** 버전을 새기기 전 세이브를 흉내 낸다 — 그때는 formatVersion 자체가 없었다. */
    private fun asLegacy(json: String) = json.replace(
        "\"formatVersion\":${Save.FORMAT_VERSION}",
        "\"formatVersion\":0",
    )

    @Test
    fun legacySaveKeepsWhatItPaidForItsFleet() {
        val fresh = NewGame.create(seed = 5, companyId = "hanseong")
        val legacyJson = asLegacy(Save.encode(fresh))
        assertTrue(legacyJson.contains("\"formatVersion\":0"), "옛 세이브를 만들지 못했다")

        val loaded = Save.decode(legacyJson)
        for (p in loaded.planes) {
            val expected = 1.0 / AircraftCatalog.priceMultiplier(AircraftCatalog[p.typeId])
            assertTrue(
                abs(p.priceMul - expected) < 1e-9,
                "옛 기재의 가격 체계가 유지되지 않았다: ${p.priceMul} (기대 $expected)",
            )
            // 중고 할인은 가격 체계와 다른 축이라 건드리면 안 된다.
            assertTrue(abs(p.valueMul - 1.0) < 1e-9, "중고 할인 기준까지 손댔다: ${p.valueMul}")
        }

        // 불러오기만으로 기단 가치가 뛰면 안 된다 — 그 값이 곧 자기자본이고 차입 한도다.
        // 기대값은 카탈로그가 배수를 먹기 전의 값, 즉 기종별 배수를 되돌린 합이다.
        val expectedValue = loaded.planesOf(loaded.playerId).sumOf { p ->
            val t = AircraftCatalog[p.typeId]
            t.price / AircraftCatalog.priceMultiplier(t) * AircraftCatalog.residualRatio(p.ageQuarters)
        }
        val after = Economics.fleetValue(loaded.planesOf(loaded.playerId))
        assertTrue(
            abs(after - expectedValue) < 1.0,
            "옛 세이브를 여니 기단 가치가 $expectedValue → $after 로 바뀌었다",
        )
    }

    /** 인도 대기 중인 발주도 같은 기준을 물려받아야 한다. */
    @Test
    fun legacyOrdersKeepTheirCostBasis() {
        val fresh = NewGame.create(seed = 5, companyId = "hanseong")
        val withOrder = fresh.copy(
            orders = listOf(
                skytycoon.core.model.Order(
                    id = 9001,
                    airlineId = fresh.playerId,
                    typeId = "b727",
                    count = 2,
                    deliverTurn = fresh.turn + 2,
                ),
            ),
        )
        val loaded = Save.decode(asLegacy(Save.encode(withOrder)))
        assertEquals(1, loaded.orders.size)
        val expected = 1.0 / AircraftCatalog.priceMultiplier(AircraftCatalog["b727"])
        assertTrue(
            abs(loaded.orders.first().priceMul - expected) < 1e-9,
            "옛 발주가 새 가격으로 잡힌다: ${loaded.orders.first().priceMul}",
        )
    }

    /** 지금 형식의 세이브는 손대지 않는다 — 열 때마다 깎이면 안 된다. */
    @Test
    fun currentSavesRoundTripUntouched() {
        val fresh = NewGame.create(seed = 5, companyId = "hanseong")
        var s = Save.decode(Save.encode(fresh))
        repeat(3) { s = Save.decode(Save.encode(s)) }

        for (p in s.planes) {
            assertTrue(abs(p.priceMul - 1.0) < 1e-9, "여닫을 때마다 가격 체계가 깎인다: ${p.priceMul}")
        }
        assertEquals(Save.FORMAT_VERSION, s.formatVersion)
    }

    /**
     * 가격 체계는 **값이 걸리는 모든 곳**에 걸려야 한다. 장부에만 새기고 매각가·선급금이
     * 새 카탈로그를 그대로 쓰면, 옛 기체를 산 값보다 비싸게 되팔 수 있다.
     */
    @Test
    fun legacyBasisReachesSalePriceAndPrepaidOrders() {
        val fresh = NewGame.create(seed = 5, companyId = "hanseong")
        val loaded = Save.decode(asLegacy(Save.encode(fresh)))
        val plane = loaded.planesOf(loaded.playerId).first()
        val type = AircraftCatalog[plane.typeId]

        val sold = Actions.sellPrice(type, plane.ageQuarters, plane.priceMul)
        val paidWhenNew = type.price / AircraftCatalog.priceMultiplier(type)
        assertTrue(sold < paidWhenNew, "옛 기체를 산 값($paidWhenNew)보다 비싸게($sold) 판다")

        // 인도 대기 중인 발주의 선급금도 그 시절 값으로 잡혀야 한다.
        val order = skytycoon.core.model.Order(
            id = 9002,
            airlineId = loaded.playerId,
            typeId = "b747_100",
            count = 2,
            deliverTurn = loaded.turn + 2,
        )
        val legacyOrder = order.copy(priceMul = 1.0 / AircraftCatalog.priceMultiplier(AircraftCatalog["b747_100"]))
        val withNew = loaded.copy(orders = listOf(order))
        val withLegacy = loaded.copy(orders = listOf(legacyOrder))
        val me = loaded.player
        assertTrue(
            Economics.equity(withLegacy, me) < Economics.equity(withNew, me),
            "옛 발주의 선급금이 새 가격으로 잡힌다",
        )
    }

    /**
     * 정비 시계가 없던 시절의 세이브를 그냥 열면 기본값 0 이 되어 **기단 전체가 같은
     * 분기에 입고**된다. 20년 굴린 판이 불러오기 한 번에 노선망이 통째로 주저앉는
     * 시한폭탄을 떠안는 셈이라, 열면서 주기 안으로 흩어 놓아야 한다.
     */
    @Test
    fun legacyFleetDoesNotAllEnterCheckOnTheSameQuarter() {
        val fresh = NewGame.create(seed = 5, companyId = "hanseong")
        // 정비 시계가 없던 세이브 = 모든 기체가 0
        val zeroed = fresh.copy(planes = fresh.planes.map { it.copy(hoursSinceCheck = 0.0) })
        val loaded = Save.decode(asLegacy(Save.encode(zeroed)))

        val clocks = loaded.planes.map { Maintenance.progress(it) }
        assertTrue(clocks.isNotEmpty())
        assertTrue(clocks.all { it in 0.0..1.0 }, "불러오자마자 입고 대상이 생겼다: $clocks")
        assertTrue(clocks.distinct().size > clocks.size / 2, "정비 시계가 흩어지지 않았다 (기단이 한꺼번에 입고된다)")

        // 같은 세이브를 두 번 열면 같은 결과여야 한다 (난수를 기재 id 로 고정한 이유).
        val again = Save.decode(asLegacy(Save.encode(zeroed)))
        assertEquals(
            loaded.planes.map { it.hoursSinceCheck },
            again.planes.map { it.hoursSinceCheck },
            "같은 세이브를 열 때마다 정비 시계가 달라진다",
        )
    }

    /** 지금 형식의 세이브는 정비 시계를 다시 흩뿌리지 않는다 — 열 때마다 리셋되면 안 된다. */
    @Test
    fun currentSavesKeepTheirCheckClocks() {
        val fresh = NewGame.create(seed = 5, companyId = "hanseong")
        val before = fresh.planes.associate { it.id to it.hoursSinceCheck }
        val loaded = Save.decode(Save.encode(fresh))
        for (p in loaded.planes) {
            assertTrue(
                abs(p.hoursSinceCheck - (before[p.id] ?: -1.0)) < 1e-9,
                "여닫으면서 정비 시계가 바뀌었다: ${p.hoursSinceCheck}",
            )
        }
    }
}
