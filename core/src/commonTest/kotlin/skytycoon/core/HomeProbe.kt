package skytycoon.core

import skytycoon.core.data.Cities
import skytycoon.core.data.Companies
import skytycoon.core.sim.Demand
import skytycoon.core.sim.Geo
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * 홈 시장 진단 하네스 — 항공사마다 **집 앞에 얼마나 시장이 있는지**를 잰다.
 * 도시나 항공사를 새로 넣을 때 그 홈이 다른 회사들과 견줘 굶지 않는지 본다.
 *   ./gradlew :core:jvmTest --tests '*HomeProbe*' -i
 *
 * 이걸로 잡은 것: 모스크바는 3,000km 안에 도시가 많은데도(유럽이 촘촘해서) 홈
 * 수요 총량이 2,769k 로 하위권이었다. 소련 국내선이 지도에 아예 없어서, 제2세계의
 * 수도가 서유럽 노선만 바라보는 꼴이었다. 국내 도시 셋을 넣어 3,425k 가 됐다.
 */
@Ignore
class HomeProbe {
    @Test
    fun probe() {
        println("홈  |  3000km내 도시  단거리수요   전체 홈수요   가장가까운 이웃")
        for (c in Companies.all.sortedBy { it.home }) {
            val home = Cities[c.home]
            val others = Cities.all.filter { it.id != home.id }
            val near = others.filter { Geo.distance(home.id, it.id) <= 3000 }
            val nearDemand = near.sumOf { Demand.annualBase(home, it).total }
            val allDemand = others.sumOf { Demand.annualBase(home, it).total }
            val closest = others.minByOrNull { Geo.distance(home.id, it.id) }!!
            println(
                "${home.name}(${c.short}) | ${near.size}개  ${(nearDemand / 1000).toInt()}k  " +
                    "${(allDemand / 1000).toInt()}k  ${closest.name} ${Geo.distance(home.id, closest.id).toInt()}km",
            )
        }
    }
}
