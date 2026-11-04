package edu.mcw.rgdai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat memory that keeps only the most recent messages of each conversation and
 * drops conversations that have been idle too long. Replaces InMemoryChatMemory,
 * which kept every message of every conversation until Tomcat restarted.
 * Conversations are also cleared when their HTTP session ends (ChatSessionListener).
 */
@Component
public class BoundedChatMemory implements ChatMemory {

    private static final Logger LOG = LoggerFactory.getLogger(BoundedChatMemory.class);

    private final int maxMessagesPerConversation;
    private final long idleTimeoutMillis;
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    private static class Conversation {
        final Deque<Message> messages = new ArrayDeque<>();
        volatile long lastAccess = System.currentTimeMillis();
    }

    public BoundedChatMemory(
            @Value("${rag.chat-memory.max-messages-per-conversation:40}") int maxMessagesPerConversation,
            @Value("${rag.chat-memory.idle-timeout-minutes:240}") long idleTimeoutMinutes) {
        this.maxMessagesPerConversation = maxMessagesPerConversation;
        this.idleTimeoutMillis = idleTimeoutMinutes * 60_000L;
        LOG.info("BoundedChatMemory: keeping last {} messages per conversation, idle timeout {} min",
                maxMessagesPerConversation, idleTimeoutMinutes);
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        Conversation conversation = conversations.computeIfAbsent(conversationId, id -> new Conversation());
        synchronized (conversation) {
            conversation.messages.addAll(messages);
            while (conversation.messages.size() > maxMessagesPerConversation) {
                conversation.messages.pollFirst();
            }
            conversation.lastAccess = System.currentTimeMillis();
        }
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        Conversation conversation = conversations.get(conversationId);
        if (conversation == null) {
            return List.of();
        }
        synchronized (conversation) {
            conversation.lastAccess = System.currentTimeMillis();
            List<Message> all = new ArrayList<>(conversation.messages);
            return all.subList(Math.max(0, all.size() - lastN), all.size());
        }
    }

    @Override
    public void clear(String conversationId) {
        conversations.remove(conversationId);
    }

    /**
     * Remove conversations idle longer than the timeout (catches sessions that never
     * fired a destroy event, e.g. conversation IDs keyed by user name).
     */
    @Scheduled(fixedDelayString = "${rag.chat-memory.cleanup-interval-ms:600000}")
    public void evictIdleConversations() {
        long cutoff = System.currentTimeMillis() - idleTimeoutMillis;
        int before = conversations.size();
        conversations.entrySet().removeIf(e -> e.getValue().lastAccess < cutoff);
        int removed = before - conversations.size();
        if (removed > 0) {
            LOG.info("Evicted {} idle conversations, {} remain", removed, conversations.size());
        }
    }
}
