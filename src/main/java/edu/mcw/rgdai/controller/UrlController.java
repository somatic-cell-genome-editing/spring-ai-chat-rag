package edu.mcw.rgdai.controller;

import edu.mcw.rgdai.model.UrlRequest;
import edu.mcw.rgdai.reader.UrlDocumentReader;
import edu.mcw.rgdai.service.DocumentPreprocessor;
import edu.mcw.rgdai.repository.DocumentEmbeddingOpenAIRepository;
import edu.mcw.rgdai.model.DocumentEmbeddingOpenAI;
import edu.mcw.scge.dao.DataSourceFactory;
import edu.mcw.scge.dao.implementation.ClinicalTrailDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class UrlController {
    private static final Logger LOG = LoggerFactory.getLogger(UrlController.class);

    private final VectorStore openaiVectorStore;
    private final DocumentPreprocessor preprocessor;
    private final DocumentEmbeddingOpenAIRepository repository;

    public UrlController(@Qualifier("openaiVectorStore") VectorStore openaiVectorStore,
                         DocumentPreprocessor preprocessor,
                         DocumentEmbeddingOpenAIRepository repository){
        this.openaiVectorStore = openaiVectorStore;
        this.preprocessor = preprocessor;
        this.repository = repository;
    }

    @PostMapping("/process-url")
    public ResponseEntity<?> processUrl(@RequestBody UrlRequest urlRequest) {
        String urlString = urlRequest.getUrl();
        LOG.info("Processing URL for OpenAI: {}", urlString);

        // Validate URL
        URL url;
        try {
            url = new URL(urlString);
        } catch (MalformedURLException e) {
            LOG.error("Invalid URL: {}", urlString, e);
            Map<String, String> response = new HashMap<>();
            response.put("error", "Invalid URL format");
            return ResponseEntity.badRequest().body(response);
        }

        return processUrlInternal(urlString);
    }

    @PostMapping("/load-clinical-trials")
    public ResponseEntity<?> loadClinicalTrials() {
        LOG.info("Starting clinical trials loading process");

        try {
            // Get NCT IDs from CurDS database using custom datasource
            DataSource curationDS = DataSourceFactory.getInstance().getScgePlatformDataSource();
            ClinicalTrailDAO dao = new ClinicalTrailDAO(curationDS);
            List<String> nctIds = dao.getAllNctIds();
            LOG.info("Retrieved {} NCT IDs from CurDS database", nctIds.size());

            // COMMENTED OUT - Hardcoded NCT IDs list (380 IDs from query select nctId from clinical_trial_record)
            // Replaced with dynamic retrieval from CurDS database above
            /*
            List<String> nctIds = Arrays.asList(
                    "NCT05930561", "NCT05197270", "NCT04517149", "NCT04483440", "NCT06864988", "NCT04519749", "NCT05248230", "NCT04676048",
                    "NCT04145037", "NCT03454893", "NCT02556736", "NCT05407636", "NCT04704921", "NCT03315182", "NCT05725018", "NCT04783181",
                    "NCT02168686", "NCT05536973", "NCT06856577", "NCT06517888", "NCT05821959", "NCT03770572", "NCT02725580", "NCT00891306",
                    "NCT02144610", "NCT06839235", "NCT06467344", "NCT05230459", "NCT03533673", "NCT05598333", "NCT06285643", "NCT04998396",
                    "NCT01410019", "NCT03964792", "NCT05071222", "NCT06736080", "NCT03199469", "NCT06483802", "NCT04174105", "NCT05973630",
                    "NCT05224505", "NCT05878860", "NCT03920007", "NCT03223194", "NCT06064890", "NCT00076557", "NCT06550011", "NCT05241444",
                    "NCT04394286", "NCT03588299", "NCT06611436", "NCT01054339", "NCT02416622", "NCT00749957", "NCT02599922", "NCT02935517",
                    "NCT04850118", "NCT06389877", "NCT06735755", "NCT05456880", "NCT05968118", "NCT04275323", "NCT06761183", "NCT06185673",
                    "NCT04737460", "NCT05121376", "NCT04480567", "NCT04323098", "NCT03116113", "NCT03496012", "NCT04278131", "NCT06515002",
                    "NCT00272857", "NCT04680065", "NCT05541627", "NCT02161380", "NCT04091737", "NCT03569891", "NCT06650319", "NCT06458595",
                    "NCT05672121", "NCT05657301", "NCT05832684", "NCT02186418", "NCT05761899", "NCT04286815", "NCT06207552", "NCT06364774",
                    "NCT05265767", "NCT06291961", "NCT06647979", "NCT05353647", "NCT03311503", "NCT02082860", "NCT02247843", "NCT04798235",
                    "NCT05444894", "NCT04853576", "NCT06692712", "NCT05518188", "NCT05419492", "NCT06399107", "NCT05144386", "NCT05903794",
                    "NCT04418414", "NCT06641154", "NCT06545955", "NCT05757245", "NCT05762510", "NCT03837483", "NCT00598481", "NCT03173521",
                    "NCT05739643", "NCT06492876", "NCT06492850", "NCT06492863", "NCT05858983", "NCT04713475", "NCT03326336", "NCT02652767",
                    "NCT05835895", "NCT05824169", "NCT05860569", "NCT06391736", "NCT06739434", "NCT03466463", "NCT01344798", "NCT01024998",
                    "NCT06199531", "NCT05207657", "NCT06833983", "NCT06856759", "NCT04566445", "NCT00821340", "NCT00787059", "NCT06819514",
                    "NCT02563522", "NCT05361031", "NCT05176093", "NCT01064440", "NCT04469270", "NCT02984085", "NCT05222178", "NCT06178432",
                    "NCT06615206", "NCT06623279", "NCT06031727", "NCT05906953", "NCT06594094", "NCT05454566", "NCT02453477", "NCT01621867",
                    "NCT06289452", "NCT06196840", "NCT06196827", "NCT06817382", "NCT01801709", "NCT04186650", "NCT05641610", "NCT06238908",
                    "NCT04728841", "NCT06128629", "NCT05120830", "NCT06622668", "NCT06634420", "NCT06662188", "NCT05811351", "NCT04671433",
                    "NCT02354781", "NCT04819841", "NCT06280378", "NCT03333590", "NCT01482195", "NCT05735158", "NCT05504837", "NCT06049082",
                    "NCT04917874", "NCT02852213", "NCT06191354", "NCT04273269", "NCT03612869", "NCT06308159", "NCT06288230", "NCT04797260",
                    "NCT03634007", "NCT06109181", "NCT05445323", "NCT06818838", "NCT01494805", "NCT04581785", "NCT05040217", "NCT04774536",
                    "NCT03818763", "NCT04240314", "NCT05152823", "NCT03001310", "NCT03758404", "NCT02781480", "NCT05603312", "NCT05926765",
                    "NCT04833907", "NCT06706427", "NCT06332807", "NCT05417126", "NCT04945772", "NCT01496040", "NCT03952637", "NCT06325709",
                    "NCT01306019", "NCT00028236", "NCT06253507", "NCT06851767", "NCT00394316", "NCT00372320", "NCT02362438", "NCT01519349",
                    "NCT00494195", "NCT00428935", "NCT05984927", "NCT03562494", "NCT05228145", "NCT05898620", "NCT05293626", "NCT05820152",
                    "NCT03363165", "NCT02132130", "NCT05073133", "NCT04443907", "NCT03374657", "NCT06018558", "NCT06388200", "NCT05956626",
                    "NCT05616793", "NCT06149403", "NCT04283227", "NCT05901480", "NCT04903288", "NCT04119687", "NCT04747431", "NCT06392724",
                    "NCT06749639", "NCT06645197", "NCT05805007", "NCT04281485", "NCT04370054", "NCT03861273", "NCT06680232", "NCT04127578",
                    "NCT04411654", "NCT04408625", "NCT06559176", "NCT03566043", "NCT02651675", "NCT05693142", "NCT03580083", "NCT06460844",
                    "NCT05788536", "NCT06511349", "NCT06092034", "NCT04105166", "NCT04525352", "NCT05885412", "NCT03812263", "NCT06422351",
                    "NCT04248439", "NCT02610582", "NCT04611503", "NCT02702115", "NCT03432364", "NCT04046224", "NCT03041324", "NCT00876863",
                    "NCT02695160", "NCT00985517", "NCT05972629", "NCT06844214", "NCT06061549", "NCT06224660", "NCT04703842", "NCT06246513",
                    "NCT05906251", "NCT01976091", "NCT05881408", "NCT06747273", "NCT06370351", "NCT06474442", "NCT06465550", "NCT05203679",
                    "NCT06111638", "NCT06022744", "NCT05765981", "NCT06641895", "NCT06141460", "NCT05441553", "NCT06217861", "NCT06699108",
                    "NCT05694598", "NCT06432140", "NCT06480461", "NCT03217617", "NCT03645460", "NCT02559830", "NCT03720418", "NCT05986864",
                    "NCT06138639", "NCT03368742", "NCT05748873", "NCT02341807", "NCT00999609", "NCT06297486", "NCT04093349", "NCT06826612",
                    "NCT03734588", "NCT06526923", "NCT04040049", "NCT05324943", "NCT05164471", "NCT06506461", "NCT00979238", "NCT06293729",
                    "NCT05394064", "NCT06152237", "NCT05606614", "NCT05836259", "NCT06228924", "NCT04669535", "NCT05791864", "NCT06107400",
                    "NCT05345171", "NCT04884815", "NCT02618915", "NCT02716246", "NCT05139316", "NCT06063850", "NCT06100276", "NCT06270316",
                    "NCT05243017", "NCT03300453", "NCT03001830", "NCT05092685", "NCT04601974", "NCT00643747", "NCT02234934", "NCT05432310",
                    "NCT03897361", "NCT03538899", "NCT00976352", "NCT02240407", "NCT05665166", "NCT04201405", "NCT00927134", "NCT02317887",
                    "NCT06291935", "NCT05477563", "NCT06164730", "NCT06451770", "NCT05398029", "NCT04537377", "NCT06237777", "NCT01161576",
                    "NCT06345898", "NCT06114056", "NCT06519552", "NCT06300476", "NCT04124042", "NCT06302608", "NCT05822739", "NCT06663878",
                    "NCT06272149", "NCT04125732", "NCT06831825", "NCT06722170", "NCT06539208", "NCT06292650", "NCT06066008", "NCT03207009",
                    "NCT03852498", "NCT04293185", "NCT03328130", "NCT06255782"
            );
            */

            List<String> processed = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            List<String> overwritten = new ArrayList<>();

            for (String nctId : nctIds) {
                try {
                    if (nctId == null || nctId.trim().isEmpty()) {
                        LOG.warn("Skipping empty nctId");
                        continue;
                    }

                    nctId = nctId.trim();
                    String url = "https://stage.scge.mcw.edu/platform/data/report/clinicalTrials/" + nctId;

                    LOG.info("Processing trial: {}", nctId);

                    // Check if already exists in vector store
                    // Use same filename format as extractFilenameFromUrl() for clinical trials
                    List<DocumentEmbeddingOpenAI> existing = repository.findByFileName("CLINICAL TRIAL: " + nctId);
                    boolean isOverwrite = !existing.isEmpty();

                    // If exists, delete existing entries first
                    if (isOverwrite) {
                        for (DocumentEmbeddingOpenAI doc : existing) {
                            repository.delete(doc);
                        }
                        LOG.info("Deleted {} existing entries for trial: {}", existing.size(), nctId);
                        overwritten.add(nctId);
                    }

                    ResponseEntity<?> result = processUrlInternal(url);

                    if (result.getStatusCode().is2xxSuccessful()) {
                        processed.add(nctId);
                        LOG.info("Successfully processed trial: {} ({})", nctId, isOverwrite ? "overwritten" : "new");
                    } else {
                        failed.add(nctId);
                        LOG.error("Failed to process trial: {}", nctId);
                    }

                } catch (Exception e) {
                    String safeNctId = nctId != null ? nctId.trim() : "unknown";
                    failed.add(safeNctId);
                    LOG.error("Exception processing trial: {}", safeNctId, e);
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("total", nctIds.size());
            response.put("processed", processed.size());
            response.put("overwritten", overwritten.size());
            response.put("failed", failed.size());
            response.put("processedList", processed);
            response.put("overwrittenList", overwritten);
            response.put("failedList", failed);

            LOG.info("Clinical trials processing complete. Total: {}, Processed: {}, Overwritten: {}, Failed: {}",
                    nctIds.size(), processed.size(), overwritten.size(), failed.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            LOG.error("Error during clinical trials loading", e);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to load clinical trials: " + e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    private ResponseEntity<?> processUrlInternal(String urlString) {
        try {
            // Fetch content from URL
            UrlDocumentReader documentReader = new UrlDocumentReader(urlString);
            List<Document> documents = documentReader.get();

            if (documents.isEmpty()) {
                LOG.error("Failed to fetch content from URL: {}", urlString);
                Map<String, String> response = new HashMap<>();
                response.put("error", "Failed to fetch content from the provided URL");
                return ResponseEntity.badRequest().body(response);
            }

            // Fix the metadata issue - add filename by creating new documents with mutable metadata
            List<Document> documentsWithFilename = documents.stream()
                    .map(doc -> {
                        Map<String, Object> mutableMetadata = new HashMap<>(doc.getMetadata());
                        mutableMetadata.put("filename", extractFilenameFromUrl(urlString));
                        return new Document(doc.getContent(), mutableMetadata);
                    })
                    .collect(Collectors.toList());

            documents = documentsWithFilename;

            // STEP 1: Universal preprocessing for ANY document type
            List<Document> preprocessedDocs = preprocessor.preprocessDocuments(documents);
            LOG.debug("Preprocessed into {} clean documents", preprocessedDocs.size());

            if (preprocessedDocs.isEmpty()) {
                LOG.error("No usable content after preprocessing");
                throw new RuntimeException("Document preprocessing failed - no usable content found");
            }

            // STEP 2: Split into chunks with correct Spring AI settings
            TokenTextSplitter splitter = TokenTextSplitter.builder()
                    .withChunkSize(800)                // Target chunk size in tokens
                    .withMinChunkSizeChars(200)        // Minimum characters per chunk
                    .withMinChunkLengthToEmbed(50)     // Minimum length to embed
                    .withMaxNumChunks(10000)           // Maximum number of chunks
                    .withKeepSeparator(true)           // Keep separators for readability
                    .build();

            List<Document> splitDocuments = splitter.apply(preprocessedDocs);
            LOG.debug("Split into {} chunks after preprocessing", splitDocuments.size());

            // Add to OpenAI vector store
            openaiVectorStore.add(splitDocuments);
            LOG.debug("Successfully added {} URL chunks to OpenAI vector store", splitDocuments.size());

            Map<String, Object> response = new HashMap<>();
            response.put("url", urlString);
            response.put("title", documents.get(0).getMetadata().getOrDefault("title", "Unknown"));
            response.put("chunkCount", splitDocuments.size());
            response.put("vectorStore", "OpenAI");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            LOG.error("Error processing URL: {}", urlString, e);
            Map<String, String> response = new HashMap<>();
            response.put("error", "Failed to process URL: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    private String extractFilenameFromUrl(String urlString) {
        try {
            URL url = new URL(urlString);
            String path = url.getPath();

            // Check if this is a clinical trial URL
            boolean isClinicalTrialUrl = urlString.contains("/clinicalTrials/report/") ||
                                         urlString.contains("/report/clinicalTrials/");

            String filename;
            if (path != null && !path.isEmpty() && !path.equals("/")) {
                String[] pathParts = path.split("/");
                String lastPart = pathParts[pathParts.length - 1];
                if (!lastPart.isEmpty()) {
                    filename = lastPart;
                } else {
                    filename = url.getHost().replaceAll("\\.", "_");
                }
            } else {
                filename = url.getHost().replaceAll("\\.", "_");
            }

            // For clinical trials, prepend "CLINICAL TRIAL: " to the NCTID
            // For other URLs, append the full URL for uniqueness
            if (isClinicalTrialUrl) {
                return "CLINICAL TRIAL: " + filename;  // "CLINICAL TRIAL: NCT06285643"
            } else {
                return filename + ":" + urlString;  // "page_name:https://example.com/page"
            }

        } catch (MalformedURLException e) {
            return "webpage_" + System.currentTimeMillis() + ":" + urlString;
        }
    }
}
