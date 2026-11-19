-- Keep visible approval steps consecutive and move soft-deleted rows out of the active order range.
ALTER TABLE public.approval_flow_steps DROP CONSTRAINT uk_approval_flow_steps_order;

WITH renumbered AS (
    SELECT id,
           CASE WHEN is_delete THEN
               -ROW_NUMBER() OVER (PARTITION BY approval_flow_id, is_delete ORDER BY step_order, id)
           ELSE
               ROW_NUMBER() OVER (PARTITION BY approval_flow_id, is_delete ORDER BY step_order, id)
           END AS new_order
    FROM public.approval_flow_steps
)
UPDATE public.approval_flow_steps AS step
SET step_order = renumbered.new_order
FROM renumbered
WHERE step.id = renumbered.id AND step.step_order <> renumbered.new_order;

ALTER TABLE public.approval_flow_steps
    ADD CONSTRAINT uk_approval_flow_steps_order UNIQUE (approval_flow_id, step_order);
