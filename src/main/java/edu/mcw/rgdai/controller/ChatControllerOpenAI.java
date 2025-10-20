package edu.mcw.rgdai.controller;

import edu.mcw.rgdai.model.Answer;
import edu.mcw.rgdai.model.Question;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.springframework.ai.openai.OpenAiChatOptions;

@RestController
@RequestMapping("/chat-openai")
public class ChatControllerOpenAI {

    private static final Logger LOG = LoggerFactory.getLogger(ChatControllerOpenAI.class);

    private ChatClient chatClient;
    private final VectorStore openaiVectorStore;
    private final String configuredModel;
    private InMemoryChatMemory chatMemory;
    private final ChatModel openAiChatModel;
    private static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "conversationId";

    public ChatControllerOpenAI(
            ApplicationContext context,
            @Qualifier("openaiVectorStore") VectorStore openaiVectorStore,
            @Value("${spring.ai.openai.model}") String configuredModel) {

        LOG.info("🤖 Initializing OpenAI ChatController with system messages for doc context");
        this.openaiVectorStore = openaiVectorStore;
        this.configuredModel = configuredModel;
        this.chatMemory = new InMemoryChatMemory();

        LOG.info("🎯 Configured OpenAI Model from properties: {}", configuredModel);

        Map<String, ChatModel> chatModels = context.getBeansOfType(ChatModel.class);
        ChatModel foundModel = null;
        for (Map.Entry<String, ChatModel> entry : chatModels.entrySet()) {
            if (entry.getValue().getClass().getSimpleName().toLowerCase().contains("openai")) {
                foundModel = entry.getValue();
                LOG.info("✅ Found OpenAI ChatModel: {}", entry.getKey());
                LOG.info("📋 ChatModel Class: {}", entry.getValue().getClass().getName());
                break;
            }
        }
        if (foundModel == null) {
            throw new RuntimeException("❌ OpenAI ChatModel not found!");
        }
        this.openAiChatModel = foundModel;
        this.chatClient = buildClient(openAiChatModel, this.chatMemory);
        LOG.info("✅ OpenAI ChatClient initialized successfully with model: {}", configuredModel);
    }

    private ChatClient buildClient(ChatModel model, InMemoryChatMemory memory) {
        return ChatClient.builder(model)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        new MessageChatMemoryAdvisor(memory)
                )
                .build();
    }

    @PostMapping
    public Answer chat(@RequestBody Question question,
                       Authentication user,
                       HttpServletRequest request) {

        LOG.info("🔵 OpenAI - Received question: {}", question.getQuestion());
        LOG.info("🎯 Processing with model: {}", configuredModel);

        String conversationId = getOrCreateConversationId(user, request);
        LOG.info("🔵 Using conversation ID: {}", conversationId);

        if (isGreeting(question.getQuestion())) {
            return new Answer("Hello! I'm the OpenAI version. I can help you with questions about the documents in my knowledge base. What would you like to know?");
        }

        try {
            List<Document> documents = openaiVectorStore.similaritySearch(
                    SearchRequest.query(question.getQuestion())
                            .withTopK(10)
                            .withSimilarityThreshold(0.35));
            LOG.info("🔵 OpenAI - Retrieved {} documents from vector store", documents.size());

            if (documents.isEmpty()) {
                return new Answer("I don't have information about that topic in my knowledge base.");
            }

//            StringBuilder contextBuilder = new StringBuilder();
//            for (Document doc : documents) {
//                contextBuilder.append(doc.getContent()).append("\n\n");
//            }
            StringBuilder contextBuilder = new StringBuilder();
            for (Document doc : documents) {
                String filename = doc.getMetadata().getOrDefault("filename", "unknown").toString();
                // Extract just the NCT ID if filename starts with NCT and contains a colon
                if (filename.startsWith("NCT") && filename.contains(":")) {
                    filename = filename.split(":")[0];
                }
                contextBuilder.append(String.format("--- FROM: %s ---\n%s\n\n", filename, doc.getContent()));
            }
            String systemMessage = String.format("""
        Answer using the context below OR conversation history. Do NOT use external knowledge about general topics, mountains, etc.
        When asked about "last question" or "previous question", refer to the most recent user message in the conversation.
        IMPORTANT: When asked about "last question" or "previous question", only refer to questions YOU were asked in THIS conversation, not questions mentioned in the document context.

        Context:
        ---------------------
        %s
        ---------------------

        MANDATORY: At the end of your response, add "SOURCES_USED: " followed by a comma-separated list of filenames
        - ONLY list files that you ACTUALLY USED to generate your answer
        - DO NOT list files that were in the context but you did not use
        - Use exact filenames from "--- FROM: filename ---" markers
        - Separate multiple files with commas (no spaces after commas)
        - Example: SOURCES_USED: file1.md,file2.md,file3.md
        """, contextBuilder);

//            String systemMessage = String.format("""
//                    Answer using the context below OR conversation history. Do NOT use external knowledge about general topics, mountains, etc.
//                    When asked about "last question" or "previous question", refer to the most recent user message in the conversation.
//                    IMPORTANT: When asked about "last question" or "previous question", only refer to questions YOU were asked in THIS conversation, not questions mentioned in the document context.
//
//                    Context:
//                    ---------------------
//                    %s
//                    ---------------------
//                    Add "SOURCES_USED: %s" when using context.
//                    """, contextBuilder, filenames);

            String response = chatClient.prompt()
                    .system(systemMessage)
                    .user(question.getQuestion())
                    .options(OpenAiChatOptions.builder()
                            .withStreamUsage(false)
                            .withModel(configuredModel)
//                            .withTemperature(1.0)
                            .build())
                    .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId))
                    .call()
                    .content();

            LOG.info("🔵 OpenAI - Generated response with system message approach using model: {}", configuredModel);
            return new Answer(response);

        } catch (Exception e) {
            LOG.error("❌ OpenAI - Error generating response with model {}: {}", configuredModel, e.getMessage(), e);
            return new Answer("OpenAI Error: " + e.getMessage());
        }
    }

    @PostMapping("/reset-memory")
    public ResponseEntity<Map<String, String>> resetChatMemory(
            Authentication user,
            HttpServletRequest request) {

        LOG.info("🔄 OpenAI - Reset chat memory requested");
        try {
            String oldId = getOrCreateConversationId(user, request);
            chatMemory.clear(oldId);
            LOG.info("✅ OpenAI - Cleared memory for conversation ID: {}", oldId);

            request.getSession().removeAttribute("openai_conversation_id");

            String newId = "reset_" + System.currentTimeMillis();
            request.getSession().setAttribute("openai_conversation_id", newId);
            LOG.info("✅ OpenAI - Started new conversation with ID: {}", newId);

            InMemoryChatMemory newMemory = new InMemoryChatMemory();
            this.chatMemory = newMemory;
            this.chatClient = buildClient(this.openAiChatModel, newMemory);

            Map<String, String> resp = new HashMap<>();
            resp.put("status", "success");
            resp.put("message", "Chat memory cleared successfully");
            resp.put("oldConversationId", oldId);
            resp.put("newConversationId", newId);
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            LOG.error("❌ OpenAI - Error resetting chat memory: {}", e.getMessage(), e);
            Map<String, String> resp = new HashMap<>();
            resp.put("status", "error");
            resp.put("message", "Failed to reset chat memory");
            return ResponseEntity.status(500).body(resp);
        }
    }

    private boolean isGreeting(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        return text.toLowerCase().matches(".*\\b(hi|hello|hey|greetings)\\b.*");
    }

    private String getOrCreateConversationId(Authentication user, HttpServletRequest request) {
        String conversationId = (String) request.getSession().getAttribute("openai_conversation_id");
        if (conversationId == null) {
            conversationId = (user != null) ? user.getName() : request.getSession().getId();
            request.getSession().setAttribute("openai_conversation_id", conversationId);
        }
        return conversationId;
    }
}
