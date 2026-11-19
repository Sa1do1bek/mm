package com.enterprise.mm.service;

import com.enterprise.mm.entity.MXIK;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.MXIKPathResponse;
import com.enterprise.mm.payload.MXIKSearchResponse;
import com.enterprise.mm.payload.mxikImport.MXIKNameCodeCountProjection;
import com.enterprise.mm.payload.mxikImport.MXIKNameCountProjection;
import com.enterprise.mm.repository.MXIKRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MXIKProductImportService {
    private final MXIKRepository repository;

    @Transactional(readOnly = true)
    public Page<MXIKNameCountProjection> listGroupCounts(Pageable pageable) {
        return isRussian() ? repository.findGroupCountsRu(pageable) : repository.findGroupCounts(pageable);
    }

    @Transactional(readOnly = true)
    public List<MXIKNameCountProjection> listClassCounts(String groupName) {
        return isRussian() ? repository.findClassCountsRu(groupName) : repository.findClassCounts(groupName);
    }

    @Transactional(readOnly = true)
    public List<MXIKNameCountProjection> listPositionCounts(String className) {
        return isRussian() ? repository.findPositionCountsRu(className) : repository.findPositionCounts(className);
    }

    @Transactional(readOnly = true)
    public List<MXIKNameCountProjection> listSubPositionCounts(String positionName) {
        return isRussian() ? repository.findSubPositionCountsRu(positionName) : repository.findSubPositionCounts(positionName);
    }

    @Transactional(readOnly = true)
    public List<MXIKNameCodeCountProjection> listMxikCounts(String subPositionName) {
        return isRussian() ? repository.findMxikCountsRu(subPositionName) : repository.findMxikCounts(subPositionName);
    }

    public Page<MXIKSearchResponse> mxikSearchName(String name,int page, int size) {
        Pageable pageable = PageRequest.of(page, size);

        Page<MXIK> mxikPage = repository.findByName(name, pageable);

        if (mxikPage.isEmpty()) {
            throw new NotFoundException("MXIK not found");
        }

        return mxikPage.map(this::buildPath);

    }
    private MXIKSearchResponse buildPath(MXIK mxik) {

        String kod = mxik.getKod();

        List<MXIKPathResponse> path = new ArrayList<>();

        if (mxik.getGroupName() != null) {
            path.add(new MXIKPathResponse(
                    kod.substring(0, 3),
                    localized(mxik.getGroupNameRu(), mxik.getGroupName()),
                    "GROUP"
            ));
        }

        if (mxik.getClassName() != null) {
            path.add(new MXIKPathResponse(
                    kod.substring(0, 5),
                    localized(mxik.getClassNameRu(), mxik.getClassName()),
                    "CLASS"
            ));
        }

        if (mxik.getPositionName() != null) {
            path.add(new MXIKPathResponse(
                    kod.substring(0, 8),
                    localized(mxik.getPositionNameRu(), mxik.getPositionName()),
                    "POSITION"
            ));
        }

        if (mxik.getSubPositionName() != null) {
            path.add(new MXIKPathResponse(
                    kod.substring(0, 11),
                    localized(mxik.getSubPositionNameRu(), mxik.getSubPositionName()),
                    "SUB_POSITION"
            ));
        }

        path.add(new MXIKPathResponse(
                mxik.getKod(),
                localized(mxik.getNameRu(), mxik.getName()),
                "MXIK"
        ));


        return new MXIKSearchResponse(
                mxik.getId(), mxik.getKod(), localized(mxik.getNameRu(), mxik.getName()),
                path
        );
    }

    private boolean isRussian() {
        return "ru".equalsIgnoreCase(LocaleContextHolder.getLocale().getLanguage());
    }

    private String localized(String russian, String fallback) {
        return isRussian() && russian != null && !russian.isBlank() ? russian : fallback;
    }
}
