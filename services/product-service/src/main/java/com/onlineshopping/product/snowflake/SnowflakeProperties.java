package com.onlineshopping.product.snowflake;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.snowflake")
public record SnowflakeProperties(long datacenterId,   // 0-31
                                  long workerId        // 0-31)
){ }
