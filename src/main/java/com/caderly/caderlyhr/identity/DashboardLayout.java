package com.caderly.caderlyhr.identity;

import com.caderly.caderlyhr.audit.EntityAuditListener;
import com.caderly.caderlyhr.common.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A user's saved Home widget order and hidden set (ADR 0021). Stores raw comma-separated keys;
 * {@link DashboardLayoutService#resolve} is what turns them into a safe, complete layout.
 */
@Entity
@EntityListeners(EntityAuditListener.class)
@Table(name = "dashboard_layout")
public class DashboardLayout extends TenantAwareEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "widget_order", nullable = false, length = 500)
    private String widgetOrder;

    @Column(name = "hidden_widgets", nullable = false, length = 500)
    private String hiddenWidgets;

    protected DashboardLayout() {}

    private DashboardLayout(UUID userId, String widgetOrder, String hiddenWidgets) {
        this.userId = userId;
        this.widgetOrder = widgetOrder;
        this.hiddenWidgets = hiddenWidgets;
    }

    public static DashboardLayout create(UUID userId, String widgetOrder, String hiddenWidgets) {
        return new DashboardLayout(userId, widgetOrder, hiddenWidgets);
    }

    public void replace(String widgetOrder, String hiddenWidgets) {
        this.widgetOrder = widgetOrder;
        this.hiddenWidgets = hiddenWidgets;
    }

    public String widgetOrder() {
        return widgetOrder;
    }

    public String hiddenWidgets() {
        return hiddenWidgets;
    }
}
