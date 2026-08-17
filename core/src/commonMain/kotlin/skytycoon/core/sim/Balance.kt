package skytycoon.core.sim

/**
 * 게임 감각을 좌우하는 상수를 한곳에 모았다.
 *
 * 화폐 단위 주의: 모든 운임·원가 상수는 **시나리오 시작 시점의 명목 달러**로 잡혀 있고,
 * [skytycoon.core.model.World.inflation] 이 해마다 이들을 함께 밀어 올린다.
 * 기재 카탈로그 가격은 이미 시대별 명목가(747-100 = 4,500만 달러)라 물가를 따로 곱하지 않는다.
 *
 * BalanceTest 가 기준 노선(독점/경쟁)의 수지가 상식적인 범위에 있는지 지켜본다.
 */
object Balance {
    // --- 수요 ---
    /**
     * 연간 도시쌍 수요 스케일.
     *
     * 실제 서울–도쿄는 연 수백만 명이 오가지만, 그 규모를 그대로 쓰면 여객기 한 대(주 14왕복 =
     * 분기 5만 4천 석)가 시장에 아무 흔적도 남기지 못해 운임도 경쟁도 무의미해진다.
     * 그래서 수요를 게임 스케일로 압축했다 — 간선 노선 하나가 항공사 두세 곳을
     * 먹여 살리는 정도. 승객 1명은 현실의 여러 명에 해당하는 추상 단위로 읽으면 된다.
     */
    const val DEMAND_K = 1500.0
    const val DEMAND_BIZ_EXP = 0.55
    const val DEMAND_LEISURE_W = 1.2
    const val DEMAND_DIST_HALF = 1_800.0
    const val DEMAND_DIST_EXP = 0.9

    /**
     * 거리에 따른 세그먼트별 감쇠 지수 (클수록 멀어질수록 빨리 준다).
     *
     * 레저가 비즈니스보다 커야 한다 — 장거리일수록 승객이 출장 쪽으로 기울어야
     * 대형기 장거리 노선의 채산이 선다. 반대로 두면 장거리가 관광 수요 위주가 되어
     * 수익계수가 낮은 손님만 태우고 마진이 바닥을 긴다 (실제로 그랬다).
     */
    const val DEMAND_BIZ_DECAY_EXP = 0.35
    const val DEMAND_LEI_DECAY_EXP = 1.15
    const val DEMAND_SAME_REGION = 1.18
    /** 이보다 가까우면 육상교통에 밀린다 */
    const val DEMAND_RAIL_RANGE = 800.0
    const val DEMAND_RAIL_EXP = 0.5
    /** 항공여행 보급 지수 연간 성장 (도시 성장과 별개의 저변 확대) */
    const val TRAVEL_GROWTH_PER_YEAR = 1.02

    val SEASON_LEISURE = doubleArrayOf(0.86, 1.02, 1.32, 0.80)
    val SEASON_BIZ = doubleArrayOf(1.02, 1.06, 0.86, 1.06)

    // --- 시장 점유 (세그먼트별 다항 로짓) ---
    // 비즈니스 승객은 운임에 둔감하고 편수·서비스를 본다. 레저 승객은 그 반대.
    const val BIZ_PRICE_SENS = 1.70
    const val BIZ_FREQ_W = 0.85
    const val BIZ_SERVICE_W = 0.38
    const val LEI_PRICE_SENS = 4.20
    const val LEI_FREQ_W = 0.42
    const val LEI_SERVICE_W = 0.16

    const val SHARE_BRAND_W = 0.012
    const val SHARE_PRESTIGE_W = 0.020
    const val SHARE_HUB_W = 0.18
    const val SHARE_SAFETY_W = 0.90

    /**
     * 양 끝 공항에서 그 회사가 가진 **다른 취항지 수**(로그)의 가중.
     *
     * 경쟁 노선에서 승자와 패자를 가르는 축이다. 이게 없으면 같은 구간에 같은 기재를
     * 띄운 회사들이 브랜드 차이만큼만 갈려 다 같이 낮은 탑승률로 사이좋게 적자를 본다 —
     * 잘 지어 둔 네트워크가 보상받지 못한다.
     */
    const val SHARE_FEED_W = 0.30

    /**
     * 기반 국가 프리미엄. 홈 공항이 끝점이면 1.0, 같은 권역이면 0.5 가 곱해진다.
     * 자국 항공사가 자국 노선에서 갖는 인지도·영업망의 이점.
     */
    const val SHARE_HOME_W = 0.45
    /** 좌석이 모자라 흘러넘친 수요를 재배분하는 횟수 */
    const val SPILL_ROUNDS = 3

    /**
     * **로컬 항공사**(모델에 없는 지역 사업자)의 효용. 모든 도시쌍에서 바깥 선택지로 늘 겨룬다.
     *
     * 게임의 여덟 회사는 주요 항공사이고, 나머지 수요는 이들이 채우고 있다고 본다.
     * 이 항이 없으면 수요가 공급을 압도하는 순간 내놓은 좌석이 무조건 팔려 — 운임도
     * 서비스도 무의미해지고 아무도 적자를 내지 않는다. 실제로 그랬다.
     *
     * 값이 높을수록 로컬이 강해 메이저의 탑승률이 낮아진다. 0 은 "평범한 조건의
     * 메이저와 비슷한 매력"을 뜻한다 (효용은 상대값이라 절대 크기 자체엔 의미가 없다).
     *
     * 이 값은 [FRINGE_DIST_REF] km 기준이고 거리가 멀수록 [FRINGE_DIST_W] 만큼 깎인다.
     */
    const val FRINGE_UTIL = 1.95

    /** 로컬 효용의 기준 거리(km). 이 거리에서 [FRINGE_UTIL] 이 그대로 쓰인다. */
    const val FRINGE_DIST_REF = 1_000.0

    /**
     * 거리가 e 배 늘 때마다 로컬 효용에서 깎이는 양.
     *
     * 로컬 사업자가 어디에나 똑같이 깔려 있는 건 아니다. 단거리 역내 구간은 소형기로
     * 붙을 수 있는 사업자가 널렸지만, 태평양을 건너려면 광동체와 그걸 굴릴 자본이 필요해
     * **그럴 수 있는 회사는 대개 이미 게임 안에 있다**. 그래서 로컬의 매력이 거리와 함께
     * 빠져야 한다 — 그러지 않으면 도쿄-LA 같은 간판 간선이 독점인데도 반도 못 채운다.
     *
     * 이게 장·단거리의 성격을 갈라놓는다. 단거리는 로컬과 점유율을 다투느라 탑승률이
     * 경쟁력에 민감하고, 장거리는 자리를 잡으면 확실히 채우는 대신 기재값과 슬롯
     * 임차료라는 고정비를 감당해야 한다.
     */
    const val FRINGE_DIST_W = 0.85

    /**
     * 도시쌍별 로컬 경쟁력의 **표준편차**. 값 하나로 고정하지 않고 종 모양으로 흩는다.
     *
     * 로컬 사업자의 실력이 어디나 같을 리 없다 — 억센 국적사가 버티는 구간이 있고 손
     * 놓은 구간이 있다. 균일하게 두면 모든 노선이 똑같이 빡빡해서 어디를 뚫을지가
     * 판단거리가 되지 않는다. 흩어 놓으면 같은 수요·거리라도 채워지는 정도가 달라져,
     * **취항 전에 시장을 살펴보는 일**이 값어치를 갖는다.
     *
     * 0.6 은 효용 단위로 대략 "편수를 두 배로 늘린 것"만 한 폭이다. 노선 간 우열을
     * 뚜렷하게 만들되, 운임·편수·네트워크로 뒤집을 수 없을 만큼 크지는 않다.
     */
    const val FRINGE_SIGMA = 0.6
    /** 시장 평균 운임이 표준보다 싸면 수요 자체가 늘어난다 (유발 수요) */
    const val INDUCED_ELASTICITY = 0.45

    // --- 환승 (허브 경유) ---
    // 직항이 못 채운 좌석을 허브 연결 수요가 메운다. 이 값들이 허브 전략의 수익성을 정한다.

    /** 경유 거리가 직항의 이 배를 넘으면 아무도 안 탄다. */
    const val CONNECT_MAX_DETOUR = 1.35

    /**
     * "환승하지 않는다"는 선택지의 효용. 환승 후보들과 **같은 로짓 안에서** 겨루므로,
     * 경유가 불편하고 비쌀수록 (CONNECT_PENALTY 만큼) 아무도 안 타고 그냥 안 움직인다.
     *
     * 이 항이 없으면 후보끼리만 정규화되어 페널티가 통째로 상쇄되고, 후보가 하나뿐인
     * 도시쌍은 아무리 나쁜 여정이어도 남은 수요를 전부 가져간다.
     */
    const val CONNECT_OUTSIDE_UTIL = 0.0

    /** 경유 여정의 운임 할인 — 불편한 만큼 싸야 팔린다. */
    const val CONNECT_FARE_MUL = 0.92

    /** 환승 승객의 수익 계수. 로컬 승객보다 낮다 (연결 할인·수하물 처리). */
    const val CONNECT_YIELD = 1.05

    /** 경유에 붙는 효용 페널티. 낮추면 허브가 만능이 되고, 높이면 환승이 죽는다. */
    const val CONNECT_PENALTY = 1.15

    // --- 운임 (1970년 명목 달러, 편도 이코노미) ---
    /**
     * 거리와 무관하게 붙는 운임의 **고정 몫**.
     *
     * 7.0 은 너무 작았다. 원가에는 거리를 안 타는 항목이 많다 — 착륙료, 회항 시간,
     * 편당 판매·지상비. 운임만 거리에 거의 비례하게 두면 짧은 노선일수록 그 고정비를
     * 못 건지고, 실제로 **800km 아래는 편수를 어떻게 잡아도 흑자가 안 났다**
     * (런던–파리 343km 는 최선이 마진 -30%, 프랑크푸르트–런던 637km 는 -5%).
     * 거리대 하나가 통째로 죽은 콘텐츠였던 셈이다.
     *
     * 현실의 운임도 같은 이유로 단거리 km 당 단가가 훨씬 비싸다. 고정 몫을 키우고
     * km 단가를 낮춰, 장거리 운임은 그대로 두면서 단거리만 제 원가를 싣게 했다.
     */
    const val FARE_BASE = 30.0
    const val FARE_PER_KM = 0.030
    const val FARE_DIST_EXP = 1.02
    /** 비즈니스/퍼스트가 섞여 생기는 수익 가산 */
    const val BIZ_YIELD = 1.90
    const val LEI_YIELD = 0.95
    const val FARE_MIN_MUL = 0.55
    const val FARE_MAX_MUL = 1.80

    // --- 운항 원가 ---
    const val WEEKS_PER_QUARTER = 13.0
    /** 기재 1대의 주간 가동 상한 (시간) */
    const val MAX_WEEKLY_HOURS = 98.0
    /** 카탈로그 지상 조업시간에 더해지는 여유 (편도당) */
    const val TURNAROUND_EXTRA = 0.35
    /** 지상 활주 등으로 블록타임에 더해지는 시간 (편도당) */
    const val BLOCK_TAXI = 0.25
    /** 150석 기준 표준 착륙료 */
    const val LANDING_BASE = 1000.0
    const val NAV_PER_KM = 0.030
    const val PAX_SERVICE_BASE = 5.5
    const val PAX_SERVICE_PER_LEVEL = 2.2
    const val DISTRIBUTION_RATE = 0.13
    const val OVERHEAD_PER_AIRCRAFT = 120_000.0
    const val OVERHEAD_PER_ROUTE = 18_000.0
    const val OVERHEAD_FIXED = 1.6e6
    const val DEPRECIATION_QUARTERS = 60.0
    const val CARGO_RATE = 0.13
    const val TAX_RATE = 0.30
    /** 기령 1분기마다 정비비가 이만큼씩 붙는다 */
    const val AGE_MAINT_PER_QUARTER = 0.012

    // --- 기재 거래 ---
    // 매각가는 반드시 중고 매입가보다 낮아야 한다. 반대가 되면 중고기를 사서 즉시 되파는
    // 것만으로 무한히 현금을 찍어낼 수 있다 (BalanceTest 가 이 부등식을 지킨다).
    const val USED_PRICE_MUL = 0.72
    const val SELL_PENALTY = 0.62
    const val ORDER_DELAY_QUARTERS = 2

    // --- 슬롯 ---
    /**
     * 공항 슬롯 총량 배수 (도시 데이터의 기본값에 곱한다).
     *
     * 예전에는 슬롯 총량이 업계 공급을 **수요의 1/10 로 하드캡**해서, 내놓은 좌석이
     * 언제나 다 팔렸다 — 운임 경쟁도 탑승률 관리도 의미가 없고 아무도 적자를 내지
     * 않았다. 슬롯을 넉넉히 풀어 그 인위적 상한을 걷어내고, 대신 **분기 임차료**가
     * 억제 장치를 맡는다. 슬롯은 이제 못 구해서 못 늘리는 게 아니라, 유지비를 감당할
     * 수 있을 때만 쥐는 것이다.
     */
    const val SLOT_SUPPLY_MUL = 1
    /**
     * 슬롯 **취득 수수료**의 기준 단가. 예전에는 이 값이 슬롯 값 전부라 한 번에 크게 나갔다.
     * 지금은 슬롯을 사는 게 아니라 **임차**하는 것이라, 취득은 가볍고 유지가 무겁다.
     */
    const val SLOT_BASE_PRICE = 0.12e6

    /**
     * 슬롯 1개의 **분기 임차료** 기준 단가 (도시 규모·착륙료·물가가 곱해진다).
     *
     * 이 항목이 게임의 성격을 바꾼다. 슬롯이 일시불이던 때는 한 번 사두면 공짜로 굴러가
     * 노선을 놀려도 손해가 없었다 — 그래서 아무도 적자를 내지 않았고 현금만 쌓였다.
     * 매 분기 나가는 고정비로 바꾸면 **안 쓰는 슬롯이 곧 손실**이라 정리 압박이 생기고,
     * 수요가 꺾이는 분기에는 실제로 적자가 난다 (영업 레버리지).
     */
    const val SLOT_RENT_PER_QUARTER = 0.055e6

    // --- 공항 확장 공사 ---
    /**
     * 한 번 완공될 때마다 늘어나는 슬롯 수.
     *
     * 18 개는 너무 적었다 — 6분기를 기다려 큰돈을 넣고도 공항 포화가 그대로라
     * 확장이 판을 바꾸지 못했다. 허브 하나를 실제로 뚫어 주는 크기여야 한다.
     */
    const val EXPANSION_SLOTS = 60

    /** 그중 후원사가 공짜로 배정받는 몫 */
    const val EXPANSION_SPONSOR_SHARE = 0.5

    /** 착공에서 완공까지 (분기). 길어야 "언제 지를까"가 판단이 된다. */
    const val EXPANSION_QUARTERS = 6

    /**
     * 공사비 = **후원사가 받는 슬롯**([EXPANSION_SLOTS] × [EXPANSION_SPONSOR_SHARE])을
     * 지금 시세로 살 값의 이 배수. 1.0 이 "시세 그대로", 그 위는 없던 자리를 만들어 내는
     * 값에 얹는 웃돈이다.
     *
     * 예전에는 늘어나는 슬롯 **전부**에 곱해서, 3.0 이 실제로는 시세의 6배를 뜻했다.
     * 받은 슬롯은 임차라 장부에 거의 안 잡히니 확장은 곧 자기자본 소각이었고 —
     * 자동조종 10년에서 확장 두 번이 기업가치 2억 8천만 달러를 지웠다. 9.0 이던 시절엔
     * 레버리지가 800% 까지 치솟았다. 기준을 바로잡았으니 이 값은 순수한 웃돈이다.
     *
     * 포화 허브의 슬롯값은 이미 희소성으로 7배 가까이 부풀어 있다. 거기에 2배를 더
     * 얹으면 슬롯당 순익 기준 회수에 15분기쯤 걸린다 — 판을 뒤집을 만하되 공짜는 아니다.
     */
    const val EXPANSION_COST_MUL = 2.0

    /** 같은 공항을 거듭 확장할수록 붙는 가중 (1회당) */
    const val EXPANSION_REPEAT_MUL = 1.8
    const val SLOT_SCARCITY_EXP = 1.9
    const val SLOT_HOME_DISCOUNT = 0.6

    // --- 재무 ---
    const val BASE_INTEREST = 0.085
    const val DEBT_CAP_EQUITY = 2.2
    const val BANKRUPTCY_GRACE = 4
    const val SHARE_PE = 11.0
    const val SHARE_ASSET_W = 0.45
    const val TAKEOVER_THRESHOLD = 0.5
    const val TENDER_PREMIUM = 1.3
    /** 지분이 쌓일수록 남은 주식을 사기가 비싸진다 (매집이 알려지면 값이 뛴다). */
    const val TENDER_ESCALATION = 0.9
    /** 한 분기에 사들일 수 있는 지분 상한 — 하룻밤에 회사가 넘어가지 않도록. */
    const val MAX_STAKE_PER_QUARTER = 0.10
    /** 유상증자 발행가 할인율 */
    const val ISSUE_DISCOUNT = 0.92

    /**
     * 증자 쿨다운 (분기). 방어측이 매 분기 찍어내면 매집을 산술적으로 앞질러
     * 인수가 영영 불가능하다 — 실제로도 유상증자는 연 단위 이벤트다.
     *
     * 이 값이 4 면 방어는 연 30%, 매집은 연 40%(분기 10%) 라 공격이 이긴다.
     * 지분 0 에서 시작해 6분기째 과반을 넘긴다 — 1년 반쯤 밀어붙여야 하는 셈.
     */
    const val ISSUE_COOLDOWN_QUARTERS = 4

    /**
     * 캠페인 전체를 통틀어 찍어낼 수 있는 신주 — 원래 주식 수의 배수.
     * 1.0 이면 "발행 주식을 최대 두 배까지 늘릴 수 있다".
     * 쿨다운만 두면 캠페인이 길어질수록 방어가 다시 무한해지므로 총량도 묶는다.
     */
    const val DILUTION_LIFETIME_CAP = 1.0

    // --- 마케팅 ---
    /** 광고비 100만 달러당 브랜드 포인트 */
    const val AD_EFFICIENCY = 42.0
    const val BRAND_DECAY = 0.90
    const val BRAND_MAX = 100.0
    const val SERVICE_UPGRADE_COST = 26e6
    const val SERVICE_OPEX_PER_LEVEL_PER_PLANE = 32_000.0

    // --- AI ---
    const val AI_CASH_FLOOR = 40e6
    const val AI_MAX_NEW_ROUTES = 3
    const val AI_MAX_ORDERS = 3
}
