package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.security.AuthenticatedTokenState;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class ResourceLibraryService {
    private final JdbcTemplate db;
    private final AttachmentService attachments;
    private final IdempotencyService idempotency;
    private final ResourceKnowledgeQueue knowledge;
    @Value("${app.resources.max-files-per-user:1000}") private int maxFilesPerUser;
    @Value("${app.resources.max-company-bytes:5368709120}") private long maxCompanyBytes;

    public record ResourceView(UUID id, UUID versionId, String title, String originalFileName,
                               String contentType, long sizeBytes, String status, String indexStatus,
                               int versionNo, LocalDateTime createdAt, LocalDateTime updatedAt,
                               String indexErrorCode, boolean canReindex) {}
    public record VersionView(UUID id, int versionNo, String title, String originalFileName,
                              String contentType, long sizeBytes, String status, String indexStatus,
                              LocalDateTime publishedAt, String indexErrorCode) {}
    public record Page<T>(List<T> content, int page, int size, long totalElements) {}
    private record UploadIdentity(String title, String fileName, String contentType, String sha256) {}

    @Transactional
    public ResourceView create(String requestedTitle, MultipartFile file, String idempotencyKey) {
        String title = title(requestedTitle, file.getOriginalFilename());
        String hash;
        try { hash = sha256(file.getBytes()); }
        catch (IOException ex) { throw new BadRequestException("Cannot read resource file"); }
        UploadIdentity identity = new UploadIdentity(title, file.getOriginalFilename(), file.getContentType(), hash);
        return idempotency.executeRequired("RESOURCE_UPLOAD", owner(), idempotencyKey, identity,
            ResourceView.class, () -> store(title, file));
    }

    private ResourceView store(String title, MultipartFile file) {
        requireQuota(file.getSize(), true);
        UUID resourceId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String tenant = TenantContext.currentTenant();
        UUID company = CompanyContext.currentCompany();
        db.update("""
            INSERT INTO resources(id,tenant_id,company_id,owner_id,title,status)
            VALUES (?,?,?,?,?,'PUBLISHED')
            """, resourceId, tenant, company, owner(), title);
        AttachmentDto original = attachments.uploadResourceVersion(versionId, file);
        db.update("""
            INSERT INTO resource_versions(id,resource_id,tenant_id,company_id,version_no,title,
                original_file_name,content_type,size_bytes,sha256,attachment_id,status,index_status)
            VALUES (?,?,?,?,1,?,?,?,?,?,?,'PUBLISHED','PENDING')
            """, versionId, resourceId, tenant, company, title, original.getOriginalFileName(),
            original.getContentType(), original.getSizeBytes(), original.getChecksumSha256(), original.getId());
        db.update("UPDATE resources SET current_version_id=? WHERE id=?", versionId, resourceId);
        long revision = bumpRevision();
        knowledge.visibility(resourceId, revision);
        knowledge.index(versionId, revision);
        return get(resourceId);
    }

    @Transactional
    public ResourceView replace(UUID resourceId, String requestedTitle, MultipartFile file, String idempotencyKey) {
        requireOwnerOrAdmin(resourceId, false);
        String title = title(requestedTitle, file.getOriginalFilename());
        String hash;
        try { hash = sha256(file.getBytes()); }
        catch (IOException ex) { throw new BadRequestException("Cannot read resource file"); }
        UploadIdentity identity = new UploadIdentity(title, file.getOriginalFilename(), file.getContentType(), hash);
        return idempotency.executeRequired("RESOURCE_REPLACE", resourceId, idempotencyKey, identity,
            ResourceView.class, () -> storeReplacement(resourceId, title, file));
    }

    private ResourceView storeReplacement(UUID resourceId, String title, MultipartFile file) {
        List<Integer> versions = db.query("""
            SELECT v.version_no FROM resources r JOIN resource_versions v ON v.id=r.current_version_id
            WHERE r.id=? AND r.tenant_id=? AND r.company_id=? AND r.status='PUBLISHED' FOR UPDATE OF r
            """, (rs, n) -> rs.getInt(1), resourceId, TenantContext.currentTenant(), CompanyContext.currentCompany());
        if (versions.isEmpty()) throw new NotFoundException("Resource not found");
        requireQuota(file.getSize(), false);
        UUID versionId = UUID.randomUUID();
        AttachmentDto original = attachments.uploadResourceVersion(versionId, file);
        db.update("""
            INSERT INTO resource_versions(id,resource_id,tenant_id,company_id,version_no,title,
                original_file_name,content_type,size_bytes,sha256,attachment_id,status,index_status)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,'PUBLISHED','PENDING')
            """, versionId, resourceId, TenantContext.currentTenant(), CompanyContext.currentCompany(),
            versions.getFirst() + 1, title, original.getOriginalFileName(), original.getContentType(),
            original.getSizeBytes(), original.getChecksumSha256(), original.getId());
        db.update("""
            UPDATE resource_versions SET status='HISTORICAL'
            WHERE id=(SELECT current_version_id FROM resources WHERE id=?)
            """, resourceId);
        db.update("UPDATE resources SET current_version_id=?,title=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
            versionId, title, resourceId);
        long revision = bumpRevision();
        knowledge.visibility(resourceId, revision);
        knowledge.index(versionId, revision);
        return get(resourceId);
    }

    @Transactional(readOnly = true)
    public Page<ResourceView> list(String query, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new BadRequestException("Invalid Resources page");
        String search = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (search.length() > 200) throw new BadRequestException("Resources search is too long");
        String term = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String where = " FROM resources r JOIN resource_versions v ON v.id=r.current_version_id"
            + " WHERE r.tenant_id=? AND r.company_id=? AND r.status='PUBLISHED'"
            + " AND v.status='PUBLISHED' AND (lower(r.title) LIKE ? ESCAPE '!' OR lower(v.original_file_name) LIKE ? ESCAPE '!')";
        String tenant = TenantContext.currentTenant();
        UUID company = CompanyContext.currentCompany();
        Long count = db.queryForObject("SELECT count(*)" + where, Long.class, tenant, company, term, term);
        List<ResourceView> rows = db.query("SELECT r.id,r.title,r.status,r.owner_id,r.created_at,r.updated_at,"
                + "v.id AS version_id,v.original_file_name,v.content_type,v.size_bytes,v.index_status,v.index_error_code,v.version_no"
                + where + " ORDER BY r.updated_at DESC,r.id LIMIT ? OFFSET ?", this::resourceRow,
            tenant, company, term, term, size, (long) page * size);
        return new Page<>(rows, page, size, count == null ? 0 : count);
    }

    @Transactional(readOnly = true)
    public ResourceView get(UUID resourceId) {
        List<ResourceView> rows = db.query("""
            SELECT r.id,r.title,r.status,r.owner_id,r.created_at,r.updated_at,v.id AS version_id,
                   v.original_file_name,v.content_type,v.size_bytes,v.index_status,v.index_error_code,v.version_no
            FROM resources r JOIN resource_versions v ON v.id=r.current_version_id
            WHERE r.id=? AND r.tenant_id=? AND r.company_id=?
              AND r.status IN ('PUBLISHED','ARCHIVED') AND v.status IN ('PUBLISHED','HISTORICAL')
            """, this::resourceRow, resourceId, TenantContext.currentTenant(), CompanyContext.currentCompany());
        if (rows.isEmpty()) throw new NotFoundException("Resource not found");
        return rows.getFirst();
    }

    @Transactional(readOnly = true)
    public List<VersionView> versions(UUID resourceId) {
        get(resourceId);
        return db.query("""
            SELECT v.id,v.version_no,v.title,v.original_file_name,v.content_type,v.size_bytes,
                   v.status,v.index_status,v.index_error_code,v.published_at
            FROM resource_versions v JOIN resources r ON r.id=v.resource_id
            WHERE v.resource_id=? AND v.tenant_id=? AND v.company_id=?
              AND r.tenant_id=? AND r.company_id=? AND v.status IN ('PUBLISHED','HISTORICAL')
            ORDER BY v.version_no DESC
            """, this::versionRow, resourceId, TenantContext.currentTenant(), CompanyContext.currentCompany(),
            TenantContext.currentTenant(), CompanyContext.currentCompany());
    }

    @Transactional(readOnly = true)
    public AttachmentService.AttachmentDownload download(UUID resourceId, UUID versionId) {
        UUID attachmentId = db.query("""
            SELECT v.attachment_id FROM resource_versions v JOIN resources r ON r.id=v.resource_id
            WHERE r.id=? AND v.id=? AND r.tenant_id=? AND r.company_id=?
              AND v.tenant_id=? AND v.company_id=?
              AND r.status IN ('PUBLISHED','ARCHIVED') AND v.status IN ('PUBLISHED','HISTORICAL')
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            resourceId, versionId, TenantContext.currentTenant(), CompanyContext.currentCompany(),
            TenantContext.currentTenant(), CompanyContext.currentCompany());
        if (attachmentId == null) throw new NotFoundException("Resource version not found");
        return attachments.download(attachmentId);
    }

    @Transactional
    public ResourceView archive(UUID resourceId) {
        requireOwnerOrAdmin(resourceId, true);
        db.update("UPDATE resource_versions SET status='HISTORICAL' WHERE id=(SELECT current_version_id FROM resources WHERE id=?)", resourceId);
        db.update("UPDATE resources SET status='ARCHIVED',updated_at=CURRENT_TIMESTAMP WHERE id=?", resourceId);
        knowledge.visibility(resourceId, bumpRevision());
        return get(resourceId);
    }

    @Transactional
    public ResourceView reindex(UUID resourceId, UUID versionId) {
        requireOwnerOrAdmin(resourceId, true);
        List<String> statuses = db.query("""
            SELECT index_status FROM resource_versions WHERE id=? AND resource_id=? AND tenant_id=? AND company_id=?
              AND status IN ('PUBLISHED','HISTORICAL') FOR UPDATE
            """, (rs, n) -> rs.getString(1), versionId, resourceId, TenantContext.currentTenant(), CompanyContext.currentCompany());
        if (statuses.isEmpty()) throw new NotFoundException("Resource version not found");
        if (List.of("PENDING", "RUNNING").contains(statuses.getFirst())) return get(resourceId);
        db.update("""
            UPDATE resource_knowledge_events SET status='SUPERSEDED',lease_token=NULL,lease_until=NULL
            WHERE version_id=? AND kind='INDEX' AND status IN ('PENDING','WAITING','FAILED')
            """, versionId);
        db.update("UPDATE resource_versions SET index_generation=index_generation+1,index_status='PENDING',index_error_code=NULL WHERE id=?", versionId);
        long revision = bumpRevision();
        knowledge.visibility(resourceId, revision);
        knowledge.index(versionId, revision);
        return get(resourceId);
    }

    private long bumpRevision() {
        db.update("""
            INSERT INTO resource_knowledge_revisions(tenant_id,company_id,revision) VALUES (?,?,1)
            ON CONFLICT (tenant_id,company_id) DO UPDATE SET revision=resource_knowledge_revisions.revision+1
            """, TenantContext.currentTenant(), CompanyContext.currentCompany());
        return db.queryForObject("SELECT revision FROM resource_knowledge_revisions WHERE tenant_id=? AND company_id=?",
            Long.class, TenantContext.currentTenant(), CompanyContext.currentCompany());
    }

    private void requireQuota(long newBytes, boolean newResource) {
        if (newResource) {
            Long owned = db.queryForObject("""
                SELECT count(*) FROM resources WHERE tenant_id=? AND company_id=? AND owner_id=?
                """, Long.class, TenantContext.currentTenant(), CompanyContext.currentCompany(), owner());
            if (owned != null && owned >= maxFilesPerUser) throw new BadRequestException("Resource upload count limit reached");
        }
        Long used = db.queryForObject("""
            SELECT COALESCE(sum(v.size_bytes),0) FROM resource_versions v
            WHERE v.tenant_id=? AND v.company_id=?
            """, Long.class, TenantContext.currentTenant(), CompanyContext.currentCompany());
        if (used != null && (newBytes > maxCompanyBytes || used > maxCompanyBytes - newBytes))
            throw new BadRequestException("Company resource storage limit reached");
    }

    private void requireOwnerOrAdmin(UUID resourceId, boolean lock) {
        UUID resourceOwner = db.query("""
            SELECT owner_id FROM resources WHERE id=? AND tenant_id=? AND company_id=? AND status='PUBLISHED'
            """ + (lock ? " FOR UPDATE" : ""), rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            resourceId, TenantContext.currentTenant(), CompanyContext.currentCompany());
        if (resourceOwner == null) throw new NotFoundException("Resource not found");
        if (!resourceOwner.equals(owner()) && !admin()) throw new AccessDeniedException("Resource owner or admin required");
    }

    private ResourceView resourceRow(ResultSet rs, int row) throws SQLException {
        return new ResourceView(rs.getObject("id", UUID.class), rs.getObject("version_id", UUID.class),
            rs.getString("title"), rs.getString("original_file_name"), rs.getString("content_type"),
            rs.getLong("size_bytes"), rs.getString("status"), rs.getString("index_status"),
            rs.getInt("version_no"), rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime(), rs.getString("index_error_code"),
            rs.getObject("owner_id", UUID.class).equals(owner()) || admin());
    }

    private VersionView versionRow(ResultSet rs, int row) throws SQLException {
        return new VersionView(rs.getObject("id", UUID.class), rs.getInt("version_no"), rs.getString("title"),
            rs.getString("original_file_name"), rs.getString("content_type"), rs.getLong("size_bytes"),
            rs.getString("status"), rs.getString("index_status"), rs.getTimestamp("published_at").toLocalDateTime(),
            rs.getString("index_error_code"));
    }

    private String title(String requested, String filename) {
        String value = requested == null ? "" : requested.trim();
        if (value.isEmpty() && filename != null) {
            String basename = filename.replace('\\', '/');
            basename = basename.substring(basename.lastIndexOf('/') + 1);
            int extension = basename.lastIndexOf('.');
            value = (extension > 0 ? basename.substring(0, extension) : basename).trim();
        }
        if (value.isEmpty() || value.length() > 200 || value.chars().anyMatch(Character::isISOControl))
            throw new BadRequestException("Resource title must contain 1 to 200 safe characters");
        return value;
    }

    private UUID owner() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
            || !(authentication.getCredentials() instanceof AuthenticatedTokenState state))
            throw new AccessDeniedException("Authenticated ERP user required");
        return state.userId();
    }

    private boolean admin() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .anyMatch(role -> "ROLE_ADMIN".equals(role.getAuthority()));
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
}
