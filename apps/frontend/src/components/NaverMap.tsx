'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { parseWkt } from '@/lib/wkt';

// 네이버 지도 키. 정적 export라 빌드 시 번들에 박히고 브라우저에서 보인다.
// 이게 이 API의 정상 사용법이고, 보호는 NCP 콘솔의 도메인 화이트리스트가 한다.
const CLIENT_ID = process.env.NEXT_PUBLIC_NAVER_MAP_CLIENT_ID;

// 스크립트 쿼리 파라미터 이름. 예전엔 ncpClientId 였고 NCP 이관 후 ncpKeyId 로 바뀌었다.
// 지도가 인증 오류로 안 뜨면 NCP 콘솔이 주는 예제 스니펫과 이 값을 대조할 것.
const KEY_PARAM = 'ncpKeyId';

const SCRIPT_ID = 'naver-maps-sdk';

export interface MapItem {
  id: number;
  label: string;
  /** WKT. LINESTRING이면 선, POINT면 마커로 그린다. */
  wkt: string;
  /** 클릭 시 이동할 경로. */
  href: string;
}

declare global {
  interface Window {
    naver?: any;
  }
}

function loadSdk(): Promise<void> {
  if (window.naver?.maps) return Promise.resolve();

  return new Promise((resolve, reject) => {
    const existing = document.getElementById(SCRIPT_ID) as HTMLScriptElement | null;
    if (existing) {
      existing.addEventListener('load', () => resolve());
      existing.addEventListener('error', () => reject(new Error('네이버 지도 SDK 로드 실패')));
      return;
    }

    const script = document.createElement('script');
    script.id = SCRIPT_ID;
    script.src = `https://oapi.map.naver.com/openapi/v3/maps.js?${KEY_PARAM}=${CLIENT_ID}`;
    script.async = true;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error('네이버 지도 SDK 로드 실패'));
    document.head.appendChild(script);
  });
}

export default function NaverMap({ items, height = '24rem' }: { items: MapItem[]; height?: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!CLIENT_ID) {
      setError('NEXT_PUBLIC_NAVER_MAP_CLIENT_ID 가 설정되지 않았습니다.');
      return;
    }

    let cancelled = false;

    loadSdk()
      .then(() => {
        if (cancelled || !ref.current) return;
        const { maps } = window.naver;

        const map = new maps.Map(ref.current, { zoom: 13 });
        const bounds = new maps.LatLngBounds();
        let drawn = 0;

        for (const item of items) {
          // 좌표가 하나라도 이상하면 그 항목만 건너뛴다. 지도 전체가 죽으면 안 된다.
          let coords;
          try {
            coords = parseWkt(item.wkt);
          } catch {
            continue;
          }
          if (coords.length === 0) continue;

          // WKT는 (경도 위도), 네이버는 (위도, 경도) — 순서가 뒤집힌다.
          const latLngs = coords.map(([lng, lat]) => new maps.LatLng(lat, lng));
          latLngs.forEach((ll: unknown) => bounds.extend(ll));
          drawn += 1;

          const shape =
            latLngs.length === 1
              ? new maps.Marker({ map, position: latLngs[0], title: item.label })
              : new maps.Polyline({ map, path: latLngs, strokeWeight: 5, strokeOpacity: 0.8 });

          maps.Event.addListener(shape, 'click', () => router.push(item.href));
        }

        if (drawn > 0) map.fitBounds(bounds);
      })
      .catch((e: Error) => {
        if (!cancelled) setError(e.message);
      });

    return () => {
      cancelled = true;
    };
  }, [items, router]);

  if (error) {
    return (
      <p className="empty" style={{ padding: '1rem', border: '1px dashed currentColor', borderRadius: 6 }}>
        지도를 불러오지 못했습니다 — {error}
      </p>
    );
  }

  return <div ref={ref} style={{ height, borderRadius: 6, overflow: 'hidden' }} />;
}
