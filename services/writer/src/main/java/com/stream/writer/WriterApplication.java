package com.stream.writer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@EntityScan(basePackages = "com.stream.shared.entity")
@SpringBootApplication
@RestController
public class WriterApplication {

    public static void main(String[] args) {
        SpringApplication.run(WriterApplication.class, args);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "writer");
    }
}
