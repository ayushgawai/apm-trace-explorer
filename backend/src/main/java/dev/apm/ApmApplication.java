package dev.apm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ApmApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApmApplication.class, args);
    }
}
