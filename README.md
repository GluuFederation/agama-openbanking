# Agama Open Banking Project

This project provides an **Agama authentication flow for Open Banking** using the Janssen Server / Gluu Flex Agama Framework.

The project integrates Open Banking consent management with the authorization flow. It allows the Agama flow to validate an Open Banking consent and use the consent status as part of the authorization journey.

## Where To Deploy

The project can be deployed to any IAM server that runs an implementation of the **Agama Framework**, such as:

* [Janssen Server](https://docs.jans.io/)
* [Gluu Flex](https://gluu.org/)

The project is designed to work with the Janssen Server Auth Server and its Agama Framework.

---

## How To Deploy

Deployment of an Agama project generally involves the following steps:

1. Download or build the `.gama` package.
2. Add the `.gama` package to the IAM server.
3. Configure the project.
4. Test the flow using an OpenID Connect authorization request.

### Prerequisites

Before deploying this project, ensure the following components are available:

* Janssen Server or Gluu Flex
* Open Banking Consent Engine
* Valid Open Banking API credentials
* Network connectivity between the IAM server and the Open Banking APIs

For a production environment, the Open Banking APIs should use a certificate trusted by the Java runtime / IAM server.

---

## Download The Project

The project is bundled as an `.gama` package.

You can deploy the project using the Janssen Server TUI, Admin UI.

If the project is available through the Janssen Server community projects list, it can also be downloaded and deployed directly using the TUI.

---

## Add The Project To The Server

The Janssen Server provides multiple methods for deploying an Agama project.

For example, using the Janssen Server TUI:

1. Open the Janssen Server TUI.
2. Navigate to the Agama / Community Projects section.
3. Locate the agama-openbanking project.
4. Download and deploy the project.
5. Configure the project parameters.

The project can also be deployed through the appropriate Admin UI.

---

# Configure The Project

The Open Banking Agama flow uses the following configuration parameters.

Example configuration:

```json
{
    "consentEngineBaseUrl": "<CONSENT_ENGINE_BASE_URL>",
    "rfacAppUrl": "<RFAC_APP_URL>",
    "testCreateConsent": "<true|false>",
    "testConsentApiKey": "<>",
    "testConsentAccessToken": "<>",
    "consentApiBasicAuth": "<>",
    "isConsentCertPath": "<true|false>",
    "consentCertPath": "<>"
}
```

## Configuration Parameters

| Parameter                | Required    | Description                                                                                                                     |
| ------------------------ | ----------- | ------------------------------------------------------------------------------------------------------------------------------- |
| `consentEngineBaseUrl`   | Yes         | Base URL of the Open Banking Consent Engine.                                                                                    |
| `rfacAppUrl`             | Yes         | URL of the RFAC application used during the Open Banking authorization flow.                                                    |
| `testCreateConsent`      | Yes         | Enables or disables test consent creation. Set to `true` to create a consent through the configured Consent Engine API.         |
| `testConsentApiKey`      | Conditional | API key used to create a test consent. Required when `testCreateConsent` is `true`.                                             |
| `testConsentAccessToken` | Conditional | Bearer access token used to create a test consent. Required when `testCreateConsent` is `true`.                                 |
| `consentApiBasicAuth`    | Conditional | Basic authentication credential used when communicating with the Consent Engine API, if required by the environment.            |
| `isConsentCertPath`      | Yes         | Determines whether the flow should use a certificate from the configured certificate path for Consent Engine TLS communication. |
| `consentCertPath`        | Conditional | Path to the Consent Engine certificate. Required when `isConsentCertPath` is `true`.                                            |

### Example: Test Consent Creation Enabled

```json
{
    "consentEngineBaseUrl": "https://consent.example.com",
    "rfacAppUrl": "https://rfac.example.com",
    "testCreateConsent": "true",
    "testConsentApiKey": "YOUR_API_KEY",
    "testConsentAccessToken": "YOUR_ACCESS_TOKEN",
    "consentApiBasicAuth": "YOUR_BASIC_AUTH",
    "isConsentCertPath": "true",
    "consentCertPath": "/opt/jans/crts/consent-engine-cert.pem"
}
```

### Example: Test Consent Creation Disabled

```json
{
    "consentEngineBaseUrl": "https://consent.example.com",
    "rfacAppUrl": "https://rfac.example.com",
    "testCreateConsent": "false",
    "testConsentApiKey": "",
    "testConsentAccessToken": "",
    "consentApiBasicAuth": "",
    "isConsentCertPath": "false",
    "consentCertPath": ""
}
```

## Certificate Configuration

If the Consent Engine uses a self-signed certificate or a private CA, configure the certificate path using:

```json
{
    "isConsentCertPath": "true",
    "consentCertPath": "/opt/jans/crts/consent-engine-cert.pem"
}
```

When `isConsentCertPath` is `false`, the `consentCertPath` value is not used.



# Consent Validation

After receiving the `ConsentId`, the flow validates the consent using:

```text
GET /internal-consent/consent/{consentId}
```

For example:

```text
GET /internal-consent/consent/example-consent-id
```

The flow checks the returned consent status.

The expected status during the authorization process is:

```text
AwaitingAuthorisation
```

Conceptually:

```text
ConsentId
    |
    v
Consent Engine
    |
    v
Consent Status
    |
    +-- AwaitingAuthorisation --> Continue
    |
    +-- Other status -----------> Stop / Error
```

---

# Authorization Request

The next stage of the project associates the Open Banking intent/consent with the OAuth 2.0 / OpenID Connect authorization request.

The authorization request needs to provide the Agama flow with the Open Banking intent identifier so that the flow can validate the correct consent.



The Agama flow can then retrieve the intent ID and validate the corresponding consent.

### OIDC Request Object

The preferred Open Banking implementation may use an OIDC Request Object.

In that approach, the authorization request contains a signed request object:

```text
TPP
 |
 | Authorization Request
 | + Request Object
 v
Jans Auth Server
 |
 v
Agama Flow
 |
 | Extract intent/consent information
 v
Consent Engine
```

Support for the final TPP/request-object integration is part of the subsequent implementation work.

---

# Sequence Diagram

The following diagram shows the current Phase 1 consent integration and the planned authorization integration.

```mermaid
[sequenceDiagram
    actor User
    participant TPP
    participant Jans as Jans Auth Server
    participant Agama as Agama Flow
    participant Consent as Open Banking Consent Engine

    TPP->>Consent: Create Account Access Consent
    Consent-->>TPP: ConsentId

    TPP->>Jans: Authorization Request
    Note over TPP,Jans: intent_id / Request Object

    Jans->>Agama: Start Open Banking Agama Flow

    Agama->>Consent: Validate ConsentId
    Consent-->>Agama: Consent Status

    alt Consent is AwaitingAuthorisation
        Agama->>User: Authenticate / Authorize
        User-->>Agama: Authorization
        Agama-->>Jans: Authentication Success
        Jans-->>TPP: Authorization Code
    else Consent is invalid
        Agama-->>User: Display Error
    end]
```

---

# Flows In The Project

The project contains the main Open Banking authentication flow.

| Qualified Name                    | Description                           |
| --------------------------------- | ------------------------------------- |
| `urn.openbanking.psd2.sca` | Main Open Banking authorization flow. |

The exact qualified flow name should be confirmed from the deployed `project.json` / Agama project configuration.

---

## `urn.openbanking.psd2.sca`

This is the main flow responsible for integrating Open Banking consent validation with the Jans authorization process.

The flow is responsible for:

1. Receiving the Open Banking authorization request.
2. Obtaining the Open Banking intent/consent identifier.
3. Validating the consent with the Consent Engine.
4. Checking the consent status.
5. Continuing the authorization process when the consent is valid.
6. Returning an authorization result to the relying party.

---

# Test The Flow

Use an OpenID Connect relying party, such as `jans-tarp`, or another OIDC client to invoke the authorization endpoint.

The Jans Auth Server uses the `acr_values` parameter to identify the authentication method / Agama flow.


# Troubleshooting

## `PKIX path building failed`

If the Agama flow reports an error similar to:

```text
javax.net.ssl.SSLHandshakeException:
PKIX path building failed
```

the Java runtime does not trust the certificate presented by the Consent Engine.

Verify:

1. The Consent Engine certificate is valid.


---

# Development

The project can be developed and visualized using **Agama Lab**.

A typical development workflow is:

```text
Agama Lab
    |
    | Design / Modify
    v
Agama Project
    |
    | Export / Build
    v
.gama Package
    |
    | Deploy
    v
Janssen Server
    |
    | Test
    v
Open Banking Environment
```

When modifying the flow, update the corresponding project configuration and test the complete authorization journey before releasing a new version.

---

# Customize The Project

This project can be customized to support different Open Banking authorization journeys.

Possible customizations include:

* Authentication methods.
* Consent validation rules.
* Open Banking permissions.
* Consent status handling.
* TPP/OIDC client integration.
* Request Object processing.
* RFAC integration.
* User interface.
* Error handling.
* Bank-specific API integrations.

The project can also be extended with additional Agama sub-flows as the Open Banking implementation evolves.

---

# License

This project is licensed under the terms of the license included in this repository.
