const addToTranscript = (who, text) => {
    const transcript = document.querySelector('#transcript');
    const name = (who === "User") ? username : who;
    transcript.innerHTML += createTranscriptEntry(who, name, text);
    transcript.scrollTop = transcript.scrollHeight;
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
    // Convert NCT IDs and .md filenames to links before adding to transcript
    let enhancedAnswer = convertNCTToLinks(response.answer);
    enhancedAnswer = convertMdToLinks(enhancedAnswer);
    enhancedAnswer = boldSourcesUsed(enhancedAnswer);
    addToTranscript("AI", enhancedAnswer);
    // addToTranscript("AI", response.answer);
};
// Function to convert NCT IDs to clickable links
const convertNCTToLinks = (text) => {
    // Pattern to match NCT IDs (NCT followed by 8 digits)
    const nctPattern = /\b(NCT\d{8})\b/g;

    return text.replace(nctPattern, (match, nctId) => {
        const url = `https://scge.mcw.edu/platform/data/clinicalTrials/report/${nctId}`;
        return `<a href="${url}" target="_blank">${nctId}</a>`;
    });
};

// Function to convert .md filenames to clickable PDF links
const convertMdToLinks = (text) => {
    // Pattern to match filenames ending with .md
    // Matches: word characters, spaces, hyphens, underscores, and other common filename chars followed by .md
    const mdPattern = /([A-Za-z0-9_\-\s\(\)\.]+\.md)\b/g;

    return text.replace(mdPattern, (match, filename) => {
        // Remove .md extension and add .pdf
        const baseFilename = filename.slice(0, -3); // Remove '.md'
        const pdfFilename = baseFilename + '.pdf';

        // URL encode the filename to handle spaces and special characters
        const encodedFilename = encodeURIComponent(pdfFilename);

        // Check if running on localhost and redirect to dev server for PDFs
        let basePath;
        if (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') {
            basePath = 'https://dev.scge.mcw.edu/spring-ai-chat-rag';
        } else {
            basePath = contextPath;
        }

        // Use basePath for URL
        const url = `${basePath}/docs/${encodedFilename}`;

        // Display the filename without .md extension
        return `<a href="${url}" target="_blank">${baseFilename}</a>`;
    });
};

// Function to make "SOURCES_USED:" bold
const boldSourcesUsed = (text) => {
    return text.replace(/SOURCES_USED:/g, '<strong>SOURCES USED:</strong>');
};

// API Interactions - OpenAI Endpoints
const postQuestion = (question) => {
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
            addToTranscript("System", `Processed URL: ${data.url} (${data.chunkCount} chunks extracted from "${data.title}")`);
        })
        .catch(error => {
            console.error('Error processing URL:', error);
            document.getElementById("urlLoader").style.visibility = "hidden";
            addToTranscript("System", `Error processing URL: ${error.message}`);
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
                    addToTranscript("System", "Chat memory cleared - starting fresh conversation");
                } else {
                    addToTranscript("System", "Error: " + data.message);
                }
            })
            .catch(error => {
                console.error('Error:', error);
                addToTranscript("System", "Error clearing chat memory");
            })
            .finally(() => {
                // Re-enable button
                startOverBtn.disabled = false;
                startOverBtn.textContent = "Start over";
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
                addToTranscript("System", `Uploaded file: ${fileName} (${json.fileSize} bytes) to OpenAI vector store`);
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

                addToTranscript("System", "Starting clinical trials loading process...");

                fetch(contextPath + "/load-clinical-trials", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/json"
                    }
                })
                    .then(res => res.json())
                    .then(data => {
                        if (data.error) {
                            addToTranscript("System", `Error: ${data.error}`);
                        } else {
                            const message = `Clinical trials loading complete!\n` +
                                `Total: ${data.total}\n` +
                                `Processed: ${data.processed}\n` +
                                `Overwritten: ${data.overwritten}\n` +
                                `Failed: ${data.failed}`;

                            addToTranscript("System", message);

                            if (data.failedList && data.failedList.length > 0) {
                                addToTranscript("System", `Failed trials: ${data.failedList.join(', ')}`);
                            }
                        }
                    })
                    .catch(error => {
                        console.error('Error loading clinical trials:', error);
                        addToTranscript("System", "Error loading clinical trials: " + error.message);
                    })
                    .finally(() => {
                        loadTrialsBtn.disabled = false;
                        loadTrialsBtn.textContent = "Load Clinical Trials";
                    });
        });
    }

    // Welcome message
    addToTranscript("System", "Welcome to SCGE Platform AI Assistant! Ask questions about the fda documents, clinical trials etc.");

    const startOverBtn = document.getElementById('startOverBtn');
    startOverBtn.addEventListener('click',startOverChat);
};

// Initialize everything when DOM is loaded
window.addEventListener('load', initUIEvents);