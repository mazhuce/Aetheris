package com.aetheris.serving.service;

import com.aetheris.serving.dto.SubscriptionInput;
import com.aetheris.serving.model.DataSubscription;
import com.aetheris.serving.repository.SubscriptionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ServingSubscriptionService {

    private final SubscriptionRepository repository;

    public ServingSubscriptionService(SubscriptionRepository repository) {
        this.repository = repository;
    }

    public List<DataSubscription> subscriptions(Optional<String> status) { return repository.list(status); }

    public DataSubscription subscription(Long id) {
        return repository.find(id).orElseThrow(() -> new NotFoundException("数据订阅不存在"));
    }

    public DataSubscription create(SubscriptionInput input, String username) {
        return repository.create(input, username);
    }

    public DataSubscription update(Long id, SubscriptionInput input) {
        return repository.update(id, input).orElseThrow(() -> new NotFoundException("数据订阅不存在"));
    }

    public void delete(Long id) {
        if (!repository.delete(id)) throw new NotFoundException("数据订阅不存在");
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }
}
