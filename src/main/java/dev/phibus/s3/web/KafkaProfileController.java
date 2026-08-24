package dev.phibus.s3.web;

import dev.phibus.s3.kafka.KafkaProfileService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/kafka/profiles")
public class KafkaProfileController {
    private final KafkaProfileService profiles;

    public KafkaProfileController(KafkaProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    public List<KafkaProfileService.Profile> list() { return profiles.list(); }

    @GetMapping("/{id}")
    public KafkaProfileService.Profile get(@PathVariable UUID id) { return profiles.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KafkaProfileService.Profile create(@RequestBody KafkaProfileService.ProfileRequest request) {
        return profiles.create(request);
    }

    @PutMapping("/{id}")
    public KafkaProfileService.Profile update(@PathVariable UUID id, @RequestBody KafkaProfileService.ProfileRequest request) {
        return profiles.update(id, request);
    }

    @PostMapping("/{id}/default")
    public KafkaProfileService.Profile makeDefault(@PathVariable UUID id) { return profiles.makeDefault(id); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { profiles.delete(id); }
}
