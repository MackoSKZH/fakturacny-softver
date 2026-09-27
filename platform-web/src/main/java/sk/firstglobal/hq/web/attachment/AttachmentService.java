package sk.firstglobal.hq.web.attachment;

import sk.firstglobal.hq.web.audit.AuditLog;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Nahravanie podkladov. Typ suboru overujeme podla obsahu (magicke bajty), nie podla pripony, ktoru
 * posle prehliadac - premenovane HTML/SVG sa tak nedostane dnu. Stahuje sa vzdy ako priloha.
 */
@Service
public class AttachmentService {
    public static final int MAX_BYTES = 10 * 1024 * 1024;

    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] PNG = {(byte)0x89, 'P', 'N', 'G'};
    private static final byte[] JPG = {(byte)0xFF, (byte)0xD8, (byte)0xFF};
    private static final byte[] ZIP = {'P', 'K', 3, 4};
    private static final byte[] OLE = {(byte)0xD0, (byte)0xCF, 0x11, (byte)0xE0};

    private record Kind(String contentType, byte[] magic) {
    }

    private static final Map<String, Kind> KINDS = Map.ofEntries(
            Map.entry("pdf", new Kind("application/pdf", PDF)),
            Map.entry("png", new Kind("image/png", PNG)),
            Map.entry("jpg", new Kind("image/jpeg", JPG)),
            Map.entry("jpeg", new Kind("image/jpeg", JPG)),
            Map.entry("docx", new Kind("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ZIP)),
            Map.entry("xlsx", new Kind("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ZIP)),
            Map.entry("pptx", new Kind("application/vnd.openxmlformats-officedocument.presentationml.presentation", ZIP)),
            Map.entry("odt", new Kind("application/vnd.oasis.opendocument.text", ZIP)),
            Map.entry("ods", new Kind("application/vnd.oasis.opendocument.spreadsheet", ZIP)),
            Map.entry("zip", new Kind("application/zip", ZIP)),
            Map.entry("doc", new Kind("application/msword", OLE)),
            Map.entry("xls", new Kind("application/vnd.ms-excel", OLE)),
            Map.entry("txt", new Kind("text/plain;charset=UTF-8", null)),
            Map.entry("csv", new Kind("text/csv;charset=UTF-8", null)));

    public static final String ALLOWED = "PDF, Word, Excel, PowerPoint, OpenDocument, PNG, JPG, ZIP, TXT, CSV";

    private final AttachmentRepository repo;
    private final AuditLog audit;

    public AttachmentService(AttachmentRepository repo, AuditLog audit) {
        this.repo = repo;
        this.audit = audit;
    }

    public long upload(AttachmentRepository.Owner owner, long ownerId, MultipartFile file, String category,
                       boolean editorsOnly, String note, String actor) {
        if (file == null || file.isEmpty()) {
            throw new AttachmentException("Vyberte súbor.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new AttachmentException("Súbor má viac než 10 MB. Veľké videá a fotky dajte na Drive a sem vložte odkaz do poznámky.");
        }
        if (!AttachmentCategory.exists(category)) {
            throw new AttachmentException("Vyberte druh podkladu.");
        }
        String name = cleanName(file.getOriginalFilename());
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        Kind kind = KINDS.get(ext);
        if (kind == null) {
            throw new AttachmentException("Typ súboru ." + ext + " nie je povolený. Povolené: " + ALLOWED + ".");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!matches(content, kind)) {
            throw new AttachmentException("Obsah súboru " + name + " nezodpovedá prípone ." + ext + ".");
        }
        String sha = sha256(content);
        if (this.repo.exists(owner, ownerId, sha)) {
            throw new AttachmentException("Tento súbor tu už je nahraný.");
        }
        boolean restricted = editorsOnly || AttachmentCategory.ZMLUVA.name().equals(category);
        long id = this.repo.insert(owner, ownerId, category, name, kind.contentType(), content, sha, restricted,
                note == null || note.isBlank() ? null : note.trim(), actor);
        this.audit.record(actor, "NAHRATIE", "priloha", id, name);
        return id;
    }

    public Attachment delete(long id, String actor) {
        Attachment a = this.repo.find(id).orElseThrow(() -> new AttachmentException("Súbor neexistuje."));
        this.repo.delete(id);
        this.audit.record(actor, "ZMAZANIE", "priloha", id, a.fileName());
        return a;
    }

    /** Bez cesty, riadiacich znakov a uvodzoviek; najviac 150 znakov s ponechanou priponou. */
    static String cleanName(String raw) {
        String n = raw == null ? "" : raw.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>|:*?]", "").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) {
            n = "subor";
        }
        if (n.length() > 150) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 && n.length() - dot <= 10 ? n.substring(dot) : "";
            n = n.substring(0, 150 - ext.length()) + ext;
        }
        return n;
    }

    private static boolean matches(byte[] content, Kind kind) {
        if (kind.magic() == null) {
            return isPlainText(content);
        }
        if (content.length < kind.magic().length) {
            return false;
        }
        for (int i = 0; i < kind.magic().length; i++) {
            if (content[i] != kind.magic()[i]) {
                return false;
            }
        }
        return true;
    }

    /** Text musi byt platne UTF-8 bez nulovych bajtov a nesmie vyzerat ako HTML. */
    private static boolean isPlainText(byte[] content) {
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
            String head = s.stripLeading().toLowerCase(Locale.ROOT);
            return s.indexOf('\0') < 0 && !head.startsWith("<");
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<AttachmentCategory> categories() {
        return List.of(AttachmentCategory.values());
    }
}
