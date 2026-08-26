package com.stream.writer.command;

import com.stream.shared.entity.Trail;
import com.stream.writer.exception.DuplicateTrailException;
import com.stream.writer.repository.StreamRepository;
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

    @Mock
    private StreamRepository streamRepository;

    @InjectMocks
    private TrailCommandHandler handler;

    @Test
    @DisplayName("handle() - Command를 처리하면 WKT를 파싱해서 PostgreSQL에 Trail을 저장한다")
    void handle_savesTrailWithParsedGeometry() throws ParseException {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-001", "POINT(126.97 37.55)", "북", "active");
        Trail savedTrail = new Trail();
        savedTrail.setCameraNumber("CAM-001");
        given(streamRepository.existsById(1L)).willReturn(true);
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
        given(streamRepository.existsById(1L)).willReturn(true);
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
    @DisplayName("handle() - 저장 시 UNIQUE(stream_id, camera_number) 제약 위반이면 DuplicateTrailException을 던진다")
    void handle_throwsDuplicateTrailExceptionOnConstraintViolation() {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-001", "POINT(126.97 37.55)", "북", "active");
        given(streamRepository.existsById(1L)).willReturn(true);
        willThrow(new DataIntegrityViolationException("insert failed",
                new RuntimeException("duplicate key value violates unique constraint \"trails_stream_id_camera_number_key\"")))
                .given(trailRepository).save(any(Trail.class));

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                DuplicateTrailException.class, () -> handler.handle(command));
    }

    @Test
    @DisplayName("handle() - 우리가 아는 제약(UNIQUE/FK)이 아닌 무결성 위반은 DataIntegrityViolationException을 그대로 던진다")
    void handle_rethrowsDataIntegrityViolationExceptionOnOtherConstraintViolation() {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-001", "POINT(126.97 37.55)", "북", "active");
        given(streamRepository.existsById(1L)).willReturn(true);
        willThrow(new DataIntegrityViolationException("insert failed",
                new RuntimeException("new row for relation \"trails\" violates check constraint \"trails_status_check\"")))
                .given(trailRepository).save(any(Trail.class));

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                DataIntegrityViolationException.class, () -> handler.handle(command));
    }

    @Test
    @DisplayName("handle() - location이 null이면 IllegalArgumentException을 던진다")
    void handle_throwsIllegalArgumentExceptionOnNullLocation() {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-008", null, "북", "active");

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> handler.handle(command));
    }

    @Test
    @DisplayName("handle() - streamId가 null이면 IllegalArgumentException을 던진다")
    void handle_throwsIllegalArgumentExceptionOnNullStreamId() {
        // given
        CreateTrailCommand command = new CreateTrailCommand(null, "CAM-009", "POINT(126.97 37.55)", "북", "active");

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> handler.handle(command));
    }

    @Test
    @DisplayName("handle() - cameraNumber가 null이면 IllegalArgumentException을 던진다")
    void handle_throwsIllegalArgumentExceptionOnNullCameraNumber() {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, null, "POINT(126.97 37.55)", "북", "active");

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> handler.handle(command));
    }

    @Test
    @DisplayName("handle() - Point가 아닌 WKT(LINESTRING 등)를 넘기면 실제로 ClassCastException을 던진다")
    void handle_throwsClassCastExceptionOnNonPointGeometry() {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-010", "LINESTRING(0 0, 1 1)", "북", "active");

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                ClassCastException.class, () -> handler.handle(command));
    }

    @Test
    @DisplayName("handle() - 존재하지 않는 stream_id면 IllegalArgumentException을 던진다 (저장 시도 안 함)")
    void handle_throwsIllegalArgumentExceptionOnNonExistentStreamId() {
        // given
        CreateTrailCommand command = new CreateTrailCommand(999L, "CAM-100", "POINT(126.97 37.55)", "북", "active");
        given(streamRepository.existsById(999L)).willReturn(false);

        // when & then
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> handler.handle(command));
        assertThat(e.getMessage()).contains("999");
        verify(trailRepository, org.mockito.Mockito.never()).save(any(Trail.class));
    }

    @Test
    @DisplayName("handle() - 존재 확인 직후 하천이 삭제된 경우(FK 위반)도 IllegalArgumentException으로 변환한다")
    void handle_throwsIllegalArgumentExceptionOnForeignKeyViolation() {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-101", "POINT(126.97 37.55)", "북", "active");
        given(streamRepository.existsById(1L)).willReturn(true);
        willThrow(new DataIntegrityViolationException("insert failed",
                new RuntimeException("insert or update on table \"trails\" violates foreign key constraint \"trails_stream_id_fkey\"")))
                .given(trailRepository).save(any(Trail.class));

        // when & then
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> handler.handle(command));
        assertThat(e.getMessage()).contains("does not exist");
    }

    @Test
    @DisplayName("handle() - direction이 50자를 넘으면 IllegalArgumentException을 던진다 (저장 시도 안 함)")
    void handle_throwsIllegalArgumentExceptionOnOverLongDirection() {
        // given: trails.direction은 VARCHAR(50)이라 51자는 DB가 거부한다.
        // 검증이 없으면 저장까지 가서 DataIntegrityViolationException → 500이 된다.
        CreateTrailCommand command = new CreateTrailCommand(
                1L, "CAM-LEN", "POINT(126.97 37.55)", "북".repeat(51), "active");

        // when & then
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> handler.handle(command));
        // 메시지까지 확인해야 한다. 이 단언이 없으면 스텁하지 않은 existsById가
        // 기본값 false를 반환해 던지는 "stream_id=1 does not exist" 예외 때문에
        // 검증이 없어도 테스트가 통과해버린다.
        assertThat(e.getMessage()).contains("direction");
        assertThat(e.getMessage()).contains("50");
        verify(trailRepository, org.mockito.Mockito.never()).save(any(Trail.class));
    }

    @Test
    @DisplayName("handle() - direction이 정확히 50자면 정상 저장된다 (경계값)")
    void handle_acceptsDirectionAtExactLimit() throws ParseException {
        // given
        String atLimit = "북".repeat(50);
        CreateTrailCommand command = new CreateTrailCommand(
                1L, "CAM-LEN-OK", "POINT(126.97 37.55)", atLimit, "active");
        given(streamRepository.existsById(1L)).willReturn(true);
        given(trailRepository.save(any(Trail.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        Trail result = handler.handle(command);

        // then
        assertThat(result.getDirection()).hasSize(50);
    }

    @Test
    @DisplayName("handle() - location에 Z/M/ZM 좌표가 있으면 IllegalArgumentException을 던진다 (저장 시도 안 함)")
    void handle_throwsIllegalArgumentExceptionOnNon2dLocation() {
        // given: trails.location은 GEOMETRY(POINT,4326)이라 3D/4D 좌표를 컬럼이 거부한다.
        for (String wkt : new String[]{
                "POINT Z(126.97 37.55 1)",
                "POINT M(126.97 37.55 1)",
                "POINT ZM(126.97 37.55 1 9)"}) {
            CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-GEO", wkt, "북", "active");

            // when & then
            // 메시지까지 확인해야 한다. 이 단언이 없으면 스텁하지 않은 existsById가
            // 기본값 false를 반환해 던지는 "stream_id=1 does not exist" 예외 때문에
            // 검증이 없어도 테스트가 통과해버린다.
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(command))
                    .as(wkt)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2D");
        }
        verify(trailRepository, org.mockito.Mockito.never()).save(any(Trail.class));
    }

    @Test
    @DisplayName("handle() - location이 빈 지오메트리면 IllegalArgumentException을 던진다 (저장 시도 안 함)")
    void handle_throwsIllegalArgumentExceptionOnEmptyLocation() {
        // given: POINT EMPTY는 지금 컬럼까지 도달해 500이 된다.
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-EMPTY", "POINT EMPTY", "북", "active");

        // when & then
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        verify(trailRepository, org.mockito.Mockito.never()).save(any(Trail.class));
    }
}
