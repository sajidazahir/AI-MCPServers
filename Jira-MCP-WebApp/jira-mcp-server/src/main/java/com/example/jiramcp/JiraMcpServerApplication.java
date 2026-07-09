package com.example.jiramcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JiraMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(JiraMcpServerApplication.class, args);
    }
}
