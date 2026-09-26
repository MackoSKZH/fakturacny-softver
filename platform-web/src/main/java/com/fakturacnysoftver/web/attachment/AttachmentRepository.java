package com.fakturacnysoftver.web.attachment;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class AttachmentRepository {
    private static final String SELECT = """
            SELECT id, project_id, partner_id, deal_id, category, file_name, content_type, size_bytes, editors_only, note,
                   uploaded_by, uploaded_at
            FROM attachment
            """;

    private final JdbcClient jdbc;

    public AttachmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public enum Owner {
        PROJECT("project_id"), PARTNER("partner_id"), DEAL("deal_id");

        final String column;

        Owner(String column) {
            this.column = column;
        }
    }

    public List<Attachment> of(Owner owner, long ownerId) {
        return this.jdbc.sql(SELECT + "WHERE " + owner.column + " = :id ORDER BY category, lower(file_name)")
                .param("id", ownerId).query(Attachment.class).list();
    }

    /** Len subory, ktore dany pouzivatel smie vidiet. */
    public List<Attachment> visible(Owner owner, long ownerId, boolean editor) {
        return this.of(owner, ownerId).stream().filter(a -> a.visibleTo(editor)).toList();
    }

    public Optional<Attachment> find(long id) {
        return this.jdbc.sql(SELECT + "WHERE id = :id").param("id", id).query(Attachment.class).optional();
    }

    public byte[] content(long id) {
        return this.jdbc.sql("SELECT content FROM attachment WHERE id = :id").param("id", id).query(byte[].class).single();
    }

    public boolean exists(Owner owner, long ownerId, String sha256) {
        return this.jdbc.sql("SELECT count(*) FROM attachment WHERE " + owner.column + " = :id AND sha256 = :sha")
                .param("id", ownerId).param("sha", sha256).query(Long.class).single() > 0;
    }

    public long insert(Owner owner, long ownerId, String category, String fileName, String contentType, byte[] content,
                       String sha256, boolean editorsOnly, String note, String actor) {
        return this.jdbc.sql("INSERT INTO attachment (" + owner.column + """
                        , category, file_name, content_type, size_bytes, sha256, content, editors_only, note, uploaded_by)
                        VALUES (:owner, :category, :name, :type, :size, :sha, :content, :editorsOnly, :note, :actor)
                        RETURNING id""")
                .param("owner", ownerId).param("category", category).param("name", fileName).param("type", contentType)
                .param("size", content.length).param("sha", sha256).param("content", content)
                .param("editorsOnly", editorsOnly).param("note", note).param("actor", actor)
                .query(Long.class).single();
    }

    public int delete(long id) {
        return this.jdbc.sql("DELETE FROM attachment WHERE id = :id").param("id", id).update();
    }
}
