FROM node:20-alpine

# 캡처에 ffmpeg가 필요하다. yt-dlp는 쓰지 않는다 —
# YouTube에서 프레임을 추출하는 것은 이용약관 위반이라
# 설계 단계에서 YouTube 어댑터를 만들지 않기로 했다.
RUN apk add --no-cache ffmpeg

WORKDIR /app

COPY package.json package-lock.json* ./
RUN npm install --only=production

COPY . .

ENV NODE_ENV=production
EXPOSE 3000

CMD ["node", "src/index.js"]
