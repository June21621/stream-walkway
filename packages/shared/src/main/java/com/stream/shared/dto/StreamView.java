package com.stream.shared.dto;

import com.stream.shared.entity.Stream;
import org.locationtech.jts.io.WKTWriter;

import java.time.LocalDateTime;

public record StreamView(
        Long id,
        String name,
        String location,
        LocalDateTime createdAt
) {
    private static final WKTWriter WKT_WRITER = new WKTWriter();

    public static StreamView from(Stream stream) {
        String wkt = WKT_WRITER.write(stream.getLocation()).replaceFirst("\\s+\\(", "(");
        return new StreamView(
                stream.getId(),
                stream.getName(),
                wkt,
                stream.getCreatedAt()
        );
    }
}
