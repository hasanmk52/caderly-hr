package com.caderly.caderlyhr.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caderly.caderlyhr.common.ValidationException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class TenantLogoValidatorTest {

    private final TenantLogoValidator validator = new TenantLogoValidator();

    @Test
    void validate_whenPng_returnsImagePng() throws IOException {
        assertThat(validator.validate("logo.png", image("png"))).isEqualTo("image/png");
    }

    @Test
    void validate_whenJpeg_returnsImageJpeg() throws IOException {
        assertThat(validator.validate("logo.jpg", image("jpg"))).isEqualTo("image/jpeg");
    }

    @Test
    void validate_whenSvg_isRejectedBecauseSvgCanCarryScript() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> validator.validate("logo.svg", svg))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", "LOGO_BAD_EXTENSION");
    }

    @Test
    void validate_whenSvgRenamedToPng_isRejectedByMagicBytes() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> validator.validate("logo.png", svg))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", "LOGO_BAD_CONTENT_TYPE");
    }

    @Test
    void validate_whenOverSizeLimit_isRejected() {
        byte[] tooBig = new byte[TenantLogoValidator.MAX_BYTES + 1];

        assertThatThrownBy(() -> validator.validate("logo.png", tooBig))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", "LOGO_TOO_LARGE");
    }

    @Test
    void validate_whenEmpty_isRejected() {
        assertThatThrownBy(() -> validator.validate("logo.png", new byte[0]))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", "LOGO_EMPTY");
    }

    private static byte[] image(String format) throws IOException {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }
}
