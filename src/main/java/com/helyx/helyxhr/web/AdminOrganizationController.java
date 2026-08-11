package com.helyx.helyxhr.web;

import com.helyx.helyxhr.common.HelyxException;
import com.helyx.helyxhr.org.DeleteOutcome;
import com.helyx.helyxhr.org.Department;
import com.helyx.helyxhr.org.DepartmentService;
import com.helyx.helyxhr.org.Division;
import com.helyx.helyxhr.org.DivisionService;
import com.helyx.helyxhr.org.OrganizationAdminService;
import com.helyx.helyxhr.org.OrganizationAdminService.DeleteResult;
import com.helyx.helyxhr.org.OrganizationAdminService.DepartmentEditData;
import com.helyx.helyxhr.org.OrganizationAdminService.OrganizationSnapshot;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Admin CRUD for Divisions and Departments (PRD §13, §26: Admin-only). One combined page —
 * {@code admin/organization} — since a Department is meaningless without its Division; mutation
 * routes stay split ({@code /admin/divisions/**}, {@code /admin/departments/**}) per the
 * Implementation Plan's endpoint naming.
 *
 * <p>Every mutation is htmx-driven: a successful save returns the form fragment plus an
 * out-of-band table swap and an {@code HX-Trigger} toast header (UI Guidelines §7.4) — see
 * {@code organization.html} and {@code helyx.js} for the wiring.
 *
 * <p>No method here is {@code @Transactional} (CLAUDE.md §7) — {@link OrganizationAdminService}
 * owns the write-then-read transaction boundary (see ADR 0007 for why). Single-read GET
 * endpoints ({@code new-form}, division {@code edit-form}) call {@link DivisionService}/{@link
 * DepartmentService} directly.
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
class AdminOrganizationController {

    private final DivisionService divisions;
    private final OrganizationAdminService organizationAdmin;

    AdminOrganizationController(DivisionService divisions, OrganizationAdminService organizationAdmin) {
        this.divisions = divisions;
        this.organizationAdmin = organizationAdmin;
    }

    @GetMapping("/admin/organization")
    @PreAuthorize("hasRole('ADMIN')")
    String organization(Model model) {
        model.addAttribute("divisionForm", blankDivisionForm());
        model.addAttribute("departmentForm", blankDepartmentForm());
        OrganizationSnapshot snapshot = organizationAdmin.snapshot();
        model.addAttribute("divisionOptions", snapshot.divisions());
        populateTables(model, snapshot);
        return "admin/organization";
    }

    @GetMapping("/admin/divisions/new-form")
    @PreAuthorize("hasRole('ADMIN')")
    String newDivisionForm(Model model) {
        model.addAttribute("divisionForm", blankDivisionForm());
        return "admin/organization :: divisionForm";
    }

    @GetMapping("/admin/divisions/{id}/edit-form")
    @PreAuthorize("hasRole('ADMIN')")
    String divisionEditForm(@PathVariable UUID id, Model model) {
        Division division = divisions.require(id);
        model.addAttribute(
                "divisionForm",
                new OrganizationForms.DivisionForm(division.name(), division.description()));
        model.addAttribute("editingDivisionId", id);
        return "admin/organization :: divisionForm";
    }

    @GetMapping("/admin/departments/new-form")
    @PreAuthorize("hasRole('ADMIN')")
    String newDepartmentForm(Model model) {
        model.addAttribute("departmentForm", blankDepartmentForm());
        model.addAttribute("divisionOptions", divisions.listActive());
        return "admin/organization :: departmentForm";
    }

    @GetMapping("/admin/departments/{id}/edit-form")
    @PreAuthorize("hasRole('ADMIN')")
    String departmentEditForm(@PathVariable UUID id, Model model) {
        DepartmentEditData data = organizationAdmin.departmentEditData(id);
        Department department = data.department();
        model.addAttribute(
                "departmentForm",
                new OrganizationForms.DepartmentForm(
                        department.name(), department.description(), department.division().getId()));
        model.addAttribute("editingDepartmentId", id);
        model.addAttribute("divisionOptions", data.divisionOptions());
        return "admin/organization :: departmentForm";
    }

    @PostMapping("/admin/divisions")
    @PreAuthorize("hasRole('ADMIN')")
    String createDivision(
            @Valid @ModelAttribute("divisionForm") OrganizationForms.DivisionForm form,
            BindingResult binding,
            Model model,
            HttpServletResponse response) {
        if (!binding.hasErrors()) {
            try {
                OrganizationSnapshot snapshot =
                        organizationAdmin.createDivision(form.name(), form.description());
                return saveSucceeded(response, model, "Division created", true, snapshot);
            } catch (HelyxException exception) {
                binding.rejectValue("name", exception.errorCode(), exception.getMessage());
            }
        }
        return "admin/organization :: divisionForm";
    }

    @PatchMapping("/admin/divisions/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    String editDivision(
            @PathVariable UUID id,
            @Valid @ModelAttribute("divisionForm") OrganizationForms.DivisionForm form,
            BindingResult binding,
            Model model,
            HttpServletResponse response) {
        if (!binding.hasErrors()) {
            try {
                OrganizationSnapshot snapshot =
                        organizationAdmin.editDivision(id, form.name(), form.description());
                return saveSucceeded(response, model, "Division updated", true, snapshot);
            } catch (HelyxException exception) {
                binding.rejectValue("name", exception.errorCode(), exception.getMessage());
            }
        }
        model.addAttribute("editingDivisionId", id);
        return "admin/organization :: divisionForm";
    }

    @DeleteMapping("/admin/divisions/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    String deleteDivision(@PathVariable UUID id, Model model, HttpServletResponse response) {
        DeleteResult result = organizationAdmin.deleteDivision(id);
        toast(
                response,
                result.outcome() == DeleteOutcome.DELETED
                        ? "Division deleted"
                        : "Division archived — it still has active departments assigned");
        populateTables(model, result.snapshot());
        return "admin/organization :: content";
    }

    @PostMapping("/admin/departments")
    @PreAuthorize("hasRole('ADMIN')")
    String createDepartment(
            @Valid @ModelAttribute("departmentForm") OrganizationForms.DepartmentForm form,
            BindingResult binding,
            Model model,
            HttpServletResponse response) {
        if (!binding.hasErrors()) {
            try {
                OrganizationSnapshot snapshot =
                        organizationAdmin.createDepartment(
                                form.name(), form.description(), form.divisionId());
                return saveSucceeded(response, model, "Department created", false, snapshot);
            } catch (HelyxException exception) {
                binding.rejectValue(fieldFor(exception), exception.errorCode(), exception.getMessage());
            }
        }
        model.addAttribute("divisionOptions", divisions.listActive());
        return "admin/organization :: departmentForm";
    }

    @PatchMapping("/admin/departments/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    String editDepartment(
            @PathVariable UUID id,
            @Valid @ModelAttribute("departmentForm") OrganizationForms.DepartmentForm form,
            BindingResult binding,
            Model model,
            HttpServletResponse response) {
        if (!binding.hasErrors()) {
            try {
                OrganizationSnapshot snapshot =
                        organizationAdmin.editDepartment(
                                id, form.name(), form.description(), form.divisionId());
                return saveSucceeded(response, model, "Department updated", false, snapshot);
            } catch (HelyxException exception) {
                binding.rejectValue(fieldFor(exception), exception.errorCode(), exception.getMessage());
            }
        }
        model.addAttribute("editingDepartmentId", id);
        model.addAttribute("divisionOptions", divisions.listActive());
        return "admin/organization :: departmentForm";
    }

    @DeleteMapping("/admin/departments/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    String deleteDepartment(@PathVariable UUID id, Model model, HttpServletResponse response) {
        DeleteResult result = organizationAdmin.deleteDepartment(id);
        toast(
                response,
                result.outcome() == DeleteOutcome.DELETED ? "Department deleted" : "Department archived");
        populateTables(model, result.snapshot());
        return "admin/organization :: content";
    }

    /**
     * Shared success path for all four create/edit endpoints: reset the relevant form to blank,
     * refresh both tables from the snapshot the write already produced, and fire the toast +
     * offcanvas-close trigger. The form fragment stays the primary (and only) thing this
     * returns — {@code refreshTable} tells it to additionally nest {@code ~{::content}} (an
     * out-of-band {@code #organization-content} swap) as a sibling of the form, so one response
     * updates both the offcanvas and the table.
     */
    private String saveSucceeded(
            HttpServletResponse response,
            Model model,
            String message,
            boolean isDivision,
            OrganizationSnapshot snapshot) {
        toast(response, message);
        populateTables(model, snapshot);
        model.addAttribute("refreshTable", true);
        if (isDivision) {
            model.addAttribute("divisionForm", blankDivisionForm());
            return "admin/organization :: divisionForm";
        }
        model.addAttribute("departmentForm", blankDepartmentForm());
        model.addAttribute("divisionOptions", snapshot.divisions());
        return "admin/organization :: departmentForm";
    }

    private static OrganizationForms.DivisionForm blankDivisionForm() {
        return new OrganizationForms.DivisionForm("", null);
    }

    private static OrganizationForms.DepartmentForm blankDepartmentForm() {
        return new OrganizationForms.DepartmentForm("", null, null);
    }

    /** {@code DIVISION_NAME_TAKEN}/{@code DEPARTMENT_NAME_TAKEN} land on name; the rest are about the chosen division. */
    private static String fieldFor(HelyxException exception) {
        return exception.errorCode().endsWith("NAME_TAKEN") ? "name" : "divisionId";
    }

    /**
     * htmx surfaces this via the {@code HX-Trigger} response header (a JSON-encoded custom
     * event); {@code helyx.js} listens for {@code organization-toast} to show a Bootstrap toast
     * (UI Guidelines §7.4) and close any open offcanvas. The message text is fixed and never
     * echoes user input, so no escaping is needed.
     */
    private static void toast(HttpServletResponse response, String message) {
        response.setHeader("HX-Trigger", "{\"organization-toast\": {\"message\": \"" + message + "\"}}");
    }

    private static void populateTables(Model model, OrganizationSnapshot snapshot) {
        model.addAttribute("divisions", divisionRows(snapshot.divisions()));
        model.addAttribute("departments", departmentRows(snapshot.departments()));
    }

    private static List<DivisionRow> divisionRows(List<Division> divisions) {
        return divisions.stream()
                .map(division -> new DivisionRow(division.getId(), division.name(), division.description()))
                .toList();
    }

    private static List<DepartmentRow> departmentRows(List<Department> departments) {
        return departments.stream()
                .map(
                        department ->
                                new DepartmentRow(
                                        department.getId(),
                                        department.name(),
                                        department.description(),
                                        department.division().name()))
                .toList();
    }

    record DivisionRow(UUID id, String name, String description) {}

    record DepartmentRow(UUID id, String name, String description, String divisionName) {}
}
