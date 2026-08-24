package com.aetheris.aiagent.controller;

import com.aetheris.aiagent.entity.ChatConversation;
import com.aetheris.aiagent.service.ChatConversationService;
import com.aetheris.aiagent.service.ChatMessageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatControllerTest {

    @Test
    void assignsConversationToAuthenticatedUser() {
        ChatConversationService conversationService = mock(ChatConversationService.class);
        ChatMessageService messageService = mock(ChatMessageService.class);
        ChatController controller = controller(conversationService, messageService);

        controller.createConversation("alice");

        ArgumentCaptor<ChatConversation> captor = ArgumentCaptor.forClass(ChatConversation.class);
        verify(conversationService).save(captor.capture());
        assertEquals("alice", captor.getValue().getOwnerUsername());
    }

    @Test
    void hidesConversationOwnedByAnotherUser() {
        ChatConversationService conversationService = mock(ChatConversationService.class);
        ChatMessageService messageService = mock(ChatMessageService.class);
        when(conversationService.getOne(any())).thenReturn(null);
        ChatController controller = controller(conversationService, messageService);

        assertEquals(HttpStatus.NOT_FOUND, controller.listMessages(42L, "alice").getStatusCode());
        verifyNoInteractions(messageService);
    }

    private ChatController controller(
            ChatConversationService conversationService,
            ChatMessageService messageService) {
        return new ChatController(
                RestClient.builder(),
                "https://example.invalid",
                "test-key",
                "test-model",
                conversationService,
                messageService);
    }
}
