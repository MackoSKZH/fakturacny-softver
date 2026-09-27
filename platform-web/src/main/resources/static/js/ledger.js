// Tabulka poloziek (penazny dennik) v style Notion: uprava v bunke, tagy, filtre, hromadne akcie, import.
// Vsetky kontroly robi aj server - klient len zlepsuje pohodlie.
(function () {
    'use strict';
    var root = document.getElementById('ledger');
    if (!root) {
        return;
    }
    var canEdit = root.getAttribute('data-can-edit') === 'true';
    var csrfToken = meta('csrf-token');
    var csrfHeader = meta('csrf-header');
    var $ = function (id) { return document.getElementById(id); };

    var state = {entries: [], tags: [], categories: [], sort: {key: 'entryDate', dir: -1}, selected: new Set(), draft: null};
    var projects = Array.prototype.map.call($('project-options').options, function (o) {
        return {id: o.value ? Number(o.value) : null, code: o.value ? o.textContent : '-'};
    });

    var COLUMNS = ['entryDate', 'description', 'direction', 'amount', 'projectId', 'tags', 'category', 'counterparty',
        'documentRef', 'paymentMethod'];
    var LOCKED_FOR_INVOICE = {entryDate: 1, direction: 1, amount: 1};

    // ---------- pomocne ----------
    function meta(name) {
        var m = document.querySelector('meta[name="' + name + '"]');
        return m ? m.getAttribute('content') : null;
    }

    function el(tag, attrs, children) {
        var e = document.createElement(tag);
        Object.keys(attrs || {}).forEach(function (k) {
            if (k === 'text') { e.textContent = attrs[k]; } else if (k === 'class') { e.className = attrs[k]; } else { e.setAttribute(k, attrs[k]); }
        });
        (children || []).forEach(function (c) { if (c != null) { e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); } });
        return e;
    }

    function money(str) {
        var neg = String(str).charAt(0) === '-';
        var p = String(str).replace('-', '').split('.');
        var i = p[0].replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
        return (neg ? '-' : '') + i + ',' + ((p[1] || '') + '00').slice(0, 2) + ' €';
    }

    function cents(str) {
        var neg = String(str).charAt(0) === '-';
        var p = String(str).replace('-', '').split('.');
        var v = Number(p[0]) * 100 + Number(((p[1] || '') + '00').slice(0, 2));
        return neg ? -v : v;
    }

    function centsToStr(c) {
        var neg = c < 0;
        var a = Math.abs(c);
        return (neg ? '-' : '') + Math.floor(a / 100) + '.' + String(a % 100).padStart(2, '0');
    }

    function fmtDate(iso) {
        var p = iso.split('-');
        return Number(p[2]) + '. ' + Number(p[1]) + '. ' + p[0];
    }

    function today() {
        return new Intl.DateTimeFormat('sv-SE', {timeZone: 'Europe/Bratislava'}).format(new Date());
    }

    var toastTimer;
    function toast(messages, bad) {
        var t = $('toast');
        t.replaceChildren.apply(t, [].concat(messages).map(function (m) { return el('div', {text: m}); }));
        t.className = 'toast' + (bad ? ' bad' : '');
        t.hidden = false;
        clearTimeout(toastTimer);
        toastTimer = setTimeout(function () { t.hidden = true; }, bad ? 9000 : 3500);
    }

    async function api(method, url, body) {
        var headers = {'Accept': 'application/json'};
        if (body !== undefined) { headers['Content-Type'] = 'application/json'; }
        if (csrfHeader && method !== 'GET') { headers[csrfHeader] = csrfToken; }
        var res = await fetch(url, {method: method, headers: headers, body: body === undefined ? undefined : JSON.stringify(body),
            credentials: 'same-origin'});
        if (res.status === 204) { return null; }
        var data = null;
        try { data = await res.json(); } catch (e) { data = null; }
        if (!res.ok) {
            var err = new Error('HTTP ' + res.status);
            err.status = res.status;
            err.errors = data && data.errors ? data.errors
                : res.status === 403 ? ['Na zmeny potrebujete rolu editora.'] : ['Požiadavka zlyhala (' + res.status + ').'];
            throw err;
        }
        return data;
    }

    function toInput(e, override) {
        var v = {
            entryDate: e.entryDate, description: e.description, direction: e.direction, amount: String(e.amount),
            projectId: e.projectId, tags: e.tags.slice(), category: e.category, counterparty: e.counterparty,
            documentRef: e.documentRef, paymentMethod: e.paymentMethod, note: e.note, version: e.version
        };
        Object.keys(override || {}).forEach(function (k) { v[k] = override[k]; });
        return v;
    }

    async function load() {
        var data = await api('GET', '/api/polozky');
        state.entries = data.entries;
        state.tags = data.tags;
        state.categories = data.categories;
        var known = new Set();
        state.selected.forEach(function (id) { if (state.entries.some(function (x) { return x.id === id; })) { known.add(id); } });
        state.selected = known;
        refreshTagLists();
        render();
    }

    function refreshTagLists() {
        var dl = $('tag-list');
        dl.replaceChildren.apply(dl, state.tags.map(function (t) { return el('option', {value: t}); }));
        var sel = $('f-tag');
        var cur = sel.value;
        sel.replaceChildren.apply(sel, [el('option', {value: '', text: 'Všetky tagy'})].concat(state.tags.map(function (t) {
            return el('option', {value: t, text: '#' + t});
        })));
        sel.value = state.tags.indexOf(cur) >= 0 ? cur : '';
        var cats = $('category-list');
        var have = new Set(Array.prototype.map.call(cats.options, function (o) { return o.value; }));
        state.categories.forEach(function (c) { if (!have.has(c)) { cats.appendChild(el('option', {value: c})); } });
    }

    // ---------- filtre a triedenie ----------
    function filtered() {
        var q = $('f-search').value.trim().toLowerCase();
        var p = $('f-project').value;
        var tag = $('f-tag').value;
        var dir = $('f-direction').value;
        var month = $('f-month').value;
        var list = state.entries.filter(function (e) {
            if (p === 'none' && e.projectId != null) { return false; }
            if (p && p !== 'none' && String(e.projectId) !== p) { return false; }
            if (tag && e.tags.indexOf(tag) < 0) { return false; }
            if (dir && e.direction !== dir) { return false; }
            if (month && e.entryDate.slice(0, 7) !== month) { return false; }
            if (q) {
                var hay = [e.description, e.counterparty, e.documentRef, e.note, e.category, e.projectCode].concat(e.tags)
                    .join(' ').toLowerCase();
                if (hay.indexOf(q) < 0) { return false; }
            }
            return true;
        });
        var k = state.sort.key;
        var d = state.sort.dir;
        list.sort(function (a, b) {
            var x = k === 'amount' ? cents(a.amount) * (a.direction === 'VYDAVOK' ? -1 : 1) : (a[k] || '');
            var y = k === 'amount' ? cents(b.amount) * (b.direction === 'VYDAVOK' ? -1 : 1) : (b[k] || '');
            if (x < y) { return -d; }
            if (x > y) { return d; }
            return (b.id - a.id);
        });
        return list;
    }

    // ---------- vykreslenie ----------
    function render() {
        var list = filtered();
        var body = $('rows');
        var rows = [];
        if (state.draft) { rows.push(draftRow()); }
        list.forEach(function (e) { rows.push(entryRow(e)); });
        body.replaceChildren.apply(body, rows);
        $('empty').hidden = list.length > 0 || !!state.draft;

        var inc = 0;
        var exp = 0;
        list.forEach(function (e) { if (e.direction === 'PRIJEM') { inc += cents(e.amount); } else { exp += cents(e.amount); } });
        $('sum-count').textContent = list.length + ' ' + (list.length === 1 ? 'položka' : list.length < 5 && list.length > 1 ? 'položky' : 'položiek');
        $('sum-line').replaceChildren(
            el('span', {class: 'pos', text: 'Príjmy ' + money(centsToStr(inc))}), ' ',
            el('span', {class: 'neg', text: 'Výdavky ' + money(centsToStr(exp))}), ' ',
            el('strong', {text: 'Saldo ' + money(centsToStr(inc - exp))}));

        document.querySelectorAll('#sheet th[data-sort]').forEach(function (th) {
            th.classList.toggle('sorted', th.dataset.sort === state.sort.key);
            th.setAttribute('aria-sort', th.dataset.sort === state.sort.key ? (state.sort.dir > 0 ? 'ascending' : 'descending') : 'none');
        });
        var sel = state.selected.size;
        $('bulkbar').hidden = !canEdit || sel === 0;
        $('bulk-count').textContent = 'Vybrané: ' + sel;
        $('select-all').checked = list.length > 0 && list.every(function (e) { return state.selected.has(e.id); });
        $('select-all').disabled = !canEdit;
    }

    function projectCode(id) {
        var p = projects.find(function (x) { return x.id === id; });
        return p ? p.code : '';
    }

    function display(e, col) {
        switch (col) {
            case 'entryDate': return el('span', {text: fmtDate(e.entryDate)});
            case 'direction': return el('span', {class: 'pill ' + (e.direction === 'PRIJEM' ? 'in' : 'out'), text: e.direction === 'PRIJEM' ? 'Príjem' : 'Výdavok'});
            case 'amount': return el('span', {class: e.direction === 'PRIJEM' ? 'pos' : 'neg', text: (e.direction === 'VYDAVOK' ? '-' : '') + money(e.amount)});
            case 'projectId': return e.projectId ? el('span', {class: 'chip project', text: projectCode(e.projectId) || e.projectCode}) : el('span', {class: 'muted', text: '-'});
            case 'tags': return el('span', {class: 'chips'}, e.tags.map(function (t) { return el('span', {class: 'chip', text: '#' + t}); }));
            case 'paymentMethod': return el('span', {text: e.paymentMethod === 'POKLADNA' ? 'Pokladňa' : 'Banka'});
            case 'description':
                return el('span', {}, [e.description, e.invoiceNumber ? el('span', {class: 'badge', text: (e.receivedInvoiceId ? 'došlá faktúra ' : 'faktúra ') + e.invoiceNumber}) : null]);
            default: return el('span', {text: e[col] || ''});
        }
    }

    function entryRow(e) {
        var tr = el('tr', {'data-id': String(e.id)});
        var box = el('input', {type: 'checkbox', 'aria-label': 'Vybrať položku'});
        box.checked = state.selected.has(e.id);
        box.disabled = !canEdit;
        box.addEventListener('change', function () {
            if (box.checked) { state.selected.add(e.id); } else { state.selected.delete(e.id); }
            render();
        });
        tr.appendChild(el('td', {class: 'sel'}, [box]));
        COLUMNS.forEach(function (col) {
            var td = el('td', {class: 'cell c-' + col + (col === 'amount' ? ' num' : ''), 'data-col': col}, [display(e, col)]);
            var editable = canEdit && !((e.invoiceId || e.receivedInvoiceId) && LOCKED_FOR_INVOICE[col]);
            if (editable) {
                td.tabIndex = 0;
                td.classList.add('editable');
                td.addEventListener('click', function () { edit(td, e, col); });
                td.addEventListener('keydown', function (ev) { if (ev.key === 'Enter') { ev.preventDefault(); edit(td, e, col); } });
            } else if ((e.invoiceId || e.receivedInvoiceId) && LOCKED_FOR_INVOICE[col]) {
                td.title = 'Určuje úhrada faktúry ' + e.invoiceNumber;
            }
            tr.appendChild(td);
        });
        var menu = el('td', {class: 'actions'});
        var hist = el('button', {type: 'button', class: 'link', title: 'História zmien', text: 'História'});
        hist.addEventListener('click', function () { showHistory(e); });
        menu.appendChild(hist);
        if (canEdit && !e.invoiceId && !e.receivedInvoiceId) {
            var del = el('button', {type: 'button', class: 'link danger', text: 'Zmazať'});
            del.addEventListener('click', function () { confirmDelete(menu, e); });
            menu.appendChild(del);
        }
        tr.appendChild(menu);
        return tr;
    }

    function confirmDelete(cell, e) {
        var yes = el('button', {type: 'button', class: 'link danger', text: 'Áno, zmazať'});
        var no = el('button', {type: 'button', class: 'link', text: 'Nie'});
        cell.replaceChildren(el('span', {class: 'muted', text: 'Zmazať? '}), yes, no);
        no.addEventListener('click', render);
        yes.addEventListener('click', async function () {
            try {
                await api('DELETE', '/api/polozky/' + e.id + '?version=' + e.version);
                state.selected.delete(e.id);
                toast('Položka je zmazaná. Záznam ostáva v histórii.');
                await load();
            } catch (err) {
                toast(err.errors, true);
                if (err.status === 409) { await load(); } else { render(); }
            }
        });
    }

    // ---------- editory ----------
    function editorFor(col, value) {
        var input;
        switch (col) {
            case 'entryDate':
                input = el('input', {type: 'date', max: today()});
                input.value = value;
                return input;
            case 'direction':
                input = el('select', {}, [el('option', {value: 'VYDAVOK', text: 'Výdavok'}), el('option', {value: 'PRIJEM', text: 'Príjem'})]);
                input.value = value;
                return input;
            case 'paymentMethod':
                input = el('select', {}, [el('option', {value: 'BANKA', text: 'Banka'}), el('option', {value: 'POKLADNA', text: 'Pokladňa'})]);
                input.value = value;
                return input;
            case 'projectId':
                input = el('select', {}, projects.map(function (p) { return el('option', {value: p.id == null ? '' : String(p.id), text: p.code}); }));
                input.value = value == null ? '' : String(value);
                return input;
            case 'amount':
                input = el('input', {inputmode: 'decimal', class: 'num'});
                input.value = String(value).replace('.', ',');
                return input;
            case 'category':
                input = el('input', {list: 'category-list'});
                input.value = value || '';
                return input;
            default:
                input = el('input', {maxlength: col === 'description' ? '500' : '200'});
                input.value = value || '';
                return input;
        }
    }

    function readEditor(col, input) {
        if (col === 'projectId') { return input.value ? Number(input.value) : null; }
        return input.value;
    }

    function edit(td, e, col) {
        if (td.classList.contains('editing')) { return; }
        if (col === 'tags') { return editTags(td, e); }
        td.classList.add('editing');
        var input = editorFor(col, e[col]);
        td.replaceChildren(input);
        input.focus();
        if (input.select && input.type !== 'date') { input.select(); }
        var done = false;
        async function commit(move) {
            if (done) { return; }
            done = true;
            var value = readEditor(col, input);
            var changed = col === 'amount' ? value.replace(/\s/g, '').replace(',', '.') !== String(e.amount) : value !== (e[col] == null ? '' : e[col]) && !(value === '' && e[col] == null);
            if (col === 'projectId') { changed = value !== e.projectId; }
            if (!changed) { render(); focusNext(e.id, col, move); return; }
            var o = {};
            o[col] = value;
            await save(e, o, move, col);
        }
        input.addEventListener('keydown', function (ev) {
            if (ev.key === 'Enter') { ev.preventDefault(); commit(0); }
            if (ev.key === 'Escape') { done = true; render(); }
            if (ev.key === 'Tab') { ev.preventDefault(); commit(ev.shiftKey ? -1 : 1); }
        });
        input.addEventListener('blur', function () { commit(0); });
        if (input.tagName === 'SELECT') { input.addEventListener('change', function () { commit(0); }); }
    }

    function editTags(td, e) {
        td.classList.add('editing');
        var tags = e.tags.slice();
        var box = el('div', {class: 'tag-editor'});
        var input = el('input', {list: 'tag-list', placeholder: 'tag + Enter', 'aria-label': 'Pridať tag'});
        function draw() {
            box.replaceChildren.apply(box, tags.map(function (t, i) {
                var x = el('button', {type: 'button', class: 'chip-x', 'aria-label': 'Odobrať ' + t, text: '×'});
                x.addEventListener('mousedown', function (ev) { ev.preventDefault(); tags.splice(i, 1); draw(); input.focus(); });
                return el('span', {class: 'chip'}, ['#' + t, x]);
            }).concat([input]));
        }
        draw();
        td.replaceChildren(box);
        input.focus();
        var done = false;
        function add() {
            input.value.split(',').forEach(function (raw) {
                var t = raw.trim().replace(/^#/, '');
                if (t && !tags.some(function (x) { return x.toLowerCase() === t.toLowerCase(); })) { tags.push(t); }
            });
            input.value = '';
            draw();
            input.focus();
        }
        async function commit(move) {
            if (done) { return; }
            if (input.value.trim()) { add(); }
            done = true;
            if (tags.join('\u0000') === e.tags.join('\u0000')) { render(); focusNext(e.id, 'tags', move); return; }
            await save(e, {tags: tags}, move, 'tags');
        }
        input.addEventListener('keydown', function (ev) {
            if (ev.key === 'Enter' || ev.key === ',') {
                ev.preventDefault();
                if (input.value.trim()) { add(); } else if (ev.key === 'Enter') { commit(0); }
            } else if (ev.key === 'Backspace' && !input.value && tags.length) {
                tags.pop();
                draw();
                input.focus();
            } else if (ev.key === 'Escape') {
                done = true;
                render();
            } else if (ev.key === 'Tab') {
                ev.preventDefault();
                commit(ev.shiftKey ? -1 : 1);
            }
        });
        input.addEventListener('blur', function () { setTimeout(function () { if (!box.contains(document.activeElement)) { commit(0); } }, 0); });
    }

    async function save(e, override, move, col) {
        try {
            var updated = await api('PUT', '/api/polozky/' + e.id, toInput(e, override));
            var i = state.entries.findIndex(function (x) { return x.id === e.id; });
            state.entries[i] = updated;
            updated.tags.forEach(function (t) { if (state.tags.indexOf(t) < 0) { state.tags.push(t); state.tags.sort(); } });
            refreshTagLists();
            render();
            focusNext(e.id, col, move);
        } catch (err) {
            toast(err.errors, true);
            if (err.status === 409) { await load(); } else { render(); }
        }
    }

    function focusNext(id, col, move) {
        if (!move) { return; }
        var row = document.querySelector('#rows tr[data-id="' + id + '"]');
        if (!row) { return; }
        var cells = Array.prototype.slice.call(row.querySelectorAll('td.editable'));
        var idx = cells.findIndex(function (c) { return c.dataset.col === col; });
        var next = cells[idx + move];
        if (next) { next.click(); }
    }

    // ---------- nova polozka ----------
    function draftRow() {
        var d = state.draft;
        var tr = el('tr', {class: 'draft'});
        var f = {};
        f.entryDate = el('input', {type: 'date', max: today(), 'aria-label': 'Dátum'});
        f.entryDate.value = d.entryDate;
        f.description = el('input', {placeholder: 'Popis, napr. Hliníkové profily', 'aria-label': 'Popis', maxlength: '500'});
        f.description.value = d.description;
        f.direction = editorFor('direction', d.direction);
        f.amount = el('input', {inputmode: 'decimal', class: 'num', placeholder: '0,00', 'aria-label': 'Suma'});
        f.amount.value = d.amount;
        f.projectId = editorFor('projectId', d.projectId);
        f.tags = el('input', {list: 'tag-list', placeholder: 'tagy, čiarkou', 'aria-label': 'Tagy'});
        f.tags.value = d.tags;
        f.category = editorFor('category', d.category);
        f.counterparty = el('input', {placeholder: 'Protistrana', 'aria-label': 'Protistrana'});
        f.counterparty.value = d.counterparty;
        f.documentRef = el('input', {placeholder: 'č. dokladu', 'aria-label': 'Doklad'});
        f.documentRef.value = d.documentRef;
        f.paymentMethod = editorFor('paymentMethod', d.paymentMethod);
        tr.appendChild(el('td', {class: 'sel'}));
        COLUMNS.forEach(function (c) { tr.appendChild(el('td', {}, [f[c]])); });
        var ok = el('button', {type: 'button', class: 'primary small', text: 'Uložiť'});
        var cancel = el('button', {type: 'button', class: 'link', text: 'Zrušiť'});
        tr.appendChild(el('td', {class: 'actions'}, [ok, cancel]));
        Object.keys(f).forEach(function (k) {
            f[k].addEventListener('input', function () { d[k] = f[k].value; });
            f[k].addEventListener('change', function () { d[k] = f[k].value; });
            f[k].addEventListener('keydown', function (ev) {
                if (ev.key === 'Enter') { ev.preventDefault(); submit(); }
                if (ev.key === 'Escape') { state.draft = null; render(); }
            });
        });
        cancel.addEventListener('click', function () { state.draft = null; render(); });
        ok.addEventListener('click', submit);
        async function submit() {
            try {
                var created = await api('POST', '/api/polozky', {
                    entryDate: d.entryDate, description: d.description, direction: d.direction, amount: d.amount,
                    projectId: d.projectId ? Number(d.projectId) : null,
                    tags: String(d.tags || '').split(',').map(function (t) { return t.trim().replace(/^#/, ''); }).filter(Boolean),
                    category: d.category, counterparty: d.counterparty, documentRef: d.documentRef, paymentMethod: d.paymentMethod
                });
                state.entries.unshift(created);
                created.tags.forEach(function (t) { if (state.tags.indexOf(t) < 0) { state.tags.push(t); state.tags.sort(); } });
                refreshTagLists();
                // dalsia polozka s rovnakym datumom a projektom - rychle zadavanie z blocikov
                state.draft = blankDraft({entryDate: d.entryDate, projectId: d.projectId, direction: d.direction});
                toast('Položka je uložená.');
                render();
                var first = document.querySelector('#rows tr.draft input[aria-label="Popis"]');
                if (first) { first.focus(); }
            } catch (err) {
                toast(err.errors, true);
            }
        }
        setTimeout(function () { if (!document.activeElement || document.activeElement === document.body) { f.description.focus(); } }, 0);
        return tr;
    }

    function blankDraft(seed) {
        var p = $('f-project').value;
        return Object.assign({entryDate: today(), description: '', direction: 'VYDAVOK', amount: '',
            projectId: p && p !== 'none' ? Number(p) : null, tags: $('f-tag').value || '', category: '', counterparty: '',
            documentRef: '', paymentMethod: 'BANKA'}, seed || {});
    }

    // ---------- historia ----------
    var LABELS = {entry_date: 'Dátum', description: 'Popis', direction: 'Typ', amount: 'Suma', project_id: 'Projekt',
        tags: 'Tagy', category: 'Kategória', counterparty: 'Protistrana', document_ref: 'Doklad', payment_method: 'Úhrada', note: 'Poznámka'};

    async function showHistory(e) {
        var items = await api('GET', '/api/polozky/' + e.id + '/historia');
        var list = $('history-list');
        list.replaceChildren.apply(list, items.map(function (h) {
            var oldR = h.oldRow ? JSON.parse(h.oldRow) : {};
            var newR = h.newRow ? JSON.parse(h.newRow) : {};
            var changes = [];
            Object.keys(LABELS).forEach(function (k) {
                var a = JSON.stringify(oldR[k] == null ? null : oldR[k]);
                var b = JSON.stringify(newR[k] == null ? null : newR[k]);
                if (h.op === 'UPDATE' && a !== b) {
                    changes.push(el('li', {text: LABELS[k] + ': ' + show(k, oldR[k]) + ' → ' + show(k, newR[k])}));
                }
            });
            var what = h.op === 'INSERT' ? 'vytvoril(a)' : h.op === 'DELETE' ? 'zmazal(a)' : 'zmenil(a)';
            return el('li', {}, [el('strong', {text: new Date(h.at).toLocaleString('sk-SK') + ' '}), h.actor + ' ' + what,
                changes.length ? el('ul', {}, changes) : null]);
        }));
        $('history').hidden = false;
        $('history').scrollIntoView({behavior: 'smooth', block: 'nearest'});
    }

    function show(k, v) {
        if (v == null || v === '') { return '-'; }
        if (k === 'project_id') { return projectCode(v) || String(v); }
        if (k === 'tags') { return v.length ? v.map(function (t) { return '#' + t; }).join(' ') : '-'; }
        if (k === 'amount') { return money(String(v)); }
        if (k === 'direction') { return v === 'PRIJEM' ? 'Príjem' : 'Výdavok'; }
        return String(v);
    }

    // ---------- import ----------
    var importRows = null;

    function parseImport(text) {
        var lines = text.split(/\r?\n/).filter(function (l) { return l.trim(); });
        if (!lines.length) { return {rows: [], errors: ['Vložte aspoň jeden riadok.']}; }
        var delim = lines[0].indexOf('\t') >= 0 ? '\t' : lines[0].indexOf(';') >= 0 ? ';' : ',';
        var rows = [];
        var errors = [];
        lines.forEach(function (line, i) {
            var c = splitLine(line, delim);
            if (i === 0 && !/\d/.test(c[0] || '')) { return; } // hlavicka
            var amountRaw = String(c[2] || '').replace(/[\s €]/g, '').replace('−', '-');
            var negative = amountRaw.charAt(0) === '-';
            var code = (c[4] || '').trim().toUpperCase();
            var project = code ? projects.find(function (p) { return p.code === code; }) : null;
            if (code && !project) { errors.push('Riadok ' + (i + 1) + ': projekt „' + code + '“ neexistuje.'); }
            rows.push({
                entryDate: (c[0] || '').trim(), description: (c[1] || '').trim(),
                direction: negative ? 'VYDAVOK' : 'PRIJEM', amount: amountRaw.replace('-', ''),
                counterparty: (c[3] || '').trim() || null, projectId: project ? project.id : null,
                tags: String(c[5] || '').split(',').map(function (t) { return t.trim().replace(/^#/, ''); }).filter(Boolean),
                paymentMethod: 'BANKA'
            });
        });
        return {rows: rows, errors: errors};
    }

    function splitLine(line, delim) {
        var out = [];
        var cur = '';
        var q = false;
        for (var i = 0; i < line.length; i++) {
            var ch = line.charAt(i);
            if (ch === '"') {
                if (q && line.charAt(i + 1) === '"') { cur += '"'; i++; } else { q = !q; }
            } else if (ch === delim && !q) {
                out.push(cur);
                cur = '';
            } else {
                cur += ch;
            }
        }
        out.push(cur);
        return out;
    }

    function previewImport() {
        var parsed = parseImport($('import-text').value);
        var box = $('import-result');
        if (parsed.errors.length || !parsed.rows.length) {
            importRows = null;
            $('import-confirm').hidden = true;
            box.replaceChildren(el('ul', {class: 'errors'}, (parsed.errors.length ? parsed.errors : ['Nenašiel som žiadne riadky s dátumom.'])
                .map(function (m) { return el('li', {text: m}); })));
            return;
        }
        importRows = parsed.rows;
        var inc = 0;
        var exp = 0;
        parsed.rows.forEach(function (r) {
            var c = Math.round(parseFloat(r.amount.replace(',', '.')) * 100) || 0;
            if (r.direction === 'PRIJEM') { inc += c; } else { exp += c; }
        });
        box.replaceChildren(el('p', {text: parsed.rows.length + ' riadkov: príjmy ' + money(centsToStr(inc)) + ', výdavky '
            + money(centsToStr(exp)) + '. Server ešte skontroluje každý riadok - ak je niektorý zlý, nenaimportuje sa nič.'}));
        $('import-confirm').hidden = false;
        $('import-confirm').textContent = 'Importovať ' + parsed.rows.length + ' položiek';
    }

    async function confirmImport() {
        if (!importRows) { return; }
        try {
            var created = await api('POST', '/api/polozky/import', {rows: importRows});
            toast('Naimportovaných ' + created.length + ' položiek.');
            $('import-text').value = '';
            $('import-result').replaceChildren();
            $('import-confirm').hidden = true;
            $('import').hidden = true;
            importRows = null;
            await load();
        } catch (err) {
            $('import-result').replaceChildren(el('ul', {class: 'errors'}, err.errors.map(function (m) { return el('li', {text: m}); })));
        }
    }

    // ---------- hromadne ----------
    async function bulk(action, value) {
        try {
            await api('POST', '/api/polozky/hromadne', {ids: Array.from(state.selected), action: action, value: value});
            toast('Upravené položky: ' + state.selected.size + '.');
            await load();
        } catch (err) {
            toast(err.errors, true);
            await load();
        }
    }

    // ---------- udalosti ----------
    ['f-search', 'f-project', 'f-tag', 'f-direction', 'f-month'].forEach(function (id) {
        $(id).addEventListener('input', render);
        $(id).addEventListener('change', render);
    });
    $('f-clear').addEventListener('click', function () {
        ['f-search', 'f-project', 'f-tag', 'f-direction', 'f-month'].forEach(function (id) { $(id).value = ''; });
        render();
    });
    document.querySelectorAll('#sheet th[data-sort]').forEach(function (th) {
        th.tabIndex = 0;
        var go = function () {
            var k = th.dataset.sort;
            state.sort = {key: k, dir: state.sort.key === k ? -state.sort.dir : (k === 'entryDate' || k === 'amount' ? -1 : 1)};
            render();
        };
        th.addEventListener('click', go);
        th.addEventListener('keydown', function (ev) { if (ev.key === 'Enter') { go(); } });
    });
    $('select-all').addEventListener('change', function () {
        var list = filtered();
        if ($('select-all').checked) { list.forEach(function (e) { state.selected.add(e.id); }); } else { state.selected.clear(); }
        render();
    });
    $('history-close').addEventListener('click', function () { $('history').hidden = true; });
    if (canEdit) {
        $('new-entry').addEventListener('click', function () { state.draft = state.draft || blankDraft(); render(); });
        $('open-import').addEventListener('click', function () { $('import').hidden = false; $('import-text').focus(); });
        $('import-close').addEventListener('click', function () { $('import').hidden = true; });
        $('import-preview').addEventListener('click', previewImport);
        $('import-confirm').addEventListener('click', confirmImport);
        $('bulk-set-project').addEventListener('click', function () { bulk('project', $('bulk-project').value); });
        $('bulk-add-tag').addEventListener('click', function () {
            var t = $('bulk-tag').value.trim().replace(/^#/, '');
            if (t) { bulk('addTag', t); }
        });
        $('bulk-remove-tag').addEventListener('click', function () {
            var t = $('bulk-tag').value.trim().replace(/^#/, '');
            if (t) { bulk('removeTag', t); }
        });
        $('bulk-clear').addEventListener('click', function () { state.selected.clear(); render(); });
    }

    // odkaz z aktivity: /polozky?projekt=ID predvyplni filter
    var preset = new URLSearchParams(window.location.search).get('projekt');
    if (preset) { $('f-project').value = preset; }

    load().catch(function (err) { toast(err.errors || ['Položky sa nepodarilo načítať.'], true); });
})();
