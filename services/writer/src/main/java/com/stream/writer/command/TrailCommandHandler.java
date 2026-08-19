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
    private final WKTReader wktReader = new WKTReader(
            new GeometryFactory(new PrecisionModel(), Trail.SRID));

    public TrailCommandHandler(TrailRepository trailRepository) {
        this.trailRepository = trailRepository;
    }

    // ─────────────────────────────────────────
    // CreateTrailCommand 처리 → status 기본값/검증 → WKT 문자열을 Point로 파싱 → PostgreSQL 저장
    // UNIQUE(stream_id, camera_number) 위반은 DuplicateTrailException(409)으로 변환한다.
    // ─────────────────────────────────────────
    public Trail handle(CreateTrailCommand command) throws ParseException {
        String status = command.status() == null ? "active" : command.status();
        if (!VALID_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Invalid status: " + status + " (must be 'active' or 'inactive')");
        }

        Trail trail = new Trail();
        trail.setStreamId(command.streamId());
        trail.setCameraNumber(command.cameraNumber());
        trail.setLocation((Point) wktReader.read(command.location()));
        trail.setDirection(command.direction());
        trail.setStatus(status);

        try {
            return trailRepository.save(trail);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateTrailException(
                    "stream_id=" + command.streamId() + ", camera_number=" + command.cameraNumber() + " already exists");
        }
    }
}
