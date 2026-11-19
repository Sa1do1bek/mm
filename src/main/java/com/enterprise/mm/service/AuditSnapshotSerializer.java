package com.enterprise.mm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditSnapshotSerializer {
    private static final int MAX_DEPTH = 3;
    private static final int MAX_COLLECTION_ITEMS = 1000;
    private static final String REDACTED = "[REDACTED]";
    private static final Set<String> SECRET_FIELDS = Set.of(
        "password",
        "passwordhash",
        "oldpassword",
        "newpassword",
        "confirmnewpassword",
        "token",
        "accesstoken",
        "refreshtoken",
        "authorization",
        "authorizationheader"
    );

    private final ObjectMapper objectMapper;

    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return writeNode(redactSecrets(objectMapper.valueToTree(value)));
        } catch (Exception ignored) {
            return writeNode(redactSecrets(toAuditNode(value, new IdentityHashMap<>(), 0)));
        }
    }

    public String diff(Object oldValue, Object newValue) {
        JsonNode oldNode = toNode(oldValue);
        JsonNode newNode = toNode(newValue);
        if (Objects.equals(oldNode, newNode)) {
            return "{}";
        }
        if (!oldNode.isObject() || !newNode.isObject()) {
            ObjectNode diff = objectMapper.createObjectNode();
            diff.set("value", change(oldNode, newNode));
            return writeNode(diff);
        }

        ObjectNode diff = objectMapper.createObjectNode();
        Set<String> fields = new HashSet<>();
        oldNode.fieldNames().forEachRemaining(fields::add);
        newNode.fieldNames().forEachRemaining(fields::add);
        for (String field : fields) {
            JsonNode before = oldNode.get(field);
            JsonNode after = newNode.get(field);
            if (!Objects.equals(before, after)) {
                diff.set(field, change(before, after));
            }
        }
        return writeNode(diff);
    }

    private JsonNode toNode(Object value) {
        if (value == null) {
            return objectMapper.createObjectNode();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return redactSecrets(objectMapper.readTree(text));
            } catch (Exception ignored) {
                return objectMapper.valueToTree(text);
            }
        }
        return redactSecrets(toAuditNode(value, new IdentityHashMap<>(), 0));
    }

    private ObjectNode change(JsonNode oldValue, JsonNode newValue) {
        ObjectNode change = objectMapper.createObjectNode();
        change.set("old", oldValue == null ? objectMapper.nullNode() : oldValue);
        change.set("new", newValue == null ? objectMapper.nullNode() : newValue);
        return change;
    }

    private String writeNode(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    private JsonNode toAuditNode(Object value, IdentityHashMap<Object, Boolean> visited, int depth) {
        if (value == null) {
            return objectMapper.nullNode();
        }
        if (value instanceof JsonNode node) {
            return redactSecrets(node.deepCopy());
        }
        Class<?> type = unwrapProxyClass(value.getClass());
        if (isSimpleType(type)) {
            return objectMapper.valueToTree(value);
        }
        if (depth >= MAX_DEPTH || visited.containsKey(value)) {
            return toReferenceNode(value);
        }
        visited.put(value, Boolean.TRUE);
        try {
            if (type.isArray()) {
                return toArrayNode(value, visited, depth + 1);
            }
            if (value instanceof Iterable<?> iterable) {
                return toIterableNode(iterable, visited, depth + 1);
            }
            if (value instanceof Map<?, ?> map) {
                return toMapNode(map, visited, depth + 1);
            }
            return toBeanNode(value, type, visited, depth + 1);
        } finally {
            visited.remove(value);
        }
    }

    private JsonNode toBeanNode(Object bean, Class<?> beanType, IdentityHashMap<Object, Boolean> visited, int depth) {
        ObjectNode node = objectMapper.createObjectNode();
        try {
            for (PropertyDescriptor descriptor : Introspector.getBeanInfo(beanType, Object.class).getPropertyDescriptors()) {
                Method getter = descriptor.getReadMethod();
                if (getter == null || "class".equals(descriptor.getName())) {
                    continue;
                }
                if (isSecretField(descriptor.getName())) {
                    node.put(descriptor.getName(), REDACTED);
                    continue;
                }
                Object property = invoke(getter, bean);
                node.set(descriptor.getName(), toPropertyNode(property, visited, depth));
            }
        } catch (Exception e) {
            return toReferenceNode(bean);
        }
        return node.isEmpty() ? toReferenceNode(bean) : node;
    }

    private JsonNode toPropertyNode(Object value, IdentityHashMap<Object, Boolean> visited, int depth) {
        if (value == null) {
            return objectMapper.nullNode();
        }
        Class<?> type = unwrapProxyClass(value.getClass());
        if (isSimpleType(type)) {
            return objectMapper.valueToTree(value);
        }
        if (type.isArray()) {
            return toArrayNode(value, visited, depth);
        }
        if (value instanceof Iterable<?> iterable) {
            return toIterableNode(iterable, visited, depth);
        }
        if (value instanceof Map<?, ?> map) {
            return toMapNode(map, visited, depth);
        }
        return toReferenceNode(value);
    }

    private ArrayNode toArrayNode(Object array, IdentityHashMap<Object, Boolean> visited, int depth) {
        ArrayNode node = objectMapper.createArrayNode();
        int length = Array.getLength(array);
        for (int i = 0; i < Math.min(length, MAX_COLLECTION_ITEMS); i++) {
            node.add(toAuditNode(Array.get(array, i), visited, depth));
        }
        if (length > MAX_COLLECTION_ITEMS) {
            node.add("...truncated...");
        }
        return node;
    }

    private ArrayNode toIterableNode(Iterable<?> iterable, IdentityHashMap<Object, Boolean> visited, int depth) {
        ArrayNode node = objectMapper.createArrayNode();
        int count = 0;
        for (Object item : iterable) {
            if (count++ >= MAX_COLLECTION_ITEMS) {
                node.add("...truncated...");
                break;
            }
            node.add(toAuditNode(item, visited, depth));
        }
        return node;
    }

    private ObjectNode toMapNode(Map<?, ?> map, IdentityHashMap<Object, Boolean> visited, int depth) {
        ObjectNode node = objectMapper.createObjectNode();
        int count = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (count++ >= MAX_COLLECTION_ITEMS) {
                node.put("_truncated", true);
                break;
            }
            String field = String.valueOf(entry.getKey());
            if (isSecretField(field)) {
                node.put(field, REDACTED);
            } else {
                node.set(field, toAuditNode(entry.getValue(), visited, depth));
            }
        }
        return node;
    }

    private JsonNode redactSecrets(JsonNode node) {
        if (node == null) {
            return objectMapper.nullNode();
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            java.util.List<String> fields = new ArrayList<>();
            object.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                if (isSecretField(field)) {
                    object.put(field, REDACTED);
                } else {
                    object.set(field, redactSecrets(object.get(field)));
                }
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int index = 0; index < array.size(); index++) {
                array.set(index, redactSecrets(array.get(index)));
            }
        }
        return node;
    }

    private boolean isSecretField(String field) {
        if (field == null) {
            return false;
        }
        String normalized = field.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return SECRET_FIELDS.contains(normalized);
    }

    private ObjectNode toReferenceNode(Object value) {
        ObjectNode ref = objectMapper.createObjectNode();
        ref.put("_type", unwrapProxyClass(value.getClass()).getSimpleName());
        Object id = invokeGetter(value, "getId");
        Object code = invokeGetter(value, "getCode");
        if (id != null) {
            ref.set("id", objectMapper.valueToTree(id));
        }
        if (code != null) {
            ref.set("code", objectMapper.valueToTree(code));
        }
        return ref;
    }

    private Object invokeGetter(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            return invoke(target.getClass().getMethod(methodName), target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object invoke(Method method, Object target) {
        try {
            if (!method.canAccess(target)) {
                method.setAccessible(true);
            }
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Class<?> unwrapProxyClass(Class<?> type) {
        Class<?> current = type;
        while (current != null && current.getName().contains("$$")) {
            current = current.getSuperclass();
        }
        return current == null ? type : current;
    }

    private boolean isSimpleType(Class<?> type) {
        return type == null
            || type.isPrimitive()
            || CharSequence.class.isAssignableFrom(type)
            || Number.class.isAssignableFrom(type)
            || Boolean.class.isAssignableFrom(type)
            || Character.class.isAssignableFrom(type)
            || Enum.class.isAssignableFrom(type)
            || UUID.class.isAssignableFrom(type)
            || BigDecimal.class.isAssignableFrom(type)
            || BigInteger.class.isAssignableFrom(type)
            || java.util.Date.class.isAssignableFrom(type)
            || Temporal.class.isAssignableFrom(type);
    }
}
