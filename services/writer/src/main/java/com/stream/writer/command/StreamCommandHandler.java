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

    // streams.name 컬럼이 VARCHAR(255)다 (infra/scripts/init-db.sql,
    // services/writer/src/test/resources/schema.sql).
    // 길이 비교는 String.length()(UTF-16 코드 단위)로 한다.
    // H2가 VARCHAR 길이를 코드 단위로 세는 것은 실측으로 확인했다.
    // PostgreSQL은 문자(코드포인트) 단위로 센다고 알려져 있으나 직접 확인하지는 않았다.
    // 다만 length() >= codePointCount()이므로 이 기준은 두 해석 모두의 상한이고,
    // 어느 쪽이 맞든 검증을 통과한 값은 컬럼에 들어간다. (VARCHAR를 바이트 길이로
    // 세는 엔진에는 이 논리가 성립하지 않지만 이 프로젝트는 H2와 PostgreSQL만 쓴다.)
    // 대가는 astral 문자(이모지 등)에 대해 PostgreSQL보다 엄격할 수 있다는 것뿐이다.
    private static final int MAX_NAME_LENGTH = 255;

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
        if (command.name().length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "name must be " + MAX_NAME_LENGTH + " characters or fewer");
        }
        if (command.location() == null || command.location().isBlank()) {
            throw new IllegalArgumentException("location is required (WKT)");
        }

        WKTReader wktReader = new WKTReader(new GeometryFactory(new PrecisionModel(), Stream.SRID));
        // 캐스트를 먼저 하고 검증한다. 그래야 LineString이 아닌 WKT는 지금처럼
        // ClassCastException 메시지로 400이 나가고 기존 동작이 바뀌지 않는다.
        LineString location = (LineString) wktReader.read(command.location());
        GeometryValidator.validateLocation(location);

        Stream stream = new Stream();
        stream.setName(command.name());
        stream.setLocation(location);
        return streamRepository.save(stream);
    }
}
