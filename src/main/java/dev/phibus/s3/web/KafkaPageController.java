package dev.phibus.s3.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class KafkaPageController {
    @GetMapping("/kafka")
    public String kafka() { return "kafka"; }

    @GetMapping("/kafka/m2")
    public String kafkaM2() { return "kafka-m2"; }

    @GetMapping("/settings/kafka-profiles")
    public String profiles() { return "kafka-profiles"; }
}
