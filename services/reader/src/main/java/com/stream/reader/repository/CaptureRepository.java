package com.stream.reader.repository;

import com.stream.shared.entity.Capture;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface CaptureRepository extends JpaRepository<Capture, Long> {

    // 필터 조합 네 가지(없음 / stream만 / trail만 / 둘 다)를 파생 쿼리 4개로
    // 만드는 대신 nullable 파라미터 하나로 처리한다.
    // 정렬과 개수 제한은 Pageable이 담당한다.
    @Query("SELECT c FROM Capture c "
            + "WHERE (:streamId IS NULL OR c.streamId = :streamId) "
            + "AND (:trailId IS NULL OR c.trailId = :trailId)")
    List<Capture> findFiltered(@Param("streamId") Integer streamId,
                               @Param("trailId") Integer trailId,
                               Pageable pageable);

    List<Capture> findByTrailId(Integer trailId);
    List<Capture> findByStreamId(Integer streamId);
    Optional<Capture> findFirstByTrailIdOrderByCreatedAtDesc(Integer trailId);
}
