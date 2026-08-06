package dev.phibus.s3.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class UiRouteController {
    @GetMapping("/schedules")
    public String schedules() {
        return "schedules";
    }

    @GetMapping("/index.html")
    public String legacyIndex() {
        return "redirect:/tasks";
    }

    @GetMapping("/history.html")
    public String legacyHistory() {
        return "redirect:/history";
    }

    @GetMapping("/agents.html")
    public String legacyAgents() {
        return "redirect:/agents";
    }

    @GetMapping("/distributed-tests.html")
    public String legacyDistributedTests() {
        return "redirect:/distributed-tests";
    }

    @GetMapping("/schedules.html")
    public String legacySchedules() {
        return "redirect:/schedules";
    }
}
