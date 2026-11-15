package com.flashsale.auth.account;

import com.flashsale.auth.account.AccountDtos.LoginRequest;
import com.flashsale.auth.account.AccountDtos.RegisterRequest;
import com.flashsale.auth.account.AccountDtos.TokenResponse;
import com.flashsale.auth.account.AccountDtos.UserResponse;
import com.flashsale.auth.token.AccessTokenIssuer;
import com.flashsale.auth.token.InvalidRefreshTokenException;
import com.flashsale.auth.token.RefreshTokenService;
import com.flashsale.auth.token.RefreshTokenService.Rotation;
import com.flashsale.auth.user.Role;
import com.flashsale.auth.user.User;
import com.flashsale.auth.user.UserRepository;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AccountService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokens;
    private final RefreshTokenService refreshTokens;
    private final Clock clock;
    // Checked when the email is unknown, so "no such user" takes as long as "wrong password"
    // and response time does not reveal which emails are registered.
    private final String dummyPasswordHash;

    public AccountService(UserRepository users, PasswordEncoder passwordEncoder, AccessTokenIssuer accessTokens,
            RefreshTokenService refreshTokens, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    public UserResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        if (users.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }
        // Public sign-up always creates USER. ADMIN accounts are created directly in the database.
        User user = new User(email, passwordEncoder.encode(request.password()), Role.USER, clock.instant());
        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Two sign-ups with the same email at the same time: the unique constraint catches the second one.
            throw new EmailAlreadyRegisteredException();
        }
        return new UserResponse(user.getId(), user.getEmail(), user.getRole().name());
    }

    public TokenResponse login(LoginRequest request) {
        Optional<User> user = users.findByEmail(normalize(request.email()));
        String hash = user.map(User::getPasswordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        if (user.isEmpty() || !passwordMatches) {
            throw new InvalidCredentialsException();
        }
        return tokens(user.get(), refreshTokens.issue(user.get().getId()));
    }

    public TokenResponse refresh(String refreshToken) {
        Rotation rotation = refreshTokens.rotate(refreshToken);
        User user = users.findById(rotation.userId()).orElseThrow(InvalidRefreshTokenException::new);
        return tokens(user, rotation.refreshToken());
    }

    public void logout(String refreshToken) {
        refreshTokens.revokeFamily(refreshToken);
    }

    private TokenResponse tokens(User user, String refreshToken) {
        return new TokenResponse(
                accessTokens.issue(user.getId(), user.getRole()), "Bearer", accessTokens.ttlSeconds(), refreshToken);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
