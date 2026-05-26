package com.sora.sora_agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = { DataSourceAutoConfiguration.class })
@MapperScan("com.sora.sora_agent.mapper")
public class SoraAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(SoraAgentApplication.class, args);
    }

}
