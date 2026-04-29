package com.onlineshopping.user;

import com.onlineshopping.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping("/health")
    public ApiResponse<String> health() {
        return ApiResponse.ok("user-service is up");
    }
}
