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
            this.CONSENT_ENGINE_BASE_URL = (String) flowConfig.get("consentEngineBaseUrl");
            this.RFAC_APP_URL = (String) flowConfig.get("rfacAppUrl");
        } else {
            LogUtils.log("No configuration provided. consentEngineBaseUrl/rfacAppUrl must be set via flow config; calls that need them will fail closed.");
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
            } else if (isTestConsentCreationEnabled()) {
                LogUtils.log("TEST-ONLY: no request object present in session; self-creating a consent for local test setup");
                consentId = createConsent();
            } else {
                LogUtils.log("No request object present in session and test consent creation is disabled");
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

    private boolean isTestConsentCreationEnabled() {
        return flowConfig != null && "true".equalsIgnoreCase((String) flowConfig.get("testCreateConsent"));
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
     * Initial check: the Consent Engine must report AwaitingAuthorisation
     * before Agama starts the RFAC journey.
     */
    private boolean isAwaitingAuthorisation(String consentId) {
        try {
            return "AwaitingAuthorisation".equals(fetchConsentStatus(consentId));
        } catch (Exception e) {
            LogUtils.log("Exception while checking initial consent status: %", e);
            return false;
        }
    }

    /**
     * Final check, independent of the App's self-reported callback status:
     * the Consent Engine must report Authorised. Never conflate this with
     * isAwaitingAuthorisation — they check different states.
     */
    private boolean isFinalAuthorised(String consentId) {
        try {
            return "Authorised".equals(fetchConsentStatus(consentId));
        } catch (Exception e) {
            LogUtils.log("Exception while checking final consent status: %", e);
            return false;
        }
    }

    private String fetchConsentStatus(String consentId) throws Exception {
        LogUtils.log("OPEN_BANKING: Fetching consent status for consentId: %", consentId);
        String authenticationToken = requireConfig("consentApiAuthToken");
        String basicAuthHeader = requireConfig("consentApiBasicAuth");
        String validationUrl = this.CONSENT_ENGINE_BASE_URL + "/internal-consent/consent/" + consentId;

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

    private boolean verifyJwtForExternalApp(String rawjwt) {
        try {
            if (rawjwt == null) return false;
            rawjwt = rawjwt.trim();
            rawjwt = java.net.URLDecoder.decode(rawjwt, StandardCharsets.UTF_8).trim();
            rawjwt = rawjwt.replaceAll("[\\s\\uFEFF\\u200B]", "");
            AbstractCryptoProvider cryptoprovider = CdiUtil.bean(AbstractCryptoProvider.class);
            Jwt jwt = Jwt.parse(rawjwt);

            String client_id = jwt.getClaims().getClaimAsString(CLIENT_ID_CLAIM);

            ClientService clientservice = CdiUtil.bean(ClientService.class);
            Client client = clientservice.getClient(client_id);
            if (client == null) {
                LogUtils.log("Jwt verification failed. Client with client_id : % not found", client_id);
                return false;
            }

            if (!isTrustedCallbackClient(client_id)) {
                LogUtils.log("Jwt verification failed. client_id : % is not the trusted signer for this flow execution", client_id);
                return false;
            }

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

            final String signingInput = jwtParts[0].trim() + "." + jwtParts[1].trim();
            final String encodedSignature = jwtParts[2].trim();

            boolean signatureValid = cryptoprovider.verifySignature(signingInput, encodedSignature, keyId, jwks, null, signatureAlg);
            if (!signatureValid) {
                LogUtils.log("Jwt verification failed. Cryptographic provider failed to validate the jwt");
                return false;
            }

            long exp = jwt.getClaims().toJsonObject().optLong("exp", -1L);
            if (exp <= 0 || Instant.now().getEpochSecond() >= exp) {
                LogUtils.log("Jwt verification failed. Callback token is missing exp or has expired");
                return false;
            }

            LogUtils.log("Jwt verification successful");
            return true;

        } catch (Exception e) {
            LogUtils.log("Exception during JWT verification: %", e.getMessage());
            return false;
        }
    }

    @Override
    public String prepareRfacRespPayload() {
        try {
            LogUtils.log("Preparing RFAC Response payload");

            // Build Payload JSON using per-execution instance state. Note:
            // this payload is the REQUEST sent to the App inviting it to
            // collect a decision — it must not pre-assert a consent_status,
            // since Agama hasn't received a decision yet.
            JSONObject payload = new JSONObject();
            long now = System.currentTimeMillis() / 1000L; // Unix timestamp in seconds
            payload.put("iss", this.SERVER_BASE_URL);
            payload.put("iat", now);
            payload.put("exp", now + 300); // expires in 5 minutes
            payload.put("openbanking_intent_id", OPENBANKING_CONSENT_ID);
            payload.put("clientId", CLIENT_ID);
            payload.put("acr_values", ACR_VALUE);
            payload.put("callback", this.SERVER_BASE_URL + "/jans-auth/fl/callback");
            payload.put("aud", this.RFAC_APP_URL);
            payload.put("channel", "web|app");

            // Get internal JWKS configuration
            WebKeysConfiguration webKeysConfig = CdiUtil.bean(WebKeysConfiguration.class);
            AbstractCryptoProvider cryptoProvider = CdiUtil.bean(AbstractCryptoProvider.class);

            // Pick a valid signing key
            SignatureAlgorithm algorithm = SignatureAlgorithm.RS256;
            String keyId = null;
            String use = Use.SIGNATURE;
            String family = algorithm.getFamily().getValue();

            for (JSONWebKey key : webKeysConfig.getKeys()) {
                String keyUse = key.getUse();
                String keyType = key.getKty();
                if (use.equals(keyUse) && family.equals(keyType)) {
                    LogUtils.log("Signing Key Id is: %", key.getKid());
                    keyId = key.getKid();
                    break;
                }
            }

            if (keyId == null) {
                LogUtils.log("No suitable signing key found in internal JWKS");
                throw new RuntimeException("No suitable signing key found in internal JWKS");
            }

            JSONObject header = new JSONObject();
            header.put("alg", algorithm.getName());
            header.put("typ", "JWT");
            header.put("kid", keyId);

            String encodedHeader = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(header.toString().getBytes(StandardCharsets.UTF_8));
            String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));

            String signingInput = encodedHeader + "." + encodedPayload;

            String signature = cryptoProvider.sign(signingInput, keyId, null, algorithm);

            String signedJws = signingInput + "." + signature;

            LogUtils.log("RFAC JWS created successfully with internal key: %", keyId);
            return signedJws;

        } catch (Exception e) {
            LogUtils.log("Error while preparing RFAC payload : %", e);
            return null;
        }
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
                validationResult.put("message", "ConsentId is not in Authorised state");
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

    /**
     * TEST-ONLY: creates a brand-new consent so this flow can be exercised
     * without a real TPP/Bank App already having created one via the
     * customer Consent API. Real journeys must arrive with a consent id
     * from an already-verified Request Object (see validateConsent above).
     * Gated by flowConfig.testCreateConsent — must not run unless explicitly
     * enabled for a test profile.
     */
    private String createConsent() throws Exception {
        LogUtils.log("TEST-ONLY: Creating consent id for local test setup");
        String apiKey = requireConfig("testConsentApiKey");
        String accessToken = requireConfig("testConsentAccessToken");
        String CONSENT_URL = this.CONSENT_ENGINE_BASE_URL + "/account-access/open-banking/v3.1.11/aisp/account-access-consents";
        LogUtils.log("CONSENT_ENGINE_BASE_URL: " + this.CONSENT_ENGINE_BASE_URL);
        LogUtils.log("CONSENT_URL: " + CONSENT_URL);

        Instant now = Instant.now();
        Instant transactionFrom = now.minus(7, ChronoUnit.DAYS);
        Instant transactionTo = now;
        Instant expiration = now.plus(1, ChronoUnit.DAYS);

        String requestBody = String.format("""
                {
                  "Data": {
                    "Permissions": [
                      "ReadAccountsBasic"
                    ],
                    "ExpirationDateTime": "%s",
                    "TransactionFromDateTime": "%s",
                    "TransactionToDateTime": "%s"
                  },
                  "Risk": {}
                }
                """,
                expiration,
                transactionFrom,
                transactionTo
        );

        String fapiAuthDate = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern(
                        "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
                ));
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
                .uri(URI.create(CONSENT_URL))
                .header("Accept", "application/json; charset=utf-8")
                .header("User-Agent", "Mozilla/5.0")
                .header("x-fapi-auth-date", fapiAuthDate) 
                .header("x-api-key", apiKey)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );
        LogUtils.log("Consent API HTTP status: " + response.statusCode());

        LogUtils.log("Consent API response: " + response.body());
        
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException(
                    "Consent API failed. HTTP "
                            + response.statusCode()
                            + ": "
                            + response.body()
            );
        }

        JsonNode responseJson = OBJECT_MAPPER.readTree(response.body());
        LogUtils.log("CREATE ConsentID api response: %", responseJson);
        JsonNode consentIdNode = responseJson
                .path("Data")
                .path("ConsentId");

        if (consentIdNode.isMissingNode() || consentIdNode.isNull()) {
            throw new RuntimeException(
                    "ConsentId not found in API response: "
                            + response.body()
            );
        }

        return consentIdNode.asText();
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