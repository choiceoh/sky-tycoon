#!/usr/bin/env python3
"""APK 가 레포에 박아둔 디버그 키로 서명됐는지 확인한다.

키스토어 없이 빌드하면 AGP 가 그 자리에서 랜덤 키를 만들어 서명한다. 빌드는
초록불로 끝나고 APK 도 멀쩡해 보이지만, 폰에 이미 깔린 앱 위에 덮어 설치할 때
서명이 달라 "앱이 설치되지 않음" 으로 튕긴다. 그 회귀는 APK 를 뜯어보기 전에는
드러나지 않으므로 CI 에서 지문을 대조한다.

사용법:
    python3 tools/verify_apk_signer.py <apk> [--expect <SHA-256 지문>]

지문을 생략하면 android/debug.keystore 의 인증서에서 직접 뽑아 비교한다
(keytool 이 있을 때). 지문은 대소문자·콜론을 무시하고 비교한다.
"""

import hashlib
import re
import struct
import subprocess
import sys
from pathlib import Path

APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
V2_BLOCK_ID = 0x7109871A  # APK Signature Scheme v2
V3_BLOCK_ID = 0xF05368C0  # v3 — 있으면 여기 인증서도 같은 키여야 한다


def signing_block_entries(apk: bytes) -> dict[int, bytes]:
    """APK 서명 블록을 훑어 {블록 ID: 값} 으로 돌려준다."""
    magic_at = apk.rfind(APK_SIG_BLOCK_MAGIC)
    if magic_at < 0:
        raise SystemExit("APK 서명 블록이 없다 — 서명되지 않은 APK 다.")
    (size,) = struct.unpack_from("<Q", apk, magic_at - 8)
    start = magic_at + len(APK_SIG_BLOCK_MAGIC) - (size + 8)
    offset, end = start + 8, magic_at - 8
    entries: dict[int, bytes] = {}
    while offset < end:
        length, block_id = struct.unpack_from("<QI", apk, offset)
        entries[block_id] = apk[offset + 12 : offset + 8 + length]
        offset += 8 + length
    return entries


def first_certificate(block: bytes) -> bytes:
    """v2/v3 블록의 첫 서명자 인증서(DER)를 꺼낸다.

    두 스킴 모두 앞부분 배치가 같다: signers → signer → signed data →
    digests → certificates. 길이 접두사(uint32)를 따라 건너뛰면 닿는다.
    """

    def u32(buf: bytes, at: int) -> int:
        return struct.unpack_from("<I", buf, at)[0]

    at = 4 + 4 + 4  # signers 길이, signer 길이, signed data 길이
    signed = block[at : at + u32(block, 8)]
    at = 4 + u32(signed, 0)  # digests 통째로 건너뛴다
    at += 4  # certificates 길이
    return signed[at + 4 : at + 4 + u32(signed, at)]


def normalize(fingerprint: str) -> str:
    return re.sub(r"[^0-9a-f]", "", fingerprint.lower())


def keystore_fingerprint(keystore: Path) -> str | None:
    """디버그 키스토어에서 기대 지문을 뽑는다. keytool 이 없으면 None."""
    try:
        out = subprocess.run(
            ["keytool", "-list", "-v", "-keystore", str(keystore), "-storepass", "android"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError):
        return None
    found = re.search(r"SHA256:\s*([0-9A-Fa-f:]+)", out)
    return found.group(1) if found else None


def main(argv: list[str]) -> int:
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0

    apk_path = Path(argv[0])
    expected = None
    if "--expect" in argv:
        expected = argv[argv.index("--expect") + 1]
    if expected is None:
        keystore = Path(__file__).resolve().parent.parent / "android" / "debug.keystore"
        expected = keystore_fingerprint(keystore)
    if expected is None:
        raise SystemExit("기대 지문을 알 수 없다 — --expect 로 넘겨라.")

    apk = apk_path.read_bytes()
    entries = signing_block_entries(apk)
    if V2_BLOCK_ID not in entries:
        raise SystemExit("v2 서명이 없다 — minSdk 26 기준으로 설치되지 않는다.")

    # v3 까지 있으면 둘 다 같은 키여야 한다. 하나라도 어긋나면 업데이트 설치가 깨진다.
    seen = {
        name: hashlib.sha256(first_certificate(entries[block_id])).hexdigest()
        for name, block_id in (("v2", V2_BLOCK_ID), ("v3", V3_BLOCK_ID))
        if block_id in entries
    }
    for scheme, digest in seen.items():
        mark = "OK" if digest == normalize(expected) else "불일치"
        print(f"  {scheme}: {digest} [{mark}]")

    if any(digest != normalize(expected) for digest in seen.values()):
        print(f"\n기대한 지문: {normalize(expected)}", file=sys.stderr)
        print(
            "APK 가 레포의 디버그 키로 서명되지 않았다. 이대로 배포하면 이미 깔린 앱 위에\n"
            "덮어 설치할 때 폰이 '앱이 설치되지 않음' 으로 거부한다.",
            file=sys.stderr,
        )
        return 1

    print(f"\n{apk_path.name}: 레포 디버그 키로 서명됨.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
