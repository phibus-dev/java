package dev.phibus.s3.web;

import dev.phibus.s3.clickhouse.ClickHouseProfileService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/settings/clickhouse-profiles")
public class ClickHouseProfileController {
    private final ClickHouseProfileService profiles;

    public ClickHouseProfileController(ClickHouseProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("profiles", profiles.list());
        return "clickhouse-profiles";
    }

    @GetMapping("/api")
    @ResponseBody
    public List<ClickHouseProfileService.Profile> list() {
        return profiles.list();
    }

    @PostMapping("/api")
    @ResponseBody
    public ClickHouseProfileService.Profile create(@RequestBody ClickHouseProfileService.ProfileRequest request) {
        return profiles.create(request);
    }

    @PutMapping("/api/{id}")
    @ResponseBody
    public ClickHouseProfileService.Profile update(@PathVariable UUID id,
                                                    @RequestBody ClickHouseProfileService.ProfileRequest request) {
        return profiles.update(id, request);
    }

    @DeleteMapping("/api/{id}")
    @ResponseBody
    public void delete(@PathVariable UUID id) {
        profiles.delete(id);
    }

    @PostMapping("/api/{id}/default")
    @ResponseBody
    public ClickHouseProfileService.Profile makeDefault(@PathVariable UUID id) {
        return profiles.makeDefault(id);
    }

    @PostMapping("/api/test")
    @ResponseBody
    public ClickHouseProfileService.NodeDiscovery test(@RequestBody ClickHouseProfileService.ProfileRequest request) {
        return profiles.test(request);
    }

    @PostMapping("/api/{id}/discover")
    @ResponseBody
    public ClickHouseProfileService.DiscoveryResult discover(@PathVariable UUID id) {
        return profiles.discover(id);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public void badRequest(IllegalArgumentException error) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
    }
}
