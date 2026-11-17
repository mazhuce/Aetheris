package com.aetheris.learning.web;

import com.aetheris.learning.model.LearningModels;
import com.aetheris.learning.repository.LearningRepository;
import com.aetheris.learning.security.CurrentUser;
import com.aetheris.learning.service.CatalogService;
import com.aetheris.learning.service.ProgressService;
import com.aetheris.learning.service.SubmissionService;
import com.aetheris.learning.service.TutorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 学习中心 API：目录、任务、提交、进度与提示。 */
@RestController
@RequestMapping("/api/learning")
public class LearningController {

    private final CatalogService catalogService;
    private final SubmissionService submissionService;
    private final ProgressService progressService;
    private final TutorService tutorService;
    private final LearningRepository repository;

    public LearningController(CatalogService catalogService, SubmissionService submissionService,
                              ProgressService progressService, TutorService tutorService,
                              LearningRepository repository) {
        this.catalogService = catalogService;
        this.submissionService = submissionService;
        this.progressService = progressService;
        this.tutorService = tutorService;
        this.repository = repository;
    }

    public record SubmitRequest(@NotBlank @Size(max = 20000) String sql, @Size(max = 2000) String notes) {
    }

    public record TutorRequest(@NotBlank @Size(max = 2000) String message) {
    }

    @GetMapping("/paths")
    public List<LearningModels.PathSummary> paths(@RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        return catalogService.listPaths(user.uid());
    }

    @GetMapping("/paths/{code}")
    public LearningModels.PathDetail path(@PathVariable String code,
                                          @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        return catalogService.pathDetail(code, user.uid());
    }

    @GetMapping("/labs/{code}")
    public LearningModels.LabDetail lab(@PathVariable String code,
                                        @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        return catalogService.labDetail(code, user.uid());
    }

    @GetMapping("/projects")
    public List<LearningModels.ProjectView> projects(@RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        return catalogService.projects(user.uid());
    }

    @GetMapping("/projects/{code}")
    public LearningModels.ProjectDetail project(@PathVariable String code,
                                                @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        return catalogService.projectDetail(code, user.uid());
    }

    @PostMapping("/labs/{code}/start")
    public LearningModels.SandboxInfo start(@PathVariable String code,
                                            @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        return catalogService.startLab(code, user.uid(), user.username());
    }

    @PostMapping("/labs/{code}/reset")
    public LearningModels.SandboxInfo reset(@PathVariable String code,
                                            @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        return catalogService.resetLab(code, user.uid(), user.username());
    }

    @PostMapping("/labs/{code}/submit")
    public LearningModels.SubmissionView submit(@PathVariable String code,
                                                @Valid @RequestBody SubmitRequest request,
                                                @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        return submissionService.submit(user.uid(), user.username(), code, request.sql(), request.notes());
    }

    @GetMapping("/submissions/{id}")
    public LearningModels.SubmissionView submission(@PathVariable long id,
                                                    @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        return submissionService.get(user.uid(), id);
    }

    @GetMapping("/labs/{code}/submissions")
    public List<LearningModels.SubmissionView> submissions(@PathVariable String code,
                                                           @RequestParam(defaultValue = "20") int limit,
                                                           @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        return submissionService.list(user.uid(), code, limit);
    }

    @PostMapping("/labs/{code}/hint")
    public LearningModels.HintView hint(@PathVariable String code,
                                        @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        return tutorService.hint(user.uid(), code);
    }

    @PostMapping("/labs/{code}/tutor")
    public LearningModels.TutorChatView tutor(@PathVariable String code, @Valid @RequestBody TutorRequest request,
                                              @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        return tutorService.chat(user.uid(), code, request.message());
    }

    @GetMapping("/labs/{code}/tutor/messages")
    public List<LearningModels.TutorMessageView> tutorHistory(@PathVariable String code,
                                                              @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        return tutorService.history(user.uid(), code);
    }

    public record LearningEventRequest(String event, String pathCode, String labCode, String payload) {
    }

    private static final java.util.Set<String> ALLOWED_EVENTS = java.util.Set.of(
            "expert_question_asked", "expert_fallback_used", "portfolio_exported",
            "lab_reset", "sandbox_expired_recovered");

    @PostMapping("/events")
    public org.springframework.http.ResponseEntity<Void> event(@RequestBody LearningEventRequest request,
                                                              @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        if (request.event() == null || !ALLOWED_EVENTS.contains(request.event())) {
            throw new IllegalArgumentException("不支持的事件");
        }
        if (request.payload() != null && request.payload().length() > 2048) {
            throw new IllegalArgumentException("事件内容过长");
        }
        if (request.payload() != null && !request.payload().isBlank()) {
            com.aetheris.learning.support.Json.readTree(request.payload());
        }
        repository.insertLearningEvent(user.uid(), request.event(),
                blankToNull(request.pathCode()), blankToNull(request.labCode()), request.payload());
        return org.springframework.http.ResponseEntity.accepted().build();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @GetMapping("/progress")
    public LearningModels.ProgressView progress(@RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        return progressService.progress(user.uid());
    }

    @GetMapping("/credentials")
    public List<LearningModels.CredentialView> credentials(@RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        return progressService.credentials(user.uid());
    }
}
