package com.helyx.helyxhr;

import org.springframework.boot.SpringApplication;

public class TestHelyxhrApplication {

  public static void main(String[] args) {
    SpringApplication.from(HelyxhrApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}
