const addToTranscript = (who, text) => {
    const transcript = document.querySelector('#transcript');
    const name = (who === "User") ? username : who;

    // Create the new entry element
    const tempDiv = document.createElement('div');
    tempDiv.innerHTML = createTranscriptEntry(who, name, text);
    const newEntry = tempDiv.firstElementChild;

    // Append the new entry
    transcript.appendChild(newEntry);

    // Only scroll to beginning of AI responses (within transcript container only)
    if (who === "AI") {
        transcript.scrollTop = newEntry.offsetTop - transcript.offsetTop;
    }
};

const createTranscriptEntry = (who, name, text) => {
    const modelBadge = (who === "AI") ? '<span style="background: #00a67e; color: white; padding: 2px 6px; border-radius: 10px; font-size: 0.8em; margin-left: 5px;">OpenAI</span>' : '';
    // <div><b>${name}:</b> ${modelBadge} ${text}</div>
    return `
    <div class="${who}Entry">
        <div><b>${name}:</b> ${text}</div>
    </div>`;
};

const handleResponse = (response) => {
    // Hide typing indicator
    hideTypingIndicator();

    // Convert markdown to HTML first (handles **bold**, lists, line breaks, etc.)
    let enhancedAnswer = marked.parse(response.answer);

    enhancedAnswer = convertNCTToLinks(enhancedAnswer);
    enhancedAnswer = convertMdToLinks(enhancedAnswer);
    enhancedAnswer = boldSourcesUsed(enhancedAnswer);
    enhancedAnswer = cleanupClinicalTrialSources(enhancedAnswer);
    addToTranscript("AI", enhancedAnswer);
};
// Function to convert NCT IDs to clickable links
const convertNCTToLinks = (text) => {
    // Pattern to match NCT IDs (NCT followed by 8 digits)
    const nctPattern = /\b(NCT\d{8})\b/g;

    return text.replace(nctPattern, (match, nctId) => {
        const url = `https://scge.mcw.edu/platform/data/report/clinicalTrials/${nctId}`;
        return `<a href="${url}" target="_blank">${nctId}</a>`;
    });
};

// Function to convert .md filenames to clickable PDF links
const convertMdToLinks = (text) => {
    // Pattern to match filenames ending with .md
    // Matches: letters, numbers, underscores, hyphens, parentheses, dots followed by .md
    // NO spaces allowed - prevents matching regular sentence text
    const mdPattern = /\b([A-Za-z0-9][A-Za-z0-9_\-\(\)\.]*\.md)\b/g;

    return text.replace(mdPattern, (match, filename) => {
        // Trim leading/trailing spaces from filename
        filename = filename.trim();
        // Remove .md extension and add .pdf
        const baseFilename = filename.slice(0, -3); // Remove '.md'
        const pdfFilename = baseFilename + '.pdf';

        // URL encode the filename to handle spaces and special characters
        const encodedFilename = encodeURIComponent(pdfFilename);

        // Check if running on localhost and redirect to dev server for PDFs
        let basePath;
        if (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') {
            basePath = 'https://dev.scge.mcw.edu/assistant';
        } else {
            basePath = contextPath;
        }

        // Use basePath for URL
        const url = `${basePath}/docs/${encodedFilename}`;

        // Display the filename without .md extension
        return `<a href="${url}" target="_blank">${baseFilename}</a>`;
    });
};

// Function to make "SOURCES_USED:" bold and format properly
const boldSourcesUsed = (text) => {
    // Add line break before SOURCES_USED if it's not already on its own line
    // Also add spaces after commas in the sources list
    return text.replace(/([^\n])\s*SOURCES_USED:\s*/g, '$1<strong class="sources-label">SOURCES USED:</strong> ')
               .replace(/SOURCES_USED:\s*/g, '<strong class="sources-label">SOURCES USED:</strong> ')
               .replace(/,(?=\S)/g, ', '); // Add space after comma if there isn't one
};

// Function to remove "CLINICAL TRIAL:" prefix from sources
const cleanupClinicalTrialSources = (text) => {
    // Remove "CLINICAL TRIAL: " from the sources section
    return text.replace(/CLINICAL TRIAL:\s*/g, '');
};

// Show typing indicator
const showTypingIndicator = () => {
    const transcript = document.querySelector('#transcript');

    // Create typing indicator HTML
    const indicatorHTML = `
        <div id="typingIndicator" class="typing-indicator active">
            <div class="typing-dots">
                <span>thinking</span>
                <div class="dot"></div>
                <div class="dot"></div>
                <div class="dot"></div>
            </div>
        </div>
    `;

    // Append it to transcript
    transcript.innerHTML += indicatorHTML;

    // Scroll to show the typing indicator
    transcript.scrollTop = transcript.scrollHeight;
};

// Hide typing indicator
const hideTypingIndicator = () => {
    const indicator = document.getElementById('typingIndicator');
    if (indicator) {
        indicator.remove();
    }
};

// API Interactions - OpenAI Endpoints
const postQuestion = (question) => {
    // Show typing indicator
    showTypingIndicator();

    fetch(contextPath + "/chat-openai", {
        method: "POST",
        headers: {
            "Content-Type": "application/json"
        },
        body: JSON.stringify({ question: question })
    })
        .then(res => res.json())
        .then(handleResponse)
        .catch(error => {
            console.error('Error:', error);
            hideTypingIndicator();
            addToTranscript("AI", "Sorry, there was an error processing your request with OpenAI.");
        });
};

const processUrl = (url) => {
    fetch(contextPath + "/process-url", {
        method: "POST",
        headers: {
            "Content-Type": "application/json"
        },
        body: JSON.stringify({ url: url })
    })
        .then(res => {
            if (!res.ok) {
                return res.json().then(errorData => {
                    throw new Error(errorData.error || `HTTP error! Status: ${res.status}`);
                });
            }
            return res.json();
        })
        .then(data => {
            document.getElementById("urlLoader").style.visibility = "hidden";
            document.getElementById("urlModal").style.display = "none";
            addToTranscript("SCGE", `Processed URL: ${data.url} (${data.chunkCount} chunks extracted from "${data.title}")`);
        })
        .catch(error => {
            console.error('Error processing URL:', error);
            document.getElementById("urlLoader").style.visibility = "hidden";
            addToTranscript("SCGE", `Error processing URL: ${error.message}`);
        });
};

// Event Handlers
const submitTypedText = (event) => {
    const typedTextInput = document.querySelector('#userInput');
    const typedText = typedTextInput.value.trim();

    if (typedText.length === 0) {
        return false;
    }

    addToTranscript("User", typedText);
    postQuestion(typedText);
    typedTextInput.value = '';
    return false;
};

const startOverChat = () => {
    if (confirm("Are you sure you want to start over? This will clear the AI's memory of our conversation.")) {
        // Disable button during request
        const startOverBtn = document.getElementById("startOverBtn");
        startOverBtn.disabled = true;
        startOverBtn.textContent = "Starting over...";

        fetch(contextPath + "/chat-openai/reset-memory", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            }
        })
            .then(res => res.json())
            .then(data => {
                if (data.status === "success") {
                    addToTranscript("SCGE", "Chat memory cleared - starting fresh conversation");
                } else {
                    addToTranscript("SCGE", "Error: " + data.message);
                }
            })
            .catch(error => {
                console.error('Error:', error);
                addToTranscript("SCGE", "Error clearing chat memory");
            })
            .finally(() => {
                // Re-enable button
                startOverBtn.disabled = false;
                startOverBtn.textContent = "Clear memory";
            });
    }
};

// Initialize UI Events
const initUIEvents = () => {
    // Submit button click
    const submitButton = document.querySelector('#typedTextSubmit');
    submitButton.addEventListener('click', submitTypedText);

    // Enter key in textarea
    const textarea = document.querySelector('#userInput');
    textarea.addEventListener('keydown', e => {
        if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            submitTypedText(e);
        }
    });

    // File upload modal
    const modal = document.getElementById("uploadModal");
    const openModalBtn = document.getElementById("uploadFile");
    const closeModalSpan = document.getElementsByClassName("closeModalSpan")[0];
    if (openModalBtn) {
        openModalBtn.addEventListener('click', () => {
            modal.style.display = "block";
        });
    }
    if (closeModalSpan) {
        closeModalSpan.addEventListener('click', () => {
            modal.style.display = "none";
        });
    }

    // URL modal
    const urlModal = document.getElementById("urlModal");
    const openUrlModalBtn = document.getElementById("processUrl");
    const closeUrlModalSpan = document.getElementsByClassName("closeUrlModalSpan")[0];
    if (openUrlModalBtn) {
        openUrlModalBtn.addEventListener('click', () => {
            urlModal.style.display = "block";
        });
    }
    if (closeUrlModalSpan) {
        closeUrlModalSpan.addEventListener('click', () => {
            urlModal.style.display = "none";
        });
    }

    // URL form submission
    const urlForm = document.getElementById("urlForm");
    urlForm.addEventListener('submit', (e) => {
        e.preventDefault();
        const urlInput = document.getElementById("urlInput");
        const url = urlInput.value.trim();

        if (url) {
            document.getElementById("urlLoader").style.visibility = "visible";
            processUrl(url);
            urlInput.value = '';
        }
    });

    // Close modals when clicking outside
    window.addEventListener('click', (event) => {
        if (event.target === modal) {
            modal.style.display = "none";
        }
        if (event.target === urlModal) {
            urlModal.style.display = "none";
        }
    });

    // File upload handling
    const uploadForm = document.getElementById("uploadForm");
    uploadForm.addEventListener('submit', () => {
        const filename = uploadForm.elements[0].value;
        if (filename && filename.length > 0) {
            document.getElementById("loader").style.visibility = "visible";
        }
    });

    const hiddenUploadFrame = document.getElementById("hiddenUploadFrame");
    hiddenUploadFrame.addEventListener('load', () => {
        try {
            const response = hiddenUploadFrame.contentDocument.body.innerText;
            if (response) {
                const json = JSON.parse(response);
                const fileName = json.fileName;
                document.getElementById("loader").style.visibility = "hidden";
                modal.style.display = "none";
                addToTranscript("SCGE", `Uploaded file: ${fileName} (${json.fileSize} bytes) to OpenAI vector store`);
                uploadForm.reset();
            }
        } catch (e) {
            console.error('Error processing upload response:', e);
        }
    });
    // Load Clinical Trials button
    const loadTrialsBtn = document.getElementById("loadTrials");
    if (loadTrialsBtn) {
        loadTrialsBtn.addEventListener('click', () => {
                loadTrialsBtn.disabled = true;
                loadTrialsBtn.textContent = "Loading Trials...";

                addToTranscript("SCGE", "Starting clinical trials loading process...");

                fetch(contextPath + "/load-clinical-trials", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/json"
                    }
                })
                    .then(res => res.json())
                    .then(data => {
                        if (data.error) {
                            addToTranscript("SCGE", `Error: ${data.error}`);
                        } else {
                            const message = `Clinical trials loading complete!\n` +
                                `Total: ${data.total}\n` +
                                `Processed: ${data.processed}\n` +
                                `Overwritten: ${data.overwritten}\n` +
                                `Failed: ${data.failed}`;

                            addToTranscript("SCGE", message);

                            if (data.failedList && data.failedList.length > 0) {
                                addToTranscript("SCGE", `Failed trials: ${data.failedList.join(', ')}`);
                            }
                        }
                    })
                    .catch(error => {
                        console.error('Error loading clinical trials:', error);
                        addToTranscript("SCGE", "Error loading clinical trials: " + error.message);
                    })
                    .finally(() => {
                        loadTrialsBtn.disabled = false;
                        loadTrialsBtn.textContent = "Load Clinical Trials";
                    });
        });
    }

    // Welcome message
    addToTranscript("SCGE", "Welcome to SCGE Platform AI Assistant! I can answer questions about FDA guidance documents or gene therapy clinical trials.");

    const startOverBtn = document.getElementById('startOverBtn');
    startOverBtn.addEventListener('click',startOverChat);
};

// Disclaimer toggle
const initDisclaimer = () => {
    const disclaimer = document.getElementById('disclaimer');
    const disclaimerHeader = document.getElementById('disclaimerHeader');

    disclaimerHeader.addEventListener('click', () => {
        disclaimer.classList.toggle('collapsed');
    });
};

// Initialize everything when DOM is loaded
window.addEventListener('load', () => {
    initUIEvents();
    initDisclaimer();
});