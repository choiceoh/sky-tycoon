package skytycoon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import skytycoon.core.data.AircraftCatalog
import skytycoon.core.data.Cities
import skytycoon.core.sim.Actions
import skytycoon.core.sim.Balance
import skytycoon.core.sim.Command
import skytycoon.core.sim.Cargo
import skytycoon.core.sim.Leasing
import skytycoon.core.sim.Maintenance
import skytycoon.ui.Amber
import skytycoon.ui.Coral
import skytycoon.ui.GameViewModel
import skytycoon.ui.InkPanelHigh
import skytycoon.ui.Mint
import skytycoon.ui.Sky
import skytycoon.ui.TextHigh
import skytycoon.ui.TextLow
import skytycoon.ui.TextMid
import skytycoon.ui.components.Chip
import skytycoon.ui.components.EmptyHint
import skytycoon.ui.components.Panel
import skytycoon.ui.components.VSpace
import skytycoon.ui.decimals
import skytycoon.ui.grouped
import skytycoon.ui.km
import skytycoon.ui.moneyShort

@Composable
fun FleetScreen(vm: GameViewModel, wide: Boolean) {
    val owned = @Composable { m: Modifier -> OwnedFleet(vm, m) }
    val market = @Composable { m: Modifier -> AircraftMarket(vm, m) }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            owned(Modifier.weight(1f).fillMaxHeight().padding(12.dp))
            market(Modifier.width(430.dp).fillMaxHeight().padding(12.dp))
        }
    } else {
        // 폰 세로에서 위아래로 반씩 나눠 쓰면 기재 시장 목록이 서너 줄만 보여
        // 고르기가 답답하다. 한 번에 하나만 **전체 높이**로 보여준다.
        var showMarket by remember { mutableStateOf(false) }
        val ownedCount = vm.game.planesOf(vm.game.playerId).size
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 한글도 식별자 문자라 `$ownedCount대` 는 변수명으로 붙어 버린다 — 중괄호 필수.
                Chip("보유 ${ownedCount}대", selected = !showMarket, onClick = { showMarket = false })
                Chip("기재 시장", selected = showMarket, accent = Amber, onClick = { showMarket = true })
            }
            val pane = Modifier.fillMaxWidth().weight(1f).padding(12.dp)
            if (showMarket) market(pane) else owned(pane)
        }
    }
}

@Composable
private fun OwnedFleet(vm: GameViewModel, modifier: Modifier) {
    val s = vm.game
    val planes = s.planesOf(s.playerId).sortedWith(compareBy({ it.routeId != null }, { it.typeId }))
    val pending = s.orders.filter { it.airlineId == s.playerId }

    Column(modifier) {
        Panel(title = "보유 기재 ${planes.size}대") {
            if (pending.isNotEmpty()) {
                for (o in pending) {
                    Text(
                        "발주 중 · ${AircraftCatalog[o.typeId].name} ${o.count}대 " +
                            "(${(o.deliverTurn - s.turn).coerceAtLeast(0)}분기 뒤 인도)",
                        color = Amber,
                        fontSize = 12.sp,
                    )
                }
                VSpace(8)
            }
            if (planes.isEmpty()) EmptyHint("보유한 기재가 없습니다.")
        }
        VSpace(10)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(planes, key = { it.id }) { plane ->
                val t = AircraftCatalog[plane.typeId]
                val route = plane.routeId?.let { id -> s.routes.firstOrNull { it.id == id } }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(InkPanelHigh)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, color = TextHigh, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${t.seats}석 · ${km(t.range.toDouble())} · 기령 ${plane.ageQuarters / 4}년 ${plane.ageQuarters % 4}분기",
                            color = TextMid,
                            fontSize = 11.sp,
                        )
                        Text(
                            route?.let { "${Cities.name(it.from)} – ${Cities.name(it.to)}" } ?: "유휴",
                            color = if (route == null) Coral else Mint,
                            fontSize = 11.sp,
                        )
                        // 정비로 편수가 깎이는 일은 **미리 보여야** 손을 쓸 수 있다.
                        // 안 보이면 그냥 무작위로 노선이 멈추는 것으로만 보인다.
                        //
                        // 화면에 보이는 turn 은 **이제 진행할 분기**다 (advance 가 끝나면서
                        // +1 된다). 그래서 지금 isDue 인 기체는 이번 분기에 입고돼 결항한다 —
                        // `inCheck(turn)` 으로 물으면 화면에서는 영영 참이 되지 않는다.
                        val checkNote = when {
                            Maintenance.isDue(plane) -> "중정비 입고 — 이번 분기 결항" to Coral
                            Maintenance.dueSoon(plane) -> "중정비 임박 (${(Maintenance.progress(plane) * 100).toInt()}%)" to Amber
                            else -> null
                        }
                        if (checkNote != null) {
                            Text(checkNote.first, color = checkNote.second, fontSize = 11.sp)
                        }
                        if (plane.leased) {
                            val left = Leasing.quartersLeft(s, plane)
                            Text(
                                "리스 · 분기 ${moneyShort(plane.leaseRate)} · ${left}분기 남음",
                                color = if (left <= 2) Amber else Mint,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        // 빌린 기체는 팔 게 아니라 돌려주는 것이라, 값 대신 위약금을 보여준다.
                        Text(
                            if (plane.leased) {
                                "위약금 ${moneyShort(Leasing.breakFee(s, plane))}"
                            } else {
                                moneyShort(Actions.sellPrice(t, plane.ageQuarters, plane.priceMul))
                            },
                            color = TextMid,
                            fontSize = 11.sp,
                        )
                        if (route == null) {
                            OutlinedButton(
                                onClick = {
                                    if (plane.leased) {
                                        vm.run(Command.ReturnLease(s.playerId, plane.id))
                                    } else {
                                        vm.run(Command.SellAircraft(s.playerId, plane.id))
                                    }
                                },
                            ) { Text(if (plane.leased) "반납" else "매각", fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
    }
}

/** 기재를 들이는 세 가지 길. 값을 치르는 방식이 저마다 다르다. */
private enum class BuyMode { NEW, USED, LEASE }

@Composable
private fun AircraftMarket(vm: GameViewModel, modifier: Modifier) {
    val s = vm.game
    var mode by remember { mutableStateOf(BuyMode.NEW) }
    val usedMode = mode == BuyMode.USED
    var leaseTerm by remember { mutableStateOf(Balance.LEASE_TERMS.max()) }
    // 판이 끝나면 turn 이 totalTurns 라 s.year 는 플레이하지도 않은 다음 해가 된다.
    // 진영 밖 기재는 애초에 목록에 없다 — 살 수 없는 것을 보여 주고 버튼만 막으면
    // 왜 못 사는지 모른 채 헤맨다.
    val home = s.player.home
    val catalog = when (mode) {
        BuyMode.USED -> AircraftCatalog.usedFor(s.displayYear, home)
        // 리스 시장에는 현행기와 갓 단종된 기종이 함께 나온다.
        BuyMode.LEASE -> (AircraftCatalog.newFor(s.displayYear, home) + AircraftCatalog.usedFor(s.displayYear, home))
            .distinctBy { it.id }
        BuyMode.NEW -> AircraftCatalog.newFor(s.displayYear, home)
    }
    val leaseRoom = Leasing.leaseRoom(s, s.player)
    val leaseFits = Actions.arrivesBeforeEnd(s, leaseTerm)
    // 신조기는 인도까지 시간이 걸린다 — 명령과 같은 술어로 물어봐야 버튼이 갈라지지 않는다.
    val inTime = Actions.orderFitsCampaign(s)

    Column(modifier) {
        Panel(title = "기재 시장 · ${s.displayYear}년") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("신조기", selected = mode == BuyMode.NEW, onClick = { mode = BuyMode.NEW })
                Chip("중고기", selected = mode == BuyMode.USED, accent = Amber, onClick = { mode = BuyMode.USED })
                Chip("리스", selected = mode == BuyMode.LEASE, accent = Mint, onClick = { mode = BuyMode.LEASE })
            }
            VSpace(6)
            Text(
                when (mode) {
                    BuyMode.USED -> "즉시 인도. 기령이 있어 정비비가 더 듭니다."
                    BuyMode.LEASE -> "목돈 없이 즉시 인도. 대신 계약 기간 내내 분기 리스료가 나가고 " +
                        "기체는 내 자산이 되지 않습니다 (지금 ${leaseRoom}대까지)."
                    BuyMode.NEW -> "발주 후 ${Balance.ORDER_DELAY_QUARTERS}분기 뒤 인도. 대금은 지금 나갑니다."
                },
                color = TextLow,
                fontSize = 11.sp,
            )
            if (mode == BuyMode.LEASE) {
                VSpace(6)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (q in Balance.LEASE_TERMS) {
                        Chip("${q / 4}년", selected = leaseTerm == q, accent = Mint, onClick = { leaseTerm = q })
                    }
                }
            }
        }
        VSpace(10)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(catalog, key = { it.id }) { t ->
                val price = if (usedMode) Actions.usedPrice(s, t.id) else t.price
                // 생산이 끝난 기종은 중고 기령으로 들어와 그만큼 싸다. 기령 0 으로 견적을
                // 내면 화면에 뜬 값과 실제 청구액이 어긋난다 — 명령과 같은 함수로 묻는다.
                val rent = Leasing.quarterlyRate(t, leaseTerm, Leasing.deliveryAge(s, t))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(InkPanelHigh)
                        .padding(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, color = TextHigh, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("${t.maker} · ${t.year}년 취항", color = TextLow, fontSize = 10.sp)
                        }
                        if (mode == BuyMode.LEASE) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(moneyShort(rent), color = Mint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text("분기 리스료", color = TextLow, fontSize = 10.sp)
                            }
                        } else {
                            Text(moneyShort(price), color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    VSpace(6)
                    Text(
                        "${t.seats}석 · 항속 ${km(t.range.toDouble())} · ${grouped(t.speed.toLong())}km/h · " +
                            "연료 ${decimals(t.fuel, 1)}L/km" +
                            if (usedMode) " · 기령 ${Actions.usedAge(s, t.id) / 4}년" else "",
                        color = TextMid,
                        fontSize = 11.sp,
                    )
                    // 화물 적재량은 좌석 수로 짐작할 수 없다 (광동체냐 아니냐가 정한다).
                    // 안 보여주면 장거리 무역로에 광동체를 붙일 이유 하나가 숨는다.
                    Text(
                        if (t.widebody) "광동체 · 편당 화물 ${decimals(Cargo.bellyTons(t), 1)}t"
                        else "협동체 · 편당 화물 ${decimals(Cargo.bellyTons(t), 1)}t",
                        color = if (t.widebody) Mint else TextLow,
                        fontSize = 10.sp,
                    )
                    VSpace(6)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (n in listOf(1, 2, 5)) {
                            if (mode == BuyMode.LEASE) {
                                OutlinedButton(
                                    onClick = {
                                        vm.run(Command.LeaseAircraft(s.playerId, t.id, n, leaseTerm))
                                    },
                                    enabled = leaseRoom >= n && leaseFits,
                                    modifier = Modifier.weight(1f),
                                ) { Text("${n}대", fontSize = 11.sp, color = Mint) }
                            } else {
                                OutlinedButton(
                                    onClick = { vm.run(Command.BuyAircraft(s.playerId, t.id, n, used = usedMode)) },
                                    enabled = s.player.cash >= price * n && (usedMode || inTime),
                                    modifier = Modifier.weight(1f),
                                ) { Text("${n}대", fontSize = 11.sp, color = Sky) }
                            }
                        }
                    }
                    if (mode == BuyMode.LEASE) {
                        VSpace(6)
                        Text(
                            if (!leaseFits) {
                                "남은 기간(${s.totalTurns - s.turn}분기)보다 긴 계약은 맺을 수 없습니다."
                            } else if (leaseRoom <= 0) {
                                "리스기 비중이 한도(기단의 ${(Balance.LEASE_FLEET_SHARE_MAX * 100).toInt()}%)에 찼습니다."
                            } else {
                                "${leaseTerm / 4}년 총 ${moneyShort(rent * leaseTerm)} · 사면 ${moneyShort(t.price)}"
                            },
                            color = TextLow,
                            fontSize = 10.sp,
                        )
                    }
                    if (mode == BuyMode.NEW && !inTime) {
                        VSpace(6)
                        Text(
                            "남은 기간(${s.totalTurns - s.turn}분기) 안에 인도되지 않습니다. 중고로 알아보세요.",
                            color = TextLow,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}
