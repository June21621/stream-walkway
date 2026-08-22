package com.stream.writer.command;

import com.stream.shared.entity.Trail;
import com.stream.writer.exception.DuplicateTrailException;
import com.stream.writer.repository.TrailRepository;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class TrailCommandHandler {

    private static final Set<String> VALID_STATUSES = Set.of("active", "inactive");

    private final TrailRepository trailRepository;

    public TrailCommandHandler(TrailRepository trailRepository) {
        this.trailRepository = trailRepository;
    }

    // ─────────────────────────────────────────
    // CreateTrailCommand 처리 → 필수값 검증 → status 기본값/검증 →
    // WKT 문자열을 Point로 파싱 → PostgreSQL 저장.
    // UNIQUE(stream_id, camera_number) 위반은 DuplicateTrailException(409)으로 변환한다.
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

        WKTReader wktReader = new WKTReader(new GeometryFactory(new PrecisionModel(), Trail.SRID));
        Trail trail = new Trail();
        trail.setStreamId(command.streamId());
        trail.setCameraNumber(command.cameraNumber());
        trail.setLocation((Point) wktReader.read(command.location()));
        trail.setDirection(command.direction());
        trail.setStatus(status);

        try {
            return trailRepository.save(trail);
        } catch (DataIntegrityViolationException e) {
            String message = e.getMostSpecificCause().getMessage();
            if (message != null && message.contains("trails_stream_id_camera_number_key")) {
                throw new DuplicateTrailException(
                        "stream_id=" + command.streamId() + ", camera_number=" + command.cameraNumber() + " already exists");
            }
            throw e;
        }
    }
}
