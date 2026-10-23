package com.aetheris.auth.controller;

import com.aetheris.auth.service.AuthUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Legacy controller retained for removal; unreachable behind WorkloadProofFilter. */
@RestController
@RequestMapping("/internal/sessions")
public class InternalSessionController {
    private final AuthUserService users;
    public InternalSessionController(AuthUserService users) { this.users = users; }

    @PostMapping("/check")
    public SessionCheck check(@Valid @RequestBody SessionRequest request) {
        boolean active = users.findByUsername(request.username()).filter(user ->
                request.uid().equals(user.getId()) && "active".equals(user.getStatus())
                        && request.accessVersion() == user.getAccessVersion()).isPresent();
        return new SessionCheck(active);
    }
    public record SessionRequest(@NotNull @Positive Long uid, @NotBlank String username,
                                 @PositiveOrZero long accessVersion) { }
    public record SessionCheck(boolean active) { }
}
