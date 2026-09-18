package com.caderly.caderlyhr.audit;

import com.caderly.caderlyhr.common.CryptoConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns an audited entity into a flat, JSON-serializable snapshot of its own scalar columns — the
 * "before"/"after" halves of an {@code audit_entry} row (ADR 0017).
 *
 * <p>Deliberately narrow, not a general object serializer:
 *
 * <ul>
 *   <li>Relationship fields ({@code @ManyToOne}/{@code @OneToMany}/{@code @OneToOne}/{@code
 *       @ManyToMany}/{@code @ElementCollection}) are skipped entirely, not followed. A lazy
 *       Hibernate proxy would otherwise either throw once accessed outside its session or force an
 *       extra load on every audited write, and a full object graph risks infinite recursion on a
 *       bidirectional association. Losing a relation's own diff is an accepted MVP simplification
 *       — the relation most likely to matter (an employee's manager) already has its own dedicated
 *       history table ({@code people.EmployeeManagerHistory}), which is itself audited.
 *   <li>A field converted by {@link CryptoConverter}, or whose name contains "password", "secret",
 *       "token", or "hash" (case-insensitive), is replaced with a fixed marker rather than its
 *       value or dropped outright — the audit trail should show that a secret field changed
 *       without ever holding the secret itself (CLAUDE.md §6 A02).
 * </ul>
 */
final class AuditFieldSnapshotter {

    static final String REDACTED = "[REDACTED]";

    private final ObjectMapper mapper;

    AuditFieldSnapshotter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String snapshot(Object entity) {
        Map<String, Object> fields = new TreeMap<>();
        for (Class<?> type = entity.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (skip(field)) {
                    continue;
                }
                // putIfAbsent: a subclass field is read before its superclass namesake, and the
                // subclass value is the one that should win (there are none today, but a name
                // collision silently picking the wrong one would be an easy mistake to miss).
                fields.putIfAbsent(field.getName(), valueOf(field, entity));
            }
        }
        try {
            return mapper.writeValueAsString(fields);
        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "Could not serialize audit snapshot for " + entity.getClass(), e);
        }
    }

    private static boolean skip(Field field) {
        int modifiers = field.getModifiers();
        return Modifier.isStatic(modifiers)
                || Modifier.isTransient(modifiers)
                || field.isAnnotationPresent(OneToMany.class)
                || field.isAnnotationPresent(ManyToOne.class)
                || field.isAnnotationPresent(OneToOne.class)
                || field.isAnnotationPresent(ManyToMany.class)
                || field.isAnnotationPresent(ElementCollection.class);
    }

    private static @Nullable Object valueOf(Field field, Object entity) {
        if (isSensitive(field)) {
            return REDACTED;
        }
        field.setAccessible(true);
        try {
            // Enums, UUIDs, and java.time types all already serialize sensibly through the
            // application's ObjectMapper (enums by name, UUID by toString, java.time via Boot's
            // auto-registered JavaTimeModule) — nothing extra needed here.
            return field.get(entity);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not read " + field, e);
        }
    }

    private static boolean isSensitive(Field field) {
        Convert convert = field.getAnnotation(Convert.class);
        if (convert != null && convert.converter() == CryptoConverter.class) {
            return true;
        }
        String name = field.getName().toLowerCase(Locale.ROOT);
        return name.contains("password")
                || name.contains("secret")
                || name.contains("token")
                || name.contains("hash");
    }
}
