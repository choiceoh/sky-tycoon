package skytycoon.core.model

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// 정적 마스터 데이터 (세이브에 들어가지 않고 id 로만 참조된다)
// ---------------------------------------------------------------------------

enum class Region(val label: String, val colorArgb: Long) {
    AS("아시아", 0xFFE8734A),
    ME("중동", 0xFFD4A13A),
    EU("유럽", 0xFF5B8FF9),
    NA("북미", 0xFF6DC8A0),
    SA("남미", 0xFFB681E0),
    AF("아프리카", 0xFFD4694A),
    OC("오세아니아", 0xFF4EC4D3),
}

/**
 * @param standing **정치·경제 비중** — 비즈니스 수요를 만든다.
 *
 * 순수한 경제 규모가 아니다. 출장 수요는 GDP 만 따라가지 않는다 — 수도에는 정부·
 * 외교·국영기업·군수 통행이 붙고, 그 나라가 클수록 그 몫이 커진다. 경제 규모만
 * 재면 제2세계의 수도인 모스크바가 상파울루와 같은 칸에 놓이고(실제로 그랬다),
 * 베이징이 상하이보다 낮게 잡힌다.
 *
 * 그래서 이 값은 **경제 규모 + 정치적 무게**로 읽는다. 뉴욕(100)이 눈금의 위쪽
 * 끝이고, 수도가 아닌 경제 중심지(프랑크푸르트·상파울루·상하이)는 경제만으로,
 * 수도는 둘을 합쳐 매긴다.
 *
 * @param tour 관광 매력 지수 — 레저 수요를 만든다
 * @param slots 주간 총 슬롯. 모든 항공사가 나눠 가지므로 허브일수록 쟁탈전이 벌어진다
 * @param fee 착륙료 지수 (1.0 = 표준)
 * @param growth 연간 도시 성장 배율
 */
data class City(
    val id: String,
    val name: String,
    val code: String,
    val lat: Double,
    val lon: Double,
    val region: Region,
    val standing: Double,
    val tour: Double,
    val slots: Int,
    val fee: Double,
    val growth: Double,
)

/**
 * @param range 페이로드 만재 기준 항속거리 (km)
 * @param fuel 연료 소모 (L/km)
 * @param maint 정비비 (USD/비행시간)
 * @param crew 승무원비 (USD/비행시간)
 * @param turn 편도당 지상 조업시간 (시간)
 * @param prestige 브랜드 가산 — 상징적인 기재일수록 승객이 알아본다
 */
data class AircraftType(
    val id: String,
    val name: String,
    val maker: String,
    val year: Int,
    val retire: Int,
    val seats: Int,
    val range: Int,
    val speed: Double,
    val price: Double,
    val fuel: Double,
    val maint: Double,
    val crew: Double,
    val turn: Double,
    val prestige: Double,
    /**
     * 이 **기종이** 어느 진영 것인가. `"east"` 는 구소련권, 빈 값은 서방 기종이다.
     *
     * 이 값만으로 도입 가능 여부가 정해지지는 않는다 — 누가 살 수 있는지는
     * [skytycoon.core.data.AircraftCatalog.operableBy] 가 항공사의 홈 공항과 연도까지
     * 보고 정한다. 냉전기에는 제약이 양방향이라, 빈 값(서방 기종)이라고 아무나 사는
     * 것도 아니다 — 모스크바 기반 항공사는 1991년까지 서방 기종을 못 산다.
     */
    val bloc: String = "",
)

enum class Trait(val label: String) {
    EXPAND("공격적 확장"),
    VALUE("저가 물량"),
    PREMIUM("고수익 프리미엄"),
    BALANCED("균형"),
}

/**
 * @param premiumAppeal 그 도시를 드나드는 손님 중 **앞자리 값을 낼 사람**을 얼마나 늘리는가.
 *   [demandBoost] 가 "손님이 더 온다"라면 이쪽은 "같은 손님이 더 비싼 자리를 산다"다.
 *   라운지가 기준점(1.0)이다 — 프리미엄 객실을 파는 시설 그 자체다. 여행사는 값을
 *   깎아 파는 창구라 0 이고, 정비창은 손님이 볼 일이 없다.
 */
enum class BusinessType(
    val label: String,
    val cost: Double,
    val income: Double,
    val demandBoost: Double,
    val brandBoost: Double,
    val maintDiscount: Double,
    val premiumAppeal: Double = 0.0,
) {
    HOTEL("호텔", 48e6, 2.9e6, 0.05, 3.0, 0.0, premiumAppeal = 0.6),
    DUTY_FREE("면세점", 30e6, 2.2e6, 0.02, 2.0, 0.0, premiumAppeal = 0.2),
    AGENCY("여행사", 22e6, 1.2e6, 0.06, 1.0, 0.0),
    HANGAR("정비창", 65e6, 0.0, 0.0, 0.0, 0.12),
    LOUNGE("라운지", 18e6, 0.5e6, 0.03, 4.0, 0.0, premiumAppeal = 1.0),
}

data class Scenario(
    val id: String,
    val name: String,
    val startYear: Int,
    val years: Int,
    val desc: String,
)

data class Difficulty(
    val id: String,
    val name: String,
    val aiSkill: Double,
    val demandBonus: Double,
    val costMul: Double,
)

// ---------------------------------------------------------------------------
// 세이브에 들어가는 가변 상태
// ---------------------------------------------------------------------------

@Serializable
data class World(
    /** 항공유 가격 (USD/L) */
    val oil: Double,
    /** 세계 경기 지수 (1.0 = 평상) */
    val economy: Double,
    val regionEconomy: Map<Region, Double>,
    /** 기준 금리 (연리) */
    val interest: Double,
    /** 항공여행 보급 지수 — 해마다 오른다 */
    val travelIndex: Double,
    val inflation: Double,
)

/** 도시에 한시적으로 걸린 수요 배율 하나 (올림픽·전염병·관광 붐 등). */
@Serializable
data class CityEffect(val mult: Double, val untilTurn: Int)

@Serializable
data class CityState(
    /** 도시 성장 누적치 */
    val dev: Double = 1.0,
    /**
     * 겹쳐 걸린 일시 효과들. 배율 하나로 합쳐 두면 만료가 서로 다를 때 짧은 쪽이
     * 긴 쪽의 만료까지 살아남는다 — 사스 폭락이 관광 붐 끝까지 따라오는 식.
     * 따로 들고 있어야 각자 제 때 꺼진다.
     */
    val effects: List<CityEffect> = emptyList(),
    /** 공항 폐쇄 (화산·전쟁) */
    val closedUntilTurn: Int = -1,
    /** 신공항·확장으로 늘어난 슬롯 */
    val extraSlots: Int = 0,
    /** 지금까지 완공된 확장 공사 횟수 — 늘어날수록 다음 확장이 비싸진다 */
    val expansions: Int = 0,
) {
    fun isClosed(turn: Int) = turn <= closedUntilTurn

    fun boostAt(turn: Int): Double = effects
        .filter { turn <= it.untilTurn }
        .fold(1.0) { acc, e -> acc * e.mult }
        .coerceIn(0.2, 3.0)

    /** 만료된 효과를 털어낸다 — 안 그러면 20년치가 그대로 쌓인다. */
    fun pruned(turn: Int): CityState =
        if (effects.none { turn > it.untilTurn }) this else copy(effects = effects.filter { turn <= it.untilTurn })
}

@Serializable
data class Business(val type: BusinessType, val city: String)

@Serializable
data class Plane(
    val id: Int,
    val typeId: String,
    val airlineId: String,
    val ageQuarters: Int,
    val routeId: Int? = null,
    /**
     * 장부가 배율 — 자산가치를 "잔존가치 × 이 값"으로 잡는다.
     *
     * 중고기는 잔존가치보다 싸게(USED_PRICE_MUL) 사는데 장부는 잔존가치 그대로 잡으면,
     * 사는 순간 차액만큼 자기자본이 공짜로 생긴다. 매물이 무한하니 그걸로 차입 한도와
     * 최종 순위를 부풀릴 수 있다. 산 값 기준을 기체에 새겨 두면 그 구멍이 막힌다.
     */
    val valueMul: Double = 1.0,
    /**
     * 이 기체를 산 **가격 체계**의 배율.
     *
     * [valueMul] 과 다른 개념이다. 저쪽은 "정가 대비 얼마에 샀나"(중고 할인)라
     * 장부·상각에만 걸리지만, 이쪽은 "그때 카탈로그 값이 지금과 얼마나 달랐나"라
     * 매각가·선급금까지 **값이 걸리는 모든 곳**에 함께 걸린다. 카탈로그 가격을
     * 재조정해도 이미 산 기체가 소급해서 비싸지지 않게 하는 장치다
     * ([skytycoon.core.save.Save] 의 마이그레이션 참고).
     */
    val priceMul: Double = 1.0,
    /**
     * 마지막 중정비 이후 쌓인 비행시간. [skytycoon.core.sim.Balance.CHECK_INTERVAL_HOURS]
     * 를 넘기면 다음 분기에 통째로 뜯어 보느라 뜨지 못한다.
     */
    val hoursSinceCheck: Double = 0.0,
    /**
     * 마지막 중정비 이후 지난 분기 수. 덜 굴린 기체도 달력으로는 늙는다 —
     * 이게 없으면 세워 둔 예비기가 영원히 새것이라 정비가 공짜 보험이 된다.
     */
    val quartersSinceCheck: Int = 0,
    /** 이 분기까지 중정비로 묶여 있다 (그 분기에는 배속돼 있어도 뜨지 않는다). */
    val checkUntilTurn: Int = -1,
    /**
     * 운용리스로 빌린 기체라면 반납 분기. `null` 이면 소유기다.
     *
     * 리스기는 **내 자산이 아니다** — 기단 가치·상각·매각 어디에도 잡히지 않고,
     * 대신 [leaseRate] 가 매 분기 고정비로 나간다. 자본을 안 쓰는 대신 경기가
     * 꺾여도 줄지 않는 비용을 지는 쪽이다.
     */
    val leaseUntilTurn: Int? = null,
    /** 분기 리스료 (계약 시점의 명목 달러 — 물가가 올라도 그대로다). */
    val leaseRate: Double = 0.0,
) {
    /** 이번 분기에 중정비로 묶여 있는가. */
    fun inCheck(turn: Int) = turn <= checkUntilTurn

    /** 빌린 기체인가. */
    val leased: Boolean get() = leaseUntilTurn != null
}

/**
 * 진행 중인 공항 확장 공사. 후원사가 공사비를 대고, 완공되면 새 슬롯의 일부를
 * 우선 배정받는다 (나머지는 시장에 풀린다).
 *
 * 허브가 포화되면 돈을 아무리 벌어도 쓸 데가 없어 게임이 멈춘다. 확장은 그 교착을
 * "큰돈과 긴 리드타임을 걸고 미래의 요지를 선점하는" 판단으로 바꾼다.
 */
@Serializable
data class Expansion(
    val id: Int,
    val city: String,
    val sponsorId: String,
    val slots: Int,
    val sponsorSlots: Int,
    val deliverTurn: Int,
)

@Serializable
data class Order(
    val id: Int,
    val airlineId: String,
    val typeId: String,
    val count: Int,
    val deliverTurn: Int,
    /**
     * 발주 시점의 **가격 체계** 배율 ([Plane.priceMul] 참고). 이미 값을 치른 발주가
     * 카탈로그 재조정 뒤에 선급금·환불·인도 장부가에서 새 가격으로 잡히지 않게 한다.
     */
    val priceMul: Double = 1.0,
)

@Serializable
data class RouteResult(
    val pax: Double = 0.0,
    val seats: Double = 0.0,
    val revenue: Double = 0.0,
    val cost: Double = 0.0,
    val share: Double = 0.0,
) {
    val profit get() = revenue - cost
    val loadFactor get() = if (seats <= 0) 0.0 else (pax / seats).coerceAtMost(1.0)
}

@Serializable
data class Route(
    val id: Int,
    val airlineId: String,
    val from: String,
    val to: String,
    /** 표준 운임 대비 배율 */
    val fareMul: Double = 1.0,
    /** 주간 왕복 편수 */
    val freq: Int = 0,
    val planeIds: List<Int> = emptyList(),
    /** 노선 단위 서비스 투자 0..2 */
    val serviceExtra: Int = 0,
    val active: Boolean = true,
    val last: RouteResult? = null,
) {
    fun touches(city: String) = from == city || to == city
}

@Serializable
data class QuarterResult(
    val turn: Int,
    val revenue: Double = 0.0,
    val cargoRevenue: Double = 0.0,
    val fuelCost: Double = 0.0,
    val crewCost: Double = 0.0,
    val maintCost: Double = 0.0,
    /** 중정비비 — 기체를 통째로 뜯어 보는 값. 노선 정비비와 달리 편수가 아니라 기재에 붙는다. */
    val checkCost: Double = 0.0,
    /** 리스료 — 빌린 기재에 매 분기 나가는 고정비. 상각과 달리 현금이 실제로 나간다. */
    val leaseCost: Double = 0.0,
    val landingCost: Double = 0.0,
    val paxServiceCost: Double = 0.0,
    val distributionCost: Double = 0.0,
    val overhead: Double = 0.0,
    /** 슬롯 임차료 — 보유한 슬롯만큼 매 분기 나가는 고정비 */
    val slotRent: Double = 0.0,
    val adSpend: Double = 0.0,
    val depreciation: Double = 0.0,
    val interestCost: Double = 0.0,
    val businessIncome: Double = 0.0,
    /** 파업 합의금 등 그 분기에만 생긴 일시 비용 */
    val extraordinaryCost: Double = 0.0,
    val tax: Double = 0.0,
    val net: Double = 0.0,
    val pax: Double = 0.0,
    val rpk: Double = 0.0,
    val asks: Double = 0.0,
    val cash: Double = 0.0,
    val debt: Double = 0.0,
    val equity: Double = 0.0,
    /**
     * 그 분기의 항공유 값. 결산에서 "연료비가 왜 늘었나"를 짚으려면 **지난 분기의
     * 유가**가 있어야 하는데, 세계 상태는 현재 값만 들고 있어 지나간 값을 되찾을 수 없다.
     * 0 은 이 필드가 없던 시절의 세이브라는 뜻이라, 읽는 쪽이 원인 지목을 건너뛴다.
     */
    val oil: Double = 0.0,
) {
    /**
     * 같은 분기 실적을 합친다 (합병 시 프로포마 실적).
     * 주가가 최근 4분기 순익에서 나오므로, 안 합치면 흑자 회사를 사도 그 실적이
     * 통째로 사라지고 적자 회사를 사면 손실이 없던 일이 된다.
     */
    operator fun plus(o: QuarterResult) = QuarterResult(
        turn = turn,
        revenue = revenue + o.revenue,
        cargoRevenue = cargoRevenue + o.cargoRevenue,
        fuelCost = fuelCost + o.fuelCost,
        crewCost = crewCost + o.crewCost,
        maintCost = maintCost + o.maintCost,
        landingCost = landingCost + o.landingCost,
        paxServiceCost = paxServiceCost + o.paxServiceCost,
        distributionCost = distributionCost + o.distributionCost,
        overhead = overhead + o.overhead,
        slotRent = slotRent + o.slotRent,
        adSpend = adSpend + o.adSpend,
        depreciation = depreciation + o.depreciation,
        interestCost = interestCost + o.interestCost,
        businessIncome = businessIncome + o.businessIncome,
        extraordinaryCost = extraordinaryCost + o.extraordinaryCost,
        tax = tax + o.tax,
        net = net + o.net,
        pax = pax + o.pax,
        rpk = rpk + o.rpk,
        asks = asks + o.asks,
        cash = cash + o.cash,
        debt = debt + o.debt,
        equity = equity + o.equity,
        // 유가는 세계 값이라 합치지 않는다 — 두 회사 실적을 더한다고 기름값이 두 배가 되지 않는다.
        oil = oil,
    )

    val totalRevenue get() = revenue + cargoRevenue + businessIncome
    val operatingCost
        get() = fuelCost + crewCost + maintCost + landingCost +
            paxServiceCost + distributionCost + overhead + slotRent +
            adSpend + depreciation + extraordinaryCost
    val loadFactor get() = if (asks <= 0) 0.0 else (rpk / asks).coerceAtMost(1.0)
}

@Serializable
data class Airline(
    val id: String,
    val name: String,
    val short: String,
    val colorArgb: Long,
    val home: String,
    val isPlayer: Boolean,
    val trait: Trait,
    val cash: Double,
    val debt: Double,
    /** 발행 주식 수 */
    val shares: Double,
    val sharePrice: Double,
    /** 기내 서비스 등급 1..5 */
    val serviceLevel: Int,
    val brand: Map<Region, Double>,
    val slots: Map<String, Int>,
    val businesses: List<Business> = emptyList(),
    /** 타사 주식 보유 (항공사 id → 주식 수) */
    val holdings: Map<String, Double> = emptyMap(),
    /**
     * 이번 분기에 이미 사들인 주식 수 (항공사 id → 주식 수). 분기가 넘어갈 때 비워진다.
     * 매수 한 번마다 따로 상한을 재면 한 분기에 여러 번 눌러 상한을 우회할 수 있어서,
     * 분기 누적으로 재야 한다.
     */
    val boughtThisQuarter: Map<String, Double> = emptyMap(),
    /** 이번 분기에 이미 발행한 신주 수. 증자 한도도 분기 누적으로 재야 우회가 막힌다. */
    val issuedThisQuarter: Double = 0.0,
    /**
     * 캠페인 내내 증자로 찍어낸 신주 **누적** 총합. 되감지 않는다 (합병에서 자사주를
     * 소각해도 "이미 찍어낸 양"은 줄지 않는다 — 줄이면 인수할 때마다 한도가 되살아나
     * 평생 한도가 무의미해진다).
     *
     * 이 한도가 없으면 방어측이 분기마다 30% 를 무한히 찍어낼 수 있다. 매집이 분기 10%
     * 이므로 지분율이 (f+0.1)/1.3 의 고정점인 33% 에서 수렴해 — 적대적 인수가
     * **산술적으로** 불가능해진다. 실제로 플레이에서 그 증상이 나왔다.
     */
    val issuedTotal: Double = 0.0,
    /**
     * 창업 시 발행 주식 수 — 평생 희석 한도를 재는 고정 기준.
     *
     * `shares - issuedTotal` 로 유도하면 안 된다. 합병에서 자사주를 소각하면 `shares` 만
     * 줄어 기준이 함께 어긋난다. 별도로 들고 있으면 소각도, 인수도 기준을 건드리지 않는다.
     *
     * 0 은 "이 필드가 없던 시절의 세이브" 라는 뜻이다 — [skytycoon.core.save.Save] 가
     * 불러올 때 창업 주식 수로 채운다.
     */
    val foundingShares: Double = 0.0,
    /** 마지막으로 증자한 분기. 증자에는 쿨다운이 있다 (연 1회). */
    val lastIssueTurn: Int? = null,
    /**
     * 결산에서 한 번에 털어낼 일시 비용 (파업 합의금 등). 현금에서 바로 빼면
     * 손익계산서의 순익과 실제 현금 변동이 어긋나 리포트가 앞뒤가 안 맞게 된다.
     */
    val pendingCharges: Double = 0.0,
    val adBudget: Map<Region, Double> = emptyMap(),
    val alive: Boolean = true,
    val mergedInto: String? = null,
    /**
     * 바닥 면적 중 **비즈니스 객실**에 내주는 비율 (0.0 ~ [skytycoon.core.sim.Balance.BIZ_SHARE_MAX]).
     *
     * 좌석 수와 단가의 맞바꿈이다. 비즈니스 한 자리가 이코노미
     * [skytycoon.core.sim.Balance.BIZ_SEAT_SPACE] 석의 바닥을 쓰므로 총 좌석은 줄고,
     * 대신 앞자리 손님은 훨씬 비싸게 낸다. 출장 수요가 두터운 장거리에서는 남고,
     * 관광 위주 단거리에서는 줄어든 좌석만큼 손해다.
     */
    val bizShare: Double = 0.0,
    val bonusKey: String = "",
    val bonusLabel: String = "",
    /** 안전·신뢰도 0..1. 사고가 나면 떨어지고 서서히 회복된다 */
    val safety: Double = 1.0,
    val negativeQuarters: Int = 0,
    val results: List<QuarterResult> = emptyList(),
) {
    fun slotsAt(city: String) = slots[city] ?: 0
    fun brandIn(region: Region) = brand[region] ?: 0.0
    val lastResult get() = results.lastOrNull()
}

enum class NewsKind { WORLD, ECONOMY, RIVAL, PLAYER, ACCIDENT, MARKET, MILESTONE }

@Serializable
data class NewsItem(
    val turn: Int,
    val kind: NewsKind,
    val headline: String,
    val body: String = "",
)

@Serializable
data class Outcome(val won: Boolean, val rank: Int, val reason: String)

@Serializable
data class GameState(
    /**
     * 세이브 형식 버전. 0 은 버전을 새기기 전의 세이브라는 뜻이다
     * ([skytycoon.core.save.Save.migrate] 가 그걸 보고 옛 규칙을 손본다).
     */
    val formatVersion: Int = 0,
    val seed: Int,
    val rngState: Int,
    val scenarioId: String,
    val difficultyId: String,
    val turn: Int,
    val startYear: Int,
    val totalTurns: Int,
    val world: World,
    val cityState: Map<String, CityState>,
    val airlines: List<Airline>,
    val playerId: String,
    val routes: List<Route>,
    val planes: List<Plane>,
    val orders: List<Order> = emptyList(),
    val expansions: List<Expansion> = emptyList(),
    val nextId: Int = 1,
    val news: List<NewsItem> = emptyList(),
    val outcome: Outcome? = null,
) {
    val year: Int get() = startYear + turn / 4
    val quarter: Int get() = turn % 4 + 1
    val endYear: Int get() = startYear + (totalTurns - 1) / 4

    /**
     * 화면·세이브 문구에 쓰는 턴. 마지막 분기를 넘기면 turn 이 totalTurns 로 올라가
     * 종료 화면이 "플레이하지도 않은 다음 해 1분기"로 뜬다 — 끝난 판은 마지막으로
     * 진행한 분기를 그대로 보여준다.
     */
    val displayTurn: Int get() = if (outcome != null && turn >= totalTurns) turn - 1 else turn
    val displayYear: Int get() = startYear + displayTurn / 4
    val displayQuarter: Int get() = displayTurn % 4 + 1

    val player: Airline get() = airlines.first { it.id == playerId }
    fun airline(id: String): Airline = airlines.first { it.id == id }
    fun airlineOrNull(id: String): Airline? = airlines.firstOrNull { it.id == id }
    val livingAirlines: List<Airline> get() = airlines.filter { it.alive }

    fun routesOf(airlineId: String) = routes.filter { it.airlineId == airlineId }
    fun planesOf(airlineId: String) = planes.filter { it.airlineId == airlineId }

    /** 이 노선에 배속된 기재 — 중정비로 묶인 기체까지 포함한다 (배속 화면용). */
    fun assignedTo(routeId: Int) = planes.filter { it.routeId == routeId }

    /**
     * 그중 **이번 분기에 실제로 뜨는** 기재.
     *
     * 좌석·원가·정비시간은 전부 이쪽으로 세야 한다. 한 군데라도 [assignedTo] 를 쓰면
     * 정비 중인 기체가 손님을 태우거나, 뜨지도 않은 기체의 연료비가 청구된다.
     */
    fun flyingOn(routeId: Int) = planes.filter { it.routeId == routeId && !it.inCheck(turn) }
    fun plane(id: Int) = planes.first { it.id == id }
    fun route(id: Int) = routes.first { it.id == id }

    /** 해당 도시에서 이 항공사가 이미 쓰고 있는 슬롯 수 (주간 왕복 편수 합) */
    fun usedSlots(airlineId: String, city: String) =
        routes.filter { it.airlineId == airlineId && it.active && it.touches(city) }.sumOf { it.freq }

    fun freeSlots(airlineId: String, city: String) =
        airline(airlineId).slotsAt(city) - usedSlots(airlineId, city)

    /**
     * 이 공항의 총 슬롯 (기본 + 확장).
     *
     * 다섯 군데서 `city.slots + extraSlots` 를 각자 더하고 있었다 — 한 곳만 빠뜨려도
     * 확장한 공항에서 점유율이 1.0 을 넘는 식으로 조용히 어긋난다. 한 군데서 답한다.
     */
    fun totalSlots(cityId: String): Int {
        val base = skytycoon.core.data.Cities[cityId].slots * skytycoon.core.sim.Balance.SLOT_SUPPLY_MUL
        return base + (cityState[cityId]?.extraSlots ?: 0)
    }

    /** 도시의 미분양 슬롯 */
    fun unsoldSlots(city: City): Int {
        val taken = airlines.filter { it.alive }.sumOf { it.slotsAt(city.id) }
        return (totalSlots(city.id) - taken).coerceAtLeast(0)
    }
}
