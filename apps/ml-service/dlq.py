"""
실패한 메시지를 DLQ 토픽으로 보낸다.

설계 문서: docs/superpowers/specs/2026-09-08-kafka-dlq-design.md

여기서 만드는 JSON은 writer의 DeadLetterPublisher가 내는 것과 **같은 모양**이어야
한다. 한쪽만 고치면 두 DLQ를 다른 방법으로 읽어야 하고, 진단용 데이터에서
그건 그대로 비용이 된다.
"""
import json
import logging
from datetime import datetime, timezone

log = logging.getLogger(__name__)

DLQ_SUFFIX = ".dlq"

# reason에 스택트레이스를 넣지 않는 것과 같은 이유로 길이를 자른다.
# DLQ 메시지가 부풀면 진단용으로 읽기 나빠진다.
REASON_MAX_LEN = 500


def dlq_topic(source: str) -> str:
    """원본 토픽 이름에서 DLQ 토픽 이름을 만든다."""
    return f"{source}{DLQ_SUFFIX}"


def build_dead_letter(source: str, consumer: str, raw: bytes | str, exc: BaseException) -> bytes:
    """
    DLQ 메시지 본문을 만든다.

    raw는 Kafka에서 받은 원문(bytes 또는 str)이다. 파싱하지 않고 문자열 그대로
    담는다 — 깨진 JSON이 실패 원인의 하나라서, 파싱해서 담으면 정작 담아야 할
    경우를 담을 수 없다.
    """
    payload = raw.decode("utf-8", errors="replace") if isinstance(raw, (bytes, bytearray)) else str(raw)

    reason = f"{type(exc).__name__}: {exc}"[:REASON_MAX_LEN]

    record = {
        "source": source,
        "consumer": consumer,
        "failedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "reason": reason,
        "payload": payload,
    }
    # ensure_ascii=False: 한글이 \uXXXX로 부풀지 않게 한다. 사람이 읽는 데이터다.
    return json.dumps(record, ensure_ascii=False).encode("utf-8")


async def publish_dead_letter(producer, source: str, consumer: str, raw, exc: BaseException) -> bool:
    """
    실패한 메시지를 DLQ로 보낸다. 성공 여부를 돌려주고 **예외를 던지지 않는다.**

    send()가 아니라 send_and_wait()를 쓴다. aiokafka의 send()는 버퍼에 넣는
    것까지만 기다려서, 브로커가 죽어도 그 자리에서 예외가 안 날 수 있다.
    발행 실패가 드러나지 않으면 아래 except가 의미를 잃는다.
    """
    topic = dlq_topic(source)
    try:
        await producer.send_and_wait(topic, value=build_dead_letter(source, consumer, raw, exc))
        log.warning("DLQ 적재 topic=%s reason=%s", topic, f"{type(exc).__name__}: {exc}"[:REASON_MAX_LEN])
        return True
    except Exception:
        # 여기가 마지막 흔적이다. 원본 메시지를 통째로 남긴다.
        log.error("DLQ 발행 실패 topic=%s payload=%r", topic, raw, exc_info=True)
        return False
