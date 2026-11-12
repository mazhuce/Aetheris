package com.aetheris.mcp.prompts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP Prompts：沉淀可复用的专家工作流模板，参数只做文本替换，不执行任何动作。 */
@Component
public class PromptRegistry {

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Prompt(String name, String title, String description, List<Argument> arguments, String template) {
    }

    private record Argument(String name, String description, boolean required) {
    }

    private final Map<String, Prompt> prompts = new LinkedHashMap<>();

    public PromptRegistry() {
        add(new Prompt("sql_review", "SQL 评审",
                "从只读安全、分区裁剪、去重、幂等和命名五个角度评审一段 SQL。",
                List.of(new Argument("sql", "待评审的 SQL", true)),
                "请评审下面这段 SQL，先指出安全与正确性问题，再给出可执行的修改建议：\n\n{{sql}}"));
        add(new Prompt("metric_definition", "指标口径梳理",
                "把一个业务指标拆成定义、公式、维度、时间窗口和数据源。",
                List.of(new Argument("metric", "指标名称或业务描述", true)),
                "请梳理指标「{{metric}}」的口径：业务定义、计算公式、统计维度、时间窗口、数据源与常见误用。"));
        add(new Prompt("data_quality_triage", "质量问题分诊",
                "对一张表的质量问题做现象、影响、根因、验证与处置建议。",
                List.of(new Argument("table", "表名", true)),
                "请对表 {{table}} 做一次质量问题分诊：列出可能的脏数据现象、业务影响、排查 SQL、根因假设与处置建议。"));
        add(new Prompt("learning_path_recommendation", "学习路径推荐",
                "根据当前进度推荐下一步任务，并说明原因。",
                List.of(new Argument("goal", "学习目标", false)),
                "请根据我的学习进度推荐下一步任务{{goal}}，说明推荐理由、预计投入和完成标准。"));
        add(new Prompt("lab_feedback", "实验反馈",
                "针对最近一次提交给出改进建议，不直接给出完整答案。",
                List.of(new Argument("labCode", "实验任务编码", true)),
                "请针对实验 {{labCode}} 的最近一次提交给出分级反馈：先指出问题位置，再给提示，不要直接给出完整可提交答案。"));
    }

    private void add(Prompt prompt) {
        prompts.put(prompt.name(), prompt);
    }

    public ArrayNode list() {
        ArrayNode result = JSON.createArrayNode();
        for (Prompt prompt : prompts.values()) {
            ObjectNode node = result.addObject();
            node.put("name", prompt.name());
            node.put("title", prompt.title());
            node.put("description", prompt.description());
            ArrayNode arguments = node.putArray("arguments");
            for (Argument argument : prompt.arguments()) {
                ObjectNode item = arguments.addObject();
                item.put("name", argument.name());
                item.put("description", argument.description());
                item.put("required", argument.required());
            }
        }
        return result;
    }

    public ObjectNode get(String name, JsonNode arguments) {
        Prompt prompt = prompts.get(name);
        if (prompt == null) throw new IllegalArgumentException("提示词不存在：" + name);
        String text = prompt.template();
        for (Argument argument : prompt.arguments()) {
            JsonNode value = arguments == null ? null : arguments.get(argument.name());
            String replacement = value == null || value.isNull() ? "" : value.asText();
            if (argument.required() && replacement.isBlank()) {
                throw new IllegalArgumentException("缺少提示词参数：" + argument.name());
            }
            text = text.replace("{{" + argument.name() + "}}", replacement);
        }
        ObjectNode result = JSON.createObjectNode();
        result.put("description", prompt.description());
        ArrayNode messages = result.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        ObjectNode content = message.putObject("content");
        content.put("type", "text");
        content.put("text", text);
        return result;
    }
}
