package studio.performance.metadata;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface Projects extends JpaRepository<Project, UUID> {}
