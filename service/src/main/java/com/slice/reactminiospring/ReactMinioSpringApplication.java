package com.slice.reactminiospring;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.slice.reactminiospring.mapper")
@EnableScheduling
public class ReactMinioSpringApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReactMinioSpringApplication.class, args);
    }

}
