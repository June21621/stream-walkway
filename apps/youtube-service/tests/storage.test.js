const { HeadBucketCommand, CreateBucketCommand, PutBucketPolicyCommand } = require('@aws-sdk/client-s3');
const { buildKey, createStorage } = require('../src/storage');

// 진짜 MinIO 없이 send()만 흉내내는 가짜 클라이언트.
function fakeClient(sendImpl) {
  return { send: jest.fn(sendImpl) };
}

describe('storage.js - buildKey', () => {

  test('captures/{streamId}/{trailId}/{ISO}.jpg 형식으로 만든다', () => {
    const key = buildKey(1, 2, new Date('2026-08-27T10:15:00.000Z'));
    expect(key).toBe('captures/1/2/2026-08-27T10-15-00Z.jpg');
  });

  test('콜론을 대시로 바꾼다 - URL과 파일명에서 안전하도록', () => {
    const key = buildKey(1, 1, new Date('2026-01-02T03:04:05.000Z'));
    expect(key).not.toContain(':');
    expect(key).toBe('captures/1/1/2026-01-02T03-04-05Z.jpg');
  });

  test('밀리초를 버린다 - 15분 간격 표본이라 초 단위로 충분하다', () => {
    const key = buildKey(1, 1, new Date('2026-01-02T03:04:05.678Z'));
    expect(key).toBe('captures/1/1/2026-01-02T03-04-05Z.jpg');
  });

  test('streamId와 trailId가 경로에 그대로 들어간다', () => {
    expect(buildKey(42, 7, new Date('2026-01-01T00:00:00.000Z')))
      .toBe('captures/42/7/2026-01-01T00-00-00Z.jpg');
  });
});

describe('storage.js - ensureBucket', () => {
  const env = { MINIO_BUCKET: 'captures' };

  test('버킷이 이미 있으면 CreateBucketCommand를 보내지 않는다', async () => {
    const client = fakeClient(async () => ({}));
    const { ensureBucket } = createStorage(env, client);

    await ensureBucket();

    const sentCreate = client.send.mock.calls
      .some(([cmd]) => cmd instanceof CreateBucketCommand);
    expect(sentCreate).toBe(false);
  });

  test('버킷이 없으면(NotFound) 새로 만든다', async () => {
    const client = fakeClient(async (cmd) => {
      if (cmd instanceof HeadBucketCommand) {
        const err = new Error('not found');
        err.name = 'NotFound';
        throw err;
      }
      return {};
    });
    const { ensureBucket } = createStorage(env, client);

    await ensureBucket();

    const sentCreate = client.send.mock.calls
      .some(([cmd]) => cmd instanceof CreateBucketCommand);
    expect(sentCreate).toBe(true);
  });

  test('버킷을 익명 읽기(s3:GetObject)로 여는 정책을 적용한다', async () => {
    const client = fakeClient(async () => ({}));
    const { ensureBucket } = createStorage(env, client);

    await ensureBucket();

    const policyCall = client.send.mock.calls
      .find(([cmd]) => cmd instanceof PutBucketPolicyCommand);
    expect(policyCall).toBeDefined();
    const [cmd] = policyCall;
    expect(cmd.input.Bucket).toBe('captures');
    const policy = JSON.parse(cmd.input.Policy);
    expect(policy.Statement[0]).toMatchObject({
      Effect: 'Allow',
      Action: ['s3:GetObject'],
      Resource: ['arn:aws:s3:::captures/*'],
    });
  });

  test('버킷 없음이 아닌 다른 오류(자격 증명 등)는 그대로 전파하고 버킷을 만들지 않는다', async () => {
    const client = fakeClient(async (cmd) => {
      if (cmd instanceof HeadBucketCommand) {
        const err = new Error('credentials missing');
        err.name = 'CredentialsProviderError';
        throw err;
      }
      return {};
    });
    const { ensureBucket } = createStorage(env, client);

    await expect(ensureBucket()).rejects.toThrow('credentials missing');

    const sentCreate = client.send.mock.calls
      .some(([cmd]) => cmd instanceof CreateBucketCommand);
    expect(sentCreate).toBe(false);
  });
});
