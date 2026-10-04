package com.caderly.caderlyhr.identity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Per-user Home dashboard layout (ADR 0021). The caller supplies the user id from the principal. */
@Service
@Transactional(readOnly = true)
public class DashboardLayoutService {

    /** One dashboard card in resolved order. */
    public record WidgetSlot(DashboardWidget widget, boolean hidden) {}

    private final DashboardLayoutRepository layouts;

    DashboardLayoutService(DashboardLayoutRepository layouts) {
        this.layouts = layouts;
    }

    /** The registry default, for a principal with no user id (a mock or system account). */
    public static List<WidgetSlot> defaultLayout() {
        return resolve(List.of(), Set.of());
    }

    public List<WidgetSlot> layoutFor(UUID userId) {
        return layouts.findByUserId(userId)
                .map(l -> resolve(split(l.widgetOrder()), Set.copyOf(split(l.hiddenWidgets()))))
                .orElseGet(DashboardLayoutService::defaultLayout);
    }

    /** Sanitises through {@link #resolve}, so only whitelisted keys are ever stored. */
    @Transactional
    public void save(UUID userId, List<String> order, List<String> hidden) {
        List<WidgetSlot> slots = resolve(order, Set.copyOf(hidden));
        String orderCsv = slots.stream().map(s -> s.widget().key()).collect(Collectors.joining(","));
        String hiddenCsv =
                slots.stream().filter(WidgetSlot::hidden).map(s -> s.widget().key()).collect(Collectors.joining(","));
        layouts.findByUserId(userId)
                .ifPresentOrElse(
                        l -> l.replace(orderCsv, hiddenCsv),
                        () -> layouts.save(DashboardLayout.create(userId, orderCsv, hiddenCsv)));
    }

    @Transactional
    public void reset(UUID userId) {
        layouts.findByUserId(userId).ifPresent(layouts::delete);
    }

    /**
     * Stored order and hidden keys plus the registry become a complete layout: unknown and duplicate
     * keys are dropped, and any widget missing from the order (a widget added after the user last
     * saved) is appended at the end (visible unless the hidden set names it), so new widgets reach everyone without a data migration.
     */
    static List<WidgetSlot> resolve(List<String> order, Set<String> hidden) {
        Set<DashboardWidget> ordered = new LinkedHashSet<>();
        for (String key : order) {
            DashboardWidget.fromKey(key).ifPresent(ordered::add);
        }
        Set<DashboardWidget> hiddenWidgets = new LinkedHashSet<>();
        for (String key : hidden) {
            DashboardWidget.fromKey(key).ifPresent(hiddenWidgets::add);
        }
        List<WidgetSlot> slots = new ArrayList<>();
        for (DashboardWidget widget : ordered) {
            slots.add(new WidgetSlot(widget, hiddenWidgets.contains(widget)));
        }
        for (DashboardWidget widget : DashboardWidget.values()) {
            if (!ordered.contains(widget)) {
                slots.add(new WidgetSlot(widget, hiddenWidgets.contains(widget)));
            }
        }
        return slots;
    }

    private static List<String> split(String csv) {
        return csv.isBlank() ? List.of() : Arrays.asList(csv.split(","));
    }
}
