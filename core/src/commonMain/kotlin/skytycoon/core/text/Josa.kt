package skytycoon.core.text

/**
 * 조사를 앞말의 받침에 맞춰 고른다.
 *
 * 회사·도시 이름을 문장에 끼워 넣는 곳이 많은데, 조사를 하나로 박아 두면
 * "리버티에어이 지분을 사들였습니다" 처럼 읽다가 걸리는 문장이 나온다.
 * 이름이 데이터에서 오는 이상 문장을 쓰는 쪽이 받침을 알 수 없으므로 여기서 정한다.
 */
object Josa {

    /** 이/가 */
    fun subject(word: String) = pick(word, "이", "가")

    /** 은/는 */
    fun topic(word: String) = pick(word, "은", "는")

    /** 을/를 */
    fun objective(word: String) = pick(word, "을", "를")

    /** 과/와 */
    fun with(word: String) = pick(word, "과", "와")

    /** 으로/로 — 받침이 ㄹ 이면 "로" 다 ("서울로"). */
    fun by(word: String): String {
        val final = finalConsonant(word)
        return if (final == null || final == 0 || final == 8) "로" else "으로"
    }

    private fun pick(word: String, withFinal: String, withoutFinal: String): String {
        val final = finalConsonant(word) ?: return withoutFinal
        return if (final != 0) withFinal else withoutFinal
    }

    /**
     * 마지막 글자의 종성 코드 (0 = 받침 없음). 한글도 숫자도 아니면 null —
     * 받침을 알 수 없다는 뜻이라 호출부가 받침 없는 쪽으로 넘어간다.
     */
    private fun finalConsonant(word: String): Int? {
        val ch = word.trimEnd().lastOrNull() ?: return null
        if (ch in '가'..'힣') return (ch - '가') % 28
        // 숫자는 읽는 소리의 종성으로 정한다 (747 → "칠사칠" 이라 받침이 있다).
        // 한글 종성 색인을 그대로 쓴다 — ㄹ 만 "로/으로"를 가르므로 ㄱ·ㅁ·ㅇ 을 뭉뚱그리면
        // "6으로"가 "6로"로 나온다.
        return when (ch) {
            '0' -> 21 // 영 → ㅇ
            '1' -> 8 // 일 → ㄹ
            '3' -> 16 // 삼 → ㅁ
            '6' -> 1 // 육 → ㄱ
            '7' -> 8 // 칠 → ㄹ
            '8' -> 8 // 팔 → ㄹ
            '2', '4', '5', '9' -> 0 // 이·사·오·구
            else -> null
        }
    }
}
