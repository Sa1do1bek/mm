package com.enterprise.mm.repository;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.CaseFilter;
import com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.CaseRow;
import com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.OriginType;
import com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.ManagementBucket;
import com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.WorkActRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ServiceCaseWorkspaceQueryRepositoryImpl implements ServiceCaseWorkspaceQueryRepository {
    private static final Set<String> FAILED_INTEGRATION_STATUSES = Set.of("FAILED", "RETRY", "DEAD", "CANCELLED");
    private static final Set<String> QUEUES = Set.of(
        "ALL", "EXECUTION", "ACCEPTANCE", "REWORK", "PAYMENT", "INTEGRATION", "COMPLETED", "HISTORY"
    );

    private static final String COLUMNS = """
        c.case_id, c.origin_type, c.origin_id, c.request_id, c.service_order_id,
        c.purchase_order_id, c.purchase_order_item_id, c.latest_act_id,
        c.code, c.title, c.status, c.phase,
        c.supplier_id, c.supplier_name, c.contract_id, c.contract_code,
        c.responsible_id, c.responsible_name, c.department_id,
        c.due_date, c.sla_state, c.is_overdue, c.progress_percent, c.acceptance_mode,
        c.ordered_quantity, c.accepted_quantity, c.rejected_quantity, c.remaining_quantity, c.uom_code,
        c.ordered_amount, c.accepted_amount, c.rejected_amount, c.remaining_amount, c.currency_code,
        c.latest_act_status, c.open_claim_count, c.blocker_count, c.next_action,
        c.row_version, c.created_at, c.completed_at
        """;

    /*
     * The union is the canonical projection. Every origin supplies the same
     * business columns and queue flags, after which one shared outer predicate
     * is used for both count and list queries.
     */
    private static final String CASES_CTE = """
        WITH cases AS (
            SELECT
                r.id AS case_id,
                'SERVICE_REQUEST' AS origin_type,
                r.id AS origin_id,
                r.id AS request_id,
                CAST(NULL AS UUID) AS service_order_id,
                r.purchase_order_id AS purchase_order_id,
                CAST(NULL AS UUID) AS purchase_order_item_id,
                (SELECT a.id FROM service_acceptance_acts a
                    WHERE a.tenant_id = r.tenant_id AND a.company_id = r.company_id
                      AND a.service_request_id = r.id AND a.is_delete = false
                    ORDER BY a.created_at DESC, a.id DESC FETCH FIRST 1 ROW ONLY) AS latest_act_id,
                COALESCE(NULLIF(r.code, ''), NULLIF(r.request_number, ''), CAST(r.id AS VARCHAR)) AS code,
                COALESCE(NULLIF(r.title, ''), NULLIF(r.name, ''), NULLIF(r.description, ''), 'Service request') AS title,
                r.status AS status,
                CASE
                    WHEN r.status IN ('CLOSED', 'CANCELLED') THEN 'COMPLETED'
                    WHEN r.status IN ('SENT_TO_PAYMENT', 'PAID') THEN 'PAYMENT'
                    WHEN r.status IN ('WAITING_ACCEPTANCE', 'PARTIALLY_ACCEPTED', 'ACCEPTED', 'ACT_PENDING') THEN 'ACCEPTANCE'
                    WHEN r.status IN ('RETURNED', 'REJECTED') THEN 'REWORK'
                    ELSE 'EXECUTION'
                END AS phase,
                r.supplier_id AS supplier_id,
                COALESCE(NULLIF(s.short_name, ''), NULLIF(s.legal_name, ''), NULLIF(s.name, ''), '—') AS supplier_name,
                r.contract_id AS contract_id,
                COALESCE(NULLIF(ct.contract_number, ''), NULLIF(ct.code, '')) AS contract_code,
                r.responsible_user_id AS responsible_id,
                COALESCE(NULLIF(u.full_name, ''), NULLIF(u.username, ''), NULLIF(u.name, ''), '—') AS responsible_name,
                r.department_id AS department_id,
                r.planned_end_date AS due_date,
                CASE
                    WHEN r.planned_end_date < CURRENT_DATE AND r.status NOT IN ('CLOSED', 'CANCELLED') THEN 'OVERDUE'
                    WHEN r.planned_end_date IS NULL THEN 'NO_DUE_DATE'
                    ELSE 'ON_TRACK'
                END AS sla_state,
                CASE WHEN r.planned_end_date < CURRENT_DATE AND r.status NOT IN ('CLOSED', 'CANCELLED') THEN true ELSE false END AS is_overdue,
                COALESCE((SELECT MAX(e.progress_percent) FROM service_executions e
                    WHERE e.tenant_id = r.tenant_id AND e.company_id = r.company_id
                      AND e.service_request_id = r.id AND e.is_delete = false), 0) AS progress_percent,
                COALESCE(NULLIF(r.acceptance_mode, ''), 'AMOUNT') AS acceptance_mode,
                r.ordered_quantity AS ordered_quantity,
                COALESCE(r.accepted_quantity, 0) AS accepted_quantity,
                COALESCE(r.rejected_quantity, 0) AS rejected_quantity,
                CASE WHEN COALESCE(NULLIF(r.acceptance_mode, ''), 'AMOUNT') = 'QUANTITY'
                    THEN GREATEST(COALESCE(r.ordered_quantity, 0) - COALESCE(r.accepted_quantity, 0) - COALESCE(r.rejected_quantity, 0), 0)
                    ELSE CAST(NULL AS DECIMAL(19,4)) END AS remaining_quantity,
                r.uom_code AS uom_code,
                COALESCE(r.ordered_amount,
                    CASE WHEN COALESCE(r.approved_amount, 0) > 0 THEN r.approved_amount ELSE COALESCE(r.estimated_amount, 0) END
                ) AS ordered_amount,
                COALESCE(r.accepted_amount, 0) AS accepted_amount,
                COALESCE(r.rejected_amount, 0) AS rejected_amount,
                CASE WHEN COALESCE(NULLIF(r.acceptance_mode, ''), 'AMOUNT') = 'AMOUNT'
                    THEN GREATEST(
                        COALESCE(r.ordered_amount, CASE WHEN COALESCE(r.approved_amount, 0) > 0 THEN r.approved_amount ELSE COALESCE(r.estimated_amount, 0) END)
                        - COALESCE(r.accepted_amount, 0) - COALESCE(r.rejected_amount, 0), 0
                    ) ELSE CAST(NULL AS DECIMAL(19,4)) END AS remaining_amount,
                COALESCE(NULLIF(r.currency_code, ''), NULLIF(ct.currency_code, ''), 'UZS') AS currency_code,
                COALESCE((SELECT SUM(a.amount) FROM service_acceptance_acts a
                    WHERE a.tenant_id = r.tenant_id AND a.company_id = r.company_id
                      AND a.service_request_id = r.id AND a.is_delete = false
                      AND a.status IN ('APPROVED', 'SENT_TO_PAYMENT')), 0) AS payment_amount,
                (SELECT a.status FROM service_acceptance_acts a
                    WHERE a.tenant_id = r.tenant_id AND a.company_id = r.company_id
                      AND a.service_request_id = r.id AND a.is_delete = false
                    ORDER BY a.created_at DESC, a.id DESC FETCH FIRST 1 ROW ONLY) AS latest_act_status,
                (SELECT COUNT(*) FROM service_claims cl
                    WHERE cl.tenant_id = r.tenant_id AND cl.company_id = r.company_id
                      AND cl.service_request_id = r.id AND cl.is_delete = false AND cl.status <> 'CLOSED') AS open_claim_count,
                (CASE WHEN r.planned_end_date < CURRENT_DATE AND r.status NOT IN ('CLOSED', 'CANCELLED') THEN 1 ELSE 0 END)
                    + (SELECT COUNT(*) FROM service_claims cl
                        WHERE cl.tenant_id = r.tenant_id AND cl.company_id = r.company_id
                          AND cl.service_request_id = r.id AND cl.is_delete = false AND cl.status <> 'CLOSED')
                    + (CASE WHEN r.status IN ('PARTIALLY_ACCEPTED', 'RETURNED', 'REJECTED') THEN 1 ELSE 0 END)
                    + (CASE WHEN EXISTS (
                        SELECT 1 FROM integration_events ie
                        WHERE ie.tenant_id = r.tenant_id AND ie.company_id = r.company_id AND ie.is_delete = false
                          AND ie.status IN ('FAILED', 'RETRY', 'DEAD', 'CANCELLED')
                          AND (
                            ie.aggregate_id = r.id
                            OR ie.aggregate_id IN (SELECT so.id FROM service_orders so WHERE so.service_request_id = r.id AND so.is_delete = false)
                            OR ie.aggregate_id IN (SELECT e.id FROM service_executions e WHERE e.service_request_id = r.id AND e.is_delete = false)
                            OR ie.aggregate_id IN (SELECT a.id FROM service_acceptance_acts a WHERE a.service_request_id = r.id AND a.is_delete = false)
                            OR ie.aggregate_id IN (SELECT cl.id FROM service_claims cl WHERE cl.service_request_id = r.id AND cl.is_delete = false)
                          )
                    ) THEN 1 ELSE 0 END) AS blocker_count,
                CASE
                    WHEN EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = r.tenant_id AND cl.company_id = r.company_id AND cl.service_request_id = r.id AND cl.is_delete = false AND cl.status <> 'CLOSED') THEN 'RESOLVE_CLAIMS'
                    WHEN r.status IN ('PARTIALLY_ACCEPTED', 'RETURNED', 'REJECTED') THEN 'RESOLVE_REWORK'
                    WHEN r.status IN ('DRAFT', 'RETURNED') THEN 'SUBMIT_REQUEST'
                    WHEN r.status = 'APPROVAL_PENDING' THEN 'COMPLETE_REQUEST_APPROVAL'
                    WHEN r.status IN ('APPROVED', 'WAITING_EXECUTION', 'IN_PROGRESS') THEN 'UPDATE_EXECUTION'
                    WHEN r.status = 'WAITING_ACCEPTANCE' THEN 'RECORD_PARTIAL_ACCEPTANCE'
                    WHEN r.status IN ('ACCEPTED', 'ACT_PENDING') THEN 'CREATE_OR_REVIEW_ACT'
                    WHEN r.status = 'SENT_TO_PAYMENT' THEN 'CONFIRM_PAYMENT'
                    WHEN r.status = 'PAID' THEN 'CLOSE_REQUEST'
                    ELSE 'NONE'
                END AS next_action,
                r.version AS row_version,
                r.created_at AS created_at,
                CASE WHEN r.status IN ('CLOSED', 'CANCELLED') THEN COALESCE(r.updated_at, r.created_at) ELSE CAST(NULL AS TIMESTAMP) END AS completed_at,
                CASE WHEN r.status IN ('CLOSED', 'CANCELLED') THEN true ELSE false END AS is_completed,
                CASE WHEN r.status IN ('APPROVED', 'WAITING_EXECUTION', 'IN_PROGRESS')
                    OR EXISTS (SELECT 1 FROM service_executions e WHERE e.tenant_id = r.tenant_id AND e.company_id = r.company_id AND e.service_request_id = r.id AND e.is_delete = false AND e.status IN ('NOT_STARTED', 'IN_PROGRESS', 'PAUSED', 'COMPLETED_BY_CONTRACTOR', 'UNDER_REVIEW'))
                    THEN true ELSE false END AS queue_execution,
                CASE WHEN r.status IN ('WAITING_ACCEPTANCE', 'PARTIALLY_ACCEPTED', 'ACCEPTED', 'ACT_PENDING')
                    OR EXISTS (SELECT 1 FROM service_acceptance_acts a WHERE a.tenant_id = r.tenant_id AND a.company_id = r.company_id AND a.service_request_id = r.id AND a.is_delete = false AND a.status IN ('DRAFT', 'SUBMITTED', 'TECHNICAL_REVIEW', 'DOCUMENT_REVIEW', 'FINANCE_REVIEW'))
                    THEN true ELSE false END AS queue_acceptance,
                CASE WHEN r.status IN ('RETURNED', 'REJECTED', 'PARTIALLY_ACCEPTED')
                    OR EXISTS (SELECT 1 FROM service_executions e WHERE e.tenant_id = r.tenant_id AND e.company_id = r.company_id AND e.service_request_id = r.id AND e.is_delete = false AND e.status IN ('REWORK', 'ACCEPTED_WITH_REMARKS', 'REJECTED'))
                    OR EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = r.tenant_id AND cl.company_id = r.company_id AND cl.service_request_id = r.id AND cl.is_delete = false AND cl.status <> 'CLOSED')
                    THEN true ELSE false END AS queue_rework,
                CASE WHEN EXISTS (SELECT 1 FROM service_acceptance_acts a WHERE a.tenant_id = r.tenant_id AND a.company_id = r.company_id AND a.service_request_id = r.id AND a.is_delete = false AND a.status IN ('APPROVED', 'SENT_TO_PAYMENT'))
                    THEN true ELSE false END AS queue_payment,
                CASE WHEN EXISTS (
                    SELECT 1 FROM integration_events ie
                    WHERE ie.tenant_id = r.tenant_id AND ie.company_id = r.company_id AND ie.is_delete = false
                      AND ie.status IN ('FAILED', 'RETRY', 'DEAD', 'CANCELLED')
                      AND (
                        ie.aggregate_id = r.id
                        OR ie.aggregate_id IN (SELECT so.id FROM service_orders so WHERE so.service_request_id = r.id AND so.is_delete = false)
                        OR ie.aggregate_id IN (SELECT e.id FROM service_executions e WHERE e.service_request_id = r.id AND e.is_delete = false)
                        OR ie.aggregate_id IN (SELECT a.id FROM service_acceptance_acts a WHERE a.service_request_id = r.id AND a.is_delete = false)
                        OR ie.aggregate_id IN (SELECT cl.id FROM service_claims cl WHERE cl.service_request_id = r.id AND cl.is_delete = false)
                      )
                ) THEN true ELSE false END AS queue_integration,
                COALESCE(
                    NULLIF(UPPER(REPLACE(REPLACE(TRIM(r.category), '-', '_'), ' ', '_')), ''),
                    'UNCATEGORIZED'
                ) AS category,
                CASE WHEN EXISTS (
                    SELECT 1 FROM service_review_history history
                    WHERE history.tenant_id = r.tenant_id AND history.company_id = r.company_id
                      AND history.service_request_id = r.id AND history.is_delete = false
                      AND history.decision IN ('REJECTED', 'RETURNED')
                ) OR EXISTS (
                    SELECT 1 FROM service_executions execution
                    WHERE execution.tenant_id = r.tenant_id AND execution.company_id = r.company_id
                      AND execution.service_request_id = r.id AND execution.is_delete = false
                      AND execution.status IN ('REWORK', 'REJECTED', 'ACCEPTED_WITH_REMARKS')
                ) THEN true ELSE false END AS has_rework_history
            FROM service_requests r
            LEFT JOIN suppliers s ON s.id = r.supplier_id AND s.tenant_id = r.tenant_id AND s.company_id = r.company_id AND s.is_delete = false
            LEFT JOIN contracts ct ON ct.id = r.contract_id AND ct.tenant_id = r.tenant_id AND ct.company_id = r.company_id AND ct.is_delete = false
            LEFT JOIN users u ON u.id = r.responsible_user_id AND u.tenant_id = r.tenant_id AND u.company_id = r.company_id AND u.is_delete = false
            WHERE r.tenant_id = :tenantId AND r.company_id = :companyId AND r.is_delete = false

            UNION ALL

            SELECT
                so.id AS case_id,
                'PO_SERVICE' AS origin_type,
                so.id AS origin_id,
                CAST(NULL AS UUID) AS request_id,
                so.id AS service_order_id,
                po.id AS purchase_order_id,
                CAST(NULL AS UUID) AS purchase_order_item_id,
                (SELECT a.id FROM service_acceptance_acts a
                    WHERE a.tenant_id = so.tenant_id AND a.company_id = so.company_id
                      AND a.service_order_id = so.id AND a.is_delete = false
                    ORDER BY a.created_at DESC, a.id DESC FETCH FIRST 1 ROW ONLY) AS latest_act_id,
                COALESCE(NULLIF(so.code, ''), NULLIF(po.code, ''), CAST(so.id AS VARCHAR)) AS code,
                COALESCE(NULLIF(so.name, ''), NULLIF(po.name, ''), 'PO service order') AS title,
                CASE
                    WHEN COALESCE(act_stats.current_rejected_count, 0) > 0 THEN 'REJECTED'
                    WHEN COALESCE(act_stats.active_review_count, 0) > 0 THEN 'ACT_PENDING'
                    WHEN COALESCE(act_stats.approved_count, 0) > 0 THEN 'APPROVED'
                    WHEN COALESCE(act_stats.sent_to_payment_count, 0) > 0 THEN 'SENT_TO_PAYMENT'
                    WHEN COALESCE(act_stats.effective_act_count, 0) > 0
                        AND COALESCE(act_stats.paid_count, 0) = COALESCE(act_stats.effective_act_count, 0)
                        AND COALESCE(act_stats.accepted_amount, 0) >= COALESCE(so.total_amount, 0) THEN 'PAID'
                    WHEN COALESCE(act_stats.accepted_amount, 0) > 0 THEN 'PARTIALLY_ACCEPTED'
                    ELSE so.status
                END AS status,
                CASE
                    WHEN COALESCE(act_stats.effective_act_count, 0) > 0
                        AND COALESCE(act_stats.paid_count, 0) = COALESCE(act_stats.effective_act_count, 0)
                        AND COALESCE(act_stats.accepted_amount, 0) >= COALESCE(so.total_amount, 0)
                        AND COALESCE(act_stats.current_rejected_count, 0) = 0
                        AND NOT EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = so.tenant_id AND cl.company_id = so.company_id AND cl.service_order_id = so.id AND cl.is_delete = false AND cl.status <> 'CLOSED')
                        THEN 'COMPLETED'
                    WHEN COALESCE(act_stats.current_rejected_count, 0) > 0
                        OR EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = so.tenant_id AND cl.company_id = so.company_id AND cl.service_order_id = so.id AND cl.is_delete = false AND cl.status <> 'CLOSED')
                        THEN 'REWORK'
                    WHEN COALESCE(act_stats.active_review_count, 0) > 0 THEN 'ACCEPTANCE'
                    WHEN COALESCE(act_stats.approved_count, 0) > 0 OR COALESCE(act_stats.sent_to_payment_count, 0) > 0 THEN 'PAYMENT'
                    WHEN COALESCE(act_stats.total_act_count, 0) > 0 THEN 'ACCEPTANCE'
                    ELSE 'EXECUTION'
                END AS phase,
                so.supplier_id AS supplier_id,
                COALESCE(NULLIF(s.short_name, ''), NULLIF(s.legal_name, ''), NULLIF(s.name, ''), '—') AS supplier_name,
                so.contract_id AS contract_id,
                COALESCE(NULLIF(ct.contract_number, ''), NULLIF(ct.code, '')) AS contract_code,
                u.id AS responsible_id,
                COALESCE(NULLIF(u.full_name, ''), NULLIF(u.username, ''), NULLIF(so.created_by, ''), '—') AS responsible_name,
                u.department_id AS department_id,
                po.expected_delivery_date AS due_date,
                CASE
                    WHEN po.expected_delivery_date < CURRENT_DATE AND NOT (
                        COALESCE(act_stats.effective_act_count, 0) > 0
                            AND COALESCE(act_stats.paid_count, 0) = COALESCE(act_stats.effective_act_count, 0)
                            AND COALESCE(act_stats.accepted_amount, 0) >= COALESCE(so.total_amount, 0)
                            AND COALESCE(act_stats.current_rejected_count, 0) = 0
                            AND NOT EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = so.tenant_id AND cl.company_id = so.company_id AND cl.service_order_id = so.id AND cl.is_delete = false AND cl.status <> 'CLOSED')
                    ) THEN 'OVERDUE'
                    WHEN po.expected_delivery_date IS NULL THEN 'NO_DUE_DATE'
                    ELSE 'ON_TRACK'
                END AS sla_state,
                CASE WHEN po.expected_delivery_date < CURRENT_DATE AND NOT (
                    COALESCE(act_stats.effective_act_count, 0) > 0
                        AND COALESCE(act_stats.paid_count, 0) = COALESCE(act_stats.effective_act_count, 0)
                        AND COALESCE(act_stats.accepted_amount, 0) >= COALESCE(so.total_amount, 0)
                        AND COALESCE(act_stats.current_rejected_count, 0) = 0
                        AND NOT EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = so.tenant_id AND cl.company_id = so.company_id AND cl.service_order_id = so.id AND cl.is_delete = false AND cl.status <> 'CLOSED')
                ) THEN true ELSE false END AS is_overdue,
                CASE WHEN COALESCE(so.total_amount, 0) > 0 THEN LEAST(100, ROUND(100 * COALESCE(act_stats.accepted_amount, 0) / so.total_amount, 2)) ELSE 0 END AS progress_percent,
                'AMOUNT' AS acceptance_mode,
                CAST(NULL AS DECIMAL(19,4)) AS ordered_quantity,
                CAST(NULL AS DECIMAL(19,4)) AS accepted_quantity,
                CAST(NULL AS DECIMAL(19,4)) AS rejected_quantity,
                CAST(NULL AS DECIMAL(19,4)) AS remaining_quantity,
                CAST(NULL AS VARCHAR) AS uom_code,
                COALESCE(so.total_amount, 0) AS ordered_amount,
                COALESCE(act_stats.accepted_amount, 0) AS accepted_amount,
                COALESCE(act_stats.current_rejected_amount, 0) AS rejected_amount,
                GREATEST(COALESCE(so.total_amount, 0) - COALESCE(act_stats.accepted_amount, 0), 0) AS remaining_amount,
                COALESCE(NULLIF(so.currency_code, ''), NULLIF(po.currency_code, ''), NULLIF(ct.currency_code, ''), 'UZS') AS currency_code,
                COALESCE(act_stats.payable_amount, 0) AS payment_amount,
                (SELECT a.status FROM service_acceptance_acts a WHERE a.tenant_id = so.tenant_id AND a.company_id = so.company_id AND a.service_order_id = so.id AND a.is_delete = false ORDER BY a.created_at DESC, a.id DESC FETCH FIRST 1 ROW ONLY) AS latest_act_status,
                (SELECT COUNT(*) FROM service_claims cl
                    WHERE cl.tenant_id = so.tenant_id AND cl.company_id = so.company_id
                      AND cl.service_order_id = so.id AND cl.is_delete = false AND cl.status <> 'CLOSED') AS open_claim_count,
                (CASE WHEN po.expected_delivery_date < CURRENT_DATE AND NOT (
                        COALESCE(act_stats.effective_act_count, 0) > 0
                            AND COALESCE(act_stats.paid_count, 0) = COALESCE(act_stats.effective_act_count, 0)
                            AND COALESCE(act_stats.accepted_amount, 0) >= COALESCE(so.total_amount, 0)
                            AND COALESCE(act_stats.current_rejected_count, 0) = 0
                            AND NOT EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = so.tenant_id AND cl.company_id = so.company_id AND cl.service_order_id = so.id AND cl.is_delete = false AND cl.status <> 'CLOSED')
                    ) THEN 1 ELSE 0 END)
                    + (CASE WHEN COALESCE(act_stats.current_rejected_count, 0) > 0 THEN 1 ELSE 0 END)
                    + (CASE WHEN EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = so.tenant_id AND cl.company_id = so.company_id AND cl.service_order_id = so.id AND cl.is_delete = false AND cl.status <> 'CLOSED') THEN 1 ELSE 0 END)
                    + (CASE WHEN EXISTS (SELECT 1 FROM integration_events ie WHERE ie.tenant_id = so.tenant_id AND ie.company_id = so.company_id AND ie.is_delete = false AND ie.status IN ('FAILED', 'RETRY', 'DEAD', 'CANCELLED') AND (ie.aggregate_id = so.id OR ie.aggregate_id = po.id OR ie.aggregate_id IN (SELECT a.id FROM service_acceptance_acts a WHERE a.service_order_id = so.id AND a.is_delete = false) OR ie.aggregate_id IN (SELECT cl.id FROM service_claims cl WHERE cl.service_order_id = so.id AND cl.is_delete = false))) THEN 1 ELSE 0 END) AS blocker_count,
                CASE
                    WHEN EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = so.tenant_id AND cl.company_id = so.company_id AND cl.service_order_id = so.id AND cl.is_delete = false AND cl.status <> 'CLOSED') THEN 'RESOLVE_CLAIMS'
                    WHEN COALESCE(act_stats.current_rejected_count, 0) > 0 THEN 'RESOLVE_REWORK'
                    WHEN COALESCE(act_stats.effective_act_count, 0) > 0
                            AND COALESCE(act_stats.paid_count, 0) = COALESCE(act_stats.effective_act_count, 0)
                            AND COALESCE(act_stats.accepted_amount, 0) >= COALESCE(so.total_amount, 0) THEN 'NONE'
                    WHEN COALESCE(act_stats.total_act_count, 0) = 0 THEN 'CREATE_ACT'
                    WHEN COALESCE(act_stats.active_review_count, 0) > 0 THEN 'COMPLETE_ACT_REVIEW'
                    WHEN COALESCE(act_stats.approved_count, 0) > 0 THEN 'SEND_TO_PAYMENT'
                    WHEN COALESCE(act_stats.sent_to_payment_count, 0) > 0 THEN 'CONFIRM_PAYMENT'
                    WHEN COALESCE(act_stats.accepted_amount, 0) < COALESCE(so.total_amount, 0) THEN 'CREATE_ACT'
                    ELSE 'NONE'
                END AS next_action,
                so.version AS row_version,
                so.created_at AS created_at,
                CASE WHEN COALESCE(act_stats.effective_act_count, 0) > 0
                        AND COALESCE(act_stats.paid_count, 0) = COALESCE(act_stats.effective_act_count, 0)
                        AND COALESCE(act_stats.accepted_amount, 0) >= COALESCE(so.total_amount, 0)
                        AND COALESCE(act_stats.current_rejected_count, 0) = 0
                        AND NOT EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = so.tenant_id AND cl.company_id = so.company_id AND cl.service_order_id = so.id AND cl.is_delete = false AND cl.status <> 'CLOSED')
                    THEN COALESCE(act_stats.effective_updated_at, so.updated_at, so.created_at) ELSE CAST(NULL AS TIMESTAMP) END AS completed_at,
                CASE WHEN COALESCE(act_stats.effective_act_count, 0) > 0
                        AND COALESCE(act_stats.paid_count, 0) = COALESCE(act_stats.effective_act_count, 0)
                        AND COALESCE(act_stats.accepted_amount, 0) >= COALESCE(so.total_amount, 0)
                        AND COALESCE(act_stats.current_rejected_count, 0) = 0
                        AND NOT EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = so.tenant_id AND cl.company_id = so.company_id AND cl.service_order_id = so.id AND cl.is_delete = false AND cl.status <> 'CLOSED')
                    THEN true ELSE false END AS is_completed,
                CASE WHEN COALESCE(act_stats.total_act_count, 0) = 0 THEN true ELSE false END AS queue_execution,
                CASE WHEN COALESCE(act_stats.active_review_count, 0) > 0
                    OR (COALESCE(act_stats.total_act_count, 0) > 0
                        AND COALESCE(act_stats.accepted_amount, 0) < COALESCE(so.total_amount, 0)
                        AND COALESCE(act_stats.current_rejected_count, 0) = 0)
                    THEN true ELSE false END AS queue_acceptance,
                CASE WHEN COALESCE(act_stats.current_rejected_count, 0) > 0 OR EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = so.tenant_id AND cl.company_id = so.company_id AND cl.service_order_id = so.id AND cl.is_delete = false AND cl.status <> 'CLOSED') THEN true ELSE false END AS queue_rework,
                CASE WHEN COALESCE(act_stats.approved_count, 0) > 0 OR COALESCE(act_stats.sent_to_payment_count, 0) > 0 THEN true ELSE false END AS queue_payment,
                CASE WHEN EXISTS (SELECT 1 FROM integration_events ie WHERE ie.tenant_id = so.tenant_id AND ie.company_id = so.company_id AND ie.is_delete = false AND ie.status IN ('FAILED', 'RETRY', 'DEAD', 'CANCELLED') AND (ie.aggregate_id = so.id OR ie.aggregate_id = po.id OR ie.aggregate_id IN (SELECT a.id FROM service_acceptance_acts a WHERE a.service_order_id = so.id AND a.is_delete = false) OR ie.aggregate_id IN (SELECT cl.id FROM service_claims cl WHERE cl.service_order_id = so.id AND cl.is_delete = false))) THEN true ELSE false END AS queue_integration,
                COALESCE(
                    NULLIF(UPPER(REPLACE(REPLACE(TRIM(so.service_type), '-', '_'), ' ', '_')), ''),
                    'SERVICE'
                ) AS category,
                CASE WHEN EXISTS (
                    SELECT 1 FROM service_acceptance_acts rejected_act
                    LEFT JOIN service_review_history history ON history.service_act_id = rejected_act.id
                        AND history.is_delete = false AND history.decision IN ('REJECTED', 'RETURNED')
                    WHERE rejected_act.tenant_id = so.tenant_id AND rejected_act.company_id = so.company_id
                      AND rejected_act.service_order_id = so.id AND rejected_act.is_delete = false
                      AND (rejected_act.status = 'REJECTED' OR history.id IS NOT NULL)
                ) THEN true ELSE false END AS has_rework_history
            FROM service_orders so
            JOIN purchase_orders po ON po.id = so.purchase_order_id AND po.tenant_id = so.tenant_id AND po.company_id = so.company_id AND po.is_delete = false AND po.request_type = 'SERVICE'
            LEFT JOIN suppliers s ON s.id = so.supplier_id AND s.tenant_id = so.tenant_id AND s.company_id = so.company_id AND s.is_delete = false
            LEFT JOIN contracts ct ON ct.id = so.contract_id AND ct.tenant_id = so.tenant_id AND ct.company_id = so.company_id AND ct.is_delete = false
            LEFT JOIN users u ON u.tenant_id = so.tenant_id AND u.company_id = so.company_id AND u.is_delete = false AND u.username = so.created_by
            LEFT JOIN (
                SELECT
                    act.tenant_id,
                    act.company_id,
                    act.service_order_id,
                    COUNT(*) AS total_act_count,
                    SUM(CASE WHEN act.status NOT IN ('REJECTED', 'CANCELLED') THEN 1 ELSE 0 END) AS effective_act_count,
                    SUM(CASE WHEN act.status IN ('DRAFT', 'RETURNED', 'SUBMITTED', 'TECHNICAL_REVIEW', 'DOCUMENT_REVIEW', 'FINANCE_REVIEW') THEN 1 ELSE 0 END) AS active_review_count,
                    SUM(CASE WHEN act.status = 'APPROVED' THEN 1 ELSE 0 END) AS approved_count,
                    SUM(CASE WHEN act.status = 'SENT_TO_PAYMENT' THEN 1 ELSE 0 END) AS sent_to_payment_count,
                    SUM(CASE WHEN act.status = 'PAID' THEN 1 ELSE 0 END) AS paid_count,
                    COALESCE(SUM(CASE WHEN act.status IN ('APPROVED', 'SENT_TO_PAYMENT') THEN act.amount ELSE 0 END), 0) AS payable_amount,
                    COALESCE(SUM(CASE WHEN act.status IN ('APPROVED', 'SENT_TO_PAYMENT', 'PAID') THEN act.amount ELSE 0 END), 0) AS accepted_amount,
                    COALESCE(SUM(CASE WHEN act.status = 'REJECTED' AND NOT EXISTS (
                        SELECT 1 FROM service_acceptance_acts replacement
                        WHERE replacement.tenant_id = act.tenant_id AND replacement.company_id = act.company_id
                          AND replacement.service_order_id = act.service_order_id AND replacement.is_delete = false
                          AND replacement.status NOT IN ('REJECTED', 'CANCELLED')
                          AND COALESCE(NULLIF(UPPER(TRIM(replacement.stage)), ''), 'UNSPECIFIED')
                              = COALESCE(NULLIF(UPPER(TRIM(act.stage)), ''), 'UNSPECIFIED')
                          AND COALESCE(replacement.period_start, DATE '1900-01-01') = COALESCE(act.period_start, DATE '1900-01-01')
                          AND COALESCE(replacement.period_end, DATE '1900-01-01') = COALESCE(act.period_end, DATE '1900-01-01')
                          AND (replacement.created_at > act.created_at OR (
                              replacement.created_at = act.created_at
                              AND CAST(replacement.id AS VARCHAR) > CAST(act.id AS VARCHAR)
                          ))
                    ) THEN act.amount ELSE 0 END), 0) AS current_rejected_amount,
                    SUM(CASE WHEN act.status = 'REJECTED' AND NOT EXISTS (
                        SELECT 1 FROM service_acceptance_acts replacement
                        WHERE replacement.tenant_id = act.tenant_id AND replacement.company_id = act.company_id
                          AND replacement.service_order_id = act.service_order_id AND replacement.is_delete = false
                          AND replacement.status NOT IN ('REJECTED', 'CANCELLED')
                          AND COALESCE(NULLIF(UPPER(TRIM(replacement.stage)), ''), 'UNSPECIFIED')
                              = COALESCE(NULLIF(UPPER(TRIM(act.stage)), ''), 'UNSPECIFIED')
                          AND COALESCE(replacement.period_start, DATE '1900-01-01') = COALESCE(act.period_start, DATE '1900-01-01')
                          AND COALESCE(replacement.period_end, DATE '1900-01-01') = COALESCE(act.period_end, DATE '1900-01-01')
                          AND (replacement.created_at > act.created_at OR (
                              replacement.created_at = act.created_at
                              AND CAST(replacement.id AS VARCHAR) > CAST(act.id AS VARCHAR)
                          ))
                    ) THEN 1 ELSE 0 END) AS current_rejected_count,
                    MAX(CASE WHEN act.status NOT IN ('REJECTED', 'CANCELLED') THEN act.updated_at END) AS effective_updated_at
                FROM service_acceptance_acts act
                WHERE act.tenant_id = :tenantId AND act.company_id = :companyId
                  AND act.is_delete = false AND act.service_order_id IS NOT NULL
                GROUP BY act.tenant_id, act.company_id, act.service_order_id
            ) act_stats ON act_stats.tenant_id = so.tenant_id
              AND act_stats.company_id = so.company_id AND act_stats.service_order_id = so.id
            WHERE so.tenant_id = :tenantId AND so.company_id = :companyId AND so.is_delete = false AND so.service_request_id IS NULL
            """.concat("""
            UNION ALL

            SELECT
                poi.id AS case_id,
                'PO_WORKS' AS origin_type,
                poi.id AS origin_id,
                CAST(NULL AS UUID) AS request_id,
                CAST(NULL AS UUID) AS service_order_id,
                po.id AS purchase_order_id,
                poi.id AS purchase_order_item_id,
                (SELECT wa.id FROM work_completion_acts wa WHERE wa.tenant_id = poi.tenant_id AND wa.company_id = poi.company_id AND wa.purchase_order_item_id = poi.id AND wa.is_delete = false ORDER BY wa.created_at DESC, wa.id DESC FETCH FIRST 1 ROW ONLY) AS latest_act_id,
                COALESCE(NULLIF(poi.code, ''), NULLIF(po.code, ''), CAST(poi.id AS VARCHAR)) AS code,
                COALESCE(NULLIF(poi.service_description, ''), NULLIF(poi.name, ''), NULLIF(po.name, ''), 'PO works line') AS title,
                CASE
                    WHEN COALESCE(act_stats.current_rejected_count, 0) > 0 THEN 'REJECTED'
                    WHEN COALESCE(act_stats.active_review_count, 0) > 0 THEN 'APPROVAL_PENDING'
                    WHEN COALESCE(act_stats.approved_count, 0) > 0 THEN 'APPROVED'
                    WHEN COALESCE(act_stats.sent_to_payment_count, 0) > 0 THEN 'SENT_TO_PAYMENT'
                    WHEN COALESCE(act_stats.effective_act_count, 0) > 0
                        AND COALESCE(act_stats.paid_count, 0) = COALESCE(act_stats.effective_act_count, 0)
                        AND COALESCE(act_stats.accepted_quantity, 0) >= COALESCE(poi.quantity, 0)
                        AND COALESCE(act_stats.accepted_amount, 0) >= COALESCE(poi.quantity, 0) * COALESCE(poi.unit_price, 0) THEN 'PAID'
                    WHEN COALESCE(act_stats.accepted_quantity, 0) > 0 THEN 'PARTIALLY_ACCEPTED'
                    ELSE po.status
                END AS status,
                CASE
                    WHEN COALESCE(act_stats.effective_act_count, 0) > 0
                        AND COALESCE(act_stats.paid_count, 0) = COALESCE(act_stats.effective_act_count, 0)
                        AND COALESCE(act_stats.accepted_quantity, 0) >= COALESCE(poi.quantity, 0)
                        AND COALESCE(act_stats.accepted_amount, 0) >= COALESCE(poi.quantity, 0) * COALESCE(poi.unit_price, 0)
                        AND COALESCE(act_stats.current_rejected_count, 0) = 0
                        AND NOT EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = poi.tenant_id AND cl.company_id = poi.company_id AND cl.purchase_order_item_id = poi.id AND cl.is_delete = false AND cl.status <> 'CLOSED')
                        THEN 'COMPLETED'
                    WHEN COALESCE(act_stats.current_rejected_count, 0) > 0
                        OR EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = poi.tenant_id AND cl.company_id = poi.company_id AND cl.purchase_order_item_id = poi.id AND cl.is_delete = false AND cl.status <> 'CLOSED')
                        THEN 'REWORK'
                    WHEN COALESCE(act_stats.active_review_count, 0) > 0 THEN 'ACCEPTANCE'
                    WHEN COALESCE(act_stats.approved_count, 0) > 0 OR COALESCE(act_stats.sent_to_payment_count, 0) > 0 THEN 'PAYMENT'
                    WHEN COALESCE(act_stats.total_act_count, 0) > 0 THEN 'ACCEPTANCE'
                    ELSE 'EXECUTION'
                END AS phase,
                po.supplier_id AS supplier_id,
                COALESCE(NULLIF(s.short_name, ''), NULLIF(s.legal_name, ''), NULLIF(s.name, ''), '—') AS supplier_name,
                po.contract_id AS contract_id,
                COALESCE(NULLIF(ct.contract_number, ''), NULLIF(ct.code, '')) AS contract_code,
                u.id AS responsible_id,
                COALESCE(NULLIF(u.full_name, ''), NULLIF(u.username, ''), NULLIF(po.created_by, ''), '—') AS responsible_name,
                u.department_id AS department_id,
                po.expected_delivery_date AS due_date,
                CASE
                    WHEN po.expected_delivery_date < CURRENT_DATE AND NOT (
                        COALESCE(act_stats.effective_act_count, 0) > 0
                            AND COALESCE(act_stats.paid_count, 0) = COALESCE(act_stats.effective_act_count, 0)
                            AND COALESCE(act_stats.accepted_quantity, 0) >= COALESCE(poi.quantity, 0)
                            AND COALESCE(act_stats.accepted_amount, 0) >= COALESCE(poi.quantity, 0) * COALESCE(poi.unit_price, 0)
                            AND COALESCE(act_stats.current_rejected_count, 0) = 0
                            AND NOT EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = poi.tenant_id AND cl.company_id = poi.company_id AND cl.purchase_order_item_id = poi.id AND cl.is_delete = false AND cl.status <> 'CLOSED')
                    ) THEN 'OVERDUE'
                    WHEN po.expected_delivery_date IS NULL THEN 'NO_DUE_DATE'
                    ELSE 'ON_TRACK'
                END AS sla_state,
                CASE WHEN po.expected_delivery_date < CURRENT_DATE AND NOT (
                    COALESCE(act_stats.effective_act_count, 0) > 0
                        AND COALESCE(act_stats.paid_count, 0) = COALESCE(act_stats.effective_act_count, 0)
                        AND COALESCE(act_stats.accepted_quantity, 0) >= COALESCE(poi.quantity, 0)
                        AND COALESCE(act_stats.accepted_amount, 0) >= COALESCE(poi.quantity, 0) * COALESCE(poi.unit_price, 0)
                        AND COALESCE(act_stats.current_rejected_count, 0) = 0
                        AND NOT EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = poi.tenant_id AND cl.company_id = poi.company_id AND cl.purchase_order_item_id = poi.id AND cl.is_delete = false AND cl.status <> 'CLOSED')
                ) THEN true ELSE false END AS is_overdue,
                CASE WHEN COALESCE(poi.quantity, 0) > 0 THEN LEAST(100, ROUND(100 * COALESCE(act_stats.accepted_quantity, 0) / poi.quantity, 2)) ELSE 0 END AS progress_percent,
                'QUANTITY' AS acceptance_mode,
                COALESCE(poi.quantity, 0) AS ordered_quantity,
                COALESCE(act_stats.accepted_quantity, 0) AS accepted_quantity,
                COALESCE(act_stats.current_rejected_quantity, 0) AS rejected_quantity,
                GREATEST(COALESCE(poi.quantity, 0) - COALESCE(act_stats.accepted_quantity, 0), 0) AS remaining_quantity,
                poi.unit AS uom_code,
                COALESCE(poi.quantity, 0) * COALESCE(poi.unit_price, 0) AS ordered_amount,
                COALESCE(act_stats.accepted_amount, 0) AS accepted_amount,
                COALESCE(act_stats.current_rejected_amount, 0) AS rejected_amount,
                GREATEST(COALESCE(poi.quantity, 0) * COALESCE(poi.unit_price, 0) - COALESCE(act_stats.accepted_amount, 0), 0) AS remaining_amount,
                COALESCE(NULLIF(po.currency_code, ''), NULLIF(ct.currency_code, ''), 'UZS') AS currency_code,
                COALESCE(act_stats.payable_amount, 0) AS payment_amount,
                (SELECT wa.status FROM work_completion_acts wa WHERE wa.tenant_id = poi.tenant_id AND wa.company_id = poi.company_id AND wa.purchase_order_item_id = poi.id AND wa.is_delete = false ORDER BY wa.created_at DESC, wa.id DESC FETCH FIRST 1 ROW ONLY) AS latest_act_status,
                (SELECT COUNT(*) FROM service_claims cl
                    WHERE cl.tenant_id = poi.tenant_id AND cl.company_id = poi.company_id
                      AND cl.purchase_order_item_id = poi.id AND cl.is_delete = false AND cl.status <> 'CLOSED') AS open_claim_count,
                (CASE WHEN po.expected_delivery_date < CURRENT_DATE AND NOT (
                        COALESCE(act_stats.effective_act_count, 0) > 0
                            AND COALESCE(act_stats.paid_count, 0) = COALESCE(act_stats.effective_act_count, 0)
                            AND COALESCE(act_stats.accepted_quantity, 0) >= COALESCE(poi.quantity, 0)
                            AND COALESCE(act_stats.accepted_amount, 0) >= COALESCE(poi.quantity, 0) * COALESCE(poi.unit_price, 0)
                            AND COALESCE(act_stats.current_rejected_count, 0) = 0
                            AND NOT EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = poi.tenant_id AND cl.company_id = poi.company_id AND cl.purchase_order_item_id = poi.id AND cl.is_delete = false AND cl.status <> 'CLOSED')
                    ) THEN 1 ELSE 0 END)
                    + (CASE WHEN COALESCE(act_stats.current_rejected_count, 0) > 0 THEN 1 ELSE 0 END)
                    + (CASE WHEN COALESCE(act_stats.accepted_quantity, 0) >= COALESCE(poi.quantity, 0)
                            AND COALESCE(act_stats.accepted_amount, 0) < COALESCE(poi.quantity, 0) * COALESCE(poi.unit_price, 0)
                        THEN 1 ELSE 0 END)
                    + (CASE WHEN EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = poi.tenant_id AND cl.company_id = poi.company_id AND cl.purchase_order_item_id = poi.id AND cl.is_delete = false AND cl.status <> 'CLOSED') THEN 1 ELSE 0 END)
                    + (CASE WHEN EXISTS (SELECT 1 FROM integration_events ie WHERE ie.tenant_id = poi.tenant_id AND ie.company_id = poi.company_id AND ie.is_delete = false AND ie.status IN ('FAILED', 'RETRY', 'DEAD', 'CANCELLED') AND (ie.aggregate_id = po.id OR ie.aggregate_id = poi.id OR ie.aggregate_id IN (SELECT wa.id FROM work_completion_acts wa WHERE wa.purchase_order_item_id = poi.id AND wa.is_delete = false) OR ie.aggregate_id IN (SELECT cl.id FROM service_claims cl WHERE cl.purchase_order_item_id = poi.id AND cl.is_delete = false))) THEN 1 ELSE 0 END) AS blocker_count,
                CASE
                    WHEN EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = poi.tenant_id AND cl.company_id = poi.company_id AND cl.purchase_order_item_id = poi.id AND cl.is_delete = false AND cl.status <> 'CLOSED') THEN 'RESOLVE_CLAIMS'
                    WHEN COALESCE(act_stats.current_rejected_count, 0) > 0 THEN 'RESOLVE_REWORK'
                    WHEN COALESCE(act_stats.effective_act_count, 0) > 0
                            AND COALESCE(act_stats.paid_count, 0) = COALESCE(act_stats.effective_act_count, 0)
                            AND COALESCE(act_stats.accepted_quantity, 0) >= COALESCE(poi.quantity, 0)
                            AND COALESCE(act_stats.accepted_amount, 0) >= COALESCE(poi.quantity, 0) * COALESCE(poi.unit_price, 0) THEN 'NONE'
                    WHEN COALESCE(act_stats.total_act_count, 0) = 0 THEN 'CREATE_WORK_ACT'
                    WHEN COALESCE(act_stats.active_review_count, 0) > 0 THEN 'COMPLETE_ACT_REVIEW'
                    WHEN COALESCE(act_stats.approved_count, 0) > 0 THEN 'SEND_TO_PAYMENT'
                    WHEN COALESCE(act_stats.sent_to_payment_count, 0) > 0 THEN 'CONFIRM_PAYMENT'
                    WHEN COALESCE(act_stats.accepted_quantity, 0) >= COALESCE(poi.quantity, 0)
                        AND COALESCE(act_stats.accepted_amount, 0) < COALESCE(poi.quantity, 0) * COALESCE(poi.unit_price, 0)
                        THEN 'RECONCILE_WORK_VALUE'
                    WHEN COALESCE(act_stats.accepted_quantity, 0) < COALESCE(poi.quantity, 0)
                        OR COALESCE(act_stats.accepted_amount, 0) < COALESCE(poi.quantity, 0) * COALESCE(poi.unit_price, 0)
                        THEN 'CREATE_WORK_ACT'
                    ELSE 'NONE'
                END AS next_action,
                poi.version AS row_version,
                poi.created_at AS created_at,
                CASE WHEN COALESCE(act_stats.effective_act_count, 0) > 0
                        AND COALESCE(act_stats.paid_count, 0) = COALESCE(act_stats.effective_act_count, 0)
                        AND COALESCE(act_stats.accepted_quantity, 0) >= COALESCE(poi.quantity, 0)
                        AND COALESCE(act_stats.accepted_amount, 0) >= COALESCE(poi.quantity, 0) * COALESCE(poi.unit_price, 0)
                        AND COALESCE(act_stats.current_rejected_count, 0) = 0
                        AND NOT EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = poi.tenant_id AND cl.company_id = poi.company_id AND cl.purchase_order_item_id = poi.id AND cl.is_delete = false AND cl.status <> 'CLOSED')
                    THEN COALESCE(act_stats.effective_updated_at, poi.updated_at, poi.created_at) ELSE CAST(NULL AS TIMESTAMP) END AS completed_at,
                CASE WHEN COALESCE(act_stats.effective_act_count, 0) > 0
                        AND COALESCE(act_stats.paid_count, 0) = COALESCE(act_stats.effective_act_count, 0)
                        AND COALESCE(act_stats.accepted_quantity, 0) >= COALESCE(poi.quantity, 0)
                        AND COALESCE(act_stats.accepted_amount, 0) >= COALESCE(poi.quantity, 0) * COALESCE(poi.unit_price, 0)
                        AND COALESCE(act_stats.current_rejected_count, 0) = 0
                        AND NOT EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = poi.tenant_id AND cl.company_id = poi.company_id AND cl.purchase_order_item_id = poi.id AND cl.is_delete = false AND cl.status <> 'CLOSED')
                    THEN true ELSE false END AS is_completed,
                CASE WHEN COALESCE(act_stats.total_act_count, 0) = 0 THEN true ELSE false END AS queue_execution,
                CASE WHEN COALESCE(act_stats.active_review_count, 0) > 0
                    OR (COALESCE(act_stats.total_act_count, 0) > 0
                        AND (COALESCE(act_stats.accepted_quantity, 0) < COALESCE(poi.quantity, 0)
                            OR COALESCE(act_stats.accepted_amount, 0) < COALESCE(poi.quantity, 0) * COALESCE(poi.unit_price, 0))
                        AND COALESCE(act_stats.current_rejected_count, 0) = 0)
                    THEN true ELSE false END AS queue_acceptance,
                CASE WHEN COALESCE(act_stats.current_rejected_count, 0) > 0 OR EXISTS (SELECT 1 FROM service_claims cl WHERE cl.tenant_id = poi.tenant_id AND cl.company_id = poi.company_id AND cl.purchase_order_item_id = poi.id AND cl.is_delete = false AND cl.status <> 'CLOSED') THEN true ELSE false END AS queue_rework,
                CASE WHEN COALESCE(act_stats.approved_count, 0) > 0 OR COALESCE(act_stats.sent_to_payment_count, 0) > 0 THEN true ELSE false END AS queue_payment,
                CASE WHEN EXISTS (SELECT 1 FROM integration_events ie WHERE ie.tenant_id = poi.tenant_id AND ie.company_id = poi.company_id AND ie.is_delete = false AND ie.status IN ('FAILED', 'RETRY', 'DEAD', 'CANCELLED') AND (ie.aggregate_id = po.id OR ie.aggregate_id = poi.id OR ie.aggregate_id IN (SELECT wa.id FROM work_completion_acts wa WHERE wa.purchase_order_item_id = poi.id AND wa.is_delete = false) OR ie.aggregate_id IN (SELECT cl.id FROM service_claims cl WHERE cl.purchase_order_item_id = poi.id AND cl.is_delete = false))) THEN true ELSE false END AS queue_integration,
                COALESCE(
                    NULLIF(UPPER(REPLACE(REPLACE(TRIM(CAST(poi.line_type AS VARCHAR)), '-', '_'), ' ', '_')), ''),
                    'WORK'
                ) AS category,
                CASE WHEN EXISTS (
                    SELECT 1 FROM work_completion_acts rejected_work_act
                    LEFT JOIN audit_logs rejection_audit ON rejection_audit.entity_id = rejected_work_act.id
                        AND rejection_audit.entity_type = 'WorkCompletionAct' AND rejection_audit.action = 'REJECT'
                        AND rejection_audit.is_delete = false
                    WHERE rejected_work_act.tenant_id = poi.tenant_id AND rejected_work_act.company_id = poi.company_id
                      AND rejected_work_act.purchase_order_item_id = poi.id AND rejected_work_act.is_delete = false
                      AND (rejected_work_act.status = 'REJECTED' OR rejection_audit.id IS NOT NULL)
                ) THEN true ELSE false END AS has_rework_history
            FROM purchase_order_items poi
            JOIN purchase_orders po ON po.id = poi.purchase_order_id AND po.tenant_id = poi.tenant_id AND po.company_id = poi.company_id AND po.is_delete = false AND po.request_type = 'SERVICE'
            LEFT JOIN suppliers s ON s.id = po.supplier_id AND s.tenant_id = po.tenant_id AND s.company_id = po.company_id AND s.is_delete = false
            LEFT JOIN contracts ct ON ct.id = po.contract_id AND ct.tenant_id = po.tenant_id AND ct.company_id = po.company_id AND ct.is_delete = false
            LEFT JOIN users u ON u.tenant_id = po.tenant_id AND u.company_id = po.company_id AND u.is_delete = false AND u.username = po.created_by
            LEFT JOIN (
                SELECT
                    act.tenant_id,
                    act.company_id,
                    act.purchase_order_item_id,
                    COUNT(*) AS total_act_count,
                    SUM(CASE WHEN act.status NOT IN ('REJECTED', 'CANCELLED') THEN 1 ELSE 0 END) AS effective_act_count,
                    SUM(CASE WHEN act.status IN ('DRAFT', 'APPROVAL_PENDING') THEN 1 ELSE 0 END) AS active_review_count,
                    SUM(CASE WHEN act.status = 'APPROVED' THEN 1 ELSE 0 END) AS approved_count,
                    SUM(CASE WHEN act.status = 'SENT_TO_PAYMENT' THEN 1 ELSE 0 END) AS sent_to_payment_count,
                    SUM(CASE WHEN act.status = 'PAID' THEN 1 ELSE 0 END) AS paid_count,
                    COALESCE(SUM(CASE WHEN act.status IN ('APPROVED', 'SENT_TO_PAYMENT') THEN act.amount ELSE 0 END), 0) AS payable_amount,
                    COALESCE(SUM(CASE WHEN act.status IN ('APPROVED', 'SENT_TO_PAYMENT', 'PAID') THEN act.amount ELSE 0 END), 0) AS accepted_amount,
                    COALESCE(SUM(CASE WHEN act.status IN ('APPROVED', 'SENT_TO_PAYMENT', 'PAID') THEN act.completed_quantity ELSE 0 END), 0) AS accepted_quantity,
                    COALESCE(SUM(CASE WHEN act.status = 'REJECTED' AND NOT EXISTS (
                        SELECT 1 FROM work_completion_acts replacement
                        WHERE replacement.tenant_id = act.tenant_id AND replacement.company_id = act.company_id
                          AND replacement.purchase_order_item_id = act.purchase_order_item_id AND replacement.is_delete = false
                          AND replacement.status NOT IN ('REJECTED', 'CANCELLED')
                          AND COALESCE(NULLIF(UPPER(TRIM(replacement.milestone)), ''), 'UNSPECIFIED')
                              = COALESCE(NULLIF(UPPER(TRIM(act.milestone)), ''), 'UNSPECIFIED')
                          AND COALESCE(replacement.period_start, DATE '1900-01-01') = COALESCE(act.period_start, DATE '1900-01-01')
                          AND COALESCE(replacement.period_end, DATE '1900-01-01') = COALESCE(act.period_end, DATE '1900-01-01')
                          AND (replacement.created_at > act.created_at OR (
                              replacement.created_at = act.created_at
                              AND CAST(replacement.id AS VARCHAR) > CAST(act.id AS VARCHAR)
                          ))
                    ) THEN act.amount ELSE 0 END), 0) AS current_rejected_amount,
                    COALESCE(SUM(CASE WHEN act.status = 'REJECTED' AND NOT EXISTS (
                        SELECT 1 FROM work_completion_acts replacement
                        WHERE replacement.tenant_id = act.tenant_id AND replacement.company_id = act.company_id
                          AND replacement.purchase_order_item_id = act.purchase_order_item_id AND replacement.is_delete = false
                          AND replacement.status NOT IN ('REJECTED', 'CANCELLED')
                          AND COALESCE(NULLIF(UPPER(TRIM(replacement.milestone)), ''), 'UNSPECIFIED')
                              = COALESCE(NULLIF(UPPER(TRIM(act.milestone)), ''), 'UNSPECIFIED')
                          AND COALESCE(replacement.period_start, DATE '1900-01-01') = COALESCE(act.period_start, DATE '1900-01-01')
                          AND COALESCE(replacement.period_end, DATE '1900-01-01') = COALESCE(act.period_end, DATE '1900-01-01')
                          AND (replacement.created_at > act.created_at OR (
                              replacement.created_at = act.created_at
                              AND CAST(replacement.id AS VARCHAR) > CAST(act.id AS VARCHAR)
                          ))
                    ) THEN act.completed_quantity ELSE 0 END), 0) AS current_rejected_quantity,
                    SUM(CASE WHEN act.status = 'REJECTED' AND NOT EXISTS (
                        SELECT 1 FROM work_completion_acts replacement
                        WHERE replacement.tenant_id = act.tenant_id AND replacement.company_id = act.company_id
                          AND replacement.purchase_order_item_id = act.purchase_order_item_id AND replacement.is_delete = false
                          AND replacement.status NOT IN ('REJECTED', 'CANCELLED')
                          AND COALESCE(NULLIF(UPPER(TRIM(replacement.milestone)), ''), 'UNSPECIFIED')
                              = COALESCE(NULLIF(UPPER(TRIM(act.milestone)), ''), 'UNSPECIFIED')
                          AND COALESCE(replacement.period_start, DATE '1900-01-01') = COALESCE(act.period_start, DATE '1900-01-01')
                          AND COALESCE(replacement.period_end, DATE '1900-01-01') = COALESCE(act.period_end, DATE '1900-01-01')
                          AND (replacement.created_at > act.created_at OR (
                              replacement.created_at = act.created_at
                              AND CAST(replacement.id AS VARCHAR) > CAST(act.id AS VARCHAR)
                          ))
                    ) THEN 1 ELSE 0 END) AS current_rejected_count,
                    MAX(CASE WHEN act.status NOT IN ('REJECTED', 'CANCELLED') THEN act.updated_at END) AS effective_updated_at
                FROM work_completion_acts act
                WHERE act.tenant_id = :tenantId AND act.company_id = :companyId
                  AND act.is_delete = false AND act.purchase_order_item_id IS NOT NULL
                GROUP BY act.tenant_id, act.company_id, act.purchase_order_item_id
            ) act_stats ON act_stats.tenant_id = poi.tenant_id
              AND act_stats.company_id = poi.company_id AND act_stats.purchase_order_item_id = poi.id
            WHERE poi.tenant_id = :tenantId AND poi.company_id = :companyId AND poi.is_delete = false AND poi.line_type = 'WORK'
        )
        """);

    private final EntityManager entityManager;

    @Override
    public Page<CaseRow> findCases(CaseFilter filter, Pageable pageable) {
        CaseFilter normalized = filter == null ? CaseFilter.all() : filter;
        Pageable effectivePageable = pageable == null ? Pageable.unpaged() : pageable;
        PredicateSql predicate = predicate(normalized, true);
        String sql = CASES_CTE + " SELECT " + COLUMNS + " FROM cases c WHERE " + predicate.sql()
            + orderBy(effectivePageable.getSort());
        Query query = entityManager.createNativeQuery(sql);
        bind(query, predicate);
        if (effectivePageable.isPaged()) {
            query.setFirstResult(Math.toIntExact(effectivePageable.getOffset()));
            query.setMaxResults(effectivePageable.getPageSize());
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<CaseRow> content = rows.stream().map(this::mapRow).toList();
        return effectivePageable.isPaged()
            ? new PageImpl<>(content, effectivePageable, count(normalized))
            : new PageImpl<>(content);
    }

    @Override
    public long count(CaseFilter filter) {
        CaseFilter normalized = filter == null ? CaseFilter.all() : filter;
        PredicateSql predicate = predicate(normalized, true);
        Query query = entityManager.createNativeQuery(
            CASES_CTE + " SELECT COUNT(*) FROM cases c WHERE " + predicate.sql()
        );
        bind(query, predicate);
        return ((Number) query.getSingleResult()).longValue();
    }

    @Override
    public Optional<CaseRow> findCase(UUID caseId, CaseFilter visibilityFilter) {
        if (caseId == null) {
            return Optional.empty();
        }
        PredicateSql predicate = predicate(visibilityFilter == null ? CaseFilter.all() : visibilityFilter, false);
        String sql = CASES_CTE + " SELECT " + COLUMNS + " FROM cases c WHERE c.case_id = :caseId AND " + predicate.sql()
            + " ORDER BY CASE c.origin_type WHEN 'SERVICE_REQUEST' THEN 0 WHEN 'PO_SERVICE' THEN 1 ELSE 2 END";
        Query query = entityManager.createNativeQuery(sql).setParameter("caseId", caseId).setMaxResults(1);
        bind(query, predicate);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream().findFirst().map(this::mapRow);
    }

    @Override
    public Optional<CaseRow> findCaseByAggregateId(UUID aggregateId, CaseFilter visibilityFilter) {
        if (aggregateId == null) return Optional.empty();
        PredicateSql predicate = predicate(visibilityFilter == null ? CaseFilter.all() : visibilityFilter, false);
        String aggregateMatch = """
            (
                :aggregateId IN (c.case_id, c.origin_id, c.request_id, c.service_order_id,
                    c.purchase_order_id, c.purchase_order_item_id, c.latest_act_id)
                OR EXISTS (
                    SELECT 1 FROM service_executions execution
                    WHERE execution.id = :aggregateId AND execution.is_delete = false
                      AND execution.tenant_id = :tenantId AND execution.company_id = :companyId
                      AND execution.service_request_id = c.request_id
                )
                OR EXISTS (
                    SELECT 1 FROM service_acceptance_acts act
                    WHERE act.id = :aggregateId AND act.is_delete = false
                      AND act.tenant_id = :tenantId AND act.company_id = :companyId
                      AND (act.service_request_id = c.request_id OR act.service_order_id = c.service_order_id)
                )
                OR EXISTS (
                    SELECT 1 FROM service_claims claim
                    WHERE claim.id = :aggregateId AND claim.is_delete = false
                      AND claim.tenant_id = :tenantId AND claim.company_id = :companyId
                      AND (claim.service_request_id = c.request_id
                        OR claim.service_order_id = c.service_order_id
                        OR claim.purchase_order_item_id = c.purchase_order_item_id)
                )
                OR EXISTS (
                    SELECT 1 FROM work_completion_acts work_act
                    WHERE work_act.id = :aggregateId AND work_act.is_delete = false
                      AND work_act.tenant_id = :tenantId AND work_act.company_id = :companyId
                      AND work_act.purchase_order_item_id = c.purchase_order_item_id
                )
            )
            """;
        String sql = CASES_CTE + " SELECT " + COLUMNS + " FROM cases c WHERE " + aggregateMatch
            + " AND " + predicate.sql()
            + " ORDER BY CASE c.origin_type WHEN 'SERVICE_REQUEST' THEN 0 WHEN 'PO_SERVICE' THEN 1 ELSE 2 END";
        Query query = entityManager.createNativeQuery(sql).setParameter("aggregateId", aggregateId).setMaxResults(1);
        bind(query, predicate);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream().findFirst().map(this::mapRow);
    }

    @Override
    public List<ManagementBucket> managementBuckets(CaseFilter visibilityFilter) {
        PredicateSql predicate = predicate(visibilityFilter == null ? CaseFilter.all() : visibilityFilter, false);
        String ageKey = """
            CASE
                WHEN c.due_date IS NULL THEN 'NO_DUE_DATE'
                WHEN c.due_date < CURRENT_DATE - 30 THEN 'OVERDUE_30_PLUS'
                WHEN c.due_date < CURRENT_DATE THEN 'OVERDUE_1_30'
                WHEN c.due_date <= CURRENT_DATE + 7 THEN 'DUE_0_7'
                ELSE 'FUTURE'
            END
            """;
        String ageLabel = """
            CASE
                WHEN c.due_date IS NULL THEN 'No due date'
                WHEN c.due_date < CURRENT_DATE - 30 THEN 'Overdue 30+ days'
                WHEN c.due_date < CURRENT_DATE THEN 'Overdue 1–30 days'
                WHEN c.due_date <= CURRENT_DATE + 7 THEN 'Due in 0–7 days'
                ELSE 'Due after 7 days'
            END
            """;
        String sql = (CASES_CTE + """
             SELECT 'LIFECYCLE' AS dimension,
                    CASE WHEN c.is_completed THEN 'COMPLETED' ELSE c.phase END,
                    CASE WHEN c.is_completed THEN 'Completed' ELSE c.phase END,
                    COUNT(*), SUM(COALESCE(c.ordered_amount, 0)),
                    CASE WHEN COUNT(DISTINCT COALESCE(c.currency_code, 'UNSPECIFIED')) = 1
                         THEN MIN(c.currency_code) ELSE NULL END,
                    COUNT(DISTINCT COALESCE(c.currency_code, 'UNSPECIFIED'))
             FROM cases c WHERE __PREDICATE__
             GROUP BY c.is_completed, c.phase
             UNION ALL
             SELECT 'BACKLOG', __AGE_KEY__, __AGE_LABEL__, COUNT(*), CAST(NULL AS NUMERIC),
                    CAST(NULL AS VARCHAR), CAST(0 AS BIGINT)
             FROM cases c
             WHERE __PREDICATE__ AND c.is_completed = false
             GROUP BY __AGE_KEY__, __AGE_LABEL__
             UNION ALL
             SELECT 'EXPOSURE', 'ORDERED', 'Ordered value', COUNT(*), SUM(c.ordered_amount),
                    CASE WHEN COUNT(DISTINCT COALESCE(c.currency_code, 'UNSPECIFIED')) = 1
                         THEN MIN(c.currency_code) ELSE NULL END,
                    COUNT(DISTINCT COALESCE(c.currency_code, 'UNSPECIFIED'))
             FROM cases c
             WHERE __PREDICATE__ AND c.is_completed = false AND COALESCE(c.ordered_amount, 0) > 0
             UNION ALL
             SELECT 'EXPOSURE', 'ACCEPTED', 'Accepted value', COUNT(*), SUM(c.accepted_amount),
                    CASE WHEN COUNT(DISTINCT COALESCE(c.currency_code, 'UNSPECIFIED')) = 1
                         THEN MIN(c.currency_code) ELSE NULL END,
                    COUNT(DISTINCT COALESCE(c.currency_code, 'UNSPECIFIED'))
             FROM cases c
             WHERE __PREDICATE__ AND c.is_completed = false AND COALESCE(c.accepted_amount, 0) > 0
             UNION ALL
             SELECT 'EXPOSURE', 'REJECTED', 'Rejected value', COUNT(*), SUM(c.rejected_amount),
                    CASE WHEN COUNT(DISTINCT COALESCE(c.currency_code, 'UNSPECIFIED')) = 1
                         THEN MIN(c.currency_code) ELSE NULL END,
                    COUNT(DISTINCT COALESCE(c.currency_code, 'UNSPECIFIED'))
             FROM cases c
             WHERE __PREDICATE__ AND c.is_completed = false AND COALESCE(c.rejected_amount, 0) > 0
             UNION ALL
             SELECT 'EXPOSURE', 'OUTSTANDING', 'Outstanding acceptance value', COUNT(*), SUM(c.remaining_amount),
                    CASE WHEN COUNT(DISTINCT COALESCE(c.currency_code, 'UNSPECIFIED')) = 1
                         THEN MIN(c.currency_code) ELSE NULL END,
                    COUNT(DISTINCT COALESCE(c.currency_code, 'UNSPECIFIED'))
             FROM cases c
             WHERE __PREDICATE__ AND c.is_completed = false AND COALESCE(c.remaining_amount, 0) > 0
             UNION ALL
             SELECT 'SUPPLIER', CAST(c.supplier_id AS VARCHAR), COALESCE(c.supplier_name, 'Unassigned supplier'), COUNT(*),
                    SUM(COALESCE(c.remaining_amount, 0)),
                    CASE WHEN COUNT(DISTINCT COALESCE(c.currency_code, 'UNSPECIFIED')) = 1
                         THEN MIN(c.currency_code) ELSE NULL END,
                    COUNT(DISTINCT COALESCE(c.currency_code, 'UNSPECIFIED'))
             FROM cases c
             WHERE __PREDICATE__ AND c.is_completed = false AND c.supplier_id IS NOT NULL
             GROUP BY c.supplier_id, c.supplier_name
             UNION ALL
             SELECT 'DEPARTMENT', CAST(c.department_id AS VARCHAR), COALESCE(d.name, CAST(c.department_id AS VARCHAR)), COUNT(*),
                    SUM(COALESCE(c.remaining_amount, 0)),
                    CASE WHEN COUNT(DISTINCT COALESCE(c.currency_code, 'UNSPECIFIED')) = 1
                         THEN MIN(c.currency_code) ELSE NULL END,
                    COUNT(DISTINCT COALESCE(c.currency_code, 'UNSPECIFIED'))
             FROM cases c
             LEFT JOIN departments d ON d.id = c.department_id AND d.tenant_id = :tenantId
                 AND d.company_id = :companyId AND d.is_delete = false
             WHERE __PREDICATE__ AND c.is_completed = false AND c.department_id IS NOT NULL
             GROUP BY c.department_id, d.name
             UNION ALL
             SELECT 'CONTRACT', CAST(c.contract_id AS VARCHAR), COALESCE(c.contract_code, CAST(c.contract_id AS VARCHAR)), COUNT(*),
                    SUM(COALESCE(c.remaining_amount, 0)),
                    CASE WHEN COUNT(DISTINCT COALESCE(c.currency_code, 'UNSPECIFIED')) = 1
                         THEN MIN(c.currency_code) ELSE NULL END,
                    COUNT(DISTINCT COALESCE(c.currency_code, 'UNSPECIFIED'))
             FROM cases c
             WHERE __PREDICATE__ AND c.is_completed = false AND c.contract_id IS NOT NULL
             GROUP BY c.contract_id, c.contract_code
             UNION ALL
             SELECT 'CATEGORY', c.category, c.category, COUNT(*),
                    SUM(COALESCE(c.remaining_amount, 0)),
                    CASE WHEN COUNT(DISTINCT COALESCE(c.currency_code, 'UNSPECIFIED')) = 1
                         THEN MIN(c.currency_code) ELSE NULL END,
                    COUNT(DISTINCT COALESCE(c.currency_code, 'UNSPECIFIED'))
             FROM cases c
             WHERE __PREDICATE__ AND c.is_completed = false
             GROUP BY c.category
             UNION ALL
             SELECT 'PAYMENT', __AGE_KEY__, __AGE_LABEL__, COUNT(*), SUM(COALESCE(c.payment_amount, 0)),
                    CASE WHEN COUNT(DISTINCT COALESCE(c.currency_code, 'UNSPECIFIED')) = 1
                         THEN MIN(c.currency_code) ELSE NULL END,
                    COUNT(DISTINCT COALESCE(c.currency_code, 'UNSPECIFIED'))
             FROM cases c
             WHERE __PREDICATE__ AND c.is_completed = false AND c.queue_payment = true
             GROUP BY __AGE_KEY__, __AGE_LABEL__
             UNION ALL
             SELECT 'INTEGRATION_TREND', CAST(CAST(integration_event.created_at AS DATE) AS VARCHAR),
                    CAST(CAST(integration_event.created_at AS DATE) AS VARCHAR), COUNT(DISTINCT c.case_id),
                    CAST(NULL AS NUMERIC), CAST(NULL AS VARCHAR), CAST(0 AS BIGINT)
             FROM cases c
             JOIN integration_events integration_event ON (
                integration_event.aggregate_id IN (
                    c.case_id, c.origin_id, c.request_id, c.service_order_id,
                    c.purchase_order_id, c.purchase_order_item_id, c.latest_act_id
                )
                OR integration_event.aggregate_id IN (
                    SELECT execution.id FROM service_executions execution
                    WHERE execution.service_request_id = c.request_id AND execution.is_delete = false
                )
                OR integration_event.aggregate_id IN (
                    SELECT act.id FROM service_acceptance_acts act
                    WHERE (act.service_request_id = c.request_id OR act.service_order_id = c.service_order_id)
                      AND act.is_delete = false
                )
                OR integration_event.aggregate_id IN (
                    SELECT claim.id FROM service_claims claim
                    WHERE (claim.service_request_id = c.request_id
                        OR claim.service_order_id = c.service_order_id
                        OR claim.purchase_order_item_id = c.purchase_order_item_id)
                      AND claim.is_delete = false
                )
                OR integration_event.aggregate_id IN (
                    SELECT work_act.id FROM work_completion_acts work_act
                    WHERE work_act.purchase_order_item_id = c.purchase_order_item_id AND work_act.is_delete = false
                )
             )
             WHERE __PREDICATE__ AND c.is_completed = false
               AND integration_event.tenant_id = :tenantId AND integration_event.company_id = :companyId
               AND integration_event.is_delete = false
               AND integration_event.status IN ('FAILED', 'RETRY', 'DEAD', 'CANCELLED')
               AND CAST(integration_event.created_at AS DATE) >= CURRENT_DATE - 29
             GROUP BY CAST(integration_event.created_at AS DATE)
             UNION ALL
             SELECT 'QUALITY_METRIC', 'CYCLE_TIME_DAYS', 'Average cycle time (days)', COUNT(*),
                    AVG(EXTRACT(EPOCH FROM (c.completed_at - c.created_at)) / 86400.0),
                    CAST(NULL AS VARCHAR), CAST(0 AS BIGINT)
             FROM cases c
             WHERE __PREDICATE__ AND c.is_completed = true AND c.completed_at IS NOT NULL AND c.created_at IS NOT NULL
             UNION ALL
             SELECT 'QUALITY_METRIC', 'ON_TIME_RATE', 'On-time completion rate', COUNT(*),
                    100.0 * SUM(CASE WHEN CAST(c.completed_at AS DATE) <= c.due_date THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0),
                    CAST(NULL AS VARCHAR), CAST(0 AS BIGINT)
             FROM cases c
             WHERE __PREDICATE__ AND c.is_completed = true AND c.completed_at IS NOT NULL AND c.due_date IS NOT NULL
             UNION ALL
             SELECT 'QUALITY_METRIC', 'FIRST_PASS_RATE', 'First-pass acceptance rate', COUNT(*),
                    100.0 * SUM(CASE WHEN c.has_rework_history = false
                        AND COALESCE(c.rejected_amount, 0) = 0 AND COALESCE(c.rejected_quantity, 0) = 0
                        THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), CAST(NULL AS VARCHAR), CAST(0 AS BIGINT)
             FROM cases c
             WHERE __PREDICATE__ AND c.is_completed = true
             UNION ALL
             SELECT 'QUALITY_METRIC', 'REWORK_RATE', 'Rework rate', COUNT(*),
                    100.0 * SUM(CASE WHEN c.has_rework_history = true
                        OR COALESCE(c.rejected_amount, 0) > 0 OR COALESCE(c.rejected_quantity, 0) > 0
                        OR c.phase = 'REWORK' THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0),
                    CAST(NULL AS VARCHAR), CAST(0 AS BIGINT)
             FROM cases c
             WHERE __PREDICATE__
             UNION ALL
             SELECT 'QUALITY_METRIC', 'OPEN_CLAIM_CASES', 'Cases with open claims',
                    SUM(CASE WHEN c.open_claim_count > 0 THEN 1 ELSE 0 END), CAST(NULL AS NUMERIC),
                    CAST(NULL AS VARCHAR), CAST(0 AS BIGINT)
             FROM cases c
             WHERE __PREDICATE__
            """)
            .replace("__PREDICATE__", predicate.sql())
            .replace("__AGE_KEY__", ageKey)
            .replace("__AGE_LABEL__", ageLabel);
        Query query = entityManager.createNativeQuery(sql);
        bind(query, predicate);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(row -> new ManagementBucket(
            string(row[0]), string(row[1]), string(row[2]), number(row[3]), decimal(row[4]), string(row[5]), number(row[6])
        )).toList();
    }

    @Override
    public List<WorkActRow> findWorkActs(UUID purchaseOrderItemId) {
        if (purchaseOrderItemId == null) return List.of();
        Query query = entityManager.createNativeQuery("""
            SELECT wa.id, wa.code, wa.name, wa.status, wa.version,
                   wa.purchase_order_id, wa.purchase_order_item_id, wa.contract_id,
                   wa.work_location, wa.milestone, wa.period_start, wa.period_end,
                   wa.completed_quantity, wa.amount, wa.inspection_result,
                   wa.accepted_at, wa.accepted_by, wa.commission_notes, wa.remarks,
                   wa.document_reviewed_at, wa.finance_reviewed_at, wa.sent_to_payment_at,
                   wa.paid_at, wa.contract_consumed_at, wa.payment_reference,
                   wa.created_at, wa.updated_at
            FROM work_completion_acts wa
            WHERE wa.tenant_id = :tenantId AND wa.company_id = :companyId
              AND wa.purchase_order_item_id = :purchaseOrderItemId AND wa.is_delete = false
            ORDER BY wa.created_at DESC, wa.id DESC
            """)
            .setParameter("tenantId", TenantContext.currentTenant())
            .setParameter("companyId", CompanyContext.currentCompany())
            .setParameter("purchaseOrderItemId", purchaseOrderItemId);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(this::mapWorkAct).toList();
    }

    private PredicateSql predicate(CaseFilter filter, boolean includeQueue) {
        List<String> clauses = new ArrayList<>();
        List<Parameter> parameters = new ArrayList<>();
        if (includeQueue) {
            String queue = normalizeQueue(filter.queue());
            clauses.add(switch (queue) {
                case "COMPLETED", "HISTORY" -> "c.is_completed = true";
                case "EXECUTION" -> "c.is_completed = false AND c.queue_execution = true";
                case "ACCEPTANCE" -> "c.is_completed = false AND c.queue_acceptance = true";
                case "REWORK" -> "c.is_completed = false AND c.queue_rework = true";
                case "PAYMENT" -> "c.is_completed = false AND c.queue_payment = true";
                case "INTEGRATION" -> "c.is_completed = false AND c.queue_integration = true";
                default -> "c.is_completed = false";
            });
        }
        if (filter.originType() != null) {
            clauses.add("c.origin_type = :originType");
            parameters.add(new Parameter("originType", filter.originType().name()));
        }
        if (filter.scope() == Scope.MY) {
            if (filter.currentUserId() == null) {
                clauses.add("1 = 0");
            } else {
                clauses.add("c.responsible_id = :currentUserId");
                parameters.add(new Parameter("currentUserId", filter.currentUserId()));
            }
        } else if (filter.scope() == Scope.TEAM) {
            if (filter.teamDepartmentId() == null) {
                clauses.add("1 = 0");
            } else {
                clauses.add("c.department_id = :teamDepartmentId");
                parameters.add(new Parameter("teamDepartmentId", filter.teamDepartmentId()));
            }
        }
        if (filter.ownerId() != null) {
            clauses.add("c.responsible_id = :ownerId");
            parameters.add(new Parameter("ownerId", filter.ownerId()));
        }
        if (filter.supplierId() != null) {
            clauses.add("c.supplier_id = :supplierId");
            parameters.add(new Parameter("supplierId", filter.supplierId()));
        }
        if (filter.departmentId() != null) {
            clauses.add("c.department_id = :departmentId");
            parameters.add(new Parameter("departmentId", filter.departmentId()));
        }
        if (filter.contractId() != null) {
            clauses.add("c.contract_id = :contractId");
            parameters.add(new Parameter("contractId", filter.contractId()));
        }
        if (filter.currencyCode() != null) {
            clauses.add("c.currency_code = :currencyCode");
            parameters.add(new Parameter("currencyCode", filter.currencyCode()));
        }
        if (filter.category() != null) {
            clauses.add("c.category = :category");
            parameters.add(new Parameter("category", filter.category()));
        }
        if (filter.exposureMetric() != null) {
            clauses.add(switch (filter.exposureMetric()) {
                case "ORDERED" -> "COALESCE(c.ordered_amount, 0) > 0";
                case "ACCEPTED" -> "COALESCE(c.accepted_amount, 0) > 0";
                case "REJECTED" -> "COALESCE(c.rejected_amount, 0) > 0";
                case "OUTSTANDING" -> "COALESCE(c.remaining_amount, 0) > 0";
                default -> throw new BadRequestException(
                    "Unsupported services exposure metric: " + filter.exposureMetric()
                );
            });
        }
        if (filter.agingBucket() != null) {
            clauses.add(switch (filter.agingBucket()) {
                case "OVERDUE_30_PLUS" -> "c.due_date < CURRENT_DATE - 30";
                case "OVERDUE_1_30" -> "c.due_date >= CURRENT_DATE - 30 AND c.due_date < CURRENT_DATE";
                case "DUE_0_7" -> "c.due_date >= CURRENT_DATE AND c.due_date <= CURRENT_DATE + 7";
                case "FUTURE" -> "c.due_date > CURRENT_DATE + 7";
                case "NO_DUE_DATE" -> "c.due_date IS NULL";
                default -> throw new BadRequestException("Unsupported services aging bucket: " + filter.agingBucket());
            });
        }
        if (filter.integrationDay() != null) {
            clauses.add("""
                EXISTS (
                    SELECT 1 FROM integration_events integration_event
                    WHERE integration_event.tenant_id = :tenantId
                      AND integration_event.company_id = :companyId
                      AND integration_event.is_delete = false
                      AND integration_event.status IN ('FAILED', 'RETRY', 'DEAD', 'CANCELLED')
                      AND CAST(integration_event.created_at AS DATE) = :integrationDay
                      AND (
                        integration_event.aggregate_id IN (
                            c.case_id, c.origin_id, c.request_id, c.service_order_id,
                            c.purchase_order_id, c.purchase_order_item_id, c.latest_act_id
                        )
                        OR integration_event.aggregate_id IN (
                            SELECT execution.id FROM service_executions execution
                            WHERE execution.service_request_id = c.request_id AND execution.is_delete = false
                        )
                        OR integration_event.aggregate_id IN (
                            SELECT act.id FROM service_acceptance_acts act
                            WHERE (act.service_request_id = c.request_id OR act.service_order_id = c.service_order_id)
                              AND act.is_delete = false
                        )
                        OR integration_event.aggregate_id IN (
                            SELECT claim.id FROM service_claims claim
                            WHERE (claim.service_request_id = c.request_id
                                OR claim.service_order_id = c.service_order_id
                                OR claim.purchase_order_item_id = c.purchase_order_item_id)
                              AND claim.is_delete = false
                        )
                        OR integration_event.aggregate_id IN (
                            SELECT work_act.id FROM work_completion_acts work_act
                            WHERE work_act.purchase_order_item_id = c.purchase_order_item_id AND work_act.is_delete = false
                        )
                      )
                )
                """);
            parameters.add(new Parameter("integrationDay", filter.integrationDay()));
        }
        if (filter.hasOpenClaims() != null) {
            clauses.add(filter.hasOpenClaims() ? "c.open_claim_count > 0" : "c.open_claim_count = 0");
        }
        if (filter.hasRework() != null) {
            String rework = "(c.has_rework_history = true OR COALESCE(c.rejected_amount, 0) > 0 "
                + "OR COALESCE(c.rejected_quantity, 0) > 0 OR c.phase = 'REWORK')";
            clauses.add(filter.hasRework() ? rework : "NOT " + rework);
        }
        if (!filter.statuses().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (int index = 0; index < filter.statuses().size(); index++) {
                String name = "status" + index;
                names.add(":" + name);
                parameters.add(new Parameter(name, filter.statuses().get(index)));
            }
            clauses.add("c.status IN (" + String.join(", ", names) + ")");
        }
        if (filter.overdue() != null) {
            clauses.add("c.is_overdue = :overdue");
            parameters.add(new Parameter("overdue", filter.overdue()));
        }
        if (filter.search() != null) {
            clauses.add("LOWER(COALESCE(c.code, '') || ' ' || COALESCE(c.title, '') || ' ' || COALESCE(c.supplier_name, '') || ' ' || COALESCE(c.contract_code, '') || ' ' || COALESCE(c.responsible_name, '')) LIKE :search");
            parameters.add(new Parameter("search", "%" + filter.search() + "%"));
        }
        return new PredicateSql(clauses.isEmpty() ? "1 = 1" : String.join(" AND ", clauses), parameters);
    }

    private String orderBy(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return " ORDER BY CASE WHEN c.is_overdue THEN 0 ELSE 1 END, CASE WHEN c.due_date IS NULL THEN 1 ELSE 0 END, c.due_date, c.created_at DESC, c.case_id";
        }
        List<String> clauses = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = switch (order.getProperty()) {
                case "code" -> "LOWER(c.code)";
                case "status" -> "c.status";
                case "phase" -> "c.phase";
                case "supplierName" -> "LOWER(c.supplier_name)";
                case "responsibleName", "ownerName" -> "LOWER(c.responsible_name)";
                case "dueDate", "plannedEndDate" -> "c.due_date";
                case "progressPercent" -> "c.progress_percent";
                case "orderedAmount" -> "c.ordered_amount";
                case "remainingAmount" -> "c.remaining_amount";
                case "createdAt" -> "c.created_at";
                case "completedAt" -> "c.completed_at";
                default -> throw new BadRequestException("Unsupported service-case sort field: " + order.getProperty());
            };
            String nullOrdering = Set.of("c.due_date", "c.completed_at").contains(column) ? " NULLS LAST" : "";
            clauses.add(column + (order.isAscending() ? " ASC" : " DESC") + nullOrdering);
        }
        clauses.add("c.case_id ASC");
        return " ORDER BY " + String.join(", ", clauses);
    }

    private void bind(Query query, PredicateSql predicate) {
        query.setParameter("tenantId", TenantContext.currentTenant());
        query.setParameter("companyId", CompanyContext.currentCompany());
        predicate.parameters().forEach(parameter -> query.setParameter(parameter.name(), parameter.value()));
    }

    private String normalizeQueue(String queue) {
        String normalized = queue == null || queue.isBlank()
            ? "ALL"
            : queue.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (!QUEUES.contains(normalized)) {
            throw new BadRequestException("Unsupported services queue: " + queue);
        }
        return normalized;
    }

    private CaseRow mapRow(Object[] row) {
        return new CaseRow(
            uuid(row[0]), OriginType.valueOf(string(row[1])), uuid(row[2]), uuid(row[3]), uuid(row[4]), uuid(row[5]),
            uuid(row[6]), uuid(row[7]), string(row[8]), string(row[9]), string(row[10]), string(row[11]),
            uuid(row[12]), string(row[13]), uuid(row[14]), string(row[15]), uuid(row[16]), string(row[17]), uuid(row[18]),
            localDate(row[19]), string(row[20]), bool(row[21]), decimal(row[22]), string(row[23]),
            decimal(row[24]), decimal(row[25]), decimal(row[26]), decimal(row[27]), string(row[28]),
            decimal(row[29]), decimal(row[30]), decimal(row[31]), decimal(row[32]), string(row[33]), string(row[34]),
            number(row[35]), number(row[36]), string(row[37]), nullableLong(row[38]), localDateTime(row[39]), localDateTime(row[40])
        );
    }

    private WorkActRow mapWorkAct(Object[] row) {
        return new WorkActRow(
            uuid(row[0]), string(row[1]), string(row[2]), string(row[3]), nullableLong(row[4]),
            uuid(row[5]), uuid(row[6]), uuid(row[7]), string(row[8]), string(row[9]),
            localDate(row[10]), localDate(row[11]), decimal(row[12]), decimal(row[13]), string(row[14]),
            localDateTime(row[15]), string(row[16]), string(row[17]), string(row[18]),
            localDateTime(row[19]), localDateTime(row[20]), localDateTime(row[21]), localDateTime(row[22]),
            localDateTime(row[23]), string(row[24]), localDateTime(row[25]), localDateTime(row[26])
        );
    }

    static UUID uuid(Object value) {
        if (value == null) return null;
        if (value instanceof UUID id) return id;
        if (value instanceof byte[] bytes) {
            if (bytes.length != 16) {
                throw new IllegalArgumentException("Expected 16 bytes for UUID but received " + bytes.length);
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
        return UUID.fromString(value.toString());
    }

    private String string(Object value) {
        return value == null ? null : value.toString();
    }

    private BigDecimal decimal(Object value) {
        if (value == null) return null;
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    private long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private boolean bool(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private LocalDate localDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate date) return date;
        if (value instanceof Date date) return date.toLocalDate();
        return LocalDate.parse(value.toString());
    }

    private LocalDateTime localDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        return LocalDateTime.parse(value.toString().replace(' ', 'T'));
    }

    private record Parameter(String name, Object value) {
    }

    private record PredicateSql(String sql, List<Parameter> parameters) {
    }
}
