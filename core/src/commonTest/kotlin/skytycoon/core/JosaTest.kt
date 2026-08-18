package skytycoon.core

import skytycoon.core.data.Companies
import skytycoon.core.text.Josa
import kotlin.test.Test
import kotlin.test.assertEquals

class JosaTest {

    @Test
    fun picksByFinalConsonant() {
        assertEquals("이", Josa.subject("화남항공"))
        assertEquals("가", Josa.subject("리버티에어"))
        assertEquals("은", Josa.topic("서울"))
        assertEquals("는", Josa.topic("도쿄"))
        assertEquals("을", Josa.objective("노선"))
        assertEquals("를", Josa.objective("기재"))
        assertEquals("과", Josa.with("서울"))
        assertEquals("와", Josa.with("도쿄"))
    }

    /** ㄹ 받침은 "으로"가 아니라 "로"다 — 서울으로가 아니라 서울로. */
    @Test
    fun handlesRieulForBy() {
        assertEquals("로", Josa.by("서울"))
        assertEquals("로", Josa.by("도쿄"))
        assertEquals("으로", Josa.by("런던"))
    }

    /** 기종명처럼 숫자로 끝나는 말은 읽는 소리로 정한다. */
    @Test
    fun readsTrailingDigits() {
        assertEquals("이", Josa.subject("747")) // 칠사칠
        assertEquals("가", Josa.subject("A32")) // 삼십이
    }

    /**
     * 숫자의 종성은 실제 소리대로여야 한다. 받침 유무만 맞추면 이/가·은/는 은 통과하지만
     * ㄹ 만 따로 보는 [Josa.by] 에서 "영으로"가 "영로"로 샌다.
     */
    @Test
    fun digitsCarryTheRightFinalConsonant() {
        assertEquals("으로", Josa.by("100")) // 영 → ㅇ
        assertEquals("으로", Josa.by("6")) // 육 → ㄱ
        assertEquals("으로", Josa.by("3")) // 삼 → ㅁ
        assertEquals("로", Josa.by("1")) // 일 → ㄹ
        assertEquals("로", Josa.by("747")) // 칠 → ㄹ
        assertEquals("로", Josa.by("2")) // 이 → 받침 없음
    }

    /** 받침을 알 수 없으면 받침 없는 쪽으로 — 어느 쪽이든 틀리지만 덜 어색하다. */
    @Test
    fun fallsBackWhenUnknown() {
        assertEquals("가", Josa.subject("Boeing"))
        assertEquals("가", Josa.subject(""))
    }

    /** 게임에 나오는 회사 이름 전부가 한글로 끝나 실제로 판정된다. */
    @Test
    fun everyCompanyNameIsDecidable() {
        for (c in Companies.all) {
            val particle = Josa.subject(c.name)
            assertEquals(true, particle == "이" || particle == "가", "${c.name} → $particle")
        }
    }
}
