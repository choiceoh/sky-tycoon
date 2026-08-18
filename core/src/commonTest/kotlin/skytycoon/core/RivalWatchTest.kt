package skytycoon.core

import skytycoon.core.data.Cities
import skytycoon.core.model.GameState
import skytycoon.core.model.NewsKind
import skytycoon.core.model.Route
import skytycoon.core.sim.Ai
import skytycoon.core.sim.NewGame
import skytycoon.core.sim.Rng
import skytycoon.core.sim.RivalWatch
import skytycoon.core.sim.TurnEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 경쟁사 동향 소식. 여기서 지키는 것은 두 가지다 —
 * **우리에게 닿는 것만** 올라오는가, 그리고 **분기마다 도배되지 않는가**.
 */
class RivalWatchTest {

    private fun freshGame(seed: Int = 7): GameState =
        NewGame.create(seed = seed, companyId = "hanseong")

    /** 우리와 겹치지 않는 곳에서 벌어진 일은 소식이 되지 않는다. */
    @Test
    fun ignoresRoutesWeDoNotTouch() {
        val before = freshGame()
        val rival = before.livingAirlines.first { it.id != before.playerId }
        // 플레이어가 뜨지도, 안방도 아닌 구간.
        val far = before.routes.map { it.id }.maxOrNull()?.plus(1) ?: 1
        val after = before.copy(
            routes = before.routes + Route(
                id = far,
                airlineId = rival.id,
                from = "saopaulo",
                to = "buenosaires",
                freq = 7,
            ),
        )

        val news = RivalWatch.report(before, after)
        assertTrue(news.none { it.headline.contains("상파울루") }, "우리와 무관한 노선이 올라왔다: $news")
    }

    /** 우리가 뜨는 구간에 들어오면 회사 이름과 함께 알려준다. */
    @Test
    fun reportsEntryOnOurRoute() {
        val before = freshGame()
        val mine = before.routesOf(before.playerId).firstOrNull()
            ?: error("시작 노선이 있어야 이 테스트가 성립한다")
        val rival = before.livingAirlines.first { it.id != before.playerId }
        val newId = before.routes.maxOf { it.id } + 1
        val after = before.copy(
            routes = before.routes + Route(
                id = newId,
                airlineId = rival.id,
                from = mine.from,
                to = mine.to,
                freq = 7,
            ),
        )

        val news = RivalWatch.report(before, after)
        assertTrue(news.isNotEmpty(), "우리 노선 진입이 알려지지 않았다")
        assertTrue(news.all { it.kind == NewsKind.RIVAL })
        assertTrue(news.any { it.headline.contains(rival.name) }, "회사 이름이 없다: $news")
    }

    /** 운임은 매 분기 흔들린다 — 잔파동까지 알리면 뉴스가 아니라 잡음이 된다. */
    @Test
    fun ignoresFareNoise() {
        val before = freshGame()
        val mine = before.routesOf(before.playerId).first()
        val rival = before.livingAirlines.first { it.id != before.playerId }
        val shared = Route(
            id = before.routes.maxOf { it.id } + 1,
            airlineId = rival.id,
            from = mine.from,
            to = mine.to,
            fareMul = 1.0,
            freq = 7,
        )
        val base = before.copy(routes = before.routes + shared)

        val nudged = base.copy(routes = base.routes.map { if (it.id == shared.id) it.copy(fareMul = 0.98) else it })
        assertTrue(RivalWatch.report(base, nudged).isEmpty(), "2% 흔들림이 소식이 됐다")

        val slashed = base.copy(routes = base.routes.map { if (it.id == shared.id) it.copy(fareMul = 0.85) else it })
        val news = RivalWatch.report(base, slashed)
        assertTrue(news.any { it.headline.contains("인하") }, "15% 인하가 묻혔다: $news")
    }

    /**
     * 지분 매집은 여기서 만들지 않는다 — [skytycoon.core.sim.Actions] 가 매수 시점에 이미
     * 알리므로, 여기서 또 만들면 같은 매수가 두 줄로 뜨고 분기 상한의 한 자리까지 잡아먹는다.
     */
    @Test
    fun leavesStakeNewsToActions() {
        val before = freshGame()
        val rival = before.livingAirlines.first { it.id != before.playerId }
        val player = before.player

        val after = before.copy(
            airlines = before.airlines.map {
                if (it.id == rival.id) it.copy(holdings = it.holdings + (player.id to player.shares * 0.2)) else it
            },
        )

        assertTrue(
            RivalWatch.report(before, after).none { it.headline.contains("지분") },
            "Actions 가 이미 알리는 지분 매집을 여기서 또 만들었다",
        )
    }

    /**
     * 인수로 넘어온 노선은 새로 연 것이 아니다. [skytycoon.core.sim.Stock] 이 노선 id 를
     * 그대로 두고 주인만 바꾸므로, 그 회사의 지난 분기 목록에만 견주면 물려받은 노선이
     * 통째로 "신규 진입"으로 잡힌다 — 회사 하나를 삼킨 분기에 거짓 경보가 쏟아진다.
     */
    @Test
    fun doesNotMistakeInheritedRoutesForEntry() {
        val before = freshGame()
        val mine = before.routesOf(before.playerId).first()
        val rivals = before.livingAirlines.filter { it.id != before.playerId }
        val acquirer = rivals[0]
        val prey = rivals[1]

        // 먹잇감이 우리 안방과 우리 노선에 이미 떠 있었다.
        val preyRoutes = listOf(
            Route(before.routes.maxOf { it.id } + 1, prey.id, mine.from, mine.to, freq = 7),
            Route(before.routes.maxOf { it.id } + 2, prey.id, before.player.home, "sydney", freq = 7),
        )
        val base = before.copy(routes = before.routes + preyRoutes)

        // 인수 — 노선 id 는 그대로, 주인만 바뀌고 먹잇감은 사라진다.
        val after = base.copy(
            airlines = base.airlines.map { if (it.id == prey.id) it.copy(alive = false) else it },
            routes = base.routes.map { if (it.airlineId == prey.id) it.copy(airlineId = acquirer.id) else it },
        )

        val news = RivalWatch.report(base, after)
        assertTrue(
            news.none { it.headline.contains("진입") || it.headline.contains("취항") },
            "인수로 물려받은 노선을 신규 진입으로 알렸다: $news",
        )
    }

    /**
     * 물려받은 노선이 **원래 있던 노선에 합쳐지는** 경우도 인수로 알아봐야 한다.
     * `mergeDuplicateRoutes` 가 남의 노선 id 를 버리고 편수만 더하므로, 노선 id 만 보면
     * 인수를 놓치고 합쳐진 편수가 "증편"으로 나간다. 기재 id 는 그대로 넘어온다.
     */
    @Test
    fun detectsTakeoverEvenWhenInheritedRouteIdsVanish() {
        val before = freshGame()
        val mine = before.routesOf(before.playerId).first()
        val rivals = before.livingAirlines.filter { it.id != before.playerId }
        val acquirer = rivals[0]
        val prey = rivals[1]

        // 둘 다 우리 노선과 같은 구간을 이미 날고 있다.
        val acquirerRoute = Route(before.routes.maxOf { it.id } + 1, acquirer.id, mine.from, mine.to, freq = 7)
        val preyPlane = before.planesOf(prey.id).firstOrNull() ?: error("먹잇감에 기재가 있어야 한다")
        val base = before.copy(routes = before.routes + acquirerRoute)

        // 인수 결과: 먹잇감의 노선 id 는 사라지고 인수사 노선의 편수만 14 로 불어난다.
        // 기재는 id 그대로 인수사에게 넘어간다.
        val after = base.copy(
            airlines = base.airlines.map { if (it.id == prey.id) it.copy(alive = false) else it },
            routes = base.routes
                .filter { it.airlineId != prey.id }
                .map { if (it.id == acquirerRoute.id) it.copy(freq = 14) else it },
            planes = base.planes.map { if (it.id == preyPlane.id) it.copy(airlineId = acquirer.id) else it },
        )

        val news = RivalWatch.report(base, after)
        assertTrue(
            news.none { it.headline.contains("증편") },
            "인수로 합쳐진 편수를 증편으로 알렸다: $news",
        )
    }

    /**
     * `stockMoves` 는 `Ai.act` 의 맨 끝이라 같은 분기에 노선을 열고 회사를 삼킬 수 있다.
     * 인수 흔적을 걸러내겠다고 그 분기 소식을 통째로 묻으면, 진짜 진입이 하필
     * 인수 분기에만 사라진다.
     */
    @Test
    fun keepsRealEntriesInTakeoverQuarters() {
        val before = freshGame()
        val mine = before.routesOf(before.playerId).first()
        val rivals = before.livingAirlines.filter { it.id != before.playerId }
        val acquirer = rivals[0]
        val prey = rivals[1]
        val preyPlane = before.planesOf(prey.id).firstOrNull() ?: error("먹잇감에 기재가 있어야 한다")

        val preyRoute = Route(before.routes.maxOf { it.id } + 1, prey.id, "sydney", "madrid", freq = 7)
        val base = before.copy(routes = before.routes + preyRoute)

        // 같은 분기에 (1) 우리 노선에 진짜로 진입하고 (2) 먹잇감을 삼켰다.
        val freshRoute = Route(base.routes.maxOf { it.id } + 1, acquirer.id, mine.from, mine.to, freq = 7)
        val after = base.copy(
            airlines = base.airlines.map { if (it.id == prey.id) it.copy(alive = false) else it },
            routes = base.routes.map { if (it.airlineId == prey.id) it.copy(airlineId = acquirer.id) else it } +
                freshRoute,
            planes = base.planes.map { if (it.id == preyPlane.id) it.copy(airlineId = acquirer.id) else it },
        )

        val news = RivalWatch.report(base, after)
        // 우리 노선이 안방을 지나면 "진입" 이 아니라 "안방 취항" 으로 뜬다 — 어느 쪽이든
        // 그 회사 이름과 새 노선의 도시가 소식에 남아 있어야 한다.
        val opened = news.filter { it.headline.contains(acquirer.name) }
        assertTrue(opened.isNotEmpty(), "인수 분기라는 이유로 진짜 진입까지 묻었다: $news")
        assertTrue(
            opened.any { Cities.name(freshRoute.to) in it.headline + it.body },
            "새로 연 노선이 소식에 없다: $opened",
        )
        assertTrue(
            news.none { "시드니" in it.headline + it.body || "마드리드" in it.headline + it.body },
            "물려받은 노선을 신규 진입으로 알렸다: $news",
        )
    }

    /** 한 분기에 쏟아지는 양을 묶어 자른다 — 안 그러면 뉴스 탭이 경쟁사 로그가 된다. */
    @Test
    fun capsPerQuarter() {
        val before = freshGame()
        val mine = before.routesOf(before.playerId).first()
        var nextId = before.routes.maxOf { it.id } + 1
        val flood = before.livingAirlines.filter { it.id != before.playerId }.flatMap { rival ->
            List(3) {
                Route(id = nextId++, airlineId = rival.id, from = mine.from, to = mine.to, freq = 7)
            }
        }
        val news = RivalWatch.report(before, before.copy(routes = before.routes + flood))
        assertTrue(news.size <= 4, "한 분기에 ${news.size}건이 올라왔다")
    }

    /** 실제 캠페인에서도 소식이 나오고, 그 양이 감당할 만한가. */
    @Test
    fun realCampaignProducesRivalNewsAtReasonableRate() {
        var s = freshGame(seed = 42)
        val rng = Rng(99)
        repeat(24) {
            if (s.outcome != null) return@repeat
            s = TurnEngine.advance(Ai.autoPilot(s, s.playerId, rng))
        }
        val rivalNews = s.news.count { it.kind == NewsKind.RIVAL }
        assertTrue(rivalNews > 0, "6년을 굴렸는데 경쟁사 소식이 하나도 없다")
        assertTrue(rivalNews <= 24 * 4, "분기당 4건 상한을 넘겼다: $rivalNews")
    }

    /** 인수당해 사라진 회사로 소식을 만들다 터지지 않는가. */
    @Test
    fun survivesDeadRivals() {
        val before = freshGame()
        val rival = before.livingAirlines.first { it.id != before.playerId }
        val after = before.copy(
            airlines = before.airlines.map { if (it.id == rival.id) it.copy(alive = false) else it },
            routes = before.routes.filter { it.airlineId != rival.id },
        )
        assertEquals(emptyList(), RivalWatch.report(before, after).filter { it.headline.contains(rival.name) })
    }
}
