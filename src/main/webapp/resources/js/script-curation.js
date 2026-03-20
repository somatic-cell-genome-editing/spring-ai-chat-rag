// ============================================================
// State
// ============================================================
let pendingDeleteFileName = null;
let pendingDeleteChunkCount = 0;
let pendingBulkDelete = [];
let selectedFiles = [];

// Pagination state per tab
let docPage = 1;
let docTotalPages = 1;
let docSearch = '';
let trialPage = 1;
let trialTotalPages = 1;
let trialSearch = '';
const PAGE_SIZE = 50;

let searchDebounceTimer = null;

// ============================================================
// API calls
// ============================================================
const loadStats = () => {
    fetch(contextPath + '/curation/stats')
        .then(res => res.json())
        .then(data => {
            document.getElementById('statDocuments').textContent = data.totalDocuments;
            document.getElementById('statTrials').textContent = data.totalClinicalTrials;
            document.getElementById('statChunks').textContent = data.totalChunks.toLocaleString();
            document.getElementById('tabDocCount').textContent = data.totalDocuments;
            document.getElementById('tabTrialCount').textContent = data.totalClinicalTrials;
        })
        .catch(err => {
            console.error('Error loading stats:', err);
        });
};

const loadDocuments = () => {
    const tbody = document.getElementById('documentsBody');
    tbody.innerHTML = '<tr class="loading-row"><td colspan="5"><div class="table-spinner"></div> Loading documents...</td></tr>';

    const params = new URLSearchParams({
        type: 'documents',
        page: docPage,
        size: PAGE_SIZE,
        search: docSearch
    });

    fetch(contextPath + '/curation/files?' + params)
        .then(res => res.json())
        .then(data => {
            docTotalPages = data.totalPages;
            renderDocumentsTable(data.files);
            const docPageChange = (page) => { docPage = page; loadDocuments(); };
            renderPagination('docPaginationTop', docPage, docTotalPages, data.totalCount, docPageChange);
            renderPagination('docPagination', docPage, docTotalPages, data.totalCount, docPageChange);
        })
        .catch(err => {
            console.error('Error loading documents:', err);
            tbody.innerHTML = '<tr class="empty-row"><td colspan="5">Failed to load documents</td></tr>';
        });
};

const loadTrials = () => {
    const tbody = document.getElementById('trialsBody');
    tbody.innerHTML = '<tr class="loading-row"><td colspan="5"><div class="table-spinner"></div> Loading clinical trials...</td></tr>';

    const params = new URLSearchParams({
        type: 'clinicalTrials',
        page: trialPage,
        size: PAGE_SIZE,
        search: trialSearch
    });

    fetch(contextPath + '/curation/files?' + params)
        .then(res => res.json())
        .then(data => {
            trialTotalPages = data.totalPages;
            renderTrialsTable(data.files);
            const trialPageChange = (page) => { trialPage = page; loadTrials(); };
            renderPagination('trialPaginationTop', trialPage, trialTotalPages, data.totalCount, trialPageChange);
            renderPagination('trialPagination', trialPage, trialTotalPages, data.totalCount, trialPageChange);
        })
        .catch(err => {
            console.error('Error loading trials:', err);
            tbody.innerHTML = '<tr class="empty-row"><td colspan="5">Failed to load clinical trials</td></tr>';
        });
};

const refreshAll = () => {
    loadStats();
    loadDocuments();
    loadTrials();
};

const deleteFile = (fileName) => {
    fetch(contextPath + '/curation/files?fileName=' + encodeURIComponent(fileName), {
        method: 'DELETE'
    })
        .then(res => res.json())
        .then(data => {
            if (data.error) {
                showToast(data.error, 'error');
            } else {
                showToast('Deleted ' + data.deletedChunks + ' chunks for ' + fileName, 'success');
                refreshAll();
            }
        })
        .catch(err => {
            console.error('Error deleting file:', err);
            showToast('Failed to delete file', 'error');
        });
};

const deleteBulk = (fileNames) => {
    let completed = 0;
    let totalDeleted = 0;
    let failed = 0;

    fileNames.forEach(fileName => {
        fetch(contextPath + '/curation/files?fileName=' + encodeURIComponent(fileName), {
            method: 'DELETE'
        })
            .then(res => res.json())
            .then(data => {
                if (data.error) {
                    failed++;
                } else {
                    totalDeleted += data.deletedChunks;
                }
            })
            .catch(() => { failed++; })
            .finally(() => {
                completed++;
                if (completed === fileNames.length) {
                    showToast('Deleted ' + totalDeleted + ' chunks from ' + (fileNames.length - failed) + ' files' +
                        (failed > 0 ? ' (' + failed + ' failed)' : ''), failed > 0 ? 'error' : 'success');
                    refreshAll();
                }
            });
    });
};

const previewFile = (fileName) => {
    document.getElementById('previewFileName').textContent = fileName;
    document.getElementById('previewChunkCount').textContent = '';
    document.getElementById('previewChunks').innerHTML = '<div class="table-spinner"></div> Loading chunks...';
    openModal('previewModal');

    fetch(contextPath + '/curation/files/preview?fileName=' + encodeURIComponent(fileName))
        .then(res => res.json())
        .then(data => {
            if (data.error) {
                document.getElementById('previewChunks').innerHTML = '<p class="error-text">' + data.error + '</p>';
                return;
            }

            document.getElementById('previewChunkCount').textContent = data.chunkCount;

            let html = '';
            data.chunks.forEach((chunk, i) => {
                html += '<div class="chunk-card">';
                html += '<div class="chunk-header">Chunk ' + (i + 1) + '</div>';
                html += '<div class="chunk-body">' + escapeHtml(chunk) + '</div>';
                html += '</div>';
            });
            document.getElementById('previewChunks').innerHTML = html;
        })
        .catch(err => {
            console.error('Error previewing file:', err);
            document.getElementById('previewChunks').innerHTML = '<p class="error-text">Failed to load preview</p>';
        });
};

const uploadFiles = async (files) => {
    document.getElementById('uploadProgress').style.display = 'block';
    document.getElementById('uploadConfirmBtn').disabled = true;
    document.getElementById('uploadCancelBtn').disabled = true;

    let statusHtml = '<div class="upload-progress-bar">' +
        '<div class="progress-text" id="progressText">0 / ' + files.length + '</div>' +
        '<div class="progress-current" id="progressCurrent">Preparing...</div>' +
        '<div class="progress-track"><div class="progress-fill" id="progressFill" style="width: 0%"></div></div>' +
        '</div>';
    for (let i = 0; i < files.length; i++) {
        statusHtml += '<div class="upload-file-status" id="uploadStatus_' + i + '">';
        statusHtml += '<i class="fas fa-clock status-waiting"></i> ';
        statusHtml += '<span class="upload-file-name">' + escapeHtml(files[i].name) + '</span>';
        statusHtml += '<span class="upload-file-state status-waiting">waiting</span>';
        statusHtml += '</div>';
    }
    document.getElementById('uploadStatusList').innerHTML = statusHtml;

    let newCount = 0;
    let updatedCount = 0;
    let failedCount = 0;

    for (let i = 0; i < files.length; i++) {
        const el = document.getElementById('uploadStatus_' + i);

        document.getElementById('progressCurrent').textContent = 'Processing: ' + files[i].name;

        el.innerHTML = '<i class="fas fa-spinner fa-spin"></i> ' +
            '<span class="upload-file-name">' + escapeHtml(files[i].name) + '</span>' +
            '<span class="upload-file-state">uploading...</span>';

        el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

        try {
            const formData = new FormData();
            formData.append('file', files[i]);

            const res = await fetch(contextPath + '/curation/upload', {
                method: 'POST',
                body: formData
            });
            const data = await res.json();

            let icon, stateClass, stateText;
            if (data.status === 'done') {
                icon = '<i class="fas fa-check-circle status-done"></i> ';
                stateClass = 'status-done';
                stateText = 'done (' + data.chunkCount + ' chunks)';
                newCount++;
            } else if (data.status === 'updated') {
                icon = '<i class="fas fa-sync-alt status-updated"></i> ';
                stateClass = 'status-updated';
                stateText = 'updated (' + data.chunkCount + ' chunks)';
                updatedCount++;
            } else {
                icon = '<i class="fas fa-times-circle status-failed"></i> ';
                stateClass = 'status-failed';
                stateText = 'failed: ' + (data.error || 'unknown error');
                failedCount++;
            }

            el.innerHTML = icon +
                '<span class="upload-file-name">' + escapeHtml(files[i].name) + '</span>' +
                '<span class="upload-file-state ' + stateClass + '">' + stateText + '</span>';

        } catch (err) {
            console.error('Error uploading file:', files[i].name, err);
            el.innerHTML = '<i class="fas fa-times-circle status-failed"></i> ' +
                '<span class="upload-file-name">' + escapeHtml(files[i].name) + '</span>' +
                '<span class="upload-file-state status-failed">failed: ' + err.message + '</span>';
            failedCount++;
        }

        const completed = i + 1;
        const pct = Math.round((completed / files.length) * 100);
        document.getElementById('progressText').textContent = completed + ' / ' + files.length;
        document.getElementById('progressFill').style.width = pct + '%';
    }

    document.getElementById('progressCurrent').textContent = 'Complete!';

    const summaryEl = document.getElementById('uploadSummary');
    summaryEl.style.display = 'block';
    summaryEl.innerHTML = '<strong>Summary:</strong> ' +
        files.length + ' files processed — ' +
        '<span class="status-done">' + newCount + ' new</span>, ' +
        '<span class="status-updated">' + updatedCount + ' updated</span>, ' +
        '<span class="status-failed">' + failedCount + ' failed</span>';

    document.getElementById('uploadCancelBtn').disabled = false;
    document.getElementById('uploadCancelBtn').textContent = 'Close';

    refreshAll();
    showToast('Upload complete: ' + newCount + ' new, ' + updatedCount + ' updated, ' + failedCount + ' failed', 'success');
};

// ============================================================
// Table rendering
// ============================================================
const renderDocumentsTable = (docs) => {
    const tbody = document.getElementById('documentsBody');

    if (docs.length === 0) {
        tbody.innerHTML = '<tr class="empty-row"><td colspan="5">' + (docSearch ? 'No documents matching "' + escapeHtml(docSearch) + '"' : 'No documents found') + '</td></tr>';
        updateBulkDeleteBtn('doc');
        return;
    }

    let html = '';
    docs.forEach(doc => {
        html += '<tr>';
        html += '<td class="col-check"><input type="checkbox" class="row-check doc-check" data-filename="' + escapeAttr(doc.fileName) + '" data-chunks="' + doc.chunkCount + '"></td>';
        const pdfName = doc.fileName.replace(/\.md$/i, '.pdf');
        const docUrl = docBase + '/assistant/docs/' + encodeURIComponent(pdfName);
        html += '<td class="col-name" title="' + escapeAttr(doc.fileName) + '"><a href="' + docUrl + '" target="_blank">' + escapeHtml(doc.fileName) + '</a></td>';
        html += '<td class="col-chunks"><span class="chunk-badge">' + doc.chunkCount + '</span></td>';
        html += '<td class="col-date">' + formatDate(doc.uploadedAt) + '</td>';
        html += '<td class="col-actions">';
        html += '<button class="icon-btn preview-btn" title="Preview chunks" onclick="previewFile(\'' + escapeAttr(doc.fileName) + '\')"><i class="fas fa-eye"></i></button>';
        html += '<button class="icon-btn delete-btn" title="Delete" onclick="confirmDelete(\'' + escapeAttr(doc.fileName) + '\', ' + doc.chunkCount + ')"><i class="fas fa-trash-alt"></i></button>';
        html += '</td>';
        html += '</tr>';
    });
    tbody.innerHTML = html;

    tbody.querySelectorAll('.doc-check').forEach(cb => {
        cb.addEventListener('change', () => updateBulkDeleteBtn('doc'));
    });
    updateBulkDeleteBtn('doc');
};

const renderTrialsTable = (trials) => {
    const tbody = document.getElementById('trialsBody');

    if (trials.length === 0) {
        tbody.innerHTML = '<tr class="empty-row"><td colspan="5">' + (trialSearch ? 'No trials matching "' + escapeHtml(trialSearch) + '"' : 'No clinical trials found') + '</td></tr>';
        updateBulkDeleteBtn('trial');
        return;
    }

    let html = '';
    trials.forEach(trial => {
        const displayName = trial.fileName.replace('CLINICAL TRIAL: ', '');
        html += '<tr>';
        html += '<td class="col-check"><input type="checkbox" class="row-check trial-check" data-filename="' + escapeAttr(trial.fileName) + '" data-chunks="' + trial.chunkCount + '"></td>';
        const trialUrl = trialReportBase + '/platform/data/report/clinicalTrials/' + encodeURIComponent(displayName);
        html += '<td class="col-name"><a href="' + trialUrl + '" target="_blank" class="trial-id">' + escapeHtml(displayName) + '</a></td>';
        html += '<td class="col-chunks"><span class="chunk-badge">' + trial.chunkCount + '</span></td>';
        html += '<td class="col-date">' + formatDate(trial.uploadedAt) + '</td>';
        html += '<td class="col-actions">';
        html += '<button class="icon-btn preview-btn" title="Preview chunks" onclick="previewFile(\'' + escapeAttr(trial.fileName) + '\')"><i class="fas fa-eye"></i></button>';
        html += '<button class="icon-btn delete-btn" title="Delete" onclick="confirmDelete(\'' + escapeAttr(trial.fileName) + '\', ' + trial.chunkCount + ')"><i class="fas fa-trash-alt"></i></button>';
        html += '</td>';
        html += '</tr>';
    });
    tbody.innerHTML = html;

    tbody.querySelectorAll('.trial-check').forEach(cb => {
        cb.addEventListener('change', () => updateBulkDeleteBtn('trial'));
    });
    updateBulkDeleteBtn('trial');
};

// ============================================================
// Pagination rendering
// ============================================================
const renderPagination = (containerId, currentPage, totalPages, totalCount, onPageChange) => {
    const container = document.getElementById(containerId);
    if (!container) return;

    if (totalPages <= 1) {
        container.innerHTML = totalCount > 0 ? '<span class="page-info">' + totalCount + ' total</span>' : '';
        return;
    }

    let html = '<span class="page-info">Page ' + currentPage + ' of ' + totalPages + ' (' + totalCount + ' total)</span>';
    html += '<div class="page-buttons">';

    // Previous button
    html += '<button class="page-btn' + (currentPage === 1 ? ' disabled' : '') + '" data-page="' + (currentPage - 1) + '"' +
        (currentPage === 1 ? ' disabled' : '') + '><i class="fas fa-chevron-left"></i></button>';

    // Page numbers
    const pages = getPageNumbers(currentPage, totalPages);
    pages.forEach(p => {
        if (p === '...') {
            html += '<span class="page-ellipsis">...</span>';
        } else {
            html += '<button class="page-btn' + (p === currentPage ? ' active' : '') + '" data-page="' + p + '">' + p + '</button>';
        }
    });

    // Next button
    html += '<button class="page-btn' + (currentPage === totalPages ? ' disabled' : '') + '" data-page="' + (currentPage + 1) + '"' +
        (currentPage === totalPages ? ' disabled' : '') + '><i class="fas fa-chevron-right"></i></button>';

    html += '</div>';
    container.innerHTML = html;

    // Attach click listeners
    container.querySelectorAll('.page-btn:not(.disabled)').forEach(btn => {
        btn.addEventListener('click', () => {
            const page = parseInt(btn.dataset.page);
            if (page >= 1 && page <= totalPages) {
                onPageChange(page);
            }
        });
    });
};

const getPageNumbers = (current, total) => {
    if (total <= 7) {
        return Array.from({length: total}, (_, i) => i + 1);
    }

    const pages = [];
    pages.push(1);

    if (current > 3) {
        pages.push('...');
    }

    const start = Math.max(2, current - 1);
    const end = Math.min(total - 1, current + 1);
    for (let i = start; i <= end; i++) {
        pages.push(i);
    }

    if (current < total - 2) {
        pages.push('...');
    }

    pages.push(total);
    return pages;
};

// ============================================================
// Bulk delete
// ============================================================
const getCheckedFiles = (type) => {
    const className = type === 'doc' ? '.doc-check' : '.trial-check';
    const checked = document.querySelectorAll(className + ':checked');
    const files = [];
    checked.forEach(cb => {
        files.push({
            fileName: cb.dataset.filename,
            chunkCount: parseInt(cb.dataset.chunks)
        });
    });
    return files;
};

const updateBulkDeleteBtn = (type) => {
    const btnId = type === 'doc' ? 'bulkDeleteDocBtn' : 'bulkDeleteTrialBtn';
    const btn = document.getElementById(btnId);
    if (!btn) return;

    const checked = getCheckedFiles(type);
    if (checked.length > 0) {
        btn.style.display = 'inline-flex';
        btn.innerHTML = '<i class="fas fa-trash-alt"></i> Delete Selected (' + checked.length + ')';
    } else {
        btn.style.display = 'none';
    }
};

const toggleSelectAll = (type) => {
    const className = type === 'doc' ? '.doc-check' : '.trial-check';
    const selectAllId = type === 'doc' ? 'selectAllDocs' : 'selectAllTrials';
    const isChecked = document.getElementById(selectAllId).checked;

    document.querySelectorAll(className).forEach(cb => {
        cb.checked = isChecked;
    });
    updateBulkDeleteBtn(type);
};

const confirmBulkDelete = (type) => {
    const checked = getCheckedFiles(type);
    if (checked.length === 0) return;

    const totalChunks = checked.reduce((sum, f) => sum + f.chunkCount, 0);
    pendingBulkDelete = checked.map(f => f.fileName);

    document.getElementById('deleteFileName').textContent = checked.length + ' files';
    document.getElementById('deleteChunkCount').textContent = totalChunks;
    pendingDeleteFileName = null;
    openModal('deleteModal');
};

// ============================================================
// Delete confirmation
// ============================================================
const confirmDelete = (fileName, chunkCount) => {
    pendingDeleteFileName = fileName;
    pendingDeleteChunkCount = chunkCount;
    pendingBulkDelete = [];
    document.getElementById('deleteFileName').textContent = fileName;
    document.getElementById('deleteChunkCount').textContent = chunkCount;
    openModal('deleteModal');
};

const executeDelete = () => {
    closeModal('deleteModal');
    if (pendingBulkDelete.length > 0) {
        deleteBulk(pendingBulkDelete);
        pendingBulkDelete = [];
    } else if (pendingDeleteFileName) {
        deleteFile(pendingDeleteFileName);
        pendingDeleteFileName = null;
    }
};

// ============================================================
// Modal helpers
// ============================================================
const openModal = (id) => {
    document.getElementById(id).classList.add('active');
};

const closeModal = (id) => {
    document.getElementById(id).classList.remove('active');
};

// ============================================================
// Upload modal logic
// ============================================================
const openUploadModal = () => {
    selectedFiles = [];
    document.getElementById('fileInput').value = '';
    document.getElementById('selectedFiles').style.display = 'none';
    document.getElementById('uploadProgress').style.display = 'none';
    document.getElementById('uploadSummary').style.display = 'none';
    document.getElementById('uploadConfirmBtn').disabled = true;
    document.getElementById('uploadCancelBtn').disabled = false;
    document.getElementById('uploadCancelBtn').textContent = 'Cancel';
    document.getElementById('dropZone').style.display = 'flex';
    openModal('uploadModal');
};

const handleFilesSelected = (files) => {
    selectedFiles = Array.from(files);
    if (selectedFiles.length === 0) return;

    document.getElementById('selectedFiles').style.display = 'block';
    document.getElementById('selectedCount').textContent = '(' + selectedFiles.length + ')';
    document.getElementById('uploadConfirmBtn').disabled = false;

    let html = '';
    selectedFiles.forEach((file, i) => {
        html += '<li>';
        html += '<i class="fas fa-file"></i> ';
        html += '<span>' + escapeHtml(file.name) + '</span>';
        html += '<span class="file-size">' + formatFileSize(file.size) + '</span>';
        html += '<button class="remove-file-btn" onclick="removeSelectedFile(' + i + ')">&times;</button>';
        html += '</li>';
    });
    document.getElementById('fileList').innerHTML = html;
};

const removeSelectedFile = (index) => {
    selectedFiles.splice(index, 1);
    if (selectedFiles.length === 0) {
        document.getElementById('selectedFiles').style.display = 'none';
        document.getElementById('uploadConfirmBtn').disabled = true;
    } else {
        handleFilesSelected(selectedFiles);
    }
};

// ============================================================
// Toast notifications
// ============================================================
const showToast = (message, type) => {
    const container = document.getElementById('toastContainer');
    const toast = document.createElement('div');
    toast.className = 'toast toast-' + type;

    const icon = type === 'success' ? 'fa-check-circle' : type === 'error' ? 'fa-exclamation-circle' : 'fa-info-circle';
    toast.innerHTML = '<i class="fas ' + icon + '"></i> <span>' + message + '</span>' +
        '<button class="toast-close" onclick="this.parentElement.remove()">&times;</button>';

    container.appendChild(toast);

    setTimeout(() => {
        toast.classList.add('toast-fade');
        setTimeout(() => toast.remove(), 300);
    }, 4000);
};

// ============================================================
// Utility functions
// ============================================================
const escapeHtml = (str) => {
    if (!str) return '';
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
};

const escapeAttr = (str) => {
    if (!str) return '';
    return str.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/"/g, '&quot;');
};

const formatDate = (dateStr) => {
    if (!dateStr) return '-';
    const date = new Date(dateStr);
    return date.toLocaleDateString('en-US', {
        year: 'numeric',
        month: 'short',
        day: 'numeric'
    });
};

const formatFileSize = (bytes) => {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
};

// ============================================================
// Initialize
// ============================================================
window.addEventListener('load', () => {
    refreshAll();

    // Tab switching
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
            document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
            btn.classList.add('active');
            document.getElementById('tab-' + btn.dataset.tab).classList.add('active');
        });
    });

    // Search inputs with debounce
    document.getElementById('docSearch').addEventListener('input', (e) => {
        clearTimeout(searchDebounceTimer);
        searchDebounceTimer = setTimeout(() => {
            docSearch = e.target.value;
            docPage = 1;
            loadDocuments();
        }, 300);
    });
    document.getElementById('trialSearch').addEventListener('input', (e) => {
        clearTimeout(searchDebounceTimer);
        searchDebounceTimer = setTimeout(() => {
            trialSearch = e.target.value;
            trialPage = 1;
            loadTrials();
        }, 300);
    });

    // Upload button
    document.getElementById('uploadBtn').addEventListener('click', openUploadModal);

    // Upload modal events
    document.getElementById('uploadModalClose').addEventListener('click', () => closeModal('uploadModal'));
    document.getElementById('uploadCancelBtn').addEventListener('click', () => closeModal('uploadModal'));
    document.getElementById('uploadConfirmBtn').addEventListener('click', () => {
        if (selectedFiles.length > 0) {
            document.getElementById('dropZone').style.display = 'none';
            document.getElementById('selectedFiles').style.display = 'none';
            uploadFiles(selectedFiles);
        }
    });

    // File input change
    document.getElementById('fileInput').addEventListener('change', (e) => {
        handleFilesSelected(e.target.files);
    });

    // Drag and drop
    const dropZone = document.getElementById('dropZone');
    dropZone.addEventListener('dragover', (e) => {
        e.preventDefault();
        dropZone.classList.add('drag-over');
    });
    dropZone.addEventListener('dragleave', () => {
        dropZone.classList.remove('drag-over');
    });
    dropZone.addEventListener('drop', (e) => {
        e.preventDefault();
        dropZone.classList.remove('drag-over');
        handleFilesSelected(e.dataTransfer.files);
    });

    // Preview modal events
    document.getElementById('previewModalClose').addEventListener('click', () => closeModal('previewModal'));
    document.getElementById('previewCloseBtn').addEventListener('click', () => closeModal('previewModal'));

    // Delete modal events
    document.getElementById('deleteModalClose').addEventListener('click', () => closeModal('deleteModal'));
    document.getElementById('deleteCancelBtn').addEventListener('click', () => closeModal('deleteModal'));
    document.getElementById('deleteConfirmBtn').addEventListener('click', executeDelete);

    // Close modals on overlay click
    document.querySelectorAll('.modal-overlay').forEach(overlay => {
        overlay.addEventListener('click', (e) => {
            if (e.target === overlay) {
                overlay.classList.remove('active');
            }
        });
    });

    // Select All checkboxes
    document.getElementById('selectAllDocs').addEventListener('change', () => toggleSelectAll('doc'));
    document.getElementById('selectAllTrials').addEventListener('change', () => toggleSelectAll('trial'));

    // Bulk delete buttons
    document.getElementById('bulkDeleteDocBtn').addEventListener('click', () => confirmBulkDelete('doc'));
    document.getElementById('bulkDeleteTrialBtn').addEventListener('click', () => confirmBulkDelete('trial'));

    // Load Clinical Trials button
    document.getElementById('loadTrialsBtn').addEventListener('click', () => {
        const btn = document.getElementById('loadTrialsBtn');
        btn.disabled = true;
        btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Loading Trials...';

        fetch(contextPath + '/load-clinical-trials', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' }
        })
            .then(res => res.json())
            .then(data => {
                if (data.error) {
                    showToast('Error: ' + data.error, 'error');
                } else {
                    showToast('Clinical trials loaded: ' + data.processed + ' processed, ' + data.overwritten + ' overwritten, ' + data.failed + ' failed', 'success');
                    refreshAll();
                }
            })
            .catch(err => {
                console.error('Error loading trials:', err);
                showToast('Failed to load clinical trials', 'error');
            })
            .finally(() => {
                btn.disabled = false;
                btn.innerHTML = '<i class="fas fa-download"></i> Load Clinical Trials';
            });
    });
});
