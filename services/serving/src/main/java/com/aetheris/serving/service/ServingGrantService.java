package com.aetheris.serving.service;

import com.aetheris.serving.dto.GrantInput;
import com.aetheris.serving.model.ServiceGrant;
import com.aetheris.serving.repository.GrantRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ServingGrantService {

    private final GrantRepository repository;

    public ServingGrantService(GrantRepository repository) {
        this.repository = repository;
    }

    public List<ServiceGrant> grants(Optional<String> status) { return repository.list(status); }

    public ServiceGrant grant(Long id) {
        return repository.find(id).orElseThrow(() -> new NotFoundException("服务授权不存在"));
    }

    public ServiceGrant create(GrantInput input, String username) {
        return repository.create(input, username);
    }

    public ServiceGrant update(Long id, GrantInput input) {
        return repository.update(id, input).orElseThrow(() -> new NotFoundException("服务授权不存在"));
    }

    public ServiceGrant revoke(Long id) {
        return repository.updateStatus(id, "revoked").orElseThrow(() -> new NotFoundException("服务授权不存在"));
    }

    public ServiceGrant restore(Long id) {
        return repository.updateStatus(id, "active").orElseThrow(() -> new NotFoundException("服务授权不存在"));
    }

    public void delete(Long id) {
        if (!repository.delete(id)) throw new NotFoundException("服务授权不存在");
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }
}
