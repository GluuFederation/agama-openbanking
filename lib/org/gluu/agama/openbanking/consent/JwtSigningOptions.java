package org.gluu.agama.openbanking.consent;

import org.json.JSONObject;

import io.jans.as.model.crypto.signature.SignatureAlgorithm;

public class JwtSigningOptions {

    private final String keyId;
    private final SignatureAlgorithm signatureAlgorithm;

    private JwtSigningOptions(String keyId, SignatureAlgorithm signatureAlgorithm) {

        this.keyId = keyId;
        this.signatureAlgorithm = signatureAlgorithm;
    }

    public String getKeyId() {

        return keyId;
    }

    public SignatureAlgorithm getSignatureAlgorithm() {

        return signatureAlgorithm;
    }

    public JSONObject asJwtHeader() {

        JSONObject header = new JSONObject();
        header.put("alg",signatureAlgorithm.getName());
        header.put("typ","JWT");
        header.put("kid",keyId);
        return header;
    }

    public static JwtSigningOptions of(String keyId, SignatureAlgorithm signatureAlgorithm) {

        if (keyId == null) 
            throw new IllegalArgumentException("KeyId is required");

        if (signatureAlgorithm == null) 
            throw new IllegalArgumentException("Signature algorithm is required");

        
        return new JwtSigningOptions(keyId, signatureAlgorithm);
    }


}