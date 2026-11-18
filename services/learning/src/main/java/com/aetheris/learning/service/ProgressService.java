package com.aetheris.learning.service;

import com.aetheris.learning.model.LearningModels;
import com.aetheris.learning.repository.LearningRepository;
import com.aetheris.learning.support.Json;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 学习进度与凭证：技能雷达来自真实提交，不单独维护一份“看起来很好看”的统计。 */
@Service
public class ProgressService {

    private final LearningRepository repository;

    public ProgressService(LearningRepository repository) {
        this.repository = repository;
    }

    public LearningModels.ProgressView progress(long uid) {
        Map<String, LearningRepository.ProgressRow> rows = new java.util.HashMap<>();
        for (LearningRepository.ProgressRow row : repository.listProgress(uid)) {
            rows.put(row.skillCode(), row);
        }
        List<LearningModels.SkillView> skills = new ArrayList<>();
        List<LearningRepository.PathRow> paths = repository.listPaths();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Set<String> passedLabs = new java.util.HashSet<>();
        int total = 0;
        for (LearningRepository.PathRow path : paths) {
            var labs = repository.listLabs(path.code());
            var attempts = repository.bestAttempts(uid, path.code());
            Map<String, List<String>> skillsByLab = repository.allLabSkills();
            total += labs.size();
            attempts.values().stream().filter(LearningRepository.AttemptRow::passed)
                    .forEach(attempt -> passedLabs.add(attempt.labCode()));
            for (LearningRepository.SkillRow skill : repository.listSkills(path.code())) {
                if (!seen.add(skill.code())) continue;
                List<String> related = labs.stream()
                        .filter(lab -> skillsByLab.getOrDefault(lab.code(), List.of()).contains(skill.code()))
                        .map(LearningModels.LabRecord::code).toList();
                LearningRepository.ProgressRow progress = rows.get(skill.code());
                int passed = (int) related.stream()
                        .filter(lab -> attempts.containsKey(lab) && attempts.get(lab).passed()).count();
                skills.add(new LearningModels.SkillView(skill.code(), skill.title(), skill.level(),
                        Json.readStringList(skill.knowledgePointsJson()), skill.outcome(), related.size(), passed,
                        progress == null ? 0 : progress.bestScore()));
            }
        }
        int completed = passedLabs.size();
        int percent = total == 0 ? 0 : Math.min(100, Math.round(completed * 100f / total));
        int average = (int) Math.round(skills.stream().filter(skill -> skill.bestScore() > 0)
                .mapToInt(LearningModels.SkillView::bestScore).average().orElse(0));
        return new LearningModels.ProgressView(completed, total, percent, average, skills, repository.listCredentials(uid));
    }

    public void recordAttempt(long uid, String labCode, int score, boolean passed) {
        for (String skill : repository.skillsOfLab(labCode)) {
            repository.upsertProgress(uid, skill, passed ? "passed" : "in_progress", score, labCode);
        }
    }

    /** 路径内全部任务通过后发放一次结业凭证；同一路径重复调用不会重复发证。 */
    public void issueIfEligible(long uid, String pathCode, String pathTitle) {
        if (repository.hasCredential(uid, "PATH_COMPLETION", pathCode)) return;
        var attempts = repository.bestAttempts(uid, pathCode);
        List<LearningModels.LabRecord> labs = repository.listLabs(pathCode);
        if (labs.isEmpty()) return;
        boolean allPassed = labs.stream().allMatch(lab -> attempts.containsKey(lab.code()) && attempts.get(lab.code()).passed());
        if (!allPassed) return;
        int average = (int) Math.round(attempts.values().stream().mapToInt(LearningRepository.AttemptRow::bestScore)
                .average().orElse(0));
        repository.insertCredential(uid, "PATH_COMPLETION", pathTitle + " 结业证明",
                Json.write(Map.of("pathCode", pathCode, "labs", labs.size(), "averageScore", average,
                        "issuedAt", java.time.Instant.now().toString())));
    }

    public List<LearningModels.CredentialView> credentials(long uid) {
        return repository.listCredentials(uid);
    }
}
