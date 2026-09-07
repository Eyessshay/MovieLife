package com.movielife;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MovieLifeApplication {

    public static void main(String[] args) {
        SpringApplication.run(MovieLifeApplication.class, args);
    }
}
