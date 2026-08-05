package dev.phibus.s3.web;

import dev.phibus.s3.history.TestHistoryStore;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class HistoryController {
    private final TestHistoryStore historyStore;

    public HistoryController(TestHistoryStore historyStore) {
        this.historyStore = historyStore;
    }

    @GetMapping("/history")
    public String history(@RequestParam(defaultValue = "100") int limit, Model model) {
        model.addAttribute("runs", historyStore.list(limit));
        model.addAttribute("limit", limit);
        return "history";
    }

    @GetMapping("/api/history")
    @ResponseBody
    public List<TestHistoryStore.HistoryRow> list(@RequestParam(defaultValue = "100") int limit) {
        return historyStore.list(limit);
    }

    @GetMapping("/api/history/{id}")
    @ResponseBody
    public TestHistoryStore.HistoryRow get(@PathVariable UUID id) {
        TestHistoryStore.HistoryRow row = historyStore.get(id);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "History item not found");
        return row;
    }
}
