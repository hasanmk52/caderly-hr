/*
 * Global htmx glue (UI Guidelines §13: no inline JavaScript in templates).
 *
 * Five responsibilities, the first applying site-wide and the rest specific to admin/timeoff
 * screens but written generically enough to serve any future offcanvas- or modal-based screen:
 *   0. Attaching the CSRF header to every htmx state-changing request. Spring Security's
 *      Thymeleaf dialect only auto-injects a hidden CSRF field into forms it decorates via
 *      th:action; these forms submit through hx-post/hx-patch/hx-delete instead, so nothing
 *      would carry the token without this — see fragments/head.html's meta tags.
 *   1. Opening an offcanvas after htmx swaps a form into its body (Add/Edit both work this way —
 *      see AdminOrganizationController's Javadoc for why the offcanvas element itself is never
 *      part of an htmx response).
 *   2. Showing a toast and closing any open offcanvas/modal when the server signals success via
 *      the HX-Trigger response header (UI Guidelines §7.4).
 *   3. Book Time Off / Reject leave request (Phase 1.6, UI Guidelines §8.1/§8.6) open via a plain
 *      Bootstrap data-bs-toggle on their trigger — the modal shell is always present, unlike the
 *      offcanvas forms — so they need no open-on-swap glue, only the close-on-success handling
 *      responsibility 2 already covers.
 *   4. Drag-and-drop upload dropzones (Files page, profile Documents tab — UI Guidelines §8.7).
 *      Plain DOM wiring here, not an Alpine expression: this app's CSP-safe Alpine build (needed
 *      because SecurityConfig's CSP has no 'unsafe-eval') rejects both `$refs` and `$el` inside a
 *      compound `@drop` handler with a parser error — confirmed by actually triggering a drop and
 *      reading the browser console, not assumed — so the input-assignment logic lives here
 *      instead. Delegated on `document.body`, matching every other handler in this file, so it
 *      keeps working on a dropzone htmx swaps in later without any rebinding.
 *   5. Team calendar leave-bar hover tooltips (UI Guidelines §8.4). Bootstrap does not
 *      auto-initialize popovers; the calendar page is a plain full-page GET (no htmx fragment
 *      swap), so DOMContentLoaded is the only initialization point it needs.
 *   6. Copy-to-clipboard (Settings -> Calendar integration). Plain `navigator.clipboard` call
 *      delegated on `document.body`, same reasoning as responsibility 4 — not an Alpine
 *      expression, so there is no first-mover risk on the CSP-safe Alpine build's constraints.
 */
(function () {
  "use strict";

  document.body.addEventListener("htmx:configRequest", function (event) {
    var token = document.querySelector('meta[name="_csrf"]');
    var header = document.querySelector('meta[name="_csrf_header"]');
    if (token && header) {
      event.detail.headers[header.content] = token.content;
    }
  });

  var OFFCANVAS_BODY_IDS = [
    "divisionOffcanvasBody",
    "departmentOffcanvasBody",
    "employeeOffcanvasBody",
    "leaveTypeOffcanvasBody",
    "holidayOffcanvasBody",
  ];

  var MODAL_IDS = ["bookLeaveModal", "rejectLeaveModal"];

  document.body.addEventListener("htmx:afterSwap", function (event) {
    if (OFFCANVAS_BODY_IDS.indexOf(event.detail.target.id) === -1) {
      return;
    }
    // Only GET (new-form/edit-form) opens the panel. A POST/PATCH swap into the same target
    // is a save response resetting the form to blank — showing here would fight the
    // organization-toast handler's hide() on the very same round trip.
    if (event.detail.requestConfig.verb !== "get") {
      return;
    }
    var offcanvasEl = event.detail.target.closest(".offcanvas");
    if (offcanvasEl) {
      bootstrap.Offcanvas.getOrCreateInstance(offcanvasEl).show();
    }
  });

  document.body.addEventListener("organization-toast", function (event) {
    OFFCANVAS_BODY_IDS.forEach(function (id) {
      var body = document.getElementById(id);
      var offcanvasEl = body && body.closest(".offcanvas");
      var instance = offcanvasEl && bootstrap.Offcanvas.getInstance(offcanvasEl);
      if (instance) {
        instance.hide();
      }
    });
    MODAL_IDS.forEach(function (id) {
      var modalEl = document.getElementById(id);
      var instance = modalEl && bootstrap.Modal.getInstance(modalEl);
      if (instance) {
        instance.hide();
      }
    });
    showToast(event.detail.message);
  });

  document.body.addEventListener("dragover", function (event) {
    var zone = event.target.closest(".upload-dropzone");
    if (!zone) {
      return;
    }
    event.preventDefault();
    zone.classList.add("upload-dropzone-active");
  });

  document.body.addEventListener("dragleave", function (event) {
    var zone = event.target.closest(".upload-dropzone");
    // relatedTarget is null when the pointer leaves the window entirely, and is some element
    // still inside the zone when only moving between the icon/text children — only clear the
    // active state once the pointer has genuinely left the zone's bounds.
    if (zone && !zone.contains(event.relatedTarget)) {
      zone.classList.remove("upload-dropzone-active");
    }
  });

  document.body.addEventListener("drop", function (event) {
    var zone = event.target.closest(".upload-dropzone");
    if (!zone) {
      return;
    }
    event.preventDefault();
    zone.classList.remove("upload-dropzone-active");
    var input = zone.querySelector('input[type="file"]');
    if (!input || !event.dataTransfer || event.dataTransfer.files.length === 0) {
      return;
    }
    input.files = event.dataTransfer.files;
    showDroppedFileName(zone, input.files[0].name);
  });

  document.body.addEventListener("change", function (event) {
    if (!event.target.matches(".upload-dropzone input[type=file]")) {
      return;
    }
    var zone = event.target.closest(".upload-dropzone");
    if (zone && event.target.files.length > 0) {
      showDroppedFileName(zone, event.target.files[0].name);
    }
  });

  function showDroppedFileName(zone, name) {
    var label = zone.querySelector(".upload-dropzone-filename");
    if (label) {
      label.textContent = name;
      label.classList.remove("d-none");
    }
  }

  function showToast(message) {
    var container = document.getElementById("toast-container");
    if (!container) {
      return;
    }
    var toastEl = document.createElement("div");
    toastEl.className = "toast align-items-center text-bg-success border-0";
    toastEl.setAttribute("role", "status");
    toastEl.setAttribute("aria-live", "polite");
    toastEl.setAttribute("aria-atomic", "true");

    var flex = document.createElement("div");
    flex.className = "d-flex";

    var body = document.createElement("div");
    body.className = "toast-body";
    body.textContent = message;

    var closeButton = document.createElement("button");
    closeButton.type = "button";
    closeButton.className = "btn-close btn-close-white me-2 m-auto";
    closeButton.setAttribute("data-bs-dismiss", "toast");
    closeButton.setAttribute("aria-label", "Close");

    flex.appendChild(body);
    flex.appendChild(closeButton);
    toastEl.appendChild(flex);
    container.appendChild(toastEl);

    var toast = new bootstrap.Toast(toastEl, { delay: 4000 });
    toastEl.addEventListener("hidden.bs.toast", function () {
      toastEl.remove();
    });
    toast.show();
  }

  document.addEventListener("DOMContentLoaded", function () {
    document.querySelectorAll('[data-bs-toggle="popover"]').forEach(function (el) {
      bootstrap.Popover.getOrCreateInstance(el, { trigger: "hover focus", html: false });
    });
  });

  document.body.addEventListener("click", function (event) {
    var button = event.target.closest("[data-copy-target]");
    if (!button) {
      return;
    }
    var target = document.getElementById(button.getAttribute("data-copy-target"));
    if (!target || !navigator.clipboard) {
      return;
    }
    navigator.clipboard.writeText(target.value).then(function () {
      showToast(button.getAttribute("data-copied-message") || "Copied");
    });
  });
})();
