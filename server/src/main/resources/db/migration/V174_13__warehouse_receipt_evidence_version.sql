ALTER TABLE inventory_receipt_intake
    ADD COLUMN content_revision bigint NOT NULL DEFAULT 0 CHECK (content_revision>=0),
    ADD COLUMN content_hash varchar(64);

ALTER TABLE inventory_receipt_intake DISABLE TRIGGER warehouse_receipt_intake_guard;
UPDATE inventory_receipt_intake SET content_hash=encode(sha256(convert_to(snapshot,'UTF8')),'hex');
ALTER TABLE inventory_receipt_intake ENABLE TRIGGER warehouse_receipt_intake_guard;
ALTER TABLE inventory_receipt_intake ALTER COLUMN content_hash SET NOT NULL,
    ADD CHECK (content_hash ~ '^[0-9a-f]{64}$');

CREATE FUNCTION warehouse_receipt_content_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.content_hash:=encode(sha256(convert_to(NEW.snapshot,'UTF8')),'hex');
    IF TG_OP='INSERT' THEN NEW.content_revision:=0;
    ELSIF NEW.snapshot IS DISTINCT FROM OLD.snapshot THEN NEW.content_revision:=OLD.content_revision+1;
    ELSE NEW.content_revision:=OLD.content_revision;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_receipt_content_revision BEFORE INSERT OR UPDATE ON inventory_receipt_intake
    FOR EACH ROW EXECUTE FUNCTION warehouse_receipt_content_revision();

ALTER TABLE inventory_receipt_evidence
    ADD COLUMN intake_content_revision bigint,
    ADD COLUMN intake_hash varchar(64),
    ADD CONSTRAINT warehouse_receipt_evidence_version_complete CHECK (
        (intake_content_revision IS NULL AND intake_hash IS NULL) OR
        (intake_content_revision IS NOT NULL AND intake_content_revision>=0 AND intake_hash IS NOT NULL AND intake_hash ~ '^[0-9a-f]{64}$'));

CREATE FUNCTION warehouse_receipt_evidence_version_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT FROM inventory_receipt_intake WHERE tenant_id=NEW.tenant_id AND id=NEW.document_id
        AND content_revision=NEW.intake_content_revision AND content_hash=NEW.intake_hash) THEN
        RAISE EXCEPTION 'evidence requires current intake binding' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_receipt_evidence_version BEFORE INSERT ON inventory_receipt_evidence
    FOR EACH ROW EXECUTE FUNCTION warehouse_receipt_evidence_version_guard();

CREATE FUNCTION warehouse_receipt_disposition_evidence_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT FROM inventory_receipt_evidence evidence
        JOIN inventory_receipt_intake intake ON intake.tenant_id=evidence.tenant_id AND intake.id=evidence.document_id
        JOIN inventory_document_line line ON line.tenant_id=intake.tenant_id AND line.document_id=intake.id
        JOIN inventory_inspection inspection ON inspection.tenant_id=line.tenant_id AND inspection.document_line_id=line.id
        WHERE evidence.tenant_id=NEW.tenant_id AND evidence.id=NEW.evidence_id AND inspection.id=NEW.inspection_id
          AND evidence.intake_content_revision=intake.content_revision AND evidence.intake_hash=intake.content_hash) THEN
        RAISE EXCEPTION 'inspection evidence has superseded or unbound intake' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_receipt_disposition_evidence_version BEFORE INSERT ON inventory_receipt_disposition
    FOR EACH ROW EXECUTE FUNCTION warehouse_receipt_disposition_evidence_version();
