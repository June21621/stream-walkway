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
}
