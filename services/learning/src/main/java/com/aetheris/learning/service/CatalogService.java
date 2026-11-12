package com.aetheris.learning.service;

import com.aetheris.learning.model.LearningModels;
import com.aetheris.learning.repository.LearningRepository;
import com.aetheris.learning.sandbox.SandboxService;
import com.aetheris.learning.support.Json;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 课程目录读模型：路径、模块、任务与学习状态。 */
@Service
public class CatalogService {

    private final LearningRepository repository;
    private final SandboxService sandboxService;

    public CatalogService(LearningRepository repository, SandboxService sandboxService) {
        this.repository = repository;
        this.sandboxService = sandboxService;
    }

    public List<LearningModels.PathSummary> listPaths(long uid) {
        List<LearningModels.PathSummary> result = new ArrayList<>();
        for (LearningRepository.PathRow path : repository.listPaths()) {
            Map<String, LearningRepository.AttemptRow> attempts = repository.bestAttempts(uid, path.code());
            int completed = (int) attempts.values().stream().filter(LearningRepository.AttemptRow::passed).count();
            int percent = path.labCount() == 0 ? 0 : Math.round(completed * 100f / path.labCount());
            result.add(new LearningModels.PathSummary(path.code(), path.title(), path.description(),
                    path.targetRole(), path.level(), path.version(), path.estimatedHours(), path.labCount(),
                    completed, percent));
        }
        return result;
    }

    public LearningModels.PathDetail pathDetail(String code, long uid) {
        LearningRepository.PathRow path = repository.findPath(code)
                .orElseThrow(() -> new IllegalArgumentException("学习路径不存在：" + code));
        Map<String, LearningRepository.AttemptRow> attempts = repository.bestAttempts(uid, code);
        List<LearningModels.LabRecord> labs = repository.listLabs(code);

        List<LearningModels.ModuleView> modules = new ArrayList<>();
        for (LearningRepository.ModuleRow module : repository.listModules(code)) {
            List<LearningModels.LabCard> cards = labs.stream()
                    .filter(lab -> lab.moduleCode().equals(module.code()))
                    .map(lab -> toCard(lab, attempts.get(lab.code())))
                    .toList();
            modules.add(new LearningModels.ModuleView(module.code(), module.title(), module.objective(),
                    module.level(), cards));
        }

        List<String> labCodes = labs.stream().map(LearningModels.LabRecord::code).toList();
        Map<String, List<String>> skillsByLab = skillsByLab();
        List<LearningModels.SkillView> skills = new ArrayList<>();
        for (LearningRepository.SkillRow skill : repository.listSkills(code)) {
            List<String> related = labCodes.stream()
                    .filter(lab -> skillsByLab.getOrDefault(lab, List.of()).contains(skill.code())).toList();
            int passed = (int) related.stream()
                    .filter(lab -> attempts.containsKey(lab) && attempts.get(lab).passed()).count();
            int best = related.stream().mapToInt(lab -> attempts.containsKey(lab) ? attempts.get(lab).bestScore() : 0)
                    .max().orElse(0);
            skills.add(new LearningModels.SkillView(skill.code(), skill.title(), skill.level(),
                    Json.readStringList(skill.knowledgePointsJson()), skill.outcome(), related.size(), passed, best));
        }
        return new LearningModels.PathDetail(path.code(), path.title(), path.description(), path.targetRole(),
                path.level(), path.estimatedHours(), modules, skills);
    }

    /** 路径下的端到端项目；进度按项目内任务的实际通过情况计算。 */
    public List<LearningModels.ProjectView> projects(long uid) {
        List<LearningModels.ProjectView> result = new ArrayList<>();
        for (LearningModels.ProjectView project : repository.listProjects()) {
            Map<String, LearningRepository.AttemptRow> attempts =
                    repository.bestAttemptsByProject(uid, project.code());
            int passed = (int) attempts.values().stream().filter(LearningRepository.AttemptRow::passed).count();
            result.add(new LearningModels.ProjectView(project.code(), project.title(), project.summary(),
                    project.level(), project.scenario(), project.deliverables(), project.labCount(), passed));
        }
        return result;
    }

    public LearningModels.ProjectDetail projectDetail(String code, long uid) {
        LearningModels.ProjectView project = repository.findProject(code)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在：" + code));
        Map<String, LearningRepository.AttemptRow> attempts = repository.bestAttemptsByProject(uid, code);
        List<LearningModels.LabCard> labs = repository.listProjectLabs(code).stream()
                .map(lab -> toCard(lab, attempts.get(lab.code())))
                .toList();
        return new LearningModels.ProjectDetail(project.code(), project.title(), project.summary(), project.level(),
                project.scenario(), project.deliverables(), labs);
    }

    public LearningModels.LabDetail labDetail(String code, long uid) {        LearningModels.LabRecord lab = repository.findLab(code)
                .orElseThrow(() -> new IllegalArgumentException("实验任务不存在：" + code));
        if (!"published".equals(lab.status())) {
            throw new IllegalArgumentException("实验任务尚未发布：" + code);
        }
        LearningModels.SandboxInfo sandbox = repository.findActiveSandbox(uid)
                .map(LearningModels.SandboxRow::toInfo).orElse(null);
        LearningRepository.AttemptRow attempt = repository.bestAttempts(uid, repository.pathOfLab(code)).get(code);
        return new LearningModels.LabDetail(lab.code(), lab.title(), lab.level(), lab.difficulty(), lab.taskType(),
                lab.summary(), lab.scenario(), lab.objective(), lab.steps(), lab.tableRefs(), lab.starterSql(),
                lab.passScore(), lab.estimatedMinutes(), lab.version(),
                repository.findAdminLab(code).map(LearningModels.LabAdminView::projectCode).orElse(null),
                lab.rubric(),
                sandbox == null ? null : sandbox.schemaName(),
                sandbox != null && sandbox.expiresAt().isAfter(java.time.Instant.now()),
                attempt == null ? null : attempt.bestScore(),
                attempt != null && attempt.passed(),
                attempt == null ? "not_started" : attempt.status());
    }

    public LearningModels.SandboxInfo startLab(String code, long uid, String username) {
        LearningModels.LabRecord lab = repository.findLab(code)
                .orElseThrow(() -> new IllegalArgumentException("实验任务不存在：" + code));
        if (!"published".equals(lab.status())) {
            throw new IllegalArgumentException("实验任务尚未发布：" + code);
        }
        return sandboxService.ensure(uid, username, code);
    }

    public LearningModels.SandboxInfo resetLab(String code, long uid, String username) {
        repository.findLab(code).orElseThrow(() -> new IllegalArgumentException("实验任务不存在：" + code));
        return sandboxService.reset(uid, username, code);
    }

    private Map<String, List<String>> skillsByLab() {
        return repository.allLabSkills();
    }

    private static LearningModels.LabCard toCard(LearningModels.LabRecord lab,
                                                 LearningRepository.AttemptRow attempt) {
        String status = attempt == null ? "not_started" : attempt.passed() ? "passed" : "in_progress";
        return new LearningModels.LabCard(lab.code(), lab.title(), lab.level(), lab.difficulty(), lab.summary(),
                lab.estimatedMinutes(), lab.orderNo(), status, attempt == null ? null : attempt.bestScore(),
                attempt != null && attempt.passed());
    }

    /** 实验任务只暴露 rubric 与提示，不暴露标准答案。 */
    public List<LearningModels.Step> stepsOf(LearningModels.LabRecord lab) {
        return lab.steps();
    }
}
