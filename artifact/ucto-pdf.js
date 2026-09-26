/*
 * PDF faktura v prehliadaci (jsPDF). Rovnake nalezitosti a rozlozenie ako Java InvoicePdfRenderer:
 * § 74 zakona o DPH, rekapitulacia DPH podla sadzieb, strankovanie, QR PAY by square vektorovo.
 */
(function (root) {
    'use strict';

    var W = 595.28;
    var H = 841.89;
    var M = 40;
    var CW = W - 2 * M;
    var FOOT = 36;
    var QR = 96;
    var GRAY = 115;
    var UNITS = {C62: 'ks', H87: 'ks', HUR: 'hod', DAY: 'deň', MON: 'mes', KGM: 'kg', MTR: 'm', LS: 'paušál'};

    function date(d) {
        if (!d) {
            return '-';
        }
        var p = d.split('-');
        return Number(p[2]) + '. ' + Number(p[1]) + '. ' + p[0];
    }

    function iban(v) {
        return String(v).replace(/(.{4})(?!$)/g, '$1 ');
    }

    /**
     * @param deps {jsPDF, qrcode, core, lzma, fonts: {regular, bold}} - fonty ako base64 TTF
     * @param inv faktura v tvare UctoCore
     * @param extra {registrationNote, projectName, issuedBy}
     * @returns ArrayBuffer
     */
    function render(deps, inv, extra) {
        var C = deps.core;
        var doc = new deps.jsPDF({unit: 'pt', format: 'a4', compress: true});
        doc.addFileToVFS('NotoSans-Regular.ttf', deps.fonts.regular);
        doc.addFont('NotoSans-Regular.ttf', 'Noto', 'normal');
        doc.addFileToVFS('NotoSans-Bold.ttf', deps.fonts.bold);
        doc.addFont('NotoSans-Bold.ttf', 'Noto', 'bold');
        doc.setProperties({title: 'Faktúra ' + inv.number});

        var payer = C.isVatRegistered(inv.seller);
        var t = C.totals(inv);
        var y = M;
        extra = extra || {};

        function font(style, size, gray) {
            doc.setFont('Noto', style);
            doc.setFontSize(size);
            doc.setTextColor(gray || 0);
        }

        function text(x, yy, s, style, size, gray) {
            font(style, size, gray);
            doc.text(String(s == null ? '' : s).replace(/[\t\r\n]+/g, ' '), x, yy);
        }

        function right(x, yy, s, style, size, gray) {
            font(style, size, gray);
            doc.text(String(s), x, yy, {align: 'right'});
        }

        function width(s, style, size) {
            font(style, size);
            return doc.getTextWidth(String(s));
        }

        function hline(yy, lw, from, to) {
            doc.setLineWidth(lw);
            doc.setDrawColor(153);
            doc.line(from == null ? M : from, yy, to == null ? W - M : to, yy);
        }

        function wrap(s, style, size, max) {
            font(style, size);
            return doc.splitTextToSize(String(s || ''), max);
        }

        function ensure(h, onNew) {
            if (y + h > H - M - FOOT) {
                doc.addPage();
                y = M;
                if (onNew) {
                    onNew();
                }
            }
        }

        // Hlavicka
        text(M, y + 20, 'Faktúra', 'bold', 22);
        right(W - M, y + 18, 'č. ' + inv.number, 'bold', 14);
        y += 30;
        hline(y, 1);
        y += 16;

        // Dodavatel / odberatel
        function party(label, p, x, top, isSeller) {
            var yy = top;
            text(x, yy, label, 'bold', 8, GRAY);
            yy += 15;
            wrap(p.name, 'bold', 11, CW / 2 - 10).forEach(function (l) {
                text(x, yy, l, 'bold', 11);
                yy += 14;
            });
            var rows = [];
            if (p.street) {
                rows.push(p.street);
            }
            if (p.postalCode || p.city) {
                rows.push(((p.postalCode || '') + ' ' + (p.city || '')).trim());
            }
            if (p.country && p.country !== 'SK') {
                rows.push(p.country);
            }
            if (p.ico) {
                rows.push('IČO: ' + p.ico);
            }
            if (p.dic) {
                rows.push('DIČ: ' + p.dic);
            }
            if (p.icDph) {
                rows.push('IČ DPH: ' + p.icDph);
            } else if (isSeller) {
                rows.push('Nie je platiteľ DPH');
            }
            if (isSeller && p.email) {
                rows.push(p.email);
            }
            if (isSeller && p.phone) {
                rows.push(p.phone);
            }
            rows.forEach(function (r) {
                text(x, yy, r, 'normal', 9.5);
                yy += 12.5;
            });
            return yy;
        }

        var top = y;
        var left = party('DODÁVATEĽ', inv.seller, M, top, true);
        if (extra.registrationNote) {
            wrap(extra.registrationNote, 'normal', 7.5, CW / 2 - 10).forEach(function (l) {
                text(M, left + 2, l, 'normal', 7.5, GRAY);
                left += 10;
            });
        }
        var rightY = party('ODBERATEĽ', inv.buyer, M + CW / 2 + 10, top, false);
        y = Math.max(left, rightY) + 4;

        // Udaje
        hline(y, 0.4);
        y += 14;
        var cells = [
            ['Dátum vyhotovenia', date(inv.issueDate)], ['Dátum dodania', date(inv.deliveryDate)],
            ['Dátum splatnosti', date(inv.dueDate)], ['Variabilný symbol', inv.variableSymbol || '-'],
            ['Forma úhrady', inv.payeeIban ? 'Prevodom na účet' : '-'],
            ['Projekt', inv.projectCode ? inv.projectCode + (extra.projectName ? ' ' + extra.projectName : '') : '-']
        ];
        cells.forEach(function (c, i) {
            var x = M + (i % 3) * CW / 3;
            var yy = y + Math.floor(i / 3) * 26;
            text(x, yy, c[0], 'normal', 7.5, GRAY);
            var v = c[1];
            while (v.length > 1 && width(v, 'bold', 9.5) > CW / 3 - 8) {
                v = v.slice(0, -2) + '…';
            }
            text(x, yy + 11, v, 'bold', 9.5);
        });
        y += 58;
        if (inv.payeeIban) {
            text(M, y, 'IBAN: ' + iban(inv.payeeIban) + (inv.payeeBic ? '     BIC: ' + inv.payeeBic : ''), 'normal', 9.5);
            y += 13;
        }
        if (inv.buyerReference) {
            text(M, y, 'Referencia odberateľa: ' + inv.buyerReference, 'normal', 9.5);
            y += 13;
        }
        y += 6;

        // Polozky
        var head = payer ? ['Popis', 'Množstvo', 'MJ', 'Cena/MJ', 'DPH', 'Spolu bez DPH'] : ['Popis', 'Množstvo', 'MJ', 'Cena/MJ', 'Spolu'];
        var widths = payer ? [0, 52, 34, 70, 40, 82] : [0, 55, 35, 80, 85];
        widths[0] = CW - widths.reduce(function (a, b) {
            return a + b;
        }, 0);

        function tableHead() {
            doc.setFillColor(237);
            doc.rect(M, y, CW, 18, 'F');
            var x = M;
            head.forEach(function (h, i) {
                if (i === 0) {
                    text(x + 4, y + 12.5, h, 'bold', 8.5);
                } else {
                    right(x + widths[i] - 4, y + 12.5, h, 'bold', 8.5);
                }
                x += widths[i];
            });
            y += 18;
        }

        tableHead();
        inv.lines.forEach(function (l) {
            var desc = wrap(l.description, 'normal', 9, widths[0] - 8);
            var rowH = desc.length * 11 + 7;
            ensure(rowH, tableHead);
            desc.forEach(function (d, i) {
                text(M + 4, y + 11 + i * 11, d, 'normal', 9);
            });
            var qty = C.milliToPlain(BigInt(l.qtyMilli)).replace('.', ',');
            var vatLabel = l.category === 'S' ? l.rate + ' %' : (l.category === 'E' ? 'oslob.' : '-');
            var vals = payer
                ? [qty, UNITS[l.unit] || l.unit, C.formatMoney(BigInt(l.priceCents)), vatLabel, C.formatMoney(C.lineNet(l))]
                : [qty, UNITS[l.unit] || l.unit, C.formatMoney(BigInt(l.priceCents)), C.formatMoney(C.lineNet(l))];
            var x = M + widths[0];
            vals.forEach(function (v, i) {
                right(x + widths[i + 1] - 4, y + 11, v, 'normal', 9);
                x += widths[i + 1];
            });
            y += rowH;
            hline(y, 0.3);
        });
        y += 14;

        // Rekapitulacia a sucet
        var R = W - M;
        if (payer) {
            ensure(30 + t.groups.length * 13);
            var cols = [R - 250, R - 170, R - 85, R];
            ['Sadzba', 'Základ dane', 'DPH', 'Spolu'].forEach(function (h, i) {
                right(cols[i], y, h, 'bold', 8.5);
            });
            y += 13;
            t.groups.forEach(function (g) {
                right(cols[0], y, g.category === 'S' ? g.rate + ' %' : g.exReason, 'normal', 9);
                right(cols[1], y, C.formatMoney(g.taxable), 'normal', 9);
                right(cols[2], y, C.formatMoney(g.tax), 'normal', 9);
                right(cols[3], y, C.formatMoney(g.taxable + g.tax), 'normal', 9);
                y += 13;
            });
            y += 4;
        }
        ensure(30);
        hline(y - 6, 0.8, R - 250, R);
        text(R - 250, y + 10, 'Spolu na úhradu', 'bold', 12);
        right(R, y + 10, C.formatMoney(t.payable) + ' €', 'bold', 13);
        y += 22;
        if (!payer) {
            text(R - 250, y, 'Dodávateľ nie je platiteľom DPH.', 'normal', 8, GRAY);
            y += 12;
        }
        y += 10;

        // QR platba
        if (inv.payeeIban && t.payable > 0n && (inv.currency || 'EUR') === 'EUR' && deps.lzma) {
            ensure(QR + 20);
            var qr = deps.qrcode(0, 'M');
            qr.addData(C.payBySquare(inv, deps.lzma), 'Alphanumeric');
            qr.make();
            var n = qr.getModuleCount();
            var m = QR / n;
            doc.setFillColor(0);
            for (var r = 0; r < n; r++) {
                for (var c = 0; c < n; c++) {
                    if (qr.isDark(r, c)) {
                        doc.rect(M + c * m, y + r * m, m + 0.01, m + 0.01, 'F');
                    }
                }
            }
            text(M + 12, y + QR + 10, 'PAY by square', 'bold', 8, GRAY);
            var x0 = M + QR + 16;
            text(x0, y + 12, 'Zaplaťte QR kódom v bankovej aplikácii', 'bold', 10);
            text(x0, y + 28, 'Suma: ' + C.formatMoney(t.payable) + ' €', 'normal', 9);
            text(x0, y + 41, 'IBAN: ' + iban(inv.payeeIban), 'normal', 9);
            text(x0, y + 54, 'Variabilný symbol: ' + (inv.variableSymbol || '-'), 'normal', 9);
            text(x0, y + 67, 'Splatnosť: ' + date(inv.dueDate), 'normal', 9);
            y += QR + 22;
        }

        if (inv.note) {
            wrap(inv.note, 'normal', 9, CW).forEach(function (l) {
                ensure(12);
                text(M, y + 9, l, 'normal', 9);
                y += 12;
            });
        }

        // Pata na kazdej strane
        var pages = doc.getNumberOfPages();
        for (var i = 1; i <= pages; i++) {
            doc.setPage(i);
            hline(H - M - 14, 0.3);
            if (extra.issuedBy) {
                text(M, H - M, 'Vystavil: ' + extra.issuedBy, 'normal', 7.5, GRAY);
            }
            right(W - M, H - M, 'Strana ' + i + ' / ' + pages, 'normal', 7.5, GRAY);
        }
        return doc.output('arraybuffer');
    }

    var api = {render: render};
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    } else {
        root.UctoPdf = api;
    }
})(typeof self !== 'undefined' ? self : this);
