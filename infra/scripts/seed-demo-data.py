#!/usr/bin/env python3
"""데모용 실제 하천 데이터를 등록한다.

테스트 찌꺼기("fk-test-stream", "Test Stream" 등) 대신 실제 하천 이름과
대략적인 실제 좌표를 넣는다. 포트폴리오 화면에 테스트 문자열이 뜨지 않게 하려는 것.

좌표는 근사치다. 지도에 그럴듯하게 그려지는 수준이며 측량 데이터가 아니다.

bash 가 아니라 Python 인 이유: Windows 셸에서 curl -d 에 한글을 직접 넣으면
CP949 로 나가서 백엔드가 'Invalid UTF-8 middle byte' 로 400 을 낸다.
여기서는 UTF-8 로 명시해 인코딩한다.

전제: 스택이 떠 있고 DB 가 비어 있어야 한다 (down -v 후 dev-up.sh).
DELETE 엔드포인트가 없으므로 이 스크립트는 기존 데이터를 지우지 못한다.
"""
import io
import json
import os
import sys
import urllib.request
from pathlib import Path

# Windows 콘솔 기본 인코딩(cp949)에서는 한글·기호 출력이 UnicodeEncodeError 로 죽는다.
# POST 는 성공했는데 출력에서 실패하는 형태라 특히 헷갈린다.
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[2]
API = os.environ.get("API_BASE", "http://localhost:8080")


def load_key() -> str:
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        if line.startswith("INTERNAL_API_KEY="):
            return line.split("=", 1)[1].strip()
    sys.exit("INTERNAL_API_KEY 가 .env 에 없다")


KEY = load_key()


def post(path: str, payload: dict) -> dict:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        API + path,
        data=body,
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "X-Internal-Key": KEY,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            return json.loads(res.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        sys.exit(f"POST {path} 실패 ({e.code}): {e.read().decode('utf-8', 'replace')}")


# (하천 이름, WKT LineString, [(카메라번호, WKT Point, 방향), ...])
STREAMS = [
    ("한강", "LINESTRING(126.93 37.53, 126.96 37.52, 126.995 37.51)", [
        ("HAN-01", "POINT(126.93 37.53)", "여의도"),
        ("HAN-02", "POINT(126.96 37.52)", "한강대교"),
        ("HAN-03", "POINT(126.995 37.51)", "반포"),
    ]),
    ("중랑천", "LINESTRING(127.06 37.63, 127.05 37.58, 127.04 37.545)", [
        ("JN-01", "POINT(127.06 37.63)", "월계"),
        ("JN-02", "POINT(127.05 37.58)", "장안"),
        ("JN-03", "POINT(127.04 37.545)", "성수"),
    ]),
    ("우이천", "LINESTRING(127.01 37.655, 127.02 37.635, 127.045 37.625)", [
        ("UI-01", "POINT(127.01 37.655)", "우이동"),
        ("UI-02", "POINT(127.02 37.635)", "수유"),
        ("UI-03", "POINT(127.045 37.625)", "월계합류"),
    ]),
]

for name, wkt, trails in STREAMS:
    stream = post("/api/streams", {"name": name, "location": wkt})
    print(f"▶ {name} (id={stream['id']})")
    for cam, point, direction in trails:
        post("/api/trails", {
            "stream_id": stream["id"],
            "camera_number": cam,
            "location": point,
            "direction": direction,
            "status": "active",
        })
        print(f"    - {cam} {direction}")

total = sum(len(t) for _, _, t in STREAMS)
print(f"\n하천 {len(STREAMS)}개 / 관측 지점 {total}개 등록 완료")
