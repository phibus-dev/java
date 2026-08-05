package dev.phibus.s3.web;

import dev.phibus.s3.history.PerformanceBaselineStore;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class BaselineController {
    private final PerformanceBaselineStore store;

    public BaselineController(PerformanceBaselineStore store) {
        this.store = store;
    }

    @GetMapping("/baselines")
    public String page(Model model) {
        model.addAttribute("baselines", store.list());
        return "baselines";
    }

    @GetMapping("/api/baselines")
    @ResponseBody
    public List<PerformanceBaselineStore.BaselineRow> list() {
        return store.list();
    }

    @PostMapping("/api/history/{id}/baseline")
    @ResponseBody
    public PerformanceBaselineStore.RegressionReport mark(@PathVariable UUID id,
            @RequestParam(required = false) String name) {
        return store.markBaseline(id, name);
    }

    @DeleteMapping("/api/history/{id}/baseline")
    @ResponseBody
    public void remove(@PathVariable UUID id) {
        store.removeBaseline(id);
    }

    @GetMapping("/api/history/{id}/regression")
    @ResponseBody
    public PerformanceBaselineStore.RegressionReport compare(@PathVariable UUID id) {
        return store.compare(id);
    }
}
