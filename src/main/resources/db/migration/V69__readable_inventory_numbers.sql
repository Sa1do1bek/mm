-- Keep internal codes/references intact. Allocate stable, concurrent-safe display numbers.
CREATE SEQUENCE inventory_document_number_seq;
ALTER TABLE inventory_documents ADD COLUMN inventory_sequence bigint;
WITH numbered AS (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS number
    FROM inventory_documents
)
UPDATE inventory_documents d SET inventory_sequence = n.number
FROM numbered n WHERE n.id = d.id;
SELECT setval('inventory_document_number_seq', COALESCE(MAX(inventory_sequence), 0) + 1, false)
FROM inventory_documents;
ALTER TABLE inventory_documents
    ALTER COLUMN inventory_sequence SET DEFAULT nextval('inventory_document_number_seq'),
    ALTER COLUMN inventory_sequence SET NOT NULL;
ALTER SEQUENCE inventory_document_number_seq OWNED BY inventory_documents.inventory_sequence;
CREATE UNIQUE INDEX uk_inventory_document_number ON inventory_documents(inventory_sequence);
