package com.fakturacnysoftver.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class UctoApplication {

    public static void main(String[] args) {
        SpringApplication.run(UctoApplication.class, args);
    }
}
