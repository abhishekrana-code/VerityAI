-- The graph canvas is gone and document connections are derived at read time
-- from shared entity names, so the per-document co-occurrence edges are dead
-- weight (up to 1,500 rows per document). Entities are kept.
DROP TABLE IF EXISTS knowledge_relations;
