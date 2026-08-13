// Streaming chat client: POSTs the user message and consumes the NDJSON response
// (fetch + ReadableStream). Event lines: {type: DELTA|ERROR|DONE, ...}.
(function () {
    'use strict';

    const pane = document.querySelector('.chat-pane');
    const composer = document.getElementById('composer');
    if (!pane || !composer) {
        return; // no chat selected
    }

    const chatId = pane.dataset.chatId;
    const messages = document.getElementById('messages');
    const input = document.getElementById('message-input');
    const sendButton = document.getElementById('send-button');
    const palette = document.getElementById('tool-palette');
    const toolList = document.getElementById('tool-list');

    // Fetched once per page and reused: the catalogue only changes when the server restarts.
    let toolsPromise = null;

    scrollToBottom();

    composer.addEventListener('submit', function (event) {
        event.preventDefault();
        const text = input.value.trim();
        if (!text || input.disabled) {
            return;
        }
        sendMessage(text);
    });

    input.addEventListener('keydown', function (event) {
        if (event.key === 'Escape' && isPaletteOpen()) {
            event.preventDefault();
            closePalette();
            return;
        }
        if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault();
            if (isPaletteOpen()) {
                closePalette();
            }
            composer.requestSubmit();
        }
    });

    // "/" opens the palette only at the start of the message, so a slash inside ordinary prose
    // (a URL, a fraction) is just text.
    input.addEventListener('input', function () {
        if (input.value.startsWith('/')) {
            openPalette(input.value.slice(1).trim().toLowerCase());
        } else {
            closePalette();
        }
    });

    document.addEventListener('click', function (event) {
        if (isPaletteOpen() && !composer.contains(event.target)) {
            closePalette();
        }
    });

    function isPaletteOpen() {
        return palette && !palette.hidden;
    }

    function closePalette() {
        if (palette) {
            palette.hidden = true;
        }
    }

    function loadTools() {
        if (!toolsPromise) {
            toolsPromise = fetch('/tools')
                .then(response => (response.ok ? response.json() : []))
                .catch(() => []);
        }
        return toolsPromise;
    }

    async function openPalette(filter) {
        if (!palette) {
            return;
        }
        const tools = await loadTools();
        const matching = tools.filter(tool =>
            !filter || tool.name.toLowerCase().includes(filter) || tool.title.toLowerCase().includes(filter));
        renderPalette(matching);
        palette.hidden = false;
    }

    function renderPalette(tools) {
        toolList.replaceChildren();
        if (tools.length === 0) {
            const empty = document.createElement('li');
            empty.className = 'tool-empty';
            empty.textContent = 'No tools available.';
            toolList.appendChild(empty);
            return;
        }
        tools.forEach(function (tool) {
            const item = document.createElement('li');
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'tool-option';
            const name = document.createElement('span');
            name.className = 'tool-option-name';
            name.textContent = '/' + tool.name;
            const description = document.createElement('span');
            description.className = 'tool-option-description';
            description.textContent = tool.description || '';
            button.append(name, description);
            button.addEventListener('click', () => runTool(tool));
            item.appendChild(button);
            toolList.appendChild(item);
        });
    }

    async function runTool(tool) {
        closePalette();
        input.value = '';
        setSending(true);
        appendBubble('user', '/' + tool.name);
        const resultBubble = appendBubble('assistant', '');
        try {
            const response = await fetch('/chats/' + encodeURIComponent(chatId) + '/tools/'
                + encodeURIComponent(tool.name), {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({})
            });
            if (!response.ok) {
                showError(resultBubble, null, 'Tool failed with status ' + response.status);
                return;
            }
            const message = await response.json();
            resultBubble.textContent = message.content;
            scrollToBottom();
        } catch (error) {
            showError(resultBubble, null, 'Tool call failed: ' + error.message);
        } finally {
            setSending(false);
            input.focus();
        }
    }

    async function sendMessage(text) {
        setSending(true);
        appendBubble('user', text);
        input.value = '';
        const assistantBubble = appendBubble('assistant', '');
        try {
            const response = await fetch('/chats/' + encodeURIComponent(chatId) + '/messages/stream', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({content: text})
            });
            if (!response.ok || !response.body) {
                showError(assistantBubble, null, 'Request failed with status ' + response.status);
                return;
            }
            await consumeStream(response.body, assistantBubble);
        } catch (error) {
            showError(assistantBubble, null, 'Connection lost: ' + error.message);
        } finally {
            setSending(false);
            input.focus();
        }
    }

    async function consumeStream(stream, assistantBubble) {
        const reader = stream.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        for (;;) {
            const {done, value} = await reader.read();
            if (done) {
                break;
            }
            buffer += decoder.decode(value, {stream: true});
            let newlineIndex;
            while ((newlineIndex = buffer.indexOf('\n')) >= 0) {
                const line = buffer.slice(0, newlineIndex).trim();
                buffer = buffer.slice(newlineIndex + 1);
                if (line) {
                    handleEvent(JSON.parse(line), assistantBubble);
                }
            }
        }
    }

    function handleEvent(event, assistantBubble) {
        if (event.type === 'DELTA') {
            assistantBubble.textContent += event.content;
            scrollToBottom();
        } else if (event.type === 'ERROR') {
            showError(assistantBubble, event.classification, event.message);
        }
        // DONE: nothing to do — input is re-enabled by the caller.
    }

    function showError(assistantBubble, classification, message) {
        if (assistantBubble.textContent === '') {
            assistantBubble.remove(); // no partial answer to keep
        }
        const errorBubble = appendBubble('error', '');
        const label = classification ? '[' + classification + '] ' : '';
        const hint = classification === 'RETRYABLE' ? ' You can try sending the message again.' : '';
        errorBubble.textContent = label + (message || 'The assistant failed.') + hint;
        scrollToBottom();
    }

    function appendBubble(kind, text) {
        const bubble = document.createElement('div');
        bubble.className = 'bubble ' + kind;
        bubble.textContent = text;
        messages.appendChild(bubble);
        scrollToBottom();
        return bubble;
    }

    function setSending(sending) {
        input.disabled = sending;
        sendButton.disabled = sending;
    }

    function scrollToBottom() {
        messages.scrollTop = messages.scrollHeight;
    }
})();
