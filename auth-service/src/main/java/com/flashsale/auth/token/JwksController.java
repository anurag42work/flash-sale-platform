package com.flashsale.auth.token;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the public signing key so other services (the gateway) can verify access tokens. */
@RestController
class JwksController {

    private final Map<String, Object> jwks;

    JwksController(RSAKey signingKey) {
        // toPublicJWK() drops the private parts: only kty, n, e, kid, use and alg are published.
        this.jwks = new JWKSet(signingKey.toPublicJWK()).toJSONObject();
    }

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() {
        return jwks;
    }
}
