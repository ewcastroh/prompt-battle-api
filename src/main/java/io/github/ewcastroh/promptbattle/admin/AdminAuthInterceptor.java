package io.github.ewcastroh.promptbattle.admin;

import io.github.ewcastroh.promptbattle.city.AdminTokens;
import io.github.ewcastroh.promptbattle.game.ErrorCode;
import io.github.ewcastroh.promptbattle.game.GameException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** {@code X-Admin-Token} → the admin's city (SPEC §6.4). Missing or unknown → 401 UNAUTHORIZED. */
public final class AdminAuthInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Admin-Token";
    public static final String CITY = "adminCity";

    private final AdminTokens tokens;

    public AdminAuthInterceptor(AdminTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String city = tokens.cityFor(request.getHeader(HEADER))
                .orElseThrow(() -> new GameException(ErrorCode.UNAUTHORIZED, "Missing or unknown admin token"));
        request.setAttribute(CITY, city);
        return true;
    }
}
