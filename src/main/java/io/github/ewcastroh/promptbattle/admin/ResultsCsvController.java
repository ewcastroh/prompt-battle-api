package io.github.ewcastroh.promptbattle.admin;

import io.github.ewcastroh.promptbattle.game.GameEngine;
import io.github.ewcastroh.promptbattle.game.Views;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/admin/results.csv} (API.md §7): the admin's own city, one row per player, one column per
 * scored round played in the city. Prompts are not included. RFC 4180 (CRLF, quoted when needed); text cells
 * that a spreadsheet would read as a formula get a leading apostrophe.
 */
@RestController
class ResultsCsvController {

    static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final String CRLF = "\r\n";

    private final GameEngine engine;

    ResultsCsvController(GameEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/api/admin/results.csv")
    ResponseEntity<byte[]> results(@RequestAttribute(AdminAuthInterceptor.CITY) String city) {
        Views.Results results = engine.read(v -> v.results(city));
        String filename = "results-" + results.city() + ".csv";
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build()
                        .toString())
                .body(csv(results).getBytes(StandardCharsets.UTF_8));
    }

    static String csv(Views.Results results) {
        StringBuilder out = new StringBuilder();
        out.append("city,rank,nickname,claimCode,total");
        for (String name : results.roundNames()) {
            out.append(',').append(text(name));
        }
        out.append(",kicked").append(CRLF);
        for (Views.ResultRow row : results.rows()) {
            out.append(text(results.city())).append(',')
                    .append(row.rank() == null ? "" : row.rank()).append(',')
                    .append(text(row.nickname())).append(',')
                    .append(text(row.claimCode())).append(',')
                    .append(number(row.total()));
            for (Double score : row.roundScores()) {
                out.append(',').append(score == null ? "" : number(score));
            }
            out.append(',').append(row.kicked()).append(CRLF);
        }
        return out.toString();
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** Quotes when needed (RFC 4180) and neutralizes formula prefixes (CSV injection). */
    static String text(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
