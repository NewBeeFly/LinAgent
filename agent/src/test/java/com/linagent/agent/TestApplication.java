package com.linagent.agent;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * agent 模块测试专用 Spring Boot 配置。
 * agent 模块暂无主应用类（启动入口由 web 模块承载），而 @SpringBootTest 需要在测试包向上
 * 搜索到 @SpringBootConfiguration 才能拉起上下文（探针测试依赖自动装配的 OpenAiChatModel）。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class TestApplication {
}
