const { app } = require('./app');
const { connectKafka } = require('./kafka');

const PORT = process.env.PORT || 3000;

async function start() {
  try {
    await connectKafka();
    app.listen(PORT, () => {
      console.log(`[youtube-service] running on port ${PORT}`);
    });
  } catch (err) {
    console.error('[youtube-service] 시작 실패:', err.message);
    process.exit(1);
  }
}

start();
