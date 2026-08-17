#!/usr/bin/env python3
"""Natural Earth 육지 폴리곤 → app/src/commonMain/.../WorldShapes.kt 생성.

손으로 찍은 근사 폴리곤을 쓰던 자리를 실제 해안선 데이터로 바꾼다.

데이터: Natural Earth `ne_50m_land` (public domain).
  https://github.com/nvkelso/natural-earth-vector

50m 원본은 60k 점 / 1.4k 폴리곤이라 폰 지도에는 과하다. 그래서
  1) 남극처럼 화면 밖(위도 컷오프 아래)인 것은 버리고,
  2) 아주 작은 섬은 버리되 게임 도시가 있는 섬은 남기고,
  3) Douglas–Peucker 로 단순화한다.

출력은 델타 부호화한 정수 텍스트다. 좌표를 0.01°(≈1.1km)로 양자화하면 폰 화면
픽셀보다 훨씬 촘촘하므로 눈에 띄는 손실이 없고, 델타로 두면 대부분 1~3자리라
파일이 작아진다. 클래스 파일의 문자열 상수 한도(64KB)에 걸리지 않도록 조각으로 나눈다.

사용법:
    python3 tools/gen_world_shapes.py path/to/ne_50m_land.geojson
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

# 지도에서 잘라 쓰는 위도 범위 — WorldShapes.LAT_TOP / LAT_BOTTOM 과 맞춰야 한다.
LAT_TOP = 84.0
LAT_BOTTOM = -58.0

# Douglas–Peucker 허용 오차 (도). 0.08° ≈ 9km — 세계 전도에서는 픽셀 이하다.
TOLERANCE = 0.08

# 이 값보다 작은 폴리곤은 버린다 (제곱도 단위, 대략적인 신발끈 면적).
MIN_AREA = 0.35

# 작아도 남겨야 하는 섬 — 게임에 도시가 있거나 지도 식별에 중요한 곳.
# (경도, 위도) 근방을 포함하는 폴리곤은 면적 조건을 면제한다.
KEEP_NEAR = [
    (126.98, 37.57),  # 서울 (한반도는 대륙이지만 안전하게)
    (139.69, 35.69),  # 도쿄
    (103.82, 1.35),   # 싱가포르
    (114.17, 22.32),  # 홍콩
    (121.0, 23.7),    # 타이완
    (120.98, 14.6),   # 마닐라
    (106.85, -6.21),  # 자카르타
    (174.76, -36.85), # 오클랜드
    (-0.13, 51.51),   # 런던
    (-21.9, 64.13),   # 레이캬비크
    (-157.86, 21.31), # 호놀룰루
    (55.27, 25.2),    # 두바이 (대륙)
    (72.88, 19.08),   # 뭄바이 (대륙)
    (79.86, 6.93),    # 콜롬보 — 스리랑카
    (47.5, -18.9),    # 안타나나리보 — 마다가스카르
]


def ring_area(ring: list[list[float]]) -> float:
    a = 0.0
    for i in range(len(ring) - 1):
        x1, y1 = ring[i][0], ring[i][1]
        x2, y2 = ring[i + 1][0], ring[i + 1][1]
        a += x1 * y2 - x2 * y1
    return abs(a) / 2.0


def contains_point(ring: list[list[float]], px: float, py: float) -> bool:
    inside = False
    n = len(ring)
    for i in range(n - 1):
        x1, y1 = ring[i][0], ring[i][1]
        x2, y2 = ring[i + 1][0], ring[i + 1][1]
        if (y1 > py) != (y2 > py):
            xin = x1 + (py - y1) / (y2 - y1) * (x2 - x1)
            if px < xin:
                inside = not inside
    return inside


def near_kept(ring: list[list[float]]) -> bool:
    xs = [p[0] for p in ring]
    ys = [p[1] for p in ring]
    lo_x, hi_x, lo_y, hi_y = min(xs), max(xs), min(ys), max(ys)
    for px, py in KEEP_NEAR:
        # 경계 상자에 여유를 두고 걸리면 남긴다 (단순화 전이라 정확한 포함 판정은 불필요).
        if lo_x - 1.0 <= px <= hi_x + 1.0 and lo_y - 1.0 <= py <= hi_y + 1.0:
            return True
    return False


def perp_dist(p, a, b) -> float:
    ax, ay = a
    bx, by = b
    px, py = p
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return ((px - ax) ** 2 + (py - ay) ** 2) ** 0.5
    t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    cx, cy = ax + t * dx, ay + t * dy
    return ((px - cx) ** 2 + (py - cy) ** 2) ** 0.5


def simplify(points: list[tuple[float, float]], tol: float) -> list[tuple[float, float]]:
    """반복(비재귀) Douglas–Peucker — 폴리곤이 커서 재귀는 스택이 위험하다."""
    if len(points) < 3:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        lo, hi = stack.pop()
        if hi <= lo + 1:
            continue
        worst, worst_i = -1.0, -1
        for i in range(lo + 1, hi):
            d = perp_dist(points[i], points[lo], points[hi])
            if d > worst:
                worst, worst_i = d, i
        if worst > tol:
            keep[worst_i] = True
            stack.append((lo, worst_i))
            stack.append((worst_i, hi))
    return [p for p, k in zip(points, keep) if k]


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    src = Path(sys.argv[1])
    data = json.loads(src.read_text())

    # 폴리곤 하나 = [겉테두리, 구멍...]. 구멍을 버리면 카스피해가 육지로 칠해진다
    # (이 데이터셋의 내부 링은 카스피해 하나뿐이다 — 오대호는 구멍이 아니라
    # 겉테두리가 호안을 따라 돌아가므로 그냥 나온다).
    # MapView 가 even-odd 로 채우므로 링을 묶어서 넘긴다.
    polygons: list[list[list[list[float]]]] = []
    for feat in data["features"]:
        geom = feat["geometry"]
        polys = [geom["coordinates"]] if geom["type"] == "Polygon" else geom["coordinates"]
        for poly in polys:
            polygons.append(poly)

    def prep(ring: list[list[float]]) -> list[tuple[float, float]] | None:
        pts = [(float(p[0]), float(p[1])) for p in ring]
        simple = simplify(pts, TOLERANCE)
        if len(simple) < 4:
            return None
        if simple[0] != simple[-1]:
            simple.append(simple[0])
        return simple

    kept: list[list[list[tuple[float, float]]]] = []
    for poly in polygons:
        outer = poly[0]
        ys = [p[1] for p in outer]
        if max(ys) < LAT_BOTTOM or min(ys) > LAT_TOP:
            continue  # 남극 등 화면 밖
        if ring_area(outer) < MIN_AREA and not near_kept(outer):
            continue
        simple_outer = prep(outer)
        if simple_outer is None:
            continue
        rings = [simple_outer]
        for hole in poly[1:]:
            # 아주 작은 구멍은 화면에서 안 보이니 버린다 (점만 늘린다).
            if ring_area(hole) < MIN_AREA:
                continue
            simple_hole = prep(hole)
            if simple_hole is not None:
                rings.append(simple_hole)
        kept.append(rings)

    kept.sort(key=lambda p: -ring_area([[x, y] for x, y in p[0]]))

    # ---- 델타 부호화 (0.01° 양자화) ----
    # 폴리곤끼리는 ';', 한 폴리곤 안의 링끼리는 '|' 로 나눈다. 델타는 링마다 0 에서
    # 다시 시작한다 — 링 하나만 떼어 봐도 복원되도록.
    parts: list[str] = []
    total_pts = 0
    total_holes = 0
    for rings in kept:
        ring_strs: list[str] = []
        for ri, ring in enumerate(rings):
            nums: list[int] = []
            px = py = 0
            for x, y in ring:
                qx = int(round(x * 100))
                qy = int(round(y * 100))
                nums.append(qx - px)
                nums.append(qy - py)
                px, py = qx, qy
            total_pts += len(ring)
            if ri > 0:
                total_holes += 1
            ring_strs.append(",".join(str(n) for n in nums))
        parts.append("|".join(ring_strs))
    encoded = ";".join(parts)

    # 클래스 파일 문자열 상수 한도(64KB, UTF-8 바이트)를 넘지 않게 조각낸다.
    chunk = 40000
    chunks = [encoded[i : i + chunk] for i in range(0, len(encoded), chunk)]

    out = Path("app/src/commonMain/kotlin/skytycoon/ui/WorldShapes.kt")
    lines: list[str] = []
    lines.append("// DO NOT EDIT — tools/gen_world_shapes.py 가 생성합니다.")
    lines.append("// 원본 데이터: Natural Earth ne_50m_land (public domain).")
    lines.append("//   https://github.com/nvkelso/natural-earth-vector")
    lines.append("// 재생성: python3 tools/gen_world_shapes.py <ne_50m_land.geojson>")
    lines.append("package skytycoon.ui")
    lines.append("")
    lines.append("/**")
    lines.append(" * 지도 배경용 실제 해안선 (Natural Earth 50m 를 단순화한 것).")
    lines.append(" *")
    lines.append(f" * 폴리곤 {len(kept)}개 · 점 {total_pts}개 · 내부 링(내해) {total_holes}개.")
    lines.append(" * 좌표는 0.01°(약 1.1km)로 양자화하고 델타로 부호화해 문자열에 담았다 — 거대한 배열")
    lines.append(" * 리터럴은 바이트코드 한도에 걸리고, 문자열 상수 하나도 64KB 를 넘을 수 없어 조각으로")
    lines.append(" * 나눠 뒀다. 첫 접근에서 한 번만 푼다.")
    lines.append(" */")
    lines.append("object WorldShapes {")
    lines.append("")
    for i, c in enumerate(chunks):
        lines.append(f'    private const val D{i} =')
        lines.append(f'        "{c}"')
    lines.append("")
    joined = " + ".join(f"D{i}" for i in range(len(chunks)))
    lines.append("    /**")
    lines.append("     * 육지 하나. [rings] 의 첫 항목이 겉테두리, 나머지는 구멍(카스피해 같은 내해)이다.")
    lines.append("     * 각 링은 (경도, 위도) 쌍이 번갈아 들어간 닫힌 고리 — 구멍이 있으므로 그리는 쪽은")
    lines.append("     * even-odd 로 채워야 한다. 단순 채움으로 그리면 내해가 육지로 칠해진다.")
    lines.append("     */")
    lines.append("    class Landmass(val rings: List<DoubleArray>) {")
    lines.append("        val outer: DoubleArray get() = rings[0]")
    lines.append("    }")
    lines.append("")
    lines.append("    val landmasses: List<Landmass> by lazy(LazyThreadSafetyMode.NONE) { decode() }")
    lines.append("")
    lines.append("    private fun decode(): List<Landmass> {")
    lines.append(f"        val src = {joined}")
    lines.append("        return src.split(';').map { poly ->")
    lines.append("            Landmass(poly.split('|').map { ring -> decodeRing(ring) })")
    lines.append("        }")
    lines.append("    }")
    lines.append("")
    lines.append("    private fun decodeRing(ring: String): DoubleArray {")
    lines.append("        val nums = ring.split(',')")
    lines.append("        val out = DoubleArray(nums.size)")
    lines.append("        var x = 0")
    lines.append("        var y = 0")
    lines.append("        var i = 0")
    lines.append("        while (i < nums.size) {")
    lines.append("            x += nums[i].toInt()")
    lines.append("            y += nums[i + 1].toInt()")
    lines.append("            out[i] = x / 100.0")
    lines.append("            out[i + 1] = y / 100.0")
    lines.append("            i += 2")
    lines.append("        }")
    lines.append("        return out")
    lines.append("    }")
    lines.append("")
    lines.append("    /** 지도에서 잘라 쓸 위도 범위. 남극은 노선이 없으니 버린다. */")
    lines.append(f"    const val LAT_TOP = {LAT_TOP}")
    lines.append(f"    const val LAT_BOTTOM = {LAT_BOTTOM}")
    lines.append("}")
    out.write_text("\n".join(lines) + "\n")

    print(f"polygons={len(kept)} points={total_pts} encoded={len(encoded)}B chunks={len(chunks)}")
    print(f"-> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
