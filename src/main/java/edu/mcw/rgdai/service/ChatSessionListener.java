package edu.mcw.rgdai.service;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Clears a conversation's chat memory when its HTTP session expires or is invalidated.
 */
@Component
public class ChatSessionListener implements HttpSessionListener {

    private static final Logger LOG = LoggerFactory.getLogger(ChatSessionListener.class);
    private static final String CONVERSATION_ID_ATTRIBUTE = "openai_conversation_id";

    private final BoundedChatMemory chatMemory;

    public ChatSessionListener(BoundedChatMemory chatMemory) {
        this.chatMemory = chatMemory;
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        Object conversationId = event.getSession().getAttribute(CONVERSATION_ID_ATTRIBUTE);
        if (conversationId != null) {
            chatMemory.clear(conversationId.toString());
            LOG.debug("Session ended - cleared chat memory for conversation {}", conversationId);
        }
    }
}
