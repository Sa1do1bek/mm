package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.MXIK;
import com.enterprise.mm.repository.MXIKRepository;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

class MXIKServiceTest {

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void russianResponseUsesRussianCatalogName() {
        MXIKRepository repository = mock(MXIKRepository.class);
        MXIK entity = new MXIK();
        entity.setKod("00101001001000000");
        entity.setName("Tirik otlar");
        entity.setNameRu("Живые лошади");
        entity.setType("GOODS");
        when(repository.findByKodAndTypeNotNull(entity.getKod())).thenReturn(Optional.of(entity));
        LocaleContextHolder.setLocale(new Locale("ru"));

        MXIKService service = new MXIKService(repository);

        var response = service.getMXIKByCode(entity.getKod());
        assertThat(response.getName()).isEqualTo("Живые лошади");
        assertThat(response.getNameRu()).isEqualTo("Живые лошади");
    }

    @Test
    void russianResponseFallsBackToUzbekWhenTranslationIsMissing() {
        MXIKRepository repository = mock(MXIKRepository.class);
        MXIK entity = new MXIK();
        entity.setKod("00101001001000001");
        entity.setName("Tirik otlar");
        entity.setType("GOODS");
        when(repository.findByKodAndTypeNotNull(entity.getKod())).thenReturn(Optional.of(entity));
        LocaleContextHolder.setLocale(new Locale("ru"));

        MXIKService service = new MXIKService(repository);

        assertThat(service.getMXIKByCode(entity.getKod()).getName()).isEqualTo("Tirik otlar");
    }
}
