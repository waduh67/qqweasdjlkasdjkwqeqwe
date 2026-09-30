DO $$ DECLARE definition text; previous text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_bng_fulfillment_handoff(uuid,uuid)'::regprocedure);
    previous:='IF cardinality(receipt.action_ids)<>(SELECT count(*) FROM bng_action WHERE tenant_id=$1 AND fulfillment_approval_id=$2 AND fulfillment_xid=receipt.created_xid) THEN';
    IF position(previous IN definition)=0 THEN RAISE EXCEPTION 'expected BNG action count validation missing'; END IF;
    EXECUTE replace(definition,previous,'IF ARRAY(SELECT listed_id FROM unnest(receipt.action_ids) listed_id ORDER BY listed_id) IS DISTINCT FROM
        ARRAY(SELECT id FROM bng_action WHERE tenant_id=$1 AND fulfillment_approval_id=$2 AND fulfillment_xid=receipt.created_xid ORDER BY id) THEN');
END $$;
