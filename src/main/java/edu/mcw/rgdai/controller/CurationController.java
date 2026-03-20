package edu.mcw.rgdai.controller;

import edu.mcw.rgdai.service.DocumentPreprocessor;
import edu.mcw.scge.dao.implementation.DocumentEmbeddingDAO;
import edu.mcw.scge.datamodel.DocumentEmbeddingSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

@RestController
@RequestMapping("/curation")
public class CurationController {

    private static final Logger LOG = LoggerFactory.getLogger(CurationController.class);
    private final VectorStore openaiVectorStore;
    private final DocumentPreprocessor preprocessor;
    private final DocumentEmbeddingDAO documentEmbeddingDAO;

    public CurationController(@Qualifier("openaiVectorStore") VectorStore openaiVectorStore,
                               DocumentPreprocessor preprocessor) {
        this.openaiVectorStore = openaiVectorStore;
        this.preprocessor = preprocessor;
        this.documentEmbeddingDAO = new DocumentEmbeddingDAO();
    }

    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        try {
            int totalDocuments = documentEmbeddingDAO.getFileCount("documents", null);
            int totalClinicalTrials = documentEmbeddingDAO.getFileCount("clinicalTrials", null);
            int totalChunks = documentEmbeddingDAO.getTotalChunkCount();

            Map<String, Object> result = new HashMap<>();
            result.put("totalDocuments", totalDocuments);
            result.put("totalClinicalTrials", totalClinicalTrials);
            result.put("totalChunks", totalChunks);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            LOG.error("Error getting stats", e);
            return ResponseEntity.status(500).body(Map.of("error", "Failed to get stats: " + e.getMessage()));
        }
    }

    @GetMapping("/files")
    public ResponseEntity<?> listFiles(
            @RequestParam(value = "type", defaultValue = "documents") String type,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @RequestParam(value = "search", defaultValue = "") String search) {
        try {
            int offset = (page - 1) * size;
            List<DocumentEmbeddingSummary> summaries = documentEmbeddingDAO.getFileSummariesPaginated(type, search, size, offset);
            int totalCount = documentEmbeddingDAO.getFileCount(type, search);
            int totalPages = (int) Math.ceil((double) totalCount / size);

            List<Map<String, Object>> files = new ArrayList<>();
            for (DocumentEmbeddingSummary s : summaries) {
                Map<String, Object> item = new HashMap<>();
                item.put("fileName", s.getFileName());
                item.put("chunkCount", s.getChunkCount());
                item.put("uploadedAt", s.getUploadedAt() != null ? s.getUploadedAt().toString() : null);
                files.add(item);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("files", files);
            result.put("page", page);
            result.put("size", size);
            result.put("totalCount", totalCount);
            result.put("totalPages", totalPages);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            LOG.error("Error listing files", e);
            return ResponseEntity.status(500).body(Map.of("error", "Failed to list files: " + e.getMessage()));
        }
    }

    @DeleteMapping("/files")
    public ResponseEntity<?> deleteFile(@RequestParam("fileName") String fileName) {
        try {
            int chunkCount = documentEmbeddingDAO.getChunkCountByFileName(fileName);
            if (chunkCount == 0) {
                return ResponseEntity.status(404).body(Map.of("error", "File not found: " + fileName));
            }

            int deleted = documentEmbeddingDAO.deleteByFileName(fileName);
            LOG.info("Deleted {} chunks for file: {}", deleted, fileName);

            Map<String, Object> result = new HashMap<>();
            result.put("fileName", fileName);
            result.put("deletedChunks", deleted);
            result.put("message", "Successfully deleted " + deleted + " chunks");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            LOG.error("Error deleting file: {}", fileName, e);
            return ResponseEntity.status(500).body(Map.of("error", "Failed to delete file: " + e.getMessage()));
        }
    }

    @GetMapping("/files/preview")
    public ResponseEntity<?> previewFile(@RequestParam("fileName") String fileName) {
        try {
            List<String> chunks = documentEmbeddingDAO.getChunksByFileName(fileName);
            if (chunks.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error", "File not found: " + fileName));
            }

            Map<String, Object> result = new HashMap<>();
            result.put("fileName", fileName);
            result.put("chunkCount", chunks.size());
            result.put("chunks", chunks);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            LOG.error("Error previewing file: {}", fileName, e);
            return ResponseEntity.status(500).body(Map.of("error", "Failed to preview file: " + e.getMessage()));
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file) {
        LOG.info("Curation upload: {}", file.getOriginalFilename());

        Map<String, Object> result = new HashMap<>();
        result.put("fileName", file.getOriginalFilename());

        try {
            // Check if file already exists
            boolean existed = documentEmbeddingDAO.fileExists(file.getOriginalFilename());

            if (existed) {
                int oldChunks = documentEmbeddingDAO.deleteByFileName(file.getOriginalFilename());
                LOG.info("Deleted {} old chunks for re-upload: {}", oldChunks, file.getOriginalFilename());
            }

            // Process the file
            int chunkCount = processFile(file);

            result.put("status", existed ? "updated" : "done");
            result.put("chunkCount", chunkCount);

        } catch (Exception e) {
            LOG.error("Failed to process file: {}", file.getOriginalFilename(), e);
            result.put("status", "failed");
            result.put("error", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    private int processFile(MultipartFile file) throws IOException {
        LOG.info("Starting file processing: {}", file.getOriginalFilename());

        // Create temp directory and save file
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "scge-uploads-openai");
        Files.createDirectories(tempDir);
        Path destinationFile = tempDir.resolve(file.getOriginalFilename());

        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("File saved to: {}", destinationFile);
        }

        try {
            // Read document
            TikaDocumentReader documentReader = new TikaDocumentReader(destinationFile.toUri().toString());
            List<Document> documents = documentReader.get();
            documents.forEach(doc -> doc.getMetadata().put("filename", file.getOriginalFilename()));
            LOG.info("Read document with {} characters", documents.get(0).getContent().length());

            // Preprocess
            List<Document> preprocessedDocs = preprocessor.preprocessDocuments(documents);
            LOG.info("Preprocessed into {} clean documents", preprocessedDocs.size());

            if (preprocessedDocs.isEmpty()) {
                throw new RuntimeException("No usable content after preprocessing");
            }

            // Split into chunks
            TokenTextSplitter splitter = TokenTextSplitter.builder()
                    .withChunkSize(800)
                    .withMinChunkSizeChars(200)
                    .withMinChunkLengthToEmbed(50)
                    .withMaxNumChunks(10000)
                    .withKeepSeparator(true)
                    .build();

            List<Document> splitDocuments = splitter.apply(preprocessedDocs);
            LOG.info("Split into {} chunks after preprocessing", splitDocuments.size());

            // Log sample of processed content
            for (int i = 0; i < Math.min(3, splitDocuments.size()); i++) {
                String content = splitDocuments.get(i).getContent();
                LOG.info("Sample chunk {}: {} chars - {}...",
                        i + 1, content.length(),
                        content.substring(0, Math.min(150, content.length())).replaceAll("\n", " "));
            }

            // Quality filter
            List<Document> qualityChunks = splitDocuments.stream()
                    .filter(doc -> preprocessor.isQualityChunk(doc.getContent()))
                    .toList();

            LOG.info("Quality filtered: {} chunks retained out of {}", qualityChunks.size(), splitDocuments.size());

            if (qualityChunks.isEmpty()) {
                throw new RuntimeException("No quality content found after processing");
            }

            // Add to vector store
            openaiVectorStore.add(qualityChunks);
            LOG.info("Successfully added {} chunks to OpenAI vector store for file: {}",
                    qualityChunks.size(), file.getOriginalFilename());

            return qualityChunks.size();
        } finally {
            // Clean up temp file
            try {
                Files.deleteIfExists(destinationFile);
            } catch (IOException e) {
                LOG.warn("Failed to delete temp file: {}", e.getMessage());
            }
        }
    }
}
