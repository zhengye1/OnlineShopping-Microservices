package com.onlineshopping.order.snowflake;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SnowflakeProperties.class)
@Slf4j
public class SnowflakeConfig {
    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(SnowflakeProperties props) {
        var gen = new SnowflakeIdGenerator(props.datacenterId(), props.workerId());
        log.info("Order-service SnowflakeIdGenerator initialized: dc={}, worker={}",
                props.datacenterId(), props.workerId());
        return gen;
    }
}
