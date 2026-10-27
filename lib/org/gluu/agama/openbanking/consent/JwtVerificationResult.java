package org.gluu.agama.openbanking.consent;

public enum JwtVerificationResult {
    JWT_VERIFICATION_SUCCESS,
    JWT_VERIFICATION_FAILED;
    
    public boolean asBoolean() {

        return this == JWT_VERIFICATION_SUCCESS ? true : false;
    }
}