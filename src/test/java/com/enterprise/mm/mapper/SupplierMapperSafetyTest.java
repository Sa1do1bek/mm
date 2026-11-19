package com.enterprise.mm.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierBankAccount;
import com.enterprise.mm.payload.SupplierBankAccountDto;
import com.enterprise.mm.payload.SupplierCreateRequest;
import com.enterprise.mm.payload.SupplierUpdateRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupplierMapperSafetyTest {

    private final SupplierMapper mapper = new SupplierMapper();

    @Test
    void mapsWorkspaceProfileFieldsWithoutConflatingRelationshipAndBranchType() {
        UUID ownerId = UUID.randomUUID();
        SupplierCreateRequest request = new SupplierCreateRequest();
        request.setLegalName("Internal Repairs Unit");
        request.setRelationshipType("INTERNAL");
        request.setSupplierType("BRANCH");
        request.setOwnerEmployeeId(ownerId);
        request.setPrimaryContactName("Nodira Karimova");
        request.setCountryCode("UZB");
        request.setCity("Tashkent");
        request.setLegalForm("LLC");

        Supplier entity = mapper.toEntity(request);

        assertThat(entity.getRelationshipType()).isEqualTo("INTERNAL");
        assertThat(entity.getSupplierType()).isEqualTo("BRANCH");
        assertThat(entity.getOwnerEmployeeId()).isEqualTo(ownerId);
    }

    @Test
    void partialUpdatePreservesOmittedAndWorkflowControlledFields() {
        Supplier entity = new Supplier();
        entity.setCode("SUP-001");
        entity.setName("Original supplier");
        entity.setStatus("ACTIVE");
        entity.setCategory("A");
        entity.setRating(new BigDecimal("92.50"));
        entity.setBlockedReason("Controlled by qualification workflow");
        entity.setLegalName("Original legal name");
        entity.setShortName("Original short name");
        entity.setRegistrationNumber("REG-001");

        SupplierUpdateRequest request = new SupplierUpdateRequest();
        request.setLegalName("Updated legal name");
        request.setCode("ATTACKER-CODE");
        request.setStatus("BLOCKED");
        request.setCategory("D");
        request.setRating(BigDecimal.ZERO);
        request.setBlockedReason("Client controlled reason");

        mapper.updateEntity(entity, request);

        assertThat(entity.getLegalName()).isEqualTo("Updated legal name");
        assertThat(entity.getShortName()).isEqualTo("Original short name");
        assertThat(entity.getRegistrationNumber()).isEqualTo("REG-001");
        assertThat(entity.getCode()).isEqualTo("SUP-001");
        assertThat(entity.getStatus()).isEqualTo("ACTIVE");
        assertThat(entity.getCategory()).isEqualTo("A");
        assertThat(entity.getRating()).isEqualByComparingTo("92.50");
        assertThat(entity.getBlockedReason()).isEqualTo("Controlled by qualification workflow");
    }

    @Test
    void explicitBlankStillClearsAnEditableTextField() {
        Supplier entity = new Supplier();
        entity.setActualAddress("Old address");
        SupplierUpdateRequest request = new SupplierUpdateRequest();
        request.setActualAddress("");

        mapper.updateEntity(entity, request);

        assertThat(entity.getActualAddress()).isEmpty();
    }

    @Test
    void bankAccountUpdateReusesExistingChildEntityWhenAccountNumberIsUnchanged() {
        Supplier entity = new Supplier();
        SupplierBankAccount existingAccount = new SupplierBankAccount();
        existingAccount.setSupplier(entity);
        existingAccount.setBankName("Original Bank");
        existingAccount.setBankAccount("20208000123456789001");
        existingAccount.setPrimaryAccount(true);
        entity.getBankAccounts().add(existingAccount);

        SupplierBankAccountDto accountUpdate = new SupplierBankAccountDto();
        accountUpdate.setBankName("Updated Bank");
        accountUpdate.setBankAccount("20208 000 123456789001");
        accountUpdate.setPrimaryAccount(true);
        SupplierUpdateRequest request = new SupplierUpdateRequest();
        request.setBankAccounts(List.of(accountUpdate));

        mapper.updateEntity(entity, request);

        assertThat(entity.getBankAccounts()).containsExactly(existingAccount);
        assertThat(entity.getBankAccounts().getFirst().getBankName()).isEqualTo("Updated Bank");
        assertThat(entity.getBankAccounts().getFirst().getBankAccount())
            .isEqualTo("20208 000 123456789001");
    }
}
