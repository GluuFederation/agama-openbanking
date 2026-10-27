package org.gluu.agama.openbanking.consent;

import io.jans.as.model.crypto.AbstractCryptoProvider;
import io.jans.as.model.crypto.signature.SignatureAlgorithm;
import io.jans.as.model.exception.CryptoProviderException;
import io.jans.as.model.exception.InvalidJwtException;
import io.jans.as.model.jwk.JSONWebKey;
import io.jans.as.model.jwk.JSONWebKeySet;
import io.jans.as.model.jwt.Jwt;
import io.jans.as.model.jwt.JwtHeader;
import io.jans.service.cdi.util.CdiUtil;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Supplier;

import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.FileReader;
import java.io.IOException;

public class CryptoHelper {
    
    public enum JwtVerificationResult {
        JWT_VERIFICATION_SUCCESS,
        JWT_VERIFICATION_FAILURE,
    }

    private static AbstractCryptoProvider obtainCryptoProvider() {

        return CdiUtil.bean(AbstractCryptoProvider.class);
    }

    public static class FileBasedJwks implements Supplier<JSONObject> {

        private final String filepath;

        public FileBasedJwks(String filepath) {

            this.filepath = filepath;
        }

        @Override
        public JSONObject get() {

            try (FileReader reader = new FileReader(filepath)) {
                JSONTokener tokener  = new JSONTokener(reader);
                return new JSONObject(tokener);
            }catch(IOException e) {
                throw new RuntimeException("Failed to load jwks",e);
            }
        }
    }

    public static class BlankSecretSupplier implements  Supplier<String> {

        @Override
        public String get() {

            return "";
        }
    }

    public static JwtVerificationResult verifyJwt(String encodedJwt, 
        Supplier<JSONObject> jwksSupplier, Supplier<String> secretSupplier) throws InvalidJwtException, CryptoProviderException {
        
        if (encodedJwt == null) 
            throw new IllegalArgumentException("encodedJwt is required");

        Jwt jwt = Jwt.parse(encodedJwt);
        
        JSONObject jwks = jwksSupplier.get();
        
        AbstractCryptoProvider cryptoProvider = obtainCryptoProvider();

        final boolean verified = cryptoProvider.verifySignature(
                jwt.getSigningInput(),jwt.getEncodedSignature(),
                jwt.getHeader().getKeyId(), jwks,
                secretSupplier.get(),jwt.getHeader().getSignatureAlgorithm()
        );

        return verified == true ? JwtVerificationResult.JWT_VERIFICATION_SUCCESS : JwtVerificationResult.JWT_VERIFICATION_FAILURE;
    }

    public static String signJwt(JSONObject payload, JwtSigningOptions signOpts) throws InvalidJwtException, CryptoProviderException {

        if (payload == null) 
            throw new IllegalArgumentException("Payload is required");

        JSONObject header = signOpts.asJwtHeader();

        String encodedHeader = Base64.getEncoder().withoutPadding()
            .encodeToString(header.toString().getBytes(StandardCharsets.UTF_8));
        
        String encodedPayload = Base64.getEncoder().withoutPadding()
            .encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
        
        String signingInput = encodedHeader + "." + encodedPayload;
        AbstractCryptoProvider cryptoProvider = obtainCryptoProvider();
        String signature = cryptoProvider.sign(signingInput,signOpts.getKeyId(),null,signOpts.getSignatureAlgorithm());

        return signingInput + "." + signature;
    }

    public static boolean testVerifyJwt() throws Exception {

        final String signedJwt = "eyJhbGciOiJSUzUxMiIsImtpZCI6InNpZ25pbmdfNDZjZGIyM2UtOGI2My00Njg1LWFkZDctNWJmMzAwZWEwNzgwIiwidHlwIjoiSldUIn0.eyJzdWIiOiJ0ZXN0LXVzZXIiLCJpc3MiOiJodHRwczovL3Rlc3QuZXhhbXBsZS5jb20iLCJhdWQiOiJteS1hcGkiLCJjdXN0b20iOiJoZWxsby13b3JsZCIsImlhdCI6MTc5MzEwNzg1NCwiZXhwIjoxNzkzMTExNDU0fQ.J5RqE0liuplYMlN6s2wlLkrtQTJlkM8kVC9N5B6SU-hOMhNBoKFMeQuI5gOQQXqn7tOkT8bQCYpfQl0sk9wAncJMnMVCO0_sCPbV1dsnZt2G77hN9jI2IWtemZ-zKvq3g8LAF1mlRKUNovkns6Iq-WHgWuM27DK_dWXUzPHxhFstf8ArKunN5FHQ9nGB273yxQBK0zUS3N1gwhg2Y_I82qFt5xn9UW8k23UONUDdFzeUC7EdTGRhvAQr9Rj802z7wm74QB1tri2MjXPgmhYebpmAxYfUMdlSyCGQcOuCzQsW2mM-yiRQHA1diO8JlTA_GsKYTpltNSXnMRKNFtqPakgb6N1zPL-l5rOKn-U-hG2lkMIA8SbpjChGrMs5aoejJ866SGd09SpUmAx0Ilk9t8JlDiHRs5StNjwidaIujZ85EiHPuwF2UNAHiSYcCEEds63DWKTw6Fy9pKqunITtJc3lgpI3PLwPJ2cIntaml7GCExUJF-kkDn-4eN9vlcoyLcI5_VT5nzUnnwUB_Y2dNUUHs6pzKjIqCDYnQyHR4_jH1BHStQdBBQ6Uk2Th-jjvw7-bHF5AFeRNCX6Sdk9ajWuilFofBg8QGnfAyrfT5J51EDkQdvfc42LXpnXLNDG2NRlhEPhSHAY3NqVFHTuLoj6KRRT9CSBGRuQQorErv3I";
        final String jwksPath = "/opt/jans/jetty/jans-auth/custom/test_jwks.json";

        JwtVerificationResult res = verifyJwt(signedJwt, new FileBasedJwks(jwksPath), new BlankSecretSupplier());
        return res == JwtVerificationResult.JWT_VERIFICATION_SUCCESS ? true : false;
    }

}


