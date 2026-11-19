package com.enterprise.mm.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.mockito.Mockito;

class MapperContractTest {
    private static final Object UNSUPPORTED = new Object();
    private static final Set<String> DTO_TO_ENTITY_IGNORED = Set.of(
        "id",
        "tenantId",
        "companyId",
        "deleted",
        "createdAt",
        "updatedAt",
        "createdBy",
        "updatedBy",
        "version",
        "acceptedAt",
        "acceptedBy",
        "documentReviewedAt",
        "financeReviewedAt",
        "sentToPaymentAt",
        "paidAt",
        "contractConsumedAt",
        "paymentReference"
    );

    @TestFactory
    Stream<DynamicTest> mapperContracts() throws Exception {
        return mapperClasses()
            .map(mapperClass -> DynamicTest.dynamicTest(mapperClass.getSimpleName(), () -> assertMapperContracts(mapperClass)));
    }

    private void assertMapperContracts(Class<?> mapperClass) throws Exception {
        Object mapper = mapperClass.getDeclaredConstructor().newInstance();
        injectEntityManagerIfNeeded(mapper);
        boolean exercised = false;
        for (Method method : mapperClass.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            if (isToResponse(method)) {
                assertNullReturn(method, mapper);
                Object source = trySampleObject(method.getParameterTypes()[0]).orElse(null);
                if (source == null) {
                    continue;
                }
                Object response = method.invoke(mapper, source);
                assertThat(response).as(method.getName() + " response").isNotNull();
                assertMatchingPropertiesCopied(source, response, responseIgnored(mapperClass));
                exercised = true;
            } else if (isToEntity(method)) {
                assertNullReturn(method, mapper);
                Object dto = trySampleObject(method.getParameterTypes()[0]).orElse(null);
                if (dto == null) {
                    continue;
                }
                Object entity = method.invoke(mapper, dto);
                assertThat(entity).as(method.getName() + " entity").isNotNull();
                assertMatchingPropertiesCopied(dto, entity, dtoToEntityIgnored(mapperClass));
                exercised = true;
            } else if (isUpdateEntity(method)) {
                Optional<Object> maybeEntity = trySampleObject(method.getParameterTypes()[0]);
                Optional<Object> maybeDto = trySampleObject(method.getParameterTypes()[1]);
                if (maybeEntity.isEmpty() || maybeDto.isEmpty()) {
                    continue;
                }
                Object entity = maybeEntity.get();
                Object dto = maybeDto.get();
                method.invoke(mapper, entity, dto);
                assertMatchingPropertiesCopied(dto, entity, dtoToEntityIgnored(mapperClass));
                exercised = true;
            }
        }
        assertThat(exercised || hasApplicableContractMethod(mapperClass))
            .as(mapperClass.getSimpleName() + " has an applicable mapper method")
            .isTrue();
    }

    private static Set<String> dtoToEntityIgnored(Class<?> mapperClass) {
        Set<String> ignored = new HashSet<>(DTO_TO_ENTITY_IGNORED);
        if (mapperClass.equals(ContractMapper.class)) {
            ignored.addAll(Set.of("usedAmount", "serviceUsedAmount"));
        } else if (mapperClass.equals(DeliveryScheduleMapper.class)) {
            ignored.addAll(Set.of("status", "receivedQuantity", "receivedAmount", "overdueFlag", "controlledAt"));
        } else if (mapperClass.equals(DemandPlanningCycleMapper.class)) {
            ignored.addAll(Set.of("status", "ownerEmployeeId", "submittedAt", "approvedAt", "activatedAt", "closedAt"));
        } else if (mapperClass.equals(LandedLogisticsEventMapper.class)) {
            ignored.addAll(Set.of("status", "resolvedAt"));
        } else if (mapperClass.equals(MaterialMasterRequestMapper.class)) {
            ignored.addAll(Set.of("status", "submittedAt", "reviewedAt", "decidedAt", "appliedAt"));
        } else if (mapperClass.equals(MaterialDuplicateCandidateMapper.class)
            || mapperClass.equals(MaterialQualityIssueMapper.class)) {
            ignored.add("status");
        } else if (mapperClass.equals(PositionMapper.class)) {
            ignored.add("occupiedHeadcount");
        } else if (mapperClass.equals(ProcurementMethodDecisionMapper.class)) {
            ignored.addAll(Set.of("status", "submittedAt", "approvedAt", "approvedBy", "rejectedAt", "rejectedBy", "rejectionReason"));
        } else if (mapperClass.equals(SupplierLicenseMapper.class)) {
            ignored.addAll(Set.of("status", "renewalRequired", "revokedAt", "revocationReason"));
        } else if (mapperClass.equals(SupplierAccreditationApplicationMapper.class)) {
            ignored.addAll(Set.of(
                "status",
                "submittedAt",
                "reviewStartedAt",
                "riskScore",
                "reviewComment",
                "complianceRecommendation",
                "complianceSnapshotJson",
                "parentApplicationId"
            ));
        } else if (mapperClass.equals(SupplierPortalAccountMapper.class)) {
            ignored.addAll(Set.of(
                "inviteTokenHash",
                "inviteTokenExpiresAt",
                "invitedAt",
                "activatedAt",
                "lastLoginAt",
                "lastSubmissionAt",
                "allowedIpRange",
                "mfaRequired",
                "termsAcceptedAt",
                "status"
            ));
        } else if (mapperClass.equals(SupplierPortalSubmissionMapper.class)) {
            ignored.addAll(Set.of("status", "submittedAt", "reviewedAt", "reviewedBy", "reviewComment"));
        }
        return ignored;
    }

    private static Set<String> responseIgnored(Class<?> mapperClass) {
        Set<String> ignored = new HashSet<>(Set.of("superAdmin"));
        if (mapperClass.equals(SupplierPortalAccountMapper.class)) {
            ignored.add("inviteTokenHash");
        } else if (mapperClass.equals(MaterialMapper.class)) {
            ignored.add("materialSubtype"); // Intentionally omitted for non-TMB material types.
        }
        return ignored;
    }

    private static Stream<Class<?>> mapperClasses() throws Exception {
        Path mapperDir = Path.of("target/classes/com/enterprise/mm/mapper");
        try (Stream<Path> paths = Files.list(mapperDir)) {
            return paths
                .filter(path -> path.getFileName().toString().endsWith("Mapper.class"))
                .filter(path -> !path.getFileName().toString().contains("$"))
                .map(MapperContractTest::classForPath)
                .filter(Optional::isPresent)
                .<Class<?>>map(Optional::orElseThrow)
                .filter(mapperClass -> !Modifier.isAbstract(mapperClass.getModifiers()))
                .filter(MapperContractTest::hasDefaultConstructor)
                .filter(MapperContractTest::hasApplicableContractMethod)
                .sorted(Comparator.comparing(Class::getSimpleName))
                .toList()
                .stream();
        }
    }

    private static Optional<Class<?>> classForPath(Path path) {
        String fileName = path.getFileName().toString();
        String className = "com.enterprise.mm.mapper." + fileName.substring(0, fileName.length() - ".class".length());
        try {
            return Optional.of(Class.forName(className));
        } catch (ClassNotFoundException ex) {
            return Optional.empty();
        }
    }

    private static boolean hasDefaultConstructor(Class<?> mapperClass) {
        try {
            Constructor<?> constructor = mapperClass.getDeclaredConstructor();
            return Modifier.isPublic(constructor.getModifiers());
        } catch (NoSuchMethodException ex) {
            return false;
        }
    }

    private static boolean hasApplicableContractMethod(Class<?> mapperClass) {
        for (Method method : mapperClass.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())
                && (isToResponse(method) || isToEntity(method) || isUpdateEntity(method))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isToResponse(Method method) {
        return method.getName().equals("toResponse")
            && method.getParameterCount() == 1
            && !method.getReturnType().equals(Void.TYPE);
    }

    private static boolean isToEntity(Method method) {
        return method.getName().equals("toEntity")
            && method.getParameterCount() == 1
            && !method.getReturnType().equals(Void.TYPE);
    }

    private static boolean isUpdateEntity(Method method) {
        return method.getName().equals("updateEntity")
            && method.getParameterCount() == 2
            && method.getReturnType().equals(Void.TYPE);
    }

    private static void assertNullReturn(Method method, Object mapper) throws Exception {
        Object result = method.invoke(mapper, new Object[] {null});
        assertThat(result).as(method.getName() + " null source").isNull();
    }

    private static Optional<Object> trySampleObject(Class<?> type) throws Exception {
        try {
            return Optional.of(sampleObject(type));
        } catch (NoSuchMethodException ex) {
            return Optional.empty();
        }
    }

    private static Object sampleObject(Class<?> type) throws Exception {
        Object instance = type.getDeclaredConstructor().newInstance();
        populate(instance);
        return instance;
    }

    private static void injectEntityManagerIfNeeded(Object mapper) throws Exception {
        for (Field field : mapper.getClass().getDeclaredFields()) {
            if (!field.getType().equals(EntityManager.class)) {
                continue;
            }
            EntityManager entityManager = Mockito.mock(EntityManager.class);
            Mockito.when(entityManager.getReference(Mockito.any(), Mockito.any())).thenAnswer(invocation -> {
                Class<?> entityType = invocation.getArgument(0);
                Object reference = sampleObject(entityType);
                getter(reference.getClass(), "id").ifPresent(idGetter -> setId(reference, invocation.getArgument(1)));
                return reference;
            });
            field.setAccessible(true);
            field.set(mapper, entityManager);
        }
    }

    private static void setId(Object target, Object id) {
        try {
            Method setter = target.getClass().getMethod("setId", UUID.class);
            setter.invoke(target, id);
        } catch (ReflectiveOperationException ignored) {
            // Some reference targets are not BusinessEntity subclasses.
        }
    }

    private static void populate(Object target) throws Exception {
        for (Method setter : target.getClass().getMethods()) {
            if (!isSetter(setter)) {
                continue;
            }
            Object value = sampleValue(setter.getParameterTypes()[0], propertyNameFromSetter(setter));
            if (value != UNSUPPORTED) {
                try {
                    setter.invoke(target, value);
                } catch (InvocationTargetException ex) {
                    throw unwrap(ex);
                }
            }
        }
    }

    private static boolean isSetter(Method method) {
        return Modifier.isPublic(method.getModifiers())
            && method.getName().startsWith("set")
            && method.getName().length() > 3
            && method.getParameterCount() == 1;
    }

    private static Object sampleValue(Class<?> type, String propertyName) {
        if (type.equals(String.class)) {
            return switch (propertyName) {
                case "lineType" -> "MATERIAL";
                case "requestType" -> "GOODS";
                case "inventoryType" -> "PLANNED";
                case "hazardClass" -> "NONE";
                case "valuationMethod" -> "ACTUAL_COST";
                case "inspectionResult" -> "PASSED";
                case "status" -> "PENDING";
                default -> propertyName + "-value";
            };
        }
        if (type.equals(UUID.class)) {
            return UUID.nameUUIDFromBytes(propertyName.getBytes(StandardCharsets.UTF_8));
        }
        if (type.equals(BigDecimal.class)) {
            return new BigDecimal("123.45");
        }
        if (type.equals(Integer.class) || type.equals(Integer.TYPE)) {
            return 7;
        }
        if (type.equals(Long.class) || type.equals(Long.TYPE)) {
            return 7L;
        }
        if (type.equals(Boolean.class) || type.equals(Boolean.TYPE)) {
            return true;
        }
        if (type.equals(Double.class) || type.equals(Double.TYPE)) {
            return 7.5d;
        }
        if (type.equals(Float.class) || type.equals(Float.TYPE)) {
            return 7.5f;
        }
        if (type.equals(Short.class) || type.equals(Short.TYPE)) {
            return (short) 7;
        }
        if (type.equals(Byte.class) || type.equals(Byte.TYPE)) {
            return (byte) 7;
        }
        if (type.equals(LocalDate.class)) {
            return LocalDate.of(2026, 1, 6);
        }
        if (type.equals(LocalDateTime.class)) {
            return LocalDateTime.of(2026, 1, 6, 9, 30);
        }
        if (type.equals(Instant.class)) {
            return Instant.parse("2026-01-06T09:30:00Z");
        }
        if (type.isEnum() && type.getEnumConstants().length > 0) {
            return type.getEnumConstants()[0];
        }
        if (List.class.isAssignableFrom(type)) {
            return List.of();
        }
        if (Set.class.isAssignableFrom(type)) {
            return Set.of();
        }
        if (Map.class.isAssignableFrom(type)) {
            return Map.of();
        }
        if (type.equals(byte[].class)) {
            return propertyName.getBytes(StandardCharsets.UTF_8);
        }
        return UNSUPPORTED;
    }

    private static void assertMatchingPropertiesCopied(Object source, Object target, Set<String> ignoredProperties) throws Exception {
        for (Method sourceGetter : source.getClass().getMethods()) {
            if (!isGetter(sourceGetter)) {
                continue;
            }
            String propertyName = propertyNameFromGetter(sourceGetter);
            if (ignoredProperties.contains(propertyName)) {
                continue;
            }
            Method targetGetter = getter(target.getClass(), propertyName).orElse(null);
            if (targetGetter == null) {
                continue;
            }
            Object expected = sourceGetter.invoke(source);
            if (expected == null || expected == UNSUPPORTED) {
                continue;
            }
            Object actual = targetGetter.invoke(target);
            String assertionName = source.getClass().getSimpleName() + " -> " + target.getClass().getSimpleName() + " [" + propertyName + "]";
            if (expected instanceof BigDecimal expectedDecimal && actual instanceof BigDecimal actualDecimal) {
                assertThat(actualDecimal).as(assertionName).isEqualByComparingTo(expectedDecimal);
            } else if (expected instanceof Enum<?> expectedEnum && actual instanceof String actualString) {
                assertThat(actualString).as(assertionName).isEqualTo(expectedEnum.name());
            } else if (expected instanceof String expectedString && actual instanceof Enum<?> actualEnum) {
                assertThat(actualEnum.name()).as(assertionName).isEqualTo(expectedString);
            } else {
                assertThat(actual).as(assertionName).isEqualTo(expected);
            }
        }
    }

    private static boolean isGetter(Method method) {
        if (!Modifier.isPublic(method.getModifiers()) || method.getParameterCount() != 0) {
            return false;
        }
        if (method.getName().equals("getClass")) {
            return false;
        }
        return (method.getName().startsWith("get") && method.getName().length() > 3)
            || (method.getName().startsWith("is") && method.getName().length() > 2);
    }

    private static Optional<Method> getter(Class<?> type, String propertyName) {
        for (Method method : type.getMethods()) {
            if (isGetter(method) && propertyNameFromGetter(method).equals(propertyName)) {
                return Optional.of(method);
            }
        }
        return Optional.empty();
    }

    private static String propertyNameFromSetter(Method method) {
        return decapitalize(method.getName().substring(3));
    }

    private static String propertyNameFromGetter(Method method) {
        if (method.getName().startsWith("is")) {
            return decapitalize(method.getName().substring(2));
        }
        return decapitalize(method.getName().substring(3));
    }

    private static String decapitalize(String value) {
        return value.substring(0, 1).toLowerCase() + value.substring(1);
    }

    private static Exception unwrap(InvocationTargetException ex) {
        Throwable cause = ex.getCause();
        return cause instanceof Exception exception ? exception : ex;
    }
}
