package studio.performance.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class Store {
    public final Projects projects;
    public final Resources resources;

    public Store(Projects projects, Resources resources) {
        this.projects = projects;
        this.resources = resources;
    }

    public Project project(UUID id) {
        return projects.findById(id).orElseThrow(Store::missing);
    }

    public Resource get(UUID project, String kind, UUID id) {
        return resources.findByIdAndProjectIdAndKind(id, project, kind).orElseThrow(Store::missing);
    }

    public List<Resource> list(UUID project, String kind) {
        project(project);
        return resources.findByProjectIdAndKindOrderByCreatedAtDesc(project, kind);
    }

    public Resource create(UUID project, String kind, String name, JsonNode data) {
        Resource resource = new Resource();
        resource.projectId = project;
        resource.kind = kind;
        resource.name = name;
        resource.data = data;
        return resource;
    }

    public Resource save(Resource value) {
        value.updatedAt = Instant.now();
        return resources.saveAndFlush(value);
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found.");
    }
}
