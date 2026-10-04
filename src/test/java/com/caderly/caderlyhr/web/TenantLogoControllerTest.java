package com.caderly.caderlyhr.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.tenant.TenantFacade;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /tenant-logo} is public (the login page needs it) and tenant-scoped by subdomain, so
 * the assertions that matter are that each host only ever sees its own tenant's bytes.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class TenantLogoControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantFacade tenants;

    @Test
    void logo_whenTenantHasOne_isServedPublicallyWithImmutableCaching() throws Exception {
        String slug = uniqueSlug();
        UUID id = tenants.createTenant(slug, "Acme", "UTC", 96, null);
        byte[] png = png();
        tenants.changeLogo(id, "logo.png", png);

        mockMvc.perform(get(URI.create("http://" + slug + ".localhost/tenant-logo")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(png))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("immutable")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void logo_whenTenantHasNone_returns404() throws Exception {
        String slug = uniqueSlug();
        tenants.createTenant(slug, "No Logo Ltd", "UTC", 96, null);

        mockMvc.perform(get(URI.create("http://" + slug + ".localhost/tenant-logo")))
                .andExpect(status().isNotFound());
    }

    @Test
    void logo_acrossTenants_neverServesAnotherTenantsLogo() throws Exception {
        String slugA = uniqueSlug();
        String slugB = uniqueSlug();
        UUID a = tenants.createTenant(slugA, "A", "UTC", 96, null);
        tenants.createTenant(slugB, "B", "UTC", 96, null);
        tenants.changeLogo(a, "logo.png", png());

        mockMvc.perform(get(URI.create("http://" + slugB + ".localhost/tenant-logo")))
                .andExpect(status().isNotFound());
    }

    @Test
    void clearLogo_afterUpload_makesTheRouteReturn404() throws Exception {
        String slug = uniqueSlug();
        UUID id = tenants.createTenant(slug, "Acme", "UTC", 96, null);
        tenants.changeLogo(id, "logo.png", png());
        tenants.clearLogo(id);

        mockMvc.perform(get(URI.create("http://" + slug + ".localhost/tenant-logo")))
                .andExpect(status().isNotFound());
    }

    @Test
    void loginPage_withLogo_rendersTheLogoImageInsteadOfTheTenantNameText() throws Exception {
        String slug = uniqueSlug();
        UUID id = tenants.createTenant(slug, "Logo Corp", "UTC", 96, null);
        tenants.changeLogo(id, "logo.png", png());

        mockMvc.perform(get(URI.create("http://" + slug + ".localhost/login")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/tenant-logo?v=")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("alt=\"Logo Corp\"")));
    }

    @Test
    void loginPage_withoutLogo_fallsBackToTheTenantNameAsText() throws Exception {
        String slug = uniqueSlug();
        tenants.createTenant(slug, "Plain Corp", "UTC", 96, null);

        mockMvc.perform(get(URI.create("http://" + slug + ".localhost/login")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Plain Corp")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/tenant-logo"))));
    }

    private static String uniqueSlug() {
        return "logo-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static byte[] png() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
