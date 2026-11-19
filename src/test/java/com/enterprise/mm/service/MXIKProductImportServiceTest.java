package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.MXIK;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.MXIKSearchResponse;
import com.enterprise.mm.payload.mxikImport.MXIKNameCountProjection;
import com.enterprise.mm.repository.MXIKRepository;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class MXIKProductImportServiceTest {

    private MXIKRepository repository;
    private MXIKProductImportService service;

    @BeforeEach
    void setUp() {
        repository = mock(MXIKRepository.class);
        service = new MXIKProductImportService(repository);
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void russianCatalogUsesLocalizedHierarchyQueries() {
        PageRequest pageable = PageRequest.of(0, 20);
        Page<MXIKNameCountProjection> page = new PageImpl<>(List.of(), pageable, 0);
        when(repository.findGroupCountsRu(pageable)).thenReturn(page);
        LocaleContextHolder.setLocale(new Locale("ru"));

        service.listGroupCounts(pageable);

        verify(repository).findGroupCountsRu(pageable);
    }

    @Test
    void mxikSearchName_returnsMappedPage() {
        MXIK mxik = new MXIK();
        mxik.setId(1L);
        mxik.setKod("01234567890123456");
        mxik.setName("Test MXIK");
        mxik.setGroupName("Group");
        mxik.setClassName("Class");
        mxik.setPositionName("Position");
        mxik.setSubPositionName("SubPosition");

        Page<MXIK> page = new PageImpl<>(List.of(mxik), PageRequest.of(0, 10), 1);
        when(repository.findByName(eq("Test"), any(PageRequest.class))).thenReturn(page);

        Page<MXIKSearchResponse> result = service.mxikSearchName("Test", 0, 10);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).kod()).isEqualTo("01234567890123456");
        assertThat(result.getContent().get(0).name()).isEqualTo("Test MXIK");
        assertThat(result.getContent().get(0).path()).hasSize(5);
    }

    @Test
    void mxikSearchName_returnsRussianPathForRussianLocale() {
        MXIK mxik = new MXIK();
        mxik.setId(1L);
        mxik.setKod("01234567890123456");
        mxik.setName("Uzbek name");
        mxik.setNameRu("Русское имя");
        mxik.setGroupName("Uzbek group");
        mxik.setGroupNameRu("Русская группа");
        mxik.setClassName("Uzbek class");
        mxik.setClassNameRu("Русский класс");
        mxik.setPositionName("Uzbek position");
        mxik.setPositionNameRu("Русская позиция");
        mxik.setSubPositionName("Uzbek sub-position");
        mxik.setSubPositionNameRu("Русская субпозиция");
        Page<MXIK> page = new PageImpl<>(List.of(mxik), PageRequest.of(0, 10), 1);
        when(repository.findByName(eq("Русское"), any(PageRequest.class))).thenReturn(page);
        LocaleContextHolder.setLocale(new Locale("ru"));

        MXIKSearchResponse result = service.mxikSearchName("Русское", 0, 10).getContent().get(0);

        assertThat(result.name()).isEqualTo("Русское имя");
        assertThat(result.path()).extracting(path -> path.name())
            .containsExactly("Русская группа", "Русский класс", "Русская позиция", "Русская субпозиция", "Русское имя");
    }

    @Test
    void mxikSearchName_throwsNotFoundException_whenEmpty() {
        Page<MXIK> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(repository.findByName(eq("Unknown"), any(PageRequest.class))).thenReturn(emptyPage);

        assertThatThrownBy(() -> service.mxikSearchName("Unknown", 0, 10))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("MXIK not found");
    }
}
