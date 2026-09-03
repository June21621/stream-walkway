// Next 16.3.3 정적 export 우회.
//
// 클라이언트 라우터(next/link 프리페치)는 RSC 페이로드를 점으로 이은 파일명으로
// 요청하는데, 익스포터는 그 점을 디렉터리 구분자로 써서 중첩 경로에 쓴다.
//
//   디스크: out/streams/1/__next.streams/$d$id/__PAGE__.txt
//   요청:   out/streams/1/__next.streams.$d$id.__PAGE__.txt   -> 404
//
// 페이지마다 헛요청이 나가고 클라이언트 이동이 전체 로드로 폴백된다.
// 요청하는 이름으로도 하나 복사해 둔다. 원본은 남겨서 어느 쪽이든 받게 한다.
//
// Next가 이 불일치를 고치면 이 스크립트와 postbuild 훅을 지우면 된다.
// 판별법: 빌드 후 out/ 에 `__next.*` 디렉터리가 더 이상 안 생기면 고쳐진 것이다.
import fs from 'node:fs';
import path from 'node:path';

const OUT = path.join(import.meta.dirname, '..', 'out');
let copied = 0;

/** `__next.*` 디렉터리 밑을 훑어 점으로 이은 이름으로 baseDir 에 복사한다. */
function flatten(baseDir, dir, prefix) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    const name = `${prefix}.${entry.name}`;
    if (entry.isDirectory()) flatten(baseDir, full, name);
    else {
      fs.copyFileSync(full, path.join(baseDir, name));
      copied += 1;
    }
  }
}

function scan(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    if (entry.name === '_next') continue; // 정적 에셋, 볼 필요 없다
    const full = path.join(dir, entry.name);
    if (entry.name.startsWith('__next.')) flatten(dir, full, entry.name);
    else scan(full);
  }
}

if (!fs.existsSync(OUT)) {
  console.error('[flatten-rsc] out/ 이 없다. next build 를 먼저 실행할 것.');
  process.exit(1);
}

scan(OUT);

if (copied === 0) {
  // 이 우회가 더 이상 필요 없거나, Next 가 이름 규칙을 또 바꿨다는 뜻이다.
  // 어느 쪽인지는 브라우저 네트워크 탭에서 `__PAGE__.txt` 요청이 200인지로 갈린다.
  console.log('[flatten-rsc] 평탄화할 페이로드가 없다. 이 스크립트가 아직 필요한지 확인할 것.');
} else {
  console.log(`[flatten-rsc] RSC 페이로드 ${copied}개를 평탄화한 이름으로 복사했다.`);
}
