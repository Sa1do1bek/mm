-- ============================================================================
-- Migration: V4__seed_units_of_measure.sql
-- Description: Updates existing valid measurement units with trilingual support
--              (English, Russian, Uzbek) and proper MeasurementUnitType enum.
--              Inserts a comprehensive set of real standard measurement units.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. UPDATE EXISTING VALID LEGACY UNITS (Leaving deleted ones untouched)
-- ----------------------------------------------------------------------------

-- 1. m (Meter - LENGTH)
UPDATE unit_of_measures SET
    measurement_name_short = 'm', measurement_name_long = 'Meter',
    measurement_name_short_ru = 'м', measurement_name_long_ru = 'Метр',
    measurement_name_short_uz = 'm', measurement_name_long_uz = 'Metr',
    name = 'ru: Метр' || E'\nen: Meter' || E'\nuz: Metr',
    unit_type = 'LENGTH', updated_at = NOW(), updated_by = 'migration'
WHERE (LOWER(measurement_name_short) = 'm' OR LOWER(code) = 'm') AND unit_type != 'LENGTH';

-- 2. шт (Piece - QUANTITY)
UPDATE unit_of_measures SET
    measurement_name_short = 'pcs', measurement_name_long = 'Piece',
    measurement_name_short_ru = 'шт', measurement_name_long_ru = 'Штука',
    measurement_name_short_uz = 'dona', measurement_name_long_uz = 'Dona',
    name = 'ru: Штука' || E'\nen: Piece' || E'\nuz: Dona',
    unit_type = 'QUANTITY', updated_at = NOW(), updated_by = 'migration'
WHERE LOWER(measurement_name_short) = 'шт' OR LOWER(code) = 'шт';

-- 3. т (Tonne - WEIGHT)
UPDATE unit_of_measures SET
    measurement_name_short = 't', measurement_name_long = 'Tonne',
    measurement_name_short_ru = 'т', measurement_name_long_ru = 'Тонна',
    measurement_name_short_uz = 't', measurement_name_long_uz = 'Tonna',
    name = 'ru: Тонна' || E'\nen: Tonne' || E'\nuz: Tonna',
    unit_type = 'WEIGHT', updated_at = NOW(), updated_by = 'migration'
WHERE LOWER(measurement_name_short) = 'т' OR LOWER(code) = 'т';

-- 4. см2 (Square centimeter - AREA)
UPDATE unit_of_measures SET
    measurement_name_short = 'cm2', measurement_name_long = 'Square centimeter',
    measurement_name_short_ru = 'см2', measurement_name_long_ru = 'Квадратный сантиметр',
    measurement_name_short_uz = 'sm2', measurement_name_long_uz = 'Kvadrat santimetr',
    name = 'ru: Квадратный сантиметр' || E'\nen: Square centimeter' || E'\nuz: Kvadrat santimetr',
    unit_type = 'AREA', updated_at = NOW(), updated_by = 'migration'
WHERE LOWER(measurement_name_short) = 'см2' OR LOWER(code) = 'см2';

-- 5. м3 (Cubic meter - VOLUME)
UPDATE unit_of_measures SET
    measurement_name_short = 'm3', measurement_name_long = 'Cubic meter',
    measurement_name_short_ru = 'м3', measurement_name_long_ru = 'Кубический метр',
    measurement_name_short_uz = 'm3', measurement_name_long_uz = 'Kub metr',
    name = 'ru: Кубический метр' || E'\nen: Cubic meter' || E'\nuz: Kub metr',
    unit_type = 'VOLUME', updated_at = NOW(), updated_by = 'migration'
WHERE LOWER(measurement_name_short) = 'м3' OR LOWER(code) = 'м3';

-- 6. м2 (Square meter - AREA)
UPDATE unit_of_measures SET
    measurement_name_short = 'm2', measurement_name_long = 'Square meter',
    measurement_name_short_ru = 'м2', measurement_name_long_ru = 'Квадратный метр',
    measurement_name_short_uz = 'm2', measurement_name_long_uz = 'Kvadrat metr',
    name = 'ru: Квадратный метр' || E'\nen: Square meter' || E'\nuz: Kvadrat metr',
    unit_type = 'AREA', updated_at = NOW(), updated_by = 'migration'
WHERE LOWER(measurement_name_short) = 'м2' OR LOWER(code) = 'м2';

-- 9. л (Liter - VOLUME)
UPDATE unit_of_measures SET
    measurement_name_short = 'l', measurement_name_long = 'Liter',
    measurement_name_short_ru = 'л', measurement_name_long_ru = 'Литр',
    measurement_name_short_uz = 'l', measurement_name_long_uz = 'Litr',
    name = 'ru: Литр' || E'\nen: Liter' || E'\nuz: Litr',
    unit_type = 'VOLUME', updated_at = NOW(), updated_by = 'migration'
WHERE LOWER(measurement_name_short) = 'л' OR LOWER(code) = 'л';

-- 10. км (Kilometer - LENGTH)
UPDATE unit_of_measures SET
    measurement_name_short = 'km', measurement_name_long = 'Kilometer',
    measurement_name_short_ru = 'км', measurement_name_long_ru = 'Километр',
    measurement_name_short_uz = 'km', measurement_name_long_uz = 'Kilometr',
    name = 'ru: Километр' || E'\nen: Kilometer' || E'\nuz: Kilometr',
    unit_type = 'LENGTH', updated_at = NOW(), updated_by = 'migration'
WHERE LOWER(measurement_name_short) = 'км' OR LOWER(code) = 'км';

-- 11. кг (Kilogram - WEIGHT)
UPDATE unit_of_measures SET
    measurement_name_short = 'kg', measurement_name_long = 'Kilogram',
    measurement_name_short_ru = 'кг', measurement_name_long_ru = 'Килограмм',
    measurement_name_short_uz = 'kg', measurement_name_long_uz = 'Kilogramm',
    name = 'ru: Килограмм' || E'\nen: Kilogram' || E'\nuz: Kilogramm',
    unit_type = 'WEIGHT', updated_at = NOW(), updated_by = 'migration'
WHERE LOWER(measurement_name_short) = 'кг' OR LOWER(code) = 'кг';

-- 12. дм.куб. (Cubic decimeter - VOLUME)
UPDATE unit_of_measures SET
    measurement_name_short = 'dm3', measurement_name_long = 'Cubic decimeter',
    measurement_name_short_ru = 'дм3', measurement_name_long_ru = 'Дециметр кубический',
    measurement_name_short_uz = 'dm3', measurement_name_long_uz = 'Kub detsimetr',
    name = 'ru: Дециметр кубический' || E'\nen: Cubic decimeter' || E'\nuz: Kub detsimetr',
    unit_type = 'VOLUME', updated_at = NOW(), updated_by = 'migration'
WHERE LOWER(measurement_name_short) = 'дм.куб.' OR LOWER(code) = 'дм.куб.';

-- 13. г (Gram - WEIGHT)
UPDATE unit_of_measures SET
    measurement_name_short = 'g', measurement_name_long = 'Gram',
    measurement_name_short_ru = 'г', measurement_name_long_ru = 'Грамм',
    measurement_name_short_uz = 'g', measurement_name_long_uz = 'Gramm',
    name = 'ru: Грамм' || E'\nen: Gram' || E'\nuz: Gramm',
    unit_type = 'WEIGHT', updated_at = NOW(), updated_by = 'migration'
WHERE LOWER(measurement_name_short) = 'г' OR LOWER(code) = 'г';

-- 14. in (Inch - LENGTH)
UPDATE unit_of_measures SET
    measurement_name_short = 'in', measurement_name_long = 'Inch',
    measurement_name_short_ru = 'дюйм', measurement_name_long_ru = 'Дюйм',
    measurement_name_short_uz = 'dyuym', measurement_name_long_uz = 'Dyuym',
    name = 'ru: Дюйм' || E'\nen: Inch' || E'\nuz: Dyuym',
    unit_type = 'LENGTH', updated_at = NOW(), updated_by = 'migration'
WHERE LOWER(measurement_name_short) = 'in' OR LOWER(code) = 'in';

-- 15. норм.ч (Standard hour - SERVICE)
UPDATE unit_of_measures SET
    measurement_name_short = 'nh', measurement_name_long = 'Standard hour',
    measurement_name_short_ru = 'норм.ч', measurement_name_long_ru = 'Нормо-час',
    measurement_name_short_uz = 'norm-soat', measurement_name_long_uz = 'Norma-soat',
    name = 'ru: Нормо-час' || E'\nen: Standard hour' || E'\nuz: Norma-soat',
    unit_type = 'SERVICE', updated_at = NOW(), updated_by = 'migration'
WHERE LOWER(measurement_name_short) = 'норм.ч' OR LOWER(code) = 'норм.ч';


-- ----------------------------------------------------------------------------
-- 2. INSERT STANDARD UNITS (Prevents duplicates)
-- ----------------------------------------------------------------------------
WITH new_units (
    code,
    name_short_en, name_long_en,
    name_short_ru, name_long_ru,
    name_short_uz, name_long_uz,
    type
) AS (
    VALUES
    -- LENGTH
    ('mm', 'mm', 'Millimeter', 'мм', 'Миллиметр', 'mm', 'Millimetr', 'LENGTH'),
    ('cm', 'cm', 'Centimeter', 'см', 'Сантиметр', 'sm', 'Santimetr', 'LENGTH'),
    ('dm', 'dm', 'Decimeter', 'дм', 'Дециметр', 'dm', 'Detsimetr', 'LENGTH'),
    
    -- WEIGHT
    ('mg', 'mg', 'Milligram', 'мг', 'Миллиграмм', 'mg', 'Milligramm', 'WEIGHT'),
    ('q',  'q',  'Quintal',   'ц',  'Центнер',    's',  'Sentner', 'WEIGHT'),
    
    -- VOLUME
    ('ml', 'ml', 'Milliliter', 'мл', 'Миллилитр', 'ml', 'Millilitr', 'VOLUME'),
    
    -- AREA
    ('mm2', 'mm2', 'Square millimeter', 'мм2', 'Квадратный миллиметр', 'mm2', 'Kvadrat millimetr', 'AREA'),
    ('ha',  'ha',  'Hectare',           'га',  'Гектар',               'ga',  'Gektar', 'AREA'),
    
    -- PACKAGING & QUANTITY
    ('pack',   'pack', 'Pack',   'упак', 'Упаковка', 'o''ram',   'O''ram', 'PACKAGING'),
    ('box',    'box',  'Box',    'кор',  'Коробка',  'quti',     'Quti', 'PACKAGING'),
    ('pair',   'pair', 'Pair',   'пар',  'Пара',     'juft',     'Juft', 'QUANTITY'),
    ('set',    'set',  'Set',    'наб',  'Набор',    'to''plam', 'To''plam', 'QUANTITY'),
    ('doz',    'doz',  'Dozen',  'дюж',  'Дюжина',   'o''n ikki','O''n ikkilik', 'QUANTITY'),
    ('roll',   'roll', 'Roll',   'рул',  'Рулон',    'rul',      'Rulon', 'PACKAGING'),
    ('bottle', 'btl',  'Bottle', 'бут',  'Бутылка',  'but',      'Butilka', 'PACKAGING'),
    ('bag',    'bag',  'Bag',    'меш',  'Мешок',    'qop',      'Qop', 'PACKAGING'),
    
    -- TIME
    ('s',   's',   'Second', 'с',   'Секунда', 's',    'Soniya', 'TIME'),
    ('min', 'min', 'Minute', 'мин', 'Минута',  'daq',  'Daqiqa', 'TIME'),
    ('h',   'h',   'Hour',   'ч',   'Час',     'soat', 'Soat',   'TIME'),
    ('d',   'd',   'Day',    'дн',  'День',    'kun',  'Kun',    'TIME'),
    ('mo',  'mo',  'Month',  'мес', 'Месяц',   'oy',   'Oy',     'TIME'),
    
    -- ELECTRICAL & ENERGY
    ('W',  'W',  'Watt',     'Вт',  'Ватт',     'Vatt', 'Vatt', 'ELECTRICAL_POWER'),
    ('kW', 'kW', 'Kilowatt', 'кВт', 'Киловатт', 'kVt',  'Kilovatt', 'ELECTRICAL_POWER'),
    ('V',  'V',  'Volt',     'В',   'Вольт',    'V',    'Volt', 'ELECTRICAL_VOLTAGE'),
    ('A',  'A',  'Ampere',   'А',   'Ампер',    'A',    'Amper', 'ELECTRICAL_CURRENT'),
    
    -- TEMPERATURE & OTHER
    ('C', '°C', 'Celsius', '°C', 'Градус Цельсия', '°C', 'Selsiy', 'TEMPERATURE'),
    ('%', '%',  'Percent', '%',  'Процент',        '%',  'Foiz',   'PERCENTAGE')
)
INSERT INTO unit_of_measures (
    id, tenant_id, company_id, 
    code, name, status, 
    measurement_name_short, measurement_name_long,
    measurement_name_short_ru, measurement_name_long_ru,
    measurement_name_short_uz, measurement_name_long_uz,
    unit_type, created_at, updated_at, created_by, version, is_delete
)
SELECT 
    gen_random_uuid(), 'default', '00000000-0000-0000-0000-000000000001'::uuid,
    nu.code, 
    'ru: ' || nu.name_long_ru || E'\nen: ' || nu.name_long_en || E'\nuz: ' || nu.name_long_uz,
    'ACTIVE',
    nu.name_short_en, nu.name_long_en,
    nu.name_short_ru, nu.name_long_ru,
    nu.name_short_uz, nu.name_long_uz,
    nu.type,
    NOW(), NOW(), 'migration', 0, false
FROM new_units nu
WHERE NOT EXISTS (
    SELECT 1 FROM unit_of_measures uom
    WHERE LOWER(uom.code) = LOWER(nu.code)
       OR LOWER(uom.measurement_name_short) = LOWER(nu.name_short_en)
       OR LOWER(uom.measurement_name_short_ru) = LOWER(nu.name_short_ru)
);
