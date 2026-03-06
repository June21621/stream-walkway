const { Kafka } = require('kafkajs');

let producer = null;

async function connectKafka() {
  const bootstrapServers = process.env.KAFKA_BOOTSTRAP_SERVERS || 'localhost:9092';

  const kafka = new Kafka({
    clientId: 'youtube-service',
    brokers: bootstrapServers.split(','),
  });

  producer = kafka.producer();
  await producer.connect();

  console.log('[kafka] 연결 완료');
}

async function publishMessage(topic, message) {
  if (!producer) {
    throw new Error('Kafka producer가 초기화되지 않았습니다.');
  }

  await producer.send({
    topic,
    messages: [{ value: JSON.stringify(message) }],
  });

  console.log(`[kafka] 메시지 발행 → ${topic}:`, message);
}

module.exports = { connectKafka, publishMessage };
