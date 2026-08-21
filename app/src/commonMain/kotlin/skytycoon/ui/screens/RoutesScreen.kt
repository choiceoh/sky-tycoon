package skytycoon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import skytycoon.core.data.AircraftCatalog
import skytycoon.core.data.Cities
import skytycoon.core.model.GameState
import skytycoon.core.model.Route
import skytycoon.core.sim.Cargo
import skytycoon.core.sim.Command
import skytycoon.core.sim.Maintenance
import skytycoon.core.sim.Economics
import skytycoon.core.sim.Geo
import skytycoon.ui.Amber
import skytycoon.ui.Coral
import skytycoon.ui.GameViewModel
import skytycoon.ui.InkPanelHigh
import skytycoon.ui.Sky
import skytycoon.ui.TextHigh
import skytycoon.ui.TextLow
import skytycoon.ui.TextMid
import skytycoon.ui.components.EmptyHint
import skytycoon.ui.components.KeyValue
import skytycoon.ui.components.Panel
import skytycoon.ui.components.RatioBar
import skytycoon.ui.components.VSpace
import skytycoon.ui.decimals
import skytycoon.ui.grouped
import skytycoon.ui.km
import skytycoon.ui.loadFactorColor
import skytycoon.ui.moneyShort
import skytycoon.ui.people
import skytycoon.ui.percent
import skytycoon.ui.profitColor
import skytycoon.ui.signedMoney

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RoutesScreen(vm: GameViewModel, wide: Boolean) {
    val s = vm.game
    val routes = s.routesOf(s.playerId).sortedByDescending { it.last?.profit ?: 0.0 }
    // 노선을 폐지하면 목록에서 사라진다 — 그때는 들여다볼 대상이 없으니 자연히
    // 목록으로 돌아온다 (노선 id 는 재사용되지 않으므로 되살아날 일도 없다).
    val open = routes.firstOrNull { it.id == vm.openRouteId }

    if (routes.isEmpty()) {
        EmptyHint("아직 노선이 없습니다. 노선망 화면에서 도시를 골라 개설하세요.")
        return
    }

    val list = @Composable { modifier: Modifier, highlight: Int? ->
        LazyColumn(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(routes, key = { it.id }) { route ->
                RouteRow(s, route, highlight == route.id) { vm.openRouteId = route.id }
            }
        }
    }

    if (wide) {
        // 넓은 화면은 목록과 상세를 나란히 둔다. 고른 적이 없으면 맨 위 노선을 보여준다.
        val shown = open ?: routes.first()
        Row(Modifier.fillMaxSize()) {
            list(Modifier.weight(1f).fillMaxHeight(), shown.id)
            Box(Modifier.width(400.dp).fillMaxHeight().padding(12.dp)) {
                RouteDetail(vm, shown)
            }
        }
    } else {
        // 폰 세로에서 목록과 상세를 위아래로 반씩 나누면 **둘 다** 서너 줄만 보인 채
        // 각자 스크롤한다 — 노선을 고르는 일도, 편수를 만지는 일도 창구멍으로 하게 된다.
        // 한 번에 하나만 전체 높이로 띄우고 노선을 누르면 상세로 넘어간다.
        BackHandler(open != null) { vm.openRouteId = null }

        if (open == null) {
            list(Modifier.fillMaxSize(), null)
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.openRouteId = null }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("‹  노선 목록", color = Sky, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${routes.size}개",
                        color = TextLow,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                }
                Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)) {
                    RouteDetail(vm, open)
                }
            }
        }
    }
}

@Composable
private fun RouteRow(state: GameState, route: Route, selected: Boolean, onClick: () -> Unit) {
    val last = route.last
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Sky.copy(alpha = 0.14f) else InkPanelHigh)
            .clickable { onClick() }
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${Cities.name(route.from)} – ${Cities.name(route.to)}",
                color = TextHigh,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                last?.let { signedMoney(it.profit) } ?: "—",
                color = last?.let { profitColor(it.profit) } ?: TextMid,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        VSpace(4)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "주 ${route.freq}왕복 · 운임 ${decimals(route.fareMul, 2)}배 · 기재 ${route.planeIds.size}대",
                color = TextMid,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            if (last != null) {
                Text(percent(last.loadFactor), color = loadFactorColor(last.loadFactor), fontSize = 11.sp)
            }
        }
        // 다음 분기에 정비로 빠지는 기재가 있으면 여기서 알린다. 노선 화면이 편수를
        // 정하는 자리라, 예비기를 붙일지 편수를 줄일지 판단이 일어나는 곳도 여기다.
        // 화면의 turn 은 **이제 진행할 분기**다 — 지금 isDue 인 기체는 그 분기에 입고된다.
        val leaving = state.assignedTo(route.id).count { Maintenance.isDue(it) }
        if (leaving > 0) {
            VSpace(4)
            Text(
                if (leaving >= route.planeIds.size) "이번 분기 전 기재 중정비 — 결항" else "이번 분기 ${leaving}대 중정비 — 편수 감소",
                color = Coral,
                fontSize = 11.sp,
            )
        }
        if (last != null) {
            VSpace(4)
            RatioBar(last.loadFactor, loadFactorColor(last.loadFactor), Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RouteDetail(vm: GameViewModel, route: Route) {
    val s = vm.game
    val player = s.player
    val dist = Geo.distance(route.from, route.to)
    val onRoute = s.planes.filter { it.routeId == route.id }
    val cap = Economics.capacity(onRoute, dist)
    val slotHeadroom = minOf(
        s.freeSlots(player.id, route.from) + route.freq,
        s.freeSlots(player.id, route.to) + route.freq,
    )
    val maxFreq = minOf(cap.maxFreq, slotHeadroom)

    // 배속 변경으로 route.freq 가 자동으로 깎일 수 있다. 노선 id 만 키로 쓰면
    // 슬라이더가 옛 값을 들고 있다가 범위 밖 값을 그대로 제출한다.
    var fare by remember(route.id, route.fareMul) { mutableStateOf(route.fareMul.toFloat()) }
    var freq by remember(route.id, route.freq) { mutableStateOf(route.freq.toFloat()) }
    var service by remember(route.id) { mutableStateOf(route.serviceExtra) }
    var editingFleet by remember(route.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Panel {
            Text(
                "${Cities.name(route.from)} – ${Cities.name(route.to)}",
                color = TextHigh,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text("${km(dist)} · 표준 운임 ${moneyShort(Economics.standardFare(dist, s.world.inflation))}", color = TextMid, fontSize = 12.sp)
            route.last?.let { r ->
                VSpace(10)
                KeyValue("수송객", people(r.pax))
                KeyValue("공급 좌석", people(r.seats))
                KeyValue("탑승률", percent(r.loadFactor), loadFactorColor(r.loadFactor))
                // 점유율은 **이 구간이 목적지인 로컬 승객**만의 몫이다. 바로 위 수송객에는
                // 이 노선을 중간 구간으로 쓴 환승객이 섞여 있어, 그냥 "점유율"이라고 하면
                // 환승으로 꽉 찬 노선이 점유율만 낮게 보여 읽는 사람이 헷갈린다.
                KeyValue("로컬 점유율", percent(r.share))
                KeyValue("매출", moneyShort(r.revenue))
                // 화물은 기종·거리·빈자리로 갈린다. 매출 안에 묻어 두면 광동체를 붙일
                // 이유 하나가 화면에서 통째로 사라진다.
                val cargoTons = Cargo.routeCapacityTons(s, route.id)
                if (cargoTons > 0.0) {
                    KeyValue(
                        "  화물 적재 여력",
                        "${grouped(cargoTons.toLong())}t / 구간 수요 ${grouped(Cargo.demandTons(s, route.from, route.to).toLong())}t",
                    )
                }
                // 원가에는 이 노선이 물고 있는 슬롯의 분기 임차료가 들어 있다.
                KeyValue("원가 (임차료 포함)", moneyShort(r.cost))
                KeyValue("손익", signedMoney(r.profit), profitColor(r.profit))
            }
        }

        VSpace(12)
        Panel(title = "운항 조건") {
            Text("주간 왕복 ${freq.toInt()}회 (최대 $maxFreq)", color = TextHigh, fontSize = 12.sp)
            Slider(
                value = freq,
                onValueChange = { freq = it },
                onValueChangeFinished = {
                    vm.run(Command.TuneRoute(player.id, route.id, freq = freq.toInt()))
                },
                valueRange = 0f..maxFreq.coerceAtLeast(1).toFloat(),
            )
            Text(
                if (cap.maxFreq <= slotHeadroom) "기재 가동률이 한계입니다 — 증편하려면 기재를 더 넣으세요."
                else "슬롯이 한계입니다 — 증편하려면 슬롯을 더 사세요.",
                color = TextLow,
                fontSize = 11.sp,
            )

            VSpace(10)
            Text("운임 ${decimals(fare.toDouble(), 2)}배", color = TextHigh, fontSize = 12.sp)
            Slider(
                value = fare,
                onValueChange = { fare = it },
                onValueChangeFinished = {
                    vm.run(Command.TuneRoute(player.id, route.id, fareMul = fare.toDouble()))
                },
                valueRange = 0.55f..1.8f,
            )

            VSpace(10)
            Text("노선 서비스 투자", color = TextHigh, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (lvl in 0..2) {
                    OutlinedButton(
                        onClick = {
                            service = lvl
                            vm.run(Command.TuneRoute(player.id, route.id, serviceExtra = lvl))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            listOf("기본", "＋1", "＋2")[lvl],
                            fontSize = 12.sp,
                            color = if (service == lvl) Amber else TextMid,
                        )
                    }
                }
            }
        }

        VSpace(12)
        Panel(title = "배속 기재", trailing = {
            Text(
                if (editingFleet) "닫기" else "변경",
                color = Sky,
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { editingFleet = !editingFleet }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }) {
            if (!editingFleet) {
                for (p in onRoute) {
                    val t = AircraftCatalog[p.typeId]
                    KeyValue(t.name, "${t.seats}석 · 기령 ${p.ageQuarters / 4}년")
                }
                if (onRoute.isEmpty()) Text("배속된 기재가 없습니다.", color = Coral, fontSize = 12.sp)
            } else {
                val candidates = s.planesOf(player.id).filter {
                    (it.routeId == null || it.routeId == route.id) &&
                        Economics.canFly(AircraftCatalog[it.typeId], dist)
                }
                var picked by remember(route.id) { mutableStateOf(onRoute.map { it.id }.toSet()) }
                for (p in candidates) {
                    val t = AircraftCatalog[p.typeId]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                picked = if (p.id in picked) picked - p.id else picked + p.id
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = p.id in picked, onCheckedChange = null)
                        Text("${t.name} · ${t.seats}석", color = TextHigh, fontSize = 12.sp)
                    }
                }
                VSpace(8)
                Button(
                    onClick = {
                        if (vm.run(Command.AssignPlanes(player.id, route.id, picked.toList()))) {
                            editingFleet = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("배속 적용") }
            }
        }

        VSpace(12)
        OutlinedButton(
            onClick = { vm.run(Command.CloseRoute(player.id, route.id)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("노선 폐지", color = Coral) }
        VSpace(20)
    }
}
