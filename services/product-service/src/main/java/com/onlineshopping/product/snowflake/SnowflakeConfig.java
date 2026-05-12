package com.onlineshopping.product.snowflake;

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
        log.info("SnowflakeIdGenerator initialized: dc={}, worker={}, sample id={}",
                props.datacenterId(), props.workerId(), gen.nextId());
        return gen;
    }
}
