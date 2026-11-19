-- Purchase order item units allow 80 characters; the immutable receipt snapshot must preserve them.
ALTER TABLE goods_receipt_lines ALTER COLUMN uom_code TYPE varchar(80);
