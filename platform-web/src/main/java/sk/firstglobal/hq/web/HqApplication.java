package sk.firstglobal.hq.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HqApplication {

    public static void main(String[] args) {
        SpringApplication.run(HqApplication.class, args);
    }
}
