package edu.mcw.rgdai.controller;

import edu.mcw.rgdai.model.Answer;
import edu.mcw.rgdai.model.Question;
import edu.mcw.rgdai.model.DocumentEmbeddingOpenAI;
import edu.mcw.rgdai.repository.DocumentEmbeddingOpenAIRepository;
import edu.mcw.rgdai.vectorstore.PostgresVectorStoreOpenAI;
import edu.mcw.rgdai.service.RecaptchaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
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
    private final DocumentEmbeddingOpenAIRepository repository;
    private final RecaptchaService recaptchaService;
    private static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";

    public ChatControllerOpenAI(
            ApplicationContext context,
            @Qualifier("openaiVectorStore") VectorStore openaiVectorStore,
            @Value("${spring.ai.openai.model}") String configuredModel,
            DocumentEmbeddingOpenAIRepository repository,
            RecaptchaService recaptchaService) {

        LOG.info("Initializing OpenAI ChatController with system messages for doc context");
        this.openaiVectorStore = openaiVectorStore;
        this.configuredModel = configuredModel;
        this.repository = repository;
        this.recaptchaService = recaptchaService;
        this.chatMemory = new InMemoryChatMemory();

        LOG.info("Configured OpenAI Model from properties: {}", configuredModel);

        Map<String, ChatModel> chatModels = context.getBeansOfType(ChatModel.class);
        ChatModel foundModel = null;
        for (Map.Entry<String, ChatModel> entry : chatModels.entrySet()) {
            if (entry.getValue().getClass().getSimpleName().toLowerCase().contains("openai")) {
                foundModel = entry.getValue();
                LOG.info("Found OpenAI ChatModel: {}", entry.getKey());
                LOG.info("ChatModel Class: {}", entry.getValue().getClass().getName());
                break;
            }
        }
        if (foundModel == null) {
            throw new RuntimeException("OpenAI ChatModel not found!");
        }
        this.openAiChatModel = foundModel;
        this.chatClient = buildClient(openAiChatModel, this.chatMemory);
        LOG.info("OpenAI ChatClient initialized successfully with model: {}", configuredModel);
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

        LOG.info("OpenAI - Received question: {}", question.getQuestion());
        LOG.info("Processing with model: {}", configuredModel);

        String conversationId = getOrCreateConversationId(user, request);
        LOG.info("Using conversation ID: {}", conversationId);

        if (isGreeting(question.getQuestion())) {
            return new Answer("Hello! I'm the SCGE AI Assistant. I can help you with questions about the documents in my knowledge base. What would you like to know?");
        }

        try {
            // Extract NCTIDs from current question
            List<String> currentNCTIDs = extractNCTIDs(question.getQuestion());

            // Get NCTIDs from previous conversation (stored in session)
            Set<String> allNCTIDs = new HashSet<>(currentNCTIDs);
            Set<String> sessionNCTIDs = (Set<String>) request.getSession().getAttribute("conversationNCTIDs");
            if (sessionNCTIDs != null && !sessionNCTIDs.isEmpty()) {
                allNCTIDs.addAll(sessionNCTIDs);
                LOG.info("Retrieved {} NCTIDs from session: {}", sessionNCTIDs.size(), sessionNCTIDs);
            }

            if (!allNCTIDs.isEmpty()) {
                LOG.info("Found {} unique NCTIDs in conversation (current + session): {}", allNCTIDs.size(), allNCTIDs);
            }

            // STAGE 1: Broad retrieval - Get top 80 candidates from semantic search WITH SCORES
            List<Document> candidates;
            if (openaiVectorStore instanceof PostgresVectorStoreOpenAI) {
                // Use the enhanced method that includes similarity scores in metadata
                PostgresVectorStoreOpenAI vectorStoreWithScores = (PostgresVectorStoreOpenAI) openaiVectorStore;
                candidates = vectorStoreWithScores.similaritySearchWithScores(
                        SearchRequest.query(question.getQuestion())
                                .withTopK(80)
                                .withSimilarityThreshold(0.35));
            } else {
                // Fallback to regular method (won't have scores for re-ranking)
                candidates = openaiVectorStore.similaritySearch(
                        SearchRequest.query(question.getQuestion())
                                .withTopK(80)
                                .withSimilarityThreshold(0.35));
            }
            LOG.info("Stage 1: Retrieved {} candidates from semantic similarity search", candidates.size());

            // STAGE 2: Re-rank using semantic + keyword scoring
            List<Document> documents = rerankDocuments(candidates, question.getQuestion());

            // Take top 40 after re-ranking
            if (documents.size() > 40) {
                documents = documents.subList(0, 40);
            }
            LOG.info("Stage 2: Re-ranked and selected top {} documents", documents.size());

            // Add documents for all mentioned NCTIDs (current question + conversation history)
            if (!allNCTIDs.isEmpty()) {
                int initialSize = documents.size();
                for (String nctid : allNCTIDs) {
                    List<Document> nctidDocs = getDocumentsByNCTID(nctid);
                    documents.addAll(nctidDocs);
                }
                int addedCount = documents.size() - initialSize;
                LOG.info("Added {} documents for {} NCTIDs mentioned in conversation", addedCount, allNCTIDs.size());
            }

            LOG.info("OpenAI - Total documents for context: {}", documents.size());

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
        If a question is about a topic completely unrelated to the subject matter in the provided context (e.g., entertainment, sports, geography), politely decline and use "SOURCES_USED: None". Do not offer to discuss such topics further.
        When asked about "last question" or "previous question", refer to the most recent user message in the conversation.
        IMPORTANT: When asked about "last question" or "previous question", only refer to questions YOU were asked in THIS conversation, not questions mentioned in the document context.

        Context:
        ---------------------
        %s
        ---------------------

        CRITICAL INSTRUCTIONS:
        - Scan the ENTIRE context carefully, including all documents from beginning to end
        - When multiple clinical trials are relevant to the question, list ALL of them with their NCTIDs
        - Do not summarize or skip relevant sources just to be brief

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
                            .withTemperature(1.0)
                            .build())
                    .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId))
                    .call()
                    .content();

            LOG.info("OpenAI - Generated response with system message approach using model: {}", configuredModel);

            // Extract NCTIDs from AI response and store in session for future questions
            List<String> responseNCTIDs = extractNCTIDs(response);
            if (!responseNCTIDs.isEmpty()) {
                Set<String> conversationNCTIDs = (Set<String>) request.getSession().getAttribute("conversationNCTIDs");
                if (conversationNCTIDs == null) {
                    conversationNCTIDs = new HashSet<>();
                }
                int beforeSize = conversationNCTIDs.size();
                conversationNCTIDs.addAll(responseNCTIDs);
                int afterSize = conversationNCTIDs.size();
                int newNCTIDs = afterSize - beforeSize;
                request.getSession().setAttribute("conversationNCTIDs", conversationNCTIDs);
                LOG.info("Found {} NCTID mentions in response, {} unique NCTIDs total in conversation ({} new)",
                         responseNCTIDs.size(), conversationNCTIDs.size(), newNCTIDs);
                LOG.debug("Session NCTIDs: {}", conversationNCTIDs);
            }

            return new Answer(response);

        } catch (Exception e) {
            LOG.error("OpenAI - Error generating response with model {}: {}", configuredModel, e.getMessage(), e);
            return new Answer("OpenAI Error: " + e.getMessage());
        }
    }

    @PostMapping("/reset-memory")
    public ResponseEntity<Map<String, String>> resetChatMemory(
            Authentication user,
            HttpServletRequest request) {

        LOG.info("OpenAI - Reset chat memory requested");
        try {
            String oldId = getOrCreateConversationId(user, request);
            chatMemory.clear(oldId);
            LOG.info("OpenAI - Cleared memory for conversation ID: {}", oldId);

            request.getSession().removeAttribute("openai_conversation_id");
            request.getSession().removeAttribute("conversationNCTIDs");
            LOG.info("OpenAI - Cleared session NCTIDs");

            String newId = "reset_" + System.currentTimeMillis();
            request.getSession().setAttribute("openai_conversation_id", newId);
            LOG.info("OpenAI - Started new conversation with ID: {}", newId);

            Map<String, String> resp = new HashMap<>();
            resp.put("status", "success");
            resp.put("message", "Chat memory cleared successfully");
            resp.put("oldConversationId", oldId);
            resp.put("newConversationId", newId);
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            LOG.error("OpenAI - Error resetting chat memory: {}", e.getMessage(), e);
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

    /**
     * Extract NCTIDs from question text (e.g., NCT06285643)
     */
    private List<String> extractNCTIDs(String question) {
        List<String> nctids = new ArrayList<>();
        if (question == null || question.trim().isEmpty()) {
            return nctids;
        }

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("NCT\\d+");
        java.util.regex.Matcher matcher = pattern.matcher(question);
        while (matcher.find()) {
            nctids.add(matcher.group());
        }
        return nctids;
    }

    /**
     * Get documents by NCTID directly from database
     */
    private List<Document> getDocumentsByNCTID(String nctid) {
        String filename = "CLINICAL TRIAL: " + nctid;
        List<DocumentEmbeddingOpenAI> docs = repository.findByFileName(filename);

        LOG.debug("Direct lookup for NCTID {}: found {} documents", nctid, docs.size());

        return docs.stream()
                .map(de -> {
                    Map<String, Object> metadata = Map.of(
                            "filename", de.getFileName(),
                            "id", de.getId(),
                            "created_at", de.getCreatedAt()
                    );
                    return new Document(de.getChunk(), metadata);
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Extract meaningful query terms (removing stop words)
     */
    private Set<String> extractQueryTerms(String query) {
        Set<String> terms = new HashSet<>();

        // Common stop words to exclude
        Set<String> stopWords = Set.of("the", "a", "an", "is", "are", "was", "were",
                                       "in", "on", "at", "to", "for", "of", "with",
                                       "what", "how", "when", "where", "which", "that",
                                       "this", "these", "those", "be", "been", "being",
                                       "have", "has", "had", "do", "does", "did");

        // Split query into words and filter
        String[] words = query.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ") // Replace punctuation with space
                .split("\\s+");

        for (String word : words) {
            if (word.length() > 2 && !stopWords.contains(word)) {
                terms.add(word);
            }
        }

        // Extract NCTIDs (case-insensitive match, case-sensitive store)
        java.util.regex.Pattern nctPattern = java.util.regex.Pattern.compile("(?i)NCT\\d+");
        java.util.regex.Matcher nctMatcher = nctPattern.matcher(query);
        while (nctMatcher.find()) {
            terms.add(nctMatcher.group().toUpperCase());
        }

        LOG.debug("Extracted query terms: {}", terms);
        return terms;
    }

    /**
     * Re-rank documents by combining semantic score + keyword matching
     */
    private List<Document> rerankDocuments(List<Document> candidates, String query) {
        Set<String> queryTerms = extractQueryTerms(query);

        if (queryTerms.isEmpty()) {
            LOG.warn("No query terms extracted, returning original order");
            return candidates;
        }

        // Score and sort documents
        List<ScoredDocument> scoredDocs = candidates.stream()
                .map(doc -> {
                    // Get semantic similarity score (already in metadata as distance)
                    Double distance = (Double) doc.getMetadata().getOrDefault("distance", 1.0);
                    double semanticScore = 1.0 - distance; // Convert distance to similarity

                    // Calculate keyword match score
                    String content = doc.getContent().toLowerCase();
                    long matchCount = queryTerms.stream()
                            .filter(term -> content.contains(term))
                            .count();
                    double keywordScore = (double) matchCount / queryTerms.size();

                    // Combined score: 70% semantic + 30% keyword
                    double finalScore = (0.7 * semanticScore) + (0.3 * keywordScore);

                    return new ScoredDocument(doc, finalScore, semanticScore, keywordScore, matchCount);
                })
                .sorted((a, b) -> Double.compare(b.finalScore, a.finalScore)) // Descending order
                .collect(java.util.stream.Collectors.toList());

        // Log top 10 for debugging
        LOG.info("Re-ranking results (top 10):");
        for (int i = 0; i < Math.min(10, scoredDocs.size()); i++) {
            ScoredDocument sd = scoredDocs.get(i);
            LOG.info("  {}. {} - Final: {}, Semantic: {}, Keyword: {}/{} = {}",
                    i + 1,
                    sd.doc.getMetadata().get("filename"),
                    String.format("%.4f", sd.finalScore),
                    String.format("%.4f", sd.semanticScore),
                    sd.matchCount,
                    queryTerms.size(),
                    String.format("%.2f", sd.keywordScore));
        }

        return scoredDocs.stream()
                .map(sd -> sd.doc)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Helper class to hold scored documents during re-ranking
     */
    private static class ScoredDocument {
        final Document doc;
        final double finalScore;
        final double semanticScore;
        final double keywordScore;
        final long matchCount;

        ScoredDocument(Document doc, double finalScore, double semanticScore, double keywordScore, long matchCount) {
            this.doc = doc;
            this.finalScore = finalScore;
            this.semanticScore = semanticScore;
            this.keywordScore = keywordScore;
            this.matchCount = matchCount;
        }
    }

    /**
     * reCAPTCHA v3 verification endpoint
     * Called by verify.jsp to verify the token with Google
     */
    @PostMapping("/verify-recaptcha")
    public ResponseEntity<Map<String, Object>> verifyRecaptcha(
            @RequestBody Map<String, String> request,
            HttpSession session) {

        LOG.info("Received reCAPTCHA verification request");

        String token = request.get("token");

        if (token == null || token.trim().isEmpty()) {
            LOG.error("No token provided in verification request");
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "No verification token provided");
            return ResponseEntity.badRequest().body(result);
        }

        // Call RecaptchaService to verify with Google
        RecaptchaService.RecaptchaResponse response = recaptchaService.verifyToken(token);

        Map<String, Object> result = new HashMap<>();

        if (response.isSuccess()) {
            // SET SESSION ATTRIBUTE - marks user as verified
            session.setAttribute("recaptcha_verified", true);
            LOG.info("reCAPTCHA verification successful - Session marked as verified");

            result.put("success", true);
            result.put("message", "Verification successful");
            result.put("score", response.getScore());
            return ResponseEntity.ok(result);
        } else {
            LOG.warn("reCAPTCHA verification failed: {}", response.getMessage());
            result.put("success", false);
            result.put("message", response.getMessage());
            result.put("score", response.getScore());
            return ResponseEntity.ok(result);
        }
    }
}
