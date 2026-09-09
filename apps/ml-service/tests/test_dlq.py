"""
dlq.py 순수 함수 테스트

I/O가 없는 부분만 여기서 못박는다. 발행(publish_dead_letter)은
producer mock이 필요하므로 test_consume.py에서 다룬다.

이 파일이 페이로드 계약의 기준이다. writer 쪽 DeadLetterPublisherTest가
같은 필드/같은 규칙을 검증한다 — 한쪽만 고치면 두 DLQ의 모양이 갈린다.
"""
import json

from dlq import build_dead_letter, dlq_topic


def _record(raw, exc, source="image.downloaded", consumer="ml"):
    return json.loads(build_dead_letter(source, consumer, raw, exc))


def test_dlq_topic_appends_suffix():
    assert dlq_topic("image.downloaded") == "image.downloaded.dlq"
    assert dlq_topic("image.analyzed") == "image.analyzed.dlq"


def test_payload_keeps_original_string():
    out = _record(b'{"trailId": 7}', ValueError("boom"))
    assert out["payload"] == '{"trailId": 7}'


def test_broken_json_is_kept_verbatim():
    # 파싱해서 담았다면 이 경우를 담을 수 없다. payload가 문자열인 이유다.
    out = _record(b"{ broken json", ValueError("boom"))
    assert out["payload"] == "{ broken json"


def test_undecodable_bytes_do_not_raise():
    # 디코딩 실패로 DLQ 발행이 죽으면 본말전도다.
    out = _record(b"\xff\xfe not utf-8", ValueError("boom"))
    assert isinstance(out["payload"], str)


def test_reason_is_type_and_message():
    out = _record(b"{}", ValueError("잘못된 값"))
    assert out["reason"] == "ValueError: 잘못된 값"


def test_reason_is_truncated_at_500():
    out = _record(b"{}", ValueError("x" * 900))
    assert len(out["reason"]) == 500


def test_failed_at_is_utc_with_z():
    out = _record(b"{}", ValueError("boom"))
    assert out["failedAt"].endswith("Z")
    assert "+00:00" not in out["failedAt"]


def test_source_and_consumer_are_recorded():
    out = _record(b"{}", ValueError("boom"))
    assert out["source"] == "image.downloaded"
    assert out["consumer"] == "ml"


def test_field_set_is_exactly_five():
    # 두 서비스가 같은 모양을 내야 한다. 필드가 늘면 여기서 걸린다.
    out = _record(b"{}", ValueError("boom"))
    assert set(out) == {"source", "consumer", "failedAt", "reason", "payload"}


def test_korean_is_not_escaped():
    out = json.loads(build_dead_letter("image.analyzed", "ml", '{"roadStatus":"양호"}', ValueError("boom")))
    assert out["payload"] == '{"roadStatus":"양호"}'
