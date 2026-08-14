package com.zzp.rag.mcp.bridge;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * MCP JsonSchema → LC4j JsonObjectSchema 转换。
 * 本阶段覆盖 string/integer/number/boolean（演示工具只用 string）；
 * 数组/枚举留到真实业务系统接入时按需扩展（纯函数，扩展点单一）。
 */
@Slf4j
public final class McpSchemaConverter {

    private McpSchemaConverter() {}

    public static JsonObjectSchema convert(McpSchema.JsonSchema schema) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        if (schema == null || schema.properties() == null) {
            return builder.build();
        }
        schema.properties().forEach((name, raw) -> {
            if (!(raw instanceof Map<?, ?> prop)) {
                return;
            }
            Object typeObj = prop.get("type");
            String type = typeObj != null ? typeObj.toString() : "string";
            Object descObj = prop.get("description");
            String desc = descObj != null ? descObj.toString() : null;
            switch (type) {
                case "string" -> builder.addStringProperty(name, desc);
                case "integer" -> builder.addIntegerProperty(name, desc);
                case "number" -> builder.addNumberProperty(name, desc);
                case "boolean" -> builder.addBooleanProperty(name, desc);
                default -> {
                    log.warn("[MCP] 参数类型 {} 暂不支持，按 string 处理（参数 {}）", type, name);
                    builder.addStringProperty(name, desc);
                }
            }
        });
        if (schema.required() != null && !schema.required().isEmpty()) {
            builder.required(schema.required());
        }
        return builder.build();
    }
}
