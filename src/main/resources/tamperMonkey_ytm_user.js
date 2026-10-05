// ==UserScript==
// @name         YTM Local Player Button
// @match        https://music.youtube.com/*
// @grant        GM_download
// @grant        unsafeWindow
// ==/UserScript==

(function () {
    'use strict';

    var PREFIX = 'https://music.youtube.com/playlist?list=';
    var STORAGE_KEY = 'localPlaylist';
    var DIALOG_ID = 'tm-local-player-dialog';
    var SVG_NS = 'http://www.w3.org/2000/svg';
    var TRASH_PATH = 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z';
    var dialogListBox = null;

    console.log('[ALP] script started (v6 upload playlists) on', location.href);

    function makeId() {
        var hex = '0123456789abcdef';
        var out = '';
        for (var i = 0; i < 32; i++) {
            if (i === 8 || i === 12 || i === 16 || i === 20) out += '-';
            var r = Math.floor(Math.random() * 16);
            if (i === 12) r = 4;
            if (i === 16) r = (r % 4) + 8;
            out += hex.charAt(r);
        }
        return out;
    }

    function getTitle() {
        var el = document.querySelector('ytmusic-responsive-header-renderer h1, ytmusic-detail-header-renderer h2');
        if (el && el.textContent.trim()) return el.textContent.trim();
        return document.title.replace(' - YouTube Music', '').trim();
    }

    // Album list ids start with "OLAK5uy_"; user playlists don't have an artist.
    // The artist sits in the header strapline (the "Arcade Fire" link above the cover).
    function getArtist(listId) {
        if (!listId || listId.indexOf('OLAK5uy_') !== 0) {
            console.log('[ALP] not an album list id, artist = playlist');
            return 'playlist';
        }
        var header = document.querySelector('ytmusic-responsive-header-renderer, ytmusic-detail-header-renderer');
        if (header) {
            var strap = header.querySelector('.strapline-text');
            if (strap && strap.textContent.trim()) {
                console.log('[ALP] artist from strapline:', strap.textContent.trim());
                return strap.textContent.trim();
            }
            var link = header.querySelector('a[href*="channel/"], a[href*="browse/UC"]');
            if (link && link.textContent.trim()) {
                console.log('[ALP] artist from channel link:', link.textContent.trim());
                return link.textContent.trim();
            }
        }
        console.log('[ALP] artist not found on page, artist = playlist');
        return 'playlist';
    }

    function loadList() {
        var list = [];
        try {
            list = JSON.parse(localStorage.getItem(STORAGE_KEY)) || [];
        } catch (e) {
            console.log('[ALP] could not parse existing localPlaylist, starting empty', e);
        }
        if (!Array.isArray(list)) list = [];
        return list;
    }

    function saveList(list) {
        var json = JSON.stringify(list, null, 2);
        localStorage.setItem(STORAGE_KEY, json);
        console.log('[ALP] saved localPlaylist, total entries:', list.length);
        return json;
    }

    function saveToLocalStorage() {
        var listId = new URL(location.href).searchParams.get('list');
        var url = PREFIX + listId;
        var title = getArtist(listId) + ' - ' + getTitle();
        var timestamp = Date.now();
        console.log('[ALP] playlist:', title, url, timestamp);

        var list = loadList();
        console.log('[ALP] existing entries:', list.length);

        var existing = null;
        for (var i = 0; i < list.length; i++) {
            if (list[i] && list[i].url === url) {
                existing = list[i];
                break;
            }
        }

        if (existing) {
            existing.title = title;
            existing.updatedDate = timestamp;
            console.log('[ALP] updated existing entry', existing);
        } else {
            var entry = { id: makeId(), title: title, url: url, updatedDate: timestamp };
            list.push(entry);
            console.log('[ALP] added new entry', entry);
        }

        return { json: saveList(list), timestamp: timestamp };
    }

    function deleteFromLocalStorage(id) {
        var list = loadList();
        var kept = [];
        for (var i = 0; i < list.length; i++) {
            if (list[i] && list[i].id !== id) kept.push(list[i]);
        }
        console.log('[ALP] deleting', id, '- entries before:', list.length, 'after:', kept.length);
        return { json: saveList(kept), timestamp: Date.now() };
    }

    function downloadJson(json, timestamp) {
        var blob = new Blob([json], { type: 'text/plain' });
        var blobUrl = URL.createObjectURL(blob);
        console.log('[ALP] blob ready, size:', blob.size);

        function cleanup() {
            setTimeout(function () {
                URL.revokeObjectURL(blobUrl);
            }, 10000);
        }

        function plainDownload() {
            var a = document.createElement('a');
            a.href = blobUrl;
            a.download = timestamp + '_playlists.txt';
            document.body.appendChild(a);
            a.click();
            a.remove();
            cleanup();
            console.log('[ALP] plain download triggered:', a.download);
        }

        // GM_download only exists with "@grant GM_download"; it can save into Downloads/ytm_playlists/
        if (typeof GM_download === 'function') {
            var name = 'ytm_playlists/' + timestamp + '_playlists.txt';
            console.log('[ALP] using GM_download:', name);
            GM_download({
                url: 'data:text/plain;charset=utf-8,' + encodeURIComponent(json),
                name: name,
                saveAs: false,
                onload: function () {
                    console.log('[ALP] GM_download finished');
                    cleanup();
                },
                onerror: function (err) {
                    console.log('[ALP] GM_download failed, falling back', err);
                    plainDownload();
                }
            });
        } else {
            console.log('[ALP] GM_download not available, using plain download');
            plainDownload();
        }
    }

    // ---------- upload ----------

    function isPlaylistEntry(item) {
        if (!item || typeof item !== 'object' || Array.isArray(item)) return false;
        if (typeof item.id !== 'string' || item.id.trim() === '') return false;
        if (typeof item.title !== 'string') return false;
        if (typeof item.url !== 'string' || item.url.trim() === '') return false;
        if (typeof item.updatedDate !== 'number' || !isFinite(item.updatedDate)) return false;
        return true;
    }

    function parsePlaylistExport(text) {
        if (typeof text !== 'string') throw new Error('file is not text');
        if (text.charCodeAt(0) === 0xFEFF) text = text.slice(1);
        var parsed;
        try {
            parsed = JSON.parse(text);
        } catch (e) {
            throw new Error('file is not a JSON array');
        }
        if (!Array.isArray(parsed)) throw new Error('expected a JSON array');
        for (var i = 0; i < parsed.length; i++) {
            if (!isPlaylistEntry(parsed[i])) {
                throw new Error('entry ' + (i + 1) + ' does not match the playlist contract');
            }
        }
        return parsed;
    }

    function isTextPlaylistFile(file) {
        if (!file) return false;
        var name = (file.name || '').toLowerCase();
        var type = (file.type || '').toLowerCase();
        if (!name.endsWith('.txt')) return false;
        if (!type) return true;
        return type.indexOf('text/') === 0 || type === 'application/octet-stream';
    }

    function filePicker() {
        var win = typeof unsafeWindow !== 'undefined' ? unsafeWindow : window;
        if (win && typeof win.showOpenFilePicker === 'function') {
            return win.showOpenFilePicker.bind(win);
        }
        if (typeof showOpenFilePicker === 'function') return showOpenFilePicker.bind(window);
        return null;
    }

    // One text file. Downloads when the browser can start there; otherwise the dialog's default, usually Home.
    function pickerOptions() {
        return {
            multiple: false,
            excludeAcceptAllOption: true,
            startIn: 'downloads',
            types: [{
                description: 'Text',
                accept: { 'text/plain': ['.txt'] }
            }]
        };
    }

    function readFileText(file) {
        return new Promise(function (resolve, reject) {
            var reader = new FileReader();
            reader.onload = function () { resolve(String(reader.result || '')); };
            reader.onerror = function () { reject(reader.error || new Error('could not read file')); };
            reader.readAsText(file);
        });
    }

    function applyUploadedText(text) {
        var list = parsePlaylistExport(text);
        // Uploaded file replaces localPlaylist. Do not merge, update, or append.
        saveList(list);
        if (dialogListBox && document.body.contains(dialogListBox)) renderList(dialogListBox);
        console.log('[ALP] replaced localPlaylist from upload, entries:', list.length);
        return list;
    }

    function cancelled() {
        var err = new Error('cancelled');
        err.name = 'AbortError';
        return err;
    }

    function chooseWithInput() {
        return new Promise(function (resolve, reject) {
            var input = document.createElement('input');
            input.type = 'file';
            input.accept = 'text/plain,.txt';
            input.multiple = false;
            input.style.display = 'none';
            var settled = false;
            function finish(err, file) {
                if (settled) return;
                settled = true;
                input.remove();
                if (err) reject(err);
                else resolve(file);
            }
            input.addEventListener('change', function () {
                var file = input.files && input.files[0];
                if (!file) finish(cancelled());
                else finish(null, file);
            });
            input.addEventListener('cancel', function () {
                finish(cancelled());
            });
            document.body.appendChild(input);
            input.click();
        });
    }

    function choosePlaylistFile() {
        var picker = filePicker();
        if (!picker) {
            console.log('[ALP] file picker API missing, using input (browser default folder, usually Home)');
            return chooseWithInput();
        }
        try {
            console.log('[ALP] opening file chooser in Downloads');
            return Promise.resolve(picker(pickerOptions())).then(function (handles) {
                if (!handles || !handles.length) throw cancelled();
                return handles[0].getFile();
            });
        } catch (e) {
            console.log('[ALP] Downloads chooser unavailable, using input', e);
            return chooseWithInput();
        }
    }

    // ---------- edit dialog ----------

    function makeTrashIcon() {
        var svg = document.createElementNS(SVG_NS, 'svg');
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('width', '20');
        svg.setAttribute('height', '20');
        var path = document.createElementNS(SVG_NS, 'path');
        path.setAttribute('d', TRASH_PATH);
        path.setAttribute('fill', 'currentColor');
        svg.appendChild(path);
        return svg;
    }

    function closeDialog() {
        var dlg = document.getElementById(DIALOG_ID);
        if (dlg) dlg.remove();
        dialogListBox = null;
        document.removeEventListener('keydown', onDialogKey, true);
        console.log('[ALP] dialog closed');
    }

    function onDialogKey(e) {
        if (e.key === 'Escape') closeDialog();
    }

    function renderList(listBox) {
        while (listBox.firstChild) listBox.removeChild(listBox.firstChild);

        var list = loadList();
        list.sort(function (a, b) {
            var ta = String((a && (a.title || a.url)) || '');
            var tb = String((b && (b.title || b.url)) || '');
            return ta.localeCompare(tb, undefined, { sensitivity: 'base', numeric: true });
        });
        if (list.length === 0) {
            var empty = document.createElement('div');
            empty.textContent = 'No playlists saved yet.';
            empty.style.color = '#aaa';
            empty.style.padding = '16px 0';
            empty.style.textAlign = 'center';
            listBox.appendChild(empty);
            return;
        }

        list.forEach(function (item) {
            var row = document.createElement('div');
            row.style.display = 'flex';
            row.style.alignItems = 'center';
            row.style.gap = '12px';
            row.style.padding = '8px 0';
            row.style.borderBottom = '1px solid #333';

            var title = document.createElement('div');
            title.textContent = item.title || item.url;
            title.title = item.url;
            title.style.flex = '1';
            title.style.overflow = 'hidden';
            title.style.textOverflow = 'ellipsis';
            title.style.whiteSpace = 'nowrap';

            var del = document.createElement('button');
            del.type = 'button';
            del.title = 'Delete';
            del.setAttribute('aria-label', 'Delete ' + (item.title || item.url));
            del.style.background = 'transparent';
            del.style.border = 'none';
            del.style.color = '#ff6b6b';
            del.style.cursor = 'pointer';
            del.style.padding = '4px';
            del.style.display = 'flex';
            del.appendChild(makeTrashIcon());
            del.addEventListener('click', function () {
                console.log('[ALP] delete clicked for', item.title);
                try {
                    var saved = deleteFromLocalStorage(item.id);
                    downloadJson(saved.json, saved.timestamp);
                    renderList(listBox);
                } catch (e) {
                    console.log('[ALP] delete FAILED:', e);
                }
            });

            row.appendChild(title);
            row.appendChild(del);
            listBox.appendChild(row);
        });
    }

    function openDialog() {
        closeDialog();
        console.log('[ALP] opening dialog');

        var overlay = document.createElement('div');
        overlay.id = DIALOG_ID;
        overlay.style.position = 'fixed';
        overlay.style.inset = '0';
        overlay.style.background = 'rgba(0, 0, 0, 0.6)';
        overlay.style.zIndex = '99999';
        overlay.style.display = 'flex';
        overlay.style.alignItems = 'center';
        overlay.style.justifyContent = 'center';
        overlay.addEventListener('click', function (e) {
            if (e.target === overlay) closeDialog();
        });

        var panel = document.createElement('div');
        panel.style.background = '#212121';
        panel.style.color = '#fff';
        panel.style.fontFamily = 'Roboto, Arial, sans-serif';
        panel.style.fontSize = '14px';
        panel.style.borderRadius = '12px';
        panel.style.padding = '20px 24px';
        panel.style.width = 'min(480px, calc(100vw - 32px))';
        panel.style.maxHeight = '70vh';
        panel.style.display = 'flex';
        panel.style.flexDirection = 'column';
        panel.style.boxShadow = '0 8px 32px rgba(0, 0, 0, 0.5)';

        var header = document.createElement('div');
        header.style.display = 'flex';
        header.style.alignItems = 'center';
        header.style.marginBottom = '12px';

        var heading = document.createElement('div');
        heading.textContent = 'Local playlists';
        heading.style.flex = '1';
        heading.style.fontSize = '18px';
        heading.style.fontWeight = '500';

        var close = document.createElement('button');
        close.type = 'button';
        close.textContent = 'close';
        styleButton(close);
        close.addEventListener('click', closeDialog);

        header.appendChild(heading);
        header.appendChild(close);

        var listBox = document.createElement('div');
        listBox.style.overflowY = 'auto';

        panel.appendChild(header);
        panel.appendChild(listBox);
        overlay.appendChild(panel);
        document.body.appendChild(overlay);
        document.addEventListener('keydown', onDialogKey, true);

        dialogListBox = listBox;
        renderList(listBox);
    }

    // ---------- buttons ----------

    function styleButton(btn) {
        btn.style.background = '#fff';
        btn.style.color = '#030303';
        btn.style.border = 'none';
        btn.style.borderRadius = '18px';
        btn.style.padding = '8px 18px';
        btn.style.fontSize = '14px';
        btn.style.fontWeight = '500';
        btn.style.cursor = 'pointer';
    }

    function createButton() {
        var wrap = document.createElement('div');
        wrap.id = 'tm-add-to-local-player';
        wrap.style.display = 'flex';
        wrap.style.flexWrap = 'wrap';
        wrap.style.width = '100%';
        wrap.style.marginTop = '12px';
        wrap.style.justifyContent = 'center';
        wrap.style.gap = '8px';

        var btn = document.createElement('button');
        btn.type = 'button';
        btn.textContent = 'add Playlist';
        styleButton(btn);
        btn.addEventListener('click', function () {
            console.log('[ALP] button clicked');
            try {
                var saved = saveToLocalStorage();
                downloadJson(saved.json, saved.timestamp);
                btn.textContent = 'added';
                setTimeout(function () {
                    btn.textContent = 'add to local player';
                }, 1500);
            } catch (e) {
                console.log('[ALP] save/download FAILED:', e);
            }
        });

        var edit = document.createElement('button');
        edit.type = 'button';
        edit.textContent = 'Manage Playlists';
        styleButton(edit);
        edit.addEventListener('click', function () {
            console.log('[ALP] edit local clicked');
            try {
                openDialog();
            } catch (e) {
                console.log('[ALP] open dialog FAILED:', e);
            }
        });

        var upload = document.createElement('button');
        upload.type = 'button';
        upload.textContent = 'Import Playlists';
        styleButton(upload);
        upload.addEventListener('click', function () {
            console.log('[ALP] upload playlists clicked');
            choosePlaylistFile().then(function (file) {
                if (!isTextPlaylistFile(file)) throw new Error('file must be a text file');
                return readFileText(file);
            }).then(function (text) {
                applyUploadedText(text);
                upload.textContent = 'uploaded';
                setTimeout(function () { upload.textContent = 'Upload Playlists'; }, 1500);
            }).catch(function (e) {
                if (e && e.name === 'AbortError') {
                    console.log('[ALP] upload cancelled');
                    return;
                }
                console.log('[ALP] upload FAILED:', e);
                upload.textContent = 'invalid file';
                upload.title = e && e.message ? e.message : 'invalid file';
                setTimeout(function () {
                    upload.textContent = 'Upload Playlists';
                    upload.title = '';
                }, 2000);
            });
        });

        wrap.appendChild(btn);
        wrap.appendChild(edit);
        wrap.appendChild(upload);
        return wrap;
    }

    setInterval(function () {
        if (!location.href.startsWith(PREFIX)) return;
        if (document.getElementById('tm-add-to-local-player')) return;

        var row = document.querySelector('#action-buttons');
        if (!row) return;

        row.insertAdjacentElement('afterend', createButton());
        console.log('[ALP] button inserted');
    }, 1000);
})();
