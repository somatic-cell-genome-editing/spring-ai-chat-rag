<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html>
<head>
    <title>SCGE Chatbot Knowledge Curation</title>
    <%
        String contextPath = request.getContextPath();
        String curationPlatformBase = request.getServerName().equals("localhost") ? "https://dev.scge.mcw.edu" : "";
    %>
    <!-- Bootstrap CSS -->
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@4.6.2/dist/css/bootstrap.min.css" integrity="sha384-xOolHFLEh07PJGoPkLv1IbcEPTNtaed2xpHsD9ESMhqIYd0nLMwNLD69Npy4HI+N" crossorigin="anonymous">
    <!-- jQuery and Popper.js for Bootstrap -->
    <script src="https://ajax.googleapis.com/ajax/libs/jquery/3.5.1/jquery.min.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/popper.js@1.16.1/dist/umd/popper.min.js" integrity="sha384-9/reFTGAW83EW2RDu2S0VKaIzap3H66lZH81PoYlFhbGU+6BZp6G7niu735Sk7lN" crossorigin="anonymous"></script>
    <!-- Font Awesome -->
    <link href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.6.0/css/all.min.css" rel="stylesheet" type="text/css"/>
    <!-- CSS from platform -->
    <link href="<%= curationPlatformBase %>/platform/css/navbarTop.css" rel="stylesheet" type="text/css"/>
    <link href="<%= curationPlatformBase %>/platform/css/base.css" rel="stylesheet" type="text/css"/>
    <link rel="stylesheet" href="<%= contextPath %>/resources/css/curation.css?v=<%= System.currentTimeMillis() %>"/>
    <%
        String serverName = request.getServerName();
        String trialReportBase;
        String docBase;
        if (serverName.equals("stage.scge.mcw.edu")) {
            trialReportBase = "https://stage.scge.mcw.edu";
            docBase = "https://stage.scge.mcw.edu";
        } else if (serverName.equals("scge.mcw.edu")) {
            trialReportBase = "https://scge.mcw.edu";
            docBase = "https://scge.mcw.edu";
        } else {
            trialReportBase = "https://scge.mcw.edu";
            docBase = "https://dev.scge.mcw.edu";
        }
    %>
    <script>
        var contextPath = "<%= contextPath %>";
        var trialReportBase = "<%= trialReportBase %>";
        var docBase = "<%= docBase %>";
    </script>
    <script src="<%= contextPath %>/resources/js/script-curation.js?v=<%= System.currentTimeMillis() %>"></script>
</head>
<body>
<%@include file="navbarTop.jsp"%>

<div class="curation-container">
    <!-- Header -->
    <div class="curation-header">
        <div class="header-left">
            <h2>Chatbot Knowledge Curation</h2>
            <p class="header-subtitle">Manage documents and clinical trials in the SCGE chatbot knowledge base</p>
        </div>
        <div class="header-right">
            <a href="<%= contextPath %>/chat" class="back-to-chat-btn"><i class="fas fa-comments"></i> Back to Chat</a>
        </div>
    </div>

    <!-- Stats Cards -->
    <div class="stats-row">
        <div class="stat-card">
            <div class="stat-icon"><i class="fas fa-file-alt"></i></div>
            <div class="stat-info">
                <span class="stat-number" id="statDocuments">--</span>
                <span class="stat-label">Documents</span>
            </div>
        </div>
        <div class="stat-card">
            <div class="stat-icon stat-icon-trials"><i class="fas fa-flask"></i></div>
            <div class="stat-info">
                <span class="stat-number" id="statTrials">--</span>
                <span class="stat-label">Clinical Trials</span>
            </div>
        </div>
        <div class="stat-card">
            <div class="stat-icon stat-icon-chunks"><i class="fas fa-puzzle-piece"></i></div>
            <div class="stat-info">
                <span class="stat-number" id="statChunks">--</span>
                <span class="stat-label">Total Chunks</span>
            </div>
        </div>
    </div>

    <!-- Tab Navigation -->
    <div class="tab-bar">
        <button class="tab-btn active" data-tab="documents">
            <i class="fas fa-file-alt"></i> Documents
            <span class="tab-count" id="tabDocCount">0</span>
        </button>
        <button class="tab-btn" data-tab="clinicalTrials">
            <i class="fas fa-flask"></i> Clinical Trials
            <span class="tab-count" id="tabTrialCount">0</span>
        </button>
    </div>

    <!-- Documents Tab -->
    <div class="tab-content active" id="tab-documents">
        <div class="toolbar">
            <div class="search-box">
                <i class="fas fa-search search-icon"></i>
                <input type="text" id="docSearch" placeholder="Search documents by filename..." />
            </div>
            <button class="action-btn bulk-delete-btn" id="bulkDeleteDocBtn" style="display:none;">
                <i class="fas fa-trash-alt"></i> Delete Selected
            </button>
            <button class="action-btn upload-btn" id="uploadBtn">
                <i class="fas fa-cloud-upload-alt"></i> Upload Files
            </button>
        </div>
        <div class="pagination-bar pagination-top" id="docPaginationTop"></div>
        <div class="table-wrapper">
            <table class="data-table" id="documentsTable">
                <thead>
                    <tr>
                        <th class="col-check"><input type="checkbox" id="selectAllDocs" title="Select all"></th>
                        <th class="col-name">Filename</th>
                        <th class="col-chunks">Chunks</th>
                        <th class="col-date">Uploaded</th>
                        <th class="col-actions">Actions</th>
                    </tr>
                </thead>
                <tbody id="documentsBody">
                    <tr class="loading-row">
                        <td colspan="5"><div class="table-spinner"></div> Loading documents...</td>
                    </tr>
                </tbody>
            </table>
        </div>
        <div class="pagination-bar" id="docPagination"></div>
    </div>

    <!-- Clinical Trials Tab -->
    <div class="tab-content" id="tab-clinicalTrials">
        <div class="toolbar">
            <div class="search-box">
                <i class="fas fa-search search-icon"></i>
                <input type="text" id="trialSearch" placeholder="Search clinical trials by NCT ID..." />
            </div>
            <button class="action-btn bulk-delete-btn" id="bulkDeleteTrialBtn" style="display:none;">
                <i class="fas fa-trash-alt"></i> Delete Selected
            </button>
            <button class="action-btn load-trials-btn" id="loadTrialsBtn">
                <i class="fas fa-download"></i> Load Clinical Trials
            </button>
        </div>
        <div class="pagination-bar pagination-top" id="trialPaginationTop"></div>
        <div class="table-wrapper">
            <table class="data-table" id="trialsTable">
                <thead>
                    <tr>
                        <th class="col-check"><input type="checkbox" id="selectAllTrials" title="Select all"></th>
                        <th class="col-name">Trial ID</th>
                        <th class="col-chunks">Chunks</th>
                        <th class="col-date">Loaded</th>
                        <th class="col-actions">Actions</th>
                    </tr>
                </thead>
                <tbody id="trialsBody">
                    <tr class="loading-row">
                        <td colspan="5"><div class="table-spinner"></div> Loading clinical trials...</td>
                    </tr>
                </tbody>
            </table>
        </div>
        <div class="pagination-bar" id="trialPagination"></div>
    </div>
</div>

<!-- Upload Modal -->
<div class="modal-overlay" id="uploadModal">
    <div class="modal-box">
        <div class="modal-header">
            <h3><i class="fas fa-cloud-upload-alt"></i> Upload Documents</h3>
            <button class="modal-close" id="uploadModalClose">&times;</button>
        </div>
        <div class="modal-body">
            <div class="drop-zone" id="dropZone">
                <i class="fas fa-file-upload drop-icon"></i>
                <p>Drag & drop files here</p>
                <span class="drop-or">or</span>
                <label class="browse-btn">
                    Browse Files
                    <input type="file" id="fileInput" multiple accept=".md,.txt,.pdf,.docx" hidden />
                </label>
            </div>
            <div class="selected-files" id="selectedFiles" style="display:none;">
                <h4>Selected Files <span id="selectedCount"></span></h4>
                <ul id="fileList"></ul>
            </div>
            <div class="upload-progress" id="uploadProgress" style="display:none;">
                <h4>Upload Progress</h4>
                <div id="uploadStatusList"></div>
                <div class="upload-summary" id="uploadSummary" style="display:none;"></div>
            </div>
        </div>
        <div class="modal-footer">
            <button class="cancel-btn" id="uploadCancelBtn">Cancel</button>
            <button class="confirm-btn" id="uploadConfirmBtn" disabled>
                <i class="fas fa-upload"></i> Upload
            </button>
        </div>
    </div>
</div>

<!-- Preview Modal -->
<div class="modal-overlay" id="previewModal">
    <div class="modal-box modal-box-large">
        <div class="modal-header">
            <h3><i class="fas fa-eye"></i> Preview: <span id="previewFileName"></span></h3>
            <button class="modal-close" id="previewModalClose">&times;</button>
        </div>
        <div class="modal-body">
            <div class="preview-info">
                <span id="previewChunkCount"></span> chunks
            </div>
            <div class="preview-chunks" id="previewChunks">
                <div class="table-spinner"></div> Loading chunks...
            </div>
        </div>
        <div class="modal-footer">
            <button class="cancel-btn" id="previewCloseBtn">Close</button>
        </div>
    </div>
</div>

<!-- Delete Confirmation Modal -->
<div class="modal-overlay" id="deleteModal">
    <div class="modal-box modal-box-small">
        <div class="modal-header modal-header-danger">
            <h3><i class="fas fa-trash-alt"></i> Confirm Delete</h3>
            <button class="modal-close" id="deleteModalClose">&times;</button>
        </div>
        <div class="modal-body">
            <p>Are you sure you want to delete <strong id="deleteFileName"></strong>?</p>
            <p class="delete-warning">This will permanently remove <span id="deleteChunkCount"></span> chunks from the knowledge base.</p>
        </div>
        <div class="modal-footer">
            <button class="cancel-btn" id="deleteCancelBtn">Cancel</button>
            <button class="danger-btn" id="deleteConfirmBtn">
                <i class="fas fa-trash-alt"></i> Delete
            </button>
        </div>
    </div>
</div>

<!-- Toast Notification -->
<div class="toast-container" id="toastContainer"></div>

<br>
<%@include file="footer.jsp"%>
</body>
</html>
