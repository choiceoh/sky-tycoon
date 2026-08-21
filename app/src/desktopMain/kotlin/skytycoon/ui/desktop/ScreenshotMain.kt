package skytycoon.ui.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import skytycoon.core.sim.Ai
import skytycoon.core.sim.Rng
import skytycoon.core.sim.TurnEngine
import skytycoon.ui.App
import skytycoon.ui.GameViewModel
import skytycoon.ui.MemorySaveStore
import skytycoon.ui.SaveStorage
import skytycoon.ui.Tab
import java.io.File

private class Shot(val name: String, val size: Size, val tab: Tab, val vm: GameViewModel) {
    class Size(val w: Int, val h: Int, val density: Float)
}

/** 고정 시드 — 스크린샷이 실행마다 달라지면 레이아웃 회귀를 눈으로 잡을 수 없다. */
private const val SCREENSHOT_SEED = 20260816

/**
 * X 서버 없이 각 화면을 PNG 로 렌더한다. UI 를 눈으로 확인하는 가장 확실한 방법이면서,
 * 레이아웃이 깨지거나 렌더 중 예외가 나면 여기서 바로 드러난다.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: "build/screenshots").apply { mkdirs() }

    // 몇 분기 굴려서 실적·뉴스가 채워진 상태를 찍는다 (빈 화면은 검증 가치가 없다).
    SaveStorage.current = MemorySaveStore()
    val vm = GameViewModel()
    vm.start("goldenage", "hanseong", "normal", seed = SCREENSHOT_SEED)
    repeat(6) {
        vm.state?.let { s ->
            val piloted = Ai.autoPilot(s, s.playerId, Rng(s.rngState xor 0x5EED))
            vm.importSave(skytycoon.core.save.Save.encode(TurnEngine.advance(piloted)))
        }
    }
    vm.dismissReport()
    vm.message = null

    // 분기 결산 대화상자도 한 장 찍는다. 새 해설이 여기 맨 위에 들어가는데, 열어 보지
    // 않으면 문장이 넘치거나 잘려도 알 수 없다. 같은 판을 한 분기 더 굴려 리포트를 띄운다.
    val reportVm = GameViewModel(MemorySaveStore())
    reportVm.importSave(requireNotNull(vm.exportSave()))
    reportVm.endTurn()
    reportVm.message = null

    // 폰에서 노선 상세는 목록에서 노선을 눌러야 나온다 — 열어 둔 판을 따로 만들어 찍는다.
    // 상세가 잘리거나 넘치는 사고는 여기서만 잡힌다 (목록 화면에는 안 보인다).
    val routeDetailVm = GameViewModel(MemorySaveStore())
    routeDetailVm.importSave(requireNotNull(vm.exportSave()))
    routeDetailVm.openRouteId = routeDetailVm.game
        .routesOf(routeDetailVm.game.playerId)
        .maxByOrNull { it.last?.profit ?: 0.0 }?.id

    // 시작 화면은 진행 중인 판이 없어야 나온다.
    val freshVm = GameViewModel(MemorySaveStore())

    // 안드로이드 폰 세로가 기준 화면이다. 412x915dp @2x 로 찍어 dp 는 실제 폰과 같게,
    // 이미지는 읽을 수 있을 만큼 크게 만든다. 데스크톱은 넓은 화면 레이아웃 확인용.
    val phone = Shot.Size(824, 1830, 2f)
    val desktop = Shot.Size(1360, 900, 1f)

    val shots = listOf(
        Shot("01-setup", phone, Tab.DASHBOARD, freshVm),
        Shot("02-dashboard", phone, Tab.DASHBOARD, vm),
        Shot("03-network", phone, Tab.NETWORK, vm),
        Shot("04-routes", phone, Tab.ROUTES, vm),
        Shot("04b-route-detail", phone, Tab.ROUTES, routeDetailVm),
        Shot("05-fleet", phone, Tab.FLEET, vm),
        Shot("06-market", phone, Tab.MARKET, vm),
        Shot("07-manage", phone, Tab.MANAGE, vm),
        Shot("08-news", phone, Tab.NEWS, vm),
        Shot("09-report", phone, Tab.DASHBOARD, reportVm),
        Shot("90-desktop-network", desktop, Tab.NETWORK, vm),
        Shot("91-desktop-routes", desktop, Tab.ROUTES, vm),
    )

    for (shot in shots) {
        val scene = ImageComposeScene(
            width = shot.size.w,
            height = shot.size.h,
            density = Density(shot.size.density),
        ) {
            App(shot.vm, shot.tab)
        }
        val image = scene.render()
        val bytes = image.encodeToData()?.bytes ?: error("${shot.name} 렌더 실패")
        File(outDir, "${shot.name}.png").writeBytes(bytes)
        scene.close()
        println("wrote ${shot.name}.png (${shot.size.w}x${shot.size.h} @${shot.size.density}x)")
    }
    println("screenshots -> ${outDir.absolutePath}")
}
