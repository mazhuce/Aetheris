package com.aetheris.learning.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LearningRepositoryPolicyTest {

    @Test
    void unwrapsStringEncodedPolicies() throws Exception {
        String encoded = new ObjectMapper().writeValueAsString("{\"allowWrite\":true}");

        var node = LearningRepository.readPolicy(encoded);

        assertThat(node.isObject()).isTrue();
        assertThat(node.path("allowWrite").asBoolean()).isTrue();
    }

    @Test
    void keepsPlainObjectPolicies() {
        var node = LearningRepository.readPolicy("{\"allowWrite\":true}");

        assertThat(node.isObject()).isTrue();
        assertThat(node.path("allowWrite").asBoolean()).isTrue();
    }
}