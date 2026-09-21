package com.linagent.agent.persistence.support;

import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;

import java.util.List;

/**
 * PostgreSQL JSONB 列（turn.usage / message.arguments）的读取转换配置。
 *
 * <p>读取：JSONB 列经 ResultSet.getObject 返回 PGobject，Spring Data JDBC 无内置的
 * PGobject → String 转换，需在此显式注册（读转换按源类型匹配，仅影响 JSONB 列，
 * 不会波及普通 String 列）。
 *
 * <p>写入：String 直写 JSONB 依赖 JDBC URL 的 stringtype=unspecified 参数
 * （PostgreSQL 驱动约定，测试容器与 web 数据源均需携带）。
 */
@Configuration(proxyBeanMethods = false)
public class JdbcConverterConfig {

    @Bean
    public JdbcCustomConversions jdbcCustomConversions() {
        return new JdbcCustomConversions(List.of(new PgObjectToStringReadConverter()));
    }

    @ReadingConverter
    static class PgObjectToStringReadConverter implements Converter<PGobject, String> {

        @Override
        public String convert(PGobject source) {
            return source.getValue();
        }
    }
}
