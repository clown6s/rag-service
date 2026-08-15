package com.zzp.rag.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.mcp.demo.DemoMcpData;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServlet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 内置演示 MCP 服务（阶段3 试点 + 业务系统出 MCP server 的进程内集成模板）。
 * <p>
 * 与 rag-service 同进程同端口：HttpServletSseServerTransportProvider 注册为
 * /mcp/demo/sse（GET，SSE 流）与 /mcp/demo/message（POST，JSON-RPC），
 * 不经 DispatcherServlet，生命周期由内嵌 Tomcat 管理。
 * <p>
 * 真实业务系统接 MCP 时复制本类：把三个工具 handler 的 mock 调用换成业务查询即可。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "rag.mcp.demo.enabled", havingValue = "true", matchIfMissing = true)
public class DemoMcpServerConfig {

    private static final String SSE_ENDPOINT = "/mcp/demo/sse";
    private static final String MESSAGE_ENDPOINT = "/mcp/demo/message";

    @Bean
    public HttpServletSseServerTransportProvider demoMcpTransportProvider(ObjectMapper objectMapper) {
        // 注意 SDK 构造器参数顺序：message 端点在前、sse 端点在后（传反则内部路径校验 404）
        return new HttpServletSseServerTransportProvider(
                objectMapper, MESSAGE_ENDPOINT, SSE_ENDPOINT);
    }

    @Bean
    public io.modelcontextprotocol.server.McpSyncServer demoMcpServer(
            @org.springframework.beans.factory.annotation.Qualifier("demoMcpTransportProvider")
            HttpServletSseServerTransportProvider provider) {
        McpServer.SyncSpecification spec = McpServer.sync(provider)
                .serverInfo("rag-demo-mcp", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tool(policyTool(), (exchange, args) ->
                        McpSchema.CallToolResult.builder()
                                .addTextContent(DemoMcpData.searchPolicy(
                                        String.valueOf(args.get("keyword"))))
                                .build())
                .tool(employeeLookupTool(), (exchange, args) ->
                        McpSchema.CallToolResult.builder()
                                .addTextContent(DemoMcpData.lookupEmployee(
                                        String.valueOf(args.get("name"))))
                                .build())
                .tool(employeeDirectoryTool(), (exchange, args) ->
                        McpSchema.CallToolResult.builder()
                                .addTextContent(DemoMcpData.directory(
                                        args.get("department") != null
                                                ? String.valueOf(args.get("department")) : null))
                                .build());
        io.modelcontextprotocol.server.McpSyncServer server = spec.build();
        log.info("[MCP] 演示服务已装配: {} / {}", SSE_ENDPOINT, MESSAGE_ENDPOINT);
        return server;
    }

    /** 将 provider servlet 注册进内嵌 Tomcat（不经 DispatcherServlet）。
     *  用 ServletContextInitializer 直连注册（ServletRegistrationBean 在该场景未生效，历史坑） */
    @Bean
    public org.springframework.boot.web.servlet.ServletContextInitializer demoMcpServletInitializer(
            @org.springframework.beans.factory.annotation.Qualifier("demoMcpTransportProvider")
            HttpServletSseServerTransportProvider provider) {
        return servletContext -> {
            jakarta.servlet.ServletRegistration.Dynamic registration =
                    servletContext.addServlet("demoMcpServlet", provider);
            registration.addMapping(SSE_ENDPOINT, MESSAGE_ENDPOINT);
            registration.setAsyncSupported(true);   // SSE 长连接必须异步支持
            registration.setLoadOnStartup(1);
            log.info("[MCP] servlet 已注册: {} {}", SSE_ENDPOINT, MESSAGE_ENDPOINT);
        };
    }

    // ==================== 工具定义（描述含触发条件，LLM 决策依据） ====================

    private McpSchema.Tool policyTool() {
        return new McpSchema.Tool("company_policy_search",
                "查询公司内部制度政策（年假、请假、考勤、报销、差旅、加班等）。用户询问公司制度规定、福利政策时调用，返回制度名称、条款编号与完整条文。",
                new McpSchema.JsonSchema("object",
                        Map.of("keyword", Map.of("type", "string",
                                "description", "制度关键词（如：年假、考勤、报销）")),
                        List.of("keyword"), null, null, null));
    }

    private McpSchema.Tool employeeLookupTool() {
        return new McpSchema.Tool("employee_lookup",
                "按姓名查询员工信息（部门、职位、工号、入职日期）。用户问某位同事在哪个部门、什么职位时调用。",
                new McpSchema.JsonSchema("object",
                        Map.of("name", Map.of("type", "string",
                                "description", "员工姓名")),
                        List.of("name"), null, null, null));
    }

    private McpSchema.Tool employeeDirectoryTool() {
        return new McpSchema.Tool("employee_directory",
                "查询部门员工名单。用户问某个部门都有哪些人时调用，不传部门参数则返回全部员工。",
                new McpSchema.JsonSchema("object",
                        Map.of("department", Map.of("type", "string",
                                "description", "部门名称（可选）")),
                        List.of(), null, null, null));
    }
}
