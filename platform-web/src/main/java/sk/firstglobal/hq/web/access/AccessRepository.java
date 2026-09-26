package sk.firstglobal.hq.web.access;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class AccessRepository {
    private final JdbcClient jdbc;

    public AccessRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- roly ----------

    public record Role(long id, String code, String name, String description, List<String> permissions,
                       String personRole, boolean system, int users) {
        public List<Permission> permissionList() {
            return Permission.parse(this.permissions);
        }

        public boolean isSensitive() {
            return this.permissionList().stream().anyMatch(p -> !p.isSafeForOpenInvite());
        }
    }

    private static final RowMapper<Role> ROLE = (rs, n) -> new Role(rs.getLong("id"), rs.getString("code"),
            rs.getString("name"), rs.getString("description"), strings(rs.getArray("permissions")),
            rs.getString("person_role"), rs.getBoolean("system"), rs.getInt("users"));

    private static final String ROLE_SELECT = """
            SELECT r.*, (SELECT count(*) FROM user_role ur JOIN app_user u ON u.id = ur.user_id
                         WHERE ur.role_id = r.id AND u.active)::int AS users
            FROM app_role r
            """;

    public List<Role> roles() {
        return this.jdbc.sql(ROLE_SELECT + "ORDER BY r.system DESC, r.id").query(ROLE).list();
    }

    public Optional<Role> role(long id) {
        return this.jdbc.sql(ROLE_SELECT + "WHERE r.id = :id").param("id", id).query(ROLE).optional();
    }

    public Optional<Role> roleByCode(String code) {
        return this.jdbc.sql(ROLE_SELECT + "WHERE r.code = :c").param("c", code).query(ROLE).optional();
    }

    public long insertRole(String code, String name, String description, List<String> permissions, String personRole) {
        return this.jdbc.sql("""
                        INSERT INTO app_role (code, name, description, permissions, person_role)
                        VALUES (:code, :name, :description, :perms, :personRole) RETURNING id""")
                .param("code", code).param("name", name).param("description", description)
                .param("perms", permissions.toArray(String[]::new)).param("personRole", personRole)
                .query(Long.class).single();
    }

    public void updateRole(long id, String name, String description, List<String> permissions, String personRole) {
        this.jdbc.sql("""
                        UPDATE app_role SET name = :name, description = :description, permissions = :perms,
                               person_role = :personRole WHERE id = :id""")
                .param("id", id).param("name", name).param("description", description)
                .param("perms", permissions.toArray(String[]::new)).param("personRole", personRole).update();
    }

    public int deleteRole(long id) {
        return this.jdbc.sql("DELETE FROM app_role WHERE id = :id AND NOT system "
                        + "AND NOT EXISTS (SELECT 1 FROM user_role WHERE role_id = :id)")
                .param("id", id).update();
    }

    // ---------- pouzivatelia ----------

    public record User(long id, String email, String displayName, Long personId, String personName, boolean active,
                       String createdBy, OffsetDateTime createdAt, OffsetDateTime lastSeenAt, List<String> roleCodes,
                       List<String> roleNames, List<String> ownedCodes, List<Long> ownedIds) {
    }

    private static final String USER_SELECT = """
            SELECT u.id, u.email, u.display_name, u.person_id, p.full_name AS person_name, u.active, u.created_by,
                   u.created_at, u.last_seen_at,
                   ARRAY(SELECT r.code FROM user_role ur JOIN app_role r ON r.id = ur.role_id
                         WHERE ur.user_id = u.id ORDER BY r.id) AS role_codes,
                   ARRAY(SELECT r.name FROM user_role ur JOIN app_role r ON r.id = ur.role_id
                         WHERE ur.user_id = u.id ORDER BY r.id) AS role_names,
                   ARRAY(SELECT pr.code FROM activity_owner o JOIN project pr ON pr.id = o.project_id
                         WHERE o.user_id = u.id ORDER BY pr.code) AS owned_codes,
                   ARRAY(SELECT pr.id FROM activity_owner o JOIN project pr ON pr.id = o.project_id
                         WHERE o.user_id = u.id ORDER BY pr.code) AS owned_ids
            FROM app_user u LEFT JOIN person p ON p.id = u.person_id
            """;

    private static final RowMapper<User> USER = (rs, n) -> new User(rs.getLong("id"), rs.getString("email"),
            rs.getString("display_name"), nullableLong(rs, "person_id"), rs.getString("person_name"),
            rs.getBoolean("active"), rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("last_seen_at", OffsetDateTime.class), strings(rs.getArray("role_codes")),
            strings(rs.getArray("role_names")), strings(rs.getArray("owned_codes")), longs(rs.getArray("owned_ids")));

    public List<User> users() {
        return this.jdbc.sql(USER_SELECT + "ORDER BY u.active DESC, u.email").query(USER).list();
    }

    public Optional<User> user(long id) {
        return this.jdbc.sql(USER_SELECT + "WHERE u.id = :id").param("id", id).query(USER).optional();
    }

    public Optional<User> userByEmail(String email) {
        return this.jdbc.sql(USER_SELECT + "WHERE u.email = lower(trim(:e))").param("e", email).query(USER).optional();
    }

    public long insertUser(String email, String displayName, Long personId, String actor) {
        return this.jdbc.sql("""
                        INSERT INTO app_user (email, display_name, person_id, created_by)
                        VALUES (lower(trim(:e)), :name, :person, :actor) RETURNING id""")
                .param("e", email).param("name", displayName).param("person", personId).param("actor", actor)
                .query(Long.class).single();
    }

    public void setActive(long userId, boolean active) {
        this.jdbc.sql("UPDATE app_user SET active = :a WHERE id = :id").param("a", active).param("id", userId).update();
    }

    public void setPerson(long userId, Long personId) {
        this.jdbc.sql("UPDATE app_user SET person_id = :p WHERE id = :id").param("p", personId).param("id", userId).update();
    }

    public void touch(long userId) {
        this.jdbc.sql("""
                        UPDATE app_user SET last_seen_at = now()
                        WHERE id = :id AND (last_seen_at IS NULL OR last_seen_at < now() - interval '5 minutes')""")
                .param("id", userId).update();
    }

    public void setRoles(long userId, List<Long> roleIds) {
        this.jdbc.sql("DELETE FROM user_role WHERE user_id = :u").param("u", userId).update();
        this.addRoles(userId, roleIds);
    }

    public void addRoles(long userId, List<Long> roleIds) {
        for (Long r : roleIds) {
            this.jdbc.sql("INSERT INTO user_role (user_id, role_id) VALUES (:u, :r) ON CONFLICT DO NOTHING")
                    .param("u", userId).param("r", r).update();
        }
    }

    /** Opravnenia zo vsetkych rol aktivneho pouzivatela. */
    public Set<String> permissionsOf(long userId) {
        return this.jdbc.sql("""
                        SELECT DISTINCT unnest(r.permissions) FROM user_role ur JOIN app_role r ON r.id = ur.role_id
                        WHERE ur.user_id = :u""")
                .param("u", userId).query(String.class).set();
    }

    public long activeAdmins() {
        return this.jdbc.sql("""
                        SELECT count(DISTINCT u.id) FROM app_user u JOIN user_role ur ON ur.user_id = u.id
                        JOIN app_role r ON r.id = ur.role_id WHERE u.active AND 'ADMIN' = ANY (r.permissions)""")
                .query(Long.class).single();
    }

    // ---------- vlastnictvo aktivit ----------

    public Set<Long> ownedProjects(long userId) {
        return this.jdbc.sql("SELECT project_id FROM activity_owner WHERE user_id = :u").param("u", userId)
                .query(Long.class).set();
    }

    public record Owner(long userId, String email, String displayName) {
    }

    public List<Owner> ownersOf(long projectId) {
        return this.jdbc.sql("""
                        SELECT u.id AS user_id, u.email, u.display_name FROM activity_owner o JOIN app_user u ON u.id = o.user_id
                        WHERE o.project_id = :p ORDER BY u.email""")
                .param("p", projectId).query(Owner.class).list();
    }

    public boolean addOwner(long userId, long projectId, String actor) {
        return this.jdbc.sql("""
                        INSERT INTO activity_owner (user_id, project_id, added_by) VALUES (:u, :p, :a)
                        ON CONFLICT DO NOTHING""")
                .param("u", userId).param("p", projectId).param("a", actor).update() == 1;
    }

    public int removeOwner(long userId, long projectId) {
        return this.jdbc.sql("DELETE FROM activity_owner WHERE user_id = :u AND project_id = :p")
                .param("u", userId).param("p", projectId).update();
    }

    // ---------- pozvanky ----------

    public record Invite(long id, String email, List<Long> roleIds, Long projectId, String projectCode, String note,
                         int maxUses, int usedCount, OffsetDateTime expiresAt, OffsetDateTime revokedAt,
                         String createdBy, OffsetDateTime createdAt) {
        public boolean isUsable(OffsetDateTime now) {
            return this.revokedAt == null && this.usedCount < this.maxUses && this.expiresAt.isAfter(now);
        }

        public String state(OffsetDateTime now) {
            if (this.revokedAt != null) {
                return "zrušená";
            }
            if (this.usedCount >= this.maxUses) {
                return "využitá";
            }
            return this.expiresAt.isAfter(now) ? "platná" : "vypršala";
        }
    }

    private static final String INVITE_SELECT = """
            SELECT i.id, i.email, i.role_ids, i.project_id, pr.code AS project_code, i.note, i.max_uses, i.used_count,
                   i.expires_at, i.revoked_at, i.created_by, i.created_at
            FROM invite i LEFT JOIN project pr ON pr.id = i.project_id
            """;

    private static final RowMapper<Invite> INVITE = (rs, n) -> new Invite(rs.getLong("id"), rs.getString("email"),
            longs(rs.getArray("role_ids")), nullableLong(rs, "project_id"), rs.getString("project_code"),
            rs.getString("note"), rs.getInt("max_uses"), rs.getInt("used_count"),
            rs.getObject("expires_at", OffsetDateTime.class), rs.getObject("revoked_at", OffsetDateTime.class),
            rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class));

    public List<Invite> invites() {
        return this.jdbc.sql(INVITE_SELECT + "ORDER BY i.created_at DESC LIMIT 200").query(INVITE).list();
    }

    public Optional<Invite> inviteByHash(String hash) {
        return this.jdbc.sql(INVITE_SELECT + "WHERE i.token_hash = :h").param("h", hash).query(INVITE).optional();
    }

    public long insertInvite(String hash, String email, List<Long> roleIds, Long projectId, String note, int maxUses,
                             OffsetDateTime expiresAt, String actor) {
        return this.jdbc.sql("""
                        INSERT INTO invite (token_hash, email, role_ids, project_id, note, max_uses, expires_at, created_by)
                        VALUES (:h, :e, :roles, :p, :note, :max, :exp, :actor) RETURNING id""")
                .param("h", hash).param("e", email).param("roles", roleIds.toArray(Long[]::new)).param("p", projectId)
                .param("note", note).param("max", maxUses).param("exp", expiresAt).param("actor", actor)
                .query(Long.class).single();
    }

    /** Atomicky: pouzitie sa zapocita len ak je pozvanka stale platna (dvaja naraz neprejdu cez limit). */
    public boolean consume(long inviteId, OffsetDateTime now) {
        return this.jdbc.sql("""
                        UPDATE invite SET used_count = used_count + 1
                        WHERE id = :id AND revoked_at IS NULL AND used_count < max_uses AND expires_at > :now""")
                .param("id", inviteId).param("now", now).update() == 1;
    }

    public boolean recordUse(long inviteId, long userId) {
        return this.jdbc.sql("INSERT INTO invite_use (invite_id, user_id) VALUES (:i, :u) ON CONFLICT DO NOTHING")
                .param("i", inviteId).param("u", userId).update() == 1;
    }

    public void revoke(long inviteId) {
        this.jdbc.sql("UPDATE invite SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL")
                .param("id", inviteId).update();
    }

    private static Long nullableLong(java.sql.ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static List<String> strings(Array a) throws SQLException {
        return a == null ? List.of() : Arrays.asList((String[])a.getArray());
    }

    private static List<Long> longs(Array a) throws SQLException {
        return a == null ? List.of() : Arrays.stream((Long[])a.getArray()).collect(Collectors.toList());
    }
}
