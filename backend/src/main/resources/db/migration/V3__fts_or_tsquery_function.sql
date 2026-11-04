-- Fix FTS retrieval: websearch_to_tsquery ANDs all terms, so natural-language
-- questions almost never match any chunk. Replace with an OR-of-stemmed-terms
-- tsquery built from the question (stopwords dropped, Porter-stemmed via the
-- english_stem dictionary). Empty/stopword-only input yields a NULL tsquery,
-- which matches nothing and never errors.

CREATE OR REPLACE FUNCTION verity_or_tsquery(q text) RETURNS tsquery
LANGUAGE sql IMMUTABLE AS $$
  SELECT to_tsquery('english', string_agg(stem, ' | '))
  FROM (
    SELECT (ts_lexize('english_stem', lower(w)))[1] AS stem
    FROM unnest(regexp_split_to_array(coalesce(q, ''), '[^[:alnum:]'']')) AS w
    WHERE w <> ''
  ) s
  WHERE stem IS NOT NULL AND stem <> '';
$$;
