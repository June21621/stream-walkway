package com.stream.writer.command;

import com.stream.shared.entity.Trail;
import com.stream.writer.exception.DuplicateTrailException;
import com.stream.writer.repository.TrailRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.io.ParseException;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("Writer - TrailCommandHandler 테스트")
class TrailCommandHandlerTest {

    @Mock
    private TrailRepository trailRepository;

    @InjectMocks
    private TrailCommandHandler handler;

    @Test
    @DisplayName("handle() - Command를 처리하면 WKT를 파싱해서 PostgreSQL에 Trail을 저장한다")
    void handle_savesTrailWithParsedGeometry() throws ParseException {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-001", "POINT(126.97 37.55)", "북", "active");
        Trail savedTrail = new Trail();
        savedTrail.setCameraNumber("CAM-001");
        given(trailRepository.save(any(Trail.class))).willReturn(savedTrail);

        // when
        Trail result = handler.handle(command);

        // then
        ArgumentCaptor<Trail> captor = ArgumentCaptor.forClass(Trail.class);
        verify(trailRepository).save(captor.capture());

        Trail toSave = captor.getValue();
        assertThat(toSave.getStreamId()).isEqualTo(1L);
        assertThat(toSave.getCameraNumber()).isEqualTo("CAM-001");
        assertThat(toSave.getLocation().toText()).isEqualTo("POINT (126.97 37.55)");
        assertThat(toSave.getLocation().getSRID()).isEqualTo(4326);
        assertThat(toSave.getDirection()).isEqualTo("북");
        assertThat(toSave.getStatus()).isEqualTo("active");
        assertThat(result).isEqualTo(savedTrail);
    }

    @Test
    @DisplayName("handle() - status가 null이면 'active'로 기본값을 채운다")
    void handle_defaultsNullStatusToActive() throws ParseException {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-002", "POINT(126.97 37.55)", "북", null);
        given(trailRepository.save(any(Trail.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        Trail result = handler.handle(command);

        // then
        assertThat(result.getStatus()).isEqualTo("active");
    }

    @Test
    @DisplayName("handle() - status가 active/inactive가 아니면 IllegalArgumentException을 던진다")
    void handle_throwsIllegalArgumentExceptionOnInvalidStatus() {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-003", "POINT(126.97 37.55)", "북", "unknown");

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> handler.handle(command));
    }

    @Test
    @DisplayName("handle() - 잘못된 WKT 문자열이면 ParseException을 던진다")
    void handle_throwsParseExceptionOnInvalidWkt() {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-004", "NOT-A-VALID-WKT", "북", "active");

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(ParseException.class, () -> handler.handle(command));
    }

    @Test
    @DisplayName("handle() - 저장 시 UNIQUE 제약 위반이면 DuplicateTrailException을 던진다")
    void handle_throwsDuplicateTrailExceptionOnConstraintViolation() {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-001", "POINT(126.97 37.55)", "북", "active");
        willThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
                .given(trailRepository).save(any(Trail.class));

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                DuplicateTrailException.class, () -> handler.handle(command));
    }
}
