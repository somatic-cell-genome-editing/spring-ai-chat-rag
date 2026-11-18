package edu.mcw.rgdai.reader;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.springframework.ai.document.DocumentReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.*;


public class UrlDocumentReader implements DocumentReader {
    private static final Logger LOG = LoggerFactory.getLogger(UrlDocumentReader.class);
    private final String url;
    private final int timeout;

    public UrlDocumentReader(String url) {
        this(url, 30000);
    }

    public UrlDocumentReader(String url, int timeout) {
        this.url = url;
        this.timeout = timeout;
    }

    @Override
    public List<org.springframework.ai.document.Document> get() {
        try {
            LOG.info("Fetching content from URL: {}", url);
            Document jsoupDoc = Jsoup.connect(url)
                    .timeout(timeout)
                    .get();

            // Remove junk elements
            jsoupDoc.select("script, style, iframe, noscript, nav, footer, .navbar, #messageVue, .chat-popup").remove();

            // Detect page type and extract accordingly
            String content = extractContent(jsoupDoc);

            String title = jsoupDoc.title();

            Map<String, Object> metadata = Map.of(
                    "source", url,
                    "title", title,
                    "type", "url"
            );

            LOG.info("Successfully fetched content from URL: {}, content length: {} chars",
                    url, content.length());

            return List.of(new org.springframework.ai.document.Document(content, metadata));
        }
        catch (IOException e) {
            LOG.error("Failed to fetch URL content: {}", url, e);
            return Collections.emptyList();
        }
    }

    /**
     * Detect page type and route to appropriate extractor
     */
    private String extractContent(Document doc) {
        if (isClinicalTrialPage(doc)) {
            LOG.info("Detected clinical trial page, using specialized extractor");
            return extractClinicalTrialContent(doc);
        } else {
            LOG.info("Generic webpage detected, using generic extractor");
            return extractGenericContent(doc);
        }
    }

    /**
     * Detect if this is a clinical trial page
     */
    private boolean isClinicalTrialPage(Document doc) {
        return doc.select("table.ctReportTable").size() > 0 ||
               doc.select("h3.ctSubHeading").size() > 0 ||
               doc.select("h2.brief-title").size() > 0 ||
               url.contains("/clinicalTrials/report/") ||
               url.contains("/report/clinicalTrials/");
    }

    /**
     * Extract structured content from clinical trial pages
     */
    private String extractClinicalTrialContent(Document doc) {
        StringBuilder content = new StringBuilder();

        // Remove navigation elements (but NOT form - data is inside form!)
        doc.select(".sidenav").remove();

        // Extract NCTID
        String nctId = extractNCTID(doc);
        LOG.debug("Extracted NCTID: {}", nctId);
        if (nctId != null) {
            content.append("--- CLINICAL TRIAL: ").append(nctId).append(" ---\n\n");
        }

        // Extract title
        Element mainHeading = doc.select("h2.brief-title").first();
        if (mainHeading != null) {
            String title = mainHeading.text().trim();
            LOG.debug("Extracted title: {}", title);
            if (!title.isEmpty()) {
                content.append("Title: ").append(title).append("\n\n");
            }
        } else {
            LOG.warn("No h2.brief-title found");
        }

        // Extract all sections with their tables
        Elements sections = doc.select("div.dynamic-heading");
        LOG.debug("Found {} sections", sections.size());

        for (Element sectionDiv : sections) {
            Element sectionHeading = sectionDiv.select("h3.ctSubHeading").first();
            if (sectionHeading != null) {
                String sectionTitle = sectionHeading.text().trim();
                LOG.debug("Processing section: {}", sectionTitle);
                if (!sectionTitle.isEmpty() && !sectionTitle.equalsIgnoreCase("Summary")) {
                    content.append("\n=== ").append(sectionTitle).append(" ===\n");
                }
            }

            // Find tables after this section
            Element nextElement = sectionDiv.nextElementSibling();
            int tableCount = 0;
            while (nextElement != null) {
                if (nextElement.hasClass("dynamic-heading")) {
                    break;
                }

                if (nextElement.tagName().equals("table") && nextElement.hasClass("ctReportTable")) {
                    String tableData = extractTableData(nextElement);
                    LOG.debug("Extracted {} chars from table", tableData.length());
                    content.append(tableData);
                    tableCount++;
                }

                nextElement = nextElement.nextElementSibling();
            }
            LOG.debug("Extracted {} tables for section", tableCount);
        }

        // Extract external links
        String links = extractExternalLinks(doc);
        LOG.debug("Extracted {} chars from external links", links.length());
        content.append(links);

        String result = content.toString().trim();
        LOG.info("Total extracted content length: {} chars", result.length());
        return result;
    }

    // Repeated lines at least this long are dropped as duplicate tab/toggle copies
    private static final int MIN_DEDUPLICATED_LINE = 80;

    // Elements that start a new line of text in the extracted content
    private static final Set<String> BLOCK_TAGS = Set.of(
            "address", "article", "aside", "blockquote", "br", "dd", "div", "dl", "dt", "fieldset",
            "figcaption", "figure", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "li", "main", "ol",
            "p", "pre", "section", "table", "tbody", "tfoot", "thead", "tr", "ul");

    /**
     * Extract content from generic webpages. Walks every text node of the main content in page
     * order and starts a new line at each block element, so text is kept wherever the page
     * builder puts it - plain divs, collapsible sections and tabs included (e.g. the Avada
     * theme on scge.mcw.edu). Table cells are joined with " | "; long repeated lines (tabs
     * often have a desktop and a mobile copy) are kept once.
     */
    private String extractGenericContent(Document doc) {
        Element mainContent = doc.selectFirst("main, #main, article, .post-content, .entry-content, #content, .content");
        if (mainContent == null) {
            mainContent = doc.body();
        }
        mainContent = mainContent.clone();
        mainContent.select("header, aside, form, button, .sidebar, #sidebar, .breadcrumbs, "
                + ".fusion-sharing-box, .related-posts, .screen-reader-text, .fusion-mobile-tab-nav").remove();

        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode textNode) {
                    line.append(textNode.text());
                } else if (node instanceof Element element) {
                    String tag = element.tagName();
                    if (tag.equals("td") || tag.equals("th")) {
                        if (!line.toString().isBlank()) {
                            line.append(" | ");
                        }
                    } else if (BLOCK_TAGS.contains(tag)) {
                        addLine(lines, line, false);
                        if (tag.equals("li")) {
                            line.append("- ");
                        }
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element element && BLOCK_TAGS.contains(element.tagName())) {
                    addLine(lines, line, element.tagName().matches("h[1-6]"));
                }
            }
        }, mainContent);
        addLine(lines, line, false);

        StringBuilder content = new StringBuilder();
        Set<String> seenLines = new HashSet<>();
        for (String text : lines) {
            // Only long repeats are tab/toggle copies; short labels ("Advocacy Groups:") repeat legitimately
            if (text.length() < MIN_DEDUPLICATED_LINE || seenLines.add(text)) {
                content.append(text.endsWith(":") && !text.startsWith("- ") ? "\n" : "").append(text).append("\n");
            }
        }

        String result = content.toString().trim();
        if (result.length() < 100) {
            result = mainContent.text();
        }

        return result;
    }

    private static void addLine(List<String> lines, StringBuilder line, boolean heading) {
        String text = line.toString().replaceAll("\\s+", " ").trim();
        line.setLength(0);
        if (text.isEmpty() || text.equals("-")) {
            return;
        }
        if (heading && !text.endsWith(":")) {
            text = text + ":";
        }
        lines.add(text);
    }

    /**
     * Extract NCTID
     */
    private String extractNCTID(Document doc) {
        Elements rows = doc.select("table.ctReportTable tr");
        for (Element row : rows) {
            Elements cells = row.select("td");
            if (cells.size() >= 2) {
                String label = cells.get(0).text().trim();
                if (label.equalsIgnoreCase("NCTID")) {
                    String value = cells.get(1).text();
                    return value.replaceAll("\\(.*?\\)", "").trim();
                }
            }
        }
        return null;
    }

    /**
     * Extract ALL data from clinical trial table - no filtering
     */
    private String extractTableData(Element table) {
        StringBuilder tableContent = new StringBuilder();
        Elements rows = table.select("tr");

        for (Element row : rows) {
            Elements cells = row.select("td");

            if (cells.size() >= 2) {
                String label = cells.get(0).text().trim();
                String value = cells.get(1).text().trim();

                // Clean up common junk from values
                value = value.replaceAll("\\(View at.*?\\)", "").trim();
                value = value.replaceAll("\\(Click here for.*?\\)", "").trim();

                // Output EVERYTHING - even if value is empty
                if (!label.isEmpty()) {
                    tableContent.append(label).append(": ").append(value).append("\n");
                }
            }
        }

        return tableContent.toString();
    }


    /**
     * Extract external links grouped by type
     */
    private String extractExternalLinks(Document doc) {
        StringBuilder linksContent = new StringBuilder();
        Elements linkHeadings = doc.select("h5.link-type-heading");

        if (linkHeadings.isEmpty()) {
            return "";
        }

        linksContent.append("\n=== Resources/Links ===\n");

        for (Element heading : linkHeadings) {
            String linkType = heading.text().trim();
            if (!linkType.isEmpty()) {
                linksContent.append("\n").append(linkType).append(":\n");

                Element listElement = heading.nextElementSibling();
                if (listElement != null && listElement.tagName().equals("ul")
                        && listElement.hasClass("external-links-list")) {

                    Elements listItems = listElement.select("li");
                    for (Element li : listItems) {
                        String itemText = li.text().trim();
                        if (!itemText.isEmpty()) {
                            linksContent.append("- ").append(itemText).append("\n");
                        }
                    }
                }
            }
        }

        return linksContent.toString();
    }
}