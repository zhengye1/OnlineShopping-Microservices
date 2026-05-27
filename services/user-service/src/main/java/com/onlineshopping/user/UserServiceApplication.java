package com.onlineshopping.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.onlineshopping.user",
        "com.onlineshopping.common.web"
})
@EnableScheduling                          // Activates @Scheduled — required by OutboxPoller (L4.6).
public class UserServiceApplication {
    public static void main(String[] args){
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
