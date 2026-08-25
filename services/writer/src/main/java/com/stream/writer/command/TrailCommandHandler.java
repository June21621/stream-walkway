package com.stream.writer.command;

import com.stream.shared.entity.Trail;
import com.stream.writer.exception.DuplicateTrailException;
import com.stream.writer.repository.StreamRepository;
import com.stream.writer.repository.TrailRepository;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
public class TrailCommandHandler {

    private static final Set<String> VALID_STATUSES = Set.of("active", "inactive");

    // trails.direction 컬럼이 VARCHAR(50)이다 (infra/scripts/init-db.sql,
    // services/writer/src/test/resources/schema.sql).
    // 길이 비교는 String.length()(UTF-16 코드 단위)로 한다.
    // H2가 VARCHAR 길이를 코드 단위로 세는 것은 실측으로 확인했다.
    // PostgreSQL은 문자(코드포인트) 단위로 센다고 알려져 있으나 직접 확인하지는 않았다.
    // 다만 length() >= codePointCount()이므로 이 기준은 두 해석 모두의 상한이고,
    // 어느 쪽이 맞든 검증을 통과한 값은 컬럼에 들어간다. (VARCHAR를 바이트 길이로
    // 세는 엔진에는 이 논리가 성립하지 않지만 이 프로젝트는 H2와 PostgreSQL만 쓴다.)
    // 대가는 astral 문자(이모지 등)에 대해 PostgreSQL보다 엄격할 수 있다는 것뿐이다.
    private static final int MAX_DIRECTION_LENGTH = 50;

    private final TrailRepository trailRepository;
    private final StreamRepository streamRepository;

    public TrailCommandHandler(TrailRepository trailRepository, StreamRepository streamRepository) {
        this.trailRepository = trailRepository;
        this.streamRepository = streamRepository;
    }

    // ─────────────────────────────────────────
    // CreateTrailCommand 처리 → 필수값 검증 → status 기본값/검증 →
    // WKT 문자열을 Point로 파싱 → stream_id 존재 확인 → PostgreSQL 저장.
    //
    // 존재 확인은 값싼 검증들을 모두 통과한 뒤 save() 직전에 한다
    // (형식이 틀린 요청 때문에 불필요한 DB 조회를 하지 않기 위해).
    //
    // UNIQUE(stream_id, camera_number) 위반은 DuplicateTrailException(409)으로,
    // FK(trails_stream_id_fkey) 위반은 IllegalArgumentException(400)으로 변환한다.
    //
    // 제약 이름 비교는 대소문자를 구분하지 않는다. SQL 식별자가 원래 대소문자를
    // 가리지 않을 뿐 아니라, 실제로 두 엔진이 다른 형태로 출력하기 때문이다.
    // PostgreSQL: ...violates foreign key constraint "trails_stream_id_fkey" (소문자)
    // H2:         "TRAILS_STREAM_ID_FKEY: PUBLIC.TRAILS FOREIGN KEY(...)"    (대문자)
    // UNIQUE 검사를 FK보다 먼저 하는데, 두 엔진 모두에서 안전하다 —
    // FK 위반 메시지에는 TRAILS_STREAM_ID_CAMERA_NUMBER_KEY가 들어가지 않는다.
    //
    // FK catch가 실제로 정확성을 보장하는 쪽이고, 위의 존재 확인은 흔한 경우를
    // DB 에러 문자열 파싱 없이 걸러내는 위생 계층이다.
    //
    // 주의: 존재 확인과 저장 사이에 하천이 삭제되는 경쟁을 "해결"하지는 못한다.
    // 경쟁에서 진 경우(DELETE가 먼저 커밋)를 500 대신 깔끔한 400으로 바꿔줄 뿐이다.
    // 반대 순서(INSERT가 먼저 커밋)면 streams의 ON DELETE CASCADE가 방금 만든
    // trail을 지우므로, 클라이언트는 이미 사라진 행에 대해 201을 받는다.
    // Postgres가 INSERT 중 streams 행에 FOR KEY SHARE 락을 잡기 때문에 오히려
    // 이쪽이 더 흔한데, 이건 이 핸들러에서 해결할 수 있는 문제가 아니다.
    //
    // WKTReader는 스레드 안전하지 않으므로(JTS 문서 명시) 싱글턴 빈의 필드로 공유하지 않고
    // 매 호출마다 새로 만든다 (생성 비용은 미미함).
    // ─────────────────────────────────────────
    public Trail handle(CreateTrailCommand command) throws ParseException {
        if (command.streamId() == null) {
            throw new IllegalArgumentException("streamId is required");
        }
        if (command.cameraNumber() == null || command.cameraNumber().isBlank()) {
            throw new IllegalArgumentException("cameraNumber is required");
        }
        if (command.location() == null || command.location().isBlank()) {
            throw new IllegalArgumentException("location is required (WKT)");
        }

        String status = command.status() == null ? "active" : command.status();
        if (!VALID_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Invalid status: " + status + " (must be 'active' or 'inactive')");
        }
        if (command.direction() != null && command.direction().length() > MAX_DIRECTION_LENGTH) {
            throw new IllegalArgumentException(
                    "direction must be " + MAX_DIRECTION_LENGTH + " characters or fewer");
        }

        WKTReader wktReader = new WKTReader(new GeometryFactory(new PrecisionModel(), Trail.SRID));
        Trail trail = new Trail();
        trail.setStreamId(command.streamId());
        trail.setCameraNumber(command.cameraNumber());
        trail.setLocation((Point) wktReader.read(command.location()));
        trail.setDirection(command.direction());
        trail.setStatus(status);

        if (!streamRepository.existsById(command.streamId())) {
            throw new IllegalArgumentException("stream_id=" + command.streamId() + " does not exist");
        }

        try {
            return trailRepository.save(trail);
        } catch (DataIntegrityViolationException e) {
            String message = e.getMostSpecificCause().getMessage();
            String upper = message == null ? "" : message.toUpperCase(Locale.ROOT);
            if (upper.contains("TRAILS_STREAM_ID_CAMERA_NUMBER_KEY")) {
                throw new DuplicateTrailException(
                        "stream_id=" + command.streamId() + ", camera_number=" + command.cameraNumber() + " already exists");
            }
            if (upper.contains("TRAILS_STREAM_ID_FKEY")) {
                throw new IllegalArgumentException("stream_id=" + command.streamId() + " does not exist");
            }
            throw e;
        }
    }
}
