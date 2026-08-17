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
    const paletteTitle = document.getElementById('tool-palette-title');
    const toolList = document.getElementById('tool-list');
    const attachments = document.getElementById('attachments');
    const attachButton = document.getElementById('attach-button');
    const attachInput = document.getElementById('attach-input');
    const cameraButton = document.getElementById('camera-button');
    const cameraInput = document.getElementById('camera-input');
    const cameraPanel = document.getElementById('camera-panel');
    const cameraPreview = document.getElementById('camera-preview');
    const cameraShutter = document.getElementById('camera-shutter');
    const cameraCancel = document.getElementById('camera-cancel');

    // Images staged for the next message: {mediaType, dataBase64, previewUrl, revocable}.
    // previewUrl is an object URL for a picked file and a data URL for a camera frame; only the
    // former can — and must — be revoked.
    let staged = [];

    // The live MediaStream while the desktop camera panel is open, or null. Held so its tracks can
    // be stopped: a stream left running keeps the camera light on after the panel is gone.
    let cameraStream = null;

    // Must match ChatProperties.maxImagesPerMessage; the server rejects anything over it with a
    // 400, and finding that out only after the upload is a poor way to learn.
    const MAX_IMAGES = 4;
    // Claude's image tier. Anything larger is downscaled by the provider anyway, so sending more
    // pixels only costs upload time and tokens.
    const MAX_EDGE = 1568;
    const JPEG_QUALITY = 0.8;

    // Fetched once per page and reused: the catalogues only change when a server restarts.
    const serversCache = {promise: null};
    const toolsCache = {promise: null};

    // Resolved snapshot of the servers catalogue. The "!<server>/" token a tool is pre-filled and
    // echoed with comes from the server's URL, which is only known once the catalogue has loaded —
    // and rendering is synchronous.
    let knownServers = [];

    // The tool whose guide panel is on screen, keyed by the text that addresses it. Typing
    // arguments must not re-render the panel on every keystroke.
    let openGuideKey = null;
    let openGuideError = null;

    scrollToBottom();

    composer.addEventListener('submit', function (event) {
        event.preventDefault();
        const text = input.value.trim();
        // An image on its own is a complete message; only a message with neither is nothing.
        if ((!text && staged.length === 0) || input.disabled) {
            return;
        }
        // "!<server>/<tool> name=value" runs the tool instead of sending the line to the model.
        // Anything else — including a message that merely starts with "!" — is an ordinary message.
        const command = parseCommand(text);
        if (command) {
            runTypedCommand(command);
            return;
        }
        closePalette();
        sendMessage(text);
    });

    // --- Attaching images: pick, paste or drop ---

    attachButton.addEventListener('click', function () {
        attachInput.click();
    });

    attachInput.addEventListener('change', function () {
        stageFiles(attachInput.files);
        // Cleared so picking the same file twice in a row still fires a change event.
        attachInput.value = '';
    });

    // --- Taking a photo ---
    //
    // Two mechanisms, because no single one works on both targets:
    //
    //  * A phone hands off to the OS camera app through a file input with capture="environment".
    //    It needs no secure context, which is the deciding factor: this app is reached from the
    //    phone over plain http on the tailnet, where navigator.mediaDevices is undefined outright.
    //    It is also the nicer camera — focus, flash, retake all come for free.
    //  * A desktop browser ignores capture= entirely and would just open a file dialog, so it gets
    //    an in-page getUserMedia preview instead. That needs a secure context and gets one from
    //    http://localhost, which is how the desktop reaches this app.
    //
    // Routed on pointer coarseness rather than user agent: it asks the question actually being
    // asked — is this a touch device with a camera app to hand off to.

    cameraButton.addEventListener('click', function () {
        if (window.matchMedia('(pointer: coarse)').matches) {
            cameraInput.click();
            return;
        }
        openLiveCamera();
    });

    cameraInput.addEventListener('change', function () {
        stageFiles(cameraInput.files);
        cameraInput.value = '';
    });

    cameraShutter.addEventListener('click', captureFrame);
    cameraCancel.addEventListener('click', closeLiveCamera);

    // Navigating away mid-preview would otherwise leave the camera in use until the tab dies.
    window.addEventListener('pagehide', closeLiveCamera);

    async function openLiveCamera() {
        if (isLiveCameraOpen()) {
            return;
        }
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
            // Almost always the secure-context rule rather than a missing feature: browsers hide
            // mediaDevices entirely on a plain-http origin, so the API is simply not there.
            showAttachmentError('The in-page camera needs a secure page. Open the app on '
                + 'http://localhost or over https and it will work — or use + to attach a photo '
                + 'you have already taken.');
            return;
        }
        try {
            cameraStream = await navigator.mediaDevices.getUserMedia({
                // Rear camera where there is a choice; ideal rather than exact so a laptop with one
                // front camera still gets a stream instead of an OverconstrainedError.
                video: {facingMode: {ideal: 'environment'}, width: {ideal: MAX_EDGE}}
            });
        } catch (error) {
            showAttachmentError(cameraFailureMessage(error));
            return;
        }
        cameraPreview.srcObject = cameraStream;
        cameraPanel.hidden = false;
        closePalette();
        try {
            await cameraPreview.play();
        } catch (ignored) {
            // Autoplay was refused; the stream is attached and the controls still work, so this is
            // not worth interrupting the user over.
        }
        cameraShutter.focus();
    }

    function captureFrame() {
        // videoWidth is 0 until the first frame has decoded — capturing then yields a blank image.
        if (!cameraStream || cameraPreview.videoWidth === 0) {
            showAttachmentError('The camera is still starting. Try again in a moment.');
            return;
        }
        const scale = Math.min(1, MAX_EDGE / Math.max(cameraPreview.videoWidth, cameraPreview.videoHeight));
        const canvas = document.createElement('canvas');
        canvas.width = Math.round(cameraPreview.videoWidth * scale);
        canvas.height = Math.round(cameraPreview.videoHeight * scale);
        canvas.getContext('2d').drawImage(cameraPreview, 0, 0, canvas.width, canvas.height);
        const dataUrl = canvas.toDataURL('image/jpeg', JPEG_QUALITY);
        closeLiveCamera();
        if (staged.length >= MAX_IMAGES) {
            showAttachmentError('Up to ' + MAX_IMAGES + ' images per message.');
            return;
        }
        staged.push({
            mediaType: 'image/jpeg',
            dataBase64: dataUrl.slice(dataUrl.indexOf(',') + 1),
            // A captured frame has no object URL to revoke — the data URL is the preview.
            previewUrl: dataUrl,
            revocable: false
        });
        renderAttachments();
    }

    function closeLiveCamera() {
        if (cameraStream) {
            cameraStream.getTracks().forEach(track => track.stop());
            cameraStream = null;
        }
        cameraPreview.srcObject = null;
        cameraPanel.hidden = true;
    }

    function isLiveCameraOpen() {
        return !cameraPanel.hidden;
    }

    function cameraFailureMessage(error) {
        switch (error && error.name) {
            case 'NotAllowedError':
            case 'SecurityError':
                return 'The browser blocked access to the camera. Allow it for this site and try '
                    + 'again, or use + to attach a photo you have already taken.';
            case 'NotFoundError':
            case 'OverconstrainedError':
                return 'No camera was found on this device. Use + to attach a photo instead.';
            case 'NotReadableError':
                return 'The camera is already in use by another application.';
            default:
                return 'Could not start the camera: ' + ((error && error.message) || 'unknown error');
        }
    }

    input.addEventListener('paste', function (event) {
        const files = Array.from(event.clipboardData ? event.clipboardData.files : []);
        if (files.length > 0) {
            // Only when there are actually files: a plain text paste must keep pasting text.
            event.preventDefault();
            stageFiles(files);
        }
    });

    // Dropping anywhere on the pane, not just on the composer — the message list is the larger and
    // more obvious target, and the browser navigates away from the page if nothing handles a drop.
    pane.addEventListener('dragover', function (event) {
        event.preventDefault();
        pane.classList.add('dropping');
    });

    pane.addEventListener('dragleave', function (event) {
        if (event.target === pane) {
            pane.classList.remove('dropping');
        }
    });

    pane.addEventListener('drop', function (event) {
        event.preventDefault();
        pane.classList.remove('dropping');
        stageFiles(event.dataTransfer ? event.dataTransfer.files : []);
    });

    async function stageFiles(fileList) {
        const images = Array.from(fileList || []).filter(file => file.type.startsWith('image/'));
        for (const file of images) {
            if (staged.length >= MAX_IMAGES) {
                showAttachmentError('Up to ' + MAX_IMAGES + ' images per message.');
                break;
            }
            try {
                staged.push(await downscale(file));
            } catch (error) {
                showAttachmentError('Could not read ' + file.name + ': ' + error.message);
            }
        }
        renderAttachments();
    }

    /**
     * Shrinks an image to Claude's tier and re-encodes it as JPEG before upload.
     *
     * Done here rather than on the server because the server has no image library — the pom is
     * frozen — and because it is the upload itself this saves: a modern phone photo is several
     * megabytes, and base64 adds a third on top of that.
     *
     * Re-encoding is also what makes an iPhone photo work at all. The camera writes HEIC, which the
     * server does not accept and Claude cannot read; drawing it to a canvas and reading the canvas
     * back as JPEG converts it on the way out, so the file input can accept image/* and let the
     * browser decode whatever it holds.
     */
    function downscale(file) {
        return new Promise(function (resolve, reject) {
            const objectUrl = URL.createObjectURL(file);
            const image = new Image();
            image.onload = function () {
                const scale = Math.min(1, MAX_EDGE / Math.max(image.width, image.height));
                const canvas = document.createElement('canvas');
                canvas.width = Math.round(image.width * scale);
                canvas.height = Math.round(image.height * scale);
                canvas.getContext('2d').drawImage(image, 0, 0, canvas.width, canvas.height);
                // Always JPEG: it is the smallest of the four types the server accepts, and the
                // canvas has already thrown away any transparency by drawing onto an opaque surface.
                const dataUrl = canvas.toDataURL('image/jpeg', JPEG_QUALITY);
                resolve({
                    mediaType: 'image/jpeg',
                    dataBase64: dataUrl.slice(dataUrl.indexOf(',') + 1),
                    previewUrl: objectUrl,
                    revocable: true
                });
            };
            image.onerror = function () {
                URL.revokeObjectURL(objectUrl);
                // Names the format, because the one case that reaches here in practice is a HEIC
                // from an iPhone opened in a browser that cannot decode it.
                reject(new Error('this browser cannot read ' + (file.type || 'that format')));
            };
            image.src = objectUrl;
        });
    }

    function renderAttachments() {
        attachments.replaceChildren();
        staged.forEach(function (image, index) {
            const item = document.createElement('div');
            item.className = 'attachment';
            const thumbnail = document.createElement('img');
            // For a picked file this is the original object URL rather than the re-encoded base64:
            // it is already in memory and the thumbnail is 64px, so the smaller of the two is
            // beside the point. For a camera frame it is the captured data URL.
            thumbnail.src = image.previewUrl;
            thumbnail.alt = 'Attached image';
            const remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'attachment-remove';
            remove.textContent = '×';
            remove.title = 'Remove';
            remove.addEventListener('click', function () {
                releasePreview(image);
                staged.splice(index, 1);
                renderAttachments();
            });
            item.appendChild(thumbnail);
            item.appendChild(remove);
            attachments.appendChild(item);
        });
        attachments.hidden = staged.length === 0;
    }

    function clearAttachments() {
        staged.forEach(releasePreview);
        staged = [];
        renderAttachments();
    }

    /** Only an object URL holds a blob alive; revoking a data URL is meaningless. */
    function releasePreview(image) {
        if (image.revocable) {
            URL.revokeObjectURL(image.previewUrl);
        }
    }

    function showAttachmentError(message) {
        const bubble = appendBubble('error', message);
        bubble.scrollIntoView({block: 'nearest'});
    }

    input.addEventListener('keydown', function (event) {
        if (event.key === 'Escape' && isPaletteOpen()) {
            event.preventDefault();
            closePalette();
            return;
        }
        if (event.key === 'Enter' && !event.shiftKey) {
            // The palette is not closed here: a typed tool call that does not parse has to leave it
            // open to say why. Sending an ordinary message closes it, and a tool run closes it too.
            event.preventDefault();
            composer.requestSubmit();
        }
    });

    // "!" lists the connected MCP servers, "!/" the tools they expose across every server, and
    // "!<server>/" scopes straight to one server's tools — the same thing clicking that server's
    // row does. All three only at the start of the message, so a "!" inside ordinary prose is just
    // text.
    //
    // Once a space follows the tool name the user is writing arguments, and a filtered list of
    // tools is no longer what they need: the palette switches to that one tool's call syntax and
    // stays there while the arguments are typed.
    input.addEventListener('input', function () {
        const value = input.value;
        if (!value.startsWith('!')) {
            closePalette();
            return;
        }
        const afterBang = value.slice(1);
        const slashIndex = afterBang.indexOf('/');
        if (slashIndex === -1) {
            openServerPalette(afterBang.trim().toLowerCase());
            return;
        }
        const token = afterBang.slice(0, slashIndex).trim();
        const rest = afterBang.slice(slashIndex + 1);
        const spaceIndex = rest.search(/\s/);
        if (spaceIndex >= 0 && rest.slice(0, spaceIndex)) {
            openToolGuide(token, rest.slice(0, spaceIndex));
            return;
        }
        const filter = rest.trim().toLowerCase();
        if (!token) {
            openToolPalette(filter, null);
        } else {
            openToolPaletteForToken(token, filter);
        }
    });

    document.addEventListener('click', function (event) {
        if (isPaletteOpen() && !composer.contains(event.target)) {
            closePalette();
        }
    });

    // Swiping the send button left deletes the whole chat — iOS-style, and a shortcut for mobile
    // where the sidebar's own delete button sits behind the hamburger drawer. Deletes immediately
    // past the threshold, no confirmation — same as the sidebar's own delete button.
    //
    // Pointer Events rather than Touch Events, for two reasons this was actually failing on a
    // real phone with the touch-event version: (1) setPointerCapture pins every subsequent
    // move/up to this element for the life of the gesture, so the drag survives the finger
    // sliding off the button — plain touch events target whatever was under the finger at
    // touchstart too, but a still-`:active` submit button can have iOS hand the gesture back to
    // the page as a touchcancel once the finger leaves its box; (2) `pointerType` filters to
    // touch without needing separate touch/mouse code paths. Arming still only needs a fixed,
    // short throw (SWIPE_TRIGGER_PX) — dragging to the far edge of the composer is a long way to
    // keep a thumb down for and reads as "broken" well before it gets there — but the button is
    // still free to travel visually all the way to the composer's left edge for the full-width
    // look, computed fresh per gesture since the available room depends on the viewport.
    const SWIPE_TRIGGER_PX = 100; // how far left counts as armed-to-delete
    const SWIPE_DEAD_ZONE_PX = 4; // below this, treat the touch as a tap, not a drag
    let swipeStartX = null;
    let swipePointerId = null;
    let swipeDragging = false;
    let swipeArmed = false;
    let swipeMaxVisual = SWIPE_TRIGGER_PX;

    if (sendButton) {
        sendButton.addEventListener('pointerdown', function (event) {
            if (event.pointerType !== 'touch' || sendButton.disabled) {
                return;
            }
            swipeStartX = event.clientX;
            swipePointerId = event.pointerId;
            swipeDragging = false;
            swipeArmed = false;
            const composerRect = composer.getBoundingClientRect();
            const buttonRect = sendButton.getBoundingClientRect();
            swipeMaxVisual = Math.max(buttonRect.left - composerRect.left, SWIPE_TRIGGER_PX);
            sendButton.classList.add('swipe-dragging');
            sendButton.setPointerCapture(event.pointerId);
        });

        sendButton.addEventListener('pointermove', function (event) {
            if (swipeStartX === null || event.pointerId !== swipePointerId) {
                return;
            }
            const deltaX = Math.min(event.clientX - swipeStartX, 0);
            if (!swipeDragging && deltaX > -SWIPE_DEAD_ZONE_PX) {
                return; // still inside the dead zone — could just be a tap
            }
            swipeDragging = true;
            event.preventDefault();
            const dragX = Math.max(deltaX, -swipeMaxVisual);
            sendButton.style.transform = 'translateX(' + dragX + 'px)';
            const armed = -deltaX >= SWIPE_TRIGGER_PX;
            if (armed !== swipeArmed) {
                swipeArmed = armed;
                sendButton.classList.toggle('swipe-armed', armed);
                sendButton.textContent = armed ? 'Delete' : 'Send';
            }
        });

        sendButton.addEventListener('pointerup', function (event) {
            if (event.pointerId !== swipePointerId) {
                return;
            }
            const armed = swipeArmed;
            const dragged = swipeDragging;
            resetSwipe();
            if (armed) {
                event.preventDefault();
                deleteChat();
            } else if (dragged) {
                // A real drag that didn't reach the threshold — swallow the click so releasing
                // early doesn't also send the message.
                event.preventDefault();
            }
        });

        sendButton.addEventListener('pointercancel', function (event) {
            if (event.pointerId === swipePointerId) {
                resetSwipe();
            }
        });
    }

    function resetSwipe() {
        sendButton.classList.remove('swipe-dragging', 'swipe-armed');
        sendButton.style.transform = '';
        sendButton.textContent = 'Send';
        swipeStartX = null;
        swipePointerId = null;
        swipeDragging = false;
        swipeArmed = false;
    }

    // Same endpoint the sidebar's own delete button posts to; built here since deleting the open
    // chat has no existing link/button on screen to reuse under the drawer.
    function deleteChat() {
        const form = document.createElement('form');
        form.method = 'post';
        form.action = '/chats/' + encodeURIComponent(chatId) + '/delete';
        document.body.appendChild(form);
        form.submit();
    }

    // The typed scoping token: everything in the URL before the first ':' or '.', after the scheme.
    // "http://localhost:8080" -> "localhost", "https://gmailmcp.googleapis.com/..." -> "gmailmcp".
    function hostLabel(url) {
        const withoutScheme = url.replace(/^[a-zA-Z][a-zA-Z\d+.-]*:\/\//, '');
        const stopIndex = withoutScheme.search(/[:.]/);
        return stopIndex === -1 ? withoutScheme : withoutScheme.slice(0, stopIndex);
    }

    function isPaletteOpen() {
        return palette && !palette.hidden;
    }

    // Closing also leaves guide mode. "openGuideKey" is what stops openToolGuide re-rendering per
    // keystroke, so a close that left it set would make the guide for that exact "!<server>/<tool>"
    // text unreopenable: every later keystroke would match the stale key, return early, and leave
    // the palette hidden — with no way out except addressing the tool by a different token.
    function closePalette() {
        if (palette) {
            palette.hidden = true;
        }
        openGuideKey = null;
        openGuideError = null;
    }

    // Both catalogues are fetched once per page. Server reachability is therefore a snapshot taken
    // when the picker was first opened — reload to re-probe a server that has since come back.
    function loadCatalogueOnce(url, cache) {
        if (!cache.promise) {
            cache.promise = fetch(url)
                .then(response => (response.ok ? response.json() : []))
                .catch(() => []);
        }
        return cache.promise;
    }

    // Every server lookup goes through here so the snapshot the renderers read is never stale.
    function loadServers() {
        return loadCatalogueOnce('/mcp-servers', serversCache).then(function (servers) {
            knownServers = servers;
            return servers;
        });
    }

    // The "!<token>/" prefix that addresses a server: the URL host label the picker itself shows,
    // falling back to the id before the catalogue has loaded. "resolveServerToken" accepts either.
    function serverToken(serverId) {
        const server = knownServers.find(candidate => candidate.id === serverId);
        return server ? hostLabel(server.url) : serverId;
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
        const servers = await loadServers();
        const matching = servers.filter(server => matches(filter, server.name, server.id));
        setPaletteTitle('MCP servers');
        renderServers(matching);
        palette.hidden = false;
    }

    async function openToolPalette(filter, serverId) {
        if (!palette) {
            return;
        }
        const tools = await loadCatalogueOnce('/tools', toolsCache);
        const matching = tools.filter(tool =>
            (!serverId || tool.serverId === serverId) && matches(filter, tool.name, tool.title));
        setPaletteTitle(serverId ? 'Tools on ' + serverId : 'Tools on every connected server');
        if (matching.length === 0 && serverId) {
            await renderNoToolsReason(serverId);
        } else {
            renderTools(matching);
        }
        palette.hidden = false;
    }

    // "!<token>/" scopes directly to one server, resolved against the servers list rather than
    // requiring the exact id the picker itself uses. An empty or ambiguous match still opens the
    // palette — silently showing nothing would look like the picker was broken.
    async function openToolPaletteForToken(token, filter) {
        if (!palette) {
            return;
        }
        const servers = await loadServers();
        const resolved = resolveServerToken(token, servers);
        if (resolved.status === 'unique') {
            await openToolPalette(filter, resolved.server.id);
            return;
        }
        setPaletteTitle('Tools on ' + token);
        clearList();
        if (resolved.status === 'ambiguous') {
            renderEmpty("'" + token + "' matches more than one server ("
                + resolved.servers.map(server => server.id).join(', ') + ') — type more of the name');
        } else {
            renderEmpty("No server matches '" + token + "'. Try: "
                + servers.map(server => server.id).join(', '));
        }
        palette.hidden = false;
    }

    // Prefers an exact match against the id or the URL-derived host label, then falls back to a
    // unique id/name/host-label prefix match, case-insensitive either way — "!local/",
    // "!localhost/" and "!gmail/"/"!gmailmcp/" should all work without typing the full id.
    function resolveServerToken(token, servers) {
        const lower = token.toLowerCase();
        const exact = servers.find(server =>
            server.id.toLowerCase() === lower || hostLabel(server.url).toLowerCase() === lower);
        if (exact) {
            return {status: 'unique', server: exact};
        }
        const prefixMatches = servers.filter(server =>
            server.id.toLowerCase().startsWith(lower)
            || (server.name || '').toLowerCase().startsWith(lower)
            || hostLabel(server.url).toLowerCase().startsWith(lower));
        const uniqueMatches = [...new Map(prefixMatches.map(server => [server.id, server])).values()];
        if (uniqueMatches.length === 1) {
            return {status: 'unique', server: uniqueMatches[0]};
        }
        if (uniqueMatches.length === 0) {
            return {status: 'none'};
        }
        return {status: 'ambiguous', servers: uniqueMatches};
    }

    // "/tools" silently drops any server it cannot reach, so scoping to one that is down would
    // otherwise just show the generic empty state with no explanation of why.
    async function renderNoToolsReason(serverId) {
        clearList();
        try {
            const servers = await loadServers();
            const server = servers.find(candidate => candidate.id === serverId);
            if (server && server.reachable === false) {
                renderEmpty(server.id + ' is unreachable: ' + (server.detail || server.url));
                return;
            }
        } catch (lookupFailure) {
            // Fall through to the generic message below.
        }
        renderEmpty('No tools available.');
    }

    function matches(filter, ...fields) {
        return !filter || fields.some(field => (field || '').toLowerCase().includes(filter));
    }

    // Anything that puts a list back on screen leaves guide mode, so the next tool the user types
    // renders fresh instead of being mistaken for the one already shown.
    function clearList() {
        toolList.replaceChildren();
        openGuideKey = null;
        openGuideError = null;
    }

    function renderServers(servers) {
        clearList();
        if (servers.length === 0) {
            renderEmpty('No MCP servers configured.');
            return;
        }
        servers.forEach(function (server) {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'tool-option';

            // Not just the name: the Local/Remote and reachability badges are appended into it.
            const nameRow = document.createElement('span');
            nameRow.className = 'tool-option-name';
            nameRow.textContent = server.name;

            const category = document.createElement('span');
            category.className = server.local ? 'server-status local' : 'server-status remote';
            category.textContent = server.local ? 'Local' : 'Remote';
            nameRow.appendChild(category);

            const status = document.createElement('span');
            status.className = server.reachable ? 'server-status online' : 'server-status offline';
            status.textContent = server.reachable
                ? server.toolCount + (server.toolCount === 1 ? ' tool' : ' tools')
                : 'unreachable';
            nameRow.appendChild(status);

            const description = document.createElement('span');
            description.className = 'tool-option-description';
            // Leads with the exact "!<token>/" the user can type, so scoping to a server never
            // requires guessing at its id. The failure reason matters more than the URL when a
            // server is down.
            const hint = '!' + hostLabel(server.url) + '/';
            description.textContent = server.reachable
                ? hint + '  ·  ' + server.url + '  ·  ' + server.protocol
                : hint + '  ·  ' + (server.detail || server.url);

            button.append(nameRow, description);
            // Drilling in beats making the user retype: the tools are already loaded.
            button.addEventListener('click', () => {
                input.value = '!' + hostLabel(server.url) + '/';
                openToolPalette('', server.id);
            });
            const item = document.createElement('li');
            item.appendChild(button);
            toolList.appendChild(item);
        });
    }

    function renderTools(tools) {
        clearList();
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
                const inputCountBadge = document.createElement('span');
                inputCountBadge.className = 'server-status needs-input';
                inputCountBadge.textContent = tool.parameters.length
                    + (tool.parameters.length === 1 ? ' input' : ' inputs');
                name.appendChild(inputCountBadge);
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

    // Clicking a tool fills the composer with the call that runs it and switches the palette from
    // the list to that one tool: how to write the inputs by hand, and the same fields as before for
    // anyone who would rather not. Nothing fires on the click itself — including for a tool that
    // takes no arguments, because one stray click sending a Gmail draft or deleting a calendar
    // event was too easy to hit.
    function selectTool(tool) {
        const prefix = '!' + serverToken(tool.serverId) + '/' + tool.name + ' ';
        input.value = prefix;
        renderToolGuide(tool, null, guideKey(serverToken(tool.serverId), tool.name));
        input.focus();
        input.setSelectionRange(prefix.length, prefix.length);
    }

    // What identifies the guide on screen: the text that addresses the tool, not the tool itself.
    // "!local/x" and "!localhost/x" reach the same tool and both must hold the panel still.
    function guideKey(serverTokenText, toolName) {
        return serverTokenText.toLowerCase() + '/' + toolName;
    }

    // Shown while the user is writing "!<server>/<tool> name=value". Re-rendered only when the
    // addressed tool changes: re-running it per keystroke would reset the fields and steal focus.
    async function openToolGuide(serverTokenText, toolName) {
        if (!palette) {
            return;
        }
        const key = guideKey(serverTokenText, toolName);
        if (openGuideKey === key) {
            hideGuideError();
            return;
        }
        const resolution = await resolveCommandTool(serverTokenText, toolName);
        if (resolution.error) {
            showPaletteMessage('Run a tool', resolution.error);
            return;
        }
        renderToolGuide(resolution.tool, null, key);
        palette.hidden = false;
    }

    function renderToolGuide(tool, errorText, key) {
        const parameters = tool.parameters || [];
        clearList();
        openGuideKey = key;
        setPaletteTitle('Run ' + tool.name);

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

        if (tool.description) {
            const about = document.createElement('p');
            about.className = 'tool-form-about';
            about.textContent = tool.description;
            item.appendChild(about);
        }

        // The call, spelled out. Placeholders rather than invented values: what the user needs is
        // which inputs exist and what type each one wants, not a plausible-looking example.
        const usage = document.createElement('p');
        usage.className = 'tool-usage';
        usage.textContent = usageLine(tool, parameters);
        item.appendChild(usage);

        const note = document.createElement('p');
        note.className = 'tool-usage-note';
        note.textContent = parameters.length === 0
            ? 'Takes no inputs — press Enter to run it.'
            : 'Write them after the tool name as name=value, space separated, quoting any value that'
                + ' contains a space (query="is:unread from:me"). Optional inputs can be left out.'
                + ' Enter runs the tool — or fill the fields below and press Run.';
        item.appendChild(note);

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
        error.textContent = errorText || '';
        error.hidden = !errorText;
        item.appendChild(error);
        openGuideError = error;

        const actions = document.createElement('div');
        actions.className = 'tool-form-actions';
        actions.append(
            paletteButton('Back', 'tool-form-cancel', back),
            paletteButton('Run', 'tool-form-run', submit));
        item.appendChild(actions);

        toolList.appendChild(item);
        // Focus stays in the composer: the pre-filled call is there and the fields are the
        // alternative to typing it, not the other way round.

        function back() {
            input.value = '!' + serverToken(tool.serverId) + '/';
            openToolPalette('', tool.serverId);
            input.focus();
        }

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

    // At most this many inputs in the usage line; a tool with a dozen optional ones would wrap into
    // a wall of text and teach nothing. The fields below it always list every one.
    const USAGE_INPUT_LIMIT = 6;

    // "!localhost/gmail_search_messages query=<string> maxResults=<integer>", required inputs
    // first — the sort is stable, so within each group the server's own order survives.
    function usageLine(tool, parameters) {
        const call = '!' + serverToken(tool.serverId) + '/' + tool.name;
        const ordered = parameters.slice()
            .sort((left, right) => Number(right.required) - Number(left.required));
        const shown = ordered.slice(0, USAGE_INPUT_LIMIT)
            .map(parameter => parameter.name + '=<' + parameter.type + '>');
        if (ordered.length > USAGE_INPUT_LIMIT) {
            shown.push('…');
        }
        return shown.length === 0 ? call : call + ' ' + shown.join(' ');
    }

    function hideGuideError() {
        if (openGuideError) {
            openGuideError.hidden = true;
        }
    }

    function showPaletteMessage(title, text) {
        if (!palette) {
            return;
        }
        setPaletteTitle(title);
        clearList();
        renderEmpty(text);
        palette.hidden = false;
    }

    // "!<server>/<tool> name=value …" — the typed form of a tool call, and the only shape the
    // composer intercepts. Null for anything else, so "!" on its own, "!important note" and a bare
    // "!localhost/" still go to the model as ordinary text.
    function parseCommand(text) {
        if (!text.startsWith('!')) {
            return null;
        }
        const afterBang = text.slice(1);
        const slashIndex = afterBang.indexOf('/');
        if (slashIndex === -1) {
            return null;
        }
        const rest = afterBang.slice(slashIndex + 1).trimStart();
        const spaceIndex = rest.search(/\s/);
        const toolName = spaceIndex === -1 ? rest : rest.slice(0, spaceIndex);
        if (!toolName) {
            return null;
        }
        return {
            serverToken: afterBang.slice(0, slashIndex).trim(),
            toolName: toolName,
            argumentText: spaceIndex === -1 ? '' : rest.slice(spaceIndex + 1)
        };
    }

    async function runTypedCommand(command) {
        const resolution = await resolveCommandTool(command.serverToken, command.toolName);
        if (resolution.error) {
            showPaletteMessage('Run a tool', resolution.error);
            return;
        }
        const parsed = parseArguments(command.argumentText, resolution.tool.parameters || []);
        if (parsed.error) {
            // Re-rendered with the reason instead of sent: the line the user wrote stays in the
            // composer to be corrected, rather than reaching the model as a message about a tool.
            renderToolGuide(resolution.tool, parsed.error,
                guideKey(command.serverToken, command.toolName));
            palette.hidden = false;
            return;
        }
        runTool(resolution.tool, parsed.arguments);
    }

    // A tool name is unique per server, not globally, so "!/<tool>" is only unambiguous when one
    // server offers it. Everything here is an error string rather than a throw: the palette says
    // what went wrong and the composer keeps the text.
    async function resolveCommandTool(serverTokenText, toolName) {
        const servers = await loadServers();
        const tools = await loadCatalogueOnce('/tools', toolsCache);
        let candidates = tools.filter(tool => tool.name === toolName);
        if (serverTokenText) {
            const resolved = resolveServerToken(serverTokenText, servers);
            if (resolved.status === 'none') {
                return {error: "No server matches '" + serverTokenText + "'. Try: "
                        + servers.map(server => server.id).join(', ')};
            }
            if (resolved.status === 'ambiguous') {
                return {error: "'" + serverTokenText + "' matches more than one server ("
                        + resolved.servers.map(server => server.id).join(', ')
                        + ') — type more of the name'};
            }
            candidates = candidates.filter(tool => tool.serverId === resolved.server.id);
            if (candidates.length === 0) {
                return {error: 'No tool named ' + toolName + ' on ' + resolved.server.id + '.'};
            }
        }
        if (candidates.length === 0) {
            return {error: 'No tool named ' + toolName + ' on any connected server.'};
        }
        if (candidates.length > 1) {
            return {error: toolName + ' exists on '
                    + candidates.map(tool => tool.serverId).join(' and ')
                    + ' — say which: !<server>/' + toolName};
        }
        return {tool: candidates[0]};
    }

    // "name=value name=\"two words\"" against the tool's own schema. An unknown name is a typo far
    // more often than a schema the palette never saw, so it is reported rather than forwarded and
    // turned into the server's less helpful rejection.
    function parseArguments(text, parameters) {
        const args = {};
        // Not words: splitRespectingQuotes deliberately keeps quoted spaces, so one element is a
        // whole assignment such as 'query=is:unread from:me'.
        const assignments = splitRespectingQuotes(text);
        for (const assignment of assignments) {
            const equalsIndex = assignment.indexOf('=');
            if (equalsIndex <= 0) {
                return {error: "'" + assignment + "' is not name=value."};
            }
            const name = assignment.slice(0, equalsIndex);
            const parameter = parameters.find(candidate => candidate.name === name);
            if (!parameter) {
                return {error: "'" + name + "' is not an input of this tool. "
                        + (parameters.length === 0
                            ? 'It takes none.'
                            : 'Try: ' + parameters.map(candidate => candidate.name).join(', '))};
            }
            const raw = assignment.slice(equalsIndex + 1);
            if (raw === '') {
                continue; // Omitted rather than sent empty, so the server applies its own default.
            }
            args[name] = coerce(raw, parameter.type);
        }
        const missing = parameters
            .filter(parameter => parameter.required && !(parameter.name in args))
            .map(parameter => parameter.name);
        if (missing.length > 0) {
            return {error: 'Fill in: ' + missing.join(', ')};
        }
        return {arguments: args};
    }

    // Splits on whitespace but not inside quotes, so a value may contain spaces:
    // 'query="is:unread from:me" max=5' -> ['query=is:unread from:me', 'max=5'].
    function splitRespectingQuotes(text) {
        const words = [];
        let current = '';
        let started = false;
        let quote = null;
        for (const character of text) {
            if (quote) {
                if (character === quote) {
                    quote = null;
                } else {
                    current += character;
                }
                continue;
            }
            if (character === '"' || character === "'") {
                quote = character;
                started = true;
                continue;
            }
            if (/\s/.test(character)) {
                if (started) {
                    words.push(current);
                    current = '';
                    started = false;
                }
                continue;
            }
            current += character;
            started = true;
        }
        if (started) {
            words.push(current);
        }
        return words;
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

    // Echoes the call the way it was made, in the same syntax the composer accepts — so a call made
    // through the fields can be re-run, or edited and re-run, by copying the line back.
    function describeCall(tool, args) {
        const call = '!' + serverToken(tool.serverId) + '/' + tool.name;
        const names = Object.keys(args);
        if (names.length === 0) {
            return call;
        }
        return call + ' ' + names.map(name => name + '=' + formatValue(args[name])).join(' ');
    }

    // Quoted only when it has to be. A quote inside the value is dropped rather than escaped: the
    // echo is there to be re-typed, and the argument that was actually sent is the one above.
    function formatValue(value) {
        if (Array.isArray(value)) {
            return formatValue(value.join(','));
        }
        const text = String(value);
        return /[\s"']/.test(text) ? '"' + text.replace(/["']/g, '') + '"' : text;
    }

    async function sendMessage(text) {
        setSending(true);
        // Taken before clearing, so the request body and the echoed bubble see the same images even
        // though the composer is emptied immediately.
        const images = staged.map(image => ({mediaType: image.mediaType, dataBase64: image.dataBase64}));
        appendUserBubble(text, staged);
        input.value = '';
        clearAttachments();
        const assistantBubble = appendBubble('assistant', '');
        try {
            const response = await fetch('/chats/' + encodeURIComponent(chatId) + '/messages/stream', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({content: text, images: images})
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

    // targetBubble, not the assistant bubble: runTool passes the tool-result bubble, so the failing
    // call and the failing turn both report through here.
    function showError(targetBubble, classification, message) {
        if (targetBubble.textContent === '') {
            targetBubble.remove(); // no partial answer to keep
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

    /**
     * The user's own bubble, showing the images it carries.
     *
     * Rendered from the base64 already in hand rather than waiting for the server to hand back
     * ids: the message should appear the instant it is sent, and on the next reload the same
     * images come back from /attachments/{id} instead.
     */
    function appendUserBubble(text, images) {
        const bubble = document.createElement('div');
        bubble.className = 'bubble user';
        images.forEach(function (image) {
            const thumbnail = document.createElement('img');
            thumbnail.className = 'bubble-image';
            thumbnail.src = 'data:' + image.mediaType + ';base64,' + image.dataBase64;
            thumbnail.alt = 'Attached image';
            bubble.appendChild(thumbnail);
        });
        if (text) {
            const span = document.createElement('span');
            span.textContent = text;
            bubble.appendChild(span);
        }
        messages.appendChild(bubble);
        scrollToBottom();
        return bubble;
    }

    function setSending(sending) {
        input.disabled = sending;
        sendButton.disabled = sending;
        // Attaching mid-stream would stage an image for a message that has already gone.
        attachButton.disabled = sending;
        cameraButton.disabled = sending;
        if (sending) {
            // The preview would otherwise sit live above a streaming answer, camera light and all.
            closeLiveCamera();
        }
    }

    function scrollToBottom() {
        messages.scrollTop = messages.scrollHeight;
    }
})();
