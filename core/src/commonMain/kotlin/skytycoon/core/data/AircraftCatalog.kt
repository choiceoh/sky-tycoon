package skytycoon.core.data

import skytycoon.core.model.AircraftType
import skytycoon.core.sim.Balance
import kotlin.math.pow
import kotlin.math.sqrt

object AircraftCatalog {
    val all: List<AircraftType> = listOf(
        AircraftType("b707", "B707-320B", "보잉", 1962, 1979, 189, 6700, 900.0, 18e6, 6.2, 780.0, 620.0, 1.10, 2.0, bloc = "west"),
        AircraftType("dc8", "DC-8-63", "더글러스", 1967, 1980, 219, 7200, 900.0, 21e6, 6.8, 810.0, 650.0, 1.20, 2.0, bloc = "west"),
        AircraftType("b727", "B727-200", "보잉", 1967, 1984, 149, 4000, 880.0, 14e6, 4.2, 640.0, 520.0, 0.75, 1.0, bloc = "west"),
        AircraftType("b737_200", "B737-200", "보잉", 1968, 1988, 115, 3500, 810.0, 10e6, 3.4, 520.0, 430.0, 0.60, 1.0, bloc = "west"),
        AircraftType("dc9", "DC-9-30", "더글러스", 1967, 1982, 105, 2800, 800.0, 8.5e6, 3.1, 500.0, 420.0, 0.60, 1.0, bloc = "west"),
        AircraftType("b747_100", "B747-100", "보잉", 1970, 1986, 366, 9800, 900.0, 45e6, 12.5, 1450.0, 1050.0, 1.70, 8.0, bloc = "west", widebody = true),
        AircraftType("dc10", "DC-10-30", "더글러스", 1972, 1989, 270, 10000, 900.0, 38e6, 9.5, 1160.0, 880.0, 1.40, 5.0, bloc = "west", widebody = true),
        AircraftType("l1011", "L-1011 트라이스타", "록히드", 1972, 1984, 256, 7400, 900.0, 36e6, 9.2, 1180.0, 870.0, 1.40, 5.0, bloc = "west", widebody = true),
        AircraftType("a300", "A300B4", "에어버스", 1975, 1994, 250, 5400, 870.0, 33e6, 7.6, 960.0, 760.0, 1.20, 4.0, widebody = true),
        AircraftType("concorde", "콩코드", "아에로스파시알", 1976, 1980, 100, 6200, 2150.0, 62e6, 25.5, 3600.0, 1400.0, 1.50, 22.0),
        AircraftType("b747sp", "B747SP", "보잉", 1976, 1987, 290, 12300, 920.0, 50e6, 11.6, 1420.0, 1020.0, 1.60, 9.0, bloc = "west", widebody = true),
        AircraftType("md80", "MD-80", "맥도넬더글러스", 1980, 1999, 155, 4600, 810.0, 30e6, 3.3, 500.0, 440.0, 0.65, 2.0, bloc = "west"),
        AircraftType("b767", "B767-300ER", "보잉", 1982, 2013, 218, 9700, 850.0, 62e6, 5.6, 780.0, 690.0, 1.10, 6.0, bloc = "west", widebody = true),
        AircraftType("b757", "B757-200", "보잉", 1983, 2004, 200, 6300, 850.0, 48e6, 4.6, 620.0, 560.0, 0.80, 4.0, bloc = "west"),
        AircraftType("a320", "A320-200", "에어버스", 1988, 2020, 164, 5700, 830.0, 44e6, 3.0, 470.0, 450.0, 0.65, 4.0),
        AircraftType("b747_400", "B747-400", "보잉", 1989, 2007, 416, 13400, 910.0, 148e6, 11.5, 1360.0, 1080.0, 1.70, 14.0, bloc = "west", widebody = true),
        AircraftType("md11", "MD-11", "맥도넬더글러스", 1990, 2000, 293, 12600, 890.0, 112e6, 9.0, 1090.0, 880.0, 1.40, 7.0, bloc = "west", widebody = true),
        AircraftType("a340", "A340-300", "에어버스", 1993, 2008, 295, 13500, 880.0, 126e6, 8.2, 1010.0, 870.0, 1.40, 9.0, widebody = true),
        AircraftType("b777", "B777-200ER", "보잉", 1997, 2018, 305, 14300, 900.0, 172e6, 7.9, 900.0, 800.0, 1.30, 12.0, bloc = "west", widebody = true),
        AircraftType("b737_800", "B737-800", "보잉", 1998, 2019, 184, 5400, 840.0, 62e6, 2.9, 420.0, 440.0, 0.60, 5.0, bloc = "west"),
        AircraftType("a380", "A380-800", "에어버스", 2007, 2021, 555, 15200, 900.0, 405e6, 13.4, 1700.0, 1500.0, 2.00, 20.0, widebody = true),
        AircraftType("b787", "B787-9", "보잉", 2014, 2040, 290, 14100, 900.0, 282e6, 5.7, 700.0, 780.0, 1.10, 15.0, bloc = "west", widebody = true),
        AircraftType("a350", "A350-900", "에어버스", 2015, 2040, 325, 15000, 900.0, 305e6, 5.9, 720.0, 810.0, 1.20, 16.0, widebody = true),
        // --- 1999~2005 ---
        // 메가캐리어 시나리오(1990~2005)가 1998년 737-800 이후로 텅 비어 있었다.
        AircraftType("b717", "B717-200", "보잉", 1999, 2020, 106, 3800, 810.0, 35e6, 2.9, 470.0, 420.0, 0.55, 3.0, bloc = "west"),
        AircraftType("a340_600", "A340-600", "에어버스", 2002, 2012, 326, 14450, 900.0, 240e6, 8.6, 950.0, 860.0, 1.35, 13.0, widebody = true),
        AircraftType("b777_300er", "B777-300ER", "보잉", 2004, 2040, 396, 13650, 900.0, 320e6, 8.2, 900.0, 850.0, 1.35, 16.0, bloc = "west", widebody = true),
        AircraftType("a321neo", "A321neo", "에어버스", 2017, 2040, 220, 7400, 840.0, 129e6, 2.6, 390.0, 460.0, 0.60, 8.0),
        // --- 2017년 이후 ---
        // 여기가 비어 있으면 현대 시나리오 후반 내내 새 기종이 한 대도 안 나와,
        // "신기종이 항속거리·좌석을 바꿔 노선 전략을 흔든다"는 재미 축이 멈춘다.
        AircraftType("b737max8", "B737 MAX 8", "보잉", 2017, 2040, 178, 6570, 840.0, 122e6, 2.5, 380.0, 450.0, 0.60, 7.0, bloc = "west"),
        AircraftType("a220", "A220-300", "에어버스", 2018, 2040, 145, 6300, 830.0, 91e6, 2.2, 350.0, 420.0, 0.55, 7.0),
        AircraftType("b787_10", "B787-10", "보잉", 2018, 2040, 336, 11750, 900.0, 338e6, 6.1, 730.0, 800.0, 1.15, 16.0, bloc = "west", widebody = true),
        AircraftType("a350_1000", "A350-1000", "에어버스", 2018, 2040, 369, 16100, 900.0, 366e6, 6.5, 760.0, 850.0, 1.25, 18.0, widebody = true),
        AircraftType("a330neo", "A330-900neo", "에어버스", 2018, 2040, 287, 13300, 880.0, 296e6, 5.4, 690.0, 770.0, 1.15, 13.0, widebody = true),
        AircraftType("e195e2", "E195-E2", "엠브라에르", 2019, 2040, 132, 4800, 830.0, 70e6, 2.0, 330.0, 400.0, 0.50, 6.0),
        AircraftType("a330_800", "A330-800neo", "에어버스", 2020, 2040, 257, 15100, 880.0, 260e6, 5.2, 670.0, 750.0, 1.15, 12.0, widebody = true),
        AircraftType("c919", "C919", "코맥", 2023, 2040, 164, 5555, 830.0, 99e6, 2.6, 400.0, 440.0, 0.62, 5.0),
        AircraftType("a321xlr", "A321XLR", "에어버스", 2024, 2040, 220, 8700, 840.0, 142e6, 2.7, 400.0, 470.0, 0.62, 10.0),
        // 아래 셋은 취항 예정 기종이라 연도가 실제와 달라질 수 있다 (인도 지연이 잦은 프로그램들).
        AircraftType("b737max10", "B737 MAX 10", "보잉", 2026, 2040, 230, 6110, 840.0, 135e6, 2.7, 400.0, 470.0, 0.62, 8.0, bloc = "west"),
        AircraftType("b777_9", "B777-9", "보잉", 2027, 2040, 426, 13500, 900.0, 442e6, 7.2, 850.0, 900.0, 1.30, 20.0, bloc = "west", widebody = true),
        AircraftType("c929", "C929", "코맥", 2028, 2040, 280, 12000, 900.0, 290e6, 5.8, 720.0, 790.0, 1.20, 10.0, widebody = true),

        // --- 구소련권 ---
        // 값은 싸지만 기름을 먹고 정비가 잦으며 브랜드값이 안 붙는다. 서방기와 반대쪽
        // 극단에 두어, 기재값을 아끼는 대신 운항비를 계속 무는 선택지가 되게 했다.
        // 유가가 오르면 그만큼 더 아프다 (연료 보너스가 있어야 버틸 만하다).
        AircraftType("tu114", "Tu-114", "투폴레프", 1962, 1976, 170, 8800, 770.0, 16e6, 7.6, 900.0, 680.0, 1.30, 2.0, bloc = "east"),
        AircraftType("tu134", "Tu-134A", "투폴레프", 1967, 1989, 84, 2900, 850.0, 6.5e6, 3.6, 560.0, 400.0, 0.60, 0.0, bloc = "east"),
        AircraftType("tu154", "Tu-154B", "투폴레프", 1972, 2005, 164, 3900, 900.0, 12e6, 6.2, 760.0, 540.0, 0.75, 1.0, bloc = "east"),
        AircraftType("il62", "Il-62M", "일류신", 1974, 1995, 186, 10000, 870.0, 19e6, 8.4, 980.0, 700.0, 1.20, 3.0, bloc = "east"),
        AircraftType("yak42", "Yak-42", "야코블레프", 1980, 2005, 120, 2900, 810.0, 9e6, 4.0, 600.0, 450.0, 0.60, 1.0, bloc = "east"),
        // 광동체인데 항속거리가 5,000km 뿐이다 — 실물이 그랬다. 큰 좌석수를 단거리에서만
        // 쓸 수 있어 "덩치는 큰데 갈 데가 없는" 기종이 된다.
        AircraftType("il86", "Il-86", "일류신", 1980, 1997, 350, 5000, 900.0, 30e6, 14.5, 1500.0, 1050.0, 1.60, 4.0, bloc = "east", widebody = true),
        AircraftType("il96", "Il-96-300", "일류신", 1993, 2015, 262, 11000, 870.0, 75e6, 9.8, 1200.0, 900.0, 1.45, 6.0, bloc = "east", widebody = true),
        AircraftType("tu204", "Tu-204-100", "투폴레프", 1996, 2020, 210, 4600, 850.0, 45e6, 4.6, 700.0, 580.0, 0.85, 3.0, bloc = "east"),
        AircraftType("ssj100", "SSJ100", "수호이", 2011, 2040, 98, 3000, 830.0, 38e6, 2.4, 400.0, 400.0, 0.55, 3.0, bloc = "east"),
        AircraftType("mc21", "MC-21-300", "이르쿠트", 2025, 2040, 180, 6000, 840.0, 96e6, 2.6, 390.0, 450.0, 0.60, 6.0, bloc = "east"),

        // --- 유럽 군소 제작사 ---
        // 좌석수가 서방 주력기와 미묘하게 어긋나 있어, 수요가 어중간한 구간을 메우는
        // 데 쓴다. 대체로 싸고 조용하지만 항속거리가 짧다.
        AircraftType("caravelle", "카라벨 10B", "쉬드아비아시옹", 1962, 1975, 99, 2500, 800.0, 7e6, 3.6, 540.0, 420.0, 0.60, 1.0),
        AircraftType("trident", "트라이던트 2E", "호커시들리", 1968, 1985, 115, 3500, 900.0, 11e6, 4.4, 620.0, 480.0, 0.65, 1.0),
        AircraftType("bac111", "BAC 1-11-500", "BAC", 1968, 1990, 119, 2700, 800.0, 9.5e6, 3.5, 540.0, 430.0, 0.60, 1.0),
        AircraftType("f28", "포커 F28-4000", "포커", 1969, 1987, 85, 2000, 780.0, 7.5e6, 2.9, 480.0, 400.0, 0.55, 1.0),
        // 좌석은 727 급인데 항속거리가 2,100km — 실물의 상업적 실패 원인 그대로다.
        AircraftType("mercure", "다소 메르퀴르", "다소", 1974, 1995, 162, 2100, 900.0, 13e6, 4.5, 630.0, 500.0, 0.70, 1.0),
        AircraftType("bae146", "BAe 146-300", "브리티시에어로스페이스", 1983, 2002, 112, 2900, 760.0, 28e6, 3.4, 560.0, 460.0, 0.60, 2.0),
        AircraftType("f100", "포커 100", "포커", 1988, 2000, 109, 3200, 780.0, 32e6, 2.8, 460.0, 420.0, 0.55, 2.0),
        // 유일한 터보프롭. 느린 대신 기름을 거의 안 먹어, 짧고 얇은 구간에서만 값어치가 있다.
        AircraftType("atr72", "ATR 72-500", "ATR", 1989, 2040, 74, 1500, 510.0, 22e6, 1.3, 300.0, 300.0, 0.45, 1.0),
    ).map { it.copy(price = it.price * priceMultiplier(it)) }

    private val byId = all.associateBy { it.id }

    operator fun get(id: String): AircraftType = byId.getValue(id)
    fun find(id: String): AircraftType? = byId[id]

    /** 구소련권 기종을 굴리는 진영의 홈 공항. */
    private val EAST_HOMES = setOf("moscow", "stpetersburg", "novosibirsk", "tashkent")

    /**
     * 냉전이 풀려 동구권이 미국기를 살 수 있게 되는 해.
     * 유럽기는 이 해와 무관하게 처음부터 양쪽 다 살 수 있고,
     * 소련기는 이 해 뒤로도 계속 그 진영 전용이다.
     */
    const val IRON_CURTAIN_UNTIL = 1991

    /**
     * 이 항공사가 이 기종을 굴릴 수 있는가.
     *
     * 진영은 셋이다. **미국기**(`"west"`)와 **구소련기**(`"east"`)는 서로 넘어가지
     * 못하고, **유럽기**(빈 값)는 양쪽 다 산다 — 유럽 제작사가 두 진영 사이의
     * 중립 공급처 노릇을 한다.
     *
     * | 기종 | 서방 항공사 | 모스크바 기반 |
     * |---|---|---|
     * | 미국 (보잉·더글러스·록히드) | 언제나 | 1991년까지 불가 |
     * | 유럽 (에어버스·포커·BAC·ATR…) | 언제나 | 언제나 |
     * | 구소련 (투폴레프·일류신·야코블레프…) | 불가 | 언제나 |
     *
     * 이 제약이 없으면 소련기가 값싸다는 이유만으로 전 세계 항공사에 팔려 나간다 —
     * 실제로 넣어 보니 브리타니아와 리버티까지 Tu-154 를 굴리고 서방 협동체가
     * 통째로 밀려나(투폴레프 110대), 진영마다 기재가 다르다는 질감이 사라졌다.
     */
    fun operableBy(type: AircraftType, homeCityId: String, year: Int): Boolean {
        val east = homeCityId in EAST_HOMES
        return when (type.bloc) {
            "east" -> east
            "west" -> !east || year > IRON_CURTAIN_UNTIL
            else -> true // 유럽·중립 제작사는 양쪽 다 판다.
        }
    }

    /** 해당 연도에 신조기로 발주 가능한 기종. */
    fun newFor(year: Int): List<AircraftType> = all.filter { year >= it.year && year <= it.retire }

    /** 그 항공사가 그 해에 실제로 발주할 수 있는 신조기. */
    fun newFor(year: Int, homeCityId: String): List<AircraftType> =
        newFor(year).filter { operableBy(it, homeCityId, year) }

    /** 중고 시장 매물 (생산 종료 후 12년까지). */
    fun usedFor(year: Int): List<AircraftType> = all.filter { year > it.year + 2 && year <= it.retire + 12 }

    /** 그 항공사가 그 해에 살 수 있는 중고 매물. */
    fun usedFor(year: Int, homeCityId: String): List<AircraftType> =
        usedFor(year).filter { operableBy(it, homeCityId, year) }

    /**
     * 기령에 따른 잔존가치 비율 — **연 7%씩** 감가한다.
     * (분기마다 3.5%를 깎으면 15년 만에 하한까지 떨어져 기업가치·중고 시세가 통째로 무너진다)
     * 15년 ≈ 34%, 25년 ≈ 16%, 하한 12%.
     */
    /**
     * 기체 급에 따른 가격 배수 (Balance 의 기재값 주석 참고).
     *
     * 좌석과 항속거리를 함께 본다 — "큰 기체를 멀리 보내는 능력"이 값의 핵심이다.
     * 소형 단거리기는 1.0 에 머무르고, 광동체 장거리기는 두 배 넘게 붙는다. 일률로
     * 올리면 대당 매출이 작은 단거리 노선이 먼저 죽는다.
     */
    fun priceMultiplier(t: AircraftType): Double {
        val scale = (t.seats / Balance.PLANE_SCALE_SEATS) * (t.range / Balance.PLANE_SCALE_RANGE)
        val over = (sqrt(scale) - Balance.PLANE_PRICE_FLOOR).coerceAtLeast(0.0)
        return 1.0 + Balance.PLANE_PRICE_SLOPE * over
    }

    fun residualRatio(ageQuarters: Int): Double =
        (0.93.pow(ageQuarters / 4.0)).coerceAtLeast(0.12)
}
