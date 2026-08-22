-- ⚠️ 이 스크립트는 Postgres 데이터 볼륨이 비어있을 때만 실행됩니다.
-- 2026-08-22부로 created_at/updated_at 컬럼이 TIMESTAMP → TIMESTAMPTZ로 변경되었습니다.
-- 그 이전에 만든 볼륨을 그대로 재사용하면 이 스크립트가 다시 실행되지 않아 옛 TIMESTAMP 컬럼이 남고,
-- Instant 값이 세션 TimeZone 기준으로 조용히 시간이 밀려 저장됩니다(에러 없음, 데이터만 틀어짐).
-- 기존 볼륨이 있다면 `docker compose ... down -v` 로 지운 뒤 다시 up 하세요.

-- PostGIS extension 활성화 (GEOMETRY 타입 사용)
CREATE EXTENSION IF NOT EXISTS postgis;

-- streams 테이블
CREATE TABLE streams (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    location GEOMETRY(LINESTRING, 4326) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- trails 테이블
CREATE TABLE trails (
    id SERIAL PRIMARY KEY,
    stream_id INTEGER NOT NULL REFERENCES streams(id) ON DELETE CASCADE,
    camera_number VARCHAR NOT NULL,
    location GEOMETRY(POINT, 4326) NOT NULL,
    direction VARCHAR(50),
    status VARCHAR(20) DEFAULT 'active' CHECK (status IN ('active', 'inactive')),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (stream_id, camera_number)
);

-- captures 테이블
CREATE TABLE captures (
    id BIGSERIAL PRIMARY KEY,
    trail_id INTEGER NOT NULL REFERENCES trails(id) ON DELETE CASCADE,
    stream_id INTEGER NOT NULL REFERENCES streams(id) ON DELETE CASCADE,
    image_path VARCHAR(500) NOT NULL,
    road_status VARCHAR(10) CHECK (road_status IN ('양호', '주의', '불량')),
    confidence DECIMAL(3,2) DEFAULT 0.0,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- 업데이트 시간 자동 갱신 트리거
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_captures_updated_at 
    BEFORE UPDATE ON captures 
    FOR EACH ROW 
    EXECUTE FUNCTION update_updated_at_column();

-- 인덱스 생성 (성능 최적화)
CREATE INDEX idx_trails_stream_id ON trails(stream_id);
CREATE INDEX idx_captures_trail_id ON captures(trail_id);
CREATE INDEX idx_captures_stream_id ON captures(stream_id);
CREATE INDEX idx_captures_created_at ON captures(created_at);
