package com.enterprise.mm.service.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.config.AttachmentProperties;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.service.attachment.AttachmentStorage;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.InputStreamResource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RfqArtifactStagingServiceTest {
    @TempDir java.nio.file.Path tempDirectory;

    @Test
    void failedPromotionLeavesNoStoredOrDownloadableAttachment() {
        AttachmentRepository attachmentRepository = mock(AttachmentRepository.class);
        FailsOnSecondStore storage = new FailsOnSecondStore();
        AttachmentProperties properties = new AttachmentProperties();
        properties.setStorageProvider("TEST");
        RfqArtifactStagingService staging = new RfqArtifactStagingService(
            new RfqPdfRenderer(),
            new RfqExcelRenderer(),
            attachmentRepository,
            properties,
            List.of(storage),
            tempDirectory
        );
        RfqArtifactStagingService.StagedPackage staged = staging.stage(
            RfqPackageParityTest.snapshot(List.of())
        );

        assertThat(staged.pdf()).exists();
        assertThat(staged.excel()).exists();
        assertThatThrownBy(() -> staging.promote(staged, java.util.UUID.randomUUID()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Cannot promote RFQ package");
        assertThat(storage.keys).isEmpty();
        verifyNoInteractions(attachmentRepository);
    }

    @Test
    void transactionRollbackDeletesBothPromotedFiles() {
        AttachmentRepository attachmentRepository = mock(AttachmentRepository.class);
        SuccessfulStorage storage = new SuccessfulStorage();
        AttachmentProperties properties = new AttachmentProperties();
        properties.setStorageProvider("TEST");
        RfqArtifactStagingService staging = new RfqArtifactStagingService(
            new RfqPdfRenderer(),
            new RfqExcelRenderer(),
            attachmentRepository,
            properties,
            List.of(storage),
            tempDirectory
        );
        when(attachmentRepository.saveAll(org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> {
            List<com.enterprise.mm.entity.Attachment> attachments = invocation.getArgument(0);
            attachments.forEach(attachment -> attachment.setId(UUID.randomUUID()));
            return attachments;
        });
        RfqArtifactStagingService.StagedPackage staged = staging.stage(
            RfqPackageParityTest.snapshot(List.of())
        );

        TransactionSynchronizationManager.initSynchronization();
        try {
            staging.promote(staged, UUID.randomUUID());
            assertThat(storage.keys).hasSize(2);
            TransactionSynchronizationManager.getSynchronizations().forEach(synchronization ->
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
            );
            assertThat(storage.keys).isEmpty();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static final class FailsOnSecondStore implements AttachmentStorage {
        private final Set<String> keys = new LinkedHashSet<>();
        private int calls;

        @Override
        public boolean supports(String provider) {
            return "TEST".equals(provider);
        }

        @Override
        public StoredFile store(
            org.springframework.web.multipart.MultipartFile file,
            String storageKey,
            String contentType
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoredFile store(byte[] content, String storageKey, String contentType) {
            calls++;
            if (calls == 2) {
                throw new BusinessException("simulated storage failure");
            }
            keys.add(storageKey);
            return new StoredFile(content.length, "checksum");
        }

        @Override
        public InputStreamResource read(String storageKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String storageKey) {
            keys.remove(storageKey);
        }
    }

    private static final class SuccessfulStorage implements AttachmentStorage {
        private final Set<String> keys = new LinkedHashSet<>();

        @Override
        public boolean supports(String provider) {
            return "TEST".equals(provider);
        }

        @Override
        public StoredFile store(
            org.springframework.web.multipart.MultipartFile file,
            String storageKey,
            String contentType
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoredFile store(byte[] content, String storageKey, String contentType) {
            keys.add(storageKey);
            return new StoredFile(content.length, "checksum");
        }

        @Override
        public InputStreamResource read(String storageKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String storageKey) {
            keys.remove(storageKey);
        }
    }
}
