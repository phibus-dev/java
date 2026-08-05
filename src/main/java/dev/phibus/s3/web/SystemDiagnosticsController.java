package dev.phibus.s3.web;

import dev.phibus.s3.diagnostics.SystemDiagnosticsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class SystemDiagnosticsController {
    private final SystemDiagnosticsService diagnostics;

    public SystemDiagnosticsController(SystemDiagnosticsService diagnostics) {
        this.diagnostics = diagnostics;
    }

    @GetMapping("/system")
    public String system(Model model) {
        model.addAttribute("report", diagnostics.inspect());
        return "system";
    }

    @GetMapping("/api/system")
    @ResponseBody
    public SystemDiagnosticsService.SystemReport report() {
        return diagnostics.inspect();
    }
}
