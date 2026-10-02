package ch.benedict.m321.batchwriter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft, dass beim Start genau die Tabelle aus der Spezifikation entsteht
 * (Abschnitt 4.1): sechs Spalten, Primärschlüssel auf id, Index für den Raum.
 */
class SchemaIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    /** Die sechs Spalten aus PLANUNG.md 3.7, jede mit dem geplanten Typ und NOT NULL. */
    @Test
    void createsMessageTableWithPlannedColumns() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT column_name, data_type, is_nullable FROM information_schema.columns"
                        + " WHERE table_name = 'message'");

        Map<String, String> typeByColumn = new HashMap<>();
        for (Map<String, Object> column : columns) {
            String columnName = (String) column.get("column_name");
            String dataType = (String) column.get("data_type");
            String nullable = (String) column.get("is_nullable");
            typeByColumn.put(columnName, dataType);
            assertEquals("NO", nullable, "Spalte " + columnName + " muss NOT NULL sein");
        }

        assertEquals(6, typeByColumn.size());
        assertEquals("uuid", typeByColumn.get("id"));
        assertEquals("uuid", typeByColumn.get("room_id"));
        assertEquals("character varying", typeByColumn.get("sender_id"));
        assertEquals("character varying", typeByColumn.get("sender_name"));
        assertEquals("text", typeByColumn.get("content"));
        assertEquals("timestamp with time zone", typeByColumn.get("sent_at"));
    }

    /** Der Primärschlüssel ist der Schutz gegen Duplikate, er muss auf id liegen. */
    @Test
    void usesIdAsPrimaryKey() {
        String primaryKeyColumn = jdbcTemplate.queryForObject(
                "SELECT key_column.column_name"
                        + " FROM information_schema.table_constraints table_constraint"
                        + " JOIN information_schema.key_column_usage key_column"
                        + " ON table_constraint.constraint_name = key_column.constraint_name"
                        + " WHERE table_constraint.table_name = 'message'"
                        + " AND table_constraint.constraint_type = 'PRIMARY KEY'",
                String.class);

        assertEquals("id", primaryKeyColumn);
    }

    /** Der Index für die einzige geplante Leseabfrage: die neusten Nachrichten eines Raums. */
    @Test
    void createsIndexForRoomAndTime() {
        String indexDefinition = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes"
                        + " WHERE tablename = 'message' AND indexname = 'message_room_id_sent_at_idx'",
                String.class);

        assertTrue(indexDefinition.contains("(room_id, sent_at DESC)"), indexDefinition);
    }

    /**
     * Das Skript läuft bei jedem Start. Ein zweiter Lauf auf einer bestehenden
     * Tabelle darf deshalb nicht scheitern. Wirft execute eine Ausnahme, ist
     * der Test rot.
     */
    @Test
    void canRunSchemaTwice() {
        ClassPathResource schema = new ClassPathResource("schema.sql");
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(schema);

        populator.execute(dataSource);

        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'message'",
                Integer.class);
        assertEquals(1, tableCount);
    }
}
