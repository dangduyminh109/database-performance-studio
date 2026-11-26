package studio.performance.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "projects")
public class Project {
    @Id public UUID id = UUID.randomUUID();
    @Column(nullable = false, length = 120) public String name;
    @Column(nullable = false, columnDefinition = "text") public String description;
    @JdbcTypeCode(SqlTypes.JSON) public JsonNode configuration;
    public Instant createdAt = Instant.now();
    public Instant updatedAt = createdAt;
    @Version public long version;

    public Project() {}
}
