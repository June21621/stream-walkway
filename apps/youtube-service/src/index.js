const express = require('express');
const app = express();

const PORT = process.env.PORT || 3000;

app.get('/health', (req, res) => {
  res.json({ status: 'ok', service: 'youtube-service' });
});

app.listen(PORT, () => {
  console.log(`[youtube-service] running on port ${PORT}`);
});
