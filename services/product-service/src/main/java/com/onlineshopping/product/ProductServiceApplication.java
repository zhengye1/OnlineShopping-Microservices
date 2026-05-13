package com.onlineshopping.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})  // ← 加 exclude
@EnableScheduling
public class ProductServiceApplication {
    public static void main(String[] args){
        SpringApplication.run(ProductServiceApplication.class, args);
    }
}
