// ============================================================
// TechMock Chat App
// Migrated to MyVibeProject blue/white UI design
// ============================================================

class ChatApp {
    constructor() {
        this.apiBaseUrl = 'http://localhost:9900/api';
        this.currentMode = 'stream'; // 'quick' or 'stream'
        this.sessionId = this.generateSessionId();
        this.isStreaming = false;
        this.isThinking = false;
        this.streamingText = '';
        this.currentConvId = null; // current conversation ID (same as sessionId for localStorage)
        this.conversations = this.loadConversations();
        this.messages = []; // current session messages
        this.messagesLoaded = false;
        this.fileInput = null;
        this.autoScrollEnabled = true; // auto-scroll only when user is at bottom

        this.initElements();
        this.bindEvents();
        this.initMarkdown();
        this.renderConversations();
        this.updateSendButton();
        this.autoResizeTextarea();
    }

    // ==================== INITIALIZATION ====================

    initElements() {
        // Nav
        this.navTabs = document.querySelectorAll('.nav-tab');

        // Sidebar
        this.newChatBtn = document.getElementById('newChatBtn');
        this.conversationList = document.getElementById('conversationList');

        // Chat area
        this.chatTitle = document.getElementById('chatTitle');
        this.welcomeScreen = document.getElementById('welcomeScreen');
        this.messagesContainer = document.getElementById('messagesContainer');

        // Input
        this.messageInput = document.getElementById('messageInput');
        this.sendBtn = document.getElementById('sendBtn');
        this.toolsBtn = document.getElementById('toolsBtn');
        this.toolsWrapper = document.getElementById('toolsWrapper');
        this.toolsMenu = document.getElementById('toolsMenu');
        this.uploadFileItem = document.getElementById('uploadFileItem');
        this.fileInput = document.getElementById('fileInput');
        this.modeBtn = document.getElementById('modeBtn');
        this.modeWrapper = document.getElementById('modeWrapper');
        this.modeDropdown = document.getElementById('modeDropdown');
        this.currentModeText = document.getElementById('currentModeText');

        // Suggestions
        this.suggestionCards = document.querySelectorAll('.suggestion-card');

        // Overlay
        this.loadingOverlay = document.getElementById('loadingOverlay');
        this.loadingText = document.getElementById('loadingText');
        this.loadingSubtext = document.getElementById('loadingSubtext');
    }

    bindEvents() {
        // Nav tabs (cosmetic only - no doc page backend)
        this.navTabs.forEach(tab => {
            tab.addEventListener('click', () => {
                this.navTabs.forEach(t => t.classList.remove('active'));
                tab.classList.add('active');
            });
        });

        // Track user scroll position to decide whether to auto-scroll
        if (this.messagesContainer) {
            this.messagesContainer.addEventListener('scroll', () => {
                const { scrollTop, scrollHeight, clientHeight } = this.messagesContainer;
                const isNearBottom = scrollHeight - scrollTop - clientHeight < 60;
                this.autoScrollEnabled = isNearBottom;
            });
        }

        // New chat
        if (this.newChatBtn) {
            this.newChatBtn.addEventListener('click', () => this.newChat());
        }

        // Send
        if (this.sendBtn) {
            this.sendBtn.addEventListener('click', () => this.handleSend());
        }

        // Input enter key
        if (this.messageInput) {
            this.messageInput.addEventListener('keydown', (e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    this.handleSend();
                }
            });
        }

        // Tools menu
        if (this.toolsBtn) {
            this.toolsBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.toolsWrapper.classList.toggle('active');
            });
        }

        document.addEventListener('click', (e) => {
            if (!this.toolsWrapper.contains(e.target)) {
                this.toolsWrapper.classList.remove('active');
            }
        });

        // Upload
        if (this.uploadFileItem) {
            this.uploadFileItem.addEventListener('click', () => {
                if (this.fileInput) {
                    this.fileInput.click();
                }
                this.toolsWrapper.classList.remove('active');
            });
        }

        if (this.fileInput) {
            this.fileInput.addEventListener('change', (e) => this.handleFileSelect(e));
        }

        // Mode selector
        if (this.modeBtn) {
            this.modeBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.modeWrapper.classList.toggle('active');
            });
        }

        document.addEventListener('click', (e) => {
            if (!this.modeWrapper.contains(e.target)) {
                this.modeWrapper.classList.remove('active');
            }
        });

        const modeItems = this.modeDropdown.querySelectorAll('.dropdown-item');
        modeItems.forEach(item => {
            item.addEventListener('click', () => {
                const mode = item.dataset.mode;
                this.selectMode(mode);
                this.modeWrapper.classList.remove('active');
            });
        });

        // Suggestion cards
        this.suggestionCards.forEach(card => {
            card.addEventListener('click', () => {
                const text = card.dataset.text;
                this.quickAsk(text);
            });
        });
    }

    initMarkdown() {
        const checkMarked = () => {
            if (typeof marked !== 'undefined') {
                try {
                    marked.setOptions({
                        breaks: true,
                        gfm: true,
                        headerIds: false,
                        mangle: false
                    });
                    if (typeof hljs !== 'undefined') {
                        marked.setOptions({
                            highlight: function (code, lang) {
                                if (lang && hljs.getLanguage(lang)) {
                                    try {
                                        return hljs.highlight(code, { language: lang }).value;
                                    } catch (err) {
                                        console.error('Highlight failed:', err);
                                    }
                                }
                                return code;
                            }
                        });
                    }
                } catch (e) {
                    console.error('Markdown setup failed:', e);
                }
            } else {
                setTimeout(checkMarked, 100);
            }
        };
        checkMarked();
    }

    // ==================== CONVERSATION MANAGEMENT ====================

    loadConversations() {
        try {
            const stored = localStorage.getItem('chatConversations');
            return stored ? JSON.parse(stored) : [];
        } catch (e) {
            console.error('Load conversations failed:', e);
            return [];
        }
    }

    saveConversations() {
        try {
            localStorage.setItem('chatConversations', JSON.stringify(this.conversations));
        } catch (e) {
            console.error('Save conversations failed:', e);
        }
    }

    renderConversations() {
        if (!this.conversationList) return;
        this.conversationList.innerHTML = '';

        if (this.conversations.length === 0) {
            this.conversationList.innerHTML = '<div class="empty-list">暂无对话</div>';
            return;
        }

        this.conversations.forEach(conv => {
            const item = document.createElement('div');
            item.className = 'conv-item' + (this.currentConvId === conv.id ? ' active' : '');
            item.innerHTML = `
                <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v10z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                <span class="conv-title">${this.escapeHtml(conv.title)}</span>
                <button class="conv-delete" title="删除">
                    <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                        <path d="M18 6L6 18M6 6L18 18" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                    </svg>
                </button>
            `;

            // Click to load
            item.addEventListener('click', (e) => {
                if (!e.target.closest('.conv-delete')) {
                    this.loadConversation(conv.id);
                }
            });

            // Delete
            const deleteBtn = item.querySelector('.conv-delete');
            deleteBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.deleteConversation(conv.id);
            });

            this.conversationList.appendChild(item);
        });
    }

    newChat() {
        if (this.isStreaming) {
            this.showNotification('请等待当前对话完成', 'warning');
            return;
        }

        // Save current conversation if it has messages
        if (this.messages.length > 0) {
            this.saveCurrentConversation();
        }

        this.isStreaming = false;
        this.isThinking = false;
        this.streamingText = '';
        this.messages = [];
        this.messagesLoaded = false;
        this.autoScrollEnabled = true;
        this.sessionId = this.generateSessionId();
        this.currentConvId = null;

        // Reset UI
        this.chatTitle.textContent = '新对话';
        this.messagesContainer.innerHTML = '';
        this.messagesContainer.style.display = 'none';
        this.welcomeScreen.style.display = 'flex';

        if (this.messageInput) this.messageInput.value = '';
        this.updateSendButton();
        this.renderConversations();
    }

    saveCurrentConversation() {
        if (this.messages.length === 0) return;

        const firstUserMsg = this.messages.find(m => m.role === 'user');
        const title = firstUserMsg
            ? (firstUserMsg.content.substring(0, 20) + (firstUserMsg.content.length > 20 ? '...' : ''))
            : '新对话';

        const convId = this.currentConvId || this.sessionId;
        const existingIndex = this.conversations.findIndex(c => c.id === convId);

        const convData = {
            id: convId,
            title: title,
            messages: [...this.messages],
            sessionId: this.sessionId,
            updatedAt: new Date().toISOString()
        };

        if (existingIndex !== -1) {
            this.conversations[existingIndex] = convData;
        } else {
            this.conversations.unshift(convData);
        }

        // Keep max 50
        if (this.conversations.length > 50) {
            this.conversations = this.conversations.slice(0, 50);
        }

        this.saveConversations();
        this.renderConversations();
    }

    loadConversation(convId) {
        if (this.isStreaming) {
            this.showNotification('请等待当前对话完成', 'warning');
            return;
        }

        // Save current before switching
        if (this.messages.length > 0) {
            this.saveCurrentConversation();
        }

        const conv = this.conversations.find(c => c.id === convId);
        if (!conv) return;

        this.currentConvId = conv.id;
        this.sessionId = conv.sessionId || conv.id;
        this.messages = [...conv.messages];
        this.messagesLoaded = true;
        this.autoScrollEnabled = true;

        // Update UI
        this.chatTitle.textContent = conv.title;
        this.welcomeScreen.style.display = 'none';
        this.messagesContainer.style.display = 'flex';

        // Render messages
        this.messagesContainer.innerHTML = '';
        this.messages.forEach(msg => {
            this.addMessage(msg.role, msg.content, false);
        });

        this.renderConversations();
        this.scrollToBottom();
    }

    deleteConversation(convId) {
        this.conversations = this.conversations.filter(c => c.id !== convId);
        this.saveConversations();
        this.renderConversations();

        // If deleted current conversation, reset
        if (this.currentConvId === convId) {
            this.messages = [];
            this.messagesLoaded = false;
            this.currentConvId = null;
            this.chatTitle.textContent = '新对话';
            this.messagesContainer.innerHTML = '';
            this.messagesContainer.style.display = 'none';
            this.welcomeScreen.style.display = 'flex';
        }
    }

    // ==================== CHAT ====================

    async handleSend() {
        const question = this.messageInput.value.trim();
        if (!question || this.isStreaming) return;

        // Auto-create conversation ID
        if (!this.currentConvId) {
            this.currentConvId = this.sessionId;
            this.chatTitle.textContent = question.substring(0, 20) + (question.length > 20 ? '...' : '');
            this.renderConversations();
        }

        // Show user message
        this.messages.push({ role: 'user', content: question });
        this.addMessage('user', question);

        // Clear input
        this.messageInput.value = '';
        this.autoResizeTextarea();
        this.updateSendButton();

        // Hide welcome, show messages
        this.welcomeScreen.style.display = 'none';
        this.messagesContainer.style.display = 'flex';

        // Set streaming state
        this.isStreaming = true;
        this.isThinking = true;
        this.streamingText = '';
        this.updateSendButton();
        this.scrollToBottom();

        try {
            if (this.currentMode === 'quick') {
                await this.sendQuick(question);
            } else {
                await this.sendStream(question);
            }
        } catch (error) {
            console.error('Send failed:', error);
            this.addMessage('assistant', '抱歉，回答出错了，请稍后重试。错误信息：' + error.message);
        } finally {
            this.isStreaming = false;
            this.isThinking = false;
            this.streamingText = '';
            this.updateSendButton();
            this.saveCurrentConversation();
        }
    }

    quickAsk(text) {
        this.messageInput.value = text;
        this.handleSend();
    }

    async sendQuick(question) {
        // Show thinking indicator
        this.showThinking();

        try {
            const response = await fetch(`${this.apiBaseUrl}/chat`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    Id: this.sessionId,
                    Question: question
                })
            });

            if (!response.ok) throw new Error(`HTTP ${response.status}`);

            const data = await response.json();
            console.log('[Quick] Response:', data);

            // Remove thinking indicator
            this.removeThinking();

            // Parse response
            if (data.code === 200 || data.message === 'success') {
                const chatResp = data.data;
                if (chatResp && chatResp.success) {
                    const answer = chatResp.answer || '(无回复内容)';
                    this.messages.push({ role: 'assistant', content: answer });
                    this.addMessage('assistant', answer);
                } else if (chatResp && chatResp.errorMessage) {
                    throw new Error(chatResp.errorMessage);
                } else {
                    const fallback = chatResp?.answer || chatResp?.errorMessage || '服务返回了空内容';
                    this.messages.push({ role: 'assistant', content: fallback });
                    this.addMessage('assistant', fallback);
                }
            } else {
                throw new Error(data.message || '请求失败');
            }
        } catch (error) {
            this.removeThinking();
            throw error;
        }
    }

    async sendStream(question) {
        // Show thinking indicator
        this.showThinking();

        try {
            const response = await fetch(`${this.apiBaseUrl}/chat_stream`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    Id: this.sessionId,
                    Question: question
                })
            });

            if (!response.ok) throw new Error(`HTTP ${response.status}`);

            // Create assistant message element (streaming)
            this.removeThinking();
            const msgEl = this.addMessage('assistant', '', true);
            let fullText = '';

            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';

            try {
                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;

                    buffer += decoder.decode(value, { stream: true });
                    const lines = buffer.split('\n');
                    buffer = lines.pop() || '';

                    for (const line of lines) {
                        if (line.trim() === '') continue;

                        if (line.startsWith('event:')) continue;
                        if (line.startsWith('id:')) continue;

                        if (line.startsWith('data:')) {
                            const rawData = line.substring(5).trim();

                            if (rawData === '[DONE]') {
                                this.finalizeStreamMessage(msgEl, fullText);
                                return;
                            }

                            try {
                                const sseMsg = JSON.parse(rawData);
                                if (sseMsg.type === 'content') {
                                    fullText += sseMsg.data || '';
                                    this.updateStreamContent(msgEl, fullText);
                                } else if (sseMsg.type === 'done') {
                                    this.finalizeStreamMessage(msgEl, fullText);
                                    return;
                                } else if (sseMsg.type === 'error') {
                                    throw new Error(sseMsg.data || '未知错误');
                                }
                            } catch (e) {
                                // Not JSON, append raw text
                                if (!e.message.includes('未知错误')) {
                                    fullText += rawData;
                                    this.updateStreamContent(msgEl, fullText);
                                } else {
                                    throw e;
                                }
                            }
                        }
                    }
                }
            } finally {
                reader.releaseLock();
            }

            // If we get here without done message, finalize anyway
            this.finalizeStreamMessage(msgEl, fullText);

        } catch (error) {
            this.removeThinking();
            throw error;
        }
    }

    showThinking() {
        if (document.getElementById('thinkingIndicator')) return;

        const row = document.createElement('div');
        row.className = 'msg-row thinking-row';
        row.id = 'thinkingIndicator';
        row.innerHTML = `
            <div class="thinking-bubble">
                <div class="thinking-dots">
                    <span class="dot"></span>
                    <span class="dot"></span>
                    <span class="dot"></span>
                </div>
                <span class="thinking-text">正在思考...</span>
            </div>
        `;
        this.messagesContainer.appendChild(row);
        this.scrollToBottom();
    }

    removeThinking() {
        const el = document.getElementById('thinkingIndicator');
        if (el) el.remove();
    }

    updateStreamContent(msgEl, text) {
        const content = msgEl.querySelector('.msg-content');
        if (content) {
            content.innerHTML = this.renderMarkdown(text) + '<span class="cursor">|</span>';
            this.scrollToBottom();
        }
    }

    finalizeStreamMessage(msgEl, text) {
        const content = msgEl.querySelector('.msg-content');
        if (content) {
            content.innerHTML = this.renderMarkdown(text);
            this.highlightCode(content);
        }
        msgEl.classList.remove('streaming');
        if (text) {
            this.messages.push({ role: 'assistant', content: text });
        }
        this.scrollToBottom();
    }

    // ==================== MESSAGE RENDERING ====================

    addMessage(role, content, isStreaming = false) {
        // Hide welcome, show messages container
        if (this.messagesContainer.style.display === 'none' || this.messagesContainer.style.display === '') {
            this.welcomeScreen.style.display = 'none';
            this.messagesContainer.style.display = 'flex';
        }

        const row = document.createElement('div');
        row.className = `msg-row ${role === 'user' ? 'msg-user' : 'msg-ai'}`;

        const bubble = document.createElement('div');
        bubble.className = `msg-bubble ${role === 'user' ? 'user' : 'assistant'}`;

        const contentDiv = document.createElement('div');
        contentDiv.className = 'msg-content';

        if (role === 'assistant' && !isStreaming) {
            contentDiv.innerHTML = this.renderMarkdown(content);
            this.highlightCode(contentDiv);
        } else {
            contentDiv.textContent = content;
        }

        bubble.appendChild(contentDiv);
        row.appendChild(bubble);
        this.messagesContainer.appendChild(row);
        this.scrollToBottom();

        return row;
    }

    renderMarkdown(content) {
        if (!content) return '';
        // Strip trailing whitespace/newlines that marked turns into empty <p> or <br>
        content = content.replace(/[\s\n\r]+$/, '');
        if (typeof marked === 'undefined') {
            return this.escapeHtml(content);
        }
        try {
            return marked.parse(content);
        } catch (e) {
            return this.escapeHtml(content);
        }
    }

    highlightCode(container) {
        if (typeof hljs !== 'undefined' && container) {
            container.querySelectorAll('pre code').forEach(block => {
                if (!block.classList.contains('hljs')) {
                    hljs.highlightElement(block);
                }
            });
        }
    }

    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    scrollToBottom(force = false) {
        requestAnimationFrame(() => {
            if (this.messagesContainer) {
                if (force || this.autoScrollEnabled) {
                    this.messagesContainer.scrollTop = this.messagesContainer.scrollHeight;
                }
            }
        });
    }

    // ==================== UI HELPERS ====================

    updateSendButton() {
        if (this.sendBtn) {
            const hasText = this.messageInput && this.messageInput.value.trim().length > 0;
            this.sendBtn.disabled = !hasText || this.isStreaming;
        }
    }

    selectMode(mode) {
        if (this.isStreaming) {
            this.showNotification('请等待当前对话完成后再切换模式', 'warning');
            return;
        }
        this.currentMode = mode;

        // Update UI
        this.modeDropdown.querySelectorAll('.dropdown-item').forEach(item => {
            item.classList.toggle('active', item.dataset.mode === mode);
        });

        const modeNames = { quick: '快速', stream: '流式' };
        if (this.currentModeText) {
            this.currentModeText.textContent = modeNames[mode] || '快速';
        }

        this.showNotification(`已切换到${modeNames[mode]}模式`, 'info');
    }

    autoResizeTextarea() {
        if (!this.messageInput) return;

        this.messageInput.addEventListener('input', () => {
            this.messageInput.style.height = 'auto';
            this.messageInput.style.height = Math.min(this.messageInput.scrollHeight, 120) + 'px';
            this.updateSendButton();
        });
    }

    // ==================== FILE UPLOAD ====================

    handleFileSelect(event) {
        const file = event.target.files[0];
        if (!file) return;

        const ext = file.name.split('.').pop().toLowerCase();
        const allowed = ['txt', 'md', 'markdown'];
        if (!allowed.includes(ext)) {
            this.showNotification('仅支持上传 TXT 或 Markdown (.md) 格式文件', 'error');
            this.fileInput.value = '';
            return;
        }

        if (file.size > 50 * 1024 * 1024) {
            this.showNotification('文件大小不能超过 50MB', 'error');
            this.fileInput.value = '';
            return;
        }

        this.uploadFile(file);
    }

    async uploadFile(file) {
        this.showOverlay('正在上传文件...', `上传: ${file.name}`);

        try {
            const formData = new FormData();
            formData.append('file', file);

            const response = await fetch(`${this.apiBaseUrl}/upload`, {
                method: 'POST',
                body: formData
            });

            if (!response.ok) throw new Error(`HTTP ${response.status}`);

            const data = await response.json();

            if ((data.code === 200 || data.message === 'success') && data.data) {
                // Add system message about upload
                if (this.messagesContainer.style.display === 'flex') {
                    this.addMessage('assistant', `\`${file.name}\` 已上传到知识库`);
                }
                this.showNotification(`${file.name} 上传成功`, 'success');
            } else {
                throw new Error(data.message || '上传失败');
            }
        } catch (error) {
            console.error('Upload failed:', error);
            this.showNotification('文件上传失败: ' + error.message, 'error');
        } finally {
            this.hideOverlay();
            this.fileInput.value = '';
        }
    }

    // ==================== OVERLAY ====================

    showOverlay(text, subtext) {
        if (this.loadingText) this.loadingText.textContent = text || '处理中...';
        if (this.loadingSubtext) this.loadingSubtext.textContent = subtext || '';
        this.loadingOverlay.classList.add('show');
    }

    hideOverlay() {
        this.loadingOverlay.classList.remove('show');
    }

    // ==================== NOTIFICATIONS ====================

    showNotification(message, type = 'info') {
        const notif = document.createElement('div');
        notif.className = `notification ${type}`;
        notif.textContent = message;
        document.body.appendChild(notif);

        setTimeout(() => {
            notif.style.animation = 'slideInRight 0.3s ease reverse';
            setTimeout(() => {
                if (notif.parentNode) notif.parentNode.removeChild(notif);
            }, 300);
        }, 3000);
    }

    // ==================== UTILS ====================

    generateSessionId() {
        return 'session_' + Math.random().toString(36).substr(2, 9) + '_' + Date.now();
    }
}

// Initialize when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    window.app = new ChatApp();
    window.interviewApp = new InterviewApp();
    window.audioReviewApp = new AudioReviewApp();
});

// ============================================================
// Interview App
// ============================================================

class InterviewApp {
    constructor() {
        this.apiBaseUrl = 'http://localhost:9900/api';
        this.currentTab = 'chat';
        this.interviews = this.loadInterviews();
        this.currentInterviewId = null;
        this.isInterviewing = false;
        this.interviewStartTime = null;

        this.initElements();
        this.bindEvents();
        this.renderInterviewList();
    }

    initElements() {
        this.navTabs = document.querySelectorAll('.nav-tab');
        this.chatPage = document.getElementById('chatPage');
        this.interviewPage = document.getElementById('interviewPage');

        // Interview config
        this.interviewConfig = document.getElementById('interviewConfig');
        this.interviewChat = document.getElementById('interviewChat');
        this.interviewReportPage = document.getElementById('interviewReportPage');
        this.startInterviewBtn = document.getElementById('startInterviewBtn');

        // Interview chat
        this.interviewMessages = document.getElementById('interviewMessages');
        this.interviewStatus = document.getElementById('interviewStatus');
        this.interviewMessageInput = document.getElementById('interviewMessageInput');
        this.interviewSendBtn = document.getElementById('interviewSendBtn');
        this.interviewInputWrapper = document.getElementById('interviewInputWrapper');
        this.interviewChatHeader = document.getElementById('interviewChatHeader');

        // Actions
        this.endInterviewBtn = document.getElementById('endInterviewBtn');
        this.newInterviewBtn = document.getElementById('newInterviewBtn');
        this.interviewList = document.getElementById('interviewList');

        // Report
        this.interviewReportBody = document.getElementById('interviewReportBody');
        this.reportBackBtn = document.getElementById('reportBackBtn');

        // 结束面试确认弹窗
        this.endInterviewModal = document.getElementById('endInterviewModal');
        this.endInterviewCancelBtn = document.getElementById('endInterviewCancelBtn');
        this.endInterviewConfirmBtn = document.getElementById('endInterviewConfirmBtn');
    }

    bindEvents() {
        // Tab switching
        this.navTabs.forEach(tab => {
            tab.addEventListener('click', () => {
                const newTab = tab.dataset.tab;
                this.switchTab(newTab);
            });
        });

        document.querySelector('.nav-tab[data-tab="audio"]')?.addEventListener('click', () => {
            if (window.audioReviewApp) {
                window.audioReviewApp.onShow();
            }
        });

        document.querySelectorAll('.radio-option').forEach(opt => {
            opt.addEventListener('click', () => {
                document.querySelectorAll('.radio-option').forEach(o => o.classList.remove('active'));
                opt.classList.add('active');
                opt.querySelector('input').checked = true;
            });
        });

        if (this.startInterviewBtn) {
            this.startInterviewBtn.addEventListener('click', () => this.startInterview());
        }

        if (this.interviewSendBtn) {
            this.interviewSendBtn.addEventListener('click', () => this.sendAnswer());
        }

        if (this.interviewMessageInput) {
            this.interviewMessageInput.addEventListener('keydown', (e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    this.sendAnswer();
                }
            });
            this.interviewMessageInput.addEventListener('input', () => {
                this.updateInterviewSendBtn();
            });
        }

        if (this.endInterviewBtn) {
            this.endInterviewBtn.addEventListener('click', () => this.endInterview());
        }

        if (this.newInterviewBtn) {
            this.newInterviewBtn.addEventListener('click', () => this.newInterview());
        }

        if (this.reportBackBtn) {
            this.reportBackBtn.addEventListener('click', () => this.showChatView());
        }

        // 结束面试确认弹窗按钮
        if (this.endInterviewCancelBtn) {
            this.endInterviewCancelBtn.addEventListener('click', () => this.hideEndInterviewModal());
        }
        if (this.endInterviewConfirmBtn) {
            this.endInterviewConfirmBtn.addEventListener('click', () => {
                this.hideEndInterviewModal();
                this._doEndInterview();
            });
        }
    }

    // ==================== TAB SWITCHING ====================

    switchTab(tab) {
        if (tab === this.currentTab) return;
        this.currentTab = tab;

        this.navTabs.forEach(t => t.classList.toggle('active', t.dataset.tab === tab));

        if (tab === 'interview') {
            this.chatPage.style.display = 'none';
            this.interviewPage.style.display = 'flex';
            this.interviewPage.style.flex = '1';
            const audioPage = document.getElementById('audioPage');
            if (audioPage) audioPage.style.display = 'none';
        } else if (tab === 'audio') {
            this.chatPage.style.display = 'none';
            this.interviewPage.style.display = 'none';
            const audioPage = document.getElementById('audioPage');
            if (audioPage) audioPage.style.display = 'flex';
        } else {
            this.chatPage.style.display = 'flex';
            this.interviewPage.style.display = 'none';
            const audioPage = document.getElementById('audioPage');
            if (audioPage) audioPage.style.display = 'none';
        }
    }

    // ==================== INTERVIEW MANAGEMENT ====================

    loadInterviews() {
        try {
            const stored = localStorage.getItem('interviews');
            return stored ? JSON.parse(stored) : [];
        } catch (e) {
            return [];
        }
    }

    saveInterviews() {
        try {
            localStorage.setItem('interviews', JSON.stringify(this.interviews));
        } catch (e) {
            console.error('Save interviews failed:', e);
        }
    }

    renderInterviewList() {
        if (!this.interviewList) return;
        this.interviewList.innerHTML = '';

        if (this.interviews.length === 0) {
            this.interviewList.innerHTML = '<div class="empty-list">暂无面试记录</div>';
            return;
        }

        this.interviews.forEach(iv => {
            const item = document.createElement('div');
            item.className = 'interview-item' + (this.currentInterviewId === iv.id ? ' active' : '');

            const typeTag = iv.type === 'tech' ? '技术面' : '非技术面';
            const gradeText = iv.grade || '未完成';
            const statusIcon = iv.status === 'completed' ? '✅' : '🔴';

            item.innerHTML = `
                <div class="item-title">
                    <span>${statusIcon}</span>
                    <span class="type-tag">${typeTag}</span>
                    <span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${this.escapeHtml(iv.title || '模拟面试')}</span>
                </div>
                <div class="item-meta">
                    <span class="grade">${gradeText}</span>
                    <span>${iv.date || ''}</span>
                </div>
                <button class="item-delete" title="删除">
                    <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" width="14" height="14">
                        <path d="M18 6L6 18M6 6L18 18" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                    </svg>
                </button>
            `;

            item.addEventListener('click', (e) => {
                if (!e.target.closest('.item-delete')) {
                    this.loadInterview(iv.id);
                }
            });

            const deleteBtn = item.querySelector('.item-delete');
            deleteBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.deleteInterview(iv.id);
            });

            this.interviewList.appendChild(item);
        });
    }

    newInterview() {
        this.currentInterviewId = null;
        this.isInterviewing = false;
        this.interviewConfig.style.display = 'flex';
        this.interviewChat.style.display = 'none';
        this.interviewReportPage.style.display = 'none';
        this.interviewMessages.innerHTML = '';
        this.renderInterviewList();
    }

    deleteInterview(id) {
        this.interviews = this.interviews.filter(iv => iv.id !== id);
        this.saveInterviews();
        this.renderInterviewList();

        if (this.currentInterviewId === id) {
            this.newInterview();
        }
    }

    loadInterview(id) {
        const iv = this.interviews.find(i => i.id === id);
        if (!iv) return;

        this.currentInterviewId = iv.id;
        this.isInterviewing = iv.status !== 'completed';

        this.interviewConfig.style.display = 'none';

        if (iv.status === 'completed') {
            // 已完成的面试直接显示报告
            this.showReportPage(iv);
        } else {
            // 进行中的面试恢复聊天
            this.interviewChat.style.display = 'flex';
            this.interviewReportPage.style.display = 'none';

            this.interviewMessages.innerHTML = '';
            if (iv.messages) {
                iv.messages.forEach(msg => {
                    this.addInterviewMessage(msg.role, msg.content);
                });
            }

            this.updateInterviewStatus();
        }

        this.renderInterviewList();
    }

    // ==================== INTERVIEW FLOW ====================

    async startInterview() {
        const typeRadio = document.querySelector('input[name="interviewType"]:checked');
        const interviewType = typeRadio ? typeRadio.value : 'tech';

        if (this.startInterviewBtn) {
            this.startInterviewBtn.disabled = true;
            this.startInterviewBtn.textContent = '准备中...';
        }

        try {
            const requestBody = {
                interviewType: interviewType,
                resumeId: null,
                selectedSkills: []
            };

            const response = await fetch(`${this.apiBaseUrl}/interview/start`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(requestBody)
            });

            const data = await response.json();

            if (data.code !== 200) {
                throw new Error(data.message || '启动面试失败');
            }

            const result = data.data;
            this.currentInterviewId = result.sessionId;
            this.isInterviewing = true;
            this.interviewStartTime = Date.now();

            const iv = {
                id: result.sessionId,
                type: interviewType,
                title: interviewType === 'tech' ? '技术面' : '非技术面',
                status: 'active',
                grade: '',
                date: new Date().toLocaleDateString('zh-CN'),
                messages: [],
                currentPhase: result.phase || '问答'
            };
            this.interviews.unshift(iv);
            this.saveInterviews();

            this.interviewConfig.style.display = 'none';
            this.interviewChat.style.display = 'flex';
            this.interviewReportPage.style.display = 'none';
            this.interviewMessages.innerHTML = '';

            this.addInterviewMessage('assistant', result.openingMessage);
            iv.messages = [{ role: 'assistant', content: result.openingMessage }];
            this.saveInterviews();

            this.updateInterviewStatus();
            this.renderInterviewList();

        } catch (error) {
            console.error('Start interview failed:', error);
            this.showNotification('启动面试失败: ' + error.message, 'error');
        } finally {
            if (this.startInterviewBtn) {
                this.startInterviewBtn.disabled = false;
                this.startInterviewBtn.textContent = '开始面试';
            }
        }
    }

    async sendAnswer() {
        const answer = this.interviewMessageInput.value.trim();
        if (!answer || !this.isInterviewing) return;

        this.interviewMessageInput.value = '';
        this.updateInterviewSendBtn();

        this.addInterviewMessage('user', answer);

        const iv = this.interviews.find(i => i.id === this.currentInterviewId);
        if (iv) {
            iv.messages.push({ role: 'user', content: answer });
        }

        const thinkingEl = this.showInterviewThinking();

        try {
            const response = await fetch(`${this.apiBaseUrl}/interview/answer`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    sessionId: this.currentInterviewId,
                    answer: answer
                })
            });

            const data = await response.json();

            if (data.code !== 200) {
                throw new Error(data.message || '回答处理失败');
            }

            thinkingEl.remove();
            const result = data.data;

            // 检查是否被行为终止
            if (result.terminated) {
                this.addInterviewMessage('assistant', '⚠️ ' + result.terminateReason);
                iv.status = 'completed';
                iv.grade = '已终止';
                iv.currentPhase = '已终止';
                this.isInterviewing = false;

                this.updateInterviewStatus();
                this.renderInterviewList();

                if (result.reportData) {
                    this.renderAndSaveReport(result.reportData, iv);
                }

                if (window.app && window.app.showOverlay) {
                    window.app.showOverlay('正在生成评估报告...', 'AI 正在分析面试对话，请稍候');
                }
                this.showReportPage(iv);
                return;
            }

            // 检查是否已完成
            if (result.completed) {
                this.isInterviewing = false;

                if (result.reportData) {
                    this.renderAndSaveReport(result.reportData, iv);
                }

                iv.status = 'completed';
                iv.currentPhase = '已完成';
                this.updateInterviewStatus();
                this.renderInterviewList();

                if (window.app && window.app.showOverlay) {
                    window.app.showOverlay('正在生成评估报告...', 'AI 正在分析面试对话，请稍候');
                }
                this.showReportPage(iv);
                return;
            }

            // 正常下一题
            const nextQuestion = result.nextQuestion;
            this.addInterviewMessage('assistant', nextQuestion);

            if (iv) {
                iv.messages.push({ role: 'assistant', content: nextQuestion });
                // 更新当前阶段显示
                if (result.currentPhase) {
                    iv.currentPhase = result.currentPhase;
                }
                this.saveInterviews();
                this.updateInterviewStatus();
            }

        } catch (error) {
            thinkingEl.remove();
            if (error.message && error.message.includes('已结束')) {
                this.addInterviewMessage('assistant', '面试已结束，请查看报告。');
                this.isInterviewing = false;
                this.updateInterviewSendBtn();
                const iv = this.interviews.find(i => i.id === this.currentInterviewId);
                if (iv) iv.status = 'completed';
                this.saveInterviews();
                if (window.app && window.app.showOverlay) {
                    window.app.showOverlay('正在生成评估报告...', 'AI 正在分析面试对话，请稍候');
                }
                this.showReportPage(iv);
            } else {
                this.addInterviewMessage('assistant', '抱歉，系统出现错误，请稍后重试。错误信息：' + error.message);
            }
        }
    }

    /**
     * 渲染报告 JSON 并保存到 localStorage
     */
    renderAndSaveReport(reportJson, iv) {
        try {
            const report = JSON.parse(reportJson);
            iv.report = report;
            iv.grade = report.overallGrade || '';
            this.saveInterviews();
        } catch (e) {
            console.warn('解析报告 JSON 失败:', e);
        }
    }

    /**
     * 显示报告页面
     */
    async showReportPage(iv) {
        this.interviewChat.style.display = 'none';
        this.interviewReportPage.style.display = 'flex';

        // 优先用本地缓存的报告
        if (iv && iv.report) {
            this.renderReport(iv.report, iv);
            if (window.app && window.app.hideOverlay) {
                window.app.hideOverlay();
            }
            return;
        }

        // 从后端获取（endInterview 调用时报告已在后端生成并返回）
        if (this.currentInterviewId) {
            try {
                const resp = await fetch(`${this.apiBaseUrl}/interview/report/${this.currentInterviewId}`);
                const data = await resp.json();
                if (data.code === 200 && data.data) {
                    const report = JSON.parse(data.data);
                    if (iv) {
                        iv.report = report;
                        this.saveInterviews();
                    }
                    this.renderReport(report, iv);
                } else {
                    this.interviewReportBody.innerHTML = '<div class="empty-task">报告生成失败，请重试</div>';
                }
            } catch (e) {
                this.interviewReportBody.innerHTML = '<div class="empty-task">报告加载失败</div>';
            }
        }

        if (window.app && window.app.hideOverlay) {
            window.app.hideOverlay();
        }
    }

    /**
     * 渲染结构化报告
     */
    renderReport(report, iv) {
        const body = this.interviewReportBody;
        if (!body) return;

        const grade = report.overallGrade || 'D';
        const skills = report.skillAssessments || [];
        const strengths = report.strengths || [];
        const weaknesses = report.weaknesses || [];
        const suggestions = report.suggestions || [];

        let html = '';

        // 终止提示
        if (iv && iv.status === 'terminated' || (iv && iv.grade === '已终止')) {
            html += '<div class="terminate-notice"><p>⚠️ 面试因候选人行为问题提前终止</p></div>';
        }

        // 等级徽章
        html += `<div class="grade-badge grade-${grade}">${grade}</div>`;

        // 技能评估
        if (skills.length > 0) {
            html += '<div class="report-section"><h4>技能评估</h4><table class="skill-table"><thead><tr><th>技能</th><th>等级</th><th>评估依据</th></tr></thead><tbody>';
            skills.forEach(s => {
                const barWidth = (s.level || 0) * 20;
                html += `<tr>
                    <td>${this.escapeHtml(s.skillName)}</td>
                    <td>
                        <span class="skill-bar" style="width:${barWidth}px"></span>
                        ${s.level}/5
                    </td>
                    <td>${this.escapeHtml(s.evidence || '')}</td>
                </tr>`;
            });
            html += '</tbody></table></div>';
        }

        // 优势
        if (strengths.length > 0) {
            html += '<div class="report-section"><h4>优势</h4><ul class="report-list">';
            strengths.forEach(s => { html += `<li>${this.escapeHtml(s)}</li>`; });
            html += '</ul></div>';
        }

        // 薄弱环节
        if (weaknesses.length > 0) {
            html += '<div class="report-section"><h4>薄弱环节</h4><ul class="report-list">';
            weaknesses.forEach(w => { html += `<li>${this.escapeHtml(w)}</li>`; });
            html += '</ul></div>';
        }

        // 改进建议
        if (suggestions.length > 0) {
            html += '<div class="report-section"><h4>改进建议</h4><ul class="report-list">';
            suggestions.forEach(s => { html += `<li>${this.escapeHtml(s)}</li>`; });
            html += '</ul></div>';
        }

        // 行为记录
        if (iv && iv.behaviorWarnings && iv.behaviorWarnings.length > 0) {
            html += '<div class="report-section"><h4>行为记录</h4>';
            iv.behaviorWarnings.forEach(w => {
                const isAggressive = w.note && w.note.includes('攻击性');
                html += `<div class="behavior-warning ${isAggressive ? 'aggressive' : 'uncooperative'}">
                    <span>第${w.turn}轮 — ${this.escapeHtml(w.note)}</span>
                </div>`;
            });
            html += '</div>';
        }

        // 对话历史回顾（可折叠）
        if (iv && iv.messages && iv.messages.length > 0) {
            html += `<div class="report-section">
                <h4>面试对话回顾</h4>
                <button class="toggle-history-btn" onclick="document.getElementById('interviewHistory').classList.toggle('collapsed')">
                    <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" width="16" height="16">
                        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v10z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                    <span>查看完整对话记录</span>
                </button>
                <div class="interview-history" id="interviewHistory">`;
            iv.messages.forEach(msg => {
                const isUser = msg.role === 'user';
                const roleLabel = isUser ? '候选人' : '面试官';
                if (isUser) {
                    html += `<div class="history-msg user"><span class="history-label">${roleLabel}</span><div class="history-content">${this.escapeHtml(msg.content)}</div></div>`;
                } else {
                    html += `<div class="history-msg ai"><span class="history-label">${roleLabel}</span><div class="history-content">${this.renderMarkdown(msg.content)}</div></div>`;
                }
            });
            html += '</div></div>';
        }

        body.innerHTML = html;
    }

    /**
     * 返回面试配置页面（查看面试列表）
     */
    showChatView() {
        this.interviewReportPage.style.display = 'none';
        this.interviewChat.style.display = 'none';
        this.interviewConfig.style.display = 'flex';
    }

    async endInterview() {
        if (!this.currentInterviewId) return;
        this.showEndInterviewModal();
    }

    /** 确认点击后执行 */
    async _doEndInterview() {
        if (!this.currentInterviewId) return;

        this.hideEndInterviewModal();

        if (window.app && window.app.showOverlay) {
            window.app.showOverlay('正在生成评估报告...', 'AI 正在分析面试对话，请稍候');
        }

        try {
            const response = await fetch(`${this.apiBaseUrl}/interview/end`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    sessionId: this.currentInterviewId
                })
            });

            const data = await response.json();

            if (data.code !== 200) {
                throw new Error(data.message || '结束面试失败');
            }

            const reportData = data.data.overallRecommendation;
            const iv = this.interviews.find(i => i.id === this.currentInterviewId);

            if (reportData) {
                try {
                    const report = JSON.parse(reportData);
                    if (iv) {
                        iv.report = report;
                        iv.grade = report.overallGrade || '';
                    }
                } catch (e) {
                    console.warn('解析报告失败:', e);
                }
            }

            if (iv) {
                iv.status = 'completed';
                this.saveInterviews();
            }

            this.isInterviewing = false;
            this.updateInterviewStatus();
            this.renderInterviewList();

            if (window.app && window.app.hideOverlay) {
                window.app.hideOverlay();
            }
            this.showReportPage(iv);

        } catch (error) {
            console.error('End interview failed:', error);
            if (window.app && window.app.hideOverlay) {
                window.app.hideOverlay();
            }
            this.showNotification('结束面试失败: ' + error.message, 'error');
        }
    }

    // ==================== 确认弹窗 ====================

    showEndInterviewModal() {
        if (this.endInterviewModal) {
            this.endInterviewModal.classList.add('show');
        }
    }

    hideEndInterviewModal() {
        if (this.endInterviewModal) {
            this.endInterviewModal.classList.remove('show');
        }
    }

    // ==================== UI HELPERS ====================

    addInterviewMessage(role, content) {
        const row = document.createElement('div');
        row.className = `msg-row ${role === 'user' ? 'msg-user' : 'msg-ai'}`;

        const bubble = document.createElement('div');
        bubble.className = `msg-bubble ${role === 'user' ? 'user' : 'assistant'}`;

        const contentDiv = document.createElement('div');
        contentDiv.className = 'msg-content';

        if (role === 'assistant') {
            contentDiv.innerHTML = this.renderMarkdown(content);
            if (typeof hljs !== 'undefined') {
                contentDiv.querySelectorAll('pre code').forEach(block => {
                    if (!block.classList.contains('hljs')) {
                        hljs.highlightElement(block);
                    }
                });
            }
        } else {
            contentDiv.textContent = content;
        }

        bubble.appendChild(contentDiv);
        row.appendChild(bubble);
        this.interviewMessages.appendChild(row);
        this.interviewMessages.scrollTop = this.interviewMessages.scrollHeight;

        return row;
    }

    showInterviewThinking() {
        const row = document.createElement('div');
        row.className = 'msg-row thinking-row';
        row.id = 'interviewThinking';
        row.innerHTML = `
            <div class="thinking-bubble">
                <div class="thinking-dots">
                    <span class="dot"></span>
                    <span class="dot"></span>
                    <span class="dot"></span>
                </div>
                <span class="thinking-text">正在评估回答...</span>
            </div>
        `;
        this.interviewMessages.appendChild(row);
        this.interviewMessages.scrollTop = this.interviewMessages.scrollHeight;
        return row;
    }

    updateInterviewStatus() {
        if (!this.interviewStatus) return;

        const iv = this.interviews.find(i => i.id === this.currentInterviewId);
        if (!iv) return;

        const typeLabel = iv.type === 'tech' ? '技术面' : '非技术面';
        const phaseLabel = iv.currentPhase ? ` — ${iv.currentPhase}` : '';

        if (iv.status === 'completed') {
            const finalLabel = iv.grade === '已终止' ? '已终止' : '已完成';
            this.interviewStatus.textContent = '✅ ' + typeLabel + finalLabel;
            this.interviewStatus.className = 'interview-status completed';
        } else {
            this.interviewStatus.textContent = typeLabel + ' 进行中' + phaseLabel;
            this.interviewStatus.className = 'interview-status';
        }
    }

    updateInterviewSendBtn() {
        if (this.interviewSendBtn) {
            const hasText = this.interviewMessageInput && this.interviewMessageInput.value.trim().length > 0;
            this.interviewSendBtn.disabled = !hasText || !this.isInterviewing;
        }
    }

    renderMarkdown(content) {
        if (!content) return '';
        // Strip trailing whitespace/newlines that marked turns into empty <p> or <br>
        content = content.replace(/[\s\n\r]+$/, '');
        if (typeof marked === 'undefined') {
            return this.escapeHtml(content);
        }
        try {
            return marked.parse(content);
        } catch (e) {
            return this.escapeHtml(content);
        }
    }

    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    showNotification(message, type = 'info') {
        const notif = document.createElement('div');
        notif.className = `notification ${type}`;
        notif.textContent = message;
        document.body.appendChild(notif);
        setTimeout(() => {
            notif.style.animation = 'slideInRight 0.3s ease reverse';
            setTimeout(() => {
                if (notif.parentNode) notif.parentNode.removeChild(notif);
            }, 300);
        }, 3000);
    }
}

// ============================================================
// Audio Review App
// ============================================================

class AudioReviewApp {
    constructor() {
        this.apiBaseUrl = 'http://localhost:9900/api';
        this.tasks = [];
        this.selectedTaskId = null;
        this.currentDetailTab = 'transcript';
        this.uploadQueue = [];
        this.pollingMap = new Map();

        this.initElements();
        this.bindEvents();
        this.loadTaskList();
        this.setupVisibilityObserver();
    }

    initElements() {
        this.audioPage = document.getElementById('audioPage');
        this.audioTaskList = document.getElementById('audioTaskList');
        this.audioUploadView = document.getElementById('audioUploadView');
        this.audioUploadZone = document.getElementById('audioUploadZone');
        this.audioFileInput = document.getElementById('audioFileInput');
        this.audioQueue = document.getElementById('audioQueue');
        this.audioTaskDetail = document.getElementById('audioTaskDetail');
        this.audioDetailFileName = document.getElementById('audioDetailFileName');
        this.audioDetailMeta = document.getElementById('audioDetailMeta');
        this.audioTabs = document.getElementById('audioTabs');
        this.transcriptBody = document.getElementById('transcriptBody');
        this.reportBody = document.getElementById('reportBody');
        this.audioBackBtn = document.getElementById('audioBackBtn');
        this.audioNewTaskBtn = document.getElementById('audioNewTaskBtn');
    }

    bindEvents() {
        // File upload zone click
        if (this.audioUploadZone) {
            this.audioUploadZone.addEventListener('click', () => {
                this.audioFileInput.click();
            });
        }

        // File input change
        if (this.audioFileInput) {
            this.audioFileInput.addEventListener('change', (e) => {
                if (e.target.files.length > 0) {
                    this.handleFiles(Array.from(e.target.files));
                    this.audioFileInput.value = '';
                }
            });
        }

        // Drag and drop
        if (this.audioUploadZone) {
            this.audioUploadZone.addEventListener('dragover', (e) => {
                e.preventDefault();
                this.audioUploadZone.classList.add('drag-over');
            });
            this.audioUploadZone.addEventListener('dragleave', () => {
                this.audioUploadZone.classList.remove('drag-over');
            });
            this.audioUploadZone.addEventListener('drop', (e) => {
                e.preventDefault();
                this.audioUploadZone.classList.remove('drag-over');
                if (e.dataTransfer.files.length > 0) {
                    this.handleFiles(Array.from(e.dataTransfer.files));
                }
            });
        }

        // Detail tab switching
        if (this.audioTabs) {
            this.audioTabs.querySelectorAll('.audio-tab-btn').forEach(btn => {
                btn.addEventListener('click', () => {
                    this.switchDetailTab(btn.dataset.tab);
                });
            });
        }

        // Page visibility — pause/resume polling
        document.addEventListener('visibilitychange', () => {
            if (document.hidden) {
                this.pauseAllPolling();
            } else {
                this.resumeAllPolling();
            }
        });

        // Back button — return to upload view
        if (this.audioBackBtn) {
            this.audioBackBtn.addEventListener('click', () => this.showUploadView());
        }

        // New task button — clear selection and show upload view
        if (this.audioNewTaskBtn) {
            this.audioNewTaskBtn.addEventListener('click', () => this.showUploadView());
        }
    }

    setupVisibilityObserver() {
        const observer = new MutationObserver(() => {
            if (this.audioPage && this.audioPage.style.display !== 'none') {
                this.loadTaskList();
            }
        });
        if (this.audioPage) {
            observer.observe(this.audioPage, { attributes: true, attributeFilter: ['style'] });
        }
    }

    onShow() {
        this.loadTaskList();
    }

    // ==================== FILE HANDLING ====================

    handleFiles(files) {
        const allowed = ['m4a', 'mp3', 'wav', 'flac'];
        const maxSize = 500 * 1024 * 1024;

        for (const file of files) {
            const ext = file.name.split('.').pop().toLowerCase();
            if (!allowed.includes(ext)) {
                this.showNotification(`${file.name}：不支持的文件格式`, 'error');
                continue;
            }
            if (file.size > maxSize) {
                this.showNotification(`${file.name}：文件大小超过 500MB 限制`, 'error');
                continue;
            }
            if (this.uploadQueue.length >= 20) {
                this.showNotification('同时最多上传 20 个文件', 'warning');
                break;
            }

            this.uploadQueue.push({
                id: 'queue_' + Date.now() + '_' + Math.random().toString(36).substr(2, 6),
                file: file,
                progress: 0,
                status: 'pending',
                errorMessage: ''
            });
        }

        this.renderUploadQueue();
        this.processNextUpload();
    }

    async processNextUpload() {
        const pending = this.uploadQueue.find(q => q.status === 'pending');
        if (!pending) return;

        pending.status = 'uploading';
        pending.progress = 0;
        this.renderUploadQueue();

        try {
            await this.uploadSingleFile(pending);
        } catch (error) {
            pending.status = 'failed';
            pending.errorMessage = error.message;
            this.renderUploadQueue();
            this.showNotification(`${pending.file.name} 上传失败：${error.message}`, 'error');
        }

        setTimeout(() => this.processNextUpload(), 500);
    }

    async uploadSingleFile(item) {
        item.abortController = new AbortController();
        const file = item.file;

        const initResp = await this.apiPost('/audio/upload/init', {
            fileName: file.name,
            fileSize: file.size
        });

        if (item.status === 'failed') return;

        const uploadId = initResp.uploadId;
        const chunkSize = initResp.chunkSize;
        const totalChunks = initResp.totalChunks;
        const uploadedChunks = initResp.uploadedChunks || [];

        item.progress = Math.round((uploadedChunks.length / totalChunks) * 80);
        this.renderUploadQueue();

        for (let i = 0; i < totalChunks; i++) {
            if (uploadedChunks.includes(i)) continue;

            const chunk = file.slice(i * chunkSize, (i + 1) * chunkSize);
            const formData = new FormData();
            formData.append('uploadId', uploadId);
            formData.append('chunkIndex', i.toString());
            formData.append('chunk', chunk);

            await this.apiUploadWithRetry('/audio/upload/chunk', formData, item.abortController.signal);

            item.progress = Math.round(((uploadedChunks.length + i + 1) / totalChunks) * 80);
            this.renderUploadQueue();
        }

        item.status = 'merging';
        item.progress = 85;
        this.renderUploadQueue();

        const mergeResp = await this.apiPost('/audio/upload/merge', { uploadId });
        const taskId = mergeResp.taskId;
        const fileName = mergeResp.fileName || item.file.name;

        // Add task to list immediately
        this.tasks.unshift({
            taskId: taskId,
            fileName: fileName,
            status: 'transcribing',
            progress: 90,
            reviewReport: null,
            createdAt: new Date().toISOString().replace('T', ' '),
            updatedAt: new Date().toISOString().replace('T', ' ')
        });
        this.renderTaskList();

        // Auto-remove from queue and show task detail
        this.removeQueueItem(item.id);

        // Auto-switch to task detail view with processing indicator
        this.showTaskDetail(taskId);
        this.startPolling(taskId);
    }

    removeQueueItem(id) {
        const item = this.uploadQueue.find(q => q.id === id);
        if (item?.abortController) {
            item.abortController.abort();
        }
        this.uploadQueue = this.uploadQueue.filter(q => q.id !== id);
        this.renderUploadQueue();
    }

    // ==================== POLLING ====================

    startPolling(taskId) {
        if (this.pollingMap.has(taskId)) return;

        const poll = async () => {
            try {
                const resp = await this.apiGet(`/audio/task/${taskId}/status`);
                this.updateTaskInList(taskId, resp);

                // Update detail view if currently showing this task
                if (this.selectedTaskId === taskId) {
                    const task = this.tasks.find(t => t.taskId === taskId);
                    if (task) {
                        this.audioDetailMeta.textContent =
                            `状态：${this.getStatusInfo(resp.status).text}  |  进度：${resp.progress}%`;
                    }

                    const isProcessing = ['queued', 'transcribing', 'reviewing'].includes(resp.status);
                    if (!isProcessing) {
                        await this.loadTranscript(taskId);
                        await this.loadReport(taskId);
                    } else {
                        this.showProcessingIndicator(resp.status);
                    }
                }

                if (resp.status === 'completed' || resp.status === 'failed') {
                    this.stopPolling(taskId);
                    this.loadTaskList();
                }
            } catch (error) {
                console.error('Poll failed for task:', taskId, error);
            }
        };

        poll();
        const intervalId = setInterval(poll, 2000);
        this.pollingMap.set(taskId, intervalId);
    }

    stopPolling(taskId) {
        const intervalId = this.pollingMap.get(taskId);
        if (intervalId) {
            clearInterval(intervalId);
            this.pollingMap.delete(taskId);
        }
    }

    pauseAllPolling() {
        this.pollingMap.forEach((id) => clearInterval(id));
    }

    resumeAllPolling() {
        this.pollingMap.forEach((_, taskId) => {
            this.pollingMap.delete(taskId);
            this.startPolling(taskId);
        });
    }

    // ==================== TASK LIST ====================

    async loadTaskList() {
        try {
            const tasks = await this.apiGet('/audio/tasks');
            this.tasks = tasks || [];
            this.renderTaskList();
        } catch (error) {
            console.error('Load task list failed:', error);
        }
    }

    renderTaskList() {
        if (!this.audioTaskList) return;
        this.audioTaskList.innerHTML = '';

        if (this.tasks.length === 0) {
            this.audioTaskList.innerHTML = '<div class="empty-list">暂无上传记录</div>';
            return;
        }

        this.tasks.forEach(task => {
            const statusInfo = this.getStatusInfo(task.status);
            const date = this.formatDate(task.createdAt);

            const item = document.createElement('div');
            item.className = 'audio-task-item' + (this.selectedTaskId === task.taskId ? ' active' : '');
            item.innerHTML = `
                <div class="audio-task-item-top">
                    <span class="audio-task-item-name">${this.escapeHtml(task.fileName)}</span>
                    <span class="audio-task-item-progress">${task.progress}%</span>
                </div>
                <div class="audio-task-item-bottom">
                    <span class="audio-task-item-status" style="color: ${statusInfo.color}">${statusInfo.icon} ${statusInfo.text}</span>
                    <span class="audio-task-item-date">${date}</span>
                </div>
                <button class="audio-task-item-delete" title="删除">
                    <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" width="14" height="14">
                        <path d="M18 6L6 18M6 6L18 18" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                    </svg>
                </button>
            `;

            item.addEventListener('click', (e) => {
                if (!e.target.closest('.audio-task-item-delete')) {
                    this.showTaskDetail(task.taskId);
                }
            });

            item.querySelector('.audio-task-item-delete').addEventListener('click', (e) => {
                e.stopPropagation();
                this.deleteTask(task.taskId);
            });

            this.audioTaskList.appendChild(item);
        });
    }

    updateTaskInList(taskId, resp) {
        const idx = this.tasks.findIndex(t => t.taskId === taskId);
        if (idx !== -1) {
            this.tasks[idx].status = resp.status;
            this.tasks[idx].progress = resp.progress;
            this.tasks[idx].errorMessage = resp.errorMessage;
            this.renderTaskList();
        }
    }

    async deleteTask(taskId) {
        try {
            await this.apiDelete(`/audio/task/${taskId}`);
            this.stopPolling(taskId);
            this.tasks = this.tasks.filter(t => t.taskId !== taskId);
            if (this.selectedTaskId === taskId) {
                this.selectedTaskId = null;
                this.audioUploadView.style.display = 'flex';
                this.audioTaskDetail.style.display = 'none';
            }
            this.renderTaskList();
            this.showNotification('任务已删除', 'info');
        } catch (error) {
            this.showNotification('删除失败', 'error');
        }
    }

    // ==================== TASK DETAIL ====================

    async showTaskDetail(taskId) {
        this.selectedTaskId = taskId;
        this.renderTaskList();

        this.audioUploadView.style.display = 'none';
        this.audioTaskDetail.style.display = 'flex';

        const task = this.tasks.find(t => t.taskId === taskId);
        if (!task) return;

        if (this.audioDetailFileName) {
            this.audioDetailFileName.textContent = task.fileName;
        }
        if (this.audioDetailMeta) {
            this.audioDetailMeta.textContent = `状态：${this.getStatusInfo(task.status).text}  |  进度：${task.progress}%`;
        }

        // Show loading for in-progress tasks
        const isProcessing = ['queued', 'transcribing', 'reviewing'].includes(task.status);
        if (isProcessing) {
            this.showProcessingIndicator(task.status);
        } else {
            this.hideProcessingIndicator();
            await this.loadTranscript(taskId);
            await this.loadReport(taskId);
        }
        this.switchDetailTab('transcript');
    }

    showProcessingIndicator(status) {
        if (this.transcriptBody) {
            const statusMessages = {
                'queued': '任务排队中，等待处理...',
                'transcribing': '正在转写音频，请稍候...',
                'reviewing': '正在生成复盘报告...'
            };
            const msg = statusMessages[status] || '处理中...';
            this.transcriptBody.innerHTML = `
                <div style="display:flex;flex-direction:column;align-items:center;justify-content:center;padding:60px 0;gap:16px;">
                    <div class="loading-spinner"></div>
                    <div style="color:#666;font-size:14px;">${msg}</div>
                </div>`;
        }
        if (this.reportBody) {
            this.reportBody.innerHTML = `
                <div style="display:flex;flex-direction:column;align-items:center;justify-content:center;padding:60px 0;gap:16px;">
                    <div class="loading-spinner"></div>
                    <div style="color:#666;font-size:14px;">等待转写完成后生成...</div>
                </div>`;
        }
    }

    hideProcessingIndicator() {
        // No-op — content will be replaced by loadTranscript/loadReport
    }

    showUploadView() {
        this.selectedTaskId = null;
        this.renderTaskList();
        this.audioUploadView.style.display = 'flex';
        this.audioTaskDetail.style.display = 'none';
    }

    async loadTranscript(taskId) {
        try {
            const resp = await this.apiGet(`/audio/task/${taskId}/transcript`);
            if (!this.transcriptBody) return;

            if (!resp.transcript || resp.transcript.trim() === '') {
                this.transcriptBody.innerHTML = '<div class="empty-task">暂无转写内容</div>';
                return;
            }

            if (resp.speakerSegments) {
                try {
                    const segments = JSON.parse(resp.speakerSegments);
                    if (Array.isArray(segments) && segments.length > 0) {
                        let html = '';
                        segments.forEach(seg => {
                            const speaker = seg.speaker || 'speaker_0';
                            const speakerNum = speaker.replace('speaker_', '');
                            const startTime = this.formatTimeMs(seg.begin_time || 0);
                            html += `<div class="speaker-segment">
                                <span class="speaker-label speaker-${speakerNum}">Speaker ${speakerNum}</span>
                                <span class="timestamp">${startTime}</span>
                                <span class="speaker-text">${this.escapeHtml(seg.text || '')}</span>
                            </div>`;
                        });
                        this.transcriptBody.innerHTML = html;
                        return;
                    }
                } catch (e) {
                    // Fall through to plain text
                }
            }

            this.transcriptBody.innerHTML = `<div class="transcript-plain">${this.escapeHtml(resp.transcript)}</div>`;
        } catch (error) {
            this.transcriptBody.innerHTML = '<div class="empty-task">加载转写内容失败</div>';
        }
    }

    async loadReport(taskId) {
        try {
            const resp = await this.apiGet(`/audio/task/${taskId}/report`);
            if (!this.reportBody) return;

            if (!resp.report || resp.report.trim() === '') {
                this.reportBody.innerHTML = '<div class="empty-task">暂无复盘报告</div>';
                return;
            }

            this.reportBody.innerHTML = this.renderMarkdown(resp.report);
            if (typeof hljs !== 'undefined') {
                this.reportBody.querySelectorAll('pre code').forEach(block => {
                    if (!block.classList.contains('hljs')) {
                        hljs.highlightElement(block);
                    }
                });
            }
        } catch (error) {
            this.reportBody.innerHTML = '<div class="empty-task">加载复盘报告失败</div>';
        }
    }

    switchDetailTab(tab) {
        this.currentDetailTab = tab;
        this.audioTabs.querySelectorAll('.audio-tab-btn').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.tab === tab);
        });
        const transcriptPanel = document.getElementById('transcriptPanel');
        const reportPanel = document.getElementById('reportPanel');
        if (transcriptPanel) transcriptPanel.classList.toggle('active', tab === 'transcript');
        if (reportPanel) reportPanel.classList.toggle('active', tab === 'report');
    }

    // ==================== UPLOAD QUEUE RENDERING ====================

    renderUploadQueue() {
        if (!this.audioQueue) return;
        this.audioQueue.innerHTML = '';

        const activeItems = this.uploadQueue.filter(q =>
            q.status === 'pending' || q.status === 'uploading' || q.status === 'merging'
        );

        if (activeItems.length === 0) return;

        activeItems.forEach(item => {
            const card = document.createElement('div');
            card.className = 'audio-upload-card';

            const stageText = this.getUploadStageText(item);
            const stageClass = `stage-${item.status}`;

            card.innerHTML = `
                <div class="audio-upload-card-top">
                    <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" width="16" height="16">
                        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                        <polyline points="14,2 14,8 20,8" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                    <span class="audio-upload-card-name">${this.escapeHtml(item.file.name)}</span>
                    <span class="audio-upload-card-size">${this.formatFileSize(item.file.size)}</span>
                    <button class="audio-upload-card-cancel" data-id="${item.id}" title="取消">
                        <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" width="14" height="14">
                            <path d="M18 6L6 18M6 6L18 18" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                        </svg>
                    </button>
                </div>
                <div class="audio-upload-card-bottom">
                    <div class="audio-progress-bar ${stageClass}">
                        <div class="audio-progress-fill" style="width: ${item.progress}%"></div>
                    </div>
                    <span class="audio-upload-card-stage">${stageText}</span>
                </div>
            `;

            card.querySelector('.audio-upload-card-cancel').addEventListener('click', (e) => {
                e.stopPropagation();
                this.removeQueueItem(item.id);
            });

            this.audioQueue.appendChild(card);
        });
    }

    getUploadStageText(item) {
        switch (item.status) {
            case 'pending': return '排队中';
            case 'uploading': return `上传中 ${item.progress}%`;
            case 'merging': return '合并中';
            case 'transcribing': return '转写中';
            default: return '';
        }
    }

    // ==================== HELPERS ====================

    getStatusInfo(status) {
        const map = {
            'queued': { text: '排队中', icon: '🕐', color: 'var(--text-tertiary)' },
            'transcribing': { text: '转写中', icon: '↻', color: '#8b5cf6' },
            'reviewing': { text: '生成报告中', icon: '⋯', color: '#8b5cf6' },
            'completed': { text: '已完成', icon: '✓', color: '#34a853' },
            'failed': { text: '失败', icon: '✗', color: '#ea4335' },
        };
        return map[status] || { text: status, icon: '·', color: 'var(--text-tertiary)' };
    }

    formatFileSize(bytes) {
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + ' KB';
        return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    }

    formatTimeMs(ms) {
        const totalSec = Math.floor(ms / 1000);
        const min = Math.floor(totalSec / 60).toString().padStart(2, '0');
        const sec = (totalSec % 60).toString().padStart(2, '0');
        return `${min}:${sec}`;
    }

    formatDate(dateStr) {
        if (!dateStr) return '';
        try {
            // MySQL DATETIME: "yyyy-MM-dd HH:mm:ss" — replace space with T for ISO parsing
            const iso = dateStr.replace(' ', 'T');
            const d = new Date(iso);
            if (isNaN(d.getTime())) return '';
            return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' });
        } catch (e) {
            return '';
        }
    }

    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    renderMarkdown(content) {
        if (!content) return '';
        if (typeof marked === 'undefined') return this.escapeHtml(content);
        try { return marked.parse(content); } catch (e) { return this.escapeHtml(content); }
    }

    // ==================== API ====================

    async apiGet(path) {
        const resp = await fetch(`${this.apiBaseUrl}${path}`);
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        return resp.json();
    }

    async apiPost(path, body) {
        const resp = await fetch(`${this.apiBaseUrl}${path}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        return resp.json();
    }

    async apiUpload(path, formData) {
        const resp = await fetch(`${this.apiBaseUrl}${path}`, { method: 'POST', body: formData });
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        return resp.json();
    }

    async apiUploadWithRetry(path, formData, signal, maxRetries = 3) {
        for (let attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                const resp = await fetch(`${this.apiBaseUrl}${path}`, {
                    method: 'POST',
                    body: formData,
                    signal
                });
                if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
                return resp.json();
            } catch (error) {
                if (error.name === 'AbortError') throw error;
                if (attempt === maxRetries) throw error;
                await new Promise(r => setTimeout(r, 1000 * attempt));
            }
        }
    }

    async apiDelete(path) {
        const resp = await fetch(`${this.apiBaseUrl}${path}`, { method: 'DELETE' });
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        return resp.json();
    }

    // ==================== NOTIFICATIONS ====================

    showNotification(message, type = 'info') {
        const notif = document.createElement('div');
        notif.className = `notification ${type}`;
        notif.textContent = message;
        document.body.appendChild(notif);
        setTimeout(() => {
            notif.style.animation = 'slideInRight 0.3s ease reverse';
            setTimeout(() => {
                if (notif.parentNode) notif.parentNode.removeChild(notif);
            }, 300);
        }, 3000);
    }
}
