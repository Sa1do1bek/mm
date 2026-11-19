package com.enterprise.mm.security;

import com.enterprise.mm.payload.CountryCodeProjection;
import com.enterprise.mm.repository.CountryCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CountryCodeService {
    private final CountryCodeRepository countryCodeRepository;

    public Page<CountryCodeProjection> getAll(String search, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size));
        return countryCodeRepository.getAll(search, pageable);
    }

    public Page<CountryCodeProjection> getAllAndDeletedFalse(String search, int page, int size) {
        return countryCodeRepository.getAllAndDeletedFalse(
            search,
            PageRequest.of(Math.max(page, 0), normalizeSize(size))
        );
    }

    private int normalizeSize(int size) {
        return size > 0 ? Math.min(size, 200) : 10;
    }
}
