package studio.performance.metadata;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Resources extends JpaRepository<Resource, UUID> {
    List<Resource> findByProjectIdAndKindOrderByCreatedAtDesc(UUID projectId, String kind);
    List<Resource> findByKind(String kind);
    Optional<Resource> findByProjectIdAndKindAndName(UUID projectId, String kind, String name);
    Optional<Resource> findByIdAndProjectIdAndKind(UUID id, UUID projectId, String kind);
}
