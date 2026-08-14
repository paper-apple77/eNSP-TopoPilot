package com.topo.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger / OpenAPI 配置
 *
 * 启动后访问 http://localhost:8080/swagger-ui.html 查看接口文档。
 * 右上角 Authorize 按钮粘贴登录返回的 token，即可在线调试需要认证的接口。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI topoPilotOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("TopoPilot API")
                        .description("AI 网络拓扑助手接口文档 — 拓扑识别、Telnet 设备连接、AI 配网对话")
                        .version("1.0.0"))
                // JWT Bearer 认证方案：调试时点 Authorize 填入 token
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录接口返回的 token（不用加 Bearer 前缀）")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
