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
        if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault();
            composer.requestSubmit();
        }
    });

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
