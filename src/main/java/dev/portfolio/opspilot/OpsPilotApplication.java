package dev.portfolio.opspilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OpsPilotApplication {
    public static void main(String[] args) { SpringApplication.run(OpsPilotApplication.class, args); }
}
