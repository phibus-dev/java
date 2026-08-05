package dev.phibus.s3.web;

import dev.phibus.s3.settings.S3ProfileService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

@Controller
public class S3ProfileController {
    private final S3ProfileService service;

    public S3ProfileController(S3ProfileService service) {
        this.service = service;
    }

    @GetMapping("/settings/s3-profiles")
    public String page() {
        return "s3-profiles";
    }

    @GetMapping("/api/s3-profiles")
    @ResponseBody
    public List<S3ProfileService.Profile> list() {
        return service.list();
    }

    @GetMapping("/api/s3-profiles/{id}")
    @ResponseBody
    public S3ProfileService.Profile get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/api/s3-profiles")
    @ResponseStatus(HttpStatus.CREATED)
    @ResponseBody
    public S3ProfileService.Profile create(@RequestBody S3ProfileService.ProfileRequest request) {
        return service.create(request);
    }

    @PutMapping("/api/s3-profiles/{id}")
    @ResponseBody
    public S3ProfileService.Profile update(@PathVariable UUID id,
                                           @RequestBody S3ProfileService.ProfileRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/api/s3-profiles/{id}/clone")
    @ResponseBody
    public S3ProfileService.Profile cloneProfile(@PathVariable UUID id, @RequestBody CloneRequest request) {
        return service.cloneProfile(id, request == null ? null : request.name());
    }

    @PostMapping("/api/s3-profiles/{id}/default")
    @ResponseBody
    public S3ProfileService.Profile makeDefault(@PathVariable UUID id) {
        return service.makeDefault(id);
    }

    @DeleteMapping("/api/s3-profiles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    public record CloneRequest(String name) { }
}
