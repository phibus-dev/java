package dev.phibus.s3.web;

import dev.phibus.s3.quality.BenchmarkScenarioCatalog;
import dev.phibus.s3.quality.QualityGatePolicyCatalog;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class QualityGatePageController {
    private final QualityGatePolicyCatalog policies;
    private final BenchmarkScenarioCatalog scenarios;

    public QualityGatePageController(QualityGatePolicyCatalog policies,
                                     BenchmarkScenarioCatalog scenarios) {
        this.policies = policies;
        this.scenarios = scenarios;
    }

    @GetMapping("/quality-gates")
    public String page(Model model) {
        model.addAttribute("policies", policies.list());
        model.addAttribute("scenarios", scenarios.list(null));
        return "quality-gates";
    }
}
