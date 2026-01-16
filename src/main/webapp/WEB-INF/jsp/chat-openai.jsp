<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ page import="org.springframework.security.core.context.SecurityContextHolder,
                  org.springframework.security.core.Authentication" %>
<!DOCTYPE html>
<html>
<head>
    <title>SCGE Chatbot</title>
    <%
        // Obtain the authentication object and username from the Spring Security context.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
//        String username = (auth != null) ? auth.getName() : "";
        String username = "User";
        String contextPath = request.getContextPath();
    %>
    <link rel="stylesheet" href="<%= contextPath %>/resources/css/style.css"/>
    <style>
        /* OpenAI-specific styling */
        .openai-badge {
            display: inline-block;
            background: #00a67e;
            color: white;
            padding: 2px 8px;
            border-radius: 12px;
            font-size: 0.8em;
            margin-left: 10px;
        }
        .model-switch {
            position: absolute;
            top: 15px;
            right: 15px;
        }
        .model-switch a {
            background: #28a745;
            color: white;
            padding: 8px 16px;
            text-decoration: none;
            border-radius: 6px;
            font-size: 0.9em;
        }
        .model-switch a:hover {
            background: #218838;
        }
        /* Start Over Button Styles */
        .start-over-section {
            padding: 10px 20px;
            display: flex;
            justify-content: flex-end;
            border-top: 1px solid #eee;
        }

        .start-over-btn {
            background-color: transparent;
            color: #28a745;
            padding: 8px 16px;
            border: 1px solid #28a745;
            border-radius: 20px;
            cursor: pointer;
            font-size: 14px;
            transition: all 0.3s ease;
        }

        .start-over-btn:hover {
            background-color: #28a745;
            color: white;
        }

        .start-over-btn:disabled {
            opacity: 0.5;
            cursor: not-allowed;
        }

        .start-over-btn:disabled:hover {
            background-color: transparent;
            color: #28a745;
        }
    </style>
    <!-- Markdown parser for AI responses -->
    <script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
    <script>
        var username = "<%= username %>";
        var contextPath = "<%= contextPath %>";
    </script>
    <script src="<%= contextPath %>/resources/js/script-openai.js"></script>
</head>
<body>
<div class="chat-container">
    <!-- Upload Modal -->
    <div id="uploadModal" class="modal">
        <div class="modal-content">
            <span class="closeModalSpan">&times;</span>
            <h2>Upload a file to OpenAI</h2>
            <form id="uploadForm" method="post" action="<%= contextPath %>/upload-openai" enctype="multipart/form-data" target="hiddenUploadFrame">
                <input type="file" name="file" id="file" required/>
                <input type="submit" value="Upload" class="submit-btn"/>
            </form>
            <div class="loader" id="loader">
                <div class="loading-spinner"></div>
            </div>
        </div>
    </div>

    <!-- URL Modal -->
    <div id="urlModal" class="modal">
        <div class="modal-content">
            <span class="closeUrlModalSpan">&times;</span>
            <h2>Process Website URL</h2>
            <form id="urlForm">
                <input type="url" name="url" id="urlInput" placeholder="https://example.com" required/>
                <input type="submit" value="Process" class="submit-btn"/>
            </form>
            <div class="loader" id="urlLoader">
                <div class="loading-spinner"></div>
            </div>
        </div>
    </div>

    <iframe name="hiddenUploadFrame" id="hiddenUploadFrame" style="display:none;"></iframe>

    <!-- Chat Area -->
    <div id="chatArea">
        <div id="header">
<%--            <h2>AI Chat Demo <span class="openai-badge">OpenAI</span></h2>--%>
            <h2>SCGE Platform AI Assistant</h2>
            <%if((request.getServerName().equals("localhost") )){%>
            <div class="model-switch">
                <a href="<%= contextPath %>/" target="_blank">Switch to Ollama</a>
            </div>
            <%}%>
            <div class="user-info">
                Welcome, <%= username %>!
                <%--                <form action="<%= contextPath %>/logout" method="post" style="display: inline;">--%>
                <%--                    <input type="submit" value="Logout" class="logout-btn"/>--%>
                <%--                </form>--%>
            </div>
        </div>

        <div id="disclaimer">
            <div id="disclaimerHeader">
                <span class="disclaimer-arrow">&#9660;</span>
                <strong>Disclaimer</strong>
            </div>
            <div id="disclaimerContent">
                This AI chatbot has a library of knowledge limited to FDA guidance documents and gene therapy trial information updated regularly from <a href="https://clinicaltrials.gov" target="_blank">clinicaltrials.gov</a>. It's answers are restricted to these topics. Regulatory advice should be confirmed with a licensed regulatory consultant or with FDA. Nothing communicated by this chatbot should be interpreted as medical advice or replaces the advice of your doctor.
            </div>
        </div>

        <div id="transcript"></div>
        <div class="start-over-section">
            <button id="startOverBtn" class="start-over-btn">Start over</button>
        </div>
        <%if((request.getServerName().equals("localhost"))){%>
        <div id="controls">
            <button id="uploadFile" class="upload-btn">Upload File</button>
            <button id="processUrl" class="upload-btn">Process URL</button>
            <button id="loadTrials" class="upload-btn">Load Clinical Trials</button>
        </div>
        <%}%>
        <div class="input-area">
            <textarea id="userInput" placeholder="Ask AI a question about fda documents or gene therapy clinical trials..." rows="3"></textarea>
            <button id="typedTextSubmit" class="submit-btn">Send</button>
        </div>
    </div>
</div>
</body>
</html>