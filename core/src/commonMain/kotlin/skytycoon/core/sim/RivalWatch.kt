package skytycoon.core.sim

import skytycoon.core.data.Cities
import skytycoon.core.model.GameState
import skytycoon.core.model.NewsItem
import skytycoon.core.model.NewsKind
import skytycoon.core.model.Route
import skytycoon.core.model.Trait
import kotlin.math.roundToInt

/**
 * 경쟁사가 이번 분기에 한 일 중 **우리에게 닿는 것**만 골라 소식으로 만든다.
 *
 * AI 는 플레이어와 같은 명령 계층으로 매 분기 열댓 가지를 하는데, 그 결과가 순위표의
 * 숫자로만 남아서 상대가 살아 있다는 느낌이 없었다. 그렇다고 전부 알리면 분기마다
 * 수십 줄이 쌓여 아무도 읽지 않는다 — 그래서 **우리 노선·우리 안방·우리 지분**에 닿는
 * 것만 남기고, 회사별로 묶어 몇 줄로 줄인다.
 *
 * 명령을 가로채지 않고 **분기 전후 상태를 견주어** 뽑는다. AI 쪽을 건드리지 않으므로
 * AI 가 새 수를 배워도 여기가 따라 바뀔 필요가 없다.
 *
 * 우리 지분을 사들이는 일은 여기서 다루지 않는다 — [Actions] 가 매수 시점에 이미 알린다.
 * 회사가 통째로 넘어가는 사안이라 그쪽은 분기 상한에 걸리지 않고 언제나 뜨는 편이 맞고,
 * 여기서 또 만들면 같은 매수가 두 줄로 뜨면서 상한의 한 자리까지 잡아먹는다.
 */
object RivalWatch {

    /** 운임은 매 분기 조금씩 흔들린다. 이만큼 움직여야 "값을 건드렸다"고 본다. */
    private const val FARE_MOVE = 0.06

    /** 편수는 분기마다 +1 씩 오르므로, 기재를 더 붙인 수준의 도약만 소식이 된다. */
    private const val FREQ_JUMP = 2

    private class Signal(val priority: Double, val item: NewsItem)

    /**
     * @param before AI 가 움직이기 **전** 상태, @param after 움직인 뒤 상태.
     * @param limit 한 분기에 남길 소식 수. 넘치면 우리에게 위험한 순서로 자른다.
     */
    fun report(before: GameState, after: GameState, limit: Int = 4): List<NewsItem> {
        val player = after.airlineOrNull(after.playerId)?.takeIf { it.alive } ?: return emptyList()
        val myPairs = after.routesOf(player.id).map { pairKey(it.from, it.to) }.toSet()

        val signals = after.livingAirlines
            .filter { it.id != player.id && before.airlineOrNull(it.id)?.alive == true }
            .flatMap { rival -> routeSignals(before, after, rival.id, myPairs, player.home) }

        return signals.sortedByDescending { it.priority }.take(limit).map { it.item }
    }

    // ------------------------------------------------------------------ 노선

    private fun routeSignals(
        before: GameState,
        after: GameState,
        rivalId: String,
        myPairs: Set<String>,
        home: String,
    ): List<Signal> {
        val rival = after.airline(rivalId)
        val old = before.routesOf(rivalId).associateBy { it.id }
        val now = after.routesOf(rivalId).associateBy { it.id }
        val out = mutableListOf<Signal>()

        // 회사를 삼킨 분기에는 노선 변화를 읽지 않는다. Stock.merge 는 노선 id 를 그대로 둔 채
        // 주인만 바꾸고 같은 도시쌍은 편수를 합치므로, 그 회사의 지난 분기 목록에만 견주면
        // 물려받은 노선이 통째로 "신규 진입"으로, 합쳐진 편수가 "증편"으로 잡힌다 —
        // 시장에 좌석이 늘지도 않았는데 "우리 노선 다섯 곳에 진입" 이 뜬다.
        // 인수 자체는 Stock 이 따로 알린다.
        val ownerBefore = before.routes.associate { it.id to it.airlineId }
        if (now.values.any { ownerBefore[it.id] != null && ownerBefore[it.id] != rivalId }) return out

        val opened = now.values.filter { it.id !in ownerBefore }
        // 안방은 따로 센다. 내가 아직 그 구간에 안 떠 있어도 홈 공항에 들어온 것은 위협이다.
        val atHome = opened.filter { it.touches(home) }
        val onMine = opened.filter { !it.touches(home) && pairKey(it.from, it.to) in myPairs }

        if (atHome.isNotEmpty()) {
            out += Signal(
                150.0 + atHome.size,
                NewsItem(
                    after.turn,
                    NewsKind.RIVAL,
                    "${rival.name}, 우리 안방 ${Cities.name(home)}에 취항",
                    "${routeList(atHome)}${weekly(atHome)}. ${tone(rival.trait)}".trim(),
                ),
            )
        }
        if (onMine.isNotEmpty()) {
            out += Signal(
                120.0 + onMine.size,
                NewsItem(
                    after.turn,
                    NewsKind.RIVAL,
                    if (onMine.size == 1) {
                        "${rival.name}, ${routeLabel(onMine.first())} 진입"
                    } else {
                        "${rival.name}, 우리 노선 ${onMine.size}곳에 진입"
                    },
                    (
                        if (onMine.size == 1) {
                            "주 ${onMine.first().freq}회로 들어왔습니다. ${tone(rival.trait)}"
                        } else {
                            "${routeList(onMine)}${weekly(onMine)}. ${tone(rival.trait)}"
                        }
                        ).trim(),
                ),
            )
        }

        val quit = old.values.filter { it.id !in now && pairKey(it.from, it.to) in myPairs }
        if (quit.isNotEmpty()) {
            out += Signal(
                60.0,
                NewsItem(
                    after.turn,
                    NewsKind.RIVAL,
                    if (quit.size == 1) {
                        "${rival.name}, ${routeLabel(quit.first())} 철수"
                    } else {
                        "${rival.name}, 우리 노선 ${quit.size}곳에서 철수"
                    },
                    if (quit.size == 1) {
                        "남은 수요를 가져올 기회입니다."
                    } else {
                        "${routeList(quit)}. 남은 수요를 가져올 기회입니다."
                    },
                ),
            )
        }

        // 이미 붙어 있는 구간에서 값을 내렸거나 좌석을 늘렸는가.
        val cuts = mutableListOf<Pair<Route, Double>>()
        val raises = mutableListOf<Pair<Route, Double>>()
        val ramps = mutableListOf<Pair<Route, Int>>()
        for ((id, route) in now) {
            val was = old[id] ?: continue
            if (pairKey(route.from, route.to) !in myPairs) continue
            val fareDelta = route.fareMul / was.fareMul - 1.0
            if (fareDelta <= -FARE_MOVE) cuts += route to fareDelta
            if (fareDelta >= FARE_MOVE) raises += route to fareDelta
            if (route.freq - was.freq >= FREQ_JUMP) ramps += route to (route.freq - was.freq)
        }

        cuts.maxByOrNull { -it.second }?.let { (route, delta) ->
            out += Signal(
                100.0 + cuts.size,
                NewsItem(
                    after.turn,
                    NewsKind.RIVAL,
                    "${rival.name}, ${routeLabel(route)} 운임 ${pct(-delta)}% 인하",
                    buildString {
                        if (cuts.size > 1) append("우리와 겹치는 ${cuts.size}개 노선에서 값을 내렸습니다. ")
                        append("맞춰 내리면 마진이, 두면 손님이 깎입니다.")
                    },
                ),
            )
        }
        ramps.maxByOrNull { it.second }?.let { (route, delta) ->
            out += Signal(
                90.0,
                NewsItem(
                    after.turn,
                    NewsKind.RIVAL,
                    "${rival.name}, ${routeLabel(route)} 증편 (주 ${route.freq - delta}→${route.freq}회)",
                    "좌석이 늘어난 만큼 우리 탑승률이 눌립니다.",
                ),
            )
        }
        raises.maxByOrNull { it.second }?.let { (route, delta) ->
            out += Signal(
                50.0,
                NewsItem(
                    after.turn,
                    NewsKind.RIVAL,
                    "${rival.name}, ${routeLabel(route)} 운임 ${pct(delta)}% 인상",
                    "값을 그대로 두면 손님이 우리 쪽으로 옵니다.",
                ),
            )
        }
        return out
    }

    // ------------------------------------------------------------------ 표현

    private fun tone(trait: Trait) = when (trait) {
        Trait.EXPAND -> "물량으로 밀어붙이는 회사입니다."
        Trait.VALUE -> "값을 낮게 매기는 회사라 운임 싸움이 붙습니다."
        Trait.PREMIUM -> "상위 고객을 노리는 회사라 앞자리부터 흔들립니다."
        Trait.BALANCED -> ""
    }

    private fun routeLabel(r: Route) = "${Cities.name(r.from)}–${Cities.name(r.to)}"

    /** 들어온 규모 — 주 몇 회짜리 진입인지가 위협의 크기다. */
    private fun weekly(rs: List<Route>): String {
        val freq = rs.sumOf { it.freq }
        return if (freq > 0) " 노선을 주 ${freq}회로 열었습니다" else " 노선을 열었습니다"
    }

    private fun routeList(rs: List<Route>) =
        rs.take(2).joinToString(", ") { routeLabel(it) } + if (rs.size > 2) " 등" else ""

    private fun pct(v: Double) = (v * 100).roundToInt()

    /** 도시쌍은 방향이 없다 — 서울→도쿄와 도쿄→서울은 같은 시장이다. */
    private fun pairKey(a: String, b: String) = if (a < b) "$a|$b" else "$b|$a"
}
