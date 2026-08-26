package com.stream.writer.command;

import com.stream.shared.entity.Stream;
import com.stream.writer.repository.StreamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.io.ParseException;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("Writer - StreamCommandHandler 테스트")
class StreamCommandHandlerTest {

    @Mock
    private StreamRepository streamRepository;

    @InjectMocks
    private StreamCommandHandler handler;

    @Test
    @DisplayName("handle() - Command를 처리하면 WKT를 파싱해서 PostgreSQL에 Stream을 저장한다")
    void handle_savesStreamWithParsedGeometry() throws ParseException {
        // given
        CreateStreamCommand command = new CreateStreamCommand("한강 산책로", "LINESTRING(126.97 37.55, 126.98 37.56)");
        Stream savedStream = new Stream();
        savedStream.setName("한강 산책로");
        given(streamRepository.save(any(Stream.class))).willReturn(savedStream);

        // when
        Stream result = handler.handle(command);

        // then
        ArgumentCaptor<Stream> captor = ArgumentCaptor.forClass(Stream.class);
        verify(streamRepository).save(captor.capture());

        Stream toSave = captor.getValue();
        assertThat(toSave.getName()).isEqualTo("한강 산책로");
        assertThat(toSave.getLocation().toText()).isEqualTo("LINESTRING (126.97 37.55, 126.98 37.56)");
        assertThat(toSave.getLocation().getSRID()).isEqualTo(4326);
        assertThat(result).isEqualTo(savedStream);
    }

    @Test
    @DisplayName("handle() - 잘못된 WKT 문자열이면 ParseException을 던진다")
    void handle_throwsParseExceptionOnInvalidWkt() {
        // given
        CreateStreamCommand command = new CreateStreamCommand("잘못된 스트림", "NOT-A-VALID-WKT");

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(ParseException.class, () -> handler.handle(command));
    }

    @Test
    @DisplayName("handle() - location이 null이면 IllegalArgumentException을 던진다")
    void handle_throwsIllegalArgumentExceptionOnNullLocation() {
        // given
        CreateStreamCommand command = new CreateStreamCommand("한강 산책로", null);

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> handler.handle(command));
    }

    @Test
    @DisplayName("handle() - name이 null이면 IllegalArgumentException을 던진다")
    void handle_throwsIllegalArgumentExceptionOnNullName() {
        // given
        CreateStreamCommand command = new CreateStreamCommand(null, "LINESTRING(126.97 37.55, 126.98 37.56)");

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> handler.handle(command));
    }

    @Test
    @DisplayName("handle() - LineString이 아닌 WKT(POINT 등)를 넘기면 실제로 ClassCastException을 던진다")
    void handle_throwsClassCastExceptionOnNonLineStringGeometry() {
        // given
        CreateStreamCommand command = new CreateStreamCommand("점 좌표", "POINT(1 2)");

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                ClassCastException.class, () -> handler.handle(command));
    }

    @Test
    @DisplayName("handle() - name이 255자를 넘으면 IllegalArgumentException을 던진다 (저장 시도 안 함)")
    void handle_throwsIllegalArgumentExceptionOnOverLongName() {
        // given: streams.name은 VARCHAR(255)라 256자는 DB가 거부한다.
        // 검증이 없으면 저장까지 가서 DataIntegrityViolationException → 500이 된다.
        CreateStreamCommand command =
                new CreateStreamCommand("가".repeat(256), "LINESTRING(126.97 37.55, 126.98 37.56)");

        // when & then
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> handler.handle(command));
        assertThat(e.getMessage()).contains("name");
        assertThat(e.getMessage()).contains("255");
        verify(streamRepository, org.mockito.Mockito.never()).save(any(Stream.class));
    }

    @Test
    @DisplayName("handle() - name이 정확히 255자면 정상 저장된다 (경계값)")
    void handle_acceptsNameAtExactLimit() throws ParseException {
        // given
        String atLimit = "가".repeat(255);
        CreateStreamCommand command =
                new CreateStreamCommand(atLimit, "LINESTRING(126.97 37.55, 126.98 37.56)");
        given(streamRepository.save(any(Stream.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        Stream result = handler.handle(command);

        // then
        assertThat(result.getName()).hasSize(255);
    }

    @Test
    @DisplayName("handle() - location에 Z/M/ZM 좌표가 있으면 IllegalArgumentException을 던진다 (저장 시도 안 함)")
    void handle_throwsIllegalArgumentExceptionOnNon2dLocation() {
        // given: streams.location은 GEOMETRY(LINESTRING,4326)이라 3D/4D 좌표를 컬럼이 거부한다.
        // 검증이 없으면 저장까지 가서 DataIntegrityViolationException(22018) → 500이 된다.
        for (String wkt : new String[]{
                "LINESTRING Z(126.97 37.55 1, 126.98 37.56 2)",
                "LINESTRING M(126.97 37.55 1, 126.98 37.56 2)",
                "LINESTRING ZM(126.97 37.55 1 9, 126.98 37.56 2 9)"}) {
            CreateStreamCommand command = new CreateStreamCommand("한강 산책로", wkt);

            // when & then
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(command))
                    .as(wkt)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2D");
        }
        verify(streamRepository, org.mockito.Mockito.never()).save(any(Stream.class));
    }

    @Test
    @DisplayName("handle() - location이 빈 지오메트리면 IllegalArgumentException을 던진다 (저장 시도 안 함)")
    void handle_throwsIllegalArgumentExceptionOnEmptyLocation() {
        // given: LINESTRING EMPTY는 지금 201로 저장된다. Trail의 POINT EMPTY(500)와
        // 동작이 갈리는 비대칭이라 양쪽 다 400으로 맞춘다.
        CreateStreamCommand command = new CreateStreamCommand("한강 산책로", "LINESTRING EMPTY");

        // when & then
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        verify(streamRepository, org.mockito.Mockito.never()).save(any(Stream.class));
    }

    @Test
    @DisplayName("handle() - location 좌표가 WGS84 범위를 벗어나면 IllegalArgumentException을 던진다 (저장 시도 안 함)")
    void handle_throwsIllegalArgumentExceptionOnOutOfBoundsLocation() {
        // given: SRID 4326인데 위도 999는 존재할 수 없다. 컬럼은 이걸 막지 않아
        // 검증이 없으면 201로 저장된다.
        CreateStreamCommand command = new CreateStreamCommand("한강 산책로", "LINESTRING(999 999, 1000 1000)");

        // when & then
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WGS84 bounds");
        verify(streamRepository, org.mockito.Mockito.never()).save(any(Stream.class));
    }
}
