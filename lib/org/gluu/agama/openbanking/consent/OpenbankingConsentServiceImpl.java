package org.gluu.agama.openbanking.consent;

import io.jans.as.common.util.CommonUtils;
import io.jans.as.common.model.registration.Client;

import io.jans.as.common.model.session.SessionId;
import io.jans.as.server.service.SessionIdService;
import jakarta.servlet.http.HttpServletRequest;
import io.jans.service.net.NetworkService;
import io.jans.service.cdi.util.CdiUtil;
import io.jans.agama.engine.script.LogUtils;
import io.jans.util.StringHelper;

import io.jans.as.model.config.WebKeysConfiguration;
import io.jans.as.model.configuration.AppConfiguration;
import io.jans.as.model.crypto.CryptoProviderFactory;
import io.jans.as.model.crypto.AbstractCryptoProvider;
import io.jans.as.model.crypto.signature.SignatureAlgorithm;

import io.jans.as.model.exception.CryptoProviderException;
import io.jans.as.model.exception.InvalidJwtException;

import io.jans.as.model.jwk.JSONWebKey;
import io.jans.as.model.jwk.Use;
import io.jans.as.model.jwt.Jwt;
import io.jans.as.model.jwt.JwtHeader;
import io.jans.service.cdi.util.CdiUtil;
import io.jans.as.server.service.ClientService;

import io.jans.util.security.StringEncrypter.EncryptionException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.Map;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.UUID;
import java.util.Date;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.io.*;
import java.util.Base64;
import java.util.function.Supplier;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import com.fasterxml.jackson.databind.JsonNode;

// import com.nimbusds.jose.*;
// import com.nimbusds.jose.crypto.RSASSASigner;
// import com.nimbusds.jose.jwk.RSAKey;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.gluu.agama.openbanking.OpenbankingConsentService;

public class OpenbankingConsentServiceImpl extends OpenbankingConsentService {

    private static final String CLIENT_ID_CLAIM = "client_id";
    private static final String KEY_ID_CLAIM = "kid";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Instance state — one object per flow execution (see getInstance below),
    // never static/shared, so concurrent flow executions cannot contaminate
    // each other's consent/client/acr correlation.
    private String OPENBANKING_CONSENT_ID;
    private String CLIENT_ID;
    private String ACR_VALUE;

    private String CONSENT_ENGINE_BASE_URL;
    private String RFAC_APP_URL;
    private HashMap<String, String> flowConfig;
    private String SERVER_BASE_URL;

    public OpenbankingConsentServiceImpl(HashMap config) {
        if (config != null) {
            LogUtils.log("Flow config provided is : %", config);
            flowConfig = config;
            this.SERVER_BASE_URL = NetworkUtils.urlBeforeContextPath();
            this.CONSENT_ENGINE_BASE_URL = (String) flowConfig.get("consentBaseUrl");
            this.RFAC_APP_URL = (String) flowConfig.get("rfacAppUrl");
        } else {
            LogUtils.log("No configuration provided. consentBaseUrl/rfacAppUrl must be set via flow config; calls that need them will fail closed.");
        }
    }

    public OpenbankingConsentServiceImpl() {}

    // Not a singleton: each flow execution must get its own instance so
    // per-request state (CLIENT_ID, ACR_VALUE, OPENBANKING_CONSENT_ID) can
    // never leak between concurrent sessions.
    public static OpenbankingConsentServiceImpl getInstance(HashMap config) {
        return new OpenbankingConsentServiceImpl(config);
    }

    @Override
    public Map<String, Object> validateConsent() {
        Map<String, Object> validationResult = new HashMap<>();
        try {
            LogUtils.log("OPEN_BANKING: Retrieve request object from session...");
            Map<String, String> sessionAttrs = getSessionId().getSessionAttributes();
            this.ACR_VALUE = sessionAttrs.get("acr");
            if (this.ACR_VALUE != null && this.ACR_VALUE.startsWith("agama_")) {
                this.ACR_VALUE = this.ACR_VALUE.substring("agama_".length());
            }

            String consentId;
            String rawjwt = sessionAttrs.get("request");
            if (rawjwt != null) {
                if (!verifyJwt(rawjwt)) {
                    validationResult.put("valid", false);
                    validationResult.put("message", "Request object JWT verification failed.");
                    return validationResult;
                }
                consentId = extractOpenBankingIntentId(rawjwt);
                if (consentId == null) {
                    validationResult.put("valid", false);
                    validationResult.put("message", "openbanking_intent_id not found in request object");
                    return validationResult;
                }
            } else {
                LogUtils.log("No request object present in session");
                validationResult.put("valid", false);
                validationResult.put("message", "No request object present");
                return validationResult;
            }

            this.OPENBANKING_CONSENT_ID = consentId;
            LogUtils.log("Extracted openbanking_consent_id: %", consentId);

            boolean isValid = isAwaitingAuthorisation(consentId);
            if (isValid) {
                LogUtils.log("Consent validation successful for consentId: %", consentId);
                validationResult.put("valid", true);
                validationResult.put("message", "Consent validation successful for consentId");
            } else {
                LogUtils.log("Consent validation failed for consentId: %", consentId);
                validationResult.put("valid", false);
                validationResult.put("message", "Consent validation failed for consentId");
            }
            return validationResult;

        } catch (Exception e) {
            LogUtils.log("Error: %", e);
            validationResult.put("valid", false);
            validationResult.put("message", "Exception during consent validation: " + e.getMessage());
            return validationResult;
        }
    }

    private boolean isConsentCertPathProvided() {
        return flowConfig != null && "true".equalsIgnoreCase((String) flowConfig.get("isConsentCertPath"));
    }

    private String requireConfig(String key) {
        String value = flowConfig != null ? (String) flowConfig.get(key) : null;
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required flow configuration: " + key);
        }
        return value;
    }

    /**
     * Initial check: compare the Consent Engine status with the configured
     * consentStatusForAuthorise value before starting the RFAC journey.
     */
    private boolean isAwaitingAuthorisation(String consentId) {
        try {
            String expectedStatus = requireConfig("consentStatusForAuthorise");
            String actualStatus = fetchConsentStatus(consentId);
            return expectedStatus.equals(actualStatus);
        } catch (Exception e) {
            LogUtils.log("Exception while checking initial consent status: %", e);
            return false;
        }
    }

    /**
     * Final check, independent of the App's self-reported callback status:
     * compare the Consent Engine status with the configured approved status.
     */
    private boolean isFinalAuthorised(String consentId) {
        try {
            String actualStatus = fetchConsentStatus(consentId);
            String approvedStatus = requireConfig("consentStatusForApproved");
            if (approvedStatus.equals(actualStatus)) {
                return true;
            }

            if (isConfiguredFailureStatus(actualStatus)) {
                LogUtils.log("Consent Engine returned a configured failure status: %", actualStatus);
            } else {
                LogUtils.log("Consent Engine status % does not match configured approved status %", actualStatus, approvedStatus);
            }
            return false;
        } catch (Exception e) {
            LogUtils.log("Exception while checking final consent status: %", e);
            return false;
        }
    }

    /**
     * Checks the comma-separated consentStatusFailures configuration value.
     * This is used for classification/logging; any status other than the
     * configured approved status remains a failed final authorisation check.
     */
    private boolean isConfiguredFailureStatus(String actualStatus) {
        if (actualStatus == null || flowConfig == null) {
            return false;
        }
        String configuredFailures = (String) flowConfig.get("consentStatusFailures");
        if (configuredFailures == null || configuredFailures.isBlank()) {
            return false;
        }
        for (String failureStatus : configuredFailures.split(",")) {
            if (actualStatus.equals(failureStatus.trim())) {
                return true;
            }
        }
        return false;
    }

    private String fetchConsentStatus(String consentId) throws Exception {
        LogUtils.log("OPEN_BANKING: Fetching consent status for consentId: %", consentId);
        String authenticationToken = requireConfig("consentApiAuthToken");
        String basicAuthHeader = requireConfig("consentApiBasicAuth");
        // Optional path prefix allows deployments to expose the Consent Engine
        // behind an API gateway. If absent/blank, preserve the legacy URL path.
        String consentBasePath = flowConfig != null
                ? (String) flowConfig.get("consentBasePath")
                : null;
        String validationPath;
        if (consentBasePath != null && !consentBasePath.isBlank()) {
            String normalizedBasePath = consentBasePath.trim();
            if (!normalizedBasePath.startsWith("/")) {
                normalizedBasePath = "/" + normalizedBasePath;
            }
            if (!normalizedBasePath.endsWith("/")) {
                normalizedBasePath += "/";
            }
            validationPath = normalizedBasePath + "internal-consent/consent/" + URLEncoder.encode(consentId, StandardCharsets.UTF_8);
        } else {
            validationPath = "/internal-consent/consent/" + URLEncoder.encode(consentId, StandardCharsets.UTF_8);
        }
        String validationUrl = this.CONSENT_ENGINE_BASE_URL + validationPath;

        // Create the appropriate HTTP client depending on whether
        // a custom consent certificate has been configured.
        HttpClient httpClient;

        if (isConsentCertPathProvided()) {
            LogUtils.log("Consent certificate is configured. Using custom HttpClient.");
            httpClient = createConsentHttpClient();
        } else {
            LogUtils.log("No consent certificate configured. Using default HttpClient.");
            httpClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
        

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(validationUrl))
                .header("accept", "application/json")
                .header("Authentication", "Bearer " + authenticationToken)
                .header("Authorization", "Basic " + basicAuthHeader)
                .GET()
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException(
                    "Consent validation failed. HTTP "
                            + response.statusCode()
                            + ": "
                            + response.body()
            );
        }

        JsonNode responseJson = OBJECT_MAPPER.readTree(response.body());
        LogUtils.log("Validation Api response: %", responseJson);
        return responseJson
                .path("linkedConsent")
                .path("Data")
                .path("Status")
                .asText(null);
    }

    private String extractOpenBankingIntentId(String rawjwt) {
        try {
            Jwt jwt = Jwt.parse(rawjwt);
            // Navigate through nested claims structure
            JSONObject claims = jwt.getClaims().toJsonObject();
            // The attribute is nested like: claims -> userinfo -> openbanking_intent_id -> value
            JSONObject userInfo = claims.getJSONObject("claims")
                    .getJSONObject("userinfo");

            JSONObject intentObject = userInfo.getJSONObject("openbanking_intent_id");
            String intentId = intentObject.getString("value");
            return intentId;

        } catch (Exception e) {
            LogUtils.log("Error extracting openbanking_intent_id: %", e);
            return null;
        }
    }

    private boolean verifyJwt(String rawjwt) {
        try {
            AbstractCryptoProvider cryptoprovider = CdiUtil.bean(AbstractCryptoProvider.class);
            Jwt jwt = Jwt.parse(rawjwt);
            String client_id = jwt.getClaims().getClaimAsString(CLIENT_ID_CLAIM);
            this.CLIENT_ID = client_id;
            ClientService clientservice = CdiUtil.bean(ClientService.class);
            Client client = clientservice.getClient(client_id);
            if (client == null) {
                LogUtils.log("Jwt verification failed. Client with client_id : % not found", client_id);
                return false;
            }
            String clientsecret = clientservice.decryptSecret(client.getClientSecret());
            JSONObject jwks = CommonUtils.getJwks(client);
            if (jwks == null) {
                LogUtils.log("Jwt verification failed. Client : % has no jwks", client_id);
                return false;
            }
            final JwtHeader jwtheader = jwt.getHeader();
            final String keyId = jwtheader.getKeyId();
            final SignatureAlgorithm signatureAlg = jwtheader.getSignatureAlgorithm();
            final String[] jwtParts = rawjwt.split("\\.");
            if (jwtParts.length != 3) {
                LogUtils.log("Invalid JWT format. Parts length: %", jwtParts.length);
                return false;
            }
            final String signingInput = jwtParts[0] + "." + jwtParts[1];
            final String encodedSignature = jwtParts[2];
            final boolean result = cryptoprovider.verifySignature(signingInput, encodedSignature, keyId, jwks, clientsecret, signatureAlg);
            if (result) {
                LogUtils.log("Jwt verification successfull");
                return true;
            } else {
                LogUtils.log("Jwt verification failed. Cryptographic provider failed to validate the jwt");
                return false;
            }
        } catch (Exception e) {
            LogUtils.log("Exception : %", e);
            return false;
        }
    }

    /**
     * Callback trust: the caller-asserted client_id in the App's JWS is only
     * trusted if it matches a configured trustedCallbackClientId, or (if none
     * is configured) the client_id that originated this flow execution. This
     * stops an arbitrary registered OIDC client from forging a callback just
     * by self-asserting client_id in its own signed token.
     */
    private boolean isTrustedCallbackClient(String callbackClientId) {
        String trustedCallbackClientId = flowConfig != null ? (String) flowConfig.get("trustedCallbackClientId") : null;
        if (trustedCallbackClientId != null && !trustedCallbackClientId.isBlank()) {
            return trustedCallbackClientId.equals(callbackClientId);
        }
        return this.CLIENT_ID != null && this.CLIENT_ID.equals(callbackClientId);
    }

    /**
     * Verify the BankApp callback JWT using the BankApp/customer public key from the configured
     * InJwTSignJwks JSON Web Key Set. This is intentionally separate
     * from verifyJwt(), which verifies the incoming OAuth request object via
     * the registered OAuth client's JWKS.
     */
    private boolean verifyJwtForExternalApp(String rawjwt) {
        try {
            if (rawjwt == null || rawjwt.isBlank()) {
                LogUtils.log("Callback JWT is missing");
                return false;
            }

            String token = rawjwt.trim();
            // The callback may arrive URL-encoded as a query parameter.
            if (token.contains("%2E") || token.contains("%2e") || token.contains("%2F") || token.contains("%2f")) {
                token = java.net.URLDecoder.decode(token, StandardCharsets.UTF_8);
            }
            token = token.replaceAll("[\\s\\uFEFF\\u200B]", "");

            Jwt parsedJwt = Jwt.parse(token);
            if (parsedJwt.getHeader().getSignatureAlgorithm() != SignatureAlgorithm.RS256) {
                LogUtils.log("Callback JWT rejected: only RS256 is allowed");
                return false;
            }

            // InJwTSignJwks must be a JWKS JSON object containing the BankApp
            // public key and a kid matching the callback JWT header.
            JSONObject configuredJwks = new JSONObject(requireConfig("InJwTSignJwks"));
            Supplier<JSONObject> jwksSupplier = () -> configuredJwks;
            CryptoHelper.JwtVerificationResult verification = CryptoHelper.verifyJwt(
                    token, jwksSupplier, new CryptoHelper.BlankSecretSupplier());
            if (verification != CryptoHelper.JwtVerificationResult.JWT_VERIFICATION_SUCCESS) {
                LogUtils.log("Callback JWT signature verification failed");
                return false;
            }

            JSONObject claims = parsedJwt.getClaims().toJsonObject();
            long exp = claims.optLong("exp", -1L);
            if (exp <= 0 || Instant.now().getEpochSecond() >= exp) {
                LogUtils.log("Callback JWT rejected: missing or expired exp claim");
                return false;
            }
            long iat = claims.optLong("iat", -1L);
            if (iat > 0 && iat > Instant.now().getEpochSecond() + 60) {
                LogUtils.log("Callback JWT rejected: iat is in the future");
                return false;
            }

            LogUtils.log("Callback JWT signature and expiry validated successfully");
            return true;
        } catch (Exception e) {
            LogUtils.log("Exception during external App JWT verification: %", e.getMessage());
            return false;
        }
    }

    @Override
    public String prepareRfacRespPayload() {
        try {
            LogUtils.log("Preparing RFAC request payload");
            JSONObject payload = new JSONObject();
            long now = Instant.now().getEpochSecond();
            payload.put("iss", this.SERVER_BASE_URL);
            payload.put("iat", now);
            payload.put("exp", now + 300); // 5 minutes
            payload.put("openbanking_intent_id", OPENBANKING_CONSENT_ID);
            payload.put("clientId", CLIENT_ID);
            payload.put("acr_values", ACR_VALUE);
            payload.put("callback", this.SERVER_BASE_URL + "/jans-auth/fl/callback");
            payload.put("aud", this.RFAC_APP_URL);
            payload.put("channel", "web|app");

            // Outgoing JWT is signed with our configured private key.
            String signedJwt = signJwtWithConfiguredKeyId(payload);
            LogUtils.log("RFAC request JWT signed with Jans key ID from OutJwTSignKeyId");
            return signedJwt;
        } catch (Exception e) {
            LogUtils.log("Error while preparing/signing RFAC payload: %", e);
            return null;
        }
    }

    /**
     * Signs the supplied claims with the Jans-managed private key identified by
     * OutJwTSignKeyId. The key itself must already be available to the Jans
     * AbstractCryptoProvider; this configuration value is a key ID (kid), not PEM.
     */
    private String signJwtWithConfiguredKeyId(JSONObject claims) throws Exception {
        String keyId = requireConfig("OutJwTSignKeyId");
        JwtSigningOptions options = JwtSigningOptions.of(keyId, SignatureAlgorithm.RS256);
        return CryptoHelper.signJwt(claims, options);
    }

    /**
     * Standalone sample for testing the configured outbound Jans signing key ID.
     * This is not a separate protocol endpoint; it returns a signed test JWT.
     */
    public String sampleSignJwtWithConfiguredKeyId() throws Exception {
        JSONObject sampleClaims = new JSONObject();
        long now = Instant.now().getEpochSecond();
        sampleClaims.put("iss", this.SERVER_BASE_URL);
        sampleClaims.put("iat", now);
        sampleClaims.put("exp", now + 300);
        sampleClaims.put("sample", true);
        return signJwtWithConfiguredKeyId(sampleClaims);
    }

    @Override
    public String buildRfacUrl(String signedJws) {
        if (signedJws == null || RFAC_APP_URL == null) return null;
        String encoded = URLEncoder.encode(signedJws, StandardCharsets.UTF_8);
        return RFAC_APP_URL + "?request=" + encoded;
    }

    @Override
    public Map<String, Object> verifyExternalAppResult(Map<String, String> resultFromApp) {
        LogUtils.log("OPEN_BANKING: Verify External App Result...");
        Map<String, Object> validationResult = new HashMap<>();
        try {
            String jws = (String) resultFromApp.get("jws");
            if (!verifyJwtForExternalApp(jws)) {
                validationResult.put("valid", false);
                validationResult.put("message", "Invalid JWS signature from App");
                return validationResult;
            }

            Map<String, String> extracted = extractAttributesFromAppJws(jws);
            String intentId = extracted.get("openbanking_intent_id");
            String userId = extracted.get("sub");

            if (intentId == null) {
                validationResult.put("valid", false);
                validationResult.put("message", "ConsentId not found");
                return validationResult;
            }

            // Binding: the callback must be about the same consent this flow
            // execution itself created/validated, not any consentId at all.
            if (this.OPENBANKING_CONSENT_ID == null || !this.OPENBANKING_CONSENT_ID.equals(intentId)) {
                LogUtils.log("Callback consentId % does not match this flow execution's consentId %", intentId, this.OPENBANKING_CONSENT_ID);
                validationResult.put("valid", false);
                validationResult.put("message", "ConsentId does not match this flow execution");
                return validationResult;
            }

            if (userId == null || userId.isBlank()) {
                LogUtils.log("Callback JWS has no verified sub (user identity) claim");
                validationResult.put("valid", false);
                validationResult.put("message", "No verified user identity in callback");
                return validationResult;
            }

            // Final check is independent of the App's own self-reported
            // status claim — always re-check the Consent Engine directly.
            boolean isValid = isFinalAuthorised(intentId);
            if (isValid) {
                validationResult.put("valid", true);
                validationResult.put("openbanking_intent_id", intentId);
                validationResult.put("acr_values", extracted.get("acr_values"));
                validationResult.put("status", extracted.get("status"));
                validationResult.put("userId", userId);
                validationResult.put("message", "External app result verify successful");
            } else {
                validationResult.put("valid", false);
                validationResult.put("message", "ConsentId is not in the configured approved state");
            }
            return validationResult;

        } catch (Exception e) {
            validationResult.put("valid", false);
            validationResult.put("message", "Exception parsing JWS: " + e.getMessage());
            return validationResult;
        }
    }

    private Map<String, String> extractAttributesFromAppJws(String rawJwt) {
        Map<String, String> result = new HashMap<>();
        Jwt jwt = Jwt.parse(rawJwt);
        JSONObject claims = jwt.getClaims().toJsonObject();
        result.put("openbanking_intent_id", claims.optString("openbanking_intent_id", null));
        result.put("acr_values", claims.optString("acr_values", null));
        result.put("status", claims.optString("consent_status", null));
        // Verified user identity, from the callback token's own subject
        // claim — never fall back to a fixed/default identity here.
        result.put("sub", claims.optString("sub", null));
        return result;
    }

    private SessionId getSessionId() {
        SessionIdService sis = CdiUtil.bean(SessionIdService.class);
        return sis.getSessionId(CdiUtil.bean(HttpServletRequest.class));
    }


    private HttpClient createConsentHttpClient() throws Exception {
        String certPath = requireConfig("consentCertPath");

        LogUtils.log("Loading Consent API certificate from: " + certPath);

        CertificateFactory certificateFactory =
                CertificateFactory.getInstance("X.509");

        Certificate certificate;

        try (InputStream inputStream =
                    Files.newInputStream(Path.of(certPath))) {

            certificate = certificateFactory.generateCertificate(inputStream);
        }

        KeyStore trustStore =
                KeyStore.getInstance(KeyStore.getDefaultType());

        trustStore.load(null, null);

        trustStore.setCertificateEntry(
                "consent-engine",
                certificate
        );

        TrustManagerFactory trustManagerFactory =
                TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm()
                );

        trustManagerFactory.init(trustStore);

        SSLContext sslContext =
                SSLContext.getInstance("TLS");

        sslContext.init(
                null,
                trustManagerFactory.getTrustManagers(),
                null
        );

        return HttpClient.newBuilder()
                .sslContext(sslContext)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }    

}
