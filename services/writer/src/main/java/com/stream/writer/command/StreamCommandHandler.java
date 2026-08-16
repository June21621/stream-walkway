package com.stream.writer.command;

import com.stream.shared.entity.Stream;
import com.stream.writer.repository.StreamRepository;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.springframework.stereotype.Component;

@Component
public class StreamCommandHandler {

    private final StreamRepository streamRepository;
    private final WKTReader wktReader = new WKTReader(
            new org.locationtech.jts.geom.GeometryFactory(new org.locationtech.jts.geom.PrecisionModel(), Stream.SRID));

    public StreamCommandHandler(StreamRepository streamRepository) {
        this.streamRepository = streamRepository;
    }

    // ─────────────────────────────────────────
    // CreateStreamCommand 처리 → WKT 문자열을 LineString으로 파싱 → PostgreSQL 저장
    // ─────────────────────────────────────────
    public Stream handle(CreateStreamCommand command) throws ParseException {
        Stream stream = new Stream();
        stream.setName(command.name());
        stream.setLocation((LineString) wktReader.read(command.location()));
        return streamRepository.save(stream);
    }
}
