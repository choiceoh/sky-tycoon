package skytycoon.core.sim

import skytycoon.core.data.Cities
import skytycoon.core.model.Airline
import skytycoon.core.model.GameState
import skytycoon.core.model.QuarterResult
import kotlin.math.abs
import kotlin.math.roundToInt

enum class DebriefTone { GOOD, BAD, FLAT }

/**
 * 결산 한 줄. 금액은 [amount] 에 담고 문장에는 `{}` 자리만 비워 둔다 — "1.2억" 같은
 * 화폐 표기는 화면의 몫이라 core 가 정할 일이 아니다.
 *
 * @param signed 금액에 부호를 붙여 보여줄지 (증감이면 붙이고, 규모면 붙이지 않는다).
 */
data class DebriefLine(
    val text: String,
    val tone: DebriefTone,
    val amount: Double? = null,
    val signed: Boolean = false,
)

/**
 * 분기 결산을 **읽히는 문장**으로 옮긴다.
 *
 * 계정과목을 스무 줄 늘어놓으면 순익이 반토막 나도 연료 탓인지 경쟁사 진입 탓인지
 * 스스로 표를 뒤져야 한다. 그래서 지난 분기와 견주어 **가장 크게 움직인 것부터**
 * 금액 순으로 몇 줄만 남긴다 — 잔돈 수준의 변화는 아예 말하지 않는다.
 */
object Debrief {

    /** 이만큼(직전·이번 매출 중 큰 쪽 대비) 움직이지 않은 항목은 말하지 않는다. */
    private const val MATERIAL = 0.03

    private class Cause(val weight: Double, val line: DebriefLine)

    /**
     * @param includeNetwork 노선망 이야기(탑승률·적자 노선)를 함께 넣을지.
     *
     *   금액 줄은 [QuarterResult] 에 박제된 **그 분기의 사실**이라 언제 다시 계산해도 같다.
     *   반면 노선망 줄은 **지금 노선망**을 읽어 만든다 — 결산을 닫고 노선을 열거나 닫으면
     *   근거가 발밑에서 바뀐다. 그래서 결산 직후(플레이어가 아직 아무것도 못 한 시점)의
     *   대화상자에서만 켜고, 언제든 다시 그려지는 대시보드에서는 끈다. 대시보드에는
     *   지금 노선망을 보는 "할 일" 패널이 따로 있어 그쪽이 제자리다.
     */
    fun lines(
        state: GameState,
        airline: Airline,
        current: QuarterResult,
        limit: Int = 4,
        includeNetwork: Boolean = true,
    ): List<DebriefLine> {
        val previous = airline.results.lastOrNull { it.turn == current.turn - 1 }
            ?: return listOf(openingLine(current))

        // 매출이 두 분기 다 0 이면(운항이 끊긴 회사) 기준이 0 이 되어, 변한 것이 없는데도
        // "순익 +$0" 과 "연료비 +$0" 이 줄줄이 올라온다. 바닥을 1 달러로 받쳐 둔다.
        val floor = (maxOf(current.totalRevenue, previous.totalRevenue) * MATERIAL).coerceAtLeast(1.0)
        val causes = buildList {
            addAll(revenueCauses(current, previous, floor))
            addAll(costCauses(current, previous, floor))
            if (includeNetwork) addAll(networkCauses(state, airline, current, previous, floor))
        }

        return listOf(summaryLine(current, previous, floor)) +
            causes.sortedByDescending { it.weight }.take(limit - 1).map { it.line }
    }

    // ------------------------------------------------------------------ 총평

    private fun openingLine(current: QuarterResult): DebriefLine {
        val margin = if (current.totalRevenue > 0) current.net / current.totalRevenue else 0.0
        return DebriefLine(
            "첫 결산입니다. 매출의 ${pct(margin)}% 가 순익으로 남았습니다.",
            if (current.net >= 0) DebriefTone.GOOD else DebriefTone.BAD,
        )
    }

    private fun summaryLine(current: QuarterResult, previous: QuarterResult, floor: Double): DebriefLine {
        val delta = current.net - previous.net
        if (abs(delta) < floor) {
            return DebriefLine("순익은 지난 분기와 비슷합니다.", DebriefTone.FLAT)
        }
        return DebriefLine(
            "지난 분기 대비 순익 {}",
            if (delta >= 0) DebriefTone.GOOD else DebriefTone.BAD,
            amount = delta,
            signed = true,
        )
    }

    // ------------------------------------------------------------------ 매출

    /**
     * 매출 변화를 **몇 명을 태웠나**와 **한 명에게 얼마를 받았나**로 가른다.
     * 같은 매출 하락이라도 손님이 준 것과 값이 눌린 것은 손 쓸 방법이 정반대다.
     *
     * 단가는 **여객 매출만** 나눈다. 부대사업 수입은 손님 수와 무관하게 오르내리므로
     * 합계로 재면 라운지 하나 지은 분기가 "승객 단가가 올랐다"로 잘못 설명된다.
     */
    private fun revenueCauses(current: QuarterResult, previous: QuarterResult, floor: Double): List<Cause> {
        if (current.pax <= 0 && previous.pax <= 0) return emptyList()
        // 한쪽이 0 이면 단가를 나눌 수 없다. 그렇다고 통째로 건너뛰면 20만 명을 태우다
        // 한 명도 못 태운 분기가 **원인 없는 순익 급락**으로만 남는다 — 가장 중요한 사실이
        // 바로 그 정지인데. 이럴 때는 물량 이야기만 한다.
        if (current.pax <= 0 || previous.pax <= 0) {
            val delta = current.revenue - previous.revenue
            if (abs(delta) < floor) return emptyList()
            val text = if (current.pax <= 0) {
                "운항이 끊겨 여객 매출이 사라졌습니다 ({})"
            } else {
                "운항을 재개해 여객 매출 {}"
            }
            return listOf(Cause(abs(delta), DebriefLine(text, tone(delta), amount = delta, signed = true)))
        }
        val wasYield = previous.revenue / previous.pax
        val nowYield = current.revenue / current.pax
        val volume = (current.pax - previous.pax) * wasYield
        val price = (nowYield - wasYield) * current.pax

        return buildList {
            if (abs(volume) >= floor) {
                add(
                    Cause(
                        abs(volume),
                        DebriefLine(
                            "수송객 ${signedPct(current.pax / previous.pax - 1)} → 여객 매출 {}",
                            tone(volume),
                            amount = volume,
                            signed = true,
                        ),
                    ),
                )
            }
            if (abs(price) >= floor) {
                add(
                    Cause(
                        abs(price),
                        DebriefLine(
                            "승객 단가 ${signedPct(nowYield / wasYield - 1)} → 여객 매출 {}",
                            tone(price),
                            amount = price,
                            signed = true,
                        ),
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------ 비용

    private fun costCauses(current: QuarterResult, previous: QuarterResult, floor: Double): List<Cause> {
        val out = mutableListOf<Cause>()

        val fuel = current.fuelCost - previous.fuelCost
        if (abs(fuel) >= floor) {
            // 유가를 실적에 함께 남기므로 연료비만은 원인까지 짚을 수 있다.
            // 0 은 이 필드가 없던 시절의 세이브라 그때는 조용히 건너뛴다.
            val oilMove = if (current.oil > 0 && previous.oil > 0) current.oil / previous.oil - 1.0 else 0.0
            val why = if (abs(oilMove) >= 0.02) " — 항공유가 ${signedPct(oilMove)} 움직였습니다" else ""
            out += Cause(
                abs(fuel),
                DebriefLine("연료비 {}$why", tone(-fuel), amount = fuel, signed = true),
            )
        }

        // 나머지 비용은 가장 크게 움직인 한 줄만 남긴다 — 다 적으면 결국 계정과목표가 된다.
        val others = listOf(
            "정비비" to current.maintCost - previous.maintCost,
            "승무원 인건비" to current.crewCost - previous.crewCost,
            "공항·항행료" to current.landingCost - previous.landingCost,
            "슬롯 임차료" to current.slotRent - previous.slotRent,
            "이자" to current.interestCost - previous.interestCost,
            "광고비" to current.adSpend - previous.adSpend,
            "본사 간접비" to current.overhead - previous.overhead,
            "감가상각" to current.depreciation - previous.depreciation,
        ).maxByOrNull { abs(it.second) }
        if (others != null && abs(others.second) >= floor) {
            out += Cause(
                abs(others.second),
                DebriefLine("${others.first} {}", tone(-others.second), amount = others.second, signed = true),
            )
        }

        val side = current.businessIncome - previous.businessIncome
        if (abs(side) >= floor) {
            out += Cause(abs(side), DebriefLine("부대사업 수입 {}", tone(side), amount = side, signed = true))
        }

        if (current.extraordinaryCost > 0) {
            out += Cause(
                current.extraordinaryCost,
                DebriefLine(
                    "파업 합의금 등 일시 비용 {} 가 이번 분기에만 나갔습니다.",
                    DebriefTone.BAD,
                    amount = current.extraordinaryCost,
                ),
            )
        }
        return out
    }

    // ------------------------------------------------------------------ 노선망

    /** 탑승률과 적자 노선은 금액표에 안 잡히지만, 다음 분기에 손댈 곳을 그대로 가리킨다. */
    private fun networkCauses(
        state: GameState,
        airline: Airline,
        current: QuarterResult,
        previous: QuarterResult,
        floor: Double,
    ): List<Cause> {
        val out = mutableListOf<Cause>()
        // 결산 결과가 붙어 있는 노선 — 즉 그 분기를 실제로 난 노선만 본다. 이번 분기에
        // 새로 연 노선(결과가 없다)이 지난 분기 탑승률 하락의 원인으로 지목되면 안 된다.
        // 반대로 결산 뒤에 **닫아 버린** 노선은 여기서 되살릴 수 없다 — 그래서 이 줄들은
        // 결산 직후에만 쓴다 (lines 의 includeNetwork 설명 참고).
        val routes = state.routesOf(airline.id).filter { it.last != null }

        val lfDelta = current.loadFactor - previous.loadFactor
        if (abs(lfDelta) >= 0.03) {
            // 탑승률이 눌렸으면 경쟁이 가장 붐비는 우리 노선을 함께 짚는다.
            val contested = routes
                .map { it to rivalsOn(state, airline.id, it.from, it.to) }
                .filter { it.second > 0 }
                .maxByOrNull { it.second }
            val where = if (lfDelta < 0 && contested != null) {
                val (route, n) = contested
                " — ${Cities.name(route.from)}–${Cities.name(route.to)}에 경쟁 ${n}사가 붙어 있습니다."
            } else {
                ""
            }
            out += Cause(
                abs(lfDelta) * current.totalRevenue,
                DebriefLine("탑승률 ${signedPct(lfDelta, ofPoint = true)}$where", tone(lfDelta)),
            )
        }

        val bleeding = routes.filter { (it.last?.profit ?: 0.0) < 0 }
        val worst = bleeding.minByOrNull { it.last?.profit ?: 0.0 }
        if (worst != null && abs(worst.last!!.profit) >= floor) {
            val label = "${Cities.name(worst.from)}–${Cities.name(worst.to)}"
            val head = if (bleeding.size == 1) "적자 노선 $label" else "적자 노선 ${bleeding.size}개 · 최악은 $label"
            out += Cause(
                abs(worst.last!!.profit),
                DebriefLine("$head {}", DebriefTone.BAD, amount = worst.last!!.profit, signed = true),
            )
        }
        return out
    }

    private fun rivalsOn(state: GameState, airlineId: String, from: String, to: String): Int =
        state.routes.count {
            it.airlineId != airlineId && ((it.from == from && it.to == to) || (it.from == to && it.to == from))
        }

    // ------------------------------------------------------------------ 표현

    private fun tone(v: Double) = if (v >= 0) DebriefTone.GOOD else DebriefTone.BAD

    private fun pct(v: Double) = (v * 100).roundToInt()

    private fun signedPct(v: Double, ofPoint: Boolean = false): String {
        val n = (v * 100).roundToInt()
        return (if (n > 0) "+" else "") + n + if (ofPoint) "%p" else "%"
    }
}
