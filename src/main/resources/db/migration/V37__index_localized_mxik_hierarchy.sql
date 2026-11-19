CREATE INDEX idx_mxik_catalog_group_name_ru
    ON public.mxik ((coalesce(nullif(btrim(group_name_ru), ''), group_name)));

CREATE INDEX idx_mxik_catalog_group_class_ru
    ON public.mxik (
        (coalesce(nullif(btrim(group_name_ru), ''), group_name)),
        (coalesce(nullif(btrim(class_name_ru), ''), class_name))
    );

CREATE INDEX idx_mxik_catalog_class_position_ru
    ON public.mxik (
        (coalesce(nullif(btrim(class_name_ru), ''), class_name)),
        (coalesce(nullif(btrim(position_name_ru), ''), position_name))
    );

CREATE INDEX idx_mxik_catalog_position_sub_position_ru
    ON public.mxik (
        (coalesce(nullif(btrim(position_name_ru), ''), position_name)),
        (coalesce(nullif(btrim(sub_position_name_ru), ''), sub_position_name))
    );

CREATE INDEX idx_mxik_catalog_sub_position_name_ru
    ON public.mxik (
        (coalesce(nullif(btrim(sub_position_name_ru), ''), sub_position_name)),
        (coalesce(nullif(btrim(name_ru), ''), name)),
        kod
    );
