package dev.phibus.s3.web;

import dev.phibus.s3.schedule.TestScheduleService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {
    private final TestScheduleService schedules;

    public ScheduleController(TestScheduleService schedules) {
        this.schedules = schedules;
    }

    @GetMapping
    public List<TestScheduleService.ScheduleView> list() {
        return schedules.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TestScheduleService.ScheduleView create(@RequestBody TestScheduleService.CreateScheduleRequest request) {
        return schedules.create(request);
    }

    @PostMapping("/{id}/run")
    public TestScheduleService.ScheduleView runNow(@PathVariable UUID id) {
        return schedules.runNow(id);
    }

    @PatchMapping("/{id}/enabled")
    public TestScheduleService.ScheduleView setEnabled(@PathVariable UUID id, @RequestBody EnabledRequest request) {
        return schedules.setEnabled(id, request.enabled());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        schedules.delete(id);
    }

    public record EnabledRequest(boolean enabled) { }
}
