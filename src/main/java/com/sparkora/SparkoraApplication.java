package com.sparkora;

import io.github.cdimascio.dotenv.Dotenv;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Sparkora 后端启动入口。
 * 包结构 {@code com.sparkora}；MyBatis-Plus 扫描 mapper 包。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.sparkora.mapper")
public class SparkoraApplication {

    public static void main(String[] args) {
        // 在 Spring 启动前加载 .env，注入 System property，
        // 保证 application.yml 的 ${SPARKORA_DB_HOST} 等占位符在数据源初始化前已就绪。
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(e -> {
            if (System.getProperty(e.getKey()) == null && System.getenv(e.getKey()) == null) {
                System.setProperty(e.getKey(), e.getValue());
            }
        });
        SpringApplication.run(SparkoraApplication.class, args);
    }
}
