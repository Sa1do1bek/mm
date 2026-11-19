package com.enterprise.mm.warehouse.custody;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.error.BadRequestException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Validates ownership, category, and accounting correspondence for warehouse stock.
 * Enforces strict separation between Company-owned goods (balance-sheet 10xx)
 * and third-party custody goods (off-balance 002).
 */
@Service
public class OwnerEligibilityService {

    public OwnerEligibilityService() {
    }

    /**
     * Validates that the owner ID, stock category, and account code form an authorized combination.
     * Prevents cross-contamination between owned and custody materials.
     */
    public void validateEligibility(UUID ownerId, String stockCategory, String accountCode) {
        String category = stockCategory != null ? stockCategory.trim().toUpperCase() : "OWNED";
        String account = accountCode != null ? accountCode.trim() : null;

        boolean isCustodyCategory = "CUSTODY".equals(category);
        boolean isCustodyAccount = account != null && account.startsWith("002");

        if (isCustodyCategory) {
            if (account != null && !isCustodyAccount && !"1070".equals(account)) {
                throw new BadRequestException(
                    "Custody stock category cannot be on balance-sheet account: " + account
                    + ". Off-balance custody account (002) is required."
                );
            }
            if (ownerId == null) {
                throw new BadRequestException("Custody stock operations require a non-null third-party owner ID");
            }
            UUID currentCompany = CompanyContext.currentCompany();
            if (currentCompany != null && currentCompany.equals(ownerId)) {
                throw new BadRequestException(
                    "Company cannot be the third-party owner of custody stock. Custody stock must belong to an external party."
                );
            }
        }

        if ("OWNED".equals(category) || "OWNED_MATERIAL".equals(category)) {
            if (isCustodyAccount) {
                throw new BadRequestException(
                    "Owned stock category cannot be assigned to off-balance custody account: " + account
                );
            }
        }
    }

    public boolean isCustody(String stockCategory, String accountCode) {
        String category = stockCategory != null ? stockCategory.trim().toUpperCase() : "";
        String account = accountCode != null ? accountCode.trim() : "";
        return "CUSTODY".equals(category) || account.startsWith("002");
    }

    public boolean isOwned(String stockCategory, String accountCode) {
        String category = stockCategory != null ? stockCategory.trim().toUpperCase() : "";
        String account = accountCode != null ? accountCode.trim() : "";
        return "OWNED".equals(category) || "OWNED_MATERIAL".equals(category) || account.startsWith("10");
    }
}
