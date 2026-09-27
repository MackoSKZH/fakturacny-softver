package sk.firstglobal.hq.web.activity;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public class TaskRepository {
    private final JdbcClient jdbc;

    public TaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Task(long id, String section, String title, LocalDate dueOn, Long assigneeId, String assigneeName,
                       boolean done, String doneBy) {
        public boolean isOverdue(LocalDate today) {
            return !this.done && this.dueOn != null && this.dueOn.isBefore(today);
        }
    }

    public List<Task> tasksOf(long projectId) {
        return this.jdbc.sql("""
                        SELECT t.id, t.section, t.title, t.due_on, t.assignee_person_id AS assignee_id,
                               p.full_name AS assignee_name, t.done, t.done_by
                        FROM task t LEFT JOIN person p ON p.id = t.assignee_person_id
                        WHERE t.project_id = :p
                        ORDER BY t.done, t.due_on NULLS LAST, t.position, t.id""")
                .param("p", projectId).query(Task.class).list();
    }

    public long add(long projectId, String section, String title, LocalDate dueOn, Long assigneeId) {
        return this.jdbc.sql("""
                        INSERT INTO task (project_id, section, title, due_on, assignee_person_id, position)
                        VALUES (:p, :section, :title, :due, :assignee,
                                COALESCE((SELECT max(position) + 1 FROM task WHERE project_id = :p), 0))
                        RETURNING id""")
                .param("p", projectId).param("section", section).param("title", title).param("due", dueOn)
                .param("assignee", assigneeId).query(Long.class).single();
    }

    public int toggle(long projectId, long taskId, String actor) {
        return this.jdbc.sql("""
                        UPDATE task SET done = NOT done,
                               done_at = CASE WHEN done THEN NULL ELSE now() END,
                               done_by = CASE WHEN done THEN NULL ELSE :actor END
                        WHERE id = :t AND project_id = :p""")
                .param("actor", actor).param("t", taskId).param("p", projectId).update();
    }

    public int delete(long projectId, long taskId) {
        return this.jdbc.sql("DELETE FROM task WHERE id = :t AND project_id = :p")
                .param("t", taskId).param("p", projectId).update();
    }

    /** Vytvori ulohy zo sablony; terminy podla zaciatku aktivity. Vrati pocet vytvorenych. */
    public int applyTemplate(long projectId, String kind, LocalDate startsOn) {
        return this.jdbc.sql("""
                        INSERT INTO task (project_id, section, title, due_on, position)
                        SELECT :p, t.section, t.title,
                               CASE WHEN CAST(:start AS date) IS NULL THEN NULL ELSE CAST(:start AS date) + t.offset_days END,
                               t.position
                        FROM checklist_item_template t
                        WHERE t.kind = :kind
                          AND NOT EXISTS (SELECT 1 FROM task x WHERE x.project_id = :p AND x.title = t.title)""")
                .param("p", projectId).param("kind", kind).param("start", startsOn).update();
    }

    public int templateSize(String kind) {
        return this.jdbc.sql("SELECT count(*) FROM checklist_item_template WHERE kind = :k").param("k", kind)
                .query(Integer.class).single();
    }
}
