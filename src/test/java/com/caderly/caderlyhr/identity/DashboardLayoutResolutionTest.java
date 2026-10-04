package com.caderly.caderlyhr.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.identity.DashboardLayoutService.WidgetSlot;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DashboardLayoutResolutionTest {

    private static final List<DashboardWidget> DEFAULT_ORDER = List.of(DashboardWidget.values());

    @Test
    void resolve_whenNothingStored_returnsRegistryDefaultAllVisible() {
        List<WidgetSlot> slots = DashboardLayoutService.resolve(List.of(), Set.of());

        assertThat(slots).extracting(WidgetSlot::widget).containsExactlyElementsOf(DEFAULT_ORDER);
        assertThat(slots).noneMatch(WidgetSlot::hidden);
    }

    @Test
    void resolve_withStoredOrder_followsItAndAppliesHidden() {
        List<WidgetSlot> slots =
                DashboardLayoutService.resolve(
                        List.of("resources", "my-peers"), Set.of("my-peers"));

        assertThat(slots.get(0).widget()).isEqualTo(DashboardWidget.RESOURCES);
        assertThat(slots.get(1).widget()).isEqualTo(DashboardWidget.MY_PEERS);
        assertThat(slots.get(1).hidden()).isTrue();
        assertThat(slots).hasSize(DEFAULT_ORDER.size());
    }

    @Test
    void resolve_dropsUnknownAndDuplicateKeys() {
        List<WidgetSlot> slots =
                DashboardLayoutService.resolve(
                        List.of("bogus", "resources", "resources", "<script>"), Set.of("bogus"));

        assertThat(slots.get(0).widget()).isEqualTo(DashboardWidget.RESOURCES);
        assertThat(slots).extracting(WidgetSlot::widget).doesNotHaveDuplicates().hasSize(DEFAULT_ORDER.size());
        assertThat(slots).noneMatch(WidgetSlot::hidden);
    }

    @Test
    void resolve_whenWidgetMissingFromStorage_appendsItVisibleAtTheEnd() {
        List<String> storedWithoutLast = DEFAULT_ORDER.stream().skip(1).map(DashboardWidget::key).toList();

        List<WidgetSlot> slots = DashboardLayoutService.resolve(storedWithoutLast, Set.of());

        WidgetSlot last = slots.get(slots.size() - 1);
        assertThat(last.widget()).isEqualTo(DEFAULT_ORDER.get(0));
        assertThat(last.hidden()).isFalse();
    }

    @Test
    void resolve_whenAllHidden_keepsEverySlotMarkedHidden() {
        Set<String> all = Set.copyOf(DEFAULT_ORDER.stream().map(DashboardWidget::key).toList());

        List<WidgetSlot> slots = DashboardLayoutService.resolve(List.of(), all);

        assertThat(slots).hasSize(DEFAULT_ORDER.size()).allMatch(WidgetSlot::hidden);
    }
}
