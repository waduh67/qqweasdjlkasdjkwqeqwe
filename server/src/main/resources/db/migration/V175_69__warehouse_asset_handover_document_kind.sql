DO $$ DECLARE item record;
BEGIN
    FOR item IN SELECT conname,pg_get_constraintdef(oid) definition FROM pg_constraint
        WHERE conrelid='inventory_document'::regclass AND conname IN ('inventory_document_kind_check','inventory_document_check2') LOOP
        EXECUTE format('ALTER TABLE inventory_document DROP CONSTRAINT %I',item.conname);
        EXECUTE format('ALTER TABLE inventory_document ADD CONSTRAINT %I CHECK ((%s) OR (kind=''ASSET_HANDOVER'' AND state IN (''DRAFT'',''POSTED'')))',
            item.conname,substring(item.definition FROM 8 FOR length(item.definition)-8));
    END LOOP;
END $$;
