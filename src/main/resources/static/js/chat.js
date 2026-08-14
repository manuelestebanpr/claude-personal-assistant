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
    const paletteTitle = document.getElementById('palette-title');
    const toolList = document.getElementById('tool-list');

    // Fetched once per page and reused: the catalogues only change when a server restarts.
    const serversCache = {promise: null};
    const toolsCache = {promise: null};

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

    // "!" lists the connected MCP servers, "!/" the tools they expose. Both only at the start of
    // the message, so a "!" inside ordinary prose is just text. "/" opens nothing: a tool belongs
    // to a server, and picking one without saying which server was always ambiguous.
    input.addEventListener('input', function () {
        const value = input.value;
        if (value.startsWith('!/')) {
            openToolPalette(value.slice(2).trim().toLowerCase(), null);
        } else if (value.startsWith('!')) {
            openServerPalette(value.slice(1).trim().toLowerCase());
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

    // Both catalogues are fetched once per page. Server reachability is therefore a snapshot taken
    // when the picker was first opened — reload to re-probe a server that has since come back.
    function load(url, cache) {
        if (!cache.promise) {
            cache.promise = fetch(url)
                .then(response => (response.ok ? response.json() : []))
                .catch(() => []);
        }
        return cache.promise;
    }

    function setPaletteTitle(text) {
        if (paletteTitle) {
            paletteTitle.textContent = text;
        }
    }

    async function openServerPalette(filter) {
        if (!palette) {
            return;
        }
        const servers = await load('/mcp-servers', serversCache);
        const matching = servers.filter(server => matches(filter, server.name, server.id));
        setPaletteTitle('MCP servers');
        renderServers(matching);
        palette.hidden = false;
    }

    async function openToolPalette(filter, serverId) {
        if (!palette) {
            return;
        }
        const tools = await load('/tools', toolsCache);
        const matching = tools.filter(tool =>
            (!serverId || tool.serverId === serverId) && matches(filter, tool.name, tool.title));
        setPaletteTitle(serverId ? 'Tools on ' + serverId : 'Tools on every connected server');
        renderTools(matching);
        palette.hidden = false;
    }

    function matches(filter, ...fields) {
        return !filter || fields.some(field => (field || '').toLowerCase().includes(filter));
    }

    function renderServers(servers) {
        toolList.replaceChildren();
        if (servers.length === 0) {
            renderEmpty('No MCP servers configured.');
            return;
        }
        servers.forEach(function (server) {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'tool-option';

            const name = document.createElement('span');
            name.className = 'tool-option-name';
            name.textContent = server.name;

            const status = document.createElement('span');
            status.className = server.reachable ? 'server-status online' : 'server-status offline';
            status.textContent = server.reachable
                ? server.toolCount + (server.toolCount === 1 ? ' tool' : ' tools')
                : 'unreachable';
            name.appendChild(status);

            const description = document.createElement('span');
            description.className = 'tool-option-description';
            // The failure reason matters more than the URL when a server is down.
            description.textContent = server.reachable
                ? server.url + '  ·  ' + server.protocol
                : (server.detail || server.url);

            button.append(name, description);
            // Drilling in beats making the user retype: the tools are already loaded.
            button.addEventListener('click', () => {
                input.value = '!/';
                openToolPalette('', server.id);
            });
            const item = document.createElement('li');
            item.appendChild(button);
            toolList.appendChild(item);
        });
    }

    function renderTools(tools) {
        toolList.replaceChildren();
        if (tools.length === 0) {
            renderEmpty('No tools available.');
            return;
        }
        let currentServer = null;
        tools.forEach(function (tool) {
            if (tool.serverId !== currentServer) {
                currentServer = tool.serverId;
                const heading = document.createElement('li');
                heading.className = 'tool-group';
                heading.textContent = tool.serverName || tool.serverId;
                toolList.appendChild(heading);
            }
            const item = document.createElement('li');
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'tool-option';
            const name = document.createElement('span');
            name.className = 'tool-option-name';
            name.textContent = tool.name;
            if (!tool.runnableAsIs) {
                const needs = document.createElement('span');
                needs.className = 'server-status needs-input';
                needs.textContent = tool.parameters.length
                    + (tool.parameters.length === 1 ? ' input' : ' inputs');
                name.appendChild(needs);
            }
            const description = document.createElement('span');
            description.className = 'tool-option-description';
            description.textContent = tool.description || '';
            button.append(name, description);
            button.addEventListener('click', () => selectTool(tool));
            item.appendChild(button);
            toolList.appendChild(item);
        });
    }

    function renderEmpty(text) {
        const empty = document.createElement('li');
        empty.className = 'tool-empty';
        empty.textContent = text;
        toolList.appendChild(empty);
    }

    // A tool that takes no arguments runs on the click. Anything else has to collect them first:
    // posting an empty argument object just comes back as "Missing required argument".
    function selectTool(tool) {
        const parameters = tool.parameters || [];
        if (parameters.length === 0) {
            runTool(tool, {});
        } else {
            renderToolForm(tool, parameters);
        }
    }

    function renderToolForm(tool, parameters) {
        toolList.replaceChildren();
        const item = document.createElement('li');
        item.className = 'tool-form';

        const heading = document.createElement('p');
        heading.className = 'tool-form-title';
        heading.textContent = tool.name;
        const origin = document.createElement('span');
        origin.className = 'tool-form-origin';
        origin.textContent = tool.serverName || tool.serverId;
        heading.appendChild(origin);
        item.appendChild(heading);

        const fields = parameters.map(function (parameter) {
            const field = document.createElement('label');
            field.className = 'tool-field';
            const caption = document.createElement('span');
            caption.className = 'tool-field-name';
            // The schema, spelled out: what to call it, what type it wants, whether it may be
            // left blank. Without this the user is guessing at the server's contract.
            caption.textContent = parameter.name + ' — ' + parameter.type
                + (parameter.required ? ', required' : ', optional');
            const control = document.createElement('input');
            control.className = 'tool-field-input';
            control.type = (parameter.type === 'integer' || parameter.type === 'number') ? 'number' : 'text';
            control.placeholder = parameter.description || '';
            // This palette sits inside the composer <form>, so an un-caught Enter would send the
            // half-typed "/gmail_search_messages" as a chat message instead of running the tool.
            control.addEventListener('keydown', function (event) {
                if (event.key === 'Enter') {
                    event.preventDefault();
                    submit();
                }
            });
            field.append(caption, control);
            item.appendChild(field);
            return {parameter: parameter, control: control};
        });

        const error = document.createElement('p');
        error.className = 'tool-form-error';
        error.hidden = true;
        item.appendChild(error);

        const actions = document.createElement('div');
        actions.className = 'tool-form-actions';
        actions.append(
            paletteButton('Back', 'tool-form-cancel', () => openToolPalette('', tool.serverId)),
            paletteButton('Run', 'tool-form-run', submit));
        item.appendChild(actions);

        toolList.appendChild(item);
        fields[0].control.focus();

        function submit() {
            const args = {};
            const missing = [];
            fields.forEach(function (field) {
                const raw = field.control.value.trim();
                if (!raw) {
                    // Omitted rather than sent empty, so the server applies its own default.
                    if (field.parameter.required) {
                        missing.push(field.parameter.name);
                    }
                    return;
                }
                args[field.parameter.name] = coerce(raw, field.parameter.type);
            });
            if (missing.length > 0) {
                error.textContent = 'Fill in: ' + missing.join(', ');
                error.hidden = false;
                return;
            }
            runTool(tool, args);
        }
    }

    function paletteButton(label, className, onClick) {
        const element = document.createElement('button');
        // Explicitly not a submit button — everything here lives inside the composer form.
        element.type = 'button';
        element.className = className;
        element.textContent = label;
        element.addEventListener('click', onClick);
        return element;
    }

    // The schema says what the tool expects; an <input> only ever yields a string.
    function coerce(raw, type) {
        if (type === 'integer' || type === 'number') {
            const parsed = Number(raw);
            return Number.isNaN(parsed) ? raw : parsed;
        }
        if (type === 'boolean') {
            return raw.toLowerCase() === 'true';
        }
        if (type === 'array') {
            return raw.split(',').map(entry => entry.trim()).filter(Boolean);
        }
        return raw;
    }

    async function runTool(tool, args) {
        closePalette();
        input.value = '';
        setSending(true);
        appendBubble('user', describeCall(tool, args));
        const resultBubble = appendBubble('assistant', '');
        try {
            const response = await fetch('/chats/' + encodeURIComponent(chatId)
                + '/servers/' + encodeURIComponent(tool.serverId)
                + '/tools/' + encodeURIComponent(tool.name), {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(args)
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

    // Echoes the call the way it was made, so the transcript shows what was actually asked for
    // rather than a bare tool name.
    function describeCall(tool, args) {
        const call = '!/' + tool.serverId + '/' + tool.name;
        const names = Object.keys(args);
        if (names.length === 0) {
            return call;
        }
        return call + ' ' + names.map(name => name + '=' + JSON.stringify(args[name])).join(' ');
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
