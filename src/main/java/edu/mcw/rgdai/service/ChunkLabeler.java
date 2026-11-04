package edu.mcw.rgdai.service;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prefixes every chunk with a one-line "[source]" label so chunks after the first still say
 * which trial or document they came from - both for the embedding and for the model reading
 * the context. Keep in sync with ChunkLabeler in clinical-trials-rag-load-pipeline.
 */
public final class ChunkLabeler {

    public static final String LABEL_METADATA_KEY = "chunk_label";

    // "Title: ..." line written by UrlDocumentReader for clinical trial pages
    private static final Pattern TITLE_LINE = Pattern.compile("(?m)^Title: (.+)$");

    private ChunkLabeler() {}

    /**
     * Store the label in each source document's metadata before splitting; the splitter copies
     * metadata to every chunk.
     */
    public static List<Document> addLabels(List<Document> documents) {
        List<Document> labeled = new ArrayList<>();
        for (Document doc : documents) {
            Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
            metadata.put(LABEL_METADATA_KEY, buildLabel(doc));
            labeled.add(new Document(doc.getContent(), metadata));
        }
        return labeled;
    }

    /**
     * Prefix each chunk with the label from its metadata.
     */
    public static List<Document> applyLabels(List<Document> chunks) {
        List<Document> labeled = new ArrayList<>();
        for (Document chunk : chunks) {
            Object label = chunk.getMetadata().get(LABEL_METADATA_KEY);
            if (label == null || chunk.getContent().startsWith(label.toString())) {
                labeled.add(chunk);
            } else {
                labeled.add(new Document(label + "\n" + chunk.getContent(), chunk.getMetadata()));
            }
        }
        return labeled;
    }

    static String buildLabel(Document doc) {
        String filename = doc.getMetadata().getOrDefault("filename", "unknown").toString();
        if (filename.startsWith("CLINICAL TRIAL: ")) {
            Matcher title = TITLE_LINE.matcher(doc.getContent());
            return title.find()
                    ? "[" + filename + " - " + title.group(1).trim() + "]"
                    : "[" + filename + "]";
        }
        // URL sources are stored as "name:url" - the label only needs the name
        int urlStart = filename.indexOf(":http");
        String source = urlStart > 0 ? filename.substring(0, urlStart) : filename;
        return "[Source: " + source + "]";
    }
}
