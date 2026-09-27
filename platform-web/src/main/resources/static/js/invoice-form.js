// Dynamicke polozky faktury: pridanie/odobranie riadku, precislovanie mien poli, orientacne sucty.
// Presne sucty (DPH za skupinu sadzby) pocita server.
(function () {
    'use strict';
    var table = document.getElementById('lines');
    if (!table) {
        return;
    }
    var body = table.querySelector('tbody');
    var template = document.getElementById('line-template');

    function parse(value) {
        var n = parseFloat(String(value || '').replace(/[\s ]/g, '').replace(',', '.'));
        return isNaN(n) ? 0 : n;
    }

    function fmt(n) {
        return n.toLocaleString('sk-SK', {minimumFractionDigits: 2, maximumFractionDigits: 2}) + ' €';
    }

    function recalc() {
        var net = 0;
        var vat = 0;
        body.querySelectorAll('tr.line').forEach(function (tr) {
            var line = Math.round(parse(tr.querySelector('[data-field=quantity]').value)
                * parse(tr.querySelector('[data-field=unitPrice]').value) * 100) / 100;
            var rateField = tr.querySelector('[data-field=vat]');
            var rate = rateField && rateField.value !== 'E' ? parse(rateField.value) : 0;
            net += line;
            vat += line * rate / 100;
            tr.querySelector('.line-total').textContent = fmt(line);
        });
        vat = Math.round(vat * 100) / 100;
        document.getElementById('sum-net').textContent = fmt(net);
        var vatOut = document.getElementById('sum-vat');
        if (vatOut) {
            vatOut.textContent = fmt(vat);
        }
        document.getElementById('sum-total').textContent = fmt(net + vat);
    }

    function renumber() {
        body.querySelectorAll('tr.line').forEach(function (tr, i) {
            tr.querySelectorAll('[data-field]').forEach(function (el) {
                el.name = 'lines[' + i + '].' + el.getAttribute('data-field');
            });
        });
        recalc();
    }

    document.getElementById('add-line').addEventListener('click', function () {
        body.appendChild(template.content.cloneNode(true));
        renumber();
        var rows = body.querySelectorAll('tr.line');
        rows[rows.length - 1].querySelector('[data-field=description]').focus();
    });

    body.addEventListener('click', function (e) {
        if (e.target.classList.contains('remove-line') && body.querySelectorAll('tr.line').length > 1) {
            e.target.closest('tr').remove();
            renumber();
        }
    });
    body.addEventListener('input', recalc);
    body.addEventListener('change', recalc);
    renumber();
})();
