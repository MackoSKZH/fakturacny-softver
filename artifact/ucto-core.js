/*
 * Ucto core - port platform-core (Java) do prehliadaca.
 * Peniaze su vzdy cele centy (BigInt), mnozstvo v tisicinach. Ziadne floaty.
 * Rovnake pravidla ako Java verzia: § 74 zakona o DPH, EN 16931, Peppol BIS Billing 3.0.
 */
(function (root) {
    'use strict';

    var SK_RATES = [23, 19, 5];
    var PEPPOL_SCHEME_SK_DIC = '0245';
    var CATEGORY = {
        S: {code: 'S', hasRate: true, exCode: null, exReason: null},
        E: {code: 'E', hasRate: true, exCode: 'VATEX-EU-132', exReason: 'Oslobodené od dane podľa zákona o DPH'},
        O: {code: 'O', hasRate: false, exCode: 'VATEX-EU-O', exReason: 'Dodávateľ nie je platiteľom DPH'}
    };

    // ---------- cisla ----------

    /** "1 500,50" -> BigInt v jednotkach 10^-scale, alebo null ak to nie je cislo / ma viac desatinnych miest. */
    function parseScaled(raw, scale) {
        if (raw === null || raw === undefined) {
            return null;
        }
        var s = String(raw).replace(/[\s ]/g, '').replace(',', '.');
        var m = /^(-)?(\d+)(?:\.(\d+))?$/.exec(s);
        if (!m) {
            return null;
        }
        var frac = m[3] || '';
        if (frac.length > scale) {
            return null;
        }
        while (frac.length < scale) {
            frac += '0';
        }
        var v = BigInt(m[2] + frac);
        return m[1] ? -v : v;
    }

    /** Delenie so zaokruhlenim HALF_UP (aj pre zaporne cisla). */
    function divRound(num, den) {
        var neg = (num < 0n) !== (den < 0n);
        var a = num < 0n ? -num : num;
        var b = den < 0n ? -den : den;
        var q = (a * 2n + b) / (2n * b);
        return neg ? -q : q;
    }

    function centsToPlain(c) {
        var neg = c < 0n;
        var a = neg ? -c : c;
        var s = (a / 100n).toString() + '.' + String(a % 100n).padStart(2, '0');
        return neg ? '-' + s : s;
    }

    /** Slovensky format: 1 500,50 */
    function formatMoney(c) {
        var p = centsToPlain(c).split('.');
        var neg = p[0].charAt(0) === '-';
        var i = neg ? p[0].slice(1) : p[0];
        i = i.replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
        return (neg ? '-' : '') + i + ',' + p[1];
    }

    function milliToPlain(m) {
        var neg = m < 0n;
        var a = neg ? -m : m;
        var frac = String(a % 1000n).padStart(3, '0').replace(/0+$/, '');
        var s = (a / 1000n).toString() + (frac ? '.' + frac : '');
        return neg ? '-' + s : s;
    }

    // ---------- identifikatory ----------

    function strip(v) {
        return v == null ? '' : String(v).replace(/\s+/g, '');
    }

    function isIco(v) {
        return /^\d{8}$/.test(v);
    }

    function isDic(v) {
        return /^\d{10}$/.test(v);
    }

    function isIcDph(v) {
        return /^SK\d{10}$/.test(v);
    }

    function isVs(v) {
        return /^\d{1,10}$/.test(v);
    }

    function isIban(raw) {
        var iban = strip(raw).toUpperCase();
        if (!/^[A-Z]{2}\d{2}[A-Z0-9]{11,30}$/.test(iban)) {
            return false;
        }
        if (iban.indexOf('SK') === 0 && iban.length !== 24) {
            return false;
        }
        var r = iban.slice(4) + iban.slice(0, 4);
        var digits = r.replace(/[A-Z]/g, function (ch) {
            return String(ch.charCodeAt(0) - 55);
        });
        return BigInt(digits) % 97n === 1n;
    }

    function peppolId(party) {
        return party && isDic(party.dic) ? PEPPOL_SCHEME_SK_DIC + ':' + party.dic : null;
    }

    function isVatRegistered(party) {
        return !!(party && party.icDph);
    }

    // ---------- cislovanie ----------

    function formatNumber(pattern, year, seq) {
        var m = /\{(N+)\}/.exec(pattern);
        if (!m) {
            throw new Error('Vzor musí obsahovať {NNNN}.');
        }
        var s = String(seq).padStart(m[1].length, '0');
        if (s.length > m[1].length) {
            throw new Error('Číselný rad ' + pattern + ' pretiekol v roku ' + year + '.');
        }
        return pattern.replace('{YYYY}', String(year)).replace('{YY}', String(year % 100).padStart(2, '0'))
            .replace(m[0], s);
    }

    function vsOf(number) {
        var d = String(number || '').replace(/\D/g, '');
        return isVs(d) ? d : null;
    }

    // ---------- polozky a sucty ----------

    /**
     * Normalizuje polozku z formulara: {description, quantity, unit, unitPrice, vat}
     * -> {description, qtyMilli, unit, priceCents, category, rate} alebo chyba.
     */
    function parseLine(l, vatPayer, no, errors) {
        var p = 'Položka ' + no + ': ';
        var qty = parseScaled(l.quantity, 3);
        var price = parseScaled(l.unitPrice, 2);
        if (qty === null) {
            errors.push(p + 'neplatné množstvo (najviac 3 desatinné miesta).');
        }
        if (price === null) {
            errors.push(p + 'neplatná cena (najviac 2 desatinné miesta).');
        }
        if (qty === null || price === null) {
            return null;
        }
        var category = 'O';
        var rate = null;
        if (vatPayer) {
            category = l.vat === 'E' ? 'E' : 'S';
            rate = l.vat === 'E' ? 0 : Number(l.vat);
        }
        return {
            description: String(l.description || '').trim(),
            qtyMilli: qty.toString(),
            unit: l.unit || 'C62',
            priceCents: price.toString(),
            category: category,
            rate: rate
        };
    }

    function lineNet(line) {
        return divRound(BigInt(line.qtyMilli) * BigInt(line.priceCents), 1000n);
    }

    /** DPH za skupinu (kategoria + sadzba), nie po polozkach - EN 16931 BR-CO-17. */
    function totals(inv) {
        var groups = [];
        var byKey = {};
        var net = 0n;
        inv.lines.forEach(function (l) {
            var n = lineNet(l);
            net += n;
            var key = l.category + '|' + (l.rate == null ? '' : l.rate);
            if (!byKey[key]) {
                byKey[key] = {category: l.category, rate: l.rate, taxable: 0n};
                groups.push(byKey[key]);
            }
            byKey[key].taxable += n;
        });
        var vat = 0n;
        groups.forEach(function (g) {
            g.tax = g.category === 'S' ? divRound(g.taxable * BigInt(g.rate), 100n) : 0n;
            vat += g.tax;
            g.exCode = CATEGORY[g.category].exCode;
            g.exReason = CATEGORY[g.category].exReason;
        });
        return {net: net, vat: vat, total: net + vat, payable: net + vat, groups: groups};
    }

    // ---------- kontroly ----------

    function validateParty(label, p, errors) {
        if (!p || !String(p.name || '').trim()) {
            errors.push(label + ': chýba názov.');
            return;
        }
        if (p.ico && !isIco(p.ico)) {
            errors.push(label + ': IČO musí mať 8 číslic.');
        }
        if (p.dic && p.country === 'SK' && !isDic(p.dic)) {
            errors.push(label + ': DIČ musí mať 10 číslic.');
        }
        if (p.icDph && p.country === 'SK' && !isIcDph(p.icDph)) {
            errors.push(label + ': IČ DPH musí byť v tvare SK + 10 číslic.');
        }
    }

    function validate(inv) {
        var errors = [];
        if (!inv.number) {
            errors.push('Chýba poradové číslo faktúry.');
        }
        if (!inv.issueDate) {
            errors.push('Chýba dátum vyhotovenia.');
        }
        if (!inv.deliveryDate) {
            errors.push('Chýba dátum dodania.');
        }
        if (inv.dueDate && inv.issueDate && inv.dueDate < inv.issueDate) {
            errors.push('Dátum splatnosti je pred dátumom vyhotovenia.');
        }
        validateParty('Dodávateľ', inv.seller, errors);
        validateParty('Odberateľ', inv.buyer, errors);
        if (!inv.lines.length) {
            errors.push('Faktúra nemá žiadne položky.');
        }
        var payer = isVatRegistered(inv.seller);
        inv.lines.forEach(function (l, i) {
            var p = 'Položka ' + (i + 1) + ': ';
            if (!l.description) {
                errors.push(p + 'chýba popis.');
            }
            if (BigInt(l.qtyMilli) === 0n) {
                errors.push(p + 'množstvo nemôže byť 0.');
            }
            if (BigInt(l.priceCents) < 0n) {
                errors.push(p + 'jednotková cena nemôže byť záporná (zľavu zadajte záporným množstvom).');
            }
            if (!payer && l.category !== 'O') {
                errors.push(p + 'dodávateľ nie je platiteľ DPH, položka nesmie mať DPH.');
            }
            if (payer && l.category === 'O') {
                errors.push(p + 'platiteľ DPH musí uviesť sadzbu DPH.');
            }
            if (l.category === 'S' && SK_RATES.indexOf(l.rate) < 0) {
                errors.push(p + 'sadzba DPH musí byť 23, 19 alebo 5 %.');
            }
        });
        if (inv.variableSymbol && !isVs(inv.variableSymbol)) {
            errors.push('Variabilný symbol môže mať najviac 10 číslic.');
        }
        if (inv.payeeIban && !isIban(inv.payeeIban)) {
            errors.push('IBAN nie je platný.');
        }
        if (inv.lines.length && totals(inv).payable > 0n && !inv.dueDate) {
            errors.push('Chýba dátum splatnosti.');
        }
        return errors;
    }

    function validateForPeppol(inv) {
        var errors = validate(inv);
        if (!peppolId(inv.seller)) {
            errors.push('Dodávateľ nemá DIČ, preto nemá Peppol ID (0245:DIČ).');
        }
        if (!peppolId(inv.buyer)) {
            errors.push('Odberateľ nemá DIČ - e-faktúru mu nie je kam doručiť, použite PDF.');
        }
        if (!inv.seller.city || !inv.buyer.city) {
            errors.push('Peppol vyžaduje adresu dodávateľa aj odberateľa.');
        }
        return errors;
    }

    // ---------- UBL 2.1 / Peppol BIS Billing 3.0 ----------

    function esc(s) {
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '');
    }

    function ublXml(inv) {
        var t = totals(inv);
        var cur = inv.currency || 'EUR';
        var onlyO = inv.lines.every(function (l) {
            return l.category === 'O';
        });
        var out = [];
        var depth = 0;

        function open(name, attrs) {
            out.push('  '.repeat(depth) + '<' + name + (attrs || '') + '>');
            depth++;
        }

        function close(name) {
            depth--;
            out.push('  '.repeat(depth) + '</' + name + '>');
        }

        function el(name, value, attrs) {
            out.push('  '.repeat(depth) + '<' + name + (attrs || '') + '>' + esc(value) + '</' + name + '>');
        }

        function amount(name, cents) {
            el(name, centsToPlain(cents), ' currencyID="' + cur + '"');
        }

        function taxScheme(id) {
            open('cac:TaxScheme');
            el('cbc:ID', id);
            close('cac:TaxScheme');
        }

        function party(wrapper, p, isSeller) {
            open(wrapper);
            open('cac:Party');
            if (peppolId(p)) {
                el('cbc:EndpointID', p.dic, ' schemeID="' + PEPPOL_SCHEME_SK_DIC + '"');
            }
            open('cac:PostalAddress');
            if (p.street) {
                el('cbc:StreetName', p.street);
            }
            if (p.city) {
                el('cbc:CityName', p.city);
            }
            if (p.postalCode) {
                el('cbc:PostalZone', p.postalCode);
            }
            open('cac:Country');
            el('cbc:IdentificationCode', p.country || 'SK');
            close('cac:Country');
            close('cac:PostalAddress');
            // BR-O-02: pri neplatitelovi ziadne IČ DPH dodavatela ani odberatela
            if (isVatRegistered(p) && !onlyO) {
                open('cac:PartyTaxScheme');
                el('cbc:CompanyID', p.icDph);
                taxScheme('VAT');
                close('cac:PartyTaxScheme');
            }
            if (isSeller && p.dic) {
                open('cac:PartyTaxScheme');
                el('cbc:CompanyID', p.dic);
                taxScheme('TAX');
                close('cac:PartyTaxScheme');
            }
            open('cac:PartyLegalEntity');
            el('cbc:RegistrationName', p.name);
            if (p.ico) {
                el('cbc:CompanyID', p.ico);
            }
            close('cac:PartyLegalEntity');
            if (p.email || p.phone) {
                open('cac:Contact');
                if (p.phone) {
                    el('cbc:Telephone', p.phone);
                }
                if (p.email) {
                    el('cbc:ElectronicMail', p.email);
                }
                close('cac:Contact');
            }
            close('cac:Party');
            close(wrapper);
        }

        out.push('<?xml version="1.0" encoding="UTF-8"?>');
        open('Invoice', ' xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"'
            + ' xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"'
            + ' xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2"');
        el('cbc:CustomizationID', 'urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0');
        el('cbc:ProfileID', 'urn:fdc:peppol.eu:2017:poacc:billing:01:1.0');
        el('cbc:ID', inv.number);
        el('cbc:IssueDate', inv.issueDate);
        if (inv.dueDate) {
            el('cbc:DueDate', inv.dueDate);
        }
        el('cbc:InvoiceTypeCode', '380');
        if (inv.note) {
            el('cbc:Note', inv.note);
        }
        el('cbc:DocumentCurrencyCode', cur);
        el('cbc:BuyerReference', inv.buyerReference || inv.number);
        if (inv.projectCode) {
            open('cac:ProjectReference');
            el('cbc:ID', inv.projectCode);
            close('cac:ProjectReference');
        }
        party('cac:AccountingSupplierParty', inv.seller, true);
        party('cac:AccountingCustomerParty', inv.buyer, false);
        if (inv.deliveryDate) {
            open('cac:Delivery');
            el('cbc:ActualDeliveryDate', inv.deliveryDate);
            close('cac:Delivery');
        }
        if (inv.payeeIban) {
            open('cac:PaymentMeans');
            el('cbc:PaymentMeansCode', '58');
            if (inv.variableSymbol) {
                el('cbc:PaymentID', inv.variableSymbol);
            }
            open('cac:PayeeFinancialAccount');
            el('cbc:ID', strip(inv.payeeIban).toUpperCase());
            if (inv.payeeBic) {
                open('cac:FinancialInstitutionBranch');
                el('cbc:ID', inv.payeeBic);
                close('cac:FinancialInstitutionBranch');
            }
            close('cac:PayeeFinancialAccount');
            close('cac:PaymentMeans');
        }
        open('cac:TaxTotal');
        amount('cbc:TaxAmount', t.vat);
        t.groups.forEach(function (g) {
            open('cac:TaxSubtotal');
            amount('cbc:TaxableAmount', g.taxable);
            amount('cbc:TaxAmount', g.tax);
            open('cac:TaxCategory');
            el('cbc:ID', g.category);
            if (CATEGORY[g.category].hasRate) {
                el('cbc:Percent', String(g.rate));
            }
            if (g.exCode) {
                el('cbc:TaxExemptionReasonCode', g.exCode);
                el('cbc:TaxExemptionReason', g.exReason);
            }
            taxScheme('VAT');
            close('cac:TaxCategory');
            close('cac:TaxSubtotal');
        });
        close('cac:TaxTotal');
        open('cac:LegalMonetaryTotal');
        amount('cbc:LineExtensionAmount', t.net);
        amount('cbc:TaxExclusiveAmount', t.net);
        amount('cbc:TaxInclusiveAmount', t.total);
        amount('cbc:PayableAmount', t.payable);
        close('cac:LegalMonetaryTotal');
        inv.lines.forEach(function (l, i) {
            open('cac:InvoiceLine');
            el('cbc:ID', String(i + 1));
            el('cbc:InvoicedQuantity', milliToPlain(BigInt(l.qtyMilli)), ' unitCode="' + esc(l.unit) + '"');
            amount('cbc:LineExtensionAmount', lineNet(l));
            open('cac:Item');
            el('cbc:Name', l.description);
            open('cac:ClassifiedTaxCategory');
            el('cbc:ID', l.category);
            if (CATEGORY[l.category].hasRate) {
                el('cbc:Percent', String(l.rate));
            }
            taxScheme('VAT');
            close('cac:ClassifiedTaxCategory');
            close('cac:Item');
            open('cac:Price');
            amount('cbc:PriceAmount', BigInt(l.priceCents));
            close('cac:Price');
            close('cac:InvoiceLine');
        });
        close('Invoice');
        return out.join('\n') + '\n';
    }

    // ---------- PAY by square ----------

    function crc32(bytes) {
        var c;
        var crc = 0xFFFFFFFF;
        for (var i = 0; i < bytes.length; i++) {
            c = (crc ^ bytes[i]) & 0xFF;
            for (var k = 0; k < 8; k++) {
                c = c & 1 ? (c >>> 1) ^ 0xEDB88320 : c >>> 1;
            }
            crc = (crc >>> 8) ^ c;
        }
        return (crc ^ 0xFFFFFFFF) >>> 0;
    }

    function clean(s) {
        return s == null ? '' : String(s).replace(/[\t\r\n]+/g, ' ').trim();
    }

    function payBySquarePayload(inv) {
        var s = inv.seller;
        return [
            '', '1', '1',
            centsToPlain(totals(inv).payable),
            clean(inv.currency || 'EUR'),
            inv.dueDate ? inv.dueDate.replace(/-/g, '') : '',
            clean(inv.variableSymbol), '', '', '',
            clean('Faktura ' + inv.number),
            '1',
            strip(inv.payeeIban).toUpperCase(),
            clean(inv.payeeBic),
            '0', '0',
            clean(s.name), clean(s.street), (clean(s.postalCode) + ' ' + clean(s.city)).trim()
        ].join('\t');
    }

    /** Vyzaduje globalny LZMA (LZMA-JS). Raw LZMA1 lc=3 lp=0 pb=2 s koncovou znackou. */
    function payBySquare(inv, lzma) {
        var payload = new TextEncoder().encode(payBySquarePayload(inv));
        var crc = crc32(payload);
        var total = [crc & 255, (crc >>> 8) & 255, (crc >>> 16) & 255, (crc >>> 24) & 255];
        for (var i = 0; i < payload.length; i++) {
            total.push(payload[i]);
        }
        // LZMA-JS vracia .lzma hlavicku (13 B: vlastnosti, slovnik, dlzka) - PAY by square chce raw prud.
        var compressed = Array.prototype.slice.call(lzma.compress(total, 1), 13).map(function (b) {
            return b & 255;
        });
        var bytes = [0, 0, total.length & 255, (total.length >>> 8) & 255].concat(compressed);
        var alphabet = '0123456789ABCDEFGHIJKLMNOPQRSTUV';
        var out = '';
        var buffer = 0;
        var bits = 0;
        bytes.forEach(function (b) {
            buffer = ((buffer << 8) | b) & 0xFFFF;
            bits += 8;
            while (bits >= 5) {
                out += alphabet[(buffer >>> (bits - 5)) & 31];
                bits -= 5;
            }
        });
        if (bits > 0) {
            out += alphabet[(buffer << (5 - bits)) & 31];
        }
        return out;
    }

    var api = {
        parseScaled: parseScaled, centsToPlain: centsToPlain, formatMoney: formatMoney, milliToPlain: milliToPlain,
        isIco: isIco, isDic: isDic, isIcDph: isIcDph, isVs: isVs, isIban: isIban, strip: strip,
        peppolId: peppolId, isVatRegistered: isVatRegistered,
        formatNumber: formatNumber, vsOf: vsOf,
        parseLine: parseLine, lineNet: lineNet, totals: totals,
        validate: validate, validateForPeppol: validateForPeppol,
        ublXml: ublXml, payBySquarePayload: payBySquarePayload, payBySquare: payBySquare
    };
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    } else {
        root.UctoCore = api;
    }
})(typeof self !== 'undefined' ? self : this);
