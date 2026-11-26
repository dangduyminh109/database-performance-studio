package studio.performance.metadata;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "resources")
public class Resource {
    @Id public UUID id = UUID.randomUUID();
    public UUID projectId;
    @Column(length = 30) public String kind;
    @Column(length = 120) public String name;
    @JdbcTypeCode(SqlTypes.JSON) public JsonNode data;
    @JsonIgnore @Column(columnDefinition = "text") public String secret;
    public Instant createdAt = Instant.now();
    public Instant updatedAt = createdAt;
    @Version public Long version;

    public Resource() {}
}
