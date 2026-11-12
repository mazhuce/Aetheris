package com.aetheris.learning.service;

import com.aetheris.learning.config.LearningProperties;
import com.aetheris.learning.model.LearningModels;
import com.aetheris.learning.repository.LearningRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * M2 的分级提示先走规则引擎：基于任务定义、静态策略与最近一次判分结果，
 * 逐级给出方向、结构、拆解和自查建议，不直接输出可提交答案。
 * 阶段 2 接入 AI 导师后，规则提示作为兜底与事实约束。
 */
@Service
public class TutorService {

    private final LearningRepository repository;
    private final SubmissionService submissionService;
    private final AiTutorClient aiTutorClient;
    private final LearningProperties properties;

    public TutorService(LearningRepository repository, SubmissionService submissionService,
                        AiTutorClient aiTutorClient, LearningProperties properties) {
        this.repository = repository;
        this.submissionService = submissionService;
        this.aiTutorClient = aiTutorClient;
        this.properties = properties;
    }

    /** AI 导师对话：有模型走模型，无模型走规则兜底；对话全文落库，便于教师复盘。 */
    public LearningModels.TutorChatView chat(long uid, String labCode, String message) {
        LearningModels.LabRecord lab = repository.findLab(labCode)
                .orElseThrow(() -> new IllegalArgumentException("实验任务不存在：" + labCode));
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("请输入问题");
        }
        String question = message.strip();
        if (question.length() > 2000) {
            throw new IllegalArgumentException("单次提问不能超过 2000 字");
        }
        repository.insertTutorMessage(uid, labCode, "user", question, null);
        LearningModels.GradingView last = submissionService.lastResult(uid, labCode);
        String lastSql = repository.latestSubmissionSql(uid, labCode).orElse("");
        String model = aiTutorClient.enabled() ? properties.getAi().getModel() : "rule-based";
        boolean fallback = true;
        String reply;
        var answer = aiTutorClient.complete(systemPrompt(lab), userPrompt(question, lastSql, last));
        if (answer.isPresent()) {
            reply = answer.get().content();
            model = answer.get().model();
            fallback = false;
        } else {
            reply = ruleReply(lab, last, question);
        }
        repository.insertTutorMessage(uid, labCode, "assistant", reply, model);
        return new LearningModels.TutorChatView(reply, model, fallback,
                repository.listTutorMessages(uid, labCode, 20));
    }

    public List<LearningModels.TutorMessageView> history(long uid, String labCode) {
        return repository.listTutorMessages(uid, labCode, 30);
    }

    private String systemPrompt(LearningModels.LabRecord lab) {
        return """
                你是 Aetheris 数据工程实训平台的 AI 导师，面向零基础转行学员。
                必须遵守：
                1. 绝不输出可直接提交的完整 SQL，也不猜测标准答案；
                2. 先指出问题属于哪一类（安全、结构、口径、执行错误），再给 1-3 条可执行提示；
                3. 只使用任务中出现的表和字段，不编造元数据；
                4. 用中文，控制在 300 字以内，语气直接、具体。
                任务信息：
                标题：%s
                目标：%s
                当前步骤：%s
                评分维度：%s
                """.formatted(lab.title(), safe(lab.objective()),
                lab.steps().stream().map(LearningModels.Step::title).toList(),
                lab.rubric().stream().map(item -> item.name() + " " + item.weight() + "%").toList());
    }

    private String userPrompt(String question, String lastSql, LearningModels.GradingView last) {
        String sql = lastSql == null || lastSql.isBlank() ? "（尚未提交 SQL）"
                : (lastSql.length() > 1500 ? lastSql.substring(0, 1500) : lastSql);
        String grading = last == null ? "（尚无评分）" : last.summary();
        return "学员问题：" + question + "\n最近一次 SQL：\n" + sql + "\n最近一次评分：" + grading;
    }

    private String ruleReply(LearningModels.LabRecord lab, LearningModels.GradingView last, String question) {
        if (last == null) {
            return "还没有提交记录。先按任务卡写出第一版只读查询并提交，我会根据真实执行结果指出问题。"
                    + " 当前任务重点是：" + safe(lab.objective());
        }
        if (last.passed()) {
            return "最近一次提交已经通过（" + last.score() + " 分）。下一步可以尝试：把查询改写成更易维护的 CTE，"
                    + "或对比另一种去重/聚合写法，确认结果仍然一致。";
        }
        String failure = last.items().stream().filter(item -> !item.passed())
                .map(item -> "「" + item.name() + "」：" + item.detail())
                .findFirst()
                .orElse("最近一次评分未通过，但没有返回具体评分项。");
        String focus = question.contains("去重") ? "去重时先确认按什么键唯一、按什么时间保留最新。"
                : question.contains("口径") || question.contains("指标") ? "先把过滤条件写成注释，再逐条翻译成 WHERE。"
                : "先缩小到最小可运行查询，再逐步加聚合或窗口函数。";
        return "当前 AI 导师不可用，先用规则导师回答。" + failure + " " + focus
                + " 需要更细的提示可以点击“获取提示”。";
    }

    public LearningModels.HintView hint(long uid, String labCode) {
        LearningModels.LabRecord lab = repository.findLab(labCode)
                .orElseThrow(() -> new IllegalArgumentException("实验任务不存在：" + labCode));
        int used = repository.countHints(uid, labCode);
        int max = Math.max(1, properties.getTutor().getMaxHintsPerLab());
        if (used >= max) {
            return new LearningModels.HintView(max, "提示已全部使用",
                    "本任务的提示额度已用完。请重新阅读任务目标与验收标准，先提交一次获取具体失败原因。", true);
        }
        int level = used + 1;
        String title = switch (level) {
            case 1 -> "第 1 级：先对齐任务目标";
            case 2 -> "第 2 级：定位失败环节";
            case 3 -> "第 3 级：检查语句结构";
            case 4 -> "第 4 级：拆解执行步骤";
            default -> "第 " + level + " 级：自查清单";
        };
        String message = switch (level) {
            case 1 -> "本任务要求：" + safe(lab.objective())
                    + " 建议先只写 SELECT 与 WHERE，确认能筛出目标数据，再考虑聚合或窗口函数。";
            case 2 -> failureHint(uid, lab);
            case 3 -> structureHint(lab);
            case 4 -> "按这个顺序推进：" + stepsHint(lab);
            default -> "自查四点：过滤条件是否完整、去重是否保留最新一条、聚合维度是否正确、输出列是否与要求一致。"
                    + " 仍然不通过时，把最近一次错误信息对照任务目标逐条核对。";
        };
        repository.insertHint(uid, labCode, level, title);
        boolean last = level >= max;
        return new LearningModels.HintView(level, title, message, last);
    }

    private String failureHint(long uid, LearningModels.LabRecord lab) {
        LearningModels.GradingView result = submissionService.lastResult(uid, lab.code());
        if (result == null) {
            return "你还没有提交记录。先完成一版能运行的查询并提交，系统会基于真实执行结果给出针对性提示。";
        }
        return result.items().stream().filter(item -> !item.passed())
                .map(item -> "「" + item.name() + "」未通过：" + item.detail())
                .findFirst()
                .orElse("最近一次评分未通过，但未返回具体原因。请检查结果行数与列数是否与任务要求一致。");
    }

    private static String structureHint(LearningModels.LabRecord lab) {
        var policy = lab.staticPolicy();
        StringBuilder message = new StringBuilder("检查语句结构：");
        if (policy.path("disallowSelectStar").asBoolean(false)) {
            message.append("不要使用 SELECT *，显式列出字段；");
        }
        if (policy.has("requiredPatterns") && !policy.withArray("requiredPatterns").isEmpty()) {
            message.append("需要使用窗口函数完成去重（ROW_NUMBER + PARTITION BY）；");
        }
        if (policy.has("requiredColumns")) {
            List<String> columns = new java.util.ArrayList<>();
            policy.withArray("requiredColumns").forEach(node -> columns.add(node.asText()));
            if (!columns.isEmpty()) message.append("必须包含条件：").append(String.join("、", columns)).append("；");
        }
        if (message.length() == "检查语句结构：".length()) {
            message.append("先确认只读、单条语句，并显式列出输出字段。");
        }
        return message.toString();
    }

    private static String stepsHint(LearningModels.LabRecord lab) {
        if (lab.steps().isEmpty()) return "读需求 → 看表结构 → 写查询 → 自检。";
        return String.join(" → ", lab.steps().stream().map(LearningModels.Step::title).toList());
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "完成本任务定义的清洗与查询目标。" : value;
    }
}
