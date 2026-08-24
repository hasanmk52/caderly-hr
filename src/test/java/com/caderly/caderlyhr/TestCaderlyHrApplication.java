package com.caderly.caderlyhr;

import org.springframework.boot.SpringApplication;

public class TestCaderlyHrApplication {

    public static void main(String[] args) {
        SpringApplication.from(CaderlyHrApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
