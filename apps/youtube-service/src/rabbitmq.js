const amqp = require('amqplib');

let channel = null;

async function connectRabbitMQ() {
  const {
    RABBITMQ_HOST = 'localhost',
    RABBITMQ_PORT = 5672,
    RABBITMQ_USER = 'stream_user',
    RABBITMQ_PASSWORD = 'rabbitmq_pw',
    RABBITMQ_VHOST = '/',
  } = process.env;

  const url = `amqp://${RABBITMQ_USER}:${RABBITMQ_PASSWORD}@${RABBITMQ_HOST}:${RABBITMQ_PORT}${RABBITMQ_VHOST}`;

  const connection = await amqp.connect(url);
  channel = await connection.createChannel();

  await channel.assertQueue('image.downloaded', { durable: true });
  console.log('[rabbitmq] 큐 준비 완료: image.downloaded');
}

async function publishMessage(queue, message) {
  if (!channel) {
    throw new Error('RabbitMQ channel이 초기화되지 않았습니다.');
  }

  channel.sendToQueue(
    queue,
    Buffer.from(JSON.stringify(message)),
    { persistent: true }
  );

  console.log(`[rabbitmq] 메시지 발행 → ${queue}:`, message);
}

module.exports = { connectRabbitMQ, publishMessage };
