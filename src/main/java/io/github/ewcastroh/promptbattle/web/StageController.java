package io.github.ewcastroh.promptbattle.web;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import io.github.ewcastroh.promptbattle.config.AppProperties;
import io.github.ewcastroh.promptbattle.game.GameEngine;
import io.github.ewcastroh.promptbattle.game.Views;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Stage endpoints (API.md §6): no auth, read-only, only what the city has already revealed. */
@RestController
class StageController {

    static final int QR_SIZE = 1024;

    private final GameEngine engine;
    private final String publicUiUrl;
    private final Map<String, byte[]> qrCache = new ConcurrentHashMap<>();

    StageController(GameEngine engine, AppProperties props) {
        this.engine = engine;
        String url = props.publicUiUrl();
        this.publicUiUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @GetMapping("/api/cities/{city}/stage/state")
    ResponseEntity<Views.Stage> state(@PathVariable String city) {
        Views.Stage stage = engine.read(v -> v.stage(city));
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(stage);
    }

    @GetMapping(value = "/api/cities/{city}/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> qr(@PathVariable String city) {
        String code = engine.read(v -> v.cityInfo(city)).code();
        byte[] png = qrCache.computeIfAbsent(code, c -> render(publicUiUrl + "/play/" + c));
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noCache()).body(png);
    }

    static byte[] render(String text) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2));
            BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(),
                    BufferedImage.TYPE_BYTE_BINARY);
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (WriterException e) {
            throw new IllegalStateException("QR encoding failed", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
