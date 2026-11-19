ALTER TABLE one_c_warehouse_history_runs DROP CONSTRAINT one_c_warehouse_history_runs_state_check;
ALTER TABLE one_c_warehouse_history_runs ADD CONSTRAINT one_c_warehouse_history_runs_state_check
 CHECK(state IN('PENDING','LOADING','COMPLETE','FAILED','CANCELLED'));
ALTER TABLE one_c_warehouse_history_runs
 ADD COLUMN retry_generation bigint NOT NULL DEFAULT 0 CHECK(retry_generation>=0),
 ADD COLUMN reported_attempts integer NOT NULL DEFAULT 0 CHECK(reported_attempts>=0),
 ADD COLUMN last_error text,
 ADD COLUMN failed_at timestamp;
