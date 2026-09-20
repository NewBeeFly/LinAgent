package com.javaagent.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

/**
 * 组合根：组件扫描覆盖 agent 模块（AgentFacade/AgentFactory 等）。
 * Spring Data JDBC 自动配置默认只扫描本类所在包，而仓储接口定义在
 * agent 模块的 persistence 包 —— 在此显式启用，AgentFacade 的仓储依赖才能装配。
 */
@SpringBootApplication(scanBasePackages = "com.javaagent")
@EnableJdbcRepositories(basePackages = "com.javaagent.agent.persistence")
public class WebApplication {
    public static void main(String[] args) {
        SpringApplication.run(WebApplication.class, args);
    }
}
