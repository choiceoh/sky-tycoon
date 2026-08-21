package skytycoon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import skytycoon.core.model.QuarterResult
import skytycoon.core.sim.Debrief
import skytycoon.core.sim.DebriefLine
import skytycoon.core.sim.DebriefTone
import skytycoon.ui.Amber
import skytycoon.ui.Coral
import skytycoon.ui.Loss
import skytycoon.ui.Mint
import skytycoon.ui.Profit
import skytycoon.ui.GameViewModel
import skytycoon.ui.TextHigh
import skytycoon.ui.TextLow
import skytycoon.ui.TextMid
import skytycoon.ui.components.KeyValue
import skytycoon.ui.components.VSpace
import skytycoon.ui.loadFactorColor
import skytycoon.ui.money
import skytycoon.ui.moneyShort
import skytycoon.ui.percent
import skytycoon.ui.profitColor
import skytycoon.ui.signedMoney

/** 분기 결산. 숫자를 늘어놓기만 하면 안 읽히므로 매출 → 비용 → 순익 순서로 이야기를 만든다. */
@Composable
fun QuarterReportDialog(vm: GameViewModel, report: QuarterResult, onDismiss: () -> Unit) {
    val s = vm.game
    val year = s.startYear + report.turn / 4
    val quarter = report.turn % 4 + 1

    // 440dp 로 박아 두면 작은 폰에서는 대화상자가 화면 밖으로 밀리고, 큰 폰에서는 남는
    // 자리를 두고도 계정과목이 여덟 줄쯤에서 잘린다. 창 높이의 절반 남짓을 준다.
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val listMax = (windowHeight * 0.52f).coerceIn(260.dp, 560.dp)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("${year}년 ${quarter}분기 결산", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    "순익 ${signedMoney(report.net)}",
                    color = profitColor(report.net),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = listMax).verticalScroll(rememberScrollState())) {
                // 계정과목보다 먼저 "무슨 일이 있었나"를 놓는다 — 스무 줄짜리 표를 스스로
                // 뒤져 원인을 찾아내라고 하면 아무도 읽지 않는다.
                Text("무슨 일이 있었나", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                VSpace(4)
                for (line in Debrief.lines(s, s.player, report)) {
                    Text(
                        "· " + render(line),
                        color = toneColor(line.tone),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }

                VSpace(10)
                HorizontalDivider()
                VSpace(10)
                Text("수입", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                KeyValue("여객 매출", moneyShort(report.revenue))
                KeyValue("화물 매출", moneyShort(report.cargoRevenue))
                KeyValue("부대사업", moneyShort(report.businessIncome))
                KeyValue("합계", moneyShort(report.totalRevenue), TextHigh)

                VSpace(10)
                HorizontalDivider()
                VSpace(10)
                Text("비용", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                KeyValue("연료", moneyShort(report.fuelCost))
                KeyValue("승무원", moneyShort(report.crewCost))
                KeyValue("정비", moneyShort(report.maintCost))
                // 몇 분기에 한 번 몰아서 나가는 돈이라, 나간 분기에만 따로 보여준다.
                if (report.checkCost > 0) KeyValue("중정비", moneyShort(report.checkCost))
                KeyValue("공항·항행", moneyShort(report.landingCost))
                KeyValue("기내 서비스", moneyShort(report.paxServiceCost))
                KeyValue("판매·유통", moneyShort(report.distributionCost))
                KeyValue("본사 간접비", moneyShort(report.overhead))
                KeyValue("슬롯 임차료", moneyShort(report.slotRent))
                KeyValue("광고", moneyShort(report.adSpend))
                KeyValue("감가상각", moneyShort(report.depreciation))
                if (report.leaseCost > 0) KeyValue("리스료", moneyShort(report.leaseCost))
                KeyValue("이자", moneyShort(report.interestCost))
                if (report.extraordinaryCost > 0) {
                    KeyValue("일시 비용(파업 등)", moneyShort(report.extraordinaryCost))
                }
                KeyValue("법인세", moneyShort(report.tax))
                KeyValue("합계", moneyShort(report.operatingCost + report.interestCost + report.tax), TextHigh)

                VSpace(10)
                HorizontalDivider()
                VSpace(10)
                Text("운송 실적", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                KeyValue("수송객", "${money(report.pax)}명")
                KeyValue("탑승률", percent(report.loadFactor), loadFactorColor(report.loadFactor))
                KeyValue("기말 현금", moneyShort(report.cash))
                KeyValue("부채", moneyShort(report.debt))
                KeyValue("자기자본", moneyShort(report.equity))

                val recent = s.player.results.takeLast(8)
                if (recent.size >= 2) {
                    VSpace(12)
                    Text("최근 순익 추이", color = TextLow, fontSize = 11.sp)
                    VSpace(6)
                    NetTrend(recent, s.startYear)
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("확인") } },
    )
}

/**
 * 분기 순익 추이 막대. 예전에는 여덟 칸에 9sp 금액을 늘어놓았는데, 폰에서는 글자가
 * 칸을 넘겨 잘리기만 하고 추세는 읽히지 않았다. 금액은 위에 이미 다 있으니 여기서는
 * 흑자·적자의 모양만 보여준다 — 0 선을 가운데 두고 위아래로 뻗는다.
 */
@Composable
private fun NetTrend(recent: List<QuarterResult>, startYear: Int) {
    val peak = recent.maxOf { kotlin.math.abs(it.net) }.coerceAtLeast(1.0)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (r in recent) {
            val share = (kotlin.math.abs(r.net) / peak).toFloat().coerceIn(0.04f, 1f)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                // 위 칸은 흑자, 아래 칸은 적자. 두 칸이 같은 높이라 0 선이 한 줄로 이어진다.
                Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.BottomCenter) {
                    if (r.net >= 0) Bar(share, Profit)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(TextLow))
                Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.TopCenter) {
                    if (r.net < 0) Bar(share, Loss)
                }
                VSpace(3)
                Text(
                    "${(startYear + r.turn / 4) % 100}Q${r.turn % 4 + 1}",
                    color = TextMid,
                    fontSize = 9.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Bar(share: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(share)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

/** core 는 금액을 `{}` 로 비워 둔다 — 화폐 표기는 화면이 정한다. */
internal fun render(line: DebriefLine): String {
    val amount = line.amount ?: return line.text
    return line.text.replace("{}", if (line.signed) signedMoney(amount) else moneyShort(amount))
}

internal fun toneColor(tone: DebriefTone) = when (tone) {
    DebriefTone.GOOD -> Mint
    DebriefTone.BAD -> Coral
    DebriefTone.FLAT -> TextMid
}
