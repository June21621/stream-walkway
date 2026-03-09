// ─────────────────────────────────────────
// kafka.js 단위 테스트
//
// 테스트 전략:
//   - kafka.js는 모듈 레벨에 `let producer = null` 상태를 가짐
//   - 테스트 간 상태 격리를 위해 beforeEach에서 jest.resetModules() + jest.doMock() + require() 사용
//   - kafkajs는 외부 브로커 없이 동작할 수 없으므로 jest로 완전히 모킹
// ─────────────────────────────────────────

describe('kafka.js', () => {
  let connectKafka;
  let publishMessage;
  let MockKafka;
  let mockProducer;
  let mockConnect;
  let mockSend;

  beforeEach(() => {
    // 모듈 캐시 초기화 → producer 상태 리셋
    jest.resetModules();

    mockSend = jest.fn().mockResolvedValue([{ topicName: 'test', partition: 0 }]);
    mockConnect = jest.fn().mockResolvedValue(undefined);

    mockProducer = {
      connect: mockConnect,
      send: mockSend,
    };

    MockKafka = jest.fn().mockImplementation(() => ({
      producer: jest.fn().mockReturnValue(mockProducer),
    }));

    jest.doMock('kafkajs', () => ({ Kafka: MockKafka }));

    ({ connectKafka, publishMessage } = require('../src/kafka'));
  });

  afterEach(() => {
    jest.clearAllMocks();
  });

  // ─────────────────────────────────────────
  // connectKafka()
  // ─────────────────────────────────────────
  describe('connectKafka()', () => {

    test('Kafka producer를 연결한다', async () => {
      await connectKafka();
      expect(mockConnect).toHaveBeenCalledTimes(1);
    });

    test("clientId를 'youtube-service'로 설정한다", async () => {
      await connectKafka();
      expect(MockKafka).toHaveBeenCalledWith(
        expect.objectContaining({ clientId: 'youtube-service' })
      );
    });

    test('KAFKA_BOOTSTRAP_SERVERS 환경변수가 없으면 localhost:9092를 기본값으로 사용한다', async () => {
      delete process.env.KAFKA_BOOTSTRAP_SERVERS;
      await connectKafka();
      expect(MockKafka).toHaveBeenCalledWith(
        expect.objectContaining({ brokers: ['localhost:9092'] })
      );
    });

    test('KAFKA_BOOTSTRAP_SERVERS 환경변수가 있으면 해당 주소를 사용한다', async () => {
      process.env.KAFKA_BOOTSTRAP_SERVERS = 'kafka1:9092,kafka2:9092';
      await connectKafka();
      expect(MockKafka).toHaveBeenCalledWith(
        expect.objectContaining({ brokers: ['kafka1:9092', 'kafka2:9092'] })
      );
      delete process.env.KAFKA_BOOTSTRAP_SERVERS;
    });

    test('연결 실패 시 에러를 전파한다', async () => {
      mockConnect.mockRejectedValue(new Error('Broker connection failed'));
      await expect(connectKafka()).rejects.toThrow('Broker connection failed');
    });
  });

  // ─────────────────────────────────────────
  // publishMessage()
  // ─────────────────────────────────────────
  describe('publishMessage()', () => {

    test('producer가 초기화되지 않은 상태에서 호출하면 에러를 던진다', async () => {
      // connectKafka() 없이 바로 publishMessage() 호출
      await expect(publishMessage('image.downloaded', {}))
        .rejects.toThrow('Kafka producer가 초기화되지 않았습니다.');
    });

    test('지정한 토픽으로 메시지를 발행한다', async () => {
      await connectKafka();
      const message = { imageId: 'test-001', streamId: 1 };
      await publishMessage('image.downloaded', message);

      expect(mockSend).toHaveBeenCalledWith(
        expect.objectContaining({ topic: 'image.downloaded' })
      );
    });

    test('메시지를 JSON 문자열로 직렬화하여 발행한다', async () => {
      await connectKafka();
      const message = { imageId: 'test-001', streamId: 1, trailId: 2 };
      await publishMessage('image.downloaded', message);

      expect(mockSend).toHaveBeenCalledWith({
        topic: 'image.downloaded',
        messages: [{ value: JSON.stringify(message) }],
      });
    });

    test('서로 다른 토픽으로 발행할 수 있다', async () => {
      await connectKafka();
      await publishMessage('image.downloaded', { imageId: 'a' });
      await publishMessage('image.analyzed', { imageId: 'b' });

      expect(mockSend).toHaveBeenNthCalledWith(1,
        expect.objectContaining({ topic: 'image.downloaded' })
      );
      expect(mockSend).toHaveBeenNthCalledWith(2,
        expect.objectContaining({ topic: 'image.analyzed' })
      );
    });

    test('send 실패 시 에러를 전파한다', async () => {
      await connectKafka();
      mockSend.mockRejectedValue(new Error('Send failed'));
      await expect(publishMessage('image.downloaded', {})).rejects.toThrow('Send failed');
    });
  });

  // ─────────────────────────────────────────
  // /download 엔드포인트 → Kafka 연동 (RED - /download 미구현)
  //
  // 아래 테스트들은 /download 구현 후 GREEN이 되어야 한다.
  // ─────────────────────────────────────────
  describe('[RED] POST /download → Kafka 발행 연동', () => {
    const request = require('supertest');

    test('[RED] POST /download 성공 시 image.downloaded 토픽으로 Kafka 메시지를 발행한다', async () => {
      await connectKafka();

      // app.js가 kafka.js의 publishMessage를 사용하도록 연동되어야 한다
      const { app } = require('../src/app');

      await request(app)
        .post('/download')
        .send({
          stream_id: 1,
          trail_id: 1,
          youtube_url: 'https://www.youtube.com/watch?v=example',
          interval_sec: 5,
        });

      // /download 구현 후 Kafka 발행이 이루어져야 한다
      expect(mockSend).toHaveBeenCalledWith(
        expect.objectContaining({ topic: 'image.downloaded' })
      );
    });

    test('[RED] Kafka 발행 메시지에 stream_id, trail_id, youtube_url이 포함된다', async () => {
      await connectKafka();
      const { app } = require('../src/app');

      await request(app)
        .post('/download')
        .send({
          stream_id: 1,
          trail_id: 2,
          youtube_url: 'https://www.youtube.com/watch?v=example',
          interval_sec: 5,
        });

      const sentMessage = JSON.parse(mockSend.mock.calls[0][0].messages[0].value);
      expect(sentMessage).toMatchObject({
        stream_id: 1,
        trail_id: 2,
        youtube_url: 'https://www.youtube.com/watch?v=example',
      });
    });
  });
});
