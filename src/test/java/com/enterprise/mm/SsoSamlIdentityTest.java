package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

@SpringBootTest(properties = {
    "app.identity.sso.enabled=true",
    "app.identity.sso.provider-type=SAML",
    "app.identity.sso.login-url=https://idp.example.test/saml/login",
    "app.identity.sso.metadata-url=https://idp.example.test/saml/metadata",
    "app.identity.sso.idp-entity-id=urn:test:idp",
    "app.identity.sso.sp-entity-id=urn:test:mm-sp",
    "app.identity.sso.acs-url=http://localhost/api/auth/sso/saml/acs",
    "app.identity.sso.assertion-secret=test-saml-secret",
    "app.identity.sso.allowed-roles=VIEWER,MM_MANAGER,ADMIN"
})
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class SsoSamlIdentityTest {
    private static final String TENANT = "default";
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String IDP_ENTITY_ID = "urn:test:idp";
    private static final String SP_ENTITY_ID = "urn:test:mm-sp";
    private static final String ACS_URL = "http://localhost/api/auth/sso/saml/acs";
    private static final String SAML_PROTOCOL_NS = "urn:oasis:names:tc:SAML:2.0:protocol";
    private static final String SAML_ASSERTION_NS = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final KeyPair SAML_KEYS = generateKeyPair();

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @DynamicPropertySource
    static void samlProperties(DynamicPropertyRegistry registry) {
        registry.add("app.identity.sso.x509-certificate", SsoSamlIdentityTest::publicKeyPem);
    }

    @Test
    void samlLoginUrlUsesRelayStateAndConfiguredIdp() throws Exception {
        String response = mockMvc.perform(get("/api/auth/sso/login-url")
                .header("X-Tenant-Id", TENANT)
                .param("tenantId", TENANT)
                .param("companyId", COMPANY_ID.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode data = objectMapper.readTree(response).path("data");
        assertThat(data.path("authorizationUrl").asText()).startsWith("https://idp.example.test/saml/login?");
        assertThat(data.path("authorizationUrl").asText()).contains("RelayState=");
        assertThat(data.path("state").asText()).isEqualTo(TENANT + "|" + COMPANY_ID);
        assertThat(data.path("expiresAtEpochSeconds").asLong()).isGreaterThan(Instant.now().getEpochSecond());
    }

    @Test
    void samlAcsValidatesSignedAssertionAndCreatesJwt() throws Exception {
        String samlResponse = signedSamlResponse(SP_ENTITY_ID, ACS_URL, "saml-user-" + UUID.randomUUID(), "MM_MANAGER");

        String response = mockMvc.perform(post("/api/auth/sso/saml/acs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "relayState", TENANT + "|" + COMPANY_ID,
                    "samlResponse", samlResponse
                ))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode data = objectMapper.readTree(response).path("data");
        assertThat(data.path("token").asText()).isNotBlank();
        assertThat(data.path("username").asText()).startsWith("saml-user-");
        assertThat(data.path("roles").toString()).contains("ROLE_MM_MANAGER");
        assertThat(data.path("mfaMethod").asText()).isEqualTo("SAML");
    }

    @Test
    void samlAcsRejectsWrongAudience() throws Exception {
        String samlResponse = signedSamlResponse("urn:wrong:sp", ACS_URL, "saml-wrong-audience", "VIEWER");

        mockMvc.perform(post("/api/auth/sso/saml/acs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "tenantId", TENANT,
                    "companyId", COMPANY_ID,
                    "samlResponse", samlResponse
                ))))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void samlAcsRejectsReplayOfAssertionId() throws Exception {
        String samlResponse = signedSamlResponse(SP_ENTITY_ID, ACS_URL, "saml-replay-" + UUID.randomUUID(), "VIEWER");
        String payload = objectMapper.writeValueAsString(Map.of(
            "tenantId", TENANT,
            "companyId", COMPANY_ID,
            "samlResponse", samlResponse
        ));

        mockMvc.perform(post("/api/auth/sso/saml/acs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/sso/saml/acs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isUnprocessableEntity());
    }

    private static String signedSamlResponse(String audience, String recipient, String username, String role) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document document = factory.newDocumentBuilder().newDocument();

        Instant now = Instant.now();
        Element response = element(document, SAML_PROTOCOL_NS, "samlp:Response");
        response.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:samlp", SAML_PROTOCOL_NS);
        response.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:saml", SAML_ASSERTION_NS);
        response.setAttribute("ID", "_" + UUID.randomUUID());
        response.setAttribute("Version", "2.0");
        response.setAttribute("IssueInstant", now.toString());
        response.setAttribute("Destination", ACS_URL);
        document.appendChild(response);
        child(document, response, SAML_ASSERTION_NS, "saml:Issuer", IDP_ENTITY_ID);
        Element status = child(document, response, SAML_PROTOCOL_NS, "samlp:Status", null);
        Element statusCode = child(document, status, SAML_PROTOCOL_NS, "samlp:StatusCode", null);
        statusCode.setAttribute("Value", "urn:oasis:names:tc:SAML:2.0:status:Success");

        Element assertion = child(document, response, SAML_ASSERTION_NS, "saml:Assertion", null);
        String assertionId = "_" + UUID.randomUUID();
        assertion.setAttribute("ID", assertionId);
        assertion.setAttribute("Version", "2.0");
        assertion.setAttribute("IssueInstant", now.toString());
        assertion.setIdAttribute("ID", true);
        child(document, assertion, SAML_ASSERTION_NS, "saml:Issuer", IDP_ENTITY_ID);

        Element subject = child(document, assertion, SAML_ASSERTION_NS, "saml:Subject", null);
        child(document, subject, SAML_ASSERTION_NS, "saml:NameID", username);
        Element confirmation = child(document, subject, SAML_ASSERTION_NS, "saml:SubjectConfirmation", null);
        confirmation.setAttribute("Method", "urn:oasis:names:tc:SAML:2.0:cm:bearer");
        Element confirmationData = child(document, confirmation, SAML_ASSERTION_NS, "saml:SubjectConfirmationData", null);
        confirmationData.setAttribute("Recipient", recipient);
        confirmationData.setAttribute("NotOnOrAfter", now.plusSeconds(300).toString());

        Element conditions = child(document, assertion, SAML_ASSERTION_NS, "saml:Conditions", null);
        conditions.setAttribute("NotBefore", now.minusSeconds(60).toString());
        conditions.setAttribute("NotOnOrAfter", now.plusSeconds(300).toString());
        Element audienceRestriction = child(document, conditions, SAML_ASSERTION_NS, "saml:AudienceRestriction", null);
        child(document, audienceRestriction, SAML_ASSERTION_NS, "saml:Audience", audience);

        Element attributes = child(document, assertion, SAML_ASSERTION_NS, "saml:AttributeStatement", null);
        attribute(document, attributes, "preferred_username", username);
        attribute(document, attributes, "email", username + "@example.test");
        attribute(document, attributes, "displayName", "SAML User");
        attribute(document, attributes, "roles", role);

        sign(assertion);
        return Base64.getMimeEncoder().encodeToString(xml(document).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void sign(Element assertion) throws Exception {
        XMLSignatureFactory signatureFactory = XMLSignatureFactory.getInstance("DOM");
        Reference reference = signatureFactory.newReference(
            "#" + assertion.getAttribute("ID"),
            signatureFactory.newDigestMethod(DigestMethod.SHA256, null),
            List.of(signatureFactory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)),
            null,
            null
        );
        SignedInfo signedInfo = signatureFactory.newSignedInfo(
            signatureFactory.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE, (C14NMethodParameterSpec) null),
            signatureFactory.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
            List.of(reference)
        );
        KeyInfoFactory keyInfoFactory = signatureFactory.getKeyInfoFactory();
        KeyInfo keyInfo = keyInfoFactory.newKeyInfo(List.of(keyInfoFactory.newKeyValue(SAML_KEYS.getPublic())));
        DOMSignContext context = new DOMSignContext(SAML_KEYS.getPrivate(), assertion, assertion.getFirstChild());
        context.setDefaultNamespacePrefix("ds");
        signatureFactory.newXMLSignature(signedInfo, keyInfo).sign(context);
    }

    private static void attribute(Document document, Element parent, String name, String value) {
        Element attribute = child(document, parent, SAML_ASSERTION_NS, "saml:Attribute", null);
        attribute.setAttribute("Name", name);
        child(document, attribute, SAML_ASSERTION_NS, "saml:AttributeValue", value);
    }

    private static Element child(Document document, Element parent, String namespace, String qualifiedName, String text) {
        Element child = element(document, namespace, qualifiedName);
        if (text != null) {
            child.setTextContent(text);
        }
        parent.appendChild(child);
        return child;
    }

    private static Element element(Document document, String namespace, String qualifiedName) {
        return document.createElementNS(namespace, qualifiedName);
    }

    private static String xml(Document document) throws Exception {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot generate SAML test keys", ex);
        }
    }

    private static String publicKeyPem() {
        return "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)).encodeToString(SAML_KEYS.getPublic().getEncoded())
            + "\n-----END PUBLIC KEY-----";
    }
}
