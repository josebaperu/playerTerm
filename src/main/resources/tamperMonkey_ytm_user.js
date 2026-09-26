// ==UserScript==
// @name         YTM Local Player Button
// @match        https://music.youtube.com/*
// @grant        GM_download
// ==/UserScript==

(function () {
    'use strict';

    var PREFIX = 'https://music.youtube.com/playlist?list=';
    var STORAGE_KEY = 'localPlaylist';
    var DIALOG_ID = 'tm-local-player-dialog';
    var SVG_NS = 'http://www.w3.org/2000/svg';
    var TRASH_PATH = 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z';

    console.log('[ALP] script started (v5 artist title) on', location.href);

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
        document.removeEventListener('keydown', onDialogKey, true);
        console.log('[ALP] dialog closed');
    }

    function onDialogKey(e) {
        if (e.key === 'Escape') closeDialog();
    }

    function renderList(listBox) {
        while (listBox.firstChild) listBox.removeChild(listBox.firstChild);

        var list = loadList();
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
        wrap.style.width = '100%';
        wrap.style.marginTop = '12px';
        wrap.style.justifyContent = 'center';
        wrap.style.gap = '8px';

        var btn = document.createElement('button');
        btn.type = 'button';
        btn.textContent = 'add to local player';
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
        edit.textContent = 'edit local';
        styleButton(edit);
        edit.addEventListener('click', function () {
            console.log('[ALP] edit local clicked');
            try {
                openDialog();
            } catch (e) {
                console.log('[ALP] open dialog FAILED:', e);
            }
        });

        wrap.appendChild(btn);
        wrap.appendChild(edit);
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
