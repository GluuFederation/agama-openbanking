package org.gluu.agama.openbanking;

import java.util.HashMap;
import java.util.Map;

import org.gluu.agama.openbanking.consent.OpenbankingConsentServiceImpl;

public abstract class OpenbankingConsentService {

    public abstract Map<String, Object> verifyExternalAppResult(Map<String, String> resultFromApp);

    public abstract String prepareRfacRespPayload();

    public abstract Map<String, Object> validateConsent();

    public abstract String buildRfacUrl(String signedJws);


    public static OpenbankingConsentService getInstance(HashMap config){
        return OpenbankingConsentServiceImpl.getInstance(config);
    }
}