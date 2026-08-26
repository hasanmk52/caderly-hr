package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.common.CaderlyException;
import com.caderly.caderlyhr.documents.CompanyFile;
import com.caderly.caderlyhr.documents.CompanyFileService;
import com.caderly.caderlyhr.documents.DownloadableFile;
import com.caderly.caderlyhr.identity.AppUserPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * Company-wide files: uploaded by Admin, visible to everyone in the tenant (PRD §6.7 FR-7.1,
 * §26). Mirrors {@code AdminLeaveController}'s holidays-upload shape (ADR 0007).
 */
@Controller
@PreAuthorize("isAuthenticated()")
class FilesController {

    private final CompanyFileService companyFiles;

    FilesController(CompanyFileService companyFiles) {
        this.companyFiles = companyFiles;
    }

    @GetMapping("/files")
    @PreAuthorize("isAuthenticated()")
    String filesPage(Model model) {
        model.addAttribute("files", companyFiles.listAll());
        model.addAttribute("uploadErrors", List.<String>of());
        return "files/list";
    }

    @PostMapping("/files/upload")
    @PreAuthorize("hasRole('ADMIN')")
    String upload(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model,
            HttpServletResponse response) {
        List<CompanyFile> fresh;
        try {
            fresh = companyFiles.uploadAndList(file, principal.userId());
        } catch (CaderlyException exception) {
            model.addAttribute("uploadErrors", List.of(exception.getMessage()));
            model.addAttribute("files", companyFiles.listAll());
            return "files/list :: content";
        }
        toast(response, "File uploaded");
        model.addAttribute("uploadErrors", List.<String>of());
        model.addAttribute("files", fresh);
        return "files/list :: content";
    }

    @GetMapping("/files/{id}/download")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<InputStreamResource> download(@PathVariable UUID id) {
        DownloadableFile file = companyFiles.download(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(file.filename()))
                .contentType(MediaType.parseMediaType(file.mime()))
                .contentLength(file.size())
                .body(new InputStreamResource(file.content()));
    }

    @DeleteMapping("/files/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    String delete(@PathVariable UUID id, Model model, HttpServletResponse response) {
        toast(response, "File deleted");
        model.addAttribute("files", companyFiles.deleteAndList(id));
        model.addAttribute("uploadErrors", List.<String>of());
        return "files/list :: content";
    }

    /**
     * RFC 5987 {@code filename*=} form, percent-encoded: the stored name is user-supplied and
     * must never be interpolated into a header raw — percent-encoding removes any literal CR/LF
     * or quote that could otherwise break out of the header (CLAUDE.md §6 A03).
     */
    private static String contentDisposition(String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename*=UTF-8''" + encoded;
    }

    private static void toast(HttpServletResponse response, String message) {
        response.setHeader("HX-Trigger", "{\"organization-toast\": {\"message\": \"" + message + "\"}}");
    }
}
