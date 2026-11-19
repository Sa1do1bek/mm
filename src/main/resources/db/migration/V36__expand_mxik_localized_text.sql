ALTER TABLE public.mxik
    ADD COLUMN name_ru text;

ALTER TABLE public.mxik
    ALTER COLUMN name TYPE text,
    ALTER COLUMN group_name TYPE text,
    ALTER COLUMN group_name_ru TYPE text,
    ALTER COLUMN group_name_cyril TYPE text,
    ALTER COLUMN class_name TYPE text,
    ALTER COLUMN class_name_ru TYPE text,
    ALTER COLUMN class_name_cyril TYPE text,
    ALTER COLUMN position_name TYPE text,
    ALTER COLUMN position_name_ru TYPE text,
    ALTER COLUMN position_name_cyril TYPE text,
    ALTER COLUMN sub_position_name TYPE text,
    ALTER COLUMN sub_position_name_ru TYPE text,
    ALTER COLUMN sub_position_name_cyril TYPE text,
    ALTER COLUMN brand_name TYPE text,
    ALTER COLUMN brand_name_ru TYPE text,
    ALTER COLUMN brand_name_cyril TYPE text,
    ALTER COLUMN attribute_name TYPE text,
    ALTER COLUMN attribute_name_ru TYPE text,
    ALTER COLUMN attribute_name_cyril TYPE text;

CREATE INDEX idx_mxik_name_ru_trgm
    ON public.mxik USING gin (lower(name_ru) public.gin_trgm_ops)
    WHERE name_ru IS NOT NULL;
