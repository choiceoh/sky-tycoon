package skytycoon.core.sim

import skytycoon.core.data.AircraftCatalog
import skytycoon.core.data.Cities
import skytycoon.core.data.Difficulties
import skytycoon.core.model.Airline
import skytycoon.core.model.AircraftType
import skytycoon.core.model.BusinessType
import skytycoon.core.model.City
import skytycoon.core.model.GameState
import skytycoon.core.model.Plane
import skytycoon.core.model.Region
import skytycoon.core.model.Route
import skytycoon.core.model.Trait
import kotlin.math.exp

/**
 * 경쟁사 두뇌. 플레이어와 똑같이 [Actions] 를 통해서만 세계를 바꾸므로
 * AI 가 규칙을 우회하는 일은 구조적으로 생기지 않는다.
 */
object Ai {

    /**
     * @param includePlayer 플레이어 회사까지 AI 두뇌에 맡긴다 (자동조종 · 밸런스 검증용).
     */
    fun actAll(state: GameState, rng: Rng, includePlayer: Boolean = false): GameState {
        var s = state
        for (airline in state.livingAirlines.filter { includePlayer || !it.isPlayer }) {
            if (s.airlineOrNull(airline.id)?.alive != true) continue
            s = act(s, airline.id, rng)
        }
        return s
    }

    /** 플레이어 회사 하나만 AI 에게 맡긴다. */
    fun autoPilot(state: GameState, airlineId: String, rng: Rng): GameState =
        if (state.airlineOrNull(airlineId)?.alive == true) act(state, airlineId, rng) else state

    private fun act(state: GameState, airlineId: String, rng: Rng): GameState {
        var s = state
        val skill = Difficulties[s.difficultyId].aiSkill

        s = tuneRoutes(s, airlineId, rng, skill)
        s = pruneRoutes(s, airlineId, rng)
        s = growFrequency(s, airlineId)
        s = upgauge(s, airlineId, skill)
        s = manageFleet(s, airlineId, rng, skill)
        // 발주(2분기)를 기다릴 수 없는 자리를 리스로 메운다 — manageFleet 이 유휴기를
        // 다 쓰고 난 뒤라야 "정말 없는지"가 판가름 난다.
        s = leaseForUrgentCapacity(s, airlineId, rng, skill)
        s = manageSlots(s, airlineId, rng, skill)
        s = expandAirports(s, airlineId, skill)
        s = openRoutes(s, airlineId, rng, skill)
        s = finance(s, airlineId)
        s = tuneCabin(s, airlineId)
        s = marketing(s, airlineId, skill)
        s = sideBusiness(s, airlineId, rng)
        // 노선을 다 벌인 **뒤에** 남는 슬롯을 정리한다. 앞에 두면 확장으로 막 받은
        // 우선 배정분을 openRoutes 가 써 보기도 전에 반납해 버린다 — 큰돈과 6분기를
        // 들여 얻은 것을 그 자리에서 버리는 셈이다.
        s = shedIdleSlots(s, airlineId)
        s = stockMoves(s, airlineId, rng, skill)
        return s
    }

    private fun cmd(state: GameState, c: Command): GameState = Actions.execute(state, c).state

    /**
     * 지금 붙일 수 있는 유휴기 — **이번 분기 중정비로 묶인 기체는 뺀다**.
     *
     * 입고는 AI 가 움직이기 전에 정해지므로(TurnEngine.advance), 그냥 `routeId == null`
     * 로 세면 뜨지도 못할 기체를 놓고 노선을 열거나 급한 리스를 접는다.
     */
    private fun idlePlanes(state: GameState, airlineId: String) =
        state.planesOf(airlineId).filter { it.routeId == null && !it.inCheck(state.turn) }

    // ------------------------------------------------------------- 운임·편수 조정

    private fun targetFare(trait: Trait): Double = when (trait) {
        Trait.VALUE -> 0.86
        Trait.PREMIUM -> 1.18
        Trait.EXPAND -> 0.96
        Trait.BALANCED -> 1.0
    }

    private fun tuneRoutes(state: GameState, airlineId: String, rng: Rng, skill: Double): GameState {
        var s = state
        val airline = s.airline(airlineId)
        val anchor = targetFare(airline.trait)
        for (route in s.routesOf(airlineId)) {
            val last = route.last ?: continue
            // 못 뜬 분기는 값을 매기는 근거가 못 된다. 좌석이 0 이면 탑승률도 0 으로 잡히는데,
            // 그걸 "텅 비어서 안 팔렸다"로 읽으면 **뜨지도 않은 분기마다 운임을 깎는다**.
            // 정비로 한 분기 쉬는 일이 흔해지면서 이 구멍이 노선 운임을 계속 끌어내렸다
            // (10년 자동조종에서 기업가치가 1397M → 371M). pruneRoutes 와 같은 이유의 방어다.
            if (last.seats <= 0.0) continue
            val lf = last.loadFactor
            var fare = route.fareMul
            // 만석이면 값을 올리고, 텅 비면 내린다. 실력이 낮은 AI 는 반응이 굼뜨고 잡음이 섞인다.
            fare *= when {
                lf > 0.90 -> 1.0 + 0.05 * skill
                lf > 0.82 -> 1.0 + 0.02 * skill
                lf < 0.50 -> 1.0 - 0.06 * skill
                lf < 0.65 -> 1.0 - 0.03 * skill
                else -> 1.0
            }
            fare += rng.range(-0.02, 0.02) * (2.0 - skill)
            // 회사 성향이 정한 기준선으로 서서히 끌려간다.
            fare = fare * 0.85 + anchor * 0.15
            s = cmd(s, Command.TuneRoute(airlineId, route.id, fareMul = fare.coerceIn(0.6, 1.7)))
        }
        return s
    }

    /**
     * 돈이 안 되고 손님도 없는 노선은 접는다.
     *
     * [RouteResult.cost] 에는 그 노선이 물고 있는 슬롯의 임차료가 이미 들어 있다
     * (TurnEngine.settle 참고). 그래서 여기서 따로 더할 것 없이 [RouteResult.profit]
     * 만 보면 **슬롯값까지 갚고 남는가**를 판단하는 셈이 된다.
     */
    private fun pruneRoutes(state: GameState, airlineId: String, rng: Rng): GameState {
        var s = state
        for (route in s.routesOf(airlineId)) {
            val last = route.last ?: continue
            // 공항 폐쇄로 아예 못 뜬 분기는 판단 근거가 못 된다. 지금은 결산이 그런
            // 분기에 빈 결과(원가 0)를 남겨서 아래 부등식이 어차피 안 걸리지만, 임차료를
            // 노선에 물리는 방식이 바뀌면 곧바로 "화산 한 번에 멀쩡한 간선을 영구히
            // 접는" 버그가 된다 — 실제로 그렇게 만들었다가 되돌렸다. 명시해 둔다.
            // 좌석이 0 이면 "띄웠는데 텅 빈" 것이 아니라 아예 못 뜬 것이다.
            if (last.seats <= 0.0) continue
            val hopeless = last.profit < 0 && last.loadFactor < 0.48
            if (hopeless && rng.chance(0.45)) {
                s = cmd(s, Command.CloseRoute(airlineId, route.id))
            }
        }
        return s
    }

    /**
     * 슬롯이 동난 요지에 확장 공사를 건다. 후기에는 이게 유일한 성장 수단이라,
     * 안 쓰면 AI 가 현금만 쌓아두고 멈춰 선다.
     */
    private fun expandAirports(state: GameState, airlineId: String, skill: Double): GameState {
        val a = state.airline(airlineId)
        // 내가 뜨는 도시 중 매물이 마른 곳.
        val candidates = state.routesOf(airlineId)
            .filter { it.active }
            .flatMap { listOf(it.from, it.to) }
            .distinct()
            .filter { cityId ->
                val city = Cities[cityId]
                val extra = state.cityState[cityId]?.extraSlots ?: 0
                // 완전히 마르기를 기다릴 필요는 없다 — 바닥이 보이면 이미 병목이다.
                val dry = state.unsoldSlots(city) <= state.totalSlots(city.id) * 0.05
                dry && !Actions.expansionInProgress(state, cityId)
            }
        if (candidates.isEmpty()) return state

        // 내 편수가 많이 걸린 공항부터 — 거기가 병목이다.
        val target = candidates.maxByOrNull { state.usedSlots(airlineId, it) } ?: return state
        val cost = Actions.expansionCost(state, airlineId, target)
        val want = cost * (1.2 + (1.0 - skill))

        // 빚을 내서까지 지르지는 않는다 — 이자만으로 흑자 회사가 자본잠식에 빠진다.
        if (a.cash < want) return state
        return cmd(state, Command.FundExpansion(airlineId, target))
    }

    /** 잘 나가는 노선에 편수를 붙이고, 남는 기재를 밀어 넣는다. */
    private fun growFrequency(state: GameState, airlineId: String): GameState {
        var s = state
        val ranked = s.routesOf(airlineId)
            .filter { (it.last?.loadFactor ?: 0.0) > 0.84 }
            .sortedByDescending { it.last?.profit ?: 0.0 }

        for (route in ranked) {
            val current = s.routes.firstOrNull { it.id == route.id } ?: continue
            val dist = Geo.distance(current.from, current.to)
            // 이번 분기에 **실제로 뜨는** 기재로 센다. 배속 목록으로 세면 정비 들어간
            // 기체의 수송력까지 있다고 보고 편수를 올려 슬롯을 쓰는데, 시장은 그 좌석을
            // 그 자리에서 걷어간다 — 결산과 같은 목록을 봐야 한다.
            val onRoute = s.flyingOn(current.id)
            val cap = Economics.capacity(onRoute, dist)

            if (current.freq < cap.maxFreq) {
                s = cmd(s, Command.TuneRoute(airlineId, current.id, freq = current.freq + 1))
                continue
            }
            // 기재가 한계면 유휴기를 추가 투입한다.
            val idle = idlePlanes(s, airlineId).firstOrNull {
                Economics.canFly(AircraftCatalog[it.typeId], dist)
            } ?: continue
            s = cmd(s, Command.AssignPlanes(airlineId, current.id, current.planeIds + idle.id))
            val grown = s.routes.firstOrNull { it.id == current.id } ?: continue
            val newCap = Economics.capacity(s.flyingOn(grown.id), dist)
            val want = minOf(
                newCap.maxFreq,
                grown.freq + s.freeSlots(airlineId, grown.from).coerceAtLeast(0),
                grown.freq + s.freeSlots(airlineId, grown.to).coerceAtLeast(0),
            )
            if (want > grown.freq) {
                s = cmd(s, Command.TuneRoute(airlineId, grown.id, freq = want))
            }
        }
        return s
    }

    /**
     * 슬롯이 동나 편수를 못 늘리는데 만석인 노선은, 더 큰 기재로 갈아타는 수밖에 없다.
     * 이걸 안 하면 허브가 포화된 순간부터 회사가 현금만 쌓아두고 멈춰 선다.
     */
    private fun upgauge(state: GameState, airlineId: String, skill: Double): GameState {
        var s = state
        val stuck = s.routesOf(airlineId).filter { r ->
            val lf = r.last?.loadFactor ?: 0.0
            if (lf <= 0.88) return@filter false
            val slotRoom = minOf(s.freeSlots(airlineId, r.from), s.freeSlots(airlineId, r.to))
            val soldOut = s.unsoldSlots(Cities[r.from]) == 0 || s.unsoldSlots(Cities[r.to]) == 0
            slotRoom <= 0 && soldOut
        }.sortedByDescending { it.last?.pax ?: 0.0 }

        for (route in stuck.take(2)) {
            val current = s.routes.firstOrNull { it.id == route.id } ?: continue
            val dist = Geo.distance(current.from, current.to)
            // 정비 중인 기체는 갈아 끼울 대상에서 뺀다. 남겨 두면 배속을 풀고 그 자리에서
            // 팔아 버려, 이미 입고된 기체의 **중정비비를 통째로 피한다** (결산은 그때
            // 남아 있는 기단만 청구한다).
            val onRoute = s.flyingOn(current.id)
            if (onRoute.isEmpty()) continue
            val smallest = onRoute.minByOrNull { AircraftCatalog[it.typeId].seats } ?: continue
            val smallestSeats = AircraftCatalog[smallest.typeId].seats

            // 이미 가진 유휴기 중에 더 큰 게 있으면 바꿔 단다.
            val idleBigger = idlePlanes(s, airlineId)
                .filter {
                    Economics.canFly(AircraftCatalog[it.typeId], dist) &&
                        AircraftCatalog[it.typeId].seats > smallestSeats
                }
                .maxByOrNull { AircraftCatalog[it.typeId].seats }

            if (idleBigger != null) {
                val swapped = current.planeIds - smallest.id + idleBigger.id
                s = cmd(s, Command.AssignPlanes(airlineId, current.id, swapped))
                // 빌린 기체는 못 판다. 실패해도 상태는 그대로라 그냥 세워 두게 된다.
                if (!smallest.leased) s = cmd(s, Command.SellAircraft(airlineId, smallest.id))
                continue
            }

            // 없으면 발주한다.
            val airline = s.airline(airlineId)
            val reserve = Balance.AI_CASH_FLOOR * s.world.inflation
            val bigger = AircraftCatalog.newFor(s.year, s.airline(airlineId).home)
                .filter { Economics.canFly(it, dist) && it.seats > smallestSeats * 1.25 }
                .filter { it.price < (airline.cash - reserve) * 0.5 * skill }
                .maxByOrNull { it.seats } ?: continue
            s = cmd(s, Command.BuyAircraft(airlineId, bigger.id, 1))
        }
        return s
    }

    // ------------------------------------------------------------- 기재

    /** 노선망 평균 거리에 맞는, 좌석당 가격이 가장 싼 기종을 고른다. */
    private fun preferredType(state: GameState, airline: Airline): AircraftType? {
        val routes = state.routesOf(airline.id)
        val typicalDist = if (routes.isEmpty()) {
            2500.0
        } else {
            routes.sumOf { Geo.distance(it.from, it.to) } / routes.size
        }
        val candidates = AircraftCatalog.newFor(state.year, airline.home)
            .filter { Economics.canFly(it, typicalDist) }
        if (candidates.isEmpty()) return null
        return when (airline.trait) {
            Trait.PREMIUM -> candidates.maxByOrNull { it.prestige + it.seats / 200.0 }
            Trait.VALUE -> candidates.minByOrNull { it.price / it.seats }
            else -> candidates.minByOrNull { (it.price / it.seats) * (1.0 + it.fuel / it.seats * 20) }
        }
    }

    /**
     * 지금 당장 붙일 기체가 없을 때 빌린다.
     *
     * 리스의 값어치는 "돈이 없을 때"만이 아니라 **"기다릴 수 없을 때"**에도 있다.
     * 발주는 두 분기 뒤에나 오는데 슬롯과 손님은 이번 분기에 있다. 현금이 두둑한
     * 회사도 그 두 분기를 사려고 리스를 쓴다 — 이 경로가 없으면 AI 는 현금이 마를
     * 때만 빌리는데, 실제 판에서 AI 는 늘 현금이 남아 리스를 한 번도 안 쓴다
     * (FleetProbe 로 확인: 리스 비중 0.0%).
     */
    private fun leaseForUrgentCapacity(state: GameState, airlineId: String, rng: Rng, skill: Double): GameState {
        val s = state
        val airline = s.airline(airlineId)
        if (Leasing.leaseRoom(s, airline) < 1) return s
        val idle = idlePlanes(s, airlineId)

        // 좌석이 모자란 노선이 실제로 있고, 편수를 더 넣을 슬롯도 남아 있어야 한다.
        // **그 거리를 날 수 있는 유휴기가 없을 때**만 빌린다 — "유휴기가 하나도 없을 때"로
        // 재면 안 된다. 항속거리가 모자라 못 쓰는 기체가 늘 몇 대 세워져 있어서
        // (기단의 15%), 그 조건으로는 리스가 한 번도 안 걸린다.
        val bursting = s.routesOf(airlineId).firstOrNull { r ->
            if ((r.last?.loadFactor ?: 0.0) <= 0.88) return@firstOrNull false
            if (minOf(s.freeSlots(airlineId, r.from), s.freeSlots(airlineId, r.to)) <= 0) return@firstOrNull false
            val d = Geo.distance(r.from, r.to)
            idle.none { Economics.canFly(AircraftCatalog[it.typeId], d) }
        } ?: return s

        val dist = Geo.distance(bursting.from, bursting.to)
        val type = AircraftCatalog.newFor(s.year, airline.home)
            .filter { Economics.canFly(it, dist) }
            .maxByOrNull { it.seats } ?: return s

        val term = Balance.LEASE_TERMS.max()
        if (!Actions.arrivesBeforeEnd(s, term)) return s
        val revenue = airline.lastResult?.totalRevenue ?: 0.0
        if (revenue <= 0 || Leasing.quarterlyRate(type, term) > revenue * Balance.AI_LEASE_REVENUE_SHARE) return s
        if (!rng.chance(0.5 * skill)) return s

        val before = s.planesOf(airlineId).map { it.id }.toSet()
        var next = cmd(s, Command.LeaseAircraft(airlineId, type.id, 1, term))
        // **빌린 자리에 바로 붙인다.** 그러지 않으면 이번 분기 수송력은 그대로인데
        // 리스료만 나가기 시작하고, 뒤이어 도는 openRoutes 가 그 기체를 엉뚱한 새 노선에
        // 가져다 쓴다 — 특정 노선이 모자라서 빌린 것이라는 이유가 통째로 사라진다.
        val leased = next.planesOf(airlineId).firstOrNull { it.id !in before } ?: return next
        val route = next.routes.firstOrNull { it.id == bursting.id } ?: return next
        next = cmd(next, Command.AssignPlanes(airlineId, route.id, route.planeIds + leased.id))

        val grown = next.routes.firstOrNull { it.id == route.id } ?: return next
        val newCap = Economics.capacity(next.flyingOn(grown.id), dist)
        val want = minOf(
            newCap.maxFreq,
            grown.freq + next.freeSlots(airlineId, grown.from).coerceAtLeast(0),
            grown.freq + next.freeSlots(airlineId, grown.to).coerceAtLeast(0),
        )
        if (want > grown.freq) next = cmd(next, Command.TuneRoute(airlineId, grown.id, freq = want))
        return next
    }

    private fun manageFleet(state: GameState, airlineId: String, rng: Rng, skill: Double): GameState {
        var s = state
        val airline = s.airline(airlineId)
        val planes = s.planesOf(airlineId)

        // 25년 넘은 기재는 정리한다 (리스기는 팔 수 없다 — 기간이 끝나면 알아서 돌아간다).
        // 정비 중인 기체는 처분 대상에서도 뺀다 — 뜯어 놓은 기체를 그 분기에 팔지는 않는다.
        for (p in planes.filter { it.routeId == null && !it.leased && !it.inCheck(s.turn) && it.ageQuarters > 100 }) {
            s = cmd(s, Command.SellAircraft(airlineId, p.id))
        }

        val idle = idlePlanes(s, airlineId).size
        if (idle >= 4) return s

        val type = preferredType(s, s.airline(airlineId)) ?: return s
        val aggression = when (airline.trait) {
            Trait.EXPAND -> 1.35
            Trait.VALUE -> 1.1
            Trait.PREMIUM -> 0.9
            Trait.BALANCED -> 1.0
        } * skill

        val cash = s.airline(airlineId).cash
        val affordable = ((cash - Balance.AI_CASH_FLOOR * s.world.inflation) / type.price).toInt()
        val want = (affordable.coerceAtMost(Balance.AI_MAX_ORDERS) * aggression).toInt()
        if (want >= 1) {
            // 확장형·저가형은 기재를 **빌려서** 늘린다. 현금을 슬롯과 노선에 남겨 두고
            // 두 분기를 기다리지 않는 쪽을 고르는 성향이다 (실제 저비용 항공사가 그렇다).
            // 이 경로가 없으면 AI 는 늘 현금이 남아 리스를 한 번도 쓰지 않는다.
            val leaser = airline.trait == Trait.EXPAND || airline.trait == Trait.VALUE
            val term = Balance.LEASE_TERMS.max()
            val room = Leasing.leaseRoom(s, s.airline(airlineId))
            val revenue = s.airline(airlineId).lastResult?.totalRevenue ?: 0.0
            val rentOk = revenue > 0 &&
                Leasing.quarterlyRate(type, term) <= revenue * Balance.AI_LEASE_REVENUE_SHARE
            if (leaser && room >= 1 && rentOk && Actions.arrivesBeforeEnd(s, term) && rng.chance(0.45 * skill)) {
                return cmd(s, Command.LeaseAircraft(airlineId, type.id, minOf(want, room, 2), term))
            }
            s = cmd(s, Command.BuyAircraft(airlineId, type.id, want.coerceAtMost(Balance.AI_MAX_ORDERS)))
            return s
        }
        if (affordable >= 1) return s

        // 살 돈이 없다고 손을 놓지는 않는다 — 빌리는 길이 있다. 목돈 대신 분기 고정비를
        // 지는 선택이라, 매출에 견줘 리스료가 감당할 만할 때만 손을 댄다. 플레이어에게만
        // 열린 수단이면 경쟁사는 현금이 마르는 순간 성장이 멈춰 판이 굳는다.
        val term = Balance.LEASE_TERMS.max()
        val rate = Leasing.quarterlyRate(type, term)
        val revenue = s.airline(airlineId).lastResult?.totalRevenue ?: 0.0
        val canCarry = revenue > 0 && rate <= revenue * Balance.AI_LEASE_REVENUE_SHARE
        if (canCarry && Leasing.leaseRoom(s, s.airline(airlineId)) >= 1 &&
            Actions.arrivesBeforeEnd(s, term) && rng.chance(0.4 * skill * aggression)
        ) {
            return cmd(s, Command.LeaseAircraft(airlineId, type.id, 1, term))
        }
        if (rng.chance(0.3 * skill)) {
            // 신조기가 부담되면 중고를 노린다.
            val used = AircraftCatalog.usedFor(s.year, s.airline(airlineId).home)
                .filter { Economics.canFly(it, 2000.0) }
                .minByOrNull { it.price }
            if (used != null) s = cmd(s, Command.BuyAircraft(airlineId, used.id, 1, used = true))
        }
        return s
    }

    // ------------------------------------------------------------- 슬롯

    /** 허브에 슬롯 여유가 없으면 확장이 막히므로 미리 사둔다. */
    /**
     * 오래 놀리는 슬롯을 반납한다. 슬롯이 분기 임차료를 무는 고정비가 된 뒤로는
     * 쌓아 두는 것만으로 손실이라, 이 정리가 없으면 AI 가 제 살을 깎으며 버틴다.
     * 홈 공항은 성장 여지로 조금 남겨 둔다.
     */
    private fun shedIdleSlots(state: GameState, airlineId: String): GameState {
        var s = state
        val home = s.airline(airlineId).home
        // 기재를 발주해 둔 동안에는 슬롯을 놓지 않는다. 인도까지 두 분기가 걸리는데
        // 그사이에 반납해 버리면, 비행기가 도착했을 때 띄울 자리가 없다 — 확장에
        // 큰돈을 쓰고 기재까지 발주해 놓고 정작 둘을 못 만나게 하는 셈이다.
        if (s.orders.any { it.airlineId == airlineId }) return s
        for (city in s.airline(airlineId).slots.keys.toList()) {
            val idle = s.freeSlots(airlineId, city)
            val keep = if (city == home) 6 else 2
            // 남는 몫을 한 번에 다 반납하지 않고 **절반씩** 흘려보낸다.
            // openRoutes 는 한 턴에 새 노선을 3개까지만 여니, 확장으로 받은 30 슬롯을
            // 그 턴에 다 쓸 수 없다. 즉시 정리하면 큰돈과 6분기를 들여 얻은 우선
            // 배정분을 다음 턴이 써 보기도 전에 버린다. 절반씩이면 정말 노는 슬롯은
            // 몇 턴 안에 정리되면서도, 막 받은 슬롯에는 쓸 시간이 생긴다.
            val excess = idle - keep
            val drop = (excess + 1) / 2
            if (excess > 0 && drop > 0) s = cmd(s, Command.ReleaseSlots(airlineId, city, drop))
        }
        return s
    }

    private fun manageSlots(state: GameState, airlineId: String, rng: Rng, skill: Double): GameState {
        var s = state
        val home = s.airline(airlineId).home
        if (s.freeSlots(airlineId, home) >= 6) return s
        val price = Economics.slotPrice(s, airlineId, home)
        val budget = (s.airline(airlineId).cash - Balance.AI_CASH_FLOOR * s.world.inflation) * 0.25 * skill
        val count = (budget / price).toInt().coerceIn(0, 8)
        if (count >= 1 && s.unsoldSlots(Cities[home]) >= count && rng.chance(0.85)) {
            s = cmd(s, Command.BuySlots(airlineId, home, count))
        }
        return s
    }

    // ------------------------------------------------------------- 신규 노선

    private class Candidate(
        val from: City,
        val to: City,
        val plane: Plane,
        val freq: Int,
        val slotsFrom: Int,
        val slotsTo: Int,
        val slotCost: Double,
        val score: Double,
    )

    /**
     * 취항 후보를 고를 때 **목적지 슬롯 매입비까지 포함해서** 판단한다.
     * 이걸 빼먹으면 AI 는 창업 때 받은 다섯 개 도시 밖으로 영영 나가지 못한다.
     */
    private fun openRoutes(state: GameState, airlineId: String, rng: Rng, skill: Double): GameState {
        var s = state
        var opened = 0
        val limit = (Balance.AI_MAX_NEW_ROUTES * skill).toInt().coerceAtLeast(1)

        while (opened < limit) {
            val airline = s.airline(airlineId)
            val idle = idlePlanes(s, airlineId)
            if (idle.isEmpty()) break

            val reserve = Balance.AI_CASH_FLOOR * s.world.inflation
            val budget = (airline.cash - reserve) * 0.55 * skill
            if (budget <= 0) break

            val served = s.routesOf(airlineId).map { Geo.pairKey(it.from, it.to) }.toSet()
            // 슬롯을 가진 도시는 **전부** 출발 거점이다. 예전에는 많이 가진 순으로 여덟
            // 곳만 봤는데, 후반이면 회사가 스물네 개 도시에 슬롯을 쥐고 있어 제 노선망의
            // 3분의 2가 탐색에서 빠졌다. 그 여덟 곳에서 갈 만한 짝이 소진되는 순간
            // 노선망이 통째로 굳어, 20년 캠페인의 마지막 5년 동안 노선 수가 한 자리도
            // 움직이지 않은 채 현금(자본의 78%)과 유휴기만 쌓였다 — 판이 얼어붙는다.
            val origins = airline.slots.filterValues { it > 0 }.keys

            var best: Candidate? = null
            for (fromId in origins) {
                val from = Cities[fromId]
                if (s.cityState[fromId]?.isClosed(s.turn) == true) continue
                for (to in Cities.all) {
                    if (to.id == fromId) continue
                    if (Geo.pairKey(fromId, to.id) in served) continue
                    if (s.cityState[to.id]?.isClosed(s.turn) == true) continue

                    val dist = Geo.distance(fromId, to.id)
                    val plane = idle
                        .filter { Economics.canFly(AircraftCatalog[it.typeId], dist) }
                        .maxByOrNull { AircraftCatalog[it.typeId].seats } ?: continue

                    val cap = Economics.capacity(listOf(plane), dist)
                    if (cap.maxFreq < 1) continue
                    val freq = minOf(cap.maxFreq, 7)

                    val needFrom = (freq - s.freeSlots(airlineId, fromId)).coerceAtLeast(0)
                    val needTo = (freq - s.freeSlots(airlineId, to.id)).coerceAtLeast(0)
                    if (needFrom > s.unsoldSlots(from) || needTo > s.unsoldSlots(to)) continue

                    // 슬롯값은 한 개 살 때마다 오른다. 현재 단가에 개수를 곱하면 실제보다 싸게
                    // 잡혀, 예산은 통과했는데 BuySlots 가 실패한다 — 그때 반대편 슬롯은 이미
                    // 사둔 뒤라 노선도 못 열고 슬롯만 놀린다. 매입과 같은 계산을 쓴다.
                    val cost = Actions.slotCost(s, airlineId, fromId, needFrom) +
                        Actions.slotCost(s, airlineId, to.id, needTo) +
                        Actions.routeSetupCost(s, fromId, to.id)
                    if (cost > budget) continue

                    // 같은 매력이면 슬롯값이 싼 쪽이 낫다.
                    val score = attractiveness(s, airlineId, from, to) / (1.0 + cost / 60e6)
                    if (best == null || score > best.score) {
                        best = Candidate(from, to, plane, freq, needFrom, needTo, cost, score)
                    }
                }
            }
            val target = best ?: break
            if (target.score <= 0.0) break

            if (target.slotsFrom > 0) {
                s = cmd(s, Command.BuySlots(airlineId, target.from.id, target.slotsFrom))
            }
            if (target.slotsTo > 0) {
                s = cmd(s, Command.BuySlots(airlineId, target.to.id, target.slotsTo))
            }
            val freq = minOf(
                target.freq,
                s.freeSlots(airlineId, target.from.id),
                s.freeSlots(airlineId, target.to.id),
            )
            if (freq < 1) break

            val before = s.routes.size
            s = cmd(
                s,
                Command.OpenRoute(
                    airlineId = airlineId,
                    from = target.from.id,
                    to = target.to.id,
                    planeIds = listOf(target.plane.id),
                    freq = freq,
                    fareMul = targetFare(airline.trait) + rng.range(-0.04, 0.04),
                ),
            )
            if (s.routes.size == before) break
            opened++
        }
        return s
    }

    /** 수요가 크고 경쟁이 옅을수록 매력적이다. */
    private fun attractiveness(state: GameState, airlineId: String, a: City, b: City): Double {
        val demand = Demand.quarterly(state, a, b).total
        if (demand < 1500) return 0.0
        // 여기서는 일부러 [GameState.flyingOn] 이 **아니라** 배속 목록으로 센다.
        //
        // 취항은 몇 해를 가는 결정이라 경쟁자의 **상시 공급**을 봐야 한다. 이번 분기에
        // 상대 기체가 정비에 들어갔다는 건 잡음이지 신호가 아니다. 뜨는 기체만 세면
        // 그 시장이 실제보다 비어 보여, 다음 분기면 다시 꽉 차는 구간에 슬롯을 사서
        // 들어가게 된다 — 아래 로컬 보정이 막으려는 것과 같은 종류의 오판이다.
        //
        // 좌석·원가·편수처럼 **이번 분기에 곧바로 값을 치르는** 계산은 반대로 반드시
        // flyingOn 을 써야 한다 (growFrequency·Market·결산이 그렇다).
        val rivalSeats = state.routes
            .filter { it.active && Geo.pairKey(it.from, it.to) == Geo.pairKey(a.id, b.id) }
            .sumOf { r ->
                val planes = state.planes.filter { it.routeId == r.id }
                val cap = Economics.capacity(planes, Geo.distance(a.id, b.id))
                val seats = Economics.quarterlySeats(r.freq.coerceAtMost(cap.maxFreq), cap.avgSeats)
                // 앞자리를 깐 경쟁자는 실제로 내놓는 좌석이 그만큼 적다. 이코노미 기준으로
                // 세면 그런 시장을 과대평가된 경쟁으로 읽어 멀쩡한 노선을 피한다.
                val rival = state.airlineOrNull(r.airlineId)
                if (rival == null) seats else Economics.cabin(seats, rival.bizShare).total
            }
        val airline = state.airline(airlineId)
        val homeBonus = if (a.id == airline.home || b.id == airline.home) 1.35 else 1.0
        val brandBonus = 1.0 + (airline.brandIn(a.region) + airline.brandIn(b.region)) / 400.0
        // 모델에 있는 경쟁자만 세면 절반만 보는 것이다. 로컬 항공사는 어느 구간에나
        // 있고 세기가 구간마다 다르므로, 로컬이 억센 시장은 그만큼 깎아 본다 —
        // 이게 없으면 AI 는 편차를 못 읽고 하필 빡센 시장만 골라 들어간다.
        val local = exp(Market.localStrength(a, b))
        return demand / (1.0 + rivalSeats / 1000.0) / (1.0 + local) * homeBonus * brandBonus
    }

    /**
     * 비즈니스 객실 비중을 노선망의 **출장 수요 비중**에 맞춘다.
     *
     * 객실은 공짜가 아니다 — 깔아 놓은 좌석만큼 유지비가 나가고 바닥도 잡아먹으므로,
     * 프리미엄 수요보다 크게 깔면 빈 채로 날며 손해다. 그래서 내가 실제로 나는 구간의
     * 출장 비중을 보고 정한다. 성향은 그 위에 얹는다 (프리미엄은 더, 저가는 덜).
     */
    private fun tuneCabin(state: GameState, airlineId: String): GameState {
        val routes = state.routesOf(airlineId).filter { it.active }
        if (routes.isEmpty()) return state
        val bizShare = routes
            .map { Demand.annualBase(Cities[it.from], Cities[it.to]) }
            .let { ds ->
                val total = ds.sumOf { it.total }
                if (total <= 0.0) 0.0 else ds.sumOf { it.business } / total
            }
        val a = state.airline(airlineId)
        val trait = when (a.trait) {
            Trait.PREMIUM -> 1.25
            Trait.VALUE -> 0.55
            else -> 1.0
        }
        // 앞자리를 실제로 팔아낼 수 있는 만큼만 깐다. 서비스 등급·브랜드·부대시설이
        // 좋은 회사는 같은 객실을 더 채우므로 더 크게 깔아도 남는다 — 이걸 안 보면
        // 대접에 투자한 회사가 그 값을 못 받고, 아홉 회사가 다 같은 크기로 수렴한다.
        val appeal = Market.networkPremiumTakeup(a, routes) / Balance.BIZ_CABIN_TAKEUP
        // 출장 비중이 절반이면 바닥의 20% 안팎이 최적이었다 (CabinProbe 참고).
        val want = (bizShare * 0.38 * trait * appeal).coerceIn(0.0, Balance.BIZ_SHARE_MAX)
        val now = a.bizShare
        if (kotlin.math.abs(want - now) < 0.02) return state
        return cmd(state, Command.SetCabin(airlineId, want))
    }

    // ------------------------------------------------------------- 재무·마케팅·부대사업

    private fun finance(state: GameState, airlineId: String): GameState {
        var s = state
        val a = s.airline(airlineId)
        val floor = Balance.AI_CASH_FLOOR * s.world.inflation
        if (a.cash < floor) {
            val room = (Actions.debtCapacity(s, a) - a.debt).coerceAtLeast(0.0)
            val need = (floor * 3 - a.cash).coerceAtMost(room)
            if (need > 1e6) s = cmd(s, Command.Loan(airlineId, need))
        } else if (a.debt > 0 && a.cash > floor * 4) {
            // 예전에는 현금이 운영 바닥의 8배는 쌓여야 갚기 시작했다. 그래서 빚을 지고
            // 나면 캠페인 내내 이자만 물었다 — 12판 중 기업가치가 줄어든 두 판이 모두
            // 부채 최상위였다 (GrowthDiag). 정비·리스로 고정비가 늘어난 뒤로는 그
            // 이자가 더 무겁다. 여유가 생기는 대로 갚되, 운영 현금은 남긴다.
            val repay = minOf(a.debt, a.cash - floor * 3)
            if (repay > 1e6) s = cmd(s, Command.Loan(airlineId, -repay))
        }
        return s
    }

    private fun marketing(state: GameState, airlineId: String, skill: Double): GameState {
        var s = state
        val a = s.airline(airlineId)
        val revenue = a.lastResult?.totalRevenue ?: 0.0
        val budget = revenue * 0.035 * skill
        val regions = s.routesOf(airlineId)
            .flatMap { listOf(Cities[it.from].region, Cities[it.to].region) }
            .distinct()
            .ifEmpty { listOf(Cities[a.home].region) }
        val each = if (regions.isEmpty()) 0.0 else budget / regions.size
        for (r in Region.entries) {
            val amount = if (r in regions) each else 0.0
            s = cmd(s, Command.SetAdBudget(airlineId, r, amount))
        }
        if (a.trait == Trait.PREMIUM && a.serviceLevel < 5 &&
            a.cash > Actions.serviceUpgradeCost(s, a) * 6
        ) {
            s = cmd(s, Command.UpgradeService(airlineId))
        }
        return s
    }

    private fun sideBusiness(state: GameState, airlineId: String, rng: Rng): GameState {
        var s = state
        val a = s.airline(airlineId)
        if (a.cash < 220e6 * s.world.inflation) return s
        if (!rng.chance(0.35)) return s

        // 취항 중인 도시에만 낼 수 있고, 정비창은 전사 하나뿐이다 (BuildBusiness 규칙과 맞춘다).
        val served = s.routesOf(airlineId)
            .filter { it.active }
            .flatMap { listOf(it.from, it.to) }
            .distinct()
        if (served.isEmpty()) return s
        val candidates = BusinessType.entries.filter { t ->
            t != BusinessType.HANGAR || a.businesses.none { it.type == BusinessType.HANGAR }
        }
        if (candidates.isEmpty()) return s
        // 프리미엄 성향은 앞자리를 파는 시설(라운지·호텔)에 무게를 둔다. 무작위로만
        // 고르면 성향이 부대사업에 드러나지 않아, 고급 노선을 표방하는 회사가 여행사만
        // 잔뜩 짓고 정작 프리미엄 매력은 평범한 일이 생긴다.
        val type = if (a.trait == Trait.PREMIUM) {
            val posh = candidates.filter { it.premiumAppeal > 0.0 }
            rng.pick(posh.ifEmpty { candidates })
        } else {
            rng.pick(candidates)
        }
        val city = served
            .filter { c -> a.businesses.none { it.type == type && it.city == c } }
            .maxByOrNull { a.slotsAt(it) } ?: return s
        return cmd(s, Command.BuildBusiness(airlineId, type, city))
    }

    /**
     * 지분 매집. 아무나 노리지 않는다 — 실적이 무너졌거나 자기보다 한참 작은 회사만 사냥한다.
     * 그리고 자기가 사냥당하는 중이면 먼저 유상증자로 방어한다.
     */
    /** 이만큼 쌓았으면 인수전에 발을 담근 것으로 본다 — 그 뒤로는 매 분기 밀어붙인다. */
    private const val COMMITTED_STAKE = 0.15

    private fun stockMoves(state: GameState, airlineId: String, rng: Rng, skill: Double): GameState {
        var s = defendOwnership(state, airlineId)
        val a = s.airline(airlineId)
        // 이미 발을 담근 인수전은 성향과 무관하게 매 분기 밀어붙인다. 넉 분기에 한 번씩만
        // 사들이면 그사이 방어측이 증자로 30% 를 희석해(4분기 쿨다운) 매집분이 그대로
        // 상쇄된다 — 지분이 3분의 1 언저리에서 굳고 20년을 굴려도 인수가 한 건도 나지
        // 않았다. 한번 시작했으면 몰아쳐야 과반에 닿는다.
        val committed = s.livingAirlines.any {
            it.id != airlineId && Stock.ownershipRatio(s, airlineId, it.id) >= COMMITTED_STAKE
        }
        if (a.trait != Trait.EXPAND && !committed && !rng.chance(0.25)) return s

        val warChest = a.cash - 400e6 * s.world.inflation
        if (warChest <= 0) return s
        val myEquity = Economics.equity(s, a)

        val candidates = s.livingAirlines
            .filter { it.id != airlineId }
            .filter { rival ->
                // 이미 지분을 쌓아 둔 상대는 조건을 다시 묻지 않는다. 공격받던 회사가
                // 실적을 회복하거나 몸집을 불리면 후보에서 빠지는데, 그러면 여태 모은
                // 지분을 든 채 다른 회사로 갈아타게 된다 — 매 분기 밀어붙이기로 바꾼
                // 의미가 바로 그 상황에서 사라진다.
                if (Stock.ownershipRatio(s, airlineId, rival.id) >= COMMITTED_STAKE) return@filter true
                val equity = Economics.equity(s, rival)
                val ailing = rival.results.takeLast(4).sumOf { r -> r.net } < 0
                // 휘청이는 회사이거나, 내가 두 배 가까이 크면 삼킬 만하다.
                // 3배로 두면 후보가 거의 안 잡혀 인수가 영영 안 일어난다.
                ailing || myEquity > equity * 1.8
            }
        // 쌓아 둔 지분이 있으면 그 상대를 끝까지 판다. 분기마다 더 작은 회사로 갈아타면
        // 어느 쪽도 과반에 못 닿고 현금만 여러 회사에 흩어진다.
        val target = candidates
            .maxByOrNull { Stock.ownershipRatio(s, airlineId, it.id) }
            ?.takeIf { Stock.ownershipRatio(s, airlineId, it.id) >= COMMITTED_STAKE }
            ?: candidates.minByOrNull { Economics.equity(s, it) }
            ?: return s

        val limit = Stock.maxBuyableThisQuarter(s, airlineId, target.id)
        if (limit <= 0) return s
        val budget = warChest * 0.6 * skill
        val unit = Stock.buyCost(s, airlineId, target.id, 1.0)
        if (unit <= 0) return s
        // 과반을 넘기는 물량이면 잔여 지분 정리 대금까지 감당돼야 통과한다. 예산만 보고
        // 크게 지르면 매 분기 거절당하며 49% 에 영영 머문다 — UI 버튼과 같은 계산으로 깎는다.
        val affordable = Stock.affordableShares(s, airlineId, target.id)
        val shares = (budget / unit).coerceAtMost(limit).coerceAtMost(affordable)
        if (shares < target.shares * 0.02) return s
        return cmd(s, Command.TradeShares(airlineId, target.id, shares))
    }

    /** 누군가 25% 넘게 들고 있으면 증자로 희석한다. */
    private fun defendOwnership(state: GameState, airlineId: String): GameState {
        val threat = state.livingAirlines
            .filter { it.id != airlineId }
            .maxOfOrNull { Stock.ownershipRatio(state, it.id, airlineId) } ?: 0.0
        if (threat < 0.25) return state
        val me = state.airline(airlineId)
        if (!Actions.canIssueShares(state, me)) return state
        val issue = Actions.maxIssuable(me) * (if (threat > 0.4) 1.0 else 0.5)
        return cmd(state, Command.IssueShares(airlineId, issue))
    }
}
