package com.topo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TopoApplication {
    public static void main(String[] args) {
        SpringApplication.run(TopoApplication.class, args);
    }
}
