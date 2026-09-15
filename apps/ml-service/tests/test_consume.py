"""
ml-service consume() 함수 테스트 (TDD)

테스트 전략:
  - consume()는 무한 루프로 동작하는 비동기 함수
  - AIOKafkaConsumer를 유한한 메시지를 yield하는 AsyncIterator로 모킹
  - AIOKafkaProducer.send()를 AsyncMock으로 모킹하여 발행 내용 검증
  - `main.AIOKafkaConsumer` / `main.AIOKafkaProducer`를 patch하여 격리

RED 항목:
  - 잘못된 JSON 수신 시 예외가 전파되지 않아야 하는 테스트
    → 현재 구현에 try/except 없음 → 실패 예상
"""
import json
import pytest
from unittest.mock import AsyncMock, MagicMock, patch, call

from main import consume


# ─────────────────────────────────────────
# 테스트 헬퍼
# ─────────────────────────────────────────

class AsyncMessageIterator:
    """유한한 메시지 목록을 yield하는 비동기 이터레이터 (Kafka Consumer 대체)"""

    def __init__(self, *messages):
        self._messages = messages
        self._index = 0

    def __aiter__(self):
        self._index = 0
        return self

    async def __anext__(self):
        if self._index >= len(self._messages):
            raise StopAsyncIteration
        msg = self._messages[self._index]
        self._index += 1
        return msg


def make_message(data: dict) -> MagicMock:
    """Kafka 메시지 mock 생성"""
    msg = MagicMock()
    msg.value = json.dumps(data).encode()
    return msg


def make_consumer_mock(*messages) -> MagicMock:
    """비동기 이터레이션을 지원하는 Kafka consumer mock"""
    mock = MagicMock()
    mock.start = AsyncMock()
    mock.stop = AsyncMock()
    iterator = AsyncMessageIterator(*messages)
    mock.__aiter__ = lambda self: iterator
    mock.__anext__ = iterator.__anext__
    return mock


def make_producer_mock() -> AsyncMock:
    """Kafka producer mock"""
    mock = AsyncMock()
    mock.start = AsyncMock()
    mock.stop = AsyncMock()
    mock.send = AsyncMock()
    return mock


# ─────────────────────────────────────────
# consume() 기본 동작 테스트
# ─────────────────────────────────────────

class TestConsumeBasicBehavior:

    @pytest.mark.asyncio
    async def test_consumer_starts_and_subscribes_to_image_downloaded(self):
        """consume()은 'image.downloaded' 토픽을 구독한다"""
        consumer_mock = make_consumer_mock()
        producer_mock = make_producer_mock()

        with patch('main.AIOKafkaConsumer', return_value=consumer_mock) as mock_consumer_cls, \
             patch('main.AIOKafkaProducer', return_value=producer_mock):
            await consume()

        mock_consumer_cls.assert_called_once()
        args, kwargs = mock_consumer_cls.call_args
        assert args[0] == 'image.downloaded'

    @pytest.mark.asyncio
    async def test_consumer_uses_ml_group_id(self):
        """consume()은 group_id로 'ml-group'을 사용한다"""
        consumer_mock = make_consumer_mock()
        producer_mock = make_producer_mock()

        with patch('main.AIOKafkaConsumer', return_value=consumer_mock) as mock_consumer_cls, \
             patch('main.AIOKafkaProducer', return_value=producer_mock):
            await consume()

        _, kwargs = mock_consumer_cls.call_args
        assert kwargs.get('group_id') == 'ml-group'

    @pytest.mark.asyncio
    async def test_consumer_and_producer_are_started(self):
        """consume()은 consumer와 producer를 모두 start한다"""
        consumer_mock = make_consumer_mock()
        producer_mock = make_producer_mock()

        with patch('main.AIOKafkaConsumer', return_value=consumer_mock), \
             patch('main.AIOKafkaProducer', return_value=producer_mock):
            await consume()

        consumer_mock.start.assert_called_once()
        producer_mock.start.assert_called_once()

    @pytest.mark.asyncio
    async def test_consumer_and_producer_are_stopped_in_finally(self):
        """consume()은 finally 블록에서 consumer와 producer를 stop한다"""
        consumer_mock = make_consumer_mock()
        producer_mock = make_producer_mock()

        with patch('main.AIOKafkaConsumer', return_value=consumer_mock), \
             patch('main.AIOKafkaProducer', return_value=producer_mock):
            await consume()

        consumer_mock.stop.assert_called_once()
        producer_mock.stop.assert_called_once()

    @pytest.mark.asyncio
    async def test_stop_is_called_even_when_no_messages(self):
        """메시지가 없어도 stop이 호출된다"""
        consumer_mock = make_consumer_mock()  # 메시지 없음
        producer_mock = make_producer_mock()

        with patch('main.AIOKafkaConsumer', return_value=consumer_mock), \
             patch('main.AIOKafkaProducer', return_value=producer_mock):
            await consume()

        consumer_mock.stop.assert_called_once()
        producer_mock.stop.assert_called_once()


# ─────────────────────────────────────────
# 메시지 처리 및 발행 테스트
# ─────────────────────────────────────────

class TestConsumeMessageProcessing:

    @pytest.mark.asyncio
    async def test_publishes_to_image_analyzed_topic(self):
        """메시지 수신 후 'image.analyzed' 토픽으로 결과를 발행한다"""
        msg = make_message({
            "imageId": "test-001",
            "trailId": 1,
            "streamId": 1,
            "imagePath": "/images/test-001.jpg",
            "timestamp": "2024-01-01T00:00:00Z",
        })
        consumer_mock = make_consumer_mock(msg)
        producer_mock = make_producer_mock()

        with patch('main.AIOKafkaConsumer', return_value=consumer_mock), \
             patch('main.AIOKafkaProducer', return_value=producer_mock):
            await consume()

        producer_mock.send.assert_called_once()
        topic_arg = producer_mock.send.call_args[0][0]
        assert topic_arg == 'image.analyzed'

    @pytest.mark.asyncio
    async def test_published_result_contains_image_id(self):
        """발행된 결과에 imageId가 포함된다"""
        msg = make_message({"imageId": "test-001", "trailId": 1, "streamId": 1,
                            "imagePath": "/images/test-001.jpg", "timestamp": "2024-01-01T00:00:00Z"})
        consumer_mock = make_consumer_mock(msg)
        producer_mock = make_producer_mock()

        with patch('main.AIOKafkaConsumer', return_value=consumer_mock), \
             patch('main.AIOKafkaProducer', return_value=producer_mock):
            await consume()

        sent_value = json.loads(producer_mock.send.call_args[1]['value'].decode())
        assert sent_value['imageId'] == 'test-001'

    @pytest.mark.asyncio
    async def test_published_result_contains_trail_and_stream_id(self):
        """발행된 결과에 trailId와 streamId가 포함된다"""
        msg = make_message({"imageId": "test-001", "trailId": 2, "streamId": 3,
                            "imagePath": "/images/test-001.jpg", "timestamp": "2024-01-01T00:00:00Z"})
        consumer_mock = make_consumer_mock(msg)
        producer_mock = make_producer_mock()

        with patch('main.AIOKafkaConsumer', return_value=consumer_mock), \
             patch('main.AIOKafkaProducer', return_value=producer_mock):
            await consume()

        sent_value = json.loads(producer_mock.send.call_args[1]['value'].decode())
        assert sent_value['trailId'] == 2
        assert sent_value['streamId'] == 3

    @pytest.mark.asyncio
    async def test_published_result_contains_road_status(self):
        """발행된 결과에 roadStatus가 포함된다"""
        msg = make_message({"imageId": "test-001", "trailId": 1, "streamId": 1,
                            "imagePath": "/images/test-001.jpg", "timestamp": "2024-01-01T00:00:00Z"})
        consumer_mock = make_consumer_mock(msg)
        producer_mock = make_producer_mock()

        with patch('main.AIOKafkaConsumer', return_value=consumer_mock), \
             patch('main.AIOKafkaProducer', return_value=producer_mock):
            await consume()

        sent_value = json.loads(producer_mock.send.call_args[1]['value'].decode())
        assert 'roadStatus' in sent_value
        assert sent_value['roadStatus'] == '양호'

    @pytest.mark.asyncio
    async def test_published_result_contains_confidence(self):
        """발행된 결과에 confidence가 포함된다"""
        msg = make_message({"imageId": "test-001", "trailId": 1, "streamId": 1,
                            "imagePath": "/images/test-001.jpg", "timestamp": "2024-01-01T00:00:00Z"})
        consumer_mock = make_consumer_mock(msg)
        producer_mock = make_producer_mock()

        with patch('main.AIOKafkaConsumer', return_value=consumer_mock), \
             patch('main.AIOKafkaProducer', return_value=producer_mock):
            await consume()

        sent_value = json.loads(producer_mock.send.call_args[1]['value'].decode())
        assert 'confidence' in sent_value
        assert sent_value['confidence'] == 0.95

    @pytest.mark.asyncio
    async def test_published_result_contains_analyzed_at_from_timestamp(self):
        """발행된 결과의 analyzedAt은 수신 메시지의 timestamp이다"""
        msg = make_message({"imageId": "test-001", "trailId": 1, "streamId": 1,
                            "imagePath": "/images/test-001.jpg", "timestamp": "2024-01-01T00:00:00Z"})
        consumer_mock = make_consumer_mock(msg)
        producer_mock = make_producer_mock()

        with patch('main.AIOKafkaConsumer', return_value=consumer_mock), \
             patch('main.AIOKafkaProducer', return_value=producer_mock):
            await consume()

        sent_value = json.loads(producer_mock.send.call_args[1]['value'].decode())
        assert sent_value['analyzedAt'] == '2024-01-01T00:00:00Z'

    @pytest.mark.asyncio
    async def test_published_result_is_valid_json_bytes(self):
        """발행되는 메시지 value는 JSON으로 파싱 가능한 bytes이다"""
        msg = make_message({"imageId": "test-001", "trailId": 1, "streamId": 1,
                            "imagePath": "/images/test-001.jpg", "timestamp": "2024-01-01T00:00:00Z"})
        consumer_mock = make_consumer_mock(msg)
        producer_mock = make_producer_mock()

        with patch('main.AIOKafkaConsumer', return_value=consumer_mock), \
             patch('main.AIOKafkaProducer', return_value=producer_mock):
            await consume()

        raw_value = producer_mock.send.call_args[1]['value']
        assert isinstance(raw_value, bytes)
        parsed = json.loads(raw_value.decode())
        assert isinstance(parsed, dict)

    @pytest.mark.asyncio
    async def test_multiple_messages_are_all_processed(self):
        """여러 메시지를 수신하면 각각 image.analyzed를 발행한다"""
        msgs = [
            make_message({"imageId": f"test-{i:03d}", "trailId": i, "streamId": 1,
                          "imagePath": f"/images/test-{i:03d}.jpg", "timestamp": "2024-01-01T00:00:00Z"})
            for i in range(1, 4)
        ]
        consumer_mock = make_consumer_mock(*msgs)
        producer_mock = make_producer_mock()

        with patch('main.AIOKafkaConsumer', return_value=consumer_mock), \
             patch('main.AIOKafkaProducer', return_value=producer_mock):
            await consume()

        assert producer_mock.send.call_count == 3


# ─────────────────────────────────────────
# 예외 처리 테스트 (RED)
#
# 현재 구현에는 try/except가 없으므로 아래 테스트들은 실패한다.
# GREEN을 위해 consume() 내부에 메시지별 try/except 추가 필요.
# ─────────────────────────────────────────

class TestConsumeErrorHandling:

    @pytest.mark.asyncio
    async def test_invalid_json_does_not_propagate_exception(self):
        """[RED] 잘못된 JSON 메시지를 수신해도 예외가 전파되지 않고 계속 처리한다"""
        invalid_msg = MagicMock()
        invalid_msg.value = b'invalid-json-string'

        consumer_mock = make_consumer_mock(invalid_msg)
        producer_mock = make_producer_mock()

        with patch('main.AIOKafkaConsumer', return_value=consumer_mock), \
             patch('main.AIOKafkaProducer', return_value=producer_mock):
            # 현재 구현: json.loads 실패 → JSONDecodeError 전파 → 테스트 실패 (RED)
            await consume()

        # 잘못된 메시지이므로 발행되지 않아야 한다
        producer_mock.send.assert_not_called()

    @pytest.mark.asyncio
    async def test_invalid_json_skipped_and_next_message_processed(self):
        """[RED] 잘못된 JSON 메시지는 건너뛰고 다음 유효한 메시지를 처리한다"""
        invalid_msg = MagicMock()
        invalid_msg.value = b'{ broken }'

        valid_msg = make_message({"imageId": "test-001", "trailId": 1, "streamId": 1,
                                  "imagePath": "/images/test-001.jpg", "timestamp": "2024-01-01T00:00:00Z"})

        consumer_mock = make_consumer_mock(invalid_msg, valid_msg)
        producer_mock = make_producer_mock()

        with patch('main.AIOKafkaConsumer', return_value=consumer_mock), \
             patch('main.AIOKafkaProducer', return_value=producer_mock):
            # 현재 구현: 첫 번째 메시지에서 실패 → 두 번째 메시지 미처리 (RED)
            await consume()

        # 유효한 메시지 1개만 발행되어야 한다
        assert producer_mock.send.call_count == 1

    @pytest.mark.asyncio
    async def test_stop_is_called_even_when_exception_occurs(self):
        """[RED] 처리 중 예외가 발생해도 consumer와 producer가 stop된다"""
        invalid_msg = MagicMock()
        invalid_msg.value = b'not-json'

        consumer_mock = make_consumer_mock(invalid_msg)
        producer_mock = make_producer_mock()

        with patch('main.AIOKafkaConsumer', return_value=consumer_mock), \
             patch('main.AIOKafkaProducer', return_value=producer_mock):
            try:
                await consume()
            except Exception:
                pass  # 예외 발생 여부와 관계없이 stop 검증

        # finally 블록이 있으므로 stop은 항상 호출되어야 한다
        consumer_mock.stop.assert_called_once()
        producer_mock.stop.assert_called_once()


# ─────────────────────────────────────────
# DLQ 적재
# ─────────────────────────────────────────

class TestConsumeDeadLetter:

    @pytest.mark.asyncio
    async def test_broken_json_goes_to_dlq(self):
        """깨진 JSON은 image.downloaded.dlq 로 간다"""
        bad = MagicMock()
        bad.value = b'{ broken json'
        consumer_mock = make_consumer_mock(bad)
        producer_mock = make_producer_mock()

        with patch('main.AIOKafkaConsumer', return_value=consumer_mock), \
             patch('main.AIOKafkaProducer', return_value=producer_mock):
            await consume()

        producer_mock.send_and_wait.assert_called_once()
        args, kwargs = producer_mock.send_and_wait.call_args
        assert args[0] == 'image.downloaded.dlq'

        record = json.loads(kwargs['value'])
        assert record['payload'] == '{ broken json'
        assert record['source'] == 'image.downloaded'
        assert record['consumer'] == 'ml'
        assert record['reason'].startswith('JSONDecodeError')

    @pytest.mark.asyncio
    async def test_normal_message_does_not_touch_dlq(self):
        """정상 메시지는 DLQ를 건드리지 않는다"""
        good = make_message({
            "imageId": 1, "trailId": 7, "streamId": 4,
            "imagePath": "captures/4/7/x.jpg", "timestamp": "2026-09-09T00:00:00Z",
        })
        consumer_mock = make_consumer_mock(good)
        producer_mock = make_producer_mock()

        with patch('main.AIOKafkaConsumer', return_value=consumer_mock), \
             patch('main.AIOKafkaProducer', return_value=producer_mock):
            await consume()

        producer_mock.send_and_wait.assert_not_called()

    @pytest.mark.asyncio
    async def test_loop_continues_after_dlq(self):
        """DLQ로 보낸 뒤에도 뒤따르는 정상 메시지를 계속 처리한다"""
        bad = MagicMock()
        bad.value = b'{ broken json'
        good = make_message({
            "imageId": 2, "trailId": 7, "streamId": 4,
            "imagePath": "captures/4/7/y.jpg", "timestamp": "2026-09-09T00:01:00Z",
        })
        consumer_mock = make_consumer_mock(bad, good)
        producer_mock = make_producer_mock()

        with patch('main.AIOKafkaConsumer', return_value=consumer_mock), \
             patch('main.AIOKafkaProducer', return_value=producer_mock):
            await consume()

        # 정상 1건은 image.analyzed 로 나갔다
        producer_mock.send.assert_called_once()
        assert producer_mock.send.call_args[0][0] == 'image.analyzed'
        # 실패 1건은 DLQ 로 갔다
        producer_mock.send_and_wait.assert_called_once()

    @pytest.mark.asyncio
    async def test_dlq_failure_does_not_break_loop(self):
        """DLQ 발행이 실패해도 루프가 죽지 않는다"""
        bad = MagicMock()
        bad.value = b'{ broken json'
        good = make_message({
            "imageId": 3, "trailId": 7, "streamId": 4,
            "imagePath": "captures/4/7/z.jpg", "timestamp": "2026-09-09T00:02:00Z",
        })
        consumer_mock = make_consumer_mock(bad, good)
        producer_mock = make_producer_mock()
        producer_mock.send_and_wait.side_effect = RuntimeError("broker down")

        with patch('main.AIOKafkaConsumer', return_value=consumer_mock), \
             patch('main.AIOKafkaProducer', return_value=producer_mock):
            await consume()   # 예외가 새어나오면 여기서 실패한다

        # DLQ 발행은 시도됐고(실패했고), 그래도 루프가 살아 있다
        producer_mock.send_and_wait.assert_called_once()
        # 브로커가 죽었어도 뒤의 정상 메시지는 계속 시도된다
        producer_mock.send.assert_called_once()

    @pytest.mark.asyncio
    async def test_publish_failure_of_analyzed_also_goes_to_dlq(self):
        """image.analyzed 발행에 실패해도 source 는 구독 토픽이다"""
        good = make_message({
            "imageId": 4, "trailId": 7, "streamId": 4,
            "imagePath": "captures/4/7/w.jpg", "timestamp": "2026-09-09T00:03:00Z",
        })
        consumer_mock = make_consumer_mock(good)
        producer_mock = make_producer_mock()
        producer_mock.send.side_effect = RuntimeError("broker down")

        with patch('main.AIOKafkaConsumer', return_value=consumer_mock), \
             patch('main.AIOKafkaProducer', return_value=producer_mock):
            await consume()

        args, kwargs = producer_mock.send_and_wait.call_args
        assert args[0] == 'image.downloaded.dlq'
        record = json.loads(kwargs['value'])
        # 되돌려보낼 곳은 그 메시지를 받은 토픽이다
        assert record['source'] == 'image.downloaded'
        assert record['reason'].startswith('RuntimeError')
