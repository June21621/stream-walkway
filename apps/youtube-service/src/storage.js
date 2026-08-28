const { S3Client, PutObjectCommand, HeadBucketCommand, CreateBucketCommand } = require('@aws-sdk/client-s3');

// captures/{streamId}/{trailId}/{ISO8601}.jpg
//
// 트레일별로 폴더가 나뉘어 사람이 봐도 이해되고, 시각이 파일명이라
// 정렬이 자연스러우며, 같은 트레일에 같은 초로 두 번 찍힐 일이 없어
// 충돌하지 않는다.
//
// 콜론을 대시로 바꾸는 이유는 URL과 일부 파일시스템에서 콜론이
// 성가시기 때문이다. 밀리초는 버린다 - 15분 간격 표본에 불필요하다.
function buildKey(streamId, trailId, date = new Date()) {
  const iso = date.toISOString()
    .replace(/\.\d{3}Z$/, 'Z')
    .replace(/:/g, '-');
  return `captures/${streamId}/${trailId}/${iso}.jpg`;
}

function createStorage(env = process.env, client = null) {
  const bucket = env.MINIO_BUCKET || 'captures';

  client = client || new S3Client({
    endpoint: env.MINIO_ENDPOINT || 'http://minio:9000',
    region: env.MINIO_REGION || 'us-east-1',
    credentials: {
      accessKeyId: env.MINIO_ACCESS_KEY,
      secretAccessKey: env.MINIO_SECRET_KEY,
    },
    // MinIO는 가상 호스트 방식 주소를 기본으로 지원하지 않는다.
    // 경로 방식(http://host/bucket/key)을 강제해야 한다.
    forcePathStyle: true,
  });

  // 버킷이 없으면 만든다. 별도 초기화 컨테이너를 두지 않는다.
  //
  // catch를 "버킷 없음"으로만 좁힌다 - 자격 증명 오류나 네트워크 장애까지
  // 같이 삼켜서 CreateBucket 실패로 둔갑시키면, index.js 기동 시점에
  // 진짜 원인이 가려져 엉뚱한 곳을 디버깅하게 된다.
  async function ensureBucket() {
    try {
      await client.send(new HeadBucketCommand({ Bucket: bucket }));
    } catch (err) {
      const notFound = err.name === 'NotFound'
        || err.name === 'NoSuchBucket'
        || err.$metadata?.httpStatusCode === 404;
      if (!notFound) {
        throw err;
      }
      await client.send(new CreateBucketCommand({ Bucket: bucket }));
    }
  }

  async function upload(key, buffer) {
    await client.send(new PutObjectCommand({
      Bucket: bucket,
      Key: key,
      Body: buffer,
      ContentType: 'image/jpeg',
    }));
  }

  return { upload, ensureBucket };
}

module.exports = { buildKey, createStorage };
