package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.payload.ApiResponse;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;

class ControllerPageResponseContractTest {
    private static final String CONTROLLER_PACKAGE = "com.enterprise.mm.controller";

    @Test
    void getEndpointsDoNotReturnPlainLists() throws Exception {
        List<String> plainListEndpoints = new ArrayList<>();

        for (Class<?> controllerClass : controllerClasses()) {
            for (var method : controllerClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(GetMapping.class) && hasPageableParameter(method.getParameterTypes()) && returnsApiResponseOfList(method.getGenericReturnType())) {
                    plainListEndpoints.add(controllerClass.getSimpleName() + "#" + method.getName());
                }
            }
        }

        assertThat(plainListEndpoints)
            .as("GET endpoints must return PageResponse instead of plain List so page metadata is present")
            .isEmpty();
    }

    private static List<Class<?>> controllerClasses() throws Exception {
        String packagePath = CONTROLLER_PACKAGE.replace('.', '/');
        URI controllerUri = ClassLoader.getSystemResource(packagePath).toURI();
        Path controllerDirectory = Path.of(controllerUri);
        List<Class<?>> classes = new ArrayList<>();

        try (var paths = Files.walk(controllerDirectory)) {
            paths.filter(path -> path.toString().endsWith(".class"))
                .filter(path -> !path.getFileName().toString().contains("$"))
                .forEach(path -> classes.add(loadControllerClass(controllerDirectory, path)));
        }

        return classes;
    }

    private static Class<?> loadControllerClass(Path controllerDirectory, Path classFile) {
        Path relative = controllerDirectory.relativize(classFile);
        String simpleName = relative.toString()
            .replace('/', '.')
            .replace('\\', '.')
            .replaceAll("\\.class$", "");
        try {
            return Class.forName(CONTROLLER_PACKAGE + "." + simpleName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Could not load controller class " + simpleName, e);
        }
    }

    private static boolean returnsApiResponseOfList(Type returnType) {
        if (!(returnType instanceof ParameterizedType apiResponseType)) {
            return false;
        }
        if (!ApiResponse.class.equals(apiResponseType.getRawType())) {
            return false;
        }
        Type dataType = apiResponseType.getActualTypeArguments()[0];
        return dataType instanceof ParameterizedType parameterizedDataType
            && List.class.equals(parameterizedDataType.getRawType());
    }

    private static boolean hasPageableParameter(Class<?>[] parameterTypes) {
        for (Class<?> parameterType : parameterTypes) {
            if (Pageable.class.equals(parameterType)) {
                return true;
            }
        }
        return false;
    }
}
