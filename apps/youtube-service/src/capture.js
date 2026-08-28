const { spawn } = require('child_process');

const DEFAULT_TIMEOUT_MS = 30000;

// ffmpeg를 실행해 stdout을 버퍼로 모은다. 디스크를 거치지 않는다.
// 임시 파일이 없으므로 정리할 것도, 볼륨도 필요 없다.
function runFfmpeg(args, { timeoutMs = DEFAULT_TIMEOUT_MS } = {}) {
  return new Promise((resolve, reject) => {
    const proc = spawn('ffmpeg', args);
    const chunks = [];
    let stderr = '';
    let settled = false;

    // 타임아웃이 없으면 응답 없는 소스를 문 프로세스가 영원히 남아
    // 좀비가 쌓이고 컨테이너가 서서히 죽는다.
    const timer = setTimeout(() => {
      settled = true;
      proc.kill('SIGKILL');
      reject(new Error(`capture failed: ffmpeg timed out after ${timeoutMs}ms`));
    }, timeoutMs);

    proc.stdout.on('data', (c) => chunks.push(c));
    proc.stderr.on('data', (c) => { stderr += c.toString(); });

    proc.on('error', (err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(new Error(`capture failed: ${err.message}`));
    });

    proc.on('close', (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);

      if (code !== 0) {
        // ffmpeg는 진행 로그를 stderr에 잔뜩 쏟으므로 마지막 줄만 남긴다.
        const lastLine = stderr.trim().split('\n').pop() || `exit code ${code}`;
        return reject(new Error(`capture failed: ${lastLine}`));
      }

      const buf = Buffer.concat(chunks);
      if (buf.length === 0) {
        return reject(new Error('capture failed: empty output'));
      }
      resolve(buf);
    });
  });
}

// 공통 출력 인자: 프레임 1장을 MJPEG로 stdout에 낸다.
const OUTPUT_ARGS = ['-frames:v', '1', '-f', 'image2', '-vcodec', 'mjpeg', '-'];

// ffmpeg가 즉석에서 만드는 테스트 패턴. 네트워크도 저작권도 개입하지 않고
// 코드 경로는 실제와 같다. 자동화 테스트와 CI에서 쓴다.
function captureFromTestPattern(opts) {
  return runFfmpeg(['-f', 'lavfi', '-i', 'testsrc=size=1280x720:rate=1', ...OUTPUT_ARGS], opts);
}

// 직접 촬영한 영상 파일. -ss를 입력 앞에 두면 디코딩을 건너뛰어 훨씬 빠르다.
function captureFromFile(path, offsetSec = 0, opts) {
  return runFfmpeg(['-ss', String(offsetSec), '-i', path, ...OUTPUT_ARGS], opts);
}

// 공공 CCTV 등의 HLS/RTSP 스트림. ffmpeg가 직접 읽으므로 새 의존성이 없다.
function captureFromHls(url, opts) {
  return runFfmpeg(['-i', url, ...OUTPUT_ARGS], opts);
}

// CAPTURE_SOURCE로 어댑터를 고른다. YouTube 어댑터는 없다 —
// 이용약관 위반이라 설계 단계에서 만들지 않기로 했다.
function createCapture(env = process.env) {
  const source = env.CAPTURE_SOURCE || 'testsrc';

  if (source === 'testsrc') return (_url) => captureFromTestPattern();
  if (source === 'file') return (_url) => captureFromFile(env.CAPTURE_FILE_PATH, Number(env.CAPTURE_FILE_OFFSET_SEC || 0));
  if (source === 'hls') return (url) => captureFromHls(url);

  throw new Error(`unknown CAPTURE_SOURCE: ${source} (supported: testsrc, file, hls)`);
}

module.exports = { captureFromTestPattern, captureFromFile, captureFromHls, createCapture };
