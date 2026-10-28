package dev.portfolio.opspilot.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

@Repository
public class DocumentStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public DocumentStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc; this.json = json;
        jdbc.execute("CREATE TABLE IF NOT EXISTS documents (kind VARCHAR(32), id VARCHAR(64), updated_at VARCHAR(40), payload CLOB NOT NULL, PRIMARY KEY(kind,id))");
    }
    public void save(String kind, String id, Object document) {
        try {
            jdbc.update("MERGE INTO documents (kind,id,updated_at,payload) KEY(kind,id) VALUES (?,?,?,?)", kind, id, Instant.now().toString(), json.writeValueAsString(document));
        } catch (JsonProcessingException e) { throw new IllegalStateException("保存工作记录失败", e); }
    }
    public <T> T get(String kind, String id, Class<T> type) {
        var values = jdbc.query("SELECT payload FROM documents WHERE kind=? AND id=?", (rs, row) -> decode(rs.getString(1), type), kind, id);
        if (values.isEmpty()) throw new NoSuchElementException("记录不存在");
        return values.getFirst();
    }
    public <T> List<T> list(String kind, Class<T> type) {
        return jdbc.query("SELECT payload FROM documents WHERE kind=? ORDER BY updated_at DESC LIMIT 100", (rs, row) -> decode(rs.getString(1), type), kind);
    }
    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("读取工作记录失败", e); }
    }
}
