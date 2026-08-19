package skytycoon.core.sim

import skytycoon.core.data.AircraftCatalog
import skytycoon.core.data.Cities
import skytycoon.core.model.Airline
import skytycoon.core.model.City
import skytycoon.core.model.GameState
import skytycoon.core.model.Route
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * 한 노선이 이번 분기에 실제로 실어 나른 결과.
 *
 * [bizPax]·[leiPax] 는 이 구간이 목적지인 **로컬 승객**이고, [connectPax] 는 이 노선을
 * 중간 구간으로 쓴 **환승 승객**이다. 나눠 두면 "이 노선이 스스로 버는가, 허브 연결이
 * 먹여 살리는가"를 읽을 수 있다 — 허브 전략의 손익이 그 구분에 달려 있다.
 */
data class RouteOutcome(
    val routeId: Int,
    val bizPax: Double,
    val leiPax: Double,
    val seats: Double,
    val fare: Double,
    val localRevenue: Double,
    val share: Double,
    /**
     * 그중 **비즈니스 운임을 낸** 승객 (분기 인원).
     *
     * "앞자리에 앉을 수 있었던 사람"이 아니라 실제로 프리미엄 값을 치른 쪽이다 —
     * 출장 수요 중 [Balance.BIZ_CABIN_TAKEUP] 만큼만 그렇고, 객실 좌석 수로 한 번 더
     * 잘린다. 기내식·라운지 원가가 이 인원에 붙는다.
     */
    val bizCabinPax: Double = 0.0,
    /**
     * 깔아 놓은 비즈니스 좌석 — **분기 공급 좌석**(편도 편수 × 좌석)이지 기체의 물리
     * 좌석 수가 아니다. 비어 있어도 유지비가 나간다.
     */
    val bizSeatsOffered: Double = 0.0,
    val connectPax: Double = 0.0,
    val connectRevenue: Double = 0.0,
) {
    val localPax: Double get() = bizPax + leiPax
    val pax: Double get() = localPax + connectPax
    val revenue: Double get() = localRevenue + connectRevenue

    /**
     * 환승 승객이 쓸 수 있는 **이코노미** 빈자리.
     *
     * 총 빈자리를 쓰면 안 된다 — 그러면 앞자리에 앉지 못하게 막아 둔 비프리미엄 수요가
     * 환승으로 우회해 그 자리를 채운다. 객실을 크게 깔아도 빈자리가 환승으로 메워지니
     * **과대 객실의 대가가 사라지고**, 게다가 그 손님은 환승 운임만 내면서 앞자리
     * 승객으로도 안 잡힌다.
     */
    val econSpare: Double get() =
        ((seats - bizSeatsOffered) - (localPax - bizCabinPax)).coerceAtLeast(0.0)
}

private class Offer(
    val route: Route,
    val fare: Double,
    /** 분기 공급 좌석 기준 (물리 좌석 수가 아니다). */
    val bizSeats: Double,
    val econSeats: Double,
    val bizUtil: Double,
    val leiUtil: Double,
    /** 이 회사가 이 노선에서 출장객 중 몇 %에게 앞자리 값을 받아내는가. */
    val takeup: Double,
) {
    val seats: Double get() = bizSeats + econSeats
    var remaining: Double = seats
    /** 이 노선이 실은 출장객 전체 (앞자리·뒷자리 합) */
    var biz: Double = 0.0
    var lei: Double = 0.0

    /**
     * **비즈니스 운임을 낸** 출장객.
     *
     * 출장 수요 전체가 앞자리 값을 내지는 않고([Balance.BIZ_CABIN_TAKEUP]), 그 비율은
     * 회사마다 다르다([takeup]) — 프리미엄 수요보다 객실을 크게 깔면 그만큼 빈 채로 난다.
     */
    val bizInCabin: Double get() = minOf(biz * takeup, bizSeats)

    /** 나머지 출장객 — 이코노미 좌석에 앉아 [Balance.BIZ_YIELD] 만 낸다. */
    val bizInEcon: Double get() = biz - bizInCabin

    /**
     * 출장객이 **실제로 앉을 수 있는** 좌석 수.
     *
     * 총 좌석을 그대로 쓰면 안 된다. 앞자리를 살 사람은 [takeup] 만큼뿐이라, 출장 수요가
     * 기재를 가득 채울 만큼 큰 저(低)매력 노선에서는 **앞자리를 비워 둔 채 뒷자리 정원을
     * 넘겨** 태우게 된다 (`bizInEcon > econSeats`). 태운 인원과 수입이 부풀고, 과대 객실의
     * 대가도 사라진다 — 환승 쪽에서 잡았던 것과 같은 종류의 구멍이다.
     *
     * 출장객이 늘면 뒷자리 사용량은 처음엔 `1 - takeup` 기울기로, 앞자리가 다 찬 뒤로는
     * 기울기 1 로 는다. 뒷자리 정원과 만나는 지점이 정원이다:
     * 앞자리가 먼저 차면 `econSeats + bizSeats`, 뒷자리가 먼저 차면 `econSeats / (1 - takeup)`.
     */
    val bizCapacity: Double get() {
        if (bizSeats <= 0.0 || takeup <= 0.0) return econSeats
        val econFillsFirst = econSeats <= bizSeats * (1.0 - takeup) / takeup
        return if (econFillsFirst) econSeats / (1.0 - takeup).coerceAtLeast(1e-6) else econSeats + bizSeats
    }
}

object Market {
    /**
     * 도시쌍 하나의 승객 배분.
     *
     * 비즈니스 승객이 먼저 좌석을 가져가고(수익 관리), 남은 좌석을 레저 승객이 채운다.
     * 각 세그먼트 안에서는 다항 로짓으로 점유율이 갈리며, 좌석이 모자라 넘친 수요는
     * 여유가 있는 다른 항공사로 몇 차례에 걸쳐 흘러간다.
     */
    fun resolvePair(state: GameState, a: City, b: City, routes: List<Route>): List<RouteOutcome> =
        resolvePair(state, a, b, routes, HashMap())

    private fun resolvePair(
        state: GameState,
        a: City,
        b: City,
        routes: List<Route>,
        unmetOut: HashMap<String, Double>,
    ): List<RouteOutcome> {
        if (routes.isEmpty()) return emptyList()
        // 공항이 닫히면 비행기가 안 뜬다. 빈 결과를 돌려줘야 결산에서 연료·승무원·착륙료가
        // 청구되지 않는다 (수요만 0 으로 만들면 원가는 그대로 나간다).
        if (state.cityState[a.id]?.isClosed(state.turn) == true ||
            state.cityState[b.id]?.isClosed(state.turn) == true
        ) {
            return emptyList()
        }
        val dist = Geo.distance(a.id, b.id)
        val standard = Economics.standardFare(dist, state.world.inflation)

        val offers = ArrayList<Offer>(routes.size)
        for (r in routes) {
            if (!r.active || r.freq <= 0 || r.planeIds.isEmpty()) continue
            val airline = state.airlineOrNull(r.airlineId) ?: continue
            if (!airline.alive) continue
            // 중정비로 묶인 기체는 빠진다 — 그만큼 편수와 좌석이 준다.
            val planes = state.flyingOn(r.id)
            if (planes.isEmpty()) continue
            val cap = Economics.capacity(planes, dist)
            if (!cap.usable) continue
            val freq = r.freq.coerceAtMost(cap.maxFreq)
            if (freq <= 0) continue

            val seats = Economics.quarterlySeats(freq, cap.avgSeats)
            val fare = standard * r.fareMul
            val fareRatio = (fare / standard).coerceAtLeast(0.05)

            val brand = (airline.brandIn(a.region) + airline.brandIn(b.region)) / 2.0
            // 확장으로 늘어난 슬롯까지 분모에 넣는다. 안 그러면 확장된 공항에서
            // 점유율이 1.0 을 넘어 있지도 않은 지배력 보너스가 붙는다.
            val capacityA = state.totalSlots(a.id).coerceAtLeast(1)
            val capacityB = state.totalSlots(b.id).coerceAtLeast(1)
            val hub = (
                airline.slotsAt(a.id).toDouble() / capacityA +
                    airline.slotsAt(b.id).toDouble() / capacityB
                ) / 2.0
            val prestige = planes.sumOf { AircraftCatalog[it.typeId].prestige } / planes.size
            val endpointBusinesses = airline.businesses.filter { it.city == a.id || it.city == b.id }
            val bizFacilities = endpointBusinesses.sumOf { it.type.demandBoost }

            // 양 끝에서 이 회사가 **다른 어디로 더 갈 수 있는가**. 연결편이 많은 회사가
            // 선택받는다 — 갈아탈 곳이 있고 일정이 틀어져도 대안이 있기 때문이다.
            // 이게 없으면 노선 하나만 띄운 회사와 허브를 가진 회사가 똑같이 취급돼,
            // 같은 구간에 붙은 경쟁자들이 다 같이 낮은 탑승률로 사이좋게 적자를 본다.
            val feed = feedStrength(state, airline.id, a.id, r.id) +
                feedStrength(state, airline.id, b.id, r.id)

            // 기반 국가 프리미엄 — 자국 적을 단 항공사가 그 나라를 드나드는 손님에게
            // 갖는 인지도·영업망의 이점. 홈 공항이 끝점이면 온전히, 같은 권역이면 절반.
            val homeCity = Cities[airline.home]
            val homeEdge = when {
                airline.home == a.id || airline.home == b.id -> 1.0
                homeCity.region == a.region || homeCity.region == b.region -> 0.5
                else -> 0.0
            }

            val common = Balance.SHARE_BRAND_W * brand +
                Balance.SHARE_PRESTIGE_W * prestige +
                Balance.SHARE_HUB_W * hub +
                Balance.SHARE_SAFETY_W * (airline.safety - 1.0) +
                Balance.SHARE_FEED_W * feed +
                Balance.SHARE_HOME_W * homeEdge +
                bizFacilities

            val service = (airline.serviceLevel + r.serviceExtra).toDouble()
            val logFreq = ln(1.0 + freq.toDouble())
            val logFare = ln(fareRatio)

            // 앞자리를 깔면 총 좌석이 줄고, 대신 출장객에게 더 매력적이다.
            val cabin = Economics.cabin(seats, airline.bizShare)

            offers += Offer(
                route = r,
                fare = fare,
                bizSeats = cabin.biz,
                econSeats = cabin.econ,
                bizUtil = -Balance.BIZ_PRICE_SENS * logFare +
                    Balance.BIZ_SERVICE_W * service +
                    Balance.BIZ_FREQ_W * logFreq +
                    Balance.BIZ_CABIN_UTIL_W * airline.bizShare + common,
                leiUtil = -Balance.LEI_PRICE_SENS * logFare +
                    Balance.LEI_SERVICE_W * service +
                    Balance.LEI_FREQ_W * logFreq + common,
                takeup = premiumTakeup(
                    brand = brand,
                    service = service,
                    facilityAppeal = endpointBusinesses.sumOf { it.type.premiumAppeal },
                ),
            )
        }
        if (offers.isEmpty()) return emptyList()

        val demand = Demand.quarterly(state, a, b)
        // 시장 평균 운임이 싸면 원래 안 움직였을 사람들까지 움직인다.
        val capacityTotal = offers.sumOf { it.seats }
        val weightedFareRatio = if (capacityTotal <= 0) {
            1.0
        } else {
            offers.sumOf { it.seats * (it.fare / standard) } / capacityTotal
        }
        val induced = weightedFareRatio.coerceIn(0.3, 3.0).pow(-Balance.INDUCED_ELASTICITY)

        val unmet = DoubleArray(1)
        val fringeUtil = fringeUtility(Geo.pairKey(a.id, b.id), dist)
        // 출장객이 먼저 고른다 (수익 관리). 앞자리든 뒷자리든 앉을 수 있지만, 앞자리를
        // 살 사람은 takeup 만큼뿐이라 좌석 풀이 객실 전체는 아니다 ([Offer.bizCapacity]).
        for (o in offers) o.remaining = o.bizCapacity
        allocate(offers, demand.business * induced, unmet, fringeUtil) { it.bizUtil }
            .also { served -> offers.forEachIndexed { i, o -> o.biz = served[i] } }
        // 레저는 **이코노미만** 쓴다. 출장객이 앞자리를 다 못 채웠어도 그 자리는
        // 관광객에게 열리지 않는다 — 그게 객실을 나눈다는 뜻이다.
        for (o in offers) o.remaining = (o.econSeats - o.bizInEcon).coerceAtLeast(0.0)
        allocate(offers, demand.leisure * induced, unmet, fringeUtil) { it.leiUtil }
            .also { served -> offers.forEachIndexed { i, o -> o.lei = served[i] } }
        unmetOut[Geo.pairKey(a.id, b.id)] = unmet[0]

        // 점유율 분모는 **로컬 항공사 몫까지 포함한 시장 전체**다. 모델에 있는 회사끼리만
        // 나누면 혼자 취항한 구간이 언제나 100% 로 뜬다 — 실제로는 로컬에 밀려 조금밖에
        // 못 실었어도 그렇다.
        val marketTotal = (demand.business + demand.leisure) * induced
        return offers.map { o ->
            val revenue = o.bizInCabin * o.fare * Balance.BIZ_CABIN_YIELD +
                o.bizInEcon * o.fare * Balance.BIZ_YIELD +
                o.lei * o.fare * Balance.LEI_YIELD
            RouteOutcome(
                routeId = o.route.id,
                bizPax = o.biz,
                leiPax = o.lei,
                seats = o.seats,
                fare = o.fare,
                localRevenue = revenue,
                share = if (marketTotal <= 0) 0.0 else ((o.biz + o.lei) / marketTotal).coerceIn(0.0, 1.0),
                bizCabinPax = o.bizInCabin,
                bizSeatsOffered = o.bizSeats,
            )
        }
    }

    /**
     * 이 공항에서 그 항공사가 가진 **다른 취항지 수**를 로그로 눌러 돌려준다.
     * 이 노선 자신은 세지 않는다 — 자기 자신은 연결편이 아니다.
     */
    private fun feedStrength(state: GameState, airlineId: String, cityId: String, selfRouteId: Int): Double {
        var n = 0
        for (r in state.routes) {
            if (r.id == selfRouteId || r.airlineId != airlineId || !r.active || r.freq <= 0) continue
            if (!r.touches(cityId)) continue
            if (r.planeIds.isEmpty()) continue
            // 이번 분기에 실제로 못 뜨는 노선은 연결편이 아니다. 반대쪽 공항이 닫혀
            // resolvePair 가 통째로 건너뛴 노선까지 세면, 한 명도 못 나르는 취항지로
            // 네트워크 보너스를 받아 폐쇄 기간에 오히려 점유율이 부풀려진다.
            val other = if (r.from == cityId) r.to else r.from
            if (state.cityState[other]?.isClosed(state.turn) == true) continue
            n++
        }
        return ln(1.0 + n.toDouble())
    }

    /**
     * 그 구간에서 **로컬 항공사가 얼마나 겨룰 만한가**. 거리가 멀수록 약해지고,
     * 도시쌍마다 종 모양으로 흩어진다.
     *
     * 단거리 역내 구간에는 소형기로 붙을 수 있는 지역 사업자가 널려 있지만, 태평양을
     * 건너려면 광동체와 그걸 굴릴 자본이 있어야 해서 **그럴 수 있는 회사는 대개 이미
     * 게임 안에 있다**. 로컬을 거리에 무관하게 똑같이 강하게 두면 도쿄-LA 같은 간판
     * 간선이 독점인데도 반도 못 채운다 — 현실과 어긋난다.
     *
     * 거기에 도시쌍마다 [Balance.FRINGE_SIGMA] 만큼의 편차를 얹는다. 로컬 사업자의
     * 실력이 어디나 똑같을 리 없다 — 억센 국적사가 버티는 구간이 있고, 손 놓은 구간이
     * 있다. 값 하나로 두면 모든 노선이 똑같이 빡빡해 **어디를 뚫을지가 판단거리가 되지
     * 않는다**. 흩어 놓아야 "여기는 해볼 만하다"를 찾는 재미가 생긴다.
     *
     * 편차는 도시쌍 이름에서 결정론적으로 뽑는다. 분기마다 다시 굴리면 탑승률이 이유
     * 없이 출렁여 경영 판단이 잡음에 묻히고, 세이브도 재현되지 않는다. 해시를 직접
     * 짜는 것은 `String.hashCode` 가 플랫폼마다 갈릴 여지를 없애기 위해서다 —
     * 안드로이드와 데스크톱이 같은 세이브에서 같은 시장을 봐야 한다.
     */
    private fun fringeUtility(pairKey: String, distanceKm: Double): Double {
        val far = Balance.FRINGE_DIST_W * ln((distanceKm / Balance.FRINGE_DIST_REF).coerceAtLeast(1.0))
        return Balance.FRINGE_UTIL - far + bellDeviate(pairKey) * Balance.FRINGE_SIGMA
    }

    /**
     * 이 구간 **로컬 항공사가 얼마나 센가**. 취항 전에 시장을 살펴볼 수 있어야 하므로
     * 밖으로 연다 — 노선 계획 화면과 AI 의 노선 후보 평가가 같은 값을 본다.
     *
     * 숨겨 두면 편차가 "돈을 쓰고 나서야 알게 되는 함정"이 된다. 흩어 놓은 이유가
     * 시장을 고르는 재미인데, 고를 정보가 없으면 그냥 운이다.
     */
    fun localStrength(a: City, b: City): Double =
        fringeUtility(Geo.pairKey(a.id, b.id), Geo.distance(a.id, b.id))

    /**
     * 로컬 경쟁 강도를 사람이 읽을 수 있는 3단계로. 기준은 [Balance.FRINGE_UTIL] 에서
     * ±0.5σ — 종분포라 대략 3:4:3 으로 갈린다.
     */
    fun localStrengthLabel(a: City, b: City): String {
        val s = localStrength(a, b)
        val mid = Balance.FRINGE_UTIL -
            Balance.FRINGE_DIST_W * ln((Geo.distance(a.id, b.id) / Balance.FRINGE_DIST_REF).coerceAtLeast(1.0))
        val half = 0.5 * Balance.FRINGE_SIGMA
        return when {
            s > mid + half -> "강함"
            s < mid - half -> "약함"
            else -> "보통"
        }
    }

    /**
     * 출장 수요 중 **이 회사에게 앞자리 값을 낼 사람**의 비율.
     *
     * 앞자리는 좌석이 아니라 대접을 사는 자리다. 같은 구간이라도 라운지가 있고 서비스가
     * 좋고 이름이 알려진 회사에게 먼저 몰린다 — 그래서 프리미엄 비율을 회사·노선마다
     * 다르게 잡는다. 예전에는 [Balance.BIZ_CABIN_TAKEUP] 하나로 모두 같았고, 그러면
     * 객실 크기를 정하는 계산이 **누구에게나 똑같아** 회사 성격이 드러나지 않았다.
     *
     * 세 축 모두 기준점 대비 증감이라, 평범한 회사(서비스 3 · 브랜드
     * [Balance.PREMIUM_BRAND_REF] · 시설 없음)는 정확히 기준 비율을 받는다. 여기에
     * 상·하한을 두는 이유는 [Balance.BIZ_CABIN_TAKEUP_MIN] 에 적어 뒀다.
     *
     * 이 값은 **누구를 태우는가**가 아니라 **얼마를 받는가**만 바꾼다. 손님을 끌어오는
     * 쪽은 로짓 효용이 따로 맡는다 (서비스와 부대시설은 거기에도 이미 들어간다) —
     * 두 곳에서 같은 축을 세게 걸면 좋은 회사가 손님도 더 받고 단가도 더 받아
     * 격차가 제곱으로 벌어진다.
     */
    /**
     * 노선 하나의 프리미엄 비율. **노선에 따로 태운 서비스**([Route.serviceExtra])까지 센다 —
     * 시장 계산이 보는 것과 같은 값이어야 하므로 도시쌍이 아니라 노선을 받는다.
     * 회사 등급만 보면 서비스를 얹어 둔 노선을 몇 %p 씩 낮게 잡아, 화면과 AI 가
     * 실제보다 작은 객실을 답으로 낸다.
     */
    fun premiumTakeup(airline: Airline, route: Route): Double {
        val a = Cities[route.from]
        val b = Cities[route.to]
        return premiumTakeup(
            brand = (airline.brandIn(a.region) + airline.brandIn(b.region)) / 2.0,
            service = (airline.serviceLevel + route.serviceExtra).toDouble(),
            facilityAppeal = airline.businesses
                .filter { it.city == route.from || it.city == route.to }
                .sumOf { it.type.premiumAppeal },
        )
    }

    /**
     * 노선망 전체의 프리미엄 비율 — **출장 수요로 가중한** 평균.
     *
     * 객실 비중은 회사 단위 설정이므로, 앞자리를 살 손님이 실제로 어디에 있는지에
     * 맞춰야 한다. 노선 수로 평균 내면 간선 하나에 출장객이 몰려 있어도 작은 지선들이
     * 같은 표를 행사해서, 지선에 라운지를 몇 개 낸 회사가 정작 손님 대부분이 타는
     * 저(低)매력 간선을 무시하고 객실을 키운다 — 그 앞자리는 빈 채로 난다.
     *
     * 가중치는 공급 좌석이 아니라 **수요**다. 공급은 이 함수가 정하려는 값(객실 비중)에
     * 다시 의존하고 분기마다 편수 따라 출렁인다. 같은 함수 안에서 객실 비중의 다른
     * 축(노선망 출장 비중)도 같은 잣대를 쓴다.
     *
     * 노선이 없으면 홈 공항 기준으로 돌려준다 — 빈 평균은 NaN 이다.
     */
    fun networkPremiumTakeup(airline: Airline, routes: List<Route>): Double {
        var weighted = 0.0
        var total = 0.0
        for (r in routes) {
            val w = Demand.annualBase(Cities[r.from], Cities[r.to]).business
            if (w <= 0.0) continue
            weighted += premiumTakeup(airline, r) * w
            total += w
        }
        if (total <= 0.0) {
            return premiumTakeup(airline, Route(-1, airline.id, airline.home, airline.home))
        }
        return weighted / total
    }

    fun premiumTakeup(brand: Double, service: Double, facilityAppeal: Double): Double {
        val appeal = Balance.PREMIUM_SERVICE_W * (service - Balance.PREMIUM_SERVICE_REF) +
            Balance.PREMIUM_BRAND_W * (brand - Balance.PREMIUM_BRAND_REF) +
            Balance.PREMIUM_FACILITY_W * facilityAppeal
        return (Balance.BIZ_CABIN_TAKEUP * (1.0 + appeal))
            .coerceIn(Balance.BIZ_CABIN_TAKEUP_MIN, Balance.BIZ_CABIN_TAKEUP_MAX)
    }

    /**
     * 문자열 하나에서 **표준정규에 가까운 값**을 결정론적으로 뽑는다 (평균 0, 표준편차 1).
     *
     * 균등난수 넷을 더하는 어윈–홀 방식이다. 정규분포에 충분히 가까우면서 ±3.46σ 로
     * 잘려 있어, 로컬이 터무니없이 세거나 아예 없는 노선이 나오지 않는다.
     */
    private fun bellDeviate(key: String): Double {
        // FNV-1a — 플랫폼에 의존하지 않는 고정 해시.
        var h = -0x340d631b7bdddcdbL
        for (c in key) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        var sum = 0.0
        repeat(4) {
            h = mix64(h)
            sum += ((h ushr 11).toDouble() / (1L shl 53).toDouble())
        }
        // 균등 4개의 합은 평균 2, 표준편차 sqrt(4/12) = 0.5774.
        return (sum - 2.0) / 0.5773502691896257
    }

    /** splitmix64 의 마무리 섞기 — 이웃한 도시쌍 이름이 비슷한 값으로 몰리지 않게 한다. */
    private fun mix64(seed: Long): Long {
        var z = seed + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /**
     * 로짓으로 수요를 나눈다. 후보에는 게임에 등장하는 항공사들뿐 아니라 **로컬 항공사**
     * ([fringeUtility]) 라는 바깥 선택지가 늘 함께 있다.
     *
     * 게임에 나오는 아홉 회사는 그 시대의 **주요 항공사**다. 나머지 수요가 비어 있는 게
     * 아니라, 모델에 없는 지역 항공사들이 낮은 경쟁력으로 실어 나르고 있다고 본다.
     * 이 가정이 있어야 수요를 실물 규모로 둬도 "내놓은 좌석은 무조건 팔린다"가 되지 않는다 —
     * 로컬보다 매력이 있어야 팔리고, 그래서 **탑승률이 곧 경쟁력의 함수**가 된다.
     * 운임을 올리면 손님이 로컬로 새고, 편수·서비스·환승편·브랜드가 좋으면 되찾아 온다.
     *
     * 좌석이 모자라 못 태운 몫만 다음 라운드로 넘긴다. 로컬을 택한 손님까지 다시 돌리면
     * 라운드를 거듭할수록 바깥 선택지가 무력해져(50% 가 93.75% 가 된다) 이 모델이 무너진다.
     */
    private fun allocate(
        offers: List<Offer>,
        demand: Double,
        leftover: DoubleArray,
        fringeUtil: Double,
        utility: (Offer) -> Double,
    ): DoubleArray {
        val taken = DoubleArray(offers.size)
        if (demand <= 0.0) return taken
        var left = demand

        repeat(Balance.SPILL_ROUNDS + 1) {
            if (left <= 1e-6) return@repeat
            val active = offers.indices.filter { offers[it].remaining > 1e-6 }
            if (active.isEmpty()) return@repeat

            val utils = active.map { utility(offers[it]) }
            val maxU = maxOf(utils.max(), fringeUtil)
            val weights = utils.map { exp(it - maxU) }
            val fringe = exp(fringeUtil - maxU)
            val denom = weights.sum() + fringe
            if (denom <= 0.0) return@repeat

            var spilled = 0.0
            for ((k, idx) in active.withIndex()) {
                val want = left * (weights[k] / denom)
                val o = offers[idx]
                val give = minOf(want, o.remaining)
                o.remaining -= give
                taken[idx] += give
                // 좌석이 없어 흘린 몫만 다시 돌린다. 로컬을 택한 손님은 이미 떠났다.
                spilled += want - give
            }
            if (spilled <= 1e-9) {
                left = 0.0
                return@repeat
            }
            left = spilled
        }
        // 끝까지 좌석을 못 구한 몫. 로컬을 택한 손님은 여기 들어 있지 않다 —
        // 그들은 이미 다른 항공사를 골랐지 "아직 안 정한" 사람이 아니다.
        leftover[0] += left
        return taken
    }

    /**
     * 도시쌍 단위로 모든 항공사의 노선을 모아 한 번에 푼다.
     *
     * 두 단계다. 먼저 **직항 시장**을 도시쌍마다 풀고(1단계), 그다음 남은 좌석을
     * **허브 경유 환승 승객**이 채운다(2단계). 실제 허브 운영이 그 순서로 돌아간다 —
     * 로컬 수요로 채우지 못한 좌석을 연결 수요로 메워 기재를 띄울 만하게 만드는 것.
     *
     * 이 2단계가 노선의 가치를 **네트워크에 의존하게** 만든다. 스포크 하나는 로컬 수요만
     * 보면 적자여도, 허브에서 뻗은 다른 노선에 승객을 물어다 주면 살아난다. 그래서
     * "어디에 허브를 세우고 무엇을 붙일 것인가"가 비로소 판단거리가 된다.
     */
    fun resolveAll(state: GameState): Map<Int, RouteOutcome> {
        val byPair = HashMap<String, MutableList<Route>>()
        for (r in state.routes) {
            if (!r.active || r.freq <= 0 || r.planeIds.isEmpty()) continue
            byPair.getOrPut(Geo.pairKey(r.from, r.to)) { mutableListOf() }.add(r)
        }
        val out = HashMap<Int, RouteOutcome>()
        // 1단계가 남긴 **진짜 미충족 수요**. 유발 수요가 반영돼 있고, 로컬을 택한 손님은
        // 빠져 있다 — 다시 계산하면 두 단계의 시장 크기가 어긋나고 로컬 손님을 환승에
        // 두 번 파는 셈이 된다.
        val unmetByPair = HashMap<String, Double>()
        for ((key, routes) in byPair) {
            val (idA, idB) = key.split("|")
            for (outcome in resolvePair(state, Cities[idA], Cities[idB], routes, unmetByPair)) {
                out[outcome.routeId] = outcome
            }
        }
        return resolveConnections(state, out, unmetByPair)
    }

    /** 환승 후보 하나 — 한 항공사가 허브 하나를 거쳐 A→C 를 잇는 여정. */
    private class ConnectOffer(
        val legA: RouteOutcome,
        val legB: RouteOutcome,
        val hubId: String,
        val fare: Double,
        val distA: Double,
        val distB: Double,
        val util: Double,
        val spare: Double,
    ) {
        var pax: Double = 0.0
    }

    /**
     * 2단계 — 직항이 채우지 못한 수요를 허브 경유 여정이 가져간다.
     *
     * 좌석은 **양쪽 구간에서 동시에** 소비되므로 병목은 둘 중 여유가 적은 쪽이다.
     * 우회가 심하면 승객이 타지 않으므로 [Balance.CONNECT_MAX_DETOUR] 로 잘라낸다.
     */
    private fun resolveConnections(
        state: GameState,
        base: Map<Int, RouteOutcome>,
        unmetByPair: Map<String, Double>,
    ): Map<Int, RouteOutcome> {
        // 노선별 남은 좌석. 환승 승객이 여기서만 태워진다.
        val spare = HashMap<Int, Double>()
        // 항공사 → 도시 → 그 도시에서 뻗은 (상대 도시, 노선) 목록.
        val links = HashMap<String, HashMap<String, MutableList<Pair<String, Route>>>>()
        for (r in state.routes) {
            val o = base[r.id] ?: continue
            val left = o.econSpare
            if (left <= 1.0) continue
            spare[r.id] = left
            val perAirline = links.getOrPut(r.airlineId) { HashMap() }
            perAirline.getOrPut(r.from) { mutableListOf() } += r.to to r
            perAirline.getOrPut(r.to) { mutableListOf() } += r.from to r
        }
        if (spare.isEmpty()) return base

        // 도시쌍별 후보 모으기. 허브에서 뻗은 노선 쌍만 보면 되므로 전 도시쌍을 훑지 않는다.
        val candidates = HashMap<String, MutableList<ConnectOffer>>()
        for ((airlineId, byCity) in links) {
            val airline = state.airlineOrNull(airlineId) ?: continue
            if (!airline.alive) continue
            for ((hubId, spokes) in byCity) {
                if (spokes.size < 2) continue
                for (i in spokes.indices) {
                    for (j in i + 1 until spokes.size) {
                        val (aId, ra) = spokes[i]
                        val (cId, rc) = spokes[j]
                        if (aId == cId) continue
                        // 같은 항공사가 A–C 직항을 갖고 있어도 후보로 둔다. 직항은 1단계에서
                        // 이미 로컬 수요를 가져갔고, 여기서 채우는 것은 그러고도 남은 좌석이다.
                        val pairKey = Geo.pairKey(aId, cId)
                        val oa = base[ra.id] ?: continue
                        val ob = base[rc.id] ?: continue
                        val sa = spare[ra.id] ?: continue
                        val sb = spare[rc.id] ?: continue

                        val direct = Geo.distance(aId, cId)
                        if (direct < 1.0) continue
                        val da = Geo.distance(aId, hubId)
                        val db = Geo.distance(hubId, cId)
                        if (da + db > direct * Balance.CONNECT_MAX_DETOUR) continue

                        val fare = Economics.standardFare(direct, state.world.inflation) *
                            ((ra.fareMul + rc.fareMul) / 2.0) * Balance.CONNECT_FARE_MUL
                        val util = connectUtility(state, airline, ra, rc, aId, cId, hubId, fare, direct)
                        candidates.getOrPut(pairKey) { mutableListOf() } += ConnectOffer(
                            legA = oa,
                            legB = ob,
                            hubId = hubId,
                            fare = fare,
                            distA = da,
                            distB = db,
                            util = util,
                            spare = minOf(sa, sb),
                        )
                    }
                }
            }
        }
        if (candidates.isEmpty()) return base

        // 노선별로 환승 승객·수입을 누적한다.
        val addPax = HashMap<Int, Double>()
        val addRev = HashMap<Int, Double>()

        // 구간별 남은 좌석을 **소비해 가며** 배분한다. 여정 하나는 두 구간의 좌석을
        // 동시에 먹으므로, 나중에 노선별로 따로 자르면 한쪽만 깎여 "한 승객이 두 구간을
        // 쓴다"는 전제가 깨진다 (A 구간은 80% 로 줄고 B 구간은 그대로 남는 식).
        val spareLeft = HashMap(spare)

        // 도시쌍마다 로짓 가중치를 미리 굳혀 둔다. **"환승하지 않는다"는 선택지**를 함께
        // 넣는다 — 후보끼리만 정규화하면 CONNECT_PENALTY 가 모든 항에서 똑같이 빠져
        // 상쇄되고, 후보가 하나뿐이면 아무리 나쁜 여정이어도 남은 수요를 통째로 가져간다.
        // 키로 정렬해 결정론을 지킨다 (같은 시드가 같은 전개를 재현해야 한다).
        val keys = candidates.keys.sorted()
        val weightsByPair = HashMap<String, List<Double>>()
        val outsideByPair = HashMap<String, Double>()
        val leftByPair = HashMap<String, Double>()
        for (pairKey in keys) {
            val offers = candidates.getValue(pairKey)
            // 직항 시장이 남긴 몫만 줍는다. 아무도 취항하지 않은 도시쌍은 1단계를 거치지
            // 않았으므로 수요 전체가 미충족이다.
            val (aId, cId) = pairKey.split("|")
            val direct = unmetByPair[pairKey]
            val unmet = direct ?: Demand.quarterly(state, Cities[aId], Cities[cId]).total
            if (unmet <= 1.0) continue

            // 1단계를 아예 안 거친 도시쌍(아무 메이저도 직항하지 않는 곳)에서는 **로컬
            // 항공사도 함께 겨뤄야 한다**. 스포크끼리의 구간이 대개 여기 해당하는데,
            // 로컬을 빼면 경유편이 "안 감"만 상대로 이겨서 수요를 통째로 가져간다 —
            // 직항 시장에는 로컬을 깔아 두고 환승 시장만 무주공산으로 두는 셈이라
            // 허브 수송량과 수입이 크게 부풀려진다.
            val fringeU = if (direct == null) {
                fringeUtility(pairKey, Geo.distance(aId, cId))
            } else {
                null // 1단계에서 이미 로컬이 제 몫을 가져갔다. 또 세면 이중 계상이다.
            }
            val maxU = maxOf(
                offers.maxOf { it.util },
                Balance.CONNECT_OUTSIDE_UTIL,
                fringeU ?: Balance.CONNECT_OUTSIDE_UTIL,
            )
            val weights = offers.map { exp(it.util - maxU) }
            val outside = exp(Balance.CONNECT_OUTSIDE_UTIL - maxU) +
                (fringeU?.let { exp(it - maxU) } ?: 0.0)
            if (weights.sum() + outside <= 0.0) continue
            weightsByPair[pairKey] = weights
            outsideByPair[pairKey] = outside
            leftByPair[pairKey] = unmet
        }

        // 도시쌍을 하나씩 끝까지 태우고 넘어가면 **먼저 처리된 도시쌍이 공용 구간의 여유
        // 좌석을 통째로 먹는다** — 도쿄 허브에서 도쿄–LA 의 빈자리를 베이징발 승객이 다
        // 가져가고 서울·홍콩발은 한 명도 못 타는 식으로, 정렬 순서가 곧 허브 경제가 된다.
        // 그래서 라운드마다 **모든 도시쌍의 희망 수요를 먼저 모으고**, 구간별로 초과분을
        // 비례 배분해 깎는다. 한 여정은 두 구간을 쓰므로 더 빡빡한 쪽 비율을 따른다.
        repeat(Balance.SPILL_ROUNDS + 1) {
            // 1) 이번 라운드에 각 여정이 원하는 양.
            val want = HashMap<ConnectOffer, Double>()
            // 이번 라운드에 실제로 쓴 정규화 분모. 3) 에서 "환승 안 함" 몫을 뺄 때
            // **같은 분모**를 써야 한다 — 좌석이 찬 후보를 빼고 정규화해 놓고 전체
            // 분모로 바깥 몫을 빼면 덜 빠져서, 안 타기로 한 손님이 다음 라운드에
            // 다시 제안된다.
            val roundDenom = HashMap<String, Double>()
            for (pairKey in keys) {
                val left = leftByPair[pairKey] ?: continue
                if (left <= 1.0) continue
                val offers = candidates.getValue(pairKey)
                val weights = weightsByPair.getValue(pairKey)
                val open = offers.indices.filter { i ->
                    minOf(
                        spareLeft[offers[i].legA.routeId] ?: 0.0,
                        spareLeft[offers[i].legB.routeId] ?: 0.0,
                    ) > 1e-6
                }
                if (open.isEmpty()) continue
                // 남은 후보만으로 다시 정규화한다. 바깥 선택지(환승 안 함)는 계속 겨룬다.
                val openDenom = open.sumOf { weights[it] } + outsideByPair.getValue(pairKey)
                if (openDenom <= 0.0) continue
                roundDenom[pairKey] = openDenom
                for (i in open) want[offers[i]] = left * (weights[i] / openDenom)
            }
            if (want.isEmpty()) return@repeat

            // 2) 구간별 희망 합계 → 여유를 넘으면 그 비율만큼 모두 깎는다.
            // 합산 순서가 부동소수점 결과를 바꾸므로 정렬된 키 순서로만 훑는다
            // (want 는 객체 동일성 해시라 순회 순서를 믿을 수 없다).
            val legWant = HashMap<Int, Double>()
            for (pairKey in keys) {
                for (o in candidates.getValue(pairKey)) {
                    val w = want[o] ?: continue
                    legWant[o.legA.routeId] = (legWant[o.legA.routeId] ?: 0.0) + w
                    legWant[o.legB.routeId] = (legWant[o.legB.routeId] ?: 0.0) + w
                }
            }
            val legScale = HashMap<Int, Double>()
            for ((routeId, w) in legWant) {
                val room = spareLeft[routeId] ?: 0.0
                legScale[routeId] = if (w <= room) 1.0 else (room / w)
            }

            // 3) 실제로 태우고, 도시쌍별로 못 태운 몫을 다음 라운드로 넘긴다.
            var anyServed = false
            for (pairKey in keys) {
                val left = leftByPair[pairKey] ?: continue
                // 이번 라운드에 열린 후보가 하나도 없었다면 좌석이 어디에도 없다는 뜻이다.
                // spareLeft 는 줄기만 하므로 다음 라운드에도 마찬가지 — 여기서 끝낸다.
                val openDenom = roundDenom[pairKey]
                if (openDenom == null) {
                    leftByPair[pairKey] = 0.0
                    continue
                }
                val offers = candidates.getValue(pairKey)
                var served = 0.0
                for (o in offers) {
                    val w = want[o] ?: continue
                    val scale = minOf(
                        legScale[o.legA.routeId] ?: 1.0,
                        legScale[o.legB.routeId] ?: 1.0,
                    )
                    val take = w * scale
                    if (take <= 1e-9) continue
                    spareLeft[o.legA.routeId] = (spareLeft[o.legA.routeId] ?: 0.0) - take
                    spareLeft[o.legB.routeId] = (spareLeft[o.legB.routeId] ?: 0.0) - take
                    o.pax += take
                    served += take
                    accumulate(o, take, addPax, addRev)
                }
                // 좌석이 없어 흘린 몫만 다시 돌린다. "환승하지 않겠다"를 고른 손님까지
                // 재제안하면 라운드를 거듭할수록 바깥 선택지가 무력해진다 (50% 가 93.75% 로).
                val spilled = left - served - left * (outsideByPair.getValue(pairKey) / openDenom)
                if (served > 1e-9) anyServed = true
                leftByPair[pairKey] = if (spilled <= 1e-9) 0.0 else spilled
            }
            if (!anyServed) return@repeat
        }
        if (addPax.isEmpty()) return base

        // 배분 단계에서 이미 좌석을 소비했으므로 사후 보정이 없다.
        return base.mapValues { (id, o) ->
            val p = addPax[id] ?: return@mapValues o
            o.copy(connectPax = p, connectRevenue = addRev[id] ?: 0.0)
        }
    }

    /** 환승 한 건이 실린 결과를 두 구간에 나눠 적는다. */
    private fun accumulate(
        o: ConnectOffer,
        take: Double,
        addPax: HashMap<Int, Double>,
        addRev: HashMap<Int, Double>,
    ) {
        // 수입은 구간 거리로 나눈다 — 긴 구간이 더 가져간다.
        val total = (o.distA + o.distB).coerceAtLeast(1.0)
        val revenue = take * o.fare * Balance.CONNECT_YIELD
        addPax[o.legA.routeId] = (addPax[o.legA.routeId] ?: 0.0) + take
        addPax[o.legB.routeId] = (addPax[o.legB.routeId] ?: 0.0) + take
        addRev[o.legA.routeId] = (addRev[o.legA.routeId] ?: 0.0) + revenue * (o.distA / total)
        addRev[o.legB.routeId] = (addRev[o.legB.routeId] ?: 0.0) + revenue * (o.distB / total)
    }

    /** 경유 여정의 효용 — 직항보다 확실히 불리해야 허브가 만능이 되지 않는다. */
    private fun connectUtility(
        state: GameState,
        airline: skytycoon.core.model.Airline,
        ra: Route,
        rc: Route,
        aId: String,
        cId: String,
        hubId: String,
        fare: Double,
        direct: Double,
    ): Double {
        val standard = Economics.standardFare(direct, state.world.inflation)
        val logFare = ln((fare / standard).coerceAtLeast(0.05))
        val service = (airline.serviceLevel * 2 + ra.serviceExtra + rc.serviceExtra) / 2.0
        // 편수는 병목 구간이 정한다 — 한쪽이 주 2회면 그 여정은 주 2회짜리다.
        val freq = minOf(ra.freq, rc.freq).toDouble()
        val brand = (airline.brandIn(Cities[aId].region) + airline.brandIn(Cities[cId].region)) / 2.0
        val hubCity = Cities[hubId]
        val hubCap = state.totalSlots(hubId).coerceAtLeast(1)
        val hubGrip = airline.slotsAt(hubId).toDouble() / hubCap
        return -Balance.LEI_PRICE_SENS * 0.5 * logFare -
            Balance.BIZ_PRICE_SENS * 0.5 * logFare +
            Balance.BIZ_SERVICE_W * service +
            Balance.BIZ_FREQ_W * ln(1.0 + freq) +
            Balance.SHARE_BRAND_W * brand +
            Balance.SHARE_HUB_W * hubGrip +
            Balance.SHARE_SAFETY_W * (airline.safety - 1.0) -
            Balance.CONNECT_PENALTY
    }
}
