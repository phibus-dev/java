package dev.phibus.s3.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DistributedPagesController {
    @GetMapping("/agents")
    public String agents() { return "agents"; }

    @GetMapping("/distributed-tests")
    public String distributedTests() { return "distributed-tests"; }
}
