-- Die Tabelle des batch-writer. Spring Boot führt diese Datei bei JEDEM Start
-- aus, deshalb ist alles mit IF NOT EXISTS geschrieben: ein zweiter Start
-- ändert nichts. Begründungen: docs/spec-batch-writer.md, Abschnitt 4.

CREATE TABLE IF NOT EXISTS message (
    -- Vom chat-service vergeben. Als Primärschlüssel der Schutz gegen
    -- Duplikate: dieselbe Nachricht kann nur einmal in der Tabelle stehen.
    id          UUID        PRIMARY KEY,
    -- Bewusst kein Fremdschlüssel auf eine Tabelle room: Räume sind nicht
    -- Teil dieser Aufgabe, ein Fremdschlüssel würde jede Nachricht ablehnen.
    room_id     UUID        NOT NULL,
    -- VARCHAR ohne Länge: ein zu langer Name soll nicht den Stapel scheitern
    -- lassen. In PostgreSQL ist das genauso schnell wie TEXT.
    sender_id   VARCHAR     NOT NULL,
    sender_name VARCHAR     NOT NULL,
    content     TEXT        NOT NULL,
    sent_at     TIMESTAMPTZ NOT NULL
);

-- Für die einzige geplante Leseabfrage: die letzten 50 Nachrichten eines Raums.
CREATE INDEX IF NOT EXISTS message_room_id_sent_at_idx
    ON message (room_id, sent_at DESC);
