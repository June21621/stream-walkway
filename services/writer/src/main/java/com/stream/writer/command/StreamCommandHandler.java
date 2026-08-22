package com.stream.writer.command;

import com.stream.shared.entity.Stream;
import com.stream.writer.repository.StreamRepository;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.springframework.stereotype.Component;

@Component
public class StreamCommandHandler {

    private final StreamRepository streamRepository;

    public StreamCommandHandler(StreamRepository streamRepository) {
        this.streamRepository = streamRepository;
    }

    // ─────────────────────────────────────────
    // CreateStreamCommand 처리 → WKT 문자열을 LineString으로 파싱 → PostgreSQL 저장
    // WKTReader는 스레드 안전하지 않으므로(JTS 문서 명시) 싱글턴 빈의 필드로 공유하지 않고
    // 매 호출마다 새로 만든다 (생성 비용은 미미함).
    // ─────────────────────────────────────────
    public Stream handle(CreateStreamCommand command) throws ParseException {
        if (command.name() == null || command.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (command.location() == null || command.location().isBlank()) {
            throw new IllegalArgumentException("location is required (WKT)");
        }

        WKTReader wktReader = new WKTReader(new GeometryFactory(new PrecisionModel(), Stream.SRID));
        Stream stream = new Stream();
        stream.setName(command.name());
        stream.setLocation((LineString) wktReader.read(command.location()));
        return streamRepository.save(stream);
    }
}
